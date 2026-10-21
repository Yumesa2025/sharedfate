package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.DragonPrepEffect;
import com.sharedfate.perk.effect.DragonPrepEffect.Kind;
import com.sharedfate.sync.DragonPerch;
import com.sharedfate.sync.DragonTrialManager;
import com.sharedfate.sync.DragonTrialSession;
import com.sharedfate.sync.MaxHealthAttribute;
import com.sharedfate.sync.StatMirror;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * 「드래곤전 대비」 열두 장이 끼어드는 자리를 한 곳에 모은 판정부(0.44.0-dev).
 *
 * <p>정의는 {@link DragonPrepEffect} 하나이고, 열두 장은 그 {@link Kind} 로 갈린다. 끼어드는 자리마다
 * 「이 팀이 이것을 가졌는가, 값은 얼마인가」를 따로 훑으면 같은 찾기가 열두 벌이 되므로 여기서만 찾는다.
 *
 * <h2>팀 하나에 한 번 — 인원수만큼 곱해지지 않는다</h2>
 * <p>이 저장소의 단골 버그는 「팀원 수만큼 도는 바퀴 안에서 팀 값을 한 번 더 건드려 인원수만큼 곱해지는
 * 것」이다. 그래서 여기의 효과는 둘로 나뉜다.
 * <ul>
 *   <li><b>사람마다 따로인 것</b> — 그 사람이 맞는 피해(바닥 감각) · 그 사람이 밀리는 거리(닻) · 그
 *       사람의 화살(화살통) · 그 사람이 때리는 피해(착지 노리기 · 하늘 사수). 팀 풀에 더하지 않으므로
 *       곱해질 것이 없다</li>
 *   <li><b>팀에 한 번인 것</b> — 불사의 맹세(팀 체력을 한 번 채움) · 결전의 함성(흡수는 팀 풀이 「가장 큰
 *       증가」로 접는다 — {@code StatMirror.mergeAbsorptionDelta}) · 엔드 적응(팀 최대 체력에 한 번 더함)
 *       · 첫 봉인 · 날개 꺾기(전투 상태에 한 번)</li>
 * </ul>
 */
public final class DragonPrep {
	/** 정의에서 값을 못 찾았을 때의 맹세 뒤 최대 체력 배율. 정의가 사라져도 「절반」은 지켜진다. */
	public static final double DEFAULT_OATH_SCALE = 0.5;
	/** 결전의 함성의 흡수 상태이상 길이(틱). 1분 30초 — 사람이 정한 값(2026-10-22, 처음 3분에서 줄임). */
	public static final int RALLY_DURATION_TICKS = 20 * 90;

	/**
	 * 클라이언트가 「내 팀이 화살통을 가졌는가」를 알려 주는 길. 서버에서는 쓰지 않는다.
	 *
	 * <p>활을 당기는 동작은 클라이언트가 먼저 {@code Player.getProjectile} 을 묻고 시작한다. 화살이 0 개인데
	 * 서버만 「쏠 수 있다」고 알면 사람 화면에서 활이 안 당겨진다 — {@code PerkFoodCapacity} 의 먹기와 같은
	 * 사정이라 같은 모양으로 둔다.
	 */
	private static volatile BooleanSupplier clientQuiver = () -> false;

	private DragonPrep() {
	}

	// ------------------------------------------------------------------ 찾기

	/** 이 팀이 가진 그 종류의 효과. 증강을 껐거나 없으면 {@code null}. 여러 장이면 먼저 얻은 것. */
	public static @Nullable DragonPrepEffect find(@Nullable TeamState state, Kind kind) {
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty() || kind == null) {
			return null;
		}
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof DragonPrepEffect prep && prep.kind() == kind) {
					return prep;
				}
			}
		}
		return null;
	}

	public static boolean held(@Nullable TeamState state, Kind kind) {
		return find(state, kind) != null;
	}

	/** 그 종류의 값. 없으면 {@code fallback}. */
	public static double value(@Nullable TeamState state, Kind kind, double fallback) {
		DragonPrepEffect effect = find(state, kind);
		return effect == null ? fallback : effect.value();
	}

	private static @Nullable TeamState stateOf(@Nullable Player player) {
		return player instanceof ServerPlayer ? TeamLookup.stateOf(player.getUUID()) : null;
	}

	// ------------------------------------------------------------------ 1. 불사의 맹세

	/**
	 * 이 팀의 최대 체력에 지금 걸 배율. 맹세를 쓴 뒤로 이 회차 내내 {@link #DEFAULT_OATH_SCALE}(정의 값)이다.
	 *
	 * <p>{@code PerkHealthRules.effectiveMaxHealth} 가 <b>보너스까지 더한 뒤에</b> 곱한다. 맹세 뒤에 체력
	 * 증강을 새로 골라도 「절반」이 유지되고, 저장되는 것은 「썼다」 한 비트라 서버를 껐다 켜도 같다.
	 */
	public static double oathScale(@Nullable TeamState state) {
		if (state == null || !state.undyingOathSpent) {
			return 1.0;
		}
		return value(state, Kind.UNDYING_OATH, DEFAULT_OATH_SCALE);
	}

	/** 지금 맹세가 죽음을 막을 수 있는가. */
	public static boolean oathAvailable(@Nullable TeamState state) {
		return state != null && !state.undyingOathSpent && held(state, Kind.UNDYING_OATH);
	}

	/**
	 * 공유 풀이 0 이 됐을 때(여럿이 같은 바퀴에 맞아 합이 체력을 넘은 경우). {@code StatMirror.tick} 이
	 * 팀을 죽이기 직전에 부른다.
	 *
	 * @return 막았으면 참. 그때 {@code state.health} 는 새 최대치로 차 있고 팀원 몸에도 써 두었다
	 */
	public static boolean tryUndyingOath(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable TeamState state, @Nullable List<ServerPlayer> online) {
		if (server == null || team == null || !oathAvailable(state) || online == null || online.isEmpty()) {
			return false;
		}
		fulfilOath(server, team, state, online);
		return true;
	}

	/**
	 * 한 대에 죽는 피해(팀원 하나의 체력이 그 자리에서 0 이 됨). {@code LivingEntityUndyingOathMixin} 이
	 * 바닐라의 불사의 토템 판정이 <b>거짓으로 끝난 뒤</b>에 부른다 — 손에 든 토템이 먼저 쓰이고, 맹세는
	 * 마지막 수단이다.
	 *
	 * <p>공허({@code out_of_world}) · {@code /kill}({@code generic_kill}) 처럼 무적을 지나는 피해는 막지
	 * 않는다. 토템도 그것을 못 막는다 — 공허에서 살려 두면 다음 틱에 또 죽어 맹세만 버린다.
	 */
	public static boolean tryUndyingOath(@Nullable ServerPlayer victim, @Nullable DamageSource source) {
		if (victim == null || (source != null && source.is(DamageTypeTags.BYPASSES_INVULNERABILITY))) {
			return false;
		}
		MinecraftServer server = victim.level().getServer();
		if (server == null) {
			return false;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(victim.getUUID());
		TeamState state = manager.stateOf(victim.getUUID());
		if (team == null || !oathAvailable(state)) {
			return false;
		}
		List<ServerPlayer> online = new ArrayList<>();
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			// 맞은 사람은 체력이 0 이라 「죽어 가는 중」으로 보인다. 그 사람은 빼지 않는다.
			if (player != null && !player.isRemoved() && (player == victim || !player.isDeadOrDying())) {
				online.add(player);
			}
		}
		fulfilOath(server, team, state, online);
		// 공유 풀을 팀원에게 써 주고 직전 스냅샷까지 맞춘다. 안 맞추면 다음 바퀴가 0 → 가득을 「회복」으로,
		// 다른 팀원의 그 틱 피해를 다시 세어 방금 채운 체력을 깎는다.
		for (ServerPlayer player : online) {
			StatMirror.syncPlayerNow(team.teamId(), state, player);
		}
		return true;
	}

	/**
	 * 맹세를 실제로 쓴다. 팀에 한 번 — 「썼다」를 적고, 최대 체력을 절반으로, 체력을 가득.
	 *
	 * <p><b>무적은 주지 않는다</b>(사람 결정). 같은 틱에 또 맞으면 그대로 들어간다.
	 */
	private static void fulfilOath(MinecraftServer server, ShareTeam team, TeamState state,
			List<ServerPlayer> online) {
		state.undyingOathSpent = true;
		float maximum = PerkHealthRules.effectiveMaxHealth(state);
		state.maxHealth = maximum;
		state.health = maximum;
		TeamManager.get(server).setDirty();
		for (ServerPlayer player : online) {
			MaxHealthAttribute.apply(player, maximum);
			player.setHealth(maximum);
			// 바닐라 토템 연출(입자 · 소리 · 화면 가운데 토템). 받는 클라이언트가 스스로 낸다.
			player.level().broadcastEntityEvent(player, (byte) 35);
			player.sendSystemMessage(Component.literal(
					"[증강] 「불사의 맹세」가 죽음을 막았습니다. 이번 회차 동안 최대 체력이 절반이 됩니다."));
		}
		SharedFateMod.LOGGER.info("[PERK] 팀 '{}' — 불사의 맹세로 전멸을 막았습니다. 최대 체력 {}",
				team.name(), maximum);
	}

	// ------------------------------------------------------------------ 2. 바닥 감각

	/**
	 * 바닥 표식형 시련 공격이 이 사람에게 넣을 피해. 그 공격들의 {@code hurtServer} 자리가 부른다.
	 *
	 * <p>대상(사람 결정 2026-10-22) — 연쇄 포격 · 자리 폭격 · 낙뢰 · 종말의 비(카드 · 폭풍 날갯짓) ·
	 * 상시 번개 · 십자 균열. <b>공허 브레스 · 공허 흡입(불 결계 · 터짐)은 넣지 않는다</b> — 바닥에 면이나
	 * 원이 깔리기는 하지만 드래곤이 직접 쏘는 패턴이라 이 증강의 대상이 아니다.
	 *
	 * <p>사람마다 따로다 — 같은 자리에 둘이 서 있으면 각자 깎인 값을 맞는다.
	 */
	public static float floorStrike(@Nullable ServerPlayer victim, float damage) {
		return scaled(damage, value(stateOf(victim), Kind.FLOOR_SENSE, 1.0));
	}

	/** 피해에 배율을 곱한다. 배율이 이상하면 원래 값. */
	static float scaled(float damage, double multiplier) {
		if (!(multiplier > 0.0) || !Double.isFinite(multiplier) || !Float.isFinite(damage)) {
			return damage;
		}
		return (float) (damage * multiplier);
	}

	// ------------------------------------------------------------------ 3. 무뎌지지 않는 갑주

	/** 이 팀이 이 물건의 내구도를 지키는가 — 방어구 넷과 방패. */
	public static boolean keepsDurability(@Nullable TeamState state, @Nullable ItemStack stack) {
		return stack != null && !stack.isEmpty() && isArmorOrShield(stack)
				&& held(state, Kind.UNWORN_ARMOR);
	}

	/**
	 * 방어구이거나 방패인가 — 컴포넌트로 가른다.
	 *
	 * <ul>
	 *   <li>방패 — 막기 컴포넌트({@code minecraft:blocks_attacks})</li>
	 *   <li>방어구 — 착용 컴포넌트의 칸이 사람 방어구 칸(머리·몸통·다리·발). 거북 등딱지도 든다</li>
	 *   <li>겉날개는 몸통 칸에 입지만 활공 컴포넌트가 있어 뺀다 — 방어구가 아니라 비행 도구다.
	 *       늑대·말 갑옷은 동물 칸이라 빠진다</li>
	 * </ul>
	 * <p>태그({@code #chest_armor} 등)로 묻지 않는 것은, 태그는 데이터팩이 늘리고 줄일 수 있는 값이라
	 * 「입으면 닳는 것」이라는 뜻과 어긋날 수 있어서다. 내구도가 있는지는 닳는 자리가 이미 정한다.
	 */
	static boolean isArmorOrShield(ItemStack stack) {
		if (stack.has(DataComponents.BLOCKS_ATTACKS)) {
			return true;
		}
		Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
		return equippable != null
				&& equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR
				&& !stack.has(DataComponents.GLIDER);
	}

	// ------------------------------------------------------------------ 4. 엔더 동화

	/**
	 * 엔더맨이 이 사람의 시선을 「쳐다봤다」로 세지 않는가. {@code EndermanGazeMixin} 이 부른다.
	 *
	 * <p>시선 판정 하나만 끈다. 때려서 화가 난 엔더맨 · 「밤의 군세」가 덤비게 한 엔더맨은 바닐라의
	 * {@code isAngryAt} 쪽이라 그대로 덤빈다.
	 */
	public static boolean ignoresGaze(@Nullable Player player) {
		return held(stateOf(player), Kind.ENDER_KINSHIP);
	}

	// ------------------------------------------------------------------ 5. 닻

	/**
	 * 시련 공격이 이 사람을 미는(당기는) 거리에 곱할 값. 1.0 이면 그대로다.
	 *
	 * <p>미는 거리를 계산한 <b>뒤</b>(천장과 땅 확인을 다 지난 뒤) 곱한다. 줄이기만 하므로 그 천장들이
	 * 지키던 「섬 밖으로 안 밀린다」가 그대로 지켜진다.
	 */
	public static double knockbackScale(@Nullable ServerPlayer player) {
		double multiplier = value(stateOf(player), Kind.ANCHOR, 1.0);
		return multiplier > 0.0 && multiplier <= 1.0 ? multiplier : 1.0;
	}

	// ------------------------------------------------------------------ 6. 첫 봉인

	/** 「첫 크리스탈」 자리를 시련 없이 넘기는가. {@code DragonTrialManager.detectTriggers} 가 묻는다. */
	public static boolean sealsFirstCrystal(@Nullable TeamState state) {
		return held(state, Kind.FIRST_SEAL);
	}

	// ------------------------------------------------------------------ 7·8. 착지 노리기 · 하늘 사수

	/**
	 * 드래곤에게 들어가는 이 한 방에 곱할 값. {@code PerkDamage.scale} 이 부른다.
	 *
	 * <p>시련 전투 중(때린 사람 팀의 세션이 열려 있고 멈추지 않음)에만 걸린다. 판정은
	 * {@link #dragonHitFactor(boolean, boolean, boolean, boolean, double, double)} 하나다.
	 */
	public static double dragonHitFactor(@Nullable Entity victim, @Nullable DamageSource source) {
		if (!(victim instanceof EnderDragon) || source == null
				|| !(source.getEntity() instanceof ServerPlayer attacker)
				|| victim.level().dimension() != Level.END) {
			return 1.0;
		}
		MinecraftServer server = attacker.level().getServer();
		if (server == null) {
			return 1.0;
		}
		ShareTeam team = TeamManager.get(server).teamOf(attacker.getUUID());
		TeamState state = TeamManager.get(server).stateOf(attacker.getUUID());
		if (team == null || state == null) {
			return 1.0;
		}
		double landing = value(state, Kind.LANDING_AMBUSH, 1.0);
		double sky = value(state, Kind.SKY_ARCHER, 1.0);
		if (landing == 1.0 && sky == 1.0) {
			return 1.0;
		}
		return dragonHitFactor(DragonTrialManager.fighting(team.teamId()), DragonPerch.seatedNow(),
				source.is(DamageTypeTags.IS_PLAYER_ATTACK), source.is(DamageTypeTags.IS_PROJECTILE),
				landing, sky);
	}

	/**
	 * 순수 판정.
	 *
	 * <ul>
	 *   <li>착지 노리기 — 앉아 있는 드래곤에게 근접. 최후의 저항에서는 앉은 깃발이 내려가 있어
	 *       ({@code DragonPerch.standDown}) 저절로 빠진다</li>
	 *   <li>하늘 사수 — 앉지 않은 드래곤에게 투사체. 최후의 저항의 공중에 붙박인 드래곤도 「날고 있다」다</li>
	 * </ul>
	 */
	static double dragonHitFactor(boolean fighting, boolean perched, boolean melee, boolean projectile,
			double landing, double sky) {
		if (!fighting) {
			return 1.0;
		}
		if (perched && melee) {
			return landing;
		}
		if (!perched && projectile) {
			return sky;
		}
		return 1.0;
	}

	// ------------------------------------------------------------------ 9. 마르지 않는 화살통

	/** 클라이언트가 자기 팀의 화살통 보유를 알려 줄 길을 건다. */
	public static void setClientQuiver(@Nullable BooleanSupplier supplier) {
		clientQuiver = supplier == null ? () -> false : supplier;
	}

	/**
	 * 지금 이 사람의 일반 화살이 줄지 않는가 — 엔드에 있고 팀이 화살통을 가졌다.
	 *
	 * <p>서버는 팀 상태로, 클라이언트(자기 자신만)는 서버가 보낸 표시로 답한다. 두 쪽이 같은 답을 내야
	 * 화살 0 개에서 활이 당겨진다.
	 */
	public static boolean quiverActive(@Nullable Player player) {
		if (player == null || player.level().dimension() != Level.END) {
			return false;
		}
		if (player instanceof ServerPlayer) {
			return held(stateOf(player), Kind.ENDLESS_QUIVER);
		}
		return player.level().isClientSide() && player.isLocalPlayer() && clientQuiver.getAsBoolean();
	}

	/** 이 탄이 「줄지 않는 일반 화살」인가. 촉 화살 · 분광 화살 · 폭죽은 그대로 줄어든다. */
	public static boolean endlessArrow(@Nullable Player player, @Nullable ItemStack ammo) {
		return ammo != null && ammo.is(Items.ARROW) && quiverActive(player);
	}

	// ------------------------------------------------------------------ 10. 날개 꺾기

	/**
	 * 착지한 드래곤을 붙들어 둘 틱. 팀원 중 누구의 팀이라도 날개 꺾기를 가졌으면 그 값이다.
	 *
	 * <p>시련 세션은 한 번에 하나라 엔드의 팀원은 한 팀이다. 첫 사람의 팀만 보면 된다.
	 */
	public static int perchHoldTicks(@Nullable List<ServerPlayer> members, int base) {
		if (members == null || members.isEmpty()) {
			return base;
		}
		double seconds = value(stateOf(members.getFirst()), Kind.WING_BREAK, 0.0);
		return perchHoldTicks(base, seconds);
	}

	/** 순수 판정. 줄이는 값은 받지 않는다 — 「날개 꺾기」가 착지를 짧게 만들면 이름과 거꾸로다. */
	static int perchHoldTicks(int base, double seconds) {
		if (!(seconds > 0.0) || !Double.isFinite(seconds)) {
			return base;
		}
		return Math.max(base, (int) Math.round(seconds * 20.0));
	}

	// ------------------------------------------------------------------ 11. 엔드 적응

	/** 이 팀의 최대 체력에 더할 엔드 몫. 팀원이 엔드에 없거나 증강이 없으면 0. */
	public static double endBonus(@Nullable TeamState state) {
		if (state == null || !state.presentInEnd) {
			return 0.0;
		}
		return value(state, Kind.END_ADAPTATION, 0.0);
	}

	/**
	 * 팀마다 「접속한 팀원이 엔드에 있는가」를 다시 적는다. {@code PerkHealthRules.tick} 이 최대 체력을
	 * 다시 세기 직전에 부른다.
	 *
	 * @return 하나라도 바뀌었으면 참
	 */
	public static boolean refreshEndPresence(MinecraftServer server, ShareTeam team, TeamState state) {
		boolean inEnd = false;
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (player != null && !player.isSpectator() && player.level().dimension() == Level.END) {
				inEnd = true;
				break;
			}
		}
		boolean changed = state.presentInEnd != inEnd;
		state.presentInEnd = inEnd;
		return changed;
	}

	// ------------------------------------------------------------------ 12. 결전의 함성

	/**
	 * 최후의 저항이 <b>처음</b> 열린 틱. {@code DragonTrialManager} 가 {@code HEALTH_30} 이 처음 터진 그 틱에만
	 * 부른다 — 그 자리는 세션에 저장되므로 일시 중단 뒤 이어 가도, 서버를 다시 켜도 다시 터지지 않는다.
	 *
	 * <p>흡수 상태이상을 건다. 공유가 켜진 서버에서는 한 사람에게만 걸면 바닐라 공유가 팀원에게 퍼뜨리고,
	 * 꺼진 서버에서는 엔드의 팀원 각자에게 건다. 어느 쪽이든 공유 흡수는 「가장 큰 증가」로 접혀
	 * ({@code StatMirror.mergeAbsorptionDelta}) <b>팀에 한 번 12</b>다 — 사람 수만큼 쌓이지 않는다.
	 */
	public static void onLastStandBegins(@Nullable ShareTeam team, @Nullable TeamState state,
			@Nullable List<ServerPlayer> members) {
		if (team == null || members == null || members.isEmpty()) {
			return;
		}
		double amount = value(state, Kind.LAST_RALLY, 0.0);
		int amplifier = rallyAmplifier(amount);
		if (amplifier < 0) {
			return;
		}
		boolean shared = SharedFateMod.config != null && SharedFateMod.config.shareStatusEffects;
		List<ServerPlayer> standing = new ArrayList<>();
		for (ServerPlayer member : members) {
			if (!member.isSpectator() && !member.isDeadOrDying()) {
				standing.add(member);
			}
		}
		if (standing.isEmpty()) {
			return;
		}
		// 공유가 켜졌으면 한 사람에게만 건다 — 나머지는 EffectSync 가 퍼뜨린다. 꺼졌으면 각자에게.
		for (ServerPlayer member : shared ? standing.subList(0, 1) : standing) {
			member.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, RALLY_DURATION_TICKS, amplifier));
		}
		Component notice = Component.literal(
				"[증강] 「결전의 함성」 — 팀이 흡수 체력 " + (int) amount + "을(를) 얻었습니다.");
		for (ServerPlayer member : standing) {
			member.sendSystemMessage(notice);
		}
		if (standing.getFirst().level() instanceof ServerLevel level) {
			com.sharedfate.sync.TrialWarning.playEach(level, standing, SoundEvents.RAID_HORN, 1.0F, 1.2F);
		}
		SharedFateMod.LOGGER.info("[PERK] 팀 '{}' — 결전의 함성으로 흡수 {}", team.name(), amount);
	}

	/** 흡수 체력을 흡수 상태이상 단계로. 한 단계가 4 다. 줄 것이 없으면 -1. */
	static int rallyAmplifier(double amount) {
		if (!(amount >= 4.0) || !Double.isFinite(amount)) {
			return -1;
		}
		return (int) Math.floor(amount / 4.0) - 1;
	}

	/** 이 세션이 지금 시련 전투 중인가 — 시련을 켰고 멈추지 않았다. 시험이 세션만으로 굴린다. */
	public static boolean fighting(@Nullable DragonTrialSession session) {
		return session != null && session.trialsEnabled() && !session.suspended();
	}
}
