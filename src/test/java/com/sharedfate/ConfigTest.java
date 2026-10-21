package com.sharedfate;

import com.sharedfate.config.SharedFateConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigTest {

	@Test
	void 파일이_없으면_기본값을_쓰고_파일을_만든다(@TempDir Path dir) {
		Path file = dir.resolve("sharedfate.json");

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertEquals(4, config.maxTeamSize);
		assertTrue(config.singleTeamOnly, "서버 팀 하나 제한은 기본으로 켜져 있어야 한다");
		assertEquals(20.0, config.sharedMaxHealth);
		assertEquals(6, config.mainInventoryRows);
		assertTrue(config.shareEnderChest);
		assertTrue(config.shareExperience);
		assertTrue(config.shareStatusEffects);
		assertEquals(30, config.damageAlertDurationTicks);
		assertTrue(config.requireClientMod);
		assertTrue(config.resetWorldOnTeamDeath);
		// 게임 오버 카운트다운 5초.
		assertEquals(100, config.worldResetDelayTicks);
		assertTrue(config.silenceAdvancementMessages,
				"발전과제 달성 알림은 기본으로 꺼져 있어야 한다");
		assertTrue(config.showRunBossBar);
		assertTrue(config.dragonKillEndsRun);
		assertEquals(100, config.victoryCreditsDelayTicks);
		// 드래곤을 잡고 10초 뒤에 정산이 시작된다.
		assertEquals(200, config.victoryTitleDelayTicks);
		// 제목이 뜨고 3.5초 뒤에 부제가 따라 붙는다.
		assertEquals(70, config.victorySubtitleDelayTicks);
		// 부제가 뜬 뒤 다음 장까지 5초.
		assertEquals(100, config.victoryFireworkDelayTicks);
		assertEquals(1.2, config.experienceMultiplier, 1.0e-9, "경험치는 기본 1.2배로 들어온다");
		assertTrue(Files.exists(file), "설정 파일이 생성되어야 한다");
	}

	@Test
	void 경험치_배율은_바꿔서_저장할_수_있다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, "{\"experienceMultiplier\": 2.0}", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertEquals(2.0, config.experienceMultiplier, 1.0e-9);
		config.save(file);
		assertEquals(2.0, SharedFateConfig.loadOrCreate(file).experienceMultiplier, 1.0e-9);
	}

	/**
	 * 0 이면 경험치가 아예 나오지 않아 마법·수선이 통째로 막힌다. 그런 값은 조용히 받지 않는다.
	 */
	@Test
	void 경험치_배율이_범위를_벗어나면_기본값으로_되돌린다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		for (String invalid : new String[] {"0", "-1", "0.05", "11", "1e308"}) {
			Files.writeString(file, "{\"experienceMultiplier\":" + invalid + "}", StandardCharsets.UTF_8);
			assertEquals(1.2, SharedFateConfig.loadOrCreate(file).experienceMultiplier, 1.0e-9, invalid);
		}

		Files.writeString(file, "{\"experienceMultiplier\": 10}", StandardCharsets.UTF_8);
		assertEquals(10.0, SharedFateConfig.loadOrCreate(file).experienceMultiplier, 1.0e-9,
				"상한 자체는 받는다");
	}

	@Test
	void 세트_보정_확률은_기본_50이고_범위_밖은_잘라_쓴다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		assertEquals(50, SharedFateConfig.loadOrCreate(file).setFocusPercent);

		Files.writeString(file, "{\"setFocusPercent\": 0}", StandardCharsets.UTF_8);
		assertEquals(0, SharedFateConfig.loadOrCreate(file).setFocusPercent, "0 은 끔");
		Files.writeString(file, "{\"setFocusPercent\": 100}", StandardCharsets.UTF_8);
		assertEquals(100, SharedFateConfig.loadOrCreate(file).setFocusPercent);
		Files.writeString(file, "{\"setFocusPercent\": 250}", StandardCharsets.UTF_8);
		assertEquals(100, SharedFateConfig.loadOrCreate(file).setFocusPercent);
		Files.writeString(file, "{\"setFocusPercent\": -5}", StandardCharsets.UTF_8);
		assertEquals(0, SharedFateConfig.loadOrCreate(file).setFocusPercent);
	}

	@Test
	void 저장한_값을_다시_읽는다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);
		config.maxTeamSize = 8;
		config.sharedMaxHealth = 60.0;
		config.save(file);

		SharedFateConfig reloaded = SharedFateConfig.loadOrCreate(file);

		assertEquals(8, reloaded.maxTeamSize);
		assertEquals(60.0, reloaded.sharedMaxHealth);
	}

	@Test
	void 누락된_키는_기본값을_유지한다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, "{\"maxTeamSize\": 6}", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertEquals(6, config.maxTeamSize);
		assertEquals(20.0, config.sharedMaxHealth);
		assertEquals(6, config.mainInventoryRows);
		assertTrue(config.shareEnderChest);
		assertTrue(config.singleTeamOnly);
		assertEquals(30, config.damageAlertDurationTicks);
	}

	@Test
	void 서버_팀_하나_제한은_끌_수_있다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, "{\"singleTeamOnly\": false}", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertFalse(config.singleTeamOnly);
		// 값을 다시 저장해도 꺼진 상태가 유지되어야 한다.
		config.save(file);
		assertFalse(SharedFateConfig.loadOrCreate(file).singleTeamOnly);
	}

	/**
	 * 발전과제 달성 알림은 기본으로 끄지만 되돌릴 수 있어야 한다.
	 *
	 * <p>규칙을 실제로 끄는 것은 살아 있는 서버가 있어야 하는 {@code WorldGameRules} 라
	 * 단위 시험으로 닿지 않는다. 여기서는 <b>그 동작을 켜고 끄는 스위치</b>가 파일에 남고 다시
	 * 읽히는지만 본다.
	 */
	@Test
	void 발전과제_알림_끄기는_설정으로_되돌릴_수_있다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, "{\"silenceAdvancementMessages\": false}", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertFalse(config.silenceAdvancementMessages);
		config.save(file);
		assertFalse(SharedFateConfig.loadOrCreate(file).silenceAdvancementMessages);
	}

	@Test
	void 깨진_파일이면_기본값으로_되돌리고_복구한다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, "{ 이건 JSON이 아니다", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);
		SharedFateConfig recovered = SharedFateConfig.loadOrCreate(file);

		assertEquals(4, config.maxTeamSize);
		assertEquals(4, recovered.maxTeamSize);
		assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("maxTeamSize"));
	}

	@Test
	void 게임을_깨뜨리는_설정값은_기본값으로_복구한다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, """
				{
				  "maxTeamSize": 0,
				  "sharedMaxHealth": -1,
				  "damageAlertDurationTicks": -20,
				  "worldResetDelayTicks": 10,
				  "victoryCreditsDelayTicks": 0
				}
				""", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertEquals(4, config.maxTeamSize);
		assertEquals(20.0, config.sharedMaxHealth);
		assertEquals(30, config.damageAlertDurationTicks);
		assertEquals(100, config.worldResetDelayTicks);
		assertEquals(100, config.victoryCreditsDelayTicks);
	}

	/**
	 * 승리 연출의 세 단계는 각각 설정으로 조절할 수 있어야 한다.
	 *
	 * <p>이 프로젝트는 연출 길이를 자주 만진다. 값이 파일에 남고 다시 읽히는지만 본다.
	 */
	@Test
	void 승리_연출_단계는_설정으로_조절할_수_있다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, """
				{
				  "victoryTitleDelayTicks": 400,
				  "victorySubtitleDelayTicks": 40,
				  "victoryFireworkDelayTicks": 160
				}
				""", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertEquals(400, config.victoryTitleDelayTicks);
		assertEquals(40, config.victorySubtitleDelayTicks);
		assertEquals(160, config.victoryFireworkDelayTicks);
		config.save(file);
		assertEquals(40, SharedFateConfig.loadOrCreate(file).victorySubtitleDelayTicks);
	}

	@Test
	void 승리_연출_단계가_범위를_벗어나면_기본값으로_되돌린다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, """
				{
				  "victoryTitleDelayTicks": -1,
				  "victorySubtitleDelayTicks": 1201,
				  "victoryFireworkDelayTicks": 99999
				}
				""", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertEquals(200, config.victoryTitleDelayTicks);
		assertEquals(70, config.victorySubtitleDelayTicks);
		assertEquals(100, config.victoryFireworkDelayTicks);

		// 범위의 양끝 자체는 그대로 받는다.
		Files.writeString(file, """
				{
				  "victorySubtitleDelayTicks": 0,
				  "victoryTitleDelayTicks": 1200
				}
				""", StandardCharsets.UTF_8);
		SharedFateConfig edge = SharedFateConfig.loadOrCreate(file);
		assertEquals(0, edge.victorySubtitleDelayTicks);
		assertEquals(1200, edge.victoryTitleDelayTicks);
	}

	@Test
	void 네트워크_목록보다_큰_팀_정원은_16으로_제한한다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, "{\"maxTeamSize\": 100}", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertEquals(16, config.maxTeamSize);
	}

	@Test
	void 인벤토리_줄_수는_3줄이나_6줄만_허용한다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		Files.writeString(file, "{\"mainInventoryRows\": 5}", StandardCharsets.UTF_8);

		SharedFateConfig config = SharedFateConfig.loadOrCreate(file);

		assertEquals(6, config.mainInventoryRows);
	}

	@Test
	void 공유_최대_체력은_실제_속성_범위만_허용한다(@TempDir Path dir) throws Exception {
		Path file = dir.resolve("sharedfate.json");
		for (String invalid : new String[] {"0.5", "1025", "1e308"}) {
			Files.writeString(file, "{\"sharedMaxHealth\":" + invalid + "}", StandardCharsets.UTF_8);
			assertEquals(20.0, SharedFateConfig.loadOrCreate(file).sharedMaxHealth);
		}

		Files.writeString(file, "{\"sharedMaxHealth\":1024}", StandardCharsets.UTF_8);
		assertEquals(1024.0, SharedFateConfig.loadOrCreate(file).sharedMaxHealth);
	}
}
