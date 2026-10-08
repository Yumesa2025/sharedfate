package com.sharedfate.team;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sharedfate.perk.PendingOffer;
import com.sharedfate.perk.PerkMilestones;
import net.minecraft.core.UUIDUtil;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.inventory.PlayerEnderChestContainer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public class TeamState {
	public static final int MAIN_SIZE = 36;
	public static final int EXTRA_SIZE = 27;
	public static final int ENDER_SIZE = 27;

	public final SharedItemList mainItems;
	public final SharedItemList extraItems;
	public final PlayerEnderChestContainer enderContainer;
	public final SharedEquipmentStore equipment;
	public final List<ItemStack> overflowItems;

	/**
	 * 지금 실제로 걸려 있는 팀 공유 최대 체력.
	 *
	 * <p>공유 체력 풀의 상한이자 {@code MaxHealthAttribute} 가 팀원 전원의 {@code max_health}
	 * 속성을 맞추는 목표값이다. <b>증강 보너스가 이미 더해진 결과</b>라 직접 정하는 값이 아니다.
	 * 정하는 값은 {@link #baseMaxHealth} 쪽이고, 둘을 이어 주는 계산은
	 * {@link com.sharedfate.perk.PerkHealthRules#effectiveMaxHealth} 한 곳에만 있다.
	 */
	public float maxHealth;
	/**
	 * 팀이 정한 기본 최대 체력. {@code /shareteam health} 가 정하고 그 밖에는 아무도 바꾸지 않는다.
	 *
	 * <p>{@link #maxHealth} 와 나눠 둔 이유는 하나뿐이다. 최대 체력을 올리는 증강의 보너스를
	 * {@link #maxHealth} 에 직접 더하면 접속·부활·주기 점검마다 또 더해져 끝없이 불어난다.
	 * "원래 얼마였는가"를 여기에 남겨 두면 몇 번을 다시 계산해도 답이 {@code 기본값 + 보너스} 로
	 * 같고, 증강을 잃었을 때 명령으로 정해 둔 값이 그대로 돌아온다.
	 *
	 * <p>기존 월드에는 이 항목이 없다. 그때는 저장된 {@link #maxHealth} 가 곧 팀이 정한 값이므로
	 * 생성자가 둘을 같게 맞춰 두고, 저장에 값이 있을 때만 덮어쓴다.
	 */
	public float baseMaxHealth;
	public float health;
	public float absorption;
	public int foodLevel;
	public float saturation;
	public int xpLevel;
	public float xpProgress;
	public int totalExperience;
	public final List<MobEffectInstance> effects;
	public int positionSwapIntervalTicks;
	public int positionSwapRemainingTicks;

	/** 증강 사용 여부. 팀을 만들 때 정하고 그 뒤로는 바꾸는 경로가 없다. */
	public boolean perksEnabled;
	/** 피격 알림 표시 여부. 팀을 만들 때 정하고 그 뒤로는 바꾸는 경로가 없다. */
	public boolean damageAlertEnabled;
	/** 사망 알림 표시 여부. 팀을 만들 때 정하고 그 뒤로는 바꾸는 경로가 없다. */
	public boolean deathAlertEnabled;
	/**
	 * 시간이 흐를수록 적대적 몹이 강해지는가. 팀을 만들 때 정하고 그 뒤로는 바꾸는 경로가 없다.
	 * 실제 계산은 {@code com.sharedfate.sync.DifficultyEscalation} 이 한다.
	 */
	public boolean difficultyEscalationEnabled;
	/**
	 * 엔더 드래곤 <b>시련</b>을 쓰는 팀인가. 팀을 만들 때 정하고 그 뒤로는 바꾸는 경로가 없다.
	 *
	 * <p>거짓이면 최종 보스가 <b>바닐라 엔더 드래곤전</b>이다. 시련 카드도 고정 시련도 기본
	 * 패시브({@code DragonPassives})도 돌지 않고, 드래곤 체력은 바닐라 200 그대로다 —
	 * {@code SharedFateConfig.dragonHealthPerMember} 는 이 팀에게 <b>아예 읽히지 않는다.</b>
	 * 가르는 자리는 {@code com.sharedfate.sync.DragonTrialManager} 한 곳이다.
	 *
	 * <p><b>기본값이 거짓이다.</b> 저장에 이 항목이 없는 예전 월드의 팀도 거짓으로 읽힌다 —
	 * 시련은 {@code feature/dragon-trials} 밖으로 나간 적이 없으므로 「예전 월드의 팀은 시련을
	 * 쓰고 있었다」고 볼 근거가 없고, 기본값과 다르게 읽으면 같은 팀이 켜 준 적도 없는 설정을
	 * 켠 채로 되살아난다.
	 */
	public boolean dragonTrialsEnabled;
	/**
	 * 이 회차에서 <b>팀원이 한 명이라도 접속해 있던</b> 시간(틱). 난이도 상승 단계를 이걸로 센다.
	 *
	 * <p>월드 저장에 들어가므로 서버를 껐다 켜도 이어지고, <b>서버가 꺼져 있던 시간은 세지
	 * 않는다.</b> 아무도 접속하지 않은 시간도 마찬가지다. 회차가 넘어가면 팀 상태를 새로 만들어
	 * 0 에서 다시 시작한다 — 「회차가 시작된 뒤 흐른 시간」이 이 값의 뜻이다.
	 */
	public int difficultyElapsedTicks;
	/**
	 * 이 팀의 회차가 <b>실제로 시작되었는가.</b> 리더가 「게임 시작」을 누르면 참이 된다.
	 *
	 * <p><b>사람이 누르는 것은 1회차 전 한 번뿐이다.</b> 2회차부터는 회차 번호만 보고
	 * {@code GameStartManager.syncRunStart} 가 저절로 켠다 — 서버가 새 월드로 떴든 이미
	 * 굴러가던 월드로 떴든 상관없다. 월드 초기화를 끈 서버에서는 {@link #resetAfterDeath} 가
	 * 이 값을 건드리지 않아 그대로 이어진다.
	 *
	 * <p>회차의 시작점은 팀을 만든 순간이 아니라 <b>이것이 참이 되는 순간</b>이다. 팀을 만들고
	 * 팀원을 부르고 설정을 확인하는 동안에도 게임은 돌아가는데, 그 시간까지 회차에 넣으면
	 * 난이도 상승도 위치 교환도 증강 구간도 아무도 시작하지 않은 회차에서 굴러간다.
	 * 시작 전에 멈춰 있는 것들은 {@code com.sharedfate.sync.GameStartManager} 에 모아 뒀다.
	 *
	 * <p><b>저장에 이 항목이 없으면 「이미 시작했다」로 읽는다.</b> 이 기능이 생기기 전의 월드에는
	 * 항목이 없는데, 그 월드의 팀은 실제로 회차를 진행하던 중이다. 거짓으로 읽으면 이미 몇 시간을
	 * 플레이한 팀이 갑자기 「시작 대기」가 되고, 그 상태에서 「게임 시작」을 누르면 아이템이 전부
	 * 사라진다. 반대로 새로 만든 팀은 {@link #fresh} 가 거짓으로 두고 저장에도 그대로 적히므로
	 * 이 기본값에 걸리지 않는다.
	 */
	public boolean runStarted;
	/**
	 * 이 회차에서 증강 시험 명령({@code /shareteam perktest ...})을 한 번이라도 썼는가.
	 *
	 * <p>한 번 켜지면 이 회차가 끝날 때까지 <b>다시 꺼지지 않는다.</b> 증강을 손으로 넣고 뺀
	 * 회차는 정상 회차가 아니고, 그 사실이 나중에 「어떻게 이겼더라」를 따질 때 남아 있어야
	 * 한다. 회차가 넘어가면 팀 상태를 새로 만들면서 저절로 지워진다.
	 */
	public boolean perkTestUsed;
	/**
	 * 이 팀이 <b>한 회차에</b> 쓸 수 있는 증강 다시 뽑기 횟수. 팀을 만들 때 정하고 그 뒤로는
	 * 바꾸는 경로가 없다.
	 *
	 * <p>{@link #rerollsRemaining} 과 나눠 둔 이유는 {@link #baseMaxHealth} 와 {@link #maxHealth}
	 * 를 나눈 것과 같다. "원래 몇 번이었는가"가 남아 있어야 회차가 넘어갈 때 그 값으로 다시
	 * 채울 수 있다. 회차를 넘겨 이어 가는 일은 {@code TeamRosterStore} 와
	 * {@link TeamManager#restoreFreshRoster} 가 맡는다.
	 */
	public int rerollAllowance;
	/**
	 * <b>이번 회차에</b> 아직 남은 다시 뽑기 횟수. 0 이면 더 못 쓴다.
	 *
	 * <p>회차마다 다시 차는 값이라 월드 저장에만 들어가고 팀 명단 파일에는 들어가지 않는다.
	 * 전멸로 팀 상태를 새로 만들면 {@link #rerollAllowance} 로 가득 찬 채 시작한다.
	 */
	public int rerollsRemaining;
	/**
	 * <b>이번 회차에</b> 세트 보상으로 얹힌 다시 뽑기 횟수. 기본은 0 이다.
	 *
	 * <p>세트 「도박 2단계 — 한 판 더」가 5 를, 「도박 3단계 — 어차피 프리즘」이 3 을 얹는다.
	 * 단계는 <b>누적</b>이라 도박 증강 셋을 모은 팀의 몫은 8 이다. 다른 세트 보상과 달리
	 * <b>저장된다.</b>
	 * 까닭은 {@link com.sharedfate.perk.effect.ExtraRerollsEffect} 에 적어 뒀다 — 다시 뽑기
	 * 횟수만은 「지금 몇 번 남았는가」를 보유 증강에서 다시 계산할 수 없는 소비값이라, 실제로
	 * {@link #rerollsRemaining} 을 늘려야 하기 때문이다.
	 *
	 * <h2>이 필드가 없으면 5회가 저장 왕복에서 사라진다</h2>
	 * <p>{@link #sanitize} 와 {@link #applyRerollSection} 두 곳이 「이번 회차에 남은 횟수가
	 * 회차당 허용치보다 클 수는 없다」로 잘라 왔다. {@link #rerollsRemaining} 만 5 올려 두면
	 * 서버를 껐다 켜는 순간 그 클램프가 기본 3 으로 되돌린다. 그래서 <b>클램프가 「세트로 얻은
	 * 몫」을 알아야 한다</b> — 두 클램프의 상한이 {@link #rerollLimit()} 로 바뀐 것이 그것이고,
	 * 이 필드가 저장에 함께 들어가는 것이 그 상한을 다음 접속까지 살려 두는 유일한 길이다.
	 *
	 * <p>값을 직접 대입하지 말고 {@link #syncRerollSetBonus(int)} 를 쓴다. 늘어난 몫을
	 * {@link #rerollsRemaining} 에 더하는 일과
	 * {@link TeamCreationSettings#MAX_SET_REROLL_BONUS} 로 접는 일이 거기 함께 들어 있다.
	 *
	 * <h2>회차가 넘어가면 저절로 사라진다</h2>
	 * <p>회차를 넘기는 두 길이 모두 이 값을 남기지 않는다. 전멸로 팀 상태를 새로 만드는 길
	 * ({@link TeamManager#restoreFreshRoster} → {@link #fresh})은 0 에서 시작하고, 회차 시작
	 * 자리({@code GameStartManager} 의 회차 값 초기화)는 {@link #rerollsRemaining} 을 허용치로
	 * 되돌려 세트로 받은 몫을 통째로 버린다. 「이번 회차만」이라는 약속이 그렇게 지켜진다.
	 */
	public int rerollSetBonus;
	/** 마지막으로 처리한 레벨 구간 (0, 3, 6, …, 36). */
	public int lastPerkMilestone;
	/**
	 * <b>이번 회차에 확률로 추가로 나온</b> 프리즘 라운드의 수.
	 *
	 * <p>{@code PerkDraft} 의 확률표가 이 수를 보고 줄을 고른다 — 나올수록 프리즘 확률이
	 * 내려가고 {@link com.sharedfate.perk.PerkDraft#MAX_EXTRA_PRISM} 에 닿으면 0 이 된다.
	 *
	 * <p><b>15 구간의 고정 프리즘은 여기 세지 않는다.</b> 고정은 확률과 아무 상관 없이 언제나 한
	 * 번 나오는 것이라, 그것까지 세면 확률로 얻을 수 있는 프리즘가 하나 줄어든다. 폴백으로
	 * 프리즘 후보가 섞여 나온 라운드도 세지 않는다 — 그건 실버·골드 라운드인데 그 등급에 남은
	 * 후보가 없었을 뿐이다. 세는 것은 <b>등급 추첨이 실제로 프리즘를 뽑은 라운드</b>뿐이다.
	 *
	 * <p>회차마다 0 에서 다시 세는 값이라 {@link #rerollsRemaining} 이나
	 * {@link #difficultyElapsedTicks} 와 같은 결이다. 월드 저장에만 들어가고 팀 명단 파일에는
	 * 들어가지 않으며, 회차가 넘어가면 팀 상태를 통째로 새로 만들거나
	 * ({@link TeamManager#restoreFreshRoster}) 회차를 시작하는 자리
	 * ({@code GameStartManager} 의 회차 값 초기화)가 0 으로 되돌린다.
	 */
	public int extraPrismRounds;
	/**
	 * 「보급」이 켜진 오버월드 게임 시간. 0 이면 아직 모른다.
	 *
	 * <p>보급 주기의 경계가 이 자리부터 주기마다다. <b>저장하지 않으면 서버를 켤 때마다 기준이
	 * 켠 시각으로 되감겨, 주기보다 자주 재시작하는 서버에서는 보급이 한 번도 오지 않는다.</b>
	 * 게임 시간은 월드 저장에 이어지므로 되살린 값으로 계산해도 결과가 달라지지 않는다.
	 */
	public long supplyAnchorTick;
	/** 팀이 보유한 증강의 id. 중첩이 없으므로 같은 id 가 두 번 들어가지 않는다. */
	public final List<String> ownedPerks = new ArrayList<>();
	/**
	 * 증강 id → <b>그 증강을 고른 사람</b>의 UUID.
	 *
	 * <p>지금은 {@code holder} 의 {@code fixed_to_owner} 하나만 쓴다. 그 증강은 고른 사람이 회차
	 * 내내 보유자여야 하는데, 보유자 자체는 {@code PerkHolderManager} 의 런타임 메모리에만 있어
	 * 서버가 다시 뜨면 사라진다. 「누가 골랐는가」까지 함께 사라지면 재시작 뒤에는 왕을 다시
	 * 알아낼 방법이 없으므로, 이 사실만은 월드 저장에 남긴다.
	 *
	 * <p>보유자와 달리 이 값은 회차 안에서 <b>절대 바뀌지 않는</b> 기록이다. 그래서 저장해도
	 * 「지금 상태」와 어긋날 일이 없다.
	 *
	 * <p>자동 선택(시간 초과)으로 들어온 증강은 선택자로 지정돼 있던 사람이 주인이 되고,
	 * 「숨은 재능」처럼 다른 증강이 덤으로 준 증강은 고른 사람을 알 수 없어 여기 들어오지 않는다.
	 * 그때의 대비는 {@code PerkHolderManager} 에 적어 뒀다.
	 */
	public final Map<String, UUID> perkOwners = new HashMap<>();
	/** 아직 고르지 않은 선택권. 구간 순서대로 쌓이며 여러 개일 수 있다. */
	public final List<PendingOffer> pending = new ArrayList<>();
	/**
	 * 「유산」처럼 증강을 고른 순간 몰수했다가, 팀이 전멸하면 다음 회차 시작 인벤토리로
	 * 돌려주기로 예약된 아이템들. {@link #resetAfterDeath}에서도 일부러 비우지 않는다 —
	 * 전멸을 넘겨야 뜻이 있는 값이고, 실제로 넘기는 일은 {@code TeamRosterStore}와
	 * {@code TeamManager#restoreFreshRoster}가 맡는다.
	 */
	public final List<ItemStack> legacyGear = new ArrayList<>();

	public TeamState(SharedItemList mainItems, SharedItemList extraItems,
			PlayerEnderChestContainer enderContainer,
			SharedEquipmentStore equipment, List<ItemStack> overflowItems,
			float maxHealth, float health, float absorption, int foodLevel, float saturation,
			int xpLevel, float xpProgress, int totalExperience, List<MobEffectInstance> effects,
			int positionSwapIntervalTicks, int positionSwapRemainingTicks) {
		this.mainItems = mainItems;
		this.extraItems = extraItems;
		this.enderContainer = enderContainer;
		this.equipment = equipment;
		this.overflowItems = new ArrayList<>();
		overflowItems.stream().filter(stack -> !stack.isEmpty()).forEach(this.overflowItems::add);
		this.maxHealth = maxHealth;
		// 증강 보너스가 붙기 전이므로 기본값은 지금 상한과 같다. 저장에서 읽어 온 경우에만
		// TeamState.CODEC 이 뒤에서 따로 덮어쓴다.
		this.baseMaxHealth = maxHealth;
		this.health = health;
		this.absorption = absorption;
		this.foodLevel = foodLevel;
		this.saturation = saturation;
		this.xpLevel = xpLevel;
		this.xpProgress = xpProgress;
		this.totalExperience = totalExperience;
		this.effects = new ArrayList<>();
		effects.forEach(effect -> this.effects.add(new MobEffectInstance(effect)));
		this.positionSwapIntervalTicks = positionSwapIntervalTicks;
		this.positionSwapRemainingTicks = positionSwapRemainingTicks;
		// 다시 뽑기는 팀을 만들 때 정하지만, 그 길을 거치지 않고 만들어진 상태(기존 월드·시험)도
		// 기본값으로 굴러가야 한다. 저장에서 읽어 온 경우에만 CODEC 이 뒤에서 덮어쓴다.
		this.rerollAllowance = TeamCreationSettings.DEFAULT_REROLL_COUNT;
		this.rerollsRemaining = TeamCreationSettings.DEFAULT_REROLL_COUNT;
		// 세트로 얻은 몫은 언제나 0 에서 시작한다. 새 회차의 팀은 아직 아무 세트도 못 모았다.
		this.rerollSetBonus = 0;
		// 새로 만들어지는 팀 상태는 언제나 「시작 대기」다. 저장에서 읽어 온 경우에만 CODEC 이
		// 뒤에서 덮어쓴다. 전멸 뒤 새 월드에 명단을 되살리는 길도 이 생성자를 지나지만, 그쪽은
		// 바로 뒤에서 GameStartManager.syncRunStart 가 회차 번호를 보고 회차를 켠다 — 사람이
		// 「게임 시작」을 누르는 것은 1회차 전 한 번뿐이다.
		this.runStarted = false;
	}

	public static TeamState fresh(float maxHealth) {
		return new TeamState(
				SharedItemList.ofSize(MAIN_SIZE),
				SharedItemList.ofSize(EXTRA_SIZE),
				new PlayerEnderChestContainer(),
				new SharedEquipmentStore(),
				List.of(),
				maxHealth, maxHealth, 0.0F, 20, 5.0F, 0, 0.0F, 0, List.of(),
				0, 0
		);
	}

	private static final Codec<PlayerEnderChestContainer> ENDER_CODEC =
			ItemStack.OPTIONAL_CODEC.listOf().xmap(
					stacks -> {
						PlayerEnderChestContainer container = new PlayerEnderChestContainer();
						for (int i = 0; i < Math.min(ENDER_SIZE, stacks.size()); i++) {
							container.setItem(i, stacks.get(i));
						}
						return container;
					},
					container -> List.copyOf(container.getItems())
			);

	/**
	 * 전멸 뒤 팀 상태를 되돌린다.
	 *
	 * <p>{@link #baseMaxHealth} 는 일부러 손대지 않는다. 여기서는 보유 증강이 그대로 남아 있어
	 * 상한도 그대로여야 하고, 회차 자체가 끝나 증강을 잃는 경로는 {@link #fresh} 로 팀 상태를
	 * 통째로 새로 만들기 때문에 이 자리를 지나지 않는다.
	 *
	 * <p>{@link #runStarted} 는 <b>일부러 건드리지 않는다.</b> 「게임 시작」을 누르는 것은 1회차
	 * 전 한 번뿐이고, 그 뒤로는 전멸해도 다음 회차가 저절로 이어진다. 월드를 초기화하는
	 * 서버에서는 팀 상태가 통째로 새로 만들어지고
	 * {@code GameStartManager.syncRunStart} 가 새 회차를 켜므로 이 자리의 값이 남지 않는다.
	 * 월드 초기화를 끈 서버({@code resetWorldOnTeamDeath=false})에서는 같은 월드에서 그대로
	 * 이어 가야 하므로 여기서 내려 버리면 <b>사람이 다시 단추를 눌러야 하고, 그 단추가 방금
	 * 살아남은 것들까지 지운다.</b>
	 */
	public void resetAfterDeath(float maxHealth, boolean keepExperience) {
		this.maxHealth = sanitizeMaximum(maxHealth, 20.0F);
		health = this.maxHealth;
		absorption = 0.0F;
		foodLevel = 20;
		saturation = 5.0F;
		if (!keepExperience) {
			xpLevel = 0;
			xpProgress = 0.0F;
			totalExperience = 0;
		}
	}

	public void sanitize(float maxHealth) {
		float safeMaximum = sanitizeMaximum(this.maxHealth, maxHealth);
		this.maxHealth = safeMaximum;
		// 기본값이 비어 있거나 망가졌으면 지금 상한을 그대로 쓴다. 증강이 없는 팀에서는 둘이
		// 어차피 같은 값이고, 기존 월드를 열 때도 이 길로 자연스럽게 채워진다.
		this.baseMaxHealth = sanitizeMaximum(this.baseMaxHealth, safeMaximum);
		health = Float.isFinite(health) ? Math.max(0.0F, Math.min(safeMaximum, health)) : safeMaximum;
		absorption = Float.isFinite(absorption) ? Math.max(0.0F, Math.min(1024.0F, absorption)) : 0.0F;
		foodLevel = Math.max(0, Math.min(20, foodLevel));
		saturation = Float.isFinite(saturation)
				? Math.max(0.0F, Math.min(foodLevel, saturation)) : 0.0F;
		totalExperience = Math.max(0, totalExperience);
		xpLevel = Math.max(0, xpLevel);
		xpProgress = Float.isFinite(xpProgress)
				? Math.max(0.0F, Math.min(1.0F, xpProgress)) : 0.0F;
		overflowItems.removeIf(ItemStack::isEmpty);
		legacyGear.removeIf(ItemStack::isEmpty);
		// 상한은 DifficultyEscalation 이 자기 계산에서 다시 자른다. 여기서는 음수만 막는다.
		difficultyElapsedTicks = Math.max(0, difficultyElapsedTicks);
		// 이번 회차에 남은 횟수가 「회차당 허용치 + 세트로 얻은 몫」보다 클 수는 없다. 손상된
		// 저장을 여기서 접는다. 허용치와 세트 몫은 서로 독립이지만, 남은 횟수를 접으려면 그 둘이
		// 먼저 접혀 있어야 한다.
		rerollAllowance = TeamCreationSettings.sanitizeRerollCount(rerollAllowance);
		rerollSetBonus = sanitizeRerollSetBonus(rerollSetBonus);
		rerollsRemaining = Math.max(0, Math.min(rerollLimit(), rerollsRemaining));
		if (positionSwapIntervalTicks < 0 || positionSwapIntervalTicks > PositionSwapLimits.MAX_INTERVAL_TICKS) {
			positionSwapIntervalTicks = 0;
		}
		if (positionSwapIntervalTicks == 0) {
			positionSwapRemainingTicks = 0;
		} else {
			positionSwapRemainingTicks = Math.max(0,
					Math.min(positionSwapIntervalTicks, positionSwapRemainingTicks));
		}
	}

	private static float sanitizeMaximum(float value, float fallback) {
		float safeFallback = Float.isFinite(fallback)
				? Math.max(1.0F, Math.min(1024.0F, fallback)) : 20.0F;
		return Float.isFinite(value) ? Math.max(1.0F, Math.min(1024.0F, value)) : safeFallback;
	}

	public void enablePositionSwap(int minutes) {
		if (minutes < PositionSwapLimits.MIN_MINUTES || minutes > PositionSwapLimits.MAX_MINUTES) {
			throw new IllegalArgumentException("위치 교환 주기는 1~120분이어야 합니다.");
		}
		positionSwapIntervalTicks = minutes * PositionSwapLimits.TICKS_PER_MINUTE;
		positionSwapRemainingTicks = positionSwapIntervalTicks;
	}

	public void disablePositionSwap() {
		positionSwapIntervalTicks = 0;
		positionSwapRemainingTicks = 0;
	}

	public boolean positionSwapEnabled() {
		return positionSwapIntervalTicks > 0;
	}

	public boolean advancePositionSwapTick(boolean enoughOnlineMembers) {
		if (!positionSwapEnabled()) {
			return false;
		}
		if (positionSwapRemainingTicks > 0) {
			positionSwapRemainingTicks--;
		}
		if (positionSwapRemainingTicks > 0) {
			return false;
		}
		if (!enoughOnlineMembers) {
			positionSwapRemainingTicks = PositionSwapLimits.RETRY_TICKS;
			return false;
		}
		positionSwapRemainingTicks = positionSwapIntervalTicks;
		return true;
	}

	public int positionSwapIntervalMinutes() {
		return positionSwapIntervalTicks / PositionSwapLimits.TICKS_PER_MINUTE;
	}

	public static final class PositionSwapLimits {
		public static final int MIN_MINUTES = 1;
		/**
		 * 위치 교환 주기의 상한(분).
		 *
		 * <p>2026-09-09 에 120 에서 30 으로 줄였다. 한 회차가 그렇게 길지 않아 30분을 넘기면
		 * 회차 내내 한 번도 안 바뀌는 것과 다르지 않았고, 굴림 단추로 고르기에도 눈금이 너무
		 * 길었다. <b>저장된 팀이 그보다 큰 값을 들고 있으면 읽을 때 30으로 잘린다</b>
		 * ({@code TeamCreationSettings.sanitize}) — 예전 팀이 죽지 않는다.
		 */
		public static final int MAX_MINUTES = 30;
		public static final int TICKS_PER_MINUTE = 20 * 60;
		public static final int RETRY_TICKS = 20;
		private static final int MAX_INTERVAL_TICKS = MAX_MINUTES * TICKS_PER_MINUTE;

		private PositionSwapLimits() {
		}
	}

	public boolean hasSharedItems() {
		return mainItems.stream().anyMatch(stack -> !stack.isEmpty())
				|| extraItems.stream().anyMatch(stack -> !stack.isEmpty())
				|| !equipment.isEmpty()
				|| overflowItems.stream().anyMatch(stack -> !stack.isEmpty())
				|| !enderContainer.isEmpty();
	}

	/**
	 * 넘침 대기열을 인벤토리로 되돌린다.
	 *
	 * <h2>⚠ 추가 칸은 「열린 만큼만」이다</h2>
	 * <p>{@link #extraItems} 는 언제나 27칸이지만 팀이 연 칸은 그보다 적을 수 있다(기본 18).
	 * 잠긴 칸은 화면 밖에 있어 <b>물건이 들어가면 사라진 것처럼 보이고 꺼낼 수도 없다.</b>
	 * 그래서 {@code ExpandedInventoryContainer.openSlots()} 와 <b>같은 한도</b>를 쓴다.
	 *
	 * <p>이 한도가 없던 시절, 「보급」이 온 팀에서 채팅에는 「보급 받음」이 뜨는데 인벤토리
	 * 어디에도 없고 재접속해야 나타나는 일이 있었다. 덤으로
	 * {@code PerkInventorySlots.occupiedFloor} 가 그 칸을 발견해 <b>짐꾼도 없는 팀에 27칸을
	 * 열어 줬다.</b>
	 *
	 * <p>{@code unlockedFor} 는 「물건이 든 칸까지는 반드시 연다」를 포함하므로, 예전 월드에서
	 * 이미 아래 칸에 있던 물건은 그대로 열린 것으로 세어 계속 쓸 수 있다.
	 */
	public void restoreOverflow(boolean includeExtra) {
		int extraLimit = com.sharedfate.perk.PerkInventorySlots.unlockedFor(this);
		for (var iterator = overflowItems.iterator(); iterator.hasNext();) {
			ItemStack stack = iterator.next();
			insertInto(mainItems, stack, mainItems.size());
			if (includeExtra && !stack.isEmpty()) {
				insertInto(extraItems, stack, extraLimit);
			}
			if (stack.isEmpty()) {
				iterator.remove();
			}
		}
	}

	/**
	 * 같은 아이템에 먼저 합치고, 남으면 빈 칸에 넣는다.
	 *
	 * @param limit 이 칸 번호 <b>앞까지만</b> 쓴다. 잠긴 칸을 건너뛰기 위한 한도다
	 */
	private static void insertInto(SharedItemList items, ItemStack stack, int limit) {
		int end = Math.max(0, Math.min(limit, items.size()));
		for (int slot = 0; slot < end && !stack.isEmpty(); slot++) {
			ItemStack existing = items.get(slot);
			if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, stack)) {
				int moved = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
				if (moved > 0) {
					existing.grow(moved);
					stack.shrink(moved);
				}
			}
		}
		for (int slot = 0; slot < end && !stack.isEmpty(); slot++) {
			if (items.get(slot).isEmpty()) {
				int moved = Math.min(stack.getCount(), stack.getMaxStackSize());
				items.set(slot, stack.copyWithCount(moved));
				stack.shrink(moved);
			}
		}
	}

	/**
	 * 증강 관련 저장 묶음.
	 *
	 * <p>{@link TeamState#CODEC}의 기존 필드가 이미 16개라 {@code RecordCodecBuilder.group}의 인자 상한
	 * (Products.P16)에 걸린다. 그래서 증강 필드는 이 하위 Codec 하나로 묶어
	 * {@code "perks"} 한 항목으로 붙인다.
	 *
	 * <p>모든 항목이 {@code optionalFieldOf}이고 묶음 자체도 선택 항목이라, 증강 필드가 없는
	 * 기존 월드는 {@link #EMPTY}로 읽힌다. 반대로 증강을 쓰지 않는 팀은 저장할 때 이 항목이
	 * 통째로 빠지므로 예전 서버 저장과 형태가 같다.
	 *
	 * <p>{@code owners}는 증강 id → 그 증강을 고른 사람이다({@link TeamState#perkOwners} 참고).
	 * 이 항목을 모르는 예전 저장은 빈 map 으로 읽히고, 그러면 예전과 똑같이 동작한다.
	 */
	public record PerkSection(boolean enabled, int lastMilestone,
			List<String> owned, List<PendingOffer> pending, Map<String, UUID> owners) {
		/** 증강을 쓰지 않는 상태. 기존 월드를 읽을 때의 기본값이다. */
		public static final PerkSection EMPTY =
				new PerkSection(false, 0, List.of(), List.of(), Map.of());

		/**
		 * 보유 증강 하나의 저장 형식.
		 *
		 * <p>중첩 개념이 있던 시절에는 {@code {perkId, count}} 객체로 적었다. 이미 돌아가는
		 * 서버의 월드에 그 형태가 들어 있으므로 <b>읽을 때는 두 형태를 모두 받아들인다.</b>
		 * {@code count} 는 뜻이 사라졌으므로 읽고 버린다. 새로 저장할 때는 id 문자열만 적는다.
		 */
		private static final Codec<String> OWNED_CODEC = Codec.either(
						Codec.STRING, Codec.STRING.fieldOf("perkId").codec())
				.xmap(either -> either.map(Function.identity(), Function.identity()), Either::left);

		/** 증강 id → 고른 사람. 키가 증강 id 라 그대로 문자열 키 map 으로 적는다. */
		private static final Codec<Map<String, UUID>> OWNERS_CODEC =
				Codec.unboundedMap(Codec.STRING, UUIDUtil.STRING_CODEC);

		public static final Codec<PerkSection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("enabled", false).forGetter(PerkSection::enabled),
				Codec.INT.optionalFieldOf("lastMilestone", 0).forGetter(PerkSection::lastMilestone),
				OWNED_CODEC.listOf().optionalFieldOf("owned", List.of())
						.forGetter(PerkSection::owned),
				PendingOffer.CODEC.listOf().optionalFieldOf("pending", List.of())
						.forGetter(PerkSection::pending),
				OWNERS_CODEC.optionalFieldOf("owners", Map.of()).forGetter(PerkSection::owners)
		).apply(instance, PerkSection::new));

		public PerkSection {
			owned = List.copyOf(owned);
			pending = List.copyOf(pending);
			owners = Map.copyOf(owners);
		}
	}

	/**
	 * 알림 설정 저장 묶음.
	 *
	 * <p>{@link PerkSection} 과 같은 이유로 따로 묶는다. {@code TeamState.CODEC} 의 본체는
	 * {@code BASE_CODEC} 이 이미 {@code RecordCodecBuilder.group} 인자 상한(P16)을 다 쓰고
	 * 있어, 새 항목은 바깥쪽에 묶음으로만 붙일 수 있다.
	 *
	 * <p>두 항목 모두 {@code optionalFieldOf} 이고 묶음 자체도 선택 항목이라, 이 항목이 없는
	 * 기존 월드는 {@link #NONE} — 둘 다 꺼짐 — 으로 읽힌다. 알림을 쓰지 않는 팀은 저장할 때
	 * 이 묶음이 통째로 빠지므로 저장 형태가 예전과 같다.
	 */
	public record AlertSection(boolean damage, boolean death) {
		/** 둘 다 꺼진 상태. 기본값이자 기존 월드를 읽을 때의 값이다. */
		public static final AlertSection NONE = new AlertSection(false, false);

		public static final Codec<AlertSection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("damage", false).forGetter(AlertSection::damage),
				Codec.BOOL.optionalFieldOf("death", false).forGetter(AlertSection::death)
		).apply(instance, AlertSection::new));
	}

	/**
	 * 난이도 상승 저장 묶음.
	 *
	 * <p>{@link AlertSection} 과 같은 이유로 따로 묶는다 — {@code BASE_CODEC} 이 이미
	 * {@code RecordCodecBuilder.group} 인자 상한(P16)을 다 써서 새 항목은 바깥쪽에만 붙는다.
	 *
	 * <p>두 항목 모두 {@code optionalFieldOf} 이고 묶음 자체도 선택 항목이라, 이 기능을 끈
	 * 팀은 저장할 때 묶음이 통째로 빠져 예전과 형태가 같고, 이 항목이 없는 기존 월드는
	 * {@link #OFF} 로 읽힌다.
	 *
	 * @param escalation   시간이 흐를수록 몹이 강해지는가
	 * @param elapsedTicks 이 회차에서 팀원이 접속해 있던 시간(틱)
	 */
	public record DifficultySection(boolean escalation, int elapsedTicks) {
		/** 꺼져 있고 아직 아무 시간도 세지 않은 상태. 기존 월드를 읽을 때의 값이다. */
		public static final DifficultySection OFF = new DifficultySection(false, 0);

		public static final Codec<DifficultySection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.BOOL.optionalFieldOf("escalation", false).forGetter(DifficultySection::escalation),
				Codec.INT.optionalFieldOf("elapsedTicks", 0).forGetter(DifficultySection::elapsedTicks)
		).apply(instance, DifficultySection::new));
	}

	/**
	 * 증강 다시 뽑기 저장 묶음.
	 *
	 * <p>{@link DifficultySection} 과 같은 이유로 바깥쪽에 따로 붙인다. 다만 기본값이 「꺼짐」이
	 * 아니라 {@linkplain TeamCreationSettings#DEFAULT_REROLL_COUNT 회차당 3회}라는 점이 다르다.
	 * 기본값 그대로인 팀은 저장할 때 이 묶음이 통째로 빠지고, 이 항목이 없는 기존 월드도
	 * {@link #DEFAULT} — 3회 전부 남아 있는 상태 — 로 읽힌다.
	 *
	 * <p>{@code setBonus} 는 나중에 붙은 항목이라 <b>없으면 0</b> 이다. 세트를 모으지 않은 팀과
	 * 이 항목을 모르는 예전 월드가 같은 값으로 열리고, 0 이면 저장에도 적히지 않아 형태가
	 * 예전과 같다. 이 항목이 저장을 왕복해야 하는 까닭은 {@link TeamState#rerollSetBonus} 에
	 * 적어 뒀다 — 이것이 없으면 세트로 받은 5회가 서버를 껐다 켜는 순간 사라진다.
	 *
	 * @param allowance 이 팀이 회차당 쓸 수 있는 횟수
	 * @param remaining 이번 회차에 아직 남은 횟수
	 * @param setBonus  이번 회차에 세트 보상으로 얹힌 횟수
	 */
	public record RerollSection(int allowance, int remaining, int setBonus) {
		/** 팀이 아무것도 안 정했을 때의 값. 기존 월드를 읽을 때도 이 값이다. */
		public static final RerollSection DEFAULT = new RerollSection(
				TeamCreationSettings.DEFAULT_REROLL_COUNT,
				TeamCreationSettings.DEFAULT_REROLL_COUNT,
				0);

		public static final Codec<RerollSection> CODEC = RecordCodecBuilder.create(instance -> instance.group(
				Codec.INT.optionalFieldOf("allowance", TeamCreationSettings.DEFAULT_REROLL_COUNT)
						.forGetter(RerollSection::allowance),
				Codec.INT.optionalFieldOf("remaining", TeamCreationSettings.DEFAULT_REROLL_COUNT)
						.forGetter(RerollSection::remaining),
				Codec.INT.optionalFieldOf("setBonus", 0).forGetter(RerollSection::setBonus)
		).apply(instance, RerollSection::new));
	}

	/** 현재 다시 뽑기 상태를 저장용 묶음으로 뽑아낸다. */
	public RerollSection rerollSection() {
		return new RerollSection(rerollAllowance, rerollsRemaining, rerollSetBonus);
	}

	/**
	 * 저장에서 읽은 다시 뽑기 묶음을 이 상태에 채운다.
	 *
	 * <p>{@link #sanitize} 와 같은 순서로 접는다 — 허용치, 세트 몫, 그다음 남은 횟수다.
	 * <b>남은 횟수의 상한은 허용치가 아니라 {@link #rerollLimit()} 다.</b> 여기서 허용치로
	 * 자르면 세트로 받은 5회가 다음 접속 때마다 사라진다.
	 */
	public void applyRerollSection(RerollSection section) {
		rerollAllowance = TeamCreationSettings.sanitizeRerollCount(section.allowance());
		rerollSetBonus = sanitizeRerollSetBonus(section.setBonus());
		rerollsRemaining = Math.max(0, Math.min(rerollLimit(), section.remaining()));
	}

	/**
	 * 이번 회차에 남은 횟수가 가질 수 있는 최댓값. {@code 회차당 허용치 + 세트로 얻은 몫} 이다.
	 *
	 * <p>{@link #rerollSetBonus} 가 0 인 보통의 팀에서는 회차당 허용치와 똑같은 값이다.
	 */
	public int rerollLimit() {
		return rerollAllowance + rerollSetBonus;
	}

	/**
	 * 세트로 얻은 몫을 지금 있어야 할 값으로 맞춘다. 실제로 바뀌었으면 참.
	 *
	 * <p><b>「더한다」가 아니라 「맞춘다」인 것이 핵심이다.</b> 이 메서드는 몇 번을 불러도
	 * 결과가 같아야 한다 — 부르는 자리({@code PerkGrantChain.run})가 증강을 얻을 때마다 지나가고,
	 * 「이미 받아 뒀는가」를 따로 기억해 두는 곳이 없기 때문이다. 지금 몫과 목표가 같으면 아무
	 * 일도 하지 않는 것이 그 답이다.
	 *
	 * <ul>
	 *   <li><b>몫이 늘면</b> 늘어난 만큼만 {@link #rerollsRemaining} 에 더한다. 목표값으로
	 *       덮어쓰지 않는다 — 이미 몇 번 써 버린 팀의 소비 기록이 지워지면 안 된다.</li>
	 *   <li><b>몫이 줄면</b>(세트가 풀리면) 남은 횟수를 더하지도 빼지도 않고 새 상한으로 다시
	 *       자르기만 한다. 아직 안 쓴 5회는 그 자리에서 사라지고, 이미 쓴 만큼은 그대로다.</li>
	 * </ul>
	 *
	 * <p>몫은 {@code 0 ~ }{@link TeamCreationSettings#MAX_SET_REROLL_BONUS} 로 접힌다.
	 * <b>회차당 허용치와 무관하다</b> — 세트 몫은 허용치 위에 얹힌다.
	 *
	 * @param bonus 세트와 보유 증강이 요구하는 몫.
	 *              보통 {@code ExtraRerollsEffect.bonusOf(this)} 를 그대로 넘긴다
	 */
	public boolean syncRerollSetBonus(int bonus) {
		int wanted = sanitizeRerollSetBonus(bonus);
		if (wanted == rerollSetBonus) {
			return false;
		}
		int gained = wanted - rerollSetBonus;
		rerollSetBonus = wanted;
		if (gained > 0) {
			rerollsRemaining += gained;
		}
		rerollsRemaining = Math.max(0, Math.min(rerollLimit(), rerollsRemaining));
		return true;
	}

	/**
	 * 세트로 얻은 몫을 허용 범위로 접는다. 접는 규칙은 반드시 이 한 곳에만 둔다.
	 *
	 * <p>상한은 {@link TeamCreationSettings#MAX_SET_REROLL_BONUS} 다. <b>회차당 허용치를 빼지
	 * 않는다</b> — 세트 몫은 허용치 위에 얹히므로 한 회차의 최대는 {@code 허용치 + 세트 몫} 이다.
	 *
	 * <p>0.29.1-dev 전에는 {@code MAX_REROLL_COUNT − 허용치} 로 접었다. 그래서 굴림을 최대로
	 * 잡은 팀은 남는 자리가 0 이라 <b>도박 세트가 통째로 죽었고, 아무 말도 없었다.</b> 실제로
	 * 당한 사고다. 허용치는 「기본으로 몇 번 주느냐」이지 「세트로 몇 번까지 받을 수 있느냐」가
	 * 아니다 — 두 물음을 한 상수로 답한 것이 잘못이었다.
	 */
	private int sanitizeRerollSetBonus(int bonus) {
		return Math.max(0, Math.min(TeamCreationSettings.MAX_SET_REROLL_BONUS, bonus));
	}

	/** 현재 난이도 상승 상태를 저장용 묶음으로 뽑아낸다. */
	public DifficultySection difficultySection() {
		return new DifficultySection(difficultyEscalationEnabled, difficultyElapsedTicks);
	}

	/** 저장에서 읽은 난이도 묶음을 이 상태에 채운다. */
	public void applyDifficultySection(DifficultySection section) {
		difficultyEscalationEnabled = section.escalation();
		difficultyElapsedTicks = Math.max(0, section.elapsedTicks());
	}

	/** 현재 알림 설정을 저장용 묶음으로 뽑아낸다. */
	public AlertSection alertSection() {
		return new AlertSection(damageAlertEnabled, deathAlertEnabled);
	}

	/** 저장에서 읽은 알림 묶음을 이 상태에 채운다. */
	public void applyAlertSection(AlertSection section) {
		damageAlertEnabled = section.damage();
		deathAlertEnabled = section.death();
	}

	/** 현재 증강 상태를 저장용 묶음으로 뽑아낸다. */
	public PerkSection perkSection() {
		return new PerkSection(perksEnabled, lastPerkMilestone, ownedPerks, pending, perkOwners);
	}

	/** 저장에서 읽은 증강 묶음을 이 상태에 채운다. */
	public void applyPerkSection(PerkSection section) {
		perksEnabled = section.enabled();
		lastPerkMilestone = section.lastMilestone();
		ownedPerks.clear();
		ownedPerks.addAll(section.owned());
		pending.clear();
		pending.addAll(section.pending());
		perkOwners.clear();
		perkOwners.putAll(section.owners());
		sanitizePerks();
	}

	/**
	 * 증강 필드를 안전한 범위로 되돌린다.
	 *
	 * <p>증강 시스템의 손상된 저장이 본 게임을 막으면 안 되므로 예외를 던지지 않고 조용히 고친다.
	 */
	public void sanitizePerks() {
		lastPerkMilestone = PerkMilestones.clampMilestone(lastPerkMilestone);
		// 손상된 저장이나 이 항목을 모르는 예전 서버가 적어 둔 값이 표 밖을 짚지 않게 접는다.
		// PerkDraft 도 스스로 한 번 더 접지만, 저장에 남는 값 자체가 범위 안이어야 다음에
		// 열었을 때 「프리즘가 다섯 번 나왔다」 같은 값이 굳어 있지 않다.
		extraPrismRounds = Math.max(0,
				Math.min(com.sharedfate.perk.PerkDraft.MAX_EXTRA_PRISM, extraPrismRounds));
		// 중첩이 없으므로 같은 증강이 두 번 들어 있으면 안 된다. 중첩 시절 저장이나 손상된
		// 저장에서 흘러들어와도 여기서 한 개로 접어 둔다.
		Set<String> seen = new HashSet<>();
		ownedPerks.removeIf(perkId -> perkId == null || perkId.isBlank() || !seen.add(perkId));
		pending.removeIf(offer -> offer == null || offer.optionIds().isEmpty());
		// 가지고 있지도 않은 증강의 주인은 남아 있을 이유가 없다. 회차가 바뀌었는데 기록만
		// 남으면 다음 회차에서 엉뚱한 사람이 보유자가 된다.
		perkOwners.keySet().retainAll(seen);
	}

	private static final MapCodec<TeamState> BASE_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			SharedItemList.codec(MAIN_SIZE).fieldOf("mainItems").forGetter(state -> state.mainItems),
			SharedItemList.codec(EXTRA_SIZE).optionalFieldOf("extraItems")
					.forGetter(state -> Optional.of(state.extraItems)),
			ENDER_CODEC.fieldOf("enderItems").forGetter(state -> state.enderContainer),
			SharedEquipmentStore.CODEC.fieldOf("equipment").forGetter(state -> state.equipment),
			ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("overflowItems")
					.forGetter(state -> Optional.of(state.overflowItems)),
			Codec.FLOAT.optionalFieldOf("maxHealth")
					.forGetter(state -> Optional.of(state.maxHealth)),
			Codec.FLOAT.fieldOf("health").forGetter(state -> state.health),
			Codec.FLOAT.optionalFieldOf("absorption", 0.0F).forGetter(state -> state.absorption),
			Codec.INT.fieldOf("foodLevel").forGetter(state -> state.foodLevel),
			Codec.FLOAT.fieldOf("saturation").forGetter(state -> state.saturation),
			Codec.INT.fieldOf("xpLevel").forGetter(state -> state.xpLevel),
			Codec.FLOAT.fieldOf("xpProgress").forGetter(state -> state.xpProgress),
			Codec.INT.fieldOf("totalExperience").forGetter(state -> state.totalExperience),
			MobEffectInstance.CODEC.listOf().optionalFieldOf("effects", List.of())
					.forGetter(state -> state.effects),
			Codec.INT.optionalFieldOf("positionSwapIntervalTicks", 0)
					.forGetter(state -> state.positionSwapIntervalTicks),
			Codec.INT.optionalFieldOf("positionSwapRemainingTicks", 0)
					.forGetter(state -> state.positionSwapRemainingTicks)
	).apply(instance, TeamState::fromCodec));

	/**
	 * 기존 16개 필드는 {@link #BASE_CODEC}이 그대로 같은 depth 에 펼쳐 쓰고, 그 옆에
	 * {@code "perks"} 와 {@code "baseMaxHealth"} 두 항목만 덧붙인다. 둘 다 선택 항목이라
	 * 저장 형태가 기존과 호환되고, 이 항목들을 모르는 예전 서버도 나머지를 그대로 읽는다.
	 *
	 * <p>{@code "baseMaxHealth"} 를 {@link #BASE_CODEC} 안에 넣지 않은 이유는
	 * {@link PerkSection} 과 같다. {@code RecordCodecBuilder.group} 의 인자 상한(P16)이 이미
	 * 다 찼다. {@code "alerts"} 도 마찬가지다.
	 */
	public static final Codec<TeamState> CODEC = RecordCodecBuilder.create(instance -> instance.group(
			BASE_CODEC.<TeamState>forGetter(state -> state),
			PerkSection.CODEC.optionalFieldOf("perks", PerkSection.EMPTY)
					.<TeamState>forGetter(TeamState::perkSection),
			Codec.FLOAT.optionalFieldOf("baseMaxHealth")
					.<TeamState>forGetter(TeamState::storedBaseMaxHealth),
			AlertSection.CODEC.optionalFieldOf("alerts", AlertSection.NONE)
					.<TeamState>forGetter(TeamState::alertSection),
			ItemStack.OPTIONAL_CODEC.listOf().optionalFieldOf("legacyGear", List.of())
					.<TeamState>forGetter(state -> List.copyOf(state.legacyGear)),
			DifficultySection.CODEC.optionalFieldOf("difficulty", DifficultySection.OFF)
					.<TeamState>forGetter(TeamState::difficultySection),
			Codec.BOOL.optionalFieldOf("perkTestUsed", false)
					.<TeamState>forGetter(state -> state.perkTestUsed),
			RerollSection.CODEC.optionalFieldOf("reroll", RerollSection.DEFAULT)
					.<TeamState>forGetter(TeamState::rerollSection),
			// 기본값이 참인 유일한 항목이다. 까닭은 runStarted 필드 문서에 적어 뒀다 —
			// 이 항목이 없는 예전 월드의 팀은 실제로 진행 중이던 팀이다.
			Codec.BOOL.optionalFieldOf("runStarted", true)
					.<TeamState>forGetter(state -> state.runStarted),
			// 이 항목이 없는 예전 월드는 0 으로 읽는다 — 「이번 회차에 확률로 나온 프리즘가
			// 아직 없다」가 맞다. 그 월드에서는 15 도 30 도 고정 프리즘였으므로 확률로 나온
			// 프리즘가 애초에 하나도 없었고, 0 이면 저장에도 적히지 않아 형태가 예전과 같다.
			Codec.INT.optionalFieldOf("extraPrismRounds", 0)
					.<TeamState>forGetter(state -> state.extraPrismRounds),
			// 이 항목이 없는 예전 월드는 0 으로 읽는다 — 「아직 모른다」가 맞다. 그 월드에서는
			// 게임 시간의 배수가 경계였으므로 켜진 시점이라는 값 자체가 없었다.
			Codec.LONG.optionalFieldOf("supplyAnchorTick", 0L)
					.<TeamState>forGetter(state -> state.supplyAnchorTick),
			// 이 항목이 없는 예전 월드는 거짓으로 읽는다 — 기본값과 같아야 한다. 까닭은
			// dragonTrialsEnabled 필드 문서에 적어 뒀다. 끈 팀은 저장에도 적히지 않아
			// 형태가 예전과 같다.
			Codec.BOOL.optionalFieldOf("dragonTrials", false)
					.<TeamState>forGetter(state -> state.dragonTrialsEnabled)
	).apply(instance, TeamState::withStoredSections));

	/**
	 * 저장에 남길 기본 최대 체력. 지금 상한과 같으면 아예 적지 않는다.
	 *
	 * <p>증강 보너스도 고정도 없는 팀에서는 둘이 언제나 같은 값이다. 읽을 때도 항목이 없으면
	 * 저장된 {@code maxHealth} 를 기본값으로 삼으므로 결과가 같다.
	 */
	private Optional<Float> storedBaseMaxHealth() {
		return baseMaxHealth == maxHealth ? Optional.empty() : Optional.of(baseMaxHealth);
	}

	/**
	 * 뒤에 붙인 선택 항목들을 채운다.
	 *
	 * <p>{@code baseMaxHealth} 가 없는 <b>기존 월드</b>에서는 생성자가 맞춰 둔 값
	 * (= 저장된 {@code maxHealth})이 그대로 남는다. 그 월드에서 팀이 정한 값은 실제로
	 * {@code maxHealth} 였으므로 이게 정확한 복원이다.
	 */
	private static TeamState withStoredSections(TeamState state, PerkSection perks,
			Optional<Float> baseMaxHealth, AlertSection alerts, List<ItemStack> legacyGear,
			DifficultySection difficulty, boolean perkTestUsed, RerollSection reroll,
			boolean runStarted, int extraPrismRounds, long supplyAnchorTick,
			boolean dragonTrials) {
		state.dragonTrialsEnabled = dragonTrials;
		state.supplyAnchorTick = Math.max(0L, supplyAnchorTick);
		state.runStarted = runStarted;
		// applyPerkSection 이 sanitizePerks 로 이 값까지 접으므로 그보다 먼저 채워야 한다.
		state.extraPrismRounds = extraPrismRounds;
		state.applyPerkSection(perks);
		state.applyAlertSection(alerts);
		state.applyDifficultySection(difficulty);
		state.applyRerollSection(reroll);
		state.perkTestUsed = perkTestUsed;
		baseMaxHealth.ifPresent(value -> state.baseMaxHealth = sanitizeMaximum(value, state.maxHealth));
		state.legacyGear.clear();
		legacyGear.stream().filter(stack -> !stack.isEmpty()).forEach(state.legacyGear::add);
		return state;
	}

	private static TeamState fromCodec(SharedItemList mainItems, Optional<SharedItemList> extraItems,
			PlayerEnderChestContainer enderContainer, SharedEquipmentStore equipment,
			Optional<List<ItemStack>> overflowItems,
			Optional<Float> maxHealth, float health, float absorption, int foodLevel, float saturation,
			int xpLevel, float xpProgress, int totalExperience, List<MobEffectInstance> effects,
			int positionSwapIntervalTicks, int positionSwapRemainingTicks) {
		float fallbackMaximum = com.sharedfate.SharedFateMod.config == null
				? 20.0F : (float) com.sharedfate.SharedFateMod.config.sharedMaxHealth;
		TeamState state = new TeamState(mainItems,
				extraItems.orElseGet(() -> SharedItemList.ofSize(EXTRA_SIZE)),
				enderContainer, equipment, overflowItems.orElseGet(List::of),
				maxHealth.orElse(fallbackMaximum), health, absorption, foodLevel, saturation,
				xpLevel, xpProgress, totalExperience, effects,
				positionSwapIntervalTicks, positionSwapRemainingTicks);
		state.sanitize(fallbackMaximum);
		return state;
	}
}
