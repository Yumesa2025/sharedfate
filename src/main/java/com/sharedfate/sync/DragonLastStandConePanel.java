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
import java.util.List;

/**
 * 부채꼴 브레스의 <b>빨간 투명 면</b>. 파티클이 아니라 <b>디스플레이 개체</b>로 바닥을 덮는다.
 *
 * <h2>왜 파티클이 아닌가</h2>
 *
 * <p>사람이 <b>「모든바닥이 위험지대라고 알수잇게 빨간색이펙트가 더잇으면」</b>이라고 했다.
 * 부채꼴 바닥(90도 · 20칸 = 314칸²)을 점으로 칠하면 0.5칸 간격으로 1200점이 넘고, 한 틱 예산이
 * {@code TrialLandingShock.MAX_POINTS_PER_TICK}(440)이라 <b>점으로는 불가능하다.</b> 사람이
 * <b>「투명면을 덮어서 빨간 투명면 그런걸로 안 되나?」</b>라고 물었고, 26.3 의 디스플레이 개체가
 * 정확히 그 길이다 — <b>점을 한 개도 쓰지 않는다.</b>
 *
 * <p>통신 규약도 올라가지 않는다. {@code block_display} 는 바닐라 개체라 <b>모드를 안 깐
 * 사람에게도 그대로 보인다</b>({@link TrialWarning} 의 「통신 규약을 올리지 않는다」와 같다).
 *
 * <h2>26.3 에서 확인한 것 — 고른 것은 {@code block_display} 다</h2>
 *
 * <p>바이트코드를 풀어 읽고 골랐다. 셋 가운데 이것뿐인 까닭이 있다.
 *
 * <table border="1">
 *   <caption>세 종류를 재어 본 결과</caption>
 *   <tr><th>종류</th><th>반투명 빨강을 낼 수 있는가</th></tr>
 *   <tr><td><b>{@code block_display}</b></td>
 *       <td>그렇다 ← 고른 것. <b>블록이 스스로 반투명 빨강</b>이다(붉은 색유리). 색·투명도 API 가
 *           필요 없다</td></tr>
 *   <tr><td>{@code text_display}</td>
 *       <td>배경색이 ARGB 라 되기는 한다. 그런데 <b>크기가 글자에서 나오고</b> 회전·크기를 판
 *           모양으로 잡을 손잡이가 글자 수뿐이다 — 20칸짜리 판을 글자로 만들 수 없다</td></tr>
 *   <tr><td>{@code item_display}</td>
 *       <td>아니다. 아이템 모델을 그리는 것이라 <b>납작한 빨강 한 장</b>을 만들 방법이 없다</td></tr>
 * </table>
 *
 * <p>색은 {@code Blocks.STAINED_GLASS.pick(DyeColor.RED)} 다. ⚠ <b>26.3 에서 색유리는
 * {@code Blocks.RED_STAINED_GLASS} 가 아니다</b> — {@code ColorCollection<Block>} 한 칸으로
 * 묶여 있어 {@code pick(DyeColor)} 로 꺼낸다. 판이 올라 이 모양이 바뀌면 컴파일이 먼저 깨진다.
 *
 * <p>규약의 빨강({@link TrialWarning.Colors#DEADLY})을 <b>파티클로 쓰던 그 뜻 그대로</b>다 —
 * 「서 있으면 죽는다」. 색 규약에 다섯째 색을 더하지 않았고, 더할 필요도 없었다: 이 면은 색을
 * 우리가 고르는 것이 아니라 <b>블록이 들고 있는 것</b>이라 규약의 16진수와 값이 같지는 않다.
 * 「빨강은 죽는다」라는 <b>뜻</b>만 같으면 되고, 그것이 규약이 지키려는 것이다.
 *
 * <h2>⚠⚠ 개체는 월드에 저장된다 — 그것을 <b>두 겹</b>으로 막는다</h2>
 *
 * <p>파티클과 달리 개체는 남는다. 남으면 <b>다음 판까지 빨간 판이 떠 있고</b>, 그것은 월드 저장
 * 파일에 들어가므로 「월드와 함께 사라진다」가 성립하지 않는다({@link DragonLastStandZone} 의
 * 월드 보더와 같은 종류의 빚이다).
 *
 * <ol>
 *   <li><b>저장을 아예 안 하게 만들었다</b>({@link Panel#shouldBeSaved()}). 26.3
 *       {@code PersistentEntitySectionManager.storeChunkSections} 가 저장할 개체를
 *       {@code EntityAccess::shouldBeSaved} 로 <b>거른다</b>(바이트코드의 람다 #17 이 그 메서드
 *       참조다). 거짓을 돌려주면 청크가 저장되든 서버가 강제 종료되든 <b>파일에 한 줄도 안
 *       들어간다.</b> 이것이 있어야 「서버 강제 종료」가 안전하다 — 그 길에는
 *       {@code SERVER_STOPPING} 도 {@code SERVER_STOPPED} 도 오지 않는다</li>
 *   <li><b>스스로 타 없어지는 심지</b>({@link #FUSE_TICKS}). 개체가 제 {@code tick()} 에서 남은
 *       틱을 세고 0 이 되면 {@code discard()} 한다. 누가 지우는 줄을 빠뜨려도
 *       <b>{@link #FUSE_TICKS} 뒤에는 반드시 사라진다.</b> 「연결된 수정」의 죽은 사람 스위치
 *       ({@code TrialCrystalLink} 의 유효기한)와 같은 장치다</li>
 * </ol>
 *
 * <p>그 위에 <b>제때 지우는 길 넷</b>이 있다. 심지는 그 넷이 전부 실패했을 때의 바닥이다.
 *
 * <ul>
 *   <li><b>브레스가 터지는 틱</b> — {@code DragonLastStandPatterns.fireCone}. 터진 뒤의 바닥은
 *       <b>안전하다</b>. 빨간 면이 한 틱이라도 더 남으면 이미 안전한 자리가 위험해 보이고,
 *       그러면 「방금 지나간 자리로 들어간다」를 배울 수 없다 —
 *       {@code DragonFireBarrage} 가 터진 고리를 그 틱에 지우는 것과 <b>같은 규칙</b>이다</li>
 *   <li><b>{@code DragonLastStandPatterns.clearState()}</b> — 월드가 바뀌거나 서버가 내려갈 때.
 *       ⚠ 이 길은 {@code SERVER_STOPPED} 에서도 불려 <b>월드를 만질 수 없다.</b> 그래서
 *       {@link #LIVE} 가 <b>개체를 들고 있는다</b> — 들고 있으면 {@code discard()} 한 줄로 끝나고,
 *       들고 있지 않으면 {@code end.getEntities(...)} 로 찾아야 해서 그 자리에서 막힌다</li>
 *   <li><b>전투가 끝날 때 · 드래곤이 죽을 때</b> — {@code DragonLastStand.onFightClosed} 가
 *       위의 {@code clearState()} 를 부른다</li>
 *   <li><b>서버가 멈추기 직전</b> — {@code DragonLastStand.onServerStopping}. 월드가 아직 살아
 *       있는 자리다({@code DragonLastStandZone.onServerStopping} 이 같은 자리에 같은 이유로 있다)</li>
 * </ul>
 *
 * <h2>90도 부채꼴은 사각형이 아니다 — 판 열여덟 장으로 <b>안쪽에서</b> 채운다</h2>
 *
 * <p>디스플레이 개체가 그리는 것은 <b>상자 하나</b>다. {@code Transformation} 이 이동·회전·크기와
 * (분해가 SVD 꼴이라) 기울임까지 낼 수 있지만 <b>삼각형은 낼 수 없다.</b> 그래서 부채꼴을
 * 축(중심선)에 <b>수직인 띠</b>로 잘라 띠마다 판 한 장을 놓는다. 띠는 서로 겹치지 않아
 * <b>반투명이 겹쳐 얼룩지지 않는다</b>(축에서 방사로 뻗는 부채살로 나누면 어디서나 겹쳐 두세 겹이 된다).
 *
 * <p>⚠ <b>칠한 면은 언제나 부채꼴 <u>안</u>이다.</b> 띠의 폭을 <b>그 띠 안에서 가장 좁은 곳</b>으로
 * 잡으므로(내접) 밖으로 한 칸도 나가지 않는다 — {@code DragonLastStandConePanelTest} 가 판
 * 네 귀퉁이를 전부 {@code DragonLastStandPatterns.insideCone} 에 넣어 본다. <b>피해를 가르는 그
 * 함수로 재는 것</b>이 요점이다: 면이 「안전한 바닥을 위험하다」고 말하는 일이 구조적으로 없다.
 *
 * <p>대가는 <b>톱니만큼(최대 {@link #STRIP_DEPTH} 칸) 모자란다</b>는 것이다. 부채꼴 넓이의
 * <b>90%</b>가 칠해지고 모자란 몫은 가장자리에 붙는데, 진짜 경계는 <b>여전히 빨간 점선과 흰
 * 벽이 말한다</b>({@code markCone}). 곧 「면은 안을 채우고 선이 경계를 말한다」다.
 *
 * <p><b>흰 벽을 그대로 둔 까닭</b>이 여기서도 같다 — 부채꼴 <b>안에 서 있으면</b> 바닥이 시선과
 * 나란해 면도 선도 거의 안 보인다({@code TrialLandingShock.EDGE_RISE_SPEED} 의 「서서 보는
 * 눈높이에서 바닥 선은 시선과 거의 나란하다」). 면이 생겨도 그 근거는 한 줄도 안 바뀐다.
 *
 * <h2>띠마다 제 바닥 높이를 잰다</h2>
 *
 * <p>판 한 장은 평면이라 지형을 따라갈 수 없다. 그래서 띠마다 가운데의 지표를 <b>한 번</b> 재어
 * 그 높이에 놓고({@code TrialEnderPulse.Ground}), 허공이면 <b>그 띠를 아예 세우지 않는다</b> —
 * 허공 위에 빨간 판이 떠 있으면 「저기가 바닥이다」라고 거짓말을 한다(바닥 점을 찍지 않는 것과
 * 같은 이유다).
 *
 * <h2>왜 개체 회전이 아니라 {@code Transformation} 으로 돌리는가</h2>
 *
 * <p>26.3 {@code DisplayRenderer.calculateOrientation} 이 {@code FIXED} 일 때
 * {@code rotationYXZ(−yRot·π/180, xRot·π/180, 0)} 으로 <b>개체의 각도를 그대로 쓴다</b>(확인했다).
 * 곧 개체를 돌려도 된다. 그런데도 {@code Transformation} 에 회전을 넣은 것은 <b>시험할 수 있어야</b>
 * 하기 때문이다 — 이 환경에서 {@code runClient} 가 죽어 눈으로 볼 수 없으므로
 * ({@code DragonLastStandPatternsTest} 의 경고), 행렬을 직접 굴려 귀퉁이 좌표를 재는 것만이
 * 「180도 뒤집혀 있다」를 잡을 수 있는 길이다. 개체 각도는 0 으로 둔다.
 */
public final class DragonLastStandConePanel {

	/**
	 * 띠 하나의 깊이(칸). <b>새 숫자를 만들지 않았다</b> — 바닥 점 간격의 두 배다.
	 *
	 * <p>톱니의 오차가 이 값을 넘지 않으므로, 1.0 이면 <b>면이 표식 자신의 해상도보다 더 틀리지
	 * 않는다</b>({@code TrialWarning.POINT_GAP} 이 0.5 이고 부채꼴 테두리를 그 간격으로 찍는다).
	 * 절반으로 줄이면 개체가 두 배로 늘고, 두 배로 늘리면 가장자리 톱니가 2칸이 되어 점선과
	 * 눈에 보이게 어긋난다.
	 */
	static final double STRIP_DEPTH = TrialWarning.POINT_GAP * 2.0;

	/**
	 * ⚠ 띠를 안쪽으로 당기는 <b>한 비트</b>(칸).
	 *
	 * <p>내접으로 잡으면 귀퉁이가 부채꼴 경계에 <b>정확히</b> 놓인다(각도로는 45도, 거리로는
	 * 사거리 20). 그러면 {@code DragonLastStandPatterns.insideCone} 이 그 점을 안이라고 할지가
	 * <b>부동소수 마지막 비트</b>에 달리고, 「면은 언제나 부채꼴 안」을 시험으로 물을 수 없다.
	 *
	 * <p>1밀리칸이라 눈에 보이지 않고, 면은 이미 톱니로 최대 1칸 모자라므로 잃는 것이 없다.
	 */
	private static final double INSET = 1.0E-3;

	/**
	 * 판의 두께(칸). 0 이면 뒷면이 보이거나 사라지는 판이 생긴다.
	 *
	 * <p>0.05 는 <b>바닥 점을 띄우는 높이</b>({@link #GROUND_OFFSET})의 3분의 1이다. 더 두꺼우면
	 * 부채꼴 안에 선 사람의 발목에 빨간 판이 걸려 보인다.
	 *
	 * <p>⚠ 패키지 안에 열어 둔 것은 {@link DragonLastStandCrossPanel} 이 <b>같은 판</b>을 깔기
	 * 때문이다(2026-10-04). 두께를 거기 따로 적으면 두 면이 다른 두께로 보인다.
	 */
	static final double THICKNESS = 0.05;

	/**
	 * 지면에서 띄우는 높이. 바닥 표식과 <b>같은 값</b>이라 면과 점선이 같은 층에 있다.
	 *
	 * <p>{@link DragonLastStandCrossPanel} 도 이 값을 읽는다 — {@link #THICKNESS} 와 같은 까닭이다.
	 */
	static final double GROUND_OFFSET = 0.15;

	/**
	 * 보이는 거리(64칸 곱). 1.0 이면 64칸에서 끊긴다.
	 *
	 * <p>26.3 {@code Display.shouldRenderAtSqrDistance} 가
	 * {@code 거리² < (viewRange × 64 × 시야배율)²} 다. 아레나 반경이 40 이라 팀원 둘이 80칸
	 * 떨어질 수 있고, <b>「누가 어디를 밟으면 안 되는가」는 밟는 사람이 아니라 나머지 셋이 봐야
	 * 하는 정보</b>라({@code TrialWarning} 의 「거리 제한을 끄고 보낸다」) 기본값으로는 모자란다.
	 * 2.0 이면 128칸이다.
	 */
	private static final float VIEW_RANGE = 2.0F;

	/**
	 * ⚠ <b>스스로 타 없어지는 심지</b>(틱). 예고 길이 + 불꽃 길이다.
	 *
	 * <p>실제로 필요한 것은 예고 80틱뿐이고({@code fireCone} 이 그 틱에 지운다) 나머지는 여유다.
	 * ⚠ 2026-10-04 에 예고가 100 → 80 으로 줄면서 이 값도 <b>저절로</b> 120 → 100 이 됐다 — 아래
	 * 문장이 말하는 그대로다.
	 * 값을 {@code DragonLastStandPatterns} 에서 직접 더하므로 <b>브레스를 길게 고치는 사람이
	 * 여기를 따로 고칠 일이 없다.</b>
	 *
	 * <p>이 심지가 하는 일은 <b>지우는 줄을 아무도 못 지난 경우의 바닥</b>이다. 개체가 제 틱에서
	 * 세므로 우리 쪽 시계가 멈춰도(세션이 사라지고 {@code tick} 이 안 불려도) 그대로 돈다.
	 */
	static final int FUSE_TICKS = DragonLastStandPatterns.CONE_WARN_TICKS
			+ DragonLastStandPatterns.CONE_AFTERGLOW_TICKS;

	/**
	 * ⚠ <b>지금 서 있는 판들.</b> 개체를 <b>들고</b> 있는다.
	 *
	 * <p>{@code UUID} 만 적어 두고 나중에 월드에서 찾는 방식으로는 안 된다 —
	 * {@code DragonLastStandPatterns.clearState()} 가 {@code SERVER_STOPPED} 에서도 불리는데
	 * 그때는 레벨이 닫혀 있어 <b>찾을 수가 없다.</b> 「연결된 수정」이 크리스탈을 들고 있는 것과
	 * 같은 사정이다.
	 *
	 * <p>정적이라 월드보다 오래 산다. {@link #drop()} 으로 반드시 비운다.
	 */
	private static final List<Panel> LIVE = new ArrayList<>();

	private DragonLastStandConePanel() {
	}

	// ------------------------------------------------------------------ 세우고 지우기

	/**
	 * 부채꼴 바닥에 빨간 면을 세운다. <b>예고 첫 틱에 한 번</b>이다.
	 *
	 * <p>이미 서 있는 것이 있으면 먼저 지운다. 브레스는 한 번에 하나만 도므로
	 * ({@code DragonLastStand.advance}) 올 일이 없지만, 남은 판 위에 새 판을 얹으면 지우는 쪽이
	 * 옛 것을 영영 놓친다.
	 *
	 * @param apex    부채꼴의 꼭대기. 드래곤을 못박아 둔 자리다
	 * @param coneYaw 고정해 둔 방향(도). {@code (sin, −cos)} 이 앞이다
	 */
	static void raise(ServerLevel end, Vec3 apex, float coneYaw) {
		drop();
		TrialEnderPulse.Ground ground = new TrialEnderPulse.Ground();
		for (int index = 0; index < stripCount(); index++) {
			Strip strip = stripAt(index);
			if (strip == null) {
				continue;
			}
			// 띠 가운데의 지표를 한 번 잰다. 허공이면 이 띠는 세우지 않는다.
			Vec3 middle = world(0.0, strip.middle(), coneYaw);
			int surface = ground.surfaceAt(end, apex.x + middle.x, apex.z + middle.z);
			if (surface == TrialEnderPulse.NO_GROUND) {
				continue;
			}
			Panel panel = new Panel(end, FUSE_TICKS);
			panel.snapTo(new Vec3(apex.x, surface + GROUND_OFFSET, apex.z));
			panel.dress(end, transformOf(strip, coneYaw));
			end.addFreshEntity(panel);
			LIVE.add(panel);
		}
	}

	/**
	 * 서 있는 판을 모두 지운다. <b>월드를 만지지 않는다</b> — 들고 있는 개체에게 직접 말한다.
	 *
	 * <p>{@code discard()} 는 {@code Entity.remove(DISCARDED)} 라 개체 자신과 그것을 들고 있는
	 * 목록만 건드린다. 그래서 {@code SERVER_STOPPED} 에서도 안전하다.
	 */
	static void drop() {
		for (Panel panel : LIVE) {
			if (!panel.isRemoved()) {
				panel.discard();
			}
		}
		LIVE.clear();
	}

	/** 시험이 들여다보는 곳. 지금 판이 서 있는가. */
	static int liveCount() {
		return LIVE.size();
	}

	// ------------------------------------------------------------------ 기하학 (월드 없이 도는 계산)

	/**
	 * 띠 하나. <b>부채꼴 좌표계</b>다 — {@code along} 이 중심선 방향, {@code halfWidth} 가 옆이다.
	 *
	 * @param near      꼭대기에서 잰 가까운 쪽 거리(칸)
	 * @param far       먼 쪽 거리(칸)
	 * @param halfWidth 이 띠의 반폭(칸). <b>띠 안에서 가장 좁은 곳</b>이라 부채꼴을 넘지 않는다
	 */
	record Strip(double near, double far, double halfWidth) {

		/** 가운데 거리(칸). 바닥 높이를 재는 자리다. */
		double middle() {
			return (near + far) * 0.5;
		}
	}

	/** 띠가 몇 장인가. 폭이 0 인 띠도 자리를 차지하므로 {@link #stripAt} 이 {@code null} 을 돌려준다. */
	static int stripCount() {
		return (int) Math.ceil(DragonLastStandPatterns.CONE_RANGE / STRIP_DEPTH);
	}

	/**
	 * {@code index} 번째 띠. 폭이 0 이면 {@code null} 이다(꼭대기 한 칸과 사거리 끝 한 칸).
	 *
	 * <h2>반폭을 <b>가장 좁은 곳</b>으로 잡는다 — 그것이 「밖으로 안 나간다」의 증명이다</h2>
	 *
	 * <p>반각 45도짜리 부채꼴에서 거리 {@code d} 의 반폭은 <b>{@code min(d, √(R² − d²))}</b> 다
	 * (앞의 것이 옆으로 벌어지는 한계, 뒤의 것이 사거리 원). 이 값은 가까운 쪽에서는 커지고 먼
	 * 쪽에서는 작아지므로, 띠 안의 최솟값은 <b>두 끝에서만</b> 나온다 —
	 * {@code min(near, √(R² − far²))} 이다.
	 *
	 * <p>이렇게 잡으면 네 귀퉁이가 전부 부채꼴 안이다. 귀퉁이는
	 * {@code (±halfWidth, near)}·{@code (±halfWidth, far)} 인데
	 *
	 * <ul>
	 *   <li>각도 — {@code halfWidth ≤ near ≤ 그 점의 거리} 라 어느 귀퉁이도 45도를 넘지 않는다</li>
	 *   <li>사거리 — {@code halfWidth ≤ √(R² − far²)} 라 {@code far² + halfWidth² ≤ R²} 이고,
	 *       가까운 쪽은 그보다 짧다</li>
	 * </ul>
	 */
	static @Nullable Strip stripAt(int index) {
		double range = DragonLastStandPatterns.CONE_RANGE;
		double near = index * STRIP_DEPTH;
		double far = Math.min(range, near + STRIP_DEPTH);
		if (!(near >= 0.0) || near >= range) {
			return null;
		}
		double byAngle = near * Math.tan(Math.toRadians(DragonLastStandPatterns.CONE_DEGREES / 2.0));
		double byRange = Math.sqrt(Math.max(0.0, range * range - far * far));
		// 한 비트 당긴다. 귀퉁이가 경계에 정확히 놓이면 안팎이 부동소수에 달린다 — INSET 을 볼 것.
		double halfWidth = Math.min(byAngle, byRange) - INSET;
		if (!(halfWidth > 1.0E-6)) {
			return null;
		}
		return new Strip(near, far, halfWidth);
	}

	/**
	 * 칠한 면이 부채꼴 넓이에서 차지하는 몫. <b>1.0 이 될 수 없다</b> — 톱니로 모자란다.
	 *
	 * <p>시험이 이 값을 붙든다. {@link #STRIP_DEPTH} 를 늘리면 여기가 먼저 떨어지므로 「면이
	 * 부채꼴을 거의 덮는가」를 숫자로 물을 수 있다.
	 */
	static double coverage() {
		double painted = 0.0;
		for (int index = 0; index < stripCount(); index++) {
			Strip strip = stripAt(index);
			if (strip != null) {
				painted += 2.0 * strip.halfWidth() * (strip.far() - strip.near());
			}
		}
		double range = DragonLastStandPatterns.CONE_RANGE;
		double sector = Math.toRadians(DragonLastStandPatterns.CONE_DEGREES) * range * range / 2.0;
		return painted / sector;
	}

	/**
	 * 부채꼴 좌표를 <b>꼭대기에서 잰 세계 좌표</b>로 옮긴다.
	 *
	 * <p>중심선이 {@code f = (sin yaw, −cos yaw)} 이고 옆이 {@code p = (cos yaw, sin yaw)} 다 —
	 * 앞 방향의 규약은 {@code DragonLastStandPatterns.insideCone} 과 <b>같은 것</b>이다.
	 *
	 * @param lateral 옆으로 잰 거리. {@code p} 쪽이 양수다
	 * @param along   중심선으로 잰 거리
	 */
	static Vec3 world(double lateral, double along, float yaw) {
		double radians = Math.toRadians(yaw);
		double forwardX = Math.sin(radians);
		double forwardZ = -Math.cos(radians);
		double sideX = Math.cos(radians);
		double sideZ = Math.sin(radians);
		return new Vec3(sideX * lateral + forwardX * along, 0.0,
				sideZ * lateral + forwardZ * along);
	}

	/**
	 * 띠 하나를 그리는 변환.
	 *
	 * <h2>⚠ 상자는 {@code [0,1]³} 에서 시작한다 — 그래서 옆으로 반폭만큼 밀어야 한다</h2>
	 *
	 * <p>26.3 {@code Transformation} 은 {@code 이동 · 왼쪽회전 · 크기 · 오른쪽회전} 순서로 곱한다
	 * ({@code compose}). 곧 블록 모델의 {@code [0,1]³} 이 <b>크기 → 회전 → 이동</b> 을 지나므로
	 * 상자는 언제나 <b>원점에서 양의 방향으로만</b> 자란다. 부채꼴 좌표로 보면 옆으로
	 * {@code [−halfWidth, +halfWidth]} 를 덮어야 하니 이동에 {@code p × halfWidth} 를 얹는다.
	 *
	 * <p>회전은 <b>Y축 한 번</b>이다. {@code Quaternionf.rotationY(α)} 가 국소 {@code +Z} 를
	 * {@code (sin α, 0, cos α)} 로 보내므로, 그것이 중심선 {@code (sin yaw, 0, −cos yaw)} 이 되려면
	 * {@code α = π − yaw} 다. 그러면 국소 {@code +X} 는 {@code −p} 로 가는데, 상자가 옆으로
	 * 대칭이라({@code [−halfWidth, +halfWidth]}) 뒤집혀도 덮는 자리가 같다.
	 *
	 * <p>⚠ <b>이 식이 180도 뒤집히면 브레스가 등 뒤를 칠한다.</b> 눈으로 볼 수 없는 자리라
	 * {@code DragonLastStandConePanelTest} 가 행렬을 직접 굴려 귀퉁이 여덟 개를 재어 본다.
	 */
	static Transformation transformOf(Strip strip, float yaw) {
		Vec3 offset = world(strip.halfWidth(), strip.near(), yaw);
		double radians = Math.toRadians(yaw);
		return new Transformation(
				new Vector3f((float) offset.x, 0.0F, (float) offset.z),
				new Quaternionf().rotationY((float) (Math.PI - radians)),
				new Vector3f((float) (strip.halfWidth() * 2.0), (float) THICKNESS,
						(float) (strip.far() - strip.near())),
				new Quaternionf());
	}

	/** 판이 쓰는 블록. 26.3 에서 색유리는 {@code ColorCollection} 한 칸이다. */
	static BlockState glass() {
		return Blocks.STAINED_GLASS.pick(DyeColor.RED).defaultBlockState();
	}

	/**
	 * 판 하나를 세우는 NBT. <b>태그 이름은 바닐라가 읽는 것과 같아야 한다</b> —
	 * 26.3 {@code Display.readAdditionalSaveData} 와 {@code BlockDisplay.readAdditionalSaveData}
	 * 의 바이트코드에서 그대로 옮겨 적었다.
	 *
	 * <p>개체 없이 만들 수 있게 떼어 두었다. 시험이 이것을 직접 만들어 <b>네 코덱이 실제로
	 * 써지는지</b> 본다 — 코덱이 레지스트리를 요구하면 여기서 터지는데, 그것은 실제 전투에서만
	 * 드러나는 종류의 실패다.
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
	 * {@code setBrightnessOverride} 가 하나도 열려 있지 않다(확인했다). 믹스인도 리플렉션도 쓰지
	 * 않고 들어가는 길은 <b>{@code readAdditionalSaveData(ValueInput)}</b> 하나다 — 그쪽은
	 * {@code protected} 라 하위 클래스가 부를 수 있고, 바닐라가 저장 파일에서 읽는 그 길이라
	 * <b>모든 칸을 다 세울 수 있다.</b> 태그 이름은 바닐라가 읽는 것과 같아야 하므로
	 * 바이트코드에서 그대로 옮겨 적었다({@code transformation}·{@code block_state}·
	 * {@code view_range}·{@code brightness}·{@code billboard}).
	 *
	 * <p>리플렉션을 쓰지 않은 까닭은 이 저장소가 이미 아는 것이다 — 필드 이름이 중간 이름으로
	 * remap 되므로 <b>이름으로 찾는 코드는 배포된 jar 에서만 조용히 실패한다.</b>
	 *
	 * <h2>⚠ 십자 균열도 이 판을 쓴다 (2026-10-04)</h2>
	 *
	 * <p>사람이 십자 예고를 <b>「이것도 브레스처럼 투명땅으로 표시했으면 해」</b>라고 해서
	 * {@link DragonLastStandCrossPanel} 이 <b>이 클래스를 그대로</b> 세운다. 그래서 {@code private}
	 * 에서 패키지 안으로 열었고, 심지 길이를 생성자로 받는다 — 부채꼴과 십자는 예고 길이가 달라
	 * 심지가 하나일 수 없다. <b>저장 안 함 · 스스로 타 없어짐</b>이라는 두 겹은 클래스 하나에 있어야
	 * 한 벌로 남는다. 십자 쪽에 같은 하위 클래스를 또 짜면 언젠가 한쪽만 {@code shouldBeSaved} 를
	 * 잃는다.
	 */
	static final class Panel extends Display.BlockDisplay {

		/** 남은 틱. 0 이 되면 스스로 사라진다. */
		private int fuse;

		/**
		 * @param fuse 스스로 사라질 때까지의 틱. 세우는 쪽이 <b>제 예고 길이에서</b> 셈해 넘긴다
		 */
		Panel(Level level, int fuse) {
			super(EntityTypes.BLOCK_DISPLAY, level);
			this.fuse = fuse;
		}

		/**
		 * 모양을 세운다. 바닐라가 저장 파일을 읽는 길을 그대로 쓴다.
		 *
		 * <p>{@code ProblemReporter.DISCARDING} 인 것은 우리가 만든 태그라 <b>잘못된 칸이 있을
		 * 수 없고</b>, 있다면 그것은 시험이 잡을 일이지 사람의 로그를 어지럽힐 일이 아니다.
		 */
		void dress(ServerLevel end, Transformation transformation) {
			ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING,
					end.registryAccess(), shapeTag(transformation));
			readAdditionalSaveData(input);
		}

		/**
		 * ⚠ <b>저장하지 않는다.</b> 26.3
		 * {@code PersistentEntitySectionManager.storeChunkSections} 가 이 물음으로 거른다.
		 *
		 * <p>이 한 줄이 「서버가 강제 종료되어도 다음 판에 빨간 판이 남지 않는다」의 전부다 —
		 * 그 길에는 {@code SERVER_STOPPING} 도 오지 않으므로 지우는 코드로는 막을 수 없다.
		 */
		@Override
		public boolean shouldBeSaved() {
			return false;
		}

		/**
		 * 심지를 한 칸 태운다. 0 이 되면 스스로 사라진다.
		 *
		 * <p>{@code DragonLastStandPatterns} 가 지우는 것이 정상 경로이고 이것은 <b>그것이 전부
		 * 실패했을 때의 바닥</b>이다. 개체 제 틱에서 세므로 우리 시계가 멈춰도 돈다.
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
