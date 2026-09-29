package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkClientRules;
import com.sharedfate.perk.PerkManager;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public final class SharedFateNetworking {
	// 6: 증강(Perk) 페이로드 3종 추가
	// 7: TeamSyncPayload 에 공유 레벨·다음 증강 레벨 추가
	// 8: 강제 증강 선택 — PerkOfferPayload 에 forced·remainingTicks 추가,
	//    PerkCloseOfferPayload 신설
	// 9: PerkOfferPayload.PerkOption 에 카드 아이콘(아이템 이름) 추가
	// 10: 클라이언트가 있어야 하는 증강 2종(double_jump / hide_hud) —
	//     PerkClientFeaturesPayload(S2C) 와 DoubleJumpPayload(C2S) 신설.
	//     기존 페이로드의 형식은 그대로지만, 이 패킷을 모르는 클라이언트는 공중 점프가
	//     조용히 안 되고 HUD 가림도 걸리지 않는다. 그 상태로 붙어 있는 편이 더 나쁘므로
	//     악수 단계에서 걸러지도록 번호를 올린다.
	// 11: /shareteam 화면 — TeamSyncPayload 에 팀 이름·최대 체력·교환 주기·증강 사용
	//     여부·리더 UUID 를 추가하고 OpenTeamScreenPayload 를 신설했다.
	//     TeamSyncPayload 의 형식 자체가 바뀌었으므로 예전 클라이언트는 읽지 못한다.
	// 12: 증강 선택 연출 — PerkDrawPayload(선택자 뽑기)·PerkResultPayload(고른 카드 보여주기)
	//     신설, PerkSyncPayload 가 이름만이 아니라 설명·등급까지 담도록 바뀌었다.
	// 13: 피격·사망 알림을 팀 생성 시 정하는 설정으로 —
	//     TeamSyncPayload 의 perksEnabled 자리가 Options(perks/damageAlert/deathAlert)
	//     중첩 묶음으로 바뀌었고 TeamWipePayload 를 신설했다. TeamSyncPayload 의 형식
	//     자체가 바뀌었으므로 예전 클라이언트는 읽지 못한다.
	// 14: 증강 후보 다시 뽑기 — PerkRerollC2SPayload(C2S) 를 신설하고 PerkOfferPayload 에
	//     이번 회차에 남은 다시 뽑기 횟수를 실었다. PerkOfferPayload 의 형식 자체가 바뀌었으므로
	//     예전 클라이언트는 선택창을 아예 읽지 못한다.
	// 15: 「게임 시작」 — 회차가 팀에 붙었다. TeamSyncPayload 의 Options 묶음에
	//     runStarted 를 더해 클라이언트가 「시작 대기」인지 알 수 있게 했다. 팀 화면의
	//     「게임 시작」 단추를 그릴지 정하는 값이라 이것 없이는 화면을 만들 수 없다.
	//     TeamSyncPayload 의 형식 자체가 바뀌었으므로 예전 클라이언트는 팀 동기화를
	//     아예 읽지 못한다.
	// 16: 인챈트 다이아몬드 칸 — EnchantmentMenu 에 칸이 하나 늘었고, 확장 27칸이 창
	//     오른쪽 바깥에서 플레이어 인벤토리 아래로 옮겨졌다. 묶음 형식은 그대로지만
	//     서버와 클라이언트의 슬롯 수가 다르면 클라이언트가
	//     IndexOutOfBoundsException 으로 죽는다. 막을 수단이 악수뿐이라 번호를 올린다.
	// 17: 팀 화면 「능력치」 탭의 공격력 — AttackDamagePayload(S2C) 를 신설했다.
	//     바닐라가 minecraft:attack_damage 만은 클라이언트에 동기화하지 않아
	//     (Attributes 에서 이 속성만 setSyncable(true) 없이 등록된다) 서버가 따로 보낸다.
	//     기존 페이로드의 형식은 한 바이트도 바뀌지 않았지만, 이 패킷을 모르는 클라이언트는
	//     능력치 탭에서 공격력 줄만 조용히 빠진 화면을 보게 된다. 「값이 안 보인다」는
	//     「모드가 안 맞는다」보다 알아채기 어려우므로 악수 단계에서 걸러지게 한다.
	// 18: 능력치가 여덟 줄이 되었다 — AttackDamagePayload 가 StatSnapshotPayload 로 바뀌면서
	//     받는 피해 배율과 몹 최대 체력·공격력 배율 셋이 더 실린다(4바이트 → 20바이트).
	//     셋 다 서버만 아는 값이다. 증강이 만드는 배율은 바닐라 속성이 아니고, 몹 배율은
	//     사람이 아니라 몹에게 붙어 클라이언트에 흔적이 없다. 형식 자체가 바뀌었으므로
	//     예전 클라이언트는 이 묶음을 읽지 못한다.
	//     공격 속도(minecraft:attack_speed)는 여기 없다 — 그 속성만은 공격력과 달리
	//     setSyncable(true) 로 등록되어 수정자까지 클라이언트에 그대로 온다.
	// 19: 인챈트 요구 개수를 화면에 알리는 칸 — 골드 「비술 공방」이 팀마다 인챈트 값을
	//     바꾸므로 그 값을 클라이언트가 알아야 툴팁과 단추 숫자가 맞는다. 새 패킷을 만드는
	//     대신 EnchantmentMenu 의 데이터 칸을 하나 더 달았고, 그래서 칸이 10개에서 11개가
	//     되었다. 16번과 똑같은 이유다 — 서버와 클라이언트의 칸 수가 다르면 클라이언트가
	//     IndexOutOfBoundsException 으로 죽고, 막을 수단이 악수뿐이다.
	// 20: 세트 효과 — 새 S2C 패킷(perk_sets)과 선택 카드의 유형 줄. 클라이언트는 증강 풀을
	//     아예 읽지 않아(PerkRegistry.load 는 서버 전용) 「어느 유형을 몇 개 가졌나」도
	//     「그 유형에 무엇이 더 있나」도 스스로 셀 수 없다. 게다가 PerkOfferPayload.PerkOption
	//     에 유형 칸이 하나 늘어 형식 자체가 바뀌었다 — 옛 클라이언트는 선택창 패킷을 못 읽는다.
	// 21: 세트 줄에 「주기」 칸이 하나 늘었다(PerkSetSyncPayload.SetLine). HUD 의 「보급」 줄이
	//     다음 보급까지 남은 시간을 그리는 데 쓴다. 남은 시간을 싣지 않고 주기만 싣는 이유는
	//     com.sharedfate.ui.SupplyCountdown 에 적어 두었다 — 남은 시간은 초마다 달라져
	//     이름표 백 몇 줄이 함께 재전송된다. 칸이 하나 늘어 형식이 바뀌었으므로 옛 클라이언트는
	//     이 패킷을 못 읽는다.
	// 22: 세트 줄에 「켜진 시점」 칸이 하나 늘었다(PerkSetSyncPayload.SetLine.anchorTick).
	//     보급 주기의 경계가 「게임 시간의 배수」에서 「보급이 켜진 시점부터 주기마다」로
	//     바뀌었고, 그 기준 자리를 클라이언트도 알아야 화면의 시계와 실제 보급 시각이 맞는다.
	//     남은 시간이 아니라 기준 자리를 싣는 이유는 21번과 같다. 칸이 하나 늘어 형식이
	//     바뀌었으므로 옛 클라이언트는 이 패킷을 못 읽는다.
	// 24: 골드 「폭발 교환」의 혜택으로 다음 위치 교환까지 남은 시간을 화면 왼쪽 위에 늘
	//     그린다(SwapTimerPayload 신설). 새 묶음이라 기존 형식은 그대로지만, 이 패킷을 모르는
	//     클라이언트는 혜택 없이 대가만 치르게 되므로 악수 단계에서 걸러지도록 번호를 올린다.
	// 25: 두 가지 때문이다.
	//     (1) 0.25.2-dev 가 고친 것이 「화면 왼쪽 위가 조용히 안 보이는」 클라이언트 버그인데
	//         규약을 안 올렸다. 그래서 고장난 클라이언트가 그대로 접속할 수 있었고, 본인도
	//         서버 운영자도 알아채지 못한 채 계속 플레이하게 된다. 17·24번과 똑같은
	//         경우였으므로 그때 올렸어야 했다. 뒤늦게 여기서 올린다.
	//     (2) ClientVersionPayload(C2S) 를 신설했다. 이 패킷을 모르는 클라이언트는 자기 판을
	//         알리지 않아 서버 로그에 줄이 빠지는데, 「로그에 아무 줄도 없다」와 「이 사람은
	//         옛 클라이언트다」를 구분할 수 없게 된다. 진단하려고 넣은 것이 진단을 헷갈리게
	//         만드는 셈이라, 이 판부터는 모두가 보내는 것이 보장되어야 한다.
	//
	//     ★ 규칙: 클라이언트가 **조용히 덜 동작하는** 판은 형식이 안 바뀌어도 번호를 올린다.
	//       「값이 안 보인다」는 「모드가 안 맞는다」보다 알아채기 훨씬 어렵다.
	// 26: PerkSyncPayload.Owned 에 세트 유형 칸이 하나 늘었다. 보유 증강 목록에 「짐꾼 가호」
	//     처럼 유형 딱지를 붙이려면 클라이언트가 그 값을 알아야 하는데, 클라이언트는 증강 풀을
	//     읽지 않으므로 스스로 셀 수 없다. 칸이 늘어 형식이 바뀌었으니 옛 클라이언트는 이
	//     패킷을 못 읽는다.
	// 27: 모루에 다이아몬드 칸이 하나 늘었다. 인챈트 테이블을 다이아 값으로 바꾸며 규약을
	//     19로 올렸던 것과 똑같은 이유다 — 메뉴의 칸 수는 서버와 클라이언트가 같아야 하고,
	//     그 칸을 모르는 클라이언트는 칸 동기화가 어긋나 창이 깨지거나 아이템이 엉뚱한 자리로
	//     간다. 형식이 바뀌는 것은 아니지만 **막지 않으면 조용히 망가지는** 쪽이라 번호를
	//     올려 악수 단계에서 걸러낸다(위 ★ 규칙).
	// 28: 형식도 안 바뀌었고 클라이언트가 덜 동작하지도 않는다. 위 ★ 규칙에 해당하지 않는데도
	//     올린 유일한 번호다 — **운영자가 판을 통일하려고** 올렸다(0.28.0-dev).
	//     0.28.0-dev 가 고친 것(침대로 잡은 드래곤의 처치자 판정)과 새로 넣은 것
	//     (/shareteam reset)은 **둘 다 서버 안쪽**이라, 규약으로 따지면 27 그대로 두는 것이
	//     맞다. 그러나 그렇게 두면 서버만 올린 채 옛 클라이언트가 섞여 들어오고, 누가 어떤
	//     판인지는 /shareteam version all 을 쳐 봐야만 안다.
	//
	//     ★ 규칙과 이유가 다르다는 것을 알고 올린 것이니, 다음에 이 줄을 근거로
	//       「기능이 서버 안쪽이어도 번호를 올린다」고 일반화하지 말 것. 판을 강제로 맞출
	//       이유가 있을 때만 그렇게 한다.
	// 29: WorldResetPayload 에 「왜 서버가 내려가는가」 칸이 하나 늘었다
	//     (GameOverCountdown.Reason — 전멸 / 운영자 초기화).
	//     28에서 넣은 /shareteam reset 이 전멸 연출을 그대로 빌려 쓰는 바람에, 운영자가 서버를
	//     초기화하면 **살아 있는 팀원 전원의 화면 한가운데에 「게임 오버」가 크게 떴다.** 아무도
	//     죽지 않았는데 전멸한 줄 아는 것이다. 클라이언트에는 두 경로를 가를 근거가 없었다 —
	//     회차도 남은 틱도 양쪽이 똑같이 채우므로 받는 쪽에서는 구분할 방법이 아예 없었고,
	//     그래서 글자를 가르려면 서버가 이유를 실어 보내는 수밖에 없다.
	//     칸이 하나 늘어 형식 자체가 바뀌었으므로 옛 클라이언트는 이 묶음을 읽지 못한다.
	//     값을 숫자가 아니라 이름("team_wipe"/"run_reset")으로 싣는 이유는
	//     GameOverCountdown.Reason.id() 에 적어 두었다.
	// 30: 엔드 시련 룰렛 화면(TrialRoulettePayload 신설).
	//     시련은 처음에 타이틀 글자를 갈아 끼우는 것으로 만들어 규약을 올리지 않았다. 그쪽이
	//     싸고, 배포판 클라이언트로 그대로 시험할 수 있다는 이점이 컸다. 그런데 **드래곤이
	//     때리는 중에 화면 위로 지나가는 글자는 읽히지 않는다** — 무엇을 받았는지 모른 채
	//     싸우게 되고, 이 모드에서 전멸은 월드 삭제다. 사람이 「증강 고르는 것처럼 멈추고
	//     뽑는다」로 정했다(2026-09-29).
	//     새 묶음이 하나 늘었으므로 옛 클라이언트는 이것을 읽지 못한다. 시련은 **브랜치
	//     `feature/dragon-trials` 안에만 있고 main 에 올리지 않으므로**, 이 번호가 배포판으로
	//     나가는 것은 시련을 실제로 내보내기로 정한 뒤다.
	public static final int PROTOCOL_VERSION = 30;

	private SharedFateNetworking() {
	}

	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(DamageAlertPayload.TYPE, DamageAlertPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SelectedSlotPayload.TYPE, SelectedSlotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TeamSyncPayload.TYPE, TeamSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(WorldResetPayload.TYPE, WorldResetPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TeamWipePayload.TYPE, TeamWipePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(SwapTimerPayload.TYPE, SwapTimerPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PerkOfferPayload.TYPE, PerkOfferPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				TrialRoulettePayload.TYPE, TrialRoulettePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PerkSyncPayload.TYPE, PerkSyncPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				PerkCloseOfferPayload.TYPE, PerkCloseOfferPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				PerkClientFeaturesPayload.TYPE, PerkClientFeaturesPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				OpenTeamScreenPayload.TYPE, OpenTeamScreenPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				StatSnapshotPayload.TYPE, StatSnapshotPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(PerkDrawPayload.TYPE, PerkDrawPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				PerkResultPayload.TYPE, PerkResultPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				PerkSetSyncPayload.TYPE, PerkSetSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SelectedSlotC2SPayload.TYPE, SelectedSlotC2SPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PerkChoiceC2SPayload.TYPE, PerkChoiceC2SPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				PerkRerollC2SPayload.TYPE, PerkRerollC2SPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DoubleJumpPayload.TYPE, DoubleJumpPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				ClientVersionPayload.TYPE, ClientVersionPayload.CODEC);
		PayloadTypeRegistry.clientboundConfiguration().register(HandshakePayload.TYPE, HandshakePayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(SelectedSlotC2SPayload.TYPE,
				(payload, context) -> TeamBroadcaster.reportSelectedSlot(
						context.server(), context.player(), payload.slot()));
		ServerPlayNetworking.registerGlobalReceiver(PerkChoiceC2SPayload.TYPE,
				(payload, context) -> PerkManager.applyChoice(
						context.player(), payload.milestone(), payload.perkId()));
		// 다시 뽑기 요청. 남은 횟수 검사도 재추첨도 전부 서버가 한다. 클라이언트는 눌렀다는
		// 사실과 어느 창에서 눌렀는지만 보낸다.
		ServerPlayNetworking.registerGlobalReceiver(PerkRerollC2SPayload.TYPE,
				(payload, context) -> PerkManager.applyReroll(
						context.player(), payload.milestone()));
		// 공중 점프 요청. 세기도 가능 여부도 전부 서버가 다시 따진다.
		ServerPlayNetworking.registerGlobalReceiver(DoubleJumpPayload.TYPE,
				(payload, context) -> PerkClientRules.onDoubleJumpRequest(context.player()));
		// 클라이언트가 알려 준 자기 판. 로그에 적고 ClientVersionRegistry 에 기억해 둔다 —
		// 이 값으로 아무 판단도 하지 않는 것은 그대로다. 막는 일은 규약 번호가 한다.
		// 「누가 어떤 클라이언트를 쓰는지」를 서버에서 알 수 없어 진단이 오래 걸렸던 적이 있고,
		// 로그는 서버를 켤 수 있는 사람만 본다. 기억해 두면 /shareteam version 이 게임 안에서
		// 같은 물음에 답할 수 있다.
		ServerPlayNetworking.registerGlobalReceiver(ClientVersionPayload.TYPE,
				(payload, context) -> {
					String version = sanitizeVersion(payload.version());
					ClientVersionRegistry.remember(context.player().getUUID(), version);
					SharedFateMod.LOGGER.info("[CLIENT] {} — sharedfate {}",
							context.player().getPlainTextName(), version);
				});
		ServerTickEvents.END_SERVER_TICK.register(TeamBroadcaster::flushSelectedSlots);
		ServerTickEvents.END_SERVER_TICK.register(TeamBroadcaster::flushTeamLevels);
		// 서버만 아는 능력치(공격력·받는 피해 배율·몹 배율). 팀에 속하지 않은 사람도
		// 능력치 표시에서 이 줄들을 보므로 팀 경로가 아니라 여기에 있다.
		ServerTickEvents.END_SERVER_TICK.register(StatSnapshotBroadcaster::flush);
		// 세트 효과의 지금 상태. 세트는 저장하지 않는 파생 상태이고 보유 목록이 바뀌는
		// 자리가 넷이라, 사건마다 거는 대신 결과를 견주어 달라졌을 때만 보낸다.
		ServerTickEvents.END_SERVER_TICK.register(PerkSetBroadcaster::flush);
		// 클라이언트가 있어야 하는 증강(double_jump / hide_hud)의 동기화·접지 판정 지점.
		// SharedFateMod 가 아니라 여기서 거는 이유는 이 기능이 네트워크 경로 하나로만
		// 성립하기 때문이다. 패킷 등록과 같은 자리에 두면 한쪽만 빠뜨릴 수 없다.
		ServerTickEvents.END_SERVER_TICK.register(PerkClientRules::tick);
		// 다시 접속했을 때 "이미 보냈다"고 착각하지 않도록 나갈 때 기록을 버린다.
		// 클라이언트는 월드에서 나가며 받은 값을 모두 버리므로, 서버가 기억을 들고 있으면
		// 상태가 그대로인 사람은 다시 들어와도 값을 영영 받지 못한다.
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			PerkClientRules.forget(handler.player.getUUID());
			StatSnapshotBroadcaster.forget(handler.player.getUUID());
			PerkSetBroadcaster.forget(handler.player.getUUID());
			// 나간 사람의 판을 남겨 두면 목록에 다시 들어오지 않은 사람의 옛 값이 계속 뜬다.
			ClientVersionRegistry.forget(handler.player.getUUID());
		});
		ClientModGate.register();
	}

	/**
	 * 클라이언트가 보낸 판 문자열을 <b>로그에 적어도 안전한 모양</b>으로 다듬는다.
	 *
	 * <p>밖에서 온 문자열이다. 줄바꿈이 섞이면 로그 한 줄이 여러 줄로 쪼개져 <b>없던 줄을 지어낸
	 * 것처럼</b> 보일 수 있다. 길이는 코덱이 이미 {@link ClientVersionPayload#MAX_LENGTH} 로
	 * 자르지만, 눈에 보이지 않는 글자는 여기서 건다.
	 */
	static String sanitizeVersion(String raw) {
		if (raw == null || raw.isBlank()) {
			return "(알 수 없음)";
		}
		StringBuilder clean = new StringBuilder(raw.length());
		raw.codePoints().forEach(code -> {
			if (Character.isISOControl(code)) {
				clean.append('?');
			} else {
				clean.appendCodePoint(code);
			}
		});
		return clean.toString();
	}
}
