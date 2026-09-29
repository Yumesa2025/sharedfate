package com.sharedfate.sync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.PowerParticleOption;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 드래곤 기본 패시브 「가르는 브레스」 — 아레나를 반으로 가르는 잔류 장판.
 *
 * <p>드래곤이 아레나를 가로질러 브레스를 뿜고, 지나간 자리에 <b>직선 장판</b>이 15초 남아 길을
 * 막는다. 시련과 달리 <b>언제나 있는 판</b>이라 팀이 무엇을 뽑았든 40초마다 한 번씩 돈다.
 *
 * <h2>블록은 한 칸도 건드리지 않는다</h2>
 *
 * <p>「바닥을 반으로 없앤다」가 처음 나온 말이었고 사람이 곧바로 바로잡았다 — <b>없애는 것이
 * 아니라 못 지나가게 하는 것</b>이다. 그 구분이 이 패턴의 전부다. 엔드 중앙 섬에 구멍을 내면
 * 공허 낙사가 되고, 그것은 이 전투가 유일하게 금지한 「대응 불가 즉사」다. 게다가 구멍은 판이
 * 끝나도 남아 <b>다음 전투의 발판이 달라진다</b>. 그래서 여기에는 블록을 읽는 코드만 있고
 * ({@link #groundY} 의 하이트맵) 쓰는 코드는 한 줄도 없다.
 *
 * <h2>왜 바닐라 {@code AreaEffectCloud} 를 쓰지 않는가</h2>
 *
 * <p>구름은 판정과 연출이 한 덩어리라 편하지만, 이 패턴에는 네 가지가 어긋난다.
 *
 * <ul>
 *   <li><b>구름은 원이고 우리 장판은 80칸짜리 선이다.</b> 반경 2 짜리 구름을 줄 세워 덮으려면
 *       마흔 개쯤 필요하고, 이웃한 구름은 반드시 겹친다. 겹친 자리에 선 사람은 <b>한 틱에 두 번
 *       맞는다</b> — 「낙뢰」에서 {@code TrialRisks.SPOT_MIN_GAP_FACTOR} 로 막아 둔 바로 그
 *       사고이고, 「피해는 카드에 적힌 값 하나」를 구조적으로 못 지킨다</li>
 *   <li><b>바닐라 드래곤 브레스 구름은 즉시 피해 <i>포션 효과</i>로 아프게 한다.</b> 26.3 의
 *       {@code DragonSittingFlamingPhase} 가 {@code addEffect(new MobEffectInstance(INSTANT_DAMAGE))}
 *       를 건다. 그러면 피해량이 포션 등급 공식에서 나와 우리가 쥐지 못하고, 상태이상이라
 *       {@link SharedEffectDamage} 와도 얽힌다</li>
 *   <li><b>{@code waitTime}·{@code reapplicationDelay}·{@code radiusPerTick} 이 값을 대신
 *       정한다.</b> 「정확히 15초 뒤 사라진다」를 시험으로 물을 수 없게 된다</li>
 *   <li><b>엔티티 마흔 개는 월드에 남는다.</b> 서버가 내려가거나 판이 접히면 {@code clearState}
 *       로 못 지운다 — 이 패키지가 가장 자주 겪은 「다음 판으로 새는」 사고다</li>
 * </ul>
 *
 * <p>그래서 판정은 우리가 하고({@link #insideField}) <b>구름의 생김새만</b> 파티클로 빌린다. 보이는
 * 것은 브레스이고 값은 전부 우리 것이다.
 *
 * <h2>예고는 표식, 장판은 구름 — 둘이 달라 보여야 한다</h2>
 *
 * <p>처음에는 예고와 장판을 <b>같은 빨간 표식</b>으로 그렸다. 사람이 그것을 보고 바로 짚었다 —
 * 표식은 이 전투의 색 규약에서 <b>「곧 무슨 일이 일어난다」</b>는 뜻이고, 일이 끝나면 사라지는
 * 것으로 배운다. 그런데 이 선은 <b>이미 일어난 뒤 15초 동안 남아 있는 것</b>이라, 표식으로 그리면
 * 「아직 안 터진 건가」로 읽히고 사람이 그 위를 그냥 걸어 들어간다.
 *
 * <p>바닐라에서 「바닥에 깔려 있고 밟으면 아픈 것」의 생김새는 이미 정해져 있다 — <b>잔류 구름</b>
 * 이다. 마인크래프트를 하는 사람은 그 모양을 배운 채로 들어오므로, 그 모양으로 그리면 설명이
 * 필요 없다. 그래서 {@link #lookFor} 가 예고와 장판을 갈라 서로 다른 파티클을 준다.
 *
 * <ul>
 *   <li>예고 — {@link TrialWarning.Colors#DEADLY} 빨간 먼지. 색 규약은 <b>예고에만</b> 해당한다</li>
 *   <li>장판 — {@code PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, 1.0F)}.
 *       26.3 {@code DragonSittingFlamingPhase} 가 제 잔류 구름에
 *       {@code setCustomParticle} 으로 넣는 것과 <b>같은 값</b>이다(세기도 1.0). 일반 잔류 포션
 *       구름은 {@code ColorParticleOption.create(ENTITY_EFFECT, 색)} 을 쓰는데, 이 선은 드래곤이
 *       뿜은 것이므로 드래곤 쪽을 고른다. 새 색 상수는 만들지 않는다 — 바닐라 구름의 생김새를
 *       빌리는 것이라 색이 규약에 들어갈 일이 아니다</li>
 * </ul>
 *
 * <p>장판은 <b>면으로 채운다</b>({@link #fieldFill}). 선 하나로 그으면 폭이 6칸이어도 여전히
 * 「금」으로 보이고, 금은 밟는 것이 아니라 넘는 것이다. 다만 면은 점이 제곱으로 늘어나므로
 * 간격에서 개수를 뽑되 {@link #FILL_MAX_POINTS} 로 자르고, <b>경계 두 줄은 상한과 무관하게
 * 언제나 제 해상도로 그린다</b> — 잘려야 하는 것은 안쪽이지 「어디부터 아픈가」가 아니다.
 *
 * <h2>피해는 한 값뿐이고, 팀에게 한 번만 들어간다</h2>
 *
 * <p>쓸고 지나가는 브레스 자체는 <b>아프지 않다.</b> 아픈 것은 남은 장판뿐이다. 둘 다 아프게
 * 하면 「브레스에 스쳤다」와 「장판을 밟았다」가 섞여 무엇에 맞았는지 못 읽고, 무엇보다
 * 이중 피해가 된다.
 *
 * <p>그리고 <b>한 장판은 하나의 공격</b>이므로 한 틱에 팀 공유 풀을 한 번만 깎는다
 * ({@link #burn}). 설계의 「광역 한 틱 한 번」이 그것이고, 넷이 함께 건너는 것이 이 패턴에
 * 대한 올바른 대응인데 그때 피해가 네 배가 되면 올바른 대응이 전멸이 된다.
 *
 * <h2>왜 {@link SharedAreaDamage} 에 맡기지 않는가</h2>
 *
 * <p>그쪽은 <b>피해원에 가해 개체가 붙어 있어야</b> 접는다({@code source.getEntity() != null}).
 * 그런데 {@code dragon_breath} 의 데이터를 보면 {@code "scaling": "when_caused_by_living_non_player"}
 * 다 — 가해 개체로 드래곤을 달면 <b>어려움 난이도에서 값이 ×1.5</b> 가 된다. 하드코어는 난이도가
 * 어려움 고정이라 적어 둔 2 가 조용히 3 이 되고, 그것이 설계의 「난이도 스케일링에 얹힌 피해」
 * 금지다. 그래서 가해 개체를 달지 않고 접는 일만 여기서 직접 한다.
 *
 * <p>넉백은 걱정하지 않아도 된다 — {@code dragon_breath} 는 바닐라 {@code no_knockback} 태그에
 * 들어 있다. 엔드 섬 밖 허공으로 밀려나는 길이 애초에 없다.
 *
 * <h2>갇히면 안 된다</h2>
 *
 * <p>장판이 생기는 순간 그 위에 서 있던 사람은 <b>걸어 나올 수 있어야 한다.</b> 사람이 폭을 좌우
 * {@link #HALF_WIDTH} 칸(총 6칸)으로 올려 달라고 했고, 그래도 이 약속은 그대로다. 걸음 속도
 * 4.317 칸/초로 재면 <b>한가운데에서 걸어 나오는 데 0.7초, 반대편으로 아예 건너가도 1.4초</b>다.
 * 피해 간격이 10틱이므로 건너는 동안 맞는 것은 최악으로 세 점, <b>여섯</b>이다 — 팀 체력 20 의
 * 3할이고, 시험이 잡아 둔 하한(절반)의 안쪽이다. 이 패턴이 뜻하는 것은 「들어가면 죽는다」가
 * 아니라 <b>「지나가려면 아프다」</b>이고, 그래서 아레나가 갈리되 길이 끊기지는 않는다. 서서
 * 15초를 다 맞으면 60 — 팀 체력의 세 배다.
 *
 * <p><b>폭을 더 넓히려면 {@link #DAMAGE_PER_PULSE} 를 함께 내려라.</b> 폭은 건너는 시간을 늘리고
 * 건너는 시간은 맞는 횟수를 늘린다 — 둘은 한 손잡이다.
 *
 * <h2>드래곤은 읽기만 한다 — 한 칸도 옮기지 않는다</h2>
 *
 * <p>「드래곤이 직접 선을 따라 날며 뿜게 해 달라」가 사람의 말이었고,
 * {@link TrialCrystalRevive#hold} 에 위치를 당기는 방법이 이미 풀려 있다. 그래도 <b>하지
 * 않는다.</b> 26.3 바이트코드를 세어 보고 접었다.
 *
 * <ol>
 *   <li><b>드래곤 몸은 지나가는 것만으로 사람을 때린다.</b> {@code EnderDragon.aiStep} 의 서버
 *       구간이 매 틱 {@code hurt(level, getEntities(head.getBoundingBox().inflate(1)))} 와
 *       목 쪽 같은 호출로 <b>{@code mobAttack} 10.0F</b> 를 넣고,
 *       {@code knockBack(wing2.getBoundingBox().inflate(4,2,4).move(0,-2,0))} 로 <b>5.0F + 밀치기</b>
 *       를 넣는다. 팀 공유 체력이 20 인데 10 은 <b>절반</b>이고, 밀치기는 엔드 섬 밖으로 미는 길이다.
 *       게다가 80칸을 20틱에 지나가면 히트박스가 <b>한 틱에 4칸씩 건너뛴다</b> — 맞고 안 맞고가
 *       복불복이 된다. 「대응 불가 즉사 0개」와 정면으로 어긋난다</li>
 *   <li><b>드래곤 몸은 지나가는 것만으로 블록을 부순다.</b> 같은 구간의
 *       {@code checkWalls(head/neck/body 의 AABB)} 가 {@code DRAGON_IMMUNE} 이 아니고
 *       {@code mobGriefing} 이 켜져 있으면 {@code removeBlock} 한다. 엔드 섬(엔드스톤)과
 *       기둥(흑요석)은 면제라도 <b>사람이 놓은 블록은 아니다.</b> 이 패시브의 제1원칙이
 *       「블록은 한 칸도 건드리지 않는다」인데, 우리가 부르는 이동 때문에 드래곤이 대신 부수면
 *       원칙이 깨진 것은 마찬가지다</li>
 * </ol>
 *
 * <p><b>경로 쪽은 오히려 안전했다.</b> 「표적」이 낸 착지 정지 사고가 재발할까 봐 먼저 이쪽을
 * 봤는데, {@code DragonHoldingPatternPhase.doServerTick} 은
 * {@code 목표까지 거리² < 100 || > 22500 || 충돌} 이면 {@code findNewTarget} 을 부르고, 그
 * 메서드는 어느 갈래로 가든 마지막에 {@code navigateToNextPathNode()} 로 <b>경로를 반드시 한 칸
 * 전진시킨다.</b> 즉 드래곤을 어디로 옮겨도 경로는 계속 끝을 향해 가고 「착지할까」 주사위가
 * 돌아온다. 사고를 낸 것은 {@code setPhase} 가 부르는 {@code begin()} 이 {@code currentPath} 를
 * {@code null} 로 지우는 쪽이었다. <b>이 문단을 남기는 이유는, 다음 사람이 「경로 때문에 못 했다」로
 * 오해하고 엉뚱한 곳을 고치지 않게 하기 위해서다</b> — 접은 이유는 위의 두 가지, 피해와 블록이다.
 *
 * <p>그래서 드래곤에게서 <b>읽기만</b> 한다. 되돌릴 상태가 아예 없으므로 연출 도중 드래곤이 죽든
 * 서버가 내려가든 남는 것이 없다.
 *
 * <ul>
 *   <li><b>선을 드래곤에 맞춘다.</b> 브레스가 출발하는 그 틱에 {@link #startingNear} 가 선의
 *       방향을 뒤집어 <b>드래곤이 있는 쪽 끝에서</b> 출발하게 한다. 예고 6초 동안 보여 준 것은
 *       좌우 대칭인 띠라 방향을 마지막에 정해도 사람이 본 것은 바뀌지 않는다</li>
 *   <li><b>브레스가 드래곤 입에서 나온다.</b> 쓸고 지나가는 동안
 *       {@link #breathStream} 이 {@code EnderDragon.head} 의 실제 자리에서 바닥의 브레스 머리까지
 *       줄기를 잇는다. 드래곤이 어디에 있든 「저기서 뿜은 것」이 선으로 보인다</li>
 * </ul>
 *
 * <p>드래곤의 방향({@code setYRot} 계열)도 건드리지 않는다. {@code aiStep} 이 매 틱
 * {@code yRotA} 관성을 섞어 {@code yRot} 를 다시 쓰고, 그 값이 {@code flightHistory.record} 를 거쳐
 * <b>부위 여덟 개의 자리를 정한다.</b> 우리가 끼어들면 몸과 히트박스가 어긋나는데, 그것이
 * 정확히 「보이는 곳에 없는데 맞는」 사고다.
 */
public final class DragonRiftBreath {

	// ------------------------------------------------------------------ 값

	/**
	 * 다시 그을 때까지(틱). 40초.
	 *
	 * <p><b>예고 + 장판보다 넉넉히 길어야 한다.</b> 선 둘이 동시에 살아 있으면 교차점에 선 사람이
	 * 한 틱에 두 번 맞고, 그 순간 {@link #worstCaseTickDamage} 가 거짓이 된다. 지금은
	 * 120 + 300 = 420 이라 380틱이 남는다 — 「길이 열려 있는 시간」이 절반 가까이 되게 잡은 값이고,
	 * 실측으로 조정한다.
	 */
	static final int PERIOD_TICKS = 800;
	/**
	 * 선이 보이기 시작해 브레스가 지나갈 때까지(틱). 6초.
	 *
	 * <h2>왜 6초인가</h2>
	 *
	 * <p>이 패턴이 요구하는 행동은 <b>「선이 지나갈 자리에서 비키기」</b>인데, 선이 아레나를
	 * 통째로 가르므로 옆걸음만으로는 끝나지 않는다. <b>어느 쪽에 남을지를 골라야</b> 하고, 그
	 * 선택은 팀 전체가 나뉘는 선택이다 — 반대편에 혼자 남으면 15초 동안 합류할 수 없다.
	 *
	 * <ul>
	 *   <li>{@link TrialWarning#TICKS_SIDESTEP}(30) — 제자리 옆걸음. <b>모자란다.</b> 비킬 방향이
	 *       한쪽뿐인 패턴의 기준이다</li>
	 *   <li>{@link TrialWarning#TICKS_REPOSITION}(50) — 지정한 자리로 이동. 여전히 모자란다.
	 *       갈 곳을 고르는 시간이 빠져 있다</li>
	 *   <li>{@link TrialWarning#TICKS_SCATTER}(80) — 넷이 서로 보고 갈라서기. <b>이것이 하한이다</b></li>
	 *   <li><b>100</b> — {@link TrialWarning#stageFor} 가 경고 첫 층을 내기 시작하는 지점이다.
	 *       예고가 100 이하면 「뭔가 온다」 층이 통째로 빠진다</li>
	 * </ul>
	 *
	 * <p>그 위에 여유 20틱(1초)을 얹어 <b>120</b>. 남는 1초는 「선이 먼저 조용히 그어지고 소리가
	 * 뒤따르는」 구간이 되는데, 80칸짜리 선은 읽는 데 시간이 걸리므로 그쪽이 낫다.
	 *
	 * <p>카드 문서의 「부채꼴 브레스」도 6초다. 패시브끼리 예고 길이를 맞춰 두면 플레이어가
	 * 「드래곤이 뭔가 준비하면 6초」 하나만 배운다.
	 */
	static final int LEAD_TICKS = 120;
	/**
	 * 브레스가 아레나를 쓸고 지나가는 시간(틱). 예고의 <b>마지막</b> 1초다.
	 *
	 * <p>이 구간에는 피해가 없다. 장판은 브레스가 반대편 끝에 닿는 순간 <b>한꺼번에</b> 붙는다 —
	 * 붙는 시각이 하나여야 사람이 15초를 셀 수 있고, 뒤에서부터 차례로 꺼지는 장판은 설명할
	 * 방법이 없다.
	 *
	 * <p>이 구간이 <b>드래곤을 읽는 유일한 구간</b>이기도 하다. 짧게 잡아 둔 덕에 드래곤이 반대편
	 * 하늘로 날아가 버리기 전에 줄기가 그어진다.
	 */
	static final int SWEEP_TICKS = 20;
	/** 장판이 남아 있는 시간(틱). 15초. */
	static final int FIELD_TICKS = 300;
	/**
	 * 선 중심에서 좌우로 뻗는 폭(칸).
	 *
	 * <p>총 6칸이다. 좌우 2칸(총 4칸)이던 것을 사람이 「50프로 더」 넓혀 달라고 해서 올렸다.
	 *
	 * <p><b>여기를 또 올리려면 {@link #DAMAGE_PER_PULSE} 를 함께 내려야 한다.</b> 폭은 건너는
	 * 시간을 정하고 건너는 시간은 맞는 횟수를 정한다 — 폭만 올리면 「지나가려면 아프다」가
	 * 「들어가면 죽는다」로 조용히 넘어간다. 지금 값에서 걸어서 건너며 받는 것은 여섯이고,
	 * {@code 걸어서_건너는_동안_받는_피해가_팀_체력보다_훨씬_적다} 가 그 선을 지키고 있다.
	 */
	static final double HALF_WIDTH = 3.0;
	/**
	 * 피해가 들어가는 간격(틱).
	 *
	 * <p>10 인 것은 바닐라 피격 무적시간이 10틱이기 때문이다. 더 촘촘하게 때리면 그 몫이
	 * 무적시간에 먹혀 사라지고, 20틱마다 4씩 주면 <b>빨리 뛰어 건넌 사람이 한 점도 안 아픈</b>
	 * 판이 생긴다. 무적시간을 무시하는 길은 설계가 금지한다.
	 */
	static final int DAMAGE_PERIOD_TICKS = 10;
	/** 한 번에 들어가는 피해. {@link #DAMAGE_PERIOD_TICKS} 와 묶여 <b>초당 4</b>가 된다. */
	static final float DAMAGE_PER_PULSE = 2.0F;

	/**
	 * 같은 시각에 살아 있을 수 있는 선의 수.
	 *
	 * <p>{@link #PERIOD_TICKS} 가 예고 + 장판보다 길어 하나뿐이다. 이 숫자가 곧
	 * {@link #worstCaseTickDamage} 의 곱셈 상대이므로, 주기를 줄이거나 선을 둘로 늘리는 사람은
	 * 반드시 여기를 함께 고쳐야 한다.
	 */
	static final int MAX_CONCURRENT_RIFTS = 1;

	// ------------------------------------------------------------------ 선을 그리는 값

	/** 점 사이 목표 간격(칸). 이것보다 촘촘하게는 찍지 않는다. */
	private static final double LINE_POINT_GAP = 1.0;
	/**
	 * 점 사이가 이보다 벌어지면 선이 아니라 점선이다.
	 *
	 * <p>이 선은 「여기를 넘지 마라」는 <b>벽</b>이다. 점선으로 보이면 사이로 지나갈 수 있어
	 * 보이고, 실제로는 못 지나간다 — 표식이 거짓말을 하는 것이라 고리보다 기준이 빡빡하다.
	 */
	static final double LINE_MAX_GAP = 1.5;
	/** 이 길이까지는 위 간격을 약속한다. 선은 아레나를 지름으로 가르므로 반경의 두 배다. */
	static final double LINE_KEPT_LENGTH = TrialRisks.ARENA_RADIUS * 2.0;
	/**
	 * 한 변에 찍는 점 수의 상한.
	 *
	 * <p>숫자를 박지 않고 위 둘에서 뽑는다 — 따로 적어 두면 한쪽만 고쳐져 약속이 조용히 깨진다.
	 * 점 하나가 패킷 한 장이고 이 선은 <b>변이 둘</b>이며 예고 6초 + 장판 15초 내내 매 틱
	 * 그려지므로, 상한 없이는 파티클만으로 틱이 밀린다.
	 */
	static final int LINE_MAX_POINTS = (int) Math.ceil(LINE_KEPT_LENGTH / LINE_MAX_GAP) + 1;

	/** 지면에서 띄우는 높이. 0 이면 블록 면에 파묻혀 안 보인다. {@code TrialWarning} 과 같은 값. */
	private static final double GROUND_OFFSET = 0.15;
	/**
	 * 하이트맵이 허공을 돌려줬을 때 쓰는 높이.
	 *
	 * <p>아레나 반경 40 안에도 섬이 끊긴 곳이 있다. 거기서 점을 포기하면 선에 구멍이 뚫려
	 * 「저기는 안전한가」로 읽히므로, 마지막으로 찾은 지면 높이를 그대로 이어 그린다. 첫 점부터
	 * 허공이면 중앙 섬 표면인 이 값으로 시작한다.
	 */
	private static final double FALLBACK_GROUND_Y = 63.0;

	// ------------------------------------------------------------------ 장판을 면으로 채우는 값

	/**
	 * 잔류 구름 파티클의 세기.
	 *
	 * <p>26.3 {@code DragonSittingFlamingPhase} 가 제 구름에 넣는 값과 같은 1.0 이다. 다른 값을
	 * 쓰면 크기와 속도가 바닐라 구름과 어긋나 「이건 저것과 다른 것인가」가 된다.
	 */
	private static final float LINGER_POWER = 1.0F;
	/**
	 * 장판 안쪽을 채우는 점 사이 목표 간격(칸). 세로·가로 모두 이 값에서 뽑는다.
	 *
	 * <p>경계 두 줄({@link #LINE_POINT_GAP})보다 성기다. 안쪽은 <b>흩어 뿌려</b> 이웃한 점과
	 * 만나게 하므로 촘촘할 필요가 없고, 면은 점이 제곱으로 늘어 촘촘하면 곧바로 상한에 닿는다.
	 */
	private static final double FILL_GAP = 2.0;
	/**
	 * 경계 사이에 넣는 줄 수의 상한.
	 *
	 * <p>폭을 더 넓혀도 줄이 끝없이 늘지 않게 막는다. 상한에 걸리면 줄 사이가 벌어질 뿐 면은
	 * 남는다 — {@link #FILL_SPREAD} 가 그만큼 메운다.
	 */
	static final int FILL_MAX_LANES = 4;
	/**
	 * 한 틱에 안쪽을 채우는 점 수 상한.
	 *
	 * <p><b>경계 두 줄은 이 상한 밖이다.</b> 잘려야 하는 것은 안쪽 채움이지 「어디부터 아픈가」가
	 * 아니다. 지금 값에서 한 틱에 나가는 것은 경계 55 + 55 에 안쪽 48 을 더한 158 점이다.
	 */
	static final int FILL_MAX_POINTS = 48;
	/** 안쪽 점 하나가 뿌리는 파티클 수. 점 수가 아니라 이쪽을 올리면 패킷은 그대로고 밀도만 는다. */
	private static final int FILL_PARTICLES = 4;
	/** 점 둘레로 흩는 폭. 간격의 절반쯤이라 이웃한 점과 만나 면이 된다. */
	private static final double FILL_SPREAD = 0.8;
	/** 위로는 거의 안 흩는다. 장판은 바닥에 깔린 것이다. */
	private static final double FILL_LIFT = 0.05;
	/** 파티클이 떠도는 속도. 바닐라 구름이 제 파티클에 주는 것과 같은 정도다. */
	private static final double FILL_DRIFT = 0.01;

	/** 드래곤 입에서 바닥까지 잇는 줄기의 점 사이 목표 간격(칸). */
	private static final double STREAM_GAP = 2.0;
	/**
	 * 줄기에 찍는 점 수 상한.
	 *
	 * <p>드래곤은 아레나 반대편 하늘에 있을 수 있어 줄기가 150칸을 넘을 수 있다. 상한이 없으면
	 * 드래곤이 멀수록 패킷이 느는데, 하필 그때가 가장 안 보이는 때다.
	 */
	static final int STREAM_MAX_POINTS = 32;

	/**
	 * 예고가 이만큼도 안 남았으면 이번 주기는 긋지 않는다.
	 *
	 * <p>{@link #LEAD_TICKS} 의 첫 틱을 놓치는 길이 둘 있다 — <b>예고 도중에 서버가 떴을 때</b>와
	 * <b>앞 장판이 아직 타고 있어 이번 주기를 건너뛴 뒤</b>다. 그때 남은 만큼만 예고하고 그으면
	 * 「예고가 짧은 판」이 생기는데, 짧은 예고는 예고가 아니라 사후 통보다. <b>한 번 안 나가는
	 * 쪽이 싸다.</b>
	 */
	static final int MIN_LEAD_TICKS = TrialWarning.TICKS_SCATTER;

	/** 아직 브레스가 지나가지 않아 장판이 붙지 않은 상태. */
	static final long NOT_IGNITED = Long.MIN_VALUE;

	/**
	 * 이번 주기의 선을 그을지 <b>이미 판단했는가</b>.
	 *
	 * <p>「예고 구간이면 긋는다」로만 두면, 앞 장판이 타는 동안 건너뛴 주기가 장판이 꺼지는 순간
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
	 * 지금 그려져 있거나 타고 있는 선. 없으면 {@code null}.
	 *
	 * <p>아레나도 드래곤도 하나뿐이고 이 모드는 팀이 하나다({@code SingleTeamOnly}). 열쇠를 둘
	 * 필요가 없어 칸 하나로 둔다. <b>{@link #clearState()} 로 반드시 비운다</b> — 지난 판의
	 * 좌표가 남으면 새 월드에서 아무도 모르는 자리가 탄다.
	 */
	private static @Nullable Rift active;

	/**
	 * 예고와 장판의 생김새.
	 *
	 * <p>둘이 같은 모양이면 사람이 <b>언제 피해야 하는지</b>를 배울 수 없다. 표식은 「곧 온다」,
	 * 구름은 「지금 아프다」다.
	 */
	enum Look {
		/** 예고 — 색 규약의 빨간 표식. */
		WARNING,
		/** 장판 — 바닐라 드래곤 브레스 잔류 구름과 같은 파티클. */
		LINGER
	}

	/**
	 * 한 번 그은 선.
	 *
	 * @param cycle     몇 번째 주기의 선인가. 주기가 넘어갔는지 판단한다
	 * @param from      브레스가 출발하는 아레나 경계
	 * @param to        브레스가 닿는 반대편 경계
	 * @param leftEdge  장판 왼쪽 경계에 찍을 점들. 지면 높이가 들어 있다
	 * @param rightEdge 오른쪽 경계
	 * @param ignitedAt 장판이 붙은 틱. 아직이면 {@link #NOT_IGNITED}
	 */
	record Rift(long cycle, Vec3 from, Vec3 to, List<Vec3> leftEdge, List<Vec3> rightEdge,
			long ignitedAt) {

		Rift {
			leftEdge = List.copyOf(leftEdge);
			rightEdge = List.copyOf(rightEdge);
		}

		boolean ignited() {
			return ignitedAt != NOT_IGNITED;
		}

		Rift ignitedAt(long tick) {
			return new Rift(cycle, from, to, leftEdge, rightEdge, tick);
		}
	}

	private DragonRiftBreath() {
	}

	// ------------------------------------------------------------------ 매 틱

	/**
	 * 매 틱.
	 *
	 * <p>{@code dragon} 은 <b>읽기만 한다.</b> 브레스가 출발할 쪽을 고르고
	 * ({@link #startingNear}) 입의 자리를 가져오는({@link #breathStream}) 두 곳뿐이고, 값을 쓰는
	 * 호출은 한 줄도 없다. 왜 옮기지 않는지는 클래스 설명의 「드래곤은 읽기만 한다」에 있다.
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

		Rift rift = active;
		// 다 탄 장판을 먼저 치운다. 남겨 두면 다음 선을 못 긋는다.
		if (rift != null && rift.ignited() && !fieldAlive(rift.ignitedAt(), now)) {
			extinguish(end, members);
			active = null;
			rift = null;
		}

		int remaining = TrialRisks.remainingTicks(now, granted, PERIOD_TICKS);
		long cycle = TrialRisks.strikeIndex(now, granted, PERIOD_TICKS);

		if (remaining <= LEAD_TICKS && cycle != plannedCycle) {
			// 이번 주기는 여기서 한 번만 판단한다. 못 그으면 그냥 지나간다.
			plannedCycle = cycle;
			// 앞 선이 아직 타고 있으면 긋지 않는다 — 선 둘이 겹치면 교차점이 한 틱에 두 번 맞는
			// 자리가 되고 worstCaseTickDamage 가 거짓이 된다. PERIOD_TICKS 가 예고 + 장판보다
			// 길어 실제로는 오지 않는 길이다.
			if (rift == null && remaining >= MIN_LEAD_TICKS) {
				rift = onGround(end, plan(cycle, end.getRandom().nextDouble()));
				active = rift;
			}
		}
		if (rift == null) {
			return;
		}

		if (!rift.ignited() && remaining == SWEEP_TICKS) {
			// 브레스가 출발하는 그 틱에 방향을 정한다. 예고 내내 보여 준 것은 좌우 대칭인 띠라,
			// 여기서 뒤집어도 사람이 본 것은 하나도 바뀌지 않는다.
			rift = startingNear(rift, bodyOf(dragon));
			active = rift;
		}

		// 예고는 빨간 표식, 장판은 잔류 구름이다. 같은 자리를 그리지만 같은 모습이면 안 된다.
		show(end, rift);
		if (!rift.ignited()) {
			warn(end, dragon, members, rift, remaining);
			if (!TrialRisks.firesAt(now, granted, PERIOD_TICKS)) {
				return;
			}
			rift = rift.ignitedAt(now);
			active = rift;
			ignite(end, members);
		}
		burn(end, members, rift, now);
	}

	/**
	 * 월드가 바뀌거나 서버가 내려갈 때.
	 *
	 * <p>선은 좌표라 월드보다 오래 산다. 비우지 않으면 새 월드에서 <b>아무도 그은 적 없는
	 * 자리가 타고</b>, 그 증상은 「엔드에 들어가자마자 아프다」라는 엉뚱한 모양으로 나온다.
	 *
	 * <p>드래곤에 되돌릴 것은 없다. 이 패시브는 드래곤을 읽기만 하므로 <b>비울 상태가 좌표뿐</b>
	 * 이다 — 연출 도중 드래곤이 죽거나 서버가 내려가도 판에 남는 것이 없다.
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
	 * 브레스가 한 번 조용히 쉬면, 그 증상은 「가끔 안 나온다」라 아무도 못 잡는다.
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

	/** 시험용. 지금 선이 남아 있는가. */
	static @Nullable Rift active() {
		return active;
	}

	/** 시험용. 월드 없이 만든 선을 꽂아 둔다. */
	static void remember(@Nullable Rift rift) {
		active = rift;
	}

	/** 시험용. 어느 주기까지 판단이 끝났는가. */
	static long plannedCycle() {
		return plannedCycle;
	}

	/** 시험용. 주기 판단 기록만 남긴다. */
	static void notePlanned(long cycle) {
		plannedCycle = cycle;
	}

	// ------------------------------------------------------------------ 예고와 연출

	/**
	 * 경고 세 층을 올리고 브레스를 쓸어 보낸다.
	 *
	 * <p><b>선은 예고 내내 그린다</b>({@link #show}). {@code TrialRisks} 의 고리는 첫 층에서 소리만
	 * 내고 표식을 늦게 띄우지만, 이 패턴이 요구하는 행동은 「어느 쪽에 남을지 고르기」라 <b>선
	 * 자체가 고를 정보의 전부</b>다. 소리만 울리고 선이 없는 구간을 두면 그동안은 고를 것이 없다.
	 *
	 * <p>액션바 자막은 걷어냈다({@link TrialWarning#shout}). 「한쪽을 고르십시오」를 글자로 일러
	 * 주던 줄이 사라졌으므로 <b>선을 예고 내내 그리는 것이 더 중요해졌다</b> — 줄이지 말 것.
	 *
	 * <p>소리는 <b>사람마다 그 자리에서</b> 울린다. 바닐라 소리 사거리는 볼륨이 1 이하면 16칸인데
	 * 이 선은 80칸이라, 한 점에서 울리면 반대편에 선 사람에게 닿지 않는다. 파티클의 거리 제한을
	 * 끄는 것과 같은 이유다.
	 */
	private static void warn(ServerLevel end, @Nullable EnderDragon dragon,
			List<ServerPlayer> members, Rift rift, int remaining) {
		TrialWarning.Stage stage = TrialWarning.stageFor(remaining);
		if (stage != null && TrialRisks.stageJustChanged(remaining)) {
			for (ServerPlayer member : members) {
				TrialWarning.sound(end, member.position(), stage);
			}
		}
		if (remaining == SWEEP_TICKS) {
			// 볼륨 4 면 사거리가 64칸이다. 「어느 쪽에서 날아오는가」가 이 소리의 몫이라 출발점에서
			// 울려야 하고, 그래서 사람마다가 아니라 한 점이다. startingNear 가 이미 출발점을
			// 드래곤 쪽으로 돌려 놓았으므로 소리도 드래곤 쪽에서 난다.
			end.playSound(null, rift.from().x, rift.from().y, rift.from().z,
					SoundEvents.ENDER_DRAGON_SHOOT, SoundSource.HOSTILE, 4.0F, 0.8F);
		}
		if (remaining <= SWEEP_TICKS) {
			drawSweep(end, rift, sweepProgress(remaining), mouthOf(dragon));
		}
	}

	/**
	 * 장판이 붙는 순간.
	 *
	 * <p>여기서 피해를 주지 않는다. 붙는 틱의 피해는 {@link #burn} 의 첫 점이 맡는다 — 두 곳에서
	 * 때리면 같은 순간에 두 번 맞는다.
	 */
	private static void ignite(ServerLevel end, List<ServerPlayer> members) {
		for (ServerPlayer member : members) {
			Vec3 at = member.position();
			end.playSound(null, at.x, at.y, at.z, SoundEvents.DRAGON_FIREBALL_EXPLODE,
					SoundSource.HOSTILE, 0.8F, 1.2F);
		}
	}

	/** 장판이 꺼지는 순간. 「이제 건너도 된다」를 알린다. */
	private static void extinguish(ServerLevel end, List<ServerPlayer> members) {
		for (ServerPlayer member : members) {
			Vec3 at = member.position();
			end.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH,
					SoundSource.HOSTILE, 0.7F, 0.8F);
		}
	}

	/**
	 * 지금 이 선을 어떤 모습으로 보여 줄 것인가.
	 *
	 * <p>예고와 장판이 <b>반드시 달라야</b> 한다. 같으면 「아직 안 터진 건가」로 읽혀 사람이
	 * 장판 위를 그냥 걸어 들어간다.
	 */
	private static void show(ServerLevel end, Rift rift) {
		if (lookFor(rift) == Look.LINGER) {
			pour(end, rift);
			return;
		}
		ParticleOptions warning = mark(Look.WARNING);
		drawEdge(end, rift.leftEdge(), warning);
		drawEdge(end, rift.rightEdge(), warning);
	}

	/**
	 * 예고 — 선의 두 경계를 빨간 표식으로 그린다.
	 *
	 * <p>가운데가 아니라 <b>양 경계</b>를 그리는 이유는, 사람이 알아야 하는 것이 「선이 어디
	 * 있는가」가 아니라 <b>「어디부터 아픈가」</b>이기 때문이다. 고리가 반경을 그려 주는 것과 같다.
	 *
	 * <p>색은 {@link TrialWarning.Colors#DEADLY} 하나뿐이다. 「서 있으면 죽는다」가 정확히 이
	 * 장판의 뜻이고, 규약에 없는 색을 새로 만들면 그 순간 규약이 장식이 된다.
	 *
	 * <p><b>긴 형태로 보낸다</b>({@code overrideLimiter=true}). 짧은 형태는 32칸에서 잘리는데 이
	 * 선은 80칸이라, 되돌리는 순간 <b>반대편 끝이 통째로 안 보인다</b> — 아레나가 갈렸다는 것을
	 * 보여 주는 것이 이 패턴의 전부이므로 그러면 패턴이 사라진다.
	 */
	private static void drawEdge(ServerLevel end, List<Vec3> edge, ParticleOptions options) {
		for (Vec3 point : edge) {
			end.sendParticles(options, true, false,
					point.x, point.y + GROUND_OFFSET, point.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}

	/**
	 * 장판 — 잔류 구름을 면으로 깐다.
	 *
	 * <p>경계 두 줄은 <b>흩지 않고</b> 제 자리에 그대로 찍는다. 「어디부터 아픈가」는 한 칸도
	 * 틀리면 안 되는 정보다. 안쪽만 흩어 뿌려 면을 만든다 — 흩는 폭이 점 간격의 절반쯤이라
	 * 이웃한 점과 만나고, 그래서 점 수를 늘리지 않고도 슬래브처럼 보인다.
	 *
	 * <p>경계가 상한 밖인 것이 중요하다. 상한에 걸려 잘려야 하는 것은 <b>안쪽 채움</b>이고,
	 * 경계가 성겨지면 장판이 어디서 끝나는지를 잃는다.
	 */
	private static void pour(ServerLevel end, Rift rift) {
		ParticleOptions cloud = mark(Look.LINGER);
		drawEdge(end, rift.leftEdge(), cloud);
		drawEdge(end, rift.rightEdge(), cloud);
		for (Vec3 point : fieldFill(rift.leftEdge(), rift.rightEdge())) {
			end.sendParticles(cloud, true, false,
					point.x, point.y + GROUND_OFFSET, point.z,
					FILL_PARTICLES, FILL_SPREAD, FILL_LIFT, FILL_SPREAD, FILL_DRIFT);
		}
	}

	/**
	 * 쓸고 지나가는 브레스.
	 *
	 * <p>선 전체를 한꺼번에 뿜지 않고 <b>지금까지 지나온 자리</b>의 앞머리에만 몰아 찍는다. 그래야
	 * 「드래곤이 한쪽에서 반대쪽으로 그었다」로 읽히고, 어느 쪽에서 오는지가 보인다.
	 *
	 * <p>드래곤이 살아 있으면 <b>그 입에서 바닥의 앞머리까지 줄기를 잇는다.</b> 이것이 「드래곤이
	 * 직접 뿜는다」를 드래곤을 한 칸도 안 옮기고 얻는 방법이다 — 드래곤이 어디에 있든 선이
	 * 그쪽에서 나오는 것으로 보인다.
	 *
	 * <p>이것도 긴 형태다. 브레스가 출발하는 경계는 반대편 사람에게서 80칸이고, 줄기는 하늘까지
	 * 이어지므로 더 멀다.
	 */
	private static void drawSweep(ServerLevel end, Rift rift, double progress,
			@Nullable Vec3 mouth) {
		Vec3 head = headAt(rift.from(), rift.to(), progress).add(0.0, 0.5, 0.0);
		ParticleOptions breath = mark(Look.LINGER);
		if (mouth != null) {
			for (Vec3 point : breathStream(mouth, head)) {
				end.sendParticles(breath, true, false,
						point.x, point.y, point.z, 1, 0.0, 0.0, 0.0, 0.0);
			}
		}
		end.sendParticles(breath, true, false, head.x, head.y, head.z, 24,
				HALF_WIDTH * 0.5, 0.4, HALF_WIDTH * 0.5, 0.02);
		end.sendParticles(ParticleTypes.LARGE_SMOKE, true, false, head.x, head.y, head.z, 6,
				HALF_WIDTH * 0.5, 0.3, HALF_WIDTH * 0.5, 0.0);
	}

	/** 드래곤 입의 자리. 드래곤이 없거나 죽었으면 {@code null}. <b>읽기만 한다.</b> */
	private static @Nullable Vec3 mouthOf(@Nullable EnderDragon dragon) {
		if (dragon == null || !dragon.isAlive()) {
			return null;
		}
		return dragon.head.position();
	}

	/** 드래곤 몸의 자리. 선의 출발점을 고를 때만 쓴다. <b>읽기만 한다.</b> */
	private static @Nullable Vec3 bodyOf(@Nullable EnderDragon dragon) {
		if (dragon == null || !dragon.isAlive()) {
			return null;
		}
		return dragon.position();
	}

	// ------------------------------------------------------------------ 피해

	/**
	 * 장판을 밟고 있는 사람을 태운다.
	 *
	 * <h2>팀에게 한 번만</h2>
	 *
	 * <p>안에 선 사람을 모두 때리면 넷이 함께 건널 때 초당 16 이다. 그런데 <b>함께 건너는 것이
	 * 이 패턴에 대한 올바른 대응</b>이라, 그러면 올바른 대응이 전멸이 된다. 설계의 「같은 공격은
	 * 팀에게 한 번만」이 정확히 이 경우를 위해 있다.
	 *
	 * <p>장비도 공유라 누가 대표로 맞든 들어가는 피해가 같다. {@link SharedEffectDamage} 가 공유된
	 * 상태이상을 대표 한 명만 남기고 막는 것과 같은 결이다.
	 *
	 * <p>피해원에 가해 개체를 달지 않는다. 달면 {@link SharedAreaDamage} 가 대신 접어 주지만
	 * {@code dragon_breath} 는 {@code when_caused_by_living_non_player} 로 스케일링되어 어려움
	 * 난이도에서 값이 ×1.5 가 된다 — 하드코어는 난이도가 고정이라 적어 둔 값이 늘 ×1.5 다.
	 */
	private static void burn(ServerLevel end, List<ServerPlayer> members, Rift rift, long now) {
		if (!pulsesAt(rift.ignitedAt(), now)) {
			return;
		}
		for (ServerPlayer member : members) {
			if (!insideField(member.position(), rift.from(), rift.to(), HALF_WIDTH)) {
				continue;
			}
			member.hurtServer(end, end.damageSources().dragonBreath(), DAMAGE_PER_PULSE);
			// 하나의 장판은 하나의 공격이다. 나머지는 같은 공격의 두 번째 몫이라 세지 않는다.
			return;
		}
	}

	// ------------------------------------------------------------------ 선을 놓는다

	/**
	 * 월드를 모르는 선. 높이는 전부 0 이다.
	 *
	 * <p>지면을 읽는 부분과 <b>기하</b>를 갈라 둔다. 「중앙을 지나는가」·「양 끝이 경계에 닿는가」·
	 * 「점이 촘촘한가」는 월드 없이 답이 정해지는 계산이라, 떼어 두면 서버를 띄우지 않고 시험할 수
	 * 있다.
	 *
	 * @param angleRoll 0~1 의 굴림. 각도가 된다. 실전에서는 {@code end.getRandom()} 이 준다
	 */
	static Rift plan(long cycle, double angleRoll) {
		Vec3 axis = axisFor(angleRoll);
		Vec3 from = axis.scale(-TrialRisks.ARENA_RADIUS);
		Vec3 to = axis.scale(TrialRisks.ARENA_RADIUS);
		Vec3 side = sideOf(axis).scale(HALF_WIDTH);
		int points = linePoints(from.distanceTo(to));
		List<Vec3> left = new ArrayList<>(points);
		List<Vec3> right = new ArrayList<>(points);
		for (int index = 0; index < points; index++) {
			Vec3 spine = pointOn(from, to, index, points);
			left.add(spine.subtract(side));
			right.add(spine.add(side));
		}
		return new Rift(cycle, from, to, left, right, NOT_IGNITED);
	}

	/**
	 * 그 선을 실제 지면에 얹는다.
	 *
	 * <p>높이를 <b>그을 때 한 번만</b> 찾는다. 매 틱 하이트맵을 백 번 넘게 두드리면 21초 내내
	 * 청크를 뒤지게 되고, 어차피 장판이 사는 동안 지면은 바뀌지 않는다 — 이 패턴은 블록을 한 칸도
	 * 건드리지 않으니 더욱 그렇다.
	 */
	private static Rift onGround(ServerLevel end, Rift flat) {
		double base = groundY(end, 0.0, 0.0, FALLBACK_GROUND_Y);
		return new Rift(flat.cycle(),
				lift(end, flat.from(), base),
				lift(end, flat.to(), base),
				liftAll(end, flat.leftEdge(), base),
				liftAll(end, flat.rightEdge(), base),
				flat.ignitedAt());
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
	 * 이 선을 지금 어떤 모습으로 보여야 하는가.
	 *
	 * <p>붙기 전에는 예고고 붙은 뒤에는 장판이다. 갈림이 여기 한 군데라 <b>둘이 같아지는 길이
	 * 없다</b> — 이 함수가 두 값을 돌려주는 한 사람은 둘을 구분해 배울 수 있다.
	 */
	static Look lookFor(Rift rift) {
		return rift.ignited() ? Look.LINGER : Look.WARNING;
	}

	/**
	 * 그 모습에 쓰는 파티클.
	 *
	 * <p>{@link Look#LINGER} 쪽은 26.3 {@code DragonSittingFlamingPhase} 가 제 잔류 구름에
	 * {@code setCustomParticle} 으로 넣는 값을 그대로 가져온 것이다. 일반 잔류 포션 구름의
	 * {@code ColorParticleOption.create(ENTITY_EFFECT, 색)} 이 아니라 <b>드래곤 쪽</b>을 고른
	 * 이유는, 이 선을 그은 것이 드래곤이기 때문이다.
	 */
	static ParticleOptions mark(Look look) {
		return look == Look.LINGER
				? PowerParticleOption.create(ParticleTypes.DRAGON_BREATH, LINGER_POWER)
				: TrialWarning.dust(TrialWarning.Colors.DEADLY);
	}

	/**
	 * 브레스가 드래곤 쪽 끝에서 출발하게 선의 방향을 맞춘다.
	 *
	 * <p><b>드래곤을 선에 맞추는 것이 아니라 선을 드래곤에 맞춘다.</b> 드래곤을 옮기면 몸이
	 * 사람을 때리고 블록을 부수지만(클래스 설명 참조), 선은 우리 것이라 공짜로 돌릴 수 있다.
	 *
	 * <p>높이는 보지 않는다. 드래곤은 아레나보다 수십 칸 위를 날아서, 세로를 섞으면 양 끝이
	 * 똑같이 멀어져 굴림이 사실상 방향을 정하게 된다.
	 *
	 * @param breather 드래곤 자리. {@code null} 이면 굴림이 정한 방향을 그대로 둔다
	 */
	static Rift startingNear(Rift rift, @Nullable Vec3 breather) {
		if (breather == null) {
			return rift;
		}
		if (flatDistanceSqr(breather, rift.from()) <= flatDistanceSqr(breather, rift.to())) {
			return rift;
		}
		// 방향을 뒤집으면 왼쪽 경계가 오른쪽 경계가 된다. 점 순서는 그리는 데 쓰지 않으므로
		// 그대로 두고 이름만 맞바꾼다.
		return new Rift(rift.cycle(), rift.to(), rift.from(),
				rift.rightEdge(), rift.leftEdge(), rift.ignitedAt());
	}

	/** 위에서 본 거리의 제곱. 높이를 버린다. */
	static double flatDistanceSqr(Vec3 one, Vec3 other) {
		double dx = one.x - other.x;
		double dz = one.z - other.z;
		return dx * dx + dz * dz;
	}

	/**
	 * 드래곤 입에서 바닥의 브레스 앞머리까지 잇는 줄기.
	 *
	 * <p>이 줄기가 「드래곤이 뿜었다」를 말하는 전부다. 드래곤을 옮기지 않기로 했으므로 몸과
	 * 장판을 잇는 것은 이 선뿐이고, <b>끊기면 인과가 사라진다</b> — 그래서 간격이 아니라 상한을
	 * 먼저 지키고 남는 길이에서 간격을 벌린다. 점선이라도 이어져 있는 편이, 가까운 쪽만 촘촘하고
	 * 먼 쪽이 없는 것보다 낫다.
	 *
	 * @return 첫 점이 입, 마지막 점이 바닥이다
	 */
	static List<Vec3> breathStream(Vec3 mouth, Vec3 ground) {
		int points = (int) Math.ceil(mouth.distanceTo(ground) / STREAM_GAP) + 1;
		points = Math.max(2, Math.min(STREAM_MAX_POINTS, points));
		List<Vec3> line = new ArrayList<>(points);
		Vec3 span = ground.subtract(mouth);
		for (int index = 0; index < points; index++) {
			line.add(mouth.add(span.scale((double) index / (points - 1))));
		}
		return line;
	}

	/**
	 * 경계 사이에 넣을 줄 수. 0 이면 경계 두 줄만 그린다.
	 *
	 * <p>폭에서 뽑는다 — 숫자를 박아 두면 {@link #HALF_WIDTH} 를 올렸을 때 줄이 안 늘어 가운데가
	 * 빈 띠가 된다.
	 */
	static int fillLanes(double halfWidth) {
		if (!(halfWidth > 0.0)) {
			return 0;
		}
		int lanes = (int) Math.ceil(halfWidth * 2.0 / FILL_GAP) - 1;
		return Math.max(0, Math.min(FILL_MAX_LANES, lanes));
	}

	/**
	 * 안쪽 한 줄에 찍을 점 수.
	 *
	 * <p>간격에서 뽑고 상한으로 자른다. 개수를 고정하면 아레나를 넓히는 순간 같은 점이 더 긴
	 * 띠에 흩어져 면이 아니라 격자가 된다.
	 */
	static int fillAlong(int lanes) {
		if (lanes <= 0) {
			return 0;
		}
		int wanted = (int) Math.ceil(LINE_KEPT_LENGTH / FILL_GAP) + 1;
		return Math.max(2, Math.min(wanted, FILL_MAX_POINTS / lanes));
	}

	/**
	 * 장판 안쪽을 채우는 점들. <b>경계 두 줄은 여기 들어 있지 않다.</b>
	 *
	 * <p>높이를 다시 읽지 않는다. 같은 번째의 왼쪽 경계와 오른쪽 경계를 이어 그 사이를 나누므로
	 * 지면 높이가 저절로 따라온다 — 하이트맵을 매 틱 수십 번 두드리지 않는 이유가 이것이다.
	 *
	 * @return 상한 안쪽. 폭이 좁아 넣을 줄이 없으면 빈 목록
	 */
	static List<Vec3> fieldFill(List<Vec3> leftEdge, List<Vec3> rightEdge) {
		int edgePoints = Math.min(leftEdge.size(), rightEdge.size());
		if (edgePoints < 2) {
			return List.of();
		}
		int lanes = fillLanes(HALF_WIDTH);
		int along = fillAlong(lanes);
		if (lanes <= 0 || along <= 0) {
			return List.of();
		}
		List<Vec3> fill = new ArrayList<>(lanes * along);
		for (int lane = 1; lane <= lanes; lane++) {
			// 0 과 1 은 경계라 뺀다. 경계는 흩지 않고 따로 찍는다.
			double across = (double) lane / (lanes + 1);
			for (int step = 0; step < along; step++) {
				int index = (int) Math.round((double) step * (edgePoints - 1) / (along - 1));
				Vec3 left = leftEdge.get(index);
				fill.add(left.add(rightEdge.get(index).subtract(left).scale(across)));
			}
		}
		return fill;
	}

	/**
	 * 굴림 하나를 아레나를 가르는 방향으로 바꾼다. 길이 1 이고 높이는 0 이다.
	 *
	 * <p>반 바퀴({@code π})만 쓴다. 선은 <b>양방향</b>이라 θ 와 θ+π 가 같은 선이고, 한 바퀴를 다
	 * 쓰면 같은 선이 두 번 나오는 셈이다. 출발점이 어느 쪽인지는 굴림이 아니라
	 * {@link #startingNear} 가 드래곤을 보고 마지막에 정한다.
	 */
	static Vec3 axisFor(double angleRoll) {
		double angle = Math.max(0.0, Math.min(1.0, angleRoll)) * Math.PI;
		return new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
	}

	/** 그 방향에 수직인 방향. 장판 폭이 이쪽으로 뻗는다. */
	static Vec3 sideOf(Vec3 axis) {
		Vec3 flat = new Vec3(axis.x, 0.0, axis.z);
		if (flat.lengthSqr() < 1.0E-9) {
			return new Vec3(0.0, 0.0, 1.0);
		}
		Vec3 unit = flat.normalize();
		return new Vec3(-unit.z, 0.0, unit.x);
	}

	/**
	 * 선 위 {@code index} 번째 점. 0 이 {@code from}, 마지막이 {@code to} 다.
	 *
	 * <p>끝을 정확히 {@code to} 로 맞추는 것이 중요하다. 한 칸 모자라게 그리면 아레나 경계에 틈이
	 * 생기고, 사람은 그 틈으로 지나갈 수 있다고 읽는다.
	 */
	static Vec3 pointOn(Vec3 from, Vec3 to, int index, int points) {
		if (points <= 1) {
			return from;
		}
		double along = (double) Math.max(0, Math.min(points - 1, index)) / (points - 1);
		return from.add(to.subtract(from).scale(along));
	}

	/**
	 * 그 길이의 선에 찍을 점 수.
	 *
	 * <p>개수가 아니라 <b>간격</b>을 정하고 개수를 거기서 뽑는다. 개수를 고정하면 아레나를 넓히는
	 * 순간 같은 점이 더 긴 선에 흩어져 점선이 된다.
	 */
	static int linePoints(double length) {
		if (!(length > 0.0)) {
			return 0;
		}
		int wanted = (int) Math.ceil(length / LINE_POINT_GAP) + 1;
		return Math.max(2, Math.min(LINE_MAX_POINTS, wanted));
	}

	/** 그 길이에서 실제로 벌어지는 점 사이 거리(칸). 「선으로 읽히는가」를 숫자로 묻는 값이다. */
	static double lineGap(double length) {
		int points = linePoints(length);
		if (points < 2) {
			return 0.0;
		}
		return length / (points - 1);
	}

	/**
	 * 장판 안에 서 있는가. 선분까지의 거리가 폭 안이면 안이다.
	 *
	 * <p>세로는 보지 않는다 — {@code TrialRisks.insideMark} 와 같은 이유다. 표식은 바닥에 그려지고,
	 * 「표식 위에 떠 있었으니 안 맞는다」가 되면 표식이 거짓말한 것이 된다.
	 */
	static boolean insideField(Vec3 position, Vec3 from, Vec3 to, double halfWidth) {
		return distanceToSegment(position, from, to) <= Math.max(0.0, halfWidth);
	}

	/**
	 * 점에서 선분까지의 거리(위에서 본 거리).
	 *
	 * <p>직선이 아니라 <b>선분</b>이다. 무한 직선으로 재면 아레나 밖 허공 연장선에도 장판이 있는
	 * 셈이 되고, 브레스가 닿지 않은 자리가 아픈 것은 표식이 거짓말하는 것이다.
	 */
	static double distanceToSegment(Vec3 position, Vec3 from, Vec3 to) {
		double lineX = to.x - from.x;
		double lineZ = to.z - from.z;
		double lengthSqr = lineX * lineX + lineZ * lineZ;
		double toPointX = position.x - from.x;
		double toPointZ = position.z - from.z;
		if (lengthSqr < 1.0E-9) {
			return Math.sqrt(toPointX * toPointX + toPointZ * toPointZ);
		}
		double along = Math.max(0.0, Math.min(1.0,
				(toPointX * lineX + toPointZ * lineZ) / lengthSqr));
		double dx = toPointX - lineX * along;
		double dz = toPointZ - lineZ * along;
		return Math.sqrt(dx * dx + dz * dz);
	}

	/** 브레스가 지금 어디까지 왔는가. 0 이 출발점, 1 이 반대편 끝. */
	static Vec3 headAt(Vec3 from, Vec3 to, double progress) {
		double along = Math.max(0.0, Math.min(1.0, progress));
		return from.add(to.subtract(from).scale(along));
	}

	/**
	 * 쓸고 지나가는 비율.
	 *
	 * <p>남은 틱으로 센다. <b>도착이 곧 발동</b>이어야 브레스가 반대편에 닿는 틱과 장판이 붙는 틱이
	 * 같아지고, 그래야 「지나간 자리가 장판이 된다」가 눈에 맞는다.
	 */
	static double sweepProgress(int remainingTicks) {
		if (SWEEP_TICKS <= 0) {
			return 1.0;
		}
		if (remainingTicks >= SWEEP_TICKS) {
			return 0.0;
		}
		if (remainingTicks <= 0) {
			return 1.0;
		}
		return (double) (SWEEP_TICKS - remainingTicks) / SWEEP_TICKS;
	}

	/** 장판이 아직 살아 있는가. 붙은 틱부터 {@link #FIELD_TICKS} 동안이다. */
	static boolean fieldAlive(long ignitedAt, long now) {
		if (ignitedAt == NOT_IGNITED) {
			return false;
		}
		long burned = now - ignitedAt;
		return burned >= 0L && burned < FIELD_TICKS;
	}

	/** 이번 틱에 피해가 들어가는가. 붙는 그 틱이 첫 점이다. */
	static boolean pulsesAt(long ignitedAt, long now) {
		if (!fieldAlive(ignitedAt, now) || DAMAGE_PERIOD_TICKS <= 0) {
			return false;
		}
		return (now - ignitedAt) % DAMAGE_PERIOD_TICKS == 0L;
	}

	/** 장판 하나가 끝까지 서 있는 사람에게 주는 점의 수. */
	static int pulseCount() {
		if (DAMAGE_PERIOD_TICKS <= 0) {
			return 0;
		}
		return (FIELD_TICKS + DAMAGE_PERIOD_TICKS - 1) / DAMAGE_PERIOD_TICKS;
	}

	/**
	 * 걸어서 장판을 가로지르는 동안 받는 가장 큰 피해.
	 *
	 * <p>폭과 피해를 잇는 한 손잡이다. <b>폭을 올리는 사람은 이 값을 보고 피해를 함께 내려야
	 * 한다</b> — 값에서 직접 계산해 두면 시험이 붙잡을 수 있고, 카드 문서에 적는 숫자도 여기서
	 * 나온다.
	 *
	 * <p>가장 짧은 길인 <b>폭을 수직으로</b> 건너는 것으로 잰다. 비스듬히 들어간 사람은 더 오래
	 * 걸리지만, 그쪽은 「어쩔 수 없이 갇힌」 경우가 아니라 고른 경우다.
	 *
	 * @param blocksPerSecond 걸음 속도. 바닐라 걷기는 4.317 이다
	 */
	static float crossingDamage(double blocksPerSecond) {
		if (!(blocksPerSecond > 0.0) || DAMAGE_PERIOD_TICKS <= 0) {
			return 0.0F;
		}
		int crossTicks = (int) Math.ceil(HALF_WIDTH * 2.0 / (blocksPerSecond / 20.0));
		// 하필 맞는 틱에 들어선 사람이 최악이다. 그래서 나눗셈 몫에 첫 점을 더한다.
		return (crossTicks / DAMAGE_PERIOD_TICKS + 1) * DAMAGE_PER_PULSE;
	}

	/**
	 * 한 사람이 이 패시브에게서 <b>한 틱에</b> 받을 수 있는 가장 큰 피해.
	 *
	 * <p>{@code TrialRisks.worstCaseTickDamage} 와 같은 몫이다 — 「즉사 메커닉 0개」가 지켜지는지를
	 * 값에서 직접 계산해 두면 시험이 붙잡을 수 있다. 값을 올리는 사람은 피해만 보고 겹침 수를 보지
	 * 않는다.
	 */
	static float worstCaseTickDamage() {
		if (DAMAGE_PER_PULSE <= 0.0F || MAX_CONCURRENT_RIFTS <= 0) {
			return 0.0F;
		}
		return DAMAGE_PER_PULSE * MAX_CONCURRENT_RIFTS;
	}
}
