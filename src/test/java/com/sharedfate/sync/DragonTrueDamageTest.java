package com.sharedfate.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 최후의 저항의 고정 피해(2026-11-07) — 데이터 정의와 바닐라 태그가 붙어 있는가. */
class DragonTrueDamageTest {
	private static final String ID = "sharedfate:dragon_true_damage";

	private static JsonObject json(String path) throws IOException {
		try (InputStream in = DragonTrueDamageTest.class.getResourceAsStream(path)) {
			assertNotNull(in, path + " 가 없다");
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	@Test
	void 피해_종류는_난이도_곱이_없다() throws IOException {
		JsonObject type = json("/data/sharedfate/damage_type/dragon_true_damage.json");
		assertEquals("never", type.get("scaling").getAsString(), "난이도에 따라 커지면 고정이 아니다");
		assertEquals("dragonBreath", type.get("message_id").getAsString(),
				"언어 파일이 없으니 바닐라 문구를 빌린다 — 새 키는 클라이언트에 그대로 보인다");
		assertEquals(DragonTrueDamage.TYPE.identifier().toString(), ID);
	}

	@Test
	void 방어구_마법부여_저항_방패_쿨타임을_지나간다() throws IOException {
		for (String tag : new String[] {"bypasses_armor", "bypasses_enchantments", "bypasses_effects",
				"bypasses_resistance", "bypasses_shield", "bypasses_cooldown", "no_knockback"}) {
			JsonObject json = json("/data/minecraft/tags/damage_type/" + tag + ".json");
			assertFalse(json.has("replace") && json.get("replace").getAsBoolean(),
					tag + " 를 갈아 치우면 바닐라 피해 종류가 태그에서 빠진다");
			assertTrue(json.getAsJsonArray("values").toString().contains(ID), tag + " 에 없다");
		}
	}

	@Test
	void 증강_배율을_건너뛴다() throws IOException {
		String path = "/com/sharedfate/mixin/LivingEntityPerkDamageMixin.class";
		try (InputStream in = DragonTrueDamageTest.class.getResourceAsStream(path)) {
			assertNotNull(in);
			String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
			assertTrue(bytes.contains("com/sharedfate/sync/DragonTrueDamage"),
					"받는 피해 감소 증강이 고정 피해에도 곱해진다");
		}
	}
}
