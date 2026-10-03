package com.sharedfate.client.trial;

import com.sharedfate.TestBootstrap;
import com.sharedfate.client.trial.TrialEntranceScreen.Countdown;
import com.sharedfate.net.TrialEntranceOfferPayload;
import com.sharedfate.sync.TrialEntranceGate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 엔드 입장 수락창이 <b>언제 스스로 닫히는가</b>.
 *
 * <h2>왜 이 시험이 있는가</h2>
 *
 * <p>⚠ <b>{@code runClient} 가 이 환경에서 아예 뜨지 않는다</b>({@code 0xC0000005}, 글꼴 로딩 중,
 * 모드 무관). 곧 이 화면은 <b>눈으로 확인할 수 없다.</b> 그려지는 모양은 사람이 시험 서버에
 * 접속해서 봐야 하고, 여기서 붙들 수 있는 것은 셈뿐이다.
 *
 * <p>틀리는 길이 둘이고 둘 다 조용하다.
 *
 * <ul>
 *   <li><b>안 닫힌다.</b> ESC 를 막아 둔 창이라 서버의 닫기 꾸러미가 유실되면 플레이어가 전투
 *       한가운데에 갇힌다 — 시련 룰렛 화면이 같은 까닭으로 타이머를 직접 든다</li>
 *   <li><b>서버보다 먼저 닫힌다.</b> 그러면 창이 사라진 채로 아직 붙들려 있어 「조작이 안
 *       된다」로 보인다. 서버가 실어 보낸 길이를 그대로 써야만 둘이 어긋나지 않는다</li>
 * </ul>
 */
class TrialEntranceScreenTest {

	/** 패킷을 하나라도 만들면 그 클래스의 코덱이 초기화된다. 레지스트리가 서 있어야 한다. */
	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	/** 서버가 실어 보내는 기다림의 길이. 숫자를 박으면 서버가 바꿔도 시험이 조용히 통과한다. */
	private static final int TIMEOUT = TrialEntranceGate.TIMEOUT_TICKS;

	private static TrialEntranceOfferPayload offer(boolean canConfirm, int timeoutTicks) {
		return new TrialEntranceOfferPayload(42L, "불운한 넷", "리더", canConfirm, timeoutTicks);
	}

	// ------------------------------------------------------------------ 스스로 닫는다

	@Test
	void 제한시간이_다_되면_스스로_닫는다() {
		Countdown countdown = new Countdown(TIMEOUT);

		assertFalse(countdown.finished(0), "뜨는 틱에 닫히면 안 된다");
		assertFalse(countdown.finished(TIMEOUT - 1), "아직 한 틱 남았다");
		assertTrue(countdown.finished(TIMEOUT), "여기서 안 닫으면 플레이어가 전투 중에 갇힌다");
		assertTrue(countdown.finished(TIMEOUT + 1000), "지나쳐도 닫힌 채다");
	}

	/**
	 * 창이 닫히는 시각이 <b>서버가 기다리는 시각과 같다.</b>
	 *
	 * <p>여기서 숫자를 지어내면 두 가지가 생긴다 — 짧으면 창이 사라진 채로 붙들려 있고, 길면
	 * 서버가 이미 전투를 연 뒤에 창만 남아 18.5초 연출의 앞머리를 가린다.
	 */
	@Test
	void 닫히는_시각이_서버가_기다리는_시각과_같다() {
		assertEquals(TIMEOUT, new Countdown(offer(true, TIMEOUT).timeoutTicks()).timeoutTicks());
	}

	@Test
	void 남은_초를_올림으로_센다() {
		Countdown countdown = new Countdown(40);

		assertEquals(2, countdown.remainingSeconds(0));
		assertEquals(1, countdown.remainingSeconds(20));
		// 마지막 한 틱까지 1 이 남는다. 0 으로 떨어뜨리면 「0초 뒤」가 한 틱 동안 뜬다.
		assertEquals(1, countdown.remainingSeconds(39));
		assertEquals(0, countdown.remainingSeconds(40));
	}

	@Test
	void 막대가_가득에서_0으로_줄어든다() {
		Countdown countdown = new Countdown(100);

		assertEquals(1.0F, countdown.fraction(0), 0.0001F);
		assertEquals(0.5F, countdown.fraction(50), 0.0001F);
		assertEquals(0.0F, countdown.fraction(100), 0.0001F);
		assertEquals(0.0F, countdown.fraction(500), 0.0001F, "지나쳐도 음수가 되지 않는다");
	}

	/** 0 으로 나누지 않는다. 밖에서 온 값이라 0 이 올 수 있다. */
	@Test
	void 제한시간이_0이면_그_틱에_닫힌다() {
		Countdown countdown = new Countdown(0);

		assertTrue(countdown.finished(0));
		assertEquals(0.0F, countdown.fraction(0), 0.0001F);
		assertEquals(0, countdown.remainingSeconds(0));
	}

	/** 음수가 들어와도 셈이 깨지지 않는다. */
	@Test
	void 음수_제한시간은_0으로_눌린다() {
		assertEquals(0, new Countdown(-50).timeoutTicks());
	}

	// ------------------------------------------------------------------ 열어도 되는 꾸러미인가

	/**
	 * 제한시간 0 인 꾸러미로는 창을 열지 않는다.
	 *
	 * <p>뜨는 틱에 닫히므로 화면이 한 번 번쩍이는 것으로만 보인다 — 고장으로 읽힌다.
	 * {@code TrialRouletteScreen.shouldOpen} 이 빈 후보를 거르는 것과 같은 자리다.
	 */
	@Test
	void 제한시간이_없는_꾸러미로는_창을_열지_않는다() {
		assertFalse(TrialEntranceScreen.shouldOpen(null), "빈 꾸러미로 창을 열지 않는다");
		assertFalse(TrialEntranceScreen.shouldOpen(offer(true, 0)));
		assertTrue(TrialEntranceScreen.shouldOpen(offer(true, TIMEOUT)));
		// 누를 수 없는 사람에게도 창은 뜬다. 「누구를 기다리는가」를 보여 주는 것이 그 창의 일이다.
		assertTrue(TrialEntranceScreen.shouldOpen(offer(false, TIMEOUT)));
	}

	// ------------------------------------------------------------------ 글자 배율

	/**
	 * ⚠ <b>화면 크기를 가정하지 않는다.</b>
	 *
	 * <p>1080p 의 기본 GUI 배율이 4 라 화면이 <b>480×270</b> 으로 잡힌다. 이 저장소는 그것을 모르고
	 * 78px 자리에 104px 짜리 줄을 그리려다 <b>그냥 안 그리고 빠져나간</b> 적이 있다. 큰 글자가
	 * 안 들어가면 배율을 낮춰야 하고, 그 판단은 화면을 띄워 봐야만 아는 자리에 두면 아무도 못
	 * 지킨다 — {@code TrialRouletteScreen.fitScale} 과 같은 셈이다.
	 */
	@Test
	void 안_들어가는_배율은_고르지_않는다() {
		int[] scales = {2, 1};

		assertEquals(2, TrialEntranceScreen.fitScale(scales, 100, 244), "2배로 들어간다");
		assertEquals(1, TrialEntranceScreen.fitScale(scales, 130, 244),
				"2배면 260px 이라 판 안쪽 244px 을 넘는다 — 한 단계 낮춰야 한다");
		assertEquals(1, TrialEntranceScreen.fitScale(scales, 1000, 244),
				"하나도 안 들어가면 가장 작은 것을 쓴다. 안 그리고 빠져나가면 안 된다");
	}

	@Test
	void 배율_후보가_없어도_1을_준다() {
		assertEquals(1, TrialEntranceScreen.fitScale(new int[0], 10, 100));
		assertEquals(1, TrialEntranceScreen.fitScale(null, 10, 100));
	}

	// ------------------------------------------------------------------ 480×270

	/** 1080p 기본 설정의 화면 크기. GUI 배율이 <b>4</b> 라 1920×1080 이 이것으로 잡힌다. */
	private static final int SCREEN_WIDTH = 480;
	private static final int SCREEN_HEIGHT = 270;
	/** 바닐라 기본 글꼴 한 줄 높이. */
	private static final int LINE_HEIGHT = 9;

	/**
	 * ⚠⚠ <b>새 문구 세 줄이 480×270 에서 판 안에 들어간다.</b>
	 *
	 * <p>사람이 적어 준 글은 셋이다 — 「시련이 곧 시작됩니다.」·「엔더드래곤」·「취소할 수
	 * 없습니다.」. 6장의 사고(78px 자리에 104px 짜리 줄을 그리려다 <b>아무것도 안 그리고
	 * 빠져나갔다</b>)가 바로 이 값을 안 재서 난 것이라, 글꼴 없이 재는 자리를 떼어 두고 여기서
	 * 값으로 묻는다.
	 */
	@Test
	void 세_줄이_480x270_판_안에_들어간다() {
		// 머리글이 2배로 들어간 경우가 기본 모양이다.
		var plate = TrialEntranceScreen.layout(SCREEN_HEIGHT, LINE_HEIGHT, 2, 1, 1, 1);

		assertTrue(plate.hasTextRoom(),
				"판 안에 글을 그릴 자리가 없다 — 아무것도 안 그리고 빠져나가는 그 사고다");
		assertTrue(plate.top() >= TrialEntranceScreen.SCREEN_MARGIN, "판이 화면 위로 나갔다");
		assertTrue(plate.barBottom() <= SCREEN_HEIGHT - TrialEntranceScreen.SCREEN_MARGIN,
				"남은 시간 막대가 화면 아래로 나갔다 — 끝이 있다는 사실을 말하는 것이 그것뿐이다");
		assertTrue(plate.buttonTop() >= plate.bottom(),
				"단추가 판 안으로 들어갔다 — 판이 「확인」을 덮으면 ESC 도 안 듣는 창에서 누를 수"
						+ " 있는 것이 하나도 없어진다");

		// 글 세 줄이 실제로 차지하는 세로. 판이 그만큼은 돼야 잘리지 않는다.
		int content = LINE_HEIGHT * 2
				+ TrialEntranceScreen.LINE_GAP + LINE_HEIGHT
				+ TrialEntranceScreen.LINE_GAP + LINE_HEIGHT;
		assertEquals(TrialEntranceScreen.PLATE_PADDING * 2 + content, plate.height(),
				"판이 세 줄을 다 담지 못한다 — 아래 줄이 잘려 「취소할 수 없습니다」가 사라진다");
	}

	/**
	 * <b>세 줄이 판 안쪽 가로에 들어간다.</b>
	 *
	 * <p>⚠ 글꼴이 없으니 글자 폭을 <b>넉넉하게 잡아</b> 센다. 한글 한 자를 <b>12px</b> 로 두는
	 * 것은 바닐라 기본 글꼴이 실제로 쓰는 폭보다 넓은 쪽이라, 여기서 들어가면 실제로도 들어간다.
	 */
	@Test
	void 세_줄이_판_안쪽_가로에_들어간다() {
		int inner = TrialEntranceScreen.innerWidth(SCREEN_WIDTH);
		assertEquals(244, inner, "판 260 에서 좌우 여백 8 씩을 뺀 값이다");
		// ⚠ 화면이 넓어져도 판은 안 늘어난다. 곧 가로는 480 에서 잰 것이 상한이다.
		assertEquals(inner, TrialEntranceScreen.innerWidth(1920),
				"판 가로에 상한이 없어졌다 — 넓은 화면에서만 들어가는 글이 생긴다");

		int hangul = 12;
		int ascii = 6;
		// 「시련이 곧 시작됩니다.」 — 한글 아홉 자 · 빈칸 둘 · 마침표 하나
		int headline = 9 * hangul + 3 * ascii;
		// 「엔더드래곤」 — 한글 다섯 자
		int boss = 5 * hangul;
		// 「취소할 수 없습니다.」 — 한글 여덟 자 · 빈칸 둘 · 마침표 하나
		int warning = 8 * hangul + 3 * ascii;

		int scale = TrialEntranceScreen.fitScale(TrialEntranceScreen.HEADLINE_SCALES,
				headline, inner);
		assertTrue(headline * scale <= inner,
				"머리글이 배율 " + scale + " 에서 판 밖으로 나간다 (" + headline * scale + " > "
						+ inner + ")");
		assertTrue(boss <= inner, "보스 이름 줄이 판 밖으로 나간다");
		assertTrue(warning <= inner, "경고 줄이 판 밖으로 나간다");
	}

	/**
	 * <b>글이 길어져 판이 커져도 아래 것들을 덮지 않는다.</b>
	 *
	 * <p>줄바꿈으로 줄 수가 늘어나는 경우다. 판 높이에 천장이 없으면 「확인」 단추와 막대가
	 * 화면 밖으로 밀려난다 — 그러면 ESC 도 안 듣는 창에서 할 수 있는 일이 없어진다.
	 */
	@Test
	void 줄이_늘어나도_단추와_막대가_화면_안에_있다() {
		var plate = TrialEntranceScreen.layout(SCREEN_HEIGHT, LINE_HEIGHT, 2, 6, 4, 4);

		assertTrue(plate.barBottom() <= SCREEN_HEIGHT - TrialEntranceScreen.SCREEN_MARGIN,
				"막대가 화면 아래로 나갔다");
		assertTrue(plate.buttonTop() + TrialEntranceScreen.BUTTON_HEIGHT <= plate.barTop(),
				"단추와 막대가 겹친다");
	}

	/**
	 * ⚠ <b>아무것도 안 그리고 빠져나가는 길이 없다.</b>
	 *
	 * <p>6장의 사고가 그것이다. 화면이 터무니없이 낮아도 판은 <b>여백 + 한 줄</b> 보다 작아지지
	 * 않는다 — 글이 넘치면 넘치게 두고({@code enableScissor} 가 잘라 준다) 안 그리지는 않는다.
	 */
	@Test
	void 화면이_아무리_낮아도_판이_사라지지_않는다() {
		for (int height : new int[] {270, 200, 120, 80, 40, 1}) {
			var plate = TrialEntranceScreen.layout(height, LINE_HEIGHT, 2, 1, 1, 1);
			assertTrue(plate.hasTextRoom(), height + "px 화면에서 판 안에 글자리가 없다");
			assertTrue(plate.height() >= TrialEntranceScreen.PLATE_PADDING * 2 + LINE_HEIGHT,
					height + "px 화면에서 판이 한 줄보다 작아졌다");
		}
	}

	/** 글꼴 높이가 0 으로 들어와도 셈이 깨지지 않는다. 0 으로 나누거나 음수 높이가 되면 안 된다. */
	@Test
	void 줄_높이가_0이어도_깨지지_않는다() {
		var plate = TrialEntranceScreen.layout(SCREEN_HEIGHT, 0, 0, 1, 1, 1);
		assertTrue(plate.height() > 0);
		assertTrue(plate.hasTextRoom());
	}

	// ------------------------------------------------------------------ 글이 이것만이다

	/**
	 * ⚠⚠ <b>사람이 적어 준 세 줄만 남아 있다.</b>
	 *
	 * <p>사람 말이 「이정도만」이었다. 설명 글을 다시 넣는 사람이 여기서 걸린다 — 걷은 것은
	 * 「엔더 드래곤 시련 전투가 시작됩니다」·「팀 전원이 … 입장 연출이 돕니다」·팀 이름 줄·
	 * 바닥의 「N초 뒤 자동으로 시작합니다」다.
	 */
	@Test
	void 사람이_적어_준_세_줄만_남아_있다() {
		String source = sourceText(
				"src/client/java/com/sharedfate/client/trial/TrialEntranceScreen.java");

		assertTrue(source.contains("Component.literal(\"시련이 곧 시작됩니다.\")"), "첫 줄이 없다");
		assertTrue(source.contains("Component.literal(\"엔더드래곤\")"), "보스 이름 줄이 없다");
		// ⚠ 사람이 「취소할수」로 적었지만 띄어쓰기를 바르게 넣었다.
		assertTrue(source.contains("Component.literal(\"취소할 수 없습니다.\")"),
				"경고 줄이 없거나 띄어쓰기가 틀렸다");
		assertFalse(source.contains("Component.literal(\"취소할수"), "띄어쓰기가 틀렸다");

		for (String gone : new String[] {
				"시련을 시작합니다", "엔더 드래곤 시련 전투가 시작됩니다", "입장 연출이 돕니다",
				"초 뒤 자동으로 시작합니다"}) {
			assertFalse(source.contains("\"" + gone),
					"걷어낸 설명 글이 돌아왔다: " + gone + " — 사람 말이 「이정도만」이었다");
		}
	}

	/**
	 * <b>남긴 것 셋은 그대로다 — 단추 · 기다림 줄 · 막대.</b>
	 *
	 * <p>셋 다 기능이고 글 장식이 아니다. 하나라도 지우면 창이 「ESC 도 안 듣는데 아무것도
	 * 할 수 없고 언제 끝나는지도 모르는 화면」이 된다.
	 */
	@Test
	void 단추와_기다림_줄과_막대는_남아_있다() {
		String source = sourceText(
				"src/client/java/com/sharedfate/client/trial/TrialEntranceScreen.java");

		assertTrue(source.contains("Component.literal(\"확인\")"), "「확인」 단추 글자가 사라졌다");
		assertTrue(source.contains("의 확인을 기다립니다"),
				"리더가 아닌 사람의 기다림 줄이 사라졌다 — 넷이 서로 상대를 기다리게 된다");
		assertTrue(source.contains("renderCountdownBar"),
				"남은 시간 막대가 사라졌다 — 끝이 있다는 사실을 말하는 것이 그것뿐이다");
	}

	/**
	 * <b>층이 셋이다 — 크기 하나로 가르지 않았다.</b>
	 *
	 * <p>판 안쪽이 244px 뿐이라 3배가 들어가는 글이 없다(위 가로 시험). 그래서 머리글만 배율로
	 * 키우고 아래 둘은 <b>굵기와 색</b>으로 가른다. 새 색은 만들지 않았다 —
	 * {@code TrialWarning.Colors} 의 보라와 빨강이다.
	 */
	@Test
	void 층을_크기와_색으로_셋으로_갈랐다() {
		String source = sourceText(
				"src/client/java/com/sharedfate/client/trial/TrialEntranceScreen.java");

		assertTrue(source.contains("TrialWarning.Colors.MARKED"),
				"보스 이름 줄의 색이 팔레트에서 오지 않는다");
		assertTrue(source.contains("TrialWarning.Colors.DEADLY"),
				"경고색이 팔레트의 빨강이 아니다");
		assertEquals(2, TrialEntranceScreen.HEADLINE_SCALES[0],
				"머리글이 가장 큰 층이 아니게 됐다");
	}

	/** 주석은 클래스 파일에 남지 않는다. 글자와 판단의 근거를 붙들려면 소스를 읽어야 한다. */
	private static String sourceText(String relative) {
		java.nio.file.Path here = java.nio.file.Path.of("").toAbsolutePath();
		for (java.nio.file.Path at = here; at != null; at = at.getParent()) {
			java.nio.file.Path candidate = at.resolve(relative);
			if (java.nio.file.Files.isRegularFile(candidate)) {
				try {
					return java.nio.file.Files.readString(candidate,
							java.nio.charset.StandardCharsets.UTF_8);
				} catch (java.io.IOException broken) {
					return org.junit.jupiter.api.Assertions.fail(broken);
				}
			}
		}
		return org.junit.jupiter.api.Assertions.fail(relative + " 을 찾지 못했다. 작업 디렉터리: " + here);
	}
}
