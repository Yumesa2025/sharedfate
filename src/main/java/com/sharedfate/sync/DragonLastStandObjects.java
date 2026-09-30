package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
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
import net.minecraft.world.entity.EquipmentSlot;
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
 *   <tr><td>그 위에</td><td><b>일자 타이머 바</b></td></tr>
 * </table>
 *
 * <p>⚠⚠ <b>4인이 여섯을 다 놓치면 30% = 720 회복이다.</b> 이 페이즈가 1200 을 깎는 싸움이라
 * <b>절반 넘게 되돌아간다.</b> 사람이 그 사실을 알고 타이머만 10 → 15초로 늘렸다 —
 * <b>값을 바꾸지 말 것.</b>
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
 * <p>✅ <b>고른 것은 {@code armor_stand} 하위 클래스 하나다</b>({@link Shard}). 개체 <b>한
 * 마리</b>가 맞는 상자와 체력 30 과 보이는 모습을 전부 든다.
 *
 * <table border="1">
 *   <caption>재어 본 길들</caption>
 *   <tr><th>길</th><th>왜 안 되는가 / 왜 골랐는가</th></tr>
 *   <tr><td>{@code end_crystal}</td><td>위의 셋</td></tr>
 *   <tr><td>몹({@code zombie} 등)에 최대 체력 30</td>
 *       <td>피해는 제대로 들어간다. 그런데 <b>죽는 순간 바닐라가 전리품과 경험치를 떨어뜨리고</b>
 *           죽는 소리가 그 몹의 것이다 — 「엔드 크리스탈 같은 것」이 좀비 소리를 내며 썩은 살을
 *           떨어뜨린다</td></tr>
 *   <tr><td>디스플레이 개체만</td>
 *       <td>아니다. {@code Display} 는 {@code isPickable()} 이 거짓이라 <b>때릴 수가 없다</b></td></tr>
 *   <tr><td><b>{@code armor_stand} 하위 클래스</b></td>
 *       <td>← 고른 것. 아래 「왜 갑옷 거치대인가」</td></tr>
 * </table>
 *
 * <h2>왜 갑옷 거치대인가 — 얻는 것이 다섯이다</h2>
 *
 * <ol>
 *   <li><b>가만히 있는다.</b> {@code setNoGravity(true)} 면 {@code ArmorStand.hasPhysics()} 가
 *       거짓이 되어 {@code travel()} 이 첫 줄에서 돌아간다(26.3 바이트코드로 확인했다). AI 도
 *       공격도 없다 — 하늘에 떠 있는 표적이 스스로 움직이지 않는다</li>
 *   <li><b>모습이 엔드 크리스탈이다.</b> 투명하게 두고 <b>머리 칸에 {@code end_crystal} 아이템</b>을
 *       끼운다. 투명한 갑옷 거치대도 <b>장비는 그린다</b> — 곧 화면에는 공중에 뜬 엔드 크리스탈만
 *       보인다. 개체를 하나 더 만들지 않고 얻는 모습이다</li>
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
 * <h2>타이머 바 — 디스플레이 개체다. 개체 이름표가 아니다</h2>
 *
 * <p>사람 말이 <b>「그위에 타이머가 있고 일자 바로」</b>다. 보스바는 쓸 수 없다(드래곤 것이 이미
 * 있고 하나뿐이다). 남은 둘을 재어 보고 <b>디스플레이 개체</b>를 골랐다.
 *
 * <table border="1">
 *   <caption>타이머를 보이게 하는 길</caption>
 *   <tr><th>길</th><th>왜</th></tr>
 *   <tr><td>보스바</td><td>아니다. 드래곤 보스바가 이미 있고 그것을 밀어낼 수 없다</td></tr>
 *   <tr><td>개체 이름표({@code █████░░░})</td>
 *       <td>글자로 바를 그릴 수는 있다. 그런데 26.3 {@code EntityRenderer.shouldShowName} 이
 *           이름표를 <b>64칸</b>에서 자르고 <b>그 값을 우리가 고를 수 없다</b> — 아레나 반경이
 *           40 이라 팀원 둘이 80칸 떨어질 수 있다</td></tr>
 *   <tr><td><b>디스플레이 개체 두 장</b></td>
 *       <td>← 고른 것. 어두운 배경 한 장 + 줄어드는 흰 채움 한 장. {@code view_range} 를 2.0
 *           (=128칸)으로 우리가 정하고, <b>점 예산을 한 개도 쓰지 않는다.</b> 빌보드가
 *           {@code CENTER} 라 어디에서 봐도 가로 바다 — {@link DragonLastStandLights}</td></tr>
 * </table>
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
 * 떨어진 틱                  그 자리에 <b>박힌다</b> — 흰 선이 꺼지고 오브젝트와 바가 선다
 * plantTicks(n)             마지막이 박힌 틱. <b>여기서부터 15초를 센다</b>
 * plantTicks(n) + {@value #TIMER_TICKS}  남은 것마다 드래곤 5% 회복
 * </pre>
 *
 * <p><b>타이머는 마지막이 박힌 뒤에 하나로 돈다.</b> 오브젝트마다 따로 세면 먼저 박힌 것이 먼저
 * 죽어 「어느 것이 몇 초 남았나」를 여섯 번 읽어야 한다. 하나면 어느 바를 봐도 같은 수라
 * <b>사람이 한 번만 읽는다</b> — 그리고 사람이 정한 「타이머 15초」가 <b>전부에게 15초</b>가 된다.
 *
 * <p><b>다 부수면 남은 시간은 버린다.</b> 그 틱에 파도가 끝나고 회복도 없다. 남겨 두면 아무것도
 * 없는 바가 초읽기를 이어 가고, 팀은 이미 이긴 일을 기다린다.
 *
 * <h2>자리 — <b>굳은 고리</b>다. 굴리지 않는다</h2>
 *
 * <p>반경 {@value #RING_RADIUS}칸 고리에 <b>고르게</b> 놓고 <b>시작 각도만</b> 무작위다. 굴려서
 * 찾지 않는 근거가 셋이다.
 *
 * <ul>
 *   <li><b>겹치지 않는 것이 증명된다.</b> 여섯이면 이웃 사이가 9칸이라 서로 안에 들어가지 않는다.
 *       상시 번개는 겹침을 <b>허용</b>했지만 그것은 피해가 겹쳐도 {@code break} 가 막아 주는
 *       경우였고, 오브젝트는 겹치면 <b>뒤의 것을 때릴 수 없다</b></li>
 *   <li>⚠ <b>마지막 지대 안인 것이 값으로 참이다.</b> {@value #RING_RADIUS} 는 안전지대의 마지막
 *       반경에서 벽 여유만큼 뺀 값이라({@link #RING_RADIUS}) 파도가 도는 동안 지대가 줄어도
 *       <b>오브젝트를 때리려고 밖으로 나가는 일이 없다.</b> 지대의 끝값을 고치면 여기가 따라간다</li>
 *   <li><b>드래곤 몸 밖이다.</b> 앉은 드래곤의 상자가 가로 16칸이라 반이 8 이고,
 *       {@value #RING_RADIUS} 는 그 바로 밖이다 — 곧 오브젝트를 때리는 자리가
 *       <b>날개 퍼덕이기가 가장 센 구간(4~12칸)</b>이다. 그 긴장은 의도다</li>
 * </ul>
 *
 * <p>허공인 자리는 <b>버린다</b>(상시 번개와 같은 판단이다). 그만큼 오브젝트가 줄고 <b>회복할
 * 몫도 함께 줄므로</b> 사람이 파 놓은 구멍이 팀에게 불리하게 돌아오지 않는다.
 *
 * <h2>점 예산 — <b>0점이다</b></h2>
 *
 * <p>파도는 패턴·번개와 <b>같은 틱에 함께 돈다.</b> 그런데 매 틱 그리는 것이 하나도 없다 —
 * 흰 선과 바는 <b>디스플레이 개체</b>이고, 떨어지는 줄기·박히는 빛·부서지는 빛·회복하는 빛은
 * 전부 <b>개수를 세는 형태</b>라 꾸러미 한 장씩이다({@code TrialEntrance.perch} 의 같은 문단).
 * 곧 {@code DragonLastStandPatterns.worstCasePointsPerTick} 의 답을 <b>바꾸지 않는다.</b>
 *
 * <h2>⚠⚠ 다섯 자리에서 지운다 — {@code DragonLastStandConePanel} 을 그대로 따른다</h2>
 *
 * <p>개체는 남는다. 남으면 <b>다음 판에 부술 수 없는 것이 떠 있다.</b>
 *
 * <ol>
 *   <li><b>저장을 아예 안 한다</b>({@link Shard#shouldBeSaved()}). 서버 강제 종료에는
 *       {@code SERVER_STOPPING} 도 오지 않으므로 지우는 코드로는 막을 수 없다</li>
 *   <li><b>스스로 타 없어지는 심지</b>({@link Shard#tick()})</li>
 *   <li><b>파도가 끝나는 틱</b> — 다 부쉈거나 15초가 지났을 때</li>
 *   <li><b>{@link #clearState()}</b> — {@code DragonLastStand.clearState} 와
 *       {@code onFightClosed} 가 부른다. ⚠ 월드를 못 만지므로 <b>개체를 들고 있어야</b> 한다</li>
 *   <li><b>{@code DragonLastStand.onServerStopping}</b> — 월드가 아직 살아 있는 자리</li>
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
	 */
	static final float MISS_HEAL_FRACTION = 0.05F;

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

	/** 타이머 바를 자리에서 띄우는 높이(칸). 머리 위 아이템(1.5칸쯤)보다 위다. */
	private static final double BAR_LIFT = 2.6;
	/** 타이머 바가 가득일 때의 길이(칸). */
	private static final double BAR_WIDTH = 3.0;
	/**
	 * 타이머 바를 고쳐 세우는 간격(틱). 0.25초.
	 *
	 * <p>매 틱 고치면 개체 데이터 꾸러미가 오브젝트 수만큼 매 틱 나간다. 15초에 3칸이 줄어드는
	 * 바라 한 틱에 0.01칸이고, 5틱이면 0.05칸씩 줄어 <b>눈에는 이어져 보인다.</b>
	 */
	private static final int BAR_STRIDE_TICKS = 5;

	/** 떨어지는 동안 줄기에 찍는 점 수. 한 틱에 <b>떨어지는 것마다</b> 이만큼이다. */
	private static final int FALL_TRAIL_POINTS = 3;

	/** 서 있는 동안 반짝이는 간격(틱). 개수를 세는 형태라 꾸러미 한 장이다. */
	private static final int IDLE_TICKS = 10;

	/**
	 * 개체가 스스로 타 없어지기까지의 틱. <b>가장 긴 파도 + 15초</b>다.
	 *
	 * <p>제때 지우는 길 넷이 전부 실패했을 때의 바닥이다. 실제로 필요한 것은 파도 길이뿐이고
	 * 나머지는 여유다 — 값에서 직접 세므로 타이머나 간격을 고치는 사람이 여기를 따로 고칠 일이
	 * 없다({@code DragonLastStandConePanel.FUSE_TICKS} 와 같은 판단이다).
	 */
	static final int FUSE_TICKS =
			plantTicks(COUNT_BY_MEMBERS[COUNT_BY_MEMBERS.length - 1]) + TIMER_TICKS + TIMER_TICKS;

	/**
	 * 맞는 상자의 크기(칸).
	 *
	 * <p>바닐라 갑옷 거치대는 0.5 × 1.975 짜리 <b>말뚝</b>이라 공중의 표적으로는 활이 자꾸
	 * 빗나간다. 보이는 것(머리 위 엔드 크리스탈)만큼으로 넓혀 <b>보이는 것과 맞는 것이 같게</b>
	 * 한다 — 이 저장소가 표식에서 지키는 「보이는 것이 곧 판정」과 같은 약속이다.
	 */
	private static final float HIT_WIDTH = 1.6F;
	private static final float HIT_HEIGHT = 2.4F;

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

	/** 한 자리. 흰 선 → 떨어짐 → 오브젝트와 바. */
	private static final class Slot {
		/** 오브젝트가 설 자리. 발밑이 지표다. */
		private final Vec3 seat;
		/** 떨어지기 시작하는 틱(파도 시작부터). */
		private final int dropsAt;
		private @Nullable DragonLastStandLights.Glow line;
		private @Nullable DragonLastStandLights.Glow barBack;
		private @Nullable DragonLastStandLights.Glow barFill;
		private @Nullable Shard shard;
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
		private final List<Slot> slots;
		/** 타이머가 시작하는 틱(파도 시작부터). 마지막이 박히는 틱이다. */
		private final int countdownAt;

		private Wave(long beganAt, int threshold, List<Slot> slots, int countdownAt) {
			this.beganAt = beganAt;
			this.threshold = threshold;
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

	/** 파도 전체 길이(틱). 다 부수면 그보다 일찍 끝난다. */
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
	 * 떨어지는 중인 오브젝트가 이 틱에 있어야 할 높이(자리에서 잰 칸).
	 *
	 * <p>{@code step} 이 낙하 구간을 벗어나면 0 이다 — 곧 자리다.
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
	 * <p>⚠ <b>회복은 주지 않는다.</b> 판이 닫히는 자리에서 「못 부쉈으니 5%」를 얹으면 이미 죽은
	 * 드래곤이나 사라진 월드에 값을 쓰는 것이 된다. 파도가 제 15초를 채웠을 때만 회복한다.
	 */
	static void clearState() {
		if (wave != null) {
			for (Slot slot : wave.slots) {
				drop(slot);
			}
		}
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
		wave = new Wave(now, firedWaves, slots, plantTicks(count));
		SharedFateMod.LOGGER.info(
				"[END] 오브젝트 파도 — 체력 {}% · {}개 · 체력 {} · 타이머 {}초 · 못 부수면 하나마다 {}%",
				Math.round(THRESHOLDS[firedWaves] * 100.0F), slots.size(), OBJECT_HEALTH,
				TIMER_TICKS / 20, Math.round(MISS_HEAL_FRACTION * 100.0F));
	}

	// ------------------------------------------------------------------ 파도 한 틱

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
		bars(end, current, step);
		if (liveCount() <= 0 && step >= current.countdownAt) {
			// 다 부쉈다. 남은 시간은 버린다 — 까닭은 클래스 설명에 있다.
			finish(end, dragon, members, current, true);
			return;
		}
		if (step >= current.countdownAt + TIMER_TICKS) {
			finish(end, dragon, members, current, false);
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
		if (slot.shard != null && slot.shard.isAlive() && step % IDLE_TICKS == 0) {
			// 개수를 세는 형태라 꾸러미 한 장이다 — 점 예산과 무관하다.
			Vec3 at = slot.shard.position();
			end.sendParticles(ParticleTypes.END_ROD, true, false, at.x, at.y + 1.4, at.z, 4,
					0.3, 0.4, 0.3, 0.01);
		}
	}

	/** 떨어지는 줄기. 한 틱에 지나간 만큼을 선으로 남긴다. */
	private static void streak(ServerLevel end, Slot slot, int falling) {
		double from = fallHeight(falling);
		double to = fallHeight(falling + 1);
		for (int index = 0; index < FALL_TRAIL_POINTS; index++) {
			double along = (double) (index + 1) / FALL_TRAIL_POINTS;
			double y = slot.seat.y + from + (to - from) * along;
			end.sendParticles(ParticleTypes.END_ROD, true, false,
					slot.seat.x, y, slot.seat.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 박는다 — 흰 선이 꺼지고 오브젝트와 타이머 바가 선다.
	 *
	 * <p>{@code random/anvil_land} 를 고른 것은 사람이 말한 것이 <b>「박힌다」</b>이기 때문이다
	 * (sounds.json 에서 확인했고, 이 저장소의 어느 카드도 쓰지 않는다). 폭발음을 쓰면
	 * 「무언가 터졌다」로 읽혀 이미 부순 줄 안다.
	 */
	private static void plant(ServerLevel end, Slot slot) {
		slot.planted = true;
		DragonLastStandLights.drop(slot.line);
		slot.line = null;

		Shard shard = new Shard(end, FUSE_TICKS);
		// 판마다 같은 방향으로 서면 여섯이 한 덩어리로 보인다. 아이템은 갑옷 거치대의 몸
		// 방향을 따르므로 이 한 줄이 곧 「저마다 다른 각도로 뜬 크리스탈」이다.
		shard.snapTo(slot.seat.x, slot.seat.y, slot.seat.z,
				end.getRandom().nextFloat() * 360.0F, 0.0F);
		if (!end.addFreshEntity(shard)) {
			return;
		}
		slot.shard = shard;
		// 배경과 채움이 같은 「가득일 때 길이」를 받는다. 그래야 줄어드는 동안 왼쪽 끝이 맞는다.
		Vec3 barAt = slot.seat.add(0.0, BAR_LIFT, 0.0);
		slot.barBack = DragonLastStandLights.raiseBarBack(end, barAt, DyeColor.BLACK, BAR_WIDTH,
				FUSE_TICKS);
		slot.barFill = DragonLastStandLights.raiseBarFill(end, barAt, DyeColor.WHITE, BAR_WIDTH,
				FUSE_TICKS);
		end.sendParticles(ParticleTypes.EXPLOSION, true, false,
				slot.seat.x, slot.seat.y + 1.0, slot.seat.z, 1, 0.0, 0.0, 0.0, 0.0);
		end.sendParticles(ParticleTypes.END_ROD, true, false,
				slot.seat.x, slot.seat.y + 1.0, slot.seat.z, 40, 0.5, 0.5, 0.5, 0.15);
		end.playSound(null, slot.seat.x, slot.seat.y, slot.seat.z, SoundEvents.ANVIL_LAND,
				SoundSource.HOSTILE, 1.0F, 1.4F);
	}

	/**
	 * 타이머 바를 고쳐 세운다.
	 *
	 * <p>배경은 그대로 두고 <b>채움만</b> 줄인다. 배경과 같은 「가득일 때 길이」를 넘기므로 왼쪽
	 * 끝이 정확히 맞는다({@code DragonLastStandLights.barShape}).
	 */
	private static void bars(ServerLevel end, Wave current, int step) {
		if (step % BAR_STRIDE_TICKS != 0) {
			return;
		}
		double width = BAR_WIDTH * remainingRatio(step, current.countdownAt);
		for (Slot slot : current.slots) {
			if (!slot.standing()) {
				// 부서진 자리다. 바가 남아 있으면 「아직 남았다」로 읽힌다.
				DragonLastStandLights.drop(slot.barBack);
				DragonLastStandLights.drop(slot.barFill);
				slot.barBack = null;
				slot.barFill = null;
				continue;
			}
			DragonLastStandLights.reshapeBarFill(end, slot.barFill, BAR_WIDTH, width);
		}
	}

	// ------------------------------------------------------------------ 파도를 닫는다

	/**
	 * 파도를 닫는다. 남은 것마다 드래곤을 회복시킨다.
	 *
	 * <p>⚠ <b>회복은 여기 한 곳에서만</b> 일어난다. {@link #clearState()} 는 주지 않는다 —
	 * 전투가 끝나는 자리에서 얹으면 죽는 드래곤이 되살아난다.
	 *
	 * @param cleared 다 부숴서 끝났는가
	 */
	private static void finish(ServerLevel end, EnderDragon dragon, List<ServerPlayer> members,
			Wave current, boolean cleared) {
		int missed = 0;
		for (Slot slot : current.slots) {
			if (slot.standing()) {
				missed++;
			}
			drop(slot);
		}
		wave = null;
		if (cleared || missed <= 0) {
			// block/amethyst/shimmer 다. 부수는 소리와 같은 계열이라 「이 물건이 끝났다」로 들린다.
			TrialWarning.playEach(end, members, SoundEvents.AMETHYST_BLOCK_CHIME, 1.0F, 1.2F);
			SharedFateMod.LOGGER.info("[END] 오브젝트 파도 — 전부 부쉈습니다(체력 {}% 파도)",
					Math.round(THRESHOLDS[current.threshold] * 100.0F));
			return;
		}
		float max = dragon.getMaxHealth();
		float healed = max * MISS_HEAL_FRACTION * missed;
		if (max > 0.0F && dragon.isAlive() && healed > 0.0F) {
			dragon.heal(healed);
		}
		Vec3 at = dragon.isAlive() ? dragon.position() : Vec3.ZERO;
		end.sendParticles(ParticleTypes.HEART, true, false, at.x, at.y + 3.0, at.z, 24,
				3.0, 1.5, 3.0, 0.05);
		TrialWarning.playEach(end, members, SoundEvents.BEACON_DEACTIVATE, 1.0F, 0.7F);
		TrialWarning.playEach(end, members, SoundEvents.ENDER_DRAGON_GROWL, 1.0F, 0.6F);
		SharedFateMod.LOGGER.info("[END] 오브젝트 파도 — {}개를 못 부쉈습니다 · 드래곤 체력 +{}",
				missed, healed);
	}

	/** 자리 하나가 들고 있던 개체를 전부 거둔다. */
	private static void drop(Slot slot) {
		DragonLastStandLights.drop(slot.line);
		DragonLastStandLights.drop(slot.barBack);
		DragonLastStandLights.drop(slot.barFill);
		slot.line = null;
		slot.barBack = null;
		slot.barFill = null;
		if (slot.shard != null && !slot.shard.isRemoved()) {
			slot.shard.discard();
		}
	}

	// ------------------------------------------------------------------ 개체

	/**
	 * 오브젝트 하나. <b>투명한 갑옷 거치대에 엔드 크리스탈을 씌운 것</b>이다.
	 *
	 * <p>왜 이 종류인지는 클래스 설명의 「왜 갑옷 거치대인가」에 있다. 여기서 덮어쓰는 것은
	 * 넷이다.
	 *
	 * <ol>
	 *   <li>{@link #hurtServer} — <b>체력 30 을 우리가 센다.</b> 바닐라 규칙은 두 대에 부서지고
	 *       투명하면 아예 안 맞는다</li>
	 *   <li>{@link #interact} — <b>머리의 크리스탈을 못 빼 가게</b> 한다. 바닐라 갑옷 거치대는
	 *       우클릭으로 장비를 바꿔 끼울 수 있다</li>
	 *   <li>{@link #shouldBeSaved} — 저장하지 않는다</li>
	 *   <li>{@link #tick} — 스스로 타 없어지는 심지</li>
	 * </ol>
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
			setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.END_CRYSTAL));
			// 상자를 넓힌 것이 실제로 먹히게 한다. 생성자에서 한 번 부르지 않으면 개체 종류의
			// 기본 상자(0.5 × 1.975)가 그대로 남는다.
			refreshDimensions();
		}

		/**
		 * 맞는 상자. 보이는 것(머리 위 크리스탈)만큼으로 넓힌다.
		 *
		 * <p>근거는 {@link #HIT_WIDTH} 에 적어 두었다.
		 */
		@Override
		public EntityDimensions getDefaultDimensions(Pose pose) {
			return EntityDimensions.scalable(HIT_WIDTH, HIT_HEIGHT);
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
						getX(), getY() + 1.4, getZ(), 6, 0.3, 0.3, 0.3, 0.1);
				return true;
			}
			shatter(level);
			return true;
		}

		/** 부서진다. 폭발을 만들지 않는다 — 진짜 크리스탈을 버린 이유 가운데 하나가 그것이다. */
		private void shatter(ServerLevel level) {
			level.sendParticles(ParticleTypes.END_ROD, true, false,
					getX(), getY() + 1.4, getZ(), 60, 0.4, 0.6, 0.4, 0.25);
			level.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false,
					getX(), getY() + 1.4, getZ(), 40, 0.4, 0.6, 0.4, 0.12);
			// block/amethyst/break1~4 다. 유리 깨지는 소리(random/glass)보다 수정에 가깝다.
			level.playSound(null, getX(), getY(), getZ(), SoundEvents.AMETHYST_BLOCK_BREAK,
					SoundSource.HOSTILE, 1.2F, 0.9F);
			discard();
		}

		/**
		 * 머리의 크리스탈을 못 빼 가게 한다.
		 *
		 * <p>바닐라 갑옷 거치대는 손에 든 것과 장비를 <b>우클릭으로 바꿔 끼운다.</b> 막지 않으면
		 * 오브젝트가 모습을 잃고, 빼낸 크리스탈은 이 페이즈에서 <b>드래곤을 회복시키는 물건</b>이다.
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

		/** 심지를 한 칸 태운다. 지우는 줄을 아무도 못 지난 경우의 바닥이다. */
		@Override
		public void tick() {
			super.tick();
			if (--fuse <= 0) {
				discard();
			}
		}
	}
}
