package com.sharedfate.enchant;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 인챈트 탁자의 <b>등급·책장·청금석</b> 규칙이다.
 *
 * <p>바닐라는 책장 수와 난수로 칸마다 다른 요구 레벨(1~30)을 뽑고, 그 값이 곧 마법부여의
 * 강도였다. 이 모드는 그 갈래를 하나로 접는다.
 *
 * <ul>
 *   <li>책장이 {@value #REQUIRED_SHELVES}개 이상이면 <b>세 칸 모두 등급 {@value #GRADE}</b>이다.
 *       결과는 바닐라가 30레벨로 뽑을 때와 똑같은 난수·품질이다 — 칸마다 바닐라 씨앗
 *       ({@code enchantmentSeed + 칸})을 그대로 쓰므로 세 칸은 서로 따로 뽑힌다.</li>
 *   <li>책장이 그보다 적으면 <b>세 칸 모두 비운다.</b> 인챈트할 수 없다.</li>
 *   <li>{@value #REQUIRED_SHELVES}개를 넘는 책장은 아무것도 더 주지 않는다.</li>
 *   <li>청금석은 칸과 상관없이 언제나 {@value #LAPIS_COST}개를 요구하고 {@value #LAPIS_COST}개를 걷는다.</li>
 * </ul>
 *
 * <p>책장을 세는 방법(가운데가 막히면 세지 않는 규칙 포함)은 바닐라 그대로다. 다이아몬드 값은
 * {@link EnchantmentDiamondCost} 가 따로 정한다.
 */
public final class EnchantmentTableRules {
	/** 인챈트에 필요한 책장 수. 이보다 적으면 세 칸 모두 빈다. */
	public static final int REQUIRED_SHELVES = 6;

	/** 세 칸 모두의 마법부여 등급. 바닐라 인챈트 탁자의 최고 등급이다. */
	public static final int GRADE = 30;

	/** 칸마다 요구하고 걷는 청금석 개수. */
	public static final int LAPIS_COST = 3;

	private EnchantmentTableRules() {
	}

	/** 책장 수에 따른 칸의 등급. 책장이 모자라면 0(빈 칸)이다. */
	public static int grade(int shelves) {
		return shelves >= REQUIRED_SHELVES ? GRADE : 0;
	}

	/** 화면에 적을 책장 수. 필요한 수를 넘는 책장은 아무것도 더 주지 않으므로 거기서 자른다. */
	public static int shownShelves(int shelves) {
		return Math.clamp(shelves, 0, REQUIRED_SHELVES);
	}

	/** 책장이 모자란지. 화면이 빨간 안내 문구를 띄울지 정한다. */
	public static boolean lacksShelves(int shelves) {
		return shelves < REQUIRED_SHELVES;
	}

	/** 책장 안내 문구. 모자라면 「책장이 부족합니다 (N/6)」, 넉넉하면 「책장 6/6」. */
	public static String shelfLabel(int shelves) {
		int shown = shownShelves(shelves);
		return lacksShelves(shelves)
				? "책장이 부족합니다 (" + shown + "/" + REQUIRED_SHELVES + ")"
				: "책장 " + shown + "/" + REQUIRED_SHELVES;
	}

	/** 청금석이 넉넉한지. 크리에이티브는 바닐라처럼 청금석 없이도 된다. */
	public static boolean hasLapis(Player player, ItemStack lapis) {
		if (player != null && player.hasInfiniteMaterials()) {
			return true;
		}
		return lapis != null && !lapis.isEmpty() && lapis.getCount() >= LAPIS_COST;
	}

	/**
	 * 화면이 단추를 살릴지 정할 때 쓸 청금석 개수.
	 *
	 * <p>바닐라 화면은 「청금석 ≥ 칸 번호 + 1」로 단추를 살린다. 모자랄 때 0 으로 바꾸면 세 칸이
	 * 모두 죽고, 넉넉하면 그대로 두어 세 칸이 모두 산다. 툴팁의 청금석 줄 색도 같은 비교를 쓰므로
	 * 함께 맞는다.
	 */
	public static int effectiveLapis(int lapis) {
		return lapis >= LAPIS_COST ? lapis : 0;
	}
}
