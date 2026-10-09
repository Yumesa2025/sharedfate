package com.sharedfate.client;

import com.sharedfate.TestBootstrap;
import com.sharedfate.client.perk.PerkDrawScreen;
import com.sharedfate.client.perk.PerkOfferScreen;
import com.sharedfate.client.trial.TrialEntranceScreen;
import com.sharedfate.client.trial.TrialRouletteScreen;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 서버가 띄우는 창이 열린 메뉴를 닫고 들어가는가.
 *
 * <h2>왜 이 시험이 있는가</h2>
 * <p>작업대·주민 거래 같은 메뉴를 쓰는 중에 증강 선택창이 덮어 열리면, 바닐라
 * {@code Gui.setScreen} 은 앞 창의 {@code removed()} 만 불러 <b>서버에 닫기 패킷이 가지
 * 않았다.</b> 창이 닫힌 뒤에도 서버에는 그 메뉴가 남아 거래하던 주민이 다시 거래를 열지 않고,
 * 인벤토리 클릭은 서버가 메뉴 번호가 다르다며 버렸다.
 *
 * <p>고친 방법은 바닐라 ESC 와 같은 {@code onClose()} 를 먼저 부르는 것이다. 여기서는
 * 「어떤 창을 닫는가」와 그 판단이 기대는 바닐라 사실 둘을 붙든다.
 */
class ForcedScreensTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@Test
	void 메뉴를_가진_창은_닫고_들어간다() throws Exception {
		assertTrue(ForcedScreens.closesMenu(allocate(CraftingScreen.class)), "작업대");
		assertTrue(ForcedScreens.closesMenu(allocate(MerchantScreen.class)), "주민 거래");
		// 플레이어 인벤토리도 바닐라 ESC 는 닫기 패킷을 보낸다. 서버가 그때 2×2 조합 칸과
		// 커서에 든 아이템을 인벤토리로 돌려준다.
		assertTrue(ForcedScreens.closesMenu(allocate(InventoryScreen.class)), "인벤토리");
	}

	@Test
	void 메뉴가_없는_창은_건드리지_않는다() throws Exception {
		assertFalse(ForcedScreens.closesMenu(null), "게임 화면");
		assertFalse(ForcedScreens.closesMenu(allocate(ChatScreen.class)), "채팅");
		// 뽑기 연출 → 선택창으로 넘어갈 때 앞 창의 onClose 를 부르면 창이 한 번 비었다가
		// 다시 뜬다. 우리 창들은 메뉴가 없으므로 그대로 밀어낸다.
		assertFalse(ForcedScreens.closesMenu(allocate(PerkDrawScreen.class)));
		assertFalse(ForcedScreens.closesMenu(allocate(PerkOfferScreen.class)));
		assertFalse(ForcedScreens.closesMenu(allocate(TrialRouletteScreen.class)));
		assertFalse(ForcedScreens.closesMenu(allocate(TrialEntranceScreen.class)));
	}

	/**
	 * 이 고침이 기대는 바닐라 사실 — 닫기 패킷은 {@code onClose()} 쪽에만 있다.
	 *
	 * <p>{@code AbstractContainerScreen.onClose} 는 {@code LocalPlayer.closeContainer} 를 부르고,
	 * 창을 바꾸는 {@code Gui} 는 그것을 부르지 않는다. 앞의 것이 사라지면 {@code onClose()} 를
	 * 불러도 메뉴가 안 닫히고, 뒤의 것이 생기면(바닐라가 창을 바꿀 때 스스로 닫게 되면) 이 고침은
	 * 닫기 패킷을 두 번 보내게 된다. 어느 쪽이든 판이 바뀐 날 여기서 걸린다.
	 */
	@Test
	void 닫기_패킷은_onClose_에만_있다() {
		assertTrue(vanillaClassBytes(AbstractContainerScreen.class).contains("closeContainer"),
				"AbstractContainerScreen 이 더는 closeContainer 를 부르지 않는다");
		assertFalse(vanillaClassBytes(Gui.class).contains("closeContainer"),
				"Gui 가 창을 바꿀 때 스스로 메뉴를 닫는다 — ForcedScreens 가 두 번 닫지 않는지 보라");
	}

	/** 생성자를 거치지 않은 빈 창. 판정이 보는 것은 종류뿐이다. */
	@SuppressWarnings("unchecked")
	private static <T> T allocate(Class<T> type) throws Exception {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
	}

	/** 바닐라 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String vanillaClassBytes(Class<?> type) {
		String path = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = ForcedScreensTest.class.getResourceAsStream(path)) {
			assertNotNull(in, path);
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException error) {
			throw new IllegalStateException(error);
		}
	}
}
