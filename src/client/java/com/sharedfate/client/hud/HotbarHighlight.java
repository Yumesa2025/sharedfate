package com.sharedfate.client.hud;

import com.sharedfate.client.ClientHotbarLock;
import com.sharedfate.client.ClientTeamState;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Inventory;

/**
 * 핫바 칸 위에 그리는 표시 둘.
 *
 * <ul>
 *   <li><b>팀원이 쓰고 있는 칸</b> — 붉은 테두리. 팀이 있으면 늘 그린다</li>
 *   <li><b>시련 「굳는 손」이 굳혀 둔 칸</b> — 붉은 덮개. 그 카드를 가진 팀에게만 온다</li>
 * </ul>
 *
 * <h2>왜 테두리와 덮개로 갈랐는가</h2>
 *
 * <p>둘 다 붉다. 「굳었다」도 「팀원이 쓰는 중」도 사람이 배운 색이 빨강이라 색을 갈라 버리면
 * 둘 중 하나가 새 색을 배우는 일이 된다. 대신 <b>모양</b>을 가른다 — 테두리는 칸을 가리키고,
 * 덮개는 칸을 <b>지운다.</b> 굳은 칸은 실제로 쓸 수 없는 칸이므로 바닐라 쿨타임 덮개와 같은
 * 말을 하는 셈이고, 아래 아이템이 비쳐 무엇이 잠겼는지도 그대로 읽힌다.
 *
 * <p>한 칸이 둘 다일 수 있다. 덮개를 나중에 그려 위에 올린다 — 못 쓰는 칸이라는 쪽이 더 급한
 * 정보다.
 */
public class HotbarHighlight implements HudElement {
	private static final int RED = 0xFFFF3030;
	/** 굳은 칸을 덮는 붉은 막. 반투명이라 아래 아이템이 비친다. */
	private static final int FROZEN_VEIL = 0x66FF1A1A;
	/** 굳은 칸의 테두리. 덮개만으로는 경계가 흐려 어느 칸인지 셀 수 없다. */
	private static final int FROZEN_EDGE = 0xFFFF6060;

	/** 핫바 한 칸의 폭(픽셀). 바닐라 핫바 무늬 그대로다. */
	private static final int SLOT_WIDTH = 20;
	/** 핫바의 높이(픽셀). */
	private static final int SLOT_HEIGHT = 22;
	/** 화면 가운데에서 핫바 왼쪽 끝까지(픽셀). 바닐라가 쓰는 값과 같다. */
	private static final int HOTBAR_HALF_WIDTH = 91;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		int center = graphics.guiWidth() / 2;
		int y = graphics.guiHeight() - SLOT_HEIGHT;

		if (ClientTeamState.inTeam()) {
			for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
				if (ClientTeamState.isAllyUsingHotbarSlot(slot)) {
					graphics.outline(slotLeft(center, slot), y, SLOT_WIDTH, SLOT_HEIGHT, RED);
				}
			}
		}

		// 굳은 칸은 팀에 속하지 않아도 뜰 수 있는 값이 아니지만(시련은 팀 전투에서만 돈다),
		// 「팀이 있는가」를 다시 묻지 않는다. 물으면 팀 동기화 패킷과 시련 패킷의 도착 순서에
		// 따라 표시가 한 틱 빠지는 길이 생긴다 — 보낸 쪽이 보냈으면 그리는 것이 맞다.
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}
		long gameTime = level.getGameTime();
		for (int slot = 0; slot < Inventory.SELECTION_SIZE; slot++) {
			if (!ClientHotbarLock.isFrozen(slot, gameTime)) {
				continue;
			}
			int left = slotLeft(center, slot);
			graphics.fill(left, y, left + SLOT_WIDTH, y + SLOT_HEIGHT, FROZEN_VEIL);
			graphics.outline(left, y, SLOT_WIDTH, SLOT_HEIGHT, FROZEN_EDGE);
		}
	}

	/**
	 * 이 칸의 왼쪽 끝 x.
	 *
	 * <p>{@code +1} 은 바닐라 핫바 무늬의 왼쪽 테두리 한 픽셀이다. 빼면 표시가 칸보다 한 픽셀
	 * 왼쪽으로 밀려 옆 칸을 물고 들어간다.
	 */
	private static int slotLeft(int center, int slot) {
		return center - HOTBAR_HALF_WIDTH + slot * SLOT_WIDTH + 1;
	}
}
