package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
 *
 * <h2>2026-10-01 — 훑기 바닥만 네 칸 내렸다</h2>
 *
 * <p>사람이 <b>「섬 표면에서 3칸까지 쌓은 발판·지붕이 안 부서진다」를 막는다</b>고 답했다. 그래서
 * <b>훑기 바닥</b>({@code DragonLastStandDome.sweepFloorY})만
 * {@code EndPodiumFeature.PODIUM_PILLAR_HEIGHT}(= 4)만큼 내렸고, <b>반구의 기하 원점</b>
 * ({@code domeOriginY})은 그대로다. 여기서 못박는 것이 그 둘이 <b>서로 따로</b>라는 사실이다 —
 * 원점을 함께 내리면 눈에 보이는 반구가 네 칸 내려앉는다.
 *
 * <h2>2026-10-04 — 물도 부순다, 그리고 같은 날 용암까지</h2>
 *
 * <p>⚠ <b>이 묶음은 같은 날의 두 걸음을 함께 들고 있다.</b> 걸음을 지우지 않는 것이 이 저장소의
 * 규약이다.
 *
 * <ol>
 *   <li><b>첫 걸음 — 물만.</b> 사람 말이 <b>「주위블럭 부수는거에서 물은 안부수는데 물도
 *       부수게」</b> 였다. 원인은 태그도 하이트맵도 아니라 <b>{@code Level.removeBlock} 이 유체를
 *       일부러 남기는 메서드</b>라는 것이었고, 그 바닐라 사실을
 *       {@link #물도_용암도_removeBlock_으로_한_칸도_안_없어진다} 가 <b>값으로</b> 붙든다. 그때
 *       <b>용암은 그대로 두었고</b> 시험 이름이 {@code 물만_없애고_용암은_그대로_둔다} 였다</li>
 *   <li><b>둘째 걸음 — 같은 날, 사람이 「전부 고쳐」.</b> 물었고 답이 왔다. 그래서
 *       {@code holdsWater} 를 <b>{@code holdsFluid} 로 이름까지 고쳐</b> 용암 두 객체를 함께 보게
 *       넓혔고, 위의 시험은 <b>뜻을 뒤집어</b> {@link #물과_용암을_함께_없앤다} 가 되었다. 옛 이름이
 *       거짓이 되었으므로 <b>이름을 남겨 두지 않고 고쳤다</b></li>
 * </ol>
 *
 * <p>이 묶음에서 가장 중요한 것은 <b>성능이 그대로라는 시험</b>
 * ({@link #유체를_찾으려고_하이트맵_거름망을_버리지_않았다})이다. 유체를 찾으려고
 * {@code TrialDryWorld} 처럼 하이트맵을 버리면 <b>2천 열을 매 초 깊게 훑는다</b> — 그럴 필요가
 * 없는 까닭(물도 용암도 공기가 아니다)과 늘지 않은 조회 횟수를 함께 못박아 두었다. ⚠ <b>용암을
 * 넓히면서도 그 두 수(1961 · 32)가 한 번도 안 늘었다</b> — 넓힌 자리가 이미 통과한 열 안에서
 * 상태를 한 번 더 묻는 것뿐이기 때문이다.
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
	 * <b>기하 원점은 드래곤 발밑이다.</b> 반구의 천장이 전부 이 값에서 나온다.
	 *
	 * <p>그 자리는 <b>중앙 기반암 포디움 구조물 맨 위의 바로 위</b>이고, 섬 표면보다 네 칸 높다.
	 * 훑기 바닥은 이것이 <b>아니다</b> — {@link #훑기_바닥이_기하_원점보다_네_칸_아래다} 를 볼 것.
	 */
	@Test
	void 기하_원점이_드래곤_발밑이다() {
		assertEquals(68, DragonLastStandDome.domeOriginY(new Vec3(0.5, 68.0, 0.5)),
				"드래곤을 못박아 둔 자리의 y 를 그대로 쓴다");
		assertEquals(68, DragonLastStandDome.domeOriginY(new Vec3(0.5, 68.9, 0.5)),
				"내림이라야 「그 칸부터」가 된다");
		assertEquals(-5, DragonLastStandDome.domeOriginY(new Vec3(0.5, -4.5, 0.5)),
				"음수에서도 내림이어야 한다 — (int) 형변환은 0 쪽으로 자른다");
	}

	/**
	 * ⚠⚠ <b>바닥을 부수지 않는다 — 두 겹 가운데 첫째.</b> 그리고 <b>2026-10-01 에 이 겹이 네 칸
	 * 내려왔다.</b>
	 *
	 * <p>훑기 바닥 = 기하 원점 − {@code EndPodiumFeature.PODIUM_PILLAR_HEIGHT}(= 4) = <b>포디움
	 * 원점 {@code p.y}</b> = <b>섬 표면 위의 첫 빈 칸</b>이다. 부수는 것은 {@code y ≥ 훑기 바닥}
	 * 뿐이고 섬 표면의 맨 위 블록은 그보다 <b>한 칸 아래</b>라 한 번도 읽히지 않는다.
	 *
	 * <p>숫자 4 를 우리가 적지 않는 것도 여기서 붙든다 — 판이 올라 바닐라가 기둥 높이를 바꾸면
	 * 훑기 바닥이 <b>저절로</b> 따라 움직여야 한다.
	 */
	@Test
	void 훑기_바닥이_기하_원점보다_네_칸_아래다() {
		assertEquals(4, EndPodiumFeature.PODIUM_PILLAR_HEIGHT,
				"바닐라 포디움 기둥 높이가 4 가 아니면 「원점 − 4 = 표면 위 첫 빈 칸」이 깨진다");
		assertEquals(EndPodiumFeature.PODIUM_PILLAR_HEIGHT,
				DragonLastStandDome.PODIUM_PILLAR_HEIGHT,
				"우리 숫자를 적지 않고 바닐라 상수를 그대로 참조한다");

		Vec3 anchor = new Vec3(0.5, 68.0, 0.5);
		int origin = DragonLastStandDome.domeOriginY(anchor);
		int floor = DragonLastStandDome.sweepFloorY(anchor);
		assertEquals(64, floor, "68 − 4 = 64 다");
		assertEquals(4, origin - floor,
				"정확히 네 칸이다 — 더 내리면 섬 표면이 범위에 들어오고 거름망이 죽는다");
		assertEquals(64, DragonLastStandDome.sweepFloorY(new Vec3(0.5, 68.9, 0.5)),
				"내림이 바닥에도 그대로 간다");
		assertEquals(-9, DragonLastStandDome.sweepFloorY(new Vec3(0.5, -4.5, 0.5)),
				"음수에서도 내림 뒤에 빼야 한다 — (−5) − 4 = −9");
	}

	/**
	 * ⚠⚠ <b>반구의 천장은 바뀌지 않았다.</b> 바닥만 내렸다.
	 *
	 * <p>여기서 못박는 수는 <b>2026-10-01 이전과 같은 수</b>다. 이 시험이 깨지면 누가
	 * {@code domeTop} 이나 {@code ceiling} 에 <b>훑기 바닥을 넣은 것</b>이고, 그 순간 눈에 보이는
	 * 반구가 네 칸 내려앉는다 — 사람이 「반구 모양은 그대로」라고 못박았다.
	 */
	@Test
	void 반구_천장은_그대로다() {
		Vec3 anchor = new Vec3(0.5, 68.0, 0.5);
		int origin = DragonLastStandDome.domeOriginY(anchor);
		assertEquals(68, origin);

		assertEquals(93, DragonLastStandDome.domeTop(origin, 0.0),
				"중심 천장은 예나 지금이나 68 + 25 = 93 이다");
		assertEquals(origin + Mth.floor(DragonLastStandDome.RADIUS),
				DragonLastStandDome.domeTop(origin, 0.0),
				"sweep() 의 ceiling 과 같은 셈이라야 한다 — 둘 다 기하 원점에서 잰다");
		assertEquals(68, DragonLastStandDome.domeTop(origin, 25.0 * 25.0),
				"가장자리 천장도 옛 값 그대로 기하 원점이다");

		// ⚠ 넣으면 안 되는 값을 일부러 넣어 본다. 이 수가 실제로 쓰이면 반구가 내려앉는다.
		int sunken = DragonLastStandDome.domeTop(DragonLastStandDome.sweepFloorY(anchor), 0.0);
		assertEquals(89, sunken);
		assertEquals(4, DragonLastStandDome.domeTop(origin, 0.0) - sunken,
				"domeTop 에 훑기 바닥을 넣으면 천장이 네 칸 내려앉는다 — sweepChunk 가 origin 을 넘긴다");
	}

	/**
	 * ⚠ <b>표면에서 세 칸까지 쌓은 발판이 이제 범위에 든다.</b> 이것이 2026-10-01 작업의 목적이다.
	 *
	 * <p>섬에 선 사람이 놓는 첫 블록은 <b>표면 위 첫 빈 칸 = 훑기 바닥</b>이다. 그 위 셋
	 * ({@code 바닥}·{@code 바닥+1}·{@code 바닥+2})이 옛 기준(기하 원점)에서는 모두 아래였다.
	 */
	@Test
	void 표면에_쌓은_세_칸이_이제_범위에_든다() {
		Vec3 anchor = new Vec3(0.5, 68.0, 0.5);
		int origin = DragonLastStandDome.domeOriginY(anchor);
		int floor = DragonLastStandDome.sweepFloorY(anchor);
		// 섬 표면의 맨 위 블록. 하이트맵이 돌려주는 값이 이것이다.
		int surface = floor - 1;

		for (int step = 1; step <= 3; step++) {
			int placed = surface + step;
			assertTrue(placed >= floor,
					"표면 +" + step + "칸(" + placed + ")이 훑기 바닥 아래다 — 발판이 살아남는다");
			assertTrue(placed < origin,
					"표면 +" + step + "칸은 옛 기준으로는 걸리지 않던 칸이라야 한다 — 그래서 내렸다");
			// 가장자리에서도 든다. domeTop 의 최솟값이 기하 원점이라 바닥 쪽 네 칸은 늘 열린다.
			assertTrue(placed <= DragonLastStandDome.domeTop(origin, DragonLastStandDome.RADIUS
							* DragonLastStandDome.RADIUS),
					"반구 가장자리에서 표면 +" + step + "칸이 천장 위로 밀려났다");
		}
		assertTrue(surface < floor, "섬 표면 자신은 여전히 범위 밖이다 — 이것이 첫째 겹이다");
	}

	/**
	 * ⚠⚠ <b>맨손 아레나에서 하이트맵 거름망이 여전히 모든 열을 거른다.</b> 이 성질이 이 클래스
	 * 성능의 전부다.
	 *
	 * <p>{@code sweepChunk} 의 {@code if (top < sweepFloor) continue;} 가 아무것도 쌓지 않은 판에서
	 * <b>모든 열</b>에 대해 참이어야 한다. 근거는 두 줄이다.
	 *
	 * <ol>
	 *   <li>훑기 바닥 = 포디움 원점 {@code p.y} 이고, {@code EnderDragonFight} 가 그 {@code p} 를
	 *       <b>하이트맵</b>으로 잡으므로 {@code p.y} 는 「섬 표면 위의 첫 빈 칸」이다</li>
	 *   <li>곧 하이트맵이 돌려주는 맨 위 블록은 {@code p.y − 1} 이고
	 *       {@code (p.y − 1) < p.y} 는 <b>참</b>이다</li>
	 * </ol>
	 *
	 * <p>여유가 <b>한 칸</b>뿐이라 바닥을 한 칸이라도 더 내리면 거름망이 통째로 죽는다(1961열 ×
	 * 최대 30칸을 매 초 읽는다). 그 한 칸을 여기서 못박는다.
	 */
	@Test
	void 맨손_아레나에서_하이트맵이_모든_열을_거른다() {
		Vec3 anchor = new Vec3(0.5, 68.0, 0.5);
		int floor = DragonLastStandDome.sweepFloorY(anchor);

		int podiumOrigin = DragonLastStandDome.domeOriginY(anchor)
				- EndPodiumFeature.PODIUM_PILLAR_HEIGHT;
		assertEquals(podiumOrigin, floor, "훑기 바닥이 포디움 원점 p.y 와 같은 칸이라야 한다");

		// 하이트맵이 돌려주는 「맨 위 블록의 y」. 맨손 아레나에서는 섬 표면 그 자신이다.
		int heightmapTop = podiumOrigin - 1;
		assertTrue(heightmapTop < floor,
				"top < sweepFloor 가 거짓이 되면 모든 열이 통과해 1961열을 매 초 다 읽는다");
		assertEquals(1, floor - heightmapTop,
				"여유가 한 칸이다 — 한 칸이라도 더 내리면 이 거름망이 죽는다");
	}

	/**
	 * ⚠⚠ <b>반구다. 원기둥이 아니다.</b>
	 *
	 * <p>이 한 줄이 빠지면 가장자리에서도 25칸 높이까지 부수는 원기둥이 되고, 사람이 정한 것은
	 * 「반구형태로」다.
	 */
	@Test
	void 가장자리로_갈수록_낮아진다() {
		int origin = 68;
		assertEquals(origin + 25, DragonLastStandDome.domeTop(origin, 0.0),
				"중심에서는 반경만큼 올라간다");
		// 수평 25칸에서는 한 칸도 못 올라간다.
		assertEquals(origin, DragonLastStandDome.domeTop(origin, 25.0 * 25.0));
		assertEquals(origin, DragonLastStandDome.domeTop(origin, 30.0 * 30.0),
				"반구 밖은 기하 원점 그대로여야 한다 — 음수 제곱근이 나오면 안 된다");

		// 줄어들기만 한다.
		int previous = Integer.MAX_VALUE;
		for (double flat = 0.0; flat <= 25.0; flat += 0.5) {
			int top = DragonLastStandDome.domeTop(origin, flat * flat);
			assertTrue(top <= previous, flat + "칸에서 높이가 되레 올랐다");
			assertTrue(top >= origin, flat + "칸에서 기하 원점보다 낮아졌다 — 그러면 훑기 바닥"
					+ "(원점 − 4)보다 낮아질 수 있고 열의 범위가 비뚤어진다");
			// 실제로 반구 안인지 값으로 확인한다.
			double dy = top - origin;
			assertTrue(flat * flat + dy * dy <= 25.0 * 25.0 + 1.0E-6,
					flat + "칸의 맨 위(" + top + ")가 반구 밖이다");
			previous = top;
		}
	}

	/**
	 * ⚠⚠ <b>바닥을 부수지 않는다 — 두 겹 가운데 둘째.</b>
	 *
	 * <p>26.3 {@code BlockTags.DRAGON_IMMUNE} 에 <b>{@code end_stone} 과 {@code bedrock} 이
	 * 들어 있다.</b> 섬을 이루는 블록이 그 둘이므로, 설령 훑기 바닥 쪽 판단이 틀리더라도 섬은
	 * 한 칸도 안 없어진다. 출구 포털과 기둥도 같은 태그가 지킨다.
	 *
	 * <p>⚠ <b>2026-10-01 부터 포디움에는 이 겹이 전부다.</b> 바닥을 네 칸 내리면서 기반암 기둥
	 * ({@code 바닥 … 바닥+3})·테두리 기반암·엔드 포털({@code 바닥})이 <b>훑는 범위 안에 들어왔다.</b>
	 * 전에는 「기준 높이 아래」라는 겹이 하나 더 있었다 — 이제 없다. 이 시험이 깨지면
	 * <b>출구 포털이 지워질 수 있다.</b>
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
		// 바닥을 지키는 둘째 겹. 이 둘이 빠지면 훑기 바닥 하나에만 목숨이 달린다.
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

		String transparent = readTag("dragon_transparent");

		// ⚠ 포디움 벽 횃불은 지켜지지 않는다 — 바닥을 내려 범위에 들어왔는데 두 태그 어디에도
		// 없다. 연출뿐이라 그대로 두기로 했고, 그 판단을 Dome 클래스 설명에 적어 두었다.
		assertFalse(immune.contains("\"minecraft:wall_torch\""),
				"횃불이 태그에 들어왔다 — Dome 클래스 설명의 「지켜지지 않는 것」을 고칠 것");
		assertFalse(transparent.contains("wall_torch"),
				"횃불이 DRAGON_TRANSPARENT 에 들어왔다 — 같은 문단을 고칠 것");

		// ⚠⚠ 2026-10-04 — 「물·용암이 안 부서진다」가 태그 탓이 아니라는 근거다. 이것이 깨지면
		// breakable 이 유체에 거짓을 돌려주게 되고, 그 순간 유체 작업이 통째로 무효가 된다.
		assertFalse(immune.contains("\"minecraft:water\""),
				"물이 DRAGON_IMMUNE 에 들어왔다 — clear 가 불리지 않아 물이 다시 안 부서진다");
		assertFalse(immune.contains("\"minecraft:lava\""),
				"용암이 DRAGON_IMMUNE 에 들어왔다 — clear 가 불리지 않아 사람이 「전부 고쳐」라고 한 "
						+ "것이 되돌아간다");
		assertFalse(transparent.contains("water"),
				"물이 DRAGON_TRANSPARENT 에 들어왔다 — breakable 이 물에 거짓을 돌려준다");
		assertFalse(transparent.contains("lava"),
				"용암이 DRAGON_TRANSPARENT 에 들어왔다 — breakable 이 용암에 거짓을 돌려준다");
		assertEquals(2, transparent.split("minecraft:", -1).length - 1,
				"dragon_transparent 는 light·#fire 두 줄뿐이다 — 늘어나면 Dome 설명을 다시 읽을 것");

		// 사람이 쌓는 블록은 여기 없다. 그것이 이 규칙이 있는 이유다.
		assertFalse(immune.contains("\"minecraft:cobblestone\""),
				"사람이 발판으로 쓰는 블록이 목록에 들어오면 「블록으로 패턴 피할수잇으니」가 남는다");
		assertFalse(immune.contains("\"minecraft:oak_planks\""));
	}

	// ------------------------------------------------------------------ ⚠ 물 (2026-10-04)

	/**
	 * ⚠⚠ <b>물도 용암도 안 부서졌던 진짜 원인.</b> 사람 말이 둘이다 —
	 * <b>「주위블럭 부수는거에서 물은 안부수는데 물도 부수게」</b> 뒤에 같은 날 <b>「전부 고쳐」</b>.
	 *
	 * <p>원인은 태그도 하이트맵도 아니라 <b>{@code Level.removeBlock} 자신</b>이다. 그 메서드는
	 * {@code setBlock(pos, getFluidState(pos).createLegacyBlock(), 3)} 이라 <b>유체를 일부러
	 * 남긴다</b> — 유체 칸에서는 <b>넣는 값이 들어 있던 값과 같은 객체</b>가 되고,
	 * {@code LevelChunk.setBlockState} 가 {@code 옛 값 == 새 값} 이면 그 자리에서 되돌아가므로
	 * <b>아무 일도 일어나지 않는다.</b>
	 *
	 * <p>⚠ <b>그 줄에 유체 종류가 안 들어간다</b>는 것이 「물과 용암이 같은 원인」의 전부다. 그래서
	 * 여기서 <b>물 셋과 용암 둘을 같은 꼴로</b> 붙든다 — 물만 붙들고 있던 2026-10-04 의 첫 걸음에서
	 * 용암 한 줄이 「사람에게 물을 근거」로 남아 있었고, 같은 날 사람이 「전부 고쳐」라고 해서 그
	 * 줄이 <b>이제는 고친 근거</b>가 되었다.
	 *
	 * <p>여기서 그 바닐라 사실을 <b>값으로</b> 붙든다. 판이 올라 {@code removeBlock} 이 공기를
	 * 넣도록 바뀌면 이 시험이 먼저 깨지고, 그때는 {@code clear} 의 유체 갈래를 지워도 된다.
	 *
	 * <p>⚠ <b>{@code breakable(물)} 을 여기서 굴려 볼 수 없다.</b> 그 함수가 태그를 묻고 시험
	 * 환경에는 bind 가 없어 {@code IllegalStateException: Tags not bound} 로 터진다(실제로 걸렸다 —
	 * {@link #섬과_포털과_기둥이_태그로_지켜진다} 가 같은 까닭을 적어 두었다). <b>물도 용암도 두 태그
	 * 어디에도 없다</b>는 것은 그쪽이 태그 파일을 읽어 못박는다. 같은 까닭으로
	 * {@code DragonLastStandDome.holdsFluid} 도 {@code FluidTags} 가 아니라 유체 객체를 비교한다.
	 */
	@Test
	void 물도_용암도_removeBlock_으로_한_칸도_안_없어진다() {
		BlockState water = Blocks.WATER.defaultBlockState();
		assertSame(water, water.getFluidState().createLegacyBlock(),
				"removeBlock 이 넣는 값이 수원과 같은 객체다 — 그래서 아무 일도 안 일어났다");
		BlockState flowing = water.setValue(LiquidBlock.LEVEL, 3);
		assertNotEquals(water, flowing, "LEVEL 3 은 다른 상태라야 한다");
		assertSame(flowing, flowing.getFluidState().createLegacyBlock(),
				"흐르는 물도 LEVEL 왕복이 정확해 같은 객체가 나온다 — 수원만의 문제가 아니었다");

		// waterlogged 는 더 나빴다. 블록만 빠지고 물이 남아 그 물이 영영 안 없어졌다.
		BlockState loggedSlab = Blocks.OAK_SLAB.defaultBlockState()
				.setValue(BlockStateProperties.WATERLOGGED, Boolean.TRUE);
		assertSame(water, loggedSlab.getFluidState().createLegacyBlock(),
				"waterlogged 칸에 removeBlock 을 쓰면 그 자리에 물이 남는다 — clear 가 그래서 "
						+ "칸을 통째로 공기로 바꾼다");

		// 서리눈은 유체가 아니라 전부터 부서지고 있었다. 사람이 가리킨 「물」에도 「전부」에도
		// 안 섞여 있다 — 2026-10-04 의 두 걸음 어느 쪽에서도 건드리지 않았다.
		assertSame(Blocks.AIR.defaultBlockState(),
				Blocks.POWDER_SNOW.defaultBlockState().getFluidState().createLegacyBlock(),
				"서리눈이 유체가 되면 그것도 안 부서지기 시작한다");

		// ⚠ 용암이 물과 똑같이 남는다. 첫 걸음에서는 이것이 「사람에게 물을 근거」였고, 같은 날
		// 사람이 「전부 고쳐」라고 해서 이제는 「용암까지 넓힌 근거」다.
		BlockState lava = Blocks.LAVA.defaultBlockState();
		assertSame(lava, lava.getFluidState().createLegacyBlock(),
				"용암 수원도 removeBlock 이 같은 객체를 되돌려 넣는다 — 물과 한 글자도 다르지 않다");
		BlockState flowingLava = lava.setValue(LiquidBlock.LEVEL, 3);
		assertNotEquals(lava, flowingLava, "용암 LEVEL 3 은 다른 상태라야 한다");
		assertSame(flowingLava, flowingLava.getFluidState().createLegacyBlock(),
				"퍼진 용암도 LEVEL 왕복이 정확해 같은 객체가 나온다 — 수원만의 문제가 아니었다");
	}

	/**
	 * ⚠⚠ <b>물과 용암을 함께 없앤다.</b> <b>이 시험은 2026-10-04 에 뜻이 뒤집혔다</b> — 옛 이름이
	 * {@code 물만_없애고_용암은_그대로_둔다} 였다.
	 *
	 * <h2>왜 뒤집혔나 — 두 걸음이다</h2>
	 *
	 * <ol>
	 *   <li><b>첫 걸음.</b> 사람 말이 <b>「주위블럭 부수는거에서 물은 안부수는데 물도 부수게」</b>
	 *       였다. 말한 것이 「물」뿐이라 <b>범위를 우리가 넓히지 않고</b> 용암 두 줄을
	 *       {@code assertFalse} 로 못박아 두었다 — 「넓히려면 사람에게 물을 것」이라고 적혀
	 *       있었다</li>
	 *   <li><b>둘째 걸음(같은 날).</b> 물었고 사람이 <b>「전부 고쳐」</b>라고 답했다. 그래서 그 두
	 *       줄이 <b>{@code assertTrue} 로 뒤집혔고</b> 함수 이름도 {@code holdsWater} →
	 *       {@code holdsFluid} 로 함께 고쳤다</li>
	 * </ol>
	 *
	 * <p>{@code holdsFluid} 가 붙드는 것이 넷이다 — <b>물 수원 · 흐르는 물 · 용암 수원 · 퍼진
	 * 용암</b>, 그리고 <b>남의 블록 안에 잠긴 물</b>까지. ⚠ {@code Fluids.WATER}·
	 * {@code Fluids.LAVA} 는 <b>수원뿐</b>이고 퍼진 쪽은 {@code Fluids.FLOWING_WATER}·
	 * {@code Fluids.FLOWING_LAVA} 라는 <b>다른 객체</b>라서, 유체마다 한 줄만 적으면 <b>수원만
	 * 지우고 퍼진 것은 그대로 남는다.</b>
	 */
	@Test
	void 물과_용암을_함께_없앤다() {
		assertTrue(DragonLastStandDome.holdsFluid(Blocks.WATER.defaultBlockState()),
				"물 수원");
		assertTrue(DragonLastStandDome.holdsFluid(
						Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3)),
				"흐르는 물 — Fluids.WATER 한 줄만 적으면 여기서 샌다(FLOWING_WATER 는 다른 객체다)");
		assertTrue(DragonLastStandDome.holdsFluid(Blocks.OAK_SLAB.defaultBlockState()
						.setValue(BlockStateProperties.WATERLOGGED, Boolean.TRUE)),
				"남의 블록 안에 잠긴 물 — waterlogged 성질을 따로 묻지 않아도 걸려야 한다");

		// ⚠⚠ 2026-10-04 둘째 걸음 — 사람이 「전부 고쳐」라고 해서 여기 둘이 뒤집혔다.
		assertTrue(DragonLastStandDome.holdsFluid(Blocks.LAVA.defaultBlockState()),
				"용암 수원 — 사람 말이 「전부 고쳐」였다. 거짓이 되면 첫 걸음으로 되돌아간 것이다");
		assertTrue(DragonLastStandDome.holdsFluid(
						Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 3)),
				"퍼진 용암 — Fluids.LAVA 한 줄만 적으면 여기서 샌다(FLOWING_LAVA 는 다른 객체다). "
						+ "수원만 지우면 퍼진 용암이 그대로 남아 사람 눈에는 「안 부서진다」다");

		// ⚠ 여기서부터는 「안 걸려야」 한다. 넓힌 것은 유체 둘뿐이다.
		assertFalse(DragonLastStandDome.holdsFluid(Blocks.OAK_SLAB.defaultBlockState()),
				"유체를 안 머금은 블록은 전과 같은 removeBlock 길이라야 한다");
		assertFalse(DragonLastStandDome.holdsFluid(Blocks.POWDER_SNOW.defaultBlockState()),
				"서리눈은 유체가 아니라 전부터 removeBlock 으로 부서지고 있었다");
		assertFalse(DragonLastStandDome.holdsFluid(Blocks.AIR.defaultBlockState()));
		assertFalse(DragonLastStandDome.holdsFluid(Blocks.COBBLESTONE.defaultBlockState()));

		// ⚠⚠ 섬 바닥·기반암·엔드 포털이 유체로 읽히면 FLUID_CLEAR_FLAGS 가 그것들에 닿는다.
		// breakable 이 먼저 막지만 이 함수 자신도 거짓이라야 겹이 둘로 남는다.
		assertFalse(DragonLastStandDome.holdsFluid(Blocks.END_STONE.defaultBlockState()),
				"섬 돌이 유체로 읽히면 섬이 UPDATE_CLIENTS 로 지워진다 — 두 겹이 한꺼번에 죽는다");
		assertFalse(DragonLastStandDome.holdsFluid(Blocks.BEDROCK.defaultBlockState()),
				"기반암 포디움이 유체로 읽히면 드래곤이 앉은 자리가 사라진다");
		assertFalse(DragonLastStandDome.holdsFluid(Blocks.END_PORTAL.defaultBlockState()),
				"출구 포털이 유체로 읽히면 판을 끝낼 수 없다");
		assertFalse(DragonLastStandDome.holdsFluid(Blocks.END_PORTAL_FRAME.defaultBlockState()));

		// 26.3 의 물 유체가 둘뿐임을 태그 파일에서 확인한다. 셋째가 생기면 holdsFluid 가 샌다.
		String water = read("/data/minecraft/tags/fluid/water.json");
		assertTrue(water.contains("\"minecraft:water\""));
		assertTrue(water.contains("\"minecraft:flowing_water\""));
		assertEquals(2, water.split("minecraft:", -1).length - 1,
				"물 유체가 둘보다 많아졌다 — holdsFluid 에 줄을 더할 것");

		// ⚠ 용암 쪽도 같은 꼴로 못박는다. 물 쪽이 이렇게 되어 있으니 같은 꼴이라야 한다.
		String lava = read("/data/minecraft/tags/fluid/lava.json");
		assertTrue(lava.contains("\"minecraft:lava\""));
		assertTrue(lava.contains("\"minecraft:flowing_lava\""),
				"퍼진 용암이 따로 있다는 근거다 — 없어지면 holdsFluid 의 넷째 줄도 다시 볼 것");
		assertEquals(2, lava.split("minecraft:", -1).length - 1,
				"용암 유체가 둘보다 많아졌다 — holdsFluid 에 줄을 더할 것");
	}

	/**
	 * ⚠⚠ <b>용암까지 넓혀도 섬 바닥 · 기반암 포디움 · 출구 포털은 한 칸도 안 없어진다.</b>
	 *
	 * <p>이것이 이 묶음에서 <b>월드 삭제와 직결되는 시험</b>이다. 2026-10-04 에 유체 갈래가
	 * <b>두 번</b> 넓어졌고(물 → 물·용암) 그 갈래는 {@code FLUID_CLEAR_FLAGS} 로 <b>칸을 통째로
	 * 공기로</b> 바꾼다 — 넓힌 쪽이 섬에 닿으면 허공 낙사이고 공유 체력이라 팀 전멸이다.
	 *
	 * <p>닿지 않는 근거가 <b>셋</b>이고 셋 다 여기서 못박는다.
	 *
	 * <ol>
	 *   <li><b>순서.</b> {@code sweepColumn} 이 {@code breakable} 을 {@code clear} 보다 <b>먼저</b>
	 *       묻는다 — 곧 유체 갈래는 {@code DRAGON_IMMUNE} 관문 <b>뒤</b>에서만 넓어졌다</li>
	 *   <li><b>태그.</b> 섬 돌·기반암·엔드 포털이 {@code DRAGON_IMMUNE} 에 있다</li>
	 *   <li><b>그 셋이 유체로 읽히지도 않는다.</b> ①이 틀려 {@code clear} 가 먼저 불려도
	 *       {@code holdsFluid} 가 거짓이라 {@code FLUID_CLEAR_FLAGS} 가 닿지 않는다</li>
	 * </ol>
	 */
	@Test
	void 용암까지_넓혀도_섬_바닥과_포털은_그대로다() {
		String immune = readTag("dragon_immune");
		for (String kept : new String[] {"end_stone", "bedrock", "end_portal",
				"end_portal_frame", "end_gateway"}) {
			assertTrue(immune.contains("\"minecraft:" + kept + "\""),
					kept + " 이 DRAGON_IMMUNE 에서 빠졌다 — 유체 갈래가 섬·포털에 닿는 길이 열렸다");
		}

		// ⚠ 유체 갈래가 못 보는 것들이라야 한다. 태그 관문이 틀려도 이 겹이 남는다.
		for (BlockState kept : new BlockState[] {
				Blocks.END_STONE.defaultBlockState(),
				Blocks.BEDROCK.defaultBlockState(),
				Blocks.END_PORTAL.defaultBlockState(),
				Blocks.END_PORTAL_FRAME.defaultBlockState(),
				Blocks.OBSIDIAN.defaultBlockState(),
				Blocks.IRON_BARS.defaultBlockState()}) {
			assertFalse(DragonLastStandDome.holdsFluid(kept),
					kept.getBlock() + " 이 유체로 읽힌다 — FLUID_CLEAR_FLAGS 가 칸을 통째로 공기로 "
							+ "바꾸므로 섬에 구멍이 난다");
			assertTrue(kept.getFluidState().isEmpty(),
					kept.getBlock() + " 에 유체가 들어 있다 — clear 의 둘째 갈래로도 샐 수 있다");
		}

		// 첫째 겹도 유체 작업과 무관하게 그대로다 — 훑기 바닥을 한 칸도 안 건드렸다.
		Vec3 anchor = new Vec3(0.5, 68.0, 0.5);
		assertEquals(64, DragonLastStandDome.sweepFloorY(anchor),
				"유체까지 넓히면서 훑기 바닥을 건드렸다 — 68 − 4 = 64 라야 한다");
		assertEquals(4, DragonLastStandDome.domeOriginY(anchor)
						- DragonLastStandDome.sweepFloorY(anchor),
				"기하 원점과 훑기 바닥의 네 칸 관계가 유체 작업에 흔들리면 안 된다");
	}

	/**
	 * ⚠⚠ <b>지운 물도 용암도 다시 차지 않는다.</b> 다시 차면 사람 눈에는 그것도
	 * 「안 부서진다」다.
	 *
	 * <p>붙드는 것은 깃발 하나다 — {@code Block.UPDATE_CLIENTS} <b>하나뿐</b>이고
	 * {@code UPDATE_NEIGHBORS} 가 <b>없다.</b> 이웃을 깨우면 아직 안 지운 옆칸 유체와 반구 밖
	 * 수원이 흐를 예약을 쌓고 그것이 다음 틱에 터져 유체가 되돌아온다. 깨우지 않으면 예약이 하나도
	 * 생기지 않으므로 <b>천장에 걸려 남은 유체까지 그 자리에 가만히 있다.</b>
	 *
	 * <p>⚠ <b>2026-10-04 에 용암까지 넓히면서 이 값을 한 비트도 안 고쳤다.</b> 근거가 같기
	 * 때문이다 — <b>용암도 흐른다</b>({@code LavaFluid} 가 {@code FlowingFluid} 를 물려받는다).
	 * 다른 것은 {@code getTickDelay} 가 30틱(물은 5틱)이라는 것뿐이고 되돌아오는 것이 느릴 뿐
	 * 안 돌아오는 것이 아니다. 고친 것은 <b>이름뿐</b>이다({@code WATER_CLEAR_FLAGS} →
	 * {@code FLUID_CLEAR_FLAGS}).
	 *
	 * <p>{@code TrialDryWorld.dryUpSection} 이 <b>같은 값을 같은 근거로</b> 쓴다.
	 */
	@Test
	void 지운_유체가_다시_차지_않는다() {
		assertEquals(Block.UPDATE_CLIENTS, DragonLastStandDome.FLUID_CLEAR_FLAGS,
				"TrialDryWorld 가 고른 답과 같은 깃발이라야 한다");
		assertEquals(0, DragonLastStandDome.FLUID_CLEAR_FLAGS & Block.UPDATE_NEIGHBORS,
				"이웃을 깨우면 옆칸 유체와 반구 밖 수원이 흐를 예약을 쌓아 다음 틱에 되돌아온다");
		assertNotEquals(Block.UPDATE_ALL, DragonLastStandDome.FLUID_CLEAR_FLAGS,
				"UPDATE_ALL 은 UPDATE_NEIGHBORS 를 품는다 — 그 한 비트가 「다시 찬다」다");
		assertNotEquals(0, DragonLastStandDome.FLUID_CLEAR_FLAGS & Block.UPDATE_CLIENTS,
				"클라이언트에 안 보내면 화면에만 유체가 남는다 — 그것도 사람 눈에는 "
						+ "「안 부서진다」다");

		String bytes = classBytes();
		assertTrue(bytes.contains("setBlock"),
				"유체 갈래가 사라졌다 — removeBlock 은 물·용암 칸에 아무 일도 못 한다");
		assertTrue(bytes.contains("getFluidState"),
				"유체를 가려내는 줄이 없으면 모르는 유체까지 공기가 되거나 물·용암이 다시 "
						+ "안 부서진다");
		assertTrue(bytes.contains("FLOWING_LAVA"),
				"퍼진 용암을 보는 줄이 사라졌다 — 수원만 지우면 퍼진 용암이 그대로 남는다");
		assertTrue(bytes.contains("FLOWING_WATER"), "흐르는 물을 보는 줄이 사라졌다");
	}

	/**
	 * ⚠⚠ <b>유체를 찾으려고 거름망을 버리지 않았다 — 맨손 아레나 비용이 전과 똑같다.</b>
	 *
	 * <p>{@code TrialDryWorld} 는 유체를 찾으려고 하이트맵을 포기하고 구획의 유체 개수
	 * ({@code hasFluid()})에 기댔다. <b>여기서 같은 길을 가면 2천 열을 매 초 깊게 훑는다.</b>
	 * 그럴 필요가 없는 근거가 둘이고 이 시험이 그 둘을 함께 못박는다.
	 *
	 * <ol>
	 *   <li><b>물도 용암도 공기가 아니다.</b> {@code WORLD_SURFACE} 의 판별식 {@code NOT_AIR} 이
	 *       바이트코드에서 {@code state -> !state.isAir()} 한 줄이라, 유체가 든 열은 하이트맵이 그
	 *       유체의 맨 위를 돌려주어 <b>거름망을 통과한다</b>. {@code hasOnlyAir()} 도 같은
	 *       {@code isAir()} 를 센다</li>
	 *   <li><b>조회 횟수가 한 번도 늘지 않았다.</b> 열 1961 · 구획 32 — 2026-10-04 <b>이전과 같은
	 *       수</b>이고, 같은 날 <b>용암까지 넓힌 뒤에도 같은 수</b>다. 넓힌 자리가
	 *       {@code holdsFluid} 한 곳이고 그 함수는 <b>이미 통과한 열 안에서 이미 읽어 둔
	 *       {@code BlockState} 에 유체 종류를 한 번 더 묻는 것</b>뿐이라, 열도 구획도 더 열지
	 *       않는다</li>
	 * </ol>
	 *
	 * <p>⚠ 옛 이름이 {@code 물을_찾으려고_하이트맵_거름망을_버리지_않았다} 였다. 용암까지 넓히면서
	 * 「물을」이 거짓이 되어 함께 고쳤다.
	 */
	@Test
	void 유체를_찾으려고_하이트맵_거름망을_버리지_않았다() {
		// ① 유체가 공기가 아니라는 사실. 이것이 거짓이면 유체만 있는 열이 통째로 걸러진다.
		assertFalse(Blocks.WATER.defaultBlockState().isAir(),
				"물이 공기로 읽히면 WORLD_SURFACE 가 물을 못 보고 물만 있는 열이 걸러진다");
		assertFalse(Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 3).isAir(),
				"흐르는 물도 공기가 아니라야 한다");
		assertFalse(Blocks.LAVA.defaultBlockState().isAir(),
				"용암이 공기로 읽히면 용암만 있는 열이 걸러져 「전부 고쳐」가 무효가 된다");
		assertFalse(Blocks.LAVA.defaultBlockState().setValue(LiquidBlock.LEVEL, 3).isAir(),
				"퍼진 용암도 공기가 아니라야 한다");
		assertFalse(Blocks.OAK_SLAB.defaultBlockState()
						.setValue(BlockStateProperties.WATERLOGGED, Boolean.TRUE).isAir(),
				"잠긴 물은 블록 자신이 하이트맵에 잡히므로 더 확실하다");
		assertTrue(Blocks.AIR.defaultBlockState().isAir(), "공기는 공기여야 한다");

		// ② 맨손 아레나 비용이 전과 같은 수다. 물 작업 전후로도, 용암까지 넓힌 뒤에도 그대로다.
		assertEquals(1961, DragonLastStandDome.columnProbesPerSweep(0.5, 0.5),
				"열 조회가 늘었다 — 유체 때문에 거름망을 버렸다는 뜻이다");
		assertEquals(32, DragonLastStandDome.sectionProbesPerSweep(0.5, 0.5, 68),
				"구획 조회가 늘었다 — hasFluid 를 끼워 넣었거나 범위를 넓혔다는 뜻이다");

		// ③ 거름망을 TrialDryWorld 쪽으로 갈아 끼우지 않았다.
		// ⚠ 유체를 보는 함수를 hasFluid 라고 이름 짓지도 말 것 — 이 줄이 이름까지 본다.
		String bytes = classBytes();
		assertTrue(bytes.contains("WORLD_SURFACE"), "하이트맵 거름망이 사라졌다");
		assertTrue(bytes.contains("hasOnlyAir"), "빈 구획 건너뛰기가 사라졌다");
		assertFalse(bytes.contains("hasFluid"),
				"구획의 유체 개수로 갈아 끼우면 유체가 든 구획마다 4096칸을 매 초 읽는다 — "
						+ "하이트맵이 물도 용암도 보므로 그럴 이유가 없다");
	}

	/**
	 * ⚠ <b>{@code mobGriefing} 을 끄면 함께 꺼진다.</b> 끄면 반구도 꺼지는 것이 <b>일부러 고른
	 * 것</b>이다 — 우리만 무시하면 「드래곤은 안 부수는데 반구는 부순다」가 되고 그 어긋남은 로그
	 * 어디에도 안 남는다.
	 *
	 * <p>관문은 {@code sweep()} 의 첫 줄 하나다. 그러니 <b>블록을 쓰는 메서드가 모두
	 * {@code private}</b> 이어야 그 관문이 유일한 문이 된다 — 밖에서 {@code sweepColumn} 이나
	 * {@code clear} 를 직접 부를 수 있으면 게임룰을 건너뛰고 블록이 지워진다.
	 *
	 * <p>⚠ 2026-10-04 에 {@code clear} 의 유체 갈래가 <b>두 번</b> 넓어졌다(물 → 물·용암). 넓어진
	 * 쪽이 {@code clear} 안이므로 <b>그 메서드가 {@code private} 인 것이 관문의 전부</b>다 — 이름을
	 * 고치면 이 시험의 반사도 함께 고칠 것.
	 */
	@Test
	void mobGriefing_을_끄면_함께_꺼진다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("MOB_GRIEFING"),
				"게임룰을 안 보면 「드래곤은 안 부수는데 반구는 부순다」가 된다");
		assertTrue(bytes.contains("GameRules"), "게임룰 자체를 안 읽는다");

		for (String hidden : new String[] {"sweepChunk", "sweepColumn", "clear", "puff"}) {
			assertTrue(isPrivate(hidden), hidden
					+ " 이 private 이 아니다 — 게임룰 관문을 건너뛰고 블록을 지우는 길이 열렸다");
		}
	}

	/**
	 * 「메마른 세계」와 싸우지 않는다.
	 *
	 * <p>그 카드는 <b>최후의 저항에서도 금지만은 이어지는 단 하나뿐인 예외</b>다
	 * ({@code TrialDryWorld.holdBanDuringLastStand}). 그 카드가 걸린 판에서는 엔드에 물을 놓을 수
	 * 없으므로 <b>반구가 만날 물이 아예 없다</b> — 둘이 같은 칸을 두고 다툴 일이 없다.
	 *
	 * <p>⚠ 그 금지가 새면 그 카드가 걸린 판에서도 유체가 놓이고, 그때 두 코드가 같은 칸을 만진다.
	 * 그래도 다투지 않는 근거는 <b>깃발이 같다</b>는 것이다.
	 *
	 * <p>⚠ <b>2026-10-04 에 용암까지 넓히면서 겹침이 늘지 않았다.</b> 그쪽은 <b>처음부터 물과
	 * 용암을 함께</b> 막고 지웠다({@code withoutFluid} 가 유체 종류를 묻지 않고 {@code LiquidBlock}
	 * 이면 공기로 바꾼다) — 곧 우리가 넓힌 쪽이 그쪽을 따라간 꼴이다.
	 */
	@Test
	void 메마른_세계와_싸우지_않는다() {
		assertTrue(TrialDryWorld.placesFluid(Items.WATER_BUCKET),
				"물 양동이를 안 막으면 그 카드가 걸린 판에서도 물이 놓인다");
		assertTrue(TrialDryWorld.placesFluid(Items.LAVA_BUCKET),
				"용암 양동이를 안 막으면 그 카드가 걸린 판에서도 용암이 놓인다 — 2026-10-04 에 "
						+ "반구가 용암까지 보게 되었으므로 둘이 같은 칸을 두고 만난다");
		assertTrue(TrialDryWorld.placesFluid(Items.POWDER_SNOW_BUCKET));
		assertFalse(TrialDryWorld.placesFluid(Items.BUCKET), "빈 양동이는 아무것도 놓지 않는다");

		assertEquals(Block.UPDATE_CLIENTS, DragonLastStandDome.FLUID_CLEAR_FLAGS,
				"TrialDryWorld.dryUpSection 과 같은 깃발이라야 둘이 같은 칸에서 다르게 굴지 않는다");
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
		// 원점 68 · 바닥 64 · 천장 93 → 64와 93이 같은 두 구획에 들어 수가 그대로다.
		assertEquals(32, sections, "청크 16개 × 구획 2개다");

		int probes = columns + sections;
		assertTrue(probes < cells / 10.0,
				"거름망이 열 배도 못 줄인다 — 실제 " + probes + " 대 " + Math.round(cells));
		// 20틱마다이므로 한 틱 평균이 이것의 1/20 이다.
		assertTrue(probes / (double) DragonLastStandDome.SWEEP_TICKS < 150.0,
				"한 틱 평균 " + probes / (double) DragonLastStandDome.SWEEP_TICKS + "번이다");
	}

	/**
	 * ⚠ <b>구획 범위의 아래끝이 훑기 바닥이다.</b> {@code holdsBlocksAbove} 가 실제로 도는 범위와
	 * 같은 셈이라야 이 값이 성능의 증거가 된다.
	 *
	 * <p>원점 68 로는 옛 셈과 새 셈이 <b>같은 수</b>(32)를 내므로 그것만으로는 바닥을 내렸는지 알 수
	 * 없다. 구획 경계를 넘는 두 자리를 골라 못박는다.
	 */
	@Test
	void 구획_범위가_훑기_바닥부터다() {
		// 원점 66 → 바닥 62(구획 3) · 천장 91(구획 5) = 구획 3개. 옛 셈(바닥 = 원점)은 2개였다.
		assertEquals(48, DragonLastStandDome.sectionProbesPerSweep(0.5, 0.5, 66),
				"청크 16개 × 구획 3개다 — 32가 나오면 구획 범위가 아직 기하 원점에서 시작한다");
		// 원점 71 → 바닥 67(구획 4) · 천장 96(구획 6) = 3개. 천장이 원점에서 나오는 증거다.
		assertEquals(48, DragonLastStandDome.sectionProbesPerSweep(0.5, 0.5, 71),
				"32가 나오면 천장을 훑기 바닥에서 재고 있다 — 반구가 네 칸 내려앉았다는 뜻이다");
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
	 *
	 * <p>⚠ <b>2026-10-04 부터 길이 둘이다.</b> 물·용암 칸만 {@code setBlock} 으로 공기를 넣고(까닭은
	 * {@link #물도_용암도_removeBlock_으로_한_칸도_안_없어진다}) 나머지는 전과 같이
	 * {@code removeBlock} 이다. <b>둘 다 드롭을 만들지 않는다</b> — 드롭은 {@code dropResources}
	 * 를 부르는 {@code destroyBlock} 의 몫이고 그 이름이 여기 없다.
	 */
	@Test
	void 드롭_없이_removeBlock_으로_지운다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("removeBlock"), "유체가 없는 블록을 지우는 줄이 없다");
		assertTrue(bytes.contains("setBlock"),
				"유체 갈래가 없다 — removeBlock 은 물·용암 칸에 아무 일도 못 한다");
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
	 * 이 메서드가 {@code private} 인가. 밖에서 부를 수 있으면 {@code mobGriefing} 관문을 건너뛰고
	 * 블록을 지우는 길이 열린다 — {@link #mobGriefing_을_끄면_함께_꺼진다} 를 볼 것.
	 */
	private static boolean isPrivate(String name) {
		for (Method method : DragonLastStandDome.class.getDeclaredMethods()) {
			if (method.getName().equals(name)) {
				return Modifier.isPrivate(method.getModifiers());
			}
		}
		return fail(name + " 메서드를 찾지 못했다 — 이름이 바뀌었으면 이 시험도 함께 고칠 것");
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
