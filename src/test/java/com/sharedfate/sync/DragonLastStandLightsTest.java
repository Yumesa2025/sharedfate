package com.sharedfate.sync;

import com.mojang.math.Transformation;
import com.sharedfate.TestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Brightness;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>신호기 빛과 타이머 바</b>.
 *
 * <p>⚠ {@code runClient} 가 이 환경에서 죽어 <b>눈으로 확인할 수 없다</b>
 * ({@code DragonLastStandConePanelTest} 의 같은 경고). 그래서 <b>행렬을 직접 굴려</b> 상자의
 * 귀퉁이가 어디에 놓이는지 잰다 — 「기둥이 밑바닥에서 위로 자라는가」와 「바가 왼쪽 끝을 두고
 * 줄어드는가」를 물을 수 있는 유일한 길이다.
 */
class DragonLastStandLightsTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 빛기둥

	/**
	 * ⚠ <b>기둥이 밑바닥에서 <u>위로</u> 자란다.</b>
	 *
	 * <p>26.3 {@code Transformation} 의 상자는 원점에서 양의 방향으로만 자라므로, 세로 이동을
	 * 잘못 넣으면 기둥이 <b>땅속으로</b> 내려간다. 그러면 사람 눈에는 아무것도 안 켜진다.
	 */
	@Test
	void 기둥이_밑바닥에서_위로_자란다() {
		double width = DragonLastStandLights.PILLAR_WIDTH;
		Matrix4f matrix = DragonLastStandLights.pillarShape(width, 48.0).getMatrixCopy();
		Vector3f low = corner(matrix, 0.0F, 0.0F, 0.0F);
		Vector3f high = corner(matrix, 1.0F, 1.0F, 1.0F);

		assertEquals(0.0F, low.y(), 1.0E-4F, "기둥의 밑이 받은 자리보다 낮으면 땅에 묻힌다");
		assertEquals(48.0F, high.y(), 1.0E-3F, "기둥이 받은 높이만큼 자라지 않는다");
		assertEquals(-width / 2.0, low.x(), 1.0E-4, "가로 가운데가 안 맞는다");
		assertEquals(width / 2.0, high.x(), 1.0E-4, "가로 가운데가 안 맞는다");
		assertEquals(-width / 2.0, low.z(), 1.0E-4);
		assertEquals(width / 2.0, high.z(), 1.0E-4);
	}

	// ------------------------------------------------------------------ 타이머 바

	/**
	 * ⚠⚠ <b>바는 왼쪽 끝을 두고 오른쪽만 들어온다.</b>
	 *
	 * <p>가운데를 기준으로 양쪽이 함께 줄면 「줄어드는 막대」가 아니라 「작아지는 막대」로 읽혀
	 * 남은 양을 가늠할 수 없다. 여러 비율에서 왼쪽 끝이 <b>한 자리에 못박혀 있는지</b> 본다.
	 */
	@Test
	void 바가_왼쪽_끝을_두고_줄어든다() {
		double full = 3.0;
		float left = Float.NaN;
		float previousRight = Float.MAX_VALUE;
		for (double ratio : new double[] {1.0, 0.75, 0.5, 0.25, 0.0}) {
			Matrix4f matrix = DragonLastStandLights
					.barShape(full, full * ratio, DragonLastStandLights.BAR_FILL_THICKNESS,
							DragonLastStandLights.BAR_FILL_DEPTH)
					.getMatrixCopy();
			float here = corner(matrix, 0.0F, 0.0F, 0.0F).x();
			float right = corner(matrix, 1.0F, 0.0F, 0.0F).x();
			if (Float.isNaN(left)) {
				left = here;
				assertEquals(-full / 2.0, left, 1.0E-4, "가득일 때 왼쪽 끝이 가운데에서 반만큼 왼쪽이어야 한다");
			}
			assertEquals(left, here, 1.0E-5F, "줄어드는 동안 왼쪽 끝이 움직였다 — 남은 양을 못 읽는다");
			assertTrue(right <= previousRight + 1.0E-5F, "오른쪽 끝이 되레 늘었다");
			previousRight = right;
		}
		assertEquals(left, previousRight, 1.0E-5F, "0 이면 길이가 0 이어야 한다");
	}

	/** 배경과 채움이 같은 「가득일 때 길이」를 받으면 왼쪽 끝이 정확히 맞는다. */
	@Test
	void 배경과_채움의_왼쪽_끝이_맞는다() {
		double full = 3.0;
		float back = corner(DragonLastStandLights
				.barShape(full, full, DragonLastStandLights.BAR_BACK_THICKNESS,
						DragonLastStandLights.BAR_BACK_DEPTH)
				.getMatrixCopy(), 0.0F, 0.0F, 0.0F).x();
		float fill = corner(DragonLastStandLights
				.barShape(full, full * 0.3, DragonLastStandLights.BAR_FILL_THICKNESS,
						DragonLastStandLights.BAR_FILL_DEPTH)
				.getMatrixCopy(), 0.0F, 0.0F, 0.0F).x();
		assertEquals(back, fill, 1.0E-5F, "배경과 채움의 왼쪽 끝이 어긋나면 바가 삐뚤어 보인다");
	}

	/**
	 * ⚠ <b>채움이 배경보다 깊고 얇다.</b>
	 *
	 * <p>깊이가 같으면 같은 평면에서 z 싸움이 나 화면이 지글거린다. 채움이 <b>양쪽으로
	 * 튀어나와</b> 어느 쪽에서 봐도 앞이어야 하고, 배경이 더 두꺼워야 테로 읽힌다.
	 */
	@Test
	void 채움이_배경보다_깊고_얇다() {
		assertTrue(DragonLastStandLights.BAR_FILL_DEPTH > DragonLastStandLights.BAR_BACK_DEPTH,
				"채움이 배경과 같은 깊이면 z 싸움으로 지글거린다");
		assertTrue(DragonLastStandLights.BAR_FILL_THICKNESS
						< DragonLastStandLights.BAR_BACK_THICKNESS,
				"배경이 채움보다 얇으면 테가 안 보여 가득 찬 바를 알아볼 수 없다");
	}

	// ------------------------------------------------------------------ 태그

	/**
	 * ⚠⚠ <b>우리가 쓴 태그를 바닐라가 쓰는 그 {@code ValueInput} 으로 되읽어 본다.</b>
	 *
	 * <p>26.3 디스플레이의 설정자가 전부 {@code private} 이라 들어가는 길이
	 * {@code readAdditionalSaveData} 하나뿐인데, 태그 이름이 어긋나면 바닐라가 <b>조용히
	 * 기본값</b>(공기 · 단위 변환)을 쓰고 로그에 한 줄도 안 남는다.
	 */
	@Test
	void 태그가_되읽힌다() {
		Transformation wanted = DragonLastStandLights.pillarShape(0.5, 48.0);
		BlockState glass = DragonLastStandLights.glass(DyeColor.PURPLE);
		CompoundTag tag = DragonLastStandLights.shapeTag(wanted, glass,
				Display.BillboardConstraints.CENTER);
		ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING,
				TestBootstrap.registries(), tag);

		Transformation read = input.read("transformation", Transformation.EXTENDED_CODEC)
				.orElse(null);
		assertNotNull(read, "transformation 이 되읽히지 않는다 — 한 칸짜리 유리가 남는다");
		assertEquals(wanted.getMatrixCopy(), read.getMatrixCopy(), "행렬이 왕복에서 달라졌다");
		assertEquals(glass, input.read("block_state", BlockState.CODEC).orElse(null),
				"block_state 가 되읽히지 않으면 바닐라가 공기를 쓴다");
		assertEquals(Brightness.FULL_BRIGHT, input.read("brightness", Brightness.CODEC).orElse(null),
				"brightness 가 되읽히지 않으면 어두운 데서 빛이 안 보인다");
		assertEquals(Display.BillboardConstraints.CENTER,
				input.read("billboard", Display.BillboardConstraints.CODEC).orElse(null),
				"billboard 가 되읽히지 않으면 바가 한 방향으로 고정된다");
		assertTrue(input.getFloatOr("view_range", 0.0F) > 1.0F, "view_range 가 되읽히지 않는다");
	}

	/**
	 * 보이는 거리가 아레나를 덮는다.
	 *
	 * <p>⚠ 이것이 <b>타이머 바를 개체 이름표로 만들지 않은 까닭</b>이다. 이름표는 64칸에서
	 * 잘리고 그 값을 우리가 고를 수 없다.
	 */
	@Test
	void 보이는_거리가_아레나를_덮는다() {
		assertTrue(DragonLastStandLights.VIEW_RANGE * 64.0F >= TrialRisks.ARENA_RADIUS * 2.0,
				"보이는 거리가 아레나 지름보다 짧다");
		assertTrue(DragonLastStandLights.VIEW_RANGE * 64.0F > 64.0,
				"이름표(64칸)보다 멀지 않으면 디스플레이 개체를 쓸 값어치가 없다");
	}

	/** 색이 블록에서 나온다. 26.3 에서 색유리는 {@code ColorCollection} 한 칸이다. */
	@Test
	void 색이_블록에서_나온다() {
		assertTrue(DragonLastStandLights.glass(DyeColor.PURPLE).getBlock().toString()
				.contains("purple_stained_glass"), "보라 신호기 빛의 색이 블록에서 안 나온다");
		assertTrue(DragonLastStandLights.glass(DyeColor.WHITE).getBlock().toString()
				.contains("white_stained_glass"), "흰 신호기 선의 색이 블록에서 안 나온다");
	}

	// ------------------------------------------------------------------ 지우는 길

	/** ⚠⚠ <b>저장되지 않고 스스로도 타 없어진다.</b> 둘 중 하나라도 빠지면 다음 판까지 남는다. */
	@Test
	void 저장되지_않고_스스로_사라진다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("shouldBeSaved"),
				"저장을 막지 않으면 서버 강제 종료에 빛이 월드 파일에 들어간다");
		assertTrue(bytes.contains("discard"), "지우는 길이 없다");
	}

	/** 블록을 한 칸도 놓지 않는다. ⚠ <b>진짜 신호기를 놓으면 다음 전투의 발판이 달라진다.</b> */
	@Test
	void 블록을_놓지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("setBlockAndUpdate"), "진짜 신호기를 놓으면 다음 전투가 달라진다");
		assertFalse(bytes.contains("BEACON"), "진짜 신호기 블록을 쓰면 기단까지 놓아야 한다");
		assertFalse(bytes.contains("removeBlock"));
		assertFalse(bytes.contains("destroyBlock"));
	}

	/** 아무것도 안 세운 상태에서 지워도 탈이 없다. 월드 없는 시험이 부를 수 있는 길이 이것뿐이다. */
	@Test
	void 세우지_않았어도_지울_수_있다() {
		DragonLastStandLights.drop();
		assertEquals(0, DragonLastStandLights.liveCount());
		DragonLastStandLights.drop(null);
		DragonLastStandLights.drop();
		assertEquals(0, DragonLastStandLights.liveCount(), "두 번 지워도 탈이 없어야 한다");
	}

	/** 쓰는 쪽 둘이 모두 이 파일을 지난다. 개체를 한자리에서 들고 있어야 한 번에 거둘 수 있다. */
	@Test
	void 쓰는_쪽_둘이_모두_여기를_지난다() {
		assertTrue(read("/com/sharedfate/sync/DragonLastStandEntry.class")
						.contains("com/sharedfate/sync/DragonLastStandLights"),
				"진입 연출이 신호기 빛을 제 손으로 만들고 있다");
		assertTrue(read("/com/sharedfate/sync/DragonLastStandObjects.class")
						.contains("com/sharedfate/sync/DragonLastStandLights"),
				"오브젝트 파도가 흰 선과 바를 제 손으로 만들고 있다");
	}

	// ------------------------------------------------------------------ 도우미

	/** 상자의 한 귀퉁이가 변환을 지난 뒤 어디에 놓이는지. */
	private static Vector3f corner(Matrix4f matrix, float x, float y, float z) {
		return matrix.transformPosition(new Vector3f(x, y, z));
	}

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStandLights.class")
				+ read("/com/sharedfate/sync/DragonLastStandLights$Glow.class");
	}

	private static String read(String path) {
		try (InputStream in = DragonLastStandLightsTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
