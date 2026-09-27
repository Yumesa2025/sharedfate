package com.sharedfate.sync;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;

/**
 * 시련 패턴이 공유하는 경고 신호.
 *
 * <h2>왜 하나로 모으는가</h2>
 *
 * <p>패턴마다 경고를 따로 만들면 플레이어가 패턴 수만큼 새 신호를 배워야 한다. 조사한 모드 중
 * 가장 잘 된 것은 <b>모든 패턴이 같은 경고를 쓰고</b>, 플레이어는 그 하나만 배우면 되게 해
 * 두었다. 여기에 모아 두면 패턴을 늘릴 때 예고가 공짜로 따라온다.
 *
 * <h2>통신 규약을 올리지 않는다</h2>
 *
 * <p>파티클·소리·자막은 전부 바닐라 패킷이다. 모드를 안 깐 사람에게도 보이고, 시련 선택 화면을
 * 만들기 전에도 패턴을 실제로 굴려 볼 수 있다.
 *
 * <h2>세 층으로 쌓는다</h2>
 *
 * <p>한 층을 놓쳐도 다음 층에서 잡히게 한다. 월드가 삭제되는 게임에서 「못 봤다」는 설계
 * 실패다.
 *
 * <ol>
 *   <li>{@link Stage#APPROACH} — 저음. 「뭔가 온다」</li>
 *   <li>{@link Stage#MARK} — 바닥 고리. 「이게 온다, 여기로 온다」</li>
 *   <li>{@link Stage#IMMINENT} — 고음 + 붉은 자막. 「지금 나가라」</li>
 * </ol>
 *
 * <h2>바닥에 그린다</h2>
 *
 * <p>{@link AuraRing} 은 「범위가 켜져 있다」를 보여 주려고 눈높이에 띄우지만, 경고는
 * <b>서 있으면 안 되는 자리</b>를 가리키므로 지면에 붙어야 한다. 엔드 섬은 평평해서 파묻힐
 * 걱정이 적다.
 */
public final class TrialWarning {
	/** 고리 하나에 찍는 파티클 수. 반경이 커져도 개수는 그대로다 — 성겨도 경계는 읽힌다. */
	private static final int POINTS = 40;
	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다. */
	private static final double GROUND_OFFSET = 0.15;

	/**
	 * 요구하는 행동별 최소 예고 시간(틱).
	 *
	 * <p>사람의 지각·판단·입력에만 0.25초가 든다. 여기에 대응 행동의 시간과 네 명이 서로를 보고
	 * 맞추는 시간을 더한 값이다. 실제 플레이로 보정해야 하는 <b>출발점</b>이지 확정값이 아니다.
	 */
	public static final int TICKS_SIDESTEP = 30;
	/** 지정한 자리로 옮겨야 할 때. */
	public static final int TICKS_REPOSITION = 50;
	/** 네 명이 서로 다른 곳으로 흩어져야 할 때. 합의할 시간이 필요하다. */
	public static final int TICKS_SCATTER = 80;

	/** 경고의 세 층. */
	public enum Stage {
		APPROACH,
		MARK,
		IMMINENT
	}

	/**
	 * 색 규약. 같은 색은 언제나 같은 뜻이어야 플레이어가 표식을 언어로 배운다.
	 *
	 * <p>패턴을 늘릴 때 여기 없는 색을 새로 만들지 말 것. 색이 늘어나는 순간 규약이 아니게 된다.
	 */
	public static final class Colors {
		/** 서 있으면 죽는다. */
		public static final int DEADLY = 0xFF3333;
		/** 여기 서 있어야 한다. */
		public static final int REQUIRED = 0xFFD54A;
		/** 밀려난다. */
		public static final int SHOVE = 0x4AA3FF;
		/** 너 하나를 노린다. */
		public static final int MARKED = 0xB44AFF;

		private Colors() {
		}
	}

	private TrialWarning() {
	}

	/**
	 * 위험한 자리를 바닥 고리로 그린다. 「서 있으면 죽는다」는 뜻의 빨강이다.
	 *
	 * @param center 위험 지점
	 * @param radius 반경(블록)
	 */
	public static void markGround(@Nullable ServerLevel level, @Nullable Vec3 center, double radius) {
		markGround(level, center, radius, dust(Colors.DEADLY));
	}

	/** 색을 골라 먼지 파티클을 만든다. 크기 1.0 이 바닐라 레드스톤 가루와 같다. */
	public static ParticleOptions dust(int color) {
		return new DustParticleOptions(color, 1.0F);
	}

	/** 파티클을 직접 고르는 형태. 색으로 위험의 종류를 가를 때 쓴다. */
	public static void markGround(@Nullable ServerLevel level, @Nullable Vec3 center, double radius,
			@Nullable ParticleOptions type) {
		if (level == null || center == null || type == null || !(radius > 0.0)) {
			return;
		}
		for (int index = 0; index < POINTS; index++) {
			double angle = (Math.PI * 2.0 * index) / POINTS;
			level.sendParticles(type,
					center.x + Math.cos(angle) * radius,
					center.y + GROUND_OFFSET,
					center.z + Math.sin(angle) * radius,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 남은 시간에 맞는 층을 고른다.
	 *
	 * <p>{@code null} 이면 아직 아무 층에도 닿지 않은 것이다. 층이 바뀌는 순간에만 소리를 내야
	 * 하므로, 호출자는 직전 층과 비교해 달라졌을 때만 {@link #sound} 를 부른다.
	 *
	 * @param remainingTicks 발동까지 남은 틱
	 */
	public static @Nullable Stage stageFor(int remainingTicks) {
		if (remainingTicks <= 14) {
			return Stage.IMMINENT;
		}
		if (remainingTicks <= 50) {
			return Stage.MARK;
		}
		if (remainingTicks <= 100) {
			return Stage.APPROACH;
		}
		return null;
	}

	/** 층에 맞는 소리를 낸다. 같은 층에서 두 번 부르면 두 번 울리므로 층이 바뀔 때만 부른다. */
	public static void sound(@Nullable ServerLevel level, @Nullable Vec3 at, Stage stage) {
		if (level == null || at == null) {
			return;
		}
		switch (stage) {
			case APPROACH -> level.playSound(null, at.x, at.y, at.z,
					SoundEvents.ENDER_DRAGON_GROWL, SoundSource.HOSTILE, 0.6F, 0.7F);
			case MARK -> level.playSound(null, at.x, at.y, at.z,
					SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 0.5F, 1.4F);
			case IMMINENT -> level.playSound(null, at.x, at.y, at.z,
					SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.HOSTILE, 1.0F, 1.8F);
		}
	}

	/** 마지막 층에서 띄우는 문구. 무엇을 해야 하는지만 짧게 적는다. */
	public static void shout(@Nullable Collection<ServerPlayer> players, @Nullable Component text) {
		if (players == null || players.isEmpty() || text == null) {
			return;
		}
		TitleMessenger.showActionBar(players, text);
	}
}
