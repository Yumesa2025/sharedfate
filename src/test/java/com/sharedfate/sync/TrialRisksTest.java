package com.sharedfate.sync;

import com.sharedfate.perk.PerkHealthRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 위험 실행기에서 월드 없이 답이 정해지는 계산만 본다.
 *
 * <p>파티클·피해·띄우기는 {@code ServerLevel} 이 있어야 해서 여기서 볼 수 없다. 그런데 이
 * 기능이 망가지는 길은 거의 전부 계산 쪽이다 — <b>받자마자 터진다</b>, <b>카드 둘이 같은 틱에
 * 겹쳐 터진다</b>, <b>복원 직후 위상이 음수가 된다</b>, <b>없는 다섯째 팀원을 뽑으려 한다</b>.
 * 전멸하면 월드가 지워지는 게임이라 이것들은 돌려 보고 발견할 수 없다.
 */
class TrialRisksTest {
	/** 「자리 폭격」의 주기. 실제 카드 값과 같게 둬야 시험이 현실과 붙어 있다. */
	private static final int INTERVAL = 240;
	private static final long GRANTED = 1000L;

	// ------------------------------------------------------------------ 위상

	@Test
	void 카드를_받은_직후에는_한_주기가_온전히_지나야_터진다() {
		for (long now = GRANTED; now < GRANTED + INTERVAL; now++) {
			assertFalse(TrialRisks.firesAt(now, GRANTED, INTERVAL),
					"받은 뒤 한 주기 안에 터지면 카드 설명을 읽는 중에 맞는다: " + (now - GRANTED));
		}
		assertTrue(TrialRisks.firesAt(GRANTED + INTERVAL, GRANTED, INTERVAL),
				"한 주기가 지나면 첫 발동이다");
	}

	@Test
	void 받은_틱이_다르면_같은_주기의_카드_둘이_다른_틱에_터진다() {
		long other = GRANTED + 50L;
		int together = 0;
		for (long now = GRANTED; now < GRANTED + INTERVAL * 20L; now++) {
			if (TrialRisks.firesAt(now, GRANTED, INTERVAL)
					&& TrialRisks.firesAt(now, other, INTERVAL)) {
				together++;
			}
		}
		assertEquals(0, together,
				"월드 시간으로 세면 주기가 같은 카드 둘은 영원히 함께 터진다. 그러면 두 장이 한 장이다");
	}

	@Test
	void 복원_직후_now_가_받은_틱보다_작아도_음수_위상이_나오지_않는다() {
		for (long now = GRANTED - 500L; now <= GRANTED; now++) {
			assertEquals(0L, TrialRisks.elapsedSinceGrant(now, GRANTED));
			assertTrue(TrialRisks.remainingTicks(now, GRANTED, INTERVAL) >= 0,
					"남은 틱이 음수면 경고 층이 뒤집히고 예고 없이 터진다: " + now);
			assertTrue(TrialRisks.strikeIndex(now, GRANTED, INTERVAL) >= 0L);
			assertFalse(TrialRisks.firesAt(now, GRANTED, INTERVAL),
					"세션은 저장 파일에서 오고 게임 시각은 월드에서 온다. 어긋났다고 터지면 안 된다");
		}
	}

	@Test
	void 남은_틱은_주기_안에서_한_칸씩만_줄어든다() {
		int previous = TrialRisks.remainingTicks(GRANTED + 1L, GRANTED, INTERVAL);
		assertEquals(INTERVAL - 1, previous, "주기가 막 시작됐다");
		for (long now = GRANTED + 2L; now <= GRANTED + INTERVAL; now++) {
			int remaining = TrialRisks.remainingTicks(now, GRANTED, INTERVAL);
			assertEquals(previous - 1, remaining, "예고가 건너뛰면 그 층의 소리가 안 울린다: " + now);
			previous = remaining;
		}
		assertEquals(0, previous, "발동하는 틱에 0 이어야 한다");
	}

	@Test
	void 주기_번호는_발동하는_틱까지_유지된다() {
		// 예고 중에 뽑아 둔 대상을 발동 순간에 잃으면 표식과 실제 폭발이 어긋난다.
		long first = TrialRisks.strikeIndex(GRANTED + 1L, GRANTED, INTERVAL);
		assertEquals(first, TrialRisks.strikeIndex(GRANTED + INTERVAL, GRANTED, INTERVAL),
				"터지는 그 틱에 번호가 넘어가면 뽑아 둔 대상이 버려진다");
		assertNotEquals(first, TrialRisks.strikeIndex(GRANTED + INTERVAL + 1L, GRANTED, INTERVAL),
				"터진 다음 틱부터는 새 주기다 — 여기서 대상을 다시 뽑는다");
	}

	@Test
	void 주기가_0_이하면_아무_일도_없다() {
		assertFalse(TrialRisks.firesAt(GRANTED + 10_000L, GRANTED, 0),
				"0 으로 나누는 대신 그냥 돌지 않는다");
		assertFalse(TrialRisks.firesAt(GRANTED + 10_000L, GRANTED, -5));
		assertNull(TrialWarning.stageFor(TrialRisks.remainingTicks(GRANTED, GRANTED, 0)),
				"경고 층도 뜨지 않아야 한다 — 터지지도 않는데 예고만 나오면 신호가 거짓말이 된다");
	}

	// ------------------------------------------------------------------ 경고 층

	@Test
	void 층이_바뀌는_틱에만_소리를_낸다() {
		int changes = 0;
		for (int remaining = INTERVAL - 1; remaining >= 0; remaining--) {
			if (TrialRisks.stageJustChanged(remaining)) {
				changes++;
			}
		}
		assertEquals(3, changes, "한 주기에 세 층. 더 울리면 신호가 흔해지고 덜 울리면 층을 놓친다");
	}

	@Test
	void 세_층의_문턱에서만_바뀐다() {
		assertTrue(TrialRisks.stageJustChanged(100), "첫 층 — 뭔가 온다");
		assertTrue(TrialRisks.stageJustChanged(50), "둘째 층 — 여기로 온다");
		assertTrue(TrialRisks.stageJustChanged(14), "셋째 층 — 지금 나가라");
		assertFalse(TrialRisks.stageJustChanged(101), "아직 아무 층도 아니다");
		assertFalse(TrialRisks.stageJustChanged(99));
		assertFalse(TrialRisks.stageJustChanged(0), "발동 틱에 한 번 더 울리면 층이 넷이 된다");
	}

	// ------------------------------------------------------------------ 대상 뽑기

	@Test
	void count_가_팀_인원보다_크면_인원수만큼만_뽑는다() {
		assertEquals(4, TrialRisks.targetCount(10, 4), "없는 다섯째 팀원을 뽑으려다 터지면 안 된다");
		assertEquals(4, TrialRisks.pickIndexes(10, 4, RandomSource.create(7L)).size());
	}

	@Test
	void count_가_0_이하면_아무도_뽑지_않는다() {
		assertEquals(0, TrialRisks.targetCount(0, 4));
		assertEquals(0, TrialRisks.targetCount(-3, 4));
		assertTrue(TrialRisks.pickIndexes(0, 4, RandomSource.create(7L)).isEmpty());
		assertTrue(TrialRisks.pickIndexes(-3, 4, RandomSource.create(7L)).isEmpty());
	}

	@Test
	void 아무도_없으면_뽑을_것도_없다() {
		assertEquals(0, TrialRisks.targetCount(2, 0), "팀원이 전부 접속을 끊었을 수 있다");
		assertTrue(TrialRisks.pickIndexes(2, 0, RandomSource.create(7L)).isEmpty());
	}

	@Test
	void 같은_사람을_두_번_뽑지_않는다() {
		RandomSource random = RandomSource.create(1234L);
		for (int round = 0; round < 200; round++) {
			List<Integer> picked = TrialRisks.pickIndexes(3, 4, random);
			assertEquals(3, picked.size());
			assertEquals(3, new HashSet<>(picked).size(),
					"같은 사람이 두 번 들어오면 그 주기가 세 발에서 두 발로 줄어든다");
			for (int index : picked) {
				assertTrue(index >= 0 && index < 4, "없는 자리를 가리키면 그 자리에서 터진다");
			}
		}
	}

	@Test
	void 오래_돌리면_모두_한_번씩은_노려진다() {
		RandomSource random = RandomSource.create(99L);
		Set<Integer> seen = new HashSet<>();
		for (int round = 0; round < 200; round++) {
			seen.addAll(TrialRisks.pickIndexes(1, 4, random));
		}
		assertEquals(4, seen.size(), "한 사람만 계속 노리면 그 사람만 게임을 하게 된다");
	}

	// ------------------------------------------------------------------ 아레나 좌표

	@Test
	void 고른_자리는_아레나_밖으로_나가지_않는다() {
		double radius = 40.0;
		RandomSource random = RandomSource.create(5150L);
		for (int round = 0; round < 500; round++) {
			Vec3 spot = TrialRisks.arenaOffset(random.nextDouble(), random.nextDouble(), radius);
			assertTrue(spot.length() <= radius + 1.0E-9,
					"엔드 중앙 섬 바깥은 허공이다. 거기서 터지면 예고도 피해도 뜻이 없다");
			assertEquals(0.0, spot.y, "높이는 지면에서 따로 찾는다");
		}
	}

	@Test
	void 굴림이_범위를_벗어나도_아레나_안이다() {
		assertTrue(TrialRisks.arenaOffset(-5.0, 9.0, 40.0).length() <= 40.0 + 1.0E-9);
		assertEquals(0.0, TrialRisks.arenaOffset(0.5, -1.0, 40.0).length(), 1.0E-9,
				"거리 굴림이 0 이하면 중앙이다");
		assertEquals(40.0, TrialRisks.arenaOffset(0.0, 1.0, 40.0).length(), 1.0E-9,
				"거리 굴림이 1 이면 가장자리다");
	}

	@Test
	void 거리는_제곱근으로_펴서_중앙에_몰리지_않게_한다() {
		double radius = 40.0;
		// 굴림이 절반이면 넓이도 절반이어야 한다 — 반경의 절반이 아니라 √½ 배다.
		assertEquals(radius * Math.sqrt(0.5), TrialRisks.arenaOffset(0.0, 0.5, radius).length(),
				1.0E-9, "그냥 곱하면 지점이 중앙에 몰려 가장자리가 안전지대가 된다");
	}

	// ------------------------------------------------------------------ 띄우기

	@Test
	void 높이가_클수록_세게_띄우고_오래_봐_준다() {
		assertTrue(TrialRisks.launchVelocity(9.0) > TrialRisks.launchVelocity(4.0));
		assertTrue(TrialRisks.fallGraceTicks(9.0) > TrialRisks.fallGraceTicks(4.0));
	}

	@Test
	void 네_블록을_띄우면_이_초쯤_봐_준다() {
		// 올라갔다 내려오는 시간에 여유를 더한 값이다. 짧으면 착지 직전에 면제가 끊긴다.
		int grace = TrialRisks.fallGraceTicks(4.0);
		assertTrue(grace >= 40 && grace <= 60, "실제 값: " + grace);
	}

	@Test
	void 띄우지_않는_카드는_면제도_없다() {
		assertEquals(0.0, TrialRisks.launchVelocity(0.0));
		assertEquals(0, TrialRisks.fallGraceTicks(0.0),
				"「낙뢰」는 띄우지 않는다. 여기서 면제가 붙으면 스스로 뛰어내린 낙사까지 사라진다");
		assertEquals(0, TrialRisks.fallGraceTicks(-3.0));
	}

	// ------------------------------------------------------------------ 값으로 돈다

	@Test
	void 카드_두_장이_같은_타입을_서로_다른_값으로_쓴다() {
		TrialCatalog.Risk.DelayedStrike trail = onlyStrike("sharedfate:ground_strike");
		TrialCatalog.Risk.DelayedStrike spots = onlyStrike("sharedfate:lightning_storm");

		assertEquals(TrialCatalog.Risk.Aim.TRAIL, trail.aim());
		assertEquals(TrialCatalog.Risk.Aim.RANDOM_SPOT, spots.aim());
		assertNotEquals(trail.interval(), spots.interval(),
				"두 장째 카드를 만들 수 없었던 것이 이 실행기를 뜯은 이유다. 값이 같아지면 뜯은 뜻이 없다");
		assertTrue(trail.launch() > 0.0, "「자리 폭격」은 띄운다");
		assertEquals(0.0, spots.launch(), "「낙뢰」는 띄우지 않는다");
	}

	// ------------------------------------------------------------------ 연출

	@Test
	void 낙뢰_카드는_진짜_번개다() {
		// 사람이 플레이하다 발견한 결함을 그대로 못박는다. 연출이 값으로 갈리지 않아 이 카드가
		// 번개 대신 폭발 파티클과 폭발음으로 나갔고, TNT 가 터지는 것으로 보였다.
		assertEquals(TrialCatalog.Risk.Impact.LIGHTNING,
				onlyStrike("sharedfate:lightning_storm").impact(),
				"카드 이름이 「낙뢰」인데 폭발이 나가면 이름이 거짓말이 된다");
	}

	@Test
	void 자리_폭격_카드는_폭발이다() {
		assertEquals(TrialCatalog.Risk.Impact.EXPLOSION,
				onlyStrike("sharedfate:ground_strike").impact(),
				"「자리 폭격」은 터지는 것이 맞다. 낙뢰를 고치면서 이쪽까지 번개로 바꾸면 안 된다");
	}

	@Test
	void 같은_타입의_두_카드가_연출이_서로_다르다() {
		// 값으로 카드를 만드는 구조가 실제로 도는지 보는 시험이다. 둘이 같아지는 순간
		// 「연출을 값으로 갈랐다」는 말이 껍데기만 남는다.
		assertNotEquals(onlyStrike("sharedfate:ground_strike").impact(),
				onlyStrike("sharedfate:lightning_storm").impact(),
				"같은 DelayedStrike 인데 하나는 터지고 하나는 내리쳐야 한다");
	}

	// ------------------------------------------------------------------ 26.3 번개 API

	/**
	 * 연출 전용 설정이 26.3 에 실제로 있다.
	 *
	 * <p>이름이 사라지면 우리 코드가 컴파일에서 먼저 걸리지만, <b>시그니처가 조용히 바뀌는</b> 쪽은
	 * 그렇지 않다. 판올림 때 여기서 먼저 멈추게 해 둔다.
	 */
	@Test
	void 번개_엔티티와_연출_전용_설정이_26_3_에_있다() {
		Method setVisualOnly = declared(LightningBolt.class, "setVisualOnly", boolean.class);
		assertEquals(void.class, setVisualOnly.getReturnType());
		assertFalse(Modifier.isStatic(setVisualOnly.getModifiers()),
				"정적이 되면 우리가 부르는 자리가 통째로 달라진다");
		assertTrue(Entity.class.isAssignableFrom(LightningBolt.class),
				"엔티티가 아니면 addFreshEntity 로 띄울 수 없다");
	}

	/**
	 * 그 설정이 실제로 끄는 두 가지가 그대로 있다.
	 *
	 * <p>{@code visualOnly} 가 참이면 26.3 의 {@code LightningBolt} 는 피해 구간을 건너뛰고
	 * {@code spawnFire} 가 첫 줄에서 되돌아간다. 두 이름 중 하나라도 사라지면 <b>그 보장을 손으로
	 * 다시 확인해야 한다</b> — 피해가 두 배가 되거나 엔드에 불이 붙는데 로그도 빌드도 조용하다.
	 */
	@Test
	void 연출_전용_번개는_피해도_불도_내지_않는다() {
		assertEquals(boolean.class, declaredField(LightningBolt.class, "visualOnly").getType(),
				"이 깃발이 피해 구간과 spawnFire 를 함께 끈다");
		assertEquals(void.class, declared(LightningBolt.class, "spawnFire", int.class)
				.getReturnType(), "불을 지르는 자리. visualOnly 가 참이면 첫 줄에서 되돌아간다");
		// 피해를 넣는 길도 그대로여야 한다. 이름이 바뀌면 visualOnly 가 무엇을 막는지 다시 봐야 한다.
		assertEquals(void.class,
				declared(Entity.class, "thunderHit", ServerLevel.class, LightningBolt.class)
						.getReturnType());
	}

	/**
	 * 우리가 번개를 띄우는 길이 반드시 연출 전용 설정을 지난다.
	 *
	 * <p>한 줄만 빠지면 <b>카드에 적힌 값의 두 배</b>가 들어가는데 빌드도 로그도 아무 말을 안 한다.
	 * 돌려 보고 발견하려면 누가 맞아 봐야 하고, 이 게임은 전멸하면 월드가 지워진다.
	 *
	 * <p>컴파일된 클래스 파일에서 이름을 찾는다. 상수 풀에 그 이름이 없다는 것은 이 클래스 어디서도
	 * 그 메서드를 부르지 않는다는 뜻이다 — {@code TrialRisks} 에서 번개를 만드는 곳은 한 군데뿐이라
	 * 이만큼이면 「그 길을 지난다」가 증명된다.
	 */
	@Test
	void 번개를_소환하는_길은_반드시_연출_전용을_지난다() {
		byte[] compiled = classBytes(TrialRisks.class);
		assertTrue(references(compiled, "setVisualOnly"),
				"소환한 번개가 자기 피해까지 주면 카드에 적힌 값이 두 배가 된다");
		assertTrue(references(compiled, "LIGHTNING_BOLT"),
				"번개를 파티클로 흉내 내면 하늘 섬광도 천둥도 나오지 않는다");
	}

	// ------------------------------------------------------------------ 카드에 적힌 무게

	@Test
	void 자리_폭격은_한_대로_팀을_죽이지_않는다() {
		float damage = onlyStrike("sharedfate:ground_strike").damage();
		float teamHealth = PerkHealthRules.effectiveMaxHealth(null);
		assertEquals(18.0F, damage, "약해서 맞아도 상관없던 값을 세 배로 올렸다");
		assertTrue(damage < teamHealth,
				"팀 공유 체력이 " + teamHealth + " 다. 여기를 넘기면 그 순간 즉사 카드가 된다 —"
						+ " 이 판의 원칙은 「즉사 메커닉 0개」이고, 전멸하면 월드가 지워진다");
	}

	@Test
	void 카드에_적힌_주기는_예고_세_층보다_길다() {
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				switch (risk) {
					case TrialCatalog.Risk.DelayedStrike strike -> assertTrue(
							strike.interval() > TrialWarning.TICKS_SCATTER,
							trial.name() + " — 주기가 예고보다 짧으면 경고가 통째로 잘린다");
					case TrialCatalog.Risk.TracedProjectile shot -> assertTrue(
							shot.traceTicks() >= TrialWarning.TICKS_SCATTER,
							trial.name() + " — 궤적이 흩어질 시간보다 짧으면 피할 수 없다");
					// 아래 셋은 터지는 순간이 없다. 상태를 걸거나 판을 바꾼다.
					case TrialCatalog.Risk.CrystalGuard ignored -> {
					}
					case TrialCatalog.Risk.DragonFocus focus -> assertTrue(
							focus.retargetTicks() >= 0,
							trial.name() + " — 재지정 간격이 음수면 뜻이 없다");
					case TrialCatalog.Risk.CrystalRevive revive -> assertTrue(
							revive.showTicks() >= 0,
							trial.name() + " — 연출 길이가 음수면 뜻이 없다");
				}
			}
		}
	}

	// ------------------------------------------------------------------ 표식과 판정

	@Test
	void 고리_밖_모서리는_맞지_않는다() {
		Vec3 center = new Vec3(0.0, 64.0, 0.0);
		// AABB.inflate(2) 는 정육면체라 여기까지 걸린다. 표식은 반경 2 짜리 원이므로 밖이다.
		assertFalse(TrialRisks.insideMark(new Vec3(1.9, 64.0, 1.9), center, 2.0),
				"표식 밖에 서 있는데 맞으면 「같은 표식은 같은 결과」가 깨진다");
		assertTrue(TrialRisks.insideMark(new Vec3(1.9, 64.0, 0.0), center, 2.0));
		assertTrue(TrialRisks.insideMark(center, center, 2.0));
	}

	@Test
	void 고리_위에_떠_있어도_맞는다() {
		Vec3 center = new Vec3(0.0, 64.0, 0.0);
		// 띄우는 카드와 겹치면 곧바로 드러난다. 바닥 표식은 세로를 묻지 않는다.
		assertTrue(TrialRisks.insideMark(new Vec3(0.5, 72.0, 0.5), center, 2.0));
	}

	@Test
	void 반경_경계에_선_사람은_맞는다() {
		Vec3 center = new Vec3(0.0, 64.0, 0.0);
		assertTrue(TrialRisks.insideMark(new Vec3(2.0, 64.0, 0.0), center, 2.0));
		assertFalse(TrialRisks.insideMark(new Vec3(2.01, 64.0, 0.0), center, 2.0));
	}

	// ------------------------------------------------------------------ 도우미

	private static Method declared(Class<?> owner, String name, Class<?>... parameters) {
		try {
			return owner.getDeclaredMethod(name, parameters);
		} catch (NoSuchMethodException missing) {
			throw new AssertionError(
					owner.getSimpleName() + "." + name + " 의 서술자가 바뀌었다."
							+ " 번개를 연출 전용으로 띄우는 길을 다시 확인할 것",
					missing);
		}
	}

	private static Field declaredField(Class<?> owner, String name) {
		try {
			return owner.getDeclaredField(name);
		} catch (NoSuchFieldException missing) {
			throw new AssertionError(
					owner.getSimpleName() + "." + name + " 가 없어졌다."
							+ " 연출 전용 번개가 무엇을 끄는지 다시 확인할 것",
					missing);
		}
	}

	/** 컴파일된 클래스 파일 그대로. 우리가 무엇을 부르는지는 소스가 아니라 여기에 남는다. */
	private static byte[] classBytes(Class<?> type) {
		String resource = type.getSimpleName() + ".class";
		try (InputStream stream = type.getResourceAsStream(resource)) {
			assertNotNull(stream, resource + " 를 클래스패스에서 찾지 못했다");
			return stream.readAllBytes();
		} catch (IOException broken) {
			throw new AssertionError(resource + " 를 읽지 못했다", broken);
		}
	}

	/**
	 * 클래스 파일이 이 이름을 상수 풀에 들고 있는가.
	 *
	 * <p>상수 풀의 이름은 ASCII 구간에서 그대로 바이트로 들어간다. 없다는 것은 이 클래스가 그것을
	 * 어디서도 부르지 않는다는 뜻이다 — 있다고 해서 어느 메서드에서 부르는지까지는 알 수 없지만,
	 * 그 이름을 쓰는 곳이 한 군데뿐이면 그만큼으로 충분하다.
	 */
	private static boolean references(byte[] compiled, String name) {
		byte[] needle = name.getBytes(StandardCharsets.US_ASCII);
		outer:
		for (int start = 0; start + needle.length <= compiled.length; start++) {
			for (int index = 0; index < needle.length; index++) {
				if (compiled[start + index] != needle[index]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

	private static TrialCatalog.Risk.DelayedStrike onlyStrike(String id) {
		TrialCatalog.Trial trial = TrialCatalog.byId(id);
		assertTrue(trial != null && trial.risks().size() == 1, id + " 카드가 없다");
		return switch (trial.risks().getFirst()) {
			case TrialCatalog.Risk.DelayedStrike strike -> strike;
			// 이 시험이 보는 것은 예고 타격뿐이다. 카드의 위험 타입이 바뀌면 조용히 지나가지 않고
			// 여기서 멈춰야 한다 — 시험이 무엇을 보는지 모르게 되는 것이 더 나쁘다.
			case TrialCatalog.Risk risk -> throw new AssertionError(
					id + " 카드의 위험이 예고 타격이 아니다: " + risk);
		};
	}
}
