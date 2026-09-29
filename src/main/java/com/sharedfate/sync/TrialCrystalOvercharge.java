package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.CrystalOvercharge} 실행기 — 수정 과충전.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p>무작위 크리스탈 하나가 붉게 달아오른다.
 *
 * <ul>
 *   <li>15초({@code fuseTicks}) 안에 <b>부수면</b> 아무 일도 없다</li>
 *   <li>못 부수면 무작위 한 명에게 10초({@code beamTicks}) 동안 빔이 붙고,
 *       <b>초당 1</b>({@code damagePerSecond})씩 들어간다</li>
 *   <li>빔은 <b>블록으로 가리면 끊긴다</b> — 숨는 것이 이 카드가 요구하는 행동이다</li>
 *   <li>빔이 끝나거나 제때 부수면 20초({@code restTicks}) 뒤에 다음 크리스탈이 달아오른다</li>
 * </ul>
 *
 * <h2>이 카드의 위협은 피해가 아니라 「끌려가는 것」이다</h2>
 *
 * <p>초당 1, 다 맞아도 10 이다. 팀 공유 체력 20 이라 이것만으로는 아무도 죽지 않는다. 이 카드가
 * 요구하는 것은 <b>크리스탈을 강제로 우선하게 만드는 것</b>이다 — 드래곤을 때리고 싶은데
 * 15초마다 기둥으로 끌려간다. 그래서 여기서 가장 중요한 코드는 피해 한 줄이 아니라
 * <b>「어느 크리스탈인지 아레나 반대편에서도 읽히게 하는」</b> {@link #COLUMN_MAX} 쪽이다.
 * 그것이 안 보이면 이 카드는 「어디선가 빔이 날아오는데 원인을 모르는」 카드가 된다.
 *
 * <h2>주기가 아니라 세 걸음의 상태 기계다</h2>
 *
 * <p>다른 카드처럼 {@code elapsed % period} 로 위상을 뽑지 않는다. 도화선이 <b>끝까지 타는지</b>
 * 아니면 <b>중간에 꺼지는지</b>가 팀의 행동에 달려 있어서, 한 바퀴의 길이가 판마다 다르기
 * 때문이다.
 *
 * <pre>
 *   FUSE — 크리스탈 하나가 달아오른다
 *     ├ 그것이 깨졌다        → REST (빔 없음)
 *     └ fuseTicks 가 지났다  → BEAM
 *   BEAM — 무작위 한 명에게 beamTicks 동안 빔
 *     └ 끝났다 / 크리스탈이 깨졌다 → REST
 *   REST — restTicks 쉬고 다시 FUSE
 * </pre>
 *
 * <p>걸음이 바뀐 시각은 <b>{@link TrialRisks#elapsedSinceGrant} 로 잰 값</b>으로 적는다. 월드
 * 시간을 부르지 않으므로 얼어붙은 판에서는 걸음도 그대로 멈춘다.
 *
 * <h2>남은 크리스탈이 없으면 아무 일도 일어나지 않는다</h2>
 *
 * <p>이 카드는 첫 크리스탈 풀({@code POOL_FIRST_CRYSTAL})이라 <b>크리스탈이 다 깨진 뒤에도
 * 전투 내내 살아 있다.</b> 그때 {@link #choose} 가 {@code null} 을 돌려주고 FUSE 는 아무것도 하지
 * 않은 채 제자리에 머문다 — 소리도, 표식도, 빔도 없다. 「부활」 카드가 크리스탈을 되살리면 그
 * 틱에 저절로 다시 돌기 시작한다.
 *
 * <h2>달아오른 것을 어떻게 보이게 하는가 — 파티클만으로는 안 된다</h2>
 *
 * <p>크리스탈은 반경 42 기둥 꼭대기(y 76~103)에 있고 사람은 바닥에 있다. 아레나 반대편이면
 * 90 블록이 넘는다. 먼지 파티클은 <b>긴 형태로 보내면 패킷은 나가지만</b>({@link TrialWarning} 의
 * 「거리 제한을 끄고 보낸다」) 그 거리에서는 점이 너무 작아 「저기 저것이다」를 말하지 못한다.
 *
 * <p>그래서 본 신호는 <b>바닐라 크리스탈 빔을 곧게 위로 세운 빛기둥</b>이다.
 * {@code EndCrystal.setBeamTarget} 은 개체 데이터라 거리 제한이 없고,
 * {@code EndCrystalRenderer.shouldRender} 는 <b>빔이 걸려 있으면 절두체 밖이어도 참</b>을
 * 돌려준다(26.3 바이트코드로 확인). 즉 아레나 어디에서든 보이는 것이 보장된다. 빛기둥은
 * 도화선이 타는 동안 <b>자라난다</b> — 다 자라면 쏜다는 뜻이라, 「어느 것인가」와 「얼마나
 * 남았는가」를 한 신호가 같이 말한다.
 *
 * <p>붉은 먼지와 고리는 그 위에 덧칠한다. 「달아올랐다」는 색을 말하는 것은 파티클뿐이고,
 * 가까이 간 사람에게는 그쪽이 더 잘 읽힌다. 둘 다 <b>긴 형태</b>로 보낸다.
 *
 * <h2>빔도 같은 바닐라 빔이다 — 직접 그리지 않는다</h2>
 *
 * <p>「크리스탈 부활」이 먼지로 선을 긋다가 <b>아무에게도 안 보여</b> 바닐라 빔으로 갈아탄 적이
 * 있다. 여기서도 먼저 바닐라 빔으로 되는지 보았고, 된다.
 *
 * <ul>
 *   <li>거리 제한이 없고 클라이언트가 알아서 그린다</li>
 *   <li>끊는 방법이 {@code setBeamTarget(null)} 한 줄이라 <b>가려졌을 때 연출을 확실히 끌 수
 *       있다</b> — 직접 그린 선은 「이번 틱에 안 그렸다」가 다음 틱까지 남는다</li>
 *   <li>도화선의 빛기둥과 <b>같은 물건</b>이라, 기둥이 사람 쪽으로 넘어가는 그림 하나로
 *       「충전이 끝났고 저 사람이 물렸다」가 전달된다</li>
 * </ul>
 *
 * <p>겨누는 자리는 사람의 <b>눈높이 칸</b>이다. 렌더러가 {@code Vec3.atCenterOf(beamTarget)} 로
 * 끝점을 잡으므로 발밑 칸을 주면 선이 땅에 박힌다.
 *
 * <p>「부활」 카드가 {@code EnderDragonFight.resetSpikeCrystals} 로 판의 빔을 한 번씩 걷지만,
 * 우리는 <b>매 틱 다시 쓰므로</b> 그 한 틱만 깜빡이고 스스로 돌아온다. 살아 있는 드래곤 전투
 * 중에 빔을 건드리는 바닐라 코드는 그것 말고는 없다(26.3 전체에서 {@code setBeamTarget} 을
 * 부르는 곳은 세계 생성({@code EndSpikeFeature})·소환 의식({@code DragonRespawnStage})·그
 * 정리({@code EnderDragonFight.resetSpikeCrystals}) 셋뿐이다. 드래곤은 크리스탈에게 회복을
 * 받으면서도 빔을 쓰지 않는다).
 *
 * <h2>시선은 바닐라 {@code hasLineOfSight} 로 본다</h2>
 *
 * <p>26.3 {@code LivingEntity.hasLineOfSight(Entity)} 는 자기 눈에서 상대의 눈높이로
 * {@code ClipContext.Block.COLLIDER}·{@code Fluid.NONE} 광선을 쏘아
 * {@code HitResult.Type.MISS} 인지를 본다 — <b>몹이 표적을 보는지 판단할 때 쓰는 바로 그
 * 방법</b>이다. 새로 짜지 않는 이유가 그것이다. 「블록으로 가리면 끊긴다」의 「블록」이 바닐라가
 * 몹에게 적용하는 「블록」과 같아야 플레이어가 두 규칙을 따로 배우지 않는다.
 *
 * <p>이 메서드에는 <b>128 블록 상한</b>이 박혀 있다(그 너머는 무조건 거짓). 아레나 반대편에서
 * 기둥 꼭대기까지가 90 남짓이라 전투 중에는 닿지만, 엔드 <b>흑요석 발판</b>(x 100)까지 나간
 * 사람은 상한을 넘어 늘 「가려짐」이 된다. 아레나 밖으로 걸어 나간 사람이 안전한 것은 이 카드의
 * 뜻과 어긋나지 않으므로 그대로 둔다.
 *
 * <p>가려져 있는 동안은 <b>피해도 없고 빔도 안 보인다.</b> 둘을 같이 끄는 것이 규칙이다 — 선이
 * 계속 보이는데 안 아프면 「막은 게 맞나」를 알 수 없다. 대신 <b>시계는 멈추지 않는다.</b>
 * {@code beamTicks} 는 가려져 있든 아니든 그대로 흐르므로, 숨는 것이 곧 시간을 버는 것이 된다.
 *
 * <h2>피해는 한 사람에게만, 넉백 없이</h2>
 *
 * <p>공유 체력에서는 <b>팀원별 피해가 그대로 합산</b>된다({@code StatMirror.fold}). 초당 1 을
 * 넷에게 다 넣으면 초당 4, 10초에 40 이라 팀 체력 20 의 두 배다. 그래서 무는 것은 언제나
 * <b>한 사람</b>이다.
 *
 * <p>피해원에 <b>실체를 달지 않는다.</b> {@code LivingEntity} 는 피해원에 엔티티나 자리가 있을
 * 때만 밀어내는데, 엔드 섬 가장자리에서 밀리면 허공이고 공유 체력이라 한 사람의 낙사가 팀
 * 전체를 끝낸다.
 *
 * <p>종류는 {@code explosion(null, null)} 이다 — 「기둥 화염구」·「종말의 비」·「착지 충격」이
 * 쓰는 것과 같다. {@code magic}·{@code dragonBreath} 를 피한 이유는 26.3 에서 <b>둘 다
 * {@code bypasses_armor} 태그에 들어 있어</b> 방어구가 무시되고, 그러면 카드에 적힌 「초당 1」이
 * 실제로는 그보다 아파지기 때문이다. 폭발 종류는 방어구와 폭발 보호가 그대로 들어 대비한
 * 사람이 손해 보지 않는다.
 *
 * <h2>크리스탈을 우리가 부수지 않는다</h2>
 *
 * <p>도화선이 끝나도 크리스탈은 그대로 선다. 달아오른 것이 빔을 쏘고 계속 서 있는 것이 이 카드의
 * 그림이다 — 부숴 버리면 「못 부순 벌」이 「알아서 사라짐」이 되어 카드가 스스로를 지운다.
 *
 * <p>「그 크리스탈이 깨졌는가」는 <b>개체를 들고 있다가 {@code isAlive()} 를 묻는 것</b>으로
 * 안다. {@code EndCrystal.hurtServer} 는 {@code final} 이라 믹스인으로 덮을 수 없고,
 * {@link CrystalWatch#lastBreaker()} 는 「누가」만 적을 뿐 「어느 것을」은 적지 않는다. 개체를
 * 들고 있으면 {@link #clearState()} 가 월드 없이도 빔을 걷을 수 있다는 이득까지 따라온다
 * ({@code TrialCrystalRevive.RAISED} 가 같은 이유로 개체를 든다).
 *
 * <h2>드래곤은 건드리지 않는다</h2>
 *
 * <p>{@code setPhase} 도 {@code setTarget} 도 부르지 않는다. 드래곤 페이즈에 끼어들었다가
 * <b>착지를 아예 안 하게</b> 된 사고가 있었다({@link TrialDragonFocus} 클래스 설명). 이 파일은
 * 드래곤에게서 아무것도 읽지 않는다 — 인자로 받기만 하는 것은 실행기들의 진입점 모양을 맞추기
 * 위해서다.
 */
public final class TrialCrystalOvercharge {

	// ------------------------------------------------------------------ 못박아 둔 값

	/** 1초. 빔 피해가 들어오는 간격이자 {@code damagePerSecond} 의 「초」다. */
	static final int SECOND_TICKS = 20;

	/**
	 * 도화선 첫 틱의 빛기둥 높이(블록).
	 *
	 * <p>0 에서 자라게 하면 <b>시작한 것 자체를 못 본다.</b> 기둥 하나가 통째로 가려질 만큼은
	 * 안 되고, 옆 기둥 너머에서도 끝이 삐져나올 만큼은 되는 높이다.
	 */
	static final int COLUMN_MIN = 8;

	/**
	 * 도화선이 다 탔을 때의 빛기둥 높이(블록).
	 *
	 * <p>엔드 기둥은 가장 낮은 것과 가장 높은 것의 차가 서른 남짓이다. 그보다 길게 잡아야
	 * <b>낮은 기둥에서 올라온 빛기둥이 옆의 높은 기둥에 가려지지 않는다.</b> 가장 높은 기둥
	 * (y 103) 위로 이만큼 더 올라가도 엔드 건축 한계 안이다.
	 */
	static final int COLUMN_MAX = 40;

	/** 붉은 먼지를 다시 뿌리는 간격(틱). 매 틱 뿌리면 이 카드 하나가 파티클 예산을 먹는다. */
	private static final int PULSE_TICKS = 4;

	/** 도화선 첫 틱에 뿌리는 먼지 수. */
	static final int EMBER_MIN = 6;

	/** 도화선이 다 탔을 때 뿌리는 먼지 수. 「점점 달아오른다」가 이 둘 사이의 기울기다. */
	static final int EMBER_MAX = 40;

	/** 먼지를 흩뿌리는 폭(블록). 크리스탈이 2×2 라 그 몸을 감쌀 만큼만. */
	private static final double EMBER_SPREAD = 0.7;

	/** 먼지를 크리스탈 발치가 아니라 몸통 높이에 뿌린다. */
	private static final double EMBER_LIFT = 1.0;

	/** 크리스탈을 두르는 붉은 고리의 반경(블록). 몸(2×2) 바깥이라 모양이 파묻히지 않는다. */
	private static final double RING_RADIUS = 2.5;

	/** 물린 사람 발밑에 그리는 보라 고리의 반경(블록). 위험 범위가 아니라 이름표다. */
	private static final double VICTIM_RING_RADIUS = 1.5;

	/** 보라 고리를 다시 그리는 간격(틱). */
	private static final int VICTIM_RING_TICKS = 5;

	/**
	 * 크리스탈이 「기둥 위의 것」인지 보는 거리(블록).
	 *
	 * <p>바닐라도 되살린 것도 {@link EndPillars#crystalSeats} 와 <b>정확히 같은 좌표</b>에
	 * 서므로 넉넉한 값이다. {@code TrialCrystalRevive.SEAT_TAKEN_REACH} 와 같은 2.0 을 쓴다.
	 */
	private static final double SEAT_REACH = 2.0;

	// ------------------------------------------------------------------ 걸음

	/** 이번 틱에 어느 걸음인가. */
	enum Step {
		/** 크리스탈 하나가 달아오르는 중. 여기서 깨면 빔이 없다. */
		FUSE,
		/** 빔이 한 사람을 물고 있다. */
		BEAM,
		/** 다음 크리스탈까지 쉰다. */
		REST
	}

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 들고 있는 상태가 <b>어느 카드의 것인가</b>.
	 *
	 * <p>위험은 값(레코드)이라 상태를 들 수 없어 정적 칸을 쓴다. 판이 갈리거나 세션을 되살려
	 * 받은 틱이 달라지면 지난 판의 걸음이 그대로 이어지는데, 그러면 <b>도화선 없이 빔부터
	 * 시작하는</b> 틱이 생긴다. 받은 틱이 바뀌면 처음부터 다시 센다.
	 */
	private static long owner = Long.MIN_VALUE;

	private static Step step = Step.FUSE;

	/** 지금 걸음이 시작된 시각. <b>월드 시간이 아니라</b> {@link TrialRisks#elapsedSinceGrant} 값이다. */
	private static long stepStart;

	/**
	 * 지금 달아오른 크리스탈. 없으면 {@code null}.
	 *
	 * <p><b>개체를 그대로 든다.</b> 좌표만 적어 두면 {@link #clearState()} 에 {@code ServerLevel}
	 * 이 없어 빔을 걷을 방법이 없고, 「그것이 깨졌는가」도 물을 수 없다.
	 */
	private static @Nullable EndCrystal charged;

	/** 직전에 달아올랐던 크리스탈. 연달아 같은 것을 고르지 않으려고 기억한다. */
	private static @Nullable UUID lastCharged;

	/** 지금 빔에 물린 사람. 빔이 없으면 {@code null}. */
	private static @Nullable UUID victim;

	/**
	 * 이번 빔에서 마지막으로 넣은 초 번호. 아직 없으면 {@code -1}.
	 *
	 * <p>위상만으로 판단하면 <b>같은 위상이 두 번 오는 순간 두 번 들어간다.</b>
	 * {@link TrialRisks#elapsedSinceGrant} 가 음수를 0 으로 깎으므로 월드 시간이 되감긴 판에서는
	 * 같은 틱이 이어질 수 있다. {@code TrialDragonFocus.Fired} 가 같은 이유로 있는 칸이다.
	 */
	private static int lastHit = -1;

	private TrialCrystalOvercharge() {
	}

	// ------------------------------------------------------------------ 진입점

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자도 받는 것은 {@link TrialRisks} 의
	 * 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param dragon  <b>쓰지 않는다.</b> 이 카드는 드래곤에게서 아무것도 읽지 않고 아무것도 쓰지
	 *                않는다 — 클래스 설명의 「드래곤은 건드리지 않는다」를 볼 것
	 * @param granted 카드를 받은 틱. 주기는 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.CrystalOvercharge risk) {
		if (end == null || risk == null) {
			return;
		}
		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		if (owner != granted) {
			// 남의 판에서 넘어온 상태다. 걷어 내고 도화선부터 다시 시작한다. 시작 시각을
			// 「0」이 아니라 「지금」으로 적는 것이 중요하다 — 세션을 되살리면 elapsed 가 이미
			// 수천 틱이라, 0 으로 두면 도화선을 건너뛰고 첫 틱에 빔부터 나간다.
			clearState();
			owner = granted;
			stepStart = elapsed;
		}

		// 되살린 세션에서 stepStart 가 지금보다 뒤일 수 있다. 음수 위상은 0 으로 눌러 둔다.
		long inStep = Math.max(0L, elapsed - stepStart);

		// default 를 넣지 말 것. 걸음을 늘리면서 실행을 안 붙이면 빌드가 깨져야 한다.
		switch (step) {
			case FUSE -> fuse(end, members, elapsed, inStep, risk);
			case BEAM -> beam(end, members, elapsed, inStep, risk);
			case REST -> rest(elapsed, inStep, risk);
		}
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>여기가 <b>달아오른 크리스탈의 빔을 걷는 마지막 정상 경로</b>다. 월드를 못 받으므로 들고
	 * 있던 개체에 직접 {@code setBeamTarget(null)} 을 쓴다 — 이미 지워진 개체에 써도 해가 없다.
	 * 빠뜨리면 다음 판의 크리스탈에 <b>이유 없는 빔</b>이 하늘로 뻗은 채 남는다. 컴파일도 시험도
	 * 조용한 사고다.
	 *
	 * <p>붉은 먼지와 고리는 되돌릴 것이 없다. 파티클은 한 번 보내고 스스로 사라지는 것이라
	 * 우리가 끄지 않아도 다음 틱에 남지 않는다.
	 */
	public static void clearState() {
		if (charged != null) {
			charged.setBeamTarget(null);
		}
		charged = null;
		lastCharged = null;
		victim = null;
		lastHit = -1;
		step = Step.FUSE;
		stepStart = 0L;
		owner = Long.MIN_VALUE;
	}

	// ------------------------------------------------------------------ 도화선

	/**
	 * 도화선 한 틱.
	 *
	 * <p>달아오를 크리스탈을 아직 못 골랐으면 <b>매 틱 다시 고른다.</b> 기둥이 텅 비어 있으면
	 * 아무 일도 하지 않고 그대로 머물고, 「부활」 카드가 하나라도 세우는 순간 그 틱에 붙는다.
	 * 고른 틱을 도화선의 0 으로 삼으므로 <b>비어 있던 시간이 도화선을 잡아먹지 않는다.</b>
	 */
	private static void fuse(ServerLevel end, @Nullable List<ServerPlayer> members, long elapsed,
			long inStep, TrialCatalog.Risk.CrystalOvercharge risk) {
		long into = inStep;
		EndCrystal crystal = charged;
		if (crystal == null) {
			crystal = choose(end, lastCharged);
			if (crystal == null) {
				// 기둥에 남은 크리스탈이 하나도 없다 — 이 카드는 조용히 서 있는다.
				stepStart = elapsed;
				return;
			}
			charged = crystal;
			lastCharged = crystal.getUUID();
			stepStart = elapsed;
			into = 0L;
		}
		if (!crystal.isAlive()) {
			// 제때 부쉈다. 빔은 없다 — 이것이 이 카드가 요구한 행동이고, 보상은 조용한 20초다.
			release();
			enter(Step.REST, elapsed);
			return;
		}

		int remaining = remainingFuse(into, risk.fuseTicks());
		if (remaining <= 0) {
			ignite(end, members, elapsed, risk);
			return;
		}

		float progress = fuseProgress(into, risk.fuseTicks());
		// 빛기둥이 본 신호다. 자라는 길이가 곧 남은 시간이다.
		crystal.setBeamTarget(crystal.blockPosition().above(columnHeight(progress)));
		ember(end, crystal, progress, into);

		// 경고의 층은 TrialWarning 의 것을 그대로 쓴다. 카드마다 새 신호를 만들면 플레이어가
		// 카드 수만큼 새 언어를 배워야 한다.
		if (TrialRisks.stageJustChanged(remaining)) {
			warn(end, members, TrialWarning.stageFor(remaining));
		}
	}

	/**
	 * 도화선이 다 탔다. 한 사람을 물고 곧바로 첫 초를 넣는다.
	 *
	 * <p>물 사람이 하나도 없으면(전원 사망·관전) 빔 없이 쉼으로 넘어간다. 크리스탈은 그대로
	 * 선 채 남는다 — <b>우리가 부수지 않는다.</b>
	 *
	 * <p>같은 틱에 {@link #beam} 을 한 번 굴리는 것이 중요하다. 다음 틱으로 미루면 빔이 한 틱
	 * 늦게 뜨고, 그 한 틱이 「도화선이 끝났는데 아무 일도 없다」로 읽힌다.
	 */
	private static void ignite(ServerLevel end, @Nullable List<ServerPlayer> members, long elapsed,
			TrialCatalog.Risk.CrystalOvercharge risk) {
		ServerPlayer target = pickVictim(end, members);
		if (target == null) {
			release();
			enter(Step.REST, elapsed);
			return;
		}
		victim = target.getUUID();
		lastHit = -1;
		enter(Step.BEAM, elapsed);
		announce(end, members);
		beam(end, members, elapsed, 0L, risk);
	}

	// ------------------------------------------------------------------ 빔

	/**
	 * 빔 한 틱.
	 *
	 * <p>순서가 규칙이다 — <b>끝나는 조건을 먼저 보고</b>, 그다음에 대상을 세우고, 마지막에
	 * 시선을 묻는다. 시선을 먼저 물으면 이미 끝난 빔이 한 틱 더 때린다.
	 *
	 * <p>빔 중에 크리스탈이 깨지면 빔도 끊긴다. 근원이 사라졌는데 선만 남는 것은 거짓말이고,
	 * 「늦게라도 부수면 멈춘다」는 도화선의 규칙과도 이어진다.
	 */
	private static void beam(ServerLevel end, @Nullable List<ServerPlayer> members, long elapsed,
			long inStep, TrialCatalog.Risk.CrystalOvercharge risk) {
		EndCrystal crystal = charged;
		if (crystal == null || !crystal.isAlive()) {
			release();
			enter(Step.REST, elapsed);
			return;
		}
		if (inStep >= Math.max(0, risk.beamTicks())) {
			release();
			enter(Step.REST, elapsed);
			return;
		}

		ServerPlayer target = victimOf(members);
		if (target == null) {
			// 물고 있던 사람이 나갔거나 관전으로 넘어갔다. 다시 고르지 않으면 이 카드는 남은
			// 시간 동안 아무도 노리지 않는 빈 카드가 된다. 고를 사람이 없으면 거기서 끝난다.
			target = pickVictim(end, members);
			if (target == null) {
				release();
				enter(Step.REST, elapsed);
				return;
			}
			victim = target.getUUID();
		}

		// 크리스탈은 빔이 끊겨 있어도 계속 달아올라 있다. 「어디서 오는가」는 가려진 동안에도
		// 보여야 다시 나설 때 어느 쪽을 피할지 정할 수 있다.
		ember(end, crystal, 1.0F, inStep);
		if (inStep % VICTIM_RING_TICKS == 0L) {
			TrialWarning.markGround(end, target.position(), VICTIM_RING_RADIUS,
					TrialWarning.dust(TrialWarning.Colors.MARKED));
		}

		// 26.3 바닐라 LivingEntity.hasLineOfSight — 눈에서 상대의 눈높이로 COLLIDER·Fluid.NONE
		// 광선을 쏘아 MISS 인지 본다. 몹이 표적을 보는지 판단하는 바로 그 방법이다.
		if (!target.hasLineOfSight(crystal)) {
			// 가려졌다. 연출과 피해를 같이 끈다 — 선만 남으면 막은 것인지 알 수 없다.
			crystal.setBeamTarget(null);
			return;
		}
		// 눈높이 칸을 겨눈다. 렌더러가 칸의 한가운데를 끝점으로 잡으므로 발밑 칸을 주면 선이
		// 땅에 박혀 「사람을 물었다」로 안 읽힌다.
		crystal.setBeamTarget(BlockPos.containing(target.getX(), target.getEyeY(), target.getZ()));

		int index = hitIndex(inStep, SECOND_TICKS);
		if (index < 0 || index <= lastHit || risk.damagePerSecond() <= 0.0F) {
			return;
		}
		lastHit = index;
		// 맞는 사람은 표적 하나뿐이고, 피해원에 실체를 달지 않아 넉백이 없다. 종류를
		// explosion 으로 고른 까닭은 클래스 설명의 「피해는 한 사람에게만, 넉백 없이」에 있다.
		target.hurtServer(end, end.damageSources().explosion(null, null), risk.damagePerSecond());
	}

	// ------------------------------------------------------------------ 쉼

	/** 쉬는 한 틱. 다 쉬면 도화선으로 돌아가고, 크리스탈은 그때 다시 고른다. */
	private static void rest(long elapsed, long inStep, TrialCatalog.Risk.CrystalOvercharge risk) {
		if (inStep < Math.max(0, risk.restTicks())) {
			return;
		}
		charged = null;
		enter(Step.FUSE, elapsed);
	}

	// ------------------------------------------------------------------ 연출

	/**
	 * 크리스탈을 붉은 먼지와 고리로 두른다. <b>둘 다 긴 형태로 보낸다.</b>
	 *
	 * <p>첫 {@code boolean} 을 {@code false} 로 되돌리면 서버가 <b>발생 지점 32 블록 안의
	 * 사람에게만</b> 패킷을 보내고 클라이언트가 한 번 더 거른다. 크리스탈은 반경 42 기둥 꼭대기라
	 * 거의 언제나 32 블록 밖이므로 <b>연출이 통째로 사라진다</b> — 「크리스탈 부활」이 정확히
	 * 이 함정에 빠져 연출을 한 번 통째로 잃었다.
	 *
	 * <p>고리는 {@link TrialWarning#markGround} 를 그대로 쓴다. 그쪽도 긴 형태이고, 색·모양·높이
	 * 규약이 갈라지지 않는다.
	 */
	private static void ember(ServerLevel end, EndCrystal crystal, float progress, long inStep) {
		if (inStep % PULSE_TICKS != 0L) {
			return;
		}
		Vec3 at = crystal.position();
		ParticleOptions dust = TrialWarning.dust(TrialWarning.Colors.DEADLY);
		end.sendParticles(dust, true, false, at.x, at.y + EMBER_LIFT, at.z, emberCount(progress),
				EMBER_SPREAD, EMBER_SPREAD, EMBER_SPREAD, 0.0);
		TrialWarning.markGround(end, at, RING_RADIUS, dust);
	}

	/**
	 * 경고 층의 소리를 <b>사람마다 그 자리에서</b> 낸다.
	 *
	 * <p>소리도 파티클과 같이 잘린다 — {@code level.playSound} 는 자리에 소리를 놓는 것이고
	 * 볼륨 1 이면 16 블록이다. 크리스탈 자리에서 한 번 울리면 기둥 꼭대기에 올라간 사람 말고는
	 * <b>아무도 못 듣는다.</b>
	 *
	 * <p>{@link TrialWarning#soundFor} 를 사람마다 부른다. 소리표를 여기에 베껴 오면
	 * {@code TrialWarning} 이 층의 소리를 바꿀 때 이 카드만 옛 소리로 남는다 — <b>규약이 갈라지는
	 * 것이 더 나쁘다.</b>
	 *
	 * <p>⚠ <b>전에는 {@code TrialWarning.sound} 를 사람 자리마다 불렀고, 그것이 틀렸다.</b> 그쪽은
	 * 자리에 소리를 놓는 것이라 반경 안의 <b>전원</b>에게 나간다 — 옛 주석은 그 대가를 「둘이 붙어
	 * 있으면 조금 커진다」고 적어 두었는데, 실제로는 넷이 모이면 <b>각자 네 겹</b>으로 듣고 남의
	 * 경고까지 듣는다. {@code soundFor} 는 그 사람의 연결로 직접 보내 둘 다 없앤다.
	 */
	private static void warn(ServerLevel end, @Nullable List<ServerPlayer> members,
			@Nullable TrialWarning.Stage stage) {
		if (stage == null || members == null) {
			return;
		}
		for (ServerPlayer member : members) {
			TrialWarning.soundFor(end, member, stage);
		}
	}

	/**
	 * 빔이 붙었다고 알린다. 이것도 <b>사람마다 그 자리에서</b> 울린다.
	 *
	 * <p>전도체의 공격음이다. 다른 카드가 쓰는 소리(용의 포효·비컨·경험치·셜커·폭발·끝문)와 겹치지
	 * 않으면서, <b>빔이 사람을 문다</b>는 그림이 원래 그 소리의 뜻과 같다. 화면을 안 보고 있어도
	 * 「무엇이 시작됐는지」가 이 한 소리로 갈린다.
	 */
	private static void announce(ServerLevel end, @Nullable List<ServerPlayer> members) {
		TrialWarning.playEach(end, members, SoundEvents.CONDUIT_ATTACK_TARGET, 1.0F, 0.6F);
	}

	// ------------------------------------------------------------------ 고르기

	/**
	 * 달아오를 크리스탈 하나. 기둥에 남은 것이 없으면 {@code null}.
	 *
	 * <p><b>기둥 위의 것만</b> 고른다. 사람이 손에 들고 다니며 터뜨리는 크리스탈까지 고르면
	 * 빔이 아레나 바닥에서 나가고, 그 크리스탈은 어차피 다음 순간 사람 손에 터진다 — 카드가
	 * 아무 일도 안 한 것이 된다. 판별은 {@link EndPillars#crystalSeats} 와의 거리로 한다.
	 *
	 * <p>직전에 골랐던 것은 <b>다른 후보가 있으면</b> 건너뛴다. 「20초 뒤 다른 크리스탈이
	 * 달아오른다」가 이 카드가 약속한 그림이고, 같은 것이 세 번 연달아 달아오르면 그 약속이
	 * 깨진다. 후보가 그것 하나뿐이면 그대로 다시 고른다 — 카드를 죽이는 것보다 낫다.
	 */
	private static @Nullable EndCrystal choose(ServerLevel end, @Nullable UUID avoid) {
		List<Vec3> seats = EndPillars.crystalSeats(end);
		if (seats.isEmpty()) {
			return null;
		}
		List<EndCrystal> onSpikes = new ArrayList<>();
		List<EndCrystal> fresh = new ArrayList<>();
		for (EndCrystal crystal : end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive)) {
			if (!onSeat(crystal.position(), seats)) {
				continue;
			}
			onSpikes.add(crystal);
			if (avoid == null || !crystal.getUUID().equals(avoid)) {
				fresh.add(crystal);
			}
		}
		List<EndCrystal> pool = fresh.isEmpty() ? onSpikes : fresh;
		if (pool.isEmpty()) {
			return null;
		}
		return pool.get(end.getRandom().nextInt(pool.size()));
	}

	/**
	 * 빔에 물릴 한 사람. 물 사람이 없으면 {@code null}.
	 *
	 * <p><b>반드시 한 명이다.</b> 공유 체력에서 범위 피해는 팀원별로 합산되므로 넷을 다 물면
	 * 초당 4 가 되고 10초에 40 이다 — 팀 체력 20 의 두 배, 곧 즉사 카드다.
	 */
	private static @Nullable ServerPlayer pickVictim(ServerLevel end,
			@Nullable List<ServerPlayer> members) {
		List<ServerPlayer> alive = targetable(members);
		if (alive.isEmpty()) {
			return null;
		}
		return alive.get(end.getRandom().nextInt(alive.size()));
	}

	/** 지금 물려 있는 사람. 명단에 없거나 죽었거나 관전 중이면 {@code null}. */
	private static @Nullable ServerPlayer victimOf(@Nullable List<ServerPlayer> members) {
		if (victim == null || members == null) {
			return null;
		}
		for (ServerPlayer member : members) {
			if (member != null && member.getUUID().equals(victim) && member.isAlive()
					&& !member.isSpectator()) {
				return member;
			}
		}
		return null;
	}

	/** 지금 물 수 있는 사람들. 시체와 관전자를 물면 빔이 아무 일도 하지 않는다. */
	private static List<ServerPlayer> targetable(@Nullable List<ServerPlayer> members) {
		List<ServerPlayer> alive = new ArrayList<>();
		if (members == null) {
			return alive;
		}
		for (ServerPlayer member : members) {
			if (member != null && member.isAlive() && !member.isSpectator()) {
				alive.add(member);
			}
		}
		return alive;
	}

	// ------------------------------------------------------------------ 걸음 바꾸기

	/** 걸음을 바꾸고 시작 시각을 적는다. 시각은 <b>받은 뒤 흐른 틱</b>이지 월드 시간이 아니다. */
	private static void enter(Step next, long elapsed) {
		step = next;
		stepStart = elapsed;
	}

	/**
	 * 달아오른 크리스탈에 우리가 걸어 둔 것을 되돌린다.
	 *
	 * <p>되돌릴 것은 빔 하나뿐이다. 무적도 블록도 건드리지 않았고, 크리스탈 자체는 <b>부수지
	 * 않고 그대로 둔다.</b>
	 */
	private static void release() {
		if (charged != null) {
			charged.setBeamTarget(null);
		}
		charged = null;
		victim = null;
		lastHit = -1;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/** 도화선이 끝나기까지 남은 틱. 0 이면 이번 틱에 쏜다. */
	static int remainingFuse(long inStep, int fuseTicks) {
		int fuse = Math.max(0, fuseTicks);
		long left = fuse - Math.max(0L, inStep);
		return (int) Math.max(0L, Math.min(fuse, left));
	}

	/**
	 * 도화선이 얼마나 탔는가. 0 이 시작, 1 이 쏘는 순간.
	 *
	 * <p><b>0 과 1 을 벗어나면 안 된다.</b> 이 값이 빛기둥의 길이라, 1 을 넘으면 기둥이 약속한
	 * 높이보다 더 자라 「다 차면 쏜다」가 거짓말이 된다.
	 */
	static float fuseProgress(long inStep, int fuseTicks) {
		int fuse = Math.max(0, fuseTicks);
		if (fuse <= 0) {
			return 1.0F;
		}
		long into = Math.max(0L, Math.min(fuse, inStep));
		return (float) into / fuse;
	}

	/** 그 진행도에서의 빛기둥 높이(블록). {@link #COLUMN_MIN} 아래로는 내려가지 않는다. */
	static int columnHeight(float progress) {
		float grown = Math.max(0.0F, Math.min(1.0F, progress));
		return COLUMN_MIN + Math.round((COLUMN_MAX - COLUMN_MIN) * grown);
	}

	/** 그 진행도에서 뿌릴 먼지 수. 「점점 달아오른다」가 이 기울기다. */
	static int emberCount(float progress) {
		float grown = Math.max(0.0F, Math.min(1.0F, progress));
		return EMBER_MIN + Math.round((EMBER_MAX - EMBER_MIN) * grown);
	}

	/**
	 * 이 위상에서 들어갈 초 번호. 들어갈 초가 아니면 {@code -1}.
	 *
	 * <p>빔이 붙는 <b>그 틱이 0번</b>이다. 그래서 {@code beamTicks} 가 200 이면 위상
	 * 0·20·…·180 에 열 번 들어가고 합계가 정확히 {@code damagePerSecond × 10} 이 된다.
	 * 첫 초를 뒤로 미루면 빔이 떠 있는데 아무 일도 없는 1초가 생겨 「가려도 되나」가 흐려진다.
	 */
	static int hitIndex(long inStep, int secondTicks) {
		if (secondTicks <= 0 || inStep < 0L || inStep % secondTicks != 0L) {
			return -1;
		}
		return (int) (inStep / secondTicks);
	}

	/** 빔 하나가 끝까지 갔을 때 들어가는 횟수. */
	static int beamHits(int beamTicks, int secondTicks) {
		if (beamTicks <= 0 || secondTicks <= 0) {
			return 0;
		}
		return beamTicks / secondTicks;
	}

	/**
	 * 한 사람이 빔 하나에서 <b>다 맞았을 때</b> 받는 총 피해.
	 *
	 * <p>{@code TrialRisks.worstCaseTickDamage} 는 「한 틱에 올 수 있는 가장 큰 값」을 보지만,
	 * 이 카드가 위험해지는 길은 한 틱이 아니라 <b>열 번 쌓이는 쪽</b>이다. 값을 고치는 사람이
	 * 곱을 볼 수 있게 여기에 둔다.
	 */
	static float beamTotalDamage(@Nullable TrialCatalog.Risk.CrystalOvercharge risk) {
		if (risk == null || risk.damagePerSecond() <= 0.0F) {
			return 0.0F;
		}
		return risk.damagePerSecond() * beamHits(risk.beamTicks(), SECOND_TICKS);
	}

	/**
	 * 그 자리가 기둥 위 크리스탈 자리인가.
	 *
	 * <p>가로와 세로를 함께 본다. 기둥은 높이가 제각각이라 가로만 보면 <b>낮은 기둥 위에 선
	 * 사람이 손으로 놓은 크리스탈</b>이 높은 기둥의 자리로 읽힌다.
	 */
	static boolean onSeat(Vec3 at, List<Vec3> seats) {
		for (Vec3 seat : seats) {
			if (Math.abs(at.y - seat.y) <= SEAT_REACH
					&& TrialRisks.insideMark(at, seat, SEAT_REACH)) {
				return true;
			}
		}
		return false;
	}
}
