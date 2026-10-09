package com.sharedfate.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code KeyMappingUnknownKeyMixin} 이 기대는 <b>바닐라 쪽 사실</b>을 붙들어 둔다.
 *
 * <p>클라이언트 믹스인 설정에도 refmap 이 없어 대상 이름이 틀려도 빌드가 그냥 통과한다. 그래서
 * 대상 메서드의 모양과, 막으려는 바닐라 동작 자체를 여기서 확인한다. 믹스인 클래스는 시험에서
 * 타입으로 쓰지 않는다 — 설정에 등록된 믹스인을 직접 불러오면 그 자리에서 터진다.
 *
 * <p>{@code fabric-loader-junit} 이라 시험도 믹스인이 붙은 {@code KeyMapping} 을 본다. 그래서
 * 아래 시험들은 <b>고친 뒤의 동작</b>을 확인하고, 그것이 믹스인 덕분이라는 근거는
 * {@link #핸들러가_KeyMapping_에_합쳐져_있다} 가 댄다.
 */
class KeyMappingUnknownKeyTargetTest {
	/** 할당된 키 하나. 값 자체는 뜻이 없고 UNKNOWN(0) 이 아니기만 하면 된다. */
	private static final int BOUND_KEYCODE = 'a';

	/** 둘 다 정적이라 하위 클래스가 재정의할 수 없다 — 믹스인이 조용히 안 붙는 함정이 없다. */
	@Test
	void click_과_set_은_정적_메서드다() throws Exception {
		Method click = KeyMapping.class.getDeclaredMethod("click", InputConstants.Key.class);
		Method set = KeyMapping.class.getDeclaredMethod("set", InputConstants.Key.class, boolean.class);
		assertTrue(Modifier.isStatic(click.getModifiers()));
		assertTrue(Modifier.isStatic(set.getModifiers()));
		assertEquals(void.class, click.getReturnType());
		assertEquals(void.class, set.getReturnType());
	}

	/** SDL 이 대응 키코드가 없는 키(한/영·한자)에 내는 값이 0 이고, UNKNOWN 이 바로 그 키다. */
	@Test
	void UNKNOWN_은_키보드_키코드_0_이다() {
		assertEquals(0, InputConstants.UNKNOWN.getValue());
		assertEquals(InputConstants.UNKNOWN, InputConstants.Type.KEYBOARD.getOrCreate(0));
	}

	/**
	 * 할당되지 않은 매핑은 UNKNOWN 아래 묶인다. 바닐라는 UNKNOWN 을 한 번 누르면 <b>그 전부를</b>
	 * 한 번씩 누르는데(기본 설정의 시네마틱 카메라가 바로 그런 매핑이다), 믹스인이 붙은 뒤에는
	 * 아무것도 눌리지 않아야 한다.
	 */
	@Test
	void UNKNOWN_은_미할당_매핑을_누르지_못한다() {
		KeyMapping first = new KeyMapping("key.sharedfate.test.unbound_a",
				InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC);
		KeyMapping second = new KeyMapping("key.sharedfate.test.unbound_b",
				InputConstants.UNKNOWN.getValue(), KeyMapping.Category.MISC);
		assertTrue(first.isUnbound());
		assertTrue(second.isUnbound());

		KeyMapping.click(InputConstants.UNKNOWN);
		KeyMapping.set(InputConstants.UNKNOWN, true);

		assertFalse(first.consumeClick(), "한/영 키 한 번에 미할당 매핑이 눌렸다 — 시네마틱 카메라가 켜진다");
		assertFalse(second.consumeClick(), "한/영 키 한 번에 미할당 매핑이 눌렸다 — 시네마틱 카메라가 켜진다");
		assertFalse(first.isDown());
		assertFalse(second.isDown());
	}

	/** 막는 것은 UNKNOWN 하나뿐이다. 할당된 키는 그대로 눌리고 그대로 놓인다. */
	@Test
	void 할당된_키는_그대로_눌린다() {
		InputConstants.Key key = InputConstants.Type.KEYBOARD.getOrCreate(BOUND_KEYCODE);
		KeyMapping bound = new KeyMapping("key.sharedfate.test.bound",
				BOUND_KEYCODE, KeyMapping.Category.MISC);
		assertFalse(bound.isUnbound());

		KeyMapping.click(key);
		KeyMapping.set(key, true);
		assertTrue(bound.consumeClick());
		assertTrue(bound.isDown());

		KeyMapping.set(key, false);
		assertFalse(bound.isDown());
	}

	/**
	 * 위 두 시험이 「믹스인이 붙어서」 통과한다는 근거. 시험도 Knot 클래스로더를 지나므로
	 * 핸들러가 {@code KeyMapping} 안으로 합쳐져 있어야 한다. 이름은 믹스인이 앞뒤에 덧붙이므로
	 * 끝부분만 본다.
	 */
	@Test
	void 핸들러가_KeyMapping_에_합쳐져_있다() {
		boolean click = false;
		boolean set = false;
		for (Method method : KeyMapping.class.getDeclaredMethods()) {
			click |= method.getName().endsWith("sharedfate$ignoreUnknownClick");
			set |= method.getName().endsWith("sharedfate$ignoreUnknownSet");
		}
		assertTrue(click, "click 쪽 핸들러가 KeyMapping 에 없다 — 믹스인이 안 붙었다");
		assertTrue(set, "set 쪽 핸들러가 KeyMapping 에 없다 — 믹스인이 안 붙었다");
	}

	/** 설정에 이름이 없으면 파일만 있고 아무 일도 하지 않는다. 컴파일도 빌드도 조용하다. */
	@Test
	void 믹스인이_클라이언트_설정에_등록돼_있다() throws Exception {
		try (InputStream in = KeyMappingUnknownKeyTargetTest.class
				.getResourceAsStream("/sharedfate.client.mixins.json")) {
			assertNotNull(in, "sharedfate.client.mixins.json 이 클래스패스에 없다");
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(json.contains("\"KeyMappingUnknownKeyMixin\""),
					"KeyMappingUnknownKeyMixin 이 클라이언트 믹스인 설정에 없다 — 한/영 키가 다시 "
							+ "시네마틱 카메라를 켠다");
		}
	}
}
