package com.sharedfate.perk;

import com.sharedfate.SharedFateMod;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.perk.effect.DiamondSundialEffect;
import com.sharedfate.sync.TitleMessenger;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamLookup;
import com.sharedfate.team.TeamState;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code diamond_sundial} 증강(골드 「엑스레이」)의 집행부.
 *
 * <p>시계를 주 손에 들고 <b>허공을</b> 우클릭하면 정의에 적힌 시간 동안 반경 안의 다이아몬드
 * 광석 자리에 파티클이 <b>계속</b> 뜨고, <b>가장 가까운 한 자리의 좌표</b>가 액션바에 이어서
 * 적힌다. 그 시간이 <b>끝난 뒤부터</b> 쿨타임이 돈다.
 *
 * <h2>id 와 표식은 「해시계」 때 그대로다</h2>
 * <p>바뀐 것은 사람에게 보이는 이름뿐이다. 증강 id({@code sharedfate:diamond_sundial}) · 효과
 * 타입 이름 · 아이템 표식({@code sharedfate_item: "diamond_sundial"}) · 쿨타임 묶음은 전부 예전
 * 값이다. id 를 바꾸면 <b>이미 그 증강을 가진 팀의 저장이 깨지고</b>, 표식을 바꾸면 이미 나가
 * 있는 시계가 남의 물건이 되어 아무 반응도 하지 않는다. 그래서 코드에 남아 있는
 * {@code sundial} 이라는 말은 옛 이름이 아니라 <b>저장에 적힌 이름</b>으로 읽어야 한다.
 *
 * <h2>등록 지점 — 둘이다</h2>
 * <ul>
 *   <li>{@code UseItemCallback.EVENT} — 우클릭을 잡는다. {@link PerkOreExchange}·
 *       {@link PerkRallyShard}·{@link PerkFlightCharm} 과 같은 자리이고, 넷 다 자기 아이템이
 *       아니면 {@code PASS} 를 돌려주므로 서로 부딪히지 않는다. 시계는 바닐라에서 허공 우클릭에
 *       아무 동작이 없어 가로채도 잃는 것이 없다.</li>
 *   <li>{@code ServerTickEvents.END_SERVER_TICK} — {@link #tick} 이 남은 시간을 세고 주기마다
 *       다시 훑는다. <b>이 등록을 빠뜨리면 한 번 반짝이고 영영 끝나지 않는다</b> — 쿨타임을
 *       거는 것도 끝나는 자리라, 걸리지도 않는다. 빌드도 로그도 조용하다.</li>
 * </ul>
 *
 * <h2>지속형이다 — 쿨타임은 끝난 뒤부터</h2>
 * <p>{@link PerkFlightCharm} 과 같은 짜임이다. 우클릭 한 번이 <b>한 판</b>({@link Session})을 열고,
 * 그 판이 도는 동안 {@value #REFRESH_INTERVAL_TICKS} 틱마다 다시 훑어 파티클과 좌표를 새로
 * 띄운다. 판이 끝나는 그 순간에 비로소 쿨타임이 걸린다.
 *
 * <p>쓰는 순간에 걸지 않는 까닭은 비행 부적에 적어 둔 것과 같다 — 그러면 지속 시간이 쿨타임
 * <b>안에서</b> 흘러, 10초 보고 쿨 90초인 정의가 실제로는 「80초만 기다리면 다시 쓴다」가 된다.
 * 지금 한 판의 주기는 <b>지속 + 쿨타임</b>이다.
 *
 * <p>판이 끝나는 자리는 넷이다 — 시간 만료 · 죽음 · 증강 상실 · 접속 끊김. 앞의 셋은 모두
 * 쿨타임을 건다. 죽은 것을 빼 주면 「죽으면 쿨타임이 없다」가 되어 죽는 쪽이 이득이 된다.
 * 접속이 끊긴 것만 걸지 않는데, 바닐라 {@code ItemCooldowns} 가 플레이어 저장 자료에 적히지
 * 않아 다시 접속하면 어차피 비기 때문이다.
 *
 * <p>비행 부적과 달리 <b>접속 종료·부활에 따로 붙는 자리가 없다.</b> 그쪽은 {@code mayfly} 가
 * 저장 자료에 새어 나가는 것을 막아야 해서 저장 전에 끼어들 자리가 필요했지만, 여기서 남는
 * 것은 이 클래스 안의 기록 하나뿐이라 {@link #tick} 이 「그 사람이 없다」·「죽어 있다」를 보고
 * 지우는 것으로 충분하다.
 *
 * <h2>통신 규약을 올리지 않는다</h2>
 * <p>파티클({@code ClientboundLevelParticlesPacket})과 액션바({@code ClientboundSetActionBarTextPacket})
 * 는 둘 다 바닐라 패킷이다. 모드를 깔지 않은 클라이언트도 그대로 본다.
 *
 * <h2>성능 — 청크 섹션 팔레트로 먼저 거른다</h2>
 * <p>반경 32칸을 순진하게 훑으면 65³ = 274,625칸을 매번 {@code getBlockState} 해야 한다. 대신
 * 여기서는 세 겹으로 줄인다.
 *
 * <ol>
 *   <li><b>이미 올라온 청크만</b> 본다. {@code getChunkNow} 가 null 이면 건너뛴다 — 탐지 때문에
 *       청크를 새로 불러오면 그것이 가장 비싼 일이 된다.</li>
 *   <li><b>{@code LevelChunkSection.maybeHas}</b> 로 섹션의 팔레트를 한 번 훑어 다이아몬드 광석이
 *       아예 없는 16³ 덩어리를 통째로 버린다. 팔레트는 보통 항목이 수십 개뿐이라 4,096칸을
 *       읽는 것과 비교가 안 되게 싸다.</li>
 *   <li>남은 섹션 안에서도 <b>거리부터 재고</b> 구 안에 드는 칸만 {@code getBlockState} 한다.</li>
 * </ol>
 *
 * <p>반경 32면 상자는 최대 5×5 청크 × 5 섹션 = 125개 섹션에 걸친다. 굴속에서 실제로 팔레트를
 * 통과하는 섹션은 보통 0~3개이므로 한 번 훑을 때 읽는 블록은 대개 만 칸 미만이고, 다이아몬드가
 * 아예 없으면 0칸이다. 모든 섹션이 통과하는 최악에도 구 안쪽 약 137,000칸을 넘지 않는다.
 *
 * <p><b>지속형이 되면서 이 훑기가 한 판에 여러 번 돈다.</b> 10초 지속에
 * {@value #REFRESH_INTERVAL_TICKS} 틱 주기면 열한 번이다(쓰는 순간 한 번 + 주기마다 열 번).
 * 그래도 한 틱에 한 사람의 한 번뿐이고, 훑는 사람은 시계를 든 한 사람이라 예전 「연타로 쓰는
 * 사람」보다 오히려 적게 든다. 주기를 더 좁히지 않는 까닭은 아래 {@link #REFRESH_INTERVAL_TICKS}
 * 에 적어 두었다.
 *
 * <p><b>「가장 가까운 하나」를 찾는다고 먼저 찾은 자리에서 멈추지는 않는다.</b> 파티클은 찾은
 * 자리를 모두 표시하는 것이 이 증강의 본체라서, 멈추면 광맥이 한 칸만 보인다. 액션바에 적을
 * 한 자리는 이미 모아 둔 목록에서 고르므로 훑는 칸 수는 늘지 않는다.
 *
 * <h2>이 클래스가 하지 못하는 일</h2>
 * <p><b>돌 뒤에 묻힌 광석은 파티클이 보이지 않는다.</b> 파티클은 클라이언트가 지형 뒤에 깊이
 * 시험을 걸어 그리므로 벽 너머는 가려진다. 그래서 광석 자리뿐 아니라 <b>그 광석에 맞닿은 빈
 * 칸</b>에도 함께 띄운다 — 굴이나 파 놓은 갱도에 노출된 광석은 그 빈 칸의 파티클로 보인다.
 * 사방이 돌로 막힌 광석은 액션바의 좌표로만 알 수 있다. 블록을 발광시키는 방법은 26.2 에 없다.
 */
public final class PerkDiamondSundial {
	/** 광석 자리에 띄우는 파티클 수. */
	private static final int PARTICLES_AT_ORE = 6;
	/** 광석에 맞닿은 빈 칸에 띄우는 파티클 수. */
	private static final int PARTICLES_AT_OPENING = 4;
	/** 파티클이 퍼지는 반지름(칸). 블록 한 칸 안에 머물게 한다. */
	private static final double SPREAD = 0.22;

	/** 액션바 머리말. 다른 증강 알림과 같은 모양으로 맞춘다. */
	public static final String PREFIX = "[증강] ";

	/**
	 * 지속 도중 다시 훑어 파티클과 좌표를 새로 띄우는 주기(틱).
	 *
	 * <p>1초다. 이 값은 <b>엔드 막대 파티클의 수명에서 나온다.</b> 26.2 의
	 * {@code EndRodParticle} 은 생성자에서 수명을 {@code 60 + random.nextInt(12)} 틱으로 잡으므로
	 * 한 번 띄운 파티클은 3초에서 3.55초를 산다. 주기가 그보다 짧기만 하면 <b>끊기는 순간이
	 * 없고</b>, 1초면 세 세대까지 겹쳐 굴 안이 이어서 밝다.
	 *
	 * <p>더 좁히지 않는 것은 훑기가 주기마다 도는 유일한 비싼 일이기 때문이고, 더 넓히지 않는
	 * 것은 액션바 쪽 사정이다 — 바닐라 {@code Gui} 가 액션바 글을 60틱 뒤에 지우므로 주기가
	 * 3초를 넘으면 좌표가 중간에 사라진다.
	 *
	 * <p>파티클이 자기 수명만큼 사는 탓에 <b>지속이 끝난 뒤에도 마지막 세대가 최대 3.55초 더
	 * 떠 있다.</b> 그 파티클은 이미 굳은 자리라 새 광석을 알려 주지 않고, 끝났다는 것은 액션바
	 * 알림과 쿨타임 게이지가 그 자리에서 알려 준다. 끝나는 순간에 지우는 방법은 26.2 에 없다 —
	 * 보낸 파티클을 되부르는 패킷이 없다.
	 */
	public static final int REFRESH_INTERVAL_TICKS = 20;

	private static final int TICKS_PER_SECOND = 20;

	/** 지금 광석을 보고 있는 사람들. 키는 플레이어 uuid 다. */
	private static final Map<UUID, Session> ACTIVE = new HashMap<>();

	private static volatile boolean warned;

	private PerkDiamondSundial() {
	}

	/**
	 * 한 사람의 한 판.
	 *
	 * <p>{@link PerkFlightCharm} 의 것과 같은 모양이다. 판이 끝날 때 걸 쿨타임을 <b>쓰는 순간의
	 * 값으로</b> 들고 다니는 것이 요점이다 — 판이 도는 동안 정의가 바뀌어도 이미 시작한 판은
	 * 사람이 보고 시작한 숫자대로 끝난다.
	 */
	private static final class Session {
		private int remainingTicks;
		/** 판이 끝날 때 걸 쿨타임(틱). */
		private final int cooldownTicks;
		/**
		 * 쿨타임을 걸 때 넘길 묶음.
		 *
		 * <p>{@code ItemCooldowns} 는 {@code minecraft:use_cooldown} 의 <b>묶음 이름</b>만 읽으므로
		 * 쓰던 묶음의 사본이면 충분하다. 인벤토리의 실물을 들고 있으면 그 사이에 시계가 옮겨지거나
		 * 사라졌을 때 엉뚱한 것에 걸린다.
		 */
		private final ItemStack cooldownKey;

		private Session(int remainingTicks, int cooldownTicks, ItemStack cooldownKey) {
			this.remainingTicks = remainingTicks;
			this.cooldownTicks = cooldownTicks;
			this.cooldownKey = cooldownKey;
		}
	}

	// ------------------------------------------------------------------ 지급

	/**
	 * 증강 하나가 가진 {@code diamond_sundial} 효과의 시계를 지급한다.
	 *
	 * <p><b>부르는 곳은 {@link PerkGrantChain#run} 하나뿐이다.</b> {@link PerkEffect#apply} 는
	 * 접속·부활·효과 갱신 때마다 다시 불리므로 거기서 주면 접속할 때마다 시계가 늘어난다.
	 *
	 * <p>아이템을 넣는 곳도 {@link PerkItemGrants} 와 같다. 개인 인벤토리가 아니라 팀 공유
	 * 목록이고, 자리가 없으면 바닥에 떨어뜨리지 않고 넘침 목록에 남긴다.
	 *
	 * @return 실제로 지급한 개수. 줄 것이 없었으면 0
	 */
	public static int grantOnChoice(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable TeamState state, @Nullable Perk perk) {
		if (state == null || perk == null) {
			return 0;
		}

		List<ItemStack> granted = new ArrayList<>();
		for (PerkEffect effect : perk.effects()) {
			if (!(effect instanceof DiamondSundialEffect sundial)) {
				continue;
			}
			try {
				ItemStack stack = sundial.createItem();
				if (stack != null && !stack.isEmpty()) {
					granted.add(stack);
				}
			} catch (RuntimeException error) {
				SharedFateMod.LOGGER.warn("증강 '{}' 의 엑스레이 지급에 실패했습니다.", perk.id(), error);
			}
		}
		if (granted.isEmpty()) {
			return 0;
		}

		state.overflowItems.addAll(granted);
		state.restoreOverflow(ExpandedInventoryManager.enabled());
		state.overflowItems.removeIf(ItemStack::isEmpty);
		SharedFateMod.LOGGER.info("[PERK] 증강 {} 엑스레이 지급={}", perk.id(), granted.size());

		if (server != null && team != null) {
			refreshScreens(server, team);
		}
		return granted.size();
	}

	/** 공유 목록을 직접 고쳤으니 접속 중인 팀원의 화면을 맞춰 준다. */
	private static void refreshScreens(MinecraftServer server, ShareTeam team) {
		for (UUID member : team.members()) {
			ServerPlayer online = server.getPlayerList().getPlayer(member);
			if (online == null || online.containerMenu == null) {
				continue;
			}
			online.containerMenu.broadcastChanges();
		}
	}

	// ------------------------------------------------------------------ 우클릭

	/** {@code UseItemCallback.EVENT} 에 붙는 지점. */
	public static InteractionResult onUseItem(Player player, Level level, InteractionHand hand) {
		try {
			return handle(player, level, hand);
		} catch (RuntimeException error) {
			warnOnce(error);
			return InteractionResult.PASS;
		}
	}

	private static InteractionResult handle(Player player, Level level, InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND || level == null || level.isClientSide()
				|| !(player instanceof ServerPlayer user)) {
			return InteractionResult.PASS;
		}
		ItemStack held = player.getItemInHand(hand);
		if (!DiamondSundialEffect.isSundial(held)) {
			return InteractionResult.PASS;
		}

		DiamondSundialEffect effect = findEffect(TeamLookup.stateOf(user.getUUID()));
		if (effect == null) {
			// 증강을 잃었으면 시계는 남아 있어도 아무 일도 하지 않는다.
			return InteractionResult.PASS;
		}
		if (user.getCooldowns().isOnCooldown(held)) {
			return InteractionResult.FAIL;
		}
		if (ACTIVE.containsKey(user.getUUID())) {
			// 도는 판을 덮어쓰지 않는다. 덮어쓰게 두면 지속이 끝나기 직전마다 다시 눌러 쿨타임을
			// 영영 미룰 수 있다.
			refuse(user, "이미 보고 있습니다");
			return InteractionResult.FAIL;
		}

		// 쿨타임은 여기서 걸지 않는다. 지속이 끝나는 순간부터 센다 — 아래 startCooldown 참고.
		start(user, effect, held.copy());
		return InteractionResult.SUCCESS;
	}

	/**
	 * 한 판을 연다.
	 *
	 * @param cooldownKey 판이 끝날 때 쿨타임을 걸 묶음의 사본
	 */
	private static void start(ServerPlayer user, DiamondSundialEffect effect,
			ItemStack cooldownKey) {
		Session session =
				new Session(effect.durationTicks(), effect.cooldownTicks(), cooldownKey);
		ACTIVE.put(user.getUUID(), session);
		// 첫 표시는 주기를 기다리지 않고 그 자리에서 한다. 기다리면 우클릭과 첫 파티클 사이가
		// 최대 1초 비어 「안 먹혔다」고 느낀다.
		refresh(user, effect, session.remainingTicks);
	}

	/**
	 * 지금 이 사람 주위를 한 번 훑어 보여 준다.
	 *
	 * <p>주기마다 <b>다시 훑는다.</b> 처음 자리를 기억해 두고 파티클만 다시 띄우지 않는 까닭은,
	 * 지속 도중에 사람이 걸어 다니고 광석을 캐기 때문이다 — 캔 자리에 계속 파티클이 뜨거나 새로
	 * 반경에 든 광맥이 안 보이면 지속형으로 만든 뜻이 없다.
	 *
	 * @param remainingTicks 액션바에 적을 남은 시간. 0 이하면 적지 않는다
	 */
	private static void refresh(ServerPlayer user, DiamondSundialEffect effect,
			int remainingTicks) {
		ServerLevel serverLevel = user.level();
		// 훑는 기준점과 거리를 재는 기준점이 다르면 액션바의 칸 수가 파티클과 어긋난다. 한 번만 읽는다.
		BlockPos center = user.blockPosition();
		List<BlockPos> found = scan(serverLevel, center, effect.radius(), effect.maxResults());
		for (BlockPos ore : found) {
			showOre(serverLevel, user, ore);
		}
		TitleMessenger.showActionBar(user,
				Component.literal(actionBarText(center, found, remainingTicks)));
	}

	/** 쓸 수 없는 상황을 알린다. 쿨타임은 걸지 않는다. */
	private static void refuse(ServerPlayer user, String reason) {
		user.sendSystemMessage(
				Component.literal(PREFIX + DiamondSundialEffect.DISPLAY_NAME + ": " + reason)
						.withStyle(ChatFormatting.GRAY));
	}

	/**
	 * 팀이 가진 엑스레이 효과. 없으면 null.
	 *
	 * <p>{@link PerkGearRules#activeState(TeamState)} 로 시작하는 것은 다른 증강 판정과 같은 규칙이다 —
	 * 팀이 아니거나, 증강을 끈 팀이거나, 아직 아무 증강도 없으면 거기서 곧바로 돌아온다.
	 * {@link #tick} 이 매 틱 이 길을 지나므로 싼 조건이 앞에 있어야 한다.
	 */
	private static @Nullable DiamondSundialEffect findEffect(@Nullable TeamState state) {
		TeamState active = PerkGearRules.activeState(state);
		if (active == null) {
			return null;
		}
		for (String perkId : active.ownedPerks) {
			Perk perk = PerkRegistry.byId(perkId).orElse(null);
			if (perk == null) {
				continue;
			}
			for (PerkEffect effect : perk.effects()) {
				if (effect instanceof DiamondSundialEffect sundial) {
					return sundial;
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ 주기

	/**
	 * 서버 틱마다 도는 판의 남은 시간을 세고, 주기마다 다시 훑는다.
	 *
	 * <p>{@code SharedFateMod} 의 서버 틱에 붙는다. 도는 판이 하나도 없으면 첫 줄에서 빠져나가므로
	 * 이 증강을 아무도 안 쓰는 서버에서는 비용이 없다.
	 */
	public static void tick(@Nullable MinecraftServer server) {
		if (server == null || ACTIVE.isEmpty()) {
			return;
		}
		try {
			tickSessions(server);
		} catch (RuntimeException error) {
			warnOnce(error);
		}
	}

	private static void tickSessions(MinecraftServer server) {
		for (var iterator = ACTIVE.entrySet().iterator(); iterator.hasNext();) {
			Map.Entry<UUID, Session> entry = iterator.next();
			Session session = entry.getValue();
			ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
			if (player == null) {
				// 접속이 끊겼다. 되돌릴 것이 없으므로 기록만 지운다. 쿨타임을 걸지 않는 까닭은
				// 클래스 문서에 적어 두었다 — 다시 접속하면 어차피 비어 있다.
				iterator.remove();
				continue;
			}
			if (player.isRemoved() || player.isDeadOrDying()) {
				// 죽으면 그 자리에서 끝낸다. 죽은 것을 빼 주면 죽는 쪽이 이득이 되므로 쿨타임은 건다.
				iterator.remove();
				startCooldown(player, session);
				continue;
			}
			DiamondSundialEffect effect = findEffect(TeamLookup.stateOf(player.getUUID()));
			if (effect == null) {
				// 회차가 넘어가거나 「환골탈태」로 증강을 잃었다. 즉시 걷어낸다.
				iterator.remove();
				startCooldown(player, session);
				expire(player, "증강을 잃어 끝났습니다");
				continue;
			}
			if (--session.remainingTicks <= 0) {
				iterator.remove();
				startCooldown(player, session);
				expire(player, "끝났습니다");
				continue;
			}
			if (session.remainingTicks % REFRESH_INTERVAL_TICKS == 0) {
				refresh(player, effect, session.remainingTicks);
			}
		}
	}

	/**
	 * 판이 끝났다고 알린다.
	 *
	 * <p>시계를 손에 들고 있으면 이 글은 곧({@code PerkItemCooldownDisplay} 의 주기인 5틱 안에)
	 * 남은 쿨타임 표시에 덮인다. 그래도 남겨 두는 것은 증강을 잃어 끊긴 경우처럼 <b>까닭이 있는
	 * 끝</b>을 알릴 자리가 여기뿐이기 때문이다.
	 */
	private static void expire(ServerPlayer player, String reason) {
		TitleMessenger.showActionBar(player,
				Component.literal(PREFIX + DiamondSundialEffect.DISPLAY_NAME + " — " + reason)
						.withStyle(ChatFormatting.GRAY));
	}

	/**
	 * 판이 끝난 <b>그 순간</b>부터 쿨타임을 센다.
	 *
	 * <p>{@link PerkFlightCharm} 이 같은 자리에서 같은 일을 하고, 까닭도 같다. 쓰는 자리에서 걸면
	 * 지속 시간이 쿨타임 안에서 흘러 정의에 적은 숫자와 사람이 겪는 간격이 어긋난다.
	 *
	 * <p>찾지 못했을 때도 건다. 그래야 연타로 훑는 일이 없다 — 예전에 쓰는 자리에서 걸던 이유가
	 * 그것이었고, 자리만 옮겼을 뿐 그 뜻은 그대로다.
	 *
	 * <p><b>죽어서 끝난 판의 쿨타임은 부활하면 사라진다.</b> 26.2 의
	 * {@code ServerPlayer.restoreFrom} 이 부활할 때 옮기는 것은 인벤토리·경험치·상태이상·엔더
	 * 상자 따위이고 {@code ItemCooldowns} 는 거기 없다. 그래도 여기서 거는 것은 부활하지 않고
	 * 죽음 화면에 머무는 동안에도 판은 끝나야 하고, {@link PerkFlightCharm} 과 다르게 굴면 두
	 * 증강의 규칙이 갈리기 때문이다. 이 구멍을 메우려면 부활 자리에 따로 붙어 남은 쿨타임을
	 * 다시 걸어야 하는데, 그것은 「소집의 조각」이 쓰는 팀 쿨타임 짜임에 가깝다.
	 */
	private static void startCooldown(ServerPlayer player, Session session) {
		if (session.cooldownTicks <= 0 || session.cooldownKey.isEmpty()) {
			return;
		}
		player.getCooldowns().addCooldown(session.cooldownKey, session.cooldownTicks);
	}

	/**
	 * 서버가 멈출 때 부른다.
	 *
	 * <p>{@link PerkFlightCharm#onServerStopping} 처럼 종료 <b>직전</b>에 끼어들 필요가 없다.
	 * 여기 남는 것은 이 클래스 안의 기록뿐이라 저장 자료로 새어 나갈 것이 없기 때문이다.
	 */
	public static void reset() {
		ACTIVE.clear();
		warned = false;
	}

	/** 지금 광석을 보고 있는 사람의 uuid. 시험과 진단용이다. */
	public static List<UUID> viewingPlayers() {
		return new ArrayList<>(ACTIVE.keySet());
	}

	// ------------------------------------------------------------------ 문구 (순수 함수)

	/**
	 * 가까운 것부터 세우는 차례.
	 *
	 * <p>거리가 같은 후보가 여럿일 때는 <b>y → x → z 가 작은 것</b>이 앞이다. 이 뒷차례가 없으면
	 * 순서가 훑는 차례(청크·섹션 번호)에 딸려 가서, 같은 자리에서 두 번 써도 다른 좌표가 뜬다.
	 * 아래를 먼저 보는 것은 광맥이 대개 발밑에 있기 때문이다.
	 */
	public static Comparator<BlockPos> byDistance(BlockPos center) {
		return Comparator.comparingLong((BlockPos pos) -> distanceSquared(center, pos))
				.thenComparingInt(BlockPos::getY)
				.thenComparingInt(BlockPos::getX)
				.thenComparingInt(BlockPos::getZ);
	}

	/** {@code center} 에서 가장 가까운 자리. 후보가 없으면 null. */
	public static @Nullable BlockPos nearest(@Nullable BlockPos center,
			@Nullable List<BlockPos> candidates) {
		if (center == null || candidates == null || candidates.isEmpty()) {
			return null;
		}
		return candidates.stream().min(byDistance(center)).orElse(null);
	}

	/**
	 * 좌표 한 줄.
	 *
	 * <p>모양은 좌표 HUD({@code client/hud/CoordinateHud.positionLine})와 같다. 그쪽을 부르지 않고
	 * 베껴 둔 것은 <b>그 클래스가 client 소스 셋이라 여기서 참조하면 컴파일이 깨지기</b>
	 * 때문이다. 한쪽을 고치면 다른 쪽도 같이 고쳐야 한다.
	 */
	public static String positionLine(int x, int y, int z) {
		return "X " + x + "  Y " + y + "  Z " + z;
	}

	/**
	 * 액션바에 띄울 문구.
	 *
	 * <p>월드를 읽지 않는 순수 함수다. 살아 있는 서버 없이 시험할 수 있게 떼어 두었다.
	 *
	 * <p>덧붙는 개수는 {@code max_results} 에서 잘린 값이라 <b>상한에 닿으면 실제보다 적다.</b>
	 * 「반경 안에 정확히 몇 개」로 읽히면 안 되므로 「근처 N개」라고만 적는다.
	 *
	 * @param center    쓴 사람이 서 있던 칸. 거리는 여기서 잰다
	 * @param found     {@link #scan} 이 돌려준 자리들. 정렬돼 있지 않아도 된다
	 */
	public static String actionBarText(@Nullable BlockPos center, @Nullable List<BlockPos> found) {
		BlockPos target = nearest(center, found);
		if (target == null) {
			return PREFIX + "근처에 다이아몬드가 없습니다";
		}
		StringBuilder text = new StringBuilder(PREFIX)
				.append("가장 가까운 다이아몬드: ")
				.append(positionLine(target.getX(), target.getY(), target.getZ()))
				.append(" (")
				.append(blockDistance(center, target))
				.append('칸');
		if (found.size() > 1) {
			text.append(", 근처 ").append(found.size()).append('개');
		}
		return text.append(')').toString();
	}

	/**
	 * 액션바에 띄울 문구에 남은 시간을 덧붙인다.
	 *
	 * <p>남은 시간을 적는 것이 지속형의 값이다. 파티클만 보고는 「이게 언제 꺼지나」를 알 수 없어,
	 * 캐러 가는 도중에 꺼지는 것과 아직 몇 초 남은 것을 사람이 가릴 수 없다.
	 *
	 * <p>두 인자짜리 {@link #actionBarText(BlockPos, List)} 를 그대로 쓰고 뒤에만 붙인다 — 좌표와
	 * 개수를 적는 규칙이 두 벌이 되면 한쪽만 고쳐져 어긋난다.
	 *
	 * @param remainingTicks 남은 시간(틱). 0 이하면 아무것도 덧붙이지 않는다
	 */
	public static String actionBarText(@Nullable BlockPos center, @Nullable List<BlockPos> found,
			int remainingTicks) {
		String base = actionBarText(center, found);
		if (remainingTicks <= 0) {
			return base;
		}
		return base + " · " + seconds(remainingTicks) + "초";
	}

	/**
	 * 남은 틱을 사람이 읽는 초로.
	 *
	 * <p>{@link PerkFlightCharm} 과 같은 규칙이다. 1초 미만이 「0초」로 뜨면 아직 보이는데 끝난
	 * 것처럼 읽히므로 바닥을 1로 둔다.
	 */
	public static int seconds(int ticks) {
		return Math.max(1, ticks / TICKS_PER_SECOND);
	}

	/** 두 칸 사이의 거리를 반올림한 칸 수. */
	public static int blockDistance(BlockPos from, BlockPos to) {
		return (int) Math.round(Math.sqrt((double) distanceSquared(from, to)));
	}

	/** 거리의 제곱. 반경 48까지도 int 를 넘지 않지만 곱을 long 으로 두어 넘칠 걱정을 없앤다. */
	private static long distanceSquared(BlockPos from, BlockPos to) {
		long dx = (long) to.getX() - from.getX();
		long dy = (long) to.getY() - from.getY();
		long dz = (long) to.getZ() - from.getZ();
		return dx * dx + dy * dy + dz * dz;
	}

	// ------------------------------------------------------------------ 훑기

	/**
	 * {@code center} 에서 {@code radius} 칸 안의 다이아몬드 광석 자리.
	 *
	 * <p>가까운 것부터 최대 {@code maxResults} 개까지 돌려준다. 훑는 방법과 그 비용은 클래스
	 * 문서에 적어 뒀다.
	 */
	public static List<BlockPos> scan(ServerLevel level, BlockPos center, int radius,
			int maxResults) {
		if (level == null || center == null || radius < 1 || maxResults < 1) {
			return List.of();
		}
		Found found = new Found(center, radius, maxResults);

		int minX = center.getX() - radius;
		int maxX = center.getX() + radius;
		int minZ = center.getZ() - radius;
		int maxZ = center.getZ() + radius;
		int minY = Math.max(level.getMinY(), center.getY() - radius);
		int maxY = Math.min(level.getMaxY(), center.getY() + radius);
		if (minY > maxY) {
			return List.of();
		}

		int firstSection = level.getSectionIndex(minY);
		int lastSection = level.getSectionIndex(maxY);

		for (int chunkX = minX >> 4; chunkX <= (maxX >> 4) && !found.full(); chunkX++) {
			for (int chunkZ = minZ >> 4; chunkZ <= (maxZ >> 4) && !found.full(); chunkZ++) {
				// 올라와 있는 청크만 본다. 탐지 때문에 청크를 불러오면 그것이 가장 비싸다.
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				if (chunk == null) {
					continue;
				}
				LevelChunkSection[] sections = chunk.getSections();
				for (int index = firstSection; index <= lastSection && !found.full(); index++) {
					if (index < 0 || index >= sections.length) {
						continue;
					}
					LevelChunkSection section = sections[index];
					if (section == null || section.hasOnlyAir()) {
						continue;
					}
					// 팔레트 한 번으로 16³ 덩어리를 통째로 거른다.
					if (!section.maybeHas(DiamondSundialEffect::isDiamondOre)) {
						continue;
					}
					scanSection(section, chunkX << 4,
							level.getSectionYFromSectionIndex(index) << 4, chunkZ << 4,
							minX, maxX, minY, maxY, minZ, maxZ, found);
				}
			}
		}
		return found.hits();
	}

	/**
	 * 섹션 하나에서 상자와 겹치는 부분만 훑는다.
	 *
	 * <p>{@code y → z → x} 순으로 도는 것은 {@code PalettedContainer} 의 첨자가
	 * {@code (y << 8) | (z << 4) | x} 라 x 를 안쪽에 두어야 이어 읽기 때문이다.
	 */
	private static void scanSection(LevelChunkSection section, int originX, int originY,
			int originZ, int minX, int maxX, int minY, int maxY, int minZ, int maxZ, Found found) {
		int startX = Math.max(minX, originX);
		int endX = Math.min(maxX, originX + 15);
		int startY = Math.max(minY, originY);
		int endY = Math.min(maxY, originY + 15);
		int startZ = Math.max(minZ, originZ);
		int endZ = Math.min(maxZ, originZ + 15);

		for (int y = startY; y <= endY; y++) {
			for (int z = startZ; z <= endZ; z++) {
				for (int x = startX; x <= endX; x++) {
					if (!found.inRange(x, y, z)) {
						continue;
					}
					BlockState state = section.getBlockState(x - originX, y - originY, z - originZ);
					if (!DiamondSundialEffect.isDiamondOre(state)) {
						continue;
					}
					if (!found.add(x, y, z)) {
						return;
					}
				}
			}
		}
	}

	/**
	 * 반경 안의 광석 자리를 모은다.
	 *
	 * <p>바로 {@code maxResults} 개에서 끊지 않고 그 {@value #OVERSCAN} 배까지 모은 다음 거리순으로
	 * 잘라 낸다. 훑는 순서는 청크·섹션 순이라 그대로 끊으면 「구석에 몰린 16개」가 나온다.
	 * 상한을 배수로 두어 다이아몬드 광석으로 벽을 쌓아 둔 경우에도 목록이 무한정 커지지 않는다.
	 */
	public static final class Found {
		/** 자르기 전에 모아 두는 배수. */
		public static final int OVERSCAN = 4;

		private final BlockPos center;
		private final int centerX;
		private final int centerY;
		private final int centerZ;
		private final long radiusSquared;
		private final int maxResults;
		private final int collectLimit;
		private final List<BlockPos> positions = new ArrayList<>();

		public Found(BlockPos center, int radius, int maxResults) {
			// 부르는 쪽이 MutableBlockPos 를 넘기면 나중에 값이 바뀌어 거리가 어긋난다.
			this.center = center.immutable();
			this.centerX = center.getX();
			this.centerY = center.getY();
			this.centerZ = center.getZ();
			this.radiusSquared = (long) radius * radius;
			this.maxResults = Math.max(1, maxResults);
			this.collectLimit = this.maxResults * OVERSCAN;
		}

		/** 더 모을 필요가 없는가. */
		public boolean full() {
			return positions.size() >= collectLimit;
		}

		/** 이 칸이 구 안에 드는가. 상자가 아니라 구로 재야 「20칸」이 방향에 상관없이 같다. */
		public boolean inRange(int x, int y, int z) {
			return distanceSquared(x, y, z) <= radiusSquared;
		}

		/**
		 * 찾은 자리를 넣는다.
		 *
		 * @return 더 받을 수 있으면 true, 상한에 닿았으면 false
		 */
		public boolean add(int x, int y, int z) {
			if (full()) {
				return false;
			}
			positions.add(new BlockPos(x, y, z));
			return !full();
		}

		/**
		 * 가까운 것부터 최대 {@code maxResults} 개.
		 *
		 * <p>차례는 {@link PerkDiamondSundial#byDistance} 하나로 맞춰 둔다. 여기서 첫 칸이
		 * {@link PerkDiamondSundial#nearest} 가 고르는 칸과 <b>반드시 같아야</b> 액션바에 적힌
		 * 좌표와 파티클이 어긋나지 않는다.
		 */
		public List<BlockPos> hits() {
			if (positions.size() > 1) {
				positions.sort(byDistance(center));
			}
			return List.copyOf(positions.subList(0, Math.min(maxResults, positions.size())));
		}

		private long distanceSquared(int x, int y, int z) {
			long dx = x - centerX;
			long dy = y - centerY;
			long dz = z - centerZ;
			return dx * dx + dy * dy + dz * dz;
		}
	}

	// ------------------------------------------------------------------ 보여 주기

	/**
	 * 광석 한 자리를 보여 준다.
	 *
	 * <p>광석 자리 자체와, 그 광석에 맞닿은 빈 칸에 함께 띄운다. 파티클은 지형 뒤에 가려지므로
	 * 돌 안에 묻힌 자리는 그대로는 보이지 않고, 굴이나 갱도 쪽으로 노출된 면이 있어야 보인다.
	 *
	 * <p>파티클은 <b>쓴 사람에게만</b> 보낸다.
	 */
	private static void showOre(ServerLevel level, ServerPlayer user, BlockPos ore) {
		sendAt(level, user, ore, PARTICLES_AT_ORE);
		for (Direction direction : Direction.values()) {
			BlockPos side = ore.relative(direction);
			BlockState state = loadedStateAt(level, side);
			// 돌로 막힌 면은 어차피 안 보인다. 물이나 반블록처럼 속이 비치는 면도 띄운다.
			if (state != null && !state.isSolidRender()) {
				sendAt(level, user, side, PARTICLES_AT_OPENING);
			}
		}
	}

	private static void sendAt(ServerLevel level, ServerPlayer user, BlockPos pos, int count) {
		level.sendParticles(user, DiamondSundialEffect.particle(),
				// 거리 제한을 넘기고(기본 32칸) 파티클 설정이 「최소」여도 보이게 한다.
				true, true,
				pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				count, SPREAD, SPREAD, SPREAD, 0.0);
	}

	/**
	 * 이미 올라와 있는 청크에서만 블록을 읽는다.
	 *
	 * <p>{@code level.getBlockState} 를 그냥 쓰면 아직 안 올라온 청크를 불러온다.
	 */
	private static @Nullable BlockState loadedStateAt(ServerLevel level, BlockPos pos) {
		if (pos.getY() < level.getMinY() || pos.getY() > level.getMaxY()) {
			return null;
		}
		LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
		return chunk == null ? null : chunk.getBlockState(pos);
	}

	private static void warnOnce(RuntimeException error) {
		if (warned) {
			return;
		}
		warned = true;
		SharedFateMod.LOGGER.warn("엑스레이 처리에 실패했습니다. 이 경고는 한 번만 남습니다.", error);
	}

	/** 테스트가 상태를 격리할 때 쓴다. */
	static void resetForTesting() {
		reset();
	}
}
