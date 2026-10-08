package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 십자 균열의 <b>빨간 투명 면</b>(2026-10-04).
 *
 * <p>⚠ {@code runClient} 가 이 환경에서 {@code 0xC0000005} 로 죽어 <b>눈으로 확인할 수 없다.</b>
 * 그래서 부채꼴 면의 시험과 같은 길로 간다 — 판 귀퉁이를 <b>피해를 가르는 그 함수</b>
 * ({@link DragonLastStandPatterns#insideRound})에 넣어 보고, 판끼리 <b>겹치지 않는지</b>를 격자로 센다.
 */
class DragonLastStandCrossPanelTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	/** 한 회차의 판 전부 — 가운데 띠 + 팔마다 마디 전부(하나도 안 이은 최악의 경우). */
	private static List<DragonLastStandCrossPanel.Piece> allPieces(double[] angles) {
		List<DragonLastStandCrossPanel.Piece> pieces =
				new ArrayList<>(DragonLastStandCrossPanel.hubPieces(angles));
		double apothem = DragonLastStandCrossPanel.hubApothem(angles);
		for (int arm = 0; arm < DragonLastStandCrossPanel.armCount(angles); arm++) {
			float yaw = DragonLastStandCrossPanel.armYaw(angles, arm);
			for (int index = 0; index < DragonLastStandCrossPanel.armSegmentCount(apothem); index++) {
				pieces.add(new DragonLastStandCrossPanel.Piece(new DragonLastStandConePanel.Strip(
						DragonLastStandCrossPanel.segmentNear(index, apothem),
						DragonLastStandCrossPanel.segmentFar(index, apothem),
						DragonLastStandCrossPanel.armHalfWidth()), yaw));
			}
		}
		return pieces;
	}

	/**
	 * 판 하나를 격자 검사용으로 펴 둔 것 — {@code {sin, cos, near, far, halfWidth}}. 수백만 번 묻는
	 * 자리라 삼각함수를 판마다 한 번만 센다.
	 */
	private static double[] flat(DragonLastStandCrossPanel.Piece piece) {
		double radians = Math.toRadians(piece.yaw());
		DragonLastStandConePanel.Strip strip = piece.strip();
		return new double[] {Math.sin(radians), Math.cos(radians), strip.near(), strip.far(),
				strip.halfWidth()};
	}

	private static List<double[]> flatAll(List<DragonLastStandCrossPanel.Piece> pieces) {
		List<double[]> flats = new ArrayList<>(pieces.size());
		for (DragonLastStandCrossPanel.Piece piece : pieces) {
			flats.add(flat(piece));
		}
		return flats;
	}

	/** 그 점이 판 안인가. 판 좌표로 옮겨 앞뒤·옆을 본다. 경계는 안 친다(맞닿은 판을 겹침으로 안 센다). */
	private static boolean covers(double[] piece, double x, double z) {
		double along = x * piece[0] - z * piece[1];
		double lateral = x * piece[1] + z * piece[0];
		return along > piece[2] + 1.0E-6 && along < piece[3] - 1.0E-6
				&& Math.abs(lateral) < piece[4] - 1.0E-6;
	}

	// ------------------------------------------------------------------ 모양

	/**
	 * 한 회차의 팔이 <b>여덟</b>이고 45도 간격이며, 그 팔들이 <b>정확히 그 회차 십자들의 선</b>이다.
	 */
	@Test
	void 팔_여덟이_두_십자의_선이다() {
		for (double[] angles : DragonLastStandPatterns.CROSS_ANGLES) {
			assertEquals(8, DragonLastStandCrossPanel.armCount(angles), "십자 둘이면 팔 여덟이다");
			assertEquals(45.0, DragonLastStandCrossPanel.armSpacing(angles), 1.0E-9);
			for (int arm = 0; arm < 8; arm++) {
				float yaw = DragonLastStandCrossPanel.armYaw(angles, arm);
				// 팔 방향으로 20칸 간 점이 그 회차의 어느 십자 선 위여야 한다.
				Vec3 at = DragonLastStandConePanel.world(0.0, 20.0, yaw);
				boolean onLine = false;
				for (double base : angles) {
					double radians = Math.toRadians(base);
					double alongX = Math.sin(radians);
					double alongZ = -Math.cos(radians);
					double toFirst = Math.abs(at.x * alongZ - at.z * alongX);
					double toSecond = Math.abs(at.x * alongX + at.z * alongZ);
					onLine |= Math.min(toFirst, toSecond) < 1.0E-3;
				}
				assertTrue(onLine, angles[0] + "도 회차의 " + arm + "번째 팔(" + yaw + "도)이 어느 선 위도 아니다");
			}
		}
	}

	/**
	 * ⚠ 가운데 팔각형의 변심거리가 <b>팔 둘이 더는 안 겹치는 거리</b>이고, 그 변의 반길이가 <b>팔
	 * 반폭과 같다</b> — 그래서 틈도 겹침도 없이 맞물린다.
	 */
	@Test
	void 가운데_팔각형이_팔과_맞물린다() {
		double[] angles = DragonLastStandPatterns.CROSS_ANGLES[0];
		double apothem = DragonLastStandCrossPanel.hubApothem(angles);
		double half = DragonLastStandPatterns.CROSS_HALF_WIDTH;
		assertEquals(half / Math.tan(Math.toRadians(22.5)), apothem, 1.0E-12, "h · cot 22.5° 다");
		assertEquals(3.6213, apothem, 0.0001);
		// 변의 반길이 = 변심거리 × tan(22.5°) = 팔 반폭.
		assertEquals(half, apothem * Math.tan(Math.toRadians(22.5)), 1.0E-12,
				"팔각형 변이 팔 폭과 다르다 — 틈이나 겹침이 생긴다");
		// 팔각형의 반길이 식이 꼭짓점·변에서 맞는다.
		int sides = DragonLastStandCrossPanel.armCount(angles);
		assertEquals(apothem, DragonLastStandCrossPanel.hubHalfExtent(0.0, apothem, sides), 1.0E-9);
		assertEquals(half, DragonLastStandCrossPanel.hubHalfExtent(apothem, apothem, sides), 1.0E-9,
				"옆 끝(변의 한가운데)에서 반길이가 팔 반폭이어야 한다");
		assertEquals(0.0, DragonLastStandCrossPanel.hubHalfExtent(apothem + 0.1, apothem, sides), 0.0);
		// 십자 하나였다면 3×3 정사각형이다 — 일반식이 그 경우도 맞는지.
		assertEquals(half, DragonLastStandCrossPanel.hubApothem(new double[] {0.0}), 1.0E-12);
	}

	// ------------------------------------------------------------------ ⚠⚠ 칠한 곳은 언제나 맞는 곳

	/**
	 * ⚠⚠ <b>칠한 면이 전부 맞는 자리다.</b> 재는 자가 <b>피해를 가르는 그 함수</b>
	 * ({@link DragonLastStandPatterns#insideRound})다.
	 *
	 * <p>이것이 참인 동안 면은 「안전한 바닥이 위험하다」고 말할 수 없다. 판마다 귀퉁이 넷 · 변 중점
	 * 넷 · 가운데를 넣어 본다.
	 */
	@Test
	void 칠한_면이_모두_맞는_자리다() {
		for (double[] angles : DragonLastStandPatterns.CROSS_ANGLES) {
			for (DragonLastStandCrossPanel.Piece piece : allPieces(angles)) {
				DragonLastStandConePanel.Strip strip = piece.strip();
				for (double lateral : new double[] {-strip.halfWidth(), 0.0, strip.halfWidth()}) {
					for (double along : new double[] {strip.near(), strip.middle(), strip.far()}) {
						Vec3 at = DragonLastStandConePanel.world(lateral, along, piece.yaw());
						assertTrue(DragonLastStandPatterns.insideRound(at.x, at.z, angles,
										DragonLastStandPatterns.CROSS_HALF_WIDTH,
										DragonLastStandPatterns.CROSS_REACH),
								angles[0] + "도 회차 · 판 (" + piece.yaw() + "도, " + strip + ") 의 ("
										+ lateral + ", " + along + ") 가 맞는 자리 밖이다");
					}
				}
			}
		}
	}

	/**
	 * ⚠⚠ <b>판끼리 한 점도 겹치지 않는다.</b> 겹치면 반투명이 두 겹이 되어 얼룩진다 — 십자 둘을 그냥
	 * 겹쳐 깔면 가운데가 여덟 겹이다.
	 *
	 * <p>0.1칸 격자로 반경 12 안(가운데와 팔의 시작부가 다 들어간다)을 훑어 한 점을 덮는 판이 둘
	 * 이상인 곳을 센다.
	 */
	@Test
	void 판끼리_겹치지_않는다() {
		for (double[] angles : DragonLastStandPatterns.CROSS_ANGLES) {
			// 반경 12 격자에 닿을 수 있는 판만 — 그 밖의 마디는 이 격자를 못 덮는다.
			List<double[]> pieces = new ArrayList<>();
			for (double[] piece : flatAll(allPieces(angles))) {
				if (piece[2] < 13.0) {
					pieces.add(piece);
				}
			}
			for (double x = -12.0; x <= 12.0; x += 0.1) {
				for (double z = -12.0; z <= 12.0; z += 0.1) {
					int count = 0;
					for (double[] piece : pieces) {
						if (covers(piece, x, z)) {
							count++;
						}
					}
					assertTrue(count <= 1, angles[0] + "도 회차에서 (" + x + ", " + z + ") 를 판 "
							+ count + "장이 덮는다 — 반투명이 겹쳐 얼룩진다");
				}
			}
		}
	}

	/**
	 * 그래도 <b>거의 다</b> 덮는다. 맞는 자리 가운데 칠해진 몫이 95% 를 넘는다 — 모자라는 것은 가운데
	 * 팔각형의 톱니뿐이다.
	 */
	@Test
	void 맞는_자리를_거의_다_덮는다() {
		double[] angles = DragonLastStandPatterns.CROSS_ANGLES[0];
		List<double[]> pieces = flatAll(allPieces(angles));
		int hit = 0;
		int painted = 0;
		double reach = DragonLastStandPatterns.CROSS_REACH;
		for (double x = -reach; x <= reach; x += 0.2) {
			for (double z = -reach; z <= reach; z += 0.2) {
				if (!DragonLastStandPatterns.insideRound(x, z, angles,
						DragonLastStandPatterns.CROSS_HALF_WIDTH, reach)) {
					continue;
				}
				hit++;
				for (double[] piece : pieces) {
					if (covers(piece, x, z)) {
						painted++;
						break;
					}
				}
			}
		}
		double coverage = (double) painted / hit;
		assertTrue(coverage > 0.95, "맞는 자리 가운데 칠한 몫이 " + coverage + " 뿐이다");
		assertTrue(coverage < 1.0, "1.0 이면 어딘가 맞는 자리 밖을 칠하고 있을 수 있다: " + coverage);
	}

	// ------------------------------------------------------------------ 판 수 — 부채꼴과 같은 기준

	/**
	 * 판 수를 <b>부채꼴과 같은 기준</b>(1칸 마디마다 지표 한 번)으로 센다.
	 *
	 * <p>부채꼴 18장. 십자 한 회차는 평지 16장 · 최악 304장이다. 최악은 마디마다 높이가 달라 하나도
	 * 못 이은 지형에서만 닿는다.
	 */
	@Test
	void 판_수가_부채꼴과_같은_기준으로_세어진다() {
		assertEquals(DragonLastStandConePanel.STRIP_DEPTH, DragonLastStandCrossPanel.SEGMENT_DEPTH, 0.0,
				"마디 깊이가 부채꼴 띠와 다르면 「같은 기준」이 아니다");
		double[] angles = DragonLastStandPatterns.CROSS_ANGLES[0];
		double apothem = DragonLastStandCrossPanel.hubApothem(angles);
		assertEquals(8, DragonLastStandCrossPanel.hubPieces(angles).size(), "가운데 팔각형이 여덟 띠다");
		assertEquals(37, DragonLastStandCrossPanel.armSegmentCount(apothem), "팔 하나가 37마디다");
		assertEquals(16, DragonLastStandCrossPanel.flatPieceCount(angles), "평지에서 한 회차 16장");
		assertEquals(304, DragonLastStandCrossPanel.worstPieceCount(angles), "최악의 지형에서 304장");
		for (double[] round : DragonLastStandPatterns.CROSS_ANGLES) {
			assertEquals(304, DragonLastStandCrossPanel.worstPieceCount(round), "회차마다 같다");
		}
		// 팔 끝이 사거리 원 안이다 — 바깥 귀퉁이까지.
		double far = DragonLastStandCrossPanel.armFar();
		double corner = Math.hypot(far, DragonLastStandCrossPanel.armHalfWidth());
		assertTrue(corner <= DragonLastStandPatterns.CROSS_REACH, "팔 바깥 귀퉁이가 사거리 밖이다");
	}

	/**
	 * ⚠ <b>높이가 같은 마디만 잇고, 허공은 건너뛴다.</b>
	 *
	 * <p>허공을 건너 이으면 판이 허공 위로 뻗어 「저기가 바닥이다」라고 거짓말을 한다. 높이가 바뀌는
	 * 자리에서 끊으므로 이은 판은 마디들과 모양이 같다.
	 */
	@Test
	void 높이가_같은_마디만_잇는다() {
		int none = TrialEnderPulse.NO_GROUND;
		List<int[]> runs = DragonLastStandCrossPanel.runs(new int[] {64, 64, 64, 65, 65, none, none, 65, 64});
		assertEquals(4, runs.size(), "64×3 · 65×2 · (허공) · 65 · 64 — 넷이다");
		assertArrayEquals(new int[] {0, 2}, runs.get(0));
		assertArrayEquals(new int[] {3, 4}, runs.get(1));
		assertArrayEquals(new int[] {7, 7}, runs.get(2), "허공 건너편은 새 묶음이다 — 허공을 건너 잇지 않는다");
		assertArrayEquals(new int[] {8, 8}, runs.get(3));
		assertTrue(DragonLastStandCrossPanel.runs(new int[] {none, none}).isEmpty(), "허공뿐이면 판이 없다");
		assertEquals(1, DragonLastStandCrossPanel.runs(new int[] {70, 70, 70, 70}).size(),
				"평지는 한 장이다");
		assertTrue(DragonLastStandCrossPanel.runs(new int[0]).isEmpty());
	}

	// ------------------------------------------------------------------ 변환 — 부채꼴의 것을 그대로 쓴다

	/**
	 * 행렬이 판을 <b>실제로 그 자리에</b> 놓는다. 부채꼴의 변환을 그대로 쓰므로 그쪽 시험이 증명을
	 * 들고 있지만, 가운데 띠는 {@code near} 가 <b>음수</b>인 띠라 그 경우를 여기서 한 번 더 굴린다.
	 */
	@Test
	void 행렬이_가운데_띠와_팔을_제자리에_놓는다() {
		for (double[] angles : DragonLastStandPatterns.CROSS_ANGLES) {
			List<DragonLastStandCrossPanel.Piece> pieces = new ArrayList<>(
					DragonLastStandCrossPanel.hubPieces(angles));
			double apothem = DragonLastStandCrossPanel.hubApothem(angles);
			pieces.add(new DragonLastStandCrossPanel.Piece(new DragonLastStandConePanel.Strip(
					DragonLastStandCrossPanel.segmentNear(0, apothem),
					DragonLastStandCrossPanel.segmentFar(5, apothem),
					DragonLastStandCrossPanel.armHalfWidth()),
					DragonLastStandCrossPanel.armYaw(angles, 3)));
			for (DragonLastStandCrossPanel.Piece piece : pieces) {
				DragonLastStandConePanel.Strip strip = piece.strip();
				Matrix4f matrix = DragonLastStandConePanel.transformOf(strip, piece.yaw()).getMatrixCopy();
				for (int lx = 0; lx <= 1; lx++) {
					for (int lz = 0; lz <= 1; lz++) {
						Vector3f drawn = matrix.transformPosition(new Vector3f(lx, 0.0F, lz));
						double lateral = strip.halfWidth() - 2.0 * strip.halfWidth() * lx;
						double along = lz == 0 ? strip.near() : strip.far();
						Vec3 wanted = DragonLastStandConePanel.world(lateral, along, piece.yaw());
						assertEquals(wanted.x, drawn.x(), 1.0E-3, "판 (" + piece.yaw() + ", " + strip + ") 의 x");
						assertEquals(wanted.z, drawn.z(), 1.0E-3, "판 (" + piece.yaw() + ", " + strip + ") 의 z");
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ 지우는 길

	/**
	 * ⚠⚠ <b>판 클래스가 부채꼴의 것 하나다</b> — 저장 안 함 · 스스로 타 없어짐이 한 벌로 남는다.
	 * 그리고 심지가 가장 긴 예고보다 길다.
	 */
	@Test
	void 부채꼴과_같은_판을_쓰고_스스로_사라진다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStandConePanel$Panel"),
				"부채꼴의 판 클래스를 안 쓴다 — 하위 클래스가 두 벌이면 언젠가 한쪽만 shouldBeSaved 를 잃는다");
		assertFalse(bytes.contains("BlockDisplay"),
				"이 파일이 디스플레이 개체를 따로 짓는다 — 판은 한 벌이어야 한다");
		assertTrue(read("/com/sharedfate/sync/DragonLastStandConePanel$Panel.class")
				.contains("shouldBeSaved"), "판이 저장을 막지 않는다");
		assertEquals(DragonLastStandPatterns.crossDurationTicks(), DragonLastStandCrossPanel.FUSE_TICKS,
				"심지를 패턴 길이에서 직접 읽을 것");
		assertTrue(DragonLastStandCrossPanel.FUSE_TICKS
						> DragonLastStandPatterns.crossWarnTicks(0),
				"심지가 첫 회차 예고보다 짧으면 판이 예고 중에 사라진다");
	}

	/** 아무것도 안 세운 상태에서 지워도 탈이 없고 목록이 빈다. 월드 없는 시험이 부를 수 있는 유일한 길이다. */
	@Test
	void 세우지_않았어도_지울_수_있다() {
		DragonLastStandCrossPanel.drop();
		assertEquals(0, DragonLastStandCrossPanel.liveCount());
		DragonLastStandCrossPanel.drop();
		assertEquals(0, DragonLastStandCrossPanel.liveCount(), "두 번 지워도 탈이 없어야 한다");
	}

	/** 패턴 쪽이 회차마다 세우고 · 터지는 틱에 지우고 · 판이 끝날 때 지운다. */
	@Test
	void 세우고_지우는_길이_배선됐다() {
		String patterns = read("/com/sharedfate/sync/DragonLastStandPatterns.class");
		assertTrue(patterns.contains("com/sharedfate/sync/DragonLastStandCrossPanel"),
				"패턴 쪽이 십자 면을 세우지도 지우지도 않는다");
		assertTrue(patterns.contains("raise") && patterns.contains("drop"),
				"세우는 줄과 지우는 줄이 둘 다 있어야 한다");
		assertTrue(patterns.contains("crossPanelRound"),
				"「이 회차의 면이 섰는가」를 기억하지 않으면 회차가 바뀔 때 면이 안 바뀐다");
	}

	/** 블록을 한 칸도 놓지 않는다. 판은 개체이지 블록이 아니다. */
	@Test
	void 블록을_놓지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("setBlock"), "바닥을 색유리로 갈아 버리면 다음 전투가 달라진다");
		assertFalse(bytes.contains("removeBlock"));
		assertFalse(bytes.contains("destroyBlock"));
	}

	// ------------------------------------------------------------------ 도우미

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStandCrossPanel.class");
	}

	private static String read(String path) {
		try (InputStream in = DragonLastStandCrossPanelTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
