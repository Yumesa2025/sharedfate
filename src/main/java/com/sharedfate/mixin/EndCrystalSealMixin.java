package com.sharedfate.mixin;

import com.sharedfate.sync.TrialCrystalLink;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * 「연결된 수정」이 잠근 크리스탈 <b>하나</b>에 들어오는 <b>모든</b> 피해를 거절한다.
 *
 * <h2>왜 믹스인이어야 하는가 — 바닐라 무적 칸으로는 안 된다</h2>
 *
 * <p>앞선 구현은 {@code Entity.setInvulnerableTime} 으로 보호막을 만들었다. 26.3 에서 그 칸이
 * 실제로 읽히기는 한다({@code isTemporarilyInvulnerable} → {@code isInvulnerable} →
 * {@code isInvulnerableToBase}). 문제는 <b>그 칸이 우리 것이 아니라는 것</b>이다 — 개체 하나에
 * 한 칸뿐이고 바닐라도 다른 카드도 같은 칸에 쓴다. 이 저장소만 해도
 * {@code TrialCrystalRevive.sweep} 이 판의 모든 크리스탈을 0 으로 민다. 까닭과 나머지 두 가지는
 * {@link TrialCrystalLink} 클래스 설명에 바이트코드와 함께 적어 두었다.
 *
 * <p>그래서 막는 일을 <b>우리 칸</b>({@link TrialCrystalLink#sealedCrystal()})으로 옮기고, 실제
 * 거절은 {@code EndCrystalGuardMixin} 이 이미 증명한 자리에서 한다.
 *
 * <h2>{@code EndCrystalGuardMixin} 과 무엇이 다른가</h2>
 *
 * <p>같은 메서드의 같은 {@code HEAD} 에 붙는 형제다. 자리를 고른 근거 — {@code hurtServer} 가
 * {@code EndCrystal} 자신이 선언한 {@code final} 이라 재정의 함정이 없다는 것, 피해량을 쓰지
 * 않으므로 「깎기」가 아니라 「통째로 거절」뿐이라는 것, {@code false} 가 바닐라의 「무적이라
 * 안 맞았다」와 같은 값이라는 것 — 은 전부 그쪽 설명에 적혀 있고 여기서도 그대로다.
 *
 * <p>다른 것은 <b>범위</b> 둘이다.
 *
 * <ul>
 *   <li>저쪽은 <b>판 전체</b>({@code boolean} 깃발), 이쪽은 <b>크리스탈 하나</b>({@code UUID})</li>
 *   <li>저쪽은 <b>{@code #minecraft:is_projectile} 만</b>, 이쪽은 <b>전부</b>. 저쪽은 「활로는
 *       안 되니 올라가라」라 근접과 폭발을 반드시 통과시켜야 하지만, 이쪽 카드가 약속한 것은
 *       「그동안은 어떤 피해도 통하지 않습니다」다. 하나만 잠그고 시한이 있으므로 「깰 방법이
 *       아예 없어진다」가 되지 않는다</li>
 * </ul>
 *
 * <p>둘이 같은 크리스탈에 함께 걸려도 결과가 같다 — 어느 쪽이 먼저 취소하든 {@code false} 다.
 *
 * <h2>운영자 탈출구는 바닐라가 열어 둔 그 둘</h2>
 *
 * <p>{@code Entity.isInvulnerableToBase} 가 무적을 뚫어 주는 경우는 26.3 에서 정확히 둘이다 —
 * {@code #minecraft:bypasses_invulnerability}({@code out_of_world}·{@code generic_kill})와
 * {@code DamageSource.isCreativePlayer()}. <b>글자 그대로 같은 둘을 여기서도 통과시킨다.</b>
 * 우리 봉인이 바닐라 무적보다 촘촘해지면 운영자가 치울 길이 없어지고, 이 모드에서 「치울 수 없는
 * 물건」은 난이도가 아니라 고장이다.
 *
 * <h2>기한이 지난 봉인은 없는 것으로 본다</h2>
 *
 * <p>{@link TrialCrystalLink#sealHolds(long)} 가 죽은 사람 스위치다. 실행기가 매 틱 기한을
 * 미루고, 미루기를 멈추면 봉인이 스스로 열린다. 바닐라 무적 칸을 버리면서 잃은 성질
 * ({@code Entity.commonTick} 이 매 틱 1씩 깎아 주던 것)을 대신하는 자리라, <b>여기를 빼면
 * 영영 안 깨지는 크리스탈이 생긴다.</b>
 *
 * <p>시계로 {@code level.getGameTime()} 을 쓴다. 우리가 안 도는 때를 재는 장치라 우리가 넘기는
 * 값으로는 잴 수 없고, {@code DragonTrialManager} 가 {@code now = end.getGameTime()} 으로
 * 시작하므로 실행기가 적는 기한과 같은 눈금이다.
 *
 * <h2>연출은 여기서 내지 않는다</h2>
 *
 * <p>막았다는 것은 {@link TrialCrystalLink#noteDeflected()} 에 <b>적어만</b> 두고, 파티클과
 * 소리는 다음 틱에 실행기가 낸다. 피격 경로에서 직접 내면 연쇄 폭발 한 번에 수십 번 나가고,
 * 소리는 사람마다 그 자리에서 내야 하는데 여기에는 팀 명단이 없다.
 *
 * <h2>비용</h2>
 *
 * <p>첫 줄이 {@code volatile} 참조 한 번 읽기다. 이 카드가 뜨지 않은 판에서는 그 한 줄이 비용의
 * 전부다 — 크리스탈은 사람이 손에 들고 다니며 터뜨리는 물건이라 엔드 밖에서도 지나간다.
 *
 * <h2>refmap 이 없다</h2>
 *
 * <p>{@code sharedfate.mixins.json} 에 refmap 이 없어 <b>대상 서술자가 틀려도 빌드가 그냥
 * 통과</b>하고 발화 시점에 터진다. 서술자는 {@code EndCrystalGuardMixin} 과 <b>글자 하나까지
 * 같은 것</b>을 쓴다 — 그쪽은 {@code TrialCrystalGuardTest} 가 반사와 클래스 파일로 못박고
 * 있으므로, 둘이 같은 한 이 자리도 함께 지켜진다.
 */
@Mixin(EndCrystal.class)
public abstract class EndCrystalSealMixin {

	/**
	 * 봉인된 크리스탈이면 이 한 방을 통째로 거절한다.
	 *
	 * <p>순서가 규칙이다. <b>가장 싼 것부터</b> 본다 — 봉인이 아예 없으면 칸 하나 읽고 끝나야
	 * 하고, 개체 비교와 기한 검사는 봉인이 있을 때만 치른다.
	 */
	@Inject(
			method = "hurtServer(Lnet/minecraft/server/level/ServerLevel;"
					+ "Lnet/minecraft/world/damagesource/DamageSource;F)Z",
			at = @At("HEAD"),
			cancellable = true)
	private void sharedfate$refuseSealed(ServerLevel level, DamageSource source, float amount,
			CallbackInfoReturnable<Boolean> callback) {
		UUID sealed = TrialCrystalLink.sealedCrystal();
		if (sealed == null) {
			return;
		}
		if (!sealed.equals(((Entity) (Object) this).getUUID())) {
			return;
		}
		if (!TrialCrystalLink.sealHolds(level.getGameTime())) {
			// 기한이 지났다. 실행기가 죽었다는 뜻이므로 봉인을 없는 것으로 본다.
			return;
		}
		if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) || source.isCreativePlayer()) {
			// 바닐라 무적이 열어 두는 그 둘. 운영자가 치울 길은 반드시 남긴다.
			return;
		}
		TrialCrystalLink.noteDeflected();
		// 바닐라가 무적일 때 돌려주는 값과 같다.
		callback.setReturnValue(false);
	}
}
