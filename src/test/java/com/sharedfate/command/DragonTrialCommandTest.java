package com.sharedfate.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.sharedfate.SharedFateMod;
import com.sharedfate.TestBootstrap;
import com.sharedfate.config.SharedFateConfig;
import com.sharedfate.sync.TrialCatalog;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 카드를 하나씩 집어 보는 시험 명령의 <b>트리 모양</b>.
 *
 * <h2>무엇을 못 하는가</h2>
 *
 * <p>명령을 실제로 <b>실행</b>하려면 서버와 {@code CommandSourceStack} 이 있어야 해서 단위
 * 시험으로는 닿지 않는다({@code ShareTeamAliasTest}·{@code RunResetCommandTest} 와 같은
 * 사정이다). 대신 브리가디어가 실제로 보는 모양과 자동 완성을 확인한다.
 *
 * <h2>실제 배선을 그대로 쓴다</h2>
 *
 * <p>{@link ShareTeamCommand#register} 를 그대로 부른다. 여기서 가지를 손으로 꽂으면
 * {@code trialtest} 가 통째로 등록에서 빠져도 이 파일은 초록으로 남는다.
 *
 * <h2>왜 자동 완성까지 시험하는가</h2>
 *
 * <p>이 명령의 값어치 절반이 자동 완성이다. {@code sharedfate:lightning_storm} 을 손으로 쳐야
 * 한다면 아무도 안 쓴다. 그런데 자동 완성은 <b>없어도 명령이 멀쩡히 동작하므로</b> 빠진 것을
 * 아무도 눈치채지 못한다. 카드를 더하고 제안 목록에 안 넣는 사고도 마찬가지다 — 그래서 개수를
 * {@link TrialCatalog#all()} 과 맞춰 본다.
 */
class DragonTrialCommandTest {
	private SharedFateConfig previousConfig;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	void rememberConfig() {
		previousConfig = SharedFateMod.config;
	}

	@AfterEach
	void restoreConfig() {
		SharedFateMod.config = previousConfig;
	}

	/**
	 * 생산 경로로 트리를 짓고 {@code trialtest} 가지를 돌려준다.
	 *
	 * <p>잠금이 <b>등록 시점</b>에 걸리므로 설정을 먼저 꽂고 등록해야 한다.
	 */
	private CommandNode<CommandSourceStack> trialTest(boolean testCommands) {
		SharedFateConfig config = new SharedFateConfig();
		config.perkTestCommands = testCommands;
		SharedFateMod.config = config;
		CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
		ShareTeamCommand.register(dispatcher, config);
		CommandNode<CommandSourceStack> node =
				dispatcher.getRoot().getChild("shareteam").getChild("trialtest");
		assertNotNull(node, "trialtest 가지가 있어야 한다");
		return node;
	}

	/** 켜진 서버의 트리. */
	private CommandNode<CommandSourceStack> trialTest() {
		return trialTest(true);
	}

	/** 인자 노드에 걸린 자동 완성을 직접 돌린다. 빈 입력이라 거르지 않고 전부 나온다. */
	private static List<Suggestion> suggest(CommandNode<CommandSourceStack> argument) {
		assertNotNull(argument, "인자 노드가 있어야 한다");
		SuggestionProvider<CommandSourceStack> provider =
				((ArgumentCommandNode<CommandSourceStack, ?>) argument).getCustomSuggestions();
		assertNotNull(provider, argument.getName() + " 에 자동 완성이 붙어 있어야 한다");
		try {
			// 두 제안기 모두 context 를 보지 않는다. 서버 없이 돌릴 수 있는 이유다.
			Suggestions suggestions = provider.getSuggestions(null, new SuggestionsBuilder("", 0)).join();
			return suggestions.getList();
		} catch (CommandSyntaxException error) {
			throw new AssertionError("자동 완성이 터졌다", error);
		}
	}

	private static List<String> texts(List<Suggestion> suggestions) {
		List<String> values = new ArrayList<>();
		for (Suggestion suggestion : suggestions) {
			values.add(suggestion.getText());
		}
		return values;
	}

	// ------------------------------------------------------------------ 트리 모양

	/**
	 * 카드를 하나씩 보는 길 셋이 <b>실제로</b> 달려 있다.
	 *
	 * <p>등록이 통째로 빠지면 게임 안에서는 「모르는 명령」한 줄만 뜨고 끝난다. 여기서 빨개져야
	 * 한다.
	 */
	@Test
	void 카드_시험_가지_셋이_모두_등록된다() {
		CommandNode<CommandSourceStack> node = trialTest();

		assertNotNull(node.getChild("give"), "give 가지가 있어야 한다");
		assertNotNull(node.getChild("fire"), "fire 가지가 있어야 한다");
		assertNotNull(node.getChild("clear"), "clear 가지가 있어야 한다");
	}

	/** 앞서 있던 셋을 새 가지가 밀어내지 않았는지. */
	@Test
	void 원래_있던_가지_셋도_그대로_있다() {
		CommandNode<CommandSourceStack> node = trialTest();

		for (String child : new String[] {"status", "start", "list"}) {
			assertNotNull(node.getChild(child), child + " 가지가 있어야 한다");
			assertNotNull(node.getChild(child).getCommand(), child + " 가 실행되어야 한다");
		}
	}

	@Test
	void give_와_fire_는_각각_인자_하나를_받는다() {
		CommandNode<CommandSourceStack> node = trialTest();

		assertNull(node.getChild("give").getCommand(),
				"인자 없는 give 는 실행되지 않아야 한다. 어느 카드인지 모른다");
		assertNotNull(node.getChild("give").getChild(DragonTrialCommand.ARG_TRIAL_ID));
		assertNotNull(node.getChild("give").getChild(DragonTrialCommand.ARG_TRIAL_ID).getCommand());

		assertNull(node.getChild("fire").getCommand());
		assertNotNull(node.getChild("fire").getChild(DragonTrialCommand.ARG_TRIGGER));
		assertNotNull(node.getChild("fire").getChild(DragonTrialCommand.ARG_TRIGGER).getCommand());
	}

	/** 비우는 것은 시험으로 쌓은 것뿐이라 되묻지 않는다. 뒤에 붙는 것도 없다. */
	@Test
	void clear_는_인자_없이_바로_실행된다() {
		CommandNode<CommandSourceStack> clear = trialTest().getChild("clear");

		assertNotNull(clear.getCommand());
		assertTrue(clear.getChildren().isEmpty());
	}

	// ------------------------------------------------------------------ 자동 완성

	@Test
	void give_자동완성이_카드_id_를_전부_제안한다() {
		List<Suggestion> suggestions =
				suggest(trialTest().getChild("give").getChild(DragonTrialCommand.ARG_TRIAL_ID));

		assertEquals(TrialCatalog.all().size(), suggestions.size(),
				"카드를 더하고 제안에 안 넣으면 그 카드는 손으로 쳐야만 볼 수 있다");
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			assertTrue(texts(suggestions).contains(trial.id()), trial.id() + " 가 제안되어야 한다");
		}
	}

	@Test
	void fire_자동완성이_자리_여섯을_전부_제안한다() {
		List<Suggestion> suggestions =
				suggest(trialTest().getChild("fire").getChild(DragonTrialCommand.ARG_TRIGGER));

		assertEquals(TrialCatalog.Trigger.values().length, suggestions.size());
		for (TrialCatalog.Trigger trigger : TrialCatalog.Trigger.values()) {
			assertTrue(texts(suggestions).contains(trigger.name().toLowerCase(Locale.ROOT)),
					trigger + " 가 제안되어야 한다");
		}
	}

	/**
	 * 기계 이름 옆에 <b>사람이 읽는 이름</b>이 붙는다.
	 *
	 * <p>{@code first_crystal} 만 늘어놓으면 그것이 「첫 크리스탈」인지 알 수 없어 목록을 따로
	 * 띄워 대조하게 된다. 그러면 자동 완성이 있으나 마나다.
	 */
	@Test
	void 자동완성이_사람이_읽는_이름을_함께_보여_준다() {
		for (Suggestion suggestion
				: suggest(trialTest().getChild("fire").getChild(DragonTrialCommand.ARG_TRIGGER))) {
			assertNotNull(suggestion.getTooltip(), suggestion.getText() + " 에 설명이 있어야 한다");
			TrialCatalog.Trigger trigger =
					TrialCatalog.Trigger.valueOf(suggestion.getText().toUpperCase(Locale.ROOT));
			assertEquals(trigger.label(), suggestion.getTooltip().getString());
		}

		for (Suggestion suggestion
				: suggest(trialTest().getChild("give").getChild(DragonTrialCommand.ARG_TRIAL_ID))) {
			assertNotNull(suggestion.getTooltip(), suggestion.getText() + " 에 설명이 있어야 한다");
			assertEquals(TrialCatalog.byId(suggestion.getText()).name(),
					suggestion.getTooltip().getString());
		}
	}

	// ------------------------------------------------------------------ 잠금

	/**
	 * 설정이 꺼져 있으면 카드를 주무르는 가지가 <b>트리에 아예 없다</b>.
	 *
	 * <p>권한만으로 막으면 운영자 계정 하나로 노는 서버에서는 사실상 잠금이 없는 것과 같다.
	 */
	@Test
	void 설정이_꺼져_있으면_카드_가지가_아예_없다() {
		CommandNode<CommandSourceStack> node = trialTest(false);

		assertNull(node.getChild("give"));
		assertNull(node.getChild("fire"));
		assertNull(node.getChild("clear"));
	}

	@Test
	void 설정을_못_읽었으면_꺼진_것으로_본다() {
		SharedFateMod.config = null;

		assertFalse(DragonTrialCommand.enabled());
	}

	@Test
	void 설정_기본값은_꺼짐이다() {
		assertFalse(new SharedFateConfig().perkTestCommands,
				"실제로 노는 서버에 조용히 켜져 있으면 안 된다");
	}

	/**
	 * 두 번째 잠금인 권한 요구가 가지에 걸려 있다.
	 *
	 * <p>{@code PerkTestCommandTest} 와 같은 방식이다 — 브리가디어의 기본 요구조건은 source 를
	 * 아예 보지 않는 {@code s -> true} 라 {@code null} 을 줘도 통과한다. 여기 걸린 요구조건은
	 * source 의 권한을 읽으므로 터진다. 그 차이로 잠금이 실제로 걸렸는지를 가른다.
	 */
	@Test
	void 권한_잠금이_걸려_있다() {
		CommandNode<CommandSourceStack> node = trialTest();

		assertThrows(NullPointerException.class, () -> node.getRequirement().test(null),
				"권한을 안 보는 요구조건이면 잠금이 하나뿐이다");
	}
}
