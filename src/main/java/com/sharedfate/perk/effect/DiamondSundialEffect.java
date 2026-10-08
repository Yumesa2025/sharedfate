package com.sharedfate.perk.effect;

import com.sharedfate.SharedFateMod;
import com.sharedfate.perk.PerkEffect;
import com.sharedfate.perk.PerkEffectType;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/**
 * 「엑스레이」를 하나 지급하고, 그것을 우클릭하면 정한 시간 동안 주변 다이아몬드 광석 자리에
 * 파티클이 계속 뜨고 가장 가까운 한 자리의 좌표가 액션바에 이어서 적힌다.
 *
 * <p>JSON 형식:
 * <pre>
 * { "type": "diamond_sundial", "radius": 20, "duration_seconds": 10,
 *   "cooldown_seconds": 90, "max_results": 16 }
 * </pre>
 *
 * <p>네 값 모두 생략할 수 있고, 생략하면 {@link #DEFAULT_RADIUS}·{@link #DEFAULT_DURATION_SECONDS}·
 * {@link #DEFAULT_COOLDOWN_SECONDS}·{@link #DEFAULT_MAX_RESULTS} 다. 범위를 벗어난 값은 정의를
 * 버리지 않고 범위 안으로 자른다.
 *
 * <h2>타입 이름과 표식은 「해시계」 때 그대로다</h2>
 * <p>증강의 이름만 「해시계」에서 「엑스레이」로 바뀌었다. 효과 타입 이름
 * ({@code diamond_sundial}) · 표식 값 · 쿨타임 묶음 · 이 클래스와 메서드의 이름은 모두 예전
 * 값이다. 그 이름들은 <b>이미 저장에 적혀 나가 있는 값</b>이라, 바꾸면 그 증강을 가진 팀의
 * 저장이 깨지고 이미 나가 있는 시계가 아무 반응도 하지 않게 된다. 사람에게 보이는 이름은
 * {@link #DISPLAY_NAME} 하나뿐이다.
 *
 * <h2>새 아이템을 등록하지 않는다</h2>
 * <p>아이템을 새로 등록하면 텍스처와 등록이 클라이언트에도 필요해져 「모드를 안 깐 사람도
 * 들어올 수 있다」는 전제가 깨진다. 그래서 엑스레이는 <b>바닐라 {@code minecraft:clock}</b> 에
 * 이름과 표식을 붙인 것이다.
 *
 * <ul>
 *   <li><b>표식</b> — {@code minecraft:custom_data} 에 {@code {sharedfate_item: "diamond_sundial"}}
 *       를 넣는다. 이 컴포넌트는 생존 모드에서 플레이어가 붙일 방법이 없으므로, 시계를 모루에서
 *       「엑스레이」로 이름만 바꿔도 가짜가 만들어지지 않는다. 이름으로 판정하지 않는 이유가 이것이다.</li>
 *   <li><b>쿨타임 묶음</b> — {@code minecraft:use_cooldown} 에 {@link #COOLDOWN_GROUP} 을 적는다.
 *       26.2 의 {@code ItemCooldowns.getCooldownGroup} 은 이 컴포넌트가 있으면 아이템 id 대신
 *       그 값을 쓰므로, 엑스레이에 건 쿨타임이 <b>평범한 시계까지 잠그지 않는다.</b> 이 컴포넌트는
 *       값만 정해 둘 뿐 저절로 쿨타임을 걸지는 않는다 — 거는 것은
 *       {@link com.sharedfate.perk.PerkDiamondSundial} 이 {@code getCooldowns().addCooldown} 으로
 *       한다. 그래야 바닐라 쿨타임 표시(아이템 위 회색 게이지)가 그대로 뜬다. <b>거는 시점은
 *       쓰는 순간이 아니라 지속이 끝나는 순간이다</b> — 까닭은 그쪽 클래스 문서에 있다.</li>
 * </ul>
 *
 * <h2>왜 그대로 시계인가</h2>
 * <p>이름이 「엑스레이」가 되었어도 아이템은 시계로 둔다. 두 가지 까닭이다. 하나는 <b>이미 나가
 * 있는 시계</b>가 그대로 쓰여야 하기 때문이고(아이템을 바꾸면 가진 사람의 것은 옛 시계로 남는다),
 * 다른 하나는 <b>시계가 26.2 에서 허공 우클릭에 아무 동작이 없는 몇 안 되는 아이템</b>이기
 * 때문이다. 망원경처럼 그럴싸한 것은 바닐라 사용 동작이 있어 우클릭을 가로채면 그 동작을 뺏는다.
 *
 * <h2>지급 시점</h2>
 * <p>{@link ItemGrantEffect} 와 똑같이 <b>{@link #apply}/{@link #remove} 에서는 아무 일도 하지
 * 않는다.</b> 두 메서드는 접속·부활·효과 갱신 때마다 다시 불리므로 거기서 주면 접속할 때마다
 * 시계가 늘어난다. 지급은 {@code PerkManager.commit} → {@code PerkGrantChain.run} 이 지나는
 * 한 곳뿐이고, 실제 전달은 {@link com.sharedfate.perk.PerkDiamondSundial#grantOnChoice} 가 맡는다.
 *
 * <h2>왜 {@code minecraft:end_rod} 파티클인가</h2>
 * <p>다이아몬드를 찾는 곳은 빛이 없는 굴속이다. 26.2 의 파티클은 대부분 자기 자리의 블록 밝기를
 * 그대로 받아 어두운 곳에서는 보이지 않는데, {@code EndRodParticle} 은
 * {@code SimpleAnimatedParticle} 을 상속하고 그 클래스의 {@code getLightCoords} 가 언제나
 * {@code 15728880}(최대 밝기)을 돌려준다. 즉 <b>칠흑 같은 굴에서도 그대로 하얗게 보인다.</b>
 * 반대로 {@code minecraft:happy_villager}({@code SuspendedTownParticle})는 그 메서드를
 * 재정의하지 않아 어두운 곳에서 거의 보이지 않는다.
 *
 * <h2>이 클래스가 하지 않는 일</h2>
 * <p>여기는 값과 아이템 모양만 들고 있는 자료 그릇이다. 우클릭을 잡고, 청크를 훑고, 파티클과
 * 액션바를 보내고, 쿨타임을 거는 일은 전부 {@link com.sharedfate.perk.PerkDiamondSundial} 이
 * 한다.
 */
public final class DiamondSundialEffect implements PerkEffect {
	/** 기본 탐지 반경(칸). 30칸은 훑을 블록이 두 배 넘게 늘어 잠깐 멈출 수 있어 20으로 잡았다. */
	public static final int DEFAULT_RADIUS = 20;
	/** 반경 하한. 이보다 좁으면 자기 발밑만 보여 뜻이 없다. */
	public static final int MIN_RADIUS = 4;
	/** 반경 상한. 여기서부터는 한 번 쓸 때 훑는 청크가 5×5 를 넘어 체감될 만큼 걸린다. */
	public static final int MAX_RADIUS = 48;

	/**
	 * 기본 지속 시간(초).
	 *
	 * <p>한 번 우클릭하면 이만큼 광석이 계속 보인다. 10초는 「비행 부적」의 기본 비행 시간과 같게
	 * 잡았다 — 같은 지속형이므로 사람이 두 증강의 감각을 따로 익히지 않아도 된다.
	 *
	 * <p>값을 조절할 때는 <b>이 상수와 {@code sharedfate-perks-default.json} 의 값이 어긋나지
	 * 않게</b> 같이 봐야 한다.
	 */
	public static final int DEFAULT_DURATION_SECONDS = 10;
	/** 지속 하한(초). 1초보다 짧으면 파티클이 한 번 뜨고 끝나 지속형이 아니게 된다. */
	public static final int MIN_DURATION_SECONDS = 1;
	/**
	 * 지속 상한(초).
	 *
	 * <p>1분이다. 지속이 길어질수록 훑기가 그만큼 여러 번 돌고
	 * ({@code PerkDiamondSundial.REFRESH_INTERVAL_TICKS} 마다 한 번), 쿨타임 하한(1초)과 겹치는
	 * 지점부터는 「잠깐 본다」가 아니라 상시 엑스레이라는 다른 증강이 된다.
	 */
	public static final int MAX_DURATION_SECONDS = 60;

	/**
	 * 기본 쿨타임(초).
	 *
	 * <p>정의에 {@code cooldown_seconds} 를 적으면 그쪽이 이긴다. 쿨타임을 조절할 때는
	 * <b>이 상수와 {@code sharedfate-perks-default.json} 의 값이 어긋나지 않게</b> 같이 봐야 한다.
	 *
	 * <p>이 값은 <b>지속이 끝난 뒤부터</b> 도므로 한 판의 실제 주기는 지속 + 쿨타임이다. 정의의
	 * 숫자를 고칠 때 그 점을 잊으면 체감 간격이 적은 것보다 길어진다.
	 *
	 * <p><b>전에는 30 초였다.</b> 2026-09-30 에 직접 플레이해 본 사람이 「너무 세다」고 해서
	 * 90 초(1분 30초)로 늘렸다. 30 초는 지속 10 초와 합쳐 주기가 40 초밖에 안 돼, 굴을 파는
	 * 내내 거의 상시로 켜 두고 다닐 수 있었다. 90 초면 주기가 100 초가 되어 「한 번 보고
	 * 그 자리를 파러 간다」가 된다.
	 */
	public static final int DEFAULT_COOLDOWN_SECONDS = 90;
	public static final int MIN_COOLDOWN_SECONDS = 1;
	/** 쿨타임 상한(초). 한 회차보다 길 이유가 없다. */
	public static final int MAX_COOLDOWN_SECONDS = 600;

	/** 한 번에 보여 주는 최대 개수. 광맥이 여러 개면 파티클이 수백 개가 되므로 상한을 둔다. */
	public static final int DEFAULT_MAX_RESULTS = 16;
	public static final int MIN_MAX_RESULTS = 1;
	public static final int MAX_MAX_RESULTS = 64;

	private static final int TICKS_PER_SECOND = 20;

	/** 엑스레이로 쓰는 바닐라 아이템. 26.2 에 {@code assets/minecraft/items/clock.json} 이 있다. */
	public static final Identifier ITEM = Identifier.withDefaultNamespace("clock");

	/** {@code minecraft:custom_data} 에 남기는 표식의 키. */
	public static final String MARKER_KEY = "sharedfate_item";
	/**
	 * {@code minecraft:custom_data} 에 남기는 표식의 값.
	 *
	 * <p>이름이 「엑스레이」로 바뀐 뒤에도 옛 값 그대로다. 이미 나가 있는 시계에 이 문자열이
	 * 적혀 있어, 바꾸면 그 시계들이 전부 평범한 시계가 된다.
	 */
	public static final String MARKER_VALUE = "diamond_sundial";

	/** 아이템에 붙는 이름. 쿨타임 표시도 이 이름을 쓴다. */
	public static final String DISPLAY_NAME = "엑스레이";

	/** 쿨타임 묶음. 평범한 시계와 갈라 두려고 모드 이름공간을 쓴다. */
	public static final Identifier COOLDOWN_GROUP = SharedFateMod.id("diamond_sundial");

	/**
	 * 광석 자리에 띄우는 파티클.
	 *
	 * <p>상수가 아니라 메서드인 것은 정의를 읽는 시점에 {@code ParticleTypes} 를 건드리지 않기
	 * 위해서다.
	 */
	public static SimpleParticleType particle() {
		return ParticleTypes.END_ROD;
	}

	/** 찾는 블록. 둘 다 26.2 에 있다({@code assets/minecraft/items/} 에 아이템 모형이 있다). */
	public static final Identifier ORE = Identifier.withDefaultNamespace("diamond_ore");
	public static final Identifier DEEPSLATE_ORE =
			Identifier.withDefaultNamespace("deepslate_diamond_ore");

	private final int radius;
	private final int durationTicks;
	private final int cooldownTicks;
	private final int maxResults;

	/** 레지스트리에서 찾아 만든 견본. 처음 지급할 때 한 번 만들고 계속 쓴다. */
	private @Nullable ItemStack template;

	public DiamondSundialEffect(int radius, int durationTicks, int cooldownTicks, int maxResults) {
		this.radius = radius;
		this.durationTicks = durationTicks;
		this.cooldownTicks = cooldownTicks;
		this.maxResults = maxResults;
	}

	/**
	 * JSON에서 만든다.
	 *
	 * <p>값이 범위를 벗어나면 경고만 남기고 잘라 쓴다. 반경이 틀렸다고 「다이아몬드가 보인다」는
	 * 증강 전체를 버리면 손해가 더 크기 때문이다.
	 */
	public static PerkEffect fromJson(String perkId, int index, JsonObject json) {
		int radius = clamp(perkId, "radius",
				PerkEffectType.readInt(json, "radius", DEFAULT_RADIUS), MIN_RADIUS, MAX_RADIUS);
		int duration = clamp(perkId, "duration_seconds",
				PerkEffectType.readInt(json, durationKey(json), DEFAULT_DURATION_SECONDS),
				MIN_DURATION_SECONDS, MAX_DURATION_SECONDS);
		int seconds = clamp(perkId, "cooldown_seconds",
				PerkEffectType.readInt(json, cooldownKey(json), DEFAULT_COOLDOWN_SECONDS),
				MIN_COOLDOWN_SECONDS, MAX_COOLDOWN_SECONDS);
		int maxResults = clamp(perkId, "max_results",
				PerkEffectType.readInt(json, "max_results", DEFAULT_MAX_RESULTS),
				MIN_MAX_RESULTS, MAX_MAX_RESULTS);
		return new DiamondSundialEffect(radius, duration * TICKS_PER_SECOND,
				seconds * TICKS_PER_SECOND, maxResults);
	}

	/** 카멜케이스로 적어도 읽어 준다. 다른 효과들이 {@code duration_minutes} 에서 쓰는 규칙과 같다. */
	private static String cooldownKey(JsonObject json) {
		return json != null && json.has("cooldownSeconds") ? "cooldownSeconds" : "cooldown_seconds";
	}

	private static String durationKey(JsonObject json) {
		return json != null && json.has("durationSeconds") ? "durationSeconds" : "duration_seconds";
	}

	private static int clamp(String perkId, String key, int value, int min, int max) {
		if (value >= min && value <= max) {
			return value;
		}
		int cut = Math.max(min, Math.min(max, value));
		SharedFateMod.LOGGER.warn(
				"증강 {}: diamond_sundial 의 {} 가 {}~{} 범위를 벗어나 {} 로 자릅니다 ({})",
				perkId, key, min, max, cut, value);
		return cut;
	}

	/** 탐지 반경(칸). */
	public int radius() {
		return radius;
	}

	/** 한 번 우클릭했을 때 광석이 계속 보이는 시간(틱). */
	public int durationTicks() {
		return durationTicks;
	}

	/** 쿨타임(틱). 지속이 <b>끝난 뒤부터</b> 돈다. */
	public int cooldownTicks() {
		return cooldownTicks;
	}

	/** 한 번에 보여 주는 최대 개수. */
	public int maxResults() {
		return maxResults;
	}

	/**
	 * 이번 선택으로 지급할 엑스레이 하나.
	 *
	 * <p>부를 때마다 새 사본을 돌려준다. 받는 쪽이 {@code shrink} 로 개수를 깎기 때문에 견본을
	 * 그대로 넘기면 두 번째 지급 때 빈 묶음이 나간다. 아이템을 레지스트리에서 찾는 일은 정의를
	 * 읽는 시점이 아니라 처음 지급할 때 한다 — 정의를 읽는 시점에는 레지스트리가 아직 준비되지
	 * 않았을 수 있다.
	 *
	 * @return 엑스레이 한 개. 시계를 찾지 못했으면 null
	 */
	public @Nullable ItemStack createItem() {
		ItemStack cached = template;
		if (cached == null) {
			cached = build();
			if (cached == null) {
				return null;
			}
			template = cached;
		}
		return cached.copy();
	}

	private @Nullable ItemStack build() {
		Item item;
		try {
			Optional<Holder.Reference<Item>> found = BuiltInRegistries.ITEM.get(ITEM);
			if (found.isEmpty()) {
				SharedFateMod.LOGGER.warn("엑스레이로 쓸 아이템을 찾을 수 없습니다: {}", ITEM);
				return null;
			}
			item = found.get().value();
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.warn("엑스레이로 쓸 아이템 {} 을 찾다가 실패했습니다", ITEM, error);
			return null;
		}
		// 아이템 레지스트리는 기본값이 공기라 없는 이름도 공기로 돌아올 수 있다.
		if (item == Items.AIR) {
			SharedFateMod.LOGGER.warn("엑스레이로 쓸 아이템을 찾을 수 없습니다: {}", ITEM);
			return null;
		}
		return decorate(new ItemStack(item, 1), cooldownTicks / (float) TICKS_PER_SECOND);
	}

	/**
	 * 시계 하나를 엑스레이로 만든다. 표식·이름·쿨타임 묶음을 붙인다.
	 *
	 * @param cooldownSeconds {@code minecraft:use_cooldown} 에 적을 초. 이 컴포넌트는 <b>묶음 이름을
	 *                        정하려고</b> 붙이는 것이라 실제로 쿨타임을 거는 것은 이 값이 아니다.
	 *                        그래도 정의와 어긋난 숫자를 남기지 않도록 같은 값을 적어 둔다.
	 */
	public static ItemStack decorate(ItemStack stack, float cooldownSeconds) {
		CompoundTag marker = new CompoundTag();
		marker.putString(MARKER_KEY, MARKER_VALUE);
		stack.set(DataComponents.CUSTOM_DATA, CustomData.of(marker));
		// 이름은 알아보기 위한 것일 뿐 판정에는 쓰지 않는다. 기본 기울임은 꺼 둔다.
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(DISPLAY_NAME)
				.withStyle(style -> style.withItalic(false).withColor(ChatFormatting.AQUA)));
		stack.set(DataComponents.USE_COOLDOWN,
				new UseCooldown(cooldownSeconds, Optional.of(COOLDOWN_GROUP)));
		return stack;
	}

	/**
	 * 이 묶음이 엑스레이인가.
	 *
	 * <p>이름이 아니라 {@code minecraft:custom_data} 의 표식으로 판정한다.
	 */
	public static boolean isSundial(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		CustomData data = stack.get(DataComponents.CUSTOM_DATA);
		if (data == null || data.isEmpty()) {
			return false;
		}
		return MARKER_VALUE.equals(data.copyTag().getStringOr(MARKER_KEY, ""));
	}

	/** 찾는 블록인가. 다이아몬드 광석과 심층암 다이아몬드 광석 둘 다다. */
	public static boolean isDiamondOre(@Nullable BlockState state) {
		return state != null
				&& (state.is(Blocks.DIAMOND_ORE) || state.is(Blocks.DEEPSLATE_DIAMOND_ORE));
	}
}
