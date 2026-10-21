package com.sharedfate.perk;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 증강 후보 추첨.
 *
 * <p>구간마다 등급이 하나로 정해지고 <b>그 등급에서만</b> 후보를 뽑는다. 한 라운드에
 * 나오는 3개는 전부 같은 등급이다. 이미 보유한 증강은 어떤 경우에도 후보에서 빠지고,
 * 한 번의 추첨 안에서 같은 증강이 두 번 나오지 않는다.
 *
 * <p>후보 풀은 호출자가 넘긴다. 난수도 주입받으므로 고정 시드를 주면 결과가 항상 같다.
 *
 * <h2>전제조건({@code requires})</h2>
 * <p>{@link Perk#requires()} 가 붙은 증강은 <b>팀이 그 조건을 갖췄을 때만</b> 후보에 든다.
 * 거르는 자리는 {@link #eligibleByRarity} 한 곳이라 등급 폴백·다시 뽑기까지 함께 걸린다.
 *
 * <p>이 클래스는 팀 상태를 직접 보지 않는다. {@code no_silver_offers} 의 {@code silverBlocked}
 * 와 같은 방식으로 <b>호출자가 갖춘 조건의 집합을 넘긴다.</b> 그래야 월드 없이 순수 계산으로
 * 시험할 수 있다. 팀 상태에서 그 집합을 만드는 한 줄은
 * {@link PerkSwapRules#satisfiedRequirements}다.
 *
 * <p>조건 집합을 받지 않는 옛 시그니처들은 {@link Perk.Requirement#ALL} 을 넘긴 것과 같아
 * 아무것도 거르지 않는다 — 전제조건이 생기기 전과 결과가 똑같다.
 *
 * <h2>「드래곤전 대비」 풀 (0.44.0-dev)</h2>
 * <p>{@link PerkRarity#TRIAL} 은 드래곤 시련 팀의 45·50 구간({@link PerkMilestones#isTrialMilestone})
 * 에서만 쓰는 따로 된 통이다. {@link #rarityFor} 가 그 두 구간에 난수 없이 이 값을 돌려주고,
 * {@link #fallbackOrder} 가 이 통과 등급 셋을 서로 끌어오지 않으며, {@link #eligibleByRarity} 가
 * 40 이하 구간에서는 이 통을 아예 비워 둔다 — 세 겹이다. 후보 수는 {@link #TRIAL_OPTIONS} 장이다
 * ({@link #optionCount}).
 *
 * <h2>가중치({@link Perk#weight})</h2>
 * <p>한 통에서 꺼낼 때 가중치에 비례해 뽑는다(비복원). <b>통 안의 가중치가 모두 같으면</b> 예전처럼
 * {@code nextInt(크기)} 한 번으로 고르므로, 가중치를 안 쓰는 통은 같은 시드에서 결과도 난수
 * 소비도 그대로다({@link #pickFrom}).
 */
public final class PerkDraft {
	/** 한 번에 제시하는 기본 후보 수. */
	public static final int DEFAULT_OPTIONS = 3;

	/**
	 * 「드래곤전 대비」 구간(45·50)에 내놓는 후보 수.
	 *
	 * <p>풀이 열두 장뿐이고 두 번만 고르므로, 세 장씩 보여 주면 두 구간이 풀의 절반을 훑어 회차마다
	 * 같은 판이 된다. 사람이 「3장이 아니라 2장」으로 정했다.
	 */
	public static final int TRIAL_OPTIONS = 2;

	/**
	 * 프리즘 라운드로 <b>고정된</b> 구간들. 15 하나뿐이다.
	 *
	 * <p>한 회차에 나오는 프리즘 라운드는 <b>고정 1회 + 확률로 최대 {@link #MAX_EXTRA_PRISM}회</b>다.
	 */
	public static final Set<Integer> PRISM_MILESTONES = Set.of(15);

	/**
	 * 등급 확률표 한 줄. 세 값을 더하면 반드시 100 이다.
	 *
	 * @param silver 실버가 나올 확률(퍼센트)
	 * @param gold   골드가 나올 확률(퍼센트)
	 * @param prism  프리즘가 나올 확률(퍼센트)
	 */
	public record RarityOdds(int silver, int gold, int prism) {
	}

	/**
	 * 고정이 아닌 구간(5·10·20·25·30·35·40)의 등급 확률표.
	 *
	 * <p>줄 번호가 곧 <b>이번 회차에 확률로 이미 나온 프리즘 라운드의 수</b>다 — 0번 줄이 아직
	 * 하나도 안 나온 상태, 마지막 줄이 한도({@link #MAX_EXTRA_PRISM})에 닿아 프리즘가 더는 나오지
	 * 않는 상태다. <b>15 구간의 고정 프리즘은 이 수에 넣지 않는다.</b> 고정은 확률과 무관하게
	 * 언제나 한 번 나오는 것이라, 그것까지 세면 확률로 얻을 수 있는 프리즘가 하나 줄어든다.
	 */
	public static final List<RarityOdds> ODDS_BY_EXTRA_PRISM = List.of(
			new RarityOdds(45, 45, 10),
			new RarityOdds(46, 51, 3),
			new RarityOdds(50, 50, 0));

	/**
	 * 한 회차에 <b>확률로</b> 추가로 나올 수 있는 프리즘 라운드의 최대 수.
	 *
	 * <p>{@link #ODDS_BY_EXTRA_PRISM} 의 마지막 줄 번호와 같은 값이다. 이 수에 닿으면 그 줄의
	 * 프리즘 확률이 0 이라 더 나오지 않고, {@link #PRISM_BOOST_PERCENT} 보너스도 붙지 않는다.
	 *
	 * <p>15 구간의 고정 프리즘을 더하면 한 회차의 프리즘 라운드는 최대 세 번이다.
	 */
	public static final int MAX_EXTRA_PRISM = 2;

	/**
	 * 「원정 준비물」({@code sharedfate:expedition_kit})을 가진 팀이 얹어 받는 프리즘 확률(%p).
	 *
	 * <p>얹는 만큼 <b>골드에서 뺀다.</b>
	 */
	public static final int PRISM_BOOST_PERCENT = 3;

	private PerkDraft() {
	}

	/**
	 * 이 구간에 쓸 확률표 한 줄을 만든다. 세 값의 합은 언제나 100 이다.
	 *
	 * <p>순서가 중요하다. <b>한도 판정 → 프리즘 보너스 → 실버 차단</b> 순으로 얹는다.
	 * <ol>
	 *   <li>{@code extraPrismCount} 를 0 과 {@link #MAX_EXTRA_PRISM} 사이로 접어 줄을 고른다.
	 *       손상된 저장에서 음수나 큰 값이 흘러들어와도 표 밖을 짚지 않는다.</li>
	 *   <li>프리즘 보너스는 <b>그 줄의 프리즘 확률이 0 보다 클 때만</b> 붙는다. 한도에 닿은 줄에
	 *       붙이면 「2개까지」라는 한도가 3%씩 새어 나간다.</li>
	 *   <li>실버가 막혔으면 실버 몫을 <b>전부 골드로</b> 넘긴다. 프리즘 몫은 건드리지 않는다.</li>
	 * </ol>
	 *
	 * @param extraPrismCount 이번 회차에 확률로 이미 나온 프리즘 라운드 수. 15 의 고정은 빼고 센다
	 * @param prismBoost      「원정 준비물」을 가지고 있는가
	 * @param silverBlocked   실버 후보가 통째로 막혀 있는가({@code no_silver_offers})
	 */
	public static RarityOdds oddsFor(int extraPrismCount, boolean prismBoost, boolean silverBlocked) {
		int row = Math.max(0, Math.min(MAX_EXTRA_PRISM, extraPrismCount));
		RarityOdds odds = ODDS_BY_EXTRA_PRISM.get(row);
		if (prismBoost && odds.prism() > 0) {
			odds = new RarityOdds(odds.silver(), odds.gold() - PRISM_BOOST_PERCENT,
					odds.prism() + PRISM_BOOST_PERCENT);
		}
		if (silverBlocked) {
			odds = new RarityOdds(0, odds.gold() + odds.silver(), odds.prism());
		}
		return odds;
	}

	/**
	 * 이 구간에 배정할 등급을 정한다.
	 *
	 * <p>{@link #PRISM_MILESTONES}에 속한 구간은 무작위가 아니라 <b>항상</b> 프리즘다. 나머지
	 * 구간은 {@link #oddsFor} 가 만든 확률표 한 줄로 굴린다.
	 *
	 * <p><b>회차 상태를 여기서 읽지 않는다.</b> 「이번 회차에 프리즘가 몇 번 나왔는가」도
	 * 「실버가 막혔는가」도 전부 인자로 받는다. 실버 차단 판정은 호출자({@code PerkManager})가 한다.
	 *
	 * @param milestone       레벨 구간 (5, 10, …, 40)
	 * @param extraPrismCount 이번 회차에 확률로 이미 나온 프리즘 라운드 수. 15 의 고정은 빼고 센다
	 * @param silverBlocked   실버 후보가 통째로 막혀 있는가. 그러면 실버 몫이 전부 골드로 간다
	 * @param prismBoost      「원정 준비물」을 가지고 있는가. 프리즘 확률이 골드를 깎아 올라간다
	 * @param random          난수원. 고정 시드를 주면 결과가 결정론적이다
	 */
	public static PerkRarity rarityFor(int milestone, int extraPrismCount, boolean silverBlocked,
			boolean prismBoost, RandomSource random) {
		// 시련 팀의 45·50 은 굴리지 않는다. 난수도 쓰지 않으므로 그 앞 구간들의 결과가 그대로다.
		if (PerkMilestones.isTrialMilestone(milestone)) {
			return PerkRarity.TRIAL;
		}
		if (PRISM_MILESTONES.contains(milestone)) {
			return PerkRarity.PRISM;
		}
		RarityOdds odds = oddsFor(extraPrismCount, prismBoost, silverBlocked);
		if (random == null) {
			// 난수원이 없으면 굴릴 수가 없다. 터뜨리는 대신 가장 낮은 등급으로 두되, 실버가
			// 막혀 있으면 골드다 — 굴리지 못했다는 사정이 차단을 뚫는 구멍이 되면 안 된다.
			return odds.silver() > 0 ? PerkRarity.SILVER : PerkRarity.GOLD;
		}
		int roll = random.nextInt(100);
		if (roll < odds.silver()) {
			return PerkRarity.SILVER;
		}
		return roll < odds.silver() + odds.gold() ? PerkRarity.GOLD : PerkRarity.PRISM;
	}

	/**
	 * 회차 상태를 모르는 채로 등급을 정하는 옛 시그니처.
	 *
	 * <p>「프리즘가 아직 하나도 안 나왔고, 실버도 막히지 않았고, 「원정 준비물」도 없다」로 보고
	 * {@link #rarityFor(int, int, boolean, boolean, RandomSource)} 에 넘긴다. 회차 상태를 들고
	 * 있는 호출부는 반드시 다섯 인자짜리를 써야 한다 — 이쪽으로 부르면 프리즘 한도가 영영
	 * 차지 않아 한 회차에 프리즘가 몇 번이고 나온다.
	 */
	public static PerkRarity rarityFor(int milestone, RandomSource random) {
		return rarityFor(milestone, 0, false, false, random);
	}

	/**
	 * 후보가 모자랄 때 어느 등급에서 채울지의 우선순위.
	 *
	 * <p>실버 부족 → 골드 → 프리즘, 골드 부족 → 실버 → 프리즘, 프리즘 부족 → 골드 → 실버.
	 * 등급 차이가 작은 쪽부터 끌어온다.
	 */
	public static List<PerkRarity> fallbackOrder(PerkRarity rarity) {
		return switch (rarity) {
			case SILVER -> List.of(PerkRarity.SILVER, PerkRarity.GOLD, PerkRarity.PRISM);
			case GOLD -> List.of(PerkRarity.GOLD, PerkRarity.SILVER, PerkRarity.PRISM);
			case PRISM -> List.of(PerkRarity.PRISM, PerkRarity.GOLD, PerkRarity.SILVER);
			// 「드래곤전 대비」는 모자라도 등급에서 끌어오지 않고, 등급도 이 통을 끌어오지 않는다.
			// 모자라면 카드가 적게 뜬다 — 45·50 에 실버가 끼거나 40 이하에 이 풀이 끼는 것보다 낫다.
			case TRIAL -> List.of(PerkRarity.TRIAL);
		};
	}

	/** 이 분류의 라운드가 내놓는 후보 수. 「드래곤전 대비」만 {@link #TRIAL_OPTIONS}, 나머지는 3. */
	public static int optionCount(PerkRarity rarity) {
		return rarity == PerkRarity.TRIAL ? TRIAL_OPTIONS : DEFAULT_OPTIONS;
	}

	/**
	 * 구간에 맞는 등급을 정한 뒤 후보를 최대 {@code count}개 뽑는다.
	 *
	 * <p>정해진 등급에 남은 후보가 모자라면 {@link #fallbackOrder} 순서대로 다른 등급에서
	 * 채운다. 그래도 부족하면 가능한 만큼만 돌려주고, 하나도 못 뽑으면 빈 리스트다.
	 *
	 * <p><b>회차 상태를 모르는 길이다.</b> 등급을 옛 {@link #rarityFor(int, RandomSource)} 로
	 * 정하므로 프리즘 한도도 실버 차단도 걸리지 않는다. 실제 게임의 구간 추첨은
	 * {@code PerkManager} 가 등급을 먼저 정한 뒤 {@link #draw(PerkRarity, int, List, List,
	 * RandomSource, int)} 를 부르는 길로 지나간다.
	 *
	 * @param milestone 이 추첨이 속한 레벨 구간 (5, 10, …, 40)
	 * @param pool      전체 증강 목록
	 * @param owned     팀이 이미 보유한 증강의 id 목록
	 * @param random    난수원. 고정 시드를 주면 결과가 결정론적이다
	 * @param count     뽑을 개수
	 */
	public static List<String> draw(int milestone, List<Perk> pool, List<String> owned,
			RandomSource random, int count) {
		if (pool == null || pool.isEmpty() || random == null || count <= 0) {
			return List.of();
		}
		return draw(rarityFor(milestone, random), milestone, pool, owned, random, count);
	}

	/** 기본 개수(3개)로 뽑는다. */
	public static List<String> draw(int milestone, List<Perk> pool, List<String> owned,
			RandomSource random) {
		return draw(milestone, pool, owned, random, DEFAULT_OPTIONS);
	}

	/**
	 * 등급을 직접 지정해 후보를 최대 {@code count}개 뽑는다. 구간(레벨) 정보가 없으므로
	 * {@link Perk#minLevel} 로 거르지 않는다 — {@code min_level} 이 설정된 증강도 그대로 뽑힐
	 * 수 있다.
	 *
	 * <p>도박꾼처럼 "이 구간은 무조건 이 등급"인 경로가 여기로 들어온다. 구간을 안다면
	 * {@link #draw(PerkRarity, int, List, List, RandomSource, int)} 를 대신 써야
	 * {@code min_level} 이 지켜진다.
	 *
	 * @param rarity 뽑을 등급
	 */
	public static List<String> draw(PerkRarity rarity, List<Perk> pool, List<String> owned,
			RandomSource random, int count) {
		return draw(rarity, PerkMilestones.MAX, pool, owned, random, count);
	}

	/**
	 * 등급과 구간을 함께 지정해 후보를 최대 {@code count}개 뽑는다.
	 *
	 * <p>{@link Perk#minLevel} 이 이 {@code milestone} 보다 큰 증강은 후보에서 빠진다 —
	 * "특정 구간부터만 나오는 증강"(예: 프리즘 「환골탈태」, 30렙부터)을 이 한 곳에서 거른다.
	 * 일반 구간 추첨({@link #draw(int, List, List, RandomSource, int)})과, 구간을 아는 채로
	 * 등급을 고정하는 경로(도박꾼의 20·25 실버 고정 등)가 여기를 함께 쓴다.
	 *
	 * @param rarity    뽑을 등급
	 * @param milestone 이 추첨이 속한 레벨 구간
	 */
	public static List<String> draw(PerkRarity rarity, int milestone, List<Perk> pool,
			List<String> owned, RandomSource random, int count) {
		return draw(rarity, milestone, pool, owned, List.of(), random, count);
	}

	/**
	 * 등급·구간에 더해 <b>팀이 갖춘 전제조건</b>까지 지정해 뽑는다. 구간 추첨의 정식 경로다.
	 *
	 * <p>{@link Perk#requires()} 가 붙은 증강은 {@code satisfied} 에 그 조건이 들어 있을 때만
	 * 후보에 든다. 위치 교환을 끈 팀에게 교환 증강을 보여 주지 않는 것이 이 인자 하나다.
	 *
	 * <p><b>이름이 {@code draw} 가 아닌 이유</b>는 {@code null} 때문이다. 인자 수가 같은
	 * {@code draw} 가 이미 회피 목록({@code List<String>})을 받고 있어서, 같은 이름으로 두면
	 * {@code draw(..., null, random, 3)} 처럼 넘기던 자리가 어느 쪽인지 정해지지 않아 컴파일이
	 * 깨진다.
	 *
	 * @param satisfied 이 팀이 갖춘 전제조건들. 빈 집합이면 전제조건이 붙은 증강이 모두 빠지고,
	 *                  {@link Perk.Requirement#ALL} 이면 아무것도 걸리지 않는다
	 */
	public static List<String> drawFor(PerkRarity rarity, int milestone, List<Perk> pool,
			List<String> owned, Set<Perk.Requirement> satisfied, RandomSource random, int count) {
		return drawFor(rarity, milestone, pool, owned, List.of(), satisfied, random, count);
	}

	/**
	 * 등급·구간에 더해 <b>이번에는 피하고 싶은 후보</b>까지 지정해 뽑는다. 「다시 뽑기」가 쓴다.
	 *
	 * <p>{@code owned} 와 {@code avoid} 는 성격이 다르다. 보유 증강은 <b>절대</b> 나오면 안 되지만,
	 * 피하고 싶은 후보는 <b>되도록</b> 나오지 않으면 되는 것이다.
	 *
	 * <p>우선순위는 <b>등급이 먼저, 회피가 나중</b>이다. 한 등급 안에서 회피 대상이 아닌
	 * 것을 먼저 다 쓰고, 모자라면 <b>같은 등급의 회피 대상</b>을 꺼낸다. 그것마저 바닥나야
	 * {@link #fallbackOrder} 의 다음 등급으로 내려간다. 반대로 하면 「실버 라운드에서 다시
	 * 뽑았더니 골드가 나왔다」가 된다.
	 *
	 * <p>다시 뽑기는 이 방식으로 <b>직전에 보여 준 3장</b>을 넘긴다. 등급이 실버(30개)라면 거의
	 * 언제나 회피 대상이 아닌 쪽에서 다 채워져 방금 본 카드가 돌아오지 않는다.
	 *
	 * @param avoid 되도록 다시 내보내지 않을 증강 id. null 이나 빈 목록이면 아무것도 피하지 않는다
	 */
	public static List<String> draw(PerkRarity rarity, int milestone, List<Perk> pool,
			List<String> owned, List<String> avoid, RandomSource random, int count) {
		return drawFor(rarity, milestone, pool, owned, avoid, Perk.Requirement.ALL, random, count);
	}

	/**
	 * 회피 목록과 전제조건을 함께 지정해 뽑는다. 다른 뽑기들이 모두 여기로 모인다.
	 *
	 * <p>전제조건은 회피 목록보다 <b>훨씬 강하다.</b> 회피는 「되도록」이라 모자라면 다시 꺼내
	 * 쓰지만, 전제조건에 걸린 증강은 어느 등급으로 폴백해도 끝까지 나오지 않는다 — 갖추지 못한
	 * 팀에게는 아무 일도 하지 않는 카드이므로 채워 넣을 값이 없다.
	 *
	 * @param satisfied 이 팀이 갖춘 전제조건들
	 */
	public static List<String> drawFor(PerkRarity rarity, int milestone, List<Perk> pool,
			List<String> owned, List<String> avoid, Set<Perk.Requirement> satisfied,
			RandomSource random, int count) {
		return drawFor(rarity, milestone, pool, owned, avoid, satisfied, random, count, false);
	}

	/**
	 * 실버 차단까지 함께 지정해 뽑는다. <b>모든 뽑기가 결국 여기로 모인다.</b>
	 *
	 * <h2>차단은 등급 추첨만으로 끝나지 않는다</h2>
	 * <p>{@code no_silver_offers}(「원정 준비물」)는 {@link #oddsFor} 에서 실버 <b>라운드</b>를
	 * 없앤다. 그런데 그것만으로는 부족하다 — 골드 라운드에서 <b>아직 안 가진 골드가 3장
	 * 미만</b>이면 {@link #fallbackOrder} 가 실버를 끌어와 채우기 때문이다. 카드에 「이 뒤로는
	 * 실버 증강이 후보에 나오지 않습니다」라고 적어 두고 실버를 보여 주는 셈이 된다.
	 *
	 * <p>그래서 차단이 켜지면 <b>실버 통을 아예 건너뛴다.</b> 그 결과 채울 것이 모자라면
	 * 카드가 세 장보다 적게 나온다 — 약속을 어기느니 적게 주는 쪽이 낫다. 「도박 3단계」의
	 * 프리즘 전용이 같은 판단을 이미 하고 있다.
	 *
	 * @param silverBlocked 실버가 통째로 막혀 있는가. 막혔으면 어느 등급에서 폴백하든 실버는
	 *                      끌어오지 않는다
	 */
	public static List<String> drawFor(PerkRarity rarity, int milestone, List<Perk> pool,
			List<String> owned, List<String> avoid, Set<Perk.Requirement> satisfied,
			RandomSource random, int count, boolean silverBlocked) {
		return drawFor(rarity, milestone, pool, owned, avoid, satisfied, random, count,
				silverBlocked, Set.of(), 0);
	}

	/**
	 * 세트 모으기 보정까지 함께 지정해 뽑는다. <b>모든 뽑기가 결국 여기로 모인다.</b>
	 *
	 * <h2>보정 칸</h2>
	 * <p>{@code focusTypes} 가 비어 있지 않고 {@code focusPercent} 가 0 보다 크면, 후보를 뽑기
	 * 전에 {@code focusPercent}% 확률로 <b>한 칸을 보정 칸</b>으로 쓴다. 이번에 실제로 뽑는
	 * 등급(실버 차단으로 건너뛰는 등급을 뺀 폴백 순서의 첫 등급)의 적격 후보 가운데 「모으는
	 * 유형을 하나라도 가진」 것 하나를 균등하게 고른다. 회피 대상은 기존 원칙대로 <b>뒤로
	 * 미룬다</b> — 회피 아닌 후보가 하나라도 있으면 그중에서만 고르고, 없을 때만 회피 대상에서
	 * 고른다. 나머지 칸은 보정 칸으로 뽑힌 것을 뺀 채 기존 방식 그대로 채운다. 보정 칸이
	 * 들어간 경우에만 최종 순서를 섞어 늘 첫 자리에 오지 않게 한다.
	 *
	 * <p><b>보정이 일어날 수 없으면 난수를 하나도 더 쓰지 않는다.</b> 모으는 세트가 없거나
	 * 확률이 0 이거나 그 등급에 해당 후보가 없으면, 같은 시드에서 보정이 생기기 전과 결과가
	 * 똑같다.
	 *
	 * @param focusTypes   팀이 모으는 중인 세트 유형들({@link PerkSets#collectingTypes})
	 * @param focusPercent 보정 칸이 들어갈 확률(0~100). 0 이하이면 끈다
	 */
	public static List<String> drawFor(PerkRarity rarity, int milestone, List<Perk> pool,
			List<String> owned, List<String> avoid, Set<Perk.Requirement> satisfied,
			RandomSource random, int count, boolean silverBlocked,
			Set<PerkSetType> focusTypes, int focusPercent) {
		if (rarity == null || pool == null || pool.isEmpty() || random == null || count <= 0) {
			return List.of();
		}

		Map<PerkRarity, List<Perk>> remaining = eligibleByRarity(pool, owned, milestone, satisfied);
		Map<PerkRarity, List<Perk>> avoided = extract(remaining, idSet(avoid));
		List<String> drawn = new ArrayList<>(count);
		boolean focused = takeFocusSlot(drawn, rarity, silverBlocked, remaining, avoided, random,
				focusTypes, focusPercent);
		for (PerkRarity bucketRarity : fallbackOrder(rarity)) {
			if (silverBlocked && bucketRarity == PerkRarity.SILVER) {
				continue;
			}
			// 한 등급 안에서 회피 대상이 아닌 것을 먼저 다 쓰고, 모자랄 때만 회피 대상을 꺼낸다.
			// 등급을 내려가기 전에 반드시 이 순서를 지켜야 한다 — 남은 실버가 회피 대상뿐인데
			// 골드를 끌어오면 다시 뽑기가 등급을 바꾸는 셈이 된다.
			takeFrom(drawn, remaining.get(bucketRarity), random, count);
			takeFrom(drawn, avoided.get(bucketRarity), random, count);
			if (drawn.size() >= count) {
				break;
			}
		}
		if (focused) {
			shuffle(drawn, random);
		}
		return List.copyOf(drawn);
	}

	/**
	 * 보정 칸 한 장을 {@code drawn} 에 먼저 담는다. 담았으면 참.
	 *
	 * <p>후보가 없으면 확률을 굴리지도 않는다 — 보정할 수 없는 추첨이 난수를 쓰면 기존 시드의
	 * 결과가 달라진다.
	 */
	private static boolean takeFocusSlot(List<String> drawn, PerkRarity rarity,
			boolean silverBlocked, Map<PerkRarity, List<Perk>> remaining,
			Map<PerkRarity, List<Perk>> avoided, RandomSource random,
			Set<PerkSetType> focusTypes, int focusPercent) {
		if (focusPercent <= 0 || focusTypes == null || focusTypes.isEmpty()) {
			return false;
		}
		// 「드래곤전 대비」에는 세트 유형이 없다. 보정할 것이 없으므로 난수도 안 쓰고 돌아간다.
		if (rarity == PerkRarity.TRIAL) {
			return false;
		}
		PerkRarity actual = null;
		for (PerkRarity candidate : fallbackOrder(rarity)) {
			if (!(silverBlocked && candidate == PerkRarity.SILVER)) {
				actual = candidate;
				break;
			}
		}
		if (actual == null) {
			return false;
		}
		// 회피 아닌 후보를 먼저 보고, 하나도 없을 때만 회피 대상에서 고른다.
		List<Perk> source = remaining.get(actual);
		List<Perk> bucket = focusCandidates(source, focusTypes);
		if (bucket.isEmpty()) {
			source = avoided.get(actual);
			bucket = focusCandidates(source, focusTypes);
		}
		if (bucket.isEmpty()) {
			return false;
		}
		if (focusPercent < 100 && random.nextInt(100) >= focusPercent) {
			return false;
		}
		Perk picked = bucket.get(pickIndex(bucket, random));
		source.remove(picked);
		drawn.add(picked.id());
		return true;
	}

	/** 통에서 모으는 유형을 하나라도 가진 증강만 모은다. */
	private static List<Perk> focusCandidates(List<Perk> bucket, Set<PerkSetType> focusTypes) {
		List<Perk> found = new ArrayList<>();
		for (Perk perk : bucket) {
			for (PerkSetType type : perk.setTypes()) {
				if (focusTypes.contains(type)) {
					found.add(perk);
					break;
				}
			}
		}
		return found;
	}

	/** 피셔-예이츠 섞기. */
	private static void shuffle(List<String> list, RandomSource random) {
		for (int i = list.size() - 1; i > 0; i--) {
			int j = random.nextInt(i + 1);
			String held = list.get(i);
			list.set(i, list.get(j));
			list.set(j, held);
		}
	}

	/** {@code count} 가 차거나 통이 빌 때까지 그 통에서 무작위로 꺼내 담는다. */
	private static void takeFrom(List<String> drawn, List<Perk> bucket, RandomSource random,
			int count) {
		while (drawn.size() < count && !bucket.isEmpty()) {
			drawn.add(bucket.remove(pickIndex(bucket, random)).id());
		}
	}

	/**
	 * 통에서 하나를 고를 자리. 가중치에 비례한다({@link Perk#weight}).
	 *
	 * <p><b>통 안의 가중치가 모두 같으면 예전과 똑같이 {@code nextInt(크기)} 한 번이다.</b> 기본값 1.0
	 * 만 있는 통(지금의 실버·골드·프리즘 전부)은 같은 시드에서 고르는 자리도, 쓰는 난수의 수도 예전과
	 * 같다 — 고정 시드 시험이 그대로 지나가는 근거가 이것이다. 가중치가 섞인 통만 {@code nextDouble}
	 * 한 번으로 누적 가중치를 짚는다.
	 */
	static int pickIndex(List<Perk> bucket, RandomSource random) {
		if (sameWeight(bucket)) {
			return random.nextInt(bucket.size());
		}
		double total = 0.0;
		for (Perk perk : bucket) {
			total += perk.weight();
		}
		double roll = random.nextDouble() * total;
		for (int index = 0; index < bucket.size(); index++) {
			roll -= bucket.get(index).weight();
			if (roll < 0.0) {
				return index;
			}
		}
		// 부동소수 끝자리로 한 칸 모자랄 때. 마지막 것을 고른다.
		return bucket.size() - 1;
	}

	/** 통 안의 가중치가 모두 같은가. 비었거나 하나뿐이면 참. */
	private static boolean sameWeight(List<Perk> bucket) {
		if (bucket.size() <= 1) {
			return true;
		}
		double first = bucket.getFirst().weight();
		for (Perk perk : bucket) {
			if (Double.compare(perk.weight(), first) != 0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * {@code buckets} 에서 {@code ids} 에 해당하는 것들을 <b>덜어내</b> 같은 모양의 통으로 돌려준다.
	 * 원본은 그만큼 줄어든다.
	 */
	private static Map<PerkRarity, List<Perk>> extract(Map<PerkRarity, List<Perk>> buckets,
			Set<String> ids) {
		Map<PerkRarity, List<Perk>> taken = new EnumMap<>(PerkRarity.class);
		for (PerkRarity rarity : PerkRarity.values()) {
			List<Perk> held = new ArrayList<>();
			if (!ids.isEmpty()) {
				Iterator<Perk> cursor = buckets.get(rarity).iterator();
				while (cursor.hasNext()) {
					Perk perk = cursor.next();
					if (ids.contains(perk.id())) {
						held.add(perk);
						cursor.remove();
					}
				}
			}
			taken.put(rarity, held);
		}
		return taken;
	}

	/**
	 * 아직 고르지 않은, 이 구간에 나올 수 있는 증강만 등급별로 모은다.
	 *
	 * <p>증강은 중첩되지 않는다. 한 번 보유하면 그 회차 동안 영원히 후보에서 빠진다.
	 * 풀에 같은 id가 두 번 들어 있어도 한 번만 담는다. {@link Perk#minLevel} 이 {@code milestone}
	 * 보다 큰 증강도, 팀이 갖추지 못한 전제조건({@link Perk#requires()})이 붙은 증강도 여기서
	 * 함께 걸러진다.
	 *
	 * <p>거르는 자리를 굳이 이 한 곳에 모은 이유는 모든 뽑기가 여기를 지나가기 때문이다. 구간
	 * 추첨·등급 폴백·다시 뽑기가 각자 거르면 셋 중 하나를 고치다가 나머지를 잊는다.
	 */
	private static Map<PerkRarity, List<Perk>> eligibleByRarity(List<Perk> pool, List<String> owned,
			int milestone, Set<Perk.Requirement> satisfied) {
		Set<String> ownedIds = idSet(owned);
		Map<PerkRarity, List<Perk>> byRarity = new EnumMap<>(PerkRarity.class);
		for (PerkRarity rarity : PerkRarity.values()) {
			byRarity.put(rarity, new ArrayList<>());
		}
		Set<String> seen = new HashSet<>();
		for (Perk perk : pool) {
			if (perk == null || perk.id() == null || perk.rarity() == null) {
				continue;
			}
			if (!seen.add(perk.id())) {
				continue;
			}
			if (ownedIds.contains(perk.id())) {
				continue;
			}
			if (perk.minLevel() > milestone) {
				continue;
			}
			// 「드래곤전 대비」는 40 이하 구간에서 통째로 뺀다. fallbackOrder 가 이미 이 통을 끌어오지
			// 않지만, 「40 이하에서는 절대」는 한 겹으로 지키지 않는다 — 다음 사람이 폴백을 고치다 열 수 있다.
			if (perk.rarity() == PerkRarity.TRIAL && milestone <= PerkMilestones.MAX) {
				continue;
			}
			// 팀이 갖추지 못한 전제조건이 붙어 있으면 뺀다. 전제조건이 없는 증강(대부분)은
			// satisfied 를 보지도 않으므로 지금까지와 결과가 같다.
			if (!perk.requirementMet(satisfied)) {
				continue;
			}
			byRarity.get(perk.rarity()).add(perk);
		}
		return byRarity;
	}

	/** null 을 빈 집합으로 받아 주는 id 집합 만들기. 보유 목록과 회피 목록이 함께 쓴다. */
	private static Set<String> idSet(List<String> perkIds) {
		Set<String> ids = new HashSet<>();
		if (perkIds == null) {
			return ids;
		}
		for (String perkId : perkIds) {
			if (perkId != null) {
				ids.add(perkId);
			}
		}
		return ids;
	}
}
