package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.ConditionalPerkManager;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>웅크리는 동안만</b> 달리기 속도의 정해진 배수로 움직이게 한다.
 *
 * <pre>{@code
 * { "type": "sneak_speed", "sprint_multiplier": 1.5 }
 * }</pre>
 *
 * <p>{@code sprint_multiplier} 가 1.5 이면 웅크린 채 움직이는 속도가 <b>같은 순간의 달리기
 * 속도의 1.5배</b>가 된다. 서서 걷는 속도는 이 효과가 건드리지 않는다 — 웅크리지 않는 동안에는
 * 수정자가 아예 붙어 있지 않기 때문이다. 그래서 같은 증강에 적힌 「서서 걷기 감소」 같은 대가와
 * 그대로 나란히 쓸 수 있다.
 *
 * <h2>{@code sneaking_speed} 만으로는 안 되는 까닭</h2>
 * <p>바닐라 {@code minecraft:sneaking_speed} 는 {@code RangedAttribute} 이고 26.2 기준
 * 기본 0.3 · 최소 0.0 · <b>최대 1.0</b> 이다({@code Attributes} 의 클래스 초기화식).
 * {@code AttributeInstance.calculateValue} 가 마지막에 {@code Attribute.sanitizeValue} 를
 * 거치고, {@code RangedAttribute.sanitizeValue} 는 {@code Mth.clamp(값, 최소, 최대)} 라 1.0 을
 * 넘겨 적어 봐야 1.0 으로 잘린다. 이 속성은 이동 <b>입력 벡터에 곱하는 비율</b>이므로 1.0 은
 * 「웅크려도 걷는 속도 그대로」가 상한이라는 뜻이다. 달리기(걷기의 1.3배)를 넘길 수 없다.
 *
 * <p>그래서 이 효과는 두 가지를 함께 건다.
 *
 * <ul>
 *   <li>{@code sneaking_speed} 를 <b>상한까지</b> 올려 웅크림 입력 감쇠를 없앤다. 상한은 코드에
 *       적지 않고 {@link RangedAttribute#getMaxValue} 에서 읽는다.</li>
 *   <li>웅크리는 <b>동안에만</b> {@code movement_speed} 에 {@code add_multiplied_total} 수정자를
 *       걸어 목표 배수를 맞춘다.</li>
 * </ul>
 *
 * <h2>달리기를 기준으로 삼는 방식</h2>
 * <p>{@link #crouchModifierAmount} 는 <b>비율만</b> 다룬다. 기준이 되는 「달리기 속도」는 이
 * 수정자를 뺀 {@code movement_speed} 에 달리기 배수를 곱한 값이라, 같은 증강의 대가든 다른
 * 증강의 이동 속도 증감이든 신속 물약이든 <b>모두 기준 쪽에도 똑같이 들어간다</b>. 즉 「웅크림
 * 속도 = 그 순간 달리기 속도 × {@code sprint_multiplier}」가 언제나 성립한다. 대가로 걷기가
 * 10% 느려졌다면 달리기도 10% 느려져 있고, 웅크림 속도는 그 느려진 달리기의 1.5배다.
 *
 * <h2>웅크린 채로 달릴 수 있다</h2>
 * <p>26.2 의 {@code LocalPlayer.canStartSprinting} 은 {@code isMovingSlowly()} 면 달리기를
 * <b>시작</b>하지 못하게 막지만, 이미 달리는 중에 웅크리는 것은 막지 않는다
 * ({@code shouldStopRunSprinting} 은 웅크림을 보지 않는다). 그래서 달리다 웅크리면 달리기
 * 수정자({@code minecraft:sprinting}, {@code add_multiplied_total} +0.3)가 그대로 붙어 있다.
 * 이때 목표 배수를 다시 곱하면 1.3배가 겹쳐 훨씬 빨라지므로, 달리기 수정자가 붙어 있는지를
 * 보고 그만큼 덜 얹는다. 판정은 {@code player.isSprinting()} 이 아니라 <b>속성에 실제로 붙어
 * 있는 수정자</b>로 한다 — 정작 계산에 들어가는 것이 그 수정자이기 때문이다.
 *
 * <h2>웅크림은 사람마다 다르다</h2>
 * <p>{@link ConditionalEffect} 의 조건은 팀이 공유하는 체력·허기라 팀원 판정이 언제나 같지만,
 * 웅크림은 <b>개인 상태</b>다. 그래서 수정자는 지금 웅크린 사람에게만 붙는다.
 * {@link ToolMismatchSlowEffect} 가 「지금 손에 든 것」을 보는 것과 같은 자리다.
 *
 * <h2>매 틱 다시 본다</h2>
 * <p>{@link ConditionalPerkManager} 가 <b>매 틱</b> {@link #refresh} 를 불러 준다. 웅크림은
 * 순간마다 바뀌고, 웅크림을 푼 뒤에도 수정자가 남아 있으면 서서 걸을 때까지 빨라지기 때문에
 * 반 초 주기로는 늦다. 판정이 지난번과 같으면 아무 일도 하지 않으므로 속성 갱신 꾸러미가
 * 매 틱 나가지는 않는다.
 */
public final class SneakSpeedEffect implements PerkEffect {
	/** 목표 배수의 하한. 이보다 작으면 「빠르게 한다」는 이 타입의 뜻과 어긋난다. */
	static final double MIN_SPRINT_MULTIPLIER = 0.05;

	/** 목표 배수의 상한. 터무니없는 값으로 서버 이동 검증에 걸리지 않게 둔다. */
	static final double MAX_SPRINT_MULTIPLIER = 4.0;

	/**
	 * 바닐라 달리기 배수.
	 *
	 * <p>26.2 의 {@code LivingEntity.SPEED_MODIFIER_SPRINTING} 은
	 * {@code new AttributeModifier(minecraft:sprinting, 0.3, ADD_MULTIPLIED_TOTAL)} 이라
	 * 달리기 속도는 걷기의 1.3배다. 달리는 중에는 이 값을 쓰지 않고 속성에 붙어 있는 수정자에서
	 * 직접 읽으므로, 이 상수는 <b>달리지 않을 때 기준을 세우는 데만</b> 쓰인다.
	 * {@code SneakSpeedTargetTest} 가 바닐라 값과 같은지 못박아 둔다.
	 */
	public static final double SPRINT_FACTOR = 1.3;

	/** 바닐라가 달리기 배수를 얹을 때 쓰는 수정자 이름. */
	public static final Identifier SPRINTING_MODIFIER_ID =
			Identifier.fromNamespaceAndPath("minecraft", "sprinting");

	/** 같은 값을 다시 붙이지 않기 위한 허용 오차. */
	private static final double EPSILON = 1.0e-9;

	private final double sprintMultiplier;
	private final Identifier speedModifierId;
	private final Identifier sneakModifierId;

	/**
	 * 플레이어별로 지금 얼마짜리 수정자를 붙여 둔 상태인지. 없으면 안 붙어 있다는 뜻이다.
	 *
	 * <p>{@link #refresh} 가 「바뀌지 않았으면 아무것도 하지 않는다」를 지키려면 직전 값을
	 * 기억해야 한다. 웅크림뿐 아니라 달리기 여부에 따라 값이 달라지므로 참·거짓이 아니라
	 * 값 자체를 기억한다.
	 */
	private final Map<UUID, Double> applied = new ConcurrentHashMap<>();

	public SneakSpeedEffect(double sprintMultiplier, Identifier speedModifierId,
			Identifier sneakModifierId) {
		this.sprintMultiplier = sprintMultiplier;
		this.speedModifierId = speedModifierId;
		this.sneakModifierId = sneakModifierId;
	}

	// ------------------------------------------------------------------ 읽기

	/** JSON 에서 만든다. 정의가 잘못됐으면 경고를 남기고 null. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		Double multiplier = PerkEffectType.readDouble(json, "sprint_multiplier");
		if (multiplier == null || !Double.isFinite(multiplier)
				|| multiplier < MIN_SPRINT_MULTIPLIER || multiplier > MAX_SPRINT_MULTIPLIER) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: sneak_speed 의 sprint_multiplier 가 없거나 {}~{} 범위를 벗어났습니다 ({})",
					perkId, MIN_SPRINT_MULTIPLIER, MAX_SPRINT_MULTIPLIER, multiplier);
			return null;
		}
		Identifier speedId = AttributeEffect.modifierId(perkId, index);
		return new SneakSpeedEffect(multiplier, speedId, sneakModifierId(speedId));
	}

	/**
	 * 이동 속도 수정자 이름에서 {@code sneaking_speed} 쪽 이름을 만든다.
	 *
	 * <p>한 효과가 속성 두 개를 건드리므로 이름도 두 개가 필요하다. 뒤에 한 마디를 붙여 만들면
	 * 증강·효과 순번이 다른 한 서로 겹치지 않는다.
	 */
	public static Identifier sneakModifierId(Identifier speedModifierId) {
		return Identifier.fromNamespaceAndPath(
				speedModifierId.getNamespace(), speedModifierId.getPath() + "/sneaking");
	}

	// ------------------------------------------------------------------ 계산

	/**
	 * 웅크리는 동안 {@code movement_speed} 에 얹을 {@code add_multiplied_total} 값.
	 *
	 * <p>기준을 세우는 식은 이렇다. 이 수정자를 뺀 이동 속도를 {@code W} 라 하면
	 *
	 * <pre>
	 *   달리기 속도   = W × 달리기배수
	 *   웅크림 속도   = W × (달리는 중이면 달리기배수, 아니면 1) × (1 + 얹을값) × 웅크림비율
	 * </pre>
	 *
	 * <p>「웅크림 속도 = 달리기 속도 × 목표배수」가 되게 푼 것이 이 식이다. {@code W} 가 식에서
	 * 사라지므로 대가든 물약이든 기준과 결과에 똑같이 들어가 서로 지워진다.
	 *
	 * @param sprintMultiplier 달리기 속도의 몇 배로 만들 것인가
	 * @param sneakingSpeed    지금 {@code sneaking_speed} 값. 클라이언트가 이동 입력에 곱한다
	 * @param sprinting        지금 달리기 수정자가 붙어 있는가
	 * @param sprintFactor     달리기 배수. 바닐라 기준 1.3
	 */
	public static double crouchModifierAmount(double sprintMultiplier, double sneakingSpeed,
			boolean sprinting, double sprintFactor) {
		double sprintPart = sprinting ? sprintFactor : 1.0;
		return sprintMultiplier * sprintFactor / (sprintPart * sneakingSpeed) - 1.0;
	}

	/**
	 * 그 값으로 수정자를 걸었을 때 웅크림 속도가 달리기 속도의 몇 배가 되는가.
	 *
	 * <p>{@link #crouchModifierAmount} 의 역이다. 시험이 「정말 1.5배인가」를 이쪽으로 확인한다.
	 */
	public static double crouchSpeedRatio(double amount, double sneakingSpeed,
			boolean sprinting, double sprintFactor) {
		double sprintPart = sprinting ? sprintFactor : 1.0;
		return sprintPart * (1.0 + amount) * sneakingSpeed / sprintFactor;
	}

	/**
	 * {@code sneaking_speed} 를 상한까지 올리는 데 필요한 {@code add_value} 값.
	 *
	 * <p>상한을 이미 넘어서 있으면 0 이다. 음수를 얹어 도로 깎지는 않는다.
	 */
	public static double sneakingSpeedAmount(double baseValue, double maxValue) {
		return Math.max(0.0, maxValue - baseValue);
	}

	/**
	 * 클라이언트가 이동 입력에 {@code sneaking_speed} 를 곱하는 상태인가.
	 *
	 * <p>26.2 의 {@code LocalPlayer.isMovingSlowly()} 와 같은 판정이다 —
	 * {@code isCrouching() || isVisuallyCrawling()}. 그 자리와 어긋나면 감쇠가 걸리는 순간과
	 * 보정이 걸리는 순간이 달라져 속도가 튄다. 두 메서드 모두 {@code Entity} 에 있고 자세는
	 * 서버에서도 {@code Player.updatePlayerPose} 로 갱신되므로 서버에서 그대로 볼 수 있다.
	 */
	public static boolean movingSlowly(@Nullable ServerPlayer player) {
		return player != null && (player.isCrouching() || player.isVisuallyCrawling());
	}

	// ------------------------------------------------------------------ 적용

	/**
	 * 지금 자세를 보고 수정자를 맞춘다.
	 *
	 * <p>기억해 둔 값과 관계없이 무조건 다시 맞춘다. 접속이나 부활 직후처럼 수정자가 통째로
	 * 날아간 상태에서도 불리기 때문이다.
	 */
	@Override
	public void apply(ServerPlayer player) {
		if (player == null) {
			return;
		}
		applySneakingCap(player);
		Double amount = desiredAmount(player);
		writeSpeedModifier(player, amount);
		remember(player.getUUID(), amount);
	}

	/** 두 수정자를 모두 걷어낸다. 붙어 있지 않아도 안전하다. */
	@Override
	public void remove(ServerPlayer player) {
		if (player == null) {
			return;
		}
		AttributeInstance sneaking = player.getAttribute(Attributes.SNEAKING_SPEED);
		if (sneaking != null) {
			sneaking.removeModifier(sneakModifierId);
		}
		writeSpeedModifier(player, null);
		applied.remove(player.getUUID());
	}

	/**
	 * 자세를 다시 보고, 지난번과 달라졌을 때만 수정자를 갈아 끼운다.
	 *
	 * <p>{@link ConditionalPerkManager} 가 매 틱 부른다.
	 *
	 * @return 실제로 갈아 끼웠으면 true
	 */
	public boolean refresh(ServerPlayer player) {
		if (player == null) {
			return false;
		}
		Double amount = desiredAmount(player);
		Double previous = applied.get(player.getUUID());
		if (same(previous, amount)) {
			return false;
		}
		writeSpeedModifier(player, amount);
		remember(player.getUUID(), amount);
		return true;
	}

	/** 이 플레이어에게 지금 붙여 둔 값. 안 붙어 있으면 null. */
	public @Nullable Double appliedAmount(@Nullable UUID playerId) {
		return playerId == null ? null : applied.get(playerId);
	}

	/** 기억해 둔 값을 모두 버린다. 서버가 멈출 때 다음 회차로 새어나가지 않게 한다. */
	public void forgetAll() {
		applied.clear();
	}

	/**
	 * 지금 붙어 있어야 하는 값. 웅크리지 않았으면 null.
	 *
	 * <p>{@code sneaking_speed} 가 0 이하이면 아무리 얹어도 제자리라 관여하지 않는다. 0 으로
	 * 나누는 것도 여기서 막힌다.
	 */
	private @Nullable Double desiredAmount(ServerPlayer player) {
		if (!movingSlowly(player)) {
			return null;
		}
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return null;
		}
		double sneakingSpeed = player.getAttributeValue(Attributes.SNEAKING_SPEED);
		if (!Double.isFinite(sneakingSpeed) || sneakingSpeed <= 0.0) {
			return null;
		}
		// 달리기 수정자가 붙어 있으면 그 값을 그대로 쓴다. 바닐라 상수를 믿는 대신 지금 계산에
		// 실제로 들어가는 값을 보는 편이 판이 바뀌어도 어긋나지 않는다.
		AttributeModifier sprint = speed.getModifier(SPRINTING_MODIFIER_ID);
		boolean sprinting = sprint != null
				&& sprint.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
		double sprintFactor = sprinting ? 1.0 + sprint.amount() : SPRINT_FACTOR;
		if (!Double.isFinite(sprintFactor) || sprintFactor <= 0.0) {
			return null;
		}
		double amount =
				crouchModifierAmount(sprintMultiplier, sneakingSpeed, sprinting, sprintFactor);
		return Double.isFinite(amount) ? amount : null;
	}

	/** {@code sneaking_speed} 를 상한까지 올려 둔다. 웅크리지 않아도 붙어 있어 해가 없다. */
	private void applySneakingCap(ServerPlayer player) {
		AttributeInstance instance = player.getAttribute(Attributes.SNEAKING_SPEED);
		if (instance == null) {
			return;
		}
		instance.removeModifier(sneakModifierId);
		Attribute attribute = instance.getAttribute().value();
		if (!(attribute instanceof RangedAttribute ranged)) {
			// 상한을 알 수 없으면 건드리지 않는다. 얼마를 얹어야 하는지 모르는 채로 올리면
			// 보정 계산의 기준이 흔들린다.
			return;
		}
		double amount = sneakingSpeedAmount(instance.getBaseValue(), ranged.getMaxValue());
		if (amount <= 0.0) {
			return;
		}
		instance.addTransientModifier(new AttributeModifier(
				sneakModifierId, amount, AttributeModifier.Operation.ADD_VALUE));
	}

	/** 이동 속도 수정자를 그 값으로 맞춘다. null 이면 걷어내기만 한다. */
	private void writeSpeedModifier(ServerPlayer player, @Nullable Double amount) {
		AttributeInstance instance = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (instance == null) {
			return;
		}
		instance.removeModifier(speedModifierId);
		if (amount != null) {
			instance.addTransientModifier(new AttributeModifier(
					speedModifierId, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		}
	}

	private void remember(UUID playerId, @Nullable Double amount) {
		if (amount == null) {
			applied.remove(playerId);
		} else {
			applied.put(playerId, amount);
		}
	}

	private static boolean same(@Nullable Double previous, @Nullable Double amount) {
		if (previous == null || amount == null) {
			return previous == null && amount == null;
		}
		return Math.abs(previous - amount) < EPSILON;
	}

	// ------------------------------------------------------------------ 조회

	public double sprintMultiplier() {
		return sprintMultiplier;
	}

	public Identifier speedModifierId() {
		return speedModifierId;
	}

	public Identifier sneakModifierIdentifier() {
		return sneakModifierId;
	}
}
