package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.PerkHealthRules;
import net.minecraft.server.level.ServerPlayer;

/**
 * 팀의 기본 최대 체력을 정해진 값으로 못 박는다. 증강 보너스는 그 위에 얹힌다.
 *
 * <p>정의는 {@code { "type": "max_health_lock", "value": 10.0 }} 하나뿐이다. 프리즘 1 고행자가
 * "허기가 떨어지지 않는다"의 대가로 이 타입을 쓴다.
 *
 * <h2>기본값을 갈아 끼운다</h2>
 * <p>{@code /shareteam health} 로 정한 값 대신 {@code value} 가 기본값이 된다. 최대 체력을 올리는
 * 다른 증강({@code max_health_bonus})은 그 위에 그대로 얹힌다 — 예전에는 그것까지 무시했지만
 * 2026-10-16 사람 결정으로 「다른 증강으로도 오르지 않는다」를 걷어냈다. 그 우선순위를 정하는 자리는
 * {@link PerkHealthRules#effectiveMaxHealth} 하나뿐이다. 그래서 한 번 붙이고 끝내지 않고
 * {@link PerkHealthRules} 가 1초마다 다시 확인해 되돌린다. 다른 곳에서 값을 바꿀 수 있는 이상,
 * 붙이는 시점 한 번만으로는 "고정"이 되지 않는다.
 *
 * <h2>팀 공유 체력과 함께 움직여야 한다</h2>
 * <p>이 모드의 체력은 {@code TeamState.maxHealth} 를 상한으로 하는 공유 풀이다. 플레이어의
 * 속성만 10 으로 낮추고 공유 상한을 20 그대로 두면, 팀은 여전히 20 만큼 맞을 수 있는데 화면에는
 * 10 칸만 보이는 상태가 된다. 그래서 {@link PerkHealthRules} 는 속성과
 * {@code TeamState.maxHealth} 를 <b>함께</b> 맞춘다. 자세한 이유와, 공유 체력 값을 직접
 * 건드리면 왜 안 되는지는 그쪽에 있다.
 *
 * <h2>회차가 끝나면 돌아온다</h2>
 * <p>속성 수정자는 임시(transient)라 저장되지 않고, 전멸로 월드가 새로 만들어지면
 * {@code TeamRosterStore} 가 팀 상태를 {@code config.sharedMaxHealth} 로 새로 만든다. 보유 증강도
 * 함께 비므로 이 효과는 그 시점에 사라지고 최대 체력은 원래 값으로 돌아온다.
 */
public final class MaxHealthLockEffect implements PerkEffect {
	/** 0 이하로 고정하면 접속하자마자 죽는다. */
	static final double MIN_VALUE = 1.0;
	/** {@code MaxHealthAttribute} 와 {@code TeamState} 가 받아들이는 상한과 같은 값이다. */
	static final double MAX_VALUE = 1024.0;

	private final float value;

	public MaxHealthLockEffect(float value) {
		this.value = value;
	}

	/** JSON에서 만든다. 정의가 잘못됐으면 경고를 남기고 null. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		Double value = PerkEffectType.readDouble(json, "value");
		if (value == null || value < MIN_VALUE || value > MAX_VALUE) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: max_health_lock 의 value 가 없거나 범위를 벗어났습니다 ({})", perkId, value);
			return null;
		}
		return new MaxHealthLockEffect(value.floatValue());
	}

	/** 못 박을 최대 체력. */
	public float value() {
		return value;
	}

	/**
	 * 고른 즉시, 그리고 접속·부활할 때마다 한 번씩 맞춘다.
	 *
	 * <p>여기서 끝나지 않는다. 나중에 고른 {@code max_health_bonus} 증강이 상한을 다시 옮길 수
	 * 있고, 명령으로도 바뀔 수 있다. 그 뒤처리는 {@link PerkHealthRules} 의 주기 점검이
	 * 맡는다.
	 */
	@Override
	public void apply(ServerPlayer player) {
		PerkHealthRules.enforce(player);
	}

	/**
	 * 일부러 아무것도 하지 않는다.
	 *
	 * <p>최상위 증강 효과는 걷어내는 경로가 없다. 증강을 잃는 유일한 길인 회차 리셋은 팀 상태를
	 * 통째로 새로 만들면서 최대 체력을 설정값으로 되돌린다. 여기서 "원래 값"을 짐작해 되돌리면
	 * {@code /shareteam health} 로 정해 둔 값을 오히려 지워 버린다.
	 */
	@Override
	public void remove(ServerPlayer player) {
	}
}
