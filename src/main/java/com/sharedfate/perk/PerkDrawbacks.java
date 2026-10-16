package com.sharedfate.perk;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.effect.NoDefenseDrawbacksEffect;
import com.sharedfate.team.TeamState;
import org.jetbrains.annotations.Nullable;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 「이 효과는 대가다」라는 표시를 모아 두는 표.
 *
 * <p>증강 정의의 효과 하나에 {@code "drawback": true} 를 적으면 그 효과가 여기 등록된다.
 *
 * <pre>{@code
 * { "type": "attribute", "attribute": "minecraft:knockback_resistance",
 *   "operation": "add_value", "amount": -1.0, "drawback": true }
 * }</pre>
 *
 * <p>표시가 붙는다고 해서 <b>그 자체로는 아무 일도 일어나지 않는다.</b> 이 표를 보고 효과를
 * 건너뛰는 곳은 세트 「방어 3단계」가 켜진 팀뿐이고, 그 판정은
 * {@link NoDefenseDrawbacksEffect#heldBy} 가 한다.
 *
 * <h2>표시를 붙일 수 있는 자리</h2>
 * <p>아무 데나 적어도 되는 것은 아니다. 표시를 <b>읽어서 실제로 건너뛰는 자리가 있는 곳</b>에만
 * 붙일 수 있다. 지금은 두 곳이다.
 *
 * <ul>
 *   <li>증강의 <b>최상위</b> 효과 — {@link PerkRegistry} 가 읽는다</li>
 *   <li>{@code on_team_hurt}·{@code on_critical} 의 <b>하위</b> 효과 —
 *       {@link TemporaryPerkGrants} 가 읽고 {@code grant} 가 건너뛴다</li>
 * </ul>
 *
 * <p>{@code conditional} 의 {@code when_true}/{@code when_false} 안에는 붙일 수 없다. 대신
 * <b>조건부 효과 전체</b>에 붙인다. 실수로 안에 적으면 {@code ConditionalEffect} 가 경고를
 * 남기고 무시하므로, 조용히 아무 일도 안 하는 사고는 없다.
 *
 * <h2>필드 없는 홑개체 효과에는 붙이지 말 것</h2>
 * <p>표의 열쇠는 <b>객체의 신원</b>이다. {@code no_sleep} 처럼 읽을 필드가 없어 하나를 만들어
 * 돌려쓰는 효과({@code INSTANCE})에 표시를 붙이면, 그 형을 쓰는 다른 증강까지 함께 대가로
 * 보이게 된다. 그런 일이 생기면 {@link #mark} 가 경고를 남긴다.
 */
public final class PerkDrawbacks {
	/** JSON 에 적는 표시 이름. */
	public static final String FIELD = "drawback";

	/**
	 * 대가로 표시된 효과 → 그 효과를 가진 증강의 id.
	 *
	 * <p>신원 비교라 {@code equals} 를 재정의한 효과(레코드 등)끼리도 섞이지 않는다.
	 * 정의를 다시 읽을 때 {@link PerkRegistry} 가 통째로 비운다.
	 */
	private static final Map<PerkEffect, String> OWNERS = new IdentityHashMap<>();

	private PerkDrawbacks() {
	}

	// ------------------------------------------------------------------ 등록

	/**
	 * 효과 정의에 {@code drawback} 이 적혀 있으면 대가로 등록한다.
	 *
	 * <p>효과를 만든 <b>직후</b>에 부른다. 받은 효과를 그대로 돌려주므로 만드는 줄을 감싸듯 쓸 수
	 * 있다. 표시가 없거나 효과가 {@code null} 이면 아무 일도 하지 않는다.
	 *
	 * <p>표시 값이 참·거짓이 아니면 경고를 남기고 <b>표시가 없는 것으로 본다.</b>
	 *
	 * @param perkId 이 효과를 가진 증강의 id
	 * @param json   효과 정의 객체
	 * @param effect 방금 만들어진 효과. {@code null} 이면 그대로 돌려준다
	 * @return 받은 {@code effect} 그대로
	 */
	public static @Nullable PerkEffect mark(@Nullable String perkId, @Nullable JsonObject json,
			@Nullable PerkEffect effect) {
		if (effect == null || json == null || perkId == null) {
			return effect;
		}
		JsonElement raw = json.get(FIELD);
		if (raw == null || raw.isJsonNull()) {
			return effect;
		}
		if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isBoolean()) {
			SharedFateMod.LOGGER.warn("증강 {}: {} 가 참·거짓이 아니라 표시를 무시합니다 ({})",
					perkId, FIELD, raw);
			return effect;
		}
		if (!raw.getAsBoolean()) {
			return effect;
		}
		synchronized (OWNERS) {
			String previous = OWNERS.put(effect, perkId);
			if (previous != null && !previous.equals(perkId)) {
				SharedFateMod.LOGGER.warn(
						"증강 {} 와 {} 가 같은 효과 객체를 대가로 표시했습니다. "
								+ "필드가 없어 하나를 돌려쓰는 효과에는 {} 를 붙이면 안 됩니다.",
						previous, perkId, FIELD);
			}
		}
		return effect;
	}

	/** 표시를 모두 버린다. {@link PerkRegistry} 가 정의를 다시 읽을 때 부른다. */
	public static void clear() {
		synchronized (OWNERS) {
			OWNERS.clear();
		}
	}

	// ------------------------------------------------------------------ 조회

	/** 이 효과가 대가로 표시돼 있는가. */
	public static boolean isDrawback(@Nullable PerkEffect effect) {
		if (effect == null) {
			return false;
		}
		synchronized (OWNERS) {
			return OWNERS.containsKey(effect);
		}
	}

	/** 이 대가를 가진 증강의 id. 표시가 없으면 null. */
	public static @Nullable String ownerOf(@Nullable PerkEffect effect) {
		if (effect == null) {
			return null;
		}
		synchronized (OWNERS) {
			return OWNERS.get(effect);
		}
	}

	/** 목록에 대가가 하나라도 들어 있는가. 없으면 부르는 쪽이 팀 상태를 볼 필요조차 없다. */
	public static boolean anyDrawback(@Nullable Iterable<PerkEffect> effects) {
		if (effects == null) {
			return false;
		}
		for (PerkEffect effect : effects) {
			if (isDrawback(effect)) {
				return true;
			}
		}
		return false;
	}

	/** 표시가 하나도 없는가. 시험이 상태 격리를 확인할 때 쓴다. */
	static boolean isEmpty() {
		synchronized (OWNERS) {
			return OWNERS.isEmpty();
		}
	}

	// ------------------------------------------------------------------ 면제 판정

	/**
	 * 한 번의 조회 동안 「이 팀에서 대가가 면제되는가」를 <b>최대 한 번만</b> 계산해 들고 다니는
	 * 그릇.
	 *
	 * <p>효과를 훑는 자리는 피해 계산처럼 초당 여러 번 도는 곳도 있다. 효과마다 세트 판정을 다시
	 * 하면 그만큼 순회가 늘어나므로, 대가를 <b>실제로 만났을 때</b> 한 번만 판정하고 그 뒤로는
	 * 기억해 둔 값을 쓴다. 대가를 하나도 갖고 있지 않은 팀은 세트를 아예 보지 않는다.
	 *
	 * <p>한 번의 조회 안에서만 쓰고 버린다. 서버 스레드에서만 오간다.
	 */
	public static final class Waiver {
		private final @Nullable TeamState state;
		private @Nullable Boolean active;

		private Waiver(@Nullable TeamState state) {
			this.state = state;
		}

		/**
		 * 지금 이 효과를 건너뛰어야 하는가.
		 *
		 * <p>세 가지가 모두 참일 때만 참이다. (1) 효과가 대가로 표시돼 있고, (2) 그 효과를 가진
		 * 증강이 <b>방어 유형</b>이며, (3) 이 팀에 방어 3단계가 켜져 있다. 하나라도 어긋나면
		 * 거짓이다.
		 *
		 * @param perk   이 효과를 가진 증강. 손에 없으면 {@code null} — 표에서 찾는다
		 * @param effect 지금 보고 있는 효과
		 */
		public boolean waives(@Nullable Perk perk, @Nullable PerkEffect effect) {
			String owner = ownerOf(effect);
			if (owner == null) {
				return false;
			}
			Perk holder = perk != null ? perk : PerkRegistry.byId(owner).orElse(null);
			// 세트 단계가 가진 효과는 증강 id 로 찾을 수 없다. 그때는 면제 대상이 아니다.
			if (holder == null || !holder.hasSetType(PerkSetType.DEFENSE)) {
				return false;
			}
			if (active == null) {
				active = NoDefenseDrawbacksEffect.heldBy(state);
			}
			return active;
		}
	}

	/** 이 팀을 기준으로 한 면제 판정 그릇을 만든다. 판정 자체는 아직 하지 않는다. */
	public static Waiver waiverFor(@Nullable TeamState state) {
		return new Waiver(state);
	}

	// ------------------------------------------------------------------ 효과 안에 묶인 대가

	/**
	 * 효과 <b>안에 묶여 있는</b> 대가가 이 팀에서 면제되는가.
	 *
	 * <p>{@code drawback} 표시는 효과 하나를 통째로 건너뛰는 그릇이라, 이득과 대가가 한 효과에
	 * 함께 들어 있는 경우에는 쓸 수 없다. 지금 그런 것이 둘이다.
	 *
	 * <ul>
	 *   <li>「완충」({@code spread_damage}) — 나뉘어 들어오는 동안 자연 회복이 막힌다.
	 *       {@code SpreadDamageManager.blocksNaturalRegen} 이 묻는다</li>
	 *   <li>「버티는 방패」({@code shield_fall_immunity}) — 막아 낸 낙하 피해의 열 배로 방패가 닳는다.
	 *       {@link PerkDamage#wearShieldForBlockedFall} 이 묻는다</li>
	 * </ul>
	 *
	 * <p>두 효과 모두 필드가 없거나 하나뿐이라 표시를 붙일 수 없다({@code shield_fall_immunity} 는
	 * 하나를 돌려쓰는 홑개체다). 그래서 <b>효과 형</b>으로 묻는다.
	 *
	 * <p>{@link Waiver#waives} 와 같은 세 조건을 본다. 다만 이 형을 가진 증강이 여럿이면
	 * <b>전부</b> 방어 유형이어야 참이다 — 방어가 아닌 증강이 같은 형을 들고 있으면 그 증강의 대가는
	 * 남아야 하는데, 형으로는 둘을 가를 수 없으므로 대가를 남기는 쪽으로 물러선다. 그 형을 가진
	 * 증강이 하나도 없으면 면제할 대가도 없으므로 거짓이다.
	 *
	 * <p>판정은 언제나 {@code ownedPerks} 를 다시 세어 만든다. 세트가 켜지기 전에 고른 것이든 뒤에
	 * 고른 것이든, 다시 접속하거나 서버를 다시 켠 뒤든 같은 답이 나오고, 세트가 풀리는 순간 대가가
	 * 돌아온다.
	 *
	 * @param state 대상 팀
	 * @param type  대가를 품은 효과 형
	 */
	public static boolean builtInWaived(@Nullable TeamState state, Class<? extends PerkEffect> type) {
		if (state == null || type == null || !state.perksEnabled || state.ownedPerks.isEmpty()) {
			return false;
		}
		boolean held = false;
		for (String perkId : state.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null || perk.effects().stream().noneMatch(type::isInstance)) {
				continue;
			}
			if (!perk.hasSetType(PerkSetType.DEFENSE)) {
				return false;
			}
			held = true;
		}
		return held && NoDefenseDrawbacksEffect.heldBy(state);
	}
}
