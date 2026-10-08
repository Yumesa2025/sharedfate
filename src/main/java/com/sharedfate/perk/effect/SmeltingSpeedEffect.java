package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import org.jetbrains.annotations.Nullable;

/**
 * 화로·용광로·훈연기가 굽는 속도에 배율을 건다.
 *
 * <pre>{@code
 * { "type": "smelting_speed", "multiplier": 2.0 }
 * }</pre>
 *
 * <p>{@code multiplier} 가 2.0 이면 <b>굽는 데 걸리는 시간이 절반</b>이다. 세 종류를 따로
 * 적지 않는다 — 셋 다 {@code AbstractFurnaceBlockEntity} 를 물려받고 굽는 시간도 그 한
 * 클래스에서 나오므로, 하나를 건드리면 셋이 함께 바뀐다.
 *
 * <h2>연료는 평소대로 탄다 — 그래서 한 덩이로 두 배를 굽는다</h2>
 * <p>사람이 정하지 않은 자리라 여기서 정했고, 근거는 셋이다.
 *
 * <ol>
 *   <li><b>바닐라가 그렇게 한다.</b> 26.3 의 {@code AbstractFurnaceBlockEntity} 에는
 *       {@code speedMultiplier} 필드가 이미 있고({@code getSpeedMultiplier} 가 연료의
 *       {@code minecraft:cooking_fuel} 성분에서 읽어 온다), 그 값은
 *       {@code getTotalCookTime} 에서 <b>굽는 시간만</b> 나눈다. 연료가 타는 시간을 정하는
 *       {@code getBurnDuration} 은 전혀 건드리지 않는다. 즉 「빨리 굽는다 = 같은 연료로 더
 *       많이 굽는다」가 이 판의 규칙이고, 우리가 새 규칙을 만들 이유가 없다.</li>
 *   <li><b>구현이 한 자리로 끝난다.</b> 연료 소모까지 2배로 하려면 {@code serverTick} 의
 *       {@code litTimeRemaining} 감소를 따로 잡아야 하는데, 그 자리는 「불이 켜져 있는가」를
 *       블록 상태와 맞추는 코드와 얽혀 있어 손대면 화로가 꺼진 채로 굽는 등의 어긋남이 난다.</li>
 *   <li><b>세트의 뜻과 맞는다.</b> 「개척」은 밖에 오래 나가 있게 해 주는 세트다. 연료를 두
 *       배로 먹으면 그만큼 자주 돌아와야 해서 세트가 하려는 일을 스스로 깎는다.</li>
 * </ol>
 *
 * <h2>이 클래스는 아무것도 하지 않는다</h2>
 * <p>「얼마를 곱하는가」만 든 자료 그릇이다. {@link #apply}/{@link #remove} 가 비어 있는 것은
 * 사람에게 붙였다 뗄 수 있는 것이 아니라, 화로가 굽는 시간을 잴 때 조회하는 값이기 때문이다.
 * 실제로 곱하는 자리는 {@link com.sharedfate.perk.PerkSmelting} 과 그것을 부르는
 * {@code AbstractFurnaceCookTimeMixin} 이다.
 */
public final class SmeltingSpeedEffect implements PerkEffect {
	/** 배율 하한. 1 보다 작으면 느려지는 정의인데, 그것도 쓸 수 있게 열어 둔다. */
	public static final double MIN_MULTIPLIER = 0.1;

	/** 배율 상한. 이보다 크면 굽는 시간이 1틱으로 눌려 화로가 사실상 사라진다. */
	public static final double MAX_MULTIPLIER = 20.0;

	private final double multiplier;

	public SmeltingSpeedEffect(double multiplier) {
		this.multiplier = multiplier;
	}

	/** JSON 에서 만든다. 정의가 잘못됐으면 경고를 남기고 {@code null}. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		Double multiplier = PerkEffectType.readDouble(json, "multiplier");
		if (multiplier == null || multiplier < MIN_MULTIPLIER || multiplier > MAX_MULTIPLIER) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: smelting_speed 의 multiplier 가 없거나 {}~{} 범위를 벗어났습니다 ({})",
					perkId, MIN_MULTIPLIER, MAX_MULTIPLIER, multiplier);
			return null;
		}
		return new SmeltingSpeedEffect(multiplier);
	}

	/** 정의에 적힌 배율. */
	public double multiplier() {
		return multiplier;
	}

	/**
	 * 안전한 범위로 자른 배율.
	 *
	 * <p>값을 못 믿을 때 <b>1.0</b> 을 돌려준다. 0 을 돌려주면 굽는 시간을 0 으로 나누게 된다.
	 */
	public double multiplierFor() {
		if (!Double.isFinite(multiplier) || multiplier <= 0.0) {
			return 1.0;
		}
		return Math.max(MIN_MULTIPLIER, Math.min(MAX_MULTIPLIER, multiplier));
	}
}
