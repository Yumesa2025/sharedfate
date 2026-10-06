package com.sharedfate.sync;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「완충」이 처음 맞을 때 한 번만 해야 하는 일(난이도 배율·방패·피격 알림)이 <b>어느 자리에
 * 붙었는가</b>를 바이트코드로 못박는다. 양을 세는 시험은 {@link SpreadDamageFirstHitTest} 다.
 *
 * <p>세 버그 모두 「판정이 틀렸다」가 아니라 <b>판정이 붙은 자리가 틀렸다</b>였다. 미루는 처리가
 * {@code hurtServer} HEAD 에 붙어 방패보다 먼저 피해를 0 으로 만들었고, 몫은 {@code Player} 의
 * 난이도 갈래와 방패를 아무 표시 없이 다시 지났고, 알림은 체력만 보고 떴다. 자리는 컴파일이
 * 붙들어 주지 않으니(refmap 이 없다) 여기서 붙든다.
 *
 * <p>믹스인 클래스는 시험에서 반사로 못 읽는다(「Mixin ... cannot be referenced directly」) —
 * 클래스 파일을 ASM 으로 읽는다. 바닐라 클래스도 자원으로 읽으므로 믹스인이 얹히기 전 원본이다.
 */
class SpreadDamageFirstHitTargetTest {

	private static final String MIXIN = "com/sharedfate/mixin/LivingEntityPerkDamageMixin";
	private static final String MANAGER = "com/sharedfate/sync/SpreadDamageManager";
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String PLAYER = "net/minecraft/world/entity/player/Player";
	private static final String HURT_SERVER_DESC =
			"(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;F)Z";
	private static final String MODIFY_VARIABLE =
			"Lorg/spongepowered/asm/mixin/injection/ModifyVariable;";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";

	// ================================================================== 바닐라 쪽 전제

	/**
	 * 난이도 배율은 {@code Player.hurtServer} 에서 {@code LivingEntity} 본문으로 내려가기
	 * <b>전에</b> 걸린다. 그래서 완충이 미루는 양에는 이미 배율이 곱해져 있고, 몫이
	 * {@code victim.hurtServer} 로 다시 들어오면 배율을 한 번 더 맞는다. 이 전제가 바뀌면
	 * {@code sliceArrival} 이 할 일도 바뀐다.
	 *
	 * <p>{@link SpreadDamageFirstHitTest#바닐라_난이도} 가 옮겨 적은 상수(어려움 3/2, 쉬움 /2+1 과
	 * min, 평화 0)도 함께 붙든다.
	 */
	@Test
	void 바닐라_난이도_배율은_LivingEntity_본문보다_먼저_걸린다() throws IOException {
		MethodNode hurt = method(PLAYER, "hurtServer", HURT_SERVER_DESC);
		List<String> seen = flatten(hurt);

		int scales = seen.indexOf("INVOKE net/minecraft/world/damagesource/DamageSource.scalesWithDifficulty");
		int peaceful = seen.indexOf("GETSTATIC net/minecraft/world/Difficulty.PEACEFUL");
		int easy = seen.indexOf("GETSTATIC net/minecraft/world/Difficulty.EASY");
		int hard = seen.indexOf("GETSTATIC net/minecraft/world/Difficulty.HARD");
		int min = seen.indexOf("INVOKE java/lang/Math.min");
		int threeHalves = seen.indexOf("LDC 3.0");
		int down = seen.indexOf("INVOKE net/minecraft/world/entity/Avatar.hurtServer");
		assertTrue(scales >= 0 && peaceful > scales && easy > peaceful && hard > easy,
				"Player.hurtServer 의 난이도 갈래 모양이 바뀌었다: " + seen);
		assertTrue(min > easy && min < hard, "쉬움의 min(x/2+1, x) 가 사라졌다: " + seen);
		assertTrue(threeHalves > hard, "어려움의 3/2 가 사라졌다: " + seen);
		assertTrue(down > threeHalves,
				"난이도 배율이 LivingEntity 본문 뒤로 옮겨 갔다. 그러면 미룬 양에 배율이 없으므로 "
						+ "sliceArrival 이 배율을 되돌리면 안 된다: " + seen);
	}

	/**
	 * {@code LivingEntity.hurtServer} 안의 순서. 방패({@code applyItemBlocking}) → 막은 양을 빼서
	 * 3번에 되쓰기 → 무적시간 판정({@code damageCooldownTime}) → 막는 소리({@code onBlocked}).
	 *
	 * <p>완충은 그 되쓰기 바로 뒤에 붙는다. 그래야 방패가 원래 피해를 바닐라처럼 막고, 무적시간
	 * 판정은 미뤄진 0 을 본다.
	 */
	@Test
	void 바닐라는_방패가_막은_양을_빼서_3번에_되쓴_뒤에_무적시간을_본다() throws IOException {
		MethodNode hurt = method(LIVING, "hurtServer", HURT_SERVER_DESC);
		AbstractInsnNode blocking = firstCall(hurt, LIVING, "applyItemBlocking");
		assertNotNull(blocking, "hurtServer 가 applyItemBlocking 을 더 이상 부르지 않는다");

		VarInsnNode stored = null;
		for (AbstractInsnNode insn = blocking.getNext(); insn != null; insn = insn.getNext()) {
			if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.FSTORE && var.var == 3) {
				stored = var;
				break;
			}
		}
		assertNotNull(stored, "방패 뒤에 피해량(3번)을 되쓰는 자리가 없다");
		VarInsnNode result = (VarInsnNode) next(blocking);
		assertEquals(Opcodes.FSTORE, result.getOpcode(), "막은 양을 지역변수에 담지 않는다");
		AbstractInsnNode sub = previous(stored);
		assertEquals(Opcodes.FSUB, sub.getOpcode(), "방패 뒤 첫 3번 저장이 「피해량 - 막은 양」이 아니다");
		VarInsnNode subtrahend = (VarInsnNode) previous(sub);
		assertEquals(result.var, subtrahend.var, "빼는 값이 applyItemBlocking 의 결과가 아니다");

		int at = hurt.instructions.indexOf(stored);
		int cooldown = indexOfField(hurt, "damageCooldownTime");
		int onBlocked = hurt.instructions.indexOf(firstCall(hurt,
				"net/minecraft/world/item/component/BlocksAttacks", "onBlocked"));
		assertTrue(cooldown > at, "무적시간 판정이 방패 앞으로 왔다");
		assertTrue(onBlocked > at, "막는 소리가 방패 판정 앞으로 왔다");
	}

	// ================================================================== 우리 쪽 자리

	/**
	 * <b>방패 — 처음 맞을 때.</b> 미루기({@code intercept})는 HEAD 가 아니라 방패 뒤 첫 3번 저장에
	 * 붙어야 한다. 예전처럼 HEAD 에 있으면 피해가 0 으로 넘어가 방패가 막을 양이 없다.
	 */
	@Test
	void 미루기는_HEAD_가_아니라_방패_판정_뒤에_붙는다() throws IOException {
		List<Handler> callers = handlersCalling(MANAGER, "intercept");
		assertEquals(1, callers.size(), "intercept 를 부르는 처리기는 하나여야 한다: " + callers);
		Handler handler = callers.getFirst();

		assertEquals(MODIFY_VARIABLE, handler.annotation, handler.toString());
		assertEquals("[hurtServer]", handler.values.get("method"), handler.toString());
		assertEquals("STORE", handler.values.get("at.value"),
				"미루기가 HEAD 에 붙어 있다. 그러면 방패가 막을 양이 0 이 된다: " + handler);
		assertEquals("0", handler.values.get("at.ordinal"), handler.toString());
		assertEquals("3", handler.values.get("index"), "피해량은 3번이다: " + handler);
		assertEquals("INVOKE", handler.values.get("slice.from.value"), handler.toString());
		assertEquals("L" + LIVING + ";applyItemBlocking(Lnet/minecraft/server/level/ServerLevel;"
						+ "Lnet/minecraft/world/damagesource/DamageSource;F)F",
				handler.values.get("slice.from.target"),
				"방패 판정부터 잘라 봐야 그 뒤 첫 3번 저장을 집는다: " + handler);
	}

	/**
	 * <b>난이도 배율.</b> HEAD 처리기는 몫이 들어올 때 {@code sliceArrival} 로 {@code Player} 가
	 * 다시 곱한 값을 되돌린다. 예전에는 받은 값을 그대로 돌려줘 배율이 몫마다 다시 곱해졌다.
	 */
	@Test
	void HEAD_처리기는_몫의_난이도_배율을_되돌리고_미루지는_않는다() throws IOException {
		Handler head = null;
		for (Handler handler : handlers()) {
			if (MODIFY_VARIABLE.equals(handler.annotation)
					&& "HEAD".equals(handler.values.get("at.value"))) {
				head = handler;
			}
		}
		assertNotNull(head, "hurtServer HEAD 의 @ModifyVariable 이 없다");
		assertTrue(head.calls.contains(MANAGER + ".sliceArrival"),
				"몫이 Player 의 난이도 배율을 또 맞고 그대로 들어간다: " + head.calls);
		assertFalse(head.calls.contains(MANAGER + ".intercept"),
				"HEAD 에서 미루면 방패가 막을 양이 없다: " + head.calls);
	}

	/**
	 * <b>방패 — 나뉜 몫.</b> 「이미 나눠 피해받을 때 방패 올려도 막으면 안 돼」.
	 * {@code applyItemBlocking} HEAD 에서 {@code ignoresShield} 를 물어 막은 양 0 으로 끝낸다.
	 */
	@Test
	void 몫은_applyItemBlocking_HEAD_에서_방패를_건너뛴다() throws IOException {
		List<Handler> callers = handlersCalling(MANAGER, "ignoresShield");
		assertEquals(1, callers.size(), "나뉜 몫이 방패에 막힌다 — 건너뛰는 처리기가 없다: " + callers);
		Handler handler = callers.getFirst();
		assertEquals(INJECT, handler.annotation, handler.toString());
		assertEquals("[applyItemBlocking]", handler.values.get("method"), handler.toString());
		assertEquals("HEAD", handler.values.get("at.value"), handler.toString());
		assertEquals("true", handler.values.get("cancellable"), handler.toString());
		assertEquals("(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/"
						+ "DamageSource;FLorg/spongepowered/asm/mixin/injection/callback/"
						+ "CallbackInfoReturnable;)V", handler.descriptor,
				"applyItemBlocking(ServerLevel, DamageSource, float) 의 인자와 맞아야 한다");
	}

	/**
	 * 몫을 넣는 자리({@code deliver})가 받는 사람과 몫을 함께 넘긴다. 사람 없이 넘기면
	 * {@code sliceArrival}·{@code ignoresShield} 가 아무에게도 안 듣는다.
	 */
	@Test
	void 몫을_넣는_자리는_받는_사람과_몫을_함께_넘긴다() throws IOException {
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : method(MANAGER, "deliver", null).instructions) {
			if (insn instanceof MethodInsnNode call) {
				calls.add(call.owner + "." + call.name + call.desc);
			}
		}
		assertTrue(calls.contains(MANAGER + ".asSlice(Lnet/minecraft/world/entity/Entity;FLjava/lang/Runnable;)V"),
				"deliver 가 받는 사람·몫 없이 표시만 켠다: " + calls);
		assertTrue(calls.contains(MANAGER + ".noteSliceLoss(Ljava/util/UUID;F)V"),
				"deliver 가 몫이 깎은 양을 피격 알림에 알리지 않는다: " + calls);
	}

	/**
	 * <b>피격 알림.</b> {@code StatMirror} 는 알림을 보내기 전에 몫이 깎은 양과 미룬 양을 꺼내
	 * {@code shouldAlert}(속은 {@code SharedHurtFeedback.shouldEcho})를 지나야 한다. 예전에는
	 * 체력이 줄었는지만 보고 보냈다.
	 */
	@Test
	void 피격_알림은_보내기_전에_완충_기록을_보고_같은_판정을_지난다() throws IOException {
		List<String> calls = new ArrayList<>();
		for (AbstractInsnNode insn : method("com/sharedfate/sync/StatMirror", "collectDeltas", null)
				.instructions) {
			if (insn instanceof MethodInsnNode call) {
				calls.add(call.owner + "." + call.name);
			}
		}
		int slice = calls.indexOf(MANAGER + ".takeSliceLoss");
		int deferred = calls.indexOf(MANAGER + ".takeDeferredAlert");
		int decide = calls.indexOf("com/sharedfate/sync/StatMirror.shouldAlert");
		int send = calls.indexOf("com/sharedfate/net/TeamBroadcaster.broadcastDamageAlert");
		assertTrue(send >= 0, "collectDeltas 가 더 이상 알림을 보내지 않는다: " + calls);
		assertTrue(slice >= 0 && slice < send, "몫이 깎은 양을 알림보다 먼저 꺼내야 한다: " + calls);
		assertTrue(deferred >= 0 && deferred < send, "미룬 양을 알림보다 먼저 꺼내야 한다: " + calls);
		assertTrue(decide > slice && decide > deferred && decide < send,
				"판정을 알림보다 먼저 지나야 한다: " + calls);

		List<String> decideCalls = new ArrayList<>();
		for (AbstractInsnNode insn : method("com/sharedfate/sync/StatMirror", "shouldAlert", null)
				.instructions) {
			if (insn instanceof MethodInsnNode call) {
				decideCalls.add(call.owner + "." + call.name);
			}
		}
		assertTrue(decideCalls.contains("com/sharedfate/sync/SharedHurtFeedback.shouldEcho"),
				"피격 알림이 팀원 피격음과 다른 판정을 쓴다 — 두 벌로 갈라졌다: " + decideCalls);

		boolean forgets = false;
		for (AbstractInsnNode insn : method("com/sharedfate/sync/StatMirror", "tick", null)
				.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(MANAGER)
					&& call.name.equals("forgetAlertLedger")) {
				forgets = true;
			}
		}
		assertTrue(forgets, "StatMirror.tick 이 꺼내 가지 못한 완충 기록을 버리지 않는다");
	}

	/**
	 * <b>HEAD 의 버리기 검사.</b> 몫을 받는 사람이면 낙하 방패·공유 상태이상 중복·광역 중복을
	 * 건너뛴다. 예전에는 몫이 이 셋을 다시 지나 버려졌고, 떼어 낸 몫은 그대로 사라졌다(2026-10-06
	 * Orca 검토 F-verified 의 V8). 묻는 자리는 판이 멈춘 검사(증강 선택·회차 시작 전·시련 화면) 뒤,
	 * 세 검사 앞이다. 「호위」({@code blocksMobDamage})는 몫에도 돌아야 하므로 그대로 있어야 한다.
	 */
	@Test
	void HEAD_버리기_검사는_몫을_받는_사람에게_처음_맞을_때의_셋을_건너뛴다() throws IOException {
		List<Handler> callers = handlersCalling("com/sharedfate/sync/TrialFreeze", "blocksDamage");
		assertEquals(1, callers.size(), "시련 화면 무적을 보는 HEAD 처리기는 하나여야 한다: " + callers);
		Handler head = callers.getFirst();
		assertEquals(INJECT, head.annotation, head.toString());
		assertEquals("HEAD", head.values.get("at.value"), head.toString());

		List<String> calls = head.calls;
		int frozen = calls.indexOf("com/sharedfate/sync/TrialFreeze.blocksDamage");
		int slice = calls.indexOf(MANAGER + ".receivingSlice");
		int fall = calls.indexOf("com/sharedfate/perk/PerkDamage.blocksFallDamage");
		int effect = calls.indexOf("com/sharedfate/sync/SharedEffectDamage.isDuplicateEffectDamage");
		int area = calls.indexOf("com/sharedfate/sync/SharedAreaDamage.isDuplicateAreaDamage");
		int ward = calls.indexOf("com/sharedfate/perk/PerkDamage.blocksMobDamage");
		assertTrue(slice >= 0, "HEAD 가 몫인지 묻지 않는다 — 몫이 낙하 방패·광역 중복에 버려진다: " + calls);
		assertTrue(frozen < slice, "판이 멈춘 검사는 몫에도 그대로 돌아야 한다: " + calls);
		assertTrue(fall > slice && effect > slice && area > slice,
				"처음 맞을 때의 검사가 몫인지 묻기 전에 돈다: " + calls);
		assertTrue(ward > area, "「호위」는 몫에도 맨 끝에서 돌아야 한다(알고 둔 동작): " + calls);
	}

	/**
	 * <b>{@code AFTER_DAMAGE} 소비자 전부가 같은 「맞은 양」 판정을 지난다.</b> Fabric 의
	 * {@code damageTaken} 은 완충이 미룬 첫 피해에서 0, 몫에서 몫 크기라, 그대로 믿는 소비자는
	 * 「처음엔 안 돌고 몫마다 돈다」. 피격음·피격 알림만 고쳐지고 {@code on_team_hurt}·
	 * {@code pass_on_hurt} 가 남아 있었다(2026-10-06 Orca 검토 F-verified 의 V7). 문서 6장 「한쪽만
	 * 막으면 반드시 샌다」 — 그래서 {@code SharedFateMod} 가 등록하는 소비자 목록 자체를 읽어 붙든다.
	 * 새 소비자가 생기면 여기가 빨개진다.
	 */
	@Test
	void AFTER_DAMAGE_소비자는_모두_완충의_맞은_양_판정을_지난다() throws IOException {
		List<String> consumers = afterDamageConsumers();
		assertTrue(consumers.contains("com/sharedfate/perk/PerkTriggers.onDamage")
						&& consumers.contains("com/sharedfate/perk/PerkHolderManager.onDamage")
						&& consumers.contains("com/sharedfate/sync/SharedHurtFeedback.onDamage"),
				"AFTER_DAMAGE 등록을 제대로 읽지 못했다: " + consumers);

		List<String> missing = new ArrayList<>();
		for (String consumer : consumers) {
			if (EXEMPT_AFTER_DAMAGE.contains(consumer)) {
				continue;
			}
			int dot = consumer.lastIndexOf('.');
			if (!reaches(consumer.substring(0, dot), consumer.substring(dot + 1), 3)) {
				missing.add(consumer);
			}
		}
		assertEquals(List.of(), missing,
				"이 소비자는 완충 몫마다 돌고 처음 맞은 순간엔 안 돈다 — SpreadDamageManager.hurtTaken 을 지날 것");
	}

	/**
	 * 판정을 안 지나도 되는 소비자. 이유를 함께 적는다.
	 *
	 * <p>{@code PerkLifesteal} — <b>때린 쪽</b>의 증강이다. 맞는 쪽이 완충을 가진 플레이어인 것은
	 * 다른 팀을 때리는 PvP 뿐이다. 그때도 같은 어긋남(처음 0, 몫마다 흡혈)이 있지만 W1 범위 밖이라
	 * 남겨 두었다(build/review/fix-W1-perks.md).
	 */
	private static final List<String> EXEMPT_AFTER_DAMAGE = List.of(
			"com/sharedfate/perk/PerkLifesteal.onDamage");

	/** {@code SharedFateMod} 가 {@code AFTER_DAMAGE} 에 등록하는 메서드 참조들. */
	private static List<String> afterDamageConsumers() throws IOException {
		List<String> found = new ArrayList<>();
		ClassNode node = new ClassNode();
		new ClassReader(bytesOf("com/sharedfate/SharedFateMod")).accept(node, ClassReader.SKIP_FRAMES);
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof InvokeDynamicInsnNode indy
						&& indy.desc.endsWith("ServerLivingEntityEvents$AfterDamage;")
						&& indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle target) {
					found.add(target.getOwner() + "." + target.getName());
				}
			}
		}
		return found;
	}

	/** 이 메서드가 (같은 클래스 안의 도우미를 몇 단계 거쳐서라도) {@code hurtTaken} 을 부르는가. */
	private static boolean reaches(String owner, String name, int depth) throws IOException {
		if (depth < 0) {
			return false;
		}
		ClassNode node = new ClassNode();
		new ClassReader(bytesOf(owner)).accept(node, ClassReader.SKIP_FRAMES);
		for (MethodNode method : node.methods) {
			if (!method.name.equals(name)) {
				continue;
			}
			for (AbstractInsnNode insn : method.instructions) {
				if (!(insn instanceof MethodInsnNode call)) {
					continue;
				}
				if (call.owner.equals(MANAGER) && call.name.equals("hurtTaken")) {
					return true;
				}
				if (call.owner.equals(owner) && !call.name.equals(name)
						&& reaches(owner, call.name, depth - 1)) {
					return true;
				}
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ 도우미

	/** 믹스인 처리기 하나. 붙은 애노테이션과 그 값들, 본문이 부르는 것. */
	private record Handler(String name, String descriptor, String annotation,
			Map<String, String> values, List<String> calls) {
	}

	private static List<Handler> handlersCalling(String owner, String name) throws IOException {
		List<Handler> found = new ArrayList<>();
		for (Handler handler : handlers()) {
			if (handler.calls.contains(owner + "." + name)) {
				found.add(handler);
			}
		}
		return found;
	}

	private static List<Handler> handlers() throws IOException {
		List<Handler> handlers = new ArrayList<>();
		new ClassReader(bytesOf(MIXIN)).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				Map<String, String> values = new LinkedHashMap<>();
				List<String> calls = new ArrayList<>();
				String[] annotation = {null};
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
						if (desc.startsWith("Lorg/spongepowered/asm/mixin/injection/")) {
							annotation[0] = desc;
							return new Flattener("", values);
						}
						return null;
					}

					@Override
					public void visitMethodInsn(int opcode, String owner, String callName,
							String callDescriptor, boolean isInterface) {
						calls.add(owner + "." + callName);
					}

					@Override
					public void visitEnd() {
						if (annotation[0] != null) {
							handlers.add(new Handler(name, descriptor, annotation[0], values, calls));
						}
					}
				};
			}
		}, ClassReader.SKIP_FRAMES);
		assertTrue(handlers.size() >= 4, "믹스인 처리기를 제대로 읽지 못했다: " + handlers);
		return handlers;
	}

	/** 애노테이션 값을 「at.value=STORE」 같은 평평한 표로 편다. */
	private static final class Flattener extends AnnotationVisitor {
		private final String prefix;
		private final Map<String, String> into;

		Flattener(String prefix, Map<String, String> into) {
			super(Opcodes.ASM9);
			this.prefix = prefix;
			this.into = into;
		}

		@Override
		public void visit(String name, Object value) {
			into.put(prefix + name, String.valueOf(value));
		}

		@Override
		public void visitEnum(String name, String descriptor, String value) {
			into.put(prefix + name, value);
		}

		@Override
		public AnnotationVisitor visitAnnotation(String name, String descriptor) {
			return new Flattener(prefix + name + ".", into);
		}

		@Override
		public AnnotationVisitor visitArray(String name) {
			List<String> items = new ArrayList<>();
			String key = prefix + name;
			return new AnnotationVisitor(Opcodes.ASM9) {
				@Override
				public void visit(String ignored, Object value) {
					items.add(String.valueOf(value));
				}

				@Override
				public AnnotationVisitor visitAnnotation(String ignored, String descriptor) {
					// 배열 안의 애노테이션(@At 여러 개)은 첫 것만 편다.
					return new Flattener(key + ".", into);
				}

				@Override
				public void visitEnd() {
					if (!items.isEmpty()) {
						into.put(key, items.toString());
					}
				}
			};
		}
	}

	private static MethodNode method(String owner, String name, String descriptor)
			throws IOException {
		ClassNode node = new ClassNode();
		new ClassReader(bytesOf(owner)).accept(node, ClassReader.SKIP_FRAMES);
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && (descriptor == null || method.desc.equals(descriptor))) {
				return method;
			}
		}
		throw new AssertionError(owner + "." + name + descriptor + " 가 없다");
	}

	/** 명령을 「INVOKE 소유자.이름」·「GETSTATIC 소유자.이름」·「LDC 값」으로 펴서 차례대로. */
	private static List<String> flatten(MethodNode method) {
		List<String> seen = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call) {
				seen.add("INVOKE " + call.owner + "." + call.name);
			} else if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC) {
				seen.add("GETSTATIC " + field.owner + "." + field.name);
			} else if (insn instanceof LdcInsnNode ldc) {
				seen.add("LDC " + ldc.cst);
			}
		}
		return seen;
	}

	private static AbstractInsnNode firstCall(MethodNode method, String owner, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(owner)
					&& call.name.equals(name)) {
				return insn;
			}
		}
		return null;
	}

	private static int indexOfField(MethodNode method, String name) {
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof FieldInsnNode field && field.name.equals(name)) {
				return method.instructions.indexOf(insn);
			}
		}
		return -1;
	}

	/** 줄 번호·라벨을 건너뛴 다음 명령. */
	private static AbstractInsnNode next(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && next.getOpcode() < 0) {
			next = next.getNext();
		}
		return next;
	}

	/** 줄 번호·라벨을 건너뛴 앞 명령. */
	private static AbstractInsnNode previous(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) {
			previous = previous.getPrevious();
		}
		return previous;
	}

	private static byte[] bytesOf(String internalName) throws IOException {
		try (InputStream in = SpreadDamageFirstHitTargetTest.class.getClassLoader()
				.getResourceAsStream(internalName + ".class")) {
			assertTrue(in != null, internalName + " 의 클래스 파일을 찾지 못했다");
			return in.readAllBytes();
		}
	}
}
