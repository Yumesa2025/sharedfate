package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import com.sharedfate.team.ShareTeam;
import com.sharedfate.team.TeamManager;
import com.sharedfate.team.TeamState;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundHurtAnimationPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 체력 30% — <b>최후의 저항.</b> 카드 한 장이 아니라 별개의 보스전이다.
 *
 * <h2>이 파일이 무엇이고 무엇이 아닌가 — <b>여덟 파일이다</b></h2>
 *
 * <p>여기 있는 것은 <b>진입 · 붙박이 드래곤 · 바닐라 동작 제거 · 처치 처리 · 패턴을 고르는
 * 규칙</b>이다. 그리는 쪽과 혼자 도는 시계들은 파일이 따로다.
 *
 * <table border="1">
 *   <caption>최후의 저항을 이루는 여덟 파일</caption>
 *   <tr><th>파일</th><th>맡은 것</th></tr>
 *   <tr><td>{@code DragonLastStand}</td><td>진입 · 붙박이 · 처치 · {@link #allowed} 로 <b>고르기</b> ·
 *       <b>시계의 원점</b>({@code Stand.clockBase})</td></tr>
 *   <tr><td>{@link DragonLastStandEntry}</td><td><b>2페이지 진입 연출</b> — 드래곤이 내려오고
 *       보라색 신호기 넷이 켜지고 체력이 차오르는 8초. <b>그동안 시계가 돌지 않는다</b></td></tr>
 *   <tr><td>{@link DragonLastStandShield}</td><td><b>진입 보호막</b> — 진입부터 첫 패턴 직전까지
 *       드래곤으로 오는 피해를 피해 처리 전에 거절하고 반구를 그린다. 2026-10-04 에 사람이
 *       「체력회복하면서 쳐맞는 거 같아」라고 해서 생겼다</td></tr>
 *   <tr><td>{@link DragonLastStandPatterns}</td><td>패턴 <b>넷</b>(날개 · 부채꼴 · 공허 흡입 ·
 *       십자 균열)을 <b>그리기</b> + <b>상시 번개</b>(뽑히지 않고 혼자 도는 시계)</td></tr>
 *   <tr><td>{@link DragonLastStandObjects}</td><td><b>오브젝트 파도</b> — 체력 25%·10% 에 두 번만.
 *       뽑히지 않고 패턴과 <b>함께</b> 돈다</td></tr>
 *   <tr><td>{@link DragonLastStandConePanel}</td><td>부채꼴의 <b>빨간 투명 면</b>(디스플레이 개체)</td></tr>
 *   <tr><td>{@link DragonLastStandLights}</td><td><b>신호기 빛</b>(디스플레이 개체).
 *       진입 연출의 보라 기둥 넷과 오브젝트 파도의 흰 선이 함께 쓴다. ⚠ 전에는 <b>타이머 바</b>도
 *       여기 있었는데 2026-10-04 에 파도가 타이머를 버리면서 함께 지웠다</td></tr>
 *   <tr><td>{@link DragonLastStandZone}</td><td>안전지대(월드 보더) — <b>혼자 도는 시계</b></td></tr>
 *   <tr><td>{@link DragonLastStandDome}</td><td><b>반구 블록 파괴</b> — 뽑히지 않고 1초마다 도는
 *       상시 규칙. 블록을 만지는 것은 <b>이 파일 하나뿐</b>이다</td></tr>
 * </table>
 *
 * <p>「왜 브레스가 안 나오지」는 <b>여기</b>({@link #allowed}), 「왜 빗나가지」는 <b>패턴</b>,
 * 「왜 파란 벽이 남았지」는 <b>지대</b>, 「왜 내 발판이 없어졌지」는 <b>반구</b>, 「왜 아무 일도
 * 안 일어나지」는 <b>진입 연출</b>(8초 동안 시계가 서 있다), 「이 떠 있는 크리스탈은 무엇이지」는
 * <b>오브젝트 파도</b> 쪽이다.
 *
 * <h2>왜 카드가 아니라 제 파일인가</h2>
 *
 * <p>{@code TrialCatalog.POOL_HEALTH_30} 은 <b>비어 있고 앞으로도 비어 있다.</b> 최후의 저항이
 * 카드였다면 {@code TrialRisks} 가 돌려 주겠지만, 이 페이즈가 하는 첫 일이 <b>「걸려 있던 시련을
 * 전부 끈다」</b>라 제 자신도 함께 꺼야 하는 모순이 생긴다. 게다가 룰렛도 화면도 없다
 * ({@code Trigger.HEALTH_30} 이 {@code Reveal.SILENT} 다). 그래서 {@code DragonPassives} 와
 * 같은 자리에 선다 — {@code DragonTrialManager.tickSessions} 가 직접 부르고, 도는 동안
 * <b>시련·패시브·룰렛이 전부 멈춘다.</b>
 *
 * <h2>⚠ 「전부 멈춘다」의 예외 — 「메마른 세계」의 설치 금지 하나</h2>
 *
 * <p>사람이 <b>「최후에서 남아도 될 거 같아」</b>라고 정했다. 그래서 물·용암·서리눈을 엔드에
 * 놓지 못하는 것만은 최후의 저항에서도 이어진다 — {@link #tick} 이 매 틱
 * {@link TrialDryWorld#holdBanDuringLastStand} 를 부른다.
 *
 * <p><b>시련을 되살리는 것이 아니다.</b> 그 카드가 쓰는 기한 한 칸을 대신 밀어 주는 것뿐이고
 * 증발도 양동이 비우기도 다시 돌지 않는다. 그 카드를 뽑지 않은 팀에게는 아무 일도 없다.
 * 전투가 끝나면 {@link #onFightClosed} 의 {@code TrialRisks.clearState()} 가 <b>그 틱에</b>
 * 내리고, 그것을 못 지나도 기한이라 2초 뒤 저절로 풀린다 — <b>다음 판까지 물을 못 놓는 일은
 * 없다.</b>
 *
 * <p>예외를 여기 하나로 못박아 둔다. 둘째가 필요해지면 이 문단부터 고칠 것 — 「전부
 * 멈춘다」가 실제로는 「거의 전부」라는 사실이 코드 어디에도 안 적히는 것이 가장 나쁘다.
 *
 * <h2>⚠ 드래곤을 어떻게 재웠는가 — 「페이즈를 건드리지 마라」를 여기서 처음 깬다</h2>
 *
 * <p>{@code TrialCatalog.Risk.DragonFocus} 와 {@code TrialDragonFocus} 에 <b>페이즈를 만지지
 * 말라</b>가 적혀 있다. 「표적」을 돌진 페이즈로 만들었다가 드래곤이 <b>착지를 아예 안 하게</b>
 * 된 사고 때문이다. 그 규칙을 여기서 의도적으로 깬다 — 목적이 반대이기 때문이다. 그쪽은
 * 드래곤을 <b>움직이려</b>다 바닐라 주기를 끊었고, 여기는 <b>영영 재우는</b> 것이라 끊을 주기가
 * 애초에 남으면 안 된다.
 *
 * <h3>고른 것 — {@code EnderDragonPhase.HOVERING} 한 칸으로 잠근다</h3>
 *
 * <p>26.3 바이트코드를 풀어 읽고 고른 것이다. 아래 넷이 근거이고, <b>다음 사람이 이 판단을 다시
 * 하지 않도록</b> 확인한 사실을 그대로 적어 둔다.
 *
 * <ol>
 *   <li><b>{@code DragonHoverPhase.isSitting()} 이 참이다.</b> 이름은 「호버」지만 26.3 에서
 *       이 칸은 스스로를 앉은 칸이라고 말한다. 그래서 앉은 자세가 그대로 나온다 —
 *       클라이언트는 {@code EnderDragonRenderer} 가 {@code state.isSitting = phase.isSitting()}
 *       한 줄로 자세를 정하고, 서버도 {@code EnderDragon.getHeadYOffset()} 이 앉았으면
 *       {@code -1.0F} 를 돌려줘 <b>머리가 한 칸 내려온다.</b> 「붙어서 때리는 싸움」이 성립하는
 *       이유가 이 한 줄이다</li>
 *   <li><b>그런데 {@code AbstractDragonSittingPhase} 가 <u>아니다</u>.</b> 진짜 앉은 칸 셋
 *       ({@code SITTING_SCANNING}·{@code SITTING_ATTACKING}·{@code SITTING_FLAMING})은
 *       {@code onHurt} 에서 <b>화살과 바람충전을 0 으로 만들고 불을 붙인다.</b> 그 칸을 골랐다면
 *       활이 한 대도 안 들어가 「115초에 1200을 깎는다」가 그 자리에서 거짓이 된다.
 *       {@code HOVERING} 은 {@code AbstractDragonPhaseInstance} 를 바로 물려받아
 *       {@code onHurt} 가 피해를 그대로 돌려준다 — <b>활도 근접도 바닐라 그대로다</b></li>
 *   <li><b>스스로 깨어날 타이머가 없다.</b> {@code DragonHoverPhase.doServerTick} 은
 *       {@code targetLocation} 을 한 번 잡는 것이 전부이고 {@code setPhase} 를 한 번도 부르지
 *       않는다. 반면 {@code SITTING_SCANNING} 은 100틱이면 {@code TAKEOFF}/{@code CHARGING_PLAYER}
 *       로 넘어가고 {@code SITTING_FLAMING} 은 200틱마다 넘어간다 — 그 둘을 매 틱 눌러 두면
 *       {@code begin()} 이 {@code scanningTime}/{@code flameTicks} 를 0 으로 되돌려
 *       <b>허수아비</b>가 된다(문서가 경고한 그 증상이다). {@code HOVERING} 은 눌러도 되돌아갈
 *       상태가 {@code targetLocation} 하나뿐이라 그 함정이 없다</li>
 *   <li><b>{@code EnderDragonPhaseManager.setPhase} 는 같은 칸이면 아무 일도 하지 않는다.</b>
 *       ({@code if (currentPhase == null || target != currentPhase.getPhase())}) 그래서 매 틱
 *       눌러도 실제로 도는 것은 <b>칸이 바뀐 그 틱 한 번</b>뿐이다. 「매 틱 억지로 누른다」의
 *       비용이 비교 한 번이다</li>
 * </ol>
 *
 * <h3>확인한 사실 ① — 우리 패턴이 나올 자리가 남는가</h3>
 *
 * <p><b>남는다. 그것도 {@code HOVERING} 이라야 남는다.</b>
 *
 * <ul>
 *   <li>{@code HOVERING.doServerTick} 은 드래곤을 <b>돌리지 않는다.</b> 반면
 *       {@code DragonSittingScanningPhase.doServerTick} 은 매 틱 가장 가까운 사람 쪽으로
 *       {@code dragon.setYRot} 을 돌린다 — 「부채꼴 브레스는 예고와 함께 머리를 고정한다」가
 *       그 칸에서는 <b>구조적으로 불가능하다</b></li>
 *   <li>다만 {@code EnderDragon.aiStep} 은 <b>날아갈 목표가 있으면</b> 제 손으로 yRot 을 돌린다
 *       ({@code if (abs(xdd) > 1e-5 || abs(zdd) > 1e-5)}). 그래서 {@link #hold} 가 매 틱
 *       <b>좌표를 못박아</b> {@code xdd == zdd == 0} 으로 만든다. 그 한 줄이 없으면 드래곤이
 *       1초에 몇 도씩 저 혼자 돈다 — 재어 보면 {@code getTurnSpeed()} 가 0.7 이라
 *       <b>최대 3.5°/틱</b>이다. 좌표를 못박아 두는 지금은 <b>yRot 이 전적으로 우리 것</b>이라,
 *       브레스를 붙이는 사람은 {@code dragon.setYRot(...)} 한 줄로 머리를 고정하면 된다</li>
 *   <li>바닐라 브레스({@code AreaEffectCloud})는 {@code SITTING_FLAMING} 만 만든다.
 *       {@code HOVERING} 은 장판을 한 장도 남기지 않으므로 우리 부채꼴과 겹칠 것이 없다</li>
 * </ul>
 *
 * <h3>확인한 사실 ② — 맞는 자리가 달라지는가</h3>
 *
 * <p><b>안 달라진다.</b> {@code EnderDragon.hurt(level, part, source, damage)} 의 부위 보정은
 * <b>앉았는지와 무관하다.</b>
 *
 * <pre>if (part != head &amp;&amp; part != neck) damage = damage / 4 + min(damage, 1);</pre>
 *
 * <p>이 줄에 페이즈가 들어오지 않는다. 곧 <b>머리·목은 100%, 나머지는 예나 지금이나 1/4 + 1</b>
 * 이고, 앉혔다고 몸통이 더 깎이지도 덜 깎이지도 않는다. 「115초에 1200」 계산이 앉히는 것 때문에
 * 어긋나는 일은 <b>없다.</b>
 *
 * <p>달라지는 것은 <b>닿기 쉬워지는 것</b> 하나다. 앉으면 머리가 한 칸 내려오고
 * ({@code getHeadYOffset() == -1.0F}) 몸 앞 6.5칸에 고정되므로, 서서 휘두르면 머리에 들어간다.
 * 다이아 검 7 을 기준으로 머리 <b>7</b> · 몸통 <b>2.75</b> 라 차이가 2.5배다 — 초당 10.4 를
 * 맞추려는 사람은 이 숫자부터 볼 것.
 *
 * <p>⚠ 다만 <b>진짜 앉은 칸을 골랐다면 달라졌다.</b> 위 ②에 적은 화살 무효화가 그것이다.
 * {@code HOVERING} 을 고른 것이 그 함정을 피한 것이지, 앉는 것 자체가 안전한 것이 아니다.
 *
 * <h3>확인한 사실 ③ — 스스로 일어나는 길은 몇 개인가</h3>
 *
 * <p>26.3 에서 {@code setPhase} 를 부르는 자리를 전부 세었다. {@code HOVERING} 에서 나갈 수 있는
 * 길은 <b>정확히 하나</b>다.
 *
 * <ol>
 *   <li><b>{@code EnderDragon.hurt(part, ...)} 의 누적 피해.</b> 앉은 상태에서 받은 피해가
 *       {@code 0.25 × 최대 체력} 을 넘으면 {@code sittingDamageReceived} 를 0 으로 되돌리고
 *       {@code TAKEOFF} 로 넘어간다. 4인 기준 최대 2400 이라 <b>600 마다 한 번</b>이고, 이
 *       페이즈에서 깎아야 하는 것이 1200 이므로 <b>반드시 두 번 걸린다.</b> 막는 길이
 *       {@link #hold} 의 {@code setPhase} 한 줄이다 — {@code sittingDamageReceived} 는
 *       {@code private} 이라 손댈 수 없지만, 넘어간 그 틱에 도로 눌러 버리면 {@code TAKEOFF} 가
 *       실제로 하는 일(경로 깔기)은 한 틱치 0.02칸뿐이고 그마저 {@link #hold} 가 좌표를
 *       못박아 지운다</li>
 *   <li>{@code EnderDragon.handleKillingBlow()} 의 {@code DYING} 전환 — <b>앉아 있으면 아예
 *       실행되지 않는다</b>({@code if (!isSitting())}). 이것은 막을 것이 아니라 <b>따라오는
 *       결과</b>다: 최후의 저항에서 잡은 드래곤은 <b>바닐라 죽음 빔 연출이 없다.</b> 바닐라에서
 *       기둥에 앉은 드래곤을 침대로 잡아도 똑같으니 새로 생긴 일은 아니다.
 *       {@code tickDeath()} 는 그대로 돌아 경험치·알·출구 포털이 전부 나온다(확인함)</li>
 *   <li>{@code onCrystalDestroyed} → {@code STRAFE_PLAYER} — {@code DragonHoldingPatternPhase}
 *       만 이 갈래를 갖는다. {@code HOVERING} 은 기본 구현이라 아무 일도 안 하고, 애초에 진입할
 *       때 크리스탈을 전부 없앤다</li>
 *   <li>{@code EnderDragonFight.createNewDragon()} → {@code HOLDING_PATTERN} — <b>새 드래곤</b>을
 *       만들 때만이다. 지금 싸우는 개체에는 걸리지 않는다</li>
 *   <li>{@code EnderDragon.readAdditionalSaveData} → 저장된 칸 — 서버를 껐다 켜면 우리가
 *       눌러 둔 {@code HOVERING} 이 그대로 되살아난다. 잃는 것이 아니라 <b>얻는 것</b>이다</li>
 * </ol>
 *
 * <h2>⚠ 접촉 피해는 믹스인으로 끈다</h2>
 *
 * <p>26.3 {@code EnderDragon.aiStep} 은 매 틱 이 넷을 한 덩어리로 돌린다.
 *
 * <pre>if (level instanceof ServerLevel sl &amp;&amp; !this.wasHurtRecently()) {
 *     knockBack(날개1 상자);   // 밀치기 + (안 앉았을 때만) mobAttack 5
 *     knockBack(날개2 상자);
 *     hurt(머리 상자 +1);      // mobAttack 10  ← 이것이 문제다
 *     hurt(목 상자 +1);        // mobAttack 10
 * }</pre>
 *
 * <p>공유 체력은 팀원별 피해를 그대로 합산하므로({@code StatMirror.fold}) 넷이 머리에 붙으면
 * 한 틱에 <b>40</b> 이고 팀 체력은 20 이다. 붙는 순간 전멸하면 이 페이즈가 성립하지 않는다.
 *
 * <p>끄는 길로 넷을 재어 보고 하나만 남았다.
 *
 * <ul>
 *   <li><b>{@code setNoAi(true)}</b> — {@code aiStep} 의 {@code else} 가지를 <b>통째로</b>
 *       건너뛴다. 블록 부수기({@code checkWalls})와 보스바 갱신({@code dragonFight.updateDragon})
 *       까지 함께 죽는다. 블록 부수기는 <b>남기라고 못박힌 것</b>이라 쓸 수 없다</li>
 *   <li><b>{@code hurtTime} 을 매 틱 세워 {@code wasHurtRecently()} 를 참으로</b> —
 *       위 덩어리가 통째로 꺼지긴 한다. 그런데 {@code hurtTime > 0} 이 곧 피격 붉은 덮개라
 *       <b>드래곤이 115초 내내 빨갛다</b></li>
 *   <li><b>스코어보드 팀으로 {@code doTeamsAllowDamage} 를 거짓으로</b> — 드래곤과 사람을 한
 *       팀에 묶어야 한다. 이름표 색과 다른 기능까지 끌려간다</li>
 *   <li><b>믹스인으로 {@code hurt(ServerLevel, List)} 만 취소</b> ← 고른 것.
 *       {@code EnderDragonContactDamageMixin} 이다. 날개 밀치기는 <b>남는다</b> — 바닐라에서
 *       기둥에 앉은 드래곤이 이미 하는 짓이고, 앉은 동안에는 그 5 피해가 저절로 꺼져 있다
 *       ({@code knockBack} 안의 {@code !isSitting()}). 곧 이 믹스인 하나로 접촉 <b>피해</b>가
 *       0 이 되고 밀치기와 블록 부수기는 그대로다</li>
 * </ul>
 *
 * <h2>보스바 이름은 바닐라 고리를 쓴다 — 믹스인이 아니다</h2>
 *
 * <p>{@code EnderDragonFight.updateDragon} 이 매 틱 이렇게 돈다.
 *
 * <pre>if (dragon.hasCustomName()) this.dragonEvent.setName(dragon.getDisplayName());</pre>
 *
 * <p>그래서 {@code dragon.setCustomName(...)} 한 줄이면 보스바 이름이 바뀐다.
 * {@code dragonEvent} 는 {@code private} 이라 접근자 믹스인을 하나 더 만들 뻔했는데, 바닐라가
 * 이미 길을 열어 두었으므로 만들지 않았다. 되돌리는 것도 같은 길이다 —
 * {@link #VANILLA_BOSS_BAR_NAME}(= {@code EnderDragonFight.EVENT_DISPLAY_NAME} 과 같은
 * {@code Component.translatable("entity.minecraft.ender_dragon")})을 다시 얹으면 글자가 바닐라와
 * 한 글자도 다르지 않다.
 *
 * <p>⚠ <b>{@code setCustomName(null)} 로 되돌리면 안 된다.</b> 이름이 없어지면
 * {@code updateDragon} 의 {@code if} 가 거짓이 되어 {@code dragonEvent} 가 <b>마지막에 받은
 * 이름을 그대로 들고 있는다</b> — 크리스탈로 드래곤을 되살리면 새 드래곤의 보스바에
 * 「최후의 저항」이 남는다. 그래서 {@link #onFightClosed} 는 <b>덮어쓴다.</b>
 *
 * <p>머리 위에 이름표가 뜨지는 않는다. {@code EntityRenderer.shouldShowName} 은
 * {@code hasCustomName() && entity == crosshairPickEntity} 를 요구하는데
 * {@code EnderDragon.isPickable()} 이 거짓이라 드래곤이 조준 대상이 되는 일이 없다(조준에
 * 잡히는 것은 {@code EnderDragonPart} 쪽이다).
 */
public final class DragonLastStand {

	// ------------------------------------------------------------------ 진입

	/** 최후의 저항이 열리는 문턱. {@code DragonTrialManager.detectTriggers} 와 같은 값이어야 한다. */
	static final float ENTRY_HEALTH_RATIO = 0.30F;

	/**
	 * 진입할 때 드래곤이 되찾는 최대 체력 비율. 30% → 50%.
	 *
	 * <p>%p 다. 남은 체력에 곱하는 것이 아니라 <b>최대 체력의 20%</b>를 더한다 — 곱하면 30%의
	 * 20%라 6%p 밖에 안 올라간다.
	 */
	static final float ENTRY_HEAL_FRACTION = 0.20F;

	/**
	 * <b>진입 연출이 끝난 뒤</b> 팀이 더 무적인 시간. 3초.
	 *
	 * <p>이 값이 세 곳을 한꺼번에 정한다 — 무적이 풀리는 틱, <b>첫 패턴을 고르는 틱</b>
	 * ({@code Stand.nextPatternAt}), <b>상시 번개의 첫 볼리</b>
	 * ({@code DragonLastStandPatterns.tickLightning}). 셋이 같은 틱이어야 「무적이 풀리는 순간
	 * 판이 시작된다」가 성립한다.
	 *
	 * <p>⚠ <b>실제로 팀이 무적인 시간은 「진입 연출 길이 + 이 값」이다</b>
	 * ({@code DragonLastStandEntry.LENGTH_TICKS} 160 + 60 = 11초). 예전에는 화면을 띄우지 않는
	 * 자리라 이 3초가 「읽을 시간」이었는데, 지금은 연출 8초가 그 몫을 하고 이 3초는 <b>연출이
	 * 끝나고 첫 패턴이 오기까지의 틈</b>이다.
	 *
	 * <p>⚠ <b>드래곤의 진입 보호막({@link DragonLastStandShield})이 걷히는 틱도 이 합으로 정해진다</b>
	 * — 보호막은 첫 패턴을 고른 틱에 걷히고 그 틱이 {@code nextPatternAt} 이다. 곧 팀의 저항 V 와
	 * 드래곤의 보호막이 <b>같은 틱에 함께 풀린다.</b> 이 값을 늘리면 드래곤이 안 맞는 시간도 함께
	 * 늘어난다.
	 *
	 * <p>방식은 {@code DragonTrialManager.ARRIVAL_GRACE_TICKS} 와 같은 저항 V 다 — 이 저장소가
	 * 「무적」이라고 부르는 것이 그것이고, 두 자리가 다른 수단을 쓰면 「무적인데 아팠다」의
	 * 원인이 두 곳이 된다.
	 */
	static final int ENTRY_GRACE_TICKS = 60;

	/** 저항 등급. 4 = 저항 V = 피해 100% 감쇠. */
	private static final int RESISTANCE_AMPLIFIER = 4;

	/**
	 * 화면을 흔드는 시간과 간격(틱).
	 *
	 * <p>바닐라에 카메라 흔들기 API 가 없어서 <b>피격 기울임</b>을 빌린다.
	 * {@code ClientboundHurtAnimationPacket(개체 id, yaw)} 를 그 사람에게 보내면
	 * {@code Player.animateHurt} 가 {@code hurtTime = 10} 과 {@code hurtDir = yaw} 를 세우고,
	 * 그 둘이 곧 {@code GameRenderer} 의 카메라 기울임이다. <b>피해는 한 점도 들어가지
	 * 않는다</b> — 꾸러미가 나르는 것은 연출뿐이다.
	 *
	 * <p>{@code hurtTime} 이 틱마다 1씩 줄므로 {@link #SHAKE_STEP_TICKS} 마다 방향을 바꿔 다시
	 * 보내면 흔들린다. 4틱은 기울임이 다 가라앉기 전에 반대쪽으로 채는 간격이다.
	 */
	private static final int SHAKE_TICKS = 24;
	private static final int SHAKE_STEP_TICKS = 4;
	/** 흔들 때마다 방향을 이만큼 돌린다. 한 바퀴를 채우지 않는 값이라야 같은 쪽으로만 안 쏠린다. */
	private static final float SHAKE_TURN_DEGREES = 137.0F;

	/** 보스바에 뜨는 이름. */
	private static final Component BOSS_BAR_NAME =
			Component.literal("엔더 드래곤 : 최후의 저항");
	/**
	 * 되돌릴 때 얹는 이름. {@code EnderDragonFight.EVENT_DISPLAY_NAME} 과 <b>같은 것</b>이다.
	 *
	 * <p>{@code private static final Component EVENT_DISPLAY_NAME =
	 * Component.translatable("entity.minecraft.ender_dragon")} 를 그대로 옮겨 적었다. 저쪽이
	 * {@code private} 이라 가리킬 수 없다 — 판이 올라 저 글자가 바뀌면 여기도 함께 볼 것.
	 */
	private static final Component VANILLA_BOSS_BAR_NAME =
			Component.translatable("entity.minecraft.ender_dragon");

	// ------------------------------------------------------------------ 붙박이

	/**
	 * 엔더맨을 쓸어 내는 간격(틱). 1초.
	 *
	 * <p>매 틱 돌지 않는 이유는 {@code TrialNightHost.wake} 에 적혀 있다 — 26.3 에는 상자로
	 * 좁히는 개체 조회가 없어 이 조회가 <b>차원 전체</b>를 훑는다. 엔드는 엔더맨이 끝없이 깔린
	 * 곳이라 매 틱 돌 조회가 아니다. 1초면 「더 안 나온다」가 눈에는 그대로 보인다.
	 */
	private static final int ENDERMAN_SWEEP_TICKS = 20;

	// ------------------------------------------------------------------ 패턴 고르기

	/** 패턴 하나가 끝나고 다음을 고르기까지 쉬는 시간(틱). 2~4초. */
	static final int REST_MIN_TICKS = 40;
	static final int REST_MAX_TICKS = 80;

	/** 부채꼴 브레스를 쓴 뒤 다시 쓸 수 있을 때까지(틱). 8초. */
	static final int BREATH_COOLDOWN_TICKS = 160;

	/** 안전지대가 줄어든 직후 브레스를 금지하는 시간(틱). 3초. */
	static final int BREATH_AFTER_SHRINK_TICKS = 60;

	// ------------------------------------------------------------------ 확정된 피해 값

	/**
	 * 부채꼴 브레스의 <b>적히는</b> 피해. 무장 기준 <b>한 대에 전멸</b>을 노린 값이다.
	 *
	 * <p>⚠ <b>피해원을 아무거나 쓰면 이 말이 거짓이 된다.</b> 문서의 「피해 값을 읽는 법」 식으로
	 * 풀면 이렇다(팀 체력 20).
	 *
	 * <table border="1">
	 *   <caption>65 가 무장한 사람에게 실제로 들어가는 양</caption>
	 *   <tr><th>피해원</th><th>들어가는 값</th><th>한 대에 전멸인가</th></tr>
	 *   <tr><td>{@code lightningBolt()}</td><td>19.66</td><td><b>아니다</b> — 0.34 남는다</td></tr>
	 *   <tr><td>{@code explosion(null, null)}</td><td>29.48</td><td>그렇다</td></tr>
	 *   <tr><td>{@code magic()}</td><td>23.40</td><td>그렇다</td></tr>
	 * </table>
	 *
	 * <p>곧 <b>하드 곱이 걸리거나 방어를 지나는 피해원이라야 한다.</b> 값을 올려 맞추지 말고
	 * 피해원을 고를 것 — {@code DragonLastStandTest} 가 이 셋을 못박아 둔다.
	 */
	static final float CONE_BREATH_DAMAGE = 65.0F;

	/**
	 * 상시 번개의 <b>적히는</b> 피해.
	 *
	 * <p>「낙뢰」와 <b>같은 값</b>이다. 문서가 「낙뢰와 같은 방식」이라고 적어 둔 것을 값으로
	 * 지킨 것이고, 그러려면 피해원도 같아야 한다 — {@code TrialRisks.detonate} 와 같은
	 * {@code damageSources().lightningBolt()} 다. 무장 기준 한 대 <b>6.93</b> · 세 대
	 * <b>20.79</b> 로 「큰 카드는 세 대에 전멸」에 그대로 얹힌다.
	 *
	 * <p>⚠ 열 개가 한꺼번에 떨어져도 <b>한 사람이 받는 것은 한 발</b>이어야 한다. 겹치면 두 발
	 * 13.86 이고 세 발이면 전멸이다. <b>사람이 겹침을 허용했으므로</b> 그것을 지키는 것은
	 * {@code TrialRisks.reserveSpots} 가 아니라 {@code DragonLastStandPatterns.strikeLightning}
	 * 의 {@code break} 한 줄뿐이다 — 겹침 확률과 넓이를 재어 둔 표가 그쪽에 있다.
	 */
	static final float LIGHTNING_DAMAGE = 35.0F;

	/**
	 * 공허 흡입이 터질 때 원 안에 있으면 들어가는 <b>적히는</b> 피해. <b>사람이 정한 값이다.</b>
	 *
	 * <p>사람이 <b>「피해 35(무장 기준 6.9 — 큰 카드 한 대분)」</b>이라고 적었으므로 <b>값이
	 * 피해원을 정했다</b> — {@code GearedDamage} 로 풀면 이렇다.
	 *
	 * <table border="1">
	 *   <caption>35 가 무장한 사람에게 실제로 들어가는 양</caption>
	 *   <tr><th>피해원</th><th>들어가는 값</th><th>사람이 적은 6.9 인가</th></tr>
	 *   <tr><td><b>{@code lightningBolt()}</b></td><td><b>6.93</b></td><td>그렇다 ← 고른 것</td></tr>
	 *   <tr><td>{@code explosion(null, null)}</td><td>10.40</td><td>아니다</td></tr>
	 *   <tr><td>{@code magic()}</td><td>12.60</td><td>아니다 · 방어도를 지나간다</td></tr>
	 * </table>
	 *
	 * <p>{@link #LIGHTNING_DAMAGE} 와 <b>값도 피해원도 같다.</b> 그래서 「큰 카드는 세 대에
	 * 전멸」에 그대로 얹힌다 — 세 대 20.79, 두 대 13.86 이다.
	 *
	 * <p>⚠ 상시 번개와 <b>같은 틱에 겹칠 수 있다.</b> 둘이 한 틱에 들어오면 13.86 이라 아직
	 * 살지만, 거기에 한 대가 더 겹치면 전멸이다 — 흡입은 5초 예고 뒤 <b>한 틱에 한 번</b>뿐이고
	 * 번개도 <b>한 사람은 한 발</b>이므로 한 틱에 둘이 최대다.
	 */
	static final float VOID_SUCTION_DAMAGE = 35.0F;

	/**
	 * 십자 균열이 터질 때 선 안에 있으면 들어가는 <b>적히는</b> 피해. <b>사람이 정한 값이다.</b>
	 *
	 * <p>사람이 <b>「피해 23(무장 기준 6.8)」</b>이라고 적었으므로 여기서도 <b>값이 피해원을
	 * 정했다.</b> {@code explosion(null, null)} 이 <b>6.77</b> 이고
	 * {@code lightningBolt()} 는 4.56 이라 사람이 적은 수와 다르다. 마침 {@code explosion} 이 이
	 * 판의 표준 피해원이다.
	 *
	 * <p>{@code GearedDamage.TARGET_PER_HIT} 이 「6.67 을 정확히 맞추려고 값을 다시 굴리지 말
	 * 것」이라고 적어 두었고 <b>그 문단이 23 을 직접 예로 들고 있다</b> — 「난이도 곱이 없는 쪽은
	 * 35(한 대 6.93), 있는 쪽은 23(한 대 6.77)」.
	 *
	 * <p>회차가 둘이고 한 회차에 한 사람은 한 번만 맞으므로 한 판에 최대 <b>두 대</b> — 무장 기준
	 * {@code 6.77 × 2 = 13.54} 라 <b>이 패턴 혼자서는 전멸하지 않는다.</b> 세 번 긋던 때는 세 번 다
	 * 맞으면 20.31 로 전멸이었다(2026-10-04 에 두 회차로 바뀌었다 —
	 * {@code DragonLastStandPatterns.CROSS_ROUNDS}). 띄움 뒤 떨어지는 낙하 피해는 이 셈 밖이다.
	 */
	static final float CROSS_FISSURE_DAMAGE = 23.0F;

	/**
	 * 안전지대 밖에 서 있을 때 <b>초당</b> 들어가는 <b>적히는</b> 피해.
	 *
	 * <p>사람 수로 곱하지 않는다. 넷이 다 밖이어도 초당 8 이다 — 곱하면 초당 32 라 한 번에
	 * 전멸이다.
	 *
	 * <p>{@code lightningBolt()} 기준으로 무장 감쇠를 지나면 <b>초당 0.81</b> 이다. 축소가 다
	 * 끝난 115초 시점부터 5초마다 얼마씩 올릴지는 안전지대를 붙이는 사람이 정한다.
	 */
	static final float OUTSIDE_ZONE_DAMAGE_PER_SECOND = 8.0F;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 최후의 저항이 도는 팀들.
	 *
	 * <p>정적이라 월드보다 오래 산다. {@link #clearState()} 로 반드시 비운다.
	 */
	private static final Map<UUID, Stand> STANDS = new LinkedHashMap<>();

	/**
	 * 드래곤을 잡아 <b>무적이 된</b> 팀들.
	 *
	 * <p>세션은 드래곤이 죽는 틱에 닫히므로({@code DragonTrialManager.tickSessions}) 그 뒤로는
	 * 여기가 유일한 기록이다. 판이 끝날 때까지, 판이 안 끝나는 설정이면
	 * ({@code SharedFateConfig.dragonKillEndsRun} 이 거짓) <b>계속</b> 유지한다 — 둘 다 끝은
	 * {@link #clearState()} 라 코드가 갈리지 않는다.
	 */
	private static final Set<UUID> VICTORIOUS_TEAMS = new LinkedHashSet<>();

	/**
	 * 접촉 피해를 끄는 깃발. <b>{@code EnderDragonContactDamageMixin} 이 매 틱 읽는다.</b>
	 *
	 * <p>{@code volatile} 인 까닭은 {@code CrystalWatch} 와 같다 — 믹스인은 남의 클래스 안에서
	 * 도는 코드라 어느 스레드가 부를지 이 파일만 보고는 보증할 수 없다.
	 *
	 * <p>팀별이 아니라 <b>판 전체에 하나</b>다. 드래곤은 차원에 하나뿐이라 「어느 팀의 최후의
	 * 저항인가」를 물을 자리가 없다.
	 */
	private static volatile boolean contactDamageOff;

	/** 마지막으로 엔더맨을 쓴 시각. 차원 전체 조회라 {@link #ENDERMAN_SWEEP_TICKS} 마다만 돈다. */
	private static long lastEndermanSweep = Long.MIN_VALUE;

	/** 한 팀의 최후의 저항. */
	private static final class Stand {
		/** 진입한 틱. <b>진입 연출의 원점</b>이다. */
		private final long beganAt;
		/**
		 * ⚠ <b>시계들의 원점.</b> 안전지대·상시 번개·패턴 고르기가 전부 이것을 본다.
		 *
		 * <p>새로 진입한 판에서는 <b>{@code beganAt + DragonLastStandEntry.LENGTH_TICKS}</b> 이고,
		 * 재시작으로 되살린 판에서는 {@code beganAt} 이다. 사람이 <b>「그땐 시간 재지말고」</b>라고
		 * 정한 것을 지키는 배선이 이 한 칸이다 — 연출이 도는 동안 두 시계의 {@code elapsed} 가
		 * 음수라 <b>보더만 서 있고 축소도 밖 피해도 번개도 돌지 않는다.</b>
		 */
		private final long clockBase;
		/**
		 * 진입 연출이 끝나는 시각. <b>되살린 판에서는 {@link Long#MIN_VALUE}</b> 다.
		 *
		 * <p>시각으로 「연출 중인가」를 묻는 자리가 이 한 칸이다. {@link DragonLastStandEntry} 에
		 * 물으면 안 된다 — 되살린 판은 연출을 아예 돌리지 않아야 하는데 그 사실은 시각만으로는
		 * 알 수 없다.
		 */
		private final long cinematicUntil;
		/**
		 * 진입한 틱의 드래곤 체력. <b>연출이 여기서부터 20%p 를 채운다.</b>
		 *
		 * <p>{@link DragonLastStandEntry#rampedHealth} 가 매 틱 이 값으로 목표를 다시 세므로,
		 * 중간에 사람이 때려 체력이 내려가도 <b>차오르는 선이 흔들리지 않는다.</b>
		 */
		private final float entryHealth;
		/**
		 * 드래곤을 못박아 둘 자리.
		 *
		 * <p>{@code DragonHoverPhase} 가 {@code begin()} 뒤 첫 서버 틱에 잡는 목표와
		 * <b>정확히 같아야</b> 한다. 한 칸이라도 어긋나면 {@code aiStep} 이 「목표가 저쪽이다」로
		 * 읽고 드래곤을 돌리기 시작한다(클래스 설명의 「확인한 사실 ①」).
		 */
		private final Vec3 anchor;

		/** 직전에 돌린 패턴. 같은 것이 연속으로 나오지 않게 한다. */
		private @Nullable Pattern previous;
		/** 다음 패턴을 고를 시각. 쉬는 중이면 미래다. */
		private long nextPatternAt;
		/** 부채꼴 브레스를 다시 쓸 수 있는 시각. */
		private long breathReadyAt = Long.MIN_VALUE;
		/** 지금 돌고 있는 패턴. 없으면 쉬는 중이다. */
		private @Nullable Pattern running;
		/** 지금 패턴이 끝나는 시각. */
		private long runningUntil;
		/** 화면 흔들기가 끝나는 시각. */
		private long shakeUntil;
		/**
		 * 진입 연출을 돌린 판인가. <b>진입 보호막이 서는 판</b>이 곧 이것이다.
		 *
		 * <p>되살린 판은 거짓이다 — {@link #resume} 은 아무것도 다시 주지 않는다.
		 */
		private final boolean cinematicEntry;
		/**
		 * <b>첫 패턴을 고른 틱.</b> 아직이면 {@link Long#MIN_VALUE}.
		 *
		 * <p>진입 보호막({@link DragonLastStandShield})이 걷히는 틱이 이것이다. 「첫 패턴이 언제
		 * 시작하는가」를 시각으로 짐작하지 않고 {@link #advance} 가 실제로 고른 그 틱을 적는다 —
		 * 고르는 규칙이 바뀌어도 보호막이 패턴보다 먼저 걷히거나 늦게 걷히는 일이 없다.
		 */
		private long firstPatternAt = Long.MIN_VALUE;

		/**
		 * @param cinematic   진입 연출을 돌릴 판인가. 되살린 판은 거짓이다
		 * @param entryHealth 진입한 틱의 드래곤 체력. 되살린 판에서는 쓰이지 않는다
		 */
		private Stand(long beganAt, Vec3 anchor, boolean cinematic, float entryHealth) {
			this.beganAt = beganAt;
			this.anchor = anchor;
			this.entryHealth = entryHealth;
			this.cinematicEntry = cinematic;
			this.cinematicUntil = cinematic
					? beganAt + DragonLastStandEntry.LENGTH_TICKS
					: Long.MIN_VALUE;
			this.clockBase = cinematic ? this.cinematicUntil : beganAt;
			// 첫 패턴은 시계가 돌기 시작한 뒤 무적 3초가 지난 틱에 고른다 — 연출이 생기기 전과
			// 같은 규칙이고 원점만 밀렸다.
			this.nextPatternAt = this.clockBase + ENTRY_GRACE_TICKS;
			this.shakeUntil = beganAt + SHAKE_TICKS;
		}

		/** 지금 진입 연출이 도는 중인가. */
		private boolean inCinematic(long now) {
			return now < cinematicUntil;
		}
	}

	/** {@link #tick} 이 돌려주는 것. 부르는 쪽이 이것으로 「시련을 돌릴 차례인가」를 가른다. */
	public enum Standing {
		/** 최후의 저항이 아니다. 평소대로 시련과 패시브를 돌린다. */
		OFF,
		/** <b>이번 틱에 진입했다.</b> 부르는 쪽이 저장을 한 번 해야 한다. */
		ENTERED,
		/** 도는 중이다. 시련도 패시브도 룰렛도 멈춘다. */
		RUNNING
	}

	private DragonLastStand() {
	}

	// ------------------------------------------------------------------ ⑤ 패턴이 걸릴 자리

	/**
	 * 최후의 저항이 <b>뽑는</b> 패턴. 지금 <b>넷</b>이다.
	 *
	 * <p>고르는 규칙은 {@link #allowed} 에 있고 <b>그리는 것은 {@link DragonLastStandPatterns}</b>
	 * 에 있다. 파일을 뗀 까닭은 그쪽 클래스 설명에 적어 두었다 — 여기는 이미 붙박이 드래곤과
	 * 진입 연출로 꽉 차 있다.
	 *
	 * <p>피해 값은 {@link #CONE_BREATH_DAMAGE}·{@link #LIGHTNING_DAMAGE} 에 있고, <b>어떤
	 * 피해원을 골랐고 왜인지</b>는 {@code DragonLastStandPatterns.coneBreath} 에 있다.
	 *
	 * <h2>⚠ 번개는 여기 없다 — 뽑히지 않는다</h2>
	 *
	 * <p>전에 {@code LIGHTNING_FIVE} 라는 칸이 있었다. 사람이 플레이해 보고 <b>「번개는 패턴에
	 * 추가하지말고 기본이펙트로 계속 터졋으면좋겟어 드래곤 패턴이 아니라」</b>라고 해서 빼고,
	 * {@link DragonLastStandZone} 처럼 <b>혼자 도는 시계</b>로 옮겼다
	 * ({@code DragonLastStandPatterns.tickLightning}). <b>되돌려 여기에 다시 넣지 말 것</b> —
	 * 그러면 「내내 터진다」가 「가끔 뽑힌다」로 되돌아간다.
	 *
	 * <h2>✅ 넷이 되어 「날개 → 브레스」 번갈이가 풀렸다</h2>
	 *
	 * <p>둘이던 동안은 {@link #allowed} 의 「같은 패턴 연속 금지」 때문에 순서가 <b>완전히 예측
	 * 가능</b>했고, <b>브레스가 잠기면 고를 것이 하나도 없는 구간</b>까지 있었다. 사람이
	 * {@link #VOID_SUCTION} 과 {@link #CROSS_FISSURE} 를 더해 그 둘이 함께 사라졌다.
	 *
	 * <p>⚠ <b>연속 금지를 지우지 말 것.</b> 넷이 되어서야 그것이 제 일을 한다.
	 *
	 * <h2>⚠ 잠금은 브레스 하나에만 걸려 있다</h2>
	 *
	 * <p>새 패턴 둘에는 쿨다운도 축소 잠금도 <b>걸지 않았다.</b> 브레스의 제한 셋은 그 카드가
	 * <b>한 대에 전멸</b>(무장 기준 29.48)이라 치르는 대가이고, 흡입(6.93)과 균열(6.77)은
	 * <b>세 대에 전멸</b>인 보통 카드라 같은 대가를 치를 이유가 없다. 게다가 제한을 하나 더 걸면
	 * {@link #allowed} 의 구멍이 되레 <b>넓어진다</b> — {@code DragonLastStandTest} 가 「제한을
	 * 하나 더 거는 사람은 여기서 멈춘다」라고 적어 둔 그것이다. 축소와 겹쳐도 되는 개별 근거는
	 * 각 칸의 설명과 {@code DragonLastStandPatterns} 에 있다.
	 */
	public enum Pattern {
		/**
		 * 날개 퍼덕이기 — 5초간 <b>0.6초마다 충격파 8회</b>. <b>넉백만, 피해 없음.</b>
		 *
		 * <p>드래곤 바로 아래(4칸 이내)는 약하고 <b>4~12칸이 가장 강하다.</b>
		 *
		 * <p>⚠ 미는 자리에 <b>천장을 걸 것.</b> 엔드 중앙 섬은 사방이 허공이고 공유 체력이라
		 * 한 사람의 낙사가 팀 전체를 끝낸다 — {@code TrialLandingShock}·{@code TrialEnderStorm}
		 * 이 이미 같은 천장을 들고 있으니 그 둘을 볼 것. 문서가 「강한 넉백 금지」를 이 페이즈에서
		 * 조건부로 풀었지만, 푼 것은 <b>「섬 안에 머무는 넉백」</b>까지다.
		 *
		 * <p>✅ 천장 둘이 걸려 있다 — {@code TrialEnderStorm.pushDistance}(목적지가 반경 32칸
		 * 안)와 {@code TrialLandingShock.groundedReach}(길에 땅이 이어진 데까지). 둘 다 그쪽
		 * 함수를 <b>그대로 부른다.</b>
		 *
		 * <p>길이 100틱은 값에서 나온다 — 0.6초마다 여덟 번이면 마지막 충격파가 84틱이고 그
		 * 뒤 12틱이 그 충격파의 몫이라 96틱이다. 100 은 거기에 남은 여유다.
		 */
		WING_BEAT(100),
		/**
		 * 부채꼴 브레스 — <b>4초 예고</b>(2026-10-04 에 5초 → 4초) · 예고 시작과 함께 <b>머리 고정</b> · <b>90도</b> ·
		 * 사거리 <b>20칸</b> · 피해 {@link #CONE_BREATH_DAMAGE} · <b>잔류 없음.</b>
		 *
		 * <p>머리 고정은 {@code dragon.setYRot(...)} 을 예고가 도는 동안 매 틱 같은 값으로
		 * 눌러 두면 된다 — {@link #hold} 가 좌표를 못박아 두어 바닐라가 yRot 을 건드리지 않는다
		 * (클래스 설명의 「확인한 사실 ①」).
		 *
		 * <p>⚠ 이 카드가 이 저장소에서 <b>「즉사 메커닉 0개」를 처음 깨는 자리</b>다. 대가로
		 * 붙은 조건 셋을 지울 수 없다 — 4초 예고 · 머리 고정 · 90도(안전지대 반경 12 안에서도
		 * 옆으로 빠질 곳이 남는다). 예고는 처음 5초였고 사람이 손으로 4초로 고쳤다 — 그 아래로는
		 * 사람에게 다시 묻지 않고 내리지 말 것({@code DragonLastStandPatterns.CONE_WARN_TICKS}).
		 *
		 * <p>✅ 피해원은 {@code explosion(null, null)} 을 골랐다 — 무장 기준 <b>29.48</b> 이라
		 * 한 대에 전멸이다. {@code magic} 을 안 고른 까닭까지
		 * {@code DragonLastStandPatterns.coneBreath} 에 적어 두었다.
		 *
		 * <p>길이는 <b>예고 80 + 불꽃 20 = 100틱</b>이다(5초 예고이던 때 120틱). 뒤의 20틱은 파티클뿐이고 피해는
		 * 81번째 틱에 <b>한 사람당 한 번</b>만 들어간다 — 「잔류 없음」이 사람이 정한 것이다.
		 */
		CONE_BREATH(DragonLastStandPatterns.CONE_WARN_TICKS
				+ DragonLastStandPatterns.CONE_AFTERGLOW_TICKS),
		/**
		 * 공허 흡입 — <b>드래곤 발밑</b>에 검은 원 · 반경 <b>4칸</b> · <b>3초 예고 → 5초 빨아들임
		 * → 터짐</b> · 피해 {@link #VOID_SUCTION_DAMAGE} · <b>잔류 없음</b> · 한 번에 <b>원 하나.</b>
		 *
		 * <p>⚠ <b>원은 드래곤에게 있다.</b> 사람이 <b>「흡입은 드래곤에 검은원이고 드래곤이
		 * 빨아드리는거임」</b>으로 고쳤다 — 아레나 어딘가의 무작위 자리가 아니다. 그래서 이 패턴은
		 * <b>붙어서 때리던 사람을 그 자리에서 쫓아낸다.</b>
		 *
		 * <p>흡입 세기는 <b>달리기보다 약하다</b>({@code SUCK_SPRINT_RATIO} = <b>0.8</b> — 0.85 →
		 * 2026-10-04 낮 0.5525 → 같은 날 저녁 0.8). 곧 <b>달리면 벗어나는 것이 정답</b>이고 — 사람이
		 * 「반대쪽으로 달려서 도망가야지」라고 했다 — 0.8 은 <b>배달 버그 수정 뒤 걷기 문턱
		 * (1 ÷ 1.3 = 0.769) 위</b>라 걸으면 천천히 끌려들고 가만있으면 끌려든다. 낮의 「35% 감소」는
		 * 버그 위에서 정한 값이라 더는 곱하지 않는다. 대신 반경 <b>4칸</b>의 <b>불 결계</b>가 원 안에 머무는 사람에게
		 * 초당 피해를 넣는다({@code DragonLastStandPatterns.SUCK_FIRE_RADIUS}).
		 *
		 * <p>⚠ <b>끌어당기는 것은 넉백의 반대라 천장 논리가 다르다.</b> 섬 밖으로 나갈 위험은
		 * 아예 없고, 대신 <b>세로로 당기면 사람이 들려 공중 감쇠에 들어가 세기가 8.4배가 된다</b> —
		 * 그래서 <b>수평만</b> 당기고 결과 속도에 천장을 씌운다. 근거는
		 * {@code DragonLastStandPatterns.SUCK_MAX_INWARD} 에 길게 적어 두었다.
		 *
		 * <p>⚠ <b>안전지대가 좁아지면 저절로 어려워진다.</b> 3단계 지대가 반변 12칸인데 중앙에서
		 * 도망쳐야 하니 <b>예고 3초만 달려도 벽</b>이다(3초에 17칸). <b>이 긴장은 의도다.</b>
		 *
		 * <p>길이는 <b>예고 60 + 흡입 100 + 터짐 1 + 여운 19 = 180틱</b>이다. 값에서 직접 더한다.
		 */
		VOID_SUCTION(DragonLastStandPatterns.SUCK_WARN_TICKS
				+ DragonLastStandPatterns.SUCK_PULL_TICKS
				+ DragonLastStandPatterns.SUCK_AFTERGLOW_TICKS),
		/**
		 * 십자 균열 — 아레나를 가로지르는 <b>폭 3칸</b> 선 · <b>3초 예고 → 십자 둘이 함께 터짐 →
		 * 1.5초 뒤 다른 각도의 십자 둘 → 총 두 회차</b> · 피해 {@link #CROSS_FISSURE_DAMAGE} ·
		 * <b>잔류 없음</b> · 맞은 사람은 <b>위로 12칸</b> 띄워진다 · <b>섬 밖으로 미는 것은 없다.</b>
		 *
		 * <p>회차마다 각도가 다르다 — <b>{@code 0° + 45°} → {@code 22.5° + 67.5°}</b>. 처음에는 세 번을
		 * 차례로 그었고({@code +} → {@code ×} → 22.5도 어긋난 십자) 2026-10-04 에 사람이 「그 두 십자를
		 * 한번에 같이 발동시켜」라고 해서 둘씩 묶었다. 둘째 회차가 첫 회차의 정확히 사이라 첫 회차를
		 * 피한 자리에 그대로 서 있으면 둘째에 맞는다({@code DragonLastStandPatterns.CROSS_ANGLES}).
		 *
		 * <p>사람이 <b>「단순 피하기」</b>라고 못박았다. 안전한 자리는 선 사이의 사분면이고 그것은
		 * 어느 반경에서나 있다.
		 *
		 * <p>길이는 <b>60 + 30 + 여운 10 = 100틱</b>이다(세 번 긋던 때 130틱). 값에서 직접 더한다
		 * ({@code DragonLastStandPatterns.crossDurationTicks}).
		 */
		CROSS_FISSURE(DragonLastStandPatterns.CROSS_FIRST_WARN_TICKS
				+ (DragonLastStandPatterns.CROSS_ROUNDS - 1)
						* DragonLastStandPatterns.CROSS_GAP_TICKS
				+ DragonLastStandPatterns.CROSS_AFTERGLOW_TICKS);

		/**
		 * 이 패턴이 차지하는 시간(틱).
		 *
		 * <p>두 값 모두 <b>예고와 착탄의 합</b>이고, 어림이 아니라
		 * {@link DragonLastStandPatterns} 의 상수에서 직접 더한 것이다. 짧으면 앞 패턴이 끝나기
		 * 전에 다음 것이 겹쳐 돌고, 길면 드래곤이 아무것도 안 하는 시간이 생긴다.
		 *
		 * <p>⚠ {@code CONE_BREATH} 의 길이는 <b>안전지대 시계도 읽는다</b> —
		 * {@code DragonLastStandZone.shrinkLockoutLead} 가 이 값만큼 미리 브레스를 잠가
		 * 「축소와 브레스 동시 실행 금지」를 실제로 지킨다. 여기를 늘리면 그쪽 잠금도 함께 늘어난다.
		 */
		private final int durationTicks;

		Pattern(int durationTicks) {
			this.durationTicks = durationTicks;
		}

		public int durationTicks() {
			return durationTicks;
		}
	}

	/**
	 * 안전지대(월드 보더)가 지금 어디까지 왔는가.
	 *
	 * <p>안전지대는 패턴이 아니라 <b>혼자 도는 시계</b>라 여기서 뽑히지 않는다. 그런데 브레스를
	 * 고르는 규칙 둘이 그 시계를 본다 — 「축소 직후 3초 브레스 금지」와 「축소와 브레스 동시
	 * 금지」. 그래서 고르는 쪽이 쓸 <b>물음 두 개</b>만 여기에 뚫어 둔다.
	 *
	 * <p>답은 {@link DragonLastStandZone#clock} 이 만든다. {@link #allowed} 는 그대로다.
	 *
	 * @param shrinking         브레스를 지금 시작해서는 안 되는가. ⚠ <b>「지금 줄어드는 중」보다
	 *                          넓다</b> — 브레스 한 판이 들어갈 자리가 남지 않은 구간까지다.
	 *                          까닭은 {@link DragonLastStandZone#shrinkLockoutLead} 에 있다
	 * @param lastShrinkEndedAt 마지막 축소가 끝난 시각. 아직 한 번도 안 줄었으면
	 *                          {@link Long#MIN_VALUE}
	 */
	public record ZoneClock(boolean shrinking, long lastShrinkEndedAt) {
		/** 축소가 없는 답. 되감긴 판과 시험이 쓴다. */
		static final ZoneClock IDLE = new ZoneClock(false, Long.MIN_VALUE);
	}

	/**
	 * 지금 안전지대 시계. 진입부터의 틱만으로 답이 정해지는 순수 계산이다.
	 *
	 * <p>보더를 실제로 미는 것은 {@link DragonLastStandZone#tick} 이고 {@link #tick} 이 매 틱
	 * 부른다. 되돌리는 것은 {@link #onFightClosed} 와 {@link #onServerStopping} 둘이다 —
	 * {@link #clearState()} 에서는 월드를 만질 수 없어 기억만 버린다.
	 */
	private static ZoneClock zoneClock(Stand stand, long now) {
		return DragonLastStandZone.clock(stand.clockBase, now);
	}

	/**
	 * 지금 고를 수 있는 패턴들. <b>제한 넷이 전부 여기 있다.</b>
	 *
	 * <p>월드 없이 도는 순수 계산이라 {@code DragonLastStandTest} 가 직접 굴려 본다. 제한을
	 * 실행 쪽에 흩어 두면 「왜 브레스가 안 나오지」를 세 파일에서 찾게 된다.
	 *
	 * <ol>
	 *   <li>같은 패턴 <b>연속 금지</b></li>
	 *   <li>브레스는 쓴 뒤 <b>8초 쿨다운</b>({@link #BREATH_COOLDOWN_TICKS})</li>
	 *   <li>안전지대가 줄어든 직후 <b>3초간 브레스 금지</b>({@link #BREATH_AFTER_SHRINK_TICKS})</li>
	 *   <li>안전지대 축소와 브레스 <b>동시 실행 금지</b></li>
	 * </ol>
	 *
	 * <h2>✅ <b>이제 빈 목록이 없다</b> — 패턴이 넷이 되어 저절로 닫혔다</h2>
	 *
	 * <p>한동안 <b>빈 목록이 실제로 나오는 구간</b>이 있었다. 번개가 패턴 풀에서 빠져 남은 것이
	 * 날개와 브레스 둘뿐이던 때, <b>직전이 날개이고 브레스가 잠겨 있으면</b> 고를 것이 하나도
	 * 없었다. 브레스가 잠기는 구간이 안전지대 축소 앞뒤 <b>220틱</b>이고
	 * ({@link DragonLastStandZone#shrinkLockoutLead} 120 + 축소 100) 그것이 <b>세 번</b> 오므로
	 * 드래곤이 그 구간의 남은 시간 동안 패턴을 하나도 안 돌렸다.
	 *
	 * <p>사람이 {@link Pattern#VOID_SUCTION} 과 {@link Pattern#CROSS_FISSURE} 를 더해 그 구멍이
	 * <b>닫혔다.</b> 증명은 짧다.
	 *
	 * <blockquote>막히는 것은 <b>브레스와 직전 것</b>뿐이고 패턴이 <b>넷</b>이므로 최악이어도
	 * <b>둘</b>이 남는다. 브레스가 열려 있으면 셋이 남는다.</blockquote>
	 *
	 * <p>⚠ <b>「연속 금지」를 지우지 말 것.</b> 넷이 되어서야 그것이 제 일을 한다 — 둘일 때는
	 * 날개↔브레스로 순서가 완전히 예측 가능했다.
	 *
	 * <p>⚠ <b>제한을 하나 더 거는 사람은 여기서 멈출 것.</b> 위 증명이 「막히는 것이 둘뿐」에
	 * 기대고 있다. 셋째 제한을 걸면 남는 것이 하나로 줄고, 그것이 직전 것과 같으면 구멍이 다시
	 * 열린다 — {@code DragonLastStandTest} 가 <b>모든 조합에서 빈 목록이 나오지 않는지</b>를 센다.
	 */
	static List<Pattern> allowed(@Nullable Pattern previous, long breathReadyAt, ZoneClock zone,
			long now) {
		List<Pattern> open = new ArrayList<>();
		for (Pattern pattern : Pattern.values()) {
			if (pattern == previous) {
				continue;
			}
			if (pattern == Pattern.CONE_BREATH && !breathOpen(breathReadyAt, zone, now)) {
				continue;
			}
			open.add(pattern);
		}
		return open;
	}

	/** 브레스를 지금 써도 되는가. 제한 셋(쿨다운 · 축소 직후 · 축소 중)을 한자리에서 본다. */
	static boolean breathOpen(long breathReadyAt, ZoneClock zone, long now) {
		if (now < breathReadyAt) {
			return false;
		}
		if (zone.shrinking()) {
			return false;
		}
		// 아직 한 번도 안 줄었으면 「직후」라는 것이 없다. MIN_VALUE 를 그냥 빼면 넘친다.
		return zone.lastShrinkEndedAt() == Long.MIN_VALUE
				|| now - zone.lastShrinkEndedAt() >= BREATH_AFTER_SHRINK_TICKS;
	}

	/**
	 * 고를 수 있는 것 중 하나를 굴림으로 고른다.
	 *
	 * @param roll 0 이상 1 미만. 실전에서는 {@code end.getRandom().nextDouble()} 이 준다
	 */
	static @Nullable Pattern pick(List<Pattern> open, double roll) {
		if (open.isEmpty()) {
			return null;
		}
		int index = (int) (Math.max(0.0, Math.min(0.999999, roll)) * open.size());
		return open.get(Math.min(index, open.size() - 1));
	}

	/**
	 * 패턴이 끝나고 쉬는 시간(틱). 2~4초.
	 *
	 * @param roll 0 이상 1 미만
	 */
	static int restTicks(double roll) {
		int span = REST_MAX_TICKS - REST_MIN_TICKS + 1;
		int offset = (int) (Math.max(0.0, Math.min(0.999999, roll)) * span);
		return REST_MIN_TICKS + Math.min(offset, span - 1);
	}

	/**
	 * 고른 패턴을 실제로 그린다. 그리는 것은 {@link DragonLastStandPatterns} 에 있다.
	 *
	 * <p>파일을 뗀 까닭은 그쪽 클래스 설명에 있다. 이 한 줄로 넘기므로 <b>고르는 쪽</b>
	 * ({@link #allowed}·{@link #restTicks}·{@link #advance})과 <b>그리는 쪽</b>이 서로를 모른다.
	 *
	 * <p>그쪽이 지키고 있는 것.
	 *
	 * <ul>
	 *   <li>파티클은 <b>긴 형식</b>({@code sendParticles(type, true, false, …)}). 짧은 형식은
	 *       32칸에서 잘린다 — {@code TrialWarning} 의 「거리 제한을 끄고 보낸다」</li>
	 *   <li>소리는 {@code TrialWarning.playEach} / {@code TrialWarning.soundFor}. 팀원 루프에서
	 *       {@code level.playSound} 를 부르면 <b>사람 수만큼 겹친다</b></li>
	 *   <li>자막을 쓰지 않는다. {@code TrialWarning.shout} 은 아무도 부르지 않는 상태로 둔다</li>
	 *   <li>{@code level.getGameTime()} 대신 <b>받은 {@code now}</b> 를 쓴다</li>
	 *   <li>드래곤은 {@link #hold} 가 좌표를 못박고 있다. 위치를 옮기지 않는다 —
	 *       {@code setYRot} 만이 패턴 쪽에 열려 있는 손잡이다</li>
	 * </ul>
	 *
	 * @param at 이 패턴이 시작한 시각
	 */
	private static void runPattern(ServerLevel end, EnderDragon dragon,
			List<ServerPlayer> members, Pattern pattern, long at, long now) {
		DragonLastStandPatterns.run(end, dragon, members, pattern, at, now);
	}

	/** 쉬고 · 고르고 · 돌린다. */
	private static void advance(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			Stand stand, long now) {
		if (stand.running != null) {
			runPattern(end, dragon, members, stand.running, stand.runningUntil
					- stand.running.durationTicks(), now);
			if (now < stand.runningUntil) {
				return;
			}
			stand.previous = stand.running;
			stand.running = null;
			stand.nextPatternAt = now + restTicks(end.getRandom().nextDouble());
			return;
		}
		if (now < stand.nextPatternAt) {
			return;
		}
		RandomSource random = end.getRandom();
		Pattern chosen = pick(allowed(stand.previous, stand.breathReadyAt, zoneClock(stand, now),
				now), random.nextDouble());
		if (chosen == null) {
			// 올 수 없는 길이다(시험이 센다). 그래도 멈춰 서지는 않는다 — 다음 틱에 다시 묻는다.
			stand.nextPatternAt = now + REST_MIN_TICKS;
			return;
		}
		stand.running = chosen;
		stand.runningUntil = now + chosen.durationTicks();
		if (chosen == Pattern.CONE_BREATH) {
			stand.breathReadyAt = now + BREATH_COOLDOWN_TICKS;
		}
		if (stand.firstPatternAt == Long.MIN_VALUE) {
			// 첫 패턴이다 — 진입 보호막이 이 틱에 걷힌다. 깃발 자체는 tick 끝의 raiseShield 가
			// 이 칸을 읽고 내린다. 깨지는 소리는 보호막이 섰던 판에서만 낸다(되살린 판에는 막이
			// 애초에 없었다).
			stand.firstPatternAt = now;
			if (stand.cinematicEntry) {
				DragonLastStandShield.shatter(end, members, dragon);
			}
		}
		runPattern(end, dragon, members, chosen, now, now);
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱. {@code DragonTrialManager.tickSessions} 가 세션마다 부른다.
	 *
	 * <p>{@link Standing#OFF} 가 아니면 부르는 쪽은 <b>그 팀의 시련·패시브·룰렛을 전부
	 * 건너뛴다.</b> 「시련이 전부 멈춥니다」가 그 한 줄이다.
	 *
	 * @param triggeredNow {@code Trigger.HEALTH_30} 이 <b>이번 틱에 처음</b> 터졌는가.
	 *                     {@code DragonTrialManager.detectTriggers} 가 돌려준 값이다. 참이면
	 *                     진입 연출을 돌리고, 거짓인데 이미 터진 적이 있으면
	 *                     ({@link #resume}) 자세와 보스바만 다시 세운다 — 서버를 껐다 켜면
	 *                     그 길로 들어온다
	 */
	public static Standing tick(@Nullable MinecraftServer server, @Nullable ServerLevel end,
			@Nullable EnderDragon dragon, @Nullable ShareTeam team,
			@Nullable DragonTrialSession session, @Nullable List<ServerPlayer> members,
			boolean triggeredNow, long now) {
		if (server == null || end == null || dragon == null || !dragon.isAlive()
				|| team == null || session == null) {
			return Standing.OFF;
		}
		Stand stand = STANDS.get(team.teamId());
		Standing standing = Standing.RUNNING;
		if (stand == null) {
			if (triggeredNow) {
				stand = enter(server, end, dragon, team, session,
						members == null ? List.of() : members, now);
				standing = Standing.ENTERED;
			} else if (resumable(session, dragon)) {
				stand = resume(end, dragon, now);
			}
			if (stand == null) {
				return Standing.OFF;
			}
			STANDS.put(team.teamId(), stand);
			contactDamageOff = true;
		}

		hold(end, dragon, stand, now);
		// 안전지대. 패턴처럼 뽑히지 않고 진입과 동시에 시작해 저 혼자 돈다 — 보더를 세우고
		// 45초 → 45초 → 25초로 좁히고 밖에 있는 사람에게 초당 값을 넣는 것이 전부 저쪽에 있다.
		// hold 뒤인 것은 중심이 「드래곤을 못박아 둔 자리」라서다.
		//
		// ⚠ 넘기는 것이 beganAt 이 아니라 stand.clockBase 다. 진입 연출이 도는 동안 저쪽의
		// elapsed 가 음수라 「보더는 서지만 축소도 밖 피해도 돌지 않는」 상태가 된다 — 사람이
		// 「보더가 생기면서 … 그땐 시간 재지말고」라고 정한 것이 그 한 줄이다.
		DragonLastStandZone.tick(end, stand.anchor, members == null ? List.of() : members,
				stand.clockBase, now);
		// 상시 번개. 이것도 패턴처럼 뽑히지 않고 진입 3초 뒤부터 6초마다 저 혼자 돈다 —
		// 사람이 「번개는 패턴이 아니라 기본 이펙트로 계속」이라고 정했다. advance 앞인 것은
		// 패턴과 같은 틱에 함께 돌기 때문이고, 그래서 한 틱 점 예산이 「번개 + 패턴」이다.
		// 여기도 원점이 stand.clockBase 라 연출 동안은 첫 볼리 시각이 미래다.
		DragonLastStandPatterns.tickLightning(end, dragon, members == null ? List.of() : members,
				stand.clockBase, now);
		// 반구 블록 파괴. 이것도 뽑히지 않고 1초마다 저 혼자 돈다 — 사람이 「드래곤 주변에 y가
		// 높은 블럭들을 계속 파괴하는게 있으면좋겟고 반구형태로」라고 정했다. 기준 높이가
		// stand.anchor.y(= 중앙 기반암 포디움 맨 위)라 바닥은 한 칸도 읽지 않는다. 이 순서가
		// advance 앞인 것은 파티클을 한 점도 쓰지 않아 예산과 무관하기 때문이고, 패턴이 그린
		// 표식을 블록이 가리는 일도 없다(부수는 것은 표식보다 위다).
		DragonLastStandDome.tick(end, stand.anchor, now);
		// ⚠ 「시련이 전부 멈춘다」의 단 하나뿐인 예외다. 「메마른 세계」의 물·용암·서리눈
		// 설치 금지만은 최후의 저항에서도 이어진다 — 사람이 「최후에서 남아도 될 거 같아」라고
		// 정했다. 시련을 되살리는 것이 아니라 그 카드의 기한 한 칸을 대신 밀어 주는 것이고,
		// 뽑지 않은 팀에게는 아무 일도 하지 않는다. 까닭과 「전투가 끝나면 반드시 풀린다」는
		// TrialDryWorld.holdBanDuringLastStand 에 있다.
		TrialDryWorld.holdBanDuringLastStand(end, session, now);
		sweepEndermen(end, now);
		shake(stand, members, now);
		// ⚠⚠ 진입 연출 중에는 여기서 돌아간다. 아래 둘이 「시간을 재는」 것이라
		// (패턴 고르기와 오브젝트 파도) 사람이 「그땐 시간 재지말고」라고 한 그 둘이다.
		if (stand.inCinematic(now)) {
			DragonLastStandEntry.tick(end, dragon, members == null ? List.of() : members,
					stand.anchor, stand.entryHealth, stand.beganAt, now);
			raiseShield(server, end, dragon, now);
			return standing;
		}
		// 오브젝트 파도. 뽑히지 않고 드래곤 체력 25%·10% 에 두 번만 열린다 — 사람이 정했다.
		// advance 앞인 것은 파도가 패턴을 멈추지 않기 때문이다.
		//
		// ⚠⚠ 「이 파도는 점을 한 개도 쓰지 않는다」가 전에 여기 적혀 있었다. 거짓이다 —
		// 연결선이 들어가면서 파도가 한 틱 37점을 쓴다(DragonLastStandObjects.worstCasePointsPerTick).
		// 흰 선은 디스플레이 개체라 0점이고 박히는 빛·반짝임은 꾸러미 한 장씩이지만, 연결선만은
		// 먼지로 긋는 것이라 여섯 줄을 두 틱에 나눠도 30점이고 반짝임 6 + 하트 1 이 같은 틱에
		// 겹친다. 곧 이 한 줄이 「파도가 패턴과 같은 틱에 돈다」이자 「예산을 둘이 나눠 쓴다」다 —
		// 최악이 패턴 322 + 파도 37 = 359 / 예산 440 이고 여유가 81점이다(2026-10-04 에 십자 예고를
		// 바닥 판으로 바꾸기 전에는 360 + 37 = 397, 여유 43점).
		// DragonLastStandPatternsTest 와 DragonLastStandObjectsTest 가 그 합을 둘 다 못박으므로
		// 한쪽을 올리면 양쪽 시험이 함께 멈춘다. 일부러 만든 목 좁은 자리다.
		DragonLastStandObjects.tick(end, dragon, members == null ? List.of() : members,
				stand.anchor, stand.clockBase, now);
		advance(end, dragon, members == null ? List.of() : members, stand, now);
		// ⚠ advance 뒤다. 첫 패턴을 고른 틱에 그 자리에서 깃발을 내려야 「첫 패턴이 시작한
		// 틱부터는 맞는다」가 성립한다 — 앞에 두면 패턴이 도는 첫 틱 하나를 보호막이 더 먹는다.
		raiseShield(server, end, dragon, now);
		return standing;
	}

	/**
	 * 진입 보호막의 깃발을 이 틱 값으로 다시 세운다. {@link #tick} 이 <b>돌아가는 두 자리 모두</b>
	 * 에서 마지막에 부른다.
	 *
	 * <p>사람이 2026-10-04 에 <b>「최후의 저항 시작하고 첫 패턴 전까지 모든 공격 막는 쉴드 생기고
	 * 피해 안 받게. 지금은 무슨 체력회복하면서 쳐맞는 거 같아」</b>라고 했다. 막는 것 · 그리는 것 ·
	 * 왜 {@code setInvulnerable} 이 아닌가는 전부 {@link DragonLastStandShield} 에 있고, 여기는
	 * <b>세션 상태(도는 판들)에서 「지금 서 있어야 하는가」를 파는 것</b>뿐이다.
	 *
	 * <p>판 전체에 하나다 — 드래곤이 차원에 하나라 「어느 팀의 보호막인가」를 물을 자리가 없다
	 * ({@link #contactDamageOff} 와 같은 판단). 팀이 둘이면 이 메서드가 한 틱에 두 번 불리는데,
	 * 둘째가 첫째 뒤의 상태를 다시 읽으므로 그 틱의 마지막 답이 맞는 답이다.
	 *
	 * <p>⚠ 이 줄을 못 지나는 틱(드래곤이 사라졌거나 팀이 시련을 껐거나)에는 깃발이 다시 안 서고,
	 * 그러면 믹스인이 맥박이 끊긴 것을 보고 <b>두 틱 안에 저절로 끈다</b> — 「안 맞는 드래곤」이
	 * 남는 길을 내리는 줄 하나에 기대지 않는다.
	 */
	private static void raiseShield(MinecraftServer server, ServerLevel end, EnderDragon dragon,
			long now) {
		Stand shielding = null;
		for (Stand candidate : STANDS.values()) {
			if (DragonLastStandShield.shielded(candidate.cinematicEntry, candidate.beganAt,
					candidate.firstPatternAt, now)) {
				shielding = candidate;
				break;
			}
		}
		DragonLastStandShield.tick(server, end, dragon, shielding != null,
				shielding == null ? now : shielding.beganAt, now);
	}

	/**
	 * 서버를 껐다 켰는데 이미 최후의 저항이던 판인가.
	 *
	 * <p>터진 자리는 저장되지만({@code DragonTrialStore}) 이 파일의 상태는 저장되지 않는다.
	 * 되살리지 않으면 드래곤이 다시 날아오르고 접촉 피해가 되돌아온다. 반대로 <b>한 번 준 것을
	 * 다시 주지는 않는다</b> — 팀 체력 회복 · 무적 · 굉음은 {@link #enter} 에만 있고
	 * <b>진입 연출 전체(그 안의 체력 +20%p 를 포함해)</b>는 {@link #resume} 이 만드는
	 * {@code Stand} 에서 아예 꺼져 있다. 재시작할 때마다 드래곤이 20%p 씩 회복하면 판이 끝나지
	 * 않는다.
	 *
	 * <p>⚠ <b>오브젝트 파도의 문턱도 다시 센다.</b> {@code DragonLastStandObjects} 의
	 * {@code firedWaves} 는 저장되지 않으므로, 25% 파도를 이미 겪고 재시작한 팀은 그 문턱을
	 * 한 번 더 받는다. 안전지대와 쉬는 시계가 같은 이유로 그렇게 되어 있고(그쪽 설명의 「짐작해
	 * 복원하는 것보다 한 번 더 주는 편」), 이 문단이 그 사실의 유일한 기록이다.
	 */
	private static boolean resumable(DragonTrialSession session, EnderDragon dragon) {
		return session.trialsEnabled()
				&& session.fired().contains(TrialCatalog.Trigger.HEALTH_30)
				&& dragon.isAlive();
	}

	// ------------------------------------------------------------------ ① 진입

	/**
	 * 진입. <b>여기 적힌 순서가 그대로 문서의 표다.</b>
	 *
	 * <p>⚠ <b>이 메서드가 하는 것은 「한 틱에 끝나는 것」뿐이다.</b> 사람이 정한 진입 연출
	 * (내려오기 · 보더 밖 사람 데려오기 · 보라색 신호기 넷 · 체력이 차오르는 것 · 터지는 소리 ·
	 * 화면에 「최후의 저항」)은 <b>{@link DragonLastStandEntry} 가 8초에 걸쳐</b> 돌린다. 그리고
	 * 그 8초 동안 <b>안전지대·번개·패턴·오브젝트 파도가 전부 서 있다</b> — 사람이 「그땐 시간
	 * 재지말고」라고 정한 것이고, 그것을 지키는 배선은 {@code Stand.clockBase} 한 칸이다.
	 *
	 * <p>룰렛 화면은 띄우지 않는다({@code Reveal.SILENT}). 화면 가운데 글자는 연출이 <b>끝나는
	 * 틱</b>에 한 번 뜨는 「최후의 저항」뿐이고, 그것은 바닐라 타이틀 패킷이라 통신 규약을
	 * 올리지 않는다.
	 */
	private static Stand enter(MinecraftServer server, ServerLevel end, EnderDragon dragon,
			ShareTeam team, DragonTrialSession session, List<ServerPlayer> members, long now) {
		// 1. 걸려 있던 시련이 전부 멈춘다.
		//    TrialRisks.tick 을 안 부르는 것만으로는 모자라다 — 화살 면역·적대 엔더맨·굳은
		//    핫바처럼 판에 걸어 둔 것들은 되돌려 주어야 풀린다. DragonTrialManager.endTrials
		//    가 전투가 닫힐 때 쓰는 바로 그 길이다.
		//
		//    ⚠ 예외가 하나 있다 — 「메마른 세계」의 설치 금지. 여기서 함께 내려가지만 아래
		//    tick 이 매 틱 다시 밀어 이어 간다. 클래스 설명의 「⚠ 「전부 멈춘다」의 예외」를 볼 것.
		TrialRisks.clearState();
		// 아직 고르지 않은 자리가 줄에 남아 있으면 저장 파일이 「선택 대기 중」으로 남는다.
		// 최후의 저항이 열린 뒤에는 그 룰렛이 영영 열리지 않으므로 여기서 비운다.
		while (session.peekTrigger() != null) {
			session.beginChoice();
			session.skipChoice();
		}

		// 2. 살아 있는 크리스탈이 전부 소멸한다. 안 없애면 드래곤이 계속 회복해 제한 시간 안에
		//    못 잡는다(EnderDragon.checkCrystals 가 10틱마다 1씩 채운다).
		int crystals = vanishCrystals(end);

		// 3. 엔더맨이 전부 소멸하고 더 안 나온다. 뒤는 sweepEndermen 이 이어받는다.
		int endermen = vanishEndermen(end);

		// 4. 드래곤이 중앙으로 「내려오기 시작한다」. 다시는 날지 않는다.
		//
		//    ⚠ 여기서 snapTo 로 한 번에 옮기지 않는다. 사람이 「엔더드래곤이 중앙으로 내려오면서」
		//    라고 정했고, 실제로 당겨 내리는 것은 DragonLastStandEntry.descend 다 —
		//    TrialCrystalRevive.hold 로 2초에 걸쳐 온다. 그동안 hold() 도 좌표를 못박지 않는다.
		Vec3 anchor = podium(end, dragon);
		float entryHealth = dragon.getHealth();
		Stand stand = new Stand(now, anchor, true, entryHealth);
		faceTeam(dragon, anchor, members);
		dragon.setDeltaMovement(Vec3.ZERO);
		dragon.getPhaseManager().setPhase(EnderDragonPhase.HOVERING);

		// 5. 드래곤 체력 +20%p — ⚠ 여기서 한 번에 주지 않는다.
		//    사람이 「점점 체력이 차는거 … 그떄 시각적으로 체력을 올리면되겟지?」라고 정했다.
		//    DragonLastStandEntry 가 연출 길이에 걸쳐 나눠 올리고, 그 「매 틱 체력을 쓰는 것」이
		//    동시에 「연출 동안 드래곤이 무적」이다 — 되돌릴 것을 한 칸도 남기지 않는 까닭까지
		//    그쪽 클래스 설명에 적어 두었다. 목표값을 정하는 곳은 healedHealth 하나뿐이다.

		// 6. 보스바 이름. 바닐라 EnderDragonFight.updateDragon 이 다음 틱에 집어 간다.
		dragon.setCustomName(BOSS_BAR_NAME);

		// 7. 팀 체력을 가득 채우고 무적으로 만든다. 4 남은 채로 들어오면 첫 패턴에 끝나
		//    페이즈가 열리지도 않는다.
		//
		//    ⚠ 무적이 「진입 연출 길이 + 3초」다. 예전에는 3초뿐이었는데, 연출이 생기면서
		//    「읽을 시간」이 그 8초로 옮겨 갔다. 연출 동안에도 걸어 두는 까닭은 반구 블록
		//    파괴가 그때도 돌기 때문이다 — 발밑이 사라진 사람의 낙하를 저항 V 가 막는다.
		refillTeam(server, team);
		grantGrace(members, DragonLastStandEntry.LENGTH_TICKS + ENTRY_GRACE_TICKS);

		// 8. 굉음. 화면 흔들림은 Stand.shakeUntil 이 들고 shake 가 매 틱 이어 간다.
		roar(end, members);

		// 9. 반구 블록 파괴가 이 틱부터 돈다. 세우는 일이 없으므로(시계 한 칸뿐이다) 로그만
		//    남긴다 — mobGriefing 이 꺼져 있으면 그 사실이 여기에 찍혀야 한다. 그러지 않으면
		//    「왜 지붕이 안 부서지지」의 답이 어디에도 없다.
		DragonLastStandDome.logEntry(server);

		SharedFateMod.LOGGER.info(
				"[END] 팀 '{}' 최후의 저항 — 크리스탈 {}개·엔더맨 {}마리 소멸 · 드래곤 체력 {} → {} "
						+ "({}틱에 걸쳐) · 자리 ({}, {}, {})",
				team.name(), crystals, endermen, entryHealth,
				healedHealth(entryHealth, dragon.getMaxHealth()), DragonLastStandEntry.LENGTH_TICKS,
				String.format(java.util.Locale.ROOT, "%.1f", anchor.x),
				String.format(java.util.Locale.ROOT, "%.1f", anchor.y),
				String.format(java.util.Locale.ROOT, "%.1f", anchor.z));
		return stand;
	}

	/**
	 * 재시작 뒤 다시 세운다. <b>주는 것은 하나도 없다.</b>
	 *
	 * <p>자세·좌표·접촉 피해·보스바만 되돌려 놓는다. 쉬는 시계는 지금부터 다시 센다 — 몇 초
	 * 쉬고 있었는지는 저장되지 않았고, 그것을 짐작해 복원하는 것보다 한 번 더 쉬는 편이 안전하다.
	 */
	private static Stand resume(ServerLevel end, EnderDragon dragon, long now) {
		Vec3 anchor = podium(end, dragon);
		// ⚠ 연출을 돌리지 않는 판이다(둘째 인자 거짓). 그래서 시계의 원점이 지금이고, 체력
		// +20%p 도 다시 주지 않는다 — 재시작마다 20%p 씩 회복하면 판이 끝나지 않는다.
		Stand stand = new Stand(now, anchor, false, dragon.getHealth());
		// 진입 연출을 다시 돌리지 않는다. 흔들기는 그 연출의 일부다.
		stand.shakeUntil = Long.MIN_VALUE;
		dragon.snapTo(anchor.x, anchor.y, anchor.z, dragon.getYRot(), dragon.getXRot());
		dragon.setDeltaMovement(Vec3.ZERO);
		dragon.getPhaseManager().setPhase(EnderDragonPhase.HOVERING);
		dragon.setCustomName(BOSS_BAR_NAME);
		SharedFateMod.LOGGER.info("[END] 최후의 저항을 다시 세웠습니다 — 재시작 전에 이미 열려 있었습니다");
		return stand;
	}

	/**
	 * 드래곤이 앉을 자리. 바닐라가 착지 목표로 쓰는 것과 <b>같은 계산</b>이다.
	 *
	 * <p>{@code DragonLandingPhase.doServerTick} 이
	 * {@code Vec3.atBottomCenterOf(level.getHeightmapPos(MOTION_BLOCKING_NO_LEAVES,
	 * EnderDragonFight.getPodiumLocation(fightOrigin)))} 을 쓴다. 우리 좌표를 따로 지어내면
	 * 「기둥에 앉은 드래곤」과 다른 자리에 앉아 사람이 배워 둔 거리감이 어긋난다.
	 *
	 * <p>⚠⚠ <b>셈은 2026-10-04 에 {@link TrialPodium#locate} 한 벌로 모았다.</b> 전에는 그 셈이
	 * 세 벌이었다 — 여기, {@code TrialLandingShock.podiumOf}, 그리고 {@link TrialPodium}. 앞의 둘이
	 * {@code private} 이라 밖에서 부를 수 없어 수락창이 <b>세 번째로 같은 식을 적게 된</b> 것이
	 * {@code TrialPodium} 이 태어난 까닭이고, 이제 셋이 그 한 줄을 지난다. 하이트맵 종류를 바꾸는
	 * 날에 고칠 곳이 한 군데다.
	 *
	 * <p>⚠ <b>이 껍데기를 지우고 호출 자리에서 바로 부르지 않은 까닭이 있다.</b>
	 * {@code DragonLastStandDome.baselineY}(지금 이름은 {@code domeOriginY})의 javadoc 이
	 * <b>「{@code DragonLastStand.podium} 이 하이트맵을 쓰는 것이 바닐라 착지 목표와 같은
	 * 계산이다」</b>를 반구 기준 높이의 근거로 들고 있다. 이름이 사라지면 그 문장이 가리킬 곳을
	 * 잃는다 — 한 줄 껍데기로 남겨 두면 문장도 셈도 함께 참이다.
	 *
	 * @param dragon <b>살아 있는 드래곤.</b> {@code null} 을 넘기는 갈래가 이 파일에 없다 —
	 *               {@link #enter} 와 {@link #resume} 둘 다 드래곤을 찾은 뒤에 부른다.
	 *               {@code TrialLandingShock} 쪽은 그 갈래가 있어 거기만 문을 하나 더 들고 있다
	 */
	private static Vec3 podium(ServerLevel end, EnderDragon dragon) {
		return TrialPodium.locate(end, dragon);
	}

	/**
	 * 앉는 순간 팀 쪽을 본다.
	 *
	 * <p>날다가 붙잡힌 방향 그대로 앉으면 「왜 저쪽을 보고 있지」가 된다. 여기서 한 번 돌려
	 * 두면 그 뒤로는 <b>아무도 드래곤을 돌리지 않는다</b> — 패턴을 붙이는 사람이 브레스에서
	 * 처음 돌린다.
	 */
	private static void faceTeam(EnderDragon dragon, Vec3 anchor, List<ServerPlayer> members) {
		double x = 0.0;
		double z = 0.0;
		int counted = 0;
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			x += member.getX();
			z += member.getZ();
			counted++;
		}
		if (counted == 0) {
			return;
		}
		double dx = x / counted - anchor.x;
		double dz = z / counted - anchor.z;
		if (Math.abs(dx) < 1.0E-4 && Math.abs(dz) < 1.0E-4) {
			return;
		}
		// aiStep 이 yRot 을 쓰는 식과 같은 규약이다(sin(yRot), -cos(yRot) 이 앞이다).
		dragon.setYRot((float) (Math.toDegrees(Math.atan2(dx, -dz))));
	}

	/** 살아 있는 크리스탈을 보라 입자와 함께 지운다. @return 지운 개수 */
	private static int vanishCrystals(ServerLevel end) {
		int count = 0;
		for (EndCrystal crystal : end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive)) {
			Vec3 at = crystal.position();
			// 보라 입자. 색 규약(TrialWarning.Colors)을 쓰지 않는 것은 그쪽이 「바닥에 그리는
			// 위험 표식」의 규약이기 때문이다 — 사라지는 크리스탈은 표식이 아니다.
			end.sendParticles(ParticleTypes.PORTAL, true, false,
					at.x, at.y + 0.5, at.z, 60, 0.6, 0.9, 0.6, 0.35);
			end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false,
					at.x, at.y + 0.5, at.z, 30, 0.3, 0.5, 0.3, 0.12);
			// hurtServer 가 아니라 discard 다. 깨뜨리면 EnderDragon.onCrystalDestroyed 가 돌아
			// 드래곤 머리에 폭발 10 이 들어가고 페이즈가 움직인다 — 지금 우리가 막 잠근 그것이다.
			crystal.discard();
			count++;
		}
		return count;
	}

	/** 엔드의 엔더맨을 지운다. @return 지운 마리 수 */
	private static int vanishEndermen(ServerLevel end) {
		int count = 0;
		for (Enderman enderman : end.getEntities(EntityTypes.ENDERMAN, Enderman::isAlive)) {
			Vec3 at = enderman.position();
			end.sendParticles(ParticleTypes.PORTAL, true, false,
					at.x, at.y + 1.0, at.z, 30, 0.4, 0.8, 0.4, 0.3);
			enderman.discard();
			count++;
		}
		return count;
	}

	/**
	 * +20%p 를 값으로만 다시 적은 것. 월드 없이 시험할 수 있게 떼어 두었다.
	 *
	 * <p>더하는 것이 <b>최대 체력의 20%</b>다. 남은 체력에 1.2 를 곱하면 30% 가 36% 가 되어
	 * 「30% → 50%」가 거짓이 된다 — 실제로 틀리기 쉬운 자리라 시험이 이 함수를 직접 부른다.
	 */
	static float healedHealth(float health, float max) {
		return Math.min(max, health + max * ENTRY_HEAL_FRACTION);
	}

	/**
	 * 팀 체력을 가득 채운다.
	 *
	 * <p>{@code TeamState.health} 한 칸만 올린다. 사람마다 {@code setHealth} 를 부르면
	 * {@code StatMirror} 가 그것을 <b>회복 변화량</b>으로 읽어 공유 풀에 한 번 더 더한다 —
	 * 이미 가득이라 넘치지는 않지만, 「누가 채웠는가」가 두 곳이 된다.
	 *
	 * <p>사람에게 실제로 닿는 것은 {@code StatMirror.writeBack} 이다. 그쪽은 같은 틱 안에서
	 * 우리 뒤에 돈다 — {@code SharedFateMod} 가 {@code DragonTrialManager::tick} 을
	 * {@code StatMirror::tick} 보다 <b>먼저</b> 등록해 두었다. 순서가 뒤집히면 회복이 한 틱
	 * 늦어질 뿐 사라지지는 않는다.
	 */
	private static void refillTeam(MinecraftServer server, ShareTeam team) {
		TeamState state = TeamManager.get(server).stateByTeamId(team.teamId());
		if (state == null) {
			return;
		}
		state.health = state.maxHealth;
	}

	/**
	 * 팀을 잠깐 무적으로 만든다.
	 *
	 * <p>저항 V 다. {@code DragonTrialManager.summonTeam} 의 도착 직후 무적과 같은 수단이라,
	 * 「무적인데 아팠다」의 원인을 한 곳에서만 찾으면 된다.
	 */
	private static void grantGrace(@Nullable List<ServerPlayer> members, int ticks) {
		if (members == null) {
			return;
		}
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			member.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ticks,
					RESISTANCE_AMPLIFIER, false, false, true));
		}
	}

	/** 굉음. 사람마다 <b>정확히 한 번</b>이라야 하므로 {@code TrialWarning.playEach} 다. */
	private static void roar(ServerLevel end, @Nullable List<ServerPlayer> members) {
		if (members == null || members.isEmpty()) {
			return;
		}
		TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.5F);
		TrialWarning.playEach(end, members, SoundEvents.WITHER_SPAWN, 1.0F, 0.6F);
	}

	/**
	 * 화면 흔들림.
	 *
	 * <p>바닐라에 카메라 흔들기가 없어 <b>피격 기울임</b>을 빌린다. 자세한 것은
	 * {@link #SHAKE_TICKS} 에 적어 두었다. 꾸러미를 그 사람의 연결로 <b>직접</b> 보내는 것은
	 * {@code TrialWarning.playEach} 와 같은 이유다 — 월드에 놓으면 누가 받을지가 거리에 달린다.
	 */
	private static void shake(Stand stand, @Nullable List<ServerPlayer> members, long now) {
		if (members == null || now > stand.shakeUntil) {
			return;
		}
		long elapsed = now - stand.beganAt;
		if (elapsed < 0L || elapsed % SHAKE_STEP_TICKS != 0L) {
			return;
		}
		float yaw = (elapsed / SHAKE_STEP_TICKS) * SHAKE_TURN_DEGREES % 360.0F;
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			member.connection.send(new ClientboundHurtAnimationPacket(member.getId(), yaw));
		}
	}

	// ------------------------------------------------------------------ ② 붙박이

	/**
	 * 드래곤을 자리와 자세에 못박는다. <b>매 틱 부른다.</b>
	 *
	 * <p>세 줄이 각각 다른 것을 막는다.
	 *
	 * <ol>
	 *   <li><b>{@code setPhase(HOVERING)}</b> — 앉은 상태에서 받은 누적 피해가 최대 체력의 25%
	 *       를 넘으면 바닐라가 {@code TAKEOFF} 로 넘긴다. 유일하게 남은 「스스로 일어나는 길」
	 *       이고, 넘어간 그 틱에 도로 눌러 막는다. 같은 칸이면
	 *       {@code EnderDragonPhaseManager.setPhase} 가 비교 한 번에 끝내므로 비용이 없다</li>
	 *   <li><b>{@code snapTo(anchor)}</b> — 자리를 못박는 것이자 <b>회전을 막는 것</b>이다.
	 *       {@code aiStep} 은 목표와의 차이가 {@code 1e-5} 를 넘으면 yRot 을 저 혼자 돌린다.
	 *       좌표가 목표와 정확히 같으면 그 가지가 아예 실행되지 않아 <b>yRot 이 우리 것</b>이
	 *       된다(클래스 설명의 「확인한 사실 ①」). 바닐라 {@code HOVERING} 은 가만히 있는 칸이
	 *       아니다 — 목표에 닿아 있어도 {@code moveRelative} 로 틱마다 0.02칸쯤 뒤로 민다</li>
	 *   <li><b>{@code setDeltaMovement(ZERO)}</b> — 위 0.02칸이 쌓이지 않게 한다. 쌓이면
	 *       115초에 40칸을 넘어 섬 밖으로 나간다</li>
	 * </ol>
	 *
	 * <p>yRot·xRot 은 <b>건드리지 않는다.</b> 머리를 돌리는 것은 패턴의 몫이다.
	 *
	 * <h2>⚠ 진입 연출 동안은 <b>자리를 못박지 않는다</b></h2>
	 *
	 * <p>사람이 <b>「중앙으로 내려오면서」</b>라고 정했으므로 연출의 첫 2초는 드래곤이 <b>오는
	 * 중</b>이어야 한다. 여기서 매 틱 좌표를 쓰면 그 2초가 <b>순간이동 한 번</b>이 된다.
	 *
	 * <p>그동안 자리를 잡는 것은 {@link DragonLastStandEntry} 다 — 하강이 끝난 뒤에도 매 틱
	 * {@code TrialCrystalRevive.hold} 로 같은 점을 쓰므로 <b>자리를 잡는 일이 끊기는 틱이 없다.</b>
	 * 페이즈를 누르는 것과 속도를 0 으로 만드는 것은 연출 중에도 여기서 한다.
	 */
	private static void hold(ServerLevel end, EnderDragon dragon, Stand stand, long now) {
		EnderDragonPhase<?> phase = dragon.getPhaseManager().getCurrentPhase().getPhase();
		// ⚠ 죽는 칸만은 덮어쓰지 않는다. 좁지만 실제로 있는 길이다 — 한 틱 안에서 A 의 공격이
		// 누적 25% 를 넘겨 TAKEOFF 로 밀어내고(그 순간 isSitting() 이 거짓이 된다) 뒤이어 B 의
		// 공격이 마지막 피를 깎으면, 그때는 handleKillingBlow 가 실제로 돌아 체력을 1 로
		// 되돌리고 DYING 으로 넘긴다. 여기서 도로 HOVERING 을 누르면 그 죽음이 취소되어
		// 드래곤이 체력 1 로 살아남는다.
		if (phase == EnderDragonPhase.DYING) {
			return;
		}
		if (phase != EnderDragonPhase.HOVERING) {
			dragon.getPhaseManager().setPhase(EnderDragonPhase.HOVERING);
		}
		dragon.setDeltaMovement(Vec3.ZERO);
		if (stand.inCinematic(now)) {
			// 연출이 자리를 몬다. 여기서 못박으면 「내려오면서」가 순간이동이 된다.
			return;
		}
		dragon.snapTo(stand.anchor.x, stand.anchor.y, stand.anchor.z,
				dragon.getYRot(), dragon.getXRot());
	}

	/** 새로 스폰되거나 순간이동해 들어온 엔더맨을 치운다. 1초마다 한 번이다. */
	private static void sweepEndermen(ServerLevel end, long now) {
		if (lastEndermanSweep != Long.MIN_VALUE && now - lastEndermanSweep < ENDERMAN_SWEEP_TICKS) {
			return;
		}
		lastEndermanSweep = now;
		for (Enderman enderman : end.getEntities(EntityTypes.ENDERMAN, Enderman::isAlive)) {
			// 진입 때와 달리 입자를 뿌리지 않는다. 1초마다 조용히 치우는 청소라 연출이 아니다.
			enderman.discard();
		}
	}

	// ------------------------------------------------------------------ ④ 처치

	/**
	 * 전투가 닫힌다. {@code DragonTrialManager.tickSessions} 가 드래곤이 사라진 틱에 부른다.
	 *
	 * <p>최후의 저항이 돌고 있지 않았으면 <b>아무 일도 하지 않는다.</b> ④ 는 이 페이즈의 규칙이지
	 * 드래곤전 일반의 규칙이 아니다.
	 *
	 * <ul>
	 *   <li><b>모든 시련이 사라진다</b> — 이미 {@link #enter} 에서 한 번 걷어냈지만 여기서 한 번
	 *       더 한다. 최후의 저항이 도는 동안 무엇이 다시 붙었을 수 있다</li>
	 *   <li><b>팀이 무적이 된다</b> — 판이 끝날 때까지. 판이 안 끝나는 설정이면 계속</li>
	 *   <li><b>보스바 이름을 되돌린다</b> — 드래곤은 죽는 순간부터 200틱 동안
	 *       {@code tickDeath()} 를 도는데 그 안에 {@code dragonFight.updateDragon(this)} 가 있다.
	 *       그러니 이 시점에 이름을 덮어쓰면 보스바가 바닐라 글자로 돌아간다.
	 *       {@code isAlive()} 가 거짓이라 {@code DragonTrialManager.findDragon} 으로는 못 찾으므로
	 *       여기서 따로 훑는다</li>
	 *   <li><b>안전지대를 되돌린다</b> — 월드 보더를 진입 전 값으로 세운다. 되돌리지 않으면 판이
	 *       끝난 뒤에도 파란 벽이 남고, 그 벽은 <b>월드 저장 파일에 들어 있어</b> 서버를 껐다
	 *       켜도 그대로다. 월드가 살아 있는 시점이라 여기서 할 수 있다 —
	 *       {@link #clearState()} 는 {@code SERVER_STOPPED} 에서도 불려 못 한다</li>
	 * </ul>
	 */
	public static void onFightClosed(@Nullable ServerLevel end, @Nullable ShareTeam team) {
		if (team == null || STANDS.remove(team.teamId()) == null) {
			return;
		}
		contactDamageOff = !STANDS.isEmpty();
		VICTORIOUS_TEAMS.add(team.teamId());
		// 진입 보호막. 보호막 도중에 전투가 닫히는 길은 「무적을 지나치는 피해」(공허·
		// generic_kill)와 /kill 뿐이다. 깨지는 소리는 내지 않는다 — 걷힌 것이 아니라 끝난 것이다.
		DragonLastStandShield.clearState();
		TrialRisks.clearState();
		DragonLastStandPatterns.clearState();
		DragonLastStandDome.clearState();
		// 진입 연출의 보라색 신호기와 오브젝트 파도의 흰 선·바·오브젝트를 거둔다. 셋 다
		// 개체라 남으면 다음 판까지 떠 있다 — 그쪽 클래스 설명의 「다섯 자리」를 볼 것.
		DragonLastStandEntry.clearState();
		DragonLastStandObjects.clearState();
		DragonLastStandLights.drop();
		DragonLastStandZone.restore(end);
		restoreBossBarName(end);
		SharedFateMod.LOGGER.info("[END] 팀 '{}' 최후의 저항 종료 — 팀이 무적이 됩니다", team.name());
	}

	/**
	 * 보스바 이름을 바닐라로 되돌린다.
	 *
	 * <p>⚠ {@code setCustomName(null)} 이 아니다. 이름을 지우면
	 * {@code EnderDragonFight.updateDragon} 의 {@code if (dragon.hasCustomName())} 가 거짓이
	 * 되어 보스바가 <b>마지막에 받은 이름을 그대로 들고 있는다.</b> 크리스탈로 드래곤을
	 * 되살리면 {@code dragonEvent} 는 같은 객체라 새 드래곤의 바에 「최후의 저항」이 남는다.
	 */
	private static void restoreBossBarName(@Nullable ServerLevel end) {
		if (end == null) {
			return;
		}
		for (EnderDragon dragon : end.getEntities(EntityTypes.ENDER_DRAGON, candidate -> true)) {
			if (BOSS_BAR_NAME.equals(dragon.getCustomName())) {
				dragon.setCustomName(VANILLA_BOSS_BAR_NAME);
			}
		}
	}

	/**
	 * 드래곤을 잡은 팀의 무적을 이어 간다. <b>{@code DragonTrialManager.tick} 이 매 틱 부른다.</b>
	 *
	 * <p>세션이 아니라 여기서 도는 까닭은 <b>세션이 이미 닫혔기</b> 때문이다. 그리고 엔드에
	 * 있는 사람만 보지 않는다 — 귀환 포털을 지나 오버월드로 나간 뒤에도 무적이어야 한다.
	 *
	 * <p>짧은 상태이상을 매 틱 다시 거는 모양이다. 영구 플래그
	 * ({@code Entity.setInvulnerable})를 쓰지 않는 것은 그것이 <b>사람 파일에 저장</b>되기
	 * 때문이다 — 되돌리는 줄을 한 번 빠뜨리면 다음 판까지 무적인 사람이 생기고, 그 상태는
	 * 어디에도 표시되지 않는다. 지금 방식은 이 메서드가 멈추면 <b>1초 안에 저절로 풀린다.</b>
	 */
	public static void tickVictory(@Nullable MinecraftServer server) {
		if (server == null || VICTORIOUS_TEAMS.isEmpty()) {
			return;
		}
		TeamManager manager = TeamManager.get(server);
		for (UUID teamId : VICTORIOUS_TEAMS) {
			ShareTeam team = manager.teamById(teamId);
			if (team == null) {
				continue;
			}
			for (UUID memberId : team.members()) {
				ServerPlayer member = server.getPlayerList().getPlayer(memberId);
				if (member == null || member.isSpectator()) {
					continue;
				}
				member.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, ENTRY_GRACE_TICKS,
						RESISTANCE_AMPLIFIER, false, false, true));
			}
		}
	}

	// ------------------------------------------------------------------ 되돌리기

	/**
	 * 지금 접촉 피해를 꺼야 하는가. <b>{@code EnderDragonContactDamageMixin} 이 매 틱 읽는다.</b>
	 *
	 * <p>칸 하나를 읽는 것 이상을 해서는 안 된다 — 최후의 저항이 한 번도 안 열린 서버까지 이
	 * 한 줄을 치른다.
	 */
	public static boolean contactDamageOff() {
		return contactDamageOff;
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>⚠ <b>월드를 만지지 않는다.</b> 이 메서드는 {@code SERVER_STOPPED} 에서도 불리는데
	 * 그때는 레벨이 이미 닫혀 있다. 그래서 되돌리는 것을 셋으로 나눠 두었다.
	 *
	 * <ul>
	 *   <li><b>여기서 되돌리는 것</b> — 정적 상태 전부(도는 팀 · 무적 팀 · 접촉 피해 깃발 ·
	 *       엔더맨 청소 시각 · <b>반구 훑기 시계</b>). 무적은 매 틱 다시 걸리는 짧은 상태이상이라
	 *       이 깃발이 꺼지면
	 *       <b>1초 안에 사람에게서도 풀린다</b>. ⚠ 그리고 <b>부채꼴의 빨간 면</b>도 여기서
	 *       사라진다({@code DragonLastStandPatterns.clearState} → {@code ConePanel.drop}) —
	 *       그것은 파티클이 아니라 <b>개체</b>인데, 지울 수 있는 것은 그쪽이 <b>개체를 들고
	 *       있기</b> 때문이다. 월드에서 찾아야 했다면 이 자리에서 막혔다</li>
	 *   <li><b>{@link #onFightClosed} 가 되돌리는 것</b> — 보스바 이름과 <b>월드 보더</b>.
	 *       드래곤이 사라진 틱이라 월드가 살아 있다</li>
	 *   <li><b>{@link #onServerStopping} 이 되돌리는 것</b> — 전투가 끝나지 않은 채로 서버가
	 *       내려갈 때의 <b>월드 보더</b>. 보더는 그 차원의 {@code SavedData} 라
	 *       <b>월드 저장 파일에 남는다</b> — 「월드가 함께 사라진다」가 성립하지 않는 유일한
	 *       되돌림이고, 여기서 할 수 없어 줄이 하나 더 필요했다</li>
	 *   <li>⚠ <b>되돌릴 수 없는 것</b> — <b>반구가 부순 블록.</b> 되살리려면 「우리가 지운 것」을
	 *       기억해야 하는데 그 기억은 서버 재시작 한 번으로 어긋나고, 어긋난 기억은 남의 건축
	 *       위에 블록을 놓는다 — {@code TrialDryWorld} 의 증발과 같은 판단이고 근거는
	 *       {@link DragonLastStandDome#clearState()} 에 있다</li>
	 *   <li><b>되돌릴 필요가 없는 것</b> — 드래곤의 자세와 이름. 서버를 껐다 켜면 저장된
	 *       {@code DragonPhase} 와 {@code CustomName} 이 그대로 되살아나고, 그때는
	 *       {@link #resume} 이 다시 세운다. 월드가 지워지는 길이면 드래곤도 함께 사라진다</li>
	 * </ul>
	 */
	public static void clearState() {
		STANDS.clear();
		VICTORIOUS_TEAMS.clear();
		contactDamageOff = false;
		lastEndermanSweep = Long.MIN_VALUE;
		// 진입 보호막의 깃발. 정적 칸뿐이라 월드를 만지지 않는다.
		DragonLastStandShield.clearState();
		DragonLastStandPatterns.clearState();
		// 반구의 훑기 시계. 월드를 만지지 않는다 — 부순 블록은 되돌리지 않는다(그쪽 설명).
		DragonLastStandDome.clearState();
		// ⚠ 개체를 들고 있는 셋이다. 진입 연출의 보라색 신호기 · 오브젝트 파도의 오브젝트와
		// 흰 선 — 전부 개체라 남으면 다음 판에 뜬다(타이머 바도 여기 있었는데 2026-10-04 에
		// 파도가 타이머를 버리면서 함께 사라졌다). 월드를 만지지 않는 것은
		// 그쪽들이 개체를 들고 있기 때문이고, 그것이 UUID 만 적어 두지 않은 유일한 이유다.
		// ⚠ 파도를 여기서 닫아도 「못 부순 몫」의 회복은 주지 않는다(그쪽 clearState 설명).
		DragonLastStandEntry.clearState();
		DragonLastStandObjects.clearState();
		DragonLastStandLights.drop();
		// 월드를 만지지 않는다 — 기억만 버린다. 보더 자체는 onServerStopping 이 먼저 되돌려 두었다.
		DragonLastStandZone.clearState();
	}

	/**
	 * ⚠ 서버가 멈추기 직전. <b>월드 보더를 저장보다 먼저 되돌린다.</b>
	 *
	 * <p>{@code SharedFateMod} 가 {@code SERVER_STOPPING} 에 붙인다.
	 * {@code TrialFreeze.onServerStopping} 이 같은 자리에 같은 이유로 있다 —
	 * {@code SERVER_STOPPED} 는 레벨이 이미 닫혀 <b>이미 늦다.</b>
	 *
	 * <p>이 줄이 없으면 전투 도중에 서버를 내린 판에서 줄어든 보더가 <b>엔드 저장 파일에
	 * 남는다.</b> 다음 기동에 최후의 저항이 {@link #resume} 으로 되살아나면 그 시계가 다시
	 * 몰고 가지만, 그 사이에 팀 설정에서 드래곤 시련을 끄거나 팀을 해체하면 <b>아무도 되돌리지
	 * 않는 파란 벽</b>이 남는다.
	 *
	 * <p>부채꼴과 십자의 <b>빨간 면</b>도 여기서 거둔다. 그쪽은 개체라 <b>월드가 살아 있는 자리에서
	 * 거두는 것이 가장 깔끔하다</b> — 다만 {@link DragonLastStandConePanel}·{@link DragonLastStandCrossPanel}
	 * 이 저장 자체를 막고
	 * ({@code shouldBeSaved()}) 심지도 들고 있어서, 이 줄을 못 지나도 파일에 남지 않는다.
	 */
	public static void onServerStopping(@Nullable MinecraftServer server) {
		DragonLastStandZone.onServerStopping(server);
		DragonLastStandConePanel.drop();
		DragonLastStandCrossPanel.drop();
		// 진입 연출의 신호기와 오브젝트 파도의 개체들. 저장을 막아 두었으므로 이 줄을 못 지나도
		// 파일에 남지 않지만, 월드가 살아 있는 자리에서 거두는 것이 가장 깔끔하다.
		DragonLastStandEntry.clearState();
		DragonLastStandObjects.clearState();
		DragonLastStandLights.drop();
	}

	// ------------------------------------------------------------------ 시험이 들여다보는 곳

	/** 이 팀의 최후의 저항이 돌고 있는가. */
	static boolean isRunning(@Nullable UUID teamId) {
		return teamId != null && STANDS.containsKey(teamId);
	}

	/** 이 팀이 드래곤을 잡아 무적인가. */
	static boolean isVictorious(@Nullable UUID teamId) {
		return teamId != null && VICTORIOUS_TEAMS.contains(teamId);
	}
}
