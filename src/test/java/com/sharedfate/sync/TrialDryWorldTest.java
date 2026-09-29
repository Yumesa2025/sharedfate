package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「메마른 세계」에서 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>물을 실제로 지우는 일은 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다. 그런데 이
 * 카드가 <b>쓸모없어지는</b> 길은 거의 전부 그 바깥이다 — <b>막아야 할 양동이를 하나 빠뜨린다</b>,
 * <b>금지가 전투보다 오래 산다</b>, <b>훑는 범위가 기둥 위를 놓친다</b>.
 *
 * <p>특히 첫째가 무섭다. 이 카드가 빼앗는 것은 MLG 물받이인데, 물을 놓는 양동이를 하나라도
 * 놓치면 카드는 「양동이 한 번 다시 채우기」가 되고 <b>그 사실이 화면에 아무 표시도 남기지
 * 않는다.</b> 그래서 레지스트리 전체를 훑어 확인한다.
 */
class TrialDryWorldTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ 무엇을 막는가

	/**
	 * 막는 양동이가 <b>정확히 아홉 개</b>다.
	 *
	 * <p>물·용암·서리눈 셋만 적으면 새고, 「{@code MobBucketItem} 이면 막는다」로 적으면 물을
	 * 놓지 않는 유황 덩이 양동이까지 막는다. 26.3 에서 실제로 세어 본 목록이 이것이다.
	 *
	 * <p>⚠ 판을 올려 이 시험이 깨지면 <b>고칠 것은 이 목록이 아니라 실행기일 수 있다.</b>
	 * 늘어난 이름이 물을 놓는 물건이면 실행기가 옳게 잡은 것이고, 줄어든 이름이 있으면 그 판에서
	 * 없어진 것이다. 둘 중 어느 쪽인지 확인하지 않고 목록만 고치지 말 것.
	 */
	@Test
	void 물을_놓는_양동이를_하나도_빠뜨리지_않는다() {
		Set<String> banned = new TreeSet<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (TrialDryWorld.placesFluid(item)) {
				banned.add(BuiltInRegistries.ITEM.getKey(item).toString());
			}
		}
		assertEquals(new TreeSet<>(Set.of(
						"minecraft:water_bucket",
						"minecraft:lava_bucket",
						"minecraft:powder_snow_bucket",
						"minecraft:cod_bucket",
						"minecraft:salmon_bucket",
						"minecraft:pufferfish_bucket",
						"minecraft:tropical_fish_bucket",
						"minecraft:axolotl_bucket",
						"minecraft:tadpole_bucket")),
				banned,
				"물을 놓는 양동이 목록이 달라졌다 — 물고기 양동이도 물을 한 칸 놓는다");
	}

	/**
	 * 양동이로 무언가를 <b>붓는</b> 아이템은 전부 셋 중 하나로 갈려 있다 — 막거나, 막지 않기로
	 * 확인했거나.
	 *
	 * <p>{@code DispensibleContainerItem} 이 「디스펜서로 부을 수 있는 것」의 인터페이스이고
	 * 실행기가 잡는 자리도 그 구현들이다. 판이 올라가며 이 인터페이스에 새 아이템이 붙으면
	 * <b>여기서 먼저 걸린다</b> — 그때 그것이 물을 놓는지 사람이 확인해야 한다.
	 */
	@Test
	void 붓는_아이템은_전부_판단이_끝나_있다() {
		Set<String> undecided = new TreeSet<>();
		for (Item item : BuiltInRegistries.ITEM) {
			if (!(item instanceof DispensibleContainerItem)) {
				continue;
			}
			if (TrialDryWorld.placesFluid(item)) {
				continue;
			}
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			// 막지 않기로 확인한 둘. 빈 양동이는 담기만 하고, 유황 덩이 양동이는 26.3 에 새로
			// 생겼는데 내용물이 빈 유체라 생물만 놓는다.
			if (id.equals("minecraft:bucket") || id.equals("minecraft:sulfur_cube_bucket")) {
				continue;
			}
			undecided.add(id);
		}
		assertTrue(undecided.isEmpty(),
				"붓는 아이템이 새로 생겼다. 물을 놓는지 확인하고 갈라 둘 것: " + undecided);
	}

	/** 우유는 양동이처럼 생겼지만 붓는 물건이 아니다. 막으면 마시지도 못하게 된다. */
	@Test
	void 우유와_빈_양동이는_막지_않는다() {
		assertFalse(TrialDryWorld.placesFluid(Items.MILK_BUCKET));
		assertFalse(TrialDryWorld.placesFluid(Items.BUCKET));
		assertFalse(TrialDryWorld.placesFluid(null));
	}

	// ------------------------------------------------------------------ 무엇을 비우는가

	/**
	 * 카드에 적힌 셋만 빈 양동이가 된다.
	 *
	 * <p>물고기 양동이도 물을 놓지만 여기서 비우지 않는다 — 비우면 안에 있던 생물이 소리 없이
	 * 사라진다. 그쪽은 설치 금지로 막는 것이 카드에 적힌 대로다.
	 */
	@Test
	void 카드에_적힌_세_양동이만_비운다() {
		for (Item full : Set.of(Items.WATER_BUCKET, Items.LAVA_BUCKET, Items.POWDER_SNOW_BUCKET)) {
			ItemStack drained = TrialDryWorld.drained(new ItemStack(full));
			assertNotNull(drained, full + " 은 비워져야 한다");
			assertTrue(drained.is(Items.BUCKET), "없애지 말고 빈 양동이로 바꿀 것");
			assertEquals(1, drained.getCount());
		}
		assertNull(TrialDryWorld.drained(new ItemStack(Items.AXOLOTL_BUCKET)),
				"비우면 안에 있던 생물이 사라진다 — 카드에 적힌 것은 물·용암·서리눈뿐이다");
		assertNull(TrialDryWorld.drained(new ItemStack(Items.MILK_BUCKET)));
		assertNull(TrialDryWorld.drained(new ItemStack(Items.BUCKET)));
		assertNull(TrialDryWorld.drained(ItemStack.EMPTY));
		assertNull(TrialDryWorld.drained(null));
	}

	/** 개수를 그대로 옮긴다. 1 로 박아 두면 쌓이게 바뀌는 판에서 말없이 물건을 지운다. */
	@Test
	void 비울_때_개수를_잃지_않는다() {
		ItemStack drained = TrialDryWorld.drained(new ItemStack(Items.WATER_BUCKET, 3));
		assertNotNull(drained);
		assertEquals(3, drained.getCount());
	}

	// ------------------------------------------------------------------ 금지의 수명

	/**
	 * 한 번도 걸린 적 없으면 금지가 아니다.
	 *
	 * <p>{@link Long#MIN_VALUE} 를 그냥 「작은 수」로 비교하면 <b>모든 시각이 그보다 크므로</b>
	 * 부등호를 뒤집어 적는 순간 카드를 뽑지도 않았는데 엔드에서 물을 못 놓게 된다.
	 */
	@Test
	void 카드를_받기_전에는_금지가_아니다() {
		assertFalse(TrialDryWorld.banHolds(0L, Long.MIN_VALUE));
		assertFalse(TrialDryWorld.banHolds(Long.MIN_VALUE, Long.MIN_VALUE));
		assertFalse(TrialDryWorld.banHolds(1_000_000L, Long.MIN_VALUE));
	}

	/**
	 * 우리가 매 틱 밀어 주는 동안만 금지가 산다.
	 *
	 * <p>이 카드의 금지는 깃발이 아니라 <b>기한</b>이다. 드래곤이 죽으면
	 * {@code DragonTrialManager} 가 {@code TrialRisks.clearState()} 를 부르지 않으므로, 깃발이면
	 * 전투가 끝난 뒤에도 엔드에서 물을 못 놓는다. 기한이면 저절로 풀린다.
	 */
	@Test
	void 부르지_않게_되면_금지가_저절로_풀린다() {
		long granted = 500L;
		long until = granted + TrialDryWorld.BAN_GRACE_TICKS;
		assertTrue(TrialDryWorld.banHolds(granted, until), "민 그 틱에는 당연히 금지다");
		assertTrue(TrialDryWorld.banHolds(until - 1L, until), "여유가 다 차기 전까지는 금지다");
		assertFalse(TrialDryWorld.banHolds(until, until), "여유가 끝나면 풀린다");
		assertFalse(TrialDryWorld.banHolds(until + 1000L, until));
	}

	/** 여유가 전투 한 판만큼 길면 「저절로 풀린다」가 거짓말이 된다. */
	@Test
	void 금지를_붙들고_있는_여유가_짧다() {
		assertTrue(TrialDryWorld.BAN_GRACE_TICKS > 0, "0 이면 우리 틱과 사람 클릭이 한 틱만"
				+ " 어긋나도 금지가 깜빡인다");
		assertTrue(TrialDryWorld.BAN_GRACE_TICKS <= 100,
				"드래곤을 잡고 5초를 넘게 기다리면 사람은 카드가 안 풀렸다고 읽는다");
	}

	// ------------------------------------------------------------------ 훑는 범위

	/**
	 * 흑요석 기둥 위가 범위 안이다.
	 *
	 * <p>바닐라 {@code EndSpikeFeature} 가 기둥을 중앙에서 <b>42칸</b> 되는 원 위에 세우고
	 * 기둥 반지름이 최대 3 이라 윗면이 45칸까지 나간다. MLG 물받이가 가장 절실한 자리가 거기다 —
	 * 반경을 42 이하로 줄이면 <b>이 카드가 노리는 바로 그 자리</b>의 물이 남는다.
	 */
	@Test
	void 기둥_위가_훑는_범위_안이다() {
		assertTrue(TrialDryWorld.SCAN_RADIUS >= 45,
				"기둥 윗면이 45칸까지 나간다. 거기 물이 남으면 카드가 노리는 자리가 비어 있다");
		assertTrue(TrialDryWorld.insideScan(45, 103, 0), "가장 높은 기둥 꼭대기");
		assertTrue(TrialDryWorld.insideScan(0, 63, 0), "중앙 섬 표면");
		assertTrue(TrialDryWorld.insideScan(30, 70, 30), "42 원 안쪽 대각선");
	}

	/** 범위 밖은 건드리지 않는다. 훑는 일이 커지면 「한 번만 하는 일」이 서버를 멈춘다. */
	@Test
	void 아레나_밖과_높이_밖은_훑지_않는다() {
		int outside = TrialDryWorld.SCAN_RADIUS + 1;
		assertFalse(TrialDryWorld.insideScan(outside, 63, 0));
		assertFalse(TrialDryWorld.insideScan(0, 63, outside));
		// 오버월드에서 오는 흑요석 발판 자리. 전투와 상관없는 자리까지 지우지 않는다.
		assertFalse(TrialDryWorld.insideScan(100, 50, 0));
		assertFalse(TrialDryWorld.insideScan(0, TrialDryWorld.SCAN_MIN_Y - 1, 0));
		assertFalse(TrialDryWorld.insideScan(0, TrialDryWorld.SCAN_MAX_Y + 1, 0));
	}

	/** 위아래 끝은 범위 안이다. 경계를 밖으로 두면 그 한 층의 물만 남는다. */
	@Test
	void 높이_경계는_범위_안이다() {
		assertTrue(TrialDryWorld.insideScan(0, TrialDryWorld.SCAN_MIN_Y, 0));
		assertTrue(TrialDryWorld.insideScan(0, TrialDryWorld.SCAN_MAX_Y, 0));
	}

	// ------------------------------------------------------------------ 카드 자체

	/**
	 * 이 카드는 값이 없다. 동작이 고정이기 때문이다.
	 *
	 * <p>칸이 생기면 「한 번」과 「끝까지」 중 어느 쪽을 재는 값인지 여기서 먼저 정해야 한다.
	 */
	@Test
	void 카드에_고를_값이_없다() {
		assertEquals(0, TrialCatalog.Risk.DryWorld.class.getRecordComponents().length,
				"칸을 더했으면 그 값이 증발 쪽인지 금지 쪽인지 TrialDryWorld 에 적을 것");
	}
}
