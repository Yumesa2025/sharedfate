package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.RallyShardEffect;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RunRallyShardTest {
	@BeforeAll
	static void bootstrap() { TestBootstrap.ensureInitialized(); }

	@Test
	void 일회용과_재사용은_표식과_이름과_쿨타임이_다르다() {
		ItemStack once = PerkRallyShard.createSingleUseItem();
		ItemStack reusable = RallyShardEffect.decorate(new ItemStack(Items.ECHO_SHARD), 240);
		assertTrue(PerkRallyShard.isSingleUseItem(once));
		assertFalse(RallyShardEffect.isRallyShard(once));
		assertEquals("소집의 조각 (1회용)", once.getHoverName().getString());
		assertNull(once.get(DataComponents.USE_COOLDOWN));
		assertFalse(PerkRallyShard.isSingleUseItem(reusable));
		assertTrue(RallyShardEffect.isRallyShard(reusable));
		assertNotNull(reusable.get(DataComponents.USE_COOLDOWN));
	}
}
