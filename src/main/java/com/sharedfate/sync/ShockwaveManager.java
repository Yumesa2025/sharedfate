package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkChoiceSession;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetEffects;
import com.sharedfate.perk.effect.ShockwaveEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code shockwave} 증강(골드 「파문」)의 실행부. 팀이 뭉쳐 있으면 주기마다 한 번 충격파를 퍼뜨려
 * 주변의 적대적 몹을 바깥으로 밀어낸다.
 *
 * <p>{@link ShockwaveEffect} 는 「얼마나 뭉쳐야, 얼마나 넓게, 얼마마다, 얼마나 세게」만 알고,
 * 지금 뭉쳐 있는지·언제가 그 주기인지·무엇이 반경 안에 있는지는 여기서 정한다.
 *
 * <h2>⚠ 팀당 한 번이다 — 이것이 이 클래스의 핵심이다</h2>
 * <p>이 모드에서 되풀이해 터졌던 버그가 「인원수만큼 곱해지는」 모양이다. 충격파를 팀원마다
 * 터뜨리면 4인 팀에서 <b>같은 몹이 네 번 밀려</b> 세기가 네 배가 되고, 게다가 서로 다른 네
 * 방향으로 밀려 결과가 뒤죽박죽이 된다. 그래서
 *
 * <ul>
 *   <li>몹을 찾는 조회도 <b>한 번</b>이고(팀원마다 훑지 않는다),</li>
 *   <li>몹 하나가 받는 넉백도 <b>한 번</b>이며,</li>
 *   <li>팀이 {@code shockwave} 정의를 둘 이상 가졌어도 {@link #select} 가 <b>정확히 하나</b>만
 *       골라 돌린다.</li>
 * </ul>
 *
 * <p>팀원마다 좁게 훑는 {@link AuraDamageManager} 와 갈리는 지점이다. 저쪽은 팀원이 흩어져 있어도
 * 각자 살기를 뿜지만, 파문은 <b>뭉쳐 있을 때만</b> 터지므로 팀 전체가 이미 한 덩어리다. 상자
 * 하나로 훑어도 넓어지지 않는다.
 *
 * <h2>뭉치지 않으면 팀원 각자 주변에서 작게 터진다</h2>
 * <p>정의에 {@code solo_radius} 가 있고 팀이 {@code distance} 안에 뭉쳐 있지 않으면, 같은 주기
 * 경계에서 접속 중인 팀원 각자 주변의 작은 반경만 민다({@link #soloBursts}). 뭉쳐 있으면 큰
 * 충격파 하나만 터지고 작은 것은 따로 터지지 않는다. 한 주기에 몹은 어느 쪽이든 <b>한 번만</b>
 * 밀린다.
 *
 * <h2>어디를 중심으로 터지는가 — 팀원들의 무게중심이다</h2>
 * <p>중심은 <b>접속 중인 팀원 좌표의 평균</b>이다. 특정 한 명(예: 증강을 고른 사람)을 중심으로
 * 삼지 않는 까닭은 두 가지다.
 *
 * <ul>
 *   <li><b>그 사람이 접속을 안 했거나 죽어 있으면 파문이 통째로 죽는다.</b> 무게중심은 남은
 *       사람만으로도 언제나 구해진다.</li>
 *   <li>파문이 터지는 조건 자체가 「전원이 {@code distance} 안에 뭉쳐 있다」이므로, 무게중심은
 *       반드시 그 무리 한가운데다. 누구를 골라도 몇 칸 차이지만 무게중심은 <b>흔들리지 않는
 *       기준</b>이라 같은 상황에서 늘 같은 자리에서 터진다.</li>
 * </ul>
 *
 * <p>차원이 섞여 있으면 그 회는 건너뛴다. 좌표를 평균 내 봐야 아무 데도 아닌 자리가 나온다.
 * 애초에 {@link TeamProximity#together} 가 차원이 다른 팀을 「벌어져 있다」로 보므로 여기까지
 * 오는 일이 드물지만, 판정과 실행 사이에 1초가 있으므로 다시 확인한다.
 *
 * <h2>주기는 오버월드 게임 시간으로 센다</h2>
 * <p>{@code MinecraftServer#getTickCount()} 는 서버를 켤 때마다 0부터 다시 세므로, 그 값으로
 * 주기를 재면 <b>재시작으로 파문을 앞당길 수 있다.</b> 오버월드 게임 시간은 {@code level.dat} 에
 * 저장돼 이어지고 월드에 하나뿐이라 다른 차원에 있는 팀원도 같은 값을 본다.
 *
 * <p>경계는 <b>게임 시간의 배수</b>다({@link #cycleAt}). {@link com.sharedfate.perk.PerkSupplyDrops}
 * 가 켜진 시점을 따로 기억하는 것과 갈리는데, 그쪽은 주기가 10분이라 「켜고 몇 초 만에 온다」가
 * 문제가 되지만 파문은 10초짜리라 최대 한 주기만 기다리면 된다. 대신 기억할 것이 없어 저장할
 * 것도 없고, 껐다 켜도 경계가 그대로 남는다.
 *
 * <p>그 팀을 <b>처음 보는 틱에는 터뜨리지 않고 지금 회차를 적어 두기만 한다.</b> 증강을 얻자마자
 * 또는 접속하자마자 터지는 것을 막는 자리다. 시간 명령으로 여러 회차를 한꺼번에 건너뛰어도
 * 파문은 한 번이다.
 *
 * <h2>밀어내는 대상</h2>
 * <p>{@link AuraDamageManager#hostile} 이 참인 몹만이다. <b>가려내는 규칙을 여기서 따로 쓰지
 * 않고 그대로 빌려 쓴다</b> — 두 곳이 다른 규칙을 쓰면 살기에는 맞는데 파문에는 안 밀리는 몹이
 * 생긴다. 소·양·닭·마을 주민·길들인 늑대는 {@code Enemy} 가 아니라 걸러지고, 엔더 드래곤도
 * 빠진다. <b>팀원(플레이어)은 애초에 {@code Mob} 이 아니라 조회에 걸리지 않는다.</b>
 *
 * <h2>피해는 주지 않는다</h2>
 * <p>{@code hurtServer} 를 부르지 않는다. 피해를 주면 프리즘 「살기」({@code aura_damage})와 하는
 * 일이 겹친다. {@code knockback} 이 요구하는 {@code DamageSource} 는 26.2 의 서명이 그것을 받기
 * 때문에 넘기는 것일 뿐 피해로 이어지지 않는다 — 자세한 것은 {@link #push} 에 적어 뒀다.
 */
public final class ShockwaveManager {

	/**
	 * 팀마다 마지막으로 본 주기 번호. <b>이번 세션에만 쓰는 값이라 저장하지 않는다.</b>
	 *
	 * <p>저장하지 않는 것이 곧 「켜자마자 터지지 않는다」를 지키는 장치다. 서버를 켜면 비어
	 * 있고, 그 팀을 처음 보는 틱에는 터뜨리지 않고 지금 회차를 적어 두기만 한다
	 * ({@link #advance}).
	 *
	 * @param intervalTicks 그때 쓰던 주기. 이 값이 바뀌면 번호의 뜻이 달라지므로 번호만 다시 잡는다
	 * @param cycle         마지막으로 본 주기 번호
	 */
	private record Schedule(int intervalTicks, long cycle) {
	}

	/** 팀별 회차 기억. 열쇠는 팀 id 다. */
	private static final Map<UUID, Schedule> SCHEDULES = new HashMap<>();

	/** 충격파가 지나간 자리에 그리는 고리의 높이(칸). 발밑보다 조금 위여야 땅에 묻히지 않는다. */
	private static final double RING_HEIGHT = 0.2;

	/** 충격파 소리 크기. 처음엔 1.0 이었는데 너무 커서 절반으로 줄였다. */
	private static final float SOUND_VOLUME = 0.5F;

	/** 작은 충격파 소리가 한 자리에서만 나도록 묶는 거리(칸). 이 안의 팀원은 소리를 건너뛴다. */
	private static final double SOUND_MERGE_DISTANCE = 16.0;

	/** 고리를 그리는 점의 수. 반경에 비례하되 이 범위를 벗어나지 않는다. */
	private static final int MIN_RING_POINTS = 12;
	private static final int MAX_RING_POINTS = 48;

	/** 반경 한 칸당 고리 점 몇 개를 찍는가. */
	private static final double RING_POINTS_PER_BLOCK = 3.0;

	private static boolean warned;

	private ShockwaveManager() {
	}

	// ------------------------------------------------------------------ 주기 계산

	/**
	 * 이 시각이 몇 번째 주기인가. <b>순수 함수다.</b>
	 *
	 * <p>경계는 게임 시간의 배수다 — 10초 주기면 200틱마다다. 기억할 기준점이 없으므로 나중에
	 * 다시 계산해도, 서버를 껐다 켜도 같은 답이 나온다.
	 *
	 * <p>{@code Math.floorDiv} 를 쓴다. {@code /} 는 음수에서 0 쪽으로 자르므로 게임 시간이
	 * 음수로 조작된 월드에서 경계가 한 칸 어긋난다.
	 */
	public static long cycleAt(long time, int intervalTicks) {
		if (intervalTicks <= 0) {
			return 0L;
		}
		return Math.floorDiv(time, intervalTicks);
	}

	// ------------------------------------------------------------------ 후보 고르기

	/**
	 * 후보 중 실제로 돌 것 <b>하나</b>를 고른다. 없으면 null.
	 *
	 * <p>세트 단계는 누적이라 한 팀이 {@code shockwave} 를 여럿 가질 수 있다. 그것을 전부 돌리면
	 * <b>충격파가 여러 번 터진다.</b> {@link com.sharedfate.perk.PerkSupplyDrops#select} 와 같은
	 * 이유로 여기서 정확히 하나로 줄인다.
	 *
	 * <p>고르는 순서는 이렇다.
	 *
	 * <ol>
	 *   <li><b>반경이 넓은 것.</b> 파문에서 눈에 띄게 세지는 축이 반경이다.</li>
	 *   <li>같으면 <b>세기가 센 것</b>.</li>
	 *   <li>그래도 같으면 <b>주기가 짧은 것</b>.</li>
	 *   <li>그래도 같으면 <b>목록에서 나중에 나온 것</b>. {@code PerkSets.activeTiers} 가 단계를
	 *       개수 오름차순으로 주므로 높은 단계가 이긴다.</li>
	 * </ol>
	 *
	 * <p>규칙이 무엇이든 <b>답이 언제나 하나</b>라는 점이 핵심이다.
	 */
	public static @Nullable ShockwaveEffect select(
			@Nullable Collection<? extends PerkEffect> effects) {
		if (effects == null || effects.isEmpty()) {
			return null;
		}
		ShockwaveEffect best = null;
		for (PerkEffect effect : effects) {
			if (!(effect instanceof ShockwaveEffect candidate)) {
				continue;
			}
			if (best == null || beats(candidate, best)) {
				best = candidate;
			}
		}
		return best;
	}

	/** {@code candidate} 가 {@code best} 를 이기는가. 비기면 나중에 온 쪽이 이긴다. */
	private static boolean beats(ShockwaveEffect candidate, ShockwaveEffect best) {
		if (candidate.radius() != best.radius()) {
			return candidate.radius() > best.radius();
		}
		if (candidate.strength() != best.strength()) {
			return candidate.strength() > best.strength();
		}
		return candidate.intervalTicks() <= best.intervalTicks();
	}

	/**
	 * 이 팀에서 후보가 되는 효과 전부.
	 *
	 * <p>보유 증강과 <b>켜진 세트 효과</b>를 함께 펼친다. {@link AuraDamageManager} 가 살기를
	 * 모으는 규칙 그대로다 — 세트 쪽을 빠뜨리면 세트가 아무 일도 안 한다.
	 */
	public static List<PerkEffect> candidatesOf(@Nullable TeamState state) {
		if (state == null || state.ownedPerks.isEmpty()) {
			return List.of();
		}
		List<PerkEffect> candidates = new ArrayList<>();
		for (String perkId : state.ownedPerks) {
			// 풀에서 사라진 id 는 건너뛴다. 증강 정의를 손으로 고칠 수 있는 이상 저장에만 남은
			// id 는 언제든 생긴다.
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof ShockwaveEffect) {
					candidates.add(effect);
				}
			}
		}
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) {
			if (effect instanceof ShockwaveEffect) {
				candidates.add(effect);
			}
		}
		return candidates;
	}

	/** 이 팀에서 지금 실제로 도는 파문. 없으면 null. */
	public static @Nullable ShockwaveEffect activeFor(@Nullable TeamState state) {
		return select(candidatesOf(state));
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 팀마다 주기를 재어 경계를 넘었고 뭉쳐 있으면 충격파를 퍼뜨린다.
	 *
	 * <p>서버 틱 한가운데서 불리므로 어떤 예외도 밖으로 내보내지 않는다. 실제로 몹을 찾는 것은
	 * 경계를 넘은 그 틱뿐이고, 나머지 틱에는 팀 → 후보 → 주기 번호 비교로 끝난다.
	 */
	public static synchronized void tick(@Nullable MinecraftServer server) {
		// 증강 선택 중에는 시간이 멈춰 있고 팀원은 창에 갇혀 있다. 게임 오버 카운트다운 5초도
		// 이미 끝난 회차라 마찬가지다. AuraDamageManager 와 같은 조건이다.
		if (server == null || PerkChoiceSession.isActive() || WorldResetCoordinator.countingDown()) {
			return;
		}
		try {
			long time = server.overworld().getGameTime();
			TeamManager manager = TeamManager.get(server);
			for (ShareTeam team : manager.allTeams()) {
				TeamState state = manager.stateByTeamId(team.teamId());
				if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
					SCHEDULES.remove(team.teamId());
					continue;
				}
				ShockwaveEffect effect = activeFor(state);
				if (effect == null) {
					// 증강이나 세트가 풀렸다. 기억을 지워 두면 다시 얻었을 때 「처음 보는 팀」이
					// 되어 곧바로 터지지 않는다.
					SCHEDULES.remove(team.teamId());
					continue;
				}
				tickTeam(server, team, effect, time);
			}
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/** 서버가 멈출 때 기억을 비운다. 다음 월드로 넘어가지 않게 한다. */
	public static synchronized void reset() {
		SCHEDULES.clear();
		warned = false;
	}

	// ------------------------------------------------------------------ 팀 하나

	/**
	 * 팀 하나의 파문을 한 번 집행한다.
	 *
	 * <p>주기 번호는 <b>뭉쳐 있는지와 상관없이 먼저 옮긴다.</b> 흩어져 있어 건너뛴 회차를
	 * 쌓아 두었다가 모이는 순간 몰아서 터뜨리면, 파문이 「주기마다 한 번」이 아니라 「모이면
	 * 곧바로」가 되어 버린다.
	 */
	private static void tickTeam(MinecraftServer server, ShareTeam team, ShockwaveEffect effect,
			long time) {
		if (!advance(team.teamId(), effect.intervalTicks(), time)) {
			return;
		}
		List<ServerPlayer> online = onlineMembers(server, team);
		if (online.isEmpty()) {
			return;
		}
		// 정의에 적힌 거리를 그대로 넘긴다. 세트 「결속 3」의 배율은 together 안에서 먹는다.
		if (!TeamProximity.together(team.teamId(), effect.distance())) {
			// 흩어져 있다. 작은 충격파가 정의돼 있으면 팀원 각자 주변에서만 터진다.
			// 뭉쳐 있을 때는 이 갈래를 타지 않는다 — 큰 충격파가 그 자리를 대신한다.
			if (effect.hasSolo()) {
				soloBursts(online,
						TeamProximity.scaled(team.teamId(), effect.soloRadius()),
						effect.strength());
			}
			return;
		}
		ServerLevel level = sharedLevel(online);
		if (level == null) {
			// 차원이 섞여 있다. 좌표를 평균 내도 아무 데도 아닌 자리가 나온다.
			return;
		}
		burst(level, center(online), TeamProximity.scaled(team.teamId(), effect.radius()),
				effect.strength());
	}

	/**
	 * 흩어진 팀의 작은 충격파. 접속 중인 팀원 각자를 중심으로 터진다.
	 *
	 * <p>⚠ 팀원 둘 사이에 있는 몹이 <b>두 번 밀리면</b> 세기가 곱해지므로, 몹마다 <b>가장 가까운
	 * 팀원 한 명</b>만 골라 한 번만 민다. 차원이 다르면 서로 닿을 수 없으니 차원별로 따로
	 * 모은다. 반경은 큰 충격파와 똑같이 {@code TeamProximity.scaled} 로 「결속」 배율을 먹인 값이다.
	 *
	 * <p>소리도 한 주기에 겹겹이 커지지 않게, 이미 소리를 낸 자리에서 {@link #SOUND_MERGE_DISTANCE}
	 * 칸 안인 팀원은 건너뛴다(파티클은 각자 그린다).
	 */
	private static void soloBursts(List<ServerPlayer> online, double radius, double strength) {
		Map<Mob, Pusher> nearest = new HashMap<>();
		List<Vec3> sounded = new ArrayList<>();
		double radiusSquared = radius * radius;
		for (ServerPlayer member : online) {
			ServerLevel level = member.level();
			Vec3 center = member.position();
			AABB box = new AABB(center, center).inflate(radius);
			for (Mob mob : level.getEntitiesOfClass(Mob.class, box, AuraDamageManager::hostile)) {
				double distance = mob.distanceToSqr(center);
				if (distance > radiusSquared) {
					continue;
				}
				Pusher known = nearest.get(mob);
				if (known == null || distance < known.distanceSquared()) {
					nearest.put(mob, new Pusher(center, distance));
				}
			}
			boolean sound = true;
			for (Vec3 other : sounded) {
				if (other.distanceToSqr(center) <= SOUND_MERGE_DISTANCE * SOUND_MERGE_DISTANCE) {
					sound = false;
					break;
				}
			}
			if (sound) {
				sounded.add(center);
			}
			showWave(level, center, radius, sound);
		}
		for (Map.Entry<Mob, Pusher> entry : nearest.entrySet()) {
			push(entry.getKey(), entry.getValue().center(), strength);
		}
	}

	/** 몹을 미는 기준점(가장 가까운 팀원의 자리)과 그 거리 제곱. */
	private record Pusher(Vec3 center, double distanceSquared) {
	}

	/**
	 * 주기 번호를 한 걸음 옮기고 <b>이번 틱에 터뜨려야 하는지</b> 답한다.
	 *
	 * <p>답이 무엇이든 <b>기억은 반드시 갱신된다.</b> 그래야 아래에서 무슨 일이 있어도 같은 회에
	 * 두 번 터지지 않는다.
	 *
	 * <p>이 팀을 처음 보거나 주기가 바뀌었으면 <b>터뜨리지 않고 번호만 잡는다.</b> 서버를 켠
	 * 직후나 증강을 막 얻은 순간에 곧바로 터지는 것을 막는 자리다.
	 *
	 * <p>살아 있는 월드가 없어도 답이 정해지는 자리라 공개해 둔다 — 「팀당 한 번」을 지키는
	 * 셈이 여기 있고, 시험으로 붙들어 두지 않으면 조용히 무너진다.
	 */
	public static synchronized boolean advance(UUID teamId, int intervalTicks, long time) {
		long cycle = cycleAt(time, intervalTicks);
		Schedule previous = SCHEDULES.put(teamId, new Schedule(intervalTicks, cycle));
		if (previous == null || previous.intervalTicks() != intervalTicks) {
			return false;
		}
		return cycle != previous.cycle();
	}

	// ------------------------------------------------------------------ 실제 집행

	/**
	 * 충격파 한 번. 반경 안의 적대적 몹을 바깥으로 밀고 파티클과 소리를 낸다.
	 *
	 * <p>조회는 이 한 번뿐이다. 상자는 조회를 좁히는 그물일 뿐이고 실제 판정은 아래의 거리
	 * 비교로 하므로 「반경 8칸」은 정육면체가 아니라 공 모양이다.
	 */
	private static void burst(ServerLevel level, Vec3 center, double radius, double strength) {
		AABB box = new AABB(center, center).inflate(radius);
		double radiusSquared = radius * radius;
		for (Mob mob : level.getEntitiesOfClass(Mob.class, box, AuraDamageManager::hostile)) {
			if (mob.distanceToSqr(center) > radiusSquared) {
				continue;
			}
			push(mob, center, strength);
		}
		showWave(level, center, radius, true);
	}

	/**
	 * 몹 하나를 중심에서 바깥으로 민다. <b>피해는 주지 않는다.</b>
	 *
	 * <p>26.2 의 {@code LivingEntity.knockback(세기, dx, dz, 피해원, 피해량)} 은 바이트코드상
	 * {@code normalize(dx,0,dz).scale(세기)} 를 만들어 이동량에서 <b>빼므로</b>, {@code dx}·{@code dz}
	 * 에는 <b>몹에서 중심을 향하는</b> 방향을 넣어야 몹이 바깥으로 밀린다. 부호를 뒤집으면 몹이
	 * 전부 팀원 발밑으로 빨려든다.
	 *
	 * <p>넘기는 {@code DamageSource} 는 <b>피해를 주지 않는다.</b> {@code knockback} 은 이동량만
	 * 건드리고 {@code hurtServer} 를 부르지 않는다. 그런데도 넘기는 것은 26.2 의 서명이 그것을
	 * 받고, {@code SulfurCube} 같은 재정의가 <b>null 검사 없이</b> {@code getEntity()} 를 부르기
	 * 때문이다 — null 을 넘기면 그 몹 앞에서 터진다. {@code generic()} 은 가해자가 없는 출처라
	 * 어떤 몹도 이것을 「누가 나를 때렸다」로 읽지 않는다. 마지막 인자 0.0F 는 「피해량 0」이라는
	 * 뜻으로, 파문이 하는 일 그대로다.
	 *
	 * <p>몹이 중심에 정확히 겹쳐 있으면 방향이 0 이 되는데, 그때는 바닐라가 무작위 방향으로
	 * 흩어 준다. 여기서 따로 손대지 않는다.
	 */
	private static void push(Mob mob, Vec3 center, double strength) {
		DamageSource source = mob.damageSources().generic();
		mob.knockback(strength, center.x - mob.getX(), center.z - mob.getZ(), source, 0.0F);
	}

	/**
	 * 퍼져 나가는 것이 눈과 귀에 보이게 한다.
	 *
	 * <p>중심에 돌풍을 한 번 터뜨리고 반경 자리에 고리를 그린다. 고리 점의 수는 반경에 비례하되
	 * 상한을 둔다 — 점 하나에 패킷 한 장이라 반경이 커질수록 그대로 늘어난다.
	 */
	private static void showWave(ServerLevel level, Vec3 center, double radius, boolean sound) {
		level.sendParticles(ParticleTypes.GUST_EMITTER_LARGE,
				center.x, center.y + RING_HEIGHT, center.z, 1, 0.0, 0.0, 0.0, 0.0);
		int points = ringPoints(radius);
		double step = Math.PI * 2.0 / points;
		for (int index = 0; index < points; index++) {
			double angle = step * index;
			level.sendParticles(ParticleTypes.CLOUD,
					center.x + Math.cos(angle) * radius,
					center.y + RING_HEIGHT,
					center.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
		if (sound) {
			level.playSound(null, center.x, center.y, center.z,
					SoundEvents.BREEZE_WIND_CHARGE_BURST.value(), SoundSource.PLAYERS,
					SOUND_VOLUME, 0.8F);
		}
	}

	/** 이 반경에 고리를 그릴 점의 수. */
	private static int ringPoints(double radius) {
		int wanted = (int) Math.round(radius * RING_POINTS_PER_BLOCK);
		return Math.max(MIN_RING_POINTS, Math.min(MAX_RING_POINTS, wanted));
	}

	// ------------------------------------------------------------------ 도우미

	/**
	 * 충격파의 중심 — 접속 중인 팀원 좌표의 평균이다.
	 *
	 * <p>부르는 쪽이 이미 같은 차원인지 확인했으므로 여기서는 좌표만 더한다.
	 */
	private static Vec3 center(List<ServerPlayer> online) {
		double x = 0.0;
		double y = 0.0;
		double z = 0.0;
		for (ServerPlayer member : online) {
			x += member.getX();
			y += member.getY();
			z += member.getZ();
		}
		int count = online.size();
		return new Vec3(x / count, y / count, z / count);
	}

	/** 전원이 같은 차원에 있으면 그 차원, 섞여 있으면 null. */
	private static @Nullable ServerLevel sharedLevel(List<ServerPlayer> online) {
		ServerLevel level = online.get(0).level();
		for (ServerPlayer member : online) {
			if (member.level() != level) {
				return null;
			}
		}
		return level;
	}

	/** 지금 파문을 뿜을 수 있는 팀원. {@link AuraDamageManager} 와 같은 기준이다. */
	private static List<ServerPlayer> onlineMembers(MinecraftServer server, ShareTeam team) {
		List<ServerPlayer> result = new ArrayList<>();
		for (UUID memberId : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(memberId);
			if (player != null && !player.isRemoved() && !player.isDeadOrDying()
					&& !player.isSpectator()) {
				result.add(player);
			}
		}
		return result;
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"파문 증강을 처리하지 못해 이번 틱은 건너뜁니다. 이 경고는 한 번만 남습니다.", error);
	}
}
