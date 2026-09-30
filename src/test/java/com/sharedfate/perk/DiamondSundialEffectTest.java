package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.DiamondSundialEffect;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code diamond_sundial}(골드 「엑스레이」)의 정의 읽기, 아이템 표식 판정, 블록 후보 판정,
 * 반경·지속·상한 계산을 본다.
 *
 * <p>가장 가까운 한 자리를 고르는 규칙과 액션바 문구도 여기서 본다.
 *
 * <p>우클릭을 잡고 청크를 훑고 파티클을 보내는 부분, 그리고 <b>지속이 끝난 뒤에 쿨타임이
 * 걸리는지</b>는 살아 있는 서버와 월드가 있어야 하므로 여기서 다루지 않는다. 대신 그 계산에서
 * 실제로 판단을 내리는 조각({@link PerkDiamondSundial.Found}, {@link PerkDiamondSundial#nearest},
 * {@link PerkDiamondSundial#actionBarText}, {@link PerkDiamondSundial#seconds},
 * {@link DiamondSundialEffect#isDiamondOre}, {@link DiamondSundialEffect#isSundial})은 월드를
 * 읽지 않게 떼어 두었으므로 전부 여기서 확인한다.
 */
class DiamondSundialEffectTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 정의 읽기

	@Test
	void 값을_적지_않으면_기본값이다() {
		DiamondSundialEffect effect = create("{ \"type\": \"diamond_sundial\" }");

		assertEquals(DiamondSundialEffect.DEFAULT_RADIUS, effect.radius());
		assertEquals(DiamondSundialEffect.DEFAULT_MAX_RESULTS, effect.maxResults());
		assertEquals(DiamondSundialEffect.DEFAULT_DURATION_SECONDS * 20, effect.durationTicks());
		assertEquals(DiamondSundialEffect.DEFAULT_COOLDOWN_SECONDS * 20, effect.cooldownTicks());
	}

	@Test
	void 기본값이_설명과_맞는다() {
		assertEquals(20, DiamondSundialEffect.DEFAULT_RADIUS, "반경 기본값은 20칸이다");
		assertEquals(10, DiamondSundialEffect.DEFAULT_DURATION_SECONDS, "지속 기본값은 10초다");
		assertEquals(90, DiamondSundialEffect.DEFAULT_COOLDOWN_SECONDS,
				"쿨타임 기본값은 1분 30초다 — 30초는 너무 세서 2026-09-30 에 늘렸다");
		assertEquals(16, DiamondSundialEffect.DEFAULT_MAX_RESULTS, "한 번에 최대 16개다");
	}

	@Test
	void 적은_값을_그대로_읽는다() {
		DiamondSundialEffect effect = create("{ \"type\": \"diamond_sundial\", \"radius\": 12,"
				+ " \"duration_seconds\": 8, \"cooldown_seconds\": 45, \"max_results\": 4 }");

		assertEquals(12, effect.radius());
		assertEquals(8 * 20, effect.durationTicks());
		assertEquals(45 * 20, effect.cooldownTicks());
		assertEquals(4, effect.maxResults());
	}

	/**
	 * 지속과 쿨타임은 <b>서로 다른 값</b>이다. 한쪽 키를 다른 쪽이 읽어 버리면 「10초 보고 30초
	 * 쉰다」가 조용히 「30초 보고 30초 쉰다」가 된다. 두 값을 일부러 어긋나게 적어 못박는다.
	 */
	@Test
	void 지속과_쿨타임을_섞지_않는다() {
		DiamondSundialEffect effect = create("{ \"type\": \"diamond_sundial\","
				+ " \"duration_seconds\": 3, \"cooldown_seconds\": 90 }");

		assertEquals(3 * 20, effect.durationTicks());
		assertEquals(90 * 20, effect.cooldownTicks());
	}

	@Test
	void 카멜케이스로_적은_쿨타임도_읽는다() {
		DiamondSundialEffect effect =
				create("{ \"type\": \"diamond_sundial\", \"cooldownSeconds\": 30 }");

		assertEquals(30 * 20, effect.cooldownTicks());
	}

	@Test
	void 카멜케이스로_적은_지속도_읽는다() {
		DiamondSundialEffect effect =
				create("{ \"type\": \"diamond_sundial\", \"durationSeconds\": 25 }");

		assertEquals(25 * 20, effect.durationTicks());
	}

	@Test
	void 범위를_벗어난_값은_버리지_않고_자른다() {
		DiamondSundialEffect tooBig = create("{ \"type\": \"diamond_sundial\", \"radius\": 999,"
				+ " \"duration_seconds\": 99999, \"cooldown_seconds\": 99999,"
				+ " \"max_results\": 999 }");
		DiamondSundialEffect tooSmall = create("{ \"type\": \"diamond_sundial\", \"radius\": 0,"
				+ " \"duration_seconds\": 0, \"cooldown_seconds\": 0, \"max_results\": 0 }");

		assertEquals(DiamondSundialEffect.MAX_RADIUS, tooBig.radius());
		assertEquals(DiamondSundialEffect.MAX_DURATION_SECONDS * 20, tooBig.durationTicks());
		assertEquals(DiamondSundialEffect.MAX_COOLDOWN_SECONDS * 20, tooBig.cooldownTicks());
		assertEquals(DiamondSundialEffect.MAX_MAX_RESULTS, tooBig.maxResults());

		assertEquals(DiamondSundialEffect.MIN_RADIUS, tooSmall.radius());
		assertEquals(DiamondSundialEffect.MIN_DURATION_SECONDS * 20, tooSmall.durationTicks());
		assertEquals(DiamondSundialEffect.MIN_COOLDOWN_SECONDS * 20, tooSmall.cooldownTicks());
		assertEquals(DiamondSundialEffect.MIN_MAX_RESULTS, tooSmall.maxResults());
	}

	/**
	 * 지속이 0 이하로 읽히면 우클릭이 한 판을 열자마자 그 틱에 끝나 <b>예전의 한 번 반짝이는
	 * 동작으로 조용히 돌아간다.</b> 하한이 그것을 막는 자리라 못박아 둔다.
	 */
	@Test
	void 지속은_반드시_한_틱_이상이다() {
		assertTrue(DiamondSundialEffect.MIN_DURATION_SECONDS >= 1,
				"0초가 허용되면 지속형이 아니라 한 번 반짝이고 끝나는 옛 동작이 된다");
		assertTrue(create("{ \"type\": \"diamond_sundial\", \"duration_seconds\": -5 }")
				.durationTicks() > 0);
	}

	@Test
	void apply_와_remove_는_아무_일도_하지_않는다() {
		DiamondSundialEffect effect = create("{ \"type\": \"diamond_sundial\" }");

		assertDoesNotThrow(() -> effect.apply(null));
		assertDoesNotThrow(() -> effect.remove(null));
	}

	// ------------------------------------------------------------------ 26.2 에 있는 id 인가

	/**
	 * 아이템·블록 레지스트리는 기본값이 공기라 {@code get} 이 비어 있는지 보는 것으로는 없는
	 * id 를 잡아내지 못한다({@code ItemGrantEffect} 에 같은 함정이 적혀 있다). 그래서 반대로
	 * 블록·아이템에서 id 를 되뽑아 우리가 적어 둔 문자열과 같은지 본다.
	 */
	@Test
	void 쓰는_id_가_26_2_의_id_와_같다() {
		assertEquals(DiamondSundialEffect.ITEM, BuiltInRegistries.ITEM.getKey(Items.CLOCK));
		assertEquals(DiamondSundialEffect.ORE, BuiltInRegistries.BLOCK.getKey(Blocks.DIAMOND_ORE));
		assertEquals(DiamondSundialEffect.DEEPSLATE_ORE,
				BuiltInRegistries.BLOCK.getKey(Blocks.DEEPSLATE_DIAMOND_ORE));
	}

	// ------------------------------------------------------------------ 아이템 표식

	@Test
	void 지급한_엑스레이에_표식과_쿨타임_묶음이_붙는다() {
		DiamondSundialEffect effect = create("{ \"type\": \"diamond_sundial\" }");

		ItemStack stack = effect.createItem();

		assertNotNull(stack);
		assertEquals(Items.CLOCK, stack.getItem(), "새 아이템을 등록하지 않고 바닐라 시계를 쓴다");
		assertEquals(1, stack.getCount());
		assertTrue(DiamondSundialEffect.isSundial(stack));

		UseCooldown cooldown = stack.get(DataComponents.USE_COOLDOWN);
		assertNotNull(cooldown, "쿨타임 묶음이 있어야 평범한 시계까지 잠기지 않는다");
		assertEquals(Optional.of(DiamondSundialEffect.COOLDOWN_GROUP), cooldown.cooldownGroup());
		assertEquals((float) DiamondSundialEffect.DEFAULT_COOLDOWN_SECONDS, cooldown.seconds(),
				0.001F);
	}

	@Test
	void 지급할_때마다_새_사본을_준다() {
		DiamondSundialEffect effect = create("{ \"type\": \"diamond_sundial\" }");

		ItemStack first = effect.createItem();
		ItemStack second = effect.createItem();

		assertNotNull(first);
		assertNotNull(second);
		assertNotSame(first, second, "견본을 그대로 넘기면 두 번째 지급이 빈 묶음이 된다");
		first.shrink(1);
		assertEquals(1, second.getCount(), "앞의 묶음을 깎아도 뒤의 묶음은 멀쩡해야 한다");
	}

	@Test
	void 표식이_없는_시계는_엑스레이가_아니다() {
		assertFalse(DiamondSundialEffect.isSundial(new ItemStack(Items.CLOCK)));
	}

	@Test
	void 이름만_바꾼_시계는_엑스레이가_아니다() {
		ItemStack fake = new ItemStack(Items.CLOCK);
		fake.set(DataComponents.CUSTOM_NAME, Component.literal(DiamondSundialEffect.DISPLAY_NAME));

		assertFalse(DiamondSundialEffect.isSundial(fake),
				"모루로 이름만 바꿔서는 엑스레이가 만들어지지 않아야 한다");
	}

	@Test
	void 빈_묶음과_null_은_엑스레이가_아니다() {
		assertFalse(DiamondSundialEffect.isSundial(ItemStack.EMPTY));
		assertFalse(DiamondSundialEffect.isSundial(null));
	}

	@Test
	void 표식을_직접_붙여도_엑스레이로_읽는다() {
		ItemStack stack = DiamondSundialEffect.decorate(new ItemStack(Items.CLOCK), 20.0F);

		assertTrue(DiamondSundialEffect.isSundial(stack));
	}

	/**
	 * 이름을 「해시계」에서 「엑스레이」로 바꿀 때 <b>표식 값과 쿨타임 묶음은 건드리지 않았다.</b>
	 * 표식을 함께 바꿨다면 이미 나가 있는 시계가 전부 평범한 시계가 되고, 쿨타임 묶음을 바꿨다면
	 * 예전 시계에 걸린 쿨타임이 영영 안 풀린 것처럼 보인다. 둘 다 빌드도 로그도 조용하다.
	 *
	 * <p>같은 까닭으로 증강 id({@code sharedfate:diamond_sundial})도 그대로 두었다 — 그쪽은
	 * {@code DefaultPerkPoolValuesTest} 가 지킨다.
	 */
	@Test
	void 이름만_바뀌었고_저장에_적히는_값은_그대로다() {
		assertEquals("엑스레이", DiamondSundialEffect.DISPLAY_NAME);
		assertEquals("diamond_sundial", DiamondSundialEffect.MARKER_VALUE,
				"표식을 바꾸면 이미 나가 있는 시계가 전부 남의 물건이 된다");
		assertEquals("sharedfate_item", DiamondSundialEffect.MARKER_KEY);
		assertEquals("diamond_sundial", DiamondSundialEffect.COOLDOWN_GROUP.getPath());
	}

	/**
	 * 쿨타임 표시가 읽는 이름이 아이템에 실제로 붙는 이름과 같아야 한다. 두 벌이 되면 한쪽만
	 * 고쳐져 「엑스레이」를 들고 있는데 액션바에 옛 이름이 뜬다.
	 */
	@Test
	void 쿨타임_표시가_아이템_이름과_같은_이름을_쓴다() {
		ItemStack stack = DiamondSundialEffect.decorate(new ItemStack(Items.CLOCK), 30.0F);

		assertEquals(DiamondSundialEffect.DISPLAY_NAME,
				PerkItemCooldownDisplay.displayNameOf(stack));
		assertNotNull(stack.get(DataComponents.CUSTOM_NAME));
		assertEquals(DiamondSundialEffect.DISPLAY_NAME,
				stack.get(DataComponents.CUSTOM_NAME).getString());
	}

	// ------------------------------------------------------------------ 블록 후보 판정

	/**
	 * {@code scan()} 은 섹션 팔레트를 거를 때도, 칸 하나를 볼 때도 이 메서드 하나만 쓴다. 여기가
	 * 한쪽 변종을 빠뜨리면 그 변종은 어디서도 걸리지 않는다.
	 */
	@Test
	void 심층암_변종도_같은_거르개를_지난다() {
		Predicate<BlockState> filter = DiamondSundialEffect::isDiamondOre;

		assertTrue(filter.test(Blocks.DIAMOND_ORE.defaultBlockState()));
		assertTrue(filter.test(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()),
				"다이아몬드가 나오는 깊이는 대부분 심층암이라 이쪽이 빠지면 증강이 거의 동작하지 않는다");
	}

	@Test
	void 다이아몬드_광석_두_가지만_후보다() {
		assertTrue(DiamondSundialEffect.isDiamondOre(Blocks.DIAMOND_ORE.defaultBlockState()));
		assertTrue(DiamondSundialEffect.isDiamondOre(
				Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()));

		assertFalse(DiamondSundialEffect.isDiamondOre(Blocks.STONE.defaultBlockState()));
		assertFalse(DiamondSundialEffect.isDiamondOre(Blocks.DEEPSLATE.defaultBlockState()));
		assertFalse(DiamondSundialEffect.isDiamondOre(Blocks.IRON_ORE.defaultBlockState()),
				"다른 광석은 보이지 않아야 한다");
		assertFalse(DiamondSundialEffect.isDiamondOre(Blocks.DIAMOND_BLOCK.defaultBlockState()),
				"다이아몬드 블록은 광석이 아니다");
		assertFalse(DiamondSundialEffect.isDiamondOre(Blocks.AIR.defaultBlockState()));
		assertFalse(DiamondSundialEffect.isDiamondOre(null));
	}

	// ------------------------------------------------------------------ 반경과 상한

	@Test
	void 반경은_상자가_아니라_구다() {
		PerkDiamondSundial.Found found = new PerkDiamondSundial.Found(new BlockPos(0, 64, 0), 20, 16);

		assertTrue(found.inRange(0, 64, 20), "정확히 20칸은 안이다");
		assertTrue(found.inRange(0, 84, 0));
		assertFalse(found.inRange(0, 64, 21), "21칸은 밖이다");
		assertFalse(found.inRange(12, 76, 12),
				"상자 모서리(12,12,12)는 20칸 상자 안이지만 구 밖이다");
		assertTrue(found.inRange(11, 75, 11), "11³ 은 구 안이다");
	}

	@Test
	void 상한을_넘으면_더_받지_않는다() {
		int max = 4;
		PerkDiamondSundial.Found found = new PerkDiamondSundial.Found(new BlockPos(0, 0, 0), 64, max);
		int limit = max * PerkDiamondSundial.Found.OVERSCAN;

		int accepted = 0;
		while (found.add(0, accepted, 0)) {
			accepted++;
		}
		accepted++;

		assertEquals(limit, accepted, "상한만큼만 받는다");
		assertTrue(found.full());
		assertFalse(found.add(0, 100, 0), "가득 찬 뒤에는 계속 거절한다");
		assertEquals(max, found.hits().size(), "돌려주는 것은 max_results 개다");
	}

	@Test
	void 가까운_것부터_돌려준다() {
		PerkDiamondSundial.Found found = new PerkDiamondSundial.Found(new BlockPos(0, 0, 0), 64, 3);

		// 일부러 먼 것부터 넣는다. 훑는 순서는 청크·섹션 순이라 거리순이 아니기 때문이다.
		found.add(0, 0, 30);
		found.add(0, 0, 20);
		found.add(0, 0, 10);
		found.add(0, 0, 5);
		found.add(0, 0, 1);

		List<BlockPos> hits = found.hits();

		assertEquals(3, hits.size());
		assertEquals(new BlockPos(0, 0, 1), hits.get(0));
		assertEquals(new BlockPos(0, 0, 5), hits.get(1));
		assertEquals(new BlockPos(0, 0, 10), hits.get(2));
	}

	@Test
	void 하나도_못_찾으면_빈_목록이다() {
		PerkDiamondSundial.Found found = new PerkDiamondSundial.Found(new BlockPos(0, 0, 0), 20, 16);

		assertTrue(found.hits().isEmpty());
		assertFalse(found.full());
	}

	// ------------------------------------------------------------------ 가장 가까운 한 자리

	@Test
	void 가장_가까운_자리를_고른다() {
		BlockPos center = new BlockPos(100, 64, -300);

		BlockPos picked = PerkDiamondSundial.nearest(center, List.of(
				new BlockPos(130, 64, -300),
				new BlockPos(100, 52, -300),
				new BlockPos(100, 64, -310)));

		assertEquals(new BlockPos(100, 64, -310), picked, "10칸짜리가 12칸·30칸보다 앞이다");
	}

	@Test
	void 훑은_차례가_아니라_거리로_고른다() {
		BlockPos center = new BlockPos(0, 64, 0);

		// 목록에 먼 것이 먼저 들어 있어도 고르는 것은 가까운 쪽이다.
		assertEquals(new BlockPos(0, 63, 0), PerkDiamondSundial.nearest(center,
				List.of(new BlockPos(0, 30, 0), new BlockPos(0, 63, 0))));
	}

	/**
	 * 거리가 같은 후보가 여럿일 때의 규칙을 못박는다. 이 규칙이 없으면 같은 자리에서 두 번 써도
	 * 훑는 차례(청크 번호)에 따라 다른 좌표가 뜬다.
	 */
	@Test
	void 거리가_같으면_y_x_z_가_작은_것을_고른다() {
		BlockPos center = new BlockPos(0, 64, 0);

		// 여섯 방향 모두 정확히 5칸이다.
		List<BlockPos> tied = List.of(
				new BlockPos(0, 69, 0), new BlockPos(5, 64, 0), new BlockPos(-5, 64, 0),
				new BlockPos(0, 64, 5), new BlockPos(0, 64, -5), new BlockPos(0, 59, 0));

		assertEquals(new BlockPos(0, 59, 0), PerkDiamondSundial.nearest(center, tied),
				"y 가 가장 작은 것이 먼저다");
		assertEquals(new BlockPos(-5, 64, 0),
				PerkDiamondSundial.nearest(center, tied.subList(1, 5)),
				"y 가 같으면 x 가 작은 것, 그다음 z 가 작은 것이다");
	}

	@Test
	void 후보가_없으면_null_이다() {
		assertNull(PerkDiamondSundial.nearest(new BlockPos(0, 64, 0), List.of()));
		assertNull(PerkDiamondSundial.nearest(new BlockPos(0, 64, 0), null));
		assertNull(PerkDiamondSundial.nearest(null, List.of(new BlockPos(1, 1, 1))));
	}

	@Test
	void 거리는_반올림한_칸_수다() {
		BlockPos center = new BlockPos(0, 64, 0);

		assertEquals(0, PerkDiamondSundial.blockDistance(center, center));
		assertEquals(5, PerkDiamondSundial.blockDistance(center, new BlockPos(3, 68, 0)));
		// √3 ≈ 1.73 → 2
		assertEquals(2, PerkDiamondSundial.blockDistance(center, new BlockPos(1, 65, 1)));
	}

	// ------------------------------------------------------------------ 액션바 문구

	@Test
	void 좌표는_좌표_HUD_와_같은_모양이다() {
		assertEquals("X 128  Y 64  Z -302", PerkDiamondSundial.positionLine(128, 64, -302),
				"CoordinateHud.positionLine 과 칸 사이가 두 칸으로 같아야 한다");
	}

	@Test
	void 하나만_찾으면_좌표와_거리만_적는다() {
		String text = PerkDiamondSundial.actionBarText(new BlockPos(128, 30, -302),
				List.of(new BlockPos(128, 12, -302)));

		assertEquals("[증강] 가장 가까운 다이아몬드: X 128  Y 12  Z -302 (18칸)", text);
	}

	@Test
	void 여럿이면_개수도_함께_적는다() {
		String text = PerkDiamondSundial.actionBarText(new BlockPos(128, 30, -302), List.of(
				new BlockPos(128, 12, -302),
				new BlockPos(130, 12, -302),
				new BlockPos(128, 11, -300)));

		assertEquals("[증강] 가장 가까운 다이아몬드: X 128  Y 12  Z -302 (18칸, 근처 3개)", text);
	}

	@Test
	void 하나도_못_찾으면_없다고_적는다() {
		BlockPos center = new BlockPos(0, 64, 0);

		assertEquals("[증강] 근처에 다이아몬드가 없습니다",
				PerkDiamondSundial.actionBarText(center, List.of()));
		assertEquals("[증강] 근처에 다이아몬드가 없습니다",
				PerkDiamondSundial.actionBarText(center, null));
	}

	@Test
	void 문구가_적는_좌표는_파티클이_뜨는_첫_자리와_같다() {
		BlockPos center = new BlockPos(0, 64, 0);
		PerkDiamondSundial.Found found = new PerkDiamondSundial.Found(center, 32, 16);
		found.add(0, 64, 20);
		found.add(0, 60, 0);
		found.add(0, 64, 10);

		List<BlockPos> hits = found.hits();

		assertEquals(hits.get(0), PerkDiamondSundial.nearest(center, hits),
				"두 길이 다른 자리를 고르면 액션바 좌표와 파티클이 어긋난다");
		assertTrue(PerkDiamondSundial.actionBarText(center, hits)
				.contains(PerkDiamondSundial.positionLine(0, 60, 0)));
	}

	// ------------------------------------------------------------------ 남은 시간

	/**
	 * 지속형이 된 뒤로 액션바에 남은 시간이 함께 뜬다. 파티클만 보고는 「이게 언제 꺼지나」를
	 * 알 수 없어, 캐러 가는 도중에 꺼지는 것과 아직 몇 초 남은 것을 사람이 가릴 수 없다.
	 */
	@Test
	void 남은_시간을_좌표_뒤에_덧붙인다() {
		String text = PerkDiamondSundial.actionBarText(new BlockPos(128, 30, -302),
				List.of(new BlockPos(128, 12, -302)), 7 * 20);

		assertEquals("[증강] 가장 가까운 다이아몬드: X 128  Y 12  Z -302 (18칸) · 7초", text);
	}

	@Test
	void 하나도_못_찾아도_남은_시간은_뜬다() {
		assertEquals("[증강] 근처에 다이아몬드가 없습니다 · 3초",
				PerkDiamondSundial.actionBarText(new BlockPos(0, 64, 0), List.of(), 3 * 20));
	}

	/**
	 * 남은 시간을 적지 않는 갈래를 남겨 둔 까닭은 <b>좌표와 개수를 적는 규칙이 두 벌이 되지
	 * 않게</b> 하기 위해서다. 세 인자짜리는 두 인자짜리의 결과를 그대로 쓰고 뒤에만 붙인다.
	 */
	@Test
	void 남은_시간이_0_이하면_아무것도_덧붙이지_않는다() {
		BlockPos center = new BlockPos(128, 30, -302);
		List<BlockPos> found = List.of(new BlockPos(128, 12, -302));

		assertEquals(PerkDiamondSundial.actionBarText(center, found),
				PerkDiamondSundial.actionBarText(center, found, 0));
		assertEquals(PerkDiamondSundial.actionBarText(center, found),
				PerkDiamondSundial.actionBarText(center, found, -40));
	}

	/**
	 * 1초가 안 남았을 때 「0초」로 뜨면 아직 보이는데 끝난 것처럼 읽힌다. 바닥을 1로 두는 규칙을
	 * 못박는다({@code PerkFlightCharm} 과 같은 규칙이다).
	 */
	@Test
	void 남은_초는_1_아래로_내려가지_않는다() {
		assertEquals(10, PerkDiamondSundial.seconds(10 * 20));
		assertEquals(1, PerkDiamondSundial.seconds(20));
		assertEquals(1, PerkDiamondSundial.seconds(1), "1틱이 남아도 0초라고 적지 않는다");
		assertEquals(3, PerkDiamondSundial.seconds(79), "내림이다 — 3.95초는 3초로 적는다");
	}

	// ------------------------------------------------------------------ 도우미

	private static DiamondSundialEffect create(String json) {
		JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
		return assertInstanceOf(DiamondSundialEffect.class,
				DiamondSundialEffect.fromJson("sharedfate:테스트", 0, parsed));
	}
}
