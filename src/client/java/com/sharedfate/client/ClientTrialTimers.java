package com.sharedfate.client;

import com.sharedfate.net.TrialTimersPayload;
import org.jetbrains.annotations.Nullable;

/**
 * 드래곤 패턴 타이머 HUD 의 <b>받은 묶음</b>을 들고 틱마다 스스로 줄이는 자리.
 * {@code com.sharedfate.client.hud.TrialTimersHud} 가 이 값을 읽어 그린다.
 *
 * <h2>사람이 정한 것 (2026-10-05)</h2>
 *
 * <p>사람 말: <b>「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게 오른쪽
 * 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」</b> — 예시 화면을 보고 「이대로 진행」.
 *
 * <h2>아무것도 스스로 세지 않는다 — 줄이기만 한다</h2>
 *
 * <p>남은 틱은 서버가 실행기의 상태에서 읽어 보낸다({@code TrialTimersPayload} 설명). 여기서 주기를
 * 다시 세면 시련 룰렛의 멈춤 · 서버 재시작 · 건너뛴 주기에서 어긋나고, 이 화면이 거짓말을 하면 그
 * 자리에서 사람이 죽는다. 그래서 받은 값에서 <b>흐른 틱만 뺀다</b>. 서버는 상태가 바뀔 때 곧바로,
 * 그 밖에는 20틱마다 보내 바로잡는다.
 *
 * <ul>
 *   <li>새 묶음이 오면 <b>통째로 바꾸고</b> 흐른 틱을 0 으로 되돌린다</li>
 *   <li>틱마다 흐른 틱을 1 올린다 — <b>판이 얼어 있거나({@code frozen}) 게임이 일시정지면 안
 *       올린다.</b> 시련 룰렛 · 증강 선택이 판을 얼리면 서버의 게임 시각과 실행기의 시계가 함께 선다</li>
 *   <li>남은 틱은 0 에서 멈춘다 — 새 묶음이 곧 온다</li>
 * </ul>
 *
 * <p>줄마다 남은 틱을 하나하나 깎지 않고 흐른 틱 하나만 센다. 모든 줄이 같은 시계로 줄기 때문이고,
 * 그래야 묶음은 받은 그대로 두고 「지금 몇 틱 남았나」를 언제든 같은 셈으로 낼 수 있다
 * ({@code TrialTimerRows.remainingAfter}).
 *
 * <h2>지우는 길 둘</h2>
 *
 * <p>서버가 <b>{@code visible} 거짓을 한 번</b> 보낸다(엔드를 떠남 · 전투가 끝남 · 팀이 없어짐).
 * 접속이 끊기는 길로는 서버가 아무것도 못 보내므로 끊길 때 스스로 지운다({@link #clear}).
 * {@code ClientHotbarLock} 처럼 「한동안 안 오면 지운다」는 쓰지 않는다 — 판이 얼어 있는 17초 동안이나
 * 서버가 바쁜 순간에 HUD 가 깜빡인다(서버 묶음 설명의 「그만 그려라를 보낸다 — 한 번」).
 */
public final class ClientTrialTimers {

	private static @Nullable TrialTimersPayload current;
	private static int elapsed;

	private ClientTrialTimers() {
	}

	/** 서버가 보낸 묶음. {@code visible} 거짓이면 지운다. 그 밖은 통째로 바꾼다. */
	public static void update(TrialTimersPayload payload) {
		if (payload == null || !payload.visible()) {
			clear();
			return;
		}
		current = payload;
		elapsed = 0;
	}

	/** 지운다. 서버를 나갈 때도 부른다 — 남겨 두면 다음 서버의 첫 화면에 남의 드래곤 시계가 뜬다. */
	public static void clear() {
		current = null;
		elapsed = 0;
	}

	/**
	 * 클라이언트 틱 하나. {@code END_CLIENT_TICK} 에서 부른다.
	 *
	 * @param paused 게임이 일시정지인가({@code Minecraft.isPaused}). 혼자 하는 판에서 메뉴를 열면 참이다
	 */
	public static void tick(boolean paused) {
		if (current == null || !counting(current.frozen(), paused)) {
			return;
		}
		if (elapsed < Integer.MAX_VALUE) {
			elapsed++;
		}
	}

	/** 지금 시계가 도는가. 얼었거나 일시정지면 서 있다. */
	public static boolean counting(boolean frozen, boolean paused) {
		return !frozen && !paused;
	}

	/** 들고 있는 묶음. 없으면 {@code null} — 그리지 않는다. */
	public static @Nullable TrialTimersPayload current() {
		return current;
	}

	/** 묶음을 받은 뒤 흐른(얼지 않은) 틱. */
	public static int elapsed() {
		return elapsed;
	}
}
