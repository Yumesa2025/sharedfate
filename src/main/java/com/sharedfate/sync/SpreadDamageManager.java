package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkChoiceSession;
import com.sharedfate.perk.PerkDamage;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetEffects;
import com.sharedfate.perk.effect.SpreadDamageEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 프리즘 「완충」({@code spread_damage})의 실행부. 받는 피해를 그 자리에서 넣지 않고 미뤄 두었다가
 * 몇 초에 걸쳐 나누어 넣는다.
 *
 * <p>{@link SpreadDamageEffect} 는 「몇 초에 걸쳐 나눌 것인가」만 알고, 지금 누가 얼마를 미뤄
 * 두었고 다음 몫이 언제 들어가는지는 여기서 센다.
 *
 * <h2>큐는 팀에 하나다</h2>
 * <p>이 모드는 체력이 팀 공유다. 큐를 사람마다 두면 4인 팀에서 <b>네 갈래가 같은 체력 통에
 * 동시에 흘러든다</b>. 초당 들어가는 양이 팀 인원만큼 곱해지는 것이고, 이 모드에서 되풀이해
 * 터졌던 버그 모양 그대로다. 그래서 키는 언제나 {@code teamId} 이고, 팀원 누가 맞았든 몫은 한
 * 큐에 모인다.
 *
 * <h2>겹치면 합친다 — 큐를 쌓지 않는다</h2>
 * <p>분산 중에 또 맞으면 새 큐를 만들지 않고 <b>남은 몫에 더한 뒤 남은 횟수로 다시 나눈다</b>.
 * 남은 시간은 늘리지 않는다. 맞을 때마다 시간을 늘리면 전투가 이어지는 동안 큐가 영원히 끝나지
 * 않아 회복 금지도 영원해지고, 큐를 여러 개 쌓으면 초당 피해가 한없이 커진다.
 *
 * <h2>미뤄 둔 몫을 다시 넣을 때 또 분산하지 않는다</h2>
 * <p>몫을 다시 넣는 자리는 {@code DELIVERING} 표시를 켜 두고 {@code hurtServer} 를 부른다.
 * {@link com.sharedfate.mixin.LivingEntityPerkDamageMixin} 이 그 표시를 보고 이번 피해는 건드리지
 * 않고 지나 보낸다. 표시가 없으면 다시 넣은 몫이 또 미뤄져 영원히 끝나지 않는다.
 *
 * <p>전용 {@code DamageSource} 를 만들지 않고 표시(플래그)를 고른 이유는 두 가지다. 첫째,
 * 26.2 에서 새 피해 종류는 데이터팩 레지스트리({@code damage_type})에 등록해야 하므로 등록
 * 파일이 늘어난다. 둘째, 그렇게 하면 <b>원래 피해원을 잃는다</b> — 사망 메시지가 「좀비에게
 * 당함」이 아니라 정체불명이 되고, 불·낙하 같은 종류 태그를 보는 다른 증강
 * ({@code damage_taken_from})이 미뤄 둔 몫을 다른 종류로 오해한다. 표시는 원래 피해원을 그대로
 * 들고 다시 넣을 수 있다.
 *
 * <h2>난이도 배율과 방패는 처음 맞을 때 한 번만 — 가로채는 자리가 방패 바로 뒤인 까닭</h2>
 * <p>26.3 바이트코드로 본 플레이어 피해의 순서는 이렇다.
 *
 * <pre>
 *   ServerPlayer.hurtServer → Player.hurtServer
 *       scalesWithDifficulty 면 난이도 배율(51~107행: 평화 0, 쉬움 min(x/2+1, x), 어려움 x*3/2)
 *       → invokespecial Avatar.hurtServer(120행) — Avatar 에 선언이 없어 LivingEntity.hurtServer
 *   LivingEntity.hurtServer
 *       HEAD                         ← 증강·「난이도 상승」 배율({@code LivingEntityPerkDamageMixin})
 *       79  applyItemBlocking(amount) ← 방패. 막은 양을 돌려주며 방패 내구도·밀쳐내기도 여기서
 *       88  amount -= 막은 양          ← 「완충」은 <b>바로 여기</b>서 남은 양을 미뤄 간다
 *       185 무적시간 판정, 그 뒤 actuallyHurt·막는 소리(onBlocked)·피격 연출
 * </pre>
 *
 * <p>그래서 처음 맞을 때 미뤄 가는 양은 <b>난이도 배율이 이미 곱해진 뒤의 값</b>이다. 그런데
 * 몫을 다시 넣는 {@link #deliver} 는 {@code victim.hurtServer} 를 불러 위 사슬을 처음부터 다시
 * 탄다 — 예전에는 몫마다 난이도 배율이 또 곱해져서 어려움의 몹 피해가 1.5배가 아니라 2.25배가
 * 됐다(쉬움은 몫이 2 를 넘으면 오히려 덜 들어갔다). 지금은 몫이
 * {@code LivingEntity.hurtServer} 에 닿는 순간 {@link #sliceArrival} 이 {@code Player} 가 다시
 * 곱한 값을 버리고 <b>넣으려던 몫 그대로</b>를 돌려준다. {@code Player} 의 갈래를 피할 길은
 * 없으니(invokespecial 이다) 타고 내려온 뒤에 되돌리는 것이다.
 *
 * <p>방패도 같은 모양의 버그였다. 예전에는 HEAD 에서 피해를 0 으로 바꿔 넘겨서
 * {@code applyItemBlocking} 이 막을 양이 없었다 — 완충을 가진 사람은 <b>처음 맞을 때 방패로
 * 막지 못했고</b>, 거꾸로 나뉜 몫이 들어올 때 방패를 들고 있으면 <b>몫이 막혔다.</b> 사람 말은
 * 「이미 나눠 피해받을 때 방패 올려도 막으면 안 돼」(2026-10-04)다. 지금은 방패가 원래 피해를
 * 바닐라 그대로 먼저 막고(내구도·막는 소리도 그 한 번), 막고 <b>남은 양만</b> 미룬다. 다 막았으면
 * 미룰 것도 없다. 몫은 {@link #ignoresShield} 가 방패 판정을 건너뛰게 한다.
 *
 * <p>평화 난이도에서는 {@code Player} 가 몹 피해를 0 으로 만들고 {@code LivingEntity} 에 닿기
 * 전에 끝내므로 애초에 미뤄지지 않는다. 낙하처럼 배율을 안 타는 피해는 처음에도 몫에도 배율이
 * 없다. 남은 어긋남 하나 — 몫이 남은 동안 난이도를 평화로 바꾸면, 마지막 피해원이 몹이었을 때
 * 남은 몫은 {@code Player} 가 0 으로 만들어 들어가지 않는다.
 *
 * <h2>무적시간을 흉내 낸다 — 여기를 빠뜨리면 피해가 몇 배가 된다</h2>
 * <p>가로채는 자리가 바닐라가 「이 피해는 무적시간에 막힌다」고 판단하기(185행) <b>전</b>이다. 그대로 큐에 넣으면 좀비 셋에게 같은 틱에 맞았을 때 바닐라는
 * 한 대만 세는데 큐는 세 대를 전부 센다. 그래서 {@link #gate} 가 바닐라의 판정을 그대로
 * 흉내 내어, <b>바닐라가 실제로 넣었을 몫만</b> 큐에 넣는다. 26.2 의 규칙은 이렇다.
 *
 * <pre>
 *   무적시간 &gt; 10 이고 피해 종류가 bypasses_cooldown 이 아니면
 *       직전 피해량보다 큰 만큼만 들어가고, 무적시간은 그대로다
 *   그렇지 않으면
 *       전부 들어가고, 직전 피해량을 갱신하며 무적시간이 20 으로 찬다
 * </pre>
 *
 * <p>흉내 낸 무적시간을 따로 들고 다니는 이유는, 미뤄 둔 몫을 다시 넣을 때마다 <b>진짜</b>
 * 무적시간이 흔들리기 때문이다. 진짜 값을 그대로 믿으면 몫을 넣을 때마다 무적시간이 새로 차서
 * 분산 중에는 몹에게 거의 맞지 않는 증강이 된다.
 *
 * <h2>회복은 분산이 끝날 때까지 막는다</h2>
 * <p>{@link #blocksHealing} 이 {@code LivingEntity.heal} 진입 시점에 불린다. 자연 회복도 재생
 * 상태이상도 금사과도 그 한 지점을 지나므로 여기서 함께 막힌다. <b>큐가 살아 있는 동안, 팀원
 * 전원에게</b> 걸린다 — 체력이 공유라 누가 회복해도 미뤄 둔 몫이 지워지기 때문이다.
 *
 * <h2>적을 처치하면 남은 몫이 사라진다</h2>
 * <p>나뉘어 들어오는 동안 팀원 누군가가 몹을 잡으면 {@link #clearPending} 이 <b>아직 넣지 않은
 * 몫만</b> 지운다. 이미 들어간 피해는 되돌리지 않는다 — 되돌리면 그것은 회복이고, 「완충」이
 * 피해를 늦추는 증강이 아니라 없애는 증강이 된다.
 *
 * <p>지울 때 {@link Spread#closeQueue()} 만 부르고 표 자체는 남긴다. 표에는 흉내 낸 무적시간
 * ({@link Guard})이 들어 있어서, 표째로 {@link #forget} 하면 처치 직후 20틱 안에 다시 맞은
 * 피해가 <b>바닐라라면 무적시간에 막혔을 텐데도</b> 통째로 큐에 쌓인다. 처치로 얻는 것은
 * 「남은 몫 면제」지 「무적시간 초기화」가 아니다. 몫이 다 빠진 표는 무적시간이 다 흐른 뒤
 * {@link #stepTeam} 이 알아서 치운다.
 *
 * <p>회복 금지는 {@link #isSpreading} 이 {@code pending()} 을 보므로 큐를 닫는 순간 함께 풀린다.
 * 따로 걷어낼 상태가 없다.
 *
 * <h2>알면서 받아들인 어긋남</h2>
 * <ul>
 *   <li>가로챈 피해는 방패 판정 뒤로 0 으로 넘어가므로 그 호출이 {@code false} 를
 *       돌려준다. 때린 몹 입장에서는 「맞지 않았다」라서 <b>넉백이 걸리지 않는다</b>. 0 대신
 *       아주 작은 값을 넘기면 넉백은 살릴 수 있지만, 그 값만큼 공유 체력과 흡수가 미세하게
 *       깎이고 장비 내구도가 한 번 더 닳는다. 미뤄 둔 피해는 <b>아직 도착하지 않은 것</b>이므로
 *       0 이 맞다고 보았다.</li>
 *   <li>피격 연출(붉은 번쩍임·화면 기울기·피격음·넉백)은 <b>처음 맞은 그 한 번만</b> 난다.
 *       나뉜 몫은 바닐라의 「쿨타임 안 추가 피해」 갈래로 넣어 체력만 조용히 깎고, 팀원에게
 *       뿌리는 피격 연출({@link SharedHurtFeedback})도, 화면 왼쪽 아래의 피격 알림
 *       ({@code StatMirror})도 몫에서는 나가지 않는다. 자세한 까닭은
 *       {@link #deliver} 에 있다. 다만 체력이 줄었다는 꾸러미를 받은 클라이언트가 스스로
 *       화면을 한 번 기울이는 것({@code LocalPlayer.hurtTo})은 서버가 막을 길이 없어 몫마다
 *       남는다 — 소리는 나지 않는다.</li>
 *   <li>몫을 넣을 때마다 방어구 내구도가 한 번씩 닳는다. 나눈 횟수만큼 닳는다는 뜻이다. 몫을
 *       1초 간격으로만 넣는 이유가 여기에도 있다.</li>
 *   <li>몫마다 방어구 계산을 다시 지나므로, 한 번에 맞았을 때보다 방어구가 조금 더 많이
 *       깎아 준다. 바닐라의 방어 공식이 작은 피해에 더 후하기 때문이다.</li>
 * </ul>
 */
public final class SpreadDamageManager {
	/** 바닐라가 「아직 무적시간 안이다」로 보는 경계. {@code invulnerableTime > 10} 이다. */
	public static final int INVULNERABLE_GATE_TICKS = 10;
	/** 피해가 들어갔을 때 채워지는 무적시간(틱). */
	public static final int INVULNERABLE_TICKS = 20;

	/** 팀마다 하나뿐인 큐. 키는 {@code teamId} 다. */
	private static final Map<UUID, Spread> ACTIVE = new ConcurrentHashMap<>();

	/**
	 * 지금 미뤄 둔 몫을 다시 넣는 중인가.
	 *
	 * <p>피해 처리는 서버 스레드에서만 돌지만, 표시를 잘못 남기면 모든 피해가 조용히 통과하므로
	 * 스레드마다 따로 두어 새어나갈 길을 없앤다.
	 */
	private static final ThreadLocal<Boolean> DELIVERING = ThreadLocal.withInitial(() -> Boolean.FALSE);

	/**
	 * 지금 돌고 있는 {@code hurtServer} 호출에서 미뤄 간 양. 키는 맞은 사람의 UUID 다.
	 *
	 * <p>미룬 피해는 {@code hurtServer} 에 0 으로 넘어가서, 같은 호출 꼬리의
	 * {@code AFTER_DAMAGE} 가 「아무 피해도 없었다」로 본다. 팀원에게 피격 연출을 뿌리는
	 * {@link SharedHurtFeedback} 이 그 0 을 그대로 믿으면 <b>처음 맞은 순간</b>에 팀원 화면에 아무것도
	 * 안 뜬다. 그래서 「이번 0 은 사실 이만큼을 미룬 것이다」를 여기 적어 두고 그쪽이 들여다본다.
	 *
	 * <p>적는 곳은 {@link #capture}, 지우는 곳은 <b>다음 {@link #intercept} 맨 앞</b>과
	 * {@link #reset} 뿐이다. <b>읽는 쪽은 꺼내 가지 않는다</b>({@link #deferredHit}). 같은 꼬리에서
	 * 이 값을 보는 소비자가 넷(팀원 피격음·{@code on_team_hurt}·{@code pass_on_hurt}, 그리고 그 판정을
	 * 같이 쓰는 피격 알림)인데, 예전처럼 꺼내며 지우면 먼저 등록된 {@link SharedHurtFeedback} 이
	 * 가져가 버려 뒤의 둘은 「아무 피해도 없었다」로 읽었다(2026-10-06 Orca 검토 F-verified 의 V7).
	 *
	 * <p>꺼내 가지 않아도 다음 사건으로 새지 않는다. 26.3 {@code hurtServer} 바이트코드에서 꼬리
	 * ({@code AFTER_DAMAGE}, 637행 {@code ireturn} 앞)에 닿는 길은 전부 79행 방패 판정과 88행 저장
	 * — {@code intercept} 가 붙은 자리 — 을 지난다. 그 앞의 {@code return} 은 10·19·41행뿐이고 88행
	 * 뒤의 것(217행, 쿨타임에 버림)은 꼬리에 닿지 않는다. 그러니 같은 사람의 다음 {@code AFTER_DAMAGE}
	 * 앞에는 반드시 {@code intercept} 맨 앞의 지우기가 있다. 호출이 꼬리까지 못 가고 끝난 경우(무적·
	 * 즉시 취소)도 같은 이유로 거기서 지워진다.
	 */
	private static final Map<UUID, Float> DEFERRED_HITS = new ConcurrentHashMap<>();

	/**
	 * 지금 넣고 있는 몫이 <b>누구에게 얼마</b>인가. 몫을 넣는 중이 아니면 비어 있다.
	 *
	 * <p>{@link #DELIVERING} 은 「지금 몫을 넣는 중인가」만 알아서, 그 사이에 다른 엔티티가 맞는
	 * 피해(가시 인챈트가 때린 쪽을 찌르는 것 따위)와 몫을 가르지 못한다. 난이도 배율을 되돌리고
	 * 방패를 건너뛰는 일은 <b>몫을 받는 그 사람</b>에게만 해야 하므로 대상을 함께 든다.
	 */
	private static final ThreadLocal<SliceInFlight> IN_FLIGHT = new ThreadLocal<>();

	/**
	 * 피격 알림({@code StatMirror} 가 보내는 「○○ 피격」)이 다음에 볼 체력 감소 가운데 <b>몫이
	 * 깎은 양</b>. 키는 몫을 받은 사람의 UUID 다.
	 *
	 * <p>알림은 피해 사건이 아니라 「지난 틱보다 체력이 줄었는가」를 보고 뜬다. 그래서 예전에는
	 * 체력이 그대로인 처음 맞은 순간에는 안 뜨고, 체력이 실제로 깎이는 몫마다 떴다 — 팀원
	 * 피격음({@link SharedHurtFeedback})과 똑같이 거꾸로였다. 몫이 깎은 만큼을 여기 적어 두면
	 * 그쪽이 빼고 본다. 적는 곳은 {@link #deliver}, 꺼내는 곳은 {@link #takeSliceLoss}.
	 */
	private static final Map<UUID, Float> SLICE_LOSS = new ConcurrentHashMap<>();

	/**
	 * 피격 알림이 다음에 볼 동안 미뤄 간 양의 합. 키는 맞은 사람의 UUID 다.
	 *
	 * <p>{@link #DEFERRED_HITS} 와 적는 순간은 같지만({@link #noteDeferredHit}) 사는 길이가 다르다.
	 * 그쪽은 같은 {@code hurtServer} 호출의 꼬리에서 꺼내 가고, 이쪽은 다음 {@code StatMirror}
	 * 한 바퀴가 꺼내 간다. 꺼내 가지 못한 것은 그 바퀴 끝의 {@link #forgetAlertLedger} 가 버린다.
	 */
	private static final Map<UUID, Float> DEFERRED_FOR_ALERT = new ConcurrentHashMap<>();

	private static boolean warned;
	/** 사망 정리 쪽 경고는 따로 센다. 가로채기 경고와 원인이 다르다. */
	private static boolean deathWarned;

	private SpreadDamageManager() {
	}

	// ------------------------------------------------------------------ 가로채기

	/**
	 * 지금 들어온 피해를 미뤄 둘 것인지 정한다.
	 *
	 * <p>{@link com.sharedfate.mixin.LivingEntityPerkDamageMixin} 이 {@code hurtServer} 안에서
	 * <b>방패 판정 바로 뒤</b>(88행, {@code amount -= 막은 양})에 부른다. 넘어오는 값은 바닐라 난이도
	 * 배율({@code Player.hurtServer})·증강 배율·「난이도 상승」 배율이 <b>이미 반영되고</b>, 방패가
	 * 막은 만큼이 <b>이미 빠진</b> 피해량이다. 배율을 미리 먹여 두어야 미뤄 둔 몫을 다시 넣을 때
	 * 배율을 두 번 곱하지 않고(다시 곱해지는 바닐라 난이도 배율은 {@link #sliceArrival} 이
	 * 되돌린다), 방패 뒤여야 처음 맞는 순간 방패가 바닐라처럼 막는다.
	 *
	 * @return 이번에 실제로 넣을 피해량. 미뤄 두었으면 0, 관여하지 않으면 {@code amount} 그대로
	 */
	public static float intercept(@Nullable Entity victim, @Nullable DamageSource source,
			float amount) {
		// 앞선 호출이 꼬리까지 못 가 남긴 「미룬 양」 표시를 새 호출마다 먼저 지운다. 몫을 넣는
		// 중에도 지워야 하므로 아래 빠른 경로보다 앞이다.
		if (victim != null) {
			forgetDeferredHit(victim.getUUID());
		}
		if (!(amount > 0.0F) || !Float.isFinite(amount) || isDeliveringSlice()) {
			return amount;
		}
		try {
			return capture(victim, source, amount);
		} catch (RuntimeException error) {
			// 가로채기가 터졌다고 피해 처리가 멈추면 안 된다. 원래 값으로 돌아간다.
			warnOnce(error);
			return amount;
		}
	}

	private static float capture(@Nullable Entity victim, @Nullable DamageSource source,
			float amount) {
		if (!(victim instanceof ServerPlayer player)) {
			return amount;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return amount;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(player.getUUID());
		if (team == null) {
			return amount;
		}
		TeamState state = manager.stateByTeamId(team.teamId());
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return amount;
		}
		int slices = sliceCountOf(state);
		if (slices <= 0) {
			return amount;
		}
		// /kill 과 공허는 미루지 않는다. 무적시간조차 무시하는 「관리용」 피해라 늦추면 4초 동안
		// 죽지 않는 플레이어가 생긴다.
		if (source != null && source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
			return amount;
		}
		// 어차피 통째로 버려질 피해는 큐에 넣지 않는다. 버리는 판정은 같은 진입점 HEAD 의 @Inject
		// 가 한다. 가로채는 자리가 방패 뒤로 옮겨 와 HEAD 에서 취소된 피해는 여기까지 오지 않지만,
		// 「버려질 피해가 4초 뒤에 되살아나는」 일은 한 번 더 막아 둔다.
		if (discarded(player, source)) {
			return amount;
		}

		Spread spread = ACTIVE.get(team.teamId());
		Guard guard = spread == null ? null : spread.guards.get(player.getUUID());
		// 사람이 2026-10-04 에 「포격 무시」를 골랐다 — 완충의 흉내 쿨타임도 같은 규칙을 따라야 한다.
		// 연쇄 포격은 바닐라 쿨타임을 지우고 들어가는데(DragonFireBarrage.strike) 여기 Guard 가 그걸
		// 모르면 완충을 가진 팀에서만 둘째 원이 먹히고 피해 0 짜리 피격 연출만 난다. 표시는 strike 의
		// hurtServer 호출 동안만 켜지므로 다른 피해원의 쿨타임은 그대로 막힌다.
		boolean bypassesCooldown = (source != null && source.is(DamageTypeTags.BYPASSES_COOLDOWN))
				|| DragonFireBarrage.ignoresCooldown();
		Gate gate = gate(amount, guard == null ? 0.0F : guard.lastAmount,
				guard == null ? 0 : guard.invulnerableTicks, bypassesCooldown);
		if (!(gate.accepted() > 0.0F)) {
			// 바닐라였어도 들어가지 않았을 피해다. 흉내 낸 상태도 그대로 둔다.
			return 0.0F;
		}

		if (spread == null) {
			spread = new Spread();
			ACTIVE.put(team.teamId(), spread);
		}
		spread.add(player, source, gate, slices);
		noteDeferredHit(player.getUUID(), gate.accepted());
		return 0.0F;
	}

	// ------------------------------------------------------------------ 미룬 피해의 피격 연출

	/**
	 * 이번 {@code hurtServer} 호출에서 이 사람의 피해를 이만큼 미뤘다고 적는다.
	 *
	 * <p>「미뤘다」를 아는 곳은 여기 하나고, 같은 호출 꼬리의 {@code AFTER_DAMAGE} 소비자들
	 * ({@link #deferredHit} → {@link #hurtTaken})과 피격 알림({@link #takeDeferredAlert})이 이 기록을
	 * 본다. 모두 「처음 맞은 순간에 한 번」을 같은 판정({@link #hurtTaken})으로 가른다.
	 */
	static void noteDeferredHit(@Nullable UUID playerId, float accepted) {
		if (playerId != null && accepted > 0.0F) {
			DEFERRED_HITS.put(playerId, accepted);
			DEFERRED_FOR_ALERT.merge(playerId, accepted, Float::sum);
		}
	}

	// ------------------------------------------------------------------ 미룬 피해의 피격 알림

	/**
	 * 지난 {@code StatMirror} 한 바퀴 뒤로 이 사람의 피해를 얼마나 미뤘는지 꺼내고 지운다. 없으면 0.
	 *
	 * <p>처음 맞은 순간은 체력이 그대로라 알림이 체력만 보면 놓친다. 이 값이 0 보다 크면 체력이
	 * 안 줄었어도 알린다 — 팀원 피격음이 {@link #deferredHit}(→ {@link #hurtTaken})를 보는 것과 같은
	 * 원리다. ⚠ 그쪽은 2026-10-06 Orca 검토 F-verified 의 V7 로 「꺼내며 지움」(옛 이름
	 * {@code takeDeferredHit})에서 「들여다보기」가 됐지만 이 값은 여전히 꺼내며 지운다 — 읽는 곳이
	 * {@code StatMirror} 한 바퀴 하나뿐이라서다.
	 */
	public static float takeDeferredAlert(@Nullable UUID playerId) {
		return take(DEFERRED_FOR_ALERT, playerId);
	}

	/**
	 * 지난 {@code StatMirror} 한 바퀴 뒤로 몫이 이 사람의 체력·흡수를 얼마나 깎았는지 꺼내고
	 * 지운다. 없으면 0. 피격 알림은 체력 감소에서 이만큼을 빼고 본다.
	 */
	public static float takeSliceLoss(@Nullable UUID playerId) {
		return take(SLICE_LOSS, playerId);
	}

	/**
	 * 피격 알림 쪽 기록 중 꺼내 가지 않은 것을 버린다. {@code StatMirror} 한 바퀴 끝에 부른다.
	 *
	 * <p>그 바퀴가 보지 못한 사람(접속이 끊겼거나 팀이 이번 틱에 정리되는 중)의 기록이 남으면,
	 * 다음에 그 사람이 진짜로 맞았을 때 엉뚱하게 빼거나 더하게 된다.
	 */
	public static void forgetAlertLedger() {
		if (!SLICE_LOSS.isEmpty()) {
			SLICE_LOSS.clear();
		}
		if (!DEFERRED_FOR_ALERT.isEmpty()) {
			DEFERRED_FOR_ALERT.clear();
		}
	}

	/** 몫 하나가 이 사람의 체력·흡수를 이만큼 깎았다고 적는다. */
	static void noteSliceLoss(@Nullable UUID playerId, float lost) {
		if (playerId != null && lost > 0.0F && Float.isFinite(lost)) {
			SLICE_LOSS.merge(playerId, lost, Float::sum);
		}
	}

	private static float take(Map<UUID, Float> ledger, @Nullable UUID playerId) {
		if (playerId == null || ledger.isEmpty()) {
			return 0.0F;
		}
		Float taken = ledger.remove(playerId);
		return taken == null ? 0.0F : taken;
	}

	// ------------------------------------------------------------------ 나뉜 몫이 지나는 바닐라 갈래

	/**
	 * 몫이 {@code LivingEntity.hurtServer} 에 닿았을 때 그 피해량을 <b>넣으려던 몫 그대로</b>
	 * 되돌린다. 몫이 아니면 받은 값 그대로다.
	 *
	 * <p>{@code LivingEntityPerkDamageMixin} 이 {@code hurtServer} HEAD 에서 부른다. 몫은
	 * {@code ServerPlayer.hurtServer → Player.hurtServer} 를 지나 내려오는데, {@code Player} 가
	 * 그 사이에 난이도 배율을 <b>또</b> 곱한다(26.3 51~107행). 미룬 양은 처음 맞을 때 이미 그
	 * 배율이 곱해진 값이라, 그대로 두면 어려움에서 몹 피해가 원래의 1.5배가 아니라 2.25배가 된다.
	 *
	 * <p>되돌리는 것은 한 몫에 한 번, 몫을 받는 그 사람에게만이다. 같은 호출 안에서 다른 엔티티가
	 * 맞거나(가시) 같은 사람이 한 번 더 맞는 피해는 원래 값 그대로 지나간다.
	 *
	 * @param self    지금 {@code hurtServer} 를 지나는 엔티티
	 * @param arrived {@code Player} 의 난이도 갈래를 지나 도착한 값
	 */
	public static float sliceArrival(@Nullable Entity self, float arrived) {
		if (!isDeliveringSlice()) {
			return arrived;
		}
		SliceInFlight slice = IN_FLIGHT.get();
		if (slice == null || slice.target != self || slice.arrived) {
			return arrived;
		}
		slice.arrived = true;
		return slice.amount;
	}

	/**
	 * 이 엔티티의 방패 판정을 건너뛸 것인가. 미뤄 둔 몫을 그 사람에게 넣는 중일 때만 참이다.
	 *
	 * <p>{@code LivingEntityPerkDamageMixin} 이 {@code applyItemBlocking} HEAD 에서 부르고, 참이면
	 * 「막은 양 0」으로 끝낸다. 사람 말 「이미 나눠 피해받을 때 방패 올려도 막으면 안 돼」
	 * (2026-10-04) 그대로다 — 몫은 이미 맞은 피해를 나눠 넣는 것이다. 방패 내구도·밀쳐내기도
	 * {@code applyItemBlocking} 안이라 함께 빠지고, 막는 소리({@code onBlocked})는 몫이 지나는
	 * 「쿨타임 안」 갈래에서 원래 안 난다. 그래서 방패의 부수효과는 처음 맞은 그 한 번뿐이다.
	 *
	 * <p>몫이 하나도 없으면 첫 줄에서 곧바로 거짓이다.
	 */
	public static boolean ignoresShield(@Nullable Entity self) {
		return receivingSlice(self);
	}

	/**
	 * 이 엔티티가 지금 미뤄 둔 몫을 받는 중인가. 몫을 <b>그 사람에게</b> 넣는 중일 때만 참이다.
	 *
	 * <p>{@link #isDeliveringSlice} 는 「어디선가 몫을 넣는 중인가」만 안다. 몫이 처음 맞을 때 이미
	 * 지난 일을 다시 하지 않게 하는 자리 — 방패({@link #ignoresShield}), 그리고
	 * {@code LivingEntityPerkDamageMixin} HEAD 의 낙하 방패·공유 상태이상 중복·광역 중복 검사 — 는 몫을
	 * 받는 그 사람에게만 들어야 하므로 이것을 본다.
	 *
	 * <p>몫이 하나도 없으면 첫 줄에서 곧바로 거짓이다.
	 */
	public static boolean receivingSlice(@Nullable Entity self) {
		if (!isDeliveringSlice()) {
			return false;
		}
		SliceInFlight slice = IN_FLIGHT.get();
		return slice != null && slice.target == self;
	}

	/**
	 * 이번 {@code hurtServer} 호출에서 이 사람의 피해를 얼마나 미뤘는지 <b>들여다본다</b>. 미루지
	 * 않았으면 0. 지우지 않는다.
	 *
	 * <p>같은 호출 꼬리의 {@code AFTER_DAMAGE} 소비자들이 {@link #hurtTaken} 을 거쳐 본다. 소비자가
	 * 여럿이라 꺼내며 지우면 안 된다 — 지우는 곳과 그래도 새지 않는 까닭은 {@link #DEFERRED_HITS}
	 * 에 있다. 사건 하나에 소비자 하나가 한 번씩 불리므로 지우지 않아도 팀원 연출이 두 번 나가지
	 * 않는다.
	 */
	public static float deferredHit(@Nullable UUID playerId) {
		if (playerId == null || DEFERRED_HITS.isEmpty()) {
			return 0.0F;
		}
		Float deferred = DEFERRED_HITS.get(playerId);
		return deferred == null ? 0.0F : deferred;
	}

	/**
	 * {@code AFTER_DAMAGE} 의 {@code damageTaken} 을 「이 사건에서 이 사람이 얼마나 맞았는가」로 고쳐
	 * 읽는다. 「완충」과 얽힌 두 갈래를 <b>한 곳에서</b> 가른다.
	 *
	 * <ul>
	 *   <li><b>몫을 넣는 중이면 0</b> — 그 피해는 처음 맞을 때 이미 셌다. Fabric 은
	 *       {@code AFTER_DAMAGE} 를 {@code hurtServer} 꼬리에서 부르므로 몫마다 또 온다.</li>
	 *   <li><b>이번 호출에서 미룬 첫 피해면 미룬 양</b> — {@code damageTaken} 은 Fabric 이 넘기는
	 *       지역변수 3번의 꼬리 값이라, 미룬 피해에서는 0 으로 온다.</li>
	 *   <li>그 밖에는 {@code damageTaken} 그대로. 완충이 없는 팀은 언제나 이 갈래다.</li>
	 * </ul>
	 *
	 * <p>쓰는 곳이 넷이다 — 팀원 피격음·피격 알림({@code SharedHurtFeedback.shouldEcho}),
	 * {@code on_team_hurt}(동병상련·반격, {@code PerkTriggers}), {@code pass_on_hurt}
	 * ({@code PerkHolderManager}). 예전에는 앞의 둘만 이 갈래를 알아 뒤의 둘이 <b>처음엔 안 돌고
	 * 몫마다 돌았다</b> — 완충 + 동병상련이면 1초마다 오는 몫이 2초짜리 저항 II 를 다시 채워 약
	 * 9초 내내 유지됐다(2026-10-06 Orca 검토 F-verified 의 V7). 문서 6장 「한쪽만 막으면 반드시
	 * 샌다」 그대로라, 새 {@code AFTER_DAMAGE} 소비자도 이것을 지나야 한다.
	 *
	 * @param victim      맞은 엔티티
	 * @param damageTaken Fabric 이 넘긴 {@code damageTaken}
	 */
	public static float hurtTaken(@Nullable Entity victim, float damageTaken) {
		return hurtTaken(damageTaken, victim == null ? 0.0F : deferredHit(victim.getUUID()),
				isDeliveringSlice());
	}

	/**
	 * {@link #hurtTaken(Entity, float)} 의 순수 판정. 월드도 엔티티도 보지 않는다.
	 *
	 * @param damageTaken     Fabric 이 넘긴 {@code damageTaken}
	 * @param deferredAmount  이번 호출에서 완충이 미룬 양. 미루지 않았으면 0
	 * @param deliveringSlice 완충이 미뤄 둔 몫을 넣는 중인가. 참이면 <b>무조건</b> 0 이다
	 */
	public static float hurtTaken(float damageTaken, float deferredAmount, boolean deliveringSlice) {
		if (deliveringSlice) {
			return 0.0F;
		}
		return deferredAmount > 0.0F ? deferredAmount : damageTaken;
	}

	private static void forgetDeferredHit(UUID playerId) {
		if (!DEFERRED_HITS.isEmpty()) {
			DEFERRED_HITS.remove(playerId);
		}
	}

	/**
	 * 바닐라의 무적시간 판정을 그대로 흉내 낸 순수 계산.
	 *
	 * <p>26.2 {@code LivingEntity.hurtServer} 의 판정과 한 줄씩 대응한다. 월드도 엔티티도 보지
	 * 않으므로 이 규칙만 따로 시험할 수 있다.
	 *
	 * @param amount             이번에 들어온 피해량
	 * @param lastAmount         직전에 받아들인 피해량({@code lastHurt} 에 해당)
	 * @param invulnerableTicks  남은 무적시간({@code invulnerableTime} 에 해당)
	 * @param bypassesCooldown   피해 종류가 {@code bypasses_cooldown} 인가
	 */
	public static Gate gate(float amount, float lastAmount, int invulnerableTicks,
			boolean bypassesCooldown) {
		if (invulnerableTicks > INVULNERABLE_GATE_TICKS && !bypassesCooldown) {
			if (!(amount > lastAmount)) {
				// 바닐라는 여기서 false 를 돌려주고 아무것도 바꾸지 않는다.
				return new Gate(0.0F, lastAmount, invulnerableTicks);
			}
			// 넘치는 만큼만 들어간다. 무적시간은 다시 차지 않는다.
			return new Gate(amount - lastAmount, amount, invulnerableTicks);
		}
		return new Gate(amount, amount, INVULNERABLE_TICKS);
	}

	/**
	 * 남은 몫을 남은 횟수로 나눈 이번 몫.
	 *
	 * <p>마지막 한 번은 나눗셈 오차가 남지 않도록 남은 전부를 넣는다. 그래서 나누어 넣은 합계는
	 * 미뤄 둔 총량과 정확히 같다.
	 */
	public static float sliceAmount(float remaining, int slicesLeft) {
		// 음수·NaN·무한대는 넣을 수 있는 몫이 아니다. 그대로 흘려보내면 바닐라 피해 계산으로
		// 새어나간다.
		if (!(remaining > 0.0F) || !Float.isFinite(remaining)) {
			return 0.0F;
		}
		if (slicesLeft <= 1) {
			return remaining;
		}
		return remaining / slicesLeft;
	}

	/** 이 팀이 가진 {@code spread_damage} 중 가장 긴 것의 몫 개수. 없으면 0. */
	static int sliceCountOf(@Nullable TeamState state) {
		int slices = 0;
		for (SpreadDamageEffect effect : spreadsOf(state)) {
			slices = Math.max(slices, effect.sliceCount());
		}
		return slices;
	}

	/**
	 * 이 팀이 가진 {@code spread_damage} 정의들. 없으면 빈 목록.
	 *
	 * <p>보유 증강과 <b>켜진 세트 효과</b>를 함께 펼친다. {@code PerkSwapRules.effectsOf} 가 팀에게
	 * 효과를 물을 때 쓰는 규칙 그대로다. 세트를 빠뜨리면 세트로 얻은 「완충」이 통째로 무동작이
	 * 되는데, 빌드도 통과하고 로그도 남지 않는다.
	 */
	private static List<SpreadDamageEffect> spreadsOf(@Nullable TeamState state) {
		if (state == null || state.ownedPerks.isEmpty()) {
			return List.of();
		}
		List<SpreadDamageEffect> found = new ArrayList<>();
		for (String perkId : state.ownedPerks) {
			// 풀에서 사라진 id 는 건너뛴다. 증강 정의를 손으로 고칠 수 있는 이상 저장에만 남은
			// id 는 언제든 생긴다.
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof SpreadDamageEffect spread) {
					found.add(spread);
				}
			}
		}
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) {
			if (effect instanceof SpreadDamageEffect spread) {
				found.add(spread);
			}
		}
		return found;
	}

	/**
	 * 같은 진입점의 다른 처리가 이 피해를 통째로 버릴 것인가.
	 *
	 * <p>버릴 피해를 큐에 넣으면 「없던 피해가 4초 뒤에 생기는」 꼴이 된다. 다섯 판정 모두 첫 줄에서
	 * 곧바로 빠져나가는 빠른 경로를 갖고 있어, 실제로 「완충」을 가진 팀의 피해에만 얹힌다.
	 *
	 * <p>시련 화면({@link TrialFreeze})은 HEAD 가 같은 판정으로 먼저 버리므로 여기까지 오지 않지만,
	 * HEAD 의 다른 「통째로 버림」과 한 벌로 맞춰 둔다(2026-10-06 Orca 검토 F-verified 의 V6 에서
	 * 이 목록에 그것만 빠져 있었다).
	 */
	private static boolean discarded(ServerPlayer victim, @Nullable DamageSource source) {
		return PerkChoiceSession.blocksDamage(victim)
				|| GameStartManager.blocksDamage(victim)
				|| TrialFreeze.blocksDamage(victim)
				|| PerkDamage.blocksFallDamage(victim, source)
				|| SharedEffectDamage.isDuplicateEffectDamage(victim);
	}

	// ------------------------------------------------------------------ 진행

	/**
	 * 미뤄 둔 몫이 있는 팀들을 한 틱씩 밀어 준다.
	 *
	 * <h2>판이 멈춘 동안은 기다린다 — 시련 화면도 그렇다</h2>
	 * <p>증강 선택 중·게임 오버 카운트다운·시련 화면({@link TrialFreeze}, 룰렛과 정해진 카드 화면)
	 * 동안에는 진행하지 않는다. 시간이 멈춰 있고 팀원은 창에 갇혀 있어 피할 수도 없다. 남은 몫은
	 * <b>줄지 않고 그대로</b> 기다리고, 흉내 낸 무적시간({@link Guard})도 같이 멈춘다.
	 *
	 * <p>시련 화면이 이 목록에 빠져 있던 동안에는 몫이 <b>통째로 사라졌다</b>(2026-10-06 Orca 검토
	 * F-verified 의 V6). 이 틱은 {@code END_SERVER_TICK} 이라 판이 얼어 있어도 돌고, 몫은
	 * {@code takeSlice} 로 먼저 떼어 낸 뒤 {@code hurtServer} 로 들어가는데, 그 HEAD
	 * ({@code LivingEntityPerkDamageMixin})가 {@code TrialFreeze.blocksDamage} 로 피해를 버린다.
	 * 룰렛 337틱·카드 화면 300틱이면 8초짜리 몫이 전부 사라져, 룰렛이 「완충」의 대가를 지우는
	 * 면제 장치가 됐다. 판 전체가 멈추는 일이라 {@code isActive()} 하나로 본다 — 증강 선택과 같은
	 * 모양이다.
	 */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null || ACTIVE.isEmpty() || PerkChoiceSession.isActive()
				|| WorldResetCoordinator.countingDown() || TrialFreeze.isActive()) {
			return;
		}
		try {
			for (UUID teamId : List.copyOf(ACTIVE.keySet())) {
				Spread spread = ACTIVE.get(teamId);
				if (spread != null) {
					stepTeam(server, teamId, spread);
				}
			}
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	private static void stepTeam(MinecraftServer server, UUID teamId, Spread spread) {
		spread.tickGuards();
		// 넣을 몫이 없어도 흉내 낸 무적시간이 남아 있으면 표를 들고 있는다. 큐가 닫히는 순간
		// 그 기억까지 버리면, 큐가 끝나는 그 몇 틱 사이에 맞은 피해가 무적시간을 무시하고
		// 통째로 쌓인다.
		if (!spread.pending()) {
			if (spread.guards.isEmpty()) {
				ACTIVE.remove(teamId);
			}
			return;
		}
		if (--spread.ticksToNextSlice > 0) {
			return;
		}

		ShareTeam team = TeamManager.get(server).teamById(teamId);
		ServerPlayer victim = team == null ? null : pickVictim(server, team, spread);
		if (victim == null) {
			// 받을 사람이 아무도 없다. 남은 몫을 들고 기다리면 다음 접속 때 영문 모를 피해가
			// 되므로 여기서 버린다.
			ACTIVE.remove(teamId);
			return;
		}

		float slice = spread.takeSlice();
		deliver(victim, spread.source, slice);

		if (!spread.pending()) {
			spread.closeQueue();
			return;
		}
		spread.ticksToNextSlice = SpreadDamageEffect.SLICE_PERIOD_TICKS;
	}

	/**
	 * 이번 몫을 받을 사람.
	 *
	 * <p>맞은 사람 본인이 첫 번째다. 체력은 어차피 공유라 누가 받아도 팀 체력은 같이 줄지만,
	 * 넉백·방어구 내구도·피격 연출은 받는 사람 것이라 원래 맞은 사람에게 몰아 주는 편이 자연스럽다.
	 * 그 사람이 나가거나 죽었으면 접속해 있는 팀원 아무나로 물러선다. (피격 연출은 이제 몫에서
	 * 나지 않는다 — {@link #deliver} 참고.)
	 */
	private static @Nullable ServerPlayer pickVictim(MinecraftServer server, ShareTeam team,
			Spread spread) {
		ServerPlayer remembered = spread.victimId == null
				? null : server.getPlayerList().getPlayer(spread.victimId);
		if (alive(remembered)) {
			return remembered;
		}
		for (UUID memberId : team.members()) {
			ServerPlayer member = server.getPlayerList().getPlayer(memberId);
			if (alive(member)) {
				return member;
			}
		}
		return null;
	}

	private static boolean alive(@Nullable ServerPlayer player) {
		return player != null && !player.isRemoved() && !player.isDeadOrDying()
				&& !player.isSpectator();
	}

	/**
	 * 미뤄 둔 몫 하나를 실제로 넣는다.
	 *
	 * <p>넣기 직전에 피격 쿨타임과 {@code lastHurt} 를 갈아 끼웠다가 <b>원래 값으로 되돌린다</b>.
	 * 갈아 끼우는 것은 이 몫이 직전 피격의 쿨타임에 삼켜지지 않으면서 피격 연출도 다시 터지지
	 * 않게 하기 위해서고(아래 「피격 연출은 처음 맞은 한 번만」), 되돌리는 것은 몫을 넣을 때마다
	 * 쿨타임이 새로 차서 「분산 중에는 몹에게 맞지 않는다」가 되지 않게 하기 위해서다.
	 *
	 * <h2>⚠ 만지는 칸이 26.3 에서 바뀌었다</h2>
	 * <p>26.2 까지는 {@code Entity.invulnerableTime} 하나가 이 일을 맡았다. 26.3 은
	 * {@code LivingEntity.damageCooldownTime} 을 따로 만들었고 <b>{@code hurtServer} 가 보는
	 * 것은 그쪽</b>이다. {@code invulnerableTime} 은 피해 쿨타임과 상관이 없어져
	 * {@code commonTick} 에서만 줄어드는 별개의 칸이 됐다.
	 *
	 * <p>0.27.0-dev 로 26.3 에 올릴 때 {@code invulnerableTime} 이 {@code private} 이 되어
	 * 접근자로 갈아 끼웠는데, <b>바닐라가 그 값의 쓰임 자체를 옮긴 것</b>은 보지 못했다. 그래서
	 * 위 두 줄이 <b>둘 다 아무 일도 하지 않았다</b> — 컴파일은 통과한다. 결과는 의도와 정반대로,
	 * 몫을 넣을 때마다 쿨타임이 20 으로 차서 <b>분산 중에는 몹 피해가 부당하게 막혔다.</b>
	 * 같은 뿌리의 회귀가 {@code PerkDamage.effectiveAmount} 에도 있었다.
	 *
	 * <h2>피격 연출은 처음 맞은 한 번만 — 몫은 「쿨타임 안 추가 피해」 갈래로 넣는다</h2>
	 * <p>예전에는 쿨타임을 0 으로 두고 넣었다. 그러면 바닐라는 몫마다 <b>새로 맞은 것</b>으로
	 * 치고 피격 연출을 전부 돌린다 — {@code broadcastDamageEvent}(클라이언트가 받아 화면을
	 * 붉히고 기울이고 피격음을 낸다), {@code markHurt}, {@code dealDefaultKnockback}(몫마다
	 * 원래 때린 쪽에서 밀려난다), {@code playHurtSound}. 서버에서 {@code hurtTime} 을 되돌리고
	 * 소리를 삼켜도 소용이 없었다. <b>붉은 번쩍임·기울기·피격음은 클라이언트가
	 * {@code LivingEntity.handleDamageEvent} 에서 스스로 만든다.</b> 그래서 여덟 몫이면 여덟 번
	 * 맞은 것처럼 보였다.
	 *
	 * <p>지금은 쿨타임을 20(&gt;10)으로, {@code lastHurt} 를 0 으로 두고 넣는다. 26.3
	 * {@code hurtServer} 바이트코드로 보면 이때 바닐라는 「쿨타임 안에 더 센 한 대가 왔다」 갈래
	 * (206~237)를 탄다.
	 *
	 * <pre>
	 *   actuallyHurt(amount - lastHurt)   // lastHurt = 0 이라 몫 전부가 들어간다
	 *   lastHurt = amount
	 *   연출 깃발(지역변수 8) = false      // ← 이것 하나로 아래 연출이 전부 빠진다
	 * </pre>
	 *
	 * <p>깃발이 꺼지면 빠지는 것은 <b>연출뿐</b>이다 — {@code broadcastDamageEvent}·
	 * {@code markHurt}·넉백·피격음·사망음(서버쪽). 나머지는 깃발과 상관없이 그대로 돈다.
	 *
	 * <ul>
	 *   <li><b>방어구·흡수·체력·사망 메시지</b> — 전부 {@code actuallyHurt} 안이다. 흡수(노란
	 *       하트)를 먼저 깎고, {@code CombatTracker.recordDamage} 로 원래 피해원을 적어 사망
	 *       메시지와 처치자가 「좀비에게 당함」 그대로 남는다.</li>
	 *   <li><b>처치자 판정</b> — {@code resolveMobResponsibleForDamage}·
	 *       {@code resolvePlayerResponsibleForDamage}(272~282)는 두 갈래가 합쳐진 뒤에 돈다.</li>
	 *   <li><b>불사의 토템과 죽음</b> — {@code isDeadOrDying → checkTotemDeathProtection → die}
	 *       (393~431)도 깃발 밖이다. 깃발이 보는 것은 그 사이의 서버쪽 사망음 한 줄뿐이고,
	 *       사망음은 {@code ServerPlayer.die} 가 보내는 엔티티 이벤트 3 을 받아 클라이언트가
	 *       따로 낸다. 토템 연출(이벤트 35)도 {@code checkTotemDeathProtection} 안이다.</li>
	 *   <li><b>받은 피해량 집계</b> — {@code StatMirror}·{@code DamageLedger} 는 다음 틱에
	 *       체력이 얼마나 줄었는지를 보는 쪽이라 갈래와 상관이 없다.</li>
	 * </ul>
	 *
	 * <p>{@code lastHurt} 와 쿨타임은 넣은 뒤 <b>둘 다 원래 값으로 되돌린다.</b> 쿨타임을
	 * 되돌리는 까닭은 위와 같고, {@code lastHurt} 까지 되돌리는 것은 몫이 바닐라의 「직전 피해량」
	 * 기억에 흔적을 남기지 않게 하기 위해서다. 무적시간 흉내는 어차피 {@link Guard} 가 따로 한다.
	 *
	 * <p>한 가지 예외가 남는다. 피해 종류가 {@code bypasses_cooldown} 이면 바닐라가 쿨타임을 보지
	 * 않고 「새로 맞음」 갈래로 간다. 26.3 바닐라의 그 태그는 비어 있어 데이터팩이 채웠을 때만
	 * 생기는 일이고, 그때를 위해 {@code hurtTime} 되돌리기와 {@code LivingEntityHurtSoundMixin}
	 * 을 그대로 남겨 둔다(클라이언트 연출까지는 못 막는다).
	 *
	 * <h2>⚠ 바닐라 갈래만으로는 모자랐다 — 팀원에게 뿌리는 피격 연출</h2>
	 * <p>126c004 에서 위 갈래로 바꾼 뒤에도 사람이 「아직도 여러 번 피격음이 난다」고 했다. 그
	 * 판은 <b>둘이서</b> 했다. 소리는 바닐라가 아니라 이 저장소의 {@link SharedHurtFeedback} 에서
	 * 나왔다. 그쪽은 {@code AFTER_DAMAGE} 에 붙어 맞은 사람을 뺀 팀원 전원에게
	 * {@code ClientboundDamageEventPacket} 을 보내는데, Fabric 이 그 사건을
	 * {@code hurtServer} <b>꼬리(TAIL)</b>에서 부르므로 연출 깃발과 상관없이 몫마다 돈다. 받은
	 * 팀원 클라이언트는 {@code handleDamageEvent} 로 피격음·붉은 번쩍임·기울기를 낸다. 거꾸로
	 * 처음 맞은 순간에는 피해가 0 으로 넘어가 그쪽이 건너뛰어서, <b>팀원에게는 맞은 순간엔 아무것도
	 * 없고 그 뒤 8초 동안 매초 피격음이 났다.</b>
	 *
	 * <p>지금은 그쪽이 {@link #isDeliveringSlice()} 를 보고 몫을 건너뛰고, 처음 맞은 순간은
	 * {@link #deferredHit} 로 「미룬 양」을 알아 한 번 뿌린다. 판정은 {@link #hurtTaken} 한 곳이고
	 * {@code on_team_hurt}·{@code pass_on_hurt} 도 같은 판정을 쓴다. 이 저장소에서 피격 연출 꾸러미를
	 * 직접 보내는 자리가 늘면 같은 판정을 지나야 한다 — {@code HurtFeedbackPathsTest} 가 그 자리를
	 * 바이트코드로 세어 붙든다.
	 *
	 * <h2>몫은 {@code Player} 의 난이도 갈래와 방패를 다시 지난다</h2>
	 * <p>{@code victim.hurtServer} 는 {@code Player.hurtServer} 부터 탄다. 거기서 난이도 배율이 다시
	 * 곱해지고, {@code LivingEntity.hurtServer} 안에서는 방패 판정이 다시 돈다. 둘 다 처음 맞을 때
	 * 이미 끝난 일이라, {@link #asSlice} 에 받는 사람과 몫을 함께 넘겨 {@link #sliceArrival}(배율을
	 * 되돌림)과 {@link #ignoresShield}(방패를 건너뜀)가 그 사람에게만 듣게 한다. 까닭은 머리말
	 * 「난이도 배율과 방패는 처음 맞을 때 한 번만」에 있다.
	 *
	 * <p>몫이 깎은 체력·흡수는 {@link #noteSliceLoss} 로 적어 피격 알림이 빼고 보게 한다.
	 *
	 * <h2>HEAD 의 「통째로 버림」 검사도 다시 지난다 — 그중 처음 맞을 때의 것은 건너뛴다</h2>
	 * <p>{@code LivingEntityPerkDamageMixin} HEAD 는 버릴 피해를 {@code false} 로 끝낸다. 몫은 이미
	 * {@link Spread#takeSlice} 로 떼어 낸 뒤라 거기서 버려지면 <b>그대로 사라진다.</b> 그래서 몫을 받는
	 * 사람({@link #receivingSlice})에게는 「판이 멈춤」(증강 선택·회차 시작 전·시련 화면)만 남기고,
	 * 처음 맞을 때 이미 지난 셋을 건너뛴다(2026-10-06 Orca 검토 F-verified 의 V8).
	 *
	 * <ul>
	 *   <li><b>버티는 방패의 낙하 면역</b> — 지금 방패를 들었는가를 본다. 떨어진 뒤 방패를 들면 남은
	 *       낙하 몫이 통째로 버려지고 방패가 몫 × 10 만큼 닳았다. 사람 말 「이미 나눠 피해받을 때 방패
	 *       올려도 막으면 안 돼」와 정면으로 어긋난다.</li>
	 *   <li><b>광역 중복</b>({@code SharedAreaDamage}) — 열쇠가 (팀, 때린 개체, 피해 종류, 게임 시각)
	 *       이고 몫은 그 틱의 개체 처리 뒤에 같은 게임 시각으로 들어간다. 같은 틱에 같은 몹이 팀원을
	 *       때렸으면 몫이 「같은 공격의 둘째」로 버려졌다.</li>
	 *   <li><b>공유 상태이상 중복</b> — 상태이상 틱 구간 안에서만 참이라 몫(서버 틱 끝)에서는 원래
	 *       거짓이지만, 「처음 맞을 때의 판정」이라 같이 건너뛴다.</li>
	 * </ul>
	 *
	 * <p>「호위」({@code damage_ward})는 <b>건너뛰지 않는다.</b> 쿨타임을 20 으로 채워 두므로
	 * 낭비 방지({@code effectiveAmount})도 {@code lastHurt} = 0 을 보고 몫 전부를 실제 피해로
	 * 세고, 호위가 돌아온 순간의 몫 하나를 막는다. 쿨타임을 0 으로 두던 예전과 같은 값이고 알고 둔
	 * 동작이다(검토 F-verified V8 (c)).
	 *
	 * <p>판이 멈춘 동안은 {@link #tick} 이 몫을 아예 진행하지 않으므로 HEAD 의 멈춤 검사에 몫이
	 * 걸리는 일은 회차 시작 전 무적뿐이다.
	 */
	private static void deliver(ServerPlayer victim, @Nullable DamageSource source, float amount) {
		if (!(amount > 0.0F)) {
			return;
		}
		ServerLevel level = victim.level();
		DamageSource actual = source != null ? source : victim.damageSources().generic();
		SpreadSliceAccess access = (SpreadSliceAccess) (Object) victim;
		int saved = victim.damageCooldownTime;
		float savedLastHurt = access.sharedfate$lastHurt();
		// bypasses_cooldown 피해만 「새로 맞음」 갈래로 새므로 그때를 위해 서버쪽 피격 표시도
		// 되돌린다. 쿨타임 안 갈래는 이 두 값을 건드리지 않는다.
		int savedHurtTime = victim.hurtTime;
		int savedHurtDuration = victim.hurtDuration;
		// 피격 알림이 이 몫을 「새로 맞았다」로 읽지 않게, 몫이 깎은 양을 재어 둔다.
		float before = victim.getHealth() + victim.getAbsorptionAmount();
		try {
			victim.damageCooldownTime = INVULNERABLE_TICKS;
			access.sharedfate$setLastHurt(0.0F);
			asSlice(victim, amount, () -> victim.hurtServer(level, actual, amount));
		} finally {
			victim.damageCooldownTime = saved;
			access.sharedfate$setLastHurt(savedLastHurt);
			victim.hurtTime = savedHurtTime;
			victim.hurtDuration = savedHurtDuration;
			noteSliceLoss(victim.getUUID(),
					before - (victim.getHealth() + victim.getAbsorptionAmount()));
		}
	}

	/**
	 * 「몫을 넣는 중」 표시를 켠 채로 {@code body} 를 돌린다. 끝나면(터져도) 반드시 끈다.
	 *
	 * <p>표시를 켜는 자리는 여기 하나다. {@link #deliver} 도 시험도 이것을 지나므로, 시험이
	 * 「몫을 넣는 중이면 팀원에게 피격 연출이 안 나간다」를 볼 때 생산 코드와 같은 표시를 본다.
	 *
	 * @param target 몫을 받는 사람. {@link #sliceArrival}·{@link #ignoresShield} 가 이 사람에게만 듣는다
	 * @param amount 넣으려는 몫
	 */
	private static void asSlice(@Nullable Entity target, float amount, Runnable body) {
		DELIVERING.set(Boolean.TRUE);
		IN_FLIGHT.set(target == null ? null : new SliceInFlight(target, amount));
		try {
			body.run();
		} finally {
			DELIVERING.set(Boolean.FALSE);
			IN_FLIGHT.remove();
		}
	}

	private static void asSlice(Runnable body) {
		asSlice(null, 0.0F, body);
	}

	/** 지금 미뤄 둔 몫을 다시 넣는 중인가. 큐가 하나도 없으면 첫 줄에서 곧바로 거짓이다. */
	public static boolean isDeliveringSlice() {
		return !ACTIVE.isEmpty() && Boolean.TRUE.equals(DELIVERING.get());
	}

	// ------------------------------------------------------------------ 회복 금지

	/**
	 * 이 대상은 지금 회복되지 않는가. {@code LivingEntity.heal} 진입 시점마다 불린다.
	 *
	 * <p>큐가 살아 있는 팀의 <b>팀원 전원</b>이 막힌다. 체력이 공유라 누가 회복해도 미뤄 둔 몫이
	 * 지워지기 때문이다. 자연 회복·재생 상태이상·금사과가 모두 {@code heal} 한 지점을 지나므로
	 * 여기 하나만 막으면 「나뉘어 들어오는 동안 회복되지 않는다」가 성립한다.
	 *
	 * <p>미뤄 둔 몫이 하나도 없으면 맵이 비었는지만 보고 곧바로 거짓이라, 평소 회복 경로에는
	 * 사실상 아무 부담도 얹히지 않는다.
	 *
	 * <p><b>{@code FoodData.tick} 의 자연 회복은 소모도를 먼저 쌓고 회복을 부른다.</b> 회복만
	 * 막으므로 그 4초 동안은 회복 없이 배만 고파진다. {@code no_natural_regen} 이 {@code isHurt}
	 * 를 가로채 그 문제를 피한 것과 다른 점인데, 그러려면 mixin 이 하나 더 필요하고 재생
	 * 상태이상은 또 따로 막아야 한다. 4초짜리 대가에는 지나친 값이라고 보았다.
	 */
	public static boolean blocksHealing(@Nullable LivingEntity entity) {
		if (ACTIVE.isEmpty() || !(entity instanceof ServerPlayer player)) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}
		ShareTeam team = TeamManager.get(server).teamOf(player.getUUID());
		return team != null && isSpreading(team.teamId());
	}

	/**
	 * 이 팀이 지금 피해를 나누어 받는 중인가.
	 *
	 * <p>표가 남아 있는 것만으로는 참이 아니다. 넣을 몫이 다 들어간 뒤에도 흉내 낸 무적시간이
	 * 다 흐를 때까지는 표가 남아 있는데, 그동안 회복까지 막으면 대가가 약속보다 길어진다.
	 */
	public static boolean isSpreading(@Nullable UUID teamId) {
		Spread spread = teamId == null ? null : ACTIVE.get(teamId);
		return spread != null && spread.pending();
	}

	/** 이 팀이 아직 넣지 않은 피해의 합계. 없으면 0. 화면 표시와 시험에 쓴다. */
	public static float remaining(@Nullable UUID teamId) {
		Spread spread = teamId == null ? null : ACTIVE.get(teamId);
		return spread == null || !spread.pending() ? 0.0F : spread.remaining;
	}

	// ------------------------------------------------------------------ 정리

	/** 서버가 멈출 때 미뤄 둔 몫을 모두 지운다. 다음 월드로 넘어가지 않게 한다. */
	public static void reset() {
		ACTIVE.clear();
		DEFERRED_HITS.clear();
		SLICE_LOSS.clear();
		DEFERRED_FOR_ALERT.clear();
		DELIVERING.remove();
		IN_FLIGHT.remove();
		warned = false;
		deathWarned = false;
	}

	/** 팀이 전멸·해체될 때 그 팀의 몫만 지운다. */
	public static void forget(@Nullable UUID teamId) {
		if (teamId != null) {
			ACTIVE.remove(teamId);
		}
	}

	/**
	 * 아직 넣지 않은 몫만 지운다. 표는 남긴다.
	 *
	 * <p>{@link #forget} 과 다른 점이 여기다. {@code forget} 은 흉내 낸 무적시간까지 통째로
	 * 버리므로 <b>팀이 사라질 때</b>만 맞고, 판이 계속되는 중에 쓰면 그 직후의 피해가 바닐라보다
	 * 많이 쌓인다. 회복 금지는 {@link #isSpreading} 이 「넣을 몫이 남았는가」를 보므로 여기서
	 * 함께 풀린다.
	 *
	 * @return 지워진 피해량. 지울 것이 없었으면 0
	 */
	public static float clearPending(@Nullable UUID teamId) {
		Spread spread = teamId == null ? null : ACTIVE.get(teamId);
		if (spread == null || !spread.pending()) {
			return 0.0F;
		}
		float cleared = spread.remaining;
		spread.closeQueue();
		return cleared;
	}

	/**
	 * 이 팀은 처치로 남은 몫을 지울 수 있는가.
	 *
	 * <p>{@code spread_damage} 를 가지지 않은 팀에는 애초에 큐가 생기지 않지만, 다른 증강이
	 * 큐를 만들 길이 생기더라도 「완충」이 없는 팀이 그 덕을 보지 않도록 명시적으로 확인한다.
	 * 월드를 보지 않는 순수 판정이라 그대로 시험할 수 있다.
	 */
	static boolean clearsOnKill(@Nullable TeamState state) {
		return state != null && state.perksEnabled && !state.ownedPerks.isEmpty()
				&& sliceCountOf(state) > 0;
	}

	/**
	 * {@code ServerLivingEntityEvents.AFTER_DEATH} 에 붙는 지점. 죽은 것이 무엇이냐에 따라 하는
	 * 일이 둘로 갈린다.
	 *
	 * <ul>
	 *   <li><b>팀원이 죽었다</b> — 이 모드에서 죽음은 팀 전멸로 이어진다. 미뤄 둔 몫을 그대로
	 *       두면 다음 회차의 첫 몇 초를 지난 회차의 피해로 시작하게 되므로 통째로 지운다.</li>
	 *   <li><b>몹이 죽었고 죽인 것이 팀원이다</b> — 「완충」의 보상이다. 남은 몫만 지운다.</li>
	 * </ul>
	 *
	 * <p>몹이 죽는 모든 자리를 지나므로 어떤 예외도 밖으로 내보내지 않는다. 분산 하나가 잘못돼
	 * 사망 처리가 멈추면 안 된다.
	 */
	public static void onDeath(LivingEntity entity, DamageSource source) {
		try {
			if (entity instanceof ServerPlayer player) {
				forgetTeamOf(player);
				return;
			}
			clearOnKill(entity, source);
		} catch (RuntimeException error) {
			warnDeathOnce(error);
		}
	}

	private static void forgetTeamOf(ServerPlayer player) {
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		ShareTeam team = TeamManager.get(server).teamOf(player.getUUID());
		if (team != null) {
			forget(team.teamId());
		}
	}

	/**
	 * 몹 처치로 남은 몫을 지운다.
	 *
	 * <p>「적」은 {@link Mob} 이다. {@code Player} 는 {@code Mob} 이 아니라서 다른 플레이어를
	 * 죽여도 걸리지 않고, 갑옷 거치대처럼 {@code Mob} 이 아닌 {@code LivingEntity} 도 빠진다.
	 * {@code PerkKillRewards} 가 {@code on_kill} 에서 쓰는 기준과 <b>여기까지는</b> 같다.
	 *
	 * <p><b>그쪽과 일부러 다른 것이 둘 있다.</b> 「잡은 사람이 아직 살아 있는가」
	 * ({@code isRemoved}·{@code isDeadOrDying})와 「공유 체력이 0보다 큰가」를 보지 않는다.
	 * 보상은 <b>주는</b> 것이라 죽은 사람에게 주거나 전멸 처리 중에 주면 어긋나지만, 이쪽은
	 * <b>면제</b>다 — 막 죽어 가며 낸 마지막 처치나 전멸 직전의 처치에도 남은 몫을 지우는 편이
	 * 사람이 기대하는 모양이고, 어차피 그 뒤에 팀이 정리되면 큐도 함께 사라진다.
	 *
	 * <p>죽인 것이 <b>팀원 아무나</b>면 된다. 체력이 팀 공유라 큐도 팀에 하나뿐이기 때문이다.
	 * {@code DamageSource.getEntity()} 는 화살을 쏜 사람도 가리키므로 원거리 처치도 세어진다.
	 *
	 * <p>알림은 띄우지 않는다. 분산이 끝난 것은 화면의 체력과 회복이 풀리는 것으로 곧바로
	 * 드러나고, 전투 중에 처치마다 채팅이 뜨면 시끄럽기만 하다.
	 */
	private static void clearOnKill(LivingEntity victim, @Nullable DamageSource source) {
		// 미뤄 둔 몫이 하나도 없으면 여기서 끝난다. 몹이 죽는 모든 자리를 지나는 코드라
		// 평소에는 이 한 줄만 돈다.
		if (ACTIVE.isEmpty() || !(victim instanceof Mob)) {
			return;
		}
		if (source == null || !(source.getEntity() instanceof ServerPlayer killer)) {
			return;
		}
		MinecraftServer server = killer.level().getServer();
		if (server == null) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		ShareTeam team = manager.teamOf(killer.getUUID());
		if (team == null || !clearsOnKill(manager.stateByTeamId(team.teamId()))) {
			return;
		}
		float cleared = clearPending(team.teamId());
		if (cleared > 0.0F) {
			SharedFateMod.LOGGER.debug("「완충」: {} 의 처치로 남은 분산 피해 {} 를 지웠습니다.",
					killer.getGameProfile().name(), cleared);
		}
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"피해 분산을 처리하지 못해 이번 피해는 그대로 들어갑니다. 이 경고는 한 번만 남습니다.",
				error);
	}

	private static void warnDeathOnce(RuntimeException error) {
		if (deathWarned) {
			return;
		}
		deathWarned = true;
		SharedFateMod.LOGGER.warn(
				"사망 처리에서 피해 분산을 정리하지 못했습니다. 이 경고는 한 번만 남습니다.", error);
	}

	/** 시험이 상태를 격리할 때 쓴다. */
	static void resetForTesting() {
		reset();
	}

	/**
	 * 시험이 「나뉘는 중」인 큐를 심을 때 쓴다.
	 *
	 * <p>제대로 된 길({@link #intercept})은 살아 있는 {@code ServerPlayer} 와 서버를 요구해서
	 * 이 저장소의 시험 환경에서는 지날 수 없다. 심는 값은 그 길이 만들어 내는 것과 같은 모양이다.
	 */
	static void queueForTesting(UUID teamId, float amount, int slices) {
		Spread spread = new Spread();
		spread.remaining = amount;
		spread.slicesLeft = slices;
		spread.ticksToNextSlice = SpreadDamageEffect.SLICE_PERIOD_TICKS;
		ACTIVE.put(teamId, spread);
	}

	/** 시험이 「몫 하나가 이미 들어갔다」를 만들 때 쓴다. 실제 진행과 같은 계산을 지난다. */
	static float takeSliceForTesting(UUID teamId) {
		Spread spread = ACTIVE.get(teamId);
		return spread == null ? 0.0F : spread.takeSlice();
	}

	/**
	 * 시험이 「몫을 넣는 중」을 만들 때 쓴다. {@link #deliver} 가 쓰는 {@link #asSlice} 를 그대로
	 * 지난다. {@code hurtServer} 는 살아 있는 플레이어가 있어야 불러 볼 수 있어, 그 자리에서
	 * 돌았을 {@code AFTER_DAMAGE} 를 시험이 {@code body} 안에서 직접 부른다.
	 */
	static void asSliceForTesting(Runnable body) {
		asSlice(body);
	}

	/**
	 * 시험이 「이 사람에게 이 몫을 넣는 중」을 만들 때 쓴다. {@link #deliver} 가 부르는 그
	 * {@link #asSlice} 를 그대로 지난다. {@code body} 안에서 시험이 {@code hurtServer} HEAD 의
	 * {@link #sliceArrival} 과 {@code applyItemBlocking} HEAD 의 {@link #ignoresShield} 를 부른다.
	 */
	static void asSliceForTesting(Entity target, float amount, Runnable body) {
		asSlice(target, amount, body);
	}

	/** 이 팀의 표가 아직 남아 있는가. {@link #forget} 과 {@link #clearPending} 을 가르는 값이다. */
	static boolean trackedForTesting(UUID teamId) {
		return ACTIVE.containsKey(teamId);
	}

	// ------------------------------------------------------------------ 자료

	/**
	 * 무적시간 판정의 결과.
	 *
	 * @param accepted          이번에 큐에 넣을 몫. 0 이면 바닐라였어도 들어가지 않았을 피해다
	 * @param lastAmount        판정 뒤의 「직전 피해량」
	 * @param invulnerableTicks 판정 뒤의 남은 무적시간
	 */
	public record Gate(float accepted, float lastAmount, int invulnerableTicks) {
	}

	/**
	 * 팀 하나가 미뤄 둔 몫. 저장하지 않는다 — 서버가 다시 뜨면 미뤄 둔 피해는 사라진다.
	 *
	 * <p>필드를 건드리는 것은 피해 처리와 서버 틱뿐이고 둘 다 서버 스레드라, 안쪽에는 평범한
	 * 자료구조를 쓴다. 팀 사이의 경합만 바깥의 {@code ACTIVE} 가 막는다.
	 */
	private static final class Spread {
		/** 사람마다 흉내 내고 있는 무적시간. 팀 인원만큼만 자란다. */
		final Map<UUID, Guard> guards = new HashMap<>();
		/** 마지막으로 맞은 사람. 몫을 받을 첫 후보다. */
		@Nullable UUID victimId;
		/** 마지막 피해원. 사망 메시지와 피해 종류가 마지막 한 방을 따라간다. */
		@Nullable DamageSource source;
		/** 아직 넣지 않은 몫의 합계. */
		float remaining;
		/** 남은 횟수. 맞을 때마다 늘어나지 않는다 — 남은 시간 안에서 다시 나눌 뿐이다. */
		int slicesLeft;
		int ticksToNextSlice;

		/** 아직 넣을 몫이 남아 있는가. 표가 남아 있는 것과는 다른 물음이다. */
		boolean pending() {
			return slicesLeft > 0 && remaining > 0.0F;
		}

		/**
		 * 이번에 넣을 몫을 떼어 낸다. 남은 몫과 남은 횟수가 함께 줄어든다.
		 *
		 * <p>떼어 내는 것과 실제로 넣는 것을 나눠 두어, 넣는 쪽({@link #deliver})이 살아 있는
		 * 플레이어를 요구해도 이 계산만은 그대로 시험할 수 있다.
		 */
		float takeSlice() {
			float slice = sliceAmount(remaining, slicesLeft);
			remaining -= slice;
			slicesLeft--;
			return slice;
		}

		/**
		 * 받아들인 몫을 더하고 흉내 낸 무적시간을 갱신한다.
		 *
		 * <p>이미 나누는 중이면 <b>남은 횟수와 다음 차례를 그대로 둔다</b>. 그래야 「남은 몫과
		 * 합쳐 남은 시간에 다시 나눈다」가 되고, 맞을 때마다 시간이 늘어나지 않는다.
		 */
		void add(ServerPlayer victim, @Nullable DamageSource damageSource, Gate gate, int slices) {
			if (!pending()) {
				remaining = 0.0F;
				slicesLeft = Math.max(1, slices);
				ticksToNextSlice = SpreadDamageEffect.SLICE_PERIOD_TICKS;
			}
			remaining += gate.accepted();
			victimId = victim.getUUID();
			source = damageSource;
			Guard guard = guards.computeIfAbsent(victim.getUUID(), key -> new Guard());
			guard.lastAmount = gate.lastAmount();
			guard.invulnerableTicks = gate.invulnerableTicks();
		}

		/**
		 * 몫을 다 넣었다. 나눗셈에서 남은 부스러기를 털고 피해원 참조를 놓아 준다.
		 *
		 * <p>표 자체는 흉내 낸 무적시간이 다 흐를 때까지 남는다.
		 */
		void closeQueue() {
			remaining = 0.0F;
			slicesLeft = 0;
			ticksToNextSlice = 0;
			source = null;
			victimId = null;
		}

		/** 흉내 낸 무적시간을 한 틱 흘린다. 다 흐른 사람은 표에서 뺀다. */
		void tickGuards() {
			guards.values().removeIf(guard -> --guard.invulnerableTicks <= 0);
		}
	}

	/** 한 사람의 흉내 낸 무적시간. */
	private static final class Guard {
		float lastAmount;
		int invulnerableTicks;
	}

	/** 지금 넣고 있는 몫 하나. {@link #asSlice} 동안만 산다. */
	private static final class SliceInFlight {
		final Entity target;
		final float amount;
		/** 이 몫이 이미 {@code hurtServer} HEAD 에 닿아 원래 값으로 되돌려졌는가. 한 번만 되돌린다. */
		boolean arrived;

		SliceInFlight(Entity target, float amount) {
			this.target = target;
			this.amount = amount;
		}
	}
}
