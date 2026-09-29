package com.sharedfate.sync;

import com.sharedfate.net.TrialHotbarLockPayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.HotbarLock} 실행기 — 굳는 손.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p>30초({@code interval})마다 핫바 두 칸({@code slots})이 굳는다.
 *
 * <ul>
 *   <li><b>쌓이지 않는다.</b> 굳은 칸이 옮겨 다닐 뿐 개수는 늘 두 칸이다</li>
 *   <li>굳은 칸을 들고는 <b>휘두르거나 쓸 수 없다</b></li>
 *   <li>꺼내거나 옮기는 것은 <b>막지 않는다</b> — 굳은 것은 손이지 물건이 아니다</li>
 * </ul>
 *
 * <h2>① 쌓지 않는 것이 이 카드를 카드로 만든다</h2>
 *
 * <p>2칸씩 쌓으면 135초에 핫바 아홉 칸이 전부 굳어 <b>이길 방법 자체가 사라진다.</b> 즉사보다
 * 나쁘다 — 죽지도 못하고 시간만 흐르고, 이 게임에서 전투를 못 끝내는 것은 결국 전멸이고 전멸은
 * 월드 삭제다. <b>되돌리지 말 것.</b> 개수를 늘리고 싶으면 카드 값 {@code slots} 를 올린다.
 *
 * <h2>② 꺼내기를 막지 않는다 — 막을 곳이 여섯에서 둘로 줄었다</h2>
 *
 * <p>사람이 「핫바 그 칸을 막는 게 의도임」이라고 확인했고, 「인벤토리를 열어 옮겨 담으면 그만
 * 아니냐」는 지적에 <b>「그대로 둔다」</b>를 골랐다. 전투 중에 인벤토리를 여는 것 자체가 대가다.
 *
 * <p>이 판단으로 <b>막을 곳이 여섯에서 둘로 줄었다.</b> 꺼내기까지 막으려면 마우스 클릭·쉬프트
 * 클릭·숫자키 스왑·드래그·Q 버리기를 <b>전부</b> 잡아야 하는데, 이 저장소는 확장 인벤토리 칸에서
 * 「한쪽만 막으면 반드시 샌다」를 이미 네 번 겪었다. 지금 막는 것은 <b>휘두르기와 쓰기 둘</b>뿐이고
 * 그 둘은 서버 이벤트 넷으로 전부 덮인다. 여기에 「그래도 옮기는 건 막아야지」를 더하지 말 것 —
 * 더하는 순간 다시 여섯 자리가 되고, 다섯만 막은 카드는 안 막은 카드보다 나쁘다.
 *
 * <h2>서버에서 막는다</h2>
 *
 * <p>막는 자리는 전부 <b>서버 쪽 이벤트</b>다. 클라이언트에서만 막으면 모드 없는 클라이언트나
 * 조작된 패킷에 샌다. 이 저장소는 {@code requireClientMod} 가 켜져 있지만 거기에 기대지 않는다 —
 * 붉은 표시만 클라이언트 일이고, <b>실제로 막는 일은 클라이언트가 무엇을 보내든 서버가 한다.</b>
 *
 * <p>26.3 의 Fabric API(fabric-events-interaction-v0 5.3.6)에 이미 있는 이벤트로 전부 된다.
 * 바이트코드를 뜯어 발화 자리를 확인했고, 넷 모두 <b>서버 쪽 클래스</b>에서만 불린다.
 *
 * <ul>
 *   <li>{@code AttackEntityCallback} — {@code Player.attack} 머리. Fabric 쪽 믹스인이
 *       {@code instanceof ServerPlayer} 로 거르고 {@code PASS} 가 아니면 공격을 통째로
 *       취소한다. 손은 언제나 {@code MAIN_HAND} 로 넘어온다</li>
 *   <li>{@code UseItemCallback} — {@code ServerPlayerGameMode.useItem} 머리. 허공 우클릭이다.
 *       먹기·물약·활 당기기·엔더진주가 전부 여기서 시작하므로 여기를 막으면 시작조차 못 한다</li>
 *   <li>{@code UseBlockCallback} — {@code ServerPlayerGameMode.useItemOn} 머리. 블록을 향한
 *       우클릭이다. 블록 놓기·부싯돌·양동이가 여기다</li>
 *   <li>{@code UseEntityCallback} — {@code ServerGamePacketListenerImpl.handleInteract}.
 *       엔티티를 향한 우클릭이다</li>
 * </ul>
 *
 * <p>믹스인을 하나도 만들지 않았다. 이미 있는 이벤트로 되는데 믹스인을 더하면 <b>재정의되는
 * 메서드에 걸어 조용히 죽는</b> 길을 새로 내는 것뿐이다.
 *
 * <h2>블록 부수기는 막지 않는다</h2>
 *
 * <p>「휘두르거나 쓸 수 없다」에 곡괭이질이 드는지는 정해진 바가 없다. <b>막지 않는 쪽을
 * 골랐다.</b> 근거는 이 카드가 나오는 자리다 — 「굳는 손」은 첫 크리스탈 풀이 아니라 <b>입장
 * 풀</b>({@code POOL_ENTRY})이라 전투 처음부터 끝까지 돈다. 크리스탈 깨기를 막으면 30초마다 두
 * 칸씩, 전투 내내, 곡괭이가 그 칸에 있는 사람이 크리스탈을 못 깬다. 그것은 「손이 굳는다」가
 * 아니라 「전투가 진행되지 않는다」이고, 카드의 무게가 완전히 달라진다.
 *
 * <p>그래서 {@code AttackBlockCallback} 과 {@code PlayerBlockBreakEvents} 에는 <b>붙지 않는다.</b>
 * 붙이는 줄을 실수로 더하지 말 것.
 *
 * <h2>사람마다 따로 뽑는다</h2>
 *
 * <p>카드 값에 팀 칸이 없고, 사람마다 핫바에 둔 물건이 다르다. 같은 번호를 팀 전원에게 걸면
 * 누구에게는 검이 굳고 누구에게는 빈 칸이 굳어 <b>같은 카드가 사람마다 다른 무게</b>가 된다.
 * 굴림을 사람마다 따로 하면 모두가 「내 두 칸」을 잃는다.
 *
 * <h2>지난 칸은 피해서 뽑는다</h2>
 *
 * <p>같은 칸이 다시 나올 수 있게 두면 소리만 울리고 아무것도 안 바뀐 주기가 생긴다 — 사람은
 * 그것을 「카드가 고장 났다」로 읽는다. 아홉 칸에서 둘을 뽑는 판이라 운이 좋으면 몇 주기를
 * 제자리에 머무를 수도 있는데, 그러면 이 카드가 요구하는 「손을 계속 옮겨 담아라」가 사라진다.
 * 칸 수가 모자라 피할 수 없을 때만({@code slots * 2 > 9}) 회피를 버린다.
 *
 * <h2>죽거나 나갔다 들어온 사람</h2>
 *
 * <p><b>둘 다 풀리지 않는다.</b> 자물쇠의 열쇠는 {@link ServerPlayer} 가 아니라 <b>UUID</b> 라,
 * 죽어서 객체가 갈려도 리스폰한 사람에게 그대로 붙어 있다. 재접속은 그 사이 명단에서 빠지므로
 * 자물쇠가 버려지는데, 돌아온 첫 틱에 {@code cycle} 이 맞는 자물쇠가 없어 <b>그 자리에서 두 칸을
 * 다시 뽑는다.</b> 즉 어느 쪽으로도 「굳은 칸 없는 30초」가 생기지 않는다.
 *
 * <p>이렇게 정한 이유는 하나다 — 조용히 풀리면 그것이 <b>이 카드를 우회하는 길</b>이 된다.
 * 죽는 것도 재접속도 사람이 마음대로 할 수 있는 일이라, 풀어 주는 순간 「불편하면 나갔다
 * 들어온다」가 최적 플레이가 된다.
 *
 * <h2>자막을 쓰지 않는다</h2>
 *
 * <p>화면 아래 글자는 이 저장소에서 전부 걷어냈다({@code TrialWarning.shout}). 칸이 옮겨 갔다는
 * 것은 <b>소리와 붉은 표시</b> 둘로만 말한다.
 */
public final class TrialHotbarLock {

	// ------------------------------------------------------------------ 못박아 둔 값

	/** 핫바 아홉 칸을 모두 덮는 비트. */
	static final int ALL_SLOTS = TrialHotbarLockPayload.ALL_SLOTS;

	/**
	 * 굳은 칸을 클라이언트에 다시 알리는 간격(틱). 1초.
	 *
	 * <p>바뀔 때 한 번만 보내면 될 것 같지만 그러면 안 되는 자리가 셋이다 — 리스폰, 재접속,
	 * 그리고 <b>끄는 패킷을 보낼 사람이 없는 전투 종료</b>(클래스 설명의 「스스로 지운다」).
	 * 정수 하나짜리 묶음이라 사람당 초당 한 장이면 값이 싸고, 받는 쪽이 「마지막으로 받은 시각」
	 * 하나로 세 경우를 모두 처리한다.
	 *
	 * <p>{@code ClientHotbarLock.STALE_TICKS}(60틱) 보다 <b>훨씬 짧아야 한다.</b> 가까우면 패킷
	 * 한 장이 늦을 때마다 붉은 표시가 깜빡인다.
	 */
	static final int SYNC_INTERVAL = 20;

	/**
	 * 마지막으로 틱을 받은 뒤 이만큼 지나면 자물쇠가 <b>물지 않는다</b>(틱). 2초.
	 *
	 * <p>⚠ 이것은 최적화가 아니라 <b>안전장치</b>다. 예전 {@code DragonTrialManager.tickSessions}
	 * 는 드래곤이 죽은 틱에 세션을 닫기만 하고 {@code TrialRisks.clearState()} 를 부르지 않았다.
	 * 그 길에서는 {@link #tick} 이 한 번도 안 불리므로 {@link #clearState()} 역시 안 불렸고, 이
	 * 장치가 없으면 <b>드래곤을 잡은 보상이 「회차가 끝날 때까지 두 칸이 굳은 채로 산다」</b>가
	 * 되었다. 컴파일도 로그도 조용한 사고다.
	 *
	 * <p>✅ <b>분배기가 고쳐졌다.</b> 이제 마지막 세션이 닫히는 틱에
	 * {@code DragonTrialManager.endTrials} 가 {@code TrialRisks.clearState()} 를 부른다.
	 * 그래도 이 값은 <b>그대로 둔다</b> — 서버 강제 종료처럼 그 길을 지나지 못하는 경우가 아직
	 * 남아 있고, 해가 없다. 틱을 받는 동안에는 {@code seenAt} 이 매 틱 갱신되므로 2초를 넘길
	 * 일이 없다.
	 */
	static final int LAPSE_TICKS = 40;

	// ------------------------------------------------------------------ 상태

	/**
	 * 사람마다의 자물쇠.
	 *
	 * <p>정적 맵인 이유는 위험이 값(레코드)이라 상태를 들 수 없기 때문이다. 월드가 바뀌면 지난
	 * 판의 굳은 칸이 새 판의 사람에게 붙으므로 {@link #clearState()} 로 반드시 비운다.
	 */
	private static final Map<UUID, Lock> LOCKS = new HashMap<>();

	/**
	 * 한 사람의 굳은 칸.
	 *
	 * @param mask   굳은 핫바 칸. 비트 {@code n} 이 {@code n} 번 칸이다
	 * @param cycle  몇 번째 주기의 굴림인가. 번호가 바뀌는 틱에만 다시 뽑는다 — 매 틱 다시 뽑으면
	 *               굳은 칸이 초당 스무 번 뛰어다녀 아무도 손을 옮길 수 없다
	 * @param seenAt 마지막으로 {@link #tick} 을 받은 시각. {@link #LAPSE_TICKS} 가 이 값을 본다
	 */
	private record Lock(int mask, long cycle, long seenAt) {
	}

	private TrialHotbarLock() {
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자({@code dragon})도 받는 것은
	 * {@link TrialRisks} 의 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <p>{@code now} 를 받아 쓰고 {@code end.getGameTime()} 을 부르지 않는다. 룰렛이 도는 동안
	 * {@code TrialFreeze} 가 판을 멈추면 게임 시각도 멈추는데, 그때 직접 물으면 주기 계산이
	 * 분배기가 보는 시계와 어긋난다.
	 *
	 * <p><b>받은 그 틱({@code elapsed == 0})에 이미 굳는다.</b> {@link TrialRisks#firesAt} 가
	 * 첫 주기를 비우는 것은 예고 없이 <b>맞는</b> 것을 막기 위해서인데, 이 카드는 피해를 주지
	 * 않으므로 예고할 것이 없다({@link TrialCrystalGuard#refreshesAt} 와 같은 판단이다). 오히려
	 * 30초를 늦추면 카드 설명을 읽고 나서 30초 동안 아무 일도 일어나지 않아 고장으로 보인다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱. 주기는 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.HotbarLock risk) {
		if (end == null || members == null || members.isEmpty() || risk == null) {
			return;
		}
		int interval = risk.interval();
		int slots = wanted(risk.slots());
		if (interval <= 0 || slots <= 0) {
			return;
		}

		// 위상은 월드 시간이 아니라 받은 틱에서 센다. now % interval 로 세면 주기가 같은 카드가
		// 전부 같은 틱에 몰린다.
		long cycle = TrialRisks.elapsedSinceGrant(now, granted) / interval;
		boolean resend = heartbeat(now, granted);

		// 이번 틱에 칸이 옮겨진 사람들. 소리는 루프 안에서 내지 않고 여기 모아 뒤에서 한 번에
		// 낸다 — 까닭은 announce 설명에 적어 두었다.
		List<ServerPlayer> moved = new ArrayList<>(members.size());
		for (ServerPlayer member : members) {
			UUID memberId = member.getUUID();
			Lock lock = LOCKS.get(memberId);
			if (lock == null || lock.cycle() != cycle) {
				// 지난 칸을 피해 뽑는다. 자물쇠가 없는 사람(첫 틱·재접속)은 피할 것이 없다.
				int mask = move(end.getRandom(), slots, lock == null ? 0 : lock.mask());
				LOCKS.put(memberId, new Lock(mask, cycle, now));
				moved.add(member);
				send(member, mask);
				continue;
			}
			// 같은 주기다. 굳은 칸은 그대로 두고 「아직 살아 있다」만 적는다.
			LOCKS.put(memberId, new Lock(lock.mask(), cycle, now));
			if (resend) {
				send(member, lock.mask());
			}
		}
		announce(end, moved);
		// 오래 틱을 못 받은 자물쇠를 버린다. 접속을 끊은 사람과 사라진 팀이 여기서 함께 정리된다.
		//
		// ⚠ 이번 명단에 없는 열쇠를 지우는(retainAll) 방식을 쓰지 말 것. 이 맵은 팀이 아니라
		// 사람으로 열쇠를 잡는데 {@code tick} 은 팀마다 따로 불린다 — 두 팀이 동시에 엔드에
		// 들어와 있으면 A 팀의 틱이 B 팀의 자물쇠를 통째로 지우고, 그러면 B 팀은 매 틱 새로
		// 뽑아 굳은 칸이 초당 스무 번 뛰어다닌다. 시각으로 재면 누구의 틱이든 남의 것을
		// 건드리지 않는다.
		LOCKS.values().removeIf(entry -> now - entry.seenAt() > LAPSE_TICKS);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. 사람에게 붙은 것이 다음 판으로 새지 않게 한다.
	 *
	 * <p>자물쇠를 버리는 것이 전부다. 사람에게 실제로 걸어 둔 것이 아무것도 없어서다 — 상태이상도
	 * 속성 수정자도 쓰지 않고, 막는 일은 이 맵을 읽어 그 자리에서 판단한다. 맵이 비면 그 순간부터
	 * 아무도 굳어 있지 않다.
	 *
	 * <p><b>끄는 패킷을 보내지 않는다.</b> 여기는 {@code SERVER_STOPPED} 에서도 불리는 자리라
	 * 보낼 연결이 남아 있다는 보장이 없고, 무엇보다 <b>받는 쪽이 스스로 지운다</b> —
	 * {@code TrialHotbarLockPayload} 의 「그만 그려라를 따로 보내지 않는다」 참고. 그래서 여기를
	 * 부르지 못하는 길(드래곤 처치)에서도 붉은 표시는 3초 안에 사라진다.
	 */
	public static void clearState() {
		LOCKS.clear();
	}

	// ---------------------------------------------------- 막는 것은 둘 — 휘두르기와 쓰기

	// 「쓰기」가 이벤트 셋인 것은 바닐라가 우클릭을 대상(허공·블록·엔티티)으로 갈라 두었기
	// 때문이지, 막는 것이 셋이라서가 아니다. 셋 다 같은 물음 하나({@link #stiff})에 답한다.

	/**
	 * {@code AttackEntityCallback.EVENT} 에 붙는 지점 — <b>휘두르기</b>.
	 *
	 * <p>손에 무엇이 들려 있는지 묻지 않는다. 굳은 것은 물건이 아니라 <b>손</b>이라 빈 칸이 굳으면
	 * 주먹질도 안 나간다. 클래스 설명의 「꺼내거나 옮기는 것은 막지 않는다 — 굳은 것은 손이지
	 * 물건이 아니다」가 그대로 이 판단의 근거다.
	 */
	public static InteractionResult onAttackEntity(@Nullable Player player, @Nullable Level level,
			@Nullable InteractionHand hand, @Nullable Entity target,
			@Nullable EntityHitResult hit) {
		return stiff(player, hand) ? InteractionResult.FAIL : InteractionResult.PASS;
	}

	/** {@code UseItemCallback.EVENT} 에 붙는 지점 — 허공 우클릭. */
	public static InteractionResult onUseItem(@Nullable Player player, @Nullable Level level,
			@Nullable InteractionHand hand) {
		return stiff(player, hand) ? InteractionResult.FAIL : InteractionResult.PASS;
	}

	/**
	 * {@code UseBlockCallback.EVENT} 에 붙는 지점 — 블록을 향한 우클릭.
	 *
	 * <p>이 자리는 <b>블록 놓기</b>만이 아니라 <b>상자 열기</b>까지 함께 막는다. 바닐라
	 * {@code ServerPlayerGameMode.useItemOn} 이 둘을 한 메서드에서 처리하기 때문이다.
	 * 더 잘게 가르는 {@code BlockEvents.USE_ITEM_ON} / {@code ItemEvents.USE_ON} 도 26.3 에
	 * 있지만 쓰지 않았다 — 그쪽은 {@code ItemStack} 쪽에 붙어 <b>클라이언트에서도 발화</b>하고,
	 * 엔티티 우클릭은 아예 지나가지 않아 막는 자리가 오히려 늘어난다.
	 *
	 * <p>상자가 한 칸에서 안 열리는 것은 대가로 받아들인 것이다. 굳지 않은 칸으로 한 번 굴리면
	 * 되고, 「손이 굳어 걸쇠를 못 연다」는 카드 이름과도 어긋나지 않는다.
	 */
	public static InteractionResult onUseBlock(@Nullable Player player, @Nullable Level level,
			@Nullable InteractionHand hand, @Nullable BlockHitResult hit) {
		return stiff(player, hand) ? InteractionResult.FAIL : InteractionResult.PASS;
	}

	/** {@code UseEntityCallback.EVENT} 에 붙는 지점 — 엔티티를 향한 우클릭. */
	public static InteractionResult onUseEntity(@Nullable Player player, @Nullable Level level,
			@Nullable InteractionHand hand, @Nullable Entity target,
			@Nullable EntityHitResult hit) {
		return stiff(player, hand) ? InteractionResult.FAIL : InteractionResult.PASS;
	}

	/**
	 * 지금 이 사람의 <b>주 손</b>이 굳어 있는가.
	 *
	 * <p>보조 손은 묻지 않는다. 보조 손 칸({@link Inventory#SLOT_OFFHAND})은 핫바 칸이 아니라
	 * 애초에 굳을 수 없고, 굳은 칸을 든 채로도 보조 손 방패는 그대로 올라간다. 그것이 이 카드의
	 * 빈틈이 아니라 「핫바 두 칸」이라고 적힌 그대로다.
	 *
	 * <p>서버에서만 참을 돌려준다. 이 이벤트들이 서버 쪽에서만 발화하는 것은 확인했지만, 다른
	 * 모드가 같은 이벤트를 클라이언트에서 부를 여지를 남기지 않는다.
	 */
	static boolean stiff(@Nullable Player player, @Nullable InteractionHand hand) {
		if (hand != InteractionHand.MAIN_HAND || !(player instanceof ServerPlayer holder)) {
			return false;
		}
		Lock lock = LOCKS.get(holder.getUUID());
		if (lock == null || !frozen(lock.mask(), holder.getInventory().getSelectedSlot())) {
			return false;
		}
		// 분배기가 이 자물쇠에 틱을 준 지 오래면 이미 없는 것으로 본다. 근거는 LAPSE_TICKS.
		long since = holder.level().getGameTime() - lock.seenAt();
		return since >= 0L && since <= LAPSE_TICKS;
	}

	// ------------------------------------------------------------------ 알리기

	/**
	 * 칸이 옮겨 갔다고 알리는 소리. 이번 틱에 <b>실제로 옮겨진 사람들</b>만 받는다.
	 *
	 * <p>사람마다 굳는 칸이 다르므로 <b>사람마다 그 자리에서</b> 울린다. 같은 틱에 모두가 함께
	 * 굳으므로 넷이 동시에 한 번씩 들으면 그것이 곧 「지금 전원이 옮길 때」라는 신호가 된다.
	 *
	 * <h2>⚠ 루프 안에서 한 사람씩 내지 않는다</h2>
	 *
	 * <p>전에는 이 메서드가 {@code ServerPlayer} 하나를 받았고 {@link #tick} 의 팀원 루프 안에서
	 * 불렸다. 그런데 그 안에서 {@code end.playSound} 를 부르면 <b>자리에 소리를 놓는 것</b>이라
	 * 반경 안의 전원에게 나간다 — 옛 주석이 「팀원이 옆에 있으면 남의 소리도 들린다」고 적어 둔
	 * 그것이고, 같은 틱에 넷이 함께 굳으므로 실제로는 <b>각자 네 겹</b>이었다. 사슬 소리가 네
	 * 번 겹치면 「묶인다」가 아니라 소음이다.
	 *
	 * <p>지금은 옮겨진 사람을 모아 {@link TrialWarning#playEach} 를 <b>한 번</b> 부른다. 목록으로
	 * 받는 모양 자체가 「루프 안에서 부르면 안 된다」를 말한다.
	 *
	 * <p>{@code CHAIN_PLACE} 를 낮게 튼 것은 「묶인다」에 가장 가까운 바닐라 소리라서다. 시련의
	 * 소리는 {@code SoundSource.HOSTILE} 로 통일돼 있고, 그 규약은 이제 {@code playEach} 가
	 * 인자로 열어 두지 않는 것으로 지킨다 — 사람이 소리 설정에서 시련만 따로 줄이거나 키울 수
	 * 있어야 한다.
	 */
	private static void announce(ServerLevel end, List<ServerPlayer> moved) {
		TrialWarning.playEach(end, moved, SoundEvents.CHAIN_PLACE, 0.8F, 0.6F);
	}

	/**
	 * 굳은 칸을 그 사람에게만 보낸다. 붉은 표시는 이것 하나로 선다.
	 *
	 * <p>{@code canSend} 를 먼저 묻는 것은 이 저장소의 관습이다({@code PerkSetBroadcaster} 등).
	 * {@code requireClientMod} 가 켜져 있어 실제로 거짓이 나올 일은 없지만, 꺼 놓고 돌리는 판에서
	 * 여기서 터지면 전투가 멈춘다. <b>막는 일은 이 패킷과 무관하다</b> — 패킷이 못 나가도 굳은
	 * 칸은 굳은 채다. 잃는 것은 붉은 표시뿐이다.
	 */
	private static void send(ServerPlayer member, int mask) {
		if (!ServerPlayNetworking.canSend(member, TrialHotbarLockPayload.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(member, new TrialHotbarLockPayload(mask));
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 실제로 굳힐 칸 수. 카드 값이 핫바보다 커도 핫바를 넘지 않는다.
	 *
	 * <p>{@code slots} 가 아홉이면 핫바 전체가 굳는데, 그것은 값이 그렇게 적힌 카드의 뜻이므로
	 * 여기서 막지 않는다. 막아야 하는 것은 <b>아홉을 넘는 값</b>뿐이다 — 그러면 뽑기가 끝없이
	 * 돌 자리가 생긴다.
	 */
	static int wanted(int slots) {
		return Math.min(Math.max(slots, 0), Inventory.SELECTION_SIZE);
	}

	/** 이 비트에서 {@code slot} 번 칸이 굳었는가. 핫바 밖 번호는 언제나 거짓이다. */
	static boolean frozen(int mask, int slot) {
		return slot >= 0 && slot < Inventory.SELECTION_SIZE && (mask & (1 << slot)) != 0;
	}

	/** 이 비트가 굳혀 둔 칸 수. 뜻 없는 비트는 세지 않는다. */
	static int count(int mask) {
		return Integer.bitCount(mask & ALL_SLOTS);
	}

	/**
	 * 이번 틱에 굳은 칸을 다시 알리는가.
	 *
	 * <p>위상을 {@code granted} 에서 세는 것은 발동과 같은 이유다. {@code now % SYNC_INTERVAL} 로
	 * 세면 이 카드를 가진 팀 전원의 패킷이 같은 틱에 몰린다.
	 */
	static boolean heartbeat(long now, long granted) {
		return TrialRisks.elapsedSinceGrant(now, granted) % SYNC_INTERVAL == 0L;
	}

	/**
	 * 굳을 칸을 새로 뽑는다. <b>쌓지 않고 옮긴다</b>는 규칙이 여기 한 곳에 있다.
	 *
	 * <p>돌려주는 것은 <b>새 비트 전부</b>다. 지난 비트와 OR 하지 않는 것이 이 카드의 ① 이다 —
	 * 여기서 {@code avoidMask | ...} 를 한 번 적는 순간 135초 뒤에 이길 방법이 사라진다.
	 *
	 * <p>부분 Fisher–Yates 라 같은 칸을 두 번 뽑지 않는다. 「무작위로 뽑고 겹치면 다시 굴리기」로
	 * 쓰면 {@code slots} 가 칸 수에 가까울 때 굴림이 길게 늘어진다.
	 *
	 * @param avoidMask 지난 주기에 굳어 있던 칸. 남은 칸이 모자라면 무시한다
	 */
	static int move(RandomSource random, int slots, int avoidMask) {
		int size = Inventory.SELECTION_SIZE;
		int want = wanted(slots);
		if (want <= 0) {
			return 0;
		}
		int[] pool = new int[size];
		int count = 0;
		for (int slot = 0; slot < size; slot++) {
			if (!frozen(avoidMask, slot)) {
				pool[count++] = slot;
			}
		}
		if (count < want) {
			// 지난 칸을 빼고 나면 모자라다. 이럴 때만 회피를 버린다 — 개수를 줄이는 쪽을 고르면
			// 「언제나 slots 칸」이 깨지고, 그쪽이 훨씬 알아채기 어려운 고장이다.
			count = 0;
			for (int slot = 0; slot < size; slot++) {
				pool[count++] = slot;
			}
		}
		int mask = 0;
		for (int taken = 0; taken < want; taken++) {
			int at = taken + random.nextInt(count - taken);
			int slot = pool[at];
			pool[at] = pool[taken];
			pool[taken] = slot;
			mask |= 1 << slot;
		}
		return mask;
	}
}
