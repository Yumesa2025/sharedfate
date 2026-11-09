package com.sharedfate.perk;

import com.mojang.authlib.GameProfile;
import com.sharedfate.TestBootstrap;
import com.sharedfate.recipe.ShieldRecipes;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamAwareEquipment;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 믹스인이 입혀진 진짜 {@code LivingEntity.applyItemBlocking} 이 팀원의 방패를 바닐라처럼 막는지 본다.
 *
 * <p>2026-11-09 사람 말 「방패가 작동을 안 해. 방패로 막아도 데미지가 들어와」. 이 자리에는 이 저장소의
 * 믹스인 넷이 붙어 있다 — 「완충」 몫이면 막은 양 0 으로 끝내는 HEAD({@code LivingEntityPerkDamageMixin}),
 * 「거북 등껍질」의 각도·「튕겨내기」의 내구도 배수·막은 뒤의 반격/반사({@code LivingEntityShieldCombatMixin}).
 * 거기에 팀원의 왼손은 팀이 함께 쓰는 칸({@link TeamAwareEquipment})이다. 이 시험은 그 모두가 붙은
 * 채로 정면 공격을 <b>전부</b> 막는지, 모드가 만든 방패(강철·다이아몬드)도 같은지를 붙든다.
 *
 * <p>fabric-loader-junit 은 믹스인을 입힌 채 클래스를 올리므로, 여기서 부르는 {@code applyItemBlocking}
 * 은 서버에서 도는 바로 그 본문이다. 껍데기 만드는 법은 {@code SharedHurtFeedbackTest} 와 같다 —
 * 생성자를 지나지 않은 {@code ServerPlayer} 에 이 길이 읽는 칸만 채운다.
 */
class ShieldBlockingPipelineTest {

	private static final float EPSILON = 0.0001F;

	@TempDir
	Path 폴더;

	private MinecraftServer server;
	private ServerLevel level;
	private TeamManager manager;

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
		// 막을 때마다 바닐라가 「방패 사용」 통계를 올리고, 통계는 점수판 목표까지 훑는다.
		setField(MinecraftServer.class, server, "scoreboard", new net.minecraft.server.ServerScoreboard(server));
		level = (ServerLevel) unsafe().allocateInstance(ServerLevel.class);
		setField(ServerLevel.class, level, "server", server);
		manager = TeamManager.get(server);
		TeamLookup.setServer(server);
	}

	@AfterEach
	void 정리() {
		TeamLookup.setServer(null);
		PerkShieldCombat.clear();
	}

	@Test
	void 팀이_함께_쓰는_왼손의_바닐라_방패는_정면_공격을_전부_막는다() throws Exception {
		ServerPlayer 막는사람 = 팀원("막는사람");
		팀(막는사람);
		막는다(막는사람, new ItemStack(Items.SHIELD));

		assertEquals(6.0F, 막는사람.applyItemBlocking(level, 정면에서(막는사람), 6.0F), EPSILON);
	}

	@Test
	void 강철_방패와_다이아몬드_방패도_정면_공격을_전부_막는다() throws Exception {
		ServerPlayer 막는사람 = 팀원("막는사람");
		팀(막는사람);

		막는다(막는사람, ShieldRecipes.steel(new ItemStack(Items.SHIELD)));
		assertEquals(6.0F, 막는사람.applyItemBlocking(level, 정면에서(막는사람), 6.0F), EPSILON,
				"강철 방패가 막지 못한다");

		막는다(막는사람, ShieldRecipes.diamond());
		assertEquals(6.0F, 막는사람.applyItemBlocking(level, 정면에서(막는사람), 6.0F), EPSILON,
				"다이아몬드 방패가 막지 못한다");
	}

	/**
	 * 2026-11-10 사람 말 「방패 4셋 달리면서 막을 때 방패가 안 막아짐」. 방패 세트 4단계로 만드는
	 * 다이아몬드 방패는 막으면서 달릴 수 있다. 서버 판정이 달리기 때문에 갈리지 않는지 붙든다.
	 */
	@Test
	void 다이아몬드_방패는_달리면서_막아도_정면_공격을_전부_막는다() throws Exception {
		ServerPlayer 막는사람 = 팀원("막는사람");
		팀(막는사람);
		막는다(막는사람, ShieldRecipes.diamond());
		// setSprinting 은 이동 속도 속성을 건드려 껍데기로는 못 지난다. 달리기 깃발(공유 깃발 3번)만 세운다.
		Field shared = Entity.class.getDeclaredField("DATA_SHARED_FLAGS_ID");
		shared.setAccessible(true);
		@SuppressWarnings("unchecked")
		EntityDataAccessor<Byte> sharedFlags = (EntityDataAccessor<Byte>) shared.get(null);
		막는사람.getEntityData().set(sharedFlags, (byte) (막는사람.getEntityData().get(sharedFlags) | 1 << 3));

		assertEquals(true, 막는사람.isSprinting(), "달리는 중이어야 한다");
		assertEquals(6.0F, 막는사람.applyItemBlocking(level, 정면에서(막는사람), 6.0F), EPSILON);
	}

	@Test
	void 등_뒤에서_온_공격은_바닐라처럼_막지_않는다() throws Exception {
		ServerPlayer 막는사람 = 팀원("막는사람");
		팀(막는사람);
		막는다(막는사람, new ItemStack(Items.SHIELD));

		DamageSource 뒤 = new DamageSource(피해종류(DamageTypes.MOB_ATTACK),
				막는사람.position().add(0.0D, 0.0D, -3.0D));
		assertEquals(0.0F, 막는사람.applyItemBlocking(level, 뒤, 6.0F), EPSILON);
	}

	// ------------------------------------------------------------------ 도우미

	/** 머리 방향 0 은 남쪽(+Z)을 본다. 그쪽 3칸 앞에서 온 몹 공격. */
	private static DamageSource 정면에서(ServerPlayer player) {
		return new DamageSource(피해종류(DamageTypes.MOB_ATTACK), player.position().add(0.0D, 1.0D, 3.0D));
	}

	/**
	 * 팀 공유 왼손에 방패를 쥐여 주고 막는 자세로 둔다. 바닐라 {@code startUsingItem} 이 서버에서 하는
	 * 것 — 쓰는 물건, 남은 시간, 「쓰는 중·왼손」 깃발 — 을 그대로 채운다. 막기 지연(0.25초)은 지난 뒤다.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static void 막는다(ServerPlayer player, ItemStack shield) throws Exception {
		// setItemSlot 은 장착 소리·게임 모드를 건드려 껍데기로는 못 지난다. 장비 칸에 바로 넣는다 —
		// 생산 코드에서 왼손 칸을 읽고 쓰는 곳도 결국 이 set/get 이다.
		Field equipment = LivingEntity.class.getDeclaredField("equipment");
		equipment.setAccessible(true);
		((TeamAwareEquipment) equipment.get(player)).set(EquipmentSlot.OFFHAND, shield);
		ItemStack held = player.getItemInHand(InteractionHand.OFF_HAND);
		assertSame(shield, held, "왼손이 팀 공유 칸을 거쳐 그 방패를 돌려줘야 한다");
		setField(LivingEntity.class, player, "useItem", held);
		setField(LivingEntity.class, player, "useItemRemaining", held.getUseDuration(player) - 20);
		Field flags = LivingEntity.class.getDeclaredField("DATA_LIVING_ENTITY_FLAGS");
		flags.setAccessible(true);
		player.getEntityData().set((EntityDataAccessor) flags.get(null), (byte) 3);
		assertNotNull(player.getItemBlockingWith(), "막는 중으로 보여야 한다");
	}

	private ShareTeam 팀(ServerPlayer leader) {
		TeamState state = TeamState.fresh(20.0F);
		state.perksEnabled = true;
		ShareTeam team = manager.createTeam("팀" + leader.getUUID(), leader.getUUID(), state);
		assertNotNull(team, "팀이 만들어져야 한다");
		return team;
	}

	/**
	 * 월드·UUID·이름·위치·장비·가방만 채운 {@code ServerPlayer} 껍데기. 장비는 생산 코드와 같이
	 * {@link TeamAwareEquipment} 다 — 팀이 생기면 왼손이 팀의 칸을 가리킨다.
	 *
	 * <p>내구도가 닳는 길({@code processDurabilityChange})은 발전과제·통계를 건드려 껍데기로는 못
	 * 지나므로 {@code instabuild} 로 비켜 간다. 막는 양 판정과는 상관없다.
	 */
	private ServerPlayer 팀원(String name) throws Exception {
		ServerPlayer player = (ServerPlayer) unsafe().allocateInstance(ServerPlayer.class);
		UUID id = UUID.randomUUID();
		setField(Entity.class, player, "level", level);
		setField(Entity.class, player, "uuid", id);
		setField(Entity.class, player, "type", EntityTypes.PLAYER);
		setField(Entity.class, player, "position", new Vec3(0.5D, 64.0D, 0.5D));
		setField(Player.class, player, "gameProfile", new GameProfile(id, name));
		Abilities abilities = new Abilities();
		abilities.instabuild = true;
		setField(Player.class, player, "abilities", abilities);
		TeamAwareEquipment equipment = new TeamAwareEquipment(player);
		setField(LivingEntity.class, player, "equipment", equipment);
		setField(Player.class, player, "inventory", new Inventory(player, equipment));
		setField(LivingEntity.class, player, "useItem", ItemStack.EMPTY);
		setField(ServerPlayer.class, player, "stats",
				new net.minecraft.stats.ServerStatsCounter(server, 폴더.resolve(name + ".json")));
		채운다(player);
		return player;
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
	}

	private static Object staticOf(String name) throws Exception {
		Field field = Entity.class.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(null);
	}

	/**
	 * 등록된 피해 종류의 값을 태그 없는 {@code Holder.direct} 로 감싼다.
	 *
	 * <p>시험 조회기({@code VanillaRegistries.createWorldLookup})는 데이터팩 태그를 묶지 않아서, 등록된
	 * 그대로의 손잡이로 {@code #bypasses_shield} 를 물으면 「Tags not bound」로 터진다. 직접 손잡이는
	 * 어떤 태그에도 들지 않으므로 몹 공격처럼 「방패를 꿰뚫지 않는 피해」가 된다 — 실제 서버의 몹
	 * 공격과 같은 답이다. 공용 조회기를 건드리지 않으므로 다른 시험에 새지 않는다.
	 */
	private static Holder<DamageType> 피해종류(net.minecraft.resources.ResourceKey<DamageType> key) {
		return Holder.direct(TestBootstrap.registries().lookupOrThrow(Registries.DAMAGE_TYPE)
				.getOrThrow(key).value());
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
}
