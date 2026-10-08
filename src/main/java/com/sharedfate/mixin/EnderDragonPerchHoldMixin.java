package com.sharedfate.mixin;

import com.sharedfate.sync.DragonPerch;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhaseManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 포디움에 내려앉은 드래곤이 <b>너무 빨리 일어나는 것</b>을 막는다.
 *
 * <p>사람 말: <b>「착지후 너무 빨리 일어나던데 착지하고 어느시간동안은 안올라가게 해야할거같아」</b>
 *
 * <h2>왜 여기인가 — 일어나는 길이 셋인데 전부 이 문을 지난다</h2>
 *
 * <p>26.3 바이트코드에서 센 「앉은 드래곤이 일어나는 길」이 셋이다(값과 근거는
 * {@code DragonPerch} 클래스 설명에 적어 두었다).
 *
 * <ol>
 *   <li>{@code DragonSittingScanningPhase.doServerTick} — 반경 20 안에 사람이 없으면 100틱에
 *       {@code TAKEOFF}. <b>사람이 「너무 빨리」라고 한 5초가 이것이다</b></li>
 *   <li>{@code DragonSittingFlamingPhase.doServerTick} — 네 번째 불을 뿜고 나면 {@code TAKEOFF}</li>
 *   <li>{@code EnderDragon.hurt} — 앉은 동안 누적 피해가 최대 체력의 25% 를 넘으면 {@code TAKEOFF}</li>
 * </ol>
 *
 * <p>셋을 저마다 막으면 이 저장소가 네 번 틀린 「한쪽만 막으면 반드시 샌다」에 그대로 걸린다.
 * 셋이 전부 지나는 자리가 <b>{@code EnderDragonPhaseManager.setPhase} 하나</b>이므로 거기를
 * 잠근다.
 *
 * <h2>무엇을 통과시키는가 — 금지가 아니라 허용으로 적는다</h2>
 *
 * <ul>
 *   <li><b>앉은 칸 셋끼리의 이동은 통과</b>({@code SITTING_SCANNING} ↔ {@code SITTING_ATTACKING}
 *       ↔ {@code SITTING_FLAMING}). 앉은 채로 포효하고 불 뿜는 것은 막을 일이 아니고, 막으면
 *       드래곤이 가만히 앉아만 있어 고정이 <b>연출 없는 공짜 시간</b>이 된다</li>
 *   <li><b>{@code DYING} 은 반드시 통과.</b> 막으면 드래곤이 <b>죽지 않는다</b> — 경험치도
 *       알도 출구 포털도 안 나오고 회차가 거기서 멈춘다. 이 믹스인에서 가장 위험한 한 줄이다</li>
 *   <li><b>{@code HOVERING} 도 통과.</b> 「최후의 저항」이 드래곤을 재우는 칸이다
 *       ({@code DragonLastStand.hold}). 여기서 막으면 그 보스전이 열리지 않는다. 깃발 쪽에서도
 *       이미 걸러지지만({@code DragonPerch.standDown}) 한 겹 더 둔다</li>
 *   <li><b>나머지 전부 거절</b> — {@code TAKEOFF}·{@code CHARGING_PLAYER}·{@code STRAFE_PLAYER}·
 *       {@code HOLDING_PATTERN}·{@code LANDING}·{@code LANDING_APPROACH}</li>
 * </ul>
 *
 * <p>⚠ 3번 길은 막혀도 바닐라가 {@code sittingDamageReceived} 를 <b>먼저</b> 0 으로 되돌린다
 * ({@code setPhase} 호출보다 앞줄이다). 곧 고정 중에 25% 를 때려 넣으면 그 몫은 그냥 버려진다 —
 * 사람에게는 「앉으면 확실히 그만큼은 앉아 있다」로 보이므로 의도한 쪽이다.
 *
 * <h2>클라이언트에서는 한 번도 막지 않는다</h2>
 *
 * <p>{@code setPhase} 는 서버만 부르는 메서드가 아니다 —
 * {@code EnderDragon.onSyncedDataUpdated} 가 {@code DATA_PHASE} 를 받을 때
 * <b>클라이언트에서도</b> 부른다. 거기서 막으면 서버가 고정을 푼 뒤에도 클라이언트의 드래곤이
 * 앉은 자세로 남아, 사람 화면에만 앉아 있는 드래곤이 생긴다. 그래서 {@code dragon.level()} 로
 * 먼저 가른다.
 *
 * <h2>대상에 재정의 함정이 없다</h2>
 *
 * <p>{@code EnderDragonPhaseManager} 는 26.3 의 두 jar(공용·클라이언트 전용) 어디에서도
 * <b>상속되지 않는다</b> — 두 jar 의 클래스 7,762 + 1,000여 개를 전부 풀어 상위 클래스를 훑어
 * 확인했다. 「재정의되는 메서드에 믹스인을 걸면 조용히 죽는다」는 이 저장소의 함정에 걸리지
 * 않는다.
 *
 * <h2>refmap 이 없다</h2>
 *
 * <p>{@code sharedfate.mixins.json} 에 refmap 이 없어 <b>서술자가 틀려도 빌드가 통과</b>한다.
 * 다만 {@code injectors.defaultRequire} 가 1 이라 대상을 못 찾으면 <b>믹스인을 붙이는 순간</b>
 * 터진다 — 조용히 지나가지는 않는다. {@code DragonPerchTest} 가 서술자와 상수 이름을 반사로
 * 못박는다.
 */
@Mixin(EnderDragonPhaseManager.class)
public abstract class EnderDragonPerchHoldMixin {

	/**
	 * ⚠ 서버인지 가르기 위해서만 쓴다.
	 *
	 * <p>{@code EnderDragonPhaseManager} 의 {@code private final} 칸이다. 이름이 바뀌면
	 * 믹스인을 붙이는 순간 터진다.
	 */
	@Shadow
	@Final
	private EnderDragon dragon;

	/**
	 * 고정 중이면 앉은 칸을 떠나는 모든 이동을 거절한다.
	 *
	 * <p>첫 줄이 {@code volatile boolean} 한 번 읽기다. 시련을 켜지 않은 판 — 곧 거의 모든
	 * 판 — 에서는 그 한 줄이 비용의 전부다.
	 */
	@Inject(
			method = "setPhase(Lnet/minecraft/world/entity/boss/enderdragon/phases/"
					+ "EnderDragonPhase;)V",
			at = @At("HEAD"),
			cancellable = true)
	private void sharedfate$holdThePerch(EnderDragonPhase<?> next, CallbackInfo callback) {
		if (!DragonPerch.holdsTakeoff()) {
			return;
		}
		if (dragon.level().isClientSide()) {
			// 클라이언트의 페이즈는 서버가 보낸 값을 그대로 받는 자리다. 여기서 막으면 사람
			// 화면에만 앉아 있는 드래곤이 남는다.
			return;
		}
		if (sharedfate$allowed(next)) {
			return;
		}
		callback.cancel();
	}

	/**
	 * 고정 중에도 통과시켜야 하는 칸인가.
	 *
	 * <p>허용으로 적는다. 금지 목록으로 적으면 판이 올라 칸이 하나 늘 때 <b>그 칸만 샌다</b>.
	 */
	@Unique
	private static boolean sharedfate$allowed(EnderDragonPhase<?> next) {
		// 죽는 길을 막으면 드래곤이 죽지 않는다. 가장 먼저 둔다.
		return next == EnderDragonPhase.DYING
				// 앉은 채로 하는 짓은 그대로 하게 둔다.
				|| next == EnderDragonPhase.SITTING_SCANNING
				|| next == EnderDragonPhase.SITTING_ATTACKING
				|| next == EnderDragonPhase.SITTING_FLAMING
				// 「최후의 저항」이 드래곤을 재우는 칸. 막으면 그 보스전이 열리지 않는다.
				|| next == EnderDragonPhase.HOVERING;
	}
}
