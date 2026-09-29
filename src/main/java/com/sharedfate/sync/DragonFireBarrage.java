package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 드래곤 기본 패시브 「연쇄 포격」 — 아레나를 가로지르는 빨간 원 열 개가 한쪽 끝부터 차례로 터진다.
 *
 * <h2>무엇이 보이는가</h2>
 *
 * <ol>
 *   <li><b>예고 6초</b> — 아레나를 가로지르는 선 위에 빨간 원 <b>열 개가 한꺼번에</b> 뜬다.
 *       어디가 위험한지 전부 미리 읽힌다. 원끼리는 닿지도 않는다</li>
 *   <li><b>포격 약 5.4초</b> — 드래곤이 <b>제가 있는 쪽 끝 원부터</b> 화염구를 던지고, 원이
 *       0.6초 간격으로 <b>하나씩</b> 터진다</li>
 *   <li><b>끝</b> — 마지막 원이 터지면 그것으로 끝이다. <b>아무것도 남지 않는다</b></li>
 * </ol>
 *
 * <h2>잔류를 남기지 않는 것이 이 패턴의 회피법을 만든다</h2>
 *
 * <p>이 자리의 앞선 구현은 지나간 자리에 15초짜리 장판을 남겼다. 이번에는 <b>한 틱도
 * 남기지 않는다.</b> 「여운으로 잠깐」도 없다 — 원이 터진 그 틱 이후로 그 자리는 <b>완전히
 * 안전</b>하고, 이미 터진 원 위에 서 있어도 아무 일이 없다.
 *
 * <p>그것이 인심이 아니라 <b>설계</b>인 이유는 이 패턴의 정답이 거기서 나오기 때문이다. 원이
 * 한쪽 끝에서 차례로 터지므로 플레이어가 배울 수 있는 답은 <b>「방금 터진 칸으로 들어간다」</b>
 * 다. 잔류가 남으면 그 답이 막히고, 그러면 원 사이 틈({@link #shellGap} - 지름 = 1칸)으로만
 * 빠져야 해서 <b>어디에 서 있었는지 운</b>에 맡기게 된다. 배움이 되는 판과 주사위가 되는 판의
 * 차이가 잔류 한 줄에 달려 있다.
 *
 * <p>그래서 <b>바닐라 드래곤 잔류 구름 계열 파티클을 쓰지 않는다.</b> 예전 구현이 그 생김새를
 * 조사해 두었지만 여기 옮겨 적지 않았다 — 「좀 남기면 멋질 텐데」로 되돌릴 씨앗이 되기 때문이다.
 * 되살리려면 이 문단부터 함께 지워야 하고, {@code 이미_터진_원은_아무도_태우지_않는다} 와
 * {@code 잔류_구름_파티클이_코드에_남아_있지_않다} 가 그때 먼저 깨진다.
 *
 * <h2>원끼리 겹치지 않는다 — 한 틱에 두 번 맞는 자리가 없다</h2>
 *
 * <p>{@code TrialRisks.SPOT_MIN_GAP_FACTOR} 에 이 저장소가 「낙뢰」에서 겪은 사고가 적혀 있다.
 * 반경 R 짜리 원 둘의 중심이 <b>2R 보다 가까우면</b> 겹침 구역이 생기고 거기 선 사람은 한 틱에
 * 두 번 맞는다 — 반경 40 아레나에 반경 3 짜리 열 곳을 무작위로 놓으면 그 일이 <b>62.8%</b> 확률로
 * 일어난다.
 *
 * <p>여기서는 자리를 무작위로 뽑지 않고 <b>선 위에 균등하게</b> 놓으므로 간격이 계산으로 나온다.
 * 선 길이 80 을 열로 나눠 <b>8칸</b>이고 지름은 7칸이라 <b>1칸이 남는다.</b> 개수를 늘리거나 반경을
 * 키우면 이 여유가 먼저 사라지므로, {@code 원끼리_반경의_두_배보다_멀다} 가 그 자리에서 멈춰 세운다.
 *
 * <h2>순차가 안전장치다</h2>
 *
 * <p>열 개가 겹쳐 터지면 한 틱에 {@code 6 × 10 = 60} 이고 팀 공유 체력은 20 이다. 그래서
 * <b>한 틱에 정확히 하나만 터진다</b>({@link #blastingIndex} 는 언제나 값 하나만 돌려준다).
 * {@link #worstCaseTickDamage} 가 그 곱셈의 상대이고 {@code 한_틱에_하나만_터진다} 가 지킨다.
 *
 * <p>다만 <b>연달아 맞는 것</b>은 막지 않는다 — 막을 수도 없고, 그것이 이 패턴의 긴장이다.
 * 도망치다 다음 원에 들어가면 또 맞는다. 몇 발까지 맞을 수 있는지는 {@link #chainHits} 가 값에서
 * 직접 센다.
 *
 * <h2>드래곤을 한 칸도 옮기지 않는다</h2>
 *
 * <p>앞 작업에서 26.3 바이트코드를 세어 보고 접은 것이다. 같은 결론을 다시 검토하지 않도록 이유를
 * 남긴다.
 *
 * <ol>
 *   <li><b>드래곤 몸은 지나가는 것만으로 사람을 때린다.</b> {@code EnderDragon.aiStep} 의 서버
 *       구간이 매 틱 {@code hurt(level, getEntities(head.getBoundingBox().inflate(1)))} 로
 *       <b>{@code mobAttack} 10.0F</b> 를 넣고,
 *       {@code knockBack(wing2.getBoundingBox().inflate(4,2,4).move(0,-2,0))} 로 5.0F 와 밀치기를
 *       넣는다. 팀 공유 체력 20 에 10 은 절반이고, 밀치기는 엔드 섬 밖으로 미는 길이다</li>
 *   <li><b>드래곤 몸은 지나가는 것만으로 블록을 부순다.</b> 같은 구간의 {@code checkWalls} 가
 *       {@code DRAGON_IMMUNE} 이 아닌 블록을 {@code removeBlock} 한다. 엔드스톤과 흑요석은
 *       면제라도 <b>사람이 놓은 블록은 아니다</b></li>
 *   <li><b>페이즈 API 도 마찬가지다.</b> {@code setPhase} 가 부르는 {@code begin()} 이
 *       {@code DragonHoldingPatternPhase.currentPath} 를 지우고, 「착지할까」 주사위는 그 경로가
 *       끝난 틱에만 굴러간다 — 「표적」이 드래곤을 <b>착지 자체를 안 하게</b> 만든 사고가 그것이다</li>
 * </ol>
 *
 * <p><b>대신 선을 드래곤에 맞춘다.</b> 첫 화염구가 떠나는 틱에 {@link #startingNear} 가 선의
 * 방향을 뒤집어 <b>드래곤이 있는 쪽 끝 원부터</b> 터지게 한다. 예고 6초 동안 보여 준 것은 좌우
 * 대칭인 원 열 개라 방향을 마지막에 정해도 사람이 본 것은 하나도 바뀌지 않는다. 그리고
 * {@link #drawShot} 이 <b>드래곤 입에서 그 원까지</b> 화염구 궤적을 잇는다 — 드래곤이 어디에
 * 있든 「저기서 던졌다」가 선으로 보인다.
 *
 * <h2>바닐라 화염구 엔티티를 띄우지 않는다</h2>
 *
 * <p>{@code LargeFireball} 의 블록 파괴는 {@code mobGriefing} 게임룰에 묶여 있다. 우리만 끌 수
 * 없고, 끄면 크리퍼와 엔더맨까지 함께 꺼진다. 폭발에는 불도 딸려 붙는다. 엔드 섬에 구멍이 하나
 * 뚫리면 <b>다음 전투부터 발판이 달라지고</b>, 그것이 이 전투가 유일하게 금지한 「대응 불가
 * 즉사」(공허 낙사)로 이어진다. {@link TrialFireball} 이 기둥 화염구에서 이미 푼 문제라 같은 답을
 * 쓴다 — <b>날아오는 모습은 파티클로 그리고 착탄은 직접 계산한다.</b>
 *
 * <p>피해원에 가해 개체를 달지 않는 것도 그쪽과 같다. 실체가 붙은 피해원이면
 * {@code LivingEntity} 가 스스로 밀어내는데 엔드 섬 가장자리에서 밀리면 대응 불가 즉사다. 폭발
 * 피해형이라 폭발 보호는 그대로 듣는다 — 대비한 사람이 손해 보지 않아야 한다.
 */
public final class DragonFireBarrage {

	// ------------------------------------------------------------------ 값

	/**
	 * 다시 포격할 때까지(틱). 40초.
	 *
	 * <p><b>예고 + 포격보다 넉넉히 길어야 한다.</b> 포격 둘이 동시에 살아 있으면 두 선이 만나는
	 * 자리에서 한 틱에 두 번 터지고, 그 순간 {@link #worstCaseTickDamage} 가 거짓이 된다. 지금은
	 * 120 + 108 = 228 이라 572틱이 남는다.
	 */
	static final int PERIOD_TICKS = 800;

	/**
	 * 원의 개수.
	 *
	 * <p>사람이 정한 값이다. <b>여기를 올리면 {@link #shellGap} 이 줄어</b> 원끼리 겹치는 쪽으로
	 * 간다 — 지금 여유가 1칸뿐이라 열한 개면 이미 간격 7.27 로 지름 7 에 0.27 밖에 안 남고, 열두
	 * 개면 6.67 로 <b>겹친다.</b> {@code 원끼리_반경의_두_배보다_멀다} 가 거기서 멈춰 세운다.
	 */
	static final int SHELL_COUNT = 10;
	/**
	 * 원 하나의 반경(칸).
	 *
	 * <p>개수와 마찬가지로 {@link #shellGap} 과 한 손잡이다. 여기를 4.0 으로 올리면 지름이 8 이 되어
	 * 간격과 같아지고, 그 순간 이웃한 원이 한 점에서 닿는다 — {@code TrialRisks.spotMinGap} 은
	 * <b>접점도 겹침으로 본다.</b> 경계에 선 사람이 두 발을 다 맞기 때문이다.
	 */
	static final double SHELL_RADIUS = 3.5;

	/**
	 * 원 열 개가 다 보이고 나서 첫 원이 터질 때까지(틱). 6초.
	 *
	 * <h2>왜 6초인가</h2>
	 *
	 * <p>이 패턴이 요구하는 행동은 <b>「선이 지나갈 자리에서 비키기」</b>다. 원이 아레나를 가로질러
	 * 늘어서므로 옆으로 {@link #SHELL_RADIUS} 칸만 나가면 되지만, <b>어느 쪽으로 나갈지는 골라야</b>
	 * 한다 — 반대편으로 나가면 팀과 갈린다.
	 *
	 * <ul>
	 *   <li>{@link TrialWarning#TICKS_SIDESTEP}(30) — 제자리 옆걸음. 모자란다</li>
	 *   <li>{@link TrialWarning#TICKS_REPOSITION}(50) — 지정한 자리로 이동. 갈 곳을 고르는 시간이
	 *       빠져 있다</li>
	 *   <li>{@link TrialWarning#TICKS_SCATTER}(80) — 넷이 서로 보고 갈라서기. <b>이것이 하한이다</b></li>
	 *   <li><b>100</b> — {@link TrialWarning#stageFor} 가 경고 첫 층을 내기 시작하는 지점이다.
	 *       예고가 100 이하면 「뭔가 온다」 층이 통째로 빠진다</li>
	 * </ul>
	 *
	 * <p>그 위에 여유 20틱(1초)을 얹어 <b>120</b>. 원 열 개가 아레나를 가로질러 늘어선 그림은 읽는 데
	 * 시간이 걸리므로 그쪽이 낫다. 카드 문서의 「부채꼴 브레스」도 6초라, 패시브끼리 맞춰 두면
	 * 플레이어가 「드래곤이 뭔가 준비하면 6초」 하나만 배운다.
	 */
	static final int LEAD_TICKS = 120;
	/**
	 * 원이 하나씩 터지는 간격(틱). 0.6초.
	 *
	 * <p>순차가 이 컨셉의 안전장치이므로 <b>0 이 되면 안 된다.</b> 그리고 <b>바닐라 피격
	 * 무적시간 10틱보다 커야</b> 한다 — 더 촘촘하면 연달아 맞은 두 번째 원의 몫이 무적시간에 먹혀
	 * 조용히 사라지고, 「원 하나에 {@link #DAMAGE_PER_BLAST}」가 거짓이 된다.
	 *
	 * <p>열 개면 터지는 데 {@link #BARRAGE_TICKS} = 108틱(5.4초)이 걸린다.
	 */
	static final int BLAST_INTERVAL_TICKS = 12;
	/**
	 * 화염구 하나가 드래곤 입에서 원까지 날아가는 시간(틱).
	 *
	 * <p>{@link #BLAST_INTERVAL_TICKS} 와 <b>같은 값</b>인 것이 중요하다. 그러면 앞 화염구가
	 * 터지는 바로 그 틱에 다음 화염구가 떠나 <b>하늘에 언제나 정확히 한 발</b>만 있다. 길면 여러
	 * 발이 동시에 날아 「어느 것이 다음인가」가 안 읽히고, 짧으면 던지는 장면 없이 원이 터진다.
	 *
	 * <p>첫 발만 예고 구간 안에서 난다 — 예고 <b>마지막 0.6초</b>다. 그래도 순수한 예고가 108틱
	 * 남아 하한(80)을 넘는다.
	 */
	static final int FLIGHT_TICKS = BLAST_INTERVAL_TICKS;
	/** 첫 원이 터진 뒤 마지막 원이 터질 때까지(틱). 값이 아니라 위 둘에서 나오는 결과다. */
	static final int BARRAGE_TICKS = (SHELL_COUNT - 1) * BLAST_INTERVAL_TICKS;

	/**
	 * 원 하나가 터질 때 팀에게 들어가는 피해.
	 *
	 * <h2>8 에서 6 으로 내렸다</h2>
	 *
	 * <p>출발점은 8 이었다. 값에서 직접 세어 보고 내렸다 — 개수와 배치는 사람이 정한 것이고
	 * <b>피해는 우리가 정하는 값</b>이라서다.
	 *
	 * <ul>
	 *   <li><b>한 틱</b> — 하나만 터지므로 6 이다. 팀 체력 20 의 3할이고 「즉사 메커닉 0개」와
	 *       거리가 멀다</li>
	 *   <li><b>옆으로 비킨 사람</b> — 원끼리 겹치지 않으므로 제자리에 서 있어도 맞는 것은
	 *       <b>한 발</b>이다. 6</li>
	 *   <li><b>선을 따라 도망친 사람</b> — 포격이 8칸씩 0.6초마다 전진하는데 달리기는 0.6초에
	 *       3.4칸이라 따라잡힌다. {@link #chainHits} 로 세면 <b>두 발</b>이고 12 다. 팀 체력의 6할 —
	 *       아프지만 살아서 「선을 따라 도망치면 안 된다」를 배운다. 8 이었으면 16(8할)이라
	 *       드래곤에게 한 대만 더 맞아도 전멸이었다</li>
	 *   <li><b>넷이 서로 다른 원에 하나씩 서 있던 판</b> — 24 로 전멸이다. 6초 동안 원 열 개를
	 *       전부 보여 준 뒤의 <b>네 사람이 각각 실패한</b> 경우이고, 옛 장판에 3초 서 있던 것과
	 *       같은 값이다. 여기까지 막으려면 4 이하여야 하는데 그러면 원 하나가 아프지 않다</li>
	 * </ul>
	 *
	 * <p><b>개수나 반경을 올리는 사람은 여기를 함께 내려야 한다.</b> 간격이 좁아지면 도망치다
	 * 걸리는 발 수가 늘고, 그것이 {@link #chainHits} 에 곧바로 나타난다.
	 */
	static final float DAMAGE_PER_BLAST = 6.0F;
	/**
	 * 한 틱에 터질 수 있는 원의 수.
	 *
	 * <p>순차가 이 컨셉의 안전장치라는 말을 숫자로 적은 것이다. {@link #blastingIndex} 가 값 하나만
	 * 돌려주므로 구조적으로 1 이고, {@link #worstCaseTickDamage} 의 곱셈 상대가 이것이다. 둘이 한
	 * 틱에 터지게 고치는 사람은 반드시 여기를 함께 고쳐야 한다.
	 */
	static final int MAX_CONCURRENT_BLASTS = 1;

	/**
	 * 예고가 이만큼도 안 남았으면 이번 주기는 건너뛴다.
	 *
	 * <p>{@link #LEAD_TICKS} 의 첫 틱을 놓치는 길이 둘 있다 — <b>예고 도중에 서버가 떴을 때</b>와
	 * <b>앞 포격이 아직 돌고 있어 이번 주기를 건너뛴 뒤</b>다. 그때 남은 만큼만 예고하고 던지면
	 * 「예고가 짧은 판」이 생기는데, 짧은 예고는 예고가 아니라 사후 통보다. <b>한 번 안 나가는
	 * 쪽이 싸다.</b>
	 */
	static final int MIN_LEAD_TICKS = TrialWarning.TICKS_SCATTER;

	// ------------------------------------------------------------------ 선을 놓는 값

	/** 원을 늘어놓는 선의 길이. 아레나를 지름으로 가르므로 반경의 두 배다. */
	static final double LINE_LENGTH = TrialRisks.ARENA_RADIUS * 2.0;

	/**
	 * 한 틱에 바닥 표식으로 나가는 점 수의 상한.
	 *
	 * <p>점 하나가 패킷 한 장이고 원 열 개를 예고 6초 내내 매 틱 그린다. 상한이 없으면 개수나
	 * 반경을 올리는 순간 파티클만으로 틱이 밀린다.
	 *
	 * <p>500 인 근거는 <b>이 저장소가 이미 쓰고 있는 예산</b>이다. 「낙뢰」가 반경 3 짜리 고리 열
	 * 개를 동시에 띄우고 그것이 {@code TrialWarning.ringPoints(3.0) × 10 = 400} 점이다. 반경이
	 * 3.5 로 커져 고리당 44점, 열 개면 440 이라 그보다 조금 많고 500 안이다.
	 */
	static final int MARK_MAX_POINTS = 500;

	/** 화염구 꼬리 점 사이 목표 간격(칸). */
	private static final double TRAIL_STEP = 1.5;
	/**
	 * 꼬리 점 사이가 이보다 벌어지면 선이 아니라 점선이다.
	 *
	 * <p>이 궤적이 말하는 것은 <b>「드래곤이 던졌다」</b> 하나뿐이다. 끊기면 인과가 사라지고
	 * 「어디선가 불이 떨어졌다」가 된다.
	 */
	static final double TRAIL_MAX_GAP = 4.0;
	/**
	 * 이 길이까지는 위 간격을 약속한다.
	 *
	 * <p>{@link TrialFireball} 의 기둥 화염구는 발사점이 반경 42 원 위의 기둥이라 70칸을 넘기
	 * 어렵지만, <b>드래곤은 아레나 반대편 하늘에 있을 수 있다.</b> 아레나 지름 80 에 비행 고도를
	 * 더하면 130칸쯤이라 넉넉히 잡는다.
	 */
	static final double TRAIL_KEPT_LENGTH = 128.0;
	/**
	 * 꼬리 점 수 상한.
	 *
	 * <p>숫자를 박지 않고 위 둘에서 뽑는다 — 따로 적어 두면 한쪽만 고쳐져 약속이 조용히 깨진다.
	 * 상한이 없으면 드래곤이 멀수록 패킷이 느는데, 하필 그때가 가장 안 보이는 때다.
	 */
	static final int TRAIL_MAX_POINTS = (int) Math.ceil(TRAIL_KEPT_LENGTH / TRAIL_MAX_GAP);

	/**
	 * 하이트맵이 허공을 돌려줬을 때 쓰는 높이.
	 *
	 * <p>아레나 반경 40 안에도 섬이 끊긴 곳이 있다. 거기서 원을 포기하면 선에 구멍이 뚫려
	 * 「저기는 안전한가」로 읽히므로, 마지막으로 찾은 지면 높이를 그대로 이어 쓴다. 첫 원부터
	 * 허공이면 중앙 섬 표면인 이 값으로 시작한다.
	 */
	private static final double FALLBACK_GROUND_Y = 63.0;

	/** 아직 첫 원이 터지지 않아 포격이 시작되지 않은 상태. */
	static final long NOT_STARTED = Long.MIN_VALUE;

	/**
	 * 이번 주기의 포격을 놓을지 <b>이미 판단했는가</b>.
	 *
	 * <p>「예고 구간이면 놓는다」로만 두면, 앞 포격이 도는 동안 건너뛴 주기가 포격이 끝나는 순간
	 * 되살아나 <b>예고를 절반만 내고</b> 터진다. 주기당 판단을 한 번으로 묶어 그 길을 없앤다.
	 */
	private static long plannedCycle = Long.MIN_VALUE;
	/**
	 * 위 기록이 <b>어느 전투의 것인가</b>. 전투가 열린 틱으로 가른다.
	 *
	 * <p>이것이 없으면 드래곤을 잡고 다시 들어간 판에서 주기 번호가 겹쳐, 새 전투의 그 주기가
	 * 「이미 판단했다」로 건너뛰어진다. 전투가 끝날 때 누가 비워 주기를 <b>기다리지 않는다</b> —
	 * 배선을 한 줄 빠뜨렸다고 패시브가 조용히 한 번 쉬면 안 된다.
	 */
	private static long plannedFight = Long.MIN_VALUE;

	/**
	 * 지금 예고 중이거나 터지고 있는 포격. 없으면 {@code null}.
	 *
	 * <p>아레나도 드래곤도 하나뿐이고 이 모드는 팀이 하나다. 열쇠를 둘 필요가 없어 칸 하나로 둔다.
	 * <b>{@link #clearState()} 로 반드시 비운다</b> — 지난 판의 좌표가 남으면 새 월드에서 아무도
	 * 모르는 자리가 터진다.
	 */
	private static @Nullable Barrage active;

	/**
	 * 한 번의 포격.
	 *
	 * @param cycle     몇 번째 주기의 포격인가. 주기가 넘어갔는지 판단한다
	 * @param from      선의 출발 경계. 이쪽 끝 원이 먼저 터진다
	 * @param to        반대편 경계
	 * @param shells    원의 중심들. <b>목록 순서가 터지는 순서</b>이고 지면 높이가 들어 있다
	 * @param startedAt 첫 원이 터진 틱. 아직이면 {@link #NOT_STARTED}
	 */
	record Barrage(long cycle, Vec3 from, Vec3 to, List<Vec3> shells, long startedAt) {

		Barrage {
			shells = List.copyOf(shells);
		}

		boolean started() {
			return startedAt != NOT_STARTED;
		}

		Barrage startedAt(long tick) {
			return new Barrage(cycle, from, to, shells, tick);
		}
	}

	private DragonFireBarrage() {
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱.
	 *
	 * <p>{@code dragon} 은 <b>읽기만 한다.</b> 선의 방향을 고르고({@link #startingNear}) 입의 자리를
	 * 가져오는({@link #mouthOf}) 두 곳뿐이고, 값을 쓰는 호출은 한 줄도 없다. 왜 옮기지 않는지는
	 * 클래스 설명의 「드래곤을 한 칸도 옮기지 않는다」에 있다.
	 *
	 * @param granted 전투가 열린 틱. 주기는 월드 시간이 아니라 여기서부터 센다. 그래야 판마다
	 *                위상이 달라지고, 받자마자 터지는 일이 없다
	 */
	public static void tick(@Nullable ServerLevel end, @Nullable EnderDragon dragon,
			@Nullable List<ServerPlayer> members, long granted, long now) {
		if (end == null || members == null || members.isEmpty()) {
			return;
		}
		beginFight(granted);
		// 전투가 열린 바로 그 틱에는 아직 아무 주기도 시작되지 않았다.
		if (TrialRisks.elapsedSinceGrant(now, granted) <= 0L) {
			return;
		}

		Barrage run = active;
		// 다 터진 포격을 먼저 버린다. 남겨 두면 다음 포격을 못 놓는다. 버릴 것이 좌표뿐이라
		// 여기서 되돌릴 상태는 없다 — 남은 장판도, 띄워 둔 엔티티도 없다.
		if (run != null && run.started() && !running(run.startedAt(), now)) {
			active = null;
			run = null;
		}

		int remaining = TrialRisks.remainingTicks(now, granted, PERIOD_TICKS);
		long cycle = TrialRisks.strikeIndex(now, granted, PERIOD_TICKS);

		if (remaining <= LEAD_TICKS && cycle != plannedCycle) {
			// 이번 주기는 여기서 한 번만 판단한다. 못 놓으면 그냥 지나간다.
			plannedCycle = cycle;
			// 앞 포격이 아직 돌고 있으면 놓지 않는다. PERIOD_TICKS 가 예고 + 포격보다 길어
			// 실제로는 오지 않는 길이다.
			if (run == null && remaining >= MIN_LEAD_TICKS) {
				run = onGround(end, plan(cycle, end.getRandom().nextDouble()));
				active = run;
			}
		}
		if (run == null) {
			return;
		}

		if (!run.started() && remaining == FLIGHT_TICKS) {
			// 첫 화염구가 떠나는 그 틱에 방향을 정한다. 예고 내내 보여 준 것은 좌우 대칭인 원
			// 열 개라, 여기서 뒤집어도 사람이 본 자리는 하나도 바뀌지 않는다.
			run = startingNear(run, bodyOf(dragon));
			active = run;
			end.playSound(null, run.from().x, run.from().y, run.from().z,
					SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 4.0F, 0.8F);
		}

		if (!run.started()) {
			// 터지는 틱에는 예고를 한 번 더 그리지 않는다. 같은 틱에 고리를 두 벌 보내면
			// 방금 터진 원이 한 틱 더 살아 있는 것으로 보인다 — 「터진 자리는 즉시 안전」이
			// 그 한 틱에서 먼저 깨진다.
			if (!TrialRisks.firesAt(now, granted, PERIOD_TICKS)) {
				warn(end, dragon, members, run, remaining);
				return;
			}
			run = run.startedAt(now);
			active = run;
		}
		bombard(end, dragon, members, run, now);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>좌표는 월드보다 오래 산다. 비우지 않으면 새 월드에서 <b>아무도 예고한 적 없는 자리가
	 * 터지고</b>, 그 증상은 「엔드에 들어가자마자 아프다」라는 엉뚱한 모양으로 나온다.
	 *
	 * <p>드래곤에 되돌릴 것은 없다. 이 패시브는 드래곤을 읽기만 하고 잔류도 엔티티도 남기지
	 * 않으므로 <b>비울 상태가 좌표뿐</b>이다 — 포격 도중 드래곤이 죽거나 서버가 내려가도 판에
	 * 남는 것이 없다.
	 */
	public static void clearState() {
		active = null;
		plannedCycle = Long.MIN_VALUE;
		plannedFight = Long.MIN_VALUE;
	}

	/**
	 * 지난 전투의 찌꺼기를 버린다. 매 틱 불리고 전투가 바뀐 그 틱에만 실제로 지운다.
	 *
	 * <p>전투가 끝날 때 누가 비워 주기를 기다리지 않는다. 배선을 한 줄 빠뜨렸다고 다음 판의
	 * 포격이 한 번 조용히 쉬면, 그 증상은 「가끔 안 나온다」라 아무도 못 잡는다.
	 *
	 * @param granted 전투가 열린 틱. 전투를 가르는 열쇠다
	 */
	static void beginFight(long granted) {
		if (granted == plannedFight) {
			return;
		}
		clearState();
		plannedFight = granted;
	}

	/** 시험용. 지금 포격이 남아 있는가. */
	static @Nullable Barrage active() {
		return active;
	}

	/** 시험용. 월드 없이 만든 포격을 꽂아 둔다. */
	static void remember(@Nullable Barrage barrage) {
		active = barrage;
	}

	/** 시험용. 어느 주기까지 판단이 끝났는가. */
	static long plannedCycle() {
		return plannedCycle;
	}

	/** 시험용. 주기 판단 기록만 남긴다. */
	static void notePlanned(long cycle) {
		plannedCycle = cycle;
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 원 열 개를 한꺼번에 보여 주고 경고 세 층을 올린다.
	 *
	 * <p><b>원은 예고 내내 전부 그린다.</b> {@code TrialRisks} 의 고리는 첫 층에서 소리만 내고
	 * 표식을 늦게 띄우지만, 이 패턴은 <b>「어디가 위험한지 미리 전부 읽힌다」가 컨셉 자체</b>다.
	 * 늦게 띄우면 열 개를 한눈에 훑을 시간이 사라진다.
	 *
	 * <p>색은 {@link TrialWarning.Colors#DEADLY} 빨강 하나뿐이다 — 「서 있으면 죽는다」가 정확히
	 * 이 원의 뜻이고, 규약에 없는 색을 새로 만들면 그 순간 규약이 장식이 된다.
	 *
	 * <p>자막은 띄우지 않는다({@link TrialWarning#shout}). 남은 신호는 <b>소리와 바닥 표식</b>뿐이라
	 * 원을 예고 내내 그리는 것이 더 중요해졌다 — 줄이지 말 것.
	 *
	 * <p>소리는 <b>사람마다 그 자리에서</b> 울린다. 바닐라 소리 사거리는 볼륨이 1 이하면 16칸인데
	 * 이 선은 80칸이라, 한 점에서 울리면 반대편에 선 사람에게 닿지 않는다.
	 */
	private static void warn(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> members, Barrage run, int remaining) {
		mark(end, run, 0);
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage != null && TrialRisks.stageJustChanged(remaining)) {
			for (ServerPlayer member : members) {
				TrialWarning.sound(end, member.position(), stage);
			}
		}
		if (remaining <= FLIGHT_TICKS) {
			// 첫 화염구는 예고의 마지막 0.6초 안에서 난다.
			drawShot(end, mouthOf(dragon), run.shells().getFirst(), approachProgress(remaining));
		}
	}

	/**
	 * 아직 안 터진 원만 빨간 고리로 그린다.
	 *
	 * <p><b>터진 원은 그 틱부터 지운다.</b> 이것이 「방금 터진 칸으로 들어간다」를 가르치는 유일한
	 * 신호다 — 고리가 남아 있으면 이미 안전한 자리가 위험해 보이고, 그러면 원 사이 1칸 틈으로만
	 * 빠지려 든다.
	 *
	 * @param from 이 번째부터 그린다. 예고 중에는 0, 포격 중에는 이미 터진 수
	 */
	private static void mark(ServerLevel end, Barrage run, int from) {
		List<Vec3> shells = run.shells();
		for (int index = Math.max(0, from); index < shells.size(); index++) {
			// TrialWarning.markGround 는 거리 제한을 끈 긴 형태로 보낸다. 아레나가 80칸이라
			// 짧은 형태면 반대편 원이 통째로 안 보인다.
			TrialWarning.markGround(end, shells.get(index), SHELL_RADIUS);
		}
	}

	// ------------------------------------------------------------------ 포격

	/**
	 * 한쪽 끝부터 하나씩 터뜨린다.
	 *
	 * <p>순서가 셋이다 — <b>터뜨리고, 남은 원을 다시 그리고, 다음 화염구를 날린다.</b> 터뜨리는
	 * 것이 먼저라야 방금 터진 원의 고리가 그 틱에 사라진다.
	 */
	private static void bombard(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> members, Barrage run, long now) {
		int blasting = blastingIndex(run.startedAt(), now);
		if (blasting >= 0 && blasting < run.shells().size()) {
			detonate(end, members, run.shells().get(blasting));
		}
		mark(end, run, blownCount(run.startedAt(), now));

		int flying = flyingIndex(run.startedAt(), now);
		if (flying >= 0 && flying < run.shells().size()) {
			drawShot(end, mouthOf(dragon), run.shells().get(flying),
					flightProgress(run.startedAt(), now));
		}
	}

	/**
	 * 원 하나가 터진다. 블록은 건드리지 않고 불도 붙이지 않는다.
	 *
	 * <h2>팀에게 한 번만</h2>
	 *
	 * <p>한 원은 <b>하나의 화염구</b>이므로 안에 선 사람을 모두 때리지 않는다. 넷이 함께 움직이는
	 * 것이 공유 체력 게임의 올바른 대응인데, 모두 때리면 같은 원에 넷이 있을 때 24 가 되어
	 * <b>올바른 대응이 전멸</b>이 된다. 설계의 「광역 한 틱 한 번」이 정확히 이 경우를 위해 있다.
	 *
	 * <p>다른 원은 다른 화염구라 따로 센다 — 그것이 이 패턴의 긴장이고
	 * {@link #DAMAGE_PER_BLAST} 의 설명에 몇 발까지인지 적어 두었다.
	 *
	 * <p><b>이 메서드가 이 원이 아프게 하는 유일한 지점이다.</b> 터진 뒤에는 어디서도 이 자리를
	 * 다시 보지 않는다 — 잔류가 없다는 말을 코드로 적으면 이 문장이 된다.
	 *
	 * <p>피해원에 가해 개체를 달지 않는다. 실체가 붙은 피해원이면 {@code LivingEntity} 가 스스로
	 * 밀어내는데, 엔드 섬 가장자리에서 밀리면 대응 불가 즉사다. 폭발 피해형이라 폭발 보호는 그대로
	 * 듣는다.
	 */
	private static void detonate(ServerLevel end, List<ServerPlayer> members, Vec3 at) {
		// 착탄 연출도 긴 형태다. 맞은 사람만 보고 나머지가 못 보면 「저기 떨어졌다」가 팀에
		// 공유되지 않아 다음 원을 못 읽는다.
		end.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, false,
				at.x, at.y + 0.5, at.z, 1, 0.0, 0.0, 0.0, 0.0);
		end.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, at.x, at.y + 0.5, at.z, 20,
				SHELL_RADIUS * 0.4, 0.3, SHELL_RADIUS * 0.4, 0.02);
		end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
				3.0F, 1.0F);

		// 팀원 목록을 직접 돈다. 상자로 후보를 추릴 이유가 없다 — 어차피 한 명만 세고, 팀이
		// 아닌 사람(관전자·다른 판의 누구)을 때릴 일도 없어야 한다.
		for (ServerPlayer member : members) {
			if (!TrialRisks.insideMark(member.position(), at, SHELL_RADIUS)) {
				continue;
			}
			member.hurtServer(end, end.damageSources().explosion(null, null), DAMAGE_PER_BLAST);
			// 하나의 원은 하나의 공격이다. 나머지는 같은 공격의 두 번째 몫이라 세지 않는다.
			return;
		}
	}

	/**
	 * 드래곤 입에서 원까지 날아가는 화염구.
	 *
	 * <p>선 전체를 매 틱 다 그리면 「화염구가 온다」가 아니라 「드래곤과 저 자리가 빨간 실로
	 * 묶였다」가 된다. <b>지금까지 날아온 만큼</b>만 성긴 연기로 남기고 선두에만 불꽃을 몰아 찍는다.
	 *
	 * <p>세 줄 모두 <b>긴 형태</b>({@code overrideLimiter=true})다. 짧은 형태는 32칸에서 잘리는데
	 * 드래곤은 100칸 밖 하늘에 있을 수 있어, 되돌리는 순간 <b>던지는 장면이 통째로 사라지고</b>
	 * 원이 저절로 터지는 것으로 보인다.
	 *
	 * <p>{@code mouth} 가 {@code null} 이면 아무것도 그리지 않는다. 드래곤이 포격 도중 죽은
	 * 경우인데, 그때 하늘 아무 데서나 그리면 <b>궤적이 거짓말을 한다.</b> 원은 예고한 대로 터진다 —
	 * 이미 보여 준 표식을 무르지 않는 것이 이 전투의 약속이다.
	 */
	private static void drawShot(ServerLevel end, @Nullable Vec3 mouth, Vec3 target,
			double progress) {
		if (mouth == null) {
			return;
		}
		Vec3 aim = target.add(0.0, 0.5, 0.0);
		Vec3 head = headAt(mouth, aim, progress);
		int points = trailSamples(mouth.distanceTo(head));
		for (int index = 0; index < points; index++) {
			Vec3 point = mouth.add(head.subtract(mouth).scale((double) index / points));
			end.sendParticles(ParticleTypes.SMOKE, true, false, point.x, point.y, point.z, 1,
					0.0, 0.0, 0.0, 0.0);
		}
		end.sendParticles(ParticleTypes.FLAME, true, false, head.x, head.y, head.z, 10,
				0.3, 0.3, 0.3, 0.01);
		end.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, head.x, head.y, head.z, 3,
				0.2, 0.2, 0.2, 0.0);
	}

	/** 드래곤 입의 자리. 드래곤이 없거나 죽었으면 {@code null}. <b>읽기만 한다.</b> */
	private static @Nullable Vec3 mouthOf(@Nullable EnderDragon dragon) {
		if (dragon == null || !dragon.isAlive()) {
			return null;
		}
		return dragon.head.position();
	}

	/** 드래곤 몸의 자리. 선의 방향을 고를 때만 쓴다. <b>읽기만 한다.</b> */
	private static @Nullable Vec3 bodyOf(@Nullable EnderDragon dragon) {
		if (dragon == null || !dragon.isAlive()) {
			return null;
		}
		return dragon.position();
	}

	// ------------------------------------------------------------------ 자리를 놓는다

	/**
	 * 월드를 모르는 포격. 높이는 전부 0 이다.
	 *
	 * <p>지면을 읽는 부분과 <b>기하</b>를 갈라 둔다. 「원이 열 개인가」·「선 위에 고르게 놓였는가」·
	 * 「원끼리 겹치지 않는가」는 월드 없이 답이 정해지는 계산이라, 떼어 두면 서버를 띄우지 않고
	 * 시험할 수 있다.
	 *
	 * @param angleRoll 0~1 의 굴림. 각도가 된다. 실전에서는 {@code end.getRandom()} 이 준다
	 */
	static Barrage plan(long cycle, double angleRoll) {
		Vec3 axis = axisFor(angleRoll);
		Vec3 from = axis.scale(-TrialRisks.ARENA_RADIUS);
		Vec3 to = axis.scale(TrialRisks.ARENA_RADIUS);
		List<Vec3> shells = new ArrayList<>(SHELL_COUNT);
		for (int index = 0; index < SHELL_COUNT; index++) {
			shells.add(shellAt(from, to, index));
		}
		return new Barrage(cycle, from, to, shells, NOT_STARTED);
	}

	/**
	 * 그 포격을 실제 지면에 얹는다.
	 *
	 * <p>높이를 <b>놓을 때 한 번만</b> 찾는다. 매 틱 하이트맵을 열 번 두드리면 11초 내내 청크를
	 * 뒤지게 되고, 어차피 포격이 도는 동안 지면은 바뀌지 않는다 — 이 패시브는 블록을 한 칸도
	 * 건드리지 않으니 더욱 그렇다.
	 */
	private static Barrage onGround(ServerLevel end, Barrage flat) {
		double base = groundY(end, 0.0, 0.0, FALLBACK_GROUND_Y);
		return new Barrage(flat.cycle(),
				lift(end, flat.from(), base),
				lift(end, flat.to(), base),
				liftAll(end, flat.shells(), base),
				flat.startedAt());
	}

	private static List<Vec3> liftAll(ServerLevel end, List<Vec3> points, double base) {
		List<Vec3> lifted = new ArrayList<>(points.size());
		double carried = base;
		for (Vec3 point : points) {
			Vec3 on = lift(end, point, carried);
			carried = on.y;
			lifted.add(on);
		}
		return lifted;
	}

	/**
	 * 그 점을 지면 높이에 얹는다.
	 *
	 * <p>바닥에서 띄우는 몫은 얹지 않는다. 고리를 그리는 {@link TrialWarning#markGround} 가 제
	 * 몫으로 0.15 를 더하므로 여기서 또 더하면 <b>두 번 뜬다</b>. 게다가 {@link #liftAll} 이
	 * 직전 높이를 다음 점의 대체값으로 넘기므로, 더해 둔 값을 물려주면 섬이 끊긴 구간에서 고리가
	 * 조금씩 떠오른다.
	 */
	private static Vec3 lift(ServerLevel end, Vec3 point, double fallback) {
		return new Vec3(point.x, groundY(end, point.x, point.z, fallback), point.z);
	}

	/** 그 자리의 지면 높이. 허공이면 하이트맵이 월드 바닥을 돌려주므로 넘겨받은 값을 쓴다. */
	private static double groundY(ServerLevel end, double x, double z, double fallback) {
		BlockPos ground = end.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
				BlockPos.containing(x, 0.0, z));
		return ground.getY() > end.getMinY() ? ground.getY() : fallback;
	}

	// ------------------------------------------------------------------ 월드 없이 도는 계산

	/**
	 * 굴림 하나를 아레나를 가르는 방향으로 바꾼다. 길이 1 이고 높이는 0 이다.
	 *
	 * <p>반 바퀴({@code π})만 쓴다. 선은 <b>양방향</b>이라 θ 와 θ+π 가 같은 선이고, 한 바퀴를 다
	 * 쓰면 같은 선이 두 번 나오는 셈이다. 어느 끝부터 터질지는 굴림이 아니라
	 * {@link #startingNear} 가 드래곤을 보고 마지막에 정한다.
	 */
	static Vec3 axisFor(double angleRoll) {
		double angle = Math.max(0.0, Math.min(1.0, angleRoll)) * Math.PI;
		return new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
	}

	/**
	 * 선 위 {@code index} 번째 원의 중심.
	 *
	 * <p>끝점이 아니라 <b>구간의 한가운데</b>에 놓는다. 선을 열 토막으로 잘라 각 토막의 중심을
	 * 쓰면 원 사이가 정확히 {@link #shellGap} 으로 고르고, 양 끝 원도 아레나 경계 안에 온전히
	 * 들어간다. 끝을 경계에 딱 맞추면 간격이 {@code 길이 / (개수 - 1)} 로 벌어져 사람이 말한
	 * 「선 길이를 개수로 나눈 값」과 어긋난다.
	 */
	static Vec3 shellAt(Vec3 from, Vec3 to, int index) {
		int clamped = Math.max(0, Math.min(SHELL_COUNT - 1, index));
		double along = (clamped + 0.5) / SHELL_COUNT;
		return from.add(to.subtract(from).scale(along));
	}

	/**
	 * 이웃한 두 원의 중심 사이 거리(칸).
	 *
	 * <p><b>이 값이 {@code TrialRisks.spotMinGap(SHELL_RADIUS)} 보다 커야 한다.</b> 그렇지 않으면
	 * 겹침 구역이 생기고 거기 선 사람은 한 틱에 두 번 맞는다 — 「낙뢰」가 겪은 그 사고다. 개수나
	 * 반경에서 직접 뽑으므로 어느 쪽을 고쳐도 따라온다.
	 */
	static double shellGap() {
		if (SHELL_COUNT <= 0) {
			return 0.0;
		}
		return LINE_LENGTH / SHELL_COUNT;
	}

	/**
	 * 화염구가 드래곤 쪽 끝 원부터 터지게 선의 방향을 맞춘다.
	 *
	 * <p><b>드래곤을 선에 맞추는 것이 아니라 선을 드래곤에 맞춘다.</b> 드래곤을 옮기면 몸이 사람을
	 * 때리고 블록을 부수지만(클래스 설명 참조), 선은 우리 것이라 공짜로 돌릴 수 있다. 「바깥부터」가
	 * 드래곤 쪽이 되면 <b>「드래곤이 저기서부터 던진다」가 저절로 읽힌다.</b>
	 *
	 * <p>원 <b>목록도 함께 뒤집는다.</b> 목록 순서가 곧 터지는 순서이므로 여기를 빠뜨리면 선만
	 * 뒤집히고 포격은 여전히 반대편에서 시작한다 — 눈에는 드래곤 반대편에서 불이 나는 것으로 보인다.
	 * 자리 자체는 좌우 대칭이라 하나도 움직이지 않는다.
	 *
	 * <p>높이는 보지 않는다. 드래곤은 아레나보다 수십 칸 위를 날아서, 세로를 섞으면 양 끝이 똑같이
	 * 멀어져 굴림이 사실상 방향을 정하게 된다.
	 *
	 * @param breather 드래곤 자리. {@code null} 이면 굴림이 정한 방향을 그대로 둔다
	 */
	static Barrage startingNear(Barrage run, @Nullable Vec3 breather) {
		if (breather == null) {
			return run;
		}
		if (flatDistanceSqr(breather, run.from()) <= flatDistanceSqr(breather, run.to())) {
			return run;
		}
		List<Vec3> reversed = new ArrayList<>(run.shells());
		Collections.reverse(reversed);
		return new Barrage(run.cycle(), run.to(), run.from(), reversed, run.startedAt());
	}

	/** 위에서 본 거리의 제곱. 높이를 버린다. */
	static double flatDistanceSqr(Vec3 one, Vec3 other) {
		double dx = one.x - other.x;
		double dz = one.z - other.z;
		return dx * dx + dz * dz;
	}

	/** {@code index} 번째 원이 터지는 시각. 첫 원이 터진 틱부터 센다. */
	static int blastTick(int index) {
		return Math.max(0, index) * BLAST_INTERVAL_TICKS;
	}

	/**
	 * 이번 틱에 터지는 원. 없으면 -1.
	 *
	 * <p><b>값을 하나만 돌려준다는 것이 이 패턴의 안전장치다.</b> 목록을 돌려주게 고치는 순간
	 * 한 틱에 여러 개가 터질 수 있게 되고, 열 개면 60 으로 팀 체력 20 을 세 배 넘는다.
	 */
	static int blastingIndex(long startedAt, long now) {
		if (startedAt == NOT_STARTED || BLAST_INTERVAL_TICKS <= 0) {
			return -1;
		}
		long offset = now - startedAt;
		if (offset < 0L || offset % BLAST_INTERVAL_TICKS != 0L) {
			return -1;
		}
		long index = offset / BLAST_INTERVAL_TICKS;
		return index < SHELL_COUNT ? (int) index : -1;
	}

	/**
	 * 지금까지 터진 원의 수. 이번 틱에 터지는 것을 포함한다.
	 *
	 * <p>고리를 어디부터 그릴지가 이 값에서 나온다. 포함하는 것이 중요하다 — 터진 원의 고리는
	 * <b>그 틱에</b> 사라져야 「저기는 이제 안전하다」가 읽힌다.
	 */
	static int blownCount(long startedAt, long now) {
		if (startedAt == NOT_STARTED || BLAST_INTERVAL_TICKS <= 0) {
			return 0;
		}
		long offset = now - startedAt;
		if (offset < 0L) {
			return 0;
		}
		return (int) Math.min(SHELL_COUNT, offset / BLAST_INTERVAL_TICKS + 1L);
	}

	/**
	 * 지금 하늘을 날고 있는 화염구가 몇 번째 원의 것인가. 없으면 -1.
	 *
	 * <p>{@link #FLIGHT_TICKS} 가 {@link #BLAST_INTERVAL_TICKS} 와 같으므로 <b>언제나 정확히 한
	 * 발</b>이다. 앞 발이 터진 그 틱에 다음 발이 떠난다.
	 */
	static int flyingIndex(long startedAt, long now) {
		if (startedAt == NOT_STARTED || BLAST_INTERVAL_TICKS <= 0) {
			return -1;
		}
		long offset = now - startedAt;
		if (offset < 0L) {
			return -1;
		}
		long next = offset / BLAST_INTERVAL_TICKS + 1L;
		return next < SHELL_COUNT ? (int) next : -1;
	}

	/** 날고 있는 화염구가 어디까지 왔는가. 0 이 드래곤 입, 1 이 원. */
	static double flightProgress(long startedAt, long now) {
		if (startedAt == NOT_STARTED || BLAST_INTERVAL_TICKS <= 0) {
			return 0.0;
		}
		long offset = now - startedAt;
		if (offset < 0L) {
			return 0.0;
		}
		return (double) (offset % BLAST_INTERVAL_TICKS) / BLAST_INTERVAL_TICKS;
	}

	/**
	 * 예고 구간에서 첫 화염구가 어디까지 왔는가.
	 *
	 * <p>남은 틱으로 센다. <b>도착이 곧 발동</b>이어야 첫 원이 터지는 틱과 화염구가 닿는 틱이
	 * 같아지고, 그래야 「던진 것이 떨어져 터졌다」로 읽힌다.
	 */
	static double approachProgress(int remainingTicks) {
		if (FLIGHT_TICKS <= 0) {
			return 1.0;
		}
		if (remainingTicks >= FLIGHT_TICKS) {
			return 0.0;
		}
		if (remainingTicks <= 0) {
			return 1.0;
		}
		return (double) (FLIGHT_TICKS - remainingTicks) / FLIGHT_TICKS;
	}

	/**
	 * 포격이 아직 돌고 있는가. 첫 원이 터진 틱부터 마지막 원이 터지는 틱까지다.
	 *
	 * <p>마지막 원이 터지는 틱을 <b>포함</b>한다. 그 틱에 버리면 마지막 폭발이 나가지 않는다.
	 * 그리고 그 다음 틱부터는 <b>아무것도 남지 않는다</b> — 잔류가 없다는 말이 여기서는
	 * 「끝나는 시각이 마지막 폭발과 같다」로 나타난다.
	 */
	static boolean running(long startedAt, long now) {
		if (startedAt == NOT_STARTED) {
			return false;
		}
		long offset = now - startedAt;
		return offset >= 0L && offset <= BARRAGE_TICKS;
	}

	/** 한 틱에 바닥 표식으로 나가는 점 수. 「상한 안인가」를 숫자로 묻는 값이다. */
	static int markPoints() {
		return SHELL_COUNT * TrialWarning.ringPoints(SHELL_RADIUS);
	}

	/**
	 * 꼬리에 찍을 점 수.
	 *
	 * <p>드래곤까지의 거리는 매 순간 다르다. 간격만 정하고 두면 멀리 있을 때 점이 수십 개 나가는데,
	 * 상한이 있으면 먼 궤적은 성겨질 뿐 선은 그대로 읽힌다.
	 */
	static int trailSamples(double travelled) {
		if (!(travelled > 0.0)) {
			return 0;
		}
		return Math.min(TRAIL_MAX_POINTS, (int) Math.floor(travelled / TRAIL_STEP));
	}

	/** 그 길이의 꼬리에서 실제로 벌어지는 점 사이 거리(칸). 「선으로 읽히는가」를 숫자로 묻는다. */
	static double trailGap(double travelled) {
		if (!(travelled > 0.0)) {
			return 0.0;
		}
		int points = trailSamples(travelled);
		if (points <= 0) {
			return travelled;
		}
		return travelled / points;
	}

	/**
	 * 그 비율일 때 화염구가 있는 자리.
	 *
	 * <p>비율을 잘라 두는 것은 안전장치가 아니라 <b>규칙</b>이다. 1 을 넘겨 그리면 화염구가 원을
	 * 지나쳐 날아가고, 그 뒤에 터지면 사람들은 「지나간 자리」에서 폭발을 본다.
	 */
	static Vec3 headAt(Vec3 from, Vec3 to, double progress) {
		double along = Math.max(0.0, Math.min(1.0, progress));
		return from.add(to.subtract(from).scale(along));
	}

	/**
	 * 한 사람이 이 패시브에게서 <b>한 틱에</b> 받을 수 있는 가장 큰 피해.
	 *
	 * <p>「즉사 메커닉 0개」가 지켜지는지를 값에서 직접 계산해 둔다. 값을 올리는 사람은 피해만 보고
	 * 한 틱에 몇 개가 터지는지는 보지 않는다.
	 */
	static float worstCaseTickDamage() {
		if (DAMAGE_PER_BLAST <= 0.0F || MAX_CONCURRENT_BLASTS <= 0) {
			return 0.0F;
		}
		return DAMAGE_PER_BLAST * MAX_CONCURRENT_BLASTS;
	}

	/**
	 * <b>선을 따라 도망친 사람이 연달아 맞는 원의 수.</b>
	 *
	 * <h2>왜 이 계산이 필요한가</h2>
	 *
	 * <p>순차가 「한 틱에 한 번」은 보장하지만 <b>연달아 맞는 것</b>은 막지 못한다. 그것이 이
	 * 패턴의 긴장이므로 막을 생각도 없다 — 다만 <b>몇 발까지인지는 알고 있어야</b> 팀 체력 20 과
	 * 견줄 수 있다.
	 *
	 * <p>가장 많이 맞는 길은 <b>포격이 오는 방향으로 등을 돌리고 선을 따라 도망치는 것</b>이다.
	 * 마주 보고 달리거나 제자리에 서 있으면 원과의 거리가 한 발마다 {@link #shellGap} 이상 벌어져
	 * 한 발로 끝나지만, 같은 방향으로 도망치면 그 차이가 <b>{@code 간격 - 속도 × 0.6초}</b> 로
	 * 줄어든다. 폭이 지름 {@code 2R} 이므로 그 사이에 들어오는 발이 답이다.
	 *
	 * <p>지금 값에서는 걷기(4.317)·달리기(5.612)·달리며 뛰기(7.13) 모두 <b>두 발</b>이다. 세 발이
	 * 되려면 7.5칸/초를 넘어야 하고, 포격 자체가 초당 13.3칸으로 전진하므로 그쯤 되면 사람이
	 * 포격을 <b>타고 가는</b> 셈이다. 신속 물약으로 그 속도가 나오면 발 수가 늘어나는데, 그때는
	 * 스스로 고른 것이라 「대응 불가」가 아니다.
	 *
	 * @param blocksPerSecond 도망치는 속도. 바닐라 걷기는 4.317, 달리기는 5.612 다
	 */
	static int chainHits(double blocksPerSecond) {
		if (SHELL_COUNT <= 0 || BLAST_INTERVAL_TICKS <= 0) {
			return 0;
		}
		double perBlast = Math.max(0.0, blocksPerSecond) * BLAST_INTERVAL_TICKS / 20.0;
		double closing = shellGap() - perBlast;
		if (closing <= 1.0E-9) {
			// 포격만큼 빠르면 원을 끼고 함께 달리는 셈이라 전부 맞는다.
			return SHELL_COUNT;
		}
		int hits = (int) Math.floor(SHELL_RADIUS * 2.0 / closing) + 1;
		return Math.max(1, Math.min(SHELL_COUNT, hits));
	}

	/**
	 * 그 속도로 도망치는 동안 팀이 받는 피해.
	 *
	 * <p><b>개수·반경·간격·피해를 잇는 한 손잡이다.</b> 넷 중 무엇을 올려도 여기에 나타나므로,
	 * 값을 고치는 사람은 이 값을 보고 {@link #DAMAGE_PER_BLAST} 를 함께 내려야 한다.
	 */
	static float chainDamage(double blocksPerSecond) {
		return chainHits(blocksPerSecond) * DAMAGE_PER_BLAST;
	}
}
