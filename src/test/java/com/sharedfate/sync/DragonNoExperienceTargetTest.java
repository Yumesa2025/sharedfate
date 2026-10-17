package com.sharedfate.sync;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * {@code EnderDragonNoExperienceMixin} 이 기대는 <b>바닐라 쪽 사실</b>과 믹스인 자신의 모양을 붙든다.
 *
 * <p>사람 말(2026-10-18): 드래곤 경험치 없음 — 항상. 게임 창을 띄우지 않고 확인하는 길이 이것이다 —
 * 26.3 클래스 파일에서 경험치가 나오는 호출이 <b>정확히 어디에 몇 개</b> 있는지 세고, 믹스인이 그
 * 자리를 <b>전부</b> 문다고 적었는지 본다. 믹스인 쪽은 {@code require = 2} 라 실제 적용에서 하나라도
 * 놓치면 클래스를 불러오는 순간 터진다(조용히 경험치가 나오는 길이 없다).
 *
 * <p>클래스를 <b>불러오지 않고</b> 자원으로 읽는다. 등록된 믹스인은 반사로 못 읽고
 * ({@code DragonLandingDiceTest} 의 도우미 설명), {@code EnderDragon} 은 불러오면 부트스트랩이 필요하다.
 */
class DragonNoExperienceTargetTest {

	private static final String DRAGON = "/net/minecraft/world/entity/boss/enderdragon/EnderDragon.class";
	private static final String MIXIN = "/com/sharedfate/mixin/EnderDragonNoExperienceMixin.class";
	private static final String ORB = "net/minecraft/world/entity/ExperienceOrb";
	private static final String AWARD_DESCRIPTOR =
			"(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;I)V";
	private static final String REDIRECT = "Lorg/spongepowered/asm/mixin/injection/Redirect;";

	/**
	 * 드래곤의 경험치 구슬은 {@code tickDeath} 의 {@code ExperienceOrb.award} <b>두 줄</b>에서만 나온다.
	 *
	 * <p>둘이 아니게 되면 26.3 이 바뀐 것이다 — 믹스인의 {@code require = 2} 가 게임에서 먼저
	 * 터지겠지만, 여기서 「몇 개가 어디로 갔는가」를 먼저 읽게 한다.
	 */
	@Test
	void 경험치는_tickDeath_의_award_두_줄에서만_나온다() {
		Map<String, Integer> awards = awardCallsByMethod();
		assertEquals(Map.of("tickDeath()V", 2), awards,
				"EnderDragon 의 ExperienceOrb.award 호출 자리가 바뀌었다: " + awards);
	}

	/**
	 * 다른 길({@code LivingEntity.dropExperience})이 0 인 까닭 — 드래곤이 {@code xpReward} 를 쓰지 않는다.
	 *
	 * <p>{@code Mob.getBaseExperienceReward} 는 {@code xpReward <= 0} 이면 0 을 돌려준다. 드래곤이 그
	 * 필드를 쓰기 시작하면 그 길로 구슬이 새므로 믹스인을 다시 봐야 한다.
	 */
	@Test
	void 드래곤은_xpReward_를_쓰지_않는다() {
		List<String> touches = new ArrayList<>();
		new ClassReader(bytesOf(DRAGON)).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitFieldInsn(int opcode, String owner, String field, String type) {
						if (field.equals("xpReward")) {
							touches.add(name + descriptor + " " + opcode);
						}
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		assertTrue(touches.isEmpty(), "드래곤이 xpReward 를 만진다 — dropExperience 길이 열렸다: "
				+ touches);
	}

	/** 믹스인이 {@code tickDeath} 의 그 호출을 <b>전부</b>({@code require = 2}) 문다고 적혀 있다. */
	@Test
	void 믹스인이_두_줄을_모두_문다() {
		Map<String, Object> redirect = redirectOf("sharedfate$noDragonExperience");
		assertEquals(Boolean.TRUE, redirect.get("present"), "@Redirect 가 없다");
		assertEquals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;I)V",
				redirect.get("descriptor"), "처리기의 인자가 award 와 같아야 한다");
		assertEquals("tickDeath()V", redirect.get("method[0]"));
		assertEquals("INVOKE", redirect.get("at[0].value"));
		assertEquals("L" + ORB + ";award" + AWARD_DESCRIPTOR, redirect.get("at[0].target"));
		assertFalse(redirect.containsKey("at[0].ordinal"),
				"서수를 적으면 두 줄 중 하나만 막힌다");
		assertEquals(2, redirect.get("require"), "둘 중 하나라도 못 찾으면 터져야 한다");
	}

	/** 등록이 안 되어 있으면 파일만 있고 아무 일도 하지 않는다 — 컴파일도 빌드도 조용하다. */
	@Test
	void 믹스인이_등록되어_있다() throws IOException {
		try (InputStream in = DragonNoExperienceTargetTest.class
				.getResourceAsStream("/sharedfate.mixins.json")) {
			assertNotNull(in, "sharedfate.mixins.json 이 클래스패스에 없다");
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(json.contains("\"EnderDragonNoExperienceMixin\""),
					"EnderDragonNoExperienceMixin 이 mixins.json 에 없다 — 드래곤 경험치가 그대로 나온다");
		}
	}

	// ------------------------------------------------------------------ 도우미

	/** 메서드마다 {@code ExperienceOrb.award(ServerLevel, Vec3, int)} 를 몇 번 부르는가. */
	private static Map<String, Integer> awardCallsByMethod() {
		Map<String, Integer> counts = new LinkedHashMap<>();
		new ClassReader(bytesOf(DRAGON)).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				String key = name + descriptor;
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String callName,
							String callDescriptor, boolean isInterface) {
						if (owner.equals(ORB) && callName.equals("award")
								&& callDescriptor.equals(AWARD_DESCRIPTOR)) {
							counts.merge(key, 1, Integer::sum);
						}
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return counts;
	}

	/** 믹스인 메서드의 {@code @Redirect} 를 평평한 이름으로 읽는다({@code DragonLandingDiceTest} 와 같은 꼴). */
	private static Map<String, Object> redirectOf(String methodName) {
		Map<String, Object> found = new LinkedHashMap<>();
		new ClassReader(bytesOf(MIXIN)).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				if (!name.equals(methodName)) {
					return null;
				}
				found.put("descriptor", descriptor);
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
						if (!REDIRECT.equals(annotation)) {
							return null;
						}
						found.put("present", Boolean.TRUE);
						return collector(found, "");
					}
				};
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		assertTrue(found.containsKey("descriptor"),
				methodName + " 을 믹스인 클래스 파일에서 못 찾았다 — 이름이 바뀌었다");
		return found;
	}

	/** 애노테이션 값을 평평한 이름으로 모은다. 배열은 {@code name[0]}, 안쪽 애노테이션은 {@code name.} 아래로. */
	private static AnnotationVisitor collector(Map<String, Object> into, String prefix) {
		return new AnnotationVisitor(Opcodes.ASM9) {
			@Override
			public void visit(String name, Object value) {
				into.put(prefix + name, value);
			}

			@Override
			public void visitEnum(String name, String descriptor, String value) {
				into.put(prefix + name, value);
			}

			@Override
			public AnnotationVisitor visitAnnotation(String name, String descriptor) {
				return collector(into, prefix + name + ".");
			}

			@Override
			public AnnotationVisitor visitArray(String name) {
				String base = prefix + name;
				int[] next = {0};
				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override
					public void visit(String ignored, Object value) {
						into.put(base + "[" + next[0]++ + "]", value);
					}

					@Override
					public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
						return collector(into, base + "[" + next[0]++ + "].");
					}
				};
			}
		};
	}

	private static byte[] bytesOf(String path) {
		try (InputStream in = DragonNoExperienceTargetTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return in.readAllBytes();
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
