package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.AttributeEffect;
import com.sharedfate.perk.effect.MaxHealthBonusEffect;
import com.sharedfate.perk.effect.MaxHealthLockEffect;
import com.sharedfate.sync.MaxHealthAttribute;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

/**
 * 최대 체력을 건드리는 증강들의 판정부이자 지킴이.
 *
 * <p>{@link MaxHealthLockEffect} 는 "몇으로 고정할 것인가"만, {@link MaxHealthBonusEffect} 는
 * "얼마를 더할 것인가"만 들고 있고, 그 숫자를 실제 최대 체력으로 바꾸는 일은 전부 여기서 한다.
 *
 * <h2>기본값과 보너스를 나눠 둔다</h2>
 * <p>팀의 최대 체력은 두 조각으로 되어 있다.
 *
 * <ul>
 *   <li>{@code TeamState.baseMaxHealth} — 팀이 정한 값. {@code /shareteam health} 가 정하고,
 *       아무것도 정하지 않았으면 설정의 {@code sharedMaxHealth} 다.</li>
 *   <li>증강 보너스 — {@link #bonusMaxHealth} 가 보유 증강에서 매번 다시 센다.</li>
 * </ul>
 *
 * <p>{@code TeamState.maxHealth} 는 이 둘을 합친 <b>결과</b>이고, 저장은 되지만 아무도 직접
 * 정하지 않는다.
 *
 * <h2>고정은 기본값을 갈아 끼운다</h2>
 * <p>{@code max_health_lock}(고행자)이 있으면 팀이 정한 기본값 대신 그 값을 쓰고, 증강 보너스는
 * 그 위에 그대로 얹힌다. 예전에는 보너스까지 통째로 무시했지만 2026-10-16 사람 결정으로 바꿨다.
 * 이 우선순위를 정하는 자리는 {@link #effectiveMaxHealth} 하나뿐이다.
 *
 * <h2>속성과 공유 상한을 함께 맞춰야 한다</h2>
 * <p>이 모드의 체력은 {@code TeamState.maxHealth} 를 상한으로 하는 팀 공유 풀이고,
 * {@code StatMirror} 가 그 상한으로 공유 체력을 자른 뒤 팀 전원에게 써 준다. 플레이어 속성만
 * 낮추면 공유 풀은 여전히 20 까지 차오르는데 {@code setHealth} 가 10 에서 잘리므로, 팀은
 * 보이지 않는 체력 10 을 더 갖게 된다. 반대로 속성만 올리면 화면의 칸만 늘고 공유 풀은 그대로다.
 * 그래서 둘을 언제나 함께 맞춘다.
 *
 * <h2>공유 체력 값에는 절대 손대지 않는다</h2>
 * <p>상한만 옮기고 {@code TeamState.health} 는 그대로 둔다. 최대 체력이 20 → 10 으로 줄면
 * {@link MaxHealthAttribute#apply} 가 플레이어의 현재 체력을 10 으로 자르고,
 * {@code StatMirror} 가 새 상한으로 공유 체력을 다시 자른다. 여기서 우리가 공유 체력까지 미리
 * 깎아 두면 <b>같은 감소가 두 번</b> 들어간다. 결과는 언제나 "가득 찬 10" 이거나 "원래 값" 이다.
 *
 * <p><b>그 자르기를 피해로 세면 안 된다.</b> {@code StatMirror} 는 팀원별 체력 감소를 사람 수만큼
 * 합산하므로, 상한이 줄어 잘린 몫까지 세면 <b>한 번의 자름이 인원수만큼 곱해진다.</b> 3인 팀이
 * 체력 18 에서 상한을 잃으면 8 이 세 번 빠져 공유 체력이 0 이 되고 팀이 즉사한다.
 * {@code StatMirror.healthDelta} 가 상한이 줄어 잘린 몫을 빼 준다.
 *
 * <h2>증강을 쓰지 않는 팀이면</h2>
 * <p>{@link #tick} 은 팀마다 {@code ownedPerks} 가 비었는지만 보고 곧바로 빠져나간다. 증강 풀이
 * 비어 있는 서버에서는 1초에 한 번 목록 두 개를 훑는 것이 전부다.
 */
public final class PerkHealthRules {
	/** 점검 주기. 매 틱 볼 필요는 없다. {@code PerkManager} 와 같은 값이다. */
	private static final int CHECK_INTERVAL_TICKS = 20;
	/** {@code MaxHealthAttribute} 와 {@code TeamState} 가 받아들이는 범위와 같은 값이다. */
	private static final double MIN_MAX_HEALTH = 1.0;
	private static final double MAX_MAX_HEALTH = 1024.0;

	private static int tickCounter;

	private PerkHealthRules() {
	}

	/**
	 * 이 팀이 못 박아 둔 최대 체력. 고정 증강이 없으면 비어 있다.
	 *
	 * <p>여러 개를 가졌으면 <b>가장 작은 값</b>이 이긴다.
	 */
	public static OptionalDouble lockedMaxHealth(@Nullable TeamState state) {
		if (state == null || state.ownedPerks.isEmpty()) {
			return OptionalDouble.empty();
		}
		double locked = Double.MAX_VALUE;
		boolean found = false;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof MaxHealthLockEffect lock) {
					locked = Math.min(locked, lock.value());
					found = true;
				}
			}
		}
		return found ? OptionalDouble.of(locked) : OptionalDouble.empty();
	}

	/**
	 * 이 팀의 증강이 기본 최대 체력에 더해 주는 양. 해당 증강이 없으면 0.
	 *
	 * <p>여러 개를 가졌으면 <b>전부 더한다.</b>
	 *
	 * <p>보유 목록을 매번 처음부터 훑는다는 점이 중요하다. 지금 상한에 무언가를 더하는 것이
	 * 아니라 "지금 가진 증강이면 보너스가 얼마인가"를 다시 세는 것이라, 몇 번을 불러도 답이
	 * 같고 증강을 잃으면 그 몫이 저절로 빠진다.
	 *
	 * <h2>예전 형식도 함께 센다</h2>
	 * <p>{@code max_health_bonus} 가 생기기 전에는 이 증강을 {@code attribute} +
	 * {@code minecraft:max_health} + {@code add_value} 로 적었다. 그 정의도 팀 상한을 올리게
	 * 되므로, {@code AttributeEffect} 가 거는 수정자는 상한과 같은 값이 되어
	 * {@code MaxHealthAttribute} 의 덮어쓰기와 부딪히지 않는다.
	 *
	 * <p>배율 연산({@code add_multiplied_*})은 세지 않는다. 팀 기본값에 곱할지 보너스를 더한 뒤에
	 * 곱할지가 정해져 있지 않아 짐작할 수 없다. 그런 정의는 {@code max_health_bonus} 로 고쳐 적어야
	 * 한다.
	 */
	public static double bonusMaxHealth(@Nullable TeamState state) {
		if (state == null || state.ownedPerks.isEmpty()) {
			return 0.0;
		}
		double total = 0.0;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof MaxHealthBonusEffect bonus) {
					total += bonus.amount();
				} else if (effect instanceof AttributeEffect attribute
						&& attribute.isLegacyMaxHealthBonus()) {
					// 예전 형식으로 적힌 설정 파일도 그대로 돌아가야 한다. 아래 주석 참고.
					total += attribute.amount();
				}
			}
		}
		// 「엔드 적응」(0.44.0-dev) — 팀원이 엔드에 있는 동안만. 팀 상한에 한 번 더한다(사람 수와 무관).
		total += DragonPrep.endBonus(state);
		// 세트가 주는 최대 체력도 같은 규칙으로 더한다. 생존 2단계가 이 길을 탄다.
		// 여기서 세지 않으면 max_health_bonus 를 붙여도 MaxHealthAttribute 의 덮어쓰기가
		// 정확히 상쇄해 아무 일도 일어나지 않는다 — 그 함정은 MaxHealthBonusEffect 에 적혀 있다.
		for (PerkEffect effect : PerkSetEffects.activeEffectsOf(state)) {
			if (effect instanceof MaxHealthBonusEffect bonus) {
				total += bonus.amount();
			} else if (effect instanceof AttributeEffect attribute
					&& attribute.isLegacyMaxHealthBonus()) {
				total += attribute.amount();
			}
		}
		return Double.isFinite(total) ? total : 0.0;
	}

	/**
	 * 지금 이 팀에 걸려 있어야 할 최대 체력.
	 *
	 * <p>최대 체력에 대한 판단은 전부 이 한 줄로 모인다. {@code 기본값 + 보너스} 이고, 고정이
	 * 있으면 기본값 자리에 고정값이 들어간다.
	 */
	public static float effectiveMaxHealth(@Nullable TeamState state) {
		if (state == null) {
			return 20.0F;
		}
		OptionalDouble locked = lockedMaxHealth(state);
		double base = locked.isPresent() ? locked.getAsDouble() : state.baseMaxHealth;
		// 「불사의 맹세」를 쓴 팀은 이번 회차 내내 절반이다. 보너스까지 더한 뒤에 곱해야 맹세 뒤에 고른
		// 체력 증강도 함께 절반이 된다 — 「최대 체력이 절반」의 뜻이 그것이다.
		double target = (base + bonusMaxHealth(state)) * DragonPrep.oathScale(state);
		if (!Double.isFinite(target)) {
			return state.maxHealth;
		}
		return (float) Math.max(MIN_MAX_HEALTH, Math.min(MAX_MAX_HEALTH, target));
	}

	/**
	 * 한 사람의 최대 체력을 지금 맞아야 할 값으로 맞춘다.
	 *
	 * <p>{@link MaxHealthLockEffect#apply} 와 {@link MaxHealthBonusEffect#apply} 가 부른다.
	 * 이미 맞아 있으면 아무것도 하지 않는다. {@link MaxHealthAttribute#apply} 는 수정자를 뗐다
	 * 다시 붙이므로, 값이 같은데도 매번 부르면 최대 체력이 한 틱 흔들려 현재 체력이 괜히 깎인다.
	 */
	public static void enforce(@Nullable ServerPlayer player) {
		if (player == null) {
			return;
		}
		TeamState state = TeamLookup.stateOf(player.getUUID());
		if (state == null || state.ownedPerks.isEmpty()) {
			return;
		}
		float target = effectiveMaxHealth(state);
		state.maxHealth = target;
		applyIfDifferent(player, target);
	}

	/**
	 * 1초마다 팀별로 최대 체력을 다시 계산해 맞춘다.
	 *
	 * <p>{@code SharedFateMod} 의 서버 틱에 붙는다. {@code StatMirror} 보다 뒤에 등록되므로,
	 * 명령이나 증강이 상한을 옮긴 틱의 계산은 그대로 지나가고 다음 점검에서 제자리로 돌아온다.
	 * 상한이 잠깐 달랐던 것뿐이라 공유 체력은 늘 새 상한으로 다시 잘린다.
	 */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null) {
			return;
		}
		if (++tickCounter < CHECK_INTERVAL_TICKS) {
			return;
		}
		tickCounter = 0;

		TeamManager manager = TeamManager.get(server);
		for (ShareTeam team : List.copyOf(manager.allTeams())) {
			TeamState state = manager.stateByTeamId(team.teamId());
			if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
				continue;
			}
			// 「엔드 적응」이 보는 값. 최대 체력을 세기 직전에 월드에서 다시 읽는다.
			DragonPrep.refreshEndPresence(server, team, state);
			float target = effectiveMaxHealth(state);
			if (state.maxHealth != target) {
				state.maxHealth = target;
				manager.setDirty();
			}
			for (UUID member : team.members()) {
				applyIfDifferent(server.getPlayerList().getPlayer(member), target);
			}
		}
	}

	/** 지금 최대 체력이 목표와 다를 때만 수정자를 다시 건다. */
	private static void applyIfDifferent(@Nullable ServerPlayer player, float target) {
		if (player == null || player.getMaxHealth() == target) {
			return;
		}
		try {
			MaxHealthAttribute.apply(player, target);
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.warn("최대 체력 증강을 적용하지 못했습니다", error);
		}
	}

	/** 테스트나 서버 종료 때 주기 카운터를 지운다. */
	public static void reset() {
		tickCounter = 0;
	}
}
