package com.sharedfate.sync;

import com.mojang.math.Transformation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「종말의 비」({@link TrialEndRain})가 바닥에 깔는 <b>보라 투명 면</b>. 파티클이 아니라
 * <b>디스플레이 개체</b>로 반경 2.5 짜리 원을 채운다.
 *
 * <h2>왜 생겼는가 — 사람이 테두리를 물렸다</h2>
 *
 * <p>사람이 <b>「보라색 테두리로는 가시성 진짜 안좋으니까 차라리 보라색 바닥으로 투명바닥으로
 * 표시하고」</b>라고 했다. 전에는 반경 2.5 둘레에 먼지 점 18개를 찍는 <b>고리</b>였는데, 한
 * 볼리에 60~90개가 한꺼번에 뜨는 카드라 작은 고리들이 서로 묻혀 「여기가 위험하다」가 안
 * 읽혔다.
 *
 * <p>같은 물음을 <b>「최후의 저항」의 부채꼴 브레스가 이미 풀었다</b>
 * ({@link DragonLastStandConePanel} — 사람이 그때는 「투명면을 덮어서 빨간 투명면 그런걸로 안
 * 되나?」라고 물었다). 그 파일의 판단과 수법을 그대로 가져왔고, 다른 것은 <b>모양이 부채꼴이
 * 아니라 원</b>이라는 것 하나다.
 *
 * <p>⚠ <b>점을 한 개도 쓰지 않는다.</b> 이것이 이 카드에서 특히 중요하다 — 한 볼리에 90곳이
 * 살아 있으므로 곳마다 점을 더하면 한 틱 예산({@link TrialEndRain#POINT_BUDGET})이 그 자리에서
 * 터진다. 반대로 고리를 걷어내면 <b>270점이 통째로 비고</b>, 그 몫이 사람이 함께 말한
 * 「떨어지는걸 좀더 보이게」로 넘어간다({@link TrialEndRain#ORB_POINTS_PER_SPOT}).
 *
 * <p>통신 규약도 올라가지 않는다. {@code block_display} 는 바닐라 개체라 <b>모드를 안 깐
 * 사람에게도 그대로 보인다.</b>
 *
 * <h2>색 — 면은 보라, 구체는 빨강이다</h2>
 *
 * <p><b>한 카드에 색이 둘인 것은 알고 한 일이고, 둘 다 사람이 정했다.</b>
 *
 * <ul>
 *   <li><b>면은 보라</b> — 이번에 사람이 「보라색 바닥으로」라고 한 그것이다. 바닥은 흰 엔드스톤
 *       이라 보라가 가장 멀리서 갈린다</li>
 *   <li><b>구체는 빨강</b>({@link TrialEndRain#MARK_COLOR}) — 전에 사람이 <b>「보라색이아닌
 *       빨간색원으로다시 복귀하자 이번건 너무 가시성이안좋아」</b>라고 직접 물려 돌아온 값이다.
 *       <b>구체는 하늘을 배경으로 본다</b>: 엔드 하늘은 어두운 보랏빛이라 보라 구체는 배경에
 *       묻힌다 — 사람이 그때 본 것이 정확히 그것이었다고 본다</li>
 * </ul>
 *
 * <p>곧 <b>배경이 둘이라 색도 둘</b>이다. 같은 색으로 묶으면 한쪽은 반드시 배경에 묻힌다.
 *
 * <p>⚠ <b>색 규약({@link TrialWarning.Colors})에 색을 더하지 않았다.</b> 면의 색은 우리가 고르는
 * 16진수가 아니라 <b>블록이 들고 있는 것</b>이고({@link #glass()}), 규약이 지키려는 것은 값이
 * 아니라 뜻이다 — {@code DragonLastStandConePanel} 이 붉은 색유리를 쓰면서 한 판단과 같다.
 * 다만 <b>이 면은 규약의 보라({@code MARKED} = 「너 하나를 노린다」)와 뜻이 다르다</b>는 것을
 * 적어 둔다. 사람이 「바닥」으로 지정한 색이라 그대로 쓴 것이고, 「표적」({@link TrialDragonFocus})
 * 과는 <b>모양</b>으로 갈린다 — 그쪽은 사람을 따라다니는 작은 고리이고 이쪽은 땅에 못박힌 면이다.
 *
 * <h2>원은 사각형이 아니다 — 판 다섯 장으로 <b>안쪽에서</b> 채운다</h2>
 *
 * <p>디스플레이 개체가 그리는 것은 <b>상자 하나</b>다. 원을 낼 수 없으므로 원을 z축에 수직인
 * <b>띠</b>로 잘라 띠마다 판 한 장을 놓는다. 띠는 서로 겹치지 않아 <b>반투명이 겹쳐 얼룩지지
 * 않는다</b>({@code DragonLastStandConePanel} 이 같은 이유로 같은 모양을 쓴다).
 *
 * <p>⚠ <b>자른 자리를 각도로 고르게 나눈다</b>({@link #QUADRANT_STEPS}). 깊이를 일정하게 자르는
 * 것보다 <b>같은 판 수로 더 많이 덮는다</b> — 반경 2.5 에서 재어 본 값이다.
 *
 * <table border="1">
 *   <caption>반경 2.5 를 채우는 방법별 비교</caption>
 *   <tr><th>방법</th><th>판 수</th><th>덮는 몫</th><th>경계가 모자라는 폭</th></tr>
 *   <tr><td>깊이 1칸씩</td><td>3</td><td>65.7%</td><td>1.00칸</td></tr>
 *   <tr><td>깊이 0.625칸씩</td><td>6</td><td>79.4%</td><td>0.63칸</td></tr>
 *   <tr><td><b>각도 22.5도씩</b> ← 고른 것</td><td><b>5</b></td><td><b>84.8%</b></td>
 *       <td><b>0.19칸</b></td></tr>
 * </table>
 *
 * <p>각도로 나누면 띠의 반폭이 {@code r·cos θ} 이고 경계가 {@code r·sin θ} 이라, <b>판의 먼 쪽
 * 귀퉁이가 전부 원 위에 정확히 놓인다.</b> 곧 칠한 면은 <b>원에 내접하는 계단 다각형</b>이고
 * 「면이 원 밖을 칠하는」 길이 값에 없다 — {@code TrialEndRainPanelTest} 가 귀퉁이를 전부
 * <b>피해를 가르는 그 함수</b>({@link TrialRisks#insideMark})에 넣어 본다.
 *
 * <p>대가는 <b>경계가 {@link #edgeGap} 만큼 안쪽으로 모자란다</b>는 것이다. 반경 2.5 에서
 * <b>0.19칸</b>이고, 이것은 <b>걷어낸 고리가 가지고 있던 오차보다 작다</b> — 그 고리는 둘레
 * 15.71칸에 점 18개라 점 사이가 <b>0.87칸</b>이었다. 곧 면으로 바꾸면서 경계가 흐려진 것이
 * 아니라 <b>네 배 또렷해졌다.</b>
 *
 * <p>⚠ <b>모자라는 쪽이 「위험한데 안 칠한 쪽」이라는 것을 알고 둔다.</b> 반대로 원 밖까지
 * 칠하면 안전한 바닥을 위험하다고 말하게 되고, 그러면 사람이 <b>피할 필요가 없던 자리에서
 * 비킨다</b> — 이 전투가 표식을 믿게 만드는 유일한 길이 「표식은 거짓말하지 않는다」라서, 둘 중
 * 하나를 골라야 하면 <b>안쪽</b>으로 모자라는 쪽이다(부채꼴 면이 같은 자리에서 같은 선택을 한다).
 * 0.19칸은 사람 몸통 폭(0.6칸)의 3분의 1이라 그 띠에만 서 있을 수가 없다.
 *
 * <h2>⚠ 겹친 자리는 <b>층을 갈라</b> 놓는다</h2>
 *
 * <p>이 카드는 표식끼리 겹치는 것이 허용된 유일한 카드이고({@code TrialEndRain} 의 「겹침 금지
 * 목록 밖이다」) 겹치는 쌍이 볼리마다 <b>60쌍 남짓</b>이다. 두 판이 <b>같은 높이의 같은 평면</b>에
 * 놓이면 반투명이 어룽거리므로 자리마다 {@link #layerLift} 만큼 다른 높이에 둔다 — 그러면 겹친
 * 자리가 <b>두 겹으로 짙어져</b> 가장 위험한 자리가 가장 짙게 보인다.
 *
 * <h2>높이는 <b>중심 하나</b>를 쓴다</h2>
 *
 * <p>판 한 장은 평면이라 지형을 따라갈 수 없다. 띠마다 지표를 재는 길도 있지만
 * ({@code DragonLastStandConePanel} 이 20칸짜리 부채꼴에서 그렇게 한다) <b>이 카드는 지름이
 * 5칸</b>이고, 걷어낸 고리도 중심 하나의 높이로 그렸다 — 그쪽 근거를 그대로 옮긴다: 점마다(띠마다)
 * 지표를 물으면 허공에 걸린 몫을 건너뛰어 <b>표식이 끊기는데</b>, 피해 판정
 * ({@link TrialRisks#insideMark})은 높이를 보지 않으므로 <b>끊긴 자리가 안전해 보이면서 실제로는
 * 맞는다.</b> 조금 파묻히는 쪽이 거짓말하는 쪽보다 낫다.
 *
 * <p>덤이 하나 있다. 지점마다 {@code getHeightmapPos} 를 다시 두드리지 않으므로 볼리를 여는 틱에
 * <b>하이트맵을 한 번도 더 묻지 않는다</b> — 90곳 × 다섯 띠면 450번이 될 자리였다.
 *
 * <h2>⚠⚠ 개체는 월드에 저장된다 — 그것을 <b>두 겹</b>으로 막는다</h2>
 *
 * <p>파티클과 달리 개체는 남는다. 남으면 <b>다음 판까지 보라 판이 깔려 있고</b>, 그것이 월드 저장
 * 파일에 들어가면 「월드와 함께 사라진다」가 성립하지 않는다.
 *
 * <ol>
 *   <li><b>저장을 아예 안 하게 만들었다</b>({@link Panel#shouldBeSaved()}). 26.3
 *       {@code PersistentEntitySectionManager.storeChunkSections} 가 저장할 개체를
 *       {@code EntityAccess::shouldBeSaved} 로 거른다. 거짓을 돌려주면 <b>서버가 강제 종료되어도
 *       파일에 한 줄도 안 들어간다</b> — 그 길에는 {@code SERVER_STOPPING} 도 오지 않으므로
 *       지우는 코드로는 막을 수 없다</li>
 *   <li><b>스스로 타 없어지는 심지</b>({@link #fuseTicks}). 개체가 제 {@code tick()} 에서 남은
 *       틱을 세고 0 이 되면 {@code discard()} 한다. 지우는 줄을 아무도 못 지나도
 *       <b>예고 길이 + {@link #FUSE_MARGIN_TICKS} 뒤에는 반드시 사라진다</b></li>
 * </ol>
 *
 * <p>그 위에 <b>제때 지우는 길 넷</b>이 있다. 심지는 그 넷이 전부 실패했을 때의 바닥이다.
 *
 * <ul>
 *   <li><b>볼리가 터지는 틱</b> — {@code TrialEndRain.land}. 터진 자리는 <b>즉시 안전</b>하므로
 *       면이 한 틱이라도 더 남으면 이미 안전한 자리가 위험해 보인다({@code DragonFireBarrage} 가
 *       터진 고리를 그 틱에 지우는 것과 같은 규칙이다)</li>
 *   <li><b>새 볼리를 열 때</b> — {@link #raise} 가 먼저 {@link #drop} 한다. 한 번에 한 볼리뿐이라
 *       올 일이 없지만, 남은 판 위에 새 판을 얹으면 지우는 쪽이 옛 것을 영영 놓친다</li>
 *   <li><b>비가 끝날 때 · 다른 판의 찌꺼기를 버릴 때</b> — {@code TrialEndRain.finish}</li>
 *   <li><b>월드가 바뀌거나 서버가 내려갈 때</b> — {@code TrialEndRain.clearState} →
 *       {@link #dropAll()}. ⚠ 이 길은 {@code SERVER_STOPPED} 에서도 불려 <b>월드를 만질 수
 *       없다.</b> 그래서 {@link #LIVE} 가 <b>개체를 들고 있는다</b> — 들고 있으면
 *       {@code discard()} 한 줄로 끝나고, {@code UUID} 만 적어 두었다면 레벨이 닫힌 그 자리에서
 *       막힌다</li>
 * </ul>
 */
public final class TrialEndRainPanel {

	/**
	 * 90도를 몇 조각으로 나눠 띠를 자를지. <b>이 값 하나가 판 수와 덮는 몫을 함께 정한다.</b>
	 *
	 * <p>띠 경계가 {@code r·sin(k·90°/이 값)} 이고 반폭이 {@code r·cos(...)} 이라, 판 수는
	 * {@link #slabCount()} = {@code 1 + 2×(이 값 − 2)} 이고 경계가 모자라는 폭은
	 * {@code r(1 − cos(90°/이 값))} 이다. 4 면 판 다섯 장에 0.19칸이고(반경 2.5), 3 이면 세 장에
	 * 0.34칸, 5 면 일곱 장에 0.12칸이다.
	 *
	 * <p><b>4 를 고른 까닭</b>은 개체 수다. 한 볼리에 90곳이 서므로 판 수는 곧 <b>90배</b>로
	 * 돌아온다 — 다섯 장이면 450개이고({@link #PANEL_BUDGET} 안이다) 일곱 장이면 630개다.
	 * 0.12칸과 0.19칸의 차이는 사람 몸통 폭(0.6칸)으로 보면 둘 다 「없다」인데 개체 180개는
	 * 그렇지 않다.
	 */
	static final int QUADRANT_STEPS = 4;

	/**
	 * ⚠ 띠를 안쪽으로 당기는 <b>한 비트</b>(칸).
	 *
	 * <p>각도로 나누면 띠의 먼 귀퉁이가 원 위에 <b>정확히</b> 놓인다
	 * ({@code (r cos θ)² + (r sin θ)² = r²}). 그러면 {@link TrialRisks#insideMark} 가 그 점을
	 * 안이라고 할지가 <b>부동소수 마지막 비트</b>에 달리고, 「면은 언제나 원 안」을 시험으로 물을
	 * 수 없다.
	 *
	 * <p>1밀리칸이라 눈에 보이지 않고, 면은 이미 {@link #edgeGap} 만큼 모자라므로 잃는 것이 없다.
	 */
	private static final double INSET = 1.0E-3;

	/**
	 * 판의 두께(칸). 0 이면 뒷면이 보이거나 사라지는 판이 생긴다.
	 *
	 * <p>{@code DragonLastStandConePanel} 과 같은 값이고 근거도 같다 — 바닥 표식을 띄우는
	 * 높이({@link #GROUND_OFFSET})의 3분의 1이라 면 위에 선 사람의 발목에 걸리지 않는다.
	 */
	private static final double THICKNESS = 0.05;

	/**
	 * 지면에서 띄우는 높이.
	 *
	 * <p>{@code TrialWarning} · {@code TrialEndRain} 의 바닥 표식과 <b>같은 값</b>이다. 0 이면
	 * 블록 면에 파묻혀 안 보인다.
	 */
	static final double GROUND_OFFSET = 0.15;

	/**
	 * ⚠⚠ 자리마다 판을 조금씩 다른 높이에 두는 <b>층 수</b>.
	 *
	 * <h2>이 카드만 필요한 장치다 — 표식끼리 겹치기 때문이다</h2>
	 *
	 * <p>이 카드는 겹침 금지 목록 밖이라({@code TrialEndRain} 클래스 설명) 원끼리 <b>자주</b>
	 * 겹친다 — 반경 40 아레나에 반경 2.5 짜리 90곳을 뿌리면 겹치는 쌍이 볼리마다 <b>60쌍 남짓</b>
	 * 이다. 두 자리의 지표가 같으면 두 판이 <b>같은 높이의 같은 평면</b>에 놓이고, 그렇게 겹친
	 * 반투명은 깊이 버퍼가 둘 중 어느 것을 그릴지 정하지 못해 <b>어룽거린다.</b>
	 *
	 * <p>층을 돌려 쓰면 그 평면이 갈린다. 겹친 자리는 <b>반투명이 두 겹으로 짙어져</b> 가장
	 * 위험한 자리가 가장 짙게 보이는 쪽이 되고, 그것이 이 카드에서 바라는 모습이다.
	 *
	 * <p>⚠ <b>완전히 없애지는 못한다.</b> 여덟 층을 돌려 쓰므로 번호가 8 로 나눈 나머지가 같은
	 * 두 자리가 겹치면 그때는 같은 평면이다 — 60쌍 가운데 <b>여덟 쌍쯤</b> 남는다. 층을 늘리면
	 * 그만큼 줄지만 가장 높은 판이 더 떠서({@link #MAX_LAYER_LIFT}) 발밑에서 뜬 것이 보인다.
	 * 여덟은 그 사이에서 고른 값이다.
	 */
	static final int LAYER_COUNT = 8;

	/**
	 * 층 사이 높이(칸). 1센티칸이다.
	 *
	 * <p>눈으로는 보이지 않지만 깊이 버퍼에는 보여야 한다. 50칸 거리에서 깊이 한 칸이
	 * <b>3밀리칸쯤</b>을 가르므로({@code z² / (near × 2²⁴)}, {@code near} 0.05) 1센티칸이면
	 * 아레나 대부분에서 갈린다. 더 줄이면 멀리 있는 판에서 다시 어룽거리고, 더 늘리면 가장 높은
	 * 판이 발목에 걸린다.
	 */
	static final double LAYER_STEP = 0.01;

	/**
	 * 가장 높은 층이 떠 있는 높이(칸). <b>{@link #GROUND_OFFSET} 보다 작아야 한다.</b>
	 *
	 * <p>층을 얹는 것은 바닥 표식을 띄우는 높이에 <b>더하는</b> 것이라, 이 값이 그 높이를 넘으면
	 * 가장 높은 판이 띄운 높이의 두 배 넘게 떠서 <b>바닥에 붙은 것으로 안 보인다.</b>
	 * {@code TrialEndRainPanelTest} 가 그 부등식을 지킨다.
	 */
	static final double MAX_LAYER_LIFT = (LAYER_COUNT - 1) * LAYER_STEP;

	/**
	 * 보이는 거리(64칸 곱). 1.0 이면 64칸에서 끊긴다.
	 *
	 * <p>26.3 {@code Display.shouldRenderAtSqrDistance} 가
	 * {@code 거리² < (viewRange × 64 × 시야배율)²} 다. 아레나 반경이 40 이라 팀원 둘이 80칸
	 * 떨어질 수 있고, <b>「저쪽 바닥이 보라로 덮였다」는 밟는 사람이 아니라 나머지 셋이 봐야 하는
	 * 정보</b>라({@code TrialWarning} 의 「거리 제한을 끄고 보낸다」와 같은 근거) 기본값으로는
	 * 모자란다. 2.0 이면 128칸이다.
	 */
	private static final float VIEW_RANGE = 2.0F;

	/**
	 * ⚠ 심지에 얹는 여유 틱. 심지는 <b>예고 길이 + 이 값</b>이다({@link #fuseTicks}).
	 *
	 * <p>심지가 하는 일은 <b>지우는 줄을 아무도 못 지난 경우의 바닥</b>이다. 정상 경로는 볼리가
	 * 터지는 틱이고({@code TrialEndRain.land}) 그때 정확히 지워지므로 이 여유는 쓰이지 않는다.
	 *
	 * <p>⚠ <b>예고보다 짧으면 안 된다.</b> 면이 착탄 전에 사라지면 사람은 비킬 자리를 잃고,
	 * 그것은 「예고를 보여 주고 무른 것」이 된다 — 이 전투가 가장 하지 않기로 한 일이다. 거꾸로
	 * 너무 길면 터진 자리가 위험해 보이는 시간이 그만큼 길어진다. 20틱(1초)은 그 사이에서 <b>한
	 * 틱도 모자라지 않게</b> 잡은 자리다.
	 */
	static final int FUSE_MARGIN_TICKS = 20;

	/**
	 * 면의 색. <b>블록이 스스로 반투명 보라</b>라 색·투명도 API 가 필요 없다.
	 *
	 * <p>사람이 「보라색 바닥으로 투명바닥으로」라고 한 그 보라다. {@code MAGENTA} 가 아닌 것은
	 * 사람이 전에 <b>자홍</b>({@code 0xC800C8})을 「가시성이 너무 안 좋다」고 직접 물렸기
	 * 때문이다 — 돌아가지 말 것.
	 */
	static final DyeColor GLASS_COLOR = DyeColor.PURPLE;

	/**
	 * ⚠ 한 볼리에 세울 수 있는 판 수의 <b>상한</b>. 넘는지는 {@code TrialEndRainTest} 가 카드
	 * 값에서 직접 세어 본다.
	 *
	 * <h2>512 의 근거 — 걷어낸 고리의 패킷과 견준 값이다</h2>
	 *
	 * <p>고리는 <b>예고 30틱 내내</b> 매 틱 점 360장을 보냈다(지점 90곳 × 고리 3점 + 구체 1점) —
	 * 한 볼리에 <b>10,800장</b>이다. 판은 세울 때 개체마다 두 장(개체 추가 + 자료)이고 지울 때는
	 * 목록 한 장이라, 450개면 <b>901장</b>으로 끝난다. 곧 <b>열 배 이상 싸다.</b>
	 *
	 * <p>512 는 그 몫이 <b>옛 고리가 한 틱에 쓰던 360장의 세 배</b>를 넘지 않게 두는 자리다.
	 * 지점 수나 {@link #QUADRANT_STEPS} 를 올리는 사람은 여기서 먼저 걸린다.
	 *
	 * <p>⚠ <b>실행 중에는 자르지 않는다.</b> 넘칠 때 뒤를 버리면 <b>표식 없는 자리가 생기고</b>
	 * 그 자리는 예고 없이 터진다 — 숫자를 지키는 자리는 시험이지 전투 한가운데가 아니다.
	 */
	static final int PANEL_BUDGET = 512;

	/**
	 * ⚠ <b>지금 깔려 있는 판들.</b> 열쇠마다(= 비마다) 한 묶음이고 <b>개체를 들고</b> 있는다.
	 *
	 * <p>{@code UUID} 만 적어 두고 나중에 월드에서 찾는 방식으로는 안 된다 —
	 * {@code TrialEndRain.clearState()} 는 {@code TrialRisks.clearState()} 를 거쳐
	 * {@code SERVER_STOPPED} 에서도 불리는데 그때는 레벨이 닫혀 있어 <b>찾을 수가 없다.</b>
	 * {@code DragonLastStandConePanel} 과 「연결된 수정」이 같은 사정으로 같은 모양을 쓴다.
	 *
	 * <p>열쇠로 가르는 것은 {@code TrialEndRain.RAINS} 와 같은 이유다 — 값이 같은 위험이 카드
	 * 둘에 걸리면 둘이 같은 칸을 쓰게 되고, 그때 한쪽이 남의 판을 지운다.
	 *
	 * <p>정적이라 월드보다 오래 산다. {@link #dropAll()} 로 반드시 비운다.
	 */
	private static final Map<String, List<Panel>> LIVE = new HashMap<>();

	private TrialEndRainPanel() {
	}

	// ------------------------------------------------------------------ 깔고 지우기

	/**
	 * 이번 볼리의 자리마다 보라 면을 깐다. <b>볼리를 여는 틱에 한 번</b>이다.
	 *
	 * <p>높이는 {@code spot.y}(그 자리의 지표)에 {@link #GROUND_OFFSET} 을 얹은 자리다 —
	 * {@code TrialEndRain.rollSpot} 이 자리를 굴릴 때 이미 지표를 물어 담았으므로 여기서 다시
	 * 묻지 않는다.
	 *
	 * @param key       이 비의 열쇠. 분배기가 준 것을 그대로 받는다
	 * @param spots     고리 중심들. 좌표의 {@code y} 가 그 자리의 지표여야 한다
	 * @param radius    피해 반경. <b>면이 덮는 반경과 같은 값이어야 한다</b>
	 * @param warnTicks 예고 길이. 심지를 여기서 만든다({@link #fuseTicks})
	 */
	static void raise(@Nullable ServerLevel end, @Nullable String key, @Nullable List<Vec3> spots,
			double radius, int warnTicks) {
		if (key == null) {
			return;
		}
		// 남은 것이 있으면 먼저 지운다. 얹으면 옛 것을 영영 놓친다.
		drop(key);
		if (end == null || spots == null || spots.isEmpty() || !(radius > 0.0)) {
			return;
		}
		// 띠는 반경에만 달려 있으므로 자리마다 다시 세지 않는다 — 90곳이면 450번이 될 자리다.
		List<Transformation> shapes = new ArrayList<>(slabCount());
		for (int index = 0; index < slabCount(); index++) {
			Slab slab = slabAt(index, radius);
			if (slab != null) {
				shapes.add(transformOf(slab));
			}
		}
		int fuse = fuseTicks(warnTicks);
		List<Panel> raised = new ArrayList<>(spots.size() * shapes.size());
		for (int spotIndex = 0; spotIndex < spots.size(); spotIndex++) {
			Vec3 spot = spots.get(spotIndex);
			// ⚠ 자리마다 층을 돌려 쓴다. 겹친 두 자리가 같은 평면에 놓이면 반투명이 어룽거린다.
			Vec3 at = new Vec3(spot.x, spot.y + GROUND_OFFSET + layerLift(spotIndex), spot.z);
			for (Transformation shape : shapes) {
				Panel panel = new Panel(end, fuse);
				panel.snapTo(at);
				panel.dress(end, shape);
				end.addFreshEntity(panel);
				raised.add(panel);
			}
		}
		if (!raised.isEmpty()) {
			LIVE.put(key, raised);
		}
	}

	/**
	 * 이 비의 판을 지운다. <b>월드를 만지지 않는다</b> — 들고 있는 개체에게 직접 말한다.
	 *
	 * <p>{@code discard()} 는 {@code Entity.remove(DISCARDED)} 라 개체 자신과 그것을 들고 있는
	 * 목록만 건드린다. 그래서 {@code SERVER_STOPPED} 에서도 안전하다.
	 */
	static void drop(@Nullable String key) {
		discard(LIVE.remove(key));
	}

	/** 월드가 바뀌거나 서버가 내려갈 때. 들고 있는 것을 전부 놓는다. */
	static void dropAll() {
		for (List<Panel> panels : LIVE.values()) {
			discard(panels);
		}
		LIVE.clear();
	}

	private static void discard(@Nullable List<Panel> panels) {
		if (panels == null) {
			return;
		}
		for (Panel panel : panels) {
			if (!panel.isRemoved()) {
				panel.discard();
			}
		}
	}

	/** 시험이 들여다보는 곳. 지금 판이 몇 장 깔려 있는가. */
	static int liveCount() {
		int count = 0;
		for (List<Panel> panels : LIVE.values()) {
			count += panels.size();
		}
		return count;
	}

	/** 심지(틱). <b>예고보다 반드시 길다</b> — 근거는 {@link #FUSE_MARGIN_TICKS} 에 있다. */
	static int fuseTicks(int warnTicks) {
		return Math.max(1, Math.max(0, warnTicks) + FUSE_MARGIN_TICKS);
	}

	/**
	 * 이 자리의 판을 얼마나 더 띄울지(칸). <b>층을 돌려 쓴다</b> — 근거는 {@link #LAYER_COUNT} 에
	 * 있다.
	 *
	 * <p>{@code floorMod} 인 이유는 번호가 음수로 들어와도 층이 범위 안이어야 하기 때문이다.
	 * 지금은 목록 번호라 음수가 올 길이 없지만, 여기서 음수가 나오면 판이 <b>바닥 밑으로</b> 가라앉아
	 * 통째로 안 보인다.
	 */
	static double layerLift(int spotIndex) {
		return Math.floorMod(spotIndex, Math.max(1, LAYER_COUNT)) * LAYER_STEP;
	}

	// ------------------------------------------------------------------ 기하학 (월드 없이 도는 계산)

	/**
	 * 띠 하나. 중심을 원점으로 본 <b>세계 좌표</b>다 — {@code z} 가 띠의 방향, {@code halfWidth} 가
	 * {@code x} 쪽 반폭이다.
	 *
	 * <p>부채꼴 면({@code DragonLastStandConePanel.Strip})과 달리 <b>부호가 있는 좌표</b>를 든다.
	 * 원은 방향이 없어 돌릴 것이 없고, 가운데 띠가 중심을 걸터앉기 때문이다.
	 *
	 * @param near      가까운 쪽 {@code z}
	 * @param far       먼 쪽 {@code z}
	 * @param halfWidth 이 띠의 반폭. <b>띠 안에서 가장 좁은 곳</b>이라 원을 넘지 않는다
	 */
	record Slab(double near, double far, double halfWidth) {

		/** 띠의 깊이(칸). */
		double depth() {
			return far - near;
		}

		/** 띠가 칠하는 넓이(칸²). */
		double area() {
			return 2.0 * halfWidth * depth();
		}
	}

	/**
	 * 판이 몇 장인가. {@code 1 + 2 × (}{@link #QUADRANT_STEPS}{@code − 2)} 다.
	 *
	 * <p>가운데 띠 하나가 중심을 걸터앉고, 그 바깥으로 위아래 한 쌍씩 붙는다. 가장 바깥
	 * 한 쌍은 반폭이 {@code r·cos 90° = 0} 이라 <b>애초에 만들지 않는다</b> — 부채꼴 면이
	 * {@code null} 을 돌려주던 자리를 수로 먼저 줄인 것이다.
	 */
	static int slabCount() {
		return Math.max(1, 1 + 2 * (QUADRANT_STEPS - 2));
	}

	/**
	 * {@code index} 번째 띠. 0 이 가운데이고, 그 뒤로 <b>위·아래가 번갈아</b> 나온다.
	 *
	 * <h2>반폭을 <b>가장 좁은 곳</b>으로 잡는다 — 그것이 「원 밖으로 안 나간다」의 증명이다</h2>
	 *
	 * <p>띠 경계를 {@code z_k = r·sin(k·α)}, 반폭을 {@code w_k = r·cos(k·α)} 로 잡으면
	 * ({@code α = 90° / }{@link #QUADRANT_STEPS}) 띠 {@code [z_k, z_{k+1}]} 의 반폭이
	 * {@code w_{k+1}} 이고, 그 띠의 네 귀퉁이가 이렇게 된다.
	 *
	 * <ul>
	 *   <li>먼 쪽 {@code (±w_{k+1}, z_{k+1})} — {@code cos² + sin² = 1} 이라 <b>원 위에 정확히</b>
	 *       놓인다({@link #INSET} 만큼 안으로 당겨 두었다)</li>
	 *   <li>가까운 쪽 {@code (±w_{k+1}, z_k)} — {@code z_k < z_{k+1}} 이라 더 안쪽이다</li>
	 * </ul>
	 *
	 * <p>곧 <b>칠한 면은 원에 내접하는 계단 다각형</b>이고, 띠마다 반폭이 다르므로 서로 겹치지도
	 * 않는다.
	 *
	 * @return 반폭이 0 이거나 반경이 없으면 {@code null}
	 */
	static @Nullable Slab slabAt(int index, double radius) {
		if (index < 0 || index >= slabCount() || !(radius > 0.0)) {
			return null;
		}
		if (index == 0) {
			double far = edgeAt(radius, 1);
			double halfWidth = widthAt(radius, 1) - INSET;
			return halfWidth > 1.0E-6 && far > 0.0 ? new Slab(-far, far, halfWidth) : null;
		}
		// 1·2 가 첫 바깥 한 쌍, 3·4 가 그다음 한 쌍이다. 홀수가 위(+z)다.
		int step = (index + 1) / 2;
		double near = edgeAt(radius, step);
		double far = edgeAt(radius, step + 1);
		double halfWidth = widthAt(radius, step + 1) - INSET;
		if (!(halfWidth > 1.0E-6) || !(far > near)) {
			return null;
		}
		return index % 2 == 1 ? new Slab(near, far, halfWidth) : new Slab(-far, -near, halfWidth);
	}

	/** {@code step} 번째 띠 경계의 {@code z}. {@code r·sin(step·α)} 다. */
	static double edgeAt(double radius, int step) {
		return radius * Math.sin(stepAngle(step));
	}

	/** {@code step} 번째 경계에서의 반폭. {@code r·cos(step·α)} 다. */
	static double widthAt(double radius, int step) {
		return radius * Math.cos(stepAngle(step));
	}

	private static double stepAngle(int step) {
		return (Math.PI / 2.0) * step / Math.max(1, QUADRANT_STEPS);
	}

	/**
	 * 칠한 면이 원 넓이에서 차지하는 몫. <b>1.0 이 될 수 없다</b> — 계단만큼 모자란다.
	 *
	 * <p>시험이 이 값을 붙든다. {@link #QUADRANT_STEPS} 를 내리면 여기가 먼저 떨어지므로 「면이
	 * 원을 거의 덮는가」를 숫자로 물을 수 있다.
	 */
	static double coverage(double radius) {
		if (!(radius > 0.0)) {
			return 0.0;
		}
		double painted = 0.0;
		for (int index = 0; index < slabCount(); index++) {
			Slab slab = slabAt(index, radius);
			if (slab != null) {
				painted += slab.area();
			}
		}
		return painted / (Math.PI * radius * radius);
	}

	/**
	 * 칠한 경계가 <b>진짜 경계보다 얼마나 안쪽인가</b>(칸). 「보이는 자리와 터지는 자리의 어긋남」을
	 * 숫자로 묻는 값이다.
	 *
	 * <p>계단 다각형이라 가장 모자라는 곳은 <b>축 방향 둘</b>이다 — 옆으로는 가운데 띠의 반폭까지,
	 * 앞뒤로는 가장 먼 띠의 끝까지다. 둘 다 {@code r(1 − cos α)} 이고 반경 2.5 · 22.5도에서
	 * <b>0.19칸</b>이다. 계단 귀퉁이 쪽은 원에 닿아 있으므로 0 이다.
	 */
	static double edgeGap(double radius) {
		Slab middle = slabAt(0, radius);
		if (middle == null) {
			return Math.max(0.0, radius);
		}
		double deepest = 0.0;
		for (int index = 0; index < slabCount(); index++) {
			Slab slab = slabAt(index, radius);
			if (slab != null) {
				deepest = Math.max(deepest, Math.max(Math.abs(slab.near()), Math.abs(slab.far())));
			}
		}
		return Math.max(radius - middle.halfWidth(), radius - deepest);
	}

	/**
	 * 띠 하나를 그리는 변환.
	 *
	 * <h2>⚠ 상자는 {@code [0,1]³} 에서 시작한다 — 그래서 옆으로 반폭만큼 밀어야 한다</h2>
	 *
	 * <p>26.3 {@code Transformation} 은 {@code 이동 · 왼쪽회전 · 크기 · 오른쪽회전} 순서로 곱한다
	 * ({@code compose}). 블록 모델의 {@code [0,1]³} 이 <b>크기 → 회전 → 이동</b>을 지나므로 상자는
	 * 언제나 <b>원점에서 양의 방향으로만</b> 자란다. 띠는 {@code x} 로 {@code [−w, +w]} 를 덮어야
	 * 하니 이동에 {@code −w} 를 얹고, {@code z} 는 {@code near} 에서 시작한다.
	 *
	 * <p><b>회전이 없다.</b> 원은 방향이 없어 돌릴 것이 없다 — 부채꼴 면이 「180도 뒤집히면 등
	 * 뒤를 칠한다」로 가장 조심하던 자리가 이 카드에는 아예 없다. 그래도 귀퉁이가 제자리에 놓이는지는
	 * {@code TrialEndRainPanelTest} 가 행렬을 직접 굴려 재어 본다 — 두께를 {@code y} 가 아닌 데에
	 * 넣는 종류의 실수는 눈으로 볼 수 없는 자리다({@code runClient} 가 이 환경에서 죽는다).
	 */
	static Transformation transformOf(Slab slab) {
		return new Transformation(
				new Vector3f((float) -slab.halfWidth(), 0.0F, (float) slab.near()),
				new Quaternionf(),
				new Vector3f((float) (slab.halfWidth() * 2.0), (float) THICKNESS,
						(float) slab.depth()),
				new Quaternionf());
	}

	/**
	 * 판이 쓰는 블록. 26.3 에서 색유리는 {@code ColorCollection} 한 칸이다.
	 *
	 * <p>⚠ <b>{@code Blocks.PURPLE_STAINED_GLASS} 가 아니다</b> — {@code ColorCollection<Block>}
	 * 으로 묶여 있어 {@code pick(DyeColor)} 로 꺼낸다. 판이 올라 이 모양이 바뀌면 컴파일이 먼저
	 * 깨진다({@code DragonLastStandConePanel.glass()} 에 같은 설명이 있다).
	 */
	static BlockState glass() {
		return Blocks.STAINED_GLASS.pick(GLASS_COLOR).defaultBlockState();
	}

	/**
	 * 판 하나를 세우는 NBT. <b>태그 이름은 바닐라가 읽는 것과 같아야 한다</b> — 26.3
	 * {@code Display.readAdditionalSaveData} 와 {@code BlockDisplay.readAdditionalSaveData} 의
	 * 바이트코드에서 그대로 옮겨 적었다({@code DragonLastStandConePanel.shapeTag} 와 같은 다섯
	 * 칸이다).
	 *
	 * <p>개체 없이 만들 수 있게 떼어 두었다. 시험이 이것을 직접 만들어 <b>네 코덱이 실제로
	 * 써지는지</b> 본다 — 하나라도 안 써지면 바닐라가 <b>조용히 기본값</b>(공기 블록 · 단위 변환)을
	 * 쓰고, 그러면 면이 <b>아예 안 보이는데 로그에는 한 줄도 안 남는다.</b>
	 */
	static CompoundTag shapeTag(Transformation transformation) {
		CompoundTag tag = new CompoundTag();
		tag.store("transformation", Transformation.EXTENDED_CODEC, transformation);
		tag.store("block_state", BlockState.CODEC, glass());
		// 판이 스스로 밝다. 엔드는 어두운 데가 있고 「가시성」이 이 면을 만든 이유다.
		tag.store("brightness", Brightness.CODEC, Brightness.FULL_BRIGHT);
		// FIXED 는 기본값이지만 적어 둔다 — 빌보드가 바뀌면 판이 사람을 따라 돌아 버린다.
		tag.store("billboard", Display.BillboardConstraints.CODEC,
				Display.BillboardConstraints.FIXED);
		tag.putFloat("view_range", VIEW_RANGE);
		return tag;
	}

	// ------------------------------------------------------------------ 개체

	/**
	 * 판 한 장.
	 *
	 * <h2>왜 하위 클래스인가 — 얻는 것이 둘이다</h2>
	 *
	 * <ol>
	 *   <li><b>저장되지 않는다</b>({@link #shouldBeSaved()})</li>
	 *   <li><b>스스로 타 없어진다</b>({@link #tick()})</li>
	 * </ol>
	 *
	 * <p>개체 종류는 <b>바닐라 {@code EntityTypes.BLOCK_DISPLAY} 그대로</b>다. 종류가 바닐라라
	 * 클라이언트에게 나가는 것도 바닐라 {@code block_display} 이고, 모드를 안 깐 사람이 우리
	 * 클래스를 알 필요가 없다.
	 *
	 * <h2>⚠ 26.3 의 디스플레이 설정자는 <b>전부 {@code private}</b> 이다</h2>
	 *
	 * <p>{@code setTransformation}·{@code setBlockState}·{@code setViewRange}·
	 * {@code setBrightnessOverride} 가 하나도 열려 있지 않다. 믹스인도 리플렉션도 쓰지 않고
	 * 들어가는 길은 <b>{@code readAdditionalSaveData(ValueInput)}</b> 하나다 — 그쪽은
	 * {@code protected} 라 하위 클래스가 부를 수 있고, 바닐라가 저장 파일에서 읽는 그 길이라
	 * 모든 칸을 다 세울 수 있다.
	 *
	 * <p>리플렉션을 쓰지 않은 까닭은 이 저장소가 이미 아는 것이다 — 필드 이름이 중간 이름으로
	 * remap 되므로 <b>이름으로 찾는 코드는 배포된 jar 에서만 조용히 실패한다.</b>
	 */
	private static final class Panel extends Display.BlockDisplay {

		/** 남은 틱. 0 이 되면 스스로 사라진다. */
		private int fuse;

		private Panel(Level level, int fuse) {
			super(EntityTypes.BLOCK_DISPLAY, level);
			this.fuse = fuse;
		}

		/**
		 * 모양을 세운다. 바닐라가 저장 파일을 읽는 길을 그대로 쓴다.
		 *
		 * <p>{@code ProblemReporter.DISCARDING} 인 것은 우리가 만든 태그라 <b>잘못된 칸이 있을 수
		 * 없고</b>, 있다면 그것은 시험이 잡을 일이지 사람의 로그를 어지럽힐 일이 아니다.
		 */
		private void dress(ServerLevel end, Transformation transformation) {
			ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING,
					end.registryAccess(), shapeTag(transformation));
			readAdditionalSaveData(input);
		}

		/**
		 * ⚠ <b>저장하지 않는다.</b> 26.3
		 * {@code PersistentEntitySectionManager.storeChunkSections} 가 이 물음으로 거른다.
		 *
		 * <p>이 한 줄이 「서버가 강제 종료되어도 다음 판에 보라 판이 남지 않는다」의 전부다 —
		 * 그 길에는 {@code SERVER_STOPPING} 도 오지 않으므로 지우는 코드로는 막을 수 없다.
		 */
		@Override
		public boolean shouldBeSaved() {
			return false;
		}

		/**
		 * 심지를 한 칸 태운다. 0 이 되면 스스로 사라진다.
		 *
		 * <p>{@code TrialEndRain} 이 지우는 것이 정상 경로이고 이것은 <b>그것이 전부 실패했을
		 * 때의 바닥</b>이다. 개체 제 틱에서 세므로 우리 시계가 멈춰도 돈다 — <b>받은
		 * {@code now} 도 {@code getGameTime()} 도 쓰지 않는다.</b>
		 */
		@Override
		public void tick() {
			super.tick();
			if (--fuse <= 0) {
				discard();
			}
		}
	}
}
