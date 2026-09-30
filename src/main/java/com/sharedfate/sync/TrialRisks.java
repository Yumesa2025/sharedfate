package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 고른 시련 카드의 위험을 실제로 돌린다.
 *
 * <h2>왜 값으로 도는가</h2>
 *
 * <p>앞선 구현은 카드 id 를 코드 안에서 문자열로 직접 비교하고 주기·피해를 클래스 상수로 들고
 * 있었다. 그래서 <b>두 장째 카드를 만들 수 없었다</b> — 「같은 패턴인데 주기만 다른 카드」를
 * 넣으려면 클래스를 통째로 복사해야 했다. 여기서는 {@link TrialCatalog.Risk} 값을 읽어 돌리므로
 * 카드를 늘리는 일이 {@code TrialCatalog} 에 줄 하나 더 적는 일이 된다.
 *
 * <h2>실행을 빠뜨리면 컴파일이 거절한다</h2>
 *
 * <p>위험 분기는 {@code default} 없는 패턴 switch 다. {@code Risk} 는 봉인 인터페이스라 자바가
 * 모든 갈래를 덮었는지 검사한다. 타입을 하나 더하고 여기에 실행을 안 붙이면 <b>빌드가 깨진다.</b>
 * {@code default} 를 넣는 순간 이 보호가 사라지고, 새 위험은 조용히 아무 일도 하지 않는 카드가
 * 된다 — 이 게임은 전멸하면 월드가 지워지므로 「예고만 나오고 안 터지는」 것보다 「빌드가 깨지는」
 * 쪽이 훨씬 싸다. 넣지 말 것.
 *
 * <h2>주기는 카드를 받은 틱부터 센다</h2>
 *
 * <p>월드 시간({@code now % interval})으로 세면 카드를 받은 순간이 마침 발동 위상일 때 예고를
 * 하나도 못 본 채 맞고, 주기가 같은 카드 둘은 영원히 같은 틱에 함께 터진다. 시련이 전투가 끝날
 * 때까지 쌓이는 것이 이 기능의 전부라 그냥 둘 수 없다. {@link DragonTrialSession#grantedTick} 을
 * 기준으로 삼으면 카드마다 위상이 저절로 어긋나고, 받은 직후 한 주기는 온전히 예고에 쓰인다.
 *
 * <h2>왜 계산을 순수 함수로 떼는가</h2>
 *
 * <p>위상·대상 수·아레나 좌표는 {@code ServerLevel} 없이 답이 정해지는 계산이다. 떼어 두면
 * 월드를 띄우지 않고 시험할 수 있고, 「받자마자 터진다」·「음수 위상」 같은 사고를 돌려 보지 않고
 * 잡을 수 있다. 이 저장소가 {@code PerkOfferScreen.isSelectClick} 에서 이미 쓰는 방식이다.
 * 패키지 밖에 내보내지 않으려고 {@code static} 패키지 전용으로 둔다.
 */
public final class TrialRisks {

	/**
	 * 아무 곳을 고를 때 쓰는 아레나 반경.
	 *
	 * <p>엔드 중앙 섬 바깥은 허공이다. 거기에 떨어뜨리면 예고도 피해도 아무 뜻이 없으므로 중앙
	 * {@code (0, ?, 0)} 기준으로 이 안에서만 고른다. 팀을 내려놓는 자리
	 * ({@code DragonTrialManager} 의 도착 반경)와 같은 크기다.
	 *
	 * <p>패키지 전용인 것은 {@code TrialRisksTest} 가 겹침을 같은 조건으로 굴려 보기 위해서다 —
	 * 시험이 제 숫자를 따로 들면 실제와 다른 아레나에서 확인한 것이 된다.
	 */
	static final double ARENA_RADIUS = 40.0;
	/**
	 * 허공이거나 이미 뽑은 지점과 너무 가까울 때 다시 굴리는 횟수.
	 *
	 * <p>여기까지 실패하면 이번 지점은 <b>포기한다</b>. 겹치느니 한 발 빠지는 쪽이다 — 카드에 적힌
	 * 개수를 맞추려고 겹쳐 놓으면 그 순간 즉사 카드가 된다.
	 *
	 * <p>반경 3 짜리 열 곳을 반경 40 아레나에 놓는 지금 카드에서는 5백만 판을 굴려 포기가 8번
	 * 나왔다(지점 5천만 개 중 8개). 실질적으로 늘 열 곳이 다 선다.
	 */
	static final int SPOT_TRIES = 8;

	/** 플레이어 중력(블록/틱²). 띄울 높이를 속도로 바꿀 때 쓴다. */
	private static final double GRAVITY_PER_TICK = 0.08;
	/** 띄운 뒤 낙하 피해를 면제하는 여유 틱. 착지가 늦어져도 면제가 먼저 끊기지 않게 한다. */
	private static final int FALL_GRACE_MARGIN = 20;

	/**
	 * 사람마다의 발자국. 오래된 것이 앞이다.
	 *
	 * <p>정적 맵인 이유는 위험이 값(레코드)이라 상태를 들 수 없기 때문이다. 월드가 바뀌면 남은
	 * 좌표가 새 판의 사람에게 붙을 수 있으므로 {@link #clearState()} 로 반드시 비운다.
	 */
	private static final Map<UUID, List<Vec3>> TRAILS = new HashMap<>();
	/** 위험마다 이번 주기에 노리기로 한 사람. */
	private static final Map<String, Cycle<UUID>> TRAIL_PICKS = new HashMap<>();
	/** 위험마다 이번 주기에 터지기로 한 자리. */
	private static final Map<String, Cycle<Vec3>> SPOT_PICKS = new HashMap<>();
	/**
	 * ⚠ <b>지금 바닥에 살아 있는 위험 지점 전부.</b> 열쇠는 위험 하나다.
	 *
	 * <h2>한 카드 안에서만 떼어 놓는 것으로는 모자라다</h2>
	 *
	 * <p>전에는 같은 카드의 지점끼리만 간격을 지켰다. 그런데 시련은 전투가 끝날 때까지 <b>쌓인다</b> —
	 * 「낙뢰」(피해 18)와 「종말의 비」(피해 10)는 자리가 달라 서로 다른 트리거에서 오지만,
	 * 한번 받고 나면 <b>둘 다 동시에 돈다.</b> 두 고리가 겹친 자리에 선 사람은 한 틱에 <b>28</b> 을
	 * 받고 팀 공유 체력은 20 이다. 그 한 틱에 전멸이고 전멸은 곧 월드 삭제다.
	 *
	 * <p>그래서 검사 범위를 <b>살아 있는 모든 지점</b>으로 넓혔다. 새로 지점을 잡는 쪽은 자기
	 * 카드가 아니라 이 목록 전체에서 떨어져야 한다.
	 *
	 * <p>⚠ <b>앞으로 추가되는 카드도 이 목록에 들어가야 한다.</b> 바닥에 표시를 띄우고 그 자리를
	 * 때리는 위험을 새로 만들면서 {@link #reserveSpots} 를 지나지 않으면, 그 카드만 남의 고리
	 * 위에 겹쳐 떨어진다 — 컴파일도 시험도 조용하고, 실제 전투에서 어느 판에 한 번 전멸한다.
	 *
	 * <p>⚠⚠ <b>예외가 하나 있다 — 「종말의 비」({@link TrialEndRain}).</b> 사람이 지점 수를
	 * 90곳으로 올리라고 했는데 이 목록을 지나면 84곳밖에 안 서고 같이 걸린 「낙뢰」가 반토막
	 * 나서, <b>「서로 겹쳐도 되니까 내가 말한 숫자로」</b>라고 정했다. 그래서 그 카드만 자리를
	 * 스스로 굴리고 여기에 한 줄도 올리지 않는다. <b>따라 하지 말 것</b> — 그 카드는 고리 셋이
	 * 겹친 자리가 무장하고도 전멸이고, 그것을 알고 고른 값이다. 까닭과 실측은 그 파일에 있고
	 * {@code TrialRisksTest} 가 <b>예외가 그 하나뿐인지</b>를 지킨다.
	 */
	private static final Map<String, List<LiveSpot>> LIVE_SPOTS = new LinkedHashMap<>();
	/** 띄워진 사람과 낙하 피해 면제가 끝나는 시각. */
	private static final Map<UUID, Long> FALL_GRACE = new HashMap<>();

	/**
	 * 살아 있는 위험 지점 하나.
	 *
	 * <p>반경을 함께 든다. 카드마다 고리 크기가 다르므로({@code 낙뢰} 3, {@code 종말의 비} 2.5)
	 * 지켜야 할 간격이 <b>두 반경의 합</b>이고, 한쪽 반경만으로는 계산할 수 없다.
	 */
	record LiveSpot(Vec3 at, double radius) {
	}

	/**
	 * 한 주기 동안 붙잡아 두는 대상.
	 *
	 * <p>매 틱 다시 뽑으면 경고 표식이 사람들 사이를 뛰어다녀 아무도 피할 수 없다. 주기 번호가
	 * 바뀔 때만 다시 뽑는다.
	 */
	private record Cycle<T>(long index, List<T> targets) {
	}

	/** 지금 돌고 있는 위험 하나. 카드 id 와 카드 안 순번으로 열쇠를 만든다. */
	private record Active(String key, String trialId, TrialCatalog.Risk risk) {
	}

	private TrialRisks() {
	}

	/**
	 * 매 틱. 고른 카드들의 위험을 모두 돌린다.
	 *
	 * <p>카드가 한 장도 없으면 발자국조차 쌓지 않는다 — 시련 없이 드래곤만 잡는 판에서 매 틱
	 * 좌표를 쌓아 둘 이유가 없다.
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, @Nullable DragonTrialSession session, long now) {
		if (end == null || session == null || members == null || members.isEmpty()) {
			return;
		}

		List<Active> active = activeRisks(session);
		if (active.isEmpty()) {
			forget();
			return;
		}
		// 카드가 빠지는 길은 없지만, 되살린 세션에 없어진 카드가 들어 있을 수 있다. 남은 열쇠를
		// 그대로 두면 옛 주기 번호 때문에 첫 발동이 한 번 건너뛰어진다.
		Set<String> keys = new HashSet<>();
		for (Active entry : active) {
			keys.add(entry.key());
		}
		TRAIL_PICKS.keySet().retainAll(keys);
		SPOT_PICKS.keySet().retainAll(keys);
		// 없어진 카드가 잡아 둔 지점을 놓지 않으면 아레나 일부가 영영 막힌 채로 남는다.
		LIVE_SPOTS.keySet().retainAll(keys);

		recordTrails(members, lookbackNeeded(active));
		relieveFalls(members, now);

		for (Active entry : active) {
			long granted = session.grantedTick(entry.trialId());
			// default 를 넣지 말 것. 이 switch 가 위험 타입과 실행을 묶어 두는 유일한 장치다.
			switch (entry.risk()) {
				case TrialCatalog.Risk.DelayedStrike strike -> runDelayedStrike(
						end, members, entry.key(), granted, now, strike);
				case TrialCatalog.Risk.TracedProjectile shot -> TrialFireball.tick(
						end, dragon, members, entry.key(), granted, now, shot);
				case TrialCatalog.Risk.CrystalGuard guard -> TrialCrystalGuard.tick(
						end, dragon, members, entry.key(), granted, now, guard);
				case TrialCatalog.Risk.DragonFocus focus -> TrialDragonFocus.tick(
						end, dragon, members, entry.key(), granted, now, focus);
				case TrialCatalog.Risk.CrystalRevive revive -> TrialCrystalRevive.tick(
						end, dragon, members, entry.key(), granted, now, revive);
				case TrialCatalog.Risk.EnderPulse pulse -> TrialEnderPulse.tick(
						end, dragon, members, entry.key(), granted, now, pulse);
				case TrialCatalog.Risk.CrystalLink link -> TrialCrystalLink.tick(
						end, dragon, members, entry.key(), granted, now, link);
				case TrialCatalog.Risk.CrystalOvercharge overcharge -> TrialCrystalOvercharge.tick(
						end, dragon, members, entry.key(), granted, now, overcharge);
				case TrialCatalog.Risk.EnderStorm storm -> TrialEnderStorm.tick(
						end, dragon, members, entry.key(), granted, now, storm);
				case TrialCatalog.Risk.DryWorld dry -> TrialDryWorld.tick(
						end, dragon, members, entry.key(), granted, now, dry);
				case TrialCatalog.Risk.NightHost host -> TrialNightHost.tick(
						end, dragon, members, entry.key(), granted, now, host);
				case TrialCatalog.Risk.EndRain rain -> TrialEndRain.tick(
						end, dragon, members, entry.key(), granted, now, rain);
				case TrialCatalog.Risk.LandingShock shock -> TrialLandingShock.tick(
						end, dragon, members, entry.key(), granted, now, shock);
				case TrialCatalog.Risk.HotbarLock lock -> TrialHotbarLock.tick(
						end, dragon, members, entry.key(), granted, now, lock);
			}
		}
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. 사람에게 붙은 것이 다음 판으로 새지 않게 한다.
	 *
	 * <p><b>실행기를 하나 더 만들면 여기에 반드시 더하라.</b> 위험은 값(레코드)이라 상태를 들 수
	 * 없어 저마다 정적 맵을 쓴다. 빠뜨리면 지난 판의 조준점·표적·화살 면역이 다음 판으로 샌다 —
	 * 컴파일도 시험도 조용한 종류의 사고다.
	 */
	public static void clearState() {
		forget();
		TrialFireball.clearState();
		TrialCrystalGuard.clearState();
		TrialDragonFocus.clearState();
		TrialCrystalRevive.clearState();
		TrialEnderPulse.clearState();
		TrialCrystalLink.clearState();
		TrialCrystalOvercharge.clearState();
		TrialEnderStorm.clearState();
		TrialDryWorld.clearState();
		TrialNightHost.clearState();
		TrialEndRain.clearState();
		TrialLandingShock.clearState();
		TrialHotbarLock.clearState();
		CrystalWatch.clearState();
	}

	private static void forget() {
		TRAILS.clear();
		TRAIL_PICKS.clear();
		SPOT_PICKS.clear();
		LIVE_SPOTS.clear();
		FALL_GRACE.clear();
	}

	// ------------------------------------------------------------------ 카드 읽기

	/**
	 * 고른 카드들이 걸고 있는 위험을 펼친다.
	 *
	 * <p>목록에 없는 id 는 조용히 건너뛴다. 옛 저장 파일에 없어진 카드가 들어 있다고 전투가
	 * 멈추면 안 된다.
	 */
	private static List<Active> activeRisks(DragonTrialSession session) {
		List<Active> active = new ArrayList<>();
		for (String id : session.chosen()) {
			TrialCatalog.Trial trial = TrialCatalog.byId(id);
			if (trial == null) {
				continue;
			}
			List<TrialCatalog.Risk> risks = trial.risks();
			for (int index = 0; index < risks.size(); index++) {
				// 한 카드가 위험 둘을 걸 수 있으므로 카드 id 만으로는 열쇠가 겹친다.
				active.add(new Active(id + '#' + index, id, risks.get(index)));
			}
		}
		return active;
	}

	/** 지금 도는 위험 중 가장 멀리 거슬러 올라가는 발자국. 발자국 기록은 이만큼만 남긴다. */
	private static int lookbackNeeded(List<Active> active) {
		int deepest = 0;
		for (Active entry : active) {
			switch (entry.risk()) {
				case TrialCatalog.Risk.DelayedStrike strike -> {
					if (strike.aim() == TrialCatalog.Risk.Aim.TRAIL) {
						deepest = Math.max(deepest, strike.lookback());
					}
				}
				// 아래는 전부 발자국을 쓰지 않는다. 지금 자리나 크리스탈이나 드래곤을 본다.
				case TrialCatalog.Risk.TracedProjectile ignored -> {
				}
				case TrialCatalog.Risk.CrystalGuard ignored -> {
				}
				case TrialCatalog.Risk.DragonFocus ignored -> {
				}
				case TrialCatalog.Risk.CrystalRevive ignored -> {
				}
				case TrialCatalog.Risk.EnderPulse ignored -> {
				}
				case TrialCatalog.Risk.CrystalLink ignored -> {
				}
				case TrialCatalog.Risk.CrystalOvercharge ignored -> {
				}
				case TrialCatalog.Risk.EnderStorm ignored -> {
				}
				case TrialCatalog.Risk.DryWorld ignored -> {
				}
				case TrialCatalog.Risk.NightHost ignored -> {
				}
				case TrialCatalog.Risk.EndRain ignored -> {
				}
				case TrialCatalog.Risk.LandingShock ignored -> {
				}
				case TrialCatalog.Risk.HotbarLock ignored -> {
				}
			}
		}
		return deepest;
	}

	// ------------------------------------------------------------------ 예고 있는 폭격

	private static void runDelayedStrike(ServerLevel end, List<ServerPlayer> members, String key,
			long granted, long now, TrialCatalog.Risk.DelayedStrike strike) {
		if (strike.interval() <= 0 || strike.count() <= 0 || !(strike.radius() > 0.0)) {
			return;
		}
		// 받은 바로 그 틱에는 아직 아무 주기도 시작되지 않았다.
		if (elapsedSinceGrant(now, granted) <= 0L) {
			return;
		}

		long cycle = strikeIndex(now, granted, strike.interval());
		int remaining = remainingTicks(now, granted, strike.interval());

		List<Vec3> spots = switch (strike.aim()) {
			case TRAIL -> trailSpots(end, members, key, cycle, strike);
			case RANDOM_SPOT -> spotsInArena(end, key, cycle, strike);
		};
		if (spots.isEmpty()) {
			return;
		}

		warn(end, spots, remaining, strike);
		if (firesAt(now, granted, strike.interval())) {
			for (Vec3 spot : spots) {
				detonate(end, spot, strike, now);
			}
		}
	}

	/**
	 * 세 층을 올린다.
	 *
	 * <p>경고 없이 터지는 길을 만들지 않는다. 층이 바뀌는 순간에만 소리를 내는 것은
	 * {@link TrialWarning#sound} 가 호출자에게 맡긴 몫이다 — 매 틱 부르면 그 층 내내 울린다.
	 *
	 * <p>고리 색은 {@link #markColor} 가 연출 값에서 뽑는다. 파티클을 고리 밖에서 한 번만 만드는
	 * 것은 「낙뢰」가 한 번에 열 곳이라 매 틱 열 번 새로 만들 이유가 없어서다.
	 *
	 * <p>사람 목록을 받지 않는다. 「발밑을 보십시오」·「표시된 자리에서 벗어나십시오」를 노려진
	 * 사람에게만 띄우던 줄을 걷어냈고({@link TrialWarning#shout}), 남은 소리와 고리는 둘 다
	 * <b>자리</b>에서 나가기 때문이다.
	 */
	private static void warn(ServerLevel end, List<Vec3> spots, int remaining,
			TrialCatalog.Risk.DelayedStrike strike) {
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage == null) {
			return;
		}
		// 예고는 한 주기 통째다. 주기가 시작되는 틱의 남은 틱이 interval - 1 이므로 그것이
		// 이 위험의 예고 길이다 — 주기가 짧은(≤51틱) 카드가 새로 나와도 첫 층을 잃지 않는다.
		boolean changed = stageJustChanged(remaining, strike.interval() - 1);
		ParticleOptions mark = TrialWarning.dust(markColor(strike.impact()));
		for (Vec3 spot : spots) {
			if (stage != TrialWarning.Stage.APPROACH) {
				TrialWarning.markGround(end, spot, strike.radius(), mark);
			}
			if (changed) {
				TrialWarning.sound(end, spot, stage);
			}
		}
	}

	/**
	 * 예고한 자리에서 실제로 터진다.
	 *
	 * <p>연출과 피해를 나눠 둔다. 연출은 카드가 적어 둔 {@link TrialCatalog.Risk.Impact} 로 갈리고
	 * 피해는 어느 연출이든 똑같이 들어간다 — <b>「무엇으로 보이는가」가 「얼마나 아픈가」를
	 * 바꾸면 안 된다.</b>
	 */
	private static void detonate(ServerLevel end, Vec3 at, TrialCatalog.Risk.DelayedStrike strike,
			long now) {
		// default 를 넣지 말 것. Impact 를 늘리고 연출을 안 붙이면 여기서 빌드가 깨져야 한다 —
		// 「낙뢰인데 폭발이 나가는」 이번 결함이 바로 연출이 갈리지 않아서 생겼다.
		switch (strike.impact()) {
			case EXPLOSION -> showExplosion(end, at);
			case LIGHTNING -> strikeLightning(end, at);
		}
		// 피해는 반경 안에 남아 있는 사람에게만 들어간다. 움직였으면 빗나간 것이다.
		for (ServerPlayer nearby : end.getEntitiesOfClass(ServerPlayer.class,
				new AABB(at, at).inflate(strike.radius()))) {
			if (!insideMark(nearby.position(), at, strike.radius())) {
				continue;
			}
			nearby.hurtServer(end, end.damageSources().lightningBolt(), strike.damage());
			launch(nearby, strike.launch(), now);
		}
	}

	/**
	 * 그 자리가 터지는 연출.
	 *
	 * <p>{@code LIGHTNING_BOLT_IMPACT} 는 이름과 달리 <b>바닐라가 「내리친 것이 땅에 닿는 굉음」으로
	 * 쓰는 소리</b>다. 번개 엔티티도 스스로 이 소리를 낸다. 여기서는 폭발음으로 쓴다 — 사람이
	 * 「자리 폭격」에서 들어야 하는 것이 정확히 이 소리라서 바꾸지 않았다.
	 */
	private static void showExplosion(ServerLevel end, Vec3 at) {
		// 긴 거리로 보낸다. 짧은 형태는 32칸에서 잘리는데, 「자리 폭격」이 노리는 자리는 아레나
		// 반경 40 안의 어디든이라 반대편에 선 팀원에게는 80칸까지 벌어진다. 맞은 사람만 보고
		// 나머지는 소리만 듣는 연출이 되어, 「무엇이 터졌는지」가 팀에 공유되지 않는다.
		end.sendParticles(ParticleTypes.EXPLOSION, true, false,
				at.x, at.y + 0.2, at.z, 1, 0.0, 0.0, 0.0, 0.0);
		end.playSound(null, at.x, at.y, at.z, SoundEvents.LIGHTNING_BOLT_IMPACT,
				SoundSource.HOSTILE, 2.0F, 1.0F);
	}

	/**
	 * 진짜 번개가 내리치는 연출.
	 *
	 * <h2>왜 파티클이 아니라 엔티티인가</h2>
	 *
	 * <p>번개는 기둥 모델·하늘 섬광({@code setSkyFlashTime})·천둥소리가 한 덩어리다. 파티클과 소리로
	 * 흉내 내면 그 어느 것도 나오지 않아 <b>「번개」라고 적힌 카드가 폭발로 보인다</b> — 이번 결함이
	 * 정확히 그것이었다. 엔티티를 띄우면 클라이언트가 전부 알아서 그린다.
	 *
	 * <h2>연출 전용으로만 띄운다</h2>
	 *
	 * <p>{@code setVisualOnly(true)} 를 <b>반드시</b> 켠다. 26.3 의 {@code LightningBolt} 는 이
	 * 깃발 하나로 두 가지를 동시에 끈다.
	 *
	 * <ul>
	 *   <li>{@code tick()} 의 피해 구간이 통째로 건너뛰어진다 — 우리가 {@code hurtServer} 로 이미
	 *       카드에 적힌 값을 주므로, 켜지 않으면 <b>피해가 조용히 두 배</b>가 된다</li>
	 *   <li>{@code spawnFire(int)} 가 첫 줄에서 되돌아간다 — 불이 붙지 않는다. 엔드에도 플레이어가
	 *       놓은 블록이 있고, 무엇보다 <b>우리 카드 어디에도 「불」이 적혀 있지 않다</b></li>
	 * </ul>
	 *
	 * <p>이 깃발은 <b>로그도 빌드도 알려 주지 않는</b> 종류의 것이다. 판을 올릴 때 메서드가 사라지면
	 * 피해가 두 배가 된 채로 굴러가므로 {@code TrialRisksTest} 가 클래스 파일에서 직접 찾아 둔다.
	 *
	 * <p>소리와 파티클을 덧붙이지 않는다. 번개 엔티티가 스스로 천둥과 착탄음을 내는데 그 위에 폭발
	 * 파티클을 뿌리는 것이 지금 TNT 처럼 보였던 이유다.
	 */
	private static void strikeLightning(ServerLevel end, Vec3 at) {
		LightningBolt bolt = EntityTypes.LIGHTNING_BOLT.create(end, EntitySpawnReason.TRIGGERED);
		if (bolt == null) {
			return;
		}
		bolt.setVisualOnly(true);
		// 표식을 그린 바로 그 자리에 세운다. 블록 중앙으로 맞추면 고리와 번개가 어긋난다.
		bolt.snapTo(at);
		end.addFreshEntity(bolt);
	}

	// ------------------------------------------------------------------ 자리 고르기

	/**
	 * 발자국을 노리는 자리들.
	 *
	 * <p>대상은 주기마다 새로 뽑는다. 한 사람만 계속 노리면 그 사람만 게임을 하게 된다. 다만
	 * <b>뽑은 대상은 그 주기 동안 유지</b>한다 — 매 틱 다시 뽑으면 표식이 사람들 사이를 뛰어다닌다.
	 *
	 * <p>표식이 가리키는 <b>자리</b>는 매 틱 갱신된다. 그 사람이 {@code lookback} 틱 전에 있던
	 * 자리를 쫓아오므로, 멈춰 서면 맞고 계속 움직이면 빗나간다. 이것이 이 카드의 전부다.
	 *
	 * <p>노려진 사람을 담아 돌려주던 칸이 있었다. 그 사람들에게만 자막을 띄우려던 것인데 자막을
	 * 걷어냈으므로 함께 지웠다 — 남은 신호는 자리에서 나가고, 사람은 <b>자기 발자국 위에 뜬
	 * 고리</b>로 자기가 물렸음을 안다.
	 */
	private static List<Vec3> trailSpots(ServerLevel end, List<ServerPlayer> members, String key,
			long cycle, TrialCatalog.Risk.DelayedStrike strike) {
		Cycle<UUID> picked = TRAIL_PICKS.get(key);
		if (picked == null || picked.index() != cycle) {
			List<UUID> ids = new ArrayList<>();
			for (int index : pickIndexes(strike.count(), members.size(), end.getRandom())) {
				ids.add(members.get(index).getUUID());
			}
			picked = new Cycle<>(cycle, ids);
			TRAIL_PICKS.put(key, picked);
		}

		List<Vec3> spots = new ArrayList<>();
		for (ServerPlayer member : members) {
			if (!picked.targets().contains(member.getUUID())) {
				continue;
			}
			Vec3 past = trailPosition(member.getUUID(), strike.lookback());
			if (past == null) {
				// 아직 발자국이 그만큼 쌓이지 않았다. 지금 자리를 노리면 예고가 무의미해진다.
				continue;
			}
			spots.add(past);
		}
		return spots;
	}

	/**
	 * 아레나 안 아무 자리들.
	 *
	 * <p>자리는 주기마다 한 번만 굴리고 그대로 들고 간다. 매 틱 다시 굴리면 예고가 예고가 아니다.
	 *
	 * <p>지점은 {@link #reserveSpots} 를 지나 <b>살아 있는 지점 목록 전체</b>에서 떨어진 자리로
	 * 잡는다 — 고리끼리 겹치면 그 겹친 구역이 즉사 구역이기 때문이다. 까닭은 {@link #LIVE_SPOTS}
	 * 와 {@link #spotMinGap} 에 적어 두었다.
	 */
	private static List<Vec3> spotsInArena(ServerLevel end, String key, long cycle,
			TrialCatalog.Risk.DelayedStrike strike) {
		Cycle<Vec3> picked = SPOT_PICKS.get(key);
		if (picked != null && picked.index() == cycle) {
			return picked.targets();
		}
		List<Vec3> spots = reserveSpots(end, key, strike.count(), strike.radius());
		SPOT_PICKS.put(key, new Cycle<>(cycle, spots));
		return spots;
	}

	/**
	 * ⚠ 지점을 잡는 <b>유일한 길</b>. 살아 있는 다른 지점 전부에서 떨어진 자리만 돌려준다.
	 *
	 * <p>앞서 이 열쇠로 잡아 둔 것은 먼저 놓는다 — 지난 주기의 고리는 이미 터졌으므로 새 주기의
	 * 자리를 막으면 안 된다.
	 *
	 * <p>⚠ <b>바닥에 표시를 띄우고 그 자리를 때리는 카드를 새로 만들면 반드시 여기를 지날 것.</b>
	 * 직접 굴리면 그 카드만 남의 고리 위에 겹치고, 「낙뢰」 18 과 「종말의 비」 10 이 겹친 자리는
	 * 한 틱에 28 이라 팀 공유 체력 20 을 넘긴다. 까닭은 {@link #LIVE_SPOTS} 에 있다.
	 *
	 * @param key    이 위험을 가리키는 열쇠. {@code 카드 id + '#' + 위험 순번} 꼴이다
	 * @param count  잡고 싶은 개수. 자리를 못 찾으면 적힌 것보다 적게 돌아온다 —
	 *               겹치느니 한 발 빠지는 쪽이다
	 * @param radius 고리 반경. 지켜야 할 간격이 여기서 나온다
	 */
	static List<Vec3> reserveSpots(ServerLevel end, String key, int count, double radius) {
		LIVE_SPOTS.remove(key);
		List<LiveSpot> taken = liveSpots();
		List<Vec3> spots = new ArrayList<>();
		List<LiveSpot> mine = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			Vec3 spot = groundSpot(end, taken, radius);
			if (spot == null) {
				continue;
			}
			spots.add(spot);
			LiveSpot live = new LiveSpot(spot, radius);
			// 방금 잡은 것도 곧바로 목록에 넣는다. 같은 볼리 안에서도 겹치면 안 된다.
			taken.add(live);
			mine.add(live);
		}
		LIVE_SPOTS.put(key, mine);
		return spots;
	}

	/**
	 * 이 열쇠가 잡아 둔 지점을 놓는다.
	 *
	 * <p>한 번 터지고 끝나는 카드는 스스로 놓아야 한다. 주기로 도는 카드는 {@link #reserveSpots}
	 * 가 다음 주기에 알아서 놓는다.
	 */
	static void releaseSpots(String key) {
		LIVE_SPOTS.remove(key);
	}

	/** 지금 바닥에 살아 있는 지점 전부. 위험을 가리지 않는다 — 겹침은 카드를 가려 주지 않는다. */
	static List<LiveSpot> liveSpots() {
		List<LiveSpot> all = new ArrayList<>();
		for (List<LiveSpot> held : LIVE_SPOTS.values()) {
			all.addAll(held);
		}
		return all;
	}

	/**
	 * 아레나 안에서 발 디딜 수 있고 살아 있는 지점 어느 것과도 겹치지 않는 자리 하나.
	 *
	 * <p>다시 굴리는 까닭이 둘이다.
	 *
	 * <ul>
	 *   <li><b>허공</b> — 중앙 섬은 둥글지 않아 반경 안에도 빈 곳이 있다. 거기서 터지면 예고도
	 *       피해도 뜻이 없다</li>
	 *   <li><b>겹침</b> — 살아 있는 고리와 너무 가까우면 겹친 구역에 선 사람이 한 틱에 두 번
	 *       맞는다. {@link #spotMinGap} 을 볼 것</li>
	 * </ul>
	 *
	 * <p>둘 다 같은 굴림으로 거른다. 한쪽만 통과한 자리는 쓰지 않는다.
	 *
	 * @param taken  지금 살아 있는 지점들. <b>자기 카드의 것만이 아니다</b>
	 * @param radius 고리 반경. 최소 간격이 여기서 나온다
	 * @return 끝내 못 찾으면 {@code null}. 그 지점은 이번 주기에 빠진다
	 */
	private static @Nullable Vec3 groundSpot(ServerLevel end, List<LiveSpot> taken, double radius) {
		RandomSource random = end.getRandom();
		for (int attempt = 0; attempt < SPOT_TRIES; attempt++) {
			Vec3 offset = arenaOffset(random.nextDouble(), random.nextDouble(), ARENA_RADIUS);
			if (!clearOfTaken(taken, offset, radius)) {
				continue;
			}
			BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					BlockPos.containing(offset.x, 0.0, offset.z));
			// 허공이면 하이트맵이 월드 바닥을 돌려준다.
			if (ground.getY() > end.getMinY()) {
				return new Vec3(offset.x, ground.getY(), offset.z);
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ 발자국

	private static void recordTrails(List<ServerPlayer> members, int lookback) {
		int keep = Math.max(1, lookback + 1);
		Set<UUID> present = new HashSet<>();
		for (ServerPlayer member : members) {
			UUID memberId = member.getUUID();
			present.add(memberId);
			List<Vec3> trail = TRAILS.computeIfAbsent(memberId, key -> new ArrayList<>());
			trail.add(member.position());
			while (trail.size() > keep) {
				trail.removeFirst();
			}
		}
		// 접속을 끊은 사람의 좌표를 들고 있어 봐야 다시 들어오면 순간이동한 발자국이 된다.
		TRAILS.keySet().retainAll(present);
	}

	private static @Nullable Vec3 trailPosition(UUID memberId, int lookback) {
		List<Vec3> trail = TRAILS.get(memberId);
		if (trail == null || trail.size() <= Math.max(0, lookback)) {
			return null;
		}
		return trail.get(trail.size() - 1 - Math.max(0, lookback));
	}

	// ------------------------------------------------------------------ 띄우기

	/**
	 * 맞은 사람을 띄운다.
	 *
	 * <p>서버가 실은 속도는 {@code syncVelocity} 를 켜야 클라이언트에 내려간다. 켜지 않으면 서버만
	 * 혼자 띄운 것이 되어, 잠시 뒤 클라이언트가 보고한 제자리로 되돌아간다.
	 *
	 * <p>낙하 피해는 면제한다. 이 카드의 위험은 <b>띄워져 회피가 막히는 것</b>이지 낙사가 아니다.
	 * 4블록만 띄워도 착지 피해가 붙으면 카드 설명과 실제가 달라지고, 공유 체력이라 한 사람의
	 * 낙사가 팀 전체를 깎는다.
	 */
	private static void launch(ServerPlayer player, double height, long now) {
		if (!(height > 0.0)) {
			return;
		}
		Vec3 motion = player.getDeltaMovement();
		player.setDeltaMovement(motion.x, launchVelocity(height), motion.z);
		player.syncVelocity = true;
		player.fallDistance = 0.0;
		player.resetFallDistance();
		FALL_GRACE.put(player.getUUID(), now + fallGraceTicks(height));
	}

	/**
	 * 띄워져 있는 동안 쌓이는 낙하 거리를 계속 지운다.
	 *
	 * <p>띄우는 순간 한 번 지우는 것으로는 부족하다. 낙하 거리는 떨어지는 매 틱 쌓이므로, 착지할
	 * 때까지 지워 주지 않으면 결국 착지 피해가 들어간다.
	 */
	private static void relieveFalls(List<ServerPlayer> members, long now) {
		if (FALL_GRACE.isEmpty()) {
			return;
		}
		for (ServerPlayer member : members) {
			Long until = FALL_GRACE.get(member.getUUID());
			if (until == null) {
				continue;
			}
			if (now > until) {
				FALL_GRACE.remove(member.getUUID());
				continue;
			}
			member.fallDistance = 0.0;
			member.resetFallDistance();
		}
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 카드를 받은 뒤 흐른 틱.
	 *
	 * <p>복원 직후에는 {@code now} 가 받은 틱보다 작을 수 있다 — 세션은 저장 파일에서 오고 게임
	 * 시각은 월드에서 온다. 그대로 나누면 위상이 음수가 되어 예고 없이 터진다.
	 */
	static long elapsedSinceGrant(long now, long grantedTick) {
		return Math.max(0L, now - grantedTick);
	}

	/**
	 * 지금 예고하고 있는 발동이 몇 번째인가. 0 부터 센다.
	 *
	 * <p>발동하는 그 틱까지 같은 번호를 유지해야 한다. 「예고 중에는 N, 터지는 순간 N+1」이 되면
	 * 뽑아 둔 대상이 정작 필요한 틱에 버려진다.
	 */
	static long strikeIndex(long now, long grantedTick, int interval) {
		if (interval <= 0) {
			return 0L;
		}
		return (Math.max(1L, elapsedSinceGrant(now, grantedTick)) - 1L) / interval;
	}

	/** 발동까지 남은 틱. 0 이면 이번 틱에 터진다. */
	static int remainingTicks(long now, long grantedTick, int interval) {
		if (interval <= 0) {
			return Integer.MAX_VALUE;
		}
		long into = (Math.max(1L, elapsedSinceGrant(now, grantedTick)) - 1L) % interval;
		return interval - 1 - (int) into;
	}

	/**
	 * 이번 틱에 터지는가.
	 *
	 * <p>받은 직후 한 주기는 온전히 예고에 쓴다. 받자마자 터지면 카드 설명을 읽는 중에 맞는다.
	 */
	static boolean firesAt(long now, long grantedTick, int interval) {
		if (interval <= 0) {
			return false;
		}
		long elapsed = elapsedSinceGrant(now, grantedTick);
		return elapsed >= interval && elapsed % interval == 0L;
	}

	/**
	 * 층이 방금 바뀌었는가. 예고가 시작되는 틱을 <b>모르는</b> 형태다.
	 *
	 * <p>예고가 100틱보다 길게 시작하는 위험에만 쓸 것 — 그때는 첫 층
	 * ({@link TrialWarning.Stage#APPROACH}, ≤100)의 경계를 지나는 틱이 반드시 있어
	 * 아래 {@link #stageJustChanged(int, int)} 와 답이 같다.
	 *
	 * @see #stageJustChanged(int, int)
	 */
	static boolean stageJustChanged(int remaining) {
		return stageJustChanged(remaining, Integer.MAX_VALUE);
	}

	/**
	 * 층이 방금 바뀌었는가.
	 *
	 * <p>{@link TrialWarning#sound} 는 부를 때마다 울리므로 호출자가 직전 층과 비교해야 한다.
	 * 남은 틱은 1씩 줄어드니 직전 틱의 층은 {@code remaining + 1} 로 구하면 된다 — 지난 층을
	 * 따로 저장하지 않아도 되고, 저장하지 않으니 어긋날 일도 없다.
	 *
	 * <h2>⚠ 예고가 ≤50틱이면 그 비교만으로는 한 층이 통째로 빠진다</h2>
	 *
	 * <p>{@link TrialWarning#stageFor} 는 남은 틱을 100 / 50 / 14 로 가른다. 예고가 30틱인
	 * 위험(「종말의 비」의 {@code warnTicks}, 「착지 충격」에서 중앙 가까이 선 사람)은
	 * <b>처음부터 MARK 구간(≤50) 안에서 시작</b>하므로 {@code stageFor(remaining)} 과
	 * {@code stageFor(remaining + 1)} 이 영영 같다. 그러면 예고가 IMMINENT 로 넘어갈 때까지
	 * 소리가 한 번도 안 나가고, 중앙에 선 사람은 처음부터 IMMINENT 라 <b>아예 한 번도 못 듣는다.</b>
	 *
	 * <p>고칠 자리를 여기로 잡은 이유. 전에는 {@code TrialEndRain} 과 {@code TrialLandingShock}
	 * 이 <b>각자 따로</b> 「출발 틱이면 무조건 울린다」를 적어 막고 있었다. 예고가 짧은 카드가
	 * 새로 나올 때마다 같은 줄을 다시 적어야 하고, 한 번 빠뜨리면 그 카드만 조용해진다 —
	 * 소리와 표식 둘뿐인 신호에서 한 갈래를 잃는 것이라 로그에도 시험에도 안 남는다.
	 *
	 * @param remaining 발동까지 남은 틱
	 * @param lead      이 예고가 <b>시작될 때의</b> 남은 틱. {@code remaining} 이 여기에
	 *                  닿아 있는 틱이 예고의 첫 틱이고, 그 틱은 직전 층이 아예 없으므로
	 *                  「바뀌었다」로 본다
	 */
	static boolean stageJustChanged(int remaining, int lead) {
		TrialWarning.Stage now = TrialWarning.stageFor(remaining);
		if (now == null) {
			return false;
		}
		if (remaining >= lead) {
			return true;
		}
		return now != TrialWarning.stageFor(remaining + 1);
	}

	/**
	 * 실제로 뽑을 수.
	 *
	 * <p>인원보다 많이 적힌 카드를 인원 넷짜리 팀이 받을 수 있다. 그때 넷을 다 노리는 것은 맞지만
	 * 없는 다섯째를 뽑으려다 터지면 안 된다.
	 */
	static int targetCount(int requested, int available) {
		if (requested <= 0 || available <= 0) {
			return 0;
		}
		return Math.min(requested, available);
	}

	/** 겹치지 않게 뽑은 자리 번호들. 같은 사람을 두 번 노리면 그 주기가 한 발로 줄어든다. */
	static List<Integer> pickIndexes(int count, int size, RandomSource random) {
		int wanted = targetCount(count, size);
		List<Integer> picked = new ArrayList<>();
		if (wanted <= 0) {
			return picked;
		}
		List<Integer> pool = new ArrayList<>();
		for (int index = 0; index < size; index++) {
			pool.add(index);
		}
		while (picked.size() < wanted && !pool.isEmpty()) {
			picked.add(pool.remove(random.nextInt(pool.size())));
		}
		return picked;
	}

	/**
	 * 중앙에서 잰 아레나 안의 한 점. {@code y} 는 0 이고 지면 높이는 호출자가 찾는다.
	 *
	 * <p>거리를 제곱근으로 펴는 이유는 원의 넓이가 반지름의 제곱에 비례하기 때문이다. 그냥 곱하면
	 * 지점이 중앙에 몰려 가장자리가 안전지대가 된다.
	 *
	 * @param angleRoll    0~1 의 굴림. 각도가 된다
	 * @param distanceRoll 0~1 의 굴림. 중앙에서의 거리가 된다
	 */
	static Vec3 arenaOffset(double angleRoll, double distanceRoll, double radius) {
		double angle = clamped(angleRoll) * Math.PI * 2.0;
		double distance = Math.sqrt(clamped(distanceRoll)) * Math.max(0.0, radius);
		return new Vec3(Math.cos(angle) * distance, 0.0, Math.sin(angle) * distance);
	}

	private static double clamped(double roll) {
		if (!(roll > 0.0)) {
			return 0.0;
		}
		return Math.min(1.0, roll);
	}

	/**
	 * 바닥 고리 안에 서 있는가.
	 *
	 * <p>{@link AABB#inflate} 는 정육면체를 만든다. 반경 2 짜리 고리라면 모서리가 2.8 칸이라
	 * <b>표식 밖에 서 있는데 맞는다.</b> 「같은 표식은 언제나 같은 결과」가 이 전투가 플레이어와
	 * 맺은 약속이므로, 상자로 후보를 추린 뒤 고리로 다시 거른다.
	 *
	 * <p>세로는 보지 않는다. 표식은 바닥에 그려지는데 「고리 위에 떠 있었으니 안 맞는다」가 되면
	 * 표식이 거짓말한 것이 된다 — 띄우는 카드와 겹치면 곧바로 드러난다.
	 */
	static boolean insideMark(Vec3 position, Vec3 center, double radius) {
		double dx = position.x - center.x;
		double dz = position.z - center.z;
		return dx * dx + dz * dz <= radius * radius;
	}

	/**
	 * 고리 둘의 중심이 이보다 가까우면 겹친다.
	 *
	 * <h2>이 함수가 「즉사 메커닉 0개」를 지키는 자리다</h2>
	 *
	 * <p>두 원이 한 점도 공유하지 않으려면 중심 거리가 <b>두 반경의 합</b>보다 커야 한다. 경계에
	 * 정확히 닿는 경우({@code 거리 == 합})도 겹침으로 본다 — {@link #insideMark} 가 경계를
	 * 「안」으로 보므로 그 접점에 선 사람은 두 발을 다 맞는다.
	 *
	 * <p>반경 둘을 따로 받는 것은 <b>카드마다 고리 크기가 다르기 때문</b>이다. 「낙뢰」는 3,
	 * 「종말의 비」는 2.5 다. 한쪽 반경의 두 배로 재면 큰 쪽 기준일 때 필요 이상으로 빡빡하고
	 * 작은 쪽 기준일 때 <b>겹친 것을 통과시킨다.</b>
	 *
	 * <p>겹침이 얼마나 흔한지: 반경 40 아레나에 반경 3 짜리 지점을 그냥 무작위로 놓으면
	 * <b>겹침 구역이 하나라도 생길 확률이 두 곳이면 2.1%, 열 곳이면 62.8%</b>다(세 겹까지 생기는
	 * 판도 2.9%). 이 규칙을 넣으면 0% 가 된다.
	 *
	 * <p><b>카드 값이 아니라 여기에 건 이유.</b> 값을 고치는 사람은 피해와 개수만 본다. 규칙을
	 * 카드마다 적어 두면 다음에 개수를 늘리는 사람이 그것을 빠뜨리고, 그때는 아무도 안 죽어 보다가
	 * 어느 판에서 한 번 전멸한다.
	 */
	static double spotMinGap(double first, double second) {
		return Math.max(0.0, first) + Math.max(0.0, second);
	}

	/** 같은 반경 둘일 때. 반경의 두 배다. */
	static double spotMinGap(double radius) {
		return spotMinGap(radius, radius);
	}

	/**
	 * 이 후보가 살아 있는 지점 전부에서 충분히 멀리 있는가.
	 *
	 * <p>보는 것은 자기 카드의 지점이 아니라 <b>{@link #LIVE_SPOTS} 전체</b>다. 시련은 전투가
	 * 끝날 때까지 쌓이므로 서로 다른 카드의 고리가 같은 틱에 함께 살아 있다.
	 *
	 * <p>높이는 보지 않는다. 고리는 바닥에 그려지고 {@link #insideMark} 도 세로를 묻지 않으므로,
	 * y 가 다른 두 고리도 위에서 보면 그대로 겹친다.
	 */
	static boolean clearOfTaken(List<LiveSpot> taken, Vec3 candidate, double radius) {
		for (LiveSpot spot : taken) {
			double gap = spotMinGap(radius, spot.radius());
			double dx = candidate.x - spot.at().x;
			double dz = candidate.z - spot.at().z;
			if (dx * dx + dz * dz <= gap * gap) {
				return false;
			}
		}
		return true;
	}

	/**
	 * 한 사람이 이 위험에게서 <b>한 틱에</b> 받을 수 있는 가장 큰 피해.
	 *
	 * <h2>값을 올리는 사람이 여기서 멈춘다</h2>
	 *
	 * <p>「즉사 메커닉 0개」가 이 전투의 설계 원칙인데, 카드에는 피해와 개수가 <b>따로</b> 적힌다.
	 * 개수를 보지 않고 피해만 올리거나, 피해를 보지 않고 개수만 올리면 곱이 팀 공유 체력 20 을
	 * 넘는다. 그 곱을 카드 값에서 직접 계산해 두면 {@code TrialRisksTest} 가 붙잡을 수 있다.
	 *
	 * <p>겹칠 수 있는 개수는 노리는 법에 따라 다르다.
	 *
	 * <ul>
	 *   <li>{@link TrialCatalog.Risk.Aim#RANDOM_SPOT} — <b>1</b>. {@link #spotMinGap} 이
	 *       겹침 구역을 없애므로 어느 자리에 서 있어도 고리 하나에만 든다. <b>그 규칙을 지우면 이
	 *       숫자가 거짓이 되고, 그 순간 낙뢰는 즉사 카드다</b></li>
	 *   <li>{@link TrialCatalog.Risk.Aim#TRAIL} — <b>{@code count}</b>. 사람마다 따로 뽑은
	 *       발자국이라 자리가 서로 가까울 수 있다. 둘이 나란히 서 있었으면 그 자리에 남은 사람은
	 *       두 발을 다 맞는다</li>
	 * </ul>
	 *
	 * <p>{@code default} 를 넣지 말 것. 피해를 주는 위험 타입을 새로 만들면서 여기에 「가장 나쁜
	 * 경우」를 적지 않으면 빌드가 깨져야 한다 — 적지 않은 타입은 시험이 못 본다.
	 */
	static float worstCaseTickDamage(TrialCatalog.Risk risk) {
		return switch (risk) {
			case TrialCatalog.Risk.DelayedStrike strike -> hits(strike.damage(), switch (strike.aim()) {
				case RANDOM_SPOT -> 1;
				case TRAIL -> strike.count();
			});
			// 조준점은 사람마다 따로 얼어붙지만 두 사람이 나란히 서 있었으면 그 자리에 남은
			// 사람이 두 발을 다 맞는다. 궤적끼리는 최소 간격이 없다.
			case TrialCatalog.Risk.TracedProjectile shot -> hits(shot.damage(), shot.count());
			// 구체는 한 번에 한 발만 날고 표적 한 사람만 때린다 — TrialDragonFocus 의 비행 시간이
			// 발사 간격을 넘지 않아 두 발이 같은 틱에 닿지 않고, 착탄도 반경 안 모두가 아니라
			// 표적에게만 묻는다(공유 체력에서 범위 피해는 팀원별로 합산된다).
			case TrialCatalog.Risk.DragonFocus focus -> hits(focus.damage(), 1);
			// ⚠ 종말의 비만 이 목록 밖이다. 전에는 reserveSpots 를 지나 「한 사람은 한 발」이라
			// 1 이었는데, 사람이 「서로 겹쳐도 되니까 내가 말한 숫자로」라고 정해 고리끼리 겹친다.
			// 그래서 이 숫자는 증명이 아니라 실측이고, 값도 근거도 TrialEndRain 이 들고 있다 —
			// 겹침을 되살리려면 그 파일부터 볼 것. 이 카드가 「즉사 메커닉 0개」의 유일한 예외다.
			case TrialCatalog.Risk.EndRain rain ->
					hits(rain.damage(), TrialEndRain.WORST_CASE_OVERLAP);
			// 고리 하나가 중앙에서 한 번 지나간다. 같은 틱에 두 번 닿는 자리가 없다.
			case TrialCatalog.Risk.LandingShock shock -> hits(shock.damage(), 1);
			// 소용돌이 둘이 서로 가까워지는 순간이 있으므로 둘 다 닿는 자리를 셈에 넣는다.
			case TrialCatalog.Risk.EnderStorm storm -> hits(storm.damage(), storm.count());
			// 빔은 한 사람만 물고 초에 한 번 들어간다. 한 틱에 올 수 있는 가장 큰 값이 그 몫이다.
			case TrialCatalog.Risk.CrystalOvercharge overcharge ->
					hits(overcharge.damagePerSecond(), 1);
			// 아래는 전부 우리가 주는 피해가 없다. 시간을 빼앗거나 발을 묶거나 판을 바꾼다.
			case TrialCatalog.Risk.CrystalGuard ignored -> 0.0F;
			case TrialCatalog.Risk.CrystalRevive ignored -> 0.0F;
			case TrialCatalog.Risk.EnderPulse ignored -> 0.0F;
			case TrialCatalog.Risk.CrystalLink ignored -> 0.0F;
			case TrialCatalog.Risk.DryWorld ignored -> 0.0F;
			// 엔더맨은 바닐라 값으로 때린다. 우리가 적은 피해가 없으므로 여기서 셀 것도 없다.
			case TrialCatalog.Risk.NightHost ignored -> 0.0F;
			case TrialCatalog.Risk.HotbarLock ignored -> 0.0F;
		};
	}

	/** 피해와 겹칠 수 있는 개수의 곱. 어느 쪽이든 0 이하면 아무 일도 없다. */
	private static float hits(float damage, int overlapping) {
		if (damage <= 0.0F || overlapping <= 0) {
			return 0.0F;
		}
		return damage * overlapping;
	}

	/**
	 * 이 연출의 바닥 표식 색.
	 *
	 * <h2>왜 카드가 아니라 연출에서 끌어내는가</h2>
	 *
	 * <p>색을 카드마다 적게 하면 「낙뢰인데 빨간 고리」가 나온다 — {@link TrialCatalog.Risk.Impact}
	 * 를 값으로 가른 이유가 바로 <b>연출과 적힌 것이 어긋나서</b>였다. 같은 실수를 색으로 한 번 더
	 * 하지 않으려고 색도 같은 값에서 뽑는다. 카드를 늘리는 사람은 색을 고를 일이 없다.
	 *
	 * <p>{@code default} 를 넣지 말 것. {@code Impact} 를 늘리고 색을 안 붙이면 여기서 빌드가
	 * 깨져야 한다.
	 */
	static int markColor(TrialCatalog.Risk.Impact impact) {
		return switch (impact) {
			case EXPLOSION -> TrialWarning.Colors.DEADLY;
			case LIGHTNING -> TrialWarning.Colors.LIGHTNING;
		};
	}

	/**
	 * 그 높이까지 뜨는 데 필요한 처음 속도.
	 *
	 * <p>공기 저항을 뺀 근사({@code v = √(2gh)})다. 실제로는 저항 때문에 적힌 것보다 조금 낮게
	 * 뜬다. 카드의 위험은 「떠 있는 동안 못 피한다」이지 정확한 높이가 아니므로 이 정도면 된다.
	 */
	static double launchVelocity(double height) {
		return Math.sqrt(2.0 * GRAVITY_PER_TICK * Math.max(0.0, height));
	}

	/**
	 * 띄운 뒤 낙하 피해를 면제할 틱 수. 올라갔다 내려오는 시간에 여유를 더한다.
	 *
	 * <p>짧게 잡으면 착지 직전에 면제가 끊겨 결국 낙하 피해가 들어간다. 길게 잡아 손해 보는 것은
	 * 「스스로 절벽에서 뛰어내렸을 때 한 번 봐 주는 것」뿐이라 넉넉한 쪽으로 기울인다.
	 */
	static int fallGraceTicks(double height) {
		if (!(height > 0.0)) {
			return 0;
		}
		return (int) Math.ceil(2.0 * launchVelocity(height) / GRAVITY_PER_TICK) + FALL_GRACE_MARGIN;
	}
}
