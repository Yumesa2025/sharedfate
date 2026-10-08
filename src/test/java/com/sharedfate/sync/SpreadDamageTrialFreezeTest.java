package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.SpreadDamageEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 시련 화면({@link TrialFreeze})이 판을 얼리는 동안 「완충」의 남은 몫이 <b>줄지 않고 기다린다</b>는
 * 것을 못박는다(2026-10-06 검토에서 확정된 문제).
 *
 * <p>예전에는 {@link SpreadDamageManager#tick} 이 증강 선택·게임 오버 카운트다운에만 멈췄다. 몫은
 * 큐에서 먼저 떼어 낸 뒤 {@code hurtServer} 로 들어가는데, 그 HEAD 가 시련 화면 동안 피해를 버리므로
 * 룰렛(337틱)·카드 화면(300틱) 한 번이면 8초짜리 몫이 전부 사라졌다.
 *
 * <h2>어떻게 재는가</h2>
 * <p>큐는 {@code queueForTesting} 으로 <b>없는 팀</b>에 심는다. 그러면 첫 몫 차례(20틱)에
 * {@code stepTeam} 이 받을 사람을 못 찾아 큐를 통째로 버린다 — 진행이 멈추지 않았다면 남은 몫이
 * 0 이 되고, 멈췄다면 그대로다. 살아 있는 플레이어 없이 「진행했는가」만 가르는 가장 짧은 길이다.
 *
 * <p>시련 화면은 {@code TrialFreeze} 의 비공개 상태를 반사로 심어 연다. 여는 진짜 길
 * ({@code TrialFreeze.open})은 살아 있는 서버의 틱 관리자와 팀원을 요구한다. 생산 코드가 보는 것은
 * {@code isActive()} 하나다.
 */
class SpreadDamageTrialFreezeTest {

	private static final float EPSILON = 0.0001F;

	@TempDir
	Path 폴더;

	private MinecraftServer server;

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	void 서버를_세운다() throws Exception {
		SavedDataStorage storage = new SavedDataStorage(폴더.resolve("data"), null,
				TestBootstrap.registries());
		server = (MinecraftServer) unsafe().allocateInstance(DedicatedServer.class);
		setField(MinecraftServer.class, server, "savedDataStorage", storage);
	}

	@AfterEach
	void 정리() throws Exception {
		시련_화면(false);
		SpreadDamageManager.resetForTesting();
	}

	@Test
	void 시련_화면_동안에는_남은_몫이_줄지_않고_기다린다() throws Exception {
		UUID 팀 = UUID.randomUUID();
		SpreadDamageManager.queueForTesting(팀, 16.0F, 8);
		시련_화면(true);
		assertTrue(TrialFreeze.isActive(), "시련 화면을 열지 못했다");

		// 룰렛 한 번(337틱)보다 길게.
		for (int tick = 0; tick < 400; tick++) {
			SpreadDamageManager.tick(server);
		}

		assertEquals(16.0F, SpreadDamageManager.remaining(팀), EPSILON,
				"시련 화면 동안 몫이 진행됐다 — 그 몫은 HEAD 에서 버려져 사라진다");
		assertTrue(SpreadDamageManager.isSpreading(팀), "회복 금지도 그대로 남아야 한다");
	}

	/** 화면이 닫히면 다시 흐른다. 멈춘 동안 다음 차례까지 남은 틱도 그대로라 한 몫 간격 뒤에 돈다. */
	@Test
	void 시련_화면이_닫히면_다시_진행한다() throws Exception {
		UUID 팀 = UUID.randomUUID();
		SpreadDamageManager.queueForTesting(팀, 16.0F, 8);
		시련_화면(true);
		for (int tick = 0; tick < 100; tick++) {
			SpreadDamageManager.tick(server);
		}
		시련_화면(false);

		for (int tick = 0; tick < SpreadDamageEffect.SLICE_PERIOD_TICKS - 1; tick++) {
			SpreadDamageManager.tick(server);
		}
		assertEquals(16.0F, SpreadDamageManager.remaining(팀), EPSILON,
				"멈춘 동안 다음 차례까지 남은 틱이 흘렀다");

		SpreadDamageManager.tick(server);
		// 받을 사람이 없는 팀이라 차례가 오면 큐를 버린다 — 진행했다는 표시다.
		assertEquals(0.0F, SpreadDamageManager.remaining(팀), EPSILON, "화면이 닫혔는데 진행하지 않는다");
	}

	// ------------------------------------------------------------------ 도우미

	/** {@code TrialFreeze} 의 비공개 상태를 심거나 걷는다. 생산 코드가 보는 것은 {@code isActive()} 다. */
	private static void 시련_화면(boolean open) throws Exception {
		Field field = TrialFreeze.class.getDeclaredField("state");
		field.setAccessible(true);
		if (!open) {
			field.set(null, null);
			return;
		}
		Class<?> stateType = Class.forName(TrialFreeze.class.getName() + "$State");
		Constructor<?> constructor = stateType.getDeclaredConstructor(UUID.class, String.class,
				boolean.class, int.class);
		constructor.setAccessible(true);
		field.set(null, constructor.newInstance(UUID.randomUUID(), "시험", false, TrialFreeze.MAX_TICKS));
	}

	private static void setField(Class<?> owner, Object target, String name, Object value)
			throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (sun.misc.Unsafe) field.get(null);
	}
}
