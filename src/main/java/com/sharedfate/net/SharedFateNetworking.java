package com.sharedfate.net;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkChoiceSession;
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
	// 31: 시련 「굳는 손」이 굳혀 둔 핫바 칸(TrialHotbarLockPayload 신설).
	//     막는 일은 전부 서버가 한다 — 이 묶음은 **붉은 표시 하나만** 나른다. 그래서 못 읽는
	//     클라이언트는 손이 똑같이 굳는데 화면에는 아무 표시가 없다. 어느 칸이 굳었는지 모른 채
	//     30초마다 두 칸이 옮겨 다니는 것이라 사람은 「모드가 고장 났다」로 읽는다. 위 ★ 규칙이
	//     말하는 **조용히 덜 동작하는** 경우 그대로다.
	//     새 묶음이 하나 늘었으므로 옛 클라이언트는 이것을 읽지 못한다. 30 과 마찬가지로 시련은
	//     브랜치 `feature/dragon-trials` 안에만 있고, 이 번호가 배포판으로 나가는 것은 시련을
	//     실제로 내보내기로 정한 뒤다. ⚠ 올린 이상 **서버와 클라이언트 jar 을 둘 다 바꿔야
	//     한다** — 한쪽만 올리면 악수 단계에서 걸려 아예 못 들어온다.
	// 32: 시련 자리가 **룰렛 말고 다른 방식으로도 뜬다**(TrialCatalog.Reveal).
	//     TrialRoulettePayload 에 「결과를 붙잡아 두는 시간」 칸이 하나 늘었다(holdTicks).
	//
	//     자리 여섯이 전부 룰렛이었을 때는 붙잡는 시간이 60틱 하나여서, 화면이
	//     TrialFreeze.HOLD_TICKS 를 직접 읽어 쓰는 것으로 서버와 맞출 수 있었다. 이제 체력 80%
	//     자리는 카드가 한 장뿐이라 **굴리지 않고**(spinTicks=0) 정해진 카드의 이름과 설명만
	//     보여 주며, 그 자리는 굴림이 빠진 만큼 읽는 시간을 따로 잡는다
	//     (TrialFreeze.FIXED_HOLD_TICKS, 100틱). 상수 하나를 읽어서는 둘을 맞출 수 없고,
	//     화면이 자리마다 골라 읽게 하면 「어느 자리인가」를 화면도 알아야 한다. 그래서 서버가
	//     **실제로 얼려 두는 시간**을 그대로 실어 보낸다.
	//
	//     새 묶음을 만들지 않고 칸을 더한 것은 **화면 코드를 한 벌로 남기기 위해서**다. 묶음을
	//     가르면 「긴 이름이 판 밖으로 나가지 않게 배율을 낮춘다」 같은 고침이 한쪽에만 들어간다.
	//     굴릴 것인가 말 것인가는 spinTicks 하나로 말한다 — 0 이면 굴리지 않는다. 그 값을 따로
	//     두면 둘이 어긋났을 때(굴림 0 인데 굴리라고 할 때) 화면이 무엇을 할지 알 수 없어진다.
	//     체력 30% 자리(Reveal.SILENT)는 **이 묶음을 아예 안 보낸다** — 아무것도 멈추지 않는다.
	//
	//     칸이 하나 늘어 형식 자체가 바뀌었으므로 옛 클라이언트는 시련 화면 묶음을 못 읽는다.
	//     30·31 과 마찬가지로 시련은 브랜치 `feature/dragon-trials` 안에만 있고, 이 번호가
	//     배포판으로 나가는 것은 시련을 실제로 내보내기로 정한 뒤다. ⚠ 서버와 클라이언트 jar 을
	//     둘 다 바꿔야 한다.
	// 33: 엔더 드래곤 시련을 <b>팀 설정으로</b> 켜고 끈다. 기본값은 끔이고, 끄면 최종 보스가
	//     바닐라 엔더 드래곤전이다(체력 200 · 카드 없음 · 패시브 없음).
	//
	//     이 설정이 서버로 가는 길은 패킷이 아니라 **명령 한 줄**이다. 팀 만들기 탭은 예전부터
	//     /shareteam create ... 를 그대로 보내고 있고, 여기에 낱말 하나(dragontrials on|off)가
	//     늘었을 뿐이다. 그 낱말만 보면 규약을 올릴 이유가 없다.
	//
	//     올린 이유는 위 ★ 규칙이다 — **양쪽 다 조용히 어긋난다.**
	//       · 옛 클라이언트 + 새 서버: 만들기 탭에 줄이 일곱뿐이라 낱말을 안 보낸다. 설정이
	//         빠지면 서버는 기본값(끔)을 쓰므로, 그 사람은 **시련을 켤 방법이 아예 없는데
	//         아무 오류도 보지 못한다.** 엔드에 가서 카드가 안 뜨는 것으로만 알게 된다.
	//       · 새 클라이언트 + 옛 서버: 보내는 낱말을 명령 트리가 모른다. 팀 만들기가 브리가디어
	//         오류로 실패하는데, 그 글은 「왜 안 되는지」를 말해 주지 않는다.
	//
	//     겸해서 TeamSyncPayload.Options 에 칸이 하나 늘었다(dragonTrials). 클라이언트는 이
	//     값으로 아무 판단도 하지 않는다 — 시련을 거는 일은 전부 서버가 한다. 설정 탭에 한 줄을
	//     적기 위한 것이고, 그 한 줄이 **기본값이 끔이라 생기는 「왜 시련이 안 뜨지」**에 게임
	//     안에서 답하는 가장 싼 길이다. 칸이 늘어 형식 자체가 바뀌었으므로 옛 클라이언트는 팀
	//     동기화를 아예 읽지 못한다.
	//
	//     30·31·32 와 마찬가지로 시련은 브랜치 `feature/dragon-trials` 안에만 있고, 이 번호가
	//     배포판으로 나가는 것은 시련을 실제로 내보내기로 정한 뒤다. ⚠ 서버와 클라이언트 jar 을
	//     둘 다 바꿔야 한다.
	// 34: 증강 선택창에서 <b>선택자가 아닌 사람이 제안</b>할 수 있다
	//     (PerkVoteC2SPayload · PerkVoteSyncPayload 신설).
	//
	//     여태 선택자가 아닌 사람의 창은 <b>보기만 하는 창</b>이었다 — 카드를 눌러도
	//     PerkOfferScreen.clickable() 이 거짓이라 클릭이 통째로 버려졌고, 다시 뽑기 단추는
	//     PerkRerollButton.visible(forced, canChoose) 가 거짓이라 아예 그려지지 않아
	//     <b>남은 리롤 횟수도 보이지 않았다.</b> 그래서 팀은 채팅으로 「2번 눌러」를 외치고
	//     선택자는 그걸 읽으며 골랐다.
	//
	//     이제 카드를 누르면 그 카드 위에 체크가 붙고, 한 명 더 누르면 수가 오른다. 같은
	//     카드를 다시 누르면 취소, 다른 카드를 누르면 그쪽으로 옮겨 간다. 다시 뽑기도 같은
	//     장치를 쓰고, 선택자가 아닌 사람에게도 남은 횟수가 보인다.
	//     ⚠ <b>표는 제안일 뿐이다.</b> 몇 개가 모이든 실제로 고르는 것은 선택자 하나다 —
	//       서버 어디에도 표를 세어 자동으로 정하는 길이 없다(PerkChoiceSession.castVote).
	//
	//     묶음이 둘 는 것뿐이고 기존 형식은 한 바이트도 안 바뀌었다. 그래도 번호를 올리는
	//     것은 위 ★ 규칙 때문이다 — 이 패킷을 모르는 클라이언트는 <b>눌러도 아무 일이 안
	//     일어나는 창</b>을 그대로 보게 되고, 옆 사람 화면에는 체크가 뜨는데 자기 화면에만
	//     안 뜬다. 「내 표가 안 들어간다」는 「모드가 안 맞는다」보다 알아채기 훨씬 어렵고,
	//     알아챌 때쯤엔 이미 엉뚱한 증강이 팀 전체에 붙어 있다.
	//
	//     표를 세는 일은 전부 서버가 한다. 클라이언트가 보내는 것은 「내가 이걸 눌렀다」
	//     하나뿐이고(PerkVoteC2SPayload), 켤지 끌지 옮길지도 서버가 정한다. 클라이언트가
	//     센 수를 실어 보내면 두 사람이 같은 틱에 누를 때 화면마다 다른 수가 뜬다.
	// 35: 「유적 감별사」가 찾아 둔 좌표가 <b>제 칸으로</b> 내려간다
	//     (PerkSyncPayload 에 ruinCoords 칸 하나).
	//
	//     여태는 설명 문자열 뒤에 괄호로 붙여 보냈다 —
	//     {@code "…  (고대 도시  -1234, 567 · 엔더 요새  890, -123)"}. 통신 형식을 한 바이트도
	//     안 늘리려는 임시 방편이었고, 옛 클라이언트도 그냥 글자로 읽어 그렸다.
	//
	//     그래서는 사람이 요청한 <b>「증강 목록 오른쪽에 좌표 두 줄」</b>이 될 수 없다. 오른쪽에
	//     따로 세우려면 클라이언트가 좌표를 <b>설명과 구분해서</b> 받아야 하고, 그것이 곧 통신
	//     형식 변경이다. 설명 안에서 괄호를 찾아 도로 가르는 길도 있지만, 그러면 설명에 괄호를
	//     쓴 증강이 하나라도 생기는 날 화면이 엉뚱한 글자를 좌표로 세운다.
	//
	//     칸은 <b>증강 줄 안이 아니라 묶음 바깥</b>에 뒀다. 유적 좌표 효과는 팀 전체에 하나뿐이라
	//     (PerkRuinSurvey.effectOf) Owned 안에 넣으면 같은 목록이 보유 증강 수만큼 되풀이된다.
	//     상한은 정의가 적을 수 있는 구조물 수와 같은 값이다
	//     (PerkSyncPayload.MAX_RUIN_COORDS = RuinSurveyEffect.MAX_STRUCTURES). 둘을 갈라 두면
	//     정의상 정당한 네 줄짜리 증강이 패킷 인코딩에서 터진다.
	//
	//     칸이 하나 늘어 <b>형식 자체가 바뀌었으므로</b> 옛 클라이언트는 증강 동기화를 아예 읽지
	//     못한다 — 보유 증강 목록과 대기 중인 선택권 수가 통째로 빈다. 위 ★ 규칙이 말하는
	//     「조용히 덜 동작하는」 경우가 아니라 눈에 바로 보이는 고장이지만, 그래도 번호를 올려
	//     악수 단계에서 걸리게 한다.
	//     ⚠ 서버와 클라이언트 jar 을 둘 다 바꿔야 한다.
	//
	//     ⚠ <b>증강을 처음 고를 때 뜨는 채팅 한 줄은 그대로다</b>(PerkRuinSurvey.announce).
	//     사람이 따로 요청한 것이고, 나중에 다시 읽을 수 있는 자리는 채팅뿐이다. 없어진 것은
	//     설명 뒤 괄호 하나이고, 그 자리를 목록 오른쪽 두 줄이 대신한다.
	// 36: 엔드에 처음 들어서면 <b>「시련을 시작합니다」 입장 수락창</b>이 뜬다
	//     (TrialEntranceOfferPayload · TrialEntranceClosePayload · TrialEntranceAcceptC2SPayload,
	//     셋 다 신설).
	//
	//     사람이 정한 것 — 「첫 엔더로 입장하면 한명이라도 엔더에 입장하면 정지하고 (시련중이면)
	//     시련을 시작합니다. 라고 수락창이 뜨고 리더가 확인 을 누루면 버튼은 확인만있지만, 엔더로
	//     진입 엔더 중앙으로 보내 그 기반암 단상있는곳으로 … 그러고 연출보게 하면됨」. 확인한 뒤
	//     중앙으로 보낼 대상을 따로 물었을 때 「팀 전원」이라고 답했다(2026-10-01).
	//
	//     묶음이 셋 늘었을 뿐 기존 형식은 한 바이트도 안 바뀌었다. 그래도 번호를 올리는 것은
	//     위 ★ 규칙 때문이고, 여기서는 그 규칙이 말하는 「조용히 덜 동작하는」 정도가 아니다 —
	//     ⚠⚠ **이 패킷을 모르는 클라이언트는 엔드에 들어간 채로 60초 동안 붙들려 있고 아무
	//     화면도 보지 못한다.** 붙들기는 서버가 하는 일이라 그대로 걸리고, 창이 안 뜨므로 확인을
	//     누를 길도 없다. 리더가 그 클라이언트면 팀 전원이 제한시간을 다 쓴 뒤에야 전투가
	//     시작된다. 악수 단계에서 걸러내는 것이 유일한 답이다.
	//
	//     C2S 가 하나 늘어난 것도 번호를 올릴 이유다. 「확인」은 **리더만** 누를 수 있고 그
	//     판단은 서버에만 있는데(TrialEntranceGate.mayConfirm), 보낼 수 없는 클라이언트에게는
	//     그 사실이 「단추를 눌렀는데 아무 일도 안 일어난다」로 보인다 — 34번이 증강 제안 패킷을
	//     두고 적어 둔 것과 같은 모양이다.
	//
	//     30·31·32·33·34·35 와 마찬가지로 시련은 브랜치 `feature/dragon-trials` 안에만 있고,
	//     이 번호가 배포판으로 나가는 것은 시련을 실제로 내보내기로 정한 뒤다.
	//     ⚠ 서버와 클라이언트 jar 을 **둘 다** 바꿔야 한다. 양쪽을 다 끄고 바꿀 것(안 그러면
	//     ZipException).
	public static final int PROTOCOL_VERSION = 36;

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
		PayloadTypeRegistry.clientboundPlay().register(
				TrialHotbarLockPayload.TYPE, TrialHotbarLockPayload.CODEC);
		// 엔드 입장 수락창. 띄우는 것과 닫는 것을 갈라 둔 까닭은 TrialEntranceClosePayload 에
		// 적어 두었다 — 리더가 1초 만에 누르면 나머지 사람의 창을 닫는 길이 서버뿐이다.
		PayloadTypeRegistry.clientboundPlay().register(
				TrialEntranceOfferPayload.TYPE, TrialEntranceOfferPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(
				TrialEntranceClosePayload.TYPE, TrialEntranceClosePayload.CODEC);
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
		PayloadTypeRegistry.clientboundPlay().register(
				PerkVoteSyncPayload.TYPE, PerkVoteSyncPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(SelectedSlotC2SPayload.TYPE, SelectedSlotC2SPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(PerkChoiceC2SPayload.TYPE, PerkChoiceC2SPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				PerkRerollC2SPayload.TYPE, PerkRerollC2SPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				PerkVoteC2SPayload.TYPE, PerkVoteC2SPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(
				TrialEntranceAcceptC2SPayload.TYPE, TrialEntranceAcceptC2SPayload.CODEC);
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
		// 선택자가 아닌 사람의 제안. PerkManager 를 거치지 않고 세션으로 바로 가는 이유는
		// 표가 저장되는 값이 아니기 때문이다 — 팀 상태(TeamState)에 한 글자도 안 남고
		// 세션이 사는 동안에만 있다가 세션과 함께 사라진다.
		// ⚠ 표는 제안일 뿐이다. 세어서 무엇을 고르는 길은 서버 어디에도 없다.
		ServerPlayNetworking.registerGlobalReceiver(PerkVoteC2SPayload.TYPE,
				(payload, context) -> PerkChoiceSession.castVote(
						context.player(), payload.milestone(), payload.target()));
		// 엔드 입장 수락창의 「확인」. 누를 수 있는 사람인지는 서버가 팀 명단에서 다시 본다 —
		// 클라이언트가 보내는 것은 「눌렀다」와 「어느 창에서」뿐이다
		// (TrialEntranceAcceptC2SPayload). 전투를 여는 일은 여기서 하지 않고 다음 틱의
		// DragonTrialManager 가 한다(TrialEntranceGate.accept 설명).
		ServerPlayNetworking.registerGlobalReceiver(TrialEntranceAcceptC2SPayload.TYPE,
				(payload, context) -> com.sharedfate.sync.TrialEntranceGate.accept(
						context.player(), payload.openedTick()));
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
