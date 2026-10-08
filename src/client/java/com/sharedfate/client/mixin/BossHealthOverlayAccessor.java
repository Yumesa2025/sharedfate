package com.sharedfate.client.mixin;

import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/**
 * 지금 떠 있는 보스바들. 드래곤 패턴 타이머의 시전 바가 <b>보스바들 바로 아래</b>에 서려고
 * 읽는다({@code TrialTimersHud}). 몇 개인지와 이름 폭으로 바닐라가 그리는 자리를 다시 센다
 * ({@code TrialTimersLayout.bossBarsDrawn}).
 *
 * <p>{@code events} 는 26.3 {@code BossHealthOverlay} 가 <b>직접 선언한</b> {@code private final}
 * 칸이다(상위 클래스는 {@code Object}) — 상위 클래스에 선언된 칸이면 접근자가 안 붙는다. 이 클래스를
 * 물려받는 바닐라 클래스도 없다. 둘 다 26.3 clientonly jar 를 {@code javap -p} 로 확인했다.
 * 그리는 차례는 이 맵을 도는 차례 그대로다({@code LinkedHashMap}).
 *
 * <p>읽기만 한다. 같은 클래스의 {@link BossHealthOverlayMixin}(회차 보스바의 막대만 지움)은 이름과
 * 간격을 그대로 두므로 자리 셈이 바뀌지 않는다.
 */
@Mixin(BossHealthOverlay.class)
public interface BossHealthOverlayAccessor {
	@Accessor("events")
	Map<UUID, LerpingBossEvent> sharedfate$events();
}
