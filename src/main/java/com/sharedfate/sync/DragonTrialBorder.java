package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 드래곤 시련 전투의 <b>엔드 월드 보더</b>. 시련을 켠 팀의 전투가 열리면 섬 가장자리에 서고,
 * 「최후의 저항」에서 {@link DragonLastStandZone} 의 시간표대로 줄어들며, 전투가 닫히면 원래대로 돌아간다.
 *
 * <h2>2026-10-18 — 사람 지시로 「나갈 수 있는 원」을 버리고 보더로 돌아왔다</h2>
 *
 * <p>사람 말: <b>「2페이지 때 나오는 경계선 있잖아, 그 밖으로 나가면 딜 입히는 거 그거 없애고,
 * 드래곤전 시작하면 월드보더가 제일 끝에서 생기고 2페이지 넘어올 때 원래 줄어들기 시작할 때부터
 * 줄어들게 하고 싶어. 월드보더로 아예 못 넘어가게, 넘어가서 딜 입히는 건 폐기」</b>. 보더가 네모라는 것을
 * 알고 <b>섬 가장자리에서 시작</b>을 골랐다. 2026-10-04 에 보더를 버렸던 까닭(「나갈 수가 없어 이상함」)은
 * 밖 피해가 붙어 있었기 때문이고, 이번에는 밖 피해를 통째로 걷었다 — <b>막기만 하고 아프게 하지 않는다.</b>
 *
 * <h2>26.3 에서 확인한 사실 — 보더는 차원마다 하나씩이다</h2>
 *
 * <p>{@code ServerLevel.getWorldBorder()} 가 {@code getDataStorage().computeIfAbsent(WorldBorder.TYPE)}
 * 다(2026-10-18 javap 로 다시 확인). 곧 엔드 보더는 엔드의 {@code SavedData}
 * ({@code dimensions/minecraft/the_end/data/minecraft/world_border.dat})이고 <b>오버월드 보더
 * ({@code PreStartRestrictions} 의 스폰 보더)와 따로 산다.</b> 그 대신 월드 저장 파일에 남는다 — 되돌리는
 * 이야기는 {@link #restore} 와 {@link #onServerStarted}.
 *
 * <h2>무엇을 바꾸는가</h2>
 *
 * <ul>
 *   <li><b>중심</b> — 포디움({@link TrialPodium#locate}). 최후의 저항의 지대 중심과 같은 자리다</li>
 *   <li><b>한 변</b> — 반변 × 2. 반변이 곧 문서의 「반경」이다({@link #sizeOf} 한 곳에서만 곱한다)</li>
 *   <li><b>칸당 피해 0</b> — 26.3 {@code LivingEntity} 의 보더 피해 구간은 {@code damagePerBlock > 0}
 *       일 때만 돈다. 「넘어가서 딜 입히는 건 폐기」가 이 한 줄이다. 경고(빨간 화면 테두리)는 바닐라
 *       그대로 둔다 — 줄어드는 벽이 다가온다는 신호다</li>
 * </ul>
 *
 * <h2>틱이 붙든다 — 여는 줄·닫는 줄 하나에 기대지 않는다</h2>
 *
 * <p>{@code DragonTrialManager.tickSessions} 가 시련 세션마다 매 틱 {@link #hold} 로 섬 크기를, 최후의
 * 저항이 열린 뒤에는 {@link DragonLastStandZone#tick} 이 지대 크기를 세운다. 이미 그 크기면 아무것도
 * 보내지 않는다. 그리고 {@code DragonTrialManager.tick} 이 매 틱 <b>시련 세션이 하나도 없는데 보더를 들고
 * 있으면</b> {@link #restore} 한다 — 승리 · 해체 · 시련 끔으로 되살린 세션 · {@code forceStart} 로 덮어쓴
 * 세션이 전부 이 한 줄로 닫힌다(「한쪽만 막으면 반드시 샌다」).
 */
public final class DragonTrialBorder {

	/**
	 * 전투가 열렸을 때의 반변(칸). <b>엔드 본섬 전체를 덮는 가장 작은 둥근 값</b>이다.
	 *
	 * <h2>근거 — 26.3 의 섬 밀도식</h2>
	 *
	 * <p>{@code data/minecraft/worldgen/density_function/end/islands.json} 이
	 * {@code (clamp(100 − 중앙까지의 거리, −100, 80) − 8) × 0.0078125} 다(그 위에 {@code end/base_3d_noise}
	 * 가 더해진다). 노이즈를 빼면 <b>중앙에서 92칸</b>에서 0 이 되어 그 밖은 섬이 아니다. 바깥 관문 고리
	 * (반경 96)도 안에 들어야 한다.
	 *
	 * <p>그런데 100 이 아니라 <b>105</b> 인 까닭은 <b>흑요석 도착 발판</b>이다. 엔드 포털로 들어온 사람은
	 * 바닐라 {@code ServerLevel.END_SPAWN_POINT}(100, 50, 0) 위 5×5 발판(x 98~102 칸, 동쪽 끝 103.0)에
	 * 떨어지는데, 반변 100 이면 보더 동쪽 끝이 100.5 라 <b>발판 한가운데에 벽이 선다</b> — 늦게 들어온
	 * 팀원이 벽에 걸친 채 도착한다. 105 면 벽이 105.5 로 발판 끝에서 2.5칸 밖이다.
	 *
	 * <p>⚠ 보더는 네모라 모서리 방향은 148칸까지 열린다 — 사람이 알고 고른 것이다. ⚠ 가장자리 조각이 실제
	 * 월드에서 105 안에 다 드는지는 <b>게임에서 못 봤다</b>(시험 월드에 엔드 지역 파일이 없었다).
	 */
	static final double ISLAND_HALF_SIDE = 105.0;

	/** 크기·중심이 같은지 견줄 때의 여유. 보더 값은 double 이라 그대로 견주면 매 틱 다시 보낸다. */
	private static final double EPSILON = 1.0E-6;

	/**
	 * ⚠ <b>우리가 만지기 전의 보더.</b> 되돌릴 때 쓰는 유일한 근거다. {@code null} 이면 「들고 있지 않다」.
	 *
	 * <p>만지기 전에 반드시 채우고 되돌린 뒤에 반드시 비운다. 다만 그 「전」이 <b>우리가 남긴 보더</b>
	 * ({@link #looksOurs})면 바닐라 기본값을 기억한다 — 서버가 죽어 남은 보더를 원래 값으로 삼으면 영영
	 * 안 돌아간다(2026-10-04 이전 코드의 구멍).
	 */
	private static @Nullable WorldBorder.Settings remembered;

	private DragonTrialBorder() {
	}

	// ------------------------------------------------------------------ 월드 없이 도는 셈

	/**
	 * 반변(칸)을 보더 {@code size}(<b>한 변</b>)로 바꾼다.
	 *
	 * <p>⚠ 반변을 그대로 {@code setSize} 에 넘기면 지대가 <b>절반</b>이 된다 — 가장 틀리기 쉬운 곱셈이라
	 * 여기 한 곳에서만 곱한다.
	 */
	static double sizeOf(double halfSide) {
		return Math.max(0.0, halfSide) * 2.0;
	}

	/**
	 * 이 보더가 <b>우리가 남긴 것</b>으로 보이는가 — 칸당 피해가 0 이고 중심이 포디움이다.
	 *
	 * <p>바닐라 기본은 칸당 피해 0.2 · 중심 (0, 0) 이고 포디움은 (0.5, 0.5) 라, 손대지 않은 보더도
	 * 운영자가 손으로 정한 보더도 이 둘을 함께 맞추는 일이 사실상 없다.
	 */
	static boolean looksOurs(double damagePerBlock, double centerX, double centerZ, Vec3 podium) {
		return damagePerBlock == 0.0
				&& Math.abs(centerX - podium.x) < EPSILON
				&& Math.abs(centerZ - podium.z) < EPSILON;
	}

	/** 기억해 둔 보더를 되돌릴 때 얹을 한 변. 움직이던 중이었으면 목표값에 세운다. */
	static double restoredSize(WorldBorder.Settings settings) {
		return settings.lerpTime() > 0L ? settings.lerpTarget() : settings.size();
	}

	// ------------------------------------------------------------------ 세우기

	/**
	 * 보더를 이 반변으로 <b>멈춰</b> 세운다. 이미 그 크기로 서 있으면 아무것도 보내지 않는다.
	 *
	 * @param center 포디움. 보더의 중심이 된다
	 */
	static void hold(ServerLevel end, Vec3 center, double halfSide) {
		WorldBorder border = take(end, center);
		double size = sizeOf(halfSide);
		if (border.getLerpTime() > 0L || Math.abs(border.getSize() - size) > EPSILON) {
			border.setSize(size);
		}
	}

	/**
	 * 보더를 {@code remaining} 틱 동안 {@code toHalf} 까지 줄인다. 이미 그 목표로 움직이는 중이면
	 * 아무것도 보내지 않는다.
	 *
	 * <p>26.3 {@code lerpSizeBetween} 의 셋째 인자는 <b>틱</b>이다({@code MovingBorderExtent.update} 가
	 * 1씩 줄인다). 남은 틱으로 넘기므로 늦게 시작한 축소도 제 시각에 끝난다. 보더의 틱은 레벨 틱이라
	 * 판이 얼면({@code TrialFreeze}) 함께 선다 — 우리 시계(게임 시각)와 같은 박자다.
	 *
	 * <p>⚠ 이미 목표에 닿아 멈춰 있으면 다시 줄이지 않는다. 보더가 우리 시계보다 먼저 끝나는 판(얼어
	 * 있는 동안 보더만 흐른 판)에서 {@code fromHalf} 로 다시 시작하면 <b>벽이 바깥으로 튀었다가</b> 다시
	 * 들어온다. 시작 크기도 같은 까닭으로 {@link #lerpStart} 가 지금 크기 쪽으로 자른다.
	 *
	 * @param fromHalf 지금 있어야 할 반변. 처음 줄이기 시작하는 틱에만 쓰인다
	 */
	static void shrink(ServerLevel end, Vec3 center, double fromHalf, double toHalf, long remaining,
			long now) {
		if (remaining <= 0L) {
			hold(end, center, toHalf);
			return;
		}
		WorldBorder border = take(end, center);
		double target = sizeOf(toHalf);
		boolean moving = border.getLerpTime() > 0L;
		if (moving && Math.abs(border.getLerpTarget() - target) < EPSILON) {
			return;
		}
		if (!moving && Math.abs(border.getSize() - target) < EPSILON) {
			return;
		}
		border.lerpSizeBetween(lerpStart(border.getSize(), sizeOf(fromHalf), target), target,
				remaining, now);
	}

	/**
	 * 줄이기를 시작할 한 변. <b>지금 크기와 있어야 할 크기 가운데 작은 쪽</b>이고 목표보다 작지 않다.
	 *
	 * <p>재시작으로 되살린 판은 보더가 바닐라 기본값(한 변 약 6천만)으로 돌아와 있다. 그 값에서 남은
	 * 몇 초 동안 줄이면 벽이 하늘 끝에서 날아온다 — 그래서 있어야 할 크기에서 시작한다. 거꾸로 지금
	 * 크기가 더 작으면(이미 거기까지 줄었다) 거기서 시작해 바깥으로 튀지 않는다.
	 */
	static double lerpStart(double currentSize, double expectedSize, double targetSize) {
		return Math.max(targetSize, Math.min(currentSize, expectedSize));
	}

	/** 지금 보더의 반변 — 움직이는 중이면 <b>이 틱의</b> 크기다. 벽이 실제로 서 있는 자리다. */
	static double halfNow(ServerLevel end) {
		return end.getWorldBorder().getSize() / 2.0;
	}

	/** 보더가 가 닿을 반변 — 움직이는 중이면 목표, 멈춰 있으면 지금 크기다. */
	static double halfTarget(ServerLevel end) {
		WorldBorder border = end.getWorldBorder();
		return (border.getLerpTime() > 0L ? border.getLerpTarget() : border.getSize()) / 2.0;
	}

	/** 보더를 우리 것으로 잡는다. 처음이면 원래 값을 기억하고, 중심과 칸당 피해를 맞춘다. */
	private static WorldBorder take(ServerLevel end, Vec3 center) {
		WorldBorder border = end.getWorldBorder();
		if (remembered == null) {
			WorldBorder.Settings now = new WorldBorder.Settings(border);
			// 서버가 죽어 우리 보더가 남은 채로 떠서 곧바로 전투가 이어지는 판이다. 그 보더를 「원래 값」
			// 으로 삼으면 전투가 끝나도 섬 크기 보더가 남는다.
			remembered = looksOurs(now.damagePerBlock(), now.centerX(), now.centerZ(), center)
					? WorldBorder.Settings.DEFAULT : now;
			SharedFateMod.LOGGER.info(
					"[END] 엔드 월드 보더를 기억했습니다 — 한 변 {} · 중심 ({}, {}) · 칸당 피해 {}{}",
					remembered.size(), remembered.centerX(), remembered.centerZ(),
					remembered.damagePerBlock(),
					remembered == WorldBorder.Settings.DEFAULT ? " (남아 있던 우리 보더라 기본값으로)" : "");
		}
		if (Math.abs(border.getCenterX() - center.x) > EPSILON
				|| Math.abs(border.getCenterZ() - center.z) > EPSILON) {
			border.setCenter(center.x, center.z);
		}
		// 「넘어가서 딜 입히는 건 폐기」 — 바닐라 보더 피해를 끈다.
		if (border.getDamagePerBlock() != 0.0) {
			border.setDamagePerBlock(0.0);
		}
		return border;
	}

	// ------------------------------------------------------------------ 되돌리기

	/**
	 * ⚠ <b>보더를 원래대로 되돌린다.</b> 월드가 살아 있어야 한다. 들고 있지 않으면 아무 일도 안 한다.
	 *
	 * <p>부르는 곳이 둘이다 — {@code DragonTrialManager.tick}(시련 세션이 없는데 들고 있으면)과
	 * {@link #onServerStopping}(저장 직전). {@link #clearState} 에서는 부를 수 없다 — 그쪽은
	 * {@code SERVER_STOPPED} 에서도 불려 레벨이 이미 닫혀 있다.
	 */
	static void restore(@Nullable ServerLevel end) {
		WorldBorder.Settings original = remembered;
		if (original == null || end == null) {
			return;
		}
		try {
			apply(end.getWorldBorder(), original);
			SharedFateMod.LOGGER.info("[END] 엔드 월드 보더를 원래대로 되돌렸습니다 — 한 변 {} · 중심 ({}, {})",
					restoredSize(original), original.centerX(), original.centerZ());
		} catch (RuntimeException error) {
			SharedFateMod.LOGGER.warn("엔드 월드 보더를 되돌리지 못했습니다.", error);
		}
		remembered = null;
	}

	private static void apply(WorldBorder border, WorldBorder.Settings settings) {
		border.setCenter(settings.centerX(), settings.centerZ());
		border.setDamagePerBlock(settings.damagePerBlock());
		border.setSafeZone(settings.safeZone());
		border.setWarningBlocks(settings.warningBlocks());
		border.setWarningTime(settings.warningTime());
		border.setSize(restoredSize(settings));
	}

	/**
	 * 서버가 멈추기 직전. 저장보다 먼저 되돌린다 — {@code SERVER_STOPPED} 는 이미 늦다. 늦으면 줄어든
	 * 보더가 저장 파일에 남는다. 진행 중이던 전투는 다시 뜬 뒤 틱이 다시 세운다.
	 */
	public static void onServerStopping(@Nullable MinecraftServer server) {
		restore(server == null ? null : server.getLevel(Level.END));
	}

	/**
	 * 서버가 떴다 — <b>강제 종료로 남은 보더</b>를 치운다. {@code DragonTrialManager.onServerStarted} 가
	 * 세션을 되살린 뒤에 부른다.
	 *
	 * <p>진행 중인 시련 전투가 있으면 손대지 않는다 — 다음 틱부터 그 전투가 다시 붙든다. 없으면 엔드 보더가
	 * {@link #looksOurs} 일 때만 바닐라 기본값으로 되돌린다. 운영자가 손으로 정한 엔드 보더는 건드리지 않는다.
	 *
	 * @param fightHeld 되살린 세션 가운데 시련을 켠 것이 있는가
	 */
	public static void onServerStarted(@Nullable MinecraftServer server, boolean fightHeld) {
		ServerLevel end = server == null ? null : server.getLevel(Level.END);
		if (end == null || fightHeld) {
			return;
		}
		WorldBorder border = end.getWorldBorder();
		Vec3 podium = TrialPodium.locate(end, null);
		if (!looksOurs(border.getDamagePerBlock(), border.getCenterX(), border.getCenterZ(), podium)) {
			return;
		}
		apply(border, WorldBorder.Settings.DEFAULT);
		SharedFateMod.LOGGER.info("[END] 지난 전투가 남긴 엔드 월드 보더를 바닐라 기본값으로 되돌렸습니다");
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. <b>기억만 버린다</b> — 월드를 만지지 않는다. */
	public static void clearState() {
		remembered = null;
	}

	/** 지금 보더를 우리가 들고 있는가. */
	static boolean held() {
		return remembered != null;
	}
}
