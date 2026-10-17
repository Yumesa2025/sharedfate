package com.sharedfate.perk.effect;

import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * 나무 도끼를 들고 우클릭하면, 인벤토리에서 종류를 가리지 않고 나무를 {@value #WOOD_COST}개
 * 소모하고 무작위 광물 하나를 준다. 부작용은 없다.
 *
 * <p>정의는 {@code { "type": "ore_exchange" }} 하나뿐이고 필드가 없다. 실버 「나무꾼의 욕심」이
 * 쓴다. 값은 전부 코드 상수로 고정돼 있다. 소모량은 {@link #WOOD_COST} 상수 하나만 고치면
 * 되도록 실행부({@link com.sharedfate.perk.PerkOreExchange})가 이 값을 직접 읽어 쓴다.
 *
 * <h2>"나무"의 범위</h2>
 * <p>{@code #minecraft:logs} 태그를 그대로 쓴다 — 원목 · 나무(6면 나무껍질 블록) · 벗긴 원목 ·
 * 벗긴 나무 · 네더 줄기/균사까지 전부 포함하고, 판자는 빠진다. 판자를 넣지 않은 이유는 원목
 * 1개가 판자 4개가 되므로, 판자까지 받아 주면 사실상 나무 6개 남짓으로 광물을 뽑는 셈이 되어
 * 25개라는 대가가 무의미해지기 때문이다.
 *
 * <h2>무엇을 주는가</h2>
 * <p>실제 채광 결과와 맞춘다 — 철·금·구리는 제련 전 원석({@code raw_iron} 등, 채굴하면 그대로
 * 나오는 형태), 다이아몬드는 원석이 없으므로 {@code diamond}, 석탄은 {@code coal} 그대로다.
 * 여기에 2%짜리 「잭팟」({@link #JACKPOT})이 하나 더 있다 — 다이아몬드 {@value #JACKPOT_COUNT}개,
 * 곧 두 스택이다.
 *
 * <p>{@link PerkEffect#apply}로 팀원에게 붙일 것이 없다. 실제로 우클릭을 감지하고 나무를 세고
 * 광물을 주는 일은 {@link com.sharedfate.perk.PerkOreExchange}가 맡는다.
 */
public final class OreExchangeEffect implements PerkEffect {
	/**
	 * 한 번 교환할 때 소모하는 나무 개수.
	 *
	 * <p>실행부와 안내 문구가 모두 이 값을 읽으므로 여기만 고치면 된다.
	 */
	public static final int WOOD_COST = 25;

	/** 이 도구를 주 손에 들고 우클릭해야 한다. */
	public static final Identifier TOOL = Identifier.withDefaultNamespace("wooden_axe");

	/**
	 * 결과 하나.
	 *
	 * <p>{@code count} 는 한 번에 주는 개수다. 잭팟을 뺀 나머지는 모두 1개라 개수를 적지 않는
	 * 두 인자 생성자를 그대로 쓴다.
	 *
	 * <p>한 묶음에 담기지 않는 개수({@link #JACKPOT_COUNT})도 여기서는 그냥 숫자로 들고 있다.
	 * 스택 한도에 맞춰 쪼개는 일은 지급하는 쪽({@code PerkOreExchange})이 맡는다 — 한도는
	 * 아이템마다 다르고, 그 값은 살아 있는 레지스트리를 봐야 알 수 있기 때문이다.
	 */
	public record Result(Identifier itemId, int weight, int count) {
		public Result {
			if (weight <= 0) {
				throw new IllegalArgumentException("가중치는 1 이상이어야 합니다: " + weight);
			}
			if (count <= 0) {
				throw new IllegalArgumentException("개수는 1 이상이어야 합니다: " + count);
			}
		}

		/** 개수를 적지 않으면 1개다. 잭팟을 뺀 다섯 항목이 전부 이 꼴이다. */
		public Result(Identifier itemId, int weight) {
			this(itemId, weight, 1);
		}

		/** 이 결과가 나올 확률(0~1). 가중치 합이 100이라 백분율과 값이 같다. */
		public double chance() {
			return weight / (double) TOTAL_WEIGHT;
		}
	}

	/**
	 * 「잭팟」으로 주는 다이아몬드 개수. 64 × 2 — 정확히 두 스택이다.
	 *
	 * <p>스택 한도를 넘는 값이라 지급할 때 반드시 쪼개야 한다. 까닭은 {@link Result} 에 있다.
	 */
	public static final int JACKPOT_COUNT = 128;

	/** 「잭팟」의 가중치. 가중치 합이 100이므로 이 값이 곧 2%다. */
	public static final int JACKPOT_WEIGHT = 2;

	/**
	 * 2%로 터지는 대박. 다이아몬드 {@value #JACKPOT_COUNT}개를 한 번에 준다.
	 *
	 * <p>{@code diamond} 는 1개짜리 항목으로도 이미 들어 있다. 같은 아이템이 두 항목에 나뉘어
	 * 들어간 꼴이라, 아이템 id 로 항목을 찾는 코드가 있으면 둘을 헷갈릴 수 있다. 찾을 때는
	 * 언제나 {@link Result} 자체를 그대로 다뤄야 한다.
	 */
	public static final Result JACKPOT = new Result(
			Identifier.withDefaultNamespace("diamond"), JACKPOT_WEIGHT, JACKPOT_COUNT);

	/**
	 * 다이아몬드 10% · 금 15% · 철 13% · 구리 30% · 석탄 30% · 잭팟 2%.
	 * 순서대로 가중치 누적선을 이룬다.
	 *
	 * <p>가중치 합이 100이어야 「가중치 = 확률 %」라는 읽기 쉬운 성질이 유지되므로, 항목을 더할
	 * 때는 어딘가에서 같은 만큼을 덜어 와야 한다.
	 *
	 * <p>잭팟은 맨 뒤에 있다. 누적선의 마지막 구간(98~99)이 되어 「굴림값이 98 이상일 때만
	 * 잭팟」이라는 경계가 한눈에 보인다.
	 */
	public static final List<Result> RESULTS = List.of(
			new Result(Identifier.withDefaultNamespace("diamond"), 10),
			new Result(Identifier.withDefaultNamespace("raw_gold"), 15),
			new Result(Identifier.withDefaultNamespace("raw_iron"), 13),
			new Result(Identifier.withDefaultNamespace("raw_copper"), 30),
			new Result(Identifier.withDefaultNamespace("coal"), 30),
			JACKPOT);

	public static final int TOTAL_WEIGHT =
			RESULTS.stream().mapToInt(Result::weight).sum();

	/** 상태가 없으므로 하나만 만들어 돌려쓴다. */
	public static final OreExchangeEffect INSTANCE = new OreExchangeEffect();

	private OreExchangeEffect() {
	}

	/**
	 * {@link #RESULTS}의 가중치대로 결과 하나를 뽑는다.
	 *
	 * <p>플레이어를 읽지 않는 순수 계산이라 살아 있는 서버 없이 시험할 수 있다.
	 *
	 * <p>아이템 id 가 아니라 {@link Result} 를 통째로 돌려준다. 개수가 항목마다 다르므로 id 만
	 * 받아서는 몇 개를 줘야 할지 알 수 없고, 다이아몬드는 id 가 같은 항목이 둘(1개짜리와 잭팟)이라
	 * id 로 되찾을 수도 없기 때문이다.
	 */
	public static Result rollResult(RandomSource random) {
		return resultForRoll(random.nextInt(TOTAL_WEIGHT));
	}

	/**
	 * 굴림값 하나를 누적선에 대어 결과를 고른다.
	 *
	 * <p>{@link #rollResult} 가 난수를 뽑은 뒤 부르는 실제 판정부다. 난수와 떨어져 있어 구간
	 * 경계(예: 「97이면 석탄, 98이면 잭팟」)를 시험으로 못박을 수 있다.
	 *
	 * @param roll 0 이상 {@link #TOTAL_WEIGHT} 미만의 굴림값. 벗어난 값은 양 끝으로 자른다
	 */
	public static Result resultForRoll(int roll) {
		int cumulative = 0;
		for (Result result : RESULTS) {
			cumulative += result.weight();
			if (roll < cumulative) {
				return result;
			}
		}
		// 굴림값이 범위를 벗어났을 때만 여기 닿는다. 마지막 것으로 막는다.
		return RESULTS.get(RESULTS.size() - 1);
	}

	/** 이번 결과가 「잭팟」인가. 개수까지 같아야 하므로 항목 자체를 견준다. */
	public static boolean isJackpot(Result result) {
		return JACKPOT.equals(result);
	}

	/** JSON에서 만든다. 읽을 필드가 없어 언제나 성공한다. */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		return INSTANCE;
	}
}
