package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.team.TeamState;
import com.sharedfate.sync.TrialCatalog;
import org.junit.jupiter.api.*;
import java.util.List;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.NbtOps;
import static org.junit.jupiter.api.Assertions.*;

/** 새 대비 카드가 팀별로 적용되고 기존 풀을 건드리지 않는지 본다. */
class DragonPrepExpansionTest {
	@BeforeAll static void bootstrap() { TestBootstrap.ensureInitialized(); }
	@BeforeEach void load() { PerkRegistry.loadBundled(); }
	@AfterEach void clear() { PerkRegistry.clear(); }
	private TeamState team(String id) {
		TeamState state = TeamState.fresh(20);
		state.perksEnabled = true;
		state.ownedPerks.add("sharedfate:" + id);
		return state;
	}
	@Test void 금간수정은_회복_크리스탈_초기체력만_일로_바꾼다() {
		TeamState state = team("cracked_crystal");
		assertEquals(1, DragonPrep.recoveryCrystalHealth(state, 30));
		assertEquals(30, DragonPrep.recoveryCrystalHealth(null, 30));
		state.perksEnabled = false;
		assertEquals(30, DragonPrep.recoveryCrystalHealth(state, 30));
	}
	@Test void 이지모드는_지정한_세_풀에서_한장씩만_빼고_후보를_남긴다() {
		TeamState state = team("easy_mode");
		for (var trigger : TrialCatalog.Trigger.values()) {
			var original = TrialCatalog.offerable(trigger, List.of());
			var filtered = DragonPrep.filterTrials(state, trigger, original);
			boolean affected = trigger == TrialCatalog.Trigger.ENTRY
					|| trigger == TrialCatalog.Trigger.FIRST_CRYSTAL || trigger == TrialCatalog.Trigger.HEALTH_50;
			assertEquals(original.size() - (affected ? 1 : 0), filtered.size());
			if (affected) assertFalse(filtered.isEmpty());
			assertTrue(filtered.stream().noneMatch(card -> List.of("sharedfate:hotbar_lock",
					"sharedfate:iron_cage", "sharedfate:crystal_revival").contains(card.id())));
			assertEquals(original, DragonPrep.filterTrials(null, trigger, original));
		}
	}
	@Test void 이미_선택한_카드까지_빼면_빈_풀도_그대로_넘긴다() {
		var trigger = TrialCatalog.Trigger.ENTRY;
		var chosen = TrialCatalog.offerable(trigger, List.of()).stream().map(TrialCatalog.Trial::id).toList();
		assertTrue(DragonPrep.filterTrials(team("easy_mode"), trigger,
				TrialCatalog.offerable(trigger, chosen)).isEmpty());
	}
	@Test void 결전보급은_첫입장뒤_공유칸에_정확히_한번_들어간다() {
		TeamState state = team("battle_supply");
		assertTrue(DragonPrep.prepareBattleSupply(state, false).isEmpty());
		assertFalse(state.battleSupplyGranted);
		state.endEntered = true;
		assertTrue(DragonPrep.prepareBattleSupply(state, false).isEmpty());
		assertTrue(state.battleSupplyGranted);
		assertEquals(10, state.mainItems.stream().filter(s -> s.is(Items.GOLDEN_APPLE)).mapToInt(ItemStack::getCount).sum());
		assertEquals(120, state.mainItems.stream().filter(s -> s.is(Items.ARROW)).mapToInt(ItemStack::getCount).sum());
		DragonPrep.prepareBattleSupply(state, false);
		assertEquals(120, state.mainItems.stream().filter(s -> s.is(Items.ARROW)).mapToInt(ItemStack::getCount).sum());
	}
	@Test void 늦게_얻어도_지급하고_이번에_넘친_것만_떨굴_목록으로_돌려준다() {
		TeamState state = team("anchor");
		state.endEntered = true;
		assertTrue(DragonPrep.prepareBattleSupply(state, false).isEmpty());
		assertFalse(state.battleSupplyGranted);
		state.ownedPerks.add("sharedfate:battle_supply");
		for (int i = 0; i < state.mainItems.size(); i++) state.mainItems.set(i, new ItemStack(Items.STONE, 64));
		state.mainItems.set(0, new ItemStack(Items.GOLDEN_APPLE, 60));
		ItemStack pending = new ItemStack(Items.DIRT, 5);
		state.overflowItems.add(pending);
		var leftovers = DragonPrep.prepareBattleSupply(state, false);
		assertEquals(6, leftovers.stream().filter(s -> s.is(Items.GOLDEN_APPLE)).mapToInt(ItemStack::getCount).sum());
		assertEquals(120, leftovers.stream().filter(s -> s.is(Items.ARROW)).mapToInt(ItemStack::getCount).sum());
		assertEquals(List.of(pending), state.overflowItems);
		assertTrue(DragonPrep.prepareBattleSupply(state, false).isEmpty());
	}
	@Test void 저장과_재입장을_지나도_지급표시가_남고_회차가_바뀌면_초기화된다() {
		TeamState state = team("battle_supply");
		state.endEntered = true;
		state.battleSupplyGranted = true;
		var encoded = TeamState.CODEC.encodeStart(NbtOps.INSTANCE, state).getOrThrow();
		TeamState loaded = TeamState.CODEC.parse(NbtOps.INSTANCE, encoded).getOrThrow();
		assertTrue(loaded.endEntered);
		assertTrue(loaded.battleSupplyGranted);
		assertTrue(DragonPrep.prepareBattleSupply(loaded, false).isEmpty());
		loaded.resetAfterDeath(20, false);
		assertFalse(loaded.endEntered);
		assertFalse(loaded.battleSupplyGranted);
		TeamState old = TeamState.fresh(20);
		var oldTag = TeamState.CODEC.encodeStart(NbtOps.INSTANCE, old).getOrThrow();
		assertFalse(TeamState.CODEC.parse(NbtOps.INSTANCE, oldTag).getOrThrow().endEntered);
	}
}
