package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;

/**
 * 받는 피해가 즉시 들어오지 않고 여러 초에 걸쳐 나뉘어 들어오게 한다. 프리즘 「완충」의 알맹이다.
 *
 * <p>JSON 형식:
 * <pre>
 * { "type": "spread_damage", "seconds": 4 }
 * </pre>
 *
 * <p>{@code seconds} 는 생략할 수 있고, 생략하면 {@link #DEFAULT_SECONDS} 다. 범위를 벗어난 값은
 * 정의를 버리지 않고 {@link #MIN_SECONDS}~{@link #MAX_SECONDS} 안으로 자른다. 초 하나가 틀렸다고
 * 증강 전체를 풀에서 빼면 손해가 더 크기 때문이다.
 *
 * <h2>대가가 함께 붙어 있다</h2>
 * <p>나뉘어 들어오는 동안에는 <b>허기로 차는 자연 회복이 되지 않는다</b>. 미뤄 둔 몫을 저절로
 * 차는 체력으로 지워 버리면 「완충」이 피해를 늦추는 증강이 아니라 <b>없애는</b> 증강이 되기
 * 때문이다. 재생 상태이상·금사과처럼 손을 써서 얻는 회복은 막지 않는다 — 사람이 정한 대가가
 * 「나눠 받는 동안 자연 회복이 안 되는 것」이다(2026-10-16). 이 대가는 별도의 효과 타입이 아니라
 * 이 효과 안에 묶여 있다 — 분산이 진행 중일 때만 걸려야 하므로
 * {@link com.sharedfate.perk.effect.NoNaturalRegenEffect} 처럼 「가지고 있으면 항상」인 판정과는
 * 성질이 다르다. 막는 방법은 {@link com.sharedfate.sync.SpreadDamageManager#blocksNaturalRegen}
 * 에 적혀 있다.
 *
 * <p>「완충」은 방어 유형이라 세트 「방어 3단계」를 켠 팀에서는 이 대가가 사라진다. 효과 안에
 * 묶여 있어 {@code drawback} 표시로는 끌 수 없으므로
 * {@link com.sharedfate.perk.PerkDrawbacks#builtInWaived} 로 따로 묻는다.
 *
 * <h2>빠져나갈 길이 하나 있다 — 적을 잡는 것</h2>
 * <p>나뉘어 들어오는 동안 팀원 누군가가 몹을 잡으면 <b>아직 들어오지 않은 몫이 전부 사라진다</b>
 * ({@link com.sharedfate.sync.SpreadDamageManager#clearPending}). 이미 들어간 피해는 그대로다 —
 * 되돌리면 그것은 공짜 회복이고, 피해를 늦추는 증강이 없애는 증강이 된다.
 *
 * <p>이 규칙은 <b>JSON 으로 끄고 켜지 않는다</b>. 자연 회복 금지가 이 효과에 묶여 있는 것과 같은
 * 까닭이다. 「피해를 미뤄 두고, 그동안 자연 회복은 막히며, 적을 잡으면 면제된다」까지가 이 효과
 * 하나가 약속하는 거래라, 조각을 따로 끄면 남는 것은 그냥 손해 보는 증강이다. 조절할 값은
 * {@code seconds} 하나뿐이다.
 *
 * <h2>몫을 나누는 모양</h2>
 * <p>매 틱 조금씩이 아니라 <b>1초에 한 번씩</b>({@link #SLICE_PERIOD_TICKS}) 넣는다. 두 가지
 * 이유가 있다.
 *
 * <ul>
 *   <li>바닐라의 피격 무적시간이 20틱이다. 그보다 촘촘히 넣으면 앞의 몫이 만든 무적시간에
 *       뒤의 몫이 삼켜져, 나눈 합계가 원래 피해보다 작아진다.</li>
 *   <li>피해가 한 번 들어갈 때마다 피격음·붉은 화면과 <b>장비 내구도 소모</b>가 따라온다. 몫을
 *       잘게 쪼갤수록 그 부수 효과가 그대로 곱해진다.</li>
 * </ul>
 *
 * <p>그래서 몫의 개수는 곧 초 수와 같다. 기본값 4초면 1·2·3·4초에 네 번 나뉘어 들어온다.
 *
 * <h2>이 클래스가 하지 않는 일</h2>
 * <p>여기는 값만 들고 있는 자료 그릇이다. 피해를 가로채 미뤄 두고 다시 넣는 일은 전부
 * {@link com.sharedfate.sync.SpreadDamageManager} 가 하고, 가로채는 지점은
 * {@link com.sharedfate.mixin.LivingEntityPerkDamageMixin} 이다.
 */
public final class SpreadDamageEffect implements PerkEffect {
	/** 나누어 넣는 기본 시간(초). */
	public static final int DEFAULT_SECONDS = 4;
	public static final int MIN_SECONDS = 1;
	/**
	 * 나누어 넣는 시간의 상한(초).
	 *
	 * <p>이보다 길면 미뤄 둔 몫이 다음 전투까지 흘러들어, 지금 왜 체력이 줄고 회복이 막히는지
	 * 플레이어가 알 수 없게 된다.
	 */
	public static final int MAX_SECONDS = 10;

	private static final int TICKS_PER_SECOND = 20;

	/**
	 * 몫 하나를 넣는 간격(틱). 1초이자 바닐라 피격 무적시간과 같은 길이다.
	 *
	 * <p>이 값을 줄이면 {@link #sliceCount()} 가 함께 늘어난다. 늘어난 만큼 피격음과 장비 내구도
	 * 소모도 늘어난다는 점을 반드시 같이 봐야 한다.
	 */
	public static final int SLICE_PERIOD_TICKS = 20;

	private final int seconds;

	public SpreadDamageEffect(int seconds) {
		this.seconds = seconds;
	}

	/**
	 * JSON에서 만든다.
	 *
	 * <p>값이 범위를 벗어나면 경고만 남기고 잘라 쓴다. 읽을 수 없는 값이면 기본값으로 물러선다.
	 */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		int seconds = clamp(perkId, PerkEffectType.readInt(json, "seconds", DEFAULT_SECONDS));
		return new SpreadDamageEffect(seconds);
	}

	private static int clamp(String perkId, int value) {
		if (value >= MIN_SECONDS && value <= MAX_SECONDS) {
			return value;
		}
		int cut = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, value));
		SharedFateMod.LOGGER.warn(
				"증강 {}: spread_damage 의 seconds 가 {}~{} 범위를 벗어나 {} 로 자릅니다 ({})",
				perkId, MIN_SECONDS, MAX_SECONDS, cut, value);
		return cut;
	}

	/** 나누어 넣는 시간(초). */
	public int seconds() {
		return seconds;
	}

	/** 나누어 넣는 시간(틱). */
	public int spreadTicks() {
		return seconds * TICKS_PER_SECOND;
	}

	/**
	 * 몇 번에 나누어 넣는가.
	 *
	 * <p>{@link #SLICE_PERIOD_TICKS} 를 고치면 이 값이 함께 따라오도록 나눗셈으로 구한다.
	 * 간격이 시간보다 길어지는 설정에서도 최소 한 번은 넣는다.
	 */
	public int sliceCount() {
		return Math.max(1, spreadTicks() / SLICE_PERIOD_TICKS);
	}
}
