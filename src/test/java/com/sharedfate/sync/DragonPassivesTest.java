package com.sharedfate.sync;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 패시브 자리의 <b>배선</b>만 본다.
 *
 * <p>{@link DragonPassives} 자체에는 계산이 없다. 하는 일은 패시브를 불러 주는 것과 상태를
 * 비워 주는 것 둘뿐이고, <b>망가지는 길도 그 둘뿐</b>이다 — 패시브를 하나 더 만들고 여기에
 * 안 붙이거나, 붙이고도 {@code clearState} 를 빠뜨리는 것. 둘 다 컴파일도 로그도 조용하고,
 * 뒤쪽은 「새 월드에서 아무도 그은 적 없는 자리가 탄다」라는 아주 엉뚱한 모양으로 나타난다.
 */
class DragonPassivesTest {

	/** 정적 상태라 시험끼리 샌다. 앞뒤로 비운다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonPassives.clearState();
	}

	@Test
	void clearState_가_패시브의_상태까지_비운다() {
		DragonFireBarrage.remember(DragonFireBarrage.plan(1L, 0.2));
		DragonFireBarrage.notePlanned(1L);
		assertNotNull(DragonFireBarrage.active());

		DragonPassives.clearState();

		assertNull(DragonFireBarrage.active(),
				"DragonPassives.clearState 가 패시브를 안 부르면 지난 판의 좌표가 다음 월드로 샌다");
	}

	/**
	 * 패시브 실행기가 이 파일에 실제로 <b>묶여</b> 있는가.
	 *
	 * <p>{@link #clearState_가_패시브의_상태까지_비운다} 는 지우는 쪽만 본다. 부르는 쪽은 월드가
	 * 있어야 해서 여기서 돌려 볼 수 없으므로, 클래스 파일에 이름이 들어 있는지로 확인한다.
	 * 패시브를 하나 더 만들면서 {@link DragonPassives#tick} 에 줄을 안 넣으면 <b>아무 일도 하지
	 * 않는 패시브</b>가 되는데, 그때 조용히 지나가지 않게 하는 것이 이 시험이다.
	 */
	@Test
	void 패시브_실행기가_배선에_들어_있다() throws IOException {
		String bytes;
		try (InputStream in = DragonPassives.class
				.getResourceAsStream("/com/sharedfate/sync/DragonPassives.class")) {
			if (in == null) {
				throw new IOException("DragonPassives 의 클래스 파일을 찾지 못했다");
			}
			bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
		assertTrue(bytes.contains("com/sharedfate/sync/DragonFireBarrage"),
				"「연쇄 포격」이 배선에서 빠졌다 — 언제나 있어야 할 판이 한 번도 안 돈다");
	}
}
