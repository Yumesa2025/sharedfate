package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.team.ShareTeam;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamRosterStoreTest {
	private static final UUID TEAM = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID A = UUID.fromString("20000000-0000-0000-0000-000000000001");
	/**
	 * 지금 쓰는 명단 형식. {@code TeamRosterStore.FORMAT_VERSION} 을 올릴 때 여기도 올린다.
	 */
	private static final String CURRENT_FORMAT = "\"formatVersion\": 7";

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}
	private static final UUID B = UUID.fromString("20000000-0000-0000-0000-000000000002");

	@Test
	void 서버_루트_명단은_팀_식별자와_멤버_순서를_왕복한다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		ShareTeam team = new ShareTeam(TEAM, "원정대", List.of(A, B));

		TeamRosterStore.save(file, List.of(new TeamRosterStore.RestoredTeam(team, false, 20.0F, 0, false, false)));

		assertEquals(List.of(team),
				TeamRosterStore.load(file).stream()
						.map(TeamRosterStore.RestoredTeam::team).toList());
		assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains(CURRENT_FORMAT));
	}

	@Test
	void 팀_설정은_회차를_넘겨_그대로_돌아온다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		ShareTeam team = new ShareTeam(TEAM, "원정대", List.of(A, B));

		TeamRosterStore.save(file,
				List.of(new TeamRosterStore.RestoredTeam(team, true, 34.0F, 2400, true, true)));

		TeamRosterStore.RestoredTeam loaded = TeamRosterStore.load(file).getFirst();
		assertTrue(loaded.perksEnabled());
		assertEquals(34.0F, loaded.maxHealth());
		assertEquals(2400, loaded.swapIntervalTicks());
		assertTrue(loaded.damageAlertEnabled());
		assertTrue(loaded.deathAlertEnabled());
	}

	@Test
	void 난이도_상승_켜고_끄기도_회차를_넘겨_이어진다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		ShareTeam team = new ShareTeam(TEAM, "원정대", List.of(A));

		TeamRosterStore.save(file, List.of(new TeamRosterStore.RestoredTeam(
				team, true, 20.0F, 0, false, false, List.of(), true)));

		assertTrue(TeamRosterStore.load(file).getFirst().difficultyEscalationEnabled());
	}

	@Test
	void 드래곤_시련_켜고_끄기도_회차를_넘겨_이어진다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		ShareTeam team = new ShareTeam(TEAM, "원정대", List.of(A));

		TeamRosterStore.save(file, List.of(new TeamRosterStore.RestoredTeam(
				team, true, 20.0F, 0, false, false, List.of(), false, 3, true)));

		assertTrue(TeamRosterStore.load(file).getFirst().dragonTrialsEnabled());
	}

	/**
	 * 형식 6 이하에는 이 항목이 없다. 기본값이 곧 <b>끔</b>이라 Gson 이 두는 false 가 그대로
	 * 맞는 값이고, 따로 되돌릴 것이 없다 — 다시 뽑기 횟수와 다른 점이 이것이다.
	 */
	@Test
	void 드래곤_시련_항목이_없는_예전_형식은_꺼진_채로_읽힌다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		Files.writeString(file, """
				{
				  "formatVersion": 6,
				  "teams": [
				    {
				      "teamId": "%s",
				      "name": "원정대",
				      "members": ["%s"],
				      "settings": { "perksEnabled": true, "maxHealth": 20.0,
				                    "swapIntervalTicks": 0, "rerollCount": 3 }
				    }
				  ]
				}
				""".formatted(TEAM, A), StandardCharsets.UTF_8);

		TeamRosterStore.RestoredTeam loaded = TeamRosterStore.load(file).getFirst();
		assertFalse(loaded.dragonTrialsEnabled());
		assertTrue(loaded.perksEnabled(), "시련과 무관한 값은 그대로 읽혀야 한다");
	}

	@Test
	void 난이도_항목이_없는_예전_형식은_꺼진_채로_읽힌다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		Files.writeString(file, """
				{
				  "formatVersion": 4,
				  "teams": [
				    {
				      "teamId": "%s",
				      "name": "원정대",
				      "members": ["%s"],
				      "settings": { "perksEnabled": true, "maxHealth": 20.0, "swapIntervalTicks": 0 }
				    }
				  ]
				}
				""".formatted(TEAM, A), StandardCharsets.UTF_8);

		TeamRosterStore.RestoredTeam loaded = TeamRosterStore.load(file).getFirst();
		assertFalse(loaded.difficultyEscalationEnabled());
		assertTrue(loaded.perksEnabled(), "난이도와 무관한 값은 그대로 읽혀야 한다");
	}

	@Test
	void 알림_항목이_없는_형식_2_명단은_둘_다_꺼진_채로_읽힌다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		// 설정 묶음은 있는데 알림 두 항목만 없다.
		Files.writeString(file, """
				{
				  "formatVersion": 2,
				  "teams": [
				    {
				      "teamId": "%s",
				      "name": "원정대",
				      "members": ["%s"],
				      "settings": {
				        "perksEnabled": true,
				        "maxHealth": 34.0,
				        "swapIntervalTicks": 2400
				      }
				    }
				  ]
				}
				""".formatted(TEAM, A), StandardCharsets.UTF_8);

		TeamRosterStore.RestoredTeam loaded = TeamRosterStore.load(file).getFirst();
		assertTrue(loaded.perksEnabled(), "예전 항목은 그대로 읽혀야 한다");
		assertEquals(2400, loaded.swapIntervalTicks());
		assertFalse(loaded.damageAlertEnabled());
		assertFalse(loaded.deathAlertEnabled());
	}

	@Test
	void 설정이_없던_예전_형식도_읽고_기본값으로_시작한다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		Files.writeString(file, """
				{"formatVersion":1,"teams":[{"teamId":"10000000-0000-0000-0000-000000000001",
				"name":"원정대","members":["20000000-0000-0000-0000-000000000001"]}]}
				""", StandardCharsets.UTF_8);

		TeamRosterStore.RestoredTeam loaded = TeamRosterStore.load(file).getFirst();
		assertEquals("원정대", loaded.team().name());
		assertFalse(loaded.perksEnabled());
		assertEquals(0, loaded.swapIntervalTicks());
	}

	@Test
	void 손상된_명단은_빈_팀으로_조용히_바꾸지_않고_거부한다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		Files.writeString(file, "{\"formatVersion\":2,\"teams\":[{\"teamId\":\"bad\"}]}",
				StandardCharsets.UTF_8);

		assertThrows(java.io.IOException.class, () -> TeamRosterStore.load(file));
	}

	@Test
	void 모르는_형식_번호는_거부한다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		Files.writeString(file, "{\"formatVersion\":99,\"teams\":[]}", StandardCharsets.UTF_8);

		assertThrows(java.io.IOException.class, () -> TeamRosterStore.load(file));
	}

	// ------------------------------------------------------------------ 유산(legacyGear)

	@Test
	void 유산으로_몰수한_아이템도_회차를_넘겨_왕복한다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		ShareTeam team = new ShareTeam(TEAM, "원정대", List.of(A, B));
		ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
		ItemStack helmet = new ItemStack(Items.DIAMOND_HELMET);

		TeamRosterStore.save(file, List.of(new TeamRosterStore.RestoredTeam(
				team, true, 20.0F, 0, false, false, List.of(pickaxe, helmet))));

		List<ItemStack> loaded = TeamRosterStore.load(file).getFirst().legacyGear();
		assertEquals(2, loaded.size());
		assertTrue(loaded.get(0).is(Items.DIAMOND_PICKAXE));
		assertTrue(loaded.get(1).is(Items.DIAMOND_HELMET));
		assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains(CURRENT_FORMAT));
	}

	@Test
	void 유산을_쓰지_않는_팀은_기존_생성자로도_저장할_수_있다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		ShareTeam team = new ShareTeam(TEAM, "원정대", List.of(A));

		TeamRosterStore.save(file, List.of(new TeamRosterStore.RestoredTeam(team, false, 20.0F, 0, false, false)));

		assertTrue(TeamRosterStore.load(file).getFirst().legacyGear().isEmpty());
	}

	@Test
	void legacyGear_가_없는_예전_형식도_빈_목록으로_읽힌다(@TempDir Path server) throws Exception {
		Path file = server.resolve(TeamRosterStore.FILE_NAME);
		Files.writeString(file, """
				{
				  "formatVersion": 3,
				  "teams": [
				    {
				      "teamId": "%s",
				      "name": "원정대",
				      "members": ["%s"],
				      "settings": { "perksEnabled": true, "maxHealth": 34.0, "swapIntervalTicks": 0 }
				    }
				  ]
				}
				""".formatted(TEAM, A), StandardCharsets.UTF_8);

		assertTrue(TeamRosterStore.load(file).getFirst().legacyGear().isEmpty());
	}
}
