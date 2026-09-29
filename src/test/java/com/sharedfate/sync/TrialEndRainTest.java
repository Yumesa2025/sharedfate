package com.sharedfate.sync;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「종말의 비」에서 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>파티클·소리·피해는 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다. 그런데 이 카드가
 * 망가지는 길은 거의 전부 그 바깥이다 — <b>한 번만 터져야 하는데 되풀이된다</b>, <b>겹침 금지
 * 목록에 우리 고리가 안 올라간다</b>, <b>예고가 착탄 없이 사라진다</b>. 전멸하면 월드가 지워지는
 * 게임이라 이것들은 돌려 보고 발견할 수 없다.
 */
class TrialEndRainTest {

	/** 이 카드의 열쇠. {@code TrialRisks} 가 {@code 카드 id + '#' + 위험 순번} 으로 만드는 것과 같다. */
	private static final String KEY = "sharedfate:end_rain#0";

	// ------------------------------------------------------------------ 한 번만 터진다

	/**
	 * 30초가 지나면 그 뒤로는 영영 아무 일도 없다.
	 *
	 * <p>이 카드의 성질 전부가 여기 있다. 되풀이되는 순간 체력 50% 자리의 난이도가 통째로
	 * 달라지고, 「낙뢰」와 늘 겹쳐 도는 카드가 된다.
	 */
	@Test
	void 지속_시간이_끝나면_다시_내리지_않는다() {
		TrialCatalog.Risk.EndRain rain = card();
		assertFalse(TrialEndRain.raining(rain.durationTicks() + 1, rain),
				"30초를 넘긴 뒤에도 내리면 한 번만 터지는 카드가 아니다");
		for (long elapsed = rain.durationTicks() + 1; elapsed < rain.durationTicks() * 100L;
				elapsed += 137L) {
			assertFalse(TrialEndRain.raining(elapsed, rain),
					"전투가 길어져도 되살아나면 안 된다: " + elapsed);
		}
	}

	/**
	 * 받은 바로 그 틱에는 아직 아무 볼리도 없다.
	 *
	 * <p>{@code TrialRisks.elapsedSinceGrant} 가 복원 직후의 음수 위상을 0 으로 깎으므로, 0 을
	 * 「내리는 중」으로 보면 되감긴 판에서 예고 없이 첫 볼리가 열린다.
	 */
	@Test
	void 카드를_받은_틱에는_아직_내리지_않는다() {
		TrialCatalog.Risk.EndRain rain = card();
		assertFalse(TrialEndRain.raining(0L, rain));
		assertTrue(TrialEndRain.raining(1L, rain));
		assertTrue(TrialEndRain.raining(rain.durationTicks(), rain),
				"마지막 틱도 지속 시간 안이다");
	}

	// ------------------------------------------------------------------ 예고는 착탄으로 끝난다

	/**
	 * 30초가 끝나기 전에 착탄하는 볼리만 연다.
	 *
	 * <p>표식만 뜨고 사라지는 볼리가 한 번이라도 생기면 사람은 <b>피할 필요가 없었던 자리에서
	 * 비킨</b> 것이 되고, 그다음부터 표식을 믿지 않는다. 이 전투는 이미 보여 준 표식을 무르지
	 * 않는다.
	 */
	@Test
	void 착탄이_지속_시간_안에_들어오는_볼리만_연다() {
		TrialCatalog.Risk.EndRain rain = card();
		long last = rain.durationTicks() - rain.warnTicks();
		assertTrue(TrialEndRain.landsInWindow(last, rain), "딱 맞게 들어오는 볼리는 열어야 한다");
		assertFalse(TrialEndRain.landsInWindow(last + 1, rain),
				"여기서 열면 착탄이 30초 밖이라 표식이 착탄 없이 사라진다");
		for (long elapsed = 1L; elapsed <= rain.durationTicks(); elapsed++) {
			if (TrialEndRain.landsInWindow(elapsed, rain)) {
				assertTrue(elapsed + rain.warnTicks() <= rain.durationTicks(),
						"연 볼리의 착탄이 30초를 넘는다: " + elapsed);
			}
		}
	}

	/**
	 * 예고 30틱은 「제자리에서 옆으로 비키기」의 하한과 같다.
	 *
	 * <p>이 카드가 요구하는 행동이 그것뿐이다 — 자리가 볼리를 여는 순간 얼어붙어 사람을 쫓아오지
	 * 않으므로 갈 곳을 고를 것도 넷이 합의할 것도 없다. 그래서 하한이
	 * {@link TrialWarning#TICKS_REPOSITION} 도 {@link TrialWarning#TICKS_SCATTER} 도 아니다.
	 * 여기서 더 줄이면 예고가 아니라 사후 통보다.
	 */
	@Test
	void 예고는_옆걸음_하한보다_짧지_않다() {
		TrialCatalog.Risk.EndRain rain = card();
		assertTrue(rain.warnTicks() >= TrialWarning.TICKS_SIDESTEP,
				"예고 " + rain.warnTicks() + "틱은 옆으로 비킬 시간(" + TrialWarning.TICKS_SIDESTEP
						+ "틱)보다 짧다");
	}

	/**
	 * 볼리 둘이 같은 틱에 바닥에 떠 있지 않는다.
	 *
	 * <p>가장 짧은 간격이 예고보다 길어야 앞 볼리가 터진 뒤에 다음 볼리가 열린다. 겹치면 고리가
	 * 서로를 덮어 어느 것이 언제 터지는지 읽히지 않고, 한 틱에 나가는 표식 점도 두 배가 된다.
	 */
	@Test
	void 볼리는_한_번에_하나뿐이다() {
		TrialCatalog.Risk.EndRain rain = card();
		assertTrue(rain.minInterval() > rain.warnTicks(),
				"간격 " + rain.minInterval() + "틱이 예고 " + rain.warnTicks()
						+ "틱보다 짧으면 볼리 둘이 겹쳐 뜬다");
	}

	// ------------------------------------------------------------------ 굴림

	/** 굴린 간격과 지점 수가 카드에 적힌 범위 밖으로 나가지 않는다. */
	@Test
	void 굴림이_카드_범위를_벗어나지_않는다() {
		TrialCatalog.Risk.EndRain rain = card();
		RandomSource random = RandomSource.create(20260930L);
		boolean sawShortest = false;
		boolean sawLongest = false;
		for (int round = 0; round < 20000; round++) {
			int interval = TrialEndRain.rolledInterval(random, rain);
			int spots = TrialEndRain.rolledSpots(random, rain);
			assertTrue(interval >= rain.minInterval() && interval <= rain.maxInterval(),
					"간격이 범위 밖이다: " + interval);
			assertTrue(spots >= rain.minSpots() && spots <= rain.maxSpots(),
					"지점 수가 범위 밖이다: " + spots);
			sawShortest |= interval == rain.minInterval();
			sawLongest |= interval == rain.maxInterval();
		}
		// 양 끝이 안 나오면 「매번 무작위」가 실은 좁은 구간을 도는 것이다.
		assertTrue(sawShortest && sawLongest, "굴림이 범위의 양 끝에 닿지 않는다");
	}

	/**
	 * 값이 뒤집혀 있으면 아무 일도 하지 않는다.
	 *
	 * <p>{@code nextIntBetweenInclusive} 는 아래위가 뒤집힌 범위에서 터진다. 그 자리는 전투
	 * 한가운데이고, 실행기 하나가 던지면 분배기의 그 틱이 통째로 죽는다 — 뒤 카드들이 그 틱에
	 * 돌지 않는다.
	 */
	@Test
	void 굴릴_수_없는_값은_아예_돌지_않는다() {
		assertTrue(TrialEndRain.usable(card()), "카드 목록의 값은 굴릴 수 있어야 한다");
		assertFalse(TrialEndRain.usable(new TrialCatalog.Risk.EndRain(
				600, 60, 40, 10, 15, 30, 10.0F, 2.5)), "간격의 아래위가 뒤집혔다");
		assertFalse(TrialEndRain.usable(new TrialCatalog.Risk.EndRain(
				600, 40, 60, 15, 10, 30, 10.0F, 2.5)), "지점 수의 아래위가 뒤집혔다");
		assertFalse(TrialEndRain.usable(new TrialCatalog.Risk.EndRain(
				0, 40, 60, 10, 15, 30, 10.0F, 2.5)), "지속 시간이 0 이면 내릴 시간이 없다");
		assertFalse(TrialEndRain.usable(new TrialCatalog.Risk.EndRain(
				600, 40, 60, 10, 15, 30, 10.0F, 0.0)), "반경이 0 이면 고리도 피해도 없다");
	}

	// ------------------------------------------------------------------ 겹침 금지 목록

	/**
	 * ⚠ <b>지점을 {@link TrialRisks#reserveSpots} 로 잡고 {@link TrialRisks#releaseSpots} 로
	 * 돌려준다.</b>
	 *
	 * <p>이 시험이 지키는 것이 「즉사 메커닉 0개」의 마지막 구멍이다. 좌표를 스스로 굴리도록
	 * 고치면 이 카드의 고리만 「낙뢰」 고리 위에 겹쳐 떨어지고, 겹친 자리는 한 틱에 28 이라 팀
	 * 공유 체력 20 을 넘긴다. 컴파일도 다른 시험도 조용하므로 <b>컴파일된 클래스가 그 이름을 실제로
	 * 들고 있는지</b>를 직접 본다.
	 *
	 * <p>돌려주는 쪽도 함께 본다. 안 돌려주면 아무것도 없는 자리를 다른 카드가 영영 못 쓴다 —
	 * 이 실행기가 {@code releaseSpots} 를 부르는 첫 번째 호출자다.
	 */
	@Test
	void 지점을_겹침_금지_목록으로만_잡고_돌려준다() {
		byte[] compiled = classBytes(TrialEndRain.class);
		assertTrue(references(compiled, "reserveSpots"),
				"지점을 스스로 굴리고 있다. 남의 고리 위에 겹쳐 떨어진다");
		assertTrue(references(compiled, "releaseSpots"),
				"잡아 둔 자리를 돌려주지 않는다. 다른 카드가 그 자리를 영영 못 쓴다");
	}

	/**
	 * 열쇠를 스스로 만들지 않는다 — 분배기에게 받는다.
	 *
	 * <p>{@code TrialRisks} 는 매 틱 <b>살아 있는 카드의 열쇠 집합으로 {@code LIVE_SPOTS} 를
	 * 걸러 낸다.</b> 다른 모양의 열쇠로 자리를 잡으면 그 자리가 다음 틱에 목록에서 지워지고,
	 * 그러면 <b>우리는 남을 피하는데 남은 우리를 못 보는</b> 한쪽만 새는 상태가 된다 —
	 * 겹쳐 떨어지는데 로그도 시험도 조용하다.
	 *
	 * <p>한때 이 파일이 {@code TrialCatalog.all()} 에서 값으로 열쇠를 되찾아 쓰는 우회로를
	 * 들고 있었다. 값이 완전히 같은 위험을 카드 둘에 걸면 앞 카드의 열쇠가 나와 뒤 카드가 겹침
	 * 검사에서 통째로 빠지는 길이라 걷어냈다. <b>되돌아가면 이 시험이 막는다</b> — 카드 목록을
	 * 뒤지는 것 자체가 열쇠를 두 번째로 만드는 일이다.
	 */
	@Test
	void 열쇠를_다시_만들지_않는다() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:end_rain");
		assertNotNull(trial, "「종말의 비」 카드가 없다");
		assertEquals(1, trial.risks().size(), "위험이 둘이 되면 순번이 0 이 아닐 수 있다");
		assertEquals(trial.id() + "#0", KEY, "열쇠 모양이 분배기의 것과 다르다");

		assertFalse(references(classBytes(TrialEndRain.class), "spotKey"),
				"열쇠를 스스로 만들고 있다. 두 곳에서 만든 열쇠는 언젠가 갈라진다");
	}

	// ------------------------------------------------------------------ 사람이 정한 값

	/**
	 * 지점 수가 <b>세 배</b>다.
	 *
	 * <p>사람이 플레이해 보고 「종말의 비를 생성이 지금의 3배여도 괜찮을 것 같다」고 해서 10~15
	 * 에서 30~45 가 됐다. 여기에 못 박아 두는 것은 <b>패킷이 무서워서 슬그머니 되돌리는 일</b>을
	 * 막기 위해서다 — 점 예산은 지점 수가 아니라 「한 틱에 얼마나 그리는가」로 푼다
	 * ({@link #한_틱_표식_점_수가_예산_안이다}).
	 */
	@Test
	void 지점_수가_사람이_정한_세_배다() {
		TrialCatalog.Risk.EndRain rain = card();
		assertEquals(30, rain.minSpots(), "10 의 세 배다");
		assertEquals(45, rain.maxSpots(), "15 의 세 배다");
	}

	// ------------------------------------------------------------------ 패킷

	/**
	 * 한 틱에 바닥 표식으로 나가는 점 수가 예산 안이다.
	 *
	 * <p>점 하나가 패킷 한 장이고 고리는 예고 30틱 내내 매 틱 나간다. 값에서 직접 뽑아 두면
	 * 지점 수나 반경을 고치는 사람이 패킷을 함께 보게 된다.
	 *
	 * <p>⚠ <b>전에는 이 시험이 「예산을 넘는다」를 적어 두기만 했다.</b> 반경 2.5 인데
	 * {@code TrialWarning.ringPoints} 의 하한이 40 이라 15곳에 <b>600점</b>이었고, 지점이 세
	 * 배가 되면서 <b>1800점</b>이 될 자리였다 — 「낙뢰」 400 · 「연쇄 포격」 440 과 견줄 수준이
	 * 아니다. 고리를 {@link TrialEndRain#markStride} 틱에 나눠 그려 예산 안으로 들어왔다.
	 */
	@Test
	void 한_틱_표식_점_수가_예산_안이다() {
		TrialCatalog.Risk.EndRain rain = card();
		int perRing = TrialWarning.ringPoints(rain.radius());
		assertEquals(40, perRing,
				"반경 2.5 의 둘레는 40점을 채우지 못한다 — ringPoints 의 하한이 그대로 나온다");
		assertEquals(rain.maxSpots() * TrialWarning.strokePoints(rain.radius(),
						TrialEndRain.markStride(rain)),
				TrialEndRain.markPoints(rain),
				"세는 법이 실제로 그리는 법과 갈라지면 이 시험이 아무것도 안 지킨다");
		assertTrue(TrialEndRain.markPoints(rain) <= TrialEndRain.MARK_BUDGET,
				"한 틱에 " + TrialEndRain.markPoints(rain) + "점이 나간다 — 예산 "
						+ TrialEndRain.MARK_BUDGET + " 을 넘는다");
		assertTrue(TrialEndRain.markPoints(rain) > 0, "한 점도 안 그리면 예고가 통째로 없다");
		assertEquals(1800, rain.maxSpots() * perRing,
				"나눠 그리지 않으면 한 틱에 이만큼이다 — 이 숫자가 고리를 나눈 까닭이다");
	}

	/**
	 * ⚠ <b>고리는 반드시 닫힌다.</b>
	 *
	 * <p>나눠 그리는 것이 성립하는 유일한 근거는 <b>먼저 찍은 점이 아직 살아 있다</b>는 것이다.
	 * 26.3 {@code DustParticleBase} 의 수명이
	 * {@code max(1, (int)(8.0 / (nextDouble() * 0.8 + 0.2)) * scale)} 이고 {@code scale} 이
	 * 1.0 이라 <b>최소 8틱</b>이다. 한 바퀴를 8틱 이상에 걸쳐 그리면 마지막 점을 찍기 전에 첫
	 * 점이 죽어 고리가 영영 안 닫힌다 — 그러면 이 카드의 유일한 대응 수단이 사라진다.
	 *
	 * <p>고리가 완성되는 데 걸리는 시간이 예고보다 짧아야 하는 것도 함께 본다. 완성 전에
	 * 착탄하면 사람이 본 것은 고리가 아니라 흩뿌려진 점이다.
	 */
	@Test
	void 고리가_파티클이_죽기_전에_닫힌다() {
		TrialCatalog.Risk.EndRain rain = card();
		int stride = TrialEndRain.markStride(rain);
		assertTrue(stride >= 1, "0 이하로 나누면 아무것도 안 그린다");
		assertEquals(6, TrialEndRain.MARK_MAX_STRIDE, "먼지 파티클의 최소 수명 8틱에서 둘을 뺀 값이다");
		assertTrue(stride <= TrialEndRain.MARK_MAX_STRIDE, "나눈 틱: " + stride);
		assertTrue(TrialEndRain.MARK_MAX_STRIDE < 8,
				"먼지 파티클은 8틱이면 죽는다. 8 이상으로 나누면 고리가 영영 안 닫힌다");
		assertTrue(stride < rain.warnTicks(),
				"고리가 완성되기 전에 착탄한다 — 예고 " + rain.warnTicks() + "틱, 완성 " + stride + "틱");
	}

	/**
	 * {@code stride} 틱이 지나면 <b>한 점도 빠짐없이</b> 찍혀 있다.
	 *
	 * <p>{@code markGround} 의 번호 고르기를 여기서 다시 적어 본다. 위상이 한 바퀴 도는 동안
	 * 어떤 번호가 한 번도 안 나오면 고리에 영영 구멍이 남고, 그 구멍이 하필 사람이 빠져나가려던
	 * 쪽일 수 있다.
	 */
	@Test
	void 위상이_한_바퀴_돌면_고리가_다_찍힌다() {
		TrialCatalog.Risk.EndRain rain = card();
		int stride = TrialEndRain.markStride(rain);
		int points = TrialWarning.ringPoints(rain.radius());
		// 되감긴 판의 음수 시각까지 포함해 여러 출발점에서 확인한다.
		for (long start : new long[] {0L, 1L, 12_345L, -7L}) {
			int[] drawn = new int[points];
			for (long now = start; now < start + stride; now++) {
				int phase = TrialEndRain.markPhase(now, stride);
				assertTrue(phase >= 0 && phase < stride, "위상이 범위 밖이다: " + phase);
				for (int index = phase; index < points; index += stride) {
					drawn[index]++;
				}
			}
			for (int index = 0; index < points; index++) {
				assertEquals(1, drawn[index],
						"출발 " + start + " 에서 " + index + "번 점이 " + drawn[index]
								+ "번 찍힌다 — 0 이면 고리에 구멍이고 2 면 예산을 두 번 쓴 것이다");
			}
		}
	}

	/**
	 * 고리를 나눠 그리는 것이 <b>{@code TrialWarning} 의 하한을 건드리지 않고</b> 된 일이다.
	 *
	 * <p>반경 2.5 에 40점은 점 간격 0.39칸으로 지나치게 촘촘하지만, 그 하한
	 * ({@code TrialWarning.ringPoints} 의 {@code BASE_POINTS})은 <b>바닥 고리를 쓰는 카드
	 * 전부</b>의 모습을 정한다 — 낙뢰(반경 3) · 연쇄 포격(3.5) · 기둥 화염구(4.35)가 전부 거기서
	 * 점 수를 받는다. 한 카드의 예산 때문에 남의 연출을 바꾸지 않았고, 되돌아가려는 사람이 이
	 * 시험에서 먼저 걸린다.
	 */
	@Test
	void 남의_카드_고리는_그대로다() {
		assertEquals(40, TrialWarning.ringPoints(3.0), "「낙뢰」의 고리가 달라졌다");
		assertEquals(44, TrialWarning.ringPoints(3.5), "「연쇄 포격」의 고리가 달라졌다");
		assertEquals(55, TrialWarning.ringPoints(4.35), "「기둥 화염구」의 고리가 달라졌다");
		assertEquals(TrialWarning.ringPoints(2.5), TrialWarning.strokePoints(2.5, 1),
				"stride 1 은 예전과 같아야 한다 — 인자를 안 넘긴 호출자가 그 길로 간다");
	}

	// ------------------------------------------------------------------ 도우미

	private static TrialCatalog.Risk.EndRain card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:end_rain");
		assertNotNull(trial, "「종말의 비」 카드가 없다");
		assertEquals(1, trial.risks().size(), "「종말의 비」의 위험이 하나가 아니다");
		return switch (trial.risks().getFirst()) {
			case TrialCatalog.Risk.EndRain rain -> rain;
			case TrialCatalog.Risk risk -> throw new AssertionError(
					"「종말의 비」의 위험이 종말의 비가 아니다: " + risk);
		};
	}

	/** 컴파일된 클래스 파일 그대로. 우리가 무엇을 부르는지는 소스가 아니라 여기에 남는다. */
	private static byte[] classBytes(Class<?> type) {
		String resource = type.getSimpleName() + ".class";
		try (InputStream stream = type.getResourceAsStream(resource)) {
			assertNotNull(stream, resource + " 를 클래스패스에서 찾지 못했다");
			return stream.readAllBytes();
		} catch (IOException broken) {
			throw new AssertionError(resource + " 를 읽지 못했다", broken);
		}
	}

	/**
	 * 클래스 파일이 이 이름을 상수 풀에 들고 있는가.
	 *
	 * <p>상수 풀의 이름은 ASCII 구간에서 그대로 바이트로 들어간다. 없다는 것은 이 클래스가 그것을
	 * 어디서도 부르지 않는다는 뜻이다.
	 */
	private static boolean references(byte[] compiled, String name) {
		byte[] needle = name.getBytes(StandardCharsets.US_ASCII);
		outer:
		for (int start = 0; start + needle.length <= compiled.length; start++) {
			for (int index = 0; index < needle.length; index++) {
				if (compiled[start + index] != needle[index]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}
}
