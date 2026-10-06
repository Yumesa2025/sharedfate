package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonSittingPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonHoverPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」의 <b>뼈대</b>가 기대고 있는 사실들.
 *
 * <p>이 페이즈는 세 가지 위에 서 있고, 셋 다 <b>조용히 무너지는</b> 종류다.
 *
 * <ol>
 *   <li><b>바닐라 쪽 사실</b> — {@code DragonHoverPhase} 가 앉은 칸이면서 화살을 막지 않는다는
 *       것. 판이 올라 이것이 바뀌면 드래곤이 다시 날거나 활이 안 통하는데, 둘 다 로그 한 줄
 *       없이 「어쩐지 안 잡힌다」로만 나타난다</li>
 *   <li><b>믹스인 대상</b> — {@code sharedfate.mixins.json} 에 refmap 이 없어 서술자가 틀려도
 *       빌드가 통과한다. 접촉 피해가 안 꺼지면 붙는 순간 전멸이다</li>
 *   <li><b>고르는 규칙</b> — 패턴은 아직 비어 있지만 <b>고르는 쪽은 이미 다 있다.</b> 제한 넷이
 *       서로를 막아 아무것도 못 고르는 조합이 생기면 드래곤이 115초 동안 가만히 있는다</li>
 * </ol>
 */
class DragonLastStandTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	/** 정적 상태라 시험끼리 샌다. 앞뒤로 비운다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonLastStand.clearState();
		TrialDryWorld.clearState();
	}

	// ------------------------------------------------------------------ ② 바닐라 쪽 사실

	/**
	 * {@code HOVERING} 이 <b>앉은 칸</b>이다.
	 *
	 * <p>이 한 줄이 거짓이 되면 자세도 없고, 날개 접촉 피해 5 도 되살아나고,
	 * {@code getHeadYOffset()} 의 -1 도 사라져 머리가 닿지 않는다.
	 */
	@Test
	void 호버링은_앉은_칸이다() {
		assertTrue(new DragonHoverPhase(null).isSitting(),
				"DragonHoverPhase.isSitting() 이 거짓이 되면 붙박이 드래곤이 성립하지 않는다");
	}

	/**
	 * 그런데 <b>진짜 앉은 칸은 아니다.</b>
	 *
	 * <p>{@code AbstractDragonSittingPhase.onHurt} 는 화살과 바람충전을 <b>0 으로 만들고 불을
	 * 붙인다.</b> 그 칸으로 잠갔다면 활이 한 대도 안 들어가 「115초에 1200을 깎는다」가 그
	 * 자리에서 거짓이 된다. {@code HOVERING} 을 고른 진짜 이유가 이것이다.
	 */
	@Test
	void 호버링은_화살을_막는_칸이_아니다() {
		assertFalse(AbstractDragonSittingPhase.class.isAssignableFrom(DragonHoverPhase.class),
				"HOVERING 이 AbstractDragonSittingPhase 를 물려받게 되면 활이 안 통한다 — "
						+ "그러면 잠그는 칸을 다시 골라야 한다");
	}

	/**
	 * {@code HOVERING} 은 <b>스스로 다른 칸으로 넘어가지 않는다.</b>
	 *
	 * <p>{@code SITTING_SCANNING}(100틱)·{@code SITTING_FLAMING}(200틱)에는 제 타이머가 있어
	 * 매 틱 눌러 두면 {@code begin()} 이 그 타이머를 0 으로 되돌려 <b>허수아비</b>가 된다.
	 * {@code HOVERING} 에는 그 타이머가 없다는 것을 상수 풀로 확인한다 — 넘어가려면
	 * {@code EnderDragonPhase} 상수를 하나라도 들고 있어야 한다.
	 */
	@Test
	void 호버링은_스스로_일어나지_않는다() {
		// getPhase() 가 EnderDragonPhase.HOVERING 을 돌려주므로 그 이름 자체는 상수 풀에 있다.
		// 문제가 되는 것은 「다른 칸으로 넘긴다」뿐이고, 그 길은 setPhase 하나다.
		assertFalse(vanillaClassBytes(DragonHoverPhase.class).contains("setPhase"),
				"DragonHoverPhase 가 setPhase 를 부르게 됐다 — 스스로 일어나는 길이 하나 늘었다");
	}

	/**
	 * 같은 칸으로 다시 눌러도 되돌아갈 상태가 없다.
	 *
	 * <p>매 틱 누르기가 값싸고 안전한 근거다. {@code EnderDragonPhaseManager.setPhase} 는
	 * {@code target != currentPhase.getPhase()} 일 때만 일을 하고, 설령 돌더라도
	 * {@code HOVERING.begin()} 이 되돌리는 것은 {@code targetLocation} 한 칸뿐이다. 이것이
	 * {@code SITTING_SCANNING}({@code scanningTime})·{@code SITTING_FLAMING}
	 * ({@code flameTicks})과 갈리는 자리다 — 그쪽을 눌러 두면 타이머가 0 으로 되돌아가
	 * 허수아비가 된다.
	 */
	@Test
	void 같은_칸을_눌러도_되돌아갈_상태가_없다() {
		// HOVERING.begin() 이 되돌리는 것은 targetLocation 한 칸뿐이다. 필드가 하나라는 사실이
		// 곧 「눌러도 잃을 것이 없다」이다.
		assertEquals(1, DragonHoverPhase.class.getDeclaredFields().length,
				"DragonHoverPhase 에 필드가 늘었다 — begin() 이 되돌리는 것이 늘었다는 뜻이라 "
						+ "매 틱 누르면 허수아비가 될 수 있다");
	}

	/** 잠그는 칸이 실제로 {@code HOVERING} 인가. 상수 풀로 본다. */
	@Test
	void 우리가_잠그는_칸이_호버링이다() {
		assertNotNull(EnderDragonPhase.HOVERING);
		String bytes = classBytes();
		assertTrue(bytes.contains("HOVERING"), "잠그는 칸이 바뀌었다 — 근거를 클래스 설명에서 다시 볼 것");
		for (String sitting : List.of("SITTING_SCANNING", "SITTING_FLAMING", "SITTING_ATTACKING")) {
			assertFalse(bytes.contains(sitting),
					sitting + " 으로 잠그면 화살이 0 이 되고 제 타이머가 돈다");
		}
	}

	// ------------------------------------------------------------------ ③ 접촉 피해 믹스인

	/**
	 * 믹스인이 무는 자리가 26.3 에 그대로 있다.
	 *
	 * <p>{@code EnderDragon} 에는 {@code hurt} 가 둘이고, <b>다른 하나를 막으면 사람이 드래곤을
	 * 못 때린다.</b> 서술자로 갈라 두었다는 것을 여기서 붙든다.
	 */
	@Test
	void 접촉_피해를_넣는_메서드가_그대로_있다() throws NoSuchMethodException {
		Method contact = EnderDragon.class.getDeclaredMethod("hurt", ServerLevel.class, List.class);
		assertEquals(void.class, contact.getReturnType());
		assertTrue(Modifier.isPrivate(contact.getModifiers()),
				"private 이 아니게 됐다면 누가 재정의할 수 있다 — 재정의되는 메서드의 믹스인은 조용히 죽는다");
		assertFalse(Modifier.isStatic(contact.getModifiers()));
	}

	/** 사람이 드래곤을 때리는 쪽은 <b>다른</b> 메서드다. 둘이 섞이면 드래곤이 무적이 된다. */
	@Test
	void 사람이_때리는_길은_서술자가_다르다() throws NoSuchMethodException {
		Method byPlayer = EnderDragon.class.getDeclaredMethod("hurt", ServerLevel.class,
				net.minecraft.world.entity.boss.enderdragon.EnderDragonPart.class,
				net.minecraft.world.damagesource.DamageSource.class, float.class);
		assertEquals(boolean.class, byPlayer.getReturnType(),
				"이쪽이 사람의 피해가 지나는 길이다. 믹스인이 여기를 물면 드래곤을 못 잡는다");
	}

	/**
	 * 믹스인이 <b>등록되어 있다.</b>
	 *
	 * <p>{@code sharedfate.mixins.json} 에 이름을 안 넣으면 파일만 있고 아무 일도 하지 않는다 —
	 * 컴파일도 빌드도 조용하고, 증상은 「최후의 저항에서 붙자마자 전멸한다」로만 나온다.
	 */
	@Test
	void 접촉_피해_믹스인이_등록되어_있다() throws IOException {
		try (InputStream in = DragonLastStand.class.getResourceAsStream("/sharedfate.mixins.json")) {
			assertNotNull(in, "sharedfate.mixins.json 이 클래스패스에 없다");
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(json.contains("\"EnderDragonContactDamageMixin\""),
					"EnderDragonContactDamageMixin 이 mixins.json 에 없다 — 파일만 있고 안 붙는다");
		}
	}

	/** 믹스인이 깃발을 읽는 자리가 이 파일이다. 둘이 갈라지면 접촉 피해가 영영 안 꺼진다. */
	@Test
	void 믹스인이_우리_깃발을_읽는다() {
		String bytes = mixinBytes();
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStand"),
				"믹스인이 DragonLastStand 를 안 본다 — 무엇을 보고 끄는지 알 수 없다");
		assertTrue(bytes.contains("contactDamageOff"), "깃발 이름이 바뀌었다");
		assertTrue(bytes.contains("(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V"),
				"서술자가 바뀌었다 — refmap 이 없어 이 시험 말고는 아무도 못 잡는다");
	}

	/** 블록 부수기는 남긴다. 믹스인이 {@code checkWalls} 쪽을 물면 발판이 안 날아간다. */
	@Test
	void 블록_부수기는_건드리지_않는다() {
		assertFalse(mixinBytes().contains("checkWalls"),
				"포탈 주변 발판이 계속 날아가는 것이 이 페이즈의 압박 중 하나다 — 끄지 말 것");
	}

	// ------------------------------------------------------------------ ⑤ 고르는 규칙

	@Test
	void 같은_패턴이_연속으로_나오지_않는다() {
		for (DragonLastStand.Pattern previous : DragonLastStand.Pattern.values()) {
			assertFalse(open(previous, 0L, DragonLastStand.ZoneClock.IDLE, 1_000L).contains(previous),
					previous + " 가 두 번 연속 나올 수 있다");
		}
	}

	@Test
	void 브레스는_쓴_뒤_8초_쉰다() {
		long usedAt = 1_000L;
		long readyAt = usedAt + DragonLastStand.BREATH_COOLDOWN_TICKS;
		assertEquals(160, DragonLastStand.BREATH_COOLDOWN_TICKS, "8초다");
		assertFalse(DragonLastStand.breathOpen(readyAt, DragonLastStand.ZoneClock.IDLE, readyAt - 1),
				"쿨다운이 1틱 남았는데 나갔다");
		assertTrue(DragonLastStand.breathOpen(readyAt, DragonLastStand.ZoneClock.IDLE, readyAt),
				"쿨다운이 끝났는데 안 나간다");
	}

	@Test
	void 축소_중에는_브레스가_안_나간다() {
		DragonLastStand.ZoneClock shrinking = new DragonLastStand.ZoneClock(true, Long.MIN_VALUE);
		assertFalse(DragonLastStand.breathOpen(Long.MIN_VALUE, shrinking, 5_000L),
				"안전지대가 줄어드는 동안 브레스가 함께 오면 피할 곳이 두 번 사라진다");
	}

	@Test
	void 축소_직후_3초는_브레스가_안_나간다() {
		long shrinkEnded = 5_000L;
		DragonLastStand.ZoneClock justShrank =
				new DragonLastStand.ZoneClock(false, shrinkEnded);
		assertEquals(60, DragonLastStand.BREATH_AFTER_SHRINK_TICKS, "3초다");
		assertFalse(DragonLastStand.breathOpen(Long.MIN_VALUE, justShrank, shrinkEnded + 59),
				"축소 직후 3초가 안 지났는데 나갔다");
		assertTrue(DragonLastStand.breathOpen(Long.MIN_VALUE, justShrank, shrinkEnded + 60),
				"3초가 지났는데 안 나간다");
	}

	/**
	 * 아직 한 번도 안 줄었을 때 <b>넘치지 않는다.</b>
	 *
	 * <p>{@code now - Long.MIN_VALUE} 는 오버플로로 음수가 되어 「3초가 안 지났다」로 읽힌다 —
	 * 그러면 안전지대가 붙기 전까지 브레스가 <b>영영</b> 안 나온다.
	 */
	@Test
	void 아직_안_줄었으면_브레스를_막지_않는다() {
		assertTrue(DragonLastStand.breathOpen(Long.MIN_VALUE, DragonLastStand.ZoneClock.IDLE, 0L),
				"축소가 한 번도 없었는데 「축소 직후」로 읽혔다");
	}

	/**
	 * ✅⚠ <b>이제 어느 조합에서도 고를 것이 있다.</b> 패턴이 넷이 되어 구멍이 닫혔다.
	 *
	 * <p>이 시험은 세 번째 모양이다. 처음에는 「어떤 조합에서도 고를 것이 남는다」였고, 번개가
	 * 패턴 풀에서 빠지면서 그것이 거짓이 되어 <b>「비는 자리가 그 하나뿐인가」</b>로 좁혔다.
	 * 사람이 공허 흡입과 십자 균열을 더해 <b>다시 「비지 않는가」로 돌아왔다.</b>
	 *
	 * <p>근거는 짧다 — 막히는 것은 <b>브레스와 직전 것</b>뿐이고 패턴이 넷이므로 최악이어도
	 * <b>둘</b>이 남는다. 그래서 남는 수의 <b>하한</b>까지 함께 센다: 제한을 하나 더 거는 사람이
	 * 「비지는 않네」로 넘기지 못하게, <b>여유가 몇 칸인지</b>를 숫자로 붙들어 둔다.
	 */
	@Test
	void 어느_조합에서도_고를_것이_있다() {
		List<DragonLastStand.ZoneClock> clocks = List.of(
				DragonLastStand.ZoneClock.IDLE,
				new DragonLastStand.ZoneClock(true, Long.MIN_VALUE),
				new DragonLastStand.ZoneClock(true, 1_000L),
				new DragonLastStand.ZoneClock(false, 1_000L));
		List<DragonLastStand.Pattern> previous = new ArrayList<>();
		previous.add(null);
		previous.addAll(List.of(DragonLastStand.Pattern.values()));

		int fewest = Integer.MAX_VALUE;
		for (DragonLastStand.Pattern before : previous) {
			for (DragonLastStand.ZoneClock clock : clocks) {
				for (long breathReadyAt : new long[] {Long.MIN_VALUE, 1_000L, 10_000L}) {
					List<DragonLastStand.Pattern> options =
							open(before, breathReadyAt, clock, 1_010L);
					assertFalse(options.isEmpty(),
							"고를 것이 하나도 없다 — 직전 " + before + " · 보더 " + clock
									+ " · 브레스 준비 " + breathReadyAt);
					assertNotNull(DragonLastStand.pick(options, 0.5));
					fewest = Math.min(fewest, options.size());
				}
			}
		}
		assertEquals(4, DragonLastStand.Pattern.values().length,
				"패턴이 넷이라는 전제 위에 아래 하한이 서 있다");
		assertEquals(2, fewest,
				"가장 나쁜 조합(직전 것 하나 + 브레스 잠김)에서 남는 수가 " + fewest
						+ " 다. 제한을 하나 더 걸면 이 수가 줄고, 1 이 되는 날 구멍이 다시 열린다");
	}

	/**
	 * 브레스가 열려 있으면 <b>셋</b>이 남는다.
	 *
	 * <p>위의 하한 2 가 「브레스 잠김」 하나에만 달려 있다는 것을 따로 못박아 둔다.
	 */
	@Test
	void 브레스가_열려_있으면_셋이_남는다() {
		for (DragonLastStand.Pattern before : DragonLastStand.Pattern.values()) {
			List<DragonLastStand.Pattern> options =
					open(before, Long.MIN_VALUE, DragonLastStand.ZoneClock.IDLE, 1_000L);
			assertEquals(DragonLastStand.Pattern.values().length - 1, options.size(),
					"직전 " + before + " 만 빠지고 나머지가 다 남아야 한다 — 실제 " + options);
		}
	}

	/**
	 * ⚠ <b>잠금은 브레스 하나에만 걸려 있다.</b>
	 *
	 * <p>새 패턴 둘에 쿨다운이나 축소 잠금을 걸지 않은 것이 판단이고, 그 판단이 코드에서 지켜지는지
	 * 여기서 붙든다. 「직전 것도 아니고 브레스도 아니면 언제나 열려 있다」가 그 뜻이다.
	 *
	 * <p>근거는 {@code DragonLastStand.Pattern} 에 적어 두었다 — 브레스의 제한 셋은 그 카드가
	 * <b>한 대에 전멸</b>이라 치르는 대가이고, 흡입·균열은 <b>세 대에 전멸</b>인 보통 카드다.
	 */
	@Test
	void 브레스_말고는_잠기지_않는다() {
		List<DragonLastStand.ZoneClock> clocks = List.of(
				DragonLastStand.ZoneClock.IDLE,
				new DragonLastStand.ZoneClock(true, Long.MIN_VALUE),
				new DragonLastStand.ZoneClock(true, 1_000L),
				new DragonLastStand.ZoneClock(false, 1_000L));
		for (DragonLastStand.ZoneClock clock : clocks) {
			for (long breathReadyAt : new long[] {Long.MIN_VALUE, 1_000L, 10_000L}) {
				List<DragonLastStand.Pattern> options = open(null, breathReadyAt, clock, 1_010L);
				for (DragonLastStand.Pattern pattern : DragonLastStand.Pattern.values()) {
					if (pattern == DragonLastStand.Pattern.CONE_BREATH) {
						continue;
					}
					assertTrue(options.contains(pattern),
							pattern + " 이 " + clock + " 에서 잠겼다 — 제한을 하나 더 걸었다면"
									+ " allowed 의 여유 하한(2)을 다시 셀 것");
				}
			}
		}
	}

	/** 굴림이 목록 밖으로 나가지 않는다. 0 과 1 은 경계라 실제로 틀리는 자리다. */
	@Test
	void 굴림이_목록_밖으로_나가지_않는다() {
		List<DragonLastStand.Pattern> options =
				open(null, Long.MIN_VALUE, DragonLastStand.ZoneClock.IDLE, 1_000L);
		Set<DragonLastStand.Pattern> seen = EnumSet.noneOf(DragonLastStand.Pattern.class);
		for (int step = 0; step <= 1000; step++) {
			DragonLastStand.Pattern chosen = DragonLastStand.pick(options, step / 1000.0);
			assertNotNull(chosen);
			assertTrue(options.contains(chosen));
			seen.add(chosen);
		}
		assertEquals(options.size(), seen.size(), "굴림을 전부 훑었는데 안 나오는 패턴이 있다");
	}

	@Test
	void 쉬는_시간이_2초에서_4초다() {
		assertEquals(40, DragonLastStand.REST_MIN_TICKS);
		assertEquals(80, DragonLastStand.REST_MAX_TICKS);
		for (int step = 0; step <= 1000; step++) {
			int rest = DragonLastStand.restTicks(step / 1000.0);
			assertTrue(rest >= DragonLastStand.REST_MIN_TICKS
							&& rest <= DragonLastStand.REST_MAX_TICKS,
					"쉬는 시간이 2~4초를 벗어났다: " + rest);
		}
		assertEquals(DragonLastStand.REST_MIN_TICKS, DragonLastStand.restTicks(0.0));
		assertEquals(DragonLastStand.REST_MAX_TICKS, DragonLastStand.restTicks(0.9999));
	}

	// ------------------------------------------------------------------ ① 진입 값

	/**
	 * 체력은 <b>%p</b> 로 오른다.
	 *
	 * <p>남은 체력에 곱하면 30% 가 36% 가 된다. 문서가 「30% → 50%」라고 적어 둔 것을 값으로
	 * 지킨다.
	 */
	@Test
	void 드래곤_체력이_20퍼센트포인트_오른다() {
		float max = 2400.0F;
		float at30 = max * 0.30F;
		assertEquals(max * 0.50F, DragonLastStand.healedHealth(at30, max), 0.01F,
				"30% 에서 들어오면 50% 가 되어야 한다");
	}

	@Test
	void 체력_회복은_최대치를_넘지_않는다() {
		float max = 2400.0F;
		assertEquals(max, DragonLastStand.healedHealth(max * 0.95F, max), 0.01F);
	}

	@Test
	void 진입_무적이_3초다() {
		assertEquals(60, DragonLastStand.ENTRY_GRACE_TICKS,
				"화면을 안 띄우는 자리라 이 3초가 읽을 시간이기도 하다");
	}

	/** 문턱이 두 곳에서 갈리지 않는가. 재는 곳은 {@code DragonTrialManager} 하나여야 한다. */
	@Test
	void 진입_문턱이_30퍼센트다() {
		assertEquals(0.30F, DragonLastStand.ENTRY_HEALTH_RATIO, 0.0001F);
	}

	// ------------------------------------------------------------------ 확정된 피해 값

	/**
	 * <b>부채꼴 브레스 65 는 피해원을 고르지 않으면 「한 대에 전멸」이 아니다.</b>
	 *
	 * <p>이 시험이 붙들고 있는 것이 이 페이즈에서 가장 놓치기 쉬운 값이다. 65 를 적어 놓고
	 * {@code lightningBolt()} 로 쏘면 무장 기준 <b>19.66</b> 이라 팀 체력 20 에 <b>0.34</b> 가
	 * 남는다 — 「한 대에 전멸」이 <b>하트 한 칸도 안 되는 차이</b>로 거짓이 된다. 값을 올려
	 * 맞추지 말고 하드 곱이 걸리거나 방어를 지나는 피해원을 쓸 것.
	 */
	@Test
	void 부채꼴_브레스는_피해원에_따라_전멸이_갈린다() {
		float bolt = GearedDamage.afterGear(DragonLastStand.CONE_BREATH_DAMAGE,
				GearedDamage.Source.LIGHTNING_BOLT);
		float explosion = GearedDamage.afterGear(DragonLastStand.CONE_BREATH_DAMAGE,
				GearedDamage.Source.EXPLOSION);
		float magic = GearedDamage.afterGear(DragonLastStand.CONE_BREATH_DAMAGE,
				GearedDamage.Source.MAGIC);

		assertTrue(bolt < GearedDamage.TEAM_HEALTH,
				"lightningBolt() 로 쏘면 한 대에 안 죽는다 — 실제 값 " + bolt);
		assertTrue(explosion >= GearedDamage.TEAM_HEALTH,
				"explosion(null, null) 이면 한 대에 전멸이어야 한다 — 실제 값 " + explosion);
		assertTrue(magic >= GearedDamage.TEAM_HEALTH,
				"magic() 이면 한 대에 전멸이어야 한다 — 실제 값 " + magic);
	}

	/**
	 * 상시 번개 35 는 「낙뢰」와 <b>같은 값이자 같은 계산</b>이다.
	 *
	 * <p>문서가 「낙뢰와 같은 방식」이라고 적어 둔 것을 값으로 지킨다. 두 곳이 갈라지면
	 * 사람이 배운 「번개는 세 대에 죽는다」가 자리마다 달라진다.
	 */
	@Test
	void 상시_번개는_낙뢰와_같은_값이다() {
		TrialCatalog.Trial lightning = TrialCatalog.byId("sharedfate:lightning_storm");
		assertNotNull(lightning);
		TrialCatalog.Risk.DelayedStrike strike =
				(TrialCatalog.Risk.DelayedStrike) lightning.risks().getFirst();
		assertEquals(strike.damage(), DragonLastStand.LIGHTNING_DAMAGE, 0.001F,
				"「낙뢰」와 같은 값이어야 한다 — 한쪽을 고치면 다른 쪽도 함께 볼 것");

		float perHit = GearedDamage.afterGear(DragonLastStand.LIGHTNING_DAMAGE,
				GearedDamage.Source.LIGHTNING_BOLT);
		assertTrue(GearedDamage.wipesInThree(perHit),
				"무장 기준 세 대에 전멸이어야 한다 — 실제 한 대 " + perHit);
	}

	/**
	 * ⚠ <b>공허 흡입 35 는 사람이 적은 「무장 기준 6.9」를 만드는 피해원이라야 한다.</b>
	 *
	 * <p>사람이 값과 <b>무장 기준 값을 함께</b> 적었으므로 값이 피해원을 정했다. 실행기가
	 * {@code explosion} 으로 바꾸면 10.40 이라 「큰 카드 한 대분」이 아니라 <b>두 대에 전멸</b>이
	 * 되고, 그 어긋남은 로그에 한 줄도 안 남는다.
	 */
	@Test
	void 공허_흡입은_무장_기준_한_대_6점9다() {
		assertEquals(35.0F, DragonLastStand.VOID_SUCTION_DAMAGE, 0.001F, "사람이 정한 값이다");
		assertEquals(DragonLastStand.LIGHTNING_DAMAGE, DragonLastStand.VOID_SUCTION_DAMAGE, 0.001F,
				"상시 번개와 같은 값이다 — 「큰 카드 한 대분」이 그 뜻이다");

		float bolt = GearedDamage.afterGear(DragonLastStand.VOID_SUCTION_DAMAGE,
				GearedDamage.Source.LIGHTNING_BOLT);
		assertEquals(6.93F, bolt, 0.02F, "사람이 적은 6.9 는 lightningBolt() 여야 나온다");
		assertTrue(GearedDamage.wipesInThree(bolt),
				"세 대에 전멸이어야 한다 — 실제 한 대 " + bolt);
		assertTrue(GearedDamage.afterGear(DragonLastStand.VOID_SUCTION_DAMAGE,
						GearedDamage.Source.EXPLOSION) > 7.0F,
				"explosion 으로 바꾸면 사람이 적은 6.9 가 거짓이 된다");
	}

	/**
	 * ⚠ <b>십자 균열 23 은 사람이 적은 「무장 기준 6.8」을 만드는 피해원이라야 한다.</b>
	 *
	 * <p>여기는 흡입과 <b>반대쪽</b>이다 — {@code explosion} 이라야 6.77 이고
	 * {@code lightningBolt} 로 쏘면 4.56 이라 두 번을 다 맞아도 9.1 로 <b>안 죽는다</b>(세 번 긋던
	 * 때도 13.7 로 안 죽었다).
	 * {@code GearedDamage.TARGET_PER_HIT} 이 「난이도 곱이 있는 쪽은 23」이라고 적어 둔 그것이다.
	 */
	@Test
	void 십자_균열은_무장_기준_한_대_6점8다() {
		assertEquals(23.0F, DragonLastStand.CROSS_FISSURE_DAMAGE, 0.001F, "사람이 정한 값이다");

		float explosion = GearedDamage.afterGear(DragonLastStand.CROSS_FISSURE_DAMAGE,
				GearedDamage.Source.EXPLOSION);
		assertEquals(6.77F, explosion, 0.02F, "사람이 적은 6.8 은 explosion 이어야 나온다");
		assertTrue(GearedDamage.wipesInThree(explosion),
				"세 대에 전멸이어야 한다 — 실제 한 대 " + explosion);

		float bolt = GearedDamage.afterGear(DragonLastStand.CROSS_FISSURE_DAMAGE,
				GearedDamage.Source.LIGHTNING_BOLT);
		assertTrue(bolt * DragonLastStandPatterns.CROSS_ROUNDS < GearedDamage.TEAM_HEALTH,
				"lightningBolt() 로 쏘면 두 번을 다 맞아도 안 죽는다 — 실제 한 대 " + bolt);
	}

	/**
	 * 새 패턴 둘의 길이가 <b>값에서 나온다.</b>
	 *
	 * <p>길이를 손으로 적으면 예고나 흡입 시간을 고치는 사람이 여기를 빠뜨려, 앞 패턴이 끝나기
	 * 전에 다음 것이 겹쳐 돌거나 드래곤이 아무것도 안 하는 시간이 생긴다.
	 */
	@Test
	void 새_패턴_둘의_길이가_값에서_나온다() {
		assertEquals(DragonLastStandPatterns.SUCK_WARN_TICKS
						+ DragonLastStandPatterns.SUCK_PULL_TICKS
						+ DragonLastStandPatterns.SUCK_AFTERGLOW_TICKS,
				DragonLastStand.Pattern.VOID_SUCTION.durationTicks());
		assertEquals(160, DragonLastStand.Pattern.VOID_SUCTION.durationTicks(),
				"예고 40 + 흡입 100 + 여운 20 이다");

		assertEquals(DragonLastStandPatterns.crossDurationTicks(),
				DragonLastStand.Pattern.CROSS_FISSURE.durationTicks(),
				"칸에 적은 합과 패턴 쪽 계산이 갈라졌다");
		assertEquals(100, DragonLastStand.Pattern.CROSS_FISSURE.durationTicks(),
				"60 + 30 + 여운 10 이다");

		// 터지는 틱이 길이 안에 들어 있어야 한다. 넘치면 피해가 아예 안 들어간다.
		assertTrue(DragonLastStandPatterns.SUCK_WARN_TICKS
						+ DragonLastStandPatterns.SUCK_PULL_TICKS
						< DragonLastStand.Pattern.VOID_SUCTION.durationTicks(),
				"흡입이 터지는 틱이 패턴 길이 밖이다");
		assertTrue(DragonLastStandPatterns.crossFireStep(DragonLastStandPatterns.CROSS_ROUNDS - 1)
						< DragonLastStand.Pattern.CROSS_FISSURE.durationTicks(),
				"마지막 십자가 터지는 틱이 패턴 길이 밖이다");
	}

	/**
	 * ⚠ <b>브레스 잠금의 미리 잠그는 길이는 브레스 길이여야 한다.</b>
	 *
	 * <p>흡입이 브레스보다 <b>길다</b>(160 대 100, 십자는 100 으로 같다). 그래서 「가장 긴 패턴」으로
	 * 잠금을 잡고 있었다면 여기서 어긋난다 — {@code DragonLastStandZone.shrinkLockoutLead} 가
	 * 재는 것은 <b>브레스 한 판이 들어갈 자리</b>이고 새 패턴은 축소와 겹쳐도 되므로 그대로여야
	 * 한다.
	 */
	@Test
	void 미리_잠그는_길이가_브레스_길이_그대로다() {
		assertEquals(DragonLastStand.Pattern.CONE_BREATH.durationTicks(),
				DragonLastStandZone.shrinkLockoutLead(),
				"새 패턴이 더 길다고 잠금을 늘리면 브레스가 나올 자리가 더 줄어든다");
		assertTrue(DragonLastStand.Pattern.VOID_SUCTION.durationTicks()
						> DragonLastStand.Pattern.CONE_BREATH.durationTicks(),
				"이 시험이 붙드는 상황(새 패턴이 브레스보다 길다)이 사라졌으면 설명을 고칠 것");
	}

	/**
	 * 안전지대 밖 피해는 <b>인원수로 곱하지 않는 초당 값</b>이다.
	 *
	 * <p>여기서 붙드는 것은 숫자 하나뿐이다. 붙이는 사람이 「넷이 밖이면 초당 32」로 만들면
	 * 5초에 전멸이라, 값을 상수로 못박아 두고 그 뜻을 여기에 적어 둔다.
	 */
	@Test
	void 안전지대_밖은_초당_8이다() {
		assertEquals(8.0F, DragonLastStand.OUTSIDE_ZONE_DAMAGE_PER_SECOND, 0.001F);
		float perSecond = GearedDamage.afterGear(DragonLastStand.OUTSIDE_ZONE_DAMAGE_PER_SECOND,
				GearedDamage.Source.LIGHTNING_BOLT);
		assertTrue(perSecond * 4.0F < GearedDamage.TEAM_HEALTH,
				"넷이 밖에 있어도 한 번에 전멸이면 안 된다 — 인원수로 곱하지 말 것");
	}

	// ------------------------------------------------- 「메마른 세계」만은 이어진다

	/**
	 * 「전부 멈춘다」의 <b>단 하나뿐인 예외</b>가 이 카드다.
	 *
	 * <p>사람이 「최후에서 남아도 될 거 같아」라고 정했다. 뽑은 팀에게만 이어지고, 뽑지 않은
	 * 팀에게는 아무 일도 없어야 한다. 카드 id 가 아니라 <b>위험 타입</b>으로 보는지까지 여기서
	 * 붙든다 — id 로 보면 카드 이름을 바꾸는 날 이 금지만 조용히 안 이어진다.
	 */
	@Test
	void 메마른_세계를_뽑은_팀에게만_금지가_이어진다() {
		DragonTrialSession dried = new DragonTrialSession(UUID.randomUUID(), 0L, true);
		assertTrue(dried.choose("sharedfate:dry_world", 0L));
		assertTrue(TrialDryWorld.holdsDryWorld(dried), "뽑았는데 이어지지 않는다");

		DragonTrialSession other = new DragonTrialSession(UUID.randomUUID(), 0L, true);
		assertTrue(other.choose("sharedfate:lightning_storm", 0L));
		assertFalse(TrialDryWorld.holdsDryWorld(other),
				"뽑지도 않은 팀이 최후의 저항에서 물을 못 놓게 된다");
		assertFalse(TrialDryWorld.holdsDryWorld(
						new DragonTrialSession(UUID.randomUUID(), 0L, true)),
				"한 장도 안 뽑은 팀");
		assertFalse(TrialDryWorld.holdsDryWorld(null));
	}

	/**
	 * 최후의 저항이 도는 동안 <b>기한이 계속 밀린다.</b>
	 *
	 * <p>{@code holdBan} 은 {@code tick} 과 최후의 저항이 함께 쓰는 한 줄이다. 두 곳이 다른
	 * 값을 밀면 최후의 저항에서만 금지가 깜빡인다.
	 */
	@Test
	void 최후의_저항_동안_금지가_살아_있다() {
		TrialDryWorld.clearState();
		long now = 10_000L;
		TrialDryWorld.holdBan(now);
		assertTrue(TrialDryWorld.banHolds(now, TrialDryWorld.banDeadline()),
				"민 그 틱에 금지가 안 살아 있다");
		assertTrue(TrialDryWorld.banHolds(now + TrialDryWorld.BAN_GRACE_TICKS - 1,
						TrialDryWorld.banDeadline()),
				"기한이 끝나기 전에 풀렸다");
		// 미는 쪽이 멈추면 저절로 풀린다. 기한 방식을 그대로 둔 값이 이것이다.
		assertFalse(TrialDryWorld.banHolds(now + TrialDryWorld.BAN_GRACE_TICKS,
						TrialDryWorld.banDeadline()),
				"아무도 안 미는데 금지가 남았다 — 다음 판까지 물을 못 놓는다");
		TrialDryWorld.clearState();
	}

	/**
	 * ⚠ <b>전투가 끝나면 그 틱에 풀린다.</b>
	 *
	 * <p>드래곤을 잡으면 {@code onFightClosed} 가 {@code TrialRisks.clearState()} 를 부르고 그
	 * 안에 {@code TrialDryWorld.clearState()} 가 있다. 여기서 보는 것은 <b>그 사슬이 실제로
	 * 기한을 지우는가</b>다 — 사슬이 끊기면 다음 판까지 물을 못 놓는 사고가 된다.
	 */
	@Test
	void 전투가_끝나면_금지가_그_틱에_풀린다() {
		TrialDryWorld.holdBan(10_000L);
		assertTrue(TrialDryWorld.banHolds(10_000L, TrialDryWorld.banDeadline()));

		// onFightClosed 가 지나는 바로 그 길이다.
		TrialRisks.clearState();

		assertEquals(Long.MIN_VALUE, TrialDryWorld.banDeadline(),
				"TrialRisks.clearState 가 메마른 세계의 기한을 안 내린다");
		assertFalse(TrialDryWorld.banHolds(10_000L, TrialDryWorld.banDeadline()),
				"전투가 끝났는데 금지가 남았다");
	}

	/** 최후의 저항이 이 금지를 실제로 이어 주는가. 배선이 빠지면 2초 뒤 조용히 풀린다. */
	@Test
	void 최후의_저항이_금지를_이어_주는_배선이_있다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("com/sharedfate/sync/TrialDryWorld"),
				"DragonLastStand 가 TrialDryWorld 를 안 부른다 — 사람이 정한 것과 반대로 금지가 풀린다");
		assertTrue(bytes.contains("holdBanDuringLastStand"), "이어 주는 메서드 이름이 바뀌었다");
	}

	// ------------------------------------------------------------------ 배선

	/** {@code DragonTrialManager} 가 이 파일을 실제로 부르는가. 빠지면 페이즈가 영영 안 열린다. */
	@Test
	void 관리자가_최후의_저항을_부른다() {
		String bytes = read("/com/sharedfate/sync/DragonTrialManager.class",
				StandardCharsets.ISO_8859_1);
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStand"),
				"DragonTrialManager 가 DragonLastStand 를 안 부른다 — 체력 30% 에서 아무 일도 안 난다");
		assertTrue(bytes.contains("onFightClosed"), "처치 처리가 배선에서 빠졌다");
		assertTrue(bytes.contains("tickVictory"), "처치 뒤 무적이 배선에서 빠졌다");
	}

	/** {@code clearState} 가 관리자 쪽에서 불리는가. 안 부르면 지난 판의 깃발이 새 월드로 샌다. */
	@Test
	void 관리자가_상태를_비워_준다() {
		DragonLastStand.clearState();
		assertFalse(DragonLastStand.isRunning(java.util.UUID.randomUUID()));
		assertFalse(DragonLastStand.isVictorious(java.util.UUID.randomUUID()));
		assertFalse(DragonLastStand.contactDamageOff(),
				"비운 뒤에도 접촉 피해가 꺼져 있으면 다음 판의 드래곤이 사람을 못 때린다");
	}

	// ------------------------------------------------------------------ 팀이 사라진 판 (V1)

	/**
	 * 첫 패턴 전에 버려진 판은 <b>언제까지나</b> 보호막을 원한다 — 거두지 않으면 남의 tick 이 그 맥박을
	 * 대신 세운다.
	 *
	 * <p>2026-10-06 Orca 검토 F-verified 의 V1 의 전제다. 이 단언이 거짓이 되면(예: 보호막에 기한이
	 * 붙으면) {@link DragonLastStand#onTeamGone} 의 무게가 달라지므로 함께 본다.
	 */
	@Test
	void 첫_패턴_전에_버려진_판은_보호막을_영영_원한다() {
		UUID gone = UUID.randomUUID();
		DragonLastStand.openForTesting(gone, 1_000L);
		assertTrue(DragonLastStand.shieldWanted(1_000L + 20L * 60 * 60),
				"첫 패턴을 고르지 않은 판은 한 시간 뒤에도 보호막을 원해야 한다 — 그래서 거둬야 한다");
	}

	/**
	 * 팀이 사라지면 판을 거둔다 — 보호막·접촉 깃발·{@code STANDS} 가 함께 내려간다.
	 *
	 * <p>고치기 전에는 그 갈래({@code DragonTrialManager.tickSessions} 의 {@code team == null})가 세션만
	 * 지워, 다음 팀의 최후의 저항 내내 드래곤이 안 맞고(보호막) 서버 재시작까지 접촉 피해·날개
	 * 밀치기가 꺼졌다(F-verified V1).
	 */
	@Test
	void 팀이_사라지면_판을_거둔다() {
		UUID gone = UUID.randomUUID();
		DragonLastStand.openForTesting(gone, 1_000L);
		assertTrue(DragonLastStand.isRunning(gone));
		assertTrue(DragonLastStand.contactDamageOff());

		DragonLastStand.onTeamGone(null, gone);

		assertFalse(DragonLastStand.isRunning(gone), "사라진 팀의 판이 STANDS 에 남았다");
		assertFalse(DragonLastStand.shieldWanted(1_100L),
				"사라진 판이 보호막을 계속 원한다 — 다음 팀의 드래곤이 끝까지 안 맞는다");
		assertFalse(DragonLastStand.contactDamageOff(),
				"사라진 판 하나가 접촉 깃발을 붙들었다 — 서버 재시작까지 접촉 피해가 꺼진다");
		assertFalse(DragonLastStand.isVictorious(gone), "팀이 사라진 것은 처치가 아니다 — 무적을 주면 안 된다");
	}

	/** 다른 팀의 id 로는 아무것도 거두지 않는다. 판을 가진 팀만 판을 내린다. */
	@Test
	void 판이_없는_팀이_사라져도_남의_판은_그대로다() {
		UUID running = UUID.randomUUID();
		DragonLastStand.openForTesting(running, 1_000L);

		DragonLastStand.onTeamGone(null, UUID.randomUUID());
		DragonLastStand.onTeamGone(null, null);

		assertTrue(DragonLastStand.isRunning(running));
		assertTrue(DragonLastStand.contactDamageOff());
		assertTrue(DragonLastStand.shieldWanted(1_100L));
	}

	/**
	 * 관리자의 「팀이 사라졌다」 갈래가 실제로 {@link DragonLastStand#onTeamGone} 을 부르는가.
	 *
	 * <p>고치기 전의 클래스에는 이 이름이 없다 — 그 갈래는 {@code finished.add} 뒤 {@code continue} 뿐이었다.
	 */
	@Test
	void 관리자가_사라진_팀의_판을_거둔다() {
		String bytes = read("/com/sharedfate/sync/DragonTrialManager.class",
				StandardCharsets.ISO_8859_1);
		assertTrue(bytes.contains("onTeamGone"),
				"DragonTrialManager 가 onTeamGone 을 안 부른다 — 팀이 사라진 판이 STANDS 에 남는다(V1)");
	}

	/** 지킬 것 — 파티클은 긴 형식, 소리는 {@code playEach}, 자막은 없다. */
	@Test
	void 연출이_저장소의_규약을_지킨다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"파티클이 긴 형식이 아니다 — 짧은 형식은 32칸에서 잘린다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형식 sendParticles 가 섞여 있다");
		assertTrue(bytes.contains("playEach"), "굉음이 playEach 를 안 쓰면 사람 수만큼 겹친다");
		assertFalse(bytes.contains("showTitle"), "자막을 쓰지 않는다");
		assertFalse(bytes.contains("showActionBar"), "자막을 쓰지 않는다");
		assertFalse(bytes.contains("getGameTime"), "받은 now 를 쓸 것 — 시간을 스스로 읽지 않는다");
	}

	/**
	 * 보스바 이름을 {@code null} 로 되돌리지 않는다.
	 *
	 * <p>{@code EnderDragonFight.updateDragon} 은 {@code hasCustomName()} 일 때만 이름을
	 * 얹으므로, 이름을 지우면 보스바가 <b>마지막에 받은 이름을 그대로 들고 있는다.</b>
	 * 크리스탈로 드래곤을 되살리면 새 드래곤의 바에 「최후의 저항」이 남는다.
	 */
	@Test
	void 보스바_이름을_덮어쓰지_지우지_않는다() {
		assertTrue(classBytes().contains("entity.minecraft.ender_dragon"),
				"되돌릴 때 얹을 바닐라 이름이 없다 — null 로 지우면 옛 이름이 남는다");
		assertTrue(classText().contains("엔더 드래곤 : 최후의 저항"), "보스바 이름이 바뀌었다");
	}

	// ------------------------------------------------------------------ 도우미

	private static List<DragonLastStand.Pattern> open(DragonLastStand.Pattern previous,
			long breathReadyAt, DragonLastStand.ZoneClock zone, long now) {
		return DragonLastStand.allowed(previous, breathReadyAt, zone, now);
	}

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStand.class", StandardCharsets.ISO_8859_1);
	}

	/** 같은 파일을 UTF-8 로 읽은 것. 상수 풀의 <b>한글 문자열</b>을 찾을 때만 쓴다. */
	private static String classText() {
		return read("/com/sharedfate/sync/DragonLastStand.class", StandardCharsets.UTF_8);
	}

	private static String mixinBytes() {
		return read("/com/sharedfate/mixin/EnderDragonContactDamageMixin.class",
				StandardCharsets.ISO_8859_1);
	}

	/** 바닐라 클래스의 바이트. 우리가 기대고 있는 사실이 그 안에 있는지 본다. */
	private static String vanillaClassBytes(Class<?> type) {
		return read("/" + type.getName().replace('.', '/') + ".class",
				StandardCharsets.ISO_8859_1);
	}

	/** 컴파일된 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String read(String path, java.nio.charset.Charset charset) {
		try (InputStream in = DragonLastStandTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), charset);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
