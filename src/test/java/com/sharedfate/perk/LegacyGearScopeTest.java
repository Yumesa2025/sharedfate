package com.sharedfate.perk;

import com.mojang.serialization.DataResult;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.LegacyGearEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「유산」이 <b>무엇을</b> 가져가는지, 그리고 가져간 것이 <b>온전한지</b>를 못박는다.
 *
 * <h2>여기서는 태그를 쓰지 않는다</h2>
 * <p>단위 시험에는 데이터팩이 없어 아이템 태그가 안 묶인다({@code LegacyGearEffectTest} 문서
 * 참고). 판정의 본 기준이 태그가 아니라 아이템 컴포넌트
 * ({@link LegacyGearEffect#hasGearComponent})라서 데이터팩 없이도 답이 같으므로,
 * 곡괭이 하나하나까지 여기서 전부 확인할 수 있다.
 */
class LegacyGearScopeTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	// ------------------------------------------------------------------ 무엇이 「장비」인가

	@Test
	void 도구는_전부_장비로_본다() {
		// 곡괭이·도끼·삽·괭이는 물론 낚싯대·가위·부싯돌·솔까지 「도구」다.
		for (Item tool : List.of(Items.DIAMOND_PICKAXE, Items.NETHERITE_AXE, Items.IRON_SHOVEL,
				Items.GOLDEN_HOE, Items.WOODEN_PICKAXE, Items.FISHING_ROD, Items.SHEARS,
				Items.FLINT_AND_STEEL, Items.BRUSH)) {
			assertTrue(LegacyGearEffect.hasGearComponent(new ItemStack(tool)),
					tool + " 은(는) 도구이므로 유산으로 넘어가야 한다");
		}
	}

	@Test
	void 무기와_방어구와_방패도_장비로_본다() {
		for (Item gear : List.of(Items.DIAMOND_SWORD, Items.BOW, Items.CROSSBOW, Items.TRIDENT,
				Items.MACE, Items.SHIELD, Items.ELYTRA, Items.NETHERITE_HELMET,
				Items.DIAMOND_CHESTPLATE, Items.LEATHER_BOOTS, Items.TURTLE_HELMET)) {
			assertTrue(LegacyGearEffect.hasGearComponent(new ItemStack(gear)),
					gear + " 은(는) 장비이므로 유산으로 넘어가야 한다");
		}
	}

	@Test
	void 자원과_음식과_블록은_장비가_아니다() {
		for (Item junk : List.of(Items.STONE, Items.DIRT, Items.COBBLESTONE, Items.DIAMOND,
				Items.IRON_INGOT, Items.APPLE, Items.ARROW, Items.ENDER_PEARL, Items.TORCH,
				Items.OAK_LOG, Items.STICK)) {
			assertFalse(LegacyGearEffect.hasGearComponent(new ItemStack(junk)),
					junk + " 은(는) 자원이라 유산으로 넘어가면 안 된다");
		}
	}

	// ------------------------------------------------------------------ 어디를 훑는가

	@Test
	void 인벤토리에_있던_도구가_전멸_스냅샷에_들어간다(@TempDir Path dir) throws IOException {
		TeamState state = 유산을_가진_팀(dir);
		state.mainItems.set(0, new ItemStack(Items.DIAMOND_PICKAXE));
		state.mainItems.set(8, new ItemStack(Items.NETHERITE_AXE));
		state.extraItems.set(3, new ItemStack(Items.IRON_SHOVEL));
		state.enderContainer.setItem(1, new ItemStack(Items.FISHING_ROD));
		state.mainItems.set(1, new ItemStack(Items.STONE, 64));

		assertEquals(4, PerkLegacyGear.captureAtDeath(state),
				"곡괭이·도끼·삽·낚싯대는 넘어가고 돌은 안 넘어가야 한다");
		assertFalse(state.legacyGear.stream().anyMatch(stack -> stack.is(Items.STONE)));
	}

	@Test
	void 보조_손의_장비는_넘어가고_보조_손의_자원은_안_넘어간다(@TempDir Path dir) throws IOException {
		TeamState state = 유산을_가진_팀(dir);
		state.equipment.set(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));

		assertEquals(1, PerkLegacyGear.captureAtDeath(state), "보조 손의 방패도 장비다");
		assertTrue(state.legacyGear.getFirst().is(Items.SHIELD));

		state.equipment.set(EquipmentSlot.OFFHAND, new ItemStack(Items.DIRT, 64));
		assertEquals(0, PerkLegacyGear.captureAtDeath(state),
				"보조 손에 쌓아 둔 흙까지 넘기면 「인벤토리를 통째로 넘기는 증강」이 된다");
	}

	// ------------------------------------------------------------------ 온전히 따라오는가

	@Test
	void 인챈트와_이름과_내구도가_그대로_따라온다(@TempDir Path dir) throws IOException {
		TeamState state = 유산을_가진_팀(dir);
		ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		EnchantmentHelper.setEnchantments(pickaxe, 효율_다섯());
		pickaxe.set(DataComponents.CUSTOM_NAME, Component.literal("할아버지의 곡괭이"));
		pickaxe.setDamageValue(123);
		state.mainItems.set(0, pickaxe);

		assertEquals(1, PerkLegacyGear.captureAtDeath(state));
		ItemStack inherited = state.legacyGear.getFirst();

		assertNotSame(pickaxe, inherited, "원본을 그대로 담으면 나중에 원본이 비워질 때 같이 비워진다");
		assertEquals(5, 효율_등급(inherited), "인챈트가 따라오지 않으면 유산이 아니라 새 곡괭이다");
		assertEquals("할아버지의 곡괭이", inherited.getHoverName().getString());
		assertEquals(123, inherited.getDamageValue());
	}

	/**
	 * 「유산」의 인챈트가 회차 경계를 넘어가려면 <b>레지스트리를 낀 ops</b> 로 적어야 한다.
	 *
	 * <p>인챈트 컴포넌트는 {@code Holder<Enchantment>} 를 담고 있어 데이터팩 레지스트리를
	 * 봐야 이름을 적을 수 있다. 맨 {@code NbtOps} 로 적으면 그 항목만 조용히 빠지거나 아이템
	 * 자체가 통째로 실패한다 — 넘어간 곡괭이에서 효율이 사라지는 길이 이것이다. 팀 명단
	 * 파일처럼 {@code ItemStack.CODEC} 을 직접 쓰는 자리는 반드시 레지스트리 ops 를 넘겨야
	 * 한다는 사실을 여기에 못박아 둔다.
	 */
	@Test
	void 레지스트리_없이_적으면_인챈트가_남지_않는다() {
		ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		EnchantmentHelper.setEnchantments(pickaxe, 효율_다섯());

		DataResult<Tag> plain = ItemStack.CODEC.encodeStart(NbtOps.INSTANCE, pickaxe);
		String written = plain.resultOrPartial(error -> {
		}).map(Tag::toString).orElse("");
		assertFalse(written.contains("efficiency"),
				"맨 NbtOps 로 적으면 인챈트가 남지 않는다 — 이 시험이 깨졌다면 반가운 일이니 지워도 된다");

		DataResult<Tag> withRegistries = ItemStack.CODEC.encodeStart(
				TestBootstrap.registries().createSerializationContext(NbtOps.INSTANCE), pickaxe);
		assertTrue(withRegistries.result().isPresent(), "레지스트리를 끼면 적을 수 있어야 한다");
		assertTrue(withRegistries.result().orElseThrow().toString().contains("efficiency"));
	}

	// ------------------------------------------------------------------ 도우미

	/** 「유산」을 보유한, 아무것도 안 가진 팀. */
	private static TeamState 유산을_가진_팀(Path dir) throws IOException {
		Perk perk = 유산_증강(dir);
		TeamState state = TeamState.fresh(20.0F);
		state.ownedPerks.add(perk.id());
		return state;
	}

	private static Perk 유산_증강(Path dir) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), """
				{ "perks": [
				  { "id": "sharedfate:유산", "rarity": "prism", "name": "유산",
				    "effects": [ { "type": "legacy_gear" } ] }
				] }
				""", StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
		return PerkRegistry.all().getFirst();
	}

	private static ItemEnchantments 효율_다섯() {
		Holder<Enchantment> efficiency = 인챈트(Enchantments.EFFICIENCY);
		ItemEnchantments.Mutable mutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
		mutable.set(efficiency, 5);
		return mutable.toImmutable();
	}

	private static int 효율_등급(ItemStack stack) {
		ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
		if (enchantments == null) {
			return 0;
		}
		for (var entry : enchantments.entrySet()) {
			if (entry.getKey().is(Enchantments.EFFICIENCY)) {
				return entry.getIntValue();
			}
		}
		return 0;
	}

	private static Holder<Enchantment> 인챈트(ResourceKey<Enchantment> key) {
		Holder<Enchantment> holder = TestBootstrap.registries()
				.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key);
		assertNotNull(holder);
		return holder;
	}
}
