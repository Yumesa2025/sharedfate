package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "잠깐 붙였다 정해진 시간 뒤에 떼는" 효과를 관리한다.
 *
 * <p>상태이상은 지속시간을 스스로 세므로 이 관리자가 필요 없다. 문제는 속성 수정자처럼
 * {@link PerkEffect#apply}/{@link PerkEffect#remove} 로만 붙였다 뗄 수 있는 효과다. 바닐라에는
 * "3초 뒤에 이 수정자를 떼라"는 장치가 없어서, 걷어낼 시점을 여기서 대신 세어 준다.
 *
 * <p><b>상태이상으로 대신할 수 없다.</b> 힘은 공격력을 고정값 <b>+3</b>, 나약함은 <b>−4</b>
 * 만큼 움직인다. 비율로 오르내리는 값을 상태이상으로 바꾸면 무기가 셀수록 효과가 묽어지고
 * 약할수록 진해져 의도한 것과 다른 값이 된다.
 *
 * <h2>시각의 기준</h2>
 * <p>{@link com.sharedfate.perk.PeriodicPerkManager} 와 달리 오버월드의 게임 시간을 쓰지 않는다.
 * 강제 증강 선택 중에는 시간이 얼어 게임 시간이 멈추는데, 그때 여기 남아 있던 효과가 영영
 * 걷히지 않으면 곤란하다. {@link #tick} 이 불릴 때마다 1씩 올리는 자체 카운터를 쓴다. 이
 * 카운터는 {@code END_SERVER_TICK} 을 타므로 시간이 멈춰 있어도 계속 흐른다.
 *
 * <p>카운터는 서버를 껐다 켜면 0 으로 돌아가지만, {@link #reset} 이 남은 예약을 함께 비우므로
 * 어긋날 여지가 없다. 임시 수정자는 저장되지 않는 {@code transient} 라 서버가 내려가면 어차피
 * 사라진다.
 *
 * <h2>같은 효과가 연달아 발동할 때</h2>
 * <p>불에 타는 동안처럼 방아쇠가 매 틱 당겨질 수 있다. 그때마다 뗐다 붙이면 초당 20번
 * 수정자가 흔들린다. 그래서 이미 걸려 있는 효과는 다시 붙이지 않고 <b>만료 시각만 미룬다</b>.
 * 결과는 "마지막 발동으로부터 지속시간만큼"이고, 손대는 횟수는 처음과 끝 두 번뿐이다.
 */
public final class TimedPerkEffects {
	/**
	 * 예약 하나를 가리키는 열쇠.
	 *
	 * <p>{@link PerkEffect} 는 {@code equals} 를 재정의하지 않으므로 객체 동일성으로 비교된다.
	 * 효과 객체는 증강 정의 하나당 하나뿐이라, "누구에게 걸린 어느 효과인가"가 정확히 구분된다.
	 */
	private record Key(UUID player, PerkEffect effect) {
	}

	private static final Map<Key, Long> EXPIRY = new ConcurrentHashMap<>();

	/** 자체 틱 카운터. {@link #tick} 이 부를 때마다 1씩 오른다. */
	private static volatile long now;

	private static boolean warned;

	private TimedPerkEffects() {
	}

	/** 지금까지 센 틱 수. */
	public static long currentTick() {
		return now;
	}

	/**
	 * 효과를 붙이고 {@code durationTicks} 뒤에 떼도록 예약한다.
	 *
	 * <p>이미 걸려 있으면 다시 붙이지 않고 만료 시각만 미룬다.
	 *
	 * @return 이번에 새로 붙였으면 {@code true}. 이미 걸려 있어 시각만 미뤘으면 {@code false}
	 */
	public static boolean grant(@Nullable ServerPlayer player, @Nullable PerkEffect effect,
			int durationTicks) {
		if (player == null || effect == null || durationTicks <= 0) {
			return false;
		}
		Key key = new Key(player.getUUID(), effect);
		boolean fresh = EXPIRY.put(key, now + durationTicks) == null;
		if (fresh) {
			try {
				effect.apply(player);
			} catch (RuntimeException error) {
				// 붙이지 못했으면 예약도 지운다. 떼어 낼 것이 없기 때문이다.
				EXPIRY.remove(key);
				SharedFateMod.LOGGER.warn("잠깐 걸 효과를 붙이지 못했습니다", error);
				return false;
			}
		}
		return fresh;
	}

	/**
	 * 예약을 취소하고 지금 곧바로 걷어낸다.
	 *
	 * <p>증강을 잃는 자리에서 부른다. 걸려 있지 않았으면 아무 일도 하지 않는다.
	 */
	public static void cancel(@Nullable ServerPlayer player, @Nullable PerkEffect effect) {
		if (player == null || effect == null) {
			return;
		}
		if (EXPIRY.remove(new Key(player.getUUID(), effect)) != null) {
			safeRemove(effect, player);
		}
	}

	/**
	 * 이 사람에게 걸린 예약을 모두 취소하고 곧바로 걷어낸다.
	 *
	 * <p>접속을 끊을 때·부활할 때·팀에서 빠질 때 부른다. 예약이 남아 있으면 그 사이 몸에서
	 * 수정자가 사라졌는데도 {@link #grant} 가 "이미 걸려 있다"고 보고 시각만 미뤄, 다음 발동이
	 * 아무것도 붙이지 않는다. 걷어내는 쪽은 언제나 안전하므로 몸에 남아 있든 없든 떼어 둔다.
	 */
	public static void revokeAll(@Nullable ServerPlayer player) {
		if (player == null || EXPIRY.isEmpty()) {
			return;
		}
		UUID id = player.getUUID();
		Iterator<Map.Entry<Key, Long>> entries = EXPIRY.entrySet().iterator();
		while (entries.hasNext()) {
			Map.Entry<Key, Long> entry = entries.next();
			if (!entry.getKey().player().equals(id)) {
				continue;
			}
			entries.remove();
			safeRemove(entry.getKey().effect(), player);
		}
	}

	/**
	 * 한 틱 지났다. 만료된 예약을 걷어낸다.
	 *
	 * <p>서버 틱 한가운데서 불리므로 어떤 예외도 밖으로 내보내지 않는다. 예약이 하나도 없으면
	 * 카운터만 올리고 끝난다. 증강 풀이 비어 있는 서버에서는 언제나 이 경로다.
	 */
	public static void tick(@Nullable MinecraftServer server) {
		long time = ++now;
		if (server == null || EXPIRY.isEmpty()) {
			return;
		}
		try {
			Iterator<Map.Entry<Key, Long>> entries = EXPIRY.entrySet().iterator();
			while (entries.hasNext()) {
				Map.Entry<Key, Long> entry = entries.next();
				if (entry.getValue() > time) {
					continue;
				}
				entries.remove();
				// 접속을 끊었으면 걷어낼 대상이 없다. 임시 수정자는 저장되지 않으므로 그냥 둔다.
				ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey().player());
				if (player != null) {
					safeRemove(entry.getKey().effect(), player);
				}
			}
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	/** 서버가 멈출 때 남은 예약을 비운다. 다음 월드로 넘어가지 않게 한다. */
	public static void reset() {
		EXPIRY.clear();
		now = 0;
		warned = false;
	}

	/** 지금 걸려 있는 예약 수. 테스트와 진단용. */
	static int pendingCount() {
		return EXPIRY.size();
	}

	private static void safeRemove(PerkEffect effect, ServerPlayer player) {
		try {
			effect.remove(player);
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.warn("잠깐 걸어 둔 효과를 걷어내지 못했습니다", error);
		}
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn(
				"잠깐 거는 증강 효과를 처리하지 못해 이번 틱은 건너뜁니다. 이 경고는 한 번만 남습니다.", error);
	}
}
