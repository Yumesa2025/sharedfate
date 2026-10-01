package com.sharedfate.sync;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonSittingPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonHoldingPatternPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonHoverPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonSittingAttackingPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonSittingFlamingPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonSittingScanningPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhaseManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 「착지」 패시브에서 월드 없이 답이 정해지는 것만 본다.
 *
 * <p>드래곤을 실제로 앉혀 보려면 {@code ServerLevel} 과 {@code EnderDragon} 이 있어야 해서
 * 여기서 굴려 볼 수 없다. 그래도 <b>이 패시브가 망가지는 길 가운데 가장 무서운 것</b>은 여기서
 * 전부 잡힌다.
 *
 * <h2>⚠⚠ 이 파일에서 가장 중요한 시험</h2>
 *
 * <p>{@link #최후의_저항에서는_원거리_면역이_안_걸린다} 다. 「최후의 저항」은 드래곤을
 * {@code EnderDragonPhase.HOVERING} 으로 영구히 앉혀 두고 사람이 <b>활로 115초에 1200 을
 * 깎는</b> 설계이고, {@code HOVERING} 은 {@code isSitting()} 이 <b>참</b>이다. 곧 「앉았는가」를
 * {@code isSitting()} 으로 묻는 한 줄이 들어오는 날 <b>그 보스전을 이길 방법이 사라진다</b> —
 * 활이 한 대도 안 들어가고, 증상은 「최후의 저항이 안 끝난다」로만 나온다. 실제로 굴려 보고
 * 발견하려면 전멸해야 하고, 이 게임에서 전멸은 월드 삭제다.
 */
class DragonPerchTest {

	/** 정적 깃발이라 시험끼리 샌다. 앞뒤로 비운다. */
	@BeforeEach
	@AfterEach
	void 상태를_비운다() {
		DragonPerch.clearState();
	}

	// ------------------------------------------------------------------ ⚠⚠ 최후의 저항을 거른다

	/**
	 * <b>이 시험이 이 파일의 목숨이다.</b>
	 *
	 * <p>「최후의 저항」이 드래곤을 재워 두는 칸이 {@code HOVERING} 이고 그 칸은
	 * {@code isSitting()} 이 참이다. 「앉았다」를 그것으로 물으면 그 페이즈가 통째로 거짓이 된다.
	 *
	 * <p>막는 것을 둘로 겹쳤으므로 둘을 함께 본다 — <b>구조</b>({@code perched} 가 앉은 칸 셋만
	 * 이름으로 센다)와 <b>배선</b>({@code DragonTrialManager} 가 진입 틱에 깃발을 내린다).
	 */
	@Test
	void 최후의_저항에서는_원거리_면역이_안_걸린다() {
		assertNotNull(EnderDragonPhase.HOVERING);
		assertFalse(DragonPerch.perched(EnderDragonPhase.HOVERING),
				"HOVERING 을 앉은 칸으로 세면 최후의 저항에서 활이 한 대도 안 들어간다 — "
						+ "그 페이즈를 이길 방법이 사라지고 증상은 「안 끝난다」로만 나온다");

		// 구조 — 이 파일의 상수 풀에 HOVERING 이라는 이름이 아예 없다.
		assertFalse(classBytes().contains("HOVERING"),
				"DragonPerch 가 HOVERING 을 들고 있다 — isSitting() 으로 돌아가는 첫 걸음이다");

		// 배선 — 최후의 저항이 열린 틱에 깃발을 내리는 줄이 살아 있다.
		String manager = read("/com/sharedfate/sync/DragonTrialManager.class",
				StandardCharsets.ISO_8859_1);
		assertTrue(manager.contains("com/sharedfate/sync/DragonPerch"),
				"DragonTrialManager 가 DragonPerch 를 안 본다 — 최후의 저항이 열리는 틱에 깃발을 "
						+ "내릴 사람이 없다. 그 뒤로 DragonPassives.tick 은 한 번도 안 불린다");
		assertTrue(manager.contains("standDown"), "깃발을 내리는 이름이 바뀌었다");

		// 깃발 자체도 내려간다.
		DragonPerch.standDown();
		assertFalse(DragonPerch.rangedImmune(), "standDown 뒤에도 원거리 면역이 켜져 있다");
		assertFalse(DragonPerch.holdsTakeoff(), "standDown 뒤에도 이륙 고정이 켜져 있다");
	}

	/** 앉은 칸 셋만 참이다. 날고 있는 칸이 하나라도 참이면 공중의 드래곤이 화살을 안 받는다. */
	@Test
	void 앉은_칸은_셋뿐이다() {
		assertTrue(DragonPerch.perched(EnderDragonPhase.SITTING_SCANNING));
		assertTrue(DragonPerch.perched(EnderDragonPhase.SITTING_ATTACKING));
		assertTrue(DragonPerch.perched(EnderDragonPhase.SITTING_FLAMING));
		for (EnderDragonPhase<?> flying : List.<EnderDragonPhase<?>>of(EnderDragonPhase.HOLDING_PATTERN,
				EnderDragonPhase.STRAFE_PLAYER, EnderDragonPhase.CHARGING_PLAYER,
				EnderDragonPhase.LANDING_APPROACH, EnderDragonPhase.LANDING,
				EnderDragonPhase.TAKEOFF, EnderDragonPhase.HOVERING, EnderDragonPhase.DYING)) {
			assertFalse(DragonPerch.perched(flying),
					flying + " 을 앉은 칸으로 센다 — 날고 있는 드래곤이 원거리를 안 받는다");
		}
		assertFalse(DragonPerch.perched(null), "페이즈를 못 읽은 틱에 규칙이 켜지면 안 된다");
	}

	/**
	 * 바닐라에서 앉은 칸 셋이 <b>정말로</b> 그 셋인가.
	 *
	 * <p>{@link DragonPerch#perched} 가 이름 셋으로 세는 근거가 이것이다. 판이 올라 넷째 앉은
	 * 칸이 생기면 여기가 먼저 터져야 한다 — 안 그러면 그 칸에서만 조용히 규칙이 빠진다.
	 */
	@Test
	void 바닐라의_앉은_칸이_그_셋이다() {
		for (Class<?> sitting : List.<Class<?>>of(DragonSittingScanningPhase.class,
				DragonSittingAttackingPhase.class, DragonSittingFlamingPhase.class)) {
			assertTrue(AbstractDragonSittingPhase.class.isAssignableFrom(sitting),
					sitting.getSimpleName() + " 이 앉은 계열에서 빠졌다");
		}
		assertFalse(AbstractDragonSittingPhase.class.isAssignableFrom(DragonHoverPhase.class),
				"HOVERING 이 앉은 계열로 들어왔다 — 최후의 저항의 「활로 1200」이 그 자리에서 깨진다");
	}

	// ------------------------------------------------------------------ ② 고정 시간

	/**
	 * 8초가 <b>지어낸 값이 아니다.</b>
	 *
	 * <p>「착지 충격」이 착지마다 고리 셋을 내보내므로 적어도 그 셋이 섬을 다 지나갈 때까지는
	 * 앉아 있어야 사람이 세 번을 읽어 볼 기회가 생긴다. 카드 값에서 다시 뽑아 상수와 맞춰 본다 —
	 * 카드를 고치는 사람이 이 상수를 잊으면 여기가 터진다.
	 */
	@Test
	void 고정_시간이_착지_충격_카드에서_나온다() {
		TrialCatalog.Risk.LandingShock shock = landingShock();
		assertEquals(3, shock.ringCount(), "고리 수가 바뀌었다 — 고정 시간을 다시 볼 것");
		assertEquals(40, shock.ringIntervalTicks(), "고리 간격이 바뀌었다");
		assertEquals(150, shock.travelTicks());
		assertEquals(75.0, shock.maxRadius(), 1.0E-9);
		assertEquals(DragonPerch.HOLD_TICKS,
				DragonPerch.holdTicksFor(shock, TrialRisks.ARENA_RADIUS),
				"고정 시간이 카드 값과 어긋났다 — 마지막 고리가 섬을 다 지나기 전에 드래곤이 "
						+ "일어나면 뒤쪽 고리가 「드래곤도 없는데 바닥이 터진다」가 된다");
		assertEquals(160, DragonPerch.HOLD_TICKS, "8초다");
	}

	/**
	 * 바닐라가 앉아 있던 시간보다 길다.
	 *
	 * <p>사람이 「너무 빨리 일어난다」고 한 것이 {@code DragonSittingScanningPhase} 의 100틱이다.
	 * 고정이 그보다 짧으면 이 패시브는 아무 일도 하지 않는다.
	 */
	@Test
	void 고정이_바닐라의_100틱보다_길다() {
		assertTrue(DragonPerch.HOLD_TICKS > 100,
				"바닐라는 반경 20 안에 사람이 없으면 100틱(5초)에 일어난다 — "
						+ "고정이 그보다 짧으면 사람이 지적한 것이 하나도 안 고쳐진다");
	}

	@Test
	void 고정은_앉은_틱부터_HOLD_TICKS_동안만_참이다() {
		long seatedAt = 1_000L;
		assertTrue(DragonPerch.holding(seatedAt, seatedAt), "앉은 그 틱부터 고정이다");
		assertTrue(DragonPerch.holding(seatedAt, seatedAt + DragonPerch.HOLD_TICKS - 1),
				"마지막 틱에 고정이 풀렸다");
		assertFalse(DragonPerch.holding(seatedAt, seatedAt + DragonPerch.HOLD_TICKS),
				"고정이 끝나야 하는 틱에 안 풀렸다 — 안 풀리면 드래곤이 영영 앉아 있다");
		assertFalse(DragonPerch.holding(seatedAt, seatedAt - 1),
				"시간이 되감겼다. 그때는 고정하지 않는다");
	}

	/**
	 * 못 본 착지의 시계를 <b>지어내지 않는다.</b>
	 *
	 * <p>서버를 껐다 켜거나 전투를 이어받으면 이미 앉아 있는 드래곤을 처음 보게 된다. 그때
	 * 「지금 앉았다」로 치면 사람이 본 착지와 어긋난 8초가 생긴다 — 원거리 면역만 주고 고정은
	 * 다음 착지부터다.
	 */
	@Test
	void 앉은_틱을_모르면_고정하지_않는다() {
		assertFalse(DragonPerch.holding(Long.MIN_VALUE, 0L));
		assertFalse(DragonPerch.holding(Long.MIN_VALUE, Long.MAX_VALUE));
	}

	/**
	 * 「방금 내려앉았는가」를 {@link TrialLandingShock} 에서 그대로 가져온다.
	 *
	 * <p>같은 뜻의 판별을 두 파일에 적으면 한쪽만 고쳐지고, 그때 어긋나는 것은 숫자가 아니라
	 * <b>「한 번의 착지」가 두 파일에서 다른 것을 가리키게 되는 것</b>이다.
	 */
	@Test
	void 착지_가장자리_판별을_착지_충격에서_가져온다() {
		assertTrue(classBytes().contains("touchdown"),
				"가장자리 판별을 여기서 새로 만들었다 — 「한 번의 착지」가 두 뜻이 된다");
		assertFalse(TrialLandingShock.touchdown(null, true), "처음 본 틱은 가장자리가 아니다");
		assertFalse(TrialLandingShock.touchdown(Boolean.TRUE, true), "앉은 내내 가장자리가 되면 안 된다");
		assertTrue(TrialLandingShock.touchdown(Boolean.FALSE, true));
	}

	// ------------------------------------------------------------------ ③ 원거리 판정

	/**
	 * 통과 목록으로 적는다 — <b>금지 목록이면 샌다.</b>
	 *
	 * <p>26.3 {@code #minecraft:is_projectile} 은 여덟 줄이고(화살·삼지창·몹 투사체·화염구 둘·
	 * 위더 해골·던진 것·바람 charge) <b>쇠뇌 폭죽({@code fireworks})·스플래시 물약
	 * ({@code magic})·TNT({@code explosion}) 가 거기 없다.</b> 이 저장소가 「한쪽만 막으면 반드시
	 * 샌다」로 네 번 틀린 자리가 그런 모양이었다.
	 */
	@Test
	void 원거리_판정이_허용_목록이다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("IS_PLAYER_ATTACK"),
				"근접을 태그로 세지 않는다 — 플레이어와의 거리로 재면 안 된다");
		assertTrue(bytes.contains("BYPASSES_INVULNERABILITY"),
				"바닐라가 「무적을 지나친다」고 적어 둔 것을 막으면 /kill 이 앉은 드래곤에게 안 듣는다");
		assertFalse(bytes.contains("IS_PROJECTILE"),
				"금지 목록으로 돌아갔다 — 쇠뇌 폭죽·스플래시 물약·TNT 가 그 태그에 없어 샌다");
	}

	/**
	 * 바닐라가 이미 하는 일과 <b>겹치는 범위를 안다.</b>
	 *
	 * <p>{@code AbstractDragonSittingPhase.onHurt} 가 {@code AbstractArrow}·{@code WindCharge} 를
	 * 0 으로 만들고 불을 붙인다. 앉은 칸 셋이 그것을 재정의하지 않는다는 것이 「화살은 우리가
	 * 없어도 안 들어간다」의 근거다. 그래도 우리가 함께 막는 까닭(되먹임을 한 종류로 맞춘다)은
	 * {@code DragonPerch} 클래스 설명에 있다.
	 */
	@Test
	void 바닐라가_화살을_막는_자리를_확인한다() throws NoSuchMethodException {
		Method onHurt = AbstractDragonSittingPhase.class
				.getDeclaredMethod("onHurt", DamageSource.class, float.class);
		assertEquals(float.class, onHurt.getReturnType());
		for (Class<?> sitting : List.<Class<?>>of(DragonSittingScanningPhase.class,
				DragonSittingAttackingPhase.class, DragonSittingFlamingPhase.class)) {
			assertThrows(NoSuchMethodException.class,
					() -> sitting.getDeclaredMethod("onHurt", DamageSource.class, float.class),
					sitting.getSimpleName() + " 이 onHurt 를 재정의하게 됐다 — "
							+ "「화살은 바닐라가 이미 막는다」의 근거가 그 칸에서만 깨진다");
		}
	}

	// ------------------------------------------------------------------ 드래곤을 쓰지는 않는다

	/**
	 * 페이즈를 <b>읽기만</b> 한다.
	 *
	 * <p>「표적」 카드가 드래곤을 돌진 페이즈로 밀었다가 <b>착지를 아예 안 하게</b> 만든 사고는
	 * 쓰는 쪽에서 났다 — {@code setPhase} 가 부르는 {@code begin()} 이
	 * {@code DragonHoldingPatternPhase.currentPath} 를 지우고, 바닐라는 그 경로가 끝난 틱에만
	 * 「착지할까」를 굴린다. 하필 이 패시브가 사는 조건이 착지다.
	 */
	@Test
	void 페이즈를_쓰지_않는다() {
		String bytes = classBytes();
		assertTrue(bytes.contains("getCurrentPhase"), "페이즈를 읽지 않으면 앉았는지 알 길이 없다");
		for (String writes : new String[] {"setPhase", "setTarget", "setPos", "setDeltaMovement",
				"setHealth", "setNoAi", "setInvulnerable"}) {
			assertFalse(bytes.contains(writes),
					writes + " — 드래곤을 쓰고 있다. 착지를 멈춘 사고가 그렇게 났고, "
							+ "이 패시브는 그 착지로 산다");
		}
	}

	/** {@code level.getGameTime()} 을 쓰지 않는다. 시련 화면이 떠 판이 얼면 그 값만 안 멈춘다. */
	@Test
	void 월드_시각을_직접_읽지_않는다() {
		assertFalse(classBytes().contains("getGameTime"),
				"넘겨받은 now 를 쓰지 않으면 룰렛이 떠 있는 동안 고정 시간이 혼자 흐른다");
	}

	@Test
	void clearState_가_깃발과_기억을_모두_버린다() {
		DragonPerch.clearState();
		assertFalse(DragonPerch.holdsTakeoff());
		assertFalse(DragonPerch.rangedImmune());
		assertFalse(DragonPerch.holding(Long.MIN_VALUE, 0L));
	}

	/** 패시브 자리에 묶여 있는가. 빠지면 아무 일도 하지 않는 패시브가 된다. */
	@Test
	void 패시브_배선에_들어_있다() {
		String passives = read("/com/sharedfate/sync/DragonPassives.class",
				StandardCharsets.ISO_8859_1);
		assertTrue(passives.contains("com/sharedfate/sync/DragonPerch"),
				"DragonPassives 가 DragonPerch 를 안 부른다 — 깃발이 한 번도 안 세워진다");
	}

	// ------------------------------------------------------------------ 믹스인

	/**
	 * 이륙을 막는 자리가 26.3 에 그대로 있다.
	 *
	 * <p>{@code EnderDragonPhaseManager.setPhase} 는 앉은 드래곤이 일어나는 <b>세 길이 전부</b>
	 * 지나는 문이다. 서술자가 틀리면 refmap 이 없어 빌드는 통과하고, 증상은 「고쳐지지 않았다」뿐이다.
	 */
	@Test
	void 이륙을_막는_자리가_그대로_있다() throws NoSuchMethodException, NoSuchFieldException {
		Method setPhase = EnderDragonPhaseManager.class
				.getDeclaredMethod("setPhase", EnderDragonPhase.class);
		assertEquals(void.class, setPhase.getReturnType());
		assertFalse(Modifier.isStatic(setPhase.getModifiers()));
		Field dragon = EnderDragonPhaseManager.class.getDeclaredField("dragon");
		assertEquals(EnderDragon.class, dragon.getType(),
				"@Shadow 로 보는 칸이 바뀌었다 — 클라이언트인지 가를 길이 없어진다");
		assertTrue(Modifier.isPrivate(dragon.getModifiers()));
		assertTrue(Modifier.isFinal(dragon.getModifiers()), "@Shadow @Final 이 어긋난다");
	}

	/** 원거리를 막는 자리. {@code EnderDragon} 의 <b>다른</b> {@code hurt} 를 물면 뜻이 반대가 된다. */
	@Test
	void 원거리를_막는_자리가_그대로_있다() throws NoSuchMethodException {
		Method byPlayer = EnderDragon.class.getDeclaredMethod("hurt", ServerLevel.class,
				EnderDragonPart.class, DamageSource.class, float.class);
		assertEquals(boolean.class, byPlayer.getReturnType(),
				"사람의 피해가 지나는 길이다. 여기가 아니면 원거리를 막을 수 없다");
		assertFalse(Modifier.isStatic(byPlayer.getModifiers()));
		// 드래곤이 사람을 때리는 쪽은 서술자가 다르다. 그쪽을 물면 접촉 피해가 꺼진다.
		Method contact = EnderDragon.class.getDeclaredMethod("hurt", ServerLevel.class, List.class);
		assertEquals(void.class, contact.getReturnType());
	}

	/**
	 * 고정 믹스인이 <b>죽는 길과 앉은 칸과 최후의 저항을 열어 둔다.</b>
	 *
	 * <p>{@code DYING} 을 막으면 드래곤이 죽지 않아 회차가 거기서 멈춘다. {@code HOVERING} 을
	 * 막으면 최후의 저항이 열리지 않는다. 앉은 칸끼리의 이동을 막으면 고정이 연출 없는 공짜
	 * 시간이 된다.
	 */
	@Test
	void 고정_믹스인이_열어_두어야_할_것을_열어_둔다() {
		String bytes = holdMixinBytes();
		for (String open : new String[] {"DYING", "HOVERING",
				"SITTING_SCANNING", "SITTING_ATTACKING", "SITTING_FLAMING"}) {
			assertTrue(bytes.contains(open),
					open + " 이 허용 목록에서 빠졌다 — " + ("DYING".equals(open)
							? "드래곤이 죽지 않는다"
							: "HOVERING".equals(open) ? "최후의 저항이 열리지 않는다"
									: "앉은 드래곤이 아무것도 안 한다"));
		}
		assertTrue(bytes.contains("isClientSide"),
				"클라이언트를 안 가른다 — 서버가 고정을 푼 뒤에도 사람 화면의 드래곤만 앉아 있다");
		assertTrue(bytes.contains("holdsTakeoff"), "깃발 이름이 바뀌었다");
		assertTrue(bytes.contains("(Lnet/minecraft/world/entity/boss/enderdragon/phases/"
						+ "EnderDragonPhase;)V"),
				"서술자가 바뀌었다 — refmap 이 없어 이 시험 말고는 아무도 못 잡는다");
	}

	/** 원거리 믹스인이 우리 깃발과 판별을 읽는다. 판별을 믹스인 안에 복사하면 두 곳이 된다. */
	@Test
	void 원거리_믹스인이_우리_판별을_읽는다() {
		String bytes = rangedMixinBytes();
		assertTrue(bytes.contains("com/sharedfate/sync/DragonPerch"),
				"믹스인이 DragonPerch 를 안 본다 — 무엇을 보고 막는지 알 수 없다");
		assertTrue(bytes.contains("rangedImmune"), "깃발 이름이 바뀌었다");
		assertTrue(bytes.contains("melee"), "판별을 믹스인 안에 복사했다 — 뜻이 두 곳에 있게 된다");
		assertTrue(bytes.contains("deflect"),
				"막은 것을 보이지 않는다 — 아무 반응 없이 0 이 들어가면 사람은 버그로 읽는다");
		assertTrue(bytes.contains("(Lnet/minecraft/server/level/ServerLevel;"
						+ "Lnet/minecraft/world/entity/boss/enderdragon/EnderDragonPart;"
						+ "Lnet/minecraft/world/damagesource/DamageSource;F)Z"),
				"서술자가 바뀌었다 — 다른 hurt 를 물면 드래곤이 사람을 못 때리게 된다");
	}

	/**
	 * 믹스인 둘이 <b>등록되어 있다.</b>
	 *
	 * <p>{@code sharedfate.mixins.json} 에 이름을 안 넣으면 파일만 있고 아무 일도 하지 않는다 —
	 * 컴파일도 빌드도 조용하다. ⚠ 그 파일은 <b>이 작업에서 다른 사람이 쥐고 있는 파일</b>이라
	 * 여기가 빨간 채로 넘어갈 수 있다. 빨간 것이 곧 「두 줄을 넣어 달라」는 말이다.
	 */
	@Test
	void 믹스인_둘이_등록되어_있다() throws IOException {
		try (InputStream in = DragonPerch.class.getResourceAsStream("/sharedfate.mixins.json")) {
			assertNotNull(in, "sharedfate.mixins.json 이 클래스패스에 없다");
			String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
			assertTrue(json.contains("\"EnderDragonPerchHoldMixin\""),
					"EnderDragonPerchHoldMixin 이 mixins.json 에 없다 — 착지 고정이 안 붙는다");
			assertTrue(json.contains("\"EnderDragonPerchRangedImmunityMixin\""),
					"EnderDragonPerchRangedImmunityMixin 이 mixins.json 에 없다 — 원거리가 그대로 들어간다");
		}
	}

	// ------------------------------------------------------------------ ① 착지 주사위가 사는 자리

	/**
	 * <b>조사 결과를 못박아 둔다 — 「착지를 너무 안 한다」의 원인은 우리 코드가 아니다.</b>
	 *
	 * <p>26.3 {@code DragonHoldingPatternPhase.findNewTarget} 을 디스어셈블해 읽은 조건이다.
	 *
	 * <pre>{@code
	 * if (currentPath != null && currentPath.isDone()) {
	 *     int alive = fight == null ? 0 : fight.aliveCrystals();
	 *     if (random.nextInt(alive + 3) == 0) { setPhase(LANDING_APPROACH); return; }
	 *     ...
	 * }
	 * }</pre>
	 *
	 * <p>곧 <b>크리스탈이 살아 있는 수가 착지 확률을 깎는다</b> — 열 개면 1/13, 0 개면 1/3 이다.
	 * 우리 판은 크리스탈 카드 넷이 깨는 것을 늦추고 「부활」이 열 개를 다시 세우므로, 바닐라의
	 * 「크리스탈을 다 깨면 앉는다」가 「거의 안 앉는다」로 읽힌다.
	 *
	 * <p>⚠ <b>「착지」 패시브는 이 주사위를 건드리지 않는다</b> — 여기가 한 것은 <b>한 번의 착지를
	 * 길게</b>({@link DragonPerch#HOLD_TICKS})뿐이다. 아래 단정들이 그 사실을 못박는다.
	 *
	 * <p>주사위에 손을 댄 것은 <b>{@link DragonLandingDice} 와
	 * {@code DragonHoldingPatternLandingMixin}</b> 이고, 그쪽은 넘기는 값에 <b>천장 6 만</b> 씌운다
	 * (사람 말: 「크리스탈 영향에 천장 — 최대 1/6 으로 못박아」). 주사위를 평평하게 만들지 않은
	 * 것이 중요한데, 크리스탈 0~3 개 구간이 <b>바닐라와 같은 값</b>이라 「부활이 뽑히면 착지가
	 * 사라지고 착지 충격이 남은 전투 내내 안 터진다」({@code docs/드래곤-트라이얼.md} 의
	 * <b>의도</b>)가 <b>약해지기만 하고 죽지는 않는다</b>. 그 천장은
	 * {@code DragonLandingDiceTest} 가 지킨다.
	 *
	 * <p>이 시험은 그 사실들이 26.3 에 그대로 있는지만 본다. 바뀌면 조사를 다시 해야 한다.
	 */
	@Test
	void 착지_주사위는_바닐라의_크리스탈_수에_달려_있다() {
		String holding = vanillaClassBytes(DragonHoldingPatternPhase.class);
		assertTrue(holding.contains("aliveCrystals"),
				"착지 확률이 크리스탈 수에서 떨어졌다 — 「착지를 너무 안 한다」의 원인 분석을 다시 할 것");
		assertTrue(holding.contains("LANDING_APPROACH"),
				"착지로 들어가는 칸이 바뀌었다");
		// 「착지」 패시브 쪽은 그 주사위를 보지 않는다. 천장을 씌우는 일은 DragonLandingDice 한
		// 곳에만 있고, 여기까지 크리스탈 수를 읽기 시작하면 같은 뜻이 두 곳에 있게 된다.
		assertFalse(classBytes().contains("aliveCrystals"),
				"DragonPerch 가 크리스탈 수를 본다 — 주사위에 손을 댄 것이면 부활 상호작용이 죽는다");
		assertFalse(holdMixinBytes().contains("aliveCrystals"));
	}

	/** 사람이 「너무 빨리 일어난다」고 한 100틱이 바닐라의 그 자리에 있다. */
	@Test
	void 바닐라가_5초에_일어나는_자리가_그대로_있다() {
		String scanning = vanillaClassBytes(DragonSittingScanningPhase.class);
		assertTrue(scanning.contains("TAKEOFF"),
				"DragonSittingScanningPhase 가 TAKEOFF 로 가지 않게 됐다 — "
						+ "그러면 고정이 막을 대상이 사라졌다는 뜻이라 근거를 다시 볼 것");
		assertTrue(vanillaClassBytes(DragonSittingFlamingPhase.class).contains("TAKEOFF"),
				"불 넷을 다 뿜고 일어나는 길이 사라졌다");
	}

	// ------------------------------------------------------------------ 도우미

	/** 「착지 충격」 카드의 값. 목록에서 직접 꺼내므로 카드를 고치면 시험이 함께 움직인다. */
	private static TrialCatalog.Risk.LandingShock landingShock() {
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				if (risk instanceof TrialCatalog.Risk.LandingShock shock) {
					return shock;
				}
			}
		}
		return fail("「착지 충격」 카드를 목록에서 못 찾았다 — 고정 시간의 근거가 사라졌다");
	}

	private static String classBytes() {
		return read("/com/sharedfate/sync/DragonPerch.class", StandardCharsets.ISO_8859_1);
	}

	private static String holdMixinBytes() {
		return read("/com/sharedfate/mixin/EnderDragonPerchHoldMixin.class",
				StandardCharsets.ISO_8859_1);
	}

	private static String rangedMixinBytes() {
		return read("/com/sharedfate/mixin/EnderDragonPerchRangedImmunityMixin.class",
				StandardCharsets.ISO_8859_1);
	}

	/** 바닐라 클래스의 바이트. 우리가 기대고 있는 사실이 그 안에 있는지 본다. */
	private static String vanillaClassBytes(Class<?> type) {
		return read("/" + type.getName().replace('.', '/') + ".class", StandardCharsets.ISO_8859_1);
	}

	/** 컴파일된 클래스의 바이트. 상수 풀에 무엇이 들어 있는지를 문자열로 뒤진다. */
	private static String read(String path, java.nio.charset.Charset charset) {
		try (InputStream in = DragonPerchTest.class.getResourceAsStream(path)) {
			if (in == null) {
				return fail(path + " 을 찾지 못했다");
			}
			return new String(in.readAllBytes(), charset);
		} catch (IOException failed) {
			return fail(failed);
		}
	}
}
