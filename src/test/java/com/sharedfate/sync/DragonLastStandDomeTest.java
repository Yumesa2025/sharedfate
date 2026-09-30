package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>반구 블록 파괴</b>가 기대고 있는 사실들.
 *
 * <p>여기서 붙드는 것 가운데 <b>둘은 월드 삭제와 직결</b>된다.
 *
 * <ol>
 *   <li><b>바닥을 부수지 않는다</b> — 엔드 섬에 구멍이 나면 허공 낙사이고 공유 체력이라 한 사람이
 *       떨어지면 팀 전멸 → 월드 삭제다. 사람이 「바닥은 안 부수고」라고 못박았다</li>
 *   <li><b>기반암·출구 포털·흑요석 기둥을 부수지 않는다</b> — 포털을 지우면 판을 끝낼 수 없다</li>
 *   <li><b>성능</b> — 반구 안이 3만 칸이라 거름망이 없으면 1초마다 서버가 멈춘다</li>
 * </ol>
 *
 * <p>⚠ {@code runClient} 가 이 환경에서 죽어 <b>눈으로 확인할 수 없다.</b> 그래서 기하학과
 * 거름망과 태그를 전부 순수 함수·순수 값으로 빼내 여기서 붙든다.
 */
class DragonLastStandDomeTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	/** 정적 상태라 시험끼리 샌다. 앞뒤로 비운다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonLastStandDome.clearState();
	}

	// ------------------------------------------------------------------ 사람이 정한 값

	/** 반경 25 · 20틱 주기. 둘 다 사람이 정한 값이다. */
	@Test
	void 반경_25에_20틱마다다() {
		assertEquals(25.0, DragonLastStandDome.RADIUS, 1.0E-9, "사람이 정한 값이다");
		assertEquals(20, DragonLastStandDome.SWEEP_TICKS, "1초다 — 사람이 정한 값이다");
	}

	/**
	 * ⚠ <b>흑요석 기둥에 닿지 않는다.</b> 사람 말이 「기둥은 안 들어갈 정도로」였다.
	 *
	 * <p>기둥은 중앙에서 40칸 넘게 떨어져 있고 이 저장소가 그 줄을 {@code START_RADIUS}(42)로 쓴다.
	 * 반경을 올리는 사람이 여기서 멈춘다.
	 */
	@Test
	void 반구가_기둥에_닿지_않는다() {
		assertTrue(DragonLastStandDome.RADIUS < TrialRisks.ARENA_RADIUS,
				"섬 반경(40)을 넘으면 반구가 허공에 걸친다");
		assertTrue(DragonLastStandDome.RADIUS < DragonLastStandZone.START_RADIUS,
				"기둥 줄(42)에 닿으면 사람이 「기둥은 안 들어갈 정도로」라고 한 것이 거짓이 된다");
		assertTrue(DragonLastStandZone.START_RADIUS - DragonLastStandDome.RADIUS >= 15.0,
				"기둥까지 여유가 15칸은 있어야 한다 — 실제 "
						+ (DragonLastStandZone.START_RADIUS - DragonLastStandDome.RADIUS));
	}

	// ------------------------------------------------------------------ ⚠ 바닥·포털·기둥

	/**
	 * ⚠⚠ <b>바닥을 부수지 않는다 — 두 겹 가운데 첫째.</b>
	 *
	 * <p>기준 높이가 드래곤을 못박아 둔 자리({@code = 중앙 기반암 포디움 맨 위 바로 위})이고
	 * 부수는 것은 {@code y ≥ 기준 높이} 뿐이다. 섬 표면은 그보다 <b>네 칸 아래</b>다.
	 */
	@Test
	void 기준_높이가_드래곤_발밑이다() {
		assertEquals(68, DragonLastStandDome.baselineY(new Vec3(0.5, 68.0, 0.5)),
				"드래곤을 못박아 둔 자리의 y 를 그대로 쓴다");
		assertEquals(68, DragonLastStandDome.baselineY(new Vec3(0.5, 68.9, 0.5)),
				"내림이라야 「그 칸부터」가 된다");
		assertEquals(-5, DragonLastStandDome.baselineY(new Vec3(0.5, -4.5, 0.5)),
				"음수에서도 내림이어야 한다 — (int) 형변환은 0 쪽으로 자른다");
	}

	/**
	 * ⚠⚠ <b>반구다. 원기둥이 아니다.</b>
	 *
	 * <p>이 한 줄이 빠지면 가장자리에서도 25칸 높이까지 부수는 원기둥이 되고, 사람이 정한 것은
	 * 「반구형태로」다.
	 */
	@Test
	void 가장자리로_갈수록_낮아진다() {
		int baseline = 68;
		assertEquals(baseline + 25, DragonLastStandDome.domeTop(baseline, 0.0),
				"중심에서는 반경만큼 올라간다");
		// 수평 25칸에서는 한 칸도 못 올라간다.
		assertEquals(baseline, DragonLastStandDome.domeTop(baseline, 25.0 * 25.0));
		assertEquals(baseline, DragonLastStandDome.domeTop(baseline, 30.0 * 30.0),
				"반구 밖은 기준 높이 그대로여야 한다 — 음수 제곱근이 나오면 안 된다");

		// 줄어들기만 한다.
		int previous = Integer.MAX_VALUE;
		for (double flat = 0.0; flat <= 25.0; flat += 0.5) {
			int top = DragonLastStandDome.domeTop(baseline, flat * flat);
			assertTrue(top <= previous, flat + "칸에서 높이가 되레 올랐다");
			assertTrue(top >= baseline, flat + "칸에서 기준 높이보다 낮아졌다 — 바닥을 부술 수 있다");
			// 실제로 반구 안인지 값으로 확인한다.
			double dy = top - baseline;
			assertTrue(flat * flat + dy * dy <= 25.0 * 25.0 + 1.0E-6,
					flat + "칸의 맨 위(" + top + ")가 반구 밖이다");
			previous = top;
		}
	}

	/**
	 * ⚠⚠ <b>바닥을 부수지 않는다 — 두 겹 가운데 둘째.</b>
	 *
	 * <p>26.3 {@code BlockTags.DRAGON_IMMUNE} 에 <b>{@code end_stone} 과 {@code bedrock} 이
	 * 들어 있다.</b> 섬을 이루는 블록이 그 둘이므로, 설령 기준 높이 쪽 판단이 틀리더라도 섬은
	 * 한 칸도 안 없어진다. 출구 포털과 기둥도 같은 태그가 지킨다.
	 *
	 * <p>이 시험이 깨지면 <b>판이 올라 태그가 바뀐 것</b>이고, 그때는 우리 목록을 따로 들어야 할지
	 * 다시 판단해야 한다.
	 *
	 * <h2>⚠ 태그를 {@code state.is(...)} 로 물을 수 없다 — 그래서 <b>태그 파일</b>을 읽는다</h2>
	 *
	 * <p>{@code BlockState.is(TagKey)} 는 태그가 <b>bind 된 뒤</b>에만 답한다. 시험 환경에는 살아
	 * 있는 서버도 데이터팩 적용도 없어 {@code Bootstrap} 만으로는 그 물음이
	 * {@code IllegalStateException} 으로 터진다(실제로 걸렸다). 그래서 바닐라 jar 안의
	 * <b>태그 정의 파일을 그대로 읽어</b> 목록을 확인한다 — 어차피 그것이 태그의 근거이고, 서버가
	 * 없어도 판이 올라 목록이 바뀌면 여기서 먼저 깨진다.
	 *
	 * <p>{@link DragonLastStandDome#breakable} 자체는 <b>공기</b>로만 굴려 볼 수 있다. 그 갈래가
	 * 태그를 보기 전에 답하는 유일한 길이다.
	 */
	@Test
	void 섬과_포털과_기둥이_태그로_지켜진다() {
		assertFalse(DragonLastStandDome.breakable(Blocks.AIR.defaultBlockState()),
				"공기를 부수면 같은 자리를 매 훑기마다 다시 센다");

		String immune = readTag("dragon_immune");
		// 바닥을 지키는 둘째 겹. 이 둘이 빠지면 기준 높이 하나에만 목숨이 달린다.
		assertTrue(immune.contains("\"minecraft:end_stone\""),
				"end_stone 이 DRAGON_IMMUNE 에서 빠졌다 — 섬 바닥을 지키는 둘째 겹이 사라졌다");
		assertTrue(immune.contains("\"minecraft:bedrock\""), "기반암 포디움이다");
		// 판을 끝내는 길.
		assertTrue(immune.contains("\"minecraft:end_portal\""),
				"출구 포털을 지우면 판을 끝낼 수 없다");
		assertTrue(immune.contains("\"minecraft:end_portal_frame\""));
		assertTrue(immune.contains("\"minecraft:end_gateway\""));
		// 기둥. 반경 25 에 닿지는 않지만 태그가 한 겹 더 막는다.
		assertTrue(immune.contains("\"minecraft:obsidian\""));
		assertTrue(immune.contains("\"minecraft:iron_bars\""), "기둥 위 쇠창살 우리다");

		// 사람이 쌓는 블록은 여기 없다. 그것이 이 규칙이 있는 이유다.
		assertFalse(immune.contains("\"minecraft:cobblestone\""),
				"사람이 발판으로 쓰는 블록이 목록에 들어오면 「블록으로 패턴 피할수잇으니」가 남는다");
		assertFalse(immune.contains("\"minecraft:oak_planks\""));
	}

	// ------------------------------------------------------------------ ⚠ 성능

	/**
	 * ⚠ <b>3만 칸을 2천 번으로 줄인다.</b>
	 *
	 * <p>{@code TrialDryWorld} 가 「120만 칸을 392번으로」라고 적어 둔 것과 같은 자리의 값이다.
	 * 반경을 올리는 사람이 성능을 눈으로 세지 않아도 여기서 먼저 멈춘다.
	 */
	@Test
	void 훑기가_2천_번쯤_본다() {
		// 반구 안의 칸 수. 거름망이 없으면 이만큼 읽어야 한다.
		double cells = 2.0 / 3.0 * Math.PI * Math.pow(DragonLastStandDome.RADIUS, 3.0);
		assertTrue(cells > 30_000.0, "반구 안이 3만 칸쯤이다 — 실제 " + Math.round(cells));

		int columns = DragonLastStandDome.columnProbesPerSweep(0.5, 0.5);
		int sections = DragonLastStandDome.sectionProbesPerSweep(0.5, 0.5, 68);
		assertEquals(1961, columns, "반구가 덮는 열의 수다 — 하이트맵을 이만큼 읽는다");
		assertEquals(32, sections, "청크 16개 × 구획 2개다");

		int probes = columns + sections;
		assertTrue(probes < cells / 10.0,
				"거름망이 열 배도 못 줄인다 — 실제 " + probes + " 대 " + Math.round(cells));
		// 20틱마다이므로 한 틱 평균이 이것의 1/20 이다.
		assertTrue(probes / (double) DragonLastStandDome.SWEEP_TICKS < 150.0,
				"한 틱 평균 " + probes / (double) DragonLastStandDome.SWEEP_TICKS + "번이다");
	}

	/** 한 번에 부수는 수에 천장이 있다. 없으면 발판 한 장에 수천 칸이 한 틱에 나간다. */
	@Test
	void 한_번에_부수는_수에_천장이_있다() {
		assertTrue(DragonLastStandDome.MAX_BREAKS_PER_SWEEP > 0);
		// 5×5 지붕(25칸)과 16×16 발판(256칸)은 한 번에 사라져야 한다.
		assertTrue(DragonLastStandDome.MAX_BREAKS_PER_SWEEP >= 256,
				"사람이 실제로 숨을 만한 구조물은 한 번에 없어져야 한다");
		assertTrue(DragonLastStandDome.MAX_BREAKS_PER_SWEEP
						< DragonLastStandDome.columnProbesPerSweep(0.5, 0.5),
				"천장이 열 수보다 크면 천장이 아니다");
	}

	/** 훑기 시계가 {@code clearState} 로 비워진다. 정적이라 월드보다 오래 산다. */
	@Test
	void 시계를_비운다() {
		assertFalse(DragonLastStandDome.sweeping(), "처음에는 돌고 있지 않아야 한다");
		DragonLastStandDome.clearState();
		assertFalse(DragonLastStandDome.sweeping());
	}

	// ------------------------------------------------------------------ 규약

	/**
	 * ⚠ <b>{@code removeBlock} 이다. {@code destroyBlock} 이 아니다.</b>
	 *
	 * <p>{@code destroyBlock} 을 쓰면 Fabric 의 {@code PlayerBlockBreakEvents.AFTER} 가 발화해
	 * <b>증강의 블록 파괴 효과(메아리 채굴 · 같은 종류 채굴)가 딸려 돈다</b> — 반구가 부순 블록이
	 * 사람의 채굴로 읽히는 순간 경험치·드롭·연쇄가 전부 얹힌다.
	 * {@code PerkBlockBreaks} 가 같은 판단을 같은 근거로 적어 두었다.
	 *
	 * <p>그리고 드롭이 없어야 한다 — 사람이 <b>「아니 파괴」</b>라고 정했다.
	 */
	@Test
	void 드롭_없이_removeBlock_으로_지운다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("removeBlock"), "블록을 지우는 줄이 없다");
		assertFalse(bytes.contains("destroyBlock"),
				"destroyBlock 을 쓰면 증강의 채굴 효과가 딸려 돈다");
		assertFalse(bytes.contains("dropResources"), "드롭 없음이 사람이 정한 것이다");
		assertFalse(bytes.contains("popResource"));
	}

	/**
	 * 바닐라 드래곤과 <b>같은 세 물음</b>을 본다.
	 *
	 * <p>우리 목록을 따로 적으면 판이 올라 바닐라가 한 줄 더할 때 우리만 뒤처진다.
	 */
	@Test
	void 바닐라_드래곤과_같은_물음을_본다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("DRAGON_IMMUNE"), "부수면 안 되는 것 목록을 안 본다");
		assertTrue(bytes.contains("DRAGON_TRANSPARENT"),
				"바닐라 드래곤이 「지나가도 되는 것」으로 치는 목록을 안 본다");
		assertTrue(bytes.contains("MOB_GRIEFING"),
				"게임룰을 안 보면 「드래곤은 안 부수는데 반구는 부순다」가 된다");
	}

	/** 빈 구획을 건너뛴다. {@code TrialDryWorld} 가 {@code hasFluid} 로 한 것과 같은 수법이다. */
	@Test
	void 빈_구획을_건너뛴다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("hasOnlyAir"),
				"구획을 통째로 건너뛰는 줄이 없다 — TrialDryWorld 의 hasFluid 와 같은 자리다");
		assertTrue(bytes.contains("getHeight"),
				"하이트맵으로 열을 건너뛰지 않으면 3만 칸을 매 초 다 읽는다");
		assertTrue(bytes.contains("WORLD_SURFACE"),
				"MOTION_BLOCKING 으로는 횃불·양탄자로 만든 지붕을 놓친다");
		assertTrue(bytes.contains("getChunkNow"),
				"청크를 억지로 불러오면 블록을 부수려다 지형을 새로 만든다");
	}

	/** 사람에게 피해를 주지 않는다. 이것은 블록 규칙이고 피해는 패턴의 몫이다. */
	@Test
	void 사람을_때리지_않는다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("hurtServer"), "반구는 블록만 만진다");
		assertFalse(bytes.contains("setDeltaMovement"), "밀지도 않는다");
	}

	// ------------------------------------------------------------------ 도우미

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStandDome.class");
	}

	/**
	 * 바닐라 태그 정의 파일. {@code state.is(TagKey)} 를 쓸 수 없는 까닭은
	 * {@link #섬과_포털과_기둥이_태그로_지켜진다} 에 적어 두었다.
	 */
	private static String readTag(String name) {
		return read("/data/minecraft/tags/block/" + name + ".json");
	}

	private static String read(String path) {
		try (InputStream in = DragonLastStandDomeTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
