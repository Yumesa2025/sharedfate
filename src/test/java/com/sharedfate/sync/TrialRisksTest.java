package com.sharedfate.sync;

import com.sharedfate.perk.PerkHealthRules;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

	/**
	 * 예고가 ≤50틱이어도 첫 층의 소리가 나간다.
	 *
	 * <p>{@link TrialWarning#stageFor} 는 남은 틱을 100 / 50 / 14 로 가른다. 30틱짜리 예고는
	 * <b>처음부터 MARK 구간 안에서 시작</b>하므로 「직전 틱의 층과 다른가」만 물으면 영영 거짓이고,
	 * IMMINENT 로 넘어갈 때까지 소리가 한 번도 안 나간다. 중앙 가까이 선 사람처럼 예고가 아예
	 * IMMINENT 구간에서 시작하면 <b>한 번도 못 듣는다.</b>
	 *
	 * <p>이 함정에 「종말의 비」와 「착지 충격」이 각자 따로 막는 줄을 적어 걸려 있었다. 예고가
	 * 짧은 카드가 나올 때마다 같은 줄을 다시 적어야 하는 모양이라 한가운데로 옮겼고, 그 자리를
	 * 여기서 못박는다.
	 */
	@Test
	void 예고가_짧아도_첫_층을_잃지_않는다() {
		assertTrue(TrialRisks.stageJustChanged(30, 30), "30틱 예고의 첫 틱 — MARK 를 여기서 알린다");
		assertTrue(TrialRisks.stageJustChanged(10, 10),
				"중앙에 선 사람은 처음부터 IMMINENT 다. 여기서 안 울리면 한 번도 못 듣는다");
		assertFalse(TrialRisks.stageJustChanged(29, 30), "첫 틱 다음은 층이 바뀔 때만이다");
		assertTrue(TrialRisks.stageJustChanged(14, 30), "짧은 예고 안에서도 층 경계는 그대로다");

		int rings = 0;
		for (int remaining = 30; remaining >= 0; remaining--) {
			if (TrialRisks.stageJustChanged(remaining, 30)) {
				rings++;
			}
		}
		assertEquals(2, rings, "30틱 예고에 들어 있는 층은 MARK 와 IMMINENT 둘뿐이다");

		// 예고가 길면 두 형태의 답이 같아야 한다 — 첫 층의 경계를 지나는 틱이 반드시 있다.
		for (int remaining = INTERVAL - 1; remaining >= 0; remaining--) {
			assertEquals(TrialRisks.stageJustChanged(remaining),
					TrialRisks.stageJustChanged(remaining, INTERVAL - 1),
					"예고가 긴 위험에서는 두 형태가 갈리면 안 된다: " + remaining);
		}
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

	/**
	 * 「자리 폭격」이 <b>무장한</b> 팀을 한 대로 죽이지 않는다.
	 *
	 * <h2>⚠ 적힌 값은 이제 팀 체력보다 크다 — 그것을 알고 고른 값이다</h2>
	 *
	 * <p>전에는 <b>적힌 18 이 팀 체력 20 보다 작다</b>는 것이 이 카드의 안전 근거였다. 지금은
	 * 35 라 그 근거가 없다 — 사람이 「다이아셋 + 보호 인챈트까지 하고 맞는 것까지 고려해야 한다」고
	 * 정했고, 완전무장을 지나면 6.93 이 들어온다.
	 *
	 * <p>그래서 <b>맨몸이면 한 대에 전멸</b>이다. 죽어서 장비를 잃고 돌아온 사람이 그 경우이고,
	 * 사람은 그 사실을 듣고도 「3대」로 가자고 했다. 이 시험은 그 갈림을 <b>숫자로 적어 두는
	 * 자리</b>다 — 값을 다시 만질 사람이 「즉사 메커닉 0개」를 날값으로 착각하지 않도록.
	 */
	@Test
	void 자리_폭격은_무장한_팀을_한_대로_죽이지_않는다() {
		float written = onlyStrike("sharedfate:ground_strike").damage();
		float teamHealth = PerkHealthRules.effectiveMaxHealth(null);
		assertEquals(35.0F, written,
				"맨몸 기준 18 이 무장하면 2.5 로 들어와 「안 아프다」였다. 역산은 34.18 이지만"
						+ " 내림하면 세 대에 19.83 으로 살아남아 「3대에 죽는다」가 거짓이 된다");
		assertTrue(GearedDamage.afterGear(written, GearedDamage.Source.LIGHTNING_BOLT) * 3
						> teamHealth,
				"세 대에 죽지 않으면 사람이 정한 「큰자리는 3대맞으면 죽는거로」가 거짓이다."
						+ " 34 로 내리면 19.83 이라 여기서 걸린다");
		float geared = GearedDamage.afterGear(written, GearedDamage.Source.LIGHTNING_BOLT);
		assertTrue(geared < teamHealth,
				"완전무장하고도 한 대에 " + geared + " 라 팀 체력 " + teamHealth + " 를 넘는다 —"
						+ " 이 판의 원칙은 「즉사 메커닉 0개」이고, 전멸하면 월드가 지워진다");
		assertTrue(written > teamHealth,
				"적힌 값이 팀 체력보다 작아졌다면 무장 기준이 아니라 날값으로 되돌아간 것이다 —"
						+ " GearedDamage 의 설명을 먼저 읽을 것");
	}

	@Test
	void 카드에_적힌_주기는_예고_세_층보다_길다() {
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				switch (risk) {
					case TrialCatalog.Risk.DelayedStrike strike -> assertTrue(
							strike.interval() > TrialWarning.TICKS_SCATTER,
							trial.name() + " — 주기가 예고보다 짧으면 경고가 통째로 잘린다");
					// 궤적 카드의 기준은 흩어지기가 아니라 옆걸음이다. 조준점이 발사 순간에
					// 얼어붙고 한 사람만 노리므로, 요구하는 행동이 「제자리에서 옆으로 비키기」
					// 하나다. 까닭은 TrialFireballTest 의 같은 시험에 길게 적어 두었다.
					case TrialCatalog.Risk.TracedProjectile shot -> assertTrue(
							shot.traceTicks() >= TrialWarning.TICKS_SIDESTEP,
							trial.name() + " — 궤적이 옆으로 비킬 시간보다 짧으면 피할 수 없다");
					// 아래는 터지는 순간이 없거나 예고가 주기와 다른 데서 나온다. 그래도 음수 값은
					// 어느 쪽이든 뜻이 없으므로 여기서 함께 붙잡는다.
					case TrialCatalog.Risk.CrystalGuard ignored -> {
					}
					case TrialCatalog.Risk.DragonFocus focus -> assertTrue(
							focus.markTicks() >= 0,
							trial.name() + " — 표적 시간이 음수면 뜻이 없다");
					case TrialCatalog.Risk.CrystalRevive revive -> assertTrue(
							revive.showTicks() >= 0,
							trial.name() + " — 연출 길이가 음수면 뜻이 없다");
					// 고리가 퍼지는 시간이 곧 예고다. 주기보다 길면 다음 고리가 앞 고리를 덮는다.
					case TrialCatalog.Risk.EnderPulse pulse -> assertTrue(
							pulse.travelTicks() > 0 && pulse.travelTicks() <= pulse.interval(),
							trial.name() + " — 퍼지는 시간이 주기를 넘으면 고리가 겹쳐 쌓인다");
					case TrialCatalog.Risk.CrystalLink link -> assertTrue(
							link.shieldTicks() >= 0,
							trial.name() + " — 보호막 시간이 음수면 뜻이 없다");
					// 도화선이 부수러 갈 시간이다. 옆걸음보다 짧으면 알아채기도 전에 끝난다.
					case TrialCatalog.Risk.CrystalOvercharge overcharge -> assertTrue(
							overcharge.fuseTicks() >= TrialWarning.TICKS_SIDESTEP,
							trial.name() + " — 도화선이 반응할 시간보다 짧으면 부술 수 없다");
					case TrialCatalog.Risk.EnderStorm storm -> assertTrue(
							storm.speedPerSecond() > 0.0 && storm.restTicks() >= 0,
							trial.name() + " — 멈춰 선 소용돌이는 영영 중앙에 닿지 않는다");
					case TrialCatalog.Risk.DryWorld ignored -> {
					}
					case TrialCatalog.Risk.NightHost host -> assertTrue(
							host.hostileTicks() >= 0,
							trial.name() + " — 적대 시간이 음수면 뜻이 없다");
					// 표시가 뜨고 착탄까지가 예고 전부다. 옆으로 비킬 시간보다 짧으면 못 피한다.
					case TrialCatalog.Risk.EndRain rain -> {
						assertTrue(rain.warnTicks() >= TrialWarning.TICKS_SIDESTEP,
								trial.name() + " — 예고가 옆으로 비킬 시간보다 짧으면 사후 통보다");
						assertTrue(rain.minInterval() <= rain.maxInterval()
										&& rain.minSpots() <= rain.maxSpots(),
								trial.name() + " — 아래위가 뒤집힌 범위는 굴릴 수 없다");
						assertTrue(rain.minInterval() > rain.warnTicks(),
								trial.name() + " — 다음 볼리가 앞 볼리의 예고 안에 들어오면"
										+ " 표시가 서로를 덮는다");
					}
					// 착지는 주기가 아니라 드래곤이 정한다. 퍼지는 시간이 곧 예고다.
					case TrialCatalog.Risk.LandingShock shock -> assertTrue(
							shock.travelTicks() > 0,
							trial.name() + " — 퍼지는 시간이 0 이면 예고 없이 동시에 맞는다");
					case TrialCatalog.Risk.HotbarLock lock -> assertTrue(
							lock.interval() > 0 && lock.slots() > 0,
							trial.name() + " — 주기나 칸 수가 0 이하면 아무 일도 없는 카드다");
				}
			}
		}
	}

	// ------------------------------------------------------------------ 겹치면 즉사다

	/**
	 * 최소 간격은 반경의 두 배다.
	 *
	 * <p>두 원이 한 점도 공유하지 않으려면 중심 거리가 반경의 합보다 커야 한다. 접점 하나만
	 * 남겨도 {@link TrialRisks#insideMark} 가 경계를 「안」으로 보므로 거기 선 사람은 두 발을 다
	 * 맞는다 — 그래서 <b>정확히 두 배인 것도 겹침</b>이다.
	 */
	@Test
	void 최소_간격은_반경의_두_배다() {
		assertEquals(6.0, TrialRisks.spotMinGap(3.0), 1.0E-9);
		assertEquals(0.0, TrialRisks.spotMinGap(0.0));
		assertEquals(0.0, TrialRisks.spotMinGap(-4.0), "반경이 없으면 지킬 간격도 없다");

		TrialRisks.LiveSpot origin = live(0.0, 64.0, 0.0, 3.0);
		assertFalse(TrialRisks.clearOfTaken(List.of(origin), new Vec3(6.0, 70.0, 0.0), 3.0),
				"정확히 두 배면 두 고리가 한 점에서 닿는다. 그 점에 선 사람은 두 번 맞는다");
		assertTrue(TrialRisks.clearOfTaken(List.of(origin), new Vec3(6.01, 70.0, 0.0), 3.0));
		assertTrue(TrialRisks.clearOfTaken(List.of(), new Vec3(0.0, 64.0, 0.0), 3.0),
				"첫 지점은 비교할 상대가 없다");
	}

	/**
	 * 크기가 다른 고리끼리는 <b>두 반경의 합</b>으로 잰다.
	 *
	 * <p>「낙뢰」는 반경 3, 「종말의 비」는 2.5 다. 한쪽의 두 배로 재면 작은 쪽 기준일 때 겹친
	 * 것을 통과시킨다 — 그 자리는 18 + 10 = 28 이라 팀 체력 20 을 한 틱에 넘긴다.
	 */
	@Test
	void 반경이_다른_고리는_두_반경의_합으로_잰다() {
		assertEquals(5.5, TrialRisks.spotMinGap(3.0, 2.5), 1.0E-9);

		TrialRisks.LiveSpot lightning = live(0.0, 64.0, 0.0, 3.0);
		assertFalse(TrialRisks.clearOfTaken(List.of(lightning), new Vec3(5.4, 64.0, 0.0), 2.5),
				"5.4 칸이면 반경 3 과 2.5 짜리 고리가 겹친다 — 그 자리는 한 틱에 두 발이다");
		assertTrue(TrialRisks.clearOfTaken(List.of(lightning), new Vec3(5.51, 64.0, 0.0), 2.5));
		// 작은 쪽의 두 배(5.0)로만 쟀다면 5.4 를 통과시켰을 것이다.
		assertTrue(TrialRisks.spotMinGap(3.0, 2.5) > TrialRisks.spotMinGap(2.5),
				"작은 쪽 기준으로 재면 큰 고리가 남의 자리를 덮는다");
	}

	@Test
	void 간격_검사는_높이를_묻지_않는다() {
		// 고리는 바닥에 그려지고 insideMark 도 세로를 보지 않는다. y 가 달라도 위에서 보면 겹친다.
		assertFalse(TrialRisks.clearOfTaken(List.of(live(0.0, 0.0, 0.0, 3.0)),
				new Vec3(1.0, 120.0, 1.0), 3.0));
	}

	/**
	 * 무작위 지점 둘이 겹치지 않는다.
	 *
	 * <p>{@code groundSpot} 은 {@code ServerLevel} 이 있어야 해서 여기서 부를 수 없다. 대신 그것이
	 * 쓰는 순수 함수 둘({@link TrialRisks#arenaOffset}·{@link TrialRisks#clearOfTaken})을 같은
	 * 순서로, 같은 아레나 반경과 같은 재굴림 횟수로 돌린다.
	 *
	 * <p><b>이 시험이 지키는 것은 「즉사 메커닉 0개」다.</b> 피해 12 짜리 고리 둘이 겹치면 24 라
	 * 팀 체력 20 을 한 틱에 넘긴다.
	 */
	@Test
	void 무작위_지점_둘이_겹치지_않는다() {
		TrialCatalog.Risk.DelayedStrike card = onlyStrike("sharedfate:lightning_storm");
		RandomSource random = RandomSource.create(20260929L);
		int rounds = 3000;
		int placed = 0;
		for (int round = 0; round < rounds; round++) {
			List<Vec3> spots = roll(random, card.count(), card.radius());
			placed += spots.size();
			for (int first = 0; first < spots.size(); first++) {
				for (int second = first + 1; second < spots.size(); second++) {
					double gap = flatDistance(spots.get(first), spots.get(second));
					assertTrue(gap > TrialRisks.spotMinGap(card.radius()),
							"두 지점이 " + gap + " 칸이다. 반경 " + card.radius()
									+ " 짜리 고리 둘이 겹치면 그 안에 선 사람이 "
									+ (card.damage() * 2) + " 를 한 틱에 받는다");
				}
			}
		}
		// 규칙이 지점을 통째로 잡아먹으면 카드가 조용히 약해진다. 실제로는 5백만 판에 8번
		// 포기했으므로 여기서는 사실상 전부 선다.
		int wanted = rounds * card.count();
		assertTrue(placed >= wanted * 0.99,
				"열 곳 중 " + ((double) placed / rounds) + " 곳만 섰다 — 간격 규칙이 너무 빡빡하다");
	}

	/**
	 * 규칙을 빼면 실제로 겹친다.
	 *
	 * <p>위 시험만 있으면 「원래 안 겹치는 것 아닌가」로 읽힐 수 있다. 같은 굴림을 규칙 없이
	 * 돌려 보면 <b>열 곳일 때 판의 60% 남짓에서 겹침 구역이 생긴다.</b> 규칙이 실제로 무언가를
	 * 막고 있다는 근거를 여기 남긴다.
	 */
	@Test
	void 규칙이_없으면_열_곳_중_겹치는_쌍이_실제로_나온다() {
		TrialCatalog.Risk.DelayedStrike card = onlyStrike("sharedfate:lightning_storm");
		RandomSource random = RandomSource.create(4242L);
		int rounds = 3000;
		int overlapping = 0;
		for (int round = 0; round < rounds; round++) {
			List<Vec3> spots = new ArrayList<>();
			for (int index = 0; index < card.count(); index++) {
				spots.add(TrialRisks.arenaOffset(random.nextDouble(), random.nextDouble(),
						TrialRisks.ARENA_RADIUS));
			}
			if (anyPairTooClose(spots, card.radius())) {
				overlapping++;
			}
		}
		double rate = (double) overlapping / rounds;
		assertTrue(rate > 0.5,
				"겹침이 드물다면 이 규칙을 지울 이유가 생긴다. 실제 비율: " + rate);
	}

	/**
	 * 이 위험이 한 사람에게 <b>한 틱</b>에 어떻게 들어오는가 — 안전 시험이 보는 세 값.
	 *
	 * <p>{@link TrialRisks#worstCaseTickDamage} 는 이 셋을 이미 곱해 버린 뒤라
	 * <b>감쇠를 걸 수 없다.</b> 방어 감쇠는 한 방마다 따로 걸리므로
	 * ({@link GearedDamage} 의 「감쇠는 한 방마다 걸린다」) 곱하기 전의 한 방이 필요하다.
	 *
	 * @param perHit      한 방의 적힌 값
	 * @param overlapping 한 틱에 겹칠 수 있는 방 수
	 * @param source      실행기가 쓰는 피해원
	 */
	private record TickShape(float perHit, int overlapping, GearedDamage.Source source) {
	}

	/**
	 * 위험 하나를 {@link TickShape} 로 푼다.
	 *
	 * <p>{@code default} 를 넣지 말 것. 피해를 주는 타입을 새로 만들면서 여기에 「한 방이 얼마이고
	 * 몇 개가 겹치며 어느 피해원인가」를 적지 않으면 <b>빌드가 깨져야 한다</b> —
	 * {@link TrialRisks#worstCaseTickDamage} 가 {@code default} 를 두지 않는 것과 같은 이유이고,
	 * 아래 {@code 시험이_보는_모양이_실행기의_셈과_같다} 가 그 둘이 어긋나지 않게 묶는다.
	 *
	 * <p>피해원은 <b>카드가 아니라 실행기가 고른다.</b> 실행기에서 {@code damageSources()} 를
	 * 바꾸면 여기도 함께 바꿀 것 — 어긋나면 시험은 계속 통과하면서 실제 피해만 달라진다.
	 */
	private static TickShape shapeOf(TrialCatalog.Risk risk) {
		return switch (risk) {
			// TrialRisks.detonate 는 연출이 폭발이든 번개든 damageSources().lightningBolt() 를
			// 쓴다 — Impact 는 보이는 것만 가르고 피해원은 가르지 않는다.
			case TrialCatalog.Risk.DelayedStrike strike -> new TickShape(strike.damage(),
					switch (strike.aim()) {
						case RANDOM_SPOT -> 1;
						case TRAIL -> strike.count();
					}, GearedDamage.Source.LIGHTNING_BOLT);
			// TrialFireball — explosion(null, null)
			case TrialCatalog.Risk.TracedProjectile shot -> new TickShape(shot.damage(),
					shot.count(), GearedDamage.Source.EXPLOSION);
			// TrialDragonFocus — magic(). #bypasses_armor 라 방어도가 안 듣는다.
			case TrialCatalog.Risk.DragonFocus focus -> new TickShape(focus.damage(), 1,
					GearedDamage.Source.MAGIC);
			// TrialEndRain — explosion(null, null). ⚠ 겹침 금지 목록 밖이라 겹침이 1 이 아니다.
			case TrialCatalog.Risk.EndRain rain -> new TickShape(rain.damage(),
					TrialEndRain.WORST_CASE_OVERLAP, GearedDamage.Source.EXPLOSION);
			// TrialLandingShock — explosion(null, null)
			case TrialCatalog.Risk.LandingShock shock -> new TickShape(shock.damage(), 1,
					GearedDamage.Source.EXPLOSION);
			// TrialEnderStorm — explosion(null, null)
			case TrialCatalog.Risk.EnderStorm storm -> new TickShape(storm.damage(), storm.count(),
					GearedDamage.Source.EXPLOSION);
			// TrialCrystalOvercharge — explosion(null, null). 구체가 초에 한 발 날고, 한 틱에 닿는
			// 것은 그중 한 발뿐이다(비행 시간이 발 간격보다 짧다). 적힌 damagePerSecond 가 곧
			// 발당 피해라 빔이던 때와 같은 곱이다.
			case TrialCatalog.Risk.CrystalOvercharge overcharge ->
					new TickShape(overcharge.damagePerSecond(), 1, GearedDamage.Source.EXPLOSION);
			// 아래는 우리가 적은 피해가 없다. 피해원도 없으므로 한 방이 0 이다.
			case TrialCatalog.Risk.CrystalGuard ignored -> none();
			case TrialCatalog.Risk.CrystalRevive ignored -> none();
			case TrialCatalog.Risk.EnderPulse ignored -> none();
			case TrialCatalog.Risk.CrystalLink ignored -> none();
			case TrialCatalog.Risk.DryWorld ignored -> none();
			// 엔더맨은 바닐라 값으로 때린다. 우리 피해원이 아니라 여기서 셀 것이 없다.
			case TrialCatalog.Risk.NightHost ignored -> none();
			case TrialCatalog.Risk.HotbarLock ignored -> none();
		};
	}

	private static TickShape none() {
		return new TickShape(0.0F, 0, GearedDamage.Source.EXPLOSION);
	}

	/**
	 * <b>시험이 보는 모양이 실행기의 셈과 같다.</b>
	 *
	 * <p>{@link #shapeOf} 는 {@link TrialRisks#worstCaseTickDamage} 의 「피해 × 겹칠 수 있는 개수」를
	 * 한 번 더 적은 것이다. 두 벌이 갈라지면 안전 시험이 <b>실행기가 세는 것과 다른 것</b>을 보게
	 * 되므로, 곱이 같은지를 먼저 묻는다.
	 */
	@Test
	void 시험이_보는_모양이_실행기의_셈과_같다() {
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				TickShape shape = shapeOf(risk);
				assertEquals(TrialRisks.worstCaseTickDamage(risk),
						shape.perHit() * shape.overlapping(), 1.0E-4F,
						trial.name() + " — 시험이 보는 「한 방 × 겹침」이 worstCaseTickDamage 와"
								+ " 다르다. 한쪽만 고치면 안전 시험이 헛것을 본다");
			}
		}
	}

	/**
	 * 한 사람이 두 번 맞아 즉사하는 조합이 없다 — <b>완전무장 기준이다.</b>
	 *
	 * <h2>⚠ 날값 비교로 되돌리지 말 것</h2>
	 *
	 * <p>전에는 카드에 <b>적힌 값</b>을 그대로 20 과 견줬다. 그런데 시련은 엔드까지 온 팀이 받는
	 * <b>추가 난이도</b>이고, 사람이 「다이아셋 + 보호 인챈트까지 하고 맞는 것까지 고려해야 한다」고
	 * 정했다. 날값으로 재면 실제로는 1/8 만 들어오는 값을 천장으로 삼게 되고, 그것이 사람이
	 * 「안 아프다」고 한 이유였다. 지금 카드의 큰 값들(35 · 23)은 <b>날값으로는 20 을 넘는다</b> —
	 * 이 시험을 되돌리면 그 값들이 전부 한 자리수로 끌려 내려간다.
	 *
	 * <p>감쇠는 {@link GearedDamage} 가 한 벌 들고 있고, 하드 난이도 곱도 거기서 함께 태운다.
	 * <b>곱도 실제 피해의 일부</b>이고 피해 종류마다 걸리는지가 다르다.
	 *
	 * <p>감쇠를 <b>한 방마다</b> 거는 것이 중요하다. 겹친 고리 둘은 {@code 감쇠(35 × 2)} 가 아니라
	 * {@code 감쇠(35) × 2} 다. 그래도 <b>겹침 금지 규칙은 그대로 둔다</b> — 무장이 약한 사람에게는
	 * 겹친 자리가 여전히 즉사이고, 이 시험이 보는 것은 「무장한 사람도 죽는가」뿐이다.
	 *
	 * <p>{@code RANDOM_SPOT} 이 1 로 세어지는 근거는 최소 간격 규칙이다. 그 규칙을 지우면 이
	 * 시험은 계속 통과하면서 게임만 즉사가 된다 — 그래서 위의 겹침 시험과 한 쌍이다.
	 *
	 * <h2>⚠⚠ 예외가 하나 있다 — <b>「종말의 비」</b></h2>
	 *
	 * <p>사람이 지점을 90곳으로 올리라고 했는데 겹침 금지 목록을 지나면 84곳밖에 안 서고 같이
	 * 걸린 「낙뢰」가 반토막 나서, <b>「서로 겹쳐도 되니까 내가 말한 숫자로 해 줘」</b>라고
	 * 정했다. 고리 셋이 겹친 자리는 무장하고도 20.31 이라 <b>이 판의 「즉사 메커닉 0개」를 깬
	 * 첫 카드</b>다.
	 *
	 * <p>그래서 이 시험은 「전부 통과」가 아니라 <b>「넘는 것이 정확히 그 하나인가」</b>를 묻는다.
	 * {@code assertTrue} 를 카드마다 거는 모양으로 되돌리면 <b>예외가 하나 있다는 사실이
	 * 사라지고</b>, 다음 카드가 슬쩍 따라 나가도 아무도 모른다.
	 */
	@Test
	void 무장_기준으로_죽는_조합은_종말의_비_하나뿐이다() {
		float teamHealth = PerkHealthRules.effectiveMaxHealth(null);
		assertEquals(GearedDamage.TEAM_HEALTH, teamHealth,
				"팀 공유 체력이 바뀌었다면 GearedDamage 와 아래 판단을 전부 다시 볼 것");
		Set<String> over = new TreeSet<>();
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			for (TrialCatalog.Risk risk : trial.risks()) {
				TickShape shape = shapeOf(risk);
				float worst = GearedDamage.afterGear(shape.perHit(), shape.source())
						* shape.overlapping();
				if (worst >= teamHealth) {
					over.add(trial.id() + "(" + worst + ")");
				}
			}
		}
		assertEquals(1, over.size(),
				"한 틱에 팀 체력 " + teamHealth + " 을 넘기는 카드가 " + over + " 다."
						+ " 「즉사 메커닉 0개」의 예외는 「종말의 비」 하나뿐이고, 그것은 사람이"
						+ " 대가를 알고 고른 자리다 — 새 카드가 슬쩍 따라 나오면 여기서 멈춘다");
		assertTrue(over.iterator().next().startsWith("sharedfate:end_rain"),
				"넘긴 것이 「종말의 비」가 아니다: " + over);
	}

	/**
	 * <b>「큰자리는 3대 맞으면 죽는거로 생각하자」가 값에 들어가 있다.</b>
	 *
	 * <p>사람이 정한 기준이다. 위 시험이 「죽지 않는가」의 천장이라면 이쪽은 <b>바닥</b>이다 —
	 * 큰 카드가 너무 안 아프면 시련이 시련이 아니고, 그것이 값을 올리게 된 이유다.
	 *
	 * <h2>「6.67 에 얼마나 가까운가」로 묻지 않는다</h2>
	 *
	 * <p>{@link GearedDamage#TARGET_PER_HIT}(6.67) 은 <b>역산에 쓴 값</b>이지 카드가 맞춰야 하는
	 * 값이 아니다. 카드에 적히는 것이 정수라 정확히 6.67 을 만드는 값이 없고, 실제로
	 * 난이도 곱이 없는 쪽은 35(한 대 6.93), 있는 쪽은 23(한 대 6.77)으로 <b>서로 다르다.</b>
	 * 그 차이를 허용 오차로 묶으려 들면 「얼마까지 봐 줄 것인가」라는 답 없는 물음이 된다.
	 *
	 * <p>그래서 사람이 말한 것을 그대로 묻는다 — <b>두 대로는 안 죽고 세 대에는 죽는가.</b>
	 * 34 로 내리면 세 대가 19.83 이라 여기서 걸린다.
	 */
	@Test
	void 큰_카드는_무장_기준_세_대에_팀을_지운다() {
		float teamHealth = PerkHealthRules.effectiveMaxHealth(null);
		Set<String> big = Set.of("sharedfate:ground_strike", "sharedfate:lightning_storm",
				"sharedfate:pillar_fireball", "sharedfate:end_rain");
		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			if (!big.contains(trial.id())) {
				continue;
			}
			TickShape shape = shapeOf(trial.risks().getFirst());
			float perHit = GearedDamage.afterGear(shape.perHit(), shape.source());
			assertTrue(GearedDamage.wipesInThree(perHit),
					trial.name() + " — 무장 기준 한 대가 " + perHit + " 라 두 대에 "
							+ perHit * 2 + ", 세 대에 " + perHit * 3 + " 다. 사람이 정한 것은"
							+ " 「큰자리는 3대맞으면 죽는거로」이고, 팀 체력은 " + teamHealth
							+ " 다 — 역산값을 내림했다면 올림할 것");
		}
	}

	/**
	 * 감쇠 식이 26.3 에서 읽은 그 식이다.
	 *
	 * <p>{@link GearedDamage} 가 손으로 옮겨 적은 것이라 <b>옮기다 틀릴 수 있는 자리</b>다. 그래서
	 * 손으로 풀어 둔 값과 맞춰 둔다 — 여기가 틀리면 위의 두 시험이 나란히 헛것을 본다.
	 */
	@Test
	void 무장_감쇠가_26_3_의_식과_같다() {
		// 35 · 난이도 곱 없음: clamp(20 - 35/4, 4, 20) = 11.25 → 35 × (1 - 0.45) = 19.25
		//                      → × (1 - 16/25) = 6.93
		assertEquals(6.93F, GearedDamage.afterGear(35.0F, GearedDamage.Source.LIGHTNING_BOLT),
				0.01F, "번개 피해원에는 하드 곱이 안 걸린다 — 가해 개체가 없다");
		// 34 로 내렸을 때 무슨 일이 일어나는지도 적어 둔다. 6.61 × 3 = 19.83 이라 살아남는다 —
		// 이 한 줄이 「34 로 충분하지 않나」를 다음 사람이 다시 묻지 않게 한다.
		assertEquals(6.61F, GearedDamage.afterGear(34.0F, GearedDamage.Source.LIGHTNING_BOLT),
				0.01F);
		assertTrue(GearedDamage.afterGear(34.0F, GearedDamage.Source.LIGHTNING_BOLT) * 3
						< GearedDamage.TEAM_HEALTH,
				"34 는 세 대에 19.83 이라 살아남는다. 그래서 역산값을 올림해 35 를 쓴다");
		// 23 · 하드 곱 있음: 34.5 → clamp(20 - 34.5/4, 4, 20) = 11.375 → 18.80 → 6.77
		assertEquals(6.77F, GearedDamage.afterGear(23.0F, GearedDamage.Source.EXPLOSION),
				0.01F, "explosion 은 scaling 이 always 라 하드에서 1.5배가 먼저 걸린다");
		// magic 은 방어도를 지나치고 보호만 듣는다. 6 × 0.36 = 2.16
		assertEquals(2.16F, GearedDamage.afterGear(6.0F, GearedDamage.Source.MAGIC),
				0.01F, "magic 은 #bypasses_armor 라 방어도가 하나도 안 듣는다");
		// 곱한 뒤 감쇠하는 것과 한 방씩 감쇠하는 것이 다르다는 것을 숫자로 적어 둔다.
		float twoSeparately = GearedDamage.afterGear(34.0F, GearedDamage.Source.LIGHTNING_BOLT)
				* 2.0F;
		float onceTogether = GearedDamage.afterGear(68.0F, GearedDamage.Source.LIGHTNING_BOLT);
		assertTrue(onceTogether > twoSeparately,
				"합쳐서 감쇠하면 실제보다 아프게 나온다 — 감쇠는 한 방마다 걸린다");
	}

	@Test
	void 가장_나쁜_경우는_피해와_개수를_함께_본다() {
		TrialCatalog.Risk.DelayedStrike lightning = onlyStrike("sharedfate:lightning_storm");
		assertEquals(lightning.damage(), TrialRisks.worstCaseTickDamage(lightning),
				"최소 간격이 겹침 구역을 없애므로 열 곳이어도 한 사람은 한 발만 맞는다");

		// 최소 간격이 없는 발자국 쪽은 개수가 그대로 곱해진다. 「자리 폭격」을 두 발로 늘리는
		// 사람이 여기서 멈춘다 — 무장 기준으로도 13.9 라 팀 체력의 7할이 한 틱에 날아간다.
		TrialCatalog.Risk.DelayedStrike twoTrails = new TrialCatalog.Risk.DelayedStrike(
				TrialCatalog.Risk.Aim.TRAIL, TrialCatalog.Risk.Impact.EXPLOSION,
				240, 40, 35.0F, 2.0, 4.0, 2);
		assertEquals(70.0F, TrialRisks.worstCaseTickDamage(twoTrails),
				"둘이 나란히 서 있었으면 그 자리에 남은 사람이 두 발을 다 맞는다");
		assertTrue(GearedDamage.afterGear(35.0F, GearedDamage.Source.LIGHTNING_BOLT) * 2
						> PerkHealthRules.effectiveMaxHealth(null) / 2.0F,
				"두 발이 겹치면 무장하고도 팀 체력의 절반이 넘는다");

		// 상태를 거는 카드는 피해가 없다.
		assertEquals(0.0F, TrialRisks.worstCaseTickDamage(
				new TrialCatalog.Risk.CrystalGuard(true, false, false)));
	}

	// ------------------------------------------------------------------ 표식 색

	/**
	 * 표식 색이 <b>연출 값에서</b> 나온다.
	 *
	 * <p>카드마다 색을 적게 하면 「낙뢰인데 빨간 고리」가 나온다 — {@code Impact} 를 값으로 가른
	 * 이유가 바로 연출과 적힌 것이 어긋나서였다. 같은 실수를 색으로 되풀이하지 않게 못박는다.
	 */
	@Test
	void 번개_표식은_노랑이고_자리_폭격_표식은_빨강이다() {
		assertEquals(TrialWarning.Colors.LIGHTNING,
				TrialRisks.markColor(TrialCatalog.Risk.Impact.LIGHTNING),
				"사람이 번개 표식을 노랑으로 정했다");
		assertEquals(TrialWarning.Colors.DEADLY,
				TrialRisks.markColor(TrialCatalog.Risk.Impact.EXPLOSION),
				"「서 있으면 죽는다」의 빨강 그대로다");

		// 카드에서 곧바로 뽑아 본다. 연출을 바꾸면 색이 따라간다는 것이 이 구조의 전부다.
		assertEquals(TrialWarning.Colors.LIGHTNING,
				TrialRisks.markColor(onlyStrike("sharedfate:lightning_storm").impact()));
		assertEquals(TrialWarning.Colors.DEADLY,
				TrialRisks.markColor(onlyStrike("sharedfate:ground_strike").impact()));
		assertNotEquals(TrialRisks.markColor(TrialCatalog.Risk.Impact.LIGHTNING),
				TrialRisks.markColor(TrialCatalog.Risk.Impact.EXPLOSION),
				"둘이 같은 색이면 열 개 뜬 고리 중 어느 것이 무엇인지 읽을 수 없다");
	}

	@Test
	void 모든_연출에_색이_붙어_있다() {
		// default 없는 switch 라 빠뜨리면 빌드가 깨지지만, 「팔레트에 없는 값」까지는 막지 못한다.
		for (TrialCatalog.Risk.Impact impact : TrialCatalog.Risk.Impact.values()) {
			int color = TrialRisks.markColor(impact);
			assertTrue(color == TrialWarning.Colors.DEADLY
							|| color == TrialWarning.Colors.LIGHTNING
							|| color == TrialWarning.Colors.SHOVE
							|| color == TrialWarning.Colors.MARKED,
					impact + " 의 색이 규약 밖이다. 색을 새로 만들면 규약이 아니라 장식이 된다");
		}
	}

	// ------------------------------------------------------------------ 문서와의 짝

	/**
	 * 카드 값이 문서와 같다.
	 *
	 * <p>{@code docs/드래곤-시련-카드.md} 는 <b>값의 근거를 남기는 곳</b>이다. 코드만 고치고
	 * 문서를 두면 다음 사람이 「왜 이 값인가」를 물을 곳이 사라지고, 문서만 고치면 게임과 다른
	 * 설명이 남는다. 문서에 적어 둔 「값 —」 줄을 카드 값에서 그대로 만들어 찾는다.
	 */
	@Test
	void 카드_값이_문서와_같다() {
		String doc = cardDoc();

		TrialCatalog.Risk.DelayedStrike lightning = onlyStrike("sharedfate:lightning_storm");
		String lightningLine = "값 — 주기 " + lightning.interval() + "틱 · 반경 "
				+ plain(lightning.radius()) + " · 피해 " + plain(lightning.damage())
				+ " · 한 번에 " + lightning.count() + "곳";
		assertTrue(doc.contains(lightningLine),
				"「낙뢰」의 값이 문서와 다르다. 문서에 이 줄이 있어야 한다: " + lightningLine);

		TrialCatalog.Risk.TracedProjectile fireball = fireballCard();
		String fireballLine = "값 — 주기 " + fireball.interval() + "틱 · 궤적 "
				+ fireball.traceTicks() + "틱 · 반경 " + plain(fireball.radius())
				+ " · 피해 " + plain(fireball.damage()) + " · 한 번에 " + fireball.count() + "발";
		assertTrue(doc.contains(fireballLine),
				"「기둥 화염구」의 값이 문서와 다르다. 문서에 이 줄이 있어야 한다: " + fireballLine);
	}

	@Test
	void 바뀐_색_규약이_문서에_적혀_있다() {
		String doc = cardDoc();
		assertTrue(doc.contains("노란 고리"), "「낙뢰」 표식이 노랑이라는 말이 문서에 없다");
		assertTrue(doc.contains("LIGHTNING"), "색 규약표에 노랑의 새 이름이 없다");
		assertTrue(doc.contains("REQUIRED"),
				"옛 뜻을 지운 이유가 없으면 다음 사람이 노랑을 도로 가져간다");
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

	// ------------------------------------------------------------------ 타입과 실행기

	/**
	 * 위험 타입마다 그것을 돌리는 실행기.
	 *
	 * <p>{@code default} 없는 switch 라 실행을 안 붙이면 빌드가 깨진다. 그래도 여기에 적어 두는
	 * 것은 <b>컴파일러가 「어디로 갔는지」까지는 안 보기 때문</b>이다 — 타입을 늘리면서 남의
	 * 실행기로 잘못 보내도 빌드는 통과한다.
	 *
	 * <p>{@link TrialCatalog.Risk.DelayedStrike} 만 분배기 자신이 돌린다.
	 */
	private static final Map<String, String> EXECUTORS = Map.ofEntries(
			Map.entry("DelayedStrike", "TrialRisks"),
			Map.entry("TracedProjectile", "TrialFireball"),
			Map.entry("CrystalGuard", "TrialCrystalGuard"),
			Map.entry("DragonFocus", "TrialDragonFocus"),
			Map.entry("CrystalRevive", "TrialCrystalRevive"),
			Map.entry("EnderPulse", "TrialEnderPulse"),
			Map.entry("CrystalLink", "TrialCrystalLink"),
			Map.entry("CrystalOvercharge", "TrialCrystalOvercharge"),
			Map.entry("EnderStorm", "TrialEnderStorm"),
			Map.entry("DryWorld", "TrialDryWorld"),
			Map.entry("NightHost", "TrialNightHost"),
			Map.entry("EndRain", "TrialEndRain"),
			Map.entry("LandingShock", "TrialLandingShock"),
			Map.entry("HotbarLock", "TrialHotbarLock"));

	/**
	 * 위험 타입이 전부 실행기에 연결돼 있다.
	 *
	 * <p>실행기의 진입점 모양이 같은지도 함께 본다. 모양이 갈라지면 분배기의 분기가 갈래를 갖게
	 * 되고, 그때부터는 타입을 늘릴 때마다 배선을 새로 생각해야 한다.
	 */
	@Test
	void 위험_타입마다_실행기가_붙어_있다() {
		byte[] compiled = classBytes(TrialRisks.class);
		Class<?>[] types = TrialCatalog.Risk.class.getPermittedSubclasses();
		assertEquals(EXECUTORS.size(), types.length,
				"위험 타입을 늘렸으면 EXECUTORS 에도 적어라 — 어느 실행기로 갔는지는"
						+ " 컴파일러가 봐 주지 않는다");
		for (Class<?> type : types) {
			String executor = EXECUTORS.get(type.getSimpleName());
			assertNotNull(executor, type.getSimpleName() + " 의 실행기가 적혀 있지 않다");
			if (executor.equals(TrialRisks.class.getSimpleName())) {
				// 분배기 자신이 돌린다. 클래스 이름으로 찾을 것이 없다.
				continue;
			}
			assertTrue(references(compiled, executor),
					type.getSimpleName() + " 이 " + executor + " 로 가지 않는다 —"
							+ " 분배기가 그 이름을 한 번도 부르지 않는다");
			Class<?> owner = executorClass(executor);
			assertEquals(void.class, method(owner, "clearState").getReturnType(),
					executor + " 에 clearState 가 없다. 지난 판의 상태가 다음 판으로 샌다");
			// 열쇠(String)를 받는 것이 서명의 일부다. 받지 않으면 자리를 잡는 실행기가 겹침 금지
			// 목록의 열쇠를 스스로 만들어야 하고, 두 곳에서 만든 열쇠는 언젠가 갈라진다.
			assertEquals(void.class, method(owner, "tick", ServerLevel.class, EnderDragon.class,
					List.class, String.class, long.class, long.class, type).getReturnType(),
					executor + " 의 진입점 모양이 다른 실행기와 다르다");
		}
	}

	/**
	 * 새 실행기의 상태도 함께 비운다.
	 *
	 * <p>위험은 값(레코드)이라 상태를 들 수 없어 실행기마다 정적 맵을 쓴다. 한 곳이라도 빠지면
	 * 지난 판의 조준점·표적·설치 금지가 다음 판으로 샌다 — 컴파일도 로그도 조용한 사고다.
	 */
	@Test
	void 상태를_비우는_길이_실행기_전부를_지난다() {
		byte[] compiled = classBytes(TrialRisks.class);
		for (String executor : EXECUTORS.values()) {
			if (executor.equals(TrialRisks.class.getSimpleName())) {
				continue;
			}
			assertTrue(references(compiled, executor), executor + " 를 분배기가 모른다");
		}
		assertTrue(references(compiled, "clearState"),
				"분배기가 실행기의 상태를 비우는 길 자체가 없다");
	}

	// ------------------------------------------------------------------ 자리마다 제 풀

	/**
	 * 자리 여섯이 풀을 나눠 쓰지 않는다.
	 *
	 * <p>두 자리가 한 풀을 가리키면 앞 자리에서 뽑힌 카드가 뒤 자리의 풀에서도 빠진다
	 * ({@link TrialCatalog#offerable}). 그러면 <b>한 자리의 카드를 고치는 일이 다른 자리를 같이
	 * 움직인다</b> — 난이도 곡선을 자리마다 따로 잡으려고 풀을 나눈 뜻이 사라진다.
	 */
	@Test
	void 자리_여섯이_풀을_나눠_쓰지_않는다() {
		List<Set<TrialCatalog.Trigger>> pools = List.of(
				TrialCatalog.POOL_ENTRY, TrialCatalog.POOL_FIRST_CRYSTAL,
				TrialCatalog.POOL_ALL_CRYSTALS, TrialCatalog.POOL_HEALTH_80,
				TrialCatalog.POOL_HEALTH_50, TrialCatalog.POOL_HEALTH_30);
		assertEquals(TrialCatalog.Trigger.values().length, pools.size(),
				"자리를 새로 만들었으면 그 자리의 풀도 만들고 여기에 적어라");

		Set<TrialCatalog.Trigger> seen = EnumSet.noneOf(TrialCatalog.Trigger.class);
		for (Set<TrialCatalog.Trigger> pool : pools) {
			assertEquals(1, pool.size(), "풀 하나에 자리 하나다: " + pool);
			TrialCatalog.Trigger only = pool.iterator().next();
			assertTrue(seen.add(only), only.label() + " 의 풀이 둘이다");
		}
		assertEquals(TrialCatalog.Trigger.values().length, seen.size(),
				"풀이 없는 자리가 있다 — 그 자리는 영영 아무것도 주지 않는다");

		for (TrialCatalog.Trial trial : TrialCatalog.all()) {
			assertEquals(1, trial.pools().size(),
					trial.name() + " 가 자리 여럿에 걸쳐 있다. 한 자리에서 뽑히면 다른 자리에서"
							+ " 조용히 사라진다");
		}
	}

	/**
	 * 체력 80% 풀에 카드가 정확히 한 장이다.
	 *
	 * <p>이 자리는 룰렛도 돌리지 않고 <b>화면도 띄우지 않는다</b>({@link TrialCatalog.Reveal#SILENT}).
	 * 사람이 「80프로떄도 착지강화라고 카드가 안나오고 그냥 안띄워도되니 화면에 강화만 시켜주고」라고
	 * 정해 {@code FIXED_SCREEN} 에서 옮겨 왔고, 그 자리를 {@link TrialEmpower} 가 「세졌다」로 메운다.
	 *
	 * <p>그래서 카드가 둘이 되면 <b>어느 것이 걸렸는지 알 길이 아예 없어진다.</b> 화면이 있던
	 * 때보다 더 나쁘다 — 늘리려면 자리의 연출부터 다시 정할 것.
	 */
	@Test
	void 체력_80_풀에는_카드가_한_장뿐이다() {
		List<TrialCatalog.Trial> cards =
				TrialCatalog.offerable(TrialCatalog.Trigger.HEALTH_80, List.of());
		assertEquals(1, cards.size(), "실제로 들어 있는 카드: " + cards);
		assertEquals("sharedfate:landing_shock", cards.getFirst().id());
		assertEquals(TrialCatalog.Reveal.SILENT, TrialCatalog.Trigger.HEALTH_80.reveal(),
				"화면을 없앤 것은 사람이 정한 것이다. 이름이 어디에도 안 뜨므로 카드는 한 장이어야 한다");
	}

	/**
	 * 최후의 저항 자리는 비어 있고 <b>앞으로도 비어 있다.</b>
	 *
	 * <p>최후의 저항은 들어갔다 — {@link com.sharedfate.sync.DragonLastStand} 다. 그런데 카드가
	 * 아니라 별개 보스전이라 이 풀을 거치지 않는다. 그 페이즈가 하는 첫 일이 「걸려 있던 시련을
	 * 전부 끈다」라, 카드로 만들면 제 자신도 함께 꺼야 하는 모순이 생긴다.
	 *
	 * <p>그러니 빈 풀은 정상이고 그 자리는 그냥 지나간다 — 여기서 멈추면 그 사실을 다음 사람이
	 * 오해한 것이다.
	 */
	@Test
	void 체력_30_풀은_비어_있어도_지나간다() {
		assertTrue(TrialCatalog.offerable(TrialCatalog.Trigger.HEALTH_30, List.of()).isEmpty(),
				"최후의 저항은 카드가 아니라 DragonLastStand 라 이 풀을 거치지 않는다");
	}

	// ------------------------------------------------------------------ 카드가 달라도 겹치면 안 된다

	/**
	 * 서로 다른 카드의 지점끼리도 겹치지 않는다.
	 *
	 * <p>⚠ <b>이 시험이 지키는 것이 「즉사 메커닉 0개」의 마지막 구멍이다.</b> 시련은 전투가
	 * 끝날 때까지 쌓이므로 자리가 다른 두 카드도 <b>둘 다 받고 나면 같은 틱에 함께 돈다.</b>
	 * 겹친 자리에 선 사람은 두 발을 한 틱에 받는다. 한 카드 안에서만 떼어 놓던 옛 규칙으로는
	 * 이것을 막지 못한다.
	 *
	 * <h2>⚠ 둘째 카드가 「종말의 비」였는데 그 카드가 빠졌다</h2>
	 *
	 * <p>사람이 <b>「서로 겹쳐도 되니까 내가 말한 숫자로 해 줘」</b>라고 정해 그 카드만
	 * {@code reserveSpots} 를 지나지 않는다({@link TrialEndRain} 의 「겹침 금지 목록 밖이다」).
	 * <b>규칙 자체는 그대로 살아 있으므로 시험도 지우지 않았다</b> — 지금은 실제 카드 둘이 아니라
	 * 「낙뢰」 + <b>같은 크기의 둘째 카드</b>로 규칙을 돌려 본다. 앞으로 지점을 잡는 카드가
	 * 하나라도 더 생기면 그 카드가 이 자리로 들어온다.
	 *
	 * <p>예외가 <b>하나뿐</b>이라는 것은 {@link #겹침_금지_예외는_종말의_비_하나뿐이다} 가 본다.
	 */
	@Test
	void 카드가_달라도_지점끼리_겹치지_않는다() {
		TrialCatalog.Risk.DelayedStrike lightning = onlyStrike("sharedfate:lightning_storm");
		// 둘째 카드의 값은 「종말의 비」에서 가져온다 — 그 카드가 목록에서 빠졌어도 규칙이
		// 지켜야 할 크기와 개수는 여전히 그 근처다.
		TrialCatalog.Risk.EndRain shaped = endRainCard();
		double otherRadius = shaped.radius();
		int otherCount = shaped.maxSpots();
		float together = lightning.damage() + shaped.damage();
		assertTrue(together > PerkHealthRules.effectiveMaxHealth(null),
				"둘이 겹쳐도 안 죽는다면 이 시험을 지울 이유가 생긴다. 실제 합: " + together);

		double gap = TrialRisks.spotMinGap(lightning.radius(), otherRadius);
		RandomSource random = RandomSource.create(20260930L);
		for (int round = 0; round < 1000; round++) {
			List<Vec3> storm = roll(random, lightning.count(), lightning.radius());
			List<TrialRisks.LiveSpot> alive = new ArrayList<>();
			for (Vec3 spot : storm) {
				alive.add(new TrialRisks.LiveSpot(spot, lightning.radius()));
			}
			for (Vec3 drop : roll(random, otherCount, otherRadius, alive)) {
				for (Vec3 spot : storm) {
					assertTrue(flatDistance(spot, drop) > gap,
							"낙뢰 고리와 둘째 카드의 고리가 " + flatDistance(spot, drop)
									+ " 칸이다. 겹친 자리는 한 틱에 " + together + " 라"
									+ " 팀 체력 " + PerkHealthRules.effectiveMaxHealth(null)
									+ " 을 넘긴다");
				}
			}
		}
	}

	/**
	 * ⚠⚠ <b>겹침 금지를 지나지 않는 실행기는 「종말의 비」 하나뿐이다.</b>
	 *
	 * <p>그 카드가 빠진 것은 사람이 대가를 알고 정한 자리다. 그런데 <b>예외가 하나 생기면
	 * 둘째가 쉬워진다</b> — 다음에 지점을 뿌리는 카드를 만드는 사람이 「저 카드도 그냥
	 * 굴리던데」로 따라 나올 수 있고, 그때는 고리가 서로 겹치는데 아무 시험도 안 깨진다.
	 *
	 * <p>그래서 <b>자리를 스스로 굴리는 실행기가 늘었는지</b>를 컴파일된 클래스에서 직접 본다.
	 * {@link TrialRisks#arenaOffset} 을 부르는 것은 분배기 자신과 이 예외뿐이어야 한다.
	 */
	@Test
	void 겹침_금지_예외는_종말의_비_하나뿐이다() {
		assertFalse(references(classBytes(TrialEndRain.class), "reserveSpots"),
				"「종말의 비」가 겹침 금지 목록으로 되돌아갔다 — 그쪽 시험도 함께 볼 것");
		assertTrue(references(classBytes(TrialRisks.class), "reserveSpots"),
				"분배기가 겹침 금지 목록을 안 쓴다. 「낙뢰」가 남의 고리 위에 떨어진다");

		Set<String> rollingOwn = new TreeSet<>();
		for (String executor : new TreeSet<>(EXECUTORS.values())) {
			if (executor.equals(TrialRisks.class.getSimpleName())) {
				// 분배기가 굴리는 쪽이다. 그것이 겹침 금지의 본체다.
				continue;
			}
			if (references(classBytes(executorClass(executor)), "arenaOffset")) {
				rollingOwn.add(executor);
			}
		}
		assertEquals(Set.of("TrialEndRain"), rollingOwn,
				"아레나 자리를 스스로 굴리는 실행기가 " + rollingOwn + " 다. 예외는 사람이 정한"
						+ " 「종말의 비」 하나뿐이고, 새로 따라 나오면 그 카드의 고리가 남의"
						+ " 고리 위에 겹쳐 떨어진다 — 컴파일도 다른 시험도 조용하다");
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

	/**
	 * {@code groundSpot} 이 지면을 찾기 전에 하는 일을 그대로 흉내 낸다.
	 *
	 * <p>아레나 반경과 재굴림 횟수를 {@link TrialRisks} 에서 가져오는 것이 핵심이다. 시험이 제
	 * 숫자를 따로 들면 <b>실제와 다른 조건에서</b> 확인한 것이 된다.
	 */
	private static List<Vec3> roll(RandomSource random, int count, double radius) {
		return roll(random, count, radius, List.of());
	}

	/**
	 * 이미 살아 있는 지점이 있는 판에서 굴린다.
	 *
	 * <p>{@code alive} 가 있는 것이 핵심이다. 시련은 전투가 끝날 때까지 쌓이므로 새 카드가
	 * 자리를 잡는 순간 아레나에는 이미 다른 카드의 고리가 서 있다.
	 */
	private static List<Vec3> roll(RandomSource random, int count, double radius,
			List<TrialRisks.LiveSpot> alive) {
		List<TrialRisks.LiveSpot> taken = new ArrayList<>(alive);
		List<Vec3> spots = new ArrayList<>();
		for (int index = 0; index < count; index++) {
			for (int attempt = 0; attempt < TrialRisks.SPOT_TRIES; attempt++) {
				Vec3 candidate = TrialRisks.arenaOffset(random.nextDouble(), random.nextDouble(),
						TrialRisks.ARENA_RADIUS);
				if (TrialRisks.clearOfTaken(taken, candidate, radius)) {
					spots.add(candidate);
					taken.add(new TrialRisks.LiveSpot(candidate, radius));
					break;
				}
			}
		}
		return spots;
	}

	private static TrialRisks.LiveSpot live(double x, double y, double z, double radius) {
		return new TrialRisks.LiveSpot(new Vec3(x, y, z), radius);
	}

	private static boolean anyPairTooClose(List<Vec3> spots, double radius) {
		for (int first = 0; first < spots.size(); first++) {
			for (int second = first + 1; second < spots.size(); second++) {
				if (flatDistance(spots.get(first), spots.get(second))
						<= TrialRisks.spotMinGap(radius)) {
					return true;
				}
			}
		}
		return false;
	}

	/** 위에서 본 거리. 고리는 바닥에 그려지므로 높이는 보지 않는다. */
	private static double flatDistance(Vec3 first, Vec3 second) {
		double dx = first.x - second.x;
		double dz = first.z - second.z;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** 문서에 적는 모양의 숫자. {@code 3.0} 은 「3」, {@code 4.35} 는 「4.35」다. */
	private static String plain(double value) {
		if (value == Math.rint(value)) {
			return String.valueOf((long) value);
		}
		return String.valueOf(value);
	}

	/**
	 * 카드 문서를 읽는다.
	 *
	 * <p>작업 디렉터리에서 위로 올라가며 찾는다. Gradle 의 {@code test} 는 프로젝트 폴더에서
	 * 돌지만 IDE 는 모듈 폴더에서 돌 수 있어, 한 자리만 보면 환경에 따라 시험이 사라진다.
	 */
	private static String cardDoc() {
		Path here = Path.of("").toAbsolutePath();
		for (Path at = here; at != null; at = at.getParent()) {
			Path candidate = at.resolve("docs").resolve("드래곤-시련-카드.md");
			if (Files.isRegularFile(candidate)) {
				try {
					return Files.readString(candidate, StandardCharsets.UTF_8);
				} catch (IOException broken) {
					throw new AssertionError(candidate + " 를 읽지 못했다", broken);
				}
			}
		}
		throw new AssertionError("docs/드래곤-시련-카드.md 를 찾지 못했다. 작업 디렉터리: " + here);
	}

	private static TrialCatalog.Risk.TracedProjectile fireballCard() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:pillar_fireball");
		assertTrue(trial != null && trial.risks().size() == 1, "「기둥 화염구」 카드가 없다");
		return switch (trial.risks().getFirst()) {
			case TrialCatalog.Risk.TracedProjectile shot -> shot;
			case TrialCatalog.Risk risk -> throw new AssertionError(
					"「기둥 화염구」의 위험이 궤적 투사체가 아니다: " + risk);
		};
	}

	/** 같은 패키지의 실행기 클래스. 이름으로 찾는다 — 없으면 배선이 끊긴 것이다. */
	private static Class<?> executorClass(String simpleName) {
		String qualified = TrialRisks.class.getPackageName() + '.' + simpleName;
		try {
			return Class.forName(qualified);
		} catch (ClassNotFoundException missing) {
			throw new AssertionError(qualified + " 가 없다. 실행기 파일을 만들었는지 볼 것",
					missing);
		}
	}

	/** 실행기의 메서드 하나. 진입점 모양이 갈라지면 여기서 멈춘다. */
	private static Method method(Class<?> owner, String name, Class<?>... parameters) {
		try {
			return owner.getDeclaredMethod(name, parameters);
		} catch (NoSuchMethodException missing) {
			throw new AssertionError(owner.getSimpleName() + "." + name
					+ " 가 없거나 모양이 다르다. 실행기 진입점은 전부 같은 모양이어야 한다",
					missing);
		}
	}

	private static TrialCatalog.Risk.EndRain endRainCard() {
		TrialCatalog.Trial trial = TrialCatalog.byId("sharedfate:end_rain");
		assertTrue(trial != null && trial.risks().size() == 1, "「종말의 비」 카드가 없다");
		return switch (trial.risks().getFirst()) {
			case TrialCatalog.Risk.EndRain rain -> rain;
			case TrialCatalog.Risk risk -> throw new AssertionError(
					"「종말의 비」의 위험이 종말의 비가 아니다: " + risk);
		};
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
