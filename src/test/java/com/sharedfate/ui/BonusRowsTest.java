package com.sharedfate.ui;

import com.sharedfate.TestBootstrap;
import com.sharedfate.net.StatBonuses;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 인벤토리 옆 패널의 보정치 줄 — 무엇을 어떤 글자·색으로 적고, 무엇을 숨기며, 좁은 화면에서
 * 어떻게 물러나는가.
 */
class BonusRowsTest {
	private static final int IMAGE_WIDTH = 176;
	private static final int IMAGE_HEIGHT = 220;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	/** 자릿수가 가장 많이 나오는, 증강을 여럿 모은 팀. */
	private static StatBonuses busy() {
		return new StatBonuses(12.0F, 0.35F, 2.0F, 0.2F, 3.0F, 0.15F, 0.06F);
	}

	private static List<String> lines(List<PanelRow> rows) {
		return rows.stream().map(PanelRow::fullLine).toList();
	}

	// ------------------------------------------------------------------ 글자

	@Test
	void 비율은_부호를_붙인_정수_퍼센트로_적는다() {
		assertEquals("+35%", BonusRow.percentText(0.35));
		assertEquals("-10%", BonusRow.percentText(-0.1));
		assertEquals("0%", BonusRow.percentText(0.004));
		assertEquals("+3%", BonusRow.percentText(0.03));
		assertEquals("0%", BonusRow.percentText(Double.NaN));
	}

	@Test
	void 고정값은_부호를_붙여_소수_한_자리까지_적는다() {
		assertEquals("+2", BonusRow.flatText(2.0));
		assertEquals("-1.5", BonusRow.flatText(-1.5));
		assertEquals("0", BonusRow.flatText(0.0));
		assertEquals("0", BonusRow.flatText(-0.01));
	}

	/** 사람이 든 보기 그대로다 — 「추가 공격력 +35%」. */
	@Test
	void 모든_보정이_걸린_팀의_줄() {
		List<PanelRow> rows = BonusRows.playerRows(busy(), 0.9, false);

		assertEquals(List.of(
				"최대 체력  +12  (하트 6개)",
				"추가 공격력  +35%",
				"고정 공격력  +2",
				"공격 속도  +20%",
				"방어력  +3",
				"이동 속도  +15%",
				"받는 피해  -10%",
				"경험치  +6%"), lines(rows));
	}

	@Test
	void 줄인_모양과_접은_모양() {
		BonusRow attack = BonusRow.percent("추가 공격력", "공격", 0.35, StatRow.Sense.HIGHER_IS_BETTER);

		assertEquals("공격  +35%", attack.shortLine());
		assertEquals("공격 +35%", attack.tightLine());
		assertEquals("+35%", attack.values());
	}

	// ------------------------------------------------------------------ 숨기기

	/** 0 인 항목은 숨긴다. 걸린 것만 남아야 눈에 들어온다. */
	@Test
	void 값이_0_인_줄은_숨긴다() {
		StatBonuses onlyAttack = new StatBonuses(0, 0.35F, 0, 0, 0, 0, 0);

		assertEquals(List.of("추가 공격력  +35%"),
				lines(BonusRows.playerRows(onlyAttack, 1.0, false)));
	}

	/** 반올림해서 0% 가 되는 부스러기도 0 이다. 「0%」 줄이 서면 안 된다. */
	@Test
	void 반올림해_0_이_되는_값도_숨긴다() {
		StatBonuses dust = new StatBonuses(0.01F, 0.004F, 0.01F, -0.004F, 0, 0.001F, 0);

		assertEquals(List.of(BonusRows.NOTHING), lines(BonusRows.playerRows(dust, 1.004, false)));
	}

	/** 묶음이 통째로 비면 흐린 안내 한 줄. 패널이 아예 사라지면 고장으로 읽힌다. */
	@Test
	void 아무_보정도_없으면_안내_한_줄을_흐리게_남긴다() {
		List<PanelRow> rows = BonusRows.playerRows(StatBonuses.NONE, 1.0, false);

		assertEquals(1, rows.size());
		assertEquals(BonusRows.NOTHING, rows.getFirst().tightLine());
		assertEquals(StatRow.COLOR_MASKED, rows.getFirst().color());
	}

	@Test
	void 서버가_말해_주기_전에는_아무_줄도_없다() {
		assertTrue(BonusRows.groups(null, 1.0, 1.0, 1.0, false).isEmpty());
	}

	/** 몹 배율이 그대로면 몹 묶음은 빠진다. */
	@Test
	void 몹_배율이_그대로면_몹_묶음이_없다() {
		assertEquals(1, BonusRows.groups(StatBonuses.NONE, 1.0, 1.0, 1.0, false).size());
		List<List<PanelRow>> groups = BonusRows.groups(StatBonuses.NONE, 1.0, 1.15, 1.0, false);
		assertEquals(2, groups.size());
		assertEquals(List.of("몹 체력  +15%"), lines(groups.get(1)));
	}

	/** 방어구 칸을 가리는 증강이 있으면 0 이어도 물음표 줄을 남긴다. 지우면 「없다」로 읽힌다. */
	@Test
	void 가린_방어력은_물음표로_남긴다() {
		List<String> masked = lines(BonusRows.playerRows(StatBonuses.NONE, 1.0, true));

		assertEquals(List.of("방어력  " + StatRow.MASK), masked);
	}

	// ------------------------------------------------------------------ 색

	@Test
	void 좋아진_값은_초록_나빠진_값은_빨강() {
		assertEquals(StatRow.COLOR_GOOD,
				BonusRow.percent("이동 속도", "속도", 0.15, StatRow.Sense.HIGHER_IS_BETTER).color());
		assertEquals(StatRow.COLOR_BAD,
				BonusRow.percent("이동 속도", "속도", -0.1, StatRow.Sense.HIGHER_IS_BETTER).color());
	}

	/** 받는 피해·몹 배율은 오르면 나쁘다. 색이 뒤집혀야 한다. */
	@Test
	void 오르면_나쁜_값은_색이_뒤집힌다() {
		List<PanelRow> rows = BonusRows.playerRows(StatBonuses.NONE, 1.2, false);
		assertEquals(StatRow.COLOR_BAD, rows.getFirst().color(), "받는 피해 +20%");
		assertEquals(StatRow.COLOR_GOOD,
				BonusRows.playerRows(StatBonuses.NONE, 0.8, false).getFirst().color(),
				"받는 피해 -20%");
		assertEquals(StatRow.COLOR_BAD, BonusRows.mobRows(1.15, 1.0).getFirst().color());
	}

	// ------------------------------------------------------------------ 자리

	private static int available(int screenWidth) {
		return InventoryTeamButton.available(screenWidth, IMAGE_WIDTH,
				(screenWidth - IMAGE_WIDTH) / 2);
	}

	private static int availableWithBook(int screenWidth) {
		return InventoryTeamButton.available(screenWidth, IMAGE_WIDTH,
				177 + (screenWidth - IMAGE_WIDTH - 200) / 2);
	}

	private static int height(int screenHeight) {
		return InventoryTeamButton.statHeight(screenHeight, (screenHeight - IMAGE_HEIGHT) / 2);
	}

	private static InventoryStatPanel.Layout layout(int width, int height) {
		return InventoryStatPanel.layout(BonusRows.groups(busy(), 0.9, 1.15, 1.15, false), width,
				height, FakeFont::width);
	}

	/**
	 * 1920×1080 배율 4(480×270) — 남는 자리 148px. 보정치 줄은 최종값 줄보다 짧아 온전한
	 * 이름이 그대로 들어간다(가장 긴 줄 「최대 체력  +12  (하트 6개)」).
	 */
	@Test
	void 배율_4_에서도_온전한_이름으로_보인다() {
		InventoryStatPanel.Layout layout = layout(available(480), height(270));

		assertEquals(InventoryStatPanel.Style.FULL, layout.style());
		assertTrue(layout.width() <= available(480));
	}

	/** 조합법 책을 펼친 좁은 화면에서도 자리를 넘지 않는다. 넘으면 책을 덮는다. */
	@Test
	void 어떤_화면에서도_자리를_넘지_않는다() {
		for (int screenWidth : new int[] {1920, 960, 640, 480, 427, 384, 320}) {
			for (int screenHeight : new int[] {1080, 540, 360, 270, 240}) {
				for (int width : new int[] {available(screenWidth), availableWithBook(screenWidth)}) {
					InventoryStatPanel.Layout layout = layout(width, height(screenHeight));
					assertTrue(layout.width() <= Math.max(0, width),
							screenWidth + "×" + screenHeight + " 가로 " + width);
					assertTrue(layout.height() <= Math.max(0, height(screenHeight)),
							screenWidth + "×" + screenHeight + " 세로");
				}
			}
		}
	}

	/** 안내 줄은 접지 않는다 — 접으면 아랫줄이 비어 자리만 먹는다. */
	@Test
	void 안내_줄은_접지_않는다() {
		assertTrue(BonusRow.percent("경험치", "경험", 0.03, StatRow.Sense.HIGHER_IS_BETTER).wraps());
		assertEquals(false, BonusRow.note(BonusRows.NOTHING).wraps());
	}
}
