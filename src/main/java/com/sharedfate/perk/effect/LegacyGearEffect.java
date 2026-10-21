package com.sharedfate.perk.effect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkItemMatcher;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 전멸하는 순간 팀이 가진 도구·무기·방어구를 다음 회차 시작 인벤토리로 넘기는 효과.
 * 증강을 고를 때는 아무것도 빼앗지 않는다.
 *
 * <p>JSON 형식:
 * <pre>
 * { "type": "legacy_gear" }
 * </pre>
 *
 * <h2>무엇이 「장비·무기·도구」인가 — 컴포넌트가 기준이고 태그는 덤이다</h2>
 * <p>판정은 {@link #matches(ItemStack)} 한 곳에 있고, 두 가지를 <b>또는</b>으로 본다.
 *
 * <ol>
 *   <li><b>아이템 컴포넌트</b>({@link #hasGearComponent}) — 이쪽이 본 기준이다. 26.2 의
 *       {@code minecraft:tool}·{@code minecraft:weapon}·{@code minecraft:equippable}·
 *       {@code minecraft:blocks_attacks}·{@code minecraft:max_damage} 중 하나라도 달고 있으면
 *       장비로 본다. 곡괭이·도끼·삽·괭이·검·창·철퇴·활·석궁·삼지창·낚싯대·가위·부싯돌·솔·
 *       방패·겉날개·방어구가 전부 여기 걸리고, 돌·흙·음식처럼 컴포넌트가 없는 자원은 걸리지
 *       않는다.</li>
 *   <li><b>태그</b> {@code sharedfate:legacy_gear}
 *       ({@code data/sharedfate/tags/item/legacy_gear.json}) — 서버 주인이 데이터팩으로 이
 *       태그에 아이템을 <b>더 넣어</b> 대상을 넓히는 확장점이다.</li>
 * </ol>
 *
 * <h2>왜 태그 하나로 끝내지 않는가</h2>
 * <p>태그는 <b>데이터팩이 올라온 뒤에만</b> 채워진다. 아직 안 올라왔거나 리로드 중이면
 * {@link PerkItemMatcher#matches}가 태그 판정을 통째로 건너뛰므로, 그 틈에 전멸이 일어나면
 * 도구가 하나도 안 잡힌다. 게다가 태그로 대상을 적으면 26.2 에 새로 들어온 창
 * ({@code #minecraft:spears})이나 철퇴처럼 <b>목록에 안 적어 둔 것이 조용히 빠진다.</b>
 * 컴포넌트는 아이템 자체에 달려 있어 데이터팩과 무관하게 언제나 답이 같고, 새로 들어온
 * 장비도 저절로 따라온다. 단위 시험에서 태그 없이도 판정을 확인할 수 있는 것도 이쪽이다.
 *
 * <h2>이 효과는 {@link #apply}/{@link #remove}에서 아무 일도 하지 않는다</h2>
 * <p>스냅샷은 {@link com.sharedfate.perk.PerkLegacyGear} 가 전멸하는 순간에 한 번 뜬다. 증강을
 * 고를 때도, 접속·부활 때마다 도는 {@link #apply}에서도 인벤토리를 건드리지 않는다.
 *
 * <h2>지금 입고 있는 방어구 네 칸은 판정을 거치지 않는다</h2>
 * <p>착용 중인 방어구는 {@link com.sharedfate.team.SharedEquipmentStore}의 HEAD·CHEST·
 * LEGS·FEET 네 칸에 있고, 그 칸에 들어 있다는 사실 자체가 이미 방어구라는 뜻이다. 그래서
 * {@link com.sharedfate.perk.PerkLegacyGear}가 그 네 칸만은 판정 없이 통째로 가져간다.
 * 반대로 오프핸드는 횃불·흙·화살도 들어갈 수 있어 {@link #matches}를 한 번 거친다.
 */
public final class LegacyGearEffect implements PerkEffect {
	/** 대상을 <b>넓히는</b> 확장 태그. 서버 주인이 데이터팩으로 덮어쓸 수 있다. */
	public static final String LEGACY_GEAR_TAG = "sharedfate:legacy_gear";

	private final PerkItemMatcher matcher;

	private LegacyGearEffect(PerkItemMatcher matcher) {
		this.matcher = matcher;
	}

	/** JSON에서 만든다. 대상 태그는 항상 고정이라 실패하는 경우가 없다. */
	public static @Nullable PerkEffect fromJson(String perkId, int index, JsonObject json) {
		JsonObject synthetic = new JsonObject();
		JsonArray tags = new JsonArray();
		tags.add(LEGACY_GEAR_TAG);
		synthetic.add("tags", tags);
		PerkItemMatcher matcher = PerkItemMatcher.fromJson(perkId, "legacy_gear", synthetic);
		return matcher == null ? null : new LegacyGearEffect(matcher);
	}

	/**
	 * 이 스택이 다음 회차로 넘어가는 「장비·무기·도구」인가.
	 *
	 * <p>인벤토리·엔더상자·오프핸드에 흩어진 아이템을 고를 때 쓰는 유일한 판정이다. 컴포넌트가
	 * 먼저고, 거기 안 걸리면 확장 태그를 본다 — 순서에 뜻이 있다. 컴포넌트 판정은 데이터팩이
	 * 없어도 언제나 답이 같아서, 태그가 아직 안 묶인 시점에도 도구를 놓치지 않는다.
	 */
	public boolean matches(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		return hasGearComponent(stack) || matcher.matches(stack);
	}

	/**
	 * 아이템 컴포넌트만 보고 「장비·무기·도구」인지 정한다. 레지스트리도 데이터팩도 필요 없다.
	 *
	 * <p>다섯 컴포넌트 중 하나라도 빼면 실제로 빠지는 장비가 있다.
	 * <ul>
	 *   <li>{@code minecraft:tool} — 곡괭이·도끼·삽·괭이·검·가위·솔. 「도구」의 본체다.</li>
	 *   <li>{@code minecraft:weapon} — 검·도끼·삼지창·철퇴·창처럼 근접 공격력이 정의된 것.</li>
	 *   <li>{@code minecraft:equippable} — 방어구·겉날개·머리에 쓰는 것. 여벌로 인벤토리에
	 *       넣어 둔 방어구가 여기서 걸린다.</li>
	 *   <li>{@code minecraft:blocks_attacks} — 방패. 위 셋 중 어디에도 안 걸린다.</li>
	 *   <li>{@code minecraft:max_damage} — 활·석궁·낚싯대·부싯돌처럼 도구도 무기도 아닌데
	 *       내구도가 닳는 장비. 26.2 의 {@code #minecraft:enchantable/durability} 가 가리키는
	 *       무리와 사실상 같고, 자원·음식·블록에는 이 컴포넌트가 없다.</li>
	 * </ul>
	 */
	public static boolean hasGearComponent(ItemStack stack) {
		return stack.has(DataComponents.TOOL)
				|| stack.has(DataComponents.WEAPON)
				|| stack.has(DataComponents.EQUIPPABLE)
				|| stack.has(DataComponents.BLOCKS_ATTACKS)
				|| stack.has(DataComponents.MAX_DAMAGE);
	}

	/** 대상을 넓히는 확장 태그 판정기. 판정 자체는 {@link #matches}를 쓴다. */
	public PerkItemMatcher matcher() {
		return matcher;
	}
}
