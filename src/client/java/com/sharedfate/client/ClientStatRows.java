package com.sharedfate.client;

import com.sharedfate.client.perk.ClientPerkFeatures;
import com.sharedfate.perk.effect.HideHudEffect;
import com.sharedfate.ui.BonusRows;
import com.sharedfate.ui.PanelRow;
import com.sharedfate.ui.StatRow;
import com.sharedfate.ui.StatSummary;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 능력치 줄들을 <b>한 곳에서</b> 만든다.
 *
 * <p>두 화면이 서로 다른 줄을 그린다. 팀 화면의 「능력치」 탭은 「공격 1 → 7.5」처럼 <b>최종값</b>을
 * ({@link #playerRows}·{@link #mobRows}), 인벤토리 화면(E) 왼쪽의 상시 표시는 「추가 공격력 +35%」처럼
 * 증강·세트가 얹은 <b>보정치</b>를({@link #bonusGroups}) 적는다. 사람이 정한 것(2026-11-04) —
 * 인벤토리 옆 숫자가 무기를 바꿀 때마다 출렁이지 않아야 한다. 보정치 줄의 차례·이름은
 * {@link BonusRows} 에 있다. 최종값 줄의 차례, 이름, 단위, 색이 뒤집히는 줄이 어느 것인지는
 * <b>여기가 유일한 출처</b>다.
 *
 * <p>값은 {@link LocalPlayer} 와 {@link ClientStatSnapshot} 에서 읽는다. 줄의 글자 모양·자리는
 * {@link StatRow}·{@link com.sharedfate.ui.InventoryStatPanel} 에 있다.
 *
 * <h2>값을 어디서 읽는가</h2>
 * <ul>
 *   <li><b>최대 체력·방어력·이동 속도·공격 속도</b> — 플레이어의 속성에서 그대로 읽는다.
 *       넷 모두 {@code setSyncable(true)} 로 등록되어 <b>수정자까지</b> 클라이언트에 오므로
 *       ({@code AttributeMap.getSyncableAttributes} → {@code ServerEntity} 가 본인에게도 보낸다)
 *       {@code getBaseValue()} 가 바닐라 기본값, {@code getValue()} 가 증강·장비까지 얹힌
 *       지금 값이 된다.</li>
 *   <li><b>공격력·받는 피해 배율·몹 배율</b> — 클라이언트가 스스로 알 수 없어 서버가 보내 준다.
 *       {@link ClientStatSnapshot} 에서 읽고, 아직 받지 못했으면 그 줄들을 <b>건너뛴다.</b></li>
 * </ul>
 *
 * <h2>줄의 차례</h2>
 * <p>내 능력치 여섯 줄과 이 판의 몹 두 줄을 <b>따로 묶는다.</b>
 *
 * <p>여섯 줄의 차례는 {@code 얼마나 버티는가 → 얼마나 때리는가 → 얼마나 빨리 때리는가 →
 * 얼마나 덜 맞는가 → 얼마나 빨리 움직이는가 → 맞으면 얼마나 아픈가} 다. 공격력과 공격 속도는
 * 무기 하나가 함께 바꾸는 값이라 반드시 붙여 둔다.
 */
public final class ClientStatRows {
	/** 묶음 제목. 팀 화면이 적는다. 인벤토리 화면은 자리가 없어 제목 없이 틈만 둔다. */
	public static final String PLAYER_HEADING = "내 능력치";
	public static final String MOB_HEADING = "이 판의 몹";

	private ClientStatRows() {
	}

	/**
	 * 인벤토리 옆 패널의 보정치 두 묶음 — 팀 보정치와 이 판의 몹.
	 *
	 * <p>서버가 값을 알려 주기 전에는 빈 목록이다. 「증강 보정 없음」도 서버가 정말 아무것도
	 * 없다고 말한 뒤에만 적는다.
	 */
	public static List<List<PanelRow>> bonusGroups() {
		if (!ClientStatSnapshot.known()) {
			return List.of();
		}
		return BonusRows.groups(ClientStatSnapshot.bonuses(), ClientStatSnapshot.damageTaken(),
				ClientStatSnapshot.mobHealth(), ClientStatSnapshot.mobDamage(),
				ClientPerkFeatures.isHidden(HideHudEffect.Element.ARMOR));
	}

	/**
	 * 내 능력치 줄들.
	 *
	 * <p>속성을 찾지 못하면 <b>그 줄만 건너뛴다.</b> 다른 모드가 속성을 지웠거나 하는 드문
	 * 경우인데, 거기서 0 을 적으면 없는 사실을 만들어 낸다.
	 */
	public static List<StatRow> playerRows(@Nullable LocalPlayer player) {
		List<StatRow> rows = new ArrayList<>(6);
		if (player == null) {
			return rows;
		}
		int before = rows.size();
		add(rows, player, "최대 체력", "체력", Attributes.MAX_HEALTH, StatSummary.Unit.RAW, false);
		if (rows.size() > before) {
			// 숫자만으로는 몇 칸인지 세어 봐야 안다. 자리가 넉넉한 화면에서만 덧붙는다.
			rows.set(before, rows.get(before).withSuffix(
					"  (하트 " + StatSummary.number(player.getMaxHealth() / 2.0) + "개)"));
		}
		if (ClientStatSnapshot.known()) {
			rows.add(StatRow.of("공격력", "공격", ClientStatSnapshot.attackDamageBase(),
					ClientStatSnapshot.attackDamageCurrent(), StatSummary.Unit.RAW,
					StatRow.Sense.HIGHER_IS_BETTER));
		}
		// 공격 속도는 「초당 공격 횟수」다. 기본 4.0 이 그대로 초당 4회라는 뜻이므로
		// 백분율로 바꾸지 않는다 — 이동 속도의 0.1 과 달리 숫자 자체가 이미 말이 된다.
		add(rows, player, "공격 속도", "공속", Attributes.ATTACK_SPEED, StatSummary.Unit.RAW, false);
		add(rows, player, "방어력", "방어", Attributes.ARMOR, StatSummary.Unit.RAW,
				ClientPerkFeatures.isHidden(HideHudEffect.Element.ARMOR));
		add(rows, player, "이동 속도", "속도", Attributes.MOVEMENT_SPEED,
				StatSummary.Unit.PERCENT, false);
		if (ClientStatSnapshot.known()) {
			// 오르면 나쁜 값이다. 받는 피해가 250% 로 오른 것을 초록으로 알리면 안 된다.
			rows.add(StatRow.multiplier("받는 피해", "피해", ClientStatSnapshot.damageTaken(),
					StatRow.Sense.LOWER_IS_BETTER));
		}
		return rows;
	}

	/**
	 * 이 판의 몹 줄들. 서버가 값을 알려 준 뒤에만 나온다.
	 *
	 * <p>둘 다 <b>오르면 나쁜 값</b>이라 {@link StatRow.Sense#LOWER_IS_BETTER} 로 둔다.
	 */
	public static List<StatRow> mobRows() {
		if (!ClientStatSnapshot.known()) {
			return List.of();
		}
		return List.of(
				StatRow.multiplier("몹 체력", "몹체력", ClientStatSnapshot.mobHealth(),
						StatRow.Sense.LOWER_IS_BETTER),
				StatRow.multiplier("몹 공격력", "몹공격", ClientStatSnapshot.mobDamage(),
						StatRow.Sense.LOWER_IS_BETTER));
	}

	private static void add(List<StatRow> rows, LocalPlayer player, String label, String shortLabel,
			Holder<Attribute> attribute, StatSummary.Unit unit, boolean masked) {
		AttributeInstance instance = player.getAttribute(attribute);
		if (instance == null) {
			return;
		}
		if (masked) {
			rows.add(StatRow.masked(label, shortLabel));
			return;
		}
		rows.add(StatRow.of(label, shortLabel, instance.getBaseValue(), instance.getValue(), unit,
				StatRow.Sense.HIGHER_IS_BETTER));
	}
}
