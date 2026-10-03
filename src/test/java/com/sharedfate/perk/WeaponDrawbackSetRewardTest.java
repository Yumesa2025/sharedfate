package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.AttributeEffect;
import com.sharedfate.perk.effect.ConditionalEffect;
import com.sharedfate.perk.effect.DamageDealtEffect;
import com.sharedfate.perk.effect.HolderEffect;
import com.sharedfate.perk.effect.MobDamageEffect;
import com.sharedfate.perk.effect.MobHealthEffect;
import com.sharedfate.perk.effect.NoAttackDamageLossEffect;
import com.sharedfate.perk.effect.OnTeamHurtEffect;
import com.sharedfate.perk.effect.PeriodicEffect;
import com.sharedfate.perk.effect.StatusEffectPerk;
import com.sharedfate.team.TeamState;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세트 「무기 3단계 — 공격력이 깎이지 않는다」.
 *
 * <p>무기 유형 증강을 셋 모으면 <b>팀원의 공격력을 깎는 효과가 전부 무효가 된다.</b> 방어
 * 3단계와 달리 정의 파일에 무엇을 지울지 적어 두지 않는다. 판정은 효과 객체가 실제로 무엇을
 * 하는지만 보고 내리므로({@link NoAttackDamageLossEffect#reduces}) 앞으로 새로 넣는 증강도
 * 코드를 고치지 않고 함께 걸린다.
 *
 * <h2>이 시험이 가장 힘주어 지키는 것</h2>
 * <p><b>올리는 쪽은 하나도 건드리지 않는다.</b> 「깎는 것을 없앤다」가 「공격력에 손대는 것을
 * 전부 없앤다」로 새어 나가면 무기 세트를 모을수록 약해지는 사고가 난다. 그래서 이 파일은
 * 「사라진다」만큼 「안 사라진다」를 확인한다.
 *
 * <h2>살아 있는 플레이어가 필요한 자리</h2>
 * <p>속성 수정자를 실제로 붙였다 떼는 {@code PerkManager.refreshPlayer}·
 * {@code ConditionalPerkManager.refreshPlayer} 와, 잠깐 얹는 {@code TemporaryPerkGrants.grant} 는
 * {@code ServerPlayer} 가 있어야 부를 수 있다. 그래서 그 자리들이 <b>실제로 보고 갈라지는
 * 판정</b>인 {@link NoAttackDamageLossEffect.Gate#suppresses} 를 직접 시험한다.
 *
 * <p>무기가 공격력을 끌어내리는 {@code weapon_damage} 만은 순수 계산
 * ({@link PerkWeaponDamage#desired})이라 끝에서 끝까지 그대로 확인한다.
 */
class WeaponDrawbackSetRewardTest {

	/**
	 * 공격력이 깎이는 경로를 하나씩 담은 시험용 풀.
	 *
	 * <p>기본 풀의 「익숙한 손목」·「급소만 노려」·「포식자」·「동병상련」·「제왕과 신하」·
	 * 「불면의 파수꾼」·「삽질의 대가」와 같은 모양이다. 아이템 태그는 데이터팩이 있어야 풀리므로
	 * {@code items} 로 적었다.
	 */
	private static final String PERKS = """
			{ "perks": [
			  { "id": "sharedfate:wpn_one", "rarity": "silver", "name": "무기 하나",
			    "set_types": [ "weapon" ],
			    "effects": [
			      { "type": "attribute", "attribute": "minecraft:attack_speed",
			        "operation": "add_multiplied_total", "amount": 0.1 }
			    ] },
			  { "id": "sharedfate:wpn_two", "rarity": "silver", "name": "무기 둘",
			    "set_types": [ "weapon" ],
			    "effects": [
			      { "type": "attribute", "attribute": "minecraft:attack_damage",
			        "operation": "add_multiplied_total", "amount": 0.2 }
			    ] },
			  { "id": "sharedfate:wpn_three", "rarity": "silver", "name": "무기 셋",
			    "set_types": [ "weapon" ],
			    "effects": [
			      { "type": "attribute", "attribute": "minecraft:sweeping_damage_ratio",
			        "operation": "add_value", "amount": 1.0 }
			    ] },
			  { "id": "sharedfate:digger", "rarity": "silver", "name": "익숙한 손목",
			    "set_types": [ "mining" ],
			    "effects": [
			      { "type": "attribute", "attribute": "minecraft:block_break_speed",
			        "operation": "add_multiplied_total", "amount": 1.0 },
			      { "type": "attribute", "attribute": "minecraft:attack_damage",
			        "operation": "add_multiplied_total", "amount": -0.1 }
			    ] },
			  { "id": "sharedfate:vital", "rarity": "silver", "name": "급소만 노려",
			    "effects": [
			      { "type": "damage_dealt", "multiplier": 0.95 },
			      { "type": "damage_dealt", "multiplier": 1.25 },
			      { "type": "damage_taken", "multiplier": 1.2 }
			    ] },
			  { "id": "sharedfate:predator", "rarity": "gold", "name": "포식자",
			    "effects": [
			      { "type": "conditional", "condition": "hunger_full",
			        "when_true": [
			          { "type": "attribute", "attribute": "minecraft:attack_damage",
			            "operation": "add_multiplied_total", "amount": 0.35 }
			        ],
			        "when_false": [
			          { "type": "attribute", "attribute": "minecraft:movement_speed",
			            "operation": "add_multiplied_total", "amount": -0.1 },
			          { "type": "attribute", "attribute": "minecraft:attack_damage",
			            "operation": "add_multiplied_total", "amount": -0.1 }
			        ] }
			    ] },
			  { "id": "sharedfate:shared_hurt", "rarity": "silver", "name": "동병상련",
			    "set_types": [ "defense" ],
			    "effects": [
			      { "type": "on_team_hurt", "durationSeconds": 2, "effects": [
			        { "type": "status_effect", "effect": "minecraft:resistance", "amplifier": 1 },
			        { "type": "attribute", "attribute": "minecraft:attack_damage",
			          "operation": "add_multiplied_total", "amount": -0.1, "drawback": true }
			      ] }
			    ] },
			  { "id": "sharedfate:king", "rarity": "prism", "name": "제왕과 신하",
			    "effects": [
			      { "type": "holder", "rotate_ticks": 1200, "min_hold_ticks": 200,
			        "on_holder": [
			          { "type": "status_effect", "effect": "minecraft:strength", "amplifier": 1 }
			        ],
			        "on_others": [
			          { "type": "attribute", "attribute": "minecraft:attack_damage",
			            "operation": "add_multiplied_total", "amount": -0.15 }
			        ] }
			    ] },
			  { "id": "sharedfate:offbeat", "rarity": "gold", "name": "엇박자",
			    "effects": [
			      { "type": "periodic", "period_ticks": 600, "phases": [
			        { "ticks": 400, "effects": [
			          { "type": "status_effect", "effect": "minecraft:strength", "amplifier": 0 }
			        ] },
			        { "ticks": 200, "effects": [
			          { "type": "status_effect", "effect": "minecraft:weakness", "amplifier": 0 },
			          { "type": "attribute", "attribute": "minecraft:attack_damage",
			            "operation": "add_value", "amount": -2.0 }
			        ] }
			      ] }
			    ] },
			  { "id": "sharedfate:watch", "rarity": "silver", "name": "불면의 파수꾼",
			    "effects": [
			      { "type": "mob_damage", "multiplier": 0.7 },
			      { "type": "mob_health", "multiplier": 1.5 }
			    ] },
			  { "id": "sharedfate:shovel", "rarity": "prism", "name": "삽질의 대가",
			    "effects": [
			      { "type": "weapon_damage", "items": [ "minecraft:diamond_shovel" ],
			        "multiplier": 3.0, "othersDamage": 1.0 }
			    ] }
			] }
			""";

	/** 세트 JSON 에 넣을 내용과 같은 모양. */
	private static final String SETS = """
			{ "sets": [
			  { "type": "weapon", "tiers": [
			    { "count": 2,
			      "description": "공격 속도가 15% 빨라집니다.",
			      "effects": [
			        { "type": "attribute", "attribute": "minecraft:attack_speed",
			          "operation": "add_multiplied_total", "amount": 0.15 }
			      ] },
			    { "count": 3,
			      "description": "공격력이 깎이지 않습니다.",
			      "effects": [ { "type": "no_attack_damage_loss" } ] }
			  ] }
			] }
			""";

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
		PerkSetRegistry.clear();
	}

	// ------------------------------------------------------------------ 등록 확인

	/**
	 * 효과 타입이 {@link PerkEffectType} 에 등록되어 있다.
	 *
	 * <p>등록을 빠뜨리면 <b>빌드도 통과하고 서버도 뜨는데</b> 무기 3단계만 조용히 사라진다.
	 * 아래 시험들이 전부 알 수 없는 이유로 깨지는 것보다 여기서 한 번에 잡히는 편이 낫다.
	 */
	@Test
	void 효과_타입이_등록되어_있다() {
		assertNotNull(PerkEffectType.fromId("no_attack_damage_loss"),
				"PerkEffectType 에 NO_ATTACK_DAMAGE_LOSS(\"no_attack_damage_loss\", "
						+ "NoAttackDamageLossEffect::fromJson) 가 없다");
	}

	// ------------------------------------------------------------------ 세트 판정

	@Test
	void 무기_증강_셋을_모으면_켜진다(@TempDir Path dir) throws IOException {
		load(dir);

		assertFalse(NoAttackDamageLossEffect.heldBy(teamWith()), "아직 아무것도 없다");
		assertFalse(NoAttackDamageLossEffect.heldBy(
				teamWith("sharedfate:wpn_one", "sharedfate:wpn_two")), "둘로는 2단계까지다");
		assertTrue(NoAttackDamageLossEffect.heldBy(teamWith(
				"sharedfate:wpn_one", "sharedfate:wpn_two", "sharedfate:wpn_three")));
	}

	@Test
	void 세트를_안_쓰는_서버에서는_아무것도_달라지지_않는다(@TempDir Path dir) throws IOException {
		// 세트 정의 파일이 없는 서버. 무기 증강을 몇 개를 모으든 감소는 그대로다.
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), PERKS, StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
		TeamState state = teamWith("sharedfate:wpn_one", "sharedfate:wpn_two",
				"sharedfate:wpn_three", "sharedfate:digger");

		assertFalse(NoAttackDamageLossEffect.heldBy(state));
		assertFalse(suppresses(state, "sharedfate:digger", 1));
	}

	@Test
	void 증강을_끈_팀에는_걸리지_않는다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = fullSet("sharedfate:digger");
		state.perksEnabled = false;

		assertFalse(NoAttackDamageLossEffect.heldBy(state));
		assertFalse(suppresses(state, "sharedfate:digger", 1));
	}

	// ------------------------------------------------------------------ 깎는 것은 안 붙는다

	/**
	 * <b>요구 사항 ① — 공격력을 깎는 {@code attribute} 는 표시가 켜지면 안 붙는다.</b>
	 *
	 * <p>{@code PerkManager.refreshPlayer} 가 이 판정을 보고 {@code apply} 대신 {@code remove} 를
	 * 부른다. 같은 증강이 가진 채굴 속도 이득은 그대로 붙는다.
	 */
	@Test
	void 공격력을_깎는_속성은_안_붙는다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = fullSet("sharedfate:digger");

		assertEquals("minecraft:attack_damage",
				attributeAt("sharedfate:digger", 1).attributeId().toString());
		assertTrue(suppresses(state, "sharedfate:digger", 1), "공격력 -10% 는 사라진다");
		assertFalse(suppresses(state, "sharedfate:digger", 0),
				"채굴 속도 ×2 는 공격력과 무관하므로 그대로다");
	}

	/** {@code damage_dealt} 로 적힌 감소도 같은 판정에 걸린다. 곱하지 않는 쪽은 배율을 모으는 자리다. */
	@Test
	void 주는_피해_배율_감소도_걸린다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = fullSet("sharedfate:vital");

		assertTrue(suppresses(state, "sharedfate:vital", 0), "×0.95 는 공격력 감소다");
		assertFalse(suppresses(state, "sharedfate:vital", 1), "×1.25 는 증가라 그대로다");
		assertFalse(suppresses(state, "sharedfate:vital", 2),
				"받는 피해 ×1.2 는 공격력이 아니라 대상이 아니다");
	}

	// ------------------------------------------------------------------ 올리는 것은 그대로

	/**
	 * <b>요구 사항 ② — 공격력을 올리는 효과는 그대로 붙는다.</b>
	 *
	 * <p>세트를 모을수록 약해지면 안 된다. 세트 자신이 거는 공격 속도 증가도 함께 확인한다.
	 */
	@Test
	void 공격력을_올리는_효과는_그대로다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = fullSet();
		assertTrue(NoAttackDamageLossEffect.heldBy(state), "3단계는 켜져 있다");

		assertFalse(suppresses(state, "sharedfate:wpn_two", 0), "공격력 +20% 는 그대로다");
		assertFalse(suppresses(state, "sharedfate:wpn_one", 0), "공격 속도도 대상이 아니다");
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) {
			assertFalse(NoAttackDamageLossEffect.reduces(effect),
					"세트가 스스로 거는 효과에는 공격력 감소가 없다");
		}
	}

	/** 값이 0 이면 아무것도 바꾸지 않는 것이라 감소가 아니다. 경계값을 못 박는다. */
	@Test
	void 값이_0_이거나_1_이면_감소가_아니다() {
		assertFalse(NoAttackDamageLossEffect.reduces(
				new AttributeEffect(NoAttackDamageLossEffect.ATTACK_DAMAGE_ID,
						AttributeEffect.modifierId("sharedfate:zero", 0),
						AttributeModifier.Operation.ADD_VALUE, 0.0)));
		assertFalse(NoAttackDamageLossEffect.reduces(new DamageDealtEffect(1.0)));
		assertTrue(NoAttackDamageLossEffect.reduces(new DamageDealtEffect(0.999)));
	}

	// ------------------------------------------------------------------ 감싼 효과 안쪽

	/**
	 * 조건부 안에서도 감소 한 줄만 골라진다.
	 *
	 * <p>부모를 통째로 건너뛰면 {@code when_true} 의 공격력 +35% 까지 사라진다. 그래서
	 * {@code apply} 는 그대로 두고 붙은 뒤에 감소만 걷어낸다.
	 */
	@Test
	void 조건부_안에서는_감소_한_줄만_골라진다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = fullSet("sharedfate:predator");
		ConditionalEffect conditional = assertInstanceOf(ConditionalEffect.class,
				PerkRegistry.byId("sharedfate:predator").orElseThrow().effects().get(0));

		assertFalse(suppresses(state, "sharedfate:predator", 0),
				"조건부 자신은 공격력을 깎지 않으므로 통째로 건너뛰면 안 된다");
		assertEquals(1, conditional.whenTrue().size());
		assertFalse(NoAttackDamageLossEffect.reduces(conditional.whenTrue().get(0)),
				"허기가 찼을 때의 공격력 +35% 는 그대로 남는다");
		assertFalse(NoAttackDamageLossEffect.reduces(conditional.whenFalse().get(0)),
				"이동 속도 감소는 공격력이 아니다");
		assertTrue(NoAttackDamageLossEffect.reduces(conditional.whenFalse().get(1)),
				"공격력 -10% 만 걷어낸다");
		assertEquals(3, NoAttackDamageLossEffect.childrenOf(conditional).size(),
				"두 묶음을 모두 훑어야 조건이 어느 쪽이든 잡힌다");
	}

	/** {@code holder} 의 {@code on_others} 도 같은 길로 잡힌다. */
	@Test
	void 보유자가_아닌_사람의_감소도_잡힌다(@TempDir Path dir) throws IOException {
		load(dir);
		HolderEffect holder = assertInstanceOf(HolderEffect.class,
				PerkRegistry.byId("sharedfate:king").orElseThrow().effects().get(0));

		assertFalse(NoAttackDamageLossEffect.reduces(holder), "감싼 효과 자신은 깎지 않는다");
		assertTrue(NoAttackDamageLossEffect.anyReduction(
				NoAttackDamageLossEffect.childrenOf(holder)));
		assertFalse(NoAttackDamageLossEffect.reduces(holder.onHolder().get(0)),
				"제왕의 힘 II 는 그대로다");
		assertTrue(NoAttackDamageLossEffect.reduces(holder.onOthers().get(0)));
	}

	/** {@code periodic} 의 구간 효과도 같은 길로 잡힌다. */
	@Test
	void 주기_구간_안의_감소도_잡힌다(@TempDir Path dir) throws IOException {
		load(dir);
		PeriodicEffect periodic = assertInstanceOf(PeriodicEffect.class,
				PerkRegistry.byId("sharedfate:offbeat").orElseThrow().effects().get(0));

		List<PerkEffect> children = NoAttackDamageLossEffect.childrenOf(periodic);
		assertTrue(NoAttackDamageLossEffect.anyReduction(children));
		// 나약함은 대상이 아니다. 아래 「상태이상은 대상이 아니다」가 그 결정을 못 박는다.
		assertEquals(1, children.stream().filter(NoAttackDamageLossEffect::reduces).count(),
				"구간 안에서 잡히는 것은 attribute 한 줄뿐이다");
	}

	/**
	 * {@code on_team_hurt} 의 하위 효과도 얹기 전에 걸러진다.
	 *
	 * <p>{@code TemporaryPerkGrants.grant} 가 부르는 모양 그대로다 — 부모 증강은 넘기지 않는다.
	 * 「동병상련」의 감소는 {@code drawback} 표시도 함께 달고 있지만, 이쪽 판정은 그 표시를
	 * 보지 않는다. 방어 3단계를 켜지 않은 팀에서도 무기 3단계만으로 사라진다.
	 */
	@Test
	void 잠깐_얹는_하위_효과도_얹기_전에_걸러진다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = fullSet("sharedfate:shared_hurt");
		OnTeamHurtEffect onHurt = assertInstanceOf(OnTeamHurtEffect.class,
				PerkRegistry.byId("sharedfate:shared_hurt").orElseThrow().effects().get(0));
		List<PerkEffect> children = onHurt.effects();

		assertTrue(NoAttackDamageLossEffect.anyReduction(children), "창 안에 감소가 있다");
		NoAttackDamageLossEffect.Gate gate = NoAttackDamageLossEffect.gateFor(state);
		assertFalse(gate.suppresses(children.get(0)), "저항 II 는 그대로 얹힌다");
		assertTrue(gate.suppresses(children.get(1)), "공격력 감소만 빠진다");
	}

	// ------------------------------------------------------------------ 몹 쪽은 그대로

	/**
	 * <b>요구 사항 ④ — 몹 관련 값은 안 변한다.</b>
	 *
	 * <p>{@code mob_damage} 가 깎는 것은 팀원의 공격력이 아니라 <b>몹의 공격력</b>이고, 게다가
	 * 팀에게 이득이다. 무효화하면 「불면의 파수꾼」의 보상이 사라진다. {@code mob_health} 도
	 * 같은 이유로 손대지 않는다.
	 */
	@Test
	void 몹_관련_값은_안_변한다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = fullSet("sharedfate:watch");
		assertTrue(NoAttackDamageLossEffect.heldBy(state), "3단계는 켜져 있다");

		assertInstanceOf(MobDamageEffect.class,
				PerkRegistry.byId("sharedfate:watch").orElseThrow().effects().get(0));
		assertInstanceOf(MobHealthEffect.class,
				PerkRegistry.byId("sharedfate:watch").orElseThrow().effects().get(1));
		assertFalse(suppresses(state, "sharedfate:watch", 0), "몹 공격력 ×0.7 은 그대로다");
		assertFalse(suppresses(state, "sharedfate:watch", 1), "몹 체력 ×1.5 도 그대로다");
	}

	/**
	 * 상태이상은 대상이 아니다.
	 *
	 * <p>나약함도 결국 공격력을 깎지만 손대지 않는다. 상태이상은 팀에
	 * 공유되고 물약·마녀처럼 증강 밖에서도 들어오는 값이라, 「증강의 대가」와 「바깥에서 온 것」을
	 * 가릴 방법이 없다. 나중에 방침이 바뀌면 이 시험이 먼저 깨진다.
	 */
	@Test
	void 상태이상은_대상이_아니다(@TempDir Path dir) throws IOException {
		load(dir);
		PeriodicEffect periodic = assertInstanceOf(PeriodicEffect.class,
				PerkRegistry.byId("sharedfate:offbeat").orElseThrow().effects().get(0));

		for (PerkEffect effect : NoAttackDamageLossEffect.childrenOf(periodic)) {
			if (effect instanceof StatusEffectPerk) {
				assertFalse(NoAttackDamageLossEffect.reduces(effect),
						"나약함을 포함한 상태이상은 지금 판정 대상이 아니다");
			}
		}
	}

	// ------------------------------------------------------------------ 무기가 끌어내리는 공격력

	/**
	 * {@code weapon_damage} 의 {@code othersDamage} 는 끌어내릴 때만 사라진다.
	 *
	 * <p>여기만 끝에서 끝까지 확인할 수 있다. 우대 무기의 배수는 감소가 아니므로 세트를 켜도
	 * 그대로 걸린다.
	 */
	@Test
	void 다른_무기의_공격력을_끌어내리는_규칙만_사라진다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState off = teamWith("sharedfate:shovel");
		TeamState on = fullSet("sharedfate:shovel");

		// 다이아몬드 검은 주 손에서 공격력을 6 얹는다. 기본 1 + 6 = 7 을 1 로 만들어야 하므로 -6.
		AttributeModifier lowered =
				PerkWeaponDamage.desired(off, new ItemStack(Items.DIAMOND_SWORD), 1.0);
		assertNotNull(lowered);
		assertEquals(-6.0, lowered.amount(), 1.0e-6);

		assertNull(PerkWeaponDamage.desired(on, new ItemStack(Items.DIAMOND_SWORD), 1.0),
				"무기 3단계를 켜면 검의 공격력이 깎이지 않는다");

		AttributeModifier boosted =
				PerkWeaponDamage.desired(on, new ItemStack(Items.DIAMOND_SHOVEL), 1.0);
		assertNotNull(boosted, "삽의 ×3 은 증가라 그대로 걸린다");
		assertEquals(2.0, boosted.amount(), 1.0e-6);
	}

	// ------------------------------------------------------------------ 되돌아가기

	/**
	 * <b>요구 사항 ③ — 세트가 풀리면 감소가 돌아온다.</b>
	 *
	 * <p>「환골탈태」가 보유 목록을 갈아엎으면 무기가 셋에서 둘로 줄 수 있다. 판정은 언제나
	 * {@code ownedPerks} 를 다시 세어 만들므로 그 즉시 따라온다. 실제로 다시 붙이는 일은
	 * {@code PerkManager.refreshPlayer} 가 맡는다 — 그 자리는 감싼 효과의 {@code apply} 까지
	 * 무조건 다시 부르므로 조건부·보유자·주기 안쪽도 함께 돌아온다.
	 */
	@Test
	void 세트가_풀리면_감소가_돌아온다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = fullSet("sharedfate:digger", "sharedfate:vital", "sharedfate:shovel");
		assertTrue(suppresses(state, "sharedfate:digger", 1));
		assertTrue(suppresses(state, "sharedfate:vital", 0));
		assertNull(PerkWeaponDamage.desired(state, new ItemStack(Items.DIAMOND_SWORD), 1.0));

		state.ownedPerks.remove("sharedfate:wpn_three");

		assertFalse(NoAttackDamageLossEffect.heldBy(state));
		assertFalse(suppresses(state, "sharedfate:digger", 1), "공격력 -10% 가 돌아온다");
		assertFalse(suppresses(state, "sharedfate:vital", 0), "×0.95 도 돌아온다");
		assertNotNull(PerkWeaponDamage.desired(state, new ItemStack(Items.DIAMOND_SWORD), 1.0),
				"검의 공격력 하향도 돌아온다");
	}

	// ------------------------------------------------------------------ 기본 풀

	/**
	 * 번들 기본 풀에서 이 세트가 무효로 만드는 효과의 <b>전체 목록</b>을 못 박는다.
	 *
	 * <p>새 증강이 공격력을 깎으면 여기가 먼저 깨진다. 그때 「이것도 사라져야 하는가」를 한 번
	 * 생각하고 목록에 더하면 된다 — 코드는 고칠 필요가 없다. 반대로 목록에 없어야 할 것이 끼면
	 * (예: 공격력 <b>증가</b>나 몹 쪽 값) 그것도 여기서 잡힌다.
	 */
	@Test
	void 기본_풀에서_무효가_되는_것은_이것뿐이다(@TempDir Path dir) throws IOException {
		loadBundled(dir);

		List<String> reduced = new ArrayList<>();
		for (Perk perk : PerkRegistry.all()) {
			for (PerkEffect effect : allEffectsOf(perk)) {
				if (NoAttackDamageLossEffect.reduces(effect)) {
					reduced.add(perk.id());
				}
			}
		}

		assertEquals(List.of(
						"sharedfate:shared_suffering",
						"sharedfate:vital_strike",
						"sharedfate:predator",
						"sharedfate:flawless",
						"sharedfate:brink",
						"sharedfate:king_and_subjects",
						"sharedfate:practiced_wrist",
						"sharedfate:sweeping_edge",
						"sharedfate:excavator",
						"sharedfate:cudgel"),
				reduced, "공격력을 깎는 증강은 이 열뿐이다");
	}

	/** 기본 풀의 몹 관련 효과는 하나도 대상이 아니다. */
	@Test
	void 기본_풀의_몹_효과는_하나도_안_잡힌다(@TempDir Path dir) throws IOException {
		loadBundled(dir);

		int mobEffects = 0;
		for (Perk perk : PerkRegistry.all()) {
			for (PerkEffect effect : allEffectsOf(perk)) {
				if (effect instanceof MobDamageEffect || effect instanceof MobHealthEffect) {
					mobEffects++;
					assertFalse(NoAttackDamageLossEffect.reduces(effect),
							perk.id() + " 의 몹 효과가 공격력 감소로 잡혔다");
				}
			}
		}
		assertTrue(mobEffects > 0, "기본 풀에 몹 효과가 하나도 없으면 이 시험이 아무것도 안 지킨다");
	}

	// ------------------------------------------------------------------ 도우미

	/** 시험용 증강 정의와 세트 정의를 함께 올린다. */
	private static void load(Path dir) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), PERKS, StandardCharsets.UTF_8);
		Files.writeString(dir.resolve(PerkSetRegistry.FILE_NAME), SETS, StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
		PerkSetRegistry.load(dir);
	}

	/** 번들에 들어 있는 진짜 증강 풀을 올린다. */
	private static void loadBundled(Path dir) throws IOException {
		try (InputStream bundled = WeaponDrawbackSetRewardTest.class
				.getResourceAsStream("/sharedfate-perks-default.json")) {
			assertNotNull(bundled, "번들에 sharedfate-perks-default.json 이 없다");
			Files.copy(bundled, dir.resolve(PerkRegistry.FILE_NAME));
		}
		PerkRegistry.load(dir);
	}

	/** 증강을 켜고 이 증강들을 가진 팀. */
	private static TeamState teamWith(String... perkIds) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.addAll(List.of(perkIds));
		return state;
	}

	/** 무기 3단계가 켜지도록 무기 증강 셋을 깔고, 그 위에 더 얹는다. */
	private static TeamState fullSet(String... extra) {
		TeamState state = teamWith("sharedfate:wpn_one", "sharedfate:wpn_two",
				"sharedfate:wpn_three");
		state.ownedPerks.addAll(List.of(extra));
		return state;
	}

	/**
	 * {@code PerkManager.refreshPlayer} 가 보고 갈라지는 판정 그대로다.
	 *
	 * @param index 그 증강의 몇 번째 최상위 효과인지
	 */
	private static boolean suppresses(TeamState state, String perkId, int index) {
		Perk perk = PerkRegistry.byId(perkId).orElseThrow();
		return NoAttackDamageLossEffect.gateFor(state).suppresses(perk.effects().get(index));
	}

	private static AttributeEffect attributeAt(String perkId, int index) {
		return assertInstanceOf(AttributeEffect.class,
				PerkRegistry.byId(perkId).orElseThrow().effects().get(index));
	}

	/** 최상위 효과와 감싼 효과의 하위, 잠깐 얹는 창의 하위까지 모두 편다. */
	private static List<PerkEffect> allEffectsOf(Perk perk) {
		List<PerkEffect> all = new ArrayList<>(perk.effects());
		for (PerkEffect effect : perk.effects()) {
			all.addAll(NoAttackDamageLossEffect.childrenOf(effect));
			if (effect instanceof OnTeamHurtEffect onHurt) {
				all.addAll(onHurt.effects());
			}
		}
		return all;
	}
}
