package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「최후의 저항」 <b>진입 보호막</b>.
 *
 * <p>사람이 2026-10-04 에 <b>「최후의 저항 시작하고 첫 패턴 전까지 모든 공격 막는 쉴드 생기고 피해
 * 안 받게. 지금은 무슨 체력회복하면서 쳐맞는 거 같아」</b>라고 했다.
 *
 * <p>여기서 붙들어 두는 것 넷.
 *
 * <ol>
 *   <li>⚠⚠ <b>언제 서 있는가</b> — 진입 전 / 진입 연출 중 / 연출 뒤 쉬는 구간 / 첫 패턴이 시작하는
 *       틱 / 그 뒤. 고치기 전에는 <b>쉬는 구간에 진짜로 깎였다</b></li>
 *   <li>⚠ <b>{@code /kill} 이 듣는다</b> — 무적을 지나치는 피해는 통과한다</li>
 *   <li><b>부위 피해도 막힌다</b> — 판단이 부위를 안 받고, 부위가 지나는 문이 그 판단이 걸린 문이다</li>
 *   <li><b>깃발이 켜진 채 멈추지 않는다</b> — 맥박이 끊기면 두 틱 안에 꺼진다</li>
 * </ol>
 */
class DragonLastStandShieldTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	/** 정적 깃발이라 시험끼리 샌다. 앞뒤로 비운다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonLastStandShield.clearState();
	}

	// ------------------------------------------------------------------ ① 언제 서 있는가

	/**
	 * ⚠⚠ <b>시간표 다섯 칸.</b> 정상 흐름의 첫 패턴 틱은 진입 + 연출 160 + 쉬는 60 = 220 이다.
	 *
	 * <p>쉬는 구간(연출 끝 ~ 첫 패턴)이 이 작업의 핵심이다 — 고치기 전에는 그 60틱 동안
	 * {@code setHealth} 조차 안 돌아 피해가 그대로 들어갔다.
	 */
	@Test
	void 진입부터_첫_패턴_직전까지만_서_있다() {
		long began = 1_000L;
		long cinematicEnd = began + DragonLastStandEntry.LENGTH_TICKS;
		long firstPattern = cinematicEnd + DragonLastStand.ENTRY_GRACE_TICKS;
		assertEquals(began + 220L, firstPattern, "정상 흐름의 첫 패턴은 진입 뒤 220틱이다");

		// 진입 전.
		assertFalse(DragonLastStandShield.shielded(true, began, Long.MIN_VALUE, began - 1L),
				"진입 전에 보호막이 서면 30% 를 넘기는 마지막 한 방이 막힌다");
		// 진입한 그 틱부터.
		assertTrue(DragonLastStandShield.shielded(true, began, Long.MIN_VALUE, began),
				"진입한 틱에 안 서면 첫 한 틱이 샌다");
		// 진입 연출 내내.
		for (long now = began; now < cinematicEnd; now++) {
			assertTrue(DragonLastStandShield.shielded(true, began, Long.MIN_VALUE, now),
					"진입 연출 " + (now - began) + "틱째에 보호막이 없다");
		}
		// ⚠ 연출이 끝나고 첫 패턴까지 쉬는 구간 — 아직 첫 패턴을 안 골랐다.
		for (long now = cinematicEnd; now < firstPattern; now++) {
			assertTrue(DragonLastStandShield.shielded(true, began, Long.MIN_VALUE, now),
					"쉬는 구간 " + (now - cinematicEnd) + "틱째에 보호막이 없다 — 고치기 전의 그 구멍이다");
		}
		// 첫 패턴을 고른 틱 직전까지는 서 있다(advance 가 그 틱에 firstPatternAt 을 적는다).
		assertTrue(DragonLastStandShield.shielded(true, began, firstPattern, firstPattern - 1L));
		// 첫 패턴이 시작하는 그 틱부터는 맞는다.
		assertFalse(DragonLastStandShield.shielded(true, began, firstPattern, firstPattern),
				"첫 패턴이 시작한 틱에도 보호막이 서 있다 — 패턴이 도는 첫 틱을 공짜로 먹는다");
		// 그 뒤로는 영영 안 선다.
		for (long now = firstPattern; now < firstPattern + 20L * 120L; now += 7L) {
			assertFalse(DragonLastStandShield.shielded(true, began, firstPattern, now),
					"첫 패턴 뒤 " + (now - firstPattern) + "틱에 보호막이 다시 섰다");
		}
	}

	/**
	 * 걷히는 틱을 <b>시각으로 짐작하지 않는다</b> — 실제로 고른 틱을 따른다.
	 *
	 * <p>{@code advance} 가 고르기를 한 틱 미루면(빈 목록 → {@code REST_MIN_TICKS} 뒤 다시) 보호막도
	 * 그만큼 더 서 있어야 한다. 「220틱에 걷힌다」로 박아 두면 그때 패턴 없이 맞는 틈이 다시 생긴다.
	 */
	@Test
	void 걷히는_틱은_실제로_고른_틱을_따른다() {
		long began = 0L;
		long late = began + 220L + DragonLastStand.REST_MIN_TICKS;
		assertTrue(DragonLastStandShield.shielded(true, began, Long.MIN_VALUE, began + 230L),
				"아직 안 골랐으면 220틱이 지나도 서 있어야 한다");
		assertTrue(DragonLastStandShield.shielded(true, began, late, late - 1L));
		assertFalse(DragonLastStandShield.shielded(true, began, late, late));
	}

	/**
	 * ⚠ <b>재시작으로 되살린 판에는 보호막이 없다.</b>
	 *
	 * <p>{@code DragonLastStand.resume} 은 아무것도 다시 주지 않는다 — 진입 연출도 체력 +20%p 도.
	 * 보호막만 다시 서면 「서버를 껐다 켤 때마다 11초씩 안 맞는 드래곤」이 된다.
	 */
	@Test
	void 되살린_판에는_보호막이_없다() {
		for (long now = 0L; now < 400L; now++) {
			assertFalse(DragonLastStandShield.shielded(false, 0L, Long.MIN_VALUE, now),
					"되살린 판의 " + now + "틱째에 보호막이 섰다");
		}
	}

	// ------------------------------------------------------------------ ② /kill 은 듣는다

	/**
	 * ⚠ <b>무적을 지나치는 피해만 통과한다.</b> 나머지는 근접이든 원거리든 전부 막는다.
	 *
	 * <p>태그 내용은 데이터팩이라 살아 있는 서버 없이는 레지스트리로 볼 수 없다. 그래서 판단은
	 * 불리언으로 굴리고, 태그가 무엇을 담는지는 바닐라 jar 의 태그 파일을 그대로 읽는다
	 * ({@code TrialCrystalGuardTest} 와 같은 방법).
	 */
	@Test
	void 무적을_지나치는_피해만_통과한다() {
		assertTrue(DragonLastStandShield.blocks(false), "평범한 피해가 보호막을 지나갔다");
		assertFalse(DragonLastStandShield.blocks(true),
				"무적을 지나치는 피해까지 막으면 공허·generic_kill 로 드래곤을 치울 수 없다");

		assertNotNull(DamageTypeTags.BYPASSES_INVULNERABILITY);
		String tag = resource("/data/minecraft/tags/damage_type/bypasses_invulnerability.json");
		assertTrue(tag.contains("minecraft:generic_kill"), "generic_kill 이 태그에서 빠졌다");
		assertTrue(tag.contains("minecraft:out_of_world"), "공허가 태그에서 빠졌다");
		assertFalse(tag.contains("minecraft:player_attack"),
				"근접이 「무적을 지나치는」 태그에 들어왔다 — 보호막이 근접을 못 막는다");
		assertFalse(tag.contains("minecraft:arrow"),
				"화살이 「무적을 지나치는」 태그에 들어왔다 — 보호막이 화살을 못 막는다");

		// 실제 판별이 그 태그를 본다. 허용 목록(근접만 통과)을 들고 오면 뜻이 반대가 된다.
		String bytes = classBytes();
		assertTrue(bytes.contains("BYPASSES_INVULNERABILITY"), "무적을 지나치는 태그를 안 본다");
		assertFalse(bytes.contains("IS_PLAYER_ATTACK"),
				"보호막이 근접을 통과시키는 허용 목록을 들고 있다 — 사람 말은 「모든 공격」이다");
	}

	// ------------------------------------------------------------------ ③ 부위 피해도 막힌다

	/**
	 * <b>판단에 부위가 안 들어간다.</b> 머리로 들어오든 날개로 들어오든 본체로 들어오든 같은 답이다.
	 *
	 * <p>{@code decide} 의 인자에 부위가 없는 것이 그 보증이다 — 여기서는 그 함수가 막아야 할 때
	 * 막고 열어야 할 때 여는지를 본다.
	 */
	@Test
	void 판단에_부위가_없고_막을_때_막는다() throws NoSuchMethodException {
		Method decide = DragonLastStandShield.class.getDeclaredMethod("decide",
				boolean.class, int.class, int.class, boolean.class);
		for (Class<?> parameter : decide.getParameterTypes()) {
			assertFalse(EnderDragonPart.class.isAssignableFrom(parameter),
					"판단이 부위를 받는다 — 부위마다 답이 달라질 길이 열린다");
		}
		assertTrue(DragonLastStandShield.decide(true, 50, 50, false), "서 있는데 안 막았다");
		assertTrue(DragonLastStandShield.decide(true, 50, 51, false),
				"다음 틱 월드 처리(화살·폭발) 중에 안 막았다");
		assertFalse(DragonLastStandShield.decide(true, 50, 50, true), "/kill 계열을 막았다");
		assertFalse(DragonLastStandShield.decide(false, 50, 50, false), "깃발이 내려갔는데 막았다");
	}

	/**
	 * <b>부위 피해가 지나는 문이 보호막이 걸린 문이다.</b> 26.3 바이트코드에서 확인한 사실을 못박는다.
	 *
	 * <ul>
	 *   <li>{@code EnderDragonPart.hurtServer} 가 {@code final} 이고 {@code EnderDragon.hurt(4인자)} 를 부른다</li>
	 *   <li>{@code EnderDragon.hurtServer}(본체) 도 같은 {@code hurt(4인자)} 를 부른다</li>
	 *   <li>믹스인이 그 서술자를 물고 보호막 판단과 막힘 되먹임을 부른다</li>
	 * </ul>
	 */
	@Test
	void 부위와_본체가_같은_문을_지나고_그_문에_보호막이_걸려_있다() throws NoSuchMethodException {
		String gate = "(Lnet/minecraft/server/level/ServerLevel;"
				+ "Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;"
				+ "Lnet/minecraft/world/damagesource/DamageSource;F)Z";

		Method partHurt = EnderDragonPart.class.getDeclaredMethod("hurtServer", ServerLevel.class,
				DamageSource.class, float.class);
		assertTrue(Modifier.isFinal(partHurt.getModifiers()),
				"EnderDragonPart.hurtServer 가 final 이 아니다 — 하위에서 문을 우회할 길이 생겼다");
		assertTrue(vanillaBytes(EnderDragonPart.class).contains(gate),
				"부위가 더는 EnderDragon.hurt(4인자) 를 안 부른다 — 부위 피해가 보호막을 지나친다");

		Method bodyHurt = EnderDragon.class.getDeclaredMethod("hurtServer", ServerLevel.class,
				DamageSource.class, float.class);
		assertEquals(EnderDragon.class, bodyHurt.getDeclaringClass());
		Method door = EnderDragon.class.getDeclaredMethod("hurt", ServerLevel.class,
				EnderDragonPart.class, DamageSource.class, float.class);
		assertEquals(boolean.class, door.getReturnType());

		String mixin = read("/com/sharedfate/mixin/EnderDragonPerchRangedImmunityMixin.class");
		assertTrue(mixin.contains(gate), "믹스인이 다른 문을 문다");
		assertTrue(mixin.contains("com/sharedfate/sync/DragonLastStandShield"),
				"믹스인이 보호막을 안 본다 — 진입 중 피해가 그대로 들어간다");
		assertTrue(mixin.contains("refuses"), "보호막 판단 이름이 바뀌었다");
		assertTrue(mixin.contains("deflect"),
				"막은 것을 보이지 않는다 — 아무 반응 없이 0 이면 사람은 버그로 읽는다");
	}

	// ------------------------------------------------------------------ ④ 깃발이 켜진 채 멈추지 않는다

	/**
	 * ⚠ <b>맥박이 끊기면 꺼진다.</b> {@code DragonLastStand.tick} 이 안 불리는 길(시련을 끔 · 팀
	 * 해체)에서 「영영 안 맞는 드래곤」이 남지 않게 한다.
	 */
	@Test
	void 맥박이_끊기면_두_틱_안에_꺼진다() {
		assertTrue(DragonLastStandShield.alive(100, 100), "틱 사이(공격 꾸러미 처리)에서 꺼졌다");
		assertTrue(DragonLastStandShield.alive(100, 101), "다음 틱 월드 처리 중에 꺼졌다");
		assertFalse(DragonLastStandShield.alive(100, 102),
				"한 틱을 통째로 못 세웠는데 서 있다 — 내리는 줄이 빠진 길에서 드래곤이 영영 안 맞는다");
		assertFalse(DragonLastStandShield.alive(100, 99), "서버가 바뀐 값(음수)을 살아 있다고 읽었다");
		// int 가 한 바퀴 돌아도 맞다.
		assertTrue(DragonLastStandShield.alive(Integer.MAX_VALUE, Integer.MIN_VALUE));
		assertEquals(1, DragonLastStandShield.PULSE_SLACK_TICKS);
	}

	/** 내리는 줄이 깃발을 내린다. */
	@Test
	void 비우면_깃발이_내려간다() {
		DragonLastStandShield.clearState();
		assertFalse(DragonLastStandShield.raisedFlag());
		assertFalse(DragonLastStandShield.refuses(null, null), "월드가 없는데 막았다");
	}

	/**
	 * ⚠ <b>되돌릴 것을 남기지 않는다</b> — 저장되는 무적 깃발도, 판이 얼면 멈추는 시계도 안 쓴다.
	 */
	@Test
	void 저장되는_무적과_얼어붙는_시계를_안_쓴다() {
		String bytes = classBytes();
		assertFalse(bytes.contains("setInvulnerable"),
				"저장되는 무적 깃발을 쓰면 보호막 도중 서버가 죽었을 때 무적 드래곤이 남는다");
		assertFalse(bytes.contains("getGameTime"), "받은 now 를 쓸 것 — 판이 얼면 멈추는 시계다");
		assertTrue(bytes.contains("getTickCount"), "맥박을 서버 틱 수로 재지 않는다");
		assertFalse(bytes.contains("setPhase"), "페이즈는 DragonLastStand.hold 하나만 만진다");
	}

	// ------------------------------------------------------------------ 보이는가 · 들리는가

	/**
	 * 반구가 <b>드래곤 몸 밖, 보라 신호기 안</b>에 선다. 모든 점이 반구 위다.
	 */
	@Test
	void 반구가_드래곤과_신호기_사이에_선다() {
		assertTrue(DragonLastStandShield.DOME_RADIUS > 8.0,
				"앉은 드래곤 상자(가로 16칸)의 반보다 안이면 막이 날개에 묻힌다");
		assertTrue(DragonLastStandShield.DOME_RADIUS < DragonLastStandEntry.BEACON_HALF_SIDE,
				"막이 보라 신호기 넷보다 밖이면 「빛 안의 보호막」으로 안 읽힌다");
		for (int index = 0; index < DragonLastStandShield.DOME_POINTS; index++) {
			Vec3 at = DragonLastStandShield.domeOffset(index);
			assertEquals(DragonLastStandShield.DOME_RADIUS, at.length(), 1.0E-6,
					index + "번째 점이 반구 위가 아니다");
			assertTrue(at.y >= 0.0, index + "번째 점이 땅 밑이다 — 보이지 않는 점에 예산을 쓴다");
		}
	}

	/**
	 * <b>점 예산</b> — 5장의 400~440.
	 *
	 * <p>보호막이 서 있는 틱에는 진입 연출과만 겹친다(패턴·번개·파도는 안 돈다). 걷히는 틱은 첫
	 * 패턴의 첫 틱이라 패턴 최악과 겹치고, 그 틱에 보호막이 더하는 것은 파편 꾸러미뿐이다.
	 */
	@Test
	void 점_예산_안이다() {
		assertEquals(0, DragonLastStandShield.DOME_POINTS % DragonLastStandShield.DOME_STRIDE,
				"나눠 그리기가 딱 떨어지지 않으면 위상에 따라 한 점 더 나간다 — 셈을 다시 할 것");
		assertTrue(DragonLastStandShield.DOME_STRIDE < 8,
				"먼지 수명(최소 8틱)보다 길게 나누면 막이 영영 안 닫힌다");
		assertEquals(54, DragonLastStandShield.worstShieldTickPoints(),
				"반구 36 + 막힘 불꽃 6 + 하강 궤적 12 — 값을 바꿨으면 클래스 설명의 표도 고칠 것");
		assertTrue(DragonLastStandShield.worstShieldTickPoints() <= 400);

		int shatterTick = DragonLastStandPatterns.worstCasePointsPerTick()
				+ DragonLastStandObjects.worstCasePointsPerTick()
				+ DragonLastStandShield.SHATTER_POINTS;
		assertTrue(shatterTick <= TrialLandingShock.MAX_POINTS_PER_TICK,
				"보호막이 걷히는 틱(= 첫 패턴의 첫 틱)이 예산 밖이다 — 실제 " + shatterTick);
	}

	/**
	 * 소리 — 막힘은 저장소의 한 벌, 걷힘은 <b>아무도 안 쓰는 파일</b>.
	 *
	 * <p>{@code GLASS_BREAK} 는 26.3 {@code sounds.json} 에서 {@code random/glass1~3} 이다.
	 * {@code SHIELD_BREAK} 는 이름만 방패이고 {@code random/break}(도구 부서지는 소리)라 버렸다.
	 * 파일 대조는 jar 밖(에셋 색인)이라 시험이 못 하고 클래스 설명에 적어 두었다.
	 */
	@Test
	void 소리가_막힘_한_벌과_깨짐_하나다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("SHIELD_BLOCK"), "막힘 소리가 저장소의 한 벌과 다르다");
		assertTrue(bytes.contains("ELECTRIC_SPARK"), "막힘 불꽃이 저장소의 한 벌과 다르다");
		assertTrue(bytes.contains("GLASS_BREAK"), "걷히는 소리가 바뀌었다 — sounds.json 을 다시 볼 것");
		assertFalse(bytes.contains("SHIELD_BREAK"),
				"SHIELD_BREAK 는 random/break(도구 부서지는 소리)다 — 막이 깨지는 소리가 아니다");
		assertTrue(bytes.contains("playEach"), "소리가 playEach 를 안 쓰면 사람 수만큼 겹친다");
		assertTrue(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;ZZDDDIDDDD)I"),
				"파티클이 긴 형식이 아니다 — 짧은 형식은 32칸에서 잘린다");
		assertFalse(bytes.contains("(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I"),
				"짧은 형식 sendParticles 가 섞여 있다");
		assertEquals(10, DragonLastStandShield.DEFLECT_SOUND_GAP_TICKS,
				"막힘 소리 간격이 바닐라 피격 무적시간(10)과 다르다 — DragonPerch 와 한 벌이 깨진다");
		assertEquals(6, DragonLastStandShield.DEFLECT_POINTS, "막힘 불꽃 수가 DragonPerch 와 다르다");
	}

	/**
	 * <b>배선</b> — {@code DragonLastStand} 가 보호막을 세우고, 걷히는 틱에 깨뜨리고, 닫을 때 내린다.
	 */
	@Test
	void 최후의_저항이_보호막을_세우고_깨뜨리고_내린다() {
		String stand = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(stand.contains("DragonLastStandShield"), "최후의 저항이 보호막을 모른다");
		assertTrue(stand.contains("shielded"), "판정을 DragonLastStand 쪽에 복사했다");
		assertTrue(stand.contains("shatter"), "걷힐 때 깨지는 소리가 없다");
		assertTrue(stand.contains("firstPatternAt"), "걷히는 틱을 실제로 고른 틱으로 안 적는다");
	}

	// ------------------------------------------------------------------ 도우미

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonLastStandShield.class");
	}

	private static String vanillaBytes(Class<?> type) {
		return read("/" + type.getName().replace('.', '/') + ".class");
	}

	private static String resource(String path) {
		return readWith(path, StandardCharsets.UTF_8);
	}

	private static String read(String path) {
		return readWith(path, StandardCharsets.ISO_8859_1);
	}

	private static String readWith(String path, java.nio.charset.Charset charset) {
		try (InputStream in = DragonLastStandShieldTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), charset);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
