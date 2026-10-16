package com.sharedfate.perk.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.ConditionalPerkManager;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.sharedfate.perk.PerkHolderManager;
import com.sharedfate.perk.PerkStatusStack;
import com.sharedfate.perk.TimedPerkEffects;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntUnaryOperator;

/**
 * 팀원 <b>한 명</b>에게만 효과를 몰아 주는 래퍼.
 *
 * <p>지금까지의 증강은 전부 "효과가 팀 전체에 똑같이 적용된다"는 전제 위에 있었다. 이 타입은
 * 그 전제를 깬다. 무작위로 뽑힌 팀원 한 명(이하 <b>보유자</b>)이 {@code on_holder} 를 받고,
 * 나머지 팀원은 {@code on_others} 를 받는다. 두 묶음 중 하나만 언제나 붙어 있는 구조는
 * {@link ConditionalEffect} 의 {@code when_true}/{@code when_false} 와 완전히 같다.
 *
 * <pre>{@code
 * {
 *   "type": "holder",
 *   "rotate_ticks": 1200,
 *   "min_hold_ticks": 200,
 *   "pass_on_hurt": true,
 *   "fixed_to_owner": false,
 *   "on_holder": [ { "type": "damage_dealt", "multiplier": 1.5 },
 *                  { "type": "status_effect", "effect": "minecraft:haste", "amplifier": 0 } ],
 *   "on_others": [ ],
 *   "on_pass":   [ { "type": "status_effect", "effect": "minecraft:weakness",
 *                    "amplifier": 0, "duration": 5 } ]
 * }
 * }</pre>
 *
 * <h2>{@code fixed_to_owner} — 고른 사람이 계속 보유자</h2>
 * <p>참이면 <b>이 증강을 고른 사람</b>이 회차 내내 보유자다. {@code rotate_ticks} 로도
 * {@code pass_on_hurt} 로도 넘어가지 않고, 그 사람이 죽어도 자리를 잃지 않는다.
 *
 * <p>적지 않으면 거짓이다.
 *
 * <p><b>고른 사람이 접속을 끊으면 그동안 아무도 보유자가 아니다.</b> 다시 들어오면 그 사람이
 * 곧바로 보유자로 돌아온다. 무작위로 다른 사람에게 넘기지 않는다.
 * 대신 그동안 나머지 팀원은 계속 {@code on_others} 를 받는다. 즉 <b>보유자가 없는 동안 팀은
 * 디메리트만 지고 버프는 없다.</b> 자세한 집행은 {@link PerkHolderManager} 에 있다.
 *
 * <h2>{@code on_holder_amplified} — 강화된 보유자 묶음</h2>
 * <p>세트 단계 같은 바깥 조건이 「강화」를 켜면 보유자는 {@code on_holder} <b>대신</b> 이 묶음을
 * 받는다. 둘을 겹쳐 붙이지 않는다. 겹쳐 붙이면 같은 속성에 수정자가 두 번 걸리거나
 * (수정자 식별자가 다르므로 실제로 두 번 더해진다) 같은 상태이상이 서로를 덮는다.
 *
 * <pre>{@code
 * {
 *   "type": "holder",
 *   "fixed_to_owner": true,
 *   "on_holder":           [ { "type": "attribute", "attribute": "minecraft:block_break_speed",
 *                              "operation": "add_multiplied_total", "amount": 4.0 } ],
 *   "on_holder_amplified": [ { "type": "attribute", "attribute": "minecraft:block_break_speed",
 *                              "operation": "add_multiplied_total", "amount": 5.0 } ],
 *   "on_others":           [ { "type": "attribute", "attribute": "minecraft:block_break_speed",
 *                              "operation": "add_multiplied_total", "amount": -0.5 } ]
 * }
 * }</pre>
 *
 * <p><b>적지 않으면 이 필드를 모르던 때와 완전히 같이 동작한다.</b> 빈 묶음이면 「강화」가 켜져도
 * {@code on_holder} 를 그대로 쓴다. 이 필드 없이 쓰이던 기존 {@code holder} 증강들이 그대로
 * 돌아야 하기 때문이다.
 *
 * <h2>{@link HolderMode} — 지금 어느 모드인가</h2>
 * <ul>
 *   <li>{@link HolderMode#NORMAL}: 보유자는 {@code on_holder}, 나머지는 {@code on_others}.</li>
 *   <li>{@link HolderMode#AMPLIFIED}: 보유자는 {@code on_holder_amplified}, 나머지는
 *       {@code on_others}.</li>
 *   <li>{@link HolderMode#EVERYONE}: <b>팀원 전원</b>이 {@code on_holder} 를 받고
 *       {@code on_others} 는 아무에게도 붙지 않는다. 「고른 사람」이라는 구분이 사라진다.</li>
 * </ul>
 *
 * <p>강화와 전원 모드는 <b>동시에 켜지지 않는다.</b> 둘 다 켜지면 전원 모드가 이기고 강화는
 * 무시된다({@link HolderMode#resolve}). 두 단계가 서로를 맞바꾸는 세트를 전제로 한 규칙이라,
 * 어느 쪽으로 읽어도 뜻이 흔들리지 않게 한 곳에 못박아 둔다.
 *
 * <p>모드를 <b>무엇을 보고</b> 정하는지는 여기서 정하지 않는다. {@link ModeResolver} 를
 * {@code PerkHolderManager} 에 꽂아 주는 쪽이 정한다. 이 클래스는 "모드가 이것일 때 무엇을
 * 붙이는가"만 안다.
 *
 * <h2>이 클래스가 하지 않는 일</h2>
 * <p>여기는 "누가 보유자인가에 따라 무엇을 붙이는가"만 아는 자료 그릇이다. "지금 누가
 * 보유자인가"와 "언제 누구에게 넘기는가"는 {@link PerkHolderManager} 가 정한다.
 *
 * <h2>보유자 상태는 저장되지 않는다</h2>
 * <p>보유자는 {@link PerkHolderManager} 의 런타임 메모리에만 있다. "지금 누가 버프를 들고
 * 있는가"는 서버가 다시 뜨면 새로 뽑아도 되는 값이다.
 *
 * <h2>하위 효과의 순번</h2>
 * <p>속성 수정자 식별자가 {@code 증강id + 효과순번} 으로 만들어지므로
 * ({@link AttributeEffect#modifierId}) 하위 순번이 형제와 겹치면 서로를 덮어쓴다.
 * {@code on_holder}/{@code on_others} 는 {@link ConditionalEffect#childIndex} 를 그대로 쓰고
 * ({@code on_others} 는 50 부터 센다), {@code on_pass} 는 그 구간과 부딪히지 않도록
 * {@link OnKillEffect#nestedIndex} 쪽 구간을 쓴다. {@code TemporaryPerkGrants} 가 같은 방식으로
 * {@code conditional} 과 한 증강 안에 공존한다.
 *
 * <p>{@code childIndex} 구간은 이미 꽉 찼다. 한 부모가 쓸 수 있는 폭이 100 인데
 * {@code on_holder} 가 0~49, {@code on_others} 가 50~99 를 다 쓰기 때문이다. 그래서
 * {@code on_holder_amplified} 는 <b>{@code on_pass} 와 같은 {@code nestedIndex} 구간</b>을
 * 나눠 쓴다({@link #amplifiedIndex}).
 *
 * <pre>
 *   on_holder           의 i 번째 → childIndex(부모순번, i)        = (부모순번+1)*100 + i
 *   on_others           의 i 번째 → childIndex(부모순번, 50 + i)   = (부모순번+1)*100 + 50 + i
 *   on_pass             의 i 번째 → nestedIndex(부모순번, i)       = (부모순번+1)*1000 + i
 *   on_holder_amplified 의 i 번째 → nestedIndex(부모순번, 100 + i) = (부모순번+1)*1000 + 100 + i
 * </pre>
 *
 * <p>{@code nestedIndex} 한 칸의 폭은 1000 이고 {@code on_pass} 는 {@link #MAX_PASS_EFFECTS} 개까지만
 * 적을 수 있으므로 0~7 만 쓴다. 강화 묶음은 그 구간을 100 만큼 비켜난 100~149 를 쓰므로
 * {@code on_pass} 와 절대 겹치지 않고, {@code childIndex} 구간(부모가 최상위라 최대 10099)과도
 * 부모 순번이 같은 한 겹치지 않는다. 즉 <b>한 {@code holder} 안의 네 묶음은 서로를 덮어쓰지
 * 못한다.</b> 이것이 이 타입에서 지켜야 할 전부다.
 *
 * <h2>최상위에만 놓을 수 있다</h2>
 * <p>{@link PerkHolderManager} 는 증강의 최상위 효과만 훑으므로, 다른 효과의 하위로 들어간
 * {@code holder} 는 아무도 순환시켜 주지 않아 보유자가 영영 정해지지 않는다.
 * {@link PeriodicEffect} 가 같은 이유로 최상위만 허용한다.
 */
public final class HolderEffect implements PerkEffect {
	/** 순환 주기 상한. 한 시간이면 어떤 증강이라도 충분하고, 실수로 적은 큰 값을 걸러 준다. */
	public static final int MAX_ROTATE_TICKS = 72_000;

	/** 최소 유지 시간 상한. 순환 주기와 같은 기준을 쓴다. */
	public static final int MAX_MIN_HOLD_TICKS = 72_000;

	/** 한 묶음에 담을 수 있는 하위 효과 수. {@link ConditionalEffect} 의 순번 규칙과 같은 한계다. */
	private static final int MAX_BRANCH_EFFECTS = 50;

	/** {@code on_others} 쪽 하위 순번에 더하는 값. {@code when_false} 와 같은 50 이다. */
	private static final int OTHERS_BRANCH_OFFSET = 50;

	/** {@code on_pass} 에 적을 수 있는 효과 수. 잠깐 거는 것이라 많을 이유가 없다. */
	static final int MAX_PASS_EFFECTS = 8;

	/**
	 * {@code on_holder_amplified} 가 {@code nestedIndex} 구간 안에서 시작하는 자리.
	 *
	 * <p>{@code on_pass} 가 0~{@link #MAX_PASS_EFFECTS}-1 을 쓰고 한 칸의 폭이 1000 이므로,
	 * 100 부터 50 개를 쓰면 양쪽 어디와도 부딪히지 않는다. 자세한 근거는 클래스 설명의
	 * 「하위 효과의 순번」에 있다.
	 */
	private static final int AMPLIFIED_NESTED_OFFSET = 100;

	/** {@code on_pass} 하위 효과가 {@code duration} 을 적지 않았을 때의 지속시간(초). */
	public static final double DEFAULT_PASS_DURATION_SECONDS = 5.0;

	/** {@code on_pass} 지속시간 상한(초). 이보다 길면 "잠깐"이 아니라 상시나 다름없다. */
	private static final double MAX_PASS_DURATION_SECONDS = 600.0;

	public static final int TICKS_PER_SECOND = 20;

	/** {@code holder} 는 최상위에만 놓을 수 있다. 최상위 순번은 언제나 이 값보다 작다. */
	private static final int TOP_LEVEL_INDEX_LIMIT = 100;

	/**
	 * 지금 이 {@code holder} 가 어떻게 동작하는가.
	 *
	 * <p>바깥에서(세트 단계 등) 켜고 끄는 스위치다. 무엇이 이 값을 정하는지는
	 * {@link ModeResolver} 를 꽂는 쪽이 안다.
	 */
	public enum HolderMode {
		/** 이 필드를 모르던 때와 같다. 보유자는 {@code on_holder}, 나머지는 {@code on_others}. */
		NORMAL,
		/** 보유자가 {@code on_holder} 대신 {@code on_holder_amplified} 를 받는다. */
		AMPLIFIED,
		/** 팀원 전원이 {@code on_holder} 를 받고 {@code on_others} 는 아무에게도 붙지 않는다. */
		EVERYONE;

		/**
		 * 두 스위치를 모드 하나로 접는다. <b>서버 없이 시험할 수 있는 순수 함수다.</b>
		 *
		 * <p>둘이 동시에 켜지면 전원 모드가 이기고 강화는 무시된다. 두 단계가 서로를 맞바꾸는
		 * 세트를 전제로 한 규칙이라, 어느 쪽이 이기는지 한 곳에만 적어 둔다.
		 */
		public static HolderMode resolve(boolean amplified, boolean everyone) {
			if (everyone) {
				return EVERYONE;
			}
			return amplified ? AMPLIFIED : NORMAL;
		}
	}

	/**
	 * 지금 한 사람에게 붙어 있는 묶음.
	 *
	 * <p>모드가 바뀔 때 <b>이전 모드가 붙여 둔 것만 정확히 걷어내려고</b> 기억해 두는 값이다.
	 * 「전부 걷어낸다」로 하면 켜진 적도 없는 묶음의 {@code remove} 가 포션으로 얻은 상태이상까지
	 * 지운다.
	 */
	public enum Branch {
		HOLDER,
		HOLDER_AMPLIFIED,
		OTHERS
	}

	/**
	 * 팀 상태를 보고 지금 어느 모드인지 돌려주는 판정기.
	 *
	 * <p>{@code PerkHolderManager.setModeResolver} 로 꽂는다. 세트 정의와 세트 유형을 아는 쪽이
	 * 이 함수를 쓰고, {@code holder} 는 그 답만 받는다.
	 *
	 * <p>증강 id 를 함께 받는 이유가 있다. 한 팀이 세트에 속한 {@code holder} 증강과 속하지 않은
	 * {@code holder} 증강(「버프 돌리기」 등)을 동시에 가질 수 있어서, <b>세트에 속한 증강만</b>
	 * 골라 모드를 켤 수 있어야 하기 때문이다. 팀 단위로만 판정하면 무관한 증강까지 강화된다.
	 */
	@FunctionalInterface
	public interface ModeResolver {
		/**
		 * @param state  판정할 팀의 상태. 모르면 null
		 * @param perkId 이 {@code holder} 가 들어 있는 증강 id. 모르면 null
		 * @return 지금 모드. null 을 돌려주면 {@link HolderMode#NORMAL} 로 본다
		 */
		@Nullable HolderMode resolve(@Nullable TeamState state, @Nullable String perkId);
	}

	/** 이 효과가 들어 있는 증강 id. 정의에서 읽지 않고 만든 경우에는 null. */
	private final @Nullable String perkId;
	private final int rotateTicks;
	private final int minHoldTicks;
	private final boolean passOnHurt;
	private final boolean fixedToOwner;
	private final List<PerkEffect> onHolder;
	private final List<PerkEffect> onHolderAmplified;
	private final List<PerkEffect> onOthers;
	private final List<OnKillEffect.Grant> onPass;

	/**
	 * 사람마다 지금 붙여 둔 묶음.
	 *
	 * <p>{@code PerkSetEffects} 가 「마지막으로 붙여 준 단계」를 기억하는 것과 같은 이유다.
	 * 모드가 바뀔 때 이전 묶음만 정확히 걷어내야 두 묶음이 이중으로 걸리지 않는다.
	 * 팀 인원만큼만 자라므로 크기는 문제되지 않는다.
	 */
	private final Map<UUID, Branch> applied = new ConcurrentHashMap<>();

	/** {@code fixed_to_owner} 가 거짓인 생성자. */
	public HolderEffect(int rotateTicks, int minHoldTicks, boolean passOnHurt,
			List<PerkEffect> onHolder, List<PerkEffect> onOthers, List<OnKillEffect.Grant> onPass) {
		this(rotateTicks, minHoldTicks, passOnHurt, false, onHolder, onOthers, onPass);
	}

	/** {@code on_holder_amplified} 가 없는 생성자. 이 필드를 모르던 때와 같이 동작한다. */
	public HolderEffect(int rotateTicks, int minHoldTicks, boolean passOnHurt, boolean fixedToOwner,
			List<PerkEffect> onHolder, List<PerkEffect> onOthers, List<OnKillEffect.Grant> onPass) {
		this(null, rotateTicks, minHoldTicks, passOnHurt, fixedToOwner,
				onHolder, List.of(), onOthers, onPass);
	}

	public HolderEffect(@Nullable String perkId, int rotateTicks, int minHoldTicks, boolean passOnHurt,
			boolean fixedToOwner, List<PerkEffect> onHolder, List<PerkEffect> onHolderAmplified,
			List<PerkEffect> onOthers, List<OnKillEffect.Grant> onPass) {
		this.perkId = perkId;
		this.rotateTicks = Math.max(0, rotateTicks);
		this.minHoldTicks = Math.max(0, minHoldTicks);
		this.passOnHurt = passOnHurt;
		this.fixedToOwner = fixedToOwner;
		this.onHolder = List.copyOf(onHolder);
		this.onHolderAmplified = List.copyOf(onHolderAmplified);
		this.onOthers = List.copyOf(onOthers);
		this.onPass = List.copyOf(onPass);
	}

	// ------------------------------------------------------------------ 읽기

	/**
	 * JSON 에서 만든다. 정의가 잘못됐으면 경고를 남기고 null.
	 *
	 * <p>하위 효과가 하나라도 잘못됐으면 이 효과 전체를 버린다. 절반만 살아남으면 설명과 다르게
	 * 동작해 플레이어를 속이게 되기 때문이다. 버리면 증강 자체가 풀에서 빠진다.
	 */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		if (index < 0 || index >= TOP_LEVEL_INDEX_LIMIT) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: holder 효과는 최상위에만 놓을 수 있습니다 (순번 {})", perkId, index);
			return null;
		}

		int rotateTicks = PerkEffectType.readInt(json, "rotate_ticks", 0);
		if (rotateTicks < 0 || rotateTicks > MAX_ROTATE_TICKS) {
			SharedFateMod.LOGGER.warn("증강 {}: holder 의 rotate_ticks 가 범위를 벗어났습니다 ({})",
					perkId, rotateTicks);
			return null;
		}
		int minHoldTicks = PerkEffectType.readInt(json, "min_hold_ticks", 0);
		if (minHoldTicks < 0 || minHoldTicks > MAX_MIN_HOLD_TICKS) {
			SharedFateMod.LOGGER.warn("증강 {}: holder 의 min_hold_ticks 가 범위를 벗어났습니다 ({})",
					perkId, minHoldTicks);
			return null;
		}

		Boolean passOnHurt = readBoolean(perkId, json, "pass_on_hurt");
		if (passOnHurt == null) {
			return null;
		}
		// 적지 않으면 거짓이다.
		Boolean fixedToOwner = readBoolean(perkId, json, "fixed_to_owner");
		if (fixedToOwner == null) {
			return null;
		}

		List<PerkEffect> onHolder = parseBranch(perkId, json, "on_holder",
				ordinal -> ConditionalEffect.childIndex(index, ordinal));
		List<PerkEffect> onOthers = parseBranch(perkId, json, "on_others",
				ordinal -> ConditionalEffect.childIndex(index, OTHERS_BRANCH_OFFSET + ordinal));
		// 강화 묶음은 childIndex 구간이 이미 꽉 차 있어 nestedIndex 쪽을 나눠 쓴다.
		List<PerkEffect> onHolderAmplified = parseBranch(perkId, json, "on_holder_amplified",
				ordinal -> amplifiedIndex(index, ordinal));
		if (onHolder == null || onOthers == null || onHolderAmplified == null) {
			return null;
		}
		if (onHolder.isEmpty() && onOthers.isEmpty() && onHolderAmplified.isEmpty()) {
			SharedFateMod.LOGGER.warn(
					"증강 {}: holder 에 on_holder 도 on_others 도 없습니다", perkId);
			return null;
		}

		List<OnKillEffect.Grant> onPass = parsePass(perkId, index, json);
		if (onPass == null) {
			return null;
		}
		if (!onPass.isEmpty() && (fixedToOwner || (!passOnHurt && rotateTicks == 0))) {
			// 넘어갈 일이 없는데 넘길 때 걸 효과만 적어 둔 정의다. 조용히 죽어 있는 것보다
			// 여기서 걸러 내는 편이 낫다. fixed_to_owner 가 켜져 있으면 rotate_ticks 나
			// pass_on_hurt 를 적어 두었더라도 보유자는 절대 넘어가지 않으므로 같은 경우다.
			SharedFateMod.LOGGER.warn(
					"증강 {}: holder 에 on_pass 를 적었지만 보유자가 넘어갈 길이 없습니다 "
							+ "(fixed_to_owner 참이거나, rotate_ticks 0 이고 pass_on_hurt 거짓)", perkId);
			return null;
		}
		if (fixedToOwner && (rotateTicks > 0 || passOnHurt)) {
			// 버리지는 않는다. 「고정」이 이기고 나머지는 무시된다는 것만 남겨 둔다. 정의를
			// 버리면 증강이 통째로 풀에서 빠지는데, 이 조합은 뜻이 분명해서 그럴 일이 아니다.
			SharedFateMod.LOGGER.warn(
					"증강 {}: holder 가 fixed_to_owner 라 rotate_ticks·pass_on_hurt 는 무시됩니다", perkId);
		}

		return new HolderEffect(perkId, rotateTicks, minHoldTicks, passOnHurt, fixedToOwner,
				onHolder, onHolderAmplified, onOthers, onPass);
	}

	/**
	 * {@code on_holder_amplified} 하위 효과의 순번.
	 *
	 * <p>{@code on_pass} 와 같은 {@link OnKillEffect#nestedIndex} 구간을 쓰되
	 * {@link #AMPLIFIED_NESTED_OFFSET} 만큼 비켜난 자리를 쓴다. 근거는 클래스 설명의
	 * 「하위 효과의 순번」에 있다.
	 */
	public static int amplifiedIndex(int parentIndex, int ordinal) {
		return OnKillEffect.nestedIndex(parentIndex, AMPLIFIED_NESTED_OFFSET + Math.max(0, ordinal));
	}

	/**
	 * 한 묶음을 읽는다. 필드가 없으면 빈 목록, 하나라도 잘못됐으면 null.
	 *
	 * <p>{@link ConditionalEffect} 가 {@code when_true} 를 읽는 규칙과 같다. 다만 {@code holder} 안에 또
	 * {@code holder} 를 넣는 것만은 막는다. 안쪽 보유자는 아무도 뽑아 주지 않는다.
	 *
	 * @param indexOf 묶음 안 순번을 받아 하위 효과의 순번을 만드는 함수. 묶음마다 다르다
	 */
	private static @Nullable List<PerkEffect> parseBranch(String perkId, JsonObject json,
			String key, IntUnaryOperator indexOf) {
		JsonElement element = json.get(key);
		if (element == null || element.isJsonNull()) {
			return List.of();
		}
		if (!element.isJsonArray()) {
			SharedFateMod.LOGGER.warn("증강 {}: holder 의 {} 가 배열이 아닙니다", perkId, key);
			return null;
		}

		JsonArray array = element.getAsJsonArray();
		if (array.size() > MAX_BRANCH_EFFECTS) {
			SharedFateMod.LOGGER.warn("증강 {}: holder 의 {} 에 효과가 너무 많습니다 ({} > {})",
					perkId, key, array.size(), MAX_BRANCH_EFFECTS);
			return null;
		}

		List<PerkEffect> effects = new ArrayList<>(array.size());
		for (int i = 0; i < array.size(); i++) {
			JsonElement raw = array.get(i);
			if (raw == null || !raw.isJsonObject()) {
				SharedFateMod.LOGGER.warn("증강 {}: holder 의 {} 중 {}번째가 객체가 아닙니다",
						perkId, key, i);
				return null;
			}
			JsonObject childJson = raw.getAsJsonObject();
			String typeId = PerkEffectType.readString(childJson, "type");
			PerkEffectType type = PerkEffectType.fromId(typeId);
			if (type == null) {
				SharedFateMod.LOGGER.warn("증강 {}: holder 의 {} 에 알 수 없는 효과 type 입니다 ({})",
						perkId, key, typeId);
				return null;
			}
			if (type == PerkEffectType.HOLDER) {
				SharedFateMod.LOGGER.warn("증강 {}: holder 안에 holder 를 넣을 수 없습니다", perkId);
				return null;
			}
			PerkEffect child = type.create(perkId, indexOf.applyAsInt(i), childJson);
			if (child == null) {
				return null;
			}
			effects.add(child);
		}
		return effects;
	}

	/**
	 * {@code on_pass} 를 읽는다. 필드가 없으면 빈 목록, 하나라도 잘못됐으면 null.
	 *
	 * <p>{@link OnKillEffect} 의 {@code effects} 와 같은 형태다. 각 항목은 보통의 효과 정의에
	 * {@code duration}(초)을 덧붙인 것이고, 적지 않으면 {@link #DEFAULT_PASS_DURATION_SECONDS} 초다.
	 */
	private static @Nullable List<OnKillEffect.Grant> parsePass(String perkId, int index,
			JsonObject json) {
		JsonElement element = json.get("on_pass");
		if (element == null || element.isJsonNull()) {
			return List.of();
		}
		if (!element.isJsonArray()) {
			SharedFateMod.LOGGER.warn("증강 {}: holder 의 on_pass 가 배열이 아닙니다", perkId);
			return null;
		}

		JsonArray array = element.getAsJsonArray();
		if (array.size() > MAX_PASS_EFFECTS) {
			SharedFateMod.LOGGER.warn("증강 {}: holder 의 on_pass 에 효과가 너무 많습니다 ({} > {})",
					perkId, array.size(), MAX_PASS_EFFECTS);
			return null;
		}

		List<OnKillEffect.Grant> grants = new ArrayList<>(array.size());
		for (int child = 0; child < array.size(); child++) {
			JsonElement raw = array.get(child);
			if (raw == null || !raw.isJsonObject()) {
				SharedFateMod.LOGGER.warn("증강 {}: holder 의 on_pass 중 {}번째가 객체가 아닙니다",
						perkId, child);
				return null;
			}
			JsonObject childJson = raw.getAsJsonObject();
			String typeId = PerkEffectType.readString(childJson, "type");
			PerkEffectType type = PerkEffectType.fromId(typeId);
			if (type == null) {
				SharedFateMod.LOGGER.warn("증강 {}: holder 의 on_pass 에 알 수 없는 효과 type 입니다 ({})",
						perkId, typeId);
				return null;
			}
			if (type == PerkEffectType.HOLDER) {
				SharedFateMod.LOGGER.warn("증강 {}: holder 안에 holder 를 넣을 수 없습니다", perkId);
				return null;
			}
			PerkEffect effect = type.create(
					perkId, OnKillEffect.nestedIndex(index, child), childJson);
			if (effect == null) {
				return null;
			}
			Integer duration = readPassDurationTicks(perkId, childJson);
			if (duration == null) {
				return null;
			}
			grants.add(new OnKillEffect.Grant(effect, duration));
		}
		return grants;
	}

	/** {@code on_pass} 하위 효과의 {@code duration}(초)을 틱으로 바꾼다. 범위를 벗어나면 null. */
	private static @Nullable Integer readPassDurationTicks(String perkId, JsonObject json) {
		Double seconds = PerkEffectType.readDouble(json, "duration");
		if (seconds == null) {
			if (json.has("duration") && !json.get("duration").isJsonNull()) {
				SharedFateMod.LOGGER.warn("증강 {}: holder 의 on_pass 하위 효과 duration 이 숫자가 아닙니다",
						perkId);
				return null;
			}
			seconds = DEFAULT_PASS_DURATION_SECONDS;
		}
		if (seconds <= 0.0 || seconds > MAX_PASS_DURATION_SECONDS) {
			SharedFateMod.LOGGER.warn("증강 {}: holder 의 on_pass 하위 효과 duration 이 범위를 벗어났습니다 ({})",
					perkId, seconds);
			return null;
		}
		return Math.max(1, (int) Math.round(seconds * TICKS_PER_SECOND));
	}

	/** 참·거짓 필드. 없으면 거짓, 적었는데 참·거짓이 아니면 null. */
	private static @Nullable Boolean readBoolean(String perkId, JsonObject json, String key) {
		JsonElement element = json.get(key);
		if (element == null || element.isJsonNull()) {
			return Boolean.FALSE;
		}
		if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
			SharedFateMod.LOGGER.warn("증강 {}: holder 의 {} 가 참·거짓이 아닙니다 ({})", perkId, key, element);
			return null;
		}
		return element.getAsBoolean();
	}

	// ------------------------------------------------------------------ 적용

	/**
	 * 지금 있어야 할 모습으로 맞춘다.
	 *
	 * <p>접속·부활 직후처럼 수정자가 통째로 날아간 자리에서 불리므로, 기억해 둔 것과 관계없이
	 * {@link PerkHolderManager} 에 지금 보유자가 누구인지 다시 물어 맞춘다. 아직 보유자가 정해지지
	 * 않았으면 {@code on_others} 를 붙인다. 곧 {@link PerkHolderManager#tick} 이 보유자를 뽑아
	 * 그 사람만 {@code on_holder} 로 갈아 끼운다.
	 */
	@Override
	public void apply(ServerPlayer player) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUUID();
		// 모드는 기억해 둔 값이 아니라 지금 값을 다시 묻는다. 접속하는 순간 이미 세트 단계가
		// 켜져 있을 수 있고, 그때 예전 모드로 붙이면 다음 점검까지 반 초 동안 틀린 값이 걸린다.
		applyAs(player, PerkHolderManager.isHolder(this, playerId),
				PerkHolderManager.modeFor(playerId, perkId));
	}

	/** 지금 모드를 다시 물어 이 사람을 보유자/비보유자로 맞춘다. */
	public void applyAs(@Nullable ServerPlayer player, boolean holding) {
		if (player == null) {
			return;
		}
		applyAs(player, holding, PerkHolderManager.modeFor(player.getUUID(), perkId));
	}

	/**
	 * 이 사람을 주어진 모드의 제 모습으로 맞춘다.
	 *
	 * <p><b>지는 쪽을 먼저 떼고 이기는 쪽을 붙인다.</b> 두 묶음이 같은 속성이나 같은 상태이상을
	 * 건드려도 이 순서면 안전하다. 이것을 뒤집으면 갓 붙인 수정자를 곧바로 떼어 내는 일이 생긴다.
	 * {@link ConditionalEffect} 의 {@code switchTo} 와 같은 규칙이다.
	 *
	 * <p><b>무엇을 떼는가가 이 메서드의 전부다.</b> 모드가 바뀌는 순간 이전 모드가 붙여 둔 것이
	 * 남아 있으면 속성 수정자가 그대로 두 번 더해진다(식별자가 서로 달라 덮이지도 않는다).
	 * 그래서 사람마다 마지막에 붙여 준 묶음을 {@link #applied} 에 적어 두고 <b>그것만</b> 떼어
	 * 낸다. 「이기는 쪽 말고 전부 뗀다」로 하면 켜진 적도 없는 묶음의 {@code remove} 가 포션으로
	 * 얻은 상태이상까지 지운다.
	 *
	 * <p>기억이 없을 때만은 어쩔 수 없이 나머지를 전부 뗀다. 그대로 두면 이전 회차나 서버
	 * 재시작 전에 붙은 수정자가 영영 남기 때문이다. 이 필드를 쓰지 않는 기존 증강에서는 강화
	 * 묶음이 비어 있어 예전과 똑같이 「반대쪽 하나만 뗀다」가 된다.
	 */
	public void applyAs(@Nullable ServerPlayer player, boolean holding, @Nullable HolderMode mode) {
		if (player == null) {
			return;
		}
		UUID playerId = player.getUUID();
		Branch wanted = branchFor(holding, mode);
		Branch previous = applied.get(playerId);
		if (previous == null) {
			for (Branch branch : Branch.values()) {
				if (branch != wanted) {
					removeAll(branchEffects(branch), player);
				}
			}
		} else if (previous != wanted) {
			removeAll(branchEffects(previous), player);
		}
		applyAll(branchEffects(wanted), player);
		applied.put(playerId, wanted);
	}

	/**
	 * 이 사람이 지금 받아야 할 묶음.
	 *
	 * <p>{@code on_holder_amplified} 가 비어 있으면 {@link HolderMode#AMPLIFIED} 여도
	 * {@code on_holder} 를 그대로 쓴다. 이 필드를 모르던 정의가 예전과 똑같이 동작해야 하기
	 * 때문이다.
	 */
	public Branch branchFor(boolean holding, @Nullable HolderMode mode) {
		return selectBranch(holding, mode, !onHolderAmplified.isEmpty());
	}

	/**
	 * 모드와 보유 여부로 어느 묶음을 붙일지 고른다.
	 *
	 * <p><b>서버 없이 시험할 수 있는 순수 함수다.</b> 이 표가 이 확장의 전부다.
	 *
	 * <pre>
	 *   EVERYONE  → 보유자든 아니든 on_holder     (on_others 는 아무에게도 붙지 않는다)
	 *   AMPLIFIED → 보유자는 on_holder_amplified, 나머지는 on_others
	 *   NORMAL    → 보유자는 on_holder,           나머지는 on_others
	 * </pre>
	 *
	 * @param hasAmplified {@code on_holder_amplified} 가 실제로 적혀 있는가
	 */
	public static Branch selectBranch(boolean holding, @Nullable HolderMode mode,
			boolean hasAmplified) {
		HolderMode resolved = mode == null ? HolderMode.NORMAL : mode;
		if (resolved == HolderMode.EVERYONE) {
			// 「고른 사람」이라는 구분이 사라진다. 전원이 보유자와 같은 효과를 받는다.
			return Branch.HOLDER;
		}
		if (!holding) {
			return Branch.OTHERS;
		}
		return resolved == HolderMode.AMPLIFIED && hasAmplified
				? Branch.HOLDER_AMPLIFIED
				: Branch.HOLDER;
	}

	/** 묶음 이름에 해당하는 하위 효과들. */
	private List<PerkEffect> branchEffects(Branch branch) {
		return switch (branch) {
			case HOLDER -> onHolder;
			case HOLDER_AMPLIFIED -> onHolderAmplified;
			case OTHERS -> onOthers;
		};
	}

	/** 이 사람에게 지금 붙여 둔 묶음. 아직 붙인 적이 없으면 null. */
	public @Nullable Branch appliedBranch(@Nullable UUID playerId) {
		return playerId == null ? null : applied.get(playerId);
	}

	/**
	 * 기억해 둔 묶음을 모두 버린다.
	 *
	 * <p>서버가 멈출 때 {@link PerkHolderManager#reset} 이 부른다. 남겨 두면 다음 회차에서
	 * 「이미 그 묶음이 붙어 있다」고 잘못 믿어 걷어내기를 건너뛴다.
	 */
	public void forgetAll() {
		applied.clear();
	}

	/**
	 * 세 묶음을 모두 걷어내고 {@code on_pass} 로 걸어 둔 것도 취소한다.
	 *
	 * <p>어느 쪽이 붙어 있었는지 몰라도 안전하다. 하나라도 남기면 속성 수정자가 영구히 붙어
	 * 팀이 망가진다.
	 */
	@Override
	public void remove(ServerPlayer player) {
		if (player == null) {
			return;
		}
		removeAll(onHolder, player);
		removeAll(onHolderAmplified, player);
		removeAll(onOthers, player);
		for (OnKillEffect.Grant grant : onPass) {
			revokePass(player, grant);
		}
		applied.remove(player.getUUID());
	}

	/**
	 * 버프가 넘어가는 순간 <b>직전 보유자에게만</b> 잠깐 거는 효과들을 얹는다.
	 *
	 * <p>{@link #applyAs} 로 묶음을 갈아 끼운 <b>뒤에</b> 불러야 한다. 먼저 얹으면 갈아 끼우는
	 * 과정의 {@code remove} 가 방금 얹은 것을 도로 걷어낼 수 있다.
	 */
	public void grantPassEffects(@Nullable ServerPlayer previous) {
		if (previous == null || onPass.isEmpty()) {
			return;
		}
		for (OnKillEffect.Grant grant : onPass) {
			try {
				grantPass(previous, grant);
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("holder 의 on_pass 효과를 얹지 못했습니다", error);
			}
		}
	}

	private static void grantPass(ServerPlayer player, OnKillEffect.Grant grant) {
		if (grant.effect() instanceof StatusEffectPerk status) {
			Holder<MobEffect> resolved = status.resolvedEffect();
			if (resolved == null) {
				return;
			}
			// 무한이 아니라 정해진 시간만 걸어야 PerkStatusEffects 가 증강분으로 오해하지 않는다.
			player.addEffect(new MobEffectInstance(
					resolved, grant.durationTicks(), status.amplifier(), false, false, true));
			return;
		}
		// 속성처럼 스스로 만료되지 않는 효과다. 걷어낼 시점을 예약해 둔다.
		TimedPerkEffects.grant(player, grant.effect(), grant.durationTicks());
	}

	private static void revokePass(ServerPlayer player, OnKillEffect.Grant grant) {
		if (grant.effect() instanceof StatusEffectPerk status) {
			// 종류째 지우면 다른 증강의 같은 종류 상시분까지 사라진다. 잠깐 건 층만 거둔다.
			PerkStatusStack.revokeTimed(PerkStatusStack.of(player), status.resolvedEffect(),
					status.amplifier());
			return;
		}
		TimedPerkEffects.cancel(player, grant.effect());
	}

	private static void applyAll(List<PerkEffect> effects, ServerPlayer player) {
		for (PerkEffect effect : effects) {
			try {
				effect.apply(player);
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("holder 증강의 하위 효과를 적용하지 못했습니다", error);
			}
		}
	}

	private static void removeAll(List<PerkEffect> effects, ServerPlayer player) {
		for (PerkEffect effect : effects) {
			try {
				effect.remove(player);
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("holder 증강의 하위 효과를 걷어내지 못했습니다", error);
			}
		}
	}

	// ------------------------------------------------------------------ 피해 배율

	/**
	 * 지금 이 배율을 묻는 사람이 보유자인지 보고 그 묶음의 배율만 돌려준다.
	 *
	 * <p>이 메서드에는 플레이어 인자가 없다. 그래서 누구를 위한 조회인지는
	 * {@link ConditionalPerkManager#multiplierContext()} 로 알아낸다. 배율을 모으는 자리
	 * ({@code PerkManager.multiplier})가 조회를 시작하면서 대상 플레이어를 적어 두기 때문에,
	 * 그 UUID 가 보유자인지 {@link PerkHolderManager} 에 물으면 된다.
	 *
	 * <p>대상을 알 수 없으면 <b>1.0 을 돌려준다.</b> {@link ConditionalEffect} 는 이때 기억해 둔
	 * 판정으로 물러서지만 여기서는 그럴 수 없다. 조건부 효과는 팀원끼리 판정이 언제나 같아서
	 * 짐작이 통하지만, 보유자는 정의상 팀에 한 명뿐이라 짐작하면 팀 전원이 보유자 배율을 받아
	 * 증강이 통째로 망가진다. 모르면 관여하지 않는 편이 안전하다.
	 */
	@Override
	public double damageDealtMultiplier() {
		UUID target = ConditionalPerkManager.multiplierContext();
		return target == null
				? 1.0
				: damageDealtMultiplier(PerkHolderManager.isHolder(this, target), modeFor(target));
	}

	@Override
	public double damageTakenMultiplier() {
		UUID target = ConditionalPerkManager.multiplierContext();
		return target == null
				? 1.0
				: damageTakenMultiplier(PerkHolderManager.isHolder(this, target), modeFor(target));
	}

	/** 보유자 여부를 직접 주고 구하는 주는 피해 배율. 모드는 {@link HolderMode#NORMAL} 로 본다. */
	public double damageDealtMultiplier(boolean holding) {
		return damageDealtMultiplier(holding, HolderMode.NORMAL);
	}

	/** 보유자 여부를 직접 주고 구하는 받는 피해 배율. 모드는 {@link HolderMode#NORMAL} 로 본다. */
	public double damageTakenMultiplier(boolean holding) {
		return damageTakenMultiplier(holding, HolderMode.NORMAL);
	}

	/** 보유자 여부와 모드를 직접 주고 구하는 주는 피해 배율. */
	public double damageDealtMultiplier(boolean holding, @Nullable HolderMode mode) {
		return branchMultiplier(branchEffects(branchFor(holding, mode)), true);
	}

	/** 보유자 여부와 모드를 직접 주고 구하는 받는 피해 배율. */
	public double damageTakenMultiplier(boolean holding, @Nullable HolderMode mode) {
		return branchMultiplier(branchEffects(branchFor(holding, mode)), false);
	}

	/** 이 사람에게 지금 걸려 있는 모드. 알 수 없으면 {@link HolderMode#NORMAL}. */
	private HolderMode modeFor(UUID target) {
		return PerkHolderManager.modeFor(target, perkId);
	}

	private static double branchMultiplier(List<PerkEffect> effects, boolean dealt) {
		double total = 1.0;
		for (PerkEffect effect : effects) {
			try {
				total *= dealt
						? effect.damageDealtMultiplier()
						: effect.damageTakenMultiplier();
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("holder 증강의 하위 피해 배율을 구하지 못했습니다", error);
				return 1.0;
			}
		}
		return Double.isFinite(total) && total > 0.0 ? total : 1.0;
	}

	// ------------------------------------------------------------------ 조회

	/** 0 이면 시간으로는 바뀌지 않는다. 접속 종료·사망 같은 사건으로만 넘어간다. */
	public int rotateTicks() {
		return rotateTicks;
	}

	/** 이 시간 안에는 {@code pass_on_hurt} 로 넘어가지 않는다. */
	public int minHoldTicks() {
		return minHoldTicks;
	}

	/** 보유자가 피해를 받으면 넘기는가. {@link #fixedToOwner()} 가 참이면 무시된다. */
	public boolean passOnHurt() {
		return passOnHurt;
	}

	/**
	 * 이 증강을 고른 사람이 계속 보유자인가.
	 *
	 * <p>참이면 순환도 넘김도 일어나지 않는다. "고른 사람"이 누구인지는
	 * {@code TeamState.perkOwners} 에 증강 id 별로 적혀 있고, 그것을 읽어 실제 보유자를 정하는
	 * 것은 {@link PerkHolderManager} 다.
	 */
	public boolean fixedToOwner() {
		return fixedToOwner;
	}

	/** 이 효과가 들어 있는 증강 id. 정의에서 읽지 않고 만든 경우에는 null. */
	public @Nullable String perkId() {
		return perkId;
	}

	public List<PerkEffect> onHolder() {
		return onHolder;
	}

	/**
	 * 강화가 켜졌을 때 보유자가 {@code on_holder} <b>대신</b> 받는 묶음.
	 *
	 * <p>비어 있으면 강화가 켜져도 {@code on_holder} 를 그대로 쓴다.
	 */
	public List<PerkEffect> onHolderAmplified() {
		return onHolderAmplified;
	}

	public List<PerkEffect> onOthers() {
		return onOthers;
	}

	public List<OnKillEffect.Grant> onPass() {
		return onPass;
	}

	/**
	 * 상시로 붙었다 떼는 세 묶음의 하위 효과 전부.
	 *
	 * <p>지금 누가 보유자든, 지금 어느 모드든 셋 다 이 증강이 거는 효과라, 하위 효과까지 훑어야
	 * 하는 곳({@code PerkStatusEffects} 처럼)에서는 전부 봐야 한다. <b>{@code on_holder_amplified}
	 * 를 빠뜨리면</b> 강화 묶음의 상태이상이 증강분으로 인식되지 않아 포션 효과처럼 팀에 공유된다.
	 *
	 * <p>{@code on_pass} 는 넣지 않는다. 그쪽은 언제나 유한 지속으로 걸리므로
	 * {@code PerkStatusEffects} 의 "무한 지속이어야 증강분" 판정에 애초에 걸리지 않는다.
	 */
	public List<PerkEffect> children() {
		if (onHolderAmplified.isEmpty()) {
			if (onOthers.isEmpty()) {
				return onHolder;
			}
			if (onHolder.isEmpty()) {
				return onOthers;
			}
		}
		List<PerkEffect> all = new ArrayList<>(
				onHolder.size() + onHolderAmplified.size() + onOthers.size());
		all.addAll(onHolder);
		all.addAll(onHolderAmplified);
		all.addAll(onOthers);
		return List.copyOf(all);
	}
}
