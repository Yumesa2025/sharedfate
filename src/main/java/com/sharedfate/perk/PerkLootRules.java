package com.sharedfate.perk;

import com.sharedfate.perk.effect.AlwaysLootingEffect;
import com.sharedfate.perk.effect.LootBonusEffect;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 전리품에 끼어드는 효과({@code loot_bonus}·{@code always_looting})의 판정부.
 *
 * <p>{@link com.sharedfate.mixin.EnchantmentHelperLootingMixin} 이 약탈 등급을 묻는 자리에서
 * 여기에 물어보고, 답만큼 등급을 더한다.
 *
 * <h2>두 가지 효과가 이 길을 탄다</h2>
 * <ul>
 *   <li>{@link LootBonusEffect}({@code loot_bonus}) — <b>손에 든 것</b>이 조건이다.
 *       「수확자」의 다이아 호미가 그것이다.</li>
 *   <li>{@link AlwaysLootingEffect}({@code always_looting}) — 조건이 없다. 무엇을 들었든,
 *       맨손이어도 걸린다. 사냥 세트 2·3단계가 이 길을 탄다.</li>
 * </ul>
 *
 * <h2>겹칠 때의 규칙 — 형마다 다르다</h2>
 * <ol>
 *   <li>{@code loot_bonus} 끼리는 <b>모두 더한다.</b> 서로 다른 증강이 각각 「이 무기를
 *       들었을 때」라고 약속한 등급이라 하나만 골라 줄 이유가 없다.</li>
 *   <li>{@code always_looting} 끼리는 <b>가장 높은 하나만</b> 센다. 세트 단계는 누적이라 사냥
 *       셋을 모으면 2단계(약탈 I)와 3단계(약탈 IV)가 둘 다 켜지는데, 더해서 V 가 되면
 *       3단계 설명 「약탈이 IV 로 오릅니다」와 실제가 어긋난다.</li>
 *   <li>둘 사이는 <b>더한다.</b> 형이 다르면 약속도 다르다 — 다이아 호미를 든 채 사냥 3을
 *       켜 두었다면 「호미를 들어서 얻은 것」과 「팀이 늘 갖고 있는 것」이 둘 다 유효하다.</li>
 * </ol>
 *
 * <h2>손에 든 것만 본다 — {@code loot_bonus} 한정</h2>
 * <p>바닐라 약탈이 {@code mainhand} 슬롯에서만 세는 것과 맞춘다. 왼손에 들거나 가방에 넣어
 * 두는 것으로는 걸리지 않는다. <b>{@code always_looting} 은 이 제한과 무관하다</b> — 빈 손이라고
 * 일찍 빠져나오면 세트 보상이 통째로 죽으므로, 빈 손 판정은 {@code loot_bonus} 쪽에만 걸린다
 * ({@link PerkItemMatcher#matches} 가 빈 스택을 거짓으로 돌려준다).
 */
public final class PerkLootRules {
	private PerkLootRules() {
	}

	/**
	 * 이 사람이 지금 얻는 약탈 추가 등급. 해당 없으면 0.
	 *
	 * <p>겹칠 때의 규칙은 클래스 주석에 적어 뒀다.
	 */
	public static int bonusLootingLevels(@Nullable LivingEntity entity) {
		if (!(entity instanceof ServerPlayer player)) {
			return 0;
		}
		TeamState state = TeamLookup.stateOf(player.getUUID());
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return 0;
		}
		return bonusLootingLevels(effectsOf(state), player.getMainHandItem());
	}

	/**
	 * 효과 목록 하나에서 약탈 추가 등급을 낸다. 살아 있는 서버 없이 이대로 시험할 수 있다.
	 *
	 * <p><b>목록을 한 번에 받는 것이 중요하다.</b> 보유 증강 몫과 세트 몫을 따로 계산해 더하면
	 * 「가장 높은 하나만」이 각각 따로 뽑혀 약탈 I + IV = V 가 된다. 두 몫을 한 목록으로
	 * 합친 뒤 한 번만 훑는다.
	 *
	 * @param effects 보유 증강과 켜진 세트의 효과를 모두 합친 목록
	 * @param held    지금 주손에 든 것. {@code loot_bonus} 판정에만 쓴다. 비어 있어도 된다
	 */
	public static int bonusLootingLevels(@Nullable Iterable<PerkEffect> effects,
			@Nullable ItemStack held) {
		if (effects == null) {
			return 0;
		}
		// 무기 조건이 붙은 몫: 전부 더한다.
		int stacked = 0;
		// 조건 없는 몫: 가장 높은 하나만. 더하지 않는다.
		int always = 0;
		for (PerkEffect effect : effects) {
			if (effect instanceof LootBonusEffect loot) {
				if (loot.matches(held)) {
					stacked += loot.levels();
				}
			} else if (effect instanceof AlwaysLootingEffect looting) {
				always = Math.max(always, looting.levels());
			}
		}
		return stacked + always;
	}

	/**
	 * 이 팀이 보유한 증강과 켜진 세트의 효과를 한 줄로 펼친다.
	 *
	 * <p>풀에서 사라진 id 는 건너뛴다. 증강 정의를 손으로 고칠 수 있는 이상 저장에만 남은
	 * id 는 언제든 생긴다.
	 */
	private static List<PerkEffect> effectsOf(TeamState state) {
		List<PerkEffect> effects = new ArrayList<>();
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk != null) {
				effects.addAll(perk.effects());
			}
		}
		// 켜진 세트의 효과도 같은 목록에 들어간다. 사냥 2·3단계가 always_looting 을 이 길로
		// 태운다. 이 한 줄이 없으면 빌드도 통과하고 로그도 없는데 그 세트만 완전 무동작이 된다.
		effects.addAll(PerkSetEffects.activeEffectsOf(state));
		return effects;
	}
}
