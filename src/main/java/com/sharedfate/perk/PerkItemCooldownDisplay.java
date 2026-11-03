package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.DiamondSundialEffect;
import com.sharedfate.perk.effect.FlightCharmEffect;
import com.sharedfate.perk.effect.RallyShardEffect;
import com.sharedfate.sync.TitleMessenger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.UseCooldown;
import org.jetbrains.annotations.Nullable;

/**
 * 쿨타임이 있는 증강 아이템을 <b>손에 들고 있는 동안</b> 남은 시간을 액션바에 적어 준다.
 *
 * <p>바닐라도 아이템 칸 위에 쿨타임 게이지를 그리지만 그것으로는 「얼마나 남았는지」를 알 수
 * 없다. 4분짜리 「소집의 조각」처럼 긴 것은 게이지가 거의 안 움직여 더 그렇다. 그래서 들고 있는
 * 동안만 숫자로 적어 준다 — 들지 않았을 때는 아무 것도 보내지 않으므로 다른 액션바 알림
 * (「엑스레이」의 광물 개수 · 보급 알림)을 덮지 않는다.
 *
 * <h2>총 쿨타임을 아이템에서 읽는다</h2>
 * <p>{@code minecraft:use_cooldown} 컴포넌트에 초가 적혀 있다. 증강 정의를 되짚지 않아도 되고,
 * 정의를 고쳐 값이 달라져도 이미 나가 있는 아이템의 표시가 그 아이템의 실제 쿨타임과 어긋나지
 * 않는다.
 *
 * <p>남은 비율은 {@code ItemCooldowns.getCooldownPercent} 가 준다. 26.2 에서 이 값은 서버에도
 * 그대로 있다 — 쿨타임은 서버가 들고 있고 클라이언트에는 패킷으로 내려간다.
 *
 * <h2>새 패킷이 없다</h2>
 * <p>액션바는 바닐라 패킷이라 <b>통신 규약을 올리지 않는다.</b> 모드를 깐 클라이언트가 아니어도
 * 글자가 보인다.
 */
public final class PerkItemCooldownDisplay {

	/**
	 * 몇 틱마다 갱신하는가.
	 *
	 * <p>0.25초다. 매 틱 보내면 사람 수만큼 초당 20패킷이 되고, 1초마다 보내면 숫자가 뚝뚝
	 * 끊겨 보인다. 소수 첫째 자리까지 적으므로 이 간격이면 눈에 부드럽다.
	 */
	private static final int INTERVAL_TICKS = 5;

	private static final int TICKS_PER_SECOND = 20;

	/** 경고를 한 번만 남기기 위한 표시. */
	private static boolean warned;

	private PerkItemCooldownDisplay() {
	}

	/** {@code SharedFateMod} 가 서버 틱마다 부른다. */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null || server.getTickCount() % INTERVAL_TICKS != 0) {
			return;
		}
		try {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				show(player);
			}
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/** 이 사람이 지금 들고 있는 것이 쿨타임 중인 증강 아이템이면 남은 시간을 적는다. */
	private static void show(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		ItemStack held = player.getMainHandItem();
		int rush = PerkShieldCombat.rushRemaining(player);
		if (held.is(net.minecraft.world.item.Items.SHIELD) && rush > 0) {
			TitleMessenger.showActionBar(player, Component.literal(PerkFlightCharm.PREFIX
					+ "방패 돌진 " + format(rush) + "초").withStyle(ChatFormatting.GRAY));
			return;
		}
		String name = displayNameOf(held);
		if (name == null || !player.getCooldowns().isOnCooldown(held)) {
			return;
		}
		int remaining = remainingTicks(player, held);
		if (remaining <= 0) {
			return;
		}
		TitleMessenger.showActionBar(player, Component
				.literal(PerkFlightCharm.PREFIX + name + " " + format(remaining) + "초")
				.withStyle(ChatFormatting.GRAY));
	}

	/**
	 * 남은 쿨타임(틱). 알 수 없으면 0.
	 *
	 * <p>{@code getCooldownPercent} 는 <b>남은 비율</b>(1 → 방금 걸림, 0 → 다 됨)이다. 아이템에
	 * 적힌 총 쿨타임을 곱해 틱으로 되돌린다.
	 */
	public static int remainingTicks(@Nullable ServerPlayer player, @Nullable ItemStack held) {
		if (player == null || held == null || held.isEmpty()) {
			return 0;
		}
		int total = totalCooldownTicks(held);
		if (total <= 0) {
			return 0;
		}
		float percent = player.getCooldowns().getCooldownPercent(held, 0.0F);
		if (!Float.isFinite(percent) || percent <= 0.0F) {
			return 0;
		}
		return Math.round(Math.min(1.0F, percent) * total);
	}

	/**
	 * 이 아이템에 적힌 총 쿨타임(틱). 컴포넌트가 없으면 0.
	 *
	 * <p>{@code UseCooldown.seconds()} 는 초 단위 실수다.
	 */
	public static int totalCooldownTicks(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		UseCooldown cooldown = stack.get(DataComponents.USE_COOLDOWN);
		if (cooldown == null) {
			return 0;
		}
		float seconds = cooldown.seconds();
		if (!Float.isFinite(seconds) || seconds <= 0.0F) {
			return 0;
		}
		return Math.round(seconds * TICKS_PER_SECOND);
	}

	/**
	 * 이것이 쿨타임을 적어 줄 증강 아이템인가. 맞으면 화면에 적을 이름, 아니면 {@code null}.
	 *
	 * <p>판정은 <b>표식</b>(`custom_data`)을 보는 각 효과의 함수에 맡긴다. 이름으로 가리면
	 * 모루에서 이름만 바꾼 가짜에도 걸린다.
	 *
	 * <p>새 쿨타임 아이템을 만들면 여기에 한 줄 늘리면 된다.
	 */
	public static @Nullable String displayNameOf(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		if (FlightCharmEffect.isFlightCharm(stack)) {
			return FlightCharmEffect.DISPLAY_NAME;
		}
		if (DiamondSundialEffect.isSundial(stack)) {
			return DiamondSundialEffect.DISPLAY_NAME;
		}
		if (RallyShardEffect.isRallyShard(stack)) {
			return RallyShardEffect.DISPLAY_NAME;
		}
		return null;
	}

	/**
	 * 남은 틱을 사람이 읽는 글자로.
	 *
	 * <p>10초 미만은 소수 첫째 자리까지 적는다 — 마지막 몇 초가 가장 궁금한 자리이고, 거기서만
	 * 숫자가 움직이는 것이 보인다. 그 위는 초 단위로 올림한다(「0초 남음」이 뜨는데 아직 안
	 * 되는 일이 없도록).
	 */
	public static String format(int remainingTicks) {
		if (remainingTicks >= 10 * TICKS_PER_SECOND) {
			return String.valueOf((remainingTicks + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND);
		}
		double seconds = remainingTicks / (double) TICKS_PER_SECOND;
		return String.format("%.1f", seconds);
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn("증강 아이템 쿨타임 표시에 실패했습니다. 이 경고는 한 번만 남습니다.",
				error);
	}
}
