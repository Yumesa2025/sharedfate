package com.sharedfate.sync;

import com.sharedfate.SharedFateMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.level.dimension.end.EnderDragonFight;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link TrialCatalog.Risk.CrystalRevive} 실행기 — 크리스탈이 되살아난다.
 *
 * <h2>이 카드는 한 번 터지는 것이 아니라 「연출 → 부활」 한 짝이다</h2>
 *
 * <p>다른 카드는 주기마다 반복하지만 이것은 <b>카드당 한 번</b>이다. 카드가 쌓여 있는 내내
 * 크리스탈이 계속 되살아나면 전투가 끝나지 않는다 — 공유 체력에 전멸이 곧 월드 삭제인 게임에서
 * 「끝나지 않는 전투」는 난이도가 아니라 고장이다. 그래서 {@link #SPENT} 가 이미 쓴 카드를
 * 기억하고, 같은 카드는 두 번 발동하지 않는다.
 *
 * <h2>크리스탈은 연출이 <b>시작될 때</b> 선다</h2>
 *
 * <p>처음 구현은 연출이 끝난 뒤에 크리스탈을 만들었다. 그러면 연출 동안 화면에 아무것도 없어
 * 「무엇이 복구되는 중인지」가 보이지 않는다. 지금은 <b>첫 틱에 크리스탈을 세우고</b> 연출이
 * 끝날 때까지 무적으로 둔다. 되살아나는 과정이 화면에 그대로 있고, 복구되는 중에 깨져 연출이
 * 무의미해지는 일도 없다.
 *
 * <p>대신 되살릴 자리는 <b>첫 틱에 정해진다.</b> 예전처럼 매 틱 다시 셀 수 없다 — 이미 물건이
 * 서 있는데 대상 목록이 바뀌면 화면과 코드가 어긋난다.
 *
 * <h2>부르는 곳이 둘이다 — 카드와 입장 연출</h2>
 *
 * <p>「부활」 카드({@code sharedfate:crystal_revival}) 말고 {@link TrialEntrance} 도 이 연출을
 * 그대로 돌린다. 사람이 입장 연출을 정할 때 <b>「이건 이미만든 크리스탈 부활로 연출하면
 * 될거같은데」</b>라고 했고, 그 말대로 여기를 다시 짜지 않고 불렀다.
 *
 * <p>⚠ 그래서 <b>{@link #SPENT} 의 열쇠가 두 자리에서 만들어진다.</b> 열쇠는 「받은 틱 + 위험
 * 값」이므로 둘이 같은 틱에 같은 값으로 시작하면 한 번으로 합쳐진다. 입장 연출은 그 길을 막으려고
 * {@code showTicks} 를 카드와 <b>일부러 다르게</b> 두었다({@code TrialEntrance.REVIVE_SHOW_TICKS}).
 * 여기를 고치는 사람은 그쪽도 함께 볼 것.
 *
 * <h2>무적은 「영구」가 아니라 「시간」이다 — 이 카드에서 가장 위험한 자리</h2>
 *
 * <p>영영 무적인 크리스탈이 하나라도 남으면 <b>전투가 끝나지 않는다.</b> 그래서 26.3 의 두 가지
 * 무적 중 스스로 풀리는 쪽만 쓴다.
 *
 * <ul>
 *   <li>{@code Entity.setPermanentlyInvulnerable} — {@code Invulnerable} 태그로 <b>저장된다.</b>
 *       연출 도중 서버가 죽으면 다시 떴을 때도 무적이고, 우리가 되돌릴 기회는 이미 없다.
 *       <b>쓰지 않는다.</b></li>
 *   <li>{@code Entity.setInvulnerableTime} — {@code Entity.commonTick} 이 매 틱 1씩 깎는다.
 *       {@code EndCrystal.tick} 이 {@code super.tick()} 을 부르지 않는데도 깎이는 것은,
 *       {@code ServerLevel} 이 개체의 {@code tick()} 과 <b>별도로</b> {@code commonTick()} 을
 *       부르기 때문이다. 저장은 되지만({@code invulnerable_time}) 세는 값이라 되살아나도 곧
 *       0 이 된다. <b>이것만 쓴다.</b></li>
 * </ul>
 *
 * <p>둘 다 {@code Entity.isInvulnerable} 이 읽고, {@code EndCrystal.hurtServer} 가 맨 처음
 * {@code isInvulnerableToBase} 로 그것을 본다. 크리에이티브와
 * {@code BYPASSES_INVULNERABILITY} 는 그대로 통과한다 — 운영자가 치울 길은 남겨 둔다.
 *
 * <h2>선은 파티클이 아니라 바닐라 부활 빔이다</h2>
 *
 * <p>앞선 구현은 먼지 파티클로 선을 그었는데 <b>아무에게도 보이지 않았다.</b>
 * {@code ServerLevel.sendParticles} 의 짧은 형태는 {@code overrideLimiter=false} 로 내려가고,
 * 그러면 서버가 <b>발생 지점에서 32 블록 안의 사람에게만</b> 패킷을 보낸다. 드래곤은 기둥 위
 * 30 블록, 사람은 바닥이라 선의 대부분이 32 블록 밖이다 — 패킷이 아예 나가지 않았다.
 *
 * <p>그래서 선은 {@code EndCrystal.setBeamTarget} 으로 긋는다. 개체 데이터라 거리 제한이 없고,
 * {@code EndCrystal.shouldRenderAtSqrDistance} 는 <b>빔이 걸려 있으면 거리와 무관하게 참</b>을
 * 돌려준다 — 즉 보이는 것이 보장된다. 예전에 이 길을 피한 이유는 「연출이 끝난 뒤 하늘의 빈 점을
 * 향한 선이 남는다」였는데, 이제 크리스탈을 우리가 만들고 우리가 지우므로 끝날 때
 * {@code setBeamTarget(null)} 로 걷으면 되는 문제다.
 *
 * <p>빔은 <b>크리스탈에서 드래곤 쪽으로</b> 자란다. 바닐라 빔은 언제나 크리스탈에서 출발하므로
 * 반대 방향으로 자라게 할 방법이 없다. 완성된 그림은 같고(드래곤과 크리스탈을 잇는 선), 닿는
 * 순간이 곧 부활이라 초읽기 노릇도 그대로 한다.
 *
 * <p>먼지 파티클은 같은 선 위에 겹쳐 덧칠만 한다. 이번에는 <b>긴 거리 깃발을 켜서</b> 보낸다.
 *
 * <h2>연출 동안 왜 무적 깃발로 드래곤을 지키지 않는가</h2>
 *
 * <p>이 카드의 대가는 「되살아난 크리스탈을 다시 깨야 한다」가 아니라 <b>그 동안 드래곤을 때릴
 * 수 없다</b>는 것이다. 그것을 무적 깃발로 구현하면 <b>때려도 아무 일이 없다</b> — 검이 지나가고
 * 화살이 박히는데 체력이 안 깎이는 것은 플레이어 눈에 언제나 버그다. 대신 드래곤을 <b>가장 높은
 * 기둥보다 더 위, 중앙 상공으로 끌어올린다.</b> 못 때리는 이유가 화면에 그대로 보이고, 그래도
 * 닿은 한 발은 정직하게 들어간다.
 *
 * <h2>위상(phase)을 바꾸지 않는다</h2>
 *
 * <p>{@code EnderDragonPhase.HOVERING} 으로 바꾸면 <b>우리가 되돌려 놓아야 한다.</b> 그런데
 * 연출 도중에 드래곤이 죽을 수도, 서버가 내려갈 수도, 팀이 전멸해 월드가 갈릴 수도 있다. 되돌릴
 * 기회를 한 번이라도 놓치면 드래곤이 공중에 선 채로 남아 전투가 영영 끝나지 않는다. 그래서
 * <b>매 틱 자리만 당겨 둔다.</b> 우리가 부르기를 멈추는 순간 바닐라 비행이 그대로 이어받으므로
 * 되돌릴 것이 아예 없다 — 어정쩡하게 남을 상태가 없는 것이 이 방식의 값어치다.
 *
 * <p>당기기가 바닐라 비행을 이긴다는 것은 바이트코드로 확인했다. {@code EnderDragon.aiStep} 의
 * 세로 조향은 {@code deltaY += clamp(…, -flySpeed, flySpeed) * 0.01} 에 감쇠 {@code 0.91} 이라
 * 한 틱에 1 블록도 못 움직인다. 우리는 남은 거리의 20%를 당긴다.
 *
 * <p>같은 이유로 서버가 연출 도중 내려가면 다시 떴을 때 {@code now - granted} 가 이미
 * {@code showTicks} 를 넘어 있어 곧바로 {@link Step#REVIVE} 로 들어간다. 연출을 못 본 것은
 * 손해지만, 그 한 틱이 <b>남은 무적과 빔을 걷어 내는 자리</b>이기도 하다.
 *
 * <h2>부활이 사람을 죽여서는 안 된다</h2>
 *
 * <p>크리스탈이 되살아나는 것은 <b>시간 손실</b>이지 피해가 아니다. 그런데 엔드 크리스탈은
 * 26.3 에서도 부서질 때 위력 6 의 폭발을 일으키고({@code EndCrystal.hurtServer}), 살아 있는
 * 동안 제 발밑 칸에 불을 놓는다({@code EndCrystal.tick} 이 드래곤 전투가 열려 있으면
 * {@code BaseFireBlock.getState} 를 깐다). 기둥 꼭대기에 서 있는 사람 발밑에 그것을 생성하면
 * 불에 타고, 나중에 그 크리스탈이 터질 때 허공으로 날아간다. 그래서 <b>사람이 서 있는 자리는
 * 건너뛴다.</b> 밀어내지 않는 이유는 기둥 아래가 허공이라 밀어내는 것이 곧 낙사이기 때문이다.
 *
 * <p>물건을 먼저 세우게 되었으니 한 가지가 늘었다 — <b>연출 도중에 그 자리로 올라오는 사람</b>.
 * 그때는 {@link #prune} 이 그 크리스탈을 <b>터뜨리지 않고 거둔다</b>
 * ({@code RemovalReason.DISCARDED}). 사람을 밀어내지 않겠다는 약속이 물건보다 앞이다.
 */
public final class TrialCrystalRevive {

	/** 자리에 그리는 고리의 반경. 이 안에 사람이 있으면 그 자리는 건너뛴다. */
	private static final double SEAT_RADIUS = 2.0;
	/** 자리 위아래로 이만큼까지 「그 자리에 있다」로 본다. 기둥 위에서 뛰어도 걸리게 넉넉히 잡는다. */
	private static final double SEAT_HEADROOM = 3.0;
	/** 이 거리 안에 크리스탈이 있으면 그 자리는 이미 차 있다. 자리와 개체 좌표는 정확히 같다. */
	private static final double SEAT_TAKEN_REACH = 2.0;

	/**
	 * 가장 높은 기둥 위로 드래곤을 얼마나 더 띄우는가.
	 *
	 * <p>활의 화살은 30 블록쯤 날아가면 눈에 띄게 떨어진다. 기둥 꼭대기에 올라선 사람 기준으로도
	 * 닿지 않을 만큼 띄우되, 화면 밖으로 사라져 「드래곤이 어디 갔지」가 되지는 않는 높이다.
	 */
	private static final double WATCH_CLEARANCE = 30.0;
	/** 매 틱 목표 자리로 당기는 비율. 1 이면 순간이동이라 연출이 아니라 사고처럼 보인다. */
	private static final double WATCH_PULL = 0.2;
	/** 이만큼 가까워지면 정확히 그 점에 붙인다. 붙이지 않으면 드래곤 비행과 줄다리기가 된다. */
	private static final double WATCH_SNAP = 1.0;

	/**
	 * 연출이 끊겨도 무적이 이만큼 뒤에는 반드시 풀린다.
	 *
	 * <p>매 틱 「남은 연출 + 이 여유」로 다시 건다. 우리가 한 번이라도 못 부르면 그 순간부터
	 * 이 값만큼만 더 버티고 스스로 0 이 된다 — <b>영영 무적인 크리스탈이 생길 수 없는 이유가
	 * 이 한 줄이다.</b> 너무 짧으면 서버가 한 틱 밀릴 때 연출 중에 깨지므로 두 초를 준다.
	 */
	static final int GUARD_MARGIN_TICKS = 40;

	/**
	 * 자리들을 <b>몇 틱에 나눠</b> 그리는가. 한 틱에 {@code ⌈자리 수 / 이 값⌉} 곳만 그린다.
	 *
	 * <h2>예전에는 4틱마다 열 곳을 한꺼번에 그렸다 — 그 틱이 예산을 혼자 넘겼다</h2>
	 *
	 * <p>자리 하나가 고리 {@value #RING_POINTS} + 빔 덧칠 {@value #BEAM_POINTS} 이라 열 곳이면
	 * <b>640점</b>이다. 이 판의 한 틱 예산은 400~440
	 * ({@code TrialEnderPulse.MAX_POINTS_PER_TICK} · {@code TrialLandingShock.MAX_POINTS_PER_TICK})
	 * 이고 한 카드 상한은 {@link DragonFireBarrage#MARK_MAX_POINTS} 다. 게다가 시련은 전투가 끝날
	 * 때까지 <b>쌓이므로</b> 그 틱에 남의 고리도 함께 나간다.
	 *
	 * <p>고친 방법은 「종말의 비」({@code TrialEndRain.MARK_MAX_STRIDE})와 「착지 충격」
	 * ({@code TrialLandingShock.MAX_STRIDE})이 쓰는 <b>나눠 그리기</b>다. 점을 하나도 지우지
	 * 않았다 — 자리마다 그리는 그림은 예전과 똑같고, 그 자리의 차례가 매 4틱이 아니라
	 * {@value #SEAT_STRIDE}틱에 한 번 돌아올 뿐이다. 한 바퀴에 쓰는 점의 합도 640 그대로다.
	 *
	 * <h2>⚠ 8보다 작아야 한다 — 먼지 수명에 묶인 값이다</h2>
	 *
	 * <p>나눠 그려도 열 자리가 <b>동시에</b> 보이는 것은 먼저 찍은 점이 아직 살아 있기 때문이다.
	 * 고리도 빔 덧칠도 {@link TrialWarning#dust} 의 먼지이고, 26.3 {@code DustParticleBase} 가
	 * 수명을 {@code max(1, (int)(8.0 / (굴림×0.8 + 0.2)) × 크기)} 로 잡는다 — 크기가 1.0 이라
	 * <b>최소 8틱</b>이다. 차례가 8틱 이상 만에 돌아오면 다시 그리기 전에 첫 점이 죽어
	 * <b>자리가 깜빡인다.</b> 5 는 그 하한에서 세 틱 아래라 항상 겹친다.
	 *
	 * <p>10 을 <b>나누어 떨어지는</b> 수인 것도 값어치다. 6 이면 어떤 틱은 두 곳, 어떤 틱은 한
	 * 곳이라 화면 밝기가 틱마다 뛴다. 5 면 매 틱 정확히 두 곳이다.
	 *
	 * <p>⚠ <b>고리나 덧칠을 다른 입자로 바꾸면 이 값의 근거가 함께 바뀐다.</b> 그때는
	 * 그 입자의 수명 하한을 먼저 확인할 것.
	 *
	 * <h2>이 연출은 카드보다 입장에서 더 자주 온다</h2>
	 *
	 * <p>{@link TrialEntrance} 가 이 연출을 그대로 부르므로 <b>엔드에 들어갈 때마다</b> 이 점이
	 * 나간다. 카드는 한 판에 뽑히지 않을 수도 있지만 입장은 언제나 있다 — 640점을 그냥 두면 안
	 * 되는 이유가 그쪽에 있었다. 입장 연출은 같은 구간에 제 고리를 홀수 틱에 얹으므로
	 * ({@code TrialEntrance.halo}) 두 파일을 합친 가장 바쁜 틱은
	 * {@code tickPoints(10) + 28} 이다.
	 */
	static final int SEAT_STRIDE = 5;
	/** 선 하나에 덧칠하는 점 수. 빔이 본선이고 이것은 덧칠이라 촘촘할수록 좋다. */
	private static final int BEAM_POINTS = 24;
	/**
	 * 빔이 아무리 짧아도 이만큼은 뻗어 있다.
	 *
	 * <p>길이가 0 이면 크리스탈 안에 점 하나가 박힌 꼴이라 <b>선이 시작된 것을 못 본다.</b>
	 * 첫 틱부터 손톱만큼은 솟아 있어야 「자라고 있다」로 읽힌다.
	 */
	private static final float BEAM_MIN_GROWTH = 0.05F;

	/** 자리 고리에 찍는 점 수. */
	private static final int RING_POINTS = 40;
	/** 고리를 기둥 윗면에서 이만큼 띄운다. 0 이면 블록 면에 파묻혀 안 보인다. */
	private static final double RING_LIFT = 0.15;
	/** 크리스탈 자리는 기둥 윗면보다 한 칸 위다({@link EndPillars#crystalSeats}). 고리는 윗면에 그린다. */
	private static final double RING_DROP = 1.0;

	/** 이번 틱에 할 일. 월드를 만지기 전에 이것만으로 정해진다. */
	enum Step {
		/** 이 카드는 이미 썼다. */
		NOTHING,
		/** 연출 중. 드래곤을 상공에 붙들고, 크리스탈을 세우고, 선을 긋는다. */
		SHOW,
		/** 연출을 끝낸다 — <b>무적과 빔을 걷는 자리</b>. 카드당 딱 한 틱만 나온다. */
		REVIVE
	}

	/**
	 * 이미 발동한 카드들.
	 *
	 * <p><b>열쇠에 주의.</b> 「받은 틱 + 위험 값」으로 만든 내부 열쇠다. 값이 완전히 같은 부활
	 * 위험 둘을 한 카드에 걸면 두 번이 한 번으로 합쳐진다.
	 *
	 * <p>✅ <b>진입점이 이제 {@code key} 를 받는다.</b> 그 구멍을 막으려면 여기를 그 열쇠로
	 * 바꾸면 되는데, {@link #step} 도 함께 열쇠를 받아야 하고 그 시험이 열여섯 자리에서 부른다 —
	 * 값이 겹치는 카드가 아직 없어 이번에는 그대로 두었다. {@link TrialFireball} 에 같은 글이 있다.
	 *
	 * <p>정적이라 월드보다 오래 산다. {@link #clearState()} 를 부르지 않으면 <b>다음 판에서 같은
	 * 틱에 받은 카드가 죽은 카드가 된다.</b>
	 */
	private static final Set<Key> SPENT = new HashSet<>();

	/**
	 * 연출 중에 우리가 세운 크리스탈들.
	 *
	 * <p><b>개체를 그대로 들고 있는 것이 중요하다.</b> {@link #clearState()} 에는
	 * {@code ServerLevel} 이 없어서, 좌표만 적어 두면 무적을 풀 방법이 없다. 개체를 들고 있으면
	 * 월드가 이미 사라진 뒤에 값을 되돌려도 해가 없다.
	 */
	private static final Map<Key, List<EndCrystal>> RAISED = new LinkedHashMap<>();

	private record Key(long granted, TrialCatalog.Risk.CrystalRevive risk) {
	}

	private TrialCrystalRevive() {
	}

	/**
	 * 매 틱.
	 *
	 * <p>{@code members} 가 비어 있어도 돈다. 되살아나는 것은 사람이 보고 있든 아니든 전투의
	 * 사실이고, 여기서 물러서면 전원이 잠깐 접속을 끊는 것으로 카드를 지울 수 있게 된다.
	 *
	 * <p>{@code key} 는 이 위험을 가리키는 열쇠다({@code 카드 id + '#' + 카드 안 위험
	 * 순번}). {@link TrialRisks} 가 겹침 금지 목록을 이 열쇠로 관리하므로, 자리를 잡는
	 * 실행기는 <b>반드시 이 값을 그대로 넘겨야 한다</b> — 스스로 만들어 쓰면 두 곳에서
	 * 만든 열쇠가 언젠가 갈라진다.
	 *
	 * @param granted 카드를 받은 틱. 연출은 월드 시간이 아니라 여기서부터 센다
	 * @param members <b>지금은 쓰지 않는다.</b> 액션바 자막을 걷어낸 뒤로 이 카드가 사람 목록을
	 *     볼 일이 없어졌다 — 소리도 표식도 크리스탈 자리에서 나간다. 그래도 받는 것은 모든 위험
	 *     실행기가 같은 모양이어야 {@code TrialRisks} 의 분기가 한 줄로 유지되기 때문이다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, String key, long granted, long now,
			@Nullable TrialCatalog.Risk.CrystalRevive risk) {
		if (end == null || risk == null || risk.count() <= 0) {
			return;
		}

		Step step = step(granted, now, risk);
		if (step == Step.NOTHING) {
			return;
		}

		Key seatKey = new Key(granted, risk);
		if (step == Step.SHOW) {
			show(end, dragon, seatKey, granted, now, risk);
			return;
		}
		finish(end, dragon, seatKey, risk);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>연출 도중이었다면 <b>여기가 무적을 푸는 마지막 정상 경로</b>다. 월드를 못 받으므로
	 * {@link #RAISED} 에 들고 있던 개체를 직접 되돌린다. 이미 지워진 개체에 값을 써도 해는 없다.
	 */
	public static void clearState() {
		for (List<EndCrystal> raised : RAISED.values()) {
			for (EndCrystal crystal : raised) {
				release(crystal);
			}
		}
		RAISED.clear();
		SPENT.clear();
	}

	// ------------------------------------------------------------------ 연출

	/**
	 * 연출 한 틱. 드래곤을 상공에 붙들고, 첫 틱에 크리스탈을 세우고, 자리마다 선과 고리를 그린다.
	 *
	 * <p>경고의 세 층은 {@link TrialWarning} 의 것을 그대로 쓴다. 이 카드만 다른 신호를 쓰면
	 * 플레이어가 카드 수만큼 새 언어를 배워야 한다. 다만 여기서 세 층이 뜻하는 것은 「맞는다」가
	 * 아니라 <b>「이 자리가 곧 막힌다」</b>다 — 색은 같은 빨강이고, 요구하는 행동도 같은
	 * 「그 자리에서 비켜라」이므로 뜻이 어긋나지 않는다.
	 *
	 * <p>사람 목록을 받지 않는다. 자막을 걷어낸 뒤로 이 연출이 내보내는 것은 소리와 표식뿐이고
	 * 둘 다 <b>크리스탈 자리</b>에서 나가므로, 누가 접속해 있는지를 알 필요가 없다.
	 *
	 * <p>⚠ <b>매 틱 전부에 하는 일과 이번 틱 몫만 하는 일이 나뉘어 있다.</b> 무적과 바닐라 빔은
	 * 열 개 <b>전부</b>에 매 틱 다시 건다 — 무적은 한 틱만 빠뜨려도 그 틱에 깨질 수 있고, 빔은
	 * 개체 데이터라 점 예산과 무관하다. 파티클(고리·덧칠)만 {@link #SEAT_STRIDE} 로 나눠 그린다.
	 */
	private static void show(ServerLevel end, @Nullable EnderDragon dragon, Key key, long granted,
			long now, TrialCatalog.Risk.CrystalRevive risk) {
		Vec3 perch = perchOver(end);
		if (dragon != null && dragon.isAlive() && perch != null) {
			hold(dragon, perch);
		}

		List<EndCrystal> raised = RAISED.get(key);
		if (raised == null) {
			// 앞선 연출이 비정상으로 끊겨 남은 것이 있으면 여기서 먼저 지운다. 시작과 끝 양쪽에서
			// 훑으므로, 어느 쪽 한 번이라도 돌면 판이 깨끗해진다.
			sweep(end);
			raised = raise(end, openSeatsFor(end, risk.count()), GUARD_MARGIN_TICKS);
			RAISED.put(key, raised);
		} else {
			prune(raised, bystanders(end));
		}

		// 되살릴 자리가 하나도 없어도 드래곤은 올라가 있다 — 연출 길이만큼 못 때리는 것이 이
		// 카드의 대가이고, 그 대가는 결과와 무관하게 치러야 앞뒤가 맞는다.
		if (raised.isEmpty()) {
			return;
		}

		int remaining = remainingShowTicks(now, granted, risk.showTicks());
		// 무적은 매 틱 다시 건다. 「남은 연출 + 여유」라 우리가 멈추면 스스로 풀린다.
		int guard = guardTicks(remaining);
		float progress = showProgress(now, granted, risk.showTicks());
		Vec3 source = dragon != null && dragon.isAlive() ? dragon.position() : perch;
		for (EndCrystal crystal : raised) {
			crystal.setInvulnerableTime(guard);
			crystal.setBeamTarget(beamHead(crystal.position(), source, progress));
		}

		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage != null && TrialRisks.stageJustChanged(remaining)) {
			for (EndCrystal crystal : raised) {
				TrialWarning.sound(end, crystal.position(), stage);
			}
		}
		// 자리를 전부 그리지 않고 이번 틱의 몫만 그린다. 까닭과 상한은 SEAT_STRIDE 에 있다.
		int phase = seatPhase(now, granted);
		for (int index = 0; index < raised.size(); index++) {
			if (!drawsSeat(index, phase)) {
				continue;
			}
			Vec3 seat = raised.get(index).position();
			if (stage != null && stage != TrialWarning.Stage.APPROACH) {
				markSeat(end, seat);
			}
			if (source != null) {
				drawBeam(end, seat, source, progress);
			}
		}
	}

	/**
	 * 이 판에서 드래곤이 머무는 <b>중앙 상공</b>.
	 *
	 * <p>{@link #watchPoint} 와 {@link #WATCH_CLEARANCE} 를 묶어 한 줄로 만든 것뿐이다. 밖으로
	 * 연 이유는 {@link TrialEntrance} 가 같은 점을 써야 하기 때문이다 — 입장 연출과 「부활」
	 * 카드가 저마다 「중앙 상공」을 계산하면 <b>두 연출에서 드래곤이 다른 높이에 선다.</b>
	 *
	 * @return 기둥이 하나도 없으면 {@code null}
	 */
	static @Nullable Vec3 perchOver(@Nullable ServerLevel end) {
		return watchPoint(EndPillars.tops(end), WATCH_CLEARANCE);
	}

	/**
	 * 드래곤을 목표 자리로 당긴다.
	 *
	 * <p>{@link TrialEntrance} 도 이것을 쓴다(입장 연출의 「중앙 상공에 나타난다」). 밖으로 연
	 * 이유는 아래 두 줄 때문이다 — 부위를 함께 밀고 위치 동기화 깃발을 세우는 일을 빠뜨리면
	 * <b>보이는 곳에 없는데 맞는</b> 한 틱이 생기는데, 그 함정을 연출마다 다시 밟게 할 이유가 없다.
	 *
	 * <p>당기기만 하고 속도를 0 으로 눌러 둔다. 비행 AI 는 매 틱 제 목표를 향해 속도를 다시
	 * 계산하므로 우리가 이기는 유일한 방법은 <b>매 틱 자리를 다시 쓰는 것</b>이다. 가까워지면
	 * 정확히 붙이는 것이 핵심이다 — 비율로만 당기면 AI 가 밀어내는 만큼 떨어진 곳에서 균형이
	 * 잡혀, 드래곤이 사람 쪽으로 조금씩 흘러내린다.
	 *
	 * <p>뒤의 두 줄이 「화면에 실제로 보이게」 하는 부분이고, 없으면 서버 혼자 올려 둔 것이 된다.
	 *
	 * <ul>
	 *   <li><b>부위를 함께 민다.</b> 드래곤의 히트박스는 제 몸이 아니라
	 *       {@code EnderDragonPart} 여덟 개다. 그것들은 {@code EnderDragon.aiStep} 의
	 *       {@code tickPart} 에서만 따라오므로, 우리가 몸만 옮기면 <b>보이는 곳에 없는데 맞는</b>
	 *       한 틱이 생긴다. 못 때리는 것이 이 카드의 대가라 그 한 틱이 곧 구멍이다.</li>
	 *   <li><b>{@code syncPosition} 을 세운다.</b> 26.3 {@code ServerEntity.sendChanges} 는
	 *       {@code needsSync || updateInterval.test(...) || 데이터 변경} 일 때만 위치를 내보내고,
	 *       드래곤의 갱신 주기는 기본값(3틱)이다. {@code setPos} 는 두 깃발 중 무엇도 세우지
	 *       않는다 — {@code needsSync} 를 세우는 것은 {@code push}·{@code knockback} 뿐이다.
	 *       이 깃발은 {@code sendChanges} 가 읽고 바로 내려 주는 일회용이라 되돌릴 것이 없다.</li>
	 * </ul>
	 */
	static void hold(EnderDragon dragon, Vec3 perch) {
		Vec3 from = dragon.position();
		Vec3 next = towards(from, perch, WATCH_PULL, WATCH_SNAP);
		dragon.setPos(next.x, next.y, next.z);
		dragon.setDeltaMovement(Vec3.ZERO);

		Vec3 shift = next.subtract(from);
		for (EnderDragonPart part : dragon.getSubEntities()) {
			part.setPos(part.getX() + shift.x, part.getY() + shift.y, part.getZ() + shift.z);
		}
		dragon.syncPosition = true;
	}

	/**
	 * 크리스탈에서 드래곤 쪽으로 선을 덧칠한다.
	 *
	 * <p>본선은 {@code setBeamTarget} 이 그리는 바닐라 부활 빔이고 이것은 그 위의 먼지다.
	 * <b>긴 거리 깃발을 켜서</b> 보낸다 — 끄면 발생 지점에서 32 블록 안의 사람에게만 가고,
	 * 이 선은 처음부터 끝까지 그보다 멀다. 앞선 구현이 안 보였던 이유가 정확히 이것이다.
	 */
	private static void drawBeam(ServerLevel end, Vec3 from, Vec3 to, float progress) {
		for (Vec3 point : beamPoints(from, to, progress, BEAM_POINTS)) {
			end.sendParticles(TrialWarning.dust(TrialWarning.Colors.DEADLY), true, false,
					point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 자리에 빨간 고리를 그린다.
	 *
	 * <p>{@link TrialWarning#markGround} 를 쓰지 않고 같은 고리를 여기서 다시 그리는 이유는
	 * 하나뿐이다 — 그쪽은 짧은 거리 파티클이라 <b>기둥 꼭대기의 고리가 바닥에 선 사람에게 가지
	 * 않는다.</b> 가장 높은 기둥은 바닥에서 40 블록이고 제한은 32 블록이다. 색·모양·높이는
	 * {@link TrialWarning} 의 것을 그대로 따른다 — 규약이 갈라지면 안 된다.
	 */
	private static void markSeat(ServerLevel end, Vec3 seat) {
		for (int index = 0; index < RING_POINTS; index++) {
			double angle = (Math.PI * 2.0 * index) / RING_POINTS;
			end.sendParticles(TrialWarning.dust(TrialWarning.Colors.DEADLY), true, false,
					seat.x + Math.cos(angle) * SEAT_RADIUS,
					seat.y - RING_DROP + RING_LIFT,
					seat.z + Math.sin(angle) * SEAT_RADIUS,
					1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	// ------------------------------------------------------------------ 크리스탈

	/**
	 * 자리마다 크리스탈을 하나씩 세우고 무적을 건다.
	 *
	 * <p>바닐라와 <b>같은 좌표·같은 회전</b>으로 둔다({@code EndSpikeFeature} 가 하는 것과 같다).
	 * 처음 생성된 크리스탈과 구분이 가면 조준이 달라지고, 플레이어가 「부활한 것은 다르다」는
	 * 없는 규칙을 배우게 된다.
	 *
	 * <p>무적은 <b>월드에 넣기 전에</b> 건다. 넣고 나서 걸면 그 사이 한 틱이 맨몸이고, 하필 그
	 * 틱에 다른 크리스탈의 폭발이 닿으면 연출이 시작하자마자 무너진다.
	 *
	 * @param guard 처음에 걸 무적 틱. 연출 없이 바로 세우는 길에서는 0 이다
	 */
	private static List<EndCrystal> raise(ServerLevel end, List<Vec3> seats, int guard) {
		List<EndCrystal> born = new ArrayList<>();
		for (Vec3 seat : seats) {
			EndCrystal crystal = new EndCrystal(end, seat.x, seat.y, seat.z);
			crystal.setYRot(end.getRandom().nextFloat() * 360.0F);
			crystal.setInvulnerableTime(Math.max(0, guard));
			if (!end.addFreshEntity(crystal)) {
				continue;
			}
			born.add(crystal);
			bloom(end, seat);
		}
		return born;
	}

	/** 지금 되살릴 수 있는 자리들 — 비어 있고, 사람이 서 있지 않은 곳에서 개수만큼. */
	private static List<Vec3> openSeatsFor(ServerLevel end, int count) {
		return pickSeats(withoutOccupied(
				openSeats(EndPillars.crystalSeats(end), livingCrystals(end)), bystanders(end)),
				count);
	}

	/**
	 * 연출 도중에 사라졌거나 사람이 올라선 크리스탈을 목록에서 뺀다.
	 *
	 * <p>사람이 올라선 것은 <b>터뜨리지 않고 거둔다.</b> {@code RemovalReason.DISCARDED} 는
	 * {@code EndCrystal.hurtServer} 를 지나지 않으므로 위력 6 짜리 폭발이 나지 않는다 — 기둥
	 * 위에서 폭발은 곧 낙사다. 무적을 먼저 풀어 두는 것은 습관이 아니라 규칙이다.
	 */
	private static void prune(List<EndCrystal> raised, List<Vec3> standing) {
		Iterator<EndCrystal> walk = raised.iterator();
		while (walk.hasNext()) {
			EndCrystal crystal = walk.next();
			if (!crystal.isAlive()) {
				walk.remove();
				continue;
			}
			if (nearAny(crystal.position(), standing, SEAT_RADIUS, SEAT_HEADROOM)) {
				release(crystal);
				crystal.remove(Entity.RemovalReason.DISCARDED);
				walk.remove();
			}
		}
	}

	/**
	 * 연출을 끝낸다 — 무적과 빔을 걷고 결과를 알린다.
	 *
	 * <p>여기서 크리스탈을 <b>새로 만들지 않는다.</b> 이미 첫 틱에 서 있다. 이 틱이 하는 일은
	 * 「복구 완료」를 보여 주고 <b>우리가 걸어 둔 것을 전부 되돌리는</b> 것뿐이다.
	 *
	 * <p>드래곤이 죽었어도 걷는 일은 한다. 예전에는 드래곤이 없으면 그냥 돌아갔는데, 이제는 이미
	 * 무적인 물건이 판에 있으므로 돌아가면 그것이 그대로 남는다.
	 *
	 * <p>연출을 <b>한 번도 못 돌린</b> 카드는 여기서 세운다. {@code showTicks} 가 0 이하인 카드와,
	 * 연출 도중 서버가 내려갔다 올라와 첫 틱이 곧 이 틱인 경우다. 연출은 잃었어도 결과는 일어나야
	 * 한다 — 안 그러면 카드를 소모하고 아무 일도 없다.
	 */
	private static void finish(ServerLevel end, @Nullable EnderDragon dragon, Key key,
			TrialCatalog.Risk.CrystalRevive risk) {
		List<EndCrystal> raised = RAISED.remove(key);
		if (raised != null) {
			for (EndCrystal crystal : raised) {
				release(crystal);
			}
		}
		// 우리가 놓친 것까지 판을 훑는다. 서버가 연출 도중 내려갔다 올라오면 RAISED 가 비어 있고
		// 이 틱이 바로 REVIVE 라, 남은 무적과 빔을 걷을 기회는 여기 한 번뿐이다.
		sweep(end);
		if (raised == null) {
			raised = raise(end, openSeatsFor(end, risk.count()), 0);
		} else {
			for (EndCrystal crystal : raised) {
				if (crystal.isAlive()) {
					bloom(end, crystal.position());
				}
			}
		}

		int born = 0;
		for (EndCrystal crystal : raised) {
			if (crystal.isAlive()) {
				born++;
			}
		}
		if (born <= 0) {
			return;
		}
		if (dragon != null && dragon.isAlive()) {
			heal(dragon, risk.heal(), born);
		}
		announce(end, dragon, born);
	}

	/**
	 * 크리스탈 하나에 우리가 걸어 둔 것을 전부 되돌린다.
	 *
	 * <p>{@code setPermanentlyInvulnerable(false)} 까지 부르는 것은 우리가 켜기 때문이 아니다.
	 * 켜지 않는다. 옛 저장 파일이나 바닐라 소환 의식이 켜 둔 것이 남아 있을 수 있어서,
	 * 「이 크리스탈은 이제 깰 수 있다」를 한 자리에서 보장하려고 함께 끈다.
	 */
	private static void release(EndCrystal crystal) {
		crystal.setInvulnerableTime(0);
		crystal.setPermanentlyInvulnerable(false);
		crystal.setBeamTarget(null);
	}

	/**
	 * 기둥 위의 크리스탈 전부에서 무적과 빔을 걷는다.
	 *
	 * <p>{@code EnderDragonFight.resetSpikeCrystals} 는 <b>바닐라가 소환 의식을 끝낼 때 쓰는 바로
	 * 그 정리</b>다(기둥마다 {@code getTopBoundingBox} 안의 크리스탈에
	 * {@code setPermanentlyInvulnerable(false)} · {@code setBeamTarget(null)}). 우리가 목록을
	 * 잃어버린 뒤에도 판을 되돌릴 수 있는 유일한 길이라 그대로 빌려 쓴다.
	 *
	 * <p>살아 있는 드래곤이 있을 때만 이 실행기가 도는데, 그때는 소환 의식이 이미 끝나 있어
	 * 의식용 크리스탈을 잘못 건드릴 일이 없다. 바닐라가 모르는 <b>시간제 무적</b>만 따로 끈다.
	 */
	private static void sweep(ServerLevel end) {
		EnderDragonFight fight = end.getDragonFight();
		if (fight != null) {
			fight.resetSpikeCrystals();
		}
		for (EndCrystal crystal : end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive)) {
			crystal.setInvulnerableTime(0);
		}
	}

	/** 크리스탈이 서는 순간과 복구가 끝나는 순간의 빛. 폭발 파티클은 쓰지 않는다 — 정반대로 읽힌다. */
	private static void bloom(ServerLevel end, Vec3 at) {
		end.sendParticles(ParticleTypes.END_ROD, true, false, at.x, at.y + 0.5, at.z, 24,
				0.2, 0.4, 0.2, 0.05);
		end.sendParticles(ParticleTypes.REVERSE_PORTAL, true, false, at.x, at.y + 0.5, at.z, 40,
				0.5, 0.8, 0.5, 0.08);
		end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_FRAME_FILL,
				SoundSource.HOSTILE, 3.0F, 0.8F);
	}

	/**
	 * 되살아난 개수만큼 드래곤을 회복시킨다.
	 *
	 * <p>카드에 적힌 값은 <b>0</b>이다. 바닐라 크리스탈이 살아 있는 동안 이미 드래곤을 회복시키므로
	 * 여기서 또 주면 같은 값을 두 번 받는다. 그래도 칸을 남겨 둔 것은 「되살리되 회복은 없다」와
	 * 「되살리고 즉시 회복까지」가 서로 다른 카드가 될 수 있기 때문이다.
	 */
	private static void heal(EnderDragon dragon, float perCrystal, int born) {
		if (!(perCrystal > 0.0F) || born <= 0) {
			return;
		}
		dragon.heal(perCrystal * born);
	}

	/**
	 * 끝났다고 알린다. 소리는 <b>드래곤 자리</b>에서 낸다 — 이 일을 한 것은 드래곤이다.
	 *
	 * <p>몇 개가 되살아났는지는 이제 <b>로그에만</b> 남는다. 개수를 적어 주던 자막을 걷어냈고,
	 * 소리는 「되살아났다」까지만 말한다. 판에 선 크리스탈은 눈으로 셀 수 있으므로 소리가
	 * 「고개를 들라」만 해도 뜻이 닿는다.
	 */
	private static void announce(ServerLevel end, @Nullable EnderDragon dragon, int born) {
		Vec3 at = dragon == null ? Vec3.ZERO : dragon.position();
		end.playSound(null, at.x, at.y, at.z, SoundEvents.END_PORTAL_SPAWN,
				SoundSource.HOSTILE, 1.0F, 1.2F);
		SharedFateMod.LOGGER.info("[END] 「부활」 — 크리스탈 {}개가 되살아났습니다", born);
	}

	// ------------------------------------------------------------------ 월드에서 읽어 오는 것

	/** 지금 살아 있는 크리스탈들의 자리. 철장에 갇힌 것도 자리를 차지한다. */
	private static List<Vec3> livingCrystals(ServerLevel end) {
		List<Vec3> taken = new ArrayList<>();
		for (EndCrystal crystal : end.getEntities(EntityTypes.END_CRYSTAL, EndCrystal::isAlive)) {
			taken.add(crystal.position());
		}
		return taken;
	}

	/**
	 * 자리를 막을 수 있는 사람들.
	 *
	 * <p>팀원 목록이 아니라 <b>차원에 있는 사람 전부</b>를 본다. 팀에 없는 사람이 기둥 위에 있다고
	 * 발밑에 크리스탈을 생성해도 되는 것은 아니다. 관전자는 뺀다 — 불에 타지도 폭발에 밀리지도
	 * 않는데 자리를 막으면 그것이야말로 버그로 보인다.
	 */
	private static List<Vec3> bystanders(ServerLevel end) {
		List<Vec3> standing = new ArrayList<>();
		for (ServerPlayer player : end.players()) {
			if (player.isSpectator()) {
				continue;
			}
			standing.add(player.position());
		}
		return standing;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 이번 틱에 그릴 자리의 몫.
	 *
	 * <p>{@link TrialRisks#elapsedSinceGrant} 에서 뽑으므로 매 틱 1씩 늘고
	 * {@value #SEAT_STRIDE} 마다 처음으로 돌아온다 — 빈 차례가 순서대로 메워지는 것이 이 값의
	 * 일이다. 월드 시간을 그대로 쓰지 않는 것은 되감긴 판에서 음수가 나오기 때문이다.
	 */
	static int seatPhase(long now, long granted) {
		return (int) Math.floorMod(TrialRisks.elapsedSinceGrant(now, granted), (long) SEAT_STRIDE);
	}

	/**
	 * 이 자리를 이번 틱에 그리는가.
	 *
	 * <p>자리 번호를 {@link #SEAT_STRIDE} 로 나눈 나머지가 이번 몫과 같을 때만이다. 그래서
	 * <b>어떤 자리도 {@value #SEAT_STRIDE}틱 안에 반드시 한 번 차례가 온다</b> — 먼지 수명(최소
	 * 8틱)보다 짧아 열 자리가 언제나 동시에 보인다.
	 */
	static boolean drawsSeat(int index, int phase) {
		if (index < 0) {
			return false;
		}
		return Math.floorMod(index - phase, SEAT_STRIDE) == 0;
	}

	/**
	 * 자리가 이만큼일 때 <b>가장 바쁜 한 틱</b>에 나가는 점 수.
	 *
	 * <p>고리와 덧칠이 같은 틱에 나가는 자리 수({@code ⌈자리 / 스트라이드⌉})로 센다. 예산을 묻는
	 * 자리는 늘 나쁜 쪽을 봐야 하므로 올림이고, 고리가 그려지지 않는 층
	 * ({@link TrialWarning.Stage#APPROACH})은 이보다 적게 나간다.
	 *
	 * <p>{@code TrialCrystalReviveTest} 가 이 값을 예산에 대고 붙들어 둔다 — 점을 늘리는 사람이
	 * 「그래서 몇 점인가」를 손으로 세지 않게 하는 것이 이 메서드의 존재 이유다.
	 */
	static int tickPoints(int seats) {
		if (seats <= 0) {
			return 0;
		}
		int drawn = (seats + SEAT_STRIDE - 1) / SEAT_STRIDE;
		return drawn * (RING_POINTS + BEAM_POINTS);
	}

	/**
	 * 이번 틱에 무엇을 할 차례인가. <b>{@link Step#REVIVE} 를 돌려주면서 카드를 소모한다.</b>
	 *
	 * <p>소모를 여기서 하는 이유는 「돌려주고 나서 호출자가 잊는」 길을 막기 위해서다. 부활 틱을
	 * 두 번 돌려주는 순간 크리스탈이 스무 개가 되고, 그 사고는 실제 전투에서만 드러난다.
	 *
	 * <p>자리가 하나도 비어 있지 않아 실제로 아무것도 서지 않아도 카드는 소모된다. 연출은 이미
	 * 돌았고 드래곤은 그 시간 동안 하늘에 있었다 — 대가를 치렀으면 카드도 끝난 것이다. 남겨 두면
	 * 한참 뒤 사람이 크리스탈을 깨는 순간 예고 없이 되살아난다.
	 */
	static Step step(long granted, long now, TrialCatalog.Risk.CrystalRevive risk) {
		Key key = new Key(granted, risk);
		if (SPENT.contains(key)) {
			return Step.NOTHING;
		}
		if (!revivesAt(now, granted, risk.showTicks())) {
			return Step.SHOW;
		}
		SPENT.add(key);
		return Step.REVIVE;
	}

	/**
	 * 지금이 연출이 끝나는 틱인가.
	 *
	 * <p>{@code showTicks} 가 0 이하면 연출 없이 받은 그 틱에 바로다. 복원 직후 {@code now} 가
	 * 받은 틱보다 작을 수 있으므로 흐른 시간은 {@link TrialRisks#elapsedSinceGrant} 로 잰다 —
	 * 그냥 빼면 음수가 나와 「아직 연출 중」으로 영영 남는다.
	 */
	static boolean revivesAt(long now, long granted, int showTicks) {
		return TrialRisks.elapsedSinceGrant(now, granted) >= Math.max(0, showTicks);
	}

	/** 부활까지 남은 틱. 0 이면 이번 틱이다. */
	static int remainingShowTicks(long now, long granted, int showTicks) {
		if (showTicks <= 0) {
			return 0;
		}
		long left = showTicks - TrialRisks.elapsedSinceGrant(now, granted);
		return (int) Math.max(0L, Math.min(showTicks, left));
	}

	/**
	 * 이번 틱에 다시 걸어 둘 무적 길이.
	 *
	 * <p>연출이 끝날 때까지 + 여유. <b>절대 무한이 되어서는 안 된다</b> — 이 함수가 0 보다 큰 수만
	 * 돌려주는 한, 우리가 다음 틱에 죽어도 크리스탈은 그만큼 뒤에 스스로 깨질 수 있게 된다.
	 */
	static int guardTicks(int remainingShowTicks) {
		return Math.max(0, remainingShowTicks) + GUARD_MARGIN_TICKS;
	}

	/**
	 * 연출이 얼마나 진행됐는가. 0 이 시작, 1 이 부활하는 순간.
	 *
	 * <p><b>0 과 1 을 벗어나면 안 된다.</b> 이 값이 선의 길이라, 1 을 넘으면 선이 드래곤을 지나쳐
	 * 허공으로 뻗고 음수면 크리스탈 아래로 뻗는다. 둘 다 「어디로 이어지는지」를 거짓말하는 것이다.
	 */
	static float showProgress(long now, long granted, int showTicks) {
		if (showTicks <= 0) {
			return 1.0F;
		}
		long elapsed = TrialRisks.elapsedSinceGrant(now, granted);
		if (elapsed >= showTicks) {
			return 1.0F;
		}
		return (float) elapsed / showTicks;
	}

	/**
	 * 아직 크리스탈이 없는 자리들.
	 *
	 * <p>살아 있는 자리를 건너뛰는 것이 이 카드가 「되살린다」인 이유다. 겹쳐 놓으면 크리스탈이
	 * 두 겹으로 서고, 하나를 깨면 옆 것이 연쇄로 터져 기둥 위가 통째로 날아간다.
	 */
	static List<Vec3> openSeats(List<Vec3> seats, List<Vec3> taken) {
		List<Vec3> open = new ArrayList<>();
		for (Vec3 seat : seats) {
			if (!nearAny(seat, taken, SEAT_TAKEN_REACH, SEAT_TAKEN_REACH)) {
				open.add(seat);
			}
		}
		return open;
	}

	/**
	 * 사람이 서 있는 자리를 뺀다.
	 *
	 * <p>이제는 <b>연출이 시작될 때</b> 거른다. 물건을 먼저 세우는 순서로 바꾸었으니 「생성 직전」이
	 * 곧 첫 틱이다. 연출 도중에 그 자리로 올라오는 사람은 {@link #prune} 이 따로 본다.
	 */
	static List<Vec3> withoutOccupied(List<Vec3> seats, List<Vec3> standing) {
		List<Vec3> safe = new ArrayList<>();
		for (Vec3 seat : seats) {
			if (!nearAny(seat, standing, SEAT_RADIUS, SEAT_HEADROOM)) {
				safe.add(seat);
			}
		}
		return safe;
	}

	/** 가로 반경과 세로 높이를 따로 본다. 자리는 바닥의 원이고 기둥 위아래는 사정이 아주 다르다. */
	private static boolean nearAny(Vec3 seat, List<Vec3> others, double radius, double headroom) {
		for (Vec3 other : others) {
			if (Math.abs(other.y - seat.y) <= headroom
					&& TrialRisks.insideMark(other, seat, radius)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 되살릴 자리를 개수만큼 집는다.
	 *
	 * <p>고르지 않고 목록 순서대로 집는다. 지금 카드는 기둥 수만큼(10) 되살리므로 고를 일 자체가
	 * 없다. <b>개수가 적은 카드를 만들 때 여기에 고르는 규칙을 더하라.</b> 물건을 첫 틱에 세우게
	 * 되었으니 이제는 저절로 카드당 한 번만 굴리게 된다.
	 */
	static List<Vec3> pickSeats(List<Vec3> open, int count) {
		if (count <= 0) {
			return List.of();
		}
		return List.copyOf(open.subList(0, Math.min(count, open.size())));
	}

	/**
	 * 드래곤이 머무는 중앙 상공.
	 *
	 * <p>좌표를 박지 않는다. 기둥 배치도 높이도 <b>월드 시드에서 나오므로</b> 어느 판에서 재어 적은
	 * 숫자는 다음 판에서 기둥 속이거나 화살 사거리 안이다. 가로는 기둥들의 한가운데, 세로는 가장
	 * <b>높은</b> 기둥 위다 — 평균을 쓰면 제일 높은 기둥 꼭대기에 올라선 사람이 드래곤과 같은
	 * 높이에 서게 된다.
	 *
	 * @return 기둥이 하나도 없으면 {@code null}. 그때는 붙들 자리가 없으니 붙들지 않는다
	 */
	static @Nullable Vec3 watchPoint(List<Vec3> tops, double clearance) {
		if (tops.isEmpty()) {
			return null;
		}
		double x = 0.0;
		double z = 0.0;
		double highest = tops.getFirst().y;
		for (Vec3 top : tops) {
			x += top.x;
			z += top.z;
			highest = Math.max(highest, top.y);
		}
		return new Vec3(x / tops.size(), highest + Math.max(0.0, clearance), z / tops.size());
	}

	/**
	 * 다음 틱에 놓을 자리.
	 *
	 * <p>{@code snap} 이 있어야 수렴한다. 비율로만 당기면 남은 거리에 비례해 당기는 힘이 줄어
	 * 드래곤 비행 속도와 균형을 이루는 지점에서 멈추고, 그 지점은 드래곤이 노리는 사람 쪽으로
	 * 치우친다 — 「중앙 상공에서 지켜본다」가 「사람 쪽으로 조금씩 내려온다」가 된다.
	 */
	static Vec3 towards(Vec3 from, Vec3 to, double pull, double snap) {
		if (from.distanceToSqr(to) <= Math.max(0.0, snap) * Math.max(0.0, snap)) {
			return to;
		}
		return from.lerp(to, Math.max(0.0, Math.min(1.0, pull)));
	}

	/**
	 * 바닐라 빔이 지금 가리켜야 할 칸. 크리스탈에서 드래곤 쪽으로 진행도만큼 뻗는다.
	 *
	 * <p>{@link #BEAM_MIN_GROWTH} 때문에 0 진행도에서도 조금은 뻗는다. 길이가 0 이면 선이
	 * 시작된 것 자체를 못 본다.
	 *
	 * @return 붙들 자리가 없으면 {@code null}. 그대로 {@code setBeamTarget} 에 넘기면 빔이 걷힌다
	 */
	static @Nullable BlockPos beamHead(Vec3 seat, @Nullable Vec3 perch, float progress) {
		if (perch == null) {
			return null;
		}
		double grown = Math.max(BEAM_MIN_GROWTH, Math.min(1.0F, Math.max(0.0F, progress)));
		Vec3 head = seat.add(perch.subtract(seat).scale(grown));
		return BlockPos.containing(head.x, head.y, head.z);
	}

	/**
	 * 지금까지 그려야 할 선 위의 점들.
	 *
	 * <p>{@code progress} 를 잘라 두는 것은 안전장치가 아니라 <b>규칙</b>이다. 선의 끝이 곧
	 * 「여기까지 이어졌다」라 선분 밖으로 나가면 예고가 거짓말이 된다.
	 */
	static List<Vec3> beamPoints(Vec3 from, Vec3 to, float progress, int points) {
		if (points <= 0) {
			return List.of();
		}
		double grown = Math.max(0.0F, Math.min(1.0F, progress));
		Vec3 head = from.add(to.subtract(from).scale(grown));
		List<Vec3> line = new ArrayList<>(points);
		for (int index = 0; index < points; index++) {
			double along = (double) (index + 1) / points;
			line.add(from.add(head.subtract(from).scale(along)));
		}
		return line;
	}
}
