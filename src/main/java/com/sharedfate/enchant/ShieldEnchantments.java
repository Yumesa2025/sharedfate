package com.sharedfate.enchant;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import java.util.ArrayList;
import java.util.List;

/** 등급은 실제 경험치 요구량이 아니라 마법부여 후보를 정하는 강도다. */
public final class ShieldEnchantments {
	private static final java.util.Map<com.sharedfate.team.TeamState, Integer> TEAM_HEALTH = new java.util.WeakHashMap<>();
	public record Candidate(String id, int maximum, boolean protection) { }
	private static final List<Candidate> BASIC = List.of(
			new Candidate("minecraft:unbreaking", 3, false), new Candidate("minecraft:thorns", 2, false));
	private static final List<Candidate> ADVANCED = List.of(
			new Candidate("sharedfate:shield_health", 6, false), new Candidate("sharedfate:shield_armor", 4, false),
			new Candidate("minecraft:protection", 4, true), new Candidate("minecraft:fire_protection", 4, true),
			new Candidate("minecraft:projectile_protection", 4, true), new Candidate("minecraft:blast_protection", 4, true));
	private ShieldEnchantments() { }

	public static List<Candidate> candidates(int grade) {
		if (grade < 15) return List.of();
		var result = new ArrayList<>(BASIC);
		if (grade >= 22) result.addAll(ADVANCED);
		return List.copyOf(result);
	}

	public static List<Candidate> choose(int grade, RandomSource random) {
		var pool = new ArrayList<>(candidates(grade));
		var selected = new ArrayList<Candidate>();
		while (!pool.isEmpty()) {
			Candidate next = pool.remove(random.nextInt(pool.size()));
			selected.add(next);
			if (next.protection()) pool.removeIf(Candidate::protection);
			if (random.nextFloat() >= 0.55F) break;
		}
		return selected;
	}

	public static List<EnchantmentInstance> select(RegistryAccess registries, int grade, long seed) {
		RandomSource random = RandomSource.create(seed);
		var registry = registries.lookupOrThrow(Registries.ENCHANTMENT);
		var result = new ArrayList<EnchantmentInstance>();
		for (Candidate candidate : choose(grade, random)) {
			registry.get(Identifier.parse(candidate.id())).ifPresent(holder -> result.add(
					new EnchantmentInstance(holder, 1 + random.nextInt(candidate.maximum()))));
		}
		return result;
	}

	/** 갑옷용 보호·가시만 방패의 양손 슬롯에서도 집행한다. 다른 아이템은 바닐라 그대로다. */
	public static boolean allowsHand(Enchantment enchantment, EquipmentSlot slot,
			ItemStack stack, LivingEntity wearer) {
		if (!stack.is(Items.SHIELD) || (slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND)) return false;
		return stack.getEnchantments().keySet().stream().filter(holder -> holder.value() == enchantment)
				.flatMap(holder -> holder.unwrapKey().stream()).anyMatch(key -> {
					String id = key.identifier().toString();
					return id.equals("minecraft:thorns") || ADVANCED.stream().anyMatch(
							candidate -> candidate.protection() && candidate.id().equals(id));
				});
	}

	public static int health(ItemStack stack, RegistryAccess registries) {
		if (!stack.is(Items.SHIELD)) return 0;
		return registries.lookupOrThrow(Registries.ENCHANTMENT).get(SharedFateMod.id("shield_health"))
				.map(holder -> Math.min(6, EnchantmentHelper.getItemEnchantmentLevel(holder, stack))).orElse(0);
	}

	/** 공유 상한에는 양손·여러 팀원의 합 대신 가장 큰 보너스 하나만 더한다. */
	public static void refreshTeamHealth(net.minecraft.server.MinecraftServer server,
			com.sharedfate.team.ShareTeam team, com.sharedfate.team.TeamState state) {
		int bonus = 0;
		for (var member : team.members()) {
			var player = server.getPlayerList().getPlayer(member);
			if (player != null) bonus = Math.max(bonus, Math.max(
					health(player.getMainHandItem(), server.registryAccess()),
					health(player.getOffhandItem(), server.registryAccess())));
		}
		rememberTeamHealth(state, bonus);
	}

	static void rememberTeamHealth(com.sharedfate.team.TeamState state, int... bonuses) {
		TEAM_HEALTH.put(state, java.util.Arrays.stream(bonuses).max().orElse(0));
	}

	public static int teamHealth(com.sharedfate.team.TeamState state) { return TEAM_HEALTH.getOrDefault(state, 0); }
	public static void reset() { TEAM_HEALTH.clear(); }
}
