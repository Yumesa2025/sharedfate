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
		assertTrue(bytes.contains("com/sharedfate/sync/DragonPerch"),
				"「착지」가 배선에서 빠졌다 — 믹스인이 읽는 깃발이 한 번도 안 세워지고, "
						+ "드래곤은 5초만 앉아 있고 활도 그대로 박힌다");
	}

	/**
	 * 「착지」는 <b>이른 반환보다 먼저</b> 불린다.
	 *
	 * <p>그 패시브가 세우는 것은 믹스인이 읽는 깃발이다. 사람이 아무도 없거나 드래곤이 사라진
	 * 틱에 깃발이 켜진 채 멈추면 <b>아무도 그것을 내리지 않는다</b> — 원거리 면역이 켜진 채 남으면
	 * 「최후의 저항」에서 활이 한 대도 안 들어간다.
	 *
	 * <p>순서를 바이트코드로 볼 수는 없으므로, <b>그쪽이 null 을 스스로 받는다</b>는 사실을 대신
	 * 붙든다 — 받지 못하게 되면 이른 반환 뒤로 옮길 수밖에 없고, 그때 이 시험이 터진다.
	 */
	@Test
	void 착지_패시브는_월드가_없어도_불릴_수_있다() throws NoSuchMethodException {
		java.lang.reflect.Method tick = DragonPerch.class.getDeclaredMethod("tick",
				net.minecraft.server.level.ServerLevel.class,
				net.minecraft.world.entity.boss.enderdragon.EnderDragon.class,
				java.util.List.class, long.class, long.class);
		assertNotNull(tick);
		// null 을 받아도 터지지 않고 깃발을 내린다. 월드 없이 확인할 수 있는 유일한 갈래다.
		DragonPerch.tick(null, null, null, 0L, 0L);
		assertTrue(!DragonPerch.holdsTakeoff() && !DragonPerch.rangedImmune(),
				"드래곤이 없는 틱에 깃발이 켜진 채 남는다 — 그러면 아무도 내리지 않는다");
	}
}
