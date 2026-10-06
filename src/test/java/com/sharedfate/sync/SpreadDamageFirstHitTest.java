package com.sharedfate.sync;

import com.mojang.authlib.GameProfile;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「완충」이 <b>처음 맞을 때 한 번만</b> 해야 하는 세 가지를 실제로 들어가는 양·나가는 알림 수로
 * 못박는다. 2026-10-04 에 사람이 셋 다 고치라고 했다.
 *
 * <ol>
 *   <li><b>난이도 배율</b> — 몫이 {@code Player.hurtServer} 를 다시 지나며 배율을 또 맞아,
 *       어려움에서 몹 피해가 1.5배가 아니라 2.25배가 됐다.</li>
 *   <li><b>피격 알림</b> — 화면의 「○○ 피격」이 몫마다 뜨고 정작 처음 맞은 순간에는 안 떴다.</li>
 *   <li><b>방패</b> — 「이미 나눠 피해받을 때 방패 올려도 막으면 안 돼」. 몫은 방패에 막히지
 *       않아야 하고, 처음 맞는 순간은 바닐라처럼 방패가 막아야 한다.</li>
 * </ol>
 *
 * <h2>바닐라 쪽은 무엇으로 대신하는가</h2>
 * <p>{@code hurtServer} 는 살아 있는 월드가 있어야 불러 볼 수 있다. 그래서 바닐라가 하는 일 중
 * 우리 판정 사이에 끼는 것만 26.3 바이트코드 그대로 옮겨 적었다 — {@code Player.hurtServer}
 * 51~107행의 난이도 배율({@link #바닐라_난이도})이다. 옮겨 적은 것이 jar 와 어긋나지 않는지는
 * {@code SpreadDamageFirstHitTargetTest} 가 바이트코드로 붙든다. 그 사이에서 도는
 * <b>우리 코드는 전부 생산 코드</b>다 — 미루기는 진짜 {@link SpreadDamageManager#intercept},
 * 몫을 넣는 중 표시는 {@code deliver} 가 쓰는 {@code asSlice}, HEAD 의 되돌리기는
 * {@link SpreadDamageManager#sliceArrival}, 알림 판정은 {@link StatMirror#shouldAlert}.
 *
 * <p>껍데기 만드는 법은 {@link SpreadDamageOnDeathTest} 와 같다.
 */
class SpreadDamageFirstHitTest {

	private static final float EPSILON = 0.0001F;
	private static final int 몫수 = 8;

	private static final String 완충_풀 = """
			{
			  "perks": [
			    { "id": "sharedfate:cushion", "rarity": "prism", "name": "완충",
			      "effects": [ { "type": "spread_damage", "seconds": 8 } ] }
			  ]
			}
			""";

	@TempDir
	Path 폴더;

	private MinecraftServer server;
	private ServerLevel level;
	private TeamManager manager;

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	void 서버를_세운다() throws Exception {
		SavedDataStorage storage = new SavedDataStorage(폴더.resolve("data"), null,
				TestBootstrap.registries());
		server = (MinecraftServer) unsafe().allocateInstance(DedicatedServer.class);
		setField(MinecraftServer.class, server, "savedDataStorage", storage);
		level = (ServerLevel) unsafe().allocateInstance(ServerLevel.class);
		setField(ServerLevel.class, level, "server", server);
		manager = TeamManager.get(server);
		// 「회차 시작 전 무적」(GameStartManager.blocksDamage)이 팀 상태를 이 길로 찾는다.
		TeamLookup.setServer(server);
		loadPerks(완충_풀);
	}

	@AfterEach
	void 정리() {
		SpreadDamageManager.resetForTesting();
		PerkRegistry.clear();
		TeamLookup.setServer(null);
	}

	// ================================================================== 1. 난이도 배율

	/**
	 * <b>어려움.</b> 좀비 10 은 바닐라에서 15 다. 미룬 양이 이미 15 인데 몫마다 1.5 를 또 곱하면
	 * 22.5 가 들어갔다.
	 */
	@Test
	void 어려움에서_나뉜_몫의_합은_완충이_없을_때와_같다() throws Exception {
		assertEquals(15.0F, 바닐라_난이도(Difficulty.HARD, 좀비가(), 10.0F), EPSILON,
				"옮겨 적은 바닐라 배율부터 맞아야 한다");
		assertEquals(15.0F, 몫으로_들어간_합(Difficulty.HARD, 좀비가(), 10.0F), EPSILON,
				"어려움 배율이 몫마다 다시 곱해졌다");
	}

	/** <b>보통.</b> 바닐라가 배율을 걸지 않는 난이도다. 고치기 전에도 같았고 지금도 같아야 한다. */
	@Test
	void 보통에서_나뉜_몫의_합은_완충이_없을_때와_같다() throws Exception {
		assertEquals(10.0F, 몫으로_들어간_합(Difficulty.NORMAL, 좀비가(), 10.0F), EPSILON);
	}

	/**
	 * <b>쉬움.</b> 배율이 {@code min(x/2+1, x)} 라 최소값이 걸린다. 몫이 2 보다 작으면 그대로
	 * 지나가 우연히 맞아떨어지므로, 몫이 2 를 넘는 큰 한 대(40 → 21, 몫 2.625)로 본다. 고치기 전에는
	 * 몫마다 2.3125 로 깎여 18.5 만 들어갔다.
	 */
	@Test
	void 쉬움에서_나뉜_몫의_합은_완충이_없을_때와_같다() throws Exception {
		assertEquals(21.0F, 바닐라_난이도(Difficulty.EASY, 좀비가(), 40.0F), EPSILON);
		assertEquals(21.0F, 몫으로_들어간_합(Difficulty.EASY, 좀비가(), 40.0F), EPSILON,
				"쉬움 배율이 몫마다 다시 걸렸다");
		// 몫이 작은 한 대도 같아야 한다.
		assertEquals(바닐라_난이도(Difficulty.EASY, 좀비가(), 6.0F),
				몫으로_들어간_합(Difficulty.EASY, 좀비가(), 6.0F), EPSILON);
	}

	/**
	 * <b>평화.</b> 몹 피해는 {@code Player} 가 0 으로 만들고 {@code LivingEntity} 에 닿기 전에
	 * 끝내므로 미뤄지지도 않는다. 배율을 안 타는 낙하는 처음에도 몫에도 그대로다.
	 */
	@Test
	void 평화에서도_나뉜_몫의_합은_완충이_없을_때와_같다() throws Exception {
		assertEquals(0.0F, 몫으로_들어간_합(Difficulty.PEACEFUL, 좀비가(), 10.0F), EPSILON);
		assertEquals(7.0F, 몫으로_들어간_합(Difficulty.PEACEFUL, 낙하(), 7.0F), EPSILON);
	}

	/** 배율을 안 타는 피해는 어느 난이도에서든 원래 값 그대로다. */
	@Test
	void 배율을_안_타는_피해는_어느_난이도에서도_그대로다() throws Exception {
		for (Difficulty difficulty : Difficulty.values()) {
			assertEquals(9.0F, 몫으로_들어간_합(difficulty, 낙하(), 9.0F), EPSILON, difficulty.name());
		}
	}

	/**
	 * 되돌리기는 <b>몫을 받는 그 사람에게, 한 번만</b> 듣는다. 같은 호출 안에서 다른 엔티티가
	 * 맞는 피해(가시 인챈트가 때린 쪽을 찌르는 것)나 같은 사람이 한 번 더 맞는 피해를 몫의 값으로
	 * 바꿔 치면 안 된다.
	 */
	@Test
	void 몫의_되돌리기는_받는_사람에게_한_번만_듣는다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		Entity 다른이 = 좀비();
		SpreadDamageManager.queueForTesting(UUID.randomUUID(), 16.0F, 8);

		float[] 본것 = new float[3];
		SpreadDamageManager.asSliceForTesting(맞은사람, 2.0F, () -> {
			본것[0] = SpreadDamageManager.sliceArrival(다른이, 4.0F);
			본것[1] = SpreadDamageManager.sliceArrival(맞은사람, 3.0F);
			본것[2] = SpreadDamageManager.sliceArrival(맞은사람, 5.0F);
		});

		assertEquals(4.0F, 본것[0], EPSILON, "몫과 상관없는 엔티티의 피해가 바뀌었다");
		assertEquals(2.0F, 본것[1], EPSILON, "몫이 넣으려던 값으로 되돌아가야 한다");
		assertEquals(5.0F, 본것[2], EPSILON, "한 몫을 두 번 되돌렸다");
		assertEquals(6.0F, SpreadDamageManager.sliceArrival(맞은사람, 6.0F), EPSILON,
				"몫을 넣는 중이 아니면 손대지 않는다");
	}

	// ================================================================== 2. 피격 알림

	/**
	 * <b>이번 고침의 본체.</b> 한 대를 미루고 여덟 몫을 넣는 동안 「○○ 피격」 알림은 처음 맞은
	 * 그 바퀴에 한 번만 뜬다. 고치기 전 조건(체력이 줄었는가)으로는 처음 바퀴에 0 번, 몫마다
	 * 한 번씩 여덟 번이었다.
	 */
	@Test
	void 한_대를_미루고_여덟_몫을_넣는_동안_피격_알림은_처음_한_번만_뜬다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ShareTeam team = 완충팀(맞은사람);
		List<Boolean> 알림 = new java.util.ArrayList<>();

		// 맞은 순간: 진짜 intercept 가 미루고, 체력은 그대로다.
		assertEquals(0.0F, SpreadDamageManager.intercept(맞은사람, 좀비가(), 16.0F), EPSILON,
				"완충을 가진 팀의 피해는 미뤄져야 한다");
		알림.add(한_바퀴_알림(맞은사람, 0.0F, 0.0F));

		// 그 뒤 1초마다 한 몫. deliver 가 적는 그대로 몫이 깎은 양을 적고 체력이 그만큼 준다.
		for (int i = 0; i < 몫수; i++) {
			float 몫 = SpreadDamageManager.takeSliceForTesting(team.teamId());
			SpreadDamageManager.noteSliceLoss(맞은사람.getUUID(), 몫);
			알림.add(한_바퀴_알림(맞은사람, -몫, 0.0F));
		}

		assertTrue(알림.getFirst(), "처음 맞은 순간에 알림이 떠야 한다");
		assertEquals(1L, 알림.stream().filter(Boolean::booleanValue).count(),
				"몫마다 알림이 다시 떴다: " + 알림);
	}

	/** 몫이 흡수(노란 하트)를 깎아도 마찬가지다. */
	@Test
	void 몫이_흡수를_깎아도_알림은_뜨지_않는다() {
		assertFalse(StatMirror.shouldAlert(0.0F, 2.0F, 2.0F, 0.0F));
		assertFalse(StatMirror.shouldAlert(-1.0F, 1.0F, 2.0F, 0.0F));
	}

	/** 몫이 들어오는 바퀴에 진짜로 또 맞아 몫보다 더 줄었으면 알린다. */
	@Test
	void 몫과_같은_바퀴에_따로_더_깎이면_알린다() {
		assertTrue(StatMirror.shouldAlert(-5.0F, 0.0F, 2.0F, 0.0F));
		// 같은 바퀴에 새로 미뤄진 한 대가 있어도 알린다.
		assertTrue(StatMirror.shouldAlert(-2.0F, 0.0F, 2.0F, 3.0F));
	}

	/** 완충이 없는 사람은 예전 조건 그대로다. 체력 0.01 넘게 감소 또는 흡수 0.01 넘게 소비. */
	@Test
	void 완충이_없으면_예전_조건과_똑같이_알린다() {
		float[] 값들 = {0.0F, 0.005F, 0.01F, 0.011F, 0.5F, 3.0F, -0.5F};
		for (float 체력감소 : 값들) {
			for (float 흡수소비 : 값들) {
				boolean 예전 = -체력감소 < -0.01F || 흡수소비 > 0.01F;
				assertEquals(예전, StatMirror.shouldAlert(-체력감소, 흡수소비, 0.0F, 0.0F),
						"체력감소 " + 체력감소 + ", 흡수소비 " + 흡수소비);
			}
		}
	}

	/** 바퀴가 꺼내 가지 못한 기록은 버려져 다음 진짜 피해를 가르지 않는다. */
	@Test
	void 꺼내_가지_못한_알림_기록은_바퀴_끝에_버려진다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		SpreadDamageManager.noteSliceLoss(맞은사람.getUUID(), 4.0F);
		SpreadDamageManager.noteDeferredHit(맞은사람.getUUID(), 6.0F);

		SpreadDamageManager.forgetAlertLedger();

		assertEquals(0.0F, SpreadDamageManager.takeSliceLoss(맞은사람.getUUID()));
		assertEquals(0.0F, SpreadDamageManager.takeDeferredAlert(맞은사람.getUUID()));
	}

	// ================================================================== 3. 방패

	/**
	 * <b>몫은 방패에 막히지 않는다.</b> 사람 말 「이미 나눠 피해받을 때 방패 올려도 막으면 안
	 * 돼」. {@code applyItemBlocking} HEAD 가 이 판정을 보고 막은 양 0 으로 끝낸다.
	 */
	@Test
	void 몫을_넣는_중이면_받는_사람의_방패를_건너뛴다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ServerPlayer 동료 = 팀원("동료");
		SpreadDamageManager.queueForTesting(UUID.randomUUID(), 16.0F, 8);

		boolean[] 본것 = new boolean[2];
		SpreadDamageManager.asSliceForTesting(맞은사람, 2.0F, () -> {
			본것[0] = SpreadDamageManager.ignoresShield(맞은사람);
			본것[1] = SpreadDamageManager.ignoresShield(동료);
		});

		assertTrue(본것[0], "몫이 방패에 막힌다");
		assertFalse(본것[1], "몫과 상관없는 사람의 방패까지 꺼졌다");
		assertFalse(SpreadDamageManager.ignoresShield(맞은사람), "몫이 끝났는데 방패가 꺼져 있다");
	}

	/**
	 * <b>HEAD 의 처음 맞을 때 검사도 몫을 받는 사람에게만 건너뛴다.</b> 낙하 방패·광역 중복이 몫을
	 * 버리면 떼어 낸 몫이 그대로 사라졌다(2026-10-06 Orca 검토 F-verified 의 V8). HEAD 가 이 판정을
	 * 그 검사들 앞에서 묻는다는 것은 {@code SpreadDamageFirstHitTargetTest} 가 바이트코드로 붙든다.
	 * 같은 틱에 맞은 <b>다른 팀원</b>의 진짜 피해는 예전처럼 광역 중복 판정을 받아야 한다.
	 */
	@Test
	void 몫을_받는_사람만_처음_맞을_때의_버리기_검사를_건너뛴다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ServerPlayer 동료 = 팀원("동료");
		SpreadDamageManager.queueForTesting(UUID.randomUUID(), 16.0F, 8);

		assertFalse(SpreadDamageManager.receivingSlice(맞은사람), "몫을 넣기 전부터 검사가 꺼져 있다");
		boolean[] 본것 = new boolean[2];
		SpreadDamageManager.asSliceForTesting(맞은사람, 2.0F, () -> {
			본것[0] = SpreadDamageManager.receivingSlice(맞은사람);
			본것[1] = SpreadDamageManager.receivingSlice(동료);
		});

		assertTrue(본것[0], "몫이 낙하 방패·광역 중복 검사를 다시 지나 버려진다");
		assertFalse(본것[1], "몫과 상관없는 팀원의 진짜 피해까지 검사를 건너뛴다");
		assertFalse(SpreadDamageManager.receivingSlice(맞은사람), "몫이 끝났는데 검사가 꺼져 있다");
	}

	/** 큐가 하나도 없으면 방패 판정에 손대지 않는다. */
	@Test
	void 완충이_없으면_방패는_그대로다() throws Exception {
		assertFalse(SpreadDamageManager.ignoresShield(팀원("아무나")));
	}

	/**
	 * 처음 맞는 순간 방패가 다 막았으면 미룰 것도 없다. 방패 판정 뒤에 불리는 intercept 가 받는
	 * 값이 0 이라 큐도, 팀원 피격음도, 피격 알림도 생기지 않는다.
	 */
	@Test
	void 방패가_다_막은_한_대는_몫으로도_들어오지_않는다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ShareTeam team = 완충팀(맞은사람);

		float 원래 = 12.0F;
		float 막은양 = 원래;
		float 넘긴것 = SpreadDamageManager.intercept(맞은사람, 좀비가(), 원래 - 막은양);

		assertEquals(0.0F, 넘긴것, EPSILON);
		assertEquals(0.0F, SpreadDamageManager.remaining(team.teamId()), EPSILON,
				"방패가 막은 피해가 몫으로 들어온다");
		assertEquals(0.0F, SpreadDamageManager.deferredHit(맞은사람.getUUID()), EPSILON);
		assertFalse(한_바퀴_알림(맞은사람, 0.0F, 0.0F), "막은 한 대에 피격 알림이 떴다");
	}

	/** 덜 막았으면 막고 남은 양만 미룬다. */
	@Test
	void 방패가_덜_막으면_남은_양만_미룬다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ShareTeam team = 완충팀(맞은사람);

		SpreadDamageManager.intercept(맞은사람, 좀비가(), 12.0F - 9.0F);

		assertEquals(3.0F, SpreadDamageManager.remaining(team.teamId()), EPSILON);
	}

	// ------------------------------------------------------------------ 한 바퀴

	/**
	 * 한 대를 맞아 미루고 여덟 몫으로 나눠 넣는 동안 {@code LivingEntity.hurtServer} 본문에
	 * 실제로 들어간 피해의 합.
	 *
	 * <p>처음 맞을 때는 {@code Player} 가 배율을 걸고 내려와 미뤄진다. 몫은
	 * {@code deliver → victim.hurtServer} 로 {@code Player} 를 다시 지나 배율을 또 맞고 HEAD 에
	 * 닿는다 — 거기서 생산 코드({@code sliceArrival})가 하는 일까지가 한 몫이다.
	 */
	private float 몫으로_들어간_합(Difficulty 난이도, DamageSource 피해원, float 원래) throws Exception {
		SpreadDamageManager.resetForTesting();
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		UUID teamId = UUID.randomUUID();
		float 미룬양 = 바닐라_난이도(난이도, 피해원, 원래);
		if (미룬양 == 0.0F) {
			// Player 가 0 이면 그 자리에서 끝낸다(108~115행). LivingEntity 까지 오지 않는다.
			return 0.0F;
		}
		SpreadDamageManager.queueForTesting(teamId, 미룬양, 몫수);
		float 합 = 0.0F;
		for (int i = 0; i < 몫수; i++) {
			float 몫 = SpreadDamageManager.takeSliceForTesting(teamId);
			float[] 들어간것 = {0.0F};
			SpreadDamageManager.asSliceForTesting(맞은사람, 몫, () -> {
				float 도착 = 바닐라_난이도(난이도, 피해원, 몫);
				if (도착 != 0.0F) {
					들어간것[0] = SpreadDamageManager.sliceArrival(맞은사람, 도착);
				}
			});
			합 += 들어간것[0];
		}
		return 합;
	}

	/** {@code StatMirror.collectDeltas} 가 한 사람에 대해 하는 일 그대로. */
	private static boolean 한_바퀴_알림(ServerPlayer player, float 체력변화, float 흡수소비) {
		float sliceLoss = SpreadDamageManager.takeSliceLoss(player.getUUID());
		float deferred = SpreadDamageManager.takeDeferredAlert(player.getUUID());
		boolean alert = StatMirror.shouldAlert(체력변화, 흡수소비, sliceLoss, deferred);
		SpreadDamageManager.forgetAlertLedger();
		return alert;
	}

	/**
	 * 26.3 {@code Player.hurtServer} 51~107행을 그대로 옮긴 것.
	 *
	 * <pre>
	 *   if (source.scalesWithDifficulty()) {
	 *       if (PEACEFUL) amount = 0;
	 *       if (EASY)     amount = Math.min(amount / 2 + 1, amount);
	 *       if (HARD)     amount = amount * 3 / 2;
	 *   }
	 * </pre>
	 */
	static float 바닐라_난이도(Difficulty difficulty, DamageSource source, float amount) {
		if (source.scalesWithDifficulty()) {
			if (difficulty == Difficulty.PEACEFUL) {
				amount = 0.0F;
			}
			if (difficulty == Difficulty.EASY) {
				amount = Math.min(amount / 2.0F + 1.0F, amount);
			}
			if (difficulty == Difficulty.HARD) {
				amount = amount * 3.0F / 2.0F;
			}
		}
		return amount;
	}

	// ------------------------------------------------------------------ 도우미

	private ShareTeam 완충팀(ServerPlayer leader) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.runStarted = true;
		state.ownedPerks.add("sharedfate:cushion");
		ShareTeam team = manager.createTeam("팀" + leader.getUUID(), leader.getUUID(), state);
		assertTrue(team != null, "팀이 만들어져야 한다");
		return team;
	}

	private ServerPlayer 팀원(String name) throws Exception {
		ServerPlayer player = (ServerPlayer) unsafe().allocateInstance(ServerPlayer.class);
		UUID id = UUID.randomUUID();
		setField(Entity.class, player, "level", level);
		setField(Entity.class, player, "uuid", id);
		setField(Player.class, player, "gameProfile", new GameProfile(id, name));
		return player;
	}

	private static Zombie 좀비() throws Exception {
		return (Zombie) unsafe().allocateInstance(Zombie.class);
	}

	/**
	 * 좀비가 때린 피해. 때린 것이 플레이어가 아닌 생물이라 바닐라 난이도 배율을 탄다.
	 *
	 * <p>레지스트리 손잡이가 아니라 {@code Holder.direct} 로 감싼다. 시험용 레지스트리는 태그가
	 * 묶이지 않아 {@code capture} 의 {@code source.is(태그)} 가 「Tags not bound」로 터지는데,
	 * 직접 손잡이는 태그를 묻으면 거짓을 돌려준다 — 바닐라에서 이 피해 종류가 그 태그들
	 * ({@code bypasses_invulnerability}·{@code bypasses_cooldown})에 없는 것과 같은 답이다.
	 */
	private static DamageSource 좀비가() throws Exception {
		DamageSource source = new DamageSource(
				Holder.direct(피해종류(DamageTypes.MOB_ATTACK).value()), 좀비());
		assertTrue(source.scalesWithDifficulty(), "몹 피해는 난이도 배율을 타야 한다");
		return source;
	}

	private static DamageSource 낙하() {
		DamageSource source = new DamageSource(피해종류(DamageTypes.FALL));
		assertFalse(source.scalesWithDifficulty(), "낙하는 난이도 배율을 안 탄다");
		return source;
	}

	private static Holder<DamageType> 피해종류(ResourceKey<DamageType> key) {
		return TestBootstrap.registries().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
	}

	private void loadPerks(String json) throws IOException {
		Path dir = 폴더.resolve("perks");
		Files.createDirectories(dir);
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), json, StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
	}

	private static void setField(Class<?> owner, Object target, String name, Object value)
			throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (sun.misc.Unsafe) field.get(null);
	}
}
