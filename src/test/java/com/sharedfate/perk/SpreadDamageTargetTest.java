package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LivingEntityPerkDamageMixin} 이 무는 바닐라 자리를 반사로 못박는다.
 *
 * <p>이 저장소는 refmap 을 만들지 않아 {@code @Inject}·{@code @ModifyVariable} 의 대상 서술자가
 * 틀려도 <b>빌드가 그냥 통과</b>하고, 그 자리를 처음 지나는 순간에야 터진다. 피해와 회복은
 * 서버가 뜨고 사람이 맞아 봐야 도는 자리라 사고를 늦게 알아차린다.
 *
 * <p>「완충」({@code spread_damage})의 대가(나뉘어 들어오는 동안 자연 회복 금지)는 2026-10-16 부터
 * {@code heal} 이 아니라 {@code FoodDataNaturalRegenMixin} 이 무는 {@code FoodData.tick} 의
 * {@code isHurt} 호출에 걸려 있다. {@code no_natural_regen} 과 같은 자리라 그쪽 시험
 * ({@code NoNaturalRegenEffectTest})이 함께 붙든다.
 */
class SpreadDamageTargetTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	/**
	 * 피해 진입점. {@code @Inject}·{@code @ModifyVariable} 의 {@code method} 문자열에 적은 것과
	 * 글자 하나까지 같아야 한다.
	 *
	 * <p>{@code @ModifyVariable} 이 {@code index = 3} 으로 세 번째 인자(피해량)를 집는다. 앞에
	 * 인자가 하나라도 끼어들면 엉뚱한 값을 갈아 끼우므로 <b>인자 차례</b>까지 함께 붙든다.
	 */
	@Test
	void 피해_대상_메서드가_그_서술자_그대로_있다() {
		Method target = assertDoesNotThrow(
				() -> LivingEntity.class.getDeclaredMethod("hurtServer",
						ServerLevel.class, DamageSource.class, float.class),
				"hurtServer(ServerLevel, DamageSource, float) 가 사라졌거나 서명이 바뀌었다");

		assertEquals(boolean.class, target.getReturnType());
		assertFalse(Modifier.isStatic(target.getModifiers()), "정적이면 인자 번호가 하나씩 밀린다");
		assertEquals(ServerLevel.class, target.getParameterTypes()[0]);
		assertEquals(DamageSource.class, target.getParameterTypes()[1]);
		assertEquals(float.class, target.getParameterTypes()[2],
				"index = 3 이 집는 것이 피해량이어야 한다");
	}

	/**
	 * 회복 진입점.
	 *
	 * <p>26.2 에서 자연 회복·재생 상태이상·금사과가 모두 이 한 곳을 지난다. 「완충」은 더 이상
	 * 여기를 막지 않지만(자연 회복만 막도록 좁혀졌다), 공유 회복의 중복을 막는
	 * {@code LivingEntitySharedHealMixin} 이 여전히 이 자리를 문다. {@code void} 라 취소해도
	 * 호출자에게 아무 신호가 가지 않는다는 점도 함께 붙든다 — 반환형이 생기면 취소가 다른 뜻이 된다.
	 */
	@Test
	void 회복_대상_메서드가_그_서술자_그대로_있다() {
		Method target = assertDoesNotThrow(
				() -> LivingEntity.class.getDeclaredMethod("heal", float.class),
				"heal(float) 가 사라졌거나 서명이 바뀌었다");

		assertEquals(void.class, target.getReturnType(), "void 라야 취소가 조용히 먹힌다");
		assertTrue(Modifier.isPublic(target.getModifiers()));
		assertFalse(Modifier.isStatic(target.getModifiers()));
	}

	/**
	 * 무적 시간 칸.
	 *
	 * <p>{@code SpreadDamageManager} 가 미뤄 둔 몫을 넣기 직전에 이 값을 0 으로 두었다가 되돌린다.
	 * 되돌리지 않으면 나뉘어 들어오는 동안 몹에게 거의 맞지 않는 증강이 된다.
	 * {@code public int} 가 아니게 되면 컴파일이 깨지므로 여기서 미리 붙든다.
	 *
	 * <p><b>이 칸은 더 이상 피해 쿨타임이 아니다.</b> 26.3 에서 피해 쿨타임이
	 * {@code LivingEntity.damageCooldownTime} 으로 갈라져 나갔고, 그쪽을 붙드는 것이 아래
	 * {@link #피격_쿨타임_칸이_그대로_있다} 다. 두 시험이 나란히 있는 것은 <b>둘을 같은 것으로
	 * 착각해 생긴 회귀</b>가 실제로 있었기 때문이다.
	 */
	@Test
	void 무적_시간_칸이_그대로_있다() {
		Field field = assertDoesNotThrow(
				() -> Entity.class.getDeclaredField("invulnerableTime"),
				"Entity.invulnerableTime 이 사라졌거나 이름이 바뀌었다");

		assertEquals(int.class, field.getType());
		assertFalse(Modifier.isFinal(field.getModifiers()));

		// 26.3 에서 칸이 private 이 되고 접근자가 생겼다. 우리는 그 접근자로 읽고 쓴다.
		assertDoesNotThrow(() -> Entity.class.getMethod("getInvulnerableTime"),
				"무적 시간을 읽을 길이 없어졌다");
		assertDoesNotThrow(() -> Entity.class.getMethod("setInvulnerableTime", int.class),
				"무적 시간을 되돌릴 길이 없어졌다");
	}

	// -------------------------------------------------- 피격 쿨타임 칸 (26.3 에서 갈라져 나왔다)

	/**
	 * 피격 쿨타임 칸. {@code LivingEntityPerkDamageMixin} 이 {@code @Shadow} 로 끌어다 쓴다.
	 *
	 * <p>{@code @Shadow} 는 <b>이름·타입·접근 제어자가 대상과 어긋나면 발화 시점에 터진다.</b>
	 * refmap 이 없어 빌드는 그냥 통과하므로 여기서 미리 붙든다 — 특히 {@code public} 이 아니게
	 * 되면 {@code @Shadow} 쪽 선언도 함께 바꿔야 한다.
	 */
	@Test
	void 피격_쿨타임_칸이_그대로_있다() {
		Field field = assertDoesNotThrow(
				() -> LivingEntity.class.getDeclaredField("damageCooldownTime"),
				"LivingEntity.damageCooldownTime 이 사라졌거나 이름이 바뀌었다. "
						+ "hurtServer 가 무엇으로 쿨타임을 세는지 바이트코드로 다시 확인할 것");

		assertEquals(int.class, field.getType(), "@Shadow 의 타입이 이것과 같아야 한다");
		assertTrue(Modifier.isPublic(field.getModifiers()),
				"@Shadow 의 접근 제어자가 이것과 같아야 한다");
		assertFalse(Modifier.isStatic(field.getModifiers()));
		assertFalse(Modifier.isFinal(field.getModifiers()));
	}

	/**
	 * <b>피해 판정이 어느 칸을 보는가</b>를 클래스 파일째로 붙든다. 이번 회귀를 정확히 잡는
	 * 한 줄이다.
	 *
	 * <p>26.3 {@code LivingEntity} 의 상수 풀에는 {@code invulnerableTime} 이라는 이름이
	 * <b>한 번도 나오지 않는다.</b> 즉 {@code hurtServer} 가 그 칸을 볼 길 자체가 없다. 반대로
	 * {@code damageCooldownTime} 은 거기 있고, 바이트코드로 보면 {@code hurtServer} 가
	 * 185번째에서 읽고 248번째에서 20 으로 되채운다.
	 *
	 * <p>0.27.0-dev 로 26.3 에 올릴 때 {@code Entity.invulnerableTime} 이 {@code private} 이 되어
	 * 컴파일이 깨졌고, 접근자 {@code getInvulnerableTime()} 으로 바꾸는 것으로 끝냈다. 그런데
	 * <b>바닐라가 그 값의 쓰임 자체를 다른 칸으로 옮긴 것</b>은 보지 못했다. 컴파일은 통과했고
	 * 시험이 없어 아무도 모른 채 「호위」의 낭비 방지가 죽어 있었다.
	 *
	 * <p>{@code DebugOverlayCheckTest} 와 같은 방식이다 — 상수 풀에 이름이 아스키로 들어가므로
	 * 살아 있는 서버 없이 바이트를 훑어 확인한다.
	 */
	@Test
	void 피해_판정은_무적시간_칸을_보지_않는다() throws IOException {
		String livingEntity = classFileOf(LivingEntity.class, LivingEntity.class.getName());

		assertTrue(livingEntity.contains("damageCooldownTime"),
				"LivingEntity 가 피격 쿨타임 칸을 아예 모른다. hurtServer 판정이 다시 바뀌었다");
		assertFalse(livingEntity.contains("invulnerableTime"),
				"LivingEntity 가 다시 invulnerableTime 을 본다. 26.3 에서 갈라진 두 칸이 합쳐졌는지, "
						+ "아니면 다른 쓰임이 생겼는지 바이트코드로 확인하고 effectiveAmount 를 맞출 것");
	}

	/**
	 * 우리 쪽이 다시 무적시간 칸으로 돌아가지 않았는가.
	 *
	 * <p>{@code Entity.getInvulnerableTime()} 은 <b>지금도 멀쩡히 컴파일되는 호출</b>이라, 같은
	 * 실수가 「컴파일이 되니까 맞겠지」로 다시 들어올 수 있다. 그 길을 막는다.
	 *
	 * <p>믹스인 클래스를 {@code .class} 로 직접 참조하지 않고 이름으로 읽는다. 믹스인 클래스를
	 * 그대로 불러오면 믹스인 환경이 「믹스인은 직접 참조할 수 없다」로 막기 때문이다.
	 */
	@Test
	void 증강_피해_믹스인은_무적시간_칸을_묻지_않는다() throws IOException {
		String mixin = classFileOf(PerkDamage.class,
				"com.sharedfate.mixin.LivingEntityPerkDamageMixin");

		assertTrue(mixin.contains("damageCooldownTime"),
				"믹스인이 피격 쿨타임 칸을 @Shadow 로 끌어오지 않는다");
		assertFalse(mixin.contains("InvulnerableTime"),
				"믹스인이 다시 무적시간 접근자를 부른다. 그 칸은 얻어맞아도 올라가지 않으므로 "
						+ "「어차피 버려질 한 대」를 못 가려내고 「호위」가 헛되이 소모된다");
	}

	/**
	 * 클래스 파일의 바이트를 그대로 문자열로 읽는다.
	 *
	 * <p>상수 풀에 이름이 아스키로 들어가므로 {@code ISO_8859_1} 로 훑으면 찾을 수 있다.
	 *
	 * @param nearby   자원을 찾을 클래스로더를 빌려 올 클래스
	 * @param binary   읽고 싶은 클래스의 이름
	 */
	private static String classFileOf(Class<?> nearby, String binary) throws IOException {
		String path = "/" + binary.replace('.', '/') + ".class";
		try (InputStream in = nearby.getResourceAsStream(path)) {
			if (in == null) {
				throw new IOException("클래스 파일을 찾지 못했습니다: " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
		}
	}
}
