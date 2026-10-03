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
 * 그래도 이 카드가 망가지는 길은 거의 전부 여기서 잡힌다 — <b>섬 밖으로 민다</b>(공허 낙사 =
 * 월드 삭제), <b>같은 사람을 매 틱 때린다</b>, <b>넷이 다 맞으면 죽는다</b>, <b>파티클이 32칸에서
 * 잘린다</b>. 전멸하면 월드가 지워지는 게임이라 이것들은 실제로 굴려 보고 발견할 수 없다.
 *
 * <p>⚠ 가장 중요한 것이 첫 묶음이다. 이 카드는 <b>바깥으로</b> 민다 — 전에는 안쪽이었고, 그
 * 「안쪽으로만 민다」가 이 카드를 강한 넉백 금지의 예외로 만든 조건이었다. 사람이 플레이해 보고
 * 「한번 밀쳐지면 끝」이라 해서 방향을 뒤집었으므로, 이제 사람이 허공으로 나가는 것을 막는 장치는
 * <b>{@code pushDistance} 의 천장 하나뿐</b>이다. 그 천장이 코드에서 사라지면 카드가 아니라
 * 월드 삭제 장치다.
 */
class TrialEnderStormTest {

	/** 「엔더폭풍」 카드의 값. 실제 카드와 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int COUNT = 2;
	private static final double SPEED_PER_SECOND = 2.0;
	private static final float DAMAGE = 2.0F;
	/** 사람이 플레이해 보고 1.0 에서 올린 값이다. 방향이 바깥이 된 것과 한 묶음이다. */
	private static final double KNOCKBACK = 3.0;
	private static final int REST = 500;
	/** 팀 인원 상한. 공유 체력에서 범위 피해는 팀원별로 합산된다. */
	private static final int TEAM = 4;

	// ------------------------------------------------------------------ 천장: 목적지는 언제나 섬 안

	/**
	 * ⚠ <b>이 시험이 이 파일에서 가장 중요하다.</b>
	 *
	 * <p>{@code pushDistance} 는 「밀린 목적지가 섬 안」을 <b>세기와 무관하게</b> 약속한다. 방향이
	 * 바깥으로 뒤집힌 지금 그 약속이 이 카드의 안전장치 전부다.
	 *
	 * <p>아레나 <b>안팎</b>의 자리 × 72 방향 × 카드 값의 <b>1·3·100배</b> 넉백을 훑는다. 목적지가
	 * 한 번이라도 천장 밖이면 그 자리는 허공이고, 공유 체력이라 그대로 월드 삭제다.
	 */
	@Test
	void 아무리_세게_밀어도_목적지가_섬_안이다() {
		double limit = TrialEnderStorm.pushLimitRadius();
		for (double factor : new double[] {1.0, 3.0, 100.0}) {
			double wanted = TrialEnderStorm.PUSH_BLOCKS * KNOCKBACK * factor;
			for (int spoke = 0; spoke < 72; spoke++) {
				Vec3 outward = new Vec3(Math.cos(spoke * Math.PI / 36.0), 0.0,
						Math.sin(spoke * Math.PI / 36.0));
				for (int px = -48; px <= 48; px += 3) {
					for (int pz = -48; pz <= 48; pz += 3) {
						double pushed = TrialEnderStorm.pushDistance(px, pz, outward, wanted);
						assertTrue(pushed >= 0.0, "음수만큼 밀면 방향이 뒤집힌다");
						double before = Math.sqrt((double) px * px + (double) pz * pz);
						double after = Math.hypot(px + outward.x * pushed, pz + outward.z * pushed);
						assertTrue(after <= Math.max(limit, before) + 1.0E-9,
								"(" + px + ", " + pz + ") 에 선 사람이 " + before + " 에서 "
										+ after + " 로 밀려났다 — 천장은 " + limit
										+ " 다. 엔드 섬 밖은 허공이고 공유 체력이라 그대로"
										+ " 월드 삭제다");
					}
				}
			}
		}
	}

	/**
	 * ⚠ <b>여러 번 연속으로 밀려도</b> 섬 안이다.
	 *
	 * <p>넉백이 「닿아 있는 동안 계속」으로 바뀐 뒤 새로 필요해진 시험이다. 한 번이 안전해도 계속
	 * 밀리면 누적되어 조금씩 밖으로 나갈 수 있다 — 소용돌이 하나를 통과하는 데 120틱이 걸리고
	 * {@code SHOVE_INTERVAL_TICKS} 마다 미므로 <b>한 소용돌이에 열다섯 번</b>, 소용돌이가 둘이고
	 * 폭풍이 반복되므로 한 전투에서는 훨씬 여러 번이다. <b>반경을 6 으로 키우고 간격을 8 로
	 * 줄이면서 여덟 번이던 것이 열다섯 번이 됐다</b> — 아래에서 스무 번을 훑는 것이 그래서 아직
	 * 최악 위다.
	 *
	 * <p>그래서 목적지를 도로 출발점에 넣어 <b>스무 번 연속</b>으로 민다. 누적되지 않는 것이
	 * {@code pushDistance} 가 「출발점 기준 거리」가 아니라 <b>「목적지가 천장 안」</b>으로
	 * 자르기 때문이라는 것을 여기서 확인한다.
	 */
	@Test
	void 여러_번_연속으로_밀어도_섬_안이다() {
		double limit = TrialEnderStorm.pushLimitRadius();
		double wanted = TrialEnderStorm.PUSH_BLOCKS * KNOCKBACK;
		for (int spoke = 0; spoke < 36; spoke++) {
			Vec3 outward = new Vec3(Math.cos(spoke * Math.PI / 18.0), 0.0,
					Math.sin(spoke * Math.PI / 18.0));
			for (int px = -45; px <= 45; px += 5) {
				for (int pz = -45; pz <= 45; pz += 5) {
					double x = px;
					double z = pz;
					double start = Math.hypot(x, z);
					for (int shove = 1; shove <= 20; shove++) {
						double pushed = TrialEnderStorm.pushDistance(x, z, outward, wanted);
						x += outward.x * pushed;
						z += outward.z * pushed;
						assertTrue(Math.hypot(x, z) <= Math.max(limit, start) + 1.0E-9,
								"(" + px + ", " + pz + ") 에서 " + shove + "번째로 밀린 뒤"
										+ " 중앙에서 " + Math.hypot(x, z) + " 다 — 천장 "
										+ limit + " 을 넘었다. 계속 미는 카드라 한 번만"
										+ " 안전해서는 안 된다");
					}
				}
			}
		}
	}

	/**
	 * 밀리는 <b>도중</b>도 안전하다.
	 *
	 * <p>목적지만 안이면 되는 것이 아니다. 경사에 걸리거나 다른 카드가 끼어들어 중간에 멈출 수
	 * 있으므로, 지나가는 어느 점도 천장 밖이면 안 된다. 이차식 {@code q(s)} 가 {@code [0, s⁺]}
	 * 에서 0 이하라는 것이 {@code pushDistance} 의 근거이고 그것을 여기서 확인한다.
	 */
	@Test
	void 밀리는_도중에도_천장_밖으로_나가지_않는다() {
		double limit = TrialEnderStorm.pushLimitRadius();
		for (int spoke = 0; spoke < 36; spoke++) {
			Vec3 outward = new Vec3(Math.cos(spoke * Math.PI / 18.0), 0.0,
					Math.sin(spoke * Math.PI / 18.0));
			for (int px = -30; px <= 30; px += 6) {
				for (int pz = -30; pz <= 30; pz += 6) {
					double pushed = TrialEnderStorm.pushDistance(px, pz, outward, 100.0);
					for (int tenth = 0; tenth <= 20; tenth++) {
						double along = pushed * tenth / 20.0;
						double now = Math.hypot(px + outward.x * along, pz + outward.z * along);
						assertTrue(now <= Math.max(limit, Math.hypot(px, pz)) + 1.0E-9,
								"밀리는 도중 " + now + " 까지 나갔다 — 천장은 " + limit + " 다");
					}
				}
			}
		}
	}

	/**
	 * 이미 천장 밖에 선 사람은 <b>한 칸도</b> 밀지 않는다.
	 *
	 * <p>다른 카드의 넉백이나 경사가 먼저 데려다 놓은 경우다. 거기서 또 밀면 이 카드가 남의 사고를
	 * 완성시킨다. 천장에 정확히 서 있는 사람도 같다 — 한 칸이라도 더 밀면 밖이다.
	 */
	@Test
	void 천장_밖에_선_사람은_밀지_않는다() {
		double limit = TrialEnderStorm.pushLimitRadius();
		Vec3 outward = new Vec3(1.0, 0.0, 0.0);
		assertEquals(0.0, TrialEnderStorm.pushDistance(limit, 0.0, outward, 24.0),
				"천장 위에 선 사람을 더 밀면 그 자리가 곧 천장 밖이다");
		assertEquals(0.0, TrialEnderStorm.pushDistance(limit + 0.5, 0.0, outward, 24.0),
				"이미 나가 있는 사람을 또 미는 것은 남의 사고를 완성시키는 일이다");
		assertEquals(0.0, TrialEnderStorm.pushDistance(0.0, limit + 5.0, outward, 24.0),
				"미는 방향과 상관없이, 천장 밖이면 한 칸도 밀지 않는다");
		assertEquals(0.0, TrialEnderStorm.pushDistance(0.0, 0.0, outward, 0.0),
				"카드가 0 을 적었으면 아무 일도 없다");
		assertEquals(0.0, TrialEnderStorm.pushDistance(0.0, 0.0, outward, -5.0),
				"음수는 방향을 뒤집는다 — 밀지 않는다");
	}

	/**
	 * 천장이 카드를 무력화하지는 않는다.
	 *
	 * <p>안전하게 만드는 가장 쉬운 방법은 아무도 안 미는 것이고, 그러면 사람이 고쳐 달라고 한
	 * 것이 사라진다. 중앙 쪽에 선 사람은 카드가 시킨 만큼 <b>다</b> 밀려야 한다.
	 */
	@Test
	void 여유가_있으면_카드가_시킨_만큼_다_민다() {
		double limit = TrialEnderStorm.pushLimitRadius();
		Vec3 outward = new Vec3(1.0, 0.0, 0.0);
		double wanted = TrialEnderStorm.PUSH_BLOCKS * KNOCKBACK;
		assertEquals(wanted, TrialEnderStorm.pushDistance(0.0, 0.0, outward, wanted), 1.0E-9,
				"중앙에 선 사람은 24칸을 다 밀려야 한다 — 천장은 " + limit + " 이라 여유가 있다");
		assertEquals(limit - 2.0, TrialEnderStorm.pushDistance(2.0, 0.0, outward, 100.0), 1.0E-9,
				"천장까지 남은 만큼만 밀린다");
		assertEquals(wanted, TrialEnderStorm.pushDistance(2.0, 0.0, outward, wanted), 1.0E-9,
				"천장에 닿지 않는 자리에서는 카드가 시킨 거리가 그대로 나온다");
		assertTrue(TrialEnderStorm.pushDistance(0.0, 0.0, outward, wanted) > 0.0,
				"아무도 안 밀면 「계속 밀쳐지게」가 통째로 사라진다");
	}

	/**
	 * ⚠ 천장이 <b>섬 경계보다 안쪽</b>이다.
	 *
	 * <p>경계에 딱 세우면 밀려 넘어진 자리가 곧 벼랑이라, 그 다음은 사람의 한 걸음이다.
	 */
	@Test
	void 천장은_섬_경계보다_안쪽이다() {
		assertTrue(TrialEnderStorm.PUSH_LIMIT_MARGIN > 0.0,
				"여유가 0 이면 천장이 섬 경계와 같아져 밀린 사람이 벼랑 끝에 선다");
		assertTrue(TrialEnderStorm.pushLimitRadius() < TrialRisks.ARENA_RADIUS,
				"천장이 아레나 경계 밖이면 천장이 아니다");
		assertTrue(TrialEnderStorm.pushLimitRadius() > 0.0,
				"천장이 0 이면 아무도 안 밀린다 — 사람이 고쳐 달라고 한 것이 사라진다");
		assertTrue(TrialEnderStorm.PUSH_LIMIT_MARGIN >= TrialEnderStorm.PUSH_BLOCKS,
				"여유는 「강한 넉백」 한 번 치(" + TrialEnderStorm.PUSH_BLOCKS + ")보다 넓어야"
						+ " 한다 — 다른 카드가 한 번 더 밖으로 밀어도 섬 안이어야 하고,"
						+ " 달려서 벗어나는 데 옆으로 비킬 시간만큼은 걸려야 한다");
		assertEquals(TrialRisks.ARENA_RADIUS - TrialEnderStorm.PUSH_LIMIT_MARGIN,
				TrialEnderStorm.pushLimitRadius(), 1.0E-9,
				"천장을 아레나 반경에서 뽑지 않고 숫자로 박으면 아레나가 바뀔 때 갈라진다");
	}

	/**
	 * ⚠ 미는 방향이 <b>소용돌이가 나아가는 쪽의 반대</b>다.
	 *
	 * <p>전에는 나아가는 쪽(안쪽)이었다. 부호 하나가 이 카드의 성격 전부고, 뒤집히면 빌드도
	 * 로그도 조용한 채로 사람이 고쳐 달라고 한 것이 도로 원래대로 돌아간다.
	 */
	@Test
	void 미는_방향이_폭풍이_가는_쪽의_반대다() {
		for (int spoke = 0; spoke < 8; spoke++) {
			Vec3 outward = TrialEnderStorm.outwardOf(spoke * Math.PI / 4.0, 0, 1);
			Vec3 travel = outward.scale(-1.0);
			Vec3 shove = TrialEnderStorm.shoveDirection(outward);
			assertEquals(-1.0, shove.x * travel.x + shove.z * travel.z, 1.0E-9,
					"미는 방향이 소용돌이가 나아가는 쪽과 반대가 아니다 — 안쪽으로 되돌아갔다면"
							+ " 「한번 밀쳐지면 끝」으로 돌아간 것이고, 그 사이 다른 방향이면"
							+ " 사람 좌표가 섞여 들어간 것이다");
			assertEquals(1.0, shove.length(), 1.0E-9,
					"단위 벡터가 아니면 미는 세기가 방향마다 달라진다");
			assertEquals(0.0, shove.y, "세로로 띄우면 낙하 피해가 붙고 훨씬 멀리 날아간다");
		}
	}

	/**
	 * 사람을 움직이는 길이 하나뿐이다.
	 *
	 * <p>사람과 가해자의 상대 위치로 방향을 잡는 밀기가 이 카드에 섞이면, 그 거리는 우리가 정한
	 * 것이 아니라 <b>{@code pushDistance} 의 천장을 지나쳐 간다.</b> 가장 흔한 길이 <b>가해 개체가
	 * 붙은 피해원</b>이다 — {@code LivingEntity} 가 맞는 쪽에서 스스로 밀어낸다.
	 *
	 * <p>바닐라 {@code knockback}·{@code push} 를 이름으로 막지는 못한다. 카드의 값 접근자가
	 * 하필 {@code knockback} 이고 이 파일의 도우미가 {@code pushDistance}·{@code pushVelocity} 라
	 * 상수 풀에서 구별되지 않는다. 그쪽을 지키는 것은 위의
	 * {@link #아무리_세게_밀어도_목적지가_섬_안이다} 와
	 * {@link #여러_번_연속으로_밀어도_섬_안이다} 다 — <b>어떤 길로 밀든</b> 목적지가 섬 안인지를
	 * 값에서 직접 센다.
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
				"세로 속도를 읽지 않으면 미는 순간 세로가 0 이 되어 떨어진다");
		assertTrue(bytes.contains("syncVelocity"),
				"켜지 않으면 서버 혼자 민 것이 되어 잠시 뒤 제자리로 되돌아간다");
	}

	/**
	 * ⚠⚠ <b>드래곤이 서버쪽에 쌓아 둔 세로를 이 카드가 배달하지 않는다.</b>
	 *
	 * <p>사람 말: <b>「드래곤 밀치는 패턴떄 점프하면 하늘로 날라가버림」</b>(2026-10-04).
	 *
	 * <p>이 카드는 세로를 <b>읽어서 그대로 돌려놓고</b> {@code syncVelocity} 를 켰다. 그런데 그
	 * 세로는 <b>우리가 쓴 값이 아니다</b> — 바닐라 {@code EnderDragon.knockBack} 이 날개 상자 안의
	 * 사람에게 매 틱 {@code push(…, 0.2, …)} 를 더하고({@code isSitting()} 조건은 <u>피해</u>에만
	 * 붙어 있다) 바닐라는 그것을 {@code needsSync} 로만 내보내 <b>본인에게 안 보낸다</b>.
	 * {@code syncVelocity} 는 <b>{@code sendToTrackingPlayersAndSelf}</b> 이고 보내는 것이
	 * <b>그 순간의 {@code getDeltaMovement()} 통째로</b>라, 그 한 줄이 남이 쌓아 둔 값을 배달한다.
	 *
	 * <p>⚠ 쌓는 쪽을 끊은 것({@code EnderDragonContactDamageMixin} 의 {@code knockBack} 차단)은
	 * {@code DragonLastStand.contactDamageOff()} 곧 <b>최후의 저항 전용</b>이다. 이 카드는 그 밖의
	 * 페이즈에서 도므로 <b>배달을 막는 것이 자름 하나뿐</b>이다.
	 *
	 * <p>그래서 여기서 재는 것은 <b>「우리가 무엇을 배달하는가」</b>다. 쌓이는 과정을 틱마다 굴려
	 * 보고, 미는 틱에 나가는 세로가 <b>사람이 실제로 올라간 만큼을 넘지 않는지</b> 본다.
	 */
	@Test
	void 쌓인_세로를_배달하지_않는다() {
		// ① 자름이 실제로 배선돼 있다. 순수 함수 시험은 함수를 안 부르면 아무것도 못 잡는다.
		String bytes = classBytes();
		assertTrue(bytes.contains("syncedVertical"),
				"세로를 읽은 그대로 내려보내고 있다 — 남이 쌓아 둔 값이 그 한 줄로 배달된다");
		assertTrue(bytes.contains("com/sharedfate/sync/TrialVelocity"),
				"자름을 이 파일에 따로 적으면 두 벌이 되어 언젠가 한쪽만 고쳐진다");
		assertTrue(bytes.contains("getKnownMovement"),
				"천장이 없다 — deltaMovement 만 보면 남이 쌓아 둔 값과 사람 몫을 가를 수 없다");
		assertFalse(bytes.contains("knockBack"),
				"이 카드가 바닐라 넉백에 손대면 안 된다 — 일반 전투의 날개 밀치기는 바닐라 동작이다");

		// ② 고치기 전에 무엇이 나갔는지 못박는다. 가만히 선 사람 — 클라이언트가 보고하는 세로가
		//   0 이다. 굴리는 길이를 DragonPerch.HOLD_TICKS(160) 로 잡은 것은 2026-10-04 에 재어 둔
		//   가장 나쁜 창이 그것이기 때문이다 — 착지는 드래곤을 포디움에 160틱 붙박아 두고, 그
		//   동안 이 카드가 터지면 그 창에서 쌓인 값이 그대로 내려간다. 끊기지 않는 까닭은
		//   EnderDragonPerchRangedImmunityMixin 이 hurt 를 끊어 hurtTime 이 안 올라가는 것이다.
		double dragonPush = 0.20000000298023224;
		double server = 0.0;
		double worstShipped = 0.0;
		for (int tick = 0; tick < DragonPerch.HOLD_TICKS; tick++) {
			server = (server + dragonPush - DragonLastStandPatterns.LIFT_GRAVITY)
					* DragonLastStandPatterns.LIFT_DRAG;
			if (tick % TrialEnderStorm.SHOVE_INTERVAL_TICKS != 0) {
				continue;
			}
			worstShipped = Math.max(worstShipped, TrialVelocity.syncedVertical(server, 0.0));
		}
		assertEquals(5.648, server, 0.001,
				"쌓이는 식이 달라졌으면 위 설명도 고칠 것 — (v + 0.2 − 0.08) × 0.98 이다");
		assertEquals(109.3, DragonLastStandPatterns.liftApex(server), 0.1,
				"그 속도의 도달 높이가 109칸이다 — 「하늘로 날라가버림」이 그것이다");

		// ③ 고친 뒤에는 한 톨도 안 나간다. 서 있는 사람은 올라가고 있지 않으므로 천장이 0 이다.
		assertEquals(0.0, worstShipped, 1.0E-12,
				"올라가고 있지 않은 사람에게 올라가는 속도를 보내면 그것이 곧 「하늘로 날아간다」다");
	}

	/**
	 * ⚠⚠ <b>정상 플레이어는 비트 단위로 무변화다.</b>
	 *
	 * <p>「점프하면 안 밀린다」로 만들지 않은 것과 같은 이유로 <b>「점프하면 점프가 죽는다」로도
	 * 만들지 않는다</b> — 그러면 정답이 「소용돌이가 올 때는 뛰지 말라」가 된다. 그리고 떨어지는
	 * 사람을 더 세게 떨어뜨리면 그것이 <b>새 낙사 장치</b>다.
	 *
	 * <p>∆ 이 아니라 {@code 0.0} 오차로 잰다 — 「거의 같다」가 아니라 <b>같은 비트</b>여야 한다.
	 */
	@Test
	void 스스로_뛴_사람과_떨어지는_사람은_비트_단위로_안_달라진다() {
		// ① 바닐라 점프. 처음 0.42 로 25틱을 굴린다. 스스로 올라가는 속도가 곧 제 천장이다.
		double rise = 0.42;
		for (int tick = 0; tick < 25; tick++) {
			assertEquals(rise, TrialVelocity.syncedVertical(rise, rise), 0.0,
					tick + "틱째 점프 세로가 달라졌다 — 점프가 손해가 된다");
			rise = (rise - DragonLastStandPatterns.LIFT_GRAVITY)
					* DragonLastStandPatterns.LIFT_DRAG;
		}

		// ② 떨어지는 중. 클라이언트가 무엇을 보고했든 받은 값이 그대로 나간다.
		for (double client : new double[] {-9.0, -1.0, -0.0784, 0.0, 0.42, 1.0, 1.0E9}) {
			for (double fall = -5.0; fall <= 0.0; fall += 0.001) {
				assertEquals(fall, TrialVelocity.syncedVertical(fall, client), 0.0,
						"떨어지는 중인 사람의 세로가 달라졌다 — 그 한 줄이 새 낙사 장치가 된다");
			}
		}
	}

	/**
	 * ⚠ <b>세로를 자르는 것이 수평 천장을 한 톨도 안 건드렸다.</b>
	 *
	 * <p>{@link #미는_세기가_이_판의_강한_넉백과_같다} 가 세기를 재고 이 시험은 <b>천장의 성질</b>을
	 * 잰다 — {@link TrialEnderStorm#pushVelocity} 는 <b>공중 모델</b>이라 사람이 떠 있어도 적힌
	 * 거리를 <b>넘을 수 없다.</b> 곧 쌓인 세로가 배달돼 사람이 들렸더라도 {@code pushDistance} 의
	 * 천장이 무너지지는 않았고, 세로를 자르는 것은 <b>「하늘로 솟는 것」을 막는 일</b>이다.
	 * 둘을 섞어 적으면 다음 사람이 한쪽을 고치고 다른 쪽이 고쳐졌다고 믿는다.
	 */
	@Test
	void 세로를_잘라도_수평_천장의_성질이_그대로다() {
		double furthest = TrialEnderStorm.PUSH_BLOCKS * 3.0;
		for (double distance = 0.0; distance <= furthest; distance += 0.25) {
			double travel = TrialEnderStorm.pushVelocity(distance)
					/ (1.0 - TrialEnderStorm.AIR_DRAG);
			assertEquals(distance, travel, 1.0E-9,
					"떠 있는 채로 끝까지 밀려도 적힌 거리에서 멈춰야 한다: " + distance);
		}
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

	// -------------------------------------------------- 피해는 한 번, 넉백은 계속

	/**
	 * ⚠ <b>넉백이 피해와 같은 명단에 걸리면 안 된다.</b>
	 *
	 * <p>사람이 고쳐 달라고 한 것이 이것이다 — 「한번 밀쳐지면 끝」. 넉백을 {@code swept} 에 도로
	 * 넣으면 그 상태로 돌아간다. 반대로 피해를 명단에서 빼면 80틱 통과에서 160 이 들어가 즉사다.
	 *
	 * <p>여기서는 <b>간격</b>을 값으로 센다. 통과 시간(120틱) 안에 여러 번 들어가야 「계속」이고,
	 * 매 틱은 아니어야 사람이 조작을 할 수 있다.
	 *
	 * <h2>⚠ 0.4초가 하한이 아니다 — 하한은 6틱이다</h2>
	 *
	 * <p>사람이 처음 <b>0.2초(4틱)</b>를 말했다가 「그러면 조작이 아예 안 된다」를 듣고 0.4초로
	 * 물러섰다. 「조금 더 자주」는 언제든 다시 나올 요청이라, <b>그때 멈춰 세우는 것이 이
	 * 시험</b>이다.
	 */
	@Test
	void 닿아_있는_동안_여러_번_밀린다() {
		double perTick = TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND);
		double crossing = TrialEnderStorm.VORTEX_RADIUS * 2.0 / perTick;
		int interval = TrialEnderStorm.SHOVE_INTERVAL_TICKS;
		assertEquals(8, interval, "사람이 「0.5초말고 일단 0.4초로해봐」라고 정한 값이다");
		assertTrue(interval > 1,
				"매 틱 밀면 사람이 조작을 아예 못 한다 — 이 카드가 요구하는 「옆으로 빠져나가기」를"
						+ " 할 수 없는 카드가 된다");
		assertTrue(crossing / interval >= 4.0,
				"통과하는 " + crossing + "틱 동안 " + (crossing / interval) + "번밖에 안 밀린다 —"
						+ " 「닿아 있는 동안 계속」이라 하기 어렵다");

		// 한 번 밀린 몸은 바닥 마찰 0.546 으로 다섯 틱이면 이동량의 95% 를 쓴다. 그보다 자주 밀면
		// 앞의 밀림이 살아 있는 채로 덮어써 속도가 끊기지 않고, 그것이 곧 조작 불능이다.
		double leftAfterFive = Math.pow(0.546, 5);
		assertTrue(leftAfterFive < 0.05, "0.546⁵ = " + leftAfterFive);
		assertEquals(6, TrialEnderStorm.SHOVE_MIN_INTERVAL_TICKS,
				"마찰이 가라앉는 5틱 바로 위다 — 5 이하면 밀림이 겹쳐 속도가 한 번도 안 끊긴다");
		assertTrue(interval >= TrialEnderStorm.SHOVE_MIN_INTERVAL_TICKS,
				interval + "틱은 하한(" + TrialEnderStorm.SHOVE_MIN_INTERVAL_TICKS + ") 아래다."
						+ " 사람이 처음 말한 0.2초(4틱)가 그 자리였고, 거기서는 앞의 밀림이 아직"
						+ " 살아 있는 채로 덮어써 조작이 아예 안 된다");

		// 8 = 마찰이 가라앉는 5틱 + 반응 여유 3틱. 전에는 10 = 5 + 5 였고, 그 뒤쪽 5 의 근거가
		// 「사람의 지각·판단·입력에 0.25초」였다. 8 로 줄면서 그 여유가 3틱(0.15초)으로 깎였다 —
		// 사람이 대가를 알고 고른 값이라 사실대로 세어 둔다.
		int frictionTicks = 5;
		int reaction = interval - frictionTicks;
		assertEquals(3, reaction,
				"반응 여유가 3틱이 아니다. 간격을 만졌으면 상수 설명의 「5 + 3」도 함께 고칠 것");
		assertTrue(reaction < TrialEnderPulse.JUMP_WINDOW_TICKS,
				"반응 여유가 " + reaction + "틱이라 사람이 한 번 반응하는 데 드는 "
						+ TrialEnderPulse.JUMP_WINDOW_TICKS + "틱보다 짧다. 이것이 0.4초의 대가고,"
						+ " 여기가 뒤집혔다면 간격이 10 으로 되돌아간 것이니 상수 설명도 되돌릴 것");
		assertTrue(reaction > 0, "반응 여유가 0 이면 밀림이 멎는 그 틱에 다시 밀린다");
	}

	/**
	 * ⚠ <b>반경을 6 으로 키우면서 함께 움직인 것들.</b>
	 *
	 * <p>사람이 「폭풍크기는 50프로 키워도 좋을거같아」라고 한 것은 크기 하나지만, 이 카드에서
	 * 반경은 <b>통과 시간과 밀리는 횟수를 함께 끌고 온다.</b> 그 셋이 어긋나면 클래스 설명과
	 * 실제가 갈라지므로 값에서 직접 센다.
	 */
	@Test
	void 반경_6_이_통과_시간과_밀리는_횟수를_끌고_온다() {
		assertEquals(6.0, TrialEnderStorm.VORTEX_RADIUS,
				"사람이 4 에서 50% 키우라고 했다 — 여기를 되돌리면 아래 숫자가 전부 어긋난다");

		double perTick = TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND);
		double crossing = TrialEnderStorm.VORTEX_RADIUS * 2.0 / perTick;
		assertEquals(120.0, crossing, 1.0E-9, "지름 12칸을 초당 2칸으로 — 120틱이다(전에는 80)");

		int shoves = (int) (crossing / TrialEnderStorm.SHOVE_INTERVAL_TICKS);
		assertEquals(15, shoves,
				"가만히 서 있으면 " + shoves + "번 밀린다. 전에는 80틱을 10틱마다라 여덟 번이었다 —"
						+ " 클래스 설명의 숫자와 다르면 한쪽만 고쳐진 것이다");

		// 밀리는 횟수가 늘어도 섬 밖으로는 못 나간다. 그 증명은 위의 연속 넉백 시험이 한다.
		assertTrue(shoves < 20, "위의 「여러 번 연속으로 밀어도 섬 안이다」가 스무 번을 훑는다 —"
				+ " 실제 횟수가 그보다 많아지면 그 시험이 더 이상 최악을 보지 않는다");
	}

	/** 처음 닿은 틱에 밀고, 그 뒤로는 간격마다 민다. */
	@Test
	void 미는_간격을_틱으로_센다() {
		int interval = TrialEnderStorm.SHOVE_INTERVAL_TICKS;
		assertTrue(TrialEnderStorm.dueToShove(null, 100L),
				"처음 닿은 틱에 안 밀면 「닿으면 밀린다」가 아니다");
		assertFalse(TrialEnderStorm.dueToShove(100L, 100L), "같은 틱에 두 번 밀지 않는다");
		assertFalse(TrialEnderStorm.dueToShove(100L, 100L + interval - 1),
				"간격이 차기 전에 밀면 앞의 밀림을 덮어써 속도가 끊기지 않는다");
		assertTrue(TrialEnderStorm.dueToShove(100L, 100L + interval), "간격이 차면 다시 민다");
		assertTrue(TrialEnderStorm.dueToShove(100L, 50L),
				"시간이 되감긴 판에서 그 사람만 영영 넉백에서 빠지면 조용한 고장이다");
	}

	/**
	 * 통과하는 데 여러 틱이 걸린다 — 그래서 <b>피해</b> 명단이 없으면 즉사한다.
	 *
	 * <p>지름 12칸을 초당 2칸으로 지나가므로 120틱이다. 매 틱 2씩 들어가면 240 이고 팀 공유
	 * 체력은 20 이다. 반경을 4 에서 6 으로 키우면서 이 값이 160 에서 240 으로 늘었다 —
	 * <b>명단이 하는 일이 그만큼 커졌다.</b> 이 시험은 그 숫자를 값에서 직접 세어, 「무적시간이
	 * 알아서 걸러 주겠지」로 명단을 지우는 사람 앞에 세워 둔다.
	 */
	@Test
	void 명단이_없으면_통과하는_동안_즉사한다() {
		double perTick = TrialEnderStorm.blocksPerTick(SPEED_PER_SECOND);
		double crossing = TrialEnderStorm.VORTEX_RADIUS * 2.0 / perTick;
		assertEquals(120.0, crossing, 1.0E-9, "지름 12칸을 초당 2칸으로 — 120틱이다");
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

	/**
	 * 공용 경고를 쓰되 <b>사람마다 보내는</b> 쪽이어야 한다.
	 *
	 * <p>자리에 놓는 {@code TrialWarning.sound} 가 아니라 {@code TrialWarning.soundFor} 다.
	 * 소용돌이가 닿는 순간이 사람마다 다른 카드라, 자리에 놓으면 반경 안의 전원에게 나가
	 * <b>남의 경고까지 들리고 겹쳐 들린다.</b> 서술자를 바꿔 적은 이유가 그것이다 — 「공용 경고를
	 * 쓴다」는 뜻은 그대로다.
	 */
	@Test
	void 경고_소리는_공용_경고를_쓴다() {
		assertTrue(classBytes().contains("(Lnet/minecraft/server/level/ServerLevel;"
						+ "Lnet/minecraft/server/level/ServerPlayer;"
						+ "Lcom/sharedfate/sync/TrialWarning$Stage;)V"),
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
		assertEquals(232, points,
				"반경을 4 에서 6 으로 키우면서 바닥 고리가 51 에서 76 이 되어 소용돌이당 116점,"
						+ " 둘이 232 다(전에는 182). 여기가 달라졌으면 반경이나 기둥 점 수가"
						+ " 바뀐 것이니 상수 설명도 함께 고칠 것");
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
