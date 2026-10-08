package com.sharedfate.sync;

import com.sharedfate.TestBootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
 * ⚠⚠ 바닐라 <b>{@code EnderDragon.knockBack}</b> 을 최후의 저항에서 끊는 자리
 * ({@code EnderDragonContactDamageMixin}).
 *
 * <h2>사람이 본 것</h2>
 *
 * <p><b>「드래곤 밀치는 패턴떄 점프하면 하늘로 날라가버림」</b>(2026-10-04). 로그에 남은 것은
 * {@code Kairen_Y moved too quickly! 14.108, 6.485, 24.602} 다.
 *
 * <h2>왜 이것이 「미는 힘뿐이니 괜찮다」가 아닌가</h2>
 *
 * <p>26.3 {@code knockBack} 은 날개 상자({@code wing1}·{@code wing2} 의
 * {@code inflate(4,2,4).move(0,-2,0)}) 안의 모든 것에 {@code push(…, 0.2, …)} 를
 * <b>조건 없이 매 틱</b> 넣는다 — {@code isSitting()} 조건은 <b>5 피해에만</b> 붙어 있다.
 * 바닐라에서는 그 값이 {@code needsSync} 로 <b>주변 사람에게만</b> 나가 본인에게 안 가므로
 * 무해한데, 최후의 저항의 넉백은 {@code syncVelocity} 를 켜고 그쪽은
 * {@code getDeltaMovement()} <b>통째로</b>를 <b>본인에게</b> 내려보낸다.
 *
 * <p>쌓이는 세로의 고정점이 <b>5.88칸/틱</b>이고 수평의 한 틱 최댓값이 <b>12.65칸/틱</b>
 * (종착 바닥 15.2 · 공중 127.9)이다. <b>공유 체력이라 한 사람의 낙사가 팀 전멸이고 그것이
 * 회차 끝이자 월드 삭제</b>라, 이 파일이 묻는 것은 「값이 맞는가」가 아니라
 * <b>「일어날 수 있는가」</b>이고 답이 「없다」여야 한다.
 *
 * <h2>⚠ 이 파일에서 가장 중요한 시험</h2>
 *
 * <p>{@link #일반_전투의_날개_밀치기는_그대로_돈다} 다. 끊는 것은 <b>최후의 저항에서만</b>이고,
 * 일반 전투의 날개 밀치기는 <b>바닐라 동작</b>이라 끊으면 안 된다 — 거기서는 우리가 속도를
 * 본인에게 내려보내지 않으므로 무해하다. 그 경계가 무너지면 「끄면 완전한 바닐라
 * 엔더드래곤전」이 거짓이 되는데, 증상은 <b>「드래곤 날개에 안 밀리네」</b>로만 나와 아무도
 * 버그라고 적지 않는다.
 */
class EnderDragonKnockBackTest {

	@BeforeAll
	static void setUp() {
		TestBootstrap.ensureInitialized();
	}

	/** 정적 깃발이라 시험끼리 샌다. 앞뒤로 비운다 — {@code DragonLastStandTest} 와 같은 자리다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonLastStand.clearState();
	}

	// ------------------------------------------------------------------ ① 끊는 자리가 그대로 있다

	/**
	 * ⚠⚠ <b>{@code knockBack} 과 {@code hurt} 의 서술자가 글자 하나까지 같다.</b>
	 *
	 * <p>둘 다 {@code (Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V} 라
	 * <b>가르는 것이 이름뿐</b>이다. refmap 이 없어 이름을 헷갈려 적어도 빌드는 통과하고,
	 * {@code injectors.defaultRequire} 가 1 이라 <b>못 찾으면</b> 붙는 순간 터지지만
	 * <b>엉뚱한 것을 찾으면 조용하다.</b>
	 *
	 * <p>{@code private} 이라는 것도 함께 못박는다 — 「재정의되는 메서드에 믹스인을 걸면 조용히
	 * 죽는다」는 이 저장소의 함정에 애초에 걸릴 수 없다는 근거다. ⚠ {@code EnderDragon} 을
	 * 상속하는 클래스가 26.3 공용·클라이언트 양쪽 판에 하나도 없는 것까지 {@code javap} 로
	 * 확인했다(7,762개를 훑었다).
	 */
	@Test
	void 날개_넉백이_사는_자리가_그대로_있다() throws NoSuchMethodException {
		Method knockBack = EnderDragon.class
				.getDeclaredMethod("knockBack", ServerLevel.class, List.class);
		assertEquals(void.class, knockBack.getReturnType());
		assertFalse(Modifier.isStatic(knockBack.getModifiers()));
		assertTrue(Modifier.isPrivate(knockBack.getModifiers()),
				"knockBack 이 더 이상 private 이 아니다 — 누가 재정의할 수 있게 됐으니 "
						+ "「조용히 죽는 믹스인」을 다시 확인할 것");

		// ⚠ 접촉 피해 쪽과 서술자가 같다. 같다는 것 자체를 붙들어 둔다 — 이름을 헷갈려 적으면
		// 둘 중 하나가 두 번 걸리고 다른 하나는 한 번도 안 걸리는데, 그때 로그는 조용하다.
		Method contact = EnderDragon.class.getDeclaredMethod("hurt", ServerLevel.class, List.class);
		assertEquals(descriptorOf(contact), descriptorOf(knockBack),
				"둘의 서술자가 갈라졌다 — 그러면 서술자만으로도 가를 수 있으니 위 설명을 고칠 것");
	}

	/**
	 * ⚠ <b>블록 부수기는 {@code knockBack} 안에 없다.</b>
	 *
	 * <p>기존 믹스인이 「블록 부수기는 남긴다」고 적어 두었고 같은 태도를 지킨다. 반구와 발판을
	 * 날리는 것은 같은 {@code aiStep} 의 <b>다른 구간</b>에 있는 {@code checkWalls} 이고 머리·
	 * 목·몸통 상자로 따로 불린다. 이 시험은 그 둘이 <b>실제로 갈라져 있다</b>는 것을 바닐라
	 * 바이트코드에서 직접 본다 — 갈라져 있지 않으면 {@code knockBack} 을 버리는 순간 반구가
	 * 함께 멈춘다.
	 */
	@Test
	void 바닐라_넉백에는_블록을_부수는_줄이_없다() throws NoSuchMethodException {
		List<String> calls = callsIn(EnderDragon.class, "knockBack");
		assertTrue(calls.contains("Entity.push"),
				"knockBack 이 더 이상 밀지 않는다 — 끊을 것이 없어졌으니 믹스인을 다시 볼 것. "
						+ "읽은 차례: " + calls);
		for (String breaks : new String[] {"EnderDragon.checkWalls", "Level.destroyBlock",
				"Level.removeBlock", "Level.setBlock", "ServerLevel.destroyBlock"}) {
			assertFalse(calls.contains(breaks),
					breaks + " 가 knockBack 안에 있다 — 여기를 버리면 블록 부수기가 함께 꺼진다. "
							+ "읽은 차례: " + calls);
		}
		// 부수는 쪽은 여전히 따로 있다. 사라졌으면 「남긴다」는 말이 가리킬 자리가 없어진 것이다.
		Method checkWalls = EnderDragon.class
				.getDeclaredMethod("checkWalls", ServerLevel.class, AABB.class);
		assertEquals(boolean.class, checkWalls.getReturnType(),
				"블록을 부수는 자리가 바뀌었다 — 「블록 부수기는 남긴다」를 다시 확인할 것");
	}

	/**
	 * ⚠ <b>{@code isSitting()} 조건이 피해에만 붙어 있다</b>는 것 — 이 작업의 전제다.
	 *
	 * <p>전에 이 자리에 「앉아 있는 동안 바닐라가 스스로 끈다」고 적혀 있었고 그것은 <b>5 피해에
	 * 대해서만</b> 참이었다. 미는 것은 조건 없이 돈다. 그 사실이 바뀌면(바닐라가 미는 것까지
	 * 앉은 자세에서 끄게 되면) 이 믹스인이 할 일이 없어지므로 여기가 먼저 터져야 한다.
	 */
	@Test
	void 미는_것에는_앉은_자세_조건이_안_붙어_있다() {
		List<String> calls = callsIn(EnderDragon.class, "knockBack");
		int push = calls.indexOf("Entity.push");
		int sitting = calls.indexOf("DragonPhaseInstance.isSitting");
		assertTrue(push >= 0, "미는 줄이 없다: " + calls);
		assertTrue(sitting >= 0,
				"앉은 자세를 묻는 줄이 아예 없어졌다 — 그러면 5 피해도 최후의 저항에서 들어온다: "
						+ calls);
		assertTrue(push < sitting,
				"⚠ 미는 줄이 앉은 자세를 물은 뒤로 옮겨졌다 — 바닐라가 앉은 동안 안 밀게 됐으면 "
						+ "이 믹스인이 할 일이 없다. 읽은 차례: " + calls);
		// 피해는 그 조건 뒤다. 최후의 저항은 isSitting() 이 참인 칸으로 잠그므로 버려지는 것은
		// 미는 힘뿐이고, 그래서 이 취소가 바닐라와 달라지게 만드는 것이 없다.
		assertTrue(calls.indexOf("Entity.hurtServer") > sitting,
				"5 피해가 앉은 자세 조건 앞으로 옮겨졌다 — 그러면 취소가 피해까지 지운다: " + calls);
	}

	// ------------------------------------------------------------------ ② 믹스인이 무는 자리

	/**
	 * 믹스인이 <b>{@code knockBack} 을, 그 하나만</b> 문다.
	 *
	 * <p>⚠⚠ <b>등록된 믹스인은 반사로 못 읽는다.</b> 이 저장소의 시험 환경은
	 * {@code fabric-loader-junit} 이라 Knot 클래스로더가 돌고, 그 로더는
	 * {@code sharedfate.mixins.json} 에 적힌 클래스를 <b>직접 불러오는 것을 금지</b>한다
	 * ({@code IllegalClassLoadError}). 그래서 <b>클래스 파일을 자원으로 읽어 ASM 으로
	 * 뜯는다</b> — 자원 읽기는 클래스 로딩이 아니라 금지에 걸리지 않는다
	 * ({@code DragonLandingDiceTest} 에 그 함정이 길게 적혀 있다).
	 *
	 * <p>⚠ {@code method} 와 {@code at} 이 <b>둘 다 배열</b>이다(sponge-mixin 0.17.4). 소스에
	 * 한 벌만 적어도 클래스 파일에는 길이 1 의 배열로 적히므로 <b>길이를 함께 못박는다</b> —
	 * 둘째가 붙으면 「하나만 문다」가 그 자리에서 거짓이 된다.
	 */
	@Test
	void 믹스인이_날개_넉백을_문다() {
		Map<String, Object> inject = injectOf(KNOCK_BACK_HANDLER);

		assertEquals(Boolean.TRUE, inject.get("present"),
				"@Inject 이 사라졌다 — 아무 일도 하지 않는 메서드가 됐다");
		assertEquals(Integer.valueOf(1), inject.get("method.count"),
				"무는 메서드가 하나가 아니다(" + inject.get("method.count") + "개) — hurt 와 "
						+ "서술자가 같아 둘을 한 애노테이션에 적으면 어느 쪽이 걸렸는지 알 수 없다");
		assertEquals("knockBack(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V",
				inject.get("method[0]"),
				"⚠⚠ 서술자가 바뀌었다 — refmap 이 없어 이 시험 말고는 아무도 못 잡는다. hurt 와 "
						+ "서술자가 글자까지 같으니 이름을 특히 조심할 것");
		assertEquals(Boolean.TRUE, inject.get("cancellable"),
				"cancellable 이 아니면 callback.cancel() 이 그 자리에서 터진다");
		assertEquals(Integer.valueOf(1), inject.get("at.count"),
				"@At 이 하나가 아니다 — HEAD 하나만 문다");
		assertEquals("HEAD", inject.get("at[0].value"),
				"HEAD 가 아니면 미는 줄이 이미 지나간 뒤에 취소된다 — 그러면 아무것도 안 막는다");
		assertEquals("(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;"
						+ "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V",
				inject.get("descriptor"),
				"핸들러가 받는 것이 대상 인자 + CallbackInfo 여야 한다");
	}

	/**
	 * 접촉 피해 쪽은 <b>그대로 남아 있다.</b>
	 *
	 * <p>이번 작업은 <b>더하는</b> 것이다. 날개 넉백을 적다가 머리·목 피해 쪽을 바꿔 버리면
	 * 「넷이 머리에 붙는 순간 한 틱에 40」이 돌아오고, 그 페이즈는 <b>열리지도 않는다.</b>
	 */
	@Test
	void 접촉_피해를_끊는_쪽은_그대로다() {
		Map<String, Object> inject = injectOf(CONTACT_HANDLER);
		assertEquals(Boolean.TRUE, inject.get("present"), "접촉 피해 주입이 사라졌다");
		assertEquals("hurt(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;)V",
				inject.get("method[0]"),
				"⚠ 접촉 피해 쪽 서술자가 바뀌었다 — hurt 가 둘이고 다른 하나는 사람이 드래곤을 "
						+ "때리는 길이다. 그쪽을 막으면 드래곤이 무적이 된다");
		assertEquals(Integer.valueOf(1), inject.get("method.count"));
		assertEquals(Boolean.TRUE, inject.get("cancellable"));
		assertEquals("HEAD", inject.get("at[0].value"));
	}

	/**
	 * ⚠⚠ <b>믹스인이 실제로 붙었는지는 이 시험만 안다.</b>
	 *
	 * <p>손수 돌리는 실행기는 거짓 초록을 준다 — 믹스인이 안 붙어도 애노테이션은 그대로 적혀
	 * 있기 때문이다. 이 저장소의 시험은 Knot 클래스로더를 지나 <b>믹스인을 진짜로 적용</b>하므로,
	 * <b>대상 클래스를 불러오는 것</b>이 곧 검증이다.
	 *
	 * <ul>
	 *   <li>대상을 <b>못 찾으면</b> {@code injectors.defaultRequire = 1} 이라
	 *       {@code EnderDragon} 을 불러오는 이 줄에서 터진다</li>
	 *   <li>붙었으면 핸들러 둘이 <b>대상 클래스에 녹아 들어가 있다</b></li>
	 * </ul>
	 *
	 * <p>⚠ 녹아 들어간 이름은 <b>그대로가 아니다</b> — Mixin 이 {@code handler$…$} 를 앞에
	 * 붙이거나 {@code _$md$…} 를 뒤에 붙여 유일한 이름으로 바꾼다(sponge-mixin 의
	 * {@code MethodMapper.getUniqueName}). 어느 꼴이든 <b>우리가 적은 이름이 부분 문자열로
	 * 남으므로</b> 그것으로 찾는다.
	 *
	 * <p>⚠ <b>접촉 피해 쪽을 먼저 본다.</b> 그쪽은 이미 돌고 있는 주입이라, 그쪽이 안 보이면
	 * 「주입이 빠졌다」가 아니라 <b>「찾는 규칙이 틀렸다」</b>다. 둘을 나란히 두어야 빨강이
	 * 무엇을 말하는지 읽을 수 있다.
	 */
	@Test
	void 대상_클래스에_핸들러_둘이_녹아_있다() {
		List<String> merged = new ArrayList<>();
		for (Method method : EnderDragon.class.getDeclaredMethods()) {
			if (method.getName().contains("drop")) {
				merged.add(method.getName());
			}
		}
		assertTrue(contains(merged, "dropContactDamage"),
				"⚠ 이미 돌고 있는 접촉 피해 핸들러가 대상 클래스에서 안 보인다 — 주입이 빠진 것이 "
						+ "아니라 이 시험이 찾는 규칙이 틀렸을 가능성이 먼저다. 찾은 이름: " + merged);
		assertTrue(contains(merged, "dropWingKnockBack"),
				"⚠⚠ 날개 넉백 핸들러가 대상 클래스에 안 녹았다 — 바로 위 단정이 통과했으니 찾는 "
						+ "규칙은 맞다. 곧 주입이 안 붙은 것이다. 찾은 이름: " + merged);
	}

	/** 믹스인이 {@code sharedfate.mixins.json} 에 <b>등록되어 있다.</b> 안 넣으면 파일만 있다. */
	@Test
	void 믹스인이_등록되어_있다() throws IOException {
		try (InputStream in = DragonLastStand.class.getResourceAsStream("/sharedfate.mixins.json")) {
			assertNotNull(in, "sharedfate.mixins.json 이 클래스패스에 없다");
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(json.contains("\"EnderDragonContactDamageMixin\""),
					"EnderDragonContactDamageMixin 이 mixins.json 에 없다 — 파일만 있고 안 붙는다. "
							+ "⚠ 그 파일은 메인이 독점한다");
		}
	}

	// ------------------------------------------------------------------ ③⚠⚠ 최후의 저항에서만

	/**
	 * ⚠⚠ <b>일반 전투의 날개 밀치기는 그대로 돈다.</b>
	 *
	 * <p>끊는 것은 <b>최후의 저항에서만</b>이다. 일반 전투에서는 우리가 사람의 속도를 본인에게
	 * 내려보내지 않으므로 바닐라가 쌓는 값이 <b>서버 혼자 쌓는 숫자</b>로 남아 무해하고, 그
	 * 자리를 끊으면 바닐라 동작이 사라진다.
	 *
	 * <p>깃발을 읽는 것 말고 아무것도 하지 않는다는 것까지 본다 — 핸들러가 스스로 판단하면 같은
	 * 뜻의 깃발이 둘이 되고, 내리는 자리를 빠뜨린 날 <b>드래곤이 영영 안 미는</b> 판이 생긴다.
	 */
	@Test
	void 일반_전투의_날개_밀치기는_그대로_돈다() {
		// 최후의 저항이 한 번도 안 열린 판 — 곧 거의 모든 판 — 에서 깃발이 거짓이다.
		DragonLastStand.clearState();
		assertFalse(DragonLastStand.contactDamageOff(),
				"깃발이 저절로 참이다 — 일반 전투의 드래곤이 사람을 안 밀고 안 때린다");

		List<String> calls = handlerCalls(KNOCK_BACK_HANDLER);
		assertEquals(List.of("DragonLastStand.contactDamageOff", "CallbackInfo.cancel"), calls,
				"핸들러가 하는 일은 「깃발을 읽고, 참이면 버린다」 둘뿐이어야 한다. 깃발을 안 읽으면 "
						+ "일반 전투의 날개 밀치기까지 끊기고, 다른 일을 더 하면 최후의 저항이 한 번도 "
						+ "안 열린 판까지 그 비용을 치른다. 읽은 차례: " + calls);
	}

	/**
	 * {@code contactDamageOff()} 가 <b>정말 최후의 저항 전용 깃발</b>이다.
	 *
	 * <p>{@code DragonLastStand} 의 {@code Stand} 가 만들어지는 틱에 참이 되고, 마지막
	 * {@code Stand} 가 사라지는 틱({@code onFightClosed})과 {@code clearState} 에서 거짓이 된다.
	 * 그 셋 말고는 이 깃발을 쓰는 곳이 없다 — 곧 <b>「최후의 저항이 도는 팀이 하나라도 있는가」</b>
	 * 와 같은 말이다.
	 */
	@Test
	void 깃발이_최후의_저항_전용이다() {
		DragonLastStand.clearState();
		assertFalse(DragonLastStand.contactDamageOff(), "비운 뒤에 참이면 다음 판의 드래곤이 멈춘다");
		assertFalse(DragonLastStand.isRunning(java.util.UUID.randomUUID()),
				"도는 팀이 없는데 깃발만 따로 서면 둘이 갈라진 것이다");

		String bytes = read("/com/sharedfate/sync/DragonLastStand.class");
		assertTrue(bytes.contains("contactDamageOff"), "깃발 이름이 바뀌었다");
		// 깃발을 세우는 곳과 내리는 곳이 한 파일 안에 있다. 남이 세우면 「최후의 저항 전용」이
		// 거짓이 된다 — 그때 일반 전투의 드래곤이 조용히 멈춘다.
		for (String other : new String[] {"/com/sharedfate/sync/DragonPerch.class",
				"/com/sharedfate/sync/DragonTrialManager.class"}) {
			assertFalse(read(other).contains("contactDamageOff"),
					other + " 가 깃발을 만진다 — 세우는 자리가 둘이 되면 「최후의 저항 전용」이 "
							+ "아니게 된다");
		}
	}

	/** 믹스인이 드래곤에게 <b>한 칸도 쓰지 않는다.</b> 페이즈에 끼어들면 착지가 사라진다. */
	@Test
	void 믹스인이_드래곤을_쓰지_않는다() {
		String bytes = mixinBytes();
		assertTrue(bytes.contains("com/sharedfate/sync/DragonLastStand"),
				"믹스인이 DragonLastStand 를 안 본다 — 무엇을 보고 끄는지 알 수 없다");
		assertFalse(bytes.contains("checkWalls"),
				"블록 부수기를 건드린다 — 포탈 주변 발판이 날아가는 것이 이 페이즈의 압박 중 하나다");
		for (String writes : new String[] {"setPhase", "setHealth", "setNoAi", "setInvulnerable",
				"setDeltaMovement", "setPos", "setTarget"}) {
			assertFalse(bytes.contains(writes),
					writes + " — 믹스인이 드래곤을 쓰고 있다. 페이즈에 끼어들면 착지가 사라진다");
		}
		assertFalse(bytes.contains("getGameTime"), "시각을 묻지 않는다 — 판이 얼면 그 값만 안 멈춘다");
	}

	// ------------------------------------------------------------------ 도우미

	private static final String MIXIN =
			"/com/sharedfate/mixin/EnderDragonContactDamageMixin.class";
	private static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
	private static final String KNOCK_BACK_HANDLER = "sharedfate$dropWingKnockBack";
	private static final String CONTACT_HANDLER = "sharedfate$dropContactDamage";

	private static boolean contains(List<String> names, String part) {
		for (String name : names) {
			if (name.contains(part)) {
				return true;
			}
		}
		return false;
	}

	private static String descriptorOf(Method method) {
		StringBuilder out = new StringBuilder("(");
		for (Class<?> parameter : method.getParameterTypes()) {
			out.append(typeOf(parameter));
		}
		return out.append(')').append(typeOf(method.getReturnType())).toString();
	}

	private static String typeOf(Class<?> type) {
		if (type == void.class) {
			return "V";
		}
		if (type == boolean.class) {
			return "Z";
		}
		return "L" + type.getName().replace('.', '/') + ";";
	}

	/**
	 * 믹스인 메서드에 달린 {@code @Inject} 를 <b>클래스 파일에서</b> 읽어 평평한 이름으로
	 * 돌려준다. 자원으로 읽으므로 믹스인 클래스를 <b>불러오지 않는다.</b>
	 */
	private static Map<String, Object> injectOf(String methodName) {
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
						if (!INJECT.equals(annotation)) {
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
				// 빈 배열이어도 개수를 적어 둔다. 안 적으면 「없다」가 「못 읽었다」와 안 갈린다.
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

	/** 믹스인 핸들러 하나가 부르는 것들의 <b>차례</b>. 자원으로 읽어 클래스를 안 불러온다. */
	private static List<String> handlerCalls(String methodName) {
		List<String> calls = callsIn(bytesOf(MIXIN), methodName);
		assertFalse(calls.isEmpty(), methodName + " 이 아무것도 안 부른다 — 빈 메서드가 됐다");
		return calls;
	}

	/** 바닐라 메서드 하나가 부르는 것들의 차례. 클래스 파일을 <b>자원으로</b> 읽는다. */
	private static List<String> callsIn(Class<?> owner, String methodName) {
		List<String> calls = callsIn(
				bytesOf("/" + owner.getName().replace('.', '/') + ".class"), methodName);
		assertFalse(calls.isEmpty(),
				owner.getSimpleName() + "." + methodName + " 을 읽지 못했다");
		return calls;
	}

	private static List<String> callsIn(byte[] classFile, String methodName) {
		List<String> calls = new ArrayList<>();
		new ClassReader(classFile).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor,
					String signature, String[] exceptions) {
				if (!name.equals(methodName)) {
					return null;
				}
				return new MethodVisitor(Opcodes.ASM9) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String call,
							String callDescriptor, boolean isInterface) {
						calls.add(owner.substring(owner.lastIndexOf('/') + 1) + "." + call);
					}
				};
			}
		}, ClassReader.SKIP_FRAMES);
		return calls;
	}

	private static String mixinBytes() {
		return read(MIXIN);
	}

	/** 컴파일된 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String read(String path) {
		return new String(bytesOf(path), StandardCharsets.ISO_8859_1);
	}

	/** 자원 한 개의 바이트. <b>클래스를 불러오지 않는다</b> — 믹스인을 읽는 유일한 길이다. */
	private static byte[] bytesOf(String path) {
		try (InputStream in = EnderDragonKnockBackTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return in.readAllBytes();
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
