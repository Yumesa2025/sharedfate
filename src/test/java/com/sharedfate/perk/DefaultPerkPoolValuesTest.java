package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.AttributeEffect;
import com.sharedfate.perk.effect.RallyShardEffect;
import com.sharedfate.perk.effect.SwapBlockEffect;
import com.sharedfate.perk.effect.DropReplaceEffect;
import com.sharedfate.perk.effect.ConditionalEffect;
import com.sharedfate.perk.effect.DamageTakenEffect;
import com.sharedfate.perk.effect.DamageTakenFromEffect;
import com.sharedfate.perk.effect.DoubleJumpEffect;
import com.sharedfate.perk.effect.HolderEffect;
import com.sharedfate.perk.effect.ItemGrantEffect;
import com.sharedfate.perk.effect.MaxHealthBonusEffect;
import com.sharedfate.perk.effect.NoDamageBoostEffect;
import com.sharedfate.perk.effect.OnKillEffect;
import com.sharedfate.perk.effect.OreExchangeEffect;
import com.sharedfate.perk.effect.RarityGrantEffect;
import com.sharedfate.perk.effect.StatusEffectPerk;
import com.sharedfate.perk.effect.WeaponDamageEffect;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 번들 기본 증강 풀({@code sharedfate-perks-default.json})의 값들이 실제로 그 값 그대로
 * 들어갔는지 본다.
 *
 * <p>값을 다시 조정할 때 회귀를 잡는다.
 */
class DefaultPerkPoolValuesTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	@Test
	void 얼음_발은_실제로_미끄러워지는_부호다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:icy_feet");
		AttributeEffect friction = attributeEffect(perk, "minecraft:friction_modifier");

		// clamp(1 - (1-블록마찰) * modifier, 0, 1) 에서 modifier = 1+amount 다.
		// amount 가 양수면 modifier > 1 이 되어 오히려 안 미끄러워진다 — 반드시 음수여야 한다.
		//
		// amount=-1.0 은 modifier=0 을 만들어 어느 블록에서든 결과가 clamp 상한인 1 로
		// 못박힌다 — -0.5(보통 땅 0.6→0.8, 기준보다 +0.2)에서 미끄러움 증가분(1-modifier)을
		// 두 배로 올린 값이자, 이 공식이 낼 수 있는 최댓값이다.
		assertTrue(friction.amount() < 0.0, "부호가 양수면 오히려 덜 미끄러워진다");
		assertEquals(-1.0, friction.amount(), 1.0e-9);
	}

	@Test
	void 불굴은_50퍼센트_경계다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:unbroken");
		ConditionalEffect conditional = assertInstanceOf(ConditionalEffect.class, perk.effects().get(0));

		assertEquals(ConditionalEffect.Condition.HEALTH_BELOW, conditional.condition());
		assertEquals(0.5, conditional.threshold(), 1.0e-9);
	}

	@Test
	void 유리_세계는_받는_피해_2점5배다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:glass_world");
		DamageTakenEffect damageTaken = assertInstanceOf(DamageTakenEffect.class, perk.effects().get(1));

		assertEquals(2.5, damageTaken.multiplier(), 1.0e-9);
	}

	/**
	 * 제왕은 <b>고른 사람에게 고정</b>이고 대가는 −15% 다.
	 *
	 * <p>예전에는 1분마다 무작위로 옮겨 다녔다. 가호 유형이 「고른 사람 하나」로 정해지면서
	 * {@code fixed_to_owner} 로 바뀌었고, 그때부터 {@code rotate_ticks}·{@code min_hold_ticks}·
	 * {@code pass_on_hurt} 는 <b>읽히기는 하지만 무시되는</b> 값이 되어 서버가 뜰 때마다 경고를
	 * 남겼다. 2026-09-09 에 정의에서 걷어냈다.
	 */
	@Test
	void 제왕과_신하는_고른_사람에게_고정이고_대가는_15퍼센트다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:king_and_subjects");
		HolderEffect holder = assertInstanceOf(HolderEffect.class, perk.effects().get(0));

		assertTrue(holder.fixedToOwner(), "고른 사람에게 고정이어야 한다");
		AttributeEffect penalty = assertInstanceOf(AttributeEffect.class, holder.onOthers().get(0));
		assertEquals(-0.15, penalty.amount(), 1.0e-9);
	}

	@Test
	void 버프_돌리기의_대가에는_실명이_없고_2초다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:rotating_buff");
		HolderEffect holder = assertInstanceOf(HolderEffect.class, perk.effects().get(0));

		assertEquals(2, holder.onPass().size(), "나약함·채굴 피로 둘뿐, 실명은 빠졌다");
		for (OnKillEffect.Grant grant : holder.onPass()) {
			assertEquals(40, grant.durationTicks(), "2초 = 40틱");
		}
	}

	@Test
	void 비상식량은_20개다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:emergency_ration");
		ItemGrantEffect grant = assertInstanceOf(ItemGrantEffect.class, perk.effects().get(0));

		assertEquals(20, grant.entries().get(0).count());
	}

	@Test
	void 손에_쥔_목숨은_토템_3개다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:life_in_hand");
		ItemGrantEffect grant = assertInstanceOf(ItemGrantEffect.class, perk.effects().get(0));

		assertEquals(3, grant.entries().get(0).count());
	}

	@Test
	void 광전사는_최대_체력_10도_준다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:berserker");
		boolean hasBonus = perk.effects().stream().anyMatch(e ->
				e instanceof MaxHealthBonusEffect bonus && bonus.amount() == 10.0F);

		assertTrue(hasBonus, "최대 체력 +10 이 있어야 한다");
	}

	@Test
	void 허공답보는_점프력_50퍼센트도_준다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:void_step");
		AttributeEffect jump = attributeEffect(perk, "minecraft:jump_strength");

		assertEquals(0.5, jump.amount(), 1.0e-9);
	}

	/**
	 * 두 번째 점프는 바닐라 기본 점프의 1.5배다.
	 *
	 * <p>{@code LivingEntity.BASE_JUMP_POWER} 가 0.42 이므로 0.42 × 1.5 = 0.63 이다.
	 */
	@Test
	void 허공답보의_두_번째_점프는_기본의_1_5배다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:void_step");
		DoubleJumpEffect jump = perk.effects().stream()
				.filter(DoubleJumpEffect.class::isInstance)
				.map(DoubleJumpEffect.class::cast)
				.findFirst()
				.orElseThrow(() -> new AssertionError("double_jump 효과가 없다"));

		assertEquals(0.42 * 1.5, jump.power(), 1.0e-9);
		assertEquals(DoubleJumpEffect.DEFAULT_POWER, jump.power(), 1.0e-9,
				"power 를 적지 않았을 때의 기본값도 같아야 한다");
	}

	/**
	 * 낙하 피해는 1.2배다.
	 *
	 * <p>{@code add_multiplied_total} 이라 최종 배율은 {@code 1 + amount} 다. 1.2배를 만들려면
	 * {@code amount} 가 0.2 여야 한다.
	 */
	@Test
	void 허공답보의_낙하_피해는_1_2배다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:void_step");
		AttributeEffect fall = attributeEffect(perk, "minecraft:fall_damage_multiplier");

		assertEquals(0.2, fall.amount(), 1.0e-9);
	}

	@Test
	void 굴착기는_속성으로_3배_빨라지고_공격력_15퍼센트를_잃는다(@TempDir Path dir) throws IOException {
		// mining_speed 로는 절대 빨라지지 않는다 — 그 타입은 서버에서만 계산되고, 26.2 에서
		// 블록이 부서지는 시점은 클라이언트의 STOP_DESTROY_BLOCK 이 정한다.
		// block_break_speed 는 setSyncable(true) 라 클라이언트까지 내려가 양쪽이 같은 값을 본다.
		// 자세한 것은 PlayerMiningSpeedMixin 과 MiningSpeedEffect 의 클래스 주석에 있다.
		Perk perk = perk(dir, "sharedfate:excavator");
		AttributeEffect speed = attributeEffect(perk, "minecraft:block_break_speed");
		AttributeEffect damage = attributeEffect(perk, "minecraft:attack_damage");

		// 기본값 1.0 에 add_multiplied_total 이라 1.0 × (1 + 2.0) = 3.0 이다.
		assertEquals(2.0, speed.amount(), 1.0e-9);
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, speed.operation());
		assertEquals(-0.15, damage.amount(), 1.0e-9);
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, damage.operation());

		// 서버 전용 경로에는 아무것도 남기지 않는다. 남아 있으면 그만큼이 다시 먹히지 않는 효과다.
		com.sharedfate.team.TeamState state = com.sharedfate.team.TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.add("sharedfate:excavator");
		assertEquals(1.0,
				PerkBlockBreaks.multiplierFor(state,
						net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()),
				1.0e-6, "굴착기에 mining_speed 효과가 남아 있으면 안 된다");
		assertEquals(1.0,
				PerkBlockBreaks.multiplierFor(state,
						net.minecraft.world.level.block.Blocks.ANCIENT_DEBRIS.defaultBlockState()),
				1.0e-6, "광석 예외는 사라졌다");
	}

	@Test
	void 기본_풀에는_빨라지는_mining_speed_가_하나도_없다(@TempDir Path dir) throws IOException {
		// mining_speed 는 느리게 하는 데만 쓸 수 있다. 1 보다 큰 배율은 조용히 아무 일도 하지
		// 않으므로, 새 증강이 그 함정에 다시 빠지면 여기서 걸린다. 빠르게 하려면
		// minecraft:block_break_speed 속성을 써야 한다.
		loadDefaultPool(dir);
		for (Perk perk : PerkRegistry.all()) {
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof com.sharedfate.perk.effect.MiningSpeedEffect mining) {
					assertTrue(mining.multiplier() <= 1.0,
							perk.name() + " 의 mining_speed 가 " + mining.multiplier()
									+ " 다. 빨라지는 쪽은 효과가 없으니 block_break_speed 속성을 쓸 것");
				}
			}
		}
	}

	@Test
	void 포식은_허기10_체력8이다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:devour");
		OnKillEffect onKill = assertInstanceOf(OnKillEffect.class, perk.effects().get(0));

		assertEquals(10, onKill.food());
		assertEquals(8.0F, onKill.health());
	}

	@Test
	void 타지_않는_살갗은_화염만_막고_해독제_id가_아니다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:fireproof_skin");
		DamageTakenFromEffect fireImmune =
				assertInstanceOf(DamageTakenFromEffect.class, perk.effects().get(0));

		assertEquals(0.0, fireImmune.multiplier(), 1.0e-9);
		assertFalse(PerkRegistry.byId("sharedfate:antidote").isPresent(), "예전 id는 더 없다");
	}

	@Test
	void 비옥한_땅은_밀을_황금_당근으로_바꾼다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:fertile_ground");
		DropReplaceEffect replace =
				assertInstanceOf(DropReplaceEffect.class, perk.effects().get(0));

		assertEquals("minecraft:wheat", replace.fromId().toString());
		assertEquals("minecraft:golden_carrot", replace.itemId().toString());
		assertEquals(1, replace.min());
		assertEquals(3, replace.max());
	}

	@Test
	void 삽질의_대가는_다른_무기_공격력을_고정하지_않고_증가_차단을_쓴다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:shovel_master");
		WeaponDamageEffect weapon = assertInstanceOf(WeaponDamageEffect.class, perk.effects().get(0));
		boolean hasBan = perk.effects().stream().anyMatch(e -> e instanceof NoDamageBoostEffect);

		assertNull(weapon.othersDamage(), "이제 다른 무기를 1로 고정하지 않는다");
		assertTrue(hasBan);
	}

	@Test
	void 나무꾼의_욕심은_ore_exchange_하나뿐이다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:woodcutters_greed");

		assertEquals(1, perk.effects().size());
		assertInstanceOf(OreExchangeEffect.class, perk.effects().get(0));
	}

	@Test
	void 숨은_재능과_하늘의_은총이_있다(@TempDir Path dir) throws IOException {
		Perk hidden = perk(dir, "sharedfate:hidden_talent");
		RarityGrantEffect hiddenGrant = assertInstanceOf(RarityGrantEffect.class, hidden.effects().get(0));
		assertEquals(PerkRarity.GOLD, hiddenGrant.rarity());
		assertEquals(PerkRarity.SILVER, hidden.rarity());

		Perk blessing = perk(dir, "sharedfate:blessing_of_heaven");
		RarityGrantEffect blessingGrant =
				assertInstanceOf(RarityGrantEffect.class, blessing.effects().get(0));
		assertEquals(PerkRarity.PRISM, blessingGrant.rarity());
		assertEquals(PerkRarity.GOLD, blessing.rarity());
	}

	/**
	 * 두 증강에는 <b>대가가 없다.</b>
	 *
	 * <p>이 시험이 지키는 것은 「효과가 지급 하나뿐인가」다. 대가를 다시 붙이고 싶어지면
	 * 여기가 먼저 깨진다.
	 */
	@Test
	void 숨은_재능과_하늘의_은총에는_대가가_없다(@TempDir Path dir) throws IOException {
		Perk hidden = perk(dir, "sharedfate:hidden_talent");
		assertEquals(1, hidden.effects().size(), "지급 하나뿐이다");
		assertTrue(hidden.effects().stream().noneMatch(e -> e instanceof DamageTakenEffect),
				"받는 피해를 늘리지 않는다");
		assertTrue(hidden.effects().stream().noneMatch(e -> e instanceof MaxHealthBonusEffect),
				"최대 체력을 깎지 않는다");

		Perk blessing = perk(dir, "sharedfate:blessing_of_heaven");
		assertEquals(1, blessing.effects().size(), "지급 하나뿐이다");
		assertTrue(blessing.effects().stream().noneMatch(e -> e instanceof DamageTakenEffect),
				"받는 피해를 늘리지 않는다");
		assertTrue(blessing.effects().stream().noneMatch(e -> e instanceof MaxHealthBonusEffect),
				"최대 체력을 깎지 않는다");
	}

	// ------------------------------------------------------------------ 도우미

	private static AttributeEffect attributeEffect(Perk perk, String attributeId) {
		return perk.effects().stream()
				.filter(e -> e instanceof AttributeEffect attribute
						&& attribute.attributeId().toString().equals(attributeId))
				.map(AttributeEffect.class::cast)
				.findFirst()
				.orElseThrow(() -> new AssertionError(attributeId + " 효과가 없다: " + perk.id()));
	}

	/** 번들 기본 풀을 임시 폴더에 풀어 레지스트리에 올린다. 이미 올렸으면 아무 일도 하지 않는다. */
	private static void loadDefaultPool(Path dir) throws IOException {
		Path target = dir.resolve(PerkRegistry.FILE_NAME);
		if (Files.exists(target)) {
			return;
		}
		try (InputStream bundled = DefaultPerkPoolValuesTest.class
				.getResourceAsStream("/sharedfate-perks-default.json")) {
			Files.copy(bundled, target);
		}
		PerkRegistry.load(dir);
	}

	private static Perk perk(Path dir, String id) throws IOException {
		loadDefaultPool(dir);
		return PerkRegistry.byId(id).orElseThrow(() -> new AssertionError(id + " 를 찾을 수 없다"));
	}

	/**
	 * 「아가미」는 물에 있을 때만 켜지고, 물 이동 속도는 조건 밖에 있다.
	 *
	 * <p>물 이동 효율 속성은 <b>물속에서만 뜻이 있는 값</b>이라 조건 안에 넣을 필요가 없다.
	 * 조건 안으로 옮기면 반 초마다 수정자를 뗐다 붙이게 되어 얻는 것 없이 일만 늘어난다.
	 * 반대로 재생을 조건 밖으로 꺼내면 땅에서도 계속 회복되어 증강이 통째로 달라진다.
	 */
	@Test
	void 아가미는_물에_있을_때만_회복한다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:gills");

		ConditionalEffect conditional = assertInstanceOf(ConditionalEffect.class,
				perk.effects().get(0));
		assertEquals(ConditionalEffect.Condition.IN_WATER, conditional.condition());
		assertTrue(conditional.whenFalse().isEmpty(), "땅에서는 아무것도 붙지 않는 것이 대가다");
		assertInstanceOf(StatusEffectPerk.class, conditional.whenTrue().get(0));

		// 물속에서만 뜻이 있는 속성이라 조건 밖에 둔다. 없으면 여기서 걸린다.
		AttributeEffect water = attributeEffect(perk, "minecraft:water_movement_efficiency");
		assertEquals(0.15, water.amount(), 1.0e-9);
	}

	/**
	 * 「소집의 조각」의 교환 막힘은 <b>조용한 쪽</b>이어야 한다.
	 *
	 * <p>{@code silent} 을 빠뜨려도 증강은 그대로 읽히고 개수 시험도 통과한다. 그런데 그러면
	 * 자리만 안 바뀌고 부수효과는 주기마다 계속 돌아, 「시차」를 함께 가진 팀은 힘·재생만
	 * 공짜로 받고 「정거장」을 함께 가진 팀은 나약함만 계속 문다. 코드가 아니라 <b>정의 한
	 * 글자</b>로 갈리는 자리라 여기서 못박는다.
	 */
	@Test
	void 소집의_조각은_조용한_막힘을_쓴다(@TempDir Path dir) throws IOException {
		Perk perk = perk(dir, "sharedfate:rally_shard");

		SwapBlockEffect block = perk.effects().stream()
				.filter(SwapBlockEffect.class::isInstance)
				.map(SwapBlockEffect.class::cast)
				.findFirst()
				.orElseThrow(() -> new AssertionError("swap_block 이 없다"));
		assertSame(SwapBlockEffect.SILENT, block, "silent 를 빠뜨리면 부수효과만 주기마다 돈다");

		RallyShardEffect shard = perk.effects().stream()
				.filter(RallyShardEffect.class::isInstance)
				.map(RallyShardEffect.class::cast)
				.findFirst()
				.orElseThrow(() -> new AssertionError("rally_shard 가 없다"));
		assertEquals(60, shard.freezeTicks(), "굳는 시간은 3초다");
		assertEquals(4800, shard.cooldownTicks(), "쿨타임은 4분이다");
	}

	/**
	 * 기본 풀이 <b>한 개도 버려지지 않고</b> 전부 읽힌다.
	 *
	 * <p>이 시험이 지키는 것은 개수가 아니라 <b>조용한 실패</b>다. {@link PerkRegistry} 는
	 * 읽을 수 없는 증강을 만나면 예외를 던지지 않고 그 하나만 건너뛴다 — 정의가 잘못됐다고
	 * 게임이 멈추면 안 되기 때문이다. 그래서 <b>새 효과 타입을 만들고
	 * {@link PerkEffectType} 에 등록하는 줄을 빠뜨리면</b> 빌드도 통과하고 서버도 뜨는데
	 * 그 효과를 쓰는 증강만 풀에서 사라진다. 서버 로그의 「건너뜀 N개」를 사람이 보지 않으면
	 * 알아챌 방법이 없다.
	 *
	 * <p>등급별 개수까지 함께 세는 것은 증강을 더할 때 등급을 잘못 적는 것을 잡기 위해서다.
	 */
	/**
	 * 설명이 길면 <b>그 줄의 카드 아이콘이 셋 다 사라진다.</b>
	 *
	 * <p>선택 화면은 아이콘 크기를 큰 것부터 대 본다({@code PerkOfferScreen.ICON_SIZES} =
	 * 32 → 16 → <b>0</b>). 설명이 길어지면 카드가 세로로 길어지고, 셋 중 <b>가장 긴 한 장</b>이
	 * 남은 높이를 넘기는 순간 아이콘 크기가 0 으로 떨어진다. 아이콘 크기는 줄 전체가 함께 쓰는
	 * 값이라 <b>한 장 때문에 나머지 두 장의 그림도 같이 사라진다.</b>
	 *
	 * <p>빌드도 시험도 통과하고 로그도 조용하다. 카드를 눈으로 볼 때만 알 수 있어 여기서 막는다.
	 * 실제로 2026-09-11 에 설명 세 장이 길어져 아이콘이 통째로 사라졌다.
	 *
	 * <p>{@value #MAX_DESCRIPTION_LENGTH} 자는 「지금 가장 긴 것」이 아니라 <b>여유를 두고 잡은
	 * 상한</b>이다. 값을 올리려거든 먼저 낮은 해상도에서 카드 셋을 띄워 아이콘이 남는지 보라.
	 */
	@Test
	void 증강_설명은_카드_아이콘을_밀어낼_만큼_길지_않다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);

		List<String> 너무긴것 = PerkRegistry.all().stream()
				.filter(perk -> perk.description().length() > MAX_DESCRIPTION_LENGTH)
				.map(perk -> perk.name() + " " + perk.description().length() + "자")
				.toList();

		assertTrue(너무긴것.isEmpty(),
				"설명이 " + MAX_DESCRIPTION_LENGTH + "자를 넘으면 카드 아이콘이 사라진다: " + 너무긴것);
	}

	/** 증강 설명의 상한. 까닭은 위 시험 문서에 있다. */
	private static final int MAX_DESCRIPTION_LENGTH = 85;

	@Test
	void 기본_풀은_하나도_버려지지_않고_읽힌다(@TempDir Path dir) throws IOException {
		loadDefaultPool(dir);

		assertEquals(96, PerkRegistry.all().size(),
				"파일에 적힌 수와 읽힌 수가 다르면 효과 타입이 등록되지 않은 것이다");

		long silver = PerkRegistry.all().stream().filter(p -> p.rarity() == PerkRarity.SILVER).count();
		long gold = PerkRegistry.all().stream().filter(p -> p.rarity() == PerkRarity.GOLD).count();
		long prism = PerkRegistry.all().stream().filter(p -> p.rarity() == PerkRarity.PRISM).count();
		// 세트 「개척」이 들어오며 실버 +1(유적 감별사) · 골드 +1(흥정의 달인) 이 됐다.
		assertEquals(35, silver, "실버");
		assertEquals(36, gold, "골드");
		assertEquals(25, prism, "프리즘");

		// 등록을 빠뜨리면 여기서 먼저 걸린다.
		for (String id : new String[] {"sharedfate:grounded_guard", "sharedfate:arcane_workshop",
				"sharedfate:diamond_sundial", "sharedfate:final_movement",
				"sharedfate:rally_shard", "sharedfate:bloodlust",
				"sharedfate:cushion", "sharedfate:haggling_master",
				"sharedfate:ruin_appraiser"}) {
			assertTrue(PerkRegistry.byId(id).isPresent(), id + " 가 풀에서 빠졌다");
		}
	}
}
