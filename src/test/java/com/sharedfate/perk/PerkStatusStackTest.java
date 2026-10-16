package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.mixin.MobEffectInstanceAccessor;
import com.sharedfate.perk.effect.StatusEffectPerk;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 같은 상태이상을 여러 출처가 줄 때 「가장 센 것이 남고, 그것이 사라지면 그다음 센 것이 남는다」를
 * 못박는다.
 *
 * <p>몸은 상태이상 표만 흉내 낸 가짜다. 추가는 바닐라 {@code LivingEntity.addEffect} 처럼 이미
 * 있으면 진짜 {@code MobEffectInstance.update} 로 합치므로, 덮고 숨기는 규칙은 게임과 같다.
 */
class PerkStatusStackTest {
	/** 포션 한 병 정도의 지속시간. 무한이 아니라는 점만 중요하다. */
	private static final int POTION_TICKS = 3 * 60 * 20;

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkStatusStack.reset();
	}

	// ------------------------------------------------------------------ 재생 (사람이 짚은 경우)

	@Test
	void 재생_II_무한이_있을_때_재생_I_무한_증강을_골라도_II_가_그대로다() {
		FakeBody body = new FakeBody();
		StatusEffectPerk bottomlessJar = perk("minecraft:regeneration", 1);
		StatusEffectPerk endlessRecovery = perk("minecraft:regeneration", 0);

		PerkStatusStack.grant(body, bottomlessJar);
		PerkStatusStack.grant(body, endlessRecovery);

		assertInfinite(body, MobEffects.REGENERATION, 1);
	}

	@Test
	void 재생_II_출처를_잃으면_재생_I_무한이_남는다() {
		FakeBody body = new FakeBody();
		StatusEffectPerk bottomlessJar = perk("minecraft:regeneration", 1);
		StatusEffectPerk endlessRecovery = perk("minecraft:regeneration", 0);
		PerkStatusStack.grant(body, bottomlessJar);
		PerkStatusStack.grant(body, endlessRecovery);

		PerkStatusStack.revoke(body, bottomlessJar);

		assertInfinite(body, MobEffects.REGENERATION, 0);
	}

	@Test
	void 붙이는_순서가_반대여도_결과가_같다() {
		FakeBody body = new FakeBody();
		StatusEffectPerk bottomlessJar = perk("minecraft:regeneration", 1);
		StatusEffectPerk endlessRecovery = perk("minecraft:regeneration", 0);

		PerkStatusStack.grant(body, endlessRecovery);
		PerkStatusStack.grant(body, bottomlessJar);
		assertInfinite(body, MobEffects.REGENERATION, 1);

		PerkStatusStack.revoke(body, bottomlessJar);
		assertInfinite(body, MobEffects.REGENERATION, 0);
	}

	@Test
	void 약한_쪽을_먼저_잃으면_센_쪽이_그대로다() {
		FakeBody body = new FakeBody();
		StatusEffectPerk bottomlessJar = perk("minecraft:regeneration", 1);
		StatusEffectPerk endlessRecovery = perk("minecraft:regeneration", 0);
		PerkStatusStack.grant(body, bottomlessJar);
		PerkStatusStack.grant(body, endlessRecovery);

		PerkStatusStack.revoke(body, endlessRecovery);
		assertInfinite(body, MobEffects.REGENERATION, 1);

		PerkStatusStack.revoke(body, bottomlessJar);
		assertNull(body.effect(MobEffects.REGENERATION));
	}

	@Test
	void 붙인_적_없는_조건부_재생_I_을_떼도_재생_II_가_남는다() {
		// 「아가미」·「발맞춤」은 조건이 거짓이면 refreshPlayer 가 돌 때마다 참 쪽 묶음(재생 I)을
		// 뗀다. 예전에는 그 한 번에 「밑 빠진 독」의 재생 II 무한이 종류째 지워졌다.
		FakeBody body = new FakeBody();
		StatusEffectPerk bottomlessJar = perk("minecraft:regeneration", 1);
		StatusEffectPerk gills = perk("minecraft:regeneration", 0);
		PerkStatusStack.grant(body, bottomlessJar);

		PerkStatusStack.revoke(body, gills);

		assertInfinite(body, MobEffects.REGENERATION, 1);
	}

	@Test
	void 같은_등급_출처가_둘이면_하나를_잃어도_그대로다() {
		FakeBody body = new FakeBody();
		StatusEffectPerk endlessRecovery = perk("minecraft:regeneration", 0);
		StatusEffectPerk inStep = perk("minecraft:regeneration", 0);
		PerkStatusStack.grant(body, endlessRecovery);
		PerkStatusStack.grant(body, inStep);

		PerkStatusStack.revoke(body, inStep);
		assertInfinite(body, MobEffects.REGENERATION, 0);

		PerkStatusStack.revoke(body, endlessRecovery);
		assertNull(body.effect(MobEffects.REGENERATION));
	}

	// ------------------------------------------------------------------ 포션과 섞일 때

	@Test
	void 더_센_포션이_덮여_있으면_증강을_잃어도_포션은_그대로고_밑에_남은_증강이_숨는다() {
		FakeBody body = new FakeBody();
		StatusEffectPerk bottomlessJar = perk("minecraft:regeneration", 1);
		StatusEffectPerk endlessRecovery = perk("minecraft:regeneration", 0);
		PerkStatusStack.grant(body, bottomlessJar);
		PerkStatusStack.grant(body, endlessRecovery);
		body.add(potion(MobEffects.REGENERATION, 2));

		PerkStatusStack.revoke(body, bottomlessJar);

		MobEffectInstance top = body.effect(MobEffects.REGENERATION);
		assertNotNull(top);
		assertEquals(2, top.getAmplifier());
		assertEquals(POTION_TICKS, top.getDuration());
		MobEffectInstance hidden = hiddenOf(top);
		assertNotNull(hidden, "포션이 끝나면 꺼낼 증강 층이 밑에 있어야 한다");
		assertTrue(hidden.isInfiniteDuration());
		assertEquals(0, hidden.getAmplifier());
		assertNull(hiddenOf(hidden));
	}

	@Test
	void 증강을_전부_잃어도_포션은_남는다() {
		FakeBody body = new FakeBody();
		StatusEffectPerk endlessRecovery = perk("minecraft:regeneration", 0);
		PerkStatusStack.grant(body, endlessRecovery);
		body.add(potion(MobEffects.REGENERATION, 1));

		PerkStatusStack.revoke(body, endlessRecovery);

		MobEffectInstance top = body.effect(MobEffects.REGENERATION);
		assertNotNull(top, "예전에는 종류째 지워 포션까지 사라졌다");
		assertEquals(1, top.getAmplifier());
		assertFalse(top.isInfiniteDuration());
		assertNull(hiddenOf(top));
	}

	@Test
	void 포션만_있을_때_붙인_적_없는_증강을_떼도_포션은_그대로다() {
		FakeBody body = new FakeBody();
		body.add(potion(MobEffects.REGENERATION, 0));

		PerkStatusStack.revoke(body, perk("minecraft:regeneration", 1));

		MobEffectInstance top = body.effect(MobEffects.REGENERATION);
		assertNotNull(top);
		assertEquals(0, top.getAmplifier());
		assertEquals(0, body.removals, "손댈 것이 없으면 지웠다 붙이지도 않는다");
	}

	@Test
	void 증강보다_약한_포션은_버려지고_증강이_남는다() {
		FakeBody body = new FakeBody();
		PerkStatusStack.grant(body, perk("minecraft:regeneration", 1));

		body.add(potion(MobEffects.REGENERATION, 0));

		assertInfinite(body, MobEffects.REGENERATION, 1);
	}

	// ------------------------------------------------------------------ 잠깐 거는 효과

	@Test
	void 잠깐_건_재생을_거둬도_증강_상시분이_남는다() {
		// 「엇박 교대」처럼 잠깐 거는 재생 I 을 거두는 자리가 예전에는 종류째 지웠다.
		FakeBody body = new FakeBody();
		PerkStatusStack.grant(body, perk("minecraft:regeneration", 1));
		body.add(new MobEffectInstance(MobEffects.REGENERATION, 30 * 20, 0, false, false, true));

		PerkStatusStack.revokeTimed(body, MobEffects.REGENERATION, 0);

		assertInfinite(body, MobEffects.REGENERATION, 1);
		assertEquals(0, body.removals, "센 상시분 밑에 버려진 층이라 손댈 것이 없다");
	}

	@Test
	void 잠깐_건_센_효과를_거두면_밑의_증강_상시분이_드러난다() {
		FakeBody body = new FakeBody();
		PerkStatusStack.grant(body, perk("minecraft:slowness", 0));
		body.add(new MobEffectInstance(MobEffects.SLOWNESS, 100, 255, false, false, false));

		PerkStatusStack.revokeTimed(body, MobEffects.SLOWNESS, 255);

		assertInfinite(body, MobEffects.SLOWNESS, 0);
	}

	@Test
	void 잠깐_건_것만_있으면_거두면_비어_있다() {
		FakeBody body = new FakeBody();
		body.add(new MobEffectInstance(MobEffects.WEAKNESS, 40, 0, false, false, true));

		PerkStatusStack.revokeTimed(body, MobEffects.WEAKNESS, 0);

		assertNull(body.effect(MobEffects.WEAKNESS));
	}

	// ------------------------------------------------------------------ 다른 효과도 같은 규칙

	@Test
	void 다른_효과도_센_것이_남고_사라지면_그다음이_남는다() {
		for (String id : List.of("minecraft:speed", "minecraft:resistance", "minecraft:strength",
				"minecraft:haste", "minecraft:slowness", "minecraft:weakness")) {
			sameRuleFor(id);
		}
	}

	private static void sameRuleFor(String id) {
		FakeBody body = new FakeBody();
		StatusEffectPerk strong = perk(id, 1);
		StatusEffectPerk weak = perk(id, 0);
		Holder<MobEffect> type = strong.resolvedEffect();
		assertNotNull(type);

		PerkStatusStack.grant(body, strong);
		PerkStatusStack.grant(body, weak);
		assertInfinite(body, type, 1);

		PerkStatusStack.revoke(body, weak);
		assertInfinite(body, type, 1);
		PerkStatusStack.grant(body, weak);

		PerkStatusStack.revoke(body, strong);
		assertInfinite(body, type, 0);

		PerkStatusStack.revoke(body, weak);
		assertNull(body.effect(type));
	}

	@Test
	void 출처는_사람마다_따로다() {
		FakeBody first = new FakeBody();
		FakeBody second = new FakeBody();
		StatusEffectPerk strong = perk("minecraft:speed", 1);
		StatusEffectPerk weak = perk("minecraft:speed", 0);
		PerkStatusStack.grant(first, strong);
		PerkStatusStack.grant(first, weak);
		PerkStatusStack.grant(second, strong);

		PerkStatusStack.revoke(second, strong);

		assertNull(second.effect(MobEffects.SPEED), "남의 약한 출처가 이 사람에게 새어 들면 안 된다");
		assertInfinite(first, MobEffects.SPEED, 1);
	}

	@Test
	void 기록을_잊으면_잃은_증강의_등급이_되살아나지_않는다() {
		FakeBody body = new FakeBody();
		StatusEffectPerk lost = perk("minecraft:regeneration", 0);
		StatusEffectPerk strong = perk("minecraft:regeneration", 1);
		PerkStatusStack.grant(body, lost);
		// 회차 초기화처럼 상태이상을 통째로 지우는 자리.
		body.effects.clear();
		PerkStatusStack.forget(body.id());

		PerkStatusStack.grant(body, strong);
		PerkStatusStack.revoke(body, strong);

		assertNull(body.effect(MobEffects.REGENERATION));
	}

	// ------------------------------------------------------------------ 도우미

	private static StatusEffectPerk perk(String id, int amplifier) {
		return new StatusEffectPerk(Identifier.parse(id), amplifier);
	}

	private static MobEffectInstance potion(Holder<MobEffect> type, int amplifier) {
		return new MobEffectInstance(type, POTION_TICKS, amplifier);
	}

	private static MobEffectInstance hiddenOf(MobEffectInstance instance) {
		return ((MobEffectInstanceAccessor) instance).sharedfate$getHiddenEffect();
	}

	private static void assertInfinite(FakeBody body, Holder<MobEffect> type, int amplifier) {
		MobEffectInstance current = body.effect(type);
		assertNotNull(current, BuiltInRegistries.MOB_EFFECT.getKey(type.value()) + " 이 없다");
		assertTrue(current.isInfiniteDuration(), "무한이어야 한다: " + current);
		assertEquals(amplifier, current.getAmplifier(), "등급: " + current);
	}

	/** 상태이상 표만 흉내 낸 몸. 합치는 규칙은 진짜 {@code MobEffectInstance.update} 다. */
	private static final class FakeBody implements PerkStatusStack.Body {
		private final UUID id = UUID.randomUUID();
		private final Map<Holder<MobEffect>, MobEffectInstance> effects = new HashMap<>();
		private int removals;

		@Override
		public UUID id() {
			return id;
		}

		@Override
		public MobEffectInstance effect(Holder<MobEffect> type) {
			return effects.get(type);
		}

		@Override
		public void add(MobEffectInstance instance) {
			MobEffectInstance existing = effects.get(instance.getEffect());
			if (existing == null) {
				effects.put(instance.getEffect(), new MobEffectInstance(instance));
			} else {
				existing.update(instance);
			}
		}

		@Override
		public void remove(Holder<MobEffect> type) {
			removals++;
			effects.remove(type);
		}
	}
}
