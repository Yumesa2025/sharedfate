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
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import java.util.ArrayList;
import java.util.List;

/**
 * 마법부여대에서 방패에 붙는 마법부여.
 *
 * <p>인챈트 탁자는 책장 6개 이상이면 언제나 등급 30 이므로({@link EnchantmentTableRules}), 방패도
 * 등급을 나누지 않고 <b>후보 전체</b>에서 뽑는다. 보호 계열은 서로 배타적이다.
 */
public final class ShieldEnchantments {
	private static final java.util.Map<com.sharedfate.team.TeamState, Integer> TEAM_HEALTH = new java.util.WeakHashMap<>();
	public record Candidate(String id, int maximum, boolean protection) { }
	private static final List<Candidate> POOL = List.of(
			new Candidate("minecraft:unbreaking", 3, false), new Candidate("minecraft:thorns", 2, false),
			new Candidate("sharedfate:shield_health", 6, false), new Candidate("sharedfate:shield_armor", 4, false),
			new Candidate("minecraft:protection", 4, true), new Candidate("minecraft:fire_protection", 4, true),
			new Candidate("minecraft:projectile_protection", 4, true), new Candidate("minecraft:blast_protection", 4, true));
	private ShieldEnchantments() { }

	/** 방패에 붙을 수 있는 마법부여 전체. */
	public static List<Candidate> candidates() {
		return POOL;
	}

	/**
	 * 마법부여대가 이 방패를 받아 줄지.
	 *
	 * <p><b>팀의 해금 여부는 서버만 안다.</b> 클라이언트의 {@code TeamLookup} 은 전용 서버에 붙으면
	 * 늘 비어 있으므로, 클라이언트에서 해금을 물으면 언제나 「잠김」이 나온다. 그런데 바닐라
	 * {@code slotsChanged} 는 클라이언트에서도 돌아서, 「받지 않는다」가 나오면 서버가 보내 준
	 * 비용({@code costs})을 그 자리에서 0 으로 지운다. 청금석을 올리는 순간 단추가 사라지는 까닭이다.
	 * 서버는 비용이 바뀌지 않았으니 다시 보내 주지도 않는다.
	 *
	 * <p>그래서 클라이언트는 판정하지 않고 받아 준다. 클라이언트의 접근자는 {@code NULL} 이라
	 * 비용을 새로 계산하지도 않으므로, 화면에는 서버가 정한 값이 그대로 남는다.
	 */
	public static boolean tableAccepts(ItemStack shield, boolean serverSide, boolean unlocked) {
		if (shield.isEnchanted()) return false;
		return !serverSide || unlocked;
	}

	public static List<Candidate> choose(RandomSource random) {
		var pool = new ArrayList<>(POOL);
		var selected = new ArrayList<Candidate>();
		while (!pool.isEmpty()) {
			Candidate next = pool.remove(random.nextInt(pool.size()));
			selected.add(next);
			if (next.protection()) pool.removeIf(Candidate::protection);
			if (random.nextFloat() >= 0.55F) break;
		}
		return selected;
	}

	/**
	 * 칸의 씨앗으로 만든 난수.
	 *
	 * <p>세 칸의 씨앗은 {@code enchantmentSeed + 0·1·2} 로 <b>붙어 있다.</b> 바닐라의 선형 합동 난수는
	 * 이웃한 씨앗의 첫 값이 거의 같아서, 후보 여덟 개 중 하나를 고르는 첫 뽑기({@code nextInt(8)})가
	 * 세 칸에서 사실상 늘 같아진다. 예전에는 칸마다 등급이 달라 후보 수가 달랐으므로 드러나지 않았지만,
	 * 이제 세 칸이 모두 같은 후보에서 뽑으므로 씨앗을 고루 섞는 난수를 쓴다.
	 */
	public static RandomSource randomFor(long seed) {
		return new XoroshiroRandomSource(seed);
	}

	/** 칸마다 다른 씨앗({@code enchantmentSeed + 칸})을 받으므로 세 칸은 따로 뽑힌다. */
	public static List<EnchantmentInstance> select(RegistryAccess registries, long seed) {
		RandomSource random = randomFor(seed);
		var registry = registries.lookupOrThrow(Registries.ENCHANTMENT);
		var result = new ArrayList<EnchantmentInstance>();
		for (Candidate candidate : choose(random)) {
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
					return id.equals("minecraft:thorns") || POOL.stream().anyMatch(
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
