package com.sharedfate.sync;

import com.sharedfate.sync.TrialCatalog.Trigger;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 드래곤 전투 한 판의 상태. 무엇이 터졌고 무엇을 골랐는지만 들고 있다.
 *
 * <h2>시간이 아니라 진행도로 센다</h2>
 *
 * <p>시련은 전투가 <b>실제로 진행돼야</b> 나온다 — 엔드 입장, 첫 크리스탈, 크리스탈 전멸,
 * 체력 80·50·30%. 시계를 보지 않아도 체력 막대만 보면 다음 시련이 언제 올지 알 수 있고,
 * 「반쯤 깎았더니 판이 바뀐다」가 시간이 흐른 것보다 훨씬 읽힌다.
 *
 * <p>덕분에 <b>빨리 깎을수록 위험해진다.</b> 화력이 센 팀이 더 많은 시련을 더 빨리 받는다.
 *
 * <h2>한 번만 센다</h2>
 *
 * <p>드래곤은 크리스탈로 회복하므로 체력이 50%를 오르내릴 수 있다. 처음 내려간 한 번만 세지
 * 않으면 후반에 같은 자리가 계속 터진다.
 *
 * <h2>줄을 세운다</h2>
 *
 * <p>화력이 센 팀은 80%에서 30%까지 한 번에 관통할 수 있다. 그때 셋을 한꺼번에 띄우지 않고
 * 차례로 낸다 — 앞 선택을 끝내야 다음이 뜬다. 빨리 깎은 대가는 <b>치르게</b> 하되, 전투
 * 중에 선택창이 겹치지는 않게 한다.
 *
 * <h2>카드마다 받은 틱을 센다</h2>
 *
 * <p>위험 효과의 주기를 월드 시간({@code now % interval})으로 세면 두 가지가 깨진다.
 *
 * <ol>
 * <li>카드를 받은 순간이 마침 발동 위상이면 <b>예고를 하나도 못 본 채 바로 맞는다</b>
 * <li>주기가 같은 카드 둘을 쌓으면 <b>영원히 같은 틱에 함께 터진다</b>
 * </ol>
 *
 * <p>시련이 전투가 끝날 때까지 쌓이는 것이 이 기능의 전부라 그냥 둘 수 없다. 카드마다 받은 틱을
 * 적어 두고 {@code (now - 받은틱) % interval} 로 세면 카드마다 위상이 저절로 어긋나고, 받은
 * 직후 한 주기는 온전히 예고에 쓰인다.
 *
 * <h2>무엇을 모르는가</h2>
 *
 * <p>시련이 무슨 효과인지 모른다. 드래곤도 월드도 모른다. 그래서 월드 없이 시험할 수 있다.
 */
public final class DragonTrialSession {
	private final UUID teamId;
	/** 전투가 시작된 게임 시각. 로그에 전투 길이를 남길 때 쓴다. */
	private final long startedTick;

	/** 이미 터진 자리. 같은 자리는 다시 세지 않는다. */
	private final Set<Trigger> fired = EnumSet.noneOf(Trigger.class);
	/** 터졌지만 아직 고르지 않은 자리들. 앞에서부터 하나씩 낸다. */
	private final Deque<Trigger> queued = new ArrayDeque<>();
	/** 고른 순서대로 쌓인다. 전투 중에는 풀리지 않는다. */
	private final List<String> chosen = new ArrayList<>();
	/** 카드마다 받은 틱. 고른 적 없는 카드는 여기에 없다 — {@code chosen} 과 어긋나면 안 된다. */
	private final Map<String, Long> grantedTicks = new LinkedHashMap<>();
	/** 지금 선택을 기다리는 중인가. 기다리는 동안 다음 자리는 줄에서 기다린다. */
	private boolean awaitingChoice;

	public DragonTrialSession(UUID teamId, long startedTick) {
		this.teamId = teamId;
		this.startedTick = startedTick;
	}

	public UUID teamId() {
		return teamId;
	}

	public long startedTick() {
		return startedTick;
	}

	public List<String> chosen() {
		return Collections.unmodifiableList(chosen);
	}

	public int trialCount() {
		return chosen.size();
	}

	public boolean awaitingChoice() {
		return awaitingChoice;
	}

	public Set<Trigger> fired() {
		return Collections.unmodifiableSet(fired);
	}

	public int queuedCount() {
		return queued.size();
	}

	/** 전투가 얼마나 이어졌는가. */
	public long elapsedTicks(long now) {
		return Math.max(0L, now - startedTick);
	}

	/**
	 * 자리 하나가 터졌다.
	 *
	 * @return 처음 터진 것이면 참. 이미 센 자리면 거짓이고 아무 일도 없다
	 */
	public boolean fire(@Nullable Trigger trigger) {
		if (trigger == null || !fired.add(trigger)) {
			return false;
		}
		queued.addLast(trigger);
		return true;
	}

	/** 지금 선택창을 띄울 때인가. */
	public boolean shouldOfferTrial() {
		return !awaitingChoice && !queued.isEmpty();
	}

	/** 줄 맨 앞의 자리. 없으면 {@code null}. */
	public @Nullable Trigger peekTrigger() {
		return queued.peekFirst();
	}

	/** 선택을 기다리기 시작한다. 줄 맨 앞을 꺼내 든다. */
	public @Nullable Trigger beginChoice() {
		Trigger trigger = queued.pollFirst();
		if (trigger != null) {
			awaitingChoice = true;
		}
		return trigger;
	}

	/**
	 * 고른 시련을 쌓는다.
	 *
	 * <p>{@code tick} 을 함께 받는 이유는 위쪽 「카드마다 받은 틱을 센다」와 같다. 틱 없이 부르는
	 * 길을 남기면 그 길로 들어온 카드만 위상이 월드 시간과 같아져 고치려던 문제로 되돌아가므로,
	 * 1인자 오버로드는 일부러 두지 않았다.
	 *
	 * @param tick 지금 게임 시각. 이 카드의 위험 주기를 여기서부터 센다
	 * @return 실제로 쌓였으면 참. 이미 고른 것이면 거짓
	 */
	public boolean choose(@Nullable String trialId, long tick) {
		awaitingChoice = false;
		if (trialId == null || trialId.isBlank() || chosen.contains(trialId)) {
			// 쌓이지 못한 카드의 틱은 남기지 않는다. 남으면 chosen 과 어긋난다.
			return false;
		}
		chosen.add(trialId);
		grantedTicks.put(trialId, tick);
		return true;
	}

	/**
	 * 카드를 받은 시각. 위험의 주기를 여기서부터 센다.
	 *
	 * <p>기록이 없으면 <b>{@code startedTick}</b> 이다. 0 을 돌려주면 위상이 월드 시간과 같아져
	 * 고치려던 문제로 되돌아간다 — 옛 저장 파일을 읽었을 때도 전투 시작이 기준이어야 한다.
	 */
	public long grantedTick(@Nullable String trialId) {
		if (trialId == null) {
			return startedTick;
		}
		return grantedTicks.getOrDefault(trialId, startedTick);
	}

	/** 저장에 쓰는 값. 읽기 전용 사본이라 밖에서 고쳐도 세션은 흔들리지 않는다. */
	public Map<String, Long> grantedTicks() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(grantedTicks));
	}

	/** 줄 수 있는 카드가 없어 그냥 지나간다. 자리는 이미 센 것으로 남는다. */
	public void skipChoice() {
		awaitingChoice = false;
	}

	/**
	 * 저장에서 되살릴 때. 서버가 재시작해도 터진 자리와 누적이 이어져야 한다.
	 *
	 * <p>받은 틱도 함께 이어받는다. 재시작마다 위상이 초기화되면 예고 없이 맞는 일이 재시작할
	 * 때마다 되돌아온다. {@code grantedTicks} 가 {@code null} 이면 — 이 칸이 없던 옛 파일이다 —
	 * 빈 것으로 다루고, 그때는 {@link #grantedTick} 이 {@code startedTick} 을 돌려준다.
	 */
	public void restore(@Nullable Collection<String> alreadyChosen,
			@Nullable Collection<String> alreadyFired,
			@Nullable Collection<String> stillQueued, boolean waiting,
			@Nullable Map<String, Long> grantedTicks) {
		chosen.clear();
		if (alreadyChosen != null) {
			for (String id : alreadyChosen) {
				if (id != null && !id.isBlank() && !chosen.contains(id)) {
					chosen.add(id);
				}
			}
		}
		this.grantedTicks.clear();
		if (grantedTicks != null) {
			for (Map.Entry<String, Long> mark : grantedTicks.entrySet()) {
				String id = mark.getKey();
				Long tick = mark.getValue();
				// 고른 적 없는 카드의 틱은 버린다. 저장이 어긋나도 chosen 과 엇갈리지 않는다.
				if (id != null && tick != null && chosen.contains(id)) {
					this.grantedTicks.put(id, tick);
				}
			}
		}
		fired.clear();
		if (alreadyFired != null) {
			for (String name : alreadyFired) {
				Trigger trigger = parse(name);
				if (trigger != null) {
					fired.add(trigger);
				}
			}
		}
		queued.clear();
		if (stillQueued != null) {
			for (String name : stillQueued) {
				Trigger trigger = parse(name);
				// 줄에 있으려면 터진 적이 있어야 한다. 저장이 어긋나도 유령 선택창을 띄우지 않는다.
				if (trigger != null && fired.contains(trigger) && !queued.contains(trigger)) {
					queued.addLast(trigger);
				}
			}
		}
		awaitingChoice = waiting;
	}

	/** 저장에 쓰는 값. */
	public List<String> firedNames() {
		List<String> names = new ArrayList<>();
		for (Trigger trigger : fired) {
			names.add(trigger.name());
		}
		return names;
	}

	/** 저장에 쓰는 값. 줄에 남은 순서를 그대로 적는다. */
	public List<String> queuedNames() {
		List<String> names = new ArrayList<>();
		for (Trigger trigger : queued) {
			names.add(trigger.name());
		}
		return names;
	}

	private static @Nullable Trigger parse(@Nullable String name) {
		if (name == null) {
			return null;
		}
		try {
			return Trigger.valueOf(name);
		} catch (IllegalArgumentException unknown) {
			// 옛 저장 파일에 없어진 트리거가 들어 있을 수 있다. 서버를 막지 않는다.
			return null;
		}
	}
}
