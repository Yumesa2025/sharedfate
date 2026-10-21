package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 「드래곤전 대비」 증강 열두 장이 쓰는 효과 하나(0.44.0-dev).
 *
 * <p>JSON 형식:
 * <pre>
 * { "type": "dragon_prep", "kind": "floor_sense", "multiplier": 0.8 }
 * { "type": "dragon_prep", "kind": "unworn_armor" }
 * </pre>
 *
 * <h2>왜 열두 개의 효과 타입이 아니라 하나인가</h2>
 * <p>열두 장은 모두 <b>남의 계산 한가운데 끼어드는 표시</b>다 — 시련 공격의 피해 · 밀림 · 드래곤 착지 시간 ·
 * 경험치 바깥의 무엇 등. {@link PerkEffect#apply} 로 사람에게 붙일 것이 없고, 끼어드는 자리가
 * 「이 팀이 이것을 가졌는가, 값은 얼마인가」만 묻는다. 그 질문은 한 곳({@code DragonPrep})에서 답하고,
 * 이 파일은 정의를 읽어 {@link Kind} 와 값 하나를 들고 있을 뿐이다. 종류마다 파일을 만들면 같은
 * 「보유 증강을 훑어 찾기」가 열두 벌이 된다.
 *
 * <h2>값은 종류마다 이름과 범위가 정해져 있다</h2>
 * <p>{@link Kind#key} 가 그 종류가 읽는 칸 이름이고, 없으면 값이 없는 종류다. 범위를 벗어나면 증강을
 * 통째로 버린다 — 설명은 그대로인데 효과만 빠진 증강은 사람을 속이는 셈이다
 * ({@code PerkRegistry.parseEffects} 의 원칙).
 */
public final class DragonPrepEffect implements PerkEffect {

	/** 열두 장이 끼어드는 자리. id 는 정의 파일의 {@code kind} 다. */
	public enum Kind {
		/** 불사의 맹세 — 죽을 피해를 한 번 막는다. 값: 그 뒤 최대 체력 배율. */
		UNDYING_OATH("undying_oath", "max_health_scale", 0.0, 1.0, false),
		/** 바닥 감각 — 바닥 표식형 시련 공격의 피해 배율. */
		FLOOR_SENSE("floor_sense", "multiplier", 0.0, 1.0, false),
		/** 무뎌지지 않는 갑주 — 방어구·방패 내구도가 닳지 않는다. */
		UNWORN_ARMOR("unworn_armor", null, 0.0, 0.0, false),
		/** 엔더 동화 — 엔더맨의 시선 적대가 걸리지 않는다. */
		ENDER_KINSHIP("ender_kinship", null, 0.0, 0.0, false),
		/** 닻 — 시련 공격의 밀어냄·끌어당김 배율. */
		ANCHOR("anchor", "multiplier", 0.0, 1.0, false),
		/** 첫 봉인 — 「첫 크리스탈」 자리의 시련을 없앤다. */
		FIRST_SEAL("first_seal", null, 0.0, 0.0, false),
		/** 착지 노리기 — 착지한 드래곤에게 주는 근접 피해 배율. */
		LANDING_AMBUSH("landing_ambush", "multiplier", 1.0, 5.0, false),
		/** 하늘 사수 — 날고 있는 드래곤에게 주는 투사체 피해 배율. */
		SKY_ARCHER("sky_archer", "multiplier", 1.0, 5.0, false),
		/** 마르지 않는 화살통 — 엔드에서 일반 화살이 줄지 않는다. */
		ENDLESS_QUIVER("endless_quiver", null, 0.0, 0.0, false),
		/** 날개 꺾기 — 드래곤 착지 고정 시간(초). */
		WING_BREAK("wing_break", "hold_seconds", 0.0, 60.0, false),
		/** 엔드 적응 — 엔드에 있는 동안 더하는 최대 체력. */
		END_ADAPTATION("end_adaptation", "amount", 0.0, 100.0, false),
		/** 결전의 함성 — 최후의 저항이 열릴 때 받는 흡수 체력. 4 의 배수(흡수 상태이상 한 단계가 4)다. */
		LAST_RALLY("last_rally", "amount", 0.0, 40.0, true);

		private final String id;
		private final @Nullable String key;
		private final double lowExclusive;
		private final double highInclusive;
		private final boolean multipleOfFour;

		Kind(String id, @Nullable String key, double lowExclusive, double highInclusive,
				boolean multipleOfFour) {
			this.id = id;
			this.key = key;
			this.lowExclusive = lowExclusive;
			this.highInclusive = highInclusive;
			this.multipleOfFour = multipleOfFour;
		}

		/** 정의 파일의 {@code kind}. */
		public String id() {
			return id;
		}

		/** 이 종류가 읽는 값 칸의 이름. 값이 없는 종류면 {@code null}. */
		public @Nullable String key() {
			return key;
		}

		/** 이 값을 받아도 되는가. 값이 없는 종류는 언제나 참이다. */
		public boolean accepts(double value) {
			if (key == null) {
				return true;
			}
			if (!Double.isFinite(value) || !(value > lowExclusive) || value > highInclusive) {
				return false;
			}
			return !multipleOfFour || value % 4.0 == 0.0;
		}

		/** {@code kind} 문자열에 맞는 종류. 모르면 {@code null}. */
		public static @Nullable Kind fromId(@Nullable String raw) {
			if (raw == null) {
				return null;
			}
			String normalized = raw.trim().toLowerCase(Locale.ROOT);
			for (Kind kind : values()) {
				if (kind.id.equals(normalized)) {
					return kind;
				}
			}
			return null;
		}
	}

	private final Kind kind;
	private final double value;

	public DragonPrepEffect(Kind kind, double value) {
		this.kind = kind;
		this.value = value;
	}

	/** JSON 에서 만든다. 종류를 모르거나 값이 범위 밖이면 경고를 남기고 {@code null}. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		String rawKind = PerkEffectType.readString(json, "kind");
		Kind kind = Kind.fromId(rawKind);
		if (kind == null) {
			SharedFateMod.LOGGER.warn("증강 {}: dragon_prep 의 kind 를 알 수 없습니다 ({})", perkId, rawKind);
			return null;
		}
		if (kind.key() == null) {
			return new DragonPrepEffect(kind, 0.0);
		}
		Double raw = PerkEffectType.readDouble(json, kind.key());
		if (raw == null || !kind.accepts(raw)) {
			SharedFateMod.LOGGER.warn("증강 {}: dragon_prep({}) 의 {} 가 범위 밖입니다 ({})",
					perkId, kind.id(), kind.key(), raw);
			return null;
		}
		return new DragonPrepEffect(kind, raw);
	}

	public Kind kind() {
		return kind;
	}

	/** 이 종류의 값. 값이 없는 종류면 0. */
	public double value() {
		return value;
	}
}
