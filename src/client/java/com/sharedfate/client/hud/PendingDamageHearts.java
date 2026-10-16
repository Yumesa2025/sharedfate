package com.sharedfate.client.hud;

import com.sharedfate.client.ClientStatSnapshot;
import com.sharedfate.ui.PendingHeartSpan;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;

import java.util.Arrays;

/**
 * 「완충」이 곧 깎을 체력을 <b>바닐라 체력바의 하트 위에</b> 겹쳐 그린다.
 *
 * <p>나눠 받는 동안 곧 비게 될 반 칸들이 어둡게 숨 쉬듯 깜빡인다. 몫이 하나 들어갈 때마다 그 칸이
 * 실제로 비고 덮인 칸이 줄어든다. 어느 칸을 덮을지는 {@link PendingHeartSpan} 이 정하고, 값은
 * 서버가 보내 준 {@link ClientStatSnapshot#spreadPending()} 이다.
 *
 * <h2>하트 자리는 바닐라가 그린 자리를 받아 쓴다</h2>
 * <p>바닐라 체력바는 체력이 낮으면 하트마다 무작위로 한 칸씩 흔들리고, 재생 중이면 한 칸이
 * 튀어 오른다. 그 자리를 따로 계산하면 흔들리는 하트 위에서 덮개가 어긋난다. 그래서
 * {@code HudPendingDamageMixin} 이 {@code Hud.extractHearts} 가 하트 틀을 그리는 순간의 자리를
 * {@link #note} 로 적어 두고, 다 그린 뒤 {@link #draw} 가 그 자리에 덮는다.
 *
 * <h2>모양</h2>
 * <p>바닐라 하트 그림({@code hud/heart/full}·{@code half})을 그대로 쓰고 색만 어둡게 곱한다. 새
 * 그림을 들이지 않으므로 리소스팩이 하트를 바꿔도 모양이 같이 따라간다. 오른쪽 반만 덮을 때는
 * 온 하트를 오른쪽 반만 남기고 잘라 그린다 — 바닐라 반쪽 그림이 왼쪽 1~4열을 쓰므로 5열부터다.
 *
 * <p>그리기 스레드에서만 불린다.
 */
public final class PendingDamageHearts {
	private static final Identifier FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HALF = Identifier.withDefaultNamespace("hud/heart/half");
	private static final int HEART_SIZE = 9;
	/** 오른쪽 반이 시작하는 열. 바닐라 반쪽 그림이 1~4열을 쓴다. */
	private static final int RIGHT_HALF_START = 5;

	/** 덮개 색(알파 제외). 하트 그림에 곱해지므로 빨간 하트는 검붉게, 흡수 하트는 어두운 금빛이 된다. */
	private static final int TINT_RGB = 0x200000;
	/** 깜빡임의 가장 옅은 때와 짙은 때의 알파. 몫이 1초마다 들어오므로 한 번 숨 쉬는 데도 1초다. */
	private static final float ALPHA_LOW = 0.45F;
	private static final float ALPHA_HIGH = 0.8F;
	private static final long PULSE_MILLIS = 1000L;

	/** 이번 프레임에 바닐라가 그린 하트들의 자리. 번호가 칸이고 값이 좌표다. */
	private static int[] heartX = new int[32];
	private static int[] heartY = new int[32];
	private static int noted;
	private static boolean active;

	private PendingDamageHearts() {
	}

	/** 하트를 그리기 시작한다. 덮을 것이 없으면 이번 프레임은 아무것도 적지 않는다. */
	public static void begin() {
		active = ClientStatSnapshot.spreadPending() > 0.0F;
		noted = 0;
	}

	/** 바닐라가 하트 {@code heart} 의 틀을 {@code (x, y)} 에 그렸다. 흔들림과 튀어 오름이 반영된 자리다. */
	public static void note(int heart, int x, int y) {
		if (!active || heart < 0) {
			return;
		}
		if (heart >= heartX.length) {
			int size = Math.max(heart + 1, heartX.length * 2);
			heartX = Arrays.copyOf(heartX, size);
			heartY = Arrays.copyOf(heartY, size);
		}
		heartX[heart] = x;
		heartY[heart] = y;
		noted = Math.max(noted, heart + 1);
	}

	/**
	 * 적어 둔 자리 위에 덮개를 그린다. {@code extractHearts} 가 끝난 직후에 불려 하트보다 위에 놓인다.
	 *
	 * @param maxHealth 바닐라가 체력 칸 수를 셀 때 쓴 최대 체력
	 */
	public static void draw(GuiGraphicsExtractor graphics, Player player, float maxHealth) {
		if (!active || noted == 0) {
			return;
		}
		active = false;
		PendingHeartSpan span = PendingHeartSpan.of(player.getHealth(), player.getAbsorptionAmount(),
				maxHealth, ClientStatSnapshot.spreadPending());
		if (span.isEmpty()) {
			return;
		}
		int color = tint();
		for (int heart = 0; heart < noted; heart++) {
			int x = heartX[heart];
			int y = heartY[heart];
			switch (span.partOf(heart)) {
				case FULL -> graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FULL, x, y,
						HEART_SIZE, HEART_SIZE, color);
				case LEFT -> graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HALF, x, y,
						HEART_SIZE, HEART_SIZE, color);
				case RIGHT -> {
					graphics.enableScissor(x + RIGHT_HALF_START, y, x + HEART_SIZE, y + HEART_SIZE);
					graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FULL, x, y,
							HEART_SIZE, HEART_SIZE, color);
					graphics.disableScissor();
				}
				case NONE -> {
				}
			}
		}
	}

	/** 지금 깜빡임 단계의 덮개 색. */
	private static int tint() {
		float phase = (Util.getMillis() % PULSE_MILLIS) / (float) PULSE_MILLIS;
		float wave = 0.5F - 0.5F * Mth.cos(phase * Mth.TWO_PI);
		float alpha = Mth.lerp(wave, ALPHA_LOW, ALPHA_HIGH);
		return (Math.round(alpha * 255.0F) << 24) | TINT_RGB;
	}
}
