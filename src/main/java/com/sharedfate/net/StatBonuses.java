package com.sharedfate.net;

import com.sharedfate.ui.BonusRow;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import org.jetbrains.annotations.Nullable;

/**
 * 인벤토리 옆 능력치 패널이 적는 <b>팀 보정치</b>. {@link StatSnapshotPayload} 에 함께 실린다.
 *
 * <p>모두 「기본값에서 얼마나 달라졌는가」다. 비율은 0.35 가 「+35%」, 고정값은 2 가 「+2」다.
 * 아무것도 안 걸렸으면 모두 0 이고 패널은 그 줄을 숨긴다.
 *
 * <p>값은 서버가 센다({@code StatSnapshotBroadcaster.bonusesOf}). 클라이언트가 증강 목록만으로
 * 다시 세면 조건부 효과가 꺼져 있는지 알 수 없어 틀린다 — 그 사정은
 * {@link com.sharedfate.perk.PerkStatBonuses} 에 적었다.
 *
 * <p>받는 피해 배율과 몹 배율은 여기 없다. {@link StatSnapshotPayload} 에 이미 있고, 패널은 그
 * 값을 그대로 「−10%」 꼴로 바꿔 적는다.
 *
 * @param maxHealth   최대 체력 보너스(체력, 하트 반 칸이 1). 증강·세트·「엔드 적응」까지 더한 팀
 *                    상한에서 팀 기본값을 뺀 값이다. 고정·맹세로 줄었으면 음수다
 * @param attack      공격력 비율. 곱하기 수정자와 {@code damage_dealt} 배율을 곱한 것에서 1 을 뺐다
 * @param attackFlat  공격력 고정 보너스({@code add_value} 의 합)
 * @param attackSpeed 공격 속도 비율
 * @param armor       방어력 고정 보너스
 * @param moveSpeed   이동 속도 비율
 * @param experience  회차 경험치 보너스 비율. 2회차부터 회차마다 붙는다
 */
public record StatBonuses(float maxHealth, float attack, float attackFlat, float attackSpeed,
		float armor, float moveSpeed, float experience) {

	/** 아무것도 안 걸린 상태. 서버가 아직 말해 주지 않았을 때도 이것으로 둔다. */
	public static final StatBonuses NONE = new StatBonuses(0, 0, 0, 0, 0, 0, 0);

	public static final StreamCodec<RegistryFriendlyByteBuf, StatBonuses> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.FLOAT, StatBonuses::maxHealth,
					ByteBufCodecs.FLOAT, StatBonuses::attack,
					ByteBufCodecs.FLOAT, StatBonuses::attackFlat,
					ByteBufCodecs.FLOAT, StatBonuses::attackSpeed,
					ByteBufCodecs.FLOAT, StatBonuses::armor,
					ByteBufCodecs.FLOAT, StatBonuses::moveSpeed,
					ByteBufCodecs.FLOAT, StatBonuses::experience,
					StatBonuses::new);

	public StatBonuses {
		// 비율은 −100% 아래로 내려갈 수 없다(배율이 0 보다 작은 속성은 없다). 이상한 값은 0.
		maxHealth = finite(maxHealth);
		attack = ratio(attack);
		attackFlat = finite(attackFlat);
		attackSpeed = ratio(attackSpeed);
		armor = finite(armor);
		moveSpeed = ratio(moveSpeed);
		experience = ratio(experience);
	}

	private static float finite(float value) {
		return Float.isFinite(value) ? value : 0.0F;
	}

	private static float ratio(float value) {
		return Float.isFinite(value) ? Math.max(-1.0F, value) : 0.0F;
	}

	/**
	 * 지난번에 보낸 것과 <b>패널이 다르게 적을 만큼</b> 다른가.
	 *
	 * <p>{@link StatSnapshotPayload#differsFrom} 과 같은 생각이다 — 비율은 정수 % 로, 고정값은
	 * 소수 한 자리로 적으므로 그 글자가 같으면 보내지 않는다. 기준을 {@link BonusRow} 의 글자
	 * 만드는 함수와 나눠 쓰는 이유는, 둘이 갈라지면 화면이 옛 숫자를 들고 있게 되기 때문이다.
	 *
	 * @param sent 지난번에 보낸 것. 아직 한 번도 보내지 않았으면 null
	 */
	public boolean differsFrom(@Nullable StatBonuses sent) {
		return sent == null
				|| !BonusRow.flatText(sent.maxHealth).equals(BonusRow.flatText(maxHealth))
				|| !BonusRow.percentText(sent.attack).equals(BonusRow.percentText(attack))
				|| !BonusRow.flatText(sent.attackFlat).equals(BonusRow.flatText(attackFlat))
				|| !BonusRow.percentText(sent.attackSpeed).equals(BonusRow.percentText(attackSpeed))
				|| !BonusRow.flatText(sent.armor).equals(BonusRow.flatText(armor))
				|| !BonusRow.percentText(sent.moveSpeed).equals(BonusRow.percentText(moveSpeed))
				|| !BonusRow.percentText(sent.experience).equals(BonusRow.percentText(experience));
	}
}
