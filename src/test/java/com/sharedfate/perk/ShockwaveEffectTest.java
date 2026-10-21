package com.sharedfate.perk;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.effect.ShockwaveEffect;
import com.sharedfate.sync.ShockwaveManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 골드 「파문」({@code shockwave})의 정의 읽기와 주기 셈, 후보 고르기를 본다.
 *
 * <p>몹을 찾아 밀어내는 일은 살아 있는 서버와 월드가 있어야 하므로 여기서 시험하지 않는다.
 * 대신 월드 없이 답이 정해지는 네 가지만 붙들어 둔다.
 *
 * <ul>
 *   <li>JSON 을 읽는 규칙과 범위를 벗어난 값을 자르는 규칙</li>
 *   <li>주기 경계 계산 — 어긋나면 파문이 영영 안 터지거나 매 틱 터진다</li>
 *   <li>{@link ShockwaveManager#advance} 의 「팀당 한 번」 — <b>이 모드에서 되풀이해 터졌던
 *       「인원수·회차만큼 곱해지는」 버그를 막는 자리다</b></li>
 *   <li>{@link ShockwaveManager#select} 가 후보를 <b>정확히 하나</b>로 줄이는가</li>
 * </ul>
 */
class ShockwaveEffectTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	@BeforeEach
	void clearMemory() {
		ShockwaveManager.reset();
	}

	// ------------------------------------------------------------------ 정의 읽기

	@Test
	void 뭉침_거리와_반경과_주기와_세기를_읽는다() {
		ShockwaveEffect effect = create("""
				{ "type": "shockwave", "distance": 10, "radius": 8,
				  "interval_seconds": 10, "strength": 1.2 }
				""");

		assertEquals(10, effect.distance());
		assertEquals(8, effect.radius());
		assertEquals(10, effect.intervalSeconds());
		assertEquals(1.2, effect.strength());
	}

	@Test
	void 아무것도_안_적으면_10칸_반경8_10초_세기1_2_다() {
		ShockwaveEffect effect = create("{ \"type\": \"shockwave\" }");

		assertEquals(ShockwaveEffect.DEFAULT_DISTANCE, effect.distance());
		assertEquals(ShockwaveEffect.DEFAULT_RADIUS, effect.radius());
		assertEquals(ShockwaveEffect.DEFAULT_INTERVAL_SECONDS, effect.intervalSeconds());
		assertEquals(ShockwaveEffect.DEFAULT_STRENGTH, effect.strength());
	}

	@Test
	void 카멜케이스로_적어도_읽는다() {
		ShockwaveEffect effect = create("""
				{ "type": "shockwave", "intervalSeconds": 30 }
				""");

		assertEquals(30, effect.intervalSeconds());
	}

	/** 주기는 초로 적고 실행부는 틱으로 센다. 여기서 20을 곱하는 것을 잊으면 20배 빨리 터진다. */
	@Test
	void 주기를_틱으로_바꿔_준다() {
		assertEquals(200, create("{ \"type\": \"shockwave\", \"interval_seconds\": 10 }")
				.intervalTicks());
		assertEquals(20, create("{ \"type\": \"shockwave\", \"interval_seconds\": 1 }")
				.intervalTicks());
	}

	// ------------------------------------------------------------------ 자르기

	/** 범위를 벗어나도 정의를 버리지 않는다. 값 하나 때문에 골드 증강이 통째로 사라지면 안 된다. */
	@Test
	void 뭉침_거리가_범위를_벗어나면_잘라_쓴다() {
		assertEquals(ShockwaveEffect.MIN_DISTANCE, distanceOf(1));
		assertEquals(ShockwaveEffect.MIN_DISTANCE, distanceOf(-40));
		assertEquals(ShockwaveEffect.MAX_DISTANCE, distanceOf(65));
		assertEquals(ShockwaveEffect.MAX_DISTANCE, distanceOf(100000));
		// 경계값은 그대로 남는다.
		assertEquals(ShockwaveEffect.MIN_DISTANCE, distanceOf(ShockwaveEffect.MIN_DISTANCE));
		assertEquals(ShockwaveEffect.MAX_DISTANCE, distanceOf(ShockwaveEffect.MAX_DISTANCE));
	}

	@Test
	void 반경이_범위를_벗어나면_잘라_쓴다() {
		assertEquals(ShockwaveEffect.MIN_RADIUS, radiusOf(0));
		assertEquals(ShockwaveEffect.MIN_RADIUS, radiusOf(-9));
		assertEquals(ShockwaveEffect.MAX_RADIUS, radiusOf(33));
		assertEquals(ShockwaveEffect.MAX_RADIUS, radiusOf(4096));
		assertEquals(ShockwaveEffect.MIN_RADIUS, radiusOf(ShockwaveEffect.MIN_RADIUS));
		assertEquals(ShockwaveEffect.MAX_RADIUS, radiusOf(ShockwaveEffect.MAX_RADIUS));
	}

	/** 0초 주기를 허용하면 매 틱 터져 반경 안이 통째로 안전지대가 된다. */
	@Test
	void 주기가_범위를_벗어나면_잘라_쓴다() {
		assertEquals(ShockwaveEffect.MIN_INTERVAL_SECONDS, secondsOf(0));
		assertEquals(ShockwaveEffect.MIN_INTERVAL_SECONDS, secondsOf(-5));
		assertEquals(ShockwaveEffect.MAX_INTERVAL_SECONDS, secondsOf(601));
		assertEquals(ShockwaveEffect.MAX_INTERVAL_SECONDS, secondsOf(99999));
		assertEquals(ShockwaveEffect.MIN_INTERVAL_SECONDS,
				secondsOf(ShockwaveEffect.MIN_INTERVAL_SECONDS));
		assertEquals(ShockwaveEffect.MAX_INTERVAL_SECONDS,
				secondsOf(ShockwaveEffect.MAX_INTERVAL_SECONDS));
	}

	@Test
	void 세기가_범위를_벗어나면_잘라_쓴다() {
		assertEquals(ShockwaveEffect.MIN_STRENGTH, strengthOf("0"));
		assertEquals(ShockwaveEffect.MIN_STRENGTH, strengthOf("-3.5"));
		assertEquals(ShockwaveEffect.MAX_STRENGTH, strengthOf("5.1"));
		assertEquals(ShockwaveEffect.MAX_STRENGTH, strengthOf("1000"));
		assertEquals(ShockwaveEffect.MIN_STRENGTH, strengthOf("0.1"));
		assertEquals(ShockwaveEffect.MAX_STRENGTH, strengthOf("5.0"));
	}

	/** 세기는 소수다. 정수로 읽으면 1.2 가 1 이 되어 넉백이 눈에 띄게 약해진다. */
	@Test
	void 세기의_소수점을_잃지_않는다() {
		assertEquals(1.2, strengthOf("1.2"));
		assertEquals(0.75, strengthOf("0.75"));
	}

	/** 숫자가 아닌 값은 없는 것으로 보고 기본값을 쓴다. {@code readInt} 의 규칙 그대로다. */
	@Test
	void 숫자가_아니면_기본값이다() {
		ShockwaveEffect effect = create("""
				{ "type": "shockwave", "distance": "열 칸", "radius": "여덟",
				  "interval_seconds": "십초", "strength": "세게" }
				""");

		assertEquals(ShockwaveEffect.DEFAULT_DISTANCE, effect.distance());
		assertEquals(ShockwaveEffect.DEFAULT_RADIUS, effect.radius());
		assertEquals(ShockwaveEffect.DEFAULT_INTERVAL_SECONDS, effect.intervalSeconds());
		assertEquals(ShockwaveEffect.DEFAULT_STRENGTH, effect.strength());
	}

	// ------------------------------------------------------------------ 작은 충격파 (solo_radius)

	/** 안 적으면 작은 충격파가 없다 — 이 필드가 생기기 전의 정의가 그대로 읽힌다. */
	@Test
	void solo_radius_를_안_적으면_작은_충격파가_없다() {
		ShockwaveEffect effect = create("{ \"type\": \"shockwave\", \"radius\": 8 }");

		assertFalse(effect.hasSolo());
		assertEquals(ShockwaveEffect.NO_SOLO, effect.soloRadius());
	}

	@Test
	void solo_radius_를_읽는다() {
		ShockwaveEffect effect = create("""
				{ "type": "shockwave", "distance": 25, "radius": 10, "solo_radius": 2,
				  "interval_seconds": 5, "strength": 2.4 }
				""");

		assertTrue(effect.hasSolo());
		assertEquals(2, effect.soloRadius());
		assertEquals(10, effect.radius());
	}

	@Test
	void solo_radius_는_카멜케이스로도_읽고_범위를_벗어나면_자른다() {
		assertEquals(3, create("{ \"type\": \"shockwave\", \"soloRadius\": 3 }").soloRadius());
		assertEquals(ShockwaveEffect.MIN_RADIUS,
				create("{ \"type\": \"shockwave\", \"solo_radius\": 1 }").soloRadius());
		assertEquals(ShockwaveEffect.MAX_RADIUS,
				create("{ \"type\": \"shockwave\", \"solo_radius\": 99 }").soloRadius());
		assertFalse(create("{ \"type\": \"shockwave\", \"solo_radius\": \"둘\" }").hasSolo(),
				"숫자가 아니면 없는 것으로 본다");
	}

	// ------------------------------------------------------------------ 주기 경계

	/**
	 * 경계는 게임 시간의 배수다. 10초 주기면 200틱마다 번호가 하나 오른다.
	 *
	 * <p>기억할 기준점이 없으므로 서버를 껐다 켜도 같은 답이 나온다 — <b>재시작으로 파문을
	 * 앞당길 수 없다</b>는 약속이 이 셈에 걸려 있다.
	 */
	@Test
	void 주기_번호는_게임_시간의_배수마다_오른다() {
		assertEquals(0L, ShockwaveManager.cycleAt(0L, 200));
		assertEquals(0L, ShockwaveManager.cycleAt(199L, 200));
		assertEquals(1L, ShockwaveManager.cycleAt(200L, 200));
		assertEquals(1L, ShockwaveManager.cycleAt(399L, 200));
		assertEquals(2L, ShockwaveManager.cycleAt(400L, 200));
		assertEquals(50L, ShockwaveManager.cycleAt(10_000L, 200));
	}

	/** 게임 시간이 음수로 조작된 월드에서도 경계가 어긋나지 않는다. */
	@Test
	void 음수_시각에서도_경계가_어긋나지_않는다() {
		assertEquals(-1L, ShockwaveManager.cycleAt(-1L, 200));
		assertEquals(-1L, ShockwaveManager.cycleAt(-200L, 200));
		assertEquals(-2L, ShockwaveManager.cycleAt(-201L, 200));
	}

	/** 주기가 0 이하면 나눌 수 없다. 터지지 않는 쪽으로 답을 고정한다. */
	@Test
	void 주기가_0_이면_언제나_같은_번호다() {
		assertEquals(0L, ShockwaveManager.cycleAt(0L, 0));
		assertEquals(0L, ShockwaveManager.cycleAt(12_345L, 0));
		assertEquals(0L, ShockwaveManager.cycleAt(12_345L, -20));
	}

	// ------------------------------------------------------------------ 팀당 한 번

	/**
	 * <b>이 시험이 이 증강의 심장이다.</b>
	 *
	 * <p>같은 회차에 두 번 참을 돌려주면 충격파가 그 틱 수만큼 터진다. 처음 본 팀에 곧바로
	 * 참을 돌려주면 증강을 얻자마자, 접속하자마자 터진다.
	 */
	@Test
	void 한_회차에_한_번만_터진다() {
		UUID team = UUID.randomUUID();

		// 처음 보는 팀이다. 번호만 잡고 터뜨리지 않는다.
		assertFalse(ShockwaveManager.advance(team, 200, 200L), "처음 보는 틱에는 터지지 않는다");
		// 같은 회차 안에서는 몇 번을 물어도 거짓이다.
		assertFalse(ShockwaveManager.advance(team, 200, 201L));
		assertFalse(ShockwaveManager.advance(team, 200, 399L));
		// 경계를 넘은 그 틱에 딱 한 번 참이다.
		assertTrue(ShockwaveManager.advance(team, 200, 400L), "경계를 넘으면 터진다");
		assertFalse(ShockwaveManager.advance(team, 200, 401L), "같은 회차에 두 번 터지면 안 된다");
		assertTrue(ShockwaveManager.advance(team, 200, 600L));
	}

	/** 시간 명령으로 여러 회차를 한꺼번에 건너뛰어도 파문은 한 번이다. */
	@Test
	void 여러_회차를_건너뛰어도_한_번만_터진다() {
		UUID team = UUID.randomUUID();

		assertFalse(ShockwaveManager.advance(team, 200, 0L));
		assertTrue(ShockwaveManager.advance(team, 200, 100_000L), "건너뛴 회차만큼 터지지 않는다");
		assertFalse(ShockwaveManager.advance(team, 200, 100_001L));
	}

	/** 팀마다 따로 센다. 한 팀이 터졌다고 다른 팀의 차례가 사라지면 안 된다. */
	@Test
	void 팀마다_따로_센다() {
		UUID one = UUID.randomUUID();
		UUID other = UUID.randomUUID();

		assertFalse(ShockwaveManager.advance(one, 200, 0L));
		assertFalse(ShockwaveManager.advance(other, 200, 0L));
		assertTrue(ShockwaveManager.advance(one, 200, 200L));
		assertTrue(ShockwaveManager.advance(other, 200, 200L));
	}

	/**
	 * 주기가 바뀌면 번호의 뜻 자체가 달라진다. 옛 번호와 견주는 것이 무의미하므로 그 틱에는
	 * 터뜨리지 않고 번호만 다시 잡는다.
	 */
	@Test
	void 주기가_바뀌면_번호만_다시_잡는다() {
		UUID team = UUID.randomUUID();

		assertFalse(ShockwaveManager.advance(team, 200, 0L));
		assertTrue(ShockwaveManager.advance(team, 200, 200L));
		assertFalse(ShockwaveManager.advance(team, 100, 400L), "주기가 바뀐 틱에는 터지지 않는다");
		assertTrue(ShockwaveManager.advance(team, 100, 500L));
	}

	/** 서버가 멈추면 기억을 비운다. 다음 월드에서 곧바로 터지지 않게 하는 자리다. */
	@Test
	void 기억을_비우면_다시_처음_보는_팀이_된다() {
		UUID team = UUID.randomUUID();

		assertFalse(ShockwaveManager.advance(team, 200, 0L));
		assertTrue(ShockwaveManager.advance(team, 200, 200L));

		ShockwaveManager.reset();

		assertFalse(ShockwaveManager.advance(team, 200, 400L), "비운 뒤에는 다시 번호만 잡는다");
	}

	// ------------------------------------------------------------------ 후보 고르기

	/**
	 * 세트 단계는 누적이라 한 팀이 {@code shockwave} 를 여럿 가질 수 있다. 전부 돌리면 충격파가
	 * 여러 번 터진다.
	 */
	@Test
	void 후보가_여럿이면_반경이_넓은_것_하나만_고른다() {
		ShockwaveEffect narrow = create("{ \"type\": \"shockwave\", \"radius\": 6 }");
		ShockwaveEffect wide = create("{ \"type\": \"shockwave\", \"radius\": 12 }");

		assertSame(wide, ShockwaveManager.select(List.of(narrow, wide)));
		assertSame(wide, ShockwaveManager.select(List.of(wide, narrow)));
	}

	/** 반경이 같으면 세기가 센 것이 이긴다. */
	@Test
	void 반경이_같으면_세기가_센_것이_이긴다() {
		ShockwaveEffect weak = create("{ \"type\": \"shockwave\", \"radius\": 8, \"strength\": 1.0 }");
		ShockwaveEffect strong =
				create("{ \"type\": \"shockwave\", \"radius\": 8, \"strength\": 2.5 }");

		assertSame(strong, ShockwaveManager.select(List.of(weak, strong)));
		assertSame(strong, ShockwaveManager.select(List.of(strong, weak)));
	}

	/** 반경도 세기도 같으면 더 자주 오는 쪽이 이긴다. */
	@Test
	void 반경과_세기가_같으면_주기가_짧은_것이_이긴다() {
		ShockwaveEffect slow = create("{ \"type\": \"shockwave\", \"interval_seconds\": 30 }");
		ShockwaveEffect fast = create("{ \"type\": \"shockwave\", \"interval_seconds\": 5 }");

		assertSame(fast, ShockwaveManager.select(List.of(slow, fast)));
		assertSame(fast, ShockwaveManager.select(List.of(fast, slow)));
	}

	/** 파문이 아닌 효과가 섞여 있어도 파문만 골라낸다. */
	@Test
	void 다른_효과는_거른다() {
		ShockwaveEffect wave = create("{ \"type\": \"shockwave\" }");
		PerkEffect other = new PerkEffect() {
		};

		assertSame(wave, ShockwaveManager.select(List.of(other, wave)));
		assertNull(ShockwaveManager.select(List.of(other)));
	}

	@Test
	void 후보가_없으면_null_이다() {
		assertNull(ShockwaveManager.select(null));
		assertNull(ShockwaveManager.select(List.of()));
	}

	// ------------------------------------------------------------------ 등록 확인

	/**
	 * <b>{@code PerkEffectType} 등록을 빠뜨리면 여기서 터진다.</b>
	 *
	 * <p>등록이 없으면 증강 정의를 읽을 때 「알 수 없는 효과 type」이라 그 증강이 통째로 버려진다.
	 * 빌드는 통과하고 로그 한 줄만 남으므로 이 시험 말고는 알 방법이 없다.
	 */
	@Test
	void 효과_타입에_등록되어_있다() {
		PerkEffectType type = PerkEffectType.fromId("shockwave");
		assertNotNull(type,
				"PerkEffectType 에 SHOCKWAVE(\"shockwave\", ShockwaveEffect::fromJson) 을 "
						+ "등록해야 한다");

		JsonObject json = JsonParser
				.parseString("{ \"type\": \"shockwave\", \"distance\": 10, \"radius\": 8, "
						+ "\"interval_seconds\": 10, \"strength\": 1.2 }")
				.getAsJsonObject();
		ShockwaveEffect effect = assertInstanceOf(ShockwaveEffect.class,
				type.create("sharedfate:파문", 0, json),
				"등록은 되어 있는데 다른 팩토리가 물려 있다");
		assertEquals(10, effect.distance());
		assertEquals(8, effect.radius());
		assertEquals(10, effect.intervalSeconds());
		assertEquals(1.2, effect.strength());
	}

	// ------------------------------------------------------------------ 도우미

	private static int distanceOf(int distance) {
		return create("{ \"type\": \"shockwave\", \"distance\": " + distance + " }").distance();
	}

	private static int radiusOf(int radius) {
		return create("{ \"type\": \"shockwave\", \"radius\": " + radius + " }").radius();
	}

	private static int secondsOf(int seconds) {
		return create("{ \"type\": \"shockwave\", \"interval_seconds\": " + seconds + " }")
				.intervalSeconds();
	}

	private static double strengthOf(String strength) {
		return create("{ \"type\": \"shockwave\", \"strength\": " + strength + " }").strength();
	}

	private static ShockwaveEffect create(String json) {
		JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
		return assertInstanceOf(ShockwaveEffect.class,
				ShockwaveEffect.fromJson("sharedfate:파문", 0, parsed));
	}
}
