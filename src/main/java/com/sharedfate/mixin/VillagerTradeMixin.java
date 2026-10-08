package com.sharedfate.mixin;

import com.sharedfate.perk.PerkVillagerTrades;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 증강 「흥정의 달인」({@code villager_trade})이 주민에게 끼어드는 세 자리.
 *
 * <p>규칙과 계산은 {@link PerkVillagerTrades} 에 있다. 여기는 「어디서 바꾸는가」만 정한다.
 *
 * <h2>대상을 어떻게 확인했는가</h2>
 * <p>{@code sharedfate.mixins.json} 에는 refmap 이 없어 <b>대상 서술자가 틀려도 빌드는
 * 통과한다.</b> 그래서 26.3 바이트코드를 직접 읽어 확인했다. 26.3 에서 주민 클래스가
 * {@code net.minecraft.world.entity.npc.Villager} 에서
 * <b>{@code net.minecraft.world.entity.npc.villager.Villager}</b> 로 옮겨졌다 —
 * {@code npc} 패키지에는 이제 {@code CatSpawner}·{@code ClientSideMerchant}·
 * {@code InventoryCarrier}·{@code Npc} 넷뿐이다.
 *
 * <pre>{@code
 * javap -p -c net/minecraft/world/entity/npc/villager/Villager.class
 *
 * private void updateSpecialPrices(Player);
 *      1: invokevirtual  // resetSpecialPrices()V        ← 특별가를 0 으로 되돌린다
 *      6: invokevirtual  // getPlayerReputation(Player)I
 *     14: invokevirtual  // Player.getEffect(HERO_OF_THE_VILLAGE)
 *     26: ldc_w 0.3f / 0.0625f …                          ← 영웅의 증표 할인율
 *     48~135: for (offer : getOffers())
 *               offer.addToSpecialPriceDiff(-floor(reputation * priceMultiplier))
 *               offer.addToSpecialPriceDiff(-max(floor(hero * baseCostA.count), 1))
 *    139: invokevirtual  // getTradingPlayer()            ← ★ 여기에 끼어든다
 *    …    거래 중이면 MerchantMenu.updateSellItem() + 목록을 클라이언트로 다시 보냄
 *
 * private boolean allowedToRestock();
 *     26: ldc2_w 2400l                                    ← 연속 재입고 사이 최소 간격
 *
 * public boolean shouldRestock(ServerLevel);
 *      4: ldc2_w 12000l                                   ← 하루치 재입고 횟수를 되돌리는 간격
 *
 * protected void rewardTradeXp(MerchantOffer);
 *      0: iconst_3 / random.nextInt(4) / iadd → istore_2  ← 3~6
 *     85: iinc 2, 5                                       ← 직급이 올랐으면 +5
 *    …   new ExperienceOrb(level, x, y+0.5, z, i)         ← ★ 여기 인자 4번을 바꾼다
 * }</pre>
 *
 * <h2>왜 {@code updateSpecialPrices} 의 <b>한가운데</b>인가</h2>
 * <p>{@code TAIL} 이 아니다. 저 메서드는 끝에서 <b>바뀐 거래 목록을 클라이언트로 다시
 * 보낸다.</b> 뒤에 끼어들면 거래 창에는 옛 값이 떠 있고, 사 보고 나서야 실제로 깎였다는 것을
 * 알게 된다. {@code getTradingPlayer()} 호출 직전은 바닐라 반복문이 끝난 뒤이자 되보내기
 * 전이라, 우리가 깎은 값이 같은 패킷에 실려 나간다.
 *
 * <p>{@code resetSpecialPrices()} 보다 <b>뒤</b>인 것도 중요하다. 앞에서 깎으면 그 한 줄이
 * 우리 몫을 지운다.
 *
 * <h2>바닐라 할인과 겹칠 때</h2>
 * <p>평판·영웅의 증표와 <b>같은 통로</b>({@code addToSpecialPriceDiff})를 쓰므로 셋이 더해진다.
 * 자세한 것은 {@link PerkVillagerTrades} 의 머리말에 적어 두었다.
 *
 * <h2>떠돌이 상인은 걸리지 않는다</h2>
 * <p>{@code WanderingTrader} 도 {@code AbstractVillager} 를 물려받지만 {@code rewardTradeXp}
 * 를 따로 구현하고, 특별가·재입고 자체가 없다. 그쪽은 건드리지 않는다 — 「주민 거래」라고
 * 적힌 증강이 떠돌이 상인까지 손보면 설명과 다르다.
 *
 * <h2>재정의되는 메서드가 아닌가</h2>
 * <p>{@code rewardTradeXp} 는 {@code AbstractVillager} 에서 추상이고 {@code Villager} 가
 * 구현한다. 우리가 잡는 것은 <b>그 구현</b>이라 조용히 죽지 않는다. 나머지 둘은
 * {@code Villager} 에만 있고 물려받는 클래스가 없다.
 */
@Mixin(Villager.class)
public abstract class VillagerTradeMixin {

	/** 거래 값을 깎는다. 바닐라 평판·영웅의 증표와 같은 통로라 더해진다. */
	@Inject(
			method = "updateSpecialPrices(Lnet/minecraft/world/entity/player/Player;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/npc/villager/Villager;"
							+ "getTradingPlayer()Lnet/minecraft/world/entity/player/Player;"))
	private void sharedfate$discountOffers(Player player, CallbackInfo info) {
		Villager self = (Villager) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}
		PerkVillagerTrades.applyDiscount(self.getOffers(), player);
	}

	/**
	 * 연속 재입고 사이의 최소 간격(2분)을 줄인다.
	 *
	 * <p>하루에 두 번이라는 바닐라 상한({@code numberOfRestocksToday < 2})은 그대로 둔다.
	 * 「재입고 시간 단축」은 기다리는 시간에 대한 말이지 횟수에 대한 말이 아니다.
	 */
	@ModifyConstant(method = "allowedToRestock()Z", constant = @Constant(longValue = 2400L))
	private long sharedfate$shortenRestockGap(long ticks) {
		return reduced(ticks);
	}

	/** 하루치 재입고 횟수를 되돌리는 간격(10분)을 줄인다. */
	@ModifyConstant(
			method = "shouldRestock(Lnet/minecraft/server/level/ServerLevel;)Z",
			constant = @Constant(longValue = 12000L))
	private long sharedfate$shortenRestockDay(long ticks) {
		return reduced(ticks);
	}

	/**
	 * 거래로 나오는 경험치 오브의 크기를 바꾼다.
	 *
	 * <p>{@code ExperienceOrbAwardMixin} 은 여기에 걸리지 않는다. 그쪽은
	 * {@code ExperienceOrb.awardWithDirection} 을 잡는데, {@code rewardTradeXp} 는 그 정적
	 * 메서드를 거치지 않고 <b>오브를 직접 만든다.</b> 그래서 여기서 따로 잡아야 한다.
	 */
	@ModifyArg(
			method = "rewardTradeXp(Lnet/minecraft/world/item/trading/MerchantOffer;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/entity/ExperienceOrb;<init>("
							+ "Lnet/minecraft/world/level/Level;DDDI)V"),
			index = 4)
	private int sharedfate$scaleTradeExperience(int amount) {
		Villager self = (Villager) (Object) this;
		if (self.level().isClientSide()) {
			return amount;
		}
		double multiplier = PerkVillagerTrades.experienceMultiplier(self.getTradingPlayer());
		if (multiplier == 1.0) {
			return amount;
		}
		// 0 이 되면 오브가 「경험치 0 짜리」로 떠서 주워도 아무 일도 안 난다. 1 은 남긴다 —
		// 경험치가 절반이 된다는 말이지 사라진다는 말이 아니다.
		return Math.max(1, (int) Math.round(amount * multiplier));
	}

	/** 두 재입고 상수에 같은 규칙을 먹인다. */
	private long reduced(long ticks) {
		Villager self = (Villager) (Object) this;
		if (self.level().isClientSide()) {
			return ticks;
		}
		return PerkVillagerTrades.reducedRestockTicks(ticks,
				PerkVillagerTrades.restockSpeed(self.level().getServer()));
	}
}
