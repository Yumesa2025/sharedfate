package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkChoiceSession;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 시련 화면이 떠 있는 동안 판을 멈춘다.
 *
 * <p>멈추는 자리가 둘이다 — 룰렛이 도는 동안({@link TrialCatalog.Reveal#ROULETTE},
 * 굴림 + {@link #HOLD_TICKS})과 정해진 카드 한 장을 읽는 동안
 * ({@link TrialCatalog.Reveal#FIXED_SCREEN}, {@link #FIXED_HOLD_TICKS}). 이 클래스는 둘을
 * 구분하지 않는다 — <b>몇 틱을 얼릴지는 부르는 쪽이 정해서 넘긴다.</b> 여기에 연출의 종류가
 * 들어오면 연출이 하나 늘 때마다 이 파일을 다시 뜯어야 한다.
 *
 * <p>{@link TrialCatalog.Reveal#SILENT} 은 <b>여기를 아예 지나지 않는다.</b>
 *
 * <h2>왜 멈추는가</h2>
 *
 * <p>처음에는 타이틀 글자를 갈아 끼우는 것으로 만들어 규약을 올리지 않았다. 그런데 <b>드래곤이
 * 때리는 중에 화면 위로 지나가는 글자는 읽히지 않는다.</b> 무엇을 받았는지 모른 채 싸우게 되고,
 * 이 모드에서 전멸은 월드 삭제다. 증강을 고를 때와 같이 판을 멈추고 뽑는다(2026-09-29).
 *
 * <h2>얼면 게임 시각이 멈춘다 — 그래서 자체 카운터로 센다</h2>
 *
 * <p>{@link ServerTickRateManager#setFrozen} 은 레벨 틱을 멈추므로 {@code getGameTime()} 이
 * 올라가지 않는다. 남은 시간을 게임 시각으로 세면 <b>영원히 녹지 않는다.</b> 우리 틱은
 * {@code END_SERVER_TICK} 이라 얼어 있어도 매 틱 도니, 여기서 직접 센다. 같은 이유로
 * {@link PerkChoiceSession} 도 제한시간을 서버가 센다.
 *
 * <h2>영원히 얼지 않게 하는 장치</h2>
 *
 * <ol>
 *   <li>남은 틱은 <b>서버가</b> 센다. 클라이언트가 아무 말도 하지 않아도 0 이 되면 녹는다.
 *       화면을 닫는 응답을 기다리지 않는다 — 기다리면 접속이 끊긴 사람 하나가 서버를 얼린다</li>
 *   <li>{@link #MAX_TICKS} 상한이 있다. 계산이 어긋나 큰 값이 들어와도 그 이상 얼지 않는다</li>
 *   <li>지켜보던 사람이 전부 나가면 그 자리에서 녹인다</li>
 *   <li>서버가 멈출 때 녹인다. 바닐라는 정지 상태를 저장하지 않지만 켜질 때 한 번 더 본다</li>
 * </ol>
 *
 * <h2>얼기 전 상태를 되돌린다</h2>
 *
 * <p>운영자가 {@code /tick freeze} 를 직접 걸어 둔 상태였다면 우리가 녹여서는 안 된다.
 * {@link PerkChoiceSession} 이 같은 자리에서 같은 판단을 한다.
 *
 * <h2>증강 선택에 양보한다</h2>
 *
 * <p>엔드 전투 중에 레벨 구간이 올 수 있다. 둘이 동시에 얼리면 나중에 녹는 쪽이 앞의 상태를
 * 「얼어 있었다」로 기억해 <b>아무도 녹이지 않는 상태</b>가 만들어진다. 시련 쪽이 물러난다 —
 * 미뤄 두면 증강이 끝난 뒤에 뜨지만, 얼어붙은 서버는 되돌릴 방법이 없다.
 */
public final class TrialFreeze {

	/**
	 * 카드 설명을 읽는 시간에 <b>나중에 더한 몫</b>. <b>10초(200틱)</b>다.
	 *
	 * <p>사람 말(2026-10-04): <b>「시련 설명글 좀 더 유지. 10초 더」</b>. 설명글은 판이 멈춘
	 * 뒤에야 뜨므로({@code TrialRouletteScreen} 의 「설명은 멈춘 뒤에만 연다」) 늘려야 하는 것은
	 * 굴림이 아니라 <b>붙잡아 두는 시간</b>이고, 설명이 뜨는 화면이 둘이라 두 값에 똑같이 더한다 —
	 * {@link #HOLD_TICKS}(룰렛)와 {@link #FIXED_HOLD_TICKS}(굴리지 않는 화면).
	 *
	 * <p>더한 몫을 따로 둔 것은 <b>원래 값의 근거가 지워지지 않게</b> 하려는 것이다.
	 * {@link #FIXED_HOLD_TICKS} 의 「왜 100인가」 셈은 60 과 137 을 놓고 한 것이고, 숫자를 합쳐
	 * 적으면 그 셈을 다시 읽을 수가 없다.
	 *
	 * <p>⚠ 이 시간 내내 <b>판이 얼어 있고 화면은 ESC 로 못 닫는다.</b> 늘어난 10초는 곧 「얼어
	 * 있는 10초」다 — 드래곤도 카드도 멈춰 있고 피해도 막히므로({@link #blocksDamage}) 위험은
	 * 없지만, 사람이 움직이지 못하는 시간도 그만큼 늘었다. 효과는 얼음이 풀린 뒤에야 쌓이므로
	 * ({@code DragonTrialManager.applyFinishedTrial}) 설명이 떠 있는 동안 새 카드가 돌지 않는다.
	 */
	public static final int READ_EXTENSION_TICKS = 10 * 20;

	/**
	 * 룰렛이 멈춘 뒤 결과를 읽을 시간. 처음 잡은 60틱(3초)에 {@link #READ_EXTENSION_TICKS} 를
	 * 더해 <b>13초</b>다.
	 */
	public static final int HOLD_TICKS = 60 + READ_EXTENSION_TICKS;

	/**
	 * 룰렛을 돌리지 않고 <b>정해진 카드 한 장</b>만 보여 줄 때 판을 멈추는 시간
	 * ({@link TrialCatalog.Reveal#FIXED_SCREEN}). 처음 잡은 100틱(5초)에
	 * {@link #READ_EXTENSION_TICKS} 를 더해 <b>15초</b>다.
	 *
	 * <p>⚠ 아래 셈은 <b>더하기 전의 값</b>(읽는 시간 60틱, 룰렛 전체 137틱)으로 한 것이다. 세 값에
	 * 같은 200틱을 더했으므로 셈이 세운 순서는 그대로다 — 읽는 시간은 룰렛보다 길고
	 * (300 &gt; 260) 전체는 룰렛보다 짧다(300 &lt; 337). 시험이 이 두 부등식과 {@link #MAX_TICKS}
	 * 를 붙든다.
	 *
	 * <h2>왜 {@link #HOLD_TICKS} 를 그대로 쓰지 않는가</h2>
	 *
	 * <p>룰렛은 굴림 {@value TrialRoulette#TOTAL_TICKS} 틱 + 읽는 시간 60틱이었고,
	 * <b>실제로 읽는 것은 뒤쪽 60틱뿐</b>이다 — 설명은 멈춘 뒤에야 뜬다. 그런데 그 60틱은 사람
	 * 눈이 <b>이미 4초 동안 그 판 위에 있던 뒤</b>의 60틱이다. 굴림이 빠지면 화면이 아무 예고
	 * 없이 뜨고, 그 처음 얼마는 「무엇이 떴나」에 쓰인다 — {@link TrialWarning#TICKS_SIDESTEP}
	 * 이 지각·판단에만 0.25초를 따로 잡아 두는 것과 같은 몫이다. 60틱을 그대로 물려주면 실제로
	 * 읽는 시간은 2초 남짓이 된다.
	 *
	 * <h2>그렇다고 룰렛 전체 길이를 쓰지도 않는다</h2>
	 *
	 * <p>{@value TrialRoulette#TOTAL_TICKS} + 60 = 137틱을 그대로 쓰면
	 * <b>아무것도 움직이지 않는 화면이 7초</b> 떠 있다. 룰렛의 7초가 견딜 만한 것은 그중 4초가
	 * 돌고 있기 때문이고, 여기서는 그 4초가 없다.
	 *
	 * <p>그래서 둘 사이에서 <b>100틱</b>을 잡았다. 읽는 시간은 룰렛보다 40틱 길어 「뜬 것을
	 * 알아채는」 몫을 스스로 치르고, 멈추는 전체 길이는 룰렛(137틱)보다 짧아 <b>굴림도 없는
	 * 자리가 룰렛보다 무거워지지 않는다.</b> {@link #MAX_TICKS} 안이다.
	 *
	 * <p>⚠ <b>실제로 플레이해 보고 정한 값이 아니다</b> — 근거는 위 셈뿐이다. 고칠 때는 여기
	 * 한 곳만 고치면 된다. 화면이 쓰는 값은 {@code TrialRoulettePayload.holdTicks} 로 실려 나가고,
	 * 그 값을 채우는 자리도 {@code DragonTrialManager} 한 곳이다.
	 */
	public static final int FIXED_HOLD_TICKS = 100 + READ_EXTENSION_TICKS;

	/**
	 * 어떤 경우에도 이보다 오래 얼지 않는다.
	 *
	 * <p>연출 길이는 카드가 아니라 코드가 정하므로 큰 값이 들어올 일이 없어야 한다. 그래도 두는
	 * 것은, 여기서 한 번 잘못되면 <b>운영자가 콘솔에 들어가야만</b> 서버가 되살아나기 때문이다.
	 */
	public static final int MAX_TICKS = 400;

	private static final class State {
		final UUID teamId;
		final String trialId;
		final boolean frozenBefore;
		final boolean frozenByUs;
		final Set<UUID> watching = new HashSet<>();
		int remaining;

		State(UUID teamId, String trialId, boolean frozenBefore, int remaining) {
			this.teamId = teamId;
			this.trialId = trialId;
			this.frozenBefore = frozenBefore;
			this.frozenByUs = !frozenBefore;
			this.remaining = remaining;
		}
	}

	/** 연출이 끝나 적용할 것이 생겼다. {@link #poll()} 이 한 번만 돌려준다. */
	public record Finished(UUID teamId, String trialId) {
	}

	private static @Nullable State state;
	private static @Nullable Finished finished;

	private TrialFreeze() {
	}

	/**
	 * 판을 멈추고 시련 화면을 연다.
	 *
	 * @param ticks 화면이 떠 있는 전체 길이. <b>읽는 시간까지 이미 더해져서</b> 와야 한다 —
	 *              룰렛이면 굴림 + {@link #HOLD_TICKS}, 정해진 카드 화면이면
	 *              {@link #FIXED_HOLD_TICKS} 다
	 * @return 실제로 멈췄으면 참. 이미 떠 있거나 증강 선택 중이면 거짓
	 */
	public static boolean begin(@Nullable MinecraftServer server, @Nullable UUID teamId,
			@Nullable String trialId, java.util.List<ServerPlayer> watching, int ticks) {
		if (server == null || teamId == null || trialId == null || trialId.isBlank()) {
			return false;
		}
		if (state != null || finished != null) {
			return false;
		}
		// 증강이 이미 얼려 두었다면 물러난다. 둘이 겹치면 아무도 녹이지 않는 상태가 된다.
		if (PerkChoiceSession.isActive()) {
			return false;
		}
		if (watching == null || watching.isEmpty()) {
			return false;
		}
		int bounded = Math.min(MAX_TICKS, Math.max(1, ticks));

		ServerTickRateManager tickRate = server.tickRateManager();
		boolean frozenBefore = tickRate.isFrozen();
		State opened = new State(teamId, trialId, frozenBefore, bounded);
		for (ServerPlayer member : watching) {
			opened.watching.add(member.getUUID());
		}
		state = opened;
		if (!frozenBefore) {
			tickRate.setFrozen(true);
		} else {
			SharedFateMod.LOGGER.info(
					"시련 화면을 여는데 서버가 이미 정지 상태였습니다. 끝나도 정지를 그대로 둡니다.");
		}
		return true;
	}

	/** 매 틱. 얼어 있어도 이 틱은 돈다. */
	public static void tick(@Nullable MinecraftServer server) {
		State current = state;
		if (current == null || server == null) {
			return;
		}
		// 지켜볼 사람이 하나도 안 남으면 얼려 둘 이유가 없다.
		boolean anyOnline = false;
		for (UUID memberId : current.watching) {
			if (server.getPlayerList().getPlayer(memberId) != null) {
				anyOnline = true;
				break;
			}
		}
		if (!anyOnline) {
			finish(server, current, "지켜보던 사람이 모두 나가 시련 화면을 닫습니다.");
			return;
		}
		current.remaining--;
		if (current.remaining <= 0) {
			finish(server, current, null);
		}
	}

	/** 지금 시련 화면이 떠 있는가. 룰렛이든 정해진 카드 화면이든 참이다. */
	public static boolean isActive() {
		return state != null;
	}

	/**
	 * 이 사람의 피해를 지금 버려야 하는가.
	 *
	 * <p>바닐라의 시간 정지는 플레이어를 얼리지 않는다. 용암·낙하·불·익사는 플레이어 자기 틱에서
	 * 계산되므로 <b>얼어 있는 동안에도 그대로 들어온다.</b> 화면을 읽는 4초 사이에 공유 체력이
	 * 깎이면 그 자체가 새 사고다.
	 */
	public static boolean blocksDamage(@Nullable Entity entity) {
		State current = state;
		if (current == null || !(entity instanceof ServerPlayer player)) {
			return false;
		}
		return current.watching.contains(player.getUUID());
	}

	/**
	 * 끝난 룰렛의 결과를 가져간다. <b>한 번만 돌려준다.</b>
	 *
	 * <p>카드를 실제로 쌓는 것은 {@code DragonTrialManager} 다. 여기서 직접 하지 않는 것은,
	 * 이 클래스가 세션도 카드 목록도 모르게 두어야 시험이 월드 없이 돌기 때문이다.
	 */
	public static @Nullable Finished poll() {
		Finished done = finished;
		finished = null;
		return done;
	}

	/** 서버가 켜질 때. 지난 실행의 찌꺼기를 털어낸다. */
	public static void onServerStarted(@Nullable MinecraftServer server) {
		state = null;
		finished = null;
		if (server == null) {
			return;
		}
		ServerTickRateManager tickRate = server.tickRateManager();
		if (tickRate.isFrozen()) {
			// 바닐라는 정지를 저장하지 않으므로 여기서 얼어 있으면 비정상이다.
			tickRate.setFrozen(false);
			SharedFateMod.LOGGER.warn("서버 시작 시점에 시간이 멈춰 있어 강제로 풀었습니다.");
		}
	}

	/** 서버가 멈추기 직전. 얼려 둔 채로 종료하지 않는다. */
	public static void onServerStopping(@Nullable MinecraftServer server) {
		State current = state;
		if (current != null) {
			finish(server, current, null);
		}
		finished = null;
	}

	/** 월드가 바뀔 때. 서버 객체를 만질 수 없는 자리에서는 상태만 버린다. */
	public static void reset() {
		state = null;
		finished = null;
	}

	/**
	 * 정지를 되돌린다. <b>이 메서드만이 시간을 다시 흐르게 한다.</b>
	 *
	 * <p>상태를 먼저 비워 재진입을 막고, 그다음 녹인다. 아래에서 예외가 나도 세션은 이미 죽어
	 * 있어야 한다 — 죽지 않으면 다음 틱에 같은 예외가 영원히 반복된다.
	 */
	private static void finish(@Nullable MinecraftServer server, State current,
			@Nullable String reason) {
		state = null;
		// 중간에 닫혔어도 결과는 넘긴다. 카드를 안 주면 자리 하나가 조용히 사라진다.
		finished = new Finished(current.teamId, current.trialId);
		if (server == null) {
			return;
		}
		try {
			if (current.frozenByUs) {
				server.tickRateManager().setFrozen(current.frozenBefore);
			}
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.error(
					"시간 정지를 되돌리지 못했습니다. /tick unfreeze 로 풀어 주십시오.", error);
		}
		if (reason != null) {
			SharedFateMod.LOGGER.info(reason);
		}
	}
}
