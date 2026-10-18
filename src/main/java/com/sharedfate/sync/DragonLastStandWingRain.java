package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 「폭풍 날갯짓」({@code WING_BEAT})에 얹은 <b>종말의 비</b> — 날갯짓 동안 하늘에서 빨간 구체가 떨어진다.
 *
 * <h2>사람 말 (2026-10-18)</h2>
 *
 * <p><b>「날개 퍼덕이기에 종말의 비 만든 거, 종말의 비를 날개 퍼덕이기 할 때 떨구는 거야. 주변에 번개처럼,
 * 지속시간 동안 3차례, 개수는 6개 정도로 한번 추가해 보자」</b>. 그래서
 *
 * <ul>
 *   <li><b>무엇을</b> — 카드 「종말의 비」({@code sharedfate:end_rain})의 낙하 그대로다. 20칸 위에서
 *       빨간 구체가 등속으로 내려와 땅에 닿는 틱에 터지고, 바닥에는 보라 반투명 면이 깔린다. 피해·반경·
 *       예고 길이는 <b>카드에서 읽고</b>({@link #CARD}) 그리기·터뜨리기는 {@link TrialEndRain} 의 것을
 *       그대로 부른다 — 두 벌로 짜면 언젠가 한쪽만 고쳐진다</li>
 *   <li><b>어디에</b> — 상시 번개처럼 <b>드래곤 둘레</b>다. 자리 고르는 법도 번개와 같다(드래곤 자리 +
 *       {@code TrialRisks.arenaOffset} · 하이트맵 · 허공이면 버림 · 다시 굴리지 않음). 굴리는 반경은
 *       {@code 마지막 안전지대 18 − 반경 2.5 = 15.5} 라 표식이 지대 밖으로 삐져나가지 않는다</li>
 *   <li><b>몇 번 · 몇 곳</b> — 세 차례({@link #ROUNDS}), 차례마다 여섯 곳({@link #SPOTS})</li>
 * </ul>
 *
 * <h2>시각표 — 세 번째가 패턴 안에 떨어진다</h2>
 *
 * <p>패턴 길이 100틱은 그대로다. 마지막 충격파가 84틱이고 그 몫이 끝나는 것이 96틱이라
 * ({@code WING_PULSES × WING_PULSE_TICKS}), <b>마지막 착탄을 96틱</b>에 맞추고 세 차례를 고르게 벌렸다.
 *
 * <pre>
 *   차례   열림(예고 시작)   착탄
 *   1           0            30
 *   2          33            63
 *   3          66            96   ← 패턴 끝 100 보다 4틱 앞
 * </pre>
 *
 * <ul>
 *   <li>간격 {@link #ROUND_TICKS} = (96 − 30) ÷ 2 = <b>33틱</b>. 예고 30틱보다 길어 <b>두 차례가 한 번도
 *       함께 살아 있지 않다</b> — 점 예산과 바닥 면이 한 차례 몫이면 된다</li>
 *   <li>착탄 간격 33틱이 바닐라 피격 쿨타임 10틱보다 길어 <b>차례끼리 서로를 먹지 않는다</b></li>
 *   <li>패턴이 끝나기 전에 마지막이 떨어지므로 패턴 길이 · HUD 시전 바(예고 0 · 진행 100) · {@code allowed()}
 *       · 다음 패턴까지의 쉼(2~4초)이 <b>하나도 안 바뀐다.</b> 다음 패턴과 구체가 겹치지 않는다</li>
 * </ul>
 *
 * <h2>⚠ 같은 틱에 착탄과 다음 열림을 함께 하지 않는다</h2>
 *
 * <p>제 시각이면 둘은 3틱 떨어져 있지만, 렉·멈춤 뒤에는 늦게 들어온 한 틱에 둘이 몰릴 수 있다. 그러면 그
 * 틱이 착탄 48 + 구체 24 = 72점이 된다. 그래서 착탄한 틱에는 열지 않고 다음 틱에 연다 — {@link #points} 가
 * 「둘 중 바쁜 쪽」만 세도 되는 근거가 이 한 줄이다.
 *
 * <h2>점 예산 — 착탄 점을 카드가 실제로 쓰는 8 로</h2>
 *
 * <p>{@code TrialEndRain.impactPoints} 는 지점이 여섯이면 지점마다 12점(상한)을 준다 — 72점. 그대로 얹으면
 * 날갯짓 최악이 228 + 72 = 300 이고 번개 70 · 파도 37 을 더해 <b>407</b> 이라 이 페이즈가 지키는 아래 끝 400 을
 * 넘는다. 카드 「종말의 비」는 90곳이라 실제로는 지점마다 <b>8점</b>(800 ÷ 90)으로 터지므로, 그 값을 그대로
 * 받아({@link #IMPACT_POINTS}) 착탄 틱 6 × 8 = <b>48</b> — 날갯짓 276 · 패턴 최악 346 · 파도 포함 <b>383</b> 이다.
 * 구체는 지점마다 4점 그대로(24)다. 「종말의 비」 카드는 최후의 저항 동안 돌지 않는다(시련이 전부 멈춘다) —
 * 카드의 360 과 이 낙하가 한 틱에 겹치는 일은 없다.
 *
 * <h2>피해와 그 셈</h2>
 *
 * <ul>
 *   <li>피해원 {@code explosion(null, null)} · 적힌 값 23 · 반경 2.5 — 카드와 같고 무장 기준 한 대
 *       <b>6.77</b>(하드 1.5배 → 34.5 → 방어). <b>세 대에 전멸</b>이라는 이 판의 큰 카드 기준과 같다</li>
 *   <li>한 자리는 <b>팀에 한 발</b>이다({@code TrialEndRain.detonate}). 여섯 자리가 겹치면 한 사람이 여러 번
 *       불리지만 같은 틱의 둘째부터는 피격 쿨타임이 먹는다 — 셈은 그래도 나쁜 쪽(여섯 겹)으로 적는다
 *       ({@link #worstCaseTickDamage})</li>
 *   <li>⚠ <b>상시 번개와는 쿨타임이 겹칠 수 있다</b> — 따로 도는 시계라 시각으로 못 피한다. 번개 24.5 뒤
 *       10틱 안의 착탄은 하드에서 차액 10 만, 착탄 뒤 10틱 안의 번개는 통째로 막힌다. 흡입 터짐과 번개가
 *       이미 그렇게 두고 있는 구멍과 같은 갈래다(문서 6장 「바닐라 피격 쿨타임 10틱」의 ②)</li>
 *   <li>날갯짓이 미는 중에 떨어지므로 <b>밀려서 표식 안으로 들어가 맞는 일</b>이 생긴다. 사람이 원한
 *       긴장이다</li>
 * </ul>
 */
final class DragonLastStandWingRain {

	/** 바닥 면을 세우고 지울 때의 열쇠. 카드 「종말의 비」의 열쇠(팀마다)와 겹치지 않는 이름이다. */
	static final String PANEL_KEY = "sharedfate:last_stand_wing_rain";

	/** 차례 수. 사람이 정한 값이다(「지속시간 동안 3차례」). */
	static final int ROUNDS = 3;

	/** 차례마다 떨어지는 곳. 사람이 정한 값이다(「개수는 6개 정도로」). */
	static final int SPOTS = 6;

	/** 카드 「종말의 비」의 값. 피해·반경·예고 길이를 여기서 읽는다 — 카드를 고치면 날갯짓도 따라간다. */
	static final TrialCatalog.Risk.EndRain CARD = card();

	/** 지점마다의 착탄 점 수 — 카드가 실제로 터지는 값(90곳이라 8)이다. 까닭은 클래스 설명의 「점 예산」. */
	static final int IMPACT_POINTS = TrialEndRain.impactPoints(CARD);

	/**
	 * 마지막 착탄 시각(패턴 시작부터의 틱). <b>96</b> — 마지막 충격파(84틱)의 몫이 끝나는 틱이다.
	 * 패턴 길이 100 안이다.
	 */
	static final int LAST_LAND_STEP =
			DragonLastStandPatterns.WING_PULSES * DragonLastStandPatterns.WING_PULSE_TICKS;

	/** 차례 사이(틱). 첫 차례를 0틱에 열고 마지막을 {@link #LAST_LAND_STEP} 에 떨어뜨리게 고르게 나눈 값 — 33. */
	static final int ROUND_TICKS = (LAST_LAND_STEP - CARD.warnTicks()) / Math.max(1, ROUNDS - 1);

	/**
	 * 이 패턴이 쓰는 비 한 벌. 모양은 카드의 기록을 그대로 쓰고 <b>지점 수와 간격만</b> 우리 것이다.
	 * {@code TrialEndRain} 의 점 셈({@code tickPoints} · {@code impactPoints})과 피해 셈이 이 값을 받는다.
	 */
	static final TrialCatalog.Risk.EndRain RAIN = new TrialCatalog.Risk.EndRain(LAST_LAND_STEP,
			ROUND_TICKS, ROUND_TICKS, SPOTS, SPOTS, CARD.warnTicks(), CARD.damage(), CARD.radius());

	/**
	 * 자리를 굴리는 반경(칸). <b>15.5 이고 새 숫자가 아니다</b> — {@code 마지막 안전지대 18 − 반경 2.5} 다.
	 * 상시 번개({@code LIGHTNING_FIELD_RADIUS})와 같은 식이라 표식이 마지막 지대 밖으로 삐져나가지 않는다.
	 */
	static final double FIELD_RADIUS =
			DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1] - CARD.radius();

	// ------------------------------------------------------------------ 한 판 동안 붙잡아 두는 것

	/** 지금 비를 내리는 날갯짓이 시작한 틱. 바뀌면 새 판이라 처음부터 센다. 패턴은 한 번에 하나다. */
	private static long rainFor = Long.MIN_VALUE;
	/** 마지막으로 연 차례. 아직 안 열었으면 −1. */
	private static int round = -1;
	/** 떨어지는 중인 자리. 비어 있으면 떨어지는 것이 없다 — 예고가 도는 동안 자리가 움직이면 예고가 아니다. */
	private static List<Vec3> spots = List.of();

	private DragonLastStandWingRain() {
	}

	/** 월드가 바뀌거나 서버가 내려갈 때 — {@code DragonLastStandPatterns.clearState} 가 부른다. 면은 개체를 들고 있어 월드 없이 지운다. */
	static void clearState() {
		rainFor = Long.MIN_VALUE;
		round = -1;
		spots = List.of();
		TrialEndRainPanel.drop(PANEL_KEY);
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/** 차례 {@code index} 가 열리는(예고가 시작하는) 틱. */
	static int openStep(int index) {
		return index * ROUND_TICKS;
	}

	/** 차례 {@code index} 가 떨어지는 틱. */
	static int landStep(int index) {
		return openStep(index) + CARD.warnTicks();
	}

	/** 지금 열려 있어야 할 가장 늦은 차례. 첫 차례 전이면 −1(0틱에 열리므로 음수 틱에만). */
	static int dueRound(int step) {
		if (step < 0) {
			return -1;
		}
		return Math.min(ROUNDS - 1, step / Math.max(1, ROUND_TICKS));
	}

	/** {@link #tick} 이 이 틱에 하는 일. {@link #next} 가 고른다. */
	enum Move {
		/** 떨어지는 중인 차례가 착탄한다(면을 거두고 터뜨린다). 같은 틱에 다음 차례를 열지 않는다. */
		LAND,
		/** 새 차례를 연다(자리를 고르고 면을 세운다). */
		OPEN,
		/** 아무것도 바꾸지 않는다 — 떨어지는 중이면 구체만 한 칸 내린다. */
		HOLD
	}

	/**
	 * {@link #tick} 의 차례 판단을 값으로만 적은 것 — 월드 없이 시험이 시각표를 통째로 굴린다(2026-10-18 밤).
	 *
	 * @param falling 떨어지는 중인 자리가 있는가
	 * @param round   마지막으로 연 차례(아직이면 −1)
	 * @param step    날갯짓이 시작한 뒤 흐른 틱
	 */
	static Move next(boolean falling, int round, int step) {
		if (falling && step >= landStep(round)) {
			return Move.LAND;
		}
		int due = dueRound(step);
		if (!falling && due > round && step < landStep(due)) {
			return Move.OPEN;
		}
		return Move.HOLD;
	}

	/** 드래곤을 중심으로 한 자리 하나의 상대 좌표. 시험이 굴림을 훑어 모든 표식이 마지막 지대 안인지 본다. */
	static Vec3 spotOffset(double roll, double otherRoll) {
		return TrialRisks.arenaOffset(roll, otherRoll, FIELD_RADIUS);
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 날갯짓 한 틱. {@code DragonLastStandPatterns.wingBeat} 가 충격파 틱이든 아니든 매 틱 부른다.
	 *
	 * <p>순서는 <b>착탄 → (착탄이 없었으면) 다음 차례 열기 → 구체</b>다. 늦게 들어온 틱이면 착탄은
	 * 「지났다」로 터지고, 예고 시간이 남지 않은 차례는 열지 않고 건너뛴다(예고 없는 낙하는 만들지 않는다).
	 *
	 * @param center 드래곤 자리
	 * @param at     이 날갯짓이 시작한 틱
	 * @param step   시작부터 흐른 틱
	 */
	static void tick(ServerLevel end, List<ServerPlayer> members, Vec3 center, long at, int step) {
		if (rainFor != at) {
			// 새 날갯짓이다. 지난 판의 면은 퓨즈로도 사라지지만 기다리지 않는다.
			TrialEndRainPanel.drop(PANEL_KEY);
			rainFor = at;
			round = -1;
			spots = List.of();
		}
		Move move = next(!spots.isEmpty(), round, step);
		if (move == Move.LAND) {
			TrialEndRainPanel.drop(PANEL_KEY);
			for (Vec3 spot : spots) {
				TrialEndRain.detonate(end, members, spot, RAIN, IMPACT_POINTS);
			}
			TrialEndRain.impactSound(end, members);
			spots = List.of();
			// 같은 틱에 다음 차례를 열지 않는다 — 착탄과 구체가 한 틱에 겹치지 않게(클래스 설명).
			return;
		}
		if (move == Move.OPEN) {
			round = dueRound(step);
			spots = pickSpots(end, center);
			if (!spots.isEmpty()) {
				TrialEndRainPanel.raise(end, PANEL_KEY, spots, RAIN.radius(), RAIN.warnTicks());
			}
		}
		if (spots.isEmpty()) {
			return;
		}
		warn(end, members, landStep(round) - step);
	}

	/** 구체를 한 칸 내리고, 예고 단계가 바뀌는 틱에 사람마다 경고음을 낸다 — 카드의 {@code warn} 과 같다. */
	private static void warn(ServerLevel end, List<ServerPlayer> members, int remaining) {
		if (remaining <= 0) {
			return;
		}
		ParticleOptions core = TrialEndRain.coreParticle();
		ParticleOptions halo = TrialEndRain.orbParticle();
		for (Vec3 spot : spots) {
			TrialEndRain.dropOrb(end, spot, core, halo, remaining, RAIN);
		}
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage == null || !TrialRisks.stageJustChanged(remaining, RAIN.warnTicks())) {
			return;
		}
		for (ServerPlayer member : members) {
			TrialWarning.soundFor(end, member, stage);
		}
	}

	/**
	 * 여섯 곳을 고른다. 상시 번개의 {@code pickLightningSpots} 와 같은 법이다 — 겹침을 허용하고, 허공이면
	 * 그 자리를 버리고 다시 굴리지 않는다(드래곤 둘레 15.5칸은 바닐라 엔드에서 통째로 엔드스톤이다).
	 */
	private static List<Vec3> pickSpots(ServerLevel end, Vec3 center) {
		List<Vec3> picked = new ArrayList<>(SPOTS);
		RandomSource random = end.getRandom();
		for (int index = 0; index < SPOTS; index++) {
			Vec3 offset = spotOffset(random.nextDouble(), random.nextDouble());
			double x = center.x + offset.x;
			double z = center.z + offset.z;
			BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					BlockPos.containing(x, 0.0, z));
			// 허공이면 하이트맵이 월드 바닥을 돌려준다.
			if (ground.getY() > end.getMinY()) {
				picked.add(new Vec3(x, ground.getY(), z));
			}
		}
		return picked;
	}

	// ------------------------------------------------------------------ 예산과 피해 셈

	/**
	 * 한 틱에 가장 많이 나가는 점 수 — <b>구체 틱(6 × 4 = 24)과 착탄 틱(6 × 8 = 48) 가운데 바쁜 쪽</b>.
	 * 둘이 한 틱에 겹치지 않는 것은 {@link #tick} 이 지킨다. 바닥 면은 디스플레이 개체라 0점이다.
	 */
	static int points() {
		return Math.max(TrialEndRain.tickPoints(RAIN), impactTickPoints());
	}

	/** 착탄 틱의 점 수. */
	static int impactTickPoints() {
		return SPOTS * IMPACT_POINTS;
	}

	/**
	 * 한 사람이 이 낙하에게서 한 틱에 받을 수 있는 가장 큰 <b>적힌</b> 피해 — {@code TrialRisks} 의 셈 그대로
	 * (23 × 여섯 겹 = 138). 실제로는 같은 틱의 둘째부터 피격 쿨타임이 먹어 한 발(무장 6.77)이다.
	 */
	static float worstCaseTickDamage() {
		return TrialRisks.worstCaseTickDamage(RAIN);
	}

	/** 카드 「종말의 비」를 찾는다. 없으면 빌드 시험에서 바로 드러나게 터진다. */
	private static TrialCatalog.Risk.EndRain card() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:end_rain");
		if (trial != null) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				if (risk instanceof TrialCatalog.Risk.EndRain rain) {
					return rain;
				}
			}
		}
		throw new IllegalStateException("카드 「종말의 비」(sharedfate:end_rain)가 없다");
	}
}
