package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.WeaponDamageEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 손에 든 무기에 따라 {@code minecraft:attack_damage} 속성 수정자를 갈아 끼우는 계산기.
 *
 * <p>{@code weapon_damage} 와 {@code item_ban} 의 공격력 부분을 여기서 함께 처리한다. 둘 다
 * "지금 든 것이 무엇이냐"에 따라 근접 공격력을 바꾸는 규칙이고, 한 칸에 수정자 하나만 붙일 수
 * 있으므로 결론도 한 곳에서 내야 한다.
 *
 * <p>{@code Player.attack} 은 {@code getAttributeValue(ATTACK_DAMAGE)} 로 기본 피해를 읽는다.
 * 그 값을 바꾸면 근접 공격에만 반영되고 화살·물약처럼 무기와 상관없는 피해는 건드리지 않는다.
 *
 * <h2>수정자를 어떻게 만드는가</h2>
 * <ul>
 *   <li><b>우대 무기</b>(예: 삽) — {@code ADD_MULTIPLIED_TOTAL} 로 배수를 건다. 계산 순서상
 *       맨 마지막에 곱해지므로 다른 증강이 공격력을 얼마로 만들어 놨든 그 결과에 곱한다.</li>
 *   <li><b>그 밖의 무기</b> — {@code ADD_VALUE} 로 "기본값 + 무기가 얹은 값"을 지정한 공격력으로
 *       끌어내린다. 무기가 얹는 값은 아이템 자신이 갖고 있으므로 플레이어 상태를 읽지 않아도
 *       구할 수 있다.</li>
 *   <li><b>무력해진 무기</b>({@code item_ban}) — {@code ADD_VALUE} 로 그 무기가 얹은 값만 정확히
 *       상쇄한다. 결과는 맨손으로 때린 것과 같다.</li>
 * </ul>
 *
 * <p>공격력을 얹지 않는 물건(맨손, 흙덩이 등)은 어느 경우에도 건드리지 않는다.
 */
public final class PerkWeaponDamage {
	/** 이 계산기가 관리하는 유일한 수정자. 증강마다 나누지 않고 하나만 쓴다. */
	public static final Identifier MODIFIER_ID = SharedFateMod.id("perk/weapon_damage");

	/** 수정자 값의 상한. 무한대·NaN 이 속성 계산으로 새어나가지 않게 막는다. */
	private static final double MAX_ABS_AMOUNT = 4096.0;

	/** 같은 경고로 로그를 채우지 않기 위한 표시. */
	private static volatile boolean warned;

	private PerkWeaponDamage() {
	}

	// ------------------------------------------------------------------ 적용

	/**
	 * 이 플레이어의 공격력 수정자를 지금 상태에 맞춘다.
	 *
	 * <p>붙일 것이 없으면 걷어낸다. <b>{@code state} 가 null 이어도 반드시 부를 수 있어야 한다.</b>
	 * 증강을 잃거나 팀에서 빠졌을 때 남아 있던 수정자를 떼는 길이 이 경로뿐이기 때문이다.
	 */
	public static void refresh(@Nullable ServerPlayer player, @Nullable TeamState state) {
		if (player == null) {
			return;
		}
		try {
			AttributeInstance attribute = player.getAttribute(Attributes.ATTACK_DAMAGE);
			if (attribute == null) {
				return;
			}
			AttributeModifier desired =
					desired(state, player.getMainHandItem(), attribute.getBaseValue());
			AttributeModifier current = attribute.getModifier(MODIFIER_ID);
			if (desired == null) {
				if (current != null) {
					attribute.removeModifier(MODIFIER_ID);
				}
				return;
			}
			// AttributeModifier 는 record 라 값이 같으면 같다. 손에 든 것이 그대로면 여기서 끝나고
			// 클라이언트로 속성 갱신 꾸러미가 나가지 않는다.
			if (desired.equals(current)) {
				return;
			}
			if (current != null) {
				attribute.removeModifier(MODIFIER_ID);
			}
			attribute.addTransientModifier(desired);
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/** 남아 있는 수정자를 무조건 걷어낸다. 팀에서 빠졌거나 증강을 잃었을 때 쓴다. */
	public static void clear(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		try {
			AttributeInstance attribute = player.getAttribute(Attributes.ATTACK_DAMAGE);
			if (attribute != null && attribute.getModifier(MODIFIER_ID) != null) {
				attribute.removeModifier(MODIFIER_ID);
			}
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	// ------------------------------------------------------------------ 계산

	/**
	 * 지금 붙어 있어야 할 수정자를 정한다.
	 *
	 * <p>플레이어를 읽지 않는 순수 계산이라 살아 있는 서버 없이 시험할 수 있다.
	 *
	 * @param state    팀 상태. null 이면 제한이 없다는 뜻이다
	 * @param mainHand 주 손에 든 묶음
	 * @param baseValue 플레이어의 기본 공격력. 바닐라는 1.0 이다
	 * @return 붙일 수정자. 건드릴 것이 없으면 null
	 */
	public static @Nullable AttributeModifier desired(@Nullable TeamState state,
			@Nullable ItemStack mainHand, double baseValue) {
		TeamState active = PerkGearRules.activeState(state);
		if (active == null || mainHand == null || mainHand.isEmpty()) {
			return null;
		}

		boolean banned = PerkGearRules.itemBanned(active, mainHand);
		WeaponDamageEffect rule = PerkGearRules.weaponRule(active);
		if (!banned && rule == null) {
			return null;
		}

		// 무력해진 무기가 먼저다. 우대 대상이기도 한 이상한 조합이면 막는 쪽이 이긴다.
		if (banned) {
			return cancelWeapon(mainHand);
		}
		if (rule.boosts(mainHand)) {
			return multiply(rule.multiplier());
		}
		Double others = rule.othersDamage();
		if (others == null) {
			return null;
		}
		return flatten(mainHand, baseValue, others);
	}

	/** 우대 무기에 배수를 건다. 배수가 1이면 붙일 것이 없다. */
	private static @Nullable AttributeModifier multiply(double multiplier) {
		double amount = multiplier - 1.0;
		if (amount == 0.0) {
			return null;
		}
		return modifier(amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
	}

	/** 무기가 얹은 공격력만 정확히 상쇄한다. 공격력을 얹지 않는 물건이면 붙일 것이 없다. */
	private static @Nullable AttributeModifier cancelWeapon(ItemStack stack) {
		AttackBonus bonus = attackBonus(stack);
		if (!bonus.present || bonus.flat == 0.0) {
			return null;
		}
		return modifier(-bonus.flat, AttributeModifier.Operation.ADD_VALUE);
	}

	/** 무기를 들었을 때의 공격력을 지정한 값으로 만든다. */
	private static @Nullable AttributeModifier flatten(ItemStack stack, double baseValue,
			double target) {
		AttackBonus bonus = attackBonus(stack);
		if (!bonus.present) {
			// 공격력을 얹지 않는 물건은 무기가 아니다. 맨손과 같으므로 손대지 않는다.
			return null;
		}
		double amount = target - baseValue - bonus.flat;
		if (amount == 0.0) {
			return null;
		}
		return modifier(amount, AttributeModifier.Operation.ADD_VALUE);
	}

	private static @Nullable AttributeModifier modifier(double amount,
			AttributeModifier.Operation operation) {
		if (!Double.isFinite(amount) || Math.abs(amount) > MAX_ABS_AMOUNT) {
			SharedFateMod.LOGGER.warn("증강의 무기 공격력 값이 범위를 벗어나 건너뜁니다: {}", amount);
			return null;
		}
		return new AttributeModifier(MODIFIER_ID, amount, operation);
	}

	// ------------------------------------------------------------------ 아이템이 얹는 공격력

	/** 아이템이 주 손에서 얹어 주는 공격력. */
	private record AttackBonus(boolean present, double flat) {
		static final AttackBonus NONE = new AttackBonus(false, 0.0);
	}

	/**
	 * 이 묶음이 주 손에서 공격력을 얹는지, 얹는다면 얼마인지 본다.
	 *
	 * <p>더하기({@code ADD_VALUE}) 수정자만 값으로 센다. 바닐라 무기·도구는 전부 더하기로 적혀
	 * 있고, 곱하기로 적힌 별난 아이템까지 정확히 상쇄하려면 플레이어의 속성 전체를 다시 계산해야
	 * 해서 얻는 것보다 잃는 것이 많다. 그런 아이템도 "무기이긴 하다"({@code present})는 알 수 있어
	 * 공격력을 지정하는 쪽({@link #flatten})은 정상 동작한다.
	 */
	private static AttackBonus attackBonus(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return AttackBonus.NONE;
		}
		Attribute attackDamage;
		try {
			attackDamage = Attributes.ATTACK_DAMAGE.value();
		} catch (RuntimeException error) {
			warnOnce(error);
			return AttackBonus.NONE;
		}

		boolean[] present = {false};
		double[] flat = {0.0};
		stack.forEachModifier(EquipmentSlot.MAINHAND, (holder, modifier) -> {
			if (!isSame(holder, attackDamage)) {
				return;
			}
			present[0] = true;
			if (modifier.operation() == AttributeModifier.Operation.ADD_VALUE) {
				flat[0] += modifier.amount();
			}
		});
		return present[0] ? new AttackBonus(true, flat[0]) : AttackBonus.NONE;
	}

	private static boolean isSame(@Nullable Holder<Attribute> holder, Attribute attackDamage) {
		try {
			return holder != null && holder.value() == attackDamage;
		} catch (RuntimeException error) {
			return false;
		}
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"증강의 무기 공격력을 맞추지 못했습니다. 이 경고는 한 번만 남습니다.", error);
	}

	/** 테스트가 경고 억제 상태를 되돌릴 때 쓴다. */
	static void resetWarnedForTesting() {
		warned = false;
	}
}
