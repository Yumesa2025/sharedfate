package com.sharedfate.sync;

import com.mojang.math.Transformation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Brightness;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 「최후의 저항」 오브젝트 파도의 <b>겉모습</b> — 엔드 크리스탈 아이템을 <b>블록 한 칸 크기로
 * 키워</b> 띄우는 {@code item_display} 개체.
 *
 * <h2>왜 생겼는가 — 사람 말이 「크기를 블럭 정도 크기로 키웟으면」이다</h2>
 *
 * <p>파도의 오브젝트는 원래 <b>투명 갑옷 거치대 머리에 엔드 크리스탈 아이템</b>을 얹은 것이었다.
 * 그것이 작게 보인 까닭이 둘이고 <b>둘 다 키우기로는 못 고친다.</b>
 *
 * <ol>
 *   <li><b>머리 아이템은 0.625배로 그려진다.</b> 26.3 {@code CustomHeadLayer} 가 장비 칸의
 *       아이템을 그 비율로 줄인다 — 머리에 얹는 한 그 비율은 우리 것이 아니다</li>
 *   <li>⚠⚠ <b>엔드 크리스탈 <u>아이템</u>은 납작한 그림 한 장이다.</b>
 *       {@code assets/minecraft/models/item/end_crystal.json} 이
 *       {@code "parent": "minecraft:item/generated"} 이고 텍스처가
 *       {@code item/end_crystal} 한 장이다(26.3 클라이언트 지프에서 확인했다). 3차원 크리스탈
 *       모형은 <b>진짜 {@code end_crystal} 개체의 렌더러만</b> 들고 있다. 곧 머리에 얹은 것은
 *       <b>옆에서 보면 사라지는 종이 한 장</b>이었다 — 사람이 「잘 보이지도 않고」라고 한 것의
 *       절반이 이것이다</li>
 * </ol>
 *
 * <p>그래서 <b>보이는 것을 갑옷 거치대에서 떼어 냈다.</b> 갑옷 거치대는 <b>맞는 상자와 체력
 * 30</b>만 들고(디스플레이 개체는 {@code isPickable()} 이 거짓이라 때릴 수 없다), 모습은 이
 * 개체가 든다.
 *
 * <h2>⚠ 얻는 것 — 「보이는 크기」와 「때려서 맞는 자리」를 <b>값 하나로</b> 묶을 수 있다</h2>
 *
 * <p>머리 아이템으로는 그것이 <b>구조적으로 불가능했다.</b> {@code EntityDimensions} 의 상자는
 * 언제나 <b>발밑에서</b> 자라는데 머리 아이템은 <b>머리 높이에</b> 그려지므로, 상자를 아무리
 * 맞춰도 보이는 것과 어긋난 자리가 남는다(그래서 옛 상자는 1.6 × 2.4 로 <b>넉넉히 크게</b>
 * 잡혀 있었다 — 빗나가지 않는 대신 빈 허공이 맞았다).
 *
 * <p>이 개체는 <b>제 자리에 가운데가 선다.</b> 26.3 {@code ItemTransform.apply} 가 아이템 모형을
 * 그리기 전에 {@code translate(-0.5, -0.5, -0.5)} 를 넣는다(바이트코드로 확인했다) — 곧
 * {@code block_display} 처럼 모퉁이에서 자라지 않고 <b>개체 좌표가 그림의 가운데</b>다. 그래서
 * {@link DragonLastStandObjects#CRYSTAL_HEIGHT} 하나가 <b>그림의 높이와 맞는 상자의 높이를
 * 함께</b> 정한다.
 *
 * <p>빌보드는 {@link Display.BillboardConstraints#CENTER} 다. 납작한 그림이 <b>언제나 보는 쪽을
 * 향하므로</b> ① 옆에서 사라지지 않고 ② <b>어느 각도에서나 보이는 크기가 같다</b> — 그 두 번째가
 * 「보이는 크기 = 맞는 상자」를 각도와 무관하게 참으로 만든다(한 변 S 의 정사각형은 한 변 S 의
 * 정육면체 그림자 안에 어느 방향에서도 들어간다).
 *
 * <h2>왜 {@link DragonLastStandLights} 에 얹지 않았는가</h2>
 *
 * <p>그쪽은 <b>{@code block_display}</b> 전용이다 — {@code shapeTag} 가 {@code block_state} 를
 * 쓰고, 상자가 모퉁이에서 자라는 전제({@code pillarShape}·{@code barShape} 의 {@code −폭/2})로
 * 적혀 있다. 아이템 디스플레이는 그 두 전제가 <b>둘 다 다르다.</b> 그리고 그 파일은 진입 연출
 * ({@link DragonLastStandEntry})과 함께 쓰는 자리라 파도 쪽 사정을 끌고 들어가지 않는 것이 낫다.
 * {@code shapeTag} 가 {@link DragonLastStandConePanel} 과 {@link DragonLastStandLights} 에
 * 이미 두 벌인 것과 같은 판단이고, <b>태그 이름이 판마다 바뀌면 세 곳을 함께 고쳐야 한다.</b>
 *
 * <h2>⚠⚠ 개체는 남는다 — 두 겹으로 막고 그 위에 지우는 길을 둔다</h2>
 *
 * <ol>
 *   <li><b>{@link Shell#shouldBeSaved()} 이 거짓</b>이다. 서버 강제 종료에는
 *       {@code SERVER_STOPPING} 도 오지 않으므로 지우는 코드로는 막을 수 없다</li>
 *   <li><b>스스로 타 없어지는 심지</b>({@link Shell#tick()})</li>
 *   <li>그 위에 {@link #LIVE} 가 <b>개체를 들고</b> 있어 {@link #drop()} 한 번이 전부를 거둔다 —
 *       {@code DragonLastStandObjects.clearState()} 가 그것을 부르고, 그 함수는
 *       {@code DragonLastStand} 의 <b>세 자리</b>(전투 종료 · 상태 비우기 · 서버 종료)에서
 *       불린다</li>
 * </ol>
 */
public final class DragonLastStandShells {

	/**
	 * 보이는 거리(64칸 곱). 2.0 이면 128칸이다.
	 *
	 * <p>{@link DragonLastStandLights#VIEW_RANGE} 를 <b>그대로 가져온다</b> — 같은 아레나에서
	 * 같은 사람들이 보는 것이라 값이 갈릴 이유가 없고, 갈리면 「크리스탈은 보이는데 그 바는 안
	 * 보이는」 거리가 생긴다.
	 */
	static final float VIEW_RANGE = DragonLastStandLights.VIEW_RANGE;

	/**
	 * ⚠ <b>지금 떠 있는 겉모습들.</b> 개체를 <b>들고</b> 있는다.
	 *
	 * <p>정적이라 월드보다 오래 산다. {@link #drop()} 으로 반드시 비운다. 까닭은
	 * {@link DragonLastStandLights} 클래스 설명의 「개체를 한자리에서 들고 있어야 한다」와 같다 —
	 * {@code UUID} 만 적어 두면 월드를 만질 수 없는 자리에서 막힌다.
	 */
	private static final List<Shell> LIVE = new ArrayList<>();

	private DragonLastStandShells() {
	}

	// ------------------------------------------------------------------ 세우기

	/**
	 * 아이템 하나를 <b>가운데가 그 자리에 오게</b> 띄운다.
	 *
	 * @param center 그림의 <b>가운데</b>. 맞는 상자의 가운데와 같은 자리여야 한다
	 * @param item   보일 아이템. 파도는 {@code end_crystal} 이다
	 * @param size   그림의 한 변(칸). {@code item/generated} 그림은 한 변이 1 이라 이 값이 곧 배율이다
	 * @param fuse   스스로 타 없어지기까지의 틱
	 * @return 세운 개체. 세우지 못했으면 {@code null}
	 */
	static @Nullable Shell raise(ServerLevel end, Vec3 center, ItemStack item, double size,
			int fuse) {
		Shell shell = new Shell(end, item, fuse);
		shell.snapTo(center);
		shell.dress(end, cubeShape(size));
		if (!end.addFreshEntity(shell)) {
			return null;
		}
		LIVE.add(shell);
		return shell;
	}

	// ------------------------------------------------------------------ 지우기

	/**
	 * 하나만 지운다. <b>월드를 만지지 않는다</b> — 들고 있는 개체에게 직접 말한다.
	 *
	 * <p>목록에서도 뺀다. 안 빼면 {@link #LIVE} 가 판 하나 동안 죽은 개체로 불어난다.
	 */
	static void drop(@Nullable Shell shell) {
		if (shell == null) {
			return;
		}
		if (!shell.isRemoved()) {
			shell.discard();
		}
		LIVE.remove(shell);
	}

	/**
	 * 전부 지운다.
	 *
	 * <p>{@code discard()} 는 {@code Entity.remove(DISCARDED)} 라 개체 자신과 그것을 들고 있는
	 * 목록만 건드린다. 그래서 {@code SERVER_STOPPED} 에서도 안전하다.
	 */
	static void drop() {
		for (Shell shell : LIVE) {
			if (!shell.isRemoved()) {
				shell.discard();
			}
		}
		LIVE.clear();
	}

	/** 시험이 들여다보는 곳. 지금 겉모습이 몇 개 떠 있는가. */
	static int liveCount() {
		return LIVE.size();
	}

	// ------------------------------------------------------------------ 기하학 (월드 없이 도는 계산)

	/**
	 * 가운데를 그대로 둔 채 <b>키우기만</b> 하는 변환.
	 *
	 * <p>⚠ <b>이동이 0 인 것이 {@link DragonLastStandLights#pillarShape} 와 다른 점이고, 그것이
	 * 이 파일이 따로 있는 까닭의 절반이다.</b> {@code block_display} 는 블록 모형의
	 * {@code [0,1]³} 이 개체 좌표에서 <b>양의 방향으로만</b> 자라 가운데를 맞추려면 이동에
	 * {@code −폭/2} 를 넣어야 한다. 아이템은 26.3 {@code ItemTransform.apply} 가 그리기 전에
	 * {@code translate(−0.5, −0.5, −0.5)} 를 넣으므로(바이트코드로 확인했다) <b>이미 가운데가
	 * 맞아 있다</b> — 여기에 이동을 넣으면 그만큼 어긋난다.
	 */
	static Transformation cubeShape(double size) {
		float side = (float) Math.max(0.0, size);
		return new Transformation(
				new Vector3f(0.0F, 0.0F, 0.0F),
				new Quaternionf(),
				new Vector3f(side, side, side),
				new Quaternionf());
	}

	/**
	 * 개체 하나를 세우는 NBT. <b>태그 이름은 바닐라가 읽는 것과 같아야 한다.</b>
	 *
	 * <p>{@link DragonLastStandLights#shapeTag} 의 다섯 칸에서 {@code block_state} 만 빠진
	 * 모양이다 — 아이템은 태그로 넣지 않고 {@link Shell#dress} 가 칸에 직접 끼운다(그쪽 ⚠ 를
	 * 볼 것).
	 */
	static CompoundTag shapeTag(Transformation transformation,
			Display.BillboardConstraints billboard) {
		CompoundTag tag = new CompoundTag();
		tag.store("transformation", Transformation.EXTENDED_CODEC, transformation);
		// 스스로 밝다. 엔드는 어두운 데가 있고, 아레나 반경이 40 이라 「어디 있나」가 먼저다.
		tag.store("brightness", Brightness.CODEC, Brightness.FULL_BRIGHT);
		tag.store("billboard", Display.BillboardConstraints.CODEC, billboard);
		tag.putFloat("view_range", VIEW_RANGE);
		return tag;
	}

	// ------------------------------------------------------------------ 개체

	/**
	 * 겉모습 한 덩어리.
	 *
	 * <p>개체 종류는 <b>바닐라 {@code EntityTypes.ITEM_DISPLAY} 그대로</b>다. 하위 클래스로 얻는
	 * 것은 둘뿐이다 — <b>저장되지 않는 것</b>과 <b>스스로 타 없어지는 것</b>.
	 */
	static final class Shell extends Display.ItemDisplay {

		/** 보일 아이템. {@link #dress} 가 태그를 읽은 <b>뒤에</b> 다시 끼우는 까닭이 그쪽에 있다. */
		private final ItemStack item;
		/** 남은 틱. 0 이 되면 스스로 사라진다. */
		private int fuse;

		private Shell(Level level, ItemStack item, int fuse) {
			super(EntityTypes.ITEM_DISPLAY, level);
			this.item = item;
			this.fuse = Math.max(1, fuse);
		}

		/**
		 * 모양을 세운다. 바닐라가 저장 파일을 읽는 길을 그대로 쓴다.
		 *
		 * <p>⚠⚠ <b>아이템을 태그 뒤에 끼워야 한다.</b> 26.3
		 * {@code Display.ItemDisplay.readAdditionalSaveData} 는 {@code "item"} 칸이 없으면
		 * {@code ItemStack.EMPTY} 를 끼운다(바이트코드에 {@code orElse(ItemStack.EMPTY)} 가
		 * 있다). 곧 모양만 담은 태그를 읽히면 <b>아이템이 조용히 비워져 아무것도 안 보인다.</b>
		 * 공개된 {@code getSlot(0)} 으로 바로 다시 끼워 그 길을 막는다.
		 *
		 * <p>{@code ProblemReporter.DISCARDING} 인 것은 우리가 만든 태그라 <b>잘못된 칸이 있을 수
		 * 없고</b>, 있다면 시험이 잡을 일이지 사람의 로그를 어지럽힐 일이 아니다
		 * ({@link DragonLastStandLights} 와 같은 판단이다).
		 */
		private void dress(ServerLevel end, Transformation transformation) {
			ValueInput input = TagValueInput.create(ProblemReporter.DISCARDING,
					end.registryAccess(),
					shapeTag(transformation, Display.BillboardConstraints.CENTER));
			readAdditionalSaveData(input);
			getSlot(0).set(item);
		}

		/**
		 * ⚠ <b>저장하지 않는다.</b> 이 한 줄이 「서버가 강제 종료되어도 다음 판에 크리스탈이 떠
		 * 있지 않는다」의 전부다.
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
