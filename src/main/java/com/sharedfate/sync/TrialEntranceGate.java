package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.net.TrialEntranceAcceptC2SPayload;
import com.sharedfate.net.TrialEntranceClosePayload;
import com.sharedfate.net.TrialEntranceOfferPayload;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 엔드에 처음 들어선 팀을 <b>그 자리에 멈춰 세우고</b> 입장 수락창을 띄운다.
 *
 * <p>⚠ 화면에 나가는 글은 {@code TrialEntranceScreen} 에 있다. 2026-10-01 에 사람이
 * <b>「시련이 곧 시작됩니다. / 엔더드래곤 / 취소할 수 없습니다.」 이정도만</b>으로 줄였으므로,
 * 아래 인용에 나오는 「시련을 시작합니다」는 <b>그때 사람이 말한 그대로</b>이고 지금 화면에
 * 뜨는 글이 아니다.
 *
 * <h2>사람이 정한 것</h2>
 *
 * <p>「첫 엔더로 입장하면 한명이라도 엔더에 입장하면 정지하고 (시련중이면) 시련을 시작합니다. 라고
 * 수락창이 뜨고 리더가 확인 을 누루면 버튼은 확인만있지만, 엔더로 진입 엔더 중앙으로 보내 그
 * 기반암 단상있는곳으로 … 그위치로 그러고 연출보게 하면됨」. 「확인한 뒤 중앙으로 보낼 대상은
 * 누구인가」를 따로 물었을 때 <b>「팀 전원」</b>이라고 답했다(2026-10-01).
 *
 * <h2>「정지」를 어떻게 했나 — {@code TrialFreeze} 를 쓰지 않는다</h2>
 *
 * <p>시련 화면은 {@link TrialFreeze} 가 <b>서버 전체를 얼려</b> 멈춘다. 그 길을 여기서 쓰지
 * 않았고, 까닭이 셋이다.
 *
 * <ol>
 *   <li><b>기다리는 길이를 서버가 모른다.</b> {@link TrialFreeze#MAX_TICKS} 가 400틱(20초)이고
 *       그것은 「계산이 어긋나도 그 이상 얼지 않는다」는 안전장치다. 사람이 단추를 누르기까지는
 *       20초보다 길 수 있고, 그 상한을 늘리는 것은 <b>운영자가 콘솔에 들어가야만 되살아나는</b>
 *       상태의 상한을 늘리는 것이다</li>
 *   <li><b>{@code TrialFreeze} 의 결과 칸이 카드 하나다</b>({@code Finished(teamId, trialId)}).
 *       그것이 {@code DragonTrialManager.applyFinishedTrial} 로 흘러 <b>카드를 쌓는다</b> —
 *       수락창은 카드가 아니므로 그 길에 얹으면 있지도 않은 카드를 하나 받게 된다</li>
 *   <li>⚠⚠ <b>바닐라의 시간 정지는 플레이어를 멈추지 않는다.</b> {@code TrialFreeze} 클래스
 *       설명이 그 사실을 적어 두고 피해만 따로 버린다({@code blocksDamage}). 곧 얼려도
 *       「그 자리에 멈춘다」는 안 되고, <b>떨어지는 것도 안 멈춘다</b></li>
 * </ol>
 *
 * <p>그래서 <b>사람마다 자리를 못박는다</b>({@link #hold}). 화면이 떠 있는 동안 클라이언트는
 * 입력을 창에 쓰므로 걷지 않고, 남는 것은 <b>중력</b>뿐이다. 매 틱 낙하 거리를 0 으로 되돌리고
 * 자리에서 {@value #DRIFT_TOLERANCE} 칸 넘게 벗어나면 되돌려 놓는다.
 *
 * <h2>⚠⚠ 붙들려 있는 동안 허공에 떨어지지 않는다 — 공유 체력이라 한 사람의 낙사가 월드 삭제다</h2>
 *
 * <p>엔드 도착 자리는 허공일 수 있다(서버 로그에 실제로 {@code standing on air - force-sending
 * blocks below} 가 찍힌다). 그래서 붙들 자리를 그냥 「지금 선 자리」로 두지 않고
 * {@link TrialPodium#grounded} 로 <b>땅을 딛고 있는지 먼저 본다.</b>
 *
 * <ul>
 *   <li><b>엔드에서 허공에 있으면</b> 붙들 자리를 <b>단상</b>으로 바꾼다. 그 자리는 기반암이라
 *       절대 사라지지 않고, 어차피 확인 뒤에 모일 자리라 사람이 놀라지도 않는다</li>
 *   <li><b>엔드 밖에서 허공에 있으면</b>(네더에서 떨어지던 중 등) <b>옮기지 않는다.</b> 확인하기
 *       전에 남의 차원에서 끌어오는 것은 「확인하면 모은다」를 미리 하는 것이고, 붙들기 자체가
 *       떨어지는 것을 막으므로 그 사람이 더 위험해지지도 않는다</li>
 * </ul>
 *
 * <p>겹겹이 저항 V 도 건다({@value #GRACE_TICKS} 틱, {@value #GRACE_REFRESH_TICKS} 틱마다 갱신).
 * {@code DragonTrialManager.ARRIVAL_GRACE_TICKS} 가 도착 직후에 쓰는 것과 같은 것이고, 창을
 * 읽는 동안 바닐라 드래곤이 브레스를 뿌리는 것을 막는다. ⚠ 공허 피해는 저항이 못 막는다 —
 * 그쪽은 위의 「떨어지지 않는다」가 막는 것이고, 저항은 <b>둘째 겹</b>이다.
 *
 * <h2>교착을 만들지 않는다 — 기다림이 끝나는 길이 넷이다</h2>
 *
 * <p>수락창은 취소가 없다. 곧 <b>「확인」 말고 끝나는 길이 없으면 리더 하나가 팀을 영원히
 * 묶는다.</b> {@link #outcomeOf} 가 넷으로 가른다.
 *
 * <ol>
 *   <li>{@link Outcome#ACCEPTED} — 리더가 눌렀다</li>
 *   <li>{@link Outcome#TIMED_OUT} — {@value #TIMEOUT_TICKS} 틱(60초)이 지났다. 리더가 자리를
 *       비웠거나 사망 화면을 보고 있어 창을 못 받은 경우가 여기로 온다</li>
 *   <li>{@link Outcome#LEADERLESS} — 리더가 <b>접속을 끊었거나 팀에서 빠졌다.</b> 누를 사람이
 *       없는 것이 확실하므로 {@value #LEADERLESS_TICKS} 틱만 더 기다린다</li>
 *   <li>{@link Outcome#ABANDONED} — 팀 전원이 접속을 끊었다. 이때만 <b>전투를 열지 않는다</b></li>
 * </ol>
 *
 * <p>⚠ 2·3 은 <b>수락한 것과 똑같이</b> 진행한다. 되돌리는 쪽으로 두면 엔드에 들어간 사람이
 * 전투도 안 열린 엔드에 혼자 남고, 공유 체력이라 나머지는 다른 차원에서 체력만 깎이는 것을
 * 구경하게 된다 — {@code DragonTrialManager} 의 「왜 전원을 부르는가」가 그대로 성립한다.
 * 엔드 입장은 원래 돌아올 수 없는 문턱이고, 창은 <b>알리는 것</b>이지 말리는 장치가 아니다.
 *
 * <h2>리더가 엔드에 없어도 된다</h2>
 *
 * <p>창은 <b>접속한 팀원 전원</b>에게 가고 차원을 가리지 않는다. 그래서 네더에서 캐고 있던 리더도
 * 그 자리에서 누를 수 있다 — 「리더가 엔드에 못 들어왔다」가 교착이 되지 않는 까닭이 이것이다.
 * 누를 수 있는 사람은 리더 하나뿐이고, 그 판단은 <b>서버에만</b> 있다({@link #mayConfirm}).
 *
 * <h2>서버를 껐다 켜면 묶인 채로 남지 않는다 — 저장하지 않는다</h2>
 *
 * <p>이 상태는 <b>파일에 한 글자도 안 남는다.</b> {@code DragonTrialStore} 에 칸을 더하지 않은
 * 것이 의도다.
 *
 * <ul>
 *   <li>붙들기는 <b>매 틱 다시 거는 것</b>이라 되돌릴 것이 없다. 서버가 죽으면 그 틱부터 아무도
 *       안 붙들려 있다 — {@link TrialEntrance} 가 「상태를 하나도 들지 않는다」로 같은 성질을
 *       고른 자리다</li>
 *   <li>재시작하고 그 사람이 아직 엔드에 서 있으면 {@code DragonTrialManager.detectArrival} 이
 *       <b>다시</b> 창을 띄운다. 「첫 입장」을 다시 보는 것이지 묶여 있는 것이 아니다</li>
 *   <li>⚠ 전투가 이미 열린 뒤에 재시작했으면 {@code DragonTrialStore} 가 세션을 되살리므로
 *       창이 뜨지 않는다 — {@code detectArrival} 이 세션이 있는 팀을 통째로 건너뛴다</li>
 * </ul>
 *
 * <h2>늦게 들어온 사람에게는 뜨지 않는다</h2>
 *
 * <p>「첫 입장」만이다. {@code detectArrival} 이 <b>세션 · 소환 대기 · 이 창</b> 셋 가운데
 * 하나라도 있으면 건너뛰므로, 전투가 열린 뒤에 들어온 사람은 이 파일에 닿지 않는다.
 */
public final class TrialEntranceGate {

	/**
	 * 리더가 접속해 있는데도 누르지 않을 때 기다리는 전체 길이(틱). <b>60초.</b>
	 *
	 * <p>{@code PerkChoiceSession.TIMEOUT_TICKS} 와 같은 값이다 — 그쪽도 「사람이 창을 보고 하나를
	 * 누르기까지」를 재는 자리이고, 두 창이 같은 박자로 기다리면 사람이 기다림의 길이를 한 번만
	 * 배운다. <b>상수를 빌려 오지는 않았다</b> — 증강 선택의 제한시간을 고치는 사람이 엔드 입장의
	 * 기다림까지 함께 고치는 것은 그가 의도한 일이 아니다.
	 */
	public static final int TIMEOUT_TICKS = 1200;

	/**
	 * 리더가 <b>접속을 끊은 뒤</b> 더 기다리는 틱. 3초.
	 *
	 * <p>누를 사람이 없는 것이 확실하므로 {@link #TIMEOUT_TICKS} 을 다 쓸 이유가 없다. 그래도 0 이
	 * 아닌 것은 <b>남은 사람이 왜 진행되는지 읽을 틈</b>을 주려는 것이다 —
	 * {@code DragonTrialManager.SUMMON_DELAY_TICKS}(60틱)가 「예고이지 말리는 장치가 아니다」로
	 * 쓰는 것과 같은 몫이다.
	 */
	static final int LEADERLESS_TICKS = 60;

	/**
	 * 붙들어 둔 자리에서 이만큼 벗어나면 되돌려 놓는다(블록).
	 *
	 * <p>1 이하로 두면 서 있기만 해도 되돌리는 꾸러미가 나간다(발밑 블록의 미세한 밀림·탈것).
	 * 크게 두면 떨어지는 사람이 그만큼 내려간 뒤에야 걸린다. 1.5 는 <b>한 칸 턱</b>보다 넓고
	 * <b>낙하 피해가 시작되는 3칸</b>보다 좁다.
	 */
	static final double DRIFT_TOLERANCE = 1.5;

	/** 붙들린 사람에게 거는 저항 V 의 길이(틱). */
	static final int GRACE_TICKS = 40;

	/**
	 * 저항을 다시 거는 간격(틱). 1초.
	 *
	 * <p>매 틱 걸면 효과 꾸러미가 사람마다 매 틱 나간다. 반대로 창 전체 길이(60초)를 한 번에
	 * 걸면 <b>리더가 1초 만에 눌렀을 때 저항 V 가 59초 더 남아</b> 전투 시작부터 무적이 된다.
	 * 그래서 짧게 걸고 자주 갱신한다 — 놓이는 꾸러미는 초당 한 장이고, 풀린 뒤에 남는 것은
	 * 길어도 {@value #GRACE_TICKS} 틱이다. 그 뒤는 소환이 거는 도착 무적이 이어받는다.
	 */
	static final int GRACE_REFRESH_TICKS = 20;

	/**
	 * 수락창을 다시 보내는 간격(틱). 1초.
	 *
	 * <p>⚠ <b>화면이 지금 안 열릴 수 있다.</b> 사망 화면이나 증강 선택창이 떠 있으면 클라이언트가
	 * 수락창을 열지 않고 버린다(그 둘을 밀어내면 안 된다). 한 번만 보내면 그 사람은 창을 영영
	 * 못 보고, 그 사람이 리더였으면 60초를 기다린 끝에 시간 초과로 진행된다.
	 *
	 * <p>{@code TrialHotbarLockPayload} 가 같은 수법을 쓴다 — 「서버는 1초마다 다시 보낸다」.
	 * 같은 창이 이미 떠 있으면 클라이언트가 <b>열려 있는 창을 그대로 둔다</b>
	 * ({@code TrialEntranceScreen} 이 {@code openedTick} 으로 같은 창인지 가린다).
	 */
	static final int OFFER_RESEND_TICKS = 20;

	/** 기다림이 끝나는 길. {@link #outcomeOf} 가 고른다. */
	public enum Outcome {
		/** 아직 기다린다. */
		WAIT,
		/** 리더가 확인을 눌렀다. */
		ACCEPTED,
		/** 시간이 다 됐다. <b>수락한 것과 똑같이</b> 진행한다. */
		TIMED_OUT,
		/** 리더가 접속을 끊었거나 팀에서 빠졌다. <b>수락한 것과 똑같이</b> 진행한다. */
		LEADERLESS,
		/** 팀 전원이 접속을 끊었다. 이때만 전투를 열지 않는다. */
		ABANDONED
	}

	/** 붙들어 둔 자리. <b>월드 객체를 들지 않는다</b> — 정적 지도가 월드를 붙잡고 있으면 안 된다. */
	private record Anchor(ResourceKey<Level> dimension, double x, double y, double z) {
	}

	/** 창 하나. 팀마다 하나뿐이다. 팀 id 는 {@link #OPEN} 의 열쇠라 여기 다시 들지 않는다. */
	private static final class Open {
		/**
		 * 창을 연 시각이자 <b>이 창의 이름표.</b> 확인 패킷이 이 값을 되돌려 보내야 받아들여진다.
		 */
		final long openedTick;
		/** 열 때의 리더. 도중에 팀 리더가 바뀌어도 <b>창에 적어 보낸 사람</b>과 어긋나지 않는다. */
		final UUID leaderId;
		/**
		 * 엔드 단상의 자리. 열 때 한 번 잡는다.
		 *
		 * <p>허공에 선 사람의 붙들 자리로 쓴다. 매 틱 다시 묻지 않는 것은 하이트맵 조회가 공짜가
		 * 아니고, 창이 떠 있는 60초 사이에 기반암 단상이 사라질 길이 없기 때문이다.
		 */
		final Vec3 podium;
		final Map<UUID, Anchor> anchors = new HashMap<>();
		/** 리더가 마지막으로 접속해 있던 시각. {@link Outcome#LEADERLESS} 를 여기서 센다. */
		long leaderSeenTick;
		long nextOfferTick;
		long nextGraceTick;
		boolean accepted;

		Open(long openedTick, UUID leaderId, Vec3 podium) {
			this.openedTick = openedTick;
			this.leaderId = leaderId;
			this.podium = podium;
			this.leaderSeenTick = openedTick;
			this.nextOfferTick = openedTick + OFFER_RESEND_TICKS;
			// 열 때 이미 한 번 걸었다. 여기에 openedTick 을 그대로 두면 다음 틱에 또 건다.
			this.nextGraceTick = openedTick + GRACE_REFRESH_TICKS;
		}
	}

	private static final Map<UUID, Open> OPEN = new HashMap<>();

	private TrialEntranceGate() {
	}

	// ------------------------------------------------------------------ 조회

	/** 이 팀의 수락창이 떠 있는가. {@code detectArrival} 이 「또 띄우지 않는다」로 쓴다. */
	public static boolean isOpen(@Nullable UUID teamId) {
		return teamId != null && OPEN.containsKey(teamId);
	}

	/** 지금 창이 떠 있는 팀들. 도는 동안 지워도 되게 사본을 준다. */
	public static List<UUID> openTeams() {
		return new ArrayList<>(OPEN.keySet());
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 되돌릴 것이 없으므로 버리기만 한다(클래스 설명). */
	public static void clearState() {
		OPEN.clear();
	}

	/**
	 * 팀이 사라졌다. 창을 <b>닫는 꾸러미도 없이</b> 버린다.
	 *
	 * <p>팀을 해체하면 {@code TeamManager.teamById} 가 {@code null} 이 되어 더는 명단을 알 수
	 * 없다. 보낼 사람을 모르니 닫기 꾸러미도 못 보내지만, 화면은 제 시계로 스스로 닫는다
	 * ({@link #TIMEOUT_TICKS}) — 닫기 꾸러미 하나에 사람이 갇히지 않게 두 겹으로 둔 값이
	 * 여기서 쓰인다.
	 *
	 * <p>이것이 없으면 해체된 팀의 창이 <b>영원히 남아</b> 그 팀 id 로 전투가 다시 열리지 않는다.
	 */
	public static void abandon(@Nullable UUID teamId) {
		if (teamId != null) {
			OPEN.remove(teamId);
		}
	}

	/**
	 * ⚠⚠ <b>창을 접고 붙들기를 푼다.</b> 기다림의 결과가 아니라 <b>밖에서 접는</b> 길이다.
	 *
	 * <h2>2026-10-01 — 드래곤을 잡은 뒤에도 붙들려 있었다</h2>
	 *
	 * <p>{@link #abandon} 과 갈라져 있는 것이 뜻이다. 그쪽은 <b>팀이 사라져 보낼 사람조차
	 * 없을 때</b>라 꾸러미를 못 보내고 상태만 버린다. 이쪽은 <b>팀은 그대로인데 창이 뜻을
	 * 잃었을 때</b>다 — 창이 떠 있는 사이에 드래곤이 죽은 경우가 그것이고, 그냥 두면
	 * {@link #tick} 이 「확인·시간 초과·리더 이탈·전원 종료」 가운데 하나가 될 때까지
	 * <b>최대 {@value #TIMEOUT_TICKS} 틱(60초)을 더 붙들어 둔다.</b>
	 *
	 * <p>무엇이 풀리는지가 중요하다. <b>붙들기는 되돌릴 것이 없다</b> — 매 틱 다시 거는
	 * 것이고({@link #hold}), {@link #OPEN} 에서 빠지는 순간 {@link #tick} 이 그 팀을 통째로
	 * 건너뛰므로 다음 틱부터 아무도 안 붙들려 있다. 남는 것은 저항 V 한 장이고 그것은
	 * {@value #GRACE_TICKS} 틱 뒤에 저절로 꺼진다.
	 *
	 * <p>남의 화면을 닫는 길은 서버뿐이라({@link TrialEntranceClosePayload}) 닫기 꾸러미를
	 * 함께 보낸다. ⚠ 꾸러미가 유실돼도 화면은 제 시계로 스스로 닫는다 — 그 두 겹이
	 * {@code TrialEntranceScreen} 에 적혀 있다.
	 *
	 * @param members 접속한 팀원 전부. 비어 있어도 된다 — 상태는 어차피 버린다
	 */
	public static void cancel(@Nullable UUID teamId, @Nullable List<ServerPlayer> members) {
		if (teamId == null) {
			return;
		}
		close(teamId, members == null ? List.of() : members);
	}

	// ------------------------------------------------------------------ 열기

	/**
	 * 창을 연다. 열었으면 참.
	 *
	 * <p>거짓을 돌려주는 경우가 둘이고 <b>둘 다 「창 없이 바로 소환한다」로 이어진다.</b>
	 *
	 * <ul>
	 *   <li><b>접속한 팀원이 없다</b> — 보낼 사람이 없다</li>
	 *   <li><b>리더가 접속해 있지 않다</b> — 누를 사람이 없는 창을 띄우면 {@value #TIMEOUT_TICKS}
	 *       틱을 기다린 끝에 시간 초과로 진행된다. 그 60초는 아무 값어치가 없으므로
	 *       <b>창을 아예 띄우지 않는다</b></li>
	 * </ul>
	 *
	 * @param end     엔드 월드. 허공에 선 사람의 붙들 자리를 단상으로 바꿀 때 쓴다
	 * @param dragon  살아 있는 드래곤. 단상 자리를 {@link TrialPodium} 이 이것으로 잡는다
	 * @param members <b>접속한 팀원 전부.</b> 차원을 가리지 않는다 — 리더가 네더에 있어도 누를 수
	 *                있어야 한다
	 * @param now     지금 게임 시각. {@code getGameTime()} 을 여기서 다시 묻지 않는다
	 */
	public static boolean open(@Nullable ServerLevel end, @Nullable ShareTeam team,
			@Nullable EnderDragon dragon, @Nullable List<ServerPlayer> members, long now) {
		if (end == null || team == null || members == null || members.isEmpty()) {
			return false;
		}
		UUID teamId = team.teamId();
		if (OPEN.containsKey(teamId)) {
			return false;
		}
		UUID leaderId = team.leader();
		if (leaderId == null || !online(members, leaderId)) {
			SharedFateMod.LOGGER.info(
					"[END] 팀 '{}' 입장 수락창을 띄우지 않습니다 — 리더가 접속해 있지 않습니다."
							+ " 창 없이 바로 소환합니다", team.name());
			return false;
		}

		MinecraftServer server = end.getServer();
		Open gate = new Open(now, leaderId, TrialPodium.locate(end, dragon));
		for (ServerPlayer member : members) {
			Anchor anchor = anchorFor(server, gate.podium, member);
			gate.anchors.put(member.getUUID(), anchor);
			// 허공에 선 사람만 여기서 한 번 옮겨진다. 나머지는 선 자리가 그대로 붙들 자리다.
			hold(server, member, anchor);
		}
		OPEN.put(teamId, gate);
		sendOffer(team, members, gate);
		grace(members);
		TrialWarning.playEach(end, members, SoundEvents.PORTAL_TRIGGER, 0.8F, 0.7F);
		SharedFateMod.LOGGER.info(
				"[END] 팀 '{}' 엔드 입장 — 수락창을 띄우고 {}명을 붙들었습니다. 리더의 확인을 기다립니다"
						+ " (최대 {}틱)", team.name(), members.size(), TIMEOUT_TICKS);
		return true;
	}

	/**
	 * 붙들 자리.
	 *
	 * <p>딛고 선 자리를 그대로 쓰되, <b>엔드에서 허공에 있으면 단상</b>이다. 까닭은 클래스 설명의
	 * 「허공에 떨어지지 않는다」에 있다.
	 */
	private static Anchor anchorFor(@Nullable MinecraftServer server, Vec3 podium,
			ServerPlayer member) {
		Vec3 at = member.position();
		ResourceKey<Level> dimension = member.level().dimension();
		if (dimension == Level.END && server != null) {
			ServerLevel end = server.getLevel(Level.END);
			if (end != null && !TrialPodium.grounded(end, at)) {
				return new Anchor(Level.END, podium.x, podium.y, podium.z);
			}
		}
		return new Anchor(dimension, at.x, at.y, at.z);
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱. 붙들고, 창을 다시 보내고, 기다림이 끝났는지 본다.
	 *
	 * <p>⚠ <b>{@link Outcome#WAIT} 이 아닌 값을 돌려줄 때는 이 메서드가 스스로 창을 닫는다.</b>
	 * 부르는 쪽이 닫는 것을 잊을 자리를 없애려는 것이고, 그래서 같은 팀에 대해 두 번 진행되는
	 * 길이 없다.
	 *
	 * @param members 접속한 팀원 전부
	 * @return 기다림의 결과. 창이 없으면 {@link Outcome#WAIT}
	 */
	public static Outcome tick(@Nullable MinecraftServer server, @Nullable ShareTeam team,
			@Nullable List<ServerPlayer> members, long now) {
		if (server == null || team == null) {
			return Outcome.WAIT;
		}
		Open gate = OPEN.get(team.teamId());
		if (gate == null) {
			return Outcome.WAIT;
		}
		List<ServerPlayer> online = members == null ? List.of() : members;
		boolean leaderOnline = team.contains(gate.leaderId) && online(online, gate.leaderId);
		if (leaderOnline) {
			gate.leaderSeenTick = now;
		}

		Outcome outcome = outcomeOf(!online.isEmpty(), gate.accepted,
				TrialRisks.elapsedSinceGrant(now, gate.openedTick),
				TrialRisks.elapsedSinceGrant(now, gate.leaderSeenTick),
				TIMEOUT_TICKS, LEADERLESS_TICKS);
		if (outcome != Outcome.WAIT) {
			close(team.teamId(), online);
			return outcome;
		}

		for (ServerPlayer member : online) {
			Anchor anchor = gate.anchors.get(member.getUUID());
			// 둘 중 하나면 기준을 지금 자리로 다시 잡는다.
			//
			//   · 아예 없다 — 창이 뜬 뒤에 접속한 팀원이다. 안 붙들면 그 사람만 혼자 움직이다가
			//     확인과 함께 단상으로 끌려간다
			//   · ⚠⚠ 차원이 달라졌다 — 우리가 아니라 남이 옮긴 것이다. 「운명 공동체」가 차원이
			//     갈린 팀을 초당 한 번 모으고, EndFightTeleportLock.preferEndAnchor 가 그 목적지를
			//     엔드 쪽으로 돌린다. 되돌려 놓으면 그쪽이 다음 초에 또 끌어와 사람이 두 자리
			//     사이에서 떨린다. 붙들기의 일은 「남과 다투는 것」이 아니라 「제자리에서 떨어지지
			//     않게 하는 것」이므로 새 자리를 받아들인다 — 엔드 허공이면 anchorFor 가 단상으로
			//     돌려 주므로 공허 낙사 보호도 그대로다
			if (anchor == null || anchor.dimension() != member.level().dimension()) {
				anchor = anchorFor(server, gate.podium, member);
				gate.anchors.put(member.getUUID(), anchor);
			}
			hold(server, member, anchor);
		}
		if (now >= gate.nextGraceTick) {
			gate.nextGraceTick = now + GRACE_REFRESH_TICKS;
			grace(online);
		}
		if (now >= gate.nextOfferTick) {
			gate.nextOfferTick = now + OFFER_RESEND_TICKS;
			sendOffer(team, online, gate);
		}
		return Outcome.WAIT;
	}

	/**
	 * 기다림이 끝났는지, 끝났으면 어떻게 끝났는지. <b>월드를 하나도 모른다 — 시험이 직접 굴린다.</b>
	 *
	 * <p>차례가 뜻을 가진다.
	 *
	 * <ol>
	 *   <li><b>아무도 없는 것</b>이 먼저다. 보여 줄 사람도 옮길 사람도 없는데 전투를 열면 18.5초
	 *       연출이 빈 엔드에서 돈다</li>
	 *   <li><b>눌린 것</b>이 시간보다 먼저다. 같은 틱에 둘이 겹치면 사람이 누른 쪽으로 읽혀야
	 *       로그가 거짓말을 하지 않는다</li>
	 *   <li><b>시간 초과</b>가 리더 이탈보다 먼저다. 리더가 나간 채로 60초가 지났으면 두 조건이
	 *       모두 참인데, 그때 더 오래된 사실은 시간 초과다</li>
	 * </ol>
	 *
	 * @param anyOnline        접속한 팀원이 하나라도 있는가
	 * @param accepted         리더가 확인을 눌렀는가
	 * @param heldTicks        창이 열린 뒤 흐른 틱
	 * @param sinceLeaderSeen  리더를 마지막으로 본 뒤 흐른 틱. 접속해 있으면 0
	 * @param timeoutTicks     전체 제한시간
	 * @param leaderlessTicks  리더가 없을 때의 짧은 기한
	 */
	static Outcome outcomeOf(boolean anyOnline, boolean accepted, long heldTicks,
			long sinceLeaderSeen, int timeoutTicks, int leaderlessTicks) {
		if (!anyOnline) {
			return Outcome.ABANDONED;
		}
		if (accepted) {
			return Outcome.ACCEPTED;
		}
		if (heldTicks >= timeoutTicks) {
			return Outcome.TIMED_OUT;
		}
		if (sinceLeaderSeen >= leaderlessTicks) {
			return Outcome.LEADERLESS;
		}
		return Outcome.WAIT;
	}

	// ------------------------------------------------------------------ 확인

	/**
	 * 「확인」이 눌렸다. {@link TrialEntranceAcceptC2SPayload} 의 받는 자리다.
	 *
	 * <p><b>여기서 전투를 열지 않는다.</b> 깃발만 세우고 실제 진행은 다음 틱의 {@link #tick} 이
	 * 한다. 네트워크 수신기는 서버 본 스레드에서 돌지만, 팀을 순간이동시키고 세션을 여는 일이
	 * <b>세션 루프 바깥</b>에서 벌어지면 그 틱의 {@code DragonTrialManager} 가 반쯤 열린 상태를
	 * 보게 된다. 진행하는 자리를 한 곳에 모아 두면 그 길이 없다.
	 *
	 * <p>버리는 경우가 넷이고 <b>조용히</b> 버리지는 않는다 — 리더가 아닌 사람이 누른 것만은
	 * 당사자에게 알린다. 화면이 단추를 안 그려 주므로 정상 경로로는 오지 않지만, 패킷은 화면
	 * 없이도 보낼 수 있고 「눌렀는데 아무 일도 없다」는 가장 알아채기 어려운 고장이다.
	 */
	public static void accept(@Nullable ServerPlayer player, long openedTick) {
		if (player == null) {
			return;
		}
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return;
		}
		ShareTeam team = TeamManager.get(server).teamOf(player.getUUID());
		if (team == null) {
			return;
		}
		Open gate = OPEN.get(team.teamId());
		// 지난 창의 늦은 확인이 다음 창을 그 자리에서 눌러 버리는 것을 막는다.
		if (gate == null || gate.openedTick != openedTick) {
			return;
		}
		if (!mayConfirm(player.getUUID(), gate.leaderId, player.isSpectator())) {
			player.sendSystemMessage(Component
					.literal("시련 시작은 팀 리더만 확인할 수 있습니다.")
					.withStyle(ChatFormatting.LIGHT_PURPLE));
			return;
		}
		if (gate.accepted) {
			return;
		}
		gate.accepted = true;
		SharedFateMod.LOGGER.info("[END] 팀 '{}' 리더 {} 가 시련 시작을 확인했습니다",
				team.name(), player.getPlainTextName());
	}

	/**
	 * 이 사람이 확인을 누를 수 있는가. <b>리더 하나뿐이다.</b>
	 *
	 * <p>관전자는 뺀다 — 판에 끼어들지 않는 사람이고, 이 저장소의 다른 판정이 전부 같은 거름망을
	 * 쓴다({@code TrialWarning.playEach} 설명의 열일곱 자리). ⚠ 리더가 관전 중이면 누를 사람이
	 * 없어지지만 교착이 되지는 않는다 — {@value #TIMEOUT_TICKS} 틱 뒤에 진행된다.
	 *
	 * <p>월드를 모르는 순수 함수라 시험이 직접 굴린다. 「리더만」이 조용히 「누구나」로 바뀌는 것이
	 * 눈으로는 안 보이는 종류의 고장이다.
	 */
	static boolean mayConfirm(@Nullable UUID clicker, @Nullable UUID leaderId, boolean spectator) {
		if (clicker == null || leaderId == null || spectator) {
			return false;
		}
		return clicker.equals(leaderId);
	}

	// ------------------------------------------------------------------ 닫기

	/**
	 * 창을 닫는다. 상태를 버리고 <b>남의 화면도 닫는다.</b>
	 *
	 * <p>누른 사람의 화면은 제 손으로 닫히지만 나머지 셋의 화면을 닫는 길은 서버뿐이다
	 * ({@link TrialEntranceClosePayload}).
	 */
	private static void close(UUID teamId, List<ServerPlayer> members) {
		Open gate = OPEN.remove(teamId);
		if (gate == null) {
			return;
		}
		TrialEntranceClosePayload payload = new TrialEntranceClosePayload(gate.openedTick);
		for (ServerPlayer member : members) {
			ServerPlayNetworking.send(member, payload);
		}
	}

	// ------------------------------------------------------------------ 붙들기

	/**
	 * 한 사람을 자리에 못박는다.
	 *
	 * <p>세 가지를 한다 — <b>낙하 거리 되돌리기 · 속도 0 · 벗어났으면 되돌려 놓기</b>.
	 *
	 * <p>⚠ <b>매 틱 순간이동시키지 않는다.</b> 자리 꾸러미를 매 틱 보내면 클라이언트 쪽 보간이
	 * 떨리고, 사람 수만큼 곱해진다. {@value #DRIFT_TOLERANCE} 칸을 넘었을 때만 되돌리므로
	 * 가만히 선 사람에게는 꾸러미가 한 장도 안 나간다.
	 *
	 * <p>⚠ <b>속도 0 만으로는 모자라다.</b> 플레이어의 움직임은 클라이언트가 계산해 보내 주는
	 * 것이라 서버가 {@code setDeltaMovement} 로 중력을 끌 수 없다. 실제로 떨어지는 것을 막는 것은
	 * 되돌려 놓기이고, 속도 0 은 되돌린 틱에 <b>쌓여 있던 낙하 속도가 이어지지 않게</b> 하는 것이다.
	 *
	 * <p>관전자는 붙들지 않는다. 판에 끼어들지 않는 사람이라 어디에 있어도 팀이 손해를 보지 않고,
	 * 관전 중에 자리가 못박히면 보려던 것을 못 본다.
	 *
	 * <p>⚠ <b>자리의 차원이 그 사람의 차원과 같다는 것을 전제한다.</b> 거리만 보고 가르므로 차원이
	 * 다른데 좌표가 가까우면 안 되돌린다. 부르는 자리가 둘뿐이고 둘 다 바로 앞에서
	 * {@link #anchorFor} 로 차원을 맞춰 두므로 그 길이 없다 — {@link #tick} 은 차원이 달라진 것을
	 * 보면 기준을 다시 잡는다.
	 */
	private static void hold(@Nullable MinecraftServer server, ServerPlayer member, Anchor anchor) {
		if (server == null || member.isSpectator()) {
			return;
		}
		member.resetFallDistance();
		member.setDeltaMovement(Vec3.ZERO);
		if (member.position().distanceToSqr(anchor.x(), anchor.y(), anchor.z())
				<= DRIFT_TOLERANCE * DRIFT_TOLERANCE) {
			return;
		}
		ServerLevel level = server.getLevel(anchor.dimension());
		if (level == null) {
			return;
		}
		// 보고 있던 방향은 그대로 둔다. 창을 읽는 동안 화면이 돌아가면 멈춘 것으로 안 읽힌다.
		member.teleportTo(level, anchor.x(), anchor.y(), anchor.z(), Set.of(),
				member.getYRot(), member.getXRot(), false);
	}

	/** 붙들린 사람에게 저항 V 를 다시 건다. {@code summonTeam} 의 도착 무적과 같은 것이다. */
	private static void grace(List<ServerPlayer> members) {
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			member.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, GRACE_TICKS, 4,
					false, false, true));
		}
	}

	// ------------------------------------------------------------------ 보내기

	/**
	 * 수락창을 보낸다. <b>「누를 수 있는가」를 사람마다 서버가 정해서 싣는다.</b>
	 *
	 * <p>남은 시간을 싣지 않고 <b>전체 길이</b>를 싣는다. 화면은 받은 틱부터 스스로 세고
	 * ({@code TrialEntranceScreen}), 1초마다 다시 보내는 꾸러미는 <b>이미 떠 있는 창을 건드리지
	 * 않는다</b> — 남은 시간을 실으면 다시 보낼 때마다 화면의 시계가 튄다.
	 */
	private static void sendOffer(ShareTeam team, List<ServerPlayer> members, Open gate) {
		String leaderName = nameOf(members, gate.leaderId);
		for (ServerPlayer member : members) {
			ServerPlayNetworking.send(member, new TrialEntranceOfferPayload(
					gate.openedTick, team.name(), leaderName,
					mayConfirm(member.getUUID(), gate.leaderId, member.isSpectator()),
					TIMEOUT_TICKS));
		}
	}

	/** 접속한 사람들 가운데 그 사람의 이름. 못 찾으면 빈 글자다 — 화면이 그 줄을 지운다. */
	private static String nameOf(List<ServerPlayer> members, @Nullable UUID playerId) {
		ServerPlayer found = find(members, playerId);
		return found == null ? "" : found.getPlainTextName();
	}

	/**
	 * 그 사람이 지금 접속해 있는가.
	 *
	 * <p>⚠ {@link #nameOf} 가 빈 글자인지로 보지 않는다. 이름이 빈 사람은 없어야 하지만, 「이름이
	 * 비었다」와 「접속해 있지 않다」가 같은 값이 되면 이름을 못 읽은 한 사람 때문에 <b>리더가
	 * 없는 것으로 읽혀</b> 창이 3초 뒤에 저절로 넘어간다.
	 */
	private static boolean online(List<ServerPlayer> members, @Nullable UUID playerId) {
		return find(members, playerId) != null;
	}

	private static @Nullable ServerPlayer find(List<ServerPlayer> members,
			@Nullable UUID playerId) {
		if (playerId == null) {
			return null;
		}
		for (ServerPlayer member : members) {
			if (member.getUUID().equals(playerId)) {
				return member;
			}
		}
		return null;
	}
}
