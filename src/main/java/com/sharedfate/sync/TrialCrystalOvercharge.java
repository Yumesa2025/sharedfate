package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.CrystalOvercharge} 실행기 — 수정 과충전.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p>무작위 크리스탈 하나가 붉게 달아오른다.
 *
 * <ul>
 *   <li>15초({@code fuseTicks}) 안에 <b>부수면</b> 아무 일도 없다</li>
 *   <li>못 부수면 그 크리스탈이 무작위 한 명에게 <b>구체를 던진다</b> — 10초({@code beamTicks})
 *       동안 <b>초에 한 발씩 열 발</b>, 맞으면 발당 {@code damagePerSecond}</li>
 *   <li>구체는 <b>블록으로 가리면 막힌다</b> — 숨는 것이 이 카드가 요구하는 행동이다.
 *       <b>쇠창살만은 막지 않는다</b>(아래 「쇠창살은 막지 않는다」)</li>
 *   <li>볼리가 끝나거나 제때 부수면 20초({@code restTicks}) 뒤에 다음 크리스탈이 달아오른다</li>
 * </ul>
 *
 * <h2>⚠ 벌이 빔이었다가 구체가 됐다 — 바뀐 것은 모양뿐이다</h2>
 *
 * <p>전에는 같은 자리에서 <b>10초 동안 빔</b>을 걸고 초당 {@code damagePerSecond} 를 넣었다.
 * 사람이 실제로 플레이하고 <b>「공격이 가시성이 진짜 안 좋다. 차라리 구체를 던져서 맞춰서
 * 딜한다는 것처럼 해야 잘 보이겠다」</b>고 했다. 지속 피해는 <b>「무엇에 맞고 있는지」가 전달되지
 * 않는다</b> — 체력이 조금씩 줄지만 화면에는 가느다란 선 하나뿐이라, 맞는 사람도 나머지 셋도
 * 원인을 못 읽는다.
 *
 * <p>그래서 <b>피해를 한 덩이씩 끊어 던진다.</b> 초에 한 발이라 들어오는 양도 리듬도 그대로이고,
 * 바뀐 것은 <b>「날아오는 것이 보이고 → 맞으면 아프다」</b>는 인과가 눈에 보인다는 것뿐이다.
 * 15초 도화선과 「못 부수면 벌이 온다」는 구조도, 아래의 모든 금지(넉백 없음·한 사람만·방어구를
 * 지나가지 않음)도 그대로다.
 *
 * <p><b>총 피해 예산은 적힌 값에서 그대로 나온다.</b> {@code beamTicks / SECOND_TICKS} 발 ×
 * 발당 {@code damagePerSecond} = 카드 값 그대로다({@link #volleyTotalDamage}). 바꿀 때는 빔이던
 * 때의 「초당 3 × 10초 = 30」과 <b>같은 곱</b>인 10발 × 3 이었고, 2026-10-04 에 사람이 「딜이
 * 약하다」고 해서 카드 값이 6 이 되어 지금은 <b>10발 × 6 = 60</b>이다. 발수와 발당 피해를
 * 따로 적어 두지 않는 것이 요점이다 — 따로 두면 카드 값을 고칠 때 한쪽만 고쳐져 예산이 조용히
 * 갈라진다. 6 으로 올릴 때 이 파일은 숫자 하나 안 고쳤다.
 *
 * <h2>⚠ 「표적」처럼 5발 × 6 으로 바꾸지 않았다</h2>
 *
 * <p>구체를 먼저 쓴 카드가 「표적」({@link TrialDragonFocus})이고 그쪽은 <b>5발 발당 6</b>이다.
 * 합계 30 을 지킨 채 같은 숫자로 맞추고 싶었지만, 그러면 발당 피해가 카드 칸
 * ({@code damagePerSecond} 3)과 <b>갈라진다.</b> {@code TrialRisks.worstCaseTickDamage} 가 이
 * 카드를 「{@code damagePerSecond} × 1」로 세고 있으므로 그 셈이 <b>거짓</b>이 된다 — 즉사
 * 조합을 막는 안전 시험이 실제보다 절반을 보게 된다. 발당 피해를 카드 칸과 <b>따로</b> 정할
 * 사람은 {@code TrialRisks} 와 {@code TrialRisksTest.shapeOf} 를 <b>같이</b> 고쳐야 한다.
 *
 * <p>2026-10-04 에 발당 6 이 된 것은 이 이야기와 다르다. <b>카드 칸 자체</b>를 6 으로 올렸고
 * 발수는 그대로 열이라, {@link #orbDamage} 가 돌려주는 한 발과 {@code worstCaseTickDamage} 가
 * 세는 「6 × 1」이 같은 칸에서 나온다. 합계가 30 에서 60 으로 바뀐 것은 사람이 시킨 일이다.
 *
 * <p>그래서 여기는 <b>「초에 한 발, 한 발이 적힌 초당 피해」</b>다. 한 틱에 닿는 구체가 언제나
 * 하나인 것도 그 셈을 지키려는 것이고, {@link #ORB_FLIGHT_TICKS} 가 발 간격보다 짧은 이유가
 * 그것이다.
 *
 * <h2>진짜 투사체 엔티티를 쓰지 않는다 — 「표적」·「기둥 화염구」와 같은 답이다</h2>
 *
 * <p>구체는 <b>파티클로 그리고 착탄은 직접 계산</b>한다. {@link TrialFireball} 과
 * {@link TrialDragonFocus} 가 이미 그렇게 하고 있고, 바닐라 투사체가 저마다 <b>우리가 끌 수
 * 없는 것</b>을 딸고 오기 때문이다(26.3 바이트코드로 확인했다).
 *
 * <ul>
 *   <li>{@code DragonFireball} — 착탄 자리에 {@code AreaEffectCloud} 장판을 남긴다
 *       ({@code onHit} 이 {@code setCustomParticle(DRAGON_BREATH)} 까지 세운다). 장판을 쓰는
 *       카드가 따로 있어 둘이 같은 화면에 있으면 구별할 수 없다</li>
 *   <li>{@code ShulkerBullet} — 맞히면 {@code MobEffects.LEVITATION} 을 건다. 엔드 중앙 섬은
 *       사방이 허공이고 <b>공유 체력이라 한 사람의 낙사가 팀 전멸</b>이다. 게다가 <b>유도</b>라
 *       「가리면 막힌다」가 성립하지 않는다</li>
 *   <li>화염구 쪽 — 블록 파괴와 불이 {@code mobGriefing} 에 묶여 <b>우리만 끌 수가 없다</b>
 *       (「기둥 화염구」가 바닐라 화염구를 안 띄우는 이유가 그것이다)</li>
 *   <li>진짜 {@code EndCrystal} — 「오브젝트 파도」가 셋 때문에 못 썼다. 그중 둘이 여기도
 *       걸린다: {@code EnderDragon.checkCrystals} 가 <b>클래스로</b> 찾아 드래곤을 회복시키고,
 *       깨질 때 폭발이 난다</li>
 * </ul>
 *
 * <p>대가는 우리가 그려야 한다는 것뿐이고, 그 값은 <b>이미 지불돼 있다</b> —
 * {@link TrialWarning} 의 「거리 제한을 끄고 보낸다」와 긴 형태 파티클이 그것이다.
 *
 * <h2>구체는 사람을 따라간다 — 옆걸음으로 피하는 카드가 아니다</h2>
 *
 * <p>「표적」은 <b>발사 순간에 조준을 얼린다.</b> 그 카드가 요구하는 행동이 「제자리에서 옆으로
 * 비키기」라서다. <b>여기서는 일부러 그렇게 하지 않는다.</b> 이 카드의 대응은 <b>「크리스탈을
 * 부수기」와 「블록 뒤에 숨기」</b>이고(카드 설명에 적힌 그대로다), 구체를 옆걸음으로 피하게 만들면
 * <b>15초 도화선의 압력이 통째로 사라진다</b> — 걸어 다니기만 해도 60 이 0 이 되므로 아무도
 * 기둥으로 가지 않는다. 벌의 <b>모양</b>만 바꾸는 것이 이번 변경의 범위다.
 *
 * <p>그래서 구체의 끝점은 <b>매 틱 그 사람의 현재 자리</b>다. 곧 <b>빔을 한 덩이씩 끊어 보내는
 * 것</b>이고, 그려 놓은 궤적이 언제나 사람에게 닿아 끝나므로 <b>거짓말을 하지 않는다.</b>
 * 비행 시간이 {@link TrialWarning#TICKS_SIDESTEP} 보다 짧은 것도 그 때문에 어긋남이 아니다 —
 * 이 카드의 예고는 비행이 아니라 <b>15초 도화선</b>이고, 그쪽이 {@code TICKS_SIDESTEP} 보다
 * 긴지는 {@code TrialRisksTest} 가 이미 붙잡고 있다.
 *
 * <h2>남은 크리스탈이 없으면 아무 일도 일어나지 않는다</h2>
 *
 * <p>이 카드는 첫 크리스탈 풀({@code POOL_FIRST_CRYSTAL})이라 <b>크리스탈이 다 깨진 뒤에도
 * 전투 내내 살아 있다.</b> 그때 {@link #choose} 가 {@code null} 을 돌려주고 FUSE 는 아무것도 하지
 * 않은 채 제자리에 머문다 — 소리도, 표식도, 구체도 없다. 「부활」 카드가 크리스탈을 되살리면 그
 * 틱에 저절로 다시 돌기 시작한다.
 *
 * <h2>달아오른 것을 어떻게 보이게 하는가 — 파티클만으로는 안 된다</h2>
 *
 * <p>크리스탈은 반경 42 기둥 꼭대기(y 76~103)에 있고 사람은 바닥에 있다. 아레나 반대편이면
 * 90 블록이 넘는다. 먼지 파티클은 <b>긴 형태로 보내면 패킷은 나가지만</b>({@link TrialWarning} 의
 * 「거리 제한을 끄고 보낸다」) 그 거리에서는 점이 너무 작아 「저기 저것이다」를 말하지 못한다.
 *
 * <p>그래서 본 신호는 <b>바닐라 크리스탈 빔을 곧게 위로 세운 빛기둥</b>이다.
 * {@code EndCrystal.setBeamTarget} 은 개체 데이터라 거리 제한이 없고,
 * {@code EndCrystalRenderer.shouldRender} 는 <b>빔이 걸려 있으면 절두체 밖이어도 참</b>을
 * 돌려준다(26.3 바이트코드로 확인). 즉 아레나 어디에서든 보이는 것이 보장된다. 빛기둥은
 * 도화선이 타는 동안 <b>자라난다</b> — 다 자라면 쏜다는 뜻이라, 「어느 것인가」와 「얼마나
 * 남았는가」를 한 신호가 같이 말한다.
 *
 * <p>붉은 먼지와 고리는 그 위에 덧칠한다. 「달아올랐다」는 색을 말하는 것은 파티클뿐이고,
 * 가까이 간 사람에게는 그쪽이 더 잘 읽힌다. 둘 다 <b>긴 형태</b>로 보낸다.
 *
 * <h2>⚠ 빛기둥은 사람을 겨누지 않는다 — 그것이 안 보였던 공격이다</h2>
 *
 * <p>전에는 볼리 동안 그 빔을 <b>사람의 눈높이로 넘겨</b> 그것이 곧 공격이었다. 그 선이 바로
 * 사람이 「안 보인다」고 한 것이므로 <b>되돌리지 말 것.</b> 지금은 볼리 동안에도 빛기둥이
 * {@link #COLUMN_MAX} 높이로 <b>곧게 위에</b> 서 있고, 뜻은 「이 크리스탈이 아직 달아올라
 * 있다」다. 공격은 구체가 말한다.
 *
 * <p>매 틱 다시 쓰는 것은 그대로다. 「부활」 카드가 {@code EnderDragonFight.resetSpikeCrystals}
 * 로 판의 빔을 한 번씩 걷지만, 우리가 매 틱 다시 쓰므로 그 한 틱만 깜빡이고 스스로 돌아온다
 * (26.3 전체에서 {@code setBeamTarget} 을 부르는 곳은 세계 생성({@code EndSpikeFeature})·소환
 * 의식({@code DragonRespawnStage})·그 정리({@code EnderDragonFight.resetSpikeCrystals}) 셋뿐이고,
 * 드래곤은 크리스탈에게 회복을 받으면서도 빔을 쓰지 않는다).
 *
 * <h2>「가리면 막힌다」는 투사체로도 공짜가 아니다 — 두 자리에서 묻는다</h2>
 *
 * <p>구체가 파티클이라 <b>바닐라가 벽을 대신 봐 주지 않는다.</b> 그래서 시선을 직접 두 번 묻는다.
 *
 * <ul>
 *   <li><b>쏘는 틱</b> — 가려져 있으면 <b>한 발도 나가지 않는다.</b> 빔이던 때 「숨은 초는
 *       통째로 빠진다」와 같은 규칙이고, 숨은 사람에게는 <b>구체가 안 날아오는 것</b>이 곧
 *       「막혔다」는 신호다</li>
 *   <li><b>닿는 틱</b> — 쏜 뒤에 숨어도 막힌다. 그 구체는 사람에게 닿지 못하고 <b>2 블록
 *       앞에서 터진다</b>({@link #BLOCKED_PULLBACK}) — 「맞았다」와 「막혔다」가 같은 그림이면
 *       안 되기 때문이다. 피해도 소리도 없다</li>
 * </ul>
 *
 * <p>판정({@link #clearShot})은 26.3 바닐라 {@code LivingEntity.hasLineOfSight(Entity)} 를
 * <b>그대로 옮긴 것에서 쇠창살 하나만 뺀 것</b>이다 — 사람의 눈에서 크리스탈의 눈높이로
 * {@code ClipContext.Block.COLLIDER}·{@code Fluid.NONE} 광선을 쏘아
 * {@code HitResult.Type.MISS} 인지를 본다. <b>몹이 표적을 보는지 판단할 때 쓰는 바로 그
 * 광선</b>이라, 「가리면 막힌다」의 「블록」이 바닐라의 「블록」과 같아진다 — 플레이어가 두 규칙을
 * 따로 배우지 않는다.
 *
 * <p>바닐라 메서드에는 <b>128 블록 상한</b>이 박혀 있고({@link #SIGHT_REACH}, 그 너머는 무조건
 * 거짓) 옮긴 판정도 같다. 아레나 반대편에서 기둥 꼭대기까지가 90 남짓이라 전투 중에는 닿지만,
 * 엔드 <b>흑요석 발판</b>(x 100)까지 나간 사람은 상한을 넘어 늘 「가려짐」이 된다. 아레나 밖으로
 * 걸어 나간 사람이 안전한 것은 이 카드의 뜻과 어긋나지 않으므로 그대로 둔다.
 *
 * <h2>쇠창살은 막지 않는다 (2026-10-08 사람 결정)</h2>
 *
 * <p>크리스탈을 고를 때 쇠창살 우리 안인지는 보지 않는다. 바닐라 판정을 그대로 쓰면 우리 안
 * 크리스탈이 골렸을 때 <b>우리 벽과 지붕이 시선을 막아</b> 사람이 어디에 서 있든 「가려짐」이
 * 되고, 볼리 열 발이 <b>한 발도 안 나갈 수 있다</b> — 카드가 운에 따라 통째로 꺼진다. 사람이
 * 「과충전이 쇠창살은 통과되게 하면 되는 거 아니야?」라고 해서 그렇게 정했다.
 *
 * <p>그래서 광선은 <b>쇠창살({@code Blocks.IRON_BARS})만 빈 칸으로 본다</b>({@link #seeThrough}).
 * 바닐라 우리도 「다시 선 쇠창살」({@code TrialCrystalGuard.cageState})이 세우는 우리도 같은
 * 블록이라 둘 다 통과한다. <b>다른 블록은 그대로 막는다</b> — 사람이 쌓은 블록 뒤에 숨는 것이
 * 여전히 이 카드의 대응이다. 우리를 사람이 쌓은 블록으로 덮으면 그 블록이 막는다.
 *
 * <p>⚠ 고르는 쪽에서 우리 안 크리스탈을 빼지 않은 것도 같은 결정이다. 빼면 우리가 있는 판에서
 * 고를 크리스탈이 줄어들고, 「다시 선 쇠창살」이 걸린 판에서는 후보가 통째로 사라질 수 있다.
 *
 * <p><b>시계는 멈추지 않는다.</b> {@code beamTicks} 는 가려져 있든 아니든 그대로 흐르므로, 숨는
 * 것이 곧 시간을 버는 것이 된다.
 *
 * <h2>피해는 한 사람에게만, 넉백 없이</h2>
 *
 * <p>공유 체력에서는 <b>팀원별 피해가 그대로 합산</b>된다({@code StatMirror.fold}). 발당 6 을
 * 넷에게 다 넣으면 한 발이 24, 열 발이면 240 이라 팀 체력 20 의 열두 배다. 그래서 무는 것은
 * 언제나 <b>한 사람</b>이고, 착탄 판정도 <b>그 구체가 노린 사람에게만</b> 묻는다 — 반경 안의
 * 아무나 때리면 넷이 모여 있는 자리에서 한 발이 즉사 카드가 된다.
 *
 * <p>피해원에 <b>실체를 달지 않는다.</b> {@code LivingEntity} 는 피해원에 엔티티나 자리가 있을
 * 때만 밀어내는데, 엔드 섬 가장자리에서 밀리면 허공이고 공유 체력이라 한 사람의 낙사가 팀
 * 전체를 끝낸다.
 *
 * <p>종류는 {@code explosion(null, null)} 이다 — 「기둥 화염구」·「종말의 비」·「착지 충격」이
 * 쓰는 것과 같다. <b>빔이던 때와 같은 피해원을 그대로 쓴다</b>(바꾸면 적힌 6 의 뜻이 달라진다).
 * ⚠ 「표적」이 쓰는 {@code magic} 을 따라가지 말 것 — 26.3 에서 {@code magic} 과
 * {@code dragonBreath} 는 <b>{@code bypasses_armor} 태그에 들어 있어</b> 방어구가 무시된다.
 * 그래서 「표적」의 6 과 이 카드의 6 은 <b>같은 자에서 재지 않은 값</b>이다 — 무장 기준 한 발이
 * 저쪽은 2.16, 이쪽은 0.94 다. 폭발 종류는 방어구와 폭발 보호가 그대로 들어 대비한 사람이 손해
 * 보지 않는다.
 *
 * <h2>크리스탈을 우리가 부수지 않는다</h2>
 *
 * <p>도화선이 끝나도 크리스탈은 그대로 선다. 달아오른 것이 구체를 던지고 계속 서 있는 것이 이
 * 카드의 그림이다 — 부숴 버리면 「못 부순 벌」이 「알아서 사라짐」이 되어 카드가 스스로를 지운다.
 *
 * <p>거꾸로 <b>늦게라도 부수면 멈춘다.</b> 볼리 중에 크리스탈이 깨지면 그 자리에서 끝나고,
 * 날고 있던 구체까지 사라진다 — 근원이 없는데 피해가 들어오는 것은 거짓말이다.
 *
 * <p>「그 크리스탈이 깨졌는가」는 <b>개체를 들고 있다가 {@code isAlive()} 를 묻는 것</b>으로
 * 안다. {@code EndCrystal.hurtServer} 는 {@code final} 이라 믹스인으로 덮을 수 없고,
 * {@link CrystalWatch#lastBreaker()} 는 「누가」만 적을 뿐 「어느 것을」은 적지 않는다. 개체를
 * 들고 있으면 {@link #clearState()} 가 월드 없이도 빛기둥을 걷을 수 있다는 이득까지 따라온다
 * ({@code TrialCrystalRevive.RAISED} 가 같은 이유로 개체를 든다).
 *
 * <h2>드래곤은 건드리지 않는다</h2>
 *
 * <p>{@code setPhase} 도 드래곤의 표적도 건드리지 않는다. 드래곤 페이즈에 끼어들었다가
 * <b>착지를 아예 안 하게</b> 된 사고가 있었다({@link TrialDragonFocus} 클래스 설명). 이 파일은
 * 드래곤에게서 아무것도 읽지 않는다 — 인자로 받기만 하는 것은 실행기들의 진입점 모양을 맞추기
 * 위해서다. 구체의 발사점이 <b>드래곤이 아니라 달아오른 크리스탈</b>인 것도 이 카드의 뜻이다:
 * 사람이 「저 크리스탈이 나를 때린다」를 읽어야 기둥으로 간다.
 */
public final class TrialCrystalOvercharge {

	// ------------------------------------------------------------------ 못박아 둔 값

	/** 1초. <b>구체 한 발의 간격</b>이자 {@code damagePerSecond} 의 「초」다. */
	static final int SECOND_TICKS = 20;

	/**
	 * 도화선 첫 틱의 빛기둥 높이(블록).
	 *
	 * <p>0 에서 자라게 하면 <b>시작한 것 자체를 못 본다.</b> 기둥 하나가 통째로 가려질 만큼은
	 * 안 되고, 옆 기둥 너머에서도 끝이 삐져나올 만큼은 되는 높이다.
	 */
	static final int COLUMN_MIN = 8;

	/**
	 * 도화선이 다 탔을 때의 빛기둥 높이(블록). 볼리 동안에도 이 높이로 곧게 서 있는다.
	 *
	 * <p>엔드 기둥은 가장 낮은 것과 가장 높은 것의 차가 서른 남짓이다. 그보다 길게 잡아야
	 * <b>낮은 기둥에서 올라온 빛기둥이 옆의 높은 기둥에 가려지지 않는다.</b> 가장 높은 기둥
	 * (y 103) 위로 이만큼 더 올라가도 엔드 건축 한계 안이다.
	 */
	static final int COLUMN_MAX = 40;

	/** 붉은 먼지를 다시 뿌리는 간격(틱). 매 틱 뿌리면 이 카드 하나가 파티클 예산을 먹는다. */
	private static final int PULSE_TICKS = 4;

	/** 도화선 첫 틱에 뿌리는 먼지 수. */
	static final int EMBER_MIN = 6;

	/** 도화선이 다 탔을 때 뿌리는 먼지 수. 「점점 달아오른다」가 이 둘 사이의 기울기다. */
	static final int EMBER_MAX = 40;

	/** 먼지를 흩뿌리는 폭(블록). 크리스탈이 2×2 라 그 몸을 감쌀 만큼만. */
	private static final double EMBER_SPREAD = 0.7;

	/** 먼지를 크리스탈 발치가 아니라 몸통 높이에 뿌린다. 구체가 떠나는 자리도 여기다. */
	private static final double EMBER_LIFT = 1.0;

	/** 크리스탈을 두르는 붉은 고리의 반경(블록). 몸(2×2) 바깥이라 모양이 파묻히지 않는다. */
	private static final double RING_RADIUS = 2.5;

	/** 물린 사람 발밑에 그리는 보라 고리의 반경(블록). 위험 범위가 아니라 이름표다. */
	private static final double VICTIM_RING_RADIUS = 1.5;

	/** 보라 고리를 다시 그리는 간격(틱). */
	private static final int VICTIM_RING_TICKS = 5;

	/**
	 * 크리스탈이 「기둥 위의 것」인지 보는 거리(블록).
	 *
	 * <p>바닐라도 되살린 것도 {@link EndPillars#crystalSeats} 와 <b>정확히 같은 좌표</b>에
	 * 서므로 넉넉한 값이다. {@code TrialCrystalRevive.SEAT_TAKEN_REACH} 와 같은 2.0 을 쓴다.
	 */
	private static final double SEAT_REACH = 2.0;

	// ------------------------------------------------------------------ 구체

	/**
	 * 한 발이 닿은 뒤 다음 발이 떠나기까지 비워 두는 틱.
	 *
	 * <p><b>0 으로 두지 말 것.</b> 발 간격이 {@link #SECOND_TICKS} 이므로 비행이 그만큼이면
	 * 앞 발이 닿는 틱에 다음 발이 떠나 <b>한 틱에 두 발이 겹칠 수 있다.</b> 그러면
	 * {@code TrialRisks.worstCaseTickDamage} 가 세는 「한 몫」이 거짓이 되고, 화면에서도 구체가
	 * 끊기지 않아 <b>빔으로 되돌아간 것처럼 보인다.</b> 한 발이 「던져서 맞는」 한 사건으로
	 * 읽히려면 사이가 비어야 한다.
	 */
	static final int ORB_GAP_TICKS = 4;

	/**
	 * 구체 한 발이 날아가는 시간(틱).
	 *
	 * <p>발 간격에서 뽑는다 — 숫자를 따로 박아 두면 {@link #SECOND_TICKS} 를 고칠 때 둘이
	 * 어긋나 마지막 발이 볼리 밖으로 밀려난다({@link #lastImpactTick} 이 그것을 잰다).
	 *
	 * <p>거리와 무관하게 고정이다. 거리로 잡으면 <b>먼 기둥이 달아올랐을 때만 안전해져</b>
	 * 카드가 기둥 운으로 갈린다(「표적」이 드래곤 위치 운을 막은 것과 같은 이유다).
	 */
	static final int ORB_FLIGHT_TICKS = SECOND_TICKS - ORB_GAP_TICKS;

	/** 구체가 발이 아니라 가슴에 닿게 하는 높이. 발밑 보라 고리와 겹치면 둘 다 안 읽힌다. */
	private static final double ORB_LIFT = 1.0;

	/** 구체 머리에 찍는 점 수. 「덩어리 하나가 날아온다」로 읽힐 만큼만. */
	private static final int ORB_HEAD_POINTS = 12;

	/** 구체 머리를 흩뿌리는 폭(블록). */
	private static final double ORB_SPREAD = 0.35;

	/**
	 * 구체 뒤에 남기는 꼬리 점 수.
	 *
	 * <p>궤적 전체를 매 틱 다시 그리면 「구체」가 아니라 「실」로 읽힌다. 꼬리는 <b>어느 쪽에서
	 * 왔는가</b>만 말하면 되므로 몇 점이면 충분하고, 기둥 꼭대기에서 바닥까지 90 블록을 24틱에
	 * 지나면 한 틱에 3~4 블록을 뛰므로 이만큼은 있어야 점이 이어져 보인다.
	 */
	private static final int ORB_TAIL_POINTS = 6;

	/** 꼬리 점 사이 거리(블록). */
	private static final double ORB_TAIL_STEP = 1.5;

	/** 떠나는 자리에 얹는 점 수. 「저 크리스탈에서 나왔다」를 말하는 것이 이 한 덩이다. */
	private static final int MUZZLE_POINTS = 8;

	/** 떠나는 덩이를 흩뿌리는 폭(블록). 크리스탈 몸(2×2)을 감쌀 만큼. */
	private static final double MUZZLE_SPREAD = 0.6;

	/** 맞은 자리에 터뜨리는 점 수. */
	private static final int IMPACT_POINTS = 16;

	/** 맞은 자리를 흩뿌리는 폭(블록). 사람 하나 두께다 — 범위 피해가 아니라는 뜻이다. */
	private static final double IMPACT_SPREAD = 0.4;

	/** 막혔을 때 터지는 점 수. 맞은 것(16)보다 적어야 둘이 구별된다. */
	private static final int BLOCKED_POINTS = 6;

	/**
	 * 막힌 구체를 사람보다 이만큼(블록) 앞에서 터뜨린다.
	 *
	 * <p>사람 자리에서 터뜨리면 <b>「맞았다」와 똑같이 보인다.</b> 피해가 없는데 같은 그림이면
	 * 「가린 게 맞나」를 알 수 없다 — 「연결된 수정」이 막힌 것을 안 보여 줘서 「버그인가」로 읽힌
	 * 적이 있다.
	 */
	static final double BLOCKED_PULLBACK = 2.0;

	/**
	 * 시선 판정의 거리 상한(블록). 넘으면 광선을 쏘지 않고 「가려짐」이다.
	 *
	 * <p>26.3 바닐라 {@code LivingEntity.hasLineOfSight} 에 박힌 {@code 128.0} 을 그대로 옮겼다.
	 * 판정을 바닐라에서 떼어 왔어도 <b>동작은 같아야</b> 하므로 늘리거나 줄이지 말 것.
	 */
	static final double SIGHT_REACH = 128.0;

	// ------------------------------------------------------------------ 걸음

	/** 이번 틱에 어느 걸음인가. */
	enum Step {
		/** 크리스탈 하나가 달아오르는 중. 여기서 깨면 벌이 없다. */
		FUSE,
		/** 달아오른 크리스탈이 한 사람에게 구체를 던지는 중. */
		VOLLEY,
		/** 다음 크리스탈까지 쉰다. */
		REST
	}

	// ------------------------------------------------------------------ 상태

	/**
	 * 날고 있는 구체 한 발.
	 *
	 * <p>{@code from} 이 엔티티가 아니라 <b>좌표</b>인 것이 요점이다. 크리스탈을 들고 매 틱 그
	 * 자리를 읽으면 깨진 크리스탈의 자리를 묻게 된다 — 떠난 자리는 떠난 그 틱에 정해진다.
	 *
	 * <p>끝점은 들지 않는다. <b>물린 사람을 따라가는 것이 이 카드의 뜻</b>이므로 매 틱 그 사람의
	 * 현재 자리를 쓴다(클래스 설명의 「구체는 사람을 따라간다」).
	 *
	 * @param targetId 이 발이 노린 사람. 착탄 판정을 그 사람에게만 묻는다
	 * @param from     떠난 순간의 크리스탈 몸통
	 * @param firedAt  떠난 위상(걸음 안에서 흐른 틱)
	 * @param landsAt  닿는 위상
	 */
	private record Orb(UUID targetId, Vec3 from, long firedAt, long landsAt) {
	}

	/**
	 * 지금 들고 있는 상태가 <b>어느 카드의 것인가</b>.
	 *
	 * <p>위험은 값(레코드)이라 상태를 들 수 없어 정적 칸을 쓴다. 판이 갈리거나 세션을 되살려
	 * 받은 틱이 달라지면 지난 판의 걸음이 그대로 이어지는데, 그러면 <b>도화선 없이 구체부터
	 * 날아오는</b> 틱이 생긴다. 받은 틱이 바뀌면 처음부터 다시 센다.
	 */
	private static long owner = Long.MIN_VALUE;

	private static Step step = Step.FUSE;

	/** 지금 걸음이 시작된 시각. <b>월드 시간이 아니라</b> {@link TrialRisks#elapsedSinceGrant} 값이다. */
	private static long stepStart;

	/**
	 * 지금 달아오른 크리스탈. 없으면 {@code null}.
	 *
	 * <p><b>개체를 그대로 든다.</b> 좌표만 적어 두면 {@link #clearState()} 에 {@code ServerLevel}
	 * 이 없어 빛기둥을 걷을 방법이 없고, 「그것이 깨졌는가」도 물을 수 없다.
	 */
	private static @Nullable EndCrystal charged;

	/** 직전에 달아올랐던 크리스탈. 연달아 같은 것을 고르지 않으려고 기억한다. */
	private static @Nullable UUID lastCharged;

	/** 지금 노려지는 사람. 볼리가 없으면 {@code null}. */
	private static @Nullable UUID victim;

	/**
	 * 이번 볼리에서 마지막으로 쏜 발 번호. 아직 없으면 {@code -1}.
	 *
	 * <p>위상만으로 판단하면 <b>같은 위상이 두 번 오는 순간 두 발이 나간다.</b>
	 * {@link TrialRisks#elapsedSinceGrant} 가 음수를 0 으로 깎으므로 월드 시간이 되감긴 판에서는
	 * 같은 틱이 이어질 수 있다. {@code TrialDragonFocus.Fired} 가 같은 이유로 있는 칸이다.
	 */
	private static int lastShot = -1;

	/**
	 * 지금 날고 있는 구체들.
	 *
	 * <p>{@link #ORB_FLIGHT_TICKS} 가 발 간격보다 짧으므로 <b>보통 한 발</b>이지만 목록으로 둔다.
	 * 값이 이상하게 적힌 카드에서 둘이 겹치더라도 앞 발이 조용히 사라지는 것보다 낫다 — 그려 놓은
	 * 궤적은 반드시 착탄이나 막힘으로 끝나야 한다.
	 */
	private static final List<Orb> ORBS = new ArrayList<>();

	private TrialCrystalOvercharge() {
	}

	// ------------------------------------------------------------------ 진입점

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자도 받는 것은 {@link TrialRisks} 의
	 * 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다. 이 카드는 <b>바닥 지점을 잡지 않으므로</b>(노리는 것이
	 * 크리스탈과 사람이다) 목록에 넣을 것이 없다.
	 *
	 * @param dragon  <b>쓰지 않는다.</b> 이 카드는 드래곤에게서 아무것도 읽지 않고 아무것도 쓰지
	 *                않는다 — 클래스 설명의 「드래곤은 건드리지 않는다」를 볼 것
	 * @param granted 카드를 받은 틱. 주기는 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.CrystalOvercharge risk) {
		if (end == null || risk == null) {
			return;
		}
		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		if (owner != granted) {
			// 남의 판에서 넘어온 상태다. 걷어 내고 도화선부터 다시 시작한다. 시작 시각을
			// 「0」이 아니라 「지금」으로 적는 것이 중요하다 — 세션을 되살리면 elapsed 가 이미
			// 수천 틱이라, 0 으로 두면 도화선을 건너뛰고 첫 틱에 구체부터 날아간다.
			clearState();
			owner = granted;
			stepStart = elapsed;
		}

		// 되살린 세션에서 stepStart 가 지금보다 뒤일 수 있다. 음수 위상은 0 으로 눌러 둔다.
		long inStep = Math.max(0L, elapsed - stepStart);

		// default 를 넣지 말 것. 걸음을 늘리면서 실행을 안 붙이면 빌드가 깨져야 한다.
		switch (step) {
			case FUSE -> fuse(end, members, elapsed, inStep, risk);
			case VOLLEY -> volley(end, members, elapsed, inStep, risk);
			case REST -> rest(elapsed, inStep, risk);
		}
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>여기가 <b>달아오른 크리스탈의 빛기둥을 걷는 마지막 정상 경로</b>다. 월드를 못 받으므로
	 * 들고 있던 개체에 직접 {@code setBeamTarget(null)} 을 쓴다 — 이미 지워진 개체에 써도 해가
	 * 없다. 빠뜨리면 다음 판의 크리스탈에 <b>이유 없는 빛기둥</b>이 하늘로 뻗은 채 남는다.
	 * 컴파일도 시험도 조용한 사고다.
	 *
	 * <p>날고 있던 구체는 그냥 버린다. 파티클은 한 번 보내고 스스로 사라지는 것이라 우리가
	 * 끄지 않아도 다음 틱에 새로 그려지지 않으면 남지 않는다 — 붉은 먼지와 고리도 같다.
	 */
	public static void clearState() {
		if (charged != null) {
			charged.setBeamTarget(null);
		}
		charged = null;
		lastCharged = null;
		victim = null;
		lastShot = -1;
		ORBS.clear();
		step = Step.FUSE;
		stepStart = 0L;
		owner = Long.MIN_VALUE;
	}

	/**
	 * 다음 과충전 — 패턴 타이머 HUD({@link TrialTimers})가 읽는다. <b>상태를 읽기만 한다.</b>
	 *
	 * <p>이 카드는 주기가 아니라 <b>걸음</b>으로 돌아서 받은 틱에서 셀 수 없다 — 크리스탈을 고른
	 * 틱부터 도화선이 타고, 제때 부수면 그 자리에서 쉼으로 넘어간다. 그래서 정적 칸
	 * ({@link #step} · {@link #stepStart} · {@link #charged})을 그대로 읽는다. 셈은
	 * {@link #clockOf} 에 떼어 두어 시험이 월드 없이 굴린다.
	 *
	 * @return 아직 이 판에서 돈 적이 없거나, 달아오를 크리스탈이 하나도 없어 도화선이 서 있으면
	 *         {@code null}
	 */
	static @Nullable TrialTimers.Clock clock(long granted, long now,
			@Nullable TrialCatalog.Risk.CrystalOvercharge risk) {
		if (risk == null || owner != granted) {
			return null;
		}
		long inStep = Math.max(0L, TrialRisks.elapsedSinceGrant(now, granted) - stepStart);
		return clockOf(step, charged != null, inStep, risk);
	}

	/**
	 * {@link #clock} 의 셈. <b>월드를 모른다.</b>
	 *
	 * <ul>
	 *   <li><b>도화선</b> — 사건은 <b>구체를 던지기 시작하는 틱</b>(「과충전까지」). 빛기둥과 붉은 고리가
	 *       도화선 내내 서 있으므로 예고 길이가 도화선 전체다</li>
	 *   <li><b>볼리</b> — 지금 벌어지는 중. 남은 틱은 던지기가 끝날 때까지다</li>
	 *   <li><b>쉼</b> — 남은 쉼 + 도화선. ⚠ 쉼이 끝날 때 기둥에 크리스탈이 하나라도 있어야 성립한다 —
	 *       없으면 도화선이 서서 기다리고, 그때는 이 줄이 사라진다(위 {@code null})</li>
	 * </ul>
	 *
	 * @param chargedPresent 달아오른 크리스탈을 들고 있는가({@link #charged} 가 있는가)
	 * @param inStep         지금 걸음이 시작되고 흐른 틱
	 */
	static @Nullable TrialTimers.Clock clockOf(Step current, boolean chargedPresent, long inStep,
			TrialCatalog.Risk.CrystalOvercharge risk) {
		int fuse = Math.max(0, risk.fuseTicks());
		return switch (current) {
			case FUSE -> chargedPresent
					? TrialTimers.Clock.countdown(remainingFuse(inStep, fuse), fuse, fuse)
					: null;
			case VOLLEY -> TrialTimers.Clock.active(Math.max(0, risk.beamTicks()) - inStep,
					Math.max(0, risk.beamTicks()));
			case REST -> TrialTimers.Clock.countdown(
					Math.max(0, risk.restTicks()) - inStep + fuse,
					(long) Math.max(0, risk.restTicks()) + fuse, fuse);
		};
	}

	// ------------------------------------------------------------------ 도화선

	/**
	 * 도화선 한 틱.
	 *
	 * <p>달아오를 크리스탈을 아직 못 골랐으면 <b>매 틱 다시 고른다.</b> 기둥이 텅 비어 있으면
	 * 아무 일도 하지 않고 그대로 머물고, 「부활」 카드가 하나라도 세우는 순간 그 틱에 붙는다.
	 * 고른 틱을 도화선의 0 으로 삼으므로 <b>비어 있던 시간이 도화선을 잡아먹지 않는다.</b>
	 */
	private static void fuse(ServerLevel end, @Nullable List<ServerPlayer> members, long elapsed,
			long inStep, TrialCatalog.Risk.CrystalOvercharge risk) {
		long into = inStep;
		EndCrystal crystal = charged;
		if (crystal == null) {
			crystal = choose(end, lastCharged);
			if (crystal == null) {
				// 기둥에 남은 크리스탈이 하나도 없다 — 이 카드는 조용히 서 있는다.
				stepStart = elapsed;
				return;
			}
			charged = crystal;
			lastCharged = crystal.getUUID();
			stepStart = elapsed;
			into = 0L;
		}
		if (!crystal.isAlive()) {
			// 제때 부쉈다. 구체는 없다 — 이것이 이 카드가 요구한 행동이고, 보상은 조용한 20초다.
			release();
			enter(Step.REST, elapsed);
			return;
		}

		int remaining = remainingFuse(into, risk.fuseTicks());
		if (remaining <= 0) {
			ignite(end, members, elapsed, risk);
			return;
		}

		float progress = fuseProgress(into, risk.fuseTicks());
		// 빛기둥이 본 신호다. 자라는 길이가 곧 남은 시간이다.
		crystal.setBeamTarget(crystal.blockPosition().above(columnHeight(progress)));
		ember(end, crystal, progress, into);

		// 경고의 층은 TrialWarning 의 것을 그대로 쓴다. 카드마다 새 신호를 만들면 플레이어가
		// 카드 수만큼 새 언어를 배워야 한다.
		if (TrialRisks.stageJustChanged(remaining)) {
			warn(end, members, TrialWarning.stageFor(remaining));
		}
	}

	/**
	 * 도화선이 다 탔다. 한 사람을 노리고 곧바로 첫 발을 던진다.
	 *
	 * <p>노릴 사람이 하나도 없으면(전원 사망·관전) 볼리 없이 쉼으로 넘어간다. 크리스탈은 그대로
	 * 선 채 남는다 — <b>우리가 부수지 않는다.</b>
	 *
	 * <p>같은 틱에 {@link #volley} 를 한 번 굴리는 것이 중요하다. 다음 틱으로 미루면 첫 발이 한 틱
	 * 늦게 떠나고, 그 한 틱이 「도화선이 끝났는데 아무 일도 없다」로 읽힌다.
	 *
	 * <p>⚠ <b>여기에 따로 알림 소리를 두지 않는다.</b> 전에는 빔이 붙는 순간을 전도체 공격음으로
	 * 알렸는데, 지금은 <b>그 소리가 첫 발의 발사음</b>이다({@link #launch}). 같은 틱에 둘을 울리면
	 * 사람에게는 「무엇이 시작됐는지」가 아니라 겹친 잡음으로 들린다.
	 */
	private static void ignite(ServerLevel end, @Nullable List<ServerPlayer> members, long elapsed,
			TrialCatalog.Risk.CrystalOvercharge risk) {
		ServerPlayer target = pickVictim(end, members);
		if (target == null) {
			release();
			enter(Step.REST, elapsed);
			return;
		}
		victim = target.getUUID();
		lastShot = -1;
		ORBS.clear();
		enter(Step.VOLLEY, elapsed);
		volley(end, members, elapsed, 0L, risk);
	}

	// ------------------------------------------------------------------ 볼리

	/**
	 * 볼리 한 틱.
	 *
	 * <p>순서가 규칙이다 — <b>끝나는 조건을 먼저 보고</b>, 그다음에 대상을 세우고, 날고 있는 것을
	 * 옮기고, 마지막에 새 발을 던진다. 끝나는 조건을 뒤로 미루면 이미 끝난 볼리가 한 발 더 쏜다.
	 *
	 * <p>볼리 중에 크리스탈이 깨지면 날고 있던 구체까지 사라진다. 근원이 사라졌는데 피해가
	 * 들어오는 것은 거짓말이고, 「늦게라도 부수면 멈춘다」는 도화선의 규칙과도 이어진다.
	 */
	private static void volley(ServerLevel end, @Nullable List<ServerPlayer> members, long elapsed,
			long inStep, TrialCatalog.Risk.CrystalOvercharge risk) {
		EndCrystal crystal = charged;
		if (crystal == null || !crystal.isAlive()) {
			release();
			enter(Step.REST, elapsed);
			return;
		}
		if (inStep >= Math.max(0, risk.beamTicks())) {
			release();
			enter(Step.REST, elapsed);
			return;
		}

		ServerPlayer target = victimOf(members);
		if (target == null) {
			// 노리던 사람이 나갔거나 관전으로 넘어갔다. 다시 고르지 않으면 이 카드는 남은
			// 시간 동안 아무도 노리지 않는 빈 카드가 된다. 고를 사람이 없으면 거기서 끝난다.
			target = pickVictim(end, members);
			if (target == null) {
				release();
				enter(Step.REST, elapsed);
				return;
			}
			victim = target.getUUID();
			ORBS.clear();
		}

		// 크리스탈은 볼리 동안에도 달아올라 있고, 빛기둥은 사람이 아니라 곧게 위를 가리킨다.
		// 「어디서 오는가」는 가려진 동안에도 보여야 다시 나설 때 어느 쪽을 피할지 정할 수 있다.
		crystal.setBeamTarget(crystal.blockPosition().above(COLUMN_MAX));
		ember(end, crystal, 1.0F, inStep);
		if (inStep % VICTIM_RING_TICKS == 0L) {
			TrialWarning.markGround(end, target.position(), VICTIM_RING_RADIUS,
					TrialWarning.dust(TrialWarning.Colors.MARKED));
		}

		float damage = orbDamage(risk);
		flyOrbs(end, members, crystal, inStep, damage);

		int index = shotIndex(inStep, SECOND_TICKS);
		if (index < 0 || index <= lastShot || index >= volleyShots(risk.beamTicks(), SECOND_TICKS)
				|| damage <= 0.0F) {
			// 발 번호로 한 번 더 자르는 까닭은 「볼리 안에서 끝나지 않는 발」을 막는 것이다.
			// beamTicks 가 SECOND_TICKS 로 나누어떨어지지 않는 카드에서는 마지막 한 발이
			// 쉼으로 넘어간 뒤에 닿게 되고, 그러면 그려 놓은 궤적이 조용히 사라진다.
			return;
		}
		lastShot = index;
		launch(end, members, crystal, target, inStep);
	}

	/**
	 * 한 발 던진다. 떠나는 자리는 <b>달아오른 크리스탈의 몸통</b>이다.
	 *
	 * <p><b>가려져 있으면 한 발도 나가지 않는다.</b> 빔이던 때 「숨은 초는 통째로 빠진다」와 같은
	 * 규칙이고, 구체가 안 날아오는 것이 숨은 사람에게는 「막혔다」는 신호다. 이것을 「쏘고 나서
	 * 안 맞히기」로 바꾸면 벽 안으로 구체가 들어가는 그림이 되어 규칙이 거짓말을 한다.
	 *
	 * <p>소리는 <b>전도체 공격음</b>이다 — 빔이던 때 볼리 시작을 알리던 그 소리를 그대로
	 * <b>발사음</b>으로 옮겼다. 26.3 {@code sounds.json} 에서 {@code block.conduit.attack.target} 은
	 * {@code block/conduit/attack1~3} 이라 이 저장소의 다른 카드와 <b>파일이 겹치지 않는다</b>
	 * (「표적」의 셜커·「기둥 화염구」의 가스트·폭발음 전부와 다르다). 사람이 이미 이 소리를
	 * 「과충전이 터졌다」로 배웠으므로 새 소리를 만들지 않는다.
	 *
	 * <p>자리에 놓는 {@code playSound} 를 쓰지 않는다. 크리스탈은 반경 42 기둥 꼭대기라 볼륨 1 의
	 * 16 블록은 <b>아무에게도 안 닿고</b>, 사람마다 자리에 놓으면 사람 수만큼 겹친다
	 * ({@link TrialWarning#playEach} 의 「사람마다 한 번」).
	 */
	private static void launch(ServerLevel end, @Nullable List<ServerPlayer> members,
			EndCrystal crystal, ServerPlayer target, long inStep) {
		// 바닐라 hasLineOfSight 와 같은 눈→눈 광선이고, 쇠창살만 지나간다(클래스 설명의
		// 「쇠창살은 막지 않는다」). 닿는 틱의 판정과 반드시 같은 것을 써야 한다.
		if (!clearShot(target, crystal)) {
			return;
		}
		Vec3 muzzle = crystal.position().add(0.0, EMBER_LIFT, 0.0);
		ORBS.add(new Orb(target.getUUID(), muzzle, inStep, inStep + ORB_FLIGHT_TICKS));
		puff(end, muzzle, MUZZLE_POINTS, MUZZLE_SPREAD);
		TrialWarning.playEach(end, members, SoundEvents.CONDUIT_ATTACK_TARGET, 1.0F, 0.6F);
	}

	/**
	 * 날고 있는 것들을 한 칸 옮겨 그리고, 닿은 것을 처리한다.
	 *
	 * <p>노린 사람이 명단에서 사라졌으면 그 구체도 사라진다. 끝점이 그 사람이라 <b>그릴 자리가
	 * 없고</b>, 시체나 관전자에게 닿는 그림은 아무것도 알려 주지 않는다.
	 */
	private static void flyOrbs(ServerLevel end, @Nullable List<ServerPlayer> members,
			EndCrystal crystal, long inStep, float damage) {
		if (ORBS.isEmpty()) {
			return;
		}
		Iterator<Orb> flying = ORBS.iterator();
		while (flying.hasNext()) {
			Orb orb = flying.next();
			ServerPlayer target = memberOf(members, orb.targetId());
			if (target == null || !target.isAlive() || target.isSpectator()) {
				flying.remove();
				continue;
			}
			if (inStep >= orb.landsAt()) {
				land(end, members, crystal, orb, target, damage);
				flying.remove();
				continue;
			}
			drawOrb(end, orb, target, inStep);
		}
	}

	/**
	 * 날아가는 중인 구체를 그린다.
	 *
	 * <p>끝점이 <b>그 사람의 지금 자리</b>다 — 클래스 설명의 「구체는 사람을 따라간다」. 그래서
	 * 그려 놓은 궤적은 언제나 사람에게 닿아 끝나고, 「피했는데 맞았다」가 생기지 않는다.
	 *
	 * <p><b>두 줄 모두 긴 형태다.</b> 첫 {@code boolean} 을 {@code false} 로 되돌리면 구체는 보는
	 * 사람 발밑 32 블록 안에서만 존재한다 — 크리스탈은 기둥 꼭대기라 그 밖이므로 <b>떠나는 구간이
	 * 통째로 사라지고</b> 구체는 코앞에서 갑자기 나타난다. 그러면 「날아오는 것이 보인다」가 아니다.
	 *
	 * <p>둘째 {@code boolean}({@code alwaysShow}) 은 「파티클 줄이기」 설정을 무시할지다. 첫
	 * 깃발이 켜져 있으면 그 검사를 건너뛰므로 값이 무의미하고, 사용자의 설정을 우리가 뒤집을
	 * 이유도 없어 {@code false} 로 둔다.
	 */
	private static void drawOrb(ServerLevel end, Orb orb, ServerPlayer target, long inStep) {
		Vec3 to = target.position().add(0.0, ORB_LIFT, 0.0);
		Vec3 head = orb.from().add(to.subtract(orb.from())
				.scale(flightProgress(inStep, orb.firedAt(), orb.landsAt())));
		ParticleOptions dust = TrialWarning.dust(TrialWarning.Colors.MARKED);
		end.sendParticles(dust, true, false, head.x, head.y, head.z, ORB_HEAD_POINTS,
				ORB_SPREAD, ORB_SPREAD, ORB_SPREAD, 0.0);
		// 꼬리는 「어느 쪽에서 왔는가」만 말하면 되므로 몇 점이면 충분하다.
		Vec3 back = orb.from().subtract(head);
		if (back.lengthSqr() <= 0.0) {
			return;
		}
		// 이름을 step 으로 쓰지 말 것 — 걸음을 들고 있는 정적 칸과 겹쳐 읽기가 흐려진다.
		Vec3 stride = back.normalize().scale(ORB_TAIL_STEP);
		for (int index = 1; index <= ORB_TAIL_POINTS; index++) {
			Vec3 point = head.add(stride.scale(index));
			end.sendParticles(dust, true, false, point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 닿았다. 블록은 건드리지 않고 불도 붙이지 않는다.
	 *
	 * <p>맞는 사람은 <b>그 구체가 노린 한 사람</b>이다. 주변을 긁어모아 때리면 넷이 모여 있는
	 * 자리에서 한 발에 {@code 피해 × 4} 가 공유 체력에 들어간다 — 클래스 설명의 「피해는 한
	 * 사람에게만」이 그 이야기다.
	 *
	 * <p><b>닿는 틱에 시선을 한 번 더 묻는다</b>({@link #clearShot} — 쏘는 틱과 같은 판정이라
	 * 쇠창살은 여기서도 막지 않는다). 쏜 뒤에 숨은 사람은 여기서 살아난다. 그때
	 * 구체는 사람 자리가 아니라 {@link #BLOCKED_PULLBACK} 블록 앞에서 터지고 <b>소리도 피해도
	 * 없다</b> — 「맞았다」와 「막혔다」가 같은 그림이면 가린 것이 일했는지 알 수 없다.
	 *
	 * <p>맞은 소리는 <b>자수정 울림</b>이다. 26.3 {@code sounds.json} 에서
	 * {@code block.amethyst_block.resonate} 는 {@code block/amethyst/resonate1~4} 이고
	 * <b>이 저장소의 어느 카드도 쓰지 않는 파일</b>이다(이름만 보고 고르면 겹친다 —
	 * {@code DRAGON_FIREBALL_EXPLODE} 와 {@code GENERIC_EXPLODE} 가 둘 다
	 * {@code random/explode1~4} 인 것이 그 예다). 수정이 깨지듯 울리는 소리라 「수정 과충전」이
	 * 던진 것이 맞았다는 그림과도 맞는다.
	 *
	 * <p>피해원에 엔티티를 달지 않는 것이 <b>넉백을 막는 장치</b>다. 실체가 붙은 피해원이면
	 * {@code LivingEntity} 가 스스로 밀어내는데, 엔드 섬 가장자리에서 밀리면 대응 불가 즉사다.
	 */
	private static void land(ServerLevel end, @Nullable List<ServerPlayer> members,
			EndCrystal crystal, Orb orb, ServerPlayer target, float damage) {
		Vec3 at = target.position().add(0.0, ORB_LIFT, 0.0);
		// 쏘는 틱과 같은 판정이다. 둘이 갈라지면 「쇠창살 너머로 쐈는데 쇠창살에 막혔다」가 된다.
		if (!clearShot(target, crystal)) {
			Vec3 toward = orb.from().subtract(at);
			Vec3 blocked = toward.lengthSqr() > 0.0
					? at.add(toward.normalize().scale(BLOCKED_PULLBACK))
					: at;
			puff(end, blocked, BLOCKED_POINTS, ORB_SPREAD);
			return;
		}
		puff(end, at, IMPACT_POINTS, IMPACT_SPREAD);
		TrialWarning.playEach(end, members, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F, 0.8F);
		if (damage <= 0.0F) {
			return;
		}
		// 맞는 사람은 노려진 하나뿐이고, 피해원에 실체를 달지 않아 넉백이 없다. 종류를
		// explosion 으로 고른 까닭은 클래스 설명의 「피해는 한 사람에게만, 넉백 없이」에 있다.
		target.hurtServer(end, end.damageSources().explosion(null, null), damage);
	}

	// ------------------------------------------------------------------ 쉼

	/** 쉬는 한 틱. 다 쉬면 도화선으로 돌아가고, 크리스탈은 그때 다시 고른다. */
	private static void rest(long elapsed, long inStep, TrialCatalog.Risk.CrystalOvercharge risk) {
		if (inStep < Math.max(0, risk.restTicks())) {
			return;
		}
		charged = null;
		enter(Step.FUSE, elapsed);
	}

	// ------------------------------------------------------------------ 연출

	/**
	 * 크리스탈을 붉은 먼지와 고리로 두른다. <b>둘 다 긴 형태로 보낸다.</b>
	 *
	 * <p>첫 {@code boolean} 을 {@code false} 로 되돌리면 서버가 <b>발생 지점 32 블록 안의
	 * 사람에게만</b> 패킷을 보내고 클라이언트가 한 번 더 거른다. 크리스탈은 반경 42 기둥 꼭대기라
	 * 거의 언제나 32 블록 밖이므로 <b>연출이 통째로 사라진다</b> — 「크리스탈 부활」이 정확히
	 * 이 함정에 빠져 연출을 한 번 통째로 잃었다.
	 *
	 * <p>고리는 {@link TrialWarning#markGround} 를 그대로 쓴다. 그쪽도 긴 형태이고, 색·모양·높이
	 * 규약이 갈라지지 않는다.
	 */
	private static void ember(ServerLevel end, EndCrystal crystal, float progress, long inStep) {
		if (inStep % PULSE_TICKS != 0L) {
			return;
		}
		Vec3 at = crystal.position();
		ParticleOptions dust = TrialWarning.dust(TrialWarning.Colors.DEADLY);
		end.sendParticles(dust, true, false, at.x, at.y + EMBER_LIFT, at.z, emberCount(progress),
				EMBER_SPREAD, EMBER_SPREAD, EMBER_SPREAD, 0.0);
		TrialWarning.markGround(end, at, RING_RADIUS, dust);
	}

	/**
	 * 한 점에 보라 먼지를 뿌린다 — 떠나는 자리·맞은 자리·막힌 자리가 모두 이것이다.
	 *
	 * <p>색은 {@link TrialWarning.Colors#MARKED}(「너 하나를 노린다」)다. 구체가 노리는 것이 한
	 * 사람이고, 「표적」의 구체도 같은 색이라 <b>사람이 한 가지만 배운다.</b> 크리스탈 쪽의 빨강
	 * (「서 있으면 죽는다」)과 갈라 두는 것이 요점이다 — 둘이 같은 색이면 달아오른 크리스탈과
	 * 날아오는 구체가 한 덩이로 보인다.
	 *
	 * <p>여기도 <b>긴 형태</b>다. 짧은 형태로 되돌리면 기둥 꼭대기에서 떠나는 덩이가 바닥에 선
	 * 사람에게 안 간다.
	 */
	private static void puff(ServerLevel end, Vec3 at, int count, double spread) {
		if (count <= 0) {
			return;
		}
		end.sendParticles(TrialWarning.dust(TrialWarning.Colors.MARKED), true, false,
				at.x, at.y, at.z, count, spread, spread, spread, 0.0);
	}

	/**
	 * 경고 층의 소리를 <b>사람마다 그 자리에서</b> 낸다.
	 *
	 * <p>소리도 파티클과 같이 잘린다 — {@code level.playSound} 는 자리에 소리를 놓는 것이고
	 * 볼륨 1 이면 16 블록이다. 크리스탈 자리에서 한 번 울리면 기둥 꼭대기에 올라간 사람 말고는
	 * <b>아무도 못 듣는다.</b>
	 *
	 * <p>{@link TrialWarning#soundFor} 를 사람마다 부른다. 소리표를 여기에 베껴 오면
	 * {@code TrialWarning} 이 층의 소리를 바꿀 때 이 카드만 옛 소리로 남는다 — <b>규약이 갈라지는
	 * 것이 더 나쁘다.</b>
	 *
	 * <p>⚠ <b>전에는 {@code TrialWarning.sound} 를 사람 자리마다 불렀고, 그것이 틀렸다.</b> 그쪽은
	 * 자리에 소리를 놓는 것이라 반경 안의 <b>전원</b>에게 나간다 — 옛 주석은 그 대가를 「둘이 붙어
	 * 있으면 조금 커진다」고 적어 두었는데, 실제로는 넷이 모이면 <b>각자 네 겹</b>으로 듣고 남의
	 * 경고까지 듣는다. {@code soundFor} 는 그 사람의 연결로 직접 보내 둘 다 없앤다.
	 */
	private static void warn(ServerLevel end, @Nullable List<ServerPlayer> members,
			@Nullable TrialWarning.Stage stage) {
		if (stage == null || members == null) {
			return;
		}
		for (ServerPlayer member : members) {
			TrialWarning.soundFor(end, member, stage);
		}
	}

	// ------------------------------------------------------------------ 고르기

	/**
	 * 달아오를 크리스탈 하나. 기둥에 남은 것이 없으면 {@code null}.
	 *
	 * <p><b>기둥 위의 것만</b> 고른다. 사람이 손에 들고 다니며 터뜨리는 크리스탈까지 고르면
	 * 구체가 아레나 바닥에서 나가고, 그 크리스탈은 어차피 다음 순간 사람 손에 터진다 — 카드가
	 * 아무 일도 안 한 것이 된다. 판별은 {@link EndPillars#crystalSeats} 와의 거리로 한다.
	 *
	 * <p>직전에 골랐던 것은 <b>다른 후보가 있으면</b> 건너뛴다. 「20초 뒤 다른 크리스탈이
	 * 달아오른다」가 이 카드가 약속한 그림이고, 같은 것이 세 번 연달아 달아오르면 그 약속이
	 * 깨진다. 후보가 그것 하나뿐이면 그대로 다시 고른다 — 카드를 죽이는 것보다 낫다.
	 */
	private static @Nullable EndCrystal choose(ServerLevel end, @Nullable UUID avoid) {
		List<Vec3> seats = EndPillars.crystalSeats(end);
		if (seats.isEmpty()) {
			return null;
		}
		List<EndCrystal> onSpikes = new ArrayList<>();
		List<EndCrystal> fresh = new ArrayList<>();
		for (EndCrystal crystal : end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive)) {
			if (!onSeat(crystal.position(), seats)) {
				continue;
			}
			onSpikes.add(crystal);
			if (avoid == null || !crystal.getUUID().equals(avoid)) {
				fresh.add(crystal);
			}
		}
		List<EndCrystal> pool = fresh.isEmpty() ? onSpikes : fresh;
		if (pool.isEmpty()) {
			return null;
		}
		return pool.get(end.getRandom().nextInt(pool.size()));
	}

	/**
	 * 구체에 노려질 한 사람. 노릴 사람이 없으면 {@code null}.
	 *
	 * <p><b>반드시 한 명이다.</b> 공유 체력에서 범위 피해는 팀원별로 합산되므로 넷을 다 노리면
	 * 한 발이 24 이고 열 발이면 240 이다 — 팀 체력 20 의 열두 배, 곧 즉사 카드다.
	 */
	private static @Nullable ServerPlayer pickVictim(ServerLevel end,
			@Nullable List<ServerPlayer> members) {
		List<ServerPlayer> alive = targetable(members);
		if (alive.isEmpty()) {
			return null;
		}
		return alive.get(end.getRandom().nextInt(alive.size()));
	}

	/** 지금 노려지는 사람. 명단에 없거나 죽었거나 관전 중이면 {@code null}. */
	private static @Nullable ServerPlayer victimOf(@Nullable List<ServerPlayer> members) {
		if (victim == null) {
			return null;
		}
		ServerPlayer member = memberOf(members, victim);
		if (member == null || !member.isAlive() || member.isSpectator()) {
			return null;
		}
		return member;
	}

	/** 그 id 의 팀원. 명단에 없으면 {@code null}. 살았는지는 묻지 않는다. */
	private static @Nullable ServerPlayer memberOf(@Nullable List<ServerPlayer> members,
			@Nullable UUID id) {
		if (members == null || id == null) {
			return null;
		}
		for (ServerPlayer member : members) {
			if (member != null && member.getUUID().equals(id)) {
				return member;
			}
		}
		return null;
	}

	/** 지금 노릴 수 있는 사람들. 시체와 관전자를 노리면 구체가 아무 일도 하지 않는다. */
	private static List<ServerPlayer> targetable(@Nullable List<ServerPlayer> members) {
		List<ServerPlayer> alive = new ArrayList<>();
		if (members == null) {
			return alive;
		}
		for (ServerPlayer member : members) {
			if (member != null && member.isAlive() && !member.isSpectator()) {
				alive.add(member);
			}
		}
		return alive;
	}

	// ------------------------------------------------------------------ 시선

	/**
	 * 그 사람이 달아오른 크리스탈을 보는가 — 쏘는 틱과 닿는 틱이 <b>둘 다 이것만</b> 묻는다.
	 *
	 * <p>26.3 바닐라 {@code LivingEntity.hasLineOfSight(Entity)} 와 한 줄씩 같다 — 다른 차원이면
	 * 거짓, 사람의 눈({@code getEyeY})에서 크리스탈의 눈높이까지 {@link #SIGHT_REACH} 를 넘으면
	 * 거짓, 아니면 그 사이로 광선을 쏜다. 다른 것은 광선이 <b>쇠창살을 지나간다</b>는 것 하나다
	 * (클래스 설명의 「쇠창살은 막지 않는다」).
	 */
	private static boolean clearShot(ServerPlayer target, EndCrystal crystal) {
		if (target.level() != crystal.level()) {
			return false;
		}
		Vec3 eye = new Vec3(target.getX(), target.getEyeY(), target.getZ());
		Vec3 aim = new Vec3(crystal.getX(), crystal.getEyeY(), crystal.getZ());
		return clearLine(target.level(), eye, aim, CollisionContext.of(target));
	}

	/**
	 * 두 점 사이가 트여 있는가. {@link #clearShot} 의 광선 부분이고, <b>월드 대신 아무
	 * {@link BlockGetter} 나 받아</b> 시험이 블록 몇 개만 놓고 굴린다.
	 *
	 * @param viewer 바닐라가 보는 쪽 엔티티로 만드는 것과 같은 충돌 문맥
	 */
	static boolean clearLine(BlockGetter level, Vec3 from, Vec3 to, CollisionContext viewer) {
		if (to.distanceTo(from) > SIGHT_REACH) {
			return false;
		}
		return level.clip(new PastBars(from, to, viewer)).getType() == HitResult.Type.MISS;
	}

	/**
	 * 광선이 지나가는 블록인가. <b>쇠창살뿐이다.</b>
	 *
	 * <p>바닐라 우리와 「다시 선 쇠창살」이 세우는 우리가 같은 {@code IRON_BARS} 다. 판유리나 다른
	 * 창살까지 넓히지 말 것 — 사람이 정한 것은 「쇠창살은 통과」이고, 나머지 블록이 막는 것이
	 * 「블록 뒤에 숨기」라는 이 카드의 대응이다.
	 */
	static boolean seeThrough(BlockState state) {
		return state.is(Blocks.IRON_BARS);
	}

	/**
	 * 바닐라 {@code COLLIDER}·{@code Fluid.NONE} 광선에서 {@link #seeThrough} 블록만 빈 모양으로
	 * 보는 문맥.
	 *
	 * <p>{@code BlockGetter.clip} 은 칸마다 {@link ClipContext#getBlockShape} 로 모양을 받아
	 * 광선과 맞대 보므로, 여기서 빈 모양을 돌려주면 그 칸은 공기와 같다. 광선을 칸 단위로 다시
	 * 짜지 않아도 되고 <b>나머지 블록은 바닐라가 보는 모양 그대로</b>다.
	 */
	private static final class PastBars extends ClipContext {

		PastBars(Vec3 from, Vec3 to, CollisionContext viewer) {
			super(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, viewer);
		}

		@Override
		public VoxelShape getBlockShape(BlockState state, BlockGetter level, BlockPos pos) {
			return seeThrough(state) ? Shapes.empty() : super.getBlockShape(state, level, pos);
		}
	}

	// ------------------------------------------------------------------ 걸음 바꾸기

	/** 걸음을 바꾸고 시작 시각을 적는다. 시각은 <b>받은 뒤 흐른 틱</b>이지 월드 시간이 아니다. */
	private static void enter(Step next, long elapsed) {
		step = next;
		stepStart = elapsed;
	}

	/**
	 * 달아오른 크리스탈에 우리가 걸어 둔 것을 되돌린다.
	 *
	 * <p>되돌릴 것은 빛기둥 하나뿐이다. 무적도 블록도 건드리지 않았고, 크리스탈 자체는 <b>부수지
	 * 않고 그대로 둔다.</b>
	 *
	 * <p>날고 있던 구체도 함께 버린다. 여기로 오는 길은 전부 「끝났다」거나 「크리스탈이
	 * 깨졌다」이고, 둘 다 <b>피해가 더 들어가서는 안 되는</b> 자리다.
	 */
	private static void release() {
		if (charged != null) {
			charged.setBeamTarget(null);
		}
		charged = null;
		victim = null;
		lastShot = -1;
		ORBS.clear();
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/** 도화선이 끝나기까지 남은 틱. 0 이면 이번 틱에 쏜다. */
	static int remainingFuse(long inStep, int fuseTicks) {
		int fuse = Math.max(0, fuseTicks);
		long left = fuse - Math.max(0L, inStep);
		return (int) Math.max(0L, Math.min(fuse, left));
	}

	/**
	 * 도화선이 얼마나 탔는가. 0 이 시작, 1 이 쏘는 순간.
	 *
	 * <p><b>0 과 1 을 벗어나면 안 된다.</b> 이 값이 빛기둥의 길이라, 1 을 넘으면 기둥이 약속한
	 * 높이보다 더 자라 「다 차면 쏜다」가 거짓말이 된다.
	 */
	static float fuseProgress(long inStep, int fuseTicks) {
		int fuse = Math.max(0, fuseTicks);
		if (fuse <= 0) {
			return 1.0F;
		}
		long into = Math.max(0L, Math.min(fuse, inStep));
		return (float) into / fuse;
	}

	/** 그 진행도에서의 빛기둥 높이(블록). {@link #COLUMN_MIN} 아래로는 내려가지 않는다. */
	static int columnHeight(float progress) {
		float grown = Math.max(0.0F, Math.min(1.0F, progress));
		return COLUMN_MIN + Math.round((COLUMN_MAX - COLUMN_MIN) * grown);
	}

	/** 그 진행도에서 뿌릴 먼지 수. 「점점 달아오른다」가 이 기울기다. */
	static int emberCount(float progress) {
		float grown = Math.max(0.0F, Math.min(1.0F, progress));
		return EMBER_MIN + Math.round((EMBER_MAX - EMBER_MIN) * grown);
	}

	/**
	 * 이 위상에서 떠나는 발 번호. 떠나는 틱이 아니면 {@code -1}.
	 *
	 * <p>볼리가 시작되는 <b>그 틱이 0번</b>이다. 그래서 {@code beamTicks} 가 200 이면 위상
	 * 0·20·…·180 에 열 발이 떠나고 합계가 정확히 {@code damagePerSecond × 10} 이 된다.
	 * 첫 발을 뒤로 미루면 도화선이 끝났는데 아무것도 날아오지 않는 1초가 생긴다.
	 */
	static int shotIndex(long inStep, int secondTicks) {
		if (secondTicks <= 0 || inStep < 0L || inStep % secondTicks != 0L) {
			return -1;
		}
		return (int) (inStep / secondTicks);
	}

	/** 볼리 하나가 끝까지 갔을 때 떠나는 발수. */
	static int volleyShots(int beamTicks, int secondTicks) {
		if (beamTicks <= 0 || secondTicks <= 0) {
			return 0;
		}
		return beamTicks / secondTicks;
	}

	/**
	 * 구체 <b>한 발</b>의 피해.
	 *
	 * <p>카드에 적힌 「초당 피해」를 그대로 쓴다. 초에 한 발이므로 <b>적힌 값의 뜻이 바뀌지
	 * 않는다</b> — 빔이던 때의 「초당 3」이 그대로 「발당 3」이 됐고, 2026-10-04 에 카드 칸이
	 * 6 으로 오른 지금은 발당 6 이다. <b>올리려면 카드 칸을 올릴 것.</b> 여기서 카드 칸과 다른
	 * 값을 돌려주면 {@code TrialRisks.worstCaseTickDamage} 가 보는 「한 몫」이 거짓이 된다
	 * (클래스 설명의 「5발 × 6 으로 바꾸지 않았다」).
	 */
	static float orbDamage(@Nullable TrialCatalog.Risk.CrystalOvercharge risk) {
		if (risk == null || risk.damagePerSecond() <= 0.0F) {
			return 0.0F;
		}
		return risk.damagePerSecond();
	}

	/**
	 * 한 사람이 볼리 하나에서 <b>다 맞았을 때</b> 받는 총 피해 — 이 카드의 피해 예산이다.
	 *
	 * <p>{@code TrialRisks.worstCaseTickDamage} 는 「한 틱에 올 수 있는 가장 큰 값」을 보지만,
	 * 이 카드가 위험해지는 길은 한 틱이 아니라 <b>열 발이 쌓이는 쪽</b>이다. 값을 고치는 사람이
	 * 곱을 볼 수 있게 여기에 둔다.
	 *
	 * <p>빔이던 때와 <b>같은 곱</b>이다 — 「초당 × 10초」가 「발당 × 10발」이 됐을 뿐이다. 지금
	 * 카드 값으로는 6 × 10 = 60 이다.
	 */
	static float volleyTotalDamage(@Nullable TrialCatalog.Risk.CrystalOvercharge risk) {
		if (risk == null) {
			return 0.0F;
		}
		return orbDamage(risk) * volleyShots(risk.beamTicks(), SECOND_TICKS);
	}

	/**
	 * 마지막 발이 닿는 위상.
	 *
	 * <p><b>{@code beamTicks} 보다 작아야 한다.</b> 크면 마지막 발이 쉼으로 넘어간 뒤에 닿는데,
	 * 볼리를 벗어난 구체는 그려 놓은 궤적째로 사라진다 — 「던졌는데 아무 일도 없었다」가 되고
	 * 적힌 예산도 그만큼 빈다. {@link #ORB_GAP_TICKS} 가 그 여유다.
	 */
	static int lastImpactTick(int beamTicks, int secondTicks, int flightTicks) {
		int shots = volleyShots(beamTicks, secondTicks);
		if (shots <= 0) {
			return 0;
		}
		return (shots - 1) * secondTicks + Math.max(0, flightTicks);
	}

	/**
	 * 지금까지 날아온 비율. 0 이 떠난 자리, 1 이 사람.
	 *
	 * <p>1 을 넘겨 그리면 구체가 사람을 지나쳐 날아가고, 사람들은 「지나간 자리」에서 착탄을
	 * 본다. 자르는 것은 안전장치가 아니라 규칙이다.
	 */
	static double flightProgress(long inStep, long firedAt, long landsAt) {
		long span = landsAt - firedAt;
		if (span <= 0L) {
			return 1.0;
		}
		double along = (double) (inStep - firedAt) / span;
		return Math.max(0.0, Math.min(1.0, along));
	}

	/**
	 * 그 자리가 기둥 위 크리스탈 자리인가.
	 *
	 * <p>가로와 세로를 함께 본다. 기둥은 높이가 제각각이라 가로만 보면 <b>낮은 기둥 위에 선
	 * 사람이 손으로 놓은 크리스탈</b>이 높은 기둥의 자리로 읽힌다.
	 */
	static boolean onSeat(Vec3 at, List<Vec3> seats) {
		for (Vec3 seat : seats) {
			if (Math.abs(at.y - seat.y) <= SEAT_REACH
					&& TrialRisks.insideMark(at, seat, SEAT_REACH)) {
				return true;
			}
		}
		return false;
	}
}
