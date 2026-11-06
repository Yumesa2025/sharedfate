package com.sharedfate.enchant;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.ArrayList;
import java.util.List;

/**
 * 인챈트 칸 툴팁에서 <b>경험치 레벨</b> 이야기를 <b>다이아몬드</b> 이야기로 바꾼다.
 *
 * <p>레벨을 더 이상 쓰지 않으므로 바닐라 툴팁의 두 줄이 거짓말이 된다.
 * 바닐라가 만들어 둔 줄 목록을 받아 <b>번역 열쇠를 보고</b>
 * 해당하는 줄만 갈아 끼운다. 인챈트 힌트는 그대로 둔다.
 *
 * <p>청금석 줄도 고친다. 바닐라는 「칸 번호 + 1」개라고 적지만 이 모드는 칸과 상관없이
 * {@value EnchantmentTableRules#LAPIS_COST}개를 걷는다. 색(모자라면 빨강)은 바닐라가 정한 그대로
 * 남긴다 — 화면 Mixin 이 바닐라가 비교하는 청금석 개수를 이미 3개 기준으로 바꿔 두었다.
 *
 * <p>적는 개수는 {@link EnchantmentDiamondCost#forSlot(int)} 에서 가져온다. 그 값은 서버가
 * 메뉴의 데이터 칸으로 내려보낸 것이므로, {@code enchant_cost} 증강으로 개수가 달라진 팀은
 * 툴팁도 함께 달라진다.
 */
public final class EnchantmentDiamondTooltip {
	/** 「필요 레벨: %s」 — 레벨이 모자랄 때 빨갛게 뜨는 줄. */
	private static final String LEVEL_REQUIREMENT = "container.enchant.level.requirement";
	/** 「인챈트 레벨 1」 — 소모하는 레벨을 알리는 줄. */
	private static final String LEVEL_ONE = "container.enchant.level.one";
	/** 「인챈트 레벨 %s」 — 위와 같되 둘 이상일 때. 인자가 칸 번호 + 1 이다. */
	private static final String LEVEL_MANY = "container.enchant.level.many";
	/** 「청금석 1개」. */
	private static final String LAPIS_ONE = "container.enchant.lapis.one";
	/** 「청금석 %s개」. 인자가 칸 번호 + 1 이다. */
	private static final String LAPIS_MANY = "container.enchant.lapis.many";

	private EnchantmentDiamondTooltip() {
	}

	public static List<Component> rewrite(List<Component> lines) {
		List<Component> rewritten = new ArrayList<>(lines.size());
		for (Component line : lines) {
			rewritten.add(rewriteLine(line));
		}
		return rewritten;
	}

	public static Component rewriteLine(Component line) {
		if (!(line.getContents() instanceof TranslatableContents contents)) {
			return line;
		}
		return switch (contents.getKey()) {
			// 바닐라가 이 줄에 넣는 숫자는 costs[칸] 인데, 화면 Mixin 이 그 배열을 이미
			// 다이아몬드 개수로 바꿔 두었으므로 그대로 읽으면 된다.
			case LEVEL_REQUIREMENT -> Component
					.literal("다이아몬드 " + firstNumber(contents, EnchantmentDiamondCost.forSlot(0))
							+ "개가 필요합니다")
					.withStyle(ChatFormatting.RED);
			case LEVEL_ONE -> costLine(0);
			// 바닐라 인자는 「칸 번호 + 1」 이다. 칸마다 값이 다를 때를 위해 되돌린다.
			case LEVEL_MANY -> costLine(firstNumber(contents, 1) - 1);
			case LAPIS_ONE, LAPIS_MANY -> Component
					.translatable(LAPIS_MANY, EnchantmentTableRules.LAPIS_COST)
					.withStyle(line.getStyle());
			default -> line;
		};
	}

	private static Component costLine(int slot) {
		int clamped = Math.clamp(slot, 0, EnchantmentDiamondCost.SLOT_COUNT - 1);
		return Component.literal("다이아몬드 " + EnchantmentDiamondCost.forSlot(clamped) + "개")
				.withStyle(ChatFormatting.GRAY);
	}

	private static int firstNumber(TranslatableContents contents, int fallback) {
		Object[] args = contents.getArgs();
		if (args.length > 0 && args[0] instanceof Number number) {
			return number.intValue();
		}
		return fallback;
	}
}
