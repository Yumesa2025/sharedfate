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
 *   <tr><td>타이머</td><td><b>15초</b></td></tr>
 *   <tr><td>못 부수면</td><td>하나마다 드래곤 체력 <b>5% 회복</b></td></tr>
 *   <tr><td>서는 모습</td><td><b>하늘에서 순차로</b> 박힌다 — 먼저 <b>흰 신호기 선</b>이 서고
 *       하나씩 떨어진다</td></tr>
 *   <tr><td>크기</td><td><b>블록 한 칸</b>({@link #CRYSTAL_HEIGHT})</td></tr>
 *   <tr><td>크리스탈마다 뜨던 바</td><td><b>없앤다.</b> 타이머는 <b>가운데 하나</b>로 남는다</td></tr>
 * </table>
 *
 * <p>⚠⚠ <b>4인이 여섯을 다 놓치면 30% = 720 회복이다.</b> 이 페이즈가 1200 을 깎는 싸움이라
 * <b>절반 넘게 되돌아간다.</b> 사람이 그 사실을 알고 타이머만 10 → 15초로 늘렸다 —
 * <b>값을 바꾸지 말 것.</b>
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
 *   <li><b>크리스탈마다 뜨던 바를 없앴다</b> — 아래 「바는 하나다」</li>
 *   <li><b>연결선</b> — {@link #beams}. <b>회복이 실제로 도는 동안만</b> 나간다(아래 「회복은
 *       1.5초에 걸쳐 돈다」)</li>
 *   <li><b>소리</b> — {@code block/amethyst/resonate1~4}(아래 {@link #HEAL_SOUND_TICKS})</li>
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
 *       {@value #RING_RADIUS}칸에 서므로 반드시 그 안이고, 15초면 <b>30</b> 을 공짜로 돌려준다 —
 *       {@code DragonLastStand.enter} 가 진입할 때 크리스탈을 전부 지우는 이유가 정확히 그것이다.
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
 *       사라지면 5% 회복이 저절로 면제되어 파도가 공짜가 된다</li>
 * </ul>
 *
 * <p>⚠ 이 페이즈의 공격이 오브젝트를 깨뜨리지 않는지 확인했다. 상시 번개는
 * {@code setVisualOnly(true)} 라 피해가 없고, 부채꼴·흡입·균열은 <b>사람에게 직접</b>
 * {@code hurtServer} 를 부르므로 범위에 든 개체를 치지 않는다. 곧 <b>드래곤이 제 오브젝트를
 * 깨뜨려 주는 일이 없다.</b>
 *
 * <p>⚠ {@code DamageTypeTags.BYPASSES_INVULNERABILITY}(운영자 {@code /kill})만은 그대로
 * 통과시킨다 — 운영자가 판을 치울 길을 막지 않는다. 그때는 <b>부순 것으로 센다</b>(회복 없음).
 *
 * <h2>바는 하나다 — 사람이 「그 크리스탈들 체력바를 없애」라고 했다</h2>
 *
 * <p>크리스탈마다 바가 하나씩 떠 있었다. 그것은 <b>타이머</b>였는데(여섯이 같은 수를 가리켰다)
 * <b>크리스탈 위에 뜬 바는 그 크리스탈의 체력으로 읽힌다</b> — 때리는 물건 위의 바를 시간으로
 * 읽을 사람은 없다. 그래서 <b>자리마다 있던 바를 걷고</b>({@link Slot} 에 바 칸이 없는 것이 그
 * 증거다) 타이머는 <b>아레나 가운데, 드래곤 머리 위에 하나</b>만 둔다({@link Wave#barFill}).
 *
 * <p>⚠ <b>지운 것은 「크리스탈마다 떠 있던 것」이고 타이머 자체는 지우지 않았다.</b> 그것을 지우면
 * 남은 시간을 볼 길이 사라진다 — 사람이 눈으로 확인할 목록에 「타이머 바가 가로로 줄어드는지」가
 * 들어 있다. 하나로 줄여서 좋아진 것이 둘이다. 어느 바를 봐도 같은 수였으니 <b>읽을 곳이 하나로
 * 모였고</b>, 바가 크리스탈에서 떨어지면서 <b>「이것은 저 크리스탈의 것이 아니다」가 자리로
 * 드러난다.</b>
 *
 * <p>보스바는 쓸 수 없다(드래곤 것이 이미 있고 하나뿐이다). 개체 이름표도 아니다 —
 * {@code EntityRenderer.shouldShowName} 이 이름표를 <b>64칸</b>에서 자르는데 아레나 반경이 40 이라
 * 팀원 둘이 80칸 떨어질 수 있다. 그래서 <b>디스플레이 개체 두 장</b>이다(어두운 배경 한 장 +
 * 줄어드는 흰 채움 한 장, {@code view_range} 를 128칸으로 우리가 정한다 —
 * {@link DragonLastStandLights}).
 *
 * <p>색은 규약({@code TrialWarning.Colors})을 쓰지 않는다. 그쪽은 <b>바닥에 그리는 위험 표식</b>의
 * 규약이고 이 바는 <b>읽는 계기</b>다 — {@code TrialEntrance} 가 고리를 「엔드의 색」으로 쓴 것과
 * 같은 판단이다. 흰색인 것은 이 오브젝트를 세운 <b>흰 신호기 선</b>과 같은 색이기 때문이다.
 *
 * <h2>시간표 — 흰 선이 먼저, 그다음 하나씩</h2>
 *
 * <pre>
 * 0 .. {@value #RISE_TICKS}         자리마다 <b>흰 신호기 선</b>이 선다. 아직 아무것도 없다
 * {@value #RISE_TICKS} + k×{@value #DROP_STRIDE_TICKS}  k 번째가 하늘에서 떨어지기 시작({@value #FALL_TICKS}틱)
 * 떨어진 틱                  그 자리에 <b>박힌다</b> — 흰 선이 꺼지고 크리스탈이 선다
 * plantTicks(n)             마지막이 박힌 틱. <b>가운데 타이머 바가 서고 여기서부터 15초를 센다</b>
 * plantTicks(n) + {@value #TIMER_TICKS}  남은 것이 있으면 <b>회복이 돌기 시작한다</b>
 * ... + {@value #DRAIN_TICKS}       회복이 끝난다. 연결선과 소리가 이 구간에만 있다
 * </pre>
 *
 * <p><b>타이머는 마지막이 박힌 뒤에 하나로 돈다.</b> 오브젝트마다 따로 세면 먼저 박힌 것이 먼저
 * 죽어 「어느 것이 몇 초 남았나」를 여섯 번 읽어야 한다. 하나면 어느 때든 읽을 수가 하나라
 * <b>사람이 한 번만 읽는다</b> — 그리고 사람이 정한 「타이머 15초」가 <b>전부에게 15초</b>가 된다.
 *
 * <p><b>다 부수면 남은 시간은 버린다.</b> 그 틱에 파도가 끝나고 회복도 없다. 남겨 두면 아무것도
 * 없는 바가 초읽기를 이어 가고, 팀은 이미 이긴 일을 기다린다.
 *
 * <h2>회복은 1.5초에 걸쳐 돈다 — <b>연결선을 거짓말로 만들지 않으려고</b></h2>
 *
 * <p>사람이 <b>「그 크리스탈에서 엔더드래곤으로 연결해서 체력을 회복하고잇다는걸 보여줫으면」</b>
 * 이라고 했다. 그런데 회복은 <b>한 틱에 끝나는 일</b>이었다 — 15초가 지나는 그 틱에
 * {@code dragon.heal} 한 번이 전부였고, 한 틱만 보이는 선은 아무도 못 본다.
 *
 * <p>그래서 회복을 {@value #DRAIN_TICKS}틱에 <b>나눠</b> 준다({@link #healStep}). <b>총량은 그대로
 * 사람이 정한 5%×개수</b>이고 시험이 그 합을 못박는다. 얻는 것이 셋이다.
 *
 * <ul>
 *   <li><b>연결선이 거짓이 아니다.</b> 선은 {@link #drain} 안에서만 나가므로 <b>회복이 실제로 도는
 *       틱에만</b> 있다 — 「아직 15초가 안 지났는데 이미 회복 중인 것처럼」 보이는 틱이 하나도
 *       없다</li>
 *   <li><b>소리에 둘 주기가 생긴다.</b> 한 틱이면 소리도 한 번이고, 매 틱 돌면 초당 20번이다
 *       ({@link #HEAL_SOUND_TICKS})</li>
 *   <li><b>사람이 수를 눈으로 본다.</b> 드래곤 보스바가 1.5초에 걸쳐 차므로 「얼마나 되돌아갔나」가
 *       읽힌다</li>
 * </ul>
 *
 * <p>⚠ <b>그 1.5초 동안 남은 크리스탈은 못 부순다</b>({@link Shard#spent}). 못 부순 몫이 이미
 * 굳은 뒤라, 깨뜨릴 수 있게 두면 <b>선이 끊긴 자리에서 회복만 계속되는</b> 틱이 생긴다 — 그것이
 * 정확히 이 설계가 피하려는 거짓말이다. 15초를 늘린 것이 아니다(타이머는 그 전에 이미 끝났다).
 *
 * <p>⚠ <b>바닐라 크리스탈의 빔은 쓸 수 없다.</b> 26.3 에서 그 빔은
 * {@code EndCrystal.DATA_BEAM_TARGET}({@code Optional<BlockPos>}) 을 <b>{@code EndCrystal} 개체의
 * 렌더러가</b> 그리는 것이다. 개체가 있어야 빔이 있고, 그 개체는 위의 셋이 막는다. 그래서
 * <b>점으로 선을 긋는다</b>({@link #beams}).
 *
 * <h2>점 예산 — 파도가 <b>{@link #worstCasePointsPerTick} 만큼</b> 쓴다</h2>
 *
 * <p>파도는 패턴·번개와 <b>같은 틱에 함께 돈다.</b> 흰 선과 타이머 바는 <b>디스플레이 개체</b>라
 * 0점이고, 떨어지는 줄기·박히는 빛·부서지는 빛은 <b>개수를 세는 형태</b>라 꾸러미 한 장씩이다.
 *
 * <p>⚠ <b>연결선만은 점을 쓴다.</b> 여섯 줄이라 늘기 쉬운 자리이므로 둘로 막았다.
 *
 * <ul>
 *   <li><b>줄 하나에 찍는 점 수를 거리와 무관하게 고정</b>했다({@value #BEAM_POINTS}) — 멀면
 *       간격이 벌어질 뿐이라 <b>예산이 값에서 바로 나온다</b></li>
 *   <li><b>{@value #BEAM_STRIDE}틱에 나눠 그린다</b>({@link #BEAM_STRIDE}) — 61점이 31점이 된다.
 *       패턴 쪽이 272 → 356 으로 오르면서 남은 몫이 84점뿐이라 그 절반도 쓰지 않는 쪽을 골랐다</li>
 * </ul>
 *
 * <p>시험이 {@link #worstCasePointsPerTick} 을 못박고 패턴 몫과의 <b>합</b>을
 * {@code TrialLandingShock.MAX_POINTS_PER_TICK}(440)과 견준다.
 *
 * <h2>⚠⚠ 다섯 자리에서 지운다 — {@code DragonLastStandConePanel} 을 그대로 따른다</h2>
 *
 * <p>개체는 남는다. 남으면 <b>다음 판에 부술 수 없는 것이 떠 있다.</b> 이제 자리마다 개체가
 * <b>둘</b>(갑옷 거치대 + 겉모습)이고 <b>둘 다</b> 같은 다섯 자리를 지난다.
 *
 * <ol>
 *   <li><b>저장을 아예 안 한다</b>({@link Shard#shouldBeSaved()} ·
 *       {@code DragonLastStandShells.Shell.shouldBeSaved()}). 서버 강제 종료에는
 *       {@code SERVER_STOPPING} 도 오지 않으므로 지우는 코드로는 막을 수 없다</li>
 *   <li><b>스스로 타 없어지는 심지</b>({@link Shard#tick()} · {@code Shell.tick()})</li>
 *   <li><b>부서지는 그 자리</b>({@link Shard#shatter}) — 겉모습을 같은 틱에 거둔다. ⚠ 여기를
 *       빠뜨리면 <b>깨뜨린 자리에 크리스탈 그림만 남는다</b></li>
 *   <li><b>파도가 끝나는 틱</b>({@link #close}) — 다 부쉈거나 회복이 끝났을 때</li>
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
	 * <p>다이아 검 7 이면 <b>다섯 대</b>, 힘껏 당긴 활(9 남짓)이면 <b>네 발</b>이다. 15초에
	 * 4인이 여섯을 부수려면 사람마다 한 개 반씩 맡아야 한다.
	 */
	static final float OBJECT_HEALTH = 30.0F;

	/**
	 * 타이머. 15초. <b>사람이 정한 값이다.</b>
	 *
	 * <p>⚠ 처음에 10초였고 <b>사람이 「다 놓치면 절반 넘게 되돌아간다」를 알고</b> 15초로
	 * 늘렸다. 값을 되돌리지 말 것.
	 */
	static final int TIMER_TICKS = 300;

	/**
	 * 못 부순 것 하나마다 드래곤이 되찾는 최대 체력 비율. <b>사람이 정한 값이다.</b>
	 *
	 * <p>4인이 여섯을 다 놓치면 <b>30%</b> 다. 4인 기준 최대 2400 이라 <b>720</b> 이고, 이
	 * 페이즈가 깎아야 하는 것이 1200 이므로 <b>절반이 넘는다.</b>
	 *
	 * <p>⚠ 이 값은 <b>총량</b>이다. {@value #DRAIN_TICKS}틱에 나눠 주지만 합은 그대로다
	 * ({@link #healStep}).
	 */
	static final float MISS_HEAL_FRACTION = 0.05F;

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

	/**
	 * 하나뿐인 타이머 바를 <b>드래곤 머리 위로</b> 띄우는 높이(칸).
	 *
	 * <p>앉은 드래곤의 상자가 <b>높이 8</b>이라(26.3 {@code EntityTypes} 의
	 * {@code sized(16.0F, 8.0F)} 에서 확인했다) 그보다 위여야 몸에 가리지 않는다. 11 이면 세 칸
	 * 여유다.
	 */
	private static final double TIMER_BAR_LIFT = 11.0;

	/**
	 * 타이머 바가 가득일 때의 길이(칸).
	 *
	 * <p>크리스탈마다 있을 때는 3칸이었다. 하나로 줄면서 <b>보는 거리가 멀어졌으므로</b>(아레나
	 * 가운데 위라 바깥에서 보면 20칸 남짓) 그만큼 길게 둔다.
	 */
	private static final double TIMER_BAR_WIDTH = 6.0;

	/**
	 * 타이머 바를 고쳐 세우는 간격(틱). 0.25초.
	 *
	 * <p>매 틱 고치면 개체 데이터 꾸러미가 매 틱 나간다. 15초에 6칸이 줄어드는 바라 한 틱에
	 * 0.02칸이고, 5틱이면 0.1칸씩 줄어 <b>눈에는 이어져 보인다.</b>
	 */
	private static final int BAR_STRIDE_TICKS = 5;

	/** 떨어지는 동안 줄기에 찍는 점 수. 한 틱에 <b>떨어지는 것마다</b> 이만큼이다. */
	private static final int FALL_TRAIL_POINTS = 3;

	/** 서 있는 동안 반짝이는 간격(틱). 개수를 세는 형태라 꾸러미 한 장이다. */
	private static final int IDLE_TICKS = 10;

	// ------------------------------------------------------------------ 회복이 도는 1.5초

	/**
	 * 못 부순 몫을 드래곤에게 흘려 넣는 시간(틱). 1.5초.
	 *
	 * <p>⚠ <b>타이머가 아니다.</b> 15초는 그 전에 이미 끝났다. 이것은 「회복이 일어나는 것을
	 * 보여 주는」 구간이고, 사람이 정한 값(5%×개수)을 <b>나눠 주는 창</b>일 뿐이다.
	 *
	 * <p>1.5초인 까닭. 더 짧으면 보스바가 차는 것이 안 읽히고, 더 길면 <b>패턴이 도는 중에</b>
	 * 아무것도 할 수 없는 구경 시간이 길어진다 — 이 페이즈는 파도가 도는 동안에도 패턴을 멈추지
	 * 않는다.
	 */
	static final int DRAIN_TICKS = 30;

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
	 * ({@code DragonLastStandPatterns} 클래스 설명). 여섯 줄을 매 틱 다 그리면 61점이라 그 몫의
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
	 * 규약이고 이 선은 <b>위험이 아니라 읽는 것</b>이다(타이머 바와 같은 판단). 흰색인 것은 이
	 * 파도의 다른 모든 빛(신호기 선 · 떨어지는 줄기 · 박히는 빛)과 같은 색이기 때문이다 —
	 * <b>「크리스탈의 빛이 드래곤으로 흘러간다」</b>가 그 한 줄이 하는 말이다.
	 */
	static final int BEAM_COLOR = 0xFFFFFF;

	/**
	 * 회복 소리의 주기(틱). 0.5초.
	 *
	 * <p>⚠ <b>회복은 매 틱 돈다.</b> 거기에 소리를 붙이면 <b>초당 20번</b>이다. 0.5초면
	 * {@value #DRAIN_TICKS}틱에 세 번 들리고, 음높이가 올라가므로 <b>세 번이 한 흐름으로</b>
	 * 읽힌다.
	 */
	static final int HEAL_SOUND_TICKS = 10;

	/**
	 * 개체가 스스로 타 없어지기까지의 틱. <b>가장 긴 파도 + 회복 + 15초</b>다.
	 *
	 * <p>제때 지우는 길 넷이 전부 실패했을 때의 바닥이다. 실제로 필요한 것은 파도 길이뿐이고
	 * 나머지는 여유다 — 값에서 직접 세므로 타이머나 간격을 고치는 사람이 여기를 따로 고칠 일이
	 * 없다({@code DragonLastStandConePanel.FUSE_TICKS} 와 같은 판단이다).
	 */
	static final int FUSE_TICKS =
			waveTicks(COUNT_BY_MEMBERS[COUNT_BY_MEMBERS.length - 1]) + DRAIN_TICKS + TIMER_TICKS;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 파도를 몰고 있는 판의 시계 원점.
	 *
	 * <p>{@code DragonLastStandZone.drivingSince} 와 <b>같은 모양</b>이다. 드래곤은 차원에
	 * 하나뿐이라 두 판이 같은 드래곤에 파도를 열면 개수도 회복도 두 배가 된다 — <b>먼저 든 판이
	 * 몰고 간다.</b>
	 */
	private static long owner = Long.MIN_VALUE;

	/** 지금까지 터진 문턱의 수. 0·1·2 다. {@link #THRESHOLDS} 를 순서대로 먹는다. */
	private static int firedWaves;

	/** 지금 도는 파도. 없으면 {@code null} 이다. */
	private static @Nullable Wave wave;

	/**
	 * 한 자리. 흰 선 → 떨어짐 → 크리스탈.
	 *
	 * <p>⚠ <b>바 칸이 없다.</b> 사람이 「그 크리스탈들 체력바를 없애」라고 한 것이 자리마다 떠
	 * 있던 그 바이고, 타이머는 {@link Wave} 가 하나만 든다.
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
		/** 이미 박았는가. 한 번만 세운다. */
		private boolean planted;

		private Slot(Vec3 seat, int dropsAt) {
			this.seat = seat;
			this.dropsAt = dropsAt;
		}

		/** 이 자리가 아직 살아 있는가. 곧 <b>회복할 몫이 남았는가</b>다. */
		private boolean standing() {
			return shard != null && shard.isAlive();
		}
	}

	/** 한 번의 파도. */
	private static final class Wave {
		private final long beganAt;
		private final int threshold;
		/** 고리의 가운데. 드래곤이 못박혀 있는 자리이고 <b>타이머 바가 서는 자리</b>다. */
		private final Vec3 anchor;
		private final List<Slot> slots;
		/** 타이머가 시작하는 틱(파도 시작부터). 마지막이 박히는 틱이다. */
		private final int countdownAt;
		/** 하나뿐인 타이머 바의 어두운 배경. */
		private @Nullable DragonLastStandLights.Glow barBack;
		/** 하나뿐인 타이머 바의 줄어드는 흰 채움. */
		private @Nullable DragonLastStandLights.Glow barFill;
		/** 회복이 돌기 시작한 틱(파도 시작부터). <b>−1 이면 아직 안 돈다.</b> */
		private int drainAt = -1;
		/** 못 부순 개수. 회복이 시작하는 틱에 <b>굳는다.</b> */
		private int missed;
		/** 이 파도가 줄 회복의 <b>총량</b>. {@link #DRAIN_TICKS}틱에 나눠 준다. */
		private float healTotal;

		private Wave(long beganAt, int threshold, Vec3 anchor, List<Slot> slots, int countdownAt) {
			this.beganAt = beganAt;
			this.threshold = threshold;
			this.anchor = anchor;
			this.slots = slots;
			this.countdownAt = countdownAt;
		}
	}

	private DragonLastStandObjects() {
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 이 인원에 몇 개인가. <b>사람이 적은 표 그대로다.</b>
	 *
	 * <p>0명이면 0 이다 — 아무도 없는 판에 오브젝트를 세우면 15초 뒤에 <b>아무도 하지 않은 일</b>로
	 * 드래곤이 회복한다. 다섯 이상은 4인 값으로 자른다(사람의 표가 넷에서 끝나고 이 판의 팀이
	 * 넷이다).
	 */
	static int objectCount(int members) {
		if (members <= 0) {
			return 0;
		}
		return COUNT_BY_MEMBERS[Math.min(members, COUNT_BY_MEMBERS.length) - 1];
	}

	/**
	 * 마지막 오브젝트가 박히는 틱(파도 시작부터). <b>타이머가 시작하는 시각</b>이기도 하다.
	 *
	 * <p>값에서 직접 센다 — 간격이나 낙하 시간을 고치는 사람이 「그래서 15초가 언제 시작하나」를
	 * 손으로 세지 않게 하는 것이 이 함수의 존재 이유다.
	 */
	static int plantTicks(int count) {
		if (count <= 0) {
			return RISE_TICKS;
		}
		return RISE_TICKS + (count - 1) * DROP_STRIDE_TICKS + FALL_TICKS;
	}

	/** 파도 전체 길이(틱). 다 부수면 그보다 일찍 끝나고, 못 부수면 회복 1.5초가 더 붙는다. */
	static int waveTicks(int count) {
		return plantTicks(count) + TIMER_TICKS;
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
	 * 남은 타이머의 비율. 1 이 가득, 0 이 끝이다.
	 *
	 * <p>타이머가 시작하기 전에는 1 이다 — 떨어지는 동안 바가 줄면 「이미 시간이 가고 있다」로
	 * 읽혀 사람이 정한 「15초」가 거짓이 된다.
	 */
	static float remainingRatio(int step, int countdownAt) {
		if (step <= countdownAt) {
			return 1.0F;
		}
		int gone = step - countdownAt;
		if (gone >= TIMER_TICKS) {
			return 0.0F;
		}
		return 1.0F - (float) gone / TIMER_TICKS;
	}

	/**
	 * 회복이 도는 {@code gone} 번째 틱에 줄 체력.
	 *
	 * <p><b>합이 총량과 정확히 같다.</b> 「지금까지 줘야 할 양」에서 「앞 틱까지 줘야 할 양」을 빼는
	 * 식이라 나누어떨어지지 않는 총량에서도 마지막 틱이 나머지를 메운다 — 사람이 정한 5%×개수가
	 * <b>조금도 새지 않는 것</b>이 이 식의 존재 이유이고 시험이 그 합을 직접 더해 본다.
	 */
	static float healStep(float total, int gone) {
		if (gone < 0 || gone >= DRAIN_TICKS || !(total > 0.0F)) {
			return 0.0F;
		}
		float before = total * gone / DRAIN_TICKS;
		float after = total * (gone + 1) / DRAIN_TICKS;
		return Math.max(0.0F, after - before);
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
	 * <p>가장 바쁜 틱은 <b>회복이 도는 틱</b>이다 — 연결선 여섯 줄을 {@value #BEAM_STRIDE}틱에
	 * 나눈 몫 + 드래곤 머리 위 하트 꾸러미 한 장. 세우는 동안은 그보다 한참 적다(동시에 떨어지는
	 * 것이 둘, 박히는 꾸러미 둘, 반짝임이 개수만큼).
	 */
	static int worstCasePointsPerTick() {
		int count = COUNT_BY_MEMBERS[COUNT_BY_MEMBERS.length - 1];
		// 위상에 따라 하나 더 나갈 수 있으므로 올림이다 — 예산을 묻는 자리는 늘 나쁜 쪽을 본다.
		int perBeam = (BEAM_POINTS + BEAM_STRIDE - 1) / Math.max(1, BEAM_STRIDE);
		int draining = count * perBeam + 1;
		int planting = FALL_TRAIL_POINTS * Math.max(1, FALL_TICKS / DROP_STRIDE_TICKS) + 2 + count;
		return Math.max(draining, planting);
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
	 * @param anchor    드래곤을 못박아 둔 자리. 고리의 가운데이고 타이머 바가 서는 자리다
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
	 * <p>⚠ <b>회복은 주지 않는다.</b> 판이 닫히는 자리에서 「못 부쉈으니 5%」를 얹으면 이미 죽은
	 * 드래곤이나 사라진 월드에 값을 쓰는 것이 된다. 회복은 {@link #drain} 한 곳에만 있다.
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
			dropTimerBar(wave);
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

	/** 시험이 들여다보는 곳. 지금 회복이 도는가. 곧 <b>연결선이 보이는가</b>다. */
	static boolean draining() {
		return wave != null && wave.drainAt >= 0;
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
				// 허공이다. 버린다 — 상시 번개와 같은 판단이고, 회복할 몫도 함께 줄어든다.
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
				"[END] 오브젝트 파도 — 체력 {}% · {}개 · 체력 {} · 크기 {}칸 · 타이머 {}초 · "
						+ "못 부수면 하나마다 {}%",
				Math.round(THRESHOLDS[firedWaves] * 100.0F), slots.size(), OBJECT_HEALTH,
				CRYSTAL_HEIGHT, TIMER_TICKS / 20, Math.round(MISS_HEAL_FRACTION * 100.0F));
	}

	// ------------------------------------------------------------------ 파도 한 틱

	private static void run(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			long now) {
		Wave current = wave;
		if (current == null) {
			return;
		}
		int step = (int) Math.max(0L, now - current.beganAt);
		if (current.drainAt >= 0) {
			// 타이머는 이미 끝났다. 남은 것은 회복을 흘려 넣고 그것을 보여 주는 일뿐이다.
			drain(end, dragon, members, current, step);
			return;
		}
		for (Slot slot : current.slots) {
			advance(end, slot, step);
		}
		if (liveCount() <= 0 && step >= current.countdownAt) {
			// 다 부쉈다. 남은 시간은 버린다 — 까닭은 클래스 설명에 있다.
			// ⚠ 바를 세우기 전에 묻는다. 마지막 하나를 박히는 틱에 부수면 세우고 그 틱에 거두는
			// 일이 되어 사람에게 바가 한 번 깜빡인다.
			close(end, members, current, true);
			return;
		}
		timerBar(end, current, step);
		if (step >= current.countdownAt + TIMER_TICKS) {
			beginDrain(end, dragon, members, current, step);
		}
	}

	/** 자리 하나를 한 틱 나아가게 한다 — 떨어지고, 박히고, 반짝인다. */
	private static void advance(ServerLevel end, Slot slot, int step) {
		if (!slot.planted) {
			if (step < slot.dropsAt) {
				return;
			}
			int falling = step - slot.dropsAt;
			if (falling < FALL_TICKS) {
				streak(end, slot, falling);
				return;
			}
			plant(end, slot);
			return;
		}
		if (!slot.standing()) {
			// 상자가 사라졌는데 그림만 남는 길이 둘 있다 — 운영자 /kill 과 심지. 여기서 거둔다.
			DragonLastStandShells.drop(slot.shell);
			slot.shell = null;
			return;
		}
		if (step % IDLE_TICKS == 0) {
			// 개수를 세는 형태라 꾸러미 한 장이다 — 점 예산과 무관하다.
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
	 */
	private static void plant(ServerLevel end, Slot slot) {
		slot.planted = true;
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

	/**
	 * 하나뿐인 타이머 바. <b>마지막이 박히는 틱에 서고</b> 그때부터 줄어든다.
	 *
	 * <p>서는 때를 늦춘 것이 뜻이 있다 — 떨어지는 동안 가득 찬 바가 떠 있으면 「벌써 시작했나」를
	 * 묻게 된다. <b>바가 보이는 순간이 곧 15초의 시작</b>이다.
	 *
	 * <p>배경은 그대로 두고 <b>채움만</b> 줄인다. 배경과 같은 「가득일 때 길이」를 넘기므로 왼쪽
	 * 끝이 정확히 맞는다({@code DragonLastStandLights.barShape}).
	 */
	private static void timerBar(ServerLevel end, Wave current, int step) {
		if (step < current.countdownAt) {
			return;
		}
		if (current.barFill == null && current.barBack == null) {
			Vec3 at = current.anchor.add(0.0, TIMER_BAR_LIFT, 0.0);
			current.barBack = DragonLastStandLights.raiseBarBack(end, at, DyeColor.BLACK,
					TIMER_BAR_WIDTH, FUSE_TICKS);
			current.barFill = DragonLastStandLights.raiseBarFill(end, at, DyeColor.WHITE,
					TIMER_BAR_WIDTH, FUSE_TICKS);
			return;
		}
		if (step % BAR_STRIDE_TICKS != 0) {
			return;
		}
		DragonLastStandLights.reshapeBarFill(end, current.barFill, TIMER_BAR_WIDTH,
				TIMER_BAR_WIDTH * remainingRatio(step, current.countdownAt));
	}

	// ------------------------------------------------------------------ 회복이 도는 1.5초

	/**
	 * 15초가 끝났다. <b>못 부순 몫을 굳히고</b> 회복을 시작한다.
	 *
	 * <p>⚠ 여기서 {@code heal} 을 부르지 않는다 — 회복은 {@link #drain} 이 틱마다 나눠 준다.
	 * 그래야 연결선과 소리가 <b>회복이 실제로 도는 동안</b>에만 있다.
	 */
	private static void beginDrain(ServerLevel end, EnderDragon dragon,
			List<ServerPlayer> members, Wave current, int step) {
		int missed = 0;
		for (Slot slot : current.slots) {
			Shard shard = slot.shard;
			if (shard != null && shard.isAlive()) {
				missed++;
				// ⚠ 몫이 굳은 뒤에 깨뜨릴 수 있게 두면 「선이 끊긴 자리에서 회복만 계속되는」
				// 틱이 생긴다. 1.5초 동안만 못 부순다 — 타이머를 늘린 것이 아니다.
				shard.spend();
			}
		}
		// 시간이 다 된 바는 더 보여 줄 것이 없다. 남겨 두면 빈 바가 회복 연출을 가린다.
		dropTimerBar(current);
		float max = dragon.getMaxHealth();
		float total = max > 0.0F ? max * MISS_HEAL_FRACTION * missed : 0.0F;
		current.missed = missed;
		current.healTotal = total;
		if (missed <= 0 || !(total > 0.0F) || !dragon.isAlive()) {
			close(end, members, current, missed <= 0);
			return;
		}
		current.drainAt = step;
		// block/beacon/deactivate 다(sounds.json 에서 확인했다) — 「시간이 꺼졌다」가 그 소리다.
		TrialWarning.playEach(end, members, SoundEvents.BEACON_DEACTIVATE, 1.0F, 0.7F);
		// mob/enderdragon/growl1~4 다. 드래곤이 되찾는 것을 드래곤의 목소리로 알린다.
		TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.6F);
		SharedFateMod.LOGGER.info(
				"[END] 오브젝트 파도 — {}개를 못 부쉈습니다 · {}초에 걸쳐 드래곤 체력 +{}",
				missed, DRAIN_TICKS / 20.0F, total);
	}

	/**
	 * 회복이 도는 한 틱. <b>연결선과 소리가 여기에만 있다.</b>
	 *
	 * <p>⚠ 드래곤이 그 사이에 죽으면 그 틱에 닫는다 — 죽은 드래곤에게 체력을 쓰면
	 * {@code DragonLastStand.onFightClosed} 뒤에 되살아난 것처럼 보인다.
	 */
	private static void drain(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			Wave current, int step) {
		int gone = step - current.drainAt;
		if (gone >= DRAIN_TICKS || !dragon.isAlive()) {
			close(end, members, current, false);
			return;
		}
		float give = healStep(current.healTotal, gone);
		if (give > 0.0F) {
			dragon.heal(give);
		}
		beams(end, dragon, current, gone);
		if (gone % HEAL_SOUND_TICKS == 0) {
			// block/amethyst/resonate1~4 다(sounds.json 에서 확인했다 — chime 은 shimmer,
			// hit 은 step*, break 는 break* 라 넷이 전부 다른 파일이다). 이 저장소의 어느 카드도
			// 쓰지 않고, 수정이 울리는 소리라 「크리스탈이 뭔가를 보내고 있다」가 들린다.
			// ⚠ 위치 기반 playSound 를 팀원 루프에서 부르면 사람 수만큼 겹친다 — playEach 다.
			TrialWarning.playEach(end, members, SoundEvents.AMETHYST_BLOCK_RESONATE, 1.0F,
					drainPitch(gone));
			// 하트. 개수를 세는 형태라 꾸러미 한 장이고, 드래곤 몸 가운데에서 피어난다.
			Vec3 heart = dragon.getBoundingBox().getCenter();
			end.sendParticles(ParticleTypes.HEART, true, false, heart.x, heart.y, heart.z, 24,
					3.0, 1.5, 3.0, 0.05);
		}
	}

	/**
	 * 회복 소리의 음높이. <b>올라간다.</b>
	 *
	 * <p>드래곤이 되찾는 중이라는 것을 음높이로 말한다 — 떨어지면 「끝나 간다」로 들린다.
	 */
	static float drainPitch(int gone) {
		float along = DRAIN_TICKS <= 0 ? 1.0F : (float) gone / DRAIN_TICKS;
		return 0.8F + 0.4F * Math.min(1.0F, Math.max(0.0F, along));
	}

	/**
	 * 크리스탈마다 드래곤까지 <b>점으로 선을 긋는다.</b>
	 *
	 * <p>사람 말이 「그 크리스탈에서 엔더드래곤으로 연결해서 체력을 회복하고잇다는걸 보여줫으면」
	 * 이다. 바닐라 크리스탈의 빔은 쓸 수 없다(클래스 설명의 ⚠).
	 *
	 * <p>⚠ <b>부르는 곳이 {@link #drain} 하나다.</b> 그것이 「회복이 도는 동안만 보인다」의 전부다.
	 *
	 * <p>⚠ 끝점이 <b>드래곤 상자의 가운데</b>다. 발밑이나 머리로 잡으면 앉은 드래곤이 몸을 돌릴 때
	 * 선이 몸에서 떨어진다 — 가운데는 상자가 16×8 이라 <b>언제나 몸 안</b>이다.
	 *
	 * <p>⚠ 파티클은 <b>긴 형식</b>이다. 짧은 형식은 32칸에서 잘리고, 이 선은 길이가 10칸이라
	 * 보는 사람이 반대쪽에 있으면 통째로 사라진다.
	 */
	private static void beams(ServerLevel end, EnderDragon dragon, Wave current, int gone) {
		ParticleOptions dust = TrialWarning.dust(BEAM_COLOR);
		Vec3 to = dragon.getBoundingBox().getCenter();
		for (Slot slot : current.slots) {
			if (!slot.standing()) {
				continue;
			}
			Vec3 from = new Vec3(slot.seat.x, crystalCenterY(slot.seat.y), slot.seat.z);
			Vec3 span = to.subtract(from);
			// floorMod 라야 음수 틱에서도 0..stride-1 로 떨어진다. 되감긴 판의 now 가 음수일 수 있다.
			for (int index = Math.floorMod(gone, Math.max(1, BEAM_STRIDE)); index < BEAM_POINTS;
					index += Math.max(1, BEAM_STRIDE)) {
				Vec3 at = from.add(span.scale(beamAlong(index, gone)));
				end.sendParticles(dust, true, false, at.x, at.y, at.z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
	}

	// ------------------------------------------------------------------ 파도를 닫는다

	/**
	 * 파도를 닫고 들고 있던 것을 전부 거둔다.
	 *
	 * <p>⚠ <b>여기서 회복을 주지 않는다.</b> 회복은 {@link #drain} 이 이미 틱마다 나눠 주었다 —
	 * 여기에 한 번 더 얹으면 두 번 준다.
	 *
	 * @param cleared 다 부숴서 끝났는가
	 */
	private static void close(ServerLevel end, List<ServerPlayer> members, Wave current,
			boolean cleared) {
		for (Slot slot : current.slots) {
			drop(slot);
		}
		dropTimerBar(current);
		wave = null;
		if (cleared) {
			// block/amethyst/shimmer 다. 부수는 소리와 같은 계열이라 「이 물건이 끝났다」로 들린다.
			TrialWarning.playEach(end, members, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.2F);
			SharedFateMod.LOGGER.info("[END] 오브젝트 파도 — 전부 부쉈습니다(체력 {}% 파도)",
					Math.round(THRESHOLDS[current.threshold] * 100.0F));
			return;
		}
		SharedFateMod.LOGGER.info("[END] 오브젝트 파도 — 회복이 끝났습니다({}개 · 체력 +{})",
				current.missed, current.healTotal);
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

	/** 하나뿐인 타이머 바를 거둔다. */
	private static void dropTimerBar(Wave current) {
		DragonLastStandLights.drop(current.barBack);
		DragonLastStandLights.drop(current.barFill);
		current.barBack = null;
		current.barFill = null;
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
		/**
		 * ⚠ <b>회복이 도는 1.5초 동안 참이다.</b> 못 부순 몫이 이미 굳었으므로 더 깎이지 않는다 —
		 * 까닭은 클래스 설명의 「회복은 1.5초에 걸쳐 돈다」에 있다.
		 */
		private boolean spent;
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

		/** 회복이 도는 동안 더 깎이지 않게 한다. */
		private void spend() {
			spent = true;
		}

		/**
		 * 체력 30 을 우리가 센다. <b>바닐라 규칙을 통째로 대신한다.</b>
		 *
		 * <p>바닐라 {@code ArmorStand.hurtServer} 를 부르지 않는다 — 그쪽은 <b>투명하면 거짓을
		 * 돌려주고</b>(바이트코드에 {@code invisible} 검사가 있다) 안 그래도 두 대에 부서진다.
		 *
		 * <p>{@code true} 를 돌려주는 것이 중요하다. 거짓이면 클라이언트가 <b>때린 것 자체를
		 * 무르고</b>(휘두른 팔이 헛손질이 된다) 공격 쿨타임도 다르게 돈다.
		 */
		@Override
		public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
			if (isRemoved()) {
				return false;
			}
			// 운영자가 치울 길은 남긴다. /kill 은 부순 것으로 세므로 회복이 붙지 않는다.
			if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
				DragonLastStandShells.drop(shell);
				shell = null;
				discard();
				return true;
			}
			if (spent) {
				// 회복이 도는 1.5초다. 몫이 이미 굳었다 — 클래스 설명의 ⚠ 를 볼 것.
				return false;
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
