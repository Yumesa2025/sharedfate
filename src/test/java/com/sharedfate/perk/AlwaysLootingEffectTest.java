package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.AlwaysLootingEffect;
import com.sharedfate.perk.effect.LootBonusEffect;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 사냥 세트가 쓰는 {@code always_looting} 과, 그것이 {@code loot_bonus} 와 겹칠 때의 셈을 본다.
 *
 * <p>살아 있는 플레이어 없이 {@link PerkLootRules#bonusLootingLevels(Iterable, ItemStack)} 를
 * 그대로 부른다. 실제 경로는 「보유 증강 효과 + 켜진 세트 효과」를 한 목록으로 합쳐 이 메서드에
 * 넘기므로, 여기서 목록을 손으로 만든 것과 셈이 같다.
 */
class AlwaysLootingEffectTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 정의 읽기

	@Test
	void 등급을_읽는다() {
		assertEquals(3, always(3).levels());
		assertEquals(1, always(1).levels());
	}

	@Test
	void levels_가_없거나_범위를_벗어나면_버린다() {
		assertNull(raw("{ \"type\": \"always_looting\" }"));
		assertNull(raw("{ \"type\": \"always_looting\", \"levels\": 0 }"));
		assertNull(raw("{ \"type\": \"always_looting\", \"levels\": -3 }"));
		assertNull(raw("{ \"type\": \"always_looting\", \"levels\": 99 }"));
		assertNull(raw("{ \"type\": \"always_looting\", \"levels\": \"셋\" }"));
	}

	/**
	 * {@code loot_bonus} 와 정반대다 — 들 물건을 <b>안 적어야</b> 정상이다.
	 *
	 * <p>이 형은 조건이 없는 것이 뜻이라, 실수로 {@code items} 를 적어 두어도 조용히 무시하고
	 * 언제나 걸린다.
	 */
	@Test
	void 들_물건을_적지_않아도_읽힌다() {
		assertEquals(3, always(3).levels());

		JsonObject withItems = JsonParser
				.parseString("{ \"type\": \"always_looting\", \"levels\": 2, "
						+ "\"items\": [\"minecraft:diamond_hoe\"] }")
				.getAsJsonObject();
		AlwaysLootingEffect effect = assertInstanceOf(AlwaysLootingEffect.class,
				AlwaysLootingEffect.fromJson("sharedfate:테스트", 0, withItems));
		assertEquals(2, effect.levels());
		assertEquals(2, PerkLootRules.bonusLootingLevels(List.of(effect), ItemStack.EMPTY),
				"items 를 적어 두어도 조건이 되지는 않는다");
	}

	/**
	 * <b>{@code PerkEffectType} 등록을 빠뜨리면 여기서 터진다.</b>
	 *
	 * <p>등록이 없으면 세트 정의를 읽을 때 「알 수 없는 효과 type」이라 그 단계 전체가 버려진다.
	 * 빌드는 통과하고 그 세트만 조용히 사라지므로 이 시험 말고는 알 방법이 없다.
	 */
	@Test
	void 효과_타입에_등록되어_있다() {
		PerkEffectType type = PerkEffectType.fromId("always_looting");
		assertNotNull(type,
				"PerkEffectType 에 ALWAYS_LOOTING(\"always_looting\", AlwaysLootingEffect::fromJson) 을 "
						+ "등록해야 한다");

		JsonObject json = JsonParser
				.parseString("{ \"type\": \"always_looting\", \"levels\": 3 }")
				.getAsJsonObject();
		AlwaysLootingEffect effect = assertInstanceOf(AlwaysLootingEffect.class,
				type.create("set/hunt/3", 0, json),
				"등록은 되어 있는데 다른 팩토리가 물려 있다");
		assertEquals(3, effect.levels());
	}

	// ------------------------------------------------------------------ 겹칠 때의 셈

	/**
	 * 세트 단계는 누적이라 사냥 셋을 모으면 2단계(약탈 I)와 3단계(약탈 IV)가 둘 다 켜진다.
	 * 그때 결과는 <b>IV</b> 이어야 한다. 더해서 V 가 되면 3단계 설명 「약탈이 IV 으로
	 * 오릅니다」와 실제가 어긋난다.
	 */
	@Test
	void 약탈_1과_4가_함께_켜지면_4이지_5가_아니다() {
		List<PerkEffect> both = List.of(always(1), always(4));

		assertEquals(4, PerkLootRules.bonusLootingLevels(both, ItemStack.EMPTY));
		assertEquals(4, PerkLootRules.bonusLootingLevels(both, new ItemStack(Items.DIAMOND_SWORD)));
	}

	/** 적은 순서가 뒤바뀌어도 답이 같다. 정의 파일에 단계를 거꾸로 적어도 흔들리면 안 된다. */
	@Test
	void 순서가_뒤바뀌어도_더_높은_쪽이_이긴다() {
		assertEquals(4, PerkLootRules.bonusLootingLevels(
				List.of(always(4), always(1)), ItemStack.EMPTY));
	}

	/** 사냥 2단계만 켜졌으면 약탈 I 그대로다. */
	@Test
	void 두_개만_모으면_약탈_1_이다() {
		assertEquals(1, PerkLootRules.bonusLootingLevels(List.of(always(1)), ItemStack.EMPTY));
	}

	/**
	 * 세트 보상은 <b>무기와 무관하다.</b> 맨손이어도 걸린다.
	 *
	 * <p>주손이 비었다고 곧바로 0 을 돌려주면 맨손으로 잡은 몹에 세트가 아예 안 걸린다.
	 */
	@Test
	void 빈_손에도_걸린다() {
		assertEquals(3, PerkLootRules.bonusLootingLevels(List.of(always(3)), ItemStack.EMPTY));
		assertEquals(3, PerkLootRules.bonusLootingLevels(List.of(always(3)), null));
	}

	// ------------------------------------------------------------------ 「수확자」가 그대로인가

	/**
	 * {@code loot_bonus} 는 <b>다이아 호미를 들었을 때만</b> 걸린다.
	 *
	 * <p>「수확자」의 정의를 그대로 옮겨 적었다. 이 조건이 느슨해지면 여기서 터진다.
	 */
	@Test
	void 수확자는_다이아_호미일_때만_약탈_5_다() {
		List<PerkEffect> harvester = List.of(harvester());

		assertEquals(5, PerkLootRules.bonusLootingLevels(harvester, new ItemStack(Items.DIAMOND_HOE)));
		assertEquals(0, PerkLootRules.bonusLootingLevels(harvester, new ItemStack(Items.IRON_HOE)));
		assertEquals(0, PerkLootRules.bonusLootingLevels(harvester, new ItemStack(Items.DIAMOND_SWORD)));
		assertEquals(0, PerkLootRules.bonusLootingLevels(harvester, ItemStack.EMPTY));
		assertEquals(0, PerkLootRules.bonusLootingLevels(harvester, null));
	}

	/**
	 * {@code loot_bonus} 끼리는 <b>더한다.</b>
	 *
	 * <p>「가장 높은 하나만」 규칙이 이쪽까지 번지면 안 된다. 서로 다른 증강이 각각 약속한
	 * 등급이라 하나만 골라 줄 이유가 없다.
	 */
	@Test
	void loot_bonus_끼리는_더한다() {
		List<PerkEffect> two = List.of(harvester(), lootBonus(2, "minecraft:diamond_hoe"));

		assertEquals(7, PerkLootRules.bonusLootingLevels(two, new ItemStack(Items.DIAMOND_HOE)));
	}

	/**
	 * 형이 다르면 더한다.
	 *
	 * <p>「호미를 들어서 얻은 것」과 「팀이 늘 갖고 있는 것」은 서로 다른 약속이다. 다이아 호미를
	 * 들지 않았으면 세트 몫만 남는다.
	 */
	@Test
	void 수확자와_세트는_서로_더해진다() {
		List<PerkEffect> mixed = List.of(harvester(), always(1), always(3));

		assertEquals(8, PerkLootRules.bonusLootingLevels(mixed, new ItemStack(Items.DIAMOND_HOE)));
		assertEquals(3, PerkLootRules.bonusLootingLevels(mixed, new ItemStack(Items.DIAMOND_SWORD)));
	}

	// ------------------------------------------------------------------ 없을 때

	/** 세트도 증강도 없으면 0 이다. 바닐라와 비트 하나 다르지 않아야 한다. */
	@Test
	void 아무것도_없으면_0_이다() {
		assertEquals(0, PerkLootRules.bonusLootingLevels(List.of(), new ItemStack(Items.DIAMOND_HOE)));
		assertEquals(0, PerkLootRules.bonusLootingLevels(null, new ItemStack(Items.DIAMOND_HOE)));
		assertEquals(0, PerkLootRules.bonusLootingLevels(List.of(new PerkEffect() {
		}), ItemStack.EMPTY), "상관없는 효과는 세지 않는다");
	}

	/** 살아 있는 플레이어가 아니면 아무 일도 없다. 클라이언트 계산은 바닐라 그대로다. */
	@Test
	void 팀이_없으면_추가_등급은_0_이다() {
		assertEquals(0, PerkLootRules.bonusLootingLevels((LivingEntity) null));
	}

	// ------------------------------------------------------------------ Mixin 대상 못박기

	/**
	 * {@code EnchantmentHelperLootingMixin} 이 파고드는 자리.
	 *
	 * <p>이 저장소는 refmap 을 만들지 않아 {@code @Inject} 의 대상이 틀려도 <b>빌드가 그냥
	 * 통과</b>하고, 몹을 잡는 순간에야 티가 난다(또는 조용히 아무 일도 안 한다). 서술자만이라도
	 * 여기서 붙들어 둔다.
	 *
	 * <p>이름이 겹치지 않는다는 것도 함께 못박는다. 겹치는 순간 이름만 적은 믹스인이 엉뚱한
	 * 쪽을 고를 수 있다.
	 */
	@Test
	void 약탈_등급을_묻는_메서드가_그대로_있다() {
		Method target = assertDoesNotThrow(() -> EnchantmentHelper.class.getDeclaredMethod(
						"getEnchantmentLevel", Holder.class, LivingEntity.class),
				"이 서술자가 바뀌면 loot_bonus 와 always_looting 이 통째로 무동작이 된다");

		assertTrue(Modifier.isStatic(target.getModifiers()),
				"믹스인이 static 으로 파고들고 있다");
		assertEquals(int.class, target.getReturnType(),
				"CallbackInfoReturnable<Integer> 가 이 반환형에 맞춰져 있다");

		long sameName = Arrays.stream(EnchantmentHelper.class.getDeclaredMethods())
				.filter(method -> method.getName().equals("getEnchantmentLevel"))
				.count();
		assertEquals(1, sameName,
				"겹침이 생겼으면 믹스인에 서술자를 적어 어느 쪽인지 못박아야 한다");
	}

	/** 약탈 인챈트의 이름표. 믹스인이 「지금 묻는 것이 약탈인가」를 이것으로 가린다. */
	@Test
	void 약탈_인챈트_키가_그대로_있다() {
		assertNotNull(Enchantments.LOOTING);
		assertEquals("looting", Enchantments.LOOTING.identifier().getPath());
	}

	// ------------------------------------------------------------------ 도우미

	private static AlwaysLootingEffect always(int levels) {
		JsonObject json = JsonParser
				.parseString("{ \"type\": \"always_looting\", \"levels\": " + levels + " }")
				.getAsJsonObject();
		return assertInstanceOf(AlwaysLootingEffect.class,
				AlwaysLootingEffect.fromJson("set/hunt/테스트", 0, json));
	}

	/** 「수확자」의 정의 그대로. */
	private static LootBonusEffect harvester() {
		return lootBonus(5, "minecraft:diamond_hoe");
	}

	private static LootBonusEffect lootBonus(int levels, String itemId) {
		JsonObject json = JsonParser
				.parseString("{ \"type\": \"loot_bonus\", \"levels\": " + levels
						+ ", \"items\": [\"" + itemId + "\"] }")
				.getAsJsonObject();
		return assertInstanceOf(LootBonusEffect.class,
				PerkEffectType.LOOT_BONUS.create("sharedfate:테스트", 0, json));
	}

	private static PerkEffect raw(String json) {
		JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
		return AlwaysLootingEffect.fromJson("sharedfate:테스트", 0, parsed);
	}
}
