package com.sharedfate.sync;

import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * 팀원 하나가 맞으면 나머지 팀원 화면에도 피격 연출(피격음·붉은 번쩍임·화면 기울기)을 띄운다.
 * 체력이 공유라 「누가 맞아 내 체력이 줄었는지」를 몸으로 알게 하려는 것이다.
 *
 * <p>{@code ServerLivingEntityEvents.AFTER_DAMAGE} 에 붙는다. 보내는 것은 바닐라
 * {@code ClientboundDamageEventPacket} 이고 대상은 <b>받는 팀원 자기 자신</b>이라, 받은 클라이언트가
 * {@code LivingEntity.handleDamageEvent} 로 스스로 맞은 것처럼 연출한다. 체력은 움직이지 않는다.
 * 맞은 사람 본인은 바닐라가 이미 보냈으므로 뺀다.
 *
 * <h2>「완충」과 얽히는 두 가지</h2>
 * <p>Fabric 은 {@code AFTER_DAMAGE} 를 {@code hurtServer} <b>꼬리</b>에서 부른다. 바닐라의 연출
 * 깃발과 상관없이 돈다는 뜻이다.
 *
 * <ol>
 *   <li><b>나뉜 몫마다 돌았다.</b> {@code SpreadDamageManager.deliver} 가 몫을 바닐라의 「쿨타임
 *       안 추가 피해」 갈래로 넣어 맞은 사람 쪽 연출은 빠졌지만, 이 사건은 꼬리에서 그대로 불려
 *       팀원에게 몫마다 피격음이 났다. 둘이서 한 판에서 사람이 「아직도 여러 번 피격음이
 *       난다」고 한 원인이 이것이다.</li>
 *   <li><b>처음 맞은 순간에는 안 돌았다.</b> 미룬 피해는 {@code hurtServer} 에 0 으로 넘어가서
 *       {@code damageTaken} 이 0 이다. 그래서 팀원에게는 맞은 순간엔 아무것도 없었다.</li>
 * </ol>
 *
 * <p>둘 다 {@link #shouldEcho} 한 곳에서 가른다. 몫을 넣는 중이면 보내지 않고, 이번 호출에서
 * 미룬 양({@link SpreadDamageManager#takeDeferredHit})이 있으면 피해가 0 이어도 보낸다. 완충이
 * 없는 팀의 피해는 두 값이 늘 거짓·0 이라 예전과 똑같이 판정된다.
 */
public final class SharedHurtFeedback {
	private SharedHurtFeedback() {
	}

	public static void onDamage(LivingEntity entity, DamageSource source,
			float baseDamageTaken, float damageTaken, boolean blocked) {
		// 미룬 양 표시는 아래에서 일찍 빠져나가더라도 남지 않게 맨 앞에서 꺼낸다.
		float deferred = SpreadDamageManager.takeDeferredHit(entity.getUUID());
		if (!(entity instanceof ServerPlayer victim) || !shouldEcho(blocked, damageTaken, deferred,
				SpreadDamageManager.isDeliveringSlice())) {
			return;
		}
		TeamManager manager = TeamManager.get(victim.level().getServer());
		ShareTeam team = manager.teamOf(victim.getUUID());
		if (team == null) {
			return;
		}

		DamageSource visualSource = new DamageSource(source.typeHolder());
		for (UUID member : team.members()) {
			if (member.equals(victim.getUUID())) {
				continue;
			}
			ServerPlayer teammate = victim.level().getServer().getPlayerList().getPlayer(member);
			if (teammate != null && !teammate.isRemoved() && !teammate.isDeadOrDying()) {
				teammate.connection.send(new ClientboundDamageEventPacket(teammate, visualSource));
			}
		}
	}

	/**
	 * 이번 피해를 팀원에게 피격 연출로 뿌릴 것인가. 월드도 엔티티도 보지 않는 순수 판정이다.
	 *
	 * <p>화면의 피격 알림({@code StatMirror.shouldAlert})도 이 판정을 같이 쓴다. 「완충」 몫에서는
	 * 안 뜨고 처음 맞은 순간에 한 번 뜬다는 규칙이 두 벌로 갈라지지 않게 하려는 것이다.
	 *
	 * @param blocked          방패로 막았는가. 막은 피해는 바닐라도 피격 연출 대신 방패 소리를 낸다
	 * @param damageTaken      {@code hurtServer} 가 실제로 다룬 피해량. 완충이 미룬 피해면 0 이다
	 * @param deferredAmount   이번 호출에서 완충이 미룬 양. 미루지 않았으면 0
	 * @param spreadSlice      완충이 미뤄 둔 몫을 넣는 중인가. 참이면 <b>무조건</b> 보내지 않는다 —
	 *                         피격 연출은 처음 맞은 그 한 번이면 된다
	 */
	static boolean shouldEcho(boolean blocked, float damageTaken, float deferredAmount,
			boolean spreadSlice) {
		if (spreadSlice || blocked) {
			return false;
		}
		return damageTaken > 0.0F || deferredAmount > 0.0F;
	}
}
