package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.LegacyGearEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code legacy_gear}(프리즘 「유산」)의 정의 읽기와 전멸 시점 승계를 본다.
 *
 * <h2>확장 태그 판정 자체는 여기서 시험하지 않는다</h2>
 * <p>아이템 태그는 데이터팩이 로드되어야 채워지는데 단위 시험에는 데이터팩이 없다. 그래서
 * 여기서는 "매칭되는 게 있다고 잘못 집지는 않는가"(가짜 양성이 없는가)만 확인한다.
 *
 * <p><b>「곡괭이가 실제로 잡히는가」는 {@code LegacyGearScopeTest} 가 본다.</b> 판정의 본
 * 기준이 태그가 아니라 아이템 컴포넌트({@link LegacyGearEffect#hasGearComponent})라서,
 * 데이터팩 없는 단위 시험에서도 도구·무기·방어구를 하나하나 확인할 수 있다.
 */
class LegacyGearEffectTest {

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
	void 대상_태그는_언제나_고정이다() {
		LegacyGearEffect effect = assertInstanceOf(LegacyGearEffect.class,
				PerkEffectType.LEGACY_GEAR.create("sharedfate:테스트", 0,
						com.google.gson.JsonParser.parseString("{ \"type\": \"legacy_gear\" }")
								.getAsJsonObject()));

		assertEquals(1, effect.matcher().tags().size());
		assertEquals("sharedfate:legacy_gear",
				effect.matcher().tags().getFirst().location().toString());
		assertTrue(effect.matcher().itemIds().isEmpty(), "개별 아이템이 아니라 태그 하나로만 고정한다");
	}

	@Test
	void 효과_타입_문자열로_찾을_수_있다() {
		assertSame(PerkEffectType.LEGACY_GEAR, PerkEffectType.fromId("legacy_gear"));
	}

	@Test
	void apply_와_remove_는_아무_일도_하지_않는다() {
		LegacyGearEffect effect = assertInstanceOf(LegacyGearEffect.class,
				PerkEffectType.LEGACY_GEAR.create("sharedfate:테스트", 0,
						com.google.gson.JsonParser.parseString("{ \"type\": \"legacy_gear\" }")
								.getAsJsonObject()));

		assertDoesNotThrow(() -> effect.apply(null));
		assertDoesNotThrow(() -> effect.remove(null));
	}

	// ------------------------------------------------------------------ 회차 경계

	@Test
	void 승계분은_전멸_초기화에서도_사라지지_않는다() {
		TeamState state = TeamState.fresh(20.0F);
		state.legacyGear.add(new ItemStack(Items.DIAMOND_PICKAXE));

		state.resetAfterDeath(20.0F, false);

		assertEquals(1, state.legacyGear.size(),
				"전멸을 넘겨야 뜻이 있는 값이라 resetAfterDeath 가 비우면 안 된다");
		assertTrue(state.legacyGear.getFirst().is(Items.DIAMOND_PICKAXE));
	}

	// ------------------------------------------------------------------ 전멸 시점 승계 (captureAtDeath)

	@Test
	void 전멸_시점에_가진_장비를_스냅샷으로_남긴다(@TempDir Path dir) throws IOException {
		loadSingle(dir, """
				{ "id": "sharedfate:유산", "rarity": "prism", "name": "유산",
				  "effects": [ { "type": "legacy_gear" } ] }
				""");
		TeamState state = TeamState.fresh(20.0F);
		state.ownedPerks.add("sharedfate:유산");
		state.equipment.set(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));

		int captured = PerkLegacyGear.captureAtDeath(state);

		assertEquals(1, captured);
		assertEquals(1, state.legacyGear.size());
		assertTrue(state.legacyGear.getFirst().is(Items.DIAMOND_HELMET));
	}

	@Test
	void 고를_때_없던_장비도_전멸_시점에_새로_갖췄으면_넘어간다(@TempDir Path dir) throws IOException {
		// 고른 시점의 보유물이 아니라, 전멸하는 순간 실제로 가진 것이 기준이어야 한다.
		Perk perk = loadSingle(dir, """
				{ "id": "sharedfate:유산", "rarity": "prism", "name": "유산",
				  "effects": [ { "type": "legacy_gear" } ] }
				""");
		TeamState state = TeamState.fresh(20.0F);
		state.ownedPerks.add(perk.id());
		// 고를 때는 아무것도 없었고, 이후 회차를 진행하며 새로 투구를 갖췄다. 방어구 네 칸은 태그 판정 없이 직접 잡으므로
		// (LegacyGearEffect 문서 참고) 데이터팩 없는 단위 시험에서도 실제로 잡히는지 볼 수 있다.
		state.equipment.set(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));

		int captured = PerkLegacyGear.captureAtDeath(state);

		assertEquals(1, captured, "고를 때 없던 장비도 전멸 시점엔 승계 대상이어야 한다");
		assertTrue(state.legacyGear.getFirst().is(Items.DIAMOND_HELMET));
	}

	@Test
	void 스냅샷은_사본이라_인벤토리를_비우지_않는다(@TempDir Path dir) throws IOException {
		// 고를 때 몰수하던 동작은 없다. 전멸 시점에도 여기서는 복사만 한다.
		loadSingle(dir, """
				{ "id": "sharedfate:유산", "rarity": "prism", "name": "유산",
				  "effects": [ { "type": "legacy_gear" } ] }
				""");
		TeamState state = TeamState.fresh(20.0F);
		state.ownedPerks.add("sharedfate:유산");
		state.mainItems.set(0, new ItemStack(Items.DIAMOND_PICKAXE));
		state.equipment.set(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));

		assertEquals(2, PerkLegacyGear.captureAtDeath(state));

		assertTrue(state.mainItems.get(0).is(Items.DIAMOND_PICKAXE));
		assertTrue(state.equipment.get(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET));
	}

	@Test
	void 이전_전멸의_스냅샷을_새로_덮어쓴다(@TempDir Path dir) throws IOException {
		loadSingle(dir, """
				{ "id": "sharedfate:유산", "rarity": "prism", "name": "유산",
				  "effects": [ { "type": "legacy_gear" } ] }
				""");
		TeamState state = TeamState.fresh(20.0F);
		state.ownedPerks.add("sharedfate:유산");
		state.legacyGear.add(new ItemStack(Items.DIAMOND_SHOVEL));
		state.equipment.set(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));

		int captured = PerkLegacyGear.captureAtDeath(state);

		assertEquals(1, captured);
		assertEquals(1, state.legacyGear.size(), "예전 스냅샷이 남아 새 것과 섞이면 안 된다");
		assertTrue(state.legacyGear.getFirst().is(Items.DIAMOND_HELMET));
	}

	@Test
	void 유산이_없으면_스냅샷을_뜨지_않는다(@TempDir Path dir) throws IOException {
		TeamState state = TeamState.fresh(20.0F);
		state.mainItems.set(0, new ItemStack(Items.DIAMOND_PICKAXE));

		assertEquals(0, PerkLegacyGear.captureAtDeath(state));
		assertTrue(state.legacyGear.isEmpty());
	}

	// ------------------------------------------------------------------ 도우미

	private static Perk loadSingle(Path dir, String perkJson) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME),
				"{ \"perks\": [" + perkJson + "] }", StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
		return PerkRegistry.all().getFirst();
	}
}
