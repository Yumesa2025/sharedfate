package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
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
 * <p>드래곤이 <b>착지할 때마다</b> 중앙에서 고리가 반경 15칸({@code maxRadius})까지 퍼진다
 * ({@code travelTicks} 에 걸쳐). 고리가 지나갈 때
 *
 * <ul>
 *   <li><b>바닥을 딛고 있으면</b> 피해 4({@code damage}) 와 바깥 넉백 최대 8칸({@code knockback})</li>
 *   <li><b>점프해 있으면 회피</b>한다 — 이 카드가 요구하는 행동이 그것 하나다</li>
 * </ul>
 *
 * <h2>⚠ 「엔더 파동」과 <b>같은 고리</b>다 — 한쪽만 고치지 말 것</h2>
 *
 * <p>{@link TrialEnderPulse} 가 중앙에서 퍼지는 고리를 먼저 끝냈고, 동작이 이 카드와 똑같다 —
 * 중앙에서 퍼지고, 바닥을 딛고 있으면 걸리고, 점프하면 통과한다. 다른 것은 반경과 결과뿐이다
 * (엔더 파동 42칸·구속, 착지 충격 15칸·피해 4·넉백).
 *
 * <p><b>두 카드가 같은 동작을 다른 규칙으로 가르치면 사람은 「고리는 뛰면 피한다」를 하나로 배울
 * 수 없다.</b> 한 카드에서 통하던 타이밍이 다른 카드에서 안 통하면 그것은 배움이 아니라 운이다.
 * 그래서 판정에 쓰는 것을 <b>새로 만들지 않고 그쪽 것을 그대로 부른다</b> —
 * {@link TrialEnderPulse#JUMP_WINDOW_TICKS}, {@link TrialEnderPulse#radiusAt},
 * {@link TrialEnderPulse#judgeRadius}, {@link TrialEnderPulse#reachTick},
 * {@link TrialEnderPulse#lifetime}, {@link TrialEnderPulse#edgePoints},
 * {@link TrialEnderPulse#wakePoints} 다.
 *
 * <p>값을 베껴 오지 않은 것이 핵심이다. 같은 숫자를 두 파일에 적어 두면 <b>한쪽만 고쳐지고</b>,
 * 그때 어긋나는 것은 숫자가 아니라 사람이 배운 타이밍이다. 두 규칙을 한 자리로 합치는 일은
 * 아직 안 됐다 — 그때까지는 이 의존이 그 자리를 대신한다.
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
 * <h2>넉백 8칸의 근거</h2>
 *
 * <p>15칸 고리의 <b>가장 바깥</b>에서 맞아 끝까지 밀려도 중앙에서 23칸이다. 섬은 반경 40,
 * 흑요석 기둥이 서 있는 원이 반경 42 이므로 <b>섬 안에 머문다.</b>
 *
 * <p>이 여유가 이 카드의 전부다. <b>낙사시키면 공유 체력이라 팀 전체가 끝난다.</b> 반경이나
 * 넉백을 올리는 사람은 두 값을 함께 더한 수가 섬 반경 안인지부터 볼 것.
 *
 * <p>계산만 맞춰 두고 끝내지 않았다. {@link #outwardLimit} 이 <b>그 23칸을 실제로 천장으로
 * 건다</b> — 다른 카드가 이미 바깥으로 밀어 놓았거나 경사를 타고 흘러가 있어도, 이 카드가 미는
 * 목적지는 결코 중앙에서 23칸(그리고 {@code TrialRisks.ARENA_RADIUS})을 넘지 않는다. 산수는
 * 값을 고치는 사람이 안 볼 수 있지만 이 함수는 못 피한다.
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
 * <h2>한 번의 착지에 고리 하나</h2>
 *
 * <p>앉아 있는 내내 「착지했다」가 참이므로 그것을 그대로 쓰면 초당 스무 번 터져
 * {@code 4 × 20} 으로 즉사한다. 그래서 <b>가장자리</b>만 잡는다({@link #touchdown}) — 직전 틱은
 * 앉아 있지 않았고 이번 틱은 앉아 있는 그 한 틱이다. 다시 날아올랐다가 앉으면 그때 다시 잡힌다.
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
 * <p>그 대신 천장이 낮다. {@link #worstCaseTeamDamage} 가 넷 전원 실패의 값을 직접 센다 —
 * {@code 4 × 4 = 16} 으로 팀 공유 체력 20 아래다. <b>피해 4 는 전원 타격을 전제로만 설명되는
 * 값</b>이고(이 판 카드 중 가장 작다), 여기를 5 로 올리면 20 이라 그날로 즉사 카드다. 16 은 이미
 * 체력의 8할이라 <b>다른 카드와 겹치면 그 16 이 마지막 한 방</b>이다.
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
 * <h2>파티클과 소리는 멀리 보낸다</h2>
 *
 * <p>고리는 15칸까지 가고 보는 사람은 반대편에 있을 수 있어 지름으로 30칸이 넘는다. 파티클
 * 짧은 형태는 <b>32칸</b>에서 잘리므로({@link TrialWarning} 의 「거리 제한을 끄고 보낸다」)
 * 전부 긴 형태로 보낸다. 소리도 같은 이유로 <b>사람마다 그 자리에서</b> 울린다 — 바닐라 소리
 * 사거리는 볼륨 1 이하면 16칸이다.
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

	// ------------------------------------------------------------------ 표식

	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다({@code TrialWarning} 과 같은 값). */
	private static final double GROUND_OFFSET = 0.15;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 퍼지고 있는 고리 하나.
	 *
	 * <p>중심을 <b>좌표로</b> 든다. 드래곤을 들고 있으면 매 틱 그 자리를 다시 읽게 되고, 드래곤이
	 * 날아오른 순간 고리가 따라 움직여 예고가 거짓말을 한다.
	 *
	 * @param center    고리가 출발한 자리. 드래곤이 앉은 포탈 꼭대기다
	 * @param startedAt 착지를 잡은 틱
	 */
	private record Wave(Vec3 center, long startedAt) {
	}

	/** 아레나도 팀도 하나뿐이라 칸 하나로 둔다. {@link #clearState()} 가 반드시 비운다. */
	private static @Nullable Wave wave;
	/**
	 * 이번 고리의 뒷자락이 <b>이미 지나간</b> 사람들.
	 *
	 * <p>고리는 한 사람을 <b>한 번만</b> 지나간다. 없으면 두 가지가 곧바로 깨진다 — 우리가 민
	 * 사람이 다음 틱에 뒷자락 앞으로 나가 또 맞고, 뛰어서 피한 사람이 착지한 다음 틱에 다시
	 * 판정당해 <b>점프가 회피가 아니게</b> 된다. 「엔더 파동」의 {@code crossed} 와 같은 장치다.
	 */
	private static final Set<UUID> CROSSED = new HashSet<>();
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
		watchLanding(end, dragon, now);
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
		wave = null;
		CROSSED.clear();
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
	private static void watchLanding(ServerLevel end, @Nullable EnderDragon dragon, long now) {
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
		open(end, podium, now);
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
	 * 고리를 놓는다.
	 *
	 * <p>앞 고리가 아직 퍼지고 있으면 <b>놓지 않는다.</b> 바닐라에는 고리 수명 안에 앉았다
	 * 일어났다 다시 앉는 길이 없어 실제로는 오지 않는 갈래지만, 그때 새 고리로 갈아 끼우면 이미
	 * 보여 준 고리가 공중에서 사라진다 — <b>그려 놓은 표식을 무르지 않는 것</b>이 이 전투의
	 * 약속이다.
	 */
	private static void open(ServerLevel end, Vec3 podium, long now) {
		if (wave != null) {
			return;
		}
		wave = new Wave(podium, now);
		CROSSED.clear();
		// 착지 자체가 이 카드의 유일한 예고다. 가운데에서 한 번 크게 터뜨려 「지금부터다」를
		// 말한다. 소리는 warn 이 사람마다 제자리에서 울린다 — 한 점에서 울리면 16칸 밖은
		// 아무것도 못 듣는다.
		end.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, true, false,
				podium.x, podium.y + GROUND_OFFSET, podium.z, 1, 0.0, 0.0, 0.0, 0.0);
	}

	// ------------------------------------------------------------------ 고리가 퍼진다

	/**
	 * 고리를 한 칸 넓혀 그리고, 경고를 올리고, 뒷자락이 지나간 사람을 가른다.
	 *
	 * <p>순서가 「엔더 파동」과 같다 — 그리고, 알리고, 판정한다. 고리의 수명도 그쪽
	 * {@link TrialEnderPulse#lifetime} 에서 가져온다. 주기가 없는 카드라 간격 자리에 0 을 넣는데,
	 * 그러면 {@code travelTicks + 창} 이 그대로 나온다. <b>앞머리가 끝까지 간 뒤에도 창만큼
	 * 남는 것</b>이 중요하다 — 깎으면 가장 바깥에 선 사람만 창을 못 받아 그 사람에게만 옛날의
	 * 한 틱짜리 판정이 적용된다.
	 */
	private static void spread(ServerLevel end, List<ServerPlayer> present, long now,
			TrialCatalog.Risk.LandingShock risk) {
		Wave run = wave;
		if (run == null) {
			return;
		}
		long age = now - run.startedAt();
		if (age < 0L || age > TrialEnderPulse.lifetime(0, risk.travelTicks())) {
			// 뒷자락까지 다 지나갔거나 시간이 되감겼다. 남는 것은 없다 — 지나간 자리는 그 틱부터
			// 안전하다.
			wave = null;
			CROSSED.clear();
			return;
		}

		int step = (int) age;
		if (step <= risk.travelTicks()) {
			draw(end, run.center(),
					TrialEnderPulse.radiusAt(step, risk.travelTicks(), risk.maxRadius()));
		}
		warn(end, present, run.center(), step, risk);
		judge(end, present, run.center(), step, now, risk);
	}

	/**
	 * 고리 뒷자락이 지나간 사람을 가른다.
	 *
	 * <p>경계를 <b>{@code (지난 틱 뒷자락, 이번 틱 뒷자락]}</b> 반열린 구간으로 잡는다. 고리는 한
	 * 틱에 0.375칸씩 건너뛰므로 「반경과 거리가 같은가」로 물으면 대부분의 사람이 <b>그냥
	 * 건너뛰어진다.</b> 구간으로 물으면 0 부터 {@code maxRadius} 까지 어느 거리든 정확히 한 번
	 * 덮인다. 「엔더 파동」이 쓰는 그 구간이고 같은 함수에서 나온다.
	 */
	private static void judge(ServerLevel end, List<ServerPlayer> present, Vec3 center, int step,
			long now, TrialCatalog.Risk.LandingShock risk) {
		double outer = TrialEnderPulse.judgeRadius(step, risk.travelTicks(), risk.maxRadius());
		double inner = TrialEnderPulse.judgeRadius(step - 1, risk.travelTicks(), risk.maxRadius());
		for (ServerPlayer member : present) {
			UUID memberId = member.getUUID();
			if (CROSSED.contains(memberId)) {
				continue;
			}
			double distance = flatDistance(member.position(), center);
			if (!(distance > inner) || !(distance <= outer)) {
				continue;
			}
			// 지나간 것은 뛰었든 아니든 지나간 것이다. 안 적으면 우리가 민 사람이 다음 틱에
			// 뒷자락 앞으로 나가 같은 고리에 두 번 맞는다.
			CROSSED.add(memberId);
			if (jumpedThrough(memberId, now)) {
				dodged(end, member);
				continue;
			}
			strike(end, member, center, risk);
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
	 * 닿는다. 그리고 <b>사람마다 그 자리에서</b> 울린다 — 한 점에서 울리면 16칸 밖에는 안 들린다.
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
			TrialWarning.sound(end, at, stage);
		}
	}

	/**
	 * 뛰어서 넘겼다. 아무 일도 일어나지 않는다.
	 *
	 * <p>그래도 신호는 준다 — <b>「지금 그 판단이 맞았다」</b>를 돌려주지 않으면 사람은 자기가
	 * 뛴 덕분인지 고리가 안 왔던 것인지 배울 수 없다. 피해도 넉백도 없으므로 발밑의 작은 바람
	 * 한 번이면 된다.
	 */
	private static void dodged(ServerLevel end, ServerPlayer member) {
		Vec3 at = member.position();
		end.sendParticles(ParticleTypes.SMALL_GUST, true, false, at.x, at.y, at.z, 2,
				0.2, 0.1, 0.2, 0.0);
	}

	/**
	 * 고리에 맞았다. 피해와 바깥 넉백이 함께 들어간다.
	 *
	 * <p>피해원에 <b>가해 개체를 달지 않는다.</b> 실체가 붙은 피해원이면 {@code LivingEntity} 가
	 * 스스로 밀어내는데, 그 밀기는 우리가 정한 방향도 거리도 아니다 — 클래스 설명의 「23칸」이
	 * 그 한 줄로 깨진다. 넉백은 {@link #push} 하나만 준다. 폭발 피해형이라 폭발 보호는 그대로
	 * 들으므로 대비한 사람이 손해 보지 않는다.
	 *
	 * <p>블록은 한 칸도 건드리지 않고 불도 붙이지 않는다.
	 */
	private static void strike(ServerLevel end, ServerPlayer member, Vec3 center,
			TrialCatalog.Risk.LandingShock risk) {
		Vec3 at = member.position();
		member.hurtServer(end, end.damageSources().explosion(null, null), risk.damage());
		push(member, center, risk);
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
	 * <p>그래서 실제로 밀리는 거리는 적힌 8칸보다 <b>훨씬 짧다</b>({@link #pushVelocity} 참고).
	 * 어긋나는 방향이 이 카드에서는 중요하다 — <b>반드시 덜 미는 쪽으로만</b> 어긋나야 한다.
	 *
	 * <p>{@code syncVelocity} 를 켜지 않으면 서버 혼자 민 것이 되어 잠시 뒤 제자리로 되돌아간다
	 * ({@code TrialRisks.launch} 와 같은 이유). 세로 속도는 건드리지 않고 그대로 둔다.
	 */
	private static void push(ServerPlayer member, Vec3 center,
			TrialCatalog.Risk.LandingShock risk) {
		double dx = member.getX() - center.x;
		double dz = member.getZ() - center.z;
		double from = Math.sqrt(dx * dx + dz * dz);
		if (!(from > PUSH_MIN_REACH)) {
			// 중심에 정확히 겹쳐 있다. 「바깥쪽」이 없으므로 밀지 않는다.
			return;
		}
		double distance = pushDistance(from, risk.knockback(),
				outwardLimit(risk.maxRadius(), risk.knockback()));
		if (!(distance > 0.0)) {
			return;
		}
		double speed = pushVelocity(distance);
		Vec3 motion = member.getDeltaMovement();
		member.setDeltaMovement(dx / from * speed, motion.y, dz / from * speed);
		member.syncVelocity = true;
	}

	// ------------------------------------------------------------------ 그리기

	/**
	 * 고리를 지금 반경으로 그린다.
	 *
	 * <h2>먼지 하나로는 고리가 안 된다 — 수명 때문이다</h2>
	 *
	 * <p>26.3 {@code DustParticleBase} 의 수명은 {@code max(1, 8.0 / (굴림×0.8 + 0.2))} 라
	 * <b>8~40틱</b>이다. 이 고리는 틱당 0.375칸으로 나아가므로, 파랑 먼지만으로 그리면 지나간
	 * 자국이 3~15칸 남아 <b>「고리」가 아니라 「퍼지는 원판」</b>으로 보인다. 앞머리가 어디인지
	 * 안 읽히면 언제 뛰어야 하는지도 안 읽힌다.
	 *
	 * <p>그래서 「엔더 파동」과 <b>같은 문법</b>으로 두 벌을 겹쳐 찍는다. 앞머리는 수명 4~10틱짜리
	 * {@code CRIT}(흰 불티)이라 자국이 1.5~3.75칸에서 끝나 <b>선으로 남고</b>, 몸통은 파랑 먼지가
	 * 지나간 자리를 채운다. 두 카드의 고리가 같은 모양으로 읽혀야 사람이 「고리는 뛰면 피한다」를
	 * 한 번만 배운다.
	 *
	 * <h2>몸통이 파랑인 것이 이 카드의 이름표다</h2>
	 *
	 * <p>색은 {@link #markColor} 가 고른다 — <b>파랑, 「밀려난다」</b>. 왜 그 색인지는 그쪽에
	 * 적어 두었다. 「엔더 파동」이 파랑을 피하고 엔더 입자를 쓴 것은 그 카드에 <b>넉백이 없어서</b>
	 * 이고, 이 카드는 넉백이 본체라 파랑이 정확히 제 뜻이다. 그러니 두 고리는 <b>앞머리가 같고
	 * 몸통 색이 다르다</b> — 문법은 하나, 뜻은 둘이다.
	 *
	 * <p>먼지 수명의 하한 8틱이 판정 창 {@link #JUMP_WINDOW_TICKS}(5틱)보다 길다. 그래서
	 * <b>판정이 내려지는 뒷자락까지는 반드시 파랑으로 덮여 있다</b> — 맞는 자리가 안 그려진 채로
	 * 맞는 일이 없다.
	 *
	 * <p>점 수도 「엔더 파동」의 것을 그대로 부른다. 반경 15 에서 앞머리 189점·몸통 160점으로
	 * 합쳐 349점이라 한 틱 예산(400~440) 안이고, 앞머리는 간격 0.5칸을 온전히 지킨다.
	 */
	private static void draw(ServerLevel end, Vec3 center, double radius) {
		if (!(radius > 0.0)) {
			// 출발 틱에는 그릴 것이 없다. 중앙 한 점에 수백 발을 쏘아 봐야 덩어리 하나다.
			return;
		}
		double y = groundY(end, center, radius) + GROUND_OFFSET;
		ring(end, ParticleTypes.CRIT, center, y, radius, TrialEnderPulse.edgePoints(radius));
		ring(end, TrialWarning.dust(markColor()), center, y, radius,
				TrialEnderPulse.wakePoints(radius));
	}

	/**
	 * 중심을 도는 점들을 찍는다.
	 *
	 * <p><b>첫 {@code boolean} 을 {@code false} 로 되돌리지 말 것.</b> 짧은 형태는 서버에서
	 * 32칸으로 잘리고 클라이언트가 한 번 더 거른다({@link TrialWarning} 의 「거리 제한을 끄고
	 * 보낸다」). 이 고리는 지름으로 30칸이 넘고 보는 사람은 그 바깥에 있을 수 있어, 되돌리면
	 * 반대편에 선 팀원에게는 <b>아무 일도 안 일어나는 것</b>으로 보인다.
	 */
	private static void ring(ServerLevel end, ParticleOptions type, Vec3 center, double y,
			double radius, int points) {
		for (int index = 0; index < points; index++) {
			double angle = (Math.PI * 2.0 * index) / points;
			end.sendParticles(type, true, false,
					center.x + Math.cos(angle) * radius, y, center.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 고리를 얹을 높이. <b>매 틱 고리 위의 한 점</b>만 잰다.
	 *
	 * <p>중앙에서 한 번만 재면 안 된다. 이 고리의 중심은 아레나 바닥이 아니라 <b>포탈 꼭대기</b>라,
	 * 그 높이를 한 바퀴에 쓰면 고리가 포탈 단을 벗어난 뒤로 섬 위를 떠서 지나간다. 반대로 점마다
	 * 재면 한 틱에 하이트맵을 삼백 번 넘게 두드린다.
	 *
	 * <p>그래서 한 점만 잰다. 중앙 섬은 평평해서 한 바퀴가 같은 높이이고, 값이 실제로 바뀌는 것은
	 * <b>포탈 단을 내려오는 몇 틱</b>뿐이다. 그 한 점이 허공이면 중심 높이를 쓴다 — 고리에 구멍을
	 * 뚫는 것보다 낫다.
	 */
	private static double groundY(ServerLevel end, Vec3 center, double radius) {
		BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				BlockPos.containing(center.x + radius, 0.0, center.z));
		return ground.getY() > end.getMinY() ? ground.getY() : center.y;
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
	 * ⚠ 이 카드가 사람을 밀어 놓을 수 있는 <b>중앙에서의 가장 먼 거리.</b>
	 *
	 * <p>클래스 설명의 {@code 15 + 8 = 23} 을 산수가 아니라 <b>코드로</b> 적은 것이다. 산수는
	 * 값을 고치는 사람이 안 볼 수 있지만 이 함수는 못 피한다. 여기에 걸리면 넉백이 그냥 짧아진다 —
	 * 밀려서 허공으로 나가는 것보다 덜 밀리는 쪽이 언제나 싸다.
	 *
	 * <p>{@code ARENA_RADIUS} 를 함께 물리는 것은 다음에 값을 만질 사람 몫이다. 누가 반경이나
	 * 넉백을 올려 둘의 합이 섬을 넘기면, 그때도 이 함수가 섬 경계에서 잘라 준다 — 다만 그때는
	 * 카드가 이미 「섬 안에 머문다」는 제 근거를 잃은 상태이므로 <b>여기에 기대지 말고 값을
	 * 되돌릴 것.</b>
	 */
	static double outwardLimit(double maxRadius, double knockback) {
		double reach = Math.max(0.0, maxRadius) + Math.max(0.0, knockback);
		return Math.min(TrialRisks.ARENA_RADIUS, reach);
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
	 * 8칸이면 0.72 칸/틱.
	 *
	 * <p>바닥 모델(마찰 0.546)로 잡으면 안 된다. 같은 8칸을 맞추려면 3.6 칸/틱을 실어야 하는데,
	 * 그 속도로 <b>한 틱이라도 떠 있으면 40칸을 날아</b> 섬 밖 허공이다 — 경사 한 칸, 다른 카드의
	 * 띄우기 한 번이면 그 일이 일어난다. 공중 모델은 반대다. 어느 상황에서도 적힌 거리를
	 * <b>넘을 수 없고</b>, 바닥에 붙어 있으면 마찰이 먼저 먹어 5분의 1 남짓만 밀린다.
	 *
	 * <p>그래서 「최대 8칸」은 말 그대로 <b>천장</b>이고, 이 카드가 허용된 예외인 근거
	 * ({@code 15 + 8 < 40})는 언제나 안전한 쪽으로 어긋난다.
	 */
	static double pushVelocity(double distance) {
		return Math.max(0.0, distance) * (1.0 - AIR_DRAG);
	}

	/**
	 * 한 고리가 팀에 넣을 수 있는 <b>가장 큰 합계 피해.</b>
	 *
	 * <p>공유 체력에서 범위 피해는 팀원별로 그대로 합산된다. 이 카드는 전원을 때리므로 넷이 다
	 * 실패하면 {@code damage × 4} 가 거의 같은 틱에 들어간다 — 그 값이 팀 체력 20 을 넘는지를
	 * 카드 값에서 직접 세어 두는 자리다.
	 *
	 * <p>고리는 한 사람을 한 번만 지나가므로({@link #CROSSED}) 사람당 몫은 언제나 {@code damage}
	 * 하나다. {@code TrialRisks.worstCaseTickDamage} 가 이 위험을 {@code hits(damage, 1)} 로
	 * 세는 것이 그 이야기다 — 그쪽은 <b>한 사람</b>이 받는 값이고 이쪽은 <b>팀 합계</b>다.
	 */
	static float worstCaseTeamDamage(float damage, int members) {
		if (damage <= 0.0F || members <= 0) {
			return 0.0F;
		}
		return damage * members;
	}

	/**
	 * 한 틱에 이 고리가 쓰는 점 수. 「예산 안인가」를 숫자로 묻는 값이다.
	 *
	 * <p>앞머리와 몸통을 더한다. 숫자를 따로 박지 않고 「엔더 파동」의 두 함수에서 뽑으므로,
	 * 그쪽 상한이 바뀌면 이 값도 따라온다.
	 */
	static int markPoints(double radius) {
		return TrialEnderPulse.edgePoints(radius) + TrialEnderPulse.wakePoints(radius);
	}

	/** 위에서 본 거리. 고리는 바닥에 그려지므로 높이를 묻지 않는다. */
	static double flatDistance(Vec3 one, Vec3 other) {
		double dx = one.x - other.x;
		double dz = one.z - other.z;
		return Math.sqrt(dx * dx + dz * dz);
	}
}
