package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.DamageWardEffect;
import com.sharedfate.perk.effect.FlightCharmEffect;
import com.sharedfate.perk.effect.HolderEffect;
import com.sharedfate.perk.effect.InventorySlotsEffect;
import com.sharedfate.perk.effect.SwapExemptEffect;
import com.sharedfate.perk.effect.WeaponKnockbackEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「가호 3」의 강화가 <b>가호 증강 전부에서</b> 실제로 값을 바꾸는지 못박는다.
 *
 * <h2>왜 이 시험이 있는가</h2>
 * <p>가호 유형이 처음 들어갔을 때, 강화를 읽는 곳이 {@link HolderEffect} 와
 * {@link InventorySlotsEffect} 두 군데뿐이었다. 나머지 다섯 장(전속 광부·몽둥이찜질·열외·
 * 호위·비행 부적)은 강화값을 적을 칸조차 없어 <b>「가호 3」에서도 평소 값 그대로 돌았다.</b>
 * 설계표에는 일곱 장 전부의 강화값이 적혀 있었으므로, 문서와 코드가 어긋난 채로 빌드도 시험도
 * 전부 통과했다.
 *
 * <p>같은 일이 되풀이되지 않게 <b>기본 풀에 실제로 들어간 값</b>을 본다. 새 가호 증강을 넣고
 * 강화값을 빠뜨리면 {@link #가호_증강은_전부_강화값을_가진다} 가 먼저 깨진다.
 *
 * <p>「가호 4」의 「전원에게」는 {@code PerkBlessingSetTest}·{@code HolderModeTest} 가 본다.
 * 여기서는 <b>값</b>만 다룬다.
 */
class BlessingAmplifiedValuesTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	/**
	 * 가호 일곱 장 모두 「가호 3」에서 무언가가 달라져야 한다.
	 *
	 * <p>무엇이 달라지는지는 증강마다 다르므로 <b>「평소 값과 다른가」</b> 하나만 본다. 값
	 * 자체는 아래 시험들이 하나씩 확인한다.
	 */
	@Test
	void 가호_증강은_짐꾼만_빼고_전부_강화값을_가진다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);
		List<String> 밋밋한것 = new ArrayList<>();
		int 본것 = 0;
		for (Perk perk : PerkRegistry.all()) {
			if (!perk.hasSetType(PerkSetType.BLESSING)) {
				continue;
			}
			본것++;
			// 짐꾼만은 강화할 자리가 없다. 이미 마지막 줄을 열어 9×7 이 가득 차기 때문이다.
			// 「칸을 더 준다」는 강화는 만들 수가 없다.
			if (강화없이도된다.contains(perk.id())) {
				continue;
			}
			if (!강화가있다(perk)) {
				밋밋한것.add(perk.name() + "(" + perk.id() + ")");
			}
		}
		assertEquals(7, 본것, "가호 증강은 일곱 장이다");
		assertTrue(밋밋한것.isEmpty(),
				"「가호 3」에서 아무것도 달라지지 않는 가호 증강이 있다: " + 밋밋한것);
	}

	/** 강화값이 없어도 되는 가호. 이유는 위 시험 본문에 적어 두었다. */
	private static final List<String> 강화없이도된다 = List.of("sharedfate:porter");

	/** 전속 광부 — 채굴 ×5 가 ×6 이 된다. {@code holder} 의 강화 묶음을 쓴다. */
	@Test
	void 전속_광부는_강화_묶음을_가진다(@TempDir Path dir) throws IOException {
		HolderEffect holder = (HolderEffect) 효과(dir, "sharedfate:shared_pickaxe",
				HolderEffect.class);
		assertFalse(holder.onHolderAmplified().isEmpty(),
				"강화 묶음이 비어 있으면 「가호 3」에서 평소 값 그대로 돈다");
	}

	/** 몽둥이찜질 — 넉백 10 이 20 이 된다. */
	@Test
	void 몽둥이찜질의_넉백이_두_배가_된다(@TempDir Path dir) throws IOException {
		WeaponKnockbackEffect effect = (WeaponKnockbackEffect) 효과(dir, "sharedfate:cudgel",
				WeaponKnockbackEffect.class);
		assertEquals(10.0F, effect.knockback(), 1.0e-6);
		assertEquals(20.0F, effect.amplifiedKnockback(), 1.0e-6);
		// 바닐라가 같은 자리에서 2 로 나누므로 실제로 돌려주는 값은 절반이다.
		assertEquals(5.0F, effect.attackKnockback(false), 1.0e-6);
		assertEquals(10.0F, effect.attackKnockback(true), 1.0e-6);
	}

	/** 호위 — 쿨타임 6초가 3초가 된다. 이쪽은 값이 <b>작아지는</b> 강화다. */
	@Test
	void 호위의_쿨타임이_절반이_된다(@TempDir Path dir) throws IOException {
		DamageWardEffect effect = (DamageWardEffect) 효과(dir, "sharedfate:bodyguard",
				DamageWardEffect.class);
		assertEquals(120, effect.cooldownTicks(), "6초");
		assertEquals(60, effect.amplifiedCooldownTicks(), "3초");
		assertEquals(60, effect.amplified().cooldownTicks(),
				"강화본은 강화 쿨타임을 평소 값으로 삼는다");
	}

	/** 열외 — 교환 때 붙는 이속이 +30% 에서 +60% 가 된다. */
	@Test
	void 열외의_이속이_두_배가_된다(@TempDir Path dir) throws IOException {
		SwapExemptEffect effect = (SwapExemptEffect) 효과(dir, "sharedfate:swap_exempt",
				SwapExemptEffect.class);
		assertEquals(0.30, effect.speedBonus(), 1.0e-6);
		assertEquals(0.60, effect.amplifiedSpeedBonus(), 1.0e-6);
	}

	/** 비행 부적 — 10초가 15초가 된다. 쿨타임은 그대로 1분이다. */
	@Test
	void 비행_부적의_시간이_길어진다(@TempDir Path dir) throws IOException {
		FlightCharmEffect effect = (FlightCharmEffect) 효과(dir, "sharedfate:flight_charm",
				FlightCharmEffect.class);
		assertEquals(200, effect.flightTicks(), "10초");
		assertEquals(300, effect.amplifiedFlightTicks(), "15초");
		assertEquals(1200, effect.cooldownTicks(), "쿨타임은 강화되지 않는다");
	}

	/**
	 * 짐꾼 — 한 줄을 통째로 여는 아홉 칸이고, 그 위로는 늘어날 자리가 없다.
	 *
	 * <p>기본 두 줄에 한 줄을 더하면 9×7 로 인벤토리가 가득 찬다. 강화가 더 줄 칸이 없다.
	 */
	@Test
	void 짐꾼은_아홉_칸이고_더_늘어날_자리가_없다(@TempDir Path dir) throws IOException {
		InventorySlotsEffect effect = (InventorySlotsEffect) 효과(dir, "sharedfate:porter",
				InventorySlotsEffect.class);
		assertEquals(9, effect.amount());
		assertEquals(9, effect.amplifiedAmount(), "강화해도 더 열 칸이 없다");
	}

	/**
	 * 강화값을 안 적은 정의는 평소 값을 그대로 쓴다.
	 *
	 * <p>가호가 아닌 증강이 이 효과 타입을 쓰게 되는 날, 강화 칸이 없다고 0 이 되거나 정의가
	 * 버려지면 안 된다.
	 */
	@Test
	void 강화값을_안_적으면_평소_값과_같다() {
		WeaponKnockbackEffect knockback = new WeaponKnockbackEffect(
				net.minecraft.resources.Identifier.withDefaultNamespace("stick"), 7.0F, true);
		assertEquals(7.0F, knockback.amplifiedKnockback(), 1.0e-6);

		DamageWardEffect ward = new DamageWardEffect(160);
		assertEquals(160, ward.amplifiedCooldownTicks());
		assertSame(ward, ward.amplified(), "바뀔 것이 없으면 새로 만들지 않는다");

		FlightCharmEffect charm = new FlightCharmEffect(120, 900);
		assertEquals(120, charm.amplifiedFlightTicks());

		SwapExemptEffect exempt = new SwapExemptEffect("sharedfate:test", 0, 200, 0.2);
		assertEquals(0.2, exempt.amplifiedSpeedBonus(), 1.0e-6);
	}

	// ------------------------------------------------------------------ 도우미

	/** 이 증강이 「가호 3」에서 무언가 달라지는가. */
	private static boolean 강화가있다(Perk perk) {
		for (PerkEffect effect : perk.effects()) {
			if (effect instanceof HolderEffect holder && !holder.onHolderAmplified().isEmpty()) {
				return true;
			}
			if (effect instanceof InventorySlotsEffect slots
					&& slots.amplifiedAmount() != slots.amount()) {
				return true;
			}
			if (effect instanceof WeaponKnockbackEffect knockback
					&& knockback.amplifiedKnockback() != knockback.knockback()) {
				return true;
			}
			if (effect instanceof DamageWardEffect ward
					&& ward.amplifiedCooldownTicks() != ward.cooldownTicks()) {
				return true;
			}
			if (effect instanceof FlightCharmEffect charm
					&& charm.amplifiedFlightTicks() != charm.flightTicks()) {
				return true;
			}
			if (effect instanceof SwapExemptEffect exempt
					&& exempt.amplifiedSpeedBonus() != exempt.speedBonus()) {
				return true;
			}
		}
		return false;
	}

	private static PerkEffect 효과(Path dir, String perkId, Class<?> type) throws IOException {
		loadDefaultPool(dir);
		Perk perk = PerkRegistry.byId(perkId)
				.orElseThrow(() -> new AssertionError(perkId + " 를 찾을 수 없다"));
		for (PerkEffect effect : perk.effects()) {
			if (type.isInstance(effect)) {
				return effect;
			}
		}
		throw new AssertionError(perkId + " 에 " + type.getSimpleName() + " 이 없다");
	}

	/** 번들 기본 풀을 임시 폴더에 풀어 레지스트리에 올린다. */
	private static void loadDefaultPool(Path dir) throws IOException {
		Path target = dir.resolve(PerkRegistry.FILE_NAME);
		if (Files.exists(target)) {
			return;
		}
		try (InputStream bundled = BlessingAmplifiedValuesTest.class
				.getResourceAsStream("/sharedfate-perks-default.json")) {
			Files.copy(bundled, target);
		}
		PerkRegistry.load(dir);
	}
}
