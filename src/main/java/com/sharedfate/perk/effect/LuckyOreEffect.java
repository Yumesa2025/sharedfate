package com.sharedfate.perk.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sharedfate.perk.BlockSelector;
import com.sharedfate.perk.PerkEffect;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 광물 블록을 캘 때마다 1~3개를 무작위로 더 떨어뜨리고, 실제로 더 나온 순간에만 알린다.
 *
 * <p>정의는 {@code { "type": "lucky_ore" }} 하나뿐이고 필수 필드가 없다. 실버 「운수 좋은 날」이
 * 쓴다. 대상 블록을 좁히거나 넓히고 싶으면 {@code "blocks"} 배열을 적어 덮어쓸 수 있고, 적지
 * 않으면 {@link #DEFAULT_ORE_BLOCKS}가 그대로 쓰인다.
 *
 * <h2>{@code bonus_drop} 과 무엇이 다른가</h2>
 * <p>{@link BonusDropEffect}는 "확률로 정해진 개수"다. 이쪽은 <b>캘 때마다 반드시 굴리되 나오는
 * 개수가 매번 다르다</b>. 예전에는 0개(꽝)도 40% 나왔지만 2026-10-16 사람 결정으로 대가를
 * 걷어내 지금은 언제나 1개 이상이다. 1~3개의 비율 30:20:10 은 그대로 두었다.
 *
 * <h2>대상 블록은 「욕심 많은 곡괭이」와 같다</h2>
 * <p>{@link #DEFAULT_ORE_BLOCKS}는 {@code sharedfate:greedy_pickaxe} 의 {@code blocks} 배열을
 * 그대로 옮긴 것이다. 두 증강이 같은 것을 "광물"이라고 불러야 플레이어가 규칙을 두 번 배우지
 * 않는다. 넓은 규약 태그({@code #c:ores})와 좁은 바닐라 태그를 겹쳐 적어 둔다 —
 * 없는 태그를 적어도 예외가 나지 않고 그 항목만 조용히 지나가므로({@link BlockSelector})
 * 겹쳐 적는 편이 안전하다.
 *
 * <h2>알림</h2>
 * <p>알림은 채팅이 아니라 액션바로 간다. 이 효과는 광물을 캘 때마다 굴리므로 광맥 하나를 파면
 * 수십 번이고, 채팅으로 알리면 그 회차의 로그가 통째로 이 줄로 덮인다. 액션바는 다음 줄이 앞
 * 줄을 덮어쓰고 사라진다.
 *
 * <p><b>실제로 하나도 안 떨어졌으면 아무 말도 하지 않는다.</b> 굴린 개수는 언제나 1 이상이지만
 * 전리품표가 빈손을 줄 수 있고, 그때 "+0" 을 띄우면 방해만 된다. 알림은 캔 사람에게만 간다 —
 * 팀 전체에 뿌리면 서로 다른 사람이 동시에 캘 때 서로의 액션바를 빼앗는다.
 *
 * <h2>이 클래스가 하지 않는 일</h2>
 * <p>여기는 "어떤 블록에서, 몇 개가 나올 수 있는가"만 들고 있는 자료 그릇이다. 실제로 드롭을
 * 굴려 떨어뜨리고 액션바를 보내는 일은 {@link com.sharedfate.perk.PerkBlockBreaks}가 맡고,
 * 그 자리는 이미 쓰고 있는 {@code PlayerBlockBreakEvents.AFTER} 다.
 */
public final class LuckyOreEffect implements PerkEffect {
	/**
	 * {@code blocks} 를 안 적었을 때 쓰는 대상 목록. 실버 「욕심 많은 곡괭이」와 같은 배열이다.
	 */
	public static final List<String> DEFAULT_ORE_BLOCKS = List.of(
			"#c:ores",
			"#minecraft:coal_ores",
			"#minecraft:copper_ores",
			"#minecraft:diamond_ores",
			"#minecraft:emerald_ores",
			"#minecraft:gold_ores",
			"#minecraft:iron_ores",
			"#minecraft:lapis_ores",
			"#minecraft:redstone_ores",
			"minecraft:nether_quartz_ore",
			"minecraft:nether_gold_ore",
			"minecraft:ancient_debris");

	/**
	 * 추가 개수별 가중치. 차례로 0개·1개·2개·3개다. 0개는 가중치 0 이라 나오지 않는다.
	 *
	 * <p>기댓값은 {@code (1*30 + 2*20 + 3*10) / 60 ≈ 1.67} 이다. 합은 {@link #TOTAL_WEIGHT}와
	 * 같아야 한다.
	 */
	static final int[] EXTRA_WEIGHTS = {0, 30, 20, 10};

	/** 가중치의 합. 난수를 이 값 미만으로 굴린다. */
	static final int TOTAL_WEIGHT = 60;

	/** 한 번에 더 나올 수 있는 최대 개수. */
	public static final int MAX_EXTRA = EXTRA_WEIGHTS.length - 1;

	/**
	 * {@link #DEFAULT_ORE_BLOCKS} 로 만든 선택기.
	 *
	 * <p>목록이 상수이고 항목이 전부 올바른 이름이므로 {@link BlockSelector#fromJson} 이
	 * {@code null} 을 돌려줄 수 없다. 그래도 확인해서 터뜨리는 이유는, 나중에 목록을 고치다
	 * 오타를 내면 <b>조용히 "모든 블록"이 되는 것보다 클래스를 읽는 순간 시험이 깨지는 편이</b>
	 * 훨씬 낫기 때문이다.
	 */
	private static final BlockSelector DEFAULT_SELECTOR = buildDefaultSelector();

	private final BlockSelector blocks;

	public LuckyOreEffect(@Nullable BlockSelector blocks) {
		this.blocks = blocks == null ? DEFAULT_SELECTOR : blocks;
	}

	/**
	 * JSON에서 만든다.
	 *
	 * <p>{@code blocks} 를 안 적으면 {@link #DEFAULT_ORE_BLOCKS}, 적었는데 쓸 만한 항목이 하나도
	 * 없으면 정의를 버린다. "모든 블록"이 되는 길은 열어 두지 않았다.
	 */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		if (json == null || !json.has("blocks")) {
			return new LuckyOreEffect(DEFAULT_SELECTOR);
		}
		BlockSelector blocks = BlockSelector.fromJson(perkId, "lucky_ore", json);
		if (blocks == null) {
			return null;
		}
		return new LuckyOreEffect(blocks);
	}

	private static BlockSelector buildDefaultSelector() {
		JsonObject synthetic = new JsonObject();
		JsonArray array = new JsonArray();
		for (String entry : DEFAULT_ORE_BLOCKS) {
			array.add(entry);
		}
		synthetic.add("blocks", array);
		BlockSelector selector = BlockSelector.fromJson("sharedfate:lucky_ore", "lucky_ore", synthetic);
		if (selector == null) {
			throw new IllegalStateException("lucky_ore 의 기본 광물 목록이 올바르지 않습니다");
		}
		return selector;
	}

	/** 이 블록에 걸리는 효과인가. */
	public boolean appliesTo(@Nullable BlockState state) {
		return blocks.matches(state);
	}

	public BlockSelector blocks() {
		return blocks;
	}

	// ------------------------------------------------------------------ 개수 굴리기

	/**
	 * 이번에 더 줄 개수를 굴린다. 난수원이 있으면 언제나 1 이상이다.
	 *
	 * <p>난수원이 없으면 0으로 본다. 난수를 못 굴렸다고 이득을 확정으로 줄 수는 없다.
	 */
	public int rollExtra(@Nullable RandomSource random) {
		return random == null ? 0 : extraForRoll(random.nextInt(TOTAL_WEIGHT));
	}

	/**
	 * 굴린 값에 해당하는 추가 개수. 분포 계산만 떼어 놓은 것이라 월드 없이 시험할 수 있다.
	 *
	 * @param roll {@code 0} 이상 {@link #TOTAL_WEIGHT} 미만의 값
	 */
	public static int extraForRoll(int roll) {
		if (roll < 0) {
			return 0;
		}
		int cursor = 0;
		for (int extra = 0; extra < EXTRA_WEIGHTS.length; extra++) {
			cursor += EXTRA_WEIGHTS[extra];
			if (roll < cursor) {
				return extra;
			}
		}
		return MAX_EXTRA;
	}

	/** 개수 {@code extra} 가 나올 확률. 시험과 문서용이다. */
	public static double chanceOf(int extra) {
		if (extra < 0 || extra >= EXTRA_WEIGHTS.length) {
			return 0.0;
		}
		return EXTRA_WEIGHTS[extra] / (double) TOTAL_WEIGHT;
	}

	// ------------------------------------------------------------------ 알림

	/**
	 * 캔 사람의 액션바에 띄울 문구. 예: {@code [증강] 운수 좋은 날 — 다이아몬드 +2}
	 *
	 * <p>증강 이름을 인자로 받는다. 효과는 자기가 어느 증강에 붙어 있는지 모르고, 정의 파일에서
	 * 이름을 바꾸면 문구도 따라 바뀌어야 하기 때문이다. 아이템 이름은 {@link Component} 로 받아
	 * 그대로 이어 붙인다 — 번역 키를 살려 두어야 플레이어의 언어 설정대로 보인다.
	 */
	public static Component announcement(@Nullable String perkName, @Nullable Component itemName,
			int extra) {
		String label = perkName == null || perkName.isBlank() ? "운수 좋은 날" : perkName;
		MutableComponent message = Component.literal("[증강] " + label + " —");
		if (itemName != null) {
			message.append(Component.literal(" ")).append(itemName);
		}
		return message.append(Component.literal(" +" + Math.max(0, extra)));
	}
}
