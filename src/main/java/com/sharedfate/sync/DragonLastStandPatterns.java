package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 「최후의 저항」의 <b>패턴 셋</b>. {@code DragonLastStand.runPattern} 이 여기로 넘긴다.
 *
 * <p>고르는 쪽({@code DragonLastStand.allowed}·{@code pick}·{@code restTicks})은 그쪽에 있고
 * 여기는 <b>그리는 쪽</b>만 있다. 파일을 뗀 것은 {@code DragonFireBarrage} 와 같은 이유다 —
 * 붙박이 드래곤·진입 연출·처치 처리가 이미 1200줄이라, 패턴 셋을 그 안에 얹으면 「왜 드래곤이
 * 안 앉나」와 「왜 브레스가 빗나가나」를 같은 파일에서 찾게 된다.
 *
 * <h2>드래곤에게서 <b>읽기만</b> 한다 — 손잡이는 {@code setYRot} 하나다</h2>
 *
 * <p>{@code DragonLastStand.hold} 가 매 틱 좌표를 못박아 바닐라의 회전 가지를 아예 죽여 두었다.
 * 그래서 yRot 이 전적으로 우리 것이고, 부채꼴 브레스의 「머리 고정」이 {@code dragon.setYRot}
 * <b>한 줄</b>이다. <b>위치를 옮기지 말 것</b>이고 {@code setPhase} 를 부르지 말 것 —
 * {@code DragonLastStand} 클래스 설명에 그 판단의 근거가 길게 붙어 있다.
 *
 * <h2>지키는 것</h2>
 *
 * <ul>
 *   <li><b>파티클은 긴 형식</b>({@code sendParticles(type, true, false, …)}). 짧은 형식은 32칸에서
 *       잘리고 아레나는 80칸이다</li>
 *   <li><b>소리는 {@link TrialWarning#playEach} / {@link TrialWarning#soundFor}.</b> 팀원 루프에서
 *       {@code level.playSound} 를 부르면 사람 수만큼 겹친다 — 최근에 열일곱 자리를 고친 함정이다</li>
 *   <li><b>자막을 쓰지 않는다.</b> 신호는 소리와 바닥 표식 둘뿐이다</li>
 *   <li><b>{@code level.getGameTime()} 을 읽지 않는다.</b> 받은 {@code now} 만 쓴다</li>
 *   <li><b>블록을 한 칸도 건드리지 않는다.</b> 바닥을 지우면 공허 낙사이고 그것이 이 전투가
 *       유일하게 금지한 「대응 불가 즉사」다. 하이트맵을 <b>읽는</b> 것만 한다</li>
 *   <li><b>피해원에 실체를 달지 않는다.</b> 실체가 붙으면 {@code LivingEntity} 가 스스로 밀어내고,
 *       그 밀기는 우리 천장을 통째로 지나쳐 간다</li>
 * </ul>
 *
 * <h2>한 틱 점 예산</h2>
 *
 * <p>{@code TrialLandingShock.MAX_POINTS_PER_TICK} 이 440 이고 그것이 이 판의 예산이다. 세 패턴은
 * <b>동시에 돌지 않으므로</b>({@code DragonLastStand.advance} 가 한 번에 하나만 돌린다) 가장 바쁜
 * 틱은 한 패턴의 가장 바쁜 틱이다. {@link #worstCasePointsPerTick} 이 값에서 직접 세고
 * {@code DragonLastStandPatternsTest} 가 그 수를 예산과 견준다.
 */
public final class DragonLastStandPatterns {

	// ------------------------------------------------------------------ ① 날개 퍼덕이기

	/** 충격파 횟수. <b>사람이 정한 값이다.</b> */
	static final int WING_PULSES = 8;
	/** 충격파 간격(틱). 0.6초. <b>사람이 정한 값이다.</b> */
	static final int WING_PULSE_TICKS = 12;

	/**
	 * 약한 구간의 끝(칸). 드래곤 바로 아래다. <b>사람이 정한 값이다.</b>
	 *
	 * <p>여기 안쪽이 약한 것이 이 패턴의 뜻이다 — 머리에 붙어 때리는 사람을 떼어 내는 것이
	 * 목적이 아니라 <b>붙기를 방해하되 쫓아내지는 않는</b> 것이다.
	 */
	static final double WING_NEAR_RADIUS = 4.0;
	/** 가장 강한 구간의 끝(칸). <b>사람이 정한 값이다.</b> 4~12칸이 가장 강하다. */
	static final double WING_STRONG_RADIUS = 12.0;
	/**
	 * 세기가 0 으로 잦아드는 거리(칸).
	 *
	 * <p><b>사람이 정하지 않았다.</b> 문서에 적힌 것은 「4칸 이내는 약하고 4~12칸이 가장 강하다」
	 * 뿐이고 12칸 밖은 말이 없다. 절벽처럼 끊으면 12.0 과 12.1 에 선 두 사람의 결과가 완전히
	 * 갈리므로 잦아들게 두었고, 20 인 것은 <b>부채꼴 브레스의 사거리와 같은 값</b>이다 — 이
	 * 페이즈가 「드래곤이 닿는 거리」로 이미 쓰는 숫자를 새로 만들지 않았다.
	 */
	static final double WING_FADE_RADIUS = 20.0;

	/**
	 * 가장 강한 구간에서 <b>부탁하는</b> 미는 거리(칸).
	 *
	 * <h2>사람이 정하지 않은 숫자다 — 왜 4 인가</h2>
	 *
	 * <p>이 저장소가 「강한 넉백」으로 쓰는 단위는 {@code TrialEnderStorm.PUSH_BLOCKS}(8)이고,
	 * 「착지 충격」·「엔더폭풍」이 그 값을 쓴다. 그런데 이 패턴은 <b>피해가 0 인데 5초 동안 여덟
	 * 번</b> 민다. 한 번치가 「강한 넉백」 한 대와 같으면 5초 내내 조작이 덮어써져 <b>요구하는
	 * 행동(비켜서 붙기)을 할 수 없는 패턴</b>이 된다. 그래서 그 단위의 <b>절반</b>으로 잡았다.
	 *
	 * <p>실제로 밀리는 거리는 이보다 훨씬 짧다 — {@code TrialEnderStorm.pushVelocity} 가 공중
	 * 감쇠로 속도를 잡으므로 발이 땅에 붙어 있으면 바닥 마찰(0.546)이 먼저 먹어 <b>5분의 1
	 * 남짓</b>이다. 적힌 값은 <b>천장</b>이다.
	 */
	static final double WING_PUSH_BLOCKS = TrialEnderStorm.PUSH_BLOCKS / 2.0;
	/**
	 * 드래곤 바로 아래에서 부탁하는 미는 거리(칸).
	 *
	 * <p>0 이 아닌 것은 「약하다」이지 「없다」가 아니기 때문이다. 0 으로 두면 머리 밑이
	 * <b>완전한 안전지대</b>가 되어 이 패턴이 아무것도 요구하지 않는다.
	 */
	static final double WING_NEAR_PUSH_BLOCKS = 1.5;

	// ------------------------------------------------------------------ ② 부채꼴 브레스

	/** 예고(틱). 5초. <b>사람이 정한 값이고 「즉사 허용」의 조건 셋 중 하나다.</b> */
	static final int CONE_WARN_TICKS = 100;
	/**
	 * 터진 뒤 불꽃이 남는 시간(틱). 1초.
	 *
	 * <p>⚠ <b>잔류가 아니다.</b> 피해는 터지는 그 한 틱에 한 번만 들어가고 이 1초는 파티클뿐이다 —
	 * 「잔류 없음」이 사람이 정한 것이고, 그 1초 동안 부채꼴 안에 들어가도 아무 일이 없다.
	 * 남긴 까닭은 「무엇이 방금 지나갔는가」가 안 보이면 다음번에 배울 것이 없어서다.
	 */
	static final int CONE_AFTERGLOW_TICKS = 20;
	/** 부채꼴의 각도. <b>사람이 정한 값이고 「즉사 허용」의 조건 셋 중 하나다</b>(옆으로 빠질 곳이 남는다). */
	static final double CONE_DEGREES = 90.0;
	/** 사거리(칸). <b>사람이 정한 값이다.</b> */
	static final double CONE_RANGE = 20.0;

	// ------------------------------------------------------------------ ③ 번개 5개

	/** 번개 개수. <b>사람이 정한 값이다.</b> */
	static final int LIGHTNING_COUNT = 5;
	/**
	 * 한 곳의 반경(칸). <b>사람이 정한 값이다</b> — 「낙뢰」의 3칸보다 15% 작다.
	 *
	 * <p>{@code TrialCatalog} 의 {@code sharedfate:lightning_storm} 이 3.0 을 들고 있고
	 * {@code 3.0 × 0.85 = 2.55} 다. {@code DragonLastStandPatternsTest} 가 그 관계를 붙든다.
	 */
	static final double LIGHTNING_RADIUS = 2.55;
	/**
	 * 바깥 넷을 놓는 거리(칸).
	 *
	 * <h2>이 값이 「다섯이 겹치지 않는다」를 증명한다</h2>
	 *
	 * <p>가운데 하나 + 90도씩 벌린 넷이다. 지켜야 할 최소 간격은
	 * {@code TrialRisks.spotMinGap(2.55) = 5.1} 칸이고, 이 배치에서 실제 간격은 셋뿐이다.
	 *
	 * <table border="1">
	 *   <caption>6.0 에서의 간격</caption>
	 *   <tr><th>쌍</th><th>거리</th><th>5.1 보다 먼가</th></tr>
	 *   <tr><td>가운데 ↔ 바깥</td><td>6.00</td><td>그렇다</td></tr>
	 *   <tr><td>이웃한 바깥 둘(90도)</td><td>8.49</td><td>그렇다</td></tr>
	 *   <tr><td>마주 보는 바깥 둘</td><td>12.00</td><td>그렇다</td></tr>
	 * </table>
	 *
	 * <p>그리고 가장 먼 점이 중앙에서 {@code 6.0 + 2.55 = 8.55} 칸이라 <b>마지막 안전지대(반경
	 * 12)의 내접원 안</b>이다. 보더는 정사각형이라 모서리가 17칸이지만 내접원으로 재야 안전하다.
	 *
	 * <p>⚠ 하한은 <b>4.34</b> 다(이웃 간격 {@code 2R sin45° > 5.1}). 상한은 <b>9.45</b> 다
	 * ({@code R + 2.55 ≤ 12}). 6.0 은 그 사이에서 가운데 하나까지 넉넉히 떨어지는 값이고,
	 * {@code DragonLastStandPatternsTest} 가 열 쌍을 전부 재어 본다.
	 */
	static final double LIGHTNING_RING_RADIUS = 6.0;
	/** 예고(틱). 3초. {@code TrialWarning.TICKS_SIDESTEP}(30틱, 옆으로 비킬 시간)의 두 배다. */
	static final int LIGHTNING_WARN_TICKS = 60;
	/** 내리친 뒤 패턴이 남아 있는 시간(틱). 번개 엔티티가 스스로 그리는 동안이다. */
	static final int LIGHTNING_AFTER_TICKS = 10;

	// ------------------------------------------------------------------ 그리는 값

	/** 바닥 표식의 점 간격(칸). {@code TrialWarning.POINT_GAP} 과 같다. */
	private static final double MARK_GAP = TrialWarning.POINT_GAP;
	/** 부채꼴 가장자리에 흰 벽을 세울 때의 점 간격(칸). 바닥 선보다 성기게 찍어 예산을 아낀다. */
	private static final double EDGE_WALL_GAP = 1.0;
	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다. */
	private static final double GROUND_OFFSET = 0.15;
	/** 부채꼴 안에 하나 더 그리는 호의 반경 비율. 「어디까지가 20칸인가」를 눈이 가늠하게 한다. */
	private static final double CONE_MID_ARC = 0.5;

	// ------------------------------------------------------------------ 한 판 동안 붙잡아 두는 것

	/**
	 * 부채꼴 브레스가 고정한 방향(도)과 그것을 고른 판.
	 *
	 * <p>⚠ <b>예고 중에 조준이 바뀌지 않는 것이 「즉사 허용」의 조건 셋 중 하나다.</b> 매 틱 다시
	 * 재면 사람이 옆으로 빠져도 부채꼴이 따라와 5초 예고가 아무 뜻이 없다.
	 *
	 * <p>한 칸뿐인 것은 패턴이 <b>한 번에 하나만</b> 돌기 때문이다
	 * ({@code DragonLastStand.advance}). 판을 가리키는 것은 시작 틱이고, 그것이 달라지면 새 판이라
	 * 다시 고른다.
	 */
	private static long coneAimedFor = Long.MIN_VALUE;
	private static float coneYaw;

	/** 번개 다섯 곳과 그것을 고른 판. 예고가 도는 동안 자리가 움직이면 예고가 아니다. */
	private static long lightningPickedFor = Long.MIN_VALUE;
	private static List<Vec3> lightningSpots = List.of();

	private DragonLastStandPatterns() {
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 지난 판의 조준과 자리가 새 판으로 새지 않게 한다. */
	static void clearState() {
		coneAimedFor = Long.MIN_VALUE;
		coneYaw = 0.0F;
		lightningPickedFor = Long.MIN_VALUE;
		lightningSpots = List.of();
	}

	/**
	 * 고른 패턴을 그린다. 시작 틱과 끝나는 틱 사이 <b>매 틱</b> 불린다.
	 *
	 * @param at 이 패턴이 시작한 틱
	 */
	static void run(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			DragonLastStand.Pattern pattern, long at, long now) {
		long step = now - at;
		if (step < 0L) {
			return;
		}
		// default 를 넣지 말 것. 패턴을 넷째로 늘리고 그리는 것을 안 붙이면 여기서 빌드가 깨져야
		// 한다 — 안 깨지면 드래곤이 그 패턴을 고른 동안 아무것도 안 한다.
		switch (pattern) {
			case WING_BEAT -> wingBeat(end, dragon, members, at, now, (int) step);
			case CONE_BREATH -> coneBreath(end, dragon, members, at, now, (int) step);
			case LIGHTNING_FIVE -> lightningFive(end, dragon, members, at, now, (int) step);
		}
	}

	// ------------------------------------------------------------------ ① 날개 퍼덕이기

	/**
	 * 날개 퍼덕이기 — 5초간 0.6초마다 충격파 여덟 번. <b>넉백만, 피해 없음.</b>
	 *
	 * <h2>⚠ 천장이 둘이다 — 하나라도 빼면 낙사 장치다</h2>
	 *
	 * <p>공유 체력이라 한 사람의 낙사가 팀 전체를 끝내고 그것이 곧 월드 삭제다. 그래서 미는
	 * 거리를 <b>두 번</b> 자른다. 둘 다 이 저장소가 이미 쓰는 함수를 <b>그대로 부른다</b> —
	 * 여기서 다시 짜면 두 벌이 되어 언젠가 한쪽만 고쳐진다.
	 *
	 * <ol>
	 *   <li>{@code TrialEnderStorm.pushDistance} — <b>목적지가 반경 32칸 안</b>인 만큼만 돌려준다
	 *       ({@code pushLimitRadius() = ARENA_RADIUS 40 − PUSH_LIMIT_MARGIN 8}). 어떤 세기를
	 *       넣어도, 몇 번을 연달아 밀려도, 밀리는 도중의 어느 점도 그 안이다 — 증명이 그 메서드에
	 *       적혀 있다</li>
	 *   <li>{@code TrialLandingShock.groundedReach} — 그 길을 반 칸씩 짚어 <b>땅이 끊기기 전</b>
	 *       에서 한 번 더 자른다. 중앙 섬은 둥글지 않아 반경 32 안에도 허공이 있고, 사람이 파 놓은
	 *       구멍도 있다</li>
	 * </ol>
	 *
	 * <p>⚠ <b>안전지대의 파란 벽에 기대지 않는다.</b> 벽은 「안에 있고 벽에서 2칸 안」일 때만
	 * 막으므로(26.3 {@code Entity.collide} 의 {@code isInsideCloseToBorder}) 정사각형 보더의
	 * <b>대각선 쪽</b>에서는 아무것도 막지 않는다 — 반변 42 짜리 사각형의 모서리는 중앙에서
	 * 59칸이다. 벽이 하는 일은 「대개 한 번 더 막아 준다」이고, 안전은 위의 둘이 지킨다.
	 *
	 * <h2>세로로 한 칸도 띄우지 않는다</h2>
	 *
	 * <p>띄우면 바닥 마찰이 안 먹어 적힌 거리를 끝까지 날아가고 낙하 피해도 붙는다. 「착지 충격」·
	 * 「엔더폭풍」이 같은 이유로 세로 속도를 읽어서 그대로 돌려놓는다.
	 */
	private static void wingBeat(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			long at, long now, int step) {
		Vec3 center = dragon.position();
		// 세기 지도를 바닥에 그린다. 파랑은 규약의 「밀려난다」다 — 4칸 고리 안은 약하고
		// 4~12칸 사이가 가장 강하다는 것을 두 고리가 그대로 말한다.
		ParticleOptions shove = TrialWarning.dust(TrialWarning.Colors.SHOVE);
		TrialWarning.markGround(end, center, WING_NEAR_RADIUS, shove);
		TrialWarning.markGround(end, center, WING_STRONG_RADIUS, shove);
		if (step % WING_PULSE_TICKS != 0 || step / WING_PULSE_TICKS >= WING_PULSES) {
			return;
		}
		// 사람마다 그 자리에서 정확히 한 번 울린다. 팀원 루프에서 level.playSound 를 부르면
		// 모여 있는 넷이 각자 네 겹으로 듣는다.
		TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_FLAP, 1.0F, 0.9F);
		end.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, true, false,
				center.x, center.y + 1.0, center.z, 1, 0.0, 0.0, 0.0, 0.0);
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			shove(end, ground, member, center, at, now);
		}
	}

	/** 한 사람을 바깥으로 민다. 천장 둘을 지난 뒤에만 실제로 민다. */
	private static void shove(ServerLevel end, TrialEnderPulse.Ground ground, ServerPlayer member,
			Vec3 center, long at, long now) {
		double dx = member.getX() - center.x;
		double dz = member.getZ() - center.z;
		double from = Math.sqrt(dx * dx + dz * dz);
		double wanted = wingPushBlocks(from);
		if (!(wanted > 0.0) || !(from > 1.0E-4)) {
			// 드래곤과 정확히 겹쳐 있으면 「바깥쪽」이 없다. 방향을 지어내지 않는다.
			return;
		}
		double stepX = dx / from;
		double stepZ = dz / from;
		Vec3 outward = new Vec3(stepX, 0.0, stepZ);
		double distance = TrialEnderStorm.pushDistance(member.getX(), member.getZ(), outward, wanted);
		distance = TrialLandingShock.groundedReach(
				(x, z) -> ground.surfaceAt(end, x, z) != TrialEnderPulse.NO_GROUND,
				member.getX(), member.getZ(), stepX, stepZ, distance);
		if (!(distance > 0.0)) {
			return;
		}
		double speed = TrialEnderStorm.pushVelocity(distance);
		Vec3 motion = member.getDeltaMovement();
		// 세로 속도는 읽어서 그대로 돌려놓는다. 더하지 않고 덮어쓰는 것은 들고 있던 수평
		// 속도가 얹혀 천장이 계산한 목적지를 넘지 않게 하기 위해서다.
		member.setDeltaMovement(stepX * speed, motion.y, stepZ * speed);
		// 켜지 않으면 서버 혼자 민 것이 되어 잠시 뒤 제자리로 되돌아간다.
		member.syncVelocity = true;
		// 밀린 사람은 2초 동안 안전지대 밖 피해를 안 받는다. 사람이 정한 유예다.
		DragonLastStandZone.noteShoved(member.getUUID(), at, now);
		end.sendParticles(ParticleTypes.GUST, true, false,
				member.getX(), member.getY() + 0.1, member.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
	}

	/**
	 * 그 거리에서 <b>부탁하는</b> 미는 거리(칸). 실제로 미는 거리는 천장 둘이 다시 자른다.
	 *
	 * <p>모양이 문서의 한 줄 그대로다 — <b>4칸 이내는 약하고 4~12칸이 가장 강하다.</b>
	 *
	 * <ul>
	 *   <li>{@code 0 ~ 4} — {@link #WING_NEAR_PUSH_BLOCKS} 에서 {@link #WING_PUSH_BLOCKS} 로 오른다</li>
	 *   <li>{@code 4 ~ 12} — {@link #WING_PUSH_BLOCKS} 그대로. 가장 강한 구간이다</li>
	 *   <li>{@code 12 ~ 20} — 0 으로 잦아든다. 절벽처럼 끊으면 12.0 과 12.1 의 결과가 완전히 갈린다</li>
	 *   <li>{@code 20 이상} — 0. 날개바람이 닿지 않는다</li>
	 * </ul>
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 0.1칸 간격으로 훑는다.
	 */
	static double wingPushBlocks(double distance) {
		if (!(distance > 0.0) || distance >= WING_FADE_RADIUS) {
			return 0.0;
		}
		if (distance < WING_NEAR_RADIUS) {
			double progress = distance / WING_NEAR_RADIUS;
			return WING_NEAR_PUSH_BLOCKS
					+ (WING_PUSH_BLOCKS - WING_NEAR_PUSH_BLOCKS) * progress;
		}
		if (distance <= WING_STRONG_RADIUS) {
			return WING_PUSH_BLOCKS;
		}
		double fade = (WING_FADE_RADIUS - distance) / (WING_FADE_RADIUS - WING_STRONG_RADIUS);
		return WING_PUSH_BLOCKS * fade;
	}

	// ------------------------------------------------------------------ ② 부채꼴 브레스

	/**
	 * 부채꼴 브레스 — 5초 예고 · 예고와 함께 머리 고정 · 90도 · 20칸 · 피해
	 * {@code DragonLastStand.CONE_BREATH_DAMAGE}(65) · <b>잔류 없음.</b>
	 *
	 * <h2>⚠⚠ 피해원을 {@code explosion(null, null)} 으로 골랐다</h2>
	 *
	 * <p>65 는 <b>무장하고도 한 대에 전멸</b>을 노린 값인데, 문서와
	 * {@code DragonLastStandTest.부채꼴_브레스는_피해원에_따라_전멸이_갈린다} 가 못박은 대로
	 * 답이 피해원에 따라 갈린다(팀 체력 20).
	 *
	 * <table border="1">
	 *   <caption>65 가 무장한 사람에게 실제로 들어가는 양</caption>
	 *   <tr><th>피해원</th><th>들어가는 값</th><th>한 대에 전멸인가</th></tr>
	 *   <tr><td>{@code lightningBolt()}</td><td>19.66</td><td><b>아니다</b> — 0.34 남는다</td></tr>
	 *   <tr><td><b>{@code explosion(null, null)}</b></td><td><b>29.48</b></td><td>그렇다 ← 고른 것</td></tr>
	 *   <tr><td>{@code magic()}</td><td>23.40</td><td>그렇다</td></tr>
	 * </table>
	 *
	 * <p>죽는 쪽 둘 가운데 {@code explosion} 을 고른 근거가 셋이다.
	 *
	 * <ol>
	 *   <li>⚠ <b>{@code magic} 은 {@code #bypasses_armor} 태그에 있어 방어도가 하나도 안 듣는다.</b>
	 *       이 판의 셈은 전부 「다이아 풀셋 + 보호 IV 를 지난 뒤」를 기준으로 서 있는데
	 *       ({@code GearedDamage}), 방어를 지나가는 피해원은 <b>갖춰도 줄지 않아</b> 그 전제와
	 *       성질이 다르다. 「표적」({@code TrialDragonFocus})이 그 쪽을 쓰면서 스스로
	 *       「이 카드만 방어도를 지나간다 — 적힌 6 은 다른 카드의 6 과 뜻이 다르다」라고 적어
	 *       두었다. 이 페이즈에서 그 예외를 하나 더 만들면 「무장 기준」이 카드마다 다른 말이 된다</li>
	 *   <li><b>{@code explosion} 이 이 판의 표준이다.</b> 「연쇄 포격」·「기둥 화염구」·「종말의 비」·
	 *       「착지 충격」·「엔더폭풍」이 모두 이것을 쓰고, {@code TrialEnderStorm} 이
	 *       <b>{@code magic}·{@code dragonBreath} 를 쓰지 않는 이유</b>를 같은 근거로 적어 두었다.
	 *       곧 「드래곤의 브레스니 {@code dragonBreath} 가 맞다」로 되돌리지 말 것 — 그쪽도
	 *       {@code #bypasses_armor} 다</li>
	 *   <li><b>폭발 보호가 그대로 듣는다.</b> 대비한 사람이 손해 보지 않는 쪽이고, 하드 곱(1.5)이
	 *       먼저 걸려 29.48 이라 <b>여유가 9.48</b> 이다 — 「하트 한 칸도 안 되는 차이」로
	 *       살아남는 일이 없다</li>
	 * </ol>
	 *
	 * <p>⚠ 맨몸이면 {@code 65 × 1.5 = 97.5} 가 그대로 들어간다. 「죽어서 장비를 잃고 돌아온
	 * 사람에게는 감쇠가 하나도 안 걸린다」가 이 저장소가 이미 알고 받아들인 사실이고
	 * ({@code GearedDamage} 의 「이 기준이 봐 주지 않는 사람」), 무장 기준에서 이미 한 대에
	 * 전멸이라 맨몸 쪽이 더 나빠지는 것은 없다.
	 *
	 * <h2>머리 고정은 {@code setYRot} 한 줄이다</h2>
	 *
	 * <p>{@code DragonLastStand.hold} 가 좌표를 못박아 바닐라가 yRot 을 건드리지 않으므로
	 * ({@code aiStep} 의 {@code abs(xdd) > 1e-5} 가지가 죽어 있다) 예고가 도는 동안 같은 값을
	 * 눌러 두면 그것으로 끝이다. 방향은 <b>예고 첫 틱에 한 번</b> 고르고
	 * ({@link #coneAimedFor}) 그 뒤로 다시 재지 않는다 — 다시 재면 5초 예고가 아무 뜻이 없다.
	 *
	 * <h2>부채꼴을 어떻게 그렸는가</h2>
	 *
	 * <p>바닥에 <b>빨간 테두리</b>(규약의 {@code DEADLY}, 「서 있으면 죽는다」)를 그린다 —
	 * 가장자리 두 줄 · 사거리 호 · 가운데 호 하나. 그리고 가장자리 두 줄에는
	 * <b>흰 벽</b>({@code CRIT})을 세운다. 「착지 충격」이 쓰는 그 수법이고, 파티클 개수를 0 으로
	 * 보내면 뒤 값이 속도로 읽혀 <b>점을 한 개도 안 늘리고</b> 사람 키만 한 벽이 선다
	 * ({@code TrialLandingShock.EDGE_RISE_SPEED} 에 그 계산이 있다). 부채꼴 안에 서 있는 사람은
	 * 바닥 선을 거의 못 보므로(시선과 나란하다) 이 벽이 「여기서부터 안전」을 말하는 갈래다.
	 */
	private static void coneBreath(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			long at, long now, int step) {
		Vec3 apex = dragon.position();
		if (coneAimedFor != at) {
			coneAimedFor = at;
			coneYaw = aimYaw(apex, members, dragon.getYRot());
		}
		// 매 틱 같은 값을 누른다. hold 가 좌표를 못박아 두었으므로 이 한 줄이 「머리 고정」이다.
		dragon.setYRot(coneYaw);
		if (step < CONE_WARN_TICKS) {
			warnCone(end, members, apex, step);
			return;
		}
		if (step == CONE_WARN_TICKS) {
			fireCone(end, members, apex);
			return;
		}
		// 잔류가 아니다 — 파티클만 남고 피해는 위의 한 틱에 끝났다.
		sprayCone(end, apex, 1);
	}

	/**
	 * 겨눌 방향(도). 팀의 무게 중심이다.
	 *
	 * <p>{@code DragonLastStand.faceTeam} 과 <b>같은 계산</b>이다 — 규약이 갈리면 「앉을 때는
	 * 팀을 보더니 브레스는 엉뚱한 데를 본다」가 된다. {@code aiStep} 이 yRot 을 쓰는 식이
	 * {@code (sin(yRot), −cos(yRot))} 이 앞이라 역산이 {@code atan2(dx, −dz)} 다.
	 *
	 * <p>셀 사람이 없으면 지금 보고 있는 쪽을 그대로 쓴다. 0 으로 두면 팀이 다 관전 중인 판에서
	 * 부채꼴이 언제나 북쪽을 향한다.
	 */
	private static float aimYaw(Vec3 apex, List<ServerPlayer> members, float fallback) {
		double x = 0.0;
		double z = 0.0;
		int counted = 0;
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			x += member.getX();
			z += member.getZ();
			counted++;
		}
		if (counted == 0) {
			return fallback;
		}
		double dx = x / counted - apex.x;
		double dz = z / counted - apex.z;
		if (Math.abs(dx) < 1.0E-4 && Math.abs(dz) < 1.0E-4) {
			return fallback;
		}
		return (float) Math.toDegrees(Math.atan2(dx, -dz));
	}

	/** 예고. 바닥 테두리와 흰 벽을 매 틱 다시 그리고 층이 바뀌는 틱에만 소리를 낸다. */
	private static void warnCone(ServerLevel end, List<ServerPlayer> members, Vec3 apex, int step) {
		markCone(end, apex);
		int remaining = CONE_WARN_TICKS - step;
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage != null && TrialRisks.stageJustChanged(remaining, CONE_WARN_TICKS)) {
			// 사람마다 그 사람에게만 보낸다. TrialWarning.sound 는 반경 안 전원에게 나가므로
			// 모여 있는 넷이 각자 네 겹으로 듣는다.
			for (ServerPlayer member : members) {
				TrialWarning.soundFor(end, member, stage);
			}
		}
		// 입에서 불씨가 모인다. 「어디서 나올 것인가」를 드래곤 쪽에서도 말한다.
		end.sendParticles(ParticleTypes.SMALL_FLAME, true, false,
				apex.x, apex.y + 2.0, apex.z, 4, 0.6, 0.4, 0.6, 0.01);
	}

	/** 터진다. <b>피해는 이 한 틱에 한 사람당 한 번</b>이다. */
	private static void fireCone(ServerLevel end, List<ServerPlayer> members, Vec3 apex) {
		sprayCone(end, apex, 3);
		TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_SHOOT, 1.2F, 0.7F);
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			if (!insideCone(member.getX() - apex.x, member.getZ() - apex.z, coneYaw,
					CONE_RANGE, CONE_DEGREES / 2.0)) {
				continue;
			}
			// 피해원에 실체를 달지 않는다. 실체가 붙으면 바닐라가 스스로 밀어내고, 그 밀기는
			// 날개 퍼덕이기가 지키는 천장을 통째로 지나쳐 간다 — 그 길이 곧 공허다.
			member.hurtServer(end, end.damageSources().explosion(null, null),
					DragonLastStand.CONE_BREATH_DAMAGE);
		}
	}

	/**
	 * 부채꼴 테두리를 바닥에 그린다.
	 *
	 * <p>점 수는 값에서 나온다 — {@link #worstCasePointsPerTick} 이 같은 식으로 센다.
	 */
	private static void markCone(ServerLevel end, Vec3 apex) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		ParticleOptions deadly = TrialWarning.dust(TrialWarning.Colors.DEADLY);
		double half = Math.toRadians(CONE_DEGREES / 2.0);
		double forwardX = Math.sin(Math.toRadians(coneYaw));
		double forwardZ = -Math.cos(Math.toRadians(coneYaw));
		for (int side = -1; side <= 1; side += 2) {
			double angle = Math.atan2(forwardX, forwardZ) + half * side;
			double edgeX = Math.sin(angle);
			double edgeZ = Math.cos(angle);
			// 바닥 선.
			for (double along = MARK_GAP; along <= CONE_RANGE; along += MARK_GAP) {
				dot(end, ground, deadly, apex.x + edgeX * along, apex.z + edgeZ * along, 0.0);
			}
			// 흰 벽. 개수를 0 으로 보내면 뒤 값이 속도로 읽혀 점을 한 개도 안 늘리고 벽이 선다.
			for (double along = EDGE_WALL_GAP; along <= CONE_RANGE; along += EDGE_WALL_GAP) {
				dot(end, ground, ParticleTypes.CRIT, apex.x + edgeX * along, apex.z + edgeZ * along,
						TrialLandingShock.EDGE_RISE_SPEED);
			}
		}
		arc(end, ground, deadly, apex, CONE_RANGE, MARK_GAP);
		arc(end, ground, deadly, apex, CONE_RANGE * CONE_MID_ARC, EDGE_WALL_GAP);
	}

	/** 부채꼴의 호 하나. {@code gap} 이 점 사이 거리다. */
	private static void arc(ServerLevel end, TrialEnderPulse.Ground ground, ParticleOptions type,
			Vec3 apex, double radius, double gap) {
		double forwardX = Math.sin(Math.toRadians(coneYaw));
		double forwardZ = -Math.cos(Math.toRadians(coneYaw));
		double middle = Math.atan2(forwardX, forwardZ);
		double half = Math.toRadians(CONE_DEGREES / 2.0);
		int points = arcPoints(radius, gap);
		for (int index = 0; index <= points; index++) {
			double angle = middle - half + (half * 2.0 * index) / points;
			dot(end, ground, type, apex.x + Math.sin(angle) * radius,
					apex.z + Math.cos(angle) * radius, 0.0);
		}
	}

	/** 그 호에 찍을 점 수. 호 길이를 간격으로 나눈다. */
	static int arcPoints(double radius, double gap) {
		if (!(radius > 0.0) || !(gap > 0.0)) {
			return 1;
		}
		double length = Math.toRadians(CONE_DEGREES) * radius;
		return Math.max(1, (int) Math.ceil(length / gap));
	}

	/**
	 * 점 하나를 그 자리 지표에 찍는다.
	 *
	 * <p>{@code rise} 가 0 보다 크면 <b>개수를 0 으로 보낸다.</b> 그러면 뒤의 세 값이 방향이고
	 * 마지막이 속도로 읽혀 점 하나가 위로 쏘아진다 — 「착지 충격」이 점을 한 개도 안 늘리고
	 * 바닥 선을 벽으로 세운 방법이다.
	 *
	 * <p>땅이 없으면 찍지 않는다. 찍으면 고리가 까마득한 아래에 떠 「저기가 바닥이다」라고
	 * 거짓말을 한다.
	 */
	private static void dot(ServerLevel end, TrialEnderPulse.Ground ground, ParticleOptions type,
			double x, double z, double rise) {
		int surface = ground.surfaceAt(end, x, z);
		if (surface == TrialEnderPulse.NO_GROUND) {
			return;
		}
		if (rise > 0.0) {
			end.sendParticles(type, true, false, x, surface + GROUND_OFFSET, z,
					0, 0.0, 1.0, 0.0, rise);
			return;
		}
		end.sendParticles(type, true, false, x, surface + GROUND_OFFSET, z,
				1, 0.0, 0.0, 0.0, 0.0);
	}

	/**
	 * 부채꼴을 브레스 파티클로 채운다. 판정과 무관한 연출이다.
	 *
	 * <p>{@code DRAGON_BREATH} 는 26.3 에서 {@code PowerParticleOption} 을 받는다. 바닐라
	 * {@code DragonSittingFlamingPhase} 와 {@code DragonLandingPhase} 가 세기 {@code 1.0F} 로
	 * 쓰므로 같은 값을 쓴다 — 우리 브레스만 다른 모양이면 사람이 두 가지를 배운다.
	 *
	 * <p><b>장판({@code AreaEffectCloud})을 만들지 않는다.</b> 만들면 그것이 곧 잔류이고,
	 * 「잔류 없음」이 사람이 정한 것이다. {@code HOVERING} 으로 잠근 덕에 바닐라도 장판을
	 * 한 장 만들지 않는다.
	 */
	private static void sprayCone(ServerLevel end, Vec3 apex, int density) {
		ParticleOptions breath = PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F);
		double half = Math.toRadians(CONE_DEGREES / 2.0);
		double middle = Math.atan2(Math.sin(Math.toRadians(coneYaw)),
				-Math.cos(Math.toRadians(coneYaw)));
		for (double radius = 4.0; radius <= CONE_RANGE; radius += 4.0) {
			int points = arcPoints(radius, 2.0);
			for (int index = 0; index <= points; index++) {
				double angle = middle - half + (half * 2.0 * index) / points;
				end.sendParticles(breath, true, false,
						apex.x + Math.sin(angle) * radius, apex.y + 0.6,
						apex.z + Math.cos(angle) * radius, density, 0.4, 0.3, 0.4, 0.01);
			}
		}
	}

	/**
	 * 그 자리가 부채꼴 안인가.
	 *
	 * <p><b>세로는 보지 않는다.</b> {@code TrialRisks.insideMark} 와 같은 태도다 — 표식이 바닥에
	 * 그려지는데 「고리 위에 떠 있었으니 안 맞는다」가 되면 표식이 거짓말한 것이 된다.
	 *
	 * <p>꼭대기에 정확히 겹쳐 있으면 안이다. 드래곤 몸 안에 서 있는 경우이고, 거기서 방향을
	 * 지어내 빼 주면 「머리에 붙어 있으면 브레스가 안 맞는다」가 된다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 90도 경계와 20칸 경계를 직접 굴려 본다.
	 *
	 * @param dx      꼭대기에서 잰 x. 곧 {@code 사람 x − 드래곤 x}
	 * @param dz      꼭대기에서 잰 z
	 * @param yaw     고정해 둔 방향(도). {@code (sin(yaw), −cos(yaw))} 이 앞이다
	 * @param range   사거리(칸)
	 * @param halfDeg 부채꼴 반각(도). 90도짜리면 45 다
	 */
	static boolean insideCone(double dx, double dz, float yaw, double range, double halfDeg) {
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance > range) {
			return false;
		}
		if (distance < 1.0E-4) {
			return true;
		}
		double radians = Math.toRadians(yaw);
		double forwardX = Math.sin(radians);
		double forwardZ = -Math.cos(radians);
		double cosine = (dx * forwardX + dz * forwardZ) / distance;
		return cosine >= Math.cos(Math.toRadians(halfDeg));
	}

	// ------------------------------------------------------------------ ③ 번개 5개

	/**
	 * 번개 5개 — 반경 2.55칸 · 피해 {@code DragonLastStand.LIGHTNING_DAMAGE}(35) ·
	 * 드래곤 주변에 몰아서. 「낙뢰」와 <b>같은 값이자 같은 계산</b>이다.
	 *
	 * <h2>⚠ 겹침을 {@code TrialRisks.reserveSpots} 로 풀지 않았다 — 근거 넷</h2>
	 *
	 * <p>문서가 「반드시 그 함수를 지날 것」이라고 적어 두었지만, 실제로 읽어 보고 <b>이 패턴은
	 * 그 함수로 풀 수 없다</b>는 결론을 냈다. 다음 사람이 같은 검토를 다시 하지 않도록 적어 둔다.
	 *
	 * <ol>
	 *   <li><b>그 함수는 「드래곤 주변」을 만들 수 없다.</b> {@code groundSpot} 이
	 *       {@code arenaOffset(…, ARENA_RADIUS)} 으로 <b>반경 40 아레나 전체</b>에 흩뿌린다.
	 *       카드는 「드래곤 주변에 몰아서」이고, 무엇보다 마지막 안전지대가 반경 12 라
	 *       흩뿌리면 <b>지대 밖에 번개가 떨어진다</b> — 피할 수는 있어도 아무 뜻이 없는 위험이다</li>
	 *   <li><b>다섯 중 여럿이 조용히 빠진다.</b> {@code SPOT_TRIES} 가 8 인데, 그 숫자의 근거로
	 *       적혀 있는 것은 <b>「반경 3 짜리 열 곳을 반경 40 에 놓을 때」</b>다. 반경 12 안에
	 *       5.1칸 간격 다섯 곳을 여덟 번 굴려 찾게 하면 「겹치느니 한 발 빠진다」가 자주 일어나
     *       패턴이 두 발짜리가 된다</li>
	 *   <li><b>지금 그 목록에 남의 고리가 없다.</b> 최후의 저항은 진입할 때
	 *       {@code TrialRisks.clearState()} 를 부르고 그 안의 {@code forget()} 이
	 *       {@code LIVE_SPOTS} 를 비운다. 그 함수를 지나야 하는 진짜 이유(카드끼리 겹침)가
	 *       이 페이즈에는 없다 — 시련이 전부 멈춰 있다</li>
	 *   <li><b>굳은 배치는 겹침이 증명된다.</b> 무작위로 굴려 거르는 것은 「이번에는 안 겹쳤다」인데,
	 *       가운데 하나 + 90도씩 벌린 넷은 <b>회전만</b> 하므로 쌍마다의 거리가 회전과 무관하게
	 *       언제나 같다. 월드 없이 열 쌍을 전부 재어 볼 수 있다 —
	 *       {@link #LIGHTNING_RING_RADIUS} 의 표가 그 셈이다</li>
	 * </ol>
	 *
	 * <p>다만 <b>재는 자는 그쪽에서 가져온다</b> — {@code TrialRisks.spotMinGap} 이 「겹친다」의
	 * 정의를 들고 있는 유일한 자리이고, 시험이 그 함수로 재므로 그 정의가 바뀌면 여기가 먼저
	 * 깨진다.
	 *
	 * <h2>한 사람이 받는 것은 한 발이다</h2>
	 *
	 * <p>겹치는 자리가 없으므로 구조적으로 그렇다. 그래도 {@link #strikeLightning} 이 첫 발에서
	 * 빠져나오게 두었다 — 두 발이면 무장 기준 13.86 이고 세 발이면 전멸이다.
	 */
	private static void lightningFive(ServerLevel end, EnderDragon dragon,
			List<ServerPlayer> members, long at, long now, int step) {
		Vec3 center = dragon.position();
		if (lightningPickedFor != at) {
			lightningPickedFor = at;
			lightningSpots = pickLightningSpots(end, center);
		}
		List<Vec3> spots = lightningSpots;
		if (spots.isEmpty()) {
			return;
		}
		if (step < LIGHTNING_WARN_TICKS) {
			warnLightning(end, members, spots, step);
			return;
		}
		if (step == LIGHTNING_WARN_TICKS) {
			strikeLightning(end, members, spots);
		}
	}

	/** 예고. 노랑 고리와 층 소리. 노랑은 규약의 「번개가 내리친다」다. */
	private static void warnLightning(ServerLevel end, List<ServerPlayer> members, List<Vec3> spots,
			int step) {
		ParticleOptions bolt = TrialWarning.dust(TrialWarning.Colors.LIGHTNING);
		for (Vec3 spot : spots) {
			TrialWarning.markGround(end, spot, LIGHTNING_RADIUS, bolt);
		}
		int remaining = LIGHTNING_WARN_TICKS - step;
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage == null || !TrialRisks.stageJustChanged(remaining, LIGHTNING_WARN_TICKS)) {
			return;
		}
		for (ServerPlayer member : members) {
			TrialWarning.soundFor(end, member, stage);
		}
	}

	/**
	 * 다섯 곳에 내리친다.
	 *
	 * <p>피해원은 {@code lightningBolt()} 다 — 문서가 「낙뢰와 같은 방식」이라고 적어 둔 것을
	 * 값으로 지킨 것이라 <b>피해원도 같아야 한다</b>({@code TrialRisks.detonate} 와 같은 것).
	 * 무장 기준 한 대 6.93 · 세 대 20.79 로 「큰 카드는 세 대에 전멸」에 그대로 얹힌다.
	 */
	private static void strikeLightning(ServerLevel end, List<ServerPlayer> members,
			List<Vec3> spots) {
		for (Vec3 spot : spots) {
			bolt(end, spot);
		}
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			for (Vec3 spot : spots) {
				if (!TrialRisks.insideMark(member.position(), spot, LIGHTNING_RADIUS)) {
					continue;
				}
				member.hurtServer(end, end.damageSources().lightningBolt(),
						DragonLastStand.LIGHTNING_DAMAGE);
				// 한 사람은 한 발이다. 겹치는 자리가 없으므로 여기까지 올 일이 없지만, 배치를
				// 손대는 사람이 이 약속을 깨뜨리지 못하게 둔다.
				break;
			}
		}
	}

	/**
	 * 연출용 번개 하나.
	 *
	 * <p>⚠ <b>{@code setVisualOnly(true)} 를 반드시 켠다.</b> 26.3 {@code LightningBolt} 는 이 깃발
	 * 하나로 두 가지를 끈다 — {@code tick()} 의 피해 구간(켜지 않으면 우리가 준 35 위에 바닐라
	 * 피해가 얹혀 <b>조용히 두 배</b>가 된다)과 {@code spawnFire}(엔드에도 사람이 놓은 블록이 있고,
	 * 우리 카드 어디에도 「불」이 적혀 있지 않다). <b>로그도 빌드도 알려 주지 않는</b> 깃발이라
	 * {@code DragonLastStandPatternsTest} 가 클래스 파일에서 직접 찾는다.
	 *
	 * <p>{@code TrialRisks.strikeLightning} 과 같은 코드인데 그쪽이 {@code private} 이다. 열어
	 * 달라고 고치지 않은 것은 {@code TrialRisks} 를 읽기만 하기로 정해져 있기 때문이고, 같은
	 * 것이 두 벌이 된 사실은 인계에 적어 두었다.
	 *
	 * <p>소리와 파티클을 덧붙이지 않는다. 번개 엔티티가 스스로 천둥과 착탄음을 내는데 그 위에
	 * 폭발 파티클을 뿌리면 TNT 처럼 보인다 — 「낙뢰」가 이미 겪은 일이다.
	 */
	private static void bolt(ServerLevel end, Vec3 at) {
		LightningBolt spawned = EntityTypes.LIGHTNING_BOLT.create(end, EntitySpawnReason.TRIGGERED);
		if (spawned == null) {
			return;
		}
		spawned.setVisualOnly(true);
		// 표식을 그린 바로 그 자리에 세운다. 블록 중앙으로 맞추면 고리와 번개가 어긋난다.
		spawned.snapTo(at);
		end.addFreshEntity(spawned);
	}

	/**
	 * 다섯 곳을 고른다. 배치는 굳어 있고 <b>회전만</b> 무작위다.
	 *
	 * <p>허공인 자리는 <b>버린다</b>(「겹치느니 한 발 빠진다」와 같은 태도다). 중앙 섬은 둥글지
	 * 않아 반경 6 안에도 사람이 파 놓은 구멍이 있을 수 있고, 허공에 내리치면 예고도 피해도 뜻이
	 * 없다. 자리를 다시 굴리지 않는 것은 <b>굴리면 겹침 증명이 깨지기 때문</b>이다.
	 */
	private static List<Vec3> pickLightningSpots(ServerLevel end, Vec3 center) {
		List<Vec3> spots = new ArrayList<>();
		for (Vec3 offset : lightningOffsets(end.getRandom().nextDouble())) {
			double x = center.x + offset.x;
			double z = center.z + offset.z;
			BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					BlockPos.containing(x, 0.0, z));
			// 허공이면 하이트맵이 월드 바닥을 돌려준다.
			if (ground.getY() > end.getMinY()) {
				spots.add(new Vec3(x, ground.getY(), z));
			}
		}
		return spots;
	}

	/**
	 * 드래곤을 중심으로 한 다섯 자리의 <b>상대 좌표</b>. 가운데 하나 + 90도씩 벌린 넷이다.
	 *
	 * <p>회전만 무작위이므로 <b>쌍마다의 거리가 굴림과 무관하게 언제나 같다</b> — 그것이
	 * 「겹치지 않는다」를 증명으로 만드는 자리다. 표는 {@link #LIGHTNING_RING_RADIUS} 에 있다.
	 *
	 * @param rotationRoll 0 이상 1 미만. 실전에서는 {@code end.getRandom().nextDouble()} 이 준다
	 */
	static List<Vec3> lightningOffsets(double rotationRoll) {
		double rotation = Math.max(0.0, Math.min(0.999999, rotationRoll)) * Math.PI * 2.0;
		List<Vec3> offsets = new ArrayList<>(LIGHTNING_COUNT);
		// 가운데 하나. 머리에 붙어 때리는 사람이 반드시 한 걸음 옮겨야 하는 자리다.
		offsets.add(Vec3.ZERO);
		int ring = LIGHTNING_COUNT - 1;
		for (int index = 0; index < ring; index++) {
			double angle = rotation + (Math.PI * 2.0 * index) / ring;
			offsets.add(new Vec3(Math.cos(angle) * LIGHTNING_RING_RADIUS, 0.0,
					Math.sin(angle) * LIGHTNING_RING_RADIUS));
		}
		return offsets;
	}

	// ------------------------------------------------------------------ 예산

	/**
	 * 세 패턴 가운데 <b>한 틱에 가장 많이 나가는</b> 점 수.
	 *
	 * <p>세 패턴은 동시에 돌지 않으므로({@code DragonLastStand.advance}) 이것이 이 페이즈의
	 * 한 틱 예산이고, {@code TrialLandingShock.MAX_POINTS_PER_TICK}(440)과 견줄 값이다.
	 * 안전지대는 월드 보더라 <b>점을 한 개도 쓰지 않는다.</b>
	 *
	 * <p>값에서 직접 센다 — 개수나 반경을 올리는 사람이 예산을 눈으로 세지 않아도 시험이 먼저
	 * 멈춰 세운다.
	 */
	static int worstCasePointsPerTick() {
		return Math.max(wingBeatPoints(), Math.max(conePoints(), lightningPoints()));
	}

	/** 가장 바쁜 틱에 팀원 수를 넷으로 본다. 사람마다 한 발씩 나가는 연출을 셀 때 쓴다. */
	private static final int BUDGET_MEMBERS = 4;

	/**
	 * 날개 퍼덕이기 — 파랑 고리 둘(반경 4 · 12) + 충격파 틱의 돌풍.
	 *
	 * <p>가장 바쁜 틱은 충격파가 나가는 틱이다.
	 */
	static int wingBeatPoints() {
		int rings = TrialWarning.ringPoints(WING_NEAR_RADIUS)
				+ TrialWarning.ringPoints(WING_STRONG_RADIUS);
		// 큰 돌풍 하나 + 밀린 사람마다 작은 돌풍 하나.
		return rings + 1 + BUDGET_MEMBERS;
	}

	/** 부채꼴 브레스 — 예고 틱과 터지는 틱 가운데 바쁜 쪽. */
	static int conePoints() {
		return Math.max(coneWarnPoints(), coneSprayPoints());
	}

	/**
	 * 예고 틱 — 가장자리 두 줄(바닥 + 흰 벽) · 사거리 호 · 가운데 호 · 입의 불씨.
	 *
	 * <p>{@code +1} 이 붙는 것은 호가 양 끝을 모두 찍기 때문이다({@code index <= points}).
	 */
	static int coneWarnPoints() {
		int edgeGround = (int) (CONE_RANGE / MARK_GAP);
		int edgeWall = (int) (CONE_RANGE / EDGE_WALL_GAP);
		int outerArc = arcPoints(CONE_RANGE, MARK_GAP) + 1;
		int midArc = arcPoints(CONE_RANGE * CONE_MID_ARC, EDGE_WALL_GAP) + 1;
		return (edgeGround + edgeWall) * 2 + outerArc + midArc + 1;
	}

	/** 터지는 틱(과 그 뒤 불꽃) — 부채꼴을 채우는 브레스 파티클. 예고 표식은 그 틱에 안 나간다. */
	static int coneSprayPoints() {
		int points = 0;
		for (double radius = 4.0; radius <= CONE_RANGE; radius += 4.0) {
			points += arcPoints(radius, 2.0) + 1;
		}
		return points;
	}

	/** 번개 5개 — 노랑 고리 다섯. 내리치는 틱에는 표식이 없고 번개 엔티티뿐이다. */
	static int lightningPoints() {
		return LIGHTNING_COUNT * TrialWarning.ringPoints(LIGHTNING_RADIUS);
	}
}
