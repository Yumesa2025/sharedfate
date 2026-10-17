package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.PerkHealthRules;
import net.minecraft.server.level.ServerPlayer;

/**
 * 팀의 최대 체력을 정해진 만큼 <b>더한다.</b>
 *
 * <p>정의는 {@code { "type": "max_health_bonus", "amount": 6.0 }} 하나뿐이다. 실버
 * "뚝배기 대신 피통"(+6)과 프리즘 "장님 거인"(+20)이 이 타입을 쓴다.
 *
 * <h2>왜 {@code attribute} 로는 안 되는가</h2>
 * <p>이 모드의 체력은 {@code TeamState.maxHealth} 를 상한으로 하는 팀 공유 풀이고,
 * {@code MaxHealthAttribute} 가 접속·부활 때마다 팀원의 {@code max_health} 속성을 그 상한과
 * <b>똑같아지도록</b> 배율 수정자로 덮어쓴다. 그래서 {@code attribute} 로 {@code max_health}
 * 에 +6 을 걸어 봐야, 곧이어 도는 그 덮어쓰기가 정확히 그 +6 을 상쇄해 버린다. 속성만 올려서는
 * 공유 상한이 따라 오르지 않으므로 애초에 반쪽짜리이기도 하다.
 *
 * <p>그래서 이 타입은 속성 수정자를 직접 걸지 않는다. "팀의 상한을 얼마나 올릴 것인가"라는
 * 숫자만 들고 있고, 그 숫자를 실제 상한으로 바꾸는 일은 전부 {@link PerkHealthRules} 가 한다.
 *
 * <h2>기본값과 보너스를 어떻게 구분하는가</h2>
 * <p>{@code TeamState.baseMaxHealth} 가 "팀이 정한 값"({@code /shareteam health} 또는 설정
 * 기본값)을 따로 들고 있고, {@code TeamState.maxHealth} 는 언제나 <b>계산된 결과</b>다.
 * 그래서 이 보너스를 몇 번을 다시 적용해도 {@code 기본값 + 보너스} 라는 답은 달라지지 않는다.
 * 보너스를 {@code maxHealth} 에 직접 더했다면 접속할 때마다 상한이 불어났을 것이다.
 *
 * <h2>고정과 함께면</h2>
 * <p>{@code max_health_lock}(고행자)을 함께 가진 팀에서는 기본값 자리에 고정값이 들어가고, 이
 * 보너스는 그 위에 얹힌다. 판정은 {@link PerkHealthRules#effectiveMaxHealth} 한 곳에서만 내린다.
 */
public final class MaxHealthBonusEffect implements PerkEffect {
	/** 터무니없는 값으로 게임을 깨뜨리지 않도록 두는 상한. {@code AttributeEffect} 와 같은 값이다. */
	static final double MAX_ABS_AMOUNT = 1024.0;

	private final float amount;

	public MaxHealthBonusEffect(float amount) {
		this.amount = amount;
	}

	/** JSON에서 만든다. 정의가 잘못됐으면 경고를 남기고 null. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		Double amount = PerkEffectType.readDouble(json, "amount");
		if (amount == null || amount == 0.0 || Math.abs(amount) > MAX_ABS_AMOUNT) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: max_health_bonus 의 amount 가 없거나 범위를 벗어났습니다 ({})", perkId, amount);
			return null;
		}
		return new MaxHealthBonusEffect(amount.floatValue());
	}

	/** 팀의 기본 최대 체력에 더할 값. 음수면 깎는다. */
	public float amount() {
		return amount;
	}

	/**
	 * 고른 즉시, 그리고 접속·부활할 때마다 한 번씩 맞춘다.
	 *
	 * <p>여러 번 불려도 안전하다. {@link PerkHealthRules#enforce} 는 지금 보유한 증강 전체를
	 * 다시 세어 {@code 기본값 + 보너스} 를 처음부터 계산하지, 지금 상한에 무언가를 더하지 않는다.
	 */
	@Override
	public void apply(ServerPlayer player) {
		PerkHealthRules.enforce(player);
	}

	/**
	 * 증강을 잃었을 때도 같은 계산을 한 번 더 돌린다.
	 *
	 * <p>보유 목록에서 빠진 뒤에 불리므로 이 보너스는 자연히 셈에서 빠지고 상한이 원래대로
	 * 돌아온다. 여기서 상한을 직접 빼지 않는 것이 중요하다. "얼마를 뺄 것인가"를 짐작하는 순간
	 * {@code /shareteam health} 로 정해 둔 값이 어긋나기 시작한다.
	 *
	 * <p>상한이 줄어든 뒤 공유 체력을 손대지 않는 이유는 {@link PerkHealthRules} 에 있다.
	 */
	@Override
	public void remove(ServerPlayer player) {
		PerkHealthRules.enforce(player);
	}
}
