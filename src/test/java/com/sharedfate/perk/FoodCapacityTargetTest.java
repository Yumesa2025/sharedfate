package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「먹방」의 믹스인 셋({@code PlayerCanEatMixin}·{@code FoodDataCapacityMixin}·{@code CakeBlockEatMixin})이
 * 무는 바닐라 자리를 반사로 못박는다.
 *
 * <p>이 저장소는 refmap 을 만들지 않아 대상이 틀려도 <b>빌드가 그냥 통과</b>한다. 게다가 재정의되는
 * 메서드에 걸면 플레이어의 호출이 재정의된 쪽으로 가 믹스인이 조용히 죽는다. 그래서 자리 자체를
 * 붙들어 둔다. {@code FoodData.add} 는 아래 시험이 실제로 불러 보고(시험은 믹스인이 입혀진 채 돈다),
 * {@code Player.canEat}·{@code CakeBlock.eat} 은 {@code -Dmixin.debug.export=true} 로 뽑은 변환 클래스에서 봤다.
 */
class FoodCapacityTargetTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@Test
	void 먹기_가능_판정이_Player_에_그대로_있다() {
		Method canEat = assertDoesNotThrow(
				() -> Player.class.getDeclaredMethod("canEat", boolean.class),
				"Player.canEat(boolean) 이 사라졌거나 서명이 바뀌었다");

		assertEquals(boolean.class, canEat.getReturnType());
	}

	@Test
	void 서버_플레이어가_먹기_가능_판정을_재정의하지_않는다() {
		assertThrows(NoSuchMethodException.class,
				() -> ServerPlayer.class.getDeclaredMethod("canEat", boolean.class),
				"ServerPlayer 가 재정의하기 시작했다 — PlayerCanEatMixin 이 서버에서 조용히 죽는다");
	}

	@Test
	void 허기를_채우는_자리가_FoodData_add_그대로다() {
		Method add = assertDoesNotThrow(
				() -> FoodData.class.getDeclaredMethod("add", int.class, float.class),
				"FoodData.add(int, float) 가 사라졌다 — FoodDataCapacityMixin 이 무는 자리가 바뀌었다");

		assertTrue(Modifier.isPrivate(add.getModifiers()), "private 이 아니면 재정의될 수 있다");
	}

	@Test
	void 케이크_먹기가_정적_메서드_그대로다() {
		Method eat = assertDoesNotThrow(
				() -> CakeBlock.class.getDeclaredMethod("eat",
						LevelAccessor.class, BlockPos.class, BlockState.class, Player.class),
				"CakeBlock.eat 이 사라졌거나 서명이 바뀌었다 — CakeBlockEatMixin 이 무는 자리다");

		assertTrue(Modifier.isStatic(eat.getModifiers()),
				"정적이 아니게 되면 @Redirect 처리기의 모양이 달라진다");
	}

	/**
	 * 시험은 fabric-loader-junit 위에서 돌아 믹스인이 실제로 입혀진다. 그래서 바닐라 {@code FoodData} 를
	 * 그대로 만들어 부르면 {@code FoodDataCapacityMixin} 이 붙었는지가 결과로 드러난다 — 안 붙었으면
	 * 57 이 20 으로 잘린다.
	 */
	@Test
	void 바닐라_eat_이_20_을_넘게_쌓인_허기를_깎지_않는다() {
		FoodData data = new FoodData();
		data.setFoodLevel(57);
		data.setSaturation(19.5F);

		data.eat(4, 0.3F);

		assertEquals(57, data.getFoodLevel(), "FoodDataCapacityMixin 이 FoodData.add 에 붙지 않았다");
		assertEquals(20.0F, data.getSaturationLevel(), 1.0e-6F, "포만감은 허기가 57 이어도 20 이 끝이다");
	}

	@Test
	void 증강이_없는_바닐라_eat_은_예전과_같다() {
		FoodData data = new FoodData();
		data.setFoodLevel(18);
		data.setSaturation(1.0F);

		data.eat(8, 0.8F);

		assertEquals(20, data.getFoodLevel());
		assertEquals(13.8F, data.getSaturationLevel(), 1.0e-5F, "1 에 8 × 0.8 × 2 = 12.8 을 더한다");
	}

	@Test
	void 케이크가_부르는_바닐라_eat_이_그대로_있다() {
		assertDoesNotThrow(() -> FoodData.class.getDeclaredMethod("eat", int.class, float.class));
	}
}
