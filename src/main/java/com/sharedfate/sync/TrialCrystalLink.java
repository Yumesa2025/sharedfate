package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link TrialCatalog.Risk.CrystalLink} 실행기 — 연결된 수정.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p>크리스탈을 <b>부순 순간</b> 가장 가까운 다른 크리스탈이 {@code shieldTicks} 동안 보호막을
 * 두른다. 카드가 약속하는 것은 <b>「그동안은 어떤 피해도 통하지 않습니다」</b>다 — 화살도 근접도
 * 폭발도 연쇄도 전부다.
 *
 * <p><b>때리는 것만으로는 걸리지 않는다.</b> 방아쇠는 「맞았다」가 아니라 「부서졌다」다.
 *
 * <h2>이 카드가 빼앗는 것은 순서다</h2>
 *
 * <p>피해가 없다. 한쪽 끝부터 기둥을 차례로 밀고 나가는 가장 편한 길이 막히고, 팀은 <b>먼 것부터
 * 깨거나 기다리거나</b> 둘 중 하나를 골라야 한다. 그래서 이 파일에서 가장 중요한 코드는 보호막 한
 * 줄이 아니라 「어느 것이 잠겼는지」를 아레나 어디에서든 읽게 만드는 연출 쪽이다 — 그것이 안
 * 보이면 이 카드는 「왜 안 깨지지」가 된다.
 *
 * <h2>「부서졌다」를 무엇으로 잡는가 — 여기에는 믹스인을 쓰지 않는다</h2>
 *
 * <p>{@code EndCrystal.hurtServer} 는 <b>{@code final}</b> 이라 피해 처리를 덮어쓸 길이 없고,
 * {@link CrystalWatch#lastBreaker()} 는 「누가」만 적을 뿐 <b>「어느 것을」</b>은 적지 않는다.
 * 이 카드에 필요한 것은 부서진 <b>자리</b>라 그 기록으로는 모자란다.
 *
 * <p>그래서 {@link TrialCrystalOvercharge} 가 이미 찾아 둔 길을 그대로 쓴다 — <b>개체를 들고
 * 있다가 상태를 묻는다.</b> 다만 저쪽은 크리스탈 하나만 들면 되지만 이 카드는 <b>어느 것이든</b>
 * 부서지는 것을 봐야 하므로 기둥 위의 것 전부를 {@link #WATCHED} 에 들고 매 틱 대조한다.
 *
 * <p>「부서졌다」의 정의는 <b>{@code RemovalReason.KILLED}</b> 다. 26.3 바이트코드를 읽고 고른
 * 값이다.
 *
 * <ul>
 *   <li>{@code EndCrystal.hurtServer} 는 통과하는 순간 {@code remove(RemovalReason.KILLED)} 를
 *       부르고, 그 뒤에 폭발과 {@code onDestroyedBy} 가 온다. <b>깨지는 유일한 길</b>이다</li>
 *   <li>{@code Entity.setRemoved} 는 <b>처음 적힌 이유를 덮어쓰지 않는다</b>
 *       ({@code if (removalReason == null)}). 나중에 청크가 내려가도 {@code KILLED} 가 남는다</li>
 *   <li>청크 언로드는 {@code UNLOADED_TO_CHUNK}, {@code TrialCrystalRevive.prune} 이 사람 발밑의
 *       크리스탈을 거두는 것은 {@code DISCARDED} 다. <b>둘 다 「부서졌다」가 아니다</b> —
 *       이유를 안 보고 「목록에서 사라졌다」로 잡으면 청크가 한 번 내려갈 때마다 보호막이 걸린다</li>
 * </ul>
 *
 * <p>대가는 <b>한 틱 늦다</b>는 것뿐이다. 크리스탈이 깨진 틱에 우리가 이미 지나갔으면 다음 틱에
 * 본다. 0.05초라 사람 눈에는 같은 순간이고, 그 대신 믹스인이 하나도 늘지 않는다.
 *
 * <h2>기둥 위의 것만 본다 — 방아쇠도 대상도</h2>
 *
 * <p>사람이 손에 들고 다니며 터뜨리는 크리스탈까지 세면 <b>폭탄을 터뜨릴 때마다 기둥 하나가
 * 잠긴다.</b> 그것은 이 카드가 약속한 「기둥을 차례로 밀지 못한다」가 아니라 그냥 사고다.
 * 판별은 {@link TrialCrystalOvercharge#onSeat} 를 그대로 쓴다 — 같은 뜻의 판별이 둘로 갈리면
 * 두 카드가 서로 다른 「기둥 위」를 갖게 된다.
 *
 * <h2>보호막은 <b>우리 봉인</b>이다 — 바닐라 무적 칸을 빌려 쓰지 않는다</h2>
 *
 * <p>앞선 구현은 {@code Entity.setInvulnerableTime} 을 매 틱 다시 걸었다. 사람이 「보호막이
 * 걸렸는데 화살에 깨진다」를 들고 왔고, 26.3 바이트코드를 다시 읽어 보니 <b>그 칸이 하는 일과
 * 이 카드가 약속한 것이 애초에 같지 않았다.</b> 확인한 것을 그대로 적어 둔다.
 *
 * <pre>{@code
 * javap -p -c net/minecraft/world/entity/boss/enderdragon/EndCrystal.class
 *   public final boolean hurtServer(ServerLevel, DamageSource, float)
 *     0: this.isInvulnerableToBase(source)  → 참이면 곧바로 false
 *
 * javap -p -c net/minecraft/world/entity/Entity.class
 *   protected final boolean isInvulnerableToBase(DamageSource source) {
 *     return this.isRemoved()
 *         || this.isInvulnerable() && !source.is(BYPASSES_INVULNERABILITY)
 *                                  && !source.isCreativePlayer()
 *         || source.is(IS_FIRE) && this.fireImmune()
 *         || source.is(IS_FALL) && this.is(FALL_DAMAGE_IMMUNE);
 *   }
 *   public boolean isInvulnerable()          { permanentlyInvulnerable || invulnerableTime > 0 }
 *   public final void commonTick()           { if (invulnerableTime > 0) invulnerableTime--; … }
 *   public void saveWithoutId(ValueOutput o) { if (invulnerableTime > 0) o.putInt("invulnerable_time", …) }
 * }</pre>
 *
 * <p>읽고 나서 바뀐 것이 셋이다.
 *
 * <ol>
 *   <li><b>{@code invulnerableTime} 은 우리 칸이 아니다.</b> 개체 하나에 <b>한 칸</b>뿐이고
 *       바닐라와 다른 카드가 같은 칸에 쓴다. 이 저장소만 해도 {@link TrialCrystalRevive} 가
 *       {@code raise}·{@code release}·{@code sweep} 세 곳에서 같은 칸을 쓰고, {@code sweep} 은
 *       <b>판의 모든 크리스탈을 0 으로 민다.</b> {@code TrialRisks} 의 실행 순서는 카드 순서라
 *       그쪽이 우리 뒤에 돌면 우리가 건 값이 그 틱 내내 0 인 채로 남는다 — 그 틈에 들어온 화살은
 *       그대로 통과한다. 앞선 구현의 「둘 다 매 틱 다시 걸므로 대칭이다」는 <b>틀린 설명</b>이다.
 *       대칭인 것은 「다시 거는 쪽」이고, 비는 것은 <b>두 실행기 사이의 틈</b>이다</li>
 *   <li><b>그 칸은 저장된다.</b> {@code saveWithoutId} 가 {@code invulnerable_time} 으로 적는다.
 *       세는 값이라 언젠가 0 이 되긴 하지만, 우리가 켠 것이 개체에 남아 월드 파일까지 따라간다</li>
 *   <li><b>{@code isInvulnerableToBase} 는 「무적이라 안 맞았다」로 끝난다.</b> 맞은 티가 전혀
 *       나지 않아 「이건 지금 못 깬다」를 화면에서 읽을 수 없다. 사람이 「버그인가」라고 한 것은
 *       이 때문이기도 하다</li>
 * </ol>
 *
 * <p>그래서 무적 칸을 <b>아예 쓰지 않는다.</b> 대신 「크리스탈 보호막」
 * ({@link TrialCrystalGuard} → {@link CrystalWatch} → {@code EndCrystalGuardMixin})이 이미
 * 증명한 길을 그대로 쓴다 — <b>{@code EndCrystal.hurtServer} 의 {@code HEAD} 에서 거절한다.</b>
 * 저쪽은 {@code final} 이라 하위 클래스가 가로챌 수 없는 자리임을 바이트코드로 못박아 두었고,
 * {@code hurtServer} 는 피해량을 쓰지 않으므로 「깎는다」가 아니라 「통째로 거절」밖에 없다는 것도
 * 이미 적혀 있다.
 *
 * <p>다만 <b>그대로는 쓸 수 없어 두 가지를 늘렸다.</b>
 *
 * <ul>
 *   <li>저쪽 깃발은 <b>판 전체 · 투사체 한정</b>({@code boolean arrowImmune})이고 이 카드는
 *       <b>크리스탈 하나 · 모든 피해</b>다. 그래서 깃발이 아니라 <b>봉인된 크리스탈의
 *       {@code UUID}</b>({@link #sealedCrystal()})를 들고, 태그를 보지 않고 전부 거절한다</li>
 *   <li>저쪽에는 없는 <b>시한</b>이 필요하다 — 아래 「죽은 사람 스위치」</li>
 * </ul>
 *
 * <p>운영자 탈출구는 {@code isInvulnerableToBase} 가 남겨 둔 것과 <b>글자 그대로 같은 둘</b>이다.
 * {@code BYPASSES_INVULNERABILITY}({@code out_of_world}·{@code generic_kill})와
 * {@code DamageSource.isCreativePlayer()}. 바닐라 무적이 열어 두는 문을 우리 봉인이 닫아 버리면
 * 운영자가 치울 길이 없어진다.
 *
 * <p>⚠ {@code setPermanentlyInvulnerable} 은 여전히 <b>쓰지 않는다.</b> {@code Invulnerable}
 * 태그로 저장되므로 보호막이 도는 중에 서버가 내려가면 영영 안 깨지는 크리스탈이 남는다. 까닭은
 * {@link TrialCrystalRevive} 클래스 설명에 이미 적혀 있다.
 *
 * <h2>죽은 사람 스위치 — 우리가 부르기를 멈추면 스스로 풀린다</h2>
 *
 * <p>무적 칸을 버리면서 <b>공짜로 얻던 안전장치 하나를 잃었다.</b> 그 칸은
 * {@code Entity.commonTick} 이 매 틱 1씩 깎아 주므로 우리가 죽어도 저절로 0 이 됐다. 정적 칸에
 * 적은 {@code UUID} 는 아무도 깎아 주지 않는다 — 드래곤이 죽어 세션이 사라지는 길에는
 * {@code TrialRisks.clearState} 가 끼어들지 않으므로({@code DragonTrialManager.tickSessions} 가
 * 세션만 지운다), 그대로 두면 <b>영영 안 깨지는 크리스탈</b>이 정확히 그 길로 생긴다.
 *
 * <p>그래서 봉인에 <b>유효기한</b>({@link #sealFreshUntil})을 함께 적는다. 매 틱
 * 「{@code now} + 남은 보호막 + 여유」로 다시 적고, 믹스인은 기한이 지난 봉인을 <b>없는 것으로
 * 본다.</b> 우리가 한 번이라도 못 부르면 여유만큼만 더 버티고 스스로 열린다 — 잃었던 성질을 그대로
 * 되찾은 셈이고, 이번에는 개체에 아무것도 남지 않으므로 저장 파일까지 따라가지도 않는다.
 *
 * <p>⚠ 기한을 재는 시계만은 <b>{@code now} 가 아니라 {@code level.getGameTime()}</b> 이다.
 * 죽은 사람 스위치는 「우리가 안 돌 때」를 재는 장치라 <b>우리가 돌려주는 값으로는 잴 수 없다.</b>
 * 둘은 같은 눈금이다 — {@code DragonTrialManager} 가 {@code now = end.getGameTime()} 으로 시작한다.
 * 얼어붙은 판({@code TrialFreeze})에서도 어긋나지 않는다. 그쪽은
 * {@code ServerTickRateManager.setFrozen} 이라 <b>게임 시각이 아예 멈추고</b>, 그러면 기한도 멈추고
 * 개체도 틱을 안 받아 아무도 크리스탈을 때릴 수 없다. 카드의 시계는 그대로 {@code elapsed} 다.
 *
 * <h2>보호막은 언제나 하나뿐이다 — 한 틱에 여럿이 부서져도</h2>
 *
 * <p>카드에 적힌 것은 「가장 가까운 <b>다른 크리스탈이</b>」 하나다. 부서진 개수만큼 걸면 연쇄
 * 폭발 한 번에 남은 기둥이 통째로 잠기고, 그때는 「순서를 바꿔라」가 아니라 「그동안 아무것도 하지
 * 마라」가 된다.
 *
 * <p>그래서 한 틱에 여럿이 부서지면 <b>부서진 것들과 남은 것들 사이에서 가장 가까운 한 쌍</b>을
 * 골라 그 한 쌍의 남은 쪽에만 건다({@link #nearestIndex}). 「가장 가까운 이웃을 잠근다」가 부서진
 * 것이 몇이든 같은 규칙으로 성립하고, 연쇄가 났을 때 가장 아픈 자리 — 방금 무너진 무리 바로
 * 옆 — 가 정확히 막힌다.
 *
 * <p>보호막이 걸려 있는 동안 <b>다른</b> 크리스탈이 부서지면 보호막은 <b>새 이웃으로 옮겨
 * 간다.</b> 쌓지 않는 것과 같은 이유다. 대가는 「둘을 거의 동시에 깨면 보호막을 흘려보낼 수
 * 있다」인데, 그것 자체가 이 카드가 요구하는 「순서를 바꿔라」의 한 답이라 뜻이 어긋나지 않는다.
 *
 * <h2>거리는 수평으로 잰다</h2>
 *
 * <p>크리스탈은 <b>반경 42 원 위</b> 기둥 꼭대기에 있고 높이만 y 76~103 으로 제각각이다. 이
 * 카드가 말하는 「가장 가까운」은 <b>그 원 위의 이웃</b>이지 「높이까지 비슷한 것」이 아니다.
 *
 * <p>세로를 넣어도 답은 같다. 기둥 열 개가 원 위에 고르게 놓이므로 이웃까지의 현은
 * {@code 2 × 42 × sin(π/10) ≈ 26}, 한 칸 건너는 {@code 2 × 42 × sin(2π/10) ≈ 49} 다. 이웃이
 * 한 칸 건너보다 멀게 나오려면 높이 차가 {@code √(49² − 26²) ≈ 42} 를 넘어야 하는데 기둥 높이의
 * 폭은 {@code 103 − 76 = 27} 이라 <b>구조적으로 뒤집힐 수 없다.</b> 그러니 세로를 넣고 빼는
 * 것은 결과가 아니라 <b>뜻</b>의 문제고, 뜻이 분명한 쪽을 골랐다. {@link EndPillars#nearestTop}
 * 이 세로까지 재는 것과 어긋나 보이지만 그쪽은 <b>바닥에 선 사람에게서</b> 기둥까지를 재는 일이라
 * 사정이 다르다 — 거기서는 높이가 실제로 순서를 뒤집는다.
 *
 * <h2>연출은 껍질이다 — 빔을 쓰지 않는다</h2>
 *
 * <p>「부활」과 「과충전」은 {@code EndCrystal.setBeamTarget} 으로 거리 제한 없는 선을 긋는다.
 * 이 카드는 <b>일부러 쓰지 않았다.</b> 「과충전」이 같은 풀({@code POOL_FIRST_CRYSTAL})이라 한
 * 판에 함께 뜰 수 있고, 그쪽은 달아오른 크리스탈에 <b>매 틱</b> 빔을 다시 쓴다. 같은 크리스탈에
 * 둘이 붙으면 매 틱 서로 덮어써 <b>두 카드의 연출이 같이 깨진다.</b>
 *
 * <p>대신 크리스탈을 감싸는 <b>파티클 껍질</b>을 그린다. 껍질은 「어느 것인가」와 「얼마나
 * 남았는가」를 한 신호로 같이 말한다(「과충전」의 자라는 빛기둥과 같은 문법이다).
 *
 * <p>색은 {@link TrialWarning.Colors} 에서 고르지 않는다. 그 규약은 <b>바닥 표식이 요구하는
 * 행동</b>을 뜻하는데(서 있으면 죽는다·밀려난다·너를 노린다·번개), 「이건 지금 못 깬다」는 그
 * 넷 중 어느 것도 아니고 새 색을 만드는 것은 규약을 규약이 아니게 만든다. 그래서 색이 아니라
 * <b>바닐라 파티클 종류</b>로 가른다.
 *
 * <h2>30초짜리 초읽기는 오므라드는 반경으로 못 읽는다</h2>
 *
 * <p>8초일 때는 껍질 반경 {@code 2.4 → 1.3} 이 초읽기였다. 30초로 늘리면 <b>초당 0.037
 * 블록</b>이라 사람 눈에는 아예 멈춘 것으로 보이고, 크리스탈은 반경 42 기둥 꼭대기라 그 1.1 블록이
 * 40 블록 밖에서 <b>1.6도</b>밖에 안 된다. 「얼마나 남았는가」를 말하는 신호가 사실상 사라진다.
 *
 * <p>그래서 초읽기를 <b>적도 띠의 호 길이</b>로 옮겼다({@link #shellArc}). 갓 걸렸을 때는 완전한
 * 고리, 풀릴 때쯤이면 짧은 조각이다. 같은 30초에 <b>360도</b>를 쓰므로 반경이 쓰던 1.6도와는
 * 자릿수가 다르고, 「고리가 닫혀 있나 열려 있나」는 멀리서도 읽힌다. 위아래 띠는 <b>언제나
 * 온전한 고리</b>로 남겨 「감싸고 있다」가 무너지지 않게 한다 — 셋이 다 같이 줄면 그냥 사라지는
 * 것으로 보인다.
 *
 * <p>반경 쪽은 그대로 두었다. 이제는 초읽기가 아니라 <b>거드는 신호</b>다. 둘 다 단조롭게 줄므로
 * 서로 거짓말하지 않는다.
 *
 * <p>점 수는 <b>호의 길이에 비례</b>해 뽑는다({@link #bandPoints}). 예전에는 띠마다 20 점을 똑같이
 * 찍었는데 위아래 띠는 둘레가 {@code cos(π/4) ≈ 0.71} 배라 거기만 촘촘했다. 비례로 바꾸면 밀도가
 * 고르고, 한 번에 나가는 패킷도 {@code 60} 에서 처음 {@code 48} · 끝 {@code 32} 로 준다 —
 * 30초를 버텨야 하니 그만큼이 그대로 이득이다.
 *
 * <h2>막힌 것이 눈에 보여야 한다</h2>
 *
 * <p>바닐라 무적은 <b>아무 반응도 내지 않는다.</b> 화살을 쐈는데 소리도 불꽃도 없으면 사람은
 * 「막혔다」가 아니라 「버그다」로 읽는다 — 실제로 그렇게 들어왔다.
 *
 * <p>그래서 봉인이 한 방을 거절하면 믹스인이 {@link #noteDeflected()} 로 적어 두고, <b>다음 틱의
 * 실행기가</b> 튕겨 낸 연출을 낸다. 믹스인 쪽에서 바로 내지 않는 이유는 둘이다 — 피격 경로에
 * 파티클·소리를 넣으면 연쇄 폭발 한 번에 수십 번 나가고, 소리는 <b>사람마다 그 자리에서</b> 내야
 * 하는데 믹스인에는 팀 명단이 없다. 한 틱에 몇 방을 막았든 <b>깃발 하나</b>라 연출도 한 번이다.
 *
 * <h2>이 저장소가 이미 밟은 지뢰</h2>
 *
 * <ul>
 *   <li><b>파티클은 긴 형식으로.</b> 짧은 형식은 {@code overrideLimiter=false} 로 내려가 발생
 *       지점 32 블록 안에만 간다. 크리스탈은 반경 42 기둥 꼭대기라 거의 언제나 그 밖이다 —
 *       「부활」이 이 함정으로 연출을 통째로 한 번 잃었다</li>
 *   <li><b>소리도 32 블록에서 잘린다.</b> {@code level.playSound} 는 자리에 소리를 놓는 것이라
 *       크리스탈 자리에서 울리면 기둥에 올라간 사람 말고는 아무도 못 듣는다. 그래서 소리는
 *       <b>사람마다 그 자리에서</b> 낸다</li>
 *   <li><b>자막은 쓰지 않는다.</b> 화면 아래 글자는 전부 걷어냈다({@link TrialWarning#shout})</li>
 *   <li><b>크리스탈을 부수지도 만들지도 않는다.</b> 이 카드가 하는 일은 보호막뿐이다</li>
 *   <li><b>개체에 아무것도 쓰지 않는다.</b> 봉인은 이 클래스의 정적 칸에만 산다. 그래서 되돌릴 것도
 *       저장될 것도 없고, 서버가 그냥 죽어도 다음에 뜬 판에는 봉인이 없다</li>
 *   <li><b>시간은 받은 {@code now} 로만 잰다.</b> {@code level.getGameTime()} 을 부르면 얼어붙은
 *       판({@code TrialFreeze})에서 혼자 시간이 흐른다. 유일한 예외가 죽은 사람 스위치이고,
 *       까닭은 위에 적었다</li>
 *   <li><b>드래곤을 건드리지 않는다.</b> {@code setPhase} 도 {@code setTarget} 도 부르지 않는다 —
 *       드래곤 페이즈에 끼어들었다가 착지를 아예 안 하게 된 사고가 있다
 *       ({@link TrialDragonFocus} 클래스 설명)</li>
 * </ul>
 */
public final class TrialCrystalLink {

	// ------------------------------------------------------------------ 못박아 둔 값

	/** 껍질을 다시 그리는 간격(틱). 매 틱 그리면 이 카드 하나가 파티클 예산을 먹는다. */
	private static final int PULSE_TICKS = 4;

	/**
	 * <b>온전한 적도 띠</b> 하나에 찍는 점 수. 다른 띠와 짧아진 호는 여기서 비례로 깎는다
	 * ({@link #bandPoints}).
	 */
	static final int SHELL_POINTS = 20;

	/**
	 * 어떤 띠도 이보다 적게 찍지 않는다.
	 *
	 * <p>초읽기가 끝나 갈 때 적도 호는 아주 짧아지는데, 비례만 따르면 점 한두 개가 되어 <b>남은
	 * 조각이 보이지 않는다.</b> 마지막 순간이 가장 알고 싶은 순간이라 바닥을 둔다.
	 */
	static final int SHELL_MIN_POINTS = 4;

	/**
	 * 껍질을 이루는 위도들(라디안).
	 *
	 * <p>적도 하나만 그리면 <b>고리</b>로 보이고, 고리는 이 저장소에서 「바닥 위험 범위」라는 뜻을
	 * 이미 갖고 있다({@link TrialWarning#markGround}). 위아래를 더해야 <b>공</b>으로 읽혀 「감싸고
	 * 있다」가 된다.
	 *
	 * <p>순서가 뜻을 갖는다 — <b>{@code 0.0} 인 적도가 초읽기</b>고 나머지 둘은 언제나 온전한
	 * 고리다({@link #shell}).
	 */
	private static final double[] SHELL_BANDS = {-Math.PI / 4.0, 0.0, Math.PI / 4.0};

	/**
	 * 보호막이 갓 걸렸을 때의 껍질 반경(블록).
	 *
	 * <p>크리스탈의 몸은 2×2 라 반폭이 1 이다. 그보다 넉넉히 커야 껍질이 몸에 파묻히지 않고
	 * 「무엇인가가 감싸고 있다」로 읽힌다.
	 */
	static final double SHELL_MAX = 2.4;

	/**
	 * 풀리기 직전의 껍질 반경(블록).
	 *
	 * <p>0 으로 오므리지 <b>않는다.</b> 마지막 순간이 가장 알고 싶은 순간인데 그때 껍질이 사라지면
	 * 「이미 풀렸다」로 읽혀 한 번 더 헛되이 쏘게 된다. 몸(반폭 1)에 달라붙은 채로 끝난다.
	 */
	static final double SHELL_MIN = 1.3;

	/**
	 * 풀리기 직전에 적도 띠에 남는 호의 비율.
	 *
	 * <p>0 으로 닫지 <b>않는다.</b> 다 사라지면 「이미 풀렸다」로 읽혀 한 번 더 헛되이 쏘게 된다 —
	 * {@link #SHELL_MIN} 과 정확히 같은 이유다. 0.12 는 약 43도라 40 블록 밖에서도 조각으로 보인다.
	 */
	static final double ARC_MIN = 0.12;

	/** 껍질이 한 틱에 도는 각(라디안). 멈춰 있으면 점 무늬로 보이고, 돌면 껍질로 보인다. */
	private static final double SHELL_SPIN = 0.05;

	/** 껍질의 중심을 개체 자리에서 이만큼 올린다. 개체 자리는 몸의 바닥이라 그대로 쓰면 아래로 쏠린다. */
	private static final double SHELL_LIFT = 1.0;

	/** 한 방을 튕겨 냈을 때 그리는 불꽃 고리의 점 수. 껍질보다 성글어야 「따로 난 일」로 읽힌다. */
	private static final int DEFLECT_POINTS = 12;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 들고 있는 상태가 <b>어느 카드의 것인가</b>.
	 *
	 * <p>위험은 값(레코드)이라 상태를 들 수 없어 정적 칸을 쓴다. 판이 갈리거나 세션을 되살려 받은
	 * 틱이 달라지면 지난 판의 명단이 그대로 이어지는데, 그 명단의 개체들은 이미 사라진 뒤라
	 * <b>첫 틱에 「전부 부서졌다」로 읽힌다.</b> 받은 틱이 바뀌면 처음부터 다시 센다.
	 */
	private static long owner = Long.MIN_VALUE;

	/**
	 * 지난 틱에 기둥 위에 살아 있던 크리스탈들.
	 *
	 * <p><b>개체를 그대로 든다.</b> 좌표만 적어 두면 「사라졌다」는 알아도 <b>왜 사라졌는지</b>를
	 * 물을 수 없어 청크 언로드와 파괴가 구별되지 않는다. 개체를 들고 있으면
	 * {@code getRemovalReason()} 한 줄로 갈린다.
	 *
	 * <p>{@link LinkedHashMap} 인 것은 같은 판이 두 번 돌 때 <b>같은 순서로 같은 답</b>이 나오게
	 * 하기 위해서다. 거리가 똑같은 두 이웃 중 어느 쪽이 잠기는지가 판마다 달라지면 사람이 규칙을
	 * 배울 수 없다.
	 */
	private static final Map<UUID, EndCrystal> WATCHED = new LinkedHashMap<>();

	/**
	 * 지금 보호막을 두른 크리스탈. 없으면 {@code null}.
	 *
	 * <p>여기 개체를 드는 것은 <b>연출 때문</b>이다 — 자리를 물어야 껍질을 그리고, 사라졌는지를
	 * 물어야 손을 놓는다. 봉인 자체는 개체가 아니라 {@link #sealedId} 가 한다.
	 */
	private static @Nullable EndCrystal shielded;

	/**
	 * 보호막이 끝나는 시각. <b>월드 시간이 아니라</b> {@link TrialRisks#elapsedSinceGrant} 값이다.
	 *
	 * <p>월드 시간으로 적으면 얼어붙은 판에서 혼자 흐르고, 세션을 복원해 {@code now} 가 받은 틱보다
	 * 작아진 판에서 음수로 떨어진다.
	 */
	private static long shieldEnds;

	/**
	 * 봉인된 크리스탈의 {@code UUID}. 없으면 {@code null}.
	 *
	 * <p><b>이 한 칸이 실제로 막는 것이다.</b> {@code EndCrystalSealMixin} 이 모든 크리스탈 피격에서
	 * 읽는다. {@code volatile} 인 까닭은 {@link CrystalWatch} 와 같다 — 쓰는 쪽(시련 틱)과 읽는
	 * 쪽(피격)이 사실 같은 스레드지만, 믹스인은 남의 클래스 안에서 도는 코드라 이 파일만 보고는
	 * 보증할 수 없다.
	 */
	private static volatile @Nullable UUID sealedId;

	/**
	 * 봉인의 유효기한. <b>{@code level.getGameTime()} 눈금</b>이다.
	 *
	 * <p>죽은 사람 스위치다. 클래스 설명의 「죽은 사람 스위치」를 볼 것 — 여기가 유한한 수를 들고
	 * 있는 한 「영영 안 깨지는 크리스탈」은 구조적으로 생길 수 없다.
	 */
	private static volatile long sealFreshUntil;

	/** 지난 틱 이후로 봉인이 한 방이라도 거절했는가. 믹스인이 세우고 실행기가 내린다. */
	private static volatile boolean deflected;

	private TrialCrystalLink() {
	}

	// ------------------------------------------------------------------ 믹스인이 보는 자리

	/**
	 * 지금 봉인된 크리스탈. 없으면 {@code null}.
	 *
	 * <p>믹스인이 <b>모든 크리스탈 피격</b>에서 첫 줄로 부른다. 그래서 이 메서드는 칸 하나를 읽는
	 * 것 이상을 해서는 안 된다 — 여기에 계산을 넣으면 시련이 하나도 없는 서버까지 값을 치른다
	 * ({@link CrystalWatch#arrowImmune()} 와 같은 규칙이다).
	 */
	public static @Nullable UUID sealedCrystal() {
		return sealedId;
	}

	/**
	 * 봉인이 아직 살아 있는가.
	 *
	 * <p>시계를 <b>인자로 받는다.</b> 이 파일이 {@code level.getGameTime()} 을 직접 부르지 않게
	 * 하려는 것이다 — 카드의 시계는 어디까지나 {@code now}/{@code elapsed} 이고, 죽은 사람
	 * 스위치만 다른 시계를 쓴다는 사실이 호출부에 그대로 드러나야 한다.
	 */
	public static boolean sealHolds(long gameTime) {
		return sealFresh(sealedId, sealFreshUntil, gameTime);
	}

	/**
	 * 봉인이 한 방을 거절했다고 적는다. 믹스인이 부른다.
	 *
	 * <p>한 틱에 몇 번 불려도 <b>깃발 하나</b>다. 연쇄 폭발은 한 틱에 수십 번 들어오는데 그때마다
	 * 연출을 내면 화면이 하얘지고, 그것은 「막혔다」가 아니라 「무슨 일이 났다」로 읽힌다.
	 */
	public static void noteDeflected() {
		deflected = true;
	}

	// ------------------------------------------------------------------ 진입점

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자도 받는 것은 {@link TrialRisks} 의
	 * 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <p>순서가 규칙이다. <b>부서진 것을 먼저 거두고</b>({@link #takeBroken}) 명단을 새로
	 * 적은 뒤에 보호막을 걸고, 유지는 맨 마지막이다. 유지를 먼저 돌리면 이번 틱에 새로 건 보호막이
	 * 그 틱에는 껍질 없이 지나가 「걸렸는데 아무것도 안 보이는」 한 틱이 생긴다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param dragon  <b>쓰지 않는다.</b> 이 카드는 드래곤에게서 아무것도 읽지 않고 아무것도 쓰지
	 *                않는다 — 클래스 설명의 「드래곤을 건드리지 않는다」를 볼 것
	 * @param granted 카드를 받은 틱. 시간은 월드 시간이 아니라 여기서부터 센다
	 * @param now     지금의 게임 시각. 카드의 시계로 쓰는 것이 아니라 <b>봉인의 유효기한</b>을 적는
	 *                데 쓴다 — 클래스 설명의 「죽은 사람 스위치」를 볼 것
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.CrystalLink risk) {
		if (end == null || risk == null || risk.shieldTicks() <= 0) {
			return;
		}
		// 엔드 밖에서 돌 일은 없지만(분배기가 END 월드만 넘긴다) 한 줄로 못박아 둔다. 이 저장소에
		// 차원을 안 가려서 생긴 버그가 이미 있고, 여기서 새는 값은 「안 깨지는 크리스탈」이다.
		if (end.dimension() != Level.END) {
			return;
		}

		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		if (owner != granted) {
			// 남의 판에서 넘어온 상태다. 걷어 내고 명단부터 새로 적는다 — 옛 명단을 그대로 두면
			// 첫 틱에 지난 판의 크리스탈이 「방금 부서졌다」로 읽힌다.
			clearState();
			owner = granted;
		}

		List<EndCrystal> onSeats = seatCrystals(end);
		List<Vec3> broken = takeBroken();
		remember(onSeats);

		if (!broken.isEmpty()) {
			arm(end, members, broken, onSeats, elapsed, now, risk.shieldTicks());
		}
		sustain(end, members, elapsed, now, risk.shieldTicks());
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>여기가 <b>봉인을 푸는 마지막 정상 경로</b>다. 되돌릴 것은 전부 이 클래스의 정적 칸이라
	 * <b>월드도 개체도 필요 없다</b> — {@code SERVER_STOPPED} 에서도 불리는 길이라 이것이 중요하다.
	 * 예전 구현은 개체에 {@code setInvulnerableTime(0)} 을 써야 했고, 그래서 개체를 들고 있어야만
	 * 되돌릴 수 있었다. 이제는 {@link #sealedId} 를 비우는 것으로 끝난다.
	 *
	 * <p>껍질은 되돌릴 것이 없다. 파티클은 한 번 보내고 스스로 사라지는 것이라 우리가 끄지 않아도
	 * 다음 틱에 남지 않는다. 빔도 건드린 적이 없으므로 걷지 않는다 — 여기서 {@code setBeamTarget}
	 * 을 부르면 같은 판에서 돌고 있는 「과충전」의 연출을 우리가 지운다.
	 */
	public static void clearState() {
		release();
		WATCHED.clear();
		owner = Long.MIN_VALUE;
	}

	// ------------------------------------------------------------------ 「부서졌다」를 거두는 자리

	/**
	 * 지난 틱 이후로 <b>부서진</b> 크리스탈들의 자리.
	 *
	 * <p>{@code KILLED} 만 센다. 청크 언로드({@code UNLOADED_TO_CHUNK})와 「부활」이 사람 발밑의
	 * 것을 거두는 {@code DISCARDED} 는 파괴가 아니다 — 이유를 안 보고 「명단에서 사라졌다」로
	 * 잡으면 <b>청크가 한 번 내려갈 때마다</b> 보호막이 걸린다.
	 *
	 * <p>자리는 지워진 개체에서 그대로 읽는다. {@code Entity.position} 은 제거 뒤에도 마지막 값을
	 * 들고 있으므로 「부서진 자리」가 정확하다.
	 *
	 * <p>같은 것을 두 번 세지 않는 것은 {@link #remember} 가 매 틱 명단을 <b>살아 있는 것으로만</b>
	 * 다시 채우기 때문이다. 거둔 개체는 그 자리에서 명단 밖으로 떨어진다.
	 */
	private static List<Vec3> takeBroken() {
		List<Vec3> spots = new ArrayList<>();
		for (EndCrystal crystal : WATCHED.values()) {
			if (!crystal.isRemoved()) {
				continue;
			}
			if (crystal.getRemovalReason() != Entity.RemovalReason.KILLED) {
				continue;
			}
			spots.add(crystal.position());
		}
		return spots;
	}

	/** 이번 틱의 명단을 적는다. 살아 있는 것만 남으므로 사라진 것은 여기서 저절로 떨어진다. */
	private static void remember(List<EndCrystal> onSeats) {
		WATCHED.clear();
		for (EndCrystal crystal : onSeats) {
			WATCHED.put(crystal.getUUID(), crystal);
		}
	}

	// ------------------------------------------------------------------ 보호막

	/**
	 * 부서진 것들에서 가장 가까운 남은 하나에 보호막을 건다.
	 *
	 * <p>남은 것이 하나도 없으면 아무 일도 하지 않는다 — <b>마지막 하나를 부순 경우</b>가 여기다.
	 * 걸 곳이 없는데 소리만 울리면 「무엇이 잠겼는지」를 찾아 헤매게 된다.
	 *
	 * <p>이미 보호막이 걸려 있었다면 <b>그것을 풀고 옮긴다.</b> 쌓지 않는 이유는 클래스 설명의
	 * 「보호막은 언제나 하나뿐이다」에 있다. 옮겨 간 곳이 마침 원래 그 크리스탈이면 시간만
	 * 늘어난다.
	 */
	private static void arm(ServerLevel end, @Nullable List<ServerPlayer> members,
			List<Vec3> broken, List<EndCrystal> survivors, long elapsed, long now, int shieldTicks) {
		List<Vec3> seats = new ArrayList<>(survivors.size());
		for (EndCrystal crystal : survivors) {
			seats.add(crystal.position());
		}
		int pick = nearestIndex(broken, seats);
		if (pick < 0) {
			return;
		}

		EndCrystal next = survivors.get(pick);
		if (shielded != null && shielded != next) {
			// 옮기기 전에 옛 것을 반드시 푼다. 안 풀면 봉인이 둘이 되는데, 칸은 하나뿐이라
			// 실제로는 「옛 것이 기한까지 잠긴 채 우리 손을 떠난다」가 된다.
			release();
		}
		shielded = next;
		shieldEnds = elapsed + shieldTicks;
		seal(next, shieldTicks, now);
		// 껍질을 여기서 한 번 그린다. 유지 쪽은 주기를 타므로 그쪽에만 맡기면 소리가 난 뒤
		// 최대 PULSE_TICKS 동안 화면에 아무것도 없는 틈이 생긴다 — 그 틈이 「걸렸다는데 어디에?」다.
		shell(end, next.position(), SHELL_MAX, shellArc(shieldTicks, shieldTicks), elapsed);
		announce(end, members);
		SharedFateMod.LOGGER.info("[END] 「연결된 수정」 — 크리스탈이 부서져 가장 가까운 하나가 {}틱 동안 잠깁니다",
				shieldTicks);
	}

	/**
	 * 보호막 한 틱 — 봉인의 기한을 미루고, 막은 것을 알리고, 껍질을 그린다.
	 *
	 * <p>기한을 <b>매 틱 미루는 것</b>이 이 카드의 안전장치다. 미루는 값이 「남은 시간 + 여유」라
	 * 우리가 한 번이라도 못 부르면 그 순간부터 여유만큼만 더 버티고 스스로 열린다. 드래곤이
	 * 죽어 세션이 사라지는 길에는 {@code TrialRisks.clearState} 가 끼어들지 않으므로, 그 길에서
	 * 봉인을 푸는 것은 정확히 이 성질이다.
	 *
	 * <p>지켜보던 크리스탈이 <b>사라져 있으면</b> 봉인을 풀고 손을 놓는다. 청크 언로드로 없어졌을
	 * 수 있고, 없는 것을 찾다 예외를 내면 <b>그 틱의 다른 시련까지 멈춘다.</b>
	 *
	 * <p>튕겨 낸 연출은 <b>주기를 타지 않는다.</b> 쏜 뒤 최대 {@code PULSE_TICKS} 만큼 아무 반응이
	 * 없으면 그 침묵이 곧 「버그인가」다.
	 */
	private static void sustain(ServerLevel end, @Nullable List<ServerPlayer> members, long elapsed,
			long now, int shieldTicks) {
		EndCrystal crystal = shielded;
		if (crystal == null) {
			return;
		}
		if (crystal.isRemoved()) {
			// 지킬 것이 없어졌다. 봉인도 함께 푼다 — 개체가 사라져도 UUID 는 우리 칸에 남는다.
			release();
			return;
		}

		int remaining = remainingShield(elapsed, shieldEnds, shieldTicks);
		if (remaining <= 0) {
			release();
			expire(end, members);
			return;
		}

		seal(crystal, remaining, now);

		if (deflected) {
			deflected = false;
			deflect(end, members, crystal.position(), elapsed);
		}

		if (elapsed % PULSE_TICKS != 0L) {
			return;
		}
		shell(end, crystal.position(), shellRadius(remaining, shieldTicks),
				shellArc(remaining, shieldTicks), elapsed);
	}

	/**
	 * 이 크리스탈을 봉인하고 기한을 미룬다.
	 *
	 * <p>개체에는 <b>아무것도 쓰지 않는다.</b> 쓰는 곳은 우리 정적 칸 둘뿐이다.
	 */
	private static void seal(EndCrystal crystal, int remaining, long now) {
		sealedId = crystal.getUUID();
		sealFreshUntil = now + guardTicks(remaining);
	}

	/**
	 * 들고 있던 보호막을 되돌린다.
	 *
	 * <p>되돌릴 것은 정적 칸 넷뿐이다. 개체도 블록도 빔도 드래곤도 건드린 적이 없다.
	 * {@code setPermanentlyInvulnerable} 은 <b>켠 적이 없으므로 끄지도 않는다</b> — 그것까지 끄는
	 * 것은 「부활」의 일이고(옛 저장 파일과 소환 의식을 함께 보는 자리다), 여기서 같이 끄면 우리가
	 * 켜지 않은 값을 우리가 지우는 셈이 된다.
	 *
	 * <p>{@link #deflected} 도 함께 내린다. 봉인이 없는데 깃발만 남아 있으면 <b>다음에 걸리는
	 * 보호막이 맞지도 않았는데 튕겨 내는 연출</b>로 시작한다.
	 */
	private static void release() {
		sealedId = null;
		sealFreshUntil = 0L;
		deflected = false;
		shielded = null;
		shieldEnds = 0L;
	}

	// ------------------------------------------------------------------ 연출

	/**
	 * 크리스탈을 감싸는 파티클 껍질. <b>긴 형식으로 보낸다.</b>
	 *
	 * <p>첫 {@code boolean} 을 {@code false} 로 되돌리면 서버가 발생 지점 32 블록 안의 사람에게만
	 * 패킷을 보내고 클라이언트가 한 번 더 거른다. 크리스탈은 반경 42 기둥 꼭대기라 거의 언제나 그
	 * 밖이므로 <b>보호막이 통째로 안 보이게 된다.</b> 「부활」이 정확히 이 함정에 빠진 적이 있다.
	 *
	 * <p>{@code END_ROD} 를 고른 것은 밝고 오래 남아 <b>멀리서도 모양이 읽히기</b> 때문이다.
	 * 「부활」도 같은 파티클을 쓰지만 그쪽은 크리스탈이 설 때 한 번 터지는 꽃이고 이것은 계속
	 * 도는 껍질이라, 화면에서 섞이지 않는다.
	 *
	 * <p><b>적도 띠만 초읽기다.</b> 호가 {@code arc} 만큼만 그려져 시간이 갈수록 고리가 열린다.
	 * 위아래 두 띠는 언제나 온전한 고리라 「감싸고 있다」가 끝까지 남는다 — 까닭은 클래스 설명의
	 * 「30초짜리 초읽기는 오므라드는 반경으로 못 읽는다」에 있다.
	 *
	 * <p>껍질을 천천히 돌린다. 같은 점에 계속 찍으면 점 무늬로 보이고, 돌면 면으로 보인다. 도는
	 * 것은 호의 <b>길이</b>를 바꾸지 않으므로 초읽기를 흐리지 않는다.
	 *
	 * @param radius 남은 시간이 정하는 반경. 이제는 거드는 신호다
	 * @param arc    적도 띠에 그릴 호의 비율. {@code 1.0} 이면 온전한 고리다
	 */
	private static void shell(ServerLevel end, Vec3 at, double radius, double arc, long elapsed) {
		double spin = elapsed * SHELL_SPIN;
		for (double band : SHELL_BANDS) {
			double ringRadius = radius * Math.cos(band);
			double lift = radius * Math.sin(band);
			// 적도만 초읽기다. 부동소수 비교로 보이지만 SHELL_BANDS 에 적은 리터럴 0.0 을 그대로
			// 읽는 것이라 오차가 끼어들 자리가 없다.
			double span = band == 0.0 ? Math.PI * 2.0 * arc : Math.PI * 2.0;
			int points = bandPoints(ringRadius, radius, span);
			for (int index = 0; index < points; index++) {
				double angle = spin + (span * index) / points;
				end.sendParticles(ParticleTypes.END_ROD, true, false,
						at.x + Math.cos(angle) * ringRadius,
						at.y + SHELL_LIFT + lift,
						at.z + Math.sin(angle) * ringRadius,
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	/**
	 * 한 방을 튕겨 냈다 — <b>막혔다는 것을 보이고 들린다.</b>
	 *
	 * <p>바닐라 무적은 아무 반응도 내지 않는다. 화살이 그냥 사라지면 사람은 「막혔다」가 아니라
	 * 「버그다」로 읽는다.
	 *
	 * <p>{@code ELECTRIC_SPARK} 는 이 저장소의 어느 시련도 쓰지 않는 파티클이다. 밝고 짧아
	 * <b>그 순간에만</b> 보이므로 계속 도는 {@code END_ROD} 껍질과 섞이지 않는다.
	 *
	 * <p>소리는 방패가 막는 소리다. 바닐라에서 그 소리의 뜻이 <b>글자 그대로 「막혔다」</b>라
	 * 배울 것이 없고, 걸릴 때와 풀릴 때의 전도체 소리({@link #announce}·{@link #expire})와도
	 * 귀로 섞이지 않는다. {@code Holder.Reference} 로 들어 있어 {@code value()} 로 꺼낸다.
	 */
	private static void deflect(ServerLevel end, @Nullable List<ServerPlayer> members, Vec3 at,
			long elapsed) {
		double spin = elapsed * SHELL_SPIN;
		for (int index = 0; index < DEFLECT_POINTS; index++) {
			double angle = spin + (Math.PI * 2.0 * index) / DEFLECT_POINTS;
			end.sendParticles(ParticleTypes.ELECTRIC_SPARK, true, false,
					at.x + Math.cos(angle) * SHELL_MAX,
					at.y + SHELL_LIFT,
					at.z + Math.sin(angle) * SHELL_MAX,
					2, 0.0, 0.0, 0.0, 0.0);
		}
		playEverywhere(end, members, SoundEvents.SHIELD_BLOCK.value(), 1.0F, 0.8F);
	}

	/**
	 * 보호막이 걸렸다고 알린다. <b>사람마다 그 자리에서</b> 울린다.
	 *
	 * <p>{@code level.playSound} 는 자리에 소리를 놓는 것이고 볼륨 1 이면 16 블록이다. 크리스탈
	 * 자리에서 한 번 울리면 기둥 꼭대기에 올라간 사람 말고는 <b>아무도 못 듣는다.</b>
	 *
	 * <p>전도체의 켜지는 소리다. 바닐라에서 전도체는 <b>사람을 감싸는 보호 장막</b>이라 그림이
	 * 그대로 맞고, 풀릴 때의 {@link #expire} 와 짝이 되어 「켜졌다/꺼졌다」를 배우기 쉽다.
	 * 「과충전」이 같은 전도체 <b>공격</b>음을 쓰지만 그쪽은 짧게 때리는 지직 소리라 귀로 섞이지
	 * 않는다 — 같은 물건의 다른 동작이므로 뜻도 어긋나지 않는다.
	 */
	private static void announce(ServerLevel end, @Nullable List<ServerPlayer> members) {
		playEverywhere(end, members, SoundEvents.CONDUIT_ACTIVATE, 1.0F, 1.4F);
	}

	/** 보호막이 풀렸다고 알린다. 「이제 깰 수 있다」는 말을 소리로 하는 유일한 자리다. */
	private static void expire(ServerLevel end, @Nullable List<ServerPlayer> members) {
		playEverywhere(end, members, SoundEvents.CONDUIT_DEACTIVATE, 1.0F, 1.4F);
	}

	/** 팀원 저마다의 자리에서 같은 소리를 낸다. 관전자는 뺀다 — 판에 끼어들지 않는 사람이다. */
	private static void playEverywhere(ServerLevel end, @Nullable List<ServerPlayer> members,
			SoundEvent sound, float volume, float pitch) {
		if (members == null) {
			return;
		}
		for (ServerPlayer member : members) {
			if (member == null || member.isSpectator()) {
				continue;
			}
			Vec3 at = member.position();
			end.playSound(null, at.x, at.y, at.z, sound, SoundSource.HOSTILE, volume, pitch);
		}
	}

	// ------------------------------------------------------------------ 월드에서 읽어 오는 것

	/**
	 * 지금 <b>기둥 위에</b> 살아 있는 크리스탈들.
	 *
	 * <p>사람이 손에 들고 다니며 터뜨리는 크리스탈은 뺀다. 방아쇠로 세면 폭탄 한 번에 기둥이
	 * 잠기고, 대상으로 세면 보호막이 아레나 바닥의 물건에 걸려 아무 뜻도 없이 사라진다.
	 *
	 * <p>판별은 {@link TrialCrystalOvercharge#onSeat} 를 그대로 쓴다. 같은 뜻의 판별을 여기 다시
	 * 적으면 두 카드가 서로 다른 「기둥 위」를 갖게 되고, 그 어긋남은 실제 전투에서만 드러난다.
	 */
	private static List<EndCrystal> seatCrystals(ServerLevel end) {
		List<Vec3> seats = EndPillars.crystalSeats(end);
		List<EndCrystal> onSeats = new ArrayList<>();
		if (seats.isEmpty()) {
			return onSeats;
		}
		for (EndCrystal crystal : end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive)) {
			if (TrialCrystalOvercharge.onSeat(crystal.position(), seats)) {
				onSeats.add(crystal);
			}
		}
		return onSeats;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 이 봉인이 아직 유효한가. <b>순수 계산</b>이라 시험이 여기를 직접 본다.
	 *
	 * <p>봉인이 없으면 거짓, 기한이 지났으면 거짓이다. 기한과 같은 눈금이면 <b>이미 끝난 것</b>으로
	 * 본다 — 열리는 쪽으로 기울여야 「영영 안 깨지는 크리스탈」이 생기지 않는다.
	 */
	static boolean sealFresh(@Nullable UUID sealed, long freshUntil, long gameTime) {
		return sealed != null && gameTime < freshUntil;
	}

	/**
	 * 부서진 자리들에서 가장 가까운 후보의 번호. 후보가 없으면 {@code -1}.
	 *
	 * <p>부서진 것이 여럿이면 <b>모든 쌍</b> 중 가장 가까운 한 쌍을 찾아 그 후보를 돌려준다.
	 * 「가장 가까운 이웃을 잠근다」가 부서진 개수와 무관하게 같은 규칙으로 성립하는 유일한 읽기다.
	 *
	 * <p>거리가 완전히 같은 후보가 둘이면 <b>먼저 온 것</b>이 이긴다. 무작위로 고르면 같은 판이
	 * 두 번 다르게 돌아 사람이 규칙을 배울 수 없다.
	 */
	static int nearestIndex(List<Vec3> from, List<Vec3> candidates) {
		int best = -1;
		double bestDistance = Double.MAX_VALUE;
		for (Vec3 origin : from) {
			for (int index = 0; index < candidates.size(); index++) {
				double distance = flatDistanceSqr(origin, candidates.get(index));
				if (distance < bestDistance) {
					bestDistance = distance;
					best = index;
				}
			}
		}
		return best;
	}

	/**
	 * 두 자리의 <b>수평</b> 거리의 제곱.
	 *
	 * <p>세로를 빼는 까닭은 클래스 설명의 「거리는 수평으로 잰다」에 있다 — 크리스탈은 전부 같은
	 * 원 위에 있고, 기둥 높이 차(최대 27)로는 이웃 순서가 뒤집히지 않는다.
	 */
	static double flatDistanceSqr(Vec3 a, Vec3 b) {
		double dx = a.x - b.x;
		double dz = a.z - b.z;
		return dx * dx + dz * dz;
	}

	/**
	 * 보호막이 끝나기까지 남은 틱. 0 이면 이번 틱에 풀린다.
	 *
	 * <p>위를 {@code shieldTicks} 로 <b>자른다.</b> 세션을 복원하면 {@code elapsed} 가 뒤로 갈 수
	 * 있고, 그러면 남은 시간이 카드에 적힌 것보다 길어져 30초짜리 보호막이 한참 더 간다.
	 */
	static int remainingShield(long elapsed, long endsAt, int shieldTicks) {
		int span = Math.max(0, shieldTicks);
		long left = endsAt - elapsed;
		return (int) Math.max(0L, Math.min(span, left));
	}

	/**
	 * 이번 틱에 밀어 둘 봉인의 여명(틱).
	 *
	 * <p>남은 보호막 + 여유. <b>절대 무한이 되어서는 안 된다</b> — 이 함수가 유한한 수만 돌려주는
	 * 한, 우리가 다음 틱에 죽어도 봉인은 그만큼 뒤에 스스로 열린다.
	 *
	 * <p>여유는 {@link TrialCrystalRevive#GUARD_MARGIN_TICKS} 를 <b>빌려 쓴다.</b> 뜻이 정확히
	 * 같은 값이고(연출이 끊겨도 이만큼 뒤에는 반드시 풀린다), 여기 따로 적어 두면 한쪽만 고쳤을 때
	 * 두 카드의 안전 여유가 조용히 갈린다.
	 */
	static int guardTicks(int remaining) {
		return Math.max(0, remaining) + TrialCrystalRevive.GUARD_MARGIN_TICKS;
	}

	/**
	 * 남은 시간에 맞는 껍질 반경.
	 *
	 * <p>갓 걸렸을 때 가장 크고 풀리기 직전에 가장 작다. 초읽기의 <b>주된</b> 신호는 이제
	 * {@link #shellArc} 지만 이쪽도 <b>단조롭게 줄어야 한다</b> — 둘이 서로 반대로 움직이면 사람이
	 * 어느 쪽을 믿어야 할지 알 수 없다.
	 */
	static double shellRadius(int remaining, int shieldTicks) {
		if (shieldTicks <= 0) {
			return SHELL_MIN;
		}
		double left = Math.max(0.0, Math.min(shieldTicks, remaining)) / shieldTicks;
		return SHELL_MIN + (SHELL_MAX - SHELL_MIN) * left;
	}

	/**
	 * 남은 시간에 맞는 적도 띠의 호 비율. {@code 1.0} 이면 온전한 고리다.
	 *
	 * <p><b>30초를 버티는 초읽기는 이쪽이다.</b> 반경이 쓸 수 있는 폭은 1.1 블록(40 블록 밖에서
	 * 1.6도)뿐인데 이쪽은 360도를 쓴다. 까닭은 클래스 설명에 적어 두었다.
	 *
	 * <p>{@link #ARC_MIN} 에서 멈추는 이유는 {@link #shellRadius} 가 {@link #SHELL_MIN} 에서
	 * 멈추는 것과 같다 — 다 사라지면 「이미 풀렸다」로 읽힌다.
	 */
	static double shellArc(int remaining, int shieldTicks) {
		if (shieldTicks <= 0) {
			return ARC_MIN;
		}
		double left = Math.max(0.0, Math.min(shieldTicks, remaining)) / shieldTicks;
		return ARC_MIN + (1.0 - ARC_MIN) * left;
	}

	/**
	 * 그 띠의 그 호에 찍을 점 수.
	 *
	 * <p><b>호의 실제 길이에 비례</b>한다. 온전한 적도({@code ringRadius == radius},
	 * {@code span == 2π})가 {@link #SHELL_POINTS} 이고 나머지는 거기서 깎인다. 예전처럼 띠마다
	 * 같은 수를 찍으면 둘레가 {@code cos(π/4) ≈ 0.71} 배인 위아래 띠만 촘촘해져 <b>공이 아니라
	 * 위아래가 두꺼운 통</b>으로 보인다.
	 *
	 * <p>{@link #SHELL_MIN_POINTS} 아래로는 내려가지 않는다. 짧아진 호가 점 한둘이 되면 남은
	 * 조각이 보이지 않는데, 그 조각이 「아직 못 깬다」를 말하는 마지막 신호다.
	 */
	static int bandPoints(double ringRadius, double radius, double span) {
		if (!(radius > 0.0) || !(span > 0.0)) {
			return SHELL_MIN_POINTS;
		}
		double share = (ringRadius / radius) * (span / (Math.PI * 2.0));
		return Math.max(SHELL_MIN_POINTS, (int) Math.round(SHELL_POINTS * share));
	}
}
