package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「크리스탈 보호막」·「다시 선 쇠창살」이 <b>월드 없이 확인할 수 있는 만큼</b>을 붙든다.
 *
 * <p>여기서 지키는 것은 넷이다.
 *
 * <ol>
 *   <li><b>정적 상태가 새지 않는다</b> — {@link CrystalWatch} 는 월드보다 오래 살고, 화살
 *       면역을 켠 채 잊으면 다음 월드의 크리스탈까지 화살에 맞지 않는다.</li>
 *   <li><b>값이 흔들리지 않는다</b> — 우리 치수, 카드 둘의 값, 카드 이름과 id.</li>
 *   <li><b>2026-10-01 에 걷은 채굴 감소가 되살아나지 않고, 함께 걷히지도 않았다</b> — 이 카드는
 *       채굴 속도를 한 톨도 안 깎고, 같은 믹스인을 타는 {@code mining_speed} 증강은 그대로
 *       돈다. 이 파일에서 가장 새로 중요해진 자리다.</li>
 *   <li><b>믹스인 대상이 26.3 에 실제로 있다</b> — 이 파일에서 가장 중요한 부분이다.
 *       {@code sharedfate.mixins.json} 에 refmap 이 없어 <b>대상 서술자가 틀려도 빌드가 그냥
 *       통과</b>하고, 크리스탈을 처음 때리는 순간에야 터진다. 그때는 이미 엔드 전투 중이다.</li>
 * </ol>
 */
class TrialCrystalGuardTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	/** 정적 상태라 시험끼리 샌다. 앞뒤로 비운다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		CrystalWatch.clearState();
	}

	// -------------------------------------------------- 정적 상태가 새지 않는가

	/**
	 * 켜는 것보다 <b>끄는 것</b>이 중요하다.
	 *
	 * <p>{@link CrystalWatch} 는 정적이라 월드보다 오래 산다. 되돌리지 않으면 다음 전투는
	 * 물론 새로 만든 월드의 크리스탈까지 화살에 맞지 않고, 그 증상은 「활이 안 먹힌다」라는
	 * 아주 엉뚱한 모양으로 나타난다.
	 */
	@Test
	void 화살_면역이_켜지고_clearState_로_반드시_꺼진다() {
		assertFalse(CrystalWatch.arrowImmune(), "처음에는 꺼져 있어야 한다");

		CrystalWatch.setArrowImmune(true);
		assertTrue(CrystalWatch.arrowImmune());

		TrialCrystalGuard.clearState();
		assertFalse(CrystalWatch.arrowImmune(),
				"TrialCrystalGuard.clearState 가 깃발을 내리지 않으면 다음 월드까지 샌다");
	}

	/** 판 자체를 접을 때 쓰는 쪽. 이쪽도 깃발을 내린다. */
	@Test
	void CrystalWatch_clearState_도_화살_면역을_내린다() {
		CrystalWatch.setArrowImmune(true);
		CrystalWatch.clearState();
		assertFalse(CrystalWatch.arrowImmune());
	}

	/** 「표적」 카드가 읽는 칸. 기록이 남고, 판이 끝나면 지워진다. */
	@Test
	void 깬_사람_기록이_남고_clearState_로_지워진다() {
		assertNull(CrystalWatch.lastBreaker(), "아무도 깨지 않았으면 null 이다");

		UUID breaker = UUID.randomUUID();
		CrystalWatch.noteBreaker(breaker);
		assertEquals(breaker, CrystalWatch.lastBreaker());

		UUID later = UUID.randomUUID();
		CrystalWatch.noteBreaker(later);
		assertEquals(later, CrystalWatch.lastBreaker(), "가장 최근에 깬 사람이어야 한다");

		CrystalWatch.clearState();
		assertNull(CrystalWatch.lastBreaker(),
				"기록이 남으면 다음 판의 드래곤이 지난 판 사람을 노린다");
	}

	/**
	 * 주인 없는 피해는 기록을 <b>지우지 않는다.</b>
	 *
	 * <p>크리스탈은 하나를 깨면 옆 것이 연쇄로 딸려 터진다. 그 연쇄를 「주인 없음」으로
	 * 덮어쓰면 방금 정해진 표적이 같은 틱에 사라진다.
	 */
	@Test
	void null_은_깬_사람_기록을_지우지_않는다() {
		UUID breaker = UUID.randomUUID();
		CrystalWatch.noteBreaker(breaker);

		CrystalWatch.noteBreaker(null);

		assertEquals(breaker, CrystalWatch.lastBreaker(),
				"연쇄 폭발 한 번에 표적이 사라지면 「표적」 카드가 무너진다");
	}

	/** {@link TrialCrystalGuard#clearState()} 는 <b>남의 상태</b>인 깬 사람을 건드리지 않는다. */
	@Test
	void 카드를_접어도_깬_사람_기록은_남는다() {
		UUID breaker = UUID.randomUUID();
		CrystalWatch.noteBreaker(breaker);

		TrialCrystalGuard.clearState();

		assertEquals(breaker, CrystalWatch.lastBreaker(),
				"깬 사람의 주인은 이 카드가 아니라 판이다. 지우는 것은 CrystalWatch.clearState 뿐");
	}

	// -------------------------------------------------- 값이 흔들리지 않는가

	/**
	 * 「다시 선 쇠창살」은 채굴 속도를 <b>한 톨도</b> 안 깎는다.
	 *
	 * <p>세 걸음을 밟았다 — ① 채굴 피로 I(실제로는 70% 감소), ② 사람이 「무딘곡괭이 이거
	 * 채굴피로1은 심하고 채굴 속도 15프로감소로」라고 해서 직접 15% 를 깎는 쪽, ③ 2026-10-01 에
	 * 사람이 「무딘곡괭이는 채굴감소 없앳으니 이름 변경해」라고 해서 <b>통째로 걷음.</b>
	 *
	 * <p>효과가 둘에서 하나로 줄어든 것은 <b>빠뜨린 것이 아니라 사람이 알고 고른 것</b>이다.
	 * 카드 하나만 보지 않고 목록 전체를 훑는 이유는, 되살릴 때 다른 카드에 몰래 켜 두는 길을
	 * 막기 위해서다.
	 */
	@Test
	void 다시_선_쇠창살은_채굴_속도를_한_톨도_안_깎는다() {
		assertFalse(guardOf("sharedfate:iron_cage").digSlowdown(),
				"2026-10-01 에 사람이 채굴 15% 감소를 걷었다. 되살리려면 사람에게 먼저 물을 것");

		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				if (risk instanceof TrialCatalog.Risk.CrystalGuard guard) {
					assertFalse(guard.digSlowdown(),
							trial.name() + " 가 채굴 감소를 켰다. 지금 그것을 켜는 카드는 없어야 한다 —"
									+ " 켜려면 카드 이름과 설명도 함께 고쳐야 한다");
				}
			}
		}
	}

	/**
	 * ⚠⚠ <b>증강의 채굴 속도 효과는 그대로 돈다.</b>
	 *
	 * <p>이 카드의 몫과 {@code mining_speed} 증강이 <b>같은 믹스인 한 자리</b>를 탄다. 그래서
	 * 「한쪽만 걷으려다 둘 다 걷는」 사고가 날 수 있는데, 그 증상은 <b>빌드도 로그도 조용하다</b> —
	 * 실버 7 「광맥 감각」의 대가가 말없이 사라질 뿐이다.
	 *
	 * <p>믹스인 클래스 파일의 상수 풀에서 {@code PerkBlockBreaks.scaleDestroySpeed} 호출을 직접
	 * 찾는다. 믹스인 클래스를 {@code .class} 리터럴로 부르면 믹스인 환경이 막으므로 이름으로
	 * 읽는다 — 아래 서술자 시험들과 같은 방식이다.
	 */
	@Test
	void 증강의_채굴_속도_효과는_그대로_돈다() throws IOException {
		String mixin = classFileOf(CrystalWatch.class,
				"com.sharedfate.mixin.PlayerMiningSpeedMixin");

		assertTrue(mixin.contains("com/sharedfate/perk/PerkBlockBreaks"),
				"믹스인이 PerkBlockBreaks 를 더 부르지 않는다. 시련 몫을 걷으며 증강까지 걷었다");
		assertTrue(mixin.contains("scaleDestroySpeed"),
				"믹스인에 scaleDestroySpeed 호출이 없다. mining_speed 증강이 죽었다");
		assertDoesNotThrow(
				() -> com.sharedfate.perk.PerkBlockBreaks.class.getDeclaredMethod(
						"scaleDestroySpeed",
						net.minecraft.world.entity.player.Player.class,
						BlockState.class, float.class),
				"PerkBlockBreaks.scaleDestroySpeed 가 사라졌다. 증강의 채굴 속도가 걸릴 자리가 없다");
		assertNotNull(com.sharedfate.perk.PerkEffectType.MINING_SPEED,
				"mining_speed 효과 유형이 사라졌다. 정의 파일이 그 자리에서 읽히지 않는다");
	}

	/**
	 * 걷은 길은 <b>살아 있지만 아무도 켜지 않는다.</b>
	 *
	 * <p>배율 자체를 지우지 않은 까닭은 위 시험에 적었다 — 깎는 자리를 증강이 함께 쓴다. 되살릴
	 * 사람이 쓸 수 있게 범위만 붙든다. <b>여기서 값을 못박지 않는 것이 일부러다</b> — 지금 도는
	 * 효과가 아닌데 0.85 를 붙들면 「15% 감소가 아직 산다」로 읽힌다.
	 */
	@Test
	void 걷은_채굴_배율은_되살릴_수_있는_범위에_있다() {
		assertTrue(TrialCrystalGuard.DIG_SLOWDOWN_MULTIPLIER < 1.0,
				"1 이상이면 빨라지는 쪽인데, 이 길은 서버에서만 계산되므로 빨라지지 않는다");
		assertTrue(TrialCrystalGuard.DIG_SLOWDOWN_MULTIPLIER > 0.0,
				"0 이면 그 블록을 영영 캘 수 없다. 이 갈래는 시간을 빼앗는 것이지 채굴을 막는 것이 아니다");
	}

	/**
	 * 이름은 「다시 선 쇠창살」이고 <b>id 는 그대로</b>다.
	 *
	 * <p>이름은 2026-10-01 에 바꿨다 — 효과가 쇠창살 하나만 남았으므로 「무딘 곡괭이」가 이름에
	 * 남아 있으면 카드가 그 자리에서 거짓을 말한다.
	 *
	 * <p><b>id 를 바꾸면 안 된다.</b> 저장된 세션 상태·명령 자동완성·월드 파일이
	 * {@code sharedfate:iron_cage} 를 들고 있다. 바꾸면 이미 이 카드를 받은 판이 복원될 때
	 * 카드가 통째로 사라진다.
	 */
	@Test
	void 이름은_다시_선_쇠창살이고_id_는_그대로다() {
		TrialCatalog.Trial cage = TrialCatalog.byId("sharedfate:iron_cage");
		assertNotNull(cage, "id 를 바꾸면 저장된 판에서 이 카드가 통째로 사라진다");

		assertEquals("다시 선 쇠창살", cage.name(),
				"「쇠창살과 무딘 곡괭이」에서 바꿨다. 채굴 감소가 없으므로 곡괭이를 말하면 거짓이다");
		assertFalse(cage.name().contains("곡괭이"),
				"이름에 곡괭이가 남아 있으면 룰렛이 없는 효과를 약속한다");
		assertFalse(cage.description().contains("채굴"),
				"설명에 채굴이 남아 있으면 룰렛이 없는 효과를 약속한다");
	}

	/**
	 * 남은 효과 하나 — <b>쇠창살이 없던 탑에도 다시 생긴다.</b>
	 *
	 * <p>효과가 둘에서 하나로 줄었으므로 이 하나가 사라지면 카드가 아무 일도 하지 않는 빈
	 * 카드가 된다. 우리의 치수와 연결 모양은 아래 시험들이 따로 붙든다.
	 */
	@Test
	void 쇠창살은_없던_탑에도_다시_생긴다() {
		TrialCatalog.Risk.CrystalGuard cage = guardOf("sharedfate:iron_cage");

		assertTrue(cage.restoreCage(),
				"이 카드에 남은 효과는 이것 하나뿐이다. 꺼지면 아무 일도 하지 않는 카드가 된다");
		assertFalse(cage.arrowImmune(),
				"화살 면역은 「크리스탈 보호막」의 몫이다. 둘이 같아지면 룰렛에 이름만 둘이 된다");
	}

	/**
	 * 선언이 시효보다 <b>먼저</b> 닿는다.
	 *
	 * <p>상태이상이 아니게 되었으므로 이제 「효과가 끊긴다」가 아니라 「시효가 지난다」다. 갱신
	 * 주기가 시효보다 길거나 같으면 곡괭이가 주기마다 빨라졌다 느려진다.
	 *
	 * <p>지금 이 갈래를 켜는 카드가 없어 도는 코드는 아니지만, <b>되살릴 사람이 여기서 멈추게</b>
	 * 남겨 둔다 — 두 값의 관계가 어긋나면 게임을 띄워 봐야만 알 수 있는 종류의 고장이다.
	 */
	@Test
	void 무뎌짐은_시효가_지나기_전에_다시_선언된다() {
		assertTrue(TrialCrystalGuard.DIG_REFRESH_INTERVAL > 0);
		assertTrue(TrialCrystalGuard.DIG_REFRESH_INTERVAL < TrialCrystalGuard.DIG_LAPSE_TICKS,
				"갱신 주기가 시효보다 길면 무뎌짐이 주기마다 풀린다");
		assertTrue(TrialCrystalGuard.DIG_LAPSE_TICKS > 0,
				"시효가 없으면 서버 강제 종료 뒤에 곡괭이가 영영 무뎌진 채로 남는다");
	}

	/**
	 * 아무것도 선언되지 않았으면 <b>바닐라 그대로</b>다.
	 *
	 * <p>{@code null} 과 서버 쪽이 아닌 플레이어에서 받은 값을 그대로 돌려주는지 본다. 여기서
	 * 값이 달라지면 <b>시련과 무관한 모든 채굴</b>이 느려진다 — 이 파일에서 살아 있는 서버 없이
	 * 확인할 수 있는 가장 중요한 성질이다.
	 *
	 * <p>2026-10-01 에 채굴 감소를 걷은 뒤로는 <b>어떤 카드도 선언을 적지 않으므로</b> 이 성질이
	 * 곧 「시련은 채굴 속도를 건드리지 않는다」가 된다.
	 */
	@Test
	void 선언이_없으면_채굴_속도를_건드리지_않는다() {
		TrialCrystalGuard.clearState();

		assertEquals(3.5F, TrialCrystalGuard.scaleDestroySpeed(null, 3.5F), 0.0F,
				"플레이어가 없으면 원래 값이다");
		assertEquals(0.0F, TrialCrystalGuard.scaleDestroySpeed(null, 0.0F), 0.0F,
				"0 은 「캘 수 없는 블록」이다. 곱하면 안 된다");
		assertEquals(Float.NaN, TrialCrystalGuard.scaleDestroySpeed(null, Float.NaN),
				"유한하지 않은 값은 손대지 않는다");
	}

	/**
	 * 카드 둘이 <b>서로 다른 값</b>을 쓴다.
	 *
	 * <p>둘이 같아지면 룰렛에 이름만 둘이고 판은 하나가 된다.
	 */
	@Test
	void 카드_둘은_서로_다른_값을_쓴다() {
		TrialCatalog.Risk.CrystalGuard ward = guardOf("sharedfate:crystal_ward");
		TrialCatalog.Risk.CrystalGuard cage = guardOf("sharedfate:iron_cage");

		assertEquals(new TrialCatalog.Risk.CrystalGuard(true, false, false), ward,
				"「크리스탈 보호막」은 화살 면역만 건다");
		assertEquals(new TrialCatalog.Risk.CrystalGuard(false, true, false), cage,
				"「다시 선 쇠창살」은 우리만 세운다. 채굴 감소는 2026-10-01 에 걷었다");
		assertNotEquals(ward, cage);
	}

	/**
	 * 주기는 <b>받은 틱부터</b> 센다.
	 *
	 * <p>월드 시간으로 세면 카드마다 위상이 겹치고, 복원 직후 {@code now < granted} 일 때
	 * 음수로 떨어진다. {@link TrialRisks#firesAt} 와 달리 <b>받은 그 틱에 곧바로 참</b>이라는
	 * 것도 함께 붙든다 — 이 카드는 피해를 주지 않아 예고할 것이 없고, 늦게 걸리면 카드를 읽은
	 * 직후 아무 일도 일어나지 않아 고장으로 보인다.
	 */
	@Test
	void 주기는_받은_틱부터_세고_받자마자_한_번_돈다() {
		long granted = 1_000L;
		int interval = TrialCrystalGuard.CAGE_REPAIR_INTERVAL;

		assertTrue(TrialCrystalGuard.refreshesAt(granted, granted, interval),
				"받은 그 틱에 곧바로 한 번 돌아야 한다");
		assertFalse(TrialCrystalGuard.refreshesAt(granted + 1L, granted, interval));
		assertTrue(TrialCrystalGuard.refreshesAt(granted + interval, granted, interval));
		assertTrue(TrialCrystalGuard.refreshesAt(granted + interval * 3L, granted, interval));

		// 복원 직후 월드 시각이 뒤로 가 있어도 음수 위상이 되지 않는다.
		assertTrue(TrialCrystalGuard.refreshesAt(granted - 500L, granted, interval));

		assertFalse(TrialCrystalGuard.refreshesAt(granted, granted, 0),
				"주기가 0 이면 아무 일도 하지 않는다. 나누기로 터지면 안 된다");
	}

	// -------------------------------------------------- 쇠창살 우리

	/**
	 * 우리의 치수와 칸 수가 바닐라 {@code EndSpikeFeature.placeSpike} 와 같다.
	 *
	 * <p>5×5×4 = 100칸 중 안쪽 3×3×3 = 27칸이 비어 73칸이 남는다. 바닥 한가운데가 비는 덕분에
	 * 크리스탈을 받치는 기반암을 덮지 않는다.
	 */
	@Test
	void 우리는_바닐라와_같은_73칸이다() {
		assertEquals(2, TrialCrystalGuard.CAGE_HALF_WIDTH);
		assertEquals(3, TrialCrystalGuard.CAGE_TOP);

		int placed = 0;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = 0; dy <= 3; dy++) {
					if (TrialCrystalGuard.cagePart(dx, dz, dy)) {
						placed++;
					}
				}
			}
		}
		assertEquals(73, placed, "5×5×4 에서 안쪽 3×3×3 을 뺀 값이다");

		assertFalse(TrialCrystalGuard.cagePart(0, 0, 0),
				"바닥 한가운데는 크리스탈을 받치는 기반암 자리다. 덮으면 안 된다");
		assertFalse(TrialCrystalGuard.cagePart(1, 1, 2), "안쪽은 비어 있어야 사람이 들어간다");
		assertTrue(TrialCrystalGuard.cagePart(2, 0, 0), "네 벽면");
		assertTrue(TrialCrystalGuard.cagePart(0, 0, 3), "지붕");
	}

	/**
	 * 쇠창살 <b>연결 모양</b>이 바닐라와 같다.
	 *
	 * <p>축이 엇갈려 보이는 것이 맞다 — {@code x = ±2} 벽에 선 창살은 z 방향으로 이어지므로
	 * 북·남이 켜진다. 여기를 뒤집으면 창살이 전부 끊긴 토막으로 보이는데, 게임을 띄우기
	 * 전에는 알아채기 어렵다.
	 */
	@Test
	void 쇠창살_연결_모양이_바닐라와_같다() {
		BlockState west = TrialCrystalGuard.cageState(-2, 0, 0);
		assertTrue(west.is(Blocks.IRON_BARS));
		assertTrue(west.getValue(IronBarsBlock.NORTH), "x = -2 벽은 z 방향으로 이어진다");
		assertTrue(west.getValue(IronBarsBlock.SOUTH));
		assertFalse(west.getValue(IronBarsBlock.WEST), "벽 바깥으로 뻗으면 안 된다");
		assertFalse(west.getValue(IronBarsBlock.EAST));

		// 북서 모서리({@code x = -2, z = -2}). 바깥이 아니라 안쪽 두 방향으로만 이어야 한다.
		BlockState corner = TrialCrystalGuard.cageState(-2, -2, 0);
		assertFalse(corner.getValue(IronBarsBlock.NORTH), "북쪽은 우리 바깥이다");
		assertTrue(corner.getValue(IronBarsBlock.SOUTH), "모서리는 안쪽 두 방향만 잇는다");
		assertFalse(corner.getValue(IronBarsBlock.WEST), "서쪽은 우리 바깥이다");
		assertTrue(corner.getValue(IronBarsBlock.EAST));

		BlockState roof = TrialCrystalGuard.cageState(0, 0, 3);
		assertTrue(roof.getValue(IronBarsBlock.NORTH), "지붕은 격자라 네 방향이 모두 켜진다");
		assertTrue(roof.getValue(IronBarsBlock.SOUTH));
		assertTrue(roof.getValue(IronBarsBlock.WEST));
		assertTrue(roof.getValue(IronBarsBlock.EAST));
	}

	// -------------------------------------------------- 믹스인 대상 (가장 중요한 시험)

	/**
	 * {@code EndCrystalGuardMixin} 이 무는 자리가 26.3 에 그 서술자 그대로 있다.
	 *
	 * <p><b>{@code EndCrystal} 이 스스로 선언</b>해야 한다. 상속만 받는 자리에 걸면
	 * {@code SlotExpandedLockMixin} 사고를 되풀이한다 — 하위 클래스가 재정의해 한 번도 돌지
	 * 않는데 빌드도 로그도 조용했다.
	 */
	@Test
	void 크리스탈_피해_메서드가_그_서술자_그대로_있다() {
		Method target = assertDoesNotThrow(
				() -> EndCrystal.class.getDeclaredMethod("hurtServer",
						ServerLevel.class, DamageSource.class, float.class),
				"EndCrystal 이 hurtServer 를 스스로 선언하지 않는다. 걸 자리를 다시 찾을 것");

		assertEquals(boolean.class, target.getReturnType(),
				"CallbackInfoReturnable<Boolean> 의 타입이 이것과 같아야 한다");
		assertTrue(Modifier.isPublic(target.getModifiers()));
		assertFalse(Modifier.isStatic(target.getModifiers()), "정적이면 인자 번호가 하나씩 밀린다");
	}

	/**
	 * <b>재정의 함정이 없다</b>는 것을 못박는다.
	 *
	 * <p>{@code hurtServer} 가 {@code final} 인 덕분에 하위 클래스가 가로챌 길이 아예 없다.
	 * 이 성질이 사라지면 「누군가 상속해 재정의하면 조용히 죽는다」가 되살아나므로, 그때는
	 * 자리를 다시 고르거나 {@code EndCrystal} 의 모든 하위 클래스를 확인해야 한다.
	 */
	@Test
	void 크리스탈_피해_메서드는_final_이라_재정의될_수_없다() throws NoSuchMethodException {
		Method target = EndCrystal.class.getDeclaredMethod("hurtServer",
				ServerLevel.class, DamageSource.class, float.class);

		assertTrue(Modifier.isFinal(target.getModifiers()),
				"final 이 풀렸다. 하위 클래스가 재정의하면 이 믹스인은 조용히 죽는다");
	}

	/**
	 * 「이번 한 방이 정말로 깼다」를 가리키는 지점이 그대로 있다.
	 *
	 * <p>{@code @At("INVOKE")} 가 {@code onDestroyedBy} 호출을 찾는다. 이 메서드가 사라지거나
	 * 인라인되면 「깬 사람」 기록이 통째로 멈추고, 그러면 「표적」 카드가 언제나 무작위로
	 * 물러난다 — <b>빌드도 로그도 조용한</b> 종류의 고장이다.
	 */
	@Test
	void 깨짐_처리_메서드가_그_서술자_그대로_있다() {
		Method target = assertDoesNotThrow(
				() -> EndCrystal.class.getDeclaredMethod("onDestroyedBy",
						ServerLevel.class, DamageSource.class),
				"EndCrystal.onDestroyedBy 가 사라졌다. 깬 사람을 적을 자리를 다시 고를 것");

		assertEquals(void.class, target.getReturnType());
		assertFalse(Modifier.isStatic(target.getModifiers()));
	}

	/**
	 * <b>믹스인 주석에 적은 문자열이 실제 클래스 파일에 있는가.</b> 이 파일에서 가장 중요한 줄이다.
	 *
	 * <p>refmap 이 없으므로 {@code @Inject} 의 {@code method}·{@code target} 문자열은 아무도
	 * 검사하지 않는다. 오타 한 글자가 있어도 빌드가 통과하고, 크리스탈을 처음 때리는 순간
	 * 터진다. 그때는 이미 엔드 전투 중이고 이 모드는 전멸하면 월드가 지워진다.
	 *
	 * <p>그래서 <b>양쪽 클래스 파일의 상수 풀을 맞대어 본다.</b> 바닐라 쪽에 그 서술자가 있고,
	 * 믹스인 쪽에 그 서술자를 담은 문자열이 있으면 둘이 가리키는 것이 같다.
	 * {@code SpreadDamageTargetTest} 와 같은 방식이다.
	 *
	 * <p>믹스인 클래스를 {@code .class} 리터럴로 참조하지 않고 이름으로 읽는 이유는, 믹스인
	 * 클래스를 직접 불러오면 믹스인 환경이 막기 때문이다.
	 */
	@Test
	void 믹스인_주석의_서술자가_바닐라_클래스_파일과_맞는다() throws IOException {
		String vanilla = classFileOf(EndCrystal.class, EndCrystal.class.getName());
		String mixin = classFileOf(CrystalWatch.class, "com.sharedfate.mixin.EndCrystalGuardMixin");

		String hurtDescriptor = "(Lnet/minecraft/server/level/ServerLevel;"
				+ "Lnet/minecraft/world/damagesource/DamageSource;F)Z";
		String destroyDescriptor = "(Lnet/minecraft/server/level/ServerLevel;"
				+ "Lnet/minecraft/world/damagesource/DamageSource;)V";

		assertTrue(vanilla.contains(hurtDescriptor),
				"EndCrystal 에 hurtServer 의 그 서술자가 없다");
		assertTrue(vanilla.contains("onDestroyedBy"),
				"EndCrystal 에 onDestroyedBy 라는 이름이 없다");
		assertTrue(vanilla.contains(destroyDescriptor),
				"EndCrystal 에 onDestroyedBy 의 그 서술자가 없다");

		assertTrue(mixin.contains("hurtServer" + hurtDescriptor),
				"믹스인의 method 문자열이 바닐라 서술자와 어긋난다. @Inject 가 대상을 못 찾는다");
		assertTrue(mixin.contains(
						"Lnet/minecraft/world/entity/boss/enderdragon/EndCrystal;"
								+ "onDestroyedBy" + destroyDescriptor),
				"믹스인의 INVOKE target 문자열이 바닐라 호출과 어긋난다. 깬 사람이 적히지 않는다");
	}

	/**
	 * 투사체만 골라 거절하는 데 필요한 바닐라 자리들.
	 *
	 * <p>{@code IS_PROJECTILE} 이 없어지거나 {@code DamageSource.is(TagKey)} 가 사라지면
	 * 「화살은 막고 근접·폭발은 통과」가 성립하지 않는다. <b>근접과 폭발을 통과시키는 것이
	 * 특히 중요하다</b> — 막히면 크리스탈을 깰 방법이 없어져 전투가 끝나지 않는다.
	 */
	@Test
	void 투사체_판정에_필요한_바닐라_자리가_그대로_있다() throws IOException {
		assertNotNull(DamageTypeTags.IS_PROJECTILE);
		assertDoesNotThrow(
				() -> DamageSource.class.getDeclaredMethod("is", TagKey.class),
				"DamageSource.is(TagKey) 가 사라졌다");

		// 태그의 내용은 데이터팩이라 살아 있는 서버 없이는 레지스트리로 볼 수 없다. 대신
		// 바닐라 jar 에 들어 있는 태그 파일을 그대로 읽는다.
		String tag = resourceOf(EndCrystal.class,
				"/data/minecraft/tags/damage_type/is_projectile.json");

		assertTrue(tag.contains("minecraft:arrow"), "화살이 투사체 태그에서 빠졌다");
		assertTrue(tag.contains("minecraft:trident"), "삼지창이 투사체 태그에서 빠졌다");
		assertFalse(tag.contains("minecraft:player_attack"),
				"근접이 투사체 태그에 들어왔다. 그러면 크리스탈을 깰 방법이 없어진다");
		assertFalse(tag.contains("minecraft:explosion"),
				"폭발이 투사체 태그에 들어왔다. 그러면 크리스탈을 깰 방법이 없어진다");
	}

	/**
	 * 쏜 사람을 되찾는 두 단계가 그대로 있다.
	 *
	 * <p>화살이면 {@code getDirectEntity()} 는 화살이고 {@code getEntity()} 가 쏜 사람이다.
	 * {@code VictoryTeamResolver} 가 이미 푼 문제라 같은 순서를 쓴다.
	 */
	@Test
	void 쏜_사람을_되찾는_두_단계가_그대로_있다() {
		assertDoesNotThrow(() -> DamageSource.class.getDeclaredMethod("getEntity"),
				"DamageSource.getEntity 가 사라졌다");
		assertDoesNotThrow(() -> DamageSource.class.getDeclaredMethod("getDirectEntity"),
				"DamageSource.getDirectEntity 가 사라졌다");

		Method indirect = assertDoesNotThrow(
				() -> Explosion.class.getDeclaredMethod("getIndirectSourceEntity", Entity.class),
				"Explosion.getIndirectSourceEntity 가 사라졌다. 주인 없는 폭발을 한 단계 풀 길이 없다");
		assertTrue(Modifier.isStatic(indirect.getModifiers()));
	}

	// -------------------------------------------------- 도우미

	private static TrialCatalog.Risk.CrystalGuard guardOf(String id) {
		TrialCatalog.Trial trial = TrialCatalog.byId(id);
		assertNotNull(trial, id + " 카드가 목록에서 사라졌다");
		assertEquals(1, trial.risks().size(), id + " 가 위험을 하나만 걸어야 이 시험이 뜻을 갖는다");
		return assertInstance(trial.risks().getFirst());
	}

	private static TrialCatalog.Risk.CrystalGuard assertInstance(TrialCatalog.Risk risk) {
		assertTrue(risk instanceof TrialCatalog.Risk.CrystalGuard,
				"CrystalGuard 가 아니면 이 실행기가 돌지 않는다: " + risk);
		return (TrialCatalog.Risk.CrystalGuard) risk;
	}

	/**
	 * 클래스 파일의 바이트를 그대로 문자열로 읽는다.
	 *
	 * <p>상수 풀에 이름과 서술자가 아스키로 들어가므로 {@code ISO_8859_1} 로 훑으면 찾을 수 있다.
	 */
	private static String classFileOf(Class<?> nearby, String binary) throws IOException {
		return resourceOf(nearby, "/" + binary.replace('.', '/') + ".class");
	}

	/** 클래스패스에 놓인 자원을 바이트 그대로 읽는다. 태그 JSON 도 같은 길로 온다. */
	private static String resourceOf(Class<?> nearby, String path) throws IOException {
		try (InputStream in = nearby.getResourceAsStream(path)) {
			if (in == null) {
				throw new IOException("자원을 찾지 못했습니다: " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
}
