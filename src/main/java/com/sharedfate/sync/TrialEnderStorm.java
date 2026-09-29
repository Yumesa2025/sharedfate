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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.EnderStorm} 실행기 — 엔더폭풍.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p>아레나 가장자리에서 중앙으로 소용돌이 둘({@code count})이 초당 2칸({@code speedPerSecond})씩
 * 전진한다. 닿으면 피해 2({@code damage}) 와 강한 넉백({@code knockback} = 3.0 배).
 *
 * <p>소용돌이가 중앙에 닿으면 25초({@code restTicks}) 뒤에 새로 둘이 시작한다.
 *
 * <h2>⚠ 넉백 방향은 <b>바깥</b>이다 — 전에는 안쪽이었다</h2>
 *
 * <p><b>전에는 안쪽</b>, 곧 폭풍이 나아가는 쪽으로 밀었다. 엔드 중앙 섬 바깥은 허공이고 공유
 * 체력이라 한 사람의 낙사가 팀 전체를 끝내므로, 「안쪽으로만 민다」가 이 카드를 <b>이 저장소의
 * 「강한 넉백 금지」에서 예외로 만든 조건</b>이었다.
 *
 * <p>그런데 사람이 실제로 플레이해 보고 <b>「한번 밀쳐지면 끝」</b>이라고 했다. 폭풍이 오는
 * 방향으로 떠밀려 들어가니 밀린 그 틱에 이미 소용돌이 밖이고, 한 번 맞고 끝이었다. 그래서
 * 사람이 셋을 정했다 — <b>세기 3배 · 방향 바깥 · 닿아 있는 동안 계속.</b> 여기 적힌 것은 그
 * 결정을 받아 적은 것이지 이 파일이 고른 것이 아니다.
 *
 * <p>방향이 바깥이면 폭풍 앞에서 계속 떠밀리므로 <b>옆으로 빠져나가야</b> 살아남는다. 대신
 * <b>「안쪽으로만 민다」는 보호가 통째로 사라졌다.</b> 폭풍은 가장자리에서 중앙으로 오므로
 * 바깥은 곧 허공이고, 계속 밀리면 반드시 넘어간다.
 *
 * <h2>⚠⚠ 그래서 <b>천장</b>이 이제 유일한 안전장치다</h2>
 *
 * <p>지키는 방법이 셋이고, <b>첫째가 전부</b>다.
 *
 * <ol>
 *   <li><b>미는 자리에 천장을 건다.</b> {@link #pushDistance} 가 <b>목적지가 섬 안</b>인
 *       거리까지만 돌려준다. 어떤 세기를 넣어도, 몇 번을 연속으로 밀려도, 밀리는 도중의 어느
 *       점도 {@link #pushLimitRadius} 안이다. 산수는 값을 고치는 사람이 안 볼 수 있지만 이
 *       함수는 못 피한다. <b>지우면 이 카드는 그날로 전멸 카드다</b></li>
 *   <li><b>방향을 사람에게서 구하지 않는다.</b> {@link #push} 가 쓰는 벡터는 <b>소용돌이가
 *       나아가는 방향의 반대</b>({@code +outward}) 하나뿐이고, 사람의 좌표는 한 번도 들어가지
 *       않는다. 「사람과 소용돌이의 상대 위치」로 방향을 잡으면 소용돌이보다 안쪽에 선 사람이
 *       <b>중앙을 가로질러</b> 반대편으로 날아가 예측이 불가능해진다 — 그 계산은 이 파일에 한
 *       줄도 없다</li>
 *   <li><b>세로로 한 칸도 띄우지 않는다.</b> 띄우면 낙하 피해가 붙고, 바닥 마찰(0.546)이 안
 *       먹어 공중 감쇠(0.91)만 남으므로 적힌 거리를 <b>끝까지</b> 날아간다. {@link #push} 는
 *       세로 속도를 읽어 그대로 돌려놓는다</li>
 * </ol>
 *
 * <p>천장이 실제로 얼마나 일하는지 세어 두면 이렇다. 배율 3.0 이면 적히는 거리가 24칸이고,
 * 바닥에 붙어 있으면 마찰이 먹어 <b>4.8칸</b>만 밀리지만 <b>점프해 있으면 24칸을 그대로
 * 날아간다.</b> 아레나 반경이 40 이므로 천장이 없으면 그 한 번으로 허공이다.
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
 *   <li><b>벽</b>(아레나를 가로지르는 띠) — 비킬 곳이 옆이 아니라 <b>뒤</b>뿐이다. 그런데 이
 *       카드는 바깥으로 미는 쪽으로 바뀌었고, <b>옆으로 빠지는 것이 남은 유일한 생존 수단</b>
 *       이다. 옆을 막아 놓고 바깥으로 밀면 요구하는 행동이 아예 없는 카드가 된다</li>
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
 *   <li><b>대응이 「모이기」가 아니라 「비키기」</b>다. 소용돌이는 지름 12칸짜리 둘이고 아레나는
 *       지름 80칸이라, 넷이 붙어 선 채로 다 같이 옆으로 비켜도 아무도 안 맞는다 — 회피가 팀을
 *       흩어 놓지 않는다</li>
 *   <li>팀에 한 번만 넣으면 <b>셋이 비키든 넷이 비키든 한 사람만 안 비키면 같은 2</b> 라
 *       「나는 안 비켜도 된다」가 성립하고, 회피 카드가 회피를 못 가르친다. 「착지 충격」이
 *       전원 타격인 것과 같은 까닭이다</li>
 * </ul>
 *
 * <p>그 대신 <b>피해</b> 천장이 낮다(미는 거리의 천장과는 다른 이야기다).
 * {@link #worstCaseTeamDamage} 가 <b>넷이 소용돌이 둘에 다 맞는</b> 값을
 * 직접 센다 — {@code 2 × 2 × 4 = 16} 으로 팀 공유 체력 20 아래다. 소용돌이 둘은 중앙 근처에서만
 * 겹치므로 16 은 <b>네 사람이 20초 내내 중앙에 서 있었다</b>는 뜻이고, 그러고도 살아남는다.
 * <b>피해 2 는 전원 타격을 전제로만 설명되는 값</b>이니 여기를 올리려는 사람은 그 곱부터 볼 것.
 *
 * <p>넉백을 넷이 다 받는 것도 이 카드에서는 위험하지 않다. 넷이 통째로 굴러가는 방향이
 * <b>바깥</b>이지만, 굴러가는 자리가 {@link #pushLimitRadius} 안으로 잘리기 때문이다. 넷이
 * 같은 방향으로 밀리는 것 자체는 막지 않는다 — 소용돌이가 마주 보고 오므로 한쪽에 밀린 팀은
 * 반대쪽 소용돌이에서 멀어진다.
 *
 * <h2>⚠ 피해는 한 번, 넉백은 계속 — <b>둘을 갈라 센다</b></h2>
 *
 * <p>소용돌이가 사람을 통과하는 데 여러 틱이 걸린다(지름 12칸을 초당 2칸으로 지나가므로
 * <b>120틱</b>이다. <b>전에는 반경 4 · 80틱이었다</b> — 사람이 「폭풍크기는 50프로 키워도
 * 좋을거같아」라고 해서 6 으로 올렸고, 통과 시간이 그만큼 길어졌다).
 *
 * <ul>
 *   <li><b>피해는 소용돌이당 한 번</b>이다({@code Storm.swept}). 매 틱 2씩이면
 *       {@code 2 × 120 = 240} 으로 팀 체력 20 의 열두 배라 그 자리에서 전멸이다 — 반경을
 *       키우면서 이 명단이 더 중요해졌다</li>
 *   <li><b>넉백은 닿아 있는 동안 계속</b>이다({@code Storm.shoved}). 사람이 「계속 밀쳐지게」
 *       하라고 정한 것이 이 카드의 수정 내용 전부다. 다만 <b>매 틱은 아니고</b>
 *       {@link #SHOVE_INTERVAL_TICKS} 마다다 — 까닭은 그 상수에 적어 두었다</li>
 * </ul>
 *
 * <p><b>이 둘을 다시 하나로 묶지 말 것.</b> 넉백을 명단에 도로 넣으면 사람이 고쳐 달라고 한
 * 「한번 밀쳐지면 끝」으로 돌아가고, 피해를 명단에서 빼면 즉사 카드가 된다.
 *
 * <p>바닐라 피격 무적시간(10틱)에 기대지 않는다. 기대면 120틱짜리 통과에서 열두 번이 들어가
 * 「피해 2」가 조용히 24 가 되고, 무적시간이 다른 카드에 먼저 쓰이면 그마저 어긋난다.
 *
 * <p>명단이 한 <b>주기</b>가 아니라 한 <b>소용돌이</b>마다인 것이 중요하다. 둘이 중앙에서 겹치는
 * 자리는 각각 한 번씩 물어 2 + 2 = 4 이고, 그것이 {@code TrialRisks.worstCaseTickDamage} 가
 * 이 위험을 {@code hits(damage, count)} 로 세는 값과 같다.
 *
 * <p>넉백 쪽 명단은 반대로 <b>소용돌이마다가 아니라 폭풍마다</b> 하나다. 소용돌이 둘이 겹친
 * 자리에서 같은 틱에 두 번 밀면 방향이 서로 반대(마주 보고 오므로)라 사람이 제자리에서 떨리기만
 * 한다 — 밀린 것도 안 밀린 것도 아닌 상태가 된다.
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
	 * <h2>전에는 4 였다 — 사람이 50% 키우라고 했다</h2>
	 *
	 * <p>카드에 적혀 있지 않아 이 파일이 정한 값이었고, 처음에는 「비키면 반드시 산다」와 「비킬
	 * 곳은 남는다」 사이에서 4 를 골랐다. 그런데 사람이 실제로 플레이해 보고 <b>「폭풍크기는
	 * 50프로 키워도 좋을거같아」</b>라고 했다. <b>6 은 그 말을 받아 적은 것</b>이지 이 파일이 다시
	 * 계산해 고른 값이 아니다.
	 *
	 * <h2>6 에서도 두 조건이 살아 있는가 — 다시 세어 봤다</h2>
	 *
	 * <ul>
	 *   <li><b>비키면 산다</b> — 정면에 선 사람이 빠져나가려면 옆으로 반경만큼 가야 하는데, 6 은
	 *       걸어서(4.317칸/초) 1.39초다. 그동안 소용돌이는 2.8칸밖에 못 오므로 여전히
	 *       <b>걸어서 빠져나간다.</b> 넷이 흩어지는 데 쓰는 시간
	 *       ({@code TrialWarning.TICKS_SCATTER} = 80틱)보다도 짧다</li>
	 *   <li><b>비킬 곳은 남는다</b> — 소용돌이 둘이 훑는 넓이가 {@code 2 × 12 × 40 = 960} 칸²
	 *       이고 아레나({@code π × 40² ≈ 5027})의 <b>19%</b> 다. 4 였을 때는 13% 였다.
	 *       20초에 19% 는 아직 「한 번은 비켜야 하는」 값이지 「서 있기만 해도 늘 맞는」 값이
	 *       아니지만, <b>여유가 그만큼 줄었다</b>는 것은 적어 둔다</li>
	 * </ul>
	 *
	 * <p>이 판의 다른 고리(「낙뢰」 3, 「종말의 비」 2.5, 「연쇄 포격」 3.5)보다 훨씬 크다. 그것들은
	 * 한 틱에 터지지만 이쪽은 <b>20초 동안 다가오는 것이 보이므로</b>, 커도 「못 피한다」가 되지
	 * 않는다.
	 *
	 * <h2>⚠ 반경을 만지면 함께 움직이는 것들</h2>
	 *
	 * <ul>
	 *   <li><b>통과 시간</b> — 지름을 초당 2칸으로 지나가므로 {@code 2 × 반경 / 0.1} 틱이다.
	 *       4 에서 80틱이던 것이 6 에서 <b>120틱</b>이다. 그만큼 <b>밀리는 횟수</b>도 는다
	 *       (8틱마다이므로 8번 → <b>15번</b>). 둘 다 시험이 센다</li>
	 *   <li><b>피해</b>는 안 는다. 소용돌이당 한 번이기 때문이다({@code Storm.swept}) — 그 명단이
	 *       없으면 120틱 × 2 = 240 이다</li>
	 *   <li><b>점 수</b> — 바닥 원이 커진다({@link #markPoints}). 4 에서 소용돌이당 91점이던
	 *       것이 6 에서 116점이고 둘이면 232 다({@link #MAX_POINTS_PER_TICK} 참고)</li>
	 *   <li><b>{@link #worstCaseTeamDamage}</b> — 둘이 겹치는 구역이 넓어져 넷이 둘 다 맞는 일이
	 *       실제로 일어난다. 그래도 {@code 2 × 2 × 4 = 16} 으로 팀 체력 아래다</li>
	 *   <li><b>{@link #ticksUntilHit}</b> — 예고도 이 반경으로 돈다. 축에서 6칸 안쪽에 선
	 *       사람까지 경고를 듣는다. 판정과 같은 값을 쓰므로 저절로 따라온다</li>
	 *   <li><b>천장({@link #PUSH_LIMIT_MARGIN})은 따라오지 않는다.</b> 그쪽의 셋째 근거가 「소용돌이
	 *       하나를 흘려보낼 만큼」이었는데 지름이 12 가 되면서 깨졌다 — 까닭은 그 상수에 적어
	 *       두었다</li>
	 * </ul>
	 */
	static final double VORTEX_RADIUS = 6.0;

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
	 * 값에서 직접 세고, 지금 값에서는 소용돌이당 116점({@code 고리 76 + 기둥 40})으로 둘이
	 * <b>232</b> 다.
	 *
	 * <p><b>전에는 91점 · 둘이 182 였다.</b> 반경을 4 에서 6 으로 올리면서 바닥 고리가 51점에서
	 * 76점이 됐다({@code TrialWarning.ringPoints} 가 둘레를 0.5칸 간격으로 나누고 80 에서 자른다).
	 * <b>기둥은 그대로 40점</b>이다 — 점 수가 반경을 안 보기 때문이고, 그래서 굵어진 기둥의 나선이
	 * 전보다 성기다. 예산에 여유가 있으니 나중에 늘려도 되지만 사람이 시킨 것은 크기뿐이라 두었다.
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
	 * <p>카드가 적는 배율은 지금 <b>3.0</b> 이라 이 카드가 실제로 부탁하는 거리는 24칸이다. 그
	 * 값은 사람이 플레이해 보고 정했고 <b>여기서 바꾸지 않는다</b> — 받은 값을 그대로 쓴다.
	 *
	 * <p>적힌 값은 <b>천장</b>이지 실제로 밀리는 거리가 아니다. {@link #pushVelocity} 가 공중
	 * 감쇠로 속도를 잡으므로 바닥에 붙어 있으면 마찰(0.546)이 먼저 먹어 적힌 값의 <b>5분의 1
	 * 남짓</b>만 밀린다 — 배율 3.0 에서 24칸을 실어도 발이 땅에 붙어 있으면 4.8칸이다. 적힌
	 * 거리를 다 날아가는 것은 <b>점프해 있을 때뿐</b>이고, 그때를 막는 것이
	 * {@link #pushDistance} 의 천장이다.
	 *
	 * <p><b>이 값이 안전을 지키는 것이 아니다.</b> 안전은 {@link #pushDistance} 가 지킨다 — 여기를
	 * 몇으로 올리든, 카드 배율을 몇으로 올리든, 밀린 사람의 목적지는 섬 안이다. 그러니 여기를
	 * 만지는 사람이 물어야 할 것은 「위험한가」가 아니라 「같은 신호에 세기가 둘이 되는가」다.
	 */
	static final double PUSH_BLOCKS = 8.0;
	/**
	 * ⚠ 천장을 섬 경계에서 이만큼 안쪽에 둔다(칸). {@link #pushLimitRadius} 가 쓴다.
	 *
	 * <h2>왜 경계에 딱 세우면 안 되는가</h2>
	 *
	 * <p>천장은 <b>우리가 실은 속도만</b> 센다. 바닐라는 밀리는 동안에도 사람의 이동 입력을 그대로
	 * 받으므로, 밀리는 방향으로 같이 달리면 계산한 목적지보다 조금 더 나간다. 무엇보다 <b>경계에
	 * 세워 놓으면 그 다음은 사람의 한 걸음</b>이다 — 밀려 넘어진 자리가 곧 벼랑이면 이 카드는
	 * 낙사를 「직접」 시키지만 않을 뿐 낙사 장치다.
	 *
	 * <h2>왜 8 인가</h2>
	 *
	 * <p>처음에는 같은 수가 세 방향에서 나왔다. 지금은 <b>둘만 남았다.</b>
	 *
	 * <ul>
	 *   <li>이 저장소가 「강한 넉백」 한 번 치로 쓰는 거리가 {@link #PUSH_BLOCKS}(8)다. 다른
	 *       카드가 배율 1.0 으로 한 번 더 밖으로 밀어도 여전히 섬 안이다</li>
	 *   <li>달리기는 초당 5.6칸(0.28 칸/틱)이라 8칸이 <b>29틱</b>이다. 이 판이 「옆으로 비킬
	 *       시간」으로 쓰는 {@code TrialWarning.TICKS_SIDESTEP}(30)과 거의 같다 — 천장에
	 *       떨궈진 사람에게 <b>정신 차리고 돌아설 시간이 한 번치</b> 남는다</li>
	 *   <li><s>소용돌이 지름이 8칸이다. 천장에 붙은 사람이 소용돌이 하나를 통째로 옆으로
	 *       흘려보낼 만큼은 남는다</s> — ⚠ <b>이 근거는 깨졌다.</b> 사람이 폭풍을 50% 키우라고
	 *       해서 {@link #VORTEX_RADIUS} 가 6 이 되었고, 지름이 <b>12칸</b>이라 여유 8 로는 더 이상
	 *       흘려보낼 수 없다. <b>천장은 그대로 두라는 것이 사람의 지시</b>였고, 실제로 이 여유가
	 *       하는 일은 「벼랑 끝에 세우지 않는 것」이라 위의 두 근거만으로도 선다. 다만 <b>천장에
	 *       밀려 붙은 사람은 이제 소용돌이를 옆으로 흘려보낼 자리가 없다</b> — 앞으로 나오는
	 *       수밖에 없고, 그것이 이 카드가 어려워진 방식이다</li>
	 * </ul>
	 *
	 * <p>줄이려는 사람은 위를 함께 볼 것. <b>0 으로 두면 천장이 섬 경계와 같아져</b> 밀린
	 * 사람이 벼랑 끝에 선다.
	 */
	static final double PUSH_LIMIT_MARGIN = 8.0;
	/**
	 * ⚠ 닿아 있는 동안 <b>몇 틱마다</b> 미는가.
	 *
	 * <h2>매 틱이면 안 되는 이유</h2>
	 *
	 * <p>매 틱 밀면 사람이 조작을 아예 못 한다. 이 카드가 요구하는 행동이 <b>「옆으로 빠져나가기」
	 * 하나</b>인데, 매 틱 속도를 덮어쓰면 옆으로 가려는 입력이 한 번도 살아남지 못해 요구한 행동을
	 * 할 수 없는 카드가 된다.
	 *
	 * <h2>전에는 10틱(0.5초)이었다 — 사람이 0.4초로 정했다</h2>
	 *
	 * <p>사람이 플레이해 보고 처음에 <b>「0.2초마다 밀치는걸로」</b>라고 했다. 그대로 하면 4틱마다
	 * 미는 것인데, <b>그러면 조작이 아예 안 된다</b>는 것을 알렸다 — 아래 「앞의 5틱」이 그
	 * 이야기다. 그 말을 듣고 사람이 <b>「0.5초말고 일단 0.4초로해봐 그럼」</b>이라고 물러섰다.
	 * <b>8 은 그 결정을 받아 적은 것</b>이다.
	 *
	 * <h2>왜 8 인가 — <b>5 + 3</b> 이다</h2>
	 *
	 * <p>한 번 밀린 몸이 <b>멈추는 데 드는 시간</b>과 <b>사람이 반응하는 데 드는 시간</b>을 더한
	 * 값이다. 10 일 때는 <b>5 + 5</b> 였고, 8 로 줄면서 <b>뒤쪽이 3 으로 깎였다.</b>
	 *
	 * <ul>
	 *   <li><b>앞의 5틱은 못 깎는다</b> — 바닥 마찰 0.546 이라 {@code 0.546⁵ ≈ 0.049}, 곧 한
	 *       번의 밀림이 가진 이동량의 <b>95%</b> 가 5틱 안에 끝난다. 그보다 자주 밀면 앞의 밀림이
	 *       아직 살아 있는 채로 덮어써 <b>속도가 한 번도 안 끊긴다</b> — 그것이 곧 조작 불능이고,
	 *       사람이 처음 말한 0.2초(4틱)가 정확히 그 자리였다</li>
	 *   <li><b>뒤의 3틱이 반응 여유다</b> — 전에는 5틱이었고 그 근거가
	 *       {@code TrialEnderPulse.JUMP_WINDOW_TICKS}(5틱 = 0.25초), 곧
	 *       {@code TrialWarning.TICKS_SIDESTEP} 의 설명이 「사람의 지각·판단·입력에만 0.25초가
	 *       든다」고 적어 둔 시간이었다. <b>3틱(0.15초)은 그보다 짧다.</b> 곧 밀림이 멎은 뒤에
	 *       한 번의 반응이 <b>온전히</b> 들어가지는 않는다 — 사람이 그 대가를 알고 고른 값이고,
	 *       이 카드가 어려워진 방식이 그것이다. <b>근거를 지어내지 않으려고 여기 그대로 적는다</b></li>
	 * </ul>
	 *
	 * <p><b>하한은 {@link #SHOVE_MIN_INTERVAL_TICKS}(6)이다.</b> 5틱은 마찰이 가라앉는 데 드는
	 * 시간 그 자체라 반응 여유가 0 이고, 그 아래로 내리면 밀림이 겹쳐 조작 불능이다. 시험이
	 * 여기를 지킨다.
	 *
	 * <p>그래도 <b>계속 밀린다</b>는 말은 지켜진다 — 통과에 120틱이 걸리므로 가만히 있으면 최대
	 * <b>열다섯 번</b> 밀리고(전에는 80틱에 여덟 번이었다), 실제로는 두세 번 만에 소용돌이 뒤로
	 * 빠진다.
	 */
	static final int SHOVE_INTERVAL_TICKS = 8;
	/**
	 * ⚠ 미는 간격의 <b>하한</b>(틱). {@link #SHOVE_INTERVAL_TICKS} 를 이 아래로 내리지 말 것.
	 *
	 * <p>바닥 마찰 0.546 으로 한 번의 밀림이 가라앉는 데 5틱이 든다. 6 은 그보다 딱 한 틱 위고,
	 * <b>5 이하면 앞의 밀림이 아직 살아 있는 채로 덮어써 속도가 한 번도 안 끊긴다</b> — 이 카드가
	 * 요구하는 「옆으로 빠져나가기」를 할 수 없는 카드가 된다.
	 *
	 * <p>사람이 처음 말한 0.2초(4틱)가 그 아래다. 그것을 알리고 0.4초로 물러선 경위는
	 * {@link #SHOVE_INTERVAL_TICKS} 에 적어 두었다. <b>상수로 둔 것은 시험이 물을 수 있게</b>
	 * 하려는 것이다 — 간격은 「조금 더 자주」가 언제든 다시 나올 값이라, 그때 멈춰 세울 자리가
	 * 코드 안에 있어야 한다.
	 */
	static final int SHOVE_MIN_INTERVAL_TICKS = 6;
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
	 * @param swept     소용돌이마다 <b>이미 피해를 준</b> 사람들. 목록 순서가 소용돌이 순서다.
	 *                  <b>이 집합들은 고쳐 쓴다</b> — 없으면 통과하는 120틱 동안 매 틱 맞는다.
	 *                  ⚠ <b>넉백은 여기에 걸리지 않는다</b>
	 * @param shoved    사람마다 <b>마지막으로 민 틱</b>. 넉백은 피해와 달리 닿아 있는 동안
	 *                  계속 들어가고, 그 간격을 이것으로 센다
	 *                  ({@link #SHOVE_INTERVAL_TICKS}). 소용돌이마다가 아니라
	 *                  <b>폭풍마다 하나</b>인 까닭은 클래스 설명에 있다
	 */
	private record Storm(long index, double baseAngle, List<Set<UUID>> swept,
			Map<UUID, Long> shoved) {
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
			run = new Storm(index, baseAngle(granted, index), sweptSets(count), new HashMap<>());
			storm = run;
			// 출발을 알린다. 가장자리는 반대편 사람에게서 80칸이라 한 점에서 울리면 아무것도
			// 안 들린다 — 사람마다 그 자리에서 울린다.
			announce(end, present);
		}

		double distance = distanceAt(step, perTick);
		for (int vortex = 0; vortex < count; vortex++) {
			Vec3 outward = outwardOf(run.baseAngle(), vortex, count);
			sweep(end, present, run.swept().get(vortex), run.shoved(), outward, distance, now, risk);
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
	 * 속도뿐이라(넉백이 여러 번 들어가도 한 번마다 그 틱에 끝난다) 상태이상도, 띄워 둔 몸도,
	 * 면제해 줄 낙하 거리도 없다. 판에 남기는 것도 없다 —
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
	 * 소용돌이 하나를 그리고, 지금 그 안에 서 있는 사람을 처리한다.
	 *
	 * <p>판정은 바닥 원 하나다({@link TrialRisks#insideMark} — 세로를 묻지 않는다. 표식이 바닥에
	 * 그려지는데 「원 위에 떠 있었으니 안 맞는다」가 되면 표식이 거짓말한 것이 된다).
	 *
	 * <p>⚠ <b>피해와 넉백을 갈라 센다.</b> 같은 원 안에 서 있는 같은 사람인데도 묻는 것이 다르다.
	 *
	 * <ul>
	 *   <li><b>피해</b> — {@code swept} 에 없을 때 한 번뿐이다. 지름 12칸을 초당 2칸으로
	 *       지나가므로 통과에 120틱이 걸리는데, 매 틱 물으면 {@code 2 × 120 = 240} 이 들어가
	 *       팀 체력 20 의 열두 배다</li>
	 *   <li><b>넉백</b> — {@code swept} 를 보지 않는다. {@link #SHOVE_INTERVAL_TICKS} 마다
	 *       다시 민다. 사람이 「닿아 있는 동안 계속 밀쳐지게」 하라고 정한 그 동작이다</li>
	 * </ul>
	 */
	private static void sweep(ServerLevel end, List<ServerPlayer> present, Set<UUID> swept,
			Map<UUID, Long> shoved, Vec3 outward, double distance, long now,
			TrialCatalog.Risk.EnderStorm risk) {
		Vec3 center = onGround(end, outward.scale(distance));
		draw(end, center, distance);
		for (ServerPlayer member : present) {
			UUID memberId = member.getUUID();
			if (!TrialRisks.insideMark(member.position(), center, VORTEX_RADIUS)) {
				continue;
			}
			if (swept.add(memberId)) {
				// 이 소용돌이가 이 사람에게 피해를 주는 것은 이번 한 번뿐이다.
				strike(end, member, risk);
			}
			if (!dueToShove(shoved.get(memberId), now)) {
				continue;
			}
			shoved.put(memberId, now);
			shove(end, member, outward, risk.knockback());
		}
	}

	/**
	 * 지금 이 사람을 밀 차례인가.
	 *
	 * <p>처음 닿은 틱에는 반드시 민다({@code last} 가 {@code null}). 그 뒤로는
	 * {@link #SHOVE_INTERVAL_TICKS} 마다다 — 매 틱 밀면 사람이 조작을 아예 못 한다.
	 *
	 * <p>시간이 되감긴 판({@code now < last})에서도 민다. 그때 안 밀면 폭풍이 끝날 때까지 그
	 * 사람만 조용히 넉백에서 빠진다 — 조용한 종류의 고장이다.
	 *
	 * @param last 마지막으로 민 틱. 이 폭풍에서 아직 한 번도 안 밀었으면 {@code null}
	 */
	static boolean dueToShove(@Nullable Long last, long now) {
		return last == null || now < last || now - last >= SHOVE_INTERVAL_TICKS;
	}

	/**
	 * 소용돌이에 닿았다. <b>피해만</b> 들어간다 — 넉백은 {@link #shove} 가 따로 센다.
	 *
	 * <p>피해원에 <b>가해 개체를 달지 않는다.</b> 실체가 붙은 피해원이면 {@code LivingEntity} 가
	 * 스스로 밀어내는데, 그 밀기는 <b>사람과 가해자의 상대 위치</b>로 방향을 잡고 거리도 우리가
	 * 정한 것이 아니다 — {@link #pushDistance} 의 천장을 통째로 지나쳐 가는 길이고, 바깥으로
	 * 미는 카드에서 그것은 곧 허공이다. 넉백은 {@link #push} 하나만 준다.
	 *
	 * <p>피해형은 폭발이다. 26.3 에서 {@code magic} 과 {@code dragonBreath} 는 {@code bypasses_armor}
	 * 태그에 들어 있어 방어구가 통째로 무시되고, 그러면 카드에 적힌 2 보다 실제로 더 아파진다.
	 * 폭발형은 「연쇄 포격」·「기둥 화염구」·「종말의 비」·「착지 충격」이 쓰는 이 판의 표준이고,
	 * 폭발 보호가 그대로 들어 대비한 사람이 손해 보지 않는다.
	 *
	 * <p>블록은 한 칸도 건드리지 않고 불도 붙이지 않는다.
	 */
	private static void strike(ServerLevel end, ServerPlayer member,
			TrialCatalog.Risk.EnderStorm risk) {
		Vec3 at = member.position();
		member.hurtServer(end, end.damageSources().explosion(null, null), risk.damage());
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
	 * 한 번 민다. 닿아 있는 동안 {@link #SHOVE_INTERVAL_TICKS} 마다 다시 불린다.
	 *
	 * <p><b>소리를 내지 않는다.</b> 첫 타격의 밀려나는 소리는 {@link #strike} 가 한 번 울렸다.
	 * 0.4초마다 같은 소리를 열네 번 더 울리면 <b>다른 카드의 경고음이 그 밑에 깔려</b> 안 들린다 —
	 * 이 판에 남은 신호가 소리와 바닥 표식 둘뿐이라 소리 하나가 비싸다. 대신 발밑에 엔더 입자를
	 * 조금 띄워 「또 밀렸다」를 눈으로 말한다.
	 *
	 * <p>점 몇 개는 예산 밖이 아니다. {@link #MAX_POINTS_PER_TICK} 은 <b>바닥 표식</b> 예산이고
	 * ({@link #markPoints} 가 세는 값), 이쪽은 밀린 사람에게만 나가므로 팀 인원만큼이 상한이다.
	 */
	private static void shove(ServerLevel end, ServerPlayer member, Vec3 outward, double knockback) {
		if (!push(member, outward, knockback)) {
			return;
		}
		Vec3 at = member.position();
		// 긴 형태다. 짧은 형태면 32칸 밖에 선 팀원에게는 아무도 안 밀린 것으로 보인다.
		end.sendParticles(ParticleTypes.PORTAL, true, false, at.x, at.y + 0.1, at.z, 6,
				0.3, 0.05, 0.3, 0.0);
	}

	/**
	 * ⚠ <b>바깥으로 민다 — 목적지는 {@link #pushDistance} 가 섬 안으로 자른다.</b>
	 *
	 * <p>쓰는 방향은 {@code outward} 그대로, 곧 <b>소용돌이가 나아가는 방향의 반대</b> 하나뿐이다.
	 * <b>전에는 이 자리에서 {@code outward.scale(-1)} 로 뒤집어 안쪽으로 밀었다</b> — 그래야
	 * 섬 밖으로 나가지 않기 때문이었다. 사람이 플레이해 보고 「한번 밀쳐지면 끝」이라 해서 부호를
	 * 뒤집었고, 그 대신 안전은 전부 {@link #pushDistance} 의 천장으로 옮겼다. 까닭은 클래스
	 * 설명에 있다.
	 *
	 * <p><b>사람의 좌표가 방향 계산에 한 번도 들어가지 않는다.</b> 「사람과 소용돌이의 상대
	 * 위치」로 잡으면 같은 소용돌이가 사람마다 다른 쪽으로 밀어 예측이 안 되고, 소용돌이 안쪽에
	 * 선 사람은 중앙을 가로질러 반대편으로 날아간다.
	 *
	 * <p>속도를 <b>더하지 않고 덮어쓴다</b>({@code setDeltaMovement}). 더하면 이미 들고 있던
	 * 수평 속도가 얹혀 천장이 계산한 목적지를 넘는다.
	 *
	 * <p>세로 속도는 읽어서 그대로 돌려놓는다. 띄우면 낙하 피해가 붙고, 공중에서는 바닥 마찰이
	 * 안 먹어 적힌 거리를 끝까지 날아간다 — 그때 천장을 지키는 것이
	 * {@link #pushDistance} 뿐이므로 여기서 한 칸도 띄우지 않는다.
	 *
	 * <p>{@code syncVelocity} 를 켜지 않으면 서버 혼자 민 것이 되어 잠시 뒤 클라이언트가 보고한
	 * 제자리로 되돌아간다({@code TrialRisks.launch} 와 같은 이유).
	 *
	 * @return 실제로 밀었으면 {@code true}. 천장에 걸려 한 칸도 못 밀면 {@code false}
	 */
	private static boolean push(ServerPlayer member, Vec3 outward, double knockback) {
		Vec3 away = shoveDirection(outward);
		double distance = pushDistance(member.getX(), member.getZ(), away,
				PUSH_BLOCKS * Math.max(0.0, knockback));
		if (!(distance > 0.0)) {
			return false;
		}
		double speed = pushVelocity(distance);
		Vec3 motion = member.getDeltaMovement();
		member.setDeltaMovement(away.x * speed, motion.y, away.z * speed);
		member.syncVelocity = true;
		return true;
	}

	/**
	 * ⚠ 미는 방향. <b>소용돌이가 나아가는 방향의 반대</b>, 곧 바깥이다.
	 *
	 * <p>한 줄짜리를 메서드로 빼 둔 것은 <b>시험이 부호를 물을 수 있게</b> 하기 위해서다
	 * ({@link #markColor} 가 같은 이유로 있다). 부호 하나가 이 카드의 성격 전부이고, 뒤집히면
	 * 빌드도 로그도 조용한 채로 <b>사람이 고쳐 달라고 한 것이 도로 원래대로</b> 돌아간다.
	 *
	 * @param outward 중앙에서 소용돌이를 가리키는 단위 벡터. 소용돌이는 그 <b>반대</b>로 나아간다
	 */
	static Vec3 shoveDirection(Vec3 outward) {
		// 전에는 여기서 outward.scale(-1) 로 뒤집어 안쪽(= 소용돌이가 가는 쪽)으로 밀었다.
		// 사람이 플레이해 보고 「한번 밀쳐지면 끝」이라 해서 바깥으로 되돌렸다. 안전은 부호가
		// 아니라 pushDistance 의 천장이 지킨다.
		return outward;
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
		TrialWarning.playEach(end, present, SoundEvents.PORTAL_TRIGGER, 0.7F, 0.5F);
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
	 * 층이 소음이 된다. 이미 <b>피해를 준</b> 소용돌이는 빼고 본다({@code swept}) — 지나간 것이
	 * 계속 경고하면 다음 폭풍의 예고와 섞인다. 넉백이 아직 남아 있어도 그렇다. 경고가 말하는
	 * 것은 「아직 안 맞았다」이고, 이미 맞은 사람에게 그 말은 거짓이다.
	 *
	 * <p>소리는 <b>그 사람에게만</b> 간다({@link TrialWarning#soundFor}). 한 점에 놓으면 16칸 밖에는
	 * 안 들리고, 그렇다고 사람 자리마다 놓으면 반경 안의 전원에게 나가 <b>남의 경고까지 듣고 겹쳐
	 * 들린다</b> — 소용돌이가 닿는 순간이 사람마다 다른 카드에서 그것은 「내 차례인가」를 지운다.
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
			TrialWarning.soundFor(end, member, stage);
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
	 * 4.5칸 움직이므로 <b>지나온 자리에 꼬리가 한 마디 남고</b>, 그것이 「어디서 와서 어디로
	 * 가는가」를 말한다. 그보다 길게 남지 않아 아레나를 흐리지도 않는다.
	 *
	 * <p>반경이 4 에서 6 이 되면서 그 꼬리가 <b>지름의 절반에서 3분의 1</b>로 짧아 보이게 됐다.
	 * 원 자체가 커져 「어디까지가 닿는가」는 오히려 잘 읽히므로 그대로 둔다.
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
	 * ⚠⚠ <b>이 카드의 천장.</b> 밀려 나갈 수 있는 중앙에서의 가장 먼 거리(칸).
	 *
	 * <p>아레나 반경({@code TrialRisks.ARENA_RADIUS} = 40)에서 {@link #PUSH_LIMIT_MARGIN} 만큼
	 * 안쪽이다. <b>섬 경계에 딱 세우지 않는 까닭</b>은 그 상수에 적어 두었다 — 경계에 서 있으면
	 * 그 다음은 사람의 한 걸음이다.
	 *
	 * <p>{@code ARENA_RADIUS} 를 그대로 쓰는 것이 중요하다. 아레나가 좁아지거나 넓어지면 천장이
	 * 따라온다 — 여기 숫자를 따로 적으면 두 곳이 갈라지고, 갈라진 쪽이 <b>허공</b>이다.
	 */
	static double pushLimitRadius() {
		return Math.max(0.0, TrialRisks.ARENA_RADIUS - PUSH_LIMIT_MARGIN);
	}

	/**
	 * ⚠⚠ 실제로 밀 거리. <b>목적지가 {@link #pushLimitRadius} 안</b>인 만큼만 돌려준다.
	 *
	 * <h2>이 함수 하나가 이 카드의 안전장치 전부다</h2>
	 *
	 * <p>전에는 「밀린 뒤가 밀리기 전보다 중앙에 가깝다」로 잘랐다({@code s ≤ -(p·d)}). 그 증명은
	 * <b>안쪽으로 밀 때만</b> 성립한다 — 미는 방향이 바깥으로 뒤집힌 지금은 그 조건이 <b>언제나
	 * 거짓</b>이라 아무도 못 밀거나, 조건을 빼면 아무도 못 막는다. 그래서 새로 짰다.
	 *
	 * <h2>무엇을 약속하는가</h2>
	 *
	 * <p>중앙에서 잰 거리의 제곱은 밀린 거리 {@code s} 에 대해
	 * {@code q(s) = |p + s·d|² - R² = s² + 2s(p·d) + (|p|² - R²)} 이고, 이것은 위로 열린
	 * 이차식이라 <b>두 근 사이에서만 0 이하</b>다. 지금 서 있는 자리가 천장 안이면
	 * ({@code |p| < R}) {@code q(0) < 0} 이므로 0 이 두 근 사이에 있고, 큰 근
	 * {@code s⁺ = √((p·d)² - (|p|² - R²)) - (p·d)} 가 <b>천장 원과 만나는 바로 그 점</b>이다.
	 * {@code [0, s⁺]} 에서 자르면
	 *
	 * <ul>
	 *   <li><b>목적지</b>가 천장 안이다 — 어떤 세기를 넣어도 그렇다</li>
	 *   <li>밀리는 <b>도중</b>의 어느 점도 천장 안이다. {@code q} 가 그 구간에서 0 이하이므로
	 *       경사에 걸려 중간에 멈춰도 안전하다</li>
	 *   <li><b>몇 번을 연속으로 밀려도</b> 그대로다. 한 번 밀린 뒤의 자리가 다시 천장 안이라
	 *       다음 번의 {@code q(0)} 도 0 이하다 — <b>누적되지 않는다.</b> 「닿아 있는 동안 계속
	 *       민다」가 안전할 수 있는 근거가 이것이다</li>
	 *   <li>이미 천장 밖에 선 사람은 <b>한 칸도</b> 밀지 않는다({@code q(0) ≥ 0}). 다른 카드나
	 *       경사가 먼저 데려다 놓은 경우인데, 거기서 또 밀면 이 카드가 남의 사고를 완성시킨다</li>
	 * </ul>
	 *
	 * <p><b>세기와 무관하다.</b> {@link #PUSH_BLOCKS} 를 몇으로 올리든, 카드 배율을 3 이 아니라
	 * 30 으로 적든, 다른 카드의 넉백이 먼저 사람을 가장자리로 데려다 놓았든, 이 카드가 미는
	 * 목적지는 언제나 섬 안이다. 산수는 값을 고치는 사람이 안 볼 수 있지만 이 함수는 못 피한다.
	 *
	 * <p><b>이 함수를 지우거나 헐겁게 하지 말 것.</b> 방향이 바깥인 이상 이것 말고는 사람이 허공
	 * 으로 나가는 것을 막는 장치가 하나도 없다. 천장을 무르려면 방향을 안쪽으로 되돌려야 한다.
	 *
	 * @param x       사람의 x. 아레나 중앙이 {@code (0, 0)} 이다
	 * @param z       사람의 z
	 * @param outward 미는 방향. <b>단위 벡터여야 하고 높이는 보지 않는다</b>
	 * @param wanted  카드가 시킨 거리({@code PUSH_BLOCKS × knockback})
	 */
	static double pushDistance(double x, double z, Vec3 outward, double wanted) {
		if (!(wanted > 0.0)) {
			return 0.0;
		}
		double limit = pushLimitRadius();
		double outside = x * x + z * z - limit * limit;
		if (outside >= 0.0) {
			// 이미 천장 위이거나 밖이다. 어느 쪽으로 밀어도 더 나빠지기만 한다.
			return 0.0;
		}
		double along = x * outward.x + z * outward.z;
		// outside < 0 이라 판별식은 반드시 양수고, 큰 근은 반드시 0 보다 크다.
		double reach = Math.sqrt(along * along - outside) - along;
		return Math.min(wanted, Math.max(0.0, reach));
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
	 * <p>⚠ 밀리는 방향이 <b>바깥</b>으로 뒤집힌 뒤로 이 선택이 안전과 직결된다. 바닥 모델을 쓰면
	 * 점프한 사람이 적힌 거리의 <b>다섯 배</b>를 날아가 {@link #pushDistance} 가 잡아 둔 목적지를
	 * 지나쳐 버린다 — 천장은 「얼마나 밀지」를 자를 뿐 「실은 속도가 그보다 멀리 가지 않는다」는
	 * 이 모델이 지킨다. 어긋나는 방향이 언제나 <b>덜 미는 쪽</b>이어야 한다.
	 *
	 * <p>「착지 충격」과 같은 식이라 두 카드가 같은 세기로 같은 만큼 민다.
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
