package com.sharedfate.client.hud;

import com.sharedfate.client.perk.ClientPerkFeatures;
import com.sharedfate.perk.PerkFoodCapacity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.player.Player;

/**
 * 「먹방」을 가진 팀원의 허기 막대 오른쪽에 「57/100」 을 그린다.
 *
 * <p>막대는 바닐라 그대로 20 칸 기준이다 — 허기가 20 을 넘으면 꽉 찬 막대로 보이고, 그 위에 쌓인
 * 양은 이 숫자로만 보인다. 증강이 없으면(상한 20) 아무것도 그리지 않는다.
 *
 * <h2>자리</h2>
 * <p>바닐라 {@code Hud.extractFood(graphics, player, top, right)} 가 넘겨받는 {@code right} 가 막대의
 * 오른쪽 끝(화면 가운데 + 91), {@code top} 이 막대의 윗줄이다. 아이콘은 9×9 이고 오른쪽 끝에서 왼쪽으로
 * 8 칸씩 놓인다. 숫자는 그 오른쪽 끝에서 {@link #GAP} 만큼 띄워 같은 줄에 놓는다. 이 줄의 오른쪽은
 * 바닐라가 비워 둔 자리다 — 공기 방울은 막대 <b>위</b> 줄, 왼손 칸과 공격 표시는 핫바 줄이다.
 *
 * <p>탈것의 체력이 허기 막대 자리를 차지할 때는 바닐라가 {@code extractFood} 를 아예 부르지 않으므로
 * 숫자도 함께 사라진다. 「장님 거인」이 허기 표시를 가릴 때도 같은 이유로 함께 가려진다.
 *
 * <p>그리기 스레드에서만 불린다.
 */
public final class FoodCapacityHud {
	/** 막대 오른쪽 끝과 숫자 사이의 틈. */
	private static final int GAP = 2;
	/** 아이콘(9)과 글자(8)의 높이 차를 반으로 나눈 만큼 내려 가운데를 맞춘다. */
	private static final int TEXT_OFFSET_Y = 1;
	/** 허기 아이콘(닭다리) 색에 맞춘 옅은 주황. */
	private static final int COLOR = 0xFFF0C080;

	private FoodCapacityHud() {
	}

	/**
	 * 허기 막대를 다 그린 직후에 불린다.
	 *
	 * @param top   막대의 윗줄
	 * @param right 막대의 오른쪽 끝
	 */
	public static void draw(GuiGraphicsExtractor graphics, Player player, int top, int right) {
		int capacity = ClientPerkFeatures.foodCapacity();
		if (!PerkFoodCapacity.eatsWhenFull(capacity)) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		if (font == null) {
			return;
		}
		graphics.text(font, label(player.getFoodData().getFoodLevel(), capacity),
				right + GAP, top + TEXT_OFFSET_Y, COLOR);
	}

	/** 「현재/상한」. 현재가 상한을 넘어 보이지 않게 자른다(상한이 막 내려간 틱). */
	static String label(int food, int capacity) {
		return Math.max(0, Math.min(food, capacity)) + "/" + capacity;
	}
}
