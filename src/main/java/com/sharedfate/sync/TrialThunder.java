package com.sharedfate.sync;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.phys.Vec3;

/**
 * 우리가 띄우는 <b>연출용 번개</b>의 소리를 바닐라의 <b>70%</b> 로 낸다.
 *
 * <h2>사람 말 (2026-10-18)</h2>
 *
 * <p><b>「번개 소리 자체를 30프로 줄여 봐 너무 시끄러워」</b>. 이 모드의 번개는 두 곳에서 뜬다 —
 * 시련 카드 「낙뢰」({@code TrialRisks.strikeLightning})와 최후의 저항의 상시 번개
 * ({@code DragonLastStandPatterns.bolt}). 둘 다 바닐라 {@link LightningBolt} 개체이고 <b>소리는 바닐라가
 * 낸다.</b> 바닐라 날씨 번개는 건드리지 않는다 — 우리가 띄운 개체에만 이 길을 태운다.
 *
 * <h2>바닐라는 소리를 <b>클라이언트에서</b> 낸다 — 그래서 끄고 서버가 다시 낸다</h2>
 *
 * <p>26.3 {@code LightningBolt.tick} 을 풀어 읽었다. {@code life == 2} 인 첫 틱에
 * {@code level.isClientSide()} 이고 <b>{@code !isSilent()}</b> 이면 클라이언트가 스스로
 * {@code playLocalSound} 두 번을 부른다 — 천둥({@code LIGHTNING_BOLT_THUNDER} · {@code WEATHER} · 볼륨
 * <b>10000</b> · 음높이 {@code 0.8 + r × 0.2})과 착탄음({@code LIGHTNING_BOLT_IMPACT} · {@code WEATHER} ·
 * 볼륨 <b>2</b> · 음높이 {@code 0.5 + r × 0.2}). 서버에서 볼륨만 바꿀 자리가 없으므로 <b>그 개체를
 * {@code setSilent(true)} 로 띄우고</b>(동기화되는 개체 값이라 클라이언트가 첫 틱 전에 받는다) 같은 두
 * 소리를 서버가 사람마다 직접 보낸다. 클라이언트 jar 를 안 바꿔도 된다.
 *
 * <h2>⚠ 볼륨에 0.7 을 곱하면 <b>안 된다</b> — 거리 감쇠가 함께 바뀐다</h2>
 *
 * <p>26.3 클라이언트({@code SoundEngine.play} · {@code Sound.getAttenuationDistance} ·
 * {@code Channel.linearAttenuation} 바이트코드)에서 들리는 크기는
 *
 * <blockquote>{@code clamp(볼륨, 0, 1) × (1 − 거리 ÷ (16 × max(볼륨, 1)))}</blockquote>
 *
 * <p>이다(OpenAL 선형 감쇠 · 기준 거리 0 · {@code sounds.json} 의 두 소리 다 감쇠 거리 기본값 16 —
 * 파일을 열어 확인했다. 두 소리의 파일 {@code ambient/weather/thunder1~3} · {@code random/explode1~4}
 * 는 모노라 자리가 그대로 방향이 된다). 볼륨 1 을 넘는 값은 <b>크기가 아니라 들리는 거리</b>다 — 천둥
 * 10000 은 「어디서나 온전히」, 착탄음 2 는 「32칸까지 줄어들며」다. 거기에 0.7 을 곱하면 천둥이 7000
 * 이라 <b>하나도 안 작아지고</b>, 착탄음은 1.4 라 크기는 그대로 거리만 22칸으로 줄어든다.
 *
 * <p>그래서 <b>사람마다</b> 바닐라가 그 거리에서 낼 크기를 셈해 0.7 을 곱하고, 소리를 <b>그 사람의 귀에서
 * 번개 쪽으로 {@value #PLACE_DISTANCE}칸</b> 떨어진 자리에 놓는다(방향은 그대로 남는다). 그 한 칸이 깎는
 * 몫({@code 1 − 1/16})은 볼륨을 그만큼 올려 메운다 — 올린 볼륨이 최대 {@code 0.7 ÷ 0.9375 ≈ 0.75} 라
 * 1 을 넘지 않아 감쇠 거리가 16 으로 고정되고 셈이 닫힌다.
 *
 * <p>⚠ 받는 사람은 <b>번개 개체가 보일 만한 사람</b>이다 — 바닐라에서 그 소리를 내는 것이 개체를 받은
 * 클라이언트이고, 번개의 추적 거리가 {@code EntityType.LIGHTNING_BOLT.clientTrackingRange()}(청크)다.
 * 서버 시야 거리로 더 줄어드는 몫은 따라 하지 않는다. 관전자도 바닐라처럼 듣는다(소리 갈래가
 * {@code WEATHER} 라 사람의 「날씨」 음량 설정을 그대로 따른다 — 바닐라와 같다).
 */
final class TrialThunder {

	/** 바닐라에 곱하는 크기. 사람이 「30프로 줄여」라고 했다. */
	static final float LOUDNESS = 0.7F;

	/** 26.3 {@code LightningBolt.tick} 이 천둥에 주는 볼륨. */
	static final float THUNDER_VOLUME = 10000.0F;
	/** 26.3 {@code LightningBolt.tick} 이 착탄음에 주는 볼륨. */
	static final float IMPACT_VOLUME = 2.0F;

	/** {@code sounds.json} 의 감쇠 거리 기본값. 두 소리 다 따로 적어 두지 않았다. */
	static final double ATTENUATION_DISTANCE = 16.0;

	/** 소리를 놓는 자리 — 귀에서 번개 쪽으로 이만큼(칸). 꾸러미가 자리를 1/8칸으로 담으므로 넉넉히 1칸. */
	static final double PLACE_DISTANCE = 1.0;

	private TrialThunder() {
	}

	/**
	 * 우리 번개 하나를 조용히 띄우고 그 소리를 70% 로 낸다. <b>{@code addFreshEntity} 앞에서</b> 부를 것 —
	 * 조용함이 개체를 처음 보내는 꾸러미에 실려야 클라이언트의 첫 틱이 소리를 안 낸다.
	 *
	 * @param at 번개가 떨어지는 자리. 바닐라도 개체 자리에서 소리를 낸다
	 */
	static void muffle(ServerLevel end, LightningBolt bolt, Vec3 at) {
		bolt.setSilent(true);
		// 바닐라는 클라이언트마다 따로 굴리지만 우리는 한 번 굴려 팀 전원이 같은 천둥을 듣게 한다.
		long seed = end.getRandom().nextLong();
		float thunderPitch = 0.8F + end.getRandom().nextFloat() * 0.2F;
		float impactPitch = 0.5F + end.getRandom().nextFloat() * 0.2F;
		double reach = EntityTypes.LIGHTNING_BOLT.clientTrackingRange() * 16.0;
		for (ServerPlayer listener : end.players()) {
			if (listener.distanceToSqr(at) > reach * reach) {
				continue;
			}
			send(listener, holderOf(SoundEvents.LIGHTNING_BOLT_THUNDER), THUNDER_VOLUME,
					thunderPitch, seed, at);
			send(listener, holderOf(SoundEvents.LIGHTNING_BOLT_IMPACT), IMPACT_VOLUME,
					impactPitch, seed, at);
		}
	}

	private static void send(ServerPlayer listener, Holder<SoundEvent> sound, float vanillaVolume,
			float pitch, long seed, Vec3 at) {
		Vec3 ear = listener.getEyePosition();
		Vec3 toward = at.subtract(ear);
		double distance = toward.length();
		float target = LOUDNESS * vanillaLoudness(vanillaVolume, distance);
		if (!(target > 0.0F)) {
			// 바닐라에서도 안 들리는 거리다.
			return;
		}
		double place = Math.min(distance, PLACE_DISTANCE);
		Vec3 spot = distance > 1.0E-6 ? ear.add(toward.scale(place / distance)) : ear;
		listener.connection.send(new ClientboundSoundPacket(sound, SoundSource.WEATHER,
				spot.x, spot.y, spot.z, sentVolume(target, place), pitch, seed));
	}

	/**
	 * 바닐라 클라이언트가 그 거리에서 내는 크기 — {@code clamp(볼륨) × (1 − 거리 ÷ 감쇠 거리)}.
	 * 월드 없이 답이 정해지므로 시험이 직접 잰다.
	 */
	static float vanillaLoudness(float volume, double distance) {
		double range = ATTENUATION_DISTANCE * Math.max(volume, 1.0F);
		double falloff = Math.max(0.0, 1.0 - distance / range);
		return (float) (Math.min(Math.max(volume, 0.0F), 1.0F) * falloff);
	}

	/**
	 * {@code place} 칸 떨어진 자리에 놓았을 때 귀에 {@code target} 으로 들리게 하는 볼륨. 1 을 넘지 않아야
	 * 감쇠 거리가 16 으로 남는다 — {@link #PLACE_DISTANCE} 가 그것을 지킨다.
	 */
	static float sentVolume(float target, double place) {
		return (float) (target / (1.0 - place / ATTENUATION_DISTANCE));
	}

	private static Holder<SoundEvent> holderOf(SoundEvent sound) {
		return BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
	}
}
