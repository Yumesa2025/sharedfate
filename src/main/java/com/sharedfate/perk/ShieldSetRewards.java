package com.sharedfate.perk;

import com.mojang.serialization.Codec;
import com.sharedfate.SharedFateMod;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.perk.effect.ShieldCraftEffect;
import com.sharedfate.recipe.ShieldRecipes;
import com.sharedfate.team.TeamManager;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 같은 월드에서 세트를 잃었다 다시 얻거나 재접속해도 방패는 한 번만 준다. */
public final class ShieldSetRewards extends SavedData {
	private final Set<UUID> granted = new HashSet<>();
	public static final Codec<ShieldSetRewards> CODEC = UUIDUtil.STRING_CODEC.listOf().fieldOf("teams").codec().xmap(ids -> {
		ShieldSetRewards rewards = new ShieldSetRewards();
		rewards.granted.addAll(ids);
		return rewards;
	}, rewards -> List.copyOf(rewards.granted));
	private static final SavedDataType<ShieldSetRewards> TYPE = new SavedDataType<>(
			SharedFateMod.id("shield_set_rewards"), ShieldSetRewards::new, CODEC, null);

	public boolean grantOnce(UUID team, Runnable delivery) {
		if (granted.contains(team)) return false;
		delivery.run();
		granted.add(team);
		setDirty();
		return true;
	}

	public static void refresh(ServerPlayer player) {
		var server = player.level().getServer();
		TeamManager manager = TeamManager.get(server);
		var team = manager.teamOf(player.getUUID());
		var state = manager.stateOf(player.getUUID());
		if (team == null || !ShieldRecipes.unlocked(state, ShieldCraftEffect.FIRST_SHIELD)) return;
		if (server.getDataStorage().computeIfAbsent(TYPE).grantOnce(team.teamId(), () -> {
			state.overflowItems.add(new ItemStack(Items.SHIELD));
			state.restoreOverflow(ExpandedInventoryManager.enabled());
		})) manager.setDirty();
	}
}
