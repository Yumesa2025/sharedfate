package com.sharedfate.sync;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhaseManager;
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
 * 페이즈는 바닐라에 이미 둘 있다 — 불덩이를 쏘는 {@code STRAFE_PLAYER} 와 몸통으로 돌진하는
 * {@code CHARGING_PLAYER}. 이 둘은 각자 「누구를」 노리는지를 자기 안에 들고 있으므로, 우리가
 * 할 일은 <b>그 값을 바꿔 끼우는 것</b>뿐이다.
 *
 * <h2>페이즈를 우리가 고르지 않는다 — 카드의 크기가 여기서 정해진다</h2>
 *
 * <p>「사람을 노리는 페이즈로 매 틱 밀어넣는」 구현은 만들면 안 된다. 드래곤이 크리스탈을 먹으러
 * 가는 것({@code LANDING_APPROACH})과 착지해서 맞아 주는 것({@code SITTING_*})이 전투의 절반인데
 * 그것을 영영 막으면 <b>전투가 성립하지 않는다.</b> 게다가 {@code STRAFE_PLAYER} 는 다 쏘고
 * {@code HOLDING_PATTERN} 으로 돌아가려 하므로, 매 틱 도로 밀어넣으면 두 페이즈가 한 틱씩
 * 번갈아 잡혀 드래곤이 제자리에서 떤다.
 *
 * <p>그래서 이 카드는 <b>「지금 사람을 노릴 차례일 때 누구를 노릴지」만</b> 정한다. 드래곤이
 * 스스로 {@code STRAFE_PLAYER}·{@code CHARGING_PLAYER} 에 들어간 그 순간에만 끼어들고, 그
 * 밖의 페이즈에서는 아무것도 하지 않는다. 바닐라는 가까운 사람을 고르는데 우리는 표적을 고른다 —
 * 그 차이가 카드 전부다.
 *
 * <h2>대상을 심는 값이 두 페이즈가 서로 다르다</h2>
 *
 * <p>26.3 바이트코드로 확인한 것이다. {@code DragonStrafePlayerPhase.setTarget} 은
 * {@code LivingEntity} 를 받아 <b>그 자리까지 경로를 새로 깐다</b>. 매 틱 부르면 경로 계산이 매 틱
 * 버려지고 다시 깔려 드래곤이 앞으로 나가지 못한다. 반대로
 * {@code DragonChargePlayerPhase.setTarget} 은 {@code Vec3} <b>한 점</b>을 받아 넣기만 한다 —
 * 매 틱 갱신하면 돌진이 사람을 따라오는 유도탄이 되어 피할 수가 없다. 두 쪽 다
 * <b>필요할 때만 한 번</b> 심어야 하는 이유가 서로 다르다.
 *
 * <p>또 {@code begin()} 이 대상을 {@code null} 로 지우므로 <b>순서는 반드시 페이즈 → 대상</b>이다.
 * 바닐라 {@code DragonHoldingPatternPhase.strafePlayer} 가 그렇게 한다. 대상이 {@code null} 인
 * 채로 틱이 돌면 두 페이즈 모두 경고를 찍고 {@code HOLDING_PATTERN} 으로 물러난다.
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
		steer(dragon, target);
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 옛 표적이 다음 판의 사람에게 붙지 않게 한다. */
	public static void clearState() {
		active = null;
		appliedTarget = null;
		appliedPhase = null;
	}

	// ------------------------------------------------------------------ 드래곤에 심기

	/**
	 * 드래곤이 사람을 노리는 중이면 그 대상을 우리 표적으로 바꾼다.
	 *
	 * <p>다른 페이즈면 <b>아무것도 하지 않고</b> 심어 둔 기억만 지운다. 크리스탈을 먹으러 가거나
	 * 착지해 있는 중일 수 있고, 그것을 막으면 전투가 성립하지 않는다. 기억을 지우는 이유는 다음에
	 * 그 페이즈로 돌아올 때 {@code begin()} 이 대상을 비워 두기 때문이다 — 「이미 심었다」고
	 * 착각하면 드래곤이 대상 없는 페이즈에서 곧바로 물러난다.
	 */
	private static void steer(EnderDragon dragon, ServerPlayer target) {
		EnderDragonPhaseManager manager = dragon.getPhaseManager();
		DragonPhaseInstance current = manager == null ? null : manager.getCurrentPhase();
		EnderDragonPhase<?> phase = current == null ? null : current.getPhase();
		if (phase != EnderDragonPhase.STRAFE_PLAYER && phase != EnderDragonPhase.CHARGING_PLAYER) {
			appliedPhase = null;
			appliedTarget = null;
			return;
		}
		// 같은 페이즈에서 같은 대상을 다시 심지 않는다. 불덩이 쪽은 심을 때마다 경로를 새로 깔아
		// 매 틱 부르면 드래곤이 앞으로 나가지 못하고, 돌진 쪽은 표적을 따라다니는 유도탄이 된다.
		if (phase == appliedPhase && target.getUUID().equals(appliedTarget)) {
			return;
		}
		if (phase == EnderDragonPhase.STRAFE_PLAYER) {
			manager.getPhase(EnderDragonPhase.STRAFE_PLAYER).setTarget(target);
		} else {
			// 돌진은 사람이 아니라 한 점을 받는다. 지금 자리를 찍어 주면 그 자리로 파고든다.
			manager.getPhase(EnderDragonPhase.CHARGING_PLAYER).setTarget(target.position());
		}
		appliedPhase = phase;
		appliedTarget = target.getUUID();
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

	/** 표적 발밑에 보라색 고리를 그린다. 위험 범위가 아니라 「이 사람이다」라는 이름표다. */
	private static void mark(ServerLevel end, ServerPlayer target) {
		TrialWarning.markGround(end, target.position(), MARK_RADIUS,
				TrialWarning.dust(TrialWarning.Colors.MARKED));
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

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
