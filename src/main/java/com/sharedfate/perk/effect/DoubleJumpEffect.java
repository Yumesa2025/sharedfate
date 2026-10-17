package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import org.jetbrains.annotations.Nullable;

/**
 * 공중에서 한 번 더 뛸 수 있게 만드는 효과.
 *
 * <p>JSON 형식:
 * <pre>
 * { "type": "double_jump" }
 * { "type": "double_jump", "power": 0.714 }
 * </pre>
 *
 * <p>{@code power} 는 공중 점프가 실을 위쪽 속도다. 적지 않으면 {@link #DEFAULT_POWER}를 쓴다.
 * 적었는데 {@link #MIN_POWER}~{@link #MAX_POWER} 밖이면 정의 자체를 버린다.
 *
 * <h2>왜 서버 혼자서는 못 하는가</h2>
 * <p>서버는 "공중에서 점프 키를 눌렀다"는 사실을 알 수 없다. 바닐라는 땅에서 뛴 결과만
 * 위치로 올려보내고, 공중에서 누른 키는 아무 데도 실리지 않는다. 그래서 이 효과만은
 * 클라이언트가 눌린 것을 알아채 스스로 몸을 띄우고 서버에 알린다. 세기와 미는 방향은
 * 클라이언트가 정하지 않으며, 서버가 이 정의에서 읽어 검증한다.
 *
 * <p>{@link #apply}/{@link #remove} 는 아무 일도 하지 않는다. 이 효과는 플레이어에게
 * 붙였다 떼는 것이 아니라 "지금 이 팀이 그 증강을 갖고 있는가"를 그때그때 물어보는 규칙이다.
 * 물어보는 자리는 {@link com.sharedfate.perk.PerkClientRules} 한 곳뿐이고, 증강을 잃으면
 * 물어볼 대상이 사라져 공중 점프도 저절로 막힌다.
 *
 * <h2>낙하 피해는 여기서 다루지 않는다</h2>
 * <p>낙하 피해를 바꾸려면 기존 {@code attribute} 타입에 {@code minecraft:fall_damage_multiplier}
 * 를 건다. 이 효과는 미는 일만 한다. 「허공답보」의 낙하 피해 대가는 2026-10-16 에 걷어냈다.
 */
public final class DoubleJumpEffect implements PerkEffect {
	/**
	 * {@code power} 를 적지 않았을 때 쓰는 값.
	 *
	 * <p>바닐라 점프 힘 {@code LivingEntity.BASE_JUMP_POWER} 0.42 의 1.5배다. 지금 이 타입을
	 * 쓰는 유일한 증강인 「허공답보」의 값과 같아야 한다. 「허공답보」의 값을 바꿀 때는 이 상수도
	 * 함께 바꾼다({@code DefaultPerkPoolValuesTest} 가 둘이 같은지 지킨다).
	 */
	public static final double DEFAULT_POWER = 0.63;
	/** 이보다 약하면 뛴 티가 나지 않아 버그로 오해받는다. */
	public static final double MIN_POWER = 0.1;
	/** 이보다 세면 낙하 피해로 죽거나 청크 밖으로 튀어 나간다. */
	public static final double MAX_POWER = 2.0;

	private final double power;

	public DoubleJumpEffect(double power) {
		this.power = power;
	}

	/** JSON에서 만든다. {@code power} 가 범위를 벗어났으면 경고를 남기고 null. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		Double raw = PerkEffectType.readDouble(json, "power");
		if (raw == null) {
			// 적지 않은 것은 잘못이 아니다.
			return new DoubleJumpEffect(DEFAULT_POWER);
		}
		if (raw < MIN_POWER || raw > MAX_POWER) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: double_jump 의 power 가 범위({}~{})를 벗어났습니다 ({})",
					perkId, MIN_POWER, MAX_POWER, raw);
			return null;
		}
		return new DoubleJumpEffect(raw);
	}

	/** 공중 점프가 실을 위쪽 속도. */
	public double power() {
		return power;
	}
}
