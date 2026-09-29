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
 * <p>크리스탈을 <b>부순 순간</b> 가장 가까운 다른 크리스탈이 8초({@code shieldTicks}) 동안
 * 보호막을 두른다.
 *
 * <p><b>때리는 것만으로는 걸리지 않는다.</b> 방아쇠는 「맞았다」가 아니라 「부서졌다」다.
 *
 * <h2>이 카드가 빼앗는 것은 순서다</h2>
 *
 * <p>피해가 없다. 한쪽 끝부터 기둥을 차례로 밀고 나가는 가장 편한 길이 막히고, 팀은 <b>먼 것부터
 * 깨거나 8초를 기다리거나</b> 둘 중 하나를 골라야 한다. 그래서 이 파일에서 가장 중요한 코드는
 * 보호막 한 줄이 아니라 「어느 것이 잠겼는지」를 아레나 어디에서든 읽게 만드는 연출 쪽이다 —
 * 그것이 안 보이면 이 카드는 「왜 안 깨지지」가 된다.
 *
 * <h2>「부서졌다」를 무엇으로 잡는가 — 믹스인을 쓰지 않는다</h2>
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
 * <h2>보호막은 시간제 무적이다 — 「부활」이 찾아 둔 그 값</h2>
 *
 * <p>{@code Entity.setInvulnerableTime} 만 쓴다. {@code setPermanentlyInvulnerable} 은
 * {@code Invulnerable} 태그로 <b>저장되므로</b> 서버가 8초 안에 내려가면 다음에 떴을 때
 * 영영 안 깨지는 크리스탈이 남는다 — 전멸하면 월드가 지워지는 게임에서 「끝나지 않는 전투」는
 * 난이도가 아니라 고장이다. 까닭은 {@link TrialCrystalRevive} 클래스 설명에 이미 적혀 있다.
 *
 * <p>거는 값은 <b>「남은 보호막 + 여유」</b>({@link #guardTicks})라 매 틱 다시 걸린다.
 * {@code Entity.commonTick} 이 매 틱 1씩 깎으므로 <b>우리가 부르기를 멈추는 순간 스스로
 * 풀린다.</b> 드래곤이 죽어 세션이 사라지는 길에는 {@code TrialRisks.clearState} 가 끼어들지
 * 않는데({@code DragonTrialManager.tickSessions} 가 세션만 지운다), 그래도 잠긴 크리스탈이 남지
 * 않는 이유가 이 한 줄이다.
 *
 * <p>{@link CrystalWatch#setArrowImmune} 을 쓰지 않은 것은 그쪽이 <b>판 전체·투사체 한정</b>이기
 * 때문이다. 이 카드는 <b>크리스탈 하나·모든 피해</b>라 뜻이 정반대다.
 *
 * <h2>보호막은 언제나 하나뿐이다 — 한 틱에 여럿이 부서져도</h2>
 *
 * <p>카드에 적힌 것은 「가장 가까운 <b>다른 크리스탈이</b>」 하나다. 부서진 개수만큼 걸면 연쇄
 * 폭발 한 번에 남은 기둥이 통째로 잠기고, 그때는 「순서를 바꿔라」가 아니라 「8초 동안 아무것도
 * 하지 마라」가 된다.
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
 * <p>대신 크리스탈을 감싸는 <b>파티클 껍질</b>을 그린다. 껍질은 남은 시간만큼 크고, 다 오므라들어
 * 크리스탈에 달라붙으면 풀린다 — 「어느 것인가」와 「얼마나 남았는가」를 한 신호가 같이 말한다
 * (「과충전」의 자라는 빛기둥과 같은 문법이다).
 *
 * <p>색은 {@link TrialWarning.Colors} 에서 고르지 않는다. 그 규약은 <b>바닥 표식이 요구하는
 * 행동</b>을 뜻하는데(서 있으면 죽는다·밀려난다·너를 노린다·번개), 「이건 지금 못 깬다」는 그
 * 넷 중 어느 것도 아니고 새 색을 만드는 것은 규약을 규약이 아니게 만든다. 그래서 색이 아니라
 * <b>바닐라 파티클 종류</b>로 가른다.
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
 *   <li><b>시간은 받은 {@code now} 로만 잰다.</b> {@code level.getGameTime()} 을 부르면 얼어붙은
 *       판({@code TrialFreeze})에서 혼자 시간이 흐른다</li>
 *   <li><b>드래곤을 건드리지 않는다.</b> {@code setPhase} 도 {@code setTarget} 도 부르지 않는다 —
 *       드래곤 페이즈에 끼어들었다가 착지를 아예 안 하게 된 사고가 있다
 *       ({@link TrialDragonFocus} 클래스 설명)</li>
 * </ul>
 */
public final class TrialCrystalLink {

	// ------------------------------------------------------------------ 못박아 둔 값

	/** 껍질을 다시 그리는 간격(틱). 매 틱 그리면 이 카드 하나가 파티클 예산을 먹는다. */
	private static final int PULSE_TICKS = 4;

	/** 껍질 한 겹(위도)에 찍는 점 수. 세 겹이므로 한 번에 이 값의 세 배가 나간다. */
	private static final int SHELL_POINTS = 20;

	/**
	 * 껍질을 이루는 위도들(라디안).
	 *
	 * <p>적도 하나만 그리면 <b>고리</b>로 보이고, 고리는 이 저장소에서 「바닥 위험 범위」라는 뜻을
	 * 이미 갖고 있다({@link TrialWarning#markGround}). 위아래를 더해야 <b>공</b>으로 읽혀 「감싸고
	 * 있다」가 된다.
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

	/** 껍질이 한 틱에 도는 각(라디안). 멈춰 있으면 점 무늬로 보이고, 돌면 껍질로 보인다. */
	private static final double SHELL_SPIN = 0.05;

	/** 껍질의 중심을 개체 자리에서 이만큼 올린다. 개체 자리는 몸의 바닥이라 그대로 쓰면 아래로 쏠린다. */
	private static final double SHELL_LIFT = 1.0;

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
	 * <p>여기도 개체를 든다 — {@link #clearState()} 에는 {@code ServerLevel} 이 없어서, 좌표만
	 * 적어 두면 무적을 풀 방법이 없다.
	 */
	private static @Nullable EndCrystal shielded;

	/**
	 * 보호막이 끝나는 시각. <b>월드 시간이 아니라</b> {@link TrialRisks#elapsedSinceGrant} 값이다.
	 *
	 * <p>월드 시간으로 적으면 얼어붙은 판에서 혼자 흐르고, 세션을 복원해 {@code now} 가 받은 틱보다
	 * 작아진 판에서 음수로 떨어진다.
	 */
	private static long shieldEnds;

	private TrialCrystalLink() {
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
			arm(end, members, broken, onSeats, elapsed, risk.shieldTicks());
		}
		sustain(end, members, elapsed, risk.shieldTicks());
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>여기가 <b>보호막을 푸는 마지막 정상 경로</b>다. 월드를 못 받으므로 들고 있던 개체에 직접
	 * {@code setInvulnerableTime(0)} 을 쓴다 — 이미 지워진 개체에 써도 해가 없다. 빠뜨리면 다음
	 * 판에 <b>이유 없이 안 깨지는 크리스탈</b>이 남는다. 컴파일도 시험도 조용한 사고다.
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
			List<Vec3> broken, List<EndCrystal> survivors, long elapsed, int shieldTicks) {
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
			// 옮기기 전에 옛 것을 반드시 푼다. 안 풀면 들고 있던 손을 놓는 순간 그 크리스탈은
			// 여유 시간만큼 더 무적인 채로 우리 손을 떠난다.
			release();
		}
		shielded = next;
		shieldEnds = elapsed + shieldTicks;
		next.setInvulnerableTime(guardTicks(shieldTicks));
		// 껍질을 여기서 한 번 그린다. 유지 쪽은 주기를 타므로 그쪽에만 맡기면 소리가 난 뒤
		// 최대 PULSE_TICKS 동안 화면에 아무것도 없는 틈이 생긴다 — 그 틈이 「걸렸다는데 어디에?」다.
		shell(end, next.position(), SHELL_MAX, elapsed);
		announce(end, members);
		SharedFateMod.LOGGER.info("[END] 「연결된 수정」 — 크리스탈이 부서져 가장 가까운 하나가 {}틱 동안 잠깁니다",
				shieldTicks);
	}

	/**
	 * 보호막 한 틱 — 무적을 다시 걸고 껍질을 그린다.
	 *
	 * <p>무적을 <b>매 틱 다시 거는 것</b>이 이 카드의 안전장치다. 거는 값이 「남은 시간 + 여유」라
	 * 우리가 한 번이라도 못 부르면 그 순간부터 여유만큼만 더 버티고 스스로 0 이 된다. 드래곤이
	 * 죽어 세션이 사라지는 길에는 {@code TrialRisks.clearState} 가 끼어들지 않으므로, 그 길에서
	 * 보호막을 푸는 것은 정확히 이 성질이다.
	 *
	 * <p>지켜보던 크리스탈이 <b>사라져 있으면</b> 손만 놓는다. 청크 언로드로 없어졌을 수 있고,
	 * 없는 것을 찾다 예외를 내면 <b>그 틱의 다른 시련까지 멈춘다.</b>
	 */
	private static void sustain(ServerLevel end, @Nullable List<ServerPlayer> members, long elapsed,
			int shieldTicks) {
		EndCrystal crystal = shielded;
		if (crystal == null) {
			return;
		}
		if (crystal.isRemoved()) {
			// 걷을 대상이 없다. 무적은 개체와 함께 사라졌으므로 되돌릴 것도 없다.
			shielded = null;
			shieldEnds = 0L;
			return;
		}

		int remaining = remainingShield(elapsed, shieldEnds, shieldTicks);
		if (remaining <= 0) {
			release();
			expire(end, members);
			return;
		}

		// 「부활」의 sweep 이 판의 모든 크리스탈 무적을 0 으로 밀 때가 있는데, 매 틱 다시 거는
		// 덕분에 그 한 틱만 비고 스스로 돌아온다. 반대로 우리가 release 로 0 을 써도 그쪽이 매 틱
		// 다시 걸므로 대칭이다 — 둘 다 「매 틱 다시 건다」라 어느 쪽도 상대를 영영 지우지 못한다.
		crystal.setInvulnerableTime(guardTicks(remaining));

		if (elapsed % PULSE_TICKS != 0L) {
			return;
		}
		shell(end, crystal.position(), shellRadius(remaining, shieldTicks), elapsed);
	}

	/**
	 * 들고 있던 보호막을 되돌린다.
	 *
	 * <p>되돌릴 것은 시간제 무적 하나뿐이다. 블록도 빔도 드래곤도 건드린 적이 없다.
	 * {@code setPermanentlyInvulnerable} 은 <b>켠 적이 없으므로 끄지도 않는다</b> — 그것까지 끄는
	 * 것은 「부활」의 일이고(옛 저장 파일과 소환 의식을 함께 보는 자리다), 여기서 같이 끄면 우리가
	 * 켜지 않은 값을 우리가 지우는 셈이 된다.
	 */
	private static void release() {
		if (shielded != null) {
			shielded.setInvulnerableTime(0);
		}
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
	 * 「부활」도 같은 파티클을 쓰지만 그쪽은 크리스탈이 설 때 한 번 터지는 꽃이고 이것은 8초 동안
	 * 도는 껍질이라, 화면에서 섞이지 않는다.
	 *
	 * <p>껍질을 천천히 돌린다. 같은 점에 계속 찍으면 점 무늬로 보이고, 돌면 면으로 보인다.
	 *
	 * @param radius 남은 시간이 정하는 반경. 다 오므라들면 곧 풀린다는 뜻이다
	 */
	private static void shell(ServerLevel end, Vec3 at, double radius, long elapsed) {
		double spin = elapsed * SHELL_SPIN;
		for (double band : SHELL_BANDS) {
			double ringRadius = radius * Math.cos(band);
			double lift = radius * Math.sin(band);
			for (int index = 0; index < SHELL_POINTS; index++) {
				double angle = spin + (Math.PI * 2.0 * index) / SHELL_POINTS;
				end.sendParticles(ParticleTypes.END_ROD, true, false,
						at.x + Math.cos(angle) * ringRadius,
						at.y + SHELL_LIFT + lift,
						at.z + Math.sin(angle) * ringRadius,
						1, 0.0, 0.0, 0.0, 0.0);
			}
		}
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
	 * 있고, 그러면 남은 시간이 카드에 적힌 것보다 길어져 8초짜리 보호막이 20초가 된다.
	 */
	static int remainingShield(long elapsed, long endsAt, int shieldTicks) {
		int span = Math.max(0, shieldTicks);
		long left = endsAt - elapsed;
		return (int) Math.max(0L, Math.min(span, left));
	}

	/**
	 * 이번 틱에 걸어 둘 무적 길이.
	 *
	 * <p>남은 보호막 + 여유. <b>절대 무한이 되어서는 안 된다</b> — 이 함수가 유한한 수만 돌려주는
	 * 한, 우리가 다음 틱에 죽어도 크리스탈은 그만큼 뒤에 스스로 깨질 수 있게 된다.
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
	 * <p>갓 걸렸을 때 가장 크고 풀리기 직전에 가장 작다. 「얼마나 남았는가」를 말하는 유일한
	 * 신호라 <b>단조롭게 줄어야 한다</b> — 중간에 다시 커지면 사람이 시간을 잘못 읽는다.
	 */
	static double shellRadius(int remaining, int shieldTicks) {
		if (shieldTicks <= 0) {
			return SHELL_MIN;
		}
		double left = Math.max(0.0, Math.min(shieldTicks, remaining)) / shieldTicks;
		return SHELL_MIN + (SHELL_MAX - SHELL_MIN) * left;
	}
}
