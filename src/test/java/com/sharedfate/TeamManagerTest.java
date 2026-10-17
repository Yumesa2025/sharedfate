package com.sharedfate;

import com.sharedfate.sync.TeamRosterStore;

import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamManagerTest {
	/** 시험용: 명단만 있는 목록을 설정 기본값과 함께 복원 입력으로 바꾼다. */
	private static java.util.List<TeamRosterStore.RestoredTeam> roster(
			java.util.Collection<ShareTeam> teams, float maxHealth) {
		return teams.stream()
				.map(team -> new TeamRosterStore.RestoredTeam(team, false, maxHealth, 0, false, false))
				.toList();
	}

	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
	private static final UUID C = UUID.fromString("00000000-0000-0000-0000-00000000000c");
	private static final UUID D = UUID.fromString("00000000-0000-0000-0000-00000000000d");
	private static final UUID E = UUID.fromString("00000000-0000-0000-0000-00000000000e");

	private TeamManager manager;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	void setUp() {
		manager = new TeamManager();
	}

	@Test
	void 팀이_없는_플레이어는_null을_돌려준다() {
		assertNull(manager.teamOf(A));
		assertNull(manager.stateOf(A));
	}

	@Test
	void 팀을_만들면_생성자가_속하고_초기_상태가_생긴다() {
		ShareTeam team = manager.createTeam("우리팀", A, 40.0F);

		assertNotNull(team);
		assertEquals(team.teamId(), manager.teamOf(A).teamId());
		assertSame(manager.stateByTeamId(team.teamId()), manager.stateOf(A));
		assertEquals(40.0F, manager.stateOf(A).health);
		assertTrue(manager.isDirty());
	}

	@Test
	void 새_월드_명단_복원은_팀만_유지하고_공유_자원을_초기화한다() {
		ShareTeam original = manager.createTeam("새출발", A, 40.0F);
		manager.addMember(original.teamId(), B, 4);
		ShareTeam rosterTeam = manager.teamOf(A);
		TeamState oldState = manager.stateOf(A);
		oldState.mainItems.set(0, new ItemStack(Items.DIAMOND, 17));
		oldState.enderContainer.setItem(0, new ItemStack(Items.ENDER_PEARL, 3));
		oldState.health = 3.0F;
		oldState.foodLevel = 2;
		oldState.totalExperience = 99;
		oldState.effects.add(new MobEffectInstance(MobEffects.POISON, 200));

		TeamManager fresh = new TeamManager();
		int restored = fresh.restoreFreshRoster(roster(manager.allTeams(), 40.0F));

		assertEquals(1, restored);
		assertEquals(rosterTeam, fresh.teamOf(A));
		assertEquals(rosterTeam, fresh.teamOf(B));
		assertNotSame(oldState, fresh.stateOf(A));
		assertTrue(fresh.stateOf(A).mainItems.stream().allMatch(ItemStack::isEmpty));
		assertTrue(fresh.stateOf(A).extraItems.stream().allMatch(ItemStack::isEmpty));
		assertTrue(fresh.stateOf(A).enderContainer.isEmpty());
		assertTrue(fresh.stateOf(A).equipment.isEmpty());
		assertTrue(fresh.stateOf(A).effects.isEmpty());
		assertEquals(40.0F, fresh.stateOf(A).maxHealth);
		assertEquals(40.0F, fresh.stateOf(A).health);
		assertEquals(20, fresh.stateOf(A).foodLevel);
		assertEquals(0, fresh.stateOf(A).totalExperience);
		assertTrue(fresh.isDirty());
	}

	@Test
	void 회차가_넘어가도_증강_사용_여부와_교환_주기와_체력은_이어진다() {
		ShareTeam team = manager.createTeam("원정대", A, 40.0F);
		manager.addMember(team.teamId(), B, 4);
		TeamState previous = manager.stateOf(A);
		previous.perksEnabled = true;
		previous.damageAlertEnabled = true;
		previous.deathAlertEnabled = true;
		previous.enablePositionSwap(3);
		previous.baseMaxHealth = 34.0F;
		previous.difficultyEscalationEnabled = true;
		previous.difficultyElapsedTicks = 72000;

		TeamManager fresh = new TeamManager();
		fresh.restoreFreshRoster(java.util.List.of(new TeamRosterStore.RestoredTeam(
				team, previous.perksEnabled, previous.baseMaxHealth,
				previous.positionSwapIntervalTicks,
				previous.damageAlertEnabled, previous.deathAlertEnabled,
				java.util.List.of(), previous.difficultyEscalationEnabled)));

		TeamState restored = fresh.stateOf(A);
		assertTrue(restored.perksEnabled, "증강 사용 여부는 회차를 넘겨 이어져야 한다");
		assertTrue(restored.damageAlertEnabled, "피격 알림은 회차를 넘겨 이어져야 한다");
		assertTrue(restored.deathAlertEnabled, "사망 알림은 회차를 넘겨 이어져야 한다");
		assertTrue(restored.difficultyEscalationEnabled, "난이도 상승 설정은 회차를 넘겨 이어져야 한다");
		assertEquals(3, restored.positionSwapIntervalMinutes());
		assertEquals(34.0F, restored.maxHealth);
		// 진행 상황은 이어지지 않는다.
		assertTrue(restored.ownedPerks.isEmpty());
		assertEquals(0, restored.totalExperience);
		assertEquals(0, restored.difficultyElapsedTicks,
				"난이도가 오른 시간은 회차마다 0 에서 다시 센다");
	}

	/**
	 * 회차 경계를 넘어온 「유산」은 <b>인벤토리에 꽂히지 않고 그대로 들려 있어야</b> 한다.
	 *
	 * <p>「게임 시작」이 인벤토리를 통째로 비우므로, 여기서 미리 넣으면 유산이 시작과 동시에
	 * 사라진다. 실제로 돌려주는 것은 {@code GameStartManager} 다.
	 */
	@Test
	void 유산으로_몰수했던_아이템은_시작할_때까지_들려_있는다() {
		ShareTeam team = manager.createTeam("원정대", A, 40.0F);
		ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);

		TeamManager fresh = new TeamManager();
		fresh.restoreFreshRoster(java.util.List.of(new TeamRosterStore.RestoredTeam(
				team, false, 40.0F, 0, false, false, java.util.List.of(pickaxe))));

		TeamState restored = fresh.stateOf(A);
		assertEquals(1, restored.legacyGear.size(),
				"「게임 시작」이 아이템을 전부 지우므로 그때까지 legacyGear 에 남아 있어야 한다");
		assertTrue(restored.legacyGear.getFirst().is(Items.DIAMOND_PICKAXE));
		assertTrue(restored.mainItems.stream().allMatch(ItemStack::isEmpty),
				"시작 전에는 공유 인벤토리가 비어 있어야 한다");
		assertTrue(restored.overflowItems.isEmpty());
	}

	/**
	 * 명단 복원 <b>그 자체</b>는 회차를 켜지 않는다. 회차를 켜는 것은 회차 번호를 아는
	 * {@code GameStartManager.syncRunStart} 뿐이고, 이 메서드는 그 번호를 모른다.
	 */
	@Test
	void 새_회차로_되살린_팀은_시작_대기_상태다() {
		ShareTeam team = manager.createTeam("원정대", A, 40.0F);

		TeamManager fresh = new TeamManager();
		fresh.restoreFreshRoster(roster(java.util.List.of(team), 40.0F));

		assertFalse(fresh.stateOf(A).runStarted,
				"명단을 되살리는 일과 회차를 켜는 일은 따로다");
	}

	@Test
	void 중복_멤버가_있는_명단은_부분_복원하지_않는다() {
		ShareTeam first = new ShareTeam(UUID.randomUUID(), "첫팀", java.util.List.of(A));
		ShareTeam second = new ShareTeam(UUID.randomUUID(), "둘째팀", java.util.List.of(A, B));

		assertThrows(IllegalArgumentException.class,
				() -> manager.restoreFreshRoster(roster(java.util.List.of(first, second), 40.0F)));
		assertTrue(manager.allTeams().isEmpty());
		assertNull(manager.teamOf(A));
	}

	@Test
	void 기존_팀이_있는_저장소에는_명단을_덮어쓰지_않는다() {
		ShareTeam existing = manager.createTeam("기존", A, 40.0F);
		ShareTeam incoming = new ShareTeam(UUID.randomUUID(), "새팀", java.util.List.of(B));

		assertThrows(IllegalStateException.class,
				() -> manager.restoreFreshRoster(roster(java.util.List.of(incoming), 40.0F)));
		assertEquals(existing, manager.teamOf(A));
		assertNull(manager.teamOf(B));
	}

	@Test
	void 같은_이름은_대소문자가_달라도_두_번_만들_수_없다() {
		manager.createTeam("Alpha", A, 40.0F);

		assertNull(manager.createTeam("alpha", B, 40.0F));
		assertNull(manager.createTeam("ALPHA", B, 40.0F));
	}

	@Test
	void 이미_팀에_속한_플레이어는_새_팀의_리더가_될_수_없다() {
		manager.createTeam("첫팀", A, 40.0F);

		assertNull(manager.createTeam("둘째팀", A, 40.0F));
		assertEquals(1, manager.allTeams().size());
	}

	@Test
	void 정원이_차면_더_넣을_수_없다() {
		ShareTeam team = manager.createTeam("우리팀", A, 40.0F);

		assertTrue(manager.addMember(team.teamId(), B, 4));
		assertTrue(manager.addMember(team.teamId(), C, 4));
		assertTrue(manager.addMember(team.teamId(), D, 4));
		assertFalse(manager.addMember(team.teamId(), E, 4));
		assertNull(manager.teamOf(E));
	}

	@Test
	void 다른_팀_멤버를_추가할_수_없다() {
		ShareTeam first = manager.createTeam("첫팀", A, 40.0F);
		manager.createTeam("둘째팀", B, 40.0F);

		assertFalse(manager.addMember(first.teamId(), B, 4));
		assertEquals("둘째팀", manager.teamOf(B).name());
	}

	@Test
	void 탈퇴하면_역인덱스에서도_사라진다() {
		ShareTeam team = manager.createTeam("우리팀", A, 40.0F);
		manager.addMember(team.teamId(), B, 4);

		manager.removeMember(B);

		assertNull(manager.teamOf(B));
		assertEquals(1, manager.teamOf(A).size());
	}

	@Test
	void 마지막_멤버가_탈퇴하면_팀과_상태가_사라진다() {
		ShareTeam team = manager.createTeam("우리팀", A, 40.0F);

		manager.removeMember(A);

		assertNull(manager.teamOf(A));
		assertNull(manager.stateByTeamId(team.teamId()));
		assertTrue(manager.allTeams().isEmpty());
	}

	@Test
	void 팀원끼리는_같은_상태_객체를_본다() {
		ShareTeam team = manager.createTeam("우리팀", A, 40.0F);
		manager.addMember(team.teamId(), B, 4);

		assertSame(manager.stateOf(A), manager.stateOf(B));
	}

	@Test
	void 팀을_해체하면_모든_멤버의_역참조가_사라진다() {
		ShareTeam team = manager.createTeam("우리팀", A, 40.0F);
		manager.addMember(team.teamId(), B, 4);

		manager.disband(team.teamId());

		assertNull(manager.teamOf(A));
		assertNull(manager.teamOf(B));
		assertNull(manager.stateByTeamId(team.teamId()));
	}

	@Test
	void 활성_팀이_있으면_틱에서_저장_플래그를_세운다() {
		assertFalse(manager.isDirty());
		manager.markDirtyIfActive();
		assertFalse(manager.isDirty());

		manager.createTeam("우리팀", A, 40.0F);
		manager.setDirty(false);
		manager.markDirtyIfActive();

		assertTrue(manager.isDirty());
	}

	@Test
	void 팀_상태_코덱은_인벤토리_엔더상자_장비와_스탯을_보존한다() {
		ShareTeam team = manager.createTeam("우리팀", A, 40.0F);
		manager.addMember(team.teamId(), B, 4);
		TeamState state = manager.stateOf(A);
		state.mainItems.set(0, new ItemStack(Items.DIAMOND_PICKAXE));
		state.extraItems.set(4, new ItemStack(Items.EMERALD, 9));
		state.enderContainer.setItem(3, new ItemStack(Items.ENDER_PEARL, 12));
		state.equipment.set(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
		state.health = 17.5F;
		state.maxHealth = 37.0F;
		state.absorption = 3.0F;
		state.foodLevel = 7;
		state.saturation = 1.5F;
		state.xpLevel = 12;
		state.xpProgress = 0.25F;
		state.totalExperience = 345;
		state.effects.add(new MobEffectInstance(MobEffects.SPEED, 200, 1));
		state.enablePositionSwap(3);
		state.positionSwapRemainingTicks = 1234;

		TeamManager round = CodecRoundTrip.through(TeamManager.CODEC, manager);

		assertNotNull(round.teamOf(A));
		assertSame(round.stateOf(A), round.stateOf(B));
		assertTrue(round.stateOf(A).mainItems.get(0).is(Items.DIAMOND_PICKAXE));
		assertEquals(9, round.stateOf(A).extraItems.get(4).getCount());
		assertEquals(12, round.stateOf(A).enderContainer.getItem(3).getCount());
		assertTrue(round.stateOf(A).equipment.get(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE));
		assertEquals(17.5F, round.stateOf(A).health);
		assertEquals(37.0F, round.stateOf(A).maxHealth);
		assertEquals(3.0F, round.stateOf(A).absorption);
		assertEquals(7, round.stateOf(A).foodLevel);
		assertEquals(1.5F, round.stateOf(A).saturation);
		assertEquals(12, round.stateOf(A).xpLevel);
		assertEquals(0.25F, round.stateOf(A).xpProgress);
		assertEquals(345, round.stateOf(A).totalExperience);
		assertEquals(1, round.stateOf(A).effects.size());
		assertEquals(MobEffects.SPEED, round.stateOf(A).effects.getFirst().getEffect());
		assertEquals(200, round.stateOf(A).effects.getFirst().getDuration());
		assertEquals(3, round.stateOf(A).positionSwapIntervalMinutes());
		assertEquals(1234, round.stateOf(A).positionSwapRemainingTicks);
	}

	@Test
	void 기존_저장에_추가_27칸이_없으면_팀마다_새_빈_목록을_만든다() {
		CompoundTag encoded = (CompoundTag) TeamState.CODEC
				.encodeStart(NbtOps.INSTANCE, TeamState.fresh(40.0F)).getOrThrow();
		encoded.remove("extraItems");

		TeamState first = TeamState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		TeamState second = TeamState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();

		assertEquals(TeamState.EXTRA_SIZE, first.extraItems.size());
		assertTrue(first.extraItems.stream().allMatch(ItemStack::isEmpty));
		assertNotSame(first.extraItems, second.extraItems, "기존 팀들이 같은 가변 기본 목록을 공유하면 안 된다");
	}

	@Test
	void 사망_초기화는_인벤토리는_유지하고_스탯만_초기화한다() {
		TeamState state = TeamState.fresh(40.0F);
		state.mainItems.set(0, new ItemStack(Items.DIAMOND));
		state.health = 1.0F;
		state.absorption = 4.0F;
		state.foodLevel = 2;
		state.saturation = 0.0F;
		state.xpLevel = 10;
		state.xpProgress = 0.9F;
		state.totalExperience = 200;

		state.resetAfterDeath(60.0F, false);

		assertTrue(state.mainItems.get(0).is(Items.DIAMOND));
		assertEquals(60.0F, state.maxHealth);
		assertEquals(60.0F, state.health);
		assertEquals(0.0F, state.absorption);
		assertEquals(20, state.foodLevel);
		assertEquals(5.0F, state.saturation);
		assertEquals(0, state.xpLevel);
		assertEquals(0.0F, state.xpProgress);
		assertEquals(0, state.totalExperience);
	}

	@Test
	void 손상된_팀_스탯은_안전한_범위로_복구한다() {
		TeamState state = TeamState.fresh(40.0F);
		state.maxHealth = Float.NaN;
		state.health = Float.POSITIVE_INFINITY;
		state.absorption = Float.NaN;
		// 「먹방」 팀의 허기는 100 까지 저장되므로 그 위만 손상으로 본다.
		state.foodLevel = 150;
		state.saturation = -2.0F;
		state.totalExperience = -10;
		state.xpProgress = Float.NaN;

		state.sanitize(40.0F);

		assertEquals(40.0F, state.maxHealth);
		assertEquals(40.0F, state.health);
		assertEquals(0.0F, state.absorption);
		assertEquals(100, state.foodLevel);
		assertEquals(0.0F, state.saturation);
		assertEquals(0, state.totalExperience);
		assertEquals(0.0F, state.xpProgress);
	}

	@Test
	void 공유_아이템이_남은_팀은_정산_없이_해체할_수_없다() {
		ShareTeam team = manager.createTeam("정산", A, 40.0F);
		manager.stateOf(A).mainItems.set(0, new ItemStack(Items.DIAMOND));

		assertThrows(IllegalStateException.class, () -> manager.disband(team.teamId()));
		assertNotNull(manager.teamOf(A));
	}

	@Test
	void 오프라인_효과_정리_표시는_저장되고_한_번만_소비된다() {
		manager.markEffectClear(A);

		TeamManager round = CodecRoundTrip.through(TeamManager.CODEC, manager);

		assertTrue(round.consumeEffectClear(A));
		assertFalse(round.consumeEffectClear(A));
	}

	@Test
	void 오프라인_경험치_정리_표시는_저장되고_한_번만_소비된다() {
		manager.markExperienceClear(A);

		TeamManager round = CodecRoundTrip.through(TeamManager.CODEC, manager);

		assertTrue(round.consumeExperienceClear(A));
		assertFalse(round.consumeExperienceClear(A));
	}

	/**
	 * 해체 때 접속하지 않았던 사람은 <b>다음에 들어올 때</b> 스폰으로 간다.
	 *
	 * <p>저장을 왕복해야 뜻이 있다. 해체하고 서버를 내린 뒤 다음 날 들어오는 것이 가장 흔한
	 * 모양인데, 쪽지가 저장에 안 들어가면 그 사람만 네더 한복판에 빈손으로 남는다.
	 */
	@Test
	void 오프라인_스폰_귀환_표시는_저장되고_한_번만_소비된다() {
		manager.markSpawnReturn(A);

		TeamManager round = CodecRoundTrip.through(TeamManager.CODEC, manager);

		assertTrue(round.consumeSpawnReturn(A));
		assertFalse(round.consumeSpawnReturn(A), "두 번 옮기면 안 된다");
	}

	/** 쪽지를 안 남긴 사람은 접속해도 아무 일이 없다. */
	@Test
	void 표시가_없는_사람은_스폰으로_끌려가지_않는다() {
		manager.markSpawnReturn(A);

		assertFalse(CodecRoundTrip.through(TeamManager.CODEC, manager).consumeSpawnReturn(B));
	}

	/**
	 * 세 쪽지는 <b>서로 다른 자루</b>다.
	 *
	 * <p>하나로 묶어 두면 경험치만 지워야 할 사람이 스폰으로 끌려간다. 코덱 항목이 셋이 된 뒤로
	 * 순서를 잘못 이어 붙이는 사고가 생길 수 있어 못박아 둔다 — 그 실수는 빌드도 통과한다.
	 */
	@Test
	void 세_쪽지가_서로_섞이지_않는다() {
		manager.markEffectClear(A);
		manager.markExperienceClear(B);
		manager.markSpawnReturn(C);

		TeamManager round = CodecRoundTrip.through(TeamManager.CODEC, manager);

		assertFalse(round.consumeSpawnReturn(A), "A 는 효과 정리만 필요했다");
		assertFalse(round.consumeSpawnReturn(B), "B 는 경험치 정리만 필요했다");
		assertTrue(round.consumeSpawnReturn(C));
		assertTrue(round.consumeEffectClear(A));
		assertTrue(round.consumeExperienceClear(B));
	}
}
