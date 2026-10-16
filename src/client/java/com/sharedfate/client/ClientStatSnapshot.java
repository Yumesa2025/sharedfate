package com.sharedfate.client;

import com.sharedfate.net.StatSnapshotPayload;
import org.jetbrains.annotations.Nullable;

/**
 * 클라이언트가 보관하는 「서버만 아는 능력치」.
 *
 * <p>{@link StatSnapshotPayload} 로 갱신되며 월드에서 나가면 {@link #clear()} 로 비운다.
 * 공격력·받는 피해 배율·몹 배율은 바닐라 속성 동기화에 실려 오지 않아, 화면이 플레이어의
 * 속성에서 읽지 못하고 여기서 읽는다.
 *
 * <h2>서버가 말해 주기 전에는 아무 줄도 그리지 않는다</h2>
 * <p>{@link #known()} 이 거짓인 동안 화면은 이 값들로 만드는 줄 자체를 건너뛴다. 맨손 기본값
 * 1.0 이나 배율 100% 를 대신 그리면 <b>없는 사실을 만들어 내는</b> 셈이다. 값은 접속하고
 * 늦어야 몇 틱 안에 오므로 창을 열었을 때 비어 있는 일은 실제로는 없다.
 *
 * <p>읽는 자리는 화면 그리기이고 쓰는 자리는 패킷 수신인데, 수신 쪽을
 * {@code client.execute(...)} 로 클라이언트 본 스레드에 올려 두므로 둘은 같은 스레드다.
 */
public final class ClientStatSnapshot {
	private static boolean known;
	private static double attackDamageBase;
	private static double attackDamageCurrent;
	private static double damageTaken = 1.0;
	private static double mobHealth = 1.0;
	private static double mobDamage = 1.0;
	/** 「완충」이 앞으로 깎을 체력. 체력바가 곧 깎일 만큼을 겹쳐 그린다. 없으면 0. */
	private static float spreadPending;

	private ClientStatSnapshot() {
	}

	/** {@link StatSnapshotPayload} 를 받았을 때 부른다. */
	public static void update(@Nullable StatSnapshotPayload payload) {
		if (payload == null) {
			clear();
			return;
		}
		known = true;
		attackDamageBase = payload.attackDamageBase();
		attackDamageCurrent = payload.attackDamageCurrent();
		damageTaken = payload.damageTaken();
		mobHealth = payload.mobHealth();
		mobDamage = payload.mobDamage();
		spreadPending = payload.spreadPending();
	}

	/** 월드에서 나갈 때 부른다. */
	public static void clear() {
		known = false;
		attackDamageBase = 0.0;
		attackDamageCurrent = 0.0;
		damageTaken = 1.0;
		mobHealth = 1.0;
		mobDamage = 1.0;
		spreadPending = 0.0F;
	}

	/** 서버가 값을 알려 준 적이 있는가. 거짓이면 화면은 이 값들의 줄을 그리지 않는다. */
	public static boolean known() {
		return known;
	}

	/** 공격력의 바닐라 기본값. 플레이어는 1.0 이다. */
	public static double attackDamageBase() {
		return attackDamageBase;
	}

	/** 공격력. 증강과 지금 든 무기까지 얹힌 값이다. */
	public static double attackDamageCurrent() {
		return attackDamageCurrent;
	}

	/** 받는 피해 배율. 1.0 이면 아무것도 안 걸렸다. */
	public static double damageTaken() {
		return damageTaken;
	}

	/** 적대적 몹의 최대 체력 배율. */
	public static double mobHealth() {
		return mobHealth;
	}

	/** 적대적 몹이 주는 피해의 배율. */
	public static double mobDamage() {
		return mobDamage;
	}

	/**
	 * 「완충」으로 미뤄 둔 몫이 앞으로 깎을 체력(흡수 포함). 방어구 계산을 지난 예상치다.
	 * 나누어 받는 중이 아니거나 서버가 아직 말해 주지 않았으면 0.
	 */
	public static float spreadPending() {
		return spreadPending;
	}
}
