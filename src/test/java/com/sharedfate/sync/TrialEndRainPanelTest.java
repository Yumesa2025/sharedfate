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
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「종말의 비」의 <b>보라 투명 면</b>.
 *
 * <p>⚠ {@code runClient} 가 이 환경에서 {@code 0xC0000005} 로 죽어 <b>눈으로 확인할 수 없다.</b>
 * 그래서 <b>행렬을 직접 굴려</b> 판 귀퉁이가 어디에 놓이는지 재고, 그 귀퉁이를 <b>피해를 가르는
 * 그 함수</b>({@link TrialRisks#insideMark})에 넣어 본다 — 「보이는 자리와 터지는 자리가
 * 어긋나지 않는다」를 시험으로 만들 수 있는 유일한 길이고, 이 작업에서 가장 중요한 성질이다.
 *
 * <p>개체가 <b>하나도 새지 않는가</b>도 여기서 본다. 한 볼리에 450개가 생기고 사라지므로 새면
 * 한 번에 크게 샌다 — 그리고 그것은 월드 저장 파일에 들어간다.
 */
class TrialEndRainPanelTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	/** 이 카드의 반경. 면이 덮어야 하는 그 값이다. */
	private static final double RADIUS = 2.5;

	// ------------------------------------------------------------------ 띠 나누기

	/** 판 수가 각도 조각 수에서 나온다. {@code 1 + 2×(조각 − 2)} 다. */
	@Test
	void 판_수가_각도_조각에서_나온다() {
		assertEquals(4, TrialEndRainPanel.QUADRANT_STEPS, "90도를 넷으로 나눈다 — 22.5도씩이다");
		assertEquals(5, TrialEndRainPanel.slabCount(),
				"판이 다섯 장이다 — 90곳이면 개체 450개다. 늘리는 사람은 그 수를 알고 늘릴 것");
		int live = 0;
		for (int index = 0; index < TrialEndRainPanel.slabCount(); index++) {
			if (TrialEndRainPanel.slabAt(index, RADIUS) != null) {
				live++;
			}
		}
		assertEquals(TrialEndRainPanel.slabCount(), live,
				"폭이 0 인 띠는 애초에 만들지 않는다 — 세는 수와 세우는 수가 같아야 개체 예산이 참이다");
	}

	/** 반경이 없거나 번호가 범위를 벗어나면 아무것도 돌려주지 않는다. */
	@Test
	void 반경이_없으면_판도_없다() {
		assertNull(TrialEndRainPanel.slabAt(0, 0.0));
		assertNull(TrialEndRainPanel.slabAt(0, -2.5));
		assertNull(TrialEndRainPanel.slabAt(-1, RADIUS));
		assertNull(TrialEndRainPanel.slabAt(TrialEndRainPanel.slabCount(), RADIUS));
		assertEquals(0.0, TrialEndRainPanel.coverage(0.0), 1.0E-9);
	}

	/**
	 * ⚠⚠ <b>칠한 면이 전부 터지는 원 안이다.</b> 재는 자가 <b>피해를 가르는 그 함수</b>다.
	 *
	 * <p>이것이 참인 동안 면은 「안전한 바닥이 위험하다」고 말할 수 없다. 반대 방향으로 모자라는
	 * 몫은 {@link #면이_원의_8할을_넘게_덮는다} 와 {@link #경계가_걷어낸_고리보다_또렷하다} 가 잰다.
	 *
	 * <p>이 카드의 반경(2.5)만 보지 않는다. 사람이 반경을 고치면 면이 따라가야 하므로 여러 반경에서
	 * 같은 성질을 묻는다.
	 */
	@Test
	void 칠한_면이_모두_터지는_원_안이다() {
		for (double radius : new double[] {0.5, 1.0, RADIUS, 3.0, 4.35, 10.0}) {
			for (int index = 0; index < TrialEndRainPanel.slabCount(); index++) {
				TrialEndRainPanel.Slab slab = TrialEndRainPanel.slabAt(index, radius);
				if (slab == null) {
					continue;
				}
				for (double x : new double[] {-slab.halfWidth(), 0.0, slab.halfWidth()}) {
					for (double z : new double[] {slab.near(), (slab.near() + slab.far()) * 0.5,
							slab.far()}) {
						assertTrue(TrialRisks.insideMark(new Vec3(x, 0.0, z), Vec3.ZERO, radius),
								"반경 " + radius + " · 띠 " + index + " 의 (" + x + ", " + z
										+ ") 가 원 밖이다 — 안전한 바닥을 위험하다고 말하고 있다");
					}
				}
			}
		}
	}

	/** 그래도 <b>거의 다</b> 덮는다. 계단으로 모자라는 몫이 원의 2할 안이다. */
	@Test
	void 면이_원의_8할을_넘게_덮는다() {
		double coverage = TrialEndRainPanel.coverage(RADIUS);
		assertTrue(coverage > 0.84, "덮는 몫이 너무 적다: " + coverage);
		assertTrue(coverage < 1.0, "1.0 이면 어딘가 원 밖을 칠하고 있다: " + coverage);
		// 조각을 줄이면 먼저 떨어지는 값이다 — 「각도로 나눈다」를 바꾸려는 사람이 여기서 걸린다.
		assertTrue(TrialEndRainPanel.coverage(10.0) > 0.84,
				"반경이 커져도 덮는 몫은 그대로여야 한다(비율이라 반경과 무관하다)");
	}

	/**
	 * ⚠⚠ <b>경계가 걷어낸 고리보다 또렷하다.</b> 「선을 버리고 면으로 간 것」의 근거다.
	 *
	 * <p>고리는 둘레 15.71칸에 점 18개라 점 사이가 {@link TrialEndRain#RETIRED_RING_GAP}(0.87칸)
	 * 이었다. 면은 원에 내접하는 계단이라 경계가 {@code r(1 − cos 22.5°)} = <b>0.19칸</b>
	 * 안쪽에서 끝난다.
	 *
	 * <p>⚠ 모자라는 쪽이 「위험한데 안 칠한 쪽」이라는 것을 알고 둔다. 사람 몸통 폭이 0.6칸이라
	 * 그 띠에만 서 있을 수가 없고, 거꾸로 원 밖까지 칠하면 <b>피할 필요가 없던 자리에서 비키게</b>
	 * 된다 — 둘 중 하나를 골라야 하면 안쪽이다.
	 */
	@Test
	void 경계가_걷어낸_고리보다_또렷하다() {
		double gap = TrialEndRainPanel.edgeGap(RADIUS);
		assertEquals(0.19, gap, 0.01, "경계가 모자라는 폭이 0.19칸이 아니다: " + gap);
		assertTrue(gap < TrialEndRain.RETIRED_RING_GAP,
				"면의 경계(" + gap + "칸)가 걷어낸 고리의 점 간격("
						+ TrialEndRain.RETIRED_RING_GAP + "칸)보다 흐리다");
		assertTrue(gap < 0.3, "사람 몸통 폭(0.6칸)의 절반을 넘으면 그 띠에 서 있을 수 있게 된다");
		// 반경에 비례한다. 반경을 키우는 사람은 모자라는 폭도 함께 커진다는 것을 알아야 한다.
		assertTrue(TrialEndRainPanel.edgeGap(10.0) > gap, "모자라는 폭은 반경에 비례한다");
	}

	/**
	 * 띠는 <b>서로 겹치지 않는다.</b> 겹치면 반투명이 두 겹이 되어 얼룩진다.
	 *
	 * <p>붙어 있어야 한다는 것도 함께 본다 — 사이가 벌어지면 칠하지 않은 띠가 원 가운데를 지나간다.
	 */
	@Test
	void 띠가_서로_겹치지도_벌어지지도_않는다() {
		List<TrialEndRainPanel.Slab> slabs = new ArrayList<>();
		for (int index = 0; index < TrialEndRainPanel.slabCount(); index++) {
			TrialEndRainPanel.Slab slab = TrialEndRainPanel.slabAt(index, RADIUS);
			if (slab != null) {
				slabs.add(slab);
			}
		}
		slabs.sort(Comparator.comparingDouble(TrialEndRainPanel.Slab::near));
		Double previousFar = null;
		for (TrialEndRainPanel.Slab slab : slabs) {
			assertTrue(slab.depth() > 0.0, "깊이가 0 이하인 띠가 있다");
			assertTrue(slab.halfWidth() > 0.0, "폭이 0 이하인 띠가 있다");
			if (previousFar != null) {
				assertEquals(previousFar, slab.near(), 1.0E-9,
						"띠 사이가 벌어졌거나 겹쳤다 — 겹치면 반투명이 두 겹으로 얼룩진다");
			}
			previousFar = slab.far();
		}
		// 가운데 띠가 중심을 걸터앉는다. 중심에 띠 경계가 오면 가장 넓은 자리가 둘로 쪼개져
		// 개체가 하나 늘어난다.
		assertTrue(slabs.get(slabs.size() / 2).near() < 0.0
						&& slabs.get(slabs.size() / 2).far() > 0.0,
				"가운데 띠가 중심을 걸터앉지 않는다");
	}

	/**
	 * ⚠⚠ <b>겹친 자리가 같은 평면에 놓이지 않는다.</b>
	 *
	 * <p>이 카드는 표식끼리 겹치는 것이 허용된 유일한 카드다(사람이 「서로 겹쳐도 되니까 내가 말한
	 * 숫자로 해 줘」라고 정했다). 두 판이 같은 높이의 같은 평면에 놓이면 반투명이 어룽거리므로
	 * 자리마다 층을 돌려 쓴다.
	 *
	 * <p>층이 <b>바닥 표식을 띄우는 높이를 넘지 않는 것</b>이 요점이다 — 넘으면 가장 높은 판이
	 * 바닥에 붙은 것으로 안 보인다.
	 */
	@Test
	void 겹친_자리가_같은_평면에_놓이지_않는다() {
		assertEquals(0.0, TrialEndRainPanel.layerLift(0), 1.0E-9, "첫 자리는 띄우지 않는다");
		assertTrue(TrialEndRainPanel.LAYER_COUNT > 1, "층이 하나면 겹친 판이 전부 같은 평면이다");
		assertEquals(TrialEndRainPanel.MAX_LAYER_LIFT,
				TrialEndRainPanel.layerLift(TrialEndRainPanel.LAYER_COUNT - 1), 1.0E-9,
				"가장 높은 층의 값이 상수와 다르다");
		assertTrue(TrialEndRainPanel.MAX_LAYER_LIFT < TrialEndRainPanel.GROUND_OFFSET,
				"가장 높은 판이 " + TrialEndRainPanel.MAX_LAYER_LIFT + "칸 더 뜬다 — 바닥 표식을"
						+ " 띄우는 높이(" + TrialEndRainPanel.GROUND_OFFSET + ")를 넘으면 바닥에"
						+ " 붙은 것으로 안 보인다");

		// 한 바퀴 안에서는 층이 전부 다르다. 같으면 그만큼 겹친 쌍이 같은 평면에 남는다.
		boolean[] used = new boolean[TrialEndRainPanel.LAYER_COUNT];
		for (int index = 0; index < TrialEndRainPanel.LAYER_COUNT; index++) {
			int layer = (int) Math.round(TrialEndRainPanel.layerLift(index)
					/ TrialEndRainPanel.LAYER_STEP);
			assertFalse(used[layer], index + "번 자리가 이미 쓴 층을 또 쓴다");
			used[layer] = true;
		}
		// 번호가 음수로 들어와도 바닥 밑으로 가라앉지 않는다.
		assertTrue(TrialEndRainPanel.layerLift(-3) >= 0.0, "음수 번호에서 판이 바닥 밑으로 간다");
		// 깊이 버퍼가 갈라 볼 수 있는 간격이어야 한다. 50칸 거리에서 3밀리칸쯤이 한계다.
		assertTrue(TrialEndRainPanel.LAYER_STEP >= 0.005,
				"층 사이가 " + TrialEndRainPanel.LAYER_STEP + "칸이면 멀리 있는 판에서 다시"
						+ " 어룽거린다");
	}

	// ------------------------------------------------------------------ 변환

	/**
	 * ⚠⚠ <b>행렬이 판을 실제로 그 자리에 놓는가.</b>
	 *
	 * <p>26.3 {@code Transformation} 은 {@code 이동 · 왼쪽회전 · 크기 · 오른쪽회전} 순서로 곱하고
	 * 블록 모델은 {@code [0,1]³} 이다. 그 귀퉁이를 행렬에 넣어 나온 값이 띠의
	 * {@code (±halfWidth, near/far)} 와 같아야 한다.
	 *
	 * <p>부채꼴 면과 달리 <b>회전이 없다</b>(원은 방향이 없다). 그래도 재는 것은, 두께를 {@code y}
	 * 가 아닌 축에 넣거나 이동에서 반폭을 빼먹는 종류의 실수가 <b>눈으로 볼 수 없는 자리</b>에
	 * 있기 때문이다.
	 */
	@Test
	void 행렬이_판을_띠_좌표에_놓는다() {
		for (int index = 0; index < TrialEndRainPanel.slabCount(); index++) {
			TrialEndRainPanel.Slab slab = TrialEndRainPanel.slabAt(index, RADIUS);
			assertNotNull(slab);
			Matrix4f matrix = TrialEndRainPanel.transformOf(slab).getMatrixCopy();
			for (int lx = 0; lx <= 1; lx++) {
				for (int lz = 0; lz <= 1; lz++) {
					Vector3f drawn = matrix.transformPosition(new Vector3f(lx, 0.0F, lz));
					double wantedX = lx == 0 ? -slab.halfWidth() : slab.halfWidth();
					double wantedZ = lz == 0 ? slab.near() : slab.far();
					assertEquals(wantedX, drawn.x(), 1.0E-3,
							"띠 " + index + " 귀퉁이 " + lx + lz + " 의 x");
					assertEquals(wantedZ, drawn.z(), 1.0E-3,
							"띠 " + index + " 귀퉁이 " + lx + lz + " 의 z");
					assertEquals(0.0, drawn.y(), 1.0E-3, "판이 바닥에서 떠 있다");
				}
			}
		}
	}

	/** 두께가 위로만 자란다. 아래로 자라면 바닥에 파묻혀 안 보인다. */
	@Test
	void 두께가_위로_자란다() {
		TrialEndRainPanel.Slab slab = TrialEndRainPanel.slabAt(0, RADIUS);
		assertNotNull(slab);
		Matrix4f matrix = TrialEndRainPanel.transformOf(slab).getMatrixCopy();
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
		TrialEndRainPanel.Slab slab = TrialEndRainPanel.slabAt(1, RADIUS);
		assertNotNull(slab);
		CompoundTag tag = TrialEndRainPanel.shapeTag(TrialEndRainPanel.transformOf(slab));
		for (String key : new String[] {"transformation", "block_state", "brightness", "billboard",
				"view_range"}) {
			assertNotNull(tag.get(key), key + " 태그가 비었다 — 코덱이 안 써졌다");
		}
	}

	/**
	 * ⚠⚠ <b>바닐라가 읽는 길로 되읽어 본다.</b> 이 파일에서 가장 위험한 고리다.
	 *
	 * <p>태그 이름이나 코덱이 하나라도 어긋나면 바닐라가 <b>조용히 기본값</b>(공기 블록 · 단위
	 * 변환)을 쓰고, 그러면 보라 면이 <b>아예 안 보이는데 로그에는 한 줄도 안 남는다.</b>
	 * {@code DragonLastStandConePanelTest} 가 같은 자리를 같은 방법으로 지킨다.
	 */
	@Test
	void 바닐라가_읽는_길로_되읽힌다() {
		TrialEndRainPanel.Slab slab = TrialEndRainPanel.slabAt(3, RADIUS);
		assertNotNull(slab);
		Transformation wanted = TrialEndRainPanel.transformOf(slab);
		ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING,
				TestBootstrap.registries(), TrialEndRainPanel.shapeTag(wanted));

		Transformation read = input.read("transformation", Transformation.EXTENDED_CODEC)
				.orElse(null);
		assertNotNull(read,
				"transformation 이 되읽히지 않는다 — 판이 단위 변환으로 남아 한 칸짜리 유리가 된다");
		assertEquals(wanted.getMatrixCopy(), read.getMatrixCopy(), "행렬이 왕복에서 달라졌다");

		assertEquals(TrialEndRainPanel.glass(),
				input.read("block_state", BlockState.CODEC).orElse(null),
				"block_state 가 되읽히지 않는다 — 바닐라가 공기를 쓰면 판이 아예 안 보인다");
		assertEquals(Brightness.FULL_BRIGHT, input.read("brightness", Brightness.CODEC).orElse(null),
				"brightness 가 되읽히지 않는다 — 어두운 데서 면이 안 보인다");
		assertEquals(Display.BillboardConstraints.FIXED,
				input.read("billboard", Display.BillboardConstraints.CODEC).orElse(null),
				"billboard 가 되읽히지 않는다 — 판이 사람을 따라 돌아 버린다");
		assertTrue(input.getFloatOr("view_range", 0.0F) > 1.0F, "view_range 가 되읽히지 않는다");
	}

	/**
	 * 판은 <b>보라 색유리</b>다. 사람이 「보라색 바닥으로 투명바닥으로」라고 했다.
	 *
	 * <p>⚠ 자홍({@code magenta})이 아니다 — 사람이 전에 자홍({@code 0xC800C8})을 「가시성이 너무
	 * 안 좋다」고 직접 물렸다. 26.3 에서 색유리는 {@code ColorCollection} 한 칸이라
	 * {@code pick(DyeColor)} 로 꺼낸다.
	 */
	@Test
	void 판이_보라_색유리다() {
		assertEquals(DyeColor.PURPLE, TrialEndRainPanel.GLASS_COLOR);
		assertNotEquals(DyeColor.MAGENTA, TrialEndRainPanel.GLASS_COLOR,
				"사람이 물린 자홍 쪽으로 돌아갔다");
		String name = TrialEndRainPanel.glass().getBlock().toString();
		assertTrue(name.contains("purple_stained_glass"),
				"반투명 보라가 블록에서 나오지 않는다: " + name);
		// 「최후의 저항」의 면과 색이 달라야 한다. 그쪽은 빨강이고 뜻도 다른 카드다.
		assertNotEquals(DragonLastStandConePanel.glass(), TrialEndRainPanel.glass(),
				"부채꼴 면과 같은 블록이다 — 두 카드가 같은 판에 뜨면 구별되지 않는다");
	}

	/** 보이는 거리가 기본값 64칸보다 멀다. 아레나가 80칸이라 기본값으로는 반대편에서 안 보인다. */
	@Test
	void 보이는_거리가_아레나를_덮는다() {
		CompoundTag tag = TrialEndRainPanel.shapeTag(Transformation.IDENTITY);
		float viewRange = tag.getFloatOr("view_range", 0.0F);
		assertTrue(viewRange * 64.0F >= TrialRisks.ARENA_RADIUS * 2.0,
				"보이는 거리가 아레나 지름보다 짧다: " + viewRange * 64.0F);
	}

	// ------------------------------------------------------------------ 지우는 길

	/**
	 * ⚠⚠ <b>저장되지 않고 스스로도 타 없어진다.</b> 둘 중 하나라도 빠지면 다음 판까지 보라 판이
	 * 남을 수 있다.
	 *
	 * <p>한 볼리에 450개가 생기므로 <b>새면 한 번에 크게 샌다.</b> 그리고 {@code shouldBeSaved} 가
	 * 없으면 그 450개가 월드 저장 파일에 들어간다 — 서버 강제 종료에는
	 * {@code SERVER_STOPPING} 도 오지 않아 지우는 코드로 막을 수 없는 길이다.
	 */
	@Test
	void 저장되지_않고_스스로_사라진다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("shouldBeSaved"),
				"저장을 막지 않으면 서버 강제 종료에 판이 월드 파일에 들어간다");
		assertTrue(bytes.contains("discard"), "지우는 길이 없다");

		assertEquals(20, TrialEndRainPanel.FUSE_MARGIN_TICKS, "여유가 1초다");
		assertTrue(TrialEndRainPanel.fuseTicks(30) > 30,
				"심지가 예고(30틱)보다 짧으면 면이 착탄 전에 사라진다 — 보여 준 표식을 무르는 것이다");
		assertEquals(30 + TrialEndRainPanel.FUSE_MARGIN_TICKS, TrialEndRainPanel.fuseTicks(30),
				"심지를 예고 길이에서 직접 더할 것 — 예고를 늘리는 사람이 여기를 따로 고치게 하지 말 것");
		assertTrue(TrialEndRainPanel.fuseTicks(0) >= 1, "심지가 0 이면 깔리는 틱에 사라진다");
		assertTrue(TrialEndRainPanel.fuseTicks(30) < 40 + 30,
				"심지가 볼리 간격(40틱)+예고를 넘으면 지우는 줄이 다 빠졌을 때 다음 볼리까지 남는다");
	}

	/**
	 * 아무것도 안 깔았어도 지울 수 있고, 두 번 지워도 탈이 없다.
	 *
	 * <p>{@code dropAll()} 은 전투가 끝날 때 · 서버가 내려갈 때 · 이 카드가 한 번도 안 뽑힌 판에서
	 * 까지 불린다({@code TrialRisks.clearState} 가 실행기 전부를 조건 없이 지난다). 월드가 없는
	 * 시험에서 부를 수 있는 것이 이 길뿐이기도 하다.
	 */
	@Test
	void 깔지_않았어도_지울_수_있다() {
		TrialEndRainPanel.dropAll();
		assertEquals(0, TrialEndRainPanel.liveCount());
		TrialEndRainPanel.dropAll();
		assertEquals(0, TrialEndRainPanel.liveCount(), "두 번 지워도 탈이 없어야 한다");
		TrialEndRainPanel.drop("sharedfate:end_rain#0");
		TrialEndRainPanel.drop(null);
		assertEquals(0, TrialEndRainPanel.liveCount(), "없는 열쇠를 지워도 탈이 없어야 한다");
	}

	/** 블록을 한 칸도 놓지 않는다. 판은 개체이지 블록이 아니다. */
	@Test
	void 블록을_놓지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("setBlockAndUpdate"),
				"바닥을 색유리로 갈아 버리면 다음 전투의 발판이 달라진다");
		assertFalse(bytes.contains("removeBlock"));
		assertFalse(bytes.contains("destroyBlock"));
	}

	/**
	 * 시계를 스스로 읽지 않는다.
	 *
	 * <p>심지는 개체 제 {@code tick()} 에서 세므로 {@code now} 도 {@code getGameTime()} 도 필요
	 * 없다. {@code getGameTime} 을 부르면 얼어붙은 판에서 심지가 멈춰, 지우는 줄이 다 빠진 경우의
	 * 바닥이 함께 사라진다.
	 */
	@Test
	void 시계를_스스로_읽지_않는다() {
		assertFalse(classBytes().contains("getGameTime"),
				"심지는 개체 제 틱에서 센다 — 월드 시계를 읽을 이유가 없다");
	}

	/**
	 * ⚠⚠ <b>깔고 지우는 길이 전부 배선됐다.</b>
	 *
	 * <p>깔는 자리는 하나(볼리를 여는 틱)이고 지우는 자리는 셋이다 — 볼리가 터지는 틱 · 비가
	 * 끝나는 틱 · 월드가 바뀌는 틱. 그 아래에 심지가 있다. 자세한 목록은
	 * {@link TrialEndRainPanel} 클래스 설명에 있고, 배선이 끊겼는지는
	 * {@code TrialEndRainTest.바닥_면을_지우는_길이_전부_배선됐다} 가 상수 풀에서 본다.
	 *
	 * <p>여기서는 <b>반대 방향</b>을 본다 — 이 파일이 들고 있는 목록이 {@code dropAll} 하나로
	 * 전부 비는가. 열쇠마다 묶음을 들고 있으므로 한 묶음만 지우고 끝나면 남는다.
	 */
	@Test
	void 들고_있는_것이_한_줄로_전부_비워진다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("dropAll"), "전부 거두는 길이 없다");
		assertTrue(bytes.contains("liveCount"), "시험이 들여다볼 창이 없다");
		// 월드를 만지지 않고 지운다. SERVER_STOPPED 에서도 불리므로 개체를 찾아서는 안 된다.
		assertFalse(bytes.contains("getEntities"),
				"월드에서 개체를 찾고 있다 — SERVER_STOPPED 에서는 레벨이 닫혀 있어 그 길이 막힌다");
		assertFalse(bytes.contains("getEntity"), "UUID 로 되찾는 길은 서버가 내려갈 때 막힌다");
	}

	// ------------------------------------------------------------------ 도우미

	/**
	 * 겉 클래스와 <b>개체 하위 클래스</b>를 함께 읽는다.
	 *
	 * <p>{@code shouldBeSaved}·{@code tick} 은 {@code TrialEndRainPanel$Panel} 쪽에 있으므로 겉만
	 * 읽으면 「없다」로 나온다.
	 */
	private static String classBytes() {
		return read("/com/sharedfate/sync/TrialEndRainPanel.class")
				+ read("/com/sharedfate/sync/TrialEndRainPanel$Panel.class");
	}

	private static String read(String path) {
		try (InputStream in = TrialEndRainPanelTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
