package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.ConditionalEffect;
import com.sharedfate.perk.effect.DamageTakenEffect;
import com.sharedfate.perk.effect.HolderEffect;
import com.sharedfate.perk.effect.LifestealEffect;
import com.sharedfate.perk.effect.MaxHealthBonusEffect;
import com.sharedfate.perk.effect.SanctuaryEffect;
import com.sharedfate.perk.effect.ShockwaveEffect;
import com.sharedfate.perk.effect.StatusEffectPerk;
import com.sharedfate.perk.effect.SwapRallyEffect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-16 사람 지시로 대가를 걷어내거나 값을 옮긴 기본 증강들을 못박는다.
 *
 * <p>정의 파일의 숫자 한 개, 배열 한 칸으로 갈리는 변경이라 코드 쪽 시험으로는 잡히지 않는다.
 * 설명 문구도 함께 본다 — 효과를 걷어냈는데 「대신 …」 문장이 남으면 플레이어가 없는 대가를
 * 피해 다닌다.
 */
class DefaultPerkCostReliefTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	@Test
	void 포식자와_무결점은_조건이_어긋나도_대가가_없다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);

		for (String id : List.of("sharedfate:predator", "sharedfate:flawless")) {
			Perk perk = perk(id);
			ConditionalEffect conditional = assertInstanceOf(ConditionalEffect.class,
					perk.effects().get(0));
			assertEquals(1, conditional.whenTrue().size(), id + " 의 공격력 +35%");
			assertTrue(conditional.whenFalse().isEmpty(), id + " 의 거짓 쪽 대가는 걷어냈다");
			assertFalse(perk.description().contains("대신"), id);
		}
	}

	@Test
	void 정거장은_집합만_하고_나약함을_걸지_않는다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);
		Perk perk = perk("sharedfate:rally_point");

		assertEquals(1, perk.effects().size(), "on_swap 의 나약함 I 은 걷어냈다");
		assertSame(SwapRallyEffect.INSTANCE, perk.effects().get(0));
		assertFalse(perk.description().contains("나약함"));
	}

	@Test
	void 장님_거인은_허기_II_를_걸지_않는다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);
		Perk perk = perk("sharedfate:blind_giant");

		assertTrue(perk.effects().stream().noneMatch(StatusEffectPerk.class::isInstance),
				"상시 허기 II 는 걷어냈다");
		assertFalse(perk.description().contains("허기 II"));
	}

	@Test
	void 피의_대가는_최대_체력_4_를_잃는다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);
		Perk perk = perk("sharedfate:price_of_blood");

		MaxHealthBonusEffect cost = perk.effects().stream()
				.filter(MaxHealthBonusEffect.class::isInstance)
				.map(MaxHealthBonusEffect.class::cast)
				.findFirst().orElseThrow();
		assertEquals(-4.0F, cost.amount(), 1.0e-6);
		assertTrue(perk.description().contains("최대 체력이 4 감소"));
	}

	@Test
	void 흡혈귀는_준_피해의_30퍼센트를_회복한다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);
		Perk perk = perk("sharedfate:vampire");

		LifestealEffect lifesteal = perk.effects().stream()
				.filter(LifestealEffect.class::isInstance)
				.map(LifestealEffect.class::cast)
				.findFirst().orElseThrow();
		assertEquals(0.30, lifesteal.fraction(), 1.0e-9);
		assertTrue(perk.description().contains("30%"));
	}

	/** 나머지 팀원 쪽이 대가(×1.10)에서 이점(×0.9)으로 바뀌었다. */
	@Test
	void 호위의_나머지_팀원은_받는_피해가_10퍼센트_줄어든다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);
		Perk perk = perk("sharedfate:bodyguard");

		HolderEffect holder = perk.effects().stream()
				.filter(HolderEffect.class::isInstance)
				.map(HolderEffect.class::cast)
				.findFirst().orElseThrow();
		DamageTakenEffect others = assertInstanceOf(DamageTakenEffect.class,
				holder.onOthers().get(0));
		assertEquals(0.9, others.multiplier(), 1.0e-9);
		assertTrue(perk.description().contains("10% 감소"));
		assertFalse(perk.description().contains("대신"));
	}

	@Test
	void 파문은_25칸_안에서_켜진다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);
		Perk perk = perk("sharedfate:ripple");

		ShockwaveEffect shockwave = assertInstanceOf(ShockwaveEffect.class, perk.effects().get(0));
		assertEquals(25, shockwave.distance());
		assertEquals(10, shockwave.radius());
		assertEquals(2, shockwave.soloRadius());
		assertEquals(2.4, shockwave.strength(), 1.0e-9);
		assertEquals(5, shockwave.intervalSeconds());
		assertTrue(perk.description().contains("25칸"));
		assertTrue(perk.description().contains("10칸"));
		assertTrue(perk.description().contains("2칸"));
	}

	@Test
	void 성역은_25칸에서_80퍼센트_느리게_하고_흩어지면_10퍼센트_빨라진다(@TempDir Path dir)
			throws IOException {
		loadDefaultPool(dir);
		Perk perk = perk("sharedfate:sanctuary");

		SanctuaryEffect sanctuary = assertInstanceOf(SanctuaryEffect.class, perk.effects().get(0));
		assertEquals(25.0, sanctuary.distance(), 1.0e-9);
		assertEquals(25.0, sanctuary.radius(), 1.0e-9);
		assertEquals(0.8, sanctuary.slow(), 1.0e-9);
		assertEquals(0.1, sanctuary.hasteWhenApart(), 1.0e-9);
		assertTrue(perk.description().contains("80%"));
		assertTrue(perk.description().contains("10% 빨라집니다"));
	}

	@Test
	void 작아진_몸은_화력_유형이다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);

		assertEquals(List.of(PerkSetType.POWER), perk("sharedfate:small_frame").setTypes());
	}

	@Test
	void 대가를_걷어낸_증강의_설명에는_그_대가가_없다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);

		assertFalse(perk("sharedfate:lucky_day").description().contains("안 나올"));
		assertFalse(perk("sharedfate:void_step").description().contains("낙하 피해"));
		assertFalse(perk("sharedfate:ascetic").description().contains("다른 증강으로도"));
	}

	private static Perk perk(String id) {
		return PerkRegistry.byId(id).orElseThrow(() -> new AssertionError(id + " 를 찾을 수 없다"));
	}

	/** 번들 기본 풀을 임시 폴더에 풀어 레지스트리에 올린다. */
	private static void loadDefaultPool(Path dir) throws IOException {
		try (InputStream bundled = DefaultPerkCostReliefTest.class
				.getResourceAsStream("/sharedfate-perks-default.json")) {
			assertNotNull(bundled, "번들에 sharedfate-perks-default.json 이 없다");
			Files.copy(bundled, dir.resolve(PerkRegistry.FILE_NAME));
		}
		PerkRegistry.load(dir);
	}
}
