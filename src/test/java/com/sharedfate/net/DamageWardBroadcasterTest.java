package com.sharedfate.net;

import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.DamageWardTracker;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetRegistry;
import com.sharedfate.team.TeamState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「호위」 준비 표에 무엇을 보낼지.
 *
 * <p>표는 <b>실제로 막을 수 있을 때만</b> 떠야 한다. 쿨타임 중에 떠 있으면 「아직 막아 준다」로 읽혀
 * 몹 앞에서 버티다 맞는다. 그래서 피해를 실제로 막는 판정({@code DamageWardEffect.wardFor} ·
 * {@link DamageWardTracker})과 같은 것을 쓰는지를 본다.
 */
class DamageWardBroadcasterTest {
	private static final String BODYGUARD = "sharedfate:bodyguard";
	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	void 준비() {
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();
		DamageWardTracker.reset();
	}

	@AfterEach
	void 정리() {
		PerkRegistry.clear();
		PerkSetRegistry.clear();
		DamageWardTracker.reset();
	}

	private static TeamState withBodyguard() {
		TeamState state = TeamState.fresh(20);
		state.perksEnabled = true;
		state.ownedPerks.add(BODYGUARD);
		state.perkOwners.put(BODYGUARD, OWNER);
		return state;
	}

	@Test
	void 고른_사람에게는_준비되면_뜬다() {
		DamageWardPayload status = DamageWardBroadcaster.statusOf(withBodyguard(), OWNER, 100L, true);

		assertTrue(status.ready());
		assertEquals("호위", status.name());
		assertEquals("gold", status.rarity());
	}

	@Test
	void 고르지_않은_팀원과_증강이_없는_사람에게는_안_뜬다() {
		assertEquals(DamageWardPayload.HIDDEN,
				DamageWardBroadcaster.statusOf(withBodyguard(), OTHER, 100L, true));
		TeamState none = TeamState.fresh(20);
		none.perksEnabled = true;
		assertEquals(DamageWardPayload.HIDDEN,
				DamageWardBroadcaster.statusOf(none, OWNER, 100L, true));
		assertEquals(DamageWardPayload.HIDDEN,
				DamageWardBroadcaster.statusOf(null, OWNER, 100L, true));
	}

	@Test
	void 증강을_끈_팀이나_죽어_있는_동안에는_안_뜬다() {
		TeamState state = withBodyguard();
		assertEquals(DamageWardPayload.HIDDEN,
				DamageWardBroadcaster.statusOf(state, OWNER, 100L, false));
		state.perksEnabled = false;
		assertEquals(DamageWardPayload.HIDDEN,
				DamageWardBroadcaster.statusOf(state, OWNER, 100L, true));
	}

	/** 쿨타임은 정의 파일(10초)에서 온다. 막은 뒤 10초 동안 감추고, 차면 다시 띄운다. */
	@Test
	void 막은_뒤_쿨타임_동안_감추고_차면_다시_뜬다() {
		TeamState state = withBodyguard();
		assertTrue(DamageWardTracker.tryConsume(OWNER, 1_000L, 200));

		assertFalse(DamageWardBroadcaster.statusOf(state, OWNER, 1_000L, true).ready());
		assertFalse(DamageWardBroadcaster.statusOf(state, OWNER, 1_199L, true).ready());
		assertTrue(DamageWardBroadcaster.statusOf(state, OWNER, 1_200L, true).ready());
	}

	@Test
	void 묶음은_그대로_오간다() {
		DamageWardPayload sent = DamageWardPayload.ready("호위", "gold");
		RegistryFriendlyByteBuf buffer =
				new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
		DamageWardPayload.CODEC.encode(buffer, sent);

		assertEquals(sent, DamageWardPayload.CODEC.decode(buffer));
		assertEquals(0, buffer.readableBytes());
	}

	@Test
	void 너무_긴_이름은_잘라_인코딩이_터지지_않는다() {
		DamageWardPayload long_ = DamageWardPayload.ready("가".repeat(200), null);

		assertEquals(DamageWardPayload.MAX_NAME_LENGTH, long_.name().length());
		assertEquals("", long_.rarity());
	}
}
