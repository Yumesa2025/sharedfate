package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import org.jetbrains.annotations.Nullable;

/**
 * 골드 「파문」. 팀원이 모두 가까이 뭉쳐 있으면 주기마다 충격파가 퍼져 주변 몹을 밀어낸다.
 * 뭉쳐 있지 않으면 {@code solo_radius} 를 적은 정의에 한해 팀원 각자 주변에 작은 충격파가 인다.
 *
 * <pre>{@code
 * { "type": "shockwave", "distance": 10, "radius": 8, "solo_radius": 2,
 *   "interval_seconds": 10, "strength": 1.2 }
 * }</pre>
 *
 * <h2>필드</h2>
 * <ul>
 *   <li>{@code distance} — 팀원 전원이 이 거리 안에 있어야 터진다(칸). 안 적으면
 *       {@value #DEFAULT_DISTANCE} 다. {@value #MIN_DISTANCE}~{@value #MAX_DISTANCE} 를 벗어나면
 *       경고만 남기고 그 범위로 자른다.</li>
 *   <li>{@code radius} — 충격파가 닿는 반경(칸). 안 적으면 {@value #DEFAULT_RADIUS} 다.
 *       {@value #MIN_RADIUS}~{@value #MAX_RADIUS} 로 자른다.</li>
 *   <li>{@code solo_radius} — <b>뭉쳐 있지 않을 때</b> 접속 중인 팀원 각자를 중심으로 터지는
 *       작은 충격파의 반경(칸). 안 적으면 작은 충격파가 없다(뭉쳐 있지 않으면 아무 일도 없다 —
 *       이 필드가 생기기 전의 정의와 같다). 적으면 {@value #MIN_RADIUS}~{@value #MAX_RADIUS} 로
 *       자른다. 뭉쳐 있을 때는 이 작은 충격파를 따로 터뜨리지 않는다(큰 충격파가 대신한다).
 *       세기와 주기는 {@code radius} 와 같다.</li>
 *   <li>{@code interval_seconds} — 몇 초마다 터지는가. 안 적으면
 *       {@value #DEFAULT_INTERVAL_SECONDS} 다. {@value #MIN_INTERVAL_SECONDS}~
 *       {@value #MAX_INTERVAL_SECONDS} 로 자른다.</li>
 *   <li>{@code strength} — 밀어내는 세기. 안 적으면 {@value #DEFAULT_STRENGTH} 로, 넉백 II 로
 *       맞았을 때와 비슷한 정도다(아래 참고). {@value #MIN_STRENGTH}~{@value #MAX_STRENGTH} 로
 *       자른다.</li>
 * </ul>
 *
 * <p>전부 생략할 수 있고, 하나도 안 적으면 「10칸 안에 뭉쳐 있으면 10초마다 반경 8칸을 세기
 * 1.2 로 밀어낸다」가 된다. 값이 범위를 벗어나도 <b>정의를 버리지 않는다</b> — 숫자 하나가
 * 틀렸다고 골드 증강이 통째로 사라지는 쪽이 손해가 크다. {@link AuraDamageEffect} 와 같은
 * 정책이다.
 *
 * <p>{@code intervalSeconds} 처럼 카멜케이스로 적어도 같게 읽는다. 다른 효과들과 같은 규칙이다.
 *
 * <h2>{@code strength} 의 눈금</h2>
 * <p>이 값은 26.2 의 {@code LivingEntity.knockback(세기, dx, dz, 피해원, 피해량)} 에 그대로
 * 넘어가는 세기다. 바이트코드로 보면 그 안에서 {@code normalize(dx,0,dz).scale(세기)} 를 만들어
 * 이동량에서 빼므로, 세기는 곧 <b>한 틱에 더해지는 수평 속도</b>다. 바닐라가 평범한 근접 타격에
 * 쓰는 값이 0.4 이고({@code dealDefaultKnockback}), 넉백 인챈트는 레벨당 대략 0.5 씩 더 실린다.
 * 그래서 기본값 1.2 는 <b>넉백 II 로 맞은 것과 비슷한 정도</b>이고 평지에서 1칸 남짓 밀린다.
 *
 * <p>실제로 밀리는 거리는 바닥 마찰과 몹의 {@code knockback_resistance} 속성에 따라 달라진다.
 * 저항이 1 인 몹(예: 굳은 상태의 워든류)은 바닐라가 세기를 0 으로 깎아 아예 밀리지 않는다.
 * 그것을 여기서 되돌리지 않는다 — 바닐라의 저항을 무시하면 「밀 수 없는 적」이라는 규칙 자체가
 * 사라진다.
 *
 * <h2>피해는 주지 않는다</h2>
 * <p>「파문」은 <b>밀어내기만</b> 한다. 피해를 주면 프리즘 「살기」({@code aura_damage})와 하는 일이
 * 겹쳐 둘을 함께 가진 팀에서 어느 쪽이 몹을 죽였는지 알 수 없게 되고, 전리품 주인도 흔들린다.
 * 그래서 {@link com.sharedfate.sync.ShockwaveManager} 는 {@code hurtServer} 를 아예 부르지 않고
 * 이동량만 건드린다.
 *
 * <h2>이 클래스가 하지 않는 일</h2>
 * <p>여기는 「얼마나 뭉쳐야, 얼마나 넓게, 얼마마다, 얼마나 세게」만 들고 있는 자료 그릇이다.
 * 지금 뭉쳐 있는지 묻고, 주기를 세고, 몹을 찾아 실제로 밀어내는 일은 전부
 * {@link com.sharedfate.sync.ShockwaveManager} 가 맡는다.
 */
public final class ShockwaveEffect implements PerkEffect {

	/** 1초는 몇 틱인가. */
	private static final int TICKS_PER_SECOND = 20;

	/** 뭉침 판정 거리의 기본값(칸). */
	public static final int DEFAULT_DISTANCE = 10;
	/** 뭉침 판정 거리 하한. 이보다 좁으면 서로 몸이 닿아야 해서 있으나 마나다. */
	public static final int MIN_DISTANCE = 2;
	/**
	 * 뭉침 판정 거리 상한.
	 *
	 * <p>64칸이면 서로 안 보이는 거리라 그 위는 「뭉쳐 있다」는 말이 성립하지 않는다.
	 * 「결속」 증강 중 가장 넉넉한 것(성역 20칸)의 세 배를 넘길 이유도 없다.
	 */
	public static final int MAX_DISTANCE = 64;

	/** 충격파가 닿는 기본 반경(칸). */
	public static final int DEFAULT_RADIUS = 8;
	/** 반경 하한. */
	public static final int MIN_RADIUS = 2;
	/**
	 * 반경 상한.
	 *
	 * <p>반경이 넓어지면 훑어야 하는 구역이 세제곱으로 늘어난다. {@link AuraDamageEffect} 가
	 * 같은 이유로 고른 값과 맞춰 둔다 — 두 곳이 다른 상한을 쓰면 어느 쪽이 기준인지 알 수 없다.
	 */
	public static final int MAX_RADIUS = 32;

	/** 기본 주기(초). */
	public static final int DEFAULT_INTERVAL_SECONDS = 10;
	/**
	 * 주기 하한(초).
	 *
	 * <p>1초보다 잦으면 몹이 계속 공중에 떠 있어 반경 안이 통째로 안전지대가 된다.
	 */
	public static final int MIN_INTERVAL_SECONDS = 1;
	/** 주기 상한(초). 10분을 넘으면 한 회차에 몇 번 볼까 말까라 뜻이 없다. */
	public static final int MAX_INTERVAL_SECONDS = 600;

	/** 기본 세기. 넉백 II 로 맞은 것과 비슷한 정도다. */
	public static final double DEFAULT_STRENGTH = 1.2;
	/** 세기 하한. 이보다 약하면 밀렸는지 눈으로 알 수 없다. */
	public static final double MIN_STRENGTH = 0.1;
	/**
	 * 세기 상한.
	 *
	 * <p>5.0 이면 몹이 화면 밖으로 날아가 청크 경계 너머에 떨어진다. 그 위는 밀어내기가 아니라
	 * 사실상 「지우기」가 되어 「피해는 주지 않는다」는 약속이 무의미해진다.
	 */
	public static final double MAX_STRENGTH = 5.0;

	/** {@code solo_radius} 를 안 적었다는 표시. 작은 충격파가 없다. */
	public static final int NO_SOLO = 0;

	private final int distance;
	private final int radius;
	private final int soloRadius;
	private final int intervalSeconds;
	private final double strength;

	public ShockwaveEffect(int distance, int radius, int intervalSeconds, double strength) {
		this(distance, radius, NO_SOLO, intervalSeconds, strength);
	}

	public ShockwaveEffect(int distance, int radius, int soloRadius, int intervalSeconds,
			double strength) {
		this.distance = distance;
		this.radius = radius;
		this.soloRadius = soloRadius;
		this.intervalSeconds = intervalSeconds;
		this.strength = strength;
	}

	/**
	 * JSON에서 만든다.
	 *
	 * <p>읽을 값이 전부 생략 가능하고 범위를 벗어나면 잘라 쓰므로 <b>실패로 끝나는 길이 없다.</b>
	 * 언제나 쓸 수 있는 효과가 나온다.
	 */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		int distance = clampInt(perkId, "distance",
				PerkEffectType.readInt(json, "distance", DEFAULT_DISTANCE),
				MIN_DISTANCE, MAX_DISTANCE);
		int radius = clampInt(perkId, "radius",
				PerkEffectType.readInt(json, "radius", DEFAULT_RADIUS),
				MIN_RADIUS, MAX_RADIUS);
		int seconds = clampInt(perkId, "interval_seconds",
				PerkEffectType.readInt(json, intervalKey(json), DEFAULT_INTERVAL_SECONDS),
				MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS);
		return new ShockwaveEffect(distance, radius, readSoloRadius(perkId, json), seconds,
				readStrength(perkId, json));
	}

	/**
	 * 안 적었거나 숫자가 아니면 {@link #NO_SOLO}, 적었으면 {@code radius} 와 같은 범위로 자른다.
	 * 카멜케이스({@code soloRadius})로 적어도 읽는다.
	 */
	private static int readSoloRadius(String perkId, JsonObject json) {
		String key = json.has("soloRadius") ? "soloRadius" : "solo_radius";
		if (!json.has(key) || PerkEffectType.readDouble(json, key) == null) {
			return NO_SOLO;
		}
		return clampInt(perkId, "solo_radius", PerkEffectType.readInt(json, key, NO_SOLO),
				MIN_RADIUS, MAX_RADIUS);
	}

	/** 카멜케이스로 적어도 읽어 준다. 나머지 셋은 한 낱말이라 갈릴 일이 없다. */
	private static String intervalKey(@Nullable JsonObject json) {
		return json != null && json.has("intervalSeconds") ? "intervalSeconds" : "interval_seconds";
	}

	/** {@code strength} 는 소수라 {@code readInt} 로 읽으면 1.2 가 1 이 된다. */
	private static double readStrength(String perkId, @Nullable JsonObject json) {
		Double raw = json == null ? null : PerkEffectType.readDouble(json, "strength");
		if (raw == null) {
			// 없거나 숫자가 아니면 기본값이다. readInt 의 규칙 그대로다.
			return DEFAULT_STRENGTH;
		}
		return clampDouble(perkId, "strength", raw, MIN_STRENGTH, MAX_STRENGTH);
	}

	private static int clampInt(String perkId, String key, int value, int min, int max) {
		if (value >= min && value <= max) {
			return value;
		}
		int cut = Math.max(min, Math.min(max, value));
		SharedFateMod.LOGGER.warn(
				"증강 {}: shockwave 의 {} 가 {}~{} 범위를 벗어나 {} 로 자릅니다 ({})",
				perkId, key, min, max, cut, value);
		return cut;
	}

	private static double clampDouble(String perkId, String key, double value, double min,
			double max) {
		if (value >= min && value <= max) {
			return value;
		}
		double cut = Math.max(min, Math.min(max, value));
		SharedFateMod.LOGGER.warn(
				"증강 {}: shockwave 의 {} 가 {}~{} 범위를 벗어나 {} 로 자릅니다 ({})",
				perkId, key, min, max, cut, value);
		return cut;
	}

	/**
	 * 팀원 전원이 이 거리 안에 있어야 터진다(칸).
	 *
	 * <p>이 값을 그대로 {@code TeamProximity.together} 에 넘기면 세트 「결속 3」의 배율이 저절로
	 * 먹는다. 부르는 쪽이 배율을 따로 곱하면 <b>두 번 곱해진다.</b>
	 */
	public int distance() {
		return distance;
	}

	/** 충격파가 닿는 반경(칸). 세트 배율은 부르는 쪽이 {@code TeamProximity.scaled} 로 먹인다. */
	public int radius() {
		return radius;
	}

	/**
	 * 뭉쳐 있지 않을 때 팀원 각자 주변에 터지는 작은 충격파의 반경(칸). 없으면 {@link #NO_SOLO}.
	 * 세트 배율은 {@code radius} 와 같이 부르는 쪽이 {@code TeamProximity.scaled} 로 먹인다.
	 */
	public int soloRadius() {
		return soloRadius;
	}

	/** 작은 충격파가 정의돼 있는가. */
	public boolean hasSolo() {
		return soloRadius != NO_SOLO;
	}

	/** 몇 초마다 터지는가. */
	public int intervalSeconds() {
		return intervalSeconds;
	}

	/** 주기를 틱으로. 실행부가 게임 시간과 견주는 값이다. */
	public int intervalTicks() {
		return intervalSeconds * TICKS_PER_SECOND;
	}

	/** 밀어내는 세기. {@code LivingEntity.knockback} 의 첫 인자로 그대로 들어간다. */
	public double strength() {
		return strength;
	}
}
