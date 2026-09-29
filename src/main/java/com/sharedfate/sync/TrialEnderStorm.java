package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.EnderStorm} 실행기 — 엔더폭풍.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p>아레나 가장자리에서 중앙으로 소용돌이 둘({@code count})이 초당 2칸({@code speedPerSecond})씩
 * 전진한다. 닿으면 피해 2({@code damage}) 와 강한 넉백({@code knockback}).
 *
 * <p>소용돌이가 중앙에 닿으면 25초({@code restTicks}) 뒤에 새로 둘이 시작한다.
 *
 * <h2>넉백 방향은 안쪽이다 — 값이 아니라 규칙이다</h2>
 *
 * <p>미는 방향은 <b>폭풍이 나아가는 쪽</b>, 즉 중앙 쪽이다. 엔드 중앙 섬은 사방이 허공이고 공유
 * 체력이라 <b>한 사람을 섬 밖으로 밀면 팀 전체가 끝난다.</b> 바깥으로 미는 구현은 카드 값을
 * 어떻게 적든 틀린 것이다.
 *
 * <p>이 저장소는 강한 넉백을 원칙적으로 금지하고, 이 카드는 <b>방향이 안쪽이라서</b> 예외로
 * 허용됐다. 그래서 방향은 카드가 아니라 여기서 지킨다. 지키는 방법이 셋이다.
 *
 * <ol>
 *   <li><b>방향을 사람에게서 구하지 않는다.</b> {@link #push} 가 쓰는 벡터는 <b>소용돌이가
 *       나아가는 방향</b>({@code -outward}) 하나뿐이고, 사람의 좌표는 한 번도 들어가지 않는다.
 *       「사람과 소용돌이의 상대 위치」로 방향을 잡으면 <b>소용돌이보다 바깥에 선 사람이 바깥으로
 *       밀려난다</b> — 그 계산은 이 파일에 한 줄도 없다</li>
 *   <li><b>세로로 한 칸도 띄우지 않는다.</b> 띄우면 낙하 피해가 붙고, 공중에서는 방향을 못 바꿔
 *       훨씬 멀리 날아간다(바닥 마찰 0.546 대 공중 감쇠 0.91). {@link #push} 는 세로 속도를
 *       읽어 그대로 돌려놓는다</li>
 *   <li><b>산수가 아니라 함수로 막는다.</b> {@link #pushDistance} 가 「밀린 뒤가 밀리기 전보다
 *       중앙에서 멀어지지 않는」 거리까지만 돌려준다. 세기를 아무리 올려도, 다른 카드가 이미
 *       사람을 가장자리로 데려다 놓았어도, <b>이 카드가 미는 목적지는 출발점보다 중앙에
 *       가깝다.</b> 산수는 값을 고치는 사람이 안 볼 수 있지만 이 함수는 못 피한다</li>
 * </ol>
 *
 * <p><b>여기서 멈출 것.</b> 위 셋 중 하나라도 무르면 이 카드가 예외로 허용된 근거가 사라진다.
 * 그때는 넉백을 손보는 것이 아니라 카드를 금지 쪽으로 되돌려야 한다.
 *
 * <h2>소용돌이의 모양 — 바닥 원에 기둥을 얹는다</h2>
 *
 * <p>이 카드의 위협은 <b>「보고 비켜야 한다」</b>다. 그러려면 어디까지가 닿는 범위인지가 눈으로
 * 읽혀야 하므로 <b>닿는 범위 자체를 바닥 원</b>으로 정하고, 그 위에 엔더 입자 기둥을 세워 멀리서도
 * 오는 것이 보이게 한다. 판정은 오로지 바닥 원이다({@link #VORTEX_RADIUS}).
 *
 * <p>고른 이유보다 <b>고르지 않은 이유</b>가 길다.
 *
 * <ul>
 *   <li><b>벽</b>(아레나를 가로지르는 띠) — 비킬 곳이 옆이 아니라 <b>뒤</b>뿐이다. 폭풍이
 *       가장자리에서 중앙으로 오므로 뒤로 물러나는 길은 <b>반대편 가장자리</b>, 곧 허공 쪽이다.
 *       이 카드가 예외로 허용된 근거(안쪽으로만 민다)와 정면으로 어긋나므로 쓰지 않는다</li>
 *   <li><b>기둥만</b> — 세로로 선 것은 <b>발밑 어디까지가 닿는지</b>를 말해 주지 못한다. 옆에서
 *       보면 바닥과 만나는 자리가 가려지고, 이 저장소가 다섯 장의 카드에서 가르쳐 온 「바닥 고리 =
 *       서 있으면 안 되는 자리」와도 말이 달라진다</li>
 *   <li><b>바닥 원</b> — 판정과 표식이 <b>같은 도형</b>이라 표식이 거짓말을 할 수 없다. 사람이
 *       이미 배운 신호이기도 하다. 기둥은 그 위에 얹는 <b>연출</b>이고 판정에 끼지 않는다</li>
 * </ul>
 *
 * <p>기둥을 <b>위로 갈수록 좁게</b> 그리는 것도 같은 이유다. 실제 회오리는 위가 넓지만 그렇게
 * 그리면 <b>가장 넓은 곳이 공중</b>이라 바닥에서 어디까지가 닿는지를 잘못 읽는다. 언제나 바닥 원이
 * 가장 넓어야 한다.
 *
 * <h2>피해는 닿은 사람 전원에게 들어간다</h2>
 *
 * <p>「연쇄 포격」({@code DragonFireBarrage.detonate})은 반대로 팀에 한 번만 넣는다. 거기서는
 * <b>넷이 한 원 안에 모여 있는 것이 공유 체력 게임의 올바른 대응</b>이라, 모두 때리면 올바른 대응이
 * 곧 전멸이 되기 때문이다.
 *
 * <p>이 카드에는 그 함정이 없다.
 *
 * <ul>
 *   <li><b>대응이 「모이기」가 아니라 「비키기」</b>다. 소용돌이는 지름 8칸짜리 둘이고 아레나는
 *       지름 80칸이라, 넷이 붙어 선 채로 다 같이 옆으로 비켜도 아무도 안 맞는다 — 회피가 팀을
 *       흩어 놓지 않는다</li>
 *   <li>팀에 한 번만 넣으면 <b>셋이 비키든 넷이 비키든 한 사람만 안 비키면 같은 2</b> 라
 *       「나는 안 비켜도 된다」가 성립하고, 회피 카드가 회피를 못 가르친다. 「착지 충격」이
 *       전원 타격인 것과 같은 까닭이다</li>
 * </ul>
 *
 * <p>그 대신 천장이 낮다. {@link #worstCaseTeamDamage} 가 <b>넷이 소용돌이 둘에 다 맞는</b> 값을
 * 직접 센다 — {@code 2 × 2 × 4 = 16} 으로 팀 공유 체력 20 아래다. 소용돌이 둘은 중앙 근처에서만
 * 겹치므로 16 은 <b>네 사람이 20초 내내 중앙에 서 있었다</b>는 뜻이고, 그러고도 살아남는다.
 * <b>피해 2 는 전원 타격을 전제로만 설명되는 값</b>이니 여기를 올리려는 사람은 그 곱부터 볼 것.
 *
 * <p>넉백을 넷이 다 받는 것도 이 카드에서는 위험하지 않다. <b>넷이 통째로 굴러가는 방향이
 * 중앙</b>이기 때문이다 — 금지가 막으려던 것과 정반대다.
 *
 * <h2>같은 사람을 매 틱 때리지 않는다</h2>
 *
 * <p>소용돌이가 사람을 통과하는 데 여러 틱이 걸린다(지름 8칸을 초당 2칸으로 지나가므로 <b>80틱</b>
 * 이다). 매 틱 2씩 넣으면 10틱이면 20 이라 즉사다. 그래서 <b>소용돌이마다 명단</b>을 들고
 * ({@code Storm.swept}) 한 번 판정한 사람은 그 소용돌이가 다시 묻지 않는다.
 *
 * <p>바닐라 피격 무적시간(10틱)에 기대지 않는다. 기대면 80틱짜리 통과에서 여덟 번이 들어가
 * 「피해 2」가 조용히 16 이 되고, 무적시간이 다른 카드에 먼저 쓰이면 그마저 어긋난다.
 *
 * <p>명단이 한 <b>주기</b>가 아니라 한 <b>소용돌이</b>마다인 것이 중요하다. 둘이 중앙에서 겹치는
 * 자리는 각각 한 번씩 물어 2 + 2 = 4 이고, 그것이 {@code TrialRisks.worstCaseTickDamage} 가
 * 이 위험을 {@code hits(damage, count)} 로 세는 값과 같다.
 *
 * <h2>파티클과 소리는 멀리 보낸다</h2>
 *
 * <p>소용돌이는 <b>반경 40 가장자리</b>에서 출발한다. 반대편에 선 팀원과는 80칸이 벌어지는데
 * 파티클 짧은 형태는 32칸에서 잘리므로({@link TrialWarning} 의 「거리 제한을 끄고 보낸다」)
 * 전부 긴 형태로 보낸다. 소리도 같은 이유로 <b>사람마다 그 자리에서</b> 울린다 — 바닐라 소리
 * 사거리는 볼륨 1 이하면 16칸이다.
 *
 * <p>자막은 쓰지 않는다. 남은 신호는 <b>소리와 바닥 표식</b> 둘뿐이라, 소용돌이를 다가오는 20초
 * 내내 그리는 것이 이 카드의 예고 전부다 — 줄이지 말 것.
 *
 * <h2>드래곤도 블록도 건드리지 않는다</h2>
 *
 * <p>{@code dragon} 인자를 받기만 하고 한 번도 읽지 않는다. 진입점 모양을 다른 실행기와 맞추려고
 * 받는 것뿐이다({@link TrialRisks} 의 분기가 갈래 없이 한 줄로 유지되게 한다). 드래곤 페이즈에
 * 끼어들었다가 <b>착지를 아예 안 하게</b> 된 사고가 이 저장소에 있고, 이 카드는 드래곤과 무관하다.
 *
 * <p>블록도 한 칸 건드리지 않는다. 지면 높이를 <b>읽기만</b> 한다 — 바닥에 구멍이 나면 공허
 * 낙사가 되고 다음 전투의 발판도 달라진다.
 *
 * <h2>⚠ 지점 겹침 목록({@code TrialRisks.LIVE_SPOTS})에 들어가지 않는다</h2>
 *
 * <p>그쪽은 <b>무작위로 자리를 뽑는</b> 카드가 서로의 고리 위에 떨어지지 않게 하는 장치다
 * ({@code TrialRisks.reserveSpots}). 이 카드는 자리를 뽑지 않는다 — 소용돌이는 가장자리에서
 * 중앙까지 <b>아레나를 가로질러 움직이므로</b>, 잡아 두면 지나가는 길목 전부가 다른 카드에게
 * 영영 막힌 자리가 된다. 「착지 충격」도 같은 이유로 그 목록 밖에 있다.
 *
 * <p>대신 값이 작다. 겹쳐서 가장 아픈 조합이 「낙뢰」(18) 와 이 카드(2) 로 <b>20</b> 인데 팀 공유
 * 체력이 정확히 20 이다. <b>여기를 올리는 사람은 그 합부터 볼 것</b> — 2 가 3 이 되는 순간 겹친
 * 자리가 즉사 구역이 된다.
 */
public final class TrialEnderStorm {

	// ------------------------------------------------------------------ 모양

	/**
	 * 소용돌이 하나가 닿는 반경(칸). <b>바닥 원의 반경이고 곧 판정 반경</b>이다.
	 *
	 * <h2>왜 4 인가</h2>
	 *
	 * <p>카드에 적혀 있지 않아 이 파일이 정한다. 위아래를 다음 둘이 잡는다.
	 *
	 * <ul>
	 *   <li><b>아래</b> — 「가만히 있으면 맞는다」가 성립해야 한다. 정면에 선 사람이 빠져나가려면
	 *       옆으로 반경만큼 움직여야 하는데, 반경 4 는 걸어서(4.317칸/초) 0.93초다. 그동안
	 *       소용돌이는 1.9칸밖에 못 오므로 <b>비키면 반드시 산다.</b> 더 작으면 한 걸음에 벗어나
	 *       카드가 아무것도 요구하지 않게 된다</li>
	 *   <li><b>위</b> — 소용돌이 둘이 훑는 넓이가 {@code 2 × 8 × 40 = 640} 칸²이고 아레나
	 *       ({@code π × 40² ≈ 5027})의 <b>13%</b> 다. 20초에 13% 는 「서 있기만 해도 늘 맞는」
	 *       값이 아니라 「한 번은 비켜야 하는」 값이다. 더 키우면 비킬 곳이 사라진다</li>
	 * </ul>
	 *
	 * <p>이 판의 다른 고리(「낙뢰」 3, 「종말의 비」 2.5, 「연쇄 포격」 3.5)보다 크다. 그것들은 한 틱에
	 * 터지지만 이쪽은 <b>20초 동안 다가오는 것이 보이므로</b>, 커도 「못 피한다」가 되지 않는다.
	 *
	 * <p><b>여기를 올리는 사람은 {@link #worstCaseTeamDamage} 를 함께 볼 것.</b> 반경이 커지면
	 * 소용돌이 둘이 겹치는 구역이 넓어져 넷이 둘 다 맞는 일이 실제로 일어난다.
	 */
	static final double VORTEX_RADIUS = 4.0;

	/**
	 * 기둥 높이(칸). 연출이고 판정에 끼지 않는다.
	 *
	 * <p>사람 키(1.8)의 세 배쯤이라 아레나 반대편에서도 지형에 가리지 않고 보인다. 더 높이면
	 * 시야를 막아 <b>다른 카드의 바닥 표식</b>이 안 읽힌다 — 시련은 쌓이므로 그쪽이 더 비싸다.
	 */
	static final double COLUMN_HEIGHT = 5.0;
	/**
	 * 기둥 꼭대기의 반경이 바닥 반경의 몇 배인가.
	 *
	 * <p><b>1 보다 작아야 한다.</b> 실제 회오리는 위가 넓지만, 그렇게 그리면 가장 넓은 곳이 공중이라
	 * 발밑에서 어디까지가 닿는지를 잘못 읽는다. 이 카드의 위협은 「보고 비키는 것」이므로 <b>닿는
	 * 범위인 바닥 원이 언제나 가장 넓어야</b> 한다.
	 */
	static final double COLUMN_TOP_FACTOR = 0.35;
	/** 기둥 하나에 찍는 점 수. 나선 한 줄이다. */
	static final int COLUMN_POINTS = 40;
	/** 기둥이 감는 바퀴 수. 둘이면 점 하나당 18도라 나선으로 읽힌다. */
	private static final double COLUMN_TURNS = 2.0;
	/**
	 * 소용돌이가 <b>한 칸 나아갈 때마다</b> 기둥이 도는 각(라디안).
	 *
	 * <p>틱이 아니라 <b>거리</b>에 매어 둔다. 그러면 얼어붙은 판에서 거리가 멈출 때 기둥도 함께
	 * 멈춘다 — 판이 멈췄는데 소용돌이만 도는 일이 없고, 따로 틱을 세지 않아도 된다.
	 *
	 * <p>지금 값에서 틱당 0.3 라디안, 초당 0.95 바퀴다. 돌지 않으면 「소용돌이」가 아니라 그냥
	 * 점무늬 기둥이라, 다가오는 것과 제자리에 있는 것이 구별되지 않는다.
	 */
	private static final double COLUMN_SPIN_PER_BLOCK = 3.0;

	/**
	 * 한 틱에 이 카드가 쓰는 점 수의 상한.
	 *
	 * <p>이 판의 예산은 400~440 이다 — 「낙뢰」가 반경 3 짜리 고리 열 개로 400,
	 * 「연쇄 포격」이 반경 3.5 짜리 열 개로 440 을 쓴다. 실제로 쓰는 수는 {@link #markPoints} 가
	 * 값에서 직접 세고, 지금 값에서는 소용돌이당 91점({@code 고리 51 + 기둥 40})으로 둘이 182 다.
	 */
	static final int MAX_POINTS_PER_TICK = 400;

	// ------------------------------------------------------------------ 미는 값

	/**
	 * {@code knockback} 이 1.0 일 때 미는 거리(칸). 카드 값은 <b>이것의 배율</b>이다.
	 *
	 * <h2>왜 8 인가</h2>
	 *
	 * <p>이 저장소가 이미 <b>「강한 넉백」으로 쓰고 있는 유일한 값</b>이다(「착지 충격」의
	 * {@code knockback} 8). 파랑 표식과 밀려나는 소리는 두 카드가 같은 것을 쓰므로, 세기까지 같아야
	 * 플레이어가 <b>하나만 배운다.</b> 다른 숫자를 새로 만들면 같은 신호에 두 가지 세기가 붙는다.
	 *
	 * <p>적힌 값은 <b>천장</b>이지 실제로 밀리는 거리가 아니다. {@link #pushVelocity} 가 공중
	 * 감쇠로 속도를 잡으므로 바닥에 붙어 있으면 마찰(0.546)이 먼저 먹어 <b>1.6칸 남짓</b>만 밀리고,
	 * 8 에 가까워지는 것은 공중에 떠 있을 때뿐이다. 어긋나는 방향이 언제나 <b>덜 미는 쪽</b>이다.
	 *
	 * <p><b>이 값이 안전을 지키는 것이 아니다.</b> 안전은 {@link #pushDistance} 가 지킨다 — 여기를
	 * 몇으로 올리든 밀린 사람이 중앙에서 멀어지는 일은 없다. 그러니 여기를 만지는 사람이 물어야 할
	 * 것은 「위험한가」가 아니라 「같은 신호에 세기가 둘이 되는가」다.
	 */
	static final double PUSH_BLOCKS = 8.0;
	/**
	 * 공중 수평 감쇠. 26.3 {@code LivingEntity} 의 공중 이동이 매 틱 수평 속도에 곱하는 값이다.
	 *
	 * <p>{@code TrialLandingShock.AIR_DRAG} 와 같은 값이고 같은 물리다. 그쪽 상수를 빌려 오지 않고
	 * 여기 적은 것은 카드 둘이 서로의 내부에 매이지 않게 하기 위해서고, 대신 <b>두 값이 같은지를
	 * 시험이 본다</b>({@code TrialEnderStormTest}) — 판이 올라 이 수가 바뀌면 한쪽만 고쳐지는 일을
	 * 그 시험이 먼저 잡는다.
	 */
	static final double AIR_DRAG = 0.91;

	// ------------------------------------------------------------------ 그리는 값

	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다({@code TrialWarning} 과 같은 값). */
	private static final double GROUND_OFFSET = 0.15;
	/**
	 * 하이트맵이 허공을 돌려줬을 때 쓰는 높이.
	 *
	 * <p>{@code DragonFireBarrage.FALLBACK_GROUND_Y} 와 같은 값이고 까닭도 같다 — 중앙 섬 표면이다.
	 * 소용돌이는 반경 40 에서 출발하는데 섬은 둥글지 않아 거기가 허공일 수 있다.
	 */
	private static final double FALLBACK_GROUND_Y = 63.0;

	/**
	 * 씨앗을 주기마다 띄우는 폭.
	 *
	 * <p>{@link #baseAngle} 이 {@code granted + index × 이 값} 으로 씨를 만든다. 주기 번호를 그냥
	 * 더하면 이웃한 폭풍의 씨가 1 밖에 안 달라져 첫 굴림이 서로 닮는다 — 그러면 폭풍이 매번
	 * 비슷한 방향에서 온다.
	 */
	private static final long ANGLE_SEED_STRIDE = 1_000_003L;

	/** 「이 소용돌이는 그 사람에게 영영 안 닿는다」. {@link #ticksUntilHit} 가 돌려준다. */
	static final int NEVER = -1;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 아레나를 건너고 있는 폭풍. 쉬는 동안에는 {@code null} 이다.
	 *
	 * <p>아레나도 팀도 하나뿐이라 칸 하나로 둔다. <b>{@link #clearState()} 가 반드시 비운다</b> —
	 * 남겨 두면 지난 판의 명단이 새 판의 첫 폭풍에서 사람을 건너뛴다.
	 */
	private static @Nullable Storm storm;
	/**
	 * 지금 들고 있는 것이 <b>어느 전투의 기록인가</b>. 카드를 받은 틱으로 가른다.
	 *
	 * <p>전투가 끝날 때 누가 비워 주기를 기다리지 않는다. 배선을 한 줄 빠뜨렸다고 지난 판의 명단이
	 * 남으면 다음 판의 첫 폭풍이 조용히 아무도 안 때린다. {@code DragonFireBarrage.beginFight} 와
	 * 같은 장치다.
	 */
	private static long rememberedGrant = Long.MIN_VALUE;

	/**
	 * 한 번에 도는 소용돌이들.
	 *
	 * @param index     몇 번째 폭풍인가. 주기가 넘어갔는지 판단한다
	 * @param baseAngle 첫 소용돌이가 서 있는 각. 나머지는 여기서 고르게 나눠 놓는다
	 * @param swept     소용돌이마다 <b>이미 판정한</b> 사람들. 목록 순서가 소용돌이 순서다.
	 *                  <b>이 집합들은 고쳐 쓴다</b> — 없으면 통과하는 80틱 동안 매 틱 맞는다
	 */
	private record Storm(long index, double baseAngle, List<Set<UUID>> swept) {
	}

	private TrialEnderStorm() {
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자도 받는 것은 {@link TrialRisks} 의
	 * 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다. {@code dragon} 은 <b>한 번도 읽지 않는다</b> —
	 * 까닭은 클래스 설명에 있다.
	 *
	 * <p>시각은 넘겨받은 {@code now} 만 쓴다. {@code level.getGameTime()} 은 룰렛이 도는 동안 판이
	 * 얼어붙으면 흐르지 않아, 섞어 쓰면 소용돌이가 그 자리에 멈추거나 한꺼번에 건너뛴다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱. 주기는 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.EnderStorm risk) {
		if (end == null || members == null || members.isEmpty() || risk == null) {
			return;
		}
		beginFight(granted);

		int count = risk.count();
		double perTick = blocksPerTick(risk.speedPerSecond());
		if (count <= 0 || !(perTick > 0.0)) {
			// 멈춰 선 소용돌이는 영영 중앙에 닿지 않는다. 그런 카드는 돌리지 않는다.
			return;
		}

		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		if (elapsed <= 0L) {
			// 받은 바로 그 틱에는 아무것도 하지 않는다. 다음 틱부터 가장자리에서 출발한다 —
			// 중앙까지 20초가 걸리므로 이 카드는 예고를 따로 둘 필요가 없다.
			return;
		}

		int travel = travelTicks(perTick);
		int cycle = cycleTicks(travel, risk.restTicks());
		int step = (int) (elapsed % cycle);
		if (step > travel) {
			// 쉬는 중이다. 소용돌이는 중앙에 닿는 그 틱에 사라지고 아무것도 남기지 않는다.
			// 명단도 여기서 버린다 — 들고 있으면 다음 폭풍이 지난 폭풍의 명단을 물려받는다.
			storm = null;
			return;
		}

		List<ServerPlayer> present = present(end, members);
		if (present.isEmpty()) {
			return;
		}

		long index = elapsed / cycle;
		Storm run = storm;
		if (run == null || run.index() != index) {
			run = new Storm(index, baseAngle(granted, index), sweptSets(count));
			storm = run;
			// 출발을 알린다. 가장자리는 반대편 사람에게서 80칸이라 한 점에서 울리면 아무것도
			// 안 들린다 — 사람마다 그 자리에서 울린다.
			announce(end, present);
		}

		double distance = distanceAt(step, perTick);
		for (int vortex = 0; vortex < count; vortex++) {
			Vec3 outward = outwardOf(run.baseAngle(), vortex, count);
			sweep(end, present, run.swept().get(vortex), outward, distance, risk);
		}
		warn(end, present, run, distance, perTick, count);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>상태를 들게 되면 <b>반드시 여기서 비울 것</b> — 위험은 값(레코드)이라 정적 맵을 쓰게 되고,
	 * 빠뜨리면 지난 판의 소용돌이가 다음 판에서 이어 돈다. 컴파일도 시험도 조용한 사고다.
	 *
	 * <p>사람에게 되돌릴 것은 없다. 이 실행기가 사람에게 하는 일은 <b>그 틱에 끝나는</b> 피해와
	 * 속도 한 번뿐이라 상태이상도, 띄워 둔 몸도, 면제해 줄 낙하 거리도 없다. 판에 남기는 것도 없다 —
	 * 파티클과 소리는 그 틱에 끝나고 블록은 한 칸도 건드리지 않는다.
	 */
	public static void clearState() {
		storm = null;
	}

	/**
	 * 지난 전투의 찌꺼기를 버린다. 매 틱 불리고 전투가 바뀐 그 틱에만 실제로 지운다.
	 *
	 * @param granted 카드를 받은 틱. 전투를 가르는 열쇠다
	 */
	static void beginFight(long granted) {
		if (granted == rememberedGrant) {
			return;
		}
		clearState();
		rememberedGrant = granted;
	}

	private static List<Set<UUID>> sweptSets(int count) {
		List<Set<UUID>> sets = new ArrayList<>(Math.max(0, count));
		for (int index = 0; index < count; index++) {
			sets.add(new HashSet<>());
		}
		return sets;
	}

	// ------------------------------------------------------------------ 소용돌이 하나

	/**
	 * 소용돌이 하나를 그리고, 이번 틱에 그 안에 <b>처음</b> 들어온 사람을 처리한다.
	 *
	 * <p>판정은 바닥 원 하나다({@link TrialRisks#insideMark} — 세로를 묻지 않는다. 표식이 바닥에
	 * 그려지는데 「원 위에 떠 있었으니 안 맞는다」가 되면 표식이 거짓말한 것이 된다).
	 *
	 * <p>한 번 판정한 사람은 {@code swept} 에 넣고 <b>이 소용돌이가 다시 묻지 않는다.</b> 지름
	 * 8칸을 초당 2칸으로 지나가므로 통과에 80틱이 걸리는데, 매 틱 물으면 {@code 2 × 80 = 160} 이
	 * 들어간다. 밀려난 사람이 앞쪽으로 밀려 여전히 원 안에 있는 것도 같은 문제라, 명단 없이는
	 * 넉백 자체가 즉사 장치가 된다.
	 */
	private static void sweep(ServerLevel end, List<ServerPlayer> present, Set<UUID> swept,
			Vec3 outward, double distance, TrialCatalog.Risk.EnderStorm risk) {
		Vec3 center = onGround(end, outward.scale(distance));
		draw(end, center, distance);
		for (ServerPlayer member : present) {
			UUID memberId = member.getUUID();
			if (swept.contains(memberId)) {
				continue;
			}
			if (!TrialRisks.insideMark(member.position(), center, VORTEX_RADIUS)) {
				continue;
			}
			// 이 소용돌이에서는 이것이 마지막 판정이다.
			swept.add(memberId);
			strike(end, member, outward, risk);
		}
	}

	/**
	 * 소용돌이에 닿았다. 피해와 <b>안쪽</b> 넉백이 함께 들어간다.
	 *
	 * <p>피해원에 <b>가해 개체를 달지 않는다.</b> 실체가 붙은 피해원이면 {@code LivingEntity} 가
	 * 스스로 밀어내는데, 그 밀기는 <b>사람과 가해자의 상대 위치</b>로 방향을 잡는다 — 이 카드가
	 * 절대 하면 안 되는 바로 그 계산이고, 엔드 섬 가장자리에서 그 방향은 허공이다. 넉백은
	 * {@link #push} 하나만 준다.
	 *
	 * <p>피해형은 폭발이다. 26.3 에서 {@code magic} 과 {@code dragonBreath} 는 {@code bypasses_armor}
	 * 태그에 들어 있어 방어구가 통째로 무시되고, 그러면 카드에 적힌 2 보다 실제로 더 아파진다.
	 * 폭발형은 「연쇄 포격」·「기둥 화염구」·「종말의 비」·「착지 충격」이 쓰는 이 판의 표준이고,
	 * 폭발 보호가 그대로 들어 대비한 사람이 손해 보지 않는다.
	 *
	 * <p>블록은 한 칸도 건드리지 않고 불도 붙이지 않는다.
	 */
	private static void strike(ServerLevel end, ServerPlayer member, Vec3 outward,
			TrialCatalog.Risk.EnderStorm risk) {
		Vec3 at = member.position();
		member.hurtServer(end, end.damageSources().explosion(null, null), risk.damage());
		push(member, outward, risk.knockback());
		// 긴 형태다. 맞은 사람만 보고 나머지가 못 보면 「저 사람이 물렸다」가 팀에 공유되지 않아
		// 다음 소용돌이를 못 읽는다.
		end.sendParticles(ParticleTypes.PORTAL, true, false, at.x, at.y + 0.1, at.z, 20,
				0.35, 0.05, 0.35, 0.0);
		// 「착지 충격」이 밀려날 때 내는 소리와 같은 것을 쓴다. 뜻이 같으면 소리도 같아야
		// 플레이어가 하나만 배운다 — 음만 낮춰 「무엇에 밀렸는가」를 가른다.
		end.playSound(null, at.x, at.y, at.z, SoundEvents.WIND_CHARGE_BURST.value(),
				SoundSource.HOSTILE, 1.0F, 0.5F);
	}

	/**
	 * ⚠ <b>안쪽으로만 민다.</b> 이 메서드가 이 카드의 안전장치다.
	 *
	 * <p>쓰는 방향은 {@code outward} 를 뒤집은 것, 곧 <b>소용돌이가 나아가는 방향</b> 하나뿐이다.
	 * <b>사람의 좌표가 방향 계산에 한 번도 들어가지 않는다</b> — 「사람과 소용돌이의 상대 위치」로
	 * 잡으면 소용돌이보다 바깥에 선 사람이 바깥으로, 곧 허공 쪽으로 밀린다.
	 *
	 * <p>세로 속도는 읽어서 그대로 돌려놓는다. 띄우면 낙하 피해가 붙고, 공중에서는 방향을 못 바꿔
	 * 훨씬 멀리 날아간다 — 「미는 거리」의 천장을 지키는 가장 싼 방법이 아예 안 띄우는 것이고,
	 * 덤으로 낙하 피해를 면제할 상태도 들지 않아도 된다.
	 *
	 * <p>{@code syncVelocity} 를 켜지 않으면 서버 혼자 민 것이 되어 잠시 뒤 클라이언트가 보고한
	 * 제자리로 되돌아간다({@code TrialRisks.launch} 와 같은 이유).
	 */
	private static void push(ServerPlayer member, Vec3 outward, double knockback) {
		Vec3 inward = outward.scale(-1.0);
		double distance = pushDistance(member.getX(), member.getZ(), inward,
				PUSH_BLOCKS * Math.max(0.0, knockback));
		if (!(distance > 0.0)) {
			return;
		}
		double speed = pushVelocity(distance);
		Vec3 motion = member.getDeltaMovement();
		member.setDeltaMovement(inward.x * speed, motion.y, inward.z * speed);
		member.syncVelocity = true;
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 폭풍이 떴다. 사람마다 그 자리에서 한 번 울린다.
	 *
	 * <p>{@link TrialWarning#stageFor} 의 층은 100틱 안쪽에서만 뜨는데 소용돌이는 <b>400틱</b>
	 * 떨어진 곳에서 출발한다. 이 한 번이 없으면 「무언가 시작됐다」를 말하는 신호가 아예 없고,
	 * 화면을 안 보고 있던 사람은 20초 뒤에야 알게 된다.
	 */
	private static void announce(ServerLevel end, List<ServerPlayer> present) {
		for (ServerPlayer member : present) {
			Vec3 at = member.position();
			end.playSound(null, at.x, at.y, at.z, SoundEvents.PORTAL_TRIGGER, SoundSource.HOSTILE,
					0.7F, 0.5F);
		}
	}

	/**
	 * 다가오는 소용돌이를 소리로 알린다.
	 *
	 * <p>층과 소리는 {@link TrialWarning} 의 것을 그대로 쓴다. 사람이 다른 카드에서 이미 배운
	 * 신호라 새로 배울 것이 없다.
	 *
	 * <p><b>닿지 않는 자리에 선 사람에게는 울리지 않는다.</b> 「경고는 들었는데 아무 일도 없다」가
	 * 되면 그 경험 하나가 다음 경고까지 무시하게 만든다({@code TrialEnderPulse.warn} 와 같은 판단).
	 *
	 * <p>소용돌이가 둘이라 <b>가장 먼저 닿는 쪽</b>만 센다. 둘을 따로 울리면 같은 틱에 두 번 울려
	 * 층이 소음이 된다. 이미 판정이 끝난 소용돌이는 빼고 본다 — 지나간 것이 계속 경고하면 다음
	 * 폭풍의 예고와 섞인다.
	 *
	 * <p>소리는 사람마다 그 자리에서 울린다. 한 점에서 울리면 16칸 밖에는 안 들린다.
	 */
	private static void warn(ServerLevel end, List<ServerPlayer> present, Storm run,
			double distance, double perTick, int count) {
		for (ServerPlayer member : present) {
			Vec3 at = member.position();
			int remaining = Integer.MAX_VALUE;
			for (int vortex = 0; vortex < count; vortex++) {
				if (run.swept().get(vortex).contains(member.getUUID())) {
					continue;
				}
				int ticks = ticksUntilHit(at, outwardOf(run.baseAngle(), vortex, count), distance,
						perTick);
				if (ticks != NEVER) {
					remaining = Math.min(remaining, ticks);
				}
			}
			if (remaining == Integer.MAX_VALUE) {
				continue;
			}
			TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
			if (stage == null || !TrialRisks.stageJustChanged(remaining)) {
				continue;
			}
			TrialWarning.sound(end, at, stage);
		}
	}

	// ------------------------------------------------------------------ 그리기

	/**
	 * 소용돌이 하나를 그린다. <b>바닥 원이 닿는 범위고 기둥은 연출</b>이다.
	 *
	 * <p>바닥 원은 규약의 <b>파랑</b>({@link TrialWarning.Colors#SHOVE})이다. 규약은
	 * 빨강=서 있으면 죽는다 · 노랑=번개 · 파랑=밀려난다 · 보라=너 하나를 노린다인데, 이 카드는
	 * <b>밀려나는 것이 본체</b>라 파랑이 정확히 그 뜻이다. 빨강을 쓰면 「자리 폭격」·「기둥 화염구」·
	 * 「연쇄 포격」의 빨강과 섞여 무엇이 오는지 안 읽힌다. 색을 여기서 고른 것은
	 * {@code TrialRisks.markColor} 가 {@code Impact} 에서 색을 끌어내는데 이 위험에는 {@code Impact}
	 * 칸이 없기 때문이고, <b>규약에 이미 있는 넷 중 하나</b>라 새 색을 만들지는 않았다.
	 *
	 * <p>기둥은 <b>엔더 입자</b>({@code PORTAL})다. 규약의 넷은 어느 것도 「소용돌이」라는 뜻이
	 * 아니고 다섯째 색을 더하면 그 순간 규약이 규약이 아니게 되므로, 기둥은 아예 <b>먼지 표식이
	 * 아닌 것</b>으로 그린다 — {@code TrialEnderPulse} 가 같은 판단을 적어 두었다. 보라가 규약에서
	 * 「너 하나를 노린다」인 것과 부딪히지 않는 이유도 그것이다. 카드가 요구한 것이 「엔더 입자
	 * 소용돌이」이기도 하다.
	 *
	 * <p>{@code PortalParticle} 은 수명이 40~49틱이고 나이에 따라 커진다. 소용돌이가 그동안
	 * 4.5칸(지름의 절반쯤) 움직이므로 <b>지나온 자리에 꼬리가 한 마디 남고</b>, 그것이 「어디서
	 * 와서 어디로 가는가」를 말한다. 그보다 길게 남지 않아 아레나를 흐리지도 않는다.
	 */
	private static void draw(ServerLevel end, Vec3 center, double distance) {
		// 바닥 원. TrialWarning 이 긴 형태로 보내고 지면에서 0.15 띄우는 몫까지 제가 한다.
		TrialWarning.markGround(end, center, VORTEX_RADIUS, TrialWarning.dust(markColor()));
		column(end, center, distance);
	}

	/**
	 * 바닥 원의 색. 까닭은 {@link #draw} 에 적어 두었다.
	 *
	 * <p>메서드로 빼 둔 것은 <b>시험이 물을 수 있게</b> 하기 위해서다. {@code Colors} 의 값은
	 * 컴파일 상수라 그냥 쓰면 클래스 파일에 숫자로 박혀, 「규약의 파랑을 쓰는가」를 바이트코드로는
	 * 물을 수 없다. {@code TrialLandingShock.markColor} 가 같은 이유로 있다.
	 *
	 * <p>{@code TrialRisks.markColor} 를 쓰지 않는 것은 그쪽이 {@code Impact} 에서 색을 끌어내는데
	 * 이 위험에는 {@code Impact} 칸이 없기 때문이다. {@code TrialRisks.markColor} 와 {@code docs/}
	 * 는 손대지 않았다 — 그 둘은 다른 사람이 쓰고 있다.
	 */
	static int markColor() {
		return TrialWarning.Colors.SHOVE;
	}

	/**
	 * 바닥 원 위에 도는 기둥을 세운다.
	 *
	 * <p><b>첫 {@code boolean} 을 {@code false} 로 되돌리지 말 것.</b> 짧은 형태는 서버에서 32칸에
	 * 잘리고 클라이언트가 한 번 더 거른다({@link TrialWarning} 의 「거리 제한을 끄고 보낸다」).
	 * 이 소용돌이는 반경 40 가장자리에서 출발하므로, 되돌리는 순간 <b>반대편 사람에게는 폭풍이
	 * 통째로 보이지 않는다.</b>
	 *
	 * <p>둘째 {@code boolean}({@code alwaysShow}) 은 첫 깃발이 켜져 있으면 무의미하고, 사용자의
	 * 「파티클 줄이기」 설정을 우리가 뒤집을 이유도 없어 {@code false} 로 둔다.
	 *
	 * <p>도는 각을 <b>남은 거리</b>에서 뽑는다. 까닭은 {@link #COLUMN_SPIN_PER_BLOCK} 에 적어 두었다.
	 */
	private static void column(ServerLevel end, Vec3 center, double distance) {
		double spin = distance * COLUMN_SPIN_PER_BLOCK;
		for (int index = 0; index < COLUMN_POINTS; index++) {
			double along = (double) index / COLUMN_POINTS;
			double angle = spin + along * Math.PI * 2.0 * COLUMN_TURNS;
			double radius = VORTEX_RADIUS * (1.0 - along * (1.0 - COLUMN_TOP_FACTOR));
			end.sendParticles(ParticleTypes.PORTAL, true, false,
					center.x + Math.cos(angle) * radius,
					center.y + GROUND_OFFSET + along * COLUMN_HEIGHT,
					center.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 그 점을 지면 높이에 얹는다.
	 *
	 * <p>소용돌이 <b>중심에서 한 번만</b> 잰다. 점마다 재면 한 틱에 하이트맵을 백 번 두드리고,
	 * 그것을 20초 내내 한다 — 이 카드는 블록을 한 칸도 건드리지 않으니 그동안 지면이 바뀌지도
	 * 않는다. 소용돌이가 움직이므로 중심에서 재는 것만으로도 경사를 따라간다.
	 *
	 * <p>바닥에서 띄우는 몫은 얹지 않는다. {@link TrialWarning#markGround} 가 제 몫으로 0.15 를
	 * 더하므로 여기서 또 더하면 원이 두 번 뜬다.
	 */
	private static Vec3 onGround(ServerLevel end, Vec3 flat) {
		BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				BlockPos.containing(flat.x, 0.0, flat.z));
		// 허공이면 하이트맵이 월드 바닥을 돌려준다. 섬이 끊긴 바깥에서 그리는 것을 그만두면
		// 「저쪽은 안전한가」로 읽히므로 중앙 섬 표면 높이로 이어 그린다.
		double y = ground.getY() > end.getMinY() ? ground.getY() : FALLBACK_GROUND_Y;
		return new Vec3(flat.x, y, flat.z);
	}

	// ------------------------------------------------------------------ 사람 고르기

	/**
	 * 지금 이 차원에 있고 판정을 받을 수 있는 팀원.
	 *
	 * <p><b>차원을 반드시 본다.</b> {@code DragonTrialManager} 가 넘기는 목록은 「접속해 있는
	 * 팀원」이라 <b>다른 차원에 있는 사람도 들어 있다.</b> 판정이 아레나 중앙에서 잰 수평 거리로만
	 * 도므로, 보지 않으면 오버월드 원점 근처에 선 팀원이 엔드의 소용돌이에 맞는다.
	 */
	private static List<ServerPlayer> present(ServerLevel end, List<ServerPlayer> members) {
		List<ServerPlayer> here = new ArrayList<>();
		for (ServerPlayer member : members) {
			if (member != null && member.isAlive() && !member.isSpectator()
					&& member.level() == end) {
				here.add(member);
			}
		}
		return here;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/** 카드에 적힌 초당 속도를 틱당으로 바꾼다. 초당 2칸이면 0.1 칸/틱이다. */
	static double blocksPerTick(double speedPerSecond) {
		return Math.max(0.0, speedPerSecond) / 20.0;
	}

	/**
	 * 가장자리에서 중앙까지 걸리는 틱.
	 *
	 * <p>출발점은 {@code TrialRisks.ARENA_RADIUS}(40) 다 — 팀을 내려놓는 자리와 같은 원이라
	 * 「아레나 가장자리」가 두 뜻을 갖지 않는다. 지금 값에서 {@code 40 / 0.1 = 400} 틱, 20초다.
	 */
	static int travelTicks(double perTick) {
		if (!(perTick > 0.0)) {
			return 0;
		}
		return (int) Math.ceil(TrialRisks.ARENA_RADIUS / perTick);
	}

	/**
	 * 폭풍 하나가 출발해 다음 폭풍이 출발할 때까지의 틱.
	 *
	 * <p>{@code +1} 은 <b>중앙에 닿는 그 틱</b>이다. 건너오는 데 {@code travel} 틱이 걸리므로
	 * 소용돌이가 살아 있는 틱은 {@code 0 … travel} 로 {@code travel + 1} 개고, 그 뒤로 카드에 적힌
	 * {@code restTicks} 가 온전히 흐른다. 이 한 틱을 빼면 소용돌이가 <b>중앙 한 칸 앞에서
	 * 증발한다</b> — 사람이 보는 것은 도착이 아니라 실종이 된다.
	 *
	 * <p>지금 값에서 {@code 400 + 1 + 500 = 901} 틱이다.
	 */
	static int cycleTicks(int travel, int restTicks) {
		return Math.max(0, travel) + 1 + Math.max(0, restTicks);
	}

	/** 출발하고 {@code step} 틱 뒤 소용돌이 중심의 중앙에서의 거리. 0 에서 멈춘다. */
	static double distanceAt(int step, double perTick) {
		if (step <= 0) {
			return TrialRisks.ARENA_RADIUS;
		}
		return Math.max(0.0, TrialRisks.ARENA_RADIUS - step * Math.max(0.0, perTick));
	}

	/**
	 * {@code vortex} 번째 소용돌이가 서 있는 방향. <b>중앙에서 소용돌이를 가리키는</b> 단위 벡터다.
	 *
	 * <p>고르게 나눠 놓는다. 둘이면 정확히 마주 보고 오므로 가장 오래 떨어져 있고, 겹치는 것은
	 * 중앙 근처 마지막 몇 초뿐이다 — 무작위로 둘을 뽑으면 나란히 붙어 와서 아레나 한쪽만
	 * 위험해지거나, 겹쳐 와서 「닿으면 4」인 구역이 40칸 내내 따라온다.
	 *
	 * <p>높이는 0 이다. 판정도 그리기도 위에서 본 좌표로만 돈다.
	 */
	static Vec3 outwardOf(double baseAngle, int vortex, int count) {
		if (count <= 0) {
			return new Vec3(1.0, 0.0, 0.0);
		}
		double angle = baseAngle + Math.PI * 2.0 * vortex / count;
		return new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
	}

	/**
	 * 이번 폭풍이 어느 방향에서 오는가(라디안).
	 *
	 * <p>고정하면 안 된다 — 같은 선으로만 오면 두 번째 판부터 축에서 비켜 서 있는 것만으로 카드가
	 * 통째로 무효가 된다.
	 *
	 * <p>그렇다고 {@code end.getRandom()} 으로 굴려 들고 있지도 않는다. 그러면 <b>서버가 폭풍
	 * 도중에 내려갔다 올라올 때</b> 각이 새로 굴려져 이미 보여 준 소용돌이가 아레나 반대편으로
	 * 순간이동한다 — <b>그려 놓은 표식을 무르지 않는 것</b>이 이 전투의 약속이다. 받은 틱과 주기
	 * 번호만으로 정하면 상태 없이도 같은 선이 다시 나온다.
	 *
	 * <p>{@code granted} 를 섞는 것은 판마다 위상이 달라지게 하기 위해서다. 주기 번호만 쓰면
	 * 모든 판의 첫 폭풍이 같은 방향에서 온다. 주기 번호를 {@link #ANGLE_SEED_STRIDE} 만큼 띄우는
	 * 까닭은 그 상수에 적어 두었다.
	 */
	static double baseAngle(long granted, long index) {
		return RandomSource.create(granted + index * ANGLE_SEED_STRIDE).nextDouble()
				* Math.PI * 2.0;
	}

	/**
	 * 그 사람에게 소용돌이 앞머리가 닿기까지 남은 틱. 영영 안 닿으면 {@link #NEVER}.
	 *
	 * <p>소용돌이 중심은 {@code outward} 축 위를 {@code distance} 에서 0 까지 내려온다. 사람을
	 * 그 축에 내리면 <b>축 위 좌표</b>와 <b>축에서 벗어난 거리</b> 둘이 나오는데, 벗어난 거리가
	 * 반경보다 크면 그 사람 위로는 지나가지 않는다.
	 *
	 * <p>닿는 사람은 원의 <b>앞머리</b>가 먼저 온다. 중심이 {@code 축 위 좌표 + √(반경² - 벗어난²)}
	 * 에 왔을 때가 그 순간이다. 그 값이 음수면 소용돌이가 중앙(0)에서 멈춘 뒤에야 닿는다는 뜻이라
	 * 역시 안 닿는다 — 중앙 <b>너머</b>에 선 사람이 여기 걸린다.
	 *
	 * <p>남은 틱이 <b>1씩</b> 줄어드는 것이 중요하다. 거리가 매 틱 정확히 {@code perTick} 만큼
	 * 줄므로 그대로 {@link TrialRisks#stageJustChanged} 를 쓸 수 있다 — 층이 건너뛰거나 두 번
	 * 울릴 자리가 없다.
	 *
	 * @param at       사람의 자리. 높이는 보지 않는다
	 * @param outward  중앙에서 소용돌이를 가리키는 단위 벡터
	 * @param distance 소용돌이 중심이 지금 중앙에서 떨어진 거리
	 */
	static int ticksUntilHit(Vec3 at, Vec3 outward, double distance, double perTick) {
		if (!(perTick > 0.0)) {
			return NEVER;
		}
		double along = at.x * outward.x + at.z * outward.z;
		double sideSqr = Math.max(0.0, at.x * at.x + at.z * at.z - along * along);
		double reachSqr = VORTEX_RADIUS * VORTEX_RADIUS - sideSqr;
		if (reachSqr < 0.0) {
			// 축에서 반경보다 멀리 비켜 서 있다. 이 소용돌이는 그 사람 위를 지나가지 않는다.
			return NEVER;
		}
		double meets = along + Math.sqrt(reachSqr);
		if (meets < 0.0) {
			// 소용돌이는 중앙에서 멈춘다. 그 너머에 선 사람에게는 영영 닿지 않는다.
			return NEVER;
		}
		if (distance <= meets) {
			return 0;
		}
		return (int) Math.ceil((distance - meets) / perTick);
	}

	/**
	 * ⚠ 실제로 밀 거리. <b>중앙에서 멀어지지 않는 만큼만</b> 돌려준다.
	 *
	 * <h2>이 함수가 이 카드를 허용된 예외로 만든다</h2>
	 *
	 * <p>미는 방향 {@code inward} 는 사람과 무관하게 <b>소용돌이가 나아가는 쪽</b>이다. 그래도
	 * 사람이 어디에 서 있느냐에 따라 그 방향이 <b>중앙에서 멀어지는 쪽</b>일 수 있다 — 소용돌이가
	 * 중앙 가까이 왔을 때 그 <b>너머</b>에 선 사람이 그렇다. 거기서 밀면 이 카드가 남의 사고를
	 * 완성시킨다.
	 *
	 * <p>그래서 산수가 아니라 함수로 막는다. 중앙에서 잰 거리의 제곱
	 * {@code |p + s·d|² = |p|² + 2s(p·d) + s²} 는 {@code s} 에 대한 아래로 볼록한 이차식이고
	 * <b>{@code s* = -(p·d)} 에서 가장 작다.</b> 곧 {@code [0, s*]} 구간에서는 밀리는 내내 거리가
	 * 줄기만 한다. 여기서 자르면
	 *
	 * <ul>
	 *   <li>밀린 <b>뒤</b>가 밀리기 <b>전</b>보다 중앙에 가깝다</li>
	 *   <li>밀리는 <b>도중</b>의 어느 점도 출발점보다 멀지 않다 — 경사에 걸려 멈춰도 안전하다</li>
	 *   <li>{@code s*} 가 0 이하면 한 칸도 밀지 않는다. 이미 지나쳐 선 사람이다</li>
	 * </ul>
	 *
	 * <p><b>세기와 무관하다.</b> {@link #PUSH_BLOCKS} 를 몇으로 올리든, 다른 카드의 넉백이 먼저
	 * 사람을 가장자리로 데려다 놓았든, 이 카드가 미는 목적지는 언제나 출발점 이내다. 산수는 값을
	 * 고치는 사람이 안 볼 수 있지만 이 함수는 못 피한다.
	 *
	 * @param x      사람의 x. 아레나 중앙이 {@code (0, 0)} 이다
	 * @param z      사람의 z
	 * @param inward 미는 방향. <b>단위 벡터여야 한다</b>
	 * @param wanted 카드가 시킨 거리
	 */
	static double pushDistance(double x, double z, Vec3 inward, double wanted) {
		if (!(wanted > 0.0)) {
			return 0.0;
		}
		double closest = -(x * inward.x + z * inward.z);
		if (!(closest > 0.0)) {
			return 0.0;
		}
		return Math.min(wanted, closest);
	}

	/**
	 * 그 거리를 밀려면 실어야 하는 처음 속도(칸/틱).
	 *
	 * <p><b>공중 감쇠로 계산한다.</b> 처음 속도 {@code v} 인 몸이 계속 떠 있다면 총 이동은
	 * {@code v + 0.91v + 0.91²v + … = v / (1 - 0.91)} 이라, 거꾸로 {@code v = 거리 × 0.09} 다.
	 * 8칸이면 0.72 칸/틱.
	 *
	 * <p>바닥 모델(마찰 0.546)로 잡으면 안 된다. 같은 8칸을 맞추려면 3.6 칸/틱을 실어야 하는데,
	 * 그 속도로 한 틱이라도 떠 있으면 40칸을 날아간다. 공중 모델은 반대로 <b>적힌 거리를 넘을 수
	 * 없고</b>, 바닥에 붙어 있으면 마찰이 먼저 먹어 5분의 1 남짓만 밀린다.
	 *
	 * <p>이 카드에서 밀리는 방향은 안쪽이라 「멀리 날아가는 것」 자체가 위험은 아니지만, 그래도
	 * 언제나 <b>덜 미는 쪽</b>으로 어긋나는 모델을 쓴다 — 「착지 충격」과 같은 식이라 두 카드가 같은
	 * 세기로 같은 만큼 민다.
	 */
	static double pushVelocity(double distance) {
		return Math.max(0.0, distance) * (1.0 - AIR_DRAG);
	}

	/**
	 * 한 폭풍이 팀에 넣을 수 있는 <b>가장 큰 합계 피해.</b>
	 *
	 * <p>공유 체력에서 범위 피해는 팀원별로 그대로 합산된다. 이 카드는 닿은 사람 전원을 때리고,
	 * 소용돌이마다 명단이 따로라 중앙에서 겹치는 자리에서는 사람당 {@code count} 번까지 맞는다 —
	 * 그 곱이 팀 체력 20 을 넘는지를 카드 값에서 직접 세어 두는 자리다.
	 *
	 * <p>{@code TrialRisks.worstCaseTickDamage} 가 이 위험을 {@code hits(damage, count)} 로 세는
	 * 것과 짝이다. 그쪽은 <b>한 사람</b>이 받는 값이고 이쪽은 <b>팀 합계</b>다.
	 */
	static float worstCaseTeamDamage(float damage, int count, int members) {
		if (damage <= 0.0F || count <= 0 || members <= 0) {
			return 0.0F;
		}
		return damage * count * members;
	}

	/** 한 틱에 바닥과 기둥으로 나가는 점 수. 「예산 안인가」를 숫자로 묻는 값이다. */
	static int markPoints(int count) {
		return Math.max(0, count) * (TrialWarning.ringPoints(VORTEX_RADIUS) + COLUMN_POINTS);
	}
}
