package com.sharedfate.perk;

import com.sharedfate.perk.effect.VillagerTradeEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 주민 거래 효과({@code villager_trade})의 집행부. 증강 「흥정의 달인」이 여기로 들어온다.
 *
 * <p>{@link VillagerTradeEffect} 가 「얼마인가」만 들고, 실제로 바꾸는 자리는 mixin 셋이다.
 * 이 클래스는 그 사이에서 <b>지금 얼마인가</b>에 답하고, 값 깎기의 셈만 직접 한다.
 *
 * <h2>어느 팀의 값을 쓰는가</h2>
 * <p>주민은 어느 팀 것도 아니다. 그래서 자리마다 다르다.
 *
 * <ul>
 *   <li><b>값 깎기 · 경험치</b> — 그 자리에 <b>사람이 있다.</b> 거래는 언제나 누군가와
 *       하는 것이고 {@code Villager} 가 그 사람을 들고 있으므로, 그 사람의 팀 값을 쓴다.
 *       가장 정확하고 설명하기도 쉽다.</li>
 *   <li><b>재입고</b> — 그 자리에 <b>사람이 없다.</b> {@code Villager.shouldRestock} 은 주민이
 *       제 할 일을 하러 갈지 정하는 자리라 누구를 위한 재입고인지가 없다. 그래서
 *       {@link PerkSmelting} 이 화로에 쓰는 것과 같은 규칙을 쓴다 — 모든 팀을 훑어 가장 빠른
 *       값 하나. {@code singleTeamOnly} 를 켜 둔 서버는 팀이 하나뿐이라 차이가 없다.</li>
 * </ul>
 *
 * <h2>값 깎기는 바닐라 「영웅의 증표」와 <b>같은 통로</b>로 들어간다</h2>
 * <p>26.3 의 {@code Villager.updateSpecialPrices(Player)} 는 이렇게 생겼다.
 *
 * <pre>{@code
 * resetSpecialPrices();
 * int reputation = getPlayerReputation(player);
 * double hero = 영웅의 증표가 있으면 0.3 + 0.0625 * amplifier, 없으면 0;
 * for (MerchantOffer offer : getOffers()) {
 *     if (reputation != 0) offer.addToSpecialPriceDiff(-Mth.floor(reputation * offer.getPriceMultiplier()));
 *     if (hero > 0) offer.addToSpecialPriceDiff(-Math.max((int) Math.floor(hero * offer.getBaseCostA().getCount()), 1));
 * }
 * // 그 뒤에 바뀐 목록을 클라이언트로 다시 보낸다
 * }</pre>
 *
 * <p>둘 다 {@code addToSpecialPriceDiff} 하나로 들어가고, 그 값은 <b>더해진다.</b> 우리도 같은
 * 메서드를 쓰므로 셋이 나란히 더해진다 — 곱해지지 않는다. 평판 최대에 영웅의 증표 V 를 쓴
 * 사람에게 흥정의 달인까지 얹히면 값이 더 내려가긴 하지만,
 * {@code MerchantOffer.getCostA} 가 최종 개수를 <b>1 아래로는 안 내려가게</b> 자르므로 공짜가
 * 되지는 않는다. 바닐라가 이미 영웅의 증표 하나로 대부분의 거래를 1 까지 밀어붙일 수 있으니,
 * 겹쳐도 새로 생기는 상태는 없다.
 *
 * <p>영웅의 증표와 <b>같은 셈</b>을 쓴다(첫 재료 원가에 비율을 곱하고 내림, 최소 1). 다른 셈을
 * 쓰면 「15% 인데 왜 하나도 안 깎이지」 같은 자리가 생긴다 — 원가가 6 이면 0.15 × 6 = 0.9 라
 * 내림해서 0 이 되기 때문이다. 최소 1 을 두는 것이 그 자리의 답이고, 바닐라가 이미 그렇게
 * 정해 두었다.
 */
public final class PerkVillagerTrades {

	private PerkVillagerTrades() {
	}

	// ------------------------------------------------------------------ 조회

	/**
	 * 이 사람이 받는 값 깎기 비율. 해당 없으면 {@code 0.0}.
	 *
	 * <p>효과가 여럿이면 <b>더한다.</b> 값 자체가 비율이라 곱하면 「15% + 15% = 27.75%」 같은
	 * 설명할 수 없는 수가 된다. 더한 뒤 상한으로 자른다.
	 */
	public static double priceDiscount(@Nullable Player player) {
		double total = 0.0;
		for (VillagerTradeEffect effect : effectsOf(stateOf(player))) {
			total += effect.priceDiscount();
		}
		return Math.min(VillagerTradeEffect.MAX_PRICE_DISCOUNT, total);
	}

	/**
	 * 이 사람이 거래로 얻는 경험치에 곱할 값. 해당 없으면 {@code 1.0}.
	 *
	 * <p>효과가 여럿이면 곱한다. {@code experience_bonus} 와 같은 규칙이다.
	 */
	public static double experienceMultiplier(@Nullable Player player) {
		double multiplier = 1.0;
		for (VillagerTradeEffect effect : effectsOf(stateOf(player))) {
			multiplier *= effect.experienceMultiplier();
		}
		return multiplier;
	}

	/**
	 * 이 서버에서 재입고를 몇 배 빨리 하는가. 해당 없으면 {@code 1.0}.
	 *
	 * <p>팀이 여럿이면 <b>가장 빠른</b> 값 하나를 쓴다. 곱하지 않는다 — 팀 수가 늘 때마다
	 * 재입고가 빨라지면 팀을 여러 개 만드는 것이 이득이 된다.
	 */
	public static double restockSpeed(@Nullable MinecraftServer server) {
		if (server == null) {
			return 1.0;
		}
		TeamManager manager = TeamManager.get(server);
		double best = 1.0;
		for (ShareTeam team : List.copyOf(manager.allTeams())) {
			double value = restockSpeed(manager.stateByTeamId(team.teamId()));
			if (value > best) {
				best = value;
			}
		}
		return best;
	}

	/** 한 팀의 재입고 배율. 효과가 여럿이면 곱한다. */
	public static double restockSpeed(@Nullable TeamState state) {
		double multiplier = 1.0;
		for (VillagerTradeEffect effect : effectsOf(state)) {
			multiplier *= effect.restockSpeed();
		}
		return multiplier;
	}

	/**
	 * 바닐라가 정한 대기 틱을 이 배율만큼 줄인다. 최소 1틱은 남긴다.
	 *
	 * <p>0 을 돌려주면 {@code gameTime > lastRestockGameTime + 0} 이 사실상 언제나 참이 되어
	 * 주민이 틱마다 재입고를 시도한다. 그것은 「빠르다」가 아니라 「제한이 사라졌다」다.
	 */
	public static long reducedRestockTicks(long ticks, double speed) {
		if (ticks <= 0L || !Double.isFinite(speed) || speed <= 0.0 || speed == 1.0) {
			return ticks;
		}
		return Math.max(1L, (long) Math.ceil(ticks / speed));
	}

	// ------------------------------------------------------------------ 값 깎기

	/**
	 * 주민이 내건 값들을 깎는다. 실제로 깎은 거래 수를 돌려준다.
	 *
	 * <p>{@code VillagerTradeMixin} 이 {@code updateSpecialPrices} 의 반복문이 끝난 <b>바로
	 * 뒤</b>, 바뀐 목록을 클라이언트로 다시 보내기 <b>전</b>에 부른다. 순서가 그래야 우리가 깎은
	 * 값이 같은 패킷에 실려 나간다. 뒤에 부르면 거래 창에는 옛 값이 뜨고, 사 보고 나서야 실제
	 * 값이 다르다는 것을 알게 된다.
	 *
	 * <p><b>{@code resetSpecialPrices} 뒤여야 한다.</b> 바닐라가 반복문 앞에서 특별가를 0 으로
	 * 되돌리므로, 그 앞에서 깎으면 우리 몫만 지워진다.
	 */
	public static int applyDiscount(@Nullable MerchantOffers offers, @Nullable Player player) {
		if (offers == null || offers.isEmpty()) {
			return 0;
		}
		double discount = priceDiscount(player);
		if (discount <= 0.0) {
			return 0;
		}
		int changed = 0;
		for (MerchantOffer offer : offers) {
			int amount = discountFor(offer, discount);
			if (amount > 0) {
				offer.addToSpecialPriceDiff(-amount);
				changed++;
			}
		}
		return changed;
	}

	/**
	 * 이 거래에서 깎을 개수. 영웅의 증표와 같은 셈이다 — 첫 재료 원가에 비율을 곱해 내림하고,
	 * 0 이 되면 1 로 올린다.
	 *
	 * <p>원가가 0 이하인(있을 수 없지만 정의가 이상하면 나올 수 있는) 거래는 건드리지 않는다.
	 */
	public static int discountFor(@Nullable MerchantOffer offer, double discount) {
		if (offer == null || discount <= 0.0 || !Double.isFinite(discount)) {
			return 0;
		}
		ItemStack base = offer.getBaseCostA();
		if (base == null || base.isEmpty() || base.getCount() <= 0) {
			return 0;
		}
		return Math.max((int) Math.floor(discount * base.getCount()), 1);
	}

	// ------------------------------------------------------------------ 모으기

	private static @Nullable TeamState stateOf(@Nullable Player player) {
		return player == null ? null : TeamLookup.stateOf(player.getUUID());
	}

	/**
	 * 이 팀이 가진 주민 거래 효과 전부. 없으면 빈 목록.
	 *
	 * <p>보유 증강과 켜진 세트 단계를 둘 다 훑는다. 지금은 증강 쪽에만 있지만, 세트 쪽을 빼
	 * 두면 나중에 세트 보상이 같은 효과를 갖게 됐을 때 <b>빌드도 로그도 통과하는데 그 단계만
	 * 조용히 아무 일도 안 한다</b>({@link PerkSetEffects} 머리말).
	 */
	public static List<VillagerTradeEffect> effectsOf(@Nullable TeamState state) {
		TeamState active = PerkWorldRules.activeState(state);
		if (active == null) {
			return List.of();
		}
		List<VillagerTradeEffect> found = new java.util.ArrayList<>();
		for (String perkId : active.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof VillagerTradeEffect trade) {
					found.add(trade);
				}
			}
		}
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(active)) {
			if (effect instanceof VillagerTradeEffect trade) {
				found.add(trade);
			}
		}
		return found;
	}
}
