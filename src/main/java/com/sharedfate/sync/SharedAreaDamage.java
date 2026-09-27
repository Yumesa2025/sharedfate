package com.sharedfate.sync;

import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 하나의 광역 공격이 여러 팀원에게 닿았을 때, 공유 풀에서는 한 번만 깎이게 한다.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>{@link StatMirror#fold} 는 팀원별 체력 손실을 <b>그대로 합산</b>한다. 그 판단 자체는 옳다 —
 * 아라가 좀비에게, 보라가 스켈레톤에게 같은 틱에 맞았다면 팀은 진짜로 두 번 맞은 것이다.
 * 반대로 공유된 상태이상 하나가 전원에게 똑같이 일으키는 피해는 {@link SharedEffectDamage} 가
 * 대표 한 명만 남기고 막는다.
 *
 * <p><b>문제는 드래곤 날갯짓이 그 둘 사이에 있다는 것이다.</b> 몸통 접촉은 5, 머리는 10 인데
 * 공유 체력은 20 이다. 네 명이 함께 붙은 상태로 한 번 날갯짓하면 5×4 = 20 — <b>한 번의 공격에
 * 팀 체력 전부가 사라진다.</b> 좀비 두 마리와도, 공유된 독과도 다른 세 번째 경우다.
 *
 * <p>이 상태로 드래곤에 광역 패턴을 얹으면 피해를 아무리 낮춰도 네 명이 함께 맞는 순간 전멸이라
 * 설계가 성립하지 않는다. 그래서 <b>같은 공격원에서 같은 틱에 온 피해는 첫 한 번만</b> 공유 풀에
 * 반영한다.
 *
 * <h2>무엇을 같은 공격으로 보는가</h2>
 *
 * <p>「가해 개체 + 피해 종류 + 틱」이 모두 같으면 하나의 공격이다.
 *
 * <ul>
 *   <li><b>가해 개체가 있어야 한다.</b> 낙하·용암·질식처럼 때린 개체가 없는 피해는 접지 않는다.
 *       둘이 각자 떨어진 것이지 하나의 공격이 아니기 때문이다.</li>
 *   <li><b>피해 종류가 갈리면 다른 공격이다.</b> 같은 드래곤이라도 날개에 닿은 것과 브레스를
 *       밟은 것은 따로 센다.</li>
 *   <li><b>틱이 넘어가면 다시 센다.</b> 두 번 날갯짓하면 두 번 아파야 한다.</li>
 * </ul>
 *
 * <p>장비도 공유이므로 팀원들의 방어구가 같고, 따라서 누가 대표로 맞든 들어가는 피해가 같다.
 * 「가장 큰 것 하나」를 고를 필요 없이 첫 번째만 통과시켜도 결과가 같다.
 *
 * <h2>무엇을 포기했는가</h2>
 *
 * <p>이 변경은 「네 명이 뭉쳐 있으면 위험하다」는 긴장을 없앤다. 그것을 이 모드의 맛으로 볼
 * 여지도 있었으나, 광역 패턴을 쓰기로 한 이상 양립할 수 없어 이쪽을 골랐다. 드래곤뿐 아니라
 * 크리퍼·위더 폭발 같은 기존 광역 사고에도 함께 듣는다.
 */
public final class SharedAreaDamage {
	/** 이번 틱에 이미 공유 풀을 깎은 공격들. */
	private static final Set<Key> SEEN_THIS_TICK = new HashSet<>();
	/** {@link #SEEN_THIS_TICK} 이 가리키는 틱. 넘어가면 통째로 비운다. */
	private static long seenTick = Long.MIN_VALUE;

	private record Key(UUID teamId, UUID attackerId, String damageTypeId) {
	}

	private SharedAreaDamage() {
	}

	/**
	 * 지금 들어온 피해가 <b>같은 틱에 이미 공유 풀을 깎은 공격</b>의 두 번째 이후 몫인가.
	 *
	 * <p>{@code hurtServer} 진입 시점마다 불린다. 참이면 호출자가 피해를 통째로 버린다.
	 *
	 * <p>판정이 서려면 넷이 모두 있어야 한다 — 피해자가 팀원이고, 때린 개체가 있고, 피해 종류를
	 * 알 수 있고, 월드가 있어야 한다. 하나라도 없으면 거짓을 돌려줘 예전처럼 합산된다.
	 */
	public static boolean isDuplicateAreaDamage(@Nullable LivingEntity victim, DamageSource source) {
		if (!(victim instanceof ServerPlayer player)) {
			return false;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}
		ShareTeam team = TeamManager.get(server).teamOf(player.getUUID());
		if (team == null) {
			return false;
		}
		Entity attacker = source.getEntity();
		if (attacker == null) {
			return false;
		}
		return isDuplicateAreaDamage(team.teamId(), attacker.getUUID(),
				source.typeHolder().getRegisteredName(), player.level().getGameTime());
	}

	/**
	 * 판정 본체. 월드 없이 시험할 수 있도록 값만 받는다.
	 *
	 * @param teamId       피해자의 팀. {@code null} 이면 판정하지 않는다
	 * @param attackerId   때린 개체. {@code null} 이면 판정하지 않는다
	 * @param damageTypeId 피해 종류의 등록 이름
	 * @param gameTime     지금 틱
	 */
	static boolean isDuplicateAreaDamage(@Nullable UUID teamId, @Nullable UUID attackerId,
			String damageTypeId, long gameTime) {
		if (teamId == null || attackerId == null) {
			return false;
		}
		if (gameTime != seenTick) {
			SEEN_THIS_TICK.clear();
			seenTick = gameTime;
		}
		// add 가 거짓이면 이번 틱에 같은 공격이 이미 공유 풀을 깎았다는 뜻이다.
		return !SEEN_THIS_TICK.add(new Key(teamId, attackerId, damageTypeId));
	}

	/** 시험용. 지난 틱 기록이 쌓이지 않는지 본다. */
	static int trackedKeyCount() {
		return SEEN_THIS_TICK.size();
	}

	static void clearState() {
		SEEN_THIS_TICK.clear();
		seenTick = Long.MIN_VALUE;
	}
}
