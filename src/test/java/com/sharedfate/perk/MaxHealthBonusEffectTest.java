package com.sharedfate.perk;

import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.MaxHealthBonusEffect;
import com.sharedfate.team.TeamState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 최대 체력을 올리는 증강({@code max_health_bonus})이 실제로 붙는지 본다.
 *
 * <p>속성 수정자를 실제로 거는 자리는 {@code MaxHealthAttribute} 이고 그건 살아 있는
 * 플레이어가 있어야 확인할 수 있다. 여기서는 그 자리에 넘겨줄 목표값을 정하는 계산
 * ({@link PerkHealthRules#effectiveMaxHealth})만 확인한다.
 */
class MaxHealthBonusEffectTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	// ------------------------------------------------------------------ 정의 읽기

	@Test
	void 더할_값을_읽는다() {
		PerkEffect effect = PerkEffectType.MAX_HEALTH_BONUS.create("sharedfate:테스트", 0,
				JsonParser.parseString("{ \"type\": \"max_health_bonus\", \"amount\": 6.0 }")
						.getAsJsonObject());

		assertInstanceOf(MaxHealthBonusEffect.class, effect);
		assertEquals(6.0F, ((MaxHealthBonusEffect) effect).amount());
	}

	@Test
	void 값이_없거나_0_이거나_범위를_벗어나면_버린다() {
		assertNull(PerkEffectType.MAX_HEALTH_BONUS.create("sharedfate:테스트", 0,
				JsonParser.parseString("{ \"type\": \"max_health_bonus\" }").getAsJsonObject()));
		assertNull(PerkEffectType.MAX_HEALTH_BONUS.create("sharedfate:테스트", 0,
				JsonParser.parseString("{ \"type\": \"max_health_bonus\", \"amount\": 0.0 }")
						.getAsJsonObject()));
		assertNull(PerkEffectType.MAX_HEALTH_BONUS.create("sharedfate:테스트", 0,
				JsonParser.parseString("{ \"type\": \"max_health_bonus\", \"amount\": 99999.0 }")
						.getAsJsonObject()));
	}

	// ------------------------------------------------------------------ 보너스 합산

	@Test
	void 증강이_없으면_보너스가_0_이다() {
		assertEquals(0.0, PerkHealthRules.bonusMaxHealth(null));
		assertEquals(0.0, PerkHealthRules.bonusMaxHealth(TeamState.fresh(20.0F)));
	}

	@Test
	void 가진_보너스를_모두_더한다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		TeamState state = perkTeam("sharedfate:피통");
		assertEquals(6.0, PerkHealthRules.bonusMaxHealth(state));

		state.ownedPerks.add("sharedfate:거인");
		assertEquals(26.0, PerkHealthRules.bonusMaxHealth(state), "6 + 20");
	}

	@Test
	void 풀에서_사라진_증강_id_는_건너뛴다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		assertEquals(0.0, PerkHealthRules.bonusMaxHealth(perkTeam("sharedfate:사라진것")));
	}

	@Test
	void 예전_형식인_attribute_max_health_도_보너스로_센다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		// max_health_bonus 가 생기기 전의 정의다. 이렇게 적힌 설정 파일이 이미 깔려 있으므로
		// 그대로 돌아가야 한다.
		assertEquals(6.0, PerkHealthRules.bonusMaxHealth(perkTeam("sharedfate:예전피통")));
		assertEquals(26.0F, PerkHealthRules.effectiveMaxHealth(perkTeam("sharedfate:예전피통")));
	}

	@Test
	void 예전_형식이라도_배율_연산은_세지_않는다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		assertEquals(0.0, PerkHealthRules.bonusMaxHealth(perkTeam("sharedfate:예전배율")),
				"무엇에 곱할지가 정해지지 않아 짐작할 수 없다");
	}

	@Test
	void 다른_속성_증강은_최대_체력에_끼어들지_않는다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		assertEquals(0.0, PerkHealthRules.bonusMaxHealth(perkTeam("sharedfate:공격")));
	}

	// ------------------------------------------------------------------ 최종 최대 체력

	@Test
	void 증강이_없으면_기본값_그대로다() {
		assertEquals(20.0F, PerkHealthRules.effectiveMaxHealth(TeamState.fresh(20.0F)));
		assertEquals(40.0F, PerkHealthRules.effectiveMaxHealth(TeamState.fresh(40.0F)));
	}

	@Test
	void 보너스는_기본값에_더해진다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		assertEquals(26.0F, PerkHealthRules.effectiveMaxHealth(perkTeam("sharedfate:피통")));
	}

	@Test
	void 몇_번을_다시_계산해도_불어나지_않는다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));
		TeamState state = perkTeam("sharedfate:피통");

		// 접속·부활·주기 점검마다 도는 길이다. 보너스를 maxHealth 에 직접 더했다면
		// 여기서 20 → 26 → 32 → … 로 끝없이 불어났을 것이다.
		for (int i = 0; i < 10; i++) {
			state.maxHealth = PerkHealthRules.effectiveMaxHealth(state);
		}

		assertEquals(26.0F, state.maxHealth);
		assertEquals(20.0F, state.baseMaxHealth, "기본값은 아무도 건드리지 않는다");
	}

	@Test
	void 명령으로_정한_값이_증강을_잃은_뒤_그대로_돌아온다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));
		TeamState state = perkTeam("sharedfate:피통");
		// /shareteam health 30 이 하는 일과 같다. 정하는 것은 언제나 기본값 쪽이다.
		state.baseMaxHealth = 30.0F;
		state.maxHealth = PerkHealthRules.effectiveMaxHealth(state);
		assertEquals(36.0F, state.maxHealth);

		// 회차 리셋으로 증강을 잃었다.
		state.ownedPerks.clear();
		state.maxHealth = PerkHealthRules.effectiveMaxHealth(state);

		assertEquals(30.0F, state.maxHealth, "명령으로 정한 값이 그대로 돌아와야 한다");
	}

	@Test
	void 고정_증강이_보너스를_이긴다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		TeamState state = perkTeam("sharedfate:피통");
		state.ownedPerks.add("sharedfate:거인");
		state.ownedPerks.add("sharedfate:고행자");

		assertEquals(10.0F, PerkHealthRules.effectiveMaxHealth(state),
				"고행자는 '다른 증강으로도 오르지 않는다'가 약속이다");
	}

	@Test
	void 고정이_먼저든_나중이든_결과가_같다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		TeamState locked = perkTeam("sharedfate:고행자");
		locked.ownedPerks.add("sharedfate:피통");

		TeamState bonusFirst = perkTeam("sharedfate:피통");
		bonusFirst.ownedPerks.add("sharedfate:고행자");

		assertEquals(PerkHealthRules.effectiveMaxHealth(locked),
				PerkHealthRules.effectiveMaxHealth(bonusFirst));
		assertEquals(10.0F, PerkHealthRules.effectiveMaxHealth(locked));
	}

	@Test
	void 상한과_하한을_넘지_않는다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(pool(dir));

		TeamState huge = perkTeam("sharedfate:거인");
		huge.baseMaxHealth = 1024.0F;
		assertEquals(1024.0F, PerkHealthRules.effectiveMaxHealth(huge));

		TeamState tiny = perkTeam("sharedfate:저주");
		tiny.baseMaxHealth = 20.0F;
		assertEquals(1.0F, PerkHealthRules.effectiveMaxHealth(tiny), "0 이하로 내려가면 접속하자마자 죽는다");
	}

	// ------------------------------------------------------------------ 저장 하위호환

	@Test
	void 증강이_없는_팀의_저장은_이_필드가_생기기_전과_같다() {
		CompoundTag encoded = encode(TeamState.fresh(40.0F));

		assertFalse(encoded.contains("baseMaxHealth"),
				"기본값과 상한이 같으면 아예 적지 않는다");
		assertEquals(40.0F, decode(encoded).baseMaxHealth);
	}

	@Test
	void 기본값_항목이_없는_기존_월드는_저장된_상한을_기본값으로_읽는다() {
		TeamState state = TeamState.fresh(20.0F);
		state.baseMaxHealth = 30.0F;
		state.maxHealth = 36.0F;
		CompoundTag encoded = encode(state);
		assertTrue(encoded.contains("baseMaxHealth"), "달라졌으면 적어야 한다");

		// 이 필드를 모르던 시절의 월드를 흉내 낸다.
		encoded.remove("baseMaxHealth");
		TeamState old = decode(encoded);

		assertEquals(36.0F, old.maxHealth);
		assertEquals(36.0F, old.baseMaxHealth,
				"그 월드에서 팀이 정한 값은 실제로 maxHealth 였다");
	}

	@Test
	void 기본값은_왕복_직렬화된다() {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.add("sharedfate:피통");
		state.baseMaxHealth = 30.0F;
		state.maxHealth = 36.0F;

		TeamState round = decode(encode(state));

		assertEquals(30.0F, round.baseMaxHealth);
		assertEquals(36.0F, round.maxHealth);
	}

	@Test
	void 전멸_뒤_되돌리기는_기본값을_건드리지_않는다() {
		TeamState state = TeamState.fresh(20.0F);
		state.baseMaxHealth = 30.0F;
		state.maxHealth = 36.0F;
		state.health = 4.0F;

		// 전멸 처리는 보유 증강을 그대로 두므로 상한도 그대로여야 한다.
		state.resetAfterDeath(state.maxHealth, true);

		assertEquals(36.0F, state.maxHealth);
		assertEquals(36.0F, state.health);
		assertEquals(30.0F, state.baseMaxHealth);
	}

	// ------------------------------------------------------------------ 기본 증강 풀

	@Test
	void 기본_풀의_최대_체력_증강들이_실제로_붙는다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(bundledDefaultPool(dir));

		assertEquals(26.0F,
				PerkHealthRules.effectiveMaxHealth(perkTeam("sharedfate:health_over_helmet")),
				"실버 「뚝배기 대신 피통」 은 최대 체력 +6 이다");
		assertEquals(35.0F,
				PerkHealthRules.effectiveMaxHealth(perkTeam("sharedfate:blind_giant")),
				"프리즘 「장님 거인」 은 최대 체력 +15 이다");
		assertEquals(16.0F,
				PerkHealthRules.effectiveMaxHealth(perkTeam("sharedfate:ascetic")),
				"프리즘 「고행자」 는 최대 체력 16 고정이다 — 12 는 너무 빡빡해 2026-09-30 에 올렸다");
	}

	@Test
	void 기본_풀은_최대_체력을_속성으로_올리지_않는다(@TempDir Path dir) throws IOException {
		PerkRegistry.load(bundledDefaultPool(dir));

		for (Perk perk : PerkRegistry.all()) {
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof com.sharedfate.perk.effect.AttributeEffect attribute) {
					assertFalse("minecraft:max_health".equals(attribute.attributeId().toString()),
							perk.id() + " 가 최대 체력을 속성으로 올리려 한다");
				}
			}
		}
	}

	// ------------------------------------------------------------------ 도우미

	private static CompoundTag encode(TeamState state) {
		return (CompoundTag) TeamState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
	}

	private static TeamState decode(CompoundTag tag) {
		return TeamState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
	}

	/** 기본 최대 체력 20 에 증강 하나를 든 팀. */
	private static TeamState perkTeam(String perkId) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.add(perkId);
		return state;
	}

	/** 모드에 들어 있는 기본 증강 풀을 그대로 꺼내 놓는다. */
	private static Path bundledDefaultPool(Path dir) throws IOException {
		try (InputStream bundled = MaxHealthBonusEffectTest.class
				.getResourceAsStream("/sharedfate-perks-default.json")) {
			Files.copy(bundled, dir.resolve(PerkRegistry.FILE_NAME));
		}
		return dir;
	}

	/** 최대 체력을 건드리는 증강 넷을 담은 풀. */
	private static Path pool(Path dir) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), """
				{
				  "perks": [
				    { "id": "sharedfate:피통", "rarity": "silver", "name": "뚝배기 대신 피통",
				      "effects": [ { "type": "max_health_bonus", "amount": 6.0 } ] },
				    { "id": "sharedfate:거인", "rarity": "prism", "name": "장님 거인",
				      "effects": [ { "type": "max_health_bonus", "amount": 20.0 } ] },
				    { "id": "sharedfate:저주", "rarity": "gold", "name": "저주",
				      "effects": [ { "type": "max_health_bonus", "amount": -40.0 } ] },
				    { "id": "sharedfate:고행자", "rarity": "prism", "name": "고행자",
				      "effects": [ { "type": "max_health_lock", "value": 10.0 } ] },
				    { "id": "sharedfate:예전피통", "rarity": "silver", "name": "예전 형식 피통",
				      "effects": [ { "type": "attribute", "attribute": "minecraft:max_health",
				                     "operation": "add_value", "amount": 6.0 } ] },
				    { "id": "sharedfate:예전배율", "rarity": "silver", "name": "예전 형식 배율",
				      "effects": [ { "type": "attribute", "attribute": "minecraft:max_health",
				                     "operation": "add_multiplied_total", "amount": 0.5 } ] },
				    { "id": "sharedfate:공격", "rarity": "silver", "name": "공격력",
				      "effects": [ { "type": "attribute", "attribute": "minecraft:attack_damage",
				                     "operation": "add_value", "amount": 2.0 } ] }
				  ]
				}
				""", StandardCharsets.UTF_8);
		return dir;
	}
}
