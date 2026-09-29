package com.sharedfate.sync;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Enderman;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link TrialCatalog.Risk.NightHost} 실행기 — 밤의 군세.
 *
 * <h2>무엇을 해야 하는가</h2>
 *
 * <p><b>한 번만</b> 터진다. 팀원에게서 20칸({@code radius}) 안에 있는 엔더맨을 가까운 순으로
 * <b>다섯 마리까지</b>({@code maxMobs}) 골라 20초({@code hostileTicks}) 동안 적대로 만든다 —
 * 쳐다보지 않아도 달려온다. 20초가 지나면 원래대로 돌아간다.
 *
 * <h2>「전부」에서 「가까운 몇 마리」로 바뀌었다</h2>
 *
 * <p>처음에는 <b>엔드에 있는 엔더맨 전부</b>였다. 사람이 플레이해 보고 「전원은 너무 빡세다」고
 * 했다 — 엔드는 엔더맨이 끝없이 깔린 곳이라 <b>「전부」가 사실상 무한</b>이었고, 공유 체력 20
 * 짜리 팀에 바닐라 근접 7 짜리가 수십 마리 붙는 것은 대응할 수 있는 양이 아니다.
 *
 * <p>숫자는 <b>전부 카드 값에서 온다</b>({@code radius} · {@code maxMobs}). 20 이나 5 를 여기에
 * 적어 두면 사람이 다시 손볼 때 카드와 실행기가 갈라진다.
 *
 * <h2>⚠ 이 카드는 금지 목록에서 조건부로 풀려난 것이다</h2>
 *
 * <p>{@code docs/드래곤-시련-카드.md} 의 「쓰지 않습니다」에 <b>「엔더맨 어그로 — 통제 불가한
 * 피해원」</b>이 있(었)다. 이 카드가 허용된 조건은 <b>하나뿐</b>이다.
 *
 * <blockquote><b>지속 시간이 정해져 있고 되풀이되지 않는다.</b> 끝이 보이지 않는 어그로만
 * 금지다 — 20초는 버티면 끝나는 시간이라 팀에 대응할 수단이 있다.</blockquote>
 *
 * <p>그러므로 이 파일을 고치는 사람은 다섯을 반드시 지켜야 한다. 하나라도 어기면 이 카드는
 * 허용된 적이 없는 카드가 된다.
 *
 * <ul>
 *   <li><b>20초가 지나면 반드시 원래대로.</b> 한 마리라도 적대로 남으면 조건이 깨진다</li>
 *   <li><b>되풀이 금지.</b> 20초 뒤 다시 켜지는 코드를 넣지 말 것</li>
 *   <li><b>드래곤이 죽거나 전투가 끝나거나 판이 리셋되면 그 자리에서 푼다</b></li>
 *   <li><b>엔더맨을 새로 소환하지 않는다.</b> 이미 엔드에 있는 것을 적대로 만드는 카드이지
 *       수를 늘리는 카드가 아니다</li>
 *   <li><b>깨우는 수에 천장이 있다.</b> 사람이 카드를 되돌린 이유가 천장이 없다는 것 하나였다
 *       (아래 「전부에서 가까운 몇 마리로 바뀌었다」). 빈자리를 채우거나 도중에 더 거는 코드는
 *       천장을 없애는 코드다</li>
 * </ul>
 *
 * <h2>바닐라는 이 카드를 저절로 끝내 주지 않는다</h2>
 *
 * <p>「지속 시간을 걸어 두면 바닐라가 알아서 풀겠지」가 <b>틀렸다.</b> 26.3 의
 * {@code Enderman.aiStep} 은 매 틱 {@code updatePersistentAnger(level, true)} 를 부르고, 그
 * 둘째 인자가 <b>참</b>이라 {@code NeutralMob.updatePersistentAnger} 안에서
 *
 * <pre>if (getTarget() != null) { ... if (바뀌었거나 || 참) startPersistentAngerTimer(); }</pre>
 *
 * <p>가 <b>매 틱</b> 성립한다. {@code Enderman.startPersistentAngerTimer} 는 분노 시간을
 * 20~39초로 <b>새로 굴린다.</b> 즉 <b>표적을 들고 있는 동안에는 우리가 적어 넣은 만료 시각이
 * 매 틱 지워진다.</b> 같은 함수의 뒷부분에서 {@code stopBeingAngry} 로 가는 길도 그 참 인자에
 * 막혀 있다.
 *
 * <p>그래서 <b>푸는 것은 전적으로 우리 몫</b>이다. 만료 시각에 기대는 코드를 쓰면 「20초짜리
 * 카드」가 실제로는 「표적을 놓칠 때까지 + 최대 39초」가 되고, 그것이 금지 목록에 적힌
 * 「통제 불가」 바로 그것이다.
 *
 * <h2>「적대로 만든다」를 어떻게 했는가 — 둘을 함께 쓴다</h2>
 *
 * <p>바닐라 엔더맨은 {@code Enderman.isBeingStaredBy(Player)} 를 통과해야 적대가 된다. 그것은
 * <b>비공개</b> 메서드이고 플레이어의 시선 벡터로 <b>그 자리에서</b> 계산되므로, 「쳐다봤다」를
 * 만들어 두는 방법은 믹스인 없이는 없다({@link #tick} 아래 「믹스인」 문단). 그래서 시선 판정을
 * <b>거치지 않는</b> 두 길을 함께 쓴다.
 *
 * <ol>
 *   <li><b>{@code setTarget(플레이어)}</b> — 곧바로 달려오게 만드는 쪽이다.
 *       {@code MeleeAttackGoal} 은 {@code mob.getTarget()} 만 보므로 시선도, 시야선도, 목표
 *       고르기의 5틱 지연도 없이 그 틱부터 추격이 시작된다</li>
 *   <li><b>{@code setPersistentAngerTarget} + {@code setPersistentAngerEndTime}</b> —
 *       바닐라 AI 를 우리 편으로 만드는 쪽이다. {@code Enderman.registerGoals} 가 다는
 *       {@code EndermanLookForPlayerGoal} 의 선별자가
 *       {@code isBeingStaredBy(p) || isAngryAt(p, level)} 라, 분노 대상을 적어 두면 <b>쳐다보지
 *       않아도</b> 그 목표가 정상적으로 돌기 시작한다. 그 목표가 도는 덕분에 엔더맨이 16칸 밖의
 *       표적에게 <b>순간이동으로 접근</b>하는 바닐라 동작({@code teleportTowards})까지 그대로
 *       살아난다 — 이 카드가 무서운 이유의 절반이 그것이다</li>
 * </ol>
 *
 * <p>둘 중 하나만 쓰면 이렇게 샌다. {@code setTarget} 만 쓰면 바닐라가 알아서 분노 대상을 써
 * 넣으며 20~39초짜리 시간을 붙여 버리고(위 문단), 분노만 쓰면 목표가 <b>시야선</b>을 요구해
 * ({@code TargetingConditions.forCombat()} 의 기본값) 기둥 뒤의 엔더맨이 한참 늦게 온다.
 *
 * <h2>만료 시각을 매 틱 다시 못 박는 이유</h2>
 *
 * <p>바닐라가 매 틱 지우는 값을 왜 매 틱 다시 쓰는가. 우리 진입점이
 * {@code ServerTickEvents.END_SERVER_TICK} 라 <b>엔티티 틱보다 뒤에</b> 돌기 때문이다. 그래서
 * 틱 경계에 남아 있는 값은 언제나 <b>우리 것</b>이고, 그 틱에 청크가 내려가 엔더맨이 저장되면
 * 저장된 {@code anger_end_time} 도 우리 것이다.
 *
 * <p>이것이 <b>우리가 다시 찾을 수 없는 엔더맨</b>에 대한 유일한 보장이다. 창이 닫힐 때 우리는
 * 손에 든 명단만 풀 수 있는데, 도중에 언로드된 놈은 그 자리에 없다. 그놈이 나중에 다시 올라오면
 * 표적은 저장되지 않으므로 {@code getTarget()} 이 비어 있고, 만료된 분노 + 빈 표적이면
 * {@code updatePersistentAnger} 가 {@code stopBeingAngry} 로 간다 — <b>바닐라가 대신 풀어
 * 준다.</b> 못 박지 않았다면 그때 남아 있는 것은 바닐라가 굴린 20~39초다.
 *
 * <h2>⚠ 명단을 딱 한 번 고르고 20초 내내 얼려 둔다</h2>
 *
 * <p>전에는 <b>매 틱 「지금 엔드에 있는 놈 전부」를 훑어</b> 걸었고, 그 근거가 「거는 조회와
 * 푸는 명단이 같은 곳에서 나오므로 어긋날 자리가 없다」였다. <b>그 전제가 깨졌다.</b> 이제
 * 조회가 사람의 자리에 달려 있어서, 사람이 걸어 다니면 범위 안의 엔더맨이 매 틱 바뀐다.
 *
 * <p>그래서 둘을 정했다. 둘 다 <b>「20초 뒤 반드시 전부 풀린다」</b>를 지키는 쪽으로 기울였다 —
 * 그것이 이 카드가 금지 목록에서 풀려난 유일한 근거이기 때문이다.
 *
 * <ul>
 *   <li><b>한 번 깨운 놈은 20초 내내 적대다.</b> 범위를 벗어나도 그 자리에서 풀지 않는다.
 *       {@code radius} 는 <b>고르는 조건</b>이지 <b>유지 조건</b>이 아니다. 벗어나면 푼다고
 *       하면 (ㄱ) 엔더맨이 표적에게 <b>순간이동으로 붙는</b> 바닐라 동작 때문에 거리가 매 틱
 *       요동쳐 적대가 깜빡이고, (ㄴ) 무엇보다 사람이 뛰면 그 자리에서 다 풀려 <b>도망이 곧
 *       해제</b>가 된다 — 20초를 버티는 카드가 아니게 된다</li>
 *   <li><b>다섯 자리가 비어도 채우지 않는다.</b> 죽거나 사라져도 새로 뽑지 않는다. 채우기
 *       시작하면 20초 동안 실제로 상대한 마리 수에 <b>천장이 없어지고</b>, 사람이 고친 것이
 *       바로 그 「사실상 무한」이다. {@code maxMobs} 는 <b>한 번에 다섯</b>이 아니라 <b>이
 *       카드가 통틀어 다섯</b>이다</li>
 * </ul>
 *
 * <p>다만 <b>한 마리도 못 고른 동안</b>에는 매 틱 다시 고른다({@link #tick} 의 {@code waking}).
 * 그 틱에는 카드가 아직 터진 것이 아니라서다 — 팀이 마침 엔더맨 없는 자리에 서 있었다는 이유로
 * 카드가 통째로 불발되면 안 된다. 첫 한 마리를 깨우는 순간 명단이 얼어붙는다.
 *
 * <h2>얼린 명단으로도 「다시 찾기」 문제가 생기지 않는다</h2>
 *
 * <p>명단이 드는 것은 <b>좌표도 번호도 아니고 엔티티 객체</b>다. 엔더맨이 순간이동해도 같은
 * 객체이고, 청크가 내려가도 우리 손에 남는다. 푸는 일은 그 객체의 필드 몇 개를 쓰는 것뿐이라
 * 월드를 다시 뒤질 일이 없다 — <b>「걸었는데 못 푸는」 엔더맨이 생길 수 없다</b>는 성질은
 * 명단을 얼리기 전과 똑같이 성립한다.
 *
 * <p>도중에 새로 스폰된 엔더맨은 <b>이제 깨우지 않는다.</b> 전에는 「엔드의 엔더맨 전원」을
 * 20초 동안 차원의 성질로 읽어 늦게 온 놈도 걸었지만, 카드가 「가까운 다섯」이 된 이상 늦게
 * 온 놈을 더 거는 것은 천장을 없애는 일이다.
 *
 * <h2>이미 화가 나 있던 놈도 조건 없이 푼다</h2>
 *
 * <p>창이 닫힐 때 명단의 <b>전부</b>에 {@code stopBeingAngry} 를 건다. 「원래 화나 있었나?」를
 * 기억하지 않는 것이 일부러다 — {@link TrialCrystalGuard#clearState()} 가 화살 면역 깃발에서
 * 내린 것과 같은 판단이다. 기억하는 순간 그 기억이 어긋날 길이 생기고, 여기서 어긋나면 남는
 * 것은 <b>끝나지 않는 어그로</b>라 카드의 허용 조건 자체가 깨진다.
 *
 * <p>대가는 <b>사람이 정당하게 얻은 어그로도 함께 지워진다</b>는 것이다(쳐다봤거나 때렸거나).
 * 지워진 뒤에 다시 쳐다보거나 때리면 바닐라가 곧바로 되살리므로 잃는 것은 그 순간뿐이고,
 * 반대 방향의 실수 — 한 마리를 못 풀고 남기는 것 — 는 되돌릴 방법이 없다.
 *
 * <h2>피해도 넉백도 우리가 주지 않는다</h2>
 *
 * <p>엔더맨의 공격은 바닐라 그대로다. 26.3 {@code Enderman.createAttributes} 의
 * {@code ATTACK_DAMAGE} 는 <b>7.0</b> 이고, 우리는 그 값을 건드리지 않는다. 그래서
 * {@code TrialRisks.worstCaseTickDamage} 가 이 위험을 0 으로 센다 — <b>우리가 적은 피해가
 * 없다</b>는 뜻이지 <b>아프지 않다</b>는 뜻이 아니다. ⚠ 공유 체력이 20 인데 바닐라 근접
 * 공격에는 넉백이 붙어 있고 엔드 섬 가장자리는 허공이다. 이 카드가 그 길을 여는지는
 * {@code 세션-인계.md} 가 아니라 <b>사람이 판단할 문제</b>라 여기서 값을 깎지 않았다.
 *
 * <h2>드래곤은 건드리지 않는다</h2>
 *
 * <p>{@code dragon} 은 <b>읽기만</b> 한다({@link #fighting}). {@code setPhase} 도
 * {@code setTarget} 도 부르지 않는다.
 */
public final class TrialNightHost {

	// ------------------------------------------------------------------ 연출 값

	/**
	 * 깨어난 엔더맨 하나에 찍는 입자 수. 한 마리가 <b>패킷 한 장</b>이다.
	 *
	 * <p>{@code ANGRY_VILLAGER} 는 바닐라가 「이놈이 너에게 화났다」로 쓰는 유일한 아이콘이다.
	 * 보라색 포털 입자를 쓰지 않은 것이 일부러인데, 그쪽은 <b>엔더맨이 방금 순간이동해 왔다</b>는
	 * 뜻으로 이미 배워져 있어서 <b>엔더맨이 늘어난 것처럼 보인다.</b> 이 카드는 한 마리도
	 * 소환하지 않으므로 그렇게 보여서는 안 된다.
	 */
	static final int FLARE_PARTICLES = 6;
	/**
	 * 터지는 틱에 표시를 붙이는 엔더맨 수의 상한. 곧 그 틱에 나가는 <b>패킷 수</b>다.
	 *
	 * <p>이제는 명단이 {@code maxMobs} 에서 이미 잘려 오므로 <b>실제로는 여기에 안 걸린다</b>
	 * (지금 카드 값이 5 다). 그래도 남겨 둔 것은 이 상한이 <b>카드 값과 무관하게</b> 「몇 장이
	 * 나가는가」에 답을 주는 자리이기 때문이다 — 누가 {@code maxMobs} 를 크게 적어도 연출은
	 * 여기서 멈춘다. {@code TrialNightHostTest} 가 그 답을 숫자로 묻는다.
	 *
	 * <p>64 인 이유. 이 저장소가 쓰는 한 틱 예산은 400 점이고
	 * ({@link TrialEnderPulse#MAX_POINTS_PER_TICK}), <b>시련은 전투가 끝날 때까지 쌓이므로</b>
	 * 같은 틱에 남의 고리도 함께 그려진다. 이 카드는 <b>단 한 틱</b>만 그리므로 예산의 한 귀퉁이만
	 * 쓰는 것이 맞다.
	 *
	 * <p>상한에 걸리면 뒤쪽 엔더맨은 표시가 없다. 소리는 <b>사람마다</b> 나가므로 「무슨 일이
	 * 일어났다」는 상한과 무관하게 전해진다.
	 */
	static final int FLARE_MAX_MOBS = 64;

	// ------------------------------------------------------------------ 상태

	/**
	 * 지금 적대인 밤들. 값은 <b>우리가 손댄 엔더맨 명단</b>이다.
	 *
	 * <p>칸에 들어 있다는 것 자체가 「창이 아직 안 닫혔다」다({@link TrialEndRain} 가 같은
	 * 모양을 쓴다). 그래서 푸는 일이 <b>정확히 한 번</b> 일어나고, 「끝났다」를 세는 숫자를 따로
	 * 들지 않는다.
	 *
	 * <p><b>월드가 아니라 엔티티를 든다.</b> {@link #clearState()} 는 {@code ServerLevel} 을
	 * 받지 않는데, 그 자리에서 조회를 하려면 월드를 붙들고 있어야 하고 그 호출은 서버가 이미
	 * 내려간 뒤({@code SERVER_STOPPED})에도 온다. 엔티티만 들고 있으면 푸는 일이 필드 몇 개를
	 * 쓰는 것뿐이라 언제 불려도 안전하다.
	 *
	 * <p>{@code Entity.equals} 는 엔티티 번호로 같음을 보므로 집합이 그대로 중복을 걸러 준다.
	 * 순간이동해도 같은 객체라 <b>「그놈을 다시 찾기」 문제가 생기지 않는다.</b>
	 *
	 * <p>{@link LinkedHashSet} 인 것은 <b>고른 순서(가까운 순)를 잃지 않기 위해서</b>다.
	 * {@link #flare} 가 상한에 걸려 자를 때 잘려 나가는 쪽이 먼 놈이어야 하고, 무엇보다 명단을
	 * 눈으로 따라갈 때 순서가 뒤섞이지 않는 편이 낫다.
	 *
	 * <p>정적 맵인 이유는 위험이 값(레코드)이라 상태를 들 수 없기 때문이다. 월드가 바뀌면
	 * 지난 판의 엔더맨을 붙들고 있으므로 {@link #clearState()} 로 반드시 비운다.
	 */
	private static final Map<Key, Set<Enderman>> NIGHTS = new HashMap<>();

	/**
	 * 밤 하나를 가리키는 열쇠.
	 *
	 * <p><b>열쇠에 주의.</b> {@link TrialFireball} 과 같이 「받은 틱 + 위험 값」으로 만든 내부
	 * 열쇠다(진입점이 주는 {@code key} 로 바꿀 수 있게 됐다 — 그쪽 설명을 볼 것). 팀 둘이 같은
	 * 카드를 동시에 들면 열쇠가 갈라져 각자
	 * 자기 창을 닫는데, 엔드의 엔더맨은 한 벌이라 <b>먼저 끝난 팀이 푼 것을 다음 팀이 다음 틱에
	 * 다시 건다.</b> 한 틱 풀렸다 걸리는 것뿐이라 두지만, 팀 둘이 동시에 드래곤을 잡는 판이
	 * 생기면 여기를 먼저 볼 것.
	 */
	private record Key(long granted, TrialCatalog.Risk.NightHost risk) {
	}

	private TrialNightHost() {
	}

	// ------------------------------------------------------------------ 진입점

	/**
	 * 매 틱.
	 *
	 * <p>진입점의 모양은 다른 실행기와 같다. 쓰지 않는 인자도 받는 것은 {@link TrialRisks} 의
	 * 분기가 갈래 없이 한 줄로 유지되게 하기 위해서다.
	 *
	 * <p>{@code now} 를 받아 쓰고 {@code level.getGameTime()} 을 부르지 않는다. 룰렛이 도는
	 * 동안 {@code TrialFreeze} 가 판을 멈추면 게임 시각도 멈추는데, 그때 직접 물으면 창이
	 * 얼어붙은 채로 남는다. 분노 만료 시각도 {@code now} 에서 뽑는다({@link #calmAt}).
	 *
	 * <h2>믹스인은 필요 없다</h2>
	 *
	 * <p>「쳐다봤다」를 만들려면 {@code Enderman.isBeingStaredBy} 에 믹스인을 걸어야 하지만,
	 * 그쪽을 고르지 않았으므로 <b>이 카드는 믹스인을 한 장도 요구하지 않는다.</b> 공개 API
	 * 셋({@code setTarget}, {@code setPersistentAngerTarget},
	 * {@code setPersistentAngerEndTime})과 {@code NeutralMob.stopBeingAngry} 만 쓴다.
	 * 등록 파일을 건드릴 일이 없다는 뜻이다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱. 적대 시간은 월드 시간이 아니라 여기서부터 센다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.NightHost risk) {
		if (end == null || members == null || members.isEmpty() || risk == null
				|| !usable(risk)) {
			return;
		}

		Key nightKey = new Key(granted, risk);
		Set<Enderman> host = NIGHTS.get(nightKey);
		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);

		if (!fighting(dragon) || !hostile(elapsed, risk.hostileTicks())) {
			// 창이 닫혔다. 여기가 「반드시 풀린다」를 지키는 자리이고, 칸을 지우므로 딱 한 번만
			// 지난다 — 다음 틱부터는 host 가 없어 아무 일도 하지 않는다.
			if (host != null) {
				NIGHTS.remove(nightKey);
				// 명단이 비는 것은 calm 이 하는 일이므로 「알릴 것이 있었는가」를 먼저 묻는다.
				boolean woke = !host.isEmpty();
				calm(host);
				if (woke) {
					signalCalm(end, members);
				}
			}
			return;
		}

		if (host == null) {
			host = new LinkedHashSet<>();
			NIGHTS.put(nightKey, host);
		}
		// 터지는 첫 순간인가. 「명단이 비어 있다」로 묻는 것은 엔더맨이 한 마리도 없는 판에서
		// 아무도 안 달려오는데 비명만 울리는 것을 막기 위해서다 — 첫 한 마리를 실제로 깨우는
		// 틱에 울린다. 명단이 얼어붙는 자리도 여기다: 비어 있는 동안만 다시 고른다.
		boolean waking = host.isEmpty();
		if (waking) {
			wake(end, members, host, risk);
		}
		hold(members, host, calmAt(now, elapsed, risk.hostileTicks()));
		if (waking && !host.isEmpty()) {
			announce(end, members, host);
		}
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p><b>적대를 반드시 여기서 되돌린다.</b> 20초가 지나기 전에 판이 끝나면 되돌릴 기회가
	 * 여기뿐이고, 놓치면 다음 판의 엔더맨이 이유 없이 달려든다 — 컴파일도 시험도 조용한 사고다.
	 *
	 * <p>월드를 만지지 않는다. 명단에 든 엔티티에 {@code stopBeingAngry} 를 걸 뿐이라 서버가
	 * 이미 내려간 뒤에 불려도 터질 것이 없다 — 그 호출이 실제로 {@code SERVER_STOPPED} 에서
	 * 온다({@code SharedFateMod} 의 {@code DragonTrialManager.clearState}).
	 */
	public static void clearState() {
		for (Set<Enderman> host : NIGHTS.values()) {
			calm(host);
		}
		NIGHTS.clear();
	}

	// ------------------------------------------------------------------ 적대로 만들기

	/**
	 * 명단을 <b>딱 한 번</b> 고른다. 새로 소환하지 않는다.
	 *
	 * <p>고르는 조건이 둘이다 — <b>팀원 아무에게서 {@code radius} 안</b>이고,
	 * <b>가까운 순으로 {@code maxMobs} 마리까지</b>. 거리는 <b>노릴 수 있는 사람</b>까지만 잰다
	 * ({@link #huntable}). 관전자 옆에 선 엔더맨을 「가깝다」고 뽑아 봐야 바닐라가 그 틱에
	 * 분노를 지운다.
	 *
	 * <p>차원 전체를 훑는다. {@code ServerLevel} 에 상자로 좁히는 조회
	 * ({@code getEntities(EntityTypeTest, AABB, Predicate)})가 26.3 에 <b>없고</b>,
	 * {@code getEntitiesOfClass} 로 사람마다 상자를 치면 겹치는 자리의 엔더맨이 두 번 나와
	 * 합치는 일이 새로 생긴다. 어차피 <b>명단이 빌 동안에만</b> 도는 조회라 보통 한 판에 한 번
	 * 뿐이다 — 전에는 이것을 20초 내내 매 틱 돌렸다.
	 *
	 * <p>가까운 순으로 자르는 것이 사람 말의 「20블럭 + 5마리 최대」다. 아무 다섯이나 고르면
	 * 20칸 끝의 놈이 뽑히고 발밑의 놈이 빠지는 판이 생겨, <b>「가까이 있는 것이 깨어난다」</b>가
	 * 화면에서 성립하지 않는다.
	 */
	private static void wake(ServerLevel end, List<ServerPlayer> members, Set<Enderman> host,
			TrialCatalog.Risk.NightHost risk) {
		List<Nearby> candidates = new ArrayList<>();
		for (Enderman enderman : end.getEntities(EntityTypes.ENDERMAN, Enderman::isAlive)) {
			double distance = nearestSqr(members, enderman);
			// 시험이 보는 것과 같은 함수로 자른다. 여기에 부등식을 다시 적으면 둘이 갈라진다.
			if (inReach(distance, risk.radius())) {
				candidates.add(new Nearby(enderman, distance));
			}
		}
		candidates.sort(Comparator.comparingDouble(Nearby::distanceSqr));
		int wanted = wakeCount(candidates.size(), risk.maxMobs());
		for (int index = 0; index < wanted; index++) {
			host.add(candidates.get(index).mob());
		}
	}

	/**
	 * 얼린 명단의 적대를 이번 틱에도 붙들어 둔다.
	 *
	 * <p>표적을 <b>이미 팀원을 노리고 있으면 그대로 둔다.</b> 매 틱 가장 가까운 사람으로 다시
	 * 찍으면 두 사람 사이에서 표적이 깜빡이고, 그때마다
	 * {@code Enderman.setTarget} 이 이동 속도 보정을 떼었다 붙였다 하며 추격이 끊긴다. 우리가
	 * 개입하는 것은 <b>표적이 없거나 팀 밖을 보고 있을 때</b>뿐이고, 그동안은 바닐라
	 * {@code EndermanLookForPlayerGoal} 이 추격과 순간이동 접근을 맡는다.
	 *
	 * <p>분노 대상과 만료 시각은 <b>매 틱</b> 다시 쓴다. 바닐라가 매 틱 지우기 때문이고, 그
	 * 까닭과 이득은 클래스 설명의 「만료 시각을 매 틱 다시 못 박는 이유」에 있다.
	 *
	 * <p>죽었거나 노릴 사람이 없는 놈은 건너뛸 뿐 <b>명단에서 빼지 않는다.</b> 빼면 그놈에게
	 * {@link #calm} 이 안 가고, 「죽은 줄 알았는데 살아 있었다」 한 번이면 적대가 남는다 —
	 * 명단은 <b>손댄 적이 있는 놈 전부</b>의 목록이어야 한다.
	 *
	 * @param calmAt 우리가 푸는 바로 그 틱. 여기까지가 분노의 수명이다
	 */
	private static void hold(List<ServerPlayer> members, Set<Enderman> host, long calmAt) {
		for (Enderman enderman : host) {
			if (!enderman.isAlive()) {
				continue;
			}
			ServerPlayer target = hunted(members, enderman);
			if (target == null) {
				// 노릴 사람이 아무도 없다(전부 관전이거나 죽어 있다). 아무나 찍으면 바닐라가
				// 곧바로 무르므로 이번 틱은 건너뛴다.
				continue;
			}
			if (enderman.getTarget() != target) {
				// 시선 판정을 거치지 않는 길이 이 한 줄이다. MeleeAttackGoal 은 표적만 보므로
				// 쳐다보지 않아도 그 틱부터 달려온다.
				enderman.setTarget(target);
			}
			// 분노 대상을 적어 두면 EndermanLookForPlayerGoal 의 선별자가 통과해 바닐라 추격
			// 목표가 정상적으로 돈다 — 16칸 밖에서 순간이동으로 붙는 동작이 여기서 나온다.
			enderman.setPersistentAngerTarget(EntityReference.<LivingEntity>of(target));
			enderman.setPersistentAngerEndTime(calmAt);
		}
	}

	/** 고를 때 쓰는 한 쌍. 거리를 함께 들어 두어야 정렬하며 매번 다시 재지 않는다. */
	private record Nearby(Enderman mob, double distanceSqr) {
	}

	/**
	 * 노릴 수 있는 팀원 중 가장 가까운 사람까지의 거리(제곱).
	 *
	 * <p>노릴 사람이 하나도 없으면 {@link Double#MAX_VALUE} 라 어떤 {@code radius} 로도 안
	 * 걸린다 — 전부 관전인 팀 옆에서 엔더맨이 깨어나는 일이 없다.
	 */
	private static double nearestSqr(List<ServerPlayer> members, Enderman enderman) {
		double best = Double.MAX_VALUE;
		for (ServerPlayer member : members) {
			if (!huntable(member)) {
				continue;
			}
			best = Math.min(best, enderman.distanceToSqr(member));
		}
		return best;
	}

	/**
	 * 이 엔더맨이 노릴 팀원. 노릴 사람이 없으면 {@code null}.
	 *
	 * <p>이미 팀원을 보고 있으면 그 사람을 그대로 돌려준다 — 바꾸지 않는 것이 규칙이다.
	 *
	 * <p>관전자와 크리에이티브는 뺀다. 바닐라 {@code NeutralMob.isValidPlayerTarget} 이 같은
	 * 둘을 빼고, 거기에 걸리면 {@code updatePersistentAnger} 가 우리가 적은 분노를 그 틱에
	 * 지워 버린다 — 넣어 봐야 한 틱도 못 간다.
	 */
	private static @Nullable ServerPlayer hunted(List<ServerPlayer> members, Enderman enderman) {
		if (enderman.getTarget() instanceof ServerPlayer current && huntable(current)
				&& members.contains(current)) {
			return current;
		}
		ServerPlayer closest = null;
		double best = Double.MAX_VALUE;
		for (ServerPlayer member : members) {
			if (!huntable(member)) {
				continue;
			}
			double distance = enderman.distanceToSqr(member);
			if (distance < best) {
				best = distance;
				closest = member;
			}
		}
		return closest;
	}

	/** 바닐라가 분노 대상으로 인정하는 사람인가. */
	private static boolean huntable(ServerPlayer member) {
		return member.isAlive() && !member.isSpectator() && !member.isCreative();
	}

	// ------------------------------------------------------------------ 되돌리기

	/**
	 * 명단을 통째로 푼다. <b>조건을 달지 말 것.</b>
	 *
	 * <p>{@code NeutralMob.stopBeingAngry} 하나가 네 가지를 한꺼번에 끈다 — 맞은 기억
	 * ({@code lastHurtByMob}), 분노 대상, 표적, 분노 만료 시각. 하나만 끄면 나머지에서 되살아
	 * 난다. 특히 <b>표적만 비우면</b> 분노 대상이 남아
	 * {@code EndermanLookForPlayerGoal} 이 다음 틱에 다시 물어 온다.
	 *
	 * <p>이미 사라진 엔티티에 걸어도 필드 몇 개를 쓰는 것뿐이라 터지지 않는다. 월드를 묻지
	 * 않으므로 청크가 내려갔는지 알 필요도 없다 — 「못 찾아서 예외가 나고 그 틱의 다른 시련까지
	 * 멈춘다」는 길이 이 함수에는 없다.
	 */
	private static void calm(Set<Enderman> host) {
		for (Enderman enderman : host) {
			enderman.stopBeingAngry();
		}
		host.clear();
	}

	// ------------------------------------------------------------------ 알리기

	/**
	 * 카드가 터졌다고 알린다.
	 *
	 * <p>자막은 쓰지 않는다 — 화면 아래 글자는 이 저장소에서 전부 걷어냈다
	 * ({@link TrialWarning#shout}). 그렇다고 엔더맨이 갑자기 달려오는 것만으로 두면 「무슨
	 * 일이지」가 되므로 소리와 입자 둘로 말한다.
	 *
	 * <p><b>{@link TrialWarning} 의 세 층을 쓰지 않는다.</b> 그쪽 소리는 「바닥 고리가 뜨고
	 * 곧 그 자리가 터진다」의 언어다. 이 카드에는 고리도 없고 비킬 자리도 없어서, 같은 소리를
	 * 내면 사람이 발밑을 보게 된다 — 봐야 하는 것은 사방이다.
	 *
	 * <p>소리는 <b>사람마다 그 자리에서, 그 사람에게만</b> 울린다
	 * ({@link TrialWarning#playEach}). 바닐라 소리 사거리는 볼륨 1 이하면 16칸인데 아레나 반경이
	 * 40 이라 한 점에서 울리면 흩어진 팀원에게 닿지 않고, 사람 자리마다 {@code level.playSound} 를
	 * 부르면 반경 안의 전원에게 나가 <b>모여 있을 때 사람 수만큼 겹친다.</b>
	 * {@code ENDERMAN_STARE} 를 고른 것은 그것이 사람이 <b>이미 배워 둔 「엔더맨이 나를
	 * 봤다」</b>이기 때문이다 — 새로 배울 것이 없고, 넷이 동시에 <b>한 번씩</b> 들으면 「전부
	 * 깨어났다」로 읽힌다.
	 */
	private static void announce(ServerLevel end, List<ServerPlayer> members, Set<Enderman> host) {
		TrialWarning.playEach(end, members, SoundEvents.ENDERMAN_STARE, 1.0F, 0.6F);
		flare(end, host);
	}

	/**
	 * 깨어난 놈들 머리 위에 성난 표시를 한 번 띄운다.
	 *
	 * <p>「어디에서 오는가」를 말하는 유일한 갈래다. 소리는 사람 자리에서 나므로 방향을 주지
	 * 못한다.
	 *
	 * <p><b>긴 형태</b>({@code overrideLimiter=true})로 보낸다. 짧은 형태는 서버에서 32칸에
	 * 잘리고 클라이언트가 한 번 더 거르는데({@link TrialWarning} 의 「거리 제한을 끄고
	 * 보낸다」), 엔더맨은 아레나 반경 40 어디에나 있고 기둥 위에도 선다 — 되돌리면 <b>가까운
	 * 한두 마리만 표시되고 나머지는 소리 없이 달려온다.</b>
	 *
	 * <p><b>깨운 명단만</b> 그린다. 전에는 여기서 「지금 엔드에 있는 엔더맨」을 한 번 더 조회해
	 * 그렸는데, 그때는 명단이 곧 그 조회 결과라 같은 말이었다. 이제는 다르다 — 그 조회로
	 * 되돌리면 <b>깨우지도 않은 놈 머리 위에 성난 표시가 뜬다.</b> 사람이 그것을 보고 비키거나
	 * 맞서면 표식이 거짓말한 것이 된다.
	 *
	 * <p>몇 장을 보낼지는 {@link #flarePackets} 에게 묻는다. 여기서 직접 세면 시험이 보는 값과
	 * 실제로 나가는 양이 갈라질 수 있고, 갈라지면 시험이 지키는 것이 아무것도 아니게 된다.
	 */
	private static void flare(ServerLevel end, Set<Enderman> host) {
		int packets = flarePackets(host.size());
		int drawn = 0;
		for (Enderman enderman : host) {
			if (drawn >= packets) {
				break;
			}
			end.sendParticles(ParticleTypes.ANGRY_VILLAGER, true, false,
					enderman.getX(), enderman.getEyeY() + 0.6, enderman.getZ(),
					FLARE_PARTICLES, 0.25, 0.1, 0.25, 0.0);
			drawn++;
		}
	}

	/**
	 * 창이 닫혔다고 알린다.
	 *
	 * <p>터진 것만 알리고 끝난 것을 안 알리면 사람은 <b>언제까지 버텨야 하는지</b>를 모른 채
	 * 20초를 보낸다. 이 카드가 금지 목록에서 풀려난 근거가 「버티면 끝난다」인데, 끝난 줄을
	 * 모르면 그 근거가 화면에서는 성립하지 않는다.
	 *
	 * <p>깨울 때와 <b>다른 소리</b>여야 한다. {@code ENDERMAN_AMBIENT} 는 바닐라가 평온한
	 * 엔더맨에게 내는 웅얼거림이라 「돌아갔다」가 그대로 읽힌다. 조용히, 한 번만 낸다.
	 */
	private static void signalCalm(ServerLevel end, List<ServerPlayer> members) {
		TrialWarning.playEach(end, members, SoundEvents.ENDERMAN_AMBIENT, 0.7F, 1.2F);
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 값이 이 카드를 돌릴 수 있는 모양인가.
	 *
	 * <p>셋 중 하나라도 0 이하면 <b>아무 일도 하지 않는다.</b> 「범위 0」이나 「0마리」를 「제한
	 * 없음」으로 읽지 않는 것이 일부러다 — 그렇게 읽으면 값을 잘못 적은 판에서 되살아나는 것이
	 * 하필 <b>옛 「엔드의 엔더맨 전부」</b>이고, 그것이 사람이 없앤 바로 그 카드다.
	 */
	static boolean usable(TrialCatalog.Risk.NightHost risk) {
		return risk.hostileTicks() > 0 && risk.radius() > 0.0 && risk.maxMobs() > 0;
	}

	/**
	 * 범위 안에 {@code candidates} 마리가 있을 때 실제로 깨우는 수.
	 *
	 * <p>「최대 몇 마리인가」를 <b>숫자로 물을 수 있는</b> 유일한 자리다. 엔드의 엔더맨 수는
	 * 우리가 정하지 않으므로, 여기가 없으면 「사람이 정한 다섯이 지켜지는가」에 답이 없다.
	 */
	static int wakeCount(int candidates, int maxMobs) {
		if (candidates <= 0 || maxMobs <= 0) {
			return 0;
		}
		return Math.min(candidates, maxMobs);
	}

	/**
	 * 이 거리(제곱)가 깨우는 범위 안인가.
	 *
	 * <p>제곱으로 재는 것은 제곱근을 피하려는 것뿐이고, 경계({@code 거리 == radius})는
	 * <b>안</b>으로 본다 — 바닥 고리의 {@code TrialRisks.insideMark} 와 같은 쪽이다.
	 *
	 * <p>{@code radius} 가 0 이하면 언제나 거짓이다. {@link #usable} 이 이미 막지만, 「범위
	 * 없음 = 전부」로 새는 길을 한 군데도 남기지 않는다.
	 */
	static boolean inReach(double distanceSqr, double radius) {
		return radius > 0.0 && distanceSqr <= radius * radius;
	}

	/**
	 * 아직 전투가 서 있는가.
	 *
	 * <p>드래곤을 <b>읽기만</b> 한다. 거짓이면 그 자리에서 적대를 푼다 — 카드의 허용 조건이
	 * 「드래곤이 죽으면 그 자리에서 푼다」를 포함하기 때문이다.
	 *
	 * <p>⚠ <b>예전에는 이 갈래가 잡아 주지 못했다.</b> {@code DragonTrialManager.tickSessions}
	 * 가 {@code dragon == null || !dragon.isAlive()} 인 틱에 세션을 닫고 {@code TrialRisks.tick}
	 * 을 부르지 않았으며, 그 길에서 {@code TrialRisks.clearState()} 도 부르지 않았다. 즉 20초가
	 * 지나기 전에 드래곤이 죽으면 이 파일은 푸는 틱을 한 번도 못 받았고, 엔더맨은 <b>영영
	 * 적대</b>로 남았다(26.3 바닐라는 풀어 주지 않는다).
	 *
	 * <p>✅ <b>분배기가 고쳐졌다.</b> 이제 마지막 세션이 닫히는 틱에
	 * {@code DragonTrialManager.endTrials} 가 {@code TrialRisks.clearState()} 를 부르고, 그
	 * 길에서 {@link #clearState()} 가 명단 전부의 분노를 푼다. 이 검사는 그대로 둔다 —
	 * 드래곤이 죽는 것을 <b>틱 안에서</b> 잡는 유일한 갈래이고, 분배기가 언젠가 죽어 가는 틱을
	 * 넘겨주게 되면 그때는 이쪽이 한 틱 먼저 푼다.
	 */
	static boolean fighting(@Nullable EnderDragon dragon) {
		return dragon != null && dragon.isAlive();
	}

	/**
	 * 지금이 적대인 시간인가.
	 *
	 * <p>「한 번만 터진다」를 <b>이 부등식 하나로</b> 기억한다. 센 횟수도, 「끝났다」 깃발도 두지
	 * 않는다 — {@link TrialEndRain#raining} 이 같은 자리에서 같은 답을 쓴다. 값에서 나오므로
	 * 몇 번을 물어도 같고, 비워 주기를 기다리는 깃발이 없으니 배선 한 줄을 빠뜨려
	 * 「어느 판에서만 두 번 온다」가 되는 길도 없다.
	 *
	 * <p>받은 바로 그 틱({@code elapsed == 0})은 아니다. {@code elapsedSinceGrant} 가 복원
	 * 직후의 음수 위상을 0 으로 깎으므로, 0 을 적대로 보면 <b>되감긴 판에서 카드가 다시
	 * 터진다</b> — 그것이 곧 「되풀이」이고 이 카드에 금지된 바로 그것이다.
	 *
	 * <p>마지막 틱({@code elapsed == hostileTicks})은 포함한다. 그래서 적대인 틱이 정확히
	 * {@code hostileTicks} 개다.
	 */
	static boolean hostile(long elapsed, int hostileTicks) {
		return elapsed > 0L && elapsed <= hostileTicks;
	}

	/**
	 * 엔더맨에게 적어 넣을 분노 만료 시각. <b>우리가 푸는 바로 그 틱</b>이다.
	 *
	 * <p>{@code granted + hostileTicks + 1} 과 같은 값이지만 {@code now} 에서 뽑는다. 세션의
	 * {@code granted} 와 월드의 게임 시각이 서로 다른 곳에서 오므로, 실제로 엔더맨이 비교하게 될
	 * 시계({@code Level.getGameTime}, 곧 분배기가 넘겨준 {@code now})에서 재야 어긋나지 않는다.
	 *
	 * <p>{@code +1} 이 있는 이유. {@code NeutralMob.isAngry} 가
	 * {@code 만료 시각 - 게임 시각 > 0} 이라 <b>같은 값이면 이미 분노가 아니다.</b> 우리가 푸는
	 * 틱을 그대로 적으면 마지막 한 틱이 창 안인데도 바닐라 기준으로 식어 있게 된다. 못 박은 값이
	 * 우리 창보다 <b>먼저</b> 끝나는 일은 없어야 한다.
	 */
	static long calmAt(long now, long elapsed, int hostileTicks) {
		return now + Math.max(0L, hostileTicks - elapsed) + 1L;
	}

	/**
	 * 엔더맨 {@code count} 마리가 있는 판에서 터지는 틱에 실제로 나가는 <b>패킷 수</b>.
	 *
	 * <p>예산이 지켜지는지를 <b>숫자로 물을 수 있는</b> 유일한 값이다. 엔더맨 수는 우리가 정하지
	 * 않으므로, 상한이 없으면 「몇 장이 나가는가」에 답이 없다.
	 */
	static int flarePackets(int count) {
		return Math.min(FLARE_MAX_MOBS, Math.max(0, count));
	}
}
