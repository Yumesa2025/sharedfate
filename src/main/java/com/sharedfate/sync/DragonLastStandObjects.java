package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 「최후의 저항」의 <b>오브젝트 파도</b>. 드래곤 체력 <b>25%와 10%, 두 번만</b> 온다.
 *
 * <h2>사람이 정한 값 — 하나도 바꾸지 말 것</h2>
 *
 * <table border="1">
 *   <caption>사람이 정한 것</caption>
 *   <tr><th>것</th><th>값</th></tr>
 *   <tr><td>오는 때</td><td>드래곤 체력 <b>25%와 10%, 두 번만</b>. 주기적이 아니다</td></tr>
 *   <tr><td>개수</td><td>1명 <b>2</b> · 2명 <b>4</b> · 3명 <b>5</b> · 4명 <b>6</b>. 두 번 다 같은 수</td></tr>
 *   <tr><td>체력</td><td><b>30</b></td></tr>
 *   <tr><td>연결</td><td>박힌 뒤 <b>2초</b>에 드래곤에 붙는다({@link #LINK_DELAY_TICKS})</td></tr>
 *   <tr><td>회복</td><td>연결된 뒤로 <b>초당 최대 체력의 0.5%</b>({@link #LEAK_FRACTION_PER_SECOND}).
 *       <b>부술 때까지</b> 계속이고 <b>크리스탈마다 따로</b> 센다</td></tr>
 *   <tr><td>타이머</td><td><b>없다.</b> 「시간 안에 부숴라」가 아니라 <b>「살아 있는 동안 계속
 *       샌다」</b>다</td></tr>
 *   <tr><td>서는 모습</td><td><b>하늘에서 순차로</b> 박힌다 — 먼저 <b>흰 신호기 선</b>이 서고
 *       하나씩 떨어진다</td></tr>
 *   <tr><td>크기</td><td><b>블록 한 칸</b>({@link #CRYSTAL_HEIGHT})</td></tr>
 *   <tr><td>바</td><td><b>하나도 없다.</b> 크리스탈마다 뜨던 것도, 가운데 타이머도 —
 *       아래 「바가 하나도 없다」</td></tr>
 * </table>
 *
 * <h2>⚠⚠ 2026-10-01 — 회복 방식이 통째로 바뀌었다</h2>
 *
 * <p>사람 말: <b>「크리스탈 소환이거 소환되자마자 엔더드래곤 연결되고 초당 1프로씩 엔더드래곤
 * 체력 차게 부술떄까지. 그니까 소환되고 2초뒤부터 엔더드래곤에 연결되고 선이 회복되는거야」</b>
 *
 * <p>그 앞은 <b>「15초 안에 못 부수면 하나마다 5%」</b>였다. 바뀐 것이 셋이다 — ① <b>타이머가
 * 없다</b>(부술 때까지다) ② 회복이 <b>못 부순 벌</b>이 아니라 <b>살아 있는 동안의 누수</b>다
 * ③ <b>크리스탈마다 제 시계로</b> 샌다(박힌 뒤 2초).
 *
 * <h2>⚠⚠ 수로 읽은 이 파도의 무게 — <b>값에서 직접 센 것이다</b></h2>
 *
 * <p>최대 체력은 {@code SharedFateConfig.dragonHealthPerMember}(<b>600</b>)에 인원을 곱한 것이고
 * ({@code DragonTrialManager.strengthenDragon}) 4인이면 <b>2400</b> 이다.
 *
 * <table border="1">
 *   <caption>4인 기준 2400 에서 실제로 되돌아가는 양</caption>
 *   <tr><th>것</th><th>수</th></tr>
 *   <tr><td>크리스탈 하나가 1초에</td><td>2400 × 0.5% = <b>12</b></td></tr>
 *   <tr><td>크리스탈 하나가 1틱에</td><td>12 ÷ 20 = <b>0.6</b>({@link #leakPerTick})</td></tr>
 *   <tr><td><b>여섯이 살아 있으면 1초에</b></td><td><b>72</b></td></tr>
 *   <tr><td>이 페이즈가 깎아야 하는 것</td><td><b>1200</b>(30% 로 들어와 +20%p 를 받아 50% 에서
 *       시작한다 — {@code DragonLastStand.healedHealth})</td></tr>
 *   <tr><td>⚠ <b>여섯이 살아 있으면 전부 되돌아오는 시간</b></td><td>1200 ÷ 72 =
 *       <b>16.6초</b></td></tr>
 * </table>
 *
 * <p>⚠ <b>사람이 1% 에서 0.5% 로 내렸다(2026-10-01). 또 움직일 값이다.</b> 1% 면 하나가 초당
 * <b>24</b>, 여섯이 <b>144</b> 라 <b>8.3초</b>면 이 페이즈가 깎아야 할 전부가 되돌아온다. 체력 30
 * 짜리 여섯을 2초 안에 다 부수는 것은 어떤 무기로도 불가능하므로(아래 「체력 30 이…」) 1% 는
 * <b>연결되는 그 순간 이미 과했다</b> — 사람에게 그 수를 알리고 받은 답이 0.5% 다.
 *
 * <p>✅ <b>그 값은 {@link #LEAK_FRACTION_PER_SECOND} 한 곳에만 적혀 있다.</b> 시험도 거기서 뽑아
 * 쓴다 — 또 움직일 값이 두 군데에 적히면 한쪽만 고쳐지는 날이 온다.
 *
 * <h2>사람이 플레이하고 말한 것 넷 — 여기가 그 답이다</h2>
 *
 * <p>사람 말: <b>「크리스탈들 소환되서 체력 차는거 크리스탈들 너무 작아서 잘 보이지도 않고 크기를
 * 블럭 정도 크기로 키웟으면좋겟어. 그리고 그 크리스탈들 체력바를 없애. 그리고 그 크리스탈에서
 * 엔더드래곤으로 연결해서 체력을 회복하고잇다는걸 보여줫으몀해. 그리고 크리스탈로 체력회복한다는
 * 소리도 있었으면 좋을거같아」</b>
 *
 * <ol>
 *   <li><b>크기</b> — {@link #CRYSTAL_HEIGHT}. 보이는 것을 갑옷 거치대에서 떼어
 *       {@link DragonLastStandShells} 에 맡겼다. 까닭과 「보이는 크기 = 맞는 상자」는 아래
 *       「모습과 상자」에 있다</li>
 *   <li><b>바를 전부 없앴다</b> — 아래 「바가 하나도 없다」</li>
 *   <li><b>연결선</b> — {@link #beams}. <b>연결된 크리스탈마다</b> 상시로 나간다(아래 「회복은
 *       연결된 동안 계속 돈다」)</li>
 *   <li><b>소리</b> — {@code block/amethyst/resonate1~4}(아래 {@link #LEAK_SOUND_TICKS})</li>
 * </ol>
 *
 * <h2>⚠⚠ 무엇으로 만들었는가 — <b>진짜 엔드 크리스탈은 쓸 수 없다</b></h2>
 *
 * <p>사람이 「엔드 크리스탈 같은 것」이라고 했고, 처음에는 진짜 {@code EndCrystal} 을 쓸 생각이었다.
 * 26.3 바이트코드를 풀어 읽고 <b>세 가지가 각각 혼자서</b> 그 길을 막는다는 것을 확인했다.
 *
 * <ol>
 *   <li>⚠⚠ <b>크리스탈에는 체력이 없다.</b> {@code EndCrystal.hurtServer} 는 한 대라도 맞으면
 *       그 자리에서 {@code remove} 하고 폭발한다 — 누적할 칸이 없으므로 <b>「체력 30」을 담을
 *       그릇이 아니다.</b> 사람이 정한 값이 30 인 이상 이것만으로 결론이 난다</li>
 *   <li><b>드래곤을 회복시킨다.</b> {@code EnderDragon.checkCrystals} 가 <b>드래곤 상자를 32칸
 *       부풀린 범위</b>에서 {@code getEntitiesOfClass(EndCrystal.class, ...)} 로 가장 가까운 것을
 *       찾아 <b>10틱마다 체력 1</b>을 준다(바이트코드로 확인했다). 오브젝트는 드래곤 발밑
 *       {@value #RING_RADIUS}칸에 서므로 반드시 그 안이고, <b>초당 2</b> 를 공짜로 돌려준다 —
 *       {@code DragonLastStand.enter} 가 진입할 때 크리스탈을 전부 지우는 이유가 정확히 그것이다.
 *       ⚠ 이제 더 나쁘다. <b>우리가 재는 누수가 초당 12 인데 바닐라가 거기에 2 를 더 얹으므로</b>
 *       사람이 보는 수가 우리 값이 아니게 되고, 그 2 는 <b>연결되기 전 2초에도</b> 들어간다 —
 *       「2초 전에는 아무 일도 없다」가 그 자리에서 거짓이 된다.
 *       ⚠ <b>하위 클래스로도 못 피한다.</b> 그 조회가 클래스로 거르므로 물려받는 순간 걸린다</li>
 *   <li><b>깨질 때 위력 6 으로 터진다.</b> 여섯이 사람 사이에서 터지면 이 페이즈가 금지한
 *       「대응 불가」가 된다</li>
 * </ol>
 *
 * <p>⚠ 넷째가 더 있다. <b>엔드 크리스탈은 제 발밑에 불을 심는다</b> — 그 불이 매 틱 크리스탈을
 * 때리고, 바닐라는 뒤에서 「불 면역」으로 버리지만 우리 피해 규칙이 앞에 서 있어 초당 20번
 * 「막았다」를 세게 된다. 쓰지 않는 쪽이 답인 이유가 하나 더 늘었을 뿐이다.
 *
 * <h2>모습과 상자 — <b>개체 둘이 나눠 든다</b></h2>
 *
 * <p>사람이 「너무 작아서 잘 보이지도 않고」라고 했다. 머리 아이템으로는 그것을 고칠 수 없다는
 * 근거가 {@link DragonLastStandShells} 클래스 설명에 둘 적혀 있다 — <b>머리 장비는 0.625배로
 * 그려지고</b>, <b>엔드 크리스탈 <u>아이템</u>은 납작한 그림 한 장</b>이라 옆에서 보면 사라진다.
 *
 * <table border="1">
 *   <caption>크기를 키우는 두 길을 재어 봤다</caption>
 *   <tr><th>길</th><th>재어 본 것</th></tr>
 *   <tr><td>26.3 {@code scale} 속성</td>
 *       <td><b>있다.</b> {@code Attributes.SCALE} 이
 *           {@code RangedAttribute("attribute.name.scale", 1.0, 0.0625, 16.0)} 이고
 *           {@code setSyncable(true)} 다. 갑옷 거치대도 가진다 —
 *           {@code ArmorStand.createAttributes()} 가 {@code createLivingAttributes()} 를 부르고
 *           그 안에 {@code .add(Attributes.SCALE)} 가 있다. 그리고 <b>상자와 그림이 함께</b>
 *           커진다: {@code LivingEntity.getDimensions} 가
 *           {@code getDefaultDimensions(pose).scale(getScale())} 이고,
 *           {@code LivingEntityRenderer} 가 {@code LivingEntity.getScale()} 을 렌더 상태에 담아
 *           {@code PoseStack.scale} 한다(넷 다 26.3 바이트코드로 확인했다).
 *           <b>그런데 이 자리에서는 못 쓴다</b> — 아래 ⚠</td></tr>
 *   <tr><td><b>{@code item_display} + 변환 확대</b></td>
 *       <td>← 고른 것. 디스플레이는 {@code isPickable()} 이 거짓이라 <b>때릴 수 없으므로</b>
 *           맞는 상자는 갑옷 거치대가 계속 든다. 그 둘을 <b>값 하나로</b> 묶는 수단이 있다 —
 *           아래</td></tr>
 * </table>
 *
 * <p>⚠ <b>{@code scale} 속성이 이 자리에서 안 되는 까닭은 「상자가 발밑에서 자란다」다.</b>
 * {@code EntityDimensions} 의 상자는 개체 좌표에서 <b>위로만</b> 자라는데(
 * {@code makeBoundingBox}) 머리 아이템은 <b>머리 높이에</b> 그려진다. 곧 배율을 올리면 상자와
 * 그림이 <b>같이 커지지만 같은 자리에 있지 않다</b> — 보이는 크리스탈 아래 허공이 맞고, 그것이
 * 바로 이 저장소가 표식에서 금지한 「보이는 것과 맞는 것이 다름」이다.
 *
 * <p>✅ <b>그래서 모습을 떼어 냈다.</b> 아이템 디스플레이는 <b>제 좌표가 그림의 가운데</b>이므로
 * ({@code ItemTransform.apply} 가 {@code translate(−0.5,−0.5,−0.5)} 를 넣는다 — 바이트코드로
 * 확인했다) 갑옷 거치대를 <b>그림의 가운데가 상자의 가운데가 되는 높이</b>에 놓으면 둘이 정확히
 * 겹친다. 그 계산이 {@link #crystalCenterY} 와 {@link #standFeetY} 한 쌍이고,
 * <b>{@link #CRYSTAL_HEIGHT} 하나가 그림의 높이와 상자의 높이를 함께 정한다.</b>
 *
 * <p>⚠ 상자를 {@code EntityDimensions.fixed} 로 만든 것도 그 약속을 지키는 장치다 — 26.3
 * {@code EntityDimensions.scale} 이 <b>{@code fixed} 면 자신을 그대로 돌려주므로</b>(바이트코드로
 * 확인했다) 누가 이 개체에 {@code scale} 속성을 걸어도 <b>상자가 그림에서 떨어져 나가지 않는다.</b>
 *
 * <p>✅ <b>맞는 상자를 드는 것은 {@code armor_stand} 하위 클래스다</b>({@link Shard}).
 *
 * <table border="1">
 *   <caption>재어 본 길들</caption>
 *   <tr><th>길</th><th>왜 안 되는가 / 왜 골랐는가</th></tr>
 *   <tr><td>{@code end_crystal}</td><td>위의 셋(+불)</td></tr>
 *   <tr><td>몹({@code zombie} 등)에 최대 체력 30</td>
 *       <td>피해는 제대로 들어간다. 그런데 <b>죽는 순간 바닐라가 전리품과 경험치를 떨어뜨리고</b>
 *           죽는 소리가 그 몹의 것이다 — 「엔드 크리스탈 같은 것」이 좀비 소리를 내며 썩은 살을
 *           떨어뜨린다</td></tr>
 *   <tr><td>디스플레이 개체만</td>
 *       <td>아니다. {@code Display} 는 {@code isPickable()} 이 거짓이라 <b>때릴 수가 없다</b></td></tr>
 *   <tr><td><b>{@code armor_stand} 하위 클래스 + {@code item_display}</b></td>
 *       <td>← 고른 것. 아래 「왜 갑옷 거치대인가」</td></tr>
 * </table>
 *
 * <h2>왜 갑옷 거치대인가 — 얻는 것이 넷이다</h2>
 *
 * <ol>
 *   <li><b>가만히 있는다.</b> {@code setNoGravity(true)} 면 {@code ArmorStand.hasPhysics()} 가
 *       거짓이 되어 {@code travel()} 이 첫 줄에서 돌아간다(26.3 바이트코드로 확인했다). AI 도
 *       공격도 없다 — 하늘에 떠 있는 표적이 스스로 움직이지 않는다</li>
 *   <li>⚠ <b>피해 규칙을 우리가 쓴다.</b> 바닐라 {@code ArmorStand.hurtServer} 는 두 번 치면
 *       부서지고 <b>투명하면 아예 피해를 안 받는다</b>(바이트코드에 {@code invisible} 검사가
 *       있다). 그 규칙을 통째로 덮어써야 「체력 30」이 성립하고, 하위 클래스라 그것이 된다 —
 *       덤으로 <b>무엇으로 깎이는가</b>를 한 함수({@link #accepts})로 못박을 수 있다</li>
 *   <li><b>전리품도 경험치도 죽는 소리도 없다.</b> 바닐라 체력을 한 번도 건드리지 않으므로
 *       {@code LivingEntity.die()} 가 아예 불리지 않는다 — 부서지는 것은 우리가
 *       {@code discard()} 하는 것이다</li>
 *   <li><b>바닐라 개체 종류 그대로</b>({@code EntityTypes.ARMOR_STAND})라 통신 규약이 올라가지
 *       않고 모드를 안 깐 사람에게도 보인다 — {@code DragonLastStandConePanel} 이
 *       {@code block_display} 를 물려받은 것과 같은 수법이다</li>
 * </ol>
 *
 * <p>⚠ <b>장비 칸은 이제 비워 둔다.</b> 머리에 아이템을 끼우면 그 0.625배 그림이 상자 위쪽에 하나
 * 더 떠서 <b>디스플레이가 그린 크리스탈과 둘이 보인다.</b>
 *
 * <h2>체력 30 이 무엇으로 깎이는가 — <b>원인이 있는 피해만</b></h2>
 *
 * <p>{@link #accepts} 한 줄이 답이고 사람이 물은 「근접·활·폭발 전부?」에 <b>전부 그렇다</b>다.
 * 기준은 <b>「누가 했는가」</b>다.
 *
 * <ul>
 *   <li><b>가해자가 있으면 받는다</b>({@code source.getEntity() != null}). 근접·활·쇠뇌·삼지창·
 *       TNT·크리스탈 폭발이 전부 여기 든다 — 화살은 {@code getEntity()} 가 <b>쏜 사람</b>이다</li>
 *   <li><b>가해자가 없어도 폭발이면 받는다</b>({@code DamageTypeTags.IS_EXPLOSION}). 사람이 놓고
 *       도망간 TNT 처럼 가해자가 사라진 폭발을 「아무도 안 했다」로 읽으면 안 된다</li>
 *   <li><b>그 밖은 안 받는다.</b> 낙하·허공·불·선인장처럼 <b>팀이 하지 않은 일</b>로 오브젝트가
 *       사라지면 누수가 저절로 멈춰 파도가 공짜가 된다</li>
 * </ul>
 *
 * <p>⚠ 이 페이즈의 공격이 오브젝트를 깨뜨리지 않는지 확인했다. 상시 번개는
 * {@code setVisualOnly(true)} 라 피해가 없고, 부채꼴·흡입·균열은 <b>사람에게 직접</b>
 * {@code hurtServer} 를 부르므로 범위에 든 개체를 치지 않는다. 곧 <b>드래곤이 제 오브젝트를
 * 깨뜨려 주는 일이 없다.</b>
 *
 * <p>⚠ {@code DamageTypeTags.BYPASSES_INVULNERABILITY}(운영자 {@code /kill})만은 그대로
 * 통과시킨다 — 운영자가 판을 치울 길을 막지 않는다. 그때는 <b>부순 것으로 센다</b>(누수 멈춤).
 *
 * <p>⚠⚠ <b>언제나 부술 수 있다.</b> 「회복이 도는 1.5초 동안은 못 부순다」가 있었고
 * ({@code Shard.spent}) <b>걷어냈다</b> — 그것은 「못 부순 몫이 이미 굳었다」는 옛 규칙의 꼬리였다.
 * 이제 <b>굳는 몫이 없다.</b> 부수는 것이 누수를 멈추는 <b>유일한 대응</b>이므로 그 길을 한 틱도
 * 막으면 안 된다.
 *
 * <h2>바가 하나도 없다 — <b>셀 것이 없어졌다</b></h2>
 *
 * <p>크리스탈마다 바가 하나씩 떠 있었다. 그것은 <b>타이머</b>였는데(여섯이 같은 수를 가리켰다)
 * <b>크리스탈 위에 뜬 바는 그 크리스탈의 체력으로 읽힌다</b> — 사람이 「그 크리스탈들 체력바를
 * 없애」라고 한 것이 그것이고 <b>자리마다 있던 바를 걷었다</b>({@link Slot} 에 바 칸이 없는 것이
 * 그 증거다). 남겨 둔 것이 아레나 가운데 하나뿐인 <b>타이머 바</b>였다.
 *
 * <p>⚠⚠ <b>그 하나도 지웠다. 타이머가 없어졌으므로 가리킬 수가 없다.</b> 가로로 줄어드는 바는
 * <b>「남은 시간」으로만 읽힌다</b> — 줄지 않는 바를 띄워 두면 사람이 <b>오지 않는 무언가를
 * 기다린다.</b> 다른 수를 넣는 길도 재어 보고 버렸다.
 *
 * <ul>
 *   <li><b>「되돌아간 양」을 채우는 바</b> — <b>드래곤 보스바가 이미 그것이다.</b> 초당 72 면
 *       보스바가 눈에 보이는 속도로 차므로 같은 수를 두 곳에 그리는 일이 된다</li>
 *   <li><b>「몇 개가 연결됐나」를 세는 바</b> — <b>연결선이 이미 그것이다.</b> 선이 몇 줄인지가
 *       곧 그 수이고, 소리의 음높이도 같은 수를 말한다({@link #leakPitch})</li>
 *   <li><b>「연결까지 2초」를 세는 바</b> — 크리스탈마다 따로 돌아야 하는데 <b>자리마다 뜨는 바는
 *       오늘 사람이 지우라고 한 그것</b>이다. 다시 만들지 않는다</li>
 * </ul>
 *
 * <p>✅ 그래서 <b>이 파도에는 바가 하나도 없다.</b> 말하는 수단이 셋 남았고 셋 다 「지금 무슨 일이
 * 일어나는가」를 바로 가리킨다 — <b>연결선</b>(어느 크리스탈이 샌다) · <b>소리</b>(얼마나 샌다) ·
 * <b>드래곤 보스바</b>(얼마나 되돌아갔다).
 *
 * <h2>시간표 — <b>크리스탈마다 제 시계로 돈다</b></h2>
 *
 * <pre>
 * 0 .. {@value #RISE_TICKS}         자리마다 <b>흰 신호기 선</b>이 선다. 아직 아무것도 없다
 * {@value #RISE_TICKS} + k×{@value #DROP_STRIDE_TICKS}  k 번째가 하늘에서 떨어지기 시작({@value #FALL_TICKS}틱)
 * 떨어진 틱 P(k)            그 자리에 <b>박힌다</b> — 흰 선이 꺼지고 크리스탈이 선다
 * P(k) + {@value #LINK_DELAY_TICKS}            ⚠ <b>그것 하나가 드래곤에 연결된다</b> — 선이 그때 뜨고
 *                           초당 0.5% 가 흐르기 시작한다
 * 부서지는 틱                그 크리스탈의 선과 누수가 멈춘다. <b>그것 하나만</b>이다
 * 전부 부서진 틱             파도가 끝난다. <b>그때까지 끝나지 않는다</b>
 * </pre>
 *
 * <p>⚠ <b>하나의 시계가 아니라 여섯 개의 시계다.</b> 옛 타이머는 「마지막이 박힌 뒤 전부에게
 * 15초」였고 그래서 하나로 셀 수 있었다. 이제 각자 <b>제가 박힌 뒤 2초</b>에 붙으므로
 * ({@link Slot#plantedAt}) 먼저 떨어진 것이 먼저 샌다 — 여섯이 모두 연결되는 것은
 * {@code plantTicks(6) + 2초} = <b>130틱</b>이고, 그 앞 2.5초는 <b>샘이 하나씩 늘어나는 구간</b>이다.
 * 사람이 그것을 <b>선이 한 줄씩 늘어나는 것</b>으로 본다.
 *
 * <h2>회복은 연결된 동안 계속 돈다 — <b>부술 때까지</b></h2>
 *
 * <p>사람이 <b>「그 크리스탈에서 엔더드래곤으로 연결해서 체력을 회복하고잇다는걸 보여줫으면」</b>
 * 이라고 했고, 그 뒤 <b>「부술떄까지」</b>라고 못박았다. 그래서 회복은 <b>매 틱</b> 돌고
 * ({@link #leakPerTick}) 선은 <b>연결된 크리스탈마다</b> 상시로 나간다.
 *
 * <ul>
 *   <li><b>연결선이 거짓이 아니다.</b> 선은 {@link #leak} 안에서만 나가고 {@link Slot#linked} 가
 *       참인 자리만 그린다 — <b>박힌 뒤 2초 동안은 선도 회복도 하나도 없다</b></li>
 *   <li><b>부서지면 그 크리스탈 몫이 그 틱에 멈춘다.</b> 살아 있는 것만 세므로({@link #linkedCount})
 *       「선이 끊긴 자리에서 회복만 계속되는」 틱이 구조적으로 없다</li>
 *   <li><b>소리에 주기가 필요하다.</b> 매 틱 울리면 초당 20번이고, 이제 <b>끝이 없으므로</b>
 *       끝까지 울린다({@link #LEAK_SOUND_TICKS})</li>
 * </ul>
 *
 * <p>⚠ <b>바닐라 크리스탈의 빔은 쓸 수 없다.</b> 26.3 에서 그 빔은
 * {@code EndCrystal.DATA_BEAM_TARGET}({@code Optional<BlockPos>}) 을 <b>{@code EndCrystal} 개체의
 * 렌더러가</b> 그리는 것이다. 개체가 있어야 빔이 있고, 그 개체는 위의 셋이 막는다. 그래서
 * <b>점으로 선을 긋는다</b>({@link #beams}).
 *
 * <h2>점 예산 — 파도가 <b>{@link #worstCasePointsPerTick} 만큼</b> 쓴다</h2>
 *
 * <p>파도는 패턴·번개와 <b>같은 틱에 함께 돈다.</b> 흰 선은 <b>디스플레이 개체</b>라 0점이고,
 * 떨어지는 줄기·박히는 빛·부서지는 빛·반짝임은 <b>개수를 세는 형태</b>라 꾸러미 한 장씩이다.
 *
 * <p>⚠ <b>연결선만은 점을 쓴다.</b> 여섯 줄이라 늘기 쉬운 자리이므로 둘로 막았다.
 *
 * <ul>
 *   <li><b>줄 하나에 찍는 점 수를 거리와 무관하게 고정</b>했다({@value #BEAM_POINTS}) — 멀면
 *       간격이 벌어질 뿐이라 <b>예산이 값에서 바로 나온다</b></li>
 *   <li><b>{@value #BEAM_STRIDE}틱에 나눠 그린다</b>({@link #BEAM_STRIDE}) — 61점이 31점이 된다.
 *       패턴 쪽이 272 → 356 으로 오르면서 남은 몫이 84점뿐이라 그 절반도 쓰지 않는 쪽을 골랐다.
 *       ⚠ <b>2026-10-04</b> 에 패턴 쪽이 띄움 기둥 넷을 더해 <b>360</b> 이 되어 남은 몫은
 *       <b>80점</b>이다 — 나눠 그리는 판단은 그래서 더 맞는 쪽이 되었다</li>
 * </ul>
 *
 * <p>⚠⚠ <b>선이 상시가 되면서 몫이 31 → 37 로 늘었다.</b> 한 틱 점수는
 * 그대로인데 <b>겹치는 것이 생겼다</b> — 옛 회복은 타이머가 끝난 뒤에만 돌아 <b>세우는 동작과
 * 절대로 겹치지 않았고</b>, 그래서 그 틱에 반짝임({@link #IDLE_TICKS})이 없었다. 이제 여섯이 서
 * 있는 채로 여섯 줄이 나가므로 <b>가장 바쁜 틱이 「선 여섯 + 반짝임 여섯 + 하트」</b>다.
 *
 * <ul>
 *   <li><b>선 30</b> — 여섯 줄 × {@value #BEAM_POINTS}점을 {@value #BEAM_STRIDE}틱에 나눈 5점</li>
 *   <li><b>반짝임 6</b> — {@value #IDLE_TICKS}틱마다 서 있는 것마다 꾸러미 한 장. ⚠ 박히는 틱이
 *       전부 10의 배수라 <b>반드시 같은 틱에 온다</b></li>
 *   <li><b>하트 1</b> — 소리가 나는 틱에 드래곤 몸에서 한 장</li>
 * </ul>
 *
 * <p>세우는 동안은 그보다 적다(동시에 떨어지는 것이 둘 = 6점 · 박히는 꾸러미 2 · 반짝임 개수만큼).
 * <b>세우는 중에 연결이 겹치는 것도 재어 봤다</b> — 마지막이 박히는 틱(90)에 연결된 것은 둘뿐이라
 * 10 + 5 + 2 + 1 = 18점이고, 전부 연결되는 130틱의 37 가 어느 틱보다 크다.
 *
 * <p>시험이 {@link #worstCasePointsPerTick} 을 못박고 패턴 몫과의 <b>합</b>을
 * {@code TrialLandingShock.MAX_POINTS_PER_TICK}(440)과 견준다 — <b>360 + 37 = 397</b> 이다.
 * ⚠ <b>2026-10-04</b>: 옛 서술은 「356 + 37」이었다. 패턴 쪽이 <b>띄움 기둥 넷</b>을 더해
 * 356 → <b>360</b> 이 되었고({@code DragonLastStandPatternsTest} 가 360 을 못박는다) 그래서 합이
 * 393 이 아니라 <b>397</b> 이다 — 예산까지 남은 몫은 <b>43점</b>이다.
 *
 * <h2>⚠⚠ 일곱 자리에서 지운다 — {@code DragonLastStandConePanel} 을 그대로 따른다</h2>
 *
 * <p>개체는 남는다. 남으면 <b>다음 판에 부술 수 없는 것이 떠 있다.</b> 자리마다 개체가
 * <b>둘</b>(갑옷 거치대 + 겉모습)이고 <b>둘 다</b> 같은 자리를 지난다. ⚠ <b>이 목록의 수를
 * 줄이지 말 것</b> — 「한쪽만 막으면 반드시 샌다」를 이 저장소가 여러 번 적어 두었다.
 *
 * <ol>
 *   <li><b>저장을 아예 안 한다</b>({@link Shard#shouldBeSaved()} ·
 *       {@code DragonLastStandShells.Shell.shouldBeSaved()}). 서버 강제 종료에는
 *       {@code SERVER_STOPPING} 도 오지 않으므로 지우는 코드로는 막을 수 없다</li>
 *   <li><b>스스로 타 없어지는 심지</b>({@link Shard#tick()} · {@code Shell.tick()})</li>
 *   <li><b>부서지는 그 자리</b>({@link Shard#shatter}) — 겉모습을 같은 틱에 거둔다. ⚠ 여기를
 *       빠뜨리면 <b>깨뜨린 자리에 크리스탈 그림만 남는다</b></li>
 *   <li><b>운영자 {@code /kill}</b>({@link Shard#hurtServer}) — 상자가 그 자리에서 사라지므로
 *       그림도 같은 틱에 거둔다</li>
 *   <li><b>상자만 사라진 자리를 매 틱 쓸어낸다</b>({@link #advance}) — 심지나 {@code /kill} 로
 *       상자가 먼저 가면 그림이 고아가 된다</li>
 *   <li><b>파도가 끝나는 틱</b>({@link #close}) — 전부 부쉈거나 드래곤이 죽었을 때</li>
 *   <li><b>{@link #clearState()}</b> — {@code DragonLastStand.clearState} ·
 *       {@code onFightClosed} · {@code onServerStopping} 이 부른다. ⚠ 월드를 못 만지므로
 *       <b>개체를 들고 있어야</b> 하고, 자리에서 놓친 것까지 쓸어내려고
 *       {@code DragonLastStandShells.drop()} 을 함께 부른다</li>
 * </ol>
 */
public final class DragonLastStandObjects {

	// ------------------------------------------------------------------ 사람이 정한 값

	/**
	 * 파도가 오는 드래곤 체력 비율. <b>사람이 정한 값이다</b> — 25%와 10%, <b>두 번만</b>이다.
	 *
	 * <p>주기적이 아니다. 순서대로 하나씩만 터지고, 한 번 터진 문턱은 다시 오지 않는다
	 * ({@link #firedWaves}).
	 */
	static final float[] THRESHOLDS = {0.25F, 0.10F};

	/**
	 * 인원별 개수. <b>사람이 정한 값이다</b> — 1명 2 · 2명 4 · 3명 5 · 4명 6.
	 *
	 * <p>두 파도가 같은 수다. 오르는 폭이 2 → 1 → 1 로 고르지 않은데 그것이 사람이 적은 표이고,
	 * <b>매끄럽게 고치지 말 것.</b>
	 */
	static final int[] COUNT_BY_MEMBERS = {2, 4, 5, 6};

	/**
	 * 오브젝트 하나의 체력. <b>사람이 정한 값이다.</b>
	 *
	 * <p>다이아 검 7 이면 <b>다섯 대</b>, 힘껏 당긴 활(9 남짓)이면 <b>네 발</b>이다.
	 *
	 * <p>⚠ <b>이 값이 「2초 안에 다 부수기」를 불가능하게 만든다.</b> 여섯이면 180 이고 4인이
	 * 2초(40틱) 안에 나눠 부수려면 사람마다 45 를 넣어야 하는데, 다이아 검의 쿨타임이 0.625초라
	 * 2초에 <b>네 번</b>(28)이 한계다. 곧 <b>연결은 반드시 일어난다</b> — 누수를 피하는 싸움이
	 * 아니라 <b>줄이는 싸움</b>이고, 그것이 {@link #LEAK_FRACTION_PER_SECOND} 를 1% 에서 0.5% 로
	 * 내린 근거다.
	 */
	static final float OBJECT_HEALTH = 30.0F;

	/**
	 * 박힌 뒤 드래곤에 <b>연결되기까지</b>의 시간(틱). 2초. <b>사람이 정한 값이다</b> —
	 * 「소환되고 2초뒤부터 엔더드래곤에 연결되고」.
	 *
	 * <p>⚠ <b>크리스탈마다 따로 돈다.</b> 재는 자리가 「파도가 열린 때」가 아니라 <b>그것이 박힌
	 * 때</b>({@link Slot#plantedAt})인 까닭은 사람 말이 <b>「소환되고」</b>이기 때문이다 — 크리스탈이
	 * 세상에 생기는 것은 하늘에서 떨어져 <b>박히는 그 틱</b>이고(그 전에는 흰 선과 떨어지는 줄기뿐
	 * 상자도 그림도 없다) 때릴 수 있게 되는 때도 그 틱이다.
	 *
	 * <p>이 2초가 하는 일이 둘이다. ① <b>팀에게 먼저 치는 시간을 준다</b> — 박히자마자 새면 선이
	 * 뜨는 것과 사람이 돌아보는 것이 같은 틱이라 「무엇이 생겼나」를 읽을 틈이 없다.
	 * ② <b>선이 뜨는 것이 사건이 된다</b> — 박힘과 연결이 갈라져 있어야 「붙었다」가 눈에 보인다.
	 */
	static final int LINK_DELAY_TICKS = 40;

	/**
	 * ⚠⚠ <b>연결된 크리스탈 하나가 1초에 드래곤에게 돌려주는 최대 체력 비율.</b>
	 * <b>사람이 정한 값이고 이 파일에서 가장 무거운 수다.</b>
	 *
	 * <p>사람 말: 「<b>초당1프로가 아니라 초당 0.5프로라고해봐 그럼</b>」.
	 *
	 * <h2>⚠ 사람이 1% 에서 0.5% 로 내렸다(2026-10-01). <b>또 움직일 값이다</b></h2>
	 *
	 * <p>그러니 <b>이 상수 한 곳에만 적혀 있다.</b> 시험도 여기서 뽑아 쓴다 — 두 군데에 적히면
	 * 한쪽만 고쳐지는 날이 온다. 값만 고치면 아래 수가 전부 따라온다({@link #leakPerSecond} ·
	 * {@link #leakPerTick}).
	 *
	 * <h2>수 — 4인 기준 최대 체력 2400 에서</h2>
	 *
	 * <table border="1">
	 *   <caption>{@code dragonHealthPerMember}(600) × 4 = 2400 에서 센 것</caption>
	 *   <tr><th>것</th><th>0.5%(지금)</th><th>1%(사람이 먼저 말한 것)</th></tr>
	 *   <tr><td>하나가 1초에</td><td><b>12</b></td><td>24</td></tr>
	 *   <tr><td>하나가 1틱에</td><td><b>0.6</b></td><td>1.2</td></tr>
	 *   <tr><td><b>여섯이 1초에</b></td><td><b>72</b></td><td><b>144</b></td></tr>
	 *   <tr><td>⚠ 깎아야 하는 1200 이 <b>전부 되돌아오는 시간</b></td>
	 *       <td><b>16.6초</b></td><td><b>8.3초</b></td></tr>
	 * </table>
	 *
	 * <p>⚠ <b>1% 가 과했던 까닭은 「피할 수 없다」였다.</b> {@link #OBJECT_HEALTH} 의 ⚠ 가 그것이고
	 * — 체력 30짜리 여섯을 2초 안에 다 부수는 것은 어떤 무기로도 불가능하므로 <b>연결은 반드시
	 * 일어난다.</b> 그 위에 8.3초면 페이즈가 통째로 되돌아간다는 것은 「대응을 잘하면 줄어드는
	 * 벌」이 아니라 <b>이 페이즈를 끝낼 수 없게 만드는 수</b>다. 0.5% 면 그 시간이 16.6초가 되어
	 * <b>부수는 속도가 실제로 결과를 가른다.</b>
	 *
	 * <p>⚠⚠ <b>그래도 타이머가 없다는 사실은 그대로다.</b> 팀이 손을 놓으면 드래곤은 가득까지
	 * 차고 이 페이즈는 <b>끝나지 않는다</b> — 사람이 「부술떄까지」라고 정한 것이 그 뜻이다. 판을
	 * 끝내는 것은 안전지대의 벽 피해뿐이다({@code DragonLastStandZone} 의 115초 뒤 증가).
	 */
	static final float LEAK_FRACTION_PER_SECOND = 0.005F;

	/**
	 * 1초가 몇 틱인가. <b>비율이 「초당」으로 적혀 있으므로 틱으로 옮기는 자리가 한 곳 필요하다.</b>
	 *
	 * <p>{@link #LEAK_FRACTION_PER_SECOND} 를 매 틱 나눠 주는 식({@link #leakPerTick})에만 쓴다.
	 */
	static final int SECOND_TICKS = 20;

	/**
	 * 보이는 크리스탈의 높이(칸). <b>사람이 정한 값이다</b> — 「크기를 블럭 정도 크기로 키웟으면」.
	 *
	 * <p>⚠ <b>이 한 값이 보이는 크기와 맞는 상자를 함께 정한다.</b> {@link #HIT_HEIGHT} 가 이것과
	 * 같은 값이고 {@link #DISPLAY_SCALE} 이 여기서 나온다 — 키우고 싶으면 <b>여기만</b> 고치면
	 * 둘이 함께 따라온다.
	 *
	 * <p>옛 모습(머리에 얹은 아이템)은 그림 높이가 0.625 × {@value #SPRITE_INK_TALL} ≒
	 * <b>0.55칸</b>이었다 — 사람이 「잘 보이지도 않고」라고 한 수가 그것이다.
	 */
	static final double CRYSTAL_HEIGHT = 1.0;

	// ------------------------------------------------------------------ 보이는 크기와 맞는 상자

	/**
	 * 엔드 크리스탈 아이템 그림에서 <b>실제로 색이 있는 칸</b>의 가로 비율.
	 *
	 * <p>{@code assets/minecraft/textures/item/end_crystal.png} 는 16×16 이고 투명하지 않은 칸이
	 * <b>x 2..14 · y 1..14</b> 다 — 곧 가로 13/16 · 세로 14/16 이다(26.3 클라이언트 지프에서 재어
	 * 보고 적었다).
	 *
	 * <p>이 두 수가 있어야 「보이는 크기」가 말이 된다. 그림은 한 변이 1 인 사각형으로 그려지지만
	 * <b>눈에 보이는 것은 그 안의 색뿐</b>이라, 맞는 상자를 사각형에 맞추면 상자가 <b>투명한
	 * 테두리만큼 크다.</b>
	 */
	static final double SPRITE_INK_WIDE = 13.0 / 16.0;

	/** 같은 그림의 세로 비율. 근거는 {@link #SPRITE_INK_WIDE} 에 있다. */
	static final double SPRITE_INK_TALL = 14.0 / 16.0;

	/**
	 * 디스플레이 개체에 줄 확대.
	 *
	 * <p>그림의 투명한 테두리를 되돌려 <b>색이 있는 부분의 높이가 정확히
	 * {@link #CRYSTAL_HEIGHT}</b> 가 되게 한다. {@code item/generated} 그림은 한 변이 1칸으로
	 * 그려지므로 배율이 곧 변의 길이다.
	 */
	static final double DISPLAY_SCALE = CRYSTAL_HEIGHT / SPRITE_INK_TALL;

	/**
	 * 맞는 상자의 가로(칸). <b>보이는 색 그대로다.</b>
	 *
	 * <p>{@link #DISPLAY_SCALE} × {@link #SPRITE_INK_WIDE} — 곧 화면에서 색이 있는 부분의 가로와
	 * <b>같은 수</b>다. 빌보드가 {@code CENTER} 라 그림이 언제나 보는 쪽을 향하므로, 한 변
	 * {@value #CRYSTAL_HEIGHT} 짜리 정육면체 상자 안에 그 사각형이 <b>어느 각도에서도</b> 들어간다 —
	 * 곧 <b>보이는 것을 치면 반드시 맞는다.</b>
	 */
	static final float HIT_WIDTH = (float) (DISPLAY_SCALE * SPRITE_INK_WIDE);

	/** 맞는 상자의 높이(칸). <b>{@link #CRYSTAL_HEIGHT} 그 값이다.</b> */
	static final float HIT_HEIGHT = (float) CRYSTAL_HEIGHT;

	/**
	 * 크리스탈의 <b>가운데</b>가 지표에서 뜨는 높이(칸).
	 *
	 * <p>사람 눈높이(1.62)에 걸치게 둔다 — 상자가 {@value #CRYSTAL_HEIGHT}칸이므로 아래 끝이
	 * 1.3, 위 끝이 2.3 이다. 더 띄우면 근접으로 때릴 때 위를 올려다봐야 하고, 더 낮추면 바닥
	 * 표식(패턴 예고)과 겹쳐 읽힌다.
	 */
	static final double CRYSTAL_LIFT = 1.8;

	// ------------------------------------------------------------------ 자리와 시간표

	/**
	 * 오브젝트가 서는 고리의 반경(칸). <b>새 숫자를 만들지 않았다.</b>
	 *
	 * <p>안전지대의 <b>마지막 반경</b>에서 벽 여유({@code DragonLastStandEntry.WALL_MARGIN})만큼
	 * 안쪽이다 — 12 − 3 = <b>9</b>. 이렇게 적어 두면 <b>「오브젝트가 지대 밖에 서지 않는다」가
	 * 구조적으로 참</b>이고, 지대의 끝값을 고치는 사람이 여기를 따로 고칠 일이 없다
	 * (상시 번개의 {@code LIGHTNING_FIELD_RADIUS} 가 같은 식으로 적혀 있다).
	 *
	 * <p>따라오는 사실 둘. ① 앉은 드래곤의 상자가 가로 16칸이라 반이 8 이므로 <b>몸 바로
	 * 밖</b>이다. ② 그래서 오브젝트를 때리는 자리가 <b>날개 퍼덕이기가 가장 센 구간</b>(4~12칸)과
	 * 겹친다.
	 */
	static final double RING_RADIUS =
			DragonLastStandZone.RADII[DragonLastStandZone.RADII.length - 1]
					- DragonLastStandEntry.WALL_MARGIN;

	/** 흰 신호기 선만 서 있는 시간(틱). 1초. 「먼저 선이 서고」가 이 1초다. */
	static final int RISE_TICKS = 20;

	/**
	 * 오브젝트 하나가 떨어지기 시작하는 간격(틱). 0.5초.
	 *
	 * <p>사람이 <b>「순차로」</b>라고 했다. 0.5초면 여섯이 2.5초에 걸쳐 들어와 <b>하나씩</b>으로
	 * 읽히고, 그보다 벌리면 마지막 것이 박히기까지 팀이 손을 놓고 기다린다.
	 */
	static final int DROP_STRIDE_TICKS = 10;

	/** 하늘에서 자리까지 떨어지는 시간(틱). 1초. */
	static final int FALL_TICKS = 20;

	/**
	 * 떨어지기 시작하는 높이(자리에서 잰 칸).
	 *
	 * <p>흑요석 기둥이 42칸이라 그보다 낮아야 <b>「하늘에서」</b>가 기둥 사이로 보이고, 너무
	 * 낮으면 떨어지는 것이 안 보인다. 40 이면 1초에 초당 40칸이라 눈에 줄기로 남는다.
	 */
	static final double SPAWN_HEIGHT = 40.0;

	/** 흰 신호기 선의 높이(칸). 떨어지는 높이보다 조금 더 길어야 선이 시작점을 덮는다. */
	static final double LINE_HEIGHT = SPAWN_HEIGHT + 4.0;

	/** 떨어지는 동안 줄기에 찍는 점 수. 한 틱에 <b>떨어지는 것마다</b> 이만큼이다. */
	private static final int FALL_TRAIL_POINTS = 3;

	/**
	 * 서 있는 동안 반짝이는 간격(틱). 개수를 세는 형태라 꾸러미 한 장이다.
	 *
	 * <p>⚠ <b>이 간격이 가장 바쁜 틱의 일부다.</b> 박히는 틱이 전부 10의 배수라(
	 * {@link #RISE_TICKS} 20 + {@link #DROP_STRIDE_TICKS} 10의 배수 + {@link #FALL_TICKS} 20)
	 * 반짝임이 연결선과 <b>반드시 같은 틱에 온다</b> — 클래스 설명의 「점 예산」을 볼 것.
	 */
	private static final int IDLE_TICKS = 10;

	// ------------------------------------------------------------------ 연결된 동안 계속 도는 누수

	/**
	 * 연결선 하나에 찍는 점 수. <b>거리와 무관하게 이만큼이다.</b>
	 *
	 * <p>⚠ 간격을 고정하면 선 길이에 따라 점이 늘어 <b>예산이 자리에 따라 달라진다</b>(크리스탈은
	 * 지표에 서고 지표 높이는 자리마다 다르다). 개수를 고정하면 멀어질 때 <b>간격이 벌어질 뿐</b>이고
	 * {@link #worstCasePointsPerTick} 이 값에서 바로 나온다.
	 *
	 * <p>10 이면 9~10칸 거리에 간격이 1칸 남짓이다. 먼지 점이 1칸 간격이면 눈에는 이어진 줄로
	 * 보인다({@code TrialWarning.ringGap} 이 바닥 고리에 쓰는 간격과 같은 자리의 판단이다).
	 */
	static final int BEAM_POINTS = 10;

	/**
	 * 연결선 하나를 <b>몇 틱에 나눠</b> 그리는가. 2 다.
	 *
	 * <p>⚠ <b>예산 때문이다.</b> 패턴 쪽이 벽 연출을 얻으면서 최악이 <b>272 → 356</b> 으로 올라
	 * 예산({@code TrialLandingShock.MAX_POINTS_PER_TICK} = 440)에 84점만 남았다
	 * ({@code DragonLastStandPatterns} 클래스 설명). ⚠ <b>2026-10-04</b> 에 띄움 기둥 넷이
	 * 더해져 패턴 쪽은 <b>360</b>, 남은 몫은 <b>80점</b>이다 — 2 를 고른 까닭은 그대로 선다.
	 * 여섯 줄을 매 틱 다 그리면 61점이라 그 몫의
	 * 대부분을 혼자 먹는다 — 나눠 그리면 <b>31점</b>이고, 다음 패턴을 늘릴 사람에게 남는 몫이
	 * 두 배가 된다.
	 *
	 * <p>나눠 그려도 선이 선으로 보이는 것은 <b>먼저 찍은 점이 아직 살아 있기</b> 때문이다. 26.3
	 * {@code DustParticleBase} 의 수명이 <b>최소 8틱</b>이고(「왜 8인가」가 적힌 원본은
	 * {@code TrialWarning.markGround} 다) 그 상한을 먼지로 그리는 쪽이 들고 있는 값
	 * ({@code TrialEndRain.MARK_MAX_STRIDE} = 6)에서 가져온다 — 2 는 한참 아래라 <b>두 틱이면
	 * 열 점이 모두 서 있고</b> 그 뒤로도 여섯 틱을 더 산다.
	 */
	static final int BEAM_STRIDE = 2;

	/**
	 * 연결선의 색. <b>흰색이다.</b>
	 *
	 * <p>{@code TrialWarning.Colors} 를 쓰지 않는다 — 그쪽은 <b>바닥에 그리는 위험 표식</b>의
	 * 규약이고 이 선은 <b>위험이 아니라 읽는 것</b>이다. 흰색인 것은 이 파도의 다른 모든 빛
	 * (신호기 선 · 떨어지는 줄기 · 박히는 빛)과 같은 색이기 때문이다 —
	 * <b>「크리스탈의 빛이 드래곤으로 흘러간다」</b>가 그 한 줄이 하는 말이다.
	 */
	static final int BEAM_COLOR = 0xFFFFFF;

	/**
	 * 누수 소리의 주기(틱). <b>2초.</b>
	 *
	 * <h2>⚠ 10틱(0.5초)에서 늘렸다 — 「끝이 있는 1.5초」가 「부술 때까지」로 바뀌었다</h2>
	 *
	 * <p>앞 규칙에서 회복은 <b>30틱으로 끝나는 구간</b>이었고 0.5초마다 울려 <b>세 번</b>이 전부였다
	 * — 올라가는 음높이로 「끝나 간다」를 말하는 짧은 한 흐름이었다. 그 끝이 사라졌다. 0.5초를
	 * 그대로 두면 <b>크리스탈이 부서질 때까지 초당 두 번이 영원히</b> 울리고, 현실적인 10~20초
	 * 싸움에 <b>20~40번</b>이다. 자수정 울림은 밝은 소리라 그만큼 겹치면 귀가 아프다.
	 *
	 * <p><b>2초인 까닭.</b> ① 바닐라 신호기 주변음이 <b>80틱(4초)</b> 주기인데 그보다 촘촘해야
	 * 「주변음」이 아니라 「지금 일어나는 일」로 들린다 ② 10~20초 싸움에 <b>5~10번</b>이라
	 * <b>떨어지는 물방울</b>로 읽힌다 — 사람이 「샌다」로 알아야 하는 것이 정확히 그 느낌이다
	 * ③ {@link #LINK_DELAY_TICKS}(40)과 같은 수라 <b>첫 연결이 일어나는 틱에 첫 소리가 맞는다</b>
	 * (첫 크리스탈이 박히는 40틱 + 2초 = 80틱이 이 주기의 배수다).
	 *
	 * <p>⚠ <b>음량도 함께 내렸다</b>({@link #LEAK_VOLUME}). 주기를 늘리는 것만으로는 「영원히
	 * 울린다」가 안 풀린다 — 끝이 없는 소리는 <b>싸움 소리 아래</b>에 있어야 한다.
	 */
	static final int LEAK_SOUND_TICKS = 40;

	/**
	 * 누수 소리의 음량. <b>1.0 에서 0.5 로 내렸다.</b>
	 *
	 * <p>⚠ <b>이것은 경고가 아니라 상태다.</b> 1.0 은 「이제 터진다」를 말하는 음량이고
	 * ({@code TrialWarning} 의 예고음들) 이 소리는 <b>끝날 때까지 계속 있는 것</b>이라 같은 음량이면
	 * 드래곤의 울음·날개·번개를 덮는다. 절반이면 <b>들리기는 하지만 싸움 위에 올라타지 않는다.</b>
	 */
	static final float LEAK_VOLUME = 0.5F;

	/**
	 * 개체가 스스로 타 없어지기까지의 틱. <b>이 페이즈의 시계 전체 + 세우는 시간</b>이다.
	 *
	 * <h2>⚠⚠ 「파도 길이 + 여유」로는 더 셀 수 없다 — <b>파도에 길이가 없어졌다</b></h2>
	 *
	 * <p>앞 규칙에서는 {@code waveTicks(6) + 회복 + 15초} = 720틱(36초)이었다. 파도가
	 * <b>타이머로 끝났기</b> 때문에 길이가 값에서 나왔다. 이제 파도는 <b>전부 부술 때까지</b>
	 * 돌므로 길이가 팀의 속도에 달려 있고, 심지가 짧으면 <b>부수지 못한 크리스탈이 공짜로
	 * 사라진다</b> — 사람이 정한 「부술 때까지」가 그 자리에서 거짓이 된다.
	 *
	 * <p>✅ 그래서 <b>페이즈 쪽 시계를 기준으로 삼는다.</b> 안전지대의 축소가 끝나고 벽 피해가
	 * 오르기 시작하는 시각({@code DragonLastStandZone.escalationStartTicks()} = 2300틱 = 115초)에
	 * 세우는 시간을 더한 <b>2390틱(약 2분)</b>이다. 크리스탈 하나를 115초 동안 못 부순 팀은
	 * <b>이미 벽 피해로 죽어 있다</b> — 곧 이 심지가 실제로 타는 일이 없고, 그러면서도
	 * <b>값에서 직접 나오므로</b> 누가 페이즈 길이를 고치면 여기가 따라온다.
	 */
	static final int FUSE_TICKS = (int) DragonLastStandZone.escalationStartTicks()
			+ plantTicks(COUNT_BY_MEMBERS[COUNT_BY_MEMBERS.length - 1]);

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 파도를 몰고 있는 판의 시계 원점.
	 *
	 * <p>{@code DragonLastStandZone.drivingSince} 와 <b>같은 모양</b>이다. 드래곤은 차원에
	 * 하나뿐이라 두 판이 같은 드래곤에 파도를 열면 개수도 누수도 두 배가 된다 — <b>먼저 든 판이
	 * 몰고 간다.</b>
	 */
	private static long owner = Long.MIN_VALUE;

	/** 지금까지 터진 문턱의 수. 0·1·2 다. {@link #THRESHOLDS} 를 순서대로 먹는다. */
	private static int firedWaves;

	/** 지금 도는 파도. 없으면 {@code null} 이다. */
	private static @Nullable Wave wave;

	/**
	 * 한 자리. 흰 선 → 떨어짐 → 크리스탈 → <b>2초 뒤 연결</b>.
	 *
	 * <p>⚠ <b>바 칸이 하나도 없다.</b> 사람이 「그 크리스탈들 체력바를 없애」라고 한 것이 자리마다
	 * 떠 있던 그 바이고, 가운데 하나뿐이던 타이머도 타이머가 사라지면서 함께 지웠다(클래스 설명의
	 * 「바가 하나도 없다」).
	 *
	 * <p>⚠ <b>제 시계를 든다</b>({@link #plantedAt}). 연결이 크리스탈마다 따로 돌기 때문이고,
	 * 그것이 「{@link Wave} 가 하나의 타이머를 들었다」와 바뀐 자리다.
	 */
	private static final class Slot {
		/** 크리스탈이 설 자리. 지표다 — 뜨는 높이는 {@link #CRYSTAL_LIFT} 가 더한다. */
		private final Vec3 seat;
		/** 떨어지기 시작하는 틱(파도 시작부터). */
		private final int dropsAt;
		private @Nullable DragonLastStandLights.Glow line;
		/** 맞는 상자와 체력 30. */
		private @Nullable Shard shard;
		/** 보이는 크리스탈. 상자와 <b>같은 자리</b>에 선다. */
		private @Nullable DragonLastStandShells.Shell shell;
		/**
		 * <b>박힌 틱</b>(파도 시작부터). <b>−1 이면 아직 안 박혔다</b> — 곧 「이미 박았는가」도 이
		 * 한 칸이 답한다.
		 *
		 * <p>⚠ 연결되는 때를 여기서 센다({@link #LINK_DELAY_TICKS}). 사람 말이 「소환되고
		 * 2초뒤부터」라 재는 원점이 <b>파도가 아니라 이 자리</b>다.
		 */
		private int plantedAt = -1;

		private Slot(Vec3 seat, int dropsAt) {
			this.seat = seat;
			this.dropsAt = dropsAt;
		}

		/** 이 자리가 아직 살아 있는가. */
		private boolean standing() {
			return shard != null && shard.isAlive();
		}

		/**
		 * 이 자리가 지금 드래곤에 <b>연결돼 있는가.</b> 곧 <b>선이 보이고 체력이 흐르는가</b>다.
		 *
		 * <p>⚠ 세 조건이 <b>모두</b>여야 한다 — 박혔고, 2초가 지났고, <b>아직 살아 있다.</b>
		 * 마지막 하나가 「부서지면 그 크리스탈 몫이 멈춘다」의 전부다.
		 */
		private boolean linked(int step) {
			return standing() && plantedAt >= 0 && linkedBy(step, plantedAt);
		}
	}

	/**
	 * 한 번의 파도.
	 *
	 * <p>⚠ <b>타이머 칸도, 「못 부순 개수」 칸도, 「회복 총량」 칸도 없다.</b> 굳는 몫이 없어졌고
	 * 끝나는 시각도 없다 — 파도는 <b>전부 부서지는 틱에</b> 끝난다.
	 */
	private static final class Wave {
		private final long beganAt;
		private final int threshold;
		/** 고리의 가운데. 드래곤이 못박혀 있는 자리다. */
		private final Vec3 anchor;
		private final List<Slot> slots;
		/** 마지막이 박히는 틱(파도 시작부터). <b>그 전에는 파도를 닫지 않는다.</b> */
		private final int plantedAt;
		/** 처음으로 하나가 연결된 틱(파도 시작부터). <b>−1 이면 아직 아무것도 안 샌다.</b> */
		private int leakBeganAt = -1;
		/** 지금까지 실제로 돌려준 체력. 로그에만 쓴다 — <b>사람이 수를 보게 하려고</b> 센다. */
		private float healed;

		private Wave(long beganAt, int threshold, Vec3 anchor, List<Slot> slots, int plantedAt) {
			this.beganAt = beganAt;
			this.threshold = threshold;
			this.anchor = anchor;
			this.slots = slots;
			this.plantedAt = plantedAt;
		}
	}

	private DragonLastStandObjects() {
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 이 인원에 몇 개인가. <b>사람이 적은 표 그대로다.</b>
	 *
	 * <p>0명이면 0 이다 — 아무도 없는 판에 오브젝트를 세우면 <b>아무도 하지 않은 일</b>로 드래곤이
	 * 끝없이 회복한다(타이머가 없으니 저절로 멈추지도 않는다). 다섯 이상은 4인 값으로 자른다
	 * (사람의 표가 넷에서 끝나고 이 판의 팀이 넷이다).
	 */
	static int objectCount(int members) {
		if (members <= 0) {
			return 0;
		}
		return COUNT_BY_MEMBERS[Math.min(members, COUNT_BY_MEMBERS.length) - 1];
	}

	/**
	 * 마지막 오브젝트가 박히는 틱(파도 시작부터).
	 *
	 * <p>값에서 직접 센다 — 간격이나 낙하 시간을 고치는 사람이 손으로 세지 않게 하는 것이 이
	 * 함수의 존재 이유다.
	 *
	 * <p>⚠ <b>이것은 더 이상 「타이머가 시작하는 시각」이 아니다.</b> 타이머가 없다. 지금 이 수가
	 * 하는 일은 둘이다 — ① <b>그 전에는 파도를 닫지 않는다</b>(아직 안 박힌 것이 있는데 「전부
	 * 부쉈다」로 읽으면 파도가 그 자리에서 사라진다) ② 심지의 바닥을 잡는다({@link #FUSE_TICKS}).
	 */
	static int plantTicks(int count) {
		if (count <= 0) {
			return RISE_TICKS;
		}
		return RISE_TICKS + (count - 1) * DROP_STRIDE_TICKS + FALL_TICKS;
	}

	/**
	 * <b>전부가 연결되는</b> 틱(파도 시작부터). 마지막이 박힌 뒤 2초다.
	 *
	 * <p>여섯이면 90 + 40 = <b>130틱</b>이고 그 틱이 <b>점 예산에서 가장 바쁜 틱</b>이다
	 * ({@link #worstCasePointsPerTick}). 그 앞 2.5초는 <b>선이 한 줄씩 늘어나는 구간</b>이다.
	 */
	static int allLinkedTicks(int count) {
		return plantTicks(count) + LINK_DELAY_TICKS;
	}

	/**
	 * {@code index} 번째 자리의 <b>상대 좌표</b>. 굳은 고리라 굴리지 않는다.
	 *
	 * <p>월드 없이 답이 정해지는 계산이라 시험이 <b>모든 개수에서</b> 이웃 사이 거리를 재고 모든
	 * 자리가 마지막 지대 안인지 본다.
	 *
	 * @param spin 고리를 돌리는 각(라디안). 실전에서는 무작위다 — 판마다 같은 자리에 서면
	 *             사람이 자리를 외워 버린다
	 */
	static Vec3 seatOffset(int index, int count, double spin) {
		if (count <= 0) {
			return Vec3.ZERO;
		}
		double angle = spin + (Math.PI * 2.0 * Math.floorMod(index, count)) / count;
		return new Vec3(Math.cos(angle) * RING_RADIUS, 0.0, Math.sin(angle) * RING_RADIUS);
	}

	/**
	 * 보이는 크리스탈의 <b>가운데</b> 높이. 지표에서 잰다.
	 *
	 * <p>여기가 디스플레이 개체가 서는 자리이고 <b>맞는 상자의 가운데</b>다 —
	 * {@link #standFeetY} 와 한 쌍으로 「보이는 크기 = 맞는 상자」를 만든다.
	 */
	static double crystalCenterY(double seatY) {
		return seatY + CRYSTAL_LIFT;
	}

	/**
	 * 갑옷 거치대를 놓는 높이(= 상자의 <b>아래 끝</b>). 지표에서 잰다.
	 *
	 * <p>⚠ 26.3 {@code EntityDimensions.makeBoundingBox} 가 상자를 <b>개체 좌표에서 위로만</b>
	 * 키우므로, 상자의 가운데를 그림의 가운데에 맞추려면 <b>높이의 절반만큼 내려</b> 놓아야 한다.
	 * 이 한 줄이 「보이는 쪽을 쳤는데 안 맞는다」를 막는다.
	 */
	static double standFeetY(double seatY) {
		return crystalCenterY(seatY) - CRYSTAL_HEIGHT / 2.0;
	}

	/**
	 * 떨어지는 중인 오브젝트가 이 틱에 있어야 할 높이(<b>가운데</b>에서 잰 칸).
	 *
	 * <p>{@code step} 이 낙하 구간을 벗어나면 0 이다 — 곧 크리스탈이 설 자리다.
	 */
	static double fallHeight(int step) {
		if (step <= 0) {
			return SPAWN_HEIGHT;
		}
		if (step >= FALL_TICKS) {
			return 0.0;
		}
		return SPAWN_HEIGHT * (1.0 - (double) step / FALL_TICKS);
	}

	/**
	 * 박힌 지 {@code plantedAt} 인 크리스탈이 {@code step} 틱에 <b>이미 연결됐는가.</b>
	 *
	 * <p>⚠ <b>2초가 「지난 뒤」가 아니라 「딱 2초에」 붙는다</b>({@code >=}). 사람 말이 「2초뒤부터」
	 * 이므로 40틱째가 <b>연결의 첫 틱</b>이고, 그래서 연결되지 않는 구간이 정확히 <b>박힌 틱부터
	 * 39틱</b>이다.
	 *
	 * <p>월드 없이 답이 정해지는 계산으로 떼어 둔 것은 시험이 「2초 전에는 아무 일도 없다」를
	 * <b>틱 단위로 훑어야</b> 하기 때문이다.
	 */
	static boolean linkedBy(int step, int plantedAt) {
		return plantedAt >= 0 && step >= plantedAt + LINK_DELAY_TICKS;
	}

	/**
	 * 연결된 크리스탈 <b>하나</b>가 <b>1초에</b> 드래곤에게 돌려주는 체력.
	 *
	 * <p>4인 기준 최대 2400 이면 <b>12</b> 다. 값은 {@link #LEAK_FRACTION_PER_SECOND} 한 곳에서만
	 * 나온다 — 시험도 여기를 지나 수를 얻는다.
	 */
	static float leakPerSecond(float max) {
		if (!(max > 0.0F)) {
			return 0.0F;
		}
		return max * LEAK_FRACTION_PER_SECOND;
	}

	/**
	 * 연결된 크리스탈 <b>하나</b>가 <b>한 틱에</b> 돌려주는 체력.
	 *
	 * <p>4인 기준 최대 2400 이면 <b>0.6</b> 이고, 여섯이면 한 틱에 3.6 · 1초에 <b>72</b> 다.
	 *
	 * <p>⚠ <b>매 틱 주는 까닭</b>은 선이 거짓이 되지 않게 하는 것이다. 1초에 한 번 몰아주면
	 * <b>19틱 동안은 선만 있고 체력은 안 차는</b> 틱이 되고, 드래곤 보스바가 초마다 뚝뚝 뛴다 —
	 * 매 틱이면 보스바가 <b>흐르듯이</b> 차 「지금 새고 있다」가 그대로 보인다.
	 *
	 * <p>⚠ 1틱 0.6 은 {@code float} 가 2400 자리에서도 잃지 않는 크기다(유효자리 일곱).
	 */
	static float leakPerTick(float max) {
		return leakPerSecond(max) / SECOND_TICKS;
	}

	/**
	 * 누수 소리의 음높이. <b>몇 개가 연결돼 있는지</b>에 따라 올라간다.
	 *
	 * <h2>⚠ 앞 규칙에서는 「흐른 시간」이 올렸다 — 그 시간이 없어졌다</h2>
	 *
	 * <p>옛 {@code drainPitch(gone)} 은 30틱 구간을 0.8 → 1.2 로 훑었다. 끝이 없는 지금 그 식을
	 * 그대로 두면 <b>1.5초 뒤부터 모든 소리가 1.2 에 붙어</b> 음높이가 아무것도 말하지 않는다.
	 *
	 * <p>✅ 그래서 <b>연결된 개수</b>를 올린다. 얻는 것이 둘이다 — ① <b>끝없이 울려도 늘 뜻이
	 * 있다</b>(높으면 많이 샌다) ② <b>부수면 다음 소리가 내려간다</b>. 곧 이 소리가
	 * <b>대응의 보상</b>이 된다. 하나 남으면 0.8 · 여섯이면 1.2 이고 양끝은 옛 식과 같은 수다.
	 *
	 * @param linked 지금 연결돼 있는 개수. 0 이면 이 소리가 아예 안 난다
	 */
	static float leakPitch(int linked) {
		int most = COUNT_BY_MEMBERS[COUNT_BY_MEMBERS.length - 1];
		if (linked <= 1 || most <= 1) {
			return 0.8F;
		}
		float along = Math.min(1.0F, (float) (linked - 1) / (most - 1));
		return 0.8F + 0.4F * along;
	}

	/**
	 * 연결선의 {@code index} 번째 점이 선 위 어디에 앉는가. 0 이 크리스탈, 1 이 드래곤이다.
	 *
	 * <p>{@code gone} 이 흐르면 점이 조금씩 <b>드래곤 쪽으로 밀린다.</b> 같은 자리에 다시 찍으면
	 * 1칸 간격의 점선이 그대로 서 있지만, 밀면 사이가 메워져 <b>흘러가는 줄</b>로 보인다 — 점을
	 * 하나도 더 쓰지 않고 방향을 보여 주는 길이다.
	 *
	 * <p>⚠ 1 을 넘지 않는다. 넘으면 선이 드래곤을 지나 반대쪽으로 뻗는다.
	 */
	static double beamAlong(int index, int gone) {
		if (BEAM_POINTS <= 0) {
			return 0.0;
		}
		double drift = (double) Math.floorMod(gone, BEAM_POINTS) / BEAM_POINTS;
		return Math.min(1.0, (Math.floorMod(index, BEAM_POINTS) + drift) / BEAM_POINTS);
	}

	/**
	 * 이 파도가 <b>한 틱에 가장 많이 쓰는</b> 점 수.
	 *
	 * <p>{@code DragonLastStandPatterns.worstCasePointsPerTick} 에 <b>더해지는</b> 값이다 — 파도는
	 * 패턴·번개와 같은 틱에 함께 돈다. 값에서 직접 세므로 개수나 선의 점 수를 올리는 사람이
	 * 예산을 눈으로 세지 않아도 시험이 먼저 멈춰 세운다.
	 *
	 * <p>⚠⚠ <b>가장 바쁜 틱이 바뀌었다 — 31 → 37.</b> 선이 「끝이 있는 1.5초」였을 때는 그 구간에
	 * <b>세우는 동작이 하나도 없었다</b>(타이머가 끝난 뒤였으니 전부 이미 서 있고 {@code advance}
	 * 가 아예 안 돌았다). 이제 선이 상시이므로 <b>반짝임과 같은 틱에 겹친다</b> — 게다가 박히는
	 * 틱이 전부 10의 배수라 {@value #IDLE_TICKS}틱 주기와 <b>반드시</b> 만난다.
	 *
	 * <p>가장 바쁜 틱은 <b>전부가 연결된 뒤의 반짝임 틱</b>({@link #allLinkedTicks} = 130틱)이다 —
	 * 선 여섯 줄을 {@value #BEAM_STRIDE}틱에 나눈 몫(30) + 서 있는 것마다 반짝임(6) + 드래곤 몸의
	 * 하트 한 장(1).
	 *
	 * <p>세우는 동안은 그보다 적다. ⚠ <b>둘이 겹치는 것도 재어 봤다</b> — 마지막이 박히는 틱(90)에
	 * 연결된 것은 <b>둘뿐</b>이므로(처음 둘이 80·90 에 붙는다) 10 + 반짝임 5 + 박히는 꾸러미 2 +
	 * 하트 1 = 18 이고, 떨어지는 줄기까지 최대로 본 {@code planting} 도 14 다. 전부 연결된 틱이
	 * 어느 틱보다 크다.
	 */
	static int worstCasePointsPerTick() {
		int count = COUNT_BY_MEMBERS[COUNT_BY_MEMBERS.length - 1];
		// 위상에 따라 하나 더 나갈 수 있으므로 올림이다 — 예산을 묻는 자리는 늘 나쁜 쪽을 본다.
		int perBeam = (BEAM_POINTS + BEAM_STRIDE - 1) / Math.max(1, BEAM_STRIDE);
		// ⚠ 셋이 같은 틱에 온다 — 선 전부 + 서 있는 것마다 반짝임 + 하트 한 장.
		int leaking = count * perBeam + count + 1;
		int planting = FALL_TRAIL_POINTS * Math.max(1, FALL_TICKS / DROP_STRIDE_TICKS) + 2 + count;
		return Math.max(leaking, planting);
	}

	/**
	 * 이 피해로 오브젝트가 깎이는가. <b>「누가 했는가」가 기준이다.</b>
	 *
	 * <p>월드 없이 도는 계산으로 떼어 둔 것은, 이 한 줄이 <b>「무엇으로 깎이나」의 유일한
	 * 출처</b>라 시험이 직접 굴려 봐야 하기 때문이다. 근거는 클래스 설명에 있다.
	 *
	 * @param hasAttacker {@code DamageSource.getEntity() != null}
	 * @param explosion   {@code DamageTypeTags.IS_EXPLOSION}
	 */
	static boolean accepts(boolean hasAttacker, boolean explosion) {
		return hasAttacker || explosion;
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱. {@code DragonLastStand.tick} 이 <b>진입 연출이 끝난 뒤에만</b> 부른다.
	 *
	 * <p>파도는 패턴을 멈추지 않는다 — 상시 번개·반구와 같은 자리에 있는 <b>덧붙는 일</b>이고,
	 * 드래곤은 그동안에도 패턴을 돌린다. 그것이 이 파도가 어려운 까닭이다.
	 *
	 * @param anchor    드래곤을 못박아 둔 자리. 고리의 가운데다
	 * @param clockBase 이 판의 시계 원점. 파도를 <b>누가 몰고 있는가</b>를 가르는 열쇠다
	 * @param now       받은 틱. {@code getGameTime} 을 여기서 다시 읽지 않는다
	 */
	static void tick(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members, Vec3 anchor,
			long clockBase, long now) {
		if (owner != clockBase) {
			// 남의 판이거나 첫 틱이다. 앞 판의 것이 남아 있으면 여기서 먼저 거둔다.
			clearState();
			owner = clockBase;
		}
		if (wave != null) {
			run(end, dragon, members, now);
			return;
		}
		if (firedWaves >= THRESHOLDS.length) {
			return;
		}
		float max = dragon.getMaxHealth();
		if (!(max > 0.0F) || dragon.getHealth() / max > THRESHOLDS[firedWaves]) {
			return;
		}
		int count = objectCount(playing(members));
		if (count <= 0) {
			// 아무도 없다. 문턱을 먹지 않는다 — 돌아오면 그때 열린다.
			return;
		}
		open(end, members, anchor, count, now);
		firedWaves++;
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때. <b>월드를 만지지 않는다</b> — 들고 있는 개체에게 직접
	 * 말한다.
	 *
	 * <p>⚠ <b>회복은 주지 않는다.</b> 판이 닫히는 자리에서 「남았으니 얼마」를 얹으면 이미 죽은
	 * 드래곤이나 사라진 월드에 값을 쓰는 것이 된다. 회복은 {@link #leak} 한 곳에만 있다 —
	 * <b>그리고 지금은 더 당연하다.</b> 누수는 <b>살아 있는 동안 이미 다 준 것</b>이라 마지막에
	 * 정산할 몫이 아예 없다.
	 *
	 * <p>⚠ 자리에서 들고 있는 것을 거둔 <b>뒤에</b> {@code DragonLastStandShells.drop()} 으로 한 번
	 * 더 쓸어낸다 — 어딘가에서 참조를 놓친 겉모습이 있어도 여기서 반드시 사라진다. 이 저장소가
	 * 「한쪽만 막으면 반드시 샌다」를 여러 번 적어 둔 자리다.
	 */
	static void clearState() {
		if (wave != null) {
			for (Slot slot : wave.slots) {
				drop(slot);
			}
		}
		DragonLastStandShells.drop();
		wave = null;
		owner = Long.MIN_VALUE;
		firedWaves = 0;
	}

	/** 시험이 들여다보는 곳. 지금 파도가 도는가. */
	static boolean running() {
		return wave != null;
	}

	/** 시험이 들여다보는 곳. 지금 몇 개가 서 있는가. */
	static int liveCount() {
		if (wave == null) {
			return 0;
		}
		int count = 0;
		for (Slot slot : wave.slots) {
			if (slot.standing()) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 시험이 들여다보는 곳. 지금 <b>몇 개가 연결돼 있는가.</b> 곧 <b>선이 몇 줄 보이고 초당 몇
	 * 퍼센트가 흐르는가</b>다.
	 *
	 * <p>⚠ <b>이 수가 매 틱 다시 센다.</b> 굳혀 두면 부서진 크리스탈의 몫이 계속 흐른다 —
	 * 「부서지면 그 크리스탈 몫이 멈춘다」가 여기 한 줄에 달려 있다.
	 */
	static int linkedCount(int step) {
		if (wave == null) {
			return 0;
		}
		int count = 0;
		for (Slot slot : wave.slots) {
			if (slot.linked(step)) {
				count++;
			}
		}
		return count;
	}

	/**
	 * 시험이 들여다보는 곳. 이 파도에서 <b>한 번이라도 연결이 일어났는가.</b>
	 *
	 * <p>⚠ 「지금 새는가」가 아니다 — 그것을 묻는 자리는 {@link #linkedCount} 이고 틱을 받아야 한다.
	 * 이 한 줄은 <b>파도가 열린 뒤 2초 동안은 거짓</b>이라는 사실을 들여다보는 창이다.
	 */
	static boolean leaking() {
		return wave != null && wave.leakBeganAt >= 0;
	}

	/** 판에 끼어드는 사람 수. 관전자는 세지 않는다. */
	private static int playing(List<ServerPlayer> members) {
		int count = 0;
		for (ServerPlayer member : members) {
			if (!member.isSpectator()) {
				count++;
			}
		}
		return count;
	}

	// ------------------------------------------------------------------ 파도를 연다

	/**
	 * 파도를 연다 — 자리를 고르고 <b>흰 신호기 선</b>을 세운다.
	 *
	 * <p>오브젝트는 아직 없다. 사람이 정한 것이 「먼저 흰 신호기 선이 서고 하나씩 떨어지는
	 * 모습」이라 <b>선이 먼저</b>여야 한다.
	 */
	private static void open(ServerLevel end, List<ServerPlayer> members, Vec3 anchor, int count,
			long now) {
		double spin = end.getRandom().nextDouble() * Math.PI * 2.0;
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		List<Slot> slots = new ArrayList<>(count);
		for (int index = 0; index < count; index++) {
			Vec3 offset = seatOffset(index, count, spin);
			double x = anchor.x + offset.x;
			double z = anchor.z + offset.z;
			int surface = ground.surfaceAt(end, x, z);
			if (surface == TrialEnderPulse.NO_GROUND) {
				// 허공이다. 버린다 — 상시 번개와 같은 판단이고, 새는 몫도 함께 줄어든다.
				continue;
			}
			slots.add(new Slot(new Vec3(x, surface, z), RISE_TICKS + index * DROP_STRIDE_TICKS));
		}
		if (slots.isEmpty()) {
			SharedFateMod.LOGGER.info("[END] 오브젝트 파도 — 설 자리를 하나도 못 찾아 지나갑니다");
			return;
		}
		for (Slot slot : slots) {
			slot.line = DragonLastStandLights.raisePillar(end, slot.seat, DyeColor.WHITE,
					LINE_HEIGHT, FUSE_TICKS);
		}
		// block/beacon/power1~3 이다(sounds.json 에서 확인했다). 이 저장소의 어느 카드도 쓰지
		// 않는 소리이고, 사람이 말한 것이 「신호기 선」이므로 신호기 소리가 맞는 답이다.
		TrialWarning.playEach(end, members, SoundEvents.BEACON_POWER_SELECT, 1.0F, 1.2F);
		wave = new Wave(now, firedWaves, anchor, slots, plantTicks(count));
		SharedFateMod.LOGGER.info(
				"[END] 오브젝트 파도 — 체력 {}% · {}개 · 체력 {} · 크기 {}칸 · 타이머 없음 · "
						+ "박힌 뒤 {}초에 연결되어 하나마다 초당 {}% 씩 회복 · 부술 때까지",
				Math.round(THRESHOLDS[firedWaves] * 100.0F), slots.size(), OBJECT_HEALTH,
				CRYSTAL_HEIGHT, LINK_DELAY_TICKS / (float) SECOND_TICKS,
				LEAK_FRACTION_PER_SECOND * 100.0F);
	}

	// ------------------------------------------------------------------ 파도 한 틱

	/**
	 * 파도 한 틱. <b>두 일을 같은 틱에 한다</b> — 세우고({@link #advance}), 새게 한다
	 * ({@link #leak}).
	 *
	 * <p>⚠⚠ <b>옛 코드에서는 이 둘이 절대로 겹치지 않았다.</b> 「타이머가 끝나면 회복」이라
	 * {@code drain} 이 {@code advance} 를 아예 건너뛰고 돌아갔다. 지금은 <b>박힌 뒤 2초</b>라
	 * 크리스탈마다 따로 붙으므로 <b>마지막 것이 떨어지는 중에 처음 것이 이미 샌다</b> — 둘을
	 * 나란히 두는 것이 그 사실의 전부이고, 점 예산이 31 → 37 로 오른 까닭도 그것이다.
	 *
	 * <p>⚠ <b>파도가 끝나는 조건이 하나뿐이다 — 전부 부서지는 것.</b> 타이머가 없다.
	 */
	private static void run(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			long now) {
		Wave current = wave;
		if (current == null) {
			return;
		}
		int step = (int) Math.max(0L, now - current.beganAt);
		for (Slot slot : current.slots) {
			advance(end, slot, step);
		}
		if (liveCount() <= 0 && step >= current.plantedAt) {
			// 전부 부쉈다. ⚠ 「마지막이 박히는 틱」을 함께 묻는 것은 아직 하늘에 있는 것이 있을 때
			// 서 있는 것이 0 이기 때문이다 — 그 틱에 닫으면 남은 것이 영영 안 떨어진다.
			close(end, members, current, true);
			return;
		}
		if (!dragon.isAlive()) {
			// 드래곤이 죽었다. 돌려줄 데가 없으므로 그 틱에 거둔다 — 죽은 드래곤에게 체력을 쓰면
			// DragonLastStand.onFightClosed 뒤에 되살아난 것처럼 보인다.
			close(end, members, current, false);
			return;
		}
		leak(end, dragon, members, current, step);
	}

	/** 자리 하나를 한 틱 나아가게 한다 — 떨어지고, 박히고, 반짝인다. */
	private static void advance(ServerLevel end, Slot slot, int step) {
		if (slot.plantedAt < 0) {
			if (step < slot.dropsAt) {
				return;
			}
			int falling = step - slot.dropsAt;
			if (falling < FALL_TICKS) {
				streak(end, slot, falling);
				return;
			}
			plant(end, slot, step);
			return;
		}
		if (!slot.standing()) {
			// 상자가 사라졌는데 그림만 남는 길이 둘 있다 — 운영자 /kill 과 심지. 여기서 거둔다.
			DragonLastStandShells.drop(slot.shell);
			slot.shell = null;
			return;
		}
		if (step % IDLE_TICKS == 0) {
			// 개수를 세는 형태라 꾸러미 한 장이다. ⚠ 「점 예산과 무관하다」가 아니다 — 선이 상시가
			// 되면서 이 틱이 선과 겹치고, 그 겹침이 최악을 31 → 37 로 올렸다(클래스 설명).
			end.sendParticles(ParticleTypes.END_ROD, true, false,
					slot.seat.x, crystalCenterY(slot.seat.y), slot.seat.z, 4, 0.3, 0.4, 0.3, 0.01);
		}
	}

	/** 떨어지는 줄기. 한 틱에 지나간 만큼을 선으로 남긴다. */
	private static void streak(ServerLevel end, Slot slot, int falling) {
		double from = fallHeight(falling);
		double to = fallHeight(falling + 1);
		double base = crystalCenterY(slot.seat.y);
		for (int index = 0; index < FALL_TRAIL_POINTS; index++) {
			double along = (double) (index + 1) / FALL_TRAIL_POINTS;
			double y = base + from + (to - from) * along;
			end.sendParticles(ParticleTypes.END_ROD, true, false,
					slot.seat.x, y, slot.seat.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 박는다 — 흰 선이 꺼지고 <b>상자와 그림이 같은 자리에</b> 선다.
	 *
	 * <p>{@code random/anvil_land} 를 고른 것은 사람이 말한 것이 <b>「박힌다」</b>이기 때문이다
	 * (sounds.json 에서 확인했고, 이 저장소의 어느 카드도 쓰지 않는다). 폭발음을 쓰면
	 * 「무언가 터졌다」로 읽혀 이미 부순 줄 안다.
	 *
	 * <p>⚠⚠ <b>이 틱이 「소환된」 틱이다.</b> {@code step} 을 받아 {@link Slot#plantedAt} 에 적는
	 * 것이 사람이 말한 <b>「소환되고 2초뒤부터」</b>의 원점이다 — 파도가 열린 틱이 아니다.
	 *
	 * <p>⚠ 상자를 세우기 <b>전에</b> 적는다. {@code addFreshEntity} 가 거짓을 돌려줘도 이 자리는
	 * <b>다시 박지 않는다</b>(앞 코드의 {@code planted} 가 그 자리에 있었다) — 다시 박게 두면
	 * 실패가 이어질 때 박히는 소리와 빛이 매 틱 난다. 그때 {@code shard} 가 {@code null} 로 남으므로
	 * {@link Slot#linked} 가 영영 거짓이고, 곧 <b>세상에 없는 크리스탈이 회복시키는 일이 없다.</b>
	 */
	private static void plant(ServerLevel end, Slot slot, int step) {
		slot.plantedAt = step;
		DragonLastStandLights.drop(slot.line);
		slot.line = null;

		double centerY = crystalCenterY(slot.seat.y);
		Shard shard = new Shard(end, FUSE_TICKS);
		shard.snapTo(slot.seat.x, standFeetY(slot.seat.y), slot.seat.z, 0.0F, 0.0F);
		if (!end.addFreshEntity(shard)) {
			return;
		}
		slot.shard = shard;
		// 그림. 상자의 가운데와 같은 자리에 선다 — 그 한 줄이 「보이는 것이 곧 판정」이다.
		// ⚠ 갑옷 거치대의 각도를 굴리지 않는 것은 빌보드가 CENTER 라 각도가 그림에 닿지
		// 않기 때문이다(그림은 언제나 보는 쪽을 향한다).
		slot.shell = DragonLastStandShells.raise(end,
				new Vec3(slot.seat.x, centerY, slot.seat.z), new ItemStack(Items.END_CRYSTAL),
				DISPLAY_SCALE, FUSE_TICKS);
		// 부서지는 자리에서 그림을 같은 틱에 거두려면 개체가 그것을 들고 있어야 한다.
		shard.shell = slot.shell;
		end.sendParticles(ParticleTypes.EXPLOSION, true, false,
				slot.seat.x, centerY, slot.seat.z, 1, 0.0, 0.0, 0.0, 0.0);
		end.sendParticles(ParticleTypes.END_ROD, true, false,
				slot.seat.x, centerY, slot.seat.z, 40, 0.5, 0.5, 0.5, 0.15);
		end.playSound(null, slot.seat.x, slot.seat.y, slot.seat.z, SoundEvents.ANVIL_LAND,
				SoundSource.HOSTILE, 1.0F, 1.4F);
	}

	// ------------------------------------------------------------------ 연결된 동안 계속 도는 누수

	/**
	 * 누수가 도는 한 틱. <b>연결선과 소리가 여기에만 있다.</b>
	 *
	 * <p>⚠⚠ <b>연결된 것만 센다</b>({@link #linkedCount}). 그 한 줄이 사람이 정한 것 셋을 함께
	 * 지킨다 — ① <b>박힌 뒤 2초 전에는 아무 일도 없다</b> ② <b>부서지면 그 크리스탈 몫이 그 틱에
	 * 멈춘다</b> ③ <b>여섯이 살아 있으면 여섯 몫</b>. 세어 둔 값을 들고 있지 않으므로 「굳은 몫이
	 * 남아 흐르는」 틱이 구조적으로 없다.
	 *
	 * <p>⚠ <b>매 틱 준다</b>({@link #leakPerTick}). 1초에 한 번 몰아주면 선만 있고 체력은 안 차는
	 * 틱이 19개 생기고 보스바가 뚝뚝 뛴다.
	 */
	private static void leak(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			Wave current, int step) {
		int linked = linkedCount(step);
		if (linked <= 0) {
			// 아직 아무것도 안 붙었거나 붙은 것이 전부 부서졌다. 선도 소리도 회복도 없다.
			return;
		}
		if (current.leakBeganAt < 0) {
			current.leakBeganAt = step;
			// mob/enderdragon/growl1~4 다. 드래곤이 되찾기 시작하는 것을 드래곤의 목소리로 한 번
			// 알린다 — ⚠ 파도마다 한 번이다. 크리스탈마다 울리면 0.5초 간격으로 여섯 번 포효한다.
			// ⚠ 옛 「block/beacon/deactivate(시간이 꺼졌다)」는 걷어냈다. 꺼질 시간이 없다.
			TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.6F);
			SharedFateMod.LOGGER.info(
					"[END] 오브젝트 파도 — 첫 연결 · 하나마다 초당 {}(최대 체력 {} 의 {}%)",
					leakPerSecond(dragon.getMaxHealth()), dragon.getMaxHealth(),
					LEAK_FRACTION_PER_SECOND * 100.0F);
		}
		float give = leakPerTick(dragon.getMaxHealth()) * linked;
		if (give > 0.0F) {
			dragon.heal(give);
			current.healed += give;
		}
		beams(end, dragon, current, step);
		// ⚠ 주기는 파도 시계의 나머지다(now 를 다시 읽지 않는다). 2초마다이고 음량이 절반이다 —
		// 끝이 없는 소리라 그렇게 잡았다({@link #LEAK_SOUND_TICKS} · {@link #LEAK_VOLUME}).
		if (step % LEAK_SOUND_TICKS == 0) {
			// block/amethyst/resonate1~4 다(sounds.json 에서 확인했다 — chime 은 shimmer,
			// hit 은 step*, break 는 break* 라 넷이 전부 다른 파일이다). 이 저장소의 어느 카드도
			// 쓰지 않고, 수정이 울리는 소리라 「크리스탈이 뭔가를 보내고 있다」가 들린다.
			// ⚠ 위치 기반 playSound 를 팀원 루프에서 부르면 사람 수만큼 겹친다 — playEach 다.
			// 음높이는 「몇 개가 붙어 있나」다 — 부수면 다음 소리가 내려간다.
			TrialWarning.playEach(end, members, SoundEvents.AMETHYST_BLOCK_RESONATE, LEAK_VOLUME,
					leakPitch(linked));
			// 하트. 개수를 세는 형태라 꾸러미 한 장이고, 드래곤 몸 가운데에서 피어난다.
			Vec3 heart = dragon.getBoundingBox().getCenter();
			end.sendParticles(ParticleTypes.HEART, true, false, heart.x, heart.y, heart.z, 24,
					3.0, 1.5, 3.0, 0.05);
		}
	}

	/**
	 * 크리스탈마다 드래곤까지 <b>점으로 선을 긋는다.</b>
	 *
	 * <p>사람 말이 「그 크리스탈에서 엔더드래곤으로 연결해서 체력을 회복하고잇다는걸 보여줫으면」
	 * 이다. 바닐라 크리스탈의 빔은 쓸 수 없다(클래스 설명의 ⚠).
	 *
	 * <p>⚠ <b>부르는 곳이 {@link #leak} 하나이고 그리는 자리를 {@link Slot#linked} 가 고른다.</b>
	 * 그 둘이 「선이 보이는 때와 체력이 흐르는 때가 같다」의 전부다 — <b>박힌 뒤 2초 동안은 선이
	 * 없고</b>, <b>부서진 자리에는 그 틱부터 선이 없다.</b>
	 *
	 * <p>⚠ 끝점이 <b>드래곤 상자의 가운데</b>다. 발밑이나 머리로 잡으면 앉은 드래곤이 몸을 돌릴 때
	 * 선이 몸에서 떨어진다 — 가운데는 상자가 16×8 이라 <b>언제나 몸 안</b>이다.
	 *
	 * <p>⚠ 파티클은 <b>긴 형식</b>이다. 짧은 형식은 32칸에서 잘리고, 이 선은 길이가 10칸이라
	 * 보는 사람이 반대쪽에 있으면 통째로 사라진다.
	 */
	private static void beams(ServerLevel end, EnderDragon dragon, Wave current, int step) {
		ParticleOptions dust = TrialWarning.dust(BEAM_COLOR);
		Vec3 to = dragon.getBoundingBox().getCenter();
		for (Slot slot : current.slots) {
			// ⚠⚠ standing() 이 아니라 linked(step) 이다. 서 있기만 한 것(박힌 뒤 2초가 안 된 것)에
			// 선을 그리면 「선이 보이는데 체력은 안 찬다」가 되어 사람이 정한 2초가 거짓이 된다.
			if (!slot.linked(step)) {
				continue;
			}
			Vec3 from = new Vec3(slot.seat.x, crystalCenterY(slot.seat.y), slot.seat.z);
			Vec3 span = to.subtract(from);
			// floorMod 라야 음수 틱에서도 0..stride-1 로 떨어진다. 되감긴 판의 now 가 음수일 수 있다.
			for (int index = Math.floorMod(step, Math.max(1, BEAM_STRIDE)); index < BEAM_POINTS;
					index += Math.max(1, BEAM_STRIDE)) {
				Vec3 at = from.add(span.scale(beamAlong(index, step)));
				end.sendParticles(dust, true, false, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	// ------------------------------------------------------------------ 파도를 닫는다

	/**
	 * 파도를 닫고 들고 있던 것을 전부 거둔다.
	 *
	 * <p>⚠ <b>여기서 회복을 주지 않는다.</b> 누수는 {@link #leak} 이 살아 있는 동안 <b>이미 다
	 * 주었다</b> — 여기에 한 번 더 얹으면 부순 팀에게 벌을 주는 것이 된다.
	 *
	 * <p>⚠ <b>거둘 바가 없다.</b> 이 파도에는 바가 하나도 없다(클래스 설명의 「바가 하나도 없다」).
	 * 치우는 자리에서 사라진 것은 <b>바의 빛뿐</b>이고, 상자와 그림을 거두는 일곱 자리는 그대로다.
	 *
	 * @param cleared 전부 부숴서 끝났는가. 거짓이면 드래곤이 죽어서 닫는 것이다
	 */
	private static void close(ServerLevel end, List<ServerPlayer> members, Wave current,
			boolean cleared) {
		for (Slot slot : current.slots) {
			drop(slot);
		}
		wave = null;
		if (cleared) {
			// block/amethyst/shimmer 다. 부수는 소리와 같은 계열이라 「이 물건이 끝났다」로 들린다.
			TrialWarning.playEach(end, members, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.2F);
			SharedFateMod.LOGGER.info(
					"[END] 오브젝트 파도 — 전부 부쉈습니다(체력 {}% 파도 · 그동안 드래곤 체력 +{})",
					Math.round(THRESHOLDS[current.threshold] * 100.0F), current.healed);
			return;
		}
		SharedFateMod.LOGGER.info(
				"[END] 오브젝트 파도 — 드래곤이 죽어 닫습니다(체력 {}% 파도 · 그동안 +{})",
				Math.round(THRESHOLDS[current.threshold] * 100.0F), current.healed);
	}

	/** 자리 하나가 들고 있던 개체를 전부 거둔다. */
	private static void drop(Slot slot) {
		DragonLastStandLights.drop(slot.line);
		slot.line = null;
		DragonLastStandShells.drop(slot.shell);
		slot.shell = null;
		if (slot.shard != null && !slot.shard.isRemoved()) {
			slot.shard.discard();
		}
	}

	// ------------------------------------------------------------------ 개체

	/**
	 * 오브젝트 하나의 <b>맞는 상자와 체력 30</b>. 보이는 것은 {@link DragonLastStandShells} 가 든다.
	 *
	 * <p>왜 이 종류인지는 클래스 설명의 「왜 갑옷 거치대인가」에 있다. 여기서 덮어쓰는 것은
	 * 넷이다.
	 *
	 * <ol>
	 *   <li>{@link #getDefaultDimensions} — <b>상자가 보이는 그림과 같다.</b> {@code fixed} 라
	 *       {@code scale} 속성에도 흔들리지 않는다</li>
	 *   <li>{@link #hurtServer} — <b>체력 30 을 우리가 센다.</b> 바닐라 규칙은 두 대에 부서지고
	 *       투명하면 아예 안 맞는다</li>
	 *   <li>{@link #shouldBeSaved} — 저장하지 않는다</li>
	 *   <li>{@link #tick} — 스스로 타 없어지는 심지</li>
	 * </ol>
	 *
	 * <p>{@link #interact} 도 막아 둔다. 장비 칸이 비어 있어도 바닐라 갑옷 거치대는 <b>우클릭으로
	 * 손에 든 것을 끼워 준다</b> — 막지 않으면 사람이 오브젝트에 투구를 씌울 수 있다.
	 *
	 * <p>⚠ <b>바닐라 체력을 한 번도 건드리지 않는다.</b> {@link #remaining} 이 따로 있는 까닭이
	 * 둘이다 — {@code LivingEntity.die()} 가 아예 불리지 않아 <b>전리품도 경험치도 죽는 소리도
	 * 없고</b>, 「무엇으로 깎이나」가 {@link #accepts} 한 줄에 모인다.
	 */
	private static final class Shard extends ArmorStand {

		/** 남은 체력. <b>바닐라 체력이 아니다.</b> */
		private float remaining = OBJECT_HEALTH;
		/** 남은 틱. 0 이 되면 스스로 사라진다. */
		private int fuse;
		/** 이 상자가 들고 있는 그림. 부서지는 틱에 <b>같은 틱에</b> 거두려고 들고 있다. */
		private @Nullable DragonLastStandShells.Shell shell;

		private Shard(Level level, int fuse) {
			super(EntityTypes.ARMOR_STAND, level);
			this.fuse = Math.max(1, fuse);
			setInvisible(true);
			setNoBasePlate(true);
			// noGravity 가 ArmorStand.hasPhysics() 를 거짓으로 만들어 travel() 이 첫 줄에서
			// 돌아간다(26.3 바이트코드로 확인했다). 곧 떠 있는 채로 가만히 있는다.
			setNoGravity(true);
			// 갑옷 거치대의 걸음·피격 소리를 내지 않는다. 이것은 크리스탈이다.
			setSilent(true);
			// ⚠ 장비 칸은 비워 둔다. 머리에 아이템을 끼우면 0.625배 그림이 하나 더 떠서
			// 디스플레이가 그린 크리스탈과 둘이 보인다(클래스 설명의 ⚠).
			// 상자를 바꾼 것이 실제로 먹히게 한다. 생성자에서 한 번 부르지 않으면 개체 종류의
			// 기본 상자(0.5 × 1.975)가 그대로 남는다.
			refreshDimensions();
		}

		/**
		 * 맞는 상자. <b>보이는 그림 그대로다.</b>
		 *
		 * <p>근거는 {@link #HIT_WIDTH} 와 클래스 설명의 「모습과 상자」에 있다. {@code fixed} 인
		 * 것이 중요하다 — 26.3 {@code EntityDimensions.scale} 이 {@code fixed} 면 자신을 그대로
		 * 돌려주므로 누가 {@code scale} 속성을 걸어도 <b>상자가 그림에서 떨어져 나가지 않는다.</b>
		 */
		@Override
		public EntityDimensions getDefaultDimensions(Pose pose) {
			return EntityDimensions.fixed(HIT_WIDTH, HIT_HEIGHT);
		}

		/**
		 * 체력 30 을 우리가 센다. <b>바닐라 규칙을 통째로 대신한다.</b>
		 *
		 * <p>바닐라 {@code ArmorStand.hurtServer} 를 부르지 않는다 — 그쪽은 <b>투명하면 거짓을
		 * 돌려주고</b>(바이트코드에 {@code invisible} 검사가 있다) 안 그래도 두 대에 부서진다.
		 *
		 * <p>{@code true} 를 돌려주는 것이 중요하다. 거짓이면 클라이언트가 <b>때린 것 자체를
		 * 무르고</b>(휘두른 팔이 헛손질이 된다) 공격 쿨타임도 다르게 돈다.
		 *
		 * <h2>⚠⚠ 「못 부수는 구간」이 없다 — 걷어낸 자리다</h2>
		 *
		 * <p>여기에 {@code if (spent) return false;} 가 있었다. 「회복이 도는 1.5초 동안은 못 부순다」
		 * 였고, 그것은 <b>「못 부순 몫이 이미 굳었다」는 옛 규칙의 꼬리</b>였다 — 몫이 굳은 뒤에
		 * 깨뜨릴 수 있게 두면 선이 끊긴 자리에서 회복만 계속되는 틱이 생겼기 때문이다.
		 *
		 * <p>✅ <b>이제 굳는 몫이 없다.</b> 회복은 {@link #linkedCount} 가 <b>매 틱 다시 세는</b> 것이고
		 * 부서지면 그 크리스탈 몫이 그 틱에 멈춘다. 곧 그 검사는 막을 것이 하나도 없으면서
		 * <b>사람의 유일한 대응을 빼앗는다</b> — 사람이 「부술떄까지」라고 정했으므로 <b>언제나 부술
		 * 수 있어야 한다.</b> 다시 넣지 말 것.
		 */
		@Override
		public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
			if (isRemoved()) {
				return false;
			}
			// 운영자가 치울 길은 남긴다. /kill 은 부순 것으로 세므로 그 몫의 누수가 멈춘다.
			if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
				DragonLastStandShells.drop(shell);
				shell = null;
				discard();
				return true;
			}
			if (!accepts(source.getEntity() != null, source.is(DamageTypeTags.IS_EXPLOSION))
					|| !(amount > 0.0F)) {
				return false;
			}
			remaining -= amount;
			if (remaining > 0.0F) {
				// block/amethyst/step* 이다. 이 저장소의 어느 카드도 쓰지 않고, 수정에 금이 가는
				// 소리라 「들어갔다」가 들린다.
				level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_HIT,
						SoundSource.HOSTILE, 0.8F, 1.4F);
				level.sendParticles(ParticleTypes.CRIT, true, false,
						getX(), getY() + HIT_HEIGHT / 2.0, getZ(), 6, 0.3, 0.3, 0.3, 0.1);
				return true;
			}
			shatter(level);
			return true;
		}

		/**
		 * 부서진다. 폭발을 만들지 않는다 — 진짜 크리스탈을 버린 이유 가운데 하나가 그것이다.
		 *
		 * <p>⚠ <b>그림을 같은 틱에 거둔다.</b> 빠뜨리면 깨뜨린 자리에 <b>때릴 수 없는 크리스탈
		 * 그림</b>만 남고, 팀은 이미 부순 것을 다시 때린다.
		 */
		private void shatter(ServerLevel level) {
			double middle = getY() + HIT_HEIGHT / 2.0;
			level.sendParticles(ParticleTypes.END_ROD, true, false,
					getX(), middle, getZ(), 60, 0.4, 0.6, 0.4, 0.25);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false,
					getX(), middle, getZ(), 40, 0.4, 0.6, 0.4, 0.12);
			// block/amethyst/break1~4 다. 유리 깨지는 소리(random/glass)보다 수정에 가깝다.
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_BREAK,
					SoundSource.HOSTILE, 1.2F, 0.9F);
			DragonLastStandShells.drop(shell);
			shell = null;
			discard();
		}

		/**
		 * 우클릭으로 아무것도 못 하게 한다.
		 *
		 * <p>바닐라 갑옷 거치대는 손에 든 것과 장비를 <b>우클릭으로 바꿔 끼운다.</b> 막지 않으면
		 * 사람이 오브젝트에 투구를 씌울 수 있고, 그만큼 「무엇을 때리는 것인가」가 흐려진다.
		 */
		@Override
		public InteractionResult interact(Player player, InteractionHand hand, Vec3 at) {
			return InteractionResult.PASS;
		}

		/** ⚠ <b>저장하지 않는다.</b> 서버 강제 종료에도 다음 판에 남지 않는다. */
		@Override
		public boolean shouldBeSaved() {
			return false;
		}

		/**
		 * 심지를 한 칸 태운다. 지우는 줄을 아무도 못 지난 경우의 바닥이다.
		 *
		 * <p>⚠ 심지로 사라질 때도 <b>그림을 함께</b> 거둔다.
		 */
		@Override
		public void tick() {
			super.tick();
			if (--fuse <= 0) {
				DragonLastStandShells.drop(shell);
				shell = null;
				discard();
			}
		}
	}
}
