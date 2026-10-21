package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「게임 시작」의 회차 상태.
 *
 * <p>실제로 시작하는 {@code GameStartManager.start} 는 서버와 살아 있는 플레이어가 있어야 해서
 * 단위 시험으로 닿지 않는다. 대신 <b>그 동작이 도는 조건</b>과 <b>저장 호환</b>을 확인한다.
 * 아이템을 지우는 동작이라 「실수로 도는 길」이 없어야 한다.
 */
class GameStartTest {
	private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final GameStartManager.WorldOrigin FRESH =
			GameStartManager.WorldOrigin.FRESH_WORLD;
	private static final GameStartManager.WorldOrigin ONGOING =
			GameStartManager.WorldOrigin.ONGOING_WORLD;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static CompoundTag encode(TeamState state) {
		return (CompoundTag) TeamState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
	}

	private static TeamState decode(CompoundTag tag) {
		return TeamState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
	}

	// ------------------------------------------------------------------ 시작 대기

	@Test
	void 새로_만든_팀은_시작_대기다() {
		assertFalse(TeamState.fresh(20.0F).runStarted);
		assertTrue(GameStartManager.waiting(TeamState.fresh(20.0F)));
		assertFalse(GameStartManager.started(TeamState.fresh(20.0F)));
	}

	@Test
	void 팀이_없으면_대기도_아니다() {
		// 「대기 중인 회차」라는 것이 없다는 뜻이다. 승리 판정처럼 회차가 있어야만 뜻이 서는
		// 물음이 이것을 본다. 「시작 전인가」는 preStart 로 따로 묻는다 — 아래 시험을 보라.
		assertFalse(GameStartManager.waiting(null));
		assertTrue(GameStartManager.started(null));
		// 엔티티가 아닌 null 은 사람이 아니므로 피해를 버리지 않는다.
		assertFalse(GameStartManager.blocksDamage(null));
	}

	/**
	 * <b>시작 전 보호는 팀이 없어도 걸린다.</b>
	 *
	 * <p>한때 막는 규칙(스폰 반경·블록 파괴·시각·허기)은 팀 없는 사람까지 걸면서 지켜 주는
	 * 규칙(무적)만 {@code waiting} 에 남아 있었다. 그 결과 <b>스폰 50칸에 갇혀 블록도 못 부수는
	 * 사람이 떨어져 죽었다.</b> 막는 것과 지켜 주는 것이 같은 물음을 보지 않으면 반드시 이런
	 * 구멍이 생긴다. 그래서 {@code preStart} 하나로 모았고, 여기서 그것을 못박는다.
	 */
	@Test
	void 시작_전_판정은_팀이_없어도_참이다() {
		assertTrue(GameStartManager.preStart(null), "팀이 없는 사람도 회차를 시작하지 않았다");
		assertTrue(GameStartManager.preStart(TeamState.fresh(20.0F)), "시작 대기 팀도 시작 전이다");

		TeamState started = TeamState.fresh(20.0F);
		started.runStarted = true;
		assertFalse(GameStartManager.preStart(started), "시작한 팀은 시작 전이 아니다");
	}

	/** 시작 전 제한과 무적은 <b>반드시 같은 판정</b>을 봐야 한다. */
	@Test
	void 막는_규칙과_지켜_주는_규칙이_같은_판정을_본다() {
		TeamState waiting = TeamState.fresh(20.0F);
		TeamState started = TeamState.fresh(20.0F);
		started.runStarted = true;

		for (TeamState state : new TeamState[] {null, waiting, started}) {
			assertEquals(GameStartManager.preStart(state),
					PreStartRestrictions.blocksPreStartAction(state),
					"두 판정이 갈리면 갇힌 채로 죽는 구멍이 생긴다");
		}
	}

	// ------------------------------------------------------------------ 자동 시작

	/**
	 * 월드 초기화를 끈 서버({@code resetWorldOnTeamDeath=false})의 전멸.
	 *
	 * <p>같은 월드에서 그대로 이어 가므로 회차도 이어져야 한다. 여기서 「시작 대기」로 되돌리면
	 * 사람이 단추를 다시 눌러야 하고, 그 단추가 방금 살아남은 것들까지 지운다.
	 */
	@Test
	void 전멸해도_회차는_이어진다() {
		TeamState state = TeamState.fresh(20.0F);
		state.runStarted = true;

		state.resetAfterDeath(20.0F, false);

		assertTrue(state.runStarted,
				"「게임 시작」은 첫 회차 전 한 번뿐이므로 전멸해도 회차 시작 여부는 그대로다");
	}

	/** 아직 시작하지 않은 팀은 전멸해도 대기 그대로여야 한다 (시작 전에는 죽지도 않지만). */
	@Test
	void 시작하지_않은_팀은_전멸_처리에도_대기_그대로다() {
		TeamState state = TeamState.fresh(20.0F);

		state.resetAfterDeath(20.0F, false);

		assertFalse(state.runStarted);
	}

	private static List<TeamRosterStore.RestoredTeam> roster(ShareTeam team, int swapIntervalTicks,
			List<ItemStack> legacyGear) {
		return List.of(new TeamRosterStore.RestoredTeam(team, true, 20.0F, swapIntervalTicks,
				false, false, legacyGear, false));
	}

	/** 전멸로 월드가 지워지고 새로 열린 뒤, 명단만 되살아난 상태. */
	private static TeamManager freshWorld(ShareTeam team, int swapIntervalTicks,
			List<ItemStack> legacyGear) {
		TeamManager fresh = new TeamManager();
		fresh.restoreFreshRoster(roster(team, swapIntervalTicks, legacyGear));
		return fresh;
	}

	/** 회차가 시작되는지는 오직 회차 번호가 정한다. 서버가 어떤 길로 떴는지는 보지 않는다. */
	@Test
	void 자동_시작_여부는_회차_번호만_본다() {
		assertFalse(GameStartManager.autoStarts(1), "1회차만이 「게임 시작」을 기다린다");
		assertTrue(GameStartManager.autoStarts(2));
		assertTrue(GameStartManager.autoStarts(5));
		assertTrue(GameStartManager.autoStarts(999));
	}

	@Test
	void 새_월드에서_회차가_저절로_시작된다() {
		TeamManager source = new TeamManager();
		ShareTeam team = source.createTeam("화이팅", MEMBER, 20.0F);
		TeamManager fresh = freshWorld(team, 0, List.of());
		// 명단 복원만으로는 아직 「시작 대기」다. 회차를 켜는 것은 syncRunStart 다.
		assertFalse(fresh.stateByTeamId(team.teamId()).runStarted);

		assertEquals(1, GameStartManager.syncRunStart(fresh, 2, FRESH));

		assertTrue(fresh.stateByTeamId(team.teamId()).runStarted,
				"전멸해서 새 월드로 넘어간 회차는 단추 없이 저절로 진행 중이어야 한다");
	}

	/**
	 * <b>이 시험이 이 파일의 핵심이다.</b>
	 *
	 * <p>5회차를 굴리던 팀이 있는 월드에서 서버만 다시 켰을 때 「시작 대기」로 남으면, 그
	 * 상태에서 「게임 시작」을 누르는 순간 5회차 동안 모은 것이 전부 사라진다.
	 */
	@Test
	void 이미_굴러가던_월드의_5회차_팀은_시작_대기로_남지_않는다() {
		TeamManager manager = new TeamManager();
		ShareTeam team = manager.createTeam("화이팅", MEMBER, 20.0F);
		TeamState state = manager.stateByTeamId(team.teamId());
		// 저장에서 「시작 대기」로 읽힌 채 5회차를 굴리던 팀.
		state.runStarted = false;

		assertEquals(1, GameStartManager.syncRunStart(manager, 5, ONGOING));

		assertTrue(state.runStarted,
				"회차 번호가 2 이상이면 서버가 어떤 길로 뜨든 진행 중이어야 한다");
		assertFalse(GameStartManager.waiting(state));
	}

	/**
	 * <b>자동 시작은 아이템을 지우지 않는다.</b>
	 *
	 * <p>자동 시작이 「게임 시작」의 청소까지 하게 되면, 서버를 다시 켤 때마다
	 * 진행 중이던 팀의 물건이 전부 사라진다. 이 시험이 그 길을 못 박는다.
	 */
	@Test
	void 자동_시작은_이미_굴러가던_팀의_물건을_건드리지_않는다() {
		TeamManager manager = new TeamManager();
		ShareTeam team = manager.createTeam("화이팅", MEMBER, 20.0F);
		TeamState state = manager.stateByTeamId(team.teamId());
		state.runStarted = false;
		state.mainItems.set(0, new ItemStack(Items.DIAMOND, 64));
		state.extraItems.set(3, new ItemStack(Items.NETHERITE_INGOT, 2));
		state.enderContainer.setItem(5, new ItemStack(Items.ELYTRA));
		state.xpLevel = 42;
		state.totalExperience = 5000;
		state.lastPerkMilestone = 9;
		state.difficultyElapsedTicks = 72000;

		GameStartManager.syncRunStart(manager, 5, ONGOING);

		assertEquals(64, state.mainItems.get(0).getCount(), "공유 인벤토리를 비우면 재앙이다");
		assertEquals(2, state.extraItems.get(3).getCount());
		assertEquals(Items.ELYTRA, state.enderContainer.getItem(5).getItem());
		assertEquals(42, state.xpLevel, "경험치도 회차 진행 상황이라 그대로여야 한다");
		assertEquals(5000, state.totalExperience);
		assertEquals(9, state.lastPerkMilestone, "증강 구간을 0으로 되돌리면 선택창이 다시 터진다");
		assertEquals(72000, state.difficultyElapsedTicks);
	}

	@Test
	void 이미_시작한_팀은_자동_시작이_건드리지_않는다() {
		TeamManager manager = new TeamManager();
		ShareTeam team = manager.createTeam("화이팅", MEMBER, 20.0F);
		TeamState state = manager.stateByTeamId(team.teamId());
		state.runStarted = true;
		state.positionSwapIntervalTicks = 6000;
		state.positionSwapRemainingTicks = 40;

		assertEquals(0, GameStartManager.syncRunStart(manager, 5, ONGOING));

		assertEquals(40, state.positionSwapRemainingTicks,
				"진행 중인 회차의 남은 교환 시간을 되감으면 안 된다");
	}

	/** 1회차는 사람이 눌러야 한다. 여기서 저절로 시작되면 「게임 시작」 자체가 사라진다. */
	@Test
	void 첫_회차는_저절로_시작되지_않는다() {
		TeamManager manager = new TeamManager();
		ShareTeam team = manager.createTeam("화이팅", MEMBER, 20.0F);

		assertEquals(0, GameStartManager.syncRunStart(manager, 1, ONGOING));
		assertEquals(0, GameStartManager.syncRunStart(
				freshWorld(team, 0, List.of()), 1, FRESH));

		assertFalse(manager.stateByTeamId(team.teamId()).runStarted);
	}

	/**
	 * 「유산」이 넘긴 장비는 자동 시작 때 인벤토리에 들어가야 한다.
	 *
	 * <p>{@code restoreFreshRoster} 는 목록만 들고 있고 인벤토리에 꽂지 않는다. 1회차 전이라면
	 * 「게임 시작」이 아이템을 지운 뒤에 넣지만, 2회차부터는 그 「게임 시작」이 없으므로 여기서
	 * 넣지 않으면 유산이 영영 사라진다.
	 */
	@Test
	void 자동_시작이_유산_장비를_인벤토리에_넣는다() {
		TeamManager source = new TeamManager();
		ShareTeam team = source.createTeam("화이팅", MEMBER, 20.0F);
		TeamManager fresh = freshWorld(team, 0, List.of(new ItemStack(Items.DIAMOND_PICKAXE)));
		TeamState state = fresh.stateByTeamId(team.teamId());
		assertEquals(1, state.legacyGear.size(), "복원 직후에는 아직 들고만 있어야 한다");

		GameStartManager.syncRunStart(fresh, 2, FRESH);

		assertTrue(state.legacyGear.isEmpty(), "회차가 시작되면 목록이 비어야 한다");
		assertTrue(state.overflowItems.isEmpty(), "빈 인벤토리라 넘칠 것이 없다");
		assertEquals(Items.DIAMOND_PICKAXE, state.mainItems.get(0).getItem());
	}

	/**
	 * 대기에 갇혀 있던 팀이 들고 있던 유산도 이어받는다.
	 *
	 * <p>시작하지 않은 팀은 증강을 고를 수 없으므로({@code PerkManager.tick} 가 건너뛴다) 그
	 * 팀의 {@code legacyGear} 에 들어 있는 것은 언제나 <b>지난 회차에서 넘어온 것</b>뿐이다.
	 * 이번 회차에 뜬 스냅샷이 섞여 있어 미리 돌려주는 일은 일어나지 않는다.
	 */
	@Test
	void 대기에_갇혀_있던_팀의_유산도_이어받는다() {
		TeamManager manager = new TeamManager();
		ShareTeam team = manager.createTeam("화이팅", MEMBER, 20.0F);
		TeamState state = manager.stateByTeamId(team.teamId());
		state.runStarted = false;
		state.legacyGear.add(new ItemStack(Items.NETHERITE_SWORD));
		state.mainItems.set(0, new ItemStack(Items.DIAMOND, 64));

		GameStartManager.syncRunStart(manager, 5, ONGOING);

		assertTrue(state.legacyGear.isEmpty());
		assertEquals(64, state.mainItems.get(0).getCount(), "있던 것은 그대로 있어야 한다");
		assertEquals(Items.NETHERITE_SWORD, state.mainItems.get(1).getItem(),
				"유산은 빈 칸에 들어간다");
	}

	/**
	 * 위치 교환의 남은 시간.
	 *
	 * <p>{@code restoreFreshRoster} 는 주기만 이어받고 남은 시간은 0 으로 둔다. 자동 시작이
	 * 여기서 채우지 않으면 새 월드에 들어서자마자 첫 교환이 터진다.
	 */
	@Test
	void 자동_시작이_위치_교환_남은_시간을_주기로_채운다() {
		TeamManager source = new TeamManager();
		ShareTeam team = source.createTeam("화이팅", MEMBER, 20.0F);
		TeamManager fresh = freshWorld(team, 6000, List.of());
		TeamState state = fresh.stateByTeamId(team.teamId());
		assertEquals(0, state.positionSwapRemainingTicks);

		GameStartManager.syncRunStart(fresh, 2, FRESH);

		assertEquals(6000, state.positionSwapRemainingTicks,
				"첫 교환은 새 회차가 열린 뒤 한 주기가 지나야 온다");
	}

	/**
	 * 대기에 갇혀 있던 팀도 남은 시간이 0 이면 채워 준다. 대기 중에는
	 * {@code PositionSwapManager.tick} 가 건너뛰므로 그 0 은 「곧 교환할 때」가 아니라
	 * 「한 번도 세지 않았다」는 뜻이다. 그대로 두면 시작하는 순간 첫 교환이 터진다.
	 */
	@Test
	void 이미_굴러가던_월드에서도_한_번도_세지_않은_교환은_주기로_채운다() {
		TeamManager manager = new TeamManager();
		ShareTeam team = manager.createTeam("화이팅", MEMBER, 20.0F);
		TeamState state = manager.stateByTeamId(team.teamId());
		state.runStarted = false;
		state.positionSwapIntervalTicks = 6000;
		state.positionSwapRemainingTicks = 0;

		GameStartManager.syncRunStart(manager, 5, ONGOING);

		assertEquals(6000, state.positionSwapRemainingTicks);
	}

	/** 반대로 세다 만 값이 남아 있으면 되감지 않는다. 그것이 새 월드와 다른 유일한 점이다. */
	@Test
	void 이미_굴러가던_월드의_세다_만_교환_시간은_되감지_않는다() {
		TeamManager manager = new TeamManager();
		ShareTeam team = manager.createTeam("화이팅", MEMBER, 20.0F);
		TeamState state = manager.stateByTeamId(team.teamId());
		state.runStarted = false;
		state.positionSwapIntervalTicks = 6000;
		state.positionSwapRemainingTicks = 1200;

		GameStartManager.syncRunStart(manager, 5, ONGOING);

		assertEquals(1200, state.positionSwapRemainingTicks);
	}

	@Test
	void 팀이_없으면_자동_시작할_것도_없다() {
		assertEquals(0, GameStartManager.syncRunStart(null, 5, FRESH));
		assertEquals(0, GameStartManager.syncRunStart(new TeamManager(), 5, FRESH));
		assertEquals(0, GameStartManager.syncRunStart(null, 5, ONGOING));
		assertEquals(0, GameStartManager.syncRunStart(new TeamManager(), 5, ONGOING));
	}

	// ------------------------------------------------------------------ 저장 호환

	@Test
	void 회차_시작_여부는_왕복_직렬화된다() {
		TeamState waiting = TeamState.fresh(20.0F);
		TeamState started = TeamState.fresh(20.0F);
		started.runStarted = true;

		assertFalse(decode(encode(waiting)).runStarted);
		assertTrue(decode(encode(started)).runStarted);
	}

	/**
	 * 이 기능이 생기기 전의 월드를 여는 길.
	 *
	 * <p>항목이 없으면 <b>이미 시작했다</b>로 읽어야 한다. 거짓으로 읽으면 이미 몇 시간을
	 * 플레이하던 팀이 갑자기 「시작 대기」가 되고, 그 상태에서 「게임 시작」을 누르면 가진 것이
	 * 전부 사라진다.
	 */
	@Test
	void 항목이_없는_예전_저장은_이미_시작한_것으로_읽는다() {
		TeamState started = TeamState.fresh(20.0F);
		started.runStarted = true;
		CompoundTag tag = encode(started);

		assertFalse(tag.keySet().contains("runStarted"),
				"진행 중인 팀은 이 항목을 적지 않아 예전 저장과 형태가 같아야 한다");
		assertTrue(decode(tag).runStarted);
	}

	@Test
	void 시작_대기인_팀만_저장에_항목을_남긴다() {
		assertTrue(encode(TeamState.fresh(20.0F)).keySet().contains("runStarted"),
				"기본값(참)과 다른 값이므로 반드시 적혀야 한다");
	}

	/** 시작 대기 상태에서도 나머지 저장 항목이 밀리거나 사라지면 안 된다. */
	@Test
	void 시작_대기_항목이_다른_저장을_밀지_않는다() {
		TeamState state = TeamState.fresh(30.0F);
		state.mainItems.set(0, new ItemStack(Items.DIAMOND, 5));
		state.xpLevel = 7;
		state.perksEnabled = true;
		state.ownedPerks.add("sharedfate:tough_body");

		TeamState round = decode(encode(state));

		assertFalse(round.runStarted);
		assertEquals(5, round.mainItems.get(0).getCount());
		assertEquals(7, round.xpLevel);
		assertTrue(round.perksEnabled);
		assertEquals(1, round.ownedPerks.size());
	}

	// ------------------------------------------------------------------ 보스바 문구

	/**
	 * 시작 전 보스바는 <b>무엇을 누르면 되는지</b>를 적는다. 회차 번호도 함께 남는다.
	 */
	@Test
	void 아무도_시작하지_않았으면_보스바가_눌러야_할_것을_적는다() {
		String label = RunProgressManager.label(3, false, "", false);
		assertEquals("SharedFate · 3회차 — 「게임 시작」을 눌러 주세요", label);
		assertTrue(label.contains("3회차"), "회차 번호는 시작 전에도 보여야 한다");
	}

	@Test
	void 시작한_뒤에는_보스바가_진행_중이라고_적는다() {
		assertEquals("SharedFate · 3회차 진행 중",
				RunProgressManager.label(3, false, "", true));
	}

	@Test
	void 승리는_시작_여부와_무관하게_승리로_적는다() {
		assertEquals("SharedFate · 2회차 · 원정대 승리!",
				RunProgressManager.label(2, true, "원정대", true));
		assertEquals("SharedFate · 2회차 · 원정대 승리!",
				RunProgressManager.label(2, true, "원정대", false));
	}
}
