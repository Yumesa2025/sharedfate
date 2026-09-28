package com.sharedfate;

import com.sharedfate.command.ShareTeamCommand;
import com.sharedfate.config.SharedFateConfig;
import com.sharedfate.net.SharedFateNetworking;
import com.sharedfate.net.TeamBroadcaster;
import com.sharedfate.perk.ConditionalPerkManager;
import com.sharedfate.perk.MobPerkModifiers;
import com.sharedfate.perk.PerkBlockBreaks;
import com.sharedfate.perk.PerkClientRules;
import com.sharedfate.perk.PerkCompassTargets;
import com.sharedfate.perk.PerkHealthRules;
import com.sharedfate.perk.PerkHolderManager;
import com.sharedfate.perk.PerkKillRewards;
import com.sharedfate.perk.PerkLegacyGear;
import com.sharedfate.perk.PerkLifesteal;
import com.sharedfate.perk.PerkManager;
import com.sharedfate.perk.PerkRegistry;
import com.sharedfate.perk.PerkSetRegistry;
import com.sharedfate.perk.PerkTriggers;
import com.sharedfate.perk.PerkWorldRules;
import com.sharedfate.perk.PeriodicPerkManager;
import com.sharedfate.perk.TimedPerkEffects;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.sync.MaxHealthAttribute;
import com.sharedfate.sync.EffectSync;
import com.sharedfate.sync.DeathHandler;
import com.sharedfate.sync.StatMirror;
import com.sharedfate.sync.SharedHurtFeedback;
import com.sharedfate.sync.WorldGameRules;
import com.sharedfate.sync.WorldResetCoordinator;
import com.sharedfate.sync.RunProgressManager;
import com.sharedfate.sync.PositionSwapManager;
import com.sharedfate.sync.TeamGathering;
import com.sharedfate.sync.TeamRosterStore;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.EntitySleepEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SharedFateMod implements ModInitializer {
	public static final String MOD_ID = "sharedfate";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	public static SharedFateConfig config;

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		config = SharedFateConfig.loadOrCreate(
				FabricLoader.getInstance().getConfigDir().resolve("sharedfate.json"));
		// 정의는 모드 안에만 있다. config/ 를 보지 않으므로 「옛 정의 파일이 남아 새 증강이 안
		// 들어온다」는 사고가 일어날 수 없다. 남아 있는 옛 파일은 읽지 않고 한 번 알리기만 한다.
		PerkRegistry.loadBundled();
		PerkSetRegistry.loadBundled();
		com.sharedfate.perk.LegacyDefinitionFiles.noticeIfPresent(
				FabricLoader.getInstance().getConfigDir());
		SharedFateNetworking.register();
		// 「가호」 세트가 holder 증강의 모드를 정한다. 판정기를 꽂지 않으면 언제나 NORMAL 이라
		// 세트 3·4 단계가 아무 일도 하지 않는다.
		com.sharedfate.perk.PerkBlessingSet.install();

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			TeamLookup.setServer(server);
			// 회차 번호를 먼저 읽어야 한다. 바로 아래에서 명단을 맞출 때 「2회차 이상이면 언제나
			// 진행 중」이라는 규칙을 적용하는데, 그 판단의 근거가 회차 번호 하나뿐이다.
			RunProgressManager.onServerStarted(server);
			TeamRosterStore.onServerStarted(server);
			WorldResetCoordinator.onServerStarted(server);
			// 전투 중에 서버가 내려갔다 올라오면 체력만 강화된 채 타이머가 0 인 상태가 된다.
			com.sharedfate.sync.DragonTrialManager.onServerStarted(server);
			// 발전과제 달성 알림 끄기. 회차마다 월드가 새로 만들어지므로 월드에 한 번 적어
			// 두는 방식으로는 유지되지 않는다.
			WorldGameRules.onServerStarted(server);
			// 얼어 있는 채로 서버가 뜨는 일을 막는다. 강제 증강 선택이 남긴 시간 정지든
			// 다른 이유든, 시작 시점에 멈춰 있으면 무조건 풀고 로그를 남긴다.
			PerkManager.onServerStarted(server);
			// 증강 시험 명령이 켜져 있으면 시끄럽게 알린다. 조용히 켜져 있는 것이 가장 위험하다.
			com.sharedfate.command.PerkTestCommand.warnOnServerStarted(server);
			// 서버 목록 설명의 증강 개수를 실제 값으로 맞춘다. 설정을 켠 전용 서버에서만 돈다.
			com.sharedfate.sync.ServerMotd.onServerStarted(server);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(TeamRosterStore::onServerStopping);
		// 종료 직전에 시간 정지를 되돌린다. reset 은 서버가 완전히 멈춘 뒤라 너무 늦다.
		ServerLifecycleEvents.SERVER_STOPPING.register(PerkManager::onServerStopping);
		// 비행 허가는 저장보다 먼저 걷어내야 한다. SERVER_STOPPED 는 이미 늦다.
		ServerLifecycleEvents.SERVER_STOPPING.register(
				com.sharedfate.perk.PerkFlightCharm::onServerStopping);
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
			TeamLookup.setServer(null);
			ExpandedInventoryManager.clearRuntimeState();
			WorldResetCoordinator.reset();
			// 대기는 정적 상태라 한 프로세스에서 서버를 껐다 켜면 살아남는다. 다음 서버에서
			// /yes 한 번에 초기화가 도는 일을 막는다.
			com.sharedfate.command.RunResetCommand.reset();
			RunProgressManager.reset();
			PerkManager.reset();
			PerkHealthRules.reset();
			ConditionalPerkManager.reset();
			PeriodicPerkManager.reset();
			com.sharedfate.sync.AbsorptionRechargeManager.reset();
			com.sharedfate.sync.DragonTrialManager.clearState();
			com.sharedfate.sync.SharedAreaDamage.clearState();
			com.sharedfate.perk.PerkSupplyDrops.reset();
			PerkHolderManager.reset();
			TeamGathering.reset();
			com.sharedfate.sync.TeamProximity.reset();
			com.sharedfate.sync.ProjectileWardManager.reset();
			com.sharedfate.sync.ShockwaveManager.reset();
			com.sharedfate.sync.SanctuaryManager.reset();
			com.sharedfate.sync.AuraDamageManager.reset();
			com.sharedfate.sync.StaggeredSwapManager.reset();
			com.sharedfate.sync.RallyPointManager.reset();
			com.sharedfate.sync.RallyShardManager.reset();
			com.sharedfate.sync.RallyShardCooldown.reset();
			com.sharedfate.perk.PerkFlightCharm.reset();
			com.sharedfate.perk.PerkDiamondSundial.reset();
			com.sharedfate.sync.SpreadDamageManager.reset();
			com.sharedfate.sync.SwapExplosionScheduler.reset();
			com.sharedfate.perk.PerkResonantMining.reset();
			PerkWorldRules.reset();
			com.sharedfate.sync.PreStartRestrictions.reset();
			PerkCompassTargets.reset();
			com.sharedfate.perk.PerkGearManager.reset();
			PerkLegacyGear.reset();
			TimedPerkEffects.reset();
			MobPerkModifiers.reset();
			com.sharedfate.sync.DifficultyEscalation.reset();
			PerkClientRules.reset();
			com.sharedfate.net.StatSnapshotBroadcaster.reset();
			com.sharedfate.net.PerkSetBroadcaster.reset();
			EffectSync.reset();
			com.sharedfate.storage.TeamStorage.reset();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> TeamManager.get(server).markDirtyIfActive());
		// 창고에 새로 들어온 것이 있으면 팀 전원에게 알린다. 물건이 창고로 가는 길이 열 곳이
		// 넘는데 모두 「넣고 되돌려 보고 남는다」 모양이라, 부르는 곳마다 달지 않고 늘어난
		// 것을 여기 한 곳에서 본다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.storage.TeamStorage::tick);
		// 엔드 전투. 엔드에 사람이 없으면 첫 줄에서 빠져나가므로 평소에는 비용이 없다.
		ServerTickEvents.END_SERVER_TICK.register(
				com.sharedfate.sync.DragonTrialManager::tick);
		ServerPlayerEvents.JOIN.register(player -> {
			com.sharedfate.perk.PerkFlightCharm.onPlayerJoin(player);
			TeamManager manager = TeamManager.get(player.level().getServer());
			if (manager.consumeExperienceClear(player.getUUID())) {
				StatMirror.setTotalExperience(player, 0);
			}
			if (manager.consumeEffectClear(player.getUUID())) {
				EffectSync.clearPersistedDetachedPlayer(player);
			}
			// 없는 사이에 팀이 해체됐다. 아이템도 경험치도 이미 사라졌으므로 있던 자리에 그대로
			// 두면 네더 한복판에 빈손으로 남는다.
			if (manager.consumeSpawnReturn(player.getUUID())) {
				com.sharedfate.sync.SpawnReturn.send(
						player.level().getServer(), player, "팀 해체 뒤 첫 접속");
			}
			MaxHealthAttribute.refresh(player, config.sharedMaxHealth);
			EffectSync.refreshPlayer(player);
			var team = manager.teamOf(player.getUUID());
			var state = manager.stateOf(player.getUUID());
			if (team != null && state != null) {
				StatMirror.syncPlayerNow(team.teamId(), state, player);
			}
			TeamBroadcaster.sendTo(player);
			RunProgressManager.onPlayerJoin(player);
			// 팀이 쓰는 소집의 조각 쿨타임이 도는 중이면 게이지를 남은 만큼 다시 걸어 준다.
			com.sharedfate.sync.RallyShardCooldown.onPlayerJoin(player);
			PerkManager.onPlayerJoin(player);
			com.sharedfate.command.PerkTestCommand.warnOnJoin(player);
		});
		ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
			com.sharedfate.perk.PerkFlightCharm.onRespawn(oldPlayer, newPlayer);
			StatMirror.forget(oldPlayer.getUUID());
			MaxHealthAttribute.refresh(newPlayer, config.sharedMaxHealth);
			EffectSync.refreshPlayer(newPlayer);
			TeamManager manager = TeamManager.get(newPlayer.level().getServer());
			var team = manager.teamOf(newPlayer.getUUID());
			var state = manager.stateOf(newPlayer.getUUID());
			if (team != null && state != null) {
				StatMirror.syncPlayerNow(team.teamId(), state, newPlayer);
			}
			PerkManager.refreshPlayer(newPlayer);
		});
		ServerPlayerEvents.LEAVE.register(player -> {
			// 저장되기 전에 비행 허가를 걷어내야 영구 비행이 안 남는다.
			com.sharedfate.perk.PerkFlightCharm.onPlayerLeave(player);
			var state = TeamLookup.stateOf(player.getUUID());
			if (state != null) {
				com.sharedfate.sync.InventorySwapper.stashCarried(player, state);
			}
			StatMirror.forget(player.getUUID());
			TeamBroadcaster.onDisconnect(player);
			ExpandedInventoryManager.removePlayer(player);
			RunProgressManager.onPlayerLeave(player);
			PerkManager.onPlayerLeave(player);
			PerkHolderManager.onPlayerLeave(player);
		});
		// 「유산」의 전멸 시점 승계 스냅샷. DeathHandler 가 공유 인벤토리를 비우기 전에 떠야
		// 하므로 DeathHandler::onDeath 보다 반드시 먼저 등록한다(PerkLegacyGear 문서 참고).
		ServerLivingEntityEvents.AFTER_DEATH.register(PerkLegacyGear::onDeath);
		ServerLivingEntityEvents.AFTER_DEATH.register(DeathHandler::onDeath);
		ServerLivingEntityEvents.AFTER_DEATH.register(RunProgressManager::onDeath);
		// 처치 보상 증강(on_kill)의 등록 지점. 죽은 쪽이 몹이 아니면 곧바로 빠져나간다.
		ServerLivingEntityEvents.AFTER_DEATH.register(PerkKillRewards::onDeath);
		// 보유자형 증강(holder)의 보유자가 죽으면 즉시 다른 팀원에게 넘기는 지점.
		ServerLivingEntityEvents.AFTER_DEATH.register(PerkHolderManager::onDeath);
		// 시차·정거장이 진행·대기 중이던 상태를 팀 전멸 때 지우는 지점.
		ServerLivingEntityEvents.AFTER_DEATH.register(com.sharedfate.sync.StaggeredSwapManager::onDeath);
		ServerLivingEntityEvents.AFTER_DEATH.register(com.sharedfate.sync.RallyPointManager::onDeath);
		ServerLivingEntityEvents.AFTER_DEATH.register(com.sharedfate.sync.RallyShardManager::onDeath);
		ServerLivingEntityEvents.AFTER_DEATH.register(com.sharedfate.sync.SpreadDamageManager::onDeath);
		ServerLivingEntityEvents.AFTER_DAMAGE.register(SharedHurtFeedback::onDamage);
		// 팀원이 맞았을 때 잠깐 걸리는 증강(on_team_hurt)의 등록 지점.
		ServerLivingEntityEvents.AFTER_DAMAGE.register(PerkTriggers::onDamage);
		// 준 피해의 일부를 팀 공유 체력으로 되돌리는 증강(lifesteal)의 등록 지점.
		ServerLivingEntityEvents.AFTER_DAMAGE.register(PerkLifesteal::onDamage);
		// 보유자가 맞으면 버프를 넘기는 증강(holder + pass_on_hurt)의 등록 지점.
		ServerLivingEntityEvents.AFTER_DAMAGE.register(PerkHolderManager::onDamage);
		// 블록 파괴 증강(bonus_drop / on_break)의 등록 지점. 팀 증강이 없으면 곧바로 빠져나간다.
		PlayerBlockBreakEvents.AFTER.register(PerkBlockBreaks::onBlockBroken);
		// 회차가 시작되기 전에는 블록을 부술 수 없다. false 를 돌려주면 파괴가 막힌다.
		// AFTER 와 달리 BEFORE 라, 막힌 파괴는 위의 증강 처리까지 아예 도달하지 않는다.
		PlayerBlockBreakEvents.BEFORE.register(
				com.sharedfate.sync.PreStartRestrictions::onBeforeBlockBreak);
		// 수면 차단 증강(no_sleep)의 집행 지점. null 을 돌려주면 평소대로 잔다.
		EntitySleepEvents.ALLOW_SLEEPING.register(PerkWorldRules::onAllowSleep);
		// 나무를 광물로 바꾸는 증강(ore_exchange)의 등록 지점. 허공 우클릭에서만 발화한다.
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
				com.sharedfate.perk.PerkOreExchange::onUseItem);
		// 근처 다이아몬드 광석을 보여 주는 증강(diamond_sundial, 「엑스레이」)의 등록 지점.
		// 그 시계를 들고 허공 우클릭했을 때만 발화한다. ore_exchange 와 같은 사건에 붙지만
		// 서로 다른 아이템만 받으므로 부딪히지 않는다.
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
				com.sharedfate.perk.PerkDiamondSundial::onUseItem);
		// 팀원을 자기 자리로 부르는 증강(rally_shard)의 등록 지점. 소집의 조각을 들고 허공
		// 우클릭했을 때만 발화한다. 위 둘과 같은 사건에 붙지만 서로 다른 아이템만 받는다.
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
				com.sharedfate.perk.PerkRallyShard::onUseItem);
		// 「비행 부적」을 들고 허공 우클릭했을 때만 발화한다.
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register(
				com.sharedfate.perk.PerkFlightCharm::onUseItem);
		EffectSync.register();
		ServerTickEvents.END_SERVER_TICK.register(EffectSync::tick);
		ServerTickEvents.END_SERVER_TICK.register(StatMirror::tick);
		// 회차 시작 전에만 걸리는 제한들. 허기 되돌리기는 반드시 StatMirror 다음이어야 한다 —
		// 자연 소모로 줄어든 값을 StatMirror 가 팀 상태에 적어 넣은 뒤에 되돌려야 같은 틱 안에서
		// 맞춰진다. 앞에 두면 한 틱 늦게 따라가며 허기 막대가 미세하게 떨린다.
		ServerTickEvents.END_SERVER_TICK.register(
				com.sharedfate.sync.PreStartRestrictions::freezeHunger);
		ServerTickEvents.END_SERVER_TICK.register(
				com.sharedfate.sync.PreStartRestrictions::applySpawnBorder);
		ServerTickEvents.END_SERVER_TICK.register(
				com.sharedfate.sync.PreStartRestrictions::applyMorningLock);
		ServerTickEvents.END_SERVER_TICK.register(WorldResetCoordinator::tick);
		// 서버 초기화 되묻기의 30초를 세는 자리. 시간이 지나면 요청한 사람에게만 알린다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.command.RunResetCommand::tick);
		ServerTickEvents.END_SERVER_TICK.register(RunProgressManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(PositionSwapManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.StaggeredSwapManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.RallyPointManager::tick);
		// 「소집의 조각」이 굳힌 팀원을 소환자에게 끌어오는 지점.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.RallyShardManager::tick);
		// 소집의 조각 쿨타임은 사람이 아니라 팀이 쓴다. 그 하나를 여기서 줄인다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.RallyShardCooldown::tick);
		// 비행 시간을 세고 핫바 1번 칸의 부적을 지킨다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.perk.PerkFlightCharm::tick);
		// 「엑스레이」가 보여 주는 시간을 세고, 주기마다 다시 훑어 광석 표시를 갱신한다.
		// 이 줄이 없으면 우클릭이 한 번 반짝이고 판이 영영 끝나지 않는다 — 쿨타임을 거는 것도
		// 끝나는 자리라 쿨타임까지 함께 안 걸린다. 빌드도 로그도 조용하다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.perk.PerkDiamondSundial::tick);
		// 「완충」이 미뤄 둔 피해를 1초에 한 몫씩 넣는 지점.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.SpreadDamageManager::tick);
		// 쿨타임이 있는 증강 아이템을 들고 있을 때 남은 시간을 액션바에 적는 지점.
		ServerTickEvents.END_SERVER_TICK.register(
				com.sharedfate.perk.PerkItemCooldownDisplay::tick);
		// 폭발 교환이 0.5초 미뤄 둔 폭발을 실제로 터뜨리는 지점.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.SwapExplosionScheduler::tick);
		// 흩어진 팀을 한곳으로 모으는 증강(gather)의 판정 지점. 1초에 한 번만 실제로 잰다.
		ServerTickEvents.END_SERVER_TICK.register(TeamGathering::tick);
		// 「결속」 증강들이 함께 쓰는 「지금 얼마나 벌어져 있나」를 1초마다 한 번만 잰다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.TeamProximity::tick);
		// 아래 셋은 그 값을 읽는다. 반드시 TeamProximity 뒤여야 같은 틱에 갓 잰 값을 본다.
		// 골드 「방패벽」 — 뭉쳐 있으면 날아오는 적대적 투사체를 지운다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.ProjectileWardManager::tick);
		// 골드 「파문」 — 뭉쳐 있으면 주기마다 팀당 한 번 충격파가 퍼진다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.ShockwaveManager::tick);
		// 프리즘 「성역」 — 뭉쳐 있으면 근처 몹의 틱을 건너뛰어 모든 행동을 늦춘다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.SanctuaryManager::tick);
		// 프리즘 「살기」(aura_damage)의 판정 지점. 1초에 한 번, 팀원 주위의 적대적 몹을
		// 겹친 인원만큼 한 번에 벤다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.AuraDamageManager::tick);
		ServerTickEvents.END_SERVER_TICK.register(PerkManager::tick);
		// 최대 체력 고정 증강(max_health_lock)이 명령이나 다른 증강에 밀리지 않게 지키는 지점.
		// StatMirror 보다 뒤에 등록해야 공유 체력 계산이 끝난 뒤에 상한을 되돌린다.
		ServerTickEvents.END_SERVER_TICK.register(PerkHealthRules::tick);
		// 팀 상태에 따라 갈리는 증강(conditional)의 주기 평가 지점.
		ServerTickEvents.END_SERVER_TICK.register(ConditionalPerkManager::tick);
		// 주기로 켜졌다 꺼지는 증강(periodic)의 주기 평가 지점.
		ServerTickEvents.END_SERVER_TICK.register(PeriodicPerkManager::tick);
		// 주기마다 꽉 차는 흡수 보호막(absorption_recharge)의 충전 지점.
		// 반드시 StatMirror 보다 뒤여야 한다 — 앞에 두면 StatMirror 가 우리가 올려 둔 흡수량을
		// 「이번 틱에 새로 얻은 보호막」으로 보고 공유 풀에 한 번 더 더한다. 뒤에서 채운 뒤
		// syncPlayerNow 로 직전 스냅샷까지 갱신해야 다음 틱 변화량이 0 이 된다.
		ServerTickEvents.END_SERVER_TICK.register(
				com.sharedfate.sync.AbsorptionRechargeManager::tick);
		// 「보급」 세트가 주기마다 팀에게 무작위 아이템을 내려 주는 지점. 단계가 여럿 켜져도
		// PerkSupplyDrops 가 그중 하나만 고르므로 보급은 한 회에 한 번만 온다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.perk.PerkSupplyDrops::tick);
		// 한 명만 효과를 받는 증강(holder)의 보유자 순환 지점.
		ServerTickEvents.END_SERVER_TICK.register(PerkHolderManager::tick);
		// 시간 고정 증강(time_lock)의 되돌리기 지점. 20틱마다 오버월드 시계만 제자리로 돌린다.
		ServerTickEvents.END_SERVER_TICK.register(PerkWorldRules::tick);
		// 나침반 지시 증강(compass_target)의 집행 지점. 증강을 잃은 팀의 나침반을
		// 되돌리는 길도 여기뿐이다.
		ServerTickEvents.END_SERVER_TICK.register(PerkCompassTargets::tick);
		// 방아쇠형 증강이 잠깐 걸어 둔 효과를 시간이 되면 걷어내는 지점.
		ServerTickEvents.END_SERVER_TICK.register(TimedPerkEffects::tick);
		// 장비 제한 증강(equip_ban / item_ban / offhand_lock / weapon_damage)의 집행 지점.
		// 증강을 잃은 사람에게 남아 있던 공격력 수정자를 걷어내는 길도 여기뿐이다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.perk.PerkGearManager::tick);
		// 몹에게 걸리는 증강(mob_health / mob_damage)의 등록 지점.
		ServerTickEvents.END_SERVER_TICK.register(MobPerkModifiers::tick);
		ServerEntityEvents.ENTITY_LOAD.register(MobPerkModifiers::onEntityLoad);
		// 시간이 흐를수록 적대적 몹이 세지는 「난이도 상승」의 등록 지점. 증강의 몹 배율과는
		// 다른 속성 수정자를 쓰므로 둘이 서로 덮어쓰지 않고 곱해진다.
		ServerTickEvents.END_SERVER_TICK.register(com.sharedfate.sync.DifficultyEscalation::tick);
		ServerEntityEvents.ENTITY_LOAD.register(com.sharedfate.sync.DifficultyEscalation::onEntityLoad);
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) ->
				ShareTeamCommand.register(dispatcher, config));
		LOGGER.info("SharedFate 로드됨");
	}
}
