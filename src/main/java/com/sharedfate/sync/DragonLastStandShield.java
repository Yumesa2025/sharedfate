package com.sharedfate.sync;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 「최후의 저항」 <b>진입 보호막.</b> 체력 30% 에 닿은 틱부터 <b>첫 패턴이 실제로 시작되는 틱</b>
 * 직전까지 드래곤으로 들어오는 피해를 <b>피해 처리 전에</b> 통째로 거절하고, 그 동안 드래곤
 * 둘레에 반구 보호막을 그린다.
 *
 * <p>사람이 2026-10-04 에 <b>「최후의 저항 시작하고 첫 패턴 전까지 모든 공격 막는 쉴드 생기고 피해
 * 안 받게. 지금은 무슨 체력회복하면서 쳐맞는 거 같아」</b>라고 했다.
 *
 * <h2>고치기 전에 실제로 일어나던 일 — 둘이었다</h2>
 *
 * <ol>
 *   <li><b>진입 연출 8초 동안 「맞고 나서 덮어쓰였다」.</b> {@link DragonLastStandEntry} 가 매 틱
 *       {@code setHealth} 로 목표값을 쓰는 것이 무적 역할이었는데, 그 줄은
 *       {@code END_SERVER_TICK} 에서 돈다. 그 사이 피해는 {@code EnderDragon.hurt} →
 *       {@code reallyHurt} → {@code LivingEntity.hurtServer} 를 <b>끝까지</b> 지나 피격 번쩍임·
 *       피격음·화살 박힘이 그대로 나가고, 체력만 그 틱 끝에 다시 올라갔다 — 사람이 본
 *       <b>「체력회복하면서 쳐맞는」</b> 것이 정확히 그것이다</li>
 *   <li><b>연출이 끝나고 첫 패턴까지 3초는 아무 장치도 없었다.</b> {@code DragonLastStand.tick} 이
 *       {@code now >= cinematicUntil} 부터 {@link DragonLastStandEntry#tick} 을 안 부르므로
 *       {@code setHealth} 도 멈추고, 첫 패턴은 {@code Stand.nextPatternAt = clockBase +
 *       ENTRY_GRACE_TICKS}(연출 끝 + 60틱)에야 고른다. 그 60틱 동안 들어간 피해는 <b>진짜로
 *       깎였다</b> — 방금 채운 20%p 를 그 자리에서 되깎을 수 있었다</li>
 * </ol>
 *
 * <h2>어디서 막는가 — {@code EnderDragonPerchRangedImmunityMixin} 의 같은 자리</h2>
 *
 * <p>드래곤이 맞는 길은 전부 {@code EnderDragon.hurt(ServerLevel, EnderDragonPart, DamageSource,
 * float)} 하나로 모인다(26.3 바이트코드로 다시 확인했다).
 *
 * <ul>
 *   <li><b>부위</b> — {@code EnderDragonPart.hurtServer} 는 {@code final} 이고 본문이
 *       {@code isInvulnerableToBase} 검사 한 줄 뒤 {@code parentMob.hurt(level, this, ...)} 다.
 *       사람이 머리·몸통·날개를 때리는 길, 화살·폭발이 부위 상자에 맞는 길이 전부 이것이다</li>
 *   <li><b>본체</b> — {@code EnderDragon.hurtServer} 는 {@code hurt(level, body, ...)} 한 줄이다.
 *       명령({@code /damage})·효과처럼 부위를 거치지 않는 피해다</li>
 *   <li><b>엔드 크리스탈 폭발</b> — {@code EnderDragon.onCrystalDestroyed} 가 이어 둔 크리스탈이
 *       깨지면 {@code hurt(level, head, explosion, 10)} 을 부른다. 같은 문이다. 진입 때 크리스탈을
 *       전부 {@code discard} 하므로 실제로는 안 오지만 와도 막힌다</li>
 * </ul>
 *
 * <p>그 문의 <b>{@code HEAD}</b> 에서 {@code false} 를 돌려주면 {@code onHurt}·부위 보정·
 * {@code reallyHurt} 가 하나도 안 돈다. 곧 {@code LivingEntity.hurtServer} 에 닿지 않으므로
 * <b>피격 번쩍임(피해 이벤트 꾸러미)·피격음·넉백이 나갈 자리가 없다.</b> 화살은 맞은 것이
 * 거절되면 바닐라가 튕겨 내 박히지 않는다. 같은 자리에 믹스인을 둘 만들지 않고 이미 걸린
 * {@code EnderDragonPerchRangedImmunityMixin} 의 처리기 맨 앞에 한 갈래를 더했다.
 *
 * <p>⚠ <b>막지 않는 것</b>은 둘이다.
 *
 * <ul>
 *   <li><b>{@code #minecraft:bypasses_invulnerability}</b>({@code out_of_world}·{@code generic_kill}) —
 *       바닐라가 「무적을 지나친다」고 태그에 적어 둔 것이라 막으면 운영자가 치울 길이 없다.
 *       {@code DragonPerch.melee} 와 같은 예외이고 근거도 같다. ({@code /kill} 자체는
 *       {@code EnderDragon.kill} 이 {@code remove(KILLED)} 를 바로 부르는 길이라 이 문을 아예 안
 *       지나지만, 공허와 {@code /damage ... minecraft:generic_kill} 은 지난다)</li>
 *   <li><b>회복</b> — {@code heal}·{@code setHealth} 는 피해가 아니라 이 문을 안 지난다. 진입
 *       연출의 차오르는 체력({@link DragonLastStandEntry#rampedHealth})이 바로 그것이고 <b>그대로
 *       돈다.</b> 보호막은 그것과 별개의 「막기」다</li>
 * </ul>
 *
 * <h2>언제 켜져 있는가 — 세션 상태에서만 판다</h2>
 *
 * <p>{@link #shielded} 가 유일한 판정이다. <b>새로 진입한 판</b>({@code Stand} 가 진입 연출을
 * 돌리는 판)에서 {@code beganAt} 부터 <b>첫 패턴을 고른 틱 직전</b>까지다. 첫 패턴을 고른
 * 그 틱({@code DragonLastStand.advance} 가 {@code stand.running} 을 처음 세우는 틱)부터는 평소대로
 * 맞는다. 정상 흐름에서 그 틱은 {@code beganAt + LENGTH_TICKS + ENTRY_GRACE_TICKS} = 진입 뒤
 * 220틱이고 — <b>팀의 저항 V 가 풀리는 틱과 같다</b>({@code DragonLastStand.grantGrace} 가 같은
 * 합을 넘긴다). 「둘 다 무적인 동안은 서로 못 때리고, 판이 시작되는 순간 함께 풀린다」.
 *
 * <p>⚠ <b>{@code setInvulnerable} 도 NBT 도 쓰지 않는다.</b> {@code Invulnerable} 태그는 개체와
 * 함께 저장되므로 보호막 도중 서버가 죽으면 다시 떴을 때 무적인 드래곤이 남는다
 * ({@link DragonLastStandEntry} 클래스 설명의 표). 여기 있는 것은 정적 칸 몇 개뿐이고, 서버를
 * 껐다 켜면 {@code DragonLastStand.resume} 이 되살린 판은 진입 연출을 안 돌리므로 보호막도 안
 * 선다 — 「되살린 판에는 아무것도 다시 주지 않는다」와 같은 규칙이다.
 *
 * <h3>⚠ 깃발이 켜진 채 멈추지 않는다 — 서버 틱 맥박</h3>
 *
 * <p>믹스인이 읽는 깃발은 {@code DragonLastStand.tick} 이 <b>매 틱 다시 세워야만</b> 살아 있다.
 * 세운 틱의 {@code MinecraftServer.getTickCount()} 를 함께 적고, 믹스인은 지금 틱 수와 그 값이
 * {@value #PULSE_SLACK_TICKS} 틱 넘게 벌어지면 꺼진 것으로 읽는다({@link #alive}).
 *
 * <p>까닭은 <b>그 tick 이 안 불리는 길이 여럿</b>이기 때문이다 — 팀 설정에서 드래곤 시련을 끄면
 * {@code DragonTrialManager.tickSessions} 가 그 팀을 {@code continue} 로 지나가고, 팀이 해체되면
 * {@code onFightClosed} 없이 세션만 지워진다. {@code contactDamageOff} 처럼 「내리는 줄」에만
 * 기대면 그 길에서 <b>영영 안 맞는 드래곤</b>이 남는다. 그 사고는 접촉 피해가 안 들어가는 것보다
 * 훨씬 비싸다(전투가 끝나지 않는다).
 *
 * <ul>
 *   <li>틱 수는 {@code tickServer} 첫머리에서 오르고 {@code END_SERVER_TICK} 은 끝에서 돈다.
 *       그래서 틱 사이(사람의 공격 꾸러미가 처리되는 자리)에서는 차이가 0, 다음 틱의 월드
 *       처리(화살·폭발) 동안 1 이다. 2 가 되면 <b>한 틱을 통째로 못 세운 것</b>이다</li>
 *   <li>{@code getGameTime()} 이 아니다. 판이 얼면 멈추는 시계는 여기서 맥박이 될 수 없다</li>
 * </ul>
 *
 * <h2>보이게 한다</h2>
 *
 * <ul>
 *   <li><b>막힐 때</b> — {@link #deflect}. {@code DragonPerch.deflect}·{@code TrialCrystalLink.deflect}
 *       와 <b>같은 한 벌</b>({@code ELECTRIC_SPARK} {@value #DEFLECT_POINTS}점 + {@code SHIELD_BLOCK}
 *       음높이 0.8, 불꽃 한 틱 한 번 · 소리 {@value #DEFLECT_SOUND_GAP_TICKS}틱 한 번)이다.
 *       「전기 불꽃 + 방패 소리 = 그 한 방은 안 들어갔다」가 이 판의 유일한 「막혔다」다.
 *       ⚠ <b>{@code DragonPerch.deflect} 를 그대로 부르지 않았다.</b> 그쪽은 시각을
 *       {@code DragonPerch.tick} 이 적어 둔 {@code nowTick} 에서 읽는데, 최후의 저항이 열리면
 *       {@code DragonPassives.tick} 이 아예 안 불려 그 값이 <b>얼어 있다</b> — 그러면 첫 한 방
 *       뒤로 불꽃도 소리도 다시는 안 나간다. 값은 같고 시계만 우리 것이다</li>
 *   <li><b>켜져 있는 동안</b> — {@link #drawDome}. 드래곤 발밑을 중심으로 한 반경
 *       {@value #DOME_RADIUS} 반구를 옅은 하늘색 먼지 {@value #DOME_POINTS}점으로 긋는다</li>
 *   <li><b>걷힐 때</b> — {@link #shatter}. 유리 깨지는 소리 한 번과 파편 꾸러미 둘</li>
 * </ul>
 *
 * <h2>점 예산 — 5장의 400~440</h2>
 *
 * <p>보호막이 켜져 있는 틱에는 <b>패턴도 번개도 오브젝트 파도도 돌지 않는다</b> — 셋 다 원점이
 * {@code clockBase} 뒤이거나(번개·패턴) 체력 25% 아래에서만 열린다(파도; 보호막 동안 체력은
 * 진입값 + 20%p 에서 한 점도 안 내려간다). 그래서 남과 겹치는 것은 진입 연출 하나다.
 *
 * <table border="1">
 *   <caption>보호막이 켜진 틱의 최악</caption>
 *   <tr><th>무엇</th><th>점</th></tr>
 *   <tr><td>반구 한 틱 몫({@value #DOME_POINTS} ÷ {@value #DOME_STRIDE})</td><td>{@value #DOME_POINTS_PER_TICK}</td></tr>
 *   <tr><td>막힘 불꽃(한 틱 한 번)</td><td>{@value #DEFLECT_POINTS}</td></tr>
 *   <tr><td>진입 연출 하강 궤적({@code DragonLastStandEntry.WORST_TICK_POINTS})</td><td>12</td></tr>
 *   <tr><td><b>합</b></td><td><b>{@code worstShieldTickPoints()} = 54</b></td></tr>
 * </table>
 *
 * <p>걷히는 틱은 첫 패턴이 시작하는 틱이라 그쪽 최악({@code DragonLastStandPatterns
 * .worstCasePointsPerTick} 322)과 겹친다. 그 틱에 반구는 이미 안 그리고 파편은 개수를 세는
 * 꾸러미 <b>{@value #SHATTER_POINTS}장</b>뿐이다 — 322 + {@value #SHATTER_POINTS} 이고, 파도의 37 을
 * 억지로 더해도 361 / 440 이다(2026-10-04 에 패턴 쪽이 360 → 322 로 내려오기 전에는 399). {@code DragonLastStandShieldTest} 가 이 셈을 붙든다.
 */
public final class DragonLastStandShield {

	// ------------------------------------------------------------------ 값

	/**
	 * 반구 반경(칸).
	 *
	 * <p>앉은 드래곤의 상자가 가로 16칸이라 반이 8 이다({@code DragonLastStandEntry.BEACON_HALF_SIDE}
	 * 의 같은 근거). 9 면 날개 끝 바로 밖이고, 보라 신호기 넷(반변 10)보다는 안이라 「빛 넷 안에
	 * 보호막」으로 읽힌다. 머리가 몸 앞 6.5칸이므로 머리도 막 안이다.
	 */
	static final double DOME_RADIUS = 9.0;

	/**
	 * 반구를 이루는 점 수.
	 *
	 * <p>반구 겉넓이가 {@code 2π × 9² ≈ 509} 칸²라 180점이면 점 사이가 약 1.7칸이다 — 막으로 읽히는
	 * 성김의 끝이다. 더 성기면 「점 몇 개가 떠 있다」가 된다.
	 */
	static final int DOME_POINTS = 180;

	/**
	 * 반구를 몇 틱에 나눠 긋는가.
	 *
	 * <p>⚠ <b>먼지 수명(최소 8틱)보다 짧아야</b> 막이 닫힌 채 보인다(5장 「{@code stride} 상한은
	 * 입자 수명에 묶여 있습니다」). 5 면 한 바퀴가 끝나기 전에 앞 점이 안 꺼진다.
	 */
	static final int DOME_STRIDE = 5;

	/** 한 틱에 긋는 반구 점의 상한. 나눠 그리기가 위상에 따라 하나 더 나가는 일은 없다(180 ÷ 5 가 딱 떨어진다). */
	static final int DOME_POINTS_PER_TICK = (DOME_POINTS + DOME_STRIDE - 1) / DOME_STRIDE;

	/**
	 * 반구 색. 옅은 하늘색.
	 *
	 * <p>{@code TrialWarning.Colors} 를 쓰지 않는다 — 그쪽은 <b>바닥에 그리는 위험 표식</b>의 규약
	 * (빨강 = 서 있으면 죽는다, 파랑 = 밀려난다, 보라 = 너를 노린다)이고 보호막은 표식이 아니다.
	 * {@code DragonLastStand.vanishCrystals} 가 같은 이유로 색 규약 밖의 보라를 쓴다. 규약의 파랑
	 * {@code SHOVE}(0x4AA3FF)와 헷갈리지 않게 훨씬 옅게 잡았다.
	 */
	static final int DOME_COLOR = 0xA8F0FF;

	/** 반구 먼지 크기. 1.0 이 레드스톤 가루다 — 9칸 떨어진 막이 보이려면 그보다 커야 한다. */
	private static final float DOME_DUST_SCALE = 1.5F;

	/** 반구를 드래곤 발밑에서 이만큼 올려 세운다(칸). 0 이면 맨 아랫줄이 포디움에 묻힌다. */
	private static final double DOME_LIFT = 0.3;

	/**
	 * 막았다는 소리를 다시 낼 때까지의 간격(틱).
	 *
	 * <p>{@code DragonPerch.DEFLECT_SOUND_GAP_TICKS} 와 <b>같은 값이고 같은 근거</b>다 — 10 은
	 * 바닐라 피격 무적시간이라 한 사람이 같은 대상을 때릴 수 있는 가장 짧은 간격이다. 그쪽 상수가
	 * {@code private} 이라 옮겨 적었다.
	 */
	static final int DEFLECT_SOUND_GAP_TICKS = 10;
	/** 한 번 막을 때 튀는 불꽃 수. {@code DragonPerch.DEFLECT_POINTS} 와 같다. */
	static final int DEFLECT_POINTS = 6;
	/** 불꽃을 맞은 자리 주위로 흩는 거리(칸). {@code DragonPerch.DEFLECT_SPREAD} 와 같다. */
	private static final double DEFLECT_SPREAD = 0.6;
	/** 막힘 소리 음높이. {@code DragonPerch.deflect} 와 같다. */
	private static final float DEFLECT_PITCH = 0.8F;

	/**
	 * 보호막이 걷힐 때 나가는 꾸러미 수. 개수를 세는 형태 둘(먼지 파편 · 불꽃 파편)이다.
	 *
	 * <p>5장의 셈법대로 개수를 세는 꾸러미는 한 장이 1점이다
	 * ({@code DragonLastStandPatterns.suckPoints} 의 「파티클 꾸러미 둘」과 같다).
	 */
	static final int SHATTER_POINTS = 2;

	/**
	 * 깨지는 소리 음높이. 1.0 보다 낮춰 「유리컵」이 아니라 「큰 막」이 깨지는 소리로 들리게 한다.
	 */
	private static final float SHATTER_PITCH = 0.6F;

	/**
	 * 맥박이 이만큼 넘게 끊기면 꺼진 것으로 읽는다(서버 틱).
	 *
	 * <p>1 인 까닭은 클래스 설명의 「서버 틱 맥박」에 있다 — 틱 사이에서 0, 다음 틱의 월드 처리
	 * 중에 1 이 정상이다.
	 */
	static final int PULSE_SLACK_TICKS = 1;

	// ------------------------------------------------------------------ 믹스인이 읽는 칸

	/**
	 * 지금 보호막이 서 있는가. <b>{@code EnderDragonPerchRangedImmunityMixin} 이 읽는다.</b>
	 *
	 * <p>{@code volatile} 인 까닭은 {@code DragonLastStand.contactDamageOff} 와 같다.
	 */
	private static volatile boolean raised;
	/** {@link #raised} 를 마지막으로 세운 서버 틱 수. 믹스인이 맥박으로 읽는다. */
	private static volatile int raisedAtServerTick;
	/**
	 * 넘겨받은 마지막 {@code now}. 믹스인이 시각을 알 길이 이것뿐이다.
	 *
	 * <p>{@code DragonPerch.nowTick} 과 같은 장치이고, 그쪽을 못 쓰는 까닭은 클래스 설명에 있다.
	 */
	private static volatile long nowTick = Long.MIN_VALUE;

	/** 마지막으로 막힘 소리를 낸 틱. */
	private static long deflectHeardAt = Long.MIN_VALUE;
	/** 마지막으로 막힘 불꽃을 그린 틱. 한 틱에 한 번으로 묶는다. */
	private static long deflectDrawnAt = Long.MIN_VALUE;
	/**
	 * 마지막으로 반구를 그린 틱. 같은 틱에 두 번 불려도 한 번만 긋는다 — 팀이 둘이던 때는
	 * {@code DragonLastStand.tick} 이 한 틱에 두 번 불렀고(지금은 시련 세션이 하나다, 2026-10-06 Orca 검토
	 * F-verified 의 V2), 판이 얼어 있는 틱에는 {@code DragonLastStand.holdShield} 가 <b>같은 게임 시각</b>으로
	 * 매 서버 틱 다시 부른다(V3). 두 길 다 이 칸이 막는다.
	 */
	private static long domeDrawnAt = Long.MIN_VALUE;

	private DragonLastStandShield() {
	}

	// ------------------------------------------------------------------ 월드 없이 도는 판정

	/**
	 * 이 틱에 보호막이 서 있어야 하는가. <b>유일한 판정이다.</b>
	 *
	 * @param cinematicEntry 진입 연출을 돌린 판인가. 재시작으로 되살린 판은 거짓이다
	 * @param beganAt        최후의 저항이 시작한 틱
	 * @param firstPatternAt 첫 패턴을 고른 틱. 아직이면 {@link Long#MIN_VALUE}
	 * @param now            받은 틱
	 */
	static boolean shielded(boolean cinematicEntry, long beganAt, long firstPatternAt, long now) {
		if (!cinematicEntry || now < beganAt) {
			return false;
		}
		// MIN_VALUE 는 「아직 안 골랐다」다. 뺄셈에 넣지 않는다.
		return firstPatternAt == Long.MIN_VALUE || now < firstPatternAt;
	}

	/**
	 * 이 한 방을 <b>막아야 하는가</b> — 보호막이 서 있다는 전제에서.
	 *
	 * <p>{@code DragonPerch.melee} 와 달리 허용 목록이 아니다. 「모든 공격을 막는다」가 사람
	 * 말이므로 통과하는 것은 <b>무적을 지나친다고 바닐라가 적어 둔 것</b> 하나뿐이다.
	 *
	 * @param bypassesInvulnerability 그 피해가 {@code #minecraft:bypasses_invulnerability} 인가
	 */
	static boolean blocks(boolean bypassesInvulnerability) {
		return !bypassesInvulnerability;
	}

	/**
	 * 깃발을 마지막으로 세운 틱에서 지금까지 맥박이 이어져 있는가.
	 *
	 * <p>{@code int} 가 한 바퀴 돌아도 뺄셈은 맞다(둘 다 같은 바퀴를 돈다). 음수는 서버가 바뀐
	 * 것이라 꺼진 것으로 읽는다.
	 */
	static boolean alive(int raisedAt, int serverTick) {
		int gap = serverTick - raisedAt;
		return gap >= 0 && gap <= PULSE_SLACK_TICKS;
	}

	/** 반구 위 {@code index} 번째 점의 상대 좌표. 시험이 「전부 반구 위인가」를 잰다. */
	static Vec3 domeOffset(int index) {
		// 반구는 높이로 고르게 자르면 띠마다 넓이가 같다(아르키메데스). 그래서 높이를 고르게 놓고
		// 각도를 황금각으로 돌리면 점이 한쪽에 몰리지 않는다.
		double height = (index + 0.5) / DOME_POINTS;
		double ring = Math.sqrt(Math.max(0.0, 1.0 - height * height));
		double angle = index * GOLDEN_ANGLE;
		return new Vec3(Math.cos(angle) * ring * DOME_RADIUS, height * DOME_RADIUS,
				Math.sin(angle) * ring * DOME_RADIUS);
	}

	/** 황금각(라디안). */
	private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0));

	/**
	 * 보호막이 켜진 틱의 최악 점 수. 클래스 설명의 표를 값으로 다시 센 것이다.
	 *
	 * <p>진입 연출과만 겹친다 — 까닭은 클래스 설명의 「점 예산」.
	 */
	static int worstShieldTickPoints() {
		return DOME_POINTS_PER_TICK + DEFLECT_POINTS + DragonLastStandEntry.WORST_TICK_POINTS;
	}

	// ------------------------------------------------------------------ 믹스인이 묻는 것

	/**
	 * 지금 이 한 방을 거절해야 하는가. <b>{@code EnderDragonPerchRangedImmunityMixin} 이 매 피격
	 * 부른다.</b>
	 *
	 * <p>첫 줄이 {@code volatile boolean} 한 번 읽기다. 최후의 저항 진입 11초 말고는 거기서 끝난다.
	 */
	public static boolean refuses(@Nullable ServerLevel level, @Nullable DamageSource source) {
		if (!raised || level == null) {
			return false;
		}
		MinecraftServer server = level.getServer();
		if (server == null) {
			return false;
		}
		return decide(raised, raisedAtServerTick, server.getTickCount(),
				source != null && source.is(DamageTypeTags.BYPASSES_INVULNERABILITY));
	}

	/**
	 * {@link #refuses} 의 판단을 값으로만 다시 적은 것. 월드 없이 시험이 굴린다.
	 *
	 * <p>⚠ <b>맞은 부위를 받지 않는다.</b> 머리·목·몸통·날개 어느 부위로 들어왔든, 부위를 거치지
	 * 않고 본체로 들어왔든({@code EnderDragon.hurtServer} → {@code body}) 답이 같아야 「부위
	 * 피해도 막힌다」가 성립하고, 그것을 인자 목록이 구조로 보증한다.
	 *
	 * @param flag       믹스인이 읽는 깃발
	 * @param raisedAt   깃발을 세운 서버 틱 수
	 * @param serverTick 지금 서버 틱 수
	 * @param bypass     그 피해가 {@code #minecraft:bypasses_invulnerability} 인가
	 */
	static boolean decide(boolean flag, int raisedAt, int serverTick, boolean bypass) {
		return flag && alive(raisedAt, serverTick) && blocks(bypass);
	}

	/**
	 * 한 방을 막았다 — <b>보이고 들린다.</b>
	 *
	 * <p>{@code DragonPerch.deflect} 와 값이 같은 한 벌이다. 받는 사람도 그쪽과 같이 <b>이 차원의
	 * 전원</b>이다 — 이 파일이 {@code ServerPlayer} 를 정적으로 쥐지 않게 하기 위해서다.
	 *
	 * @param at 맞은 부위의 자리. 몸 중심에서 내면 날개 끝을 맞춘 화살이 엉뚱한 데서 튕긴다
	 */
	public static void deflect(@Nullable ServerLevel level, @Nullable Vec3 at) {
		if (level == null || at == null) {
			return;
		}
		long now = nowTick;
		if (now != deflectDrawnAt) {
			deflectDrawnAt = now;
			for (int index = 0; index < DEFLECT_POINTS; index++) {
				double angle = (Math.PI * 2.0 * index) / DEFLECT_POINTS;
				// 긴 형식이다. 짧은 형식은 32칸에서 잘린다.
				level.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false,
						at.x + Math.cos(angle) * DEFLECT_SPREAD,
						at.y,
						at.z + Math.sin(angle) * DEFLECT_SPREAD,
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}
		// MIN_VALUE 를 뺄셈에 넣지 않는다. 넘치면 「방금 울렸다」가 되어 첫 소리가 사라진다.
		if (deflectHeardAt != Long.MIN_VALUE
				&& now - deflectHeardAt >= 0L && now - deflectHeardAt < DEFLECT_SOUND_GAP_TICKS) {
			return;
		}
		deflectHeardAt = now;
		TrialWarning.playEach(level, level.players(), SoundEvents.SHIELD_BLOCK, 1.0F, DEFLECT_PITCH);
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱. {@code DragonLastStand.tick} 이 <b>그 틱의 마지막에</b> 부른다 — 첫 패턴을 고르는
	 * {@code advance} 뒤여야 「첫 패턴이 시작한 틱부터는 맞는다」가 그 틱 안에서 성립한다.
	 *
	 * @param up      이 틱에 보호막이 서 있어야 하는가({@link #shielded} 의 답)
	 * @param beganAt 보호막을 세운 판이 시작한 틱. 반구를 나눠 긋는 위상의 원점이다
	 * @param now     받은 틱. {@code getGameTime} 을 다시 읽지 않는다
	 */
	static void tick(MinecraftServer server, ServerLevel end, EnderDragon dragon, boolean up,
			long beganAt, long now) {
		nowTick = now;
		raisedAtServerTick = server.getTickCount();
		raised = up;
		if (up && now != domeDrawnAt) {
			domeDrawnAt = now;
			drawDome(end, dragon.position(), beganAt, now);
		}
	}

	/**
	 * 반구의 이번 틱 몫을 긋는다.
	 *
	 * <p>중심을 <b>드래곤 자리</b>로 잡는다 — 진입 연출의 첫 2초는 드래곤이 내려오는 중이라
	 * 막이 함께 내려온다. 그 뒤로는 자리가 못박혀 있어 막도 선다.
	 */
	private static void drawDome(ServerLevel end, Vec3 center, long beganAt, long now) {
		// 주기는 (now - 원점) % 간격이다. now % 간격이면 다른 시계와 같은 틱에 몰린다(5장).
		int phase = (int) Math.floorMod(now - beganAt, (long) DOME_STRIDE);
		ParticleOptions dust = new DustParticleOptions(DOME_COLOR, DOME_DUST_SCALE);
		for (int index = phase; index < DOME_POINTS; index += DOME_STRIDE) {
			Vec3 offset = domeOffset(index);
			end.sendParticles(dust, true, false,
					center.x + offset.x, center.y + DOME_LIFT + offset.y, center.z + offset.z,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 보호막이 걷힌다 — <b>깨지는 소리 한 번과 파편.</b> {@code DragonLastStand.advance} 가 첫 패턴을
	 * 고른 그 틱에 한 번 부른다.
	 *
	 * <p>⚠ 소리는 {@code BLOCK_GLASS_BREAK} 다. 26.3 {@code sounds.json} 에서 그 이름은
	 * <b>{@code random/glass1~3}</b> 을 가리키고(6장 「소리 이름이 가리키는 실제 파일」), 이 저장소가
	 * 쓰는 소리 48 가지 가운데 그 파일을 가리키는 것이 <b>하나도 없다.</b> 재어 보고 버린 것.
	 *
	 * <ul>
	 *   <li>{@code SHIELD_BREAK} — {@code random/break}, 곧 <b>도구가 부서지는 소리</b>다. 이름만
	 *       방패고 들리는 것은 곡괭이가 닳아 없어지는 소리라 「막이 깨진다」가 안 된다</li>
	 *   <li>{@code AMETHYST_BLOCK_BREAK} — {@code block/amethyst/break1~4}, 이미 다른 자리가 쓴다</li>
	 * </ul>
	 *
	 * <p>막힘 신호({@code SHIELD_BLOCK} = {@code item/shield/block1~5})와도 파일이 다르다 — 「막혔다」와
	 * 「막이 걷혔다」는 반대 뜻이라 같은 소리면 안 된다.
	 */
	static void shatter(ServerLevel end, List<ServerPlayer> members, EnderDragon dragon) {
		Vec3 at = dragon.position().add(0.0, DOME_RADIUS * 0.5, 0.0);
		// 개수를 세는 형태 둘. 반구 자리에 하늘색 파편이 흩어지고 불꽃이 바깥으로 튄다.
		end.sendParticles(new DustParticleOptions(DOME_COLOR, 2.0F), true, false,
				at.x, at.y, at.z, 160, DOME_RADIUS * 0.5, DOME_RADIUS * 0.3, DOME_RADIUS * 0.5, 0.0);
		end.sendParticles(ParticleTypes.FIREWORK, true, false,
				at.x, at.y, at.z, 80, DOME_RADIUS * 0.3, DOME_RADIUS * 0.2, DOME_RADIUS * 0.3, 0.35);
		TrialWarning.playEach(end, members, SoundEvents.GLASS_BREAK, 1.0F, SHATTER_PITCH);
	}

	// ------------------------------------------------------------------ 되돌리기

	/**
	 * 깃발을 내린다. <b>월드를 만지지 않는다.</b>
	 *
	 * <p>{@code DragonLastStand.onFightClosed}·{@code clearState} 가 부른다. 이 줄을 못 지나도
	 * 맥박이 끊기면 두 틱 안에 저절로 꺼진다(클래스 설명).
	 */
	static void clearState() {
		raised = false;
		raisedAtServerTick = 0;
		nowTick = Long.MIN_VALUE;
		deflectHeardAt = Long.MIN_VALUE;
		deflectDrawnAt = Long.MIN_VALUE;
		domeDrawnAt = Long.MIN_VALUE;
	}

	/** 시험이 들여다보는 곳. 깃발이 서 있는가(맥박은 안 본다). */
	static boolean raisedFlag() {
		return raised;
	}
}
