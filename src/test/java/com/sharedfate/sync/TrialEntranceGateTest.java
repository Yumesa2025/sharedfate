package com.sharedfate.sync;

import com.sharedfate.sync.TrialEntranceGate.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 엔드 입장 「시련을 시작합니다」 수락창.
 *
 * <p>살아 있는 서버 없이는 사람을 엔드에 세울 수 없다. 그래서 두 가지를 붙든다 —
 * <b>월드를 모르는 판단</b>({@link TrialEntranceGate#outcomeOf} ·
 * {@link TrialEntranceGate#mayConfirm} · {@link TrialPodium#groundedAt})과 <b>배선</b>이다.
 * 둘 다 <b>조용히 무너지는 쪽</b>이고, 이 판에서 조용한 고장의 값은 월드 하나다.
 */
class TrialEntranceGateTest {

	private static final UUID LEADER = UUID.fromString("00000000-0000-0000-0000-00000000aaaa");
	private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-00000000bbbb");

	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		TrialEntranceGate.clearState();
		DragonTrialManager.clearState();
	}

	// ------------------------------------------------------------ ② 리더만 누를 수 있다

	/**
	 * <b>리더가 아닌 사람의 확인은 거절된다.</b>
	 *
	 * <p>사람 말이 「리더가 확인 을 누루면」이다. 이것이 무너지면 네 사람 가운데 아무나 팀을
	 * 돌아올 수 없는 문턱 밖으로 밀어 넣는다 — 그리고 그 사고는 <b>로그에도 화면에도 안 보인다.</b>
	 */
	@Test
	void 리더가_아닌_사람의_확인은_거절된다() {
		assertTrue(TrialEntranceGate.mayConfirm(LEADER, LEADER, false), "리더는 누를 수 있다");
		assertFalse(TrialEntranceGate.mayConfirm(MEMBER, LEADER, false),
				"리더가 아닌 사람이 눌렀다 — 거절해야 한다");
	}

	/** 관전자는 판에 끼어들지 않는다. 이 저장소의 다른 판정 열일곱 자리와 같은 거름망이다. */
	@Test
	void 관전_중인_리더는_누를_수_없다() {
		assertFalse(TrialEntranceGate.mayConfirm(LEADER, LEADER, true),
				"관전자는 판에 끼어들지 않는다");
	}

	/** 모르는 값으로는 아무도 누를 수 없다. 리더를 못 찾았을 때 「누구나」로 열려서는 안 된다. */
	@Test
	void 리더를_모르면_아무도_누를_수_없다() {
		assertFalse(TrialEntranceGate.mayConfirm(LEADER, null, false));
		assertFalse(TrialEntranceGate.mayConfirm(null, LEADER, false));
		assertFalse(TrialEntranceGate.mayConfirm(null, null, false));
	}

	// ------------------------------------------------------------ ④ 교착이 생기지 않는다

	/** 아직 아무 일도 없으면 기다린다. */
	@Test
	void 리더가_접속해_있고_안_눌렀으면_기다린다() {
		assertEquals(Outcome.WAIT, TrialEntranceGate.outcomeOf(true, false, 5L, 0L,
				TrialEntranceGate.TIMEOUT_TICKS, TrialEntranceGate.LEADERLESS_TICKS));
	}

	@Test
	void 리더가_누르면_진행한다() {
		assertEquals(Outcome.ACCEPTED, TrialEntranceGate.outcomeOf(true, true, 5L, 0L,
				TrialEntranceGate.TIMEOUT_TICKS, TrialEntranceGate.LEADERLESS_TICKS));
	}

	/**
	 * <b>리더가 자리를 비워도 교착이 안 생긴다 — 기다림에 끝이 있다.</b>
	 *
	 * <p>리더가 접속해 있지만 창을 못 보거나(사망 화면·증강 선택창) 그냥 자리를 비웠을 때다.
	 * 제한시간이 없으면 나머지가 <b>영원히</b> 붙들려 있다.
	 */
	@Test
	void 리더가_끝내_안_누르면_시간이_끝나고_진행한다() {
		int timeout = TrialEntranceGate.TIMEOUT_TICKS;
		assertEquals(Outcome.WAIT, TrialEntranceGate.outcomeOf(true, false, timeout - 1L, 0L,
				timeout, TrialEntranceGate.LEADERLESS_TICKS), "아직 한 틱 남았다");
		assertEquals(Outcome.TIMED_OUT, TrialEntranceGate.outcomeOf(true, false, timeout, 0L,
				timeout, TrialEntranceGate.LEADERLESS_TICKS), "여기서 기다리면 영영 안 끝난다");
	}

	/**
	 * <b>리더가 나가면 짧게 기다리고 진행한다.</b>
	 *
	 * <p>누를 사람이 없는 것이 확실하므로 60초를 다 쓸 이유가 없다. 그래도 0 이 아닌 것은 남은
	 * 사람이 왜 진행되는지 읽을 틈을 주려는 것이다.
	 */
	@Test
	void 리더가_나가면_짧게_기다리고_진행한다() {
		int grace = TrialEntranceGate.LEADERLESS_TICKS;
		assertEquals(Outcome.WAIT, TrialEntranceGate.outcomeOf(true, false, 100L, grace - 1L,
				TrialEntranceGate.TIMEOUT_TICKS, grace));
		assertEquals(Outcome.LEADERLESS, TrialEntranceGate.outcomeOf(true, false, 100L, grace,
				TrialEntranceGate.TIMEOUT_TICKS, grace));
	}

	/** 전원이 나가면 <b>전투를 열지 않는다.</b> 빈 엔드에서 18.5초 연출이 도는 것을 막는다. */
	@Test
	void 전원이_나가면_전투를_열지_않는다() {
		assertEquals(Outcome.ABANDONED, TrialEntranceGate.outcomeOf(false, false, 5L, 5L,
				TrialEntranceGate.TIMEOUT_TICKS, TrialEntranceGate.LEADERLESS_TICKS));
		// 눌렸더라도 아무도 없으면 열지 않는다. 옮길 사람도 창을 볼 사람도 없다.
		assertEquals(Outcome.ABANDONED, TrialEntranceGate.outcomeOf(false, true, 5L, 5L,
				TrialEntranceGate.TIMEOUT_TICKS, TrialEntranceGate.LEADERLESS_TICKS));
	}

	/**
	 * 눌린 것이 시간보다 <b>먼저</b> 읽힌다.
	 *
	 * <p>같은 틱에 둘이 겹칠 수 있다. 시간 초과로 읽히면 로그가 「확인되지 않아 그대로
	 * 시작합니다」라고 적는데, 리더는 방금 눌렀다 — 다음에 같은 자리를 보는 사람이 엉뚱한 곳을
	 * 뒤지게 된다.
	 */
	@Test
	void 같은_틱에_겹치면_누른_쪽으로_읽는다() {
		int timeout = TrialEntranceGate.TIMEOUT_TICKS;
		assertEquals(Outcome.ACCEPTED, TrialEntranceGate.outcomeOf(true, true, timeout, 999L,
				timeout, TrialEntranceGate.LEADERLESS_TICKS));
	}

	/**
	 * <b>어떤 입력에도 결말이 난다.</b>
	 *
	 * <p>위의 갈래 하나하나가 아니라 「영원히 {@link Outcome#WAIT} 인 길이 없다」를 붙든다.
	 * 조건을 하나 더 넣는 사람이 실수로 교착을 만들면 여기서 걸린다.
	 */
	@Test
	void 기다림은_반드시_끝난다() {
		int timeout = TrialEntranceGate.TIMEOUT_TICKS;
		int grace = TrialEntranceGate.LEADERLESS_TICKS;
		for (boolean anyOnline : new boolean[] {true, false}) {
			for (boolean leaderOnline : new boolean[] {true, false}) {
				long sinceSeen = leaderOnline ? 0L : grace * 10L;
				Outcome outcome = TrialEntranceGate.outcomeOf(anyOnline, false,
						timeout * 10L, sinceSeen, timeout, grace);
				assertNotEquals(Outcome.WAIT, outcome,
						"접속 " + anyOnline + " · 리더 " + leaderOnline
								+ " — 충분히 기다렸는데 아직도 기다린다. 교착이다");
			}
		}
	}

	/** 제한시간이 0 이하면 창이 뜨는 틱에 끝난다. 곧 <b>영원히 기다리는 값이 없다.</b> */
	@Test
	void 제한시간_값이_0보다_크다() {
		assertTrue(TrialEntranceGate.TIMEOUT_TICKS > 0,
				"0 이면 창이 뜨는 틱에 닫힌다 — 누를 틈이 없다");
		assertTrue(TrialEntranceGate.LEADERLESS_TICKS > 0,
				"0 이면 리더가 한 틱 끊겼을 때 그 자리에서 넘어간다");
		assertTrue(TrialEntranceGate.LEADERLESS_TICKS < TrialEntranceGate.TIMEOUT_TICKS,
				"리더가 없는 것이 확실한 쪽이 더 오래 기다리면 뒤집힌 것이다");
	}

	/**
	 * 제한시간이 <b>증강 선택과 같은 60초</b>다.
	 *
	 * <p>두 창이 같은 박자로 기다리면 사람이 기다림의 길이를 한 번만 배운다. ⚠ 상수를 빌려 오지는
	 * 않았다 — 증강 선택의 제한시간을 고치는 사람이 엔드 입장의 기다림까지 함께 고치는 것은 그가
	 * 의도한 일이 아니다. 그 「따로지만 같은 값」을 여기서 붙든다.
	 */
	@Test
	void 제한시간이_증강_선택과_같은_60초다() {
		assertEquals(20 * 60, TrialEntranceGate.TIMEOUT_TICKS);
	}

	// ------------------------------------------------------------ ⑥ 허공에 떨어지지 않는다

	/**
	 * <b>딛고 선 자리와 허공을 가른다.</b>
	 *
	 * <p>붙들린 사람이 허공에 있으면 단상으로 자리를 바꾼다. 이 셈이 뒤집히면 공유 체력 판에서
	 * 한 사람의 낙사가 곧 팀 전멸이고, 그것이 월드 삭제다.
	 */
	@Test
	void 땅을_딛고_있으면_참이다() {
		int minY = -64;
		assertTrue(TrialPodium.groundedAt(64.0, 63, minY), "발밑 한 칸 — 서 있는 것이다");
		// 기준 바로 그 자리까지는 딛고 있는 것으로 본다. 한 칸 턱을 뛰어오른 사람을 단상으로
		// 끌어가지 않으려는 여유다.
		assertTrue(TrialPodium.groundedAt(63.0 + TrialPodium.SAFE_DROP, 63, minY),
				"기준 안이면 딛고 있는 것으로 본다");
	}

	@Test
	void 허공에_떠_있으면_거짓이다() {
		int minY = -64;
		assertFalse(TrialPodium.groundedAt(63.0 + TrialPodium.SAFE_DROP + 1, 63, minY),
				"기준에서 한 칸만 더 멀어도 허공이다");
		assertFalse(TrialPodium.groundedAt(100.0, 63, minY), "섬 위 하늘은 허공이다");
	}

	/**
	 * ⚠ <b>열이 통째로 비면 「아주 먼 땅」이 아니라 공허다.</b>
	 *
	 * <p>하이트맵은 열이 비면 월드 바닥을 돌려준다. 그것을 땅으로 읽으면 <b>엔드 섬 밖 허공에
	 * 선 사람이 「딛고 있다」가 되어</b> 붙들린 채로 공허에 떨어진다 — 이 시험이 막는 것이 그
	 * 한 가지다.
	 */
	@Test
	void 열이_비어_있으면_공허다() {
		int minY = -64;
		assertFalse(TrialPodium.groundedAt(minY + 1.0, minY, minY),
				"하이트맵이 바닥을 돌려준 것은 땅이 없다는 뜻이다");
		assertFalse(TrialPodium.groundedAt(64.0, minY, minY));
	}

	/** 낙하 피해가 시작되는 3칸보다 한 칸 위다 — 「이 거리 안이면 떨어져도 안 아프다」가 근거다. */
	@Test
	void 안전_낙하_기준이_낙하_피해_문턱_위다() {
		int vanillaFallDamageStart = 3;
		assertTrue(TrialPodium.SAFE_DROP > vanillaFallDamageStart,
				"기준이 낙하 피해 문턱보다 낮으면 「딛고 있다」로 본 자리에서 피해가 난다");
	}

	// ------------------------------------------------------------ ③ 좌표를 숫자로 박지 않았다

	/**
	 * ⚠⚠ <b>모을 자리를 바닐라에게 묻는다.</b>
	 *
	 * <p>사람이 「0 75 0 이엇나?」라고 <b>물음표를 붙여</b> 말했고, 실제로 그 값이
	 * {@code DragonTrialManager} 에 박혀 있었다. 숫자로 두면 전투 원점이 {@code (0, ?, 0)} 이
	 * 아닌 판에서 엉뚱한 곳에 모이고, 세로 75 는 섬 표면보다 열 칸 넘게 위다.
	 */
	@Test
	void 단상_자리를_바닐라_계산에서_가져온다() {
		String bytes = classBytes(TrialPodium.class, "TrialPodium");
		assertTrue(bytes.contains("getPodiumLocation"),
				"바닐라가 착지 목표로 삼는 그 점을 쓰지 않으면 좌표를 지어내는 것이다");
		assertTrue(bytes.contains("getFightOrigin"),
				"전투 원점을 읽지 않으면 중앙을 (0, 0) 으로 박게 된다");
		assertTrue(bytes.contains("getHeightmapPos"),
				"하이트맵을 지나지 않으면 세로를 숫자로 적게 된다");
	}

	/**
	 * 팀을 옮기는 쪽에 <b>좌표 상수가 남아 있지 않다.</b>
	 *
	 * <p>{@code (0, 75, 0)} 이 있던 자리다. 상수 풀에 {@code 75.0} 이 다시 나타나면 누가 계산을
	 * 지우고 숫자로 되돌린 것이다.
	 */
	@Test
	void 소환_코드에_옛_좌표가_남아_있지_않다() {
		String source = sourceText("src/main/java/com/sharedfate/sync/DragonTrialManager.java");
		assertFalse(source.contains("new Vec3(0.0, 75.0, 0.0)"),
				"착지점이 다시 숫자로 박혔다 — TrialPodium.locate 를 쓸 것");
		assertTrue(source.contains("TrialPodium.locate"),
				"팀을 옮기는 자리가 단상 계산을 쓰지 않는다");
	}

	/** 모으는 길이 한 벌이다. 두 벌이 되면 한쪽만 고쳐지는 날이 온다. */
	@Test
	void 전원_모으기와_재소환이_같은_길을_쓴다() {
		String bytes = classBytes(DragonTrialManager.class, "DragonTrialManager");
		assertTrue(bytes.contains("gatherTeam"), "전원 모으기가 사라졌다");
		assertTrue(bytes.contains("summonTeam"), "밖에 남은 사람 부르기가 사라졌다");
		assertTrue(bytes.contains("moveTeam"),
				"둘이 같은 길을 쓰지 않는다 — 착지점과 도착 무적이 두 벌이 된 것이다");
	}

	// ------------------------------------------------------------ ①⑤ 배선

	/**
	 * <b>① 시련을 끈 팀에게는 창이 뜨지 않는다.</b>
	 *
	 * <p>1장이 「끄면 완전한 바닐라」라고 못박고 있다. 창을 띄우는 조건에
	 * {@code trialsEnabled} 가 들어 있는지를 소스에서 본다 — 클래스 파일에는 조건의 모양이 남지
	 * 않는다.
	 */
	@Test
	void 시련을_끈_팀에게는_창이_뜨지_않는다() {
		String source = sourceText("src/main/java/com/sharedfate/sync/DragonTrialManager.java");
		int at = source.indexOf("TrialEntranceGate.open");
		assertTrue(at > 0, "수락창을 여는 자리가 사라졌다");
		String before = source.substring(Math.max(0, at - 400), at);
		assertTrue(before.contains("trialsEnabled"),
				"창을 여는 조건에 「시련을 켰는가」가 없다 — 끈 팀도 붙들린다");
	}

	/**
	 * <b>⑤ 이미 열린 전투에 늦게 들어온 사람에게는 안 뜬다.</b>
	 *
	 * <p>「첫 입장」만이다. 세션 · 소환 대기 · 창 셋 가운데 하나라도 있으면 건너뛴다. 이것이
	 * 무너지면 <b>싸우고 있는 팀이 다시 붙들려 단상으로 모인다.</b>
	 */
	@Test
	void 이미_열린_전투에는_창이_다시_뜨지_않는다() {
		String source = sourceText("src/main/java/com/sharedfate/sync/DragonTrialManager.java");
		int at = source.indexOf("SESSIONS.containsKey(teamId)");
		assertTrue(at > 0, "입장 감지의 거름망이 사라졌다");
		String guard = source.substring(at, Math.min(source.length(), at + 200));
		assertTrue(guard.contains("PENDING_SUMMON.containsKey(teamId)"),
				"소환 대기 중인 팀을 걸러야 한다");
		assertTrue(guard.contains("TrialEntranceGate.isOpen(teamId)"),
				"창이 떠 있는 팀을 걸러야 한다 — 안 걸면 매 틱 창이 새로 뜬다");
	}

	/** 전투가 없으면 어떤 팀에도 창이 떠 있지 않다. */
	@Test
	void 아무_팀에나_창이_떠_있다고_하지_않는다() {
		assertFalse(TrialEntranceGate.isOpen(null), "팀을 모르면 창도 없다");
		assertFalse(TrialEntranceGate.isOpen(UUID.randomUUID()));
		assertTrue(TrialEntranceGate.openTeams().isEmpty());
	}

	/**
	 * 수락창이 {@code tick} 배선에 들어 있다.
	 *
	 * <p>⚠ 소환 대기보다 <b>앞</b>이어야 한다. 뒤에 두면 확인한 그 틱에 전투가 열리는 것을 소환
	 * 대기가 먼저 보게 되어 순서가 두 가지로 읽힌다.
	 */
	@Test
	void 수락창이_배선에_들어_있다() {
		String source = sourceText("src/main/java/com/sharedfate/sync/DragonTrialManager.java");
		int gate = source.indexOf("tickEntranceGates(server, end, now)");
		int summons = source.indexOf("tickPendingSummons(server, end, now)");
		assertTrue(gate > 0, "수락창 틱이 배선에서 빠졌다 — 창이 떠 있기만 하고 아무 일도 안 난다");
		assertTrue(summons > 0);
		assertTrue(gate < summons, "수락창이 소환 대기보다 뒤에 있다");
	}

	/**
	 * <b>서버를 껐다 켜도 묶인 채로 남지 않는다 — 저장 파일에 한 글자도 안 남긴다.</b>
	 *
	 * <p>붙들기는 매 틱 다시 거는 것이라 되돌릴 것이 없다. 저장했다가 값이 어긋나면 <b>아무도
	 * 풀어 줄 수 없는 상태</b>가 월드 파일에 남는다 — {@code TrialFreeze} 가 「영원히 얼지 않게
	 * 하는 장치」를 넷이나 둔 것과 같은 두려움이다.
	 */
	@Test
	void 수락창은_저장되지_않는다() {
		for (java.lang.reflect.Field field : DragonTrialStore.Entry.class.getFields()) {
			assertFalse(field.getName().toLowerCase(java.util.Locale.ROOT).contains("entrance"),
					"저장 칸에 수락창이 들어갔다: " + field.getName()
							+ " — 재시작 뒤에 묶인 채로 되살아날 길이 생긴다");
			assertFalse(field.getName().toLowerCase(java.util.Locale.ROOT).contains("accept"),
					"저장 칸에 수락창이 들어갔다: " + field.getName());
		}
		String bytes = classBytes(TrialEntranceGate.class, "TrialEntranceGate");
		assertFalse(bytes.contains("DragonTrialStore"),
				"수락창이 저장 파일을 건드린다 — 재시작 뒤에 되살아날 길이 생긴다");
	}

	/**
	 * 붙들기가 <b>낙하 거리를 되돌린다.</b>
	 *
	 * <p>자리로 되돌려 놓는 것만으로는 모자랄 수 있다 — 되돌리는 꾸러미가 한 틱 늦으면 그 사이에
	 * 쌓인 낙하 거리가 남는다. 공유 체력 20 에서 낙하 피해는 그 자체로 사고다.
	 */
	@Test
	void 붙들린_사람의_낙하_거리를_되돌린다() {
		String bytes = classBytes(TrialEntranceGate.class, "TrialEntranceGate");
		assertTrue(bytes.contains("resetFallDistance"),
				"낙하 거리를 되돌리지 않는다 — 되돌려 놓은 틱에 낙하 피해가 들어온다");
		assertTrue(bytes.contains("teleportTo"), "자리를 되돌려 놓는 길이 사라졌다");
		assertTrue(bytes.contains("RESISTANCE"),
				"붙들린 동안 저항이 없다 — 창을 읽는 사이에 바닐라 드래곤이 때린다");
	}

	/**
	 * ⚠ <b>판을 얼리지 않는다.</b>
	 *
	 * <p>{@code TrialFreeze} 는 상한이 400틱(20초)이고 결과 칸이 <b>카드 하나</b>라
	 * ({@code Finished(teamId, trialId)}) 그 길로 보내면 있지도 않은 카드가 쌓인다. 게다가 바닐라
	 * 시간 정지는 플레이어를 멈추지 않아 <b>떨어지는 것도 안 멈춘다.</b> 까닭이 셋인 판단이라
	 * 다음 사람이 「얼리면 되지 않나」로 되돌리지 못하게 못박는다.
	 */
	@Test
	void 수락창은_판을_얼리지_않는다() {
		String bytes = classBytes(TrialEntranceGate.class, "TrialEntranceGate");
		for (String banned : new String[] {"TrialFreeze", "setFrozen", "tickRateManager"}) {
			assertFalse(bytes.contains(banned),
					banned + " 이 상수 풀에 있다 — 수락창이 판을 얼리고 있다");
		}
	}

	/** 「왜 얼리지 않았나」가 파일에 남아 있다. 주석은 클래스 파일에 안 남으므로 소스를 본다. */
	@Test
	void 얼리지_않은_까닭이_적혀_있다() {
		String source = sourceText("src/main/java/com/sharedfate/sync/TrialEntranceGate.java");
		assertTrue(source.contains("TrialFreeze"),
				"무엇을 쓰지 않았는지가 사라졌다 — 다음 사람이 그 길로 되돌린다");
		assertTrue(source.contains("MAX_TICKS"), "상한 400틱 이야기가 사라졌다");
	}

	// ------------------------------------------------- 2026-10-01 무한 고리를 막는 두 겹

	/**
	 * ⚠⚠ <b>드래곤이 죽은 뒤에는 전투가 「살아 있다」로 읽히지 않는다.</b>
	 *
	 * <p>사람 말: 「엔더드래곤 잡앗는데 시련을 시작합니다 가 떳어. 무한으로 확인 눌러도」.
	 * 시험 서버 로그에서 1초에 두세 바퀴 돌던 그 고리의 뿌리가 이 판단 하나다 —
	 * {@code DragonTrialManager.endFightLive} 에 고리의 네 단계를 적어 두었다.
	 *
	 * <p>셋이 <b>모두</b> 「살아 있다」라고 말해야 참이다. 곧 <b>하나라도 「끝났다」면 끝난
	 * 것</b>이고, 그것이 「한쪽만 막으면 반드시 샌다」를 값의 모양으로 적은 것이다.
	 */
	@Test
	void 드래곤이_죽은_뒤에는_전투가_살아_있지_않다() {
		assertTrue(DragonTrialManager.endFightLive(true, false, false),
				"살아 있는 드래곤이 있고 바닐라도 회차도 끝났다고 안 했다 — 이것만 참이다");
		assertFalse(DragonTrialManager.endFightLive(false, false, false),
				"살아 있는 드래곤이 없다 — 로그의 「드래곤 체력 0.0 · 크리스탈 0개」가 이 길이다");
		assertFalse(DragonTrialManager.endFightLive(true, true, false),
				"바닐라 EnderDragonFight.dragonKilled 가 참이면 끝난 전투다");
		assertFalse(DragonTrialManager.endFightLive(true, false, true),
				"회차가 이미 승리로 적혔다 — 그 월드는 초기화를 기다리는 중이다");
		assertFalse(DragonTrialManager.endFightLive(false, true, true));
	}

	/**
	 * <b>모를 때는 「끝나지 않았다」 쪽이다.</b>
	 *
	 * <p>바닐라 전투 객체를 못 읽는 판(엔드가 아닌 차원, 믹스인이 안 붙은 경우)에서 「끝났다」로
	 * 기울면 <b>엔드에 들어가도 전투가 영영 안 열리는 월드</b>가 생긴다. 그때 남는 수단은
	 * 「살아 있는 드래곤이 있는가」 하나이고, 그 하나로도 이번 고리는 막힌다.
	 */
	@Test
	void 바닐라_깃발을_못_읽으면_끝나지_않은_것으로_본다() {
		// fightClosed 는 「읽었고 참이었다」일 때만 참이다. 못 읽었으면 거짓으로 들어온다.
		assertTrue(DragonTrialManager.endFightLive(true, false, false),
				"깃발을 못 읽었어도 살아 있는 드래곤이 있으면 전투다");
		assertFalse(DragonTrialManager.endFightLive(false, false, false),
				"깃발을 못 읽었고 드래곤도 없으면 열지 않는다");
	}

	/**
	 * ⚠⚠ <b>바닐라 되살리기를 막지 않는다 — 크리스탈로 되살린 드래곤에는 창이 다시 뜬다.</b>
	 *
	 * <p>엔드 크리스탈 넷을 출구 포털에 놓아 드래곤을 다시 띄우는 것은 <b>바닐라 기능</b>이다.
	 * 그 길로 드래곤이 살아나면 새 전투라 수락창이 다시 떠야 한다 — 「한 번 끝났으면 영영 안
	 * 뜬다」로 만들면 안 된다.
	 *
	 * <p>이것을 지키는 것이 <b>어느 칸을 골랐는가</b>다. 26.3 바이트코드에서
	 * {@code setRespawnStage(END)} 가 {@code dragonKilled = false} 로 되돌리고
	 * {@code createNewDragon()} 을 부르는 것을 확인했다. 반대로
	 * {@code hasPreviouslyKilledDragon} 은 <b>거짓으로 되돌리는 자리가 한 곳도 없어</b> 그것으로
	 * 갈랐으면 이 시험이 무너진다 — 그래서 아래가 그 이름이 쓰이지 않았는지까지 본다.
	 */
	@Test
	void 크리스탈로_되살린_드래곤에는_창이_다시_뜬다() {
		// 되살리는 중 — 아직 드래곤이 없고 바닐라 깃발도 참이다. 이때는 열지 않는다.
		assertFalse(DragonTrialManager.endFightLive(false, true, false),
				"되살리는 연출 중에 전투를 열면 드래곤 없는 전투가 또 열린다");
		// 되살아난 그 틱 — 바닐라가 깃발을 스스로 내리고 드래곤을 만든다.
		assertTrue(DragonTrialManager.endFightLive(true, false, false),
				"되살아난 드래곤에는 창이 다시 떠야 한다. 새 전투다");

		String bytes = classBytes(DragonTrialManager.class, "DragonTrialManager");
		assertTrue(bytes.contains("sharedfate$dragonKilled"),
				"바닐라의 전투별 깃발을 안 읽는다 — 되살리기를 알아볼 수단이 사라졌다");
		assertFalse(bytes.contains("hasPreviouslyKilledDragon"),
				"「이 월드에서 잡아 본 적이 있는가」로 가르고 있다 — 그 칸은 영영 안 내려가므로"
						+ " 크리스탈로 되살려도 창이 다시 뜨지 않는다");
	}

	/**
	 * <b>첫째 겹 — 수락창을 띄우지 않는다.</b>
	 *
	 * <p>⚠ 수락창만 건너뛰면 안 된다. 창을 안 띄우면 {@code detectArrival} 이 아래 예전 길
	 * ({@code PENDING_SUMMON} → {@code startSession})로 그대로 흘러가 <b>같은 고리가 로그
	 * 글자만 바꿔서 돈다.</b> 그래서 메서드를 통째로 돌아가는지를 본다.
	 */
	@Test
	void 드래곤이_죽은_뒤에는_수락창이_뜨지_않는다() {
		String source = sourceText("src/main/java/com/sharedfate/sync/DragonTrialManager.java");
		int at = source.indexOf("private static void detectArrival(");
		assertTrue(at > 0, "입장 감지가 사라졌다");
		int body = source.indexOf('{', at);
		String head = source.substring(body, Math.min(source.length(), body + 400));
		assertTrue(head.contains("endFightLive(end)"),
				"입장 감지가 「이 월드의 드래곤전이 이미 끝났는가」를 묻지 않는다 — 무한 고리가"
						+ " 돌아온다");
		assertTrue(head.contains("return"),
				"창만 건너뛰면 예전 소환 길로 흘러가 같은 고리가 돈다 — 통째로 돌아가야 한다");
	}

	/**
	 * <b>둘째 겹 — 죽은 드래곤·끝난 전투로는 전투가 열리지 않는다.</b>
	 *
	 * <p>⚠ 이 문을 지나는 길이 셋이고 그중 하나가 <b>{@code /shareteam trialtest start}</b>
	 * ({@code forceStart}) 라, 첫째 겹을 지나지 않는 길이 실제로 있다. 명령으로도 같은 고리를
	 * 만들 수 없어야 한다.
	 */
	@Test
	void 죽은_드래곤으로는_전투가_열리지_않는다() {
		String source = sourceText("src/main/java/com/sharedfate/sync/DragonTrialManager.java");
		int at = source.indexOf("private static boolean startSession(");
		assertTrue(at > 0, "startSession 이 참·거짓을 돌려주지 않는다 — 거절할 길이 없다");
		int body = source.indexOf('{', at);
		String head = source.substring(body, Math.min(source.length(), body + 600));
		assertTrue(head.contains("endFightLive(end)"),
				"전투를 여는 자리가 「이미 끝났는가」를 묻지 않는다");
		assertTrue(head.contains("return false"), "거절하지 않고 그대로 열고 있다");
	}

	/** {@code /trialtest start} 도 둘째 겹을 지난다. 명령이 전투를 직접 열지 않는다. */
	@Test
	void 명령으로도_같은_고리를_만들_수_없다() {
		String source = sourceText("src/main/java/com/sharedfate/sync/DragonTrialManager.java");
		int at = source.indexOf("public static boolean forceStart(");
		assertTrue(at > 0, "forceStart 가 참·거짓을 돌려주지 않는다 — 명령이 실패를 알 수 없다");
		String body = source.substring(at, Math.min(source.length(), at + 1200));
		assertTrue(body.contains("if (!startSession("),
				"명령이 startSession 의 거절을 무시한다 — 거절된 틱에도 팀을 엔드로 끌어간다");
		String command = sourceText("src/main/java/com/sharedfate/command/DragonTrialCommand.java");
		assertTrue(command.contains("if (!DragonTrialManager.forceStart("),
				"명령이 실패를 사람에게 안 알린다 — 「명령이 안 듣는다」로 시간을 버린다");
	}

	/**
	 * ⚠⚠ <b>고리가 돌 수 없다 — 「확인 → 전투 → 즉시 처치 → 다시 수락창」의 모든 고비에
	 * 문이 있다.</b>
	 *
	 * <p>갈래 하나하나가 아니라 <b>네 고비가 모두 막혀 있는지</b>를 붙든다. 하나만 남겨 두면
	 * 그 길로 같은 고리가 돈다.
	 *
	 * <ol>
	 *   <li>창을 띄우는 자리({@code detectArrival})</li>
	 *   <li>전투를 여는 자리({@code startSession}) — 「처치 판정」이 세션에서 나오므로 세션이
	 *       안 열리면 3·4번이 통째로 사라진다</li>
	 *   <li>이미 떠 있는 창({@code tickEntranceGates}) — 붙들기를 푼다</li>
	 *   <li>팀을 옮기는 자리 — 거절된 틱에는 옮기지 않는다</li>
	 * </ol>
	 */
	@Test
	void 확인_전투_처치_수락창_고리가_돌_수_없다() {
		String source = sourceText("src/main/java/com/sharedfate/sync/DragonTrialManager.java");
		int guards = 0;
		int from = 0;
		while (true) {
			int at = source.indexOf("endFightLive(end)", from);
			if (at < 0) {
				break;
			}
			guards++;
			from = at + 1;
		}
		assertTrue(guards >= 3,
				"끝난 전투를 묻는 자리가 " + guards + " 곳뿐이다 — 창·전투·떠 있는 창 셋 모두에"
						+ " 있어야 한다");
		// ⚠ 거절을 무시하는 호출이 하나라도 남으면 그 길로 고리가 돈다.
		assertFalse(source.contains("\t\t\tstartSession(server, end, team"),
				"startSession 의 거절을 무시하는 호출이 남아 있다");
		assertFalse(source.contains("\t\tstartSession(server, end, team"),
				"startSession 의 거절을 무시하는 호출이 남아 있다");
	}

	/**
	 * ⚠⚠ <b>잡은 뒤 엔드에 남은 사람이 붙들린 채로 남지 않는다.</b>
	 *
	 * <p>고리를 끊는 것만으로는 모자라다. <b>이미 떠 있는 창</b>은 {@link TrialEntranceGate#tick}
	 * 이 넷 가운데 하나가 될 때까지 사람을 붙들어 두므로, 그 사이에 드래곤이 죽으면 최대 60초를
	 * 붙들린 채로 남는다 — 공유 체력 판에서 조작이 안 되는 60초는 그 자체로 사고다.
	 *
	 * <p>{@link TrialEntranceGate#cancel} 이 창을 버리면 {@link TrialEntranceGate#tick} 이 그
	 * 팀을 통째로 건너뛰므로 <b>다음 틱부터 아무도 안 붙들려 있다</b> — 붙들기는 매 틱 다시 거는
	 * 것이라 되돌릴 것이 없다.
	 */
	@Test
	void 잡은_뒤_엔드에_남은_사람이_붙들린_채로_남지_않는다() {
		UUID teamId = UUID.randomUUID();
		// 창이 없는 팀을 접어도 터지지 않는다. 월드 없이 부를 수 있는 자리까지만 본다.
		TrialEntranceGate.cancel(teamId, null);
		assertFalse(TrialEntranceGate.isOpen(teamId));
		TrialEntranceGate.cancel(null, null);
		assertTrue(TrialEntranceGate.openTeams().isEmpty());

		String source = sourceText("src/main/java/com/sharedfate/sync/DragonTrialManager.java");
		int at = source.indexOf("private static void tickEntranceGates(");
		assertTrue(at > 0, "떠 있는 창을 돌리는 자리가 사라졌다");
		String body = source.substring(at, Math.min(source.length(), at + 2200));
		int cancel = body.indexOf("TrialEntranceGate.cancel(");
		int tick = body.indexOf("TrialEntranceGate.tick(");
		assertTrue(cancel > 0,
				"드래곤이 죽어도 떠 있는 창을 접지 않는다 — 사람이 60초를 붙들린 채로 남는다");
		assertTrue(tick > 0);
		assertTrue(cancel < tick,
				"창을 접는 검사가 tick 보다 뒤에 있다 — 그 틱에 한 번 더 붙들린다");

		String bytes = classBytes(TrialEntranceGate.class, "TrialEntranceGate");
		assertTrue(bytes.contains("cancel"), "밖에서 창을 접는 길이 사라졌다");
		assertTrue(bytes.contains("TrialEntranceClosePayload"),
				"접으면서 남의 화면을 닫지 않는다 — 화면만 남아 조작이 안 되는 것으로 보인다");
	}

	/**
	 * ⚠⚠ <b>새 접근자 믹스인이 등록되어 있다.</b>
	 *
	 * <p>{@code EnderDragonFightAccessor} 가 {@code sharedfate.mixins.json} 에 없으면 바닐라
	 * 깃발을 <b>영영 못 읽는다.</b> 코드가 캐스팅이 아니라 {@code instanceof} 라 서버가 터지지는
	 * 않지만(그쪽 주석), 그러면 수단 셋 가운데 하나가 조용히 빠진 채로 돌아간다 — 이 저장소가
	 * 가장 싫어하는 모양이다.
	 *
	 * <p>⚠ 등록된 믹스인은 시험에서 <b>반사로 못 읽는다.</b> 그래서 등록 파일의 글자를 본다.
	 */
	@Test
	void 전투_깃발_접근자가_등록되어_있다() {
		String registry = sourceText("src/main/resources/sharedfate.mixins.json");
		assertTrue(registry.contains("\"EnderDragonFightAccessor\""),
				"sharedfate.mixins.json 에 \"EnderDragonFightAccessor\" 줄을 넣어야 한다 —"
						+ " 없으면 바닐라 전투 깃발을 못 읽는다");
		String accessor = sourceText(
				"src/main/java/com/sharedfate/mixin/EnderDragonFightAccessor.java");
		assertTrue(accessor.contains("@Accessor(\"dragonKilled\")"),
				"읽는 칸이 dragonKilled 가 아니다");
		assertTrue(accessor.contains("@Mixin(EnderDragonFight.class)"),
				"⚠ 대상이 EnderDragonFight 자신이어야 한다 — 그 칸이 선언된 클래스다."
						+ " 상위 클래스를 겨냥하면 믹스인 적용 단계에서 죽는다");
	}

	// ------------------------------------------------------------------ 도우미

	private static String classBytes(Class<?> owner, String simpleName) {
		try (InputStream in = owner.getResourceAsStream(
				"/com/sharedfate/sync/" + simpleName + ".class")) {
			if (in == null) {
				return fail(simpleName + " 의 클래스 파일을 찾지 못했다");
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		} catch (IOException failed) {
			return fail(failed);
		}
	}

	/** 주석은 클래스 파일에 남지 않는다. 판단의 근거를 붙들려면 소스를 읽어야 한다. */
	private static String sourceText(String relative) {
		java.nio.file.Path here = java.nio.file.Path.of("").toAbsolutePath();
		for (java.nio.file.Path at = here; at != null; at = at.getParent()) {
			java.nio.file.Path candidate = at.resolve(relative);
			if (java.nio.file.Files.isRegularFile(candidate)) {
				try {
					return java.nio.file.Files.readString(candidate, StandardCharsets.UTF_8);
				} catch (IOException broken) {
					return fail(broken);
				}
			}
		}
		return fail(relative + " 을 찾지 못했다. 작업 디렉터리: " + here);
	}
}
