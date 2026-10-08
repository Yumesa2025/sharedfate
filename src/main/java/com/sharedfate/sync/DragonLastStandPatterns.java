package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 「최후의 저항」의 <b>패턴 넷</b>과 <b>상시 번개</b>. {@code DragonLastStand.runPattern} 이 여기로 넘긴다.
 *
 * <p>고르는 쪽({@code DragonLastStand.allowed}·{@code pick}·{@code restTicks})은 그쪽에 있고
 * 여기는 <b>그리는 쪽</b>만 있다. 파일을 뗀 것은 {@code DragonFireBarrage} 와 같은 이유다 —
 * 붙박이 드래곤·진입 연출·처치 처리가 이미 1200줄이라, 패턴을 그 안에 얹으면 「왜 드래곤이
 * 안 앉나」와 「왜 브레스가 빗나가나」를 같은 파일에서 찾게 된다.
 *
 * <h2>⚠ 뽑히는 것은 <b>넷</b>이고 번개는 <b>혼자 도는 시계</b>다</h2>
 *
 * <p>번개는 전에 뽑히는 패턴이었다. 사람이 플레이해 보고 <b>「번개는 패턴에 추가하지말고
 * 기본이펙트로 계속 터졋으면좋겟어 드래곤 패턴이 아니라」</b>라고 해서 패턴 풀에서 빼고,
 * {@link DragonLastStandZone}(안전지대)처럼 <b>진입부터 끝까지 저 혼자 도는 시계</b>로 옮겼다
 * ({@link #tickLightning}). 개수도 사람이 <b>5 → 10</b> 으로 올렸다.
 *
 * <table border="1">
 *   <caption>지금 무엇이 무엇인가</caption>
 *   <tr><th></th><th>뽑히는가</th><th>언제 도는가</th></tr>
 *   <tr><td>날개 퍼덕이기</td><td>그렇다</td><td>골랐을 때 100틱</td></tr>
 *   <tr><td>부채꼴 브레스</td><td>그렇다</td><td>골랐을 때 100틱(2026-10-04 에 120 → 100)</td></tr>
 *   <tr><td><b>공허 흡입</b></td><td>그렇다</td><td>골랐을 때 160틱(2026-10-04 에 180 → 160)</td></tr>
 *   <tr><td><b>십자 균열</b></td><td>그렇다</td><td>골랐을 때 100틱(2026-10-04 에 130 → 100)</td></tr>
 *   <tr><td><b>상시 번개</b></td><td><b>아니다</b></td><td>3초 뒤부터 <b>7.8초마다 계속</b></td></tr>
 *   <tr><td><b>반구 블록 파괴</b></td><td><b>아니다</b></td><td>1초마다 계속 —
 *       {@link DragonLastStandDome} 에 있다</td></tr>
 * </table>
 *
 * <p>⚠ 그래서 <b>번개와 패턴이 같은 틱에 함께 돈다.</b> 전에는 「패턴은 한 번에 하나뿐이니 가장
 * 바쁜 틱은 한 패턴의 가장 바쁜 틱」이었는데 그 전제가 깨졌다 — 한 틱 점 예산을 세는 식이
 * <b>번개 + 패턴</b>으로 바뀌었다({@link #worstCasePointsPerTick}).
 *
 * <h2>✅ 패턴이 넷이 되어 순서가 예측 불가능해졌다</h2>
 *
 * <p>둘이던 동안은 {@code DragonLastStand.allowed} 의 「같은 패턴 연속 금지」 때문에
 * <b>날개 → 브레스</b> 로 완전히 번갈아 나왔고, <b>브레스가 잠기면 고를 것이 하나도 없는
 * 구간</b>까지 있었다. 사람이 <b>공허 흡입</b>과 <b>십자 균열</b>을 더해 그 둘이 함께 사라졌다 —
 * 브레스가 잠겨도 남는 것이 셋이라 {@code allowed} 가 <b>빈 목록을 돌려줄 수 없다</b>.
 *
 * <p>⚠ <b>연속 금지를 지우지 말 것.</b> 넷이 되어서야 그것이 제 일을 한다.
 *
 * <p>⚠ <b>새 패턴 둘에는 잠금을 걸지 않았다.</b> 브레스의 제한 셋(쿨다운 8초 · 축소 직후 3초 ·
 * 축소와 동시)은 그 카드가 <b>한 대에 전멸</b>이기 때문에 붙은 대가다. 흡입 35(무장 기준
 * 6.93)와 균열 23(무장 기준 6.77)은 <b>세 대에 전멸</b>인 보통 카드라 같은 대가를 치를 이유가
 * 없고, 제한을 하나 더 걸면 {@code allowed} 의 구멍이 되레 <b>넓어진다</b>
 * ({@code DragonLastStandTest} 의 「제한을 하나 더 거는 사람은 여기서 멈춘다」). 축소와 겹쳐도
 * 되는 까닭은 각 패턴 설명에 따로 적어 두었다.
 *
 * <h2>드래곤에게서 <b>읽기만</b> 한다 — 손잡이는 {@code setYRot} 하나다</h2>
 *
 * <p>{@code DragonLastStand.hold} 가 매 틱 좌표를 못박아 바닐라의 회전 가지를 아예 죽여 두었다.
 * 그래서 yRot 이 전적으로 우리 것이고, 부채꼴 브레스의 「머리 고정」이 {@code dragon.setYRot}
 * <b>한 줄</b>이다. <b>위치를 옮기지 말 것</b>이고 {@code setPhase} 를 부르지 말 것 —
 * {@code DragonLastStand} 클래스 설명에 그 판단의 근거가 길게 붙어 있다.
 *
 * <h2>지키는 것</h2>
 *
 * <ul>
 *   <li><b>파티클은 긴 형식</b>({@code sendParticles(type, true, false, …)}). 짧은 형식은 32칸에서
 *       잘리고 아레나는 80칸이다</li>
 *   <li><b>소리는 {@link TrialWarning#playEach} / {@link TrialWarning#soundFor}.</b> 팀원 루프에서
 *       {@code level.playSound} 를 부르면 사람 수만큼 겹친다 — 최근에 열일곱 자리를 고친 함정이다</li>
 *   <li><b>자막을 쓰지 않는다.</b> 신호는 소리와 바닥 표식 둘뿐이다</li>
 *   <li><b>{@code level.getGameTime()} 을 읽지 않는다.</b> 받은 {@code now} 만 쓴다</li>
 *   <li><b>블록을 한 칸도 건드리지 않는다.</b> 바닥을 지우면 공허 낙사이고 그것이 이 전투가
 *       유일하게 금지한 「대응 불가 즉사」다. 하이트맵을 <b>읽는</b> 것만 한다.
 *       ⚠ <b>블록을 부수는 것은 {@link DragonLastStandDome} 하나뿐</b>이고, 그쪽이 「바닥은 절대
 *       안 부순다」를 두 겹으로 막아 두었다 — 이 파일에 그 코드를 들여오지 말 것</li>
 *   <li><b>피해원에 실체를 달지 않는다.</b> 실체가 붙으면 {@code LivingEntity} 가 스스로 밀어내고,
 *       그 밀기는 우리 천장을 통째로 지나쳐 간다</li>
 * </ul>
 *
 * <h2>⚠ 패턴마다 소리가 다르다 — <b>이름이 아니라 파일</b>로 다르다</h2>
 *
 * <p>사람이 플레이해 보고 <b>「각각 패턴마다 소리가 구분되엇으면해」</b>라고 했다. 그래서 넷이
 * 저마다의 소리를 갖고, <b>예고 소리와 발동 소리를 갈라</b> 「온다」와 「터졌다」가 따로 들린다.
 *
 * <p>⚠⚠ <b>이름이 다른 것으로는 아무것도 보장되지 않는다.</b> 이 저장소가 <b>세 번</b> 걸렸다 —
 * {@code DRAGON_FIREBALL_EXPLODE} 와 {@code END_GATEWAY_SPAWN} 이 둘 다 {@code random/explode1~4}
 * (일반 폭발음)이었고, {@code FIRECHARGE_USE} 가 {@code mob/ghast/fireball4} 로
 * {@code ENDER_DRAGON_SHOOT}·{@code GHAST_SHOOT} 과 같은 파일이었다. 그래서 아래 표의
 * <b>오른쪽 칸을 26.3 바닐라 {@code sounds.json} 에서 직접 열어 확인했고</b>, 여덟이 전부 다른
 * 파일이다. 소리를 바꾸는 사람은 <b>반드시 그 파일을 다시 열 것.</b>
 *
 * <table border="1">
 *   <caption>26.3 {@code assets/minecraft/sounds.json} 에서 확인한 실제 파일</caption>
 *   <tr><th>패턴</th><th>예고</th><th>실제 파일</th><th>발동</th><th>실제 파일</th></tr>
 *   <tr><td>날개 퍼덕이기</td><td>— (예고가 없는 패턴이다)</td><td></td>
 *       <td>{@code ENDER_DRAGON_FLAP}</td><td>{@code mob/enderdragon/wings1~6}</td></tr>
 *   <tr><td>부채꼴 브레스</td><td>{@code GHAST_WARN}</td><td>{@code mob/ghast/charge}</td>
 *       <td>{@code ENDER_DRAGON_SHOOT}</td><td>{@code mob/ghast/fireball4}</td></tr>
 *   <tr><td>공허 흡입</td><td>{@code BREEZE_INHALE}</td><td>{@code mob/breeze/inhale1~2}</td>
 *       <td>{@code WARDEN_SONIC_BOOM}</td><td>{@code mob/warden/sonic_boom1~4}</td></tr>
 *   <tr><td>십자 균열</td><td><b>{@code DEEPSLATE_BREAK}</b></td>
 *       <td><b>{@code block/deepslate/break1~4}</b></td>
 *       <td>{@code WARDEN_DIG}</td><td>{@code mob/warden/dig}</td></tr>
 *   <tr><td>공허 흡입의 <b>불 결계</b>(2026-10-04)</td><td colspan="2">—</td>
 *       <td><b>{@code FIRE_AMBIENT}</b></td><td><b>{@code fire/fire}</b></td></tr>
 * </table>
 *
 * <p>⚠ 2026-10-04 에 <b>여덟째</b>가 생겼다 — 불 결계의 {@code FIRE_AMBIENT}. 26.3
 * {@code sounds.json} 에서 {@code block.fire.ambient} 가 {@code fire/fire} 하나를 가리키고 이 저장소의
 * 어느 카드도 그 파일을 안 쓴다(쓰는 소리 48개를 통째로 훑었다). ⚠ <b>같은 파일을 쓰는 다른 이름이
 * 있다</b> — {@code BLAZE_BURN}({@code entity.blaze.burn})도 {@code fire/fire} 다. 「불 결계와 다른
 * 불 소리」가 필요해지면 그쪽은 고를 수 없다.
 *
 * <p>공용 층 소리({@link TrialWarning#soundFor})는 <b>그대로 둔다.</b> 그것이 이 판의 규약이라
 * 사람이 카드마다 새 신호를 배우지 않는 근거이고, 쓰는 파일도 위의 여덟과 겹치지 않는다 —
 * {@code mob/enderdragon/growl1~4}({@code ENDER_DRAGON_GROWL}) · {@code block/beacon/activate} ·
 * {@code random/orb}({@code EXPERIENCE_ORB_PICKUP})다.
 *
 * <h2>⚠ 입자가 가리키는 방향이 <b>빨아들임</b>과 <b>밀어냄</b>을 가른다</h2>
 *
 * <p>사람 말이 <b>「빨아드리는거랑 밀치는거랑 이펙트가 너무 구분이안됨 … 빨아드리는 패턴이면
 * 입자들이 드래곤에게 빨려들어가는 입자가 잘보이면 이해하잖아」</b>다. 그래서 둘을
 * <b>같은 점으로 반대 방향</b>으로 그린다 — 종류를 달리하면 「다른 패턴이다」만 말하고
 * 「반대되는 패턴이다」는 말하지 못한다.
 *
 * <table border="1">
 *   <caption>같은 {@code CRIT} 가닥이 서로 반대로 흐른다</caption>
 *   <tr><th></th><th>자리가 어디로 움직이는가</th><th>가닥이 어디로 흐르는가</th></tr>
 *   <tr><td>공허 흡입</td><td>{@link #SUCK_REACH}(20) → {@link #SUCK_RADIUS}(4) <b>안으로</b></td>
 *       <td><b>드래곤 쪽</b> ({@link #streamSuck})</td></tr>
 *   <tr><td>날개 퍼덕이기</td><td>0 → {@link #WING_FADE_RADIUS}(20) <b>바깥으로</b></td>
 *       <td><b>드래곤 반대쪽</b> ({@link #wingWave})</td></tr>
 * </table>
 *
 * <p>가닥 하나가 흐르는 거리도 <b>같은 값</b>이다({@link #critDrift} 로 재는 3칸 남짓). 세기가
 * 다르면 「한쪽이 더 세다」로 읽혀 방향 대비가 흐려진다.
 *
 * <p>⚠ <b>가닥은 세로를 한 톨도 쓰지 않는다.</b> 흡입이 사람을 위로 당기지 않는 것과 같은 자리다
 * ({@link #SUCK_MAX_INWARD}) — 입자가 위로 빨려 올라가면 사람이 「들린다」로 읽고, 실제로는
 * 들리지 않으므로 그것이 거짓 신호다.
 *
 * <h2>⚠⚠ 사람에게 <b>세로 속도</b>와 <b>이동 속도</b>를 거는 자리가 생겼다 (2026-10-01)</h2>
 *
 * <p>사람이 플레이해 보고 둘을 더 시켰다. <b>둘 다 이 저장소의 명문 규칙을 사람이 알고 뒤집은
 * 것</b>이라, 규칙이 막으려던 사고를 이 파일이 대신 막는다.
 *
 * <table border="1">
 *   <caption>사람 말과 그 답</caption>
 *   <tr><th>사람 말</th><th>뒤집힌 규칙</th><th>대신 막는 것</th></tr>
 *   <tr><td><b>「30프로 2페이지때 십자가 공격받앗을때도 한 6칸 띄워버려 점프하게」</b> → 2026-10-04
 *       <b>「십자 맞았을 때 하늘로 지금보다 2배는 더 날려버려」</b>(12칸)</td>
 *       <td>「<b>세로로 띄우지 않습니다</b> — 띄우면 마찰이 안 먹어 훨씬 멀리 갑니다」</td>
 *       <td>{@link #liftCross} — 가로를 한 톨도 안 더하고, 낙하 피해를 그 띄움 몫만 면제한다</td></tr>
 *   <tr><td><b>「2페이지 번개에 맞으면 그 플레이어만 구속3 1초 걸리게」</b></td>
 *       <td>상태이상은 {@code EffectSync} 가 <b>팀 전원에게 퍼뜨린다</b></td>
 *       <td>{@link #slowStruck} — 상태이상을 안 걸고 <b>이동 속도를 직접 깎는다</b></td></tr>
 * </table>
 *
 * <p>⚠ <b>세로를 더하는 곳은 {@link #liftCross} 하나다.</b> 날개 퍼덕이기({@link #shove})는 세로를
 * <b>읽어서 내려 보내기만</b> 하고, 공허 흡입({@link #pullSuck})은 2026-10-04 저녁부터 <b>세로를 아예
 * 안 보낸다</b>(수평 한 벌을 더하게 보낸다) — 그 둘은 <b>미는</b> 패턴이라 띄우면 마찰이 빠져 섬 밖으로
 * 나간다({@link #AIRBORNE_PUSH_SCALE}). 십자는 <b>밀지 않으므로</b> 그 사고가 성립하지 않는다.
 *
 * <p>⚠⚠ <b>날개의 세로는 「읽은 그대로」가 아니다 — {@link TrialVelocity#syncedVertical} 을 지난다.</b> 서버가 들고 있는
 * {@code deltaMovement} 는 <b>우리 것이 아니고</b>, 최후의 저항에서는 바닐라 드래곤이 매 틱
 * 거기에 세로 {@code +0.2} 를 쌓아 둔다. 그 쌓인 값을 그대로 내려 보내면 사람이 하늘로 날아간다 —
 * 2026-10-04 에 사람이 본 것이 그것이다.
 *
 * <p>⚠ <b>그런데 띄운 사람은 「공중에 있는 사람」이 된다.</b> 곧 <b>내가 만든 상태가 남이 만든
 * 넉백의 입력</b>이 된다 — 띄워 놓고 1.75초(12칸 띄움, 2026-10-04 전에는 6칸 · 1.25초) 안에 날개
 * 퍼덕이기가 오면 그 사람은 공중에서 맞는다.
 * {@link #AIRBORNE_PUSH_SCALE} 가 공중 세기를 바닥과 같게 맞춰 두었고 천장 둘이 그대로 걸려 있어
 * 그때도 섬 안이다 — {@code DragonLastStandPatternsTest.띄워진_직후에_밀려도_섬_밖으로_못_나간다}
 * 가 그 사실을 붙든다.
 *
 * <h2>한 틱 점 예산</h2>
 *
 * <p>{@code TrialLandingShock.MAX_POINTS_PER_TICK} 이 440 이고 그것이 이 판의 예산이다. 패턴 넷은
 * <b>동시에 돌지 않지만</b>({@code DragonLastStand.advance} 가 한 번에 하나만 돌린다)
 * <b>번개는 언제나 함께 돈다.</b> 그래서 가장 바쁜 틱은 <b>번개 + 가장 바쁜 패턴</b>이다 —
 * {@link #worstCasePointsPerTick} 이 값에서 직접 세고 {@code DragonLastStandPatternsTest} 가 그
 * 수를 예산과 견준다.
 *
 * <p>번개가 <b>고리 한 바퀴를 여섯 틱에 나눠</b> 그리는 것이 그래서다. 열 곳을 매 틱 다 그리면
 * 400점이라 그것만으로 예산이 차고, 부채꼴 예고(236)와 겹치는 순간 636점이 된다 —
 * {@link #LIGHTNING_MARK_STRIDE} 에 그 셈이 있다.
 *
 * <p>⚠ <b>가장 바쁜 틱이 바뀌었다.</b> 사람이 <b>「각각 패턴을 쓴다느 느낌이 들게 이펙트를 키우든
 * 좀더 가시성이 좋앗으면좋겟어」</b>·<b>「특히 십자가 공격이 너무 잘 안보엿어」</b>라고 해서 넷을
 * 함께 키웠고, 그러면서 최악이 <b>272 → 356</b> 으로 올랐다. 전에는 「번개 + 부채꼴 예고」였는데
 * 이제는 <b>「번개 + 십자가 터지는 틱」</b>이다 — 그 틱에 갈라짐 연출과 솟는 기둥과 <b>다음 십자의
 * 예고</b>와 <b>띄워진 사람 발밑의 기둥</b>이 함께 나간다({@link #crossPoints}). 그 뒤 십자가
 * 사람을 띄우면서 <b>356 → 360</b> 이 됐다(사람당 한 점 × 넷).
 *
 * <p>⚠⚠ <b>2026-10-04 에 360 → 322 로 내려왔다.</b> 사람이 십자 예고를 <b>「브레스처럼 투명땅으로」</b>
 * 바꾸라고 해서 먼지 선(54점)과 흰 기둥 벽(108점)을 걷고 디스플레이 판으로 깔았다
 * ({@link DragonLastStandCrossPanel} — 판은 점을 안 쓴다). 대신 한 회차에 십자가 <b>둘</b> 터지게 되어
 * 터지는 틱의 갈라짐 연출이 두 벌(124 × 2)이다. 그래서 가장 바쁜 틱의 주인은 여전히 <b>「번개 +
 * 십자가 터지는 틱」</b>이고 그 틱이 {@code 70 + 248 + 4 = 322} 다. 공허 흡입에 불 결계(17점)가
 * 더해졌지만 163점이라 그 아래다. 수를 쓰려는 사람은
 * {@code DragonLastStandPatternsTest.한_틱_점_예산을_넘지_않는다} 가 못박아 둔 수부터 고쳐야 한다.
 *
 * <p>⚠ <b>내리치는 틱도 이제 센다.</b> 번개가 맞은 사람 발밑에 점을 뿌리게 되어
 * ({@link #slowStruck}) 「내리치는 틱은 0점」이 거짓이 됐다 — {@link #lightningPoints} 가 예고 틱
 * (70)과 내리침 틱(48) 가운데 큰 쪽을 센다.
 *
 * <p>그리고 부채꼴과 십자의 <b>빨간 투명 면은 점을 한 개도 쓰지 않는다.</b> 파티클이 아니라
 * 디스플레이 개체라 예산과 무관하다 — {@link DragonLastStandConePanel}·{@link DragonLastStandCrossPanel}
 * 을 볼 것. ⚠ 대신 <b>개체 수와 패킷</b>이라는 다른 자가 생긴다(부채꼴 18장 · 십자 한 회차 평지 16장,
 * 최악 304장 — {@link DragonLastStandCrossPanel} 클래스 설명).
 */
public final class DragonLastStandPatterns {

	// ------------------------------------------------------------------ ⓪ 사람의 움직임 (패턴 둘이 함께 쓴다)

	/**
	 * 사람의 이동속도 특성 기본값. 26.3 {@code Player.createAttributes} 에서 읽었다.
	 *
	 * <p>바닥에서는 이 값이 <b>매 틱 속도에 더해지는 입력 가속</b>과 같다. 26.3
	 * {@code LivingEntity.getFrictionInfluencedSpeed(f)} 가 {@code f > 0.6} 일 때만 보정하고 기본
	 * 블록 마찰이 <b>정확히 0.6</b> 이라 보정이 걸리지 않고 {@code getSpeed()} 가 그대로 나온다
	 * (바이트코드로 확인했다).
	 *
	 * <p>⚠ <b>이 셋이 공용 자리에 있는 까닭.</b> 처음에는 공허 흡입만 쓰는 값이라 그 절에 있었는데,
	 * 사람이 <b>「밀치는거 점프하는도중 밀쳐지면 저끝까지 날라가버리거든?」</b>이라고 해서 날개
	 * 퍼덕이기도 {@link #GROUND_DRAG} 를 쓰게 됐다({@link #AIRBORNE_PUSH_SCALE}). 두 패턴이 같은
	 * 숫자를 쓰는데 한쪽 절에 있으면 <b>다른 쪽을 고치는 사람이 그 절을 읽지 않는다.</b>
	 */
	static final double WALK_INPUT = 0.1;
	/**
	 * 달릴 때 붙는 곱. 26.3 {@code LivingEntity.SPEED_MODIFIER_SPRINTING} 이
	 * {@code 0.3 · ADD_MULTIPLIED_TOTAL} 이라 <b>1.3배</b>다(바이트코드로 확인했다).
	 */
	static final double SPRINT_MULTIPLIER = 1.3;
	/**
	 * 사람의 입력이 가속이 되기 전에 한 번 깎이는 몫. 26.3 {@code LocalPlayer.modifyInput} 이 움직임
	 * 벡터에 {@code 0.98} 을 곱한다(바이트코드로 확인했다 — {@code LivingEntity.applyInput} 의 0.98 은
	 * 사람에게는 안 걸린다. {@code LocalPlayer.applyInput} 이 자기 카메라일 때 그것을 부르지 않는다).
	 *
	 * <p>2026-10-04 저녁에 더했다. 전에는 이것을 빼고 셌으므로 걷기 종착이 0.2203 · 달리기가 0.2863
	 * 이었는데, 실제는 <b>0.2159 · 0.2806칸/틱</b>(초당 4.32 · 5.61칸 — 위키에 적힌 바닐라 걷기·달리기
	 * 속도와 같다)이다. 2% 차이라 결론을 바꾸지는 않았지만, 공허 흡입의 셈이 틀렸던 날 함께 찾은
	 * 것이라 같이 고쳤다 — {@link #SUCK_SPRINT_RATIO} 를 볼 것.
	 */
	static final double INPUT_DAMPING = 0.98;
	/**
	 * 바닥 감쇠. <b>블록 마찰 0.6 × 0.91</b> 이다.
	 *
	 * <p>26.3 {@code LivingEntity.travelInAir} 가 매 틱 끝에 수평 속도에 {@code f × 0.91} 을
	 * 곱하고 {@code f} 가 바닥 블록의 마찰(기본 0.6)이다. 공중에서는 {@code f = 1} 이라
	 * {@code TrialEnderStorm.AIR_DRAG}(0.91)가 되고 <b>그 차이가 이 파일의 함정 둘</b>이다 —
	 * 당기는 쪽은 {@link #SUCK_MAX_INWARD}, 미는 쪽은 {@link #AIRBORNE_PUSH_SCALE} 를 볼 것.
	 */
	static final double GROUND_DRAG = 0.546;

	/**
	 * ⚠⚠ <b>공중에 떠 있는 사람을 밀 때 세기에 곱하는 값. 이 한 줄이 「점프하면 저 끝까지」를
	 * 막는다.</b>
	 *
	 * <h2>사람이 본 것</h2>
	 *
	 * <p><b>「밀치는거 점프하는도중 밀쳐지면 저끝까지 날라가버리거든? 그것도 조심해야겟어」</b>
	 *
	 * <h2>왜 그렇게 되는가 — 다섯 배다</h2>
	 *
	 * <p>{@code TrialEnderStorm.pushVelocity} 는 <b>공중 감쇠로</b> 속도를 잡는다. 곧 적힌 거리
	 * {@code d} 를 밀려면 {@code d × 0.09} 를 싣는데, 그 속도로
	 *
	 * <ul>
	 *   <li><b>바닥에 붙어 있으면</b> 감쇠가 {@value #GROUND_DRAG} 라 총 이동이
	 *       {@code d × 0.09 ÷ 0.454 = d × 0.198} — <b>적힌 거리의 5분의 1</b>이다. 그 사실이
	 *       {@link #WING_PUSH_BLOCKS} 에 「적힌 값은 천장이다」로 적혀 있었다</li>
	 *   <li><b>떠 있으면</b> 감쇠가 0.91 이라 총 이동이 {@code d × 0.09 ÷ 0.09 = d} — <b>적힌 거리
	 *       그대로</b>다</li>
	 * </ul>
	 *
	 * <p>곧 <b>점프한 순간 같은 한 번치가 다섯 배를 민다.</b> 5초에 여덟 번이라 그 다섯 배가 여덟
	 * 번 쌓이고, 사람이 본 「저 끝까지」가 정확히 그것이다.
	 *
	 * <h2>고친 방법 — <b>공중이면 바닥과 같은 거리만 가게 깎는다</b></h2>
	 *
	 * <p>값이 {@code (1 − 공중 감쇠) ÷ (1 − 바닥 감쇠) = 0.09 ÷ 0.454 = 0.198} 이다. 이 비율을
	 * 곱하면 공중의 총 이동이 <b>바닥의 총 이동과 정확히 같아진다</b>
	 * ({@link #shoveSpeed} · {@code DragonLastStandPatternsTest} 가 그 등식을 붙든다).
	 *
	 * <ul>
	 *   <li><b>점프해도 이득도 손해도 없다.</b> 흡입이 {@link #SUCK_MAX_INWARD} 로 「바닥·공중·얼음이
	 *       같은 세기」를 만든 것과 <b>같은 수법이고 같은 어휘</b>다 — 이 파일에 두 벌의 다른
	 *       대처가 있으면 다음 사람이 한쪽만 고친다</li>
	 *   <li><b>「점프하면 안 밀린다」로 만들지 않았다.</b> 공중을 통째로 면제하면 사람이 배우는 답이
	 *       <b>「퍼덕일 때는 뛰어 있어라」</b>가 되어 이 패턴이 아무것도 요구하지 않는다</li>
	 *   <li>⚠ <b>세로는 그대로 한 톨도 건드리지 않는다.</b> 띄우면 마찰이 안 먹어 더 멀리 가고
	 *       낙하 피해까지 붙는다 — 그 규칙이 이 전투 전체의 것이다</li>
	 * </ul>
	 *
	 * <p>⚠ <b>이것은 세기 문제의 답이고 낙사의 답이 아니다.</b> 낙사를 막는 것은 여전히 천장
	 * 둘({@code TrialEnderStorm.pushDistance} · {@code TrialLandingShock.groundedReach})이다. 둘 중
	 * 하나라도 빼고 이 비율만 남기면 <b>그날로 낙사 장치</b>다 — {@link #wingBeat} 를 볼 것.
	 */
	static final double AIRBORNE_PUSH_SCALE =
			(1.0 - TrialEnderStorm.AIR_DRAG) / (1.0 - GROUND_DRAG);

	/**
	 * 발이 그 자리 지표보다 이만큼 높으면 <b>떠 있는 것으로 본다</b>(칸).
	 *
	 * <p>0.5 인 것은 <b>계단·반 블록 한 칸</b>이다. 그보다 작게 두면 반 블록을 올라서는 중인
	 * 사람이 떠 있는 것으로 읽혀 세기가 들쭉날쭉해지고, 크게 두면 짧은 점프가 공중으로 안 잡힌다.
	 *
	 * <p>⚠ <b>{@code onGround()} 만 믿지 않는 까닭</b>은 그것이 <b>클라이언트가 보내 준 깃발</b>
	 * 이라는 것이다. 렉이나 거짓 보고로 「땅에 있다」가 들어오면 떠 있는 사람이 바닥 세기를
	 * 받는다 — 그 어긋남이 <b>더 미는 쪽</b>이라 가장 나쁘다. 그래서 서버만 아는 하이트맵으로 한 번
	 * 더 본다({@link #isAirborne}).
	 */
	static final double AIRBORNE_LIFT = 0.5;

	// ------------------------------------------------------------------ ① 날개 퍼덕이기

	/** 충격파 횟수. <b>사람이 정한 값이다.</b> */
	static final int WING_PULSES = 8;
	/** 충격파 간격(틱). 0.6초. <b>사람이 정한 값이다.</b> */
	static final int WING_PULSE_TICKS = 12;

	/**
	 * 약한 구간의 끝(칸). 드래곤 바로 아래다. <b>사람이 정한 값이다.</b>
	 *
	 * <p>여기 안쪽이 약한 것이 이 패턴의 뜻이다 — 머리에 붙어 때리는 사람을 떼어 내는 것이
	 * 목적이 아니라 <b>붙기를 방해하되 쫓아내지는 않는</b> 것이다.
	 */
	static final double WING_NEAR_RADIUS = 4.0;
	/** 가장 강한 구간의 끝(칸). <b>사람이 정한 값이다.</b> 4~12칸이 가장 강하다. */
	static final double WING_STRONG_RADIUS = 12.0;
	/**
	 * 세기가 0 으로 잦아드는 거리(칸).
	 *
	 * <p><b>사람이 정하지 않았다.</b> 문서에 적힌 것은 「4칸 이내는 약하고 4~12칸이 가장 강하다」
	 * 뿐이고 12칸 밖은 말이 없다. 절벽처럼 끊으면 12.0 과 12.1 에 선 두 사람의 결과가 완전히
	 * 갈리므로 잦아들게 두었고, 20 인 것은 <b>부채꼴 브레스의 사거리와 같은 값</b>이다 — 이
	 * 페이즈가 「드래곤이 닿는 거리」로 이미 쓰는 숫자를 새로 만들지 않았다.
	 */
	static final double WING_FADE_RADIUS = 20.0;

	/**
	 * 사람이 <b>처음에 올린</b> 배율. <b>「날개퍼덕이기 때 넉백을 1.5배 늘려봐」</b>.
	 *
	 * <p>지우지 말 것. 이 값이 남아 있어야 {@link #WING_PUSH_SCALE} 이 <b>「올렸다가 절반으로
	 * 되돌렸다」</b>를 스스로 말하고, 다음에 「왜 0.75 라는 어정쩡한 수인가」를 묻는 사람이 여기서
	 * 답을 본다.
	 */
	static final double WING_PUSH_RAISE = 1.5;
	/**
	 * ⚠ 사람이 <b>플레이해 보고 다시 깎은</b> 몫. <b>「30프로미만 2페이지에서 밀쳐지는게 너무심해
	 * 지금보다 50프로는 안밀쳐지게하고」</b>.
	 *
	 * <p>「지금보다」가 <b>1.5배로 올린 뒤</b>를 가리킨다. 그러니 밑값(4)의 절반이 아니라
	 * <b>올린 값(6)의 절반</b>이다.
	 */
	static final double WING_PUSH_CUT = 0.5;
	/**
	 * ⚠⚠ 사람이 2026-10-04 에 <b>다시 올린</b> 몫. <b>「반대로 계속 밀치는 패턴은 밀치는 힘이 지금
	 * 거의 없어진 것처럼 됐어. 50프로 키워」</b>.
	 *
	 * <p>「지금」이 {@link #WING_PUSH_CUT} 으로 깎은 뒤(0.75)를 가리키므로 곱이 {@code 0.75 × 1.5} 다.
	 * 지우지 말 것 — 셋이 다 남아 있어야 「1.5배 → 절반 → 1.5배」가 스스로 말해진다.
	 *
	 * <h2>⚠ 「거의 없어진 것처럼」의 정체 — 코드는 수평을 한 톨도 안 깎았다</h2>
	 *
	 * <p>2026-10-04 에 {@code 1bf6c84}(세로 자르기 · 날개 밀치기 끊기)와 {@code 6ae77f2}(공중 비율)를
	 * 따라가 보았다. <b>수평을 줄이는 줄은 없다.</b>
	 *
	 * <ul>
	 *   <li>{@link TrialVelocity#syncedVertical} 은 <b>세로 한 성분만</b> 받고 돌려준다. {@link #shove} 의
	 *       수평은 {@code stepX × speed} 를 그대로 덮어쓴다</li>
	 *   <li>{@link #isAirborne} 은 바닥에 선 사람에게 거짓이다 — {@code Ground.surfaceAt} 이
	 *       <b>가장 높은 블록 + 1</b>(설 수 있는 높이)을 돌려주므로 선 사람의 {@code y − 지표} 가 0 이다</li>
	 *   <li>천장 둘은 섬 안쪽에서 걸리지 않는다 — {@code pushDistance} 는 반경 32 원까지,
	 *       {@code groundedReach} 는 땅이 끊기는 데까지만 자른다. 부탁한 거리(3칸)보다 한참 멀다</li>
	 * </ul>
	 *
	 * <p>원인은 <b>고치기 전의 느낌이 버그로 부풀어 있었던 것</b>이다. {@code 1bf6c84} 전에는 바닐라
	 * 드래곤이 날개 상자 안의 사람 세로에 매 틱 {@code +0.2} 를 쌓았고 {@code shove} 가 그것을 본인에게
	 * 배달했다. 번치를 맞은 사람이 <b>뜨고</b>, 뜬 채로는 감쇠가 0.546 → 0.91 이라 같은 수평 속도가
	 * <b>다섯 배</b>를 밀었다(바닥 0.59칸 → 공중 3.0칸). 사람이 기억하는 세기가 그것이고,
	 * {@link #WING_PUSH_CUT} 의 「너무심해」도 그 버그 위에서 나온 말이다. 버그가 닫히자 설계값 그대로인
	 * <b>바닥 0.59칸</b>만 남았다 — 걷는 사람이 0.6초에 2.6칸을 가므로 그것은 「없다」로 읽힌다.
	 *
	 * <p>⚠ <b>1.5배로도 버그 시절 세기에는 한참 못 미친다.</b> 바닥 한 번치가 0.59 → <b>0.89칸</b> 이고
	 * ({@code DragonLastStandPatternsTest.날개_넉백이_다시_1점5배가_됐다}), 버그 시절은 공중으로 떠서
	 * 그 다섯 배였다. 사람이 다시 「약하다」고 하면 그 비교를 먼저 보여 줄 것.
	 */
	static final double WING_PUSH_REGAIN = 1.5;
	/**
	 * 넉백 세기를 한 번에 곱하는 배율. <b>{@code 1.5 × 0.5 × 1.5 = 1.125}</b> 다
	 * (2026-10-04 전에는 {@code 1.5 × 0.5 = 0.75}).
	 *
	 * <p>구간마다 따로 곱하지 않고 <b>한 곳</b>에서 곱한다. 「4~12칸 구간만」으로 읽으면 12칸 밖의
	 * 잦아드는 구간이 그대로 남아 <b>12.0 과 12.1 의 결과가 절벽처럼 갈린다</b> — 잦아드는 구간은
	 * {@link #WING_PUSH_BLOCKS} 의 비율로 적혀 있어 저절로 따라 움직인다.
	 *
	 * <p>⚠ <b>이것은 「세기」의 답이고 「공중」의 답이 아니다.</b> 떠 있는 사람은 바닥의 다섯 배를 가는
	 * 것을 {@link #AIRBORNE_PUSH_SCALE} 가 따로 막는다 — <b>둘은 다른 문제이고 둘 다 사람이 말했다.</b>
	 */
	static final double WING_PUSH_SCALE = WING_PUSH_RAISE * WING_PUSH_CUT * WING_PUSH_REGAIN;

	/**
	 * 가장 강한 구간에서 <b>부탁하는</b> 미는 거리(칸). <b>4 → 6 → 3 → 4.5 다</b>(마지막이
	 * 2026-10-04 의 {@link #WING_PUSH_REGAIN}).
	 *
	 * <h2>밑값 4 는 왜 4 였는가</h2>
	 *
	 * <p>이 저장소가 「강한 넉백」으로 쓰는 단위는 {@code TrialEnderStorm.PUSH_BLOCKS}(8)이고,
	 * 「착지 충격」·「엔더폭풍」이 그 값을 쓴다. 그런데 이 패턴은 <b>피해가 0 인데 5초 동안 여덟
	 * 번</b> 민다. 한 번치가 「강한 넉백」 한 대와 같으면 5초 내내 조작이 덮어써져 <b>요구하는
	 * 행동(비켜서 붙기)을 할 수 없는 패턴</b>이 된다. 그래서 그 단위의 <b>절반</b>으로 잡았다.
	 *
	 * <h2>6 으로 올렸다가 사람이 플레이해 보고 <b>3</b> 으로 되돌렸다</h2>
	 *
	 * <p>사람 말이 <b>「30프로미만 2페이지에서 밀쳐지는게 너무심해 지금보다 50프로는
	 * 안밀쳐지게하고」</b>다. {@code 8 ÷ 2 × 1.5 × 0.5 = 3} 이라 이제 단위의 <b>8분의 3</b> 이고,
	 * 위의 근거(한 번치가 한 대와 같으면 안 된다)는 더 넉넉하게 성립한다.
	 *
	 * <p>⚠ <b>밑값 4 보다도 작아진 것이 이상한 것이 아니다.</b> 밑값 4 는 「한 대와 같으면 안
	 * 된다」만 지킨 추정이고, 3 은 <b>사람이 실제로 맞아 보고 정한 값</b>이다. 이 판의 규칙대로
	 * 플레이로 정해진 쪽이 이긴다.
	 *
	 * <p>⚠ <b>2026-10-04 에 4.5 로 다시 올랐다</b>({@link #WING_PUSH_REGAIN}). 3 이 「너무심해」였던 것은
	 * 세로 배달 버그로 사람이 떠서 다섯 배를 밀렸기 때문이었고, 버그가 닫힌 뒤의 3 은 「거의 없어진
	 * 것처럼」이었다. 4.5 는 단위(8)의 16분의 9 라 「한 번치가 한 대와 같으면 안 된다」는 그대로 선다.
	 *
	 * <p>⚠ <b>천장은 그대로다.</b> 세기를 어떻게 바꿔도 목적지는 {@code TrialEnderStorm.pushDistance}
	 * (반경 32 안)와 {@code TrialLandingShock.groundedReach}(땅이 이어진 데까지)가 자른다 —
	 * {@code DragonLastStandPatternsTest} 가 <b>새 값으로 섬 곳곳에서 다시 굴려 본다.</b>
	 *
	 * <p>실제로 밀리는 거리는 이보다 훨씬 짧다 — {@code TrialEnderStorm.pushVelocity} 가 공중
	 * 감쇠로 속도를 잡으므로 발이 땅에 붙어 있으면 바닥 마찰({@value #GROUND_DRAG})이 먼저 먹어
	 * <b>5분의 1 남짓</b>이다. 적힌 값은 <b>천장</b>이고, 떠 있으면 그 천장까지 가던 것을
	 * {@link #AIRBORNE_PUSH_SCALE} 가 바닥과 같은 거리로 되돌린다.
	 */
	static final double WING_PUSH_BLOCKS = TrialEnderStorm.PUSH_BLOCKS / 2.0 * WING_PUSH_SCALE;
	/**
	 * 드래곤 바로 아래에서 부탁하는 미는 거리(칸). <b>1.5 → 2.25 → 1.125 → 1.6875 다.</b>
	 *
	 * <p>0 이 아닌 것은 「약하다」이지 「없다」가 아니기 때문이다. 0 으로 두면 머리 밑이
	 * <b>완전한 안전지대</b>가 되어 이 패턴이 아무것도 요구하지 않는다.
	 *
	 * <p>같은 배율을 여기에도 곱한다. 한쪽만 깎으면 <b>「4칸 이내는 약하다」의 정도가 달라진다</b> —
	 * 6 : 2.25 였던 비가 3 : 2.25 가 되면 머리 밑이 상대적으로 덜 안전해진다. 올릴 때도 같다
	 * (4.5 : 1.6875 = 6 : 2.25 그대로).
	 */
	static final double WING_NEAR_PUSH_BLOCKS = 1.5 * WING_PUSH_SCALE;

	/**
	 * <b>바깥으로</b> 흐르는 가닥의 갈래 수.
	 *
	 * <p>사람 말이 <b>「각각 패턴을 쓴다느 느낌이 들게」</b>이고, 밀어내는 패턴이 밀어내는 것처럼
	 * 보이려면 <b>무엇인가가 바깥으로 움직여야</b> 한다. 전에는 파랑 고리 둘뿐이어서 <b>세기
	 * 지도만 있고 바람이 없었다</b> — 고리는 가만히 있으므로 「여기가 세다」만 말하고 「밀린다」는
	 * 말하지 못한다.
	 *
	 * <p>24 인 까닭은 <b>15도마다 하나</b>라는 것이다. 가닥 하나가 3칸 남짓 흐르므로
	 * ({@link #critDrift}) 반경 12 에서 가닥 사이가 3.1칸이고, 그쯤이면 눈에는 <b>방사선</b>으로
	 * 읽힌다. 고리로 읽히기를 바라는 것이 아니라 <b>방향</b>이 읽히기를 바라는 것이라 고리의
	 * 간격 규칙({@code TrialWarning.POINT_GAP})을 따르지 않는다.
	 *
	 * <p>⚠ 공허 흡입의 {@link #SUCK_STREAM_SPOKES} 와 <b>같은 값이어야 한다.</b> 한쪽이 성기면
	 * 「한쪽이 더 세다」로 읽혀 방향 대비가 흐려진다 — 시험이 그 등식을 붙든다.
	 */
	static final int WING_GUST_SPOKES = 24;
	/**
	 * 가닥을 쏘는 세기. {@link #SUCK_STREAM_DRIFT} 와 <b>같은 값이다.</b>
	 *
	 * <p>{@link #critDrift} 로 재면 3.04칸이다 — 반경 12 짜리 고리에서 가닥 사이가 3.1칸이므로
	 * <b>가닥 길이와 가닥 사이가 거의 같다.</b> 더 길게 쏘면 서로 이어 붙어 「퍼져 나간다」가
	 * 「원이 커진다」로 읽히고, 짧으면 점으로 흩어진다.
	 */
	static final double WING_GUST_DRIFT = 3.0;

	/** 충격파의 첫 음높이. 「아직 멀었다」. */
	private static final float WING_FLAP_PITCH_LOW = 0.7F;
	/**
	 * 충격파의 마지막 음높이.
	 *
	 * <p>여덟 번이 <b>모두 같은 음높이</b>였다. 그러면 「몇 번째인가」를 소리로 알 수 없어 다섯
	 * 번째와 여덟 번째가 구별되지 않고, <b>언제 끝나는지 모르는 채 5초를 버틴다.</b> 부채꼴
	 * 예고가 같은 근거로 음높이를 올린다({@link #chargePitch}) — 새 소리를 만들지 않고
	 * <b>이미 있는 소리로 한 가지를 더 말하는</b> 쪽이 규약에 맞다.
	 */
	private static final float WING_FLAP_PITCH_HIGH = 1.3F;

	// ------------------------------------------------------------------ ② 부채꼴 브레스

	/**
	 * 예고(틱). <b>4초. 사람이 정한 값이고 「즉사 허용」의 조건 셋 중 하나다.</b>
	 *
	 * <p>⚠⚠ <b>2026-10-04 에 5초 → 4초로 줄었다.</b> 사람 말: <b>「브레스 터지는 시간을 1초
	 * 감소시켜」</b>. 문서의 「즉사 허용」 조건이 <b>「5초 예고 · 머리 고정 · 90도」</b>였으므로 그
	 * 첫째를 <b>사람이 손으로 4초로 고친 것</b>이다 — 시험({@code 즉사를_허용하는_조건_셋이_지켜진다})이
	 * 이제 4초를 바닥으로 붙든다. <b>사람에게 다시 묻지 않고 그 아래로 내리지 말 것.</b>
	 *
	 * <p>묶여 있는 것이 저절로 따라간다 — 패턴 길이 120 → 100({@code Pattern.CONE_BREATH}), 축소 앞
	 * 잠금 120 → 100({@code DragonLastStandZone.shrinkLockoutLead}), 면의 심지 120 → 100
	 * ({@code DragonLastStandConePanel.FUSE_TICKS}), 충전음 다섯 번 → 네 번({@link #chargePitch}).
	 * 쿨 8초({@code BREATH_COOLDOWN_TICKS})와 「축소 직후 3초 금지」는 예고 길이와 무관한 시계라
	 * 그대로이고 여전히 말이 된다 — 쿨은 시작에서 재고 연속 금지가 있어, 다음 브레스를 고를 수 있는
	 * 가장 이른 틱이 {@code 100 + 40 + 100 + 40 = 280틱} 뒤라 160틱 쿨은 전처럼(그때 300) 실제로는
	 * 걸리지 않는다.
	 */
	static final int CONE_WARN_TICKS = 80;
	/**
	 * 터진 뒤 불꽃이 남는 시간(틱). 1초.
	 *
	 * <p>⚠ <b>잔류가 아니다.</b> 피해는 터지는 그 한 틱에 한 번만 들어가고 이 1초는 파티클뿐이다 —
	 * 「잔류 없음」이 사람이 정한 것이고, 그 1초 동안 부채꼴 안에 들어가도 아무 일이 없다.
	 * 남긴 까닭은 「무엇이 방금 지나갔는가」가 안 보이면 다음번에 배울 것이 없어서다.
	 */
	static final int CONE_AFTERGLOW_TICKS = 20;
	/** 부채꼴의 각도. <b>사람이 정한 값이고 「즉사 허용」의 조건 셋 중 하나다</b>(옆으로 빠질 곳이 남는다). */
	static final double CONE_DEGREES = 90.0;
	/** 사거리(칸). <b>사람이 정한 값이다.</b> */
	static final double CONE_RANGE = 20.0;

	/**
	 * 예고 중에 「불이 모인다」 소리를 내는 간격(틱). 1초.
	 *
	 * <h2>왜 층 소리만으로는 모자랐는가</h2>
	 *
	 * <p>사람이 <b>「부채꼴 브레스도 전조에 소리를 뭔가 넣엇으면해」</b>라고 했다. 그런데
	 * {@link #warnCone} 은 <b>처음부터 {@link TrialWarning#soundFor} 를 부르고 있었다.</b> 들리지
	 * 않았던 까닭은 층 소리가 <b>예고 내내 세 번</b>뿐이고({@code stageFor} 가 100·50·14틱 남은
	 * 자리에서만 바뀐다 — 예고가 80틱이 된 지금은 첫 틱·50·14) 그 첫 번째가 <b>드래곤 울음</b>이라
	 * 붙박이 드래곤이 제 울음을 내는 것과 구별되지 않기 때문이다.
	 *
	 * <p>그래서 층 소리를 <b>그대로 두고</b> 그 위에 <b>되풀이되는 충전음</b>을 얹었다 — 1초마다
	 * 한 번씩, 음높이가 {@link #chargePitch} 로 낮은 데서 높은 데까지 오른다. 「무언가 차오르고
	 * 있다」는 되풀이와 음높이로만 말할 수 있고, 한 번 울리는 소리로는 못 한다. 예고가 4초가 되어
	 * 다섯 번이던 것이 <b>네 번</b>(0·20·40·60틱)이고, 마지막이 여전히 가장 높다.
	 */
	static final int CONE_CHARGE_TICKS = 20;
	/** 충전음의 첫 음높이. */
	private static final float CONE_CHARGE_PITCH_LOW = 0.6F;
	/** 충전음의 마지막 음높이. 1.0 을 넘겨야 「올라갔다」가 들린다. */
	private static final float CONE_CHARGE_PITCH_HIGH = 1.4F;

	/** 터지는 틱에 불꽃을 뿌리는 고리의 간격(칸). */
	private static final double FLAME_RING_STEP = 2.5;
	/** 그 고리에 찍는 불꽃 사이 간격(칸). 표식이 아니라 연출이라 바닥 선보다 성기다. */
	private static final double FLAME_ARC_GAP = 2.5;

	// ------------------------------------------------------------------ ③ 공허 흡입

	/** 검은 원의 반경(칸). <b>사람이 정한 값이다.</b> */
	static final double SUCK_RADIUS = 4.0;
	/**
	 * 예고(틱). <b>2초. 사람이 정한 값이다.</b>
	 *
	 * <p>⚠⚠ <b>2026-10-04 에 3초 → 2초로 줄었다.</b> 사람 말: <b>「공허 흡입 예고 2초로」</b>. 그 아래로
	 * 내리려면 사람에게 다시 물을 것 — 바닥은 {@code TrialWarning.TICKS_SIDESTEP}(30틱, 옆으로 비킬
	 * 시간)이고 시험이 그것을 붙든다.
	 *
	 * <p>묶여 있는 것이 저절로 따라간다 — 패턴 길이 180 → 160({@code Pattern.VOID_SUCTION} 이 세 값을
	 * 더한다), 흡입 시작 60 → 40, 터짐 160 → 140, 결계가 때리는 틱 60·80·100·120·140 →
	 * <b>40·60·80·100·120</b>({@link #suckFireDue}). 결계와 터짐 사이는 여전히 20틱이라 바닐라 피격
	 * 무적(10틱)에 걸리지 않는다 — 흡입 구간(100)과 결계 주기(20)가 둘 다 예고 끝에서 재므로 예고
	 * 길이가 그 간격을 바꾸지 못한다. 한 틱 점 예산도 그대로다(틱마다 그리는 것이 같다).
	 *
	 * <p>⚠ <b>층 소리가 셋에서 둘이 된다.</b> {@code TrialWarning.stageFor} 가 100 / 50 / 14 로 가르므로
	 * 남은 40틱은 처음부터 MARK 구간이라 APPROACH(드래곤 울음)가 빠지고 MARK · IMMINENT 둘만 울린다
	 * ({@code TrialRisks.stageJustChanged} 의 {@code lead} 가 첫 틱의 MARK 를 살린다). 흡입음
	 * {@code BREEZE_INHALE} 은 흡입 구간에서만 0.5초마다라 횟수·음높이가 그대로다.
	 *
	 * <p>⚠ 예고 동안 달려서 버는 거리가 17칸 → <b>11칸</b>(달리기 종착 0.2806 × 40)이다. 3단계 지대가
	 * 반경 12칸 원이라 <b>예고만으로 지대 끝을 넘던 긴장은 사라졌다</b> — 대신 터지기까지 시간이 짧다.
	 * (지대는 같은 날 보더를 버리고 「나갈 수 있는 원」이 됐다 — 더 달려 원 밖에서 버티는 길도 있다.)
	 */
	static final int SUCK_WARN_TICKS = 40;
	/** 빨아들이는 시간(틱). 5초. <b>사람이 정한 값이다.</b> */
	static final int SUCK_PULL_TICKS = 100;
	/** 터진 뒤 파티클이 남는 시간(틱). 1초. <b>잔류가 아니다</b> — 피해는 터지는 한 틱뿐이다. */
	static final int SUCK_AFTERGLOW_TICKS = 20;

	/**
	 * 빨아들이는 손이 닿는 거리(칸). <b>사람이 정하지 않았다.</b>
	 *
	 * <p>사람이 정한 것은 「반경 4칸」이고 <b>그것은 터질 때 아픈 자리</b>다. 손이 4칸까지만
	 * 닿으면 이 패턴은 <b>아무것도 요구하지 않는다</b> — 4칸 밖에 선 사람은 가만히 있어도 되고,
	 * 안에 선 사람도 한 칸만 걸어 나가면 끝이라 「반대쪽으로 달려서 도망가야지」가 성립하지 않는다.
	 *
	 * <p>그래서 <b>새 숫자를 만들지 않고</b> 이 페이즈가 이미 「드래곤이 닿는 거리」로 쓰는 20 을
	 * 쓴다 — {@link #CONE_RANGE} 가 그 값이고 {@link #WING_FADE_RADIUS} 도 같은 근거로 같은 값이다.
	 *
	 * <p>⚠ 3단계 지대가 반경 12칸 원이므로 <b>지대 안 전원이 손에 들어온다.</b> 그것이 의도다 —
	 * 사람이 「반대쪽으로 달려서 도망가야지」라고 했고, 한 사람만 달리는 패턴이 아니다.
	 */
	static final double SUCK_REACH = CONE_RANGE;

	/**
	 * ⚠ <b>처음 정한 「달리기보다 약간 약하게」의 「약간」.</b> <b>사람이 정하지 않은 값이었다.</b>
	 *
	 * <p>사람이 정한 것은 <b>「반대쪽으로 달려서 도망가야지」</b>, 곧 <b>달리면 벗어나는 것이
	 * 정답</b>이다. 그러려면 세기가 달리기보다 <b>반드시 약해야</b> 하고, 한참 약하면
	 * 「빨려들어간다」가 말뿐이 된다. 0.85 가 그 둘 사이였고, 그때의 답이 이랬다 — 달리면 1초에
	 * +0.86칸, 걸으면 −0.47칸(끌려든다), 가만히 −4.87칸.
	 *
	 * <p>⚠ <b>기록이다 — 2026-10-04 저녁부터 곱하지 않는다</b>({@link #SUCK_SPRINT_RATIO} 를 볼 것).
	 * 지우지 말 것. 「0.85 → 0.5525 → 0.8」이 스스로 말해지려면 셋이 다 남아 있어야 한다.
	 */
	static final double SUCK_SPRINT_RATIO_FIRST = 0.85;
	/**
	 * ⚠⚠ 사람이 2026-10-04 에 <b>깎은</b> 몫. <b>「빨아들이는 패턴 빨아들이는 힘이 35프로 감소시키는
	 * 대신, 드래곤 주위에 있으면 계속 딜 맞게」</b>. 35% 를 빼고 남는 것이 0.65 다.
	 *
	 * <p>「대신」이 요점이다 — 당김을 줄이고 그 자리를 <b>불 결계</b>({@link #SUCK_FIRE_RADIUS})가
	 * 메운다. 당김만 보면 이 패턴이 쉬워졌지만 원 안에 머무는 값이 새로 생겼다.
	 *
	 * <p>⚠⚠ <b>기록이다 — 2026-10-04 저녁부터 곱하지 않는다.</b> 사람이 「너무 세다」고 느낀 것이
	 * <b>세기가 아니라 배달 버그</b>였다({@link #pullSuck}). 0.85 에서 사람이 겪은 것은 「달려도 초당
	 * 2.27칸 끌려든다」였고 이 0.65 를 곱한 뒤에도 「달려도 초당 0.62칸 끌려든다」였다 — 어느 쪽도
	 * 설계 표의 「달리면 벗어난다」가 아니었다. 깎은 뜻(「달려서는 벗어나게」)은 {@link #SUCK_SPRINT_RATIO}
	 * 가 그대로 지키고, 불 결계는 그대로 둔다.
	 */
	static final double SUCK_PULL_KEEP = 0.65;
	/**
	 * 당기는 세기가 달리기 입력의 몇 배인가. <b>0.8</b> 이다
	 * (0.85 → 2026-10-04 낮 0.5525 → 같은 날 저녁 0.8).
	 *
	 * <h2>⚠⚠ 0.5525 의 표는 거짓이었다 — 배달이 셈과 다르게 움직였다</h2>
	 *
	 * <p>사람 말(0.5525 로 플레이한 뒤): <b>「빨아들이는 거 아직 너무 셈. 적어도 반대로 달려서 저항할 수
	 * 있을 만큼」</b>. 표에는 「달리면 +2.56칸/초」가 적혀 있었다. 표의 식 {@code v' = (v + a − 당김) × 감쇠}
	 * 는 <b>사람의 속도 {@code v} 가 틱을 넘어 남는다</b>는 가정 위에 있었는데, 옛 {@link #pullSuck} 은
	 * 매 틱 {@code syncVelocity} 로 <b>서버의 속도를 사람에게 덮어썼다</b>(26.3 {@code Entity.lerpMotion}
	 * 이 {@code setDeltaMovement} 다). 서버의 속도에는 사람 입력이 없으므로 사람이 달려서 쌓은 속도가
	 * 매 틱 지워지고, 남는 것은 <b>그 틱의 입력 한 번(0.1274)</b> 대 <b>당김 천장 통째(0.1582)</b>였다 —
	 * 달려도 초당 0.62칸 끌려들었다. 지금은 당김을 <b>더하는</b> 길로 보낸다({@link #pullSuck}).
	 *
	 * <h2>왜 0.8 인가 — 걷기 문턱과 1.0 사이에서 <b>달리기 쪽 여유를 크게</b></h2>
	 *
	 * <p>배달을 고치면 표의 식이 참이 된다. 그러면 0.5525 는 <b>걸어도 초당 1.22칸을 벗어나</b>
	 * 「빨아들인다」가 걷는 사람에게 말뿐이 된다. 지키는 선은 둘이다 — <b>달리면 벗어난다</b>(1.0 미만)와
	 * <b>걸으면 끌려든다</b>({@code 1 ÷ 1.3 = 0.769} 초과). 그 사이에서 사람이 먼저 말한 쪽(「적어도
	 * 달려서 저항할 수 있을 만큼」)에 여유를 몰아 0.8 로 잡았다. 달리기와 걷기의 차이(초당 1.29칸)는
	 * 이 값과 무관하므로 그 차이를 어디서 0 으로 가르느냐만 고른 것이다.
	 *
	 * <table border="1">
	 *   <caption>이 값이 만드는 답 — 실제로 배달되는 모델(당김을 더하고 사람의 속도가 남는다).
	 *     달리기 종착 0.2806 · 걷기 종착 0.2159 · 당김 천장 0.2245 칸/틱</caption>
	 *   <tr><th>사람이 하는 것</th><th>1초에 벌거나 잃는 거리</th><th>5초(흡입 전체)</th>
	 *     <th>0.5525 때 사람이 실제로 겪은 것</th></tr>
	 *   <tr><td><b>달려서 반대쪽으로</b></td><td><b>+1.12칸</b> — 벗어난다</td><td>+5.6칸</td>
	 *     <td>−0.62칸(끌려들었다)</td></tr>
	 *   <tr><td>걸어서 반대쪽으로</td><td><b>−0.17칸</b> — 천천히 끌려든다</td><td>−0.9칸</td>
	 *     <td>−1.20칸</td></tr>
	 *   <tr><td>가만히</td><td><b>−4.49칸</b> — 끌려든다</td><td>−22칸</td><td>−3.16칸</td></tr>
	 * </table>
	 *
	 * <p>⚠ <b>가만히 선 사람은 0.5525 때보다 세게 끌린다</b>(−3.16 → −4.49). 가만히 선 사람에게는 지울
	 * 속도가 처음부터 없어서 버그가 그 줄만은 바꾸지 않았기 때문이다 — 사람이 그 줄을 「세다」고 하면
	 * 이 값을 내리되 <b>0.769 아래로 내리면 걸어도 벗어난다</b>는 것을 먼저 말할 것.
	 *
	 * <p>달리며 뛰어도 같다 — 공중에서는 당김이 {@link #AIRBORNE_PUSH_SCALE} 만큼 줄고 사람의 공중
	 * 입력(0.026)도 감쇠 0.91 에서 거의 같은 종착(0.289)에 닿아, 공중 한 토막의 순이동이 초당 +1.29칸
	 * 이다. 뛰기의 앞쪽 덤(0.2)은 그 위에 얹힌다.
	 *
	 * <p>1.0 을 넘기면 어떤 사람도 벗어날 수 없어 「달리면 벗어난다」가 그 자리에서 거짓이 된다 —
	 * {@code DragonLastStandPatternsTest} 가 그 선과 0.769 선을 함께 붙들고 있다.
	 */
	static final double SUCK_SPRINT_RATIO = 0.8;

	/**
	 * 매 틱 사람의 속도에 <b>더하는</b> 값(칸/틱). {@code 0.1 × 1.3 × 0.98 × 0.8 = 0.10192}.
	 *
	 * <p>사람의 입력 가속과 <b>같은 자리에서 같은 감쇠를 지나므로</b> 비율을 그대로 곱하면 된다 —
	 * 둘 다 {@code v' = (v + a) × 감쇠} 의 {@code a} 다. ⚠ 이 말이 참인 것은 <b>당김이 사람의
	 * 속도에 더해질 때뿐</b>이다 — 덮어쓰면 {@code v} 가 매 틱 지워져 식이 무너진다({@link #pullSuck}).
	 */
	static final double SUCK_STEP = WALK_INPUT * SPRINT_MULTIPLIER * INPUT_DAMPING * SUCK_SPRINT_RATIO;

	/**
	 * ⚠⚠ <b>안쪽으로 갈 수 있는 가장 빠른 속도(칸/틱). 이 한 줄이 이 패턴의 안전장치다.</b>
	 *
	 * <h2>세로 성분을 어떻게 다뤘는가 — <b>한 톨도 건드리지 않는다</b></h2>
	 *
	 * <p>사람의 세로 속도에 <b>한 톨도 더하지 않는다</b>({@link #pullSuck} 이 보내는 더할 값의
	 * {@code y} 가 {@code 0}). 날개 퍼덕이기가 같은 짓을 하지만 <b>당기는 쪽에는 이유가 하나 더 있다.</b>
	 *
	 * <p>⚠ 2026-10-04 저녁부터는 <b>세로를 내려 보내는 일 자체가 없다.</b> 전에는 서버의
	 * {@code deltaMovement} 통째를 덮어써 보냈으므로 세로를 {@link TrialVelocity#syncedVertical} 로
	 * 잘라야 했는데, 지금은 <b>더할 수평 한 벌</b>만 보낸다 — 바닐라 드래곤이 서버쪽에 쌓아 둔 세로가
	 * 섞일 길이 없고, 스스로 뛴 사람의 점프도 한 톨도 안 깎인다.
	 *
	 * <ul>
	 *   <li><b>아래로 당기면 사람을 땅에 눌러 넣는다.</b> 바닐라 충돌이 블록 속으로는 못 넣지만,
	 *       내리누르는 동안은 점프가 죽어 <b>구덩이에 빠진 사람이 나올 수 없다</b> — 5초 동안
	 *       조작을 빼앗는 것이라 「달리면 벗어난다」와 정면으로 어긋난다</li>
	 *   <li>⚠⚠ <b>위로 당기면 그 자리에서 사고가 된다.</b> 원의 중심(= 드래곤 발밑)은 섬 표면보다
	 *       <b>네 칸 높다</b>({@link DragonLastStandDome#domeOriginY} 의 셈). 그러니 중심을 향해
	 *       <b>세로까지</b> 당기면 섬에 선 사람이 <b>들린다.</b> 들리는 순간 감쇠가 바닥
	 *       ({@value #GROUND_DRAG})에서 공중(0.91)으로 바뀌어 같은 {@link #SUCK_STEP} 이 만드는
	 *       종착 속도가 <b>8.4배</b>가 되고, 그때는 달려도 못 벗어난다. 넉백의 천장 논리
	 *       ({@code TrialEnderStorm.pushDistance})가 <b>여기에 그대로 옮겨 오지 않는</b> 까닭이
	 *       이것이다 — 그쪽이 막는 것은 「섬 밖으로 나가는 것」인데 당기는 방향은 안쪽이라 그
	 *       위험이 아예 없고, 대신 <b>이 세로 함정</b>이 생긴다</li>
	 * </ul>
	 *
	 * <h2>그래서 수평만 당기고, 그 수평에도 천장을 씌운다</h2>
	 *
	 * <p>세로를 안 건드려도 사람이 <b>제 발로 점프하면</b> 공중 감쇠에 들어간다. 그때
	 * {@link #SUCK_STEP} 을 그냥 매 틱 더하면 종착 속도가 {@code a × 0.91 / 0.09} 로 바닥의
	 * <b>8.4배</b>가 되어 점프한 사람이 중심으로 날아간다. 얼음을 깔아도(마찰 0.98) 같은 일이다.
	 *
	 * <p>막는 길이 둘이고 둘 다 {@link #suckImpulse} 에 있다.
	 *
	 * <ul>
	 *   <li><b>공중이면 한 번치를 {@link #AIRBORNE_PUSH_SCALE} 만큼 깎는다.</b> 날개 퍼덕이기와 같은
	 *       비율 · 같은 어휘다. 그러면 공중 종착이 바닥 종착과 같아지고, 바깥으로 달리며 뛴 사람도
	 *       바닥에서 달리는 사람과 거의 같은 답을 받는다 — 점프가 이득도 손해도 아니다</li>
	 *   <li><b>결과 속도에 천장을 씌운다.</b> 값은 <b>바닥에서의 종착 속도</b>({@code a / (1 − 감쇠)})다.
	 *       바닥에서는 그것이 평형점이라 걸리지 않고, 얼음처럼 감쇠가 다른 바닥에서는 걸려
	 *       <b>이 값보다 빨리 안쪽으로 가게 만들지 않는다.</b></li>
	 * </ul>
	 *
	 * <p>⚠⚠ <b>「결과 속도」는 서버의 {@code deltaMovement} 가 아니다.</b> 서버는 사람의 이동을 사람
	 * 입력 없이 따로 굴리므로(26.3 {@code Player.getMoveSimulationType} =
	 * {@code AUTHORITATIVE_SIDE_AND_SERVER}, 서버의 {@code xxa}·{@code zza} 는 0) 그 수는 우리가
	 * 더한 당김의 메아리일 뿐이다 — 옛 {@link #pullSuck} 이 그것을 「사람의 속도」로 읽었다. 지금은
	 * {@code getKnownMovement()}(사람이 지난 틱에 <b>실제로</b> 간 만큼)에 그 바닥의 감쇠를 곱해
	 * 사람의 지금 속도로 삼는다. 한 박자 늦은 수지만 평형점은 늦음과 무관하다
	 * ({@code DragonLastStandPatternsTest} 가 늦음 0 · 1 · 3 · 6틱으로 굴려 본다).
	 *
	 * <p>{@code TrialEnderStorm.pushVelocity} 가 「어긋나는 방향이 언제나 <b>덜 미는 쪽</b>이어야
	 * 한다」라고 적어 둔 그 태도와 같다.
	 */
	static final double SUCK_MAX_INWARD = SUCK_STEP / (1.0 - GROUND_DRAG);

	/**
	 * 당김을 싣는 패킷의 「폭발 중심」을 사람 머리 위로 이만큼 띄운다(칸).
	 *
	 * <p>당김은 {@code ClientboundExplodePacket} 의 {@code playerKnockback} 으로 보낸다({@link #pullSuck}).
	 * 그 패킷은 받는 쪽에서 <b>입자 하나</b>를 중심에 반드시 찍는다(26.3
	 * {@code ClientPacketListener.handleExplosion} → {@code ClientLevel.addParticle}). 소리는
	 * {@code playSound = false} 로 끄고 블록 조각은 빈 목록이라 안 나오지만 그 입자 하나는 끌 깃발이 없다.
	 *
	 * <p>26.3 {@code ClientLevel.doAddParticle} 이 카메라에서 <b>32칸</b>(제곱 1024) 넘는 입자를 버리므로
	 * ({@code ParticleType.getOverrideLimiter()} 가 거짓인 종류만 — {@code ASH} 가 그렇다) 그보다 멀리
	 * 두면 <b>아무것도 안 그려진다.</b> 64 는 그 두 배다. 이 패턴의 연출은 이미 점 예산 안에서 짜여
	 * 있으니 매 틱 사람마다 입자를 하나씩 더하지 않는다.
	 */
	static final double SUCK_PACKET_HIDE_LIFT = 64.0;

	/**
	 * 검은 원을 채우는 색.
	 *
	 * <p>사람 말이 <b>「흡입은 드래곤에 검은원이고」</b>다. 규약({@link TrialWarning.Colors})에
	 * <b>다섯째 색을 더하지 않았다</b> — 이 검정은 「무엇을 해야 하는가」를 말하는 <b>표식이
	 * 아니라</b> 구멍 그 자체이고, 「서 있으면 죽는다」는 경계는 여전히 규약의 빨강이 말한다
	 * ({@link TrialWarning.Colors#DEADLY} 로 그리는 반경 4 고리). {@link DragonLastStandConePanel}
	 * 이 빨간 면에서 한 것과 같은 판단이다.
	 */
	private static final int SUCK_BLACK = 0x000000;

	/**
	 * 검은 원의 속을 <b>몇 틱에 나눠</b> 채울지. {@code TrialEndRain} 의 상한을 그대로 쓴다.
	 *
	 * <p>반경 4 원의 속을 {@link #MARK_GAP}(0.5) 간격의 고리 일곱 겹으로 채우면 <b>180점</b>이고,
	 * 경계 고리 51점을 더하면 한 틱에 231점이다. 상시 번개(70)와 겹치면 301점이라 <b>이 페이즈의
	 * 가장 바쁜 틱을 갈아치운다.</b> 나눠 그리면 30점이라 부채꼴 예고(202)가 여전히 가장 바쁜 쪽으로
	 * 남고, 예산을 세는 식도 바뀌지 않는다.
	 *
	 * <p>6 인 까닭은 <b>먼지 파티클 수명이 최소 8틱</b>이라는 것이고
	 * ({@code TrialWarning.markGround} 의 「stride 는 8보다 작아야 한다」), 상시 번개가 같은
	 * 근거로 같은 값을 쓴다. 예고가 40틱이라(2026-10-04 전에는 60틱) 처음 여섯 틱이 성긴 것은 묻힌다.
	 */
	static final int SUCK_MARK_STRIDE = TrialEndRain.MARK_MAX_STRIDE;

	/** 흡입음을 되풀이하는 간격(틱). 0.5초. 빨아들이는 동안만 울린다. */
	static final int SUCK_BREATH_TICKS = 10;

	/**
	 * ⚠ <b>안으로</b> 흐르는 가닥의 갈래 수. <b>사람이 이것을 말했다.</b>
	 *
	 * <p><b>「빨아드리는거랑 밀치는거랑 이펙트가 너무 구분이안됨 … 예를들어 빨아드리는 패턴이면
	 * 입자들이 드래곤에게 빨려들어가는 입자가 잘보이면 이해하잖아」</b>
	 *
	 * <p>전에는 <b>검은 원 + 빨간 경계 고리 + 끌려가는 사람 발밑의 재</b>뿐이었다. 셋 다
	 * <b>가만히 있는 표식</b>이라 「여기가 위험하다」만 말하고 <b>「안으로 당긴다」는 한 점도 말하지
	 * 않았다</b> — 그래서 날개 퍼덕이기의 파랑 고리 둘과 모양만 다른 원으로 보였다.
	 *
	 * <p>{@link #WING_GUST_SPOKES} 와 <b>같은 값이다.</b> 같은 점 · 같은 개수 · 같은 길이로
	 * <b>방향만 반대</b>여야 두 패턴이 서로의 반대로 읽힌다. 시험이 그 등식을 붙든다.
	 */
	static final int SUCK_STREAM_SPOKES = WING_GUST_SPOKES;
	/**
	 * 가닥을 쏘는 세기. {@link #WING_GUST_DRIFT} 와 <b>같은 값이다.</b>
	 *
	 * <p>⚠ <b>이 값은 입자에만 쓴다.</b> 사람을 당기는 세기는 {@link #SUCK_STEP} 이고
	 * <b>달리기 입력의 55.25%</b>(사람이 2026-10-04 에 35% 깎았다) 위에 서 있다 — 둘을 같은 수로 묶지
	 * 말 것. ⚠ 당김을 깎으면서 <b>가닥은 그대로 두었다.</b> 「안으로 빨린다」는 방향의 말이지 세기의
	 * 말이 아니고, 날개 가닥과 같은 세기여야 「반대」로 읽힌다.
	 */
	static final double SUCK_STREAM_DRIFT = WING_GUST_DRIFT;
	/**
	 * 가닥이 생기는 자리가 <b>한 틱에 안으로 들어오는 거리</b>(칸).
	 *
	 * <p>가닥 하나는 3칸만 흐르고 죽는다({@link #critDrift}). 그러니 <b>생기는 자리 자체가 안으로
	 * 들어와야</b> 20칸을 가로지르는 흐름이 보인다 — 「엔더 파동」·「착지 충격」의 고리가 퍼지는 것과
	 * 같은 수법인데 방향이 반대다.
	 *
	 * <p>1.5 면 {@link #SUCK_REACH}(20)에서 {@link #SUCK_RADIUS}(4)까지 <b>16칸을 10.67틱</b>에
	 * 지난다. 사람이 달려서 버는 것이 초당 2.56칸이므로(2026-10-04 전에는 0.86칸) 흐름이 사람보다
	 * <b>열두 배</b> 빠르고, 그래서 「흐름은 빠른데 나는 버틸 수 있다」가 보인다. 느리게 하면 흐름이
	 * 사람과 같은 속도로 보여 「도망칠 수 없다」로 읽힌다.
	 */
	static final double SUCK_STREAM_SPEED = 1.5;
	/**
	 * 같은 갈래에 가닥을 <b>몇 벌</b> 겹쳐 흘릴지.
	 *
	 * <p>한 벌이면 갈래마다 점 하나가 들어오고, {@code CRIT} 수명이 4~10틱이라 눈에는 4~10개가
	 * 한 줄로 보인다. 그 줄이 16칸을 10.67틱에 지나므로 <b>줄과 줄 사이에 빈 구간</b>이 생긴다 —
	 * 둘로 겹쳐 반 바퀴 어긋나게 쏘면 그 구간이 메워져 <b>끊기지 않는 흐름</b>이 된다.
	 *
	 * <p>셋으로 늘리지 않은 것은 점 때문이다. 갈래 24 × 벌 2 = 48점이고, 셋이면 72점이라 이
	 * 패턴이 부채꼴 예고를 넘어선다({@link #suckWarnPoints}).
	 */
	static final int SUCK_STREAM_PHASES = 2;

	/**
	 * ⚠⚠ <b>불 결계</b>의 반경(칸). <b>사람이 정한 값이다</b> — 검은 원({@link #SUCK_RADIUS})과 같은 자리.
	 *
	 * <h2>사람 말 (2026-10-04)</h2>
	 *
	 * <p><b>「빨아들이는 힘이 35프로 감소시키는 대신, 드래곤 주위에 있으면 계속 딜 맞게. 그리고 옆에
	 * 있으면 딜 맞는다를 알기 쉽게 드래곤 주위에 불 결계가 생기게」</b>. 반경·초당 피해는 물어서 답을
	 * 받았다 — <b>반경 4 · 초당 4</b>.
	 *
	 * <p>같은 원이라 「검은 원 안 = 터질 때 맞는다」와 「불 고리 안 = 지금 맞는다」가 <b>한 경계</b>다.
	 * 경계가 둘이면 사람이 4칸과 다른 칸 사이에서 무엇이 무엇인지 따로 배워야 한다.
	 */
	static final double SUCK_FIRE_RADIUS = SUCK_RADIUS;

	/**
	 * 불 결계가 원 안의 사람에게 넣는 <b>적히는</b> 피해(초당 한 번). <b>사람이 정한 값이다</b> — 4.
	 *
	 * <h2>⚠ 「적히는 값」이다 — 피해원은 {@code lightningBolt()}</h2>
	 *
	 * <p>이 판의 피해 값은 전부 <b>무장 기준</b>으로 읽는다(문서 5장). 같은 꼴의 선례가 이미 있다 —
	 * 안전지대 밖의 <b>「초당 8」</b>({@code DragonLastStand.OUTSIDE_ZONE_DAMAGE_PER_SECOND})이
	 * {@code lightningBolt()} 로 넣는 적히는 값이고 무장 기준 초당 0.81 이다. 여기도 같은 표기라
	 * <b>4 → 무장 기준 한 대 0.35</b>(방어도 19 → ×0.24, 보호 IV ×0.36) 다.
	 *
	 * <table border="1">
	 *   <caption>피해원 후보와 버린 까닭</caption>
	 *   <tr><th>피해원</th><th>버린 까닭</th></tr>
	 *   <tr><td><b>{@code lightningBolt()}</b></td><td>← 고른 것. 하드 곱 없음 · 방어도 들음. 안전지대
	 *       「초당 8」과 같은 꼴</td></tr>
	 *   <tr><td>{@code inFire()}</td><td>⚠ {@code #is_fire} 라 <b>화염 저항 하나로 0</b>이 된다(26.3
	 *       {@code LivingEntity.hurtServer} 첫머리가 {@code IS_FIRE && FIRE_RESISTANCE} 를 본다).
	 *       게다가 상태이상은 {@code EffectSync} 가 팀 전원에게 퍼뜨리므로 <b>한 사람의 물약 한 병이
	 *       넷의 결계를 지운다</b></td></tr>
	 *   <tr><td>{@code onFire()}</td><td>{@code #bypasses_armor} 다 — 무장 기준의 셈과 성질이 다르다
	 *       (부채꼴이 {@code magic} 을 버린 까닭과 같다)</td></tr>
	 *   <tr><td>{@code explosion(null, null)}</td><td>하드 ×1.5 가 걸려 「4」가 6 으로 읽힌다 — 사람이
	 *       적은 수와 다르다</td></tr>
	 * </table>
	 *
	 * <h2>⚠⚠ 흡입·균열의 피해원 규칙을 깨지 않는다</h2>
	 *
	 * <p>문서 6장의 「흡입과 균열이 <b>서로 다른</b> 피해원이어야 정한 값(6.9 / 6.8)이 나온다」는 <b>각
	 * 카드의 값이 제 피해원으로 셈해져야 한다</b>는 말이다. 결계는 흡입과 같은 {@code lightningBolt()}
	 * 를 쓰지만 <b>흡입의 터짐(35)도 균열(23)도 피해원이 그대로</b>이고, 결계의 4 는 그 둘과 따로
	 * 셈한다. 바꾼 것은 결계를 하나 <b>더한</b> 것뿐이다.
	 *
	 * <h2>⚠ 인원수로 곱해진다 — 안전지대와 다르다</h2>
	 *
	 * <p>안전지대는 「넷이 다 밖이어도 초당 8 하나」로 <b>한 사람에게만</b> 넣는다. 결계는 사람 말이
	 * <b>「드래곤 주위에 있으면 계속 딜 맞게」</b>라 <b>원 안의 사람마다</b> 넣는다 — 넷이 5초 내내
	 * 머물면 {@code 0.35 × 5 × 4 = 6.9} 가 팀에 들어간다(공유 체력은 팀원별 피해를 합산한다,
	 * {@code StatMirror.fold}). 그 위에 터짐이 사람마다 6.93 이다. 「한 사람에게만」으로 바꾸려면
	 * {@code DragonLastStandZone.punish} 의 「가장 멀리 나간 사람」 꼴을 가져오면 된다.
	 */
	static final float SUCK_FIRE_DAMAGE = 4.0F;

	/** 불 결계가 피해를 넣는 주기(틱). 1초. 사람이 「초당」이라고 했다. */
	static final int SUCK_FIRE_PERIOD_TICKS = 20;

	/**
	 * ⚠⚠ <b>바닐라 피격 무적의 「차액만」 구간</b>(틱). 결계가 터짐 직전 이만큼 안에서는 때리지 않는다.
	 *
	 * <h2>이것이 없으면 터짐 35 가 깎이거나 사라진다</h2>
	 *
	 * <p>26.3 {@code LivingEntity.hurtServer} 를 바이트코드로 읽었다(필드 이름이
	 * {@code invulnerableTime} 에서 <b>{@code damageCooldownTime}</b> 으로 바뀌어 있다).
	 *
	 * <pre>{@code
	 * if (damageCooldownTime > 10.0F && !source.is(BYPASSES_COOLDOWN)) {
	 *     if (amount <= lastHurt) return false;          // 막힌다
	 *     actuallyHurt(amount - lastHurt);               // 차액만 들어간다
	 *     lastHurt = amount;
	 * } else {
	 *     lastHurt = amount; damageCooldownTime = 20; actuallyHurt(amount);
	 * }
	 * }</pre>
	 *
	 * <p>{@code ServerPlayer.tick} 이 그 값을 틱마다 1 씩 줄인다. 곧 <b>결계 4 를 맞은 뒤 10틱 안에
	 * 터지면 35 가 아니라 31 만</b> 들어가고, 결계를 바꿔 35 이상을 넣는 날에는 터짐이 <b>통째로
	 * 막힌다</b>. ⚠ {@code #bypasses_cooldown} 태그는 26.3 바닐라에서 <b>비어 있어</b>(데이터 파일에서
	 * 확인했다) 피해원 고르기로는 피할 수 없다 — 그래서 <b>시각</b>으로 피한다({@link #suckFireDue}).
	 *
	 * <p>10 은 바닐라의 수({@code 10.0F})이고 우리가 고른 수가 아니다. {@code 20} 으로 세우고 그 반이
	 * 넘는 동안만 막으므로 「마지막 결계와 터짐 사이가 10틱보다 길다」면 터짐이 언제나 온전하다.
	 */
	static final int HURT_COOLDOWN_GUARD_TICKS = 10;

	/**
	 * 불꽃 벽을 <b>몇 틱에 나눠</b> 세울지. <b>3</b> 이다.
	 *
	 * <p>26.3 {@code RisingParticle} 이 불꽃 수명을 {@code (int)(8.0 / (굴림 × 0.8 + 0.2))} 곧
	 * <b>8~40틱</b>으로 잡는다(바이트코드로 확인했다 — {@code burnCone} 이 적어 둔 그 값이다). 8 보다 작은
	 * 폭이면 어떤 자리도 꺼지지 않는다. 6 이 아니라 3 인 것은 <b>밀도</b>다 — 한 자리에 3틱마다 새
	 * 불꽃이 올라 가장 짧게 사는 불꽃으로도 <b>세 개가 겹쳐</b> 서 있고, 그래야 「점선」이 아니라
	 * 「벽」으로 읽힌다. 사람이 <b>「알기 쉽게」</b>라고 했다.
	 */
	static final int SUCK_FIRE_STRIDE = 3;

	/**
	 * 불꽃을 위로 쏘는 세기(칸/틱). 그 불꽃이 올라가는 높이가 <b>0.7~2.0칸</b>이다({@link #flameRise}).
	 *
	 * <p>26.3 {@code RisingParticle} 은 받은 속도에 바닐라가 굴린 값의 1% 만 더하고 마찰 {@code 0.96}
	 * 으로만 줄인다(중력이 없다 — {@code FlameParticle.move} 가 충돌도 안 본다). 그래서 이 값이 거의
	 * 그대로 위로 가는 속도다. 0.1 이면 가장 짧게 사는 불꽃이 무릎(0.7), 가장 오래 사는 것이 사람 키
	 * (2.0)에서 사그라든다 — <b>낮은 벽</b>이다. 더 높이면 같은 원의 빨간 고리·흰 기둥을 가린다.
	 */
	static final double SUCK_FIRE_RISE = 0.1;

	/** {@code RisingParticle} 의 마찰. 26.3 생성자가 {@code friction = 0.96F} 를 넣는다. */
	private static final double FLAME_FRICTION = 0.96;

	// ------------------------------------------------------------------ ④ 십자 균열

	/**
	 * 터지는 <b>회차</b> 수. <b>사람이 정한 값이다</b> — 처음 「3번 반복하는 패턴」에서 2026-10-04 에
	 * <b>둘</b>로 바뀌었다.
	 *
	 * <p>사람 말: <b>「2페이지때 십자 나오고 다른 각도로 십자 나오고 이런 걸 차라리 그 두 십자를
	 * 한번에 같이 발동시켜. 그리고 다음에도 두 개 같이 터지고」</b>. 곧 <b>회차마다 십자 둘</b>
	 * ({@link #CROSSES_PER_ROUND})이고 회차가 둘이다 — 세 번 긋던 각도 셋(0 · 45 · 22.5)에 67.5 를 더해
	 * 넷을 두 회차로 묶은 꼴이다({@link #CROSS_ANGLES}).
	 *
	 * <p>⚠ <b>「세 대에 전멸」이 이 패턴 혼자서는 이제 안 된다.</b> 한 회차에 한 사람은 한 번만
	 * 맞으므로({@link #fireRound}) 한 판에 최대 <b>두 대</b> — 무장 기준 {@code 6.77 × 2 = 13.54} 다.
	 * 전에는 세 번을 다 맞으면 20.31 로 전멸이었다.
	 */
	static final int CROSS_ROUNDS = 2;
	/**
	 * 한 회차에 <b>동시에</b> 터지는 십자 수. 2 — 사람이 「그 두 십자를 한번에」라고 했다.
	 *
	 * <p>한 회차의 십자들은 <b>고르게 벌어져 있어야</b> 한다({@code 90 ÷ 2 = 45도}). 그래야 팔 여덟이
	 * 45도마다 하나씩 서고, 바닥 면의 가운데 팔각형이 팔 폭과 맞물린다
	 * ({@link DragonLastStandCrossPanel#hubApothem}). 시험이 그 간격을 붙든다.
	 */
	static final int CROSSES_PER_ROUND = 2;
	/** 첫 십자의 예고(틱). 3초. <b>사람이 정한 값이다.</b> */
	static final int CROSS_FIRST_WARN_TICKS = 60;
	/**
	 * 터진 뒤 다음 십자까지(틱). 1.5초. <b>사람이 정한 값이다.</b>
	 *
	 * <p>그러니 둘째 회차의 예고는 <b>1.5초</b>다(회차가 셋이던 때는 둘째·셋째). 그것이 우연히 맞는
	 * 값이 아니다 —
	 * {@code TrialWarning.TICKS_SIDESTEP} 이 정확히 30틱이고 「<b>옆으로 비킬</b> 시간」이라고
	 * 적혀 있다. 이 패턴이 요구하는 것이 딱 그것이다(사람 말: <b>「단순 피하기」</b>) — 첫 십자는
	 * 어느 사분면에 설지 고르는 3초가 필요하지만, 그 뒤로는 각도가 도는 것을 이미 봤으므로 옆으로
	 * 한 걸음이면 된다.
	 */
	static final int CROSS_GAP_TICKS = 30;
	/** 마지막 십자가 터진 뒤 파티클이 남는 시간(틱). <b>잔류가 아니다.</b> */
	static final int CROSS_AFTERGLOW_TICKS = 10;

	/** 선의 폭(칸). <b>사람이 정한 값이다.</b> */
	static final double CROSS_WIDTH = 3.0;
	/** 그 반폭. 중심선에서 이만큼까지가 안이다. */
	static final double CROSS_HALF_WIDTH = CROSS_WIDTH / 2.0;

	/**
	 * 선이 뻗는 거리(칸). <b>사람이 정하지 않았다</b> — 「아레나를 가로지르는」의 「아레나」다.
	 *
	 * <p>{@code TrialRisks.ARENA_RADIUS}(40)를 쓴다. 새 숫자를 만들지 않았고, 이 저장소가 「섬
	 * 반경」으로 쓰는 값이라 그 밖은 허공이다 — 허공에는 {@link #dot} 이 점을 찍지 않고
	 * {@link DragonLastStandCrossPanel} 이 판을 세우지 않는다.
	 *
	 * <p>{@code DragonLastStandZone.START_RADIUS}(42, 기둥 줄)를 쓰지 않은 까닭은 그쪽이
	 * <b>「처음에는 아무도 밖이 아니어야」</b>를 위한 값이라 <b>섬 밖 허공까지 걸친다</b>는 것을
	 * 그 상수가 스스로 적어 두었기 때문이다.
	 */
	static final double CROSS_REACH = TrialRisks.ARENA_RADIUS;

	/**
	 * ⚠ 회차마다 <b>동시에</b> 터지는 십자들의 기준 각도(도). <b>{@code {0, 45} → {22.5, 67.5}}</b> 다.
	 *
	 * <h2>어디서 왔는가</h2>
	 *
	 * <p>처음에는 세 번을 차례로 그었다 — {@code +} → {@code ×} → <b>22.5도 어긋난 십자</b>. 사람이 셋
	 * 중에 그것을 골랐고 근거는 십자가 <b>90도 대칭</b>이라 「대각선으로 두 번」이면 셋째가 첫째와 같은
	 * 모양이 된다는 것이었다.
	 *
	 * <p>2026-10-04 에 사람이 <b>「그 두 십자를 한번에 같이 발동시켜. 그리고 다음에도 두 개 같이
	 * 터지고」</b>라고 해서 앞의 둘(0 · 45)을 <b>첫 회차에 함께</b>, 22.5 와 그 짝 67.5 를 <b>둘째 회차에
	 * 함께</b> 터뜨린다. 둘째 회차가 첫 회차의 <b>정확히 사이</b>(22.5도 어긋남)라 「다른 각도로」가
	 * 그대로 남고, 첫 회차의 안전한 쐐기 한가운데가 둘째 회차의 선이 된다 — 첫 회차를 피한 자리에
	 * 그대로 서 있으면 둘째에 맞는다.
	 *
	 * <p>⚠ <b>한 회차 안의 십자는 45도 간격이어야 한다</b>({@code 90 ÷ CROSSES_PER_ROUND}). 바닥 면이
	 * 팔 여덟을 45도마다 깔고 가운데를 팔각형으로 맞물리므로 간격이 어긋나면 면이 판정과 갈린다.
	 * 시험이 그 간격과 「넷이 다 다른 모양」을 붙든다.
	 */
	static final double[][] CROSS_ANGLES = {{0.0, 45.0}, {22.5, 67.5}};

	/** 터지는 틱에 갈라짐을 그리는 간격(칸). 표식이 아니라 연출이라 성기다. */
	private static final double CROSS_FLASH_GAP = 2.0;

	/** 터지는 틱에 갈라짐에서 <b>솟는</b> 기둥의 간격(칸). 연출이라 바닥 선보다 한참 성기다. */
	private static final double CROSS_BURST_GAP = 4.0;
	/**
	 * 그 기둥을 쏘는 세기. 「착지 충격」의 벽({@code EDGE_RISE_SPEED} = 2.0)보다 <b>두 배</b>다.
	 *
	 * <p>{@code TrialLandingShock.riseHeight} 와 같은 식으로 재면 수명이 가장 짧은 점도 <b>3.9칸</b>
	 * 올라간다 — 사람 키의 두 배가 넘는다. 「착지 충격」이 벽을 사람 키에서 멈춘 까닭은 <b>높으면
	 * 다른 카드의 고리를 가린다</b>는 것인데, 이것은 <b>터지는 그 한 틱</b>뿐이고 그 틱에 가릴 만한
	 * 것은 자기 자신밖에 없다.
	 */
	private static final double CROSS_BURST_RISE = 4.0;

	/**
	 * 균열이 번지는 소리를 되풀이하는 간격(틱). 0.5초.
	 *
	 * <h2>이 패턴에만 제 소리가 없었다</h2>
	 *
	 * <p>사람 말이 <b>「각각 패턴마다 소리가 구분되엇으면해」</b>인데, 예고 중에 나는 것이 공용 층
	 * 소리뿐이었다 — 곧 <b>「무엇인가 온다」는 들렸지만 「십자가 온다」는 안 들렸다.</b> 부채꼴은
	 * {@code GHAST_WARN} 을, 흡입은 {@code BREEZE_INHALE} 을 이미 얹고 있었고 여기만 비어 있었다.
	 *
	 * <p>0.5초인 것은 예고가 <b>3초 · 1.5초</b>(회차가 셋이던 때는 3 · 1.5 · 1.5)라는 것이다. 1초로
	 * 하면 둘째 예고에 <b>두 번</b>밖에 울리지 않아 「되풀이된다」가 안 들린다. 0.5초면 6 · 3 번이다.
	 *
	 * <p>⚠ 2026-10-04 에 예고 선(먼지·흰 기둥)을 걷고 바닥 면으로 바꾼 뒤로 <b>예고 중의 신호는 면 ·
	 * 이 균열음 · 층 소리 셋</b>이다. 면은 「어디」만 말하고 「언제」는 이 소리가 말하므로 지우지 말 것.
	 */
	static final int CROSS_CRACK_TICKS = 10;
	/** 균열음의 첫 음높이. */
	private static final float CROSS_CRACK_PITCH_LOW = 0.7F;
	/** 균열음의 마지막 음높이. 「지금 터진다」. */
	private static final float CROSS_CRACK_PITCH_HIGH = 1.5F;

	/**
	 * 사람 중력(칸/틱²). 26.3 {@code Attributes.GRAVITY} 기본값이
	 * {@code RangedAttribute("attribute.name.gravity", 0.08, -1.0, 1.0)} 인 것을 바이트코드로
	 * 확인했다.
	 *
	 * <p>⚠ {@code TrialRisks} 에 같은 값이 {@code GRAVITY_PER_TICK} 으로 또 있다. 그쪽이
	 * {@code private} 이고 {@code TrialRisks} 는 <b>읽기만 하기로</b> 정해져 있어 여기 한 벌을 더
	 * 적었다 — 열어 달라고 고치지 않은 것이 의도다.
	 */
	static final double LIFT_GRAVITY = 0.08;
	/**
	 * <b>세로</b> 감쇠. 26.3 {@code LivingEntity.travelInAir} 가 매 틱 끝에 세로 속도에
	 * {@code 0.98} 을 곱하는 것을 바이트코드로 확인했다({@code getAirDrag} 도 같은 값을 돌려준다).
	 *
	 * <p>⚠ <b>수평의 {@code 0.91}({@code TrialEnderStorm.AIR_DRAG})과 다른 값이다.</b> 수평은
	 * 거기에 블록 마찰까지 곱해 {@link #GROUND_DRAG}(0.546)가 되는데, 세로는 마찰이 끼지 않아
	 * 바닥이든 공중이든 늘 0.98 이다. 그 차이가 「띄우면 훨씬 멀리 간다」의 근거다 — 세로는
	 * 감쇠가 거의 없어 오래 떠 있고, 떠 있는 동안 수평은 0.91 로만 줄어든다.
	 */
	static final double LIFT_DRAG = 0.98;

	/**
	 * ⚠⚠ 십자에 맞은 사람이 <b>솟아오르는 높이</b>(칸). <b>사람이 명문 규칙을 알고 뒤집은
	 * 값이다.</b>
	 *
	 * <h2>사람 말</h2>
	 *
	 * <p><b>「30프로 2페이지때 십자가 공격받앗을때도 한 6칸 띄워버려 점프하게」</b> → 2026-10-04
	 * <b>「십자 맞았을 때 하늘로 지금보다 2배는 더 날려버려」</b>. 6 → <b>12</b> 다. 그 낙하가 맨몸이면
	 * 피해 9({@code ceil(12 − 3)})라는 것을 사람에게 알렸고, 그 피해는 {@link #liftCross} 가 이 띄움
	 * 몫만 면제한다.
	 *
	 * <h2>⚠ 「세로로 띄우지 않습니다」를 어기는 자리다</h2>
	 *
	 * <p>이 전투의 설계 원칙에 <b>「세로로 띄우지 않습니다 — 띄우면 마찰이 안 먹어 훨씬 멀리
	 * 갑니다」</b>가 적혀 있다({@link #AIRBORNE_PUSH_SCALE} 가 그 문장의 근거를 수로 들고 있다).
	 * 사람이 그것을 알고 띄우라고 했으므로 <b>띄우되 그 규칙이 막으려던 사고를 따로 막는다.</b>
	 *
	 * <ol>
	 *   <li><b>가로를 한 톨도 더하지 않는다</b>({@link #liftCross}). 섞으면 「마찰이 안 먹는 공중
	 *       이동」이 되어 섬 밖으로 날아간다 — 그 규칙의 이유가 정확히 그것이다</li>
	 *   <li><b>이 패턴은 밀지 않는다.</b> 사람이 <b>「단순 피하기」</b>라고 못박은 카드라 수평
	 *       성분이 애초에 0 이고, 그래서 세로만 주는 것이 가능하다. 미는 패턴(날개 퍼덕이기)에
	 *       같은 짓을 하면 그날로 낙사 장치다</li>
	 *   <li><b>띄워진 사람이 다음 번치의 입력이 된다.</b> 1.75초(12칸 · 35틱) 동안 공중이라 그 사이에
	 *       날개 퍼덕이기가 오면 공중에서 맞는데, {@link #AIRBORNE_PUSH_SCALE} 와 천장 둘이 그대로
	 *       걸려 있어 그때도 섬 안이다 — 시험이 그 경우를 통째로 굴린다. ⚠ 높이를 두 배로 해도 이
	 *       보장은 <b>공중 시간과 무관하다</b>: 비율은 「공중에 있는가」만 묻고 천장 둘은 「어디까지」만
	 *       자르므로 35틱 내내 공중이어도 번치마다 같은 셈이다. 게다가 실제로는 그 겹침이 안 생긴다 —
	 *       마지막 회차 뒤 여운 10틱 + 쉬는 시간 최소 40틱 = <b>50틱이 35틱보다 길다</b>(패턴은 한 번에
	 *       하나뿐이다)</li>
	 * </ol>
	 *
	 * <h2>⚠ 높이가 두 배라 <b>제 발로 가는 거리</b>가 늘었다 (2026-10-04)</h2>
	 *
	 * <p>띄우기는 가로를 더하지 않지만 <b>공중에 있는 시간</b>이 25틱 → 35틱이다. 공중에서도 사람은
	 * 제 입력으로 움직이므로(달리는 중이면 틱당 0.026 가속 · 감쇠 0.91) <b>달리던 채로 키를 안 놓으면
	 * 6칸 때 6.1칸 → 지금 9.0칸</b>을 간다(손을 놓으면 1.6 → 1.7칸). 섬 가장자리에서 바깥으로 달리다
	 * 맞은 사람은 그만큼 더 나간다 — 이것은 이 카드가 미는 거리가 아니라 <b>사람이 스스로 가는
	 * 거리</b>라 천장 둘을 걸지 않았고, {@code TrialLandingShock.EDGE_MARGIN}(6칸, 스프린트 점프 한 번)
	 * 보다 길어졌다는 것만 적어 둔다. 사람이 「2배」를 알고 정한 값이다.
	 *
	 * <h2>12 는 <b>도달 높이</b>다 — 처음 속도가 아니다</h2>
	 *
	 * <p>{@link #CROSS_LIFT_SPEED} 가 이 높이에서 역산된다. 바닐라 점프(처음 0.42 → <b>1.2522칸</b>)
	 * 의 <b>9.6배</b>이고, 올라가는 데 16틱 · 되돌아오는 데 35틱이라 <b>1.75초쯤 공중에 있다.</b>
	 * (6칸이던 때는 4.8배 · 12틱 · 25틱 · 1.25초.)
	 */
	static final double CROSS_LIFT_BLOCKS = 12.0;
	/**
	 * ⚠ 그 높이에 닿는 <b>처음 세로 속도</b>(칸/틱). <b>1.49053</b> 이다(6칸이던 때 1.00746).
	 *
	 * <p>{@link #liftSpeed} 가 {@link #liftApex} 를 이분법으로 뒤집어 낸다. 값을 손으로 적지 않는
	 * 까닭은 <b>사람이 말한 것이 「칸」이고 속도는 거기서 나오는 것</b>이기 때문이다 — 속도를 적어
	 * 두면 중력이나 감쇠가 바뀌는 판에서 높이가 조용히 달라진다. 「2배」가 높이의 2배이지 속도의 2배가
	 * 아닌 것도 그래서 저절로 맞는다(속도는 1.48배다).
	 *
	 * <p>⚠ <b>이 값이 {@link TrialVelocity#syncedVertical} 의 두 번째 천장이다.</b> 「우리가 일부러 싣는
	 * 가장 큰 세로」라서이고, 12칸이 되며 그 천장도 1.007 → 1.491 로 올랐다 — 거짓 보고용 보험이
	 * 그만큼 헐거워졌다(낙사를 막는 것은 여전히 「실제로 올라간 만큼」쪽이다).
	 *
	 * <p>⚠ <b>{@code TrialRisks.launchVelocity} 를 쓰지 않았다.</b> 그쪽은 공기 저항을 뺀 근사
	 * ({@code √(2gh)})라 12칸을 넣으면 1.3856 이 나오고 실제 도달이 <b>10.55칸</b>이다(1.45칸 ·
	 * 12% 모자람, 6칸 때는 5%). 「자리 폭격」은 그 모자람을 <b>알고</b> 받아들였는데 여기서는 사람이
	 * 높이를 수로 말했으므로 그 근사를 쓸 수 없다. ⚠ 그쪽을 고치지 않은 것도 의도다 —
	 * {@code TrialRisks} 는 읽기만 한다.
	 */
	static final double CROSS_LIFT_SPEED = liftSpeed(CROSS_LIFT_BLOCKS);

	// ------------------------------------------------------------------ ⑤ 상시 번개 (패턴이 아니다)

	/**
	 * 한 볼리의 번개 개수. <b>사람이 5 → 10 으로 올렸다.</b>
	 *
	 * <p>「낙뢰」({@code sharedfate:lightning_storm})가 <b>한 번에 열 곳</b>이라 이제 개수까지 같다.
	 */
	static final int LIGHTNING_COUNT = 10;
	/**
	 * 한 곳의 반경(칸). <b>사람이 정한 값이다</b> — 「낙뢰」의 3칸보다 15% 작다.
	 *
	 * <p>{@code TrialCatalog} 의 {@code sharedfate:lightning_storm} 이 3.0 을 들고 있고
	 * {@code 3.0 × 0.85 = 2.55} 다. {@code DragonLastStandPatternsTest} 가 그 관계를 붙든다.
	 */
	static final double LIGHTNING_RADIUS = 2.55;
	/**
	 * 자리를 굴리는 반경(칸). <b>9.45 이고 새 숫자가 아니다</b> —
	 * {@code 마지막 안전지대 12 − 반경 2.55} 다.
	 *
	 * <p>이렇게 적으면 <b>「고리가 지대 밖으로 삐져나가지 않는다」가 구조적으로 참</b>이다. 지대의
	 * 마지막 크기를 누가 고쳐도 여기가 따라간다. 지대는 <b>원</b>이라 반경 12 가 곧 지대 끝이다
	 * ({@link DragonLastStandZone} 의 설명). 보더 시절에는 정사각형이라 모서리가 중앙에서 17칸이었고
	 * 그때도 <b>내접원(12)</b>으로 쟀으므로 이 셈은 그대로 맞다.
	 *
	 * <p>전에는 「가운데 하나 + 90도씩 벌린 넷」이라는 <b>굳은 배치</b>였고 그것이 겹침 없음을
	 * 증명했다. 열 곳이 되면서 그 증명이 불가능해졌다 — 아래 「겹침을 허용한다」를 볼 것.
	 */
	static final double LIGHTNING_FIELD_RADIUS =
			DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1] - LIGHTNING_RADIUS;
	/** 예고(틱). 3초. {@code TrialWarning.TICKS_SIDESTEP}(30틱, 옆으로 비킬 시간)의 두 배다. */
	static final int LIGHTNING_WARN_TICKS = 60;
	/**
	 * 내리친 뒤 번개 엔티티가 스스로 그리는 시간(틱). 그 동안 바닥에는 아무 표식도 없다.
	 *
	 * <p>⚠ <b>실행 코드가 이 값을 읽지 않는다.</b> 번개 엔티티는 우리가 세지 않아도 제 수명을
	 * 다하고 사라지므로 잴 것이 없다. 남겨 둔 이유는 <b>{@link #LIGHTNING_PERIOD_TICKS} 가
	 * 「바닥이 깨끗한 틈」을 셀 때 쓰는 유일한 숫자</b>이기 때문이고, {@code 예고 60 + 여운 10 =
	 * 70틱} 이 그 셈이다. 시험이 {@code 예고 + 여운 < 주기} 를 붙들고 있다 — 지우면 「번개가
	 * 끊기지 않아 부채꼴 테두리가 묻힌다」를 재는 자가 사라진다.
	 *
	 * <p>전에는 패턴 길이({@code LIGHTNING_FIVE.durationTicks()})의 뒷부분이었다. 번개가 패턴
	 * 풀에서 빠지면서 그 쓰임이 없어졌다.
	 */
	static final int LIGHTNING_AFTER_TICKS = 10;
	/**
	 * 볼리 사이 주기(틱). <b>7.8초 — 「낙뢰」의 6초와 일부러 갈라졌다.</b>
	 *
	 * <h2>왜 156 인가 — 사람이 6초를 30% 내리라고 했다</h2>
	 *
	 * <p>사람 말: <b>「번개 주기를 30프로 내리고」</b>. 「주기를 내린다」가 두 뜻으로 읽히는데
	 * 확인한 답이 <b>「덜 자주」</b>였다 — 곧 주기를 1.3배로 늘린다. 120 × 1.3 = <b>156</b> 이고
	 * 6초 → <b>7.8초</b>다.
	 *
	 * <h2>⚠⚠ 왜 전에는 120 이었나 — 그 근거가 이제 깨졌다</h2>
	 *
	 * <p>사람이 정한 것은 「패턴이 아니라 배경」과 「열 곳」뿐이었고, 주기는 <b>새로 만들지
	 * 않았다</b> — 「낙뢰」가 이미 <b>주기 120틱 · 한 번에 열 곳 · 피해 35</b> 이고, 개수가 10 이
	 * 된 그때 이 배경은 반경(2.55 대 3)과 놓는 자리(드래곤 주변 대 아레나 전체)만 다른 <b>같은
	 * 카드</b>였다. 실제로 시험 서버에서 맞아 보고 정해진 값이 그쪽에 있었으므로 그것을 그대로
	 * 썼다.
	 *
	 * <p><b>이제 둘은 다른 값이다.</b> 사람이 내리라고 한 것은 <b>최후의 저항 쪽뿐</b>이고
	 * 「낙뢰」 카드({@code sharedfate:lightning_storm})는 <b>6초 그대로</b> 둔다. 한쪽을 고칠 때
	 * 다른 쪽을 따라 고치지 말 것 — 「낙뢰」를 156 으로 올리면 사람이 건드리라고 하지 않은 카드가
	 * 묶여서 움직이고, 여기를 120 으로 되돌리면 사람이 내리라고 한 것이 조용히 사라진다.
	 * {@code DragonLastStandPatternsTest} 가 <b>둘이 같아지면 터지게</b> 뒤집어 두었다.
	 * <b>개수(열 곳)는 여전히 같은 값</b>이다 — 바꾸라고 한 것은 주기 하나뿐이다.
	 *
	 * <p>값으로도 맞는다.
	 *
	 * <ul>
	 *   <li><b>「쉬는 틈 없이 계속 돈다」는 아직 참이다</b> — 한 볼리가 예고 60 + 여운 10 =
	 *       <b>70틱</b>을 쓰므로 156틱 주기에서 <b>바닥이 깨끗한 시간이 86틱(4.3초)</b>이다. 번개가
	 *       보이는 시간이 주기의 45% 라 여전히 배경으로 읽힌다</li>
	 *   <li><b>부채꼴 테두리를 볼 틈이 그만큼 넓어진다</b> — 그 86틱이 <b>날개 고리와 부채꼴
	 *       테두리만 떠 있는 시간</b>이고, 사람이 내리라고 한 것이 이 틈이다. 70틱으로 붙여 돌리면
	 *       노란 고리가 끊기지 않아 빨간 테두리가 그 위에 묻힌다</li>
	 *   <li><b>그래도 「가끔 오는 사건」은 아니다</b> — 그 86틱이 <b>가장 짧은 패턴(날개 퍼덕이기
	 *       100틱)보다 짧다.</b> 곧 번개를 한 번도 안 보고 지나가는 패턴이 없다. 주기를 더 늘릴
	 *       사람은 그 100틱이 천장임을 알고 늘릴 것 — 시험이 그 선을 패턴 길이에서 직접 뽑는다</li>
	 *   <li><b>연달아 맞아도 세 발까지 시간이 있다</b> — 무장 기준 한 발 6.93 이라 세 발이 20.79 로
	 *       전멸인데, 세 발 사이가 <b>312틱(15.6초)</b> 이다. 그 사이에 자연 회복도 돌고 무엇보다
	 *       세 번 다 3초 예고를 무시해야 한다</li>
	 * </ul>
	 *
	 * <p>115초 페이즈에 <b>14 볼리</b>가 돈다({@code (2300 − 60) ÷ 156}).
	 */
	static final int LIGHTNING_PERIOD_TICKS = 156;
	/**
	 * 노란 고리 한 바퀴를 <b>몇 틱에 나눠</b> 그릴지. {@code TrialEndRain} 의 상한을 그대로 쓴다.
	 *
	 * <h2>이 값이 없으면 예산이 그 자리에서 넘친다</h2>
	 *
	 * <p>반경 2.55 의 고리는 {@code TrialWarning.ringPoints} 가 하한 40점을 주므로 열 곳이면
	 * <b>한 틱에 400점</b>이다. 번개가 패턴이었을 때는 그것으로 끝이었지만(패턴은 하나만 돈다)
	 * 이제 <b>부채꼴 예고(202점)와 같은 틱에 돈다</b> — 합이 602점이라 예산 440 을 넘는다.
	 *
	 * <p>{@code TrialWarning.markGround} 의 나눠 그리기로 {@code ceil(40 ÷ 6) = 7} 점씩 여섯 틱에
	 * 채우면 <b>70점</b>이다. 6 인 까닭은 <b>먼지 파티클 수명이 최소 8틱</b>이라는 것이고
	 * ({@code TrialWarning.markGround} 의 「stride 는 8보다 작아야 한다」), 「종말의 비」가 같은
	 * 근거로 같은 값을 들고 있으므로 <b>그쪽에서 가져온다</b> — 숫자를 여기 따로 적으면 파티클을
	 * 바꿀 때 한쪽만 따라간다.
	 *
	 * <p>대가는 <b>처음 여섯 틱 동안 고리가 성기다</b>는 것이다. 예고가 60틱이라 묻힌다.
	 */
	static final int LIGHTNING_MARK_STRIDE = TrialEndRain.MARK_MAX_STRIDE;

	/**
	 * ⚠⚠ 번개에 맞은 사람의 <b>이동 속도를 직접 깎는 수정자 이름.</b> 상태이상이 아니다.
	 *
	 * <h2>사람 말</h2>
	 *
	 * <p>처음 말은 <b>「2페이지 번개에 맞으면 그 플레이어만 구속3 1초 걸리게」</b>였고, 그것이
	 * 이 저장소에서 <b>불가능하다</b>는 것을 보고하자 사람이 방법을 정했다 —
	 * <b>「그 플레이어만 구속을 구속3급으로 이속을 감소시키는쪽으로가면 되지않나? 버프효과로
	 * 주는게 아니라」</b>
	 *
	 * <h2>⚠⚠ 왜 {@code MobEffects.SLOWNESS} 를 못 쓰는가 — <b>상태이상은 팀에 퍼진다</b></h2>
	 *
	 * <p>{@code EffectSync} 가 {@code ServerMobEffectEvents.AFTER_ADD} 에 붙어 있어, 한 사람에게
	 * 붙은 상태이상을 그 틱에 <b>팀 전원에게 다시 붙인다.</b> 건너뛰는 문은 그 파일의
	 * {@code private static boolean propagating} 하나뿐이고 밖에서 켤 길이 없다 — 곧
	 * <b>{@code player.addEffect} 를 부르는 순간 「그 사람만」이 거짓이 된다.</b> 「엔더 파동」의
	 * 구속 III 가 팀 공유인 것이 그 증거이고, 그쪽은 <b>사람이 의도한 것</b>이라 고칠 수도 없다
	 * ({@code TrialEnderPulse.root} 에 그렇게 적혀 있다).
	 *
	 * <p>⚠ <b>그래서 「엔더 파동의 구속은 팀 공유인데 번개의 구속은 혼자」가 된다.</b> 버그가
	 * 아니다. 사람이 위의 두 문장으로 그렇게 지시했고, 둘이 다른 기계를 쓰기 때문에 그렇게 될 수
	 * 있었다 — 같은 기계로는 둘 중 하나밖에 못 한다. <b>한쪽을 다른 쪽에 맞추려는 사람은 여기서
	 * 멈출 것.</b>
	 *
	 * <h2>본보기는 증강 쪽이다</h2>
	 *
	 * <p>{@code SneakSpeedEffect} 가 <b>같은 기계</b>를 쓴다 — {@code Attributes.MOVEMENT_SPEED} 에
	 * {@code ADD_MULTIPLIED_TOTAL} 수정자를 <b>그 사람에게만</b> 걸고, 조건이 풀리면 이름으로
	 * 걷어낸다. 상태이상이 아니므로 {@code EffectSync} 를 아예 지나지 않는다. 그 파일을 읽고 같은
	 * 꼴로 적었다({@code removeModifier} 먼저 → {@code addTransientModifier}).
	 *
	 * <p>⚠ <b>이름을 바닐라({@code minecraft:effect.slowness})로 하면 안 된다.</b>
	 * {@code MobEffect.removeAttributeModifiers} 가 <b>이름으로</b> 걷으므로, 진짜 구속이 한 번
	 * 붙었다 떨어지는 것만으로 우리 몫이 함께 사라지고 {@code addAttributeModifiers} 는
	 * 거꾸로 우리 것을 덮어쓴다.
	 *
	 * <h2>대가 — <b>아이콘이 없다</b></h2>
	 *
	 * <p>상태이상이 아니므로 화면에 아이콘이 뜨지 않는다. 「다시 선 쇠창살」(그때 이름은 「쇠창살과
	 * 무딘 곡괭이」였다)의 <b>채굴 15% 감소</b>가 같은 대가를 치렀던 자리다 — ⚠ <b>그 감소는
	 * 2026-10-01 에 걷혔고</b> 그 카드는 이제 쇠창살만 다시 세우므로, 「아이콘 없는 감소」를 들고
	 * 있는 것은 <b>이 파일이 유일하다.</b> 그래서 {@link #LIGHTNING_SLOW_MARK_POINTS} 가 되먹임을
	 * 하나 둔다.
	 */
	private static final Identifier LIGHTNING_SLOW_MODIFIER_ID =
			SharedFateMod.id("last_stand_lightning_slow");
	/**
	 * 구속 <b>III</b> 의 증폭값. 사람이 「구속3급」이라고 했고 증폭은 0 부터 세므로 <b>2</b> 다.
	 */
	static final int LIGHTNING_SLOW_AMPLIFIER = 2;
	/**
	 * 구속 <b>한 급</b>이 이동 속도에 거는 몫. <b>바닐라에서 그대로 베낀 수다.</b>
	 *
	 * <p>26.3 {@code MobEffects} 의 클래스 초기화식을 {@code javap -c} 로 읽은 것이다.
	 *
	 * <pre>{@code
	 * ldc           // String slowness
	 * getstatic     // Attributes.MOVEMENT_SPEED
	 * ldc           // String effect.slowness
	 * ldc2_w        // double -0.15000000596046448d
	 * getstatic     // AttributeModifier$Operation.ADD_MULTIPLIED_TOTAL
	 * invokevirtual // MobEffect.addAttributeModifier
	 * }</pre>
	 *
	 * <p>⚠ <b>{@code 0.15} 가 아니라 이 긴 수다.</b> 바닐라가 {@code float 0.15F} 를
	 * {@code double} 로 넓힌 값이라 끝자리가 남아 있고, 시험이 <b>바닐라가 만든 수정자와
	 * 1.0E-12 안에서 같은지</b>를 보기 때문에 반올림해 적으면 그 시험이 멈춘다.
	 */
	static final double SLOWNESS_AMOUNT_PER_LEVEL = -0.15000000596046448;
	/**
	 * 구속 III 가 거는 몫. <b>{@code -0.45000001788139343}</b> 이다.
	 *
	 * <p>급을 곱하는 식까지 바닐라와 같다 — 26.3 {@code MobEffect.AttributeTemplate.create(int)} 가
	 * {@code new AttributeModifier(id, amount × (amplifier + 1), operation)} 인 것을 바이트코드로
	 * 확인했다({@code iload_1; iconst_1; iadd; i2d; dmul}).
	 *
	 * <p><b>연산이 {@code ADD_MULTIPLIED_TOTAL} 이므로 이동 속도가 × 0.55 가 된다</b>(45% 감소).
	 * 26.3 {@code AttributeInstance.calculateValue} 가 그 갈래에서 {@code 값 ×= (1 + 몫)} 을 하는
	 * 것까지 바이트코드로 확인했다. ⚠ <b>연산을 바꾸면 「구속 3급」이 거짓이 된다</b> — 같은 −0.45
	 * 를 {@code ADD_VALUE} 로 걸면 기본 이동 속도 0.1 이 음수가 되어 아예 못 걷는다.
	 */
	static final double LIGHTNING_SLOW_AMOUNT =
			SLOWNESS_AMOUNT_PER_LEVEL * (LIGHTNING_SLOW_AMPLIFIER + 1);
	/**
	 * 깎아 두는 시간(틱). <b>1초. 사람이 정한 값이다.</b>
	 *
	 * <p>⚠ <b>바닐라가 세어 주지 않는다.</b> 상태이상은 시간이 다하면 바닐라가 걷어 가는데 직접
	 * 깎은 것은 <b>우리가 걷어야 한다</b> — 안 걷으면 <b>영구히 느린 사람</b>이 남는다. 걷는 자리가
	 * 둘이고 둘 다 있어야 한다({@link #expireSlows} · {@link #releaseSlows}).
	 */
	static final int LIGHTNING_SLOW_TICKS = 20;
	/**
	 * 깎인 그 사람 발밑에 터뜨리는 점 수. <b>아이콘이 없으니 이것이 유일한 신호다.</b>
	 *
	 * <p>「엔더 파동」이 구속을 걸 때 쓰는 신호와 <b>같은 입자·같은 퍼짐</b>이다
	 * ({@code TrialEnderPulse.root} 의 {@code PORTAL} 24점 · 퍼짐 {@code 0.35, 0.05, 0.35}). 사람이
	 * 이미 그 카드에서 <b>「발이 묶였다」의 그림</b>으로 배운 것이라 새로 배울 것이 없다.
	 *
	 * <p>⚠ <b>24 가 아니라 12 인 것은 점 예산이다.</b> 내리치는 틱은 바닥 표식이 없어 번개 몫이
	 * 0 인데, 여기에 사람마다 점을 뿌리면 <b>그 틱이 새로 가장 바쁜 틱이 될 수 있다.</b>
	 * {@code 12 × 4인 = 48} 점이고 그것이 예고 틱의 {@code 70} 점 아래라 <b>가장 바쁜 틱이 안
	 * 움직인다</b> — {@link #lightningPoints} 가 그 둘 가운데 큰 쪽을 센다.
	 *
	 * <p>⚠ <b>소리를 더하지 않았다.</b> 이 페이즈는 사람 말(<b>「각각 패턴마다 소리가
	 * 구분되엇으면해」</b>)에 따라 <b>일곱 소리가 전부 다른 파일</b>인 상태로 맞춰져 있고, 번개는
	 * 스스로 천둥과 착탄음을 낸다. 여덟째 소리를 얹으면 그 일곱의 뜻이 묶히는 쪽이 손해가 크다.
	 */
	static final int LIGHTNING_SLOW_MARK_POINTS = 12;

	// ------------------------------------------------------------------ 그리는 값

	/** 바닥 표식의 점 간격(칸). {@code TrialWarning.POINT_GAP} 과 같다. */
	private static final double MARK_GAP = TrialWarning.POINT_GAP;
	/** 부채꼴 가장자리에 흰 벽을 세울 때의 점 간격(칸). 바닥 선보다 성기게 찍어 예산을 아낀다. */
	private static final double EDGE_WALL_GAP = 1.0;
	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다. */
	private static final double GROUND_OFFSET = 0.15;
	/**
	 * 부채꼴 안에 더 그리는 호의 반경 비율. 「어디까지가 20칸인가」를 눈이 가늠하게 한다.
	 *
	 * <p>전에는 <b>0.5 하나</b>였다. 사람이 <b>「각각 패턴을 쓴다느 느낌이 들게 이펙트를 키우든 좀더
	 * 가시성이 좋앗으면좋겟어」</b>라고 해서 셋으로 늘렸다 — 안쪽 호가 하나뿐이면 20칸짜리 부채꼴
	 * 안이 <b>거의 비어 있어</b> 테두리만 보이고, 부채꼴 안에 선 사람은 그 테두리를 시선과 나란하게
	 * 본다(그래서 흰 벽이 있다).
	 *
	 * <p>⚠ 1.0 을 넣지 말 것. 그것이 {@link #CONE_RANGE} 의 호이고 이미 따로 그린다 — 두 번 찍으면
	 * 점만 두 배다.
	 */
	private static final double[] CONE_ARC_FRACTIONS = {0.25, 0.5, 0.75};
	/**
	 * 고리를 <b>벽으로</b> 세울 때의 점 간격(칸).
	 *
	 * <p>바닥 고리의 간격({@code TrialWarning.POINT_GAP} = 0.5)보다 성기다. 벽은 「경계가 정확히
	 * 어디인가」를 말하는 갈래가 아니라 <b>「저기 무엇인가 서 있다」</b>를 말하는 갈래라, 눈높이에서
	 * 세로로 선 것은 1.5칸 간격이어도 한 겹으로 읽힌다 — 「착지 충격」이 같은 수법을 쓰면서 적어 둔
	 * 근거가 그것이다({@code TrialLandingShock.EDGE_RISE_SPEED}).
	 */
	private static final double RING_WALL_GAP = 1.5;

	/**
	 * {@code CritParticle} 이 받은 속도에 곱하는 값. 26.3 클래스 파일에서 확인했다.
	 *
	 * <p>⚠ 이 셋이 {@code TrialLandingShock} 에도 있다. 그쪽이 {@code private} 이고 <b>그 파일을
	 * 읽기만 하기로 정해져 있어서</b> 열어 달라고 고치지 않았다 — 같은 것이 두 벌인 사실은 인계에
	 * 적어 두었다. ⚠ <b>판을 올려 한쪽을 고치면 반드시 다른 쪽도 볼 것.</b>
	 *
	 * <p>{@code CritParticle} 의 생성자가 상위에 {@code 0,0,0} 을 넘기고 나서
	 * {@code xd = xd × 0.1 + 받은 속도 × 0.4} 를 한다. 앞 항이 0 이므로 <b>방향이 정확히 보존되고
	 * 크기만 0.4배</b>다 — 그래서 이 점으로 <b>방향</b>을 말할 수 있다. ⚠ 먼지
	 * ({@code DustParticleBase})는 그렇지 않다: 상위 생성자가 받은 속도를 <b>정규화해 무작위
	 * 크기로 다시 싣고</b> 거기에 0.1 을 곱하므로 흐르는 거리가 0.15칸이다. <b>방향을 말해야 하는
	 * 자리에 먼지를 쓰지 말 것.</b>
	 */
	private static final double CRIT_SPEED_FACTOR = 0.4;
	/** {@code CritParticle} 의 마찰. {@code Particle.tick} 이 매 틱 속도에 곱한다. */
	private static final double CRIT_FRICTION = 0.7;
	/** {@code CritParticle} 수명의 <b>하한</b>(틱). {@code max(1, 6.0 / (굴림×0.8 + 0.6))} 이 4~10 이다. */
	private static final int CRIT_MIN_LIFETIME = 4;

	// ------------------------------------------------------------------ 한 판 동안 붙잡아 두는 것

	/**
	 * 부채꼴 브레스가 고정한 방향(도)과 그것을 고른 판.
	 *
	 * <p>⚠ <b>예고 중에 조준이 바뀌지 않는 것이 「즉사 허용」의 조건 셋 중 하나다.</b> 매 틱 다시
	 * 재면 사람이 옆으로 빠져도 부채꼴이 따라와 4초 예고가 아무 뜻이 없다.
	 *
	 * <p>한 칸뿐인 것은 패턴이 <b>한 번에 하나만</b> 돌기 때문이다
	 * ({@code DragonLastStand.advance}). 판을 가리키는 것은 시작 틱이고, 그것이 달라지면 새 판이라
	 * 다시 고른다.
	 */
	private static long coneAimedFor = Long.MIN_VALUE;
	private static float coneYaw;

	/**
	 * 십자 바닥 면을 <b>어느 판 · 몇 회차</b> 것으로 세워 두었는가.
	 *
	 * <p>{@link #coneAimedFor} 와 같은 수법이다. 「예고 첫 틱이면 세운다」를 {@code step == …} 으로
	 * 물으면 그 한 틱이 빠질 때(그 틱에 패턴이 시작하지 않은 판 등) 면이 영영 안 선다. 「세워 둔 것이
	 * 지금 회차가 아니면 세운다」로 물으면 빠진 틱 다음 틱에라도 선다.
	 */
	private static long crossPanelAt = Long.MIN_VALUE;
	private static int crossPanelRound = -1;

	/**
	 * 상시 번개의 시계. <b>{@link DragonLastStandZone} 의 {@code drivingSince} 와 같은 모양</b>이다.
	 *
	 * <ul>
	 *   <li>{@code lightningOwner} — 지금 몰고 있는 최후의 저항이 시작한 틱. 바뀌면 처음부터 다시 센다</li>
	 *   <li>{@code lightningNextVolleyAt} — 다음 볼리를 열 시각</li>
	 *   <li>{@code lightningStrikeAt} — 열려 있는 볼리가 내리칠 시각. {@link Long#MIN_VALUE} 면 볼리가 없다</li>
	 *   <li>{@code lightningSpots} — 그 볼리의 열 곳. 예고가 도는 동안 자리가 움직이면 예고가 아니다</li>
	 * </ul>
	 *
	 * <p>⚠ 주기를 {@code now % 120} 으로 세지 않는 것은 <b>틱을 건너뛰어도 내리치게</b> 하기
	 * 위해서다. 예고를 3초 보여 준 볼리가 렉 한 번으로 조용히 사라지면 그것이 가장 나쁘다 —
	 * 「종말의 비」가 {@code nextVolleyAt} 을 들고 있는 것과 같은 까닭이다.
	 */
	private static long lightningOwner = Long.MIN_VALUE;
	private static long lightningNextVolleyAt;
	private static long lightningStrikeAt = Long.MIN_VALUE;
	private static List<Vec3> lightningSpots = List.of();

	/**
	 * ⚠⚠ 번개에 이동 속도를 깎인 사람과 <b>걷어낼 시각</b>.
	 *
	 * <p>상태이상이 아니므로 <b>바닐라가 시간을 세어 주지 않는다.</b> 이 표가 「누구에게서 언제
	 * 걷어야 하는가」의 유일한 기록이고, 비어 있는 것이 곧 「아무도 안 깎여 있다」다.
	 *
	 * <h2>⚠ {@code ServerPlayer} 를 들고 있는 까닭</h2>
	 *
	 * <p>{@link #clearState} 는 {@code SERVER_STOPPED} 에서도 불려 <b>월드도 서버도 만질 수
	 * 없다</b> — UUID 만 적어 두면 그 자리에서 사람을 찾을 길이 없어 수정자가 남는다. 수정자를
	 * 걷는 것은 <b>그 사람의 속성 표를 만지는 것뿐이고 월드가 필요 없으므로</b>, 개체를 들고 있으면
	 * 그 자리에서 걷을 수 있다 — {@link DragonLastStandConePanel}·{@code DragonLastStandObjects} 가
	 * 「월드를 못 만지는 자리에서 거두려고 개체를 들고 있다」고 적어 둔 것과 <b>같은 까닭이고 같은
	 * 수법</b>이다.
	 *
	 * <p>참조가 오래 남을 걱정은 {@link #expireSlows} 가 <b>매 틱</b> 쓸어 없앤다 — 보통 20틱이면
	 * 비고, 접속을 끊은 사람은 {@code isRemoved()} 로 그 틱에 빠진다.
	 */
	private static final Map<UUID, Slow> SLOWED = new HashMap<>();

	/**
	 * 한 사람의 깎임. {@code until} 은 <b>걷어낼 시각</b>이고 {@code player} 는 그 사람이다.
	 *
	 * @param player 수정자를 걷을 대상. 월드 없이 걷기 위해 개체를 들고 있다({@link #SLOWED})
	 * @param until  이 틱이 되면 걷는다. 곧 {@code 맞은 틱 + }{@value #LIGHTNING_SLOW_TICKS}
	 */
	private record Slow(ServerPlayer player, long until) {
	}

	private DragonLastStandPatterns() {
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. 지난 판의 조준·자리·시계가 새 판으로 새지 않게 한다.
	 *
	 * <p>⚠ <b>{@link DragonLastStandConePanel#drop()} 이 여기 있는 것이 중요하다.</b> 이 메서드는
	 * {@code SERVER_STOPPED} 에서도 불려 월드를 만질 수 없는데, 빨간 면은 <b>파티클이 아니라
	 * 개체</b>라 지우지 않으면 남는다. 그쪽이 개체를 들고 있으므로 월드 없이 지울 수 있다.
	 *
	 * <p>⚠ <b>{@link #releaseSlows} 가 여기 있는 것도 같은 까닭이다.</b> 번개가 깎아 둔 이동 속도는
	 * <b>상태이상이 아니라 속성 수정자</b>라 바닐라가 걷어 가지 않는다. 이 메서드는
	 * {@code DragonLastStand.onFightClosed}(전투가 닫힐 때)와 {@code DragonLastStand.clearState}
	 * (월드가 바뀌거나 서버가 내려갈 때) 둘 다에서 불리므로, <b>그 두 길이 여기 한 줄로 막힌다.</b>
	 *
	 * <p>⚠ <b>{@link DragonLastStandCrossPanel#drop()} 도 같은 까닭으로 여기 있다</b>(2026-10-04). 십자
	 * 바닥 면도 개체라 지우지 않으면 남는다.
	 */
	static void clearState() {
		coneAimedFor = Long.MIN_VALUE;
		coneYaw = 0.0F;
		crossPanelAt = Long.MIN_VALUE;
		crossPanelRound = -1;
		lightningOwner = Long.MIN_VALUE;
		lightningNextVolleyAt = 0L;
		lightningStrikeAt = Long.MIN_VALUE;
		lightningSpots = List.of();
		releaseSlows();
		DragonLastStandConePanel.drop();
		DragonLastStandCrossPanel.drop();
	}

	/**
	 * 다음 상시 번개가 <b>내리치는</b> 틱까지 — 패턴 타이머 HUD({@link TrialTimers})가 읽는다.
	 * <b>상태를 읽기만 한다.</b>
	 *
	 * <p>볼리 시각은 굴림 없이 {@link #LIGHTNING_PERIOD_TICKS} 마다지만, 자리를 못 얻은 볼리는 예고 없이
	 * 건너뛰고 렉·멈춤 뒤에는 「지났다」로 내리치므로({@link #tickLightning}) 주기를 다시 세지 않고 정적
	 * 칸({@code lightningOwner} · {@code lightningNextVolleyAt} · {@code lightningStrikeAt})을 그대로
	 * 읽는다. 셈은 {@link #lightningClockOf} 에 떼어 두었다.
	 *
	 * @param clockBase 최후의 저항의 시계 원점({@code DragonLastStand} 의 {@code clockBase})
	 */
	static TrialTimers.Clock lightningClock(long clockBase, long now) {
		return lightningClockOf(lightningOwner, lightningNextVolleyAt, lightningStrikeAt,
				clockBase, now);
	}

	/**
	 * {@link #lightningClock} 의 셈. <b>월드를 모른다.</b>
	 *
	 * <ul>
	 *   <li>이 판의 첫 틱이 아직 안 돌았다({@code owner} 가 다르다) — 첫 볼리는 시계 원점 +
	 *       {@link DragonLastStand#ENTRY_GRACE_TICKS} 에 열리고 그 {@link #LIGHTNING_WARN_TICKS} 뒤에
	 *       내리친다(진입 연출 중이면 그만큼 더 멀다)</li>
	 *   <li>볼리가 열려 있다 — 내리칠 시각이 칸에 있다. 노란 고리가 이미 깔려 있다</li>
	 *   <li>볼리가 없다 — 다음 볼리를 열 시각 + 예고</li>
	 * </ul>
	 */
	static TrialTimers.Clock lightningClockOf(long owner, long nextVolleyAt, long strikeAt,
			long clockBase, long now) {
		long strikesAt;
		if (owner != clockBase) {
			strikesAt = clockBase + DragonLastStand.ENTRY_GRACE_TICKS + LIGHTNING_WARN_TICKS;
		} else if (strikeAt != Long.MIN_VALUE) {
			strikesAt = strikeAt;
		} else {
			strikesAt = nextVolleyAt + LIGHTNING_WARN_TICKS;
		}
		long remaining = strikesAt - now;
		return TrialTimers.Clock.countdown(remaining, Math.max(remaining, LIGHTNING_PERIOD_TICKS),
				LIGHTNING_WARN_TICKS);
	}

	/**
	 * 고른 패턴을 그린다. 시작 틱과 끝나는 틱 사이 <b>매 틱</b> 불린다.
	 *
	 * @param at 이 패턴이 시작한 틱
	 */
	static void run(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			DragonLastStand.Pattern pattern, long at, long now) {
		long step = now - at;
		if (step < 0L) {
			return;
		}
		// default 를 넣지 말 것. 패턴을 셋째로 늘리고 그리는 것을 안 붙이면 여기서 빌드가 깨져야
		// 한다 — 안 깨지면 드래곤이 그 패턴을 고른 동안 아무것도 안 한다.
		switch (pattern) {
			case WING_BEAT -> wingBeat(end, dragon, members, at, now, (int) step);
			case CONE_BREATH -> coneBreath(end, dragon, members, at, now, (int) step);
			case VOID_SUCTION -> voidSuction(end, dragon, members, (int) step);
			case CROSS_FISSURE -> crossFissure(end, dragon, members, at, (int) step);
		}
	}

	// ------------------------------------------------------------------ ① 날개 퍼덕이기

	/**
	 * 날개 퍼덕이기 — 5초간 0.6초마다 충격파 여덟 번. <b>넉백만, 피해 없음.</b>
	 *
	 * <h2>⚠ 천장이 둘이다 — 하나라도 빼면 낙사 장치다</h2>
	 *
	 * <p>공유 체력이라 한 사람의 낙사가 팀 전체를 끝내고 그것이 곧 월드 삭제다. 그래서 미는
	 * 거리를 <b>두 번</b> 자른다. 둘 다 이 저장소가 이미 쓰는 함수를 <b>그대로 부른다</b> —
	 * 여기서 다시 짜면 두 벌이 되어 언젠가 한쪽만 고쳐진다.
	 *
	 * <ol>
	 *   <li>{@code TrialEnderStorm.pushDistance} — <b>목적지가 반경 32칸 안</b>인 만큼만 돌려준다
	 *       ({@code pushLimitRadius() = ARENA_RADIUS 40 − PUSH_LIMIT_MARGIN 8}). 어떤 세기를
	 *       넣어도, 몇 번을 연달아 밀려도, 밀리는 도중의 어느 점도 그 안이다 — 증명이 그 메서드에
	 *       적혀 있다</li>
	 *   <li>{@code TrialLandingShock.groundedReach} — 그 길을 반 칸씩 짚어 <b>땅이 끊기기 전</b>
	 *       에서 한 번 더 자른다. 중앙 섬은 둥글지 않아 반경 32 안에도 허공이 있고, 사람이 파 놓은
	 *       구멍도 있다</li>
	 * </ol>
	 *
	 * <p>⚠ <b>안전지대에 기대지 않는다 — 기댈 벽이 없다.</b> 2026-10-04 부터 안전지대는 월드 보더가
	 * 아니라 <b>나갈 수 있는 원</b>이다(빨간 입자 기둥 · {@link DragonLastStandZoneWall}). 아무것도 막지
	 * 않으므로 밀린 사람은 원 밖으로 그대로 나간다 — 그때 밖 피해를 봐 주는 것이
	 * {@code DragonLastStandZone.SHOVE_GRACE_TICKS}(2초)다. 보더 시절에도 벽은 「안에 있고 벽에서 2칸
	 * 안」일 때만 막아 대각선 쪽(모서리 59칸)에서는 아무것도 막지 않았다. 안전은 언제나 위의 둘이
	 * 지켰고 지금도 그렇다.
	 *
	 * <h2>세로로 한 칸도 띄우지 않는다</h2>
	 *
	 * <p>띄우면 바닥 마찰이 안 먹어 적힌 거리를 끝까지 날아가고 낙하 피해도 붙는다. 「착지 충격」·
	 * 「엔더폭풍」도 세로를 올리지 않는다.
	 *
	 * <p>⚠⚠ <b>세로를 「읽어서 그대로」 돌려놓는 것이 2026-10-04 에 사고가 됐다.</b> 서버가 들고
	 * 있는 세로는 <b>우리가 쓴 값이 아니라</b> 바닐라 드래곤이 매 틱 쌓아 둔 값이고,
	 * {@code syncVelocity} 를 켜는 한 줄이 그것을 본인에게 배달한다 —
	 * {@link TrialVelocity#syncedVertical} 에 전부 적어 두었다.
	 * ⚠ <b>「착지 충격」·「엔더폭풍」의 {@code push} 도 같은 날 같은 함수를 지나게 됐다</b> — 그 둘은
	 * 최후의 저항 밖에서 도는 카드라 바닐라 넉백이 그대로 쌓이고, 배달을 막는 것이 그 함수뿐이다.
	 *
	 * <h2>⚠ 그런데 <b>사람이 스스로 뛰면</b> 천장 둘로는 모자랐다</h2>
	 *
	 * <p>사람 말: <b>「밀치는거 점프하는도중 밀쳐지면 저끝까지 날라가버리거든? 그것도
	 * 조심해야겟어」</b>. 천장 둘은 <b>섬 밖으로 나가는 것</b>을 막는 장치이고, 떠 있는 사람이
	 * <b>천장 안에서 다섯 배 멀리 가는 것</b>은 막지 않는다 — 바닥 마찰이 빠지기 때문이다.
	 * {@link #AIRBORNE_PUSH_SCALE} 가 그쪽을 따로 막는다. <b>셋 다 있어야 하고 셋이 다른 일을
	 * 한다</b>: 천장 둘은 「허공으로 못 나간다」, 비율은 「점프가 이득도 손해도 아니다」다.
	 *
	 * <h2>세기를 절반으로 줄였다가 1.5배로 되돌렸다</h2>
	 *
	 * <p><b>「30프로미만 2페이지에서 밀쳐지는게 너무심해 지금보다 50프로는 안밀쳐지게하고」</b> —
	 * {@link #WING_PUSH_CUT} 이 그 몫이다. 그 뒤 2026-10-04 에 <b>「밀치는 힘이 지금 거의 없어진 것처럼
	 * 됐어. 50프로 키워」</b> — {@link #WING_PUSH_REGAIN} 이다. 둘 사이에 세로 배달 버그가 닫혀 사람이
	 * 더는 뜨지 않게 된 것이 까닭이고, 그 따라감이 {@link #WING_PUSH_REGAIN} 에 적혀 있다.
	 *
	 * <h2>⚠ 연출이 「밀어낸다」를 말해야 한다</h2>
	 *
	 * <p>파랑 고리 둘은 <b>가만히 있는 세기 지도</b>라 「여기가 세다」만 말한다. 사람이
	 * <b>「빨아드리는거랑 밀치는거랑 이펙트가 너무 구분이안됨」</b>이라고 한 까닭이 그것이고,
	 * 그래서 {@link #wingWave} 가 <b>바깥으로</b> 번지는 가닥을 더한다 — 공허 흡입의
	 * {@link #streamSuck} 과 글자 그대로 반대다.
	 */
	private static void wingBeat(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			long at, long now, int step) {
		Vec3 center = dragon.position();
		// 세기 지도를 바닥에 그린다. 파랑은 규약의 「밀려난다」다 — 4칸 고리 안은 약하고
		// 4~12칸 사이가 가장 강하다는 것을 두 고리가 그대로 말한다.
		ParticleOptions shove = TrialWarning.dust(TrialWarning.Colors.SHOVE);
		TrialWarning.markGround(end, center, WING_NEAR_RADIUS, shove);
		TrialWarning.markGround(end, center, WING_STRONG_RADIUS, shove);
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		// 그 두 고리를 벽으로도 세운다. 바닥 선은 서서 보는 눈높이에서 시선과 나란해 거의 안 보인다.
		ringWall(end, ground, center, WING_NEAR_RADIUS);
		ringWall(end, ground, center, WING_STRONG_RADIUS);
		// 바깥으로 흐르는 가닥. 공허 흡입의 안쪽 흐름과 정확히 반대다 — 사람이 「빨아드리는거랑
		// 밀치는거랑 이펙트가 너무 구분이안됨」이라고 한 자리가 여기와 그쪽 둘이다.
		wingWave(end, ground, center, step);
		if (step % WING_PULSE_TICKS != 0 || step / WING_PULSE_TICKS >= WING_PULSES) {
			return;
		}
		int pulse = step / WING_PULSE_TICKS;
		// 사람마다 그 자리에서 정확히 한 번 울린다. 팀원 루프에서 level.playSound 를 부르면
		// 모여 있는 넷이 각자 네 겹으로 듣는다. 음높이가 여덟 번에 걸쳐 올라 「몇 번째인가」가 들린다.
		TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_FLAP, 1.0F, flapPitch(pulse));
		end.sendParticles(ParticleTypes.GUST_EMITTER_LARGE, true, false,
				center.x, center.y + 1.0, center.z, 1, 0.0, 0.0, 0.0, 0.0);
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			shove(end, ground, member, center, at, now);
		}
	}

	/**
	 * 그 번치의 날갯짓 음높이. 낮은 데서 시작해 <b>마지막 한 번이 가장 높다.</b>
	 *
	 * <p>나누는 것이 {@link #WING_PULSES} 가 아니라 <b>{@code WING_PULSES − 1}</b> 이다. 개수로
	 * 나누면 마지막 번치가 {@code 7/8} 진행에 머물러 {@link #WING_FLAP_PITCH_HIGH} 가 <b>한 번도
	 * 나지 않는다</b> — 부채꼴의 {@link #chargePitch} 가 같은 함정을 같은 방법으로 피한다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 여덟 번을 직접 굴려 본다.
	 */
	static float flapPitch(int pulse) {
		int last = WING_PULSES - 1;
		if (last <= 0) {
			return WING_FLAP_PITCH_HIGH;
		}
		float progress = Math.max(0.0F, Math.min(1.0F, (float) pulse / last));
		return WING_FLAP_PITCH_LOW + (WING_FLAP_PITCH_HIGH - WING_FLAP_PITCH_LOW) * progress;
	}

	/**
	 * 한 사람을 바깥으로 민다. 천장 둘을 지난 뒤에만 실제로 민다.
	 *
	 * <p>⚠ <b>속도를 덮어쓴다.</b> 더하면 달리던 사람이 들고 있던 수평 속도가 얹혀 천장이 계산한
	 * 목적지를 지나쳐 간다 — 그 한 줄이 「달리는 중에 밀려도 안전하다」의 근거다.
	 *
	 * <p>⚠⚠ <b>덮어쓰는 것이 수평을 지키는 동시에 수평의 오염까지 지운다.</b> 바닐라
	 * {@code EnderDragon.knockBack} 은 수평도 쌓는데({@code dx ÷ max(dx²+dz², 0.1) × 4}, 드래곤
	 * 몸통 중심에서 0.316칸에 선 사람에게 <b>12.65칸/틱</b>) 그 값이 덮어써져 사라진다. 세로만
	 * 읽어서 돌려놓고 있었기 때문에 <b>세로로만 샜다</b> — {@link TrialVelocity#syncedVertical} 을 볼 것.
	 */
	private static void shove(ServerLevel end, TrialEnderPulse.Ground ground, ServerPlayer member,
			Vec3 center, long at, long now) {
		double dx = member.getX() - center.x;
		double dz = member.getZ() - center.z;
		double from = Math.sqrt(dx * dx + dz * dz);
		double wanted = wingPushBlocks(from);
		if (!(wanted > 0.0) || !(from > 1.0E-4)) {
			// 드래곤과 정확히 겹쳐 있으면 「바깥쪽」이 없다. 방향을 지어내지 않는다.
			return;
		}
		double stepX = dx / from;
		double stepZ = dz / from;
		Vec3 outward = new Vec3(stepX, 0.0, stepZ);
		double distance = TrialEnderStorm.pushDistance(member.getX(), member.getZ(), outward, wanted);
		distance = TrialLandingShock.groundedReach(
				(x, z) -> ground.surfaceAt(end, x, z) != TrialEnderPulse.NO_GROUND,
				member.getX(), member.getZ(), stepX, stepZ, distance);
		if (!(distance > 0.0)) {
			return;
		}
		double speed = shoveSpeed(distance, isAirborne(end, ground, member));
		Vec3 motion = member.getDeltaMovement();
		// 수평을 더하지 않고 덮어쓰는 것은 들고 있던 속도가 얹혀 천장이 계산한 목적지를 넘지
		// 않게 하기 위해서다. ⚠ 세로는 「읽은 그대로」가 아니라 TrialVelocity.syncedVertical
		// 을 지난다 —
		// 바닐라 드래곤이 매 틱 +0.2 를 쌓아 두고 그것을 그대로 배달하면 사람이 하늘로 간다.
		member.setDeltaMovement(stepX * speed,
				TrialVelocity.syncedVertical(motion.y, member.getKnownMovement().y),
				stepZ * speed);
		// 켜지 않으면 서버 혼자 민 것이 되어 잠시 뒤 제자리로 되돌아간다.
		member.syncVelocity = true;
		// 밀린 사람은 2초 동안 안전지대 밖 피해를 안 받는다. 사람이 정한 유예다.
		DragonLastStandZone.noteShoved(member.getUUID(), at, now);
		end.sendParticles(ParticleTypes.GUST, true, false,
				member.getX(), member.getY() + 0.1, member.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
	}

	/**
	 * ⚠⚠ 이 번치에 실을 <b>처음 속도</b>(칸/틱). <b>공중이면 바닥과 같은 거리만 가게 깎는다.</b>
	 *
	 * <p>사람 말: <b>「밀치는거 점프하는도중 밀쳐지면 저끝까지 날라가버리거든? 그것도
	 * 조심해야겟어」</b>. 왜 떠 있으면 다섯 배가 되는지와 왜 비율이 0.198 인지는
	 * {@link #AIRBORNE_PUSH_SCALE} 에 적어 두었다.
	 *
	 * <p>천장에 걸러진 {@code distance} 를 받는다 — 그러니 <b>이 함수가 하는 일은 세기뿐이고
	 * 안전은 그 앞의 둘이 지킨다.</b> 거꾸로도 참이다: 이 함수가 1 을 돌려주든 0.1 을 돌려주든
	 * 사람이 섬 밖으로 나가지 않는다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이
	 * <b>{@code 공중 이동(공중 속도) == 바닥 이동(바닥 속도)}</b> 를 거리마다 직접 굴려 본다.
	 */
	static double shoveSpeed(double distance, boolean airborne) {
		double speed = TrialEnderStorm.pushVelocity(distance);
		return airborne ? speed * AIRBORNE_PUSH_SCALE : speed;
	}

	/**
	 * 그 속도로 <b>떠 있는</b> 몸이 끝까지 나아가는 수평 거리(칸).
	 *
	 * <p>{@code v + 0.91v + 0.91²v + … = v ÷ (1 − 0.91)} 이다. {@code TrialEnderStorm.pushVelocity}
	 * 가 거꾸로 쓰는 바로 그 식이라, 둘을 이어 붙이면 「공중에서는 적힌 거리를 그대로 간다」가 나온다.
	 */
	static double airborneTravel(double speed) {
		return Math.max(0.0, speed) / (1.0 - TrialEnderStorm.AIR_DRAG);
	}

	/**
	 * 그 속도로 <b>바닥에 붙은</b> 몸이 끝까지 나아가는 수평 거리(칸).
	 *
	 * <p>감쇠가 {@value #GROUND_DRAG} 라 {@code v ÷ 0.454} 이고, 적힌 거리의 <b>5분의 1 남짓</b>
	 * 이라는 그 말의 출처다.
	 */
	static double groundedTravel(double speed) {
		return Math.max(0.0, speed) / (1.0 - GROUND_DRAG);
	}

	/**
	 * 이 사람이 <b>떠 있는가.</b>
	 *
	 * <h2>⚠ 둘 중 하나만 참이어도 떠 있는 것으로 본다</h2>
	 *
	 * <ul>
	 *   <li>{@code onGround()} 가 거짓 — 가장 바로인데 <b>클라이언트가 보내 준 깃발</b>이다</li>
	 *   <li>발이 그 자리 지표보다 {@value #AIRBORNE_LIFT} 칸 넘게 높다 — <b>서버만 아는 하이트맵</b>
	 *       이라 거짓 보고로 뒤집을 수 없다</li>
	 * </ul>
	 *
	 * <p>「또는」인 것이 요점이다. 어긋나는 방향이 언제나 <b>덜 미는 쪽</b>이어야 한다는 것이 이
	 * 저장소의 태도이고({@code TrialEnderStorm.pushVelocity} 가 그 문장을 적어 두었다), 땅에 선
	 * 사람을 공중으로 잘못 보면 <b>조금 덜 밀리고</b> 그 반대는 <b>저 끝까지 날아간다.</b>
	 *
	 * <p>땅이 아예 없으면({@code NO_GROUND}) 허공 위다 — 그때도 떠 있는 것으로 본다. 그 자리에서
	 * 미는 것은 애초에 {@code groundedReach} 가 막지만, 이 물음이 먼저 틀릴 이유는 없다.
	 */
	private static boolean isAirborne(ServerLevel end, TrialEnderPulse.Ground ground,
			ServerPlayer member) {
		if (!member.onGround()) {
			return true;
		}
		int surface = ground.surfaceAt(end, member.getX(), member.getZ());
		if (surface == TrialEnderPulse.NO_GROUND) {
			return true;
		}
		return member.getY() - surface > AIRBORNE_LIFT;
	}

	/**
	 * 바깥으로 번져 나가는 가닥. <b>번치마다 한 벌</b>이고 12틱에 20칸을 간다.
	 *
	 * <p>앞머리가 {@link #wingWaveRadius} 로 나아가고, 그 자리마다 가닥 하나가 <b>바깥쪽</b>으로
	 * 흐른다. 공허 흡입의 {@link #streamSuck} 과 <b>같은 점 · 같은 개수 · 같은 길이</b>이고
	 * <b>방향만 반대</b>다.
	 *
	 * <p>번치마다 갈래를 반 칸 돌린다. 안 돌리면 여덟 번이 모두 같은 스물네 줄을 지나 바닥에
	 * <b>같은 자국</b>이 쌓이고, 그러면 「번졌다」가 아니라 「거기 늘 있었다」로 보인다.
	 */
	private static void wingWave(ServerLevel end, TrialEnderPulse.Ground ground, Vec3 center,
			int step) {
		int pulse = step / WING_PULSE_TICKS;
		if (pulse >= WING_PULSES) {
			return;
		}
		double front = wingWaveRadius(step);
		double turn = Math.PI * pulse / WING_GUST_SPOKES;
		for (int spoke = 0; spoke < WING_GUST_SPOKES; spoke++) {
			double angle = (Math.PI * 2.0 * spoke) / WING_GUST_SPOKES + turn;
			double outX = Math.cos(angle);
			double outZ = Math.sin(angle);
			streak(end, ground, ParticleTypes.CRIT, center.x + outX * front,
					center.z + outZ * front, outX, outZ, WING_GUST_DRIFT);
		}
	}

	/**
	 * 그 틱에 앞머리가 서 있는 반경(칸). <b>번치마다 0 에서 {@link #WING_FADE_RADIUS} 까지다.</b>
	 *
	 * <p>12틱에 20칸이라 틱당 1.67칸이다. 가닥 하나가 3칸을 흐르므로({@link #critDrift}) 앞머리
	 * 뒤로 두 틱쯤의 꼬리가 남아 <b>번져 나가는 띠</b>로 보인다.
	 *
	 * <p>{@code +1} 을 하는 것은 첫 틱에 반경 0 에 스물네 점이 겹쳐 찍히지 않게 하는 것이다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 100틱을 통째로 훑는다.
	 */
	static double wingWaveRadius(int step) {
		int into = Math.floorMod(step, WING_PULSE_TICKS);
		return (WING_FADE_RADIUS * (into + 1)) / WING_PULSE_TICKS;
	}

	/**
	 * 그 거리에서 <b>부탁하는</b> 미는 거리(칸). 실제로 미는 거리는 천장 둘이 다시 자른다.
	 *
	 * <p>모양이 문서의 한 줄 그대로다 — <b>4칸 이내는 약하고 4~12칸이 가장 강하다.</b>
	 *
	 * <ul>
	 *   <li>{@code 0 ~ 4} — {@link #WING_NEAR_PUSH_BLOCKS} 에서 {@link #WING_PUSH_BLOCKS} 로 오른다</li>
	 *   <li>{@code 4 ~ 12} — {@link #WING_PUSH_BLOCKS} 그대로. 가장 강한 구간이다</li>
	 *   <li>{@code 12 ~ 20} — 0 으로 잦아든다. 절벽처럼 끊으면 12.0 과 12.1 의 결과가 완전히 갈린다</li>
	 *   <li>{@code 20 이상} — 0. 날개바람이 닿지 않는다</li>
	 * </ul>
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 0.1칸 간격으로 훑는다.
	 */
	static double wingPushBlocks(double distance) {
		if (!(distance > 0.0) || distance >= WING_FADE_RADIUS) {
			return 0.0;
		}
		if (distance < WING_NEAR_RADIUS) {
			double progress = distance / WING_NEAR_RADIUS;
			return WING_NEAR_PUSH_BLOCKS
					+ (WING_PUSH_BLOCKS - WING_NEAR_PUSH_BLOCKS) * progress;
		}
		if (distance <= WING_STRONG_RADIUS) {
			return WING_PUSH_BLOCKS;
		}
		double fade = (WING_FADE_RADIUS - distance) / (WING_FADE_RADIUS - WING_STRONG_RADIUS);
		return WING_PUSH_BLOCKS * fade;
	}

	// ------------------------------------------------------------------ ② 부채꼴 브레스

	/**
	 * 부채꼴 브레스 — 4초 예고(2026-10-04 에 5초 → 4초) · 예고와 함께 머리 고정 · 90도 · 20칸 · 피해
	 * {@code DragonLastStand.CONE_BREATH_DAMAGE}(65) · <b>잔류 없음.</b>
	 *
	 * <h2>⚠⚠ 피해원을 {@code explosion(null, null)} 으로 골랐다</h2>
	 *
	 * <p>65 는 <b>무장하고도 한 대에 전멸</b>을 노린 값인데, 문서와
	 * {@code DragonLastStandTest.부채꼴_브레스는_피해원에_따라_전멸이_갈린다} 가 못박은 대로
	 * 답이 피해원에 따라 갈린다(팀 체력 20).
	 *
	 * <table border="1">
	 *   <caption>65 가 무장한 사람에게 실제로 들어가는 양</caption>
	 *   <tr><th>피해원</th><th>들어가는 값</th><th>한 대에 전멸인가</th></tr>
	 *   <tr><td>{@code lightningBolt()}</td><td>19.66</td><td><b>아니다</b> — 0.34 남는다</td></tr>
	 *   <tr><td><b>{@code explosion(null, null)}</b></td><td><b>29.48</b></td><td>그렇다 ← 고른 것</td></tr>
	 *   <tr><td>{@code magic()}</td><td>23.40</td><td>그렇다</td></tr>
	 * </table>
	 *
	 * <p>죽는 쪽 둘 가운데 {@code explosion} 을 고른 근거가 셋이다.
	 *
	 * <ol>
	 *   <li>⚠ <b>{@code magic} 은 {@code #bypasses_armor} 태그에 있어 방어도가 하나도 안 듣는다.</b>
	 *       이 판의 셈은 전부 「다이아 풀셋 + 보호 IV 를 지난 뒤」를 기준으로 서 있는데
	 *       ({@code GearedDamage}), 방어를 지나가는 피해원은 <b>갖춰도 줄지 않아</b> 그 전제와
	 *       성질이 다르다. 「표적」({@code TrialDragonFocus})이 그 쪽을 쓰면서 스스로
	 *       「이 카드만 방어도를 지나간다 — 적힌 6 은 다른 카드의 6 과 뜻이 다르다」라고 적어
	 *       두었다. 이 페이즈에서 그 예외를 하나 더 만들면 「무장 기준」이 카드마다 다른 말이 된다</li>
	 *   <li><b>{@code explosion} 이 이 판의 표준이다.</b> 「연쇄 포격」·「기둥 화염구」·「종말의 비」·
	 *       「착지 충격」·「엔더폭풍」이 모두 이것을 쓰고, {@code TrialEnderStorm} 이
	 *       <b>{@code magic}·{@code dragonBreath} 를 쓰지 않는 이유</b>를 같은 근거로 적어 두었다.
	 *       곧 「드래곤의 브레스니 {@code dragonBreath} 가 맞다」로 되돌리지 말 것 — 그쪽도
	 *       {@code #bypasses_armor} 다</li>
	 *   <li><b>폭발 보호가 그대로 듣는다.</b> 대비한 사람이 손해 보지 않는 쪽이고, 하드 곱(1.5)이
	 *       먼저 걸려 29.48 이라 <b>여유가 9.48</b> 이다 — 「하트 한 칸도 안 되는 차이」로
	 *       살아남는 일이 없다</li>
	 * </ol>
	 *
	 * <p>⚠ 맨몸이면 {@code 65 × 1.5 = 97.5} 가 그대로 들어간다. 「죽어서 장비를 잃고 돌아온
	 * 사람에게는 감쇠가 하나도 안 걸린다」가 이 저장소가 이미 알고 받아들인 사실이고
	 * ({@code GearedDamage} 의 「이 기준이 봐 주지 않는 사람」), 무장 기준에서 이미 한 대에
	 * 전멸이라 맨몸 쪽이 더 나빠지는 것은 없다.
	 *
	 * <h2>머리 고정은 {@code setYRot} 한 줄이다</h2>
	 *
	 * <p>{@code DragonLastStand.hold} 가 좌표를 못박아 바닐라가 yRot 을 건드리지 않으므로
	 * ({@code aiStep} 의 {@code abs(xdd) > 1e-5} 가지가 죽어 있다) 예고가 도는 동안 같은 값을
	 * 눌러 두면 그것으로 끝이다. 방향은 <b>예고 첫 틱에 한 번</b> 고르고
	 * ({@link #coneAimedFor}) 그 뒤로 다시 재지 않는다 — 다시 재면 4초 예고가 아무 뜻이 없다.
	 *
	 * <h2>부채꼴을 어떻게 그렸는가 — 네 갈래다</h2>
	 *
	 * <ol>
	 *   <li><b>빨간 테두리</b>(규약의 {@code DEADLY}, 「서 있으면 죽는다」) — 가장자리 두 줄 ·
	 *       사거리 호 · 가운데 호 하나. 이것이 <b>경계를 말하는 유일한 갈래</b>다</li>
	 *   <li><b>흰 벽</b>({@code CRIT})을 가장자리 두 줄에 세운다. 「착지 충격」이 쓰는 그 수법이고,
	 *       파티클 개수를 0 으로 보내면 뒤 값이 속도로 읽혀 <b>점을 한 개도 안 늘리고</b> 사람 키만 한
	 *       벽이 선다({@code TrialLandingShock.EDGE_RISE_SPEED} 에 그 계산이 있다). 부채꼴 안에 서
	 *       있는 사람은 바닥 선을 거의 못 보므로(시선과 나란하다) 이 벽이 「여기서부터 안전」을
	 *       말하는 갈래다. <b>면이 생겨도 이 벽을 걷지 않았다</b> — 근거가 한 줄도 바뀌지 않는다</li>
	 *   <li><b>빨간 투명 면</b>({@link DragonLastStandConePanel}) — 사람이 <b>「모든바닥이
	 *       위험지대라고 알수잇게」</b>라고 해서 바닥을 통째로 덮는다. 파티클이 아니라 디스플레이
	 *       개체라 <b>점 예산과 무관</b>하고, 대신 <b>개체라서 지워야 한다</b> —
	 *       {@link #fireCone} 이 터지는 틱에 지우고 {@link #clearState} 가 판이 끝날 때 지운다</li>
	 *   <li><b>불꽃</b>({@link #burnCone}) — 터지는 그 틱에만. 사람이 <b>「브레스쏠떄 불타는
	 *       이펙트가 있으면좋겟어」</b>라고 한 것이고, ⚠ <b>불을 실제로 붙이지 않는다</b>(아래)</li>
	 * </ol>
	 *
	 * <h2>⚠ 빨간 면은 터지는 틱에 사라진다 — 잔류처럼 보이면 배울 것이 없다</h2>
	 *
	 * <p>브레스가 터진 뒤의 바닥은 <b>안전하다</b>(피해는 그 한 틱뿐이다). 빨간 면이 불꽃 20틱 동안
	 * 더 남아 있으면 <b>이미 안전한 자리가 위험해 보인다</b> — 「연쇄 포격」이 터진 고리를 그 틱에
	 * 지우는 것과 같은 규칙이고({@code DragonFireBarrage} 의 「빨간 고리도 그 틱에 사라진다」),
	 * 빨간 테두리도 이미 그렇게 되어 있다({@link #markCone} 은 예고 중에만 불린다).
	 */
	private static void coneBreath(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			long at, long now, int step) {
		Vec3 apex = dragon.position();
		if (coneAimedFor != at) {
			coneAimedFor = at;
			coneYaw = aimYaw(apex, members, dragon.getYRot());
			// 방향이 정해진 그 틱에 면을 세운다. 자리가 예고 중에 바뀌지 않는 것이
			// 「즉사 허용」의 조건이므로 면도 한 번만 세우면 된다.
			DragonLastStandConePanel.raise(end, apex, coneYaw);
		}
		// 매 틱 같은 값을 누른다. hold 가 좌표를 못박아 두었으므로 이 한 줄이 「머리 고정」이다.
		dragon.setYRot(coneYaw);
		if (step < CONE_WARN_TICKS) {
			warnCone(end, members, apex, step);
			return;
		}
		if (step == CONE_WARN_TICKS) {
			fireCone(end, members, apex);
			return;
		}
		// 잔류가 아니다 — 파티클만 남고 피해는 위의 한 틱에 끝났다.
		sprayCone(end, apex, 1);
	}

	/**
	 * 겨눌 방향(도). 팀의 무게 중심이다.
	 *
	 * <p>{@code DragonLastStand.faceTeam} 과 <b>같은 계산</b>이다 — 규약이 갈리면 「앉을 때는
	 * 팀을 보더니 브레스는 엉뚱한 데를 본다」가 된다. {@code aiStep} 이 yRot 을 쓰는 식이
	 * {@code (sin(yRot), −cos(yRot))} 이 앞이라 역산이 {@code atan2(dx, −dz)} 다.
	 *
	 * <p>셀 사람이 없으면 지금 보고 있는 쪽을 그대로 쓴다. 0 으로 두면 팀이 다 관전 중인 판에서
	 * 부채꼴이 언제나 북쪽을 향한다.
	 */
	private static float aimYaw(Vec3 apex, List<ServerPlayer> members, float fallback) {
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
			return fallback;
		}
		double dx = x / counted - apex.x;
		double dz = z / counted - apex.z;
		if (Math.abs(dx) < 1.0E-4 && Math.abs(dz) < 1.0E-4) {
			return fallback;
		}
		return (float) Math.toDegrees(Math.atan2(dx, -dz));
	}

	/**
	 * 예고. 바닥 테두리와 흰 벽을 매 틱 다시 그리고, 층이 바뀌는 틱과 <b>1초마다</b> 소리를 낸다.
	 *
	 * <h2>소리가 두 겹이다 — 층 소리와 충전음</h2>
	 *
	 * <p>층 소리({@link TrialWarning#soundFor})는 <b>남겼다.</b> 그것이 이 판의 공용 경고라 사람이
	 * 카드마다 새 신호를 배우지 않는 근거이고({@link TrialWarning} 클래스 설명), 다른 카드와 같은
	 * 자리에서 같은 소리가 나야 한다.
	 *
	 * <p>그 위에 <b>충전음</b>을 얹는다. 왜 층 소리만으로는 안 들렸는지는
	 * {@link #CONE_CHARGE_TICKS} 에 적어 두었다.
	 *
	 * <p>고른 소리는 <b>{@code GHAST_WARN}</b> 이다. 근거가 둘이다.
	 *
	 * <ol>
	 *   <li>⚠ <b>이 판에서 쓰는 어느 소리와도 파일이 겹치지 않는다.</b> 바닐라
	 *       {@code sounds.json} 을 열어 확인했다 — {@code entity.ghast.warn} 이 가리키는 것은
	 *       {@code mob/ghast/charge} 하나이고 이 저장소의 어느 카드도 그 파일을 쓰지 않는다.
	 *       ⚠ <b>이름으로 고르면 걸린다</b>: {@code item.firecharge.use} 를 쓸 뻔했는데 그것이
	 *       가리키는 파일이 {@code mob/ghast/fireball4} 로 <b>{@code ENDER_DRAGON_SHOOT}(바로
	 *       아래 터지는 소리)·{@code GHAST_SHOOT}(기둥 화염구)와 글자 하나까지 같다.</b>
	 *       「종말의 비」가 {@code DRAGON_FIREBALL_EXPLODE} 로 이미 한 번 걸린 함정이다</li>
	 *   <li><b>뜻이 맞는다.</b> 가스트가 화염구를 모을 때 내는 소리라 「불을 모으고 있고 곧
	 *       쏜다」가 그대로다. 그리고 터지는 소리가 {@code mob/ghast/fireball4} 이므로
	 *       <b>「모으는 소리 → 쏘는 소리」가 바닐라 가스트와 같은 한 쌍</b>이 된다</li>
	 * </ol>
	 *
	 * <p>26.3 에서 {@code SoundEvents.GHAST_WARN} 은 맨 {@code SoundEvent} 다({@code javap} 로
	 * 확인했다). {@link TrialWarning#playEach} 의 그 형태가 레지스트리에서 감싸 준다.
	 */
	private static void warnCone(ServerLevel end, List<ServerPlayer> members, Vec3 apex, int step) {
		markCone(end, apex);
		int remaining = CONE_WARN_TICKS - step;
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage != null && TrialRisks.stageJustChanged(remaining, CONE_WARN_TICKS)) {
			// 사람마다 그 사람에게만 보낸다. TrialWarning.sound 는 반경 안 전원에게 나가므로
			// 모여 있는 넷이 각자 네 겹으로 듣는다.
			for (ServerPlayer member : members) {
				TrialWarning.soundFor(end, member, stage);
			}
		}
		if (step % CONE_CHARGE_TICKS == 0) {
			// playEach 다. 팀원 루프에서 level.playSound 를 부르면 모여 있는 넷이 네 겹으로 듣는다.
			TrialWarning.playEach(end, members, SoundEvents.GHAST_WARN, 0.9F, chargePitch(step));
		}
		// 입에서 불씨가 모인다. 「어디서 나올 것인가」를 드래곤 쪽에서도 말한다.
		end.sendParticles(ParticleTypes.SMALL_FLAME, true, false,
				apex.x, apex.y + 2.0, apex.z, 4, 0.6, 0.4, 0.6, 0.01);
	}

	/**
	 * 그 틱의 충전음 음높이. 낮은 데서 시작해 <b>마지막 한 번이 가장 높다.</b>
	 *
	 * <p>나누는 것이 {@code CONE_WARN_TICKS} 가 아니라 <b>마지막으로 울리는 틱</b>
	 * ({@code 80 − 20 = 60})이다. 예고 길이로 나누면 마지막 울림이 0.75 진행에 머물러
	 * {@link #CONE_CHARGE_PITCH_HIGH} 가 <b>한 번도 나지 않는다</b> — 「끝까지 올라갔다」가 안 들린다.
	 * 예고를 4초로 줄였어도 이 식이 그대로 맞는 까닭이 그것이다(값에서 셈한다).
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 네 번을 직접 굴려 본다.
	 */
	static float chargePitch(int step) {
		int last = CONE_WARN_TICKS - CONE_CHARGE_TICKS;
		if (last <= 0) {
			return CONE_CHARGE_PITCH_HIGH;
		}
		float progress = Math.max(0.0F, Math.min(1.0F, (float) step / last));
		return CONE_CHARGE_PITCH_LOW
				+ (CONE_CHARGE_PITCH_HIGH - CONE_CHARGE_PITCH_LOW) * progress;
	}

	/**
	 * 터진다. <b>피해는 이 한 틱에 한 사람당 한 번</b>이다.
	 *
	 * <p>이 틱에 <b>빨간 면을 지운다.</b> 터진 뒤의 바닥은 안전하므로 한 틱이라도 더 남기면
	 * 표식이 거짓말을 한다 — 까닭은 {@link #coneBreath} 에 적어 두었다.
	 */
	private static void fireCone(ServerLevel end, List<ServerPlayer> members, Vec3 apex) {
		DragonLastStandConePanel.drop();
		sprayCone(end, apex, 3);
		burnCone(end, apex);
		TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_SHOOT, 1.2F, 0.7F);
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			if (!insideCone(member.getX() - apex.x, member.getZ() - apex.z, coneYaw,
					CONE_RANGE, CONE_DEGREES / 2.0)) {
				continue;
			}
			// 피해원에 실체를 달지 않는다. 실체가 붙으면 바닐라가 스스로 밀어내고, 그 밀기는
			// 날개 퍼덕이기가 지키는 천장을 통째로 지나쳐 간다 — 그 길이 곧 공허다.
			member.hurtServer(end, end.damageSources().explosion(null, null),
					DragonLastStand.CONE_BREATH_DAMAGE);
		}
	}

	/**
	 * 터지는 <b>그 틱</b>에 부채꼴 바닥이 불타는 모습. <b>판정과 무관한 연출이다.</b>
	 *
	 * <h2>⚠ 불을 실제로 붙이지 않는다 — 세 가지를 하지 않는다</h2>
	 *
	 * <ul>
	 *   <li><b>블록을 놓지 않는다.</b> {@code fire} 를 한 칸이라도 놓으면 그 불이 사람이 쌓은 발판을
	 *       태워 <b>다음 전투의 지형이 달라진다</b> — 이 파일이 「블록을 한 칸도 건드리지
	 *       않는다」를 지키는 까닭과 같다. 하이트맵을 <b>읽는</b> 것만 한다</li>
	 *   <li><b>사람에게 불을 붙이지 않는다.</b> {@code setRemainingFireTicks} 는 <b>피해가 계속
	 *       들어오는 장치</b>라 「잔류 없음」을 그 자리에서 깬다. 공유 체력이라 넷이 함께 타면
	 *       초당 넷이 합산된다</li>
	 *   <li><b>장판({@code AreaEffectCloud})을 만들지 않는다.</b> 그것이 곧 잔류다</li>
	 * </ul>
	 *
	 * <p>남는 것은 <b>파티클 한 종류</b>다. {@code FLAME} 을 고른 것은 26.3 {@code FlameParticle} 의
	 * 수명이 {@code (int)(8.0 / (굴림 × 0.8 + 0.2))} 곧 <b>8~40틱</b>이라, <b>한 틱만 뿌려도 눈에는
	 * 0.4~2초 타오르는 것으로 보이기</b> 때문이다. 매 틱 뿌리면 그것이 잔류처럼 보이고,
	 * 안 뿌리면 「불탔다」가 한 틱에 끝나 아무것도 안 보인다.
	 *
	 * <p>{@code LAVA} 를 쓰지 않은 것은 그쪽이 <b>방울이 튀는 모양</b>이라 「용암이 고였다」로
	 * 읽히기 때문이다 — 이 패턴은 고인 것을 남기지 않는다.
	 *
	 * <p>허공에는 찍지 않는다. 찍으면 불꽃이 까마득한 아래에 떠 「저기가 바닥이다」라고 거짓말한다.
	 */
	private static void burnCone(ServerLevel end, Vec3 apex) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		double half = Math.toRadians(CONE_DEGREES / 2.0);
		double middle = Math.atan2(Math.sin(Math.toRadians(coneYaw)),
				-Math.cos(Math.toRadians(coneYaw)));
		for (double radius = FLAME_RING_STEP; radius <= CONE_RANGE; radius += FLAME_RING_STEP) {
			int points = arcPoints(radius, FLAME_ARC_GAP);
			for (int index = 0; index <= points; index++) {
				double angle = middle - half + (half * 2.0 * index) / points;
				double x = apex.x + Math.sin(angle) * radius;
				double z = apex.z + Math.cos(angle) * radius;
				int surface = ground.surfaceAt(end, x, z);
				if (surface == TrialEnderPulse.NO_GROUND) {
					continue;
				}
				end.sendParticles(ParticleTypes.FLAME, true, false,
						x, surface + GROUND_OFFSET, z, 3, 0.5, 0.1, 0.5, 0.03);
			}
		}
	}

	/**
	 * 부채꼴 테두리를 바닥에 그린다.
	 *
	 * <p>점 수는 값에서 나온다 — {@link #worstCasePointsPerTick} 이 같은 식으로 센다.
	 */
	private static void markCone(ServerLevel end, Vec3 apex) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		ParticleOptions deadly = TrialWarning.dust(TrialWarning.Colors.DEADLY);
		double half = Math.toRadians(CONE_DEGREES / 2.0);
		double forwardX = Math.sin(Math.toRadians(coneYaw));
		double forwardZ = -Math.cos(Math.toRadians(coneYaw));
		for (int side = -1; side <= 1; side += 2) {
			double angle = Math.atan2(forwardX, forwardZ) + half * side;
			double edgeX = Math.sin(angle);
			double edgeZ = Math.cos(angle);
			// 바닥 선.
			for (double along = MARK_GAP; along <= CONE_RANGE; along += MARK_GAP) {
				dot(end, ground, deadly, apex.x + edgeX * along, apex.z + edgeZ * along, 0.0);
			}
			// 흰 벽. 개수를 0 으로 보내면 뒤 값이 속도로 읽혀 점을 한 개도 안 늘리고 벽이 선다.
			for (double along = EDGE_WALL_GAP; along <= CONE_RANGE; along += EDGE_WALL_GAP) {
				dot(end, ground, ParticleTypes.CRIT, apex.x + edgeX * along, apex.z + edgeZ * along,
						TrialLandingShock.EDGE_RISE_SPEED);
			}
		}
		arc(end, ground, deadly, apex, CONE_RANGE, MARK_GAP);
		// 안쪽 호 셋. 하나뿐이던 때는 20칸짜리 부채꼴 안이 거의 비어 있었다.
		for (double fraction : CONE_ARC_FRACTIONS) {
			arc(end, ground, deadly, apex, CONE_RANGE * fraction, EDGE_WALL_GAP);
		}
	}

	/** 부채꼴의 호 하나. {@code gap} 이 점 사이 거리다. */
	private static void arc(ServerLevel end, TrialEnderPulse.Ground ground, ParticleOptions type,
			Vec3 apex, double radius, double gap) {
		double forwardX = Math.sin(Math.toRadians(coneYaw));
		double forwardZ = -Math.cos(Math.toRadians(coneYaw));
		double middle = Math.atan2(forwardX, forwardZ);
		double half = Math.toRadians(CONE_DEGREES / 2.0);
		int points = arcPoints(radius, gap);
		for (int index = 0; index <= points; index++) {
			double angle = middle - half + (half * 2.0 * index) / points;
			dot(end, ground, type, apex.x + Math.sin(angle) * radius,
					apex.z + Math.cos(angle) * radius, 0.0);
		}
	}

	/** 그 호에 찍을 점 수. 호 길이를 간격으로 나눈다. */
	static int arcPoints(double radius, double gap) {
		if (!(radius > 0.0) || !(gap > 0.0)) {
			return 1;
		}
		double length = Math.toRadians(CONE_DEGREES) * radius;
		return Math.max(1, (int) Math.ceil(length / gap));
	}

	/**
	 * 점 하나를 그 자리 지표에 찍는다.
	 *
	 * <p>{@code rise} 가 0 보다 크면 <b>개수를 0 으로 보낸다.</b> 그러면 뒤의 세 값이 방향이고
	 * 마지막이 속도로 읽혀 점 하나가 위로 쏘아진다 — 「착지 충격」이 점을 한 개도 안 늘리고
	 * 바닥 선을 벽으로 세운 방법이다.
	 *
	 * <p>땅이 없으면 찍지 않는다. 찍으면 고리가 까마득한 아래에 떠 「저기가 바닥이다」라고
	 * 거짓말을 한다.
	 */
	private static void dot(ServerLevel end, TrialEnderPulse.Ground ground, ParticleOptions type,
			double x, double z, double rise) {
		int surface = ground.surfaceAt(end, x, z);
		if (surface == TrialEnderPulse.NO_GROUND) {
			return;
		}
		if (rise > 0.0) {
			end.sendParticles(type, true, false, x, surface + GROUND_OFFSET, z,
					0, 0.0, 1.0, 0.0, rise);
			return;
		}
		end.sendParticles(type, true, false, x, surface + GROUND_OFFSET, z,
				1, 0.0, 0.0, 0.0, 0.0);
	}

	/**
	 * 점 하나를 그 자리 지표에서 <b>한 방향으로 흘려보낸다.</b> 이것이 「빨아들임」과 「밀어냄」을
	 * 가르는 유일한 갈래다.
	 *
	 * <p>{@link #dot} 의 {@code rise} 와 <b>같은 수법</b>이다 — 개수를 0 으로 보내면 뒤의 세 값이
	 * 방향이고 마지막이 속도로 읽혀 점 하나가 그쪽으로 쏘아진다. 그쪽은 위로 쏘아 벽을 세우고
	 * 여기는 <b>수평으로</b> 쏘아 흐름을 만든다.
	 *
	 * <p>⚠ <b>세로를 0 으로 둔다.</b> 흡입이 사람을 위로 당기지 않는데 입자가 위로 빨려 올라가면
	 * 그것이 거짓 신호다({@link #SUCK_MAX_INWARD}).
	 *
	 * <p>⚠ <b>{@code type} 은 {@code CRIT} 이어야 한다.</b> 먼지는 방향만 남기고 크기를 제멋대로
	 * 다시 굴려 0.15칸밖에 못 간다 — 근거는 {@link #CRIT_SPEED_FACTOR} 에 있다.
	 *
	 * <p>땅이 없으면 찍지 않는다. {@link #dot} 과 같은 까닭이다.
	 *
	 * @param dirX  흐를 방향의 x 성분. <b>단위 벡터여야 한다</b> — 크기는 {@code speed} 가 정한다
	 * @param dirZ  흐를 방향의 z 성분
	 * @param speed 쏘는 세기. 실제로 흐르는 거리는 {@link #critDrift} 가 센다
	 */
	private static void streak(ServerLevel end, TrialEnderPulse.Ground ground, ParticleOptions type,
			double x, double z, double dirX, double dirZ, double speed) {
		int surface = ground.surfaceAt(end, x, z);
		if (surface == TrialEnderPulse.NO_GROUND) {
			return;
		}
		end.sendParticles(type, true, false, x, surface + GROUND_OFFSET, z,
				0, dirX, 0.0, dirZ, speed);
	}

	/**
	 * 수평으로 쏜 {@code CRIT} 이 <b>수명이 가장 짧을 때도</b> 흐르는 거리(칸).
	 *
	 * <p>{@code TrialLandingShock.riseHeight} 와 같은 식인데 <b>중력이 없다</b> — 26.3
	 * {@code Particle.tick} 이 중력을 세로 속도에서만 빼기 때문이다(클래스 파일로 확인했다).
	 * 그래서 수평은 받은 속도의 0.4배에서 마찰 0.7 로만 줄어든다.
	 *
	 * <p><b>하한을 재는 것</b>이 요점이다. 수명이 4~10틱으로 굴려지므로 어떤 점은 일찍 죽는다 —
	 * 「가닥이 보인다」는 <b>가장 짧게 사는 점</b>으로도 참이어야 한다. 긴 쪽(10틱)은 1.3배다.
	 *
	 * <p>⚠ 바닥에 닿은 뒤에는 {@code Particle.tick} 이 수평 속도에 0.7 을 한 번 더 곱한다. 그래도
	 * {@value #CRIT_MIN_LIFETIME} 틱 안에는 닿지 않는다 — 중력이 {@code 0.04 × 0.5} 라 네 틱에
	 * 0.15칸을 겨우 내려오고 그것이 {@link #GROUND_OFFSET} 이다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 직접 굴린다.
	 */
	static double critDrift(double speed) {
		double velocity = Math.max(0.0, speed) * CRIT_SPEED_FACTOR;
		double drift = 0.0;
		for (int tick = 0; tick < CRIT_MIN_LIFETIME; tick++) {
			drift += velocity;
			velocity *= CRIT_FRICTION;
		}
		return drift;
	}

	/**
	 * 고리 하나를 <b>벽으로</b> 세운다. 점은 {@link #ringWallPoints} 개이고 전부 위로 쏘아진다.
	 *
	 * <p>나눠 그리지 않는다. {@code CRIT} 수명이 4~10틱이라 나눌 폭이 3 뿐인데
	 * ({@code TrialLandingShock.MAX_STRIDE}) 반경 4·12 짜리 고리 둘이 {@value #RING_WALL_GAP} 간격이면
	 * 통틀어 68점뿐이라 나눠서 얻을 것이 없다.
	 */
	private static void ringWall(ServerLevel end, TrialEnderPulse.Ground ground, Vec3 center,
			double radius) {
		int points = ringWallPoints(radius);
		for (int index = 0; index < points; index++) {
			double angle = (Math.PI * 2.0 * index) / points;
			dot(end, ground, ParticleTypes.CRIT, center.x + Math.cos(angle) * radius,
					center.z + Math.sin(angle) * radius, TrialLandingShock.EDGE_RISE_SPEED);
		}
	}

	/** 그 반경의 벽에 세울 점 수. 둘레를 {@value #RING_WALL_GAP} 으로 나눈다. */
	static int ringWallPoints(double radius) {
		if (!(radius > 0.0)) {
			return 1;
		}
		return Math.max(1, (int) Math.ceil((Math.PI * 2.0 * radius) / RING_WALL_GAP));
	}

	/**
	 * 부채꼴을 브레스 파티클로 채운다. 판정과 무관한 연출이다.
	 *
	 * <p>{@code DRAGON_BREATH} 는 26.3 에서 {@code PowerParticleOption} 을 받는다. 바닐라
	 * {@code DragonSittingFlamingPhase} 와 {@code DragonLandingPhase} 가 세기 {@code 1.0F} 로
	 * 쓰므로 같은 값을 쓴다 — 우리 브레스만 다른 모양이면 사람이 두 가지를 배운다.
	 *
	 * <p><b>장판({@code AreaEffectCloud})을 만들지 않는다.</b> 만들면 그것이 곧 잔류이고,
	 * 「잔류 없음」이 사람이 정한 것이다. {@code HOVERING} 으로 잠근 덕에 바닐라도 장판을
	 * 한 장 만들지 않는다.
	 */
	private static void sprayCone(ServerLevel end, Vec3 apex, int density) {
		ParticleOptions breath = PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F);
		double half = Math.toRadians(CONE_DEGREES / 2.0);
		double middle = Math.atan2(Math.sin(Math.toRadians(coneYaw)),
				-Math.cos(Math.toRadians(coneYaw)));
		for (double radius = 4.0; radius <= CONE_RANGE; radius += 4.0) {
			int points = arcPoints(radius, 2.0);
			for (int index = 0; index <= points; index++) {
				double angle = middle - half + (half * 2.0 * index) / points;
				end.sendParticles(breath, true, false,
						apex.x + Math.sin(angle) * radius, apex.y + 0.6,
						apex.z + Math.cos(angle) * radius, density, 0.4, 0.3, 0.4, 0.01);
			}
		}
	}

	/**
	 * 그 자리가 부채꼴 안인가.
	 *
	 * <p><b>세로는 보지 않는다.</b> {@code TrialRisks.insideMark} 와 같은 태도다 — 표식이 바닥에
	 * 그려지는데 「고리 위에 떠 있었으니 안 맞는다」가 되면 표식이 거짓말한 것이 된다.
	 *
	 * <p>꼭대기에 정확히 겹쳐 있으면 안이다. 드래곤 몸 안에 서 있는 경우이고, 거기서 방향을
	 * 지어내 빼 주면 「머리에 붙어 있으면 브레스가 안 맞는다」가 된다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 90도 경계와 20칸 경계를 직접 굴려 본다.
	 *
	 * @param dx      꼭대기에서 잰 x. 곧 {@code 사람 x − 드래곤 x}
	 * @param dz      꼭대기에서 잰 z
	 * @param yaw     고정해 둔 방향(도). {@code (sin(yaw), −cos(yaw))} 이 앞이다
	 * @param range   사거리(칸)
	 * @param halfDeg 부채꼴 반각(도). 90도짜리면 45 다
	 */
	static boolean insideCone(double dx, double dz, float yaw, double range, double halfDeg) {
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance > range) {
			return false;
		}
		if (distance < 1.0E-4) {
			return true;
		}
		double radians = Math.toRadians(yaw);
		double forwardX = Math.sin(radians);
		double forwardZ = -Math.cos(radians);
		double cosine = (dx * forwardX + dz * forwardZ) / distance;
		return cosine >= Math.cos(Math.toRadians(halfDeg));
	}

	// ------------------------------------------------------------------ ③ 공허 흡입

	/**
	 * 공허 흡입 — <b>드래곤 발밑</b>에 검은 원 · 반경 4칸 · 2초 예고(2026-10-04 에 3초 → 2초) → 5초 흡입 → 터짐 ·
	 * 피해 {@code DragonLastStand.VOID_SUCTION_DAMAGE}(35) · <b>잔류 없음.</b>
	 *
	 * <h2>⚠ 원은 <b>드래곤에게</b> 있다 — 무작위 자리가 아니다</h2>
	 *
	 * <p>사람이 한 번 고쳤다. 처음 말은 「그작은원안에 빨려들어가고」였고 그것을 아레나 어딘가의
	 * 작은 원으로 읽었는데, 사람이 <b>「흡입은 드래곤에 검은원이고 드래곤이 빨아드리는거임」</b>
	 * 이라고 못박았다. 그래서 중심이 <b>드래곤을 못박아 둔 자리</b>이고 여기서 자리를 굴리지
	 * <b>않는다.</b>
	 *
	 * <p>그것이 이 패턴의 뜻이다. 이 페이즈의 핵심이 「드래곤 머리에 붙어 때리기」인데
	 * ({@code DragonLastStand} 의 「닿기 쉬워지는 것」) 이것이 나오면 <b>때리던 자리가 곧 위험한
	 * 자리</b>가 된다 — <b>붙어 있던 사람을 그 자리에서 쫓아낸다.</b>
	 *
	 * <h2>세 토막이다</h2>
	 *
	 * <table border="1">
	 *   <caption>{@code step} 으로 읽는 세 토막</caption>
	 *   <tr><th>{@code step}</th><th>무엇을 하는가</th></tr>
	 *   <tr><td>{@code 0 ~ 39}</td><td><b>예고.</b> 검은 원과 빨간 경계를 그린다. <b>당기지 않는다</b></td></tr>
	 *   <tr><td>{@code 40 ~ 139}</td><td><b>흡입.</b> 계속 그리면서 매 틱 당긴다</td></tr>
	 *   <tr><td>{@code 140}</td><td><b>터짐.</b> 원 안이면 피해 한 번. 표식은 이 틱에 사라진다</td></tr>
	 *   <tr><td>{@code 141 ~ 159}</td><td>파티클만. 바닥은 이미 안전하다</td></tr>
	 * </table>
	 *
	 * <p>(예고가 3초이던 때는 {@code 0~59 · 60~159 · 160 · 161~179} 였다 — 사람이 2026-10-04 에
	 * 「공허 흡입 예고 2초로」라고 해서 앞 토막만 20틱 줄었다. {@link #SUCK_WARN_TICKS} 를 볼 것.)
	 *
	 * <p>⚠ <b>예고 중에 당기지 않는 것이 「달리면 벗어난다」의 절반이다.</b> 2초는 검은 원을 보고
	 * 방향을 정하는 시간이고, 그 2초에 달리면 11칸을 간다(3초이던 때 17칸) — {@link #SUCK_SPRINT_RATIO}
	 * 의 표를 볼 것.
	 *
	 * <h2>⚠ 입자가 <b>드래곤 쪽으로</b> 흐른다 — 사람이 그것을 말했다</h2>
	 *
	 * <p><b>「빨아드리는거랑 밀치는거랑 이펙트가 너무 구분이안됨 … 예를들어 빨아드리는 패턴이면
	 * 입자들이 드래곤에게 빨려들어가는 입자가 잘보이면 이해하잖아」</b>
	 *
	 * <p>전에 있던 것(검은 원 · 빨간 고리 · 끌려가는 사람 발밑의 재) 셋이 모두 <b>가만히 있는
	 * 표식</b>이라 날개 퍼덕이기의 파랑 고리 둘과 「모양만 다른 원」으로 보였다. {@link #streamSuck}
	 * 이 그 답이고, <b>예고 2초 동안에도 흐른다</b> — 어느 쪽으로 달릴지 정하는 그 2초에 가장
	 * 보여야 한다.
	 *
	 * <p>⚠ <b>입자도 세로를 한 톨도 쓰지 않는다.</b> 아래 세로 규칙과 같은 자리다 — 사람이 들리지
	 * 않는데 입자가 위로 빨려 올라가면 그것이 거짓 신호다.
	 *
	 * <h2>피해원은 {@code lightningBolt()} 다</h2>
	 *
	 * <p>사람이 적어 둔 것이 <b>「피해 35(무장 기준 6.9)」</b>이므로 피해원이 그 셈을 만들어야
	 * 한다 — {@code GearedDamage} 로 풀면 {@code lightningBolt} 가 <b>6.93</b> 이고
	 * {@code explosion(null, null)} 은 10.4 라 사람이 적은 수와 다르다. 곧 <b>값이 피해원을
	 * 정했다.</b>
	 *
	 * <p>이름이 어색한 것은 알고 고른 것이다. 이 저장소는 {@code lightningBolt()} 를 <b>「하드 곱이
	 * 안 걸리고 방어도는 듣는 피해원」</b>으로 쓰고 있고 실제로 번개가 아닌 자리에서도 쓴다 —
	 * {@code DragonLastStandZone.punish}(안전지대 밖 피해)가 그 선례다. {@code magic} 을 안 쓴
	 * 까닭은 부채꼴 브레스와 같다: {@code #bypasses_armor} 라 <b>무장해도 줄지 않아</b> 이 판의
	 * 모든 셈이 서 있는 「무장 기준」과 성질이 다르다.
	 *
	 * <h2>⚠⚠ 불 결계 (2026-10-04) — 당김을 35% 깎은 「대신」이다</h2>
	 *
	 * <p>사람 말: <b>「빨아들이는 힘이 35프로 감소시키는 대신, 드래곤 주위에 있으면 계속 딜 맞게. 그리고
	 * 옆에 있으면 딜 맞는다를 알기 쉽게 드래곤 주위에 불 결계가 생기게」</b>.
	 *
	 * <table border="1">
	 *   <caption>결계가 하는 일</caption>
	 *   <tr><th>{@code step}</th><th>보이는가</th><th>아픈가</th></tr>
	 *   <tr><td>{@code 0 ~ 39}(예고)</td><td><b>그렇다</b> — 불꽃 벽이 선다</td><td>아니다</td></tr>
	 *   <tr><td>{@code 40 ~ 139}(흡입)</td><td>그렇다</td><td><b>40·60·80·100·120</b> 에 원 안이면
	 *       {@link #SUCK_FIRE_DAMAGE} 한 대씩 — 다섯 번(예고 3초이던 때 60·80·100·120·140)</td></tr>
	 *   <tr><td>{@code 140}(터짐)</td><td>사라진다</td><td>터짐 35 만. ⚠ 결계는 그 앞 <b>20틱</b>에
	 *       멈춰 있다({@link #HURT_COOLDOWN_GUARD_TICKS})</td></tr>
	 * </table>
	 *
	 * <p>⚠ <b>사람을 불붙이지 않는다.</b> 불이 붙으면 결계 밖으로 나가도 계속 타 「결계 안에서만
	 * 딜」이 거짓이 되고, 공유 체력이라 넷이 함께 타면 합산된다. 그래서 피해는 우리가 직접
	 * {@code hurtServer} 로 넣고 불꽃은 파티클뿐이다({@link #burnCone} 이 「불을 실제로 붙이지 않는다」
	 * 를 지키는 것과 같다).
	 *
	 * <h2>⚠ 브레스처럼 잠그지 않았다 — 축소와 겹쳐도 된다</h2>
	 *
	 * <p>브레스의 「축소 중 금지」는 <b>피할 곳이 두 번 사라지기</b> 때문에 붙었다. 흡입은
	 * <b>피하는 방향이 바깥</b>이고 지대의 원도 <b>바깥에서 안으로</b> 오므로 위협이 겹치지 않는다 —
	 * 그리고 벗어나야 하는 것이 반경 4 뿐인데 지대의 가장 좁은 반경이 12 라 <b>언제나 8칸이
	 * 남는다.</b> 게다가 이 카드는 즉사가 아니다(무장 기준 6.93, 세 대에 전멸).
	 *
	 * <p>축소가 사람을 지대 밖에 두고 지나간 경우에는 흡입이 <b>도로 안으로 데려온다</b> —
	 * 방향이 안쪽이라 그렇다.
	 *
	 * @param step 이 패턴이 시작한 뒤 지난 틱
	 */
	private static void voidSuction(ServerLevel end, EnderDragon dragon,
			List<ServerPlayer> members, int step) {
		Vec3 center = dragon.position();
		if (step < SUCK_WARN_TICKS + SUCK_PULL_TICKS) {
			markSuck(end, center, step);
			warnSuckSound(end, members, step);
			if (step >= SUCK_WARN_TICKS) {
				pullSuck(end, members, center, step);
			}
			if (step == 0 || suckFireDue(step)) {
				// 결계가 서는 틱과 결계가 때리는 틱에 한 번씩. 「치익」이 딜과 같은 박자라 「지금
				// 맞았다」가 소리로도 들린다. playEach 다 — 팀원 루프에서 level.playSound 를 부르면
				// 모여 있는 넷이 네 겹으로 듣는다.
				TrialWarning.playEach(end, members, SoundEvents.FIRE_AMBIENT, 1.0F, 0.8F);
			}
			if (suckFireDue(step)) {
				burnInside(end, members, center);
			}
			return;
		}
		if (step == SUCK_WARN_TICKS + SUCK_PULL_TICKS) {
			burstSuck(end, members, center);
			return;
		}
		// 잔류가 아니다 — 파티클만 남고 피해는 위의 한 틱에 끝났다.
		sprayBurst(end, center, 1);
	}

	/**
	 * 검은 원과 빨간 경계.
	 *
	 * <p>속은 {@link #SUCK_MARK_STRIDE} 로 나눠 채우고 <b>경계 고리는 매 틱 전부</b> 그린다.
	 * 경계가 성기면 「어디까지가 아픈가」가 흐려지는데 이 원은 반경이 4밖에 안 돼 고리 한 바퀴가
	 * 51점뿐이다 — 나눌 이유가 없다.
	 */
	private static void markSuck(ServerLevel end, Vec3 center, int step) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		ParticleOptions black = TrialWarning.dust(SUCK_BLACK);
		int index = 0;
		for (double radius = MARK_GAP; radius < SUCK_RADIUS; radius += MARK_GAP) {
			int points = suckFillPoints(radius);
			for (int point = 0; point < points; point++) {
				// 나눠 그리기를 고리마다 따로 세지 않는다. 고리마다 세면 점이 적은 안쪽 고리가
				// 매 틱 같은 자리만 찍혀 바람개비처럼 돈다 — 「원 전체에서 몇 번째 점인가」로 센다.
				if (Math.floorMod(index++ - step, SUCK_MARK_STRIDE) != 0) {
					continue;
				}
				double angle = (Math.PI * 2.0 * point) / points;
				dot(end, ground, black, center.x + Math.cos(angle) * radius,
						center.z + Math.sin(angle) * radius, 0.0);
			}
		}
		// 경계는 규약의 빨강이다. 「서 있으면 죽는다」를 말하는 갈래는 여기 하나다.
		Vec3 floor = groundedCenter(end, ground, center);
		TrialWarning.markGround(end, floor, SUCK_RADIUS,
				TrialWarning.dust(TrialWarning.Colors.DEADLY));
		// 그 경계를 벽으로도 세운다. 원 안에 서 있는 사람은 바닥 고리를 시선과 나란하게 본다.
		ringWall(end, ground, floor, SUCK_RADIUS);
		// 바깥에서 드래곤 쪽으로 흐르는 가닥. 사람이 말한 그대로다 — 「입자들이 드래곤에게
		// 빨려들어가는 입자가 잘보이면 이해하잖아」.
		streamSuck(end, ground, center, step);
		// 불 결계. 예고 첫 틱부터 선다 — 「옆에 있으면 딜 맞는다를 알기 쉽게」.
		fireWall(end, ground, center, step);
	}

	/**
	 * ⚠ <b>불 결계의 불꽃 벽.</b> 반경 {@link #SUCK_FIRE_RADIUS} 둘레에서 불꽃이 무릎~사람 키까지
	 * 피어오른다.
	 *
	 * <p>{@link #dot} 의 {@code rise} 와 같은 수법이다 — 개수를 0 으로 보내면 뒤의 세 값이 방향이고
	 * 마지막이 속도로 읽혀 <b>점 하나가 위로 쏘아진다</b>. 불꽃은 중력이 없고 마찰 0.96 으로만
	 * 줄어 {@link #flameRise} 만큼 오르다 사그라든다. 자리마다 3틱마다 새 불꽃이 오르므로
	 * ({@link #SUCK_FIRE_STRIDE}) 한 자리에 높이가 다른 불꽃이 여럿 서 있어 <b>벽</b>으로 읽힌다.
	 *
	 * <p>⚠ <b>빨간 고리 점 사이에 선다.</b> 같은 원이고 같은 점 수({@code TrialWarning.ringPoints})라
	 * 반 칸 돌려 놓지 않으면 불꽃이 빨간 점을 정확히 덮는다 — 반 칸 돌리면 「여기가 경계」(빨강)와
	 * 「여기가 불」(주황)이 번갈아 보인다.
	 *
	 * <p>⚠ <b>불을 실제로 붙이지 않는다</b>({@link #voidSuction} 의 결계 절). 블록도 놓지 않는다.
	 *
	 * <p>허공에는 찍지 않는다. {@link #dot} 과 같은 까닭이다.
	 */
	private static void fireWall(ServerLevel end, TrialEnderPulse.Ground ground, Vec3 center,
			int step) {
		int points = TrialWarning.ringPoints(SUCK_FIRE_RADIUS);
		for (int index = 0; index < points; index++) {
			if (Math.floorMod(index - step, SUCK_FIRE_STRIDE) != 0) {
				continue;
			}
			double angle = (Math.PI * 2.0 * (index + 0.5)) / points;
			double x = center.x + Math.cos(angle) * SUCK_FIRE_RADIUS;
			double z = center.z + Math.sin(angle) * SUCK_FIRE_RADIUS;
			int surface = ground.surfaceAt(end, x, z);
			if (surface == TrialEnderPulse.NO_GROUND) {
				continue;
			}
			end.sendParticles(ParticleTypes.FLAME, true, false, x, surface + GROUND_OFFSET, z,
					0, 0.0, 1.0, 0.0, SUCK_FIRE_RISE);
		}
	}

	/**
	 * 위로 쏜 불꽃이 <b>수명 {@code lifetime} 틱 동안</b> 올라가는 높이(칸).
	 *
	 * <p>26.3 {@code RisingParticle} 은 중력이 없고 매 틱 속도에 {@value #FLAME_FRICTION} 을 곱한다 —
	 * 곧 {@code v + 0.96v + 0.96²v + …} 를 수명만큼 더한 값이다. 바닐라가 처음 속도에 더하는 굴림은
	 * {@code 0.01 × (±0.06 남짓)} 이라 셈에서 뺐다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 수명의 양 끝(8 · 40틱)을 직접 굴린다.
	 */
	static double flameRise(double speed, int lifetime) {
		double velocity = Math.max(0.0, speed);
		double height = 0.0;
		for (int tick = 0; tick < lifetime; tick++) {
			height += velocity;
			velocity *= FLAME_FRICTION;
		}
		return height;
	}

	/**
	 * ⚠⚠ 이 틱에 불 결계가 <b>때리는가.</b> 흡입 구간 동안 {@link #SUCK_FIRE_PERIOD_TICKS} 마다,
	 * <b>단 터짐 직전 {@link #HURT_COOLDOWN_GUARD_TICKS} 틱 안은 빼고</b>.
	 *
	 * <p>뒤의 조건이 이 메서드의 전부다. 결계 한 대가 터짐 10틱 안에 들어가면 바닐라 피격 무적이
	 * 터짐 35 를 <b>차액(31)만</b> 넣거나 통째로 막는다 — 근거는 {@link #HURT_COOLDOWN_GUARD_TICKS} 에
	 * 있다. 지금 값(주기 20)으로는 마지막 결계가 120 이고 터짐이 140 이라(예고 3초이던 때 140 · 160)
	 * 그 조건이 걸리지 않지만, <b>주기를 고치는 사람이 그 셈을 다시 하지 않아도</b> 터짐이 온전하도록
	 * 조건으로 적어 둔다. 예고 길이는 이 간격을 바꾸지 못한다 — 결계도 터짐도 예고 끝에서 잰다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 패턴 전체(160틱)를 통째로 훑는다.
	 */
	static boolean suckFireDue(int step) {
		int burst = SUCK_WARN_TICKS + SUCK_PULL_TICKS;
		if (step < SUCK_WARN_TICKS || step >= burst) {
			return false;
		}
		if ((step - SUCK_WARN_TICKS) % SUCK_FIRE_PERIOD_TICKS != 0) {
			return false;
		}
		return burst - step > HURT_COOLDOWN_GUARD_TICKS;
	}

	/**
	 * 불 결계 한 대. <b>원 안의 사람마다 한 번</b>이다.
	 *
	 * <p>판정은 터짐과 같은 {@code TrialRisks.insideMark}(세로를 보지 않는 원)다 — 같은 원이 두 자로
	 * 재어지면 「결계 안이었는데 터짐은 안 맞았다」 같은 어긋남이 생긴다.
	 *
	 * <p>⚠ <b>불을 붙이지 않는다</b>({@code setRemainingFireTicks}·{@code igniteForSeconds} 를 부르지
	 * 않는다). 피해원에 실체도 달지 않는다 — 실체가 붙으면 바닐라가 스스로 밀어낸다.
	 */
	private static void burnInside(ServerLevel end, List<ServerPlayer> members, Vec3 center) {
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			if (!TrialRisks.insideMark(member.position(), center, SUCK_FIRE_RADIUS)) {
				continue;
			}
			member.hurtServer(end, end.damageSources().lightningBolt(), SUCK_FIRE_DAMAGE);
		}
	}

	/**
	 * ⚠ <b>바깥에서 드래곤 쪽으로 흐르는 가닥.</b> 이 메서드가 사람의 지적에 대한 답이다.
	 *
	 * <p>생기는 자리가 {@link #suckStreamRadius} 로 <b>안쪽으로 들어오고</b>, 그 자리마다 가닥
	 * 하나가 다시 <b>안쪽으로</b> 흐른다. 곧 <b>자리도 흐름도 둘 다 드래곤 쪽</b>이다 — 하나만
	 * 안쪽이면 「원이 작아진다」나 「점이 떨린다」로 읽힌다.
	 *
	 * <p>날개 퍼덕이기의 {@link #wingWave} 와 <b>글자 그대로 반대</b>다. 같은 {@code CRIT} ·
	 * 같은 갈래 수({@link #SUCK_STREAM_SPOKES}) · 같은 세기({@link #SUCK_STREAM_DRIFT})이고
	 * 부호만 뒤집혀 있다. 종류나 세기를 달리하면 「다른 패턴이다」만 말하고 「반대되는
	 * 패턴이다」는 말하지 못한다.
	 *
	 * <p>⚠ <b>예고 2초 동안에도 흐른다.</b> 손은 아직 닿지 않지만({@link #voidSuction}) 그 2초가
	 * 「어느 쪽으로 달릴지 정하는」 시간이라 <b>그때 가장 보여야 한다.</b> 흐름이 당김과 함께
	 * 시작하면 이미 늦다.
	 */
	private static void streamSuck(ServerLevel end, TrialEnderPulse.Ground ground, Vec3 center,
			int step) {
		for (int phase = 0; phase < SUCK_STREAM_PHASES; phase++) {
			double radius = suckStreamRadius(step, phase);
			for (int spoke = 0; spoke < SUCK_STREAM_SPOKES; spoke++) {
				// 벌마다 갈래를 반 칸 어긋나게 둔다. 겹쳐 두면 같은 줄에 두 점이 포개진다.
				double angle = (Math.PI * 2.0 * spoke) / SUCK_STREAM_SPOKES
						+ (Math.PI * phase) / SUCK_STREAM_SPOKES;
				double outX = Math.cos(angle);
				double outZ = Math.sin(angle);
				streak(end, ground, ParticleTypes.CRIT, center.x + outX * radius,
						center.z + outZ * radius, -outX, -outZ, SUCK_STREAM_DRIFT);
			}
		}
	}

	/**
	 * 그 틱에 그 벌의 가닥이 생기는 반경(칸). <b>{@link #SUCK_REACH}(20)에서
	 * {@link #SUCK_RADIUS}(4)까지 되풀이해 들어온다.</b>
	 *
	 * <p>{@link #SUCK_RADIUS} 아래로 내려가지 않는 것은 그 안이 <b>터질 때 아픈 자리</b>라 검은
	 * 속이 이미 채워져 있기 때문이다 — 그 위에 흰 가닥을 겹치면 검정이 묻힌다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 예고 + 흡입(140틱)을 통째로 훑어 <b>언제나 4~20 사이</b>인지,
	 * 그리고 벌 둘이 <b>서로 다른 반경</b>에 있는지 본다.
	 *
	 * @param phase 몇 번째 벌인가. 벌마다 한 바퀴를 고르게 나눠 어긋난다
	 */
	static double suckStreamRadius(int step, int phase) {
		double span = SUCK_REACH - SUCK_RADIUS;
		double offset = (span * Math.floorMod(phase, SUCK_STREAM_PHASES)) / SUCK_STREAM_PHASES;
		double travelled = (Math.max(0, step) * SUCK_STREAM_SPEED + offset) % span;
		return SUCK_REACH - travelled;
	}

	/** 속을 채우는 고리 한 겹의 점 수. 값에서 세는 자리와 그리는 자리가 같은 식이어야 한다. */
	static int suckFillPoints(double radius) {
		if (!(radius > 0.0)) {
			return 1;
		}
		return Math.max(1, (int) Math.ceil((Math.PI * 2.0 * radius) / MARK_GAP));
	}

	/**
	 * 경계 고리를 그릴 중심. <b>바닥 높이로 내려 잡는다.</b>
	 *
	 * <p>{@code TrialWarning.markGround} 는 중심의 {@code y} 를 그대로 쓰므로 드래곤 발밑을 그냥
	 * 넘기면 고리가 <b>포디움 높이에 떠서</b> 섬 표면에 선 사람 눈에는 허공에 뜬 고리가 된다
	 * ({@link DragonLastStandDome#domeOriginY} 의 「섬 표면보다 네 칸 높다」). 표식이 거짓말하지 않게
	 * 그 자리의 지표로 내린다 — 땅이 없으면 원래 값을 쓴다(그 경우 그릴 자리가 애초에 없다).
	 */
	private static Vec3 groundedCenter(ServerLevel end, TrialEnderPulse.Ground ground, Vec3 center) {
		int surface = ground.surfaceAt(end, center.x, center.z);
		if (surface == TrialEnderPulse.NO_GROUND) {
			return center;
		}
		return new Vec3(center.x, surface, center.z);
	}

	/**
	 * 예고와 흡입의 소리.
	 *
	 * <p>층 소리({@link TrialWarning#soundFor})는 예고 2초 동안만이다 — 그것이 이 판의 공용
	 * 경고라 사람이 카드마다 새 신호를 배우지 않는 근거다({@link TrialWarning} 클래스 설명).
	 *
	 * <p>⚠ 예고가 2026-10-04 에 3초 → 2초가 되어(사람 말 「공허 흡입 예고 2초로」) 층 소리가
	 * <b>셋에서 둘</b>이다 — 남은 40틱은 처음부터 MARK 구간이라 APPROACH 가 빠지고, 첫 틱의 MARK 는
	 * {@code stageJustChanged} 의 {@code lead} 가 살린다. 흡입음은 예고 길이와 무관하다.
	 *
	 * <p>흡입이 시작되면 그 위에 <b>{@code BREEZE_INHALE}</b> 을 0.5초마다 얹는다. 「빨려들어가고
	 * 있다」는 <b>되풀이</b>로만 말할 수 있고 한 번 울리는 소리로는 못 한다(부채꼴의 충전음이 같은
	 * 근거로 있다).
	 *
	 * <p>⚠ <b>{@code sounds.json} 을 열어 고른 소리다.</b> {@code entity.breeze.inhale} 이
	 * 가리키는 파일은 {@code mob/breeze/inhale1}·{@code inhale2} 이고 <b>이 저장소의 어느 카드도
	 * 그 파일을 쓰지 않는다</b>(「엔더폭풍」이 쓰는 {@code BREEZE_JUMP}·
	 * {@code BREEZE_WIND_CHARGE_BURST} 와도 파일이 다르다). 이름으로 고르면 걸린다 — 이 저장소가
	 * 두 번 걸렸다({@code DRAGON_FIREBALL_EXPLODE} 가 일반 폭발음과 같은 파일이었고
	 * {@code FIRECHARGE_USE} 가 {@code ENDER_DRAGON_SHOOT} 과 같은 파일이었다).
	 *
	 * <p>뜻도 맞는다 — 브리즈가 <b>숨을 들이마시는</b> 소리다.
	 */
	private static void warnSuckSound(ServerLevel end, List<ServerPlayer> members, int step) {
		if (step < SUCK_WARN_TICKS) {
			int remaining = SUCK_WARN_TICKS - step;
			TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
			if (stage != null && TrialRisks.stageJustChanged(remaining, SUCK_WARN_TICKS)) {
				for (ServerPlayer member : members) {
					TrialWarning.soundFor(end, member, stage);
				}
			}
			return;
		}
		if ((step - SUCK_WARN_TICKS) % SUCK_BREATH_TICKS != 0) {
			return;
		}
		// playEach 다. 팀원 루프에서 level.playSound 를 부르면 모여 있는 넷이 네 겹으로 듣는다.
		TrialWarning.playEach(end, members, SoundEvents.BREEZE_INHALE, 1.0F, 0.6F);
	}

	/**
	 * 한 틱 당긴다. <b>수평만</b> 당기고 결과 속도에 천장을 씌운다.
	 *
	 * <p>왜 세로를 안 건드리고 왜 천장이 필요한지는 {@link #SUCK_MAX_INWARD} 에 있다. 손이 닿는
	 * 거리는 {@link #SUCK_REACH}(20칸)이고 세기는 거리에 따라 <b>변하지 않는다.</b>
	 *
	 * <p>⚠ <b>세기를 거리로 깎지 않은 것이 일부러다.</b> 깎으면 「달리면 벗어난다」가 거리마다 다른
	 * 답이 된다 — 사람이 한 약속은 하나이고 그것이 <b>어디서나</b> 참이어야 한다. 날개 퍼덕이기가
	 * 거리로 세기를 나눈 것은 그쪽이 <b>「4칸 이내는 약하고」</b>라는 사람의 말을 지키는 것이라
	 * 사정이 다르다.
	 *
	 * <p>닿는 거리 끝(20칸)에서 세기가 뚝 끊기는 것은 남아 있다. 지대가 마지막 반경 12 로 좁혀지면
	 * 그 경계가 <b>지대 밖</b>이라 거기 선 사람은 이미 밖 피해를 받는 중이고(2026-10-04 부터 원 밖으로
	 * 걸어 나갈 수 있다), 그 끊김이 판을 가르는 것은 지대가 넓은 앞구간뿐이다.
	 *
	 * <h2>⚠⚠ 당김을 <b>덮어쓰지 않고 더한다</b> (2026-10-04 저녁)</h2>
	 *
	 * <p>사람 말: <b>「빨아들이는 거 아직 너무 셈. 적어도 반대로 달려서 저항할 수 있을 만큼」</b>. 표에는
	 * 「달리면 초당 +2.56칸」이 적혀 있었는데 사람은 달려도 끌려갔다. <b>값이 아니라 배달이 셈과 달랐다.</b>
	 * 26.3 바이트코드로 따라간 길이다.
	 *
	 * <ol>
	 *   <li>옛 줄은 서버쪽 {@code deltaMovement} 에 당김을 더하고 {@code syncVelocity} 를 켰다.
	 *       {@code ServerEntity.sendChanges} 가 그 깃발을 보면 {@code ClientboundSetEntityMotionPacket}
	 *       ({@code getDeltaMovement()} 통째)을 <b>본인에게도</b> 보낸다 — 매 틱이다</li>
	 *   <li>받는 쪽 {@code ClientPacketListener.handleSetEntityMotion} → {@code Entity.lerpMotion} →
	 *       {@code setDeltaMovement} 다. <b>더하지 않고 바꿔 끼운다</b></li>
	 *   <li>서버의 {@code deltaMovement} 에는 <b>사람 입력이 없다.</b> 서버도 사람의 이동을 굴리지만
	 *       ({@code AUTHORITATIVE_SIDE_AND_SERVER}) 서버쪽 {@code xxa}·{@code zza} 는 0 이고, 움직임 패킷은
	 *       자리({@code setKnownMovement})만 넣고 속도는 안 넣는다. 그러니 서버의 그 수는 <b>우리가 더한
	 *       당김의 메아리</b>뿐이고 매 틱 당김 천장(0.1582)에 붙어 있었다</li>
	 *   <li>그래서 사람이 달려서 쌓은 속도가 <b>매 틱 지워졌다.</b> 한 틱에 남는 것은 그 틱의 입력 한 번
	 *       (달리기 0.1274)뿐이고 그것이 0.1582 를 못 이겨 <b>달려도 초당 0.62칸 · 걸으면 1.20칸
	 *       끌려들었다.</b> 가만히 선 사람(초당 3.16칸)만 표와 같았다 — 지울 속도가 없으니까</li>
	 * </ol>
	 *
	 * <p>날개 퍼덕이기({@link #shove})는 같은 덮어쓰기를 <b>12틱에 한 번</b> 하므로 사이의 11틱 동안
	 * 사람 입력이 살아 있다. 매 틱 덮어쓰는 것은 여기 하나였다.
	 *
	 * <p>그래서 바닐라가 <b>사람에게 넉백을 더할 때 쓰는 길</b>로 보낸다 — {@code ClientboundExplodePacket}
	 * 의 {@code playerKnockback}. 받는 쪽이 {@code Entity.pushFromExplosion} → {@code push} 로
	 * <b>지금 속도에 더한다</b>(26.3 바이트코드로 확인했다). 바닐라 폭발이 사람을 밀 때 쓰는 바로 그
	 * 필드다. 소리는 {@code playSound = false} 에 빈 소리까지 겹쳐 끄고, 블록 조각은 빈 목록이라 안
	 * 나오며, 남는 입자 하나는 {@link #SUCK_PACKET_HIDE_LIFT} 가 안 보이는 자리로 보낸다.
	 *
	 * <p>⚠ 덕분에 <b>서버의 {@code deltaMovement} 를 아예 안 건드린다.</b> {@code syncVelocity} 도 안 켜므로
	 * 바닐라 드래곤이 서버쪽에 쌓아 둔 세로·수평이 배달될 길이 없고({@link TrialVelocity} 의 「지나는 자리」
	 * 에서 이 메서드가 빠졌다), 사람의 점프도 한 톨도 안 깎인다.
	 */
	private static void pullSuck(ServerLevel end, List<ServerPlayer> members, Vec3 center,
			int step) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			double dx = member.getX() - center.x;
			double dz = member.getZ() - center.z;
			double from = Math.sqrt(dx * dx + dz * dz);
			if (from > SUCK_REACH || from < 1.0E-4) {
				// 정확히 겹쳐 있으면 「안쪽」이 없다. 방향을 지어내지 않는다.
				continue;
			}
			double outX = dx / from;
			double outZ = dz / from;
			// 사람이 지난 틱에 실제로 간 만큼. 서버의 deltaMovement 는 사람 입력이 없는 서버 혼자의
			// 수라 「사람의 속도」로 읽으면 안 된다 — 옛 줄이 그 수를 읽고 그 수로 덮어썼다.
			Vec3 known = member.getKnownMovement();
			double pull = suckImpulse(known.x * outX + known.z * outZ,
					isAirborne(end, ground, member));
			if (!(pull > 0.0)) {
				continue;
			}
			// 세로는 0 이다. 위로 당기면 사람이 들려 공중 감쇠에 들어가고 그때는 달려도 못
			// 벗어난다 — SUCK_MAX_INWARD 를 볼 것.
			sendPull(member, -outX * pull, -outZ * pull);
			if (step % SUCK_BREATH_TICKS == 0) {
				// 끌려가는 모습. 사람 발밑에서 흐르는 재다.
				end.sendParticles(ParticleTypes.ASH, true, false,
						member.getX(), member.getY() + 0.1, member.getZ(), 6, 0.3, 0.3, 0.3, 0.02);
			}
		}
	}

	/**
	 * 당김 한 번치를 그 사람의 <b>지금 속도에 더하게</b> 보낸다. 왜 이 패킷인지는 {@link #pullSuck} 에 있다.
	 *
	 * <p>⚠ {@code syncVelocity} 를 켜지 말 것. 켜면 {@code ServerEntity} 가 서버의 속도 통째로 같은
	 * 사람의 속도를 다시 <b>덮어쓴다</b> — 고친 것이 그대로 되돌아간다.
	 */
	private static void sendPull(ServerPlayer member, double x, double z) {
		if (member.connection == null) {
			return;
		}
		member.connection.send(new ClientboundExplodePacket(
				new Vec3(member.getX(), member.getY() + SUCK_PACKET_HIDE_LIFT, member.getZ()),
				0.0F, 0, Optional.of(new Vec3(x, 0.0, z)), ParticleTypes.ASH,
				Holder.direct(SoundEvents.EMPTY), WeightedList.of(), false));
	}

	/**
	 * 이 틱에 그 사람에게 더할 값(칸/틱). <b>월드 없이 답이 정해지는 계산이라 시험이 직접 굴린다.</b>
	 *
	 * <ul>
	 *   <li><b>공중이면 한 번치를 {@link #AIRBORNE_PUSH_SCALE} 만큼 깎는다.</b> 감쇠 0.91 에서 같은 번치는
	 *       바닥(0.546)의 다섯 배를 끌고 간다 — 날개 퍼덕이기가 같은 비율로 「점프하면 저 끝까지」를
	 *       막는다</li>
	 *   <li><b>천장은 결과 속도에 걸린다</b>({@link #suckStep} 과 같은 식). 사람의 지금 속도는
	 *       {@code knownOutward × 그 바닥의 감쇠} 다 — 사람이 지난 틱에 간 거리(감쇠 전 속도)에 그 틱
	 *       끝의 감쇠를 곱한 것이 이 틱이 시작할 때의 속도다</li>
	 * </ul>
	 *
	 * <p>⚠ 깎는 것이 천장보다 <b>먼저</b>다. 거꾸로(천장을 씌운 뒤 깎기) 하면 공중에서 가만히 선 사람이
	 * 천장의 바깥쪽 몫까지 깎여 바닥보다 약하게 끌린다 — 시험이 「공중 종착 == 바닥 종착」을 붙든다.
	 *
	 * @param knownOutward {@code getKnownMovement()} 의 <b>바깥쪽</b> 성분. 안쪽으로 가고 있으면 음수다
	 * @param airborne     {@link #isAirborne} — 둘 중 하나라도 「떠 있다」면 참
	 */
	static double suckImpulse(double knownOutward, boolean airborne) {
		double drag = airborne ? TrialEnderStorm.AIR_DRAG : GROUND_DRAG;
		double step = airborne ? SUCK_STEP * AIRBORNE_PUSH_SCALE : SUCK_STEP;
		return cappedPull(step, knownOutward * drag);
	}

	/**
	 * 바닥 한 번치(칸/틱). <b>월드 없이 답이 정해지는 계산이라 시험이 직접 굴린다.</b>
	 *
	 * <p>천장은 <b>결과 속도</b>에 걸린다 — 안쪽 속도가 이미 {@link #SUCK_MAX_INWARD} 면 0 을
	 * 돌려주고 그 사이면 천장까지만 더한다.
	 *
	 * @param outwardSpeed 지금 속도의 <b>바깥쪽</b> 성분. 안쪽으로 가고 있으면 음수다
	 */
	static double suckStep(double outwardSpeed) {
		return cappedPull(SUCK_STEP, outwardSpeed);
	}

	/** 천장을 씌운 한 번치. 식이 두 벌이 되지 않게 {@link #suckStep}·{@link #suckImpulse} 가 함께 부른다. */
	private static double cappedPull(double step, double outwardSpeed) {
		double room = SUCK_MAX_INWARD + outwardSpeed;
		if (!(room > 0.0)) {
			return 0.0;
		}
		return Math.min(step, room);
	}

	/**
	 * 터진다. <b>피해는 이 한 틱에 한 사람당 한 번</b>이다.
	 *
	 * <p>판정은 {@code TrialRisks.insideMark} 다 — 남의 카드와 같은 자(세로를 보지 않는 원)라야
	 * 「같은 표식은 언제나 같은 결과」가 성립한다.
	 *
	 * <p>소리는 <b>{@code WARDEN_SONIC_BOOM}</b> 이다. ⚠ {@code sounds.json} 을 열어 골랐고
	 * {@code mob/warden/sonic_boom1~4} 는 이 저장소의 어느 카드도 쓰지 않는다.
	 * {@code GENERIC_EXPLODE} 를 쓰지 않은 것은 그 파일({@code random/explode1~4})을 이미 다섯
	 * 자리가 쓰고 있어 <b>「또 폭발이 터졌다」로 묻히기</b> 때문이다 — 구멍이 닫히는 소리는 폭발이
	 * 아니라 <b>내려앉는 소리</b>라야 한다.
	 */
	private static void burstSuck(ServerLevel end, List<ServerPlayer> members, Vec3 center) {
		sprayBurst(end, center, 4);
		TrialWarning.playEach(end, members, SoundEvents.WARDEN_SONIC_BOOM, 1.2F, 0.8F);
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			if (!TrialRisks.insideMark(member.position(), center, SUCK_RADIUS)) {
				continue;
			}
			// 피해원에 실체를 달지 않는다. 실체가 붙으면 바닐라가 스스로 밀어내고, 그 밀기는
			// 날개 퍼덕이기가 지키는 천장을 통째로 지나쳐 간다.
			member.hurtServer(end, end.damageSources().lightningBolt(),
					DragonLastStand.VOID_SUCTION_DAMAGE);
		}
	}

	/** 터질 때와 그 뒤의 파티클. 판정과 무관한 연출이다. */
	private static void sprayBurst(ServerLevel end, Vec3 center, int density) {
		end.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, false,
				center.x, center.y + 0.5, center.z, 1, 0.0, 0.0, 0.0, 0.0);
		end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false,
				center.x, center.y + 0.5, center.z, density * 20, SUCK_RADIUS * 0.5, 0.5,
				SUCK_RADIUS * 0.5, 0.4);
	}

	// ------------------------------------------------------------------ ④ 십자 균열

	/**
	 * 십자 균열 — 폭 3칸 선 · <b>회차마다 십자 둘이 동시에</b> · 두 회차 · 각도가 회차마다 다르다 · 피해
	 * {@code DragonLastStand.CROSS_FISSURE_DAMAGE}(23) · <b>잔류 없음.</b>
	 *
	 * <p>처음 사람 말: <b>「십자균열을 내고 그냥 이름만 균열이지 십자로 긋고 다시 대각선으로 십자긋고
	 * 다시 대각선으로 긋고 3번 반복하는 패턴 단순 피하기」</b>. 2026-10-04 에 <b>「그 두 십자를 한번에
	 * 같이 발동시켜. 그리고 다음에도 두 개 같이 터지고」</b>로 바뀌었다.
	 *
	 * <h2>두 회차의 시각</h2>
	 *
	 * <table border="1">
	 *   <caption>{@code step} 으로 읽는 두 회차</caption>
	 *   <tr><th>{@code step}</th><th>무엇을 하는가</th><th>각도</th></tr>
	 *   <tr><td>{@code 0 ~ 59}</td><td>첫 회차 예고(3초) — 바닥 면이 선다</td><td>{@code +} 와 {@code ×}
	 *       (0 · 45도) 함께</td></tr>
	 *   <tr><td>{@code 60}</td><td>첫 회차 <b>터짐</b> + 둘째 예고 시작</td><td>→ 22.5 · 67.5도 함께</td></tr>
	 *   <tr><td>{@code 90}</td><td>둘째 <b>터짐</b></td><td></td></tr>
	 *   <tr><td>{@code 91 ~ 99}</td><td>아무것도 없다. 바닥은 이미 안전하다</td><td></td></tr>
	 * </table>
	 *
	 * <p>⚠ <b>터지는 틱과 다음 예고가 시작하는 틱이 같다.</b> 1.5초 사이에 「아무 표식도 없는 틈」을
	 * 두면 사람이 그 1.5초를 쉬는 시간으로 읽는다. 예고 시간 구조(첫 3초 · 다음 1.5초)는 사람이 바꾸라고
	 * 하지 않아 그대로다.
	 *
	 * <h2>⚠⚠ 예고는 <b>빨간 투명 바닥</b>이다 (2026-10-04)</h2>
	 *
	 * <p>사람이 이 패턴의 가시성을 두 번 지적했다 — <b>「특히 십자가 공격이 너무 잘 안보엿어」</b>, 그리고
	 * <b>「이것도 브레스처럼 투명땅으로 표시했으면 해」</b>. 처음 답(먼지 1.5배 · 같은 자리 흰 기둥)을
	 * <b>걷어내고</b> 부채꼴과 같은 판을 깐다({@link DragonLastStandCrossPanel}). 남은 신호는 셋이다.
	 *
	 * <ol>
	 *   <li><b>바닥 면</b> — 어디가 위험한가. 점을 안 쓴다(디스플레이 개체)</li>
	 *   <li><b>균열음이 0.5초마다, 음높이가 오른다</b> — 언제 터지는가({@link #CROSS_CRACK_TICKS})</li>
	 *   <li><b>터질 때 솟는 기둥</b> — 터졌다({@link #flashCross})</li>
	 * </ol>
	 *
	 * <h2>낙사도 밀기도 없다</h2>
	 *
	 * <p>사람이 <b>「단순 피하기」</b>라고 못박았다. 넉백이 없으므로 이 패턴에는 천장을 걸 것이
	 * 없고, 그래서 {@code TrialEnderStorm.pushDistance}·{@code TrialLandingShock.groundedReach} 를
	 * 부르지 않는다 — <b>부를 이유가 없는 것이지 빠뜨린 것이 아니다.</b>
	 *
	 * <h2>피해원은 {@code explosion(null, null)} 이다</h2>
	 *
	 * <p>사람이 적어 둔 것이 <b>「피해 23(무장 기준 6.8)」</b>이고, {@code GearedDamage} 로 풀면
	 * {@code explosion} 이 <b>6.77</b> 이다({@code lightningBolt} 는 4.56 이라 사람이 적은 수와
	 * 다르다). 곧 여기서도 <b>값이 피해원을 정했다.</b>
	 *
	 * <h2>⚠⚠ 한 회차에 한 사람은 <b>한 번</b>이다 — 가운데서는 선 넷이 겹친다</h2>
	 *
	 * <p>십자 둘이 함께 터지면 가운데(반경 3.9칸 안)는 <b>여덟 팔이 다 지나는 자리</b>다. 십자마다
	 * 따로 물으면 거기 선 사람이 두 번 맞고 두 번 띄워진다. 그래서 {@link #insideRound} 가 <b>물음
	 * 하나</b>로 「이 회차의 어느 선 안인가」를 답하고, {@link #fireRound} 가 사람마다 {@code hurtServer}
	 * 와 띄우기를 <b>한 번</b>만 부른다 — 상시 번개가 {@code break} 한 줄로 지키는 것을 이쪽은
	 * <b>구조로</b> 지킨다.
	 *
	 * <h2>⚠ 브레스처럼 잠그지 않았다 — 축소와 겹쳐도 된다</h2>
	 *
	 * <p>안전한 자리는 선 사이의 <b>쐐기</b>이고 그것은 <b>어느 반경에서나 있다</b>. 십자 둘이 함께라
	 * 쐐기가 45도로 좁아졌다 — 반경 6칸(마지막 지대의 절반)에서 쐐기 폭이 1.7칸이고 그 원의 35%(128도)
	 * 만 안전하다(전에는 반 넘게). ⚠ 반경 3.92칸 안은 <b>어디에도 쐐기가 없다</b> — 가운데 팔각형이 그
	 * 자리다. 그래도 피할 자리가 사람 폭(0.6)보다 넉넉히 남는다({@code DragonLastStandPatternsTest.십자는_마지막_지대에서도_피할_곳이_있다}).
	 *
	 * @param at   이 패턴이 시작한 틱. 바닥 면을 「어느 판의 몇 회차」로 세웠는지 가르는 데 쓴다
	 * @param step 이 패턴이 시작한 뒤 지난 틱
	 */
	private static void crossFissure(ServerLevel end, EnderDragon dragon,
			List<ServerPlayer> members, long at, int step) {
		Vec3 center = dragon.position();
		int fired = crossFiredRound(step);
		if (fired >= 0) {
			fireRound(end, members, center, CROSS_ANGLES[fired]);
		}
		int pending = crossPendingRound(step);
		if (pending >= 0) {
			warnCross(end, members, center, pending, at, step);
		}
	}

	/**
	 * {@code round} 번째 십자가 터지는 {@code step}.
	 *
	 * <p>첫 번째가 예고 60틱 뒤이고 그 뒤로는 1.5초씩이다. 월드 없이 답이 정해지므로 시험이 세
	 * 값을 직접 센다.
	 */
	static int crossFireStep(int round) {
		return CROSS_FIRST_WARN_TICKS + Math.max(0, round) * CROSS_GAP_TICKS;
	}

	/** 이 패턴이 차지하는 시간(틱). 마지막이 터진 뒤 여운까지다. */
	static int crossDurationTicks() {
		return crossFireStep(CROSS_ROUNDS - 1) + CROSS_AFTERGLOW_TICKS;
	}

	/** 이 틱에 터지는 회차의 번호. 터지지 않으면 {@code -1}. */
	static int crossFiredRound(int step) {
		for (int round = 0; round < CROSS_ROUNDS; round++) {
			if (step == crossFireStep(round)) {
				return round;
			}
		}
		return -1;
	}

	/**
	 * 이 틱에 <b>예고 중인</b> 회차의 번호. 다 터진 뒤면 {@code -1}.
	 *
	 * <p>⚠ 터지는 틱({@code step == crossFireStep(r)})에는 <b>다음</b> 번호를 돌려준다. 그 틱에
	 * 표식이 끊기지 않아야 한다 — 클래스 설명의 「터지는 틱과 다음 예고가 시작하는 틱이 같다」.
	 */
	static int crossPendingRound(int step) {
		for (int round = 0; round < CROSS_ROUNDS; round++) {
			if (step < crossFireStep(round)) {
				return round;
			}
		}
		return -1;
	}

	/** 그 회차의 예고 길이(틱). 첫째는 3초, 둘째는 1.5초다. */
	static int crossWarnTicks(int round) {
		return round <= 0 ? CROSS_FIRST_WARN_TICKS : CROSS_GAP_TICKS;
	}

	/**
	 * 예고. 그 회차의 <b>바닥 면</b>을 한 번 세우고({@link DragonLastStandCrossPanel}) 균열음과 층 소리를
	 * 낸다. <b>점은 한 개도 안 쓴다</b>(2026-10-04 에 먼지 선 · 흰 기둥을 걷었다).
	 *
	 * <h2>⚠ 균열음을 얹었다 — <b>전에는 이 패턴만 제 소리가 없었다</b></h2>
	 *
	 * <p>층 소리가 촘촘하다는 것(3초 예고에서 APPROACH·MARK·IMMINENT 세 번, 1.5초 예고에서
	 * MARK·IMMINENT 두 번)을 근거로 <b>제 소리를 얹지 않았었다.</b> 그 판단이 틀렸다 — 층 소리는
	 * <b>공용</b>이라 「무엇인가 온다」만 말하고, 사람이 바란 것은 <b>「각각 패턴마다 소리가
	 * 구분되엇으면해」</b>였다. 부채꼴은 {@code GHAST_WARN} 을, 흡입은 {@code BREEZE_INHALE} 을
	 * 이미 얹고 있었고 넷 가운데 여기만 비어 있었다.
	 *
	 * <p>층 소리는 <b>그대로 둔다.</b> 그 위에 {@code DEEPSLATE_BREAK} 를 0.5초마다 얹고 음높이가
	 * 오른다({@link #crackPitch}) — 「바닥이 갈라지고 있다」는 <b>되풀이</b>로만 말할 수 있다.
	 *
	 * <p>⚠ <b>{@code sounds.json} 을 열어 고른 소리다.</b> {@code block.deepslate.break} 가
	 * 가리키는 파일은 {@code block/deepslate/break1~4} 이고 <b>이 저장소의 어느 카드도 그 파일을
	 * 쓰지 않는다</b>(쓰는 소리를 통째로 훑어 확인했다). 뜻도 맞는다 — 돌이 갈라지는 소리이고
	 * 이 패턴의 이름이 균열이다. 터지는 소리 {@code WARDEN_DIG}({@code mob/warden/dig})와도
	 * 파일이 달라 <b>「금이 간다 → 갈라졌다」가 한 쌍</b>으로 들린다.
	 */
	private static void warnCross(ServerLevel end, List<ServerPlayer> members, Vec3 center,
			int round, long at, int step) {
		if (crossPanelAt != at || crossPanelRound != round) {
			// 이 회차의 면이 아직 안 섰다. 앞 회차가 터지는 틱이 곧 이 틱이라 fireRound 가 방금 앞
			// 면을 지웠고, 여기서 새 면을 세운다 — 「터지는 틱과 다음 예고가 시작하는 틱이 같다」.
			crossPanelAt = at;
			crossPanelRound = round;
			DragonLastStandCrossPanel.raise(end, center, CROSS_ANGLES[round]);
		}
		int warn = crossWarnTicks(round);
		int into = step - (crossFireStep(round) - warn);
		if (into >= 0 && into % CROSS_CRACK_TICKS == 0) {
			// playEach 다. 팀원 루프에서 level.playSound 를 부르면 모여 있는 넷이 네 겹으로 듣는다.
			TrialWarning.playEach(end, members, SoundEvents.DEEPSLATE_BREAK, 1.0F,
					crackPitch(into, warn));
		}
		int remaining = crossFireStep(round) - step;
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage == null || !TrialRisks.stageJustChanged(remaining, crossWarnTicks(round))) {
			return;
		}
		for (ServerPlayer member : members) {
			TrialWarning.soundFor(end, member, stage);
		}
	}

	/**
	 * 그 틱의 균열음 음높이. 낮은 데서 시작해 <b>마지막 한 번이 가장 높다.</b>
	 *
	 * <p>나누는 것이 예고 길이가 아니라 <b>마지막으로 울리는 틱</b>({@code 예고 − 0.5초})이다.
	 * 예고 길이로 나누면 마지막 울림이 {@link #CROSS_CRACK_PITCH_HIGH} 에 못 닿아 「지금 터진다」가
	 * 안 들린다 — 부채꼴의 {@link #chargePitch} 가 같은 함정을 같은 방법으로 피한다.
	 *
	 * <p>⚠ <b>예고 길이를 받는다.</b> 첫 회차는 3초이고 둘째는 1.5초라({@link #crossWarnTicks})
	 * 고정된 길이로 나누면 둘째가 <b>0.7~1.1 에서 끝난다.</b> 회차가 셋에서 둘로 줄어도(2026-10-04) 이
	 * 식은 「몇 번째인가」를 세지 않으므로 고칠 것이 없었다 — 회차마다 같은 음높이로 끝나야 「마지막」이
	 * 아니라 「매번 그렇다」가 된다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 두 회차를 통째로 굴려 본다.
	 *
	 * @param into      그 십자의 예고가 시작한 뒤 지난 틱
	 * @param warnTicks 그 십자의 예고 길이(틱)
	 */
	static float crackPitch(int into, int warnTicks) {
		int last = warnTicks - CROSS_CRACK_TICKS;
		if (last <= 0) {
			return CROSS_CRACK_PITCH_HIGH;
		}
		float progress = Math.max(0.0F, Math.min(1.0F, (float) into / last));
		return CROSS_CRACK_PITCH_LOW
				+ (CROSS_CRACK_PITCH_HIGH - CROSS_CRACK_PITCH_LOW) * progress;
	}

	/**
	 * 한 회차가 터진다. <b>피해와 띄우기는 이 한 틱에 한 사람당 한 번</b>이다 — 그 회차의 십자가
	 * 몇 개든.
	 *
	 * <p>⚠⚠ <b>사람 루프가 바깥이고 물음이 하나다.</b> 십자마다 사람을 돌면 가운데(두 십자가 다
	 * 지나는 자리)에 선 사람이 <b>두 번 맞고 두 번 띄워진다</b> — 피해는 바닐라 피격 무적이 두 번째를
	 * 막아 주더라도 띄우기는 막지 않는다. 그래서 {@link #insideRound} 한 번으로 「이 회차의 어느 선
	 * 안인가」를 묻고 한 번만 친다.
	 *
	 * <p>이 틱에 <b>바닥 면을 지운다.</b> 터진 뒤의 바닥은 안전하므로 한 틱이라도 더 남기면 표식이
	 * 거짓말을 한다(부채꼴의 {@link #fireCone} 과 같다). 다음 회차가 있으면 같은 틱에
	 * {@link #warnCross} 가 새 면을 세운다.
	 *
	 * <p>소리는 <b>{@code WARDEN_DIG}</b> 다. ⚠ {@code sounds.json} 을 열어 골랐고
	 * {@code mob/warden/dig} 는 이 저장소의 어느 카드도 쓰지 않는다. 십자가 둘이어도 <b>한 번</b>
	 * 울린다 — 「회차가 터졌다」가 한 사건이다.
	 */
	private static void fireRound(ServerLevel end, List<ServerPlayer> members, Vec3 center,
			double[] angles) {
		DragonLastStandCrossPanel.drop();
		for (double baseDeg : angles) {
			flashCross(end, center, baseDeg);
		}
		TrialWarning.playEach(end, members, SoundEvents.WARDEN_DIG, 1.0F, 0.8F);
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			if (!insideRound(member.getX() - center.x, member.getZ() - center.z, angles,
					CROSS_HALF_WIDTH, CROSS_REACH)) {
				continue;
			}
			// 피해원에 실체를 달지 않는다. 실체가 붙으면 바닐라가 스스로 밀어내고, 사람이 정한
			// 것은 「낙사·섬 밖으로 미는 것 없음」이다.
			member.hurtServer(end, end.damageSources().explosion(null, null),
					DragonLastStand.CROSS_FISSURE_DAMAGE);
			// ⚠ 피해 뒤에 띄운다. 앞에 두면 hurtServer 가 지나가며 속도를 건드릴 수 있고, 그러면
			// 「12칸」이 맞는 사람마다 달라진다.
			liftCross(end, member);
		}
	}

	/**
	 * 그 자리가 <b>이 회차의 어느 십자</b> 안인가. 십자가 몇 개든 <b>답은 하나</b>다.
	 *
	 * <p>{@link #fireRound} 가 이 물음 하나로 사람마다 한 번만 친다 — 가운데처럼 여러 선이 겹치는
	 * 자리에서 「두 번 맞는다」가 구조적으로 없는 까닭이 이것이다. 바닥 면
	 * ({@link DragonLastStandCrossPanel})이 칠한 자리도 이 물음으로 재어진다(시험이 판 귀퉁이를 여기에
	 * 넣어 본다).
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 직접 굴린다.
	 */
	static boolean insideRound(double dx, double dz, double[] angles, double halfWidth,
			double reach) {
		for (double baseDeg : angles) {
			if (insideCross(dx, dz, baseDeg, halfWidth, reach)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * ⚠⚠ 맞은 사람을 <b>위로 {@value #CROSS_LIFT_BLOCKS}칸 솟구치게</b> 한다. <b>가로는 한 톨도
	 * 건드리지 않는다.</b>
	 *
	 * <p>사람 말: <b>「30프로 2페이지때 십자가 공격받앗을때도 한 6칸 띄워버려 점프하게」</b> →
	 * 2026-10-04 <b>「지금보다 2배는 더 날려버려」</b>(12칸). 왜 이것이 이 전투의 명문 규칙을 뒤집는 것이고 왜 그래도 안전한지는
	 * {@link #CROSS_LIFT_BLOCKS} 에 길게 적어 두었다.
	 *
	 * <h2>⚠ 가로를 더하지 않는 것이 이 메서드의 전부다</h2>
	 *
	 * <p>{@code setDeltaMovement} 에 넘기는 x·z 가 <b>읽은 그대로</b>다. 세로만 덮어쓴다 —
	 * {@link #shove} 가 거꾸로 <b>가로만 덮어쓰고 세로는 올리는 쪽만 자른다</b>
	 * ({@link TrialVelocity#syncedVertical})는 것과 정확히 반대이고, 둘이 합쳐 「이 파일은 가로와 세로를 섞지
	 * 않는다」가 된다. ⚠ <b>이 메서드는 {@link TrialVelocity#syncedVertical} 을 안 지난다</b> — 여기서 쓰는
	 * 세로는 <b>우리가 지은 값</b>({@link #CROSS_LIFT_SPEED})이라 남이 쌓아 둔 것이 섞일 자리가
	 * 없다. 덮어쓰기라는 것 자체가 그 방어다. 가로를 조금이라도
	 * 더하면 그 사람은 <b>마찰이 안 먹는 공중에서</b> 그만큼을 가고, 그것이 「세로로 띄우지
	 * 않습니다」가 막으려던 사고다. ⚠ 그래서 <b>천장 둘
	 * ({@code TrialEnderStorm.pushDistance}·{@code TrialLandingShock.groundedReach})을 부르지
	 * 않는다</b> — 수평으로 한 칸도 옮기지 않으므로 자를 것이 없다. <b>빠뜨린 것이 아니다.</b>
	 *
	 * <h2>⚠⚠ 그 논증에 구멍이 하나 있었다 — <b>2026-10-04 에 뿌리에서 닫았다</b></h2>
	 *
	 * <p>「수평으로 한 칸도 옮기지 않는다」는 <b>우리가 더하지 않는다</b>는 말이고,
	 * <b>우리가 배달하지 않는다</b>는 말이 아니었다. {@link TrialVelocity#syncedVertical} 을 파면서 드러난
	 * 것인데, 서버가 들고 있는 {@code deltaMovement} 의 <b>수평에도</b> 바닐라
	 * {@code EnderDragon.knockBack} 이 매 틱 값을 쌓았다 —
	 * {@code dx ÷ max(dx²+dz², 0.1) × 4} 라 <b>거리로 나누는 식</b>이고 분모가 {@code 0.1} 에서
	 * 멈추므로 {@code √0.1} 칸에서 <b>12.65칸/틱</b>이 최댓값이다. 그 값이 쌓이면 바닥 종착
	 * <b>15.2칸/틱</b> · 공중 종착 <b>127.9칸/틱</b>이다.
	 *
	 * <p>{@link #shove} 는 수평을 <b>덮어쓰므로</b> 그 오염이 그 자리에서 사라진다. 그런데
	 * <b>이 메서드와 {@link #pullSuck} 은 수평을 읽어서 돌려놓고</b> {@code syncVelocity} 를 켠다 —
	 * 곧 <b>쌓인 수평을 본인에게 배달하면서 동시에 그 사람을 공중(감쇠 0.91)으로 띄우고 낙하
	 * 피해까지 면제</b>하는 자리였다. 셋이 겹쳤다.
	 *
	 * <p>⚠⚠ <b>닫은 자리는 이 파일이 아니다.</b> {@code EnderDragonContactDamageMixin} 이
	 * {@code hurt} 와 함께 <b>{@code knockBack} 까지 같은 {@code contactDamageOff()} 깃발로
	 * 끊는다</b> — 최후의 저항이 도는 동안 바닐라가 쌓는 세로·수평이 <b>아예 생기지 않으므로</b>
	 * 「읽어서 돌려놓는다」가 돌려놓을 오염이 없다. <b>여기와 {@link #pullSuck} 의 실행되는 코드는
	 * 한 줄도 안 고쳤다</b> — 「들고 있던 가로 속도를 바꾸지 않는다」는 설계 약속을 그대로 두고
	 * 구멍만 닫는 길이 그것이었기 때문이다. 못박은 시험:
	 * {@code 띄워진_뒤_쌓인_수평을_안고_밀려도_섬_밖으로_못_나간다}.
	 *
	 * <p>⚠⚠ <b>2026-10-04 저녁에 {@link #pullSuck} 은 이 길을 떠났다</b> — 매 틱 덮어쓰는 배달이 사람의
	 * 달리기를 지우고 있었다(그 메서드 설명). 같은 날 드러난 것 하나가 여기에도 걸린다: 서버의
	 * 「들고 있던 가로」는 <b>사람의 가로가 아니다</b>(서버는 사람 입력 없이 이동을 굴린다). 그러니 이
	 * 메서드가 그것을 「읽은 그대로」 보내면 받는 쪽에서는 <b>사람이 달리던 가로가 서버의 수(대개 0
	 * 근처)로 바뀐다</b> — 띄워지는 순간 달리던 속도를 잃는다. 한 번뿐이라 흡입처럼 무너지지는 않고,
	 * 고치지 않았다(사람이 본 문제가 아니다).
	 *
	 * <p>⚠ <b>그래도 「남이 쌓아 둔 값을 믿지 않는다」는 규약은 남는다.</b> 뿌리를 끊은 것은
	 * <b>최후의 저항에서만</b>이고(일반 전투의 날개 밀치기는 바닐라 동작이라 끊으면 안 된다),
	 * 그 깃발이 내려간 자리에서 이 메서드를 부르는 길이 생기면 구멍이 그대로 돌아온다.
	 * {@link TrialVelocity#syncedVertical} 을 세로의 보험으로 남겨 둔 것과 같은 까닭이다.
	 *
	 * <h2>⚠⚠ 낙하 피해를 <b>이 띄움 몫만</b> 없앤다</h2>
	 *
	 * <p>12칸에서 떨어지면 바닐라 낙하 피해가 <b>9</b> 다({@code ceil(12 − 안전 낙하 3)}, 6칸이던 때
	 * 3). 그리고 ⚠ <b>{@code minecraft:fall} 은 {@code #bypasses_armor} 에 들어 있어 다이아 풀셋이 한
	 * 점도 안 깎는다</b>(26.3 {@code data/minecraft/tags/damage_type/bypasses_armor.json} 에서 확인했다)
	 * — 듣는 것은 보호 IV 뿐이라 무장 기준 <b>9 × 0.36 = 3.24</b> 다. 맨몸이면 9 그대로다(사람에게
	 * 알렸다).
	 *
	 * <p>그 3.24 는 {@code TrialRisks.worstCaseTickDamage} 가 <b>세지 않는 피해</b>다. 한 판에 최대 두
	 * 대라 통틀어 <b>6.48 이 셈 밖에서</b> 얹히고, 적힌 값으로 잡아 둔 두 대의 13.54 가 실제로는
	 * <b>20.02</b> — <b>팀 체력 20 을 넘는다.</b> 면제가 빠지는 날 「두 대에 안 죽는다」가 그
	 * 자리에서 거짓이 된다. 셈에 안 들어오는 피해를 늘리지 않는 것이 이 판의 규칙이고, 높이가 두 배가
	 * 되어 이 면제의 무게도 세 배가 됐다.
	 *
	 * <p>쓰는 것은 바닐라가 <b>바로 이 일을 위해</b> 들고 있는 장치다 — 26.3
	 * {@code LivingEntity.setIgnoreFallDamageFromCurrentImpulse(boolean, Vec3)} 이고,
	 * {@code ServerPlayer.onExplosionHit} 이 바람 충전에 밀린 사람에게 {@code position()} 을 넘겨
	 * 부르는 그 메서드다(바이트코드로 확인했다).
	 *
	 * <h2>⚠ 다른 낙하가 공짜가 되지 않는 근거 — <b>거리에 자가 달려 있다</b></h2>
	 *
	 * <p>26.3 {@code LivingEntity.causeFallDamage} 의 첫 줄이 이렇다(바이트코드로 확인했다).
	 *
	 * <pre>{@code
	 * d = min(fallDistance, currentImpulseImpactPos.y − getY())
	 * }</pre>
	 *
	 * <p>곧 면제되는 것은 <b>「띄운 자리보다 위」인 몫뿐</b>이다. 우리가 넘기는 자리가 <b>띄우는
	 * 그 순간의 발밑</b>이므로
	 *
	 * <ul>
	 *   <li><b>제자리에 떨어지면</b> {@code d ≤ 0} 이라 피해가 0 이고, 그 자리에서
	 *       {@code resetCurrentImpulseContext()} 가 불려 <b>면제가 그 틱에 사라진다</b></li>
	 *   <li><b>띄운 자리보다 10칸 아래에 떨어지면</b> {@code d = 10} 이라 <b>그 10칸 몫은 그대로
	 *       아프다.</b> 「12칸 띄웠으니 그 뒤의 낙하는 전부 공짜」가 아니다</li>
	 *   <li>⚠ <b>이미 떨어지던 중에 맞아도</b> 그 사람이 이미 쌓아 둔 낙하 거리는 면제되지 않는다
	 *       ({@code min} 의 오른쪽이 띄운 자리에서부터만 재기 때문이다)</li>
	 * </ul>
	 *
	 * <p>⚠ <b>{@code resetFallDistance()} 를 부르지 않는다.</b> 부르면 바로 위의 마지막 줄이
	 * 깨져 <b>맞기 전에 떨어지고 있던 몫까지 공짜</b>가 된다. 「자리 폭격」
	 * ({@code TrialRisks.launch})은 거꾸로 <b>낙하 거리를 매 틱 지우는</b> 쪽인데, 그쪽은 면제가
	 * <b>시간</b>으로만 끊겨 「스스로 절벽에서 뛰어내렸을 때 한 번 봐 주는 것」을 대가로 적어 두었다.
	 * 여기서는 그 대가를 치르지 않으려고 <b>거리로 끊는 쪽</b>을 골랐고, 그래서 지울 상태도 돌릴
	 * 시계도 없다.
	 *
	 * <h2>되먹임</h2>
	 *
	 * <p>발밑에서 {@code CRIT} 하나를 <b>위로</b> 쏜다. {@link #flashCross} 가 같은 틱에 중심선마다
	 * 세우는 기둥과 <b>같은 입자·같은 세기</b>({@link #CROSS_BURST_RISE})라 「저 기둥 하나가 내 발밑에
	 * 섰다」로 읽힌다. 개수를 0 으로 보내므로 점은 <b>하나</b>다.
	 */
	private static void liftCross(ServerLevel end, ServerPlayer member) {
		// ⚠ 새 속도를 짓는 것을 liftMotion 한 곳에 모아 둔다. 월드 없이 답이 정해지는 계산이라
		// 시험이 「가로가 들어온 그대로인가」를 거기서 직접 굴린다 — 여기서 손으로 적으면
		// 그 시험이 아무것도 재지 못한다.
		member.setDeltaMovement(liftMotion(member.getDeltaMovement()));
		// 켜지 않으면 서버 혼자 띄운 것이 되어 잠시 뒤 제자리로 되돌아간다.
		member.syncVelocity = true;
		// 이 띄움에서 비롯한 낙하만 면제한다. 넘기는 자리가 「띄운 순간의 발밑」이라 그보다
		// 아래로 떨어지는 몫은 그대로 아프다 — 위의 「다른 낙하가 공짜가 되지 않는 근거」를 볼 것.
		member.setIgnoreFallDamageFromCurrentImpulse(true, member.position());
		// 개수 0 이라 뒤 값이 속도로 읽혀 점을 하나만 쓰고 기둥이 선다.
		end.sendParticles(ParticleTypes.CRIT, true, false,
				member.getX(), member.getY() + GROUND_OFFSET, member.getZ(),
				0, 0.0, 1.0, 0.0, CROSS_BURST_RISE);
	}

	/**
	 * ⚠⚠ 띄운 뒤의 속도. <b>세로만 덮어쓰고 가로는 받은 그대로 돌려준다.</b>
	 *
	 * <p>이 메서드가 「<b>가로 성분을 한 톨도 더하지 않는다</b>」의 전부이고, 그것이 사람이 명문
	 * 규칙을 뒤집은 자리에서 <b>규칙이 막으려던 사고를 대신 막는 한 줄</b>이다 — 가로가 섞이면
	 * 떠 있는 동안 마찰이 안 먹어 그만큼이 통째로 이동이 된다({@link #AIRBORNE_PUSH_SCALE}).
	 *
	 * <p>⚠ <b>{@link #shove} 와 정확히 반대다.</b> 그쪽은 가로만 덮어쓰고 세로는
	 * {@link TrialVelocity#syncedVertical} 로 <b>올리는 쪽만</b> 자른다. 둘을 나란히 두면 「이 파일은 가로와
	 * 세로를 섞지 않는다」가 읽힌다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 가로를 바꿔 넣어 가며 직접 굴린다.
	 */
	static Vec3 liftMotion(Vec3 motion) {
		return new Vec3(motion.x, CROSS_LIFT_SPEED, motion.z);
	}

	/**
	 * 그 처음 세로 속도로 <b>실제로 올라가는 높이</b>(칸). 공기 저항까지 센 값이다.
	 *
	 * <p>26.3 {@code LivingEntity.travelInAir} 의 순서를 그대로 굴린다(바이트코드로 확인했다) —
	 * <b>자리를 먼저 옮기고</b>({@code handleRelativeFrictionAndCalculateMovement} 안의
	 * {@code move}), 그 다음 세로 속도에서 중력을 빼고, 마지막에 {@value #LIFT_DRAG} 를 곱한다.
	 *
	 * <pre>{@code
	 * y += v;  v = (v - 0.08) * 0.98
	 * }</pre>
	 *
	 * <p><b>이 식이 맞는 것은 바닐라 점프로 검산된다</b> — 처음 0.42 를 넣으면 <b>1.2522칸</b>이
	 * 나오고 그것이 널리 알려진 바닐라 점프 높이다. 시험이 그 검산을 먼저 한다.
	 *
	 * <p>⚠ {@code √(2gh)} 로 풀지 않는 까닭이 여기 있다. 그 근사는 <b>감쇠를 빼먹고</b>(낮게 뜬다)
	 * <b>이산 합을 적분으로 바꿔</b>(높게 뜬다) 두 오차가 높이마다 다르게 상쇄된다 — 4칸에서는
	 * 0.7% 모자라는데 6칸에서는 <b>5%</b>(5.70칸), 12칸에서는 <b>12%</b>(10.55칸) 모자란다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 직접 굴린다.
	 */
	static double liftApex(double speed) {
		double velocity = speed;
		double height = 0.0;
		while (velocity > 0.0) {
			height += velocity;
			velocity = (velocity - LIFT_GRAVITY) * LIFT_DRAG;
		}
		return height;
	}

	/**
	 * 그 높이에 닿는 <b>처음 세로 속도</b>(칸/틱). {@link #liftApex} 를 이분법으로 뒤집는다.
	 *
	 * <p>닫힌 식이 없는 것은 <b>몇 틱 올라가는가가 정수</b>라 구간마다 식이 갈리기 때문이다. 이분법
	 * 은 {@link #liftApex} 가 단조이므로 반드시 수렴하고, 60번이면 {@code double} 의 자리까지
	 * 간다 — 클래스가 열릴 때 한 번 돌고 {@link #CROSS_LIFT_SPEED} 에 들어앉는다.
	 *
	 * <p>위쪽 끝을 4 로 잡은 것은 <b>그것이 바닐라 종착 낙하 속도(3.92칸/틱)보다 크기</b> 때문이다.
	 * 그 속도로도 안 닿는 높이는 이 판에서 올라갈 수 없는 높이이고, 그때는 4 를 돌려주는 것이
	 * <b>조용히 1 을 돌려주는 것보다 눈에 띈다.</b>
	 */
	static double liftSpeed(double height) {
		if (!(height > 0.0)) {
			return 0.0;
		}
		double low = 0.0;
		double high = 4.0;
		for (int step = 0; step < 60; step++) {
			double middle = (low + high) / 2.0;
			if (liftApex(middle) < height) {
				low = middle;
			} else {
				high = middle;
			}
		}
		return high;
	}

	/**
	 * 그 처음 세로 속도로 <b>몇 틱 올라가는가.</b> 12칸이면 16틱(0.8초)이다(6칸이던 때 12틱).
	 *
	 * <p>{@link #liftApex} 와 같은 식을 세기만 한다. 재는 것은 <b>띄워진 사람이 공중에 있는
	 * 시간</b>이고, 그 시간이 날개 퍼덕이기의 번치 간격({@value #WING_PULSE_TICKS}틱)보다 길다는
	 * 것이 「띄운 뒤에 밀릴 수 있다」의 근거다 — 시험이 그 부등호를 붙든다.
	 */
	static int liftRiseTicks(double speed) {
		double velocity = speed;
		int ticks = 0;
		while (velocity > 0.0) {
			ticks++;
			velocity = (velocity - LIFT_GRAVITY) * LIFT_DRAG;
		}
		return ticks;
	}

	/**
	 * ⚠⚠ 그 처음 세로 속도로 띄워진 사람이 <b>공중에 있는 시간</b>(틱). 12칸이면 <b>35틱(1.75초)</b>
	 * 이다(6칸이던 때 25틱).
	 *
	 * <p>올라갔다가 띄운 자리 높이로 되돌아올 때까지를 센다. 올라가는 16틱보다 내려오는 19틱이 긴
	 * 것은 세로 감쇠가 {@value #LIFT_DRAG} 뿐이라 떨어지는 속도가 계속 커지되 거리는 천천히 쌓이기
	 * 때문이다.
	 *
	 * <p>⚠ <b>이 수가 이 작업에서 가장 중요한 수다.</b> 날개 퍼덕이기의 번치 간격이
	 * {@value #WING_PULSE_TICKS}틱이라 <b>띄워진 사람은 공중에서 번치를 두세 번 받는다</b>(6칸이던 때
	 * 두 번) — 곧
	 * 「내가 만든 상태가 남이 만든 넉백의 입력」이 되는 것이 <b>드문 일이 아니라 거의 언제나</b>다.
	 * 그때 받는 세기를 바닥과 같게 맞추는 것이 {@link #AIRBORNE_PUSH_SCALE} 이고, 시험이 그
	 * 부등호를 붙든다.
	 */
	static int liftAirborneTicks(double speed) {
		double velocity = speed;
		double height = 0.0;
		int ticks = 0;
		while (true) {
			height += velocity;
			ticks++;
			velocity = (velocity - LIFT_GRAVITY) * LIFT_DRAG;
			if (height <= 0.0) {
				return ticks;
			}
			if (ticks > 1200) {
				// 되돌아오지 않는 속도를 넣었다. 영원히 도는 것보다 눈에 띄는 수를 돌려준다.
				return ticks;
			}
		}
	}

	/**
	 * 터지는 <b>그 틱</b>에 갈라짐이 보이는 모습. <b>판정과 무관한 연출이다.</b>
	 *
	 * <p>{@code SWEEP_ATTACK} 을 고른 것은 26.3 에서 그것이 <b>납작한 반원 한 장</b>이라 「바닥이
	 * 한 줄로 갈라졌다」로 읽히기 때문이다. {@code EXPLOSION} 을 깔면 폭격과 구별되지 않고,
	 * {@code FLAME} 은 부채꼴 브레스가 이미 쓰고 있어 「불이 지나갔다」로 읽힌다.
	 *
	 * <p>⚠ <b>블록을 한 칸도 건드리지 않는다.</b> 이름이 「균열」이지만 사람이 <b>「그냥 이름만
	 * 균열이지」</b>라고 못박았다 — 실제로 바닥을 파면 공허 낙사이고 그것이 이 전투가 유일하게
	 * 금지한 것이다.
	 *
	 * <h2>⚠ 솟는 기둥을 더했다 — <b>터진 것이 터진 것처럼 보여야 한다</b></h2>
	 *
	 * <p>{@code SWEEP_ATTACK} 은 <b>바닥에 눕는 납작한 한 장</b>이라 서서 보는 눈높이에서 거의
	 * 두께가 없다. 사람이 <b>「특히 십자가 공격이 너무 잘 안보엿어」</b>라고 한 까닭의 나머지
	 * 절반이 그것이다 — 예고가 안 보이는 것과 <b>터진 것이 안 보이는 것</b>은 다른 문제다.
	 *
	 * <p>그래서 같은 중심선 위에 {@value #CROSS_BURST_GAP} 칸마다 {@code CRIT} 을 <b>위로</b>
	 * 쏜다({@link #CROSS_BURST_RISE} 로 3.9칸). 「연쇄 포격」이 터진 고리를 그 틱에 지우는 규칙은
	 * 그대로다 — 이 기둥도 <b>그 한 틱</b>에만 나가고 피해는 이미 끝났다.
	 */
	private static void flashCross(ServerLevel end, Vec3 center, double baseDeg) {
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		for (int line = 0; line < 2; line++) {
			double radians = Math.toRadians(baseDeg + line * 90.0);
			double alongX = Math.sin(radians);
			double alongZ = -Math.cos(radians);
			for (double along = -CROSS_REACH; along <= CROSS_REACH; along += CROSS_FLASH_GAP) {
				double x = center.x + alongX * along;
				double z = center.z + alongZ * along;
				int surface = ground.surfaceAt(end, x, z);
				if (surface == TrialEnderPulse.NO_GROUND) {
					continue;
				}
				end.sendParticles(ParticleTypes.SWEEP_ATTACK, true, false,
						x, surface + GROUND_OFFSET, z, 1, 0.0, 0.0, 0.0, 0.0);
			}
			// 그 선에서 솟는 기둥. 납작한 한 장만으로는 터진 것이 터진 것처럼 안 보인다.
			for (double along = -CROSS_REACH; along <= CROSS_REACH; along += CROSS_BURST_GAP) {
				dot(end, ground, ParticleTypes.CRIT, center.x + alongX * along,
						center.z + alongZ * along, CROSS_BURST_RISE);
			}
		}
	}

	/**
	 * 그 자리가 십자 안인가.
	 *
	 * <p><b>세로는 보지 않는다.</b> {@code TrialRisks.insideMark} 와 같은 태도다 — 표식이 바닥에
	 * 그려지는데 「선 위에 떠 있었으니 안 맞는다」가 되면 표식이 거짓말한 것이 된다.
	 *
	 * <p>십자는 <b>90도 떨어진 선 둘</b>이므로 두 수직거리 가운데 하나라도 반폭 안이면 안이다.
	 * 그 둘은 서로 직교하므로 각각 {@code |외적|} 과 {@code |내적|} 이고, 그래서 삼각함수를
	 * <b>한 번만</b> 쓴다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 세 각도의 경계를 직접 굴려 본다.
	 *
	 * @param dx        중심에서 잰 x. 곧 {@code 사람 x − 드래곤 x}
	 * @param dz        중심에서 잰 z
	 * @param baseDeg   이 십자의 기준 각도(도). {@code (sin, −cos)} 이 첫 선의 방향이다
	 * @param halfWidth 선의 반폭(칸)
	 * @param reach     중심에서 선이 뻗는 거리(칸)
	 */
	static boolean insideCross(double dx, double dz, double baseDeg, double halfWidth,
			double reach) {
		if (dx * dx + dz * dz > reach * reach) {
			return false;
		}
		double radians = Math.toRadians(baseDeg);
		double alongX = Math.sin(radians);
		double alongZ = -Math.cos(radians);
		// 첫 선까지의 수직거리와, 그것과 직교하는 둘째 선까지의 수직거리.
		double toFirst = Math.abs(dx * alongZ - dz * alongX);
		double toSecond = Math.abs(dx * alongX + dz * alongZ);
		return Math.min(toFirst, toSecond) <= halfWidth;
	}

	// ------------------------------------------------------------------ ⑤ 상시 번개 (패턴이 아니다)

	/**
	 * 상시 번개 — 열 곳 · 반경 2.55칸 · 피해 {@code DragonLastStand.LIGHTNING_DAMAGE}(35) ·
	 * 드래곤 주변에 몰아서. <b>7.8초마다 저 혼자 돈다</b>({@link #LIGHTNING_PERIOD_TICKS} — 사람이
	 * 6초를 30% 내리라고 했고, 「낙뢰」 카드의 6초와는 일부러 갈라졌다).
	 *
	 * <p>사람이 <b>「번개는 패턴에 추가하지말고 기본이펙트로 계속 터졋으면좋겟어 드래곤 패턴이
	 * 아니라」</b>라고 해서 패턴 풀에서 빼고 여기로 옮겼다. {@link DragonLastStandZone} 과 같은
	 * 모양이다 — {@code DragonLastStand.tick} 이 매 틱 넘기고, <b>쉬는 틈도 쿨다운도 없다.</b>
	 *
	 * <h2>⚠⚠ 겹침을 허용한다 — 사람이 알고 고른 것이다</h2>
	 *
	 * <p><b>이 저장소에서 「즉사 메커닉 0개」를 깨는 두 번째 자리다</b>(첫째는 「종말의 비」).
	 * 값을 만지기 전에 이 절을 끝까지 읽을 것.
	 *
	 * <h3>어쩌다 이렇게 됐는가</h3>
	 *
	 * <ol>
	 *   <li>다섯 곳일 때는 <b>굳은 배치</b>(가운데 하나 + 90도씩 벌린 넷, 회전만 무작위)로
	 *       <b>겹침 없음이 증명</b>되어 있었다 — 쌍마다의 거리가 회전과 무관하게 같았다</li>
	 *   <li>사람이 열 곳으로 올렸다. 그러면 <b>겹침 없이 열 곳을 세울 수 없다</b> —
	 *       정확히 말하면 <b>무작위로 굴려 찾는 방식으로는</b> 그렇다. 자리를 굴릴 수 있는
	 *       반경이 9.45 뿐인데({@link #LIGHTNING_FIELD_RADIUS}) 최소 간격이
	 *       {@code TrialRisks.spotMinGap(2.55) = 5.1} 이라, {@code TrialRisks.SPOT_TRIES}(8)번
	 *       굴려 찾게 하면 「겹치느니 한 발 빠진다」가 자주 일어나 열 곳이 몇 곳으로 줄어든다.
	 *       ⚠ <b>손으로 배치하면 이론적으로는 들어간다</b>(가운데 하나 + 5.1 고리 여섯 + 9.45
	 *       고리 여섯이면 열셋이다). 그러니 「기하학적으로 불가능」이 아니라 「굴려서는 안
	 *       된다」가 정확한 말이고, 굳은 배치를 열 곳으로 다시 짜는 길은 남아 있다 — 다만 그것은
	 *       사람이 고른 답이 아니다</li>
	 *   <li>셋을 물었고 사람이 <b>「겹침을 허용함」</b>을 골랐다</li>
	 * </ol>
	 *
	 * <h3>그래서 {@code TrialRisks} 의 겹침 금지 목록을 쓰지 않는다</h3>
	 *
	 * <p>{@code reserveSpots}·{@code releaseSpots} 를 부르지 않고 {@code LIVE_SPOTS} 에 한 줄도
	 * 올리지 않는다. <b>「종말의 비」와 글자 그대로 같은 예외</b>이고 그쪽에 적힌 까닭이 그대로
	 * 적용된다 — <b>양쪽 다 끈 상태</b>여야 한다(남의 고리를 피하지도 않고 남이 우리를 피하지도
	 * 않는다). 한쪽만 되돌리면 겹침은 남고 자리만 줄어든다.
	 *
	 * <p>덧붙여, 이 페이즈에는 애초에 남의 고리가 없다 — 진입할 때 {@code TrialRisks.clearState()}
	 * 가 돌아 {@code LIVE_SPOTS} 가 비고 시련이 전부 멈춰 있다.
	 *
	 * <p>다만 <b>자리를 굴리는 자와 재는 자는 그쪽에서 가져온다</b> —
	 * {@code TrialRisks.arenaOffset}(원 안에 고르게)과 {@code TrialRisks.insideMark} 다. 분포를
	 * 여기서 새로 짜면 남의 카드와 다른 모양이 된다. ⚠ 넘기는 반경만
	 * {@link #LIGHTNING_FIELD_RADIUS}(9.45) 이고 {@code ARENA_RADIUS}(40)가 아니다 — 카드가
	 * 「드래곤 주변에 몰아서」이고, 아레나 전체에 흩뿌리면 <b>지대 밖에 번개가 떨어진다.</b>
	 *
	 * <h3>⚠ 겹친 자리가 세 겹이면 무장하고도 전멸일 <b>값</b>이다</h3>
	 *
	 * <p>피해 35 · {@code lightningBolt()} 라 다이아 풀셋 + 보호 IV 기준으로 이렇다
	 * ({@code GearedDamage}).
	 *
	 * <table border="1">
	 *   <caption>한 틱에 맞는 발 수</caption>
	 *   <tr><th>발</th><th>실제 피해</th><th></th></tr>
	 *   <tr><td>1</td><td>6.93</td><td>산다</td></tr>
	 *   <tr><td>2</td><td>13.86</td><td>산다</td></tr>
	 *   <tr><td><b>3</b></td><td><b>20.79</b></td><td><b>팀 체력 20 — 전멸</b></td></tr>
	 * </table>
	 *
	 * <p>⚠⚠ <b>그런데 실제로는 한 사람이 한 볼리에 한 발만 받는다.</b>
	 * {@link #strikeLightning} 이 첫 발에서 빠져나오기 때문이다. 그 {@code break} 는 굳은 배치
	 * 시절에 「배치를 손대는 사람이 약속을 깨뜨리지 못하게」 둔 보험이었는데, <b>겹침을 허용하면서
	 * 그것이 유일한 안전장치가 됐다.</b> 지우면 그날로 전멸 카드다 —
	 * {@code DragonLastStandPatternsTest} 가 그 {@code break} 를 이름으로 붙들고 있다.
	 *
	 * <h3>얼마나 자주 겹치는가 — 20만 볼리를 굴린 값</h3>
	 *
	 * <p>반경 9.45 원 안에 반경 2.55 짜리 열 곳을 겹침 검사 없이 뿌린 경우다. 「가장 깊은 겹침」은
	 * 배치의 모든 원-원 교점에서 덮은 원 수를 세어 구한 <b>정확값</b>이고, 넓이는 몬테카를로다
	 * (「종말의 비」가 같은 방법으로 재 두었다).
	 *
	 * <table border="1">
	 *   <caption>한 볼리의 가장 깊은 겹침(10곳 · 20만 볼리)</caption>
	 *   <tr><th>깊이</th><th>그런 볼리의 비율</th></tr>
	 *   <tr><td>2겹</td><td>7.57%</td></tr>
	 *   <tr><td>3겹</td><td>56.62%</td></tr>
	 *   <tr><td>4겹</td><td>29.72%</td></tr>
	 *   <tr><td>5겹</td><td>5.45%</td></tr>
	 *   <tr><td>6겹</td><td>0.59%</td></tr>
	 *   <tr><td>7겹</td><td>0.042%</td></tr>
	 *   <tr><td>8겹</td><td>0.002%</td></tr>
	 * </table>
	 *
	 * <p>곧 <b>3겹 이상 구역이 생기는 볼리가 92.43%</b> 이고 2겹은 <b>100%</b> 다. 넓이는 이렇다
	 * (덮임은 반경 12 안에만 있으므로 그 원 452.4칸²과 견준다. 2026-10-04 에 지대가 원이 되어 이것이
	 * 곧 마지막 지대 전체다. 맨 끝 열의 576칸²은 보더가 정사각형이던 시절의 지대 넓이이고 기록으로
	 * 남긴다).
	 *
	 * <table border="1">
	 *   <caption>그 구역이 지대에서 차지하는 넓이</caption>
	 *   <tr><th>깊이</th><th>지대(원 452.4칸²) 비율</th><th>넓이</th><th>옛 보더(576칸²) 비율</th></tr>
	 *   <tr><td>1겹 이상</td><td>34.63%</td><td>156.7칸²</td><td>27.20%</td></tr>
	 *   <tr><td>2겹 이상</td><td>8.78%</td><td>39.7칸²</td><td>6.90%</td></tr>
	 *   <tr><td><b>3겹 이상</b></td><td><b>1.54%</b></td><td><b>6.96칸²</b></td><td><b>1.21%</b></td></tr>
	 *   <tr><td>4겹 이상</td><td>0.18%</td><td>0.83칸²</td><td>0.15%</td></tr>
	 * </table>
	 *
	 * <p>읽는 법은 이렇다. <b>거의 모든 볼리에 3겹 구역이 있지만 그 넓이는 지대의 1.5%(보더 시절 셈으로
	 * 1.2%)이고,
	 * 무엇보다 {@code break} 때문에 3겹이 3배 피해가 되지 않는다.</b> 그래도 적어 두는 것은 <b>그
	 * {@code break} 가 지워지는 날 이 표가 그대로 현실이 되기 때문</b>이다.
	 *
	 * <p>연달아 맞는 것은 막지 않는다 — <b>세 볼리 연속으로 맞으면 12초에 20.79 로 전멸</b>이다.
	 * 그 사이가 {@link #LIGHTNING_PERIOD_TICKS} 이고 볼리마다 3초 예고가 있다.
	 *
	 * @param beganAt 최후의 저항이 시작한 틱. 시계의 원점이다
	 */
	static void tickLightning(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			long beganAt, long now) {
		// ⚠ 맨 앞이다. 깎아 둔 이동 속도를 걷는 유일한 「시간」 길이고, 볼리가 없는 틱에도 돌아야
		// 한다 — 아래 어느 분기에서 되돌아가도 1초는 1초여야 한다.
		expireSlows(now);
		if (lightningOwner != beganAt) {
			// 남의 판이거나 첫 틱이다. 진입 무적이 끝나는 자리에서 첫 볼리를 연다 —
			// DragonLastStand.ENTRY_GRACE_TICKS 는 첫 패턴을 고르는 시각과 같은 값이고,
			// 그보다 일찍 열면 「저항 V 로 아무 일도 안 일어나는 번개」가 한 번 지나간다.
			lightningOwner = beganAt;
			lightningNextVolleyAt = beganAt + DragonLastStand.ENTRY_GRACE_TICKS;
			lightningStrikeAt = Long.MIN_VALUE;
			lightningSpots = List.of();
		}
		if (lightningStrikeAt == Long.MIN_VALUE) {
			if (now < lightningNextVolleyAt) {
				return;
			}
			lightningSpots = pickLightningSpots(end, dragon.position());
			// 다음 볼리 시각은 자리를 얻었는지와 무관하게 민다. 자리를 못 얻은 볼리를 매 틱
			// 다시 열면 허공만 있는 판에서 하이트맵을 초당 200번 묻는다.
			lightningNextVolleyAt = now + LIGHTNING_PERIOD_TICKS;
			if (lightningSpots.isEmpty()) {
				return;
			}
			lightningStrikeAt = now + LIGHTNING_WARN_TICKS;
		}
		if (now < lightningStrikeAt) {
			warnLightning(end, members, lightningSpots,
					(int) (now - (lightningStrikeAt - LIGHTNING_WARN_TICKS)));
			return;
		}
		// 부등호가 「같다」가 아니라 「지났다」인 것이 요점이다. 틱을 건너뛰어도(렉·시간 정지)
		// 예고를 보여 준 볼리는 반드시 내리친다.
		strikeLightning(end, members, lightningSpots, now);
		lightningStrikeAt = Long.MIN_VALUE;
		lightningSpots = List.of();
	}

	/**
	 * 예고. 노랑 고리와 층 소리. 노랑은 규약의 「번개가 내리친다」다.
	 *
	 * <p>고리를 <b>여섯 틱에 나눠</b> 그린다 — 열 곳을 매 틱 다 그리면 400점이라 부채꼴 예고와
	 * 겹치는 순간 예산이 넘친다({@link #LIGHTNING_MARK_STRIDE}).
	 */
	private static void warnLightning(ServerLevel end, List<ServerPlayer> members, List<Vec3> spots,
			int step) {
		ParticleOptions bolt = TrialWarning.dust(TrialWarning.Colors.LIGHTNING);
		for (Vec3 spot : spots) {
			TrialWarning.markGround(end, spot, LIGHTNING_RADIUS, bolt, LIGHTNING_MARK_STRIDE, step);
		}
		int remaining = LIGHTNING_WARN_TICKS - step;
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage == null || !TrialRisks.stageJustChanged(remaining, LIGHTNING_WARN_TICKS)) {
			return;
		}
		for (ServerPlayer member : members) {
			TrialWarning.soundFor(end, member, stage);
		}
	}

	/**
	 * 열 곳에 내리친다.
	 *
	 * <p>피해원은 {@code lightningBolt()} 다 — 문서가 「낙뢰와 같은 방식」이라고 적어 둔 것을
	 * 값으로 지킨 것이라 <b>피해원도 같아야 한다</b>({@code TrialRisks.detonate} 와 같은 것).
	 * 무장 기준 한 대 6.93 · 세 대 20.79 로 「큰 카드는 세 대에 전멸」에 그대로 얹힌다.
	 *
	 * <p>⚠⚠ <b>{@code break} 를 지우지 말 것.</b> 겹침을 허용한 지금 <b>「한 사람은 한 발」을
	 * 지키는 것이 이 한 줄뿐</b>이고, 3겹 구역은 거의 모든 볼리에 있다(위의 표). 지우면 무장
	 * 기준 20.79 로 그 자리에서 전멸이다.
	 *
	 * <p>⚠ <b>이동 속도 깎기도 그 {@code break} 안쪽이다.</b> 맞는 자리가 겹쳐도 깎는 것은 한 번이고,
	 * {@link #slowStruck} 이 어차피 <b>붙이기 전에 걷어내므로</b> 두 겹이 되지 않는다 —
	 * 안전장치가 둘이 된 것이지 {@code break} 를 대신하는 것이 아니다.
	 */
	private static void strikeLightning(ServerLevel end, List<ServerPlayer> members,
			List<Vec3> spots, long now) {
		for (Vec3 spot : spots) {
			bolt(end, spot);
		}
		for (ServerPlayer member : members) {
			if (member.isSpectator()) {
				continue;
			}
			for (Vec3 spot : spots) {
				if (!TrialRisks.insideMark(member.position(), spot, LIGHTNING_RADIUS)) {
					continue;
				}
				member.hurtServer(end, end.damageSources().lightningBolt(),
						DragonLastStand.LIGHTNING_DAMAGE);
				// 사람이 정한 것 — 「2페이지 번개에 맞으면 그 플레이어만 구속3 1초 걸리게」.
				// 맞은 그 사람에게만이고, 상태이상이 아니라 이동 속도를 직접 깎는다.
				slowStruck(end, member, now);
				// ⚠ 한 사람은 한 발이다. 겹침을 허용했으므로 이 줄이 유일한 안전장치다 —
				// 세 겹 자리에 서 있으면 20.79 로 전멸이고, 그런 자리는 거의 매 볼리에 있다.
				break;
			}
		}
	}

	/**
	 * ⚠⚠ 맞은 <b>그 사람 하나</b>의 이동 속도를 구속 III 급으로 깎는다. <b>상태이상이 아니다.</b>
	 *
	 * <p>사람 말: <b>「그 플레이어만 구속을 구속3급으로 이속을 감소시키는쪽으로가면 되지않나?
	 * 버프효과로 주는게 아니라」</b>. 왜 {@code MobEffects.SLOWNESS} 로는 「그 사람만」이 안 되는지와
	 * 왜 수정자 이름을 바닐라와 달리 두는지는 {@link #LIGHTNING_SLOW_MODIFIER_ID} 에 적어 두었다.
	 *
	 * <h2>⚠ 두 겹으로 쌓이지 않는다 — <b>걷어내기가 먼저다</b></h2>
	 *
	 * <p>{@code removeModifier} 를 먼저 부르는 것이 그것이다. 26.3
	 * {@code AttributeInstance.addTransientModifier} 는 같은 이름이 이미 있으면
	 * <b>{@code IllegalArgumentException} 을 던지고</b>(바이트코드의 {@code putIfAbsent} +
	 * {@code "Modifier is already applied on this attribute!"}), 이름을 달리해 두 벌을 걸면
	 * {@code ADD_MULTIPLIED_TOTAL} 이 <b>곱으로</b> 쌓여 {@code 0.55 × 0.55 = 0.3025} 가 된다 —
	 * 곧 <b>구속 6급</b>이다. 7.8초 주기에 1초 깎기라 겹칠 일이 드물어 보이지만 볼리가 늦게
	 * 내리치는 길이 있고({@code now ≥ lightningStrikeAt}), 무엇보다 <b>드물게 일어나는 것이 가장
	 * 늦게 발견된다.</b> {@code SneakSpeedEffect} 가 같은 순서를 같은 까닭으로 쓴다.
	 *
	 * <h2>⚠ {@code addTransientModifier} 다 — <b>저장되지 않는다</b></h2>
	 *
	 * <p>{@code addPermanentModifier} 로 걸면 <b>사람 파일에 들어가</b> 서버를 껐다 켜도 느린 사람이
	 * 남고, 그것은 우리 코드가 한 줄도 돌지 않는 판에서도 남는다. {@code transient} 는 메모리에만
	 * 있으므로 <b>접속을 끊거나 서버가 내려가는 것만으로 사라진다</b> — 걷어내는 자리 둘
	 * ({@link #expireSlows}·{@link #releaseSlows})은 <b>살아 있는 판</b>을 위한 것이다.
	 *
	 * <h2>되먹임 — 아이콘이 없으므로</h2>
	 *
	 * <p>{@link #LIGHTNING_SLOW_MARK_POINTS} 에 왜 「엔더 파동」과 같은 그림인지, 왜 24 가 아니라
	 * 12 인지, 왜 소리를 더하지 않았는지 적어 두었다.
	 */
	private static void slowStruck(ServerLevel end, ServerPlayer member, long now) {
		AttributeInstance speed = member.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed == null) {
			return;
		}
		// ⚠ 걷어내기가 먼저다. 이 한 줄이 「두 겹으로 쌓이지 않는다」의 전부이고, 없으면
		// addTransientModifier 가 같은 이름에 예외를 던진다.
		speed.removeModifier(LIGHTNING_SLOW_MODIFIER_ID);
		speed.addTransientModifier(new AttributeModifier(LIGHTNING_SLOW_MODIFIER_ID,
				LIGHTNING_SLOW_AMOUNT, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
		SLOWED.put(member.getUUID(), new Slow(member, now + LIGHTNING_SLOW_TICKS));
		// 「엔더 파동」이 발을 묶을 때 쓰는 그림과 같다 — 사람이 이미 배운 신호다. 긴 형식이라
		// 아레나 반대편의 팀원도 「저 사람이 묶였다」를 본다.
		end.sendParticles(ParticleTypes.PORTAL, true, false,
				member.getX(), member.getY() + 0.1, member.getZ(),
				LIGHTNING_SLOW_MARK_POINTS, 0.35, 0.05, 0.35, 0.0);
	}

	/**
	 * ⚠⚠ 1초가 지난 사람에게서 깎기를 걷는다. <b>{@link #tickLightning} 이 매 틱 부른다.</b>
	 *
	 * <p>이 메서드가 「{@value #LIGHTNING_SLOW_TICKS}틱 뒤에 반드시 걷힌다」의 전부다. 상태이상이
	 * 아니므로 바닐라가 세어 주지 않는다 — <b>빠뜨리면 영구히 느린 사람이 남는다.</b>
	 *
	 * <p>걷는 조건이 셋이고, 뒤의 둘은 <b>표에 적힌 시각을 믿을 수 없는 경우</b>를 위한 것이다.
	 *
	 * <ol>
	 *   <li>{@code now} 가 걷을 시각에 닿았다 — 보통의 길이다</li>
	 *   <li>⚠ <b>그 사람이 월드에서 사라졌다</b>({@code isRemoved()}). 접속을 끊거나 죽어서 새
	 *       개체로 돌아온 경우다. 수정자는 {@code transient} 라 그 사람과 함께 이미 사라졌으므로
	 *       여기서 할 일은 <b>표에서 지워 참조를 놓는 것</b>뿐이다</li>
	 *   <li>⚠ <b>걷을 시각이 턱없이 멀다</b>({@code until − now >} 깎는 시간). 이 페이즈는 시계를
	 *       {@code clockBase} 로 미루고 판이 얼 수도 있어 <b>{@code now} 가 뒤로 갈 수 있다</b> —
	 *       그러면 영영 오지 않는 시각을 기다리게 된다. 되감겼다는 것이 드러나는 유일한 표시가
	 *       이 부등호다</li>
	 * </ol>
	 */
	private static void expireSlows(long now) {
		if (SLOWED.isEmpty()) {
			return;
		}
		Iterator<Slow> each = SLOWED.values().iterator();
		while (each.hasNext()) {
			Slow slow = each.next();
			boolean due = now >= slow.until() || slow.until() - now > LIGHTNING_SLOW_TICKS;
			if (!due && !slow.player().isRemoved()) {
				continue;
			}
			unslow(slow.player());
			each.remove();
		}
	}

	/**
	 * ⚠⚠ 깎여 있는 <b>모든</b> 사람에게서 걷는다. {@link #clearState} 가 부른다.
	 *
	 * <p>{@link #expireSlows} 가 「시간이 다했으니」 걷는 쪽이고 이쪽은 <b>「판이 끝났으니」</b>
	 * 걷는 쪽이다. 둘 다 있어야 하는 까닭은 <b>드래곤이 깎인 1초 안에 죽을 수 있기</b> 때문이다 —
	 * 그러면 {@link #tickLightning} 이 다시 불리지 않아 시간 쪽이 영영 안 돈다.
	 *
	 * <p>⚠ <b>월드도 서버도 만지지 않는다.</b> 속성 수정자를 걷는 것은 그 사람의 속성 표를 만지는
	 * 것뿐이라, {@code SERVER_STOPPED} 에서 불려도 할 수 있다 — {@link #SLOWED} 가 UUID 가 아니라
	 * 개체를 들고 있는 유일한 이유가 그것이다.
	 */
	private static void releaseSlows() {
		for (Slow slow : SLOWED.values()) {
			unslow(slow.player());
		}
		SLOWED.clear();
	}

	/**
	 * 한 사람에게서 깎기를 걷는다. <b>이름으로 걷으므로 몇 번 불러도 같다.</b>
	 *
	 * <p>속성이 없을 수가 있는 것은 아니지만({@code Player.createAttributes} 에 이동 속도가 있다)
	 * {@code getAttribute} 가 {@code Nullable} 이라 보고 지난다 — 여기서 터지면 걷어내기가 통째로
	 * 멈춰 <b>영구히 느린 사람</b>이 남는다.
	 */
	private static void unslow(ServerPlayer player) {
		AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
		if (speed != null) {
			speed.removeModifier(LIGHTNING_SLOW_MODIFIER_ID);
		}
	}

	/**
	 * 연출용 번개 하나.
	 *
	 * <p>⚠ <b>{@code setVisualOnly(true)} 를 반드시 켠다.</b> 26.3 {@code LightningBolt} 는 이 깃발
	 * 하나로 두 가지를 끈다 — {@code tick()} 의 피해 구간(켜지 않으면 우리가 준 35 위에 바닐라
	 * 피해가 얹혀 <b>조용히 두 배</b>가 된다)과 {@code spawnFire}(엔드에도 사람이 놓은 블록이 있고,
	 * 우리 카드 어디에도 「불」이 적혀 있지 않다). <b>로그도 빌드도 알려 주지 않는</b> 깃발이라
	 * {@code DragonLastStandPatternsTest} 가 클래스 파일에서 직접 찾는다.
	 *
	 * <p>{@code TrialRisks.strikeLightning} 과 같은 코드인데 그쪽이 {@code private} 이다. 열어
	 * 달라고 고치지 않은 것은 {@code TrialRisks} 를 읽기만 하기로 정해져 있기 때문이고, 같은
	 * 것이 두 벌이 된 사실은 인계에 적어 두었다.
	 *
	 * <p>소리와 파티클을 덧붙이지 않는다. 번개 엔티티가 스스로 천둥과 착탄음을 내는데 그 위에
	 * 폭발 파티클을 뿌리면 TNT 처럼 보인다 — 「낙뢰」가 이미 겪은 일이다.
	 */
	private static void bolt(ServerLevel end, Vec3 at) {
		LightningBolt spawned = EntityTypes.LIGHTNING_BOLT.create(end, EntitySpawnReason.TRIGGERED);
		if (spawned == null) {
			return;
		}
		spawned.setVisualOnly(true);
		// 표식을 그린 바로 그 자리에 세운다. 블록 중앙으로 맞추면 고리와 번개가 어긋난다.
		spawned.snapTo(at);
		end.addFreshEntity(spawned);
	}

	/**
	 * 한 볼리의 열 곳을 고른다. <b>자리는 무작위이고 겹침을 보지 않는다.</b>
	 *
	 * <p>굴리는 자는 {@code TrialRisks.arenaOffset} 이다 — 분포(원 안에 고르게)가 남의 카드와
	 * 같아야 한다. ⚠ 넘기는 반경만 {@link #LIGHTNING_FIELD_RADIUS} 이고 아레나 반경이 아니다.
	 *
	 * <p>허공인 자리는 <b>버린다</b>. 중앙 섬은 둥글지 않아 반경 9.45 안에도 사람이 파 놓은 구멍이
	 * 있을 수 있고, 허공에 내리치면 예고도 피해도 뜻이 없다.
	 *
	 * <p>{@code TrialRisks.SPOT_TRIES} 로 다시 굴리지 <b>않는다.</b> 그 되풀이는 「겹치지 않는 자리를
	 * 찾는」 장치인데 우리는 겹침을 허용하므로 남는 이유가 「허공을 피하는」 것뿐이고, 드래곤 발밑
	 * 9.45칸은 바닐라 엔드에서 통째로 엔드스톤이라 <b>한 번에 거의 다 맞는다.</b> 다시 굴리면 판 곳곳에
	 * 구멍을 파 둔 서버에서 열 곳이 몇 번씩 굴려지는 값만 늘어난다.
	 */
	private static List<Vec3> pickLightningSpots(ServerLevel end, Vec3 center) {
		List<Vec3> spots = new ArrayList<>(LIGHTNING_COUNT);
		RandomSource random = end.getRandom();
		for (int index = 0; index < LIGHTNING_COUNT; index++) {
			Vec3 offset = lightningOffset(random.nextDouble(), random.nextDouble());
			double x = center.x + offset.x;
			double z = center.z + offset.z;
			BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
					BlockPos.containing(x, 0.0, z));
			// 허공이면 하이트맵이 월드 바닥을 돌려준다.
			if (ground.getY() > end.getMinY()) {
				spots.add(new Vec3(x, ground.getY(), z));
			}
		}
		return spots;
	}

	/**
	 * 드래곤을 중심으로 한 자리 하나의 <b>상대 좌표</b>.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 굴림을 격자로 훑어 <b>모든 자리가
	 * {@link #LIGHTNING_FIELD_RADIUS} 안</b>인지, 곧 <b>모든 고리가 마지막 지대 안</b>인지 본다.
	 *
	 * @param roll 0 이상 1 미만 둘. 실전에서는 {@code end.getRandom().nextDouble()} 이 준다
	 */
	static Vec3 lightningOffset(double roll, double otherRoll) {
		return TrialRisks.arenaOffset(roll, otherRoll, LIGHTNING_FIELD_RADIUS);
	}

	// ------------------------------------------------------------------ 예산

	/**
	 * <b>한 틱에 가장 많이 나가는</b> 점 수.
	 *
	 * <p>⚠ <b>더하기다.</b> 패턴 넷은 동시에 돌지 않지만({@code DragonLastStand.advance})
	 * <b>번개는 언제나 함께 돈다</b> — 번개가 패턴이었을 때의 「셋 중 가장 바쁜 것」은 이제 틀린
	 * 식이다. {@code TrialLandingShock.MAX_POINTS_PER_TICK}(440)과 견줄 값이다.
	 *
	 * <p>부채꼴과 십자의 빨간 면은 디스플레이 개체라 <b>점을 한 개도 쓰지 않는다</b>
	 * ({@link DragonLastStandConePanel}·{@link DragonLastStandCrossPanel}).
	 *
	 * <p>⚠ <b>안전지대는 이 수에 없다 — 그런데 0 이 아니다.</b> 2026-10-04 까지는 월드 보더라 점을 한
	 * 개도 안 썼다. 보더를 버린 뒤로 벽이 빨간 입자 기둥이라 한 틱 최대 <b>39점</b>을 따로 쓴다
	 * ({@link DragonLastStandZoneWall#worstPointsPerTick}). 패턴·파도와 언제나 함께 돌므로 그 위에
	 * <b>더한다</b> — 패턴 322 + 파도 37 + 벽 39 = <b>398</b>, {@code DragonLastStandZoneWallTest} 가 그 합을
	 * 400 이하로 붙든다.
	 *
	 * <p>값에서 직접 센다 — 개수나 반경을 올리는 사람이 예산을 눈으로 세지 않아도 시험이 먼저
	 * 멈춰 세운다.
	 */
	static int worstCasePointsPerTick() {
		return lightningPoints() + Math.max(Math.max(wingBeatPoints(), conePoints()),
				Math.max(suckPoints(), crossPoints()));
	}

	/** 가장 바쁜 틱에 팀원 수를 넷으로 본다. 사람마다 한 발씩 나가는 연출을 셀 때 쓴다. */
	private static final int BUDGET_MEMBERS = 4;

	/**
	 * 날개 퍼덕이기 — 파랑 고리 둘(반경 4 · 12) + <b>그 둘의 벽</b> + <b>바깥으로 흐르는 가닥</b>
	 * + 충격파 틱의 돌풍.
	 *
	 * <p>가장 바쁜 틱은 충격파가 나가는 틱이다.
	 */
	static int wingBeatPoints() {
		int rings = TrialWarning.ringPoints(WING_NEAR_RADIUS)
				+ TrialWarning.ringPoints(WING_STRONG_RADIUS);
		int walls = ringWallPoints(WING_NEAR_RADIUS) + ringWallPoints(WING_STRONG_RADIUS);
		// 큰 돌풍 하나 + 밀린 사람마다 작은 돌풍 하나.
		return rings + walls + WING_GUST_SPOKES + 1 + BUDGET_MEMBERS;
	}

	/** 부채꼴 브레스 — 예고 틱과 터지는 틱 가운데 바쁜 쪽. */
	static int conePoints() {
		return Math.max(coneWarnPoints(), coneFirePoints());
	}

	/**
	 * 예고 틱 — 가장자리 두 줄(바닥 + 흰 벽) · 사거리 호 · <b>안쪽 호 셋</b> · 입의 불씨.
	 *
	 * <p>{@code +1} 이 붙는 것은 호가 양 끝을 모두 찍기 때문이다({@code index <= points}).
	 */
	static int coneWarnPoints() {
		int edgeGround = (int) (CONE_RANGE / MARK_GAP);
		int edgeWall = (int) (CONE_RANGE / EDGE_WALL_GAP);
		int outerArc = arcPoints(CONE_RANGE, MARK_GAP) + 1;
		int innerArcs = 0;
		for (double fraction : CONE_ARC_FRACTIONS) {
			innerArcs += arcPoints(CONE_RANGE * fraction, EDGE_WALL_GAP) + 1;
		}
		return (edgeGround + edgeWall) * 2 + outerArc + innerArcs + 1;
	}

	/** 터지는 틱과 그 뒤 불꽃 — 부채꼴을 채우는 브레스 파티클. 예고 표식은 그 틱에 안 나간다. */
	static int coneSprayPoints() {
		int points = 0;
		for (double radius = 4.0; radius <= CONE_RANGE; radius += 4.0) {
			points += arcPoints(radius, 2.0) + 1;
		}
		return points;
	}

	/** 터지는 <b>그 한 틱</b>에만 나가는 불꽃({@link #burnCone}). 뒤이은 20틱에는 안 나간다. */
	static int coneFlamePoints() {
		int points = 0;
		for (double radius = FLAME_RING_STEP; radius <= CONE_RANGE; radius += FLAME_RING_STEP) {
			points += arcPoints(radius, FLAME_ARC_GAP) + 1;
		}
		return points;
	}

	/** 터지는 틱 — 브레스 파티클과 불꽃이 <b>함께</b> 나간다. */
	static int coneFirePoints() {
		return coneSprayPoints() + coneFlamePoints();
	}

	/**
	 * 공허 흡입 — 예고 틱과 터지는 틱 가운데 바쁜 쪽.
	 *
	 * <p>터지는 틱에는 표식이 없고 파티클 꾸러미 둘뿐이라 예고 쪽이 언제나 이긴다.
	 */
	static int suckPoints() {
		return Math.max(suckWarnPoints(), 2);
	}

	/**
	 * 예고 틱 — 검은 속(나눠 그린 한 틱 몫) + 빨간 경계 고리(매 틱 전부) + <b>그 고리의 벽</b>
	 * + <b>안으로 흐르는 가닥</b> + <b>불 결계의 불꽃 벽</b>(2026-10-04, 나눠 세운 한 틱 몫 17점).
	 *
	 * <p>흡입 구간에도 같은 것이 나가고 결계가 때리는 틱에 더 나가는 점은 없다(피해와 소리뿐이다).
	 * 끌려가는 사람 발밑의 재는 꾸러미 하나라 사람당 한 장이고, 0.5초마다라 예고 틱과 같은 틱에 와도
	 * 이 수가 넉넉히 남는다 — 이 패턴은 가장 바쁜 틱의 주인이 아니다.
	 *
	 * <p>속을 나눠 그리지 않으면 여기가 231점 더 올라 <b>부채꼴 예고를 넘어</b>
	 * {@link #worstCasePointsPerTick} 의 답이 바뀐다 — {@link #SUCK_MARK_STRIDE} 를 볼 것.
	 */
	static int suckWarnPoints() {
		int fill = 0;
		for (double radius = MARK_GAP; radius < SUCK_RADIUS; radius += MARK_GAP) {
			fill += suckFillPoints(radius);
		}
		// 나눠 그리기는 위상에 따라 하나 더 나갈 수 있다. 예산은 늘 나쁜 쪽을 봐야 한다.
		int stroke = (fill + SUCK_MARK_STRIDE - 1) / SUCK_MARK_STRIDE;
		return stroke + TrialWarning.ringPoints(SUCK_RADIUS) + ringWallPoints(SUCK_RADIUS)
				+ SUCK_STREAM_SPOKES * SUCK_STREAM_PHASES + suckFirePoints();
	}

	/**
	 * 불 결계의 불꽃 벽 한 틱 몫. 둘레 점을 {@link #SUCK_FIRE_STRIDE} 로 나눈다.
	 *
	 * <p>나눠 세우기는 위상에 따라 하나 더 나갈 수 있으므로 올림이다 — 예산은 늘 나쁜 쪽을 본다.
	 */
	static int suckFirePoints() {
		int ring = TrialWarning.ringPoints(SUCK_FIRE_RADIUS);
		return (ring + SUCK_FIRE_STRIDE - 1) / SUCK_FIRE_STRIDE;
	}

	/**
	 * 십자 균열 — 예고 틱과 터지는 틱 가운데 바쁜 쪽.
	 *
	 * <p>⚠ <b>터지는 틱에는 둘이 함께 나간다.</b> 그 틱에 앞 회차가 터지고 다음 회차의 예고가
	 * 시작하므로({@link #crossPendingRound} 의 경고) 갈라짐 연출과 새 표식이 같은 틱이다. 예고가 이제
	 * 바닥 면이라 0점이지만 식은 그대로 둔다 — 예고에 점을 다시 얹는 날 이 합이 저절로 맞다.
	 *
	 * <p>⚠ 갈라짐 연출이 <b>회차의 십자 수만큼</b>이다(2026-10-04 부터 둘). 한 회차에 둘이 터지므로
	 * 둘 다 갈라지는 모습이 보여야 「두 개 같이 터지고」가 눈에 보인다.
	 */
	static int crossPoints() {
		return Math.max(crossWarnPoints(),
				CROSSES_PER_ROUND * crossFlashPoints() + crossLiftPoints() + crossWarnPoints());
	}

	/**
	 * 띄워진 사람마다 발밑에 서는 기둥({@link #liftCross}). <b>사람당 한 점</b>이다.
	 *
	 * <p>개수를 0 으로 보내 속도로 읽히게 하는 길이라 기둥 하나가 점 하나다 — 그래서 넷이 다 맞아도
	 * {@value #BUDGET_MEMBERS} 점이다. 십자가 둘이어도 <b>사람당 한 번</b>만 띄우므로({@link #fireRound})
	 * 이 수는 그대로다. 날개 퍼덕이기가 밀린 사람마다 돌풍 하나를 쓰는 것과
	 * <b>같은 셈</b>이다({@link #wingBeatPoints}).
	 *
	 * <p>⚠ 터지는 틱에만 나간다. 그 틱이 이미 이 페이즈의 가장 바쁜 틱이므로 <b>이 넷이 그대로
	 * 최악에 더해진다</b> — 시험이 그 수를 못박는다.
	 */
	static int crossLiftPoints() {
		return BUDGET_MEMBERS;
	}

	/**
	 * 예고 틱 — <b>0점</b>이다. 2026-10-04 에 먼지 선(54)과 흰 기둥 벽(108)을 걷고 바닥 면
	 * ({@link DragonLastStandCrossPanel})으로 바꿨다. 판은 디스플레이 개체라 점을 안 쓴다.
	 *
	 * <p>메서드를 지우지 않은 것은 {@link #crossPoints} 의 식이 「터지는 틱 = 갈라짐 + 띄움 + 다음
	 * 예고」를 그대로 말하게 하려는 것이다. 예고에 점을 다시 얹는 사람은 여기에 더하면 된다.
	 */
	static int crossWarnPoints() {
		return 0;
	}

	/**
	 * 터지는 틱에만 나가는 갈라짐 연출 — <b>십자 하나</b> 몫. 중심선 둘뿐이라 성기다. 한 회차에
	 * 십자가 {@link #CROSSES_PER_ROUND} 개라 {@link #crossPoints} 가 그만큼 곱한다.
	 *
	 * <p>납작한 {@code SWEEP_ATTACK} 과 <b>솟는 기둥</b>이 함께 나간다 — 눕는 것만으로는 터진 것이
	 * 눈높이에서 안 보인다({@link #flashCross}).
	 */
	static int crossFlashPoints() {
		return 2 * crossLinePoints(CROSS_FLASH_GAP) + 2 * crossLinePoints(CROSS_BURST_GAP);
	}

	/** 중심을 지나 양쪽으로 뻗는 선 하나에 찍는 점 수. */
	static int crossLinePoints(double gap) {
		if (!(gap > 0.0)) {
			return 1;
		}
		return (int) Math.floor((CROSS_REACH * 2.0) / gap) + 1;
	}

	/**
	 * 상시 번개 — <b>예고 틱과 내리치는 틱 가운데 바쁜 쪽.</b>
	 *
	 * <p>⚠ <b>예전에는 예고 틱만 셌다.</b> 「내리치는 틱에는 표식이 없고 번개 엔티티뿐이라 0 이다」가
	 * 그 근거였는데, 번개가 <b>맞은 사람 발밑에 점을 뿌리게</b> 되면서({@link #slowStruck}) 그 말이
	 * 거짓이 됐다. 내리치는 틱이 더 바빠지는 날 <b>이 식이 조용히 틀린 답을 주면</b> 예산을 넘긴
	 * 것을 아무도 모른다.
	 *
	 * <p>지금은 예고 {@code 70} 대 내리침 {@code 48} 이라 답이 안 바뀐다 —
	 * {@link #LIGHTNING_SLOW_MARK_POINTS} 가 그 여유를 수로 적어 두었다.
	 */
	static int lightningPoints() {
		return Math.max(lightningWarnPoints(), lightningStrikePoints());
	}

	/** 예고 틱 — 노랑 고리 열 개를 <b>여섯 틱에 나눠</b> 그린 한 틱 몫. 예고가 60틱이라 거의 언제나다. */
	static int lightningWarnPoints() {
		return LIGHTNING_COUNT * TrialWarning.strokePoints(LIGHTNING_RADIUS, LIGHTNING_MARK_STRIDE);
	}

	/**
	 * 내리치는 틱 — <b>바닥 표식이 없고</b> 맞은 사람마다의 발밑 입자뿐이다.
	 *
	 * <p>번개 엔티티는 클라이언트가 스스로 그리므로 점을 쓰지 않는다. 여기서 세는 것은 이동 속도를
	 * 깎였다는 신호 하나이고({@link #LIGHTNING_SLOW_MARK_POINTS}), 최악은 <b>넷이 다 맞은</b> 경우다.
	 */
	static int lightningStrikePoints() {
		return BUDGET_MEMBERS * LIGHTNING_SLOW_MARK_POINTS;
	}
}
