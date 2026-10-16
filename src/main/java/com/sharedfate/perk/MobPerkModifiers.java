package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.config.SharedFateConfig;
import com.sharedfate.perk.effect.DamageIncreaseReliefEffect;
import com.sharedfate.perk.effect.MobActionSpeedEffect;
import com.sharedfate.perk.effect.MobDamageEffect;
import com.sharedfate.perk.effect.MobHealthEffect;
import com.sharedfate.perk.effect.MobSpawnRateEffect;
import com.sharedfate.perk.effect.MobSpeedEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 몹에게 걸리는 증강 효과({@code mob_health}, {@code mob_damage}, {@code mob_speed})의 실행부.
 *
 * <ul>
 *   <li>{@code mob_health} 는 몹이 월드에 올라올 때({@code ServerEntityEvents.ENTITY_LOAD})
 *       최대 체력 속성에 임시 수정자를 붙인다. 임시 수정자는 저장되지 않으므로 서버를 껐다
 *       켜면 저절로 사라지고, 다시 올라올 때 그때의 증강 구성으로 새로 계산된다.</li>
 *   <li>{@code mob_speed} 는 같은 자리에서 같은 방식으로 이동 속도 속성에 붙인다. 수정자
 *       식별자만 {@link #SPEED_MODIFIER_ID} 로 다르다. 체력과 달리 현재 값을 정리할 일이
 *       없어({@code settleHealth} 같은 뒷정리) 붙였다 떼는 것으로 끝난다.</li>
 *   <li>{@code mob_damage} 는 {@link PerkDamage} 가 피해 계산 시점에 배율만 조회해 간다.
 *       몹에게 아무것도 붙이지 않으므로 정리할 상태가 없다.</li>
 *   <li>{@code mob_spawn_rate} 는 몹 한 마리가 아니라 <b>월드의 스폰 경로</b>에 걸린다.
 *       {@code NaturalSpawnerRateMixin} 이 청크마다 {@link #spawnRateMultiplier} 를 물어
 *       적대 몹 스폰 시도를 몇 번 돌릴지 정한다. 붙였다 뗄 상태가 없으므로 증강을 잃으면
 *       그 다음 틱부터 바닐라 그대로다.</li>
 *   <li>{@code mob_action_speed} 는 속성을 하나도 건드리지 않는다. <b>서버가 그 몹에게 틱을
 *       주는 횟수</b>를 바꿔 이동·공격 간격·크리퍼 부풀기·활 쏘기를 한꺼번에 당기거나 늦춘다.
 *       여기서는 몹 종류별 배율을 미리 계산해 두기만 하고({@link #actionSpeedMultiplier}),
 *       어느 틱에 한 번 더 돌릴지 고르는 일은
 *       {@code com.sharedfate.sync.MobTickRate} 가 맡는다. 역시 붙였다 뗄 상태가 없다.</li>
 * </ul>
 *
 * <h2>어느 팀의 증강을 몹에게 적용하는가</h2>
 * <p>몹은 어느 팀에도 속하지 않는다. 한 팀 안에서는 여러 증강의 배율을 곱하고, 팀이 여럿이면
 * 그중 <b>1.0 에서 가장 멀리 떨어진 배율 하나</b>만 고른다 (같으면 작은 쪽). 팀 목록을 훑는
 * 순서에 결과가 좌우되지 않고, 팀 수가 늘어도 배율이 누적되지 않으며, 어느 팀이 실제로 고른
 * 증강이 다른 팀 때문에 조용히 무효가 되지도 않는다. {@code singleTeamOnly} 를 켜 둔 서버는
 * 팀이 하나뿐이라 "보유한 팀이 있으면 그 배율" 과 같은 결과가 된다.
 *
 * <h2>증강 구성이 바뀌었을 때</h2>
 * <p>1초에 한 번 팀들의 보유 증강을 훑어 지문(signature)을 만든다. 지문이 달라졌을 때만
 * 캐시를 비우고 이미 올라와 있는 몹 전체를 다시 계산한다. 회차 리셋으로 증강을 잃으면 배율이
 * 1.0 이 되고, 그 순간 살아 있던 몹에 붙어 있던 수정자도 이 훑기에서 걷힌다.
 */
public final class MobPerkModifiers {
	/** 최대 체력 수정자의 식별자. 여러 증강의 배율을 하나로 합쳐 붙이므로 고정값 하나면 된다. */
	public static final Identifier HEALTH_MODIFIER_ID = SharedFateMod.id("perk/mob_health");

	/**
	 * 이동 속도 수정자의 식별자.
	 *
	 * <p>체력({@link #HEALTH_MODIFIER_ID})·난이도 상승({@code difficulty/mob_health})과 반드시
	 * 달라야 한다. 같은 식별자를 쓰면 나중에 붙는 쪽이 앞의 것을 조용히 덮어쓴다.
	 */
	public static final Identifier SPEED_MODIFIER_ID = SharedFateMod.id("perk/mob_speed");

	/** 증강 구성이 바뀌었는지 보는 주기. 매 틱 볼 필요가 없다. */
	private static final int CHECK_INTERVAL_TICKS = 20;
	/** 최대 체력이 0 이 되면 몹이 존재할 수 없으므로 하한을 둔다. */
	static final double MIN_HEALTH_MULTIPLIER = 0.01;
	/** 합쳐진 배율의 상한. 무한대가 속성이나 피해 계산으로 새어나가지 않게 막는다. */
	static final double MAX_MULTIPLIER = 1.0e4;
	/**
	 * 합쳐진 스폰율 배율의 상한.
	 *
	 * <p>다른 배율과 달리 크게 잡을 수 없다. 이 값이 곧 <b>매 틱 스폰 경로를 다시 도는
	 * 횟수</b>라 서버 부담에 그대로 곱해지기 때문이다. 4배면 이미 「몹이 끝없이 나오는 판」이다.
	 */
	static final double MAX_SPAWN_RATE_MULTIPLIER = 4.0;

	/**
	 * 몹 종류별로 계산해 둔 배율. 피해 계산은 초당 수십 번 도는 자리라 매번 팀을 훑을 수 없다.
	 * 증강 구성이 바뀌면 통째로 비운다.
	 */
	private static final Map<EntityType<?>, Double> HEALTH_CACHE = new ConcurrentHashMap<>();
	private static final Map<EntityType<?>, Double> DAMAGE_CACHE = new ConcurrentHashMap<>();
	private static final Map<EntityType<?>, Double> SPEED_CACHE = new ConcurrentHashMap<>();
	private static final Map<EntityType<?>, Double> ACTION_SPEED_CACHE = new ConcurrentHashMap<>();

	/**
	 * 어느 몫의 배율을 묻는지.
	 *
	 * <p>{@link #SPEED} 는 이동 속도 <b>속성</b>이고 {@link #ACTION_SPEED} 는 <b>틱 수</b>다.
	 * 이름이 비슷하지만 붙는 자리가 전혀 다르다.
	 */
	enum Kind {
		HEALTH,
		DAMAGE,
		SPEED,
		ACTION_SPEED
	}

	/**
	 * 계산해 둔 적대 몹 스폰율 배율. 몹 종류와 무관한 값 하나뿐이다.
	 *
	 * <p>{@code spawnRateKnown} 이 켜져 있을 때만 {@code spawnRateValue} 를 믿는다. 증강
	 * 구성이 바뀌면 {@link #tick} 이 이 깃발을 내려 다음 물음에서 다시 계산하게 한다.
	 * 스폰 경로는 매 틱 청크마다 도는 자리라 여기서 팀을 훑을 수 없다.
	 */
	private static volatile double spawnRateValue = 1.0;
	private static volatile boolean spawnRateKnown;

	/**
	 * 행동 속도를 건드리는 증강을 가진 팀이 하나라도 있는가.
	 *
	 * <p><b>매 틱, 월드의 모든 엔티티가 지나는 자리의 첫 줄이 읽는 깃발이다.</b> 아무도 이
	 * 효과를 갖지 않은 서버에서 {@link #actionSpeedMultiplier} 의 비용은 {@code instanceof}
	 * 한 번과 {@code volatile boolean} 한 번 읽기가 전부다. 종류별 배율 표를 뒤지는 일은 이 줄
	 * 아래에 있다.
	 *
	 * <p>{@code actionSpeedKnown} 이 켜져 있을 때만 값을 믿는다. 증강 구성이 바뀌면
	 * {@link #tick} 이 깃발을 내려 다음 물음에서 다시 세게 한다. 스폰율과 같은 방식이다.
	 */
	private static volatile boolean actionSpeedPresent;
	private static volatile boolean actionSpeedKnown;

	private static int tickCounter;
	private static int signature;
	private static boolean signatureKnown;
	private static volatile boolean warned;

	private MobPerkModifiers() {
	}

	// ------------------------------------------------------------------ 등록 지점

	/**
	 * 몹이 월드에 올라올 때 최대 체력·이동 속도 수정자를 맞춘다.
	 *
	 * <p>새로 스폰될 때든 청크가 다시 읽힐 때든 같은 자리를 지나므로, 여기 한 곳만 잡으면
	 * mixin 없이 모든 몹을 덮는다.
	 */
	public static void onEntityLoad(Entity entity, ServerLevel level) {
		applyHealth(entity);
		applySpeed(entity);
	}

	/** 증강 구성이 바뀌었으면 캐시를 비우고 이미 올라와 있는 몹을 다시 계산한다. */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (++tickCounter < CHECK_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;

		int current;
		try {
			current = computeSignature(server);
		} catch (RuntimeException error) {
			warnOnce(error);
			return;
		}
		if (signatureKnown && current == signature) {
			return;
		}
		signature = current;
		signatureKnown = true;
		HEALTH_CACHE.clear();
		DAMAGE_CACHE.clear();
		SPEED_CACHE.clear();
		ACTION_SPEED_CACHE.clear();
		spawnRateKnown = false;
		actionSpeedKnown = false;
		sweep(server);
	}

	/**
	 * 다음 틱을 기다리지 않고 곧바로 다시 계산한다.
	 *
	 * <p>증강을 고른 직후처럼 구성이 바뀐 시점을 이미 아는 곳에서 부른다.
	 */
	public static void invalidateNow(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		tickCounter = CHECK_INTERVAL_TICKS - 1;
		signatureKnown = false;
		tick(server);
	}

	/** 서버가 멈출 때 캐시와 지문을 비운다. */
	public static void reset() {
		HEALTH_CACHE.clear();
		DAMAGE_CACHE.clear();
		SPEED_CACHE.clear();
		ACTION_SPEED_CACHE.clear();
		spawnRateValue = 1.0;
		spawnRateKnown = false;
		actionSpeedPresent = false;
		actionSpeedKnown = false;
		tickCounter = 0;
		signature = 0;
		signatureKnown = false;
	}

	// ------------------------------------------------------------------ 조회

	/**
	 * 이 몹이 주는 피해에 곱할 배율. 몹이 아니면(플레이어 포함) 항상 1.0 이다.
	 *
	 * <p>{@link PerkDamage} 가 피해 계산 시점에 부른다.
	 */
	public static double damageMultiplier(@Nullable Entity attacker) {
		if (!(attacker instanceof Mob mob)) {
			return 1.0;
		}
		return lookup(DAMAGE_CACHE, mob, Kind.DAMAGE);
	}

	/** 이 몹의 최대 체력에 곱할 배율. */
	public static double healthMultiplier(Mob mob) {
		return lookup(HEALTH_CACHE, mob, Kind.HEALTH);
	}

	/** 이 몹의 이동 속도에 곱할 배율. */
	public static double speedMultiplier(Mob mob) {
		return lookup(SPEED_CACHE, mob, Kind.SPEED);
	}

	/**
	 * 이 엔티티가 받을 <b>틱 수</b>에 곱할 배율. 1.2 면 몹이 하는 모든 것이 20% 빨라진다.
	 *
	 * <p>{@code com.sharedfate.sync.MobTickRate} 가 <b>모든 엔티티마다 매 틱</b> 부른다. 그래서
	 * 순서가 중요하다.
	 *
	 * <ol>
	 *   <li>{@code Mob} 이 아니면 곧바로 끝낸다. 화살·아이템·경험치 구슬·플레이어가 여기서
	 *       걸러지고, 월드 엔티티의 큰 몫이 이쪽이다.</li>
	 *   <li>{@link #actionSpeedPresent} 깃발을 본다. 이 효과를 가진 팀이 없으면 여기서 끝이다.</li>
	 *   <li>그다음에야 몹 종류별 배율 표를 본다.</li>
	 * </ol>
	 *
	 * <p>이동 속도 속성({@link #speedMultiplier})과는 아무 관계가 없다. 그쪽은 걸음의 <b>폭</b>을,
	 * 이쪽은 걸음의 <b>수</b>를 바꾼다. 둘을 같은 배율로 함께 걸면 이동만 곱절로 빨라진다.
	 */
	public static double actionSpeedMultiplier(@Nullable Entity entity) {
		if (!(entity instanceof Mob mob)) {
			return 1.0;
		}
		if (!actionSpeedKnown) {
			MinecraftServer server = mob.level().getServer();
			if (server == null) {
				return 1.0;
			}
			try {
				// 값을 먼저 넣고 깃발을 나중에 올린다. 다른 스레드가 반쪽짜리를 보지 않게.
				actionSpeedPresent = anyTeamHasActionSpeed(server);
				actionSpeedKnown = true;
			} catch (RuntimeException error) {
				warnOnce(error);
				return 1.0;
			}
		}
		if (!actionSpeedPresent) {
			return 1.0;
		}
		return lookup(ACTION_SPEED_CACHE, mob, Kind.ACTION_SPEED);
	}

	/**
	 * {@code mob_action_speed} 를 가진 팀이 하나라도 있는지 센다. 증강 구성이 바뀐 뒤 딱 한 번
	 * 돈다.
	 *
	 * <p>보는 범위가 {@link #teamMultiplier} 와 <b>글자 그대로 같아야 한다</b>. 여기서 세는 곳과
	 * 배율을 곱하는 곳이 어긋나면, 깃발이 내려간 채로 실제 효과만 살아 있어 조용히 무동작이 된다.
	 * 그래서 이쪽도 <b>보유 증강만</b> 본다 — 세트 효과는 체력·공격력·이동 속도와 마찬가지로
	 * 이 계열이 보지 않는다.
	 */
	private static boolean anyTeamHasActionSpeed(MinecraftServer server) {
		TeamManager manager = TeamManager.get(server);
		for (ShareTeam team : manager.allTeams()) {
			TeamState state = manager.stateByTeamId(team.teamId());
			if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
				continue;
			}
			for (String perkId : state.ownedPerks) {
				Perk perk = PerkRegistry.byId(perkId).orElse(null);
				if (perk == null) {
					continue;
				}
				for (PerkEffect effect : perk.effects()) {
					if (effect instanceof MobActionSpeedEffect) {
						return true;
					}
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 적대 몹 스폰율

	/**
	 * 적대 몹 자연 스폰 시도 횟수에 곱할 배율. 걸린 증강이 없거나 설정이 꺼져 있으면 1.0.
	 *
	 * <p><b>매 틱, 스폰이 도는 청크마다 불린다.</b> 그래서 팀을 훑는 일은 증강 구성이 바뀐
	 * 뒤 첫 물음에서 딱 한 번만 하고, 나머지는 밭 두 개를 읽는 것으로 끝난다. 설정이 꺼져
	 * 있으면 그 검사에서 곧바로 돌아간다.
	 */
	public static double spawnRateMultiplier(@Nullable MinecraftServer server) {
		if (!spawnRatePerksEnabled()) {
			return 1.0;
		}
		if (spawnRateKnown) {
			return spawnRateValue;
		}
		if (server == null) {
			return 1.0;
		}
		try {
			// 팀마다 보유 증강과 켜진 세트를 함께 곱한 뒤, 팀끼리는 1.0 에서 가장 먼 하나를
			// 고른다. 세트를 여기서 함께 넘기지 않으면 화력 4단계의 스폰율이 무동작이 된다.
			double chosen = 1.0;
			TeamManager manager = TeamManager.get(server);
			for (ShareTeam team : manager.allTeams()) {
				TeamState state = manager.stateByTeamId(team.teamId());
				if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
					continue;
				}
				chosen = stronger(chosen,
						spawnRateForTeam(state.ownedPerks, PerkSetEffects.activeEffectsOf(state)));
			}
			double value = sanitizeSpawnRate(chosen);
			// 값을 먼저 넣고 깃발을 나중에 올린다. 다른 스레드가 반쪽짜리를 보지 않게.
			spawnRateValue = value;
			spawnRateKnown = true;
			return value;
		} catch (RuntimeException error) {
			warnOnce(error);
			return 1.0;
		}
	}

	/**
	 * 설정에서 이 효과를 켜 두었는지. 설정을 아직 읽지 않았으면(시험·초기화 전) 켜진 것으로 본다.
	 *
	 * <p>조회 쪽({@link #spawnRateMultiplier})과 합성 쪽({@link #spawnRateOf}) 두 곳에서 본다.
	 */
	static boolean spawnRatePerksEnabled() {
		SharedFateConfig config = SharedFateMod.config;
		return config == null || config.mobSpawnRatePerks;
	}

	/**
	 * 팀별 보유 증강 목록에서 적대 몹 스폰율 배율을 합성한다.
	 *
	 * <p>한 팀 안에서는 곱하고, 팀이 여럿이면 {@link #stronger} 로 <b>1.0 에서 가장 멀리
	 * 떨어진 하나</b>만 고른다. 체력·공격력과 똑같은 규칙이다.
	 *
	 * <p>서버 상태를 보지 않는 순수 계산이다.
	 */
	static double spawnRateOf(Iterable<? extends Collection<String>> teamOwnedPerks) {
		if (!spawnRatePerksEnabled()) {
			return 1.0;
		}
		double chosen = 1.0;
		for (Collection<String> owned : teamOwnedPerks) {
			chosen = stronger(chosen, spawnRateForTeam(owned, List.of()));
		}
		return sanitizeSpawnRate(chosen);
	}

	/**
	 * 한 팀의 스폰율 배율. <b>보유 증강과 켜진 세트를 함께 곱한다.</b>
	 *
	 * <p>팀끼리 고를 때만 {@link #stronger} 로 1.0 에서 가장 먼 하나를 고른다.
	 *
	 * <p>지금 {@code mob_spawn_rate} 를 쓰는 정의는 <b>세트 「화력 4」 하나뿐</b>이라, 이 줄이
	 * 빠지면 그 효과 타입이 통째로 무동작이 된다.
	 *
	 * @param owned      그 팀의 보유 증강 id
	 * @param setEffects 그 팀에 켜져 있는 세트 효과. 세트를 안 보는 자리는 빈 목록을 넘긴다
	 */
	static double spawnRateForTeam(Collection<String> owned,
			Iterable<? extends PerkEffect> setEffects) {
		double total = 1.0;
		for (String perkId : owned) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			total *= productOfSpawnRates(perk.effects());
		}
		return total * productOfSpawnRates(setEffects);
	}

	/**
	 * 효과 목록에서 스폰율 몫만 골라 모두 곱한다. {@code mob_spawn_rate} 가 아닌 효과는 1.0.
	 *
	 * <p>레지스트리도 팀도 보지 않는 순수 계산이다.
	 */
	static double productOfSpawnRates(Iterable<? extends PerkEffect> effects) {
		double total = 1.0;
		for (PerkEffect effect : effects) {
			if (effect instanceof MobSpawnRateEffect rate) {
				total *= rate.multiplierFor();
			}
		}
		return total;
	}

	/**
	 * 팀별로 구한 스폰율 배율을 하나로 합친다.
	 *
	 * <p>{@link #spawnRateOf} 가 쓰는 규칙과 같다.
	 */
	static double combineSpawnRates(double... teamRates) {
		if (!spawnRatePerksEnabled()) {
			return 1.0;
		}
		double chosen = 1.0;
		for (double rate : teamRates) {
			chosen = stronger(chosen, rate);
		}
		return sanitizeSpawnRate(chosen);
	}

	/**
	 * 스폰율 배율은 0 이 될 수 없다. 이상한 값은 1.0 으로 물러난다.
	 *
	 * <p>0 을 허용하면 적대 몹이 자연스럽게 전혀 생기지 않아 판이 성립하지 않는다. 위쪽은
	 * {@link #MAX_SPAWN_RATE_MULTIPLIER} 로 자른다 — 이 값이 곧 매 틱 스폰 경로를 다시 도는
	 * 횟수라, 커지면 그만큼 서버가 느려진다.
	 */
	static double sanitizeSpawnRate(double value) {
		if (!Double.isFinite(value) || value <= 0.0) {
			return 1.0;
		}
		return Math.min(MAX_SPAWN_RATE_MULTIPLIER, value);
	}

	/**
	 * 배율을 「이번 청크에서 스폰을 몇 번 돌릴까」로 바꾼다.
	 *
	 * <p>바닐라는 청크마다 정확히 한 번 돈다. 배율 1.35 를 「1.35번」 돌 수는 없으므로
	 * <b>정수 부분만큼 돌고 소수 부분은 확률로</b> 돈다. 1.35 면 65% 확률로 1번, 35% 확률로
	 * 2번이라 기댓값이 정확히 1.35 가 된다. 0.5 면 절반은 아예 건너뛴다.
	 *
	 * @param multiplier 합성된 스폰율 배율
	 * @param roll       0 이상 1 미만의 난수
	 * @return 돌 횟수. 0 이면 이번 청크의 적대 몹 스폰을 건너뛴다
	 */
	public static int spawnPasses(double multiplier, double roll) {
		double safe = sanitizeSpawnRate(multiplier);
		int whole = (int) Math.floor(safe);
		double fraction = safe - whole;
		if (roll < fraction) {
			whole++;
		}
		return Math.max(0, whole);
	}

	private static double lookup(Map<EntityType<?>, Double> cache, Mob mob, Kind kind) {
		try {
			Double cached = cache.get(mob.getType());
			if (cached != null) {
				return cached;
			}
			double value = compute(mob, kind);
			cache.put(mob.getType(), value);
			return value;
		} catch (RuntimeException error) {
			warnOnce(error);
			return 1.0;
		}
	}

	/**
	 * 팀별 배율을 구해 하나로 합친다.
	 *
	 * <p>한 팀 안에서는 곱하고, 팀 사이에서는 {@link #stronger} 로 하나를 고른다.
	 */
	private static double compute(Mob mob, Kind kind) {
		MinecraftServer server = mob.level().getServer();
		if (server == null) {
			return 1.0;
		}
		return computeFor(server, mob.getType(), mob instanceof Enemy, kind);
	}

	/**
	 * 화면에 적을 <b>대표 배율</b>. 능력치 표시가 「지금 판이 얼마나 험한가」로 쓴다.
	 *
	 * <p>기준으로 삼는 몹은 좀비다. 대상을 따로 적지 않은 증강은 <b>적대적 몹 전체</b>에
	 * 걸리므로 그 경우 이 값이 곧 모든 몹의 배율이다. {@code targets} 로 몹을 골라 잡은
	 * 증강만은 그 몹에게만 걸려 이 줄과 다를 수 있는데, 화면에 몹 종류별 표를 그릴 수는
	 * 없으므로 <b>가장 흔한 경우를 대표로</b> 적는다.
	 *
	 * <p>「난이도 상승」의 배율은 여기 들어 있지 않다. 그쪽은 속성 수정자를 따로 붙여 곱하므로
	 * ({@code DifficultyEscalation}) 부르는 쪽에서 곱한다.
	 */
	public static double representativeMultiplier(@Nullable MinecraftServer server, boolean health) {
		if (server == null) {
			return 1.0;
		}
		try {
			return computeFor(server, EntityTypes.ZOMBIE, true, health ? Kind.HEALTH : Kind.DAMAGE);
		} catch (RuntimeException error) {
			warnOnce(error);
			return 1.0;
		}
	}

	private static double computeFor(MinecraftServer server, EntityType<?> type, boolean hostile,
			Kind kind) {
		TeamManager manager = TeamManager.get(server);
		double chosen = 1.0;
		for (ShareTeam team : manager.allTeams()) {
			TeamState state = manager.stateByTeamId(team.teamId());
			if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
				continue;
			}
			chosen = stronger(chosen, teamMultiplier(state, type, hostile, kind));
		}
		return switch (kind) {
			case DAMAGE -> sanitizeDamage(chosen);
			// 행동 속도만 범위가 좁다. 까닭은 sanitizeActionSpeed 에 적어 두었다.
			case ACTION_SPEED -> sanitizeActionSpeed(chosen);
			// 이동 속도도 0 이 되면 몹이 제자리에 굳으므로 체력과 같은 하한을 쓴다.
			case HEALTH, SPEED -> sanitizeHealth(chosen);
		};
	}

	/**
	 * 한 팀이 보유한 증강들의 배율을 모두 곱한 값.
	 *
	 * <p>세트 「방어 3단계」를 켠 팀은 방어 증강이 대가로 건 배율(「철벽」·「버티는 방패」의 몹
	 * 체력)을 곱하지 않는다. <b>그 팀의 몫만</b> 빠진다 — 서버 전체 값은 팀들 중 센 쪽을 따르므로
	 * ({@link #stronger}) 다른 팀이 같은 증강을 들고 세트를 안 켰으면 그쪽 값은 그대로 걸린다.
	 *
	 * <p>세트가 켜지거나 풀리는 것은 언제나 보유 목록이 바뀔 때라 {@link #computeSignature} 가
	 * 달라지고, 다음 훑기에 이미 올라와 있는 몹까지 다시 계산된다.
	 */
	static double teamMultiplier(TeamState state, EntityType<?> type, boolean hostile,
			Kind kind) {
		PerkDrawbacks.Waiver waiver = PerkDrawbacks.waiverFor(state);
		// 「굳은살」은 몹 공격력을 올리는 몫(「유리 세계」·「암살자」 등)만 덜어 낸다. 이 팀 몫에만
		// 걸리고, 서버 전체 값은 여전히 팀들 중 센 쪽을 따른다.
		DamageIncreaseReliefEffect.Relief relief = DamageIncreaseReliefEffect.reliefFor(state);
		double total = 1.0;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (waiver.waives(perk, effect)) {
					continue;
				}
				double value = contribution(effect, type, hostile, kind);
				total *= kind == Kind.DAMAGE ? relief.ease(value) : value;
			}
		}
		return total;
	}

	/** 한 팀 몫의 몹 최대 체력 배율. 월드 없이 팀 상태만으로 계산하므로 시험이 쓴다. */
	static double teamHealthMultiplier(TeamState state, EntityType<?> type, boolean hostile) {
		return teamMultiplier(state, type, hostile, Kind.HEALTH);
	}

	private static double contribution(PerkEffect effect, EntityType<?> type, boolean hostile,
			Kind kind) {
		return sparingDragon(type, rawContribution(effect, type, hostile, kind));
	}

	/**
	 * 증강이 <b>엔더 드래곤을 강하게 만드는 몫</b>을 지운다. 1 보다 큰 배율이면 1.0, 아니면 그대로.
	 *
	 * <p>체력·공격력·이동 속도·행동 속도 네 갈래가 모두 여기를 지난다. 정의 파일의
	 * {@code excludes} 에 드래곤을 적어 두는 것과 별개로, 적는 것을 잊은 증강이 새로 들어와도
	 * 드래곤은 강해지지 않는다(사람 결정 2026-10-16). 드래곤 시련 자체의 강화(팀 인원 비례 체력
	 * 등)는 증강이 아니라 이 길을 지나지 않는다.
	 *
	 * <p><b>약하게 하는 쪽은 정의대로 둔다.</b> 드래곤을 약하게 하는 증강을 막을지는 정의의
	 * {@code excludes} 가 정한다 — 지금은 「유리 세계」·「거인 사냥꾼」의 몹 체력 감소가 드래곤을
	 * 빼고 있다.
	 */
	static double sparingDragon(EntityType<?> type, double value) {
		return type == EntityTypes.ENDER_DRAGON && value > 1.0 ? 1.0 : value;
	}

	private static double rawContribution(PerkEffect effect, EntityType<?> type, boolean hostile,
			Kind kind) {
		return switch (kind) {
			case HEALTH -> effect instanceof MobHealthEffect mobHealth
					&& mobHealth.appliesTo(type, hostile) ? mobHealth.multiplierFor() : 1.0;
			case DAMAGE -> effect instanceof MobDamageEffect mobDamage
					&& mobDamage.appliesTo(type, hostile) ? mobDamage.multiplierFor() : 1.0;
			case SPEED -> effect instanceof MobSpeedEffect mobSpeed
					&& mobSpeed.appliesTo(type, hostile) ? mobSpeed.multiplierFor() : 1.0;
			case ACTION_SPEED -> effect instanceof MobActionSpeedEffect actionSpeed
					&& actionSpeed.appliesTo(type, hostile) ? actionSpeed.multiplierFor() : 1.0;
		};
	}

	/**
	 * 두 배율 중 1.0 에서 더 멀리 떨어진 쪽을 고른다. 거리가 같으면 작은 쪽(플레이어에게
	 * 유리한 쪽)을 고른다.
	 *
	 * <p>비율은 로그 눈금으로 봐야 대칭이다. 2배와 0.5배는 방향만 반대고 세기가 같다.
	 */
	static double stronger(double first, double second) {
		double firstDistance = Math.abs(Math.log(first));
		double secondDistance = Math.abs(Math.log(second));
		if (secondDistance > firstDistance) {
			return second;
		}
		if (firstDistance > secondDistance) {
			return first;
		}
		return Math.min(first, second);
	}

	/** 최대 체력 배율은 0 이 될 수 없다. 이상한 값은 1.0 으로 물러난다. */
	static double sanitizeHealth(double value) {
		if (!Double.isFinite(value) || value <= 0.0) {
			return 1.0;
		}
		return Math.max(MIN_HEALTH_MULTIPLIER, Math.min(MAX_MULTIPLIER, value));
	}

	/**
	 * 행동 속도 배율은 다른 배율들보다 범위가 훨씬 좁다. 이상한 값은 1.0 으로 물러난다.
	 *
	 * <p>위쪽은 {@code MobActionSpeedEffect.MAX_MULTIPLIER}(×2.0)다. 실행부가 한 틱에 많아야
	 * 한 번만 더 돌리므로 그 위는 <b>표현할 방법이 없고</b>, 허용하면 정의에 적힌 숫자와 실제가
	 * 조용히 달라진다. 아래쪽은 {@code MIN_MULTIPLIER}(×0.1)다 — 0 이면 그 몹은 틱을 영영 돌지
	 * 못해 불에 타지도 디스폰되지도 않고 월드에 굳은 채 쌓인다.
	 *
	 * <p>한 팀이 이 계열 증강을 여럿 가지면 배율이 곱해져 범위를 넘을 수 있다. 그때 잘리는
	 * 자리가 여기다.
	 */
	static double sanitizeActionSpeed(double value) {
		if (!Double.isFinite(value) || value <= 0.0) {
			return 1.0;
		}
		return Math.max(MobActionSpeedEffect.MIN_MULTIPLIER,
				Math.min(MobActionSpeedEffect.MAX_MULTIPLIER, value));
	}

	/** 피해 배율은 0 까지 허용한다. 이상한 값은 1.0 으로 물러난다. */
	static double sanitizeDamage(double value) {
		if (!Double.isFinite(value) || value < 0.0) {
			return 1.0;
		}
		return Math.min(MAX_MULTIPLIER, value);
	}

	// ------------------------------------------------------------------ 최대 체력 반영

	/**
	 * 몹 하나의 최대 체력 수정자를 지금 있어야 할 모습으로 맞춘다.
	 *
	 * <p>여러 번 불려도 결과가 같다. 배율이 1.0 이면 붙어 있던 수정자를 떼고 끝내므로,
	 * 증강 풀이 비어 있거나 회차 리셋으로 증강을 잃은 뒤에는 바닐라와 완전히 같아진다.
	 *
	 * <p>몹 스폰 경로 한가운데서 불리므로 어떤 예외도 밖으로 내보내지 않는다.
	 */
	private static void applyHealth(@Nullable Entity entity) {
		// Mob 이 아닌 것은 손대지 않는다. 플레이어는 Mob 이 아니므로 여기서 이미 걸러진다.
		if (!(entity instanceof Mob mob)) {
			return;
		}
		try {
			AttributeInstance instance = mob.getAttribute(Attributes.MAX_HEALTH);
			if (instance == null) {
				return;
			}
			double multiplier = healthMultiplier(mob);
			// ADD_MULTIPLIED_TOTAL 은 다른 수정자까지 계산한 뒤 (1 + amount) 를 곱한다.
			double amount = multiplier - 1.0;
			AttributeModifier existing = instance.getModifier(HEALTH_MODIFIER_ID);

			if (multiplier == 1.0) {
				if (existing == null) {
					return;
				}
				float before = mob.getMaxHealth();
				instance.removeModifier(HEALTH_MODIFIER_ID);
				settleHealth(mob, before);
				return;
			}
			if (existing != null && existing.amount() == amount) {
				return;
			}
			float before = mob.getMaxHealth();
			instance.addOrUpdateTransientModifier(new AttributeModifier(
					HEALTH_MODIFIER_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
			settleHealth(mob, before);
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/**
	 * 최대 체력이 바뀐 뒤 현재 체력을 정리한다.
	 *
	 * <p>바뀌기 전에 체력이 가득 차 있었으면 새 최대치로 다시 채운다. 갓 스폰된 몹이 여기
	 * 해당하므로, 배율이 1보다 크면 늘어난 만큼을 실제로 얻는다. 가득 차 있지 않았으면
	 * 넘치는 만큼만 깎는다. 이미 죽어가는 개체는 건드리지 않는다.
	 */
	private static void settleHealth(Mob mob, float previousMax) {
		float health = mob.getHealth();
		if (!(health > 0.0F)) {
			return;
		}
		float max = mob.getMaxHealth();
		if (!Float.isFinite(max) || max <= 0.0F) {
			return;
		}
		boolean wasFull = health >= previousMax;
		if (wasFull || health > max) {
			mob.setHealth(max);
		}
	}

	// ------------------------------------------------------------------ 이동 속도 반영

	/**
	 * 몹 하나의 이동 속도 수정자를 지금 있어야 할 모습으로 맞춘다.
	 *
	 * <p>{@link #applyHealth} 와 같은 자리·같은 방식이다. 여러 번 불려도 결과가 같고, 배율이
	 * 1.0 이면 붙어 있던 수정자를 떼고 끝내므로 증강을 잃은 뒤에는 바닐라와 완전히 같아진다.
	 *
	 * <p>체력과 달리 뒷정리가 없다. 최대 체력은 현재 체력이 새 최대치를 넘지 않게 맞춰 줘야
	 * 하지만, 이동 속도에는 "현재 값"이 따로 없어 속성만 갈아 끼우면 그것으로 끝이다.
	 *
	 * <p>몹 스폰 경로 한가운데서 불리므로 어떤 예외도 밖으로 내보내지 않는다.
	 */
	private static void applySpeed(@Nullable Entity entity) {
		if (!(entity instanceof Mob mob)) {
			return;
		}
		try {
			AttributeInstance instance = mob.getAttribute(Attributes.MOVEMENT_SPEED);
			if (instance == null) {
				return;
			}
			double multiplier = speedMultiplier(mob);
			// ADD_MULTIPLIED_TOTAL 은 다른 수정자까지 계산한 뒤 (1 + amount) 를 곱한다.
			double amount = multiplier - 1.0;
			AttributeModifier existing = instance.getModifier(SPEED_MODIFIER_ID);

			if (multiplier == 1.0) {
				if (existing != null) {
					instance.removeModifier(SPEED_MODIFIER_ID);
				}
				return;
			}
			if (existing != null && existing.amount() == amount) {
				return;
			}
			instance.addOrUpdateTransientModifier(new AttributeModifier(
					SPEED_MODIFIER_ID, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/** 이미 올라와 있는 몹 전체를 다시 계산한다. 증강 구성이 바뀐 순간에만 돈다. */
	private static void sweep(MinecraftServer server) {
		try {
			for (ServerLevel level : server.getAllLevels()) {
				for (Entity entity : level.getAllEntities()) {
					applyHealth(entity);
					applySpeed(entity);
				}
			}
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/**
	 * 팀들의 보유 증강을 요약한 값. 이 값이 그대로면 배율도 그대로다.
	 *
	 * <p>해시라 이론적으로는 서로 다른 구성이 같은 값을 낼 수 있다. 그 경우에도 최악은
	 * "이미 올라와 있는 몹의 수정자가 다음 변경까지 옛날 값으로 남는" 것뿐이라, 스폰이나
	 * 피해 처리가 깨지지는 않는다.
	 */
	private static int computeSignature(MinecraftServer server) {
		TeamManager manager = TeamManager.get(server);
		int hash = 1;
		for (ShareTeam team : manager.allTeams()) {
			TeamState state = manager.stateByTeamId(team.teamId());
			if (state == null || !state.perksEnabled) {
				continue;
			}
			for (String perkId : state.ownedPerks) {
				hash = hash * 31 + String.valueOf(perkId).hashCode();
			}
			hash = hash * 31 + 17;
		}
		return hash;
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"몹 증강 효과를 처리하지 못해 이번에는 건너뜁니다. 이 경고는 한 번만 남습니다.", error);
	}

	/** 테스트가 상태를 격리할 때 쓴다. */
	static void resetForTesting() {
		reset();
		warned = false;
	}

	// ------------------------------------------------------------------ 대상 한정

	/**
	 * {@code targets} / {@code excludes} 로 적은 대상 한정.
	 *
	 * <ul>
	 *   <li>{@code targets} 를 적으면 거기 적힌 종류에만 적용한다.</li>
	 *   <li>{@code excludes} 만 적으면 적대적 몹 전체에서 그 종류만 뺀다.</li>
	 *   <li>둘 다 없으면 적대적 몹 전체에 적용한다. 주민·소·팀원은 여기 들어가지 않는다.</li>
	 * </ul>
	 *
	 * <p>레지스트리에 없는 엔티티 이름은 경고를 남기고 그 이름만 버린다. 증강 자체는 살린다.
	 * 확인은 만들 때가 아니라 처음 쓸 때 한다. 증강 정의를 읽는 시점에는 엔티티 레지스트리가
	 * 아직 준비되지 않았을 수 있기 때문이다.
	 */
	public static final class Targets {
		/** 아무 한정도 없는 기본값. 적대적 몹 전체를 뜻한다. */
		public static final Targets ALL_HOSTILE = new Targets("", false, Set.of(), Set.of());

		private final String perkId;
		private final boolean hasTargetList;
		private Set<Identifier> targets;
		private Set<Identifier> excludes;
		private boolean verified;

		Targets(String perkId, boolean hasTargetList, Set<Identifier> targets,
				Set<Identifier> excludes) {
			this.perkId = perkId;
			this.hasTargetList = hasTargetList;
			this.targets = Set.copyOf(targets);
			this.excludes = Set.copyOf(excludes);
		}

		/** 이 효과가 해당 종류에 걸리는지. */
		public synchronized boolean matches(EntityType<?> type, boolean hostile) {
			if (type == null) {
				return false;
			}
			verify();
			// 팀원에게는 어떤 경우에도 걸리지 않는다. targets 에 직접 적어도 마찬가지다.
			if (type == EntityTypes.PLAYER) {
				return false;
			}
			if (hasTargetList) {
				return targets.contains(EntityType.getKey(type));
			}
			if (excludes.contains(EntityType.getKey(type))) {
				return false;
			}
			return hostile;
		}

		/** {@code targets} 필드를 적었는지. 적었는데 남은 게 없으면 아무에게도 안 걸린다. */
		public boolean hasTargetList() {
			return hasTargetList;
		}

		public synchronized Set<Identifier> targets() {
			return targets;
		}

		public synchronized Set<Identifier> excludes() {
			return excludes;
		}

		/** 레지스트리에 없는 이름을 처음 쓸 때 한 번 걸러낸다. */
		private void verify() {
			if (verified) {
				return;
			}
			verified = true;
			targets = keepKnown(targets, "targets");
			excludes = keepKnown(excludes, "excludes");
			if (hasTargetList && targets.isEmpty()) {
				SharedFateMod.LOGGER.warn(
						"증강 {}: targets 에 쓸 수 있는 엔티티가 하나도 없어 이 효과는 아무에게도 걸리지 않습니다",
						perkId);
			}
		}

		private Set<Identifier> keepKnown(Set<Identifier> ids, String field) {
			if (ids.isEmpty()) {
				return ids;
			}
			Set<Identifier> kept = new LinkedHashSet<>(ids.size());
			for (Identifier id : ids) {
				boolean known;
				try {
					known = BuiltInRegistries.ENTITY_TYPE.containsKey(id);
				} catch (RuntimeException error) {
					// 레지스트리를 못 읽는 상황이면 걸러내지 않고 그대로 둔다.
					return ids;
				}
				if (known) {
					kept.add(id);
				} else {
					SharedFateMod.LOGGER.warn("증강 {}: {} 의 알 수 없는 엔티티 {} 를 무시합니다",
							perkId, field, id);
				}
			}
			return Set.copyOf(kept);
		}
	}

	/**
	 * 효과 JSON 에서 {@code targets} / {@code excludes} 를 읽는다.
	 *
	 * <p>이름 모양이 틀렸거나 레지스트리에 없는 값은 경고 후 그 항목만 버린다. 증강 전체를
	 * 버리지 않으므로 오타 하나가 증강을 통째로 날리지는 않는다.
	 */
	public static Targets parseTargets(String perkId, JsonObject json) {
		List<String> rawTargets = PerkEffectType.readStringList(json, "targets");
		List<String> rawExcludes = PerkEffectType.readStringList(json, "excludes");
		if (rawTargets == null && rawExcludes == null) {
			return Targets.ALL_HOSTILE;
		}
		Set<Identifier> targets = toIdentifiers(perkId, "targets", rawTargets);
		Set<Identifier> excludes = toIdentifiers(perkId, "excludes", rawExcludes);
		if (rawTargets != null && rawExcludes != null && !excludes.isEmpty()) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: targets 와 excludes 를 함께 적었습니다. targets 만 씁니다", perkId);
		}
		return new Targets(perkId, rawTargets != null, targets, excludes);
	}

	private static Set<Identifier> toIdentifiers(String perkId, String field,
			@Nullable List<String> raw) {
		if (raw == null || raw.isEmpty()) {
			return Set.of();
		}
		List<Identifier> parsed = new ArrayList<>(raw.size());
		for (String value : raw) {
			Identifier id = Identifier.tryParse(value);
			if (id == null) {
				SharedFateMod.LOGGER.warn("증강 {}: {} 의 엔티티 이름 {} 이(가) 올바르지 않아 무시합니다",
						perkId, field, value);
				continue;
			}
			parsed.add(id);
		}
		return Set.copyOf(new LinkedHashSet<>(parsed));
	}
}
