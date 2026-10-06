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
 * 드래곤 기본 패시브 「연쇄 포격」 — 아레나를 가로지르는 빨간 원 열 개짜리 줄 <b>둘</b>이 드래곤
 * 쪽 끝부터 같은 박자로 차례로 터진다.
 *
 * <h2>무엇이 보이는가</h2>
 *
 * <ol>
 *   <li><b>예고 4초</b> — 아레나를 가로지르는 선 <b>두 줄</b> 위에 빨간 원 <b>스무 개가
 *       한꺼번에</b> 뜬다. 어디가 위험한지 전부 미리 읽힌다. 한 줄 안의 원끼리는 닿지도 않는다</li>
 *   <li><b>포격 3.6초</b> — 드래곤이 <b>두 줄 모두 제가 있는 쪽 끝 원부터</b> 화염구를 던지고,
 *       두 줄의 원이 0.4초 간격으로 <b>줄마다 하나씩, 같은 박자에</b> 터진다</li>
 *   <li><b>끝</b> — 마지막 원이 터지면 그것으로 끝이다. <b>아무것도 남지 않는다</b></li>
 * </ol>
 *
 * <h2>빠르게, 두 줄로 (2026-10-04)</h2>
 *
 * <p>사람 말: <b>「드래곤 일반패턴에 1자로 폭발시키는 거 지금보다 터지는 속도가 50프로
 * 빨라지게 하고 1자만 긋는 게 아니라 랜덤으로 한 줄 더 그어 한번에 2줄씩 터지게」</b>, 이어서
 * <b>「간격도 0.4초이고 그 원표식이 생기고 50프로 더 빨리 떨어지게」</b>.
 *
 * <ul>
 *   <li>예고 6초 → <b>4초</b>({@link #LEAD_TICKS} 120 → 80), 간격 0.6초 → <b>0.4초</b>
 *       ({@link #BLAST_INTERVAL_TICKS} 12 → 8). 둘 다 「50프로 빨리」를 시간 ÷ 1.5 로 읽은 값이다</li>
 *   <li>둘째 줄은 첫 줄과 <b>{@link #MIN_LINE_ANGLE_DEGREES}° 이상</b> 벌어진 무작위 방향이다.
 *       까닭은 그 상수의 설명에 있다</li>
 *   <li>⚠ <b>두 줄은 언제나 중앙에서 교차한다</b>(둘 다 지름이다). 그 자리의 같은 박자 원 둘은
 *       각도가 몇이든 겹친다 — 그래서 <b>한 박자에 한 사람은 한 번만</b> 맞는다({@link #victims})</li>
 *   <li>⚠ 0.4초는 <b>바닐라 피격 쿨타임 10틱보다 짧다.</b> 그대로 두면 연달아 맞은 두 번째 원이
 *       쿨타임에 먹히므로, <b>포격만 쿨타임을 무시하고 들어간다</b>({@link #strike}). 사람이
 *       2026-10-04 에 「포격 무시」를 골랐다</li>
 * </ul>
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
 * <h2>한 줄 안의 원끼리는 겹치지 않는다</h2>
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
 * <b>한 틱에 줄마다 정확히 하나만 터진다</b>({@link #blastingIndex} 는 언제나 값 하나만
 * 돌려주고, 두 줄이 그 값을 함께 쓴다). 한 틱에 터지는 원은 {@link #MAX_CONCURRENT_BLASTS} =
 * 줄 수 = 2 이고, {@link #worstCaseTickDamage} 가 그 곱셈의 상대다.
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
 * <p><b>대신 선을 드래곤에 맞춘다.</b> 첫 화염구가 떠나는 틱에 {@link #startingNear} 가 <b>줄마다</b>
 * 방향을 뒤집어 <b>드래곤이 있는 쪽 끝 원부터</b> 터지게 한다. 예고 동안 보여 준 것은 좌우
 * 대칭인 원 열 개짜리 줄이라 방향을 마지막에 정해도 사람이 본 것은 하나도 바뀌지 않는다. 그리고
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
	 * 80 + 72 = 152 라 648틱이 남는다.
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
	 * 원이 다 보이고 나서 첫 원이 터질 때까지(틱). <b>4초.</b>
	 *
	 * <h2>⚠ 2026-10-04 에 120 → 80 — 정확히 하한이다</h2>
	 *
	 * <p>사람이 2026-10-04 에 「그 원표식이 생기고 50프로 더 빨리 떨어지게」라고 했다. 6초 ÷ 1.5 =
	 * 4초이고, 그것이 아래에 적힌 하한 {@link TrialWarning#TICKS_SCATTER}(80) 과 <b>정확히 같다.</b>
	 * 더 줄이는 사람은 「흩어지기」 하한을 깨는 것이므로 여기서 멈출 것 — {@link #MIN_LEAD_TICKS} 도
	 * 같은 값이라, 이 아래로 내리면 <b>모든 주기가 건너뛰어져 포격이 영영 안 나온다.</b>
	 *
	 * <p>잃은 것이 둘이다. ① 아래 「100」 줄의 「뭔가 온다」 층이 따로 앞서 나오지 않는다 — 그 층은
	 * 이제 <b>원이 뜨는 틱에 함께</b> 울린다({@link #warn} 이 {@code stageJustChanged(남은 틱,
	 * LEAD_TICKS)} 로 첫 틱을 층이 바뀐 틱으로 센다). ② 첫 화염구가 예고의 마지막 0.4초에 뜨므로
	 * 화염구 없는 조용한 예고는 72틱이다. 원은 그 동안에도 그대로 보이므로 「예고가 짧아졌다」는
	 * 아니다.
	 *
	 * <h2>6초였던 까닭 (지금은 사람이 뒤집었다)</h2>
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
	static final int LEAD_TICKS = 80;
	/**
	 * 원이 하나씩 터지는 간격(틱). <b>0.4초.</b>
	 *
	 * <p>사람이 2026-10-04 에 「간격도 0.4초」라고 정했다(0.6초 → 0.4초, 12 → 8).
	 *
	 * <p>순차가 이 컨셉의 안전장치이므로 <b>0 이 되면 안 된다.</b>
	 *
	 * <h2>⚠ 바닐라 피격 쿨타임 10틱보다 짧다 — 그래서 포격만 쿨타임을 무시한다</h2>
	 *
	 * <p>전에는 「10틱보다 커야 한다」가 여기 규칙이었다. 사람이 정한 0.4초가 그것을 깬다.
	 * 26.3 {@code LivingEntity.hurtServer} 는 {@code damageCooldownTime > 10} 이면 직전 피해보다 큰
	 * 몫만 넣고, 온전히 들어간 피해는 그 값을 20 으로 채운다. 사람 쪽은 {@code ServerPlayer.tick}
	 * 이 틱마다 1 씩 줄인다(바이트코드에서 확인했다 — {@code LivingEntity.baseTick} 은
	 * {@code ServerPlayer} 를 건너뛴다). 그래서 8틱 뒤에는 12 가 남아 <b>같은 23 은 통째로
	 * 버려진다.</b> 선을 따라 도망친 사람이 원 둘에 걸려도 한 발(무장 6.77)만 들어갔다.
	 *
	 * <p>그 사실을 듣고 사람이 2026-10-04 에 <b>「포격 무시」</b>를 골랐다 — 연쇄 포격의 피해만
	 * 피격 쿨타임을 무시하고 들어간다. 다른 피해원(몹·카드·결계)의 쿨타임은 그대로다. 어떻게
	 * 무시하고 무엇을 되돌리는지는 {@link #strike} 에 있다. 이제 걸린 원은 <b>전부 들어간다</b>
	 * ({@link #landedChainHits}).
	 *
	 * <p>열 개면 터지는 데 {@link #BARRAGE_TICKS} = 72틱(3.6초)이 걸린다.
	 */
	static final int BLAST_INTERVAL_TICKS = 8;
	/**
	 * 바닐라 피격 쿨타임의 문턱(틱). 온전히 맞은 뒤 이만큼 지나야 같은 크기의 피해가 다시 들어간다.
	 *
	 * <p>26.3 {@code hurtServer} 의 {@code damageCooldownTime > 10.0F} 비교다(쿨타임은 20 에서
	 * 시작해 틱마다 1 씩 준다). 값이 아니라 <b>바닐라가 정한 사실</b>을 적어 둔 것이라 고칠 대상이
	 * 아니다.
	 */
	static final int VANILLA_DAMAGE_COOLDOWN_TICKS = 10;
	/**
	 * 온전히 맞은 피해가 채우는 피격 쿨타임(틱). 26.3 {@code hurtServer} 248번째의
	 * {@code bipush 20; putfield damageCooldownTime} 이다.
	 *
	 * <p>위 문턱과 같이 <b>바닐라가 정한 사실</b>이다. {@link #strike} 가 「포격이 실제로 맞았는가」를
	 * 이 값과 {@link #STRIKE_COOLDOWN_TICKS} 가 다르다는 것으로 가려낸다.
	 */
	static final int VANILLA_FRESH_COOLDOWN_TICKS = 20;
	/**
	 * 포격을 넣기 직전에 피격 쿨타임을 지워 두는 값(틱). <b>0.</b>
	 *
	 * <p>문턱({@link #VANILLA_DAMAGE_COOLDOWN_TICKS}) 이하면 무엇이든 바닐라를 「새로 맞음」 갈래로
	 * 보내지만 0 이어야 하는 까닭이 따로 있다 — 26.3 에서 서버 쪽 {@code hurtServer} 호출 사이에
	 * {@code damageCooldownTime} 에 0 을 쓰는 곳이 <b>하나도 없다</b>. 그래서 호출 뒤에도 0 이면
	 * 「바닐라가 이 피해를 받아들이지 않았다」가 확실하다({@link #cooldownAfterStrike}).
	 */
	static final int STRIKE_COOLDOWN_TICKS = 0;
	/**
	 * 화염구 하나가 드래곤 입에서 원까지 날아가는 시간(틱).
	 *
	 * <p>{@link #BLAST_INTERVAL_TICKS} 와 <b>같은 값</b>인 것이 중요하다. 그러면 앞 화염구가
	 * 터지는 바로 그 틱에 다음 화염구가 떠나 <b>하늘에 언제나 정확히 한 발</b>만 있다. 길면 여러
	 * 발이 동시에 날아 「어느 것이 다음인가」가 안 읽히고, 짧으면 던지는 장면 없이 원이 터진다.
	 *
	 * <p>첫 발만 예고 구간 안에서 난다 — 예고 <b>마지막 0.4초</b>다. 그 동안에도 원은 그대로
	 * 보이므로 예고 길이는 {@link #LEAD_TICKS} 그대로다.
	 *
	 * <p>줄이 둘이라 하늘에는 <b>줄마다 한 발, 두 발</b>이 함께 난다. 둘 다 드래곤 입에서 떠나 같은
	 * 박자로 닿으므로 「어느 것이 다음인가」는 여전히 한눈에 읽힌다.
	 */
	static final int FLIGHT_TICKS = BLAST_INTERVAL_TICKS;
	/** 첫 원이 터진 뒤 마지막 원이 터질 때까지(틱). 값이 아니라 위 둘에서 나오는 결과다. */
	static final int BARRAGE_TICKS = (SHELL_COUNT - 1) * BLAST_INTERVAL_TICKS;

	/**
	 * 원 하나가 터질 때 팀에게 들어가는 피해.
	 *
	 * <h2>⚠ 이 값은 <b>완전무장</b>을 전제로 다시 잡혔다 — 8 → 6 → 23 이다</h2>
	 *
	 * <p>출발점은 8 이었고 값에서 세어 보고 6 으로 내렸다. 그 두 숫자는 모두 <b>맨몸 날값</b>으로
	 * 팀 체력 20 과 견준 것이었다. 그런데 사람이 실제로 플레이하고 「다이아셋 + 보호 인챈트까지
	 * 하고 맞는 것까지 고려해야 한다」고 정했다 — 다이아 풀셋(방어 20 · 강도 8) + 보호 IV 네
	 * 곳(EPF 16)을 지나면 <b>6 이 1.8 로 들어와</b> 원 하나가 아무 무게도 없었다.
	 *
	 * <p>기준은 시련 카드와 같다 — 사람이 정한 <b>「큰자리는 3대 맞으면 죽는거로 생각하자」</b>,
	 * 곧 무장 기준 한 대에 <b>6.67</b> 이다. 이 패시브는
	 * {@code damageSources().explosion(null, null)} 을 쓰고 {@code explosion} 의
	 * {@code scaling} 이 {@code always} 라 <b>하드에서 1.5배가 먼저</b> 걸리므로, 그 6.67 을
	 * 만드는 적힌 값이 <b>23</b> 이다({@code 23 × 1.5 = 34.5} → 방어·보호를 지나 6.77).
	 * 감쇠 식과 그 근거는 {@code TrialCatalog} 의 카드 목록 설명에 한 벌 적혀 있다.
	 *
	 * <p>그 값으로 다시 세어 본 판들이다. <b>괄호 안이 무장 기준</b>이다.
	 *
	 * <ul>
	 *   <li><b>한 틱</b> — 줄마다 하나씩 터지므로 팀에게는 많아야 두 발, 46(13.5)이다. 한 사람은
	 *       한 박자에 한 번만 맞으므로({@link #victims}) 두 발은 <b>서로 다른 두 사람이 각자 다른
	 *       줄의 원 안에 있던</b> 경우뿐이다. 팀 체력의 6할 7푼이고 세 발에는 못 미친다</li>
	 *   <li><b>옆으로 비킨 사람</b> — 한 줄 안의 원끼리 겹치지 않으므로 제자리에 서 있어도 맞는
	 *       것은 <b>한 발</b>이다. 23(6.77). 중앙에서는 두 줄이 겹치지만 같은 박자의 두 원은 한
	 *       번으로 센다</li>
	 *   <li><b>선을 따라 도망친 사람</b> — 포격이 8칸씩 0.4초마다 전진하는데 달리기는 0.4초에
	 *       2.2칸이라 따라잡힌다. {@link #chainHits} 로 세면 원 <b>두 개</b>에 걸리고, 포격은 피격
	 *       쿨타임을 무시하므로({@link #strike}) <b>두 발 다 들어간다</b>. 46(13.5) — 팀 체력의
	 *       6할 7푼이다. 0.4초 간격만 놓고 보면 둘째 원이 바닐라 쿨타임에 먹혀 한 발(6.77)이었는데,
	 *       사람이 2026-10-04 에 「포격 무시」를 골라 0.6초 시절의 「6할」로 돌아왔다</li>
	 *   <li><b>넷이 서로 다른 원에 하나씩 서 있던 판</b> — 무장 기준 27 로 전멸이다. 원을 전부
	 *       보여 준 뒤의 <b>네 사람이 각각 실패한</b> 경우이고, 줄이 둘이라 두 사람씩 같은 박자에
	 *       들어올 수 있다. 그래도 한 틱에 오는 것은 많아야 두 발이다</li>
	 *   <li>⚠ <b>죽어서 장비를 잃고 돌아온 사람</b> — 감쇠가 하나도 안 걸려 <b>34.5 가 그대로</b>
	 *       들어간다. 팀 체력이 20 이라 <b>한 발에 전멸</b>이다. 사람이 그 사실을 듣고도 「3대」로
	 *       가자고 했으므로 되돌리지 말 것 — 다만 「즉사 메커닉 0개」는 이제 <b>무장 기준의
	 *       약속</b>이다</li>
	 * </ul>
	 *
	 * <p><b>개수나 반경을 올리는 사람은 여기를 함께 내려야 한다.</b> 간격이 좁아지면 도망치다
	 * 걸리는 발 수가 늘고, 그것이 {@link #chainHits} 에 곧바로 나타난다.
	 */
	static final float DAMAGE_PER_BLAST = 23.0F;
	/**
	 * 한 번에 긋는 줄의 수.
	 *
	 * <p>사람이 2026-10-04 에 「1자만 긋는 게 아니라 랜덤으로 한 줄 더 그어 한번에 2줄씩 터지게」
	 * 라고 정했다.
	 */
	static final int LINE_COUNT = 2;
	/**
	 * 두 줄이 이루는 각의 하한(도). <b>45°.</b>
	 *
	 * <h2>왜 45° 인가</h2>
	 *
	 * <p>두 줄은 언제나 중앙에서 만난다. 같은 박자에 터지는 두 원(둘 다 {@code k} 번째)은 중앙에서
	 * 같은 거리 {@code |s|} 에 있으므로 그 사이가 {@code 2|s|·sin(θ/2)} 다(θ 는 두 줄 사이 각).
	 * 지름 7 보다 멀어야 같은 박자 원끼리 안 닿는다.
	 *
	 * <ul>
	 *   <li>중앙 둘({@code |s| = 4}, 4·5 번째) — {@code 8·sin(θ/2)} 가 7 을 넘으려면 θ > 122° 라
	 *       <b>줄 사이 각(최대 90°)으로는 못 피한다.</b> 그 자리는 {@link #victims} 의 「한 박자에 한
	 *       사람 한 번」이 맡는다</li>
	 *   <li>그 바깥 둘({@code |s| = 12}) — {@code 24·sin(θ/2) > 7} 이 <b>θ > 33.9°</b> 다. 이것이
	 *       「겹침이 중앙 둘에서만 생긴다」의 경계다</li>
	 * </ul>
	 *
	 * <p>33.9° 에 바로 붙이면 바깥 원 사이가 0 에 가깝게 붙어 「두 줄」이 아니라 「굵은 한 줄」로
	 * 읽히므로 여유를 둬 <b>45°</b>. 그때 바깥 둘 사이가 9.18칸이라 틈이 2칸 남는다. 둘째 줄의 각은
	 * 첫 줄에서 {@code [45°, 135°]} 사이로 고르게 뽑으므로 두 줄 사이 각은 언제나 45°~90° 다.
	 */
	static final double MIN_LINE_ANGLE_DEGREES = 45.0;
	/**
	 * 한 틱에 터질 수 있는 원의 수.
	 *
	 * <p>순차가 이 컨셉의 안전장치라는 말을 숫자로 적은 것이다. {@link #blastingIndex} 가 값 하나만
	 * 돌려주고 두 줄이 그 값을 함께 쓰므로 <b>줄 수와 같다.</b> 2026-10-04 에 줄이 둘이 되며
	 * 1 → 2 가 됐다.
	 *
	 * <p>⚠ <b>2 는 「팀이 한 틱에 두 번」이지 「한 사람이 두 번」이 아니다.</b> 두 줄이 만나는
	 * 중앙에서 같은 박자의 두 원이 겹치는데, 그 겹친 자리에 선 사람은 {@link #victims} 가 한 번만
	 * 세운다. 두 번째 원은 <b>다른 사람</b>이 그 원 안에 있을 때만 그 사람을 때린다.
	 */
	static final int MAX_CONCURRENT_BLASTS = LINE_COUNT;

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
	 * <p>점 하나가 패킷 한 장이고 원을 예고 내내 그린다. 상한이 없으면 개수나 반경을 올리는 순간
	 * 파티클만으로 틱이 밀린다.
	 *
	 * <p>500 인 근거는 <b>이 저장소가 이미 쓰고 있는 예산</b>이다. 「낙뢰」가 반경 3 짜리 고리 열
	 * 개를 동시에 띄우고 그것이 {@code TrialWarning.ringPoints(3.0) × 10 = 400} 점이다. 반경
	 * 3.5 고리는 44점이다.
	 *
	 * <p>⚠ 줄이 둘이 되며 원이 스무 개가 됐다. 매 틱 다 그리면 880 점이라 이 상한을 넘으므로
	 * {@link #MARK_STRIDE} 틱에 나눠 그린다 — 한 틱 308 점이다.
	 */
	static final int MARK_MAX_POINTS = 500;
	/**
	 * 고리를 몇 틱에 나눠 그리는가. <b>3.</b>
	 *
	 * <p>고리 하나를 통째로 {@code 3} 틱에 한 번씩 그리고, 고리마다 차례를 어긋나게 둬 한 틱에
	 * 스무 개 중 많아야 일곱 개만 나간다({@link #drawsRingAt}). 먼지 수명이 <b>최소 8틱</b>이라
	 * ({@code TrialWarning.markGround} 의 「stride 는 8보다 작아야 한다」) 3틱에 한 번이면 고리가
	 * 늘 두 겹 이상 살아 있다 — 매 틱 같은 자리에 다시 찍던 점은 원래 겹쳐 보이지 않았으므로 눈에
	 * 보이는 손해가 없다.
	 *
	 * <p>{@code TrialWarning} 의 점 단위 stride 를 쓰지 않은 것은 그 형태가 <b>색을 직접 받기</b>
	 * 때문이다. 이 원은 색 없는 {@code markGround} 만 불러 빨강 말고는 나올 수 없게 해 두었다
	 * ({@code 바닥_표식은_규약의_빨강_하나뿐이다}).
	 *
	 * <p>3 인 까닭은 한 틱 합계다 — 고리 308 + 화염구 두 발 90 + 착탄 두 곳 42 = <b>440</b> 으로
	 * 이 저장소의 한 틱 예산(400~440)의 위 끝에 맞는다({@link #worstTickPoints}). 2 면 고리만
	 * 440 이라 합계가 572 다.
	 */
	static final int MARK_STRIDE = 3;
	/** 화염구 머리 한 번에 찍는 불꽃 수. */
	static final int SHOT_HEAD_FLAMES = 10;
	/** 화염구 머리 한 번에 찍는 큰 연기 수. */
	static final int SHOT_HEAD_SMOKE = 3;
	/** 원 하나가 터질 때 찍는 큰 연기 수. 폭발 방출기 한 점이 따로 붙는다. */
	static final int BLAST_SMOKE = 20;

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
	 * <p>아레나도 드래곤도 하나뿐이고 <b>시련 세션도 한 번에 하나다</b>
	 * ({@code DragonTrialManager.trialHolder}). 열쇠를 둘 필요가 없어 칸 하나로 둔다.
	 * ⚠ 전에는 「이 모드는 팀이 하나다」라고만 적혀 있었는데 {@code singleTeamOnly} 를 끈 서버에서는
	 * 거짓이었다 — 시련 팀 둘이 다른 틱에 들어오면 {@code granted} 가 달라 {@link #beginFight} 가 매 틱
	 * 서로의 포격을 지워 <b>두 팀 다 포격이 영영 안 터졌다.</b> 그래서 둘째 시련 팀은 시련 끔으로 연다
	 * (2026-10-06 Orca 검토 F-verified 의 V2). 이 칸을 팀 열쇠 맵으로 바꾸는 것이 근본이지만 손이 크다.
	 * <b>{@link #clearState()} 로 반드시 비운다</b> — 지난 판의 좌표가 남으면 새 월드에서 아무도
	 * 모르는 자리가 터진다.
	 */
	private static @Nullable Volley active;

	/**
	 * 한 번의 포격 — <b>같은 주기, 같은 박자로 터지는 줄들</b>. 지금은 {@link #LINE_COUNT} 줄이다.
	 *
	 * <p>줄마다 주기와 시작 틱을 들고 있지만 언제나 같은 값이다. 한 줄만 따로 시작하게 두면
	 * 「같은 박자」가 깨지고, 그러면 한 박자에 한 번만 센다는 {@link #victims} 의 전제도 깨진다 —
	 * 그래서 시작은 {@link #startedAt(long)} 하나로만 바꾼다.
	 */
	record Volley(List<Barrage> lines) {

		Volley {
			lines = List.copyOf(lines);
			if (lines.isEmpty()) {
				throw new IllegalArgumentException("줄이 하나도 없는 포격");
			}
		}

		long cycle() {
			return lines.getFirst().cycle();
		}

		boolean started() {
			return lines.getFirst().started();
		}

		long startedAt() {
			return lines.getFirst().startedAt();
		}

		Volley startedAt(long tick) {
			List<Barrage> moved = new ArrayList<>(lines.size());
			for (Barrage line : lines) {
				moved.add(line.startedAt(tick));
			}
			return new Volley(moved);
		}
	}

	/**
	 * 한 줄의 포격.
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

		Volley run = active;
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
				run = onGround(end, planVolley(cycle, end.getRandom().nextDouble(),
						end.getRandom().nextDouble()));
				active = run;
			}
		}
		if (run == null) {
			return;
		}

		if (!run.started() && remaining == FLIGHT_TICKS) {
			// 첫 화염구가 떠나는 그 틱에 줄마다 방향을 정한다. 예고 내내 보여 준 것은 좌우
			// 대칭인 원 열 개짜리 줄이라, 여기서 뒤집어도 사람이 본 자리는 하나도 바뀌지 않는다.
			run = startingNear(run, bodyOf(dragon));
			active = run;
			// 소리는 한 번만 낸다. 두 줄 모두 드래곤 쪽 끝에서 떠나므로 같은 소리를 두 번 겹칠
			// 까닭이 없다.
			Vec3 from = run.lines().getFirst().from();
			end.playSound(null, from.x, from.y, from.z,
					SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 4.0F, 0.8F);
		}

		if (!run.started()) {
			// 터지는 틱에는 예고를 한 번 더 그리지 않는다. 같은 틱에 고리를 두 벌 보내면
			// 방금 터진 원이 한 틱 더 살아 있는 것으로 보인다 — 「터진 자리는 즉시 안전」이
			// 그 한 틱에서 먼저 깨진다.
			if (!TrialRisks.firesAt(now, granted, PERIOD_TICKS)) {
				warn(end, dragon, members, run, remaining, now);
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
	static @Nullable Volley active() {
		return active;
	}

	/** 시험용. 월드 없이 만든 포격을 꽂아 둔다. */
	static void remember(@Nullable Volley volley) {
		active = volley;
	}

	/**
	 * 시험용. 한 줄짜리 포격을 꽂아 둔다.
	 *
	 * <p>줄이 하나였던 때 쓰인 남의 시험({@code DragonPassivesTest})이 그대로 컴파일되게 남긴다.
	 * 상태가 비워지는가만 보는 자리라 줄 수는 상관없다.
	 */
	static void remember(Barrage line) {
		active = new Volley(List.of(line));
	}

	/** 시험용. 어느 주기까지 판단이 끝났는가. */
	static long plannedCycle() {
		return plannedCycle;
	}

	/** 시험용. 주기 판단 기록만 남긴다. */
	static void notePlanned(long cycle) {
		plannedCycle = cycle;
	}

	/**
	 * 다음 포격 — 패턴 타이머 HUD({@link TrialTimers})가 읽는다. <b>상태를 읽기만 한다.</b>
	 *
	 * <p>사건은 <b>첫 원이 터지는 틱</b>이다({@link #tick} 의 {@code firesAt}). 원 열 개가
	 * {@link #LEAD_TICKS} 앞에 한꺼번에 깔리므로 그것이 예고 길이다. 터지기 시작한 뒤
	 * {@link #BARRAGE_TICKS} 동안은 「진행 중」이다 — 원이 0.4초마다 하나씩 터지는 그 사이에 「다음
	 * 포격 40초」를 띄우면 지금 터지고 있는 줄을 HUD 가 모른 척하게 된다.
	 *
	 * <p>⚠ <b>건너뛴 주기를 본다.</b> 주기 판단은 예고 구간에 들어서는 틱에 한 번뿐이고
	 * ({@link #plannedCycle}), 그때 못 놓으면(재시작 직후 남은 예고가 {@link #MIN_LEAD_TICKS} 보다
	 * 짧았다 — 「예고를 절반만 내고 터지는」 길을 막은 자리) 그 주기는 통째로 안 온다. 주기만 다시
	 * 세면 HUD 가 아무것도 안 올 「0.0초」를 띄우므로, 이미 판단했는데 놓인 포격이 없으면 다음
	 * 주기로 넘긴다.
	 *
	 * @param granted 전투가 열린 틱({@link DragonTrialSession#startedTick()})
	 */
	static @Nullable TrialTimers.Clock clock(long granted, long now) {
		// 다른 판의 기록이면 아직 이 판의 첫 틱이 안 돈 것이다. 주기만으로 센다.
		boolean ours = plannedFight == granted;
		Volley run = ours ? active : null;
		if (run != null && run.started()) {
			long offset = now - run.startedAt();
			if (offset >= 0L && offset < BARRAGE_TICKS) {
				return TrialTimers.Clock.active(BARRAGE_TICKS - offset, BARRAGE_TICKS);
			}
		}
		int remaining = TrialRisks.ticksUntilFire(now, granted, PERIOD_TICKS);
		long cycle = TrialRisks.strikeIndex(now, granted, PERIOD_TICKS);
		boolean skipped = ours && remaining <= LEAD_TICKS && plannedCycle == cycle
				&& (run == null || run.cycle() != cycle);
		return TrialTimers.Clock.countdown(skipped ? remaining + PERIOD_TICKS : remaining,
				skipped ? remaining + PERIOD_TICKS : PERIOD_TICKS, LEAD_TICKS);
	}

	// ------------------------------------------------------------------ 예고

	/**
	 * 원을 한꺼번에 보여 주고 경고 세 층을 올린다.
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
	 * <p>소리는 <b>사람마다 그 자리에서, 그 사람에게만</b> 울린다
	 * ({@link TrialWarning#soundFor}). 바닐라 소리 사거리는 볼륨이 1 이하면 16칸인데 이 선은
	 * 80칸이라 한 점에서 울리면 반대편에 선 사람에게 닿지 않고, 그렇다고 사람 자리마다
	 * {@code TrialWarning.sound} 를 부르면 그쪽은 반경 안의 <b>전원</b>에게 나가므로 모여 있는
	 * 넷이 <b>각자 네 겹</b>으로 듣는다 — 한동안 그렇게 되어 있었다.
	 */
	private static void warn(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> members, Volley run, int remaining, long now) {
		markAll(end, run, 0, now);
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		// 예고가 LEAD_TICKS(80) 로 「뭔가 온다」 층(≤100) 안에서 시작하므로, 첫 틱을 층이 바뀐
		// 틱으로 센다. 그러지 않으면 그 층이 통째로 빠진다(TrialRisks.stageJustChanged 의 설명).
		if (stage != null && TrialRisks.stageJustChanged(remaining, LEAD_TICKS)) {
			for (ServerPlayer member : members) {
				TrialWarning.soundFor(end, member, stage);
			}
		}
		if (remaining <= FLIGHT_TICKS) {
			// 첫 화염구는 예고의 마지막 0.4초 안에서 난다. 줄마다 한 발.
			Vec3 mouth = mouthOf(dragon);
			for (Barrage line : run.lines()) {
				drawShot(end, mouth, line.shells().getFirst(), approachProgress(remaining));
			}
		}
	}

	/**
	 * 아직 안 터진 원만 빨간 고리로 그린다.
	 *
	 * <p><b>터진 원은 그 틱부터 지운다.</b> 이것이 「방금 터진 칸으로 들어간다」를 가르치는 유일한
	 * 신호다 — 고리가 남아 있으면 이미 안전한 자리가 위험해 보이고, 그러면 원 사이 1칸 틈으로만
	 * 빠지려 든다.
	 *
	 * <p>고리 하나는 {@link #MARK_STRIDE} 틱에 한 번 그린다({@link #drawsRingAt}). 점 예산 때문이다.
	 *
	 * @param from 이 번째부터 그린다. 예고 중에는 0, 포격 중에는 이미 터진 수
	 */
	private static void markAll(ServerLevel end, Volley run, int from, long now) {
		List<Barrage> lines = run.lines();
		for (int line = 0; line < lines.size(); line++) {
			List<Vec3> shells = lines.get(line).shells();
			for (int index = Math.max(0, from); index < shells.size(); index++) {
				if (!drawsRingAt(line * SHELL_COUNT + index, now)) {
					continue;
				}
				// TrialWarning.markGround 는 거리 제한을 끈 긴 형태로 보낸다. 아레나가 80칸이라
				// 짧은 형태면 반대편 원이 통째로 안 보인다.
				TrialWarning.markGround(end, shells.get(index), SHELL_RADIUS);
			}
		}
	}

	// ------------------------------------------------------------------ 포격

	/**
	 * 드래곤 쪽 끝부터 줄마다 하나씩, 같은 박자로 터뜨린다.
	 *
	 * <p>순서가 셋이다 — <b>터뜨리고, 남은 원을 다시 그리고, 다음 화염구를 날린다.</b> 터뜨리는
	 * 것이 먼저라야 방금 터진 원의 고리가 그 틱에 사라진다.
	 */
	private static void bombard(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> members, Volley run, long now) {
		int blasting = blastingIndex(run.startedAt(), now);
		if (blasting >= 0 && blasting < SHELL_COUNT) {
			List<Vec3> shells = new ArrayList<>(run.lines().size());
			for (Barrage line : run.lines()) {
				shells.add(line.shells().get(blasting));
			}
			detonate(end, members, shells);
		}
		markAll(end, run, blownCount(run.startedAt(), now), now);

		int flying = flyingIndex(run.startedAt(), now);
		if (flying >= 0 && flying < SHELL_COUNT) {
			Vec3 mouth = mouthOf(dragon);
			double progress = flightProgress(run.startedAt(), now);
			for (Barrage line : run.lines()) {
				drawShot(end, mouth, line.shells().get(flying), progress);
			}
		}
	}

	/**
	 * 이번 박자의 원들(줄마다 하나)이 터진다. 블록은 건드리지 않고 불도 붙이지 않는다.
	 *
	 * <h2>원 하나는 팀에게 한 번만, 한 사람은 한 박자에 한 번만</h2>
	 *
	 * <p>한 원은 <b>하나의 화염구</b>이므로 안에 선 사람을 모두 때리지 않는다. 넷이 함께 움직이는
	 * 것이 공유 체력 게임의 올바른 대응인데, 모두 때리면 같은 원에 넷이 있을 때 무장 기준으로도
	 * 27 이 되어 <b>올바른 대응이 전멸</b>이 된다(맨몸이면 138 이다). 설계의 「광역 한 틱 한 번」이
	 * 정확히 이 경우를 위해 있다.
	 *
	 * <p>다른 원은 다른 화염구라 따로 센다 — 그것이 이 패턴의 긴장이고
	 * {@link #DAMAGE_PER_BLAST} 의 설명에 몇 발까지인지 적어 두었다.
	 *
	 * <p>⚠ 다만 <b>같은 박자의 두 원이 한 사람을 두 번 때리지는 않는다.</b> 두 줄은 중앙에서 만나고
	 * 그 자리의 같은 박자 원 둘은 겹친다({@link #MIN_LINE_ANGLE_DEGREES}). 거기 선 사람은 한 번만
	 * 맞고, 둘째 원은 <b>그 원 안의 다른 사람</b>이 있을 때만 그 사람을 때린다. 누가 맞는지는
	 * {@link #victims} 가 월드 없이 정한다. ⚠ <b>이제 그것이 유일한 문지기다.</b> 포격은 피격
	 * 쿨타임을 무시하고 들어가므로({@link #strike}) 같은 틱의 두 번째 23 을 바닐라가 대신 버려
	 * 주지 않는다 — {@code victims} 를 걷어내면 교차점에 선 사람이 한 틱에 두 번 맞는다.
	 *
	 * <p><b>이 메서드가 이 원이 아프게 하는 유일한 지점이다.</b> 터진 뒤에는 어디서도 이 자리를
	 * 다시 보지 않는다 — 잔류가 없다는 말을 코드로 적으면 이 문장이 된다.
	 *
	 * <p>피해원에 가해 개체를 달지 않는다. 실체가 붙은 피해원이면 {@code LivingEntity} 가 스스로
	 * 밀어내는데, 엔드 섬 가장자리에서 밀리면 대응 불가 즉사다. 폭발 피해형이라 폭발 보호는 그대로
	 * 듣는다.
	 */
	private static void detonate(ServerLevel end, List<ServerPlayer> members, List<Vec3> shells) {
		for (Vec3 at : shells) {
			// 착탄 연출도 긴 형태다. 맞은 사람만 보고 나머지가 못 보면 「저기 떨어졌다」가 팀에
			// 공유되지 않아 다음 원을 못 읽는다.
			end.sendParticles(ParticleTypes.EXPLOSION_EMITTER, true, false,
					at.x, at.y + 0.5, at.z, 1, 0.0, 0.0, 0.0, 0.0);
			end.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, at.x, at.y + 0.5, at.z,
					BLAST_SMOKE, SHELL_RADIUS * 0.4, 0.3, SHELL_RADIUS * 0.4, 0.02);
			end.playSound(null, at.x, at.y, at.z, SoundEvents.GENERIC_EXPLODE, SoundSource.HOSTILE,
					3.0F, 1.0F);
		}

		// 팀원 목록을 직접 돈다. 상자로 후보를 추릴 이유가 없다 — 원마다 한 명만 세고, 팀이
		// 아닌 사람(관전자·다른 판의 누구)을 때릴 일도 없어야 한다.
		List<Vec3> positions = new ArrayList<>(members.size());
		for (ServerPlayer member : members) {
			positions.add(member.position());
		}
		for (int victim : victims(positions, shells)) {
			if (victim < 0) {
				continue;
			}
			strike(end, members.get(victim));
		}
	}

	/**
	 * 지금 포격 한 발을 넣는 중인가. {@link #strike} 의 {@code hurtServer} 호출 동안만 참이다.
	 *
	 * <p>「완충」({@code SpreadDamageManager})은 바닐라 쿨타임을 따로 흉내 낸다({@code Guard}).
	 * 그 흉내가 이 표시를 보지 않으면 「완충」을 가진 팀에게서만 둘째 원이 다시 먹힌다.
	 */
	private static boolean striking;

	/** 지금 포격 한 발을 넣는 중인가 — 피격 쿨타임을 흉내 내는 쪽이 「무시」를 맞추려고 묻는다. */
	static boolean ignoresCooldown() {
		return striking;
	}

	/**
	 * 포격 한 발을 한 사람에게 넣는다. <b>이 패시브가 사람을 아프게 하는 유일한 호출이고, 피격
	 * 쿨타임을 무시하는 것도 이 호출 하나뿐이다.</b>
	 *
	 * <h2>「포격 무시」 — 사람이 2026-10-04 에 골랐다</h2>
	 *
	 * <p>간격 0.4초(8틱)가 바닐라 피격 쿨타임의 문턱 10틱보다 짧아, 선을 따라 도망친 사람이 원
	 * 둘에 걸려도 둘째 원이 쿨타임에 먹혔다({@link #BLAST_INTERVAL_TICKS}). 그 사실을 듣고 사람이
	 * <b>연쇄 포격의 피해만</b> 쿨타임을 무시하게 했다. 다른 피해원의 쿨타임은 풀리면 안 된다.
	 *
	 * <h2>어떻게 무시하는가 — 넣기 직전에 쿨타임을 지운다</h2>
	 *
	 * <p>26.3 {@code LivingEntity.hurtServer} 바이트코드(184~269)로 보면 갈래는 둘이다.
	 *
	 * <pre>
	 *   damageCooldownTime &gt; 10 이고 bypasses_cooldown 이 아니면   // 쿨타임 안
	 *       amount &lt;= lastHurt 면 false — 버린다
	 *       actuallyHurt(amount - lastHurt); lastHurt = amount; 연출 깃발 = false
	 *   아니면                                                     // 새로 맞음
	 *       lastHurt = amount; damageCooldownTime = 20; actuallyHurt(amount); hurtTime = 10
	 * </pre>
	 *
	 * <p>쿨타임을 {@link #STRIKE_COOLDOWN_TICKS}(0)로 지우고 부르면 바닐라가 <b>「새로 맞음」</b>
	 * 갈래를 탄다. {@code lastHurt} 는 건드릴 필요가 없다 — 그 갈래는 {@code lastHurt} 를 읽지 않고
	 * 덮어쓴다. {@code #bypasses_cooldown} 태그로 하지 않는 것은 26.3 바닐라에서 그 태그가 비어
	 * 있고 피해원이 {@code minecraft:explosion} 이라, 태그를 채우면 <b>모든 폭발</b>이 쿨타임을
	 * 무시하게 되기 때문이다. 같은 지점에 믹스인을 하나 더 거는 길도 버렸다 —
	 * {@code damageCooldownTime} 은 26.3 에서 {@code public} 이라 여기서 바로 쓸 수 있고,
	 * {@code SpreadDamageManager.deliver} 가 이미 같은 칸을 같은 방식으로 만진다.
	 *
	 * <p>그래서 증강 처리가 보는 것도 모두 바닐라 그대로다 — 「새로 맞음」 갈래의 연출(붉은 번쩍임·
	 * 피격음)이 매 발 나고, 흡수·방어구·{@code CombatTracker}(사망 메시지)·처치자 판정·불사의 토템·
	 * 피해 집계({@code StatMirror}·{@code DamageLedger})가 전부 {@code actuallyHurt} 와 그 뒤에서
	 * 평소대로 돈다. {@code LivingEntityPerkDamageMixin} 의 「호위」 낭비 방지
	 * ({@code effectiveAmount})는 지운 쿨타임을 보고 포격 전부를 실제 피해로 세는데, 실제로도 전부
	 * 들어가므로 맞는 값이다. 피해원이 몹이 아니라 「호위」 자체는 걸리지 않는다.
	 *
	 * <h2>무엇을 되돌리는가 — 맞았으면 바닐라 새 값, 안 맞았으면 원래 값</h2>
	 *
	 * <ul>
	 *   <li><b>맞았으면 되돌리지 않는다.</b> 바닐라가 쓴 쿨타임 20 과 {@code lastHurt} = 이번 피해량을
	 *       그대로 둔다. 포격 직후 10틱 동안 다른 피해원이 쿨타임에 막히는 바닐라 동작이 그대로
	 *       남아야 하기 때문이다 — 되돌리면 포격 34.5(하드)에 이어 같은 틱의 좀비 한 대가 온전히
	 *       얹힌다. 무시하는 것은 <b>「포격이 들어갈 때 이전 쿨타임」뿐</b>이다.
	 *       ({@code SpreadDamageManager.deliver} 가 둘 다 되돌리는 것과 반대다. 그쪽은 이미 맞은
	 *       피해를 나눠 넣는 것이라 쿨타임을 새로 채우면 안 되고, 이쪽은 <b>새로 맞는 것</b>이다.)</li>
	 *   <li><b>안 맞았으면 지운 쿨타임을 되돌린다.</b> 시련 정지·게임 시작 전의 HEAD 취소,
	 *       {@code Player.hurtServer} 의 무적·평화 난이도 조기 반환, 죽어 가는 중 — 이런 길에서는
	 *       바닐라가 쿨타임을 쓰지 않으므로 지운 0 이 남는다. 그대로 두면 <b>다음에 오는 다른
	 *       피해원이 쿨타임 없이 들어간다.</b> {@code lastHurt} 는 그 길들에서 아무도 쓰지 않아
	 *       되돌릴 것이 없다. 판정은 {@link #cooldownAfterStrike} 다.</li>
	 * </ul>
	 *
	 * <h2>「완충」과의 관계</h2>
	 *
	 * <p>「완충」을 가진 팀이면 피해량이 {@code hurtServer} 머리에서 0 으로 미뤄진다. 바닐라는
	 * 0 을 「새로 맞음」 갈래로 받아 쿨타임 20 을 쓰고(맞음으로 판정), 미뤄 둔 몫은 나중에
	 * {@code SpreadDamageManager.deliver} 가 제 방식으로 넣는다 — 다른 피해원이 미뤄질 때와 같다.
	 * 다만 「완충」은 바닐라 쿨타임을 따로 흉내 내므로({@code Guard}) 그 흉내가
	 * {@link #ignoresCooldown} 을 봐야 둘째 원이 미뤄질 몫으로 받아들여진다.
	 */
	private static void strike(ServerLevel end, ServerPlayer victim) {
		int saved = victim.damageCooldownTime;
		victim.damageCooldownTime = cooldownForStrike(saved);
		striking = true;
		try {
			victim.hurtServer(end, end.damageSources().explosion(null, null), DAMAGE_PER_BLAST);
		} finally {
			striking = false;
			victim.damageCooldownTime = cooldownAfterStrike(saved, victim.damageCooldownTime);
		}
	}

	/**
	 * 포격을 넣기 직전에 둘 피격 쿨타임. 쿨타임 안이면 {@link #STRIKE_COOLDOWN_TICKS} 로 지우고,
	 * 이미 문턱 아래면 손대지 않는다 — 어차피 「새로 맞음」 갈래로 간다.
	 */
	static int cooldownForStrike(int current) {
		return current > VANILLA_DAMAGE_COOLDOWN_TICKS ? STRIKE_COOLDOWN_TICKS : current;
	}

	/**
	 * 포격을 넣은 뒤 남길 피격 쿨타임.
	 *
	 * <p>쿨타임을 지웠는데({@code saved} 가 문턱 위) 호출 뒤에도 지운 값 그대로면 바닐라가 이
	 * 피해를 받아들이지 않은 것이라 원래 값으로 되돌린다. 그 밖에는 바닐라가 남긴 값을 그대로
	 * 둔다 — 맞았으면 20 이다. 까닭은 {@link #strike} 의 「무엇을 되돌리는가」.
	 *
	 * @param saved    넣기 전의 쿨타임
	 * @param observed {@code hurtServer} 가 돌아온 뒤의 쿨타임
	 */
	static int cooldownAfterStrike(int saved, int observed) {
		boolean cleared = saved > VANILLA_DAMAGE_COOLDOWN_TICKS;
		return cleared && observed == STRIKE_COOLDOWN_TICKS ? saved : observed;
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
		end.sendParticles(ParticleTypes.FLAME, true, false, head.x, head.y, head.z,
				SHOT_HEAD_FLAMES, 0.3, 0.3, 0.3, 0.01);
		end.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, head.x, head.y, head.z,
				SHOT_HEAD_SMOKE, 0.2, 0.2, 0.2, 0.0);
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
	 * 월드를 모르는 <b>두 줄</b> 포격. 둘째 줄은 첫 줄에서 {@link #MIN_LINE_ANGLE_DEGREES}° 이상
	 * 벌어진 무작위 방향이다.
	 *
	 * <p>⚠ {@link #LINE_COUNT} 를 셋 이상으로 올리는 사람은 여기부터 다시 짤 것. 각도 하한은 「두
	 * 줄 사이」만 보장하고, 셋째 줄이 둘 중 어느 쪽과 가까워질지는 이 함수가 모른다.
	 *
	 * @param firstRoll 0~1 의 굴림. 첫 줄의 각도가 된다
	 * @param gapRoll   0~1 의 굴림. 두 줄 사이 각이 된다
	 */
	static Volley planVolley(long cycle, double firstRoll, double gapRoll) {
		List<Barrage> lines = new ArrayList<>(LINE_COUNT);
		lines.add(plan(cycle, firstRoll));
		lines.add(plan(cycle, secondRoll(firstRoll, gapRoll)));
		return new Volley(lines);
	}

	/**
	 * 둘째 줄의 굴림. 첫 줄에서 {@code [45°, 135°]} 만큼 돌린 각을 {@link #axisFor} 가 받는 0~1
	 * 굴림으로 되돌려 준다.
	 *
	 * <p>선은 양방향이라 {@code θ} 와 {@code θ+180°} 가 같은 선이다. 그래서 첫 줄에서 45°~135°
	 * 만큼 돌리면 두 줄 사이 각(0~90°로 잰 것)은 언제나 45°~90° 이고, 그 안에서 고르다.
	 */
	static double secondRoll(double firstRoll, double gapRoll) {
		double first = clampUnit(firstRoll) * Math.PI;
		double min = Math.toRadians(MIN_LINE_ANGLE_DEGREES);
		double gap = min + clampUnit(gapRoll) * (Math.PI - 2.0 * min);
		return ((first + gap) % Math.PI) / Math.PI;
	}

	/** 두 줄 사이 각(도). 선이 양방향이므로 0~90° 로 잰다. 「너무 가깝지 않은가」를 숫자로 묻는다. */
	static double lineSeparationDegrees(Barrage one, Barrage other) {
		Vec3 a = one.to().subtract(one.from()).normalize();
		Vec3 b = other.to().subtract(other.from()).normalize();
		double dot = Math.min(1.0, Math.abs(a.x * b.x + a.z * b.z));
		return Math.toDegrees(Math.acos(dot));
	}

	private static double clampUnit(double roll) {
		return Math.max(0.0, Math.min(1.0, roll));
	}

	/**
	 * 그 포격을 실제 지면에 얹는다.
	 *
	 * <p>높이를 <b>놓을 때 한 번만</b> 찾는다. 매 틱 하이트맵을 스무 번 두드리면 예고·포격 7.6초 내내 청크를
	 * 뒤지게 되고, 어차피 포격이 도는 동안 지면은 바뀌지 않는다 — 이 패시브는 블록을 한 칸도
	 * 건드리지 않으니 더욱 그렇다.
	 */
	private static Volley onGround(ServerLevel end, Volley flat) {
		List<Barrage> lines = new ArrayList<>(flat.lines().size());
		for (Barrage line : flat.lines()) {
			lines.add(onGround(end, line));
		}
		return new Volley(lines);
	}

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
	static Volley startingNear(Volley run, @Nullable Vec3 breather) {
		// 줄마다 따로 맞춘다. 두 줄 모두 「드래곤이 있는 쪽 끝부터」여야 한다.
		List<Barrage> lines = new ArrayList<>(run.lines().size());
		for (Barrage line : run.lines()) {
			lines.add(startingNear(line, breather));
		}
		return new Volley(lines);
	}

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
	 * 이번 틱에 터지는 원(줄마다 같은 번째). 없으면 -1.
	 *
	 * <p><b>값을 하나만 돌려준다는 것이 이 패턴의 안전장치다.</b> 목록을 돌려주게 고치는 순간
	 * 한 줄에서 한 틱에 여러 개가 터질 수 있게 되고, 열 개면 60 으로 팀 체력 20 을 세 배 넘는다.
	 * 두 줄은 이 값 하나를 함께 쓰므로 한 틱에 터지는 원은 줄 수와 같다.
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
	 * <p>{@link #FLIGHT_TICKS} 가 {@link #BLAST_INTERVAL_TICKS} 와 같으므로 <b>줄마다 언제나
	 * 정확히 한 발</b>이다. 앞 발이 터진 그 틱에 다음 발이 떠난다.
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

	/**
	 * 이 고리를 이번 틱에 그리는가.
	 *
	 * <p>고리마다 차례를 하나씩 어긋나게 둬 {@link #MARK_STRIDE} 틱에 한 번씩 돌아오게 한다.
	 * 스무 개가 한 틱에 몰리지 않고 7·7·6 으로 갈린다.
	 *
	 * @param ring 줄 번호 × 원 개수 + 원 번호. 두 줄의 고리를 한 줄로 이어 센 번호다
	 */
	static boolean drawsRingAt(int ring, long now) {
		if (MARK_STRIDE <= 1) {
			return true;
		}
		return Math.floorMod(now - ring, (long) MARK_STRIDE) == 0L;
	}

	/**
	 * 한 틱에 바닥 표식으로 나가는 점 수의 <b>상한</b>. 「상한 안인가」를 숫자로 묻는 값이다.
	 *
	 * <p>고리 {@code 줄 수 × 원 개수} 개를 {@link #MARK_STRIDE} 틱에 나누므로 한 틱에 많아야
	 * 그 몫의 올림만큼 그린다 — 20 ÷ 3 의 올림 7 × 44 = 308.
	 */
	static int markPoints() {
		int rings = LINE_COUNT * SHELL_COUNT;
		int stride = Math.max(1, MARK_STRIDE);
		return (rings + stride - 1) / stride * TrialWarning.ringPoints(SHELL_RADIUS);
	}

	/**
	 * 이 패시브가 <b>한 틱에</b> 내보내는 점 수의 상한. 고리 + 화염구 + 착탄.
	 *
	 * <p>줄이 둘이 되며 화염구도 착탄도 두 벌이다. 꼬리 {@link #TRAIL_MAX_POINTS} + 머리
	 * ({@link #SHOT_HEAD_FLAMES} + {@link #SHOT_HEAD_SMOKE}) 가 한 발이고, 착탄은 방출기 하나 +
	 * {@link #BLAST_SMOKE} 다. 실제로 셋이 한 틱에 다 겹치지는 않지만(터지는 틱에는 고리가 이미
	 * 줄어 있다) 예산을 묻는 자리는 나쁜 쪽을 본다 — 308 + 90 + 42 = <b>440</b>.
	 */
	static int worstTickPoints() {
		int shot = TRAIL_MAX_POINTS + SHOT_HEAD_FLAMES + SHOT_HEAD_SMOKE;
		int blast = 1 + BLAST_SMOKE;
		return markPoints() + LINE_COUNT * shot + LINE_COUNT * blast;
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
	 * 이번 박자에 터지는 원마다 <b>누가 맞는가</b>. 원 번호 순서대로 팀원 번호를 돌려주고, 아무도
	 * 없으면 -1 이다.
	 *
	 * <h2>두 규칙</h2>
	 *
	 * <ol>
	 *   <li><b>원 하나는 한 사람만</b> 때린다 — 한 원은 하나의 화염구다({@link #detonate})</li>
	 *   <li><b>한 사람은 한 박자에 한 번만</b> 맞는다 — 두 줄이 만나는 중앙에서 같은 박자의 두 원이
	 *       겹치는데, 거기 선 사람이 두 번 맞으면 46(무장 13.5)이 한 틱에 한 사람에게 간다</li>
	 * </ol>
	 *
	 * <p>원을 차례로 보며 아직 안 맞은 사람 중 원 안의 첫 사람을 고른다. 이 순서 때문에 두 사람이
	 * 둘 다 맞을 수 있었던 판에서 한 사람만 맞는 경우가 있다(겹친 자리의 사람이 첫 원에 잡히고,
	 * 첫 원에만 있던 사람이 남는 경우). <b>덜 아픈 쪽으로만 틀리므로</b> 그대로 둔다.
	 *
	 * @param positions 팀원 자리. 높이는 보지 않는다({@code TrialRisks.insideMark})
	 * @param shells    이번 박자의 원 중심들. 줄마다 하나
	 */
	static int[] victims(List<Vec3> positions, List<Vec3> shells) {
		int[] chosen = new int[shells.size()];
		boolean[] struck = new boolean[positions.size()];
		for (int shell = 0; shell < shells.size(); shell++) {
			chosen[shell] = -1;
			for (int member = 0; member < positions.size(); member++) {
				if (struck[member]
						|| !TrialRisks.insideMark(positions.get(member), shells.get(shell),
								SHELL_RADIUS)) {
					continue;
				}
				chosen[shell] = member;
				struck[member] = true;
				break;
			}
		}
		return chosen;
	}

	/**
	 * 한 사람이 연달아 걸린 원 {@code hits} 개 중 <b>실제로 들어가는</b> 수.
	 *
	 * <p>바닐라 피격 쿨타임을 원 하나씩 그대로 굴려 센다. 원은 {@link #BLAST_INTERVAL_TICKS} 마다
	 * 오고, 쿨타임은 그 사이 그만큼 줄며, 원이 닿을 때마다 {@link #strike} 가 하는 그대로
	 * {@link #cooldownForStrike} 를 거친다. 문턱 이하면 들어가고 쿨타임이
	 * {@link #VANILLA_FRESH_COOLDOWN_TICKS} 로 찬다. 문턱 위에 남은 같은 크기의 피해는 버려진다.
	 *
	 * <p>사람이 2026-10-04 에 「포격 무시」를 골라 지금은 <b>걸린 원이 전부 들어간다</b> — 이
	 * 함수가 {@code hits} 를 그대로 돌려준다. 그래도 곱셈 하나로 줄이지 않고 굴려 세는 것은,
	 * {@code cooldownForStrike} 가 되돌아가면(쿨타임을 지우지 않으면) 이 값이 곧바로 <b>한 칸 걸러
	 * 하나</b>로 떨어져 시험이 그 자리에서 깨지게 하려는 것이다.
	 */
	static int landedHits(int hits) {
		int landed = 0;
		int cooldown = 0;
		for (int shell = 0; shell < hits; shell++) {
			if (shell > 0) {
				cooldown = Math.max(0, cooldown - Math.max(0, BLAST_INTERVAL_TICKS));
			}
			if (cooldownForStrike(cooldown) <= VANILLA_DAMAGE_COOLDOWN_TICKS) {
				landed++;
				cooldown = VANILLA_FRESH_COOLDOWN_TICKS;
			}
		}
		return landed;
	}

	/** 그 속도로 선을 따라 도망친 사람에게 <b>실제로 들어가는</b> 발 수. {@link #chainHits} 에 쿨타임을 굴린 것. */
	static int landedChainHits(double blocksPerSecond) {
		return landedHits(chainHits(blocksPerSecond));
	}

	/**
	 * 한 사람이 이 패시브에게서 <b>한 틱에</b> 받을 수 있는 가장 큰 피해. <b>적힌 날값</b>이다.
	 *
	 * <p>「즉사 메커닉 0개」가 지켜지는지를 값에서 직접 계산해 둔다. 값을 올리는 사람은 피해만 보고
	 * 한 틱에 몇 개가 터지는지는 보지 않는다.
	 *
	 * <p>⚠ 이름과 달리 <b>팀 몫</b>이다 — 한 사람은 한 박자에 한 번만 맞으므로({@link #victims})
	 * 두 번째 발은 다른 사람의 것이다. 공유 체력에서는 그 둘이 같은 체력을 깎으므로 팀 몫이 곧
	 * 견줄 값이다.
	 *
	 * <p>⚠ <b>이 값을 그대로 20 과 견주지 말 것.</b> 이제 피해는 완전무장을 전제로 잡혀 있어
	 * 날값이 20 을 넘는다({@link #DAMAGE_PER_BLAST} 의 설명). 여기서 날값을 돌려주는 것은
	 * <b>감쇠를 어디에 걸지는 보는 쪽이 정해야</b> 하기 때문이다 — 하드 곱과 방어·보호는 피해
	 * 한 방마다 따로 걸리므로, 곱해 놓은 뒤에 한 번 감쇠하면 겹친 발이 실제보다 아프게 나온다.
	 * 무장 기준으로 견주는 것은 {@code DragonFireBarrageTest} 가 한다.
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
	 * 한 발로 끝나지만, 같은 방향으로 도망치면 그 차이가 <b>{@code 간격 - 속도 × 0.4초}</b> 로
	 * 줄어든다. 폭이 지름 {@code 2R} 이므로 그 사이에 들어오는 발이 답이다.
	 *
	 * <p>지금 값(0.4초 간격)에서는 걷기(4.317)·달리기(5.612)·달리며 뛰기(7.13) 모두 원 <b>두
	 * 개</b>에 걸린다. 세 개가 되려면 11.25칸/초를 넘어야 하고, 포격 자체가 초당 20칸으로 전진하므로
	 * 그쯤 되면 사람이 포격을 <b>타고 가는</b> 셈이다.
	 *
	 * <p>⚠ <b>걸리는 원 수이지 들어가는 발 수가 아니다.</b> 두 번째 원은 8틱 뒤라 바닐라대로면
	 * 피격 쿨타임에 먹히는데, 포격은 그 쿨타임을 무시하므로({@link #strike}) 지금은 둘이 같다 —
	 * 들어가는 발 수는 {@link #landedChainHits} 가 센다.
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
	 * 그 속도로 도망치는 동안 팀이 받는 피해. <b>실제로 들어가는 발</b>({@link #landedChainHits})
	 * 의 <b>적힌 날값 합</b>이다. 0.4초 간격이 처음 들어왔을 때는 둘째 원이 쿨타임에 먹혀 한
	 * 발(23)이었고, 사람이 2026-10-04 에 「포격 무시」를 골라 다시 두 발(46)이 됐다.
	 *
	 * <p><b>개수·반경·간격·피해를 잇는 한 손잡이다.</b> 넷 중 무엇을 올려도 여기에 나타나므로,
	 * 값을 고치는 사람은 이 값을 보고 {@link #DAMAGE_PER_BLAST} 를 함께 내려야 한다.
	 *
	 * <p>⚠ 감쇠는 <b>발마다 따로</b> 걸리므로 이 합을 통째로 감쇠하면 안 된다. 두 발이면
	 * {@code 46 × 감쇠} 가 아니라 {@code 6.77 × 2 = 13.5} 다 — 무장 기준 몫을 묻는 쪽은
	 * {@link #landedChainHits} 로 발 수를 받아 한 발씩 감쇠할 것.
	 */
	static float chainDamage(double blocksPerSecond) {
		return landedChainHits(blocksPerSecond) * DAMAGE_PER_BLAST;
	}
}
