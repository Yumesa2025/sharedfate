package com.sharedfate.sync;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 십자 균열의 <b>빨간 투명 면</b>. 부채꼴 브레스의 면({@link DragonLastStandConePanel})과 <b>같은 판</b>을
 * 십자 모양으로 깐다.
 *
 * <h2>사람 말 (2026-10-04)</h2>
 *
 * <p><b>「이것도 브레스처럼 투명땅으로 표시했으면 해」</b>. 같은 날 십자가 <b>한 회차에 둘씩</b>
 * 터지게 바뀌어(<b>「그 두 십자를 한번에 같이 발동시켜」</b>) 한 회차에 깔리는 것이 팔 <b>여덟</b>
 * 개다 — {@code DragonLastStandPatterns.CROSS_ANGLES} 를 볼 것.
 *
 * <p>그 대신 <b>먼지 선과 흰 기둥 벽을 걷어냈다</b>(사람이 「투명 면이 그 일을 대신한다」고 정했다).
 * 터질 때 솟는 기둥과 균열음은 남는다 — 그것은 「어디가 위험한가」가 아니라 「지금 터졌다」·「곧
 * 터진다」를 말하는 갈래라 면이 대신하지 못한다.
 *
 * <h2>판은 부채꼴 쪽 것을 <b>그대로</b> 쓴다</h2>
 *
 * <p>개체 클래스({@code DragonLastStandConePanel.Panel}) · NBT({@code shapeTag}) · 변환
 * ({@code transformOf}) · 블록(붉은 색유리) · 두께 · 띄우는 높이가 전부 그쪽 한 벌이다. 「저장 안 함 ·
 * 스스로 타 없어짐」 두 겹이 클래스 하나에 있어야 <b>한쪽만 잃는 일</b>이 없고, 두 면이 같은 판이라야
 * 사람이 「빨간 투명 바닥 = 서 있으면 맞는다」를 <b>한 번만</b> 배운다.
 *
 * <h2>⚠ 팔 여덟이 겹치지 않게 — <b>가운데는 정팔각형</b>이다</h2>
 *
 * <p>반투명 판이 겹치면 두 겹이 되어 얼룩진다(부채꼴이 띠를 부채살이 아니라 가로 띠로 자른 까닭이
 * 그것이다). 십자 둘을 그냥 겹쳐 깔면 가운데에서 <b>여덟 겹</b>이 된다. 그래서 팔을 가운데에서
 * 떼어 내고 그 자리를 <b>정다각형 하나</b>로 메운다.
 *
 * <ul>
 *   <li>폭 {@code 2h} 짜리 띠가 {@code s} 도 간격으로 놓이면 이웃한 둘이 겹치는 끝이 중심에서
 *       {@code h · cot(s/2)} 다. 한 회차에 십자 둘이면 {@code s = 45} 라
 *       {@code 1.5 × cot 22.5° = 3.621칸} 이다({@link #hubApothem})</li>
 *   <li>팔은 그 거리에서 시작한다. 그러면 팔끼리는 <b>한 점도</b> 겹치지 않는다</li>
 *   <li>가운데는 <b>변심거리가 그 값인 정 {@code 4n} 각형</b>이다(십자 둘이면 팔각형). 그 변이
 *       팔의 시작선과 <b>정확히</b> 같은 선이고 변의 반길이가 {@code a · tan(s/2) = h} 라 <b>팔 폭과
 *       글자 그대로 같다</b> — 틈도 겹침도 없이 맞물린다</li>
 *   <li>⚠ 그 다각형은 <b>전부 맞는 자리</b>다. 중심과 변 하나가 이루는 삼각형마다 그 변에 수직인
 *       팔의 띠 안에 들어가기 때문이다(삼각형의 옆 거리가 {@code 중심선 거리 × tan(s/2) ≤ h}).
 *       곧 「칠한 곳은 언제나 맞는 곳」이 부채꼴과 같은 약속으로 남는다</li>
 * </ul>
 *
 * <p>다각형은 상자 하나로 못 그리므로 부채꼴처럼 <b>띠로 잘라 안쪽에서</b> 채운다
 * ({@link #hubStrips}). 띠 폭이 그 띠 안에서 가장 좁은 곳이라 다각형 밖으로 나가지 않는다.
 *
 * <h2>판 수 — 부채꼴과 <b>같은 기준</b>으로 센다</h2>
 *
 * <p>부채꼴은 깊이 {@code DragonLastStandConePanel.STRIP_DEPTH}(1칸)마다 지표를 한 번 재어 띠
 * 하나를 세우고 그것이 <b>18장</b>이다. 여기도 팔을 같은 1칸 마디로 잘라 마디마다 지표를 잰다 —
 * 판 한 장은 평면이라 지형을 못 따라가기 때문이다. 그대로 세우면 팔 하나가 37마디라
 * {@code 8 × 37 + 가운데 8 = 304장}이다.
 *
 * <p>그래서 <b>높이가 같은 마디를 한 장으로 잇는다</b>({@link #runs}). 지표가 같은 마디는 같은 높이에
 * 놓이므로 이어도 모양이 한 톨도 안 달라지고, 평평한 엔드 섬에서는 팔 하나가 몇 장으로 줄어든다.
 * ⚠ <b>304 는 천장이고 평소 값이 아니다</b> — 마디마다 높이가 다른 최악의 지형에서만 닿는다.
 *
 * <h2>지우는 길</h2>
 *
 * <ul>
 *   <li><b>그 회차가 터지는 틱</b> — {@code DragonLastStandPatterns.fireRound}. 터진 뒤의 바닥은
 *       안전하므로 한 틱도 더 남기지 않는다(부채꼴 면과 같은 규칙)</li>
 *   <li><b>{@code DragonLastStandPatterns.clearState()}</b> — 전투가 닫힐 때 · 월드가 바뀌거나 서버가
 *       내려갈 때. {@code SERVER_STOPPED} 에서도 불리므로 {@link #LIVE} 가 개체를 <b>들고</b> 있는다</li>
 *   <li><b>심지</b>({@link #FUSE_TICKS}) — 위가 전부 실패했을 때의 바닥</li>
 *   <li><b>저장 안 함</b> — 판 클래스가 {@code shouldBeSaved()} 를 거짓으로 돌려준다</li>
 * </ul>
 *
 * <p>⚠ 하나가 더 있다 — {@code DragonLastStand.onServerStopping} 이 월드가 살아 있는 자리에서
 * {@code DragonLastStandConePanel.drop()} 다음 줄로 {@code DragonLastStandCrossPanel.drop()} 을
 * 부른다. 처음에는 그 파일을 만질 수 없어 이쪽만 그 줄이 없었고(저장 안 함 · 심지 · {@code clearState}
 * 셋이 이미 막고 있어 남는 판은 없었다), 지금은 부채꼴과 같은 다섯 겹이다.
 */
public final class DragonLastStandCrossPanel {

	/**
	 * 팔 한 마디의 깊이(칸). <b>부채꼴 띠와 같은 값</b>이다 — 「같은 기준으로 센다」가 이 한 줄이다.
	 *
	 * <p>그쪽 근거가 그대로 온다. 이 값이 지표를 재는 해상도라, 1칸이면 면이 바닥 표식 자신의 해상도
	 * 보다 더 틀리지 않는다.
	 */
	static final double SEGMENT_DEPTH = DragonLastStandConePanel.STRIP_DEPTH;

	/**
	 * ⚠ 판을 안쪽으로 당기는 <b>한 비트</b>(칸). 부채꼴의 {@code INSET} 과 같은 값이고 같은 까닭이다.
	 *
	 * <p>판 가장자리가 판정 경계(반폭 1.5 · 사거리 40)에 <b>정확히</b> 놓이면 「칠한 곳은 언제나 맞는
	 * 곳」이 부동소수 마지막 비트에 달려 시험으로 물을 수 없다. 팔과 가운데 사이의 맞물림도 이만큼
	 * 떨어뜨려 <b>겹침이 0</b>이 되게 한다.
	 */
	private static final double INSET = 1.0E-3;

	/**
	 * ⚠ <b>스스로 타 없어지는 심지</b>(틱). 십자 균열 한 판의 길이다.
	 *
	 * <p>실제로 필요한 것은 가장 긴 예고(첫 회차 60틱)뿐이고 그 회차가 터지는 틱에 지운다. 패턴 길이
	 * 전체를 쓰는 것은 부채꼴이 「예고 + 불꽃」을 쓰는 것과 같은 여유이고, 값을
	 * {@code DragonLastStandPatterns.crossDurationTicks()} 에서 직접 읽으므로 회차나 예고를 고치는
	 * 사람이 여기를 따로 고칠 일이 없다.
	 */
	static final int FUSE_TICKS = DragonLastStandPatterns.crossDurationTicks();

	/**
	 * ⚠ <b>지금 서 있는 판들.</b> 개체를 <b>들고</b> 있는다 — 까닭은
	 * {@code DragonLastStandConePanel} 의 같은 칸에 있다({@code SERVER_STOPPED} 에서는 월드에서 찾을 수
	 * 없다). 정적이라 월드보다 오래 산다. {@link #drop()} 으로 반드시 비운다.
	 */
	private static final List<DragonLastStandConePanel.Panel> LIVE = new ArrayList<>();

	private DragonLastStandCrossPanel() {
	}

	/**
	 * 판 하나. 부채꼴 좌표계의 띠({@code Strip})와 그 띠를 놓을 방향(도)이다.
	 *
	 * @param strip 띠. {@code near}~{@code far} 가 {@code yaw} 앞 방향, {@code halfWidth} 가 옆이다
	 * @param yaw   {@code (sin, −cos)} 이 앞인 방향 — 이 저장소의 규약이다
	 */
	record Piece(DragonLastStandConePanel.Strip strip, float yaw) {
	}

	// ------------------------------------------------------------------ 세우고 지우기

	/**
	 * 한 회차의 십자들을 바닥에 깐다. <b>그 회차의 예고 첫 틱에 한 번</b>이다.
	 *
	 * <p>이미 서 있는 것이 있으면 먼저 지운다 — 앞 회차가 터지는 틱과 다음 회차의 예고가 시작하는
	 * 틱이 같아(「터지는 틱과 다음 예고가 시작하는 틱이 같다」) 정상 경로에서도 부르는 순서가
	 * 「지우고 → 세우고」다.
	 *
	 * @param center 십자의 중심. 드래곤을 못박아 둔 자리다
	 * @param angles 그 회차에 <b>동시에</b> 터지는 십자들의 기준 각도(도). 고르게 벌어져 있어야 한다
	 */
	static void raise(ServerLevel end, Vec3 center, double[] angles) {
		drop();
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		for (Piece piece : hubPieces(angles)) {
			// 가운데 띠도 띠마다 제 가운데의 지표를 한 번 잰다. 허공이면 그 띠는 세우지 않는다.
			Vec3 middle = DragonLastStandConePanel.world(0.0, piece.strip().middle(), piece.yaw());
			int surface = ground.surfaceAt(end, center.x + middle.x, center.z + middle.z);
			if (surface != TrialEnderPulse.NO_GROUND) {
				place(end, center, piece, surface);
			}
		}
		double apothem = hubApothem(angles);
		int segments = armSegmentCount(apothem);
		for (int arm = 0; arm < armCount(angles); arm++) {
			float yaw = armYaw(angles, arm);
			int[] surfaces = new int[segments];
			for (int index = 0; index < segments; index++) {
				double along = (segmentNear(index, apothem) + segmentFar(index, apothem)) * 0.5;
				Vec3 at = DragonLastStandConePanel.world(0.0, along, yaw);
				surfaces[index] = ground.surfaceAt(end, center.x + at.x, center.z + at.z);
			}
			// 높이가 같은 마디를 한 장으로 잇는다. 허공 마디는 건너뛴다 — 허공 위에 빨간 판이 뜨면
			// 「저기가 바닥이다」라고 거짓말을 한다.
			for (int[] run : runs(surfaces)) {
				DragonLastStandConePanel.Strip strip = new DragonLastStandConePanel.Strip(
						segmentNear(run[0], apothem), segmentFar(run[1], apothem), armHalfWidth());
				place(end, center, new Piece(strip, yaw), surfaces[run[0]]);
			}
		}
	}

	/** 판 한 장을 세운다. 부채꼴과 같은 판 · 같은 변환 · 같은 높이다. */
	private static void place(ServerLevel end, Vec3 center, Piece piece, int surface) {
		DragonLastStandConePanel.Panel panel = new DragonLastStandConePanel.Panel(end, FUSE_TICKS);
		panel.snapTo(new Vec3(center.x, surface + DragonLastStandConePanel.GROUND_OFFSET, center.z));
		panel.dress(end, DragonLastStandConePanel.transformOf(piece.strip(), piece.yaw()));
		end.addFreshEntity(panel);
		LIVE.add(panel);
	}

	/**
	 * 서 있는 판을 모두 지운다. <b>월드를 만지지 않는다</b> — 들고 있는 개체에게 직접 말한다.
	 * {@code SERVER_STOPPED} 에서도 안전하다(부채꼴 쪽 {@code drop} 과 같은 까닭).
	 */
	static void drop() {
		for (DragonLastStandConePanel.Panel panel : LIVE) {
			if (!panel.isRemoved()) {
				panel.discard();
			}
		}
		LIVE.clear();
	}

	/** 시험이 들여다보는 곳. 지금 판이 몇 장 서 있는가. */
	static int liveCount() {
		return LIVE.size();
	}

	// ------------------------------------------------------------------ 기하학 (월드 없이 도는 계산)

	/** 한 회차의 팔 수. 십자 하나가 팔 넷이다. */
	static int armCount(double[] angles) {
		return 4 * angles.length;
	}

	/** 이웃한 팔 사이의 각도(도). 십자 둘이면 45 다. */
	static double armSpacing(double[] angles) {
		return 360.0 / armCount(angles);
	}

	/**
	 * {@code arm} 번째 팔의 방향(도). 첫 십자의 기준 각도에서 {@link #armSpacing} 씩 돈다.
	 *
	 * <p>십자들이 고르게 벌어져 있다는 것({@code DragonLastStandPatterns.CROSS_ANGLES} 의 시험이
	 * 붙든다)이 있어야 이 팔들이 <b>정확히 그 십자들의 선</b>이 된다.
	 */
	static float armYaw(double[] angles, int arm) {
		return (float) (angles[0] + arm * armSpacing(angles));
	}

	/**
	 * ⚠ 가운데 다각형의 <b>변심거리</b>(칸). 팔이 여기서 시작한다.
	 *
	 * <p>{@code h · cot(s/2)} — 이웃한 팔 둘이 더는 겹치지 않는 거리다. 십자 둘(팔 여덟)이면
	 * <b>3.621칸</b>, 십자 하나(팔 넷)였다면 반폭 그대로 1.5칸(3×3 정사각형)이다.
	 */
	static double hubApothem(double[] angles) {
		return DragonLastStandPatterns.CROSS_HALF_WIDTH
				/ Math.tan(Math.toRadians(armSpacing(angles) / 2.0));
	}

	/** 팔 판의 반폭(칸). 판정 반폭에서 한 비트 당긴다 — {@link #INSET} 을 볼 것. */
	static double armHalfWidth() {
		return DragonLastStandPatterns.CROSS_HALF_WIDTH - INSET;
	}

	/**
	 * 팔 판이 끝나는 거리(칸).
	 *
	 * <p>{@code DragonLastStandPatterns.insideCross} 가 <b>중심에서 잰 거리</b>로 사거리를 자르므로
	 * 판 바깥 귀퉁이({@code far, ±h})까지 사거리 원 안이어야 한다 — {@code √(R² − h²)} 이다.
	 */
	static double armFar() {
		double reach = DragonLastStandPatterns.CROSS_REACH;
		double half = DragonLastStandPatterns.CROSS_HALF_WIDTH;
		return Math.sqrt(reach * reach - half * half) - INSET;
	}

	/** 팔 판이 시작하는 거리(칸). 가운데 다각형의 변에서 한 비트 떨어진다. */
	static double armNear(double apothem) {
		return apothem + INSET;
	}

	/** 팔 하나의 마디 수. 십자 둘이면 37 이다. */
	static int armSegmentCount(double apothem) {
		return (int) Math.ceil((armFar() - armNear(apothem)) / SEGMENT_DEPTH);
	}

	/** {@code index} 번째 마디의 가까운 끝(칸). */
	static double segmentNear(int index, double apothem) {
		return armNear(apothem) + index * SEGMENT_DEPTH;
	}

	/** {@code index} 번째 마디의 먼 끝(칸). 마지막 마디는 {@link #armFar} 에서 잘린다. */
	static double segmentFar(int index, double apothem) {
		return Math.min(armFar(), segmentNear(index, apothem) + SEGMENT_DEPTH);
	}

	/**
	 * ⚠ <b>높이가 같은 마디를 잇는다.</b> 돌려주는 것은 {@code {첫 마디, 끝 마디}} 쌍이다.
	 *
	 * <p>허공({@code NO_GROUND}) 마디는 어느 묶음에도 안 들어간다 — 허공을 건너 이으면 판이 허공
	 * 위로 뻗는다. 높이가 바뀌는 자리에서 끊으므로 <b>이은 판은 이어지기 전의 마디들과 모양이 한
	 * 톨도 안 다르다</b>(같은 높이 · 같은 반폭 · 맞닿은 깊이).
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 직접 굴린다.
	 */
	static List<int[]> runs(int[] surfaces) {
		List<int[]> found = new ArrayList<>();
		int start = -1;
		for (int index = 0; index <= surfaces.length; index++) {
			boolean ends = index == surfaces.length
					|| surfaces[index] == TrialEnderPulse.NO_GROUND
					|| (start >= 0 && surfaces[index] != surfaces[start]);
			if (ends && start >= 0) {
				found.add(new int[] {start, index - 1});
				start = -1;
			}
			if (index < surfaces.length && surfaces[index] != TrialEnderPulse.NO_GROUND
					&& start < 0) {
				start = index;
			}
		}
		return found;
	}

	/**
	 * 변심거리 {@code apothem} 인 정 {@code sides} 각형에서, 옆으로 {@code lateral} 만큼 떨어진 줄의
	 * <b>앞뒤 반길이</b>(칸).
	 *
	 * <p>변의 법선이 {@code θ_k = k · 360/sides} 이고 {@code θ = 0} 이 첫 팔(앞)이다. 한 점
	 * {@code (u, v)} 가 안이려면 모든 변에 대해 {@code u·sinθ + v·cosθ ≤ a} 이고, 다각형이 앞뒤·좌우로
	 * 대칭이라 반길이가 {@code min((a − |u|·|sinθ|) ÷ cosθ)} ({@code cosθ > 0} 인 변만)다. 팔각형이면
	 * {@code min(a, a√2 − |u|)} 다.
	 */
	static double hubHalfExtent(double lateral, double apothem, int sides) {
		double u = Math.abs(lateral);
		if (u > apothem + 1.0E-9) {
			return 0.0;
		}
		double best = Double.MAX_VALUE;
		for (int k = 0; k < sides; k++) {
			double theta = Math.toRadians(360.0 * k / sides);
			double cos = Math.cos(theta);
			if (cos <= 1.0E-9) {
				continue;
			}
			best = Math.min(best, (apothem - u * Math.abs(Math.sin(theta))) / cos);
		}
		return Math.max(0.0, best);
	}

	/**
	 * 가운데 다각형을 채우는 띠들. <b>띠마다 반폭이 그 띠 안에서 가장 좁은 곳</b>이라 밖으로 안 나간다.
	 *
	 * <p>부채꼴 띠와 같은 수법이다. 띠는 <b>첫 팔에 수직인 방향</b>(옆, {@code u})으로 나란히 놓이고
	 * 그래서 방향이 {@code 첫 기준 각도 + 90} 이다 — 그 방향의 「앞」이 옆 축이 되고 띠의 반폭이
	 * 앞뒤({@code v}) 반길이가 된다. 반길이가 {@code |u|} 에 대해 줄기만 하므로 띠 안의 최솟값은
	 * <b>바깥쪽 끝</b>에서 나온다.
	 *
	 * <p>띠 폭은 {@link #SEGMENT_DEPTH} 를 넘지 않게 고르게 나눈다. 팔각형(변심거리 3.621)이면 여덟
	 * 장이다.
	 */
	static List<Piece> hubPieces(double[] angles) {
		double apothem = hubApothem(angles);
		int sides = armCount(angles);
		int count = Math.max(1, (int) Math.ceil(apothem * 2.0 / SEGMENT_DEPTH));
		double width = apothem * 2.0 / count;
		float yaw = (float) (angles[0] + 90.0);
		List<Piece> pieces = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			double near = -apothem + index * width;
			double far = near + width;
			double outer = Math.max(Math.abs(near), Math.abs(far));
			// 한 비트 당긴다 — 귀퉁이가 다각형 변에 정확히 놓이면 안팎이 부동소수에 달린다.
			double half = hubHalfExtent(outer, apothem, sides) - INSET;
			if (!(half > 1.0E-6)) {
				continue;
			}
			pieces.add(new Piece(new DragonLastStandConePanel.Strip(near, far, half), yaw));
		}
		return pieces;
	}

	/**
	 * <b>평평한 땅에서</b> 한 회차가 세우는 판 수 — 가운데 띠 + 팔마다 한 장.
	 *
	 * <p>마디가 전부 같은 높이면 {@link #runs} 가 팔 하나를 한 장으로 잇는다. 십자 둘이면
	 * {@code 8 + 8 = 16장}이다.
	 */
	static int flatPieceCount(double[] angles) {
		return hubPieces(angles).size() + armCount(angles);
	}

	/**
	 * <b>가장 나쁜 땅에서</b> 한 회차가 세우는 판 수 — 마디마다 높이가 달라 하나도 못 이은 경우다.
	 *
	 * <p>십자 둘이면 {@code 8 + 8 × 37 = 304장}이다. 부채꼴(18장)과 같은 1칸 기준으로 센 천장이다.
	 */
	static int worstPieceCount(double[] angles) {
		return hubPieces(angles).size() + armCount(angles) * armSegmentCount(hubApothem(angles));
	}
}
