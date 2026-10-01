package com.sharedfate.mixin;

import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.AbstractDragonPhaseInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 드래곤 페이즈가 들고 있는 <b>드래곤 개체</b>를 꺼낸다. 읽기 하나뿐이다.
 *
 * <h2>⚠⚠ 왜 {@code @Shadow} 가 아니라 접근자인가 — 이 저장소가 처음 겪은 함정이다</h2>
 *
 * <p><b>{@code @Shadow} 는 「대상 클래스 자신에 선언된」 칸만 붙인다.</b> 상위 클래스에 선언된
 * 칸은 아무리 {@code protected} 라도 안 붙고, 증상이 <b>빌드가 아니라 믹스인 적용 단계의
 * 예외</b>다.
 *
 * <pre>{@code
 * InvalidMixinException: @Shadow field dragon was not located in the target class
 *     net.minecraft.world.entity.boss.enderdragon.phases.DragonHoldingPatternPhase.
 *     No refMap loaded.
 *   at MixinPreProcessorStandard.attachFields
 * }</pre>
 *
 * <p>{@code DragonHoldingPatternLandingMixin} 이 {@code DragonHoldingPatternPhase} 에
 * {@code @Shadow protected EnderDragon dragon;} 을 적었다가 실제로 그렇게 죽었다. 그때 함께
 * 넘어지는 것이 <b>그 클래스를 불러오는 모든 길</b>이라, {@code EnderDragonPhase} 의 정적
 * 초기화가 걸려 <b>드래곤을 쓰는 시험 넷이 영문 모를 {@code NoClassDefFoundError} 로</b> 떨어졌다.
 *
 * <p>{@code javap -p} 로 선언 위치를 확인한 사실이 이것이다(26.3).
 *
 * <ul>
 *   <li>{@code AbstractDragonPhaseInstance} — <b>{@code protected final EnderDragon dragon;}</b>
 *       을 선언한다. 그래서 <b>여기</b>를 대상으로 삼으면 칸이 대상 클래스 자신에 있다</li>
 *   <li>{@code DragonHoldingPatternPhase} — 칸이 넷인데({@code NEW_TARGET_TARGETING} ·
 *       {@code currentPath} · {@code targetLocation} · {@code clockwise}) <b>{@code dragon} 이
 *       없다.</b> 물려받아 쓰는 것뿐이다</li>
 *   <li>{@code DragonPhaseInstance} 인터페이스에도 <b>드래곤을 돌려주는 메서드가 없다</b> —
 *       열한 개를 전부 세었다. 그래서 바닐라 공개 길로는 꺼낼 수 없다</li>
 * </ul>
 *
 * <p>대상을 상위 클래스로 올리면 그 아래 <b>모든 페이즈</b>가 이 인터페이스를 갖게 되므로,
 * {@code DragonHoldingPatternPhase} 의 인스턴스를 그대로 캐스팅할 수 있다.
 *
 * <h2>읽기만 만든다</h2>
 *
 * <p>칸이 {@code final} 이고, 드래곤을 <b>바꿀</b> 일이 이 모드에 없다. 쓰는 접근자를 함께
 * 만들어 두면 다음 사람이 「표적」 카드의 사고(드래곤을 밀었다가 <b>착지를 아예 없앤</b> 일)를
 * 되풀이할 문을 열어 두는 셈이다.
 */
@Mixin(AbstractDragonPhaseInstance.class)
public interface AbstractDragonPhaseInstanceAccessor {

	/** 이 페이즈가 붙어 있는 드래곤. {@code DragonHoldingPatternLandingMixin} 이 판을 알려고 쓴다. */
	@Accessor("dragon")
	EnderDragon sharedfate$dragon();
}
