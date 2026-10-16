package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.team.TeamState;
import net.minecraft.world.entity.EntityTypes;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 증강은 <b>엔더 드래곤을 강하게 만들지 않는다</b>(사람 결정 2026-10-16).
 *
 * <p>몹의 체력·공격력·이동 속도·행동 속도를 올리는 증강 효과는 모두
 * {@link MobPerkModifiers#sparingDragon} 을 지난다. 정의 파일의 {@code excludes} 에 드래곤을
 * 적는 것을 잊은 증강이 새로 들어와도 드래곤은 그대로라는 것을 여기서 못박는다.
 *
 * <p>드래곤 시련 자체의 강화(팀 인원 비례 체력 등)는 증강이 아니라 이 시험의 대상이 아니다.
 */
class PerkDragonSparingTest {

	/** 드래곤을 빼는 것을 <b>잊은</b> 정의들. 네 갈래를 하나씩 둔다. */
	private static final String PERKS = """
			{ "perks": [
			  { "id": "sharedfate:t_health", "rarity": "silver", "name": "체력 올림",
			    "effects": [ { "type": "mob_health", "multiplier": 1.5 } ] },
			  { "id": "sharedfate:t_damage", "rarity": "silver", "name": "공격력 올림",
			    "effects": [ { "type": "mob_damage", "multiplier": 1.3 } ] },
			  { "id": "sharedfate:t_speed", "rarity": "silver", "name": "속도 올림",
			    "effects": [ { "type": "mob_speed", "multiplier": 1.2 } ] },
			  { "id": "sharedfate:t_action", "rarity": "silver", "name": "행동 올림",
			    "effects": [ { "type": "mob_action_speed", "multiplier": 1.2 } ] },
			  { "id": "sharedfate:t_weaken", "rarity": "silver", "name": "체력 깎음",
			    "effects": [ { "type": "mob_health", "multiplier": 0.5 } ] },
			  { "id": "sharedfate:t_targeted", "rarity": "silver", "name": "드래곤을 직접 적음",
			    "effects": [ { "type": "mob_damage", "multiplier": 2.0,
			      "targets": [ "minecraft:ender_dragon", "minecraft:zombie" ] } ] }
			] }
			""";

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
	}

	@Test
	void 올리는_배율만_지운다() {
		assertEquals(1.0, MobPerkModifiers.sparingDragon(EntityTypes.ENDER_DRAGON, 1.5), 1.0e-9);
		assertEquals(0.5, MobPerkModifiers.sparingDragon(EntityTypes.ENDER_DRAGON, 0.5), 1.0e-9,
				"약하게 하는 쪽은 정의대로 둔다");
		assertEquals(1.5, MobPerkModifiers.sparingDragon(EntityTypes.ZOMBIE, 1.5), 1.0e-9,
				"드래곤이 아닌 몹은 그대로");
	}

	@Test
	void 제외를_잊은_정의도_드래곤을_강하게_하지_않는다(@TempDir Path dir) throws IOException {
		write(dir, PERKS);
		TeamState state = teamWith("sharedfate:t_health", "sharedfate:t_damage", "sharedfate:t_speed",
				"sharedfate:t_action");

		for (MobPerkModifiers.Kind kind : MobPerkModifiers.Kind.values()) {
			assertEquals(1.0, MobPerkModifiers.teamMultiplier(state, EntityTypes.ENDER_DRAGON, true, kind),
					1.0e-9, kind + " 이 드래곤에 걸렸다");
		}
		// 다른 적대 몹에는 그대로 걸린다. 지운 것은 드래곤 몫뿐이다.
		assertEquals(1.5, multiplier(state, MobPerkModifiers.Kind.HEALTH), 1.0e-9);
		assertEquals(1.3, multiplier(state, MobPerkModifiers.Kind.DAMAGE), 1.0e-9);
		assertEquals(1.2, multiplier(state, MobPerkModifiers.Kind.SPEED), 1.0e-9);
		assertEquals(1.2, multiplier(state, MobPerkModifiers.Kind.ACTION_SPEED), 1.0e-9);
	}

	@Test
	void targets_에_드래곤을_직접_적어도_강해지지_않는다(@TempDir Path dir) throws IOException {
		write(dir, PERKS);
		TeamState state = teamWith("sharedfate:t_targeted");

		assertEquals(1.0, MobPerkModifiers.teamMultiplier(state, EntityTypes.ENDER_DRAGON, true,
				MobPerkModifiers.Kind.DAMAGE), 1.0e-9);
		assertEquals(2.0, multiplier(state, MobPerkModifiers.Kind.DAMAGE), 1.0e-9);
	}

	@Test
	void 약하게_하는_정의는_드래곤에도_그대로_걸린다(@TempDir Path dir) throws IOException {
		write(dir, PERKS);
		TeamState state = teamWith("sharedfate:t_weaken");

		assertEquals(0.5, MobPerkModifiers.teamMultiplier(state, EntityTypes.ENDER_DRAGON, true,
				MobPerkModifiers.Kind.HEALTH), 1.0e-9, "드래곤을 뺄지는 정의의 excludes 가 정한다");
	}

	/**
	 * 기본 풀의 증강 <b>하나하나</b>가 드래곤의 네 갈래 배율 어느 것도 1 위로 올리지 않는다.
	 *
	 * <p>증강을 하나씩 따로 가진 팀으로 잰다. 여럿을 한꺼번에 들면 늘림과 줄임이 서로 가려
	 * 빠뜨린 것을 놓칠 수 있다.
	 */
	@Test
	void 기본_풀의_어느_증강도_드래곤을_강하게_하지_않는다(@TempDir Path dir) throws IOException {
		loadBundled(dir);

		List<String> broken = new ArrayList<>();
		for (Perk perk : PerkRegistry.all()) {
			TeamState state = teamWith(perk.id());
			for (MobPerkModifiers.Kind kind : MobPerkModifiers.Kind.values()) {
				double value = MobPerkModifiers.teamMultiplier(state, EntityTypes.ENDER_DRAGON, true, kind);
				if (value > 1.0 + 1.0e-9) {
					broken.add(perk.name() + " " + kind + " ×" + value);
				}
			}
		}
		assertTrue(broken.isEmpty(), "드래곤을 강하게 하는 증강: " + broken);
	}

	/**
	 * 몹을 강하게 하는 증강의 설명에는 「엔더 드래곤」이 적혀 있다.
	 *
	 * <p>좀비로 재어 1 보다 큰 갈래가 하나라도 있으면 「몹을 강하게 하는 증강」으로 본다.
	 */
	@Test
	void 몹을_강하게_하는_증강의_설명에는_드래곤_제외가_적혀_있다(@TempDir Path dir) throws IOException {
		loadBundled(dir);

		List<String> missing = new ArrayList<>();
		for (Perk perk : PerkRegistry.all()) {
			TeamState state = teamWith(perk.id());
			boolean strengthens = false;
			for (MobPerkModifiers.Kind kind : MobPerkModifiers.Kind.values()) {
				strengthens |= multiplier(state, kind) > 1.0 + 1.0e-9;
			}
			if (strengthens && !perk.description().contains("엔더 드래곤")) {
				missing.add(perk.name());
			}
		}
		assertTrue(missing.isEmpty(), "설명에 엔더 드래곤 제외가 없다: " + missing);
	}

	// ------------------------------------------------------------------ 도우미

	/** 기준 몹(좀비)에 걸리는 이 팀 몫의 배율. */
	private static double multiplier(TeamState state, MobPerkModifiers.Kind kind) {
		return MobPerkModifiers.teamMultiplier(state, EntityTypes.ZOMBIE, true, kind);
	}

	private static void write(Path dir, String perks) throws IOException {
		Files.writeString(dir.resolve(PerkRegistry.FILE_NAME), perks, StandardCharsets.UTF_8);
		PerkRegistry.load(dir);
	}

	private static void loadBundled(Path dir) throws IOException {
		try (InputStream bundled = PerkDragonSparingTest.class
				.getResourceAsStream("/sharedfate-perks-default.json")) {
			assertNotNull(bundled, "번들에 sharedfate-perks-default.json 이 없다");
			Files.copy(bundled, dir.resolve(PerkRegistry.FILE_NAME));
		}
		PerkRegistry.load(dir);
	}

	private static TeamState teamWith(String... perkIds) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		state.ownedPerks.addAll(List.of(perkIds));
		return state;
	}
}
