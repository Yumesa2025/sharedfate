package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.StrawBedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 엔드의 침대는 <b>터지지 않고 주저앉는다.</b>
 *
 * <p>사람 말: <b>「엔더에서 침대 터뜨렷을떄 터지지않게 하자 대신 침대가 아에 파괴되게 터지는딜
 * 없애버리게」</b>
 *
 * <h2>⚠ 이 파일에서 가장 중요한 시험</h2>
 *
 * <p>{@link #네더는_그대로_터진다} 다. 사람이 말한 것은 <b>「엔더에서」</b>인데 네더 침대도
 * <b>같은 {@code BedBlock.destroyOnUse} 한 줄</b>을 지난다. 차원 가리기를 빼먹으면 네더 침대가
 * 함께 죽고, 그것은 <b>엔드에서 시험하는 동안에는 절대 드러나지 않는다.</b>
 *
 * <p>둘째는 {@link #침대는_바닐라가_먼저_지운다} 다. 「터지지 않지만 침대는 사라진다」가 성립하는
 * 근거가 <b>오직 바닐라의 호출 순서</b>이기 때문이다 — 바닐라가 어느 판에서 폭발을 먼저 하고
 * 블록을 나중에 지우도록 바꾸면, 우리 믹스인은 그대로 붙은 채 <b>침대가 안 사라지는</b> 쪽으로
 * 뜻이 뒤집힌다. 로그에는 아무 흔적도 없다.
 *
 * <p>⚠ {@link #대상_클래스를_불러_믹스인이_붙는지_본다} 는 손수 실행기로는 <b>거짓 초록</b>이
 * 난다. 이 저장소의 시험은 {@code fabric-loader-junit} 으로 Knot 클래스로더를 지나므로
 * {@code BedBlock} 을 불러오는 순간 믹스인이 실제로 붙는다. 메인의 {@code gradlew test} 가
 * 「붙었는지」를 답하는 자리가 여기다.
 */
class EndBedCollapseTest {
	/** 믹스인은 <b>이름(문자열)으로만</b> 가리킨다. 클래스 리터럴은 Knot 가 거절한다. */
	private static final String MIXIN = "/com/sharedfate/mixin/BedBlockEndBlastMixin.class";

	private static final String HANDLER = "sharedfate$swallowEndBedExplosion";

	private static final String WRAP_OPERATION =
			"Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";

	/** 26.3 바이트코드에서 그대로 읽어 온 폭발 호출의 서술자. */
	private static final String EXPLODE_TARGET = "Lnet/minecraft/world/level/Level;explode("
			+ "Lnet/minecraft/world/entity/Entity;"
			+ "Lnet/minecraft/world/damagesource/DamageSource;"
			+ "Lnet/minecraft/world/level/ExplosionDamageCalculator;"
			+ "Lnet/minecraft/world/phys/Vec3;FZ"
			+ "Lnet/minecraft/world/level/Level$ExplosionInteraction;)V";

	@BeforeAll
	static void bootstrap() {
		TestBootstrap.ensureInitialized();
	}

	// ------------------------------------------------------------------ ① 엔드에서는 안 터진다

	/**
	 * <b>엔드에서는 바닐라 폭발을 한 번도 부르지 않는다.</b>
	 *
	 * <p>피해 0 · 블록 파괴 0 · 화염 0 이 전부 이 한 가지에 달려 있다 — 26.3 은 반지름
	 * {@code 5.0F} · {@code fire=true} · {@code ExplosionInteraction.BLOCK} 셋을
	 * <b>호출 하나</b>에 담으므로, 그 호출을 안 하면 셋이 함께 사라진다.
	 */
	@Test
	void 엔드에서는_폭발이_안_난다() {
		List<String> ran = new ArrayList<>();
		EndBedCollapse.route(Level.END, () -> ran.add("폭발"), () -> ran.add("주저앉음"));

		assertEquals(List.of("주저앉음"), ran,
				"엔드에서 바닐라 폭발이 돌았다 — 「침대로 드래곤을 때리는」 전술이 그대로 남는다");
		assertTrue(EndBedCollapse.swallows(Level.END), "엔드는 삼키는 차원이어야 한다");
	}

	// ------------------------------------------------------------------ ② 침대는 사라진다

	/**
	 * 침대가 사라지는 것은 <b>바닐라가 폭발보다 먼저</b> 지우기 때문이다.
	 *
	 * <p>26.3 {@code BedBlock.destroyOnUse} 는 {@code removeBlock} 둘 → {@code explode} 하나
	 * 순서다. 우리는 마지막 한 줄만 감싸므로 앞의 둘은 손대지 않는다. ⚠ 이 순서가 뒤집히면
	 * 「폭발은 없고 침대는 남는」 쪽으로 뜻이 조용히 바뀐다.
	 */
	@Test
	void 침대는_바닐라가_먼저_지운다() {
		List<String> calls = callsIn(BedBlock.class, "destroyOnUse");
		int explode = calls.indexOf("Level.explode");
		assertTrue(explode >= 0, "BedBlock.destroyOnUse 가 폭발을 안 만든다 — 감쌀 것이 없다: " + calls);
		assertEquals(calls.size() - 1, explode,
				"폭발이 마지막 호출이 아니다 — 뒤에 무엇이 더 있으면 삼킬 때 그것까지 잃는지 "
						+ "다시 읽어야 한다: " + calls);

		List<Integer> removals = new ArrayList<>();
		for (int index = 0; index < calls.size(); index++) {
			if (calls.get(index).equals("Level.removeBlock")) {
				removals.add(index);
			}
		}
		assertEquals(2, removals.size(),
				"removeBlock 이 둘이어야 한다 — 침대는 머리와 발 두 칸이다: " + calls);
		for (int at : removals) {
			assertTrue(at < explode,
					"블록 지우기가 폭발보다 뒤에 있다 — 폭발을 삼키면 침대가 안 사라진다. "
							+ "사람이 요청한 것은 「대신 침대가 아에 파괴되게」다: " + calls);
		}
	}

	// ------------------------------------------------------------------ ③ 네더는 그대로다

	/**
	 * <b>네더는 한 글자도 달라지지 않는다.</b>
	 *
	 * <p>사람이 말한 것은 「엔더에서」다. 그런데 {@code BedRule.destroyOnUse()} 는 네더에서도
	 * 참이라 <b>같은 호출 자리</b>를 지난다. 그 깃발로 가렸다면 여기서 빨개진다.
	 *
	 * <p>오버월드도 함께 본다 — 침대가 안 터지는 차원이라 평소에는 이 자리에 오지 않지만,
	 * 데이터팩이 {@code BED_RULE} 을 바꾼 서버에서는 올 수 있다.
	 */
	@Test
	void 네더는_그대로_터진다() {
		List<String> nether = new ArrayList<>();
		EndBedCollapse.route(Level.NETHER, () -> nether.add("폭발"), () -> nether.add("주저앉음"));
		assertEquals(List.of("폭발"), nether,
				"네더 침대가 안 터진다 — 사람이 말한 것은 「엔더에서」뿐이다");

		List<String> overworld = new ArrayList<>();
		EndBedCollapse.route(Level.OVERWORLD, () -> overworld.add("폭발"),
				() -> overworld.add("주저앉음"));
		assertEquals(List.of("폭발"), overworld, "오버월드까지 삼켰다 — 엔드만 가려야 한다");

		assertFalse(EndBedCollapse.swallows(Level.NETHER), "네더는 삼키는 차원이 아니다");
		assertFalse(EndBedCollapse.swallows(Level.OVERWORLD), "오버월드는 삼키는 차원이 아니다");
		assertFalse(EndBedCollapse.swallows(null),
				"모르는 차원은 바닐라로 흘려야 한다 — 삼키면 모드 차원에서 조용히 샌다");
	}

	// ------------------------------------------------------------------ ④ 대상 못박기

	/**
	 * refmap 이 없으므로 {@code @Mixin} 이나 {@code @At} 대상이 틀려도 <b>빌드는 통과한다.</b>
	 * 여기서 먼저 터지게 한다.
	 */
	@Test
	void 믹스인_대상_서술자가_26_3에_그대로_있다() {
		assertDoesNotThrow(() -> BedBlock.class.getDeclaredMethod("destroyOnUse",
						BlockState.class, Level.class, BlockPos.class, Player.class),
				"BedBlock.destroyOnUse 의 서술자가 바뀌었다 — 믹스인이 붙을 자리가 없다");
		assertDoesNotThrow(() -> Level.class.getDeclaredMethod("explode",
						Entity.class, DamageSource.class, ExplosionDamageCalculator.class,
						Vec3.class, float.class, boolean.class, Level.ExplosionInteraction.class),
				"@At 이 가리키는 Level.explode 의 서술자가 바뀌면 삼키기가 통째로 죽는다");

		Map<String, Object> wrap = wrapOperationOf(HANDLER);
		assertEquals(Boolean.TRUE, wrap.get("present"),
				"@WrapOperation 이 안 달렸다 — " + HANDLER + " 는 아무도 부르지 않는 메서드다");
		assertEquals(Integer.valueOf(1), wrap.get("method.count"), "method 가 하나여야 한다: " + wrap);
		assertEquals("destroyOnUse", wrap.get("method[0]"),
				"⚠ AbstractBedBlock.destroyOnUse 는 abstract 이고 BedBlock·StrawBedBlock 이 "
						+ "각각 재정의한다. 이름이 바뀌면 조용히 죽는다: " + wrap);
		assertEquals(Integer.valueOf(1), wrap.get("at.count"), "@At 이 하나여야 한다: " + wrap);
		assertEquals("INVOKE", wrap.get("at[0].value"), "@At 이 호출 자리를 가리켜야 한다: " + wrap);
		assertEquals(EXPLODE_TARGET, wrap.get("at[0].target"),
				"@At 의 target 이 26.3 바이트코드와 다르다: " + wrap);

		String descriptor = String.valueOf(wrap.get("descriptor"));
		assertTrue(descriptor.endsWith(
						"Lcom/llamalad7/mixinextras/injector/wrapoperation/Operation;)V"),
				"마지막 인자가 Operation 이어야 바닐라를 흘릴 수 있다: " + descriptor);
	}

	/**
	 * {@code BedBlock.destroyOnUse} 안의 폭발 호출은 <b>하나뿐</b>이다.
	 *
	 * <p>하나뿐이라 {@code ordinal} 을 적지 않았다. 둘로 늘어나면 하나만 감싸고 다른 하나가
	 * 조용히 샌다 — 「한쪽만 막으면 반드시 샌다」가 바로 이 모양이다.
	 */
	@Test
	void 폭발하는_길은_destroyOnUse_하나다() {
		List<String> destroy = callsIn(BedBlock.class, "destroyOnUse");
		assertEquals(1, destroy.stream().filter("Level.explode"::equals).count(),
				"폭발 호출이 하나가 아니다 — ordinal 없는 @WrapOperation 이 전부를 감싸는지 "
						+ "다시 읽어야 한다: " + destroy);

		assertTrue(callsIn(AbstractBedBlock.class, "useWithoutItem").contains(
						"AbstractBedBlock.destroyOnUse"),
				"useWithoutItem 이 destroyOnUse 를 안 부른다 — 침대를 쓰는 길이 늘었다. "
						+ "26.3 에서는 그 한 군데가 유일한 호출 자리였다");
	}

	/**
	 * <b>짚 침대는 원래 안 터진다.</b> 손댈 것이 없다는 것을 못박는다.
	 *
	 * <p>26.3 의 {@code StrawBedBlock.destroyOnUse} 는 {@code destroyBed} 하나만 부르고,
	 * 그 안은 {@code STRAW_BED_BREAK_LEAVE} 를 울리고 공기로 바꾸는 두 줄이다. 우리가
	 * 되먹임으로 빌려 쓴 소리가 바로 그것이라 뜻이 어긋나지 않는다.
	 */
	@Test
	void 짚_침대는_원래_안_터진다() {
		List<String> calls = callsIn(StrawBedBlock.class, "destroyOnUse");
		assertFalse(calls.contains("Level.explode"),
				"짚 침대가 터진다 — 사람이 말한 것은 침대뿐이지만 이쪽도 다시 읽어야 한다: " + calls);
		assertNotNull(SoundEvents.STRAW_BED_BREAK_LEAVE,
				"되먹임 소리가 사라졌다 — sounds.json 의 block/straw_bed/break_leave 를 다시 확인해야 한다");
	}

	/**
	 * <b>리스폰 앵커는 전혀 다른 길이다.</b> 여기 믹스인이 닿지 않는다.
	 *
	 * <p>사람이 말한 것은 침대뿐이라 손대지 않았다. 앵커는 {@code destroyOnUse} 를 아예 갖지
	 * 않고 자기만의 {@code private explode(BlockState, ServerLevel, BlockPos)} 를 가진다.
	 * 「침대를 막았으니 앵커도 막혔겠지」로 읽히지 않게 여기 적어 둔다.
	 */
	@Test
	void 리스폰_앵커는_다른_길이다() {
		assertDoesNotThrow(() -> RespawnAnchorBlock.class.getDeclaredMethod("explode",
						BlockState.class, ServerLevel.class, BlockPos.class),
				"앵커의 자기 폭발이 사라졌다 — 침대와 같은 길로 합쳐졌는지 다시 읽어야 한다");
		assertTrue(Arrays.stream(RespawnAnchorBlock.class.getDeclaredMethods())
						.map(Method::getName)
						.noneMatch("destroyOnUse"::equals),
				"앵커가 destroyOnUse 를 갖게 됐다 — 우리 믹스인이 앵커까지 조용히 바꾸는지 봐야 한다");
	}

	// ------------------------------------------------------------------ ⑤ 붙었는지 본다

	/**
	 * <b>{@code BedBlock} 을 실제로 불러 믹스인이 붙는지 본다.</b>
	 *
	 * <p>{@code injectors.defaultRequire = 1} 이라 대상을 못 찾으면 <b>붙는 순간 터진다.</b>
	 * 이 시험이 {@code BedBlock} 을 불러오므로 그 터짐이 메인의 {@code gradlew test} 에서
	 * 드러난다. ⚠ Knot 를 지나지 않는 손수 실행기는 여기서 <b>거짓 초록</b>을 준다.
	 *
	 * <p>붙으면 핸들러가 대상 클래스로 <b>병합</b>된다. 이름으로 그것을 확인한다 —
	 * {@code ExpandedQuickMoveFallbackTest} 와 같은 도구다.
	 */
	@Test
	void 대상_클래스를_불러_믹스인이_붙는지_본다() {
		Set<String> merged = Arrays.stream(BedBlock.class.getDeclaredMethods())
				.map(Method::getName)
				.filter(name -> name.contains("sharedfate"))
				.collect(Collectors.toSet());

		assertTrue(merged.stream().anyMatch(name -> name.contains("swallowEndBedExplosion")),
				"BedBlockEndBlastMixin 이 BedBlock 에 병합되지 않았다 — mixins.json 에 줄이 "
						+ "없거나 대상이 틀렸다. 병합된 것들: " + merged);
	}

	/**
	 * 믹스인이 <b>등록되어</b> 있다.
	 *
	 * <p>{@code sharedfate.mixins.json} 에 이름을 안 넣으면 파일만 있고 아무 일도 하지 않는다 —
	 * 컴파일도 빌드도 조용하다. ⚠ 그 파일은 <b>메인이 독점</b>하므로 여기가 빨간 채로 넘어갈 수
	 * 있다. <b>빨간 것이 곧 「줄을 넣어 달라」는 말이다.</b>
	 */
	@Test
	void 믹스인이_등록되어_있다() throws IOException {
		try (InputStream in = EndBedCollapse.class.getResourceAsStream("/sharedfate.mixins.json")) {
			assertNotNull(in, "sharedfate.mixins.json 이 클래스패스에 없다");
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(json.contains("\"BedBlockEndBlastMixin\""),
					"BedBlockEndBlastMixin 이 mixins.json 에 없다 — 엔드 침대가 그대로 터진다");
		}
	}

	// ------------------------------------------------------------------ 도우미

	/**
	 * 믹스인 메서드에 달린 {@code @WrapOperation} 을 <b>클래스 파일에서</b> 읽어 평평한 이름으로
	 * 돌려준다.
	 *
	 * <p>자원으로 읽으므로 믹스인 클래스를 <b>불러오지 않는다.</b> ⚠ 등록된 믹스인은 반사로 못
	 * 읽는다({@code IllegalClassLoadError … cannot be referenced directly}).
	 */
	private static Map<String, Object> wrapOperationOf(String methodName) {
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
						if (!WRAP_OPERATION.equals(annotation)) {
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

	/** 애노테이션 값을 평평한 이름으로 모은다. 배열은 {@code at[0]}, 안쪽 애노테이션은 그 아래로. */
	private static AnnotationVisitor collector(Map<String, Object> into, String prefix) {
		return new AnnotationVisitor(Opcodes.ASM9) {
			@Override
			public void visit(String name, Object value) {
				into.put(prefix + (name == null ? "" : name), value);
			}

			@Override
			public AnnotationVisitor visitArray(String name) {
				String base = prefix + name;
				// 빈 배열이어도 개수를 적어 둔다. 안 적으면 「@At 이 없다」가 「못 읽었다」와
				// 구별되지 않는다.
				into.put(base + ".count", 0);
				int[] next = {0};
				return new AnnotationVisitor(Opcodes.ASM9) {
					@Override
					public void visit(String ignored, Object value) {
						into.put(base + "[" + next[0]++ + "]", value);
						into.put(base + ".count", next[0]);
					}

					@Override
					public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
						String at = base + "[" + next[0]++ + "]";
						into.put(base + ".count", next[0]);
						into.put(at + ".type", descriptor);
						return collector(into, at + ".");
					}
				};
			}
		};
	}

	/**
	 * 메서드 하나가 부르는 것들의 <b>차례</b>. {@code DragonLandingDiceTest} 와 같은 도구다.
	 *
	 * <p>자원으로 읽으므로 <b>바닐라 바이트코드</b>를 본다 — 우리 믹스인이 끼기 전의 순서다.
	 * 그것이 여기서 알고 싶은 것이다.
	 */
	private static List<String> callsIn(Class<?> owner, String methodName) {
		List<String> calls = new ArrayList<>();
		// 바닐라 자원은 그 클래스 자신의 로더에게 묻는다. 시험 클래스로 물으면 Knot 가
		// 마인크래프트 jar 를 그 이름 아래 안 걸어 둔 판에서 못 찾는다.
		byte[] vanilla = bytesOf(owner, "/" + owner.getName().replace('.', '/') + ".class");
		new ClassReader(vanilla).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				if (!name.equals(methodName)) {
					return null;
				}
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String callOwner, String callName,
							String callDescriptor, boolean isInterface) {
						calls.add(callOwner.substring(callOwner.lastIndexOf('/') + 1)
								+ "." + callName);
					}
				};
			}
		}, ClassReader.SKIP_FRAMES);
		assertFalse(calls.isEmpty(),
				owner.getSimpleName() + "." + methodName + " 을 읽지 못했다 — 이름이 바뀌었다");
		return calls;
	}

	/** 자원 한 개의 바이트. <b>클래스를 불러오지 않는다</b> — 믹스인을 읽는 유일한 길이다. */
	private static byte[] bytesOf(String path) {
		return bytesOf(EndBedCollapseTest.class, path);
	}

	/** 같은 것을 다른 클래스의 로더에게 묻는다. 바닐라 자원은 바닐라 클래스에게 묻는다. */
	private static byte[] bytesOf(Class<?> asker, String path) {
		try (InputStream in = asker.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return in.readAllBytes();
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
