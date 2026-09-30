package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.entity.SmokerBlockEntity;
import net.minecraft.world.level.levelgen.structure.Structure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세트 「개척」이 무는 바닐라 자리를 반사로 못박는다.
 *
 * <p>이 저장소는 refmap 을 만들지 않아 {@code @Inject}·{@code @ModifyArg}·
 * {@code @ModifyConstant} 의 대상 서술자가 틀려도 <b>빌드가 그냥 통과</b>한다. 그리고 여기서
 * 잡는 자리들은 전부 「한참 놀다가 처음 닿는」 곳이다 — 화로에 무언가를 넣을 때, 주민과 거래할
 * 때. 서술자가 틀리면 그때가 되어서야 터지거나, 더 나쁘게는 <b>조용히 아무 일도 안 한다.</b>
 *
 * <p>그래서 서술자만이라도 여기서 붙들어 둔다. {@code NaturalSpawnerTargetTest} 와 같은 뜻·같은
 * 모양이다.
 */
class PioneerTargetTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 화로

	/**
	 * {@code AbstractFurnaceCookTimeMixin} 이 무는 자리.
	 *
	 * <p>같은 이름의 메서드가 <b>둘</b>이라({@code ServerLevel} 짜리와 {@code RecipeHolder} 짜리)
	 * 서술자를 끝까지 적어야 한다. 이름만으로 고르면 어느 쪽에 붙을지 알 수 없다.
	 */
	@Test
	void 굽는_시간_메서드가_그_서술자_그대로_있다() {
		Method target = assertDoesNotThrow(
				() -> AbstractFurnaceBlockEntity.class.getDeclaredMethod("getTotalCookTime",
						RecipeHolder.class, AbstractFurnaceBlockEntity.class),
				"이 서술자가 바뀌면 개척 2 의 굽는 속도가 조용히 걸리지 않는다");

		assertTrue(Modifier.isStatic(target.getModifiers()), "믹스인 처리기도 static 이어야 한다");
		assertEquals(int.class, target.getReturnType(),
				"int 가 아니게 되면 @ModifyReturnValue 의 첫 인자 형이 틀어진다");
		assertEquals(2, countDeclared(AbstractFurnaceBlockEntity.class, "getTotalCookTime"),
				"겹침이 둘에서 달라졌으면 서술자를 다시 확인해야 한다");
	}

	/**
	 * 세 종류가 굽는 시간을 <b>재정의하지 않는다.</b>
	 *
	 * <p>재정의된 메서드에 믹스인을 걸면 부모 쪽에 붙어 아무 일도 하지 않는다 — 빌드도 로그도
	 * 통과한다. 화로·용광로·훈연기 중 하나가 나중에 제 시간을 갖게 되면 여기서 걸린다.
	 */
	@Test
	void 화로_세_종류는_굽는_시간을_재정의하지_않는다() {
		for (Class<?> type : new Class<?>[] {FurnaceBlockEntity.class,
				BlastFurnaceBlockEntity.class, SmokerBlockEntity.class}) {
			assertTrue(AbstractFurnaceBlockEntity.class.isAssignableFrom(type),
					type.getSimpleName() + " 가 AbstractFurnaceBlockEntity 를 안 물려받는다");
			assertEquals(0, countDeclared(type, "getTotalCookTime"),
					type.getSimpleName() + " 가 굽는 시간을 재정의하면 믹스인이 그쪽에는 안 걸린다");
		}
	}

	/**
	 * 연료가 타는 시간은 굽는 시간과 <b>다른 메서드</b>다.
	 *
	 * <p>우리가 굽는 시간만 건드려도 연료가 평소대로 타는 근거가 이것이다. 26.3 의 바닐라도
	 * 연료의 {@code cooking_fuel} 성분으로 굽는 시간만 줄이고 타는 시간은 안 건드린다.
	 */
	@Test
	void 연료가_타는_시간은_다른_자리에서_정해진다() {
		assertDoesNotThrow(() -> AbstractFurnaceBlockEntity.class.getDeclaredMethod(
						"getBurnDuration", ServerLevel.class, net.minecraft.world.item.ItemStack.class),
				"이 메서드가 사라지면 「연료는 평소대로 탄다」의 근거를 다시 확인해야 한다");
		assertDoesNotThrow(() -> AbstractFurnaceBlockEntity.class.getDeclaredMethod(
						"getSpeedMultiplier", ServerLevel.class, net.minecraft.world.item.ItemStack.class),
				"바닐라의 굽는 속도 가속. 우리 배율은 이것과 곱해진다");
	}

	// ------------------------------------------------------------------ 주민

	/**
	 * 26.3 에서 주민 클래스가 옮겨졌다.
	 *
	 * <p>{@code net.minecraft.world.entity.npc.Villager} → {@code ...npc.villager.Villager}.
	 * 믹스인의 {@code target} 문자열에 옛 경로가 남아 있으면 조용히 아무 일도 안 한다.
	 */
	@Test
	void 주민_클래스_경로가_그대로다() {
		assertEquals("net.minecraft.world.entity.npc.villager.Villager", Villager.class.getName());
	}

	/** {@code VillagerTradeMixin} 이 무는 세 메서드. */
	@Test
	void 주민_대상_메서드_셋이_그_서술자_그대로_있다() {
		Method prices = assertDoesNotThrow(
				() -> Villager.class.getDeclaredMethod("updateSpecialPrices", Player.class),
				"이 서술자가 바뀌면 거래 값이 깎이지 않는다");
		assertTrue(Modifier.isPrivate(prices.getModifiers()),
				"private 이라 같은 클래스 안에서만 불린다. 이름이 겹칠 일이 없다");

		Method allowed = assertDoesNotThrow(
				() -> Villager.class.getDeclaredMethod("allowedToRestock"),
				"연속 재입고 사이 2400틱 상수가 여기 있다");
		assertEquals(boolean.class, allowed.getReturnType());

		Method should = assertDoesNotThrow(
				() -> Villager.class.getDeclaredMethod("shouldRestock", ServerLevel.class),
				"하루치 재입고 횟수를 되돌리는 12000틱 상수가 여기 있다");
		assertEquals(boolean.class, should.getReturnType());

		Method reward = assertDoesNotThrow(
				() -> Villager.class.getDeclaredMethod("rewardTradeXp", MerchantOffer.class),
				"거래 경험치 오브를 만드는 자리");
		assertFalse(Modifier.isAbstract(reward.getModifiers()),
				"Villager 의 구현이어야 한다. AbstractVillager 쪽 추상 선언에 걸면 조용히 죽는다");
	}

	/**
	 * 우리가 끼어드는 지점으로 쓰는 호출이 그대로 있다.
	 *
	 * <p>{@code updateSpecialPrices} 안의 {@code getTradingPlayer()} 호출 <b>직전</b>에 붙는다 —
	 * 바닐라 반복문이 끝난 뒤이자 거래 목록을 클라이언트로 다시 보내기 전이다.
	 */
	@Test
	void 끼어들_지점의_호출이_그대로다() {
		assertDoesNotThrow(() -> Villager.class.getMethod("getTradingPlayer"),
				"이 호출이 사라지면 @Inject 의 대상 지점이 없어진다");
		assertDoesNotThrow(() -> Villager.class.getMethod("getOffers"),
				"깎을 거래 목록을 여기서 가져온다");
		assertEquals(MerchantOffers.class,
				assertDoesNotThrow(() -> Villager.class.getMethod("getOffers")).getReturnType());
	}

	/** 바닐라 할인이 들어가는 통로. 우리도 같은 것을 쓰므로 더해진다. */
	@Test
	void 특별가_통로가_그대로다() {
		assertDoesNotThrow(() -> MerchantOffer.class.getMethod("addToSpecialPriceDiff", int.class),
				"평판·영웅의 증표와 같은 통로. 사라지면 셈을 다시 정해야 한다");
		assertDoesNotThrow(() -> MerchantOffer.class.getMethod("getBaseCostA"),
				"영웅의 증표와 같은 셈을 쓰려면 깎기 전 원가가 필요하다");
	}

	/**
	 * 거래 경험치 오브 생성자가 그대로다.
	 *
	 * <p>{@code @ModifyArg} 의 {@code index = 4} 는 이 생성자의 인자 차례에서 나온 값이다
	 * ({@code Level}, {@code double}×3, {@code int}). 인자가 바뀌면 엉뚱한 값을 바꾼다.
	 */
	@Test
	void 경험치_오브_생성자가_그_서술자_그대로_있다() {
		Constructor<?> ctor = assertDoesNotThrow(
				() -> ExperienceOrb.class.getConstructor(Level.class, double.class, double.class,
						double.class, int.class),
				"이 서술자가 바뀌면 @ModifyArg 의 index 가 틀어진다");
		assertEquals(5, ctor.getParameterCount());
		assertEquals(int.class, ctor.getParameterTypes()[4], "index = 4 가 경험치 양이다");
	}

	// ------------------------------------------------------------------ 구조물

	/**
	 * 개척이 쓰는 구조물 셋이 26.3 에 이 이름으로 있다.
	 *
	 * <p>이름을 확인하는 것이지 세계에 실재하는지를 보는 것이 아니다. 오타는 서버 로그의 경고
	 * 한 줄로만 드러나고, 그 경고는 나침반을 처음 쓸 때까지 나오지 않는다.
	 *
	 * <p>26.3 의 {@code data/minecraft/worldgen/structure/} 에는 {@code stronghold} ·
	 * {@code ancient_city} · {@code mansion} 이 있다. <b>{@code woodland_mansion} 이 아니다.</b>
	 * 그리고 {@code tags/worldgen/structure/} 에는 이 셋의 태그가 <b>없다</b> — 마을만 태그가
	 * 있어서 {@code #minecraft:village} 로 적을 수 있다.
	 */
	@Test
	void 개척이_쓰는_구조물_이름이_레지스트리_형식에_맞는다() {
		for (String id : new String[] {"minecraft:stronghold", "minecraft:ancient_city",
				"minecraft:mansion", "minecraft:fortress"}) {
			Identifier parsed = Identifier.tryParse(id);
			assertNotNull(parsed, id + " 가 올바른 이름이 아니다");
			ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, parsed);
			assertEquals(id, key.identifier().toString());
		}
	}

	private static int countDeclared(Class<?> type, String name) {
		int count = 0;
		for (Method method : type.getDeclaredMethods()) {
			if (method.getName().equals(name)) {
				count++;
			}
		}
		return count;
	}
}
