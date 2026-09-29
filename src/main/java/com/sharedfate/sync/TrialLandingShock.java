package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
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
 * {@link TrialCatalog.Risk.LandingShock} 실행기 — 착지 충격.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p>드래곤이 <b>착지할 때마다</b> 중앙에서 고리가 {@code maxRadius} 까지 퍼진다
 * ({@code travelTicks} 에 걸쳐). 그런 고리가 <b>{@code ringCount} 개</b>,
 * {@code ringIntervalTicks} 간격으로 이어 나간다. 고리가 지나갈 때
 *
 * <ul>
 *   <li><b>바닥을 딛고 있으면</b> 피해({@code damage}) 와 바깥 넉백({@code knockback})</li>
 *   <li><b>점프해 있으면 회피</b>한다 — 이 카드가 요구하는 행동이 그것 하나다</li>
 * </ul>
 *
 * <p>고리가 여럿이라 <b>한 번 비켰다고 끝이 아니다.</b> 지금 값(고리 3개·2초 간격)에서는 착지
 * 한 번에 점프를 세 번 읽어야 한다.
 *
 * <h2>⚠ 다음에 볼 사람에게 — 사람이 「운으로 피해진다」고 했다</h2>
 *
 * <p>플레이해 보고 <b>「그냥 뛰어다녀도 알아서 피해지는 정도야」</b>라고 했다. 달리면서 점프를
 * 이어 하면 절반 넘게 공중이라, 고리를 <b>보지 않고도</b> 판정 창에 걸려 통과한다는 뜻이다.
 * 이 카드가 요구하는 행동이 「고리를 보고 그 순간 뛰기」인데 실제로는 「계속 뛰기」로 풀린다.
 *
 * <p><b>이번에는 고치지 않기로 했다.</b> 사람이 시킨 것은 연출뿐이고(아래 「연출을 어떻게
 * 고쳤는가」), 난이도는 그대로 두라고 했다 — 점프 창 5틱, 고리 속도, 판정 규칙 전부 손대지
 * 않았다. 여기 적어 두는 것은 <b>다음에 난이도를 볼 때 무엇이 문제였는지</b>를 남기려는 것이다.
 *
 * <p>고치게 된다면 창({@link TrialEnderPulse#JUMP_WINDOW_TICKS})을 줄이는 쪽이 아니라
 * <b>「뛰어 있는 동안」이 아니라 「앞머리가 지나는 그 틱 언저리에 떴는가」로 묻는 쪽</b>을 먼저
 * 볼 것. 창을 줄이면 「엔더 파동」의 타이밍까지 함께 바뀌고, 그 창은 통신 왕복을 봐 주려고
 * 있는 것이라 줄이면 억울하게 걸리는 사람이 먼저 생긴다.
 *
 * <h2>⚠ 「엔더 파동」과 <b>같은 고리</b>다 — 한쪽만 고치지 말 것</h2>
 *
 * <p>{@link TrialEnderPulse} 가 중앙에서 퍼지는 고리를 먼저 끝냈고, 동작이 이 카드와 똑같다 —
 * 중앙에서 퍼지고, 바닥을 딛고 있으면 걸리고, 점프하면 통과한다. 다른 것은 반경·개수·결과뿐이다
 * (엔더 파동 42칸 고리 하나·구속, 착지 충격 75칸 고리 셋·피해 4·넉백).
 *
 * <p><b>두 카드가 같은 동작을 다른 규칙으로 가르치면 사람은 「고리는 뛰면 피한다」를 하나로 배울
 * 수 없다.</b> 한 카드에서 통하던 타이밍이 다른 카드에서 안 통하면 그것은 배움이 아니라 운이다.
 * 그래서 판정에 쓰는 것을 <b>새로 만들지 않고 그쪽 것을 그대로 부른다</b> —
 * {@link TrialEnderPulse#JUMP_WINDOW_TICKS}, {@link TrialEnderPulse#radiusAt},
 * {@link TrialEnderPulse#judgeRadius}, {@link TrialEnderPulse#reachTick},
 * {@link TrialEnderPulse#lifetime}, {@link TrialEnderPulse#edgePoints},
 * {@link TrialEnderPulse#wakePoints} 다. 지형을 타는 것도 같은 자리에서 온다 —
 * {@link TrialEnderPulse.Ground}, {@link TrialEnderPulse#atRingHeight}.
 *
 * <p>값을 베껴 오지 않은 것이 핵심이다. 같은 숫자를 두 파일에 적어 두면 <b>한쪽만 고쳐지고</b>,
 * 그때 어긋나는 것은 숫자가 아니라 사람이 배운 타이밍이다. 두 규칙을 한 자리로 합치는 일은
 * 아직 안 됐다 — 그때까지는 이 의존이 그 자리를 대신한다.
 *
 * <h2>고리는 지형을 탄다 — 그리는 것도 판정도 함께</h2>
 *
 * <p>점마다 그 자리의 지표를 묻고 그 위에 찍는다. 이 고리의 중심은 아레나 바닥이 아니라
 * <b>포탈 꼭대기</b>라, 전에는 고리가 포탈 단을 벗어난 뒤로 섬 위를 떠서 지나갔다.
 *
 * <p>「바닥을 딛고 있는가」도 함께 따라간다({@link TrialEnderPulse#atRingHeight}). 그러지
 * 않으면 보이는 고리와 맞는 자리가 갈라진다. 까닭과 성능 장치는
 * {@link TrialEnderPulse.Ground} 에 한 번만 적어 두었다 — <b>한쪽만 고치지 말 것.</b>
 *
 * <h2>「점프해 있다」를 한 틱으로 묻지 않는다</h2>
 *
 * <p>고리가 어떤 사람의 자리를 지나가는 것은 <b>한 틱</b>이다. 그 한 틱에 {@code onGround()} 만
 * 물으면 0.05초 차이로 억울하게 걸린다 — 서버의 {@code onGround} 는 서버가 계산한 값이 아니라
 * <b>클라이언트가 이동 패킷에 실어 보낸 깃발</b>이라, 사람이 스페이스를 누른 시점보다 한 왕복
 * 늦게 도착한다.
 *
 * <p>그래서 고리에 <b>두께</b>를 준다. 앞머리가 내 자리를 지난 뒤
 * {@link TrialEnderPulse#JUMP_WINDOW_TICKS}(5틱 = 0.25초) 동안이 「고리가 내 위에 있는 시간」이고,
 * <b>그 사이 한 틱이라도 공중에 있었으면 통과</b>다. 판정은 그 창이 닫히는 틱, 곧 고리의
 * <b>뒷자락</b>이 나를 지나는 틱에 내린다. 0.25초는 {@link TrialWarning#TICKS_SIDESTEP} 의
 * 설명에 적힌 「사람의 지각·판단·입력」 시간이고, 이 카드도 요구하는 것이 점프 한 번뿐이라
 * 고를 자리도 갈 거리도 없이 <b>남는 것이 그 반응 시간뿐</b>이다.
 *
 * <p>창은 <b>뒤로만</b> 열린다. 늦게 뛴 것은 봐 주고 너무 일찍 뛴 것은 안 봐 주는데, 바닐라
 * 점프가 그 자체로 열두 틱쯤 공중에 떠 있어 <b>앞쪽 여유는 점프가 이미 들고 있기</b> 때문이다.
 *
 * <h2>⚠⚠ 넉백 천장 — 이제 이것 하나가 팀을 살린다</h2>
 *
 * <p>예전에는 반경 15 + 넉백 8 = <b>23칸</b>이라 섬(반경 40, 흑요석 기둥이 서 있는 원 42)
 * 안에 저절로 머물렀고, 그 산수가 이 카드를 「강한 넉백 금지」의 예외로 만든 근거였다.
 *
 * <p><b>지금 값은 그 조건을 깼다.</b> 75 + 16 = 91칸이라 산수로는 섬을 두 배 넘게 벗어난다.
 * 엔드 중앙 섬은 사방이 허공이고 체력이 팀 공유라 <b>한 사람의 낙사가 팀 전체를 끝내며 그것이
 * 곧 월드 삭제</b>다.
 *
 * <p>그래서 근거를 산수에서 <b>함수</b>로 옮겼다. {@link #outwardLimit} 이 미는 자리에 천장을
 * 걸고 {@link #pushDistance} 가 그 천장 밖으로는 한 칸도 내보내지 않는다 — <b>카드에 어떤 값을
 * 적어도</b> 목적지는 섬 안이다. 천장은 섬 경계가 아니라 {@link #EDGE_MARGIN} 만큼 안쪽이다.
 * 경계에 딱 세우면 그 자리에서 한 걸음이 곧 낙사이기 때문이다.
 *
 * <p>여기에 하나를 더 건다. {@link #groundedReach} 가 미는 길을 한 칸씩 짚어 <b>땅이 끊기기
 * 전</b>에서 멈춘다 — 중앙 섬은 둥글지 않아 반경 34 안에도 빈 곳이 있을 수 있고
 * ({@code TrialRisks.pickSpot} 이 같은 이유로 허공을 거른다), 사람이 파 놓은 구멍도 있다.
 *
 * <p><b>위 셋 중 하나라도 무르면 이 카드는 그날로 전멸 카드다.</b> 반경 75 자체는 위험하지
 * 않다 — 섬(40)을 넘는 구간은 허공 위라 거기엔 사람이 없다. 위험한 것은 넉백뿐이다.
 *
 * <h2>드래곤을 읽기만 한다 — 이 저장소가 한 번 크게 틀린 자리다</h2>
 *
 * <p>앞선 작업에서 드래곤을 <b>돌진 페이즈로 밀어넣어</b> 표적 카드를 만들었더니
 * <b>드래곤이 착지를 아예 하지 않게</b> 됐다. 바닐라는 드래곤이 원을 한 바퀴 다 돌아
 * <b>경로가 끝난 틱</b>에만 「착지할까」를 굴리는데, 주기적으로 끼어들면 경로가 매번 처음부터
 * 다시 깔려 그 틱이 영영 오지 않는다({@link TrialCatalog.Risk.DragonFocus} 의 설명). 하필
 * <b>착지가 발동 조건인 카드</b>가 이 카드다 — 여기서 같은 실수를 하면 카드가 스스로를 끈다.
 *
 * <p>그래서 이 파일은 {@code setPhase}·{@code getPhaseManager}·{@code setTarget} 을 한 번도
 * 부르지 않는다. <b>페이즈를 읽지도 않는다.</b> 읽기는 허락된 일이지만, 읽지 않으면 나중에
 * 이 규칙을 어길 실마리 자체가 남지 않는다 — 「이미 페이즈를 보고 있으니 한 줄만 더」가
 * 시작되는 자리가 없다.
 *
 * <h2>그럼 착지를 어떻게 아는가 — 좌표 둘로 안다</h2>
 *
 * <p>26.3 {@code DragonLandingPhase.doServerTick} 을 풀어 보면 착지의 정의가 좌표로 적혀 있다.
 *
 * <ul>
 *   <li>착지 목표는 {@code EnderDragonFight.getPodiumLocation(fightOrigin)} 를
 *       {@code MOTION_BLOCKING_NO_LEAVES} 하이트맵에 올린 뒤 {@code Vec3.atBottomCenterOf} 로
 *       옮긴 점 — 곧 <b>포탈 꼭대기</b>다({@link #podiumOf})</li>
 *   <li>그 점까지 {@code distanceToSqr < 1.0} 이 되는 틱에 {@code SITTING_SCANNING} 으로 넘어간다.
 *       그래서 <b>「포탈 꼭대기에서 1칸 안」이 바닐라 자신이 쓰는 착지의 정의</b>다
 *       ({@link #LANDED_DISTANCE})</li>
 *   <li>앉아 있는 동안 {@code getFlyTargetLocation()} 이 {@code null} 이라 {@code aiStep} 의
 *       이동 구간이 통째로 건너뛰어진다 — {@code move()} 가 아예 불리지 않으므로 드래곤의 좌표가
 *       <b>한 틱도 변하지 않는다.</b> 그것이 둘째 조건이다({@link #STILL_SPEED})</li>
 * </ul>
 *
 * <p>둘째 조건이 있어야 <b>돌진</b>과 갈린다. 포탈 위에 선 사람에게 드래곤이 달려들면 좌표만으로는
 * 착지와 구별되지 않는데, 그때 드래곤은 매 틱 움직이고 있다.
 *
 * <h2>한 번의 착지에 <b>고리 한 벌</b></h2>
 *
 * <p>앉아 있는 내내 「착지했다」가 참이므로 그것을 그대로 쓰면 초당 스무 번 터져
 * {@code 4 × 20} 으로 즉사한다. 그래서 <b>가장자리</b>만 잡는다({@link #touchdown}) — 직전 틱은
 * 앉아 있지 않았고 이번 틱은 앉아 있는 그 한 틱이다. 다시 날아올랐다가 앉으면 그때 다시 잡힌다.
 *
 * <p>가장자리 한 번이 여는 것은 <b>{@link Wave} 하나</b>고, 그 안에 고리가 {@code ringCount}
 * 개 들어 있다. 고리마다 나이가 다르므로({@link #stepOf}) <b>셋이 동시에 날고 있다</b> —
 * 지금 값(퍼짐 150틱, 수명 155틱, 간격 40틱)에서 벌 하나가 235틱을 살고 그 가운데 70틱은
 * 고리 셋이 함께 떠 있다. 그래서 <b>지나간 사람 명단도 고리마다 따로</b> 든다. 하나로 합치면
 * 첫 고리를 맞은 사람이 두 번째·세 번째 고리를 그냥 통과한다.
 *
 * <p>카드를 받은 직후에는 <b>기준을 잡기만 하고 터뜨리지 않는다</b>({@link #sitting} 이 처음에
 * {@code null} 이다). 그러지 않으면 이미 앉아 있는 드래곤을 때리던 중에 이 카드를 받은 판에서
 * <b>받자마자</b> 발밑이 터진다 — 그때 팀은 정확히 고리의 한가운데에 모여 있다.
 *
 * <h2>피해는 고리가 지나간 사람 <b>전원</b>에게 들어간다</h2>
 *
 * <p>「연쇄 포격」은 반대로 팀에 한 번만 넣는다. 거기서는 <b>넷이 한 원 안에 모여 있는 것이
 * 공유 체력 게임의 올바른 대응</b>이라, 모두 때리면 올바른 대응이 곧 전멸이 되기 때문이다.
 *
 * <p>이 카드에는 그 함정이 없다. 대응 수단이 <b>모이는 것이 아니라 뛰는 것</b>이라, 넷이 붙어
 * 있어도 넷 다 뛰면 0 이다. 반대로 팀에 한 번만 넣으면 <b>셋이 뛰든 넷이 뛰든 한 사람만 안 뛰면
 * 같은 4</b> 라 「나는 안 뛰어도 된다」가 성립하고, 회피 카드가 회피를 못 가르친다. 「엔더 파동」이
 * 구속을 걸린 사람마다 거는 것과 같은 판단이다.
 *
 * <p>그 대신 천장이 낮다. {@link #worstCaseTeamDamage} 가 넷 전원 실패의 값을 직접 센다.
 * <b>다이아 풀셋 + 보호 IV 기준</b>으로 재면 피해 4 는 한 대에 <b>0.56</b> 이라(폭발형이라
 * 하드 곱 1.5 가 먼저 붙고 방어·보호가 그 뒤를 깎는다), 넷이 고리 셋을 모두 맞아도
 * {@code 0.56 × 4 × 3 ≈ 6.7} 로 팀 공유 체력 20 의 3분의 1 이다.
 *
 * <p><b>피해 4 는 지금 이 판에서 가장 작은 값이고 그것이 맞다.</b> 큰 카드들(자리 폭격·낙뢰 35,
 * 기둥 화염구·연쇄 포격·종말의 비 23)은 무장 기준 <b>세 대에 전멸</b>하도록 잡혀 있는데, 이
 * 카드는 <b>넉백이 본체</b>라 피해로 승부하지 않는다. 사람을 끝내는 것은 4 가 아니라 <b>허공</b>
 * 이고, 그래서 이 파일이 지키는 것도 피해 산수가 아니라 {@link #outwardLimit} 의 천장이다.
 * <b>피해를 올리지 말 것</b> — 올리는 순간 이 카드는 「밀리는 카드」가 아니라 그냥 또 하나의
 * 「아픈 카드」가 된다.
 *
 * <h2>안 터지는 판이 있다 — 의도한 것이다</h2>
 *
 * <p>바닐라 드래곤은 <b>크리스탈이 다 깨져야</b> 포탈에 앉는다. 체력 50% 에서 「부활」이 뽑히면
 * 크리스탈이 되살아나 그 뒤로 착지가 없어지고, 이 카드는 <b>남은 전투 내내 한 번도 안 터진다.</b>
 *
 * <p><b>이것을 고치지 말 것.</b> 카드끼리 상호작용하는 것이 재밌다고 사람이 정했다. 「안 터지니까
 * 강제로 앉히자」는 이 파일이 가장 하면 안 되는 일이기도 하다 — 위의 「드래곤을 읽기만 한다」가
 * 바로 그 사고의 기록이다.
 *
 * <h2>연출을 어떻게 고쳤는가 — <b>점을 한 개도 더 쓰지 않고</b></h2>
 *
 * <p>사람이 플레이해 보고 <b>「착지충격 너무 이펙트가 잘안보여」</b>라고 했다. 까닭은 반경이다.
 * 반경 75 고리의 둘레가 471칸인데 한 틱 점 예산이 434점이라 점 사이가 1.1칸이고, 게다가 고리가
 * <b>초당 10칸</b>으로 지나가 한자리에 오래 머물지 않는다. 멀리서 보면 희미한 선 하나다.
 *
 * <p><b>점을 더 쓰는 길은 없었다.</b> 이 판의 예산이 400~440 인데 이미 434 라 늘릴 자리가
 * 없다. 그래서 <b>같은 점으로 더 크게 보이게</b> 하는 세 가지를 했다.
 *
 * <ul>
 *   <li><b>앞머리를 세로로 세웠다</b>({@link #EDGE_RISE_SPEED}). 바닥에 찍기만 하던
 *       {@code CRIT} 을 <b>위로 쏜다</b> — 점 수도 패킷 수도 그대로인데 고리가 바닥에 그은 선이
 *       아니라 <b>사람 키만 한 흰 벽</b>이 된다. 멀리서 볼 때 바닥 선은 시선과 거의 나란해
 *       한 줄로 뭉개지지만, 서 있는 것은 그렇지 않다</li>
 *   <li><b>몸통을 키웠다</b>({@link #WAKE_SCALE}). 먼지는 크기와 수명이 <b>한 값에 매여</b>
 *       있어서(26.3 {@code DustParticleBase}) 키우면 오래 남는다 — 「희미하다」와 「한자리에 안
 *       머문다」가 한꺼번에 조금씩 낫는다. 늘릴 수 있는 한계는 그 상수에 적어 두었다</li>
 *   <li><b>소리를 늘렸다.</b> 점 예산과 무관하고, 무엇보다 <b>고리가 아직 안 보이는 자리</b>에도
 *       닿는다 — 착지한 그 틱에 한 번({@link #open}), 뛰어서 넘긴 순간에 한 번
 *       ({@link #dodged}) 사람마다 제자리에서 울린다</li>
 * </ul>
 *
 * <p><b>값은 한 개도 안 건드렸다</b> — 반경 75 · 넉백 16 · 피해 4 · 고리 3개 · 간격 40틱 ·
 * 150틱 그대로다. 예산을 나누는 장치({@link #ringAllowance}·{@link #stride}·
 * {@link #touchesGround})도 그대로다.
 *
 * <h2>파티클과 소리는 멀리 보낸다</h2>
 *
 * <p>고리는 75칸까지 간다. 파티클 짧은 형태는 <b>32칸</b>에서 잘리므로({@link TrialWarning} 의
 * 「거리 제한을 끄고 보낸다」) 전부 긴 형태로 보낸다. 소리도 같은 이유로 <b>사람마다 그
 * 자리에서</b> 울린다 — 바닐라 소리 사거리는 볼륨 1 이하면 16칸이다.
 *
 * <h2>점 예산을 고리 수로 나눈다</h2>
 *
 * <p>한 틱 예산은 400~440 인데 고리가 셋이라 그대로 두면 1200 이 나갈 수 있다. 그래서
 * {@link #ringAllowance} 가 <b>고리마다 바라는 만큼에 비례해</b> 예산을 나눈다 — 몇 개가 겹쳐
 * 날든 합이 {@link #MAX_POINTS_PER_TICK} 을 넘지 않고, 작은 고리가 큰 고리의 몫을 빼앗지도
 * 않는다. 통째로 허공 위를 도는 고리는 {@link #touchesGround} 가 미리 빼 준다.
 */
public final class TrialLandingShock {

	// ------------------------------------------------------------------ 착지를 알아내는 값

	/**
	 * 포탈 꼭대기에서 이 거리 안이면 「앉은 자리」다.
	 *
	 * <p>지어낸 값이 아니라 <b>바닐라가 쓰는 값</b>이다. 26.3
	 * {@code DragonLandingPhase.doServerTick} 이 {@code targetLocation.distanceToSqr(드래곤) < 1.0}
	 * 인 틱에 앉기 페이즈로 넘어가고, 넘어간 뒤로는 {@code move()} 가 안 불려 좌표가 그대로
	 * 멈춘다. 즉 <b>앉아 있는 동안 이 조건은 반드시 참</b>이고, 그것이 이 값을 고르는 유일한
	 * 근거다. 판이 올라 저 상수가 바뀌면 여기도 함께 바꿀 것.
	 */
	static final double LANDED_DISTANCE = 1.0;
	/**
	 * 한 틱에 이만큼도 안 움직였으면 「멈춰 있다」로 본다.
	 *
	 * <p>앉아 있으면 {@code aiStep} 의 이동 구간을 통째로 건너뛰므로 실제 값은 <b>정확히 0</b>
	 * 이다. 그런데도 0 으로 비교하지 않는 것은, 누가 드래곤을 한 번 {@code setPos} 로 스치기만
	 * 해도 이 카드가 <b>영영 한 번도 안 터지는</b> 조용한 고장이 나기 때문이다.
	 *
	 * <p>반대쪽으로 헐거워도 안 된다 — 날고 있는 드래곤을 「멈췄다」로 읽으면 안 된다. 착지
	 * 페이즈의 비행 속도가 1.5 이고 가장 느린 구간에서도 그 언저리라, 0.01 은 그 150분의 1 이다.
	 */
	static final double STILL_SPEED = 0.01;

	// ------------------------------------------------------------------ 「점프해 있다」

	/**
	 * 앞머리가 지나간 뒤 「뛰었다」로 쳐 주는 시간(틱). 고리 두께이기도 하다.
	 *
	 * <p>⚠ <b>{@link TrialEnderPulse} 의 것을 그대로 쓴다.</b> 같은 숫자를 여기 다시 적으면
	 * 한쪽만 고쳐지고, 그때 어긋나는 것은 숫자가 아니라 <b>사람이 두 카드에서 배운 타이밍</b>이다.
	 * 근거(0.25초)는 그쪽에 적혀 있다 — 이 카드도 요구하는 것이 점프 한 번뿐이라 근거가 같다.
	 */
	static final int JUMP_WINDOW_TICKS = TrialEnderPulse.JUMP_WINDOW_TICKS;

	// ------------------------------------------------------------------ 미는 값

	/**
	 * 공중 수평 감쇠. 26.3 {@code LivingEntity} 의 공중 이동이 매 틱 수평 속도에 곱하는 값이다.
	 *
	 * <p>이 값이 <b>「미는 거리」를 속도로 바꾸는 유일한 근거</b>다. 처음 속도 {@code v} 로 밀린
	 * 몸이 공중에만 있다면 총 이동 거리는 {@code v / (1 - 0.91)} 로 수렴한다 — 뒤집으면
	 * {@link #pushVelocity} 다.
	 */
	static final double AIR_DRAG = 0.91;
	/** 이보다 가까이 중앙에 겹쳐 있으면 「바깥쪽」이라는 방향이 없다. 그때는 밀지 않는다. */
	private static final double PUSH_MIN_REACH = 1.0E-4;

	/**
	 * ⚠ 섬 경계에서 이만큼 <b>안쪽</b>까지만 민다.
	 *
	 * <p>{@code TrialRisks.ARENA_RADIUS}(40)에 딱 세우면 안 되는 까닭이 둘이다.
	 *
	 * <ul>
	 *   <li>거기 세워진 사람은 <b>한 걸음이 곧 낙사</b>다. 밀린 직후에는 화면이 돌아가 있고
	 *       방향도 모르는데, 그 상태에서 실수 한 번의 값이 팀 전멸이면 카드가 아니라 함정이다</li>
	 *   <li>40 은 「아무 곳이나 고를 때 쓰는 원」이지 <b>섬의 실제 가장자리가 아니다.</b> 중앙
	 *       섬은 둥글지 않아 그 원 안에도 허공이 있다({@code TrialRisks.pickSpot} 의 설명)</li>
	 * </ul>
	 *
	 * <p>6 인 근거는 <b>스프린트 점프 한 번</b>이다. 바닐라에서 4.3칸쯤 나가므로 그것보다 한
	 * 걸음 더 남긴다. 그 이상은 사람이 스스로 걸어 나간 것이고 이 카드가 책임질 거리가 아니다.
	 */
	static final double EDGE_MARGIN = 6.0;
	/**
	 * {@link #groundedReach} 가 미는 길을 짚는 간격(블록).
	 *
	 * <p>0.5 인 것은 <b>한 칸짜리 구멍을 반드시 밟기</b> 위해서다. 1.0 으로 짚으면 폭 1 인
	 * 구멍이 두 점 사이에 통째로 들어가 없는 것이 된다.
	 *
	 * <p>이 값이 작을수록 묻는 횟수가 는다. 지금 넉백(16)이면 사람당 32번이고 맞은 사람에게만
	 * 한 번 도므로 한 착지에 백여 번이다 — 청크 기억이 붙어 있어 대부분 하이트맵 한 번씩이다.
	 */
	private static final double GROUND_PROBE_STEP = 0.5;
	/**
	 * {@link #touchesGround} 가 고리를 찔러 보는 갈래 수.
	 *
	 * <p>「통째로 허공인가」만 가르면 되는 물음이라 여덟이면 넉넉하다. 늘려도 얻는 것은 사람이
	 * 허공에 세워 둔 발판을 조금 더 자주 찾는 것뿐인데, 그 값이 고리 셋에 매 틱 곱해진다.
	 */
	private static final int RING_PROBE_SPOKES = 8;

	// ------------------------------------------------------------------ 표식

	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다({@code TrialWarning} 과 같은 값). */
	private static final double GROUND_OFFSET = 0.15;
	/**
	 * ⚠ 앞머리 {@code CRIT} 을 위로 쏘는 속도. <b>점을 안 늘리고 고리를 세우는 장치다.</b>
	 *
	 * <h2>전에는 바닥에만 찍었다</h2>
	 *
	 * <p>점을 전부 지표 바로 위({@link #GROUND_OFFSET})에 찍었다. 사람이 <b>「너무 이펙트가
	 * 잘안보여」</b>라고 한 까닭의 절반이 그것이다 — 서서 보는 눈높이에서 바닥 선은 시선과 거의
	 * 나란해, 75칸 밖에서는 1.1칸 간격의 점들이 한 줄로 뭉개진다.
	 *
	 * <p>위로 쏘면 <b>점 수도 패킷 수도 그대로인데</b> 같은 점들이 세로로 퍼져 고리가 벽이 된다.
	 * 예산을 한 점도 안 쓰고 얻는 것이라 먼저 골랐다.
	 *
	 * <h2>왜 2.0 인가 — 올라가는 높이가 사람 키다</h2>
	 *
	 * <p>26.3 클래스 파일을 직접 뜯어 확인한 값으로 계산했다({@link #riseHeight} 가 그 계산이다).
	 * {@code CritParticle} 은 받은 속도를 <b>0.4배</b> 해서 싣고(무작위 밑바닥은 0.02 남짓이라
	 * 묻힌다), 마찰 0.7 · 중력 0.5 로 매 틱 줄어든다. 2.0 이면 처음 0.8 칸/틱으로 올라
	 * <b>수명이 가장 짧은 점(4틱)도 1.88칸</b>, 긴 점(10틱)은 2.1칸까지 간다 — 사람 키(1.8)만
	 * 하다.
	 *
	 * <p><b>더 높이지 않는 까닭</b>은 시야다. 아레나에는 카드가 겹쳐 뜨고 그 대부분이 바닥
	 * 표식이라, 세로로 선 것이 높으면 <b>다른 카드의 고리를 가린다</b> — 「엔더폭풍」이 기둥을
	 * 5칸에서 멈춘 것과 같은 판단이다. 고리가 셋이라 이 카드만으로도 벽이 셋이다.
	 *
	 * <p>⚠ <b>수명은 이 값과 무관하다.</b> {@code CritParticle} 의 수명 식에 속도가 들어가지
	 * 않으므로 4~10틱 그대로고, 따라서 {@link #MAX_STRIDE} 의 근거도 그대로다.
	 */
	static final double EDGE_RISE_SPEED = 2.0;
	/** {@code CritParticle} 이 받은 속도에 곱하는 값. 26.3 클래스 파일에서 확인했다. */
	private static final double CRIT_SPEED_FACTOR = 0.4;
	/** {@code CritParticle} 의 마찰. {@code Particle.tick} 이 매 틱 속도에 곱한다. */
	private static final double CRIT_FRICTION = 0.7;
	/** {@code Particle.tick} 이 매 틱 세로 속도에서 먼저 빼는 값. {@code 0.04 × 중력 0.5} 다. */
	private static final double CRIT_GRAVITY_PULL = 0.02;
	/**
	 * {@code CritParticle} 수명의 <b>하한</b>(틱).
	 *
	 * <p>{@code max(1, 6.0 / (굴림×0.8 + 0.6))} 이 4~10틱을 잡는다. {@link #MAX_STRIDE} 가 기대는
	 * 것과 <b>같은 값</b>이다 — 한쪽만 고치지 말 것.
	 */
	private static final int CRIT_MIN_LIFETIME = 4;
	/**
	 * 몸통 먼지의 크기. 1.0 이 바닐라 레드스톤 가루다.
	 *
	 * <h2>전에는 1.0 이었다({@code TrialWarning.dust} 의 기본)</h2>
	 *
	 * <p>사람이 「잘 안 보여」라고 한 까닭의 나머지 절반이 <b>한자리에 안 머무는 것</b>이다.
	 * 고리가 초당 10칸으로 지나가므로 어느 한 자리가 파랗게 있는 시간은 먼지 수명뿐이다.
	 *
	 * <p>26.3 {@code DustParticleBase} 는 이 값을 <b>크기와 수명 둘 다에</b> 곱한다
	 * ({@code quadSize × 0.75 × 크기}, {@code 수명 = max(1, (int)(8.0 / (굴림×0.8 + 0.2)) × 크기)}).
	 * 그래서 한 값을 올리면 <b>더 크게 보이고 더 오래 남는다</b> — 두 불만이 함께 조금씩 낫는다.
	 * 1.25 에서 수명이 8~40틱에서 <b>10~50틱</b>이 되어 지나간 자국이 5~25칸으로 늘어난다.
	 *
	 * <h2>⚠ 왜 더 못 키우는가 — 쌓이는 수 때문이다</h2>
	 *
	 * <p>몸통은 <b>남아서 쌓인다.</b> 화면에 살아 있는 수가 {@code 한 틱 몸통 점수 × 수명}인데,
	 * 이 카드의 몸통 몫은 한 틱에 최대 176점({@code 440 - edgeShare(440)})이라 지금이
	 * {@code 176 × 40 = 7,040} 이고 1.25 에서 <b>8,800</b> 이다.
	 * {@link TrialEnderPulse#WAKE_MAX_POINTS} 가 「{@code 240 × 40} 이면 만 점을 넘긴다」며
	 * 긋고 간 선(9,600)보다 아래다. <b>1.4 로 올리면 {@code 176 × 56 = 9,856} 이라 그 선을
	 * 넘는다</b> — 더 키우려면 몸통 몫부터 줄여야 하고, 그 몫은 「엔더 파동」과 같은 비율이라
	 * 그쪽부터 봐야 한다.
	 */
	static final float WAKE_SCALE = 1.25F;
	/**
	 * 한 틱에 이 카드가 쓸 수 있는 점 수의 상한.
	 *
	 * <p>이 판의 예산은 400~440 이다 — 「낙뢰」가 반경 3 짜리 고리 열 개로 400,
	 * 「연쇄 포격」이 반경 3.5 짜리 열 개로 440 을 쓴다. 고리가 여럿인 이 카드는 그 <b>합</b>이
	 * 여기를 넘지 않게 {@link #ringAllowance}(고리끼리)·{@link #edgeShare}(앞머리와 몸통)가
	 * 나눠 쓴다.
	 *
	 * <p>지금 값에서 가장 바쁜 틱이 <b>434점</b>이다({@code TrialLandingShockTest} 가 벌이 사는
	 * 235틱을 통째로 훑어 그 수를 못박아 둔다). 곧 <b>늘릴 자리가 없다</b> — 「이펙트가 잘 안
	 * 보인다」의 답을 점에서 찾으려는 사람은 여기서 멈춰야 하고, 실제로 찾은 답은
	 * {@link #EDGE_RISE_SPEED}·{@link #WAKE_SCALE}·소리였다.
	 */
	static final int MAX_POINTS_PER_TICK = 440;
	/**
	 * ⚠ 고리 한 바퀴를 나눠 그릴 수 있는 <b>가장 긴 틱 수</b>({@link #stride}).
	 *
	 * <p>「종말의 비」는 먼지 수명 하한 8틱을 보고 6 으로 묶었다. 이 카드는 더 작다 —
	 * <b>앞머리에 쓰는 {@code CRIT} 의 수명이 최소 4틱</b>이기 때문이다(26.3
	 * {@code CritParticle} 이 {@code max(1, 6.0 / (굴림×0.8 + 0.6))} 으로 4~10틱을 잡는다).
	 * 4틱에 나눠 그리면 마지막 몫을 찍는 그 틱에 첫 몫이 죽어 <b>고리가 영영 안 닫힌다.</b>
	 *
	 * <p>3 은 그 하한 바로 아래다. 고리가 틱당 0.5칸으로 나아가므로 세 몫이 <b>1.5칸 안에</b>
	 * 흩어지는데, {@code CRIT} 자국이 어차피 2~5칸 남으므로 눈에는 그 두께 안에 묻힌다.
	 */
	static final int MAX_STRIDE = 3;

	// ------------------------------------------------------------------ 상태

	/**
	 * 착지 한 번이 여는 <b>고리 한 벌</b>.
	 *
	 * <p>중심을 <b>좌표로</b> 든다. 드래곤을 들고 있으면 매 틱 그 자리를 다시 읽게 되고, 드래곤이
	 * 날아오른 순간 고리가 따라 움직여 예고가 거짓말을 한다.
	 *
	 * <p>고리마다 틱을 따로 세지 않는다. <b>출발 틱 하나</b>와 몇 번째 고리인가로
	 * {@link #stepOf} 가 나이를 구한다 — 고리마다 제 시계를 들면 하나가 어긋나기 시작해도
	 * 나머지는 멀쩡해서 알아채지 못한다.
	 *
	 * @param center    고리들이 출발한 자리. 드래곤이 앉은 포탈 꼭대기다
	 * @param startedAt 착지를 잡은 틱. <b>첫 고리</b>의 출발 틱이다
	 * @param crossed   고리마다 <b>뒷자락이 이미 지나간</b> 사람들. 목록 순서가 고리 순서다.
	 *                  <b>이 집합들은 고쳐 쓴다.</b> 없으면 두 가지가 곧바로 깨진다 — 우리가
	 *                  민 사람이 다음 틱에 뒷자락 앞으로 나가 또 맞고, 뛰어서 피한 사람이
	 *                  착지한 다음 틱에 다시 판정당해 <b>점프가 회피가 아니게</b> 된다.
	 *                  고리마다 따로인 것은 <b>고리 셋이 각자 한 번씩</b> 지나가야 하기
	 *                  때문이다. 하나로 합치면 두 번째·세 번째 고리가 아무도 안 때린다
	 */
	private record Wave(Vec3 center, long startedAt, List<Set<UUID>> crossed) {
	}

	/** 아레나도 팀도 하나뿐이라 칸 하나로 둔다. {@link #clearState()} 가 반드시 비운다. */
	private static @Nullable Wave wave;
	/**
	 * 사람마다 <b>마지막으로 공중에 있던 틱.</b>
	 *
	 * <p>고리가 없는 동안에도 계속 적는다. 판정 틱에만 보면 그 한 틱의 {@code onGround()} 를
	 * 묻는 것과 같아져 창이 아무 일도 하지 않는다.
	 */
	private static final Map<UUID, Long> LAST_AIRBORNE = new HashMap<>();
	/** 직전 틱의 드래곤 자리. 「한 틱도 안 움직였는가」는 이것 없이 물을 수 없다. */
	private static @Nullable Vec3 lastDragonAt;
	/**
	 * 직전 틱에 앉아 있었는가. {@code null} 이면 <b>아직 한 번도 판단하지 않았다</b>는 뜻이다.
	 *
	 * <p>이 세 번째 값이 「받자마자 터진다」를 막는다. {@code false} 로 시작하면 이미 앉아 있는
	 * 드래곤 앞에서 카드를 받은 판이 곧바로 가장자리로 읽힌다.
	 */
	private static @Nullable Boolean sitting;
	/**
	 * 지금 들고 있는 것이 <b>어느 전투의 기록인가</b>. 카드를 받은 틱으로 가른다.
	 *
	 * <p>전투가 끝날 때 누가 비워 주기를 기다리지 않는다. 배선을 한 줄 빠뜨렸다고 지난 판의
	 * 「앉아 있었다」가 남으면 <b>다음 판의 첫 고리가 통째로 건너뛰어진다</b> — 조용한 종류의
	 * 사고다. {@code DragonFireBarrage.beginFight} 와 같은 장치다.
	 */
	private static long rememberedGrant = Long.MIN_VALUE;

	private TrialLandingShock() {
	}

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자도 받는 것은 {@link TrialRisks} 의
	 * 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <p>이 카드는 <b>주기가 없다.</b> 발동을 정하는 것은 {@code granted} 로부터의 시간이 아니라
	 * 드래곤의 착지다. {@code granted} 는 전투를 가르는 열쇠로만 쓴다.
	 *
	 * <p>시각은 넘겨받은 {@code now} 만 쓴다. {@code level.getGameTime()} 은 시련 화면이 떠
	 * 판이 얼어붙은 동안 흐르지 않아, 섞어 쓰면 고리가 그 자리에서 멈추거나 건너뛴다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.LandingShock risk) {
		if (end == null || risk == null) {
			return;
		}
		beginFight(granted);
		if (risk.travelTicks() <= 0 || !(risk.maxRadius() > 0.0)) {
			// 퍼지지 않는 고리는 예고 없이 동시에 맞는다는 뜻이다. 그런 카드는 돌리지 않는다.
			return;
		}

		List<ServerPlayer> present = present(end, members);
		// 고리가 없는 동안에도 적는다. 판정 창이 과거를 보므로 기록이 끊기면 창이 비어 버린다.
		recordAirborne(present, now);
		watchLanding(end, dragon, present, now, risk);
		spread(end, present, now, risk);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>상태를 들게 되면 <b>반드시 여기서 비울 것</b> — 위험은 값(레코드)이라 정적 맵을 쓰게 되고,
	 * 빠뜨리면 지난 판의 착지 기록이 다음 판의 첫 고리를 건너뛰게 한다. 조용한 종류의 사고다.
	 * 발 기록도 마찬가지다 — 지난 판에 뛰어 있던 것이 새 판의 첫 고리를 그냥 통과시킨다.
	 *
	 * <p>드래곤에 되돌릴 것은 없다. 이 실행기는 드래곤을 <b>읽기만</b> 하고 판에 남기는 것도 없다
	 * — 파티클과 소리는 그 틱에 끝나고 블록은 한 칸도 건드리지 않는다.
	 */
	public static void clearState() {
		// 지나간 사람 명단은 고리 한 벌 안에 들어 있으므로 벌을 버리면 함께 버려진다.
		wave = null;
		LAST_AIRBORNE.clear();
		lastDragonAt = null;
		sitting = null;
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

	// ------------------------------------------------------------------ 착지 잡기

	/**
	 * 드래곤이 방금 내려앉았는지 본다. <b>읽기만 한다.</b>
	 *
	 * <p>여기서 페이즈를 묻지 않는 까닭은 클래스 설명의 「드래곤을 읽기만 한다」에 있다. 보는 것은
	 * <b>좌표 둘</b>뿐이다 — 포탈 꼭대기에서 얼마나 떨어져 있는가, 그리고 직전 틱에서 얼마나
	 * 움직였는가.
	 *
	 * <p>드래곤이 없거나 죽었으면 기록을 놓는다. 다시 나타나면 {@link #sitting} 이 {@code null}
	 * 부터 다시 시작하므로, 죽는 연출 중에 좌표가 멈춘 것이 착지로 읽히지 않는다.
	 */
	private static void watchLanding(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> present, long now, TrialCatalog.Risk.LandingShock risk) {
		Vec3 podium = podiumOf(end, dragon);
		if (dragon == null || podium == null) {
			lastDragonAt = null;
			sitting = null;
			return;
		}
		Vec3 at = dragon.position();
		Vec3 before = lastDragonAt;
		lastDragonAt = at;
		if (before == null) {
			// 「움직였는가」를 물으려면 직전 좌표가 있어야 한다. 이 틱은 기준점만 잡는다.
			return;
		}
		boolean down = landed(at, before, podium);
		Boolean was = sitting;
		sitting = down;
		if (!touchdown(was, down)) {
			return;
		}
		open(end, present, podium, now, ringCount(risk.ringCount()));
	}

	/**
	 * 이번 틱이 <b>방금 내려앉은</b> 가장자리인가.
	 *
	 * <p>이 함수 하나가 「한 번의 착지에 고리 하나」를 지킨다. 「앉아 있다」를 그대로 쓰면 앉아
	 * 있는 내내 참이라 초당 스무 번 터지고, 피해 4 × 20 이면 그 자리에서 전멸이다.
	 *
	 * @param was 직전 틱의 판단. {@code null} 이면 아직 한 번도 판단하지 않았다는 뜻이고,
	 *            그때는 <b>터뜨리지 않는다</b> — 이미 앉아 있는 드래곤 앞에서 카드를 받은 판이
	 *            받자마자 터지는 길이 그것이다
	 * @param now 이번 틱의 판단
	 */
	static boolean touchdown(@Nullable Boolean was, boolean now) {
		return now && was != null && !was;
	}

	/**
	 * 지금 앉아 있는가.
	 *
	 * @param at     이번 틱의 드래곤 자리
	 * @param before 직전 틱의 드래곤 자리
	 * @param podium 포탈 꼭대기 — 바닐라가 착지 목표로 삼는 그 점
	 */
	static boolean landed(Vec3 at, Vec3 before, Vec3 podium) {
		return at.distanceToSqr(podium) < LANDED_DISTANCE * LANDED_DISTANCE
				&& at.distanceToSqr(before) <= STILL_SPEED * STILL_SPEED;
	}

	/**
	 * 드래곤이 앉는 자리. 곧 고리의 중심이다.
	 *
	 * <p>26.3 {@code DragonLandingPhase} 가 착지 목표를 만드는 식을 그대로 쓴다. 중앙을
	 * {@code (0, ?, 0)} 으로 박지 않는 것은 {@code fightOrigin} 이 원점이 아닌 판이 있기
	 * 때문이고, 좌표 하나를 읽는 것뿐이라 드래곤의 상태는 아무것도 건드리지 않는다.
	 */
	private static @Nullable Vec3 podiumOf(ServerLevel end, @Nullable EnderDragon dragon) {
		if (dragon == null || !dragon.isAlive() || dragon.isDeadOrDying()) {
			return null;
		}
		BlockPos podium = EnderDragonFight.getPodiumLocation(dragon.getFightOrigin());
		return Vec3.atBottomCenterOf(
				end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, podium));
	}

	/**
	 * 고리 한 벌을 놓는다.
	 *
	 * <p>앞 벌이 아직 퍼지고 있으면 <b>놓지 않는다.</b> 바닐라에는 고리 수명 안에 앉았다
	 * 일어났다 다시 앉는 길이 없어 실제로는 오지 않는 갈래지만, 그때 새 벌로 갈아 끼우면 이미
	 * 보여 준 고리가 공중에서 사라진다 — <b>그려 놓은 표식을 무르지 않는 것</b>이 이 전투의
	 * 약속이다. 고리가 셋이 되어 한 벌이 사는 시간이 길어졌으므로 이 문이 전보다 자주 닫힌다.
	 *
	 * <p>명단을 여기서 <b>고리 수만큼</b> 만든다. 나중에 세면 카드 값이 바뀌는 순간 이미 날고
	 * 있던 벌의 명단 수와 어긋나 자리를 벗어난다.
	 *
	 * <p><b>착지한 그 틱에 소리를 한 번 울린다.</b> 전에는 가운데 파티클 하나뿐이었는데, 사람이
	 * 「잘 안 보여」라고 한 카드에서 <b>화면을 안 보고 있으면 시작을 놓친다</b>는 뜻이기도 했다.
	 * 소리는 점 예산과 무관하고 고리가 아직 중앙에 있는 동안에도 닿는다. 층 경고
	 * ({@link #warn})는 <b>고리가 나에게 닿기까지 남은 시간</b>을 말하는 것이라 이 「시작했다」를
	 * 대신하지 못한다 — 중앙 가까이 선 사람은 첫 층이 곧 마지막 층이다.
	 */
	private static void open(ServerLevel end, List<ServerPlayer> present, Vec3 podium, long now,
			int rings) {
		if (wave != null) {
			return;
		}
		List<Set<UUID>> crossed = new ArrayList<>(rings);
		for (int index = 0; index < rings; index++) {
			crossed.add(new HashSet<>());
		}
		wave = new Wave(podium, now, crossed);
		// 착지 자체가 이 카드의 유일한 예고다. 가운데에서 한 번 크게 터뜨려 「지금부터다」를
		// 말한다.
		end.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, true, false,
				podium.x, podium.y + GROUND_OFFSET, podium.z, 1, 0.0, 0.0, 0.0, 0.0);
		// 사람마다 그 자리에서, 그 사람에게만 울린다 — 한 점에서 울리면 16칸 밖은 아무것도 못
		// 듣고, 사람 자리마다 level.playSound 를 부르면 반경 안의 전원에게 나가 모여 있는 넷이
		// 각자 네 겹으로 듣는다(TrialWarning.playEach 의 설명).
		// 음을 바닥까지 낮춘 폭발음이라 「무언가 거대한 것이 내려앉았다」로 읽히고, 이 판의
		// 다른 신호(WIND_CHARGE_BURST = 밀렸다, TrialWarning 의 세 층)와 섞이지 않는다.
		TrialWarning.playEach(end, present, SoundEvents.GENERIC_EXPLODE, 1.0F, 0.5F);
	}

	// ------------------------------------------------------------------ 고리가 퍼진다

	/**
	 * 살아 있는 고리마다 한 칸 넓혀 그리고, 경고를 올리고, 뒷자락이 지나간 사람을 가른다.
	 *
	 * <p>순서가 「엔더 파동」과 같다 — 그리고, 알리고, 판정한다. 고리 하나의 수명도 그쪽
	 * {@link TrialEnderPulse#lifetime} 에서 가져온다. 주기가 없는 카드라 간격 자리에 0 을 넣는데,
	 * 그러면 {@code travelTicks + 창} 이 그대로 나온다. <b>앞머리가 끝까지 간 뒤에도 창만큼
	 * 남는 것</b>이 중요하다 — 깎으면 가장 바깥에 선 사람만 창을 못 받아 그 사람에게만 옛날의
	 * 한 틱짜리 판정이 적용된다.
	 *
	 * <p>고리를 도는 것이 <b>두 바퀴</b>다. 첫 바퀴는 그릴 고리마다 「점이 몇 개 필요한가」만
	 * 세고, 둘째 바퀴가 그 합으로 나눈 몫을 들고 실제로 그린다. 나눌 합을 모른 채 첫 고리부터
	 * 그리면 그 고리가 예산을 다 쓴다. 세는 바퀴는 산수와 {@link #touchesGround} 의 여덟 번
	 * 뿐이라 값이 없다.
	 *
	 * <p>지표를 묻는 기억({@link TrialEnderPulse.Ground})은 <b>한 틱에 하나</b>를 모든 고리가
	 * 함께 쓴다. 고리마다 만들면 겹쳐 나는 동안 같은 청크를 몇 번씩 다시 찾는다.
	 */
	private static void spread(ServerLevel end, List<ServerPlayer> present, long now,
			TrialCatalog.Risk.LandingShock risk) {
		Wave run = wave;
		if (run == null) {
			return;
		}
		int rings = run.crossed().size();
		int interval = ringInterval(risk.ringIntervalTicks());
		int life = TrialEnderPulse.lifetime(0, risk.travelTicks());
		long age = now - run.startedAt();
		if (age < 0L || age > lastTick(rings, interval, life)) {
			// 마지막 고리의 뒷자락까지 다 지나갔거나 시간이 되감겼다. 남는 것은 없다 — 지나간
			// 자리는 그 틱부터 안전하다. 명단도 벌과 함께 버려진다.
			wave = null;
			return;
		}

		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		double[] radii = new double[rings];
		int[] want = new int[rings];
		int wanted = 0;
		for (int index = 0; index < rings; index++) {
			int step = stepOf(age, index, interval);
			if (step < 1 || step > risk.travelTicks()) {
				// 아직 안 태어났거나(반경 0 이라 그릴 것이 없다) 앞머리가 이미 멈췄다.
				continue;
			}
			double radius = TrialEnderPulse.radiusAt(step, risk.travelTicks(), risk.maxRadius());
			if (!touchesGround(end, ground, run.center(), radius)) {
				// 통째로 허공 위를 도는 고리다. 어차피 한 점도 안 나가므로 예산에서도 뺀다 —
				// 안 빼면 반경 70 짜리 유령 고리가 섬 위를 도는 고리의 몫을 반으로 줄인다.
				continue;
			}
			radii[index] = radius;
			want[index] = ringWant(radius);
			wanted += want[index];
		}

		for (int index = 0; index < rings; index++) {
			int step = stepOf(age, index, interval);
			if (step < 0 || step > life) {
				// 아직 안 태어났거나 이미 다 지나갔다. 판정도 경고도 없다.
				continue;
			}
			// 위상을 고리마다 어긋나게 준다. 같은 위상이면 셋이 늘 같은 각만 찍어, 나눠 그리는
			// 동안 비어 있는 각이 세 고리에서 나란히 비어 「부챗살」로 읽힌다.
			draw(end, ground, run.center(), radii[index], ringAllowance(want[index], wanted),
					now + index);
			warn(end, present, run.center(), step, risk);
			judge(end, ground, present, run.center(), run.crossed().get(index), step, now, risk);
		}
	}

	/**
	 * 이 고리가 <b>땅에 닿는 데가 한 군데라도</b> 있는가.
	 *
	 * <p>반경 75 짜리 고리는 대부분의 시간을 허공 위에서 돈다(섬은 반경 40 언저리다). 그런
	 * 고리는 한 점도 안 찍히는데, 예산을 나눌 때까지 몰라 주면 <b>실제로 그려지는 고리의 몫을
	 * 빼앗는다.</b>
	 *
	 * <p>여덟 갈래만 찔러 본다. 통째로 허공인지 아닌지를 가르는 데는 그만하면 되고, 점마다 세는
	 * 것은 이미 {@link #ring} 이 한다. 사람이 허공 위에 세워 둔 <b>한 칸짜리 발판</b>은 여기서
	 * 놓칠 수 있다 — 그 위에는 고리가 안 그려진다. 거기 설 수 있는 사람이 거의 없고, 놓쳐서
	 * 잃는 것이 점 몇 개인 반면 안 걸러서 잃는 것은 <b>섬 위 고리의 선명함</b>이라 이쪽을 골랐다.
	 */
	private static boolean touchesGround(ServerLevel end, TrialEnderPulse.Ground ground,
			Vec3 center, double radius) {
		for (int spoke = 0; spoke < RING_PROBE_SPOKES; spoke++) {
			double angle = (Math.PI * 2.0 * spoke) / RING_PROBE_SPOKES;
			if (ground.surfaceAt(end, center.x + Math.cos(angle) * radius,
					center.z + Math.sin(angle) * radius) != TrialEnderPulse.NO_GROUND) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 고리 뒷자락이 지나간 사람을 가른다.
	 *
	 * <p>경계를 <b>{@code (지난 틱 뒷자락, 이번 틱 뒷자락]}</b> 반열린 구간으로 잡는다. 고리는 한
	 * 틱에 {@code maxRadius / travelTicks} 칸씩 건너뛰므로 「반경과 거리가 같은가」로 물으면
	 * 대부분의 사람이 <b>그냥 건너뛰어진다.</b> 구간으로 물으면 0 부터 {@code maxRadius} 까지
	 * 어느 거리든 정확히 한 번 덮인다. 「엔더 파동」이 쓰는 그 구간이고 같은 함수에서 나온다.
	 *
	 * <p><b>거리만으로는 부족하다.</b> 고리가 지형을 타므로 같은 거리라도 내 발밑을 지나갔는지는
	 * 높이를 봐야 안다({@link TrialEnderPulse#atRingHeight}). 그 검사가 없으면 지붕 밑이나 굴
	 * 속에 선 사람이 <b>머리 위로 지나간 고리</b>에 맞고 밀려난다.
	 *
	 * @param crossed 이 고리의 명단. 고리마다 따로다 — 합치면 두 번째 고리가 아무도 안 때린다
	 */
	private static void judge(ServerLevel end, TrialEnderPulse.Ground ground,
			List<ServerPlayer> present, Vec3 center, Set<UUID> crossed, int step, long now,
			TrialCatalog.Risk.LandingShock risk) {
		double outer = TrialEnderPulse.judgeRadius(step, risk.travelTicks(), risk.maxRadius());
		double inner = TrialEnderPulse.judgeRadius(step - 1, risk.travelTicks(), risk.maxRadius());
		for (ServerPlayer member : present) {
			UUID memberId = member.getUUID();
			if (crossed.contains(memberId)) {
				continue;
			}
			Vec3 at = member.position();
			double distance = flatDistance(at, center);
			if (!(distance > inner) || !(distance <= outer)) {
				continue;
			}
			// 지나간 것은 뛰었든 아니든 지나간 것이다. 안 적으면 우리가 민 사람이 다음 틱에
			// 뒷자락 앞으로 나가 같은 고리에 두 번 맞는다. 높이로 빠진 사람도 적는다 — 고리는
			// 그 사람의 칸을 이미 지나갔다.
			crossed.add(memberId);
			if (!TrialEnderPulse.atRingHeight(at.y, ground.surfaceAt(end, at.x, at.z))) {
				continue;
			}
			if (jumpedThrough(memberId, now)) {
				dodged(end, member);
				continue;
			}
			strike(end, ground, member, center, risk);
		}
	}

	/**
	 * 이 사람이 <b>고리가 지나가는 동안</b> 한 번이라도 떠 있었는가.
	 *
	 * <p>판정은 앞머리가 지난 뒤 {@link #JUMP_WINDOW_TICKS} 틱째에 내리므로, 지금부터 그만큼
	 * 거슬러 본 구간이 곧 「고리가 내 위에 있던 시간」이다. <b>「엔더 파동」의 {@code jumpedThrough}
	 * 와 같은 규칙이다 — 한쪽만 고치지 말 것.</b>
	 *
	 * <p>기록이 아예 없으면 걸린 것으로 본다. 이 카드가 붙은 뒤로 한 번도 안 뛰었다는 뜻이다.
	 */
	static boolean jumpedThrough(@Nullable UUID memberId, long now) {
		Long last = LAST_AIRBORNE.get(memberId);
		return last != null && now - last <= JUMP_WINDOW_TICKS;
	}

	/**
	 * 고리가 <b>나에게</b> 닿기까지 남은 시간으로 경고 층을 올린다.
	 *
	 * <p>층과 소리는 {@link TrialWarning} 의 것을 그대로 쓰고, 닿는 틱은
	 * {@link TrialEnderPulse#reachTick} 에서 가져온다 — 같은 고리라 예고도 같은 자리에서 나와야
	 * 한다.
	 *
	 * <p>남은 시간이 <b>사람마다 다르다.</b> 고리는 중앙에서 출발하므로 가까이 선 사람에게 먼저
	 * 닿는다. 그래서 소리도 <b>그 사람에게만</b> 간다({@link TrialWarning#soundFor}) — 한 점에
	 * 놓으면 16칸 밖에는 안 들리고, 사람 자리마다 놓으면 반경 안의 전원에게 나가 <b>남의 경고까지
	 * 듣고 겹쳐 듣는다.</b>
	 *
	 * <p>출발 틱은 층이 바뀌지 않았어도 무조건 한 번 울린다. 고리가 없던 직전 틱에는 층 자체가
	 * 없으니 「바뀌었다」가 참이어야 맞고, 무엇보다 <b>중앙 가까이 선 사람은 처음부터 마지막
	 * 층</b>이라 그렇지 않으면 경고를 한 번도 못 듣는다. 이 카드에서는 그 사람이 곧 <b>드래곤이
	 * 발밑에 내려앉은 사람</b>이다.
	 *
	 * <p>그 판단을 여기서 하지 않는다. {@link TrialRisks#stageJustChanged(int, int)} 에 이
	 * 사람의 <b>예고 길이</b>(고리가 나에게 닿기까지 걸리는 전체 틱)를 넘기면 같은 답이 나온다 —
	 * 전에는 {@code step != 0} 을 이 파일에서 따로 적어 막았는데, 예고가 ≤50틱인 카드마다 같은
	 * 줄을 다시 적어야 하는 모양이라 한가운데로 옮겼다.
	 */
	private static void warn(ServerLevel end, List<ServerPlayer> present, Vec3 center, int step,
			TrialCatalog.Risk.LandingShock risk) {
		for (ServerPlayer member : present) {
			Vec3 at = member.position();
			double distance = flatDistance(at, center);
			if (distance > risk.maxRadius()) {
				// 고리가 닿지 않는 자리다. 그런데도 울리면 「경고는 들었는데 아무 일도 없다」가
				// 되고, 그 경험 하나가 다음 고리의 경고까지 무시하게 만든다.
				continue;
			}
			// 이 사람의 예고 길이. 고리가 출발한 틱(step 0)에 남아 있던 틱 수 그대로다.
			int lead = TrialEnderPulse.reachTick(distance, risk.travelTicks(), risk.maxRadius());
			int remaining = lead - step;
			if (remaining < 0) {
				continue;
			}
			TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
			if (stage == null) {
				continue;
			}
			if (!TrialRisks.stageJustChanged(remaining, lead)) {
				continue;
			}
			TrialWarning.soundFor(end, member, stage);
		}
	}

	/**
	 * 뛰어서 넘겼다. 아무 일도 일어나지 않는다.
	 *
	 * <p>그래도 신호는 준다 — <b>「지금 그 판단이 맞았다」</b>를 돌려주지 않으면 사람은 자기가
	 * 뛴 덕분인지 고리가 안 왔던 것인지 배울 수 없다. 피해도 넉백도 없으므로 발밑의 작은 바람
	 * 한 번이면 된다.
	 *
	 * <p><b>전에는 파티클뿐이었다.</b> 그런데 이 카드에서 뛰는 사람은 대개 <b>발밑을 안 보고</b>
	 * 있고(고리를 보려면 멀리 봐야 한다), 사람이 「잘 안 보여」라고 한 것이 그 이야기이기도 했다.
	 * 그래서 소리를 하나 붙였다 — {@link #strike} 가 맞은 사람에게 내는 것과 <b>같은 소리를 더
	 * 높고 작게</b> 낸다. 같은 소리면 「고리와 나 사이에 무슨 일이 있었다」가 하나로 읽히고,
	 * 음과 크기가 맞았을 때와 갈라 준다(「엔더폭풍」이 음만 낮춰 가른 것과 같은 수법이다).
	 */
	private static void dodged(ServerLevel end, ServerPlayer member) {
		Vec3 at = member.position();
		end.sendParticles(ParticleTypes.SMALL_GUST, true, false, at.x, at.y, at.z, 2,
				0.2, 0.1, 0.2, 0.0);
		end.playSound(null, at.x, at.y, at.z, SoundEvents.WIND_CHARGE_BURST.value(),
				SoundSource.HOSTILE, 0.5F, 1.6F);
	}

	/**
	 * 고리에 맞았다. 피해와 바깥 넉백이 함께 들어간다.
	 *
	 * <p>피해원에 <b>가해 개체를 달지 않는다.</b> 실체가 붙은 피해원이면 {@code LivingEntity} 가
	 * 스스로 밀어내는데, 그 밀기는 우리가 정한 방향도 거리도 아니다 — {@link #outwardLimit} 의
	 * 천장이 그 한 줄로 무너진다. 넉백은 {@link #push} 하나만 준다. 폭발 피해형이라 폭발 보호는 그대로
	 * 들으므로 대비한 사람이 손해 보지 않는다.
	 *
	 * <p>블록은 한 칸도 건드리지 않고 불도 붙이지 않는다.
	 */
	private static void strike(ServerLevel end, TrialEnderPulse.Ground ground, ServerPlayer member,
			Vec3 center, TrialCatalog.Risk.LandingShock risk) {
		Vec3 at = member.position();
		member.hurtServer(end, end.damageSources().explosion(null, null), risk.damage());
		push(end, ground, member, center, risk);
		end.sendParticles(ParticleTypes.GUST, true, false, at.x, at.y + 0.1, at.z, 1,
				0.0, 0.0, 0.0, 0.0);
		// 맞은 사람 자리에서 울린다. 밀려나는 소리라 파랑 표식과 같은 뜻을 귀로도 말한다.
		end.playSound(null, at.x, at.y, at.z, SoundEvents.WIND_CHARGE_BURST.value(),
				SoundSource.HOSTILE, 1.0F, 0.7F);
	}

	/**
	 * 바깥쪽으로 민다.
	 *
	 * <h2>세로로는 한 칸도 띄우지 않는다</h2>
	 *
	 * <p>띄우면 밀리는 거리가 <b>몇 배로 늘어난다.</b> 바닥 마찰은 0.546 인데 공중 감쇠는 0.91
	 * 이라, 같은 처음 속도라도 떠 있는 동안은 훨씬 멀리 간다. 「미는 거리」의 천장을 지키는 가장
	 * 싼 방법이 아예 안 띄우는 것이고, 덤으로 낙하 피해를 면제할 상태도 들지 않아도 된다.
	 *
	 * <p>그래서 실제로 밀리는 거리는 적힌 값보다 <b>훨씬 짧다</b>({@link #pushVelocity} 참고).
	 * 어긋나는 방향이 이 카드에서는 중요하다 — <b>반드시 덜 미는 쪽으로만</b> 어긋나야 한다.
	 *
	 * <h2>천장이 둘이다</h2>
	 *
	 * <p>{@link #pushDistance} 가 {@link #outwardLimit} 으로 <b>거리</b>를 자르고, 그 뒤
	 * {@link #groundedReach} 가 그 길을 한 칸씩 짚어 <b>땅이 끊기기 전</b>에서 한 번 더 자른다.
	 * 앞의 것은 월드 없이도 답이 정해져 시험이 훑을 수 있고, 뒤의 것은 실제 섬이 둥글지 않다는
	 * 것을 안다. 둘 중 어느 하나도 빼지 말 것 — <b>낙사 한 번이 월드 삭제</b>다.
	 *
	 * <p>{@code syncVelocity} 를 켜지 않으면 서버 혼자 민 것이 되어 잠시 뒤 제자리로 되돌아간다
	 * ({@code TrialRisks.launch} 와 같은 이유). 세로 속도는 건드리지 않고 그대로 둔다.
	 */
	private static void push(ServerLevel end, TrialEnderPulse.Ground ground, ServerPlayer member,
			Vec3 center, TrialCatalog.Risk.LandingShock risk) {
		double dx = member.getX() - center.x;
		double dz = member.getZ() - center.z;
		double from = Math.sqrt(dx * dx + dz * dz);
		if (!(from > PUSH_MIN_REACH)) {
			// 중심에 정확히 겹쳐 있다. 「바깥쪽」이 없으므로 밀지 않는다.
			return;
		}
		double stepX = dx / from;
		double stepZ = dz / from;
		double distance = pushDistance(fromCenter(member.getX(), member.getZ()), risk.knockback(),
				outwardLimit(risk.maxRadius(), risk.knockback()));
		distance = groundedReach(
				(x, z) -> ground.surfaceAt(end, x, z) != TrialEnderPulse.NO_GROUND,
				member.getX(), member.getZ(), stepX, stepZ, distance);
		if (!(distance > 0.0)) {
			return;
		}
		double speed = pushVelocity(distance);
		Vec3 motion = member.getDeltaMovement();
		member.setDeltaMovement(stepX * speed, motion.y, stepZ * speed);
		member.syncVelocity = true;
	}

	/**
	 * 미는 길에 <b>땅이 이어져 있는 데까지</b>의 거리.
	 *
	 * <p>{@link #outwardLimit} 은 중앙에서 잰 반경으로만 막는다. 그런데 중앙 섬은 둥글지 않아
	 * 그 반경 안에도 허공이 있고({@code TrialRisks.pickSpot} 의 설명), 사람이 파 놓은 구멍도
	 * 있다. 그런 자리로 밀면 천장을 지켰는데도 낙사다.
	 *
	 * <p>그래서 길을 <b>한 칸씩</b> 짚는다. 땅이 없는 칸을 만나면 <b>그 앞에서</b> 멈춘다 —
	 * 건너편에 다시 땅이 있어도 건너뛰지 않는다. 중간이 비어 있으면 밀려가는 몸은 그 구멍으로
	 * 떨어지지 건너가지 않는다.
	 *
	 * <p>값은 {@code wanted} 를 넘지 않으므로 짚는 횟수는 <b>{@code wanted} 칸 남짓</b>이다.
	 * 맞은 사람에게만 한 번 도는 것이고 팀은 넷이라, 한 착지에 예순 번쯤 묻는 것이 전부다.
	 *
	 * <p>월드를 직접 묻지 않고 {@link GroundProbe} 를 받는다. <b>낙사를 막는 함수라 시험이
	 * 월드 없이 훑을 수 있어야</b> 하기 때문이다 — 전멸하면 월드가 지워지는 게임이라 이 계산을
	 * 실제로 굴려 보고 확인할 수는 없다.
	 *
	 * @param probe  그 자리에 설 땅이 있는가
	 * @param stepX  미는 방향의 x 성분. <b>단위 벡터여야 한다</b>
	 * @param stepZ  미는 방향의 z 성분
	 * @param wanted 여기까지 밀고 싶다는 거리
	 */
	static double groundedReach(GroundProbe probe, double x, double z, double stepX, double stepZ,
			double wanted) {
		if (!(wanted > 0.0)) {
			return 0.0;
		}
		double reached = 0.0;
		for (double along = GROUND_PROBE_STEP; ; along += GROUND_PROBE_STEP) {
			double at = Math.min(along, wanted);
			if (!probe.hasGround(x + stepX * at, z + stepZ * at)) {
				// 구멍을 만났다. 건너편에 다시 땅이 있어도 건너뛰지 않는다 — 밀려가는 몸은
				// 구멍을 건너가지 않고 그리로 떨어진다.
				return reached;
			}
			reached = at;
			if (at >= wanted) {
				return reached;
			}
		}
	}

	/** 그 자리에 설 땅이 있는가. {@link #groundedReach} 를 월드에서 떼어 놓는 자리다. */
	@FunctionalInterface
	interface GroundProbe {
		boolean hasGround(double x, double z);
	}

	// ------------------------------------------------------------------ 그리기

	/**
	 * 고리를 지금 반경으로 그린다.
	 *
	 * <h2>먼지 하나로는 고리가 안 된다 — 수명 때문이다</h2>
	 *
	 * <p>26.3 {@code DustParticleBase} 의 수명은 {@code max(1, (int)(8.0 / (굴림×0.8 + 0.2)) × 크기)}
	 * 라 크기 1.0 에서 <b>8~40틱</b>이다. 이 고리는 틱당 0.5칸으로 나아가므로, 파랑 먼지만으로
	 * 그리면 지나간 자국이 4~20칸 남아 <b>「고리」가 아니라 「퍼지는 원판」</b>으로 보인다.
	 * 앞머리가 어디인지 안 읽히면 언제 뛰어야 하는지도 안 읽힌다.
	 *
	 * <p>그래서 「엔더 파동」과 <b>같은 문법</b>으로 두 벌을 겹쳐 찍는다. 앞머리는 수명 4~10틱짜리
	 * {@code CRIT}(흰 불티)이라 자국이 2~5칸에서 끝나 <b>선으로 남고</b>, 몸통은 파랑 먼지가
	 * 지나간 자리를 채운다. 두 카드의 고리가 같은 모양으로 읽혀야 사람이 「고리는 뛰면 피한다」를
	 * 한 번만 배운다.
	 *
	 * <h2>앞머리는 세우고 몸통은 키운다 — 점은 그대로다</h2>
	 *
	 * <p>사람이 「너무 이펙트가 잘안보여」라고 해서 고친 자리가 여기다. 앞머리는 위로 쏘고
	 * ({@link #EDGE_RISE_SPEED}) 몸통은 크기를 올렸다({@link #WAKE_SCALE}). <b>둘 다 점 수를
	 * 한 개도 안 늘린다</b> — 까닭과 한계는 그 두 상수에 적어 두었다.
	 *
	 * <h2>몸통이 파랑인 것이 이 카드의 이름표다</h2>
	 *
	 * <p>색은 {@link #markColor} 가 고른다 — <b>파랑, 「밀려난다」</b>. 왜 그 색인지는 그쪽에
	 * 적어 두었다. 「엔더 파동」이 파랑을 피하고 엔더 입자를 쓴 것은 그 카드에 <b>넉백이 없어서</b>
	 * 이고, 이 카드는 넉백이 본체라 파랑이 정확히 제 뜻이다. 그러니 두 고리는 <b>앞머리가 같고
	 * 몸통 색이 다르다</b> — 문법은 하나, 뜻은 둘이다.
	 *
	 * <p>먼지 수명의 하한(크기 1.0 에서 8틱, 지금 크기에서 10틱)이 판정 창
	 * {@link #JUMP_WINDOW_TICKS}(5틱)보다 길다. 그래서 <b>판정이 내려지는 뒷자락까지는 반드시
	 * 파랑으로 덮여 있다</b> — 맞는 자리가 안 그려진 채로 맞는 일이 없다.
	 *
	 * <h2>점 수는 「엔더 파동」에서 가져오되 예산으로 한 번 더 자른다</h2>
	 *
	 * <p>고리가 셋이라 그쪽 값을 그대로 쓰면 한 틱에 1200점이 나갈 수 있다. 그래서
	 * {@link #ringAllowance} 가 <b>바라는 만큼에 비례해</b> 예산을 나눠 준다.
	 *
	 * <p>그런데 몫을 그대로 점 수로 쓰면 <b>고리가 성겨진다</b> — 셋이 함께 섬 위를 돌 때 반경
	 * 42 짜리 앞머리가 2.2칸 간격이 되어 점선으로 읽힌다. 언제 뛰어야 하는지를 말하는 줄이 그
	 * 꼴이면 카드가 제 일을 못 한다.
	 *
	 * <p>그래서 「종말의 비」가 먼저 푼 답을 그대로 쓴다 — <b>시간축으로 나눈다</b>
	 * ({@link TrialWarning#markGround(ServerLevel, Vec3, double, ParticleOptions, int, int)}).
	 * 고리의 점자리는 온전히 {@code edgePoints} 개로 두고 한 틱에 {@link #stride} 개마다 하나씩만
	 * 찍되, 다음 틱에 위상을 한 칸 옮겨 빈자리를 메운다. 먼저 찍은 점이 아직 살아 있으므로
	 * <b>눈에는 촘촘한 고리 하나</b>로 보인다.
	 *
	 * <p>그쪽 함수를 그냥 부르지 못하는 것은 <b>높이</b> 때문이다. {@code markGround} 는 한 바퀴를
	 * {@code center.y} 한 값에 찍는데, 이 카드의 고리는 칸마다 제 지표를 따라가야 한다. 발상만
	 * 가져오고 {@code stride}·{@code phase} 라는 이름을 그대로 쓴다.
	 *
	 * @param allowance 이 고리가 이번 틱에 쓸 수 있는 점 수({@link #ringAllowance})
	 * @param now       위상을 뽑을 틱. 받은 값을 쓴다 — {@code getGameTime} 은 얼어붙은 판에서
	 *                  멈춰 위상이 한자리에 고정되고, 그러면 고리가 영영 안 닫힌다
	 */
	private static void draw(ServerLevel end, TrialEnderPulse.Ground ground, Vec3 center,
			double radius, int allowance, long now) {
		if (!(radius > 0.0) || allowance <= 0) {
			// 출발 틱에는 그릴 것이 없다. 중앙 한 점에 수백 발을 쏘아 봐야 덩어리 하나다.
			return;
		}
		int edgeRoom = edgeShare(allowance);
		stroke(end, ground, ParticleTypes.CRIT, EDGE_RISE_SPEED, center, radius,
				TrialEnderPulse.edgePoints(radius), edgeRoom, now);
		stroke(end, ground, wakeDust(), 0.0, center, radius,
				TrialEnderPulse.wakePoints(radius), allowance - edgeRoom, now);
	}

	/**
	 * 몸통에 쓰는 파랑 먼지.
	 *
	 * <p>{@code TrialWarning.dust} 를 쓰지 않는 것은 <b>그쪽이 크기를 1.0 으로 박아 두기</b>
	 * 때문이다. 색은 그대로 {@link #markColor} 에서 가져오므로 규약은 지켜진다 — 바뀌는 것은
	 * 크기뿐이고 까닭은 {@link #WAKE_SCALE} 에 있다.
	 *
	 * <p>{@code TrialWarning} 쪽에 크기를 받는 형태를 더하지 않은 것은 그 파일을 다른 사람이
	 * 쓰고 있기 때문이다. 크기를 쓰는 카드가 둘이 되면 그때 그쪽으로 옮길 것.
	 */
	private static ParticleOptions wakeDust() {
		return new DustParticleOptions(markColor(), WAKE_SCALE);
	}

	/**
	 * 고리 한 바퀴 가운데 <b>이번 틱 몫</b>을 찍는다.
	 *
	 * <p>{@code room} 개를 넘지 않도록 {@link #stride} 를 고르고, 그래도 넘치면 점자리 자체를
	 * 줄인다. 두 장치가 함께 있어야 <b>예산이 반드시 지켜진다</b> — {@code stride} 는 8보다
	 * 작아야 해서(아래) 그것만으로는 아무리 큰 고리도 담을 수 없기 때문이다.
	 *
	 * @param rise 점을 위로 쏘는 속도. 0 이면 제자리에 찍는다({@link #ring} 참고)
	 */
	private static void stroke(ServerLevel end, TrialEnderPulse.Ground ground, ParticleOptions type,
			double rise, Vec3 center, double radius, int wholeRing, int room, long now) {
		if (wholeRing <= 0 || room <= 0) {
			return;
		}
		int step = stride(wholeRing, room);
		int points = Math.min(wholeRing, room * step);
		ring(end, ground, type, rise, center, radius, points, step, Math.floorMod(now, step));
	}

	/**
	 * 중심을 도는 점들을 <b>각자 제 자리의 지표 위에</b> 찍는다.
	 *
	 * <p><b>첫 {@code boolean} 을 {@code false} 로 되돌리지 말 것.</b> 짧은 형태는 서버에서
	 * 32칸으로 잘리고 클라이언트가 한 번 더 거른다({@link TrialWarning} 의 「거리 제한을 끄고
	 * 보낸다」). 이 고리는 75칸까지 가므로 되돌리면 바깥쪽이 <b>아무에게도 안 그려진다.</b>
	 *
	 * <p>허공에 걸린 점은 건너뛴다. 섬이 끝나는 자리에서 고리도 함께 끊겨 「여기서부터 땅이
	 * 없다」가 그대로 읽히고, 반경 75 의 바깥쪽 절반은 어차피 허공 위라 실제로 나가는 점은
	 * 세는 수보다 훨씬 적다.
	 *
	 * <p>각을 도는 순서를 뒤섞지 말 것 — {@link TrialEnderPulse.Ground} 의 청크 기억이 이어
	 * 도는 것을 전제로 한다. {@code step} 만큼 건너뛰어도 순서는 한 방향 그대로다.
	 *
	 * <h2>⚠ 위로 쏘는 점은 <b>개수 0</b> 으로 보낸다 — 실수가 아니다</h2>
	 *
	 * <p>바닐라는 파티클 꾸러미의 <b>개수가 0 일 때만</b> 뒤의 세 값을 <b>속도</b>로 읽는다
	 * (26.3 {@code ClientPacketListener.handleParticleEvent} 가 {@code count() != 0} 이면 그
	 * 값들을 「퍼뜨릴 범위」로 쓴다). 개수를 1 로 두고 속도를 적으면 <b>제자리에 흩뿌리기만</b>
	 * 하고 한 점도 안 올라간다 — 빌드도 로그도 조용한 채로 연출만 사라지는 종류의 실수다.
	 *
	 * <p>개수 0 이라고 아무것도 안 나가는 것이 아니다. 그 갈래도 파티클을 <b>정확히 하나</b>
	 * 만들므로 점 수도 패킷 수도 제자리에 찍을 때와 같다. 그래서 이 카드가 <b>예산을 한 점도
	 * 더 안 쓰고</b> 고리를 세울 수 있다.
	 *
	 * @param points 고리 한 바퀴의 점자리 수
	 * @param step   그 가운데 몇 개마다 하나씩 찍을지
	 * @param phase  이번 틱에 찍을 몫. {@code 0..step-1}
	 * @param rise   위로 쏘는 속도. 0 이면 제자리에 찍는다
	 */
	private static void ring(ServerLevel end, TrialEnderPulse.Ground ground, ParticleOptions type,
			double rise, Vec3 center, double radius, int points, int step, int phase) {
		for (int index = phase; index < points; index += step) {
			double angle = (Math.PI * 2.0 * index) / points;
			double x = center.x + Math.cos(angle) * radius;
			double z = center.z + Math.sin(angle) * radius;
			int surface = ground.surfaceAt(end, x, z);
			if (surface == TrialEnderPulse.NO_GROUND) {
				continue;
			}
			if (rise > 0.0) {
				end.sendParticles(type, true, false, x, surface + GROUND_OFFSET, z,
						0, 0.0, 1.0, 0.0, rise);
				continue;
			}
			end.sendParticles(type, true, false, x, surface + GROUND_OFFSET, z,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	// ------------------------------------------------------------------ 사람 고르기

	/**
	 * 지금 이 차원에 있고 판정을 받을 수 있는 팀원.
	 *
	 * <p><b>차원을 반드시 본다.</b> {@code DragonTrialManager} 가 넘기는 목록은 「접속해 있는
	 * 팀원」이라 <b>다른 차원에 있는 사람도 들어 있다.</b> 고리는 중심에서 잰 수평 거리로만
	 * 판정하므로, 오버월드 원점 근처에 서 있는 팀원이 엔드의 고리에 맞는다.
	 */
	private static List<ServerPlayer> present(ServerLevel end, @Nullable List<ServerPlayer> members) {
		List<ServerPlayer> here = new ArrayList<>();
		if (members == null) {
			return here;
		}
		for (ServerPlayer member : members) {
			if (member != null && member.isAlive() && !member.isSpectator()
					&& member.level() == end) {
				here.add(member);
			}
		}
		return here;
	}

	/**
	 * 지금 공중에 있는 사람을 적어 둔다.
	 *
	 * <p>보는 것이 {@code onGround()} 하나인 것은 <b>「엔더 파동」과 같은 규칙</b>이기 때문이다.
	 * 물이나 사다리를 따로 세는 쪽이 정교하지만, 두 고리가 서로 다른 「발판」을 쓰면 사람이 한
	 * 카드에서 배운 것이 다른 카드에서 안 통한다. <b>한쪽만 고치지 말 것.</b>
	 *
	 * <p>이 차원에 없는 사람의 기록은 버린다. 들고 있어 봐야 돌아왔을 때 <b>몇 분 전에 뛴 것</b>
	 * 으로 고리를 통과하게 된다.
	 */
	private static void recordAirborne(List<ServerPlayer> present, long now) {
		Set<UUID> here = new HashSet<>();
		for (ServerPlayer member : present) {
			UUID memberId = member.getUUID();
			here.add(memberId);
			if (!member.onGround()) {
				LAST_AIRBORNE.put(memberId, now);
			}
		}
		LAST_AIRBORNE.keySet().retainAll(here);
	}

	/** 시험용. 월드 없이 발 기록을 꽂아 둔다. */
	static void noteAirborne(UUID memberId, long tick) {
		LAST_AIRBORNE.put(memberId, tick);
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 이 고리의 색.
	 *
	 * <p>{@code TrialRisks.markColor} 를 쓰지 않는 것은 그쪽이 {@code Impact} 에서 색을 끌어내는데
	 * <b>이 위험에는 {@code Impact} 칸이 없기</b> 때문이다. 그래서 실행기가 고르되, 규약 표와
	 * {@code TrialRisks.markColor} 는 손대지 않았다 — 그 둘은 다른 사람이 쓰고 있다.
	 *
	 * <p>고른 값이 함수로 나와 있는 것은 <b>시험이 물을 수 있게</b> 하려는 것이다.
	 * {@code Colors} 는 {@code static final int} 라 컴파일할 때 숫자로 녹아들어, 상수 풀을 뒤지는
	 * 방식으로는 「어느 색을 골랐는가」를 확인할 길이 없다.
	 */
	static int markColor() {
		// 파랑 = 밀려난다. 이 카드는 「서 있으면 맞고 밀려난다」라 그 뜻 그대로이고, 규약에서
		// 아직 아무 카드도 쓰지 않던 빈 자리다. 빨강을 쓰면 「자리 폭격」·「기둥 화염구」·
		// 「연쇄 포격」과 섞여 무엇이 오는지 안 읽힌다.
		return TrialWarning.Colors.SHOVE;
	}

	/**
	 * 앞머리를 {@code speed} 로 쏘면 <b>몇 칸까지 올라가는가</b>(칸).
	 *
	 * <p>{@link #EDGE_RISE_SPEED} 를 고른 근거를 <b>시험이 물을 수 있게</b> 함수로 둔다. 「위로
	 * 쏜다」는 눈으로만 확인되는 종류의 값이라, 다음 사람이 2.0 을 20 으로 바꿔도 컴파일도 시험도
	 * 조용하면 <b>다른 카드의 바닥 표식을 가리는 벽</b>이 서 있는 것을 아무도 못 잡는다.
	 *
	 * <p>26.3 클래스 파일을 뜯어 확인한 그대로 굴린다.
	 *
	 * <ul>
	 *   <li>{@code CritParticle} 이 받은 속도에 <b>0.4</b> 를 곱해 싣는다. 여기에 무작위 밑바닥이
	 *       0.02 남짓 섞이는데 지금 값에서 2% 라 세지 않는다</li>
	 *   <li>{@code Particle.tick} 이 매 틱 <b>{@code yd -= 0.04 × 중력}</b> 을 먼저 하고,
	 *       옮긴 다음 <b>마찰</b>을 곱한다. {@code CritParticle} 의 중력이 0.5, 마찰이 0.7 이다</li>
	 *   <li>수명은 4~10틱인데 <b>짧은 쪽</b>으로 센다. 가장 낮게 서는 벽이 그것이다</li>
	 * </ul>
	 *
	 * <p>바닥에 부딪히는 것은 안 센다 — {@code CritParticle} 은 {@code hasPhysics} 가 거짓이라
	 * 블록을 통과하고, 어차피 위로만 간다.
	 */
	static double riseHeight(double speed) {
		double velocity = Math.max(0.0, speed) * CRIT_SPEED_FACTOR;
		double height = 0.0;
		for (int tick = 0; tick < CRIT_MIN_LIFETIME; tick++) {
			velocity -= CRIT_GRAVITY_PULL;
			if (velocity <= 0.0) {
				break;
			}
			height += velocity;
			velocity *= CRIT_FRICTION;
		}
		return height;
	}

	/**
	 * ⚠⚠ 이 카드가 사람을 밀어 놓을 수 있는 <b>중앙에서의 가장 먼 거리.</b>
	 *
	 * <p><b>이 함수가 지금 이 카드를 살려 두는 장치다.</b> 예전에는 {@code 15 + 8 = 23} 이
	 * 저절로 섬 안이라 이 함수가 산수를 옮겨 적은 것에 지나지 않았는데, 지금 값
	 * ({@code 75 + 16 = 91})에서는 <b>이것 말고 사람을 섬 안에 붙들어 두는 것이 없다.</b>
	 *
	 * <p>천장은 섬 경계가 아니라 {@link #EDGE_MARGIN} 만큼 <b>안쪽</b>이다. 경계에 딱 세우면
	 * 거기서 한 걸음이 곧 낙사이고, 공유 체력이라 그 한 걸음이 팀 전체를 끝낸다.
	 *
	 * <p>여기에 걸리면 넉백이 그냥 짧아진다 — 밀려서 허공으로 나가는 것보다 덜 밀리는 쪽이
	 * 언제나 싸다. 카드에 어떤 값을 적어도 이 함수는 못 피한다.
	 */
	static double outwardLimit(double maxRadius, double knockback) {
		double reach = Math.max(0.0, maxRadius) + Math.max(0.0, knockback);
		return Math.max(0.0, Math.min(TrialRisks.ARENA_RADIUS - EDGE_MARGIN, reach));
	}

	/** 아레나 중앙 {@code (0, 0)} 에서 잰 거리. 천장이 재는 것이 이 거리다. */
	static double fromCenter(double x, double z) {
		return Math.sqrt(x * x + z * z);
	}

	/**
	 * 몇 번째 고리가 <b>몇 틱째</b>인가. 음수면 아직 안 태어났다는 뜻이다.
	 *
	 * <p>고리마다 시계를 따로 들지 않고 <b>벌의 출발 틱 하나</b>에서 뺀다. 따로 들면 하나가
	 * 어긋나기 시작해도 나머지가 멀쩡해 알아채지 못한다.
	 *
	 * @param age          벌이 출발한 뒤 지난 틱
	 * @param ringIndex    몇 번째 고리인가. 0 이 첫 고리다
	 * @param intervalTicks 고리 사이 간격. {@link #ringInterval} 을 거친 값이어야 한다
	 */
	static int stepOf(long age, int ringIndex, int intervalTicks) {
		return (int) (age - (long) ringIndex * intervalTicks);
	}

	/** 마지막 고리의 뒷자락까지 다 지나가는 틱. 벌은 그 틱을 넘기면 사라진다. */
	static long lastTick(int rings, int intervalTicks, int ringLifetime) {
		return (long) Math.max(0, rings - 1) * intervalTicks + Math.max(0, ringLifetime);
	}

	/**
	 * 실제로 돌릴 고리 수.
	 *
	 * <p>0 이하로 적힌 카드는 <b>아무 일도 안 일어나는 카드</b>가 되는데, 착지가 발동 조건이라
	 * 그때는 「드래곤이 앉았는데 조용하다」가 되어 버그로 읽힌다. 최소 하나는 돌린다.
	 */
	static int ringCount(int ringCount) {
		return Math.max(1, ringCount);
	}

	/**
	 * 실제로 쓸 고리 간격.
	 *
	 * <p>0 이하로 적히면 고리 전부가 <b>같은 틱에 겹쳐</b> 한 사람이 한 번에 {@code ringCount}
	 * 번 맞는다 — 지금 값에서 한 틱에 12, 팀 합계 48 이라 그 자리에서 전멸이다. 최소 1 틱은
	 * 벌린다. 벌린다고 안전해지는 것은 아니지만 적어도 <b>한 틱에 다 들어오지는 않는다.</b>
	 */
	static int ringInterval(int ringIntervalTicks) {
		return Math.max(1, ringIntervalTicks);
	}

	/**
	 * 고리 하나가 이번 틱에 쓸 수 있는 점 수.
	 *
	 * <h2>고리 수로 똑같이 나누지 않는다</h2>
	 *
	 * <p>고리 셋이 함께 날 때 반경이 40·20·0.5 인 순간이 있다. 똑같이 나누면 갓 태어난 반경
	 * 0.5 짜리가 146점을 받아 <b>한 점을 서른 번 겹쳐 찍고</b>, 정작 둘레 251칸짜리 바깥 고리가
	 * 같은 146점으로 2.9칸씩 벌어져 점선이 된다.
	 *
	 * <p>그래서 <b>바라는 만큼에 비례해</b> 나눈다. 바라는 수는 둘레에서 나오므로 결국 반경에
	 * 비례하고, 작은 고리는 제가 필요한 만큼만 가져간다. 셋이 다 땅 위에 있는 그 순간
	 * (둘레 합 380칸)에 440점이면 점 사이가 0.86칸이라 <b>「엔더 파동」의 가장 성긴
	 * 자리(1.1칸)보다도 촘촘하다.</b>
	 *
	 * <p>합이 예산 안이면 그냥 바라는 대로 준다 — 고리 하나만 날 때가 그렇고, 그때는 예전과
	 * 똑같이 400점이다.
	 *
	 * <p>내림 나눗셈이라 <b>몫의 합이 예산을 넘을 수 없다</b>. 그것이 이 함수가 있는 이유다.
	 *
	 * @param want   이 고리가 바라는 점 수
	 * @param wanted 이번 틱에 그리는 고리들이 바라는 점 수의 합
	 */
	static int ringAllowance(int want, int wanted) {
		if (want <= 0 || wanted <= 0) {
			return 0;
		}
		if (wanted <= MAX_POINTS_PER_TICK) {
			return want;
		}
		return (int) ((long) want * MAX_POINTS_PER_TICK / wanted);
	}

	/**
	 * 몫 가운데 <b>앞머리</b>에 돌아가는 수. 나머지가 몸통이다.
	 *
	 * <p>가르는 비율을 숫자로 적지 않고 「엔더 파동」의 상한(240:160)에서 뽑는다. 그쪽이 바뀌면
	 * 이 카드의 고리도 같은 모양으로 따라가야 하기 때문이다 — 두 고리가 다르게 보이면 사람이
	 * 「고리는 뛰면 피한다」를 두 번 배운다.
	 */
	static int edgeShare(int allowance) {
		if (allowance <= 0) {
			return 0;
		}
		return (int) ((long) allowance * TrialEnderPulse.EDGE_MAX_POINTS
				/ TrialEnderPulse.MAX_POINTS_PER_TICK);
	}

	/** 그 반경의 고리가 <b>바라는</b> 점 수. 예산을 나누는 저울이다. */
	static int ringWant(double radius) {
		return TrialEnderPulse.edgePoints(radius) + TrialEnderPulse.wakePoints(radius);
	}

	/**
	 * 고리 한 바퀴를 <b>몇 틱에 나눠</b> 그릴지.
	 *
	 * <p>{@code room} 개만 쓸 수 있는데 점자리가 {@code wholeRing} 개면, 한 틱에
	 * {@code ceil(wholeRing / room)} 개마다 하나씩 찍고 다음 틱에 위상을 옮겨 메운다. 예산은
	 * 지키면서 <b>눈에 보이는 촘촘함은 온전히</b> 남는다.
	 *
	 * <h2>⚠ {@link #MAX_STRIDE} 를 넘기지 않는다</h2>
	 *
	 * <p>나눠 그려도 고리가 고리로 보이는 것은 <b>먼저 찍은 점이 아직 살아 있기</b> 때문이다.
	 * 이 카드는 두 입자를 겹쳐 쓰는데 <b>짧은 쪽이 앞머리의 {@code CRIT} 이고 수명이 최소
	 * 4틱</b>이다({@code CritParticle} 의 {@code max(1, 6.0 / (굴림×0.8 + 0.6))}). 먼지의
	 * 하한 8틱보다 이쪽이 먼저 걸리므로 <b>「종말의 비」의 6 이 아니라 더 작은 수</b>를 쓴다.
	 *
	 * <p>여기서 담기지 않는 나머지는 {@link #stroke} 가 점자리를 줄여 받는다. 고리가 성겨지는
	 * 것보다는 낫지만 그쪽이 마지막 수단이라는 뜻이다.
	 */
	static int stride(int wholeRing, int room) {
		if (wholeRing <= 0 || room <= 0) {
			return 1;
		}
		int need = (wholeRing + room - 1) / room;
		return Math.max(1, Math.min(MAX_STRIDE, need));
	}

	/**
	 * 실제로 밀 거리.
	 *
	 * <p>이미 {@link #outwardLimit} 밖에 나가 있는 사람은 <b>한 칸도 밀지 않는다.</b> 다른 카드의
	 * 넉백이나 경사가 먼저 데려다 놓은 경우인데, 거기서 또 밀면 이 카드가 남의 사고를 완성시키는
	 * 꼴이 된다.
	 *
	 * @param fromCenter 중심에서 잰 지금 거리(수평)
	 * @param knockback  카드에 적힌 미는 거리
	 * @param limit      {@link #outwardLimit}
	 */
	static double pushDistance(double fromCenter, double knockback, double limit) {
		double room = limit - Math.max(0.0, fromCenter);
		if (room <= 0.0) {
			return 0.0;
		}
		return Math.min(Math.max(0.0, knockback), room);
	}

	/**
	 * 그 거리를 밀려면 실어야 하는 처음 속도(칸/틱).
	 *
	 * <p><b>공중 감쇠로 계산한다.</b> 처음 속도 {@code v} 인 몸이 계속 떠 있다면 총 이동은
	 * {@code v + 0.91v + 0.91²v + … = v / (1 - 0.91)} 이라, 거꾸로 {@code v = 거리 × 0.09} 다.
	 * 16칸이면 1.44 칸/틱.
	 *
	 * <p>바닥 모델(마찰 0.546)로 잡으면 안 된다. 같은 16칸을 맞추려면 7.2 칸/틱을 실어야 하는데,
	 * 그 속도로 <b>한 틱이라도 떠 있으면 80칸을 날아</b> 섬 밖 허공이다 — 경사 한 칸, 다른 카드의
	 * 띄우기 한 번이면 그 일이 일어난다. 공중 모델은 반대다. 어느 상황에서도 적힌 거리를
	 * <b>넘을 수 없고</b>, 바닥에 붙어 있으면 마찰이 먼저 먹어 5분의 1 남짓만 밀린다.
	 *
	 * <p>그래서 적힌 넉백은 말 그대로 <b>천장</b>이고, 밀리는 거리는 언제나 안전한 쪽으로
	 * 어긋난다. 다만 <b>이 모델은 거리를 줄이는 장치이지 섬 안에 붙드는 장치가 아니다</b> —
	 * 그것은 {@link #outwardLimit} 과 {@link #groundedReach} 가 한다.
	 */
	static double pushVelocity(double distance) {
		return Math.max(0.0, distance) * (1.0 - AIR_DRAG);
	}

	/**
	 * 고리 한 벌이 팀에 넣을 수 있는 <b>가장 큰 합계 피해.</b>
	 *
	 * <p>공유 체력에서 범위 피해는 팀원별로 그대로 합산된다. 이 카드는 전원을 때리고 고리가
	 * {@code rings} 개라 — 명단을 고리마다 따로 들기 때문에 — 한 사람이 최대 {@code rings} 번
	 * 맞는다. 넷이 매번 실패한 판이 {@code perHit × 4 × rings} 다.
	 *
	 * <p>⚠ <b>{@code perHit} 은 카드에 적힌 날값이 아니라 감쇠를 지난 한 대</b>여야 한다.
	 * 방어·보호 감쇠는 <b>한 방마다</b> 걸리는 비선형이라, 곱해 놓고 감쇠하면 실제보다 아프게
	 * 나온다. 곱하는 것이 이 함수의 몫이고 감쇠는 부르는 쪽 몫이다 — 시험 쪽
	 * {@code GearedDamage} 가 그 한 대를 만든다.
	 *
	 * <p>{@code TrialRisks.worstCaseTickDamage} 가 이 위험을 {@code hits(damage, 1)} 로 세는
	 * 것과 어긋나지 않는다. 그쪽은 <b>한 틱</b>에 한 사람이 받는 값이고, 고리들은
	 * {@code ringIntervalTicks} 만큼 떨어져 지나가므로 한 틱에 겹치지 않는다.
	 *
	 * @param perHit  감쇠를 지난 한 대
	 * @param members 팀 인원
	 * @param rings   고리 수
	 */
	static float worstCaseTeamDamage(float perHit, int members, int rings) {
		if (perHit <= 0.0F || members <= 0 || rings <= 0) {
			return 0.0F;
		}
		return perHit * members * rings;
	}

	/**
	 * 한 틱에 고리 하나가 쓰는 점 수의 <b>상한</b>. 「예산 안인가」를 숫자로 묻는 값이다.
	 *
	 * <p>{@link #stroke} 가 실제로 하는 셈을 그대로 따라간다 — 점자리를 {@link #stride} 로
	 * 나눈 몫이다. 위상에 따라 하나 적을 수 있으므로 올림으로 돌려준다
	 * ({@code TrialWarning.strokePoints} 와 같은 까닭).
	 *
	 * <p>이것은 <b>세는 수</b>이지 실제로 나가는 수가 아니다. 허공에 걸린 점은 안 찍히므로
	 * 실제는 이보다 적다 — 반경 40 을 넘어가면 대부분의 점이 섬 밖이라 거의 안 나간다.
	 *
	 * @param allowance 이 고리가 이번 틱에 쓸 수 있는 점 수({@link #ringAllowance})
	 */
	static int markPoints(double radius, int allowance) {
		int edgeRoom = edgeShare(allowance);
		return strokePoints(TrialEnderPulse.edgePoints(radius), edgeRoom)
				+ strokePoints(TrialEnderPulse.wakePoints(radius), allowance - edgeRoom);
	}

	/** {@link #stroke} 한 번에 나가는 점 수의 상한. */
	static int strokePoints(int wholeRing, int room) {
		if (wholeRing <= 0 || room <= 0) {
			return 0;
		}
		int step = stride(wholeRing, room);
		int points = Math.min(wholeRing, room * step);
		return (points + step - 1) / step;
	}

	/** 위에서 본 거리. 고리는 바닥에 그려지므로 높이를 묻지 않는다. */
	static double flatDistance(Vec3 one, Vec3 other) {
		double dx = one.x - other.x;
		double dz = one.z - other.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
