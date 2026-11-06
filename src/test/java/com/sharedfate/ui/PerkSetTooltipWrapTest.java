package com.sharedfate.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 세트 툴팁의 단계 설명 접기.
 *
 * <p>바닐라 툴팁은 줄을 접지 않는다. 「무기 3단계」 설명이 한 줄 500픽셀을 넘어 GUI 배율이 큰
 * 화면에서 툴팁이 화면 밖으로 잘렸다(0.45.0-dev 실서버). 이 시험은 <b>어떤 설명도 정해 둔 폭을
 * 넘지 않을 것</b>, <b>접어도 글자가 하나도 사라지지 않을 것</b>, <b>지금 있는 설명은 두 줄 안에
 * 들 것</b>을 지킨다.
 */
class PerkSetTooltipWrapTest {
	private static final PerkSetTooltipLines.Palette PALETTE = new PerkSetTooltipLines.Palette(
			0x111111, 0x222222, 0x333333, 0x444444, 0x555555, 0x666666, 0x777777, 0x888888);

	@Test
	void 짧은_줄은_그대로다() {
		assertEquals(List.of("2:  공격 속도가 35% 빨라집니다."),
				PerkSetTooltipLines.wrap("2:  공격 속도가 35% 빨라집니다.", FakeFont::width, 240));
	}

	@Test
	void 자가_없으면_접지_않는다() {
		String text = "3:  아주 긴 설명이 여기에 들어갑니다 아주 긴 설명이 여기에 들어갑니다";
		assertEquals(List.of(text), PerkSetTooltipLines.wrap(text, null, 50));
		assertEquals(List.of(text), PerkSetTooltipLines.wrap(text, FakeFont::width, 0));
	}

	@Test
	void 긴_줄은_폭_안으로_접고_글자를_잃지_않는다() {
		String text = "4:  광물을 캘 때 증강 「광부의 직감」처럼 근처의 광물이 함께 캐지고, "
				+ "채굴 속도가 크게 빨라집니다.";
		List<String> lines = PerkSetTooltipLines.wrap(text, FakeFont::width, 120);

		assertTrue(lines.size() > 1, "120 폭에서는 접혀야 한다");
		for (String line : lines) {
			assertTrue(FakeFont.width(line) <= 120, "폭을 넘는 줄: " + line);
		}
		assertEquals(text.replace(" ", ""), String.join("", lines).replace(" ", ""));
	}

	@Test
	void 둘째_줄부터는_단계_번호만큼_들여_쓴다() {
		List<String> lines = PerkSetTooltipLines.wrap(
				"3:  공격 속도가 30% 더 빨라지고, 지금과 앞으로 얻는 증강의 공격력 감소가 모두 사라집니다.",
				FakeFont::width, 240);

		assertEquals(2, lines.size());
		assertTrue(lines.get(0).startsWith("3:  공격"));
		// "3:  " 는 6 + 6 + 4 + 4 = 20 픽셀이다(FakeFont 는 콜론도 6 으로 잰다). 공백 다섯 칸.
		assertTrue(lines.get(1).startsWith("     ") && !lines.get(1).startsWith("      "),
				"들여 쓴 폭이 번호 폭과 다르다: [" + lines.get(1) + "]");
	}

	@Test
	void 띄어쓰기_없는_긴_덩어리는_글자_단위로_끊는다() {
		String text = "가나다라마바사아자차카타파하가나다라마바사아자차카타파하";
		List<String> lines = PerkSetTooltipLines.wrap(text, FakeFont::width, 60);

		assertTrue(lines.size() > 1);
		for (String line : lines) {
			assertTrue(FakeFont.width(line) <= 60, "폭을 넘는 줄: " + line);
		}
		assertEquals(text, String.join("", lines));
	}

	@Test
	void 접힌_단계_줄은_원래_줄의_색을_그대로_쓴다() {
		PerkSetTooltip.Body body = new PerkSetTooltip.Body("무기 3/3", List.of(
				new PerkSetTooltip.TierEntry("weapon", 2, "공격 속도가 35% 빨라집니다.", true),
				new PerkSetTooltip.TierEntry("weapon", 3,
						"공격 속도가 30% 더 빨라지고, 지금과 앞으로 얻는 증강의 공격력 감소가 모두 사라집니다.",
						false)),
				new PerkSetTooltip.Trimmed(List.of(), 0));

		List<PerkSetTooltipLines.Row> rows =
				PerkSetTooltipLines.build(body, "무기", 6, PALETTE, FakeFont::width, 240);

		// 진행도 · 2단계 · 3단계(두 줄) · 빈 줄 · 머리글.
		assertEquals(6, rows.size());
		assertEquals(0x222222, rows.get(1).color());
		assertEquals(0x333333, rows.get(2).color());
		assertEquals(0x333333, rows.get(3).color());
		assertTrue(rows.get(3).text().startsWith(" "));
	}

	@Test
	void 화면이_좁으면_더_좁게_접되_하한_아래로는_안_간다() {
		assertEquals(PerkSetTooltipLines.TIER_WRAP_WIDTH, PerkSetTooltipLines.wrapWidth(640));
		assertEquals(224, PerkSetTooltipLines.wrapWidth(480));
		assertEquals(144, PerkSetTooltipLines.wrapWidth(320));
		assertEquals(PerkSetTooltipLines.MIN_WRAP_WIDTH, PerkSetTooltipLines.wrapWidth(100));
	}

	/**
	 * 묶음 정의의 모든 단계 설명이 기본 폭에서 <b>두 줄 안</b>에 든다.
	 *
	 * <p>세 줄이 되는 설명을 새로 적으면 여기서 걸린다. 툴팁이 못 그리는 것은 아니지만, 세트
	 * 툴팁은 단계가 넷까지 한 상자에 쌓이므로 한 단계가 세 줄이면 상자가 눈에 띄게 길어진다.
	 */
	@Test
	void 묶음_세트_설명은_모두_두_줄_안에_든다() throws Exception {
		List<String> descriptions = new ArrayList<>();
		try (InputStream in = getClass().getResourceAsStream("/sharedfate-sets-default.json")) {
			assertNotNull(in, "세트 정의 파일이 시험 경로에 없다");
			JsonObject root = JsonParser.parseReader(
					new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			for (JsonElement set : root.getAsJsonArray("sets")) {
				for (JsonElement tier : set.getAsJsonObject().getAsJsonArray("tiers")) {
					JsonObject object = tier.getAsJsonObject();
					descriptions.add(object.get("count").getAsInt() + PerkSetTooltipLines.TIER_SEPARATOR
							+ object.get("description").getAsString());
				}
			}
		}
		assertTrue(descriptions.size() > 10, "단계를 거의 못 읽었다");
		for (String line : descriptions) {
			List<String> wrapped = PerkSetTooltipLines.wrap(line, FakeFont::width,
					PerkSetTooltipLines.TIER_WRAP_WIDTH);
			assertTrue(wrapped.size() <= 2, "세 줄 이상으로 접힌다: " + line + " → " + wrapped);
			for (String piece : wrapped) {
				assertTrue(FakeFont.width(piece) <= PerkSetTooltipLines.TIER_WRAP_WIDTH,
						"폭을 넘는 줄: " + piece);
			}
		}
	}
}
