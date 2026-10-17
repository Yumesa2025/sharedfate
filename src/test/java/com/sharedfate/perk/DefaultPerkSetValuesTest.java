package com.sharedfate.perk;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.AttributeEffect;
import com.sharedfate.perk.effect.BonusDropEffect;
import com.sharedfate.perk.effect.InvulnerableEffect;
import com.sharedfate.perk.effect.LifestealEffect;
import com.sharedfate.perk.effect.MaxHealthBonusEffect;
import com.sharedfate.perk.effect.OnSwapEffect;
import com.sharedfate.perk.effect.StatusEffectPerk;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 번들 기본 세트 정의({@code sharedfate-sets-default.json})가 그대로 읽히는지, 값이 정한 대로
 * 들어갔는지 본다.
 *
 * <p>첫 시험이 지키는 것은 개수가 아니라 <b>조용한 실패</b>다 — {@link PerkSetRegistry} 는
 * 읽을 수 없는 단계를 만나면 그것 하나만 건너뛰므로, 새 효과 타입을 만들고
 * {@link PerkEffectType} 에 등록하는 줄을 빠뜨리면 빌드도 통과하고 서버도 뜨는데 그 단계만
 * 사라진다.
 */
class DefaultPerkSetValuesTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkSetRegistry.clear();
		PerkRegistry.clear();
	}

	// ------------------------------------------------------------------ 조용한 실패 막기

	/**
	 * 기본 세트 정의가 <b>한 단계도 버려지지 않고</b> 전부 읽힌다.
	 *
	 * <p>기대값을 여기 손으로 적지 않고 <b>파일 자신을 다시 세어</b> 비교한다. 그래야 단계를
	 * 더하거나 뺄 때마다 이 시험을 함께 고치지 않아도 되고, 그러면서도 「파일에 적힌 것 중
	 * 하나가 조용히 사라졌다」는 정확히 잡힌다.
	 */
	@Test
	void 기본_세트_정의는_하나도_버려지지_않고_읽힌다(@TempDir Path dir) throws IOException {
		load();
		JsonObject raw = bundled();

		int declaredTypes = 0;
		int declaredTiers = 0;
		for (JsonElement element : raw.getAsJsonArray("sets")) {
			JsonArray tiers = element.getAsJsonObject().getAsJsonArray("tiers");
			int size = tiers == null ? 0 : tiers.size();
			declaredTiers += size;
			// 단계가 하나도 없는 유형(무기)은 레지스트리 표에 들어가지 않는다.
			if (size > 0) {
				declaredTypes++;
			}
		}

		Map<PerkSetType, List<PerkSets.Tier>> loaded = PerkSetRegistry.all();
		int loadedTiers = loaded.values().stream().mapToInt(List::size).sum();

		assertEquals(declaredTypes, loaded.size(),
				"파일에 적힌 유형 수와 읽힌 수가 다르면 유형 이름이 틀렸거나 단계가 통째로 버려진 것이다");
		assertEquals(declaredTiers, loadedTiers,
				"파일에 적힌 단계 수와 읽힌 수가 다르면 효과 타입이 PerkEffectType 에 등록되지 않은 것이다");
	}

	/** 파일에 적힌 {@code type} 은 모두 실제 {@link PerkSetType} 이다. */
	@Test
	void 파일의_모든_유형이_실제로_있는_유형이다() throws IOException {
		JsonObject raw = bundled();
		Set<PerkSetType> seen = new HashSet<>();

		for (JsonElement element : raw.getAsJsonArray("sets")) {
			String id = element.getAsJsonObject().get("type").getAsString();
			PerkSetType type = PerkSetType.fromId(id);
			assertNotNull(type, "알 수 없는 세트 유형: " + id);
			assertTrue(seen.add(type), "세트 유형이 두 번 적혀 있다: " + id);
		}
	}

	/**
	 * 유형 전부가 파일에 있다.
	 *
	 * <p>보상이 아직 없는 유형(무기)도 <b>항목 자체는 있어야</b> 한다. 나중에 값만 넣으면 켜지게
	 * 자리를 잡아 둔 것이라, 항목을 지우면 그 자리가 어디였는지 잊힌다.
	 */
	@Test
	void 유형_전부가_파일에_있다() throws IOException {
		JsonObject raw = bundled();
		Set<PerkSetType> seen = new HashSet<>();
		for (JsonElement element : raw.getAsJsonArray("sets")) {
			seen.add(PerkSetType.fromId(element.getAsJsonObject().get("type").getAsString()));
		}

		for (PerkSetType type : PerkSetType.values()) {
			assertTrue(seen.contains(type), type.displayName() + " 항목이 파일에 없다");
		}
	}

	// ------------------------------------------------------------------ 수정자 이름 충돌

	/**
	 * 세트가 거는 속성 수정자 이름이 증강 것과 <b>절대 겹치지 않는다.</b>
	 *
	 * <p>{@link AttributeEffect#modifierId} 는 {@code perk/<증강 id>/<순번>} 으로 이름을 만들고,
	 * {@code apply} 는 같은 이름의 수정자를 먼저 지운 뒤 새로 건다. 세트가 증강과 같은 이름을
	 * 쓰면 세트가 켜졌다 꺼질 때 <b>증강이 걸어 둔 수정자를 지운다.</b> 그러면 증강이 조용히
	 * 무력해지고, 원인을 찾기가 대단히 어렵다.
	 *
	 * <p>순번은 하위 효과 때문에 커질 수 있으므로 넉넉한 범위를 함께 본다.
	 */
	@Test
	void 세트_수정자_id는_증강_것과_겹치지_않는다(@TempDir Path dir) throws IOException {
		load();

		Set<Identifier> perkIds = new HashSet<>();
		for (Perk perk : PerkRegistry.all()) {
			for (int index = 0; index < 64; index++) {
				perkIds.add(AttributeEffect.modifierId(perk.id(), index));
			}
		}

		List<PerkSets.Tier> tiers = new ArrayList<>();
		PerkSetRegistry.all().values().forEach(tiers::addAll);
		assertFalse(tiers.isEmpty(), "세트 단계를 하나도 읽지 못했다");

		for (PerkSets.Tier tier : tiers) {
			String owner = PerkSetRegistry.effectOwnerId(tier.type(), tier.count());
			for (int index = 0; index < 64; index++) {
				Identifier id = AttributeEffect.modifierId(owner, index);
				assertFalse(perkIds.contains(id),
						"세트 " + owner + " 의 수정자 이름 " + id + " 가 증강 것과 겹친다");
			}
		}
	}

	// ------------------------------------------------------------------ 유형별 개수

	/** 유형이 둘인 증강은 실제 풀에서도 양쪽에 세어진다. */
	@Test
	void 원정_준비물과_피의_대가는_두_유형에_다_세어진다(@TempDir Path dir) throws IOException {
		load();

		Map<PerkSetType, Integer> counts = PerkSets.countByType(
				List.of("sharedfate:expedition_kit", "sharedfate:price_of_blood"),
				id -> PerkRegistry.byId(id).orElse(null));

		assertEquals(1, counts.get(PerkSetType.SUPPLY), "원정 준비물 — 보급");
		assertEquals(1, counts.get(PerkSetType.GAMBLE), "원정 준비물 — 도박");
		assertEquals(1, counts.get(PerkSetType.POWER), "피의 대가 — 화력");
		assertEquals(1, counts.get(PerkSetType.RECOVERY), "피의 대가 — 회복");
	}

	// ------------------------------------------------------------------ 값 여섯

	@Test
	void 화력_2단계는_공격력_2다(@TempDir Path dir) throws IOException {
		AttributeEffect attack = attribute(tier(PerkSetType.POWER, 2), "minecraft:attack_damage");

		assertEquals(2.0, attack.amount(), 1.0e-9);
		assertEquals(AttributeModifier.Operation.ADD_VALUE, attack.operation());
	}

	/**
	 * 생존 2단계는 {@code max_health_bonus} 로 적혀 있다.
	 *
	 * <p>{@code attribute} + {@code minecraft:max_health} 로 적으면 {@code MaxHealthAttribute}
	 * 의 덮어쓰기가 정확히 상쇄해 아무 일도 일어나지 않는다. 그 함정에 다시 빠지면 여기서 걸린다.
	 */
	@Test
	void 생존_2단계는_max_health_bonus_로_체력_6이다(@TempDir Path dir) throws IOException {
		PerkSets.Tier tier = tier(PerkSetType.SURVIVAL, 2);
		MaxHealthBonusEffect bonus =
				assertInstanceOf(MaxHealthBonusEffect.class, tier.effects().get(0));

		assertEquals(6.0F, bonus.amount(), 1.0e-6F);
		assertTrue(tier.effects().stream().noneMatch(effect ->
						effect instanceof AttributeEffect attribute
								&& attribute.attributeId().toString().equals("minecraft:max_health")),
				"최대 체력을 attribute 로 적으면 안 된다");
	}

	/** 기동 3단계는 낙하 피해 −90% 다. {@code add_multiplied_total} 이라 배율은 {@code 1 + amount}. */
	@Test
	void 기동_3단계는_낙하_피해_90퍼센트_감소다(@TempDir Path dir) throws IOException {
		AttributeEffect fall =
				attribute(tier(PerkSetType.MOBILITY, 3), "minecraft:fall_damage_multiplier");

		assertEquals(-0.9, fall.amount(), 1.0e-9);
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, fall.operation());
	}

	@Test
	void 화력_3단계는_흡혈_15퍼센트다(@TempDir Path dir) throws IOException {
		LifestealEffect lifesteal = assertInstanceOf(LifestealEffect.class,
				tier(PerkSetType.POWER, 3).effects().get(0));

		assertEquals(0.15, lifesteal.fractionFor(), 1.0e-9);
	}

	/**
	 * 화력 4단계는 근접 공격이 닿는 거리를 50% 늘린다.
	 *
	 * <p>{@code add_multiplied_total} 이라 배율은 {@code 1 + amount} 다. 26.2 의
	 * {@code minecraft:entity_interaction_range} 는 기본값 3.0 · 범위 0~64 이므로 4.5 가 된다.
	 *
	 * <p><b>이 속성은 {@code setSyncable(true)} 로 등록되어 있다</b>(26.2 {@code Attributes}
	 * 바이트코드에서 확인). 공격이 닿는지는 클라이언트가 먼저 재고 서버가 다시 재는데, 동기화가
	 * 안 되는 속성이면 클라이언트가 3.0 으로 재서 <b>서버만 아는 값</b>이 된다 — 늘어난 거리에서
	 * 휘둘러도 애초에 공격 패킷이 가지 않으므로 아무 일도 일어나지 않는다. {@code mining_speed}
	 * 가 정확히 그 이유로 「느리게 하는 데만 쓸 수 있는」 타입이 됐다. 자동으로 내려가는 값이라
	 * <b>새 패킷도 통신 규약 인상도 필요 없다.</b>
	 *
	 * <p><b>단계는 누적이다</b> — 넷을 모으면 2·3단계도 함께 켜진다. 그래서 4단계에 공격력이나
	 * 흡혈을 또 적으면 앞 단계와 겹쳐 두 번 걸린다. 여기서 <b>효과가 하나뿐</b>인지까지 보는
	 * 이유가 그것이다.
	 */
	@Test
	void 화력_4단계는_근접_사거리_75퍼센트_증가뿐이다(@TempDir Path dir) throws IOException {
		PerkSets.Tier tier = tier(PerkSetType.POWER, 4);
		AttributeEffect reach = attribute(tier, "minecraft:entity_interaction_range");

		assertEquals(0.75, reach.amount(), 1.0e-9);
		assertEquals(AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL, reach.operation());
		assertEquals(1, tier.effects().size(), "화력 4단계에는 대가가 붙지 않는다");
	}

	/** 채굴 3단계는 다이아 광석에서 확정으로 2개를 더 준다. */
	@Test
	void 채굴_3단계는_다이아를_2개_더_준다(@TempDir Path dir) throws IOException {
		BonusDropEffect bonus = assertInstanceOf(BonusDropEffect.class,
				tier(PerkSetType.MINING, 3).effects().get(0));

		assertEquals(1.0, bonus.chanceFor(), 1.0e-9);
		assertEquals(2, bonus.extra());
		assertEquals(0, bonus.extraDurability(), "세트에는 대가가 없다");
		// 태그가 아직 묶이지 않은 자리에서도 판정이 되도록 블록 id 도 함께 적어 둔다.
		assertTrue(bonus.blocks().blockIds().stream()
						.anyMatch(id -> id.toString().equals("minecraft:diamond_ore")),
				"태그만 적으면 태그가 안 묶인 시점에 아무 데도 안 걸린다");
	}

	/**
	 * 교환 2단계는 교환 시점에 <b>10초</b>짜리 상태이상 넷을 얹는다.
	 *
	 * <p>0.29.2-dev 에서 4초 → 10초가 됐다. 위치 교환 주기가 가장 짧아도 1분이라 두 창이 겹칠
	 * 일은 없다.
	 */
	@Test
	void 교환_2단계는_10초짜리_버프_넷이다(@TempDir Path dir) throws IOException {
		OnSwapEffect onSwap = assertInstanceOf(OnSwapEffect.class,
				tier(PerkSetType.SWAP, 2).effects().get(0));

		assertEquals(4, onSwap.grants().size());
		for (OnSwapEffect.Grant grant : onSwap.grants()) {
			assertEquals(200, grant.durationTicks(), "10초 = 200틱");
			assertInstanceOf(StatusEffectPerk.class, grant.effect());
		}
	}

	/**
	 * 교환 3단계도 <b>10초</b>다. 2단계와 길이를 맞춰 둔다.
	 *
	 * <p>둘이 어긋나면 「교환 직후 얼마 동안 세냐」가 단계마다 달라져서, 실제로 받는 사람이
	 * 언제까지 안전한지 알 수 없게 된다.
	 *
	 * <p>저항이 아니라 <b>무적</b>({@code invulnerable})이어야 한다. 저항은 겹치면 긴 쪽 하나만
	 * 남아 「본진이 바뀐다」의 3초가 이 10초에 묻힌다. 무적은 시간이 합쳐져 둘을 함께 가지면
	 * 13초다({@link PerkInvulnerability}).
	 */
	@Test
	void 교환_3단계도_10초다(@TempDir Path dir) throws IOException {
		OnSwapEffect onSwap = assertInstanceOf(OnSwapEffect.class,
				tier(PerkSetType.SWAP, 3).effects().get(0));

		assertEquals(1, onSwap.grants().size());
		assertEquals(200, onSwap.grants().get(0).durationTicks(), "10초 = 200틱");
		assertInstanceOf(InvulnerableEffect.class, onSwap.grants().get(0).effect(),
				"저항이면 「본진이 바뀐다」의 3초와 합쳐지지 않는다");
	}

	/**
	 * 무기에도 보상이 있다.
	 *
	 * <p>단계가 하나도 없으면 여섯을 다 모아도 아무 일이 안 일어나고 화면에는 「무기 2/2」만
	 * 뜬다 — 켜졌는데 보상이 없는 것과 구별되지 않는다. 그래서 <b>단계가 하나라도 있는지</b>를
	 * 여기서 못박는다.
	 */
	@Test
	void 무기에도_보상이_있다(@TempDir Path dir) throws IOException {
		load();

		List<PerkSets.Tier> tiers = PerkSetRegistry.tiersOf(PerkSetType.WEAPON);
		assertFalse(tiers.isEmpty(), "무기에 단계가 하나도 없으면 여섯을 모아도 아무 일이 없다");
		for (PerkSets.Tier tier : tiers) {
			assertFalse(tier.isPlaceholder(),
					"무기 " + tier.count() + "단계에 효과가 없다 — 켜져도 아무 일이 안 일어난다");
		}
	}

	// ------------------------------------------------------------------ 도우미

	/** 모드 안의 기본 정의를 두 레지스트리에 모두 올린다. */
	private static void load() {
		if (!PerkRegistry.isLoaded()) {
			PerkRegistry.loadBundled();
		}
		if (!PerkSetRegistry.isLoaded()) {
			PerkSetRegistry.loadBundled();
		}
	}

	/** 번들에 들어 있는 세트 정의 원본을 그대로 읽는다. */
	private static JsonObject bundled() throws IOException {
		try (InputStream stream = DefaultPerkSetValuesTest.class
				.getResourceAsStream("/sharedfate-sets-default.json")) {
			assertNotNull(stream, "번들에 sharedfate-sets-default.json 이 없다");
			return JsonParser.parseReader(
					new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	private static PerkSets.Tier tier(PerkSetType type, int count) {
		load();
		return PerkSetRegistry.tiersOf(type).stream()
				.filter(candidate -> candidate.count() == count)
				.findFirst()
				.orElseThrow(() -> new AssertionError(type.displayName() + " " + count + "단계가 없다"));
	}

	private static AttributeEffect attribute(PerkSets.Tier tier, String attributeId) {
		return tier.effects().stream()
				.filter(effect -> effect instanceof AttributeEffect candidate
						&& candidate.attributeId().toString().equals(attributeId))
				.map(AttributeEffect.class::cast)
				.findFirst()
				.orElseThrow(() -> new AssertionError(
						attributeId + " 효과가 없다: " + tier.type() + " " + tier.count()));
	}
}
