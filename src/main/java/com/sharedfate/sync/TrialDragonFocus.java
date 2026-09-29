package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhaseManager;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.DragonFocus} — 드래곤의 공격을 한 사람에게 몰아준다.
 *
 * <h2>왜 「미워한다」를 심지 않는가</h2>
 *
 * <p>엔더 드래곤은 일반 몹과 타겟 구조가 다르다. {@code setTarget} 으로 증오 대상을 심어 두는
 * 자리가 아예 없고, <b>행동을 정하는 것은 페이즈</b>({@link EnderDragonPhase})다. 사람을 노리는
 * 페이즈는 바닐라에 둘 있다 — 불덩이를 쏘는 {@code STRAFE_PLAYER} 와 몸통으로 파고드는
 * {@code CHARGING_PLAYER}. 이 둘은 각자 「누구를」 노리는지를 자기 안에 들고 있다.
 *
 * <h2>기다리기만 하던 판 — 이 카드가 아무 일도 하지 않았던 까닭</h2>
 *
 * <p>처음 판은 <b>페이즈를 우리가 바꾸지 않고</b> 드래곤이 스스로 사람을 노리는 페이즈에 들어간
 * 틱에만 대상을 바꿔 끼웠다. 그런데 26.3 바이트코드를 실제로 세어 보면 그 방식으로는 카드가
 * <b>바닐라와 구별되지 않는다.</b>
 *
 * <ul>
 *   <li>{@code DragonHoldingPatternPhase.findNewTarget} 은 원을 한 바퀴 돌아 경로가 끝난 틱에만
 *       판단한다. 거기서 {@code nextInt(살아있는크리스탈 + 3) == 0} 이면 착지하러 가고, 아니면
 *       <b>광장 중앙에서 가장 가까운 사람</b>을 찾아
 *       {@code nextInt((int)(그사람까지거리²/512 + 2)) == 0 || nextInt(살아있는크리스탈 + 2) == 0}
 *       일 때 {@code STRAFE_PLAYER} 로 간다. 크리스탈 10 개가 살아 있고 사람이 중앙에서 30 블록
 *       거리면 판단 한 번마다 <b>약 36%</b>다 — 드물지 않다. 문제는 <b>그렇게 고른 사람이 이미
 *       「중앙에서 가장 가까운 사람」</b>이라는 것이다. 우리가 바꿔 끼워도 같은 사람인 일이 잦다.</li>
 *   <li>같은 클래스의 {@code onCrystalDestroyed} 는 <b>크리스탈을 깬 사람</b>을 그 자리에서
 *       {@code strafePlayer} 로 넘긴다. 이 카드의 {@code CRYSTAL_BREAKER} 가 고르는 사람과
 *       <b>정확히 같은 사람</b>이다. 즉 카드가 가장 잘 맞는 순간에 우리 개입은 완전한 무효과다.</li>
 *   <li>{@code STRAFE_PLAYER} 는 불덩이 <b>한 발</b>을 쏘고 곧바로 {@code HOLDING_PATTERN} 으로
 *       돌아간다. 드래곤이 다가오지 않는다.</li>
 *   <li>「드래곤이 나에게 날아온다」인 {@code CHARGING_PLAYER} 는 바닐라에서
 *       {@code DragonSittingScanningPhase} <b>한 곳</b>에서만 켜진다. 드래곤이 광장에 내려앉고
 *       (판단마다 크리스탈 10 개일 때 1/13), 그 뒤 <b>100 틱 동안 20 블록·높이 10 안에 아무도
 *       들어오지 않아야</b> 한다. 사람들이 내려앉은 드래곤을 때리러 가는 게임이라 이 조건은
 *       사실상 성립하지 않는다 — 옛 구현의 {@code CHARGING_PLAYER} 가지는 <b>죽은 코드</b>였다.</li>
 * </ul>
 *
 * <p>그래서 자막과 보라 표식만 뜨고 아무 일도 일어나지 않았다. 카드 이름이 「표적」이고 설명이
 * 「공격이 집중됩니다」인데 <b>약속을 지키지 않았다.</b>
 *
 * <h2>지금은 우리가 민다 — 다만 {@code HOLDING_PATTERN} 에서만</h2>
 *
 * <p>{@link #chargeInterval} 틱마다 한 번, 드래곤이 <b>원을 돌고 있을 때만</b>
 * {@code CHARGING_PLAYER} 로 밀어넣고 표적의 그 순간 좌표를 심는다. 크리스탈을 먹으러 가는
 * {@code LANDING_APPROACH}, 내려앉는 {@code LANDING}, 앉아 있는 {@code SITTING_*}, 다시 뜨는
 * {@code TAKEOFF} 에는 <b>손대지 않는다.</b> 전투의 절반인 착지·타격 구간을 그대로 남기려는
 * 것이고, 이것이 「바닐라 흐름을 죽이지 않는다」의 실제 내용이다. 대가는 원을 도는 시간의 일부를
 * 우리가 가져가므로 착지 주사위를 굴리는 횟수가 그만큼 준다는 것 — 없어지지는 않는다.
 *
 * <h2>갇히지 않는다 — 바이트코드에 적힌 탈출구</h2>
 *
 * <p>{@link TrialCrystalRevive} 는 「페이즈를 바꾸면 우리가 되돌려야 한다」는 이유로 페이즈를
 * 건드리지 않는다. 그 걱정은 옳지만 <b>{@code CHARGING_PLAYER} 에는 해당하지 않는다.</b>
 * 26.3 의 {@code DragonChargePlayerPhase} 는 되돌려 줄 사람이 없어도 스스로 나온다.
 *
 * <ul>
 *   <li>{@code doServerTick} 첫 줄 — 목표 좌표가 {@code null} 이면 경고 한 줄을 찍고 곧바로
 *       {@code HOLDING_PATTERN}</li>
 *   <li>목표까지 거리²가 100 미만이거나 22500 초과이거나 벽에 닿으면 {@code timeSinceCharge} 가
 *       오르고, <b>10 틱</b> 뒤 {@code HOLDING_PATTERN}. 좌표가 한 점으로 고정되어 있으므로
 *       드래곤은 반드시 그 점에 닿거나 지나친다</li>
 *   <li>드래곤이 죽으면 {@code EnderDragon} 이 {@code DYING} 으로 덮어쓴다</li>
 *   <li>서버가 내려가도 갇히지 않는다. {@code addAdditionalSaveData} 는 페이즈 <b>번호만</b>
 *       저장하고 목표 좌표는 저장하지 않는다. 다시 뜰 때 {@code setPhase} → {@code begin()} 이
 *       좌표를 {@code null} 로 두므로 첫 틱에 위의 첫 번째 탈출구로 빠진다</li>
 * </ul>
 *
 * <p>우리 틱이 언제 끊겨도 남는 것은 <b>몇 틱 뒤 알아서 원으로 돌아가는 드래곤</b>뿐이다.
 *
 * <h2>대상을 심는 값이 두 페이즈가 서로 다르다</h2>
 *
 * <p>{@code DragonStrafePlayerPhase.setTarget} 은 {@code LivingEntity} 를 받아 <b>그 자리까지
 * 경로를 새로 깐다</b>. 매 틱 부르면 경로가 매 틱 버려져 드래곤이 앞으로 나가지 못한다. 반대로
 * {@code DragonChargePlayerPhase.setTarget} 은 {@code Vec3} <b>한 점</b>을 받아 넣기만 한다 —
 * 매 틱 갱신하면 돌진이 유도탄이 되어 피할 수가 없다. 그래서 둘 다 <b>페이즈에 들어갈 때 한
 * 번만</b> 심고, 특히 돌진은 <b>시작한 뒤로는 절대 다시 겨누지 않는다.</b> 비켜서면 빗나가는
 * 것이 이 카드의 유일한 대응 수단이다.
 *
 * <p>또 {@code begin()} 이 대상을 지우므로 <b>순서는 반드시 페이즈 → 대상</b>이다.
 * {@code setPhase} 는 같은 페이즈면 아무 일도 하지 않으므로({@code EnderDragonPhaseManager}
 * 첫 줄) 이미 들어가 있는 페이즈를 다시 밀어도 {@code begin()} 이 다시 돌지 않는다.
 *
 * <h2>오고 있는 것이 보여야 한다</h2>
 *
 * <p>「타겟팅만 뜨고 아무 일도 없다」가 이 카드에 대한 실제 불만이었다. 그래서 드래곤이 표적을
 * 노리는 동안 <b>드래곤 머리에서 표적까지 보라색 선</b>을 긋는다. 드래곤은 y 133 까지 올라가고
 * 아레나를 가로지르면 150 블록이 넘으므로, 이 선은 <b>반드시 긴 형태</b>
 * ({@code overrideLimiter=true})로 보내야 한다 — 짧은 형태는 32 블록에서 잘려 정작 「멀리서
 * 온다」는 정보가 통째로 사라진다. 까닭은 {@link TrialWarning} 클래스 설명에 적혀 있다.
 *
 * <h2>피해는 손대지 않는다</h2>
 *
 * <p>드래곤의 몸통 피해는 하드코딩 {@code 10.0F}, 밀쳐내기는 {@code 5.0F} 인데 이 모드의 공유
 * 최대 체력은 20 에서 시작한다. <b>한 대에 팀 절반</b>이고 전멸하면 월드가 지워진다. 이 카드는
 * 「누구를 노리는가」만 바꾼다 — 여기에 피해를 한 점이라도 더하면 즉사 카드가 된다.
 */
public final class TrialDragonFocus {

	/** 표적 발밑에 그리는 고리의 반경(블록). 사람 하나를 감쌀 만큼만 — 위험 범위가 아니라 이름표다. */
	private static final double MARK_RADIUS = 1.5;

	/**
	 * 표식을 다시 그리는 간격(틱).
	 *
	 * <p>먼지 파티클은 1초 넘게 남으므로 매 틱 찍을 필요가 없다. 이 표식은
	 * {@link TrialRisks} 의 예고와 달리 <b>전투 내내</b> 켜져 있어서, 매 틱 40점을 뿌리면 시련
	 * 하나가 파티클 예산을 통째로 먹는다.
	 */
	private static final int MARK_REDRAW_TICKS = 5;

	// ------------------------------------------------------------------ 개입 주기

	/**
	 * 돌진 한 번 사이에 표적을 몇 번 다시 고르는가.
	 *
	 * <p>여기가 「가끔 노리러 온다」와 「영원히 사람만 쫓는다」를 가르는 값이다. 돌진 한 번은
	 * 비행 2~3초 + {@code DragonChargePlayerPhase} 가 정해 둔 회복 10틱이라 대략 <b>3초</b>다.
	 * 1 로 두면 「표적」 카드(재지정 100틱)에서 5초마다 3초를 돌진에 쓰게 되어 드래곤이 원을 도는
	 * 시간이 거의 남지 않고, 그러면 착지 주사위를 굴릴 기회가 사라져 <b>전투가 끝나지 않는다.</b>
	 * 3 이면 15초에 한 번, 시간의 20%다 — 나머지 80%는 바닐라 그대로다.
	 */
	static final int CHARGE_CYCLES = 3;

	/**
	 * 돌진 사이 최소 간격(틱).
	 *
	 * <p>{@link TrialCatalog.Risk.DragonFocus#retargetTicks()} 가 작게 적힌 카드가 앞으로
	 * 생기더라도 드래곤이 쉬지 않고 파고들지 않게 막는 바닥이다. 값이 잘못 적힌 카드는
	 * <b>심심해질 뿐</b>이어야지 대응 불가를 만들면 안 된다 — {@link #select} 의 판단과 같다.
	 */
	static final int CHARGE_MIN_TICKS = 200;

	/**
	 * 이보다 가까우면 돌진시키지 않는다(블록).
	 *
	 * <p>{@code DragonChargePlayerPhase.doServerTick} 은 목표까지 거리²가 100 미만이면
	 * <b>이미 도착했다</b>고 보고 회복을 세기 시작한다. 즉 10 블록 안에서 밀어 봐야 10틱 뒤
	 * 그냥 되돌아갈 뿐, 사람 눈에는 아무 일도 없다. 조금 여유를 둔 값이다.
	 */
	static final double CHARGE_MIN_DISTANCE = 12.0;

	/**
	 * 이보다 멀면 돌진시키지 않는다(블록).
	 *
	 * <p>같은 곳에서 거리²가 22500(=150 블록)을 넘으면 역시 회복을 세기 시작한다. 그 밖으로
	 * 겨누면 돌진이 시작조차 하지 않으므로 주기만 낭비된다. 아레나가 이 안이라 평소에는 걸리지
	 * 않고, 사람이 끝 관문 너머로 나간 경우를 위한 것이다.
	 */
	static final double CHARGE_MAX_DISTANCE = 140.0;

	// ------------------------------------------------------------------ 오고 있다는 선

	/** 선을 다시 긋는 간격(틱). 돌진 중에만 그리지만 점 하나가 패킷 한 장이라 매 틱은 과하다. */
	private static final int BEAM_REDRAW_TICKS = 2;

	/** 선 위 점 사이 목표 간격(블록). */
	static final double BEAM_STEP = 2.0;

	/**
	 * 점 사이가 이보다 벌어지면 선이 아니라 점선이다.
	 *
	 * <p>상한에 걸린 뒤로는 점이 늘지 않으므로 거리가 멀수록 간격만 벌어진다. 어디까지 벌어져도
	 * 「저 하늘에서 나에게로 그어진 선」으로 읽히는가가 상한을 정하는 기준이다.
	 */
	static final double BEAM_MAX_GAP = 4.0;

	/**
	 * 이 길이까지는 위 간격을 지킨다.
	 *
	 * <p>드래곤은 y 133 까지 올라가고 광장 바깥 궤도까지 나간다. 반대편 끝에 선 사람까지가
	 * 가로 140·세로 70 이면 156 이므로 그것을 덮는 값이다.
	 */
	static final double BEAM_KEPT_LENGTH = 160.0;

	/**
	 * 선에 찍는 점 수의 상한.
	 *
	 * <p>숫자를 박지 않고 위 둘에서 뽑는다 — 간격이나 약속 길이를 고치면 상한이 따라와야 하는데
	 * 따로 적어 두면 한쪽만 고쳐져 <b>약속이 조용히 깨진다.</b>
	 */
	private static final int BEAM_MAX_POINTS = (int) Math.ceil(BEAM_KEPT_LENGTH / BEAM_MAX_GAP);

	/** 선이 표적의 발이 아니라 가슴에 닿게 하는 높이. 발밑 고리와 선이 겹치면 둘 다 안 읽힌다. */
	private static final double BEAM_TARGET_LIFT = 1.0;

	/**
	 * 지금 노리는 사람과 그 사람을 고른 주기.
	 *
	 * <p>주기 번호를 함께 들고 있어야 「이번 주기에 이미 골랐는가」를 물을 수 있다. 매 틱 다시
	 * 고르면 표식이 사람들 사이를 뛰어다니고 자막이 매 틱 뜬다.
	 */
	record Selection(long cycle, UUID target) {
	}

	/** 이번 주기의 표적. 위험이 값(레코드)이라 상태를 들 수 없어 여기 둔다. */
	private static @Nullable Selection active;

	/**
	 * 마지막으로 드래곤에게 실제로 심은 대상과 그때의 페이즈.
	 *
	 * <p>같은 값을 다시 심지 않으려고 들고 있다. 페이즈가 달라졌으면 {@code begin()} 이 대상을
	 * 지웠다는 뜻이므로 값이 같아도 다시 심어야 한다.
	 */
	private static @Nullable UUID appliedTarget;
	private static @Nullable EnderDragonPhase<?> appliedPhase;

	/**
	 * 마지막으로 돌진을 민 시각. 아직 한 번도 밀지 않았으면 {@code null}.
	 *
	 * <p>{@code null} 을 「지금 바로 밀 차례」로 읽는다. 카드를 받자마자 첫 돌진이 오게 하려는
	 * 것이다 — 자막이 뜨고 한참 아무 일도 없으면 그것이 바로 이번에 고친 그 결함이다.
	 */
	private static @Nullable Long lastCharge;

	private TrialDragonFocus() {
	}

	/**
	 * 매 틱.
	 *
	 * <p>드래곤이 없거나 죽어 있으면 아무 일도 하지 않는다 — 죽는 연출({@code DYING})이 도는 동안
	 * 표식을 그리면 이미 끝난 전투에 경고가 남는다.
	 *
	 * @param granted 카드를 받은 틱. 주기를 여기서부터 센다
	 * @param now     지금 게임 시각
	 */
	public static void tick(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> members, long granted, long now,
			TrialCatalog.Risk.DragonFocus risk) {
		if (end == null || risk == null || dragon == null || !dragon.isAlive()
				|| dragon.isDeadOrDying()) {
			return;
		}
		List<ServerPlayer> targetable = targetable(members);
		if (targetable.isEmpty()) {
			return;
		}

		// 위상 계산은 TrialRisks 의 순수 함수를 그대로 쓴다. 같은 규칙을 두 벌 들고 있으면
		// 한쪽만 고쳐졌을 때 카드마다 다른 시간을 살게 된다.
		long cycle = TrialRisks.strikeIndex(now, granted, risk.retargetTicks());
		Selection before = active;
		active = select(before, cycle, idsOf(targetable), risk.focus(), CrystalWatch.lastBreaker(),
				end.getRandom());
		if (active == null) {
			return;
		}
		ServerPlayer target = memberOf(targetable, active.target());
		if (target == null) {
			return;
		}

		boolean changed = before == null || !before.target().equals(active.target());
		if (changed) {
			announce(end, target);
		}
		// 받은 틱부터 센다. 월드 시간으로 나누면 카드마다 그림이 같은 틱에 몰린다. 바뀐 틱에는
		// 간격을 기다리지 않는다 — 알림과 표식이 따로 오면 누구인지가 흐려진다.
		if (changed || TrialRisks.elapsedSinceGrant(now, granted) % MARK_REDRAW_TICKS == 0L) {
			mark(end, target);
		}
		steer(end, dragon, target, now, chargeInterval(risk.retargetTicks()));
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 옛 표적이 다음 판의 사람에게 붙지 않게 한다. */
	public static void clearState() {
		active = null;
		appliedTarget = null;
		appliedPhase = null;
		lastCharge = null;
	}

	// ------------------------------------------------------------------ 드래곤에 심기

	/**
	 * 드래곤을 표적 쪽으로 돌린다.
	 *
	 * <p>페이즈마다 할 일이 다르다.
	 *
	 * <ul>
	 *   <li>{@code HOLDING_PATTERN} — 원을 돌고 있다. 돌진할 차례이고 거리가 맞으면 여기서
	 *       <b>밀어넣는다.</b> 이 카드가 실제로 개입하는 자리는 여기 하나뿐이다</li>
	 *   <li>{@code STRAFE_PLAYER}·{@code CHARGING_PLAYER} — 이미 사람을 노리고 있다. 대상만
	 *       우리 표적으로 바꿔 끼우고 오고 있다는 선을 긋는다</li>
	 *   <li>그 밖 — <b>아무것도 하지 않는다.</b> 크리스탈을 먹으러 가거나 내려앉아 있거나 다시
	 *       뜨는 중이고, 그것을 막으면 전투가 성립하지 않는다</li>
	 * </ul>
	 *
	 * <p>노리는 페이즈를 벗어나면 심어 둔 기억을 지운다. 다음에 그 페이즈로 돌아올 때
	 * {@code begin()} 이 대상을 비워 두기 때문이다 — 「이미 심었다」고 착각하면 드래곤이 대상
	 * 없는 페이즈에서 곧바로 물러난다.
	 */
	private static void steer(ServerLevel end, EnderDragon dragon, ServerPlayer target, long now,
			int chargeInterval) {
		EnderDragonPhaseManager manager = dragon.getPhaseManager();
		DragonPhaseInstance current = manager == null ? null : manager.getCurrentPhase();
		EnderDragonPhase<?> phase = current == null ? null : current.getPhase();
		if (phase == null) {
			return;
		}

		if (phase == EnderDragonPhase.HOLDING_PATTERN) {
			forgetApplied();
			if (!chargeDue(now, lastCharge, chargeInterval)
					|| !chargeable(dragon.position().distanceTo(target.position()))) {
				return;
			}
			// 순서가 규칙이다. begin() 이 좌표를 지우므로 페이즈를 먼저 세우고 좌표를 넣는다.
			manager.setPhase(EnderDragonPhase.CHARGING_PLAYER);
			manager.getPhase(EnderDragonPhase.CHARGING_PLAYER).setTarget(target.position());
			appliedPhase = EnderDragonPhase.CHARGING_PLAYER;
			appliedTarget = target.getUUID();
			lastCharge = now;
			announceDive(end, dragon, target);
			beam(end, dragon, target);
			return;
		}

		if (phase != EnderDragonPhase.STRAFE_PLAYER && phase != EnderDragonPhase.CHARGING_PLAYER) {
			forgetApplied();
			return;
		}

		// 같은 페이즈에서 같은 대상을 다시 심지 않는다. 불덩이 쪽은 심을 때마다 경로를 새로 깔아
		// 매 틱 부르면 드래곤이 앞으로 나가지 못하고, 돌진 쪽은 표적을 따라다니는 유도탄이 된다.
		if (phase != appliedPhase || !target.getUUID().equals(appliedTarget)) {
			if (phase == EnderDragonPhase.STRAFE_PLAYER) {
				manager.getPhase(EnderDragonPhase.STRAFE_PLAYER).setTarget(target);
				appliedPhase = phase;
				appliedTarget = target.getUUID();
			} else if (appliedPhase != EnderDragonPhase.CHARGING_PLAYER) {
				// 바닐라가 스스로 시작한 돌진이다. 겨냥은 한 번만 바꾼다 — 표적이 주기마다
				// 바뀐다고 날아가는 중에 다시 겨누면 비켜설 방법이 없어진다.
				manager.getPhase(EnderDragonPhase.CHARGING_PLAYER).setTarget(target.position());
				appliedPhase = phase;
				appliedTarget = target.getUUID();
			}
		}
		if (now % BEAM_REDRAW_TICKS == 0L) {
			beam(end, dragon, target);
		}
	}

	private static void forgetApplied() {
		appliedPhase = null;
		appliedTarget = null;
	}

	// ------------------------------------------------------------------ 알리기

	/**
	 * 표적이 바뀌었다고 알린다.
	 *
	 * <p>이 게임은 전멸하면 월드가 지워진다. <b>누가 물렸는지 모르면 대응할 수 없다</b> — 물린
	 * 사람은 떨어져 나가야 하고 나머지는 그 틈에 때려야 한다. 색은
	 * {@link TrialWarning.Colors#MARKED}(「너 하나를 노린다」)이고, 여기서 새 색을 만들지 않는다.
	 */
	private static void announce(ServerLevel end, ServerPlayer target) {
		TrialWarning.shout(List.of(target), Component.literal("드래곤이 당신을 노립니다"));
		// 본인 자막만으로는 나머지가 모른다. 소리는 표적 자리에서 나므로 누구인지가 함께 전해진다.
		TrialWarning.sound(end, target.position(), TrialWarning.Stage.MARK);
	}

	/**
	 * 돌진이 시작됐다고 알린다.
	 *
	 * <p>「노립니다」와 다른 사실이라 문구를 나눈다 — 표식은 전투 내내 붙어 있지만 돌진은
	 * {@link #chargeInterval} 틱에 한 번뿐이다. 소리는 <b>드래곤 자리</b>에서 낸다. 어디서
	 * 오는지가 피할 방향을 정하는 정보이고, 표적 자리에서 울리면 그것을 못 준다.
	 */
	private static void announceDive(ServerLevel end, EnderDragon dragon, ServerPlayer target) {
		TrialWarning.shout(List.of(target), Component.literal("드래곤이 당신에게 날아옵니다"));
		TrialWarning.sound(end, dragon.position(), TrialWarning.Stage.APPROACH);
	}

	/** 표적 발밑에 보라색 고리를 그린다. 위험 범위가 아니라 「이 사람이다」라는 이름표다. */
	private static void mark(ServerLevel end, ServerPlayer target) {
		TrialWarning.markGround(end, target.position(), MARK_RADIUS,
				TrialWarning.dust(TrialWarning.Colors.MARKED));
	}

	/**
	 * 드래곤 머리에서 표적까지 보라색 선을 긋는다.
	 *
	 * <p><b>세 줄 모두 긴 형태다.</b> 첫 {@code boolean} 을 {@code false} 로 되돌리면 이 선은
	 * 보는 사람 발밑 32 블록 안에서만 존재한다 — 드래곤은 그 밖에 있으므로 <b>선의 출발점,
	 * 곧 「어디서 오는가」가 통째로 사라진다.</b> 그러면 고친 것이 다시 원래대로 돌아간다.
	 *
	 * <p>둘째 {@code boolean}({@code alwaysShow}) 은 「파티클 줄이기」 설정을 무시할지다. 첫
	 * 깃발이 켜져 있으면 그 검사를 건너뛰므로 값이 무의미하고, 사용자의 설정을 우리가 뒤집을
	 * 이유도 없어 {@code false} 로 둔다.
	 */
	private static void beam(ServerLevel end, EnderDragon dragon, ServerPlayer target) {
		Vec3 from = dragon.head.position();
		Vec3 to = target.position().add(0.0, BEAM_TARGET_LIFT, 0.0);
		int points = beamPoints(from.distanceTo(to));
		ParticleOptions dust = TrialWarning.dust(TrialWarning.Colors.MARKED);
		for (int index = 0; index < points; index++) {
			double along = (double) index / points;
			Vec3 point = from.add(to.subtract(from).scale(along));
			end.sendParticles(dust, true, false, point.x, point.y, point.z, 1,
					0.0, 0.0, 0.0, 0.0);
		}
		// 출발점에 덩어리를 하나 얹는다. 선만 있으면 어느 쪽 끝이 다가오는 쪽인지 안 읽힌다.
		end.sendParticles(dust, true, false, from.x, from.y, from.z, 8,
				0.6, 0.6, 0.6, 0.0);
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 이 카드의 돌진 간격(틱).
	 *
	 * <p>{@code retargetTicks} 에 묶어 둔다. 재지정과 무관한 숫자를 따로 박으면 표적이 바뀌는
	 * 박자와 드래곤이 오는 박자가 어긋나 「누가 물렸는지」가 흐려진다.
	 *
	 * <p>{@code retargetTicks} 가 0 이하면 표적이 영영 고정되지만({@link TrialRisks#strikeIndex})
	 * 돌진까지 멈추면 카드가 통째로 죽는다. 그때는 바닥값으로 돈다.
	 */
	static int chargeInterval(int retargetTicks) {
		if (retargetTicks <= 0) {
			return CHARGE_MIN_TICKS;
		}
		// int 로 곱하면 큰 값이 음수로 넘어가 바닥값 검사를 그대로 통과한다.
		long wanted = (long) retargetTicks * CHARGE_CYCLES;
		return (int) Math.max(CHARGE_MIN_TICKS, Math.min(Integer.MAX_VALUE, wanted));
	}

	/**
	 * 지금이 돌진을 밀 차례인가.
	 *
	 * <p>「마지막으로 민 때부터 간격이 지났는가」이지 「주기 경계 틱인가」가 아니다. 드래곤이
	 * 내려앉아 있는 동안 경계 틱이 지나가 버려도 <b>다시 뜨는 순간</b> 돌진이 온다 — 경계 틱만
	 * 보면 착지가 길어진 판에서 카드가 한 번도 발동하지 않을 수 있다.
	 *
	 * @param lastCharge 마지막으로 민 시각. 아직 없으면 {@code null} 이고 그때는 바로 차례다
	 */
	static boolean chargeDue(long now, @Nullable Long lastCharge, int interval) {
		if (lastCharge == null) {
			return true;
		}
		// 월드 시간이 되감기면(판을 다시 시작했는데 상태가 남았다면) 영영 차례가 오지 않는다.
		if (now < lastCharge) {
			return true;
		}
		return now - lastCharge >= interval;
	}

	/**
	 * 그 거리에서 돌진이 실제로 일어나는가.
	 *
	 * <p>두 끝값 모두 {@code DragonChargePlayerPhase.doServerTick} 이 「도착했다」로 보는
	 * 경계에서 왔다. 그 밖에서 밀면 드래곤은 10틱 뒤 그냥 원으로 돌아가고, 사람 눈에는 이 카드가
	 * <b>또</b> 아무 일도 하지 않은 것으로 보인다.
	 */
	static boolean chargeable(double distance) {
		return distance >= CHARGE_MIN_DISTANCE && distance <= CHARGE_MAX_DISTANCE;
	}

	/** 그 길이의 선에 찍을 점 수. 상한에 걸리면 성겨질 뿐 선은 남는다. */
	static int beamPoints(double length) {
		if (!(length > 0.0)) {
			return 0;
		}
		return Math.min(BEAM_MAX_POINTS, (int) Math.floor(length / BEAM_STEP));
	}

	/**
	 * 그 길이에서 실제로 벌어지는 점 사이 거리(블록).
	 *
	 * <p>점 수만 보면 상한에 걸린 먼 거리가 촘촘한 줄 알게 된다. 선이 <b>선으로 읽히는가</b>를
	 * 숫자로 물을 수 있는 유일한 값이다.
	 */
	static double beamGap(double length) {
		if (!(length > 0.0)) {
			return 0.0;
		}
		int points = beamPoints(length);
		if (points <= 0) {
			return length;
		}
		return length / points;
	}

	/**
	 * 이번 틱의 표적. 주기가 그대로면 들고 있던 사람을 그대로 돌려준다.
	 *
	 * <p><b>{@code retargetTicks} 가 0 이하면 한 번 고른 사람을 영영 유지한다.</b>
	 * {@link TrialRisks#strikeIndex} 가 그때 언제나 0 을 돌려주기 때문이다. 「매 틱 다시 고른다」로
	 * 정하지 않은 이유는 그쪽이 훨씬 나쁘기 때문이다 — 표식이 사람들 사이를 뛰어다니고 자막이 매 틱
	 * 떠서 아무도 자기가 물렸는지 알 수 없다. 값이 잘못 적힌 카드는 <b>심심해질 뿐</b>이어야지
	 * 못 읽을 화면을 만들면 안 된다.
	 *
	 * <p>주기 중간이라도 들고 있던 사람이 명단에 없으면 다시 고른다. 접속을 끊었거나 관전으로
	 * 넘어간 사람을 계속 노리면 <b>아무도 안 노리는 것</b>과 같고, 그러면 카드가 죽는다.
	 *
	 * @param held    직전까지 들고 있던 표적. 처음이면 {@code null}
	 * @param members 지금 노릴 수 있는 사람들. 비어 있으면 {@code null} 을 돌려준다
	 * @param breaker 크리스탈을 가장 최근에 깬 사람. 없으면 {@code null}
	 */
	static @Nullable Selection select(@Nullable Selection held, long cycle,
			@Nullable List<UUID> members, TrialCatalog.Risk.Focus focus, @Nullable UUID breaker,
			RandomSource random) {
		if (members == null || members.isEmpty()) {
			return null;
		}
		if (held != null && held.cycle() == cycle && members.contains(held.target())) {
			return held;
		}
		return new Selection(cycle, choose(members, focus, breaker, random));
	}

	/**
	 * 한 사람을 고른다.
	 *
	 * <p>{@code default} 를 넣지 말 것. {@link TrialCatalog.Risk.Focus} 에 갈래를 더하고 여기에
	 * 고르는 법을 안 붙이면 빌드가 깨져야 한다. {@code default} 가 있으면 새 갈래가 조용히
	 * 무작위가 되어, 카드 설명과 실제가 다른 채로 판이 굴러간다.
	 *
	 * <p>깬 사람이 없거나 지금 명단에 없으면 <b>무작위로 물러난다.</b> 아무도 안 노리면 카드가
	 * 죽는다 — 룰렛이 이 카드를 뽑은 판은 시련 없는 판이 되어 버린다.
	 */
	static UUID choose(List<UUID> members, @Nullable TrialCatalog.Risk.Focus focus,
			@Nullable UUID breaker, RandomSource random) {
		if (focus == null) {
			return randomOf(members, random);
		}
		return switch (focus) {
			case CRYSTAL_BREAKER -> breaker != null && members.contains(breaker)
					? breaker
					: randomOf(members, random);
			case RANDOM -> randomOf(members, random);
		};
	}

	private static UUID randomOf(List<UUID> members, RandomSource random) {
		return members.get(random.nextInt(members.size()));
	}

	// ------------------------------------------------------------------ 명단

	/**
	 * 지금 노릴 수 있는 사람들.
	 *
	 * <p>죽어 있거나 관전 중인 사람을 노리면 드래곤이 시체를 쫓는다. 그 사이 살아 있는 사람은
	 * 아무 위협도 받지 않으므로 카드가 꺼진 것과 같다.
	 */
	private static List<ServerPlayer> targetable(@Nullable List<ServerPlayer> members) {
		List<ServerPlayer> alive = new ArrayList<>();
		if (members == null) {
			return alive;
		}
		for (ServerPlayer member : members) {
			if (member != null && member.isAlive() && !member.isSpectator()) {
				alive.add(member);
			}
		}
		return alive;
	}

	private static List<UUID> idsOf(List<ServerPlayer> members) {
		List<UUID> ids = new ArrayList<>(members.size());
		for (ServerPlayer member : members) {
			ids.add(member.getUUID());
		}
		return ids;
	}

	private static @Nullable ServerPlayer memberOf(List<ServerPlayer> members, UUID id) {
		for (ServerPlayer member : members) {
			if (member.getUUID().equals(id)) {
				return member;
			}
		}
		return null;
	}
}
