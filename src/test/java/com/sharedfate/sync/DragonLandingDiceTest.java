package com.sharedfate.sync;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonHoldingPatternPhase;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 착지 주사위의 <b>천장</b>. 사람이 고른 값 하나와, 그 값이 걸리는 자리를 못박는다.
 *
 * <p>사람 말: <b>「크리스탈 영향에 천장 — 최대 1/6 으로 못박아 평균 12초에 한 번 내려앉게.
 * 『크리스탈이 살아 있으면 덜 앉는다』는 방향은 남기고, 『부활 = 착지 봉인』도 약하게만 남긴다」</b>
 *
 * <h2>⚠⚠ 이 파일에서 가장 중요한 시험</h2>
 *
 * <p>{@link #시련이_꺼진_판에서는_바닐라_값이_그대로다} 다. 인계 문서가 <b>「끄면 … 곧 완전한
 * 바닐라 엔더드래곤전입니다」</b> 를 못박고 있는데, 착지 확률은 <b>눈으로 재기 어려운 값</b>이다 —
 * 틀려도 「드래곤이 좀 자주 앉네」로만 보이고 누구도 버그라고 적지 않는다. 그래서 여기서 잡는다.
 *
 * <p>둘째는 {@link #착지_주사위가_findNewTarget_의_첫_nextInt_다} 다. 그 메서드에는 같은 서술자의
 * {@code nextInt} 가 <b>넷</b> 있어 ordinal 하나만 밀려도 <b>엉뚱한 것이 바뀌고 로그에는 아무
 * 흔적이 없다.</b>
 */
class DragonLandingDiceTest {

	// ------------------------------------------------------------------ ① 천장이 6 이다

	/**
	 * <b>크리스탈 0~3 개는 바닐라와 한 글자도 다르지 않다.</b>
	 *
	 * <p>바닐라가 {@code nextInt(alive + 3)} 을 굴리므로 천장 6 은 그대로
	 * {@code min(alive, 3) + 3} 이다. 그 셋째 칸까지가 바닐라와 같은 값이라
	 * 「크리스탈이 살아 있으면 덜 앉는다」는 기울기가 <b>한 칸도 안 깎인다</b> — 사람이 남기라고
	 * 한 것이 그것이다.
	 */
	@Test
	void 크리스탈_셋까지는_바닐라와_같은_값이다() {
		for (int alive = 0; alive <= 3; alive++) {
			int vanilla = alive + 3;
			assertEquals(vanilla, DragonLandingDice.ceiling(vanilla),
					"크리스탈 " + alive + "개에서 바닐라와 달라졌다 — 천장이 기울기를 먹었다. "
							+ "천장을 3 으로 내리면 alive 가 몇이든 1/3 이 되어 "
							+ "「크리스탈이 살아 있으면 덜 앉는다」가 통째로 사라진다");
		}
	}

	/** 넷 이상에서만 천장이 일한다. 열 개(=「부활」 직후)에서도 1/6 이다. */
	@Test
	void 크리스탈_넷부터_천장_6에_걸린다() {
		assertEquals(6, DragonLandingDice.CEILING, "사람이 고른 값은 1/6 이다");
		assertEquals(6, DragonLandingDice.ceiling(4 + 3), "크리스탈 4개에서 천장이 안 걸렸다");
		assertEquals(6, DragonLandingDice.ceiling(10 + 3),
				"크리스탈 10개에서 천장이 안 걸렸다 — 「부활」이 열 개를 세우면 바닐라는 1/13 "
						+ "(평균 26초)이고 그것이 사람이 지적한 「착지를 너무 안 함」이다");
		for (int alive = 4; alive <= 20; alive++) {
			assertEquals(6, DragonLandingDice.ceiling(alive + 3),
					"크리스탈 " + alive + "개에서 1/6 이 아니다");
		}
	}

	/**
	 * <b>0 이나 음수를 돌려주지 않는다.</b>
	 *
	 * <p>돌려준 값이 그대로 바닐라 {@code nextInt} 의 상한이 되므로, 0 이하를 만들어 보내면
	 * 바닐라가 {@code IllegalArgumentException} 으로 터진다 — 전투 한가운데서 드래곤 틱이 죽는다.
	 */
	@Test
	void 주사위_상한을_0_아래로_만들지_않는다() {
		// 바닐라가 실제로 넘기는 값은 언제나 3 이상이다(alive 는 음수가 될 수 없다). 그래도 1 부터
		// 돌려 보는 것은 「천장이 값을 낮추기만 한다」가 어디서도 깨지지 않는 것을 보려는 것이다.
		for (int vanilla = 1; vanilla <= 20; vanilla++) {
			int capped = DragonLandingDice.ceiling(vanilla);
			assertEquals(Math.min(vanilla, DragonLandingDice.CEILING), capped,
					"천장이 Math.min 이 아니다: " + vanilla + " → " + capped);
			assertTrue(capped > 0, "상한이 0 이하가 됐다: " + vanilla + " → " + capped);
			assertTrue(capped <= vanilla, "천장이 값을 올렸다: " + vanilla + " → " + capped);
		}
		assertEquals(3, DragonLandingDice.ceiling(3), "바닐라가 넘기는 가장 작은 값이 3 이다");
	}

	// ------------------------------------------------------------------ ②⚠⚠ 시련이 꺼진 판

	/**
	 * <b>시련을 끈 판에서는 바닐라 값이 그대로 나온다.</b>
	 *
	 * <p>{@link DragonLandingDice#cap} 이 「시련이 도는가」를 증명하지 못하면 받은 값을 그대로
	 * 돌려준다. 판을 넘기지 못하는 자리(여기)는 증명할 길이 없으므로 <b>언제나 바닐라</b>여야
	 * 한다 — 모를 때는 기본값(끔)과 같은 쪽으로 가는 것이
	 * {@code DragonTrialManager.trialsEnabled} 의 규칙 그대로다.
	 */
	@Test
	void 시련이_꺼진_판에서는_바닐라_값이_그대로다() {
		for (int alive = 0; alive <= 10; alive++) {
			int vanilla = alive + 3;
			assertEquals(vanilla, DragonLandingDice.cap(null, vanilla),
					"시련을 켠 전투를 증명하지 못했는데 값이 바뀌었다 — 인계 문서의 「끄면 완전한 "
							+ "바닐라 엔더드래곤전」이 거짓이 된다. 크리스탈 " + alive + "개");
		}
	}

	/**
	 * 「시련이 도는가」를 <b>이미 있는 것으로</b> 묻는다.
	 *
	 * <p>깃발을 새로 세우지 않는다 — {@code DragonTrialSession.trialsEnabled()} 가 전투가 열린
	 * 틱에 {@code TeamState.dragonTrialsEnabled} 를 복사해 둔 값이고,
	 * {@code DragonTrialManager} 의 세션 루프도 그 한 줄로 끈 팀을 통째로 지나간다. 같은 뜻의
	 * 깃발이 둘이 되면 내리는 자리를 빠뜨린 날 「시련을 껐는데 드래곤만 시련처럼 구는」 판이 생긴다.
	 */
	@Test
	void 시련_판별을_세션에서_가져온다() {
		UUID team = UUID.randomUUID();
		assertFalse(DragonLandingDice.trialLive(null), "세션이 없으면 전투가 안 열린 것이다");
		assertFalse(DragonLandingDice.trialLive(new DragonTrialSession(team, 0L, false)),
				"시련을 끈 세션이 참으로 읽힌다 — 바닐라 드래곤전의 착지가 달라진다");
		assertTrue(DragonLandingDice.trialLive(new DragonTrialSession(team, 0L, true)),
				"시련을 켠 세션이 거짓으로 읽힌다 — 천장이 아무 일도 하지 않는다");

		String bytes = classBytes();
		assertTrue(bytes.contains("sessionOf"),
				"DragonTrialManager 의 세션을 안 본다 — 「시련이 도는가」를 여기서 새로 짠 것이다");
		assertTrue(bytes.contains("trialsEnabled"), "세션의 판별 이름이 바뀌었다");
		assertFalse(bytes.contains("getGameTime"),
				"시각을 묻고 있다 — 이 파일은 시각을 쓸 일이 없고, 룰렛이 판을 얼리면 그 값만 안 멈춘다");
	}

	/**
	 * <b>천장 아래면 팀 목록을 열지도 않는다.</b>
	 *
	 * <p>{@link DragonLandingDice#cap} 의 첫 줄이 {@code vanilla <= CEILING} 이라, 크리스탈이 셋
	 * 이하인 모든 굴림은 거기서 바로 빠져나간다. 그 순서가 뒤집히면 바닐라와 <b>답이 같은</b>
	 * 구간에서도 매번 팀을 뒤진다.
	 */
	@Test
	void 천장_아래_값은_판을_보기_전에_빠져나간다() {
		// 판이 null 이면 「증명 못 함」이라 천장 아래 값도 그대로 돌아온다 — 두 갈래가 같은 답을
		// 주므로 부르는 것만으로는 순서를 못 본다. 순서는 바이트코드에 그대로 적혀 있다.
		List<String> events = eventsIn(DragonLandingDice.class, "cap");
		int firstCall = -1;
		for (int at = 0; at < events.size(); at++) {
			if (events.get(at).startsWith("call:")) {
				firstCall = at;
				break;
			}
		}
		assertTrue(firstCall >= 0, "cap 이 아무것도 묻지 않는다 — 시련 판별이 사라졌다");
		assertTrue(events.subList(0, firstCall).contains("return"),
				"천장을 보기 전에 빠져나가는 길이 없다 — 크리스탈이 셋 이하인 모든 굴림에서도 팀 "
						+ "목록을 뒤진다. 읽은 차례: " + events);
		// 판별은 남에게 맡긴다. 여기에 팀 목록이 직접 나오면 같은 질문이 두 곳에 있게 된다.
		assertTrue(events.contains("call:DragonLandingDice.anyTrialLive"),
				"시련 판별을 부르지 않는다 — 시련을 끈 판에서도 천장이 걸린다");
	}

	// ------------------------------------------------------------------ ④ 체력 80% 이후 ×1.3

	/**
	 * <b>체력 80% 이후 한 굴림의 착지 확률이 지금의 1.3 배다.</b>
	 *
	 * <p>사람 말(2026-10-04): 「80프로 터지고 착지 확률을 좀 더 올렸으면 좋겠어. 지금보다
	 * 30프로는 더」. 크리스탈 셋 이상 1/6 → 0.2167, 0 개 1/3 → 0.4333.
	 */
	@Test
	void 체력_80_이후_착지_확률이_한_점_삼_배다() {
		assertEquals(1.3F, DragonLandingDice.HEALTH_80_BOOST, 1.0E-6F, "사람이 정한 것은 「30프로 더」다");
		for (int alive = 0; alive <= 10; alive++) {
			int bound = DragonLandingDice.ceiling(alive + 3);
			float base = 1.0F / bound;
			float extra = DragonLandingDice.extraChance(bound);
			assertTrue(extra > 0.0F && extra < 1.0F, "두 번째 굴림 확률이 범위 밖: " + extra);
			float total = base + (1.0F - base) * extra;
			assertEquals(1.3F / bound, total, 1.0E-6F,
					"크리스탈 " + alive + "개에서 착지 확률이 " + total + " — 1.3/" + bound + " 이 아니다");
			assertEquals(0.3F / (bound - 1), extra, 1.0E-6F, "q = 0.3/(n-1) 로 정리되어야 한다");
		}
		assertEquals(0.21667F, DragonLandingDice.boostedChance(6), 1.0E-4F, "1/6 × 1.3");
		assertEquals(0.43333F, DragonLandingDice.boostedChance(3), 1.0E-4F, "1/3 × 1.3");
	}

	/**
	 * <b>실제로 굴려 봐도 1.3 배다.</b> 바닐라가 굴리는 그대로(첫 굴림 → 0 이 아니면 두 번째
	 * 굴림) 20만 번 흉내 낸다.
	 */
	@Test
	void 굴려_보면_착지_빈도가_한_점_삼_배다() {
		RandomSource random = RandomSource.create(20261004L);
		int rounds = 200_000;
		for (int bound = 3; bound <= DragonLandingDice.CEILING; bound++) {
			int landed = 0;
			for (int round = 0; round < rounds; round++) {
				int rolled = random.nextInt(bound);
				if (rolled != 0) {
					rolled = DragonLandingDice.reroll(random, bound, rolled);
				}
				if (rolled == 0) {
					landed++;
				}
			}
			double seen = (double) landed / rounds;
			// 20만 번이면 표준오차가 0.0011 이하다. 0.005 는 4.5 시그마 넘게 떨어진 자리다.
			assertEquals(1.3 / bound, seen, 0.005,
					"상한 " + bound + " 에서 착지 빈도 " + seen + " — 1.3/" + bound + " 이 아니다");
		}
	}

	/**
	 * <b>⚠⚠ 80% 이전과 시련이 꺼진 판에서는 두 번째 굴림이 아예 없다.</b>
	 *
	 * <p>확률이 같은 것으로 부족하다 — 난수를 한 번이라도 더 꺼내면 그 뒤 바닐라 굴림(돌진,
	 * 도는 방향)이 모두 다른 값을 받는다. 판을 증명하지 못하는 자리(여기, {@code null})는 언제나
	 * 바닐라여야 하므로, 같은 씨앗의 주사위 둘을 나란히 굴려 <b>흐름이 한 칸도 안 밀렸는지</b> 본다.
	 */
	@Test
	void 체력_80_전이나_시련이_꺼진_판에서는_난수를_더_꺼내지_않는다() {
		RandomSource touched = RandomSource.create(7L);
		RandomSource fresh = RandomSource.create(7L);
		for (int round = 0; round < 1_000; round++) {
			int bound = 3 + round % 4;
			int rolled = touched.nextInt(bound);
			assertEquals(fresh.nextInt(bound), rolled);
			assertEquals(rolled, DragonLandingDice.boost(null, touched, bound, rolled),
					"80% 를 증명하지 못했는데 값이 바뀌었다 — 시련을 끈 판의 착지가 달라진다");
		}
		assertEquals(fresh.nextLong(), touched.nextLong(),
				"boost 가 난수를 꺼냈다 — 시련을 끈 판에서 바닐라 난수 흐름이 밀린다");
	}

	/**
	 * 「80% 가 터졌는가」를 <b>세션에 이미 있는 것으로</b> 묻는다.
	 *
	 * <p>{@code DragonTrialManager} 가 체력 비율 ≤ 0.80 인 틱에 {@code fire(HEALTH_80)} 을 부르고
	 * 그것이 {@code fired()} 에 남는다. 시련을 끈 세션은 {@code fire} 가 아무것도 쌓지 않는다.
	 */
	@Test
	void 체력_80_판별을_세션에서_가져온다() {
		UUID team = UUID.randomUUID();
		assertFalse(DragonLandingDice.health80Passed(null), "세션이 없으면 전투가 안 열린 것이다");

		DragonTrialSession on = new DragonTrialSession(team, 0L, true);
		assertFalse(DragonLandingDice.health80Passed(on), "80% 가 안 터졌는데 착지가 늘었다");
		on.fire(TrialCatalog.Trigger.HEALTH_50);
		assertFalse(DragonLandingDice.health80Passed(on), "다른 자리가 80% 로 읽힌다");
		assertTrue(on.fire(TrialCatalog.Trigger.HEALTH_80));
		assertTrue(DragonLandingDice.health80Passed(on), "80% 가 터졌는데 ×1.3 이 안 걸린다");

		DragonTrialSession off = new DragonTrialSession(team, 0L, false);
		off.fire(TrialCatalog.Trigger.HEALTH_80);
		assertFalse(DragonLandingDice.health80Passed(off),
				"시련을 끈 세션이 80% 로 읽힌다 — 「끄면 완전한 바닐라」가 거짓이 된다");

		String bytes = classBytes();
		assertTrue(bytes.contains("HEALTH_80"), "80% 자리를 안 본다 — 판별을 새로 짠 것이다");
		assertTrue(bytes.contains("fired"), "세션이 들고 있는 「터진 자리」를 안 본다");
	}

	/**
	 * <b>바닐라가 이미 0(착지)을 냈으면 팀 목록을 열지도 않는다.</b>
	 *
	 * <p>순서가 비용이다 — 착지로 이미 정해진 굴림까지 팀을 뒤질 까닭이 없다.
	 */
	@Test
	void 이미_착지로_나온_굴림은_판을_보기_전에_빠져나간다() {
		List<String> events = eventsIn(DragonLandingDice.class, "boost");
		int firstCall = -1;
		for (int at = 0; at < events.size(); at++) {
			if (events.get(at).startsWith("call:")) {
				firstCall = at;
				break;
			}
		}
		assertTrue(firstCall >= 0, "boost 가 아무것도 묻지 않는다 — 80% 판별이 사라졌다");
		assertTrue(events.subList(0, firstCall).contains("return"),
				"착지로 이미 나온 굴림에서도 팀 목록을 뒤진다. 읽은 차례: " + events);
		assertTrue(events.contains("call:DragonLandingDice.anyHealth80Passed"),
				"80% 판별을 부르지 않는다 — 시련을 끈 판에서도 ×1.3 이 걸린다");
		assertEquals(0, DragonLandingDice.boost(null, RandomSource.create(1L), 6, 0),
				"착지로 나온 굴림을 뒤집었다");
	}

	// ------------------------------------------------------------------ ③ 믹스인이 무는 자리

	/**
	 * 주사위가 사는 자리가 26.3 에 그대로 있다.
	 *
	 * <p>{@code findNewTarget} 은 <b>{@code private}</b> 이다 — 그래서 「재정의되는 메서드에
	 * 믹스인을 걸면 조용히 죽는다」는 이 저장소의 함정에 애초에 걸릴 수 없다. 그 사실이 바뀌면
	 * 여기가 먼저 터져야 한다.
	 */
	@Test
	void 주사위가_사는_자리가_그대로_있다() throws NoSuchMethodException, NoSuchFieldException {
		Method findNewTarget = DragonHoldingPatternPhase.class
				.getDeclaredMethod("findNewTarget", ServerLevel.class);
		assertEquals(void.class, findNewTarget.getReturnType());
		assertFalse(Modifier.isStatic(findNewTarget.getModifiers()));
		assertTrue(Modifier.isPrivate(findNewTarget.getModifiers()),
				"findNewTarget 이 더 이상 private 이 아니다 — 하위 클래스가 재정의할 수 있게 됐으니 "
						+ "「조용히 죽는 믹스인」을 다시 확인할 것");

		// 판을 알아내는 칸. ⚠ 선언 위치가 중요하다 — 아래 절을 볼 것.
		Field dragon = AbstractDragonPhaseInstance.class.getDeclaredField("dragon");
		assertEquals(EnderDragon.class, dragon.getType(),
				"접근자가 꺼내는 칸이 바뀌었다 — 믹스인이 판을 알 길이 없어진다");
		assertTrue(Modifier.isProtected(dragon.getModifiers()));
		assertTrue(Modifier.isFinal(dragon.getModifiers()),
				"final 이 풀렸다 — 쓰는 접근자를 만들 문이 열렸으니 그러지 말 것");
		assertTrue(AbstractDragonPhaseInstance.class
						.isAssignableFrom(DragonHoldingPatternPhase.class),
				"홀딩 패턴이 그 칸을 물려받지 않게 됐다");

		// ⚠⚠ 이 단정이 이 파일에서 가장 비싸게 배운 것이다. @Shadow 는 「대상 클래스 자신에
		// 선언된」 칸만 붙이므로, dragon 이 상위 클래스에만 있는 동안에는 접근자 믹스인이
		// 반드시 필요하다. 처음에 @Shadow 로 적었다가 믹스인 적용 단계에서
		// InvalidMixinException 으로 죽었고, 함께 넘어진 것은 이 파일이 아니라
		// EnderDragonPhase 를 쓰는 남의 시험 넷이었다.
		assertThrows(NoSuchFieldException.class,
				() -> DragonHoldingPatternPhase.class.getDeclaredField("dragon"),
				"홀딩 패턴이 dragon 을 스스로 선언하게 됐다 — 그러면 @Shadow 로도 붙으니 "
						+ "AbstractDragonPhaseInstanceAccessor 를 지울 수 있다. 지금은 상위 클래스 "
						+ "칸이라 @Shadow 가 안 붙는다");
	}

	/**
	 * <b>⚠⚠ ordinal 0 이 착지 주사위다.</b>
	 *
	 * <p>{@code findNewTarget} 안의 {@code RandomSource.nextInt(I)I} 가 <b>넷</b>이다. 서술자가
	 * 모두 같아 ordinal 말고는 가를 길이 없고, 하나라도 밀면 <b>돌진 빈도나 도는 방향이 바뀌고
	 * 로그에는 아무 흔적이 없다.</b>
	 *
	 * <ul>
	 *   <li>ordinal 0 — {@code nextInt(alive + 3)} → {@code LANDING_APPROACH}. <b>우리 것</b></li>
	 *   <li>ordinal 1 — {@code nextInt((int)(거리²/512 + 2))} → 돌진 문 ①</li>
	 *   <li>ordinal 2 — {@code nextInt(alive + 2)} → 돌진 문 ②</li>
	 *   <li>ordinal 3 — {@code nextInt(8)} → {@code clockwise} 뒤집기</li>
	 * </ul>
	 */
	@Test
	void 착지_주사위가_findNewTarget_의_첫_nextInt_다() {
		List<String> calls = callsIn(DragonHoldingPatternPhase.class, "findNewTarget");
		List<Integer> rolls = new ArrayList<>();
		for (int at = 0; at < calls.size(); at++) {
			if (calls.get(at).equals("RandomSource.nextInt")) {
				rolls.add(at);
			}
		}
		assertEquals(4, rolls.size(),
				"findNewTarget 의 주사위 수가 바뀌었다(" + rolls.size() + "개) — ordinal 을 다시 셀 것. "
						+ "읽은 차례: " + calls);

		int landing = rolls.get(0);
		// 첫 굴림 바로 뒤가 「착지로 넘긴다」 두 줄이다.
		assertEquals("EnderDragon.getPhaseManager", calls.get(landing + 1),
				"첫 굴림 뒤가 착지가 아니다 — ordinal 0 이 더 이상 착지 주사위가 아니다");
		assertEquals("EnderDragonPhaseManager.setPhase", calls.get(landing + 2),
				"첫 굴림이 페이즈를 바꾸지 않는다");
		// 그 굴림이 크리스탈 수에서 나온 값을 쓴다.
		assertTrue(calls.subList(0, landing).contains("EnderDragonFight.aliveCrystals"),
				"첫 굴림 앞에 크리스탈 수를 세는 줄이 없다 — 「크리스탈이 착지를 늦춘다」의 근거가 사라졌다");
		// 나머지 셋은 페이즈를 바꾸지 않는다. 그쪽을 물면 돌진과 도는 방향이 바뀐다.
		for (int other = 1; other < rolls.size(); other++) {
			int at = rolls.get(other);
			assertFalse(at + 1 < calls.size()
							&& calls.get(at + 1).equals("EnderDragonPhaseManager.setPhase"),
					"ordinal " + other + " 도 페이즈를 바꾼다 — 어느 것이 착지인지 가를 수 없게 됐다");
		}
	}

	/**
	 * 믹스인이 <b>그 자리를 정확히, 그 자리 하나만</b> 문다. 서술자와 ordinal 을 못박는다.
	 *
	 * <p>refmap 이 없어 서술자가 틀려도 빌드는 통과한다. {@code injectors.defaultRequire} 가 1 이라
	 * <b>못 찾았을 때는</b> 붙이는 순간 터지지만, <b>엉뚱한 것을 찾았을 때는 조용하다</b>.
	 *
	 * <h2>⚠⚠ 등록된 믹스인은 <b>반사로 못 읽는다</b> — 이 저장소가 처음 겪은 함정이다</h2>
	 *
	 * <p>처음에는 {@code DragonHoldingPatternLandingMixin.class} 를 그대로 써서
	 * {@code getAnnotation(ModifyArg.class)} 로 읽었다. 그런데 이 저장소의 시험 환경은
	 * {@code fabric-loader-junit} 이라 <b>Knot 클래스로더가 돌고 믹스인이 진짜로 적용된다</b>.
	 * 그 로더는 등록된 믹스인 클래스를 <b>직접 불러오는 것을 금지</b>한다.
	 *
	 * <pre>{@code
	 * IllegalClassLoadError: Illegal classload request for
	 *     com.sharedfate.mixin.DragonHoldingPatternLandingMixin.
	 *     Mixin is defined in sharedfate.mixins.json and cannot be referenced directly
	 * }</pre>
	 *
	 * <p>⚠ <b>그래서 「등록 전에는 통과하고 등록되는 순간 깨지는」 시험이 된다.</b> 가장 나쁜 모양의
	 * 시험이다 — 믹스인을 만든 사람에게는 초록으로 보이고, {@code mixins.json} 에 줄을 넣은 사람이
	 * 영문 모를 빨강을 받는다. <b>믹스인 클래스를 시험에서 타입으로 쓰지 말 것.</b>
	 *
	 * <p>길은 <b>클래스 파일을 자원으로 읽어 ASM 으로 뜯는 것</b>이다. 자원 읽기는 클래스 로딩이
	 * 아니라서 금지에 걸리지 않는다(같은 파일의 {@link #믹스인은_값을_들고_있지_않다} 가 등록 뒤에도
	 * 멀쩡했던 것이 그 증거다).
	 *
	 * <h2>⚠ {@code method} 와 {@code at} 이 <b>둘 다 배열</b>이다</h2>
	 *
	 * <p>{@code javap -p} 로 확인한 서술자다(sponge-mixin 0.17.4) — {@code String[] method()} ·
	 * <b>{@code At[] at()}</b> · {@code int index()}. ⚠ 0.17.3 에서는 {@code At at()} <b>단일</b>
	 * 이었다. 소스에 {@code at = @At(...)} 한 벌만 적어도 클래스 파일에는 <b>길이 1 의 배열</b>로
	 * 적힌다.
	 *
	 * <p>2026-10-04 에 {@code @ModifyArg} 가 {@code @WrapOperation} 으로 바뀌었다(체력 80% 이후
	 * ×1.3 은 상한 하나로 적을 수 없다 — {@code DragonLandingDice} 설명). MixinExtras 0.5.5 의
	 * {@code WrapOperation} 도 {@code String[] method()} · <b>{@code At[] at()}</b> 이라
	 * ({@code javap -p} 로 확인했다) 아래 단정이 그대로 선다. {@code index} 는 없다.
	 *
	 * <p>그래서 길이를 함께 못박는다. 배열이라는 것은 <b>{@code @At} 을 둘 이상 달 수 있다</b>는
	 * 뜻이고, 하나만 더 붙으면 「ordinal 0 하나만 문다」가 <b>그 자리에서 거짓</b>이 된다 —
	 * 둘째 {@code @At} 이 ordinal 2 를 물면 <b>돌진 빈도까지 바뀌는데</b>, 이 시험이 첫 번째만
	 * 보고 있으면 그것을 한 번도 보지 못한다.
	 */
	@Test
	void 믹스인이_첫_주사위만_문다() {
		Map<String, Object> modify = wrapOperationOf("sharedfate$rollLandingDice");

		assertEquals(Boolean.TRUE, modify.get("present"),
				"@WrapOperation 이 사라졌다 — 아무 일도 하지 않는 메서드가 됐다");
		assertEquals("(Lnet/minecraft/util/RandomSource;I"
						+ "Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)I",
				modify.get("descriptor"),
				"@WrapOperation 은 감싸는 호출의 받는 쪽·인자·Operation 을 받고 같은 것을 돌려줘야 한다");
		assertEquals(Integer.valueOf(1), modify.get("method.count"),
				"무는 메서드가 하나가 아니다 — 서술자가 둘이면 어느 쪽에 천장이 걸리는지 알 수 없다");
		assertEquals("findNewTarget(Lnet/minecraft/server/level/ServerLevel;)V",
				modify.get("method[0]"),
				"서술자가 바뀌었다 — refmap 이 없어 이 시험 말고는 아무도 못 잡는다");

		// ⚠ at 은 At 하나가 아니라 At[] 다. 길이를 먼저 못박는다 — 둘째 @At 이 붙으면 「첫 주사위만
		// 문다」가 거짓이 되고, 첫 번째만 보는 시험은 그것을 못 본다.
		assertEquals(Integer.valueOf(1), modify.get("at.count"),
				"@At 이 하나가 아니다(" + modify.get("at.count") + "개) — 「ordinal 0 하나만 문다」가 "
						+ "거짓이 됐다. findNewTarget 의 nextInt 는 넷이고 나머지 셋은 돌진과 도는 방향이다");

		assertEquals("Lorg/spongepowered/asm/mixin/injection/At;", modify.get("at[0].type"),
				"@At 이 아닌 것이 들어왔다");
		assertEquals("INVOKE", modify.get("at[0].value"));
		assertEquals("Lnet/minecraft/util/RandomSource;nextInt(I)I", modify.get("at[0].target"),
				"무는 호출이 바뀌었다");
		assertEquals(Integer.valueOf(0), modify.get("at[0].ordinal"),
				"⚠⚠ ordinal 이 0 이 아니다 — 착지가 아니라 돌진이나 도는 방향이 바뀐다. "
						+ "그 사고는 로그에 한 줄도 남지 않는다. 읽은 것: " + modify);
	}

	/** 믹스인이 값을 들고 있지 않다. 천장은 {@code DragonLandingDice} 한 곳에만 적혀 있다. */
	@Test
	void 믹스인은_값을_들고_있지_않다() {
		String bytes = mixinBytes();
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLandingDice"),
				"믹스인이 우리 판별을 안 본다 — 무엇을 보고 천장을 씌우는지 알 수 없다");
		assertTrue(bytes.contains("cap"), "부르는 이름이 바뀌었다");
		assertTrue(bytes.contains("boost"),
				"체력 80% 이후 ×1.3 을 부르지 않는다 — 사람이 2026-10-04 에 정한 값이 안 걸린다");
		// 바닐라 주사위는 언제나 그대로 한 번 굴러야 한다. 안 부르면 착지 판정 자체가 사라진다.
		assertTrue(bytes.contains("call"), "Operation.call 이 없다 — 바닐라 주사위를 굴리지 않는다");
		// ⚠ 판은 접근자로 꺼낸다. @Shadow 로 돌아가면 상위 클래스 칸이라 적용 단계에서 죽는다.
		assertTrue(bytes.contains("AbstractDragonPhaseInstanceAccessor"),
				"접근자를 안 쓴다 — dragon 은 상위 클래스 칸이라 @Shadow 로는 안 붙고, 그때 "
						+ "InvalidMixinException 이 나며 EnderDragonPhase 를 쓰는 남의 시험까지 "
						+ "NoClassDefFoundError 로 넘어진다");
		// 드래곤에게 쓰지 않는다. 「표적」 카드가 돌진 페이즈로 밀었다가 착지를 아예 없앤 사고가
		// 쓰는 쪽에서 났고, 하필 우리가 고치려는 것이 그 착지다.
		for (String writes : new String[] {"setPhase", "setTarget", "setDeltaMovement", "setPos",
				"setHealth", "setNoAi", "setInvulnerable"}) {
			assertFalse(bytes.contains(writes),
					writes + " — 믹스인이 드래곤을 쓰고 있다. setPhase 가 부르는 begin() 이 "
							+ "currentPath 를 지우고, 바닐라는 그 경로가 끝난 틱에만 「착지할까」를 "
							+ "굴린다. 끼어들면 그 틱이 영영 안 온다");
		}
	}

	/**
	 * 믹스인 <b>둘이</b> 등록되어 있다.
	 *
	 * <p>{@code sharedfate.mixins.json} 에 이름을 안 넣으면 파일만 있고 아무 일도 하지 않는다 —
	 * 컴파일도 빌드도 조용하다. ⚠ 그 파일은 <b>메인이 독점</b>하므로 여기가 빨간 채로 넘어갈 수
	 * 있다. 빨간 것이 곧 「줄을 넣어 달라」는 말이다.
	 *
	 * <p>⚠ <b>접근자가 빠지면 증상이 더 나쁘다.</b> 천장 쪽은 안 붙으면 아무 일도 안 일어나는
	 * 것으로 끝나는데, 접근자가 없으면 {@code AbstractDragonPhaseInstance} 가 그 인터페이스를
	 * 갖지 않아 <b>주사위를 굴리는 그 틱에 {@code ClassCastException}</b> 이 난다 — 드래곤 틱
	 * 한가운데다.
	 */
	@Test
	void 믹스인_둘이_등록되어_있다() throws IOException {
		try (InputStream in = DragonLandingDice.class
				.getResourceAsStream("/sharedfate.mixins.json")) {
			assertNotNull(in, "sharedfate.mixins.json 이 클래스패스에 없다");
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(json.contains("\"DragonHoldingPatternLandingMixin\""),
					"DragonHoldingPatternLandingMixin 이 mixins.json 에 없다 — 착지 천장이 안 붙는다");
			assertTrue(json.contains("\"AbstractDragonPhaseInstanceAccessor\""),
					"AbstractDragonPhaseInstanceAccessor 가 mixins.json 에 없다 — 천장 믹스인이 "
							+ "드래곤을 꺼내려는 순간 ClassCastException 이 난다");
		}
	}

	// ------------------------------------------------------------------ 도우미

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLandingDice.class");
	}

	/**
	 * ⚠ 믹스인은 <b>이름(문자열)으로만</b> 가리킨다. 클래스 리터럴을 쓰면 Knot 로더가
	 * {@code IllegalClassLoadError} 로 거절한다(위 {@link #믹스인이_첫_주사위만_문다} 의 절).
	 */
	private static final String MIXIN = "/com/sharedfate/mixin/DragonHoldingPatternLandingMixin.class";
	private static final String WRAP_OPERATION =
			"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

	private static String mixinBytes() {
		return read(MIXIN);
	}

	/**
	 * 믹스인 메서드에 달린 {@code @WrapOperation} 을 <b>클래스 파일에서</b> 읽어 평평한 이름으로
	 * 돌려준다.
	 *
	 * <p>이름 꼴 — {@code descriptor} · {@code present} · {@code method.count} ·
	 * {@code method[0]} · {@code at.count} · {@code at[0].ordinal}. MixinExtras 0.5.5 의
	 * {@code at()} 도 {@code At[]} 이다({@code javap -p} 로 확인했다).
	 *
	 * <p>자원으로 읽으므로 믹스인 클래스를 <b>불러오지 않는다.</b> 등록된 믹스인은 반사로 못 읽는다.
	 */
	private static Map<String, Object> wrapOperationOf(String methodName) {
		Map<String, Object> found = new LinkedHashMap<>();
		new ClassReader(bytesOf(MIXIN)).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				if (!name.equals(methodName)) {
					return null;
				}
				found.put("descriptor", descriptor);
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
						if (!WRAP_OPERATION.equals(annotation)) {
							return null;
						}
						found.put("present", Boolean.TRUE);
						return collector(found, "");
					}
				};
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		assertTrue(found.containsKey("descriptor"),
				methodName + " 을 믹스인 클래스 파일에서 못 찾았다 — 이름이 바뀌었다");
		return found;
	}

	/** 애노테이션 값을 평평한 이름으로 모은다. 배열은 {@code at[0]}, 안쪽 애노테이션은 그 아래로. */
	private static AnnotationVisitor collector(Map<String, Object> into, String prefix) {
		return new AnnotationVisitor(Opcodes.ASM9) {
			@Override
			public void visit(String name, Object value) {
				into.put(prefix + (name == null ? "" : name), value);
			}

			@Override
			public AnnotationVisitor visitArray(String name) {
				String base = prefix + name;
				// 빈 배열이어도 개수를 적어 둔다. 안 적으면 「@At 이 없다」가 「못 읽었다」와
				// 구별되지 않는다.
				into.put(base + ".count", 0);
				int[] next = {0};
				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override
					public void visit(String ignored, Object value) {
						into.put(base + "[" + next[0]++ + "]", value);
						into.put(base + ".count", next[0]);
					}

					@Override
					public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
						String at = base + "[" + next[0]++ + "]";
						into.put(base + ".count", next[0]);
						into.put(at + ".type", descriptor);
						return collector(into, at + ".");
					}
				};
			}
		};
	}

	/** 컴파일된 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String read(String path) {
		return new String(bytesOf(path), StandardCharsets.ISO_8859_1);
	}

	/** 자원 한 개의 바이트. <b>클래스를 불러오지 않는다</b> — 믹스인을 읽는 유일한 길이다. */
	private static byte[] bytesOf(String path) {
		try (InputStream in = DragonLandingDiceTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return in.readAllBytes();
		} catch (IOException failed) {
			return fail(failed);
		}
	}

	/** 메서드 하나가 부르는 것들의 <b>차례</b>. 반환은 빼고 호출만 본다. */
	private static List<String> callsIn(Class<?> owner, String methodName) {
		List<String> calls = new ArrayList<>();
		for (String event : eventsIn(owner, methodName)) {
			if (event.startsWith("call:")) {
				calls.add(event.substring("call:".length()));
			}
		}
		assertFalse(calls.isEmpty(), owner.getSimpleName() + "." + methodName + " 이 아무것도 안 부른다");
		return calls;
	}

	/**
	 * 메서드 하나에서 <b>호출과 반환</b>이 나오는 차례. {@code RunResetOrderTest} 와 같은 도구다.
	 *
	 * <p>asm-tree 를 쓰지 않고 방문자만 쓴다 — 코어 ASM 은 믹스인이 이미 끌고 오므로 의존성이
	 * 늘지 않는다.
	 */
	private static List<String> eventsIn(Class<?> owner, String methodName) {
		List<String> events = new ArrayList<>();
		try (InputStream in = owner
				.getResourceAsStream("/" + owner.getName().replace('.', '/') + ".class")) {
			if (in == null) {
				return fail(owner.getName() + " 의 클래스 파일을 찾지 못했다");
			}
			new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
						String signature, String[] exceptions) {
					if (!name.equals(methodName)) {
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitMethodInsn(int opcode, String callOwner, String callName,
								String callDescriptor, boolean isInterface) {
							events.add("call:"
									+ callOwner.substring(callOwner.lastIndexOf('/') + 1)
									+ "." + callName);
						}

						@Override
						public void visitInsn(int opcode) {
							if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
								events.add("return");
							}
						}
					};
				}
			}, ClassReader.SKIP_FRAMES);
		} catch (IOException failed) {
			return fail(failed);
		}
		assertFalse(events.isEmpty(), owner.getSimpleName() + "." + methodName + " 을 읽지 못했다");
		return events;
	}
}
