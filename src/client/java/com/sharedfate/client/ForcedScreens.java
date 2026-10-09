package com.sharedfate.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * 서버가 사람 손을 거치지 않고 띄우는 창(증강 뽑기·선택, 시련 룰렛, 엔드 입장 수락)을 연다.
 *
 * <h2>왜 따로 있는가 — 덮어 열면 메뉴가 서버에 남는다</h2>
 * <p>{@code Minecraft.setScreenAndShow} → {@code Gui.setScreen} 은 앞 창의 {@code removed()} 만
 * 부른다. 작업대·화로·상자·주민 거래 창({@link AbstractContainerScreen})의 {@code removed()} 는
 * 클라이언트 쪽 메뉴 정리({@code menu.removed(player)})뿐이고, <b>서버에 닫기 패킷을 보내는 것은
 * {@code onClose()} 다</b> — ESC 를 누르면 {@code onClose()} → {@code LocalPlayer.closeContainer()}
 * 가 {@code ServerboundContainerClosePacket} 을 보낸다(26.3 바이트코드로 확인).
 *
 * <p>그래서 메뉴를 쓰던 중에 이 창들이 덮어 열리면 <b>서버의 {@code containerMenu} 가 그대로
 * 남고</b> 클라이언트의 {@code containerMenu} 도 그 메뉴를 가리킨 채 남는다. 창이 닫힌 뒤에도
 * 그 메뉴가 유효한 거리 안에 있는 한 풀리지 않는다.
 *
 * <ul>
 *   <li><b>주민</b> — 거래 중이던 주민은 서버에서 {@code isTrading()} 이 참으로 남아
 *       {@code Villager.mobInteract} 가 거래를 열지 않는다. 눌러도 아무 일이 없다</li>
 *   <li><b>인벤토리</b> — E 로 연 인벤토리의 클릭은 메뉴 번호 0 으로 가는데 서버는 남은 메뉴의
 *       번호와 다르다며 버린다({@code ServerGamePacketListenerImpl.handleContainerClick}).
 *       옮긴 아이템이 제자리로 튄다</li>
 *   <li><b>손에 든 아이템·조합 칸</b> — 서버가 메뉴를 닫지 않았으므로 커서에 들고 있던 것과
 *       조합 칸에 둔 것이 인벤토리로 돌아오지 않는다</li>
 * </ul>
 *
 * <h2>어떻게 닫는가</h2>
 * <p>바닐라 ESC 와 같은 길이다 — 앞 창의 {@code onClose()} 를 부른 뒤 새 창을 연다.
 * {@code onClose()} 가 닫기 패킷 · 클라이언트 메뉴 되돌리기 · 창 비우기를 한 번에 한다. 플레이어
 * 인벤토리 창(메뉴 번호 0)도 바닐라는 닫기 패킷을 보낸다 — 서버가 그때 2×2 조합 칸과 커서의
 * 아이템을 인벤토리로 돌려준다. 그래서 여기서도 가리지 않는다.
 *
 * <p>사망 화면을 밀어내지 않는 판단은 부르는 쪽이 이미 한다. 여기는 메뉴를 닫는 일만 더한다.
 */
public final class ForcedScreens {

	private ForcedScreens() {
	}

	/** 열린 메뉴를 바닐라처럼 닫고 {@code next} 를 연다. */
	public static void show(Minecraft client, Screen next) {
		Screen current = client.gui.screen();
		if (closesMenu(current)) {
			current.onClose();
		}
		client.setScreenAndShow(next);
	}

	/**
	 * 이 창을 밀어내기 전에 {@code onClose()} 로 닫아야 하는가.
	 *
	 * <p>메뉴를 가진 창이면 참이다. 메뉴가 없는 창(채팅, 우리 창들)은 서버에 남길 것이 없고,
	 * 그런 창의 {@code onClose()} 는 부모 창으로 돌아가는 등 다른 일을 할 수 있어 부르지 않는다.
	 */
	static boolean closesMenu(Screen current) {
		return current instanceof AbstractContainerScreen<?>;
	}
}
