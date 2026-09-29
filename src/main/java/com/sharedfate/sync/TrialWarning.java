package com.sharedfate.sync;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

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
 * <p>소리에는 갈래가 하나 더 있다. {@link #sound} 는 <b>위험한 자리</b>에 소리를 놓는 것이라
 * 16칸 밖에서는 안 들리는데, 아레나 반경이 40 이라 「팀 전원에게 한 번 알린다」에는 쓸 수 없다.
 * 그쪽은 {@link #playEach} 다 — <b>사람마다 그 사람에게만</b> 꾸러미를 보낸다. 사람마다
 * {@code level.playSound} 를 부르는 것으로는 안 되는 까닭이 그 메서드 설명에 적혀 있고,
 * <b>거의 모든 카드가 그 함정에 빠져 있었다.</b>
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
	 *
	 * <p>⚠ <b>이 상한은 고리 <i>하나</i>만 본다.</b> 작은 고리를 수십 개 띄우는 카드는 여기에
	 * 걸리지 않고도 한 틱 예산을 넘긴다 — 그쪽은
	 * {@link #markGround(ServerLevel, Vec3, double, ParticleOptions, int, int)} 로 나눠 그린다.
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
		markGround(level, center, radius, type, 1, 0);
	}

	/**
	 * 고리 한 바퀴를 <b>여러 틱에 나눠</b> 그리는 형태. 한 틱에 {@code stride} 개마다 하나씩만 찍고,
	 * 다음 틱에 {@code phase} 를 한 칸 옮겨 나머지를 채운다.
	 *
	 * <h2>⚠ 지금 {@code stride} 를 넘기는 호출자가 하나도 없다</h2>
	 *
	 * <p>위의 세 형태가 전부 여기로 모이지만 모두 {@code stride = 1} 이다. 그러니 <b>실제로
	 * 나눠 그리는 카드는 지금 하나도 없다.</b> 그래도 남겨 둔 이유를 적어 둔다 — 그렇지 않으면
	 * 다음 사람이 「아무도 안 쓰는데」로 지우고, 그 뒤에 같은 것을 다시 만든다.
	 *
	 * <p><b>왜 생겼는지는 이제 사실이 아니다.</b> 여기에는 「종말의 비가 한 볼리 45곳이 되면서 한
	 * 틱에 1800점이 됐다」가 존재 이유로 적혀 있었는데, <b>그 카드는 제 고리를 직접 그린다.</b>
	 * {@link #BASE_POINTS} 하한 40 이 반경 2.5 에 지나치게 촘촘해서, 남의 고리를 건드리지 않고
	 * 제 간격을 쓰려고 {@code TrialEndRain} 안으로 옮겨 갔다. 「착지 충격」도 제 것을 들고 있고,
	 * 그쪽은 <b>높이</b> 때문이다({@code TrialLandingShock.ring}).
	 *
	 * <p>남긴 근거는 둘이다.
	 *
	 * <ul>
	 *   <li><b>여기가 이 수법의 원본이고, 규칙이 적힌 유일한 자리다.</b> 아래의 「{@code stride}
	 *       는 8보다 작아야 한다」가 그것이다 — 두 카드가 제 것을 들고 나가면서도 그 상한만은
	 *       여기를 가리켜 가져갔다({@code TrialEndRain.MARK_MAX_STRIDE} ·
	 *       {@code TrialLandingShock.MAX_STRIDE}). 이 문단을 지우면 <b>「왜 8인가」가 저장소에서
	 *       사라진다</b></li>
	 *   <li><b>공용 고리에 다음 차례가 있다.</b> 작은 고리를 수십 개 띄우는 카드는
	 *       {@link #MAX_POINTS} 에 걸리지 않고도 한 틱 예산을 넘긴다. 그런 카드가 <b>제 색과 제
	 *       간격을 따로 원하지 않는다면</b> 여기가 답이고, 그때 다시 만들 것이 아니다</li>
	 * </ul>
	 *
	 * <p>⚠ 색 없이 {@code stride} 만 받던 오버로드는 <b>지웠다.</b> 부르는 곳이 한 곳도 없었고,
	 * 그것이 존재 이유로 들던 「종말의 비 1800점」이 위와 같이 사실이 아니게 되어 <b>설명도
	 * 호출자도 없는 공개 메서드</b>만 남았기 때문이다. 필요해지면 {@link #dust} 와 함께 이
	 * 형태를 부르면 그만이라 잃은 것이 없다.
	 *
	 * <h2>⚠ {@code stride} 는 8보다 작아야 한다</h2>
	 *
	 * <p>나눠 그려도 고리가 고리로 보이는 것은 <b>먼저 찍은 점이 아직 살아 있기</b> 때문이다.
	 * 26.3 {@code DustParticleBase} 의 생성자가 수명을 이렇게 잡는다.
	 *
	 * <pre>lifetime = max(1, (int)(8.0 / (random.nextDouble() * 0.8 + 0.2)) * scale)</pre>
	 *
	 * <p>{@link #dust} 가 {@code scale} 을 1.0 으로 주므로 수명은 <b>최소 8틱</b>(굴림이 1 에
	 * 가까울 때) ~ 40틱이다. 즉 {@code stride} 가 8 이상이면 한 바퀴를 다 그리기 전에 첫 점이
	 * 죽어 <b>고리가 영영 안 닫힌다.</b> 8 이하로 둘 것.
	 *
	 * <p>대가는 <b>처음 {@code stride} 틱 동안 고리가 성기다</b>는 것이다. 예고가 그보다 훨씬
	 * 길어야 뜻이 있다.
	 *
	 * @param stride 몇 틱에 나눠 한 바퀴를 채울지. 1 이하면 예전처럼 한 틱에 다 그린다
	 * @param phase  이번 틱에 그릴 몫. 보통 호출자가 받은 {@code now} 에서 뽑는다 — 매 틱
	 *               1씩 늘어야 빈자리가 순서대로 메워진다
	 */
	public static void markGround(@Nullable ServerLevel level, @Nullable Vec3 center, double radius,
			@Nullable ParticleOptions type, int stride, int phase) {
		if (level == null || center == null || type == null || !(radius > 0.0)) {
			return;
		}
		int points = ringPoints(radius);
		int step = Math.max(1, stride);
		// floorMod 라야 음수 phase 에서도 0..step-1 로 떨어진다. 되감긴 판의 now 가 음수일 수 있다.
		for (int index = Math.floorMod(phase, step); index < points; index += step) {
			double angle = (Math.PI * 2.0 * index) / points;
			level.sendParticles(type, true, false,
					center.x + Math.cos(angle) * radius,
					center.y + GROUND_OFFSET,
					center.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * {@link #markGround(ServerLevel, Vec3, double, ParticleOptions, int, int)} 한 번에 나가는
	 * 점 수의 <b>상한</b>.
	 *
	 * <p>위상에 따라 실제로는 이보다 하나 적을 수 있다({@code 40점을 6으로 나누면 7·7·7·7·6·6}).
	 * 예산을 묻는 자리는 늘 나쁜 쪽을 봐야 하므로 올림으로 돌려준다.
	 */
	static int strokePoints(double radius, int stride) {
		int points = ringPoints(radius);
		if (points <= 0) {
			return 0;
		}
		int step = Math.max(1, stride);
		return (points + step - 1) / step;
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

	/**
	 * 층마다의 소리·볼륨·음높이.
	 *
	 * <p>표가 하나여야 한다. {@link #sound} 와 {@link #soundFor} 가 각자 숫자를 들고 있으면
	 * 한쪽을 고칠 때 <b>같은 층이 카드에 따라 다른 소리로 들린다</b> — 경고를 한 곳에 모은 이유가
	 * 통째로 사라지는 종류의 어긋남이다.
	 */
	private record Cue(Holder<SoundEvent> sound, float volume, float pitch) {
	}

	/**
	 * 그 층의 소리표.
	 *
	 * <p>{@code SoundEvents} 상수를 <b>여기서 처음 만진다.</b> {@link Stage} 의 필드로 올리지
	 * 않은 것은 그러면 층 이름을 읽는 것만으로 소리 레지스트리가 깨어나기 때문이다 —
	 * {@code TrialWarningTest} 는 {@code Bootstrap} 없이 {@link #stageFor} 만 굴린다.
	 */
	private static Cue cueOf(Stage stage) {
		return switch (stage) {
			case APPROACH -> new Cue(holderOf(SoundEvents.ENDER_DRAGON_GROWL), 0.6F, 0.7F);
			case MARK -> new Cue(holderOf(SoundEvents.BEACON_ACTIVATE), 0.5F, 1.4F);
			case IMMINENT -> new Cue(holderOf(SoundEvents.EXPERIENCE_ORB_PICKUP), 1.0F, 1.8F);
		};
	}

	/**
	 * 층에 맞는 소리를 <b>그 자리</b>에 놓는다. 같은 층에서 두 번 부르면 두 번 울리므로 층이
	 * 바뀔 때만 부른다.
	 *
	 * <h2>⚠ 이것을 <b>사람마다</b> 부르지 말 것 — {@link #soundFor} 가 그쪽이다</h2>
	 *
	 * <p>여기서 나는 소리는 <b>자리의 것</b>이다. 그 반경 안에 있는 사람 전부가 듣고, 그래서
	 * 「저기가 위험하다」를 말할 수 있다 — 「자리 폭격」·「기둥 화염구」·「부활」처럼 <b>위험한
	 * 지점이 사람과 따로 있는</b> 카드가 쓰는 갈래다.
	 *
	 * <p>고리가 <b>사람마다 다른 순간에</b> 닿는 카드(「엔더 파동」·「엔더폭풍」·「착지 충격」·
	 * 「수정 과충전」·「연쇄 포격」·「종말의 비」)는 이것을 팀원 목록으로 돌려서는 안 된다. 그렇게
	 * 하면 둘이 함께 틀린다 — 내 경고가 옆 사람에게도 들려 <b>「내 차례인가」가 흐려지고</b>,
	 * 같은 거리에 선 둘이 같은 틱에 울려 <b>각자 두 번</b> 듣는다. 실제로 여섯 카드가 그렇게
	 * 되어 있었다.
	 */
	public static void sound(@Nullable ServerLevel level, @Nullable Vec3 at, @Nullable Stage stage) {
		if (level == null || at == null || stage == null) {
			return;
		}
		Cue cue = cueOf(stage);
		level.playSound(null, at.x, at.y, at.z, cue.sound().value(), SoundSource.HOSTILE,
				cue.volume(), cue.pitch());
	}

	/**
	 * 층에 맞는 소리를 <b>그 사람에게만</b>, 그 사람 자리에서 낸다.
	 *
	 * <p>고리가 중앙에서 퍼지는 카드는 남은 시간이 <b>사람마다 다르다.</b> 그러니 경고도 그
	 * 사람의 것이고, 옆 사람에게 들릴 이유가 없다 — 들리면 「지금 뛰어야 하는 것이 나인가」가
	 * 흐려진다. 자리에 놓는 {@link #sound} 로는 그 구별을 만들 수 없다.
	 *
	 * <p>겹침도 여기서 함께 사라진다. 같은 거리에 선 둘은 같은 틱에 층이 바뀌는데, 자리에 놓는
	 * 소리였을 때는 그 둘이 서로의 경고까지 들어 <b>각자 두 번</b>이었다.
	 *
	 * <p>쓰는 곳은 여섯이다 — 「연쇄 포격」·「수정 과충전」·「종말의 비」·「엔더 파동」·
	 * 「엔더폭풍」·「착지 충격」. 전부 팀원 루프 안에서 부르므로 <b>여기를 루프 밖으로 끌어내지
	 * 말 것</b>: 사람마다 층이 다른 것이 이 갈래가 있는 이유다.
	 */
	public static void soundFor(@Nullable ServerLevel level, @Nullable ServerPlayer member,
			@Nullable Stage stage) {
		if (level == null || member == null || stage == null) {
			return;
		}
		Cue cue = cueOf(stage);
		playEach(level, List.of(member), cue.sound(), cue.volume(), cue.pitch());
	}

	/**
	 * {@code SoundEvents} 상수를 {@code Holder} 로 맞춰 준다.
	 *
	 * <p>오버로드 둘인 것이 요점이다. 그 상수들은 <b>모양이 섞여 있어서</b>
	 * ({@code Holder.Reference} 인 것과 맨 {@code SoundEvent} 인 것) 어느 쪽인지 사람이 외우고
	 * 있어야 했다. 둘 다 받아 두면 컴파일러가 고르므로 <b>틀릴 자리가 없어진다</b> — 판이 올라
	 * 어떤 상수의 모양이 바뀌어도 여기는 그대로 컴파일된다.
	 */
	private static Holder<SoundEvent> holderOf(Holder<SoundEvent> sound) {
		return sound;
	}

	private static Holder<SoundEvent> holderOf(SoundEvent sound) {
		return BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
	}

	// ------------------------------------------------------------------ 사람마다 한 번

	/**
	 * 팀원 <b>저마다의 자리</b>에서 같은 소리를 낸다. 넷이면 각자 <b>정확히 한 번</b> 듣는다.
	 *
	 * <h2>⚠ {@code level.playSound} 를 사람마다 부르면 사람 수만큼 겹친다</h2>
	 *
	 * <p>거의 모든 카드가 이 관용구를 들고 있었다.
	 *
	 * <pre>for (ServerPlayer member : members) {
	 *     end.playSound(null, member.getX(), ..., sound, ...);   // ⚠ 이러면 안 된다
	 * }</pre>
	 *
	 * <p>주석에는 하나같이 「사람마다 그 자리에서 울린다」고 적혀 있었지만 <b>구현이 그 말과
	 * 달랐다.</b> {@code ServerLevel.playSound} 는 {@code playSeededSound} 를 거쳐
	 * {@code PlayerList.broadcast(except, x, y, z, radius, dim, packet)} 로 내려가고, 그것은
	 * <b>그 반경 안의 모든 플레이어</b>에게 같은 꾸러미를 보낸다. 곧 한 번 부를 때마다
	 * <b>「그 자리 근처에 있는 사람 전부」</b>가 듣는다. 넷이 16칸 안에 모여 있으면 네 번 부른
	 * 소리를 <b>각자 네 겹으로</b> 듣고, 흩어져 있으면 그제야 한 번씩 듣는다 — 팀이 모일수록
	 * 시끄러워지는, 사람이 「소리가 이상하다」고만 말할 수 있는 종류의 결함이다.
	 *
	 * <p>고치는 길은 <b>월드에 소리를 놓지 않는 것</b>뿐이다. 사람마다 꾸러미를 그 사람의
	 * 연결로 <b>직접</b> 보내면 받는 사람이 정확히 한 명이라 겹칠 곳이 없다
	 * ({@code TeamStorage.chime} 이 같은 이유로 이미 쓰던 방식이다).
	 *
	 * <p>⚠ 26.3 {@code ServerPlayer} 에는 {@code playNotifySound} 가 <b>없다.</b> 확인했고,
	 * {@code TrialWarningTest} 가 그 사실을 붙들고 있다 — 생기더라도 그쪽은
	 * {@code SoundSource} 를 우리가 고를 수 없으므로 여기를 대체하지 못한다.
	 *
	 * <h2>왜 여기에 두는가</h2>
	 *
	 * <p>실행기마다 제 것을 들고 있으면 <b>다음에 또 한쪽만 고쳐진다.</b> 실제로 이 저장소는 같은
	 * 관용구를 <b>열 파일 열일곱 자리</b>에 복사해 두었었고(이 메서드로 온 것이 <b>열한 자리</b>,
	 * {@link #soundFor} 로 간 것이 <b>여섯 자리</b>), 그 열일곱이 전부 같은 방식으로 틀려 있었다.
	 * 한 사람이 자기 카드만 보고서는 알 수 없는 종류의 결함이다 — 어느 파일에서도 그 한 줄은
	 * 옳아 보이고, 겹치는 것은 <b>여러 카드가 같은 짓을 하기 때문</b>이라서다. 소리·바닥 표식이
	 * 이미 여기 모여 있으므로 「사람에게 닿는 갈래」는 전부 이 파일에 있다.
	 *
	 * <h2>관전자는 뺀다</h2>
	 *
	 * <p>{@code DragonTrialManager.membersOf} 는 <b>차원만</b> 보고 거르므로 관전 중인 팀원도
	 * 목록에 들어 있다. 그 사람을 빼는 것이 이 저장소가 이미 정해 둔 것이다 — 피해·판정 쪽이
	 * 전부 {@code isSpectator} 를 보고 건너뛰고({@code TrialEnderStorm}·
	 * {@code TrialLandingShock}·{@code TrialNightHost}·{@code TrialDragonFocus}), 소리 쪽에서도
	 * {@code TrialCrystalLink}·{@code TrialCrystalOvercharge} 가 「판에 끼어들지 않는 사람이다」라고
	 * 적고 건너뛰고 있었다. 여기서 그 규칙을 <b>열일곱 자리 전부에 일관되게</b> 적용한다.
	 *
	 * <p>⚠ <b>달라지는 것이 하나 있다.</b> 예전에는 자리에 놓은 소리라 근처에 떠 있던 관전자가
	 * <b>덤으로</b> 들었다. 이제는 한 사람도 안 들린다. 그것이 의도다 — 관전자가 듣고 안 듣고는
	 * 그가 <b>어디에 떠 있었는가</b>에 달린 우연이었고, 우연에 기대는 연출은 규약이 아니다.
	 * 되돌리고 싶으면 이 한 곳의 {@code isSpectator} 만 지우면 되고, 그러면 열일곱 자리가 함께
	 * 바뀐다 — 도우미를 하나로 모은 값이 그것이다.
	 *
	 * <h2>{@code SoundSource} 는 고를 수 없다</h2>
	 *
	 * <p>시련 소리는 전부 {@link SoundSource#HOSTILE} 이다({@link #sound} 와 같다). 사람이 소리
	 * 설정에서 <b>시련만</b> 따로 줄이거나 키울 수 있어야 하므로 인자로 열어 두지 않는다.
	 *
	 * @param level  씨앗을 뽑을 월드. 한 번 부를 때 씨앗도 하나라 <b>팀 전원이 같은 변주</b>를
	 *               듣는다 — 사람마다 다시 굴리면 같은 사건이 저마다 다른 소리로 들린다
	 * @param sound  {@code Holder} 로 들어 있는 소리. {@code SoundEvents.SHIELD_BLOCK} 처럼
	 *               이미 {@code Holder.Reference} 인 것은 {@code value()} 로 벗기지 말 것
	 * @return 꾸러미가 실제로 나간 사람 수. 곧 <b>이 소리를 들은 사람 수</b>다
	 */
	public static int playEach(@Nullable ServerLevel level,
			@Nullable Collection<ServerPlayer> members,
			@Nullable Holder<SoundEvent> sound, float volume, float pitch) {
		if (level == null || sound == null) {
			return 0;
		}
		long seed = level.getRandom().nextLong();
		return eachListener(members, ServerPlayer::isSpectator,
				member -> member.connection.send(new ClientboundSoundPacket(sound,
						SoundSource.HOSTILE, member.getX(), member.getY(), member.getZ(),
						volume, pitch, seed)));
	}

	/**
	 * {@code Holder} 가 아닌 <b>맨 {@link SoundEvent}</b> 를 받는 형태.
	 *
	 * <p>{@code SoundEvents} 의 상수는 두 모양이 섞여 있다 — {@code SHIELD_BLOCK}·
	 * {@code GENERIC_EXPLODE} 는 {@code Holder.Reference} 인데 {@code CONDUIT_ACTIVATE}·
	 * {@code CONDUIT_DEACTIVATE}·{@code ENDER_EYE_DEATH} 같은 것은 맨 {@code SoundEvent} 다.
	 * {@code ClientboundSoundPacket} 은 {@code Holder} 만 받으므로 그런 것은 레지스트리에서
	 * 감싸 줘야 한다.
	 *
	 * <p>{@code wrapAsHolder} 는 <b>등록된</b> 소리에만 쓸 수 있다. 바닐라 {@code SoundEvents}
	 * 상수는 전부 등록돼 있으므로 문제가 없지만, 모드가 제 소리를 만들어 쓰기 시작하면 등록
	 * 여부를 먼저 볼 것.
	 */
	public static int playEach(@Nullable ServerLevel level,
			@Nullable Collection<ServerPlayer> members,
			@Nullable SoundEvent sound, float volume, float pitch) {
		if (sound == null) {
			return 0;
		}
		return playEach(level, members, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound),
				volume, pitch);
	}

	/**
	 * 「목록에서 <b>들을 자격이 있는 사람</b>을 한 번씩만 골라 무언가 한다」만 떼어 놓은 것.
	 *
	 * <p>월드도 {@code ServerPlayer} 도 없이 부를 수 있어야 한다. 시험 환경에는 살아 있는
	 * 서버가 없어 {@code ServerPlayer} 를 만들 수 없고, 그러면 <b>「넷이면 각자 한 번」이라는
	 * 이 수정의 핵심이 시험으로 못 잡힌다.</b> 그래서 그 셈만 여기로 꺼내 두고
	 * {@code TrialWarningTest} 가 아무 객체로나 직접 굴려 본다.
	 *
	 * <p>같은 사람이 두 번 들어와도 한 번만 센다. {@code DragonTrialManager} 가 넘기는 목록은
	 * UUID 집합에서 만들어져 중복이 없지만, 이 함수가 약속하는 것이 「<b>사람마다</b> 한 번」인
	 * 이상 그 약속은 목록의 사정과 무관하게 여기서 지켜져야 한다. 값이 아니라 <b>객체가
	 * 같은지</b>로 본다 — {@code ServerPlayer} 의 {@code equals} 는 개체 식별자를 보는데, 같은
	 * 사람이 재접속해 새 개체가 되면 그 둘은 애초에 서로 다른 연결이다.
	 *
	 * @param outOfPlay 판에 끼어들지 않는 사람. 실전에서는 {@code isSpectator} 다
	 * @return 실제로 {@code send} 를 받은 수
	 */
	static <T> int eachListener(@Nullable Collection<T> members, Predicate<T> outOfPlay,
			Consumer<T> send) {
		if (members == null || members.isEmpty()) {
			return 0;
		}
		Set<T> heard = Collections.newSetFromMap(new IdentityHashMap<>());
		int sent = 0;
		for (T member : members) {
			if (member == null || outOfPlay.test(member) || !heard.add(member)) {
				continue;
			}
			send.accept(member);
			sent++;
		}
		return sent;
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
