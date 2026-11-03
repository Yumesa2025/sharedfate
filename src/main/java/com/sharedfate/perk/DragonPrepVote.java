package com.sharedfate.perk;

import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 「드래곤전 대비」 증강(45·50렙) 팀 투표 한 판(2026-11-04).
 *
 * <p>사람 말 — 「리롤 선택권은 없애고, 일반 증강처럼 랜덤으로 고르는 플레이어 뽑지 마. … 그 2개에서 1분간
 * 고르게 하고 모든 플레이어가 증강을 선택하는 거지. 1분이 지나버리면 제일 많이 고른 증강이 하나 선택되는
 * 거고, 모든 플레이어가 선택이 끝났을 때 시간이 5초가 되어 카운트다운 시작되고 과반수로 가게. … 동점이
 * 나올 경우 룰렛이 돌아가고 랜덤으로 그 2개 중 하나 골라.」
 *
 * <p>일반 증강은 선택자 한 사람이 고르고 남은 사람의 표는 <b>제안일 뿐</b>이다({@code PerkChoiceSession}
 * 의 {@code votes}). 여기는 반대로 <b>표가 곧 결정</b>이다 — 선택자가 없고, 다시 뽑기도 없다.
 *
 * <h2>네 단계</h2>
 * <ol>
 *   <li>{@link Stage#REVEAL} — 카드 두 장이 어둠에서 떠올라 뒤집히는 연출. 표를 받지 않는다</li>
 *   <li>{@link Stage#VOTE} — 60초. 표를 언제든 옮길 수 있다. 접속한 전원이 던지면 남은 시간이 5초로
 *       줄어든다(이미 5초 미만이면 그대로)</li>
 *   <li>{@link Stage#ROULETTE} — 시간이 끝났는데 동점(0:0 포함)이면 두 장 사이로 짧은 룰렛. 무엇에서
 *       멈출지는 <b>들어가는 순간 서버가 이미 정했다</b>({@link #winner()})</li>
 *   <li>{@link Stage#RESULT} — 안 뽑힌 카드가 떨어지고 뽑힌 카드가 가운데에서 5초. 끝나면 창이 닫힌다</li>
 * </ol>
 *
 * <p><b>서버도 플레이어도 읽지 않는다.</b> 「지금 표를 던질 수 있는 사람」은 부르는 쪽이 UUID 묶음으로
 * 넘긴다 — 그래야 살아 있는 서버 없이 집계·동점·5초 단축·접속 변화를 시험할 수 있다
 * ({@code DragonPrepVoteTest}).
 */
public final class DragonPrepVote {
	/** 카드 두 장이 떠오르는 연출. 3초. 이 동안 시간은 멈춰 있고 표는 받지 않는다. */
	public static final int REVEAL_TICKS = 60;
	/** 투표 제한시간. 60초 — 일반 증강의 {@code PerkChoiceSession.TIMEOUT_TICKS} 와 같다. */
	public static final int VOTE_TICKS = 1200;
	/** 접속한 전원이 표를 던졌을 때 줄이는 남은 시간. 5초. */
	public static final int ALL_VOTED_TICKS = 100;
	/** 동점일 때 두 장 사이를 오가는 룰렛. 3초. */
	public static final int ROULETTE_TICKS = 60;
	/** 뽑힌 카드를 가운데에 두고 보여 주는 시간. 5초 — 일반 증강의 결과 화면과 같다. */
	public static final int RESULT_TICKS = 100;

	/** 한 판이 지나가는 네 단계. 차례({@link #ordinal()})가 통신 값이다 — 바꾸지 말 것. */
	public enum Stage {
		REVEAL,
		VOTE,
		ROULETTE,
		RESULT;

		/** 통신 값에서 되돌린다. 모르는 값이면 {@link #REVEAL}(아무것도 못 누르는 단계)로 본다. */
		public static Stage byId(int id) {
			Stage[] all = values();
			return id >= 0 && id < all.length ? all[id] : REVEAL;
		}
	}

	/** {@link #tick} 이 알려 주는 일. 부르는 쪽은 이것을 보고 화면을 다시 보내거나 증강을 준다. */
	public enum Event {
		/** 아무 일도 없었다. */
		NONE,
		/** 연출이 끝나 투표가 열렸다. */
		VOTE_OPENED,
		/** 동점이라 룰렛이 돌기 시작했다. 멈출 카드는 {@link #winner()} 에 이미 있다. */
		ROULETTE_STARTED,
		/** 뽑혔다. 부르는 쪽이 이 순간 증강을 팀에 준다. 결과 화면이 시작된다. */
		DECIDED,
		/** 결과 화면이 끝났다. 창을 닫고 시간을 다시 흐르게 한다. */
		FINISHED
	}

	private final List<String> options;
	/**
	 * 던진 표. 열쇠가 <b>던진 사람</b>이라 한 사람은 언제나 한 표뿐이고, 옮기면 옛 표는 저절로 사라진다.
	 * 넣은 차례를 지켜야 화면의 이름 줄이 표가 바뀔 때마다 뒤섞이지 않는다.
	 */
	private final Map<UUID, String> votes = new LinkedHashMap<>();
	private Stage stage = Stage.REVEAL;
	/** 지금 단계가 끝나기까지 남은 틱. */
	private int stageTicks = REVEAL_TICKS;
	/** 지금 단계의 전체 길이. 화면의 남은 시간 막대가 이것을 분모로 쓴다. 5초로 줄면 같이 준다. */
	private int stageTotal = REVEAL_TICKS;
	/** 접속한 전원이 던져 남은 시간이 5초로 줄었는가. 한 번 줄면 다시 늘지 않는다. */
	private boolean shortened;
	private @Nullable String winner;
	private boolean byRoulette;

	/** @param options 후보 증강 id. 차례가 화면의 카드 차례다 */
	public DragonPrepVote(List<String> options) {
		this.options = List.copyOf(options);
	}

	// ------------------------------------------------------------------ 조회

	public List<String> options() {
		return options;
	}

	public Stage stage() {
		return stage;
	}

	public int stageTicks() {
		return stageTicks;
	}

	public int stageTotal() {
		return stageTotal;
	}

	public boolean shortened() {
		return shortened;
	}

	/** 뽑힌 카드. 룰렛이 돌기 시작하거나 표로 정해지기 전에는 null. */
	public @Nullable String winner() {
		return winner;
	}

	/** 동점이라 룰렛으로 정했는가. */
	public boolean byRoulette() {
		return byRoulette;
	}

	/** 지금 표. 읽기만 할 수 있다. */
	public Map<UUID, String> votes() {
		return Collections.unmodifiableMap(votes);
	}

	/** 이 후보에 모인 표 수. */
	public int count(String option) {
		int count = 0;
		for (String target : votes.values()) {
			if (target.equals(option)) {
				count++;
			}
		}
		return count;
	}

	/** 지금 표를 받는 단계인가. */
	public boolean accepting() {
		return stage == Stage.VOTE;
	}

	// ------------------------------------------------------------------ 표

	/**
	 * 한 표를 받는다. 이미 다른 카드에 던졌으면 옮긴다.
	 *
	 * <p>같은 카드를 다시 눌러도 표가 꺼지지 않는다. 일반 증강의 제안 표({@code PerkVoteBoard.toggle})와
	 * 다른 점이다 — 여기서 표를 끄면 「전원이 골랐다」가 되었다 안 되었다 해서 5초 단축이 흔들린다.
	 *
	 * <p>다음 중 하나라도 어긋나면 아무것도 안 하고 거짓을 돌려준다. 클라이언트가 보낸 값이라 조작이나
	 * 늦게 도착한 패킷일 수 있다.
	 * <ul>
	 *   <li>표를 받는 단계가 아니다</li>
	 *   <li>던진 사람이 지금 표를 던질 수 있는 사람({@code present})이 아니다 — 관전자·남의 팀</li>
	 *   <li>후보에 없는 카드다</li>
	 * </ul>
	 *
	 * @param present 지금 표를 던질 수 있는 사람 전원. 5초 단축의 「전원」이 이 묶음이다
	 * @return 표나 남은 시간이 실제로 바뀌었으면 참. 화면을 다시 보낼지 정하는 값이다
	 */
	public boolean vote(UUID voter, String option, Collection<UUID> present) {
		if (!accepting() || voter == null || option == null || present == null
				|| !present.contains(voter) || !options.contains(option)) {
			return false;
		}
		boolean changed = !option.equals(votes.put(voter, option));
		return shortenIfEveryoneVoted(present) || changed;
	}

	/**
	 * 지금 표를 던질 수 없는 사람의 표를 거둔다 — 접속이 끊겼거나 관전자가 됐다.
	 *
	 * <p>거두고 나니 남은 사람 전원이 던진 상태면 그때도 5초로 줄인다. 아직 안 던진 한 사람이 나가서
	 * 「전원이 골랐다」가 된 것이다.
	 *
	 * <p>결과가 정해진 뒤에는 거두지 않는다. 화면의 이름 줄은 「누가 무엇을 골랐는가」의 기록이다.
	 *
	 * @return 표나 남은 시간이 실제로 바뀌었으면 참
	 */
	public boolean prune(Collection<UUID> present) {
		if (stage != Stage.VOTE && stage != Stage.REVEAL) {
			return false;
		}
		boolean removed = votes.keySet().retainAll(present == null ? List.of() : present);
		return shortenIfEveryoneVoted(present) || removed;
	}

	/**
	 * 지금 표를 던질 수 있는 사람이 전원 던졌으면 남은 시간을 {@link #ALL_VOTED_TICKS} 로 줄인다.
	 *
	 * <p><b>이미 그보다 적게 남았으면 그대로 둔다</b> — 늘리면 거의 끝난 시계가 5초로 되돌아간다. 한 번 줄면
	 * 새로 들어온 사람이 아직 안 던졌어도 다시 늘지 않는다(사람 말 「모든 플레이어가 선택이 끝났을 때 시간이
	 * 5초가 되어」 — 되돌리라는 말은 없고, 되돌리면 들락날락으로 시계를 끝없이 늘릴 수 있다).
	 */
	private boolean shortenIfEveryoneVoted(@Nullable Collection<UUID> present) {
		if (stage != Stage.VOTE || shortened || present == null || present.isEmpty()
				|| !votes.keySet().containsAll(present)) {
			return false;
		}
		shortened = true;
		if (stageTicks > ALL_VOTED_TICKS) {
			stageTicks = ALL_VOTED_TICKS;
			stageTotal = ALL_VOTED_TICKS;
		}
		return true;
	}

	// ------------------------------------------------------------------ 시간

	/**
	 * 한 틱 진행한다. 단계가 넘어가는 틱에만 {@link Event#NONE} 이 아닌 값이 나온다.
	 *
	 * @param random 동점일 때 멈출 카드를 고르는 난수. 표로 정해지면 쓰지 않는다
	 */
	public Event tick(RandomSource random) {
		if (stageTicks > 0) {
			stageTicks--;
			if (stageTicks > 0) {
				return Event.NONE;
			}
		}
		switch (stage) {
			case REVEAL -> {
				enter(Stage.VOTE, VOTE_TICKS);
				return Event.VOTE_OPENED;
			}
			case VOTE -> {
				List<String> tied = leaders(votes, options);
				if (tied.size() == 1) {
					winner = tied.getFirst();
					enter(Stage.RESULT, RESULT_TICKS);
					return Event.DECIDED;
				}
				// 0:0 도 여기다 — 아무도 안 던졌으면 모든 카드가 「가장 많은 표」로 동점이다.
				winner = tied.isEmpty() ? null : tied.get(random.nextInt(tied.size()));
				byRoulette = true;
				enter(Stage.ROULETTE, ROULETTE_TICKS);
				return Event.ROULETTE_STARTED;
			}
			case ROULETTE -> {
				enter(Stage.RESULT, RESULT_TICKS);
				return Event.DECIDED;
			}
			default -> {
				return Event.FINISHED;
			}
		}
	}

	/**
	 * 실제로 팀에 준 증강으로 결과를 바꾼다.
	 *
	 * <p>표로 뽑힌 카드를 그 사이에 이미 얻었으면(즉시 지급 연쇄 등) 부르는 쪽이 남은 카드로 대신 준다.
	 * 화면이 그 카드를 가운데에 두어야 「뽑힌 것」과 「받은 것」이 갈리지 않는다.
	 */
	public void settle(String applied) {
		if (applied != null && options.contains(applied)) {
			winner = applied;
		}
	}

	private void enter(Stage next, int ticks) {
		stage = next;
		stageTicks = ticks;
		stageTotal = ticks;
	}

	/**
	 * 표가 가장 많은 후보들. 하나면 그것이 뽑히고, 둘 이상이면 동점이다.
	 *
	 * <p>후보에 없는 대상에 붙은 표는 세지 않는다. 후보가 하나도 없으면 빈 목록이다.
	 */
	static List<String> leaders(Map<UUID, String> votes, List<String> options) {
		List<String> best = new ArrayList<>();
		int bestCount = -1;
		for (String option : options) {
			int count = 0;
			for (String target : votes.values()) {
				if (option.equals(target)) {
					count++;
				}
			}
			if (count > bestCount) {
				best.clear();
				bestCount = count;
			}
			if (count == bestCount) {
				best.add(option);
			}
		}
		return best;
	}
}
