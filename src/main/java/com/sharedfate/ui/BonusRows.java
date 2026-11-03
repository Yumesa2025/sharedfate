package com.sharedfate.ui;

import com.sharedfate.net.StatBonuses;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 인벤토리 옆 패널에 세울 보정치 줄들을 <b>한 곳에서</b> 만든다.
 *
 * <p>사람이 정한 것(2026-11-04) — 「무기를 들 때마다 바뀌는 최종 피해 숫자가 아니라 「추가
 * 공격력 +35%」처럼 증강·세트로 붙은 보정치를 % 로」. 최종값(「공격 1 → 7.5」)은 팀 화면의
 * 「능력치」 탭이 그대로 적는다({@code ClientStatRows.playerRows}).
 *
 * <p>클라이언트 소스셋 밖에 둔 이유는 시험 때문이다. 값은 클라이언트가 받아 둔
 * {@link StatBonuses} 와 배율 셋뿐이라 화면 없이도 줄을 다 만들 수 있다.
 *
 * <h2>줄의 차례</h2>
 * <p>{@code ClientStatRows} 와 같은 결이다 — 얼마나 버티는가 → 얼마나 때리는가 → 얼마나 빨리
 * 때리는가 → 얼마나 덜 맞는가 → 얼마나 빨리 움직이는가 → 맞으면 얼마나 아픈가. 맨 끝에 회차
 * 경험치를 둔다. 싸움과 상관없는 값이라 싸움 줄들 사이에 끼우면 결이 끊긴다.
 *
 * <h2>0 인 줄은 숨긴다</h2>
 * <p>아무것도 안 걸린 항목이 「0%」로 줄줄이 서 있으면 정작 걸린 것이 묻힌다. 다만 <b>내 묶음이
 * 통째로 비면</b> 「증강 보정 없음」 한 줄을 흐리게 남긴다. 패널이 아예 사라지면 고장인지 아무것도
 * 없는 것인지 알 수 없다.
 */
public final class BonusRows {
	/** 내 묶음이 통째로 비었을 때 남기는 한 줄. */
	public static final String NOTHING = "증강 보정 없음";

	private BonusRows() {
	}

	/**
	 * 두 묶음 — 팀 보정치와 이 판의 몹. 비어 있는 몹 묶음은 뺀다.
	 *
	 * @param bonuses     서버가 센 보정치. null 이면 아직 받지 못한 것이라 아무 줄도 만들지 않는다
	 * @param damageTaken 받는 피해 배율(1.0 이 그대로)
	 * @param mobHealth   몹 최대 체력 배율
	 * @param mobDamage   몹 공격력 배율
	 * @param armorMasked 증강이 방어구 칸을 가리고 있는가. 참이면 방어력 줄을 {@value StatRow#MASK} 로
	 */
	public static List<List<PanelRow>> groups(@Nullable StatBonuses bonuses, double damageTaken,
			double mobHealth, double mobDamage, boolean armorMasked) {
		if (bonuses == null) {
			return List.of();
		}
		List<List<PanelRow>> groups = new ArrayList<>(2);
		groups.add(playerRows(bonuses, damageTaken, armorMasked));
		List<PanelRow> mobs = mobRows(mobHealth, mobDamage);
		if (!mobs.isEmpty()) {
			groups.add(mobs);
		}
		return groups;
	}

	/** 팀 보정치 줄들. 0 인 줄은 빠지고, 모두 빠지면 {@link #NOTHING} 한 줄이 남는다. */
	public static List<PanelRow> playerRows(StatBonuses bonuses, double damageTaken,
			boolean armorMasked) {
		List<PanelRow> rows = new ArrayList<>(8);
		BonusRow health = BonusRow.flat("최대 체력", "체력", bonuses.maxHealth(),
				StatRow.Sense.HIGHER_IS_BETTER);
		// 체력 2 가 하트 한 칸이다. 숫자만으로는 몇 칸인지 세어 봐야 안다.
		addIfSet(rows, health.withSuffix(
				"  (하트 " + StatSummary.number(bonuses.maxHealth() / 2.0) + "개)"));
		addIfSet(rows, BonusRow.percent("추가 공격력", "공격", bonuses.attack(),
				StatRow.Sense.HIGHER_IS_BETTER));
		// 더하기는 비율로 바꾸지 않는다. 맨손 1 에 +2 는 +200% 지만 검 7 에 +2 는 +29% 라
		// 든 것에 따라 숫자가 달라진다 — 그 출렁임을 없애려고 만든 패널이다.
		addIfSet(rows, BonusRow.flat("고정 공격력", "고정공", bonuses.attackFlat(),
				StatRow.Sense.HIGHER_IS_BETTER));
		addIfSet(rows, BonusRow.percent("공격 속도", "공속", bonuses.attackSpeed(),
				StatRow.Sense.HIGHER_IS_BETTER));
		if (armorMasked) {
			// 가린 값은 줄을 지우지 않는다. 지우면 「그런 보정이 없다」로 읽힌다(StatRow.MASK).
			rows.add(BonusRow.masked("방어력", "방어"));
		} else {
			addIfSet(rows, BonusRow.flat("방어력", "방어", bonuses.armor(),
					StatRow.Sense.HIGHER_IS_BETTER));
		}
		addIfSet(rows, BonusRow.percent("이동 속도", "속도", bonuses.moveSpeed(),
				StatRow.Sense.HIGHER_IS_BETTER));
		// 오르면 나쁜 값이다. 받는 피해 +20% 를 초록으로 알리면 안 된다.
		addIfSet(rows, BonusRow.percent("받는 피해", "피해", damageTaken - 1.0,
				StatRow.Sense.LOWER_IS_BETTER));
		addIfSet(rows, BonusRow.percent("경험치", "경험", bonuses.experience(),
				StatRow.Sense.HIGHER_IS_BETTER));
		if (rows.isEmpty()) {
			rows.add(BonusRow.note(NOTHING));
		}
		return rows;
	}

	/** 이 판의 몹 줄들. 둘 다 오르면 나쁜 값이다. 0 인 줄은 빠진다. */
	public static List<PanelRow> mobRows(double mobHealth, double mobDamage) {
		List<PanelRow> rows = new ArrayList<>(2);
		addIfSet(rows, BonusRow.percent("몹 체력", "몹체력", mobHealth - 1.0,
				StatRow.Sense.LOWER_IS_BETTER));
		addIfSet(rows, BonusRow.percent("몹 공격력", "몹공격", mobDamage - 1.0,
				StatRow.Sense.LOWER_IS_BETTER));
		return rows;
	}

	private static void addIfSet(List<PanelRow> rows, BonusRow row) {
		if (!row.isZero()) {
			rows.add(row);
		}
	}
}
