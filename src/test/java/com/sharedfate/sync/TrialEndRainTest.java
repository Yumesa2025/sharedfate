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

	// ------------------------------------------------------------------ 패킷

	/**
	 * 한 틱에 바닥 표식으로 나가는 점 수.
	 *
	 * <p>점 하나가 패킷 한 장이고 고리는 예고 30틱 내내 매 틱 그려진다. 값에서 직접 뽑아 두면
	 * 지점 수나 반경을 고치는 사람이 패킷을 함께 보게 된다.
	 *
	 * <p>⚠ <b>지금 값은 이 저장소가 쓰던 예산보다 크다.</b> 「낙뢰」가 반경 3 짜리 열 곳으로 400,
	 * 「연쇄 포격」이 반경 3.5 짜리 열 곳으로 440 이고 그쪽은 상한을 500 으로 적어 두었다. 이
	 * 카드는 반경 2.5 지만 <b>{@code TrialWarning.ringPoints} 의 하한이 40</b> 이라 고리가 작아도
	 * 점이 줄지 않고, 15곳이면 600 이다. 줄이려면 지점 수나 하한을 손봐야 하는데 둘 다 이 파일의
	 * 것이 아니라 여기서는 <b>숫자를 드러내 놓기만</b> 한다.
	 */
	@Test
	void 한_틱_표식_점_수를_값에서_센다() {
		TrialCatalog.Risk.EndRain rain = card();
		int perRing = TrialWarning.ringPoints(rain.radius());
		assertEquals(rain.maxSpots() * perRing, TrialEndRain.markPoints(rain));
		assertEquals(40, perRing,
				"반경 2.5 의 둘레는 40점을 채우지 못한다 — ringPoints 의 하한이 그대로 나온다");
		assertEquals(600, TrialEndRain.markPoints(rain),
				"한 볼리가 15곳이면 600점이다. 낙뢰 400 · 연쇄 포격 440 보다 크다");
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
