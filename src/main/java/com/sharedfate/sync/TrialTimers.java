package com.sharedfate.sync;

import com.sharedfate.net.TrialTimersPayload;
import com.sharedfate.net.TrialTimersPayload.Cast;
import com.sharedfate.net.TrialTimersPayload.Entry;
import com.sharedfate.net.TrialTimersPayload.Kind;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 드래곤 패턴 타이머 HUD 의 <b>서버 쪽</b> — 무엇을 담고, 누구에게, 언제 보내는가.
 *
 * <h2>사람이 정한 것 (2026-10-05)</h2>
 *
 * <p>사람 말: <b>「와우 레이드에서 보스 스킬 시전 바, 몇 초 뒤에 오는지 패턴 바 같은 게 오른쪽
 * 상단에 있어서 몇 초 뒤에 패턴 오는지 알려 주는 건 어때?」</b>. 화면의 모양은
 * {@link TrialTimersPayload} 에 적어 두었다.
 *
 * <h2>⚠⚠ 주기를 여기서 다시 세지 않는다 — 실행기에게 묻는다</h2>
 *
 * <p>남은 틱은 전부 <b>실행기가 돌려준 값</b>이다({@code clock} 이라는 이름의 읽기 메서드 —
 * {@link TrialRisks#strikeClock} · {@link TrialFireball#clock} · {@link DragonFireBarrage#clock} …).
 * 실행기마다 「다음 발동」의 정의가 다르고(받은 틱부터 · 고른 틱부터 · 크리스탈이 달아오른 틱부터 ·
 * 쉬는 시간을 굴린 틱부터), 같은 셈을 여기 한 벌 더 두면 실행기를 고치는 날 HUD 만 옛 주기를
 * 센다 — 이 저장소가 「같은 셈 세 벌」로 이미 겪은 일이다({@code docs/드래곤-트라이얼.md} 6장).
 * 읽기 메서드는 <b>실행기의 상태를 읽기만 하고</b> 한 칸도 쓰지 않는다.
 *
 * <h2>무엇을 「다음 사건」으로 보는가</h2>
 *
 * <p>사람이 묻는 것은 「몇 초 뒤에 패턴이 오나」다. 그래서 <b>실제 피해 · 효과가 들어가는 틱</b>을
 * 센다 — 예고가 시작되는 틱이 아니다. 바닥 표식이 그보다 먼저 깔리는 길이는
 * {@link Entry#warnTicks} 로 따로 싣는다. 카드마다 무엇을 사건으로 봤는지는 각 실행기의
 * {@code clock} 설명에 있다.
 *
 * <h2>넣지 않는 것</h2>
 *
 * <ul>
 *   <li><b>시각이 정해지지 않은 것</b> — 착지(바닐라 주사위)와 「착지 충격」(착지할 때). 숫자를
 *       띄우면 거짓말이다</li>
 *   <li><b>한 번으로 끝나는 카드</b> — 크리스탈 보호막 · 다시 선 쇠창살 · 부활 · 메마른 세계</li>
 *   <li>⚠ 「연결된 수정」은 <b>보호막이 서 있는 동안만</b> 「수정 보호막 · 진행 중」으로 넣는다.
 *       깨면 30초 동안 다른 수정이 안 깨지는데, 그 30초가 언제 끝나는지가 곧 「언제 다시 쏘는가」라
 *       사람이 쓸 수 있는 값이다. 「밤의 군세」의 적대 20초와 「종말의 비」의 30초도 같은 까닭으로
 *       진행 중 줄로 넣는다</li>
 * </ul>
 *
 * <h2>누구에게 · 언제</h2>
 *
 * <p>그 팀의 드래곤 전투에 있는 사람 — <b>엔드에 서 있고 세션이 열린 팀원</b>에게만
 * ({@code DragonTrialManager.publishTimers}). HUD 를 한 번이라도 받은 사람이 거기서 빠지면
 * (엔드를 떠남 · 전투가 끝남 · 팀이 없어짐) <b>{@link TrialTimersPayload#HIDDEN} 을 한 번</b> 보낸다.
 * 시련을 끈 팀은 처음부터 받지 않으므로 지울 것도 없다.
 *
 * <p>보내는 때는 <b>상태가 바뀔 때</b>(패턴 고름 · 발동 · 카드 추가 · 멈춤 시작/끝 · 축소 시작) +
 * 그 외 {@link #HEARTBEAT_TICKS} 마다다. 「상태가 바뀌었다」는 사건 목록을 따로 들지 않고 <b>지난번
 * 묶음에서 예측한 값과 지금 값을 견준다</b>({@link #needsSend}) — 발동하면 남은 틱이 다음 주기로
 * 튀어 예측과 어긋나고, 패턴을 고르면 시전 바의 제목이 바뀐다. 사건을 하나하나 알려 주게 두면
 * 실행기 열몇 개가 이 파일을 불러야 하고, 하나를 빠뜨린 카드만 HUD 가 20틱 늦는다.
 *
 * <h2>멈춤</h2>
 *
 * <p>시련 룰렛이 판을 얼리면({@link TrialFreeze}) 레벨 틱이 서서 게임 시각이 안 오른다. 카드
 * 실행기는 전부 그 게임 시각({@code now})으로 세므로 <b>남은 틱이 저절로 멈춘다</b> — 따로 뺄 것이
 * 없다. 받는 쪽만 모르므로 {@link TrialTimersPayload#frozen} 으로 알린다. 증강 선택이 얼려도
 * 마찬가지라 바닐라 정지 상태를 함께 본다.
 */
public final class TrialTimers {

	/**
	 * 상태가 안 바뀌어도 다시 보내는 간격(틱). 1초.
	 *
	 * <p>받는 쪽이 틱마다 스스로 줄이므로 이것은 <b>틀어진 것을 바로잡는 몫</b>이다 — 클라이언트 틱이
	 * 서버보다 느리게 돈 판, 체력형 줄의 바(드래곤이 맞을 때마다 움직이지만 그때마다 보내지는 않는다).
	 */
	static final int HEARTBEAT_TICKS = 20;

	/**
	 * 예측과 실제가 이만큼 넘게 벌어지면 상태가 바뀐 것으로 본다(틱).
	 *
	 * <p>0 이 아닌 것은 실행기들이 「이번 틱에 터졌다」를 다루는 자리가 한 틱씩 다르기 때문이다 —
	 * 그 한 틱 차이로 매 틱 보내는 일이 없게 한다. 발동이 만드는 차이는 주기 하나(수백 틱)라 1 에
	 * 걸리지 않는다.
	 */
	static final int DRIFT_TOLERANCE_TICKS = 1;

	/** 기본 패시브 「연쇄 포격」 줄의 열쇠. */
	static final String BARRAGE_ID = "passive:barrage";
	/** 최후의 저항 — 안전지대 줄의 열쇠. */
	static final String ZONE_ID = "last_stand:zone";
	/** 최후의 저항 — 상시 번개 줄의 열쇠. */
	static final String LIGHTNING_ID = "last_stand:lightning";
	/** 최후의 저항 — 오브젝트 파도 줄의 열쇠. */
	static final String OBJECTS_ID = "last_stand:objects";

	/** 시전 바 — 진입 보호막 구간의 제목. */
	static final String SHIELD_TITLE = "최후의 저항 · 보호막";
	/** 시전 바 — 쉬는 중의 제목. 패턴은 고르는 순간 정해져 미리 알 수 없다. */
	static final String REST_TITLE = "다음 패턴 · 무작위";
	/** 시전 바 — 진행 중 제목 뒤에 붙는 말. 사람 화면의 「이름 · 진행 중」. */
	static final String RUNNING_SUFFIX = " · 진행 중";

	/**
	 * 실행기가 돌려주는 「다음 사건」 하나. <b>월드 시간이 아니라 남은 틱</b>이다.
	 *
	 * @param remaining 사건까지(진행 중이면 끝날 때까지) 남은 틱. <b>1 이상</b> — 이번 틱에 이미
	 *                  터진 것은 다음 주기로 센다. 이 값은 그 틱의 실행기가 다 돈 <b>뒤에</b> 읽힌다
	 * @param total     그 카운트다운의 전체 길이(바의 분모)
	 * @param warn      사건 전에 바닥 표식이 깔리는 길이. 진행 중이면 0
	 * @param active    지금 벌어지는 중인가
	 */
	record Clock(int remaining, int total, int warn, boolean active) {

		Clock {
			remaining = Math.max(1, remaining);
			total = Math.max(remaining, total);
			warn = Math.max(0, warn);
		}

		static Clock countdown(long remaining, long total, int warn) {
			return new Clock(clampInt(remaining), clampInt(total), warn, false);
		}

		static Clock active(long remaining, long total) {
			return new Clock(clampInt(remaining), clampInt(total), 0, true);
		}
	}

	/**
	 * 최후의 저항의 안전지대가 다음에 어떻게 되는가.
	 *
	 * @param shrinking 지금 줄어드는 중인가. 그러면 {@code remaining} 은 다 줄 때까지다
	 * @param remaining 축소가 시작될 때까지(줄어드는 중이면 끝날 때까지) 남은 틱
	 * @param total     그 카운트다운의 전체 길이
	 * @param toRadius  이번 축소가 끝나면 될 반경(칸)
	 */
	record ZoneTimer(boolean shrinking, long remaining, long total, double toRadius) {
	}

	/**
	 * 오브젝트 파도의 지금 상태.
	 *
	 * @param firedWaves 이미 열린 문턱 수(0·1·2)
	 * @param running    지금 파도가 서 있는가
	 * @param live       서 있는 크리스탈 수
	 * @param slots      이번 파도에 박힌 자리 수
	 */
	record ObjectsView(int firedWaves, boolean running, int live, int slots) {
	}

	/** 한 사람에게 마지막으로 보낸 것. */
	record Sent(TrialTimersPayload payload, long gameTime, int serverTick) {
	}

	/**
	 * 이번 틱에 실제로 보낼 것 하나 — 누구에게 무엇을. {@link #plan} 이 고르고 {@link #publish} 가
	 * 그대로 보낸다.
	 */
	record Delivery(UUID player, TrialTimersPayload payload) {
	}

	/**
	 * 사람마다 마지막으로 보낸 묶음. <b>여기 있는 사람만</b> 지우는 묶음을 받는다.
	 *
	 * <p>사람으로 열쇠를 잡는다 — 늦게 엔드에 들어온 팀원은 첫 틱에 곧장 받고, 나간 사람은 다음 틱에
	 * 한 번 지워진다.
	 */
	private static final Map<UUID, Sent> SENT = new HashMap<>();

	private TrialTimers() {
	}

	// ------------------------------------------------------------------ 보내기

	/**
	 * 이번 틱에 HUD 를 받을 사람들과 그 묶음. {@code DragonTrialManager} 가 모아 넘긴다.
	 *
	 * <p>여기 없는데 전에 받은 적이 있는 사람에게는 지우는 묶음을 한 번 보낸다 — 그래서 엔드가 없는
	 * 틱 · 시련 서버 스위치가 꺼진 틱에는 <b>빈 맵을 넘기면 된다.</b>
	 *
	 * @param now 지금 게임 시각. 예측에 쓴다 — 얼어 있으면 오르지 않는다
	 */
	public static void publish(@Nullable MinecraftServer server,
			Map<ServerPlayer, TrialTimersPayload> audience, long now) {
		if (server == null) {
			return;
		}
		// 사람 객체는 여기서만 다룬다. 고르는 일은 plan 이 UUID 로 한다 — 그래야 시험이 굴린다.
		Map<UUID, ServerPlayer> players = new HashMap<>();
		Map<UUID, TrialTimersPayload> byId = new HashMap<>();
		for (Map.Entry<ServerPlayer, TrialTimersPayload> target : audience.entrySet()) {
			ServerPlayer player = target.getKey();
			if (player == null) {
				continue;
			}
			players.put(player.getUUID(), player);
			byId.put(player.getUUID(), target.getValue());
		}
		java.util.function.Function<UUID, ServerPlayer> resolve = id -> {
			ServerPlayer player = players.get(id);
			// 받는 사람 목록에 없는 사람(지울 사람)은 접속 목록에서 찾는다. 끊었으면 null 이다.
			return player != null ? player : server.getPlayerList().getPlayer(id);
		};
		List<Delivery> deliveries = plan(SENT, byId, id -> {
			ServerPlayer player = resolve.apply(id);
			// canSend 를 먼저 묻는 것은 이 저장소의 관습이다(TrialHotbarLock.send). 이 묶음을 받을
			// 줄 모르는 클라이언트 — 클라이언트 쪽 수신 등록이 아직 없는 판 — 에는 아예 보내지 않는다.
			return player != null && ServerPlayNetworking.canSend(player, TrialTimersPayload.TYPE);
		}, now, server.getTickCount());
		for (Delivery delivery : deliveries) {
			ServerPlayer player = resolve.apply(delivery.player());
			if (player != null) {
				ServerPlayNetworking.send(player, delivery.payload());
			}
		}
	}

	/**
	 * {@link #publish} 의 <b>결정부</b> — 이번 틱에 누구에게 무엇을 보내고, 기록({@code sent})을 어떻게
	 * 고치는가. <b>월드도 연결도 모른다 — 시험이 직접 굴린다.</b>
	 *
	 * <h2>떼어 낸 까닭 (2026-10-06 Orca 검토 F-verified 의 V10)</h2>
	 *
	 * <p>보내기·지우기·받는 사람 로직에 시험이 하나도 없었다. 지금 코드는 맞게 돌지만 아래 순서 하나만
	 * 바뀌어도 조용히 깨진다 — 검토가 짚은 실패 넷이 전부 이 메서드 안의 줄 순서다.
	 *
	 * <ul>
	 *   <li><b>「받았다」는 보낼 수 있는지 묻기 전에 센다.</b> 거꾸로 두면 받을 줄 모르는 클라이언트가
	 *       매 틱 「지울 사람」 후보가 된다</li>
	 *   <li><b>보낼 수 없으면 기록하지 않는다.</b> 기록해 두면 다음 틱에 받을 수 있게 된 사람이 「이미
	 *       보냈다」로 읽혀 박동(1초)까지 아무것도 못 받는다</li>
	 *   <li><b>지운 사람은 기록에서 뺀다.</b> 안 빼면 엔드를 떠난 사람이 <b>매 틱</b> 지우는 묶음을
	 *       받는다 — 「한 번」이 깨진다</li>
	 *   <li><b>기록에서 빼는 것은 보낼 수 있든 없든이다.</b> 접속을 끊은 사람은 보낼 길이 없고, 받는
	 *       쪽이 끊길 때 스스로 지운다. 남겨 두면 다시 들어온 사람이 「이미 보냈다」로 읽힌다</li>
	 * </ul>
	 *
	 * <p>보낼지 말지(상태 변화 즉시 · 박동 {@value #HEARTBEAT_TICKS} 틱 · 멈춤 시작과 끝)는
	 * {@link #needsSend} 가 정한다. 받는 쪽이 묶음을 한 번도 안 받았으면 묻지 않고 보낸다.
	 *
	 * @param sent     사람마다 마지막으로 보낸 것. <b>이 메서드가 고친다</b> — 보낸 것은 적고, 지운 것은 뺀다
	 * @param audience 이번 틱에 HUD 를 받을 사람과 그 묶음. 묶음이 {@code null} 이거나 안 보이는 것이면
	 *                 받지 않는 사람으로 센다
	 * @param canSend  그 사람에게 이 묶음을 보낼 수 있는가(접속해 있고 수신 등록이 있다)
	 * @return 보낼 것들. 받는 사람 몫이 먼저, 지우는 묶음이 뒤다
	 */
	static List<Delivery> plan(Map<UUID, Sent> sent, Map<UUID, TrialTimersPayload> audience,
			java.util.function.Predicate<UUID> canSend, long now, int serverTick) {
		List<Delivery> deliveries = new ArrayList<>();
		Set<UUID> reached = new HashSet<>();
		for (Map.Entry<UUID, TrialTimersPayload> target : audience.entrySet()) {
			UUID player = target.getKey();
			TrialTimersPayload payload = target.getValue();
			if (player == null || payload == null || !payload.visible()) {
				continue;
			}
			reached.add(player);
			Sent last = sent.get(player);
			if (last != null && !needsSend(last.payload(), last.gameTime(), last.serverTick(),
					payload, now, serverTick)) {
				continue;
			}
			if (!canSend.test(player)) {
				continue;
			}
			deliveries.add(new Delivery(player, payload));
			sent.put(player, new Sent(payload, now, serverTick));
		}
		Iterator<Map.Entry<UUID, Sent>> shown = sent.entrySet().iterator();
		while (shown.hasNext()) {
			Map.Entry<UUID, Sent> entry = shown.next();
			if (reached.contains(entry.getKey())) {
				continue;
			}
			shown.remove();
			// 접속을 끊은 사람에게는 보낼 길이 없다. 받는 쪽이 끊길 때 스스로 지운다.
			if (canSend.test(entry.getKey())) {
				deliveries.add(new Delivery(entry.getKey(), TrialTimersPayload.HIDDEN));
			}
		}
		return deliveries;
	}

	/**
	 * {@code DragonTrialManager.publishTimers} 의 <b>결정부</b> — 세션들에서 「누가 어느 묶음을 받나」를
	 * 고른다. 사람 객체를 모르므로 시험이 문자열이든 무엇이든 넣어 굴린다.
	 *
	 * <p>2026-10-06 Orca 검토 F-verified 의 V10 으로 떼어 냈다. 거르는 것은 셋이다.
	 *
	 * <ul>
	 *   <li><b>시련을 끈 팀</b> — 바닐라 드래곤전이라 띄울 것이 없다. 팀을 찾기도 전에 거른다</li>
	 *   <li><b>팀이 없어진 세션</b>({@code membersInEnd} 가 {@code null}) — 그 틱에 세션 루프가 닫는다</li>
	 *   <li><b>엔드에 선 팀원이 없는 팀</b> — 묶음을 만들지도 않는다</li>
	 * </ul>
	 *
	 * <p>⚠ 「엔드에 서 있는가」는 여기서 가르지 않는다 — {@code membersInEnd} 가 돌려주는 목록이 이미
	 * 그것이어야 한다({@code DragonTrialManager.membersOf}). 오버월드 원점에 선 사람이 엔드 HUD 를
	 * 받지 않게 하는 자리가 그 한 곳이다.
	 *
	 * @param membersInEnd 팀 id → 엔드에 서 있는 팀원. 팀이 없어졌으면 {@code null}
	 * @param payloadOf    세션 → 그 팀의 묶음({@link #collect}). 받을 사람이 있는 팀에만 불린다
	 */
	static <P> Map<P, TrialTimersPayload> audienceOf(
			java.util.Collection<DragonTrialSession> sessions,
			java.util.function.Function<UUID, List<P>> membersInEnd,
			java.util.function.Function<DragonTrialSession, TrialTimersPayload> payloadOf) {
		Map<P, TrialTimersPayload> audience = new HashMap<>();
		for (DragonTrialSession session : sessions) {
			if (!session.trialsEnabled()) {
				continue;
			}
			List<P> members = membersInEnd.apply(session.teamId());
			if (members == null || members.isEmpty()) {
				continue;
			}
			TrialTimersPayload payload = payloadOf.apply(session);
			for (P member : members) {
				audience.put(member, payload);
			}
		}
		return audience;
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 보낼 연결이 없을 수 있으므로 기록만 버린다. */
	public static void clearState() {
		SENT.clear();
	}

	/**
	 * 지금 판이 얼어 있는가 — 시련 화면이든 증강 선택이든 운영자의 {@code /tick freeze} 든.
	 *
	 * <p>바닐라 정지 상태를 함께 보는 것은 <b>어느 길로 얼었든 게임 시각이 서고</b>, 실행기의 시계가
	 * 그 게임 시각으로 돌기 때문이다.
	 */
	public static boolean frozen(@Nullable MinecraftServer server) {
		return TrialFreeze.isActive()
				|| (server != null && server.tickRateManager().isFrozen());
	}

	/**
	 * 지난번 보낸 것과 견주어 지금 다시 보내야 하는가. <b>월드를 모른다 — 시험이 직접 굴린다.</b>
	 *
	 * <p>예측은 {@code 지난 남은 틱 − (지금 게임 시각 − 그때 게임 시각)} 이다. 얼어 있으면 게임 시각이
	 * 안 오르므로 예측도 저절로 멈춘다 — 받는 쪽이 {@code frozen} 을 보고 멈추는 것과 같은 셈이다.
	 */
	static boolean needsSend(TrialTimersPayload last, long lastGameTime, int lastServerTick,
			TrialTimersPayload now, long gameTime, int serverTick) {
		if (serverTick - lastServerTick >= HEARTBEAT_TICKS) {
			return true;
		}
		if (last.visible() != now.visible() || last.frozen() != now.frozen()) {
			return true;
		}
		long passed = Math.max(0L, gameTime - lastGameTime);
		if (!sameTimers(last.timers(), now.timers(), passed)) {
			return true;
		}
		return !sameCast(last.cast(), now.cast(), passed);
	}

	private static boolean sameTimers(List<Entry> before, List<Entry> after, long passed) {
		if (before.size() != after.size()) {
			return false;
		}
		Map<String, Entry> byId = new HashMap<>();
		for (Entry entry : before) {
			byId.put(entry.id(), entry);
		}
		for (Entry entry : after) {
			Entry old = byId.get(entry.id());
			if (old == null || old.mode() != entry.mode() || old.kind() != entry.kind()
					|| old.totalTicks() != entry.totalTicks() || old.warnTicks() != entry.warnTicks()
					|| !old.label().equals(entry.label())
					|| !old.valueText().equals(entry.valueText())) {
				return false;
			}
			// 체력형의 바는 여기서 보지 않는다 — 맞을 때마다 보내게 된다. 1초 박동이 실어 간다.
			if (entry.mode() != Entry.MODE_HEALTH
					&& drifted(old.remainingTicks(), entry.remainingTicks(), passed)) {
				return false;
			}
		}
		return true;
	}

	private static boolean sameCast(Optional<Cast> before, Optional<Cast> after, long passed) {
		if (before.isPresent() != after.isPresent()) {
			return false;
		}
		if (before.isEmpty()) {
			return true;
		}
		Cast old = before.get();
		Cast cast = after.get();
		return old.state() == cast.state() && old.kind() == cast.kind()
				&& old.totalTicks() == cast.totalTicks() && old.title().equals(cast.title())
				&& old.hint().equals(cast.hint())
				&& !drifted(old.remainingTicks(), cast.remainingTicks(), passed);
	}

	private static boolean drifted(int before, int after, long passed) {
		long predicted = Math.max(0L, before - passed);
		return Math.abs(predicted - after) > DRIFT_TOLERANCE_TICKS;
	}

	// ------------------------------------------------------------------ 묶음 만들기

	/**
	 * 한 팀의 묶음. 세션과 실행기의 <b>정적 상태만</b> 읽는다 — 월드를 만지지 않는다.
	 *
	 * @param healthRatio 드래곤 체력 비율(0~1). 드래곤이 없으면 {@code NaN}
	 * @param now         지금 게임 시각. <b>그 틱의 실행기가 다 돈 뒤</b>에 불려야 한다
	 */
	static TrialTimersPayload collect(@Nullable DragonTrialSession session, boolean frozen,
			float healthRatio, long now) {
		if (session == null || !session.trialsEnabled()) {
			// 시련을 끈 팀은 바닐라 드래곤전이다 — 패시브도 카드도 최후의 저항도 없다.
			return TrialTimersPayload.HIDDEN;
		}
		DragonLastStand.View stand = DragonLastStand.view(session.teamId(), now);
		if (stand != null) {
			// 최후의 저항이 열리면 카드와 패시브가 전부 멈춘다(DragonTrialManager.tickSessions 의
			// continue). 카드 줄을 남기면 멈춘 카드의 숫자가 계속 줄어드는 거짓 화면이 된다.
			return new TrialTimersPayload(true, frozen,
					lastStandTimers(DragonLastStandZone.timer(stand.clockBase(), now),
							DragonLastStandPatterns.lightningClock(stand.clockBase(), now),
							DragonLastStandObjects.view(stand.clockBase()), healthRatio),
					castOf(stand, now));
		}
		return new TrialTimersPayload(true, frozen, fightTimers(session, now), Optional.empty());
	}

	/** 일반 전투(최후의 저항 전)의 줄들 — 기본 패시브 + 걸려 있는 카드. */
	static List<Entry> fightTimers(DragonTrialSession session, long now) {
		List<Entry> entries = new ArrayList<>();
		Clock barrage = DragonFireBarrage.clock(session.startedTick(), now);
		if (barrage != null) {
			entries.add(entryOf(BARRAGE_ID, "연쇄 포격", Kind.DAMAGE, barrage));
		}
		for (String id : session.chosen()) {
			TrialCatalog.Trial trial = TrialCatalog.byId(id);
			if (trial == null) {
				continue;
			}
			long granted = session.grantedTick(id);
			List<TrialCatalog.Risk> risks = trial.risks();
			for (int index = 0; index < risks.size(); index++) {
				TrialCatalog.Risk risk = risks.get(index);
				Clock clock = clockOf(risk, granted, now);
				if (clock == null) {
					continue;
				}
				entries.add(entryOf(TrialRisks.riskKey(id, index), labelOf(trial, risk, clock),
						kindOf(risk), clock));
			}
		}
		return sorted(entries);
	}

	/**
	 * 위험 하나의 「다음 사건」. 시각이 정해지지 않았거나 한 번으로 끝나면 {@code null}.
	 *
	 * <p>{@code default} 없는 switch 식이다 — {@code TrialRisks.tick} 과 같은 규칙이다. 위험을 하나
	 * 더 만들고 여기에 갈래를 안 붙이면 컴파일이 거절한다. 「HUD 에 넣을까」를 정하지 않은 채
	 * 지나갈 수 없게 한다.
	 */
	static @Nullable Clock clockOf(TrialCatalog.Risk risk, long granted, long now) {
		return switch (risk) {
			case TrialCatalog.Risk.DelayedStrike strike -> TrialRisks.strikeClock(granted, now, strike);
			case TrialCatalog.Risk.TracedProjectile shot -> TrialFireball.clock(granted, now, shot);
			case TrialCatalog.Risk.DragonFocus focus -> TrialDragonFocus.clock(granted, now, focus);
			case TrialCatalog.Risk.EnderPulse pulse -> TrialEnderPulse.clock(granted, now, pulse);
			case TrialCatalog.Risk.CrystalLink link -> TrialCrystalLink.clock(granted, now, link);
			case TrialCatalog.Risk.CrystalOvercharge overcharge ->
					TrialCrystalOvercharge.clock(granted, now, overcharge);
			case TrialCatalog.Risk.EnderStorm storm -> TrialEnderStorm.clock(granted, now, storm);
			case TrialCatalog.Risk.NightHost host -> TrialNightHost.clock(granted, now, host);
			case TrialCatalog.Risk.EndRain rain -> TrialEndRain.clock(granted, now, rain);
			case TrialCatalog.Risk.HotbarLock lock -> TrialHotbarLock.clock(granted, now, lock);
			// 한 번으로 끝나는 카드. 줄을 띄우면 아무 일도 안 올 숫자가 남는다.
			case TrialCatalog.Risk.CrystalGuard ignored -> null;
			case TrialCatalog.Risk.CrystalRevive ignored -> null;
			case TrialCatalog.Risk.DryWorld ignored -> null;
			// 드래곤이 착지할 때 터진다. 착지는 바닐라 주사위라 시각이 정해지지 않는다.
			case TrialCatalog.Risk.LandingShock ignored -> null;
		};
	}

	/** 위험의 종류 — 곧 HUD 의 색. 바닥 표식의 색 규약({@link TrialWarning.Colors})과 뜻을 맞춘다. */
	static byte kindOf(TrialCatalog.Risk risk) {
		return switch (risk) {
			// 「낙뢰」의 바닥 표식이 노랑인 것과 같은 갈림이다(TrialRisks.markColor).
			case TrialCatalog.Risk.DelayedStrike strike -> switch (strike.impact()) {
				case EXPLOSION -> Kind.DAMAGE;
				case LIGHTNING -> Kind.LIGHTNING;
			};
			case TrialCatalog.Risk.TracedProjectile ignored -> Kind.DAMAGE;
			case TrialCatalog.Risk.DragonFocus ignored -> Kind.DAMAGE;
			case TrialCatalog.Risk.CrystalOvercharge ignored -> Kind.DAMAGE;
			case TrialCatalog.Risk.EndRain ignored -> Kind.DAMAGE;
			case TrialCatalog.Risk.EnderPulse ignored -> Kind.SHOVE;
			case TrialCatalog.Risk.EnderStorm ignored -> Kind.SHOVE;
			case TrialCatalog.Risk.LandingShock ignored -> Kind.SHOVE;
			case TrialCatalog.Risk.HotbarLock ignored -> Kind.DISRUPT;
			case TrialCatalog.Risk.CrystalLink ignored -> Kind.DISRUPT;
			case TrialCatalog.Risk.NightHost ignored -> Kind.DISRUPT;
			case TrialCatalog.Risk.CrystalGuard ignored -> Kind.DISRUPT;
			case TrialCatalog.Risk.DryWorld ignored -> Kind.DISRUPT;
			case TrialCatalog.Risk.CrystalRevive ignored -> Kind.HEAL;
		};
	}

	/**
	 * 줄 이름. 카드 이름이 기본이고, 카드 이름보다 <b>사건</b>을 말해야 읽히는 둘만 바꾼다.
	 *
	 * <ul>
	 *   <li>「수정 과충전」 — 도화선이 타는 동안은 「과충전까지」(사람 화면 그대로), 구체가 날아오는
	 *       동안은 카드 이름</li>
	 *   <li>「연결된 수정」 — 줄이 서는 것은 보호막이 서 있는 동안뿐이라 「수정 보호막」</li>
	 * </ul>
	 */
	static String labelOf(TrialCatalog.Trial trial, TrialCatalog.Risk risk, Clock clock) {
		if (risk instanceof TrialCatalog.Risk.CrystalOvercharge) {
			return clock.active() ? trial.name() : "과충전까지";
		}
		if (risk instanceof TrialCatalog.Risk.CrystalLink) {
			return "수정 보호막";
		}
		return trial.name();
	}

	// ------------------------------------------------------------------ 최후의 저항

	/**
	 * 최후의 저항의 오른쪽 위 줄들 — 안전지대 · 상시 번개 · 오브젝트 파도.
	 *
	 * @param healthRatio 드래곤 체력 비율. {@code NaN} 이면 오브젝트 파도 줄을 뺀다
	 */
	static List<Entry> lastStandTimers(@Nullable ZoneTimer zone, @Nullable Clock lightning,
			@Nullable ObjectsView objects, float healthRatio) {
		List<Entry> entries = new ArrayList<>();
		if (zone != null) {
			String radius = String.format(Locale.ROOT, "%d칸", Math.round(zone.toRadius()));
			if (zone.shrinking()) {
				entries.add(Entry.active(ZONE_ID, "안전지대 축소 중 → " + radius, Kind.ZONE,
						clampInt(zone.remaining()), clampInt(zone.total())));
			} else {
				entries.add(Entry.countdown(ZONE_ID, "안전지대 → " + radius, Kind.ZONE,
						clampInt(zone.remaining()), clampInt(zone.total()), 0));
			}
		}
		if (lightning != null) {
			entries.add(entryOf(LIGHTNING_ID, "상시 번개", Kind.LIGHTNING, lightning));
		}
		Entry wave = objectsEntry(objects, healthRatio);
		if (wave != null) {
			entries.add(wave);
		}
		return sorted(entries);
	}

	/**
	 * 오브젝트 파도 줄. 체력으로 정해지는 것이라 초 대신 「체력 25%」를 띄운다.
	 *
	 * <p>바({@link Entry#fill})는 <b>다음 문턱까지의 거리</b>다 — 1 이면 멀고 0 이면 문턱이다. 거리의
	 * 기준은 앞 문턱이고, 첫 문턱의 앞은 진입 직후 체력(진입 문턱 30% + 연출이 채우는 20%p)이다.
	 * 문턱을 다 쓰면(25 → 10 → 없음) 줄이 사라진다. 파도가 서 있는 동안은 「진행 중 · N개」와 남은
	 * 크리스탈 비율이다.
	 */
	static @Nullable Entry objectsEntry(@Nullable ObjectsView objects, float healthRatio) {
		if (objects == null) {
			return null;
		}
		if (objects.running()) {
			float fill = objects.slots() <= 0 ? 0.0F : (float) objects.live() / objects.slots();
			return Entry.health(OBJECTS_ID, "오브젝트 파도", Kind.HEAL,
					"진행 중 · " + objects.live() + "개", fill);
		}
		float[] thresholds = DragonLastStandObjects.THRESHOLDS;
		if (objects.firedWaves() >= thresholds.length || Float.isNaN(healthRatio)) {
			return null;
		}
		float next = thresholds[objects.firedWaves()];
		float upper = objects.firedWaves() == 0
				? DragonLastStand.ENTRY_HEALTH_RATIO + DragonLastStand.ENTRY_HEAL_FRACTION
				: thresholds[objects.firedWaves() - 1];
		float span = upper - next;
		float fill = span > 0.0F ? (healthRatio - next) / span : 0.0F;
		return Entry.health(OBJECTS_ID, "오브젝트 파도", Kind.HEAL,
				"체력 " + Math.round(next * 100.0F) + "%", fill);
	}

	/**
	 * 시전 바. <b>상태 셋 — 쉬는 중 · 예고 · 진행</b>. 값은 전부 {@link DragonLastStand} 가 실제로 든
	 * 시각(다음 패턴을 고를 시각 · 지금 패턴이 끝나는 시각)과 {@link DragonLastStandPatterns} 의 예고
	 * 상수에서 온다.
	 */
	static Optional<Cast> castOf(DragonLastStand.View stand, long now) {
		DragonLastStand.Pattern running = stand.running();
		if (running != null) {
			long startedAt = stand.runningUntil() - running.durationTicks();
			int warn = warnTicksOf(running);
			if (now - startedAt < warn) {
				return Optional.of(new Cast(nameOf(running), kindOf(running), Cast.STATE_WARNING,
						clampInt(startedAt + warn - now), warn, hintOf(running)));
			}
			return Optional.of(new Cast(nameOf(running) + RUNNING_SUFFIX, kindOf(running),
					Cast.STATE_RUNNING, clampInt(stand.runningUntil() - now),
					running.durationTicks() - warn, hintOf(running)));
		}
		long remaining = Math.max(0L, stand.nextPatternAt() - now);
		if (stand.shielded()) {
			// 진입 연출 + 무적 3초. 보호막은 첫 패턴을 고르는 틱에 깨진다(DragonLastStandShield).
			return Optional.of(new Cast(SHIELD_TITLE, Kind.NEUTRAL, Cast.STATE_IDLE,
					clampInt(remaining), clampInt(stand.nextPatternAt() - stand.beganAt()), ""));
		}
		return Optional.of(new Cast(REST_TITLE, Kind.NEUTRAL, Cast.STATE_IDLE, clampInt(remaining),
				clampInt(stand.nextPatternAt() - stand.restBegan()), ""));
	}

	/**
	 * 패턴의 예고 길이 — 고른 틱부터 「발동」까지. {@link DragonLastStandPatterns} 의 상수 그대로다.
	 *
	 * <p>흡입의 「발동」은 빨아들이기가 시작되는 틱이다(터지는 것은 그 5초 뒤). 사람 화면이 「예고 →
	 * 발동 → 진행 남은 시간」이고, 빨아들이기부터가 이미 「달려야 하는」 때라서다.
	 */
	static int warnTicksOf(DragonLastStand.Pattern pattern) {
		return switch (pattern) {
			case WING_BEAT -> 0;
			case CONE_BREATH -> DragonLastStandPatterns.CONE_WARN_TICKS;
			case VOID_SUCTION -> DragonLastStandPatterns.SUCK_WARN_TICKS;
			case CROSS_FISSURE -> DragonLastStandPatterns.CROSS_FIRST_WARN_TICKS;
		};
	}

	/** 패턴 이름. */
	static String nameOf(DragonLastStand.Pattern pattern) {
		return switch (pattern) {
			case WING_BEAT -> "날개 퍼덕이기";
			case CONE_BREATH -> "부채꼴 브레스";
			case VOID_SUCTION -> "공허 흡입";
			case CROSS_FISSURE -> "십자 균열";
		};
	}

	/** 패턴의 종류 — 곧 색. */
	static byte kindOf(DragonLastStand.Pattern pattern) {
		return switch (pattern) {
			case WING_BEAT -> Kind.SHOVE;
			case CONE_BREATH -> Kind.DAMAGE;
			case VOID_SUCTION -> Kind.DISRUPT;
			case CROSS_FISSURE -> Kind.DAMAGE;
		};
	}

	/** 시전 바 아래 대처법 한 줄. 문구는 승인된 화면 그대로다. */
	static String hintOf(DragonLastStand.Pattern pattern) {
		return switch (pattern) {
			case WING_BEAT -> "0.6초마다 넉백 8번 · 피해 없음";
			case CONE_BREATH -> "머리 방향 90° · 한 대에 전멸 — 옆으로";
			case VOID_SUCTION -> "반대로 달리기 · 불 결계 안은 계속 아픔";
			case CROSS_FISSURE -> "빨간 바닥 사이 틈으로 · 두 번째 십자 대비";
		};
	}

	// ------------------------------------------------------------------ 작은 도구

	private static Entry entryOf(String id, String label, byte kind, Clock clock) {
		if (clock.active()) {
			return Entry.active(id, label, kind, clock.remaining(), clock.total());
		}
		return Entry.countdown(id, label, kind, clock.remaining(), clock.total(), clock.warn());
	}

	/**
	 * 남은 시간이 짧은 줄이 위 — 사람 화면 그대로. 체력형은 맨 아래다(초로 견줄 수 없다).
	 *
	 * <p>⚠ 받는 쪽도 줄여 가며 다시 정렬해야 한다. 여기 순서는 보낸 그 틱의 순서일 뿐이다.
	 */
	static List<Entry> sorted(List<Entry> entries) {
		List<Entry> ordered = new ArrayList<>(entries);
		ordered.sort(Comparator
				.comparingInt((Entry entry) -> entry.mode() == Entry.MODE_HEALTH ? 1 : 0)
				.thenComparingInt(Entry::remainingTicks)
				.thenComparing(Entry::id));
		if (ordered.size() > TrialTimersPayload.MAX_TIMERS) {
			return new ArrayList<>(ordered.subList(0, TrialTimersPayload.MAX_TIMERS));
		}
		return ordered;
	}

	private static int clampInt(long value) {
		return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
	}
}
