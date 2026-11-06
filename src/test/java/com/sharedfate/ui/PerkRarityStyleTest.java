package com.sharedfate.ui;

import com.sharedfate.perk.PerkRarity;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 등급마다 쓰는 색과 그림({@link PerkRarityStyle}).
 *
 * <p>그림 이름이 틀리면 게임은 멈추지 않고 <b>보라·검정 바둑판</b>(없는 그림)을 그린다. 창을 띄우지 않는
 * 이 시험이 그 자리를 붙든다 — 이름마다 {@code textures/gui/sprites} 아래에 파일이 있는지 본다.
 */
class PerkRarityStyleTest {

	@Test
	void 등급색은_예전_값_그대로다() {
		assertEquals(0xFFC0C6CC, PerkRarityStyle.SILVER.color());
		assertEquals(0xFFFFC63A, PerkRarityStyle.GOLD.color());
		assertEquals(0xFF5FE0D8, PerkRarityStyle.PRISM.color());
		assertEquals(DragonPrepStyle.COLOR, PerkRarityStyle.TRIAL.color());
	}

	@Test
	void 모든_등급에_모양이_있다() {
		for (PerkRarity rarity : PerkRarity.values()) {
			assertEquals(rarity, PerkRarityStyle.of(rarity).rarity());
			assertEquals(PerkRarityStyle.of(rarity), PerkRarityStyle.ofId(rarity.id()));
		}
		assertEquals(PerkRarityStyle.SILVER, PerkRarityStyle.of(null));
	}

	@Test
	void 모르는_등급_문자열은_실버다() {
		assertEquals(PerkRarityStyle.SILVER, PerkRarityStyle.ofId(null));
		assertEquals(PerkRarityStyle.SILVER, PerkRarityStyle.ofId("???"));
		assertEquals(PerkRarityStyle.SILVER.color(), PerkRarityStyle.colorOf("rare"));
	}

	@Test
	void 그림_이름마다_파일이_있다() {
		List<Identifier> sprites = new ArrayList<>(PerkRarityStyle.SHARED_SPRITES);
		for (PerkRarityStyle style : PerkRarityStyle.values()) {
			sprites.addAll(style.sprites());
		}
		for (Identifier sprite : sprites) {
			assertTrue(exists(texture(sprite)), "그림이 없다: " + sprite);
		}
	}

	@Test
	void 늘여_그리는_그림은_나인_슬라이스_설정이_있다() {
		// 카드 틀 · 띠 · 빛 · 뒷면 · 판은 카드 크기에 맞춰 늘인다. 설정이 없으면 통째로 늘어나
		// 금속 틀이 두꺼워지고 뭉개진다.
		List<Identifier> sliced = new ArrayList<>(List.of(PerkRarityStyle.CARD_GLOW,
				PerkRarityStyle.CARD_BACK, PerkRarityStyle.PANEL));
		for (PerkRarityStyle style : PerkRarityStyle.values()) {
			sliced.add(style.card());
			sliced.add(style.band());
		}
		for (Identifier sprite : sliced) {
			assertTrue(exists(texture(sprite) + ".mcmeta"), "나인 슬라이스 설정이 없다: " + sprite);
		}
	}

	@Test
	void 그림은_이_모드_이름공간이다() {
		for (PerkRarityStyle style : PerkRarityStyle.values()) {
			for (Identifier sprite : style.sprites()) {
				assertEquals("sharedfate", sprite.getNamespace());
				assertTrue(sprite.getPath().endsWith("_" + style.rarity().id()), sprite.toString());
			}
		}
	}

	private static String texture(Identifier sprite) {
		return "/assets/" + sprite.getNamespace() + "/textures/gui/sprites/" + sprite.getPath()
				+ ".png";
	}

	private static boolean exists(String resource) {
		try (InputStream stream = PerkRarityStyleTest.class.getResourceAsStream(resource)) {
			return stream != null;
		} catch (java.io.IOException error) {
			return false;
		}
	}
}
