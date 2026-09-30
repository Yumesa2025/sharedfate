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
 * 「최후의 저항」의 <b>신호기 빛</b>과 <b>타이머 바</b>. 둘 다 파티클이 아니라 <b>디스플레이
 * 개체</b>다.
 *
 * <h2>왜 이 파일이 따로 있는가 — 개체를 <b>한자리에서</b> 들고 있어야 한다</h2>
 *
 * <p>{@link DragonLastStandConePanel} 이 같은 사정을 먼저 겪었다. 개체는 파티클과 달리
 * <b>남는다.</b> 남으면 다음 판까지 빛기둥이 서 있고, 그것을 지우는 자리
 * ({@code DragonLastStand.clearState})는 {@code SERVER_STOPPED} 에서도 불려 <b>월드를 만질 수
 * 없다.</b> 그래서 <b>{@link #LIVE} 가 개체를 들고 있는다</b> — 들고 있으면
 * {@code discard()} 한 줄로 끝나고, {@code UUID} 만 적어 두었다면 그 자리에서 막힌다.
 *
 * <p>쓰는 쪽이 둘이다({@link DragonLastStandEntry} 의 <b>보라색</b> 신호기 넷,
 * {@link DragonLastStandObjects} 의 <b>흰</b> 신호기 선과 타이머 바). 둘이 각자 제 개체를 들고
 * 있으면 지우는 자리가 둘로 늘고, <b>그중 하나를 빠뜨리는 것</b>이 이 저장소가 반복해 적어 둔
 * 실패다. 여기 하나로 모아 두면 {@link #drop()} 한 번이 전부를 거둔다.
 *
 * <h2>26.3 에서 고른 것 — {@code block_display} 다. 까닭은 부채꼴의 빨간 면과 같다</h2>
 *
 * <p>{@link DragonLastStandConePanel} 클래스 설명의 표를 그대로 따른다.
 * {@code text_display} 는 크기가 글자에서 나오고 {@code item_display} 는 납작한 색 한 장을 만들
 * 수 없다. {@code block_display} 만 <b>블록이 스스로 색과 반투명을 들고 있어</b> 색·투명도 API
 * 가 필요 없다 — 26.3 에서 색유리는 {@code Blocks.STAINED_GLASS.pick(DyeColor)} 다
 * ({@code Blocks.RED_STAINED_GLASS} 같은 필드가 아니다).
 *
 * <p>모두 <b>바닐라 개체</b>라 통신 규약이 올라가지 않고 모드를 안 깐 사람에게도 보인다.
 * 그리고 <b>점 예산을 한 개도 쓰지 않는다</b> — 신호기 빛을 파티클 기둥으로 세우면 한 기둥에
 * 수십 점이라 넷이면 그것만으로 예산이 흔들린다.
 *
 * <h2>⚠ 진짜 신호기 블록을 놓지 않는다</h2>
 *
 * <p>사람이 말한 것은 <b>「신호기빛」</b>이고, 바닐라 신호기 빛은 {@code BeaconBlockEntity} 가
 * 있어야 난다 — 곧 <b>블록을 놓아야</b> 한다. 이 페이즈에서 블록을 만지는 것은
 * {@link DragonLastStandDome} 하나뿐이고 그쪽은 <b>부수기만</b> 한다. 블록을 놓으면
 * <b>다음 전투의 발판이 달라지고</b>, 그 자리에 신호기 기단(철·금·다이아·에메랄드)까지
 * 필요하다. 그래서 「빛기둥처럼 보이는 것」을 디스플레이 개체로 세운다 —
 * {@link #pillarShape} 가 그 상자다.
 *
 * <h2>두 겹으로 막는다 — 저장 금지 + 스스로 타는 심지</h2>
 *
 * <ol>
 *   <li><b>{@link Glow#shouldBeSaved()} 이 거짓</b>이다. 26.3
 *       {@code PersistentEntitySectionManager.storeChunkSections} 가 저장할 개체를
 *       {@code EntityAccess::shouldBeSaved} 로 거른다. 이것이 있어야 <b>서버 강제 종료</b>가
 *       안전하다 — 그 길에는 {@code SERVER_STOPPING} 도 오지 않으므로 지우는 코드로는 막을 수
 *       없다</li>
 *   <li><b>스스로 타 없어지는 심지</b>({@link Glow#tick()}). 누가 지우는 줄을 빠뜨려도 받은
 *       심지 틱 뒤에는 반드시 사라진다</li>
 * </ol>
 *
 * <p>그 위에 제때 지우는 길이 <b>다섯</b>이다 — 쓰는 쪽이 제 연출을 끝내는 틱,
 * {@code DragonLastStandEntry.clearState()}·{@code DragonLastStandObjects.clearState()},
 * {@code DragonLastStand.clearState()}, {@code DragonLastStand.onFightClosed},
 * {@code DragonLastStand.onServerStopping}. 심지는 그 다섯이 전부 실패했을 때의 바닥이다.
 */
public final class DragonLastStandLights {

	/**
	 * 보이는 거리(64칸 곱). 2.0 이면 128칸이다.
	 *
	 * <p>{@link DragonLastStandConePanel} 과 <b>같은 값이고 같은 근거</b>다. 26.3
	 * {@code Display.shouldRenderAtSqrDistance} 가 {@code 거리² < (viewRange × 64 × 시야배율)²}
	 * 이고, 아레나 반경이 40 이라 팀원 둘이 80칸 떨어질 수 있다. 「무엇이 남았는가」는 그것을
	 * 때리는 사람이 아니라 <b>나머지 셋이 봐야 하는 정보</b>다.
	 *
	 * <p>⚠ 이 값이 <b>타이머 바를 개체 이름표로 만들지 않은 까닭</b>이기도 하다. 이름표는
	 * {@code EntityRenderer.shouldShowName} 이 <b>64칸</b>에서 자르고 그 값을 우리가 고를 수
	 * 없다 — 아레나가 그보다 넓다.
	 */
	static final float VIEW_RANGE = 2.0F;

	/**
	 * 빛기둥의 굵기(칸).
	 *
	 * <p>바닐라 신호기 빛이 <b>한 칸 기둥의 절반쯤</b> 굵기로 보이는 것을 흉내 낸 값이다. 더
	 * 굵으면 기둥이 아니라 벽으로 읽히고, 더 얇으면 멀리서 사라진다(반투명이라 두께가 곧
	 * 진하기다).
	 */
	static final double PILLAR_WIDTH = 0.5;

	/**
	 * 타이머 바의 두께(칸). 배경이 채움보다 <b>두껍다.</b>
	 *
	 * <p>배경이 채움을 위아래로 감싸 <b>테</b>가 된다. 같은 두께면 다 찬 바에서 배경이 한 줄도
	 * 안 보여 「무엇이 줄어드는 것인지」를 처음 볼 때 알 수 없다.
	 */
	static final double BAR_BACK_THICKNESS = 0.40;
	static final double BAR_FILL_THICKNESS = 0.28;

	/**
	 * ⚠ 타이머 바의 깊이(칸). <b>채움이 배경보다 깊다</b> — 그것이 두 판이 겹쳐 떨리지 않게 하는
	 * 유일한 장치다.
	 *
	 * <p>배경과 채움은 <b>같은 자리</b>에 서므로 깊이가 같으면 같은 평면에서 z 싸움이 나
	 * 화면이 지글거린다. 한쪽을 앞으로 밀어 피하는 길도 있지만, 빌보드가
	 * {@link Display.BillboardConstraints#CENTER} 라 <b>국소 {@code +Z} 가 카메라 쪽인지 반대인지
	 * 식으로 확인해야</b> 하고 틀리면 채움이 배경 뒤로 숨는다.
	 *
	 * <p>그래서 <b>채움을 더 깊게 만들어 양쪽으로 튀어나오게</b> 했다. 어느 쪽에서 봐도 채움이
	 * 앞이므로 부호를 몰라도 된다 — 0.12 와 0.04 라 양쪽으로 0.04 씩 나온다.
	 */
	static final double BAR_BACK_DEPTH = 0.04;
	static final double BAR_FILL_DEPTH = 0.12;

	/**
	 * ⚠ <b>지금 서 있는 빛들.</b> 개체를 <b>들고</b> 있는다.
	 *
	 * <p>정적이라 월드보다 오래 산다. {@link #drop()} 으로 반드시 비운다. 까닭은 클래스 설명의
	 * 「개체를 한자리에서 들고 있어야 한다」에 있다.
	 */
	private static final List<Glow> LIVE = new ArrayList<>();

	private DragonLastStandLights() {
	}

	// ------------------------------------------------------------------ 세우기

	/**
	 * 빛기둥 하나. <b>밑바닥</b>에서 위로 자란다.
	 *
	 * @param base   기둥의 밑바닥 가운데
	 * @param color  기둥 색. 진입 연출은 보라, 오브젝트 파도는 흰색이다
	 * @param height 기둥 높이(칸)
	 * @param fuse   스스로 타 없어지기까지의 틱
	 * @return 세운 개체. 세우지 못했으면 {@code null}
	 */
	static @Nullable Glow raisePillar(ServerLevel end, Vec3 base, DyeColor color, double height,
			int fuse) {
		return raise(end, base, glass(color), Display.BillboardConstraints.FIXED,
				pillarShape(PILLAR_WIDTH, height), fuse);
	}

	/**
	 * 타이머 바의 <b>배경</b> 한 장. 길이가 변하지 않는다.
	 *
	 * <p>빌보드가 {@link Display.BillboardConstraints#CENTER} 다 — 보는 사람 쪽으로 판이 돌아
	 * 어디에서 봐도 <b>가로 바</b>로 읽힌다. 바닥 표식과 달리 이것은 <b>읽는 계기에 가까운
	 * 것</b>이라 방향이 고정될 이유가 없다(부채꼴의 빨간 면이 {@code FIXED} 인 것은 그쪽이
	 * <b>바닥의 어느 자리</b>를 가리키기 때문이다).
	 */
	static @Nullable Glow raiseBarBack(ServerLevel end, Vec3 at, DyeColor color, double fullWidth,
			int fuse) {
		return raise(end, at, glass(color), Display.BillboardConstraints.CENTER,
				barShape(fullWidth, fullWidth, BAR_BACK_THICKNESS, BAR_BACK_DEPTH), fuse);
	}

	/** 타이머 바의 <b>채움</b> 한 장. {@link #reshapeBarFill} 로 줄어든다. */
	static @Nullable Glow raiseBarFill(ServerLevel end, Vec3 at, DyeColor color, double fullWidth,
			int fuse) {
		return raise(end, at, glass(color), Display.BillboardConstraints.CENTER,
				barShape(fullWidth, fullWidth, BAR_FILL_THICKNESS, BAR_FILL_DEPTH), fuse);
	}

	/**
	 * 서 있는 채움의 길이를 고쳐 세운다. <b>개체를 다시 만들지 않는다.</b>
	 *
	 * <p>매번 지우고 새로 만들면 클라이언트가 개체를 지우고 다시 받아 <b>깜빡인다.</b> 26.3
	 * 디스플레이의 설정자가 전부 {@code private} 이라 들어가는 길은 {@code readAdditionalSaveData}
	 * 하나이고, 그것은 같은 개체에 <b>여러 번 부를 수 있다</b> — {@link Glow#dress} 가 태그를
	 * 통째로 다시 만들어 넘기므로 빠진 칸이 기본값으로 되돌아가는 일도 없다.
	 *
	 * @param fullWidth 배경에 넘긴 것과 <b>같은 값</b>이어야 왼쪽 끝이 맞는다
	 */
	static void reshapeBarFill(ServerLevel end, @Nullable Glow bar, double fullWidth,
			double width) {
		if (bar == null || bar.isRemoved()) {
			return;
		}
		bar.dress(end, barShape(fullWidth, width, BAR_FILL_THICKNESS, BAR_FILL_DEPTH));
	}

	private static @Nullable Glow raise(ServerLevel end, Vec3 at, BlockState state,
			Display.BillboardConstraints billboard, Transformation shape, int fuse) {
		Glow glow = new Glow(end, state, billboard, fuse);
		glow.snapTo(at);
		glow.dress(end, shape);
		if (!end.addFreshEntity(glow)) {
			return null;
		}
		LIVE.add(glow);
		return glow;
	}

	// ------------------------------------------------------------------ 지우기

	/**
	 * 하나만 지운다. <b>월드를 만지지 않는다</b> — 들고 있는 개체에게 직접 말한다.
	 *
	 * <p>목록에서도 뺀다. 안 빼면 {@link #LIVE} 가 판 하나 동안 죽은 개체로 불어난다.
	 */
	static void drop(@Nullable Glow glow) {
		if (glow == null) {
			return;
		}
		if (!glow.isRemoved()) {
			glow.discard();
		}
		LIVE.remove(glow);
	}

	/**
	 * 전부 지운다.
	 *
	 * <p>{@code discard()} 는 {@code Entity.remove(DISCARDED)} 라 개체 자신과 그것을 들고 있는
	 * 목록만 건드린다. 그래서 {@code SERVER_STOPPED} 에서도 안전하다.
	 */
	static void drop() {
		for (Glow glow : LIVE) {
			if (!glow.isRemoved()) {
				glow.discard();
			}
		}
		LIVE.clear();
	}

	/** 시험이 들여다보는 곳. 지금 빛이 몇 개 서 있는가. */
	static int liveCount() {
		return LIVE.size();
	}

	// ------------------------------------------------------------------ 기하학 (월드 없이 도는 계산)

	/**
	 * 빛기둥의 변환.
	 *
	 * <p>⚠ 26.3 {@code Transformation} 의 상자는 블록 모델의 {@code [0,1]³} 이 <b>크기 → 회전 →
	 * 이동</b> 을 지난 것이라 언제나 <b>원점에서 양의 방향으로만</b> 자란다
	 * ({@link DragonLastStandConePanel#transformOf} 에 같은 설명이 있다). 곧 가로로 가운데를
	 * 맞추려면 이동에 {@code −굵기/2} 를 넣어야 하고, 세로는 <b>밑바닥에서 위로</b>가 맞으므로
	 * 0 이다.
	 */
	static Transformation pillarShape(double width, double height) {
		return new Transformation(
				new Vector3f((float) (-width / 2.0), 0.0F, (float) (-width / 2.0)),
				new Quaternionf(),
				new Vector3f((float) width, (float) Math.max(0.0, height), (float) width),
				new Quaternionf());
	}

	/**
	 * 타이머 바의 변환. <b>왼쪽 끝이 제자리에 남고 오른쪽 끝만 들어온다.</b>
	 *
	 * <p>이동의 {@code x} 가 {@code −전체길이/2} 로 <b>고정</b>이고 크기의 {@code x} 만 줄어든다.
	 * 가운데를 기준으로 양쪽이 함께 줄면 「줄어드는 막대」가 아니라 「작아지는 막대」로 읽혀
	 * 남은 양을 가늠할 수 없다.
	 *
	 * <p>세로와 깊이는 가운데를 맞춘다. 빌보드가 {@code CENTER} 라 국소 {@code +X} 가 화면
	 * 오른쪽이고 {@code +Y} 가 화면 위다 — 곧 이 계산 그대로 보인다.
	 *
	 * @param fullWidth 남은 시간이 가득일 때의 길이. 배경 판과 <b>같은 값</b>이어야 왼쪽 끝이 맞는다
	 * @param width     지금 길이
	 */
	static Transformation barShape(double fullWidth, double width, double thickness,
			double depth) {
		return new Transformation(
				new Vector3f((float) (-fullWidth / 2.0), (float) (-thickness / 2.0),
						(float) (-depth / 2.0)),
				new Quaternionf(),
				new Vector3f((float) Math.max(0.0, width), (float) thickness, (float) depth),
				new Quaternionf());
	}

	/**
	 * 그 색의 색유리.
	 *
	 * <p>⚠ <b>26.3 에서 색유리는 {@code Blocks.PURPLE_STAINED_GLASS} 가 아니다</b> —
	 * {@code ColorCollection<Block>} 한 칸으로 묶여 있어 {@code pick(DyeColor)} 로 꺼낸다. 판이
	 * 올라 이 모양이 바뀌면 컴파일이 먼저 깨진다({@link DragonLastStandConePanel#glass()} 에 같은
	 * 설명이 있다).
	 */
	static BlockState glass(DyeColor color) {
		return Blocks.STAINED_GLASS.pick(color).defaultBlockState();
	}

	/**
	 * 개체 하나를 세우는 NBT. <b>태그 이름은 바닐라가 읽는 것과 같아야 한다.</b>
	 *
	 * <p>{@link DragonLastStandConePanel#shapeTag} 와 <b>같은 다섯 칸</b>이다. 그쪽이
	 * {@code private} 이라 부를 수 없어 같은 것이 두 벌이 됐고, 그 사실을 여기 적어 둔다 —
	 * 태그 이름이 판마다 바뀌면 <b>두 곳을 함께</b> 고쳐야 한다.
	 */
	static CompoundTag shapeTag(Transformation transformation, BlockState state,
			Display.BillboardConstraints billboard) {
		CompoundTag tag = new CompoundTag();
		tag.store("transformation", Transformation.EXTENDED_CODEC, transformation);
		tag.store("block_state", BlockState.CODEC, state);
		// 스스로 밝다. 엔드는 어두운 데가 있고 「빛기둥」이 어두우면 빛이 아니다.
		tag.store("brightness", Brightness.CODEC, Brightness.FULL_BRIGHT);
		tag.store("billboard", Display.BillboardConstraints.CODEC, billboard);
		tag.putFloat("view_range", VIEW_RANGE);
		return tag;
	}

	// ------------------------------------------------------------------ 개체

	/**
	 * 빛 한 덩어리.
	 *
	 * <p>개체 종류는 <b>바닐라 {@code EntityTypes.BLOCK_DISPLAY} 그대로</b>다. 하위 클래스로
	 * 얻는 것은 둘뿐이다 — <b>저장되지 않는 것</b>과 <b>스스로 타 없어지는 것</b>.
	 */
	static final class Glow extends Display.BlockDisplay {

		private final BlockState state;
		private final Display.BillboardConstraints billboard;
		/** 남은 틱. 0 이 되면 스스로 사라진다. */
		private int fuse;

		private Glow(Level level, BlockState state, Display.BillboardConstraints billboard,
				int fuse) {
			super(EntityTypes.BLOCK_DISPLAY, level);
			this.state = state;
			this.billboard = billboard;
			this.fuse = Math.max(1, fuse);
		}

		/**
		 * 모양을 세운다. 바닐라가 저장 파일을 읽는 길을 그대로 쓴다.
		 *
		 * <p>{@code ProblemReporter.DISCARDING} 인 것은 우리가 만든 태그라 <b>잘못된 칸이 있을 수
		 * 없고</b>, 있다면 시험이 잡을 일이지 사람의 로그를 어지럽힐 일이 아니다.
		 */
		private void dress(ServerLevel end, Transformation transformation) {
			ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING,
					end.registryAccess(), shapeTag(transformation, state, billboard));
			readAdditionalSaveData(input);
		}

		/**
		 * ⚠ <b>저장하지 않는다.</b> 이 한 줄이 「서버가 강제 종료되어도 다음 판에 빛기둥이 남지
		 * 않는다」의 전부다.
		 */
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
