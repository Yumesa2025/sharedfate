package com.sharedfate.mixin;

import com.sharedfate.perk.PerkBlockBreaks;
import com.sharedfate.sync.TrialCrystalGuard;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 채굴 속도에 끼어드는 <b>단 하나의</b> 자리. 둘이 여기를 탄다.
 *
 * <ul>
 *   <li>{@code mining_speed} 증강 — 실버 7 「광맥 감각」의 대가. <b>지금 실제로 도는 쪽은
 *       이것뿐이다.</b></li>
 *   <li>시련의 채굴 무뎌짐
 *       ({@link com.sharedfate.sync.TrialCrystalGuard#DIG_SLOWDOWN_MULTIPLIER}). ⚠⚠
 *       <b>2026-10-01 현재 이것을 켜는 카드가 하나도 없다</b> — 「쇠창살과 무딘 곡괭이」가
 *       채굴 피로 I → 직접 15% 감소를 거쳐 왔다가, 사람이 「무딘곡괭이는 채굴감소 없앳으니
 *       이름 변경해」라고 해서 그 몫을 통째로 걷고 「다시 선 쇠창살」이 되었다. 아래 호출은
 *       남겨 두었다 — <b>이 믹스인을 증강이 함께 쓰므로 한쪽을 걷으려다 둘 다 걷으면 증강이
 *       죽는다.</b> 되살리려면 카드에 {@code digSlowdown = true} 를 적으면 된다.
 *       <b>믹스인을 새로 만들지 않은</b> 까닭도 그대로다 — 잡을 자리가 같으니 파일을 하나 더
 *       만들면 {@code sharedfate.mixins.json} 에 줄이 늘고, 재정의되는 메서드에 거는 사고를 낼
 *       자리가 하나 늘 뿐이다</li>
 * </ul>
 *
 * <p>둘을 <b>곱해서</b> 먹인다. 함께 걸리면 곱이 맞는다 — 증강이 0.7 이고 시련이 0.85 면
 * 0.595 다. 각자 자기 몫만 보고 계산하므로 순서도 상관없다.
 *
 * <p>26.2 에서 "이 플레이어가 이 블록을 얼마나 빨리 캐는가"가 한 숫자로 정해지는 곳은
 * {@code Player.getDestroySpeed(BlockState)} 하나다. 도구 등급·효율 마법·성급함·채굴 피로·
 * 물속·공중까지 전부 여기서 합쳐지고, 진행도를 세는 쪽
 * ({@code BlockStateBase.getDestroyProgress} → {@code ServerPlayerGameMode.tick})은 그 결과만
 * 받아 간다. 그래서 여기 한 곳만 잡으면 모든 경로가 같은 값을 본다.
 *
 * <p>RETURN 에 붙는 이유는 바닐라가 계산을 다 끝낸 값에 배율을 곱해야 하기 때문이다. HEAD 에서
 * 끼어들면 도구·마법·상태이상이 아직 반영되지 않은 값을 보게 된다.
 *
 * <h2>{@code ServerPlayer} 가 이 메서드를 재정의하지 않는다</h2>
 * <p>26.3 클래스 파일로 확인했다. 재정의하는 하위 클래스가 있으면 이 믹스인은 <b>한 번도 돌지
 * 않으면서 빌드도 로그도 통과한다</b> — 이 저장소가 {@code SlotExpandedLockMixin} 에서 실제로
 * 겪은 사고다. 판을 올리는 사람은 여기를 다시 확인할 것.
 *
 * <h2>클라이언트에서는 아무 일도 하지 않는다</h2>
 * <p>이 mixin 은 공용 설정에 들어 있어 클라이언트의 {@code LocalPlayer} 에도 걸린다. 하지만
 * {@link PerkBlockBreaks#scaleDestroySpeed} 와
 * {@link com.sharedfate.sync.TrialCrystalGuard#scaleDestroySpeed} 가 {@code ServerPlayer} 가
 * 아닌 플레이어에게는 원래 값을 그대로 돌려주므로 클라이언트 계산은 바닐라 그대로다.
 *
 * <p>그래서 전용 서버에서는 클라이언트가 서버보다 빨리 "다 캤다"고 판단한다. 이때 바닐라는
 * 블록을 없애 주지 않고 {@code ServerPlayerGameMode} 가 {@code hasDelayedDestroy} 로 자기
 * 진행도를 마저 채운 뒤 부순다. 즉 <b>블록은 늦게, 그러나 반드시 부서진다.</b> 대신 클라이언트
 * 화면에서 금이 한 번 되돌아갔다가 다시 부서지는 것이 보인다. 이것을 없애려면 보유 증강을
 * 클라이언트까지 내려보내야 하는데, 지금 내려가는 {@code PerkSyncPayload} 는 표시용 문자열만
 * 담고 있어 증강 id 를 알 수 없다. 배율을 크게 잡을수록 눈에 띄므로 정의 파일에서 0.5 아래로는
 * 내리지 않는 편이 좋다.
 *
 * <h2>그래서 이 길로는 빠르게 할 수 없다</h2>
 * <p>위 문단은 <b>느려지는 쪽</b>에서만 성립한다. 26.2 의 {@code ServerPlayerGameMode.tick} 은
 * {@code isDestroyingBlock} 분기에서 {@code incrementDestroyProgress} 의 결과를 <b>버린다</b>
 * (바이트코드에서 {@code pop}). 서버가 스스로 블록을 부수는 자리는 {@code START} 시점의 즉시
 * 파괴, 클라이언트의 {@code STOP} 을 받았을 때, 그리고 {@code hasDelayedDestroy} 셋뿐이다.
 * <b>파괴 시점은 전적으로 클라이언트의 {@code STOP_DESTROY_BLOCK} 에 달려 있다.</b> 서버가
 * 아무리 빨라져도 클라이언트가 바닐라 속도로 다 캘 때까지 아무 일도 일어나지 않는다.
 *
 * <p>빠르게 하려면 {@code mining_speed} 가 아니라 <b>{@code minecraft:block_break_speed}
 * 속성</b>을 써야 한다. 그 속성은 {@code setSyncable(true)} 라 클라이언트까지 자동으로
 * 내려가고, {@code Player.getDestroySpeed} 안에서 바닐라가 직접 곱한다. 자세한 것은
 * {@link com.sharedfate.perk.effect.MiningSpeedEffect} 의 클래스 주석에 있다.
 */
@Mixin(Player.class)
public abstract class PlayerMiningSpeedMixin {
	@Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
	private void sharedfate$applyPerkMiningSpeed(BlockState state,
			CallbackInfoReturnable<Float> callback) {
		float original = callback.getReturnValueF();
		Player self = (Player) (Object) this;
		float scaled = PerkBlockBreaks.scaleDestroySpeed(self, state, original);
		// 시련의 채굴 무뎌짐. 블록을 가리지 않으므로 상태만 보고 곱한다.
		// ⚠ 지금 이것을 켜는 카드가 없어 늘 base 를 그대로 돌려준다. 떼지 않은 까닭은 위에.
		scaled = TrialCrystalGuard.scaleDestroySpeed(self, scaled);
		if (scaled != original) {
			callback.setReturnValue(scaled);
		}
	}
}
