package com.sharedfate.enchant;

import com.sharedfate.perk.Perk;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetEffects;
import com.sharedfate.perk.effect.EnchantCostEffect;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * 인챈트 탁자의 대가를 경험치 레벨 대신 <b>다이아몬드</b>로 받는다.
 *
 * <p>바닐라는 칸마다 요구 레벨이 있었지만 이 모드는 <b>레벨을 아예 보지 않는다.</b>
 * 레벨 0으로도 세 칸 모두 인챈트할 수 있고, 대신 다이아몬드를 낸다. 청금석은 칸과 상관없이
 * 3개다({@link EnchantmentTableRules}).
 *
 * <h2>다이아몬드는 「칸」에서 받는다</h2>
 *
 * <p>인벤토리를 뒤져 걷지 않는다. 청금석 칸
 * 아래에 <b>진짜 칸</b>({@link EnchantmentDiamondSlot})을 두고 거기 있는 것만 센다.
 *
 * <h2>값을 바꾸려면</h2>
 *
 * <ul>
 *   <li>세 칸이 모두 같은 기본값이면 {@link #DIAMONDS_PER_ENCHANT} 하나만 고친다.</li>
 *   <li>칸마다 다르게 하려면 {@link #forSlot(Player, int)} 하나만 고친다.
 *       단추 표시·툴팁·차감·검사가 모두 이 메서드 하나를 보므로 다른 곳은 손댈 필요가
 *       없다.</li>
 * </ul>
 *
 * <h2>팀마다 값이 다르다 — {@code enchant_cost}</h2>
 *
 * <p>{@link EnchantCostEffect} 는 <b>보유 증강</b>으로도 오고 <b>세트 단계</b>로도 온다. 둘 다
 * 없는 팀은 {@value #DIAMONDS_PER_ENCHANT} 개 그대로다. 합치는 규칙은
 * {@link #forState} 에 적어 뒀다 — 한 줄로 줄이면 「출처마다 가장 싼 쪽, 두 출처를 다 가지면
 * {@value #DIAMONDS_WITH_BOTH_DISCOUNTS} 개」다.
 *
 * <p><b>팀은 서버만 안다.</b> 그런데 단추의 숫자와 툴팁은 클라이언트가 그리므로, 클라이언트도
 * 같은 숫자를 알아야 한다. 그래서 인챈트
 * 메뉴에 데이터 칸({@link EnchantmentCostDataSlot})을 하나 더 달아 서버가 계산한 개수를
 * 내려보내고, 클라이언트는 그 값을 {@link #rememberShown} 으로 받아 둔다. 화면 쪽 경로
 * ({@link #forSlot(int)}, {@link #displayCosts}, {@link EnchantmentDiamondTooltip})는 전부 그
 * 값을 본다.
 *
 * <p>서버 쪽 경로({@link #canAfford}, {@link #consume})는 받아 둔 값을 쓰지 않고 <b>플레이어의
 * 팀을 직접</b> 본다. 실제로 걷는 개수가 클라이언트가 보낸 숫자에 좌우되면 안 된다.
 */
public final class EnchantmentDiamondCost {
	/** 인챈트 칸 수. 바닐라 {@code EnchantmentMenu.costs} 배열 길이와 같다. */
	public static final int SLOT_COUNT = 3;

	/** 인챈트 한 번에 드는 다이아몬드 개수. {@code enchant_cost} 가 하나도 없을 때의 값이다. */
	public static final int DIAMONDS_PER_ENCHANT = 10;

	/**
	 * <b>보유 증강 할인과 세트 할인을 둘 다</b> 가진 팀이 내는 개수.
	 *
	 * <p>이 숫자는 계산에서 나오지 않는다. 사람이 「둘 다 가지면 1개」라고 직접 정했다.
	 * 자세한 이유는 {@link #forState} 에 적어 뒀다.
	 */
	public static final int DIAMONDS_WITH_BOTH_DISCOUNTS = 1;

	/**
	 * 두 할인이 겹쳤을 때 내려갈 수 있는 가장 작은 개수.
	 *
	 * <p>한쪽만 가진 팀에는 걸리지 않는다 — {@code enchant_cost} 는 0(공짜)을 허용하고,
	 * 그 정의를 단 하나 가진 팀은 지금까지처럼 공짜여야 한다. 이 하한은 <b>겹쳤을 때의 특별
	 * 규칙</b>이 0 이나 음수로 새지 않게만 막는다. 나중에 {@link #DIAMONDS_WITH_BOTH_DISCOUNTS}
	 * 를 만지거나 여기에 다른 식을 끼워 넣어도 「겹치면 공짜」가 되는 일은 없다.
	 */
	private static final int MIN_DIAMONDS_WITH_BOTH_DISCOUNTS = 1;

	/**
	 * 「이 출처에는 {@code enchant_cost} 가 하나도 없다」는 표시.
	 *
	 * <p>가장 싼 값을 고르는 자리에 그대로 섞어 써도 되도록 실제 개수가 될 수 없는 가장 큰
	 * 값이다({@link EnchantCostEffect#MAX_DIAMONDS} 보다 훨씬 크다).
	 */
	private static final int NO_DISCOUNT = Integer.MAX_VALUE;

	/**
	 * 화면이 그릴 개수. 서버가 메뉴의 데이터 칸으로 내려보낸 값이다.
	 *
	 * <p>한 클라이언트가 인챈트 창을 둘 열 수는 없으므로 정적 값 하나로 충분하다. 창을 열 때
	 * {@code sendAllDataToRemote} 가 반드시 한 번 내려보내므로, 창이 열려 있는 동안 이 값은
	 * 언제나 그 창의 것이다. 전용 서버에서는 아무도 이 값을 쓰지 않는다.
	 */
	private static volatile int shownDiamonds = DIAMONDS_PER_ENCHANT;

	private EnchantmentDiamondCost() {
	}

	// ------------------------------------------------------------------ 팀별 개수

	/**
	 * 이 팀이 인챈트 한 번에 내는 다이아몬드 개수다.
	 *
	 * <h2>할인은 두 곳에서 온다</h2>
	 *
	 * <p>{@code enchant_cost} 는 <b>보유 증강</b>(「비술 공방」)에도 있고 <b>세트 단계</b>
	 * (「채굴 4단계」)에도 있다. 그래서 {@code state.ownedPerks} 를 훑은 뒤 반드시
	 * {@link PerkSetEffects#activeEffectsOf} 도 이어서 훑는다. <b>그 한 줄을 빠뜨리면 세트에 적어
	 * 둔 {@code enchant_cost} 는 아무 일도 하지 않는다 — 빌드도 통과하고 로그도 남지 않는다.</b>
	 *
	 * <h2>합치는 규칙</h2>
	 *
	 * <p>먼저 <b>출처마다 따로</b> 가장 싼 값을 고른다. 한 출처에 {@code enchant_cost} 가 여럿이면
	 * 지금까지처럼 가장 싼 쪽이 이긴다.
	 *
	 * <p>그다음 두 출처를 합치는데, 여기가 특별하다.
	 *
	 * <table border="1">
	 *   <caption>사람이 정한 표</caption>
	 *   <tr><th>가진 것</th><th>개수</th></tr>
	 *   <tr><td>아무것도 없음</td><td>{@value #DIAMONDS_PER_ENCHANT}</td></tr>
	 *   <tr><td>세트만 (채굴 4단계, 5)</td><td>5</td></tr>
	 *   <tr><td>증강만 (비술 공방, 2)</td><td>2</td></tr>
	 *   <tr><td><b>둘 다</b></td><td><b>{@value #DIAMONDS_WITH_BOTH_DISCOUNTS}</b></td></tr>
	 * </table>
	 *
	 * <p><b>왜 곱이나 최솟값이 아니라 특별 규칙인가.</b> 이 숫자는 어떤 식에서도 나오지 않는다.
	 * 최솟값이면 둘 다 가져도 2 이고(세트가 아무 보람도 없다), 곱하거나 나누면 10 이나 2.5 다.
	 * 「둘 다 모은 팀은 1개」는 사람이 재미를 보고 직접 고른 값이라, 식으로 흉내 내지 않고 이
	 * 조합을 <b>명시적으로</b> 적는다. 값을 바꾸려면 {@link #DIAMONDS_WITH_BOTH_DISCOUNTS} 하나만
	 * 고친다.
	 *
	 * <p>한쪽만 있으면 그 값 그대로다. 겹쳤을 때만
	 * {@link #MIN_DIAMONDS_WITH_BOTH_DISCOUNTS} 하한이 걸린다 — 겹쳐서 공짜가 되지는 않는다.
	 *
	 * <p>증강을 꺼 두었거나 가진 증강이 없으면 팀 상태 두 번만 보고 곧바로 기본값이다. 세트는
	 * 보유 증강에서 파생되므로 가진 증강이 없으면 켜진 세트도 없다.
	 */
	public static int forState(@Nullable TeamState state) {
		if (state == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return DIAMONDS_PER_ENCHANT;
		}
		int fromPerks = cheapestOwned(state);
		// 세트 정의가 비어 있으면 곧바로 빈 목록이라, 세트를 쓰지 않는 서버에는 부담이 없다.
		int fromSets = cheapestIn(PerkSetEffects.activeEffectsOf(state));
		if (fromPerks == NO_DISCOUNT) {
			return fromSets == NO_DISCOUNT ? DIAMONDS_PER_ENCHANT : fromSets;
		}
		if (fromSets == NO_DISCOUNT) {
			return fromPerks;
		}
		// 둘 다 가졌다. 여기서만 특별 규칙이다.
		return Math.max(MIN_DIAMONDS_WITH_BOTH_DISCOUNTS, DIAMONDS_WITH_BOTH_DISCOUNTS);
	}

	/**
	 * 보유 증강이 주는 가장 싼 값. 하나도 없으면 {@link #NO_DISCOUNT}.
	 *
	 * <p>풀에서 사라진 id 는 조용히 건너뛴다. 정의 파일을 손으로 고칠 수 있는 이상 저장에만 남은
	 * id 는 언제든 생긴다.
	 */
	private static int cheapestOwned(TeamState state) {
		int cheapest = NO_DISCOUNT;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			cheapest = Math.min(cheapest, cheapestIn(perk.effects()));
		}
		return cheapest;
	}

	/** 이 효과 목록에 든 {@code enchant_cost} 중 가장 싼 값. 하나도 없으면 {@link #NO_DISCOUNT}. */
	private static int cheapestIn(Iterable<PerkEffect> effects) {
		int cheapest = NO_DISCOUNT;
		for (PerkEffect effect : effects) {
			if (effect instanceof EnchantCostEffect cost) {
				cheapest = Math.min(cheapest, cost.diamonds());
			}
		}
		return cheapest;
	}

	/**
	 * 이 사람이 인챈트 한 번에 내는 다이아몬드 개수다.
	 *
	 * <p>서버의 팀원일 때만 팀을 본다. 클라이언트 쪽 플레이어는 팀 상태를 볼 수 없으므로
	 * 서버가 내려보낸 {@link #shownDiamonds} 를 쓴다. {@code clickMenuButton} 은 클라이언트에서도
	 * 그대로 도는 자리라, 여기서 무턱대고 기본값을 돌려주면 값이 1인 팀이 다이아몬드 2개를 들고도
	 * 단추를 누르지 못한다.
	 */
	public static int forPlayer(@Nullable Player player) {
		if (player instanceof ServerPlayer) {
			return forState(TeamLookup.stateOf(player.getUUID()));
		}
		return shownDiamonds();
	}

	/** 서버가 내려보낸 개수를 받아 둔다. {@link EnchantmentCostDataSlot} 만 부른다. */
	public static void rememberShown(int diamonds) {
		if (diamonds < 0 || diamonds > EnchantCostEffect.MAX_DIAMONDS) {
			// 우리가 보낸 값이 아니다. 기본값으로 물러난다.
			shownDiamonds = DIAMONDS_PER_ENCHANT;
			return;
		}
		shownDiamonds = diamonds;
	}

	/** 화면이 그릴 개수. 아직 아무것도 받지 못했으면 기본값이다. */
	public static int shownDiamonds() {
		return shownDiamonds;
	}

	/** 받아 둔 값을 기본값으로 되돌린다. 월드에서 나갈 때와 시험이 쓴다. */
	public static void resetShown() {
		shownDiamonds = DIAMONDS_PER_ENCHANT;
	}

	// ------------------------------------------------------------------ 칸별 개수

	/**
	 * 칸 하나에 드는 다이아몬드 개수다. <b>화면용</b>이며 서버가 내려보낸 값을 본다.
	 *
	 * @param slot 인챈트 칸 번호 (0 = 맨 위)
	 */
	public static int forSlot(int slot) {
		if (slot < 0 || slot >= SLOT_COUNT) {
			return 0;
		}
		return shownDiamonds();
	}

	/**
	 * 칸 하나에 드는 다이아몬드 개수다. 칸마다 다른 값을 주려면 여기만 고친다.
	 *
	 * @param player 인챈트하려는 사람. 팀을 알아내는 데 쓴다
	 * @param slot   인챈트 칸 번호 (0 = 맨 위)
	 */
	public static int forSlot(@Nullable Player player, int slot) {
		if (slot < 0 || slot >= SLOT_COUNT) {
			return 0;
		}
		return forPlayer(player);
	}

	/**
	 * 단추에 그릴 숫자다. 바닐라 요구 레벨 배열을 다이아몬드 개수 배열로 바꾼다.
	 *
	 * <p>바닐라가 0 으로 둔 칸은 <b>인챈트 후보가 없다</b>는 뜻이고 화면도 그 칸을
	 * 빈칸으로 그리므로 0 을 그대로 남긴다.
	 */
	public static int[] displayCosts(int[] vanillaCosts) {
		int[] shown = new int[vanillaCosts.length];
		for (int slot = 0; slot < vanillaCosts.length; slot++) {
			shown[slot] = vanillaCosts[slot] == 0 ? 0 : forSlot(slot);
		}
		return shown;
	}

	/** 메뉴에 달린 다이아몬드 칸. 없으면 {@code null}. */
	public static Container containerOf(Object menu) {
		return menu instanceof EnchantmentDiamondAccess access
				? access.sharedfate$diamondContainer()
				: null;
	}

	/** 다이아몬드 칸에 들어 있는 개수. */
	public static int count(Container diamonds) {
		if (diamonds == null) {
			return 0;
		}
		int found = 0;
		for (int slot = 0; slot < diamonds.getContainerSize(); slot++) {
			ItemStack stack = diamonds.getItem(slot);
			if (stack.is(Items.DIAMOND)) {
				found += stack.getCount();
			}
		}
		return found;
	}

	/** 메뉴에 든 다이아몬드 개수. 화면이 단추 숫자를 정할 때 쓴다. */
	public static int countIn(Object menu) {
		return count(containerOf(menu));
	}

	/** 한 칸을 쓸 수 있는지. 크리에이티브는 다이아몬드 없이도 된다. */
	public static boolean canAfford(Player player, Container diamonds, int slot) {
		if (player == null) {
			return false;
		}
		if (player.hasInfiniteMaterials()) {
			return true;
		}
		return count(diamonds) >= forSlot(player, slot);
	}

	/**
	 * 다이아몬드 칸에서 실제로 걷는다.
	 *
	 * <p>반드시 {@code ContainerLevelAccess.execute(...)} 람다 안에서만 불러야 한다.
	 * 클라이언트의 접근자는 {@code NULL} 이라 람다가 돌지 않는데, 람다 밖에서 깎으면
	 * 클라이언트에서도 아이템이 사라져 서버와 즉시 어긋난다.
	 *
	 * @return 실제로 걷은 개수
	 */
	public static int consume(Player player, Container diamonds, int slot) {
		if (player != null && player.hasInfiniteMaterials()) {
			return 0;
		}
		if (diamonds == null) {
			return 0;
		}
		int wanted = forSlot(player, slot);
		int taken = 0;
		for (int index = 0; index < diamonds.getContainerSize() && taken < wanted; index++) {
			ItemStack stack = diamonds.getItem(index);
			if (!stack.is(Items.DIAMOND)) {
				continue;
			}
			int fromHere = Math.min(stack.getCount(), wanted - taken);
			stack.shrink(fromHere);
			taken += fromHere;
			if (stack.isEmpty()) {
				diamonds.setItem(index, ItemStack.EMPTY);
			}
		}
		if (taken > 0) {
			diamonds.setChanged();
		}
		return taken;
	}
}
