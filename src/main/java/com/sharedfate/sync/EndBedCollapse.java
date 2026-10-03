package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 엔드의 침대는 <b>터지지 않고 주저앉는다.</b>
 *
 * <p>사람 말: <b>「엔더에서 침대 터뜨렷을떄 터지지않게 하자 대신 침대가 아에 파괴되게 터지는딜
 * 없애버리게」</b>
 *
 * <p>값을 깎는 것이 아니다. <b>「침대로 드래곤을 때리는」 전술을 통째로 없애는 것</b>이 목적이다.
 * 그래서 피해도 블록 파괴도 화염도 <b>전부</b> 사라진다 — 반지름을 줄이거나 피해만 0 으로
 * 만드는 길은 쓰지 않는다. 폭발 자체가 안 생기므로 {@code VictoryTeamResolver} 가 말하는
 * 「주인 없는 폭발」도 엔드에서는 침대로는 더 이상 생기지 않는다.
 *
 * <p>침대는 그래도 <b>사라진다.</b> 그것을 여기서 하지 않는다 — 26.3 바이트코드에서 확인했듯
 * 바닐라가 폭발보다 <b>먼저</b> 치우기 때문이다(아래). 우리가 또 치우면 두 번 지우는 것이고,
 * 그 둘째 번은 이미 공기가 된 자리를 건드린다. 「블록을 부수지 마라」는 이 저장소 규칙과도
 * 어긋나지 않는다.
 *
 * <h2>26.3 바이트코드에서 확인한 갈래</h2>
 *
 * <p>26.3 은 침대를 {@code AbstractBedBlock} / {@code BedBlock} / {@code StrawBedBlock} 셋으로
 * 쪼개고, 「침대가 통하는 차원인가」를 <b>차원 타입이 아니라 환경 속성</b>으로 묻는다 —
 * {@code dimensionType().bedWorks()} 는 더 이상 없다.
 *
 * <pre>
 * AbstractBedBlock.useWithoutItem(BlockState, Level, BlockPos, Player, BlockHitResult)
 *   ├ isClientSide() 면 곧바로 SUCCESS_SERVER 로 빠진다        ← 서버만 지난다
 *   ├ BedRule rule = getBedRule(level, pos)                    ← EnvironmentAttributes.BED_RULE
 *   └ rule.destroyOnUse() 가 참이면
 *       rule.errorMessage().ifPresent(player::displayClientMessage)
 *       return this.destroyOnUse(state, level, pos, player)     ← 유일한 호출 자리
 *
 * BedBlock.destroyOnUse(BlockState, Level, BlockPos, Player)   ← 폭발이 사는 곳
 *   ├ level.removeBlock(pos, false)                            ← 머리 쪽을 지운다
 *   ├ level.removeBlock(상대편, false)                          ← 발 쪽도 지운다
 *   └ level.explode(null, damageSources().badRespawnPointExplosion(가운데),
 *                   null, 가운데, 5.0F, true, ExplosionInteraction.BLOCK)
 * </pre>
 *
 * <p><b>지우는 것이 먼저이고 폭발이 마지막 한 줄</b>이다. 그래서 폭발 호출만 삼키면 요청이
 * 그대로 이루어진다 — 침대는 사라지고 폭발은 없다. {@code 5.0F} 는 반지름,
 * {@code true} 는 <b>불을 심는다</b>는 뜻이고 {@code ExplosionInteraction.BLOCK} 이 블록을
 * 부수게 한다. 셋 다 호출 하나에 들어 있으므로 호출을 안 하면 셋이 함께 사라진다.
 *
 * <h2>왜 차원 열쇠로 가리는가 — {@code BedRule} 로 가리지 않는다</h2>
 *
 * <p>사람이 말한 것은 <b>「엔더에서」</b>다. 그런데 {@code BedRule.destroyOnUse()} 는
 * <b>네더에서도 참</b>이다. 그 깃발로 가리면 네더 침대까지 함께 죽는다.
 *
 * <p>{@code BED_RULE} 은 데이터팩이 차원마다 주는 환경 속성이라 「이 규칙이 엔드에서 왔는가」를
 * 그 값만 보고는 알 수 없다. 그래서 <b>{@code level.dimension()} 과 {@link Level#END} 를 직접
 * 견준다</b> — 이 저장소가 이미 열두 자리에서 쓰는 수단이고({@code DragonTrialManager} ·
 * {@code EndFightTeleportLock} · {@code TrialDryWorld}), 데이터팩이 무엇을 바꾸든 「엔드인가」의
 * 뜻이 흔들리지 않는다.
 *
 * <p><b>리스폰 앵커는 건드리지 않는다.</b> 26.3 의 {@code RespawnAnchorBlock} 은 자기만의
 * {@code private void explode(BlockState, ServerLevel, BlockPos)} 와 자기만의 환경 속성
 * ({@code EnvironmentAttributes.RESPAWN_ANCHOR_WORKS})을 갖는다 — {@code destroyOnUse} 를
 * 아예 지나지 않으므로 여기 믹스인이 닿지 않는다. 사람이 말한 것은 침대뿐이다.
 *
 * <p><b>짚 침대도 건드릴 것이 없다.</b> {@code StrawBedBlock.destroyOnUse} 는 폭발을 만들지
 * 않고 {@code STRAW_BED_BREAK_LEAVE} 를 울리고 공기로 바꿀 뿐이다. 26.3 에서 이미 안 터진다.
 *
 * <h2>되먹임 — 아무 일도 안 일어난 것처럼 보이면 안 된다</h2>
 *
 * <p>폭발이 사라지면 침대만 조용히 없어진다. 사람은 그것을 「막혔다」가 아니라 <b>「버그다」로
 * 읽는다</b> — {@code TrialCrystalLink.deflect} 가 바닐라 무적 앞에서 겪은 것과 같은 자리다.
 * 그쪽과 같은 꼴로 <b>보이는 것 하나와 들리는 것 하나</b>를 둔다. ⚠ 글은 띄우지 않는다.
 *
 * <ul>
 *   <li>{@link ParticleTypes#POOF} — 이 저장소의 어느 시련도 쓰지 않는 파티클이다(쓰는 것은
 *       {@code EXPLOSION} · {@code EXPLOSION_EMITTER} · {@code SMOKE} · {@code LARGE_SMOKE}).
 *       바닐라에서 뜻이 <b>「그 자리에서 사라졌다」</b>라 배울 것이 없고, 폭발 파티클과
 *       눈으로 섞이지 않는다 — 섞이면 「터졌나?」가 되어 요청이 거짓이 된다</li>
 *   <li>{@link SoundEvents#STRAW_BED_BREAK_LEAVE} — ⚠ 26.3 {@code sounds.json} 을 직접 열어
 *       확인했다. 실제 파일은 {@code block/straw_bed/break_leave} <b>한 개</b>이고, 그 파일을
 *       쓰는 소리 이름은 26.3 전체에서 <b>이것뿐</b>이다(자막 열쇠
 *       {@code subtitles.block.straw_bed.break_leave}). 같은 이름 아래 {@code break1~4} 를 쓰는
 *       {@code STRAW_BED_BREAK} 와도 파일이 다르다. 바닐라에서 이 소리의 뜻은 글자 그대로
 *       <b>「침대가 주저앉았다」</b>({@code StrawBedBlock.destroyBed} 가 유일한 사용처)라
 *       여기 뜻과 정확히 맞는다</li>
 * </ul>
 *
 * <p>⚠ 폭발음 계열은 모두 피했다 — {@code DRAGON_FIREBALL_EXPLODE} 와
 * {@code END_GATEWAY_SPAWN} 이 <b>둘 다</b> {@code random/explode1~4} 라 이름만 다르고 귀로는
 * 일반 폭발음이다(6장). 그것을 쓰면 「안 터졌다」를 폭발음으로 알리는 꼴이 된다.
 */
public final class EndBedCollapse {
	/** 주저앉는 연기 알 수. 한 자리에서 터지므로 숫자가 곧 짙기다. */
	private static final int POOF_COUNT = 18;

	/** 연기가 퍼지는 폭. 침대 한 칸(1.0)보다 좁게 둬 옆 칸으로 새지 않는다. */
	private static final double POOF_SPREAD = 0.32;

	/** 소리는 침대 자리에 한 번. 볼륨 1 이면 16블록이고, 누른 사람은 늘 그 안에 있다. */
	private static final float SOUND_VOLUME = 1.0F;

	/** 바닐라보다 낮게 — 짚이 아니라 무거운 것이 내려앉는 소리로 들린다. */
	private static final float SOUND_PITCH = 0.8F;

	private EndBedCollapse() {
	}

	/**
	 * 이 차원에서 침대 폭발을 삼키는가. <b>엔드에서만 참이다.</b>
	 *
	 * <p>네더는 거짓이다 — 사람이 말한 것은 「엔더에서」이고, 네더 침대는 그대로 터져야 한다.
	 *
	 * @param dimension {@code level.dimension()}. {@code null} 은 「모르는 차원」으로 보아 거짓이다
	 */
	public static boolean swallows(@Nullable ResourceKey<Level> dimension) {
		return dimension == Level.END;
	}

	/**
	 * 폭발 한 방이 갈리는 자리.
	 *
	 * <p>레벨을 받지 않는다 — 시험이 <b>여기와 똑같은 순서</b>를 지날 수 있게 차원 열쇠와 두
	 * 갈래만 받는다. 믹스인이 부르는 {@link #explode} 가 이것을 그대로 쓴다.
	 */
	public static void route(@Nullable ResourceKey<Level> dimension, Runnable vanilla,
			Runnable collapse) {
		if (swallows(dimension)) {
			collapse.run();
			return;
		}
		vanilla.run();
	}

	/**
	 * {@code BedBlock.destroyOnUse} 안의 {@code level.explode(..)} 를 대신 받는 자리.
	 *
	 * <p>엔드가 아니면 {@code vanilla} 를 그대로 흘린다 — 네더에서 한 글자도 달라지지 않는다.
	 *
	 * @param at 바닐라가 폭발을 놓으려던 자리. {@code Vec3.atCenterOf(머리 쪽 pos)} 다
	 * @param vanilla 삼키지 않을 때 부를 바닐라 폭발
	 */
	public static void explode(Level level, Vec3 at, Runnable vanilla) {
		route(level.dimension(), vanilla, () -> collapse(level, at));
	}

	/**
	 * 침대가 주저앉았다 — <b>터지지 않았다는 것을 보이고 들린다.</b>
	 *
	 * <p>블록은 지우지 않는다. 바닐라가 이 호출 <b>앞에서</b> 양쪽을 이미 치웠다(클래스 주석의
	 * 바이트코드). 여기서 또 치우면 두 번째는 공기를 지운다.
	 */
	private static void collapse(Level level, Vec3 at) {
		if (level instanceof ServerLevel server) {
			server.sendParticles(ParticleTypes.POOF, true, false, at.x, at.y, at.z,
					POOF_COUNT, POOF_SPREAD, POOF_SPREAD, POOF_SPREAD, 0.0);
		}
		// 주인을 null 로 둔다. 누른 사람에게도 들려야 하는데, 엔티티를 넣으면 바닐라가 그
		// 사람에게는 보내지 않는다(자기 소리는 자기가 낸다고 보는 자리다).
		level.playSound(null, at.x, at.y, at.z, SoundEvents.STRAW_BED_BREAK_LEAVE,
				SoundSource.BLOCKS, SOUND_VOLUME, SOUND_PITCH);
	}
}
