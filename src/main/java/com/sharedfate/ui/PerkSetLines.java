package com.sharedfate.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 세트 효과를 <b>몇 줄로, 어떤 글자로, 어느 차례로</b> 보여 줄지 정하는 계산.
 *
 * <h2>가진 것이 0인 유형은 뺀다</h2>
 * <p>유형이 열네 개다. 전부 그리면 <b>「기동 0/2」 같은 줄 열네 개가 화면을 덮는다.</b> 아직
 * 한 발도 딛지 않은 유형은 알려 줄 것이 없다 — 그 유형에 무엇이 있는지는 팀 화면의 툴팁에서
 * 본다. 그래서 여기서는 {@code owned >= 1} 인 것만 남긴다.
 *
 * <h2>켜진 것이 위로 온다</h2>
 * <p>지금 몸에 붙어 있는 효과가 먼저고, 그 다음이 「조금만 더 모으면 되는 것」이다. 눈이
 * 위에서 아래로 흐르는 동안 <b>사실 → 계획</b> 순서로 읽힌다. 서버가 보낸 차례를 그대로
 * 쓰면 켜진 세트가 목록 한가운데 끼어 눈에 띄지 않는다.
 *
 * <h2>분모는 있는 단계만 가리킨다</h2>
 * <p>「방어 4/3」처럼 <b>분자가 분모보다 클 수 있다.</b> 방어는 단계가 2·3 둘뿐이라 넷째를
 * 모아도 켜질 것이 없고, 그때 분모에 가진 개수를 놓으면 「방어 4/4」가 되어 <b>있지도 않은
 * 4 단계</b>를 가리킨다. 툴팁을 열면 2·3 두 줄뿐이라 화면끼리 어긋난다. 자세한 규칙은
 * {@link Entry#goal()} 에 있다.
 *
 * <h2>「보급」 줄에만 시계가 붙는다</h2>
 * <p>「◆ 보급 4/4 04:12」처럼 다음 보급까지 남은 시간을 <b>진행도 뒤</b>에 적는다. <b>어느 유형에
 * 붙일지를 여기서 판단하지 않는다</b> — {@link Entry#intervalTicks()} 가 0 보다 큰 줄에만 붙고,
 * 그 값을 채우는 것은 서버({@code PerkSetBroadcaster})다. 「보급 옆에만」이라는 규칙을 화면과
 * 서버 두 곳에 적어 두면 한쪽만 고쳤을 때 채굴 줄에 시계가 뜬다.
 *
 * <p>시계가 <b>줄 끝</b>에 붙는 덕에 마름모도 이름도 진행도도 줄마다 같은 자리에서 시작한다.
 * 시계를 가운데 끼워 넣으면 보급 줄만 이름이 오른쪽으로 밀려 「켜졌나」와 「몇 개 모았나」를
 * 세로로 훑을 수 없다.
 *
 * <p>시계를 그리려면 <b>지금 게임 시간</b>이 있어야 하므로 {@link #visible(List, int, long)} 을
 * 쓴다. 시간을 모르는 자리(팀 화면·선택창 곁판)는 {@link #visible(List, int)} 를 그대로 써서
 * 시계 없는 줄을 받는다 — 그 두 화면은 늘 떠 있는 것이 아니라 초를 세어 봐야 소용이 없고,
 * 줄 폭이 매초 흔들리면 마우스가 어느 줄 위인지 재는 계산까지 함께 흔들린다.
 */
public final class PerkSetLines {
	/** 켜진 세트 앞에 붙는 표. 속이 찬 마름모다. */
	public static final String ACTIVE_MARK = "◆";
	/** 아직 안 켜진 세트 앞에 붙는 표. 속이 빈 마름모다. */
	public static final String PROGRESS_MARK = "◇";

	/**
	 * HUD 에 그릴 줄 수의 상한.
	 *
	 * <p>좌표·바이옴 아래에 붙는 자리라 화면 왼쪽 위를 통째로 먹으면 안 된다. 한 회차에 고르는
	 * 증강이 여덟 개 남짓이라 유형이 여덟 가지로 흩어지는 일은 드물고, 그렇게까지 흩어졌다면
	 * 어차피 켜진 세트가 없어 급히 볼 줄도 없다.
	 */
	public static final int MAX_HUD_LINES = 6;

	private PerkSetLines() {
	}

	/**
	 * 유형 하나의 진행 상황. {@code net.PerkSetSyncPayload.SetLine} 에서 그대로 옮겨 담는다.
	 *
	 * @param typeId        유형 id. 툴팁이 이름표를 고를 때 쓰는 열쇠다
	 * @param displayName   화면에 적을 한국어 이름
	 * @param owned         지금 가진 개수
	 * @param nextThreshold 다음 단계에 필요한 개수. 더 오를 곳이 없으면 0
	 * @param activeTier    켜진 단계 중 <b>가장 높은 것이 열리는 개수</b>. 안 켜졌으면 0.
	 *                      단계는 누적이라 켜진 것이 여럿일 수 있고({@code 채굴 4} 면 2·3·4 가
	 *                      전부 켜진다) 그중 가장 높은 하나를 서버가 골라 싣는다.
	 *                      {@code nextThreshold} 가 0 일 때 이 값이 분모가 된다
	 * @param intervalTicks 이 유형이 되풀이하는 일의 주기(틱). 지금은 「보급」만 0 보다 크다.
	 *                      0 이면 시계를 안 그린다
	 * @param anchorTick    그 되풀이가 <b>켜진 게임 시간</b>. 경계는 이 자리부터 주기마다다.
	 *                      0 이면 게임 시간의 배수를 경계로 삼는다 — 이 값을 안 싣는 옛 서버에
	 *                      붙었을 때의 모습이다
	 */
	public record Entry(String typeId, String displayName, int owned, int nextThreshold,
			int activeTier, int intervalTicks, long anchorTick) {

		/**
		 * 켜진 시점을 모르는 자리에서 쓰는 짧은 생성자.
		 *
		 * <p>켜진 시점을 싣지 않는 옛 서버에 붙었을 때의 모습이다. 시계는 그대로 뜨고 경계만
		 * 게임 시간의 배수가 된다.
		 */
		public Entry(String typeId, String displayName, int owned, int nextThreshold,
				int activeTier, int intervalTicks) {
			this(typeId, displayName, owned, nextThreshold, activeTier, intervalTicks, 0L);
		}

		/**
		 * 주기를 모르는 자리에서 쓰는 짧은 생성자.
		 *
		 * <p>주기를 싣지 않는 옛 서버에 붙었을 때와, 시계와 상관없는 것을 보는 시험을 위한
		 * 것이다. 주기가 0 이면 시계가 안 뜰 뿐 나머지는 그대로다.
		 */
		public Entry(String typeId, String displayName, int owned, int nextThreshold,
				int activeTier) {
			this(typeId, displayName, owned, nextThreshold, activeTier, 0, 0L);
		}

		/** 세트 효과가 이미 켜져 있는가. */
		public boolean active() {
			return activeTier > 0;
		}

		/**
		 * 이 줄에 남은 시간을 적어야 하는가.
		 *
		 * <p>{@link #active()} 를 함께 보지 않는다. 세트가 아니라 증강 하나가
		 * {@code supply_drop} 을 들고 도는 경우가 있을 수 있고({@code PerkSupplyDrops.candidatesOf}
		 * 가 보유 증강도 훑는다), 그때는 단계가 안 켜져 있어도 보급은 실제로 온다. <b>도는데
		 * 안 보이는 것</b>이 <b>안 도는데 보이는 것</b>보다 나쁘다.
		 */
		public boolean hasTimer() {
			return intervalTicks > 0;
		}

		/** 화면에 뜰 만한 것이 있는가. 한 개도 없는 유형은 알려 줄 것이 없다. */
		public boolean worthShowing() {
			return owned > 0;
		}

		/**
		 * 분모로 적을 수. <b>가리킬 단계가 없으면 0</b> 이고, 그때는 분수를 아예 안 적는다.
		 *
		 * <p>분모는 언제나 <b>실제로 있는 단계</b>여야 한다. 툴팁에 「2」·「3」 두 줄만 있는 방어에
		 * 「4/4」가 뜨면 4 단계가 있는 것으로 읽히고, 그 단계를 찾으러 툴팁을 열면 없다.
		 *
		 * <ul>
		 *   <li>다음 단계가 있으면 그 개수 — 「채굴 3/4」</li>
		 *   <li>다 켰으면 <b>가장 높은 단계</b>({@link #activeTier()}) — 방어는 2·3 뿐이라 넷을
		 *       모으면 「방어 4/3」이다. 분자가 분모보다 큰 것이 그대로 <b>더 모았지만 더 켤 것은
		 *       없다</b>는 뜻이 된다. 마지막 단계에 꼭 맞게 모았을 때는 예전과 같은 「채굴 3/3」이다</li>
		 *   <li>둘 다 0 이면 0 — 단계가 하나도 정의되지 않은 유형에서 임계값까지 채운 자리다.
		 *       가리킬 숫자가 없으므로 {@link PerkSetLines#label(Entry, String)} 이 분수를 뺀다</li>
		 * </ul>
		 */
		public int goal() {
			return nextThreshold > 0 ? nextThreshold : activeTier;
		}
	}

	/** 그릴 줄 하나. 색은 화면이 {@link #active()} 를 보고 고른다. */
	public record Line(String typeId, String text, boolean active) {
	}

	/**
	 * 「◆ 채굴 3/3」 한 줄의 글자. 시계는 붙지 않는다.
	 */
	public static String label(Entry entry) {
		return label(entry, "");
	}

	/**
	 * 「◆ 보급 4/4 04:12」 한 줄의 글자.
	 *
	 * <p>시계는 <b>진행도 뒤</b>, 즉 줄의 맨 끝에 붙는다. 그래서 시계가 있든 없든 마름모·이름·
	 * 진행도가 놓이는 자리가 줄마다 같다.
	 *
	 * <p>시계가 필요 없는 줄에는 {@code timer} 가 빈 문자열이고, 그러면 {@link #label(Entry)} 와
	 * 글자 하나까지 똑같다. 빈 시계에 자리를 남겨 두지 않는다 — 한 줄만 시계를 다는데 나머지
	 * 열 줄이 그만큼 넓어지면 무엇을 위해 넓어졌는지 읽히지 않는다.
	 *
	 * @param timer 「04:12」 같은 남은 시간. 비어 있으면 아무것도 붙지 않는다
	 */
	public static String label(Entry entry, String timer) {
		String clock = timer == null || timer.isEmpty() ? "" : " " + timer;
		int goal = entry.goal();
		// 가리킬 단계가 없으면 분수를 안 적는다. 없는 숫자를 지어내느니 개수만 적는 것이 낫다.
		String progress = goal > 0 ? entry.owned() + "/" + goal : Integer.toString(entry.owned());
		return (entry.active() ? ACTIVE_MARK : PROGRESS_MARK) + " " + entry.displayName()
				+ " " + progress + clock;
	}

	/**
	 * 화면에 실제로 그릴 줄들.
	 *
	 * <p>가진 것이 없는 유형을 빼고, 켜진 것을 위로 올리고, {@code limit} 줄까지만 남긴다.
	 * 잘릴 때 없어지는 것은 <b>가장 덜 모은 유형</b>이다 — 정렬을 먼저 하기 때문이다.
	 *
	 * @param entries 서버가 보낸 그대로의 유형 목록. 순서를 건드리지 않는다(복사해서 정렬한다)
	 * @param limit   남길 줄 수의 상한. 0 이하면 빈 목록
	 */
	public static List<Line> visible(List<Entry> entries, int limit) {
		return build(entries, limit, false, 0L);
	}

	/**
	 * {@link #visible(List, int)} 과 같되 <b>「보급」 줄에 남은 시간을 붙인다.</b>
	 *
	 * <p>HUD 만 이것을 쓴다. 시계가 붙는 줄은 {@link Entry#hasTimer()} 인 줄뿐이고, 그 값을
	 * 채우는 것은 서버다 — 화면은 유형 이름을 보고 판단하지 않는다.
	 *
	 * @param gameTime 지금 게임 시간. 클라이언트는 {@code level.getGameTime()} 으로 얻는다.
	 *                 바닐라가 초마다 서버 값으로 맞춰 주므로 서버가 재는 주기와 어긋나지 않는다
	 */
	public static List<Line> visible(List<Entry> entries, int limit, long gameTime) {
		return build(entries, limit, true, gameTime);
	}

	/**
	 * 두 {@code visible} 의 알맹이.
	 *
	 * @param withTimer 시계를 붙일 것인가. {@code gameTime} 을 아는 자리만 참이다
	 */
	private static List<Line> build(List<Entry> entries, int limit, boolean withTimer,
			long gameTime) {
		if (entries == null || limit <= 0) {
			return List.of();
		}
		List<Entry> kept = new ArrayList<>(entries.size());
		for (Entry entry : entries) {
			if (entry != null && entry.worthShowing()) {
				kept.add(entry);
			}
		}
		// 안정 정렬이라 같은 값끼리는 서버가 보낸 차례가 그대로 남는다. 그래야 줄이 프레임마다
		// 자리를 바꾸지 않는다.
		kept.sort(Comparator
				// 켜진 것이 먼저.
				.comparing((Entry entry) -> !entry.active())
				// 켜진 것끼리는 높은 단계가 먼저.
				.thenComparingInt(entry -> -entry.activeTier())
				// 아직인 것끼리는 많이 모은 쪽이 먼저.
				.thenComparingInt(entry -> -entry.owned()));

		List<Line> lines = new ArrayList<>(Math.min(limit, kept.size()));
		for (int index = 0; index < kept.size() && index < limit; index++) {
			Entry entry = kept.get(index);
			String timer = withTimer && entry.hasTimer()
					? SupplyCountdown.text(gameTime, entry.intervalTicks(), entry.anchorTick())
					: "";
			lines.add(new Line(entry.typeId(), label(entry, timer), entry.active()));
		}
		return List.copyOf(lines);
	}

	/**
	 * 가장 긴 줄의 폭.
	 *
	 * <p>세트 줄 위에 긋는 구분선의 길이가 이 값이다. 짧은 줄에 맞추면 선이 글자를 덜 덮고,
	 * 화면 폭에 맞추면 왼쪽 위를 가로지르는 큰 선이 되어 좌표보다 눈에 먼저 든다.
	 *
	 * <p>매 프레임 다시 재기 때문에 <b>보급 줄의 글자 수가 흔들리면 선의 길이도 함께
	 * 흔들린다.</b> {@link SupplyCountdown#format(int)} 가 분까지 두 자리로 채우는 것이 그
	 * 흔들림을 막는 자리다.
	 *
	 * @param minimum 줄이 아무리 짧아도 이만큼은 긋는다. 줄이 없으면 0 이다
	 */
	public static int blockWidth(List<Line> lines, java.util.function.ToIntFunction<String> measure,
			int minimum) {
		if (lines == null || lines.isEmpty() || measure == null) {
			return 0;
		}
		int widest = Math.max(0, minimum);
		for (Line line : lines) {
			widest = Math.max(widest, measure.applyAsInt(line.text()));
		}
		return widest;
	}

	/**
	 * 세트 덩어리가 차지하는 세로 길이.
	 *
	 * <p>줄이 하나도 없으면 <b>0</b>이다. 그래야 세트를 하나도 모으지 않은 사람의 화면에서
	 * 아래 목록이 이유 없이 내려가지 않는다. 팀 화면은 이 값을 {@code perkListTop()} 에 더해
	 * 스크롤 계산과 잘라내기를 한 번에 따라오게 한다.
	 *
	 * @param lineCount  그릴 줄 수
	 * @param lineHeight 한 줄의 높이
	 * @param gap        덩어리 아래에 둘 틈
	 */
	public static int blockHeight(int lineCount, int lineHeight, int gap) {
		if (lineCount <= 0) {
			return 0;
		}
		return lineCount * Math.max(0, lineHeight) + Math.max(0, gap);
	}

	/**
	 * 마우스가 올라가 있는 줄의 차례. 어느 줄에도 없으면 −1.
	 *
	 * <p>가로 범위까지 보는 이유는, 세트 덩어리가 판 왼쪽에 있고 오른쪽은 빈자리라 그쪽에서도
	 * 툴팁이 뜨면 <b>마우스를 어디에 두어도 무언가 뜨는</b> 화면이 되기 때문이다.
	 *
	 * @param width  줄 하나가 마우스를 받는 가로 길이. 보통은 글자 폭이다
	 * @param top    첫 줄의 윗변
	 */
	public static int rowAt(double mouseX, double mouseY, int left, int width, int top,
			int lineHeight, int lineCount) {
		if (lineCount <= 0 || lineHeight <= 0 || width <= 0) {
			return -1;
		}
		if (mouseX < left || mouseX >= left + width) {
			return -1;
		}
		if (mouseY < top || mouseY >= top + lineCount * lineHeight) {
			return -1;
		}
		return (int) ((mouseY - top) / lineHeight);
	}
}
