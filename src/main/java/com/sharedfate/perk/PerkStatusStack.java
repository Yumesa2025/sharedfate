package com.sharedfate.perk;

import com.sharedfate.mixin.MobEffectInstanceAccessor;
import com.sharedfate.perk.effect.StatusEffectPerk;
import com.sharedfate.sync.EffectSync;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 증강이 사람에게 거는 상태이상이 겹칠 때의 규칙을 한곳에서 맞춘다.
 *
 * <h2>규칙</h2>
 * <p>같은 종류를 여러 출처가 주면 <b>가장 센 것이 남고, 그것이 사라지면 그다음 센 것이 남는다.</b>
 * 출처는 증강 둘 이상일 수도, 증강과 포션·비컨일 수도 있다.
 *
 * <h2>왜 따로 필요한가</h2>
 * <p>플레이어의 상태이상 표는 종류마다 인스턴스가 하나뿐이다. 바닐라
 * {@code MobEffectInstance.update} 는 더 센 것이 오면 위에 올리고, 약하지만 <b>더 오래가는</b>
 * 쪽만 밑에 숨겨 둔다. 무한끼리는 「더 오래」가 없어서 약한 쪽은 그냥 버려진다. 재생 II 무한
 * 위에 재생 I 무한을 얹으면 I 은 흔적도 남지 않는다 — 붙이는 쪽은 그래서 바닐라에 맡겨도 된다.
 *
 * <p>문제는 걷어낼 때다. 예전에는 {@code removeEffect} 로 종류째 지웠는데, 그러면
 * <ol>
 *   <li>다른 증강이 준 같은 종류까지 사라지고,
 *   <li>그 위에 덮여 있던 포션·비컨 몫까지 함께 사라진다.
 * </ol>
 * 조건부 증강은 {@code refreshPlayer} 가 돌 때마다 지는 쪽 묶음을 걷어내므로, 「아가미」처럼
 * 조건부 재생 I 을 하나 고르기만 해도 「밑 빠진 독」의 재생 II 무한이 지워졌다.
 *
 * <h2>어떻게 맞추는가</h2>
 * <p>사람마다 <b>「지금 걸려 있어야 할 증강 상시분의 출처」</b>를 적어 둔다. 붙일 때 적고 뗄 때
 * 지운다. 뗄 때는 남은 출처 중 가장 센 등급을 구해,
 * <ul>
 *   <li>그 등급이 떼는 것 이상이면 몸은 이미 맞으므로 손대지 않는다.
 *   <li>아니면 몸에 걸린 층(겉과 그 밑에 숨은 것)을 펼쳐 보고, 떼는 것이 준 무한 층이 남아
 *       있을 때만 그 종류를 다시 쌓는다. 남의 유한 층(포션)은 그대로 두고, 증강 무한 층은
 *       남은 출처 중 가장 센 것 하나로 바꾼다.
 * </ul>
 *
 * <p>증강 상시분은 언제나 무한이고 포션·비컨·전도체는 절대 무한이 아니라는 점에 기댄다. 판별
 * 기준은 {@link PerkStatusEffects#grants} 와 같다.
 *
 * <p>다시 쌓는 동안의 추가·제거는 팀 공유로 흘려보내지 않는다. 겉모습이 바뀌지 않는 포션 층을
 * 지웠다 붙이는 일이 팀원에게 퍼지면 팀원의 증강분까지 함께 지워진다.
 */
public final class PerkStatusStack {
	/**
	 * 사람 → 지금 걸려 있어야 할 증강 상시분의 출처.
	 *
	 * <p>출처는 정의 하나에 객체 하나라 같은 객체인지로 가린다. 값이 같은 두 증강은 남남이다.
	 * 서버 스레드에서만 오간다.
	 */
	private static final Map<UUID, Set<StatusEffectPerk>> GRANTED = new HashMap<>();

	private PerkStatusStack() {
	}

	/**
	 * 상태이상을 붙이고 떼는 몸.
	 *
	 * <p>살아 있는 서버에서는 {@link #of(ServerPlayer)} 를 쓰고, 시험에서는 상태이상 표만 흉내
	 * 낸 가짜를 넣는다.
	 */
	public interface Body {
		UUID id();

		@Nullable MobEffectInstance effect(Holder<MobEffect> type);

		/** 바닐라 {@code addEffect} 처럼 이미 있으면 {@code update} 로 합친다. */
		void add(MobEffectInstance instance);

		void remove(Holder<MobEffect> type);

		/** 한 종류를 다시 쌓는 일을 감싼다. 팀 공유로 새지 않게 막는 자리다. */
		default void rebuild(Holder<MobEffect> type, Runnable action) {
			action.run();
		}
	}

	/** 살아 있는 플레이어를 몸으로 쓴다. */
	public static Body of(ServerPlayer player) {
		return new PlayerBody(player);
	}

	// ------------------------------------------------------------------ 붙이고 떼기

	/**
	 * 증강 상시분을 붙인다.
	 *
	 * <p>더 센 것이 이미 걸려 있으면 바닐라가 알아서 그대로 둔다. 출처는 그래도 적어 둔다 — 센
	 * 쪽이 사라질 때 이것이 남아야 하기 때문이다.
	 */
	public static void grant(Body body, StatusEffectPerk source) {
		MobEffectInstance granted = source.grantedInstance();
		if (granted == null) {
			return;
		}
		GRANTED.computeIfAbsent(body.id(), ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
				.add(source);
		body.add(granted);
	}

	/**
	 * 증강 상시분을 뗀다. 남은 출처가 있으면 그중 가장 센 것이 남는다.
	 *
	 * <p>붙인 적 없는 출처를 떼도 안전하다. 조건부의 지는 쪽처럼 「어느 쪽이 붙어 있었는지 몰라
	 * 둘 다 뗀다」는 자리가 많아서다.
	 */
	public static void revoke(Body body, StatusEffectPerk source) {
		Holder<MobEffect> type = source.resolvedEffect();
		if (type == null) {
			return;
		}
		Set<StatusEffectPerk> sources = GRANTED.get(body.id());
		if (sources != null) {
			sources.remove(source);
			if (sources.isEmpty()) {
				GRANTED.remove(body.id());
			}
		}

		StatusEffectPerk strongest = strongest(body.id(), type);
		int remaining = strongest == null ? -1 : strongest.amplifier();
		int removed = source.amplifier();
		if (remaining >= removed) {
			// 같은 등급 이상이 아직 남아 있다. 몸은 이미 맞는 모습이다.
			return;
		}
		MobEffectInstance current = body.effect(type);
		if (current == null) {
			return;
		}
		List<MobEffectInstance> layers = layersOf(current);
		// 떼는 것이 준 무한 층이 남아 있을 때만 다시 쌓는다. 그보다 센 무한 층은 남의 것이다.
		boolean stale = false;
		List<MobEffectInstance> kept = new ArrayList<>(layers.size());
		for (MobEffectInstance layer : layers) {
			if (layer.isInfiniteDuration() && layer.getAmplifier() <= removed) {
				stale |= layer.getAmplifier() > remaining;
			} else {
				kept.add(layer);
			}
		}
		if (!stale) {
			return;
		}
		restack(body, type, kept, strongest);
	}

	/**
	 * 정해진 시간만 걸어 둔 상태이상을 곧바로 걷어낸다. 증강 상시분과 다른 등급의 층은 남긴다.
	 *
	 * <p>{@code on_team_hurt}·{@code on_critical}·{@code holder} 의 넘겨줄 때 효과처럼 「잠깐 건
	 * 것을 일찍 거둔다」는 자리가 쓴다. 예전에는 {@code removeEffect} 로 종류째 지워서, 잠깐 건
	 * 재생 I 을 거두는 순간 다른 증강의 재생 II 무한까지 사라졌다.
	 *
	 * <p>잠깐 건 것과 등급이 같은 포션은 이미 바닐라가 하나로 합쳐 두었으므로 함께 걷힌다.
	 */
	public static void revokeTimed(Body body, Holder<MobEffect> type, int amplifier) {
		if (type == null) {
			return;
		}
		MobEffectInstance current = body.effect(type);
		if (current == null) {
			return;
		}
		boolean found = false;
		List<MobEffectInstance> kept = new ArrayList<>();
		for (MobEffectInstance layer : layersOf(current)) {
			if (!layer.isInfiniteDuration() && layer.getAmplifier() == amplifier) {
				found = true;
			} else {
				kept.add(layer);
			}
		}
		if (!found) {
			return;
		}
		restack(body, type, kept, strongest(body.id(), type));
	}

	/** 이 사람에게 이 종류를 거는 출처 중 가장 센 것. 없으면 null. */
	public static @Nullable StatusEffectPerk strongest(UUID player, Holder<MobEffect> type) {
		Set<StatusEffectPerk> sources = GRANTED.get(player);
		if (sources == null || type == null) {
			return null;
		}
		StatusEffectPerk best = null;
		for (StatusEffectPerk source : sources) {
			if (type.equals(source.resolvedEffect())
					&& (best == null || source.amplifier() > best.amplifier())) {
				best = source;
			}
		}
		return best;
	}

	/**
	 * 이 사람의 출처 기록을 지운다.
	 *
	 * <p>상태이상을 통째로 지우는 자리(팀 해체·회차 초기화)와 접속 종료에서 부른다. 남겨 두면
	 * 이미 잃은 증강의 등급이 나중에 다시 쌓을 때 되살아난다. 다시 붙여야 할 것은
	 * {@code PerkManager.refreshPlayer} 가 처음부터 다시 적는다.
	 */
	public static void forget(@Nullable UUID player) {
		if (player != null) {
			GRANTED.remove(player);
		}
	}

	/** 서버가 멈출 때나 시험에서 상태를 격리할 때 쓴다. */
	public static void reset() {
		GRANTED.clear();
	}

	// ------------------------------------------------------------------ 도우미

	/** 겉 층부터 그 밑에 숨은 층까지 차례로. 돌려주는 것은 숨은 층을 뗀 복사본이다. */
	static List<MobEffectInstance> layersOf(MobEffectInstance top) {
		List<MobEffectInstance> layers = new ArrayList<>();
		for (MobEffectInstance layer = top; layer != null;
				layer = ((MobEffectInstanceAccessor) layer).sharedfate$getHiddenEffect()) {
			layers.add(new MobEffectInstance(layer.getEffect(), layer.getDuration(), layer.getAmplifier(),
					layer.isAmbient(), layer.isVisible(), layer.showIcon()));
		}
		return layers;
	}

	/**
	 * 한 종류를 지우고 남길 층과 증강 상시분 하나로 다시 쌓는다.
	 *
	 * <p>등급이 낮은 것부터 붙인다. 그러면 바닐라 {@code update} 가 「센 것은 위로, 약하지만 더
	 * 오래가는 것은 밑으로」를 그대로 되풀이해 처음과 같은 모양의 층이 만들어진다. 등급이 같으면
	 * 짧은 것을 먼저 붙여 긴 것이 지속시간을 이어받게 한다.
	 */
	private static void restack(Body body, Holder<MobEffect> type, List<MobEffectInstance> kept,
			@Nullable StatusEffectPerk strongest) {
		List<MobEffectInstance> stack = new ArrayList<>(kept);
		MobEffectInstance perk = strongest == null ? null : strongest.grantedInstance();
		if (perk != null) {
			stack.add(perk);
		}
		stack.sort(Comparator.comparingInt(MobEffectInstance::getAmplifier)
				.thenComparing(MobEffectInstance::isInfiniteDuration)
				.thenComparingInt(MobEffectInstance::getDuration));
		body.rebuild(type, () -> {
			body.remove(type);
			for (MobEffectInstance layer : stack) {
				body.add(layer);
			}
		});
	}

	/** 살아 있는 플레이어. */
	private record PlayerBody(ServerPlayer player) implements Body {
		@Override
		public UUID id() {
			return player.getUUID();
		}

		@Override
		public @Nullable MobEffectInstance effect(Holder<MobEffect> type) {
			return player.getEffect(type);
		}

		@Override
		public void add(MobEffectInstance instance) {
			player.addEffect(instance);
		}

		@Override
		public void remove(Holder<MobEffect> type) {
			player.removeEffect(type);
		}

		/**
		 * 팀 공유를 막고 다시 쌓는다.
		 *
		 * <p>흡수는 붙는 순간 바닐라가 흡수량을 최대치로 채운다. 층을 다시 쌓았다고 노란 하트가
		 * 공짜로 차오르면 안 되므로 쌓기 전의 양으로 되돌린다.
		 */
		@Override
		public void rebuild(Holder<MobEffect> type, Runnable action) {
			boolean absorption = MobEffects.ABSORPTION.equals(type);
			float before = player.getAbsorptionAmount();
			EffectSync.withoutSharing(action);
			if (absorption) {
				player.setAbsorptionAmount(Math.min(before, player.getMaxAbsorption()));
			}
		}
	}
}
