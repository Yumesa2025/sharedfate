package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「부활」 카드에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>크리스탈을 실제로 세우는 것과 드래곤을 끌어올리는 것은 {@code ServerLevel} 이 있어야 해서
 * 여기서 볼 수 없다. 그런데 이 카드가 망가지는 길은 거의 전부 계산 쪽이다 — <b>한 카드로 두 번
 * 되살아난다</b>, <b>연출이 끝나기 전에 끝난 것으로 친다</b>, <b>이미 살아 있는 자리에 겹쳐
 * 놓는다</b>, <b>사람 발밑에 놓는다</b>, 그리고 <b>무적이 영영 안 풀린다</b>. 전멸하면 월드가
 * 지워지는 게임이라 이것들은 돌려 보고 발견할 수 없다.
 *
 * <p>무적은 개체에 거는 것이라 여기서 개체를 만들 수 없지만, <b>거는 값</b>은 순수 계산이다.
 * 그 값이 언제나 유한한 카운트다운이라는 것만 붙들면 「영영 무적」은 구조적으로 불가능해진다 —
 * {@code Entity.commonTick} 이 매 틱 1씩 깎기 때문이다.
 */
class TrialCrystalReviveTest {

	/** 실제 카드 값. 시험이 현실과 붙어 있어야 값을 바꿀 때 여기가 먼저 운다. */
	private static final int COUNT = 10;
	private static final int SHOW_TICKS = 100;
	private static final long GRANTED = 1000L;

	/** 기둥 위 크리스탈 자리 하나와 드래곤이 머무는 중앙 상공. 빔 계산에 쓴다. */
	private static final Vec3 SEAT = new Vec3(42.5, 77.0, 0.5);
	private static final Vec3 PERCH = new Vec3(0.0, 133.0, 0.0);

	private static TrialCatalog.Risk.CrystalRevive card() {
		return new TrialCatalog.Risk.CrystalRevive(COUNT, SHOW_TICKS, 0.0F);
	}

	/** 「이미 썼다」는 기억은 정적이다. 시험끼리 새지 않게 매번 비운다. */
	@BeforeEach
	void reset() {
		TrialCrystalRevive.clearState();
	}

	// ------------------------------------------------------------------ 한 번만 발동한다

	@Test
	void 한_카드로_두_번_되살아나지_않는다() {
		TrialCatalog.Risk.CrystalRevive risk = card();
		assertEquals(TrialCrystalRevive.Step.REVIVE,
				TrialCrystalRevive.step(GRANTED, GRANTED + SHOW_TICKS, risk));
		for (long now = GRANTED + SHOW_TICKS; now < GRANTED + SHOW_TICKS + 500L; now++) {
			assertEquals(TrialCrystalRevive.Step.NOTHING,
					TrialCrystalRevive.step(GRANTED, now, risk),
					"카드가 쌓여 있는 내내 되살아나면 전투가 끝나지 않는다: " + now);
		}
	}

	@Test
	void 값이_같은_카드는_같은_카드다() {
		// 열쇠는 「받은 틱 + 위험 값」이다. 같은 값으로 다시 물어도 이미 쓴 카드여야 한다.
		assertEquals(TrialCrystalRevive.Step.REVIVE,
				TrialCrystalRevive.step(GRANTED, GRANTED + SHOW_TICKS, card()));
		assertEquals(TrialCrystalRevive.Step.NOTHING,
				TrialCrystalRevive.step(GRANTED, GRANTED + SHOW_TICKS, card()),
				"레코드는 값이 같으면 같은 것이다. 새로 만들었다고 카드가 되살아나면 안 된다");
	}

	@Test
	void clearState_뒤에는_다음_전투에서_다시_발동한다() {
		assertEquals(TrialCrystalRevive.Step.REVIVE,
				TrialCrystalRevive.step(GRANTED, GRANTED + SHOW_TICKS, card()));
		TrialCrystalRevive.clearState();
		assertEquals(TrialCrystalRevive.Step.REVIVE,
				TrialCrystalRevive.step(GRANTED, GRANTED + SHOW_TICKS, card()),
				"비우지 않으면 다음 판에서 같은 틱에 받은 카드가 조용히 죽는다");
	}

	// ------------------------------------------------------------------ 연출과 부활의 시각

	@Test
	void 연출_동안은_부활하지_않고_끝나는_그_틱에_부활한다() {
		TrialCatalog.Risk.CrystalRevive risk = card();
		for (long now = GRANTED; now < GRANTED + SHOW_TICKS; now++) {
			assertEquals(TrialCrystalRevive.Step.SHOW, TrialCrystalRevive.step(GRANTED, now, risk),
					"연출이 끝나기 전에 끝난 것으로 치면 예고한 뜻이 없다: " + (now - GRANTED));
			assertFalse(TrialCrystalRevive.revivesAt(now, GRANTED, SHOW_TICKS));
		}
		assertTrue(TrialCrystalRevive.revivesAt(GRANTED + SHOW_TICKS, GRANTED, SHOW_TICKS));
		assertEquals(TrialCrystalRevive.Step.REVIVE,
				TrialCrystalRevive.step(GRANTED, GRANTED + SHOW_TICKS, risk));
	}

	@Test
	void showTicks_가_0_이하면_연출_없이_즉시() {
		assertTrue(TrialCrystalRevive.revivesAt(GRANTED, GRANTED, 0));
		assertTrue(TrialCrystalRevive.revivesAt(GRANTED, GRANTED, -20));
		assertEquals(TrialCrystalRevive.Step.REVIVE, TrialCrystalRevive.step(GRANTED, GRANTED,
				new TrialCatalog.Risk.CrystalRevive(COUNT, 0, 0.0F)));
	}

	@Test
	void 복원_직후_now_가_받은_틱보다_작아도_연출을_건너뛰지_않는다() {
		// 세션은 저장 파일에서 오고 게임 시각은 월드에서 온다. 그냥 빼면 음수가 나온다.
		for (long now = GRANTED - 500L; now < GRANTED; now++) {
			assertFalse(TrialCrystalRevive.revivesAt(now, GRANTED, SHOW_TICKS),
					"음수 위상으로 연출 없이 되살아나면 안 된다: " + now);
			assertEquals(SHOW_TICKS, TrialCrystalRevive.remainingShowTicks(now, GRANTED, SHOW_TICKS));
			assertEquals(0.0F, TrialCrystalRevive.showProgress(now, GRANTED, SHOW_TICKS));
		}
	}

	@Test
	void 남은_틱은_한_칸씩_줄어_부활하는_틱에_0_이_된다() {
		int previous = TrialCrystalRevive.remainingShowTicks(GRANTED, GRANTED, SHOW_TICKS);
		assertEquals(SHOW_TICKS, previous, "받은 틱에는 연출이 통째로 남아 있다");
		for (long now = GRANTED + 1L; now <= GRANTED + SHOW_TICKS; now++) {
			int remaining = TrialCrystalRevive.remainingShowTicks(now, GRANTED, SHOW_TICKS);
			assertEquals(previous - 1, remaining, "예고 층이 건너뛰면 그 층의 소리가 안 울린다: " + now);
			previous = remaining;
		}
		assertEquals(0, previous);
	}

	@Test
	void 연출_중에_경고_세_층이_모두_나온다() {
		// 층은 TrialWarning 의 것을 그대로 쓴다. 연출이 세 문턱보다 짧으면 층이 통째로 잘린다.
		int layers = 0;
		for (long now = GRANTED; now <= GRANTED + SHOW_TICKS; now++) {
			if (TrialRisks.stageJustChanged(
					TrialCrystalRevive.remainingShowTicks(now, GRANTED, SHOW_TICKS))) {
				layers++;
			}
		}
		assertEquals(3, layers, "연출 길이가 예고 세 층을 다 담아야 한다");
	}

	// ------------------------------------------------------------------ 연출 진행도

	@Test
	void 연출_진행도는_0_과_1_을_벗어나지_않는다() {
		for (long now = GRANTED - 300L; now <= GRANTED + SHOW_TICKS + 300L; now++) {
			float progress = TrialCrystalRevive.showProgress(now, GRANTED, SHOW_TICKS);
			assertTrue(progress >= 0.0F && progress <= 1.0F,
					"선의 길이가 이 값이다. 벗어나면 선이 자리를 지나치거나 뒤로 뻗는다: " + progress);
		}
		assertEquals(0.0F, TrialCrystalRevive.showProgress(GRANTED, GRANTED, SHOW_TICKS));
		assertEquals(1.0F, TrialCrystalRevive.showProgress(GRANTED + SHOW_TICKS, GRANTED, SHOW_TICKS));
		assertEquals(0.5F, TrialCrystalRevive.showProgress(GRANTED + 50L, GRANTED, SHOW_TICKS));
	}

	@Test
	void 연출이_없으면_선은_처음부터_닿아_있다() {
		assertEquals(1.0F, TrialCrystalRevive.showProgress(GRANTED, GRANTED, 0),
				"즉시 카드는 그릴 시간이 없다. 0 이면 점 하나만 찍히고 끝난다");
	}

	// ------------------------------------------------------------------ 어느 자리에 생기는가

	@Test
	void 이미_살아_있는_자리는_건너뛴다() {
		List<Vec3> seats = seats(4);
		List<Vec3> alive = List.of(seats.get(1), seats.get(3));
		List<Vec3> open = TrialCrystalRevive.openSeats(seats, alive);
		assertEquals(List.of(seats.get(0), seats.get(2)), open,
				"겹쳐 놓으면 하나를 깰 때 옆 것이 연쇄로 터져 기둥 위가 통째로 날아간다");
	}

	@Test
	void 빈_자리가_없으면_아무_일도_없다() {
		List<Vec3> seats = seats(10);
		assertTrue(TrialCrystalRevive.openSeats(seats, seats).isEmpty());
		assertTrue(TrialCrystalRevive.pickSeats(TrialCrystalRevive.openSeats(seats, seats), COUNT)
				.isEmpty(), "전부 살아 있는데 발동하면 카드가 아무 뜻도 없다");
	}

	@Test
	void count_가_빈_자리보다_많으면_빈_자리_수만큼만() {
		List<Vec3> seats = seats(3);
		assertEquals(3, TrialCrystalRevive.pickSeats(seats, COUNT).size(),
				"없는 열한 번째 기둥을 집으려다 터지면 안 된다");
		assertEquals(2, TrialCrystalRevive.pickSeats(seats, 2).size());
		assertTrue(TrialCrystalRevive.pickSeats(seats, 0).isEmpty());
		assertTrue(TrialCrystalRevive.pickSeats(seats, -4).isEmpty());
		assertTrue(TrialCrystalRevive.pickSeats(List.of(), COUNT).isEmpty());
	}

	// ------------------------------------------------------------------ 사람을 죽이지 않는다

	@Test
	void 자리에_서_있는_사람_발밑에는_생성하지_않는다() {
		List<Vec3> seats = seats(3);
		// 기둥 꼭대기에 선 사람의 발은 자리보다 한 칸 아래다.
		Vec3 standing = seats.get(1).add(0.4, -1.0, -0.3);
		List<Vec3> safe = TrialCrystalRevive.withoutOccupied(seats, List.of(standing));
		assertEquals(List.of(seats.get(0), seats.get(2)), safe,
				"엔드 크리스탈은 제 발밑에 불을 놓고 부서질 때 터진다. 부활은 시간 손실이지 사형이 아니다");
	}

	@Test
	void 자리에서_충분히_떨어진_사람은_자리를_막지_않는다() {
		List<Vec3> seats = seats(2);
		List<Vec3> away = List.of(
				seats.getFirst().add(6.0, 0.0, 0.0),
				seats.getFirst().add(0.0, -20.0, 0.0));
		assertEquals(seats, TrialCrystalRevive.withoutOccupied(seats, away),
				"기둥 아래 땅에 선 사람까지 자리를 막으면 팀이 자리 열 개를 영영 잠글 수 있다");
	}

	@Test
	void 아무도_없으면_자리가_그대로_남는다() {
		List<Vec3> seats = seats(5);
		assertEquals(seats, TrialCrystalRevive.withoutOccupied(seats, List.of()));
	}

	// ------------------------------------------------------------------ 드래곤을 어디에 두는가

	@Test
	void 드래곤은_가장_높은_기둥보다_위에_머문다() {
		List<Vec3> tops = List.of(
				new Vec3(-40.0, 76.0, 0.0),
				new Vec3(40.0, 103.0, 0.0),
				new Vec3(0.0, 90.0, 40.0),
				new Vec3(0.0, 82.0, -40.0));
		Vec3 perch = TrialCrystalRevive.watchPoint(tops, 30.0);
		assertNotNull(perch);
		assertEquals(0.0, perch.x, 1.0E-9, "가로는 기둥들의 한가운데다");
		assertEquals(0.0, perch.z, 1.0E-9);
		assertEquals(133.0, perch.y, 1.0E-9,
				"평균을 쓰면 제일 높은 기둥에 올라선 사람이 드래곤과 같은 높이에 선다");
		for (Vec3 top : tops) {
			assertTrue(perch.y - top.y >= 30.0, "화살이 닿는 높이면 「때릴 수 없다」가 거짓말이 된다");
		}
	}

	@Test
	void 기둥이_없으면_붙들_자리도_없다() {
		assertNull(TrialCrystalRevive.watchPoint(List.of(), 30.0),
				"지형을 갈아엎은 판이 있다. 좌표를 지어내느니 붙들지 않는 편이 낫다");
	}

	@Test
	void 가까워지면_정확히_그_점에_붙는다() {
		Vec3 perch = new Vec3(0.0, 130.0, 0.0);
		Vec3 near = perch.add(0.5, 0.0, 0.0);
		assertSame(perch, TrialCrystalRevive.towards(near, perch, 0.2, 1.0),
				"비율로만 당기면 드래곤 비행과 균형을 이룬 지점에서 멈춰 사람 쪽으로 흘러내린다");
	}

	@Test
	void 멀면_한_번에_옮기지_않고_당긴다() {
		Vec3 from = new Vec3(0.0, 60.0, 0.0);
		Vec3 perch = new Vec3(0.0, 130.0, 0.0);
		Vec3 next = TrialCrystalRevive.towards(from, perch, 0.2, 1.0);
		assertEquals(74.0, next.y, 1.0E-9, "순간이동은 연출이 아니라 사고처럼 보인다");
		assertTrue(next.distanceTo(perch) < from.distanceTo(perch), "당기고 있어야 한다");
	}

	@Test
	void 당기는_비율이_범위를_벗어나도_지나치지_않는다() {
		Vec3 from = new Vec3(0.0, 60.0, 0.0);
		Vec3 perch = new Vec3(0.0, 130.0, 0.0);
		assertEquals(perch, TrialCrystalRevive.towards(from, perch, 5.0, 1.0));
		assertEquals(from, TrialCrystalRevive.towards(from, perch, -1.0, 1.0));
	}

	// ------------------------------------------------------------------ 연출의 선

	@Test
	void 선은_드래곤과_자리를_잇는_선분_안에만_찍힌다() {
		Vec3 from = new Vec3(0.0, 130.0, 0.0);
		Vec3 to = new Vec3(40.0, 77.0, 0.0);
		double whole = from.distanceTo(to);
		for (float progress = 0.0F; progress <= 1.0F; progress += 0.05F) {
			for (Vec3 point : TrialCrystalRevive.beamPoints(from, to, progress, 8)) {
				assertTrue(from.distanceTo(point) + point.distanceTo(to) <= whole + 1.0E-6,
						"선이 선분을 벗어나면 「여기에 생긴다」가 거짓말이 된다: " + point);
			}
		}
	}

	@Test
	void 선은_진행도만큼만_자란다() {
		Vec3 from = new Vec3(0.0, 130.0, 0.0);
		Vec3 to = new Vec3(0.0, 80.0, 0.0);
		List<Vec3> half = TrialCrystalRevive.beamPoints(from, to, 0.5F, 5);
		assertEquals(5, half.size());
		assertEquals(105.0, half.getLast().y, 1.0E-9, "절반이면 중간까지다");
		assertEquals(80.0, TrialCrystalRevive.beamPoints(from, to, 1.0F, 5).getLast().y, 1.0E-9,
				"닿는 순간이 곧 부활이다");
	}

	@Test
	void 진행도가_범위를_벗어나도_선이_선분_밖으로_나가지_않는다() {
		Vec3 from = new Vec3(0.0, 130.0, 0.0);
		Vec3 to = new Vec3(0.0, 80.0, 0.0);
		assertEquals(80.0, TrialCrystalRevive.beamPoints(from, to, 9.0F, 4).getLast().y, 1.0E-9);
		for (Vec3 point : TrialCrystalRevive.beamPoints(from, to, -3.0F, 4)) {
			assertEquals(130.0, point.y, 1.0E-9, "음수면 드래곤 뒤쪽으로 뻗는다");
		}
		assertTrue(TrialCrystalRevive.beamPoints(from, to, 0.5F, 0).isEmpty());
	}

	// ------------------------------------------------------------------ 크리스탈은 언제 서는가

	@Test
	void 크리스탈은_연출이_시작될_때_선다() {
		TrialCatalog.Risk.CrystalRevive risk = card();
		// 받은 그 틱이 이미 SHOW 다. 크리스탈을 세우는 것은 이 틱이고, REVIVE 가 하는 일은
		// 세우는 것이 아니라 걷는 것이다 — 뒤집으면 연출 내내 화면에 아무것도 없다.
		assertEquals(TrialCrystalRevive.Step.SHOW, TrialCrystalRevive.step(GRANTED, GRANTED, risk),
				"연출 첫 틱에 물건이 서 있어야 「복구되는 중」이 보인다");
		// 세우는 그 틱에 이미 무적이 걸릴 만큼의 값이 나와야 한다. 0 이면 세우자마자 깨질 수 있다.
		assertTrue(TrialCrystalRevive.guardTicks(
						TrialCrystalRevive.remainingShowTicks(GRANTED, GRANTED, SHOW_TICKS)) > 0,
				"무적 없이 세우면 복구되는 중에 깨져 연출이 무의미해진다");
		// 그리고 그 틱에 이미 선이 조금은 뻗어 있어야 한다. 물건만 나타나면 예고가 아니라 사고다.
		BlockPos head = TrialCrystalRevive.beamHead(SEAT, PERCH, 0.0F);
		assertNotNull(head);
		assertTrue(head.getY() > SEAT.y,
				"길이가 0 이면 크리스탈 안에 점 하나가 박힌 꼴이라 선이 시작된 것을 못 본다");
	}

	// ------------------------------------------------------------------ 무적을 반드시 푼다

	@Test
	void 연출_중에는_무적이고_끝나면_무적이_풀린다() {
		TrialCatalog.Risk.CrystalRevive risk = card();
		for (long now = GRANTED; now < GRANTED + SHOW_TICKS; now++) {
			assertEquals(TrialCrystalRevive.Step.SHOW, TrialCrystalRevive.step(GRANTED, now, risk));
			assertTrue(TrialCrystalRevive.guardTicks(
							TrialCrystalRevive.remainingShowTicks(now, GRANTED, SHOW_TICKS)) > 0,
					"연출 중 한 틱이라도 0 이면 그 틱에 깨진다: " + (now - GRANTED));
		}
		// 끝나는 틱이 거두는 자리다. 그 뒤로는 영원히 NOTHING 이라 무적을 다시 걸 길이 없다 —
		// 이것이 「연출이 끝나면 풀린다」를 보장하는 두 축 중 하나다(다른 하나는 아래 만료).
		assertEquals(TrialCrystalRevive.Step.REVIVE,
				TrialCrystalRevive.step(GRANTED, GRANTED + SHOW_TICKS, risk));
		for (long now = GRANTED + SHOW_TICKS; now < GRANTED + SHOW_TICKS + 600L; now++) {
			assertEquals(TrialCrystalRevive.Step.NOTHING, TrialCrystalRevive.step(GRANTED, now, risk),
					"끝난 뒤에도 SHOW 가 돌면 무적이 계속 다시 걸려 전투가 끝나지 않는다: " + now);
		}
	}

	@Test
	void 어떤_경로로_끝나도_무적은_스스로_만료된다() {
		// 정상 종료가 아닌 길이 여럿이다 — 연출 도중 드래곤이 죽거나, 팀이 전멸해 월드가 갈리거나,
		// 서버가 그냥 죽거나, clearState 가 불린다. 그 전부에 공통된 사실은 「우리가 다음 틱에
		// 다시 걸어 주지 못한다」는 것뿐이다. 그래서 거는 값이 언제나 유한해야 한다.
		int longest = SHOW_TICKS + TrialCrystalRevive.GUARD_MARGIN_TICKS;
		for (int remaining = 0; remaining <= SHOW_TICKS; remaining++) {
			int guard = TrialCrystalRevive.guardTicks(remaining);
			assertTrue(guard > 0, "연출 중에는 무적이어야 한다: " + remaining);
			assertTrue(guard <= longest,
					"영구 무적이면 그 크리스탈은 영영 못 깨고 전투가 끝나지 않는다: " + guard);
		}
		assertEquals(TrialCrystalRevive.GUARD_MARGIN_TICKS, TrialCrystalRevive.guardTicks(0),
				"마지막 틱에도 여유는 준다. 서버가 한 틱 밀린다고 연출 중에 깨지면 안 된다");
		assertEquals(TrialCrystalRevive.GUARD_MARGIN_TICKS, TrialCrystalRevive.guardTicks(-999),
				"음수가 들어와도 무한이 되지 않는다");
	}

	@Test
	void clearState_는_연출_기억을_남기지_않는다() {
		// 연출 도중 월드가 갈리면 clearState 가 유일한 정상 경로다. 카드 기억이 남으면 다음 판의
		// 카드가 조용히 죽고, 세워 둔 크리스탈 목록이 남으면 그 개체를 영원히 붙들고 있게 된다.
		assertEquals(TrialCrystalRevive.Step.SHOW, TrialCrystalRevive.step(GRANTED, GRANTED, card()));
		TrialCrystalRevive.clearState();
		assertEquals(TrialCrystalRevive.Step.SHOW, TrialCrystalRevive.step(GRANTED, GRANTED, card()));
		TrialCrystalRevive.clearState();
		assertEquals(TrialCrystalRevive.Step.REVIVE,
				TrialCrystalRevive.step(GRANTED, GRANTED + SHOW_TICKS, card()));
	}

	// ------------------------------------------------------------------ 바닐라 빔이 그리는 선

	@Test
	void 빔_대상은_진행도만큼_크리스탈에서_멀어진다() {
		double whole = SEAT.distanceTo(PERCH);
		double previous = -1.0;
		for (float progress = 0.0F; progress <= 1.0F; progress += 0.05F) {
			BlockPos head = TrialCrystalRevive.beamHead(SEAT, PERCH, progress);
			assertNotNull(head);
			double reach = SEAT.distanceTo(Vec3.atLowerCornerOf(head));
			assertTrue(reach >= previous - 1.8,
					"선이 줄어들면 「복구되는 중」이 아니라 「꺼지는 중」으로 읽힌다: " + progress);
			assertTrue(reach <= whole + 1.8,
					"선이 드래곤을 지나치면 어디로 이어지는지가 거짓말이 된다: " + reach);
			previous = reach;
		}
	}

	@Test
	void 빔은_연출이_끝나는_순간_드래곤에_닿는다() {
		assertEquals(BlockPos.containing(PERCH.x, PERCH.y, PERCH.z),
				TrialCrystalRevive.beamHead(SEAT, PERCH, 1.0F), "닿는 순간이 곧 부활이다");
		assertNotEquals(BlockPos.containing(PERCH.x, PERCH.y, PERCH.z),
				TrialCrystalRevive.beamHead(SEAT, PERCH, 0.5F), "절반이면 아직 닿지 않았다");
	}

	@Test
	void 진행도가_범위를_벗어나도_빔이_선분_밖으로_나가지_않는다() {
		assertEquals(BlockPos.containing(PERCH.x, PERCH.y, PERCH.z),
				TrialCrystalRevive.beamHead(SEAT, PERCH, 9.0F));
		BlockPos back = TrialCrystalRevive.beamHead(SEAT, PERCH, -3.0F);
		assertNotNull(back);
		assertTrue(back.getY() > SEAT.y && back.getY() < PERCH.y,
				"음수면 크리스탈 아래로 뻗는다 — 땅속을 가리키는 선은 아무 뜻도 없다");
	}

	@Test
	void 붙들_자리가_없으면_빔도_걷는다() {
		assertNull(TrialCrystalRevive.beamHead(SEAT, null, 0.5F),
				"null 을 그대로 setBeamTarget 에 넘기면 선이 지워진다. 하늘의 빈 점을 가리키느니 없는 편이 낫다");
	}

	// ------------------------------------------------------------------ 카드 값

	@Test
	void 카드_값이_설계와_같다() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:crystal_revival");
		assertNotNull(trial, "「부활」 카드가 없다");
		assertEquals(1, trial.risks().size());
		TrialCatalog.Risk.CrystalRevive risk = switch (trial.risks().getFirst()) {
			case TrialCatalog.Risk.CrystalRevive revive -> revive;
			default -> throw new AssertionError("「부활」 카드가 다른 위험을 걸고 있다");
		};
		assertEquals(COUNT, risk.count());
		assertEquals(SHOW_TICKS, risk.showTicks());
		assertEquals(0.0F, risk.heal(),
				"바닐라 크리스탈이 이미 드래곤을 회복시킨다. 여기서 또 주면 같은 값을 두 번 받는다");
		assertTrue(risk.showTicks() >= TrialWarning.TICKS_REPOSITION,
				"기둥 위에서 내려올 시간은 줘야 한다 — 밀어내지 않고 건너뛰는 것이 이 카드의 약속이다");
	}

	/** 기둥 열 개를 흉내 낸 자리들. 실제 좌표는 시드에서 오므로 값 자체에는 뜻이 없다. */
	private static List<Vec3> seats(int count) {
		List<Vec3> seats = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			double angle = (Math.PI * 2.0 * index) / Math.max(1, count);
			seats.add(new Vec3(Math.cos(angle) * 43.0, 77.0 + index, Math.sin(angle) * 43.0));
		}
		return List.copyOf(seats);
	}
}
