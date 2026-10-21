package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.LegacyGearEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.SharedItemList;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code legacy_gear} 증강(프리즘 「유산」)의 전멸 시점 승계분 스냅샷을 실행한다.
 *
 * <h2>고를 때는 아무것도 빼앗지 않는다</h2>
 * <p>이 증강은 대가가 없다. <b>전멸하는 그 순간</b>에 {@link #captureAtDeath}가 스냅샷을 떠서
 * 다음 회차로 넘길 물건을 정하는 일만 하고, {@link TeamState#legacyGear}만 채울 뿐 인벤토리는
 * 건드리지 않는다.
 *
 * <h2>무엇을 훑는가</h2>
 * <ul>
 *   <li>{@link TeamState#mainItems}·{@link TeamState#extraItems}·공유 엔더상자 —
 *       {@link LegacyGearEffect#matches}에 걸리는 스택을 전부 가져간다. 손에 쥔 도구도 여기
 *       걸린다 — 마인핸드 선택 칸이 {@code mainItems} 안에 있기 때문이다.</li>
 *   <li>{@link TeamState#equipment} — 방어구 HEAD·CHEST·LEGS·FEET 네 칸은 판정 없이 통째로,
 *       오프핸드처럼 아무거나 들어갈 수 있는 칸은 판정을 거쳐서 가져간다
 *       ({@link #collectEquipment}).</li>
 * </ul>
 *
 * <h2>스냅샷은 「죽는 순간」이 아니라 「인벤토리를 쏟기 직전」에 떠야 한다</h2>
 * <p>{@code ServerLivingEntityEvents.AFTER_DEATH}는 이름과 달리 <b>인벤토리가 이미 바닥에
 * 쏟아진 뒤</b>에 발화한다. {@code LivingEntity.die} 안에서
 * {@code dropAllDeathLoot → Player.dropEquipment → Inventory.dropAll} 이 먼저 돌고, Fabric 은
 * 그보다 뒤인 {@code Level.broadcastEntityEvent} 자리에 이 이벤트를 끼워 넣기 때문이다.
 * 그런데 이 모드는 {@code InventoryMixin} 이 {@code Inventory.items} 자체를
 * {@link TeamState#mainItems}로 갈아 끼워 두었으므로, 바닐라의 {@code Inventory.dropAll} 은
 * <b>공유 인벤토리 36칸을 직접 비운다.</b> 그래서 {@code AFTER_DEATH}에서 스냅샷을 뜨면
 * 곡괭이·도끼·삽은 물론 손에 든 무기까지 이미 사라진 뒤라 하나도 안 잡힌다
 * (착용 중인 방어구만 남는다 — {@code TeamAwareEquipment.dropAll} 이 공유 장비일 때는 아무
 * 일도 하지 않아서다. 「방어구만 넘어오고 도구는 안 넘어온다」의 정체가 이것이다).
 *
 * <p>그래서 진짜 스냅샷 지점은 {@link #captureBeforeDrop} 이다 — 바닐라가 쏟기 직전,
 * 공유 인벤토리가 아직 온전한 자리다. {@link #onDeath}는 <b>{@code keepInventory} 가 켜진
 * 서버를 위한 남은 길</b>이다. 그 경우 {@code Player.dropEquipment} 가 쏟는 가지 자체를
 * 건너뛰어 {@link #captureBeforeDrop} 이 아예 안 불리는데, 대신 아무것도 비워지지 않으므로
 * {@code AFTER_DEATH} 시점의 스냅샷이 정확하다.
 *
 * <h2>한 전멸에 스냅샷은 한 번뿐이다</h2>
 * <p>전멸 하나에 팀원 여럿이 죽고({@code DeathHandler}가 나머지 팀원도 {@code die}를 불러
 * 연쇄시킨다) 위의 두 지점이 모두 지나갈 수 있어, 같은 전멸에서 스냅샷 요청이 여러 번 들어온다.
 * <b>맨 처음(아직 아무것도 지워지지 않은) 요청에서만 떠야 한다.</b> 이후 요청은 이미 비워진
 * 상태를 볼 수 있어 그대로 두면 방금 뜬 정확한 스냅샷을 빈 목록으로 덮어써 버린다. 이걸
 * 막으려고 팀마다 "이번 전멸에서 이미 스냅샷을 떴는가"를 게임 시각(틱)으로 표시해 둔다
 * ({@link #LAST_CAPTURE_TICK}) — 같은 전멸의 연쇄 죽음은 전부 같은 틱 안에서 동기적으로
 * 일어나므로, 게임 시각이 다르면 새로운 전멸이라는 뜻이다.
 *
 * <h2>인챈트·이름·내구도는 어떻게 따라오는가</h2>
 * <p>스택을 새로 만들지 않고 {@link ItemStack#copy()} 로만 옮긴다. {@code copy} 는 컴포넌트
 * 묶음을 통째로 들고 오므로 인챈트도, 모루에서 붙인 이름도, 닳은 내구도도 그대로다.
 * {@code new ItemStack(item)} 으로 다시 만들면 그 자리에서 전부 날아간다.
 *
 * <h2>승계분은 어디로 가는가</h2>
 * <p>{@link TeamState#legacyGear}에 담아 둔다. {@code TeamRosterStore}가 이 목록을 회차
 * 경계 너머로 실어 날라, 다음 회차의 시작 인벤토리에 그대로 돌려준다
 * ({@code TeamManager#restoreFreshRoster}). 전멸하지 않고 회차가 끝나면(승리) 이 자리
 * 자체가 안 불리므로 예전 값이 남아 있을 수 있는데, 쓰이는 자리가 전멸 경계뿐이라 무해하다.
 * {@link TeamState#resetAfterDeath}도 이 필드는 일부러 건드리지 않는다.
 */
public final class PerkLegacyGear {
	/**
	 * 판정 없이 통째로 가져가는 칸. 이 네 칸에 들어 있다는 사실 자체가 방어구라는 뜻이다.
	 * 그 밖의 장비 칸(오프핸드 등)은 흙·횃불·화살도 들어갈 수 있어 한 번 걸러야 한다.
	 */
	private static final List<EquipmentSlot> ARMOR_SLOTS = List.of(
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);

	/**
	 * 팀마다, 이번 전멸에서 승계 스냅샷을 이미 떴는지 표시한다. 값은 그 스냅샷을 뜬 게임 시각
	 * (틱)이다. 같은 전멸의 연쇄 죽음은 전부 이 값과 같은 틱에 일어나므로, 다음 죽음이 다른
	 * 틱이면 새 전멸로 본다.
	 */
	private static final Map<UUID, Long> LAST_CAPTURE_TICK = new HashMap<>();

	private PerkLegacyGear() {
	}

	// ------------------------------------------------------------------ 승계(전멸하는 순간)

	/**
	 * 바닐라가 죽은 사람의 인벤토리를 바닥에 쏟기 <b>직전</b>에 부르는 지점.
	 *
	 * <p>{@code PlayerDropMixin} 이 {@code Player.dropEquipment} 안의
	 * {@code Inventory.dropAll()} 호출을 감싸고 있으므로, 그 감싸개 안에서 실제 쏟기보다 먼저
	 * 이 메서드를 부르면 공유 인벤토리가 아직 온전한 상태를 볼 수 있다. 여기가 「유산」이
	 * 도구·무기를 볼 수 있는 마지막 자리다.
	 *
	 * <p>연쇄로 죽는 팀원도 이 자리를 지나지만 {@link #LAST_CAPTURE_TICK} 표시에 걸려 이미 뜬
	 * 스냅샷을 덮어쓰지 못한다.
	 */
	public static void captureBeforeDrop(@Nullable Player player) {
		if (player instanceof ServerPlayer dead) {
			captureOnce(dead);
		}
	}

	/**
	 * {@code ServerLivingEntityEvents.AFTER_DEATH}에 붙는 지점.
	 *
	 * <p><b>{@code keepInventory} 가 켜진 서버에서만 실제로 일을 한다.</b> 꺼져 있으면 이미
	 * {@link #captureBeforeDrop}이 같은 틱에 스냅샷을 떠 둔 뒤라 여기서는 조용히 지나간다.
	 * 그래도 이 자리를 없애면 안 된다 — {@code keepInventory} 서버에는
	 * {@code Player.dropEquipment} 가 쏟는 가지 자체가 없어 {@link #captureBeforeDrop} 이
	 * 한 번도 안 불린다.
	 *
	 * <p>{@code SharedFateMod}가 이 메서드를 {@code DeathHandler::onDeath}보다 <b>먼저</b>
	 * 등록해야 한다. {@code DeathHandler} 가 {@code keepInventory} 가 꺼진 서버에서 남은
	 * 공유 아이템을 바닥에 쏟아 비우기 때문이다.
	 */
	public static void onDeath(LivingEntity entity, DamageSource source) {
		if (entity instanceof ServerPlayer dead) {
			captureOnce(dead);
		}
	}

	/**
	 * 이 팀의 승계 스냅샷을 뜬다. 같은 전멸에서 두 번째부터는 아무 일도 하지 않는다.
	 *
	 * <p>스냅샷 지점이 둘({@link #captureBeforeDrop}·{@link #onDeath})이고 한 전멸에 팀원이
	 * 여럿 죽으므로, "이번 틱에 이미 떴는가"를 여기 한 곳에서만 따진다.
	 */
	private static void captureOnce(ServerPlayer dead) {
		MinecraftServer server = dead.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(dead.getUUID());
		TeamState state = manager.stateOf(dead.getUUID());
		if (team == null || state == null) {
			return;
		}

		long now = server.overworld().getGameTime();
		Long last = LAST_CAPTURE_TICK.get(team.teamId());
		if (last != null && last == now) {
			// 같은 전멸이다. 이미 이번 틱에 스냅샷을 떴다.
			return;
		}
		LAST_CAPTURE_TICK.put(team.teamId(), now);

		int captured = captureAtDeath(state);
		if (captured > 0) {
			SharedFateMod.LOGGER.info(
					"[PERK] 「유산」— 팀 {} 이(가) 전멸하는 시점의 장비 {}개를 다음 회차로 넘깁니다.",
					team.teamId(), captured);
		}
	}

	/**
	 * 이 팀이 「유산」을 가졌으면, 지금(전멸하는 이 순간) 가진 도구·무기·방어구를 스냅샷으로
	 * 남긴다. 없으면 아무것도 하지 않는다.
	 *
	 * <p>{@link TeamState#legacyGear}를 통째로 다시 채운다 — 지금 이 순간의 보유물이 유일한 기준이다.
	 *
	 * @return 스냅샷에 담긴 아이템 묶음 수. 「유산」이 없거나 가진 게 없었으면 0
	 */
	static int captureAtDeath(TeamState state) {
		LegacyGearEffect effect = find(state);
		if (effect == null) {
			return 0;
		}

		List<ItemStack> captured = new ArrayList<>();
		collectFromList(state.mainItems, effect, captured);
		collectFromList(state.extraItems, effect, captured);
		collectFromEnderChest(state.enderContainer, effect, captured);
		collectEquipment(state, effect, captured);

		state.legacyGear.clear();
		state.legacyGear.addAll(captured);
		return captured.size();
	}

	/** 이 팀이 지금 가진 증강 중 {@code legacy_gear} 효과. 없으면 null. */
	private static @Nullable LegacyGearEffect find(TeamState state) {
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			LegacyGearEffect effect = find(perk);
			if (effect != null) {
				return effect;
			}
		}
		return null;
	}

	private static @Nullable LegacyGearEffect find(Perk perk) {
		for (PerkEffect effect : perk.effects()) {
			if (effect instanceof LegacyGearEffect legacy) {
				return legacy;
			}
		}
		return null;
	}

	/**
	 * 목록을 훑어 걸리는 스택을 <b>사본으로</b> 모은다. 원본은 손대지 않는다.
	 *
	 * <p>{@link ItemStack#copy()} 여야 한다. 아이템 종류만 보고 스택을 새로 만들면 인챈트도
	 * 이름도 내구도도 그 자리에서 사라진다.
	 */
	private static void collectFromList(SharedItemList items, LegacyGearEffect effect,
			List<ItemStack> collected) {
		for (int slot = 0; slot < items.size(); slot++) {
			ItemStack stack = items.get(slot);
			if (effect.matches(stack)) {
				collected.add(stack.copy());
			}
		}
	}

	private static void collectFromEnderChest(PlayerEnderChestContainer container,
			LegacyGearEffect effect, List<ItemStack> collected) {
		for (int slot = 0; slot < container.getContainerSize(); slot++) {
			ItemStack stack = container.getItem(slot);
			if (effect.matches(stack)) {
				collected.add(stack.copy());
			}
		}
	}

	/**
	 * 지금 착용 중인 장비를 모은다.
	 *
	 * <p>방어구 네 칸은 판정 없이 전부 가져간다 — 그 칸에 들어 있다는 사실이 이미 방어구라는
	 * 뜻이다. 나머지 칸은 오프핸드처럼 아무거나 들어갈 수 있어(횃불·흙·화살) 한 번 거른다.
	 * 보조 손에 든 방패·곡괭이·삼지창은 그래서 넘어가고, 보조 손에 쌓아 둔 흙은 안 넘어간다.
	 *
	 * <p>마인핸드는 여기 없다. {@code TeamAwareEquipment} 가 그 칸만 공유 장비가 아니라
	 * 바닐라 인벤토리(= {@link TeamState#mainItems} 의 선택 칸)에 두기 때문이고, 그쪽은
	 * {@link #collectFromList} 가 이미 훑는다.
	 */
	private static void collectEquipment(TeamState state, LegacyGearEffect effect,
			List<ItemStack> collected) {
		for (EquipmentSlot slot : EquipmentSlot.values()) {
			if (slot == EquipmentSlot.MAINHAND) {
				continue;
			}
			ItemStack worn = state.equipment.get(slot);
			if (worn.isEmpty()) {
				continue;
			}
			if (ARMOR_SLOTS.contains(slot) || effect.matches(worn)) {
				collected.add(worn.copy());
			}
		}
	}

	/**
	 * 서버 종료·시험 정리용. 팀 상태는 {@code TeamManager}가 따로 관리하므로 여기서는
	 * {@link #LAST_CAPTURE_TICK} 표시만 비운다. 안 비우면 다음 서버는 게임 시각이 다시 0부터
	 * 시작하는데 예전 팀 id 가 우연히 같은 틱값으로 남아 있어, 극히 드물게 첫 전멸의 스냅샷을
	 * 건너뛸 수 있다.
	 */
	public static void reset() {
		LAST_CAPTURE_TICK.clear();
	}
}
