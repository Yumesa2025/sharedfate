package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.util.Locale;
import java.util.Optional;

/**
 * 팀원에게 속성 수정자를 붙이는 효과.
 *
 * <p>수정자는 임시(transient)로 붙으므로 저장되지 않는다. 회차가 끝나 월드가 새로 생기면
 * 자연히 사라지고, 접속할 때마다 다시 붙인다.
 *
 * <p>수정자 식별자는 증강 id와 효과 순번으로 만든다. 같은 식별자를 쓰면 서로 덮어쓰기 때문에
 * 증강마다, 효과마다 달라야 한다.
 *
 * <p>속성 자체는 만들 때가 아니라 처음 적용할 때 찾는다. 증강 정의를 읽는 시점에는 레지스트리가
 * 아직 준비되지 않았을 수 있기 때문이다.
 *
 * <h2>{@code minecraft:max_health} 는 여기서 정해지지 않는다</h2>
 * <p>이 모드의 최대 체력은 팀 공유 상한이고 {@code MaxHealthAttribute} 가 팀원의 속성을 그
 * 상한과 <b>똑같아지도록</b> 덮어쓴다. 그래서 여기서 {@code max_health} 에 +6 을 걸어 봐야
 * 그 덮어쓰기가 정확히 +6 을 상쇄해 증강이 아무 일도 하지 않은 것처럼 보인다. 최대 체력은
 * {@link MaxHealthBonusEffect}({@code max_health_bonus}) 로 적는다.
 *
 * <p>{@code attribute} + {@code max_health} + {@code add_value} 로 적은 정의도
 * <b>버리지는 않는다.</b> {@link #isLegacyMaxHealthBonus} 로 표를 내주고,
 * {@link com.sharedfate.perk.PerkHealthRules} 가 그것도 보너스로 세어 상한 자체를 올려 준다.
 * 그러면 여기서 건 수정자는 상한과 같은 값이 되어 덮어쓰기와 부딪히지 않는다.
 */
public final class AttributeEffect implements PerkEffect {
	/** 터무니없는 값으로 게임을 깨뜨리지 않도록 두는 상한. */
	private static final double MAX_ABS_AMOUNT = 1024.0;
	/** 이 속성만은 수정자가 아니라 {@link MaxHealthBonusEffect} 로 옮겨 읽는다. */
	private static final Identifier MAX_HEALTH_ID =
			Identifier.fromNamespaceAndPath("minecraft", "max_health");

	private final Identifier attributeId;
	private final Identifier modifierId;
	private final AttributeModifier.Operation operation;
	private final double amount;

	private Holder<Attribute> attribute;
	private boolean resolveFailed;

	public AttributeEffect(Identifier attributeId, Identifier modifierId,
			AttributeModifier.Operation operation, double amount) {
		this.attributeId = attributeId;
		this.modifierId = modifierId;
		this.operation = operation;
		this.amount = amount;
	}

	/** JSON에서 만든다. 정의가 잘못됐으면 경고를 남기고 null. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		String rawAttribute = PerkEffectType.readString(json, "attribute");
		if (rawAttribute == null || rawAttribute.isBlank()) {
			SharedFateMod.LOGGER.warn("증강 {}: attribute 효과에 attribute 필드가 없습니다", perkId);
			return null;
		}
		Identifier attributeId = Identifier.tryParse(rawAttribute.trim());
		if (attributeId == null) {
			SharedFateMod.LOGGER.warn("증강 {}: 올바르지 않은 속성 이름 {}", perkId, rawAttribute);
			return null;
		}

		AttributeModifier.Operation operation =
				parseOperation(PerkEffectType.readString(json, "operation"));
		if (operation == null) {
			SharedFateMod.LOGGER.warn("증강 {}: 알 수 없는 operation {}",
					perkId, PerkEffectType.readString(json, "operation"));
			return null;
		}

		Double amount = PerkEffectType.readDouble(json, "amount");
		if (amount == null || Math.abs(amount) > MAX_ABS_AMOUNT) {
			SharedFateMod.LOGGER.warn("증강 {}: amount 값이 없거나 범위를 벗어났습니다 ({})", perkId, amount);
			return null;
		}

		if (MAX_HEALTH_ID.equals(attributeId)) {
			SharedFateMod.LOGGER.info(
					"증강 {}: 최대 체력은 max_health_bonus 로 적는 편이 좋습니다. "
							+ "attribute 로 적힌 add_value 는 PerkHealthRules 가 대신 세어 줍니다 ({} {})",
					perkId, operation, amount);
		}
		return new AttributeEffect(attributeId, modifierId(perkId, index), operation, amount);
	}

	/** 이 효과가 최대 체력을 {@code add_value} 로 올리는 형식인가. */
	public boolean isLegacyMaxHealthBonus() {
		return MAX_HEALTH_ID.equals(attributeId)
				&& operation == AttributeModifier.Operation.ADD_VALUE;
	}

	/** 증강 id와 효과 순번으로 이 효과만의 수정자 식별자를 만든다. */
	public static Identifier modifierId(String perkId, int index) {
		return SharedFateMod.id("perk/" + sanitize(perkId) + "/" + Math.max(0, index));
	}

	@Override
	public void apply(ServerPlayer player) {
		apply(player, amount);
	}

	/**
	 * 정의에 적힌 값 대신 {@code appliedAmount} 로 붙인다. 식별자는 같으므로 다음
	 * {@link #apply(ServerPlayer)} 가 그대로 덮어쓴다.
	 *
	 * <p>「굳은살」이 낙하 피해 증가분을 덜어 낼 때 쓴다({@link DamageIncreaseReliefEffect}).
	 */
	public void apply(ServerPlayer player, double appliedAmount) {
		AttributeInstance instance = instanceFor(player);
		if (instance == null) {
			return;
		}
		double safe = Double.isFinite(appliedAmount)
				? Math.max(-MAX_ABS_AMOUNT, Math.min(MAX_ABS_AMOUNT, appliedAmount))
				: amount;
		instance.removeModifier(modifierId);
		instance.addTransientModifier(new AttributeModifier(modifierId, safe, operation));
		clampHealth(player);
	}

	@Override
	public void remove(ServerPlayer player) {
		AttributeInstance instance = instanceFor(player);
		if (instance == null) {
			return;
		}
		instance.removeModifier(modifierId);
		clampHealth(player);
	}

	public Identifier attributeId() {
		return attributeId;
	}

	public Identifier modifierId() {
		return modifierId;
	}

	public AttributeModifier.Operation operation() {
		return operation;
	}

	public double amount() {
		return amount;
	}

	private AttributeInstance instanceFor(ServerPlayer player) {
		if (player == null) {
			return null;
		}
		Holder<Attribute> resolved = resolve();
		return resolved == null ? null : player.getAttribute(resolved);
	}

	/** 최대 체력이 줄었을 때 현재 체력이 넘치지 않도록 맞춘다. 다른 속성에는 영향이 없다. */
	private static void clampHealth(ServerPlayer player) {
		float max = player.getMaxHealth();
		if (player.getHealth() > max) {
			player.setHealth(max);
		}
	}

	private Holder<Attribute> resolve() {
		if (attribute != null || resolveFailed) {
			return attribute;
		}
		try {
			Optional<Holder.Reference<Attribute>> found = BuiltInRegistries.ATTRIBUTE.get(attributeId);
			if (found.isEmpty()) {
				resolveFailed = true;
				SharedFateMod.LOGGER.warn("증강 효과가 가리키는 속성을 찾을 수 없습니다: {}", attributeId);
			} else {
				attribute = found.get();
			}
		} catch (Exception error) {
			resolveFailed = true;
			SharedFateMod.LOGGER.warn("속성 {} 을 찾다가 실패했습니다", attributeId, error);
		}
		return attribute;
	}

	private static AttributeModifier.Operation parseOperation(String raw) {
		if (raw == null) {
			return null;
		}
		String normalized = raw.trim().toUpperCase(Locale.ROOT);
		for (AttributeModifier.Operation operation : AttributeModifier.Operation.values()) {
			if (operation.name().equals(normalized)) {
				return operation;
			}
		}
		return null;
	}

	/** 증강 id에는 콜론처럼 식별자 경로에 못 쓰는 문자가 들어 있어 걸러낸다. */
	private static String sanitize(String perkId) {
		if (perkId == null || perkId.isBlank()) {
			return "unknown";
		}
		StringBuilder builder = new StringBuilder(perkId.length());
		for (char character : perkId.trim().toLowerCase(Locale.ROOT).toCharArray()) {
			boolean allowed = (character >= 'a' && character <= 'z')
					|| (character >= '0' && character <= '9')
					|| character == '_' || character == '.' || character == '-';
			builder.append(allowed ? character : '_');
		}
		return builder.toString();
	}
}
