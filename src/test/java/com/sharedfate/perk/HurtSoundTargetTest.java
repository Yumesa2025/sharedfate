package com.sharedfate.perk;

import com.sharedfate.TestBootstrap;
import com.sharedfate.sync.SpreadSliceAccess;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code LivingEntityHurtSoundMixin} 이 무는 바닐라 자리와, 「완충」이 되돌리는 피격 표시
 * 필드를 반사로 못박는다.
 *
 * <p>이 저장소는 refmap 을 만들지 않아 {@code @Inject} 의 대상이 틀려도 <b>빌드가 그냥
 * 통과</b>하고 그 자리를 처음 지나는 순간에야 터진다. 피격 소리는 사람이 실제로 맞아 봐야 도는
 * 자리라 사고를 늦게 알아차린다.
 *
 * <p>필드 둘({@code hurtTime}·{@code hurtDuration})은 Mixin 이 아니라
 * {@code SpreadDamageManager.deliver} 가 직접 저장했다 되돌리는 값이다. 접근 제한이 좁아지면
 * <b>컴파일이 깨지므로</b> 그쪽은 이 시험 없이도 알 수 있지만, 이름이 바뀌어 다른 필드를 잡게
 * 되는 경우까지 함께 붙들어 둔다.
 *
 * <p>뒤쪽 시험들은 <b>몫마다 피격 연출이 빠지는 진짜 근거</b>를 붙든다. 위 두 막기는 서버쪽만
 * 막아 클라이언트의 붉은 번쩍임·피격음을 못 막았고, 지금은 {@code deliver} 가 몫을 바닐라의
 * 「쿨타임 안 추가 피해」 갈래로 넣어 {@code broadcastDamageEvent} 자체를 건너뛴다.
 */
class HurtSoundTargetTest {

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	/**
	 * 피격 소리 진입점. {@code @Inject} 의 {@code method} 문자열에 적은 것과 같아야 한다.
	 *
	 * <p>인자가 하나뿐이라 이름만 맞으면 서술자도 맞지만, <b>정적이 되면</b> 주입 형태가
	 * 달라지므로 그것까지 본다.
	 */
	@Test
	void 피격_소리_대상_메서드가_그_서술자_그대로_있다() {
		Method target = assertDoesNotThrow(
				() -> LivingEntity.class.getDeclaredMethod("playHurtSound", DamageSource.class),
				"playHurtSound(DamageSource) 가 사라졌거나 서명이 바뀌었다");

		assertEquals(void.class, target.getReturnType(),
				"돌려주는 값이 생기면 cancel 만으로는 못 막는다");
		assertFalse(Modifier.isStatic(target.getModifiers()));
		assertEquals(1, target.getParameterCount());
		assertEquals(DamageSource.class, target.getParameterTypes()[0]);
	}

	/** 「완충」이 몫마다 되돌리는 피격 표시 필드 둘. */
	@Test
	void 피격_표시_필드가_그대로_있다() {
		for (String name : new String[] {"hurtTime", "hurtDuration"}) {
			Field field = assertDoesNotThrow(() -> LivingEntity.class.getDeclaredField(name),
					name + " 가 사라졌거나 이름이 바뀌었다");
			assertEquals(int.class, field.getType(), name);
			assertTrue(Modifier.isPublic(field.getModifiers()),
					name + " 이 public 이 아니면 되돌릴 수 없다");
			assertFalse(Modifier.isStatic(field.getModifiers()), name);
		}
	}

	// ------------------------------------------- 몫을 「쿨타임 안 추가 피해」 갈래로 넣는 길

	/**
	 * {@code LivingEntityPerkDamageMixin} 이 {@code @Shadow} 로 끌어와 {@link SpreadSliceAccess}
	 * 로 내보내는 칸. {@code @Shadow} 는 이름·타입·접근 제어자가 어긋나면 발화 시점에 터지고,
	 * <b>상위 클래스에 선언된 칸에는 붙지 않는다</b> — 그래서 선언 위치까지 본다.
	 */
	@Test
	void 직전_피해량_칸이_LivingEntity_에_그대로_있다() {
		Field field = assertDoesNotThrow(() -> LivingEntity.class.getDeclaredField("lastHurt"),
				"LivingEntity.lastHurt 가 사라졌거나 이름이 바뀌었다");

		assertEquals(float.class, field.getType(), "@Shadow 의 타입이 이것과 같아야 한다");
		assertTrue(Modifier.isProtected(field.getModifiers()),
				"@Shadow 의 접근 제어자가 이것과 같아야 한다");
		assertFalse(Modifier.isStatic(field.getModifiers()));
		assertFalse(Modifier.isFinal(field.getModifiers()), "되돌려 써야 하므로 final 이면 안 된다");
	}

	/**
	 * {@code SpreadDamageManager.deliver} 가 {@code LivingEntity} 를 {@link SpreadSliceAccess} 로
	 * 바꿔 쥔다. 믹스인이 그 인터페이스를 얹지 않으면 <b>몫을 넣는 순간</b>
	 * {@code ClassCastException} 이 난다 — 빌드는 그냥 통과한다.
	 *
	 * <p>믹스인 클래스는 직접 참조하면 믹스인 환경이 막으므로 클래스 파일을 이름으로 읽는다.
	 */
	@Test
	void 증강_피해_믹스인이_직전_피해량_통로를_얹는다() throws IOException {
		String mixin = asciiOf(classBytes(HurtSoundTargetTest.class,
				"com.sharedfate.mixin.LivingEntityPerkDamageMixin"));

		assertTrue(mixin.contains("com/sharedfate/sync/SpreadSliceAccess"),
				"LivingEntityPerkDamageMixin 이 SpreadSliceAccess 를 구현하지 않는다");
		assertTrue(mixin.contains("lastHurt"), "믹스인이 lastHurt 를 @Shadow 로 끌어오지 않는다");
	}

	/**
	 * 시험도 Knot 클래스로더를 지나므로 믹스인이 실제로 적용된 {@code LivingEntity} 를 본다.
	 * 여기서 거짓이면 실제 서버에서도 몫을 넣는 순간 {@code ClassCastException} 이다.
	 */
	@Test
	void 적용된_LivingEntity_가_직전_피해량_통로를_구현한다() {
		assertTrue(SpreadSliceAccess.class.isAssignableFrom(LivingEntity.class),
				"LivingEntity 에 SpreadSliceAccess 가 얹히지 않았다. 믹스인이 적용됐는지 볼 것");
	}

	/**
	 * <b>「완충」 몫에서 피격 연출이 빠지는 근거</b>를 바이트코드째로 붙든다.
	 *
	 * <p>{@code deliver} 는 쿨타임을 20, {@code lastHurt} 를 0 으로 두고 {@code hurtServer} 를
	 * 부른다. 그러면 바닐라가 「쿨타임 안에 더 센 한 대」 갈래를 타
	 * {@code actuallyHurt(amount - lastHurt)} 뒤에 <b>연출 깃발(지역변수)을 끈다.</b> 아래
	 * 네 호출이 전부 그 깃발 뒤에 숨어 있어야 몫마다 화면이 붉어지고 밀려나는 일이 없다.
	 *
	 * <ul>
	 *   <li>{@code ServerLevel.broadcastDamageEvent} — 클라이언트가 받아 붉은 번쩍임·화면
	 *       기울기·피격음을 낸다. <b>이것이 본체다.</b></li>
	 *   <li>{@code markHurt}·{@code dealDefaultKnockback} — 넉백</li>
	 *   <li>{@code playHurtSound} — 서버쪽 피격음</li>
	 * </ul>
	 *
	 * <p>판올림으로 이 모양이 바뀌면 시험이 빨개진다. 그때는 {@code hurtServer} 를
	 * {@code javap -p -c} 로 다시 읽고 {@code deliver} 를 맞출 것.
	 */
	@Test
	void 쿨타임_안_갈래는_피격_연출_깃발을_끈다() throws IOException {
		List<String> code = instructionsOf(LivingEntity.class, "hurtServer",
				"(Lnet/minecraft/server/level/ServerLevel;"
						+ "Lnet/minecraft/world/damagesource/DamageSource;F)Z");

		// 쿨타임 안 갈래의 actuallyHurt — 바로 앞에서 lastHurt 를 뺀다.
		int partial = -1;
		for (int i = 1; i < code.size(); i++) {
			if (code.get(i).equals("CALL actuallyHurt") && code.get(i - 1).equals(op(Opcodes.FSUB))) {
				partial = i;
				break;
			}
		}
		assertNotEquals(-1, partial,
				"hurtServer 에 「actuallyHurt(amount - lastHurt)」 갈래가 없다. 실제 명령: " + code);

		// 그 뒤 몇 줄 안에서 깃발을 끈다(iconst_0 → istore n).
		int flag = -1;
		for (int i = partial + 1; i < Math.min(code.size() - 1, partial + 8); i++) {
			if (code.get(i).equals(op(Opcodes.ICONST_0))
					&& code.get(i + 1).startsWith("VAR " + Opcodes.ISTORE + " ")) {
				flag = Integer.parseInt(code.get(i + 1).substring(("VAR " + Opcodes.ISTORE + " ").length()));
				break;
			}
		}
		assertNotEquals(-1, flag,
				"쿨타임 안 갈래가 더 이상 연출 깃발을 끄지 않는다. 몫마다 피격 연출이 다시 터진다");

		for (String call : new String[] {"broadcastDamageEvent", "markHurt",
				"dealDefaultKnockback", "playHurtSound"}) {
			int at = code.subList(partial, code.size()).indexOf("CALL " + call);
			assertNotEquals(-1, at, "hurtServer 가 쿨타임 판정 뒤에 " + call + " 을 부르지 않는다");
			at += partial;
			assertTrue(guardedBy(code, partial, at, flag),
					call + " 이 연출 깃발(지역변수 " + flag + ") 뒤에 있지 않다. "
							+ "「완충」 몫마다 이것이 다시 돈다");
		}
	}

	// ------------------------------------------------------------------ 도우미

	/** {@code from} 과 {@code at} 사이에서 가장 가까운 「iload flag → ifeq」 가 있는가. */
	private static boolean guardedBy(List<String> code, int from, int at, int flag) {
		for (int i = at - 1; i > from; i--) {
			if (code.get(i).equals("VAR " + Opcodes.ILOAD + " " + flag)
					&& code.get(i + 1).equals("JUMP " + Opcodes.IFEQ)) {
				return true;
			}
		}
		return false;
	}

	private static String op(int opcode) {
		return "OP " + opcode;
	}

	/**
	 * 메서드 하나의 명령을 순서대로 글자로 늘어놓는다. 꼬리표·줄 번호는 빼고, 판정에 쓰는 것만
	 * 알아볼 수 있게 적는다.
	 */
	private static List<String> instructionsOf(Class<?> owner, String methodName, String descriptor)
			throws IOException {
		List<String> code = new ArrayList<>();
		boolean[] seen = {false};
		new ClassReader(classBytes(owner, owner.getName())).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String signature,
					String[] exceptions) {
				if (!name.equals(methodName) || !desc.equals(descriptor)) {
					return null;
				}
				seen[0] = true;
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitInsn(int opcode) {
						code.add(op(opcode));
					}

					@Override
					public void visitVarInsn(int opcode, int var) {
						code.add("VAR " + opcode + " " + var);
					}

					@Override
					public void visitJumpInsn(int opcode, Label label) {
						code.add("JUMP " + opcode);
					}

					@Override
					public void visitMethodInsn(int opcode, String callOwner, String callName,
							String callDescriptor, boolean isInterface) {
						code.add("CALL " + callName);
					}

					@Override
					public void visitFieldInsn(int opcode, String fieldOwner, String fieldName,
							String fieldDescriptor) {
						code.add("FIELD " + opcode + " " + fieldName);
					}

					@Override
					public void visitIntInsn(int opcode, int operand) {
						code.add("INT " + opcode + " " + operand);
					}

					@Override
					public void visitLdcInsn(Object value) {
						code.add("LDC");
					}

					@Override
					public void visitTypeInsn(int opcode, String type) {
						code.add("TYPE " + opcode);
					}
				};
			}
		}, ClassReader.SKIP_FRAMES);
		assertTrue(seen[0], owner.getSimpleName() + "." + methodName + " 을 읽지 못했다");
		return code;
	}

	/**
	 * 클래스 파일의 바이트를 그대로 읽는다. 시험 클래스로더가 돌려주는 자원은 믹스인이 얹히기
	 * <b>전</b>의 원본이다.
	 */
	private static byte[] classBytes(Class<?> nearby, String binary) throws IOException {
		String path = "/" + binary.replace('.', '/') + ".class";
		try (InputStream in = nearby.getResourceAsStream(path)) {
			if (in == null) {
				throw new IOException("클래스 파일을 찾지 못했습니다: " + path);
			}
			return in.readAllBytes();
		}
	}

	/** 상수 풀의 이름은 아스키라 {@code ISO_8859_1} 로 훑으면 찾을 수 있다. */
	private static String asciiOf(byte[] bytes) {
		return new String(bytes, StandardCharsets.ISO_8859_1);
	}
}
