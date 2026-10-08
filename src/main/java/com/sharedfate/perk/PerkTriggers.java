package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.HungerOnDamageEffect;
import com.sharedfate.perk.effect.OnCriticalEffect;
import com.sharedfate.perk.effect.OnTeamHurtEffect;
import com.sharedfate.sync.SpreadDamageManager;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 사건이 일어났을 때 잠깐 효과를 얹는 증강들의 실행부.
 *
 * <p>{@code on_team_hurt} 와 {@code on_critical} 이 여기를 지난다. 각 효과 클래스는 "무엇을
 * 얼마 동안 얹는가"만 들고 있고, "언제 누구에게"는 전부 여기서 정한다.
 *
 * <p>{@code hunger_on_damage} 도 여기를 지난다. 이쪽은 효과를 얹는 것이 아니라 <b>공유 허기
 * 풀을 직접 채우는</b> 유일한 경로다. 자세한 규칙은 {@link #fillSharedHunger} 에 적어 뒀다.
 *
 * <h2>보유 증강과 세트를 <b>둘 다</b> 훑는다</h2>
 * <p>{@code ownedPerks} 를 훑는 자리마다 {@link PerkSetEffects#activeEffectsOf} 도 이어서
 * 훑는다. 그 한 줄을 빠뜨리면 그 자리가 소비하는 효과 형은 세트에서 아무 일도 하지 않는다 —
 * 빌드도 통과하고 로그도 남지 않는다. 생존 3단계({@code hunger_on_damage})가 그 길로 들어온다.
 *
 * <h2>공유 체력과의 관계</h2>
 * <p>여기서 얹는 것은 상태이상과 속성 수정자뿐이라 체력 공유 풀을 직접 건드리지 않는다.
 * 팀원 넷에게 저항을 걸어도 늘어나는 것은 각자의 저항이지 공유 풀이 아니므로,
 * "공유 상태이상이 팀 인원수만큼 배수로 들어가던" 문제와 같은 함정에 빠지지 않는다.
 *
 * <p>다만 여기서 회복이나 피해를 주는 상태이상(재생·독 등)을 얹으면 그때부터는 공유 상태이상의
 * 문제가 된다. 그 경우에도 {@code SharedEffectDamage} 가 상태이상 틱 구간에서 대표 한 명 것만
 * 남기므로 1인분으로 유지된다. 여기서 따로 할 일은 없다.
 *
 * <p>허기만은 예외로 공유 풀을 직접 건드린다. 맞은 사람의 {@code FoodData} 를 채우면
 * {@code StatMirror} 가 그 변화량을 관측해 공유 풀에 <b>한 번 더</b> 더하기 때문이다. 이 클래스는
 * {@link PerkKillRewards#applyToPool} 과 똑같은 규칙으로 더한다.
 *
 * <h2>증강이 없으면 바닐라 그대로</h2>
 * <p>두 진입점 모두 팀 → 증강 사용 여부 → 보유 증강 순으로 먼저 걸러 낸다. 팀에 속하지 않은
 * 플레이어와 증강을 쓰지 않는 팀은 첫 몇 줄에서 되돌아 나가므로 피해·공격 경로에 사실상
 * 아무 부담도 얹히지 않는다.
 */
public final class PerkTriggers {
	/**
	 * 허기 상한. {@code FoodData}·{@code TeamState.sanitize}·{@link PerkKillRewards} 가 쓰는 값과
	 * 같다.
	 */
	static final int MAX_FOOD = 20;

	/**
	 * 팀별로 아직 한 칸이 되지 못한 허기의 나머지.
	 *
	 * <p>「피해 1당 0.4」는 정수로 떨어지지 않는데 {@code TeamState.foodLevel} 은 정수다. 매번
	 * 버리면 피해 2 이하는 아무 일도 일어나지 않고, 매번 올림하면 한 대에 한 칸씩 차서 값이
	 * 몇 배가 된다. 그래서 남는 소수를 팀별로 들고 있다가 1 이 될 때 한 칸을 준다. 저장하지
	 * 않으므로 서버를 껐다 켜면 비어 있고, 잃는 것은 언제나 1 칸 미만이다.
	 *
	 * <p>서버 스레드에서만 오간다. 그래도 접속 종료 처리와 겹칠 수 있어 잠그고 쓴다.
	 * 키는 {@code ShareTeam.teamId} 라 팀 하나에 값도 하나다 — <b>팀원마다 따로 쌓이지
	 * 않는다.</b> 여기에 사람 UUID 를 넣으면 인원수만큼 허기가 차는 바로 그 사고가 난다.
	 */
	private static final Map<UUID, Float> HUNGER_CARRY = new HashMap<>();

	private static volatile boolean warned;

	private PerkTriggers() {
	}

	// ------------------------------------------------------------------ 팀원 피격

	/**
	 * {@code ServerLivingEntityEvents.AFTER_DAMAGE} 에 붙는 지점.
	 *
	 * <p>피해가 들어가는 모든 자리를 지나므로 어떤 예외도 밖으로 내보내지 않는다.
	 *
	 * <h2>「완충」을 가진 팀 — 처음 맞은 순간에 한 번</h2>
	 * <p>{@code damageTaken} 은 그대로 믿지 않고 {@link SpreadDamageManager#hurtTaken} 으로 고쳐
	 * 읽는다. 완충이 미룬 첫 피해는 {@code damageTaken} 이 0 으로 오고, 그 뒤 1초마다 들어오는 몫은
	 * 몫 크기로 온다. 그대로 믿으면 <b>맞은 순간엔 안 돌고 몫마다 돌아</b> — 동병상련(2초 저항 II)이
	 * 몫마다 갱신돼 약 9초 내내 유지되고 그동안의 몫·다른 피해가 40% 깎였다. 반격(8초 힘 II)은 약
	 * 16초였다(2026-10-06 검토에서 확정된 문제). 지금은 몫이면 건너뛰고, 미룬 첫 피해면 미룬
	 * 양으로 센다. 허기({@code hunger_on_damage})도 미룬 양으로 맞은 순간에 한 번 찬다 — 바닐라에서
	 * 그 한 대를 맞았을 때와 같은 양이고, 처치로 남은 몫이 지워져도 이미 찬 허기는 그대로다.
	 */
	public static void onDamage(LivingEntity victim, DamageSource source,
			float baseDamageTaken, float damageTaken, boolean blocked) {
		try {
			teamHurt(victim, SpreadDamageManager.hurtTaken(victim,
					damageBasis(baseDamageTaken, damageTaken)), blocked);
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/**
	 * 이 사건을 「얼마만큼의 피해」로 셀 것인가.
	 *
	 * <p><b>감산 뒤 실제로 들어간 피해({@code damageTaken})다.</b> 방어구·저항·흡수를 모두 지난
	 * 값이라 공유 체력에서 실제로 깎인 양과 정확히 같다. {@code baseDamageTaken} 은 그 앞의 원래
	 * 피해라 여기서는 쓰지 않는다.
	 */
	static float damageBasis(float baseDamageTaken, float damageTaken) {
		return damageTaken;
	}

	private static void teamHurt(LivingEntity victim, float damageTaken, boolean blocked) {
		if (blocked || !(damageTaken > 0.0F) || !Float.isFinite(damageTaken)
				|| !(victim instanceof ServerPlayer hurt)) {
			return;
		}
		MinecraftServer server = hurt.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(hurt.getUUID());
		TeamState state = manager.stateOf(hurt.getUUID());
		if (team == null || state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return;
		}

		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof OnTeamHurtEffect onHurt) {
					grantToTeam(server, team, hurt, onHurt);
				}
			}
		}
		// 보유 증강을 훑은 바로 뒤에 세트도 훑는다. 이 줄이 없으면 세트의 on_team_hurt 는
		// 빌드도 통과하고 로그도 없는 채로 아무 일도 하지 않는다.
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) {
			if (effect instanceof OnTeamHurtEffect onHurt) {
				grantToTeam(server, team, hurt, onHurt);
			}
		}

		fillSharedHunger(manager, team, state, damageTaken);
	}

	/**
	 * 접속해 있고 살아 있는 팀원 전원에게 얹는다.
	 *
	 * <p>맞은 본인을 포함할지는 정의가 정한다. 기본은 포함이다. 체력을 공유하므로 한 명이
	 * 맞으면 팀 전체의 체력이 깎이고, 그러면 맞은 본인에게도 "팀원이 맞았다"가 일어난 것이다.
	 */
	private static void grantToTeam(MinecraftServer server, ShareTeam team, ServerPlayer hurt,
			OnTeamHurtEffect effect) {
		for (UUID member : team.members()) {
			if (!effect.includesVictim() && member.equals(hurt.getUUID())) {
				continue;
			}
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online != null && !online.isRemoved() && !online.isDeadOrDying()) {
				effect.grantTo(online);
			}
		}
	}

	// ------------------------------------------------------------------ 피격으로 차는 허기

	/**
	 * {@code hunger_on_damage} 가 약속한 만큼 <b>팀 공유 허기 풀</b>을 채운다.
	 *
	 * <p>{@code hurt.getFoodData().eat(...)} 을 부르면 안 된다. 개인 허기가 움직인 만큼
	 * {@code StatMirror} 가 그것을 관측해 공유 풀에 <b>한 번 더</b> 더하기 때문이다. 그래서
	 * {@link TeamState#foodLevel} 만 직접 올리고 팀원 개인에게는 손대지 않는다. 개인 값이
	 * 그대로면 {@code StatMirror} 가 보는 변화량이 0 이라 공유 풀에 아무것도 더해지지 않고,
	 * 같은 틱 끝의 {@code writeBack} 이 우리가 올려 둔 공유 값을 팀 전원에게 그대로 써 준다.
	 *
	 * <h2>팀 인원수만큼 곱해지지 않는 이유</h2>
	 * <p>여기에는 <b>팀원을 도는 고리가 없다.</b> {@link #grantToTeam} 과 달리 사건 하나에
	 * {@code TeamState} 를 정확히 한 번 건드린다. 팀이 넷이든 하나든 「누가 한 대 맞았다」는
	 * 사건 한 번이고 공유 풀도 한 번만 움직인다.
	 *
	 * <p>같은 원인이 팀원 수만큼 복제되는 경우 — 공유된 독·화상 같은 상태이상 피해 — 는
	 * {@code SharedEffectDamage} 가 피해 진입점에서 대표 한 명만 남기고 나머지를 버리므로
	 * {@code AFTER_DAMAGE} 까지 올라오는 사건 자체가 하나다. 팀원 둘이 각각 좀비에게 맞았다면
	 * 그건 진짜로 두 번이라 두 번 차는 것이 맞다.
	 */
	private static void fillSharedHunger(TeamManager manager, ShareTeam team, TeamState state,
			float damageTaken) {
		// 공유 체력이 이미 0 이면 전멸 처리가 도는 중이다. 그 위에 회복을 얹지 않는다.
		if (!(state.health > 0.0F)) {
			return;
		}
		float gain = hungerGainFor(state, damageTaken);
		if (!(gain > 0.0F)) {
			return;
		}
		int food = takeWholeHunger(team.teamId(), gain);
		if (food <= 0) {
			return;
		}
		applyHungerToPool(state, food);
		manager.setDirty();
	}

	/**
	 * 이 팀이 가진 {@code hunger_on_damage} 들이 이 피해로 채우는 허기의 합.
	 *
	 * <p>여러 개를 가졌으면 전부 더한다.
	 *
	 * <p>보유 증강을 훑은 뒤 <b>세트도 이어서 훑는다.</b> 생존 3단계가 그 길로 들어온다.
	 *
	 * @param damageTaken 감산 뒤 실제로 들어간 피해. 기준을 고르는 자리는 {@link #damageBasis}
	 */
	static float hungerGainFor(@Nullable TeamState state, float damageTaken) {
		if (state == null || state.ownedPerks.isEmpty()) {
			return 0.0F;
		}
		float total = 0.0F;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			total += hungerGainOf(perk.effects(), damageTaken);
		}
		total += hungerGainOf(PerkSetEffects.activeEffectsOf(state), damageTaken);
		return Float.isFinite(total) ? Math.max(0.0F, total) : 0.0F;
	}

	/**
	 * 효과 목록만 보는 순수 계산.
	 */
	static float hungerGainOf(@Nullable Iterable<PerkEffect> effects, float damageTaken) {
		if (effects == null || !(damageTaken > 0.0F) || !Float.isFinite(damageTaken)) {
			return 0.0F;
		}
		float total = 0.0F;
		for (PerkEffect effect : effects) {
			if (effect instanceof HungerOnDamageEffect hunger) {
				total += hunger.hungerFor(damageTaken);
			}
		}
		return total;
	}

	/**
	 * 소수로 나온 허기를 정수 칸으로 바꾼다. 남는 소수는 이 팀 몫으로 들고 간다.
	 *
	 * <p>피해 3 에 0.4 면 1.2 다. 이번에 1 칸을 주고 0.2 를 남겨 두면, 같은 피해를 다섯 번 받는
	 * 동안 정확히 6 칸이 찬다. 「피해 1당 0.4」가 긴 눈으로 보면 그대로 지켜진다.
	 *
	 * <p>남는 값은 언제나 1 미만이라 끝없이 불어나지 않는다. 표의 크기는 팀 수로 묶인다.
	 */
	static int takeWholeHunger(@Nullable UUID teamId, float gain) {
		if (teamId == null || !(gain > 0.0F) || !Float.isFinite(gain)) {
			return 0;
		}
		synchronized (HUNGER_CARRY) {
			float carried = HUNGER_CARRY.getOrDefault(teamId, 0.0F) + gain;
			if (!Float.isFinite(carried) || carried <= 0.0F) {
				HUNGER_CARRY.remove(teamId);
				return 0;
			}
			int whole = (int) Math.min(MAX_FOOD, Math.floor(carried));
			float rest = carried - whole;
			if (rest > 0.0F && rest < 1.0F) {
				HUNGER_CARRY.put(teamId, rest);
			} else {
				HUNGER_CARRY.remove(teamId);
			}
			return whole;
		}
	}

	/**
	 * 허기를 공유 풀에 더한다.
	 *
	 * <p>허기는 20 까지다. 포만감은 건드리지 않는다 — {@code TeamState.sanitize} 가 포만감을
	 * 허기 이하로 다시 맞춰 준다.
	 */
	static void applyHungerToPool(TeamState state, int food) {
		if (state == null || food <= 0) {
			return;
		}
		state.foodLevel = Math.max(0, Math.min(MAX_FOOD, state.foodLevel + food));
	}

	// ------------------------------------------------------------------ 치명타

	/**
	 * {@link com.sharedfate.mixin.ServerPlayerCritMixin} 이 부르는 지점.
	 *
	 * <p>치명타로 실제 피해를 입힌 순간이다. 때린 본인에게만 얹는다.
	 */
	public static void onCriticalHit(@Nullable ServerPlayer attacker) {
		try {
			critical(attacker);
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	private static void critical(@Nullable ServerPlayer attacker) {
		if (attacker == null || attacker.isRemoved()) {
			return;
		}
		TeamState state = com.sharedfate.team.TeamLookup.stateOf(attacker.getUUID());
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return;
		}
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof OnCriticalEffect onCritical) {
					onCritical.grantTo(attacker);
				}
			}
		}
		// 보유 증강을 훑은 바로 뒤에 세트도 훑는다.
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) {
			if (effect instanceof OnCriticalEffect onCritical) {
				onCritical.grantTo(attacker);
			}
		}
	}

	// ------------------------------------------------------------------ 도우미

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"방아쇠형 증강을 처리하지 못해 이번에는 건너뜁니다. 이 경고는 한 번만 남습니다.", error);
	}

	/** 테스트가 상태를 격리할 때 쓴다. */
	static void resetForTesting() {
		warned = false;
		synchronized (HUNGER_CARRY) {
			HUNGER_CARRY.clear();
		}
	}
}
