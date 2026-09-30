package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 「최후의 저항」의 <b>패턴 넷</b>과 <b>상시 번개</b>. {@code DragonLastStand.runPattern} 이 여기로 넘긴다.
 *
 * <p>고르는 쪽({@code DragonLastStand.allowed}·{@code pick}·{@code restTicks})은 그쪽에 있고
 * 여기는 <b>그리는 쪽</b>만 있다. 파일을 뗀 것은 {@code DragonFireBarrage} 와 같은 이유다 —
 * 붙박이 드래곤·진입 연출·처치 처리가 이미 1200줄이라, 패턴을 그 안에 얹으면 「왜 드래곤이
 * 안 앉나」와 「왜 브레스가 빗나가나」를 같은 파일에서 찾게 된다.
 *
 * <h2>⚠ 뽑히는 것은 <b>넷</b>이고 번개는 <b>혼자 도는 시계</b>다</h2>
 *
 * <p>번개는 전에 뽑히는 패턴이었다. 사람이 플레이해 보고 <b>「번개는 패턴에 추가하지말고
 * 기본이펙트로 계속 터졋으면좋겟어 드래곤 패턴이 아니라」</b>라고 해서 패턴 풀에서 빼고,
 * {@link DragonLastStandZone}(안전지대)처럼 <b>진입부터 끝까지 저 혼자 도는 시계</b>로 옮겼다
 * ({@link #tickLightning}). 개수도 사람이 <b>5 → 10</b> 으로 올렸다.
 *
 * <table border="1">
 *   <caption>지금 무엇이 무엇인가</caption>
 *   <tr><th></th><th>뽑히는가</th><th>언제 도는가</th></tr>
 *   <tr><td>날개 퍼덕이기</td><td>그렇다</td><td>골랐을 때 100틱</td></tr>
 *   <tr><td>부채꼴 브레스</td><td>그렇다</td><td>골랐을 때 120틱</td></tr>
 *   <tr><td><b>공허 흡입</b></td><td>그렇다</td><td>골랐을 때 180틱</td></tr>
 *   <tr><td><b>십자 균열</b></td><td>그렇다</td><td>골랐을 때 130틱</td></tr>
 *   <tr><td><b>상시 번개</b></td><td><b>아니다</b></td><td>3초 뒤부터 <b>6초마다 계속</b></td></tr>
 *   <tr><td><b>반구 블록 파괴</b></td><td><b>아니다</b></td><td>1초마다 계속 —
 *       {@link DragonLastStandDome} 에 있다</td></tr>
 * </table>
 *
 * <p>⚠ 그래서 <b>번개와 패턴이 같은 틱에 함께 돈다.</b> 전에는 「패턴은 한 번에 하나뿐이니 가장
 * 바쁜 틱은 한 패턴의 가장 바쁜 틱」이었는데 그 전제가 깨졌다 — 한 틱 점 예산을 세는 식이
 * <b>번개 + 패턴</b>으로 바뀌었다({@link #worstCasePointsPerTick}).
 *
 * <h2>✅ 패턴이 넷이 되어 순서가 예측 불가능해졌다</h2>
 *
 * <p>둘이던 동안은 {@code DragonLastStand.allowed} 의 「같은 패턴 연속 금지」 때문에
 * <b>날개 → 브레스</b> 로 완전히 번갈아 나왔고, <b>브레스가 잠기면 고를 것이 하나도 없는
 * 구간</b>까지 있었다. 사람이 <b>공허 흡입</b>과 <b>십자 균열</b>을 더해 그 둘이 함께 사라졌다 —
 * 브레스가 잠겨도 남는 것이 셋이라 {@code allowed} 가 <b>빈 목록을 돌려줄 수 없다</b>.
 *
 * <p>⚠ <b>연속 금지를 지우지 말 것.</b> 넷이 되어서야 그것이 제 일을 한다.
 *
 * <p>⚠ <b>새 패턴 둘에는 잠금을 걸지 않았다.</b> 브레스의 제한 셋(쿨다운 8초 · 축소 직후 3초 ·
 * 축소와 동시)은 그 카드가 <b>한 대에 전멸</b>이기 때문에 붙은 대가다. 흡입 35(무장 기준
 * 6.93)와 균열 23(무장 기준 6.77)은 <b>세 대에 전멸</b>인 보통 카드라 같은 대가를 치를 이유가
 * 없고, 제한을 하나 더 걸면 {@code allowed} 의 구멍이 되레 <b>넓어진다</b>
 * ({@code DragonLastStandTest} 의 「제한을 하나 더 거는 사람은 여기서 멈춘다」). 축소와 겹쳐도
 * 되는 까닭은 각 패턴 설명에 따로 적어 두었다.
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
 *       유일하게 금지한 「대응 불가 즉사」다. 하이트맵을 <b>읽는</b> 것만 한다.
 *       ⚠ <b>블록을 부수는 것은 {@link DragonLastStandDome} 하나뿐</b>이고, 그쪽이 「바닥은 절대
 *       안 부순다」를 두 겹으로 막아 두었다 — 이 파일에 그 코드를 들여오지 말 것</li>
 *   <li><b>피해원에 실체를 달지 않는다.</b> 실체가 붙으면 {@code LivingEntity} 가 스스로 밀어내고,
 *       그 밀기는 우리 천장을 통째로 지나쳐 간다</li>
 * </ul>
 *
 * <h2>한 틱 점 예산</h2>
 *
 * <p>{@code TrialLandingShock.MAX_POINTS_PER_TICK} 이 440 이고 그것이 이 판의 예산이다. 패턴 넷은
 * <b>동시에 돌지 않지만</b>({@code DragonLastStand.advance} 가 한 번에 하나만 돌린다)
 * <b>번개는 언제나 함께 돈다.</b> 그래서 가장 바쁜 틱은 <b>번개 + 가장 바쁜 패턴</b>이다 —
 * {@link #worstCasePointsPerTick} 이 값에서 직접 세고 {@code DragonLastStandPatternsTest} 가 그
 * 수를 예산과 견준다.
 *
 * <p>번개가 <b>고리 한 바퀴를 여섯 틱에 나눠</b> 그리는 것이 그래서다. 열 곳을 매 틱 다 그리면
 * 400점이라 그것만으로 예산이 차고, 부채꼴 예고(202)와 겹치는 순간 602점이 된다 —
 * {@link #LIGHTNING_MARK_STRIDE} 에 그 셈이 있다.
 *
 * <p>그리고 부채꼴의 <b>빨간 투명 면은 점을 한 개도 쓰지 않는다.</b> 파티클이 아니라 디스플레이
 * 개체라 예산과 무관하다 — {@link DragonLastStandConePanel} 을 볼 것.
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
	 * 넉백 세기를 한 번에 올린 배율. <b>사람이 정한 값이다</b> —
	 * <b>「날개퍼덕이기 때 넉백을 1.5배 늘려봐」</b>.
	 *
	 * <p>구간마다 따로 곱하지 않고 <b>한 곳</b>에서 곱한다. 「4~12칸 구간만 1.5배」로 읽으면
	 * 12칸 밖의 잦아드는 구간이 그대로 남아 <b>12.0 과 12.1 의 결과가 다시 절벽처럼 갈린다</b> —
	 * 사람이 「나머지 거리의 비율도 함께 올리라」고 한 것이 그 뜻이다. 잦아드는 구간은
	 * {@link #WING_PUSH_BLOCKS} 의 비율로 적혀 있어 저절로 따라 올라간다.
	 */
	static final double WING_PUSH_SCALE = 1.5;

	/**
	 * 가장 강한 구간에서 <b>부탁하는</b> 미는 거리(칸). <b>4 → 6 이다.</b>
	 *
	 * <h2>밑값 4 는 왜 4 였는가</h2>
	 *
	 * <p>이 저장소가 「강한 넉백」으로 쓰는 단위는 {@code TrialEnderStorm.PUSH_BLOCKS}(8)이고,
	 * 「착지 충격」·「엔더폭풍」이 그 값을 쓴다. 그런데 이 패턴은 <b>피해가 0 인데 5초 동안 여덟
	 * 번</b> 민다. 한 번치가 「강한 넉백」 한 대와 같으면 5초 내내 조작이 덮어써져 <b>요구하는
	 * 행동(비켜서 붙기)을 할 수 없는 패턴</b>이 된다. 그래서 그 단위의 <b>절반</b>으로 잡았다.
	 *
	 * <h2>사람이 1.5배로 올렸다 — 그래도 「강한 넉백」 한 대보다 약하다</h2>
	 *
	 * <p>{@code 8 ÷ 2 × 1.5 = 6} 이라 단위의 <b>4분의 3</b> 이다. 위의 근거(한 번치가 한 대와
	 * 같으면 안 된다)가 아직 성립한다 — 8 을 넘기려면 배율이 2 를 넘어야 한다.
	 *
	 * <p>⚠ <b>천장은 그대로다.</b> 세기를 올려도 목적지는 {@code TrialEnderStorm.pushDistance}
	 * (반경 32 안)와 {@code TrialLandingShock.groundedReach}(땅이 이어진 데까지)가 자른다 —
	 * {@code DragonLastStandPatternsTest} 가 <b>올린 값으로 섬 곳곳에서 다시 굴려 본다.</b>
	 *
	 * <p>실제로 밀리는 거리는 이보다 훨씬 짧다 — {@code TrialEnderStorm.pushVelocity} 가 공중
	 * 감쇠로 속도를 잡으므로 발이 땅에 붙어 있으면 바닥 마찰(0.546)이 먼저 먹어 <b>5분의 1
	 * 남짓</b>이다. 적힌 값은 <b>천장</b>이다.
	 */
	static final double WING_PUSH_BLOCKS = TrialEnderStorm.PUSH_BLOCKS / 2.0 * WING_PUSH_SCALE;
	/**
	 * 드래곤 바로 아래에서 부탁하는 미는 거리(칸). <b>1.5 → 2.25 다.</b>
	 *
	 * <p>0 이 아닌 것은 「약하다」이지 「없다」가 아니기 때문이다. 0 으로 두면 머리 밑이
	 * <b>완전한 안전지대</b>가 되어 이 패턴이 아무것도 요구하지 않는다.
	 *
	 * <p>같은 배율을 여기에도 곱한다. 한쪽만 올리면 <b>「4칸 이내는 약하다」의 정도가 달라진다</b> —
	 * 밑값에서 4 : 1.5 였던 것이 6 : 1.5 가 되면 머리 밑이 상대적으로 더 안전해진다.
	 */
	static final double WING_NEAR_PUSH_BLOCKS = 1.5 * WING_PUSH_SCALE;

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

	/**
	 * 예고 중에 「불이 모인다」 소리를 내는 간격(틱). 1초.
	 *
	 * <h2>왜 층 소리만으로는 모자랐는가</h2>
	 *
	 * <p>사람이 <b>「부채꼴 브레스도 전조에 소리를 뭔가 넣엇으면해」</b>라고 했다. 그런데
	 * {@link #warnCone} 은 <b>처음부터 {@link TrialWarning#soundFor} 를 부르고 있었다.</b> 들리지
	 * 않았던 까닭은 층 소리가 <b>5초 동안 세 번</b>뿐이고({@code stageFor} 가 100·50·14틱 남은
	 * 자리에서만 바뀐다) 그 첫 번째가 <b>드래곤 울음</b>이라 붙박이 드래곤이 제 울음을 내는 것과
	 * 구별되지 않기 때문이다.
	 *
	 * <p>그래서 층 소리를 <b>그대로 두고</b> 그 위에 <b>되풀이되는 충전음</b>을 얹었다 — 1초마다
	 * 한 번씩 다섯 번, 음높이가 {@link #chargePitch} 로 낮은 데서 높은 데까지 오른다. 「무언가
	 * 차오르고 있다」는 되풀이와 음높이로만 말할 수 있고, 한 번 울리는 소리로는 못 한다.
	 */
	static final int CONE_CHARGE_TICKS = 20;
	/** 충전음의 첫 음높이. */
	private static final float CONE_CHARGE_PITCH_LOW = 0.6F;
	/** 충전음의 마지막 음높이. 1.0 을 넘겨야 「올라갔다」가 들린다. */
	private static final float CONE_CHARGE_PITCH_HIGH = 1.4F;

	/** 터지는 틱에 불꽃을 뿌리는 고리의 간격(칸). */
	private static final double FLAME_RING_STEP = 2.5;
	/** 그 고리에 찍는 불꽃 사이 간격(칸). 표식이 아니라 연출이라 바닥 선보다 성기다. */
	private static final double FLAME_ARC_GAP = 2.5;

	// ------------------------------------------------------------------ ③ 공허 흡입

	/** 검은 원의 반경(칸). <b>사람이 정한 값이다.</b> */
	static final double SUCK_RADIUS = 4.0;
	/** 예고(틱). 3초. <b>사람이 정한 값이다.</b> */
	static final int SUCK_WARN_TICKS = 60;
	/** 빨아들이는 시간(틱). 5초. <b>사람이 정한 값이다.</b> */
	static final int SUCK_PULL_TICKS = 100;
	/** 터진 뒤 파티클이 남는 시간(틱). 1초. <b>잔류가 아니다</b> — 피해는 터지는 한 틱뿐이다. */
	static final int SUCK_AFTERGLOW_TICKS = 20;

	/**
	 * 빨아들이는 손이 닿는 거리(칸). <b>사람이 정하지 않았다.</b>
	 *
	 * <p>사람이 정한 것은 「반경 4칸」이고 <b>그것은 터질 때 아픈 자리</b>다. 손이 4칸까지만
	 * 닿으면 이 패턴은 <b>아무것도 요구하지 않는다</b> — 4칸 밖에 선 사람은 가만히 있어도 되고,
	 * 안에 선 사람도 한 칸만 걸어 나가면 끝이라 「반대쪽으로 달려서 도망가야지」가 성립하지 않는다.
	 *
	 * <p>그래서 <b>새 숫자를 만들지 않고</b> 이 페이즈가 이미 「드래곤이 닿는 거리」로 쓰는 20 을
	 * 쓴다 — {@link #CONE_RANGE} 가 그 값이고 {@link #WING_FADE_RADIUS} 도 같은 근거로 같은 값이다.
	 *
	 * <p>⚠ 3단계 지대가 반변 12칸이므로 <b>지대 안 전원이 손에 들어온다.</b> 그것이 의도다 —
	 * 사람이 「반대쪽으로 달려서 도망가야지」라고 했고, 한 사람만 달리는 패턴이 아니다.
	 */
	static final double SUCK_REACH = CONE_RANGE;

	/**
	 * 사람의 이동속도 특성 기본값. 26.3 {@code Player.createAttributes} 에서 읽었다.
	 *
	 * <p>바닥에서는 이 값이 <b>매 틱 속도에 더해지는 입력 가속</b>과 같다. 26.3
	 * {@code LivingEntity.getFrictionInfluencedSpeed(f)} 가 {@code f > 0.6} 일 때만 보정하고 기본
	 * 블록 마찰이 <b>정확히 0.6</b> 이라 보정이 걸리지 않고 {@code getSpeed()} 가 그대로 나온다
	 * (바이트코드로 확인했다).
	 */
	static final double WALK_INPUT = 0.1;
	/**
	 * 달릴 때 붙는 곱. 26.3 {@code LivingEntity.SPEED_MODIFIER_SPRINTING} 이
	 * {@code 0.3 · ADD_MULTIPLIED_TOTAL} 이라 <b>1.3배</b>다(바이트코드로 확인했다).
	 */
	static final double SPRINT_MULTIPLIER = 1.3;
	/**
	 * 바닥 감쇠. <b>블록 마찰 0.6 × 0.91</b> 이다.
	 *
	 * <p>26.3 {@code LivingEntity.travelInAir} 가 매 틱 끝에 수평 속도에 {@code f × 0.91} 을
	 * 곱하고 {@code f} 가 바닥 블록의 마찰(기본 0.6)이다. 공중에서는 {@code f = 1} 이라
	 * {@code TrialEnderStorm.AIR_DRAG}(0.91)가 되고 <b>그 차이가 이 패턴의 함정</b>이다 —
	 * 아래 {@link #SUCK_MAX_INWARD} 를 볼 것.
	 */
	static final double GROUND_DRAG = 0.546;

	/**
	 * ⚠ <b>「달리기보다 약간 약하게」의 「약간」.</b> <b>사람이 정하지 않았다.</b>
	 *
	 * <p>사람이 정한 것은 <b>「반대쪽으로 달려서 도망가야지」</b>, 곧 <b>달리면 벗어나는 것이
	 * 정답</b>이다. 그러려면 세기가 달리기보다 <b>반드시 약해야</b> 하고, 한참 약하면
	 * 「빨려들어간다」가 말뿐이 된다. 0.85 가 그 둘 사이다.
	 *
	 * <table border="1">
	 *   <caption>이 값이 만드는 답 (달리기 = 0.2864칸/틱 = 5.73칸/초)</caption>
	 *   <tr><th>사람이 하는 것</th><th>1초에 벌거나 잃는 거리</th></tr>
	 *   <tr><td><b>달려서 반대쪽으로</b></td><td><b>+0.86칸</b> — 벗어난다</td></tr>
	 *   <tr><td>걸어서 반대쪽으로</td><td>−0.47칸 — 끌려든다</td></tr>
	 *   <tr><td>가만히</td><td>−4.87칸 — 그냥 끌려든다</td></tr>
	 * </table>
	 *
	 * <p>곧 <b>달리기만 답이다.</b> 5초 동안 달리면 4.3칸을 벌므로, 드래곤 머리에 붙어 있던
	 * 사람(반경 3 남짓)이 반경 4 를 넘기는 데 1.2초면 된다 — 그것이 「붙어 있던 사람을 그 자리에서
	 * 쫓아낸다」다.
	 *
	 * <p>⚠ <b>진짜로 벽에 닿는 것은 예고 3초 동안이다.</b> 예고 중에는 손이 아직 닿지 않으므로
	 * 검은 원을 보고 곧바로 달린 사람은 3초에 <b>17칸</b>을 간다 — 3단계 지대(반변 12)에서는
	 * 그것만으로 벽이다. 사람이 말한 긴장이 그 자리에 있고 <b>쉽게 만들지 말 것.</b>
	 *
	 * <p>1.0 을 넘기면 어떤 사람도 벗어날 수 없어 「달리면 벗어난다」가 그 자리에서 거짓이 된다 —
	 * {@code DragonLastStandPatternsTest} 가 그 선을 붙들고 있다.
	 */
	static final double SUCK_SPRINT_RATIO = 0.85;

	/**
	 * 매 틱 속도에 <b>더하는</b> 값(칸/틱).
	 *
	 * <p>사람의 입력 가속과 <b>같은 자리에서 같은 감쇠를 지나므로</b> 비율을 그대로 곱하면 된다 —
	 * 둘 다 {@code v' = (v + a) × 감쇠} 의 {@code a} 다. 그래서 「달리기의 85%」가 상수 한 줄이다.
	 */
	static final double SUCK_STEP = WALK_INPUT * SPRINT_MULTIPLIER * SUCK_SPRINT_RATIO;

	/**
	 * ⚠⚠ <b>안쪽으로 갈 수 있는 가장 빠른 속도(칸/틱). 이 한 줄이 이 패턴의 안전장치다.</b>
	 *
	 * <h2>세로 성분을 어떻게 다뤘는가 — <b>한 톨도 건드리지 않는다</b></h2>
	 *
	 * <p>사람의 세로 속도는 <b>읽어서 그대로 돌려놓는다</b>({@link #suck} 의 {@code motion.y}).
	 * 날개 퍼덕이기가 같은 짓을 하지만({@code shove} 의 「세로 속도는 읽어서 그대로 돌려놓는다」)
	 * <b>당기는 쪽에는 이유가 하나 더 있다.</b>
	 *
	 * <ul>
	 *   <li><b>아래로 당기면 사람을 땅에 눌러 넣는다.</b> 바닐라 충돌이 블록 속으로는 못 넣지만,
	 *       내리누르는 동안은 점프가 죽어 <b>구덩이에 빠진 사람이 나올 수 없다</b> — 5초 동안
	 *       조작을 빼앗는 것이라 「달리면 벗어난다」와 정면으로 어긋난다</li>
	 *   <li>⚠⚠ <b>위로 당기면 그 자리에서 사고가 된다.</b> 원의 중심(= 드래곤 발밑)은 섬 표면보다
	 *       <b>네 칸 높다</b>({@link DragonLastStandDome#baselineY} 의 셈). 그러니 중심을 향해
	 *       <b>세로까지</b> 당기면 섬에 선 사람이 <b>들린다.</b> 들리는 순간 감쇠가 바닥
	 *       ({@value #GROUND_DRAG})에서 공중(0.91)으로 바뀌어 같은 {@link #SUCK_STEP} 이 만드는
	 *       종착 속도가 <b>8.4배</b>가 되고, 그때는 달려도 못 벗어난다. 넉백의 천장 논리
	 *       ({@code TrialEnderStorm.pushDistance})가 <b>여기에 그대로 옮겨 오지 않는</b> 까닭이
	 *       이것이다 — 그쪽이 막는 것은 「섬 밖으로 나가는 것」인데 당기는 방향은 안쪽이라 그
	 *       위험이 아예 없고, 대신 <b>이 세로 함정</b>이 생긴다</li>
	 * </ul>
	 *
	 * <h2>그래서 수평만 당기고, 그 수평에도 천장을 씌운다</h2>
	 *
	 * <p>세로를 안 건드려도 사람이 <b>제 발로 점프하면</b> 공중 감쇠에 들어간다. 그때
	 * {@link #SUCK_STEP} 을 그냥 매 틱 더하면 종착 속도가 {@code a × 0.91 / 0.09} 로 바닥의
	 * <b>8.4배</b>가 되어 점프한 사람이 중심으로 날아간다. 얼음을 깔아도(마찰 0.98) 같은 일이다.
	 *
	 * <p>막는 길은 <b>결과 속도에 천장을 씌우는 것</b>이고, 값은 <b>바닥에서의 종착 속도</b>
	 * ({@code a / (1 − 감쇠)})로 잡았다. 그러면
	 *
	 * <ul>
	 *   <li><b>바닥에서는 천장이 걸리지 않는다</b> — 그것이 바로 바닥의 평형점이라 값이 같다</li>
	 *   <li><b>공중·얼음에서는 천장이 걸려</b> 바닥과 <b>똑같은</b> 세기가 된다. 점프해도 이득도
	 *       손해도 없다</li>
	 *   <li>렉으로 틱이 몰려도 누적되지 않는다. 천장이 <b>속도</b>에 걸리므로 몇 번을 연달아
	 *       더해도 이 값을 넘지 못한다</li>
	 * </ul>
	 *
	 * <p>{@code TrialEnderStorm.pushVelocity} 가 「어긋나는 방향이 언제나 <b>덜 미는 쪽</b>이어야
	 * 한다」라고 적어 둔 그 태도와 같다.
	 */
	static final double SUCK_MAX_INWARD = SUCK_STEP / (1.0 - GROUND_DRAG);

	/**
	 * 검은 원을 채우는 색.
	 *
	 * <p>사람 말이 <b>「흡입은 드래곤에 검은원이고」</b>다. 규약({@link TrialWarning.Colors})에
	 * <b>다섯째 색을 더하지 않았다</b> — 이 검정은 「무엇을 해야 하는가」를 말하는 <b>표식이
	 * 아니라</b> 구멍 그 자체이고, 「서 있으면 죽는다」는 경계는 여전히 규약의 빨강이 말한다
	 * ({@link TrialWarning.Colors#DEADLY} 로 그리는 반경 4 고리). {@link DragonLastStandConePanel}
	 * 이 빨간 면에서 한 것과 같은 판단이다.
	 */
	private static final int SUCK_BLACK = 0x000000;

	/**
	 * 검은 원의 속을 <b>몇 틱에 나눠</b> 채울지. {@code TrialEndRain} 의 상한을 그대로 쓴다.
	 *
	 * <p>반경 4 원의 속을 {@link #MARK_GAP}(0.5) 간격의 고리 일곱 겹으로 채우면 <b>180점</b>이고,
	 * 경계 고리 51점을 더하면 한 틱에 231점이다. 상시 번개(70)와 겹치면 301점이라 <b>이 페이즈의
	 * 가장 바쁜 틱을 갈아치운다.</b> 나눠 그리면 30점이라 부채꼴 예고(202)가 여전히 가장 바쁜 쪽으로
	 * 남고, 예산을 세는 식도 바뀌지 않는다.
	 *
	 * <p>6 인 까닭은 <b>먼지 파티클 수명이 최소 8틱</b>이라는 것이고
	 * ({@code TrialWarning.markGround} 의 「stride 는 8보다 작아야 한다」), 상시 번개가 같은
	 * 근거로 같은 값을 쓴다. 예고가 60틱이라 처음 여섯 틱이 성긴 것은 묻힌다.
	 */
	static final int SUCK_MARK_STRIDE = TrialEndRain.MARK_MAX_STRIDE;

	/** 흡입음을 되풀이하는 간격(틱). 0.5초. 빨아들이는 동안만 울린다. */
	static final int SUCK_BREATH_TICKS = 10;

	// ------------------------------------------------------------------ ④ 십자 균열

	/** 십자를 긋는 횟수. <b>사람이 정한 값이다</b> — 「3번 반복하는 패턴」. */
	static final int CROSS_ROUNDS = 3;
	/** 첫 십자의 예고(틱). 3초. <b>사람이 정한 값이다.</b> */
	static final int CROSS_FIRST_WARN_TICKS = 60;
	/**
	 * 터진 뒤 다음 십자까지(틱). 1.5초. <b>사람이 정한 값이다.</b>
	 *
	 * <p>그러니 둘째·셋째 십자의 예고는 <b>1.5초</b>다. 그것이 우연히 맞는 값이 아니다 —
	 * {@code TrialWarning.TICKS_SIDESTEP} 이 정확히 30틱이고 「<b>옆으로 비킬</b> 시간」이라고
	 * 적혀 있다. 이 패턴이 요구하는 것이 딱 그것이다(사람 말: <b>「단순 피하기」</b>) — 첫 십자는
	 * 어느 사분면에 설지 고르는 3초가 필요하지만, 그 뒤로는 각도가 도는 것을 이미 봤으므로 옆으로
	 * 한 걸음이면 된다.
	 */
	static final int CROSS_GAP_TICKS = 30;
	/** 마지막 십자가 터진 뒤 파티클이 남는 시간(틱). <b>잔류가 아니다.</b> */
	static final int CROSS_AFTERGLOW_TICKS = 10;

	/** 선의 폭(칸). <b>사람이 정한 값이다.</b> */
	static final double CROSS_WIDTH = 3.0;
	/** 그 반폭. 중심선에서 이만큼까지가 안이다. */
	static final double CROSS_HALF_WIDTH = CROSS_WIDTH / 2.0;

	/**
	 * 선이 뻗는 거리(칸). <b>사람이 정하지 않았다</b> — 「아레나를 가로지르는」의 「아레나」다.
	 *
	 * <p>{@code TrialRisks.ARENA_RADIUS}(40)를 쓴다. 새 숫자를 만들지 않았고, 이 저장소가 「섬
	 * 반경」으로 쓰는 값이라 그 밖은 허공이다 — 허공에는 {@link #dot} 이 애초에 점을 찍지 않는다.
	 *
	 * <p>{@code DragonLastStandZone.START_RADIUS}(42, 기둥 줄)를 쓰지 않은 까닭은 그쪽이
	 * <b>「처음에는 아무도 밖이 아니어야」</b>를 위한 값이라 <b>섬 밖 허공까지 걸친다</b>는 것을
	 * 그 상수가 스스로 적어 두었기 때문이다.
	 */
	static final double CROSS_REACH = TrialRisks.ARENA_RADIUS;

	/**
	 * ⚠ 세 번의 각도(도). <b>사람이 셋 중에 이것을 골랐다.</b>
	 *
	 * <p>{@code +} → {@code ×} → <b>22.5도 어긋난 십자</b>다. 근거는 십자가 <b>90도 대칭</b>이라는
	 * 것이다 — 「대각선으로 십자 긋고 다시 대각선으로」를 글자대로 읽으면 {@code 45 + 45 = 90} 이라
	 * <b>셋째가 첫째와 같은 모양</b>이 되어 세 번이 두 번이 된다. 22.5 는 그 사이를 다시 반으로
	 * 갈라 <b>세 번이 다 다르게</b> 만드는 값이다.
	 *
	 * <p>⚠ <b>배열 길이가 {@link #CROSS_ROUNDS} 와 같아야 한다.</b> 시험이 붙들고 있다.
	 */
	static final double[] CROSS_ANGLES = {0.0, 45.0, 22.5};

	/**
	 * 십자의 가장자리 선에 점을 찍는 간격(칸). {@link #MARK_GAP}(0.5)보다 성기다.
	 *
	 * <p>선 하나가 지름 <b>80칸</b>이라 0.5 간격이면 가장자리 넷에 640점이고 그것만으로 예산을
	 * 넘긴다. 1.0 이면 320점이고 {@link #CROSS_MARK_STRIDE} 로 나눠 54점이 된다. 곧은 선은
	 * 고리와 달리 점 사이가 1칸이어도 <b>선으로 읽힌다</b> — 고리에 0.5 가 필요했던 까닭은
	 * 곡률이었다({@code TrialWarning.ringGap}).
	 */
	static final double CROSS_MARK_GAP = 1.0;

	/**
	 * 십자 가장자리를 <b>몇 틱에 나눠</b> 그릴지. 상시 번개와 <b>같은 값이자 같은 근거</b>다
	 * (먼지 수명 최소 8틱).
	 *
	 * <p>⚠ 둘째·셋째 예고가 <b>30틱</b>뿐이라 처음 여섯 틱이 성긴 것이 예고의 <b>1/5</b> 이다.
	 * 그래도 6 을 쓰는 것은 각도가 <b>이미 예측되는 것</b>이라 첫 여섯 틱에 선을 다 읽어야 할
	 * 이유가 없기 때문이고, 첫 십자는 예고가 60틱이라 넉넉하다.
	 */
	static final int CROSS_MARK_STRIDE = TrialEndRain.MARK_MAX_STRIDE;

	/** 터지는 틱에 갈라짐을 그리는 간격(칸). 표식이 아니라 연출이라 성기다. */
	private static final double CROSS_FLASH_GAP = 2.0;

	// ------------------------------------------------------------------ ⑤ 상시 번개 (패턴이 아니다)

	/**
	 * 한 볼리의 번개 개수. <b>사람이 5 → 10 으로 올렸다.</b>
	 *
	 * <p>「낙뢰」({@code sharedfate:lightning_storm})가 <b>한 번에 열 곳</b>이라 이제 개수까지 같다.
	 */
	static final int LIGHTNING_COUNT = 10;
	/**
	 * 한 곳의 반경(칸). <b>사람이 정한 값이다</b> — 「낙뢰」의 3칸보다 15% 작다.
	 *
	 * <p>{@code TrialCatalog} 의 {@code sharedfate:lightning_storm} 이 3.0 을 들고 있고
	 * {@code 3.0 × 0.85 = 2.55} 다. {@code DragonLastStandPatternsTest} 가 그 관계를 붙든다.
	 */
	static final double LIGHTNING_RADIUS = 2.55;
	/**
	 * 자리를 굴리는 반경(칸). <b>9.45 이고 새 숫자가 아니다</b> —
	 * {@code 마지막 안전지대 12 − 반경 2.55} 다.
	 *
	 * <p>이렇게 적으면 <b>「고리가 지대 밖으로 삐져나가지 않는다」가 구조적으로 참</b>이다. 지대의
	 * 마지막 크기를 누가 고쳐도 여기가 따라간다. ⚠ 보더는 정사각형이라 모서리가 중앙에서 17칸이지만
	 * <b>내접원(12)</b>으로 재야 안전하다({@link DragonLastStandZone} 의 설명).
	 *
	 * <p>전에는 「가운데 하나 + 90도씩 벌린 넷」이라는 <b>굳은 배치</b>였고 그것이 겹침 없음을
	 * 증명했다. 열 곳이 되면서 그 증명이 불가능해졌다 — 아래 「겹침을 허용한다」를 볼 것.
	 */
	static final double LIGHTNING_FIELD_RADIUS =
			DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1] - LIGHTNING_RADIUS;
	/** 예고(틱). 3초. {@code TrialWarning.TICKS_SIDESTEP}(30틱, 옆으로 비킬 시간)의 두 배다. */
	static final int LIGHTNING_WARN_TICKS = 60;
	/**
	 * 내리친 뒤 번개 엔티티가 스스로 그리는 시간(틱). 그 동안 바닥에는 아무 표식도 없다.
	 *
	 * <p>⚠ <b>실행 코드가 이 값을 읽지 않는다.</b> 번개 엔티티는 우리가 세지 않아도 제 수명을
	 * 다하고 사라지므로 잴 것이 없다. 남겨 둔 이유는 <b>{@link #LIGHTNING_PERIOD_TICKS} 가
	 * 「바닥이 깨끗한 틈」을 셀 때 쓰는 유일한 숫자</b>이기 때문이고, {@code 예고 60 + 여운 10 =
	 * 70틱} 이 그 셈이다. 시험이 {@code 예고 + 여운 < 주기} 를 붙들고 있다 — 지우면 「번개가
	 * 끊기지 않아 부채꼴 테두리가 묻힌다」를 재는 자가 사라진다.
	 *
	 * <p>전에는 패턴 길이({@code LIGHTNING_FIVE.durationTicks()})의 뒷부분이었다. 번개가 패턴
	 * 풀에서 빠지면서 그 쓰임이 없어졌다.
	 */
	static final int LIGHTNING_AFTER_TICKS = 10;
	/**
	 * 볼리 사이 주기(틱). <b>6초 — 「낙뢰」와 같은 값이다.</b>
	 *
	 * <h2>사람이 정하지 않은 숫자다 — 왜 120 인가</h2>
	 *
	 * <p>사람이 정한 것은 「패턴이 아니라 배경」과 「열 곳」뿐이다. 주기는 <b>새로 만들지 않았다</b> —
	 * 「낙뢰」가 이미 <b>주기 120틱 · 한 번에 열 곳 · 피해 35</b> 이고, 개수가 10 이 된 지금 이
	 * 배경은 반경(2.55 대 3)과 놓는 자리(드래곤 주변 대 아레나 전체)만 다른 <b>같은 카드</b>다.
	 * 실제로 시험 서버에서 맞아 보고 정해진 값이 그쪽에 있으므로 그것을 그대로 쓴다.
	 *
	 * <p>값으로도 맞는다.
	 *
	 * <ul>
	 *   <li><b>「쉬는 틈 없이 계속 돈다」</b> — 한 볼리가 예고 60 + 여운 10 = <b>70틱</b>을 쓰므로
	 *       120틱 주기에서 <b>바닥이 깨끗한 시간이 50틱(2.5초)뿐</b>이다. 번개는 사실상 언제나 떠 있다</li>
	 *   <li><b>「너무 잦으면 다른 패턴을 볼 여유가 없다」</b> — 그 50틱이 <b>날개 고리와 부채꼴
	 *       테두리만 떠 있는 시간</b>이다. 70틱으로 붙여 돌리면 노란 고리가 끊기지 않아 빨간
	 *       테두리가 그 위에 묻힌다</li>
	 *   <li><b>연달아 맞아도 세 발까지 시간이 있다</b> — 무장 기준 한 발 6.93 이라 세 발이 20.79 로
	 *       전멸인데, 세 발 사이가 <b>240틱(12초)</b> 이다. 그 사이에 자연 회복도 돌고 무엇보다
	 *       세 번 다 3초 예고를 무시해야 한다</li>
	 * </ul>
	 *
	 * <p>115초 페이즈에 <b>19 볼리</b>가 돈다({@code (2300 − 60) ÷ 120}).
	 */
	static final int LIGHTNING_PERIOD_TICKS = 120;
	/**
	 * 노란 고리 한 바퀴를 <b>몇 틱에 나눠</b> 그릴지. {@code TrialEndRain} 의 상한을 그대로 쓴다.
	 *
	 * <h2>이 값이 없으면 예산이 그 자리에서 넘친다</h2>
	 *
	 * <p>반경 2.55 의 고리는 {@code TrialWarning.ringPoints} 가 하한 40점을 주므로 열 곳이면
	 * <b>한 틱에 400점</b>이다. 번개가 패턴이었을 때는 그것으로 끝이었지만(패턴은 하나만 돈다)
	 * 이제 <b>부채꼴 예고(202점)와 같은 틱에 돈다</b> — 합이 602점이라 예산 440 을 넘는다.
	 *
	 * <p>{@code TrialWarning.markGround} 의 나눠 그리기로 {@code ceil(40 ÷ 6) = 7} 점씩 여섯 틱에
	 * 채우면 <b>70점</b>이다. 6 인 까닭은 <b>먼지 파티클 수명이 최소 8틱</b>이라는 것이고
	 * ({@code TrialWarning.markGround} 의 「stride 는 8보다 작아야 한다」), 「종말의 비」가 같은
	 * 근거로 같은 값을 들고 있으므로 <b>그쪽에서 가져온다</b> — 숫자를 여기 따로 적으면 파티클을
	 * 바꿀 때 한쪽만 따라간다.
	 *
	 * <p>대가는 <b>처음 여섯 틱 동안 고리가 성기다</b>는 것이다. 예고가 60틱이라 묻힌다.
	 */
	static final int LIGHTNING_MARK_STRIDE = TrialEndRain.MARK_MAX_STRIDE;

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

	/**
	 * 상시 번개의 시계. <b>{@link DragonLastStandZone} 의 {@code drivingSince} 와 같은 모양</b>이다.
	 *
	 * <ul>
	 *   <li>{@code lightningOwner} — 지금 몰고 있는 최후의 저항이 시작한 틱. 바뀌면 처음부터 다시 센다</li>
	 *   <li>{@code lightningNextVolleyAt} — 다음 볼리를 열 시각</li>
	 *   <li>{@code lightningStrikeAt} — 열려 있는 볼리가 내리칠 시각. {@link Long#MIN_VALUE} 면 볼리가 없다</li>
	 *   <li>{@code lightningSpots} — 그 볼리의 열 곳. 예고가 도는 동안 자리가 움직이면 예고가 아니다</li>
	 * </ul>
	 *
	 * <p>⚠ 주기를 {@code now % 120} 으로 세지 않는 것은 <b>틱을 건너뛰어도 내리치게</b> 하기
	 * 위해서다. 예고를 3초 보여 준 볼리가 렉 한 번으로 조용히 사라지면 그것이 가장 나쁘다 —
	 * 「종말의 비」가 {@code nextVolleyAt} 을 들고 있는 것과 같은 까닭이다.
	 */
	private static long lightningOwner = Long.MIN_VALUE;
	private static long lightningNextVolleyAt;
	private static long lightningStrikeAt = Long.MIN_VALUE;
	private static List<Vec3> lightningSpots = List.of();

	private DragonLastStandPatterns() {
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. 지난 판의 조준·자리·시계가 새 판으로 새지 않게 한다.
	 *
	 * <p>⚠ <b>{@link DragonLastStandConePanel#drop()} 이 여기 있는 것이 중요하다.</b> 이 메서드는
	 * {@code SERVER_STOPPED} 에서도 불려 월드를 만질 수 없는데, 빨간 면은 <b>파티클이 아니라
	 * 개체</b>라 지우지 않으면 남는다. 그쪽이 개체를 들고 있으므로 월드 없이 지울 수 있다.
	 */
	static void clearState() {
		coneAimedFor = Long.MIN_VALUE;
		coneYaw = 0.0F;
		lightningOwner = Long.MIN_VALUE;
		lightningNextVolleyAt = 0L;
		lightningStrikeAt = Long.MIN_VALUE;
		lightningSpots = List.of();
		DragonLastStandConePanel.drop();
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
		// default 를 넣지 말 것. 패턴을 셋째로 늘리고 그리는 것을 안 붙이면 여기서 빌드가 깨져야
		// 한다 — 안 깨지면 드래곤이 그 패턴을 고른 동안 아무것도 안 한다.
		switch (pattern) {
			case WING_BEAT -> wingBeat(end, dragon, members, at, now, (int) step);
			case CONE_BREATH -> coneBreath(end, dragon, members, at, now, (int) step);
			case VOID_SUCTION -> voidSuction(end, dragon, members, (int) step);
			case CROSS_FISSURE -> crossFissure(end, dragon, members, (int) step);
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
	 * <h2>부채꼴을 어떻게 그렸는가 — 네 갈래다</h2>
	 *
	 * <ol>
	 *   <li><b>빨간 테두리</b>(규약의 {@code DEADLY}, 「서 있으면 죽는다」) — 가장자리 두 줄 ·
	 *       사거리 호 · 가운데 호 하나. 이것이 <b>경계를 말하는 유일한 갈래</b>다</li>
	 *   <li><b>흰 벽</b>({@code CRIT})을 가장자리 두 줄에 세운다. 「착지 충격」이 쓰는 그 수법이고,
	 *       파티클 개수를 0 으로 보내면 뒤 값이 속도로 읽혀 <b>점을 한 개도 안 늘리고</b> 사람 키만 한
	 *       벽이 선다({@code TrialLandingShock.EDGE_RISE_SPEED} 에 그 계산이 있다). 부채꼴 안에 서
	 *       있는 사람은 바닥 선을 거의 못 보므로(시선과 나란하다) 이 벽이 「여기서부터 안전」을
	 *       말하는 갈래다. <b>면이 생겨도 이 벽을 걷지 않았다</b> — 근거가 한 줄도 바뀌지 않는다</li>
	 *   <li><b>빨간 투명 면</b>({@link DragonLastStandConePanel}) — 사람이 <b>「모든바닥이
	 *       위험지대라고 알수잇게」</b>라고 해서 바닥을 통째로 덮는다. 파티클이 아니라 디스플레이
	 *       개체라 <b>점 예산과 무관</b>하고, 대신 <b>개체라서 지워야 한다</b> —
	 *       {@link #fireCone} 이 터지는 틱에 지우고 {@link #clearState} 가 판이 끝날 때 지운다</li>
	 *   <li><b>불꽃</b>({@link #burnCone}) — 터지는 그 틱에만. 사람이 <b>「브레스쏠떄 불타는
	 *       이펙트가 있으면좋겟어」</b>라고 한 것이고, ⚠ <b>불을 실제로 붙이지 않는다</b>(아래)</li>
	 * </ol>
	 *
	 * <h2>⚠ 빨간 면은 터지는 틱에 사라진다 — 잔류처럼 보이면 배울 것이 없다</h2>
	 *
	 * <p>브레스가 터진 뒤의 바닥은 <b>안전하다</b>(피해는 그 한 틱뿐이다). 빨간 면이 불꽃 20틱 동안
	 * 더 남아 있으면 <b>이미 안전한 자리가 위험해 보인다</b> — 「연쇄 포격」이 터진 고리를 그 틱에
	 * 지우는 것과 같은 규칙이고({@code DragonFireBarrage} 의 「빨간 고리도 그 틱에 사라진다」),
	 * 빨간 테두리도 이미 그렇게 되어 있다({@link #markCone} 은 예고 중에만 불린다).
	 */
	private static void coneBreath(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			long at, long now, int step) {
		Vec3 apex = dragon.position();
		if (coneAimedFor != at) {
			coneAimedFor = at;
			coneYaw = aimYaw(apex, members, dragon.getYRot());
			// 방향이 정해진 그 틱에 면을 세운다. 자리가 예고 중에 바뀌지 않는 것이
			// 「즉사 허용」의 조건이므로 면도 한 번만 세우면 된다.
			DragonLastStandConePanel.raise(end, apex, coneYaw);
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

	/**
	 * 예고. 바닥 테두리와 흰 벽을 매 틱 다시 그리고, 층이 바뀌는 틱과 <b>1초마다</b> 소리를 낸다.
	 *
	 * <h2>소리가 두 겹이다 — 층 소리와 충전음</h2>
	 *
	 * <p>층 소리({@link TrialWarning#soundFor})는 <b>남겼다.</b> 그것이 이 판의 공용 경고라 사람이
	 * 카드마다 새 신호를 배우지 않는 근거이고({@link TrialWarning} 클래스 설명), 다른 카드와 같은
	 * 자리에서 같은 소리가 나야 한다.
	 *
	 * <p>그 위에 <b>충전음</b>을 얹는다. 왜 층 소리만으로는 안 들렸는지는
	 * {@link #CONE_CHARGE_TICKS} 에 적어 두었다.
	 *
	 * <p>고른 소리는 <b>{@code GHAST_WARN}</b> 이다. 근거가 둘이다.
	 *
	 * <ol>
	 *   <li>⚠ <b>이 판에서 쓰는 어느 소리와도 파일이 겹치지 않는다.</b> 바닐라
	 *       {@code sounds.json} 을 열어 확인했다 — {@code entity.ghast.warn} 이 가리키는 것은
	 *       {@code mob/ghast/charge} 하나이고 이 저장소의 어느 카드도 그 파일을 쓰지 않는다.
	 *       ⚠ <b>이름으로 고르면 걸린다</b>: {@code item.firecharge.use} 를 쓸 뻔했는데 그것이
	 *       가리키는 파일이 {@code mob/ghast/fireball4} 로 <b>{@code ENDER_DRAGON_SHOOT}(바로
	 *       아래 터지는 소리)·{@code GHAST_SHOOT}(기둥 화염구)와 글자 하나까지 같다.</b>
	 *       「종말의 비」가 {@code DRAGON_FIREBALL_EXPLODE} 로 이미 한 번 걸린 함정이다</li>
	 *   <li><b>뜻이 맞는다.</b> 가스트가 화염구를 모을 때 내는 소리라 「불을 모으고 있고 곧
	 *       쏜다」가 그대로다. 그리고 터지는 소리가 {@code mob/ghast/fireball4} 이므로
	 *       <b>「모으는 소리 → 쏘는 소리」가 바닐라 가스트와 같은 한 쌍</b>이 된다</li>
	 * </ol>
	 *
	 * <p>26.3 에서 {@code SoundEvents.GHAST_WARN} 은 맨 {@code SoundEvent} 다({@code javap} 로
	 * 확인했다). {@link TrialWarning#playEach} 의 그 형태가 레지스트리에서 감싸 준다.
	 */
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
		if (step % CONE_CHARGE_TICKS == 0) {
			// playEach 다. 팀원 루프에서 level.playSound 를 부르면 모여 있는 넷이 네 겹으로 듣는다.
			TrialWarning.playEach(end, members, SoundEvents.GHAST_WARN, 0.9F, chargePitch(step));
		}
		// 입에서 불씨가 모인다. 「어디서 나올 것인가」를 드래곤 쪽에서도 말한다.
		end.sendParticles(ParticleTypes.SMALL_FLAME, true, false,
				apex.x, apex.y + 2.0, apex.z, 4, 0.6, 0.4, 0.6, 0.01);
	}

	/**
	 * 그 틱의 충전음 음높이. 낮은 데서 시작해 <b>마지막 한 번이 가장 높다.</b>
	 *
	 * <p>나누는 것이 {@code CONE_WARN_TICKS} 가 아니라 <b>마지막으로 울리는 틱</b>
	 * ({@code 100 − 20 = 80})이다. 예고 길이로 나누면 마지막 울림이 0.8 진행에 머물러
	 * {@link #CONE_CHARGE_PITCH_HIGH} 가 <b>한 번도 나지 않는다</b> — 「끝까지 올라갔다」가 안 들린다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 다섯 번을 직접 굴려 본다.
	 */
	static float chargePitch(int step) {
		int last = CONE_WARN_TICKS - CONE_CHARGE_TICKS;
		if (last <= 0) {
			return CONE_CHARGE_PITCH_HIGH;
		}
		float progress = Math.max(0.0F, Math.min(1.0F, (float) step / last));
		return CONE_CHARGE_PITCH_LOW
				+ (CONE_CHARGE_PITCH_HIGH - CONE_CHARGE_PITCH_LOW) * progress;
	}

	/**
	 * 터진다. <b>피해는 이 한 틱에 한 사람당 한 번</b>이다.
	 *
	 * <p>이 틱에 <b>빨간 면을 지운다.</b> 터진 뒤의 바닥은 안전하므로 한 틱이라도 더 남기면
	 * 표식이 거짓말을 한다 — 까닭은 {@link #coneBreath} 에 적어 두었다.
	 */
	private static void fireCone(ServerLevel end, List<ServerPlayer> members, Vec3 apex) {
		DragonLastStandConePanel.drop();
		sprayCone(end, apex, 3);
		burnCone(end, apex);
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
	 * 터지는 <b>그 틱</b>에 부채꼴 바닥이 불타는 모습. <b>판정과 무관한 연출이다.</b>
	 *
	 * <h2>⚠ 불을 실제로 붙이지 않는다 — 세 가지를 하지 않는다</h2>
	 *
	 * <ul>
	 *   <li><b>블록을 놓지 않는다.</b> {@code fire} 를 한 칸이라도 놓으면 그 불이 사람이 쌓은 발판을
	 *       태워 <b>다음 전투의 지형이 달라진다</b> — 이 파일이 「블록을 한 칸도 건드리지
	 *       않는다」를 지키는 까닭과 같다. 하이트맵을 <b>읽는</b> 것만 한다</li>
	 *   <li><b>사람에게 불을 붙이지 않는다.</b> {@code setRemainingFireTicks} 는 <b>피해가 계속
	 *       들어오는 장치</b>라 「잔류 없음」을 그 자리에서 깬다. 공유 체력이라 넷이 함께 타면
	 *       초당 넷이 합산된다</li>
	 *   <li><b>장판({@code AreaEffectCloud})을 만들지 않는다.</b> 그것이 곧 잔류다</li>
	 * </ul>
	 *
	 * <p>남는 것은 <b>파티클 한 종류</b>다. {@code FLAME} 을 고른 것은 26.3 {@code FlameParticle} 의
	 * 수명이 {@code (int)(8.0 / (굴림 × 0.8 + 0.2))} 곧 <b>8~40틱</b>이라, <b>한 틱만 뿌려도 눈에는
	 * 0.4~2초 타오르는 것으로 보이기</b> 때문이다. 매 틱 뿌리면 그것이 잔류처럼 보이고,
	 * 안 뿌리면 「불탔다」가 한 틱에 끝나 아무것도 안 보인다.
	 *
	 * <p>{@code LAVA} 를 쓰지 않은 것은 그쪽이 <b>방울이 튀는 모양</b>이라 「용암이 고였다」로
	 * 읽히기 때문이다 — 이 패턴은 고인 것을 남기지 않는다.
	 *
	 * <p>허공에는 찍지 않는다. 찍으면 불꽃이 까마득한 아래에 떠 「저기가 바닥이다」라고 거짓말한다.
	 */
	private static void burnCone(ServerLevel end, Vec3 apex) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		double half = Math.toRadians(CONE_DEGREES / 2.0);
		double middle = Math.atan2(Math.sin(Math.toRadians(coneYaw)),
				-Math.cos(Math.toRadians(coneYaw)));
		for (double radius = FLAME_RING_STEP; radius <= CONE_RANGE; radius += FLAME_RING_STEP) {
			int points = arcPoints(radius, FLAME_ARC_GAP);
			for (int index = 0; index <= points; index++) {
				double angle = middle - half + (half * 2.0 * index) / points;
				double x = apex.x + Math.sin(angle) * radius;
				double z = apex.z + Math.cos(angle) * radius;
				int surface = ground.surfaceAt(end, x, z);
				if (surface == TrialEnderPulse.NO_GROUND) {
					continue;
				}
				end.sendParticles(ParticleTypes.FLAME, true, false,
						x, surface + GROUND_OFFSET, z, 3, 0.5, 0.1, 0.5, 0.03);
			}
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

	// ------------------------------------------------------------------ ③ 공허 흡입

	/**
	 * 공허 흡입 — <b>드래곤 발밑</b>에 검은 원 · 반경 4칸 · 3초 예고 → 5초 흡입 → 터짐 ·
	 * 피해 {@code DragonLastStand.VOID_SUCTION_DAMAGE}(35) · <b>잔류 없음.</b>
	 *
	 * <h2>⚠ 원은 <b>드래곤에게</b> 있다 — 무작위 자리가 아니다</h2>
	 *
	 * <p>사람이 한 번 고쳤다. 처음 말은 「그작은원안에 빨려들어가고」였고 그것을 아레나 어딘가의
	 * 작은 원으로 읽었는데, 사람이 <b>「흡입은 드래곤에 검은원이고 드래곤이 빨아드리는거임」</b>
	 * 이라고 못박았다. 그래서 중심이 <b>드래곤을 못박아 둔 자리</b>이고 여기서 자리를 굴리지
	 * <b>않는다.</b>
	 *
	 * <p>그것이 이 패턴의 뜻이다. 이 페이즈의 핵심이 「드래곤 머리에 붙어 때리기」인데
	 * ({@code DragonLastStand} 의 「닿기 쉬워지는 것」) 이것이 나오면 <b>때리던 자리가 곧 위험한
	 * 자리</b>가 된다 — <b>붙어 있던 사람을 그 자리에서 쫓아낸다.</b>
	 *
	 * <h2>세 토막이다</h2>
	 *
	 * <table border="1">
	 *   <caption>{@code step} 으로 읽는 세 토막</caption>
	 *   <tr><th>{@code step}</th><th>무엇을 하는가</th></tr>
	 *   <tr><td>{@code 0 ~ 59}</td><td><b>예고.</b> 검은 원과 빨간 경계를 그린다. <b>당기지 않는다</b></td></tr>
	 *   <tr><td>{@code 60 ~ 159}</td><td><b>흡입.</b> 계속 그리면서 매 틱 당긴다</td></tr>
	 *   <tr><td>{@code 160}</td><td><b>터짐.</b> 원 안이면 피해 한 번. 표식은 이 틱에 사라진다</td></tr>
	 *   <tr><td>{@code 161 ~ 179}</td><td>파티클만. 바닥은 이미 안전하다</td></tr>
	 * </table>
	 *
	 * <p>⚠ <b>예고 중에 당기지 않는 것이 「달리면 벗어난다」의 절반이다.</b> 3초는 검은 원을 보고
	 * 방향을 정하는 시간이고, 그 3초에 달리면 17칸을 간다 — {@link #SUCK_SPRINT_RATIO} 의 표를 볼 것.
	 *
	 * <h2>피해원은 {@code lightningBolt()} 다</h2>
	 *
	 * <p>사람이 적어 둔 것이 <b>「피해 35(무장 기준 6.9)」</b>이므로 피해원이 그 셈을 만들어야
	 * 한다 — {@code GearedDamage} 로 풀면 {@code lightningBolt} 가 <b>6.93</b> 이고
	 * {@code explosion(null, null)} 은 10.4 라 사람이 적은 수와 다르다. 곧 <b>값이 피해원을
	 * 정했다.</b>
	 *
	 * <p>이름이 어색한 것은 알고 고른 것이다. 이 저장소는 {@code lightningBolt()} 를 <b>「하드 곱이
	 * 안 걸리고 방어도는 듣는 피해원」</b>으로 쓰고 있고 실제로 번개가 아닌 자리에서도 쓴다 —
	 * {@code DragonLastStandZone.punish}(안전지대 밖 피해)가 그 선례다. {@code magic} 을 안 쓴
	 * 까닭은 부채꼴 브레스와 같다: {@code #bypasses_armor} 라 <b>무장해도 줄지 않아</b> 이 판의
	 * 모든 셈이 서 있는 「무장 기준」과 성질이 다르다.
	 *
	 * <h2>⚠ 브레스처럼 잠그지 않았다 — 축소와 겹쳐도 된다</h2>
	 *
	 * <p>브레스의 「축소 중 금지」는 <b>피할 곳이 두 번 사라지기</b> 때문에 붙었다. 흡입은
	 * <b>피하는 방향이 바깥</b>이고 보더도 <b>바깥에서 안으로</b> 오므로 위협이 겹치지 않는다 —
	 * 그리고 벗어나야 하는 것이 반경 4 뿐인데 지대의 가장 좁은 반변이 12 라 <b>언제나 8칸이
	 * 남는다.</b> 게다가 이 카드는 즉사가 아니다(무장 기준 6.93, 세 대에 전멸).
	 *
	 * <p>축소가 사람을 지대 밖에 두고 지나간 경우에는 흡입이 <b>도로 안으로 데려온다</b> —
	 * 방향이 안쪽이라 그렇다.
	 *
	 * @param step 이 패턴이 시작한 뒤 지난 틱
	 */
	private static void voidSuction(ServerLevel end, EnderDragon dragon,
			List<ServerPlayer> members, int step) {
		Vec3 center = dragon.position();
		if (step < SUCK_WARN_TICKS + SUCK_PULL_TICKS) {
			markSuck(end, center, step);
			warnSuckSound(end, members, step);
			if (step >= SUCK_WARN_TICKS) {
				pullSuck(end, members, center, step);
			}
			return;
		}
		if (step == SUCK_WARN_TICKS + SUCK_PULL_TICKS) {
			burstSuck(end, members, center);
			return;
		}
		// 잔류가 아니다 — 파티클만 남고 피해는 위의 한 틱에 끝났다.
		sprayBurst(end, center, 1);
	}

	/**
	 * 검은 원과 빨간 경계.
	 *
	 * <p>속은 {@link #SUCK_MARK_STRIDE} 로 나눠 채우고 <b>경계 고리는 매 틱 전부</b> 그린다.
	 * 경계가 성기면 「어디까지가 아픈가」가 흐려지는데 이 원은 반경이 4밖에 안 돼 고리 한 바퀴가
	 * 51점뿐이다 — 나눌 이유가 없다.
	 */
	private static void markSuck(ServerLevel end, Vec3 center, int step) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		ParticleOptions black = TrialWarning.dust(SUCK_BLACK);
		int index = 0;
		for (double radius = MARK_GAP; radius < SUCK_RADIUS; radius += MARK_GAP) {
			int points = suckFillPoints(radius);
			for (int point = 0; point < points; point++) {
				// 나눠 그리기를 고리마다 따로 세지 않는다. 고리마다 세면 점이 적은 안쪽 고리가
				// 매 틱 같은 자리만 찍혀 바람개비처럼 돈다 — 「원 전체에서 몇 번째 점인가」로 센다.
				if (Math.floorMod(index++ - step, SUCK_MARK_STRIDE) != 0) {
					continue;
				}
				double angle = (Math.PI * 2.0 * point) / points;
				dot(end, ground, black, center.x + Math.cos(angle) * radius,
						center.z + Math.sin(angle) * radius, 0.0);
			}
		}
		// 경계는 규약의 빨강이다. 「서 있으면 죽는다」를 말하는 갈래는 여기 하나다.
		TrialWarning.markGround(end, groundedCenter(end, ground, center), SUCK_RADIUS,
				TrialWarning.dust(TrialWarning.Colors.DEADLY));
	}

	/** 속을 채우는 고리 한 겹의 점 수. 값에서 세는 자리와 그리는 자리가 같은 식이어야 한다. */
	static int suckFillPoints(double radius) {
		if (!(radius > 0.0)) {
			return 1;
		}
		return Math.max(1, (int) Math.ceil((Math.PI * 2.0 * radius) / MARK_GAP));
	}

	/**
	 * 경계 고리를 그릴 중심. <b>바닥 높이로 내려 잡는다.</b>
	 *
	 * <p>{@code TrialWarning.markGround} 는 중심의 {@code y} 를 그대로 쓰므로 드래곤 발밑을 그냥
	 * 넘기면 고리가 <b>포디움 높이에 떠서</b> 섬 표면에 선 사람 눈에는 허공에 뜬 고리가 된다
	 * ({@link DragonLastStandDome#baselineY} 의 「섬 표면보다 네 칸 높다」). 표식이 거짓말하지 않게
	 * 그 자리의 지표로 내린다 — 땅이 없으면 원래 값을 쓴다(그 경우 그릴 자리가 애초에 없다).
	 */
	private static Vec3 groundedCenter(ServerLevel end, TrialEnderPulse.Ground ground, Vec3 center) {
		int surface = ground.surfaceAt(end, center.x, center.z);
		if (surface == TrialEnderPulse.NO_GROUND) {
			return center;
		}
		return new Vec3(center.x, surface, center.z);
	}

	/**
	 * 예고와 흡입의 소리.
	 *
	 * <p>층 소리({@link TrialWarning#soundFor})는 예고 3초 동안만이다 — 그것이 이 판의 공용
	 * 경고라 사람이 카드마다 새 신호를 배우지 않는 근거다({@link TrialWarning} 클래스 설명).
	 *
	 * <p>흡입이 시작되면 그 위에 <b>{@code BREEZE_INHALE}</b> 을 0.5초마다 얹는다. 「빨려들어가고
	 * 있다」는 <b>되풀이</b>로만 말할 수 있고 한 번 울리는 소리로는 못 한다(부채꼴의 충전음이 같은
	 * 근거로 있다).
	 *
	 * <p>⚠ <b>{@code sounds.json} 을 열어 고른 소리다.</b> {@code entity.breeze.inhale} 이
	 * 가리키는 파일은 {@code mob/breeze/inhale1}·{@code inhale2} 이고 <b>이 저장소의 어느 카드도
	 * 그 파일을 쓰지 않는다</b>(「엔더폭풍」이 쓰는 {@code BREEZE_JUMP}·
	 * {@code BREEZE_WIND_CHARGE_BURST} 와도 파일이 다르다). 이름으로 고르면 걸린다 — 이 저장소가
	 * 두 번 걸렸다({@code DRAGON_FIREBALL_EXPLODE} 가 일반 폭발음과 같은 파일이었고
	 * {@code FIRECHARGE_USE} 가 {@code ENDER_DRAGON_SHOOT} 과 같은 파일이었다).
	 *
	 * <p>뜻도 맞는다 — 브리즈가 <b>숨을 들이마시는</b> 소리다.
	 */
	private static void warnSuckSound(ServerLevel end, List<ServerPlayer> members, int step) {
		if (step < SUCK_WARN_TICKS) {
			int remaining = SUCK_WARN_TICKS - step;
			TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
			if (stage != null && TrialRisks.stageJustChanged(remaining, SUCK_WARN_TICKS)) {
				for (ServerPlayer member : members) {
					TrialWarning.soundFor(end, member, stage);
				}
			}
			return;
		}
		if ((step - SUCK_WARN_TICKS) % SUCK_BREATH_TICKS != 0) {
			return;
		}
		// playEach 다. 팀원 루프에서 level.playSound 를 부르면 모여 있는 넷이 네 겹으로 듣는다.
		TrialWarning.playEach(end, members, SoundEvents.BREEZE_INHALE, 1.0F, 0.6F);
	}

	/**
	 * 한 틱 당긴다. <b>수평만</b> 당기고 결과 속도에 천장을 씌운다.
	 *
	 * <p>왜 세로를 안 건드리고 왜 천장이 필요한지는 {@link #SUCK_MAX_INWARD} 에 있다. 손이 닿는
	 * 거리는 {@link #SUCK_REACH}(20칸)이고 세기는 거리에 따라 <b>변하지 않는다.</b>
	 *
	 * <p>⚠ <b>세기를 거리로 깎지 않은 것이 일부러다.</b> 깎으면 「달리면 벗어난다」가 거리마다 다른
	 * 답이 된다 — 사람이 한 약속은 하나이고 그것이 <b>어디서나</b> 참이어야 한다. 날개 퍼덕이기가
	 * 거리로 세기를 나눈 것은 그쪽이 <b>「4칸 이내는 약하고」</b>라는 사람의 말을 지키는 것이라
	 * 사정이 다르다.
	 *
	 * <p>닿는 거리 끝(20칸)에서 세기가 뚝 끊기는 것은 남아 있다. 지대가 마지막 반변 12 로 좁혀지면
	 * 그 경계가 <b>지대 밖</b>이라 아무도 그 자리에 없고, 지대가 넓은 앞구간에서만 드러난다.
	 */
	private static void pullSuck(ServerLevel end, List<ServerPlayer> members, Vec3 center,
			int step) {
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			double dx = member.getX() - center.x;
			double dz = member.getZ() - center.z;
			double from = Math.sqrt(dx * dx + dz * dz);
			if (from > SUCK_REACH || from < 1.0E-4) {
				// 정확히 겹쳐 있으면 「안쪽」이 없다. 방향을 지어내지 않는다.
				continue;
			}
			double outX = dx / from;
			double outZ = dz / from;
			Vec3 motion = member.getDeltaMovement();
			double pull = suckStep(motion.x * outX + motion.z * outZ);
			if (!(pull > 0.0)) {
				continue;
			}
			// 세로 속도는 읽어서 그대로 돌려놓는다. 위로 당기면 사람이 들려 공중 감쇠에 들어가고
			// 그때는 달려도 못 벗어난다 — SUCK_MAX_INWARD 를 볼 것.
			member.setDeltaMovement(motion.x - outX * pull, motion.y, motion.z - outZ * pull);
			// 켜지 않으면 서버 혼자 당긴 것이 되어 다음 틱에 제자리로 되돌아간다.
			member.syncVelocity = true;
			if (step % SUCK_BREATH_TICKS == 0) {
				// 끌려가는 모습. 사람 발밑에서 흐르는 재다.
				end.sendParticles(ParticleTypes.ASH, true, false,
						member.getX(), member.getY() + 0.1, member.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
			}
		}
	}

	/**
	 * 이 틱에 실제로 더할 값(칸/틱). <b>월드 없이 답이 정해지는 계산이라 시험이 직접 굴린다.</b>
	 *
	 * <p>천장은 <b>결과 속도</b>에 걸린다 — 안쪽 속도가 이미 {@link #SUCK_MAX_INWARD} 면 0 을
	 * 돌려주고 그 사이면 천장까지만 더한다. 그래서 바닥·공중·얼음이 <b>같은 세기</b>가 된다.
	 *
	 * @param outwardSpeed 지금 속도의 <b>바깥쪽</b> 성분. 안쪽으로 가고 있으면 음수다
	 */
	static double suckStep(double outwardSpeed) {
		double room = SUCK_MAX_INWARD + outwardSpeed;
		if (!(room > 0.0)) {
			return 0.0;
		}
		return Math.min(SUCK_STEP, room);
	}

	/**
	 * 터진다. <b>피해는 이 한 틱에 한 사람당 한 번</b>이다.
	 *
	 * <p>판정은 {@code TrialRisks.insideMark} 다 — 남의 카드와 같은 자(세로를 보지 않는 원)라야
	 * 「같은 표식은 언제나 같은 결과」가 성립한다.
	 *
	 * <p>소리는 <b>{@code WARDEN_SONIC_BOOM}</b> 이다. ⚠ {@code sounds.json} 을 열어 골랐고
	 * {@code mob/warden/sonic_boom1~4} 는 이 저장소의 어느 카드도 쓰지 않는다.
	 * {@code GENERIC_EXPLODE} 를 쓰지 않은 것은 그 파일({@code random/explode1~4})을 이미 다섯
	 * 자리가 쓰고 있어 <b>「또 폭발이 터졌다」로 묻히기</b> 때문이다 — 구멍이 닫히는 소리는 폭발이
	 * 아니라 <b>내려앉는 소리</b>라야 한다.
	 */
	private static void burstSuck(ServerLevel end, List<ServerPlayer> members, Vec3 center) {
		sprayBurst(end, center, 4);
		TrialWarning.playEach(end, members, SoundEvents.WARDEN_SONIC_BOOM, 1.2F, 0.8F);
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			if (!TrialRisks.insideMark(member.position(), center, SUCK_RADIUS)) {
				continue;
			}
			// 피해원에 실체를 달지 않는다. 실체가 붙으면 바닐라가 스스로 밀어내고, 그 밀기는
			// 날개 퍼덕이기가 지키는 천장을 통째로 지나쳐 간다.
			member.hurtServer(end, end.damageSources().lightningBolt(),
					DragonLastStand.VOID_SUCTION_DAMAGE);
		}
	}

	/** 터질 때와 그 뒤의 파티클. 판정과 무관한 연출이다. */
	private static void sprayBurst(ServerLevel end, Vec3 center, int density) {
		end.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, false,
				center.x, center.y + 0.5, center.z, 1, 0.0, 0.0, 0.0, 0.0);
		end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false,
				center.x, center.y + 0.5, center.z, density * 20, SUCK_RADIUS * 0.5, 0.5,
				SUCK_RADIUS * 0.5, 0.4);
	}

	// ------------------------------------------------------------------ ④ 십자 균열

	/**
	 * 십자 균열 — 폭 3칸 선 넷 · <b>세 번</b> · 각도가 매번 다르다 · 피해
	 * {@code DragonLastStand.CROSS_FISSURE_DAMAGE}(23) · <b>잔류 없음.</b>
	 *
	 * <p>사람 말: <b>「십자균열을 내고 그냥 이름만 균열이지 십자로 긋고 다시 대각선으로 십자긋고
	 * 다시 대각선으로 긋고 3번 반복하는 패턴 단순 피하기」</b>
	 *
	 * <h2>세 번의 시각</h2>
	 *
	 * <table border="1">
	 *   <caption>{@code step} 으로 읽는 세 번</caption>
	 *   <tr><th>{@code step}</th><th>무엇을 하는가</th><th>각도</th></tr>
	 *   <tr><td>{@code 0 ~ 59}</td><td>첫 십자 예고(3초)</td><td>{@code +} (0도)</td></tr>
	 *   <tr><td>{@code 60}</td><td>첫 십자 <b>터짐</b> + 둘째 예고 시작</td><td>→ {@code ×} (45도)</td></tr>
	 *   <tr><td>{@code 90}</td><td>둘째 <b>터짐</b> + 셋째 예고 시작</td><td>→ 22.5도</td></tr>
	 *   <tr><td>{@code 120}</td><td>셋째 <b>터짐</b></td><td></td></tr>
	 *   <tr><td>{@code 121 ~ 129}</td><td>아무것도 없다. 바닥은 이미 안전하다</td><td></td></tr>
	 * </table>
	 *
	 * <p>⚠ <b>터지는 틱과 다음 예고가 시작하는 틱이 같다.</b> 1.5초 사이에 「아무 표식도 없는 틈」을
	 * 두면 사람이 그 1.5초를 쉬는 시간으로 읽고, 그러면 셋을 잇달아 낸 뜻이 없어진다.
	 *
	 * <h2>낙사도 밀기도 없다</h2>
	 *
	 * <p>사람이 <b>「단순 피하기」</b>라고 못박았다. 넉백이 없으므로 이 패턴에는 천장을 걸 것이
	 * 없고, 그래서 {@code TrialEnderStorm.pushDistance}·{@code TrialLandingShock.groundedReach} 를
	 * 부르지 않는다 — <b>부를 이유가 없는 것이지 빠뜨린 것이 아니다.</b>
	 *
	 * <h2>피해원은 {@code explosion(null, null)} 이다</h2>
	 *
	 * <p>사람이 적어 둔 것이 <b>「피해 23(무장 기준 6.8)」</b>이고, {@code GearedDamage} 로 풀면
	 * {@code explosion} 이 <b>6.77</b> 이다({@code lightningBolt} 는 4.56 이라 사람이 적은 수와
	 * 다르다). 곧 여기서도 <b>값이 피해원을 정했다.</b> 마침 {@code explosion} 이 이 판의
	 * 표준이다 — 「연쇄 포격」·「기둥 화염구」·「종말의 비」·「착지 충격」·「엔더폭풍」·부채꼴
	 * 브레스가 모두 그것을 쓴다.
	 *
	 * <h2>⚠ 한 번에 한 대다 — 중심에서는 선 둘이 겹친다</h2>
	 *
	 * <p>십자는 중심에서 두 선이 만나므로 그 자리는 <b>두 선 안</b>이다. 그래도
	 * {@link #insideCross} 가 <b>물음 하나</b>이고 {@link #fireCross} 가 사람마다
	 * {@code hurtServer} 를 <b>한 번</b>만 부르므로 겹침이 곱으로 오지 않는다 — 상시 번개가
	 * {@code break} 한 줄로 지키는 것을 이쪽은 <b>구조로</b> 지킨다.
	 *
	 * <h2>⚠ 브레스처럼 잠그지 않았다 — 축소와 겹쳐도 된다</h2>
	 *
	 * <p>안전한 자리는 선 사이의 <b>사분면</b>이고 그것은 <b>어느 반경에서나 있다</b> — 지대가
	 * 좁아져도 사라지지 않는다. 낙사도 밀기도 없고 즉사도 아니다(무장 기준 6.77, 세 대에 전멸).
	 *
	 * @param step 이 패턴이 시작한 뒤 지난 틱
	 */
	private static void crossFissure(ServerLevel end, EnderDragon dragon,
			List<ServerPlayer> members, int step) {
		Vec3 center = dragon.position();
		int fired = crossFiredRound(step);
		if (fired >= 0) {
			fireCross(end, members, center, CROSS_ANGLES[fired]);
		}
		int pending = crossPendingRound(step);
		if (pending >= 0) {
			warnCross(end, members, center, pending, step);
		}
	}

	/**
	 * {@code round} 번째 십자가 터지는 {@code step}.
	 *
	 * <p>첫 번째가 예고 60틱 뒤이고 그 뒤로는 1.5초씩이다. 월드 없이 답이 정해지므로 시험이 세
	 * 값을 직접 센다.
	 */
	static int crossFireStep(int round) {
		return CROSS_FIRST_WARN_TICKS + Math.max(0, round) * CROSS_GAP_TICKS;
	}

	/** 이 패턴이 차지하는 시간(틱). 마지막이 터진 뒤 여운까지다. */
	static int crossDurationTicks() {
		return crossFireStep(CROSS_ROUNDS - 1) + CROSS_AFTERGLOW_TICKS;
	}

	/** 이 틱에 터지는 십자의 번호. 터지지 않으면 {@code -1}. */
	static int crossFiredRound(int step) {
		for (int round = 0; round < CROSS_ROUNDS; round++) {
			if (step == crossFireStep(round)) {
				return round;
			}
		}
		return -1;
	}

	/**
	 * 이 틱에 <b>예고 중인</b> 십자의 번호. 셋이 다 터진 뒤면 {@code -1}.
	 *
	 * <p>⚠ 터지는 틱({@code step == crossFireStep(r)})에는 <b>다음</b> 번호를 돌려준다. 그 틱에
	 * 표식이 끊기지 않아야 한다 — 클래스 설명의 「터지는 틱과 다음 예고가 시작하는 틱이 같다」.
	 */
	static int crossPendingRound(int step) {
		for (int round = 0; round < CROSS_ROUNDS; round++) {
			if (step < crossFireStep(round)) {
				return round;
			}
		}
		return -1;
	}

	/** 그 번호의 예고 길이(틱). 첫째는 3초, 나머지는 1.5초다. */
	static int crossWarnTicks(int round) {
		return round <= 0 ? CROSS_FIRST_WARN_TICKS : CROSS_GAP_TICKS;
	}

	/**
	 * 예고. 가장자리 선 넷을 {@link #CROSS_MARK_STRIDE} 로 나눠 그리고 층 소리를 낸다.
	 *
	 * <h2>충전음을 얹지 않았다 — 부채꼴과 사정이 다르다</h2>
	 *
	 * <p>부채꼴에 충전음을 얹은 까닭은 층 소리가 <b>5초에 세 번</b>뿐이라 안 들렸다는 것이었다
	 * ({@link #CONE_CHARGE_TICKS}). 여기는 예고가 3초·1.5초·1.5초라 층이 촘촘하게 바뀐다 —
	 * 3초 예고에서 세 번(APPROACH·MARK·IMMINENT), 1.5초 예고에서 두 번(MARK·IMMINENT)이다.
	 * <b>새 신호를 만들지 않는 것이 규약이 있는 이유</b>이므로 필요하지 않으면 만들지 않는다.
	 */
	private static void warnCross(ServerLevel end, List<ServerPlayer> members, Vec3 center,
			int round, int step) {
		markCross(end, center, CROSS_ANGLES[round], step);
		int remaining = crossFireStep(round) - step;
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage == null || !TrialRisks.stageJustChanged(remaining, crossWarnTicks(round))) {
			return;
		}
		for (ServerPlayer member : members) {
			TrialWarning.soundFor(end, member, stage);
		}
	}

	/**
	 * 십자의 가장자리를 바닥에 그린다. <b>선 둘의 양쪽 가장자리, 곧 줄 넷이다.</b>
	 *
	 * <p>폭 3칸이므로 가장자리가 중심선에서 {@link #CROSS_HALF_WIDTH}(1.5)씩 떨어져 있다. 중심선을
	 * 그리지 않는 것은 <b>그것이 위험의 한가운데</b>라 경계를 말하지 않기 때문이고, 가장자리 둘이
	 * 3칸 간격이라 밴드 안에 선 사람도 가까운 쪽을 본다.
	 *
	 * @param baseDeg 이 십자의 기준 각도(도)
	 * @param step    나눠 그리기의 위상. 매 틱 1씩 늘어야 빈자리가 순서대로 메워진다
	 */
	private static void markCross(ServerLevel end, Vec3 center, double baseDeg, int step) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		ParticleOptions deadly = TrialWarning.dust(TrialWarning.Colors.DEADLY);
		int index = 0;
		for (int line = 0; line < 2; line++) {
			double radians = Math.toRadians(baseDeg + line * 90.0);
			// 이 저장소의 앞 방향 규약이다 — (sin, −cos) 이 앞이고 그 수직이 (cos, sin) 이다.
			double alongX = Math.sin(radians);
			double alongZ = -Math.cos(radians);
			double sideX = Math.cos(radians);
			double sideZ = Math.sin(radians);
			for (int edge = -1; edge <= 1; edge += 2) {
				double offX = sideX * CROSS_HALF_WIDTH * edge;
				double offZ = sideZ * CROSS_HALF_WIDTH * edge;
				// 선은 중심을 지나 양쪽으로 뻗는다. 「아레나를 가로지르는」이 그 뜻이다.
				for (double along = -CROSS_REACH; along <= CROSS_REACH; along += CROSS_MARK_GAP) {
					if (Math.floorMod(index++ - step, CROSS_MARK_STRIDE) != 0) {
						continue;
					}
					dot(end, ground, deadly, center.x + alongX * along + offX,
							center.z + alongZ * along + offZ, 0.0);
				}
			}
		}
	}

	/**
	 * 터진다. <b>피해는 이 한 틱에 한 사람당 한 번</b>이다.
	 *
	 * <p>소리는 <b>{@code WARDEN_DIG}</b> 다. ⚠ {@code sounds.json} 을 열어 골랐고
	 * {@code mob/warden/dig} 는 이 저장소의 어느 카드도 쓰지 않는다. 뜻도 맞는다 — 워든이 땅을
	 * 헤집는 소리라 「바닥이 갈라진다」가 그대로다. 공허 흡입의 {@code WARDEN_SONIC_BOOM} 과
	 * <b>파일이 다르므로</b> 두 패턴이 같은 소리로 들리지 않는다.
	 */
	private static void fireCross(ServerLevel end, List<ServerPlayer> members, Vec3 center,
			double baseDeg) {
		flashCross(end, center, baseDeg);
		TrialWarning.playEach(end, members, SoundEvents.WARDEN_DIG, 1.0F, 0.8F);
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			if (!insideCross(member.getX() - center.x, member.getZ() - center.z, baseDeg,
					CROSS_HALF_WIDTH, CROSS_REACH)) {
				continue;
			}
			// 피해원에 실체를 달지 않는다. 실체가 붙으면 바닐라가 스스로 밀어내고, 사람이 정한
			// 것은 「낙사·섬 밖으로 미는 것 없음」이다.
			member.hurtServer(end, end.damageSources().explosion(null, null),
					DragonLastStand.CROSS_FISSURE_DAMAGE);
		}
	}

	/**
	 * 터지는 <b>그 틱</b>에 갈라짐이 보이는 모습. <b>판정과 무관한 연출이다.</b>
	 *
	 * <p>{@code SWEEP_ATTACK} 을 고른 것은 26.3 에서 그것이 <b>납작한 반원 한 장</b>이라 「바닥이
	 * 한 줄로 갈라졌다」로 읽히기 때문이다. {@code EXPLOSION} 을 깔면 폭격과 구별되지 않고,
	 * {@code FLAME} 은 부채꼴 브레스가 이미 쓰고 있어 「불이 지나갔다」로 읽힌다.
	 *
	 * <p>⚠ <b>블록을 한 칸도 건드리지 않는다.</b> 이름이 「균열」이지만 사람이 <b>「그냥 이름만
	 * 균열이지」</b>라고 못박았다 — 실제로 바닥을 파면 공허 낙사이고 그것이 이 전투가 유일하게
	 * 금지한 것이다.
	 */
	private static void flashCross(ServerLevel end, Vec3 center, double baseDeg) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		for (int line = 0; line < 2; line++) {
			double radians = Math.toRadians(baseDeg + line * 90.0);
			double alongX = Math.sin(radians);
			double alongZ = -Math.cos(radians);
			for (double along = -CROSS_REACH; along <= CROSS_REACH; along += CROSS_FLASH_GAP) {
				double x = center.x + alongX * along;
				double z = center.z + alongZ * along;
				int surface = ground.surfaceAt(end, x, z);
				if (surface == TrialEnderPulse.NO_GROUND) {
					continue;
				}
				end.sendParticles(ParticleTypes.SWEEP_ATTACK, true, false,
						x, surface + GROUND_OFFSET, z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	/**
	 * 그 자리가 십자 안인가.
	 *
	 * <p><b>세로는 보지 않는다.</b> {@code TrialRisks.insideMark} 와 같은 태도다 — 표식이 바닥에
	 * 그려지는데 「선 위에 떠 있었으니 안 맞는다」가 되면 표식이 거짓말한 것이 된다.
	 *
	 * <p>십자는 <b>90도 떨어진 선 둘</b>이므로 두 수직거리 가운데 하나라도 반폭 안이면 안이다.
	 * 그 둘은 서로 직교하므로 각각 {@code |외적|} 과 {@code |내적|} 이고, 그래서 삼각함수를
	 * <b>한 번만</b> 쓴다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 세 각도의 경계를 직접 굴려 본다.
	 *
	 * @param dx        중심에서 잰 x. 곧 {@code 사람 x − 드래곤 x}
	 * @param dz        중심에서 잰 z
	 * @param baseDeg   이 십자의 기준 각도(도). {@code (sin, −cos)} 이 첫 선의 방향이다
	 * @param halfWidth 선의 반폭(칸)
	 * @param reach     중심에서 선이 뻗는 거리(칸)
	 */
	static boolean insideCross(double dx, double dz, double baseDeg, double halfWidth,
			double reach) {
		if (dx * dx + dz * dz > reach * reach) {
			return false;
		}
		double radians = Math.toRadians(baseDeg);
		double alongX = Math.sin(radians);
		double alongZ = -Math.cos(radians);
		// 첫 선까지의 수직거리와, 그것과 직교하는 둘째 선까지의 수직거리.
		double toFirst = Math.abs(dx * alongZ - dz * alongX);
		double toSecond = Math.abs(dx * alongX + dz * alongZ);
		return Math.min(toFirst, toSecond) <= halfWidth;
	}

	// ------------------------------------------------------------------ ⑤ 상시 번개 (패턴이 아니다)

	/**
	 * 상시 번개 — 열 곳 · 반경 2.55칸 · 피해 {@code DragonLastStand.LIGHTNING_DAMAGE}(35) ·
	 * 드래곤 주변에 몰아서. <b>6초마다 저 혼자 돈다.</b>
	 *
	 * <p>사람이 <b>「번개는 패턴에 추가하지말고 기본이펙트로 계속 터졋으면좋겟어 드래곤 패턴이
	 * 아니라」</b>라고 해서 패턴 풀에서 빼고 여기로 옮겼다. {@link DragonLastStandZone} 과 같은
	 * 모양이다 — {@code DragonLastStand.tick} 이 매 틱 넘기고, <b>쉬는 틈도 쿨다운도 없다.</b>
	 *
	 * <h2>⚠⚠ 겹침을 허용한다 — 사람이 알고 고른 것이다</h2>
	 *
	 * <p><b>이 저장소에서 「즉사 메커닉 0개」를 깨는 두 번째 자리다</b>(첫째는 「종말의 비」).
	 * 값을 만지기 전에 이 절을 끝까지 읽을 것.
	 *
	 * <h3>어쩌다 이렇게 됐는가</h3>
	 *
	 * <ol>
	 *   <li>다섯 곳일 때는 <b>굳은 배치</b>(가운데 하나 + 90도씩 벌린 넷, 회전만 무작위)로
	 *       <b>겹침 없음이 증명</b>되어 있었다 — 쌍마다의 거리가 회전과 무관하게 같았다</li>
	 *   <li>사람이 열 곳으로 올렸다. 그러면 <b>겹침 없이 열 곳을 세울 수 없다</b> —
	 *       정확히 말하면 <b>무작위로 굴려 찾는 방식으로는</b> 그렇다. 자리를 굴릴 수 있는
	 *       반경이 9.45 뿐인데({@link #LIGHTNING_FIELD_RADIUS}) 최소 간격이
	 *       {@code TrialRisks.spotMinGap(2.55) = 5.1} 이라, {@code TrialRisks.SPOT_TRIES}(8)번
	 *       굴려 찾게 하면 「겹치느니 한 발 빠진다」가 자주 일어나 열 곳이 몇 곳으로 줄어든다.
	 *       ⚠ <b>손으로 배치하면 이론적으로는 들어간다</b>(가운데 하나 + 5.1 고리 여섯 + 9.45
	 *       고리 여섯이면 열셋이다). 그러니 「기하학적으로 불가능」이 아니라 「굴려서는 안
	 *       된다」가 정확한 말이고, 굳은 배치를 열 곳으로 다시 짜는 길은 남아 있다 — 다만 그것은
	 *       사람이 고른 답이 아니다</li>
	 *   <li>셋을 물었고 사람이 <b>「겹침을 허용함」</b>을 골랐다</li>
	 * </ol>
	 *
	 * <h3>그래서 {@code TrialRisks} 의 겹침 금지 목록을 쓰지 않는다</h3>
	 *
	 * <p>{@code reserveSpots}·{@code releaseSpots} 를 부르지 않고 {@code LIVE_SPOTS} 에 한 줄도
	 * 올리지 않는다. <b>「종말의 비」와 글자 그대로 같은 예외</b>이고 그쪽에 적힌 까닭이 그대로
	 * 적용된다 — <b>양쪽 다 끈 상태</b>여야 한다(남의 고리를 피하지도 않고 남이 우리를 피하지도
	 * 않는다). 한쪽만 되돌리면 겹침은 남고 자리만 줄어든다.
	 *
	 * <p>덧붙여, 이 페이즈에는 애초에 남의 고리가 없다 — 진입할 때 {@code TrialRisks.clearState()}
	 * 가 돌아 {@code LIVE_SPOTS} 가 비고 시련이 전부 멈춰 있다.
	 *
	 * <p>다만 <b>자리를 굴리는 자와 재는 자는 그쪽에서 가져온다</b> —
	 * {@code TrialRisks.arenaOffset}(원 안에 고르게)과 {@code TrialRisks.insideMark} 다. 분포를
	 * 여기서 새로 짜면 남의 카드와 다른 모양이 된다. ⚠ 넘기는 반경만
	 * {@link #LIGHTNING_FIELD_RADIUS}(9.45) 이고 {@code ARENA_RADIUS}(40)가 아니다 — 카드가
	 * 「드래곤 주변에 몰아서」이고, 아레나 전체에 흩뿌리면 <b>지대 밖에 번개가 떨어진다.</b>
	 *
	 * <h3>⚠ 겹친 자리가 세 겹이면 무장하고도 전멸일 <b>값</b>이다</h3>
	 *
	 * <p>피해 35 · {@code lightningBolt()} 라 다이아 풀셋 + 보호 IV 기준으로 이렇다
	 * ({@code GearedDamage}).
	 *
	 * <table border="1">
	 *   <caption>한 틱에 맞는 발 수</caption>
	 *   <tr><th>발</th><th>실제 피해</th><th></th></tr>
	 *   <tr><td>1</td><td>6.93</td><td>산다</td></tr>
	 *   <tr><td>2</td><td>13.86</td><td>산다</td></tr>
	 *   <tr><td><b>3</b></td><td><b>20.79</b></td><td><b>팀 체력 20 — 전멸</b></td></tr>
	 * </table>
	 *
	 * <p>⚠⚠ <b>그런데 실제로는 한 사람이 한 볼리에 한 발만 받는다.</b>
	 * {@link #strikeLightning} 이 첫 발에서 빠져나오기 때문이다. 그 {@code break} 는 굳은 배치
	 * 시절에 「배치를 손대는 사람이 약속을 깨뜨리지 못하게」 둔 보험이었는데, <b>겹침을 허용하면서
	 * 그것이 유일한 안전장치가 됐다.</b> 지우면 그날로 전멸 카드다 —
	 * {@code DragonLastStandPatternsTest} 가 그 {@code break} 를 이름으로 붙들고 있다.
	 *
	 * <h3>얼마나 자주 겹치는가 — 20만 볼리를 굴린 값</h3>
	 *
	 * <p>반경 9.45 원 안에 반경 2.55 짜리 열 곳을 겹침 검사 없이 뿌린 경우다. 「가장 깊은 겹침」은
	 * 배치의 모든 원-원 교점에서 덮은 원 수를 세어 구한 <b>정확값</b>이고, 넓이는 몬테카를로다
	 * (「종말의 비」가 같은 방법으로 재 두었다).
	 *
	 * <table border="1">
	 *   <caption>한 볼리의 가장 깊은 겹침(10곳 · 20만 볼리)</caption>
	 *   <tr><th>깊이</th><th>그런 볼리의 비율</th></tr>
	 *   <tr><td>2겹</td><td>7.57%</td></tr>
	 *   <tr><td>3겹</td><td>56.62%</td></tr>
	 *   <tr><td>4겹</td><td>29.72%</td></tr>
	 *   <tr><td>5겹</td><td>5.45%</td></tr>
	 *   <tr><td>6겹</td><td>0.59%</td></tr>
	 *   <tr><td>7겹</td><td>0.042%</td></tr>
	 *   <tr><td>8겹</td><td>0.002%</td></tr>
	 * </table>
	 *
	 * <p>곧 <b>3겹 이상 구역이 생기는 볼리가 92.43%</b> 이고 2겹은 <b>100%</b> 다. 넓이는 이렇다
	 * (덮임은 반경 12 안에만 있으므로 그 내접원 452.4칸²과 견준다. 보더는 정사각형이라 지대
	 * 전체는 576칸²다).
	 *
	 * <table border="1">
	 *   <caption>그 구역이 지대에서 차지하는 넓이</caption>
	 *   <tr><th>깊이</th><th>내접원 비율</th><th>넓이</th><th>지대(576칸²) 비율</th></tr>
	 *   <tr><td>1겹 이상</td><td>34.63%</td><td>156.7칸²</td><td>27.20%</td></tr>
	 *   <tr><td>2겹 이상</td><td>8.78%</td><td>39.7칸²</td><td>6.90%</td></tr>
	 *   <tr><td><b>3겹 이상</b></td><td><b>1.54%</b></td><td><b>6.96칸²</b></td><td><b>1.21%</b></td></tr>
	 *   <tr><td>4겹 이상</td><td>0.18%</td><td>0.83칸²</td><td>0.15%</td></tr>
	 * </table>
	 *
	 * <p>읽는 법은 이렇다. <b>거의 모든 볼리에 3겹 구역이 있지만 그 넓이는 지대의 1.2% 이고,
	 * 무엇보다 {@code break} 때문에 3겹이 3배 피해가 되지 않는다.</b> 그래도 적어 두는 것은 <b>그
	 * {@code break} 가 지워지는 날 이 표가 그대로 현실이 되기 때문</b>이다.
	 *
	 * <p>연달아 맞는 것은 막지 않는다 — <b>세 볼리 연속으로 맞으면 12초에 20.79 로 전멸</b>이다.
	 * 그 사이가 {@link #LIGHTNING_PERIOD_TICKS} 이고 볼리마다 3초 예고가 있다.
	 *
	 * @param beganAt 최후의 저항이 시작한 틱. 시계의 원점이다
	 */
	static void tickLightning(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			long beganAt, long now) {
		if (lightningOwner != beganAt) {
			// 남의 판이거나 첫 틱이다. 진입 무적이 끝나는 자리에서 첫 볼리를 연다 —
			// DragonLastStand.ENTRY_GRACE_TICKS 는 첫 패턴을 고르는 시각과 같은 값이고,
			// 그보다 일찍 열면 「저항 V 로 아무 일도 안 일어나는 번개」가 한 번 지나간다.
			lightningOwner = beganAt;
			lightningNextVolleyAt = beganAt + DragonLastStand.ENTRY_GRACE_TICKS;
			lightningStrikeAt = Long.MIN_VALUE;
			lightningSpots = List.of();
		}
		if (lightningStrikeAt == Long.MIN_VALUE) {
			if (now < lightningNextVolleyAt) {
				return;
			}
			lightningSpots = pickLightningSpots(end, dragon.position());
			// 다음 볼리 시각은 자리를 얻었는지와 무관하게 민다. 자리를 못 얻은 볼리를 매 틱
			// 다시 열면 허공만 있는 판에서 하이트맵을 초당 200번 묻는다.
			lightningNextVolleyAt = now + LIGHTNING_PERIOD_TICKS;
			if (lightningSpots.isEmpty()) {
				return;
			}
			lightningStrikeAt = now + LIGHTNING_WARN_TICKS;
		}
		if (now < lightningStrikeAt) {
			warnLightning(end, members, lightningSpots,
					(int) (now - (lightningStrikeAt - LIGHTNING_WARN_TICKS)));
			return;
		}
		// 부등호가 「같다」가 아니라 「지났다」인 것이 요점이다. 틱을 건너뛰어도(렉·시간 정지)
		// 예고를 보여 준 볼리는 반드시 내리친다.
		strikeLightning(end, members, lightningSpots);
		lightningStrikeAt = Long.MIN_VALUE;
		lightningSpots = List.of();
	}

	/**
	 * 예고. 노랑 고리와 층 소리. 노랑은 규약의 「번개가 내리친다」다.
	 *
	 * <p>고리를 <b>여섯 틱에 나눠</b> 그린다 — 열 곳을 매 틱 다 그리면 400점이라 부채꼴 예고와
	 * 겹치는 순간 예산이 넘친다({@link #LIGHTNING_MARK_STRIDE}).
	 */
	private static void warnLightning(ServerLevel end, List<ServerPlayer> members, List<Vec3> spots,
			int step) {
		ParticleOptions bolt = TrialWarning.dust(TrialWarning.Colors.LIGHTNING);
		for (Vec3 spot : spots) {
			TrialWarning.markGround(end, spot, LIGHTNING_RADIUS, bolt, LIGHTNING_MARK_STRIDE, step);
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
	 * 열 곳에 내리친다.
	 *
	 * <p>피해원은 {@code lightningBolt()} 다 — 문서가 「낙뢰와 같은 방식」이라고 적어 둔 것을
	 * 값으로 지킨 것이라 <b>피해원도 같아야 한다</b>({@code TrialRisks.detonate} 와 같은 것).
	 * 무장 기준 한 대 6.93 · 세 대 20.79 로 「큰 카드는 세 대에 전멸」에 그대로 얹힌다.
	 *
	 * <p>⚠⚠ <b>{@code break} 를 지우지 말 것.</b> 겹침을 허용한 지금 <b>「한 사람은 한 발」을
	 * 지키는 것이 이 한 줄뿐</b>이고, 3겹 구역은 거의 모든 볼리에 있다(위의 표). 지우면 무장
	 * 기준 20.79 로 그 자리에서 전멸이다.
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
				// ⚠ 한 사람은 한 발이다. 겹침을 허용했으므로 이 줄이 유일한 안전장치다 —
				// 세 겹 자리에 서 있으면 20.79 로 전멸이고, 그런 자리는 거의 매 볼리에 있다.
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
	 * 한 볼리의 열 곳을 고른다. <b>자리는 무작위이고 겹침을 보지 않는다.</b>
	 *
	 * <p>굴리는 자는 {@code TrialRisks.arenaOffset} 이다 — 분포(원 안에 고르게)가 남의 카드와
	 * 같아야 한다. ⚠ 넘기는 반경만 {@link #LIGHTNING_FIELD_RADIUS} 이고 아레나 반경이 아니다.
	 *
	 * <p>허공인 자리는 <b>버린다</b>. 중앙 섬은 둥글지 않아 반경 9.45 안에도 사람이 파 놓은 구멍이
	 * 있을 수 있고, 허공에 내리치면 예고도 피해도 뜻이 없다.
	 *
	 * <p>{@code TrialRisks.SPOT_TRIES} 로 다시 굴리지 <b>않는다.</b> 그 되풀이는 「겹치지 않는 자리를
	 * 찾는」 장치인데 우리는 겹침을 허용하므로 남는 이유가 「허공을 피하는」 것뿐이고, 드래곤 발밑
	 * 9.45칸은 바닐라 엔드에서 통째로 엔드스톤이라 <b>한 번에 거의 다 맞는다.</b> 다시 굴리면 판 곳곳에
	 * 구멍을 파 둔 서버에서 열 곳이 몇 번씩 굴려지는 값만 늘어난다.
	 */
	private static List<Vec3> pickLightningSpots(ServerLevel end, Vec3 center) {
		List<Vec3> spots = new ArrayList<>(LIGHTNING_COUNT);
		RandomSource random = end.getRandom();
		for (int index = 0; index < LIGHTNING_COUNT; index++) {
			Vec3 offset = lightningOffset(random.nextDouble(), random.nextDouble());
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
	 * 드래곤을 중심으로 한 자리 하나의 <b>상대 좌표</b>.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 굴림을 격자로 훑어 <b>모든 자리가
	 * {@link #LIGHTNING_FIELD_RADIUS} 안</b>인지, 곧 <b>모든 고리가 마지막 지대 안</b>인지 본다.
	 *
	 * @param roll 0 이상 1 미만 둘. 실전에서는 {@code end.getRandom().nextDouble()} 이 준다
	 */
	static Vec3 lightningOffset(double roll, double otherRoll) {
		return TrialRisks.arenaOffset(roll, otherRoll, LIGHTNING_FIELD_RADIUS);
	}

	// ------------------------------------------------------------------ 예산

	/**
	 * <b>한 틱에 가장 많이 나가는</b> 점 수.
	 *
	 * <p>⚠ <b>더하기다.</b> 패턴 넷은 동시에 돌지 않지만({@code DragonLastStand.advance})
	 * <b>번개는 언제나 함께 돈다</b> — 번개가 패턴이었을 때의 「셋 중 가장 바쁜 것」은 이제 틀린
	 * 식이다. {@code TrialLandingShock.MAX_POINTS_PER_TICK}(440)과 견줄 값이다.
	 *
	 * <p>안전지대는 월드 보더라 <b>점을 한 개도 쓰지 않고</b>, 부채꼴의 빨간 면은 디스플레이 개체라
	 * <b>역시 한 개도 쓰지 않는다</b>({@link DragonLastStandConePanel}).
	 *
	 * <p>값에서 직접 센다 — 개수나 반경을 올리는 사람이 예산을 눈으로 세지 않아도 시험이 먼저
	 * 멈춰 세운다.
	 */
	static int worstCasePointsPerTick() {
		return lightningPoints() + Math.max(Math.max(wingBeatPoints(), conePoints()),
				Math.max(suckPoints(), crossPoints()));
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
		return Math.max(coneWarnPoints(), coneFirePoints());
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

	/** 터지는 틱과 그 뒤 불꽃 — 부채꼴을 채우는 브레스 파티클. 예고 표식은 그 틱에 안 나간다. */
	static int coneSprayPoints() {
		int points = 0;
		for (double radius = 4.0; radius <= CONE_RANGE; radius += 4.0) {
			points += arcPoints(radius, 2.0) + 1;
		}
		return points;
	}

	/** 터지는 <b>그 한 틱</b>에만 나가는 불꽃({@link #burnCone}). 뒤이은 20틱에는 안 나간다. */
	static int coneFlamePoints() {
		int points = 0;
		for (double radius = FLAME_RING_STEP; radius <= CONE_RANGE; radius += FLAME_RING_STEP) {
			points += arcPoints(radius, FLAME_ARC_GAP) + 1;
		}
		return points;
	}

	/** 터지는 틱 — 브레스 파티클과 불꽃이 <b>함께</b> 나간다. */
	static int coneFirePoints() {
		return coneSprayPoints() + coneFlamePoints();
	}

	/**
	 * 공허 흡입 — 예고 틱과 터지는 틱 가운데 바쁜 쪽.
	 *
	 * <p>터지는 틱에는 표식이 없고 파티클 꾸러미 둘뿐이라 예고 쪽이 언제나 이긴다.
	 */
	static int suckPoints() {
		return Math.max(suckWarnPoints(), 2);
	}

	/**
	 * 예고 틱 — 검은 속(나눠 그린 한 틱 몫) + 빨간 경계 고리(매 틱 전부).
	 *
	 * <p>속을 나눠 그리지 않으면 여기가 231점이 되어 <b>부채꼴 예고(202)를 넘어</b>
	 * {@link #worstCasePointsPerTick} 의 답이 바뀐다 — {@link #SUCK_MARK_STRIDE} 를 볼 것.
	 */
	static int suckWarnPoints() {
		int fill = 0;
		for (double radius = MARK_GAP; radius < SUCK_RADIUS; radius += MARK_GAP) {
			fill += suckFillPoints(radius);
		}
		// 나눠 그리기는 위상에 따라 하나 더 나갈 수 있다. 예산은 늘 나쁜 쪽을 봐야 한다.
		int stroke = (fill + SUCK_MARK_STRIDE - 1) / SUCK_MARK_STRIDE;
		return stroke + TrialWarning.ringPoints(SUCK_RADIUS);
	}

	/**
	 * 십자 균열 — 예고 틱과 터지는 틱 가운데 바쁜 쪽.
	 *
	 * <p>⚠ <b>터지는 틱에는 둘이 함께 나간다.</b> 그 틱에 앞 십자가 터지고 다음 십자의 예고가
	 * 시작하므로({@link #crossPendingRound} 의 경고) 갈라짐 연출과 새 표식이 같은 틱이다.
	 */
	static int crossPoints() {
		return Math.max(crossWarnPoints(), crossFlashPoints() + crossWarnPoints());
	}

	/** 예고 틱 — 가장자리 줄 넷을 여섯 틱에 나눠 그린 한 틱 몫. */
	static int crossWarnPoints() {
		int whole = 2 * 2 * crossLinePoints(CROSS_MARK_GAP);
		return (whole + CROSS_MARK_STRIDE - 1) / CROSS_MARK_STRIDE;
	}

	/** 터지는 틱에만 나가는 갈라짐 연출. 중심선 둘뿐이라 가장자리보다 성기다. */
	static int crossFlashPoints() {
		return 2 * crossLinePoints(CROSS_FLASH_GAP);
	}

	/** 중심을 지나 양쪽으로 뻗는 선 하나에 찍는 점 수. */
	static int crossLinePoints(double gap) {
		if (!(gap > 0.0)) {
			return 1;
		}
		return (int) Math.floor((CROSS_REACH * 2.0) / gap) + 1;
	}

	/**
	 * 상시 번개 — 노랑 고리 열 개를 <b>여섯 틱에 나눠</b> 그린 한 틱 몫.
	 *
	 * <p>내리치는 틱에는 표식이 없고 번개 엔티티뿐이라 0 이다. 여기서 세는 것은 <b>예고 틱</b>이고
	 * 예고가 60틱이라 그것이 사실상 언제나다.
	 */
	static int lightningPoints() {
		return LIGHTNING_COUNT * TrialWarning.strokePoints(LIGHTNING_RADIUS, LIGHTNING_MARK_STRIDE);
	}
}
