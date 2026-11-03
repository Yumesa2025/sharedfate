package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import static org.junit.jupiter.api.Assertions.*;

/** 실제 26.3 바이트코드에 믹스인이 집는 호출이 정확히 하나 있는지 확인한다. */
class ShieldCombatTargetTest {
	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }
	private MethodNode method(Class<?> owner, String name) throws Exception {
		try (var stream = owner.getResourceAsStream("/" + owner.getName().replace('.', '/') + ".class")) {
			assertNotNull(stream);
			ClassNode node = new ClassNode();
			new ClassReader(stream).accept(node, 0);
			return node.methods.stream().filter(m -> m.name.equals(name)).findFirst().orElseThrow();
		}
	}
	private long calls(MethodNode method, String owner, String name, String descriptor) {
		long count = 0;
		for (var instruction : method.instructions) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(owner)
					&& call.name.equals(name) && call.desc.equals(descriptor)) count++;
		}
		return count;
	}
	@Test void 거북은_바닐라_방패_판정의_각도만_바꾸는_지점을_쓴다() throws Exception {
		MethodNode blocking = method(LivingEntity.class, "applyItemBlocking");
		assertEquals(1, calls(blocking, "net/minecraft/world/item/component/BlocksAttacks", "resolveBlockedDamage",
				"(Lnet/minecraft/world/damagesource/DamageSource;FD)F"));
		assertEquals(1, calls(blocking, "net/minecraft/world/item/component/BlocksAttacks", "hurtBlockingItem",
				"(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/InteractionHand;F)V"));
	}
	@Test void 화살_반동_직전과_몹_AI_진입점이_있다() throws Exception {
		assertEquals(1, calls(method(AbstractArrow.class, "onHitEntity"),
				"net/minecraft/world/entity/projectile/arrow/AbstractArrow", "deflect",
				"(Lnet/minecraft/world/entity/projectile/ProjectileDeflection;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/EntityReference;ZD)Z"));
		assertEquals("()V", method(Mob.class, "serverAiStep").desc);
	}
}
