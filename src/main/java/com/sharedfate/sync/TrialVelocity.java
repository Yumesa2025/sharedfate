package com.sharedfate.sync;

/**
 * ⚠⚠ 사람에게 <b>속도를 내려 보낼 때</b> 지키는 규약. 지금은 세로 하나다.
 *
 * <h2>왜 파일이 따로인가</h2>
 *
 * <p>{@link #syncedVertical} 은 2026-10-04 에 {@code DragonLastStandPatterns} 안에서 태어났다.
 * 그때는 「최후의 저항」의 밀기와 흡입 둘만 쓰는 함수였는데, <b>같은 날 같은 구멍이 일반 전투에도
 * 남아 있는 것</b>이 드러나 「엔더폭풍」({@code TrialEnderStorm})과 「착지 충격」
 * ({@code TrialLandingShock})도 이 함수를 지나게 됐다. 곧 <b>최후의 저항 전용 함수가 아니다.</b>
 *
 * <p>⚠ 그 둘이 {@code DragonLastStandPatterns} 를 부르게 두면 <b>의존이 거꾸로</b> 흐른다 —
 * 지금은 {@code DragonLastStandPatterns} 가 {@code TrialEnderStorm.pushDistance} 와
 * {@code TrialLandingShock.groundedReach} 를 <b>빌려 쓰는 쪽</b>이고, 그 방향이 「천장은 카드가
 * 만들고 패턴이 물려받는다」는 이 저장소의 약속이다. 반대쪽 화살표를 새로 그으면 다음 사람이
 * 어느 쪽이 빌리는 쪽인지 알 수 없게 된다.
 *
 * <p>⚠⚠ <b>이 저장소는 같은 값이 두 벌이 되는 것을 반복해서 겪었다</b>(단상 계산 세 벌 · 먼지
 * 크기 두 벌 · {@code CRIT} 상수 두 벌). 그래서 자르는 식은 <b>한 벌</b>이고, 부르는 자리가
 * 넷이어도 숫자가 적힌 곳은 여기 하나뿐이다. {@code TrialVelocityTest} 가 <b>다른 어느 파일도 제
 * {@code syncedVertical} 을 선언하지 않는다</b>는 것과 <b>이 파일이 칸을 하나도 들지 않는다</b>
 * (= 천장을 베껴 적지 않았다)는 것을 못박는다.
 *
 * <h2>왜 {@code TrialRisks} 가 아닌가</h2>
 *
 * <p>{@code TrialRisks} 는 「위험 계산이 모이는 자리」이긴 하지만 카드를 <b>돌리는</b> 실행기이고,
 * 이 규약을 지켜야 하는 자리는 그쪽 말고 <b>셋이 더</b> 있다(패턴 둘 · 카드 둘). 「한 위험의
 * 계산」이 아니라 <b>속도를 내려 보내는 모든 자리의 규약</b>이라, 이름으로 찾을 수 있는 자리에
 * 따로 둔다. {@code TrialWarning}(소리·고리 점 같은 연출 도구)에 두는 것은 더 멀다.
 *
 * <h2>지키지 않는 자리 둘 — 일부러다</h2>
 *
 * <ul>
 *   <li>{@code TrialRisks.launch} 는 <b>세로와 수평을 둘 다 덮어쓴다</b>
 *       ({@code TrialRisks.launchMotion} = {@code (0, launchVelocity(height), 0)}) — 남이 쌓아 둔
 *       값이 섞일 자리가 없다. 그 값이 이 천장 아래인 것은 {@code TrialVelocityTest} 가 센다.
 *       ⚠ <b>2026-10-04 에는 수평이 「읽은 그대로」였다</b> — 세로만 덮어쓰고 수평은
 *       {@code getDeltaMovement()} 에서 읽어 돌려놓았으므로, 「덮어쓰니 섞일 자리가 없다」가
 *       <b>세로에만</b> 참이었다. 그 수평은 공중 종착 <b>127.9칸/틱</b>이고 이 카드는
 *       <b>최후의 저항 밖</b>에서 돌아 뿌리 차단이 닿지 않는다. 지금은 둘 다 덮어쓴다 —
 *       「자름이 아니라 0 이 답인 이유」는 {@code TrialRisks.launch} 에 적어 두었다</li>
 *   <li>{@code DragonLastStandPatterns.liftCross} 도 세로를 <b>덮어쓴다</b>
 *       ({@code CROSS_LIFT_SPEED}). 덮어쓰기 자체가 그 방어다</li>
 * </ul>
 */
public final class TrialVelocity {

	private TrialVelocity() {
	}

	/**
	 * ⚠⚠ 속도를 내려 보낼 때 <b>실어도 되는 세로</b>(칸/틱). <b>이 한 줄이 「점프하면 하늘로
	 * 날아간다」를 막는다.</b>
	 *
	 * <h2>사람이 본 것</h2>
	 *
	 * <p><b>「드래곤 밀치는 패턴떄 점프하면 하늘로 날라가버림」</b>(2026-10-04)
	 *
	 * <h2>⚠⚠ 원인이 우리 세기에 없었다 — <b>남이 쌓아 둔 값을 우리가 배달했다</b></h2>
	 *
	 * <p>26.3 {@code EnderDragon.knockBack} 을 {@code javap -c} 로 읽은 결과다.
	 *
	 * <pre>double dd = Math.max(dx * dx + dz * dz, 0.1);
	 * e.push(dx / dd * 4.0, 0.20000000298023224, dz / dd * 4.0);   // ← 세로 +0.2
	 * if (!phaseManager.getCurrentPhase().isSitting() &amp;&amp; …) { e.hurtServer(…, 5.0F); }</pre>
	 *
	 * <p>⚠ <b>{@code isSitting()} 조건은 <u>피해</u>에만 붙어 있다 — 미는 것은 조건 없이 매 틱
	 * 돈다.</b> 상자는 {@code wing1}·{@code wing2} 의 {@code inflate(4,2,4).move(0,-2,0)} 이고
	 * {@code !wasHurtRecently()} 인 틱마다 돈다. 최후의 저항은 드래곤을 포디움 위에 붙박아 두는
	 * 페이즈라 <b>붙어서 때리는 사람이 늘 그 상자 안</b>이고, ⚠ <b>착지도 같은 기하</b>다 —
	 * {@code DragonPerch} 가 드래곤을 포디움에 <b>160틱</b>({@code DragonPerch.HOLD_TICKS})
	 * 붙박아 둔다.
	 *
	 * <h2>왜 바닐라에서는 아무 일도 안 일어나는가</h2>
	 *
	 * <p>{@code Entity.push} 가 켜는 깃발이 {@code needsSync} 이고, 26.3 {@code ServerEntity} 는
	 * 그것을 <b>{@code sendToTrackingPlayers}</b> 로만 내보낸다 — <b>맞은 본인에게는 안 간다.</b>
	 * 사람의 자리는 클라이언트가 정하므로 그 값은 <b>서버 혼자 쌓아 두는 숫자</b>로 남는다.
	 *
	 * <p>그런데 {@code syncVelocity} 는 <b>{@code sendToTrackingPlayersAndSelf}</b> 이고, 보내는
	 * 것이 <b>그 순간의 {@code getDeltaMovement()} 통째로</b>다
	 * ({@code new ClientboundSetEntityMotionPacket(Entity)}). 곧 <b>우리가 {@code syncVelocity} 를
	 * 켜는 한 줄이 남이 쌓아 둔 세로를 본인에게 배달하는 길</b>이다.
	 *
	 * <h2>얼마나 쌓이는가 — 매 틱 {@code (v + 0.2 − 0.08) × 0.98}</h2>
	 *
	 * <table border="1">
	 *   <caption>드래곤 상자 안에 서 있은 시간과 서버쪽 세로</caption>
	 *   <tr><th>틱</th><th>세로(칸/틱)</th><th>그 속도의 도달 높이</th></tr>
	 *   <tr><td>12 (번치 한 칸)</td><td>1.204</td><td>8.4칸</td></tr>
	 *   <tr><td>96 (패턴 한 판)</td><td>5.023</td><td>92칸</td></tr>
	 *   <tr><td>160 (착지 한 번)</td><td><b>5.645</b></td><td><b>109칸</b></td></tr>
	 *   <tr><td>∞</td><td><b>5.88</b> = {@code 0.12 × 0.98 ÷ 0.02}</td><td><b>116칸</b></td></tr>
	 * </table>
	 *
	 * <p>⚠ 표는 <b>가만히 선 사람의 한 틱치</b>({@code −0.08 × 0.98 = −0.0784})에서 굴린 값이다.
	 * {@code 0} 에서 굴리면 12틱 <b>1.266</b>(9.0칸) · 96틱 <b>5.035</b>(91.4칸) · 160틱
	 * <b>5.648</b>(109.3칸)이다 — 어느 쪽으로 세든 <b>섬 밖 허공보다 높이 솟는다</b>는 것이 요점
	 * 이고, 두 벌을 함께 적어 두는 것은 다음 사람이 0.003 차이를 버그로 읽지 않게 하기 위해서다.
	 *
	 * <p>⚠⚠ <b>착지에서는 160틱이 한 틱도 안 끊긴다.</b> 쌓임을 끊는 것은 {@code hurtTime > 0}
	 * ({@code wasHurtRecently()})인데, {@code EnderDragonPerchRangedImmunityMixin} 이
	 * {@code hurt} 를 HEAD 에서 {@code setReturnValue(false)} 로 끊어 <b>{@code hurtTime} 이 아예
	 * 안 올라간다.</b> 곧 <b>원거리로만 때리는 팀에게는 160틱이 통째로 쌓인다</b> — 우리 기능
	 * 둘이 서로를 악화시키는 자리다.
	 *
	 * <p>⚠ <b>26.3 에는 「속도 패킷이 3.9 에서 잘린다」가 없다.</b> {@code LpVec3.ABS_MAX_VALUE} 가
	 * {@code 1.7179869183E10} 이라 쌓인 값이 <b>한 톨도 안 깎이고</b> 내려간다. 옛 판의 그 자름이
	 * 천장 노릇을 하고 있었다고 믿으면 안 된다.
	 *
	 * <p>⚠ 중력이 지우지도 않는다 — {@code Player.isEffectiveAi()} 가 서버에서 <b>참</b>이라
	 * {@code travel} 이 돌지만 중력이 틱당 0.08 뿐이고 드래곤이 0.2 를 넣는다. 그리고 26.3
	 * {@code LivingEntity.jumpFromGround} 가 {@code setDeltaMovement(x, Math.max(점프력, y), z)} 라
	 * <b>점프가 쌓인 값을 지우지도 않는다</b>(바이트코드로 확인했다).
	 *
	 * <h2>고친 방법 — <b>올리는 쪽만 자른다</b></h2>
	 *
	 * <p>천장이 <b>{@code getKnownMovement().y}</b> 다. 26.3
	 * {@code ServerGamePacketListenerImpl.handlePlayerKnownMovement} 가 <b>움직임 패킷의 자리
	 * 차이</b>를 그대로 {@code setKnownMovement} 에 넣고, 그 틱에 패킷이 안 왔으면
	 * {@code handleClientTickEnd} 가 {@code Vec3.ZERO} 로 되돌린다 — 곧 <b>사람이 실제로 올라간
	 * 만큼</b>이고 <b>서버가 쌓은 값이 섞이지 않는 유일한 수</b>다.
	 *
	 * <ul>
	 *   <li><b>내리는 쪽은 한 톨도 안 건드린다.</b> {@code y ≤ 0} 이면 받은 값을 그대로 돌려준다 —
	 *       떨어지는 사람을 더 세게 떨어뜨리면 그것이 새 낙사 장치다</li>
	 *   <li><b>점프가 이득도 손해도 아니다.</b> 스스로 뛴 사람은 자기 올라가는 속도가 곧 천장이라
	 *       이 함수가 <b>아무것도 바꾸지 않는다</b>
	 *       ({@link DragonLastStandPatterns#AIRBORNE_PUSH_SCALE} 가 수평에 대해 한 약속과 같은
	 *       약속이다)</li>
	 *   <li><b>십자 띄움도 그대로다.</b> 띄워진 사람은 실제로 올라가고 있으므로 천장이 그 속도다 —
	 *       {@link DragonLastStandPatterns#CROSS_LIFT_BLOCKS} 의 12칸이 안 깎인다</li>
	 *   <li>⚠ <b>{@link DragonLastStandPatterns#CROSS_LIFT_SPEED} 를 두 번째 천장으로 겹친다.</b>
	 *       클라이언트가 보내 준 수를 홀로 믿으면 거짓 보고 한 번에 이 자름이 통째로 열린다 —
	 *       <b>우리가 일부러 싣는 가장 큰 세로</b>가 12칸 띄움이므로 그보다 위는 우리 것이 아니다
	 *       (「자리 폭격」의 {@code TrialRisks.launchVelocity(4)} = 0.8 도 그 아래다 —
	 *       {@code TrialVelocityTest} 가 센다). ⚠ 다만 그 천장만으로는 <b>안전하지 않다</b>:
	 *       1.49053 을 12틱마다 다시 실으면 12틱에 11.2칸씩, 여덟 번에 <b>약 90칸</b>이 오른다
	 *       (6칸이던 때는 1.00746 · 48칸) — 낙사를 막는 것은
	 *       「사람이 실제로 올라간 만큼」쪽이고 이쪽은 거짓 보고용 보험이다</li>
	 * </ul>
	 *
	 * <p>⚠ <b>이것은 세기 문제의 답이 아니다.</b> 세기는
	 * {@link DragonLastStandPatterns#AIRBORNE_PUSH_SCALE} 이고 낙사는 천장 둘이다. 셋이 다 있어야
	 * 하고 셋이 다른 일을 한다.
	 *
	 * <h2>⚠ 쌓는 쪽은 최후의 저항에서만 끊었다 — 그래서 이 함수가 넷을 지킨다</h2>
	 *
	 * <p>{@code EnderDragonContactDamageMixin} 이 {@code hurt} 와 함께 <b>{@code knockBack} 까지</b>
	 * 끊는다. 그런데 그 깃발은 {@code DragonLastStand.contactDamageOff()} 곧 <b>최후의 저항
	 * 전용</b>이다 — 일반 전투의 날개 밀치기는 바닐라 동작이고, 「끄면 완전한 바닐라
	 * 엔더드래곤전」의 경계를 한 칸 더 움직이는 일이라 <b>끄지 않기로 했다.</b>
	 *
	 * <p>그래서 그 밖의 페이즈에서는 바닐라가 그대로 쌓고, <b>배달을 막는 것은 이 함수뿐</b>이다.
	 * 이 길을 고른 근거가 그것이다 — <b>남이 쌓아 둔 숫자를 우리가 배달하지 않는 것</b>뿐이라
	 * 바닐라 동작을 하나도 바꾸지 않고, 정상 플레이어에게는 <b>비트 단위로 무변화</b>다
	 * (스스로 뛴 사람·떨어지는 사람 둘 다 받은 값이 그대로 나간다).
	 *
	 * <p>지나는 자리 <b>넷</b> — {@code DragonLastStandPatterns.shove}(날개 번치) ·
	 * {@code DragonLastStandPatterns.pullSuck}(공허 흡입) · {@code TrialEnderStorm.push}(엔더폭풍) ·
	 * {@code TrialLandingShock.push}(착지 충격). ⚠ <b>속도를 내려 보내는 자리를 새로 만들면 이
	 * 목록에 더해야 한다.</b>
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 쌓이는 과정을 틱마다 직접 굴려 본다.
	 *
	 * @param motionY 서버가 들고 있는 세로. <b>남이 쌓아 둔 값이 섞여 있다</b>
	 * @param clientY {@code getKnownMovement().y} — 사람이 <b>실제로</b> 올라간 만큼
	 */
	static double syncedVertical(double motionY, double clientY) {
		// 올려 보낼 수 있는 천장. 사람이 올라가고 있지 않으면 0 이라 「올리지 않는다」가 된다.
		// ⚠ 두 번째 천장을 여기서 베껴 적지 않는다 — 그 값이 정의된 자리에서 읽는다. 베끼면
		// 이 저장소가 네 번 겪은 「같은 값이 두 벌」이 다섯 번째가 된다.
		double ceiling = Math.max(0.0,
				Math.min(clientY, DragonLastStandPatterns.CROSS_LIFT_SPEED));
		return Math.min(motionY, ceiling);
	}
}
