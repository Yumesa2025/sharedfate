package com.sharedfate.client.perk;

import com.sharedfate.net.PerkClientFeaturesPayload;
import com.sharedfate.perk.PerkFoodCapacity;
import com.sharedfate.perk.effect.HideHudEffect;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

/**
 * 클라이언트가 보관하는 "지금 켜져 있는 클라 기능".
 *
 * <p>{@link PerkClientFeaturesPayload} 로 갱신되며, 월드에서 나가면 {@link #clear()} 로
 * 비운다. {@link PerkClientState} 는 화면에 글자로 뿌릴 표시용 문자열이고, 이쪽은
 * <b>동작을 가르는 판단값</b>이다.
 *
 * <h2>서버가 말해 주기 전에는 아무것도 켜지지 않는다</h2>
 * <p>기본값은 전부 꺼짐이다. 서버가 이 모드를 안 쓰거나 증강을 안 쓰는 팀이면 패킷이 아예
 * 오지 않고, 그러면 이 캐시는 계속 비어 있어 바닐라와 똑같이 움직인다.
 *
 * <p>값을 읽는 자리가 렌더 스레드({@code HudElement.extractRenderState})이고 쓰는 자리가
 * 패킷 수신인데, 수신 쪽을 {@code client.execute(...)} 로 클라이언트 본 스레드에 올려
 * 두었으므로 둘은 같은 스레드다.
 */
public final class ClientPerkFeatures {
	private static boolean doubleJump;
	private static double doubleJumpPower;
	private static int foodCapacity = PerkFoodCapacity.VANILLA_MAX_FOOD;
	private static final Set<HideHudEffect.Element> HIDDEN =
			EnumSet.noneOf(HideHudEffect.Element.class);

	private ClientPerkFeatures() {
	}

	/** {@link PerkClientFeaturesPayload} 를 받았을 때 부른다. */
	public static void update(@Nullable PerkClientFeaturesPayload payload) {
		if (payload == null) {
			clear();
			return;
		}
		doubleJump = payload.doubleJump();
		doubleJumpPower = payload.doubleJumpPower();
		HIDDEN.clear();
		HIDDEN.addAll(payload.hidden());
		foodCapacity = payload.foodCapacity();
		if (!doubleJump) {
			// 공중 점프가 꺼지면 남아 있던 사용 표시도 뜻이 없어진다.
			DoubleJumpHandler.reset();
		}
	}

	/** 공중에서 한 번 더 뛸 수 있는가. */
	public static boolean doubleJumpEnabled() {
		return doubleJump;
	}

	/**
	 * 공중 점프의 세기.
	 *
	 * <p>{@link DoubleJumpHandler} 가 누른 그 틱에 이 값으로 몸을 띄운다. 값을 정하는 것은
	 * 여전히 서버다 — 클라이언트는 받은 값을 그대로 쓸 뿐이고, 서버도 요청을 받아들일 때
	 * 증강 정의에서 같은 값을 다시 읽는다.
	 */
	public static double doubleJumpPower() {
		return doubleJumpPower;
	}

	/**
	 * 허기 상한. 「먹방」을 가진 팀이면 100, 아니면 20.
	 *
	 * <p>{@link PerkFoodCapacity#setClientCapacity} 로 공통 코드에 건네져 {@code Player.canEat} 이
	 * 클라이언트에서도 「배가 불러도 먹을 수 있다」고 답하게 하고, 허기 막대 옆 숫자도 이 값을 본다.
	 */
	public static int foodCapacity() {
		return foodCapacity;
	}

	/** 이 HUD 칸을 가려야 하는가. */
	public static boolean isHidden(@Nullable HideHudEffect.Element element) {
		return element != null && HIDDEN.contains(element);
	}

	/** 월드에서 나갈 때 부른다. */
	public static void clear() {
		doubleJump = false;
		doubleJumpPower = 0.0;
		HIDDEN.clear();
		foodCapacity = PerkFoodCapacity.VANILLA_MAX_FOOD;
	}
}
