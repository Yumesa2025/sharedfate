package com.sharedfate.mixin;

import com.sharedfate.sync.DragonLandingDice;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonHoldingPatternPhase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * 착지 주사위에 <b>천장</b>을 둔다 — 살아 있는 크리스탈이 넷을 넘어도 확률이 1/6 아래로는
 * 안 내려간다.
 *
 * <p>사람 말: <b>「엔더드래곤이 진짜 착지 자체를 너무 안 함」</b> → <b>「크리스탈 영향에 천장 —
 * 최대 1/6 으로 못박아 평균 12초에 한 번 내려앉게」</b>
 *
 * <p>무엇을 왜 6 으로 자르는지, 크리스탈 0~3 개 구간이 바닐라와 같은 까닭, 시련을 끈 판에서
 * 손대지 않는 까닭은 <b>전부 {@link DragonLandingDice} 클래스 설명</b>에 있다. 여기는 그 함수를
 * 부르는 자리일 뿐이다 — 「천장이 얼마인가」를 믹스인 안에 적으면 값과 근거가 갈라진다.
 *
 * <h2>왜 {@code @ModifyArg} 인가 — 주사위를 다시 굴리지 않는다</h2>
 *
 * <p>고치려는 것이 <b>{@code nextInt} 에 넘기는 상한 하나</b>다. {@code @Inject} 로 페이즈를
 * 직접 밀면 「표적」 카드가 저질렀던 사고를 되풀이한다 — {@code setPhase} 가 부르는
 * {@code begin()} 이 {@code currentPath} 를 지우고, 바닐라는 <b>경로가 끝난 틱에만</b>
 * 「착지할까」를 굴리므로 그 틱이 영영 안 온다. 상한만 깎으면 바닐라의 주사위와 리듬이 그대로다.
 *
 * <h2>⚠ {@code ordinal = 0} 의 근거 — 이 메서드의 {@code nextInt} 는 <b>넷</b>이다</h2>
 *
 * <p>26.3 {@code DragonHoldingPatternPhase.findNewTarget} 을 {@code javap -c} 로 뜯어 센 것이다.
 * 네 개 전부 {@code RandomSource.nextInt(I)I} 라 <b>서술자로는 갈라지지 않는다.</b>
 *
 * <table border="1">
 *   <caption>findNewTarget 안의 nextInt 호출</caption>
 *   <tr><th>ordinal</th><th>바이트</th><th>넘기는 값</th><th>무엇을 고르는가</th></tr>
 *   <tr><td><b>0</b></td><td>70</td><td><b>{@code alive + 3}</b></td>
 *       <td><b>{@code LANDING_APPROACH} — 우리가 노리는 것</b></td></tr>
 *   <tr><td>1</td><td>167</td><td>{@code (int)(거리²/512 + 2)}</td><td>돌진 문 ①</td></tr>
 *   <tr><td>2</td><td>185</td><td>{@code alive + 2}</td><td>돌진 문 ②</td></tr>
 *   <tr><td>3</td><td>236</td><td>{@code 8}</td><td>{@code clockwise} 뒤집기</td></tr>
 * </table>
 *
 * <p>ordinal 을 하나라도 밀면 <b>엉뚱한 것이 바뀌고 로그에는 아무 흔적이 없다.</b> 1·2 를 물면
 * 돌진 빈도가 바뀌고 3 을 물면 드래곤이 도는 방향이 바뀐다. {@code DragonLandingDiceTest} 가
 * 바닐라 바이트코드에서 네 개를 다시 세어 이 표를 못박는다.
 *
 * <h2>대상에 재정의 함정이 없다</h2>
 *
 * <p>{@code DragonHoldingPatternPhase} 는 26.3 의 두 jar(공용·클라이언트 전용)에서
 * <b>상속되지 않는다</b> — 클래스 11,383 개를 전부 풀어 이름을 참조하는 파일이
 * {@code EnderDragonPhase}(등록표)와 자기 자신뿐인 것을 확인했다. 게다가
 * {@code findNewTarget} 은 <b>{@code private}</b> 이라 애초에 재정의될 수 없다. 「재정의되는
 * 메서드에 믹스인을 걸면 조용히 죽는다」는 이 저장소의 함정에 걸리지 않는다.
 *
 * <h2>⚠⚠ 판을 {@code @Shadow} 로 알아내려다 한 번 죽었다</h2>
 *
 * <p>처음에는 {@code @Shadow @Final protected EnderDragon dragon;} 을 적었다. {@code javap} 로
 * {@code protected final} 인 것까지 확인했는데도 <b>믹스인 적용 단계에서 죽었다</b> —
 * {@code InvalidMixinException: @Shadow field dragon was not located in the target class
 * DragonHoldingPatternPhase}.
 *
 * <p><b>{@code @Shadow} 는 「대상 클래스 자신에 선언된」 칸만 붙인다.</b> {@code dragon} 은
 * 상위 클래스 {@code AbstractDragonPhaseInstance} 의 칸이고 이 클래스는 물려받아 쓸 뿐이다
 * (칸 선언 위치를 {@code javap -p} 로 확인했다). 접근성이 아니라 <b>선언 위치</b>가 문제다.
 *
 * <p>그래서 판은 {@link AbstractDragonPhaseInstanceAccessor} 로 꺼낸다 — 그쪽은 <b>칸을 선언한
 * 상위 클래스</b>를 대상으로 삼으므로 같은 함정에 걸리지 않고, 그 아래 모든 페이즈가 그
 * 인터페이스를 갖게 되어 {@code this} 를 그대로 캐스팅할 수 있다. 서버는 이 저장소가 늘 쓰는 길
 * ({@code level().getServer()} — {@code NaturalSpawnerRateMixin} 등)로 {@code DragonLandingDice}
 * 안에서 얻는다.
 *
 * <p>⚠ 이 사고의 <b>진짜 값은 증상이 조용하지 않았다는 것이 아니라 엉뚱한 데서 터졌다는 것</b>
 * 이다. 믹스인이 못 붙으면 {@code DragonHoldingPatternPhase} 를 <b>불러오는 모든 길</b>이 함께
 * 넘어져 {@code EnderDragonPhase} 의 정적 초기화가 깨지고, 드래곤을 쓰는 남의 시험 넷이
 * {@code NoClassDefFoundError} 로 떨어졌다. 이 파일을 고치는 사람은 그 넷을 먼저 의심하지 말 것.
 *
 * <h2>클라이언트에서는 돌지 않는다</h2>
 *
 * <p>{@code findNewTarget} 은 {@code doServerTick(ServerLevel)} 안에서만 불린다(호출 자리가
 * 하나인 것을 {@code javap -c} 로 확인했다). 그래도 {@link DragonLandingDice#cap} 이
 * {@code ServerLevel} 인지 먼저 묻는다 — 판별을 한 곳에 두면 다음 판에서 호출 자리가 늘어도
 * 샐 데가 없다.
 *
 * <h2>refmap 이 없다</h2>
 *
 * <p>{@code sharedfate.mixins.json} 에 refmap 이 없어 <b>서술자가 틀려도 빌드가 통과</b>한다.
 * {@code injectors.defaultRequire} 가 1 이라 대상을 못 찾으면 붙이는 순간 터지지만, 그것은
 * 「못 찾았을 때」뿐이고 <b>엉뚱한 것을 찾았을 때는 조용하다</b>. 그래서
 * {@code DragonLandingDiceTest} 가 서술자와 ordinal 을 반사로 못박는다.
 */
@Mixin(DragonHoldingPatternPhase.class)
public abstract class DragonHoldingPatternLandingMixin {

	/**
	 * 착지 주사위의 상한을 {@link DragonLandingDice#cap} 에 한 번 물어본다.
	 *
	 * <p>바꾸지 않을 때는 받은 값을 <b>그대로</b> 돌려준다. 시련을 끈 판에서는 언제나 그쪽이다.
	 *
	 * <p>드래곤은 <b>읽기만</b> 한다({@code level()} 한 번). {@code setPhase}·{@code setTarget} 을
	 * 부르는 순간 「표적」 카드의 사고가 돌아온다(클래스 설명).
	 */
	@ModifyArg(
			method = "findNewTarget(Lnet/minecraft/server/level/ServerLevel;)V",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/util/RandomSource;nextInt(I)I",
					ordinal = 0),
			index = 0)
	private int sharedfate$capLandingDice(int bound) {
		// ⚠ @Shadow 로 dragon 을 보지 않는다 — 상위 클래스에 선언된 칸이라 붙지 않는다(클래스 설명).
		EnderDragon dragon = ((AbstractDragonPhaseInstanceAccessor) this).sharedfate$dragon();
		return DragonLandingDice.cap(dragon == null ? null : dragon.level(), bound);
	}
}
