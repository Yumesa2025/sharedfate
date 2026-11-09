package com.sharedfate.sync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sharedfate.TestBootstrap;
import com.sharedfate.perk.PerkDamage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.zombie.Zombie;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 최후의 저항의 고정 피해(2026-11-07) — 데이터 정의 · 바닐라 태그 · 피격 쿨타임을 지나가는 방식. */
class DragonTrueDamageTest {
	private static final String ID = "sharedfate:dragon_true_damage";
	private static final float EPSILON = 1.0E-6F;

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	private static JsonObject json(String path) throws IOException {
		try (InputStream in = DragonTrueDamageTest.class.getResourceAsStream(path)) {
			assertNotNull(in, path + " 가 없다");
			return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
		}
	}

	@Test
	void 피해_종류는_난이도_곱이_없다() throws IOException {
		JsonObject type = json("/data/sharedfate/damage_type/dragon_true_damage.json");
		assertEquals("never", type.get("scaling").getAsString(), "난이도에 따라 커지면 고정이 아니다");
		assertEquals("dragonBreath", type.get("message_id").getAsString(),
				"언어 파일이 없으니 바닐라 문구를 빌린다 — 새 키는 클라이언트에 그대로 보인다");
		assertEquals(DragonTrueDamage.TYPE.identifier().toString(), ID);
	}

	@Test
	void 방어구_마법부여_저항_방패를_지나간다() throws IOException {
		for (String tag : new String[] {"bypasses_armor", "bypasses_enchantments", "bypasses_effects",
				"bypasses_resistance", "bypasses_shield"}) {
			JsonObject json = json("/data/minecraft/tags/damage_type/" + tag + ".json");
			assertFalse(json.has("replace") && json.get("replace").getAsBoolean(),
					tag + " 를 갈아 치우면 바닐라 피해 종류가 태그에서 빠진다");
			assertTrue(json.getAsJsonArray("values").toString().contains(ID), tag + " 에 없다");
		}
	}

	/**
	 * <b>{@code bypasses_cooldown} 태그에는 없어야 한다</b>(2026-11-10). 그 태그의 피해는 바닐라가 「새로 맞음」으로
	 * 받아 {@code lastHurt} 를 작은 고정 피해로 덮어써, 번개 직후 막혀야 할 몸통 · 브레스가 다 들어왔다.
	 */
	/** 사람이 정한 것(2026-11-10) — 「맞아도 밀침 있게, 고정 피해에도」. 0.45.9 는 밀침을 없앴다. */
	@Test
	void 밀침_없음_태그에는_없다() throws IOException {
		JsonObject json = json("/data/minecraft/tags/damage_type/no_knockback.json");
		assertFalse(json.getAsJsonArray("values").toString().contains(ID), "고정 피해도 맞으면 밀려야 한다");
	}

	@Test
	void 피격_쿨타임_태그에는_없다() throws IOException {
		JsonObject json = json("/data/minecraft/tags/damage_type/bypasses_cooldown.json");
		assertFalse(json.has("replace") && json.get("replace").getAsBoolean(),
				"갈아 치우면 바닐라 · 다른 데이터팩의 항목이 빠진다");
		assertFalse(json.getAsJsonArray("values").toString().contains(ID),
				"고정 피해가 bypasses_cooldown 이면 바닐라가 쿨타임 기준(lastHurt)을 고정 피해로 덮어쓴다");
	}

	/**
	 * 고정 피해를 넣는 동안만 쿨타임이 0 이고, 넣은 뒤에는 {@code lastHurt} · {@code damageCooldownTime} 이 넣기 전
	 * 그대로다. 바닐라 「새로 맞음」 갈래가 두 값을 덮어쓰는 것을 몸통에서 흉내 낸다.
	 */
	@Test
	void 고정_피해를_맞아도_쿨타임과_기준이_그대로다() throws Exception {
		LivingEntity target = empty(Zombie.class);
		SpreadSliceAccess access = (SpreadSliceAccess) (Object) target;
		// 상시 번개 24.5 를 맞은 지 3틱.
		target.damageCooldownTime = 17;
		access.sharedfate$setLastHurt(24.5F);

		boolean[] seen = new boolean[1];
		boolean hurt = DragonTrueDamage.bypassingCooldown(target, () -> {
			assertEquals(0, target.damageCooldownTime, "넣는 동안 쿨타임이 0 이 아니면 「직전보다 약한 한 대」로 버려진다");
			assertTrue(DragonTrueDamage.applying(), "넣는 동안 「완충」이 고정 피해임을 알 수 없다");
			// 바닐라 「새로 맞음」 갈래 — lastHurt = amount, damageCooldownTime = 20.
			access.sharedfate$setLastHurt(2.0F);
			target.damageCooldownTime = 20;
			seen[0] = true;
			return true;
		});

		assertTrue(seen[0]);
		assertTrue(hurt, "hurtServer 가 돌려준 값을 그대로 돌려줘야 한다");
		assertEquals(17, target.damageCooldownTime, "고정 피해가 쿨타임을 새로 열었다");
		assertEquals(24.5F, access.sharedfate$lastHurt(), EPSILON, "고정 피해가 쿨타임 기준을 낮췄다");
		assertFalse(DragonTrueDamage.applying(), "다 넣었는데 표시가 켜져 있다 — 다른 피해원까지 「완충」 Guard 를 뚫는다");

		// 그래서 다음 틱 드래곤 몸통 10 은 바닐라대로 막힌다.
		assertEquals(0.0F, PerkDamage.effectiveAmount(10.0F, access.sharedfate$lastHurt(),
				target.damageCooldownTime - 1, false), EPSILON, "번개 직후 몸통이 쿨타임을 뚫는다");
	}

	@Test
	void 넣다가_터져도_되돌린다() throws Exception {
		LivingEntity target = empty(Zombie.class);
		SpreadSliceAccess access = (SpreadSliceAccess) (Object) target;
		target.damageCooldownTime = 12;
		access.sharedfate$setLastHurt(7.0F);

		assertThrows(IllegalStateException.class, () -> DragonTrueDamage.bypassingCooldown(target, () -> {
			access.sharedfate$setLastHurt(0.5F);
			target.damageCooldownTime = 20;
			throw new IllegalStateException("시험");
		}));

		assertEquals(12, target.damageCooldownTime);
		assertEquals(7.0F, access.sharedfate$lastHurt(), EPSILON);
		assertFalse(DragonTrueDamage.applying());
	}

	/** 「완충」의 흉내 쿨타임도 같은 규칙 — 전부 받아들이되 직전 피해량 · 남은 무적시간은 그대로다. */
	@Test
	void 완충의_흉내_쿨타임도_기준을_바꾸지_않는다() {
		SpreadDamageManager.Gate gate = SpreadDamageManager.trueDamageGate(0.5F, 24.5F, 17);
		assertEquals(0.5F, gate.accepted(), EPSILON, "쿨타임 안이어도 고정 피해는 전부 들어가야 한다");
		assertEquals(24.5F, gate.lastAmount(), EPSILON, "고정 피해가 Guard 의 기준을 낮췄다");
		assertEquals(17, gate.invulnerableTicks(), "고정 피해가 Guard 의 쿨타임을 새로 열었다");

		// bypasses_cooldown 갈래였다면 기준이 0.5 로 내려가 다음 몸통 10 이 9.5 나 들어갔다 — 고치기 전의 증상.
		SpreadDamageManager.Gate old = SpreadDamageManager.gate(0.5F, 24.5F, 17, true);
		assertEquals(0.5F, old.lastAmount(), EPSILON);
		assertEquals(0.0F, SpreadDamageManager.gate(10.0F, gate.lastAmount(), gate.invulnerableTicks() - 1, false)
				.accepted(), EPSILON, "고정 피해 뒤 몸통이 Guard 를 뚫는다");
	}

	/** {@code capture} 가 흉내 판정 앞에서 「고정 피해를 넣는 중인가」를 묻는가. */
	@Test
	void 완충이_고정_피해를_묻는다() throws IOException {
		List<String> calls = invokes(SpreadDamageManager.class, "capture");
		assertTrue(calls.contains("DragonTrueDamage.applying"), "완충이 고정 피해를 「새로 맞음」으로 흉내 낸다");
		assertTrue(calls.contains("SpreadDamageManager.trueDamageGate"), "완충이 고정 피해용 판정을 쓰지 않는다");
	}

	/**
	 * 최후의 저항이 고정 피해를 넣는 네 자리(불 결계 · 흡입 터짐 · 십자 균열 · 번개 위 고정)가 모두
	 * {@link DragonTrueDamage#hurt} 를 지나고, 피해원을 꺼내 {@code hurtServer} 를 직접 부르는 자리가 없다.
	 */
	@Test
	void 고정_피해는_모두_도우미를_지난다() throws IOException {
		List<String> patterns = invokes(DragonLastStandPatterns.class, null);
		assertEquals(4, patterns.stream().filter("DragonTrueDamage.hurt"::equals).count(),
				"불 결계 · 흡입 터짐 · 십자 균열 · 번개 위 고정 — 네 자리여야 한다");
		assertFalse(patterns.contains("DragonTrueDamage.source"),
				"피해원을 꺼내 hurtServer 를 직접 부르면 쿨타임 기준이 고정 피해로 덮인다");
		assertFalse(invokes(DragonLastStand.class, null).contains("DragonTrueDamage.source"),
				"피해원을 꺼내 hurtServer 를 직접 부르면 쿨타임 기준이 고정 피해로 덮인다");
	}

	@Test
	void 증강_배율을_건너뛴다() throws IOException {
		String path = "/com/sharedfate/mixin/LivingEntityPerkDamageMixin.class";
		try (InputStream in = DragonTrueDamageTest.class.getResourceAsStream(path)) {
			assertNotNull(in);
			String bytes = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1);
			assertTrue(bytes.contains("com/sharedfate/sync/DragonTrueDamage"),
					"받는 피해 감소 증강이 고정 피해에도 곱해진다");
		}
	}

	// ------------------------------------------------------------------ 도우미

	/** 클래스(또는 그 안의 메서드 하나)가 부르는 이 패키지의 정적 메서드를 「클래스.메서드」로 순서대로 뽑는다. */
	private static List<String> invokes(Class<?> type, String onlyMethod) throws IOException {
		List<String> calls = new ArrayList<>();
		String path = "/" + type.getName().replace('.', '/') + ".class";
		try (InputStream in = type.getResourceAsStream(path)) {
			assertNotNull(in, path + " 를 찾지 못했다");
			new ClassReader(in.readAllBytes()).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor,
						String signature, String[] exceptions) {
					if (onlyMethod != null && !name.equals(onlyMethod)) {
						return null;
					}
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String callName,
								String callDescriptor, boolean isInterface) {
							if (owner.startsWith("com/sharedfate/sync/")) {
								calls.add(owner.substring("com/sharedfate/sync/".length()) + "." + callName);
							}
						}
					};
				}
			}, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		}
		return calls;
	}

	@SuppressWarnings("unchecked")
	private static <T> T empty(Class<T> type) throws Exception {
		var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		field.setAccessible(true);
		return (T) ((sun.misc.Unsafe) field.get(null)).allocateInstance(type);
	}
}
