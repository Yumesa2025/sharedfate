package com.sharedfate.mixin;

import com.sharedfate.sync.TrialExperience;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 드래곤 시련 팀의 경험치 규칙 둘을 플레이어 입구에 건다(0.44.0-dev). 규칙 자체는
 * {@link TrialExperience} 에 있다.
 *
 * <ul>
 *   <li><b>곡선</b> — {@code getXpNeededForNextLevel()} 을 팀의 곡선으로. 바닐라 {@code giveExperiencePoints}
 *       가 막대를 「받은 점수 ÷ 이 값」만큼 채우므로, 이 값이 공유 셈({@code StatMirror})과 같아야 점수가
 *       새지 않는다. <b>서버 플레이어만</b>이다 — 클라이언트는 서버가 보낸 레벨·막대를 그리기만 한다</li>
 *   <li><b>엔드 차단</b> — 엔드에 선 시련 팀원은 {@code giveExperiencePoints}·{@code giveExperienceLevels}
 *       의 <b>양수</b>를 받지 않는다. 구슬(채굴·처치·병·화로·거래·번식·낚시)과 {@code /xp} 가 이 둘로
 *       들어온다. 음수(줄어드는 쪽)는 그대로 둔다</li>
 * </ul>
 *
 * <h2>대상</h2>
 * <p>26.3 {@code javap -p net.minecraft.world.entity.player.Player} —
 * {@code public int getXpNeededForNextLevel()} · {@code public void giveExperiencePoints(int)} ·
 * {@code public void giveExperienceLevels(int)}. refmap 이 없으므로 {@code PlayerExperienceTargetTest} 가
 * 셋이 그 모양으로 있는지 못박는다.
 */
@Mixin(Player.class)
public abstract class PlayerExperienceRulesMixin {

	@Inject(method = "getXpNeededForNextLevel()I", at = @At("HEAD"), cancellable = true)
	private void sharedfate$trialCurve(CallbackInfoReturnable<Integer> callback) {
		if ((Object) this instanceof ServerPlayer player && TrialExperience.usesTrialCurve(player)) {
			callback.setReturnValue(TrialExperience.needed(player.experienceLevel, true));
		}
	}

	@Inject(method = "giveExperiencePoints(I)V", at = @At("HEAD"), cancellable = true)
	private void sharedfate$blockEndPoints(int amount, CallbackInfo callback) {
		if (amount > 0 && (Object) this instanceof ServerPlayer player
				&& TrialExperience.blocksGain(player)) {
			callback.cancel();
		}
	}

	@Inject(method = "giveExperienceLevels(I)V", at = @At("HEAD"), cancellable = true)
	private void sharedfate$blockEndLevels(int levels, CallbackInfo callback) {
		if (levels > 0 && (Object) this instanceof ServerPlayer player
				&& TrialExperience.blocksGain(player)) {
			callback.cancel();
		}
	}
}
