package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.SneakSpeedEffect;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code sneak_speed} 의 정의 읽기와 속도 계산을 본다.
 *
 * <p>수정자를 실제로 붙이는 자리는 살아 있는 {@code ServerPlayer} 가 있어야 해서 단위 시험에서
 * 그대로 돌릴 수 없다. 대신 두 갈래로 나눠 본다.
 *
 * <ul>
 *   <li>「얼마를 얹어야 하는가」는 {@link SneakSpeedEffect#crouchModifierAmount} 로 떼어 놓은
 *       순수 함수라 값 그대로 확인한다.</li>
 *   <li>「그 값을 얹으면 정말 그 속도가 되는가」는 바닐라
 *       {@link AttributeInstance} 를 직접 만들어 확인한다. 계산 규칙과 상한 자르기가 실제로
 *       26.2 의 것이므로, 손으로 옮겨 적은 식이 아니라 게임이 쓰는 식으로 확인하는 셈이다.</li>
 * </ul>
 *
 * <h2>{@code PerkEffectType} 을 거치지 않는다</h2>
 * <p>{@code sneak_speed} 를 열거형에 잇는 것은 등록 파일의 일이라 여기서는
 * {@link SneakSpeedEffect#fromJson} 을 직접 부른다. 열거형에 이어진 뒤에는
 * {@code PerkEffectType.fromId("sneak_speed")} 가 이 팩토리를 그대로 가리킨다.
 */
class SneakSpeedEffectTest {

	/** 「숨죽인 걸음」이 노리는 값. 웅크림 속도를 달리기의 1.5배로 만든다. */
	private static final double TARGET = 1.5;

	/** 같은 증강이 대가로 거는 서서 걷기 감소. {@code add_multiplied_total} 로 −10% 다. */
	private static final double WALK_DRAWBACK = -0.1;

	private static AttributeSupplier attributes;

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
		attributes = Player.createAttributes().build();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	// ------------------------------------------------------------------ 정의 읽기

	@Test
	void sprint_multiplier_를_그대로_읽는다() {
		SneakSpeedEffect effect = create("{ \"type\": \"sneak_speed\", \"sprint_multiplier\": 1.5 }");

		assertEquals(TARGET, effect.sprintMultiplier(), 1.0e-9);
	}

	@Test
	void sprint_multiplier_가_없거나_범위를_벗어나면_버린다() {
		assertNull(raw("{ \"type\": \"sneak_speed\" }"),
				"배수를 안 적으면 무엇을 노리는 정의인지 알 수 없다");
		assertNull(raw("{ \"type\": \"sneak_speed\", \"sprint_multiplier\": 0 }"),
				"0 배는 발을 묶는 것이지 빠르게 하는 것이 아니다");
		assertNull(raw("{ \"type\": \"sneak_speed\", \"sprint_multiplier\": 99 }"),
				"상한이 없으면 서버 이동 검증에 걸릴 값도 그대로 들어간다");
	}

	@Test
	void 속성_두_개를_서로_다른_이름으로_건드린다() {
		SneakSpeedEffect effect = create("{ \"type\": \"sneak_speed\", \"sprint_multiplier\": 1.5 }");

		assertNotEquals(effect.speedModifierId(), effect.sneakModifierIdentifier(),
				"이름이 같으면 한쪽이 다른 쪽을 덮어써 둘 중 하나가 조용히 사라진다");
	}

	@Test
	void 효과_순번마다_다른_수정자를_쓴다() {
		SneakSpeedEffect first = create("{ \"type\": \"sneak_speed\", \"sprint_multiplier\": 1.5 }");
		SneakSpeedEffect second = assertInstanceOf(SneakSpeedEffect.class,
				raw("{ \"type\": \"sneak_speed\", \"sprint_multiplier\": 1.5 }", 1));

		assertNotEquals(first.speedModifierId(), second.speedModifierId());
		assertNotEquals(first.sneakModifierIdentifier(), second.sneakModifierIdentifier());
	}

	// ------------------------------------------------------------------ 얼마를 얹는가

	@Test
	void 웅크림_속도가_달리기의_1_5배가_된다() {
		double amount = SneakSpeedEffect.crouchModifierAmount(
				TARGET, 1.0, false, SneakSpeedEffect.SPRINT_FACTOR);

		// 1.5 × 1.3 = 1.95 배. add_multiplied_total 은 (1 + 값) 을 곱하므로 0.95 다.
		assertEquals(0.95, amount, 1.0e-9);
		assertEquals(TARGET, SneakSpeedEffect.crouchSpeedRatio(
				amount, 1.0, false, SneakSpeedEffect.SPRINT_FACTOR), 1.0e-9);
	}

	@Test
	void 달리는_채로_웅크려도_1_5배를_넘지_않는다() {
		// 26.2 에서는 달리다 웅크리는 것을 막지 않는다. 달리기 수정자가 그대로 붙어 있으므로
		// 그만큼 덜 얹어야 1.3 배가 겹치지 않는다.
		double amount = SneakSpeedEffect.crouchModifierAmount(
				TARGET, 1.0, true, SneakSpeedEffect.SPRINT_FACTOR);

		assertEquals(0.5, amount, 1.0e-9);
		assertEquals(TARGET, SneakSpeedEffect.crouchSpeedRatio(
				amount, 1.0, true, SneakSpeedEffect.SPRINT_FACTOR), 1.0e-9);
	}

	@Test
	void 웅크림_비율이_상한까지_안_올라가도_배수를_맞춘다() {
		// sneaking_speed 를 못 올린 상태(바닐라 0.3)에서도 결과 비율은 같아야 한다.
		double amount = SneakSpeedEffect.crouchModifierAmount(
				TARGET, 0.3, false, SneakSpeedEffect.SPRINT_FACTOR);

		assertEquals(TARGET, SneakSpeedEffect.crouchSpeedRatio(
				amount, 0.3, false, SneakSpeedEffect.SPRINT_FACTOR), 1.0e-9);
	}

	@Test
	void 웅크림_비율을_상한까지_올리는_값을_구한다() {
		assertEquals(0.7, SneakSpeedEffect.sneakingSpeedAmount(0.3, 1.0), 1.0e-9,
				"바닐라 sneaking_speed 는 기본 0.3 · 상한 1.0 이다");
		assertEquals(0.0, SneakSpeedEffect.sneakingSpeedAmount(1.0, 1.0), 1.0e-9);
		assertEquals(0.0, SneakSpeedEffect.sneakingSpeedAmount(1.2, 1.0), 1.0e-9,
				"이미 상한을 넘어서 있으면 도로 깎지 않는다");
	}

	// ------------------------------------------------------------------ 대가와의 관계

	@Test
	void 서서_걷는_속도는_대가_그대로_10퍼센트만_준다() {
		// 웅크리지 않는 동안에는 이 효과의 수정자가 아예 붙지 않는다. 서서 걷는 속도에 남는 것은
		// 같은 증강이 거는 대가뿐이다.
		AttributeInstance speed = instance(Attributes.MOVEMENT_SPEED);
		double bare = speed.getValue();
		speed.addTransientModifier(new AttributeModifier(
				id("drawback"), WALK_DRAWBACK, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));

		assertEquals(bare * 0.9, speed.getValue(), 1.0e-9);
	}

	@Test
	void 대가가_먼저_걸려도_웅크림은_그_달리기의_1_5배다() {
		// 기준이 되는 「달리기」는 대가까지 먹은 뒤의 달리기다. 대가는 기준과 결과 양쪽에
		// 똑같이 들어가므로 배수 자체는 흔들리지 않는다.
		AttributeInstance speed = instance(Attributes.MOVEMENT_SPEED);
		speed.addTransientModifier(new AttributeModifier(
				id("drawback"), WALK_DRAWBACK, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		double walk = speed.getValue();
		double sprint = walk * SneakSpeedEffect.SPRINT_FACTOR;

		double amount = SneakSpeedEffect.crouchModifierAmount(
				TARGET, 1.0, false, SneakSpeedEffect.SPRINT_FACTOR);
		speed.addTransientModifier(new AttributeModifier(
				id("sneak"), amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));

		// sneaking_speed 를 1.0 으로 올려 두었으므로 이동 입력은 그대로 곱해진다.
		assertEquals(TARGET * sprint, speed.getValue() * 1.0, 1.0e-9);
	}

	@Test
	void 수정자를_떼면_대가만_남는다() {
		AttributeInstance speed = instance(Attributes.MOVEMENT_SPEED);
		speed.addTransientModifier(new AttributeModifier(
				id("drawback"), WALK_DRAWBACK, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		double walk = speed.getValue();
		speed.addTransientModifier(new AttributeModifier(
				id("sneak"), 0.95, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		assertNotEquals(walk, speed.getValue(), 1.0e-9);

		assertTrue(speed.removeModifier(id("sneak")));
		assertEquals(walk, speed.getValue(), 1.0e-9,
				"웅크림을 풀면 곧바로 대가만 남아야 한다. 남으면 서서 걸을 때도 빨라진다");
	}

	// ------------------------------------------------------------------ 사람마다 따로

	@Test
	void 웅크림은_사람마다_따로_기억한다() {
		SneakSpeedEffect effect = create("{ \"type\": \"sneak_speed\", \"sprint_multiplier\": 1.5 }");

		assertNull(effect.appliedAmount(null));
		assertNull(effect.appliedAmount(UUID.randomUUID()),
				"한 사람이 웅크렸다고 다른 팀원까지 붙어 있으면 안 된다");
	}

	@Test
	void 플레이어가_없으면_아무것도_하지_않는다() {
		SneakSpeedEffect effect = create("{ \"type\": \"sneak_speed\", \"sprint_multiplier\": 1.5 }");

		assertFalse(SneakSpeedEffect.movingSlowly(null));
		assertFalse(effect.refresh(null));
	}

	// ------------------------------------------------------------------ 증강 풀 통합

	/**
	 * 번들 기본 풀의 「숨죽인 걸음」이 정말 이 효과를 쓰는지 본다.
	 *
	 * <p>등록 파일 두 곳이 모두 맞아야 통과한다 — {@code PerkEffectType} 에
	 * {@code sneak_speed} 가 이어져 있어야 하고, {@code sharedfate-perks-default.json} 의
	 * 「숨죽인 걸음」이 그 타입을 써야 한다. 둘 중 하나라도 빠지면 이 증강은 <b>조용히
	 * 풀에서 빠지거나 아무 일도 안 하는 정의</b>가 되므로, 그것을 여기서 붙들어 둔다.
	 */
	@Test
	void 기본_풀의_숨죽인_걸음이_이_효과를_쓴다() {
		PerkRegistry.loadBundled();

		Perk perk = PerkRegistry.byId("sharedfate:hushed_step").orElseThrow(
				() -> new AssertionError("증강이 풀에서 빠졌다. sneak_speed 가 등록돼 있는지 본다"));
		SneakSpeedEffect sneakSpeed = assertInstanceOf(
				SneakSpeedEffect.class, perk.effects().get(0),
				"첫 효과가 sneak_speed 여야 한다");
		assertEquals(TARGET, sneakSpeed.sprintMultiplier(), 1.0e-9);

		// 대가는 그대로 남아 있어야 한다. 이것이 사라지면 이득만 남은 증강이 된다.
		com.sharedfate.perk.effect.AttributeEffect drawback = assertInstanceOf(
				com.sharedfate.perk.effect.AttributeEffect.class, perk.effects().get(1));
		assertEquals("minecraft:movement_speed", drawback.attributeId().toString());
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, drawback.operation());
		assertEquals(WALK_DRAWBACK, drawback.amount(), 1.0e-9);
	}

	// ------------------------------------------------------------------ 도우미

	private static AttributeInstance instance(
			net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
		AttributeInstance created = attributes.createInstance(ignored -> {
		}, attribute);
		assertNotNull(created, "플레이어가 가진 속성이어야 한다");
		return created;
	}

	private static net.minecraft.resources.Identifier id(String path) {
		return net.minecraft.resources.Identifier.fromNamespaceAndPath("sharedfate", "test/" + path);
	}

	private static SneakSpeedEffect create(String json) {
		return assertInstanceOf(SneakSpeedEffect.class, raw(json));
	}

	private static PerkEffect raw(String json) {
		return raw(json, 0);
	}

	private static PerkEffect raw(String json, int index) {
		JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
		return SneakSpeedEffect.fromJson("sharedfate:테스트", index, parsed);
	}
}
