package com.sharedfate.sync;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 이 모드가 <b>스스로</b> 피격 연출(피격음·붉은 번쩍임)을 내는 자리를 바이트코드째로 센다.
 *
 * <p>「완충」 몫의 피격음은 한 번에 고쳐지지 않았다. 126c004 는 바닐라 {@code hurtServer} 의
 * 연출 깃발만 보고 「이제 몫에서 연출이 안 난다」고 했는데, 소리는 이 저장소의
 * {@link SharedHurtFeedback} 이 팀원에게 보내는 {@code ClientboundDamageEventPacket} 에서 따로
 * 나고 있었다. <b>한 길만 막으면 반드시 샌다.</b> 그래서 길 목록 자체를 붙들어 둔다 — 새 자리가
 * 생기면 이 시험이 빨개지고, 고치는 사람은 그 자리도 몫을 건너뛰는지 보게 된다.
 *
 * <p>세는 것은 모드 본체({@code src/main}) 클래스 전부다.
 *
 * <ul>
 *   <li>{@code ClientboundDamageEventPacket} 을 만드는 자리 — 받은 클라이언트가
 *       {@code handleDamageEvent} 로 피격음·번쩍임·기울기를 낸다.
 *       {@link SharedHurtFeedback} 하나여야 한다.</li>
 *   <li>{@code broadcastDamageEvent}·{@code handleDamageEvent}·{@code playHurtSound}·
 *       {@code playSecondaryHurtSound} 를 직접 부르는 자리 — 없어야 한다.</li>
 *   <li>{@code SoundEvents} 의 피격음({@code *HURT*})을 꺼내는 자리 — 없어야 한다.</li>
 * </ul>
 *
 * <p>바닐라 쪽 길(연출 깃발)은 {@code HurtSoundTargetTest} 가, 클라이언트가 체력 감소만 보고
 * 스스로 기울이는 {@code LocalPlayer.hurtTo} 는 소리를 내지 않는다는 것은 26.3 clientonly jar 을
 * 읽어 확인했다(그 메서드에는 소리 호출이 없다).
 */
class HurtFeedbackPathsTest {

	private static final String DAMAGE_EVENT_PACKET =
			"net/minecraft/network/protocol/game/ClientboundDamageEventPacket";
	private static final String SOUND_EVENTS = "net/minecraft/sounds/SoundEvents";
	private static final List<String> FEEDBACK_CALLS = List.of(
			"broadcastDamageEvent", "handleDamageEvent", "playHurtSound", "playSecondaryHurtSound");

	@Test
	void 팀원에게_피격_연출_꾸러미를_보내는_자리는_SharedHurtFeedback_하나다() throws IOException {
		Found found = scan();

		assertEquals(new TreeSet<>(List.of("com/sharedfate/sync/SharedHurtFeedback")),
				found.damageEventPackets,
				"ClientboundDamageEventPacket 을 만드는 자리가 늘었다. 그 자리도 "
						+ "「완충」 몫(SpreadDamageManager.isDeliveringSlice)을 건너뛰는지 볼 것");
	}

	@Test
	void 피격음이나_피격_연출을_직접_부르는_자리가_없다() throws IOException {
		Found found = scan();

		assertEquals(new TreeSet<>(), found.feedbackCalls,
				"피격 연출을 직접 부르는 자리가 생겼다. 「완충」 몫에서도 도는지 볼 것");
		assertEquals(new TreeSet<>(), found.hurtSounds,
				"피격음을 직접 꺼내 쓰는 자리가 생겼다. 「완충」 몫에서도 도는지 볼 것");
	}

	/**
	 * {@link SharedHurtFeedback#onDamage} 는 꾸러미를 만들기 <b>전에</b> 몫인지 묻고 판정을
	 * 지나야 한다. 순서가 뒤집히면 판정이 있어도 소용이 없다.
	 */
	@Test
	void 꾸러미를_만들기_전에_완충_몫인지_묻는다() throws IOException {
		List<String> calls = new ArrayList<>();
		new ClassReader(bytesOf("com/sharedfate/sync/SharedHurtFeedback"))
				.accept(new ClassVisitor(Opcodes.ASM9) {
					@Override
					public MethodVisitor visitMethod(int access, String name, String descriptor,
							String signature, String[] exceptions) {
						if (!name.equals("onDamage")) {
							return null;
						}
						return new MethodVisitor(Opcodes.ASM9) {
							@Override
							public void visitMethodInsn(int opcode, String owner, String callName,
									String callDescriptor, boolean isInterface) {
								calls.add(owner + "." + callName);
							}
						};
					}
				}, ClassReader.SKIP_FRAMES);

		int deferred = calls.indexOf("com/sharedfate/sync/SpreadDamageManager.deferredHit");
		int slice = calls.indexOf("com/sharedfate/sync/SpreadDamageManager.isDeliveringSlice");
		int decide = calls.indexOf("com/sharedfate/sync/SharedHurtFeedback.shouldEcho");
		int packet = calls.indexOf(DAMAGE_EVENT_PACKET + ".<init>");
		assertNotEquals(-1, packet, "onDamage 가 더 이상 꾸러미를 만들지 않는다: " + calls);
		assertTrue(deferred >= 0 && deferred < packet, "미룬 양을 꾸러미보다 먼저 봐야 한다: " + calls);
		assertTrue(slice >= 0 && slice < packet, "몫인지 꾸러미보다 먼저 물어야 한다: " + calls);
		assertTrue(decide >= 0 && decide < packet, "판정을 꾸러미보다 먼저 지나야 한다: " + calls);
	}

	// ------------------------------------------------------------------ 도우미

	private static final class Found {
		final TreeSet<String> damageEventPackets = new TreeSet<>();
		final TreeSet<String> feedbackCalls = new TreeSet<>();
		final TreeSet<String> hurtSounds = new TreeSet<>();
	}

	private static Found scan() throws IOException {
		Found found = new Found();
		int[] classes = {0};
		forEachModClass((name, bytes) -> {
			classes[0]++;
			new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
				@Override
				public MethodVisitor visitMethod(int access, String methodName, String descriptor,
						String signature, String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String callName,
								String callDescriptor, boolean isInterface) {
							if (owner.equals(DAMAGE_EVENT_PACKET) && callName.equals("<init>")) {
								found.damageEventPackets.add(name);
							}
							if (FEEDBACK_CALLS.contains(callName)) {
								found.feedbackCalls.add(name + "." + methodName + " → " + callName);
							}
						}

						@Override
						public void visitFieldInsn(int opcode, String owner, String fieldName,
								String fieldDescriptor) {
							if (owner.equals(SOUND_EVENTS) && fieldName.contains("HURT")) {
								found.hurtSounds.add(name + "." + methodName + " → " + fieldName);
							}
						}
					};
				}
			}, ClassReader.SKIP_FRAMES);
		});
		// 엉뚱한 곳을 훑어 「하나도 없음」으로 통과하는 일이 없게 한다.
		assertTrue(classes[0] > 100, "모드 클래스를 제대로 찾지 못했다: " + classes[0] + "개");
		return found;
	}

	private interface ClassConsumer {
		void accept(String internalName, byte[] bytes) throws IOException;
	}

	/**
	 * 모드 본체 클래스가 놓인 뿌리를 {@code SharedHurtFeedback.class} 의 자리로 찾아 그 아래
	 * {@code com/sharedfate} 를 전부 훑는다. 폴더(빌드 출력)와 jar 둘 다 받는다. 시험
	 * 클래스로더가 돌려주는 자원은 믹스인이 얹히기 전의 원본이다.
	 */
	private static void forEachModClass(ClassConsumer consumer) throws IOException {
		String anchor = "com/sharedfate/sync/SharedHurtFeedback.class";
		URL url = HurtFeedbackPathsTest.class.getClassLoader().getResource(anchor);
		assertTrue(url != null, anchor + " 를 찾지 못했다");
		String text = url.toString();
		if (text.startsWith("jar:")) {
			URI jar = URI.create(text.substring(0, text.indexOf("!/") + 2));
			try (FileSystem fs = FileSystems.newFileSystem(jar, Map.of())) {
				walk(fs.getPath("/com/sharedfate"), fs.getPath("/"), consumer);
			}
			return;
		}
		Path file = Path.of(URI.create(text));
		Path root = file;
		for (int i = 0; i < anchor.split("/").length; i++) {
			root = root.getParent();
		}
		walk(root.resolve("com/sharedfate"), root, consumer);
	}

	private static void walk(Path start, Path root, ClassConsumer consumer) throws IOException {
		List<Path> files;
		try (Stream<Path> stream = Files.walk(start)) {
			files = stream.filter(path -> path.toString().endsWith(".class")).toList();
		}
		for (Path path : files) {
			String relative = root.relativize(path).toString().replace('\\', '/');
			String internal = relative.substring(0, relative.length() - ".class".length());
			consumer.accept(internal, Files.readAllBytes(path));
		}
	}

	private static byte[] bytesOf(String internalName) throws IOException {
		try (InputStream in = HurtFeedbackPathsTest.class.getClassLoader()
				.getResourceAsStream(internalName + ".class")) {
			assertTrue(in != null, internalName + " 의 클래스 파일을 찾지 못했다");
			return in.readAllBytes();
		}
	}
}
