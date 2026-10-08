package com.sharedfate.perk;

import com.sharedfate.perk.effect.SmeltingSpeedEffect;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 굽는 속도 효과({@code smelting_speed})의 집행부.
 *
 * <p>{@link SmeltingSpeedEffect} 가 「얼마를 곱하는가」만 들고, 실제로 굽는 시간을 줄이는 자리는
 * {@code AbstractFurnaceCookTimeMixin} 이다. 그 둘 사이에서 <b>지금 몇 배인가</b>만 답한다.
 *
 * <h2>화로는 어느 팀 것도 아니다</h2>
 * <p>{@code AbstractFurnaceBlockEntity.getTotalCookTime} 이 불릴 때 그 자리에 사람은 없다.
 * 아궁이에 넣어 둔 것이 저 혼자 익는 동안에도 불리고, 호퍼가 넣어 준 것도 불린다. 그래서
 * <b>팀 하나를 고를 수가 없다.</b>
 *
 * <p>{@link MobPerkModifiers} 가 몹에게 쓰는 규칙을 그대로 따른다 — 월드에 걸리는 효과는
 * <b>모든 팀을 훑어 1.0 에서 가장 멀리 떨어진 배율 하나</b>를 고른다. 팀 목록을 훑는 순서에
 * 결과가 좌우되지 않고, 팀 수가 늘어도 배율이 누적되지 않는다. {@code singleTeamOnly} 를 켜 둔
 * 서버(이 모드의 보통 쓰임새)는 팀이 하나뿐이라 「가진 팀이 있으면 그 배율」과 같은 결과다.
 *
 * <h2>효과는 증강에서도 세트에서도 온다</h2>
 * <p>지금은 세트 「개척 2」 하나뿐이지만, 보유 증강 쪽도 함께 훑는다. 나중에 증강 하나가 같은
 * 효과를 갖게 됐을 때 여기를 고치는 것을 잊으면 <b>빌드도 로그도 통과하는데 그 증강만 조용히
 * 아무 일도 안 한다.</b> {@link PerkSetEffects} 의 머리말이 경고하는 바로 그 사고다.
 *
 * <h2>비싼 자리가 아니다</h2>
 * <p>{@code getTotalCookTime} 은 화로에 든 것이 바뀔 때와 한 번 구워 낼 때만 불린다. 매 틱이
 * 아니다. 그래도 효과를 가진 팀이 없으면 팀 목록 한 번만 훑고 끝난다.
 */
public final class PerkSmelting {

	private PerkSmelting() {
	}

	/**
	 * 지금 이 서버에서 굽는 시간에 나눌 배율. 해당 없으면 {@code 1.0}.
	 *
	 * @param server 서버. {@code null} 이면 {@code 1.0}
	 */
	public static double speedMultiplier(@Nullable MinecraftServer server) {
		if (server == null) {
			return 1.0;
		}
		TeamManager manager = TeamManager.get(server);
		double best = 1.0;
		for (ShareTeam team : List.copyOf(manager.allTeams())) {
			double value = speedMultiplier(manager.stateByTeamId(team.teamId()));
			if (farther(value, best)) {
				best = value;
			}
		}
		return best;
	}

	/**
	 * 한 팀이 가진 굽는 속도 배율. 해당 없으면 {@code 1.0}.
	 *
	 * <p>같은 팀 안에서 효과가 여럿이면 <b>곱한다.</b> 세트 보상과 증강이 겹쳤을 때 둘 다
	 * 살아 있어야 한다.
	 */
	public static double speedMultiplier(@Nullable TeamState state) {
		TeamState active = PerkWorldRules.activeState(state);
		if (active == null) {
			return 1.0;
		}
		double multiplier = 1.0;
		for (String perkId : active.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof SmeltingSpeedEffect speed) {
					multiplier *= speed.multiplierFor();
				}
			}
		}
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(active)) {
			if (effect instanceof SmeltingSpeedEffect speed) {
				multiplier *= speed.multiplierFor();
			}
		}
		return multiplier;
	}

	/**
	 * 굽는 시간을 이 배율로 줄인다. 최소 1틱은 남긴다.
	 *
	 * <p>0틱을 돌려주면 {@code AbstractFurnaceBlockEntity.serverTick} 의
	 * {@code cookingTimer >= cookingTotalTime} 판정이 한 틱에 여러 번 걸려 화로가 넣는 족족
	 * 비워진다. 그것은 「빠르다」가 아니라 「굽는 단계가 사라졌다」다.
	 */
	public static int reducedCookTime(int cookTime, double multiplier) {
		if (cookTime <= 0 || !Double.isFinite(multiplier) || multiplier <= 0.0
				|| multiplier == 1.0) {
			return cookTime;
		}
		// 바닐라가 speedMultiplier 를 먹일 때와 같은 셈이다 — 나눈 뒤 올림.
		// (AbstractFurnaceBlockEntity.getTotalCookTime 의 Math.ceil(time / speedMultiplier))
		return Math.max(1, (int) Math.ceil(cookTime / multiplier));
	}

	/** 뒤에 온 배율이 1.0 에서 더 멀리 떨어져 있는가. 같으면 거짓 — 먼저 본 것이 남는다. */
	private static boolean farther(double candidate, double best) {
		double candidateGap = Math.abs(candidate - 1.0);
		double bestGap = Math.abs(best - 1.0);
		if (candidateGap != bestGap) {
			return candidateGap > bestGap;
		}
		// 벌어진 정도가 같으면 작은 쪽. MobPerkModifiers 와 같은 규칙이라 팀 순서에 안 흔들린다.
		return candidate < best;
	}
}
