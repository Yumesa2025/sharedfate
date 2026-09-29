package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.EnderPulse} 실행기 — 엔더 파동.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p>20초({@code interval})마다 중앙에서 바닥 고리가 하나 출발해 4초({@code travelTicks})에 걸쳐
 * 42칸({@code maxRadius})까지 퍼진다. 고리가 지나갈 때
 *
 * <ul>
 *   <li><b>바닥을 딛고 있으면</b> 구속 III 가 3초({@code rootTicks}) 붙는다</li>
 *   <li><b>점프해 있으면 통과</b>한다 — 이 카드가 요구하는 행동이 그것 하나다</li>
 * </ul>
 *
 * <p><b>피해는 없다.</b> 이 카드가 빼앗는 것은 체력이 아니라 발이다.
 *
 * <h2>「점프해 있다」를 한 틱으로 묻지 않는다</h2>
 *
 * <p>고리가 어떤 사람의 자리를 지나가는 것은 <b>한 틱</b>이다. 그 한 틱에 {@code onGround()} 만
 * 물으면 0.05초 차이로 억울하게 걸린다 — 사람은 눈으로 본 것에 반응해 스페이스를 누르고, 그
 * 입력이 서버의 {@code onGround} 를 뒤집기까지 또 한 왕복이 걸린다.
 *
 * <p>그래서 고리에 <b>두께</b>를 준다. 앞머리가 내 자리를 지난 뒤 {@link #JUMP_WINDOW_TICKS}
 * 틱 동안이 「고리가 내 위에 있는 시간」이고, <b>그 사이 한 틱이라도 공중에 있었으면 통과</b>다.
 * 판정은 그 창이 닫히는 틱, 즉 고리의 <b>뒷자락</b>이 나를 지나는 틱에 내린다.
 *
 * <p>창이 <b>뒤로만</b> 열려 있는 것은 일부러다. 늦게 뛰는 것은 이 창이 봐 주지만 너무 일찍 뛴
 * 것은 봐 주지 않는데, 바닐라 점프는 그 자체로 열두 틱쯤 공중에 떠 있어 <b>앞쪽 여유는 점프가
 * 이미 들고 있기</b> 때문이다. 이 창이 메우는 것은 「보고 나서 누르기까지」 한쪽뿐이다.
 *
 * <h2>구속은 팀 전체에 걸린다 — 의도한 것이다</h2>
 *
 * <p>{@code shareStatusEffects} 가 켜져 있어 {@link EffectSync} 가 한 사람에게 붙은 상태이상을
 * 팀원 전원에게 그대로 옮긴다. 즉 <b>한 명이 못 뛰면 넷이 다 묶인다.</b> 사람이 「의도한 거임」
 * 이라고 확인한 동작이므로 <b>되돌리지 말 것.</b>
 *
 * <p>고리는 사람마다 <b>다른 틱에</b> 닿는다(중앙에서 먼 사람일수록 늦다). 그래서 먼저 걸린
 * 사람 때문에 아직 고리가 오지 않은 사람도 미리 묶인다. 그것이 이 카드가 공유 체력 게임에서
 * 갖는 긴장이다 — 각자 제 순간에 뛰어야 하고, 한 사람의 실패가 팀 전체의 실패다.
 *
 * <h2>왜 규약 색을 쓰지 않고 엔더 입자를 쓰는가</h2>
 *
 * <p>{@link TrialWarning.Colors} 의 넷(빨강=서 있으면 죽는다, 노랑=번개, 파랑=밀려난다,
 * 보라=너 하나를 노린다)은 어느 것도 이 카드의 뜻이 아니다. 여기에 다섯째 색을 더하면 그 순간
 * 규약이 규약이 아니게 되므로({@code Colors} 의 설명) <b>먼지 표식 자체를 쓰지 않는다.</b>
 * 규약은 먼지 고리의 규약이고, 엔더 입자는 그 바깥이라 색을 하나도 빌려 쓰지 않는다.
 *
 * <p>고른 것은 두 가지다. <b>{@code CRIT} 이 앞머리, {@code PORTAL} 이 몸통</b>이다. 26.3 의
 * 클라이언트 입자를 뜯어 보고 갈랐다.
 *
 * <ul>
 *   <li>{@code PortalParticle} — 수명 <b>40~49틱</b>이고 {@code getQuadSize} 가 나이에 따라
 *       <b>0 에서 자란다.</b> 갓 찍은 점이 가장 작다는 뜻이라, 이것만으로 그리면 <b>앞머리가 가장
 *       흐리다.</b> 대신 남아서 커지므로 <b>지나간 자리</b>를 보라색으로 채운다 — 고리 안쪽은 이미
 *       지나간 땅이라 「지금 어디가 위험한가」를 흐리지 않고, 파동이 어디서 와서 어디로 가는지를
 *       알려 준다</li>
 *   <li>{@code CritParticle} — 수명이 {@code max(1, 6.0 / (굴림×0.8 + 0.6))} 이라 <b>4~10틱</b>
 *       이다. 카드 값에서 고리 속도가 틱당 {@code 42/80 = 0.525칸}이므로 자국이 2~5칸에서 끝나
 *       <b>앞머리가 선으로 남는다.</b> 흰 불티라 규약의 네 색 어디에도 닿지 않는다</li>
 * </ul>
 *
 * <p>파랑 계열({@code ENCHANTED_HIT})을 쓰지 않은 이유를 남긴다. 대비는 그쪽이 낫지만
 * <b>파랑은 「밀려난다」로 이미 배워져 있고 이 카드에는 넉백이 없다.</b> 먼지가 아니니 규약 위반은
 * 아니어도, 색이 거짓말을 하면 규약을 지킨 보람이 없다.
 *
 * <h2>넉백을 주지 않는다</h2>
 *
 * <p>엔드 중앙 섬은 사방이 허공이고 체력이 팀 공유라 <b>한 사람의 낙사가 팀 전체를 끝낸다.</b>
 * 이 카드는 아무것도 밀지 않는다 — 미는 코드를 한 줄도 두지 않는 것이 그 장치다.
 *
 * <h2>⚠ 고리는 지형을 탄다 — 그리는 것도 판정도 함께</h2>
 *
 * <p>전에는 고리를 <b>평평한 한 높이</b>로 그렸다. 중앙에서 지표를 한 번 재고 그 값을 한 바퀴에
 * 다 썼는데, 중앙 섬은 평평하지 않고 사람이 발판을 쌓기도 해서 <b>높이가 달라지는 자리에서
 * 고리가 땅에 파묻히거나 공중에 떴다.</b> 사람이 직접 고쳐 달라고 한 자리다.
 *
 * <p>그래서 이제 <b>점마다 그 자리의 지표를 묻는다</b>({@link Ground}). 고리가 지나는 칸의
 * 가장 높은 블록 위에 점이 찍히므로 기둥이든 쌓아 올린 발판이든 고리가 그 위를 넘어간다.
 *
 * <p><b>판정도 같이 따라간다.</b> 보이는 고리가 지형을 타는데 「바닥을 딛고 있는가」가 평평한
 * 높이로 남으면, <b>지붕 밑에 선 사람은 머리 위로 지나간 고리에 묶이고</b> 사람이 본 것과 맞은
 * 것이 갈라진다. {@link #atRingHeight} 가 「내 발밑을 지나갔는가」를 물어 그 어긋남을 막는다.
 *
 * <p>비싸지지 않게 하는 장치가 셋이다. 한 틱에 사백 점을 찍으므로 점마다 월드에 묻는 것은
 * 그대로 두면 비싸다.
 *
 * <ul>
 *   <li>{@link Ground} 가 <b>직전에 본 청크를 기억한다.</b> 고리는 한 바퀴를 이어 도므로 이웃한
 *       점은 거의 같은 청크다 — 청크를 새로 찾는 것은 경계를 넘을 때뿐이다</li>
 *   <li>청크는 {@code getChunkNow} 로만 본다. <b>없으면 그 점을 건너뛴다</b> — 고리를 그리자고
 *       청크를 불러오는 것이 이 카드에서 가장 비싼 일이다</li>
 *   <li>하이트맵은 {@code ServerLevel} 이 아니라 <b>청크에서 바로</b> 읽는다. 월드 쪽
 *       {@code getHeightmapPos} 는 부를 때마다 청크를 다시 찾고 {@code BlockPos} 를 하나씩
 *       만든다 — 점마다 부르면 그 둘이 사백 배가 된다</li>
 * </ul>
 *
 * <p><b>허공에는 점을 찍지 않는다.</b> 블록이 하나도 없는 칸에서는 하이트맵이 월드 바닥을
 * 돌려주는데, 거기에 찍으면 고리가 발밑이 아니라 까마득한 아래에 뜬다. 안 찍으면 섬이 끝나는
 * 자리에서 고리도 함께 끊겨 <b>「여기서부터 땅이 없다」가 그대로 읽힌다.</b>
 */
public final class TrialEnderPulse {

	// ------------------------------------------------------------------ 값

	/**
	 * 앞머리가 지나간 뒤 「뛰었다」로 쳐 주는 시간(틱). 고리 두께이기도 하다.
	 *
	 * <p>{@link TrialWarning#TICKS_SIDESTEP} 의 설명이 「사람의 지각·판단·입력에만 0.25초가
	 * 든다」고 적어 둔 그 0.25초다. 이 카드가 요구하는 것은 <b>옆걸음이 아니라 점프 한 번</b>이라
	 * 고를 자리도 갈 거리도 없다 — 남는 것이 딱 그 반응 시간뿐이라 여기를 창의 길이로 삼는다.
	 *
	 * <p>여기를 0 으로 만들면 판정이 다시 한 틱짜리가 되고, 사람이 눈으로 맞춘 점프가 서버에
	 * 닿기 전에 묶인다. 키우면 고리가 두꺼워져 「고리를 보고 나서」 뛰어도 통과하게 된다.
	 */
	static final int JUMP_WINDOW_TICKS = 5;

	/**
	 * 구속 III 의 증폭값. 레벨 I 이 0 이므로 III 은 2 다.
	 *
	 * <p>카드에 적힌 「구속 III」을 코드로 옮긴 것뿐이다. 세기를 바꾸려면 카드 값을 늘려
	 * {@code TrialCatalog} 에서 받아야 한다 — 여기를 올리면 카드 설명과 실제가 갈라진다.
	 */
	static final int ROOT_AMPLIFIER = 2;

	/**
	 * 앞머리 고리 한 바퀴에 찍는 점 수의 상한.
	 *
	 * <p>이 카드가 <b>반드시 읽혀야 하는 한 줄</b>이라 예산의 큰 쪽을 준다. 반경 42 에서
	 * 둘레가 264칸이므로 점 사이가 {@code 264 / 240 = 1.1칸}이다 — {@link TrialWarning#ringPoints}
	 * 가 상한 80 에 걸려 같은 반경에서 3.3칸까지 벌어지는 것과 견주면 세 배 촘촘하다.
	 * {@link #edgeGap} 으로 그 값을 직접 물을 수 있다.
	 */
	static final int EDGE_MAX_POINTS = 240;
	/**
	 * 몸통 고리 한 바퀴에 찍는 점 수의 상한.
	 *
	 * <p>이쪽은 <b>남아서 쌓인다.</b> 화면에 살아 있는 수가 {@code 이 값 × 수명}이라, 앞머리와
	 * 같은 240 을 주면 만 점에 닿는다. 몸통은 경계를 말하는 줄이 아니라 지나간 자리를 채우는
	 * 안개라 성겨도 제 몫을 한다.
	 *
	 * <p>⚠ <b>수명은 40~49틱이 아니다.</b> 여기에 그렇게 적혀 있었는데 그것은
	 * {@code PORTAL} 의 값이고, 몸통이 쓰는 것은 먼지다. 26.3 {@code DustParticleBase} 의
	 * 수명은 {@code max(1, (int)(8.0 / (random.nextDouble() * 0.8 + 0.2)) * scale)} 이라
	 * {@code scale} 이 1.0 이면 <b>8~40틱</b>이다({@code TrialWarning.markGround} 의 같은 계산).
	 * 그은 선 자체는 그대로 맞다 — 240 × 40 = 9600 이라 여전히 만 점 언저리이고, 160 이면
	 * 6400 이다.
	 */
	static final int WAKE_MAX_POINTS = 160;
	/**
	 * 한 틱에 이 카드가 쓰는 점 수의 <b>상한</b>.
	 *
	 * <p>숫자를 따로 박지 않고 위 둘에서 뽑는다 — 한쪽만 고치면 예산이 조용히 깨진다. 400 은
	 * 이 저장소가 이미 쓰는 예산이다({@code DragonFireBarrage.MARK_MAX_POINTS} 의 설명 —
	 * 「낙뢰」가 반경 3 짜리 고리 열 개로 정확히 400점을 쓴다).
	 *
	 * <p>실제로 나가는 수는 이보다 <b>적다.</b> 고리가 지형을 타면서 허공에 걸린 점을 건너뛰기
	 * 때문이다 — 섬이 끊긴 자리에서는 고리가 함께 끊긴다.
	 */
	static final int MAX_POINTS_PER_TICK = EDGE_MAX_POINTS + WAKE_MAX_POINTS;

	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다({@code TrialWarning} 과 같은 이유). */
	private static final double GROUND_OFFSET = 0.15;

	/**
	 * 「그 자리에는 설 땅이 없다」. {@link Ground#surfaceAt} 이 돌려주는 값이다.
	 *
	 * <p>두 경우를 한 값으로 묶는다 — <b>청크가 아직 안 올라왔거나</b>, 올라왔는데 그 칸이
	 * <b>바닥까지 허공</b>이거나. 둘 다 이 카드가 할 일은 같다(점을 안 찍고 판정도 안 한다)라
	 * 갈래를 늘려 봐야 부르는 쪽이 같은 줄을 두 번 적게 된다.
	 *
	 * <p>{@code Integer.MIN_VALUE} 인 것은 <b>진짜 높이일 수 없는 수</b>라서다. 월드 바닥
	 * ({@code getMinY}) 을 표시값으로 쓰면 바닥에 실제로 블록이 있는 판에서 구별되지 않는다.
	 */
	static final int NO_GROUND = Integer.MIN_VALUE;
	/**
	 * 판정이 지표에서 봐 주는 <b>세로 여유</b>(블록).
	 *
	 * <p>고리는 칸마다 그 자리의 지표 위에 그려진다. 그러니 「고리가 나를 지나갔다」는 내 발이
	 * <b>그 지표 근처에 있었다</b>는 뜻이고, 이 값이 그 「근처」다.
	 *
	 * <p>2 인 근거. 아래쪽으로는 반 블록짜리 발판(하프 블록·계단)에 서면 하이트맵이 한 칸 위를
	 * 돌려주므로 <b>1 블록쯤은 반드시 봐 줘야 한다</b>. 위쪽으로는 점프가 1.25 블록까지 뜨는데,
	 * 뜬 사람은 어차피 {@link #JUMP_WINDOW_TICKS} 창이 먼저 통과시키므로 여기서 더 볼 것이 없다.
	 * 그 둘을 다 덮는 가장 작은 정수가 2 다.
	 *
	 * <p>여기를 키우면 <b>지붕 밑에 선 사람이 머리 위로 지나간 고리에 다시 묶인다</b> — 고친
	 * 어긋남이 그대로 돌아온다. 줄이면 반 블록 발판 위에 선 사람이 눈앞으로 지나가는 고리를
	 * 그냥 통과시킨다.
	 */
	static final double JUDGE_VERTICAL_REACH = 2.0;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 퍼지고 있는 고리들.
	 *
	 * <p><b>열쇠에 주의.</b> {@link TrialFireball} 과 같이 「받은 틱 + 위험 값」으로 만든 내부
	 * 열쇠다. 이 위험을 가진 카드가 하나뿐이라 충분하지만, 값이 완전히 같은 파동 둘을 한 카드에
	 * 걸면 두 고리가 하나로 합쳐진다.
	 *
	 * <p>✅ <b>진입점이 이제 {@code key} 를 받는다.</b> 그 구멍을 막으려면 여기를 그 열쇠로
	 * 바꾸면 된다 — {@link TrialFireball} 에 같은 글을 남겨 두었다.
	 */
	private static final Map<Key, Pulse> PULSES = new HashMap<>();
	/**
	 * 사람마다 <b>마지막으로 공중에 있던 틱.</b>
	 *
	 * <p>고리가 없는 동안에도 계속 적는다. 판정 틱에만 보면 그 한 틱의 {@code onGround()} 를
	 * 묻는 것과 같아져 창이 아무 일도 하지 않는다.
	 *
	 * <p>정적 맵인 이유는 위험이 값(레코드)이라 상태를 들 수 없기 때문이다. 월드가 바뀌면 지난
	 * 판의 기록이 새 판의 첫 고리를 통과시키므로 {@link #clearState()} 로 반드시 비운다.
	 */
	private static final Map<UUID, Long> LAST_AIRBORNE = new HashMap<>();

	private record Key(long granted, TrialCatalog.Risk.EnderPulse risk) {
	}

	/**
	 * 퍼지고 있는 고리 하나.
	 *
	 * <p>높이를 들지 않는다. 전에는 출발할 때 중앙에서 한 번 재어 여기에 담아 두었는데, 이제
	 * 고리가 <b>칸마다 그 자리의 지표</b>를 따라가므로 파동 전체가 공유할 높이라는 것이 없다.
	 *
	 * @param index   몇 번째 파동인가. 주기가 넘어갔는지 판단한다
	 * @param crossed 이미 고리가 지나간 사람들. <b>이 집합은 고쳐 쓴다.</b> 고리가 한 사람을 두
	 *                번 지나가지 않게 막는 것이 전부인데, 안 막으면 고리 끝에 붙어 바깥으로 달리는
	 *                사람이 같은 파동에 두 번 묶인다
	 */
	private record Pulse(long index, Set<UUID> crossed) {
	}

	private TrialEnderPulse() {
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자({@code dragon})도 받는 것은
	 * {@link TrialRisks} 의 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <p>{@code now} 를 받아 쓰고 {@code level.getGameTime()} 을 부르지 않는다. 룰렛이 도는
	 * 동안 {@code ServerTickRateManager} 가 판을 멈추면 게임 시각도 멈추는데, 그때 직접 물으면
	 * 고리가 얼어붙은 채로 남는다.
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
			@Nullable TrialCatalog.Risk.EnderPulse risk) {
		if (end == null || members == null || members.isEmpty() || risk == null) {
			return;
		}
		int interval = risk.interval();
		int travelTicks = risk.travelTicks();
		if (interval <= 0 || travelTicks <= 0 || !(risk.maxRadius() > 0.0)) {
			return;
		}

		// 고리가 없는 동안에도 적는다. 판정 창이 과거를 보므로 기록이 끊기면 창이 비어 버린다.
		recordAirborne(members, now);

		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		// 받은 직후 한 주기는 온전히 비워 둔다 — 카드 설명을 읽는 중에 묶이면 안 된다.
		if (elapsed < interval) {
			return;
		}
		// 위상은 월드 시간이 아니라 받은 틱에서 센다. now % interval 로 세면 주기가 같은 카드가
		// 전부 같은 틱에 몰려 터진다.
		int step = (int) (elapsed % interval);
		Key pulseKey = new Key(granted, risk);
		if (step > lifetime(interval, travelTicks)) {
			// 뒷자락까지 다 지나갔다. 들고 있으면 다음 파동이 지난 파동의 명단을 물려받는다.
			PULSES.remove(pulseKey);
			return;
		}

		long index = elapsed / interval;
		Pulse pulse = PULSES.get(pulseKey);
		if (pulse == null || pulse.index() != index) {
			pulse = new Pulse(index, new HashSet<>());
			PULSES.put(pulseKey, pulse);
		}

		// 지표를 묻는 자리가 이 틱에 둘(그리기·판정)이라 기억을 하나만 만들어 함께 쓴다.
		// 나눠 들면 판정이 그리기가 이미 찾아 둔 청크를 다시 찾는다.
		Ground ground = new Ground();
		if (step <= travelTicks) {
			draw(end, ground, radiusAt(step, travelTicks, risk.maxRadius()));
		}
		warn(end, members, step, risk);
		judge(end, ground, members, pulse, step, now, risk);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. 사람에게 붙은 것이 다음 판으로 새지 않게 한다.
	 *
	 * <p>거는 구속은 지속시간이 있어 저절로 풀리므로 걷어낼 것이 없다. 비워야 하는 것은 <b>지난
	 * 판의 고리와 발 기록</b>이다 — 남겨 두면 새 판의 첫 고리가 옛 명단을 보고 사람을 건너뛰거나,
	 * 지난 판에 뛰어 있던 기록이 첫 고리를 그냥 통과시킨다. 컴파일도 시험도 조용한 사고다.
	 */
	public static void clearState() {
		PULSES.clear();
		LAST_AIRBORNE.clear();
	}

	// ------------------------------------------------------------------ 발 기록

	/**
	 * 지금 공중에 있는 사람을 적어 둔다.
	 *
	 * <p>접속을 끊은 사람의 기록은 버린다. 들고 있어 봐야 다시 들어왔을 때 <b>몇 분 전에 뛴 것</b>
	 * 으로 고리를 통과하게 된다.
	 */
	private static void recordAirborne(List<ServerPlayer> members, long now) {
		Set<UUID> present = new HashSet<>();
		for (ServerPlayer member : members) {
			present.add(member.getUUID());
			if (!member.onGround()) {
				LAST_AIRBORNE.put(member.getUUID(), now);
			}
		}
		LAST_AIRBORNE.keySet().retainAll(present);
	}

	/**
	 * 이 사람이 <b>고리가 지나가는 동안</b> 한 번이라도 떠 있었는가.
	 *
	 * <p>판정은 앞머리가 지난 뒤 {@link #JUMP_WINDOW_TICKS} 틱째에 내리므로, 지금부터 그만큼
	 * 거슬러 본 구간이 곧 「고리가 내 위에 있던 시간」이다.
	 *
	 * <p>기록이 아예 없으면 걸린 것으로 본다. 이 카드가 붙은 뒤로 한 번도 안 뛰었다는 뜻이다.
	 */
	private static boolean jumpedThrough(UUID memberId, long now) {
		Long last = LAST_AIRBORNE.get(memberId);
		return last != null && now - last <= JUMP_WINDOW_TICKS;
	}

	// ------------------------------------------------------------------ 판정

	/**
	 * 고리 뒷자락이 지나간 사람을 가른다.
	 *
	 * <p>경계를 <b>{@code (지난 틱 반경, 이번 틱 반경]}</b> 반열린 구간으로 잡는다. 고리는 한 틱에
	 * 0.5칸씩 건너뛰므로 「반경과 거리가 같은가」로 물으면 대부분의 사람이 <b>그냥 건너뛰어진다.</b>
	 * 구간으로 물으면 0 부터 {@code maxRadius} 까지 어느 거리든 정확히 한 번 덮인다.
	 *
	 * <p>피해는 없고 넉백도 없다. 여기서 사람에게 하는 일은 구속을 거는 것 하나뿐이다.
	 *
	 * <p><b>거리만으로는 부족하다.</b> 고리가 지형을 타므로 같은 거리라도 내 발밑을 지나갔는지는
	 * 높이를 봐야 안다({@link #atRingHeight}). 그 검사가 없으면 지붕 밑이나 굴 속에 선 사람이
	 * <b>머리 위로 지나간 고리</b>에 묶인다.
	 */
	private static void judge(ServerLevel end, Ground ground, List<ServerPlayer> members,
			Pulse pulse, int step, long now, TrialCatalog.Risk.EnderPulse risk) {
		double outer = judgeRadius(step, risk.travelTicks(), risk.maxRadius());
		double inner = judgeRadius(step - 1, risk.travelTicks(), risk.maxRadius());
		for (ServerPlayer member : members) {
			UUID memberId = member.getUUID();
			if (pulse.crossed().contains(memberId)) {
				continue;
			}
			Vec3 at = member.position();
			double distance = distanceFromCenter(at);
			if (!(distance > inner) || !(distance <= outer)) {
				continue;
			}
			// 지나간 것은 뛰었든 아니든 지나간 것이다. 안 적으면 고리 끝에 붙어 바깥으로 달리는
			// 사람이 같은 파동에 다시 걸린다. 높이로 빠진 사람도 마찬가지로 적는다 — 고리는
			// 그 사람의 칸을 이미 지나갔고, 안 적으면 지붕 밑에서 나오는 순간 다시 걸린다.
			pulse.crossed().add(memberId);
			if (!atRingHeight(at.y, ground.surfaceAt(end, at.x, at.z))) {
				continue;
			}
			if (jumpedThrough(memberId, now)) {
				continue;
			}
			root(end, member, risk.rootTicks());
		}
	}

	/**
	 * 발을 묶는다.
	 *
	 * <p>이 한 줄이 <b>팀 전원에게 퍼진다.</b> {@link EffectSync} 가 {@code shareStatusEffects}
	 * 를 보고 옮기는 것이고 <b>의도한 동작</b>이다(클래스 설명). 그래서 걸린 사람마다 따로 부르는
	 * 것이 낭비처럼 보이지만, 세기와 길이가 같으니 뒤에 걸린 사람은 남은 시간을 새로 채울 뿐이다 —
	 * 자기 순간에 실패했다는 사실이 그 사람의 화면에도 나타나야 한다.
	 *
	 * <p>{@code visible} 과 {@code showIcon} 을 켠다. 자막을 전부 걷어낸 판이라 <b>「내가 묶였다」를
	 * 말하는 것이 입자와 아이콘뿐</b>이다.
	 *
	 * <p>소리는 그 사람 자리에서 낸다. 바닐라 소리 사거리는 볼륨 1 이하면 16칸인데 아레나는 반경
	 * 42 라, 중앙 한 점에서 울리면 가장자리에 선 사람에게 닿지 않는다.
	 */
	private static void root(ServerLevel end, ServerPlayer member, int rootTicks) {
		if (rootTicks <= 0) {
			return;
		}
		Vec3 at = member.position();
		member.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, rootTicks, ROOT_AMPLIFIER,
				false, true, true));
		// 발밑에서 한 번 터뜨린다. 긴 형태라 나머지 셋도 「저 사람이 걸렸다」를 본다 — 구속이
		// 어차피 넷에게 다 오므로 누가 못 뛰었는지가 보여야 다음 고리에 쓸모가 있다.
		end.sendParticles(ParticleTypes.PORTAL, true, false, at.x, at.y + 0.1, at.z, 24,
				0.35, 0.05, 0.35, 0.0);
		end.playSound(null, at.x, at.y, at.z, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE,
				1.0F, 0.6F);
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 고리가 <b>나에게</b> 닿기까지 남은 시간으로 경고 층을 올린다.
	 *
	 * <p>층과 소리는 {@link TrialWarning} 의 것을 그대로 쓴다. 사람이 다른 카드에서 이미 배운
	 * 신호라 새로 배울 것이 없고, 층이 언제 바뀌는지도 저장소가 한 곳에서 정한다.
	 *
	 * <p>남은 시간이 <b>사람마다 다르다</b>는 것이 이 카드의 특징이다. 고리는 중앙에서 출발하므로
	 * 가까이 선 사람에게 먼저 닿는다 — 같은 파동에 네 사람이 네 번 다른 순간에 뛰어야 한다.
	 *
	 * <p>소리는 <b>그 사람에게만</b> 간다({@link TrialWarning#soundFor}). 한 점에 놓으면 16칸 밖에는
	 * 안 들리고, 사람 자리마다 놓는 것으로는 안 된다 — 그쪽은 반경 안의 <b>전원</b>에게 나가므로
	 * 같은 거리에 선 둘이 서로의 경고까지 들어 <b>각자 두 번</b>이 되고, 「네 사람이 네 번 다른
	 * 순간에 뛴다」는 이 카드의 요점이 그 한 줄로 무너진다.
	 *
	 * <p>출발 틱은 층이 바뀌지 않았어도 무조건 한 번 울린다. 고리가 없던 직전 틱에는 층 자체가
	 * 없으니 「바뀌었다」가 참이어야 맞고, 무엇보다 <b>중앙 가까이 선 사람은 처음부터 마지막
	 * 층</b>이라 그렇지 않으면 경고를 한 번도 못 듣는다. 그 판단은
	 * {@link TrialRisks#stageJustChanged(int, int)} 에 이 사람의 예고 길이를 넘겨 맡긴다 —
	 * 「착지 충격」이 같은 규칙을 쓰므로 <b>한쪽만 고치지 말 것.</b>
	 */
	private static void warn(ServerLevel end, List<ServerPlayer> members, int step,
			TrialCatalog.Risk.EnderPulse risk) {
		for (ServerPlayer member : members) {
			Vec3 at = member.position();
			double distance = distanceFromCenter(at);
			if (distance > risk.maxRadius()) {
				// 고리가 닿지 않는 자리다. 그런데도 울리면 「경고는 들었는데 아무 일도 없다」가
				// 되고, 그 경험 하나가 다음 고리의 경고까지 무시하게 만든다.
				continue;
			}
			// 이 사람의 예고 길이. 고리가 출발한 틱(step 0)에 남아 있던 틱 수 그대로다.
			int lead = reachTick(distance, risk.travelTicks(), risk.maxRadius());
			int remaining = lead - step;
			if (remaining < 0) {
				// 이미 지나갔다. 지나간 고리가 계속 경고하면 다음 파동의 예고와 섞인다.
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

	// ------------------------------------------------------------------ 그리기

	/**
	 * 고리를 바닥에 그린다.
	 *
	 * <p>두 벌을 <b>같은 반경</b>에 겹쳐 찍는다. {@code CRIT} 은 4~10틱만 살아 앞머리를 선으로
	 * 남기고, {@code PORTAL} 은 40~49틱을 살며 자라서 지나간 자리를 채운다. 한 자리에서 나와
	 * 수명이 갈리는 것이므로 둘의 위치를 따로 계산할 이유가 없다 — 까닭은 클래스 설명에 있다.
	 *
	 * <p>반경이 0 인 출발 틱에는 그릴 것이 없다. 중앙 한 점에 400 발을 쏘아 봐야 덩어리 하나다.
	 */
	private static void draw(ServerLevel end, Ground ground, double radius) {
		if (!(radius > 0.0)) {
			return;
		}
		ring(end, ground, ParticleTypes.CRIT, radius, edgePoints(radius));
		ring(end, ground, ParticleTypes.PORTAL, radius, wakePoints(radius));
	}

	/**
	 * 중앙을 도는 점들을 <b>각자 제 자리의 지표 위에</b> 찍는다.
	 *
	 * <p><b>첫 {@code boolean} 을 {@code false} 로 되돌리지 말 것.</b> 짧은 형태는 서버에서
	 * 32칸으로 잘리고 클라이언트가 한 번 더 거른다({@link TrialWarning} 의 「거리 제한을 끄고
	 * 보낸다」). 이 고리는 반경 42 까지 가므로 되돌리는 순간 <b>바깥쪽 절반이 아무에게도 안
	 * 그려지고</b>, 피해가 없는 이 카드는 그대로 「갑자기 발이 묶이는 카드」가 된다.
	 *
	 * <p>둘째 {@code boolean}({@code alwaysShow}) 은 첫 깃발이 켜져 있으면 무의미하고, 사용자의
	 * 「파티클 줄이기」 설정을 우리가 뒤집을 이유도 없어 {@code false} 로 둔다.
	 *
	 * <p>각을 도는 순서를 뒤섞지 말 것. 이어 도니까 이웃한 두 점이 거의 같은 청크이고, 그래서
	 * {@link Ground} 의 기억이 거의 언제나 맞는다 — 순서를 흩으면 점마다 청크를 새로 찾는다.
	 */
	private static void ring(ServerLevel end, Ground ground, ParticleOptions type, double radius,
			int points) {
		for (int index = 0; index < points; index++) {
			double angle = (Math.PI * 2.0 * index) / points;
			double x = Math.cos(angle) * radius;
			double z = Math.sin(angle) * radius;
			int surface = ground.surfaceAt(end, x, z);
			if (surface == NO_GROUND) {
				// 허공이거나 아직 안 올라온 청크다. 여기에 찍으면 고리가 까마득한 아래에 떠
				// 「저기가 바닥이다」라고 거짓말을 한다.
				continue;
			}
			end.sendParticles(type, true, false, x, surface + GROUND_OFFSET, z,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	// ------------------------------------------------------------------ 지표 묻기

	/**
	 * 지표 높이를 묻되 <b>직전에 본 청크를 기억하는</b> 조회기.
	 *
	 * <p>한 틱에 하나 만들어 그 틱 안에서만 쓴다. 틱을 넘겨 들고 있으면 블록이 바뀌어도 낡은
	 * 청크를 붙들게 되고, 무엇보다 정적으로 두면 월드가 바뀔 때 비워 줄 자리가 하나 더 는다.
	 *
	 * <p>기억이 <b>한 칸</b>뿐인 것은 고리를 이어 돌기 때문이다. 이웃한 두 점은 거의 같은 청크라
	 * 한 칸으로도 거의 다 맞고, 여러 칸을 두면 그 자체를 뒤지는 값이 하이트맵 한 번보다 비싸진다.
	 */
	static final class Ground {

		private @Nullable LevelChunk chunk;
		/** 기억하고 있는 청크 좌표. 시작값은 <b>있을 수 없는 좌표</b>라 첫 물음이 반드시 빗나간다. */
		private int chunkX = Integer.MIN_VALUE;
		private int chunkZ = Integer.MIN_VALUE;

		/**
		 * 그 칸에서 <b>설 수 있는 높이</b>. 곧 가장 높은 블록의 윗면이다.
		 *
		 * <p>{@code MOTION_BLOCKING_NO_LEAVES} 를 쓰는 것은 이 저장소의 다른 실행기와 같다 —
		 * 지나갈 수 있는 것(잎·풀)을 지표로 치면 고리가 그 위에 뜬다.
		 *
		 * <p>{@code chunk.getHeight} 는 <b>가장 높은 블록 자체</b>의 y 를 돌려주므로 1 을 더해
		 * 윗면으로 옮긴다. 월드 쪽 {@code getHeight} 가 안에서 하는 것과 같은 계산인데, 그쪽은
		 * 부를 때마다 청크를 다시 찾는다.
		 *
		 * @return 설 수 있는 높이, 또는 {@link #NO_GROUND}
		 */
		int surfaceAt(ServerLevel end, double x, double z) {
			int blockX = Mth.floor(x);
			int blockZ = Mth.floor(z);
			int wantX = blockX >> 4;
			int wantZ = blockZ >> 4;
			if (wantX != chunkX || wantZ != chunkZ) {
				chunkX = wantX;
				chunkZ = wantZ;
				// 없으면 없는 대로 둔다. 표식을 그리자고 청크를 불러오면 그것이 가장 비싸다.
				chunk = end.getChunkSource().getChunkNow(wantX, wantZ);
			}
			LevelChunk here = chunk;
			if (here == null) {
				return NO_GROUND;
			}
			int surface = here.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					blockX, blockZ) + 1;
			// 바닥까지 통째로 허공이면 하이트맵이 월드 바닥을 돌려준다. 거기는 땅이 아니다.
			return surface > end.getMinY() ? surface : NO_GROUND;
		}
	}

	/**
	 * 고리가 <b>이 사람의 발밑</b>을 지나갔는가.
	 *
	 * <p>고리는 칸마다 그 자리의 지표 위에 그려진다. 그러니 거리가 맞아도 높이가 어긋나면 그
	 * 사람이 본 고리는 <b>제 발밑을 지나간 고리가 아니다</b> — 지붕 밑에 선 사람, 굴을 파고 들어간
	 * 사람이 그렇다. 여유는 {@link #JUDGE_VERTICAL_REACH} 에 근거를 적어 두었다.
	 *
	 * <p>땅이 없으면({@link #NO_GROUND}) 거짓이다. 그 칸에는 고리를 그리지도 않았으니 판정만
	 * 남으면 <b>아무것도 안 보이는데 걸리는</b> 꼴이 된다.
	 *
	 * @param feetY    사람의 발 높이({@code position().y})
	 * @param surfaceY {@link Ground#surfaceAt} 이 돌려준 값
	 */
	static boolean atRingHeight(double feetY, int surfaceY) {
		if (surfaceY == NO_GROUND) {
			return false;
		}
		return Math.abs(feetY - surfaceY) <= JUDGE_VERTICAL_REACH;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 고리가 살아 있는 시간(틱).
	 *
	 * <p>앞머리가 {@code maxRadius} 에 닿은 <b>뒤에도</b> {@link #JUMP_WINDOW_TICKS} 만큼 남는다.
	 * 여기서 깎으면 가장 바깥에 선 사람만 창을 못 받아, 그 사람에게만 옛날의 한 틱짜리 판정이
	 * 적용된다.
	 *
	 * <p>다만 <b>주기를 넘기지는 않는다.</b> 카드에 적힌 {@code travelTicks} 가 주기보다 길면
	 * 다음 고리가 앞 고리를 밀어내는데, 한 자리에 고리 하나만 들고 있으므로 앞 고리는 <b>도중에
	 * 그냥 사라진다</b> — 아직 자기 차례를 기다리던 바깥쪽 사람들은 눈앞까지 온 고리가 증발하는
	 * 것을 본다. {@code TrialFireball.traceWindow} 가 같은 이유로 궤적을 주기로 깎는다.
	 */
	static int lifetime(int interval, int travelTicks) {
		int wanted = Math.max(0, travelTicks) + JUMP_WINDOW_TICKS;
		if (interval <= 0) {
			return wanted;
		}
		return Math.min(wanted, interval - 1);
	}

	/**
	 * 출발하고 {@code step} 틱 뒤 앞머리의 반경.
	 *
	 * <p>{@code step} 이 음수면 <b>음수를 그대로 돌려준다.</b> 안전장치가 아니라 규칙이다 —
	 * {@link #judgeRadius} 가 뒷자락을 「{@link #JUMP_WINDOW_TICKS} 틱 전의 앞머리」로 구하므로,
	 * 파동 첫머리에서 음수가 나와야 거리 0 인 사람도 걸러지지 않고 창을 온전히 받는다.
	 */
	static double radiusAt(int step, int travelTicks, double maxRadius) {
		if (travelTicks <= 0) {
			return maxRadius;
		}
		return maxRadius * step / travelTicks;
	}

	/**
	 * 이 틱에 판정을 받는 거리. 곧 고리 <b>뒷자락</b>의 반경이다.
	 *
	 * <p>앞머리에서 {@link #JUMP_WINDOW_TICKS} 틱 뒤처져 있고, 그 뒤처진 만큼이 「뛰었다」로
	 * 쳐 주는 창이다. 창과 두께를 한 값에서 뽑으므로 <b>보이는 고리와 봐 주는 구간이 어긋날 수
	 * 없다.</b>
	 *
	 * <p>{@code maxRadius} 를 넘지 않게 자른다. 앞머리가 멈춘 뒤에도 뒷자락은 다가오므로, 자르지
	 * 않으면 고리가 닿은 적 없는 42칸 밖 사람까지 묶인다.
	 */
	static double judgeRadius(int step, int travelTicks, double maxRadius) {
		return Math.min(maxRadius, radiusAt(step - JUMP_WINDOW_TICKS, travelTicks, maxRadius));
	}

	/**
	 * 앞머리가 그 거리에 닿는 틱. 고리가 출발한 틱부터 센다.
	 *
	 * <p>올림한다. 실제로 고리가 그 거리를 <b>넘어서는</b> 첫 틱이라야 「아직 안 왔다」가 참이고,
	 * 내림하면 경고가 한 틱 늦게 끝나 사람이 이미 지나간 고리를 기다린다.
	 */
	static int reachTick(double distance, int travelTicks, double maxRadius) {
		if (travelTicks <= 0 || !(maxRadius > 0.0) || !(distance > 0.0)) {
			return 0;
		}
		return (int) Math.ceil(distance * travelTicks / maxRadius);
	}

	/**
	 * 아레나 중앙에서 잰 거리. 높이는 보지 않는다.
	 *
	 * <p>고리는 바닥에 그려지고 중심은 언제나 {@code (0, ?, 0)} 이다. 세로를 섞으면 기둥 위에
	 * 올라간 사람의 거리가 실제보다 멀어져, <b>고리가 발밑을 지나가는데 판정은 비껴간다.</b>
	 */
	static double distanceFromCenter(Vec3 at) {
		return Math.sqrt(at.x * at.x + at.z * at.z);
	}

	/** 앞머리 고리에 찍을 점 수. */
	static int edgePoints(double radius) {
		return ringPoints(radius, EDGE_MAX_POINTS);
	}

	/** 몸통 고리에 찍을 점 수. */
	static int wakePoints(double radius) {
		return ringPoints(radius, WAKE_MAX_POINTS);
	}

	/**
	 * 그 반경에 찍을 점 수.
	 *
	 * <p>개수를 고정하면 반경이 커질수록 점 사이만 벌어진다. 그래서 {@link TrialWarning#POINT_GAP}
	 * 을 목표 간격으로 두고 개수를 거기서 뽑되, 상한에 걸리면 벌어지는 것을 받아들인다.
	 *
	 * <p>하한을 {@link TrialWarning#ringPoints} 에서 빌려 온다. 저장소가 「작은 고리는 이만큼은
	 * 찍는다」를 이미 한 곳에서 정해 두었는데, 여기에 같은 숫자를 다시 적으면 한쪽만 고쳐진다.
	 */
	private static int ringPoints(double radius, int cap) {
		if (!(radius > 0.0) || cap <= 0) {
			return 0;
		}
		int wanted = (int) Math.ceil((Math.PI * 2.0 * radius) / TrialWarning.POINT_GAP);
		return Math.min(cap, Math.max(TrialWarning.ringPoints(radius), wanted));
	}

	/**
	 * 그 반경에서 앞머리 점 사이가 실제로 벌어지는 거리(칸).
	 *
	 * <p>「고리가 고리로 읽히는가」를 숫자로 물을 수 있는 유일한 값이다. 점 수만 보면 상한에 걸린
	 * 큰 고리가 촘촘한 줄 알게 된다.
	 */
	static double edgeGap(double radius) {
		int points = edgePoints(radius);
		if (points <= 0) {
			return 0.0;
		}
		return (Math.PI * 2.0 * radius) / points;
	}
}
