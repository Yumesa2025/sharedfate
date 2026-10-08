package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import org.jetbrains.annotations.Nullable;

/**
 * 나침반을 <b>우클릭해서</b> 다른 구조물로 갈아탈 수 있게 한다. 세트 「개척 2」의 효과다.
 *
 * <pre>{@code
 * { "type": "compass_toggle", "structure": "minecraft:stronghold",
 *   "dimension": "minecraft:overworld", "search_radius": 100,
 *   "base_name": "길잡이 나침반", "alternate_name": "엔더 요새 나침반" }
 * }</pre>
 *
 * <p>{@code structure}·{@code dimension}·{@code search_radius} 는 {@link CompassTargetEffect}
 * 와 <b>글자 하나까지 같은 뜻</b>이다. 그래서 이 클래스는 그 셋을 스스로 읽지 않고
 * {@link CompassTargetEffect#fromJson} 에 그대로 넘겨 <b>안에 하나를 품는다.</b> 탐색·캐시·
 * 나침반 손보기가 전부 그 타입을 열쇠로 쓰고 있으므로, 새 타입을 만들면 그 전부를 두 벌로
 * 늘려야 한다.
 *
 * <h2>왜 {@code compass_target} 을 하나 더 주지 않는가</h2>
 * <p>「개척 2」가 열렸을 때 오버월드용 {@code compass_target} 을 하나 더 얹으면, 마을과 엔더
 * 요새가 <b>같은 차원의 후보 둘</b>이 된다. {@link com.sharedfate.perk.PerkCompassTargets#choose}
 * 는 그럴 때 「먼저 얻은 것」으로 판가름하므로 언제나 마을이 이기고 엔더 요새는 영영 안 뜬다.
 * 사람이 고를 길이 없다. 그래서 토글은 후보를 <b>늘리는 것</b>이 아니라 고른 결과를
 * <b>갈아치우는 것</b>이어야 한다.
 *
 * <h2>이름은 왜 정의에 적는가</h2>
 * <p>나침반이 지금 무엇을 가리키는지는 <b>아이템 이름</b>으로만 보인다. 그 두 문자열을 코드에
 * 박으면 세트 정의만 고쳐서는 바꿀 수 없게 된다. 세트 정의 파일의 머리말이 「보상 값은 자주
 * 바뀐다. 코드에 박지 말고 이 파일 한 곳만 고칠 것」이라고 못박고 있다.
 *
 * <h2>이 클래스는 아무것도 하지 않는다</h2>
 * <p>{@link #apply}/{@link #remove} 가 비어 있다. 토글 상태를 들고 되돌리는 일은
 * {@link com.sharedfate.perk.PerkCompassToggle} 이, 실제로 나침반을 손보는 일은
 * {@link com.sharedfate.perk.PerkCompassTargets} 가 맡는다.
 */
public final class CompassToggleEffect implements PerkEffect {
	/** 아이템 이름으로 쓸 수 있는 글자 수 상한. 이보다 길면 툴팁이 화면을 넘는다. */
	public static final int MAX_NAME_LENGTH = 32;

	private final CompassTargetEffect alternate;
	private final String baseName;
	private final String alternateName;

	public CompassToggleEffect(CompassTargetEffect alternate, String baseName,
			String alternateName) {
		this.alternate = alternate;
		this.baseName = baseName;
		this.alternateName = alternateName;
	}

	/** JSON 에서 만든다. 정의가 잘못됐으면 경고를 남기고 {@code null}. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		// 구조물·차원·반경은 compass_target 이 읽는다. 같은 오류 문구가 한 곳에만 있어야
		// 「반경이 범위를 벗어났다」는 경고를 봤을 때 고칠 자리를 헷갈리지 않는다.
		PerkEffect inner = CompassTargetEffect.fromJson(perkId, index, json);
		if (!(inner instanceof CompassTargetEffect alternate)) {
			return null;
		}

		String baseName = PerkEffectType.readString(json, "base_name");
		String alternateName = PerkEffectType.readString(json, "alternate_name");
		if (isUnusable(baseName) || isUnusable(alternateName)) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: compass_toggle 의 base_name / alternate_name 이 없거나 {}자를 넘습니다",
					perkId, MAX_NAME_LENGTH);
			return null;
		}
		if (baseName.trim().equals(alternateName.trim())) {
			// 두 이름이 같으면 눌러도 아무것도 안 바뀐 것처럼 보인다. 그 상태로 내보내면
			// 「우클릭이 안 먹는다」는 제보만 남고 원인은 정의에 있다.
			SharedFateMod.LOGGER.warn(
					"증강 {}: compass_toggle 의 두 이름이 같아 무엇을 가리키는지 알 수 없습니다", perkId);
			return null;
		}
		return new CompassToggleEffect(alternate, baseName.trim(), alternateName.trim());
	}

	private static boolean isUnusable(@Nullable String name) {
		return name == null || name.isBlank() || name.trim().length() > MAX_NAME_LENGTH;
	}

	/**
	 * 토글을 켰을 때 가리킬 자리.
	 *
	 * <p><b>이 객체의 동일성이 중요하다.</b> {@link com.sharedfate.perk.PerkCompassTargets} 의
	 * 탐색 캐시가 {@code IdentityHashMap} 이라, 부를 때마다 새로 만들면 캐시가 매번 빗나가
	 * 10초마다 엔더 요새를 다시 찾게 된다. 그래서 여기서 만들어 두고 그대로 돌려준다.
	 */
	public CompassTargetEffect alternate() {
		return alternate;
	}

	/** 토글이 꺼져 있을 때 나침반에 붙일 이름. */
	public String baseName() {
		return baseName;
	}

	/** 토글이 켜져 있을 때 나침반에 붙일 이름. */
	public String alternateName() {
		return alternateName;
	}

	/** 토글 상태에 맞는 이름. */
	public String nameFor(boolean alternateOn) {
		return alternateOn ? alternateName : baseName;
	}
}
