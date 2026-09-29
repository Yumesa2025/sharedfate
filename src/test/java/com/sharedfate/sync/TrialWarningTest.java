package com.sharedfate.sync;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 경고가 세 층으로 제때 올라오는지, 그리고 <b>보이기는 하는지</b> 본다.
 *
 * <p>층을 고르는 것이 이 클래스의 판단이다. 파티클과 소리는 월드가 있어야 해서 여기서 그려 볼 수
 * 없지만, <b>언제 어느 층인가</b>가 틀리면 나머지가 다 맞아도 경고가 늦거나 겹친다.
 *
 * <h2>보이는지는 바이트코드로 묻는다</h2>
 *
 * <p>이 파일에 파티클 사거리 시험이 붙어 있는 이유는, 이번 결함이 <b>조용히 안 보이는</b>
 * 종류였기 때문이다. 짧은 형태로 되돌려도 컴파일이 되고 로그도 깨끗하고 시험도 다 통과한다 —
 * 게임에서 아무것도 안 나올 뿐이다. 그래서 컴파일된 클래스의 상수 풀을 직접 뒤진다
 * ({@code DebugOverlayCheckTest} 가 같은 이유로 쓰는 방식이다).
 *
 * <h2>자막은 나가지 않는다</h2>
 *
 * <p>사람이 「스킬 발동할 때 밑에 글 써 주는 것들도 일단 없애」라고 해서
 * {@link TrialWarning#shout} 를 부르던 자리를 전부 걷어냈다. 글자는 <b>한 줄만 되살려도</b>
 * 화면에 돌아오고, 카드를 새로 만들다 보면 「예고니까 한 줄 띄우자」가 자연스러워 보인다. 그래서
 * 같은 상수 풀 수법으로 <b>아무도 그 이름을 부르지 않는지</b>를 못박아 둔다.
 */
class TrialWarningTest {

	/** 짧은 형태의 서술자. 이것을 부르면 32 블록 밖에는 패킷이 나가지 않는다. */
	private static final String SHORT_FORM =
			"(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I";
	/** 긴 형태의 서술자. 앞의 {@code ZZ} 가 {@code overrideLimiter}·{@code alwaysShow} 다. */
	private static final String LONG_FORM =
			"(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I";
	/**
	 * {@link TrialWarning#sound} 의 서술자.
	 *
	 * <p>이름만 찾으면 안 된다 — {@code playSound}·{@code SoundEvents} 에도 {@code sound} 가
	 * 들어 있어 무엇을 부르든 통과한다. {@code TrialWarning$Stage} 가 적힌 이 서술자가 「공용
	 * 경고 소리를 부른다」를 가리키는 유일한 자국이다.
	 */
	private static final String SOUND_FORM = "(Lnet/minecraft/server/level/ServerLevel;"
			+ "Lnet/minecraft/world/phys/Vec3;Lcom/sharedfate/sync/TrialWarning$Stage;)V";
	/**
	 * {@link TrialWarning#soundFor} 의 서술자. <b>그 사람에게만</b> 가는 경고음이다.
	 *
	 * <p>{@link #SOUND_FORM} 과 갈라 두는 것이 요점이다. 둘은 이름이 거의 같은데 닿는 사람이
	 * 전혀 다르다 — 하나는 자리 반경 안의 전원이고 하나는 딱 한 명이다.
	 */
	private static final String SOUND_FOR_FORM = "(Lnet/minecraft/server/level/ServerLevel;"
			+ "Lnet/minecraft/server/level/ServerPlayer;Lcom/sharedfate/sync/TrialWarning$Stage;)V";
	/**
	 * {@code ServerLevel.playSound} 의 <b>자리에 놓는</b> 형태. 26.3 에서 javap 로 뜬 서술자다.
	 *
	 * <p>이것을 팀원 루프 안에서 부르면 사람 수만큼 겹친다 — 까닭은
	 * {@link TrialWarning#playEach} 설명에 있다.
	 */
	private static final String PLACED_SOUND = "(Lnet/minecraft/world/entity/Entity;DDD"
			+ "Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FF)V";
	/** 같은 것의 {@code Holder} 형태. 한쪽만 막으면 다른 쪽으로 그대로 돌아온다. */
	private static final String PLACED_SOUND_HOLDER = "(Lnet/minecraft/world/entity/Entity;DDD"
			+ "Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FF)V";

	/**
	 * 엔드 전투에서 32 블록을 넘겨 그리는 자리들. 하나라도 짧은 형태로 되돌아가면 그 연출이 통째로
	 * 사라진다.
	 */
	private static final Class<?>[] LONG_RANGE_DRAWERS = {
			TrialWarning.class, TrialFireball.class, TrialCrystalRevive.class,
			// 「자리 폭격」의 폭발도 아레나 어디서든 터진다. 반대편 팀원과는 80칸까지 벌어져,
			// 짧은 형태로 두면 맞은 사람만 보고 나머지는 소리만 듣는 연출이 된다.
			TrialRisks.class};

	@Test
	void 먼_미래에는_아직_아무_층도_아니다() {
		assertNull(TrialWarning.stageFor(200), "2초 넘게 남았는데 벌써 경고하면 신호가 흔해진다");
		assertNull(TrialWarning.stageFor(101));
	}

	@Test
	void 세_층이_차례로_올라온다() {
		assertEquals(TrialWarning.Stage.APPROACH, TrialWarning.stageFor(100), "첫 층 — 뭔가 온다");
		assertEquals(TrialWarning.Stage.MARK, TrialWarning.stageFor(50), "둘째 층 — 여기로 온다");
		assertEquals(TrialWarning.Stage.IMMINENT, TrialWarning.stageFor(14), "셋째 층 — 지금 나가라");
	}

	@Test
	void 층은_뒤로_돌아가지_않는다() {
		TrialWarning.Stage previous = null;
		for (int remaining = 120; remaining >= 0; remaining--) {
			TrialWarning.Stage now = TrialWarning.stageFor(remaining);
			if (previous != null && now != null) {
				assertTrue(now.ordinal() >= previous.ordinal(),
						"시간이 줄어드는데 경고가 약해지면 플레이어가 안심한다: " + remaining);
			}
			previous = now;
		}
	}

	@Test
	void 발동_순간에도_마지막_층이다() {
		assertEquals(TrialWarning.Stage.IMMINENT, TrialWarning.stageFor(0),
				"0 틱에서 층이 사라지면 마지막 경고 없이 터진다");
		assertEquals(TrialWarning.Stage.IMMINENT, TrialWarning.stageFor(-5),
				"이미 지난 값이 들어와도 약해지면 안 된다");
	}

	@Test
	void 경계값에서_층이_갈린다() {
		assertNotEquals(TrialWarning.stageFor(101), TrialWarning.stageFor(100), "첫 층의 문턱");
		assertNotEquals(TrialWarning.stageFor(51), TrialWarning.stageFor(50), "둘째 층의 문턱");
		assertNotEquals(TrialWarning.stageFor(15), TrialWarning.stageFor(14), "셋째 층의 문턱");
	}

	@Test
	void 예고_시간은_요구하는_행동이_클수록_길다() {
		assertTrue(TrialWarning.TICKS_SIDESTEP < TrialWarning.TICKS_REPOSITION,
				"옆으로 한 걸음보다 지정 위치로 가는 것이 오래 걸린다");
		assertTrue(TrialWarning.TICKS_REPOSITION < TrialWarning.TICKS_SCATTER,
				"혼자 움직이는 것보다 넷이 흩어지는 것이 오래 걸린다");
		assertTrue(TrialWarning.TICKS_SIDESTEP >= 25,
				"사람의 지각·판단·입력에만 0.25초가 든다. 그보다 짧으면 반응할 수 없다");
	}

	@Test
	void 가장_긴_예고도_세_층_안에_들어온다() {
		assertEquals(TrialWarning.Stage.APPROACH, TrialWarning.stageFor(TrialWarning.TICKS_SCATTER),
				"넷이 흩어져야 하는 패턴인데 첫 층이 안 뜨면 예고가 통째로 없는 것과 같다");
	}

	// ------------------------------------------------------------------ 고리 점 수

	@Test
	void 지금_카드가_쓰는_반경에서는_예전과_같은_고리다() {
		// 1.5(표적 표식)·2(자리 폭격)·3(낙뢰). 하한이 있어 모습이 달라지지 않는다.
		assertEquals(40, TrialWarning.ringPoints(1.5));
		assertEquals(40, TrialWarning.ringPoints(2.0));
		assertEquals(40, TrialWarning.ringPoints(3.0));
	}

	@Test
	void 넓어진_화염구_반경에서도_고리로_읽힌다() {
		// 「기둥 화염구」가 3 에서 4.35 로 넓어졌다. 개수를 고정해 두었다면 여기서 점이 벌어져
		// 고리가 점선이 됐을 자리다 — 간격을 정하고 개수를 뽑는 구조라 그냥 점이 늘어난다.
		double radius = 4.35;
		assertTrue(TrialWarning.ringPoints(radius) > TrialWarning.ringPoints(3.0),
				"둘레가 늘었는데 점이 그대로면 간격만 벌어진다");
		assertTrue(TrialWarning.ringGap(radius) <= TrialWarning.POINT_GAP + 1.0E-9,
				"실제 간격: " + TrialWarning.ringGap(radius));
	}

	@Test
	void 반경이_커지면_점이_늘어난다() {
		assertTrue(TrialWarning.ringPoints(3.0) < TrialWarning.ringPoints(5.0),
				"둘레만 늘고 점은 그대로면 고리가 아니라 흩뿌려진 점이 된다");
		assertTrue(TrialWarning.ringPoints(5.0) < TrialWarning.ringPoints(6.0));

		int previous = 0;
		for (double radius = 0.5; radius <= 30.0; radius += 0.5) {
			int points = TrialWarning.ringPoints(radius);
			assertTrue(points >= previous,
					"반경이 커졌는데 점이 줄면 큰 위험일수록 덜 보인다: 반경 " + radius);
			previous = points;
		}
	}

	@Test
	void 점_수에_상한이_있다() {
		// 상한이 없으면 큰 반경 카드 하나가 파티클 패킷만으로 틱을 민다. 매 틱 그리고, 이제는
		// 거리 제한 없이 전원에게 나가기 때문이다.
		for (double radius = 0.5; radius <= 500.0; radius += 0.5) {
			assertTrue(TrialWarning.ringPoints(radius) <= TrialWarning.MAX_POINTS,
					"반경 " + radius + " 에서 상한을 넘었다");
		}
		assertEquals(TrialWarning.MAX_POINTS, TrialWarning.ringPoints(1000.0),
				"아무리 커도 상한에서 멈춘다");
		assertEquals(0, TrialWarning.ringPoints(0.0), "반경이 없으면 그리지 않는다");
		assertEquals(0, TrialWarning.ringPoints(-3.0));
	}

	@Test
	void 상한에_닿기_전까지는_점_사이_간격을_지킨다() {
		for (double radius = 0.5; radius <= 6.0; radius += 0.5) {
			assertTrue(TrialWarning.ringGap(radius) <= TrialWarning.POINT_GAP + 1.0E-9,
					"반경 " + radius + " 에서 점이 " + TrialWarning.ringGap(radius)
							+ " 블록씩 벌어진다 — 고리로 안 읽힌다");
		}
	}

	// ------------------------------------------------------------------ 색 규약

	/**
	 * 노랑의 뜻을 바꾸면서 색을 늘리지 않았다.
	 *
	 * <p>같은 색이 언제나 같은 뜻이어야 플레이어가 표식을 언어로 배운다. 「번개」에 색이 필요해졌을
	 * 때 다섯째 색을 만드는 대신 <b>아무 카드도 쓰지 않던 노랑의 뜻을 다시 정했다.</b> 그 결정이
	 * 지켜지려면 팔레트는 여전히 넷이어야 하고, 넷이 서로 달라야 한다.
	 */
	@Test
	@SuppressWarnings("deprecation")
	void 색_규약은_서로_다른_넷_그대로다() {
		Set<Integer> palette = new HashSet<>(List.of(TrialWarning.Colors.DEADLY,
				TrialWarning.Colors.LIGHTNING, TrialWarning.Colors.SHOVE,
				TrialWarning.Colors.MARKED));
		assertEquals(4, palette.size(),
				"같은 색 둘이 다른 뜻을 가지면 표식이 언어가 아니라 소음이 된다");
		assertEquals(TrialWarning.Colors.LIGHTNING, TrialWarning.Colors.REQUIRED,
				"REQUIRED 는 LIGHTNING 과 같은 값의 옛 이름이다 — 새 색이 아니다."
						+ " 화면 글자에만 남아 있고 표식에는 쓰지 않는다");
	}

	// ------------------------------------------------------------------ 보이는가

	@Test
	void 먼_곳에_그리는_연출은_전부_긴_형태를_쓴다() throws IOException {
		for (Class<?> type : LONG_RANGE_DRAWERS) {
			String bytes = classBytes(type);
			assertTrue(bytes.contains(LONG_FORM),
					type.getSimpleName() + " 가 긴 형태를 한 번도 부르지 않는다");
			assertFalse(bytes.contains(SHORT_FORM),
					type.getSimpleName() + " 가 짧은 형태로 되돌아갔다. 첫 boolean 이 512 블록과 "
							+ "32 블록을 가르는 깃발이고, 끄면 기둥 위·공중의 파티클이 "
							+ "패킷조차 나가지 않는다 — 빌드도 로그도 조용하다");
		}
	}

	/**
	 * 26.3 에 긴 형태가 <b>그 시그니처로</b> 실제로 있는지.
	 *
	 * <p>위 시험은 문자열을 보는 것이라 판이 올라 인자가 바뀌면 「짧은 형태가 없다」로 통과해
	 * 버릴 수 있다. 여기서 실물을 잡아 둔다.
	 */
	@Test
	void 긴_형태_오버로드가_실제로_있다() {
		Method method = assertDoesNotThrow(() -> ServerLevel.class.getMethod("sendParticles",
						ParticleOptions.class, boolean.class, boolean.class,
						double.class, double.class, double.class, int.class,
						double.class, double.class, double.class, double.class),
				"긴 거리로 보낼 길이 사라졌다. 26.3 의 시그니처를 javap 로 다시 확인할 것");
		assertEquals(int.class, method.getReturnType(), "보낸 사람 수를 돌려준다");
		assertEquals(boolean.class, method.getParameterTypes()[1],
				"첫 boolean 이 overrideLimiter — 512 와 32 를 가르는 그 깃발이다");

		assertDoesNotThrow(() -> ServerLevel.class.getMethod("sendParticles",
						ParticleOptions.class, double.class, double.class, double.class,
						int.class, double.class, double.class, double.class, double.class),
				"짧은 형태가 없어졌다면 위의 서술자 검사도 뜻을 잃는다 — 함께 고칠 것");
	}

	// ------------------------------------------------------------------ 자막은 나가지 않는다

	/**
	 * 본 소스 세트에 컴파일된 <b>모든</b> 클래스에서 {@code shout} 참조를 찾는다.
	 *
	 * <p>{@link TrialWarning#shout} 은 지우지 않았다 — 「일단 없애」였으므로 되돌릴 자리를 남긴
	 * 것이다. 그러니 「호출자가 하나도 없다」를 지키는 것은 사람의 기억이 아니라 이 시험이어야
	 * 한다. 카드를 하나 더 만들 때 「예고니까 한 줄 띄우자」가 슬그머니 들어오면 여기서 걸린다.
	 *
	 * <p>클래스 파일을 직접 뒤지는 까닭은 {@code grep} 과 달리 <b>실제로 컴파일된 것</b>을 보기
	 * 때문이다. 주석이나 javadoc 의 {@code shout} 은 클래스 파일에 들어가지 않으므로 설명을 적어
	 * 두어도 걸리지 않는다.
	 *
	 * <p>메서드를 선언한 {@link TrialWarning} 자신은 건너뛴다. 자기 이름은 언제나 상수 풀에 있다.
	 *
	 * <p>클라이언트 소스 세트는 보지 않는다. {@code shout} 은 {@code ServerPlayer} 를 받으므로
	 * 그쪽에서 부를 길이 없고, 시련·패시브 실행기는 전부 본 소스 세트에 있다.
	 */
	@Test
	void 아무도_자막을_띄우지_않는다() throws Exception {
		List<Path> scanned = new ArrayList<>();
		for (Path file : mainClassFiles()) {
			String name = file.getFileName().toString();
			if (name.equals("TrialWarning.class") || name.startsWith("TrialWarning$")) {
				continue;
			}
			scanned.add(file);
			String bytes = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
			assertFalse(bytes.contains("shout"),
					file.getFileName() + " 가 TrialWarning.shout 을 부른다. 액션바 자막은 걷어낸"
							+ " 상태다 — 되살리려면 TrialWarning 의 설명(「두 갈래로 쌓는다」)부터"
							+ " 함께 고칠 것");
		}
		assertTrue(scanned.size() > 50,
				"클래스를 " + scanned.size() + "개밖에 못 찾았다. 빌드 산출물 자리가 바뀌었다면 이"
						+ " 시험은 아무것도 안 지키면서 통과한다");
	}

	/**
	 * 없앤 것이 <b>글자뿐</b>임을 못박는다.
	 *
	 * <p>자막을 걷어내면서 같은 메서드 안의 소리나 표식까지 함께 지우기 쉽다 — 세 줄이 붙어 있던
	 * 자리들이다. 전멸하면 월드가 지워지는 전투라 <b>「몰라서 죽었다」가 가장 나쁜 결과</b>이므로,
	 * 남은 두 갈래가 실제로 살아 있는지를 카드마다 확인한다.
	 *
	 * <p>{@code TrialCrystalRevive} 는 {@code markGround} 를 쓰지 않는다. 제 모양(자리 고리)을
	 * 직접 그리기 때문이고, 그쪽이 살아 있는지는
	 * {@link #먼_곳에_그리는_연출은_전부_긴_형태를_쓴다} 가 본다.
	 */
	@Test
	void 소리와_바닥_표식은_여전히_나간다() throws IOException {
		// 자리에 놓는 경고음을 쓰는 카드들 — 위험한 지점이 사람과 따로 있는 쪽이다.
		for (Class<?> type : new Class<?>[] {TrialCrystalRevive.class,
				TrialDragonFocus.class, TrialFireball.class, TrialRisks.class}) {
			assertTrue(classBytes(type).contains(SOUND_FORM),
					type.getSimpleName() + " 가 경고 소리를 내지 않는다. 자막을 걷어낸 뒤로 소리는"
							+ " 「무엇이 언제 오는가」를 말하는 두 갈래 중 하나다");
		}
		// 「연쇄 포격」은 사람마다 보내는 쪽으로 옮겼다. 소리를 안 내게 된 것이 아니다.
		assertTrue(classBytes(DragonFireBarrage.class).contains(SOUND_FOR_FORM),
				"DragonFireBarrage 가 경고 소리를 내지 않는다");
		for (Class<?> type : new Class<?>[] {DragonFireBarrage.class, TrialDragonFocus.class,
				TrialFireball.class, TrialRisks.class}) {
			assertTrue(classBytes(type).contains("markGround"),
					type.getSimpleName() + " 가 바닥 표식을 그리지 않는다. 「어디로 오는가」를 말하는"
							+ " 것이 그 고리뿐이다");
		}
	}

	// ------------------------------------------------------------------ 소리가 겹치지 않는가

	/**
	 * <b>팀이 넷이면 각자 정확히 한 번</b> — 이 수정의 전부다.
	 *
	 * <p>실전에서는 {@code ServerPlayer} 의 연결로 꾸러미가 나가므로 <b>받는 사람 수 = 나간 수</b>
	 * 이고, 그래서 「몇 장 나갔나」를 세면 「각자 몇 번 들었나」를 센 것과 같다. 그 셈만
	 * {@link TrialWarning#eachListener} 로 떼어 두었다 — 시험 환경에는 살아 있는 서버가 없어
	 * {@code ServerPlayer} 를 만들 수 없기 때문이다.
	 *
	 * <p>고치기 전에는 이 수가 4 여도 <b>들린 횟수는 16</b> 이었다. {@code level.playSound} 가
	 * 자리 반경 안의 전원에게 나가서다. 그 부분은 세는 것으로 잡을 수 없으므로
	 * {@link #시련_실행기는_팀_소리를_자리에_놓지_않는다} 가 따로 붙들고 있다.
	 */
	@Test
	void 넷이면_저마다_정확히_한_번_듣는다() {
		List<String> team = List.of("가", "나", "다", "라");
		List<String> heard = new ArrayList<>();

		int sent = TrialWarning.eachListener(team, member -> false, heard::add);

		assertEquals(4, sent, "넷인데 " + sent + "장이 나갔다");
		assertEquals(team, heard, "누군가 빠졌거나 두 번 들어갔다");
	}

	@Test
	void 관전자는_듣지_않는다() {
		List<String> team = List.of("산 사람", "관전자", "산 사람2");
		List<String> heard = new ArrayList<>();

		int sent = TrialWarning.eachListener(team, "관전자"::equals, heard::add);

		assertEquals(2, sent);
		assertEquals(List.of("산 사람", "산 사람2"), heard,
				"관전자는 판에 끼어들지 않는 사람이다 — 피해·판정 쪽이 이미 같은 규칙을 쓴다");
	}

	@Test
	void 같은_사람이_두_번_들어와도_한_번만_듣는다() {
		String twice = "같은 사람";
		List<String> heard = new ArrayList<>();

		int sent = TrialWarning.eachListener(List.of(twice, "다른 사람", twice),
				member -> false, heard::add);

		assertEquals(2, sent, "명단이 중복을 들고 와도 「사람마다 한 번」은 여기서 지켜져야 한다");
		assertEquals(List.of(twice, "다른 사람"), heard);
	}

	@Test
	void 아무도_없으면_아무_일도_안_한다() {
		List<String> heard = new ArrayList<>();
		assertEquals(0, TrialWarning.<String>eachListener(null, member -> false, heard::add));
		assertEquals(0,
				TrialWarning.eachListener(List.<String>of(), member -> false, heard::add));
		assertTrue(heard.isEmpty());
	}

	/**
	 * 사람 하나에게 직접 보내는 길이 26.3 에 <b>그 모양 그대로</b> 있는지.
	 *
	 * <p>{@link TrialWarning#playEach} 가 이 생성자 하나에 매달려 있다. 판이 올라 인자가 바뀌면
	 * 컴파일이 먼저 깨지겠지만, 그때 <b>왜</b> 이 모양이어야 하는지를 여기서 읽을 수 있어야 한다.
	 */
	@Test
	void 소리_꾸러미를_사람에게_직접_보낼_길이_있다() {
		assertDoesNotThrow(() -> ClientboundSoundPacket.class.getConstructor(
						Holder.class, SoundSource.class,
						double.class, double.class, double.class,
						float.class, float.class, long.class),
				"사람마다 보내는 길이 사라졌다. 26.3 의 생성자를 javap 로 다시 확인할 것");
	}

	/**
	 * {@code ServerPlayer.playNotifySound} 는 <b>없다.</b>
	 *
	 * <p>「사람에게만 소리를 내려면 그런 게 있지 않나」가 이 수정에서 가장 먼저 나온 생각이었고,
	 * 26.3 에는 없다는 것을 클래스 파일로 확인했다. 없다는 사실을 시험으로 적어 두지 않으면
	 * 다음 사람이 같은 자리를 다시 뒤진다. <b>생기더라도 이 자리를 대체하지 못한다</b> —
	 * {@code SoundSource} 를 우리가 고를 수 없기 때문이다.
	 */
	@Test
	void ServerPlayer_에는_혼자_듣는_소리가_없다() {
		for (Method method : ServerPlayer.class.getMethods()) {
			assertNotEquals("playNotifySound", method.getName(),
					"26.3 에 없다고 적어 둔 것이 생겼다. TrialWarning.playEach 의 설명을 고칠 것 —"
							+ " 다만 SoundSource 를 못 고르므로 그대로 쓸 수는 없다");
		}
	}

	/**
	 * <b>왜</b> {@code level.playSound} 로는 안 되는가를 바닐라 쪽에 못박아 둔다.
	 *
	 * <p>{@code playSound} → {@code playSeededSound} → {@code PlayerList.broadcast} 인데, 그
	 * {@code broadcast} 가 <b>반경과 차원을 받는다</b>는 것이 겹침의 원인 전부다. 받는 사람이
	 * 하나가 아니라 「그 반경 안의 전원」이라는 뜻이라, 사람마다 한 번씩 부르면 사람 수의 제곱만큼
	 * 소리가 난다.
	 */
	@Test
	void 자리에_놓는_소리는_반경_안_전원에게_나간다() {
		Method broadcast = assertDoesNotThrow(() -> PlayerList.class.getMethod("broadcast",
						Player.class, double.class, double.class, double.class, double.class,
						ResourceKey.class, Packet.class),
				"이 서술자가 사라졌다면 겹침의 근거도 다시 확인할 것");
		assertEquals(double.class, broadcast.getParameterTypes()[4],
				"다섯째 인자가 반경이다. 한 사람이 아니라 범위를 받는다는 것이 요점");
	}

	/**
	 * 팀 전체에게 알리던 카드가 <b>자리에 놓는 소리로 돌아가지 않았는지.</b>
	 *
	 * <p>이 다섯은 소리가 전부 「팀에게 한 번 알린다」뿐이라, 자리에 놓는 형태가 한 줄이라도
	 * 있으면 그것이 곧 되돌아간 것이다. 되돌아가도 <b>컴파일도 로그도 조용하고</b> 게임에서
	 * 소리가 조금 커질 뿐이라 사람 눈으로는 못 잡는다 — 이 저장소가 파티클 사거리에서 이미 같은
	 * 종류의 사고를 겪었고, 그래서 같은 수법으로 붙든다.
	 *
	 * <p>나머지 카드는 여기 넣지 않았다. 「한 사람이 맞았다」·「저 자리가 터졌다」처럼 <b>자리에
	 * 놓는 것이 맞는</b> 소리를 함께 들고 있어서다({@code TrialLandingShock.strike} 등).
	 */
	@Test
	void 시련_실행기는_팀_소리를_자리에_놓지_않는다() throws IOException {
		for (Class<?> type : new Class<?>[] {TrialCrystalLink.class, TrialCrystalOvercharge.class,
				TrialEndRain.class, TrialNightHost.class, TrialHotbarLock.class}) {
			String bytes = classBytes(type);
			assertFalse(bytes.contains(PLACED_SOUND) || bytes.contains(PLACED_SOUND_HOLDER),
					type.getSimpleName() + " 가 자리에 소리를 놓는다. 팀원 루프 안에서 부르면"
							+ " PlayerList.broadcast 가 반경 안의 전원에게 보내므로 모여 있는 넷이"
							+ " 각자 네 겹으로 듣는다 — TrialWarning.playEach 를 쓸 것");
			assertTrue(bytes.contains("playEach"),
					type.getSimpleName() + " 가 공용 도우미를 쓰지 않는다. 실행기마다 제 것을 들면"
							+ " 다음에 또 한쪽만 고쳐진다");
		}
	}

	/**
	 * 고리가 <b>사람마다 다른 순간에</b> 닿는 카드는 경고음도 사람마다여야 한다.
	 *
	 * <p>이 여섯은 남은 시간이 사람마다 다르다. 자리에 놓는 {@link TrialWarning#sound} 를 팀원
	 * 루프에서 부르면 겹치는 것에 더해 <b>남의 경고가 나에게 들려</b> 「지금 뛰어야 하는 것이
	 * 나인가」가 지워진다. 그 카드의 요점 자체가 사라지는 자리라 이름으로 붙들어 둔다.
	 */
	@Test
	void 사람마다_때가_다른_카드는_경고음도_사람마다다() throws IOException {
		for (Class<?> type : new Class<?>[] {DragonFireBarrage.class, TrialCrystalOvercharge.class,
				TrialEndRain.class, TrialEnderPulse.class, TrialEnderStorm.class,
				TrialLandingShock.class}) {
			String bytes = classBytes(type);
			assertTrue(bytes.contains(SOUND_FOR_FORM),
					type.getSimpleName() + " 가 사람마다 보내는 경고음을 쓰지 않는다");
			assertFalse(bytes.contains(SOUND_FORM),
					type.getSimpleName() + " 가 자리에 놓는 TrialWarning.sound 로 돌아갔다."
							+ " 그쪽은 반경 안의 전원에게 나가 남의 경고까지 들린다");
		}
	}

	/** 컴파일된 클래스 파일을 그대로 읽는다. 서술자는 상수 풀에 아스키로 들어간다. */
	private static String classBytes(Class<?> type) throws IOException {
		String path = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(path)) {
			if (in == null) {
				throw new IOException("클래스 파일을 찾지 못했습니다: " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}

	/**
	 * 본 소스 세트가 컴파일된 자리의 클래스 파일 전부.
	 *
	 * <p>패키지 이름으로 자원을 찾지 않는다 — {@code com/sharedfate/sync/} 는 <b>시험 산출물
	 * 쪽에도 있어서</b> 먼저 걸리는 쪽이 돌아온다. 그러면 이 파일 자신을 뒤지게 되고, 여기 적힌
	 * {@code "shout"} 문자열 때문에 시험이 스스로 깨진다. 그래서 클래스 파일 하나를 짚어 거기서
	 * 위로 올라간다.
	 */
	private static List<Path> mainClassFiles() throws Exception {
		URL url = TrialWarning.class.getResource("TrialWarning.class");
		if (url == null) {
			throw new IOException("TrialWarning.class 를 찾지 못했습니다");
		}
		// .../com/sharedfate/sync/TrialWarning.class 에서 셋 올라가면 산출물 뿌리다.
		Path root = Path.of(url.toURI()).getParent().getParent().getParent().getParent();
		try (Stream<Path> files = Files.walk(root)) {
			return files.filter(path -> path.getFileName().toString().endsWith(".class"))
					.sorted()
					.toList();
		}
	}
}
