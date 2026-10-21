package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.team.TeamState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerkStateCodecTest {
	private static final UUID CHOOSER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static CompoundTag encode(TeamState state) {
		return (CompoundTag) TeamState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
	}

	private static TeamState decode(CompoundTag tag) {
		return TeamState.CODEC.parse(NbtOps.INSTANCE, tag).getOrThrow();
	}

	/** 증강을 한창 진행한 팀. */
	private static TeamState playedState() {
		TeamState state = TeamState.fresh(40.0F);
		state.mainItems.set(0, new ItemStack(Items.DIAMOND, 3));
		state.xpLevel = 16;
		state.perksEnabled = true;
		state.lastPerkMilestone = 10;
		state.ownedPerks.add("sharedfate:tough_body");
		state.ownedPerks.add("sharedfate:glass_cannon");
		state.pending.add(new PendingOffer(15, Optional.of(CHOOSER),
				List.of("sharedfate:a", "sharedfate:b", "sharedfate:c")));
		state.pending.add(new PendingOffer(18, Optional.empty(),
				List.of("sharedfate:d", "sharedfate:e")));
		return state;
	}

	@Test
	void 증강_상태는_왕복_직렬화된다() {
		TeamState round = decode(encode(playedState()));

		assertTrue(round.perksEnabled);
		assertEquals(10, round.lastPerkMilestone);
		assertEquals(List.of("sharedfate:tough_body",
						"sharedfate:glass_cannon"),
				round.ownedPerks);
		assertEquals(2, round.pending.size());
		assertEquals(15, round.pending.getFirst().milestone());
		assertEquals(Optional.of(CHOOSER), round.pending.getFirst().chooser());
		assertEquals(List.of("sharedfate:a", "sharedfate:b", "sharedfate:c"),
				round.pending.getFirst().optionIds());
		assertEquals(18, round.pending.get(1).milestone());
		assertTrue(round.pending.get(1).chooser().isEmpty(), "선택자 미정 상태가 유지돼야 한다");

		// 기존 필드도 그대로여야 한다
		assertTrue(round.mainItems.get(0).is(Items.DIAMOND));
		assertEquals(3, round.mainItems.get(0).getCount());
		assertEquals(16, round.xpLevel);
		assertEquals(40.0F, round.maxHealth);
	}

	@Test
	void 증강_필드가_없는_기존_월드도_그대로_열린다() {
		CompoundTag encoded = encode(playedState());
		encoded.remove("perks");

		TeamState round = decode(encoded);

		assertFalse(round.perksEnabled, "구 저장은 증강 꺼진 상태로 열려야 한다");
		assertEquals(0, round.lastPerkMilestone);
		assertTrue(round.ownedPerks.isEmpty());
		assertTrue(round.pending.isEmpty());

		// 증강과 무관한 기존 데이터는 손실 없이 살아남는다
		assertTrue(round.mainItems.get(0).is(Items.DIAMOND));
		assertEquals(16, round.xpLevel);
		assertEquals(40.0F, round.maxHealth);
		assertEquals(TeamState.EXTRA_SIZE, round.extraItems.size());
	}

	@Test
	void 증강을_쓰지_않는_팀은_perks_항목을_아예_저장하지_않는다() {
		CompoundTag encoded = encode(TeamState.fresh(40.0F));

		assertFalse(encoded.contains("perks"),
				"증강을 안 쓰면 저장 형태가 증강 도입 전과 같아야 한다");
		assertTrue(decode(encoded).ownedPerks.isEmpty());
	}

	@Test
	void 증강_묶음은_기존_필드와_같은_깊이에_한_항목으로만_붙는다() {
		CompoundTag legacy = encode(TeamState.fresh(40.0F));
		CompoundTag withPerks = encode(playedState());

		assertTrue(withPerks.keySet().containsAll(legacy.keySet()),
				"기존 항목 이름이 하나도 바뀌면 안 된다");
		assertTrue(withPerks.contains("perks"));
		assertEquals(legacy.keySet().size() + 1, withPerks.keySet().size(),
				"증강은 perks 한 항목만 늘려야 한다");
	}

	@Test
	void 켜기만_하고_아직_아무것도_안_고른_팀도_저장된다() {
		TeamState state = TeamState.fresh(40.0F);
		state.perksEnabled = true;

		TeamState round = decode(encode(state));

		assertTrue(round.perksEnabled);
		assertEquals(0, round.lastPerkMilestone);
		assertTrue(round.ownedPerks.isEmpty());
		assertTrue(round.pending.isEmpty());
	}

	@Test
	void 손상된_구간값과_후보없는_선택권은_읽으면서_고친다() {
		TeamState state = TeamState.fresh(40.0F);
		state.perksEnabled = true;
		state.lastPerkMilestone = 1000;
		state.pending.add(new PendingOffer(3, Optional.empty(), List.of()));
		state.ownedPerks.add("  ");
		state.ownedPerks.add("sharedfate:ok");

		TeamState round = decode(encode(state));

		assertEquals(PerkMilestones.MAX, round.lastPerkMilestone, "구간 상한을 넘으면 MAX(40)로 잘린다");
		assertTrue(round.pending.isEmpty(), "후보가 하나도 없는 선택권은 버린다");
		assertEquals(List.of("sharedfate:ok"), round.ownedPerks);
	}

	@Test
	void 중첩이_있던_시절의_저장도_그대로_열린다() {
		// 예전에는 보유 증강을 {perkId, count} 객체로 적었다. 이미 돌아가는 서버의 월드에
		// 그 형태가 들어 있으므로 읽을 수 있어야 한다. count 는 뜻이 사라졌으니 버린다.
		CompoundTag legacy = encode(playedState());
		CompoundTag perks = legacy.getCompound("perks").orElseThrow();
		ListTag owned = new ListTag();
		owned.add(legacyEntry("sharedfate:tough_body", 2));
		owned.add(legacyEntry("sharedfate:glass_cannon", 1));
		perks.put("owned", owned);

		TeamState round = decode(legacy);

		assertEquals(List.of("sharedfate:tough_body", "sharedfate:glass_cannon"), round.ownedPerks,
				"중첩 수는 버리고 id 만 남는다");
		assertTrue(round.perksEnabled);
		assertEquals(2, round.pending.size(), "증강 밖의 항목도 손상되면 안 된다");
	}

	@Test
	void 중첩_시절_저장에_같은_증강이_두_번_들어_있어도_한_개로_접는다() {
		CompoundTag legacy = encode(playedState());
		CompoundTag perks = legacy.getCompound("perks").orElseThrow();
		ListTag owned = new ListTag();
		owned.add(legacyEntry("sharedfate:tough_body", 3));
		owned.add(legacyEntry("sharedfate:tough_body", 1));
		perks.put("owned", owned);

		assertEquals(List.of("sharedfate:tough_body"), decode(legacy).ownedPerks);
	}

	@Test
	void 새로_저장한_보유_증강은_문자열_목록이다() {
		CompoundTag perks = encode(playedState()).getCompound("perks").orElseThrow();
		ListTag owned = perks.getList("owned").orElseThrow();

		assertEquals(2, owned.size());
		assertEquals("sharedfate:tough_body", owned.getString(0).orElseThrow());
		assertEquals("sharedfate:glass_cannon", owned.getString(1).orElseThrow());
	}

	/** 중첩이 있던 시절의 보유 증강 한 칸. */
	private static CompoundTag legacyEntry(String perkId, int count) {
		CompoundTag tag = new CompoundTag();
		tag.putString("perkId", perkId);
		tag.putInt("count", count);
		return tag;
	}

	@Test
	void 같은_저장을_두_번_읽어도_증강_목록을_공유하지_않는다() {
		CompoundTag encoded = encode(playedState());

		TeamState first = decode(encoded);
		TeamState second = decode(encoded);

		assertNotSame(first.ownedPerks, second.ownedPerks);
		assertNotSame(first.pending, second.pending);

		first.ownedPerks.clear();
		first.pending.clear();

		assertEquals(2, second.ownedPerks.size(), "한 팀을 건드려도 다른 팀이 흔들리면 안 된다");
		assertEquals(2, second.pending.size());
	}

	@Test
	void 보유_증강_목록은_저장_뒤에도_계속_수정할_수_있다() {
		TeamState round = decode(encode(playedState()));

		round.ownedPerks.add("sharedfate:new");
		round.pending.removeFirst();

		assertEquals(3, round.ownedPerks.size());
		assertEquals(1, round.pending.size());
	}

	// ------------------------------------------------------------------ 유산(legacyGear)

	@Test
	void 유산이_남긴_아이템도_왕복_직렬화된다() {
		TeamState state = TeamState.fresh(20.0F);
		state.legacyGear.add(new ItemStack(Items.DIAMOND_PICKAXE));
		state.legacyGear.add(new ItemStack(Items.DIAMOND_CHESTPLATE));

		TeamState round = decode(encode(state));

		assertEquals(2, round.legacyGear.size());
		assertTrue(round.legacyGear.get(0).is(Items.DIAMOND_PICKAXE));
		assertTrue(round.legacyGear.get(1).is(Items.DIAMOND_CHESTPLATE));
	}

	@Test
	void legacyGear_가_없는_저장은_빈_목록으로_열린다() {
		CompoundTag encoded = encode(TeamState.fresh(20.0F));

		assertFalse(encoded.contains("legacyGear"),
				"승계분이 없으면 저장 형태가 기존과 같아야 한다");
		assertTrue(decode(encoded).legacyGear.isEmpty());
	}
}
