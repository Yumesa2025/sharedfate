package com.sharedfate.perk;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 「호위」가 방패로 막을 공격을 건너뛸 때 쓰는 각도.
 *
 * <p>바닐라 방패는 이 각도가 90° 안쪽이면 막는다. 머리 방향 0 은 남쪽(+Z)을 본다.
 */
class ShieldWouldBlockAngleTest {
	private static final Vec3 ORIGIN = new Vec3(0.0D, 64.0D, 0.0D);

	@Test
	void 정면에서_온_피해는_0도다() {
		assertEquals(0.0D, PerkDamage.blockAngle(new Vec3(0.0D, 65.0D, 5.0D), ORIGIN, 0.0F), 1.0e-3);
	}

	@Test
	void 등_뒤에서_온_피해는_180도다() {
		assertEquals(Math.PI, PerkDamage.blockAngle(new Vec3(0.0D, 64.0D, -5.0D), ORIGIN, 0.0F), 1.0e-3);
	}

	@Test
	void 옆에서_온_피해는_90도다() {
		assertEquals(Math.PI / 2.0D, PerkDamage.blockAngle(new Vec3(5.0D, 64.0D, 0.0D), ORIGIN, 0.0F), 1.0e-3);
	}

	@Test
	void 높이_차이는_보지_않는다() {
		assertEquals(0.0D, PerkDamage.blockAngle(new Vec3(0.0D, 90.0D, 5.0D), ORIGIN, 0.0F), 1.0e-3);
	}

	@Test
	void 출처가_없는_피해는_바닐라처럼_정반대로_본다() {
		assertEquals(Mth.PI, PerkDamage.blockAngle(null, ORIGIN, 0.0F), 1.0e-6);
	}
}
