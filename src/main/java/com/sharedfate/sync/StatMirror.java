package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.net.TeamBroadcaster;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.food.FoodData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class StatMirror {
	/**
	 * 팀 공유 레벨의 기본 상한.
	 *
	 * <p><b>전에는 상한이 아예 없었다.</b> 공유 경험치가 쌓이는 대로 레벨이 41·42·… 로 끝없이
	 * 올라갔는데, 증강 구간은 {@link com.sharedfate.perk.PerkMilestones#MAX} 인 40 에서 끝나므로
	 * 그 위의 숫자는 아무것도 뜻하지 않는 장식이었다. 2026-09-30 에 직접 플레이해 본 사람이
	 * 「레벨이 40이면 그 이상 안 오르게, 맥스가 40이니」라고 해서 여기서 멈춘다.
	 *
	 * <p>상한은 「증강을 더 주지 않는다」가 아니라 <b>레벨 자체가 오르지 않는다</b>는 뜻이다.
	 * 40 에서 멈춘 화면이 곧 「이 회차에서 받을 증강을 다 받았다」는 표시가 된다.
	 */
	public static final int BASE_LEVEL_CAP = 40;

	/**
	 * 드래곤 시련({@code TeamState.dragonTrialsEnabled})을 켠 팀의 상한.
	 *
	 * <p>시련을 켠 팀은 회차가 드래곤전까지 이어지므로 10 레벨을 더 준다. 이 값을 가르는 설정은
	 * 팀을 만들 때 정해지고 그 뒤로 바뀌는 경로가 없어, 한 회차 안에서 상한이 움직이는 일은 없다.
	 *
	 * <p><b>⚠ 41~50 구간에는 아직 증강도 세트도 없다.</b> {@code PerkMilestones.MAX} 는 40
	 * 그대로라 41~50 에서는 증강 선택권이 한 번도 열리지 않는다. 레벨만 오르고 보상이 비는
	 * 것이 <b>지금은 맞는 상태다</b> — 그 구간의 증강·세트는 「추후 추가 예정」이고 아직 무엇을
	 * 줄지 정해진 것이 없다. 나중에 채우는 사람은 여기와 {@code PerkMilestones} 두 곳을 함께
	 * 보면 된다.
	 */
	public static final int TRIAL_LEVEL_CAP = 50;

	private static final Map<UUID, Snapshot> LAST = new HashMap<>();
	private static final Set<UUID> SUPPRESSED_TEAMS = new HashSet<>();
	private static final Set<UUID> DAMAGE_CAPTURED_THIS_TICK = new HashSet<>();

	private record Snapshot(UUID teamId, float health, float absorption, float maxAbsorption,
			int foodLevel, float saturation, int experiencePoints) {
	}

	record StatDelta(float healthLoss, float healthGain, float absorptionLoss, float absorptionGain,
			int foodLevel, float saturation, long totalExperience) {
	}

	/**
	 * 한 팀원이 이번 틱에 만들어 낸 변화량.
	 *
	 * <p>{@link #fold} 가 이걸 팀 단위 {@link StatDelta} 로 접는다.
	 *
	 * @param health              체력 변화량. 음수면 피해, 양수면 회복
	 * @param absorptionDelta     흡수량의 순변화. 양수면 새 보호막을 받은 것이다
	 * @param absorptionConsumed  피해로 실제 소비된 흡수량. 효과 만료로 사라진 몫은 빠져 있다
	 */
	record PlayerDelta(float health, float absorptionDelta, float absorptionConsumed,
			int foodLevel, float saturation, long experience) {
	}

	record AbsorptionDelta(float loss, float gain) {
	}

	private StatMirror() {
	}

	/** 이 팀의 공유 레벨 상한. 시련을 켠 팀만 {@link #TRIAL_LEVEL_CAP} 이다. */
	public static int levelCap(@Nullable TeamState state) {
		return state != null && state.dragonTrialsEnabled ? TRIAL_LEVEL_CAP : BASE_LEVEL_CAP;
	}

	/**
	 * 상한 레벨에 <b>정확히</b> 닿는 경험치 점수. 공유 경험치는 이 값을 넘지 않는다.
	 *
	 * <p>{@code progress = 0} 인 지점을 고른 것은 한 가지 이유 때문이다. 상한 레벨의 막대를
	 * 가득 채운 지점({@code 다음 레벨 − 1})을 쓰면 화면이 「곧 41 이 된다」고 말하는데 영원히
	 * 오르지 않아, 사람이 버그로 읽는다. 빈 막대로 멈추면 「여기가 끝이다」가 한눈에 보인다.
	 */
	public static int experienceCap(@Nullable TeamState state) {
		return experiencePointsFor(levelCap(state), 0.0F);
	}

	/**
	 * 공유 경험치를 상한 안으로 자른다. 공유 풀에 경험치를 써 넣는 자리는 모두 여기를 지난다.
	 *
	 * <h2>넘친 몫은 버린다 — 쌓아 두지 않는다</h2>
	 * <p>사람이 정해 주지 않아 여기서 고른 것이다. 까닭은 둘이다.
	 *
	 * <p>하나. 넘친 경험치를 어딘가에 적어 두면, 나중에 41~50 구간의 증강이 생겨 상한이 풀리는
	 * 순간 <b>그동안 모아 둔 몫이 한꺼번에 터져</b> 팀이 몇 레벨을 건너뛰고 증강 선택 화면이
	 * 줄줄이 뜬다. 상한에 닿은 뒤에 딴 경험치는 「받을 것이 없는 동안 딴 것」이므로 그 회차에
	 * 값을 쳐 줄 근거가 없다.
	 *
	 * <p>둘. 쌓아 두려면 {@code TeamState} 에 저장 항목이 하나 더 붙고 저장 형식을 올려야 한다.
	 * 값 하나를 정하는 일에 저장 형식을 건드릴 이유가 없다.
	 *
	 * <p>상한에 닿은 뒤에도 경험치 오브는 그대로 떨어지고 그대로 먹힌다 — 먹은 몫이 공유 풀에
	 * 더해지지 않을 뿐이다. 오브를 없애지 않는 것은, 없애는 자리가 팀마다 다른 상한을 알 수 없는
	 * 곳들이라 한 팀의 상한 때문에 다른 팀의 경험치까지 사라질 수 있기 때문이다.
	 */
	private static int clampToLevelCap(TeamState state, long points) {
		long bounded = Math.max(0L, Math.min(Integer.MAX_VALUE, points));
		return (int) Math.min(bounded, experienceCap(state));
	}

	public static void forget(UUID player) {
		LAST.remove(player);
	}

	public static void forgetTeam(ShareTeam team) {
		team.members().forEach(LAST::remove);
	}

	public static void suppressTeamForCurrentTick(UUID teamId) {
		SUPPRESSED_TEAMS.add(teamId);
	}

	public static void captureExperienceBeforeDeath(MinecraftServer server, ShareTeam team, TeamState state) {
		if (!SharedFateMod.config.shareExperience) {
			return;
		}
		long delta = 0;
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			Snapshot last = LAST.get(member);
			if (player != null && last != null && last.teamId().equals(team.teamId())) {
				delta += currentExperiencePoints(player) - last.experiencePoints();
			}
		}
		state.totalExperience = clampToLevelCap(state, (long) state.totalExperience + delta);
	}

	public static void captureDamageBeforeDeath(MinecraftServer server, ShareTeam team) {
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			Snapshot last = LAST.get(member);
			if (player != null && last != null && last.teamId().equals(team.teamId())) {
				recordDamage(team, player, last);
			}
		}
		DamageLedger.flushIfDirty();
	}

	public static void tick(MinecraftServer server) {
		TeamManager manager = TeamManager.get(server);
		try {
			for (ShareTeam team : manager.allTeams()) {
				if (SUPPRESSED_TEAMS.contains(team.teamId())) {
					continue;
				}
				TeamState state = manager.stateByTeamId(team.teamId());
				if (state == null) {
					continue;
				}

				List<ServerPlayer> online = aliveOnlineMembers(server, team);
				if (online.isEmpty()) {
					continue;
				}

				StatDelta delta = collectDeltas(team, online, state.damageAlertEnabled);
				int adjustedFoodDelta = FoodOverflowBuffer.get(server).apply(
						team.teamId(), state.foodLevel, delta.foodLevel());
				delta = new StatDelta(delta.healthLoss(), delta.healthGain(),
						delta.absorptionLoss(), delta.absorptionGain(), adjustedFoodDelta,
						delta.saturation(), delta.totalExperience());
				applyDeltas(state, state.maxHealth,
						sharedMaxAbsorption(online),
						delta, SharedFateMod.config.shareExperience);
				if (state.health <= 0.0F) {
					forgetTeam(team);
					DeathHandler.killTeamFromMirror(team, online);
					continue;
				}
				writeBack(team.teamId(), state, online);
			}
		} finally {
			SUPPRESSED_TEAMS.clear();
			DAMAGE_CAPTURED_THIS_TICK.clear();
			DamageLedger.flushIfDue();
		}
	}

	public static void syncPlayerNow(UUID teamId, TeamState state, ServerPlayer player) {
		writeBack(teamId, state, List.of(player));
	}

	private static List<ServerPlayer> aliveOnlineMembers(MinecraftServer server, ShareTeam team) {
		List<ServerPlayer> online = new ArrayList<>();
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (isSharing(player)) {
				online.add(player);
			}
		}
		return online;
	}

	/** 지금 공유 풀에 참여하고 있는 팀원인가. 접속해 있고 살아 있어야 한다. */
	private static boolean isSharing(@Nullable ServerPlayer player) {
		return player != null && !player.isRemoved() && !player.isDeadOrDying();
	}

	/**
	 * @param damageAlert 이 팀이 피격 알림을 쓰는가. 꺼져 있으면 <b>서버가 패킷을 아예 보내지
	 *                    않는다.</b>
	 */
	private static StatDelta collectDeltas(ShareTeam team, List<ServerPlayer> online,
			boolean damageAlert) {
		UUID teamId = team.teamId();
		List<PlayerDelta> deltas = new ArrayList<>(online.size());

		for (ServerPlayer player : online) {
			Snapshot last = LAST.get(player.getUUID());
			if (last == null || !last.teamId().equals(teamId)) {
				continue;
			}
			FoodData foodData = player.getFoodData();
			float playerHealthDelta = healthDelta(
					last.health(), player.getHealth(), player.getMaxHealth());
			float playerAbsorptionDelta = player.getAbsorptionAmount() - last.absorption();
			float consumedAbsorption = consumedAbsorption(
					last.absorption(), player.getAbsorptionAmount(), player.getMaxAbsorption());
			recordDamage(team, player, last);
			if (damageAlert && (playerHealthDelta < -0.01F || consumedAbsorption > 0.01F)) {
				TeamBroadcaster.broadcastDamageAlert(online, player.getPlainTextName());
			}
			deltas.add(new PlayerDelta(
					playerHealthDelta, playerAbsorptionDelta, consumedAbsorption,
					foodData.getFoodLevel() - last.foodLevel(),
					foodData.getSaturationLevel() - last.saturation(),
					currentExperiencePoints(player) - last.experiencePoints()));
		}
		return fold(deltas);
	}

	/**
	 * 팀원별 변화량을 공유 풀 하나의 변화량으로 접는다.
	 *
	 * <p>체력 손실도, 체력 회복도, 허기·포만감 변화도 그대로 <em>합산</em>한다. 팀원 A 가
	 * 좀비에게, B 가 스켈레톤에게 같은 틱에 맞았다면 팀은 진짜로 두 번 맞은 것이고, A 가
	 * 금사과를 B 가 빵을 먹었다면 진짜로 두 번 먹은 것이므로 둘 다 세는 게 맞다.
	 *
	 * <p>합산하면 안 되는 경우 — 공유된 상태이상 하나가 팀 전원에게 똑같이 일으키는 피해·회복·
	 * 허기 소모 — 는 여기까지 오지 않는다. {@link SharedEffectDamage} 가 변화가 발생하는
	 * 자리에서 대표 한 명 것만 남기고 나머지를 막으므로, 막힌 팀원의 체력과 허기는 애초에
	 * 움직이지 않아 변화량이 0 이다.
	 *
	 * <p>허기는 한 가지가 더 얽힌다. 허기 효과는 그 자리에서 배를 깎지 않고 소모도만 쌓고,
	 * 실제 감소는 소모도가 4.0 을 넘는 한참 뒤 {@code FoodData.tick} 에서 일어난다. 그래서
	 * 여기서 관측하는 배고픔 변화만 봐서는 원인을 되짚을 수 없다. 소모도가 쌓이는 입구인
	 * {@code Player.causeFoodExhaustion} 에서 막는 이유가 그것이다.
	 */
	static StatDelta fold(List<PlayerDelta> deltas) {
		float healthLoss = 0.0F;
		float healthGain = 0.0F;
		AbsorptionDelta absorption = new AbsorptionDelta(0.0F, 0.0F);
		int food = 0;
		float saturation = 0.0F;
		long experience = 0;

		for (PlayerDelta delta : deltas) {
			if (delta.health() < 0.0F) {
				healthLoss += delta.health();
			} else {
				healthGain += delta.health();
			}
			if (delta.absorptionDelta() > 0.0F) {
				absorption = mergeAbsorptionDelta(absorption, delta.absorptionDelta());
			} else if (delta.absorptionConsumed() > 0.0F) {
				absorption = mergeAbsorptionDelta(absorption, -delta.absorptionConsumed());
			}
			food += delta.foodLevel();
			saturation += delta.saturation();
			experience += delta.experience();
		}
		return new StatDelta(
				healthLoss, healthGain, absorption.loss(), absorption.gain(),
				food, saturation, experience);
	}

	/**
	 * 팀에서 공유 상태이상의 몫을 실제로 겪을 한 명.
	 *
	 * <p>독 피해도, 재생 회복도, 허기 소모도 이 한 명 것만 공유 풀에 반영된다. 셋이 같은
	 * 대표를 쓰는 게 중요하다. 피해와 회복의 대표가 다르면 재생과 독이 동시에 걸린 팀에서
	 * 한쪽만 1인분이 되어 셈이 어긋난다.
	 *
	 * <p>{@code team.members()} 순서로 처음 만나는, 접속해 있고 살아 있는 팀원이다. 팀 명단
	 * 순서는 안정적이라 같은 틱 안에서 여러 번 물어도 같은 사람이 나온다. 아무도 없으면
	 * {@code null} 이고, 그때는 {@link SharedEffectDamage} 가 아무것도 막지 않는다.
	 */
	public static @Nullable ServerPlayer sharedEffectRepresentative(MinecraftServer server, ShareTeam team) {
		for (UUID member : team.members()) {
			ServerPlayer player = server.getPlayerList().getPlayer(member);
			if (isSharing(player)) {
				return player;
			}
		}
		return null;
	}

	private static void recordDamage(ShareTeam team, ServerPlayer player, Snapshot last) {
		if (!DAMAGE_CAPTURED_THIS_TICK.add(player.getUUID())) {
			return;
		}
		float healthLoss = Math.max(0.0F,
				-healthDelta(last.health(), player.getHealth(), player.getMaxHealth()));
		float absorptionLoss = consumedAbsorption(
				last.absorption(), player.getAbsorptionAmount(), player.getMaxAbsorption());
		DamageLedger.record(team, player, healthLoss + absorptionLoss);
	}

	/**
	 * 최대 체력이 내려가 잘린 몫을 뺀, 이 사람의 진짜 체력 변화량. 음수면 피해, 양수면 회복.
	 *
	 * <p>{@link #consumedAbsorption} 이 흡수량에 대해 하는 일과 같다. 상한이 줄어서 줄어든 것은
	 * 맞은 것이 아니다.
	 *
	 * <p><b>이걸 빼지 않으면 팀이 즉사한다.</b> 최대 체력이 20 에서 10 으로 줄면
	 * {@link MaxHealthAttribute#apply} 가 팀원 <b>각자의</b> 체력을 10 으로 자르고,
	 * {@link #fold} 는 그 자름을 사람 수만큼 <b>합산</b>한다. 3인 팀이 체력 18 에서 상한을
	 * 잃으면 8 이 세 번 빠져 공유 체력이 18 − 24 = 0 이 된다. 한 번의 자름이 인원수만큼
	 * 곱해지는 것이라, 팀이 건강할수록 확실하게 죽는다 — 3인은 15 이상, 2인은 20 에서
	 * 그렇게 된다.
	 *
	 * <p>같은 틱에 진짜 피해도 받았다면 그 몫은 그대로 남는다. 상한이 20 → 10 이 된 틱에 3 을
	 * 맞아 체력이 7 이 됐다면 답은 −3 이다. 반대로 상한이 오를 때는 아무 일도 하지 않는다 —
	 * 체력이 저절로 차오르지는 않기 때문이다.
	 *
	 * <p>공유 체력을 새 상한으로 자르는 일은 {@link #applyDeltas} 가 이미 한다. 여기서는
	 * 「피해로 세지 않는다」까지만 하면 된다.
	 */
	static float healthDelta(float previousHealth, float currentHealth, float currentMaximum) {
		float capacityLoss = Math.max(0.0F, previousHealth - currentMaximum);
		return currentHealth - previousHealth + capacityLoss;
	}

	static float consumedAbsorption(float previousAmount, float currentAmount, float currentMaximum) {
		float observedLoss = Math.max(0.0F, previousAmount - currentAmount);
		float capacityLoss = Math.max(0.0F, previousAmount - currentMaximum);
		return Math.max(0.0F, observedLoss - capacityLoss);
	}

	static AbsorptionDelta mergeAbsorptionDelta(AbsorptionDelta current, float delta) {
		if (delta < 0.0F) {
			return new AbsorptionDelta(current.loss() + delta, current.gain());
		}
		return new AbsorptionDelta(current.loss(), Math.max(current.gain(), delta));
	}

	static void applyDeltas(TeamState state, float maxHealth, float maxAbsorption,
			StatDelta delta, boolean shareExperience) {
		float availableAbsorption = clamp(
				state.absorption + delta.absorptionGain(), 0.0F, maxAbsorption);
		float totalDamage = Math.max(0.0F,
				-delta.healthLoss() - delta.absorptionLoss());
		float absorbed = Math.min(availableAbsorption, totalDamage);
		float overflowDamage = totalDamage - absorbed;
		state.absorption = availableAbsorption - absorbed;
		state.health = clamp(
				state.health + delta.healthGain() - overflowDamage, 0.0F, maxHealth);
		state.foodLevel = Math.round(clamp(state.foodLevel + delta.foodLevel(), 0.0F, 20.0F));
		state.saturation = clamp(state.saturation + delta.saturation(), 0.0F, state.foodLevel);
		if (shareExperience) {
			// 상한을 넘은 몫은 여기서 사라진다. 다음 writeBack 이 팀원의 경험치를 잘린 값으로
			// 되돌리므로, 레벨은 상한에서 멈추고 막대도 더 차지 않는다.
			state.totalExperience = clampToLevelCap(state,
					(long) state.totalExperience + delta.totalExperience());
		}
	}

	private static void writeBack(UUID teamId, TeamState state, List<ServerPlayer> online) {
		for (ServerPlayer player : online) {
			if (player.getHealth() != state.health) {
				player.setHealth(state.health);
			}
			if (player.getAbsorptionAmount() != state.absorption) {
				player.setAbsorptionAmount(state.absorption);
			}

			FoodData food = player.getFoodData();
			if (food.getFoodLevel() != state.foodLevel) {
				food.setFoodLevel(state.foodLevel);
			}
			if (food.getSaturationLevel() != state.saturation) {
				food.setSaturation(state.saturation);
			}

			if (SharedFateMod.config.shareExperience
					&& currentExperiencePoints(player) != state.totalExperience) {
				applyTotalExperience(player, state.totalExperience);
			}

			LAST.put(player.getUUID(), new Snapshot(
					teamId, player.getHealth(), player.getAbsorptionAmount(), player.getMaxAbsorption(),
					food.getFoodLevel(),
					food.getSaturationLevel(), currentExperiencePoints(player)));
		}

		if (SharedFateMod.config.shareExperience) {
			state.xpLevel = online.getFirst().experienceLevel;
			state.xpProgress = online.getFirst().experienceProgress;
		}
	}

	private static float sharedMaxAbsorption(List<ServerPlayer> online) {
		float maximum = 0.0F;
		for (ServerPlayer player : online) {
			maximum = Math.max(maximum, player.getMaxAbsorption());
		}
		return Math.max(0.0F, maximum);
	}

	private static void applyTotalExperience(ServerPlayer player, int total) {
		int safeTotal = Math.max(0, total);
		int level = levelForExperiencePoints(safeTotal);
		int base = experiencePointsFor(level, 0.0F);
		player.experienceLevel = level;
		player.experienceProgress = (safeTotal - base) / (float) xpNeededForLevel(level);
		player.totalExperience = safeTotal;
	}

	public static int currentExperiencePoints(ServerPlayer player) {
		return experiencePointsFor(player.experienceLevel, player.experienceProgress);
	}

	public static void setTotalExperience(ServerPlayer player, int total) {
		applyTotalExperience(player, total);
	}

	public static void addSharedExperience(TeamState state, int points) {
		state.totalExperience = clampToLevelCap(state,
				(long) state.totalExperience + Math.max(0, points));
	}

	static int experiencePointsFor(int level, float progress) {
		int safeLevel = Math.max(0, level);
		long base = baseExperienceForLevel(safeLevel);
		long current = base + Math.round(clamp(progress, 0.0F, 1.0F) * xpNeededForLevel(safeLevel));
		return (int) Math.min(Integer.MAX_VALUE, current);
	}

	private static long baseExperienceForLevel(int safeLevel) {
		if (safeLevel <= 15) {
			return (long) safeLevel * safeLevel + 6L * safeLevel;
		} else if (safeLevel <= 30) {
			long steps = safeLevel - 15L;
			return 315L + 37L * steps + 5L * steps * (steps - 1L) / 2L;
		} else {
			long steps = safeLevel - 30L;
			return 1395L + 112L * steps + 9L * steps * (steps - 1L) / 2L;
		}
	}

	private static int xpNeededForLevel(int level) {
		if (level >= 30) {
			return 112 + (level - 30) * 9;
		}
		if (level >= 15) {
			return 37 + (level - 15) * 5;
		}
		return 7 + level * 2;
	}

	private static int levelForExperiencePoints(int points) {
		int low = 0;
		int high = 1;
		while (high < 100_000 && baseExperienceForLevel(high) <= points) {
			high *= 2;
		}
		while (low + 1 < high) {
			int middle = low + (high - low) / 2;
			if (baseExperienceForLevel(middle) <= points) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return low;
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}
}
