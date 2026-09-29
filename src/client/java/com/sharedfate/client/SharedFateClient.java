package com.sharedfate.client;

import com.sharedfate.SharedFateMod;
import com.sharedfate.client.hud.CoordinateHud;
import com.sharedfate.client.hud.DamageAlertHud;
import com.sharedfate.client.hud.GameOverHud;
import com.sharedfate.client.hud.HotbarHighlight;
import com.sharedfate.client.hud.PerkProgressHud;
import com.sharedfate.client.hud.TeamLevelHud;
import com.sharedfate.client.team.TeamScreen;
import com.sharedfate.client.perk.ClientPerkFeatures;
import com.sharedfate.client.perk.ClientPerkSets;
import com.sharedfate.client.perk.DoubleJumpHandler;
import com.sharedfate.client.perk.PerkClientState;
import com.sharedfate.client.perk.PerkDrawScreen;
import com.sharedfate.client.perk.PerkOfferScreen;
import com.sharedfate.client.trial.TrialRouletteScreen;
import com.sharedfate.net.ClientVersionPayload;
import com.sharedfate.net.StatSnapshotPayload;
import com.sharedfate.net.DamageAlertPayload;
import com.sharedfate.net.HandshakePayload;
import com.sharedfate.net.OpenTeamScreenPayload;
import com.sharedfate.net.PerkClientFeaturesPayload;
import com.sharedfate.net.PerkCloseOfferPayload;
import com.sharedfate.net.PerkDrawPayload;
import com.sharedfate.net.PerkOfferPayload;
import com.sharedfate.net.PerkResultPayload;
import com.sharedfate.net.PerkSetSyncPayload;
import com.sharedfate.net.PerkSyncPayload;
import com.sharedfate.net.SelectedSlotPayload;
import com.sharedfate.net.SharedFateNetworking;
import com.sharedfate.net.TeamSyncPayload;
import com.sharedfate.net.TeamWipePayload;
import com.sharedfate.net.TrialHotbarLockPayload;
import com.sharedfate.net.TrialRoulettePayload;
import com.sharedfate.net.WorldResetPayload;
import com.sharedfate.perk.effect.HideHudEffect;
import com.sharedfate.inventory.ExpandedInventoryManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;

import java.util.UUID;

public class SharedFateClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientConfigurationNetworking.registerGlobalReceiver(HandshakePayload.TYPE,
				(payload, context) -> {
					if (payload.protocolVersion() != SharedFateNetworking.PROTOCOL_VERSION) {
						context.packetContext().orElseThrow(PacketContext.CONNECTION).disconnect(
								Component.literal(
										"SharedFate 모드 버전이 서버와 맞지 않습니다."));
						return;
					}
					ExpandedInventoryManager.applyNegotiatedClientLayout(payload.inventoryLayout());
				});

		ClientPlayNetworking.registerGlobalReceiver(TeamSyncPayload.TYPE, (payload, context) -> {
			Minecraft client = Minecraft.getInstance();
			UUID localPlayer = client.player == null
					? new UUID(0L, 0L) : client.player.getUUID();
			ClientTeamState.setTeam(payload, localPlayer);
			ExpandedInventoryManager.setClientTeamActive(client.player, ClientTeamState.inTeam());
			SelectedSlotReporter.forceResend();
		});
		ClientPlayNetworking.registerGlobalReceiver(SelectedSlotPayload.TYPE,
				(payload, context) -> ClientTeamState.setAllySlot(payload.player(), payload.slot()));
		ClientPlayNetworking.registerGlobalReceiver(DamageAlertPayload.TYPE,
				(payload, context) -> DamageAlertHud.show(
						payload.playerName(), payload.durationTicks()));
		// 서버 종료 예고. 전멸인지 운영자 초기화인지가 함께 오고, 화면에 적을 글자는 그것으로
		// 갈린다 — 회차도 남은 틱도 두 경로가 똑같이 채우므로 이 칸 말고는 가를 근거가 없다.
		ClientPlayNetworking.registerGlobalReceiver(WorldResetPayload.TYPE,
				(payload, context) -> GameOverClientDisplay.show(
						payload.runNumber(), payload.delayTicks(), payload.reason()));
		ClientPlayNetworking.registerGlobalReceiver(TeamWipePayload.TYPE,
				(payload, context) -> GameOverClientDisplay.showVictim(payload.victimName()));
		// 「폭발 교환」의 혜택. 받은 숫자를 그대로 들고 있다가 왼쪽 위에 그린다.
		ClientPlayNetworking.registerGlobalReceiver(com.sharedfate.net.SwapTimerPayload.TYPE,
				(payload, context) -> {
					var level = context.client().level;
					if (level != null) {
						ClientSwapTimer.set(payload.remainingSeconds(), level.getGameTime());
					}
				});

		// /shareteam 화면. 네트워크 스레드에서 화면을 열 수 없으므로 클라이언트 스레드로 넘긴다.
		// 다른 창이 이미 떠 있으면 열지 않는다. 증강 강제 선택 창을 밀어내면 안 된다.
		ClientPlayNetworking.registerGlobalReceiver(OpenTeamScreenPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					if (context.client().gui.screen() == null) {
						context.client().setScreenAndShow(new TeamScreen());
					}
				}));

		// 증강 후보 제시 — 네트워크 스레드에서 화면을 열 수 없으므로 클라이언트 스레드로 넘긴다.
		ClientPlayNetworking.registerGlobalReceiver(PerkOfferPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> openOfferScreen(context.client(), payload)));
		// 강제로 띄운 창은 ESC 로 닫을 수 없으므로 닫는 책임이 서버에 있다.
		// 고른 사람과 관전하던 팀원의 화면이 함께 닫혀야 한다.
		ClientPlayNetworking.registerGlobalReceiver(PerkCloseOfferPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> closeOfferScreen(context.client(), payload)));
		ClientPlayNetworking.registerGlobalReceiver(PerkSyncPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> PerkClientState.update(payload.owned(),
								payload.pendingCount(), payload.chooserName())));
		// 선택자 뽑기 연출. 서버가 선택창을 보낼 때까지 이 화면이 떠 있는다.
		ClientPlayNetworking.registerGlobalReceiver(PerkDrawPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> openDrawScreen(context.client(), payload)));
		// 무엇이 골라졌는지 알려 준다. 창은 서버가 조금 뒤에 닫는다.
		ClientPlayNetworking.registerGlobalReceiver(PerkResultPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> showPerkResult(context.client(), payload)));
		// 클라이언트가 스스로 해야 하는 증강 기능. HUD 가 읽는 값이므로 렌더와 같은
		// 스레드(클라이언트 본 스레드)에서 갱신한다.
		ClientPlayNetworking.registerGlobalReceiver(PerkClientFeaturesPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> ClientPerkFeatures.update(payload)));
		// 서버만 아는 능력치(공격력·받는 피해 배율·몹 배율). 두 화면이 그리기 스레드에서
		// 읽으므로 갱신도 클라이언트 본 스레드에서 한다.
		ClientPlayNetworking.registerGlobalReceiver(StatSnapshotPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> ClientStatSnapshot.update(payload)));
		// 세트 효과의 지금 상태. 클라이언트는 증강 풀을 읽지 않으므로 「어느 유형을 몇 개
		// 가졌는지」도 「그 유형에 무엇이 더 있는지」도 이 패킷으로만 안다. HUD 와 팀 화면이
		// 그리기 스레드에서 읽으므로 갱신도 클라이언트 본 스레드에서 한다.
		ClientPlayNetworking.registerGlobalReceiver(PerkSetSyncPayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> ClientPerkSets.update(payload)));
		// 엔드 시련 룰렛 — 네트워크 스레드에서 화면을 열 수 없으므로 클라이언트 스레드로
		// 넘긴다. 이 화면에는 뒤따르는 패킷이 없다. 연출도 닫는 것도 화면이 혼자 한다.
		ClientPlayNetworking.registerGlobalReceiver(TrialRoulettePayload.TYPE,
				(payload, context) -> context.client().execute(
						() -> openTrialRoulette(context.client(), payload)));
		// 시련 「굳는 손」이 굳혀 둔 핫바 칸. HotbarHighlight 가 그리기 스레드에서 읽으므로
		// 갱신도 클라이언트 본 스레드에서 한다.
		//
		// 받은 시각을 함께 적어 둔다. 「그만 그려라」를 보내 주는 사람이 없기 때문이다 —
		// 드래곤이 죽는 틱에 서버가 세션만 닫고 지나가는 길이 있어 끄는 패킷을 기대할 수 없다.
		// 월드가 아직 없으면 시각을 잴 수 없으므로 버린다. 서버는 1초마다 다시 보낸다.
		ClientPlayNetworking.registerGlobalReceiver(TrialHotbarLockPayload.TYPE,
				(payload, context) -> context.client().execute(() -> {
					if (context.client().level == null) {
						return;
					}
					ClientHotbarLock.update(payload, context.client().level.getGameTime());
				}));

		// 접속하자마자 자기 판을 한 번 알린다. 서버는 로그에만 적는다 — 막는 일은 규약
		// 번호가 하고, 이것은 「누가 어떤 클라이언트를 쓰는지」를 서버에서 볼 수 있게 하는
		// 기록일 뿐이다. 그것이 없어서 화면이 안 보이던 원인을 찾는 데 오래 걸린 적이 있다.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				ClientPlayNetworking.send(new ClientVersionPayload(sharedfate$modVersion())));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			ClientTeamState.clear();
			ClientSwapTimer.clear();
			// 남겨 두면 다음 서버의 첫 화면에 남의 판 붉은 칸이 뜬다. 낡으면 스스로 지우지만
			// (STALE_TICKS 3초) 그 3초가 곧 다른 서버의 첫 3초다.
			ClientHotbarLock.clear();
			SelectedSlotReporter.reset();
			DamageAlertHud.clear();
			ExpandedInventoryManager.clearNegotiatedClientLayout();
			GameOverClientDisplay.clear();
			PerkClientState.clear();
			ClientPerkFeatures.clear();
			// 여기서 안 비우면 서버의 LAST_SENT 와 어긋난다. 세트가 그대로인 채 다시 들어온
			// 사람에게는 서버가 "이미 보냈다"고 여겨 패킷을 다시 보내지 않고, 클라이언트는
			// 월드에서 나가며 값을 버렸으므로 그 회차 내내 세트 줄이 영영 안 뜬다.
			ClientPerkSets.clear();
			ClientStatSnapshot.clear();
			DoubleJumpHandler.reset();
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			SelectedSlotReporter.tick(client);
			DamageAlertHud.tick();
			GameOverClientDisplay.tick(client);
			DoubleJumpHandler.tick(client);
		});

		HudElementRegistry.attachElementAfter(
				VanillaHudElements.HOTBAR,
				SharedFateMod.id("hotbar_highlight"),
				new HotbarHighlight());
		HudElementRegistry.addLast(
				SharedFateMod.id("damage_alert"),
				new DamageAlertHud());
		// 전멸 뒤 서버 종료까지의 5초 카운트다운. 사망 화면 위에 겹쳐 보여야 하는데 HUD 가
		// 화면보다 먼저 그려지므로, 단추에 가리지 않는 자리를 직접 골라 그린다.
		HudElementRegistry.addLast(
				SharedFateMod.id("game_over"),
				new GameOverHud());
		// 경험치 레벨 숫자 바로 뒤에 붙인다. 그려지는 자리도 그 옆이라 순서를 맞춰 둔다.
		// F1 로 HUD 를 끄면 바닐라가 이 구간 자체를 건너뛰므로 같이 사라진다.
		HudElementRegistry.attachElementAfter(
				VanillaHudElements.EXPERIENCE_LEVEL,
				SharedFateMod.id("team_level"),
				new TeamLevelHud());
		// 다음 증강까지의 게이지. 레벨 숫자 바로 위에 그린다.
		HudElementRegistry.attachElementAfter(
				VanillaHudElements.EXPERIENCE_LEVEL,
				SharedFateMod.id("perk_progress"),
				new PerkProgressHud());
		// 화면 왼쪽 위의 좌표·바이옴. 이 모드도 바닐라도 쓰지 않는 자리라 기준으로 삼을
		// 바닐라 요소가 없다. 그래서 순서를 따지지 않고 맨 뒤에 붙인다 — 겹치는 것이
		// 없으므로 언제 그려지든 결과가 같다.
		HudElementRegistry.addLast(
				SharedFateMod.id("coordinates"),
				new CoordinateHud());

		// 「장님 거인」 처럼 HUD 를 가리는 증강. 바닐라 요소를 지우지 않고 "가려야 할 때만
		// 건너뛰는" 껍데기로 감싼다. removeElement 는 되돌릴 수 없어 증강을 잃어도 영영
		// 안 보인다.
		hideWhenPerkSays(VanillaHudElements.HEALTH_BAR, HideHudEffect.Element.HEALTH);
		hideWhenPerkSays(VanillaHudElements.FOOD_BAR, HideHudEffect.Element.FOOD);
		hideWhenPerkSays(VanillaHudElements.ARMOR_BAR, HideHudEffect.Element.ARMOR);
		hideWhenPerkSays(VanillaHudElements.AIR_BAR, HideHudEffect.Element.AIR);
	}

	/**
	 * 바닐라 HUD 요소 하나를 "가려야 할 때만 건너뛰는" 껍데기로 바꾼다.
	 *
	 * <p>감싸기만 하고 원래 요소를 버리지 않으므로, 증강이 없거나 잃은 뒤에는 바닐라가
	 * 그대로 그린다. 다른 모드가 같은 요소를 이미 바꿔 놓았어도 그쪽 결과를 그대로 감싼다.
	 */
	/**
	 * 이 클라이언트의 모드 판. 알 수 없으면 빈 문자열이다.
	 *
	 * <p>{@code fabric.mod.json} 의 값이라 {@code gradle.properties} 의 {@code mod_version} 과
	 * 언제나 같다. 사람이 따로 적어 두는 상수를 만들면 판을 올릴 때 반드시 한 번은 어긋난다.
	 */
	private static String sharedfate$modVersion() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
				.getModContainer(SharedFateMod.MOD_ID)
				.map(container -> container.getMetadata().getVersion().getFriendlyString())
				.orElse("");
	}

	private static void hideWhenPerkSays(Identifier vanillaElement, HideHudEffect.Element element) {
		HudElementRegistry.replaceElement(vanillaElement, original -> {
			HudElement wrapped = (graphics, deltaTracker) -> {
				if (ClientPerkFeatures.isHidden(element)) {
					return;
				}
				original.extractRenderState(graphics, deltaTracker);
			};
			return wrapped;
		});
	}

	/**
	 * 증강 선택창을 연다.
	 *
	 * <p>사망 화면만은 밀어내지 않는다. 밀어내면 부활 버튼이 사라져 아무것도 못 하게 된다.
	 * 창을 못 봐도 서버는 제한시간이 지나면 알아서 무작위로 골라 주므로 진행이 막히지 않는다.
	 */
	private static void openOfferScreen(Minecraft client, PerkOfferPayload payload) {
		if (client.gui.screen() instanceof DeathScreen) {
			return;
		}
		client.setScreenAndShow(new PerkOfferScreen(payload));
	}

	/**
	 * 선택자 뽑기 연출을 연다.
	 *
	 * <p>사망 화면만은 밀어내지 않는다. 연출을 못 봐도 곧이어 오는
	 * 선택창이 알아서 뜨고, 그마저 못 봐도 서버가 시간이 다 되면 대신 골라 준다.
	 */
	private static void openDrawScreen(Minecraft client, PerkDrawPayload payload) {
		if (client.gui.screen() instanceof DeathScreen) {
			return;
		}
		client.setScreenAndShow(new PerkDrawScreen(payload));
	}

	/**
	 * 엔드 시련 룰렛을 연다.
	 *
	 * <p>후보가 하나도 없으면 열지 않는다({@code TrialRouletteScreen.shouldOpen}). 서버가 그런
	 * 패킷을 만들지 않지만 <b>패킷은 밖에서 오는 값</b>이고, 빈 룰렛이 4초 동안 떠 있다가
	 * 사라지면 고장으로 읽힌다.
	 *
	 * <p>사망 화면만은 밀어내지 않는다 — 증강 쪽과 같은 이유다. 연출을 못 봐도 시련은 서버가
	 * 이미 정해 두었으므로 진행이 막히지 않는다.
	 */
	private static void openTrialRoulette(Minecraft client, TrialRoulettePayload payload) {
		if (client.gui.screen() instanceof DeathScreen || !TrialRouletteScreen.shouldOpen(payload)) {
			return;
		}
		client.setScreenAndShow(new TrialRouletteScreen(payload));
	}

	/**
	 * 골라진 증강을 선택창에 표시한다.
	 *
	 * <p>선택창이 떠 있지 않으면 아무것도 하지 않는다. 사망 화면을 보고 있었거나 창을 놓친
	 * 사람에게 억지로 띄우지는 않는다. 무엇이 정해졌는지는 채팅으로도 나간다.
	 */
	private static void showPerkResult(Minecraft client, PerkResultPayload payload) {
		if (client.gui.screen() instanceof PerkOfferScreen offer) {
			offer.showResult(payload.perkId(), payload.chooserName(), payload.holdTicks());
		}
	}

	/**
	 * 서버의 지시로 증강 선택창을 닫는다.
	 *
	 * <p>다른 화면이 떠 있으면 아무것도 하지 않는다. 늦게 도착한 지시가 그 사이에 열린 다음
	 * 구간의 창을 닫아버리지 않도록 구간까지 맞춰 본다.
	 */
	private static void closeOfferScreen(Minecraft client, PerkCloseOfferPayload payload) {
		if (!(client.gui.screen() instanceof PerkOfferScreen offer)) {
			return;
		}
		if (!payload.matches(offer.milestone())) {
			return;
		}
		offer.closeFromServer();
	}
}
