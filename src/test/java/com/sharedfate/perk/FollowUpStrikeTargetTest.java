package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;

/** 무기 3단계 「한 번 더」가 누구에게, 어떤 표시를 세우고 두 번째 타격을 넣는가. */
class FollowUpStrikeTargetTest {
	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }

	@Test void 갑옷_거치대와_플레이어에게는_한_번_더_치지_않는다() throws Exception {
		assertTrue(PerkFollowUpStrike.eligibleTarget(empty(Zombie.class)));
		// 거치대는 5틱 안에 두 번 맞으면 부서지는데 두 번째 타격이 4틱 뒤다.
		assertFalse(PerkFollowUpStrike.eligibleTarget(empty(ArmorStand.class)));
		assertFalse(PerkFollowUpStrike.eligibleTarget(empty(ServerPlayer.class)));
		assertFalse(PerkFollowUpStrike.eligibleTarget(null));
	}

	/**
	 * 두 번째 타격의 {@code hurtServer} 는 {@link PerkShieldCombat#followUpHit} 안에서 불려야 방패 반격을
	 * 소모하지 않는다. 서버 없이 strike 를 돌릴 수 없으므로 클래스 파일로 붙든다 — strike 가 followUpHit 를
	 * 부르고, hurtServer 는 strike 본문에 직접 있지 않고 람다 안에만 있다.
	 */
	@Test void 두_번째_타격은_반격_표시_안에서_넣는다() throws Exception {
		ClassNode node = new ClassNode();
		try (var in = PerkFollowUpStrike.class.getResourceAsStream(
				"/" + PerkFollowUpStrike.class.getName().replace('.', '/') + ".class")) {
			assertNotNull(in);
			new ClassReader(in).accept(node, 0);
		}
		MethodNode strike = node.methods.stream().filter(m -> m.name.equals("strike")).findFirst().orElseThrow();
		boolean wraps = false, direct = false, lambda = false;
		for (var insn : strike.instructions) {
			if (insn instanceof MethodInsnNode call) {
				if (call.owner.equals("com/sharedfate/perk/PerkShieldCombat") && call.name.equals("followUpHit")) wraps = true;
				if (call.name.equals("hurtServer")) direct = true;
			}
			if (insn instanceof InvokeDynamicInsnNode) lambda = true;
		}
		assertTrue(wraps, "두 번째 타격이 반격 표시를 세우지 않는다");
		assertTrue(lambda);
		assertFalse(direct, "hurtServer 를 표시 밖에서 바로 부른다");
	}

	@SuppressWarnings("unchecked")
	private static <T extends Entity> T empty(Class<T> type) throws Exception {
		var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
		return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
	}
}
