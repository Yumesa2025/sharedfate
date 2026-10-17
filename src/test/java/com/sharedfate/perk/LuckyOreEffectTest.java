package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.LuckyOreEffect;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code lucky_ore} 의 정의 읽기와 추가 개수 분포를 본다.
 *
 * <p>실제로 드롭이 떨어지고 액션바가 나가는 자리는 {@link PerkBlockBreaks} 이고, 그건 살아 있는
 * 월드가 있어야 확인할 수 있다. 여기서는 그 코드가 부르는 것들 — 대상 블록 판정, 개수 분포,
 * 문구 만들기 — 만 본다.
 *
 * <p>블록 <b>태그</b> 판정은 데이터팩이 올라와 있어야 실제로 걸린다. 그래서 대상 블록 시험은
 * 태그가 아니라 이름으로 적힌 항목({@code minecraft:ancient_debris})으로 한다.
 */
class LuckyOreEffectTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	// ------------------------------------------------------------------ 정의 읽기

	@Test
	void 필드가_없으면_기본_광물_목록을_쓴다() {
		LuckyOreEffect effect = create("{ \"type\": \"lucky_ore\" }");

		assertEquals(LuckyOreEffect.DEFAULT_ORE_BLOCKS.size(),
				effect.blocks().tags().size() + effect.blocks().blockIds().size());
		assertFalse(effect.blocks().matchesEverything(), "모든 블록에 걸리면 안 된다");
	}

	@Test
	void 기본_목록은_욕심_많은_곡괭이와_같다() {
		// 두 증강이 같은 것을 "광물"이라고 불러야 한다. sharedfate:greedy_pickaxe 의
		// bonus_drop blocks 배열을 그대로 옮긴 값이다.
		assertEquals(List.of(
						"#c:ores",
						"#minecraft:coal_ores",
						"#minecraft:copper_ores",
						"#minecraft:diamond_ores",
						"#minecraft:emerald_ores",
						"#minecraft:gold_ores",
						"#minecraft:iron_ores",
						"#minecraft:lapis_ores",
						"#minecraft:redstone_ores",
						"minecraft:nether_quartz_ore",
						"minecraft:nether_gold_ore",
						"minecraft:ancient_debris"),
				LuckyOreEffect.DEFAULT_ORE_BLOCKS);
	}

	@Test
	void 기본_목록의_이름으로_적힌_블록에는_실제로_걸린다() {
		LuckyOreEffect effect = create("{ \"type\": \"lucky_ore\" }");

		assertTrue(effect.appliesTo(Blocks.ANCIENT_DEBRIS.defaultBlockState()));
		assertFalse(effect.appliesTo(Blocks.STONE.defaultBlockState()));
		assertFalse(effect.appliesTo(null));
	}

	@Test
	void blocks_를_적으면_그것만_쓴다() {
		LuckyOreEffect effect = create("""
				{ "type": "lucky_ore", "blocks": ["minecraft:ancient_debris"] }
				""");

		assertEquals(1, effect.blocks().blockIds().size());
		assertTrue(effect.blocks().tags().isEmpty());
		assertTrue(effect.appliesTo(Blocks.ANCIENT_DEBRIS.defaultBlockState()));
	}

	@Test
	void blocks_가_비면_버린다() {
		// "모든 블록"이 되는 길을 열어 두지 않는다. 오타를 조용히 넘기면 흙 한 삽마다
		// 세 개씩 더 나오는 증강이 된다.
		assertNull(raw("{ \"type\": \"lucky_ore\", \"blocks\": [] }"));
		assertNull(raw("{ \"type\": \"lucky_ore\", \"blocks\": [\"대문자 안 됨\"] }"));
	}

	// ------------------------------------------------------------------ 개수 분포

	@Test
	void 확률의_합은_1_이다() {
		double total = 0.0;
		for (int extra = 0; extra <= LuckyOreEffect.MAX_EXTRA; extra++) {
			total += LuckyOreEffect.chanceOf(extra);
		}
		assertEquals(1.0, total, 1.0e-9, "굴리는 범위와 가중치의 합이 어긋나면 분포가 무너진다");
	}

	@Test
	void 기댓값은_1_67_이다() {
		double expected = 0.0;
		for (int extra = 0; extra <= LuckyOreEffect.MAX_EXTRA; extra++) {
			expected += extra * LuckyOreEffect.chanceOf(extra);
		}

		assertEquals(5.0 / 3.0, expected, 1.0e-9, "(1*30 + 2*20 + 3*10) / 60 이다");
	}

	/** 꽝(0개)은 2026-10-16 사람 결정으로 걷어냈다. 1~3개의 비율 30:20:10 은 그대로다. */
	@Test
	void 확률은_0_50_33_17_이다() {
		assertEquals(0.0, LuckyOreEffect.chanceOf(0), 1.0e-9, "0개는 나오지 않는다");
		assertEquals(30.0 / 60.0, LuckyOreEffect.chanceOf(1), 1.0e-9);
		assertEquals(20.0 / 60.0, LuckyOreEffect.chanceOf(2), 1.0e-9);
		assertEquals(10.0 / 60.0, LuckyOreEffect.chanceOf(3), 1.0e-9);
		assertEquals(0.0, LuckyOreEffect.chanceOf(4), 1.0e-9, "4개는 나올 수 없다");
		assertEquals(0.0, LuckyOreEffect.chanceOf(-1), 1.0e-9);
	}

	@Test
	void 굴린_값이_구간_경계에서_넘어간다() {
		assertEquals(1, LuckyOreEffect.extraForRoll(0));
		assertEquals(1, LuckyOreEffect.extraForRoll(29));
		assertEquals(2, LuckyOreEffect.extraForRoll(30));
		assertEquals(2, LuckyOreEffect.extraForRoll(49));
		assertEquals(3, LuckyOreEffect.extraForRoll(50));
		assertEquals(3, LuckyOreEffect.extraForRoll(59));
	}

	@Test
	void 범위를_벗어난_값도_안전하다() {
		assertEquals(0, LuckyOreEffect.extraForRoll(-1));
		assertEquals(LuckyOreEffect.MAX_EXTRA, LuckyOreEffect.extraForRoll(1000));
	}

	@Test
	void 실제로_굴려도_1_부터_3_까지_나온다() {
		LuckyOreEffect effect = create("{ \"type\": \"lucky_ore\" }");
		RandomSource random = RandomSource.create(20260902L);

		int rolls = 200_000;
		int[] counts = new int[LuckyOreEffect.MAX_EXTRA + 1];
		for (int i = 0; i < rolls; i++) {
			counts[effect.rollExtra(random)]++;
		}

		for (int extra = 0; extra <= LuckyOreEffect.MAX_EXTRA; extra++) {
			double ratio = counts[extra] / (double) rolls;
			assertEquals(LuckyOreEffect.chanceOf(extra), ratio, 0.01,
					extra + "개가 나오는 비율이 정의한 확률과 달라졌습니다");
		}
		assertEquals(0, counts[0], "0개(꽝)는 나오면 안 된다 — 2026-10-16 에 대가를 걷어냈다");
	}

	@Test
	void 난수원이_없으면_0_이다() {
		LuckyOreEffect effect = create("{ \"type\": \"lucky_ore\" }");

		assertEquals(0, effect.rollExtra(null), "난수를 못 굴렸다고 이득을 확정으로 줄 수는 없다");
	}

	// ------------------------------------------------------------------ 알림 문구

	@Test
	void 문구는_증강_이름과_아이템과_개수를_잇는다() {
		Component message =
				LuckyOreEffect.announcement("운수 좋은 날", Component.literal("다이아몬드"), 2);

		assertEquals("[증강] 운수 좋은 날 — 다이아몬드 +2", message.getString());
	}

	@Test
	void 증강_이름이나_아이템이_없어도_문구가_만들어진다() {
		assertEquals("[증강] 운수 좋은 날 — +1",
				LuckyOreEffect.announcement(null, null, 1).getString());
		assertEquals("[증강] 운수 좋은 날 — +1",
				LuckyOreEffect.announcement("  ", null, 1).getString(),
				"이름이 비면 기본 이름으로 되돌아간다");
	}

	// ------------------------------------------------------------------ 도우미

	private static LuckyOreEffect create(String json) {
		return assertInstanceOf(LuckyOreEffect.class, raw(json));
	}

	private static PerkEffect raw(String json) {
		JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
		return PerkEffectType.LUCKY_ORE.create("sharedfate:테스트", 0, parsed);
	}
}
