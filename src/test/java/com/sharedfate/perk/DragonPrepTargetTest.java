package com.sharedfate.perk;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.net.SharedFateNetworking;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「드래곤전 대비」(0.44.0-dev)의 믹스인 다섯이 기대는 <b>바닐라 쪽 사실</b>. refmap 이 없어 대상이 틀려도
 * 빌드는 통과하므로 여기서 먼저 터지게 한다. 게임 창을 띄우지 않고 확인할 수 있는 것은 이 모양까지다.
 */
class DragonPrepTargetTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static Method declared(Class<?> owner, String name, Class<?>... parameters) {
		try {
			return owner.getDeclaredMethod(name, parameters);
		} catch (NoSuchMethodException error) {
			throw new AssertionError(owner.getSimpleName() + "." + name + " 이 사라졌다", error);
		}
	}

	@Test
	void 경험치_입구_셋이_그대로_있다() {
		assertEquals(int.class, declared(Player.class, "getXpNeededForNextLevel").getReturnType());
		assertEquals(void.class, declared(Player.class, "giveExperiencePoints", int.class).getReturnType());
		assertEquals(void.class, declared(Player.class, "giveExperienceLevels", int.class).getReturnType());
	}

	@Test
	void 토템_판정이_그대로_있다() {
		Method totem = declared(LivingEntity.class, "checkTotemDeathProtection", DamageSource.class);
		assertEquals(boolean.class, totem.getReturnType());
	}

	@Test
	void 엔더맨의_시선_판정이_그대로_있다() {
		Method stare = declared(Enderman.class, "isBeingStaredBy", Player.class);
		assertEquals(boolean.class, stare.getReturnType());
	}

	@Test
	void 화살_소모와_탄_찾기가_그대로_있다() {
		Method useAmmo = declared(ProjectileWeaponItem.class, "useAmmo",
				ItemStack.class, ItemStack.class, LivingEntity.class, boolean.class);
		assertEquals(ItemStack.class, useAmmo.getReturnType());
		assertTrue(Modifier.isStatic(useAmmo.getModifiers()));
		assertEquals(ItemStack.class,
				declared(Player.class, "getProjectile", ItemStack.class).getReturnType());
	}

	@Test
	void 믹스인_설정에_다섯이_모두_올라_있다() throws IOException {
		Set<String> listed = new HashSet<>();
		try (InputStream in = DragonPrepTargetTest.class.getClassLoader()
				.getResourceAsStream("sharedfate.mixins.json")) {
			assertNotNull(in);
			JsonArray mixins = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonArray("mixins");
			mixins.forEach(element -> listed.add(element.getAsString()));
		}
		for (String name : new String[] {"PlayerExperienceRulesMixin", "LivingEntityUndyingOathMixin",
				"EndermanGazeMixin", "EndlessQuiverMixin", "EndlessQuiverPlayerMixin"}) {
			// 화살통 둘은 클라이언트에서도 같은 답을 내야 하므로 공용(mixins) 목록이어야 한다.
			assertTrue(listed.contains(name), name + " 이 공용 믹스인 목록에 없다");
		}
	}

	@Test
	void 통신_규약은_40_이다() {
		// 0.44.0-dev — PerkClientFeaturesPayload 에 화살통 칸이 늘었다.
		assertEquals(40, SharedFateNetworking.PROTOCOL_VERSION);
	}
}
