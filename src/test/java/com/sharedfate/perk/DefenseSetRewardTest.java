package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.AttributeEffect;
import com.sharedfate.perk.effect.ConditionalEffect;
import com.sharedfate.perk.effect.MobHealthEffect;
import com.sharedfate.perk.effect.NoDefenseDrawbacksEffect;
import com.sharedfate.perk.effect.OnTeamHurtEffect;
import com.sharedfate.perk.effect.StatusEffectPerk;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
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
 * 세트 「방어 3단계 — 대가는 안 치른다」.
 *
 * <p>방어 유형 증강을 셋 모으면 <b>가지고 있는 방어 증강들의 「대가」가 사라진다.</b> 대가란
 * 정의 파일의 효과 하나에 {@code "drawback": true} 라고 적어 둔 것이고, 그 표시를 모아 두는 곳이
 * {@link PerkDrawbacks} 다.
 *
 * <h2>이 시험이 가장 힘주어 지키는 것</h2>
 * <p><b>표시가 없는 효과는 지금과 똑같이 동작한다.</b> 증강 여든두 개 중 표시가 붙은 것은 다섯
 * 뿐이고, 나머지는 이 기능이 들어오기 전과 한 톨도 다르지 않아야 한다. 그래서 이 파일은
 * 「사라진다」보다 「안 사라진다」를 더 많이 확인한다.
 *
 * <h2>살아 있는 플레이어가 필요한 자리</h2>
 * <p>속성 수정자를 실제로 붙였다 떼는 {@code PerkManager.refreshPlayer} 와, 잠깐 얹는
 * {@code TemporaryPerkGrants.grant} 는 {@code ServerPlayer} 가 있어야 부를 수 있다. 그래서 그
 * 자리들이 <b>실제로 보고 갈라지는 판정</b>인 {@link PerkDrawbacks.Waiver#waives} 를 직접
 * 시험한다.
 *
 * <p>피해원을 가리는 {@code damage_taken_from} 만은 순수 계산
 * ({@link PerkDamage#takenSourceMultiplier})이라 끝에서 끝까지 그대로 확인한다.
 */
class DefenseSetRewardTest {

	/**
	 * 다섯 대가가 지나는 경로를 하나씩 담은 시험용 풀.
	 *
	 * <p>기본 풀의 「바람 빠진 방패」·「화살막이」·「동병상련」·「맨살의 각오」·「불굴」과 같은
	 * 모양이다. 태그({@code #minecraft:is_explosion})는 데이터팩이 있어야 풀리므로 여기서는 피해
	 * 종류를 직접 적었다.
	 */
	private static final String PERKS = """
			{ "perks": [
			  { "id": "sharedfate:def_shield", "rarity": "silver", "name": "바람 빠진 방패",
			    "set_types": [ "defense" ],
			    "effects": [
			      { "type": "damage_taken_from", "multiplier": 0.5,
			        "sources": [ "minecraft:explosion" ] },
			      { "type": "attribute", "attribute": "minecraft:knockback_resistance",
			        "operation": "add_value", "amount": -1.0, "drawback": true }
			    ] },
			  { "id": "sharedfate:def_arrow", "rarity": "silver", "name": "화살막이",
			    "set_types": [ "defense" ],
			    "effects": [
			      { "type": "damage_taken_from", "multiplier": 0.5,
			        "sources": [ "minecraft:arrow" ] },
			      { "type": "damage_taken_from", "multiplier": 1.2,
			        "sources": [ "minecraft:explosion" ], "drawback": true }
			    ] },
			  { "id": "sharedfate:def_hurt", "rarity": "silver", "name": "동병상련",
			    "set_types": [ "defense" ],
			    "effects": [
			      { "type": "on_team_hurt", "durationSeconds": 2, "effects": [
			        { "type": "status_effect", "effect": "minecraft:resistance", "amplifier": 1 },
			        { "type": "attribute", "attribute": "minecraft:attack_damage",
			          "operation": "add_multiplied_total", "amount": -0.1, "drawback": true }
			      ] }
			    ] },
			  { "id": "sharedfate:def_bare", "rarity": "silver", "name": "맨살의 각오",
			    "set_types": [ "defense" ],
			    "effects": [
			      { "type": "attribute", "attribute": "minecraft:armor",
			        "operation": "add_value", "amount": 4.0 },
			      { "type": "attribute", "attribute": "minecraft:block_break_speed",
			        "operation": "add_multiplied_total", "amount": -0.25, "drawback": true }
			    ] },
			  { "id": "sharedfate:def_unbroken", "rarity": "gold", "name": "불굴",
			    "set_types": [ "defense" ],
			    "effects": [
			      { "type": "conditional", "condition": "health_below", "threshold": 0.5,
			        "when_true": [
			          { "type": "damage_taken", "multiplier": 0.7 }
			        ] },
			      { "type": "conditional", "condition": "health_above", "threshold": 0.75,
			        "when_true": [ { "type": "damage_taken", "multiplier": 1.1 } ], "drawback": true }
			    ] },
			  { "id": "sharedfate:power_cost", "rarity": "silver", "name": "다른 유형의 대가",
			    "set_types": [ "power" ],
			    "effects": [
			      { "type": "damage_taken_from", "multiplier": 1.5,
			        "sources": [ "minecraft:explosion" ], "drawback": true }
			    ] },
			  { "id": "sharedfate:plain", "rarity": "silver", "name": "무유형",
			    "effects": [ { "type": "damage_dealt", "multiplier": 1.1 } ] }
			] }
			""";

	/**
	 * 표시만 뺀 같은 풀. 「표시가 없으면 예전과 똑같다」를 재는 자다.
	 *
	 * <p>위의 정의는 표시를 전부 {@code , "drawback": true} 한 가지 모양으로만 적어 두었다.
	 * 그래야 여기서 지우는 일이 확실하다.
	 */
	private static final String PERKS_WITHOUT_MARKS = PERKS.replace(", \"drawback\": true", "");

	/** 세트 JSON 에 넣을 내용과 같은 모양. */
	private static final String SETS = """
			{ "sets": [
			  { "type": "defense", "tiers": [
			    { "count": 2, "name": "방패 뒤가 편하다",
			      "description": "방패로 막는 동안 받는 피해가 절반이 됩니다.",
			      "effects": [ { "type": "damage_taken_blocking", "multiplier": 0.5 } ] },
			    { "count": 3, "name": "대가는 안 치른다",
			      "description": "가지고 있는 방어 증강들의 대가가 사라집니다.",
			      "effects": [ { "type": "no_defense_drawbacks" } ] }
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
	 * <p>등록을 빠뜨리면 <b>빌드도 통과하고 서버도 뜨는데</b> 방어 3단계만 조용히 사라진다.
	 */
	@Test
	void 효과_타입이_등록되어_있다() {
		assertNotNull(PerkEffectType.fromId("no_defense_drawbacks"),
				"PerkEffectType 에 NO_DEFENSE_DRAWBACKS(\"no_defense_drawbacks\", "
						+ "NoDefenseDrawbacksEffect::fromJson) 가 없다");
	}

	// ------------------------------------------------------------------ 표시가 없으면 그대로

	/**
	 * 표시를 안 적은 효과는 표에 오르지 않는다. 표에 없으면 {@link PerkDrawbacks.Waiver} 가
	 * 첫 줄에서 거짓을 돌려주므로, 세트가 켜져 있든 말든 예전과 완전히 같은 길을 지난다.
	 */
	@Test
	void 표시를_안_적은_효과는_표에_오르지_않는다(@TempDir Path dir) throws IOException {
		load(dir);

		for (Perk perk : PerkRegistry.all()) {
			for (PerkEffect effect : perk.effects()) {
				if (PerkDrawbacks.isDrawback(effect)) {
					continue;
				}
				assertNull(PerkDrawbacks.ownerOf(effect),
						perk.id() + " 의 표시 없는 효과가 표에 올라 있다");
			}
		}
		// 표시를 뺀 같은 풀에는 대가가 하나도 없다.
		loadWithoutMarks(dir);
		for (Perk perk : PerkRegistry.all()) {
			assertFalse(PerkDrawbacks.anyDrawback(perk.effects()),
					perk.id() + " 에 표시가 남아 있다");
		}
	}

	/**
	 * 표시를 뺀 풀에서는 방어 3단계를 켜도 값이 하나도 달라지지 않는다.
	 *
	 * <p>「대가가 사라진다」가 표시를 보고 도는지, 아니면 방어 증강이라는 이유만으로 도는지를
	 * 가르는 자리다. 뒤엣것이면 여기서 0.5 가 나온다.
	 */
	@Test
	void 표시가_없으면_세트를_켜도_예전_값_그대로다(@TempDir Path dir) throws IOException {
		loadWithoutMarks(dir);
		TeamState off = teamWith("sharedfate:def_shield", "sharedfate:def_arrow");
		TeamState on = teamWith("sharedfate:def_shield", "sharedfate:def_arrow",
				"sharedfate:def_hurt");
		assertTrue(NoDefenseDrawbacksEffect.heldBy(on), "3단계 자체는 켜져 있어야 한다");

		assertEquals(0.6, PerkDamage.takenSourceMultiplier(off, explosion()), 1.0e-9);
		assertEquals(0.6, PerkDamage.takenSourceMultiplier(on, explosion()), 1.0e-9,
				"표시가 없으면 세트가 켜져도 ×1.2 가 그대로 곱해진다");
	}

	// ------------------------------------------------------------------ 세트 판정

	@Test
	void 방어_증강_셋을_모으면_켜진다(@TempDir Path dir) throws IOException {
		load(dir);

		assertFalse(NoDefenseDrawbacksEffect.heldBy(teamWith()), "아직 아무것도 없다");
		assertFalse(NoDefenseDrawbacksEffect.heldBy(
				teamWith("sharedfate:def_shield", "sharedfate:def_arrow")), "둘로는 2단계까지다");
		assertTrue(NoDefenseDrawbacksEffect.heldBy(teamWith(
				"sharedfate:def_shield", "sharedfate:def_arrow", "sharedfate:def_hurt")));
	}

	@Test
	void 세트를_안_쓰는_서버에서는_아무것도_달라지지_않는다(@TempDir Path dir) throws IOException {
		// 세트 정의 파일이 없는 서버. 방어 증강을 몇 개를 모으든 대가는 그대로다.
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), PERKS, StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
		TeamState state = teamWith("sharedfate:def_shield", "sharedfate:def_arrow",
				"sharedfate:def_hurt", "sharedfate:def_bare", "sharedfate:def_unbroken");

		assertFalse(NoDefenseDrawbacksEffect.heldBy(state));
		assertEquals(0.6, PerkDamage.takenSourceMultiplier(state, explosion()), 1.0e-9);
		assertFalse(waives(state, "sharedfate:def_shield", 1));
	}

	@Test
	void 증강을_끈_팀에는_걸리지_않는다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:def_shield", "sharedfate:def_arrow",
				"sharedfate:def_hurt");
		state.perksEnabled = false;

		assertFalse(NoDefenseDrawbacksEffect.heldBy(state));
		assertFalse(waives(state, "sharedfate:def_shield", 1));
	}

	// ------------------------------------------------------------------ 다섯 경로

	/**
	 * 경로 ①·② — {@code attribute} 로 붙였다 떼는 대가.
	 *
	 * <p>「바람 빠진 방패」의 넉백 2배와 「맨살의 각오」의 채굴 속도 감소다. 두 대가 모두
	 * {@code PerkManager.refreshPlayer} 가 이 판정을 보고 {@code apply} 대신 {@code remove} 를
	 * 부른다. 같은 증강이 가진 <b>이득 쪽 속성은 그대로 붙는다.</b>
	 */
	@Test
	void 넉백과_채굴속도_대가는_붙이지_않고_이득은_그대로다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:def_shield", "sharedfate:def_arrow",
				"sharedfate:def_bare");

		assertEquals("minecraft:knockback_resistance",
				attributeAt("sharedfate:def_shield", 1).attributeId().toString());
		assertTrue(waives(state, "sharedfate:def_shield", 1), "넉백 2배는 사라진다");

		assertEquals("minecraft:block_break_speed",
				attributeAt("sharedfate:def_bare", 1).attributeId().toString());
		assertTrue(waives(state, "sharedfate:def_bare", 1), "채굴 속도 감소는 사라진다");

		assertEquals("minecraft:armor", attributeAt("sharedfate:def_bare", 0).attributeId().toString());
		assertFalse(waives(state, "sharedfate:def_bare", 0), "방어력 +4 는 이득이라 그대로다");
		assertFalse(waives(state, "sharedfate:def_shield", 0), "폭발 ×0.5 도 이득이라 그대로다");
	}

	/**
	 * 경로 ③ — {@code damage_taken_from}. 여기만 끝에서 끝까지 확인할 수 있다.
	 *
	 * <p>「바람 빠진 방패」의 폭발 ×0.5 와 「화살막이」의 폭발 ×1.2 가 함께 걸려 0.6 이던 값이,
	 * 세트가 켜지면 대가만 빠져 0.5 가 된다.
	 */
	@Test
	void 폭발_피해_대가가_실제로_빠진다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState off = teamWith("sharedfate:def_shield", "sharedfate:def_arrow");
		TeamState on = teamWith("sharedfate:def_shield", "sharedfate:def_arrow",
				"sharedfate:def_hurt");

		assertEquals(0.6, PerkDamage.takenSourceMultiplier(off, explosion()), 1.0e-9,
				"둘만 모았을 때는 0.5 × 1.2");
		assertEquals(0.5, PerkDamage.takenSourceMultiplier(on, explosion()), 1.0e-9,
				"셋을 모으면 ×1.2 만 빠진다");
		assertEquals(0.5, PerkDamage.takenSourceMultiplier(on, arrow()), 1.0e-9,
				"화살 ×0.5 는 이득이라 그대로다");
	}

	/**
	 * 경로 ④ — {@code on_team_hurt} 의 하위 효과.
	 *
	 * <p>발동 자체({@code on_team_hurt})는 이득이고 그 안의 공격력 감소만 대가다. 그래서 표시가
	 * 하위에 붙어 있고, {@code TemporaryPerkGrants.grant} 는 <b>부모 증강을 손에 들지 않은 채로</b>
	 * 판정한다 — 표가 「누가 가진 대가인가」를 함께 들고 있어서 가능한 일이다.
	 */
	@Test
	void 동병상련은_저항만_남고_공격력_감소가_빠진다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:def_shield", "sharedfate:def_arrow",
				"sharedfate:def_hurt");
		OnTeamHurtEffect onHurt = assertInstanceOf(OnTeamHurtEffect.class,
				PerkRegistry.byId("sharedfate:def_hurt").orElseThrow().effects().get(0));
		List<PerkEffect> children = onHurt.effects();

		assertTrue(PerkDrawbacks.anyDrawback(children), "창 안에 대가가 있다");
		assertInstanceOf(StatusEffectPerk.class, children.get(0));
		assertInstanceOf(AttributeEffect.class, children.get(1));

		PerkDrawbacks.Waiver waiver = PerkDrawbacks.waiverFor(state);
		// grant 가 부르는 모양 그대로 — 부모 증강은 넘기지 않는다.
		assertFalse(waiver.waives(null, children.get(0)), "저항 II 는 그대로 얹힌다");
		assertTrue(waiver.waives(null, children.get(1)), "공격력 감소만 빠진다");

		assertFalse(onHurt.effects().isEmpty(), "정의 자체는 그대로 남는다");
		assertFalse(PerkDrawbacks.isDrawback(onHurt), "부모에는 표시가 없다");
	}

	/**
	 * 경로 ⑤ — {@code conditional}.
	 *
	 * <p>「불굴」의 두 번째 조건부는 묶음이 하나뿐인 <b>대가 그 자체</b>라 통째로 표시를 붙였다.
	 * 첫 번째 조건부(체력 절반 이하일 때 받는 피해 ×0.7)는 이득이므로 손대지 않는다. 두 조건부가
	 * 같은 {@code damage_taken} 을 들고 있어도 표시는 조건부 단위라 이득 쪽은 건너뛰지 않는다.
	 *
	 * <p>대가를 <b>없애는 것이 아니라 건너뛴다.</b> 조건부 효과 자신이 들고 있는 배율은 그대로
	 * 1.1 이고, 그것을 곱하지 않는 쪽은 {@code PerkManager.multiplier} 와
	 * {@code ConditionalPerkManager.refreshPlayer} 다.
	 */
	@Test
	void 불굴은_조건부_전체가_대가다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:def_shield", "sharedfate:def_arrow",
				"sharedfate:def_unbroken");
		List<PerkEffect> effects = PerkRegistry.byId("sharedfate:def_unbroken").orElseThrow().effects();
		ConditionalEffect benefit = assertInstanceOf(ConditionalEffect.class, effects.get(0));
		ConditionalEffect cost = assertInstanceOf(ConditionalEffect.class, effects.get(1));

		assertEquals(ConditionalEffect.Condition.HEALTH_BELOW, benefit.condition());
		assertEquals(ConditionalEffect.Condition.HEALTH_ABOVE, cost.condition());
		assertFalse(waives(state, "sharedfate:def_unbroken", 0), "받는 피해 ×0.7 은 이득이라 그대로다");
		assertTrue(waives(state, "sharedfate:def_unbroken", 1));

		assertEquals(1.1, cost.damageTakenMultiplier(true), 1.0e-9,
				"효과 자신은 그대로다 — 곱하지 않는 쪽이 건너뛴다");
		assertEquals(0.7, benefit.damageTakenMultiplier(true), 1.0e-9);
	}

	/** {@code conditional} 하위에 적은 표시는 읽지 않는다. 조용히 무시하지 않고 경고를 남긴다. */
	@Test
	void 조건부_하위에_적은_표시는_무시된다() {
		JsonObject json = JsonParser.parseString("""
				{ "type": "conditional", "condition": "health_above", "threshold": 0.75,
				  "when_true": [ { "type": "damage_taken", "multiplier": 1.1, "drawback": true } ] }
				""").getAsJsonObject();

		ConditionalEffect effect = assertInstanceOf(ConditionalEffect.class,
				ConditionalEffect.fromJson("sharedfate:test", 0, json));

		// 정의는 그대로 살아 있고, 하위 효과는 대가로 등록되지 않는다.
		assertEquals(1, effect.whenTrue().size());
		assertFalse(PerkDrawbacks.isDrawback(effect.whenTrue().get(0)));
		assertFalse(PerkDrawbacks.isDrawback(effect));
	}

	// ------------------------------------------------------------------ 다른 유형은 그대로

	/**
	 * 방어가 아닌 증강의 대가는 사라지지 않는다.
	 *
	 * <p>같은 표시를 달아 두었어도 유형이 다르면 건너뛰지 않는다. 「방어 유형 증강의 대가만
	 * 사라진다」를 지키는 자리다.
	 */
	@Test
	void 다른_유형_증강의_대가는_그대로다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:def_shield", "sharedfate:def_arrow",
				"sharedfate:def_hurt", "sharedfate:power_cost");

		assertTrue(NoDefenseDrawbacksEffect.heldBy(state));
		assertFalse(waives(state, "sharedfate:power_cost", 0), "화력 유형의 대가는 남는다");
		// 0.5(이득) × 1.2(사라짐) × 1.5(화력 대가라 남음)
		assertEquals(0.75, PerkDamage.takenSourceMultiplier(state, explosion()), 1.0e-9);
	}

	// ------------------------------------------------------------------ 되돌아가기

	/**
	 * 세트가 풀리면 대가가 <b>돌아온다.</b>
	 *
	 * <p>「환골탈태」가 보유 목록을 갈아엎으면 방어가 셋에서 둘로 줄 수 있다. 판정은 언제나
	 * {@code ownedPerks} 를 다시 세어 만들므로 그 즉시 따라온다.
	 */
	@Test
	void 세트가_풀리면_대가가_돌아온다(@TempDir Path dir) throws IOException {
		load(dir);
		TeamState state = teamWith("sharedfate:def_shield", "sharedfate:def_arrow",
				"sharedfate:def_hurt");
		assertEquals(0.5, PerkDamage.takenSourceMultiplier(state, explosion()), 1.0e-9);
		assertTrue(waives(state, "sharedfate:def_shield", 1));

		state.ownedPerks.remove("sharedfate:def_hurt");

		assertFalse(NoDefenseDrawbacksEffect.heldBy(state));
		assertEquals(0.6, PerkDamage.takenSourceMultiplier(state, explosion()), 1.0e-9,
				"폭발 ×1.2 가 돌아온다");
		assertFalse(waives(state, "sharedfate:def_shield", 1), "넉백 2배도 돌아온다");
	}

	// ------------------------------------------------------------------ 표 자체

	@Test
	void 정의를_다시_읽으면_표가_비워진다(@TempDir Path dir) throws IOException {
		load(dir);
		assertFalse(PerkDrawbacks.isEmpty());

		PerkRegistry.clear();

		assertTrue(PerkDrawbacks.isEmpty(),
				"효과 객체가 새로 만들어지므로 옛 표시는 아무도 가리키지 않는 쓰레기가 된다");
	}

	/** 표시 값이 참·거짓이 아니면 무시한다. 정의 실수로 증강이 통째로 사라지지는 않는다. */
	@Test
	void 표시가_참거짓이_아니면_무시한다(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), """
				{ "perks": [
				  { "id": "sharedfate:odd", "rarity": "silver", "name": "이상한 표시",
				    "set_types": [ "defense" ],
				    "effects": [
				      { "type": "attribute", "attribute": "minecraft:armor",
				        "operation": "add_value", "amount": -1.0, "drawback": "네" }
				    ] }
				] }
				""", StandardCharsets.UTF_8);
		PerkRegistry.load(dir);

		Perk perk = PerkRegistry.byId("sharedfate:odd").orElseThrow();
		assertEquals(1, perk.effects().size(), "증강은 그대로 살아 있다");
		assertFalse(PerkDrawbacks.isDrawback(perk.effects().get(0)), "표시가 없는 것으로 본다");
	}

	@Test
	void 표시를_거짓으로_적으면_대가가_아니다(@TempDir Path dir) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), """
				{ "perks": [
				  { "id": "sharedfate:off", "rarity": "silver", "name": "꺼진 표시",
				    "set_types": [ "defense" ],
				    "effects": [
				      { "type": "attribute", "attribute": "minecraft:armor",
				        "operation": "add_value", "amount": -1.0, "drawback": false }
				    ] }
				] }
				""", StandardCharsets.UTF_8);
		PerkRegistry.load(dir);

		assertFalse(PerkDrawbacks.isDrawback(
				PerkRegistry.byId("sharedfate:off").orElseThrow().effects().get(0)));
	}

	// ------------------------------------------------------------------ 기본 풀

	/**
	 * 번들 기본 풀에서 대가로 표시된 것은 <b>정확히 다섯</b>이고, 전부 방어 유형 증강의 것이다.
	 *
	 * <p>표시를 하나 더 붙이거나 엉뚱한 증강에 붙이면 여기서 잡힌다. 증강 여든두 개 중 나머지
	 * 일흔일곱 개는 이 기능이 들어오기 전과 완전히 같다는 뜻이기도 하다.
	 */
	@Test
	void 기본_풀의_대가는_방어_증강_다섯_개뿐이다(@TempDir Path dir) throws IOException {
		loadBundled(dir);

		List<String> owners = new ArrayList<>();
		for (Perk perk : PerkRegistry.all()) {
			for (PerkEffect effect : allEffectsOf(perk)) {
				if (!PerkDrawbacks.isDrawback(effect)) {
					continue;
				}
				assertEquals(perk.id(), PerkDrawbacks.ownerOf(effect));
				assertTrue(perk.hasSetType(PerkSetType.DEFENSE),
						perk.id() + " 는 방어 유형이 아닌데 대가가 표시돼 있다");
				owners.add(perk.id());
			}
		}

		assertEquals(List.of(
						"sharedfate:deflated_shield",
						"sharedfate:shared_suffering",
						"sharedfate:arrow_guard",
						"sharedfate:bare_skin_resolve",
						"sharedfate:unbroken"),
				owners, "표시가 붙은 증강은 이 다섯뿐이다");
	}

	/**
	 * 「버티는 방패」와 「철벽」의 몹 체력은 <b>표시하지 않았다.</b>
	 *
	 * <p>몹 체력은 팀이 아니라 <b>월드에 걸리는</b> 값이라 다른 대가와 성격이 다르다. 이미 스폰된
	 * 몹까지 되돌리면 체력 표시가 튀고, 두 증강을 함께 가진 팀에서는 세트를 켜는 순간 월드의 모든
	 * 몹이 1.725 배에서 1 배로 내려앉는다.
	 */
	@Test
	void 몹_체력은_대가로_표시하지_않았다(@TempDir Path dir) throws IOException {
		loadBundled(dir);

		for (String perkId : List.of("sharedfate:grounded_guard", "sharedfate:iron_wall")) {
			Perk perk = PerkRegistry.byId(perkId).orElseThrow();
			assertTrue(perk.hasSetType(PerkSetType.DEFENSE));
			assertFalse(PerkDrawbacks.anyDrawback(perk.effects()),
					perkId + " 에는 표시가 하나도 없어야 한다");
			assertTrue(perk.effects().stream().anyMatch(MobHealthEffect.class::isInstance),
					perkId + " 의 몹 체력 대가는 그대로 남아 있다");
		}
	}

	/** 「철벽」에 대가로 남은 것은 몹 체력뿐이다. */
	@Test
	void 철벽에_남은_대가는_몹_체력뿐이다(@TempDir Path dir) throws IOException {
		loadBundled(dir);
		Perk ironWall = PerkRegistry.byId("sharedfate:iron_wall").orElseThrow();

		assertTrue(ironWall.effects().stream().noneMatch(effect ->
						effect instanceof AttributeEffect attribute
								&& attribute.attributeId().toString().equals("minecraft:movement_speed")),
				"이속 감소가 되살아나 있다");
	}

	// ------------------------------------------------------------------ 도우미

	/** 시험용 증강 정의와 세트 정의를 함께 올린다. */
	private static void load(Path dir) throws IOException {
		write(dir, PERKS);
	}

	/** 표시만 뺀 같은 풀을 올린다. */
	private static void loadWithoutMarks(Path dir) throws IOException {
		PerkRegistry.clear();
		PerkSetRegistry.clear();
		write(dir, PERKS_WITHOUT_MARKS);
	}

	private static void write(Path dir, String perks) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), perks, StandardCharsets.UTF_8);
		Files.writeString(dir.resolve(PerkSetRegistry.FILE_NAME), SETS, StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
		PerkSetRegistry.load(dir);
	}

	/** 번들에 들어 있는 진짜 증강 풀을 올린다. */
	private static void loadBundled(Path dir) throws IOException {
		try (InputStream bundled = DefenseSetRewardTest.class
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

	/**
	 * {@code PerkManager.refreshPlayer} 가 보고 갈라지는 판정 그대로다.
	 *
	 * @param index 그 증강의 몇 번째 최상위 효과인지
	 */
	private static boolean waives(TeamState state, String perkId, int index) {
		Perk perk = PerkRegistry.byId(perkId).orElseThrow();
		return PerkDrawbacks.waiverFor(state).waives(perk, perk.effects().get(index));
	}

	private static AttributeEffect attributeAt(String perkId, int index) {
		return assertInstanceOf(AttributeEffect.class,
				PerkRegistry.byId(perkId).orElseThrow().effects().get(index));
	}

	/** 최상위 효과와 {@code on_team_hurt}·{@code conditional} 하위 효과를 모두 편다. */
	private static List<PerkEffect> allEffectsOf(Perk perk) {
		List<PerkEffect> all = new ArrayList<>(perk.effects());
		for (PerkEffect effect : perk.effects()) {
			if (effect instanceof OnTeamHurtEffect onHurt) {
				all.addAll(onHurt.effects());
			} else if (effect instanceof ConditionalEffect conditional) {
				all.addAll(conditional.children());
			}
		}
		return all;
	}

	private static DamageSource explosion() {
		return new DamageSource(damageType(DamageTypes.EXPLOSION));
	}

	private static DamageSource arrow() {
		return new DamageSource(damageType(DamageTypes.ARROW));
	}

	private static Holder<DamageType> damageType(ResourceKey<DamageType> key) {
		return TestBootstrap.registries().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
	}
}
