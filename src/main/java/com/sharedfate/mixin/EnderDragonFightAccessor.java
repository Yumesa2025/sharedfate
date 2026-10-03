package com.sharedfate.mixin;

import net.minecraft.world.level.dimension.end.EnderDragonFight;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 바닐라가 들고 있는 <b>「이 전투는 끝났다」 깃발</b>을 꺼낸다. 읽기 하나뿐이다.
 *
 * <h2>왜 이 파일이 생겼나 — 2026-10-01 의 무한 고리</h2>
 *
 * <p>사람 말: <b>「엔더드래곤 잡앗는데 시련을 시작합니다 가 떳어. 무한으로 확인 눌러도」</b>.
 * 드래곤을 잡고도 엔드에 서 있으면 {@code DragonTrialManager.detectArrival} 이 「세션 없는
 * 팀원이 엔드에 있다」로 보고 수락창을 띄우고, 확인하면 <b>죽은 드래곤으로 전투가 열리고 그
 * 자리에서 「처치」로 판정</b>되어 다시 수락창이 떴다(시험 서버 로그 167~190줄, 1초에 두세 바퀴).
 *
 * <p>고리를 끊으려면 <b>「이 월드의 드래곤전이 이미 끝났는가」</b>를 물을 수단이 필요하고,
 * 추측하지 않고 26.3 {@code EnderDragonFight} 를 {@code javap -c} 로 읽어 고른 것이 이 칸이다.
 *
 * <h2>26.3 바이트코드에서 확인한 것</h2>
 *
 * <p>{@code javap -p} 로 <b>{@code EnderDragonFight} 자신에 선언된 칸</b>임을 먼저 확인했다 —
 * {@code private boolean dragonKilled;} 가 그 클래스의 칸 목록에 있고 상위
 * {@code SavedData} 에는 없다. ⚠ <b>이것을 먼저 봐야 한다.</b> 상위 클래스의 칸에
 * {@code @Shadow}·{@code @Accessor} 를 걸면 <b>빌드가 아니라 믹스인 적용 단계에서</b> 죽고,
 * 2026-10-01 에 {@code AbstractDragonPhaseInstanceAccessor} 가 그 함정에 걸렸다(그쪽 설명).
 *
 * <p>칸이 뜻하는 것도 바이트코드로 확인했다.
 *
 * <ul>
 *   <li>{@code tick()} 첫 줄이 {@code dragonEvent.setVisible(!dragonKilled)} 다 — 곧
 *       <b>보스바가 사라지는 바로 그 깃발</b>이고 「지금 전투 중인가」의 바닐라 쪽 답이다</li>
 *   <li>{@code setDragonKilled(dragon)} 이 출구 포털·게이트웨이를 세우고
 *       {@code hasPreviouslyKilledDragon = true} 와 함께 <b>{@code dragonKilled = true}</b> 로
 *       적는다</li>
 *   <li>⚠⚠ <b>{@code setRespawnStage(END)} 가 {@code dragonKilled = false} 로 되돌리고
 *       {@code createNewDragon()} 을 부른다.</b> 곧 <b>엔드 크리스탈 넷으로 되살리는 바닐라
 *       기능이 이 깃발을 스스로 내린다</b> — 이 칸을 쓰면 「한 번 끝났으면 영영 안 뜬다」가
 *       되지 않는다</li>
 *   <li>{@code scanState()} 는 살아 있는 드래곤을 찾으면 {@code false}, 못 찾으면
 *       {@code true} 로 적고, 그 뒤에 {@code if (!hasPreviouslyKilledDragon && dragonKilled)
 *       dragonKilled = false} 로 한 번 더 되돌린다 — <b>갓 만든 월드는 드래곤이 아직 없어도
 *       거짓</b>이다. {@code createDefault()} 도 {@code dragonKilled = false} 로 시작한다
 *       ({@code iconst_1 iconst_0 iconst_0} 세 깃발)</li>
 * </ul>
 *
 * <h2>⚠ {@code hasPreviouslyKilledDragon()} 을 쓰지 않은 까닭</h2>
 *
 * <p>그쪽은 <b>공개 메서드라 믹스인도 필요 없었다</b>({@code public boolean
 * hasPreviouslyKilledDragon()}). 그런데 그 칸을 거짓으로 되돌리는 자리가 바이트코드에
 * <b>한 곳도 없다</b> — 한 번 참이 되면 그 월드에서 영영 참이다. 그것으로 가르면
 * <b>크리스탈로 되살린 드래곤에도 수락창이 다시 뜨지 않는다.</b> 「바닐라 되살리기를 막지
 * 마라」가 이 선택의 근거다.
 *
 * <p>출구 포털({@code exitPortalLocation} · {@code hasActiveExitPortal()})도 후보였지만 둘 다
 * {@code private} 이라 믹스인이 한 벌 더 필요하고, <b>되살린 뒤에도 포털은 그대로 남는다</b> —
 * 같은 「영영」 함정이다. 그래서 {@code dragonKilled} 하나로 간다.
 *
 * <h2>읽기만 만든다</h2>
 *
 * <p>이 깃발을 <b>쓰는</b> 접근자를 함께 만들어 두면 다음 사람이 바닐라 전투 상태를 우리 손으로
 * 적을 문을 여는 셈이다. 그 칸은 월드 저장 파일({@code ender_dragon_fight} SavedData)에 남으므로
 * 잘못 적으면 <b>서버를 껐다 켜도 안 사라진다.</b>
 */
@Mixin(EnderDragonFight.class)
public interface EnderDragonFightAccessor {

	/**
	 * 이 전투가 이미 끝났는가. {@code DragonTrialManager.endFightLive} 가 읽는 유일한 자리.
	 *
	 * <p>⚠ 「이 월드에서 드래곤을 잡아 본 적이 있는가」가 아니다. <b>지금 도는 전투</b>의
	 * 깃발이고, 크리스탈로 되살리면 바닐라가 스스로 내린다(클래스 설명).
	 */
	@Accessor("dragonKilled")
	boolean sharedfate$dragonKilled();
}
