package com.sharedfate.sync;

import com.mojang.authlib.GameProfile;
import com.sharedfate.TestBootstrap;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「완충」의 나뉜 몫이 팀원에게 피격음을 내지 않는다는 것을 <b>보내는 꾸러미 수</b>로 못박는다.
 *
 * <p>126c004 는 몫을 바닐라의 「쿨타임 안 추가 피해」 갈래로 넣어 맞은 사람 쪽 연출을 뺐지만,
 * 둘이서 한 판에서 사람이 「아직도 여러 번 피격음이 난다」고 했다. 소리는
 * {@link SharedHurtFeedback} 이 {@code AFTER_DAMAGE} 에서 팀원에게 보내는
 * {@link ClientboundDamageEventPacket} 이었다. Fabric 은 그 사건을 {@code hurtServer}
 * 꼬리에서 부르므로 연출 깃발과 상관없이 몫마다 돈다. 그 꾸러미를 받은 클라이언트가
 * {@code handleDamageEvent} 로 피격음을 내므로, <b>꾸러미가 안 나가면 소리도 안 난다.</b>
 *
 * <h2>어떻게 서버 없이 꾸러미를 세는가</h2>
 * <p>{@link SpreadDamageOnDeathTest} 와 같은 기법이다. {@code Unsafe.allocateInstance} 로
 * 생성자를 지나지 않은 껍데기를 만들고 생산 코드가 지나는 칸만 채운다. 팀원의 연결은
 * {@code send} 만 갈아 끼운 껍데기라, 실제로 보내려 한 꾸러미가 목록에 쌓인다. 팀 조회는
 * 빈 폴더 위의 진짜 {@link SavedDataStorage} 로 생산 코드의 그 길을 그대로 돈다.
 *
 * <p>「몫을 넣는 중」은 {@code SpreadDamageManager.asSliceForTesting} 으로 만든다. 그것이
 * {@code deliver} 가 쓰는 표시 켜기({@code asSlice})를 그대로 지나므로 생산 코드와 같은 표시다.
 * {@code hurtServer} 자체는 살아 있는 월드가 있어야 불러 볼 수 있어, 그 꼬리에서 Fabric 이
 * 불렀을 {@code onDamage} 를 표시 안에서 직접 부른다. Fabric 이 꼬리에서 부른다는 것과 바닐라
 * 쿨타임 갈래가 꼬리까지 간다는 것은 jar 을 읽어 확인했다(fabric-entity-events-v1 6.0.4
 * {@code LivingEntityMixin.afterDamage} 의 {@code @At("TAIL")}, 26.3
 * {@code LivingEntity.hurtServer} 237행 {@code goto 272}).
 */
class SharedHurtFeedbackTest {

	@TempDir
	Path 폴더;

	private MinecraftServer server;
	private ServerLevel level;
	private TeamManager manager;
	private Map<UUID, ServerPlayer> 접속자;
	private int 다음번호;

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	void 서버를_세운다() throws Exception {
		SavedDataStorage storage = new SavedDataStorage(폴더.resolve("data"), null,
				TestBootstrap.registries());
		server = (MinecraftServer) unsafe().allocateInstance(DedicatedServer.class);
		setField(MinecraftServer.class, server, "savedDataStorage", storage);
		PlayerList players = (PlayerList) unsafe().allocateInstance(DedicatedPlayerList.class);
		접속자 = new HashMap<>();
		setField(PlayerList.class, players, "playersByUUID", 접속자);
		setField(MinecraftServer.class, server, "playerList", players);
		level = (ServerLevel) unsafe().allocateInstance(ServerLevel.class);
		setField(ServerLevel.class, level, "server", server);
		manager = TeamManager.get(server);
	}

	@AfterEach
	void 정리() {
		SpreadDamageManager.resetForTesting();
	}

	// ------------------------------------------------------------------ 순수 판정

	@Test
	void 평범한_피해는_팀원에게_뿌린다() {
		assertTrue(SharedHurtFeedback.shouldEcho(false, 5.0F, 0.0F, false));
	}

	@Test
	void 완충_몫이면_피해가_얼마든_뿌리지_않는다() {
		assertFalse(SharedHurtFeedback.shouldEcho(false, 5.0F, 0.0F, true));
		assertFalse(SharedHurtFeedback.shouldEcho(false, Float.MAX_VALUE, 0.0F, true));
		// 몫을 넣는 중에는 미룬 양 표시가 어쩌다 남아 있어도 뿌리지 않는다.
		assertFalse(SharedHurtFeedback.shouldEcho(false, 5.0F, 3.0F, true));
	}

	@Test
	void 완충이_미룬_처음_한_대는_피해가_0_이어도_뿌린다() {
		assertTrue(SharedHurtFeedback.shouldEcho(false, 0.0F, 6.0F, false));
	}

	@Test
	void 미루지도_않은_0_피해는_예전처럼_뿌리지_않는다() {
		assertFalse(SharedHurtFeedback.shouldEcho(false, 0.0F, 0.0F, false));
	}

	@Test
	void 방패로_막은_피해는_예전처럼_뿌리지_않는다() {
		assertFalse(SharedHurtFeedback.shouldEcho(true, 5.0F, 0.0F, false));
		assertFalse(SharedHurtFeedback.shouldEcho(true, 0.0F, 6.0F, false));
	}

	// ------------------------------------------------------------------ 실제로 나가는 꾸러미

	@Test
	void 완충이_없는_평범한_피해는_팀원에게_한_번_나간다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ServerPlayer 동료 = 팀원("동료");
		팀(맞은사람, 동료);

		SharedHurtFeedback.onDamage(맞은사람, 좀비(), 5.0F, 5.0F, false);

		assertEquals(1, 받은것(동료).size(), "동료에게 피격 연출이 한 번 나가야 한다");
		ClientboundDamageEventPacket packet = assertInstanceOf(ClientboundDamageEventPacket.class,
				받은것(동료).getFirst());
		assertEquals(동료.getId(), packet.entityId(), "동료 자신이 맞은 것처럼 보여야 소리가 동료에게 난다");
		assertEquals(0, 받은것(맞은사람).size(), "맞은 사람 본인 것은 바닐라가 보낸다");
	}

	/**
	 * <b>이번 고침의 본체.</b> 몫을 넣는 중에 불린 {@code AFTER_DAMAGE} 는 팀원에게 아무것도
	 * 보내지 않는다. 8초에 여덟 몫이면 예전에는 여기서 여덟 번 나갔다.
	 */
	@Test
	void 완충_몫을_넣는_중에는_팀원에게_아무것도_나가지_않는다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ServerPlayer 동료 = 팀원("동료");
		ShareTeam team = 팀(맞은사람, 동료);
		SpreadDamageManager.queueForTesting(team.teamId(), 16.0F, 8);

		for (int slice = 0; slice < 8; slice++) {
			SpreadDamageManager.asSliceForTesting(() ->
					SharedHurtFeedback.onDamage(맞은사람, 좀비(), 2.0F, 2.0F, false));
		}

		assertEquals(0, 받은것(동료).size(), "나뉜 몫마다 동료에게 피격 연출이 나갔다");
		assertEquals(0, 받은것(맞은사람).size());
	}

	/**
	 * 처음 맞은 순간은 완충이 피해를 0 으로 바꿔 넘기므로 {@code damageTaken} 이 0 이다. 예전에는
	 * 그래서 동료에게 아무것도 안 나갔다. 이제 미룬 양을 보고 한 번 나간다.
	 */
	@Test
	void 완충이_미룬_처음_한_대는_팀원에게_한_번_나간다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ServerPlayer 동료 = 팀원("동료");
		팀(맞은사람, 동료);
		SpreadDamageManager.noteDeferredHit(맞은사람.getUUID(), 6.0F);

		SharedHurtFeedback.onDamage(맞은사람, 좀비(), 0.0F, 0.0F, false);

		assertEquals(1, 받은것(동료).size(), "처음 맞은 순간에는 동료도 알아야 한다");
	}

	/**
	 * <b>같은 꼬리의 뒤 소비자도 미룬 양을 본다.</b> {@code AFTER_DAMAGE} 에는 이 클래스가 맨 먼저
	 * 등록되고 그 뒤에 {@code on_team_hurt}({@code PerkTriggers})·{@code pass_on_hurt}
	 * ({@code PerkHolderManager})가 온다. 예전에는 여기서 미룬 양을 꺼내며 지워, 뒤의 둘이 처음 맞은
	 * 순간을 「피해 0」으로 읽고 건너뛰었다 — 그리고 몫마다 돌았다(2026-10-06 Orca 검토 F-verified
	 * 의 V7). 뒤의 둘이 읽는 값은 {@link SpreadDamageManager#hurtTaken} 그대로다.
	 */
	@Test
	void 팀원_피격음을_보낸_뒤에도_같은_호출의_뒤_소비자는_미룬_양을_본다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ServerPlayer 동료 = 팀원("동료");
		팀(맞은사람, 동료);
		SpreadDamageManager.noteDeferredHit(맞은사람.getUUID(), 6.0F);

		SharedHurtFeedback.onDamage(맞은사람, 좀비(), 0.0F, 0.0F, false);

		assertEquals(6.0F, SpreadDamageManager.hurtTaken(맞은사람, 0.0F),
				"먼저 등록된 피격음 쪽이 미룬 양을 가져가 on_team_hurt·pass_on_hurt 가 0 을 본다");
		// 지우는 것은 같은 사람의 다음 hurtServer 맨 앞(intercept)이다.
		SpreadDamageManager.intercept(맞은사람, 좀비(), 0.0F);
		assertEquals(0.0F, SpreadDamageManager.hurtTaken(맞은사람, 0.0F),
				"지난 호출의 미룬 양이 다음 사건으로 샜다");
	}

	/**
	 * on_team_hurt·pass_on_hurt·팀원 피격음·피격 알림이 같이 쓰는 한 판정. 몫이면 0, 미룬 첫 피해면
	 * 미룬 양, 그 밖에는 받은 값 그대로.
	 */
	@Test
	void 맞은_양_판정은_몫이면_0_미룬_첫_피해면_미룬_양이다() {
		assertEquals(5.0F, SpreadDamageManager.hurtTaken(5.0F, 0.0F, false), "완충이 없으면 그대로");
		assertEquals(16.0F, SpreadDamageManager.hurtTaken(0.0F, 16.0F, false), "미룬 첫 피해");
		assertEquals(0.0F, SpreadDamageManager.hurtTaken(2.0F, 0.0F, true), "몫은 처음에 이미 셌다");
		assertEquals(0.0F, SpreadDamageManager.hurtTaken(2.0F, 3.0F, true),
				"몫을 넣는 중이면 미룬 양 표시가 어쩌다 남아 있어도 0");
		assertEquals(0.0F, SpreadDamageManager.hurtTaken(0.0F, 0.0F, false));
	}

	/**
	 * 실제 길 — 한 대를 미루고 여덟 몫을 넣는 한 바퀴 동안 뒤 소비자가 보는 「맞은 양」. 처음에 미룬
	 * 양 한 번, 몫에서는 0. 고치기 전에는 처음 0 · 몫마다 2 였다.
	 */
	@Test
	void 한_바퀴_동안_뒤_소비자는_처음에만_미룬_양을_본다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ServerPlayer 동료 = 팀원("동료");
		ShareTeam team = 팀(맞은사람, 동료);

		SpreadDamageManager.queueForTesting(team.teamId(), 16.0F, 8);
		SpreadDamageManager.noteDeferredHit(맞은사람.getUUID(), 16.0F);
		SharedHurtFeedback.onDamage(맞은사람, 좀비(), 0.0F, 0.0F, false);
		List<Float> 본것 = new ArrayList<>();
		본것.add(SpreadDamageManager.hurtTaken(맞은사람, 0.0F));

		for (int slice = 0; slice < 8; slice++) {
			float amount = SpreadDamageManager.takeSliceForTesting(team.teamId());
			SpreadDamageManager.asSliceForTesting(맞은사람, amount, () -> {
				// deliver → hurtServer 맨 앞의 intercept, 그리고 꼬리의 AFTER_DAMAGE 소비자들.
				SpreadDamageManager.intercept(맞은사람, 좀비(), amount);
				SharedHurtFeedback.onDamage(맞은사람, 좀비(), amount, amount, false);
				본것.add(SpreadDamageManager.hurtTaken(맞은사람, amount));
			});
		}

		assertEquals(List.of(16.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F), 본것);
		assertEquals(1, 받은것(동료).size());
	}

	/** 한 대를 미루고 여덟 몫을 넣는 한 바퀴 전체. 동료에게 나가는 것은 처음 한 번뿐이다. */
	@Test
	void 한_대를_미루고_여덟_몫을_넣는_동안_팀원에게는_처음_한_번만_나간다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ServerPlayer 동료 = 팀원("동료");
		ShareTeam team = 팀(맞은사람, 동료);

		// 맞은 순간: capture 가 큐를 만들고 미룬 양을 적은 뒤 hurtServer 꼬리에서 사건이 온다.
		SpreadDamageManager.queueForTesting(team.teamId(), 16.0F, 8);
		SpreadDamageManager.noteDeferredHit(맞은사람.getUUID(), 16.0F);
		SharedHurtFeedback.onDamage(맞은사람, 좀비(), 0.0F, 0.0F, false);

		// 그 뒤 1초마다 한 몫씩.
		for (int slice = 0; slice < 8; slice++) {
			float amount = SpreadDamageManager.takeSliceForTesting(team.teamId());
			SpreadDamageManager.asSliceForTesting(() ->
					SharedHurtFeedback.onDamage(맞은사람, 좀비(), amount, amount, false));
		}

		assertEquals(1, 받은것(동료).size());
	}

	@Test
	void 꼬리까지_못_간_호출이_남긴_표시는_다음_피해_맨_앞에서_지워진다() throws Exception {
		ServerPlayer 맞은사람 = 팀원("맞은사람");
		ServerPlayer 동료 = 팀원("동료");
		팀(맞은사람, 동료);
		SpreadDamageManager.noteDeferredHit(맞은사람.getUUID(), 6.0F);

		// 다음 hurtServer 의 HEAD. 피해 0 이라 곧바로 빠져나가는 길이지만 표시는 먼저 지운다.
		SpreadDamageManager.intercept(맞은사람, 좀비(), 0.0F);
		SharedHurtFeedback.onDamage(맞은사람, 좀비(), 0.0F, 0.0F, false);

		assertEquals(0, 받은것(동료).size(), "지난 호출의 미룬 양이 엉뚱한 0 피해로 샜다");
	}

	@Test
	void 몫을_넣는_중_표시는_끝나면_반드시_꺼진다() {
		SpreadDamageManager.queueForTesting(UUID.randomUUID(), 4.0F, 2);
		SpreadDamageManager.asSliceForTesting(
				() -> assertTrue(SpreadDamageManager.isDeliveringSlice()));
		assertFalse(SpreadDamageManager.isDeliveringSlice());

		try {
			SpreadDamageManager.asSliceForTesting(() -> {
				throw new IllegalStateException("몫을 넣다 터졌다");
			});
		} catch (IllegalStateException expected) {
			// 터져도 표시가 남으면 그 뒤의 모든 피해에서 팀원 연출이 사라진다.
		}
		assertFalse(SpreadDamageManager.isDeliveringSlice());
	}

	// ------------------------------------------------------------------ 도우미

	private ShareTeam 팀(ServerPlayer leader, ServerPlayer... others) {
		TeamState state = TeamState.fresh(20.0F);
		ShareTeam team = manager.createTeam("팀" + leader.getUUID(), leader.getUUID(), state);
		assertTrue(team != null, "팀이 만들어져야 한다");
		for (ServerPlayer other : others) {
			assertTrue(manager.addMember(team.teamId(), other.getUUID(), 4), "팀원이 들어가야 한다");
		}
		return team;
	}

	/**
	 * 월드·UUID·이름·체력·연결만 채운 {@code ServerPlayer} 껍데기. 접속자 명단에도 올린다.
	 *
	 * <p>체력은 {@code isDeadOrDying} 이 읽는다. 생성자를 지나지 않아 비어 있는
	 * {@code entityData} 를 {@code Entity} 생성자가 하는 그대로 다시 만든다.
	 */
	private ServerPlayer 팀원(String name) throws Exception {
		ServerPlayer player = (ServerPlayer) unsafe().allocateInstance(ServerPlayer.class);
		UUID id = UUID.randomUUID();
		setField(Entity.class, player, "level", level);
		setField(Entity.class, player, "uuid", id);
		setField(Player.class, player, "gameProfile", new GameProfile(id, name));
		// 꾸러미가 받는 사람의 엔티티 번호를 적는다. 비워 두면 그 줄에서 터진다.
		player.setId(++다음번호);
		채운다(player);
		player.connection = (ServerGamePacketListenerImpl) unsafe()
				.allocateInstance(RecordingListener.class);
		접속자.put(id, player);
		return player;
	}

	private static List<Packet<?>> 받은것(ServerPlayer player) {
		return ((RecordingListener) player.connection).보낸것();
	}

	/** {@code Entity} 생성자가 {@code entityData} 를 만드는 순서 그대로. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void 채운다(ServerPlayer player) throws Exception {
		SynchedEntityData.Builder builder = new SynchedEntityData.Builder(player);
		builder.define((EntityDataAccessor) staticOf("DATA_SHARED_FLAGS_ID"), (byte) 0);
		builder.define((EntityDataAccessor) staticOf("DATA_AIR_SUPPLY_ID"), 300);
		builder.define((EntityDataAccessor) staticOf("DATA_CUSTOM_NAME_VISIBLE"), false);
		builder.define((EntityDataAccessor) staticOf("DATA_CUSTOM_NAME"), Optional.empty());
		builder.define((EntityDataAccessor) staticOf("DATA_SILENT"), false);
		builder.define((EntityDataAccessor) staticOf("DATA_NO_GRAVITY"), false);
		builder.define((EntityDataAccessor) staticOf("DATA_POSE"), Pose.STANDING);
		builder.define((EntityDataAccessor) staticOf("DATA_TICKS_FROZEN"), 0);
		Method define = Entity.class.getDeclaredMethod("defineSynchedData",
				SynchedEntityData.Builder.class);
		define.setAccessible(true);
		define.invoke(player, builder);
		setField(Entity.class, player, "entityData", builder.build());
		assertFalse(player.isDeadOrDying(), "껍데기의 체력이 차 있어야 팀원으로 센다");
	}

	private static Object staticOf(String name) throws Exception {
		Field field = Entity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	private static DamageSource 좀비() {
		return new DamageSource(피해종류(DamageTypes.MOB_ATTACK));
	}

	private static Holder<DamageType> 피해종류(ResourceKey<DamageType> key) {
		return TestBootstrap.registries().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
	}

	private static void setField(Class<?> owner, Object target, String name, Object value)
			throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static sun.misc.Unsafe unsafe() throws Exception {
		Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (sun.misc.Unsafe) field.get(null);
	}

	/**
	 * 보내려 한 꾸러미를 쌓아 두기만 하는 연결. 생성자를 지나지 않고 만들므로 필드 초기화가
	 * 돌지 않는다 — 목록은 처음 쓸 때 만든다.
	 */
	static final class RecordingListener extends ServerGamePacketListenerImpl {
		private List<Packet<?>> 쌓인것;

		@SuppressWarnings("DataFlowIssue")
		private RecordingListener() {
			super(null, null, null, null);
		}

		@Override
		public void send(Packet<?> packet) {
			보낸것().add(packet);
		}

		List<Packet<?>> 보낸것() {
			if (쌓인것 == null) {
				쌓인것 = new ArrayList<>();
			}
			return 쌓인것;
		}
	}
}
