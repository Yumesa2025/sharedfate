package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 드래곤 기본 패시브 「가르는 브레스」 — 아레나를 반으로 가르는 잔류 장판.
 *
 * <p>드래곤이 아레나를 가로질러 브레스를 뿜고, 지나간 자리에 <b>직선 장판</b>이 15초 남아 길을
 * 막는다. 시련과 달리 <b>언제나 있는 판</b>이라 팀이 무엇을 뽑았든 40초마다 한 번씩 돈다.
 *
 * <h2>블록은 한 칸도 건드리지 않는다</h2>
 *
 * <p>「바닥을 반으로 없앤다」가 처음 나온 말이었고 사람이 곧바로 바로잡았다 — <b>없애는 것이
 * 아니라 못 지나가게 하는 것</b>이다. 그 구분이 이 패턴의 전부다. 엔드 중앙 섬에 구멍을 내면
 * 공허 낙사가 되고, 그것은 이 전투가 유일하게 금지한 「대응 불가 즉사」다. 게다가 구멍은 판이
 * 끝나도 남아 <b>다음 전투의 발판이 달라진다</b>. 그래서 여기에는 블록을 읽는 코드만 있고
 * ({@link #groundY} 의 하이트맵) 쓰는 코드는 한 줄도 없다.
 *
 * <h2>왜 바닐라 {@code AreaEffectCloud} 를 쓰지 않는가</h2>
 *
 * <p>구름은 판정과 연출이 한 덩어리라 편하지만, 이 패턴에는 네 가지가 어긋난다.
 *
 * <ul>
 *   <li><b>구름은 원이고 우리 장판은 80칸짜리 선이다.</b> 반경 2 짜리 구름을 줄 세워 덮으려면
 *       마흔 개쯤 필요하고, 이웃한 구름은 반드시 겹친다. 겹친 자리에 선 사람은 <b>한 틱에 두 번
 *       맞는다</b> — 「낙뢰」에서 {@code TrialRisks.SPOT_MIN_GAP_FACTOR} 로 막아 둔 바로 그
 *       사고이고, 「피해는 카드에 적힌 값 하나」를 구조적으로 못 지킨다</li>
 *   <li><b>바닐라 드래곤 브레스 구름은 즉시 피해 <i>포션 효과</i>로 아프게 한다.</b> 26.3 의
 *       {@code DragonSittingFlamingPhase} 가 {@code addEffect(INSTANT_DAMAGE)} 를 건다. 그러면
 *       피해량이 포션 등급 공식에서 나와 우리가 쥐지 못하고, 상태이상이라
 *       {@link SharedEffectDamage} 와도 얽힌다</li>
 *   <li><b>{@code waitTime}·{@code reapplicationDelay}·{@code radiusPerTick} 이 값을 대신
 *       정한다.</b> 「정확히 15초 뒤 사라진다」를 시험으로 물을 수 없게 된다</li>
 *   <li><b>엔티티 마흔 개는 월드에 남는다.</b> 서버가 내려가거나 판이 접히면 {@code clearState}
 *       로 못 지운다 — 이 패키지가 가장 자주 겪은 「다음 판으로 새는」 사고다</li>
 * </ul>
 *
 * <p>그래서 판정은 우리가 하고({@link #insideField}) 구름 모양만 파티클로 흉내 낸다. 보이는
 * 것은 브레스이고 값은 전부 우리 것이다.
 *
 * <h2>피해는 한 값뿐이고, 팀에게 한 번만 들어간다</h2>
 *
 * <p>쓸고 지나가는 브레스 자체는 <b>아프지 않다.</b> 아픈 것은 남은 장판뿐이다. 둘 다 아프게
 * 하면 「브레스에 스쳤다」와 「장판을 밟았다」가 섞여 무엇에 맞았는지 못 읽고, 무엇보다
 * 이중 피해가 된다.
 *
 * <p>그리고 <b>한 장판은 하나의 공격</b>이므로 한 틱에 팀 공유 풀을 한 번만 깎는다
 * ({@link #burn}). 설계의 「광역 한 틱 한 번」이 그것이고, 넷이 함께 건너는 것이 이 패턴에
 * 대한 올바른 대응인데 그때 피해가 네 배가 되면 올바른 대응이 전멸이 된다.
 *
 * <h2>왜 {@link SharedAreaDamage} 에 맡기지 않는가</h2>
 *
 * <p>그쪽은 <b>피해원에 가해 개체가 붙어 있어야</b> 접는다({@code source.getEntity() != null}).
 * 그런데 {@code dragon_breath} 의 데이터를 보면 {@code "scaling": "when_caused_by_living_non_player"}
 * 다 — 가해 개체로 드래곤을 달면 <b>어려움 난이도에서 값이 ×1.5</b> 가 된다. 하드코어는 난이도가
 * 어려움 고정이라 적어 둔 2 가 조용히 3 이 되고, 그것이 설계의 「난이도 스케일링에 얹힌 피해」
 * 금지다. 그래서 가해 개체를 달지 않고 접는 일만 여기서 직접 한다.
 *
 * <p>넉백은 걱정하지 않아도 된다 — {@code dragon_breath} 는 바닐라 {@code no_knockback} 태그에
 * 들어 있다. 엔드 섬 밖 허공으로 밀려나는 길이 애초에 없다.
 *
 * <h2>갇히면 안 된다</h2>
 *
 * <p>장판이 생기는 순간 그 위에 서 있던 사람은 <b>걸어 나올 수 있어야 한다.</b> 폭이 좌우
 * {@link #HALF_WIDTH} 칸뿐인 이유가 그것이다 — 걸어서 0.9초, 달리면 0.7초면 벗어나고 그동안
 * 받는 것은 네 점 남짓이다. 이 패턴이 뜻하는 것은 「들어가면 죽는다」가 아니라 <b>「지나가려면
 * 아프다」</b>이고, 그래서 아레나가 갈리되 길이 끊기지는 않는다. 서서 15초를 다 맞으면 60 —
 * 팀 체력의 세 배다.
 */
public final class DragonRiftBreath {

	// ------------------------------------------------------------------ 값

	/**
	 * 다시 그을 때까지(틱). 40초.
	 *
	 * <p><b>예고 + 장판보다 넉넉히 길어야 한다.</b> 선 둘이 동시에 살아 있으면 교차점에 선 사람이
	 * 한 틱에 두 번 맞고, 그 순간 {@link #worstCaseTickDamage} 가 거짓이 된다. 지금은
	 * 120 + 300 = 420 이라 380틱이 남는다 — 「길이 열려 있는 시간」이 절반 가까이 되게 잡은 값이고,
	 * 실측으로 조정한다.
	 */
	static final int PERIOD_TICKS = 800;
	/**
	 * 선이 보이기 시작해 브레스가 지나갈 때까지(틱). 6초.
	 *
	 * <h2>왜 6초인가</h2>
	 *
	 * <p>이 패턴이 요구하는 행동은 <b>「선이 지나갈 자리에서 비키기」</b>인데, 선이 아레나를
	 * 통째로 가르므로 옆걸음만으로는 끝나지 않는다. <b>어느 쪽에 남을지를 골라야</b> 하고, 그
	 * 선택은 팀 전체가 나뉘는 선택이다 — 반대편에 혼자 남으면 15초 동안 합류할 수 없다.
	 *
	 * <ul>
	 *   <li>{@link TrialWarning#TICKS_SIDESTEP}(30) — 제자리 옆걸음. <b>모자란다.</b> 비킬 방향이
	 *       한쪽뿐인 패턴의 기준이다</li>
	 *   <li>{@link TrialWarning#TICKS_REPOSITION}(50) — 지정한 자리로 이동. 여전히 모자란다.
	 *       갈 곳을 고르는 시간이 빠져 있다</li>
	 *   <li>{@link TrialWarning#TICKS_SCATTER}(80) — 넷이 서로 보고 갈라서기. <b>이것이 하한이다</b></li>
	 *   <li><b>100</b> — {@link TrialWarning#stageFor} 가 경고 첫 층을 내기 시작하는 지점이다.
	 *       예고가 100 이하면 「뭔가 온다」 층이 통째로 빠진다</li>
	 * </ul>
	 *
	 * <p>그 위에 여유 20틱(1초)을 얹어 <b>120</b>. 남는 1초는 「선이 먼저 조용히 그어지고 소리가
	 * 뒤따르는」 구간이 되는데, 80칸짜리 선은 읽는 데 시간이 걸리므로 그쪽이 낫다.
	 *
	 * <p>카드 문서의 「부채꼴 브레스」도 6초다. 패시브끼리 예고 길이를 맞춰 두면 플레이어가
	 * 「드래곤이 뭔가 준비하면 6초」 하나만 배운다.
	 */
	static final int LEAD_TICKS = 120;
	/**
	 * 브레스가 아레나를 쓸고 지나가는 시간(틱). 예고의 <b>마지막</b> 1초다.
	 *
	 * <p>이 구간에는 피해가 없다. 장판은 브레스가 반대편 끝에 닿는 순간 <b>한꺼번에</b> 붙는다 —
	 * 붙는 시각이 하나여야 사람이 15초를 셀 수 있고, 뒤에서부터 차례로 꺼지는 장판은 설명할
	 * 방법이 없다.
	 */
	static final int SWEEP_TICKS = 20;
	/** 장판이 남아 있는 시간(틱). 15초. */
	static final int FIELD_TICKS = 300;
	/**
	 * 선 중심에서 좌우로 뻗는 폭(칸).
	 *
	 * <p>총 4칸이다. <b>더 넓히지 말 것</b> — 넓히는 순간 「지나가려면 아프다」가 「들어가면
	 * 죽는다」가 되고, 장판이 생기는 순간 그 위에 있던 사람이 빠져나오지 못한다.
	 */
	static final double HALF_WIDTH = 2.0;
	/**
	 * 피해가 들어가는 간격(틱).
	 *
	 * <p>10 인 것은 바닐라 피격 무적시간이 10틱이기 때문이다. 더 촘촘하게 때리면 그 몫이
	 * 무적시간에 먹혀 사라지고, 20틱마다 4씩 주면 <b>빨리 뛰어 건넌 사람이 한 점도 안 아픈</b>
	 * 판이 생긴다. 무적시간을 무시하는 길은 설계가 금지한다.
	 */
	static final int DAMAGE_PERIOD_TICKS = 10;
	/** 한 번에 들어가는 피해. {@link #DAMAGE_PERIOD_TICKS} 와 묶여 <b>초당 4</b>가 된다. */
	static final float DAMAGE_PER_PULSE = 2.0F;

	/**
	 * 같은 시각에 살아 있을 수 있는 선의 수.
	 *
	 * <p>{@link #PERIOD_TICKS} 가 예고 + 장판보다 길어 하나뿐이다. 이 숫자가 곧
	 * {@link #worstCaseTickDamage} 의 곱셈 상대이므로, 주기를 줄이거나 선을 둘로 늘리는 사람은
	 * 반드시 여기를 함께 고쳐야 한다.
	 */
	static final int MAX_CONCURRENT_RIFTS = 1;

	// ------------------------------------------------------------------ 선을 그리는 값

	/** 점 사이 목표 간격(칸). 이것보다 촘촘하게는 찍지 않는다. */
	private static final double LINE_POINT_GAP = 1.0;
	/**
	 * 점 사이가 이보다 벌어지면 선이 아니라 점선이다.
	 *
	 * <p>이 선은 「여기를 넘지 마라」는 <b>벽</b>이다. 점선으로 보이면 사이로 지나갈 수 있어
	 * 보이고, 실제로는 못 지나간다 — 표식이 거짓말을 하는 것이라 고리보다 기준이 빡빡하다.
	 */
	static final double LINE_MAX_GAP = 1.5;
	/** 이 길이까지는 위 간격을 약속한다. 선은 아레나를 지름으로 가르므로 반경의 두 배다. */
	static final double LINE_KEPT_LENGTH = TrialRisks.ARENA_RADIUS * 2.0;
	/**
	 * 한 변에 찍는 점 수의 상한.
	 *
	 * <p>숫자를 박지 않고 위 둘에서 뽑는다 — 따로 적어 두면 한쪽만 고쳐져 약속이 조용히 깨진다.
	 * 점 하나가 패킷 한 장이고 이 선은 <b>변이 둘</b>이며 예고 6초 + 장판 15초 내내 매 틱
	 * 그려지므로, 상한 없이는 파티클만으로 틱이 밀린다.
	 */
	static final int LINE_MAX_POINTS = (int) Math.ceil(LINE_KEPT_LENGTH / LINE_MAX_GAP) + 1;

	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다. {@code TrialWarning} 과 같은 값. */
	private static final double GROUND_OFFSET = 0.15;
	/**
	 * 하이트맵이 허공을 돌려줬을 때 쓰는 높이.
	 *
	 * <p>아레나 반경 40 안에도 섬이 끊긴 곳이 있다. 거기서 점을 포기하면 선에 구멍이 뚫려
	 * 「저기는 안전한가」로 읽히므로, 마지막으로 찾은 지면 높이를 그대로 이어 그린다. 첫 점부터
	 * 허공이면 중앙 섬 표면인 이 값으로 시작한다.
	 */
	private static final double FALLBACK_GROUND_Y = 63.0;

	/**
	 * 예고가 이만큼도 안 남았으면 이번 주기는 긋지 않는다.
	 *
	 * <p>{@link #LEAD_TICKS} 의 첫 틱을 놓치는 길이 둘 있다 — <b>예고 도중에 서버가 떴을 때</b>와
	 * <b>앞 장판이 아직 타고 있어 이번 주기를 건너뛴 뒤</b>다. 그때 남은 만큼만 예고하고 그으면
	 * 「예고가 짧은 판」이 생기는데, 짧은 예고는 예고가 아니라 사후 통보다. <b>한 번 안 나가는
	 * 쪽이 싸다.</b>
	 */
	static final int MIN_LEAD_TICKS = TrialWarning.TICKS_SCATTER;

	/** 아직 브레스가 지나가지 않아 장판이 붙지 않은 상태. */
	static final long NOT_IGNITED = Long.MIN_VALUE;

	/**
	 * 이번 주기의 선을 그을지 <b>이미 판단했는가</b>.
	 *
	 * <p>「예고 구간이면 긋는다」로만 두면, 앞 장판이 타는 동안 건너뛴 주기가 장판이 꺼지는 순간
	 * 되살아나 <b>예고를 절반만 내고</b> 터진다. 주기당 판단을 한 번으로 묶어 그 길을 없앤다.
	 */
	private static long plannedCycle = Long.MIN_VALUE;
	/**
	 * 위 기록이 <b>어느 전투의 것인가</b>. 전투가 열린 틱으로 가른다.
	 *
	 * <p>이것이 없으면 드래곤을 잡고 다시 들어간 판에서 주기 번호가 겹쳐, 새 전투의 그 주기가
	 * 「이미 판단했다」로 건너뛰어진다. 전투가 끝날 때 누가 비워 주기를 <b>기다리지 않는다</b> —
	 * 배선을 한 줄 빠뜨렸다고 패시브가 조용히 한 번 쉬면 안 된다.
	 */
	private static long plannedFight = Long.MIN_VALUE;

	/**
	 * 지금 그려져 있거나 타고 있는 선. 없으면 {@code null}.
	 *
	 * <p>아레나도 드래곤도 하나뿐이고 이 모드는 팀이 하나다({@code SingleTeamOnly}). 열쇠를 둘
	 * 필요가 없어 칸 하나로 둔다. <b>{@link #clearState()} 로 반드시 비운다</b> — 지난 판의
	 * 좌표가 남으면 새 월드에서 아무도 모르는 자리가 탄다.
	 */
	private static @Nullable Rift active;

	/**
	 * 한 번 그은 선.
	 *
	 * @param cycle     몇 번째 주기의 선인가. 주기가 넘어갔는지 판단한다
	 * @param from      브레스가 출발하는 아레나 경계
	 * @param to        브레스가 닿는 반대편 경계
	 * @param leftEdge  장판 왼쪽 경계에 찍을 점들. 지면 높이가 들어 있다
	 * @param rightEdge 오른쪽 경계
	 * @param ignitedAt 장판이 붙은 틱. 아직이면 {@link #NOT_IGNITED}
	 */
	record Rift(long cycle, Vec3 from, Vec3 to, List<Vec3> leftEdge, List<Vec3> rightEdge,
			long ignitedAt) {

		Rift {
			leftEdge = List.copyOf(leftEdge);
			rightEdge = List.copyOf(rightEdge);
		}

		boolean ignited() {
			return ignitedAt != NOT_IGNITED;
		}

		Rift ignitedAt(long tick) {
			return new Rift(cycle, from, to, leftEdge, rightEdge, tick);
		}
	}

	private DragonRiftBreath() {
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱.
	 *
	 * <p>{@code dragon} 은 이 패시브가 좌표로 쓰지 않는다 — 브레스는 아레나 중앙을 지나는 선이지
	 * 드래곤이 바라보는 방향이 아니다. 그래도 받는 것은 <b>드래곤이 살아 있을 때만 도는 판</b>임을
	 * 진입점에 남겨 두고, 패시브들의 시그니처를 하나로 맞춰 {@link DragonPassives} 의 배선이 갈래
	 * 없이 한 줄로 끝나게 하기 위해서다.
	 *
	 * @param granted 전투가 열린 틱. 주기는 월드 시간이 아니라 여기서부터 센다. 그래야 판마다
	 *                위상이 달라지고, 받자마자 터지는 일이 없다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, long granted, long now) {
		if (end == null || members == null || members.isEmpty()) {
			return;
		}
		beginFight(granted);
		// 전투가 열린 바로 그 틱에는 아직 아무 주기도 시작되지 않았다.
		if (TrialRisks.elapsedSinceGrant(now, granted) <= 0L) {
			return;
		}

		Rift rift = active;
		// 다 탄 장판을 먼저 치운다. 남겨 두면 다음 선을 못 긋는다.
		if (rift != null && rift.ignited() && !fieldAlive(rift.ignitedAt(), now)) {
			extinguish(end, members);
			active = null;
			rift = null;
		}

		int remaining = TrialRisks.remainingTicks(now, granted, PERIOD_TICKS);
		long cycle = TrialRisks.strikeIndex(now, granted, PERIOD_TICKS);

		if (remaining <= LEAD_TICKS && cycle != plannedCycle) {
			// 이번 주기는 여기서 한 번만 판단한다. 못 그으면 그냥 지나간다.
			plannedCycle = cycle;
			// 앞 선이 아직 타고 있으면 긋지 않는다 — 선 둘이 겹치면 교차점이 한 틱에 두 번 맞는
			// 자리가 되고 worstCaseTickDamage 가 거짓이 된다. PERIOD_TICKS 가 예고 + 장판보다
			// 길어 실제로는 오지 않는 길이다.
			if (rift == null && remaining >= MIN_LEAD_TICKS) {
				rift = onGround(end, plan(cycle, end.getRandom().nextDouble()));
				active = rift;
			}
		}
		if (rift == null) {
			return;
		}

		// 선은 예고 중에도 타는 중에도 같은 모습으로 보인다. 그어진 자리가 곧 아픈 자리라
		// 「예고는 빨간 선, 장판은 다른 것」으로 갈리면 사람이 둘을 따로 배워야 한다.
		show(end, rift);
		if (!rift.ignited()) {
			warn(end, members, rift, remaining);
			if (!TrialRisks.firesAt(now, granted, PERIOD_TICKS)) {
				return;
			}
			rift = rift.ignitedAt(now);
			active = rift;
			ignite(end, members);
		}
		burn(end, members, rift, now);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>선은 좌표라 월드보다 오래 산다. 비우지 않으면 새 월드에서 <b>아무도 그은 적 없는
	 * 자리가 타고</b>, 그 증상은 「엔드에 들어가자마자 아프다」라는 엉뚱한 모양으로 나온다.
	 */
	public static void clearState() {
		active = null;
		plannedCycle = Long.MIN_VALUE;
		plannedFight = Long.MIN_VALUE;
	}

	/**
	 * 지난 전투의 찌꺼기를 버린다. 매 틱 불리고 전투가 바뀐 그 틱에만 실제로 지운다.
	 *
	 * <p>전투가 끝날 때 누가 비워 주기를 기다리지 않는다. 배선을 한 줄 빠뜨렸다고 다음 판의
	 * 브레스가 한 번 조용히 쉬면, 그 증상은 「가끔 안 나온다」라 아무도 못 잡는다.
	 *
	 * @param granted 전투가 열린 틱. 전투를 가르는 열쇠다
	 */
	static void beginFight(long granted) {
		if (granted == plannedFight) {
			return;
		}
		clearState();
		plannedFight = granted;
	}

	/** 시험용. 지금 선이 남아 있는가. */
	static @Nullable Rift active() {
		return active;
	}

	/** 시험용. 월드 없이 만든 선을 꽂아 둔다. */
	static void remember(@Nullable Rift rift) {
		active = rift;
	}

	/** 시험용. 어느 주기까지 판단이 끝났는가. */
	static long plannedCycle() {
		return plannedCycle;
	}

	/** 시험용. 주기 판단 기록만 남긴다. */
	static void notePlanned(long cycle) {
		plannedCycle = cycle;
	}

	// ------------------------------------------------------------------ 예고와 연출

	/**
	 * 경고 세 층을 올리고 선을 보여 준다.
	 *
	 * <p><b>선은 예고 내내 그린다.</b> {@code TrialRisks} 의 고리는 첫 층에서 소리만 내고 표식을
	 * 늦게 띄우지만, 이 패턴이 요구하는 행동은 「어느 쪽에 남을지 고르기」라 <b>선 자체가 고를
	 * 정보의 전부</b>다. 소리만 울리고 선이 없는 구간을 두면 그동안은 고를 것이 없다.
	 *
	 * <p>소리는 <b>사람마다 그 자리에서</b> 울린다. 바닐라 소리 사거리는 볼륨이 1 이하면 16칸인데
	 * 이 선은 80칸이라, 한 점에서 울리면 반대편에 선 사람에게 닿지 않는다. 파티클의 거리 제한을
	 * 끄는 것과 같은 이유다.
	 */
	private static void warn(ServerLevel end, List<ServerPlayer> members, Rift rift, int remaining) {
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage != null && TrialRisks.stageJustChanged(remaining)) {
			for (ServerPlayer member : members) {
				TrialWarning.sound(end, member.position(), stage);
			}
		}
		if (remaining == SWEEP_TICKS) {
			// 볼륨 4 면 사거리가 64칸이다. 「어느 쪽에서 날아오는가」가 이 소리의 몫이라 출발점에서
			// 울려야 하고, 그래서 사람마다가 아니라 한 점이다.
			end.playSound(null, rift.from().x, rift.from().y, rift.from().z,
					SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 4.0F, 0.8F);
		}
		if (remaining <= SWEEP_TICKS) {
			drawSweep(end, rift, sweepProgress(remaining));
		}
		if (remaining == TrialWarning.TICKS_SIDESTEP) {
			TrialWarning.shout(members, Component.literal("브레스가 아레나를 가릅니다 — 한쪽을 고르십시오"));
		}
	}

	/**
	 * 장판이 붙는 순간.
	 *
	 * <p>여기서 피해를 주지 않는다. 붙는 틱의 피해는 {@link #burn} 의 첫 점이 맡는다 — 두 곳에서
	 * 때리면 같은 순간에 두 번 맞는다.
	 */
	private static void ignite(ServerLevel end, List<ServerPlayer> members) {
		for (ServerPlayer member : members) {
			Vec3 at = member.position();
			end.playSound(null, at.x, at.y, at.z, SoundEvents.DRAGON_FIREBALL_EXPLODE,
					SoundSource.HOSTILE, 0.8F, 1.2F);
		}
	}

	/** 장판이 꺼지는 순간. 「이제 건너도 된다」를 알린다. */
	private static void extinguish(ServerLevel end, List<ServerPlayer> members) {
		for (ServerPlayer member : members) {
			Vec3 at = member.position();
			end.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH,
					SoundSource.HOSTILE, 0.7F, 0.8F);
		}
	}

	/**
	 * 선의 두 경계를 그린다.
	 *
	 * <p>가운데가 아니라 <b>양 경계</b>를 그리는 이유는, 사람이 알아야 하는 것이 「선이 어디
	 * 있는가」가 아니라 <b>「어디부터 아픈가」</b>이기 때문이다. 고리가 반경을 그려 주는 것과 같다.
	 *
	 * <p>색은 {@link TrialWarning.Colors#DEADLY} 하나뿐이다. 「서 있으면 죽는다」가 정확히 이
	 * 장판의 뜻이고, 규약에 없는 색을 새로 만들면 그 순간 규약이 장식이 된다.
	 *
	 * <p><b>긴 형태로 보낸다</b>({@code overrideLimiter=true}). 짧은 형태는 32칸에서 잘리는데 이
	 * 선은 80칸이라, 되돌리는 순간 <b>반대편 끝이 통째로 안 보인다</b> — 아레나가 갈렸다는 것을
	 * 보여 주는 것이 이 패턴의 전부이므로 그러면 패턴이 사라진다.
	 */
	private static void show(ServerLevel end, Rift rift) {
		ParticleOptions mark = TrialWarning.dust(TrialWarning.Colors.DEADLY);
		drawEdge(end, rift.leftEdge(), mark);
		drawEdge(end, rift.rightEdge(), mark);
	}

	private static void drawEdge(ServerLevel end, List<Vec3> edge, ParticleOptions mark) {
		for (Vec3 point : edge) {
			end.sendParticles(mark, true, false,
					point.x, point.y + GROUND_OFFSET, point.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 쓸고 지나가는 브레스.
	 *
	 * <p>선 전체를 한꺼번에 뿜지 않고 <b>지금까지 지나온 자리</b>의 앞머리에만 몰아 찍는다. 그래야
	 * 「드래곤이 한쪽에서 반대쪽으로 그었다」로 읽히고, 어느 쪽에서 오는지가 보인다.
	 *
	 * <p>이것도 긴 형태다. 브레스가 출발하는 경계는 반대편 사람에게서 80칸이다.
	 */
	private static void drawSweep(ServerLevel end, Rift rift, double progress) {
		Vec3 head = headAt(rift.from(), rift.to(), progress);
		ParticleOptions breath = PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F);
		end.sendParticles(breath, true, false, head.x, head.y + 0.5, head.z, 24,
				HALF_WIDTH * 0.5, 0.4, HALF_WIDTH * 0.5, 0.02);
		end.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, head.x, head.y + 0.5, head.z, 6,
				HALF_WIDTH * 0.5, 0.3, HALF_WIDTH * 0.5, 0.0);
	}

	// ------------------------------------------------------------------ 피해

	/**
	 * 장판을 밟고 있는 사람을 태운다.
	 *
	 * <h2>팀에게 한 번만</h2>
	 *
	 * <p>안에 선 사람을 모두 때리면 넷이 함께 건널 때 초당 16 이다. 그런데 <b>함께 건너는 것이
	 * 이 패턴에 대한 올바른 대응</b>이라, 그러면 올바른 대응이 전멸이 된다. 설계의 「같은 공격은
	 * 팀에게 한 번만」이 정확히 이 경우를 위해 있다.
	 *
	 * <p>장비도 공유라 누가 대표로 맞든 들어가는 피해가 같다. {@link SharedEffectDamage} 가 공유된
	 * 상태이상을 대표 한 명만 남기고 막는 것과 같은 결이다.
	 *
	 * <p>피해원에 가해 개체를 달지 않는다. 달면 {@link SharedAreaDamage} 가 대신 접어 주지만
	 * {@code dragon_breath} 는 {@code when_caused_by_living_non_player} 로 스케일링되어 어려움
	 * 난이도에서 값이 ×1.5 가 된다 — 하드코어는 난이도가 고정이라 적어 둔 값이 늘 ×1.5 다.
	 */
	private static void burn(ServerLevel end, List<ServerPlayer> members, Rift rift, long now) {
		if (!pulsesAt(rift.ignitedAt(), now)) {
			return;
		}
		for (ServerPlayer member : members) {
			if (!insideField(member.position(), rift.from(), rift.to(), HALF_WIDTH)) {
				continue;
			}
			member.hurtServer(end, end.damageSources().dragonBreath(), DAMAGE_PER_PULSE);
			// 하나의 장판은 하나의 공격이다. 나머지는 같은 공격의 두 번째 몫이라 세지 않는다.
			return;
		}
	}

	// ------------------------------------------------------------------ 선을 놓는다

	/**
	 * 월드를 모르는 선. 높이는 전부 0 이다.
	 *
	 * <p>지면을 읽는 부분과 <b>기하</b>를 갈라 둔다. 「중앙을 지나는가」·「양 끝이 경계에 닿는가」·
	 * 「점이 촘촘한가」는 월드 없이 답이 정해지는 계산이라, 떼어 두면 서버를 띄우지 않고 시험할 수
	 * 있다.
	 *
	 * @param angleRoll 0~1 의 굴림. 각도가 된다. 실전에서는 {@code end.getRandom()} 이 준다
	 */
	static Rift plan(long cycle, double angleRoll) {
		Vec3 axis = axisFor(angleRoll);
		Vec3 from = axis.scale(-TrialRisks.ARENA_RADIUS);
		Vec3 to = axis.scale(TrialRisks.ARENA_RADIUS);
		Vec3 side = sideOf(axis).scale(HALF_WIDTH);
		int points = linePoints(from.distanceTo(to));
		List<Vec3> left = new ArrayList<>(points);
		List<Vec3> right = new ArrayList<>(points);
		for (int index = 0; index < points; index++) {
			Vec3 spine = pointOn(from, to, index, points);
			left.add(spine.subtract(side));
			right.add(spine.add(side));
		}
		return new Rift(cycle, from, to, left, right, NOT_IGNITED);
	}

	/**
	 * 그 선을 실제 지면에 얹는다.
	 *
	 * <p>높이를 <b>그을 때 한 번만</b> 찾는다. 매 틱 하이트맵을 백 번 넘게 두드리면 21초 내내
	 * 청크를 뒤지게 되고, 어차피 장판이 사는 동안 지면은 바뀌지 않는다 — 이 패턴은 블록을 한 칸도
	 * 건드리지 않으니 더욱 그렇다.
	 */
	private static Rift onGround(ServerLevel end, Rift flat) {
		double base = groundY(end, 0.0, 0.0, FALLBACK_GROUND_Y);
		return new Rift(flat.cycle(),
				lift(end, flat.from(), base),
				lift(end, flat.to(), base),
				liftAll(end, flat.leftEdge(), base),
				liftAll(end, flat.rightEdge(), base),
				flat.ignitedAt());
	}

	private static List<Vec3> liftAll(ServerLevel end, List<Vec3> points, double base) {
		List<Vec3> lifted = new ArrayList<>(points.size());
		double carried = base;
		for (Vec3 point : points) {
			Vec3 on = lift(end, point, carried);
			carried = on.y;
			lifted.add(on);
		}
		return lifted;
	}

	private static Vec3 lift(ServerLevel end, Vec3 point, double fallback) {
		return new Vec3(point.x, groundY(end, point.x, point.z, fallback), point.z);
	}

	/** 그 자리의 지면 높이. 허공이면 하이트맵이 월드 바닥을 돌려주므로 넘겨받은 값을 쓴다. */
	private static double groundY(ServerLevel end, double x, double z, double fallback) {
		BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				BlockPos.containing(x, 0.0, z));
		return ground.getY() > end.getMinY() ? ground.getY() : fallback;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 굴림 하나를 아레나를 가르는 방향으로 바꾼다. 길이 1 이고 높이는 0 이다.
	 *
	 * <p>반 바퀴({@code π})만 쓴다. 선은 <b>양방향</b>이라 θ 와 θ+π 가 같은 선이고, 한 바퀴를 다
	 * 쓰면 같은 선이 두 번 나오는 셈이다. 나오는 <b>선</b>의 분포는 같지만 브레스가 어느 쪽에서
	 * 출발하는지는 굴림이 정하게 된다 — 그쪽이 덜 헷갈린다.
	 */
	static Vec3 axisFor(double angleRoll) {
		double angle = Math.max(0.0, Math.min(1.0, angleRoll)) * Math.PI;
		return new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
	}

	/** 그 방향에 수직인 방향. 장판 폭이 이쪽으로 뻗는다. */
	static Vec3 sideOf(Vec3 axis) {
		Vec3 flat = new Vec3(axis.x, 0.0, axis.z);
		if (flat.lengthSqr() < 1.0E-9) {
			return new Vec3(0.0, 0.0, 1.0);
		}
		Vec3 unit = flat.normalize();
		return new Vec3(-unit.z, 0.0, unit.x);
	}

	/**
	 * 선 위 {@code index} 번째 점. 0 이 {@code from}, 마지막이 {@code to} 다.
	 *
	 * <p>끝을 정확히 {@code to} 로 맞추는 것이 중요하다. 한 칸 모자라게 그리면 아레나 경계에 틈이
	 * 생기고, 사람은 그 틈으로 지나갈 수 있다고 읽는다.
	 */
	static Vec3 pointOn(Vec3 from, Vec3 to, int index, int points) {
		if (points <= 1) {
			return from;
		}
		double along = (double) Math.max(0, Math.min(points - 1, index)) / (points - 1);
		return from.add(to.subtract(from).scale(along));
	}

	/**
	 * 그 길이의 선에 찍을 점 수.
	 *
	 * <p>개수가 아니라 <b>간격</b>을 정하고 개수를 거기서 뽑는다. 개수를 고정하면 아레나를 넓히는
	 * 순간 같은 점이 더 긴 선에 흩어져 점선이 된다.
	 */
	static int linePoints(double length) {
		if (!(length > 0.0)) {
			return 0;
		}
		int wanted = (int) Math.ceil(length / LINE_POINT_GAP) + 1;
		return Math.max(2, Math.min(LINE_MAX_POINTS, wanted));
	}

	/** 그 길이에서 실제로 벌어지는 점 사이 거리(칸). 「선으로 읽히는가」를 숫자로 묻는 값이다. */
	static double lineGap(double length) {
		int points = linePoints(length);
		if (points < 2) {
			return 0.0;
		}
		return length / (points - 1);
	}

	/**
	 * 장판 안에 서 있는가. 선분까지의 거리가 폭 안이면 안이다.
	 *
	 * <p>세로는 보지 않는다 — {@code TrialRisks.insideMark} 와 같은 이유다. 표식은 바닥에 그려지고,
	 * 「표식 위에 떠 있었으니 안 맞는다」가 되면 표식이 거짓말한 것이 된다.
	 */
	static boolean insideField(Vec3 position, Vec3 from, Vec3 to, double halfWidth) {
		return distanceToSegment(position, from, to) <= Math.max(0.0, halfWidth);
	}

	/**
	 * 점에서 선분까지의 거리(위에서 본 거리).
	 *
	 * <p>직선이 아니라 <b>선분</b>이다. 무한 직선으로 재면 아레나 밖 허공 연장선에도 장판이 있는
	 * 셈이 되고, 브레스가 닿지 않은 자리가 아픈 것은 표식이 거짓말하는 것이다.
	 */
	static double distanceToSegment(Vec3 position, Vec3 from, Vec3 to) {
		double lineX = to.x - from.x;
		double lineZ = to.z - from.z;
		double lengthSqr = lineX * lineX + lineZ * lineZ;
		double toPointX = position.x - from.x;
		double toPointZ = position.z - from.z;
		if (lengthSqr < 1.0E-9) {
			return Math.sqrt(toPointX * toPointX + toPointZ * toPointZ);
		}
		double along = Math.max(0.0, Math.min(1.0,
				(toPointX * lineX + toPointZ * lineZ) / lengthSqr));
		double dx = toPointX - lineX * along;
		double dz = toPointZ - lineZ * along;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** 브레스가 지금 어디까지 왔는가. 0 이 출발점, 1 이 반대편 끝. */
	static Vec3 headAt(Vec3 from, Vec3 to, double progress) {
		double along = Math.max(0.0, Math.min(1.0, progress));
		return from.add(to.subtract(from).scale(along));
	}

	/**
	 * 쓸고 지나가는 비율.
	 *
	 * <p>남은 틱으로 센다. <b>도착이 곧 발동</b>이어야 브레스가 반대편에 닿는 틱과 장판이 붙는 틱이
	 * 같아지고, 그래야 「지나간 자리가 장판이 된다」가 눈에 맞는다.
	 */
	static double sweepProgress(int remainingTicks) {
		if (SWEEP_TICKS <= 0) {
			return 1.0;
		}
		if (remainingTicks >= SWEEP_TICKS) {
			return 0.0;
		}
		if (remainingTicks <= 0) {
			return 1.0;
		}
		return (double) (SWEEP_TICKS - remainingTicks) / SWEEP_TICKS;
	}

	/** 장판이 아직 살아 있는가. 붙은 틱부터 {@link #FIELD_TICKS} 동안이다. */
	static boolean fieldAlive(long ignitedAt, long now) {
		if (ignitedAt == NOT_IGNITED) {
			return false;
		}
		long burned = now - ignitedAt;
		return burned >= 0L && burned < FIELD_TICKS;
	}

	/** 이번 틱에 피해가 들어가는가. 붙는 그 틱이 첫 점이다. */
	static boolean pulsesAt(long ignitedAt, long now) {
		if (!fieldAlive(ignitedAt, now) || DAMAGE_PERIOD_TICKS <= 0) {
			return false;
		}
		return (now - ignitedAt) % DAMAGE_PERIOD_TICKS == 0L;
	}

	/** 장판 하나가 끝까지 서 있는 사람에게 주는 점의 수. */
	static int pulseCount() {
		if (DAMAGE_PERIOD_TICKS <= 0) {
			return 0;
		}
		return (FIELD_TICKS + DAMAGE_PERIOD_TICKS - 1) / DAMAGE_PERIOD_TICKS;
	}

	/**
	 * 한 사람이 이 패시브에게서 <b>한 틱에</b> 받을 수 있는 가장 큰 피해.
	 *
	 * <p>{@code TrialRisks.worstCaseTickDamage} 와 같은 몫이다 — 「즉사 메커닉 0개」가 지켜지는지를
	 * 값에서 직접 계산해 두면 시험이 붙잡을 수 있다. 값을 올리는 사람은 피해만 보고 겹침 수를 보지
	 * 않는다.
	 */
	static float worstCaseTickDamage() {
		if (DAMAGE_PER_PULSE <= 0.0F || MAX_CONCURRENT_RIFTS <= 0) {
			return 0.0F;
		}
		return DAMAGE_PER_PULSE * MAX_CONCURRENT_RIFTS;
	}
}
