package com.sharedfate.sync;

import com.sharedfate.perk.PerkHealthRules;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「엔더폭풍」에서 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>소용돌이를 실제로 굴리려면 {@code ServerLevel} 이 있어야 해서 여기서 돌려 볼 수 없다.
 * 그래도 이 카드가 망가지는 길은 거의 전부 여기서 잡힌다 — <b>바깥으로 민다</b>(공허 낙사 =
 * 월드 삭제), <b>같은 사람을 매 틱 때린다</b>, <b>넷이 다 맞으면 죽는다</b>, <b>파티클이 32칸에서
 * 잘린다</b>. 전멸하면 월드가 지워지는 게임이라 이것들은 실제로 굴려 보고 발견할 수 없다.
 *
 * <p>가장 중요한 것이 첫 묶음이다. 이 저장소는 강한 넉백을 <b>원칙적으로 금지</b>하고 이 카드는
 * 방향이 안쪽이라서 예외로 허용됐다. 그 근거가 코드에서 사라지면 카드가 아니라 월드 삭제 장치다.
 */
class TrialEnderStormTest {

	/** 「엔더폭풍」 카드의 값. 실제 카드와 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int COUNT = 2;
	private static final double SPEED_PER_SECOND = 2.0;
	private static final float DAMAGE = 2.0F;
	private static final double KNOCKBACK = 1.0;
	private static final int REST = 500;
	/** 팀 인원 상한. 공유 체력에서 범위 피해는 팀원별로 합산된다. */
	private static final int TEAM = 4;

	// ------------------------------------------------------------------ 넉백은 언제나 안쪽이다

	/**
	 * ⚠ <b>이 시험이 이 파일에서 가장 중요하다.</b>
	 *
	 * <p>{@code pushDistance} 는 「밀린 뒤가 밀리기 전보다 중앙에 가깝다」를 <b>세기와 무관하게</b>
	 * 약속한다. 그 약속이 이 카드가 예외로 허용된 근거 전부다.
	 *
	 * <p>아레나 안팎의 자리와 온갖 방향을 훑으면서, 카드 값의 100배짜리 넉백으로도 중앙에서
	 * 멀어지는 목적지가 나오지 않는지 본다.
	 */
	@Test
	void 아무리_세게_밀어도_중앙에서_멀어지지_않는다() {
		double wanted = TrialEnderStorm.PUSH_BLOCKS * KNOCKBACK * 100.0;
		for (int spoke = 0; spoke < 72; spoke++) {
			Vec3 inward = new Vec3(Math.cos(spoke * Math.PI / 36.0), 0.0,
					Math.sin(spoke * Math.PI / 36.0));
			for (int px = -48; px <= 48; px += 3) {
				for (int pz = -48; pz <= 48; pz += 3) {
					double pushed = TrialEnderStorm.pushDistance(px, pz, inward, wanted);
					assertTrue(pushed >= 0.0, "음수만큼 밀면 방향이 뒤집힌다");
					double before = Math.sqrt((double) px * px + (double) pz * pz);
					double after = Math.hypot(px + inward.x * pushed, pz + inward.z * pushed);
					assertTrue(after <= before + 1.0E-9,
							"(" + px + ", " + pz + ") 에 선 사람이 " + before + " 에서 "
									+ after + " 로 밀려났다 — 바깥으로 민 것이고,"
									+ " 엔드 섬 밖은 허공이라 그대로 월드 삭제다");
				}
			}
		}
	}

	/**
	 * 밀리는 <b>도중</b>도 안전하다.
	 *
	 * <p>목적지만 가까우면 되는 것이 아니다. 경사에 걸리거나 다른 카드가 끼어들어 중간에 멈출 수
	 * 있으므로, 지나가는 어느 점도 출발점보다 멀면 안 된다. {@code [0, s*]} 구간에서 거리가
	 * 단조 감소한다는 것이 {@code pushDistance} 의 근거이고 그것을 여기서 확인한다.
	 */
	@Test
	void 밀리는_도중에도_멀어지는_순간이_없다() {
		Vec3 inward = new Vec3(-1.0, 0.0, 0.0);
		double pushed = TrialEnderStorm.pushDistance(12.0, 5.0, inward, 100.0);
		double previous = Math.hypot(12.0, 5.0);
		for (int tenth = 1; tenth <= 10; tenth++) {
			double along = pushed * tenth / 10.0;
			double now = Math.hypot(12.0 - along, 5.0);
			assertTrue(now <= previous + 1.0E-9, "밀리는 도중에 중앙에서 멀어졌다");
			previous = now;
		}
	}

	/**
	 * 이미 지나쳐 선 사람은 <b>한 칸도</b> 밀지 않는다.
	 *
	 * <p>소용돌이가 중앙 가까이 왔을 때 그 <b>너머</b>에 선 사람이 이 경우다. 미는 방향은 여전히
	 * 안쪽(= 소용돌이가 가는 쪽)이지만, 그 사람에게는 그 방향이 곧 바깥이다.
	 */
	@Test
	void 소용돌이_너머에_선_사람은_밀지_않는다() {
		Vec3 inward = new Vec3(-1.0, 0.0, 0.0);
		// 소용돌이는 +x 에서 와서 -x 쪽으로 간다. 중앙 너머(-x)에 선 사람을 더 밀면 반대편
		// 가장자리로 나간다.
		assertEquals(0.0, TrialEnderStorm.pushDistance(-3.0, 0.0, inward, 8.0),
				"중앙을 지나친 사람을 더 밀면 반대편 허공으로 보낸다");
		assertEquals(0.0, TrialEnderStorm.pushDistance(0.0, 0.0, inward, 8.0),
				"중앙에 정확히 선 사람은 어느 쪽으로 밀어도 멀어지기만 한다");
		assertEquals(3.0, TrialEnderStorm.pushDistance(3.0, 0.0, inward, 8.0), 1.0E-9,
				"중앙까지 3칸 남은 사람은 3칸만 밀린다 — 넘기면 반대편으로 나간다");
		assertEquals(8.0, TrialEnderStorm.pushDistance(30.0, 0.0, inward, 8.0), 1.0E-9,
				"여유가 넉넉하면 카드가 시킨 만큼 다 민다");
	}

	/**
	 * 사람을 움직이는 길이 하나뿐이다.
	 *
	 * <p>사람과 가해자의 상대 위치로 방향을 잡는 밀기가 이 카드에 섞이면, 소용돌이보다 바깥에 선
	 * 사람이 <b>바깥으로</b> 밀린다. 가장 흔한 길이 <b>가해 개체가 붙은 피해원</b>이다 —
	 * {@code LivingEntity} 가 맞는 쪽에서 스스로 밀어내고, 그 방향은 우리가 정한 것이 아니다.
	 *
	 * <p>바닐라 {@code knockback}·{@code push} 를 이름으로 막지는 못한다. 카드의 값 접근자가
	 * 하필 {@code knockback} 이고 이 파일의 도우미가 {@code pushDistance}·{@code pushVelocity} 라
	 * 상수 풀에서 구별되지 않는다. 그쪽을 지키는 것은 위의
	 * {@link #아무리_세게_밀어도_중앙에서_멀어지지_않는다} 다 — <b>어떤 길로 밀든</b> 목적지가
	 * 중앙에 가까운지를 값에서 직접 센다.
	 */
	@Test
	void 사람을_움직이는_길이_하나뿐이다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("explosion"),
				"피해원에 가해 개체를 달지 않는 폭발형이라야 LivingEntity 가 스스로 밀어내지 않는다");
		assertFalse(bytes.contains("magic"),
				"26.3 에서 magic 은 bypasses_armor 태그에 있다 — 방어구가 통째로 무시되어"
						+ " 카드에 적힌 2 보다 실제로 더 아파진다");
		assertFalse(bytes.contains("dragonBreath"), "dragonBreath 도 bypasses_armor 다");
		assertTrue(bytes.contains("setDeltaMovement"), "속도를 실지 않으면 아무도 밀리지 않는다");
		for (String moves : new String[] {"hurtMarked", "teleportTo", "snapTo", "setPos",
				"randomTeleport"}) {
			assertFalse(bytes.contains(moves),
					moves + " — 사람을 직접 옮기면 pushDistance 의 약속을 지나쳐 간다");
		}
	}

	/**
	 * 위로 띄우지 않는다.
	 *
	 * <p>띄우면 낙하 피해가 붙고, 공중에서는 방향을 못 바꿔 훨씬 멀리 날아간다(바닥 마찰 0.546 대
	 * 공중 감쇠 0.91). 「미는 거리」의 천장을 지키는 가장 싼 방법이 아예 안 띄우는 것이다.
	 */
	@Test
	void 위로_띄우지_않는다() {
		String bytes = classBytes();
		for (String lifts : new String[] {"launchVelocity", "fallGraceTicks", "resetFallDistance",
				"fallDistance", "setJumping", "addDeltaMovement"}) {
			assertFalse(bytes.contains(lifts),
					lifts + " — 띄우면 낙하 피해가 붙고 밀리는 거리가 몇 배로 늘어난다");
		}
		assertTrue(bytes.contains("getDeltaMovement"),
				"세로 속도를 읽어 그대로 돌려놓지 않으면 미는 순간 세로가 0 이 되어 떨어진다");
		assertTrue(bytes.contains("syncVelocity"),
				"켜지 않으면 서버 혼자 민 것이 되어 잠시 뒤 제자리로 되돌아간다");
	}

	/**
	 * 미는 세기가 「착지 충격」과 같다.
	 *
	 * <p>파랑 표식도 밀려나는 소리도 두 카드가 같은 것을 쓴다. 세기까지 같아야 플레이어가 하나만
	 * 배운다 — 여기가 갈라지면 같은 신호에 두 가지 세기가 붙는다.
	 */
	@Test
	void 미는_세기가_이_판의_강한_넉백과_같다() {
		assertEquals(8.0, TrialEnderStorm.PUSH_BLOCKS,
				"이 저장소가 「강한 넉백」으로 쓰는 값은 「착지 충격」의 8칸 하나뿐이다");
		assertEquals(TrialLandingShock.AIR_DRAG, TrialEnderStorm.AIR_DRAG,
				"공중 감쇠는 26.3 의 물리 상수다. 두 곳이 갈라졌다면 판이 올라 한쪽만 고쳐진 것이다");
		assertEquals(TrialLandingShock.pushVelocity(8.0), TrialEnderStorm.pushVelocity(8.0), 1.0E-12,
				"같은 거리를 적었는데 실제로 밀리는 속도가 다르면 세기를 맞춘 뜻이 없다");
		assertEquals(0.72, TrialEnderStorm.pushVelocity(8.0), 1.0E-9, "8 × 0.09 = 0.72 칸/틱");
		assertEquals(0.0, TrialEnderStorm.pushVelocity(-1.0), "음수 거리는 밀지 않는다");
	}

	// ------------------------------------------------------------------ 한 번 지나갈 때 한 번만

	/**
	 * 통과하는 데 여러 틱이 걸린다 — 그래서 명단이 없으면 즉사한다.
	 *
	 * <p>지름 8칸을 초당 2칸으로 지나가므로 80틱이다. 매 틱 2씩 들어가면 160 이고 팀 공유 체력은
	 * 20 이다. 이 시험은 그 숫자를 값에서 직접 세어, 「무적시간이 알아서 걸러 주겠지」로 명단을
	 * 지우는 사람 앞에 세워 둔다.
	 */
	@Test
	void 명단이_없으면_통과하는_동안_즉사한다() {
		double perTick = TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND);
		double crossing = TrialEnderStorm.VORTEX_RADIUS * 2.0 / perTick;
		assertEquals(80.0, crossing, 1.0E-9, "지름 8칸을 초당 2칸으로 — 80틱이다");
		float unguarded = (float) crossing * DAMAGE;
		assertTrue(unguarded > PerkHealthRules.effectiveMaxHealth(null),
				"매 틱 물으면 " + unguarded + " 가 들어간다. 팀 체력은 "
						+ PerkHealthRules.effectiveMaxHealth(null) + " 이다");

		// 바닐라 무적시간 10틱에 기대도 여덟 번이라 「피해 2」가 조용히 16 이 된다.
		float withVanillaImmunity = (float) Math.ceil(crossing / 10.0) * DAMAGE;
		assertTrue(withVanillaImmunity > DAMAGE * COUNT,
				"무적시간에 기대면 " + withVanillaImmunity + " 라 카드에 적힌 값이 거짓이 된다");
	}

	/**
	 * 명단이 소용돌이마다 따로여야 한다.
	 *
	 * <p>한 주기에 하나로 묶으면 중앙에서 겹치는 자리가 2 가 되어
	 * {@code TrialRisks.worstCaseTickDamage} 의 {@code hits(damage, count)} 와 어긋난다. 반대로
	 * 명단을 아예 안 두면 앞의 시험이 깨진다.
	 */
	@Test
	void 한_사람이_받는_몫은_소용돌이마다_한_번이다() {
		assertEquals(DAMAGE * COUNT, TrialRisks.worstCaseTickDamage(card()),
				"분배기가 세는 한 사람 몫이 「소용돌이마다 한 번」과 달라졌다");
	}

	// ------------------------------------------------------------------ 겹치면 죽는가

	@Test
	void 넷이_둘_다_맞아도_살아남는다() {
		float worst = TrialEnderStorm.worstCaseTeamDamage(DAMAGE, COUNT, TEAM);
		assertEquals(16.0F, worst, "2 × 2 × 4");
		assertTrue(worst < PerkHealthRules.effectiveMaxHealth(null),
				"넷이 20초 내내 중앙에 서 있던 판이 전멸이면 즉사 카드다. 실제 합: " + worst);
		assertEquals(0.0F, TrialEnderStorm.worstCaseTeamDamage(DAMAGE, 0, TEAM),
				"소용돌이가 없으면 아무 일도 없다");
	}

	/**
	 * ⚠ 다른 카드와 겹치는 최악이 팀 체력 <b>바로 아래</b>가 아니라 <b>정확히 그 값</b>이다.
	 *
	 * <p>이 카드는 움직이므로 {@code TrialRisks} 의 지점 겹침 목록에 들어갈 수 없다(까닭은
	 * {@code TrialEnderStorm} 클래스 설명). 그래서 「낙뢰」(18)의 고리 위로 소용돌이가 지나갈 수
	 * 있고 그 자리는 한 틱에 <b>20</b> 이다.
	 *
	 * <p>지금은 딱 살아남지만 <b>여유가 0</b> 이다. 피해를 3 으로 올리는 순간 즉사 구역이 생긴다 —
	 * 이 시험이 그 자리에서 멈춰 세운다.
	 */
	@Test
	void 낙뢰와_겹쳐도_한_틱에_죽지는_않는다() {
		float lightning = 18.0F;
		float together = lightning + DAMAGE;
		assertTrue(together <= PerkHealthRules.effectiveMaxHealth(null),
				"낙뢰 " + lightning + " 와 겹친 자리가 " + together + " 다 — 팀 체력 "
						+ PerkHealthRules.effectiveMaxHealth(null) + " 을 넘으면 즉사 구역이다");
	}

	// ------------------------------------------------------------------ 주기

	@Test
	void 가장자리에서_중앙까지_20초다() {
		double perTick = TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND);
		assertEquals(0.1, perTick, 1.0E-9, "초당 2칸이면 0.1 칸/틱");
		assertEquals(400, TrialEnderStorm.travelTicks(perTick), "반경 40 을 0.1 로 — 400틱, 20초");
		assertEquals(0, TrialEnderStorm.travelTicks(0.0), "멈춰 선 소용돌이는 건너오지 않는다");
	}

	/**
	 * 쉬는 시간이 카드에 적힌 그대로여야 한다.
	 *
	 * <p>{@code +1} 은 중앙에 닿는 그 틱이다. 빼면 소용돌이가 중앙 한 칸 앞에서 증발해, 사람이
	 * 보는 것이 도착이 아니라 실종이 된다.
	 */
	@Test
	void 중앙에_닿는_틱까지_그리고_나서_쉰다() {
		int travel = TrialEnderStorm.travelTicks(TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND));
		int cycle = TrialEnderStorm.cycleTicks(travel, REST);
		assertEquals(901, cycle, "400 + 1 + 500");
		assertEquals(REST, cycle - (travel + 1),
				"살아 있는 틱을 뺀 나머지가 카드에 적힌 쉼과 달라졌다");
	}

	@Test
	void 소용돌이는_중앙에서_멈춘다() {
		double perTick = TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND);
		assertEquals(TrialRisks.ARENA_RADIUS, TrialEnderStorm.distanceAt(0, perTick),
				"출발은 아레나 가장자리다 — 팀을 내려놓는 원과 같아야 뜻이 하나다");
		assertEquals(20.0, TrialEnderStorm.distanceAt(200, perTick), 1.0E-9);
		assertEquals(0.0, TrialEnderStorm.distanceAt(400, perTick), 1.0E-9, "중앙에 닿는다");
		assertEquals(0.0, TrialEnderStorm.distanceAt(4000, perTick),
				"지나쳐서 반대편으로 나가면 안 된다");
		assertEquals(TrialRisks.ARENA_RADIUS, TrialEnderStorm.distanceAt(-5, perTick),
				"시간이 되감긴 판에서 음수 거리가 나오면 안 된다");
	}

	/**
	 * 폭풍마다 방향이 달라지고, <b>같은 폭풍은 언제나 같은 방향</b>이다.
	 *
	 * <p>고정하면 축에서 비켜 서 있는 것만으로 카드가 무효가 된다. 반대로 매번 새로 굴리면 서버가
	 * 폭풍 도중에 내려갔다 올라올 때 이미 보여 준 소용돌이가 아레나 반대편으로 순간이동한다 —
	 * 그려 놓은 표식을 무르지 않는 것이 이 전투의 약속이다.
	 */
	@Test
	void 폭풍마다_방향이_다르고_다시_물어도_같다() {
		assertEquals(TrialEnderStorm.baseAngle(1000L, 3L), TrialEnderStorm.baseAngle(1000L, 3L),
				"같은 폭풍을 다시 물었는데 각이 달라지면 서버가 뜰 때마다 소용돌이가 순간이동한다");

		Set<Double> angles = new HashSet<>();
		boolean[] quadrant = new boolean[4];
		for (long index = 0; index < 32; index++) {
			double angle = TrialEnderStorm.baseAngle(1000L, index);
			assertTrue(angle >= 0.0 && angle < Math.PI * 2.0, "각이 한 바퀴 밖이다: " + angle);
			angles.add(angle);
			quadrant[(int) (angle / (Math.PI / 2.0))] = true;
		}
		assertEquals(32, angles.size(),
				"폭풍마다 방향이 달라야 한다 — 같은 선으로만 오면 축에서 비켜 서면 끝난다");
		for (int corner = 0; corner < quadrant.length; corner++) {
			assertTrue(quadrant[corner], corner + " 사분면에서 오는 폭풍이 32번 안에 한 번도 없다"
					+ " — 한쪽에서만 오면 반대편이 안전지대가 된다");
		}
		assertNotEquals(TrialEnderStorm.baseAngle(10L, 0L), TrialEnderStorm.baseAngle(4000L, 0L),
				"판마다 첫 폭풍이 같은 방향이면 두 번째 판부터 자리를 외운다");
	}

	@Test
	void 소용돌이들은_고르게_갈라선다() {
		Vec3 first = TrialEnderStorm.outwardOf(0.0, 0, COUNT);
		Vec3 second = TrialEnderStorm.outwardOf(0.0, 1, COUNT);
		assertEquals(1.0, first.length(), 1.0E-9, "단위 벡터가 아니면 미는 세기가 방향마다 달라진다");
		assertEquals(1.0, second.length(), 1.0E-9);
		assertEquals(0.0, first.y, "높이를 섞으면 위에서 본 판정과 어긋난다");
		assertEquals(-1.0, first.x * second.x + first.z * second.z, 1.0E-9,
				"둘이면 정확히 마주 봐야 가장 오래 떨어져 있다 — 나란히 오면 한쪽만 위험해진다");
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 남은 틱이 <b>1씩</b> 줄어들어야 {@code TrialWarning} 의 층 계산을 그대로 쓸 수 있다.
	 *
	 * <p>어긋나면 경고 층이 건너뛰어지거나 같은 층에서 두 번 울린다.
	 */
	@Test
	void 남은_틱은_한_틱에_하나씩_줄어든다() {
		double perTick = TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND);
		Vec3 outward = new Vec3(1.0, 0.0, 0.0);
		Vec3 standing = new Vec3(12.0, 64.0, 1.0);
		int previous = Integer.MIN_VALUE;
		for (int step = 0; step <= 400; step++) {
			int remaining = TrialEnderStorm.ticksUntilHit(standing, outward,
					TrialEnderStorm.distanceAt(step, perTick), perTick);
			assertNotEquals(TrialEnderStorm.NEVER, remaining, "축 위에 선 사람은 반드시 닿는다");
			if (previous != Integer.MIN_VALUE && remaining > 0) {
				assertEquals(previous - 1, remaining, "틱 " + step + " 에서 남은 틱이 건너뛰었다");
			}
			previous = remaining;
		}
		assertEquals(0, previous, "중앙에 닿을 때까지 남은 틱이 0 이 되어야 한다");
	}

	/**
	 * 닿지 않는 사람에게는 경고하지 않는다.
	 *
	 * <p>「경고는 들었는데 아무 일도 없다」가 되면 그 경험 하나가 다음 경고까지 무시하게 만든다.
	 */
	@Test
	void 비켜_선_사람과_지나친_사람에게는_닿지_않는다() {
		double perTick = TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND);
		Vec3 outward = new Vec3(1.0, 0.0, 0.0);
		double radius = TrialEnderStorm.VORTEX_RADIUS;

		assertEquals(TrialEnderStorm.NEVER, TrialEnderStorm.ticksUntilHit(
						new Vec3(10.0, 64.0, radius + 0.5), outward, 40.0, perTick),
				"축에서 반경보다 멀리 비킨 사람 위로는 지나가지 않는다");
		assertNotEquals(TrialEnderStorm.NEVER, TrialEnderStorm.ticksUntilHit(
						new Vec3(10.0, 64.0, radius - 0.5), outward, 40.0, perTick),
				"반경 안쪽이면 닿는다 — 여기가 뒤집히면 경고가 실제 판정과 어긋난다");
		assertEquals(TrialEnderStorm.NEVER, TrialEnderStorm.ticksUntilHit(
						new Vec3(-radius - 0.5, 64.0, 0.0), outward, 40.0, perTick),
				"소용돌이는 중앙에서 멈춘다. 그 너머에 선 사람에게는 영영 닿지 않는다");
		assertEquals(TrialEnderStorm.NEVER,
				TrialEnderStorm.ticksUntilHit(new Vec3(10.0, 64.0, 0.0), outward, 40.0, 0.0),
				"움직이지 않는 소용돌이는 영영 닿지 않는다");
		assertEquals(0, TrialEnderStorm.ticksUntilHit(new Vec3(38.0, 64.0, 0.0), outward, 40.0,
				perTick), "이미 원 안에 선 사람은 0 이다");
	}

	/**
	 * 가만히 서 있으면 반드시 맞고, 비키면 반드시 산다.
	 *
	 * <p>이 카드가 요구하는 행동이 「옆으로 비키기」 하나라는 말을 값으로 적은 것이다. 소용돌이는
	 * 초당 2칸이라 걷기(4.317)보다 느리므로, 반경만큼 옆으로 나가는 데 드는 시간보다 소용돌이가
	 * 그만큼 다가오는 시간이 길어야 한다.
	 */
	@Test
	void 걸어서_비킬_시간이_있다() {
		double perTick = TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND);
		double walk = 4.317 / 20.0;
		assertTrue(walk > perTick,
				"걷기 " + walk + " 칸/틱이 소용돌이 " + perTick + " 보다 느리면 비킬 수 없다");
		double sidestepTicks = TrialEnderStorm.VORTEX_RADIUS / walk;
		assertTrue(sidestepTicks < TrialWarning.TICKS_SCATTER,
				"옆으로 빠지는 데 " + sidestepTicks + " 틱이 든다 — 넷이 흩어지는 데 드는 시간"
						+ "(" + TrialWarning.TICKS_SCATTER + ")보다 길면 반경이 너무 크다");
		// 정면에 선 사람의 예고는 소용돌이가 건너오는 시간 전부다. 하한이 아니라 천장에 가깝다.
		assertTrue(TrialEnderStorm.travelTicks(perTick) > TrialWarning.TICKS_SCATTER,
				"건너오는 것이 곧 예고다. 흩어질 시간보다 짧으면 이 카드에는 예고가 없는 셈이다");
	}

	@Test
	void 경고_소리는_공용_경고를_쓴다() {
		assertTrue(classBytes().contains("(Lnet/minecraft/server/level/ServerLevel;"
						+ "Lnet/minecraft/world/phys/Vec3;Lcom/sharedfate/sync/TrialWarning$Stage;)V"),
				"자막을 걷어낸 뒤로 소리는 「무엇이 언제 오는가」를 말하는 두 갈래 중 하나다");
	}

	@Test
	void 자막을_띄우지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("shout"), "화면 아래 글자는 전부 걷어낸 판이다");
		assertFalse(bytes.contains("showActionBar"), "자막을 돌려놓으려면 TrialWarning 부터 볼 것");
		assertFalse(bytes.contains("sendSystemMessage"));
	}

	// ------------------------------------------------------------------ 보이는가

	@Test
	void 소용돌이는_긴_형태로_나간다() {
		// 소용돌이는 반경 40 가장자리에서 출발한다. 반대편에 선 사람과는 80칸이라, 짧은 형태면
		// 폭풍이 통째로 안 보인다.
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"긴 형태를 한 번도 부르지 않는다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형태로 되돌아갔다 — 빌드도 로그도 조용한 채로 폭풍이 사라진다");
	}

	@Test
	void 색은_규약에_있는_파랑이다() {
		// 규약: 빨강=서 있으면 죽는다 · 노랑=번개 · 파랑=밀려난다 · 보라=너 하나를 노린다.
		// 이 카드는 밀려나는 것이 본체라 파랑이 그 뜻 그대로다.
		assertEquals(TrialWarning.Colors.SHOVE, TrialEnderStorm.markColor(),
				"이 카드는 「닿으면 아프고 밀려난다」라 파랑이 그 뜻 그대로다");
		assertEquals(TrialLandingShock.markColor(), TrialEnderStorm.markColor(),
				"밀려나는 카드 둘이 다른 색을 쓰면 표식이 언어가 아니라 소음이 된다");
		assertNotEquals(TrialWarning.Colors.DEADLY, TrialEnderStorm.markColor(),
				"빨강은 자리 폭격·기둥 화염구·연쇄 포격이 쓰고 있다 — 섞이면 무엇이 오는지 안 읽힌다");
		assertNotEquals(TrialWarning.Colors.LIGHTNING, TrialEnderStorm.markColor());
		assertNotEquals(TrialWarning.Colors.MARKED, TrialEnderStorm.markColor());
	}

	@Test
	void 한_틱_점_예산_안이다() {
		int points = TrialEnderStorm.markPoints(COUNT);
		assertTrue(points > 0, "아무것도 안 그리면 이 카드에는 예고가 없다");
		assertTrue(points <= TrialEnderStorm.MAX_POINTS_PER_TICK,
				"한 틱에 " + points + " 점이 나간다. 이 판이 쓰는 예산은 400~440 이다");
		assertEquals(400, TrialEnderStorm.MAX_POINTS_PER_TICK,
				"「낙뢰」가 반경 3 짜리 고리 열 개로 쓰는 400점이 이 저장소의 예산이다");
		assertEquals(COUNT * (TrialWarning.ringPoints(TrialEnderStorm.VORTEX_RADIUS)
						+ TrialEnderStorm.COLUMN_POINTS), points,
				"점 수를 값에서 뽑지 않으면 반경이나 개수를 올릴 때 예산이 조용히 깨진다");
	}

	/**
	 * 바닥 원이 기둥보다 언제나 넓다.
	 *
	 * <p>닿는 범위가 바닥 원인데 기둥이 더 넓으면 발밑에서 어디까지가 위험한지를 잘못 읽는다.
	 * 실제 회오리는 위가 넓지만 이 카드의 위협은 「보고 비키는 것」이라 그쪽을 버렸다.
	 */
	@Test
	void 기둥이_바닥_원보다_넓어지지_않는다() {
		assertTrue(TrialEnderStorm.COLUMN_TOP_FACTOR < 1.0,
				"위가 넓으면 가장 넓은 곳이 공중이라 닿는 범위를 잘못 읽는다");
		assertTrue(TrialEnderStorm.COLUMN_TOP_FACTOR > 0.0,
				"0 이면 꼭짓점 하나로 모여 소용돌이가 아니라 원뿔이 된다");
		assertTrue(TrialEnderStorm.COLUMN_HEIGHT > 1.8,
				"사람 키보다 낮으면 멀리서 지형에 가린다");
	}

	// ------------------------------------------------------------------ 건드리지 않는 것

	@Test
	void 드래곤을_건드리지도_읽지도_않는다() {
		String bytes = classBytes();
		for (String banned : new String[] {"setPhase", "getPhaseManager", "getCurrentPhase",
				"EnderDragonPhase", "setTarget", "getFightOrigin", "getSubEntities"}) {
			assertFalse(bytes.contains(banned),
					banned + " 이 상수 풀에 있다 — 드래곤 페이즈에 끼어들었다가 착지를 아예 안 하게"
							+ " 된 사고가 있다. 이 카드는 드래곤과 무관하다");
		}
	}

	@Test
	void 블록을_건드리지_않는다() {
		String bytes = classBytes();
		for (String breaks : new String[] {"removeBlock", "destroyBlock", "setBlockAndUpdate",
				"setBlock"}) {
			assertFalse(bytes.contains(breaks),
					breaks + " — 엔드 섬에 구멍이 하나 뚫리면 공허 낙사가 되고 다음 전투부터"
							+ " 발판이 달라진다");
		}
		assertTrue(bytes.contains("getHeightmapPos"), "지면 높이는 읽기만 한다");
	}

	@Test
	void 월드_시각을_직접_읽지_않는다() {
		// 시련 화면이 떠 판이 얼어붙는 동안 게임 시각은 흐르지 않는다. 넘겨받은 now 와 섞어 쓰면
		// 소용돌이가 그 자리에 멈추거나 한꺼번에 건너뛴다.
		assertFalse(classBytes().contains("getGameTime"),
				"level.getGameTime() 을 쓰고 있다 — 얼어붙은 판에서 소용돌이가 어긋난다");
	}

	/**
	 * 다른 차원에 선 팀원을 때리지 않는다.
	 *
	 * <p>{@code DragonTrialManager} 가 넘기는 목록은 「접속해 있는 팀원」이라 오버월드에 있는
	 * 사람도 들어 있다. 판정이 중앙에서 잰 수평 거리로만 도므로, 차원을 안 보면 오버월드 원점
	 * 근처에 선 팀원이 엔드의 소용돌이에 맞는다.
	 */
	@Test
	void 이_차원에_있는_사람만_판정한다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("isSpectator"), "관전자를 때리면 안 된다");
		assertTrue(bytes.contains("level") && bytes.contains("isAlive"),
				"차원과 생존을 보지 않으면 엔드 밖의 팀원이 맞는다");
	}

	// ------------------------------------------------------------------ 카드와 문서

	@Test
	void 카드_값이_문서와_같다() {
		TrialCatalog.Risk.EnderStorm storm = card();
		assertEquals(COUNT, storm.count());
		assertEquals(SPEED_PER_SECOND, storm.speedPerSecond());
		assertEquals(DAMAGE, storm.damage());
		assertEquals(KNOCKBACK, storm.knockback(), "넉백은 세기의 배율이다. 실제 칸 수는 실행기가 정한다");
		assertEquals(REST, storm.restTicks());
	}

	// ------------------------------------------------------------------ 도우미

	private static TrialCatalog.Risk.EnderStorm card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:ender_storm");
		assertNotNull(trial, "「엔더폭풍」 카드가 없다");
		for (TrialCatalog.Risk risk : trial.risks()) {
			if (risk instanceof TrialCatalog.Risk.EnderStorm storm) {
				return storm;
			}
		}
		throw new AssertionError("「엔더폭풍」 카드에 EnderStorm 이 없다");
	}

	/** 컴파일된 우리 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String classBytes() {
		try (InputStream in = TrialEnderStorm.class
				.getResourceAsStream("/com/sharedfate/sync/TrialEnderStorm.class")) {
			if (in == null) {
				return fail("TrialEnderStorm 의 클래스 파일을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
