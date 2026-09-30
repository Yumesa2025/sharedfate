package com.sharedfate.sync;

import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「종말의 비」에서 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>파티클·소리·피해는 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다. 그런데 이 카드가
 * 망가지는 길은 거의 전부 그 바깥이다 — <b>한 번만 터져야 하는데 되풀이된다</b>, <b>겹침을
 * 허용한 것이 슬그머니 되돌아간다</b>, <b>예고가 착탄 없이 사라진다</b>, <b>구체가 예고보다 먼저
 * 닿는다</b>. 전멸하면 월드가 지워지는 게임이라 이것들은 돌려 보고 발견할 수 없다.
 *
 * <p>입자와 소리는 <b>컴파일된 클래스 파일의 상수 풀</b>에서 이름으로 본다. 「무엇을 쓰고
 * 있는가」는 주석이 아니라 거기에 남는다.
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
	 * 서로를 덮어 어느 것이 언제 터지는지 읽히지 않고, 한 틱에 나가는 점도 두 배가 된다 —
	 * {@link TrialEndRain#tickPoints} 가 「볼리는 하나뿐」을 전제로 세는 값이라
	 * 그 전제가 깨지면 예산 계산이 통째로 거짓이 된다.
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

	// ------------------------------------------------------------------ 겹침 금지 목록 밖이다

	/**
	 * ⚠⚠ <b>이 카드는 겹침 금지 목록을 쓰지 않는다 — 양쪽 다 끈 상태여야 한다.</b>
	 *
	 * <p>사람이 지점을 90곳으로 올리라고 했는데 {@link TrialRisks#reserveSpots} 를 지나면 84곳
	 * 밖에 안 서고 같이 걸린 「낙뢰」가 9.4 → 4.2 로 반토막 났다. 그것을 알린 뒤 사람이
	 * <b>「서로 겹쳐도 되니까 내가 말한 숫자로 해 줘」</b>라고 정했다. <b>전에는 이 자리에
	 * 「반드시 {@code reserveSpots} 를 지나라」는 시험이 있었고, 그 뜻을 뒤집어 그대로 남긴
	 * 것이 이 시험이다</b> — 지우면 어느 날 조용히 되돌아간다.
	 *
	 * <p><b>잡기와 놓기를 함께 본다.</b> 한쪽만 되살아나면 더 나쁘다 — 잡기만 되살리면
	 * 「낙뢰」가 다시 반토막 나고, 놓기만 남으면 남이 잡은 자리를 우리가 지운다.
	 *
	 * <p>대신 <b>지점을 굴리는 다른 규칙은 그대로 지나는지</b>도 본다. 겹침 검사만 뺀 것이지
	 * 허공 거르기와 지표 재기까지 뺀 것이 아니다.
	 */
	@Test
	void 겹침_금지_목록을_쓰지_않는다() {
		byte[] compiled = classBytes(TrialEndRain.class);
		assertFalse(references(compiled, "reserveSpots"),
				"겹침 금지 목록으로 되돌아갔다. 90곳이 84곳이 되고 「낙뢰」가 반토막 난다 —"
						+ " 사람이 「서로 겹쳐도 되니까 내가 말한 숫자로」라고 정했다");
		assertFalse(references(compiled, "releaseSpots"),
				"잡는 자리가 없는데 놓고 있다. 남이 잡아 둔 자리를 지운다");

		// 겹침 검사만 뺀 것이지 나머지까지 뺀 것이 아니다.
		assertTrue(references(compiled, "arenaOffset"),
				"아레나 안에서 고르게 뽑는 길을 제 손으로 다시 적었다 — 아레나가 바뀔 때"
						+ " 이 카드만 안 따라간다");
		assertTrue(references(compiled, "getHeightmapPos"),
				"지표를 안 재고 있다. 허공에서 터지고 구체가 땅에 안 닿는다");
	}

	/**
	 * ⚠⚠ <b>겹치면 죽는다는 사실이 값에 적혀 있다.</b>
	 *
	 * <p>사람이 대가를 알고 고른 값이라 <b>되돌리지 않는다.</b> 다만 이 카드가 이 저장소에서
	 * 「즉사 메커닉 0개」를 깬 첫 자리이므로, 그 사실을 시험이 숫자로 들고 있어야 다음에 값을
	 * 만지는 사람이 모르고 지나가지 않는다.
	 *
	 * <p>무장 기준(다이아 풀셋 + 보호 IV)으로 한 발 6.77 · 두 발 13.54 · <b>세 발 20.31</b> 이고
	 * 팀 공유 체력이 20 이다. 반경 40 아레나에 반경 2.5 짜리 90곳을 겹침 검사 없이 뿌리면
	 * <b>3겹 이상 구역이 생기는 볼리가 99.995%</b> 이고 그 넓이가 아레나의 <b>0.51%</b>(25.8칸²)
	 * 다 — 20만 판을 굴린 값이고 표는 {@link TrialEndRain} 클래스 설명에 있다.
	 */
	@Test
	void 고리_셋이_겹치면_무장하고도_전멸이다() {
		TrialCatalog.Risk.EndRain rain = card();
		float perHit = GearedDamage.afterGear(rain.damage(), GearedDamage.Source.EXPLOSION);
		assertEquals(6.77F, perHit, 0.01F, "한 발이 6.77 이 아니다 — 피해나 피해원이 바뀌었다");
		assertTrue(perHit * 2 < GearedDamage.TEAM_HEALTH, "두 발로는 산다: " + perHit * 2);
		assertTrue(perHit * 3 >= GearedDamage.TEAM_HEALTH,
				"세 발이 " + perHit * 3 + " 이라 살아남는다. 살아남으면 이 시험이 지킬 것이"
						+ " 없어진 것이니 겹침 허용의 근거부터 다시 볼 것");

		assertTrue(TrialEndRain.WORST_CASE_OVERLAP > 1,
				"겹칠 수 있는 개수가 1 로 돌아갔다. 1 은 겹침 금지 규칙이 있을 때만 참이다");
		assertTrue(TrialEndRain.WORST_CASE_OVERLAP >= 3,
				"실측 최악이 3겹 아래면 20만 판 시뮬레이션과 어긋난다 — 3겹 이상이 99.995% 의"
						+ " 볼리에 있다");
	}

	/**
	 * 열쇠를 스스로 만들지 않는다 — 분배기에게 받는다.
	 *
	 * <p>⚠ <b>열쇠가 하는 일이 줄었다.</b> 겹침 금지 목록에서 빠지면서 이제 이 열쇠는
	 * {@code RAINS} 의 칸을 가르는 데만 쓴다. 그래도 스스로 만들지 않는 이유는 그대로다 —
	 * 값이 완전히 같은 위험을 카드 둘에 걸면 <b>둘이 같은 칸을 써서</b> 한쪽의 볼리가 다른 쪽을
	 * 덮어쓴다.
	 *
	 * <p>한때 이 파일이 {@code TrialCatalog.all()} 에서 값으로 열쇠를 되찾아 쓰는 우회로를
	 * 들고 있었다. <b>되돌아가면 이 시험이 막는다</b> — 카드 목록을 뒤지는 것 자체가 열쇠를 두
	 * 번째로 만드는 일이다.
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
	 * 지점 수는 <b>카드가 정하고 실행기는 숫자를 박지 않는다.</b>
	 *
	 * <p>여기에 30·45 같은 숫자를 다시 적어 두면 사람이 값을 올릴 때마다 시험이 먼저 깨져,
	 * 값을 올리는 사람이 <b>시험을 고치는 김에 실행기도 고쳤는지</b>를 확인하지 않게 된다.
	 * 그래서 지키는 것을 둘로 좁혔다.
	 *
	 * <ul>
	 *   <li><b>아래로 되돌아가지 않는다.</b> 처음 10~15 에서 사람이 두 번 올렸다(3배 → 다시 2배).
	 *       패킷이 무서워 슬그머니 내리는 것을 막는 하한이 여기다</li>
	 *   <li><b>실행기가 카드 값을 읽는다.</b> 지점 수를 바꿨을 때 예산 계산이 따라오는지는
	 *       {@link #지점이_두_배가_되어도_한_틱_예산_안이다} 가 실제 값으로 본다</li>
	 * </ul>
	 *
	 * <p>피해 23 도 함께 못 박는다. 사람이 「데미지는 충분해」라고 했으므로 <b>연출을 바꾸면서
	 * 건드릴 값이 아니다.</b>
	 */
	@Test
	void 지점_수와_피해는_카드가_정한다() {
		TrialCatalog.Risk.EndRain rain = card();
		assertTrue(rain.minSpots() >= 30,
				"지점 수가 되돌아갔다: " + rain.minSpots() + " — 사람이 올린 값보다 작다");
		assertTrue(rain.maxSpots() > rain.minSpots(), "굴릴 폭이 없으면 박자를 외운다");
		assertEquals(23.0F, rain.damage(),
				"사람이 「데미지는 충분해」라고 했다 — 연출을 바꾸면서 피해를 건드리지 말 것");
	}

	// ------------------------------------------------------------------ 점 예산

	/**
	 * 예고 한 틱에 나가는 점 수가 예산 안이다. <b>고리와 구체를 합쳐서</b>다.
	 *
	 * <p>점 하나가 패킷 한 장이고 고리와 구체는 예고 30틱 내내 매 틱 나간다. 값에서 직접 뽑아
	 * 두면 지점 수나 반경을 고치는 사람이 패킷을 함께 보게 된다.
	 *
	 * <p>⚠ <b>구체가 생기면서 세는 법이 바뀌었다.</b> 구체는 매 틱 자리가 바뀌어 나눠 그릴 수
	 * 없으므로 <b>고정 비용</b>이고, 고리는 남은 예산으로 나눠 그린다. 둘을 따로 세면 합이
	 * 예산을 넘는 것을 아무도 못 본다.
	 */
	@Test
	void 예고_한_틱_점_수가_예산_안이다() {
		TrialCatalog.Risk.EndRain rain = card();
		assertEquals(TrialEndRain.markPoints(rain) + TrialEndRain.orbPoints(rain),
				TrialEndRain.tickPoints(rain),
				"세는 법이 실제로 그리는 법과 갈라지면 이 시험이 아무것도 안 지킨다");
		assertEquals(rain.maxSpots() * TrialEndRain.strokePoints(rain.radius(),
						TrialEndRain.markStride(rain)),
				TrialEndRain.markPoints(rain), "고리 몫을 세는 법이 실제와 다르다");
		assertEquals(rain.maxSpots() * TrialEndRain.ORB_POINTS_PER_SPOT,
				TrialEndRain.orbPoints(rain), "구체는 지점마다 매 틱 한 번씩이다");
		assertTrue(TrialEndRain.tickPoints(rain) <= TrialEndRain.MARK_BUDGET,
				"한 틱에 " + TrialEndRain.tickPoints(rain) + "점이 나간다 — 예산 "
						+ TrialEndRain.MARK_BUDGET + " 을 넘는다");
		assertTrue(TrialEndRain.markPoints(rain) > 0, "한 점도 안 그리면 고리가 통째로 없다");
		assertTrue(TrialEndRain.orbPoints(rain) > 0, "구체가 한 점도 안 나가면 하늘이 비어 있다");
	}

	/**
	 * ⚠ <b>지점이 두 배가 되어도 예산 안이다.</b>
	 *
	 * <p>사람이 「투사체 떨어지는거 지금의 2배로」라고 해서 30~45 가 60~90 으로 간다. 그
	 * 값으로 직접 세어 본다 — <b>카드 목록이 아직 안 바뀌었어도 실행기는 미리 견뎌야 한다.</b>
	 *
	 * <p>여기가 깨지면 고칠 곳은 {@link TrialEndRain#MARK_POINT_GAP} 이다. 고리를 나눌 수 있는
	 * 틱 수({@link TrialEndRain#MARK_MAX_STRIDE})는 파티클 수명이 정한 값이라 협상할 수 없고,
	 * 구체 몫은 지점당 한 점이라 더 줄일 수 없다.
	 */
	@Test
	void 지점이_두_배가_되어도_한_틱_예산_안이다() {
		TrialCatalog.Risk.EndRain rain = card();
		TrialCatalog.Risk.EndRain doubled = new TrialCatalog.Risk.EndRain(
				rain.durationTicks(), rain.minInterval(), rain.maxInterval(),
				60, 90, rain.warnTicks(), rain.damage(), rain.radius());
		assertTrue(TrialEndRain.usable(doubled), "두 배 값이 굴릴 수 없는 모양이면 안 된다");
		assertEquals(90, TrialEndRain.orbPoints(doubled), "구체가 지점마다 한 점이 아니다");
		assertTrue(TrialEndRain.markStride(doubled) <= TrialEndRain.MARK_MAX_STRIDE,
				"90곳에서 나눔이 상한을 넘는다 — 상한은 파티클 수명이 정한 값이라 못 올린다");
		assertTrue(TrialEndRain.tickPoints(doubled) <= TrialEndRain.MARK_BUDGET,
				"90곳이면 한 틱에 " + TrialEndRain.tickPoints(doubled) + "점이다 — 예산 "
						+ TrialEndRain.MARK_BUDGET + " 을 넘는다");
		assertTrue(TrialEndRain.impactTickPoints(doubled) <= TrialEndRain.IMPACT_BUDGET,
				"착탄 틱에 " + TrialEndRain.impactTickPoints(doubled) + "점이다 — 한 틱 예산 "
						+ TrialEndRain.IMPACT_BUDGET + " 을 넘는다");
	}

	/** 착탄하는 그 한 틱도 예산이 있다. 지점이 늘면 자리마다의 몫이 저절로 줄어야 한다. */
	@Test
	void 착탄_한_틱_점_수가_예산_안이다() {
		TrialCatalog.Risk.EndRain rain = card();
		assertTrue(TrialEndRain.impactPoints(rain) >= 1, "터지는데 아무것도 안 보이면 안 된다");
		assertTrue(TrialEndRain.impactTickPoints(rain) <= TrialEndRain.IMPACT_BUDGET,
				"착탄 틱에 " + TrialEndRain.impactTickPoints(rain) + "점이 나간다");
		// 지점이 늘수록 자리당 몫이 줄어야 합이 예산 안에 남는다.
		TrialCatalog.Risk.EndRain many = new TrialCatalog.Risk.EndRain(
				rain.durationTicks(), rain.minInterval(), rain.maxInterval(),
				200, 400, rain.warnTicks(), rain.damage(), rain.radius());
		assertTrue(TrialEndRain.impactPoints(many) < TrialEndRain.impactPoints(rain),
				"지점이 열 배가 됐는데 자리당 점 수가 그대로다");
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
	 * <p>{@code markRing} 의 번호 고르기를 여기서 다시 적어 본다. 위상이 한 바퀴 도는 동안
	 * 어떤 번호가 한 번도 안 나오면 고리에 영영 구멍이 남고, 그 구멍이 하필 사람이 빠져나가려던
	 * 쪽일 수 있다.
	 */
	@Test
	void 위상이_한_바퀴_돌면_고리가_다_찍힌다() {
		TrialCatalog.Risk.EndRain rain = card();
		int stride = TrialEndRain.markStride(rain);
		int points = TrialEndRain.ringPoints(rain.radius());
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
	 * 고리를 이 카드가 <b>직접</b> 그리게 되면서도 남의 고리는 하나도 안 바뀌었다.
	 *
	 * <p>{@code TrialWarning.ringPoints} 의 하한(40)은 반경 2.5 에서 간격 0.39칸이라 지나치게
	 * 촘촘하고, 지점이 수십 곳이면 그 하한만으로 예산이 무너진다. 그렇다고 하한을 내리면
	 * <b>바닥 고리를 쓰는 카드 전부</b>의 모습이 바뀐다 — 낙뢰(반경 3) · 연쇄 포격(3.5) · 기둥
	 * 화염구(4.35)가 전부 거기서 점 수를 받는다. 그래서 하한을 건드리는 대신 이 카드만
	 * {@link TrialEndRain#ringPoints} 로 제 밀도를 쓴다.
	 *
	 * <p>이 시험이 그 경계를 지킨다 — 남의 고리를 고치려는 사람이 여기서 먼저 걸린다.
	 */
	@Test
	void 남의_카드_고리는_그대로다() {
		assertEquals(40, TrialWarning.ringPoints(3.0), "「낙뢰」의 고리가 달라졌다");
		assertEquals(44, TrialWarning.ringPoints(3.5), "「연쇄 포격」의 고리가 달라졌다");
		assertEquals(55, TrialWarning.ringPoints(4.35), "「기둥 화염구」의 고리가 달라졌다");
		assertEquals(TrialWarning.ringPoints(2.5), TrialWarning.strokePoints(2.5, 1),
				"stride 1 은 예전과 같아야 한다 — 인자를 안 넘긴 호출자가 그 길로 간다");

		TrialCatalog.Risk.EndRain rain = card();
		assertTrue(TrialEndRain.ringPoints(rain.radius()) < TrialWarning.ringPoints(rain.radius()),
				"이 카드가 제 밀도를 안 쓰고 있다면 고리를 직접 그릴 이유가 없다");
		assertTrue(TrialEndRain.ringGap(rain.radius()) <= 1.0,
				"점 사이가 " + TrialEndRain.ringGap(rain.radius()) + "칸이면 고리가 아니라 점선이다");
	}

	// ------------------------------------------------------------------ 하늘에서 떨어지는 구체

	/**
	 * ⚠ <b>구체는 예고가 끝나는 그 틱에 땅에 닿는다.</b>
	 *
	 * <p>이 카드의 연출 전부가 여기 걸려 있다. 먼저 닿으면 사람은 「이미 터진 줄」 알고 들어가고,
	 * 늦게 닿으면 아직 공중에 있는데 발밑이 터진다 — 둘 다 <b>예고가 거짓말을 한 것</b>이고,
	 * 이 전투가 가장 하지 않기로 한 일이다.
	 */
	@Test
	void 구체는_착탄하는_그_틱에_땅에_닿는다() {
		TrialCatalog.Risk.EndRain rain = card();
		int warn = rain.warnTicks();
		assertEquals(0.0, TrialEndRain.orbHeight(0, warn), 1.0E-9,
				"착탄 틱에 구체가 공중에 있다");
		assertEquals(TrialEndRain.ORB_DROP_HEIGHT, TrialEndRain.orbHeight(warn, warn), 1.0E-9,
				"볼리가 열리는 틱에 구체가 시작 높이에 없다");
		// 한 틱도 거스르지 않고 내려온다. 중간에 되올라가면 「언제 닿는가」를 못 읽는다.
		double previous = TrialEndRain.orbHeight(warn, warn);
		for (int remaining = warn - 1; remaining >= 0; remaining--) {
			double height = TrialEndRain.orbHeight(remaining, warn);
			assertTrue(height < previous,
					"남은 " + remaining + "틱에 구체가 안 내려왔다: " + previous + " → " + height);
			previous = height;
		}
		// 범위 밖 값이 들어와도 시작 높이 위나 땅 밑으로 가지 않는다.
		assertEquals(TrialEndRain.ORB_DROP_HEIGHT, TrialEndRain.orbHeight(warn + 50, warn), 1.0E-9);
		assertEquals(0.0, TrialEndRain.orbHeight(-5, warn), 1.0E-9);
		assertEquals(0.0, TrialEndRain.orbHeight(10, 0), 1.0E-9, "예고가 없으면 떨어질 것도 없다");
	}

	/**
	 * 구체가 <b>보일 만큼 높고 기둥이 될 만큼 높지는 않다.</b>
	 *
	 * <p>높이는 곧 속도다 — 예고가 30틱으로 고정이므로 {@code 높이 / 30} 이 틱당 낙하 거리이고,
	 * 거기에 입자 수명 하한 8틱을 곱한 것이 <b>꼬리 길이</b>다. 꼬리가 구체 지름의 서너 배를
	 * 넘으면 사람이 보는 것은 구체가 아니라 하늘에서 내려온 기둥이다.
	 */
	@Test
	void 구체_높이가_꼬리를_기둥으로_만들지_않는다() {
		TrialCatalog.Risk.EndRain rain = card();
		double perTick = TrialEndRain.ORB_DROP_HEIGHT / rain.warnTicks();
		double tail = perTick * 8.0;
		assertTrue(TrialEndRain.ORB_DROP_HEIGHT >= 12.0,
				"이보다 낮으면 예고 내내 눈높이 띠 안이라 「하늘에서」가 안 읽힌다");
		assertTrue(tail <= rain.radius() * 2.5,
				"꼬리가 " + tail + "칸이다 — 반경 " + rain.radius() + " 짜리 구체가 기둥으로 보인다");
	}

	// ------------------------------------------------------------------ 입자와 소리

	/**
	 * ⚠ <b>수명이 긴 엔더 입자를 쓰지 않는다.</b>
	 *
	 * <p>26.3 클라이언트에서 잰 값이다 — {@code PORTAL} 40~49틱, {@code REVERSE_PORTAL} 60~61틱,
	 * {@code END_ROD} 60~71틱. 예고가 30틱이고 볼리 간격이 40~60틱이라, 이 중 무엇을 쓰든
	 * <b>터진 뒤에도 자국이 남고 그 자국이 다음 볼리의 고리와 겹친다.</b> 이미 안전한 자리가
	 * 위험해 보이는 것이 이 전투가 가장 피하는 거짓말이다.
	 *
	 * <p>{@code REVERSE_PORTAL} 은 실제로 착탄 연출에 들어 있던 것을 걷어낸 것이라, 되돌아가는
	 * 길을 여기서 막는다.
	 */
	@Test
	void 수명_긴_엔더_입자를_쓰지_않는다() {
		byte[] compiled = classBytes(TrialEndRain.class);
		assertFalse(references(compiled, "REVERSE_PORTAL"),
				"수명 60~61틱이라 터진 뒤 3초 동안 자국이 남는다");
		assertFalse(references(compiled, "END_ROD"), "수명 60~71틱이다");
		assertFalse(references(compiled, "PORTAL"),
				"PORTAL 은 40~49틱을 살고 나이에 따라 커진다 — 터진 뒤에 가장 크다");
		assertTrue(references(compiled, "ENTITY_EFFECT"),
				"구체와 착탄이 쓰는 입자가 바뀌었다. 빨강은 여기서 나온다");
		assertFalse(references(compiled, "WITCH"),
				"WITCH 는 26.3 의 WitchProvider 가 제 색을 자홍으로 직접 칠한다 —"
						+ " 서버가 무엇을 보내든 빨강이 안 된다");
	}

	/**
	 * ⚠ <b>구체가 고리와 같은 색이고, 알파가 살아 있다.</b>
	 *
	 * <p>사람이 말한 것은 「원」뿐이지만 구체가 다른 색이면 「저 구체가 이 고리로 온다」가 안
	 * 읽힌다. 그래서 색이 {@link TrialEndRain#MARK_COLOR} <b>하나</b>에서 나온다.
	 *
	 * <p>알파를 따로 보는 것은 {@code ColorParticleOption} 이 받는 것이 RGB 가 아니라
	 * <b>ARGB</b> 이기 때문이다. 최상위 바이트가 0 이면 구체가 통째로 안 보이는데, 파티클은
	 * 시험에서 그려 볼 수 없어 <b>여기가 그것을 잡는 유일한 자리</b>다.
	 */
	@Test
	void 구체가_고리와_같은_색이고_투명하지_않다() {
		assertEquals(TrialEndRain.MARK_COLOR, TrialEndRain.ORB_ARGB & 0x00FFFFFF,
				"구체 색이 고리 색과 다르다 — 예고 두 겹이 서로 다른 것을 가리킨다");
		assertEquals(0xFF, (TrialEndRain.ORB_ARGB >>> 24) & 0xFF,
				"알파가 " + ((TrialEndRain.ORB_ARGB >>> 24) & 0xFF)
						+ " 다. 0 이면 하늘에서 아무것도 안 내려온다");
	}

	/**
	 * ⚠ <b>고리가 규약의 빨강으로 되돌아왔다.</b>
	 *
	 * <p>사람이 「이펙트도 보라색으로」라고 해서 자홍({@code 0xC800C8})으로 갔다가, 플레이해 보고
	 * <b>「보라색이아닌 빨간색원으로다시 복귀하자 이번건 너무 가시성이안좋아」</b>라고 해서
	 * 되돌렸다. 되돌린 이유는 <b>가시성</b>이고, 돌아간 자리가 마침 규약의
	 * {@link TrialWarning.Colors#DEADLY}(「서 있으면 죽는다」)라 뜻도 맞는다.
	 *
	 * <p>「표적」의 보라와 같아지지 않는 것은 그대로 본다 — 그쪽은 「너 하나를 노린다」이고 이
	 * 카드는 자리를 노린다.
	 *
	 * <p>규약에 다섯째 색을 더하지 않은 것도 함께 본다. 자홍이던 때는 색이 이 실행기 안에 있는
	 * 것이 근거였고, 지금은 규약의 색을 그대로 쓰므로 더할 이유 자체가 없다.
	 */
	@Test
	void 고리가_규약의_빨강으로_되돌아왔다() {
		assertEquals(TrialWarning.Colors.DEADLY, TrialEndRain.MARK_COLOR,
				"사람이 「빨간색원으로다시 복귀하자」라고 했다 — 자홍(0xC800C8)은 가시성이"
						+ " 너무 안 좋았다");
		assertNotEquals(0xC800C8, TrialEndRain.MARK_COLOR, "자홍으로 되돌아갔다");
		assertNotEquals(TrialWarning.Colors.MARKED, TrialEndRain.MARK_COLOR,
				"「표적」과 같은 보라다. 두 카드가 같은 틱에 돌면 뜻이 섞인다");
		// 규약이 들고 있는 이름은 넷(+옛 이름 하나)뿐이다. 이 카드 때문에 색을 더하지 않았다.
		assertEquals(5, TrialWarning.Colors.class.getFields().length,
				"색 규약에 색이 늘었거나 줄었다");
	}

	/**
	 * ⚠ <b>착탄음이 그냥 폭발음이고, 이름도 솔직한 쪽이다.</b>
	 *
	 * <p>경위가 셋이다.
	 *
	 * <ol>
	 *   <li>이 카드는 {@code DRAGON_FIREBALL_EXPLODE} 로 시작했다. 그런데 바닐라
	 *       {@code sounds.json} 에서 그 이름이 가리키는 파일이 {@code random/explode1~4} 로
	 *       <b>{@code entity.generic.explode} 와 같다</b>(자막 키도 같다). <b>이름만 드래곤
	 *       이었다</b></li>
	 *   <li>사람이 「엔더쪽 폭발음을 원해」라고 해서 {@code ENDER_EYE_DEATH} 로 갔다</li>
	 *   <li>플레이해 보고 <b>「그냥 폭발음으로해줘」</b>라고 해서 되돌렸다</li>
	 * </ol>
	 *
	 * <p><b>되돌아간 자리가 {@code DRAGON_FIREBALL_EXPLODE} 가 아닌 것이 이 시험의 요점이다.</b>
	 * 둘은 같은 소리지만 한쪽은 이름이 거짓말을 한다 — 거기로 돌아가면 다음 사람이 「드래곤
	 * 소리인데 왜 폭발음이 나지」로 같은 자리를 다시 판다.
	 */
	@Test
	void 착탄음이_그냥_폭발음이고_이름도_솔직하다() {
		byte[] compiled = classBytes(TrialEndRain.class);
		assertTrue(references(compiled, "GENERIC_EXPLODE"),
				"사람이 「그냥 폭발음으로해줘」라고 했다");
		assertFalse(references(compiled, "DRAGON_FIREBALL_EXPLODE"),
				"같은 소리인데 이름만 드래곤이다. 값은 되돌리되 이름은 솔직한 쪽을 쓴다");
		assertFalse(references(compiled, "ENDER_EYE_DEATH"),
				"엔더 소리로 되돌아갔다 — 사람이 그것을 물렸다");
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
