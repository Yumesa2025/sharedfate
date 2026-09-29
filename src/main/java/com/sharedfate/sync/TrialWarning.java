package com.sharedfate.sync;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * 시련 패턴이 공유하는 경고 신호.
 *
 * <h2>왜 하나로 모으는가</h2>
 *
 * <p>패턴마다 경고를 따로 만들면 플레이어가 패턴 수만큼 새 신호를 배워야 한다. 조사한 모드 중
 * 가장 잘 된 것은 <b>모든 패턴이 같은 경고를 쓰고</b>, 플레이어는 그 하나만 배우면 되게 해
 * 두었다. 여기에 모아 두면 패턴을 늘릴 때 예고가 공짜로 따라온다.
 *
 * <h2>통신 규약을 올리지 않는다</h2>
 *
 * <p>파티클과 소리는 전부 바닐라 패킷이다. 모드를 안 깐 사람에게도 보이고, 시련 선택 화면을
 * 만들기 전에도 패턴을 실제로 굴려 볼 수 있다.
 *
 * <h2>두 갈래로 쌓는다 — 예전에는 셋이었다</h2>
 *
 * <p>한쪽을 놓쳐도 다른 쪽에서 잡히게 한다. 월드가 삭제되는 게임에서 「못 봤다」는 설계
 * 실패다. 지금 플레이어에게 닿는 갈래는 <b>둘</b>이다.
 *
 * <ul>
 *   <li><b>소리</b>({@link #sound}) — 화면을 안 보고 있어도 들린다</li>
 *   <li><b>바닥 표식</b>({@link #markGround}) — 「어디인가」를 말할 수 있는 유일한 갈래</li>
 * </ul>
 *
 * <p>셋째 갈래는 액션바 자막이었고 <b>지금은 나가지 않는다.</b> 사람이 「일단 없애」라고 해서
 * {@link #shout} 를 부르는 곳을 전부 걷어냈다 — 메서드는 남아 있지만 아무도 부르지 않는다.
 * 그러니 이 설명을 읽고 「자막도 뜨겠거니」 하고 카드를 설계하지 말 것. <b>「무엇이 언제
 * 오는가」를 소리와 표식 둘만으로 말해야 한다.</b>
 *
 * <p>그 두 갈래가 시간을 따라 세 단계로 세진다({@link Stage}). 예전에는 마지막 단계에 자막이
 * 함께 나갔다.
 *
 * <ol>
 *   <li>{@link Stage#APPROACH} — 저음. 「뭔가 온다」</li>
 *   <li>{@link Stage#MARK} — 바닥 고리 + 중음. 「이게 온다, 여기로 온다」</li>
 *   <li>{@link Stage#IMMINENT} — 고음. 「지금 나가라」</li>
 * </ol>
 *
 * <h2>바닥에 그린다</h2>
 *
 * <p>{@link AuraRing} 은 「범위가 켜져 있다」를 보여 주려고 눈높이에 띄우지만, 경고는
 * <b>서 있으면 안 되는 자리</b>를 가리키므로 지면에 붙어야 한다. 엔드 섬은 평평해서 파묻힐
 * 걱정이 적다.
 *
 * <h2>거리 제한을 끄고 보낸다</h2>
 *
 * <p><b>이 깃발을 끄면 경고가 조용히 사라진다.</b> 「파티클인데 왜 깃발이 필요한가」로 되돌리지
 * 말 것. 26.3 의 바이트코드가 이렇다.
 *
 * <ul>
 *   <li>서버 — {@code sendParticles} 의 <b>짧은 형태</b>는 {@code overrideLimiter=false} 로
 *       위임하고, 비공개 오버로드가
 *       {@code player.blockPosition().closerToCenterThan(점, limiter ? 512 : 32)} 로 거른다.
 *       <b>32 블록 밖이면 패킷이 아예 나가지 않는다.</b></li>
 *   <li>클라이언트 — {@code ClientLevel.doAddParticle} 이 같은 깃발을 다시 본다.
 *       {@code force} 가 거짓이면 {@code camera.distanceToSqr > 1024}(=32 블록)에서 그냥
 *       돌아간다. 즉 서버를 통과해도 <b>한 번 더 걸린다.</b></li>
 * </ul>
 *
 * <p>엔드 전투는 이 32 블록을 우습게 넘긴다. 아레나 반경이 40 이라 팀원 둘이 80 블록 떨어질 수
 * 있고, 기둥 꼭대기는 바닥에서 13~40 블록 위다. 「누가 어디를 밟으면 안 되는가」는 <b>밟는 사람이
 * 아니라 나머지 셋이 봐야</b> 하는 정보이므로, 바닥 표식이라고 가까운 것이 아니다.
 *
 * <p>대가는 패킷 수뿐이다 — 차원 안 전원에게 나간다. 팀이 많아야 넷이라 네 배가 상한이고,
 * 그래서 점 개수에 상한을 두는 것이 이 깃발과 한 쌍이다({@link #MAX_POINTS}).
 */
public final class TrialWarning {
	/**
	 * 고리 한 바퀴의 기본 점 수.
	 *
	 * <p>하한으로 둔다. 지금 카드가 쓰는 반경(1.5~3)에서는 이 값이 그대로 나오므로 <b>보이는
	 * 모습이 예전과 같다.</b>
	 */
	private static final int BASE_POINTS = 40;
	/**
	 * 이웃한 두 점 사이 허용 간격(블록).
	 *
	 * <p>개수를 고정해 두면 반경이 커질수록 둘레만 늘어 <b>점 사이가 벌어진다.</b> 반경 3 에서
	 * 0.47 블록이던 간격이 반경 10 이면 1.57 블록이 되고, 그쯤이면 고리가 아니라 흩뿌려진 점으로
	 * 읽혀 「경계가 어디인가」를 못 준다. 그래서 개수가 아니라 <b>간격</b>을 정하고 개수를 거기서
	 * 뽑는다.
	 */
	static final double POINT_GAP = 0.5;
	/**
	 * 한 고리에 찍는 점 수의 상한.
	 *
	 * <p>간격만 정하고 두면 점이 반경에 비례해 끝없이 는다. 점 하나가 패킷 한 장이고
	 * {@link #markGround} 는 <b>매 틱</b> 불리며, 이제 거리 제한 없이 <b>전원에게</b> 나간다 —
	 * 상한이 없으면 큰 반경 카드 하나가 파티클 패킷만으로 틱을 민다. 상한에 걸리면 간격이 벌어질
	 * 뿐 고리는 남는다.
	 */
	static final int MAX_POINTS = 80;
	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다. */
	private static final double GROUND_OFFSET = 0.15;

	/**
	 * 요구하는 행동별 최소 예고 시간(틱).
	 *
	 * <p>사람의 지각·판단·입력에만 0.25초가 든다. 여기에 대응 행동의 시간과 네 명이 서로를 보고
	 * 맞추는 시간을 더한 값이다. 실제 플레이로 보정해야 하는 <b>출발점</b>이지 확정값이 아니다.
	 */
	public static final int TICKS_SIDESTEP = 30;
	/** 지정한 자리로 옮겨야 할 때. */
	public static final int TICKS_REPOSITION = 50;
	/** 네 명이 서로 다른 곳으로 흩어져야 할 때. 합의할 시간이 필요하다. */
	public static final int TICKS_SCATTER = 80;

	/** 경고의 세 층. */
	public enum Stage {
		APPROACH,
		MARK,
		IMMINENT
	}

	/**
	 * 색 규약. 같은 색은 언제나 같은 뜻이어야 플레이어가 표식을 언어로 배운다.
	 *
	 * <p>패턴을 늘릴 때 여기 없는 색을 새로 만들지 말 것. 색이 늘어나는 순간 규약이 아니게 된다.
	 *
	 * <h2>노랑의 뜻을 한 번 바꿨다</h2>
	 *
	 * <p>노랑은 원래 「여기 서 있어야 한다」({@code REQUIRED})였다. 색이 <b>무엇을 해야 하는가</b>를
	 * 뜻한다는 원칙에서 보면 「번개가 내리친다」는 그 자리에 들어갈 말이 아니다 — 피하라는 뜻은
	 * 이미 빨강이 들고 있다. 그런데도 바꾼 이유는 둘이다.
	 *
	 * <ul>
	 *   <li>사람이 실제로 플레이하고 <b>번개 표식은 노랑이어야 한다</b>고 정했다. 빨강 고리가 아레나
	 *       열 곳에 동시에 뜨면 「자리 폭격」의 빨강과 구별되지 않아, 무엇이 떨어지는지 모른 채
	 *       빨강만 잔뜩 보인다</li>
	 *   <li>{@code REQUIRED} 를 쓰는 <b>카드가 하나도 없었다.</b> 아무도 배우지 않은 뜻을 지키느라
	 *       실제로 필요한 뜻을 못 쓰는 것은 규약이 아니라 장식이다</li>
	 * </ul>
	 *
	 * <p><b>나중에 「여기 서 있어야 함」이 필요해지면 그때 새 색을 고른다.</b> 그 자리는 지금 비어
	 * 있고, 노랑을 도로 가져가지 말 것 — 그때는 번개가 이미 노랑으로 배워져 있다.
	 */
	public static final class Colors {
		/** 서 있으면 죽는다. */
		public static final int DEADLY = 0xFF3333;
		/**
		 * 번개가 내리친다.
		 *
		 * <p>피하라는 뜻은 빨강과 같다. 색을 따로 둔 것은 <b>무엇이 떨어지는지</b>를 가르기 위해서다 —
		 * 「낙뢰」는 한 번에 열 곳이라, 「자리 폭격」·「기둥 화염구」의 빨강과 같은 색이면 화면이 빨강
		 * 고리로 뒤덮여 어느 것이 무엇인지 읽히지 않는다.
		 *
		 * <p>카드마다 이 색을 손으로 적지 말 것. {@code TrialRisks.markColor} 가
		 * {@link TrialCatalog.Risk.Impact} 에서 끌어내므로 <b>연출과 색이 어긋날 수 없다.</b>
		 */
		public static final int LIGHTNING = 0xFFD54A;
		/**
		 * 옛 이름. 값은 {@link #LIGHTNING} 과 같은 노랑이다.
		 *
		 * <p>「여기 서 있어야 한다」를 뜻했고 <b>지금은 그 뜻이 없다.</b> 남겨 둔 것은
		 * {@code TrialRouletteScreen} 이 룰렛 글자색으로 아직 이 이름을 쓰기 때문뿐이다. 그쪽은
		 * 바닥 표식이 아니라 화면 글자라 규약과 무관하다 — <b>새 표식에는 쓰지 말 것.</b>
		 *
		 * @deprecated 표식이면 {@link #LIGHTNING}, 화면 글자면 그쪽에 색을 따로 둘 것.
		 */
		@Deprecated
		public static final int REQUIRED = LIGHTNING;
		/** 밀려난다. */
		public static final int SHOVE = 0x4AA3FF;
		/** 너 하나를 노린다. */
		public static final int MARKED = 0xB44AFF;

		private Colors() {
		}
	}

	private TrialWarning() {
	}

	/**
	 * 위험한 자리를 바닥 고리로 그린다. 「서 있으면 죽는다」는 뜻의 빨강이다.
	 *
	 * <p>연출에 따라 색이 갈리는 위험은 이것을 쓰지 않고 색을 직접 넘긴다 —
	 * {@code TrialRisks.markColor} 가 {@link TrialCatalog.Risk.Impact} 에서 색을 끌어낸다.
	 *
	 * @param center 위험 지점
	 * @param radius 반경(블록)
	 */
	public static void markGround(@Nullable ServerLevel level, @Nullable Vec3 center, double radius) {
		markGround(level, center, radius, dust(Colors.DEADLY));
	}

	/** 색을 골라 먼지 파티클을 만든다. 크기 1.0 이 바닐라 레드스톤 가루와 같다. */
	public static ParticleOptions dust(int color) {
		return new DustParticleOptions(color, 1.0F);
	}

	/**
	 * 파티클을 직접 고르는 형태. 색으로 위험의 종류를 가를 때 쓴다.
	 *
	 * <p><b>첫 {@code boolean} 을 {@code false} 로 되돌리지 말 것.</b> 그것이 512 블록과 32 블록을
	 * 가르는 깃발이고, 끄는 순간 이 고리는 <b>보는 사람 발밑 32 블록 안에서만</b> 존재한다 —
	 * 서버가 패킷을 안 보내고 클라이언트도 한 번 더 거른다. 까닭은 클래스 설명의
	 * 「거리 제한을 끄고 보낸다」에 적어 두었다.
	 *
	 * <p>둘째 {@code boolean}({@code alwaysShow}) 은 「파티클 줄이기」 설정을 무시할지다. 첫
	 * 깃발이 켜져 있으면 그 검사 자체를 건너뛰므로 값이 무의미하고, 사용자의 설정을 우리가 뒤집을
	 * 이유도 없어 {@code false} 로 둔다.
	 */
	public static void markGround(@Nullable ServerLevel level, @Nullable Vec3 center, double radius,
			@Nullable ParticleOptions type) {
		if (level == null || center == null || type == null || !(radius > 0.0)) {
			return;
		}
		int points = ringPoints(radius);
		for (int index = 0; index < points; index++) {
			double angle = (Math.PI * 2.0 * index) / points;
			level.sendParticles(type, true, false,
					center.x + Math.cos(angle) * radius,
					center.y + GROUND_OFFSET,
					center.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 이 반경에 찍을 점 수.
	 *
	 * <p>둘레를 {@link #POINT_GAP} 으로 나눈 값을 {@link #BASE_POINTS}~{@link #MAX_POINTS} 로
	 * 자른다. 하한이 있어 작은 고리가 예전보다 성겨지지 않고, 상한이 있어 큰 고리가 패킷을
	 * 터뜨리지 않는다.
	 */
	static int ringPoints(double radius) {
		if (!(radius > 0.0)) {
			return 0;
		}
		int wanted = (int) Math.ceil((Math.PI * 2.0 * radius) / POINT_GAP);
		return Math.max(BASE_POINTS, Math.min(MAX_POINTS, wanted));
	}

	/**
	 * 그 반경에서 실제로 벌어지는 점 사이 거리(블록).
	 *
	 * <p>고리가 <b>고리로 읽히는가</b>를 숫자로 물을 수 있는 유일한 값이다. 점 수만 보면 상한에
	 * 걸린 큰 고리가 촘촘한 줄 알게 된다.
	 */
	static double ringGap(double radius) {
		int points = ringPoints(radius);
		if (points <= 0) {
			return 0.0;
		}
		return (Math.PI * 2.0 * radius) / points;
	}

	/**
	 * 남은 시간에 맞는 층을 고른다.
	 *
	 * <p>{@code null} 이면 아직 아무 층에도 닿지 않은 것이다. 층이 바뀌는 순간에만 소리를 내야
	 * 하므로, 호출자는 직전 층과 비교해 달라졌을 때만 {@link #sound} 를 부른다.
	 *
	 * @param remainingTicks 발동까지 남은 틱
	 */
	public static @Nullable Stage stageFor(int remainingTicks) {
		if (remainingTicks <= 14) {
			return Stage.IMMINENT;
		}
		if (remainingTicks <= 50) {
			return Stage.MARK;
		}
		if (remainingTicks <= 100) {
			return Stage.APPROACH;
		}
		return null;
	}

	/** 층에 맞는 소리를 낸다. 같은 층에서 두 번 부르면 두 번 울리므로 층이 바뀔 때만 부른다. */
	public static void sound(@Nullable ServerLevel level, @Nullable Vec3 at, Stage stage) {
		if (level == null || at == null) {
			return;
		}
		switch (stage) {
			case APPROACH -> level.playSound(null, at.x, at.y, at.z,
					SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 0.6F, 0.7F);
			case MARK -> level.playSound(null, at.x, at.y, at.z,
					SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 0.5F, 1.4F);
			case IMMINENT -> level.playSound(null, at.x, at.y, at.z,
					SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.HOSTILE, 1.0F, 1.8F);
		}
	}

	/**
	 * 액션바에 문구를 띄운다. 무엇을 해야 하는지만 짧게 적는다.
	 *
	 * <h2>지금은 아무도 부르지 않는다 — 죽은 코드가 아니다</h2>
	 *
	 * <p>사람이 「스킬 발동할 때 밑에 글 써 주는 것들도 <b>일단</b> 없애」라고 했다. 시련·패시브가
	 * 부르던 자리를 전부 걷어내 지금은 호출자가 하나도 없다. 그래도 지우지 않은 이유는 셋이다.
	 *
	 * <ul>
	 *   <li>「일단」은 되돌릴 여지를 남긴 말이다. 글자가 다시 필요해지면 여기로 돌아온다</li>
	 *   <li>공용 경고 API 의 일부다. 소리·표식과 한 자리에 있어야 다음 사람이 「경고를 어떻게
	 *       내보내는가」를 한 곳에서 본다</li>
	 *   <li>{@code TrialWarningTest} 가 <b>아무도 이 이름을 부르지 않는지</b>를 지킨다. 글자가
	 *       슬그머니 되돌아오면 그 시험이 먼저 깨진다 — 지워 버리면 그 감시도 함께 사라진다</li>
	 * </ul>
	 *
	 * <p>다시 쓰기로 한다면 클래스 설명의 「두 갈래로 쌓는다」도 함께 고칠 것.
	 */
	public static void shout(@Nullable Collection<ServerPlayer> players, @Nullable Component text) {
		if (players == null || players.isEmpty() || text == null) {
			return;
		}
		TitleMessenger.showActionBar(players, text);
	}
}
