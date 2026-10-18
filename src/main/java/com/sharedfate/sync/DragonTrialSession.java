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
 * <h2>시련을 끈 팀도 세션을 연다</h2>
 *
 * <p>팀 설정에서 시련을 끄면({@link #trialsEnabled()} 이 거짓) 이 세션은 <b>아무 자리도 세지
 * 않고 아무 카드도 받지 않는다.</b> 그래도 세션 자체는 열린다 — 엔드에 들어섰다는 사실과 전투
 * 시각은 여전히 있어야 하고, 이것이 없으면 {@code DragonTrialManager.detectArrival} 이 매 틱
 * 「아직 안 열렸다」로 읽어 팀을 계속 다시 부른다.
 *
 * <p><b>켰는가를 생성자로 받는 것이 이 설계의 핵심이다.</b> 나중에 켜고 끌 수 있게 두면
 * 「전투 도중에 바뀌면 이미 받은 카드는 어떻게 되는가」라는 답 없는 물음이 생기고, 무엇보다
 * 세션을 만드는 사람이 <b>정하지 않고 지나갈 수 있게</b> 된다. 그래서 값 없이는 만들 수 없다.
 *
 * <h2>무엇을 모르는가</h2>
 *
 * <p>시련이 무슨 효과인지 모른다. 드래곤도 월드도 모른다. 그래서 월드 없이 시험할 수 있다.
 */
public final class DragonTrialSession {
	private final UUID teamId;
	/** 전투가 시작된 게임 시각. 로그에 전투 길이를 남길 때 쓴다. */
	private final long startedTick;
	/**
	 * 이 팀이 시련을 쓰기로 했는가({@code TeamState.dragonTrialsEnabled}).
	 *
	 * <p>거짓이면 {@link #fire}·{@link #choose} 가 <b>언제나 거짓을 돌려주고 아무것도 쌓지
	 * 않는다.</b> 자리를 세는 길과 카드를 쌓는 길이 이 둘뿐이므로, 여기서 막으면 룰렛도 고정
	 * 시련도 시험 명령({@code /shareteam trialtest give|fire})도 함께 막힌다 — 켜고 끄기를
	 * 부르는 쪽마다 적어 두면 언젠가 한 길만 빠진다.
	 */
	private final boolean trialsEnabled;

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

	// ------------------------------------------------------------------ 일시 중단 (2026-10-18)

	/**
	 * 입장 연출({@code TrialEntrance})의 원점. 처음에는 {@link #startedTick} 과 같다.
	 *
	 * <p>연출 도중에 전투가 멈췄다가 이어지면 <b>이 칸만</b> 이은 틱으로 옮겨 연출을 처음부터 다시
	 * 돌린다 — 크리스탈을 거둔 채 멈추면 되살리는 단계가 영영 안 와 크리스탈이 0개로 남는다.
	 * {@link #startedTick} 을 옮기지 않는 까닭은 그것이 패시브(연쇄 포격)·카드 위상의 원점이라서다 —
	 * 그쪽은 멈춘 동안에도 위상을 그대로 잇는다. 저장된다.
	 */
	private long entranceTick;
	/**
	 * 전투가 <b>일시 중단</b>되었는가 — 엔드에 팀원이 아무도 없어 드래곤이 메모리에서 내려갔다
	 * ({@code DragonTrialSuspend}). 중단된 세션은 실행기를 하나도 돌리지 않고 자리도 세지 않는다.
	 * 저장된다.
	 */
	private boolean suspended;
	/** 이을 때 입장 연출을 처음부터 다시 돌리는가. 중단될 때 정해진다. 저장된다. */
	private boolean replayEntrance;
	/**
	 * 중단될 때의 드래곤 <b>실제</b> 체력. 모르면 {@link Float#NaN}. 싸우는 동안은 언제나 NaN 이다 —
	 * 싸우는 도중의 값을 적어 두면 강제 종료 뒤에 <b>낡은 체력</b>으로 되돌려 드래곤이 회복한다.
	 * 저장된다.
	 */
	private float dragonHealth = Float.NaN;
	/**
	 * 최후의 저항에서 <b>끝까지 치른</b> 오브젝트 파도 수(0·1·2). 이을 때 이만큼은 다시 주지 않는다 —
	 * 도는 중이던 파도는 개체가 걷혔으므로 셈에 넣지 않아 다시 온다. 저장된다.
	 */
	private int lastStandWaves;
	/**
	 * 멈춘 틱. 모르면 {@link Long#MIN_VALUE}(싸우는 중 · 옛 저장). 이을 때 카드의 받은 틱을 이만큼 뒤로 민다
	 * ({@link DragonTrialSuspend#resumedGrant}) — 엔드의 게임 시각은 아무도 없어도 흐르므로, 밀지 않으면 창 있는
	 * 카드를 끊기로 건너뛴다(2026-10-18 검토). 저장된다.
	 */
	private long pausedAt = Long.MIN_VALUE;
	/**
	 * 최후의 저항 지대(보더) 시계가 멈출 때 어디까지 왔는가({@code now - clockBase}). 모르면
	 * {@link Long#MIN_VALUE}. 이을 때 보더를 그 단계·그 시간표에서 잇는다(사람 결정 「이어 가기, 무적 없음」,
	 * {@link DragonTrialSuspend#resumedZoneClock}). 저장된다.
	 */
	private long lastStandClock = Long.MIN_VALUE;
	/**
	 * 한 번짜리 카드가 <b>이미 한 일</b> — 위험 열쇠({@code 카드 id#순번}) → 값. 부활 · 메마른 세계 · 다시 선
	 * 쇠창살은 1(했다), 밤의 군세는 멈출 때 살아 있던 엔더맨 수다({@code TrialRisks.snapshotDone}).
	 *
	 * <p>멈출 때 실행기의 기억을 걷으므로(시련이 판에 건 것을 되돌리는 같은 길) 이것이 없으면 재개 첫 틱에 부활이
	 * 크리스탈을 또 살리고 메마른 세계가 또 증발시켰다. 서버 재시작도 같았다. 이을 때 실행기에 다시 심는다
	 * ({@code TrialRisks.restoreDone}). <b>줄지 않는다</b> — 한 번 한 일은 그 전투 동안 한 일이다. 저장된다.
	 */
	private final Map<String, Integer> riskDone = new LinkedHashMap<>();

	/**
	 * @param trialsEnabled 이 팀이 시련을 쓰기로 했는가. <b>기본값이 없다</b> — 부르는 쪽이
	 *                      반드시 정해야 한다. 까닭은 클래스 문서에 적어 뒀다
	 */
	public DragonTrialSession(UUID teamId, long startedTick, boolean trialsEnabled) {
		this.teamId = teamId;
		this.startedTick = startedTick;
		this.trialsEnabled = trialsEnabled;
		this.entranceTick = startedTick;
	}

	public UUID teamId() {
		return teamId;
	}

	/** 이 팀이 시련을 쓰는가. 거짓이면 바닐라 드래곤전이다. */
	public boolean trialsEnabled() {
		return trialsEnabled;
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
	 * <p>시련을 끈 팀에서는 <b>아무 자리도 세지 않는다.</b> 세어 두면 나중에 무엇을 쌓을지
	 * 모르는 자리가 줄에 남고, 그 줄이 곧 「룰렛을 띄울 때」의 근거다.
	 *
	 * @return 처음 터진 것이면 참. 이미 센 자리이거나 시련을 끈 팀이면 거짓이고 아무 일도 없다
	 */
	public boolean fire(@Nullable Trigger trigger) {
		if (!trialsEnabled || trigger == null || !fired.add(trigger)) {
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
	 * <p>시련을 끈 팀에서는 <b>무엇을 넘겨도 쌓이지 않는다.</b> 자리가 세지지 않으니 정상
	 * 경로로는 여기까지 오지 않지만, 시험 명령({@code /shareteam trialtest give})은 자리를
	 * 거치지 않고 곧장 들어온다 — 그 길도 여기서 함께 막힌다.
	 *
	 * @param tick 지금 게임 시각. 이 카드의 위험 주기를 여기서부터 센다
	 * @return 실제로 쌓였으면 참. 이미 고른 것이거나 시련을 끈 팀이면 거짓
	 */
	public boolean choose(@Nullable String trialId, long tick) {
		awaitingChoice = false;
		if (!trialsEnabled || trialId == null || trialId.isBlank() || chosen.contains(trialId)) {
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
		this.grantedTicks.clear();
		fired.clear();
		queued.clear();
		awaitingChoice = false;
		// 카드를 비우면(시험 명령 clear) 그 카드들이 한 일도 버린다. 같은 카드를 다시 받으면 새 카드다.
		riskDone.clear();
		if (!trialsEnabled) {
			// 시련을 끈 팀은 무엇이 적혀 있었든 한 장도 되살리지 않는다. 설정을 끄기 전에
			// 쌓아 둔 저장이 남아 있을 수 있는데, 그것을 읽어 들이면 「꺼 놓았는데 카드가
			// 걸려 있는」 팀이 생기고 그 상태는 화면 어디에도 설명되지 않는다.
			return;
		}
		if (alreadyChosen != null) {
			for (String id : alreadyChosen) {
				if (id != null && !id.isBlank() && !chosen.contains(id)) {
					chosen.add(id);
				}
			}
		}
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
		if (alreadyFired != null) {
			for (String name : alreadyFired) {
				Trigger trigger = parse(name);
				if (trigger != null) {
					fired.add(trigger);
				}
			}
		}
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

	// ------------------------------------------------------------------ 일시 중단

	/** 입장 연출의 원점. 까닭은 칸 설명에 있다. */
	public long entranceTick() {
		return entranceTick;
	}

	/** 전투가 일시 중단되었는가. */
	public boolean suspended() {
		return suspended;
	}

	/** 이을 때 입장 연출을 처음부터 다시 돌리는가. */
	public boolean replayEntrance() {
		return replayEntrance;
	}

	/** 중단될 때의 드래곤 실제 체력. 모르면 NaN. */
	public float dragonHealth() {
		return dragonHealth;
	}

	/** 최후의 저항에서 끝까지 치른 오브젝트 파도 수. */
	public int lastStandWaves() {
		return lastStandWaves;
	}

	/**
	 * 끝까지 치른 파도 수를 <b>그때그때</b> 적는다 — {@code DragonLastStand.tick} 이 매 틱 부른다(2026-10-18 밤).
	 * 늘 때만 바뀐다(치른 파도는 되돌아가지 않는다).
	 *
	 * <p>전에는 {@link #suspend} 만 이 칸을 적어, 서버가 강제로 죽으면 저장 파일에 그 전 값이 남아 다시 켠 뒤
	 * 이미 부순 회복 크리스탈 소환을 한 번 더 받았다.
	 *
	 * @return 바뀌었으면 참 — 부르는 쪽이 저장한다
	 */
	public boolean recordLastStandWaves(int waves) {
		if (waves <= lastStandWaves) {
			return false;
		}
		lastStandWaves = waves;
		return true;
	}

	/**
	 * 전투를 멈춘다. <b>뽑힌 카드·터진 자리·줄은 그대로 둔다</b> — 그것이 멈추는 까닭이다.
	 *
	 * @param replay       이을 때 입장 연출을 처음부터 다시 돌리는가
	 * @param health       드래곤 실제 체력. 모르면 NaN — 앞서 적어 둔 값이 있으면 그것을 지킨다
	 *                     (재시작 뒤 첫 틱처럼 드래곤을 못 본 채 다시 멈추는 길)
	 * @param waves        최후의 저항에서 끝까지 치른 파도 수. 음수면 앞 값을 지킨다
	 */
	public void suspend(boolean replay, float health, int waves) {
		suspend(replay, health, waves, Long.MIN_VALUE);
	}

	/**
	 * 전투를 멈춘다 — 멈춘 틱을 함께 적는다. 이을 때 카드의 받은 틱을 이만큼 뒤로 민다.
	 *
	 * @param now 멈추는 틱. 이미 멈춰 있으면 앞서 적은 틱을 지킨다
	 */
	public void suspend(boolean replay, float health, int waves, long now) {
		if (!suspended && now != Long.MIN_VALUE) {
			pausedAt = now;
		}
		suspended = true;
		replayEntrance = replayEntrance || replay;
		if (health > 0.0F) {
			dragonHealth = health;
		}
		if (waves >= 0) {
			lastStandWaves = waves;
		}
	}

	/**
	 * 전투를 잇는다. 입장 연출을 다시 돌려야 하면 그 원점을 {@code now} 로 옮긴다.
	 *
	 * <p>적어 둔 체력은 여기서 버린다 — 부르는 쪽이 이미 드래곤에 되돌려 놓은 뒤다. 남겨 두면 다음
	 * 강제 종료 뒤에 그 낡은 값으로 한 번 더 되돌린다.
	 *
	 * @return 입장 연출을 처음부터 다시 돌리는가
	 */
	public boolean resume(long now) {
		boolean replay = replayEntrance;
		if (replay) {
			entranceTick = now;
		}
		// 카드의 받은 틱을 멈춰 있던 만큼 민다 — 창 있는 카드는 멈춘 자리에서 잇고 주기 카드는 위상만 바뀐다.
		if (pausedAt != Long.MIN_VALUE) {
			for (Map.Entry<String, Long> mark : grantedTicks.entrySet()) {
				mark.setValue(DragonTrialSuspend.resumedGrant(mark.getValue(), pausedAt, now));
			}
		}
		pausedAt = Long.MIN_VALUE;
		suspended = false;
		replayEntrance = false;
		dragonHealth = Float.NaN;
		return replay;
	}

	/**
	 * 저장에서 중단 상태를 되살린다. {@link #restore} 다음에 부른다.
	 *
	 * @param entrance 입장 연출의 원점. {@code null} 이면(이 칸이 없던 옛 파일) {@link #startedTick}
	 * @param health   중단될 때의 체력. {@code null} 이면 모른다
	 */
	public void restoreSuspension(boolean wasSuspended, @Nullable Long entrance, boolean replay,
			@Nullable Float health, int waves) {
		entranceTick = entrance == null ? startedTick : entrance;
		suspended = wasSuspended;
		replayEntrance = wasSuspended && replay;
		dragonHealth = wasSuspended && health != null && health > 0.0F ? health : Float.NaN;
		lastStandWaves = Math.max(0, waves);
	}

	/**
	 * 저장에서 멈춘 틱 · 지대 시계 · 한 번짜리 카드가 한 일을 되살린다. {@link #restore} 와
	 * {@link #restoreSuspension} 다음에 부른다. {@code null} 은 모름(이 칸이 없던 옛 파일)이다.
	 */
	public void restorePause(@Nullable Long paused, @Nullable Long standClock,
			@Nullable Map<String, Integer> done) {
		pausedAt = suspended && paused != null ? paused : Long.MIN_VALUE;
		lastStandClock = standClock == null ? Long.MIN_VALUE : standClock;
		riskDone.clear();
		if (done != null) {
			rememberDone(done);
		}
	}

	/** 멈춘 틱. 모르거나 싸우는 중이면 {@link Long#MIN_VALUE}. */
	public long pausedAt() {
		return pausedAt;
	}

	/** 최후의 저항 지대 시계가 멈출 때 어디까지 왔는가. 모르면 {@link Long#MIN_VALUE}. */
	public long lastStandClock() {
		return lastStandClock;
	}

	/** 최후의 저항이 멈출 때 지대 시계의 경과를 적는다. */
	public void keepLastStandClock(long elapsed) {
		lastStandClock = elapsed;
	}

	/** 한 번짜리 카드가 이미 한 일. 읽기 전용 사본이다. */
	public Map<String, Integer> riskDone() {
		return Collections.unmodifiableMap(new LinkedHashMap<>(riskDone));
	}

	/**
	 * 한 번짜리 카드가 한 일을 더한다 — <b>덮어 넣기만 하고 지우지 않는다.</b> 재시작 첫 틱처럼 실행기 기억이 빈
	 * 채로 다시 멈추는 길이 앞서 적은 것을 지우지 않게.
	 */
	public void rememberDone(@Nullable Map<String, Integer> done) {
		if (done == null) {
			return;
		}
		for (Map.Entry<String, Integer> mark : done.entrySet()) {
			if (mark.getKey() != null && !mark.getKey().isBlank() && mark.getValue() != null) {
				riskDone.put(mark.getKey(), mark.getValue());
			}
		}
	}

	/** 이 자리가 아직 줄에 남아 있는가(터졌고 아직 카드를 쌓거나 지나가지 않았다). */
	public boolean pending(@Nullable Trigger trigger) {
		return trigger != null && queued.contains(trigger);
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
