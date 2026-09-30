package com.sharedfate.sync;

import com.mojang.math.Transformation;
import com.sharedfate.TestBootstrap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Brightness;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 부채꼴 브레스의 <b>빨간 투명 면</b>.
 *
 * <p>⚠ {@code runClient} 가 이 환경에서 {@code 0xC0000005} 로 죽어 <b>눈으로 확인할 수 없다.</b>
 * 그래서 <b>행렬을 직접 굴려</b> 판 귀퉁이가 어디에 놓이는지 재고, 그 귀퉁이를 <b>피해를 가르는
 * 그 함수</b>({@link DragonLastStandPatterns#insideCone})에 넣어 본다 — 「면이 안전한 바닥을
 * 위험하다고 말하지 않는다」를 시험으로 만들 수 있는 유일한 길이다.
 */
class DragonLastStandConePanelTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	/** 판을 여러 방향으로 돌려 본다. 90도 배수만 보면 회전 부호가 틀려도 지나간다. */
	private static final float[] YAWS = {0.0F, 37.0F, 90.0F, 123.5F, 180.0F, 221.0F, 270.0F, 359.0F};

	// ------------------------------------------------------------------ 띠 나누기

	/** 띠의 깊이가 <b>바닥 점 간격의 두 배</b>다. 새 숫자를 만들지 않았다. */
	@Test
	void 띠_깊이가_점_간격에서_나온다() {
		assertEquals(TrialWarning.POINT_GAP * 2.0, DragonLastStandConePanel.STRIP_DEPTH, 1.0E-9);
		assertEquals(1.0, DragonLastStandConePanel.STRIP_DEPTH, 1.0E-9, "지금 1칸이다");
		assertEquals(20, DragonLastStandConePanel.stripCount(), "사거리 20칸 ÷ 1칸");
	}

	/** 폭이 0 인 띠는 세우지 않는다 — 꼭대기 한 칸과 사거리 끝 한 칸이다. */
	@Test
	void 폭이_0인_띠는_세우지_않는다() {
		assertNull(DragonLastStandConePanel.stripAt(0), "꼭대기에서는 부채꼴의 폭이 0 이다");
		assertNull(DragonLastStandConePanel.stripAt(DragonLastStandConePanel.stripCount() - 1),
				"사거리 끝에서도 폭이 0 이다");
		assertNull(DragonLastStandConePanel.stripAt(-1));
		assertNull(DragonLastStandConePanel.stripAt(DragonLastStandConePanel.stripCount()));
		int live = 0;
		for (int index = 0; index < DragonLastStandConePanel.stripCount(); index++) {
			if (DragonLastStandConePanel.stripAt(index) != null) {
				live++;
			}
		}
		assertEquals(18, live, "지금 판이 열여덟 장이다 — 늘리는 사람은 개체 수를 알고 늘릴 것");
	}

	/**
	 * ⚠⚠ <b>칠한 면이 전부 부채꼴 안이다.</b> 재는 자가 <b>피해를 가르는 그 함수</b>다.
	 *
	 * <p>이것이 참인 동안 면은 「안전한 바닥이 위험하다」고 말할 수 없다. 톱니로 모자라는 것은
	 * 다음 시험이 재고, 진짜 경계는 빨간 점선과 흰 벽이 말한다.
	 */
	@Test
	void 칠한_면이_모두_부채꼴_안이다() {
		double half = DragonLastStandPatterns.CONE_DEGREES / 2.0;
		for (float yaw : YAWS) {
			for (int index = 0; index < DragonLastStandConePanel.stripCount(); index++) {
				DragonLastStandConePanel.Strip strip = DragonLastStandConePanel.stripAt(index);
				if (strip == null) {
					continue;
				}
				for (double lateral : new double[] {-strip.halfWidth(), 0.0, strip.halfWidth()}) {
					for (double along : new double[] {strip.near(), strip.middle(), strip.far()}) {
						Vec3 at = DragonLastStandConePanel.world(lateral, along, yaw);
						assertTrue(DragonLastStandPatterns.insideCone(at.x, at.z, yaw,
										DragonLastStandPatterns.CONE_RANGE, half),
								"yaw " + yaw + " · 띠 " + index + " 의 (" + lateral + ", " + along
										+ ") 가 부채꼴 밖이다");
					}
				}
			}
		}
	}

	/** 그래도 <b>거의 다</b> 덮는다. 톱니로 모자라는 몫이 부채꼴의 10% 안이다. */
	@Test
	void 면이_부채꼴의_9할을_덮는다() {
		double coverage = DragonLastStandConePanel.coverage();
		assertTrue(coverage > 0.88, "덮는 몫이 너무 적다: " + coverage);
		assertTrue(coverage < 1.0, "1.0 이면 어딘가 부채꼴 밖을 칠하고 있다: " + coverage);
	}

	/** 띠는 <b>서로 겹치지 않는다.</b> 겹치면 반투명이 두 겹이 되어 얼룩진다. */
	@Test
	void 띠가_서로_겹치지_않는다() {
		Double previousFar = null;
		for (int index = 0; index < DragonLastStandConePanel.stripCount(); index++) {
			DragonLastStandConePanel.Strip strip = DragonLastStandConePanel.stripAt(index);
			if (strip == null) {
				continue;
			}
			assertTrue(strip.far() > strip.near(), "깊이가 0 이하인 띠가 있다");
			if (previousFar != null) {
				assertTrue(strip.near() >= previousFar - 1.0E-9,
						"띠 " + index + " 가 앞 띠와 겹친다");
			}
			previousFar = strip.far();
		}
	}

	// ------------------------------------------------------------------ 변환

	/**
	 * ⚠⚠ <b>행렬이 판을 실제로 그 자리에 놓는가.</b> 180도 뒤집힌 회전을 잡는 유일한 시험이다.
	 *
	 * <p>26.3 {@code Transformation} 은 {@code 이동 · 왼쪽회전 · 크기 · 오른쪽회전} 순서로 곱하고
	 * 블록 모델은 {@code [0,1]³} 이다. 그 여덟 귀퉁이를 행렬에 넣어 나온 값이
	 * {@code (±halfWidth, near/far)} 를 세계 좌표로 옮긴 것과 같아야 한다.
	 */
	@Test
	void 행렬이_판을_부채꼴_좌표에_놓는다() {
		for (float yaw : YAWS) {
			for (int index = 0; index < DragonLastStandConePanel.stripCount(); index++) {
				DragonLastStandConePanel.Strip strip = DragonLastStandConePanel.stripAt(index);
				if (strip == null) {
					continue;
				}
				Matrix4f matrix = DragonLastStandConePanel.transformOf(strip, yaw).getMatrixCopy();
				for (int lx = 0; lx <= 1; lx++) {
					for (int lz = 0; lz <= 1; lz++) {
						Vector3f drawn = matrix.transformPosition(
								new Vector3f(lx, 0.0F, lz));
						// 국소 +X 가 −p 로 가므로 lx = 0 이 +halfWidth 다.
						double lateral = strip.halfWidth() - 2.0 * strip.halfWidth() * lx;
						double along = lz == 0 ? strip.near() : strip.far();
						Vec3 wanted = DragonLastStandConePanel.world(lateral, along, yaw);
						assertEquals(wanted.x, drawn.x(), 1.0E-3,
								"yaw " + yaw + " 띠 " + index + " 귀퉁이 " + lx + lz + " 의 x");
						assertEquals(wanted.z, drawn.z(), 1.0E-3,
								"yaw " + yaw + " 띠 " + index + " 귀퉁이 " + lx + lz + " 의 z");
						assertEquals(0.0, drawn.y(), 1.0E-3, "판이 바닥에서 떠 있다");
					}
				}
			}
		}
	}

	/** 두께가 위로만 자란다. 아래로 자라면 바닥에 파묻혀 안 보인다. */
	@Test
	void 두께가_위로_자란다() {
		DragonLastStandConePanel.Strip strip = DragonLastStandConePanel.stripAt(10);
		assertNotNull(strip);
		Matrix4f matrix = DragonLastStandConePanel.transformOf(strip, 42.0F).getMatrixCopy();
		Vector3f top = matrix.transformPosition(new Vector3f(0.0F, 1.0F, 0.0F));
		assertTrue(top.y() > 0.0F && top.y() < 0.1F,
				"두께가 0 이거나 발목에 걸릴 만큼 두껍다: " + top.y());
	}

	// ------------------------------------------------------------------ NBT

	/**
	 * 네 코덱이 <b>레지스트리 없이도</b> 써진다.
	 *
	 * <p>26.3 의 디스플레이 설정자가 전부 {@code private} 이라 들어가는 길이 저장 NBT 하나뿐이고,
	 * 코덱이 하나라도 쓰이지 않으면 <b>판이 통째로 기본값(공기 · 단위 변환)이 된다</b> — 그것은
	 * 로그에도 안 남는다({@code ProblemReporter.DISCARDING}).
	 */
	@Test
	void 판을_세우는_NBT_가_만들어진다() {
		DragonLastStandConePanel.Strip strip = DragonLastStandConePanel.stripAt(5);
		assertNotNull(strip);
		Transformation transformation = DragonLastStandConePanel.transformOf(strip, 17.0F);
		CompoundTag tag = DragonLastStandConePanel.shapeTag(transformation);
		for (String key : new String[] {"transformation", "block_state", "brightness", "billboard",
				"view_range"}) {
			assertNotNull(tag.get(key), key + " 태그가 비었다 — 코덱이 안 써졌다");
		}
	}

	/**
	 * ⚠⚠ <b>바닐라가 읽는 길로 되읽어 본다.</b> 이것이 이 파일에서 가장 위험한 고리다.
	 *
	 * <p>26.3 의 디스플레이 설정자가 전부 {@code private} 이라 들어가는 길이
	 * {@code readAdditionalSaveData(ValueInput)} 하나다. 태그 이름이나 코덱이 하나라도 어긋나면
	 * 바닐라가 <b>조용히 기본값</b>(공기 블록 · 단위 변환)을 쓰고, 그러면 빨간 면이 <b>아예 안
	 * 보이는데 로그에는 한 줄도 안 남는다</b>({@code ProblemReporter.DISCARDING}).
	 *
	 * <p>그래서 우리가 쓴 태그를 <b>바닐라가 쓰는 그 {@code ValueInput}</b> 으로 다시 읽어
	 * 되돌아오는지 본다. 태그 이름과 코덱은 {@code Display.readAdditionalSaveData} 의
	 * 바이트코드에서 그대로 옮겨 적은 것이다.
	 */
	@Test
	void 바닐라가_읽는_길로_되읽힌다() {
		DragonLastStandConePanel.Strip strip = DragonLastStandConePanel.stripAt(7);
		assertNotNull(strip);
		Transformation wanted = DragonLastStandConePanel.transformOf(strip, 66.0F);
		ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING,
				TestBootstrap.registries(), DragonLastStandConePanel.shapeTag(wanted));

		Transformation read = input.read("transformation", Transformation.EXTENDED_CODEC)
				.orElse(null);
		assertNotNull(read, "transformation 이 되읽히지 않는다 — 판이 단위 변환으로 남아 한 칸짜리 유리가 된다");
		assertEquals(wanted.getMatrixCopy(), read.getMatrixCopy(), "행렬이 왕복에서 달라졌다");

		assertEquals(DragonLastStandConePanel.glass(),
				input.read("block_state", BlockState.CODEC).orElse(null),
				"block_state 가 되읽히지 않는다 — 바닐라가 공기를 쓰면 판이 아예 안 보인다");
		assertEquals(Brightness.FULL_BRIGHT, input.read("brightness", Brightness.CODEC).orElse(null),
				"brightness 가 되읽히지 않는다 — 어두운 데서 면이 안 보인다");
		assertEquals(Display.BillboardConstraints.FIXED,
				input.read("billboard", Display.BillboardConstraints.CODEC).orElse(null),
				"billboard 가 되읽히지 않는다 — 판이 사람을 따라 돌아 버린다");
		assertTrue(input.getFloatOr("view_range", 0.0F) > 1.0F, "view_range 가 되읽히지 않는다");
	}

	/** 판은 <b>붉은 색유리</b>다. 26.3 에서 색유리는 {@code ColorCollection} 한 칸이다. */
	@Test
	void 판이_붉은_색유리다() {
		String name = DragonLastStandConePanel.glass().getBlock().toString();
		assertTrue(name.contains("red_stained_glass"),
				"반투명 빨강이 블록에서 나오지 않는다: " + name);
	}

	/** 보이는 거리가 기본값 64칸보다 멀다. 아레나가 80칸이라 기본값으로는 반대편에서 안 보인다. */
	@Test
	void 보이는_거리가_아레나를_덮는다() {
		CompoundTag tag = DragonLastStandConePanel.shapeTag(Transformation.IDENTITY);
		float viewRange = tag.getFloatOr("view_range", 0.0F);
		assertTrue(viewRange * 64.0F >= TrialRisks.ARENA_RADIUS * 2.0,
				"보이는 거리가 아레나 지름보다 짧다: " + viewRange * 64.0F);
	}

	// ------------------------------------------------------------------ 지우는 길

	/**
	 * ⚠⚠ <b>저장되지 않고 스스로도 타 없어진다.</b> 둘 중 하나라도 빠지면 다음 판까지 빨간 판이
	 * 남을 수 있다.
	 */
	@Test
	void 저장되지_않고_스스로_사라진다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("shouldBeSaved"),
				"저장을 막지 않으면 서버 강제 종료에 판이 월드 파일에 들어간다 — 그 길에는 "
						+ "SERVER_STOPPING 이 오지 않아 지우는 코드로 막을 수 없다");
		assertTrue(bytes.contains("discard"), "지우는 길이 없다");
		assertTrue(DragonLastStandConePanel.FUSE_TICKS
						>= DragonLastStandPatterns.CONE_WARN_TICKS,
				"심지가 예고보다 짧으면 판이 예고 중에 사라진다");
		assertEquals(DragonLastStandPatterns.CONE_WARN_TICKS
						+ DragonLastStandPatterns.CONE_AFTERGLOW_TICKS,
				DragonLastStandConePanel.FUSE_TICKS,
				"심지를 브레스 길이에서 직접 더할 것 — 브레스를 늘리는 사람이 여기를 따로 고치게 하지 말 것");
	}

	/**
	 * 아무것도 안 세운 상태에서 지워도 탈이 없고 목록이 빈다.
	 *
	 * <p>{@code drop()} 은 전투가 끝날 때 · 서버가 내려갈 때 · 최후의 저항이 한 번도 안 열린
	 * 판에서까지 불린다. 월드가 없는 시험에서 부를 수 있는 것이 이 길뿐이기도 하다.
	 */
	@Test
	void 세우지_않았어도_지울_수_있다() {
		DragonLastStandConePanel.drop();
		assertEquals(0, DragonLastStandConePanel.liveCount());
		DragonLastStandConePanel.drop();
		assertEquals(0, DragonLastStandConePanel.liveCount(), "두 번 지워도 탈이 없어야 한다");
	}

	/** 블록을 한 칸도 놓지 않는다. 판은 개체이지 블록이 아니다. */
	@Test
	void 블록을_놓지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("setBlockAndUpdate"), "바닥을 색유리로 갈아 버리면 다음 전투가 달라진다");
		assertFalse(bytes.contains("removeBlock"));
		assertFalse(bytes.contains("destroyBlock"));
	}

	/** 지우는 길 넷이 전부 배선돼 있다. */
	@Test
	void 지우는_길이_전부_배선됐다() {
		String patterns = read("/com/sharedfate/sync/DragonLastStandPatterns.class");
		assertTrue(patterns.contains("com/sharedfate/sync/DragonLastStandConePanel"),
				"패턴 쪽이 판을 세우지도 지우지도 않는다");
		assertTrue(patterns.contains("raise") && patterns.contains("drop"),
				"세우는 줄과 지우는 줄이 둘 다 있어야 한다");
		String stand = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(stand.contains("com/sharedfate/sync/DragonLastStandConePanel"),
				"onServerStopping 이 판을 거두지 않는다 — 월드가 살아 있는 마지막 자리다");
	}

	// ------------------------------------------------------------------ 도우미

	/**
	 * 겉 클래스와 <b>개체 하위 클래스</b>를 함께 읽는다.
	 *
	 * <p>{@code shouldBeSaved}·{@code tick} 은 {@code DragonLastStandConePanel$Panel} 쪽에 있으므로
	 * 겉만 읽으면 「없다」로 나온다.
	 */
	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStandConePanel.class")
				+ read("/com/sharedfate/sync/DragonLastStandConePanel$Panel.class");
	}

	private static String read(String path) {
		try (InputStream in = DragonLastStandConePanelTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
