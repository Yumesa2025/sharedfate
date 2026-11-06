package com.sharedfate.ui;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * 세트 툴팁 한 덩어리를 <b>화면이 그대로 그릴 줄들</b>로 바꾸는 계산.
 *
 * <p>{@link PerkSetTooltip} 이 「무엇을 담을지」를 정한다면 여기는 「어떤 글자와 어떤 색으로
 * 놓을지」를 정한다. 증강 선택 카드의 <b>유형 줄</b>과 그 화면 왼쪽 <b>세트 판</b>이 같은 것을
 * 띄워야 한다. 두 곳에서 따로 만들면 한쪽만 고쳐진다.
 *
 * <h2>켜진 단계와 안 켜진 단계는 반드시 갈려야 한다</h2>
 * <p>이 툴팁의 본론은 「지금 무엇이 켜져 있고 하나 더 모으면 무엇이 켜지는가」다. 네 줄이
 * 같은 색으로 늘어서면 그 물음에 아무 답도 못 한다. 그래서 켜진 줄은 밝게, 아직인 줄은
 * 흐리게 준다.
 */
public final class PerkSetTooltipLines {
	/** 단계 번호와 설명 사이. {@code "2:  광물에 …"}. */
	public static final String TIER_SEPARATOR = ":  ";
	/** 「아직 없는 것」 이름 앞에 붙는 점. */
	public static final String MISSING_BULLET = "· ";

	/**
	 * 단계 설명을 접는 폭(픽셀).
	 *
	 * <p>바닐라 툴팁은 줄을 접지 않는다. 「무기 3단계」처럼 긴 설명은 한 줄이 500픽셀을 넘어
	 * GUI 배율이 큰 화면에서 툴팁이 화면 밖으로 잘렸다. 240 이면 한글 스물여덟 자 남짓이라
	 * 지금 있는 가장 긴 설명(채굴 4단계)도 두 줄에 들어간다. 화면이 좁으면 {@link #wrapWidth} 가
	 * 이보다 더 좁게 접어(배율 4 의 1080p, 가로 480 이면 224) 마우스 한쪽에 툴팁이 통째로 서게 한다 —
	 * 그때는 긴 설명이 세 줄이 될 수 있다.
	 */
	public static final int TIER_WRAP_WIDTH = 240;
	/** 화면이 아주 좁을 때도 이보다는 좁게 접지 않는다. 한 줄에 몇 자 안 들어가면 읽을 수 없다. */
	public static final int MIN_WRAP_WIDTH = 120;

	private PerkSetTooltipLines() {
	}

	/**
	 * 그릴 줄 하나.
	 *
	 * @param text  적을 글자. 빈 문자열이면 줄 사이를 벌리는 빈 줄이다
	 * @param color 0xAARRGGBB 색. 화면이 알파를 떼어 쓴다
	 */
	public record Row(String text, int color) {

		/** 줄 사이를 벌리려고 넣은 빈 줄인가. */
		public boolean blank() {
			return text.isEmpty();
		}
	}

	/**
	 * 줄마다 쓸 색.
	 *
	 * @param progress      「채굴 2/3」 진행도 줄
	 * @param tierActive    이미 켜진 단계
	 * @param tierIdle      아직 안 켜진 단계
	 * @param missingHeader 「채굴 — 아직 없는 것」 머리글
	 * @param silver        실버 증강 이름
	 * @param gold          골드 증강 이름
	 * @param prism         프리즘 증강 이름
	 * @param overflow      「… 외 N개」
	 */
	public record Palette(int progress, int tierActive, int tierIdle, int missingHeader,
			int silver, int gold, int prism, int overflow) {

		/**
		 * 등급 문자열에 맞는 글자색. 모르는 등급은 실버로 본다.
		 *
		 * <p>서버가 새 등급을 만들어 보내도 툴팁이 검은 글자로 사라지면 안 된다.
		 */
		public int rarityColor(String rarity) {
			if (rarity == null) {
				return silver;
			}
			return switch (rarity) {
				case "gold" -> gold;
				case "prism" -> prism;
				default -> silver;
			};
		}
	}

	/** 단계 한 줄의 글자. {@code "3:  다이아몬드 광석을 …"}. */
	public static String tierLine(PerkSetTooltip.TierEntry tier) {
		if (tier == null) {
			return "";
		}
		String description = tier.description() == null ? "" : tier.description();
		return tier.count() + TIER_SEPARATOR + description;
	}

	/**
	 * 툴팁을 띄울 화면 폭에 맞춘 접는 폭.
	 *
	 * <p>툴팁은 마우스 오른쪽에 서고, 자리가 모자라면 왼쪽으로 넘어간다. 화면 절반보다 좁으면
	 * 어느 쪽에 서든 통째로 들어간다.
	 *
	 * @param screenWidth 화면의 GUI 폭
	 */
	public static int wrapWidth(int screenWidth) {
		return Math.min(TIER_WRAP_WIDTH, Math.max(MIN_WRAP_WIDTH, screenWidth / 2 - 16));
	}

	/**
	 * 한 줄을 {@code maxWidth} 안에 들어가게 접는다.
	 *
	 * <p>띄어쓰기에서 끊고, 띄어쓰기 없이 한 덩어리가 한 줄보다 길면 글자 단위로 끊는다. 단계
	 * 번호({@code "3:  "})로 시작하는 줄이면 둘째 줄부터 그 폭만큼 들여 써서 번호 아래가 비어
	 * 보이게 한다 — 그래야 다음 단계 번호와 섞여 읽히지 않는다.
	 *
	 * @param width 글자 폭을 재는 자. {@code null} 이면 접지 않는다
	 * @return 접힌 줄들. 접을 것이 없으면 원래 줄 하나
	 */
	public static List<String> wrap(String text, @Nullable ToIntFunction<String> width,
			int maxWidth) {
		String line = text == null ? "" : text;
		if (width == null || maxWidth <= 0 || width.applyAsInt(line) <= maxWidth) {
			return List.of(line);
		}
		String indent = hangingIndent(line, width);
		// 들여 쓴 자리만으로 줄이 꽉 차면 들여 쓰지 않는다. 그렇지 않으면 한 줄에 한 자도 못 들어간다.
		if (width.applyAsInt(indent) * 2 >= maxWidth) {
			indent = "";
		}

		List<String> lines = new ArrayList<>();
		// 지금 쌓는 줄. 둘째 줄부터는 들여 쓴 공백으로 시작한다.
		StringBuilder current = new StringBuilder();
		// 지금 줄에 낱말이 하나라도 들어갔는가. 들여 쓴 공백만 있는 줄은 빈 줄이다.
		boolean hasWord = false;
		for (String word : line.split(" ", -1)) {
			if (word.isEmpty() && !hasWord) {
				// 줄머리에 걸린 겹 띄어쓰기는 버린다. 접힌 줄 앞이 한 칸 더 밀려 보인다.
				continue;
			}
			String candidate = hasWord ? current + " " + word : current + word;
			if (width.applyAsInt(candidate) <= maxWidth) {
				current.setLength(0);
				current.append(candidate);
				hasWord = true;
				continue;
			}
			if (hasWord) {
				lines.add(current.toString().stripTrailing());
				current.setLength(0);
				current.append(indent);
				hasWord = false;
				if (width.applyAsInt(indent + word) <= maxWidth) {
					current.append(word);
					hasWord = true;
					continue;
				}
			}
			// 한 낱말이 한 줄보다 길다 — 글자 단위로 끊는다.
			for (int index = 0; index < word.length(); index++) {
				char letter = word.charAt(index);
				if (hasWord && width.applyAsInt(current.toString() + letter) > maxWidth) {
					lines.add(current.toString().stripTrailing());
					current.setLength(0);
					current.append(indent);
				}
				current.append(letter);
				hasWord = true;
			}
		}
		if (hasWord) {
			lines.add(current.toString().stripTrailing());
		}
		return lines.isEmpty() ? List.of(line) : List.copyOf(lines);
	}

	/**
	 * 둘째 줄부터 앞에 붙일 공백. 단계 번호({@code "3:  "})의 폭만큼이고, 번호가 없으면 빈 문자열.
	 */
	private static String hangingIndent(String line, ToIntFunction<String> width) {
		int separator = line.indexOf(TIER_SEPARATOR);
		if (separator <= 0) {
			return "";
		}
		for (int index = 0; index < separator; index++) {
			if (!Character.isDigit(line.charAt(index))) {
				return "";
			}
		}
		int prefixWidth = width.applyAsInt(line.substring(0, separator + TIER_SEPARATOR.length()));
		StringBuilder indent = new StringBuilder();
		while (width.applyAsInt(indent.toString()) < prefixWidth) {
			indent.append(' ');
			// 공백 폭이 0 인 자를 받으면 끝나지 않는다. 그런 자는 들여 쓰지 않는다.
			if (indent.length() > 32) {
				return "";
			}
		}
		return indent.toString();
	}

	/**
	 * 툴팁 줄들을 만든다.
	 *
	 * <p>담기는 차례는 <b>진행도 → 단계 → 빈 줄 → 아직 없는 것</b>이다. 지금 상태를 먼저 말하고
	 * 그 다음에 「무엇을 더 집으면 되는가」를 말한다.
	 *
	 * <p>진행도도 단계도 없으면 <b>빈 목록</b>을 돌려준다. 서버가 모르는 유형 id 를 보냈거나
	 * 세트 패킷이 아직 안 왔을 때인데, 그때 「— 전부 모았습니다」만 뜨면 새빨간 거짓말이 된다.
	 *
	 * @param body        {@code ClientPerkSets.tooltip(typeId)} 가 준 덩어리
	 * @param displayName 유형의 한국어 이름. 「아직 없는 것」 머리글에 쓴다
	 * @param missingLimit 「아직 없는 것」을 몇 줄까지 적을지. {@code body} 가 이미 잘려 있어도
	 *                     여기서 <b>더</b> 줄일 수 있다 — 유형이 둘인 증강은 툴팁이 두 배가 된다
	 */
	public static List<Row> build(PerkSetTooltip.Body body, String displayName, int missingLimit,
			Palette palette) {
		return build(body, displayName, missingLimit, palette, null, 0);
	}

	/**
	 * 툴팁 줄들을 만들되, 단계 설명이 {@code maxWidth} 를 넘으면 여러 줄로 접는다.
	 *
	 * <p>접힌 줄은 단계 번호 뒤 글머리에 맞춰 들여 쓴다({@link #wrap}). 색은 원래 줄과 같다 —
	 * 켜진 단계가 두 줄이면 두 줄 다 밝아야 「어디까지가 한 단계인가」가 읽힌다.
	 *
	 * @param width    글자 폭을 재는 자. 화면이 {@code font::width} 를 넘긴다. {@code null} 이면 접지 않는다
	 * @param maxWidth 한 줄의 최대 폭(픽셀). 0 이하면 접지 않는다
	 */
	public static List<Row> build(PerkSetTooltip.Body body, String displayName, int missingLimit,
			Palette palette, @Nullable ToIntFunction<String> width, int maxWidth) {
		if (body == null || palette == null) {
			return List.of();
		}
		String progress = body.progress() == null ? "" : body.progress();
		List<PerkSetTooltip.TierEntry> tiers = body.tiers();
		if (progress.isEmpty() && tiers.isEmpty()) {
			return List.of();
		}

		List<Row> rows = new ArrayList<>();
		if (!progress.isEmpty()) {
			rows.add(new Row(progress, palette.progress()));
		}
		for (PerkSetTooltip.TierEntry tier : tiers) {
			if (tier == null) {
				continue;
			}
			int color = tier.active() ? palette.tierActive() : palette.tierIdle();
			for (String piece : wrap(tierLine(tier), width, maxWidth)) {
				rows.add(new Row(piece, color));
			}
		}

		// 이미 한 번 잘린 것을 또 자른다. 자른 개수는 더해야 「외 N개」가 진실이 된다.
		PerkSetTooltip.Trimmed given = body.missing();
		PerkSetTooltip.Trimmed shown = PerkSetTooltip.trim(given.shown(), missingLimit);
		int hidden = shown.hidden() + given.hidden();
		int total = given.shown().size() + given.hidden();

		if (!rows.isEmpty()) {
			// 단계 설명과 이름 목록은 성격이 다르다. 빈 줄 하나가 그 경계를 말한다.
			rows.add(new Row("", palette.overflow()));
		}
		rows.add(new Row(PerkSetTooltip.header(displayName, total), palette.missingHeader()));
		for (PerkSetTooltip.Missing missing : shown.shown()) {
			rows.add(new Row(MISSING_BULLET + missing.perkName(),
					palette.rarityColor(missing.rarity())));
		}
		if (hidden > 0) {
			rows.add(new Row(PerkSetTooltip.overflowLine(hidden), palette.overflow()));
		}
		return List.copyOf(rows);
	}
}
