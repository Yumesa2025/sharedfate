package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.enchant.EnchantmentCostDataSlot;
import com.sharedfate.enchant.EnchantmentDiamondAccess;
import com.sharedfate.enchant.EnchantmentDiamondCost;
import com.sharedfate.enchant.EnchantmentDiamondSlot;
import com.sharedfate.inventory.ExpandedInventoryManager;
import com.sharedfate.inventory.ExpandedInventoryMoves;
import com.sharedfate.inventory.ExpandedMenuLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.BiConsumer;

/**
 * 인챈트의 대가를 경험치 레벨에서 <b>다이아몬드</b>로 바꾸고, 그 다이아몬드를 넣을
 * <b>칸을 하나 만든다.</b>
 *
 * <h2>어떻게 붙이는가</h2>
 *
 * <p>바닐라 {@code enchantSlots} 는 두 칸짜리({@code SimpleContainer(2)})다. 그 크기
 * 상수를 {@code @ModifyConstant} 로 늘리는 것은 <b>위험하다</b> — 같은 생성자에
 * {@code enchantClue[2]}·{@code levelClue[2]} 가 있어 {@code iconst_2} 가 여러 번
 * 나오고, 어느 것이 잡힐지 서술자만으로는 알 수 없다. 그래서 <b>한 칸짜리 그릇을
 * 따로 만들어</b> 붙인다. 그러면 다이아몬드를 넣고 뺄 때 {@code slotsChanged} 가 불리지
 * 않아 인챈트 후보를 헛되이 다시 뽑는 일도 없다.
 *
 * <p>칸은 <b>맨 뒤</b>에 붙는다. 앞에 끼우면 바닐라 {@code quickMoveStack} 이 쓰는
 * 상수 {@code 2}·{@code 38}(플레이어 인벤토리의 시작과 끝)이 어긋난다.
 *
 * <p><b>슬롯 수가 달라지므로 옛 클라이언트는 접속하면 안 된다.</b> 막는 수단은 악수
 * 규약뿐이라 {@code PROTOCOL_VERSION} 도 함께 올려야 한다.
 *
 * <h2>요구 개수를 화면에 알려 주기</h2>
 *
 * <p>{@code enchant_cost} 증강을 가진 팀은 요구 개수가 달라지는데, 팀은 서버만 안다. 단추의
 * 숫자와 툴팁은 클라이언트가 그리므로 그쪽도 같은 숫자를 알아야 한다 — 5개라고 써 놓고 1개만
 * 걷으면 버그로 보인다. 그래서 <b>데이터 칸</b>을 하나 더 단다
 * ({@link EnchantmentCostDataSlot}).
 *
 * <p><b>데이터 칸의 개수도 달라지므로</b> 이 역시 옛 클라이언트가 접속하면 안 되는 변경이고,
 * {@code PROTOCOL_VERSION} 을 올려야 한다.
 *
 * <h2>창을 닫을 때</h2>
 *
 * <p>바닐라 {@code removed} 는 {@code enchantSlots} 만 비운다. 우리 그릇을 따로 비우지
 * 않으면 <b>창을 닫는 순간 다이아몬드가 증발한다.</b>
 *
 * <p><b>{@code clickMenuButton} 은 클라이언트에서도 그대로 돈다.</b>
 * {@code EnchantmentScreen.mouseClicked} 가 패킷을 보내기 전에 로컬에서 부르고 참일 때만
 * 보낸다. 그래서 검사는 양쪽에서 같이 돌아야 하고, <b>실제 차감은
 * {@code access.execute(...)} 안</b>이어야 한다.
 */
@Mixin(EnchantmentMenu.class)
public abstract class EnchantmentMenuDiamondMixin implements EnchantmentDiamondAccess {
	/** 다이아몬드 칸의 화면 좌표. 아이템 칸(15,47)·청금석 칸(35,47) 아래 한 줄이다. */
	@Unique
	private static final int DIAMOND_SLOT_X = 15;
	@Unique
	private static final int DIAMOND_SLOT_Y = 65;

	/** 바닐라 인챈트 메뉴에서 플레이어 인벤토리가 시작하는 번호. 아이템·청금석 다음이다. */
	@Unique
	private static final int PLAYER_SLOT_START = 2;

	@Shadow
	@Final
	private ContainerLevelAccess access;

	@Unique
	private Container sharedfate$diamonds;
	@Unique
	private int sharedfate$diamondSlot = EnchantmentDiamondAccess.NO_SLOT;
	/** 이 창을 연 사람. 요구 개수를 정할 때 팀을 알아내는 데 쓴다. */
	@Unique
	private Player sharedfate$owner;

	@Override
	public Container sharedfate$diamondContainer() {
		return sharedfate$diamonds;
	}

	@Override
	public int sharedfate$diamondMenuSlot() {
		return sharedfate$diamondSlot;
	}

	@Inject(
			method = "<init>(ILnet/minecraft/world/entity/player/Inventory;"
					+ "Lnet/minecraft/world/inventory/ContainerLevelAccess;)V",
			at = @At("TAIL")
	)
	private void sharedfate$addDiamondSlot(
			int containerId, Inventory inventory, ContainerLevelAccess levelAccess,
			CallbackInfo ci) {
		sharedfate$owner = inventory.player;
		sharedfate$diamonds = new SimpleContainer(1);
		sharedfate$diamondSlot = ((AbstractContainerMenu) (Object) this).slots.size();
		((AbstractContainerMenuAccessor) this).sharedfate$invokeAddSlot(
				new EnchantmentDiamondSlot(
						sharedfate$diamonds, 0, DIAMOND_SLOT_X, DIAMOND_SLOT_Y));
		// 요구 개수를 화면 쪽에 알려 주는 통로다. 양쪽에서 똑같이 붙어야 하므로
		// 조건 없이 언제나 단다.
		((AbstractContainerMenuAccessor) this).sharedfate$invokeAddDataSlot(
				new EnchantmentCostDataSlot(sharedfate$owner));
	}

	/**
	 * 창을 닫을 때 다이아몬드를 돌려준다.
	 *
	 * <p>바닐라와 똑같이 {@code access.execute} 안에서 한다. 클라이언트의 접근자는
	 * {@code NULL} 이라 람다가 돌지 않으므로, 여기서 비우는 일은 서버에서만 일어난다.
	 */
	@Inject(method = "removed", at = @At("HEAD"))
	private void sharedfate$returnDiamonds(Player player, CallbackInfo ci) {
		// 창을 닫으면 화면용 개수도 기본값으로 되돌린다. 다음 창은 열릴 때 다시 받는다.
		if (!(player instanceof ServerPlayer)) {
			EnchantmentDiamondCost.resetShown();
		}
		Container diamonds = sharedfate$diamonds;
		if (diamonds == null) {
			return;
		}
		access.execute((level, pos) -> ((AbstractContainerMenuInvoker) this)
				.sharedfate$invokeClearContainer(player, diamonds));
	}

	/** 다이아몬드가 모자라면 아예 누르지 못하게 한다. 크리에이티브는 그대로 통과한다. */
	@Inject(
			method = "clickMenuButton(Lnet/minecraft/world/entity/player/Player;I)Z",
			at = @At("HEAD"),
			cancellable = true
	)
	private void sharedfate$requireDiamonds(
			Player player, int id, CallbackInfoReturnable<Boolean> cir) {
		if (id < 0 || id >= EnchantmentDiamondCost.SLOT_COUNT) {
			// 범위 밖은 바닐라가 기록을 남기고 거절한다. 그 자리를 뺏지 않는다.
			return;
		}
		if (!EnchantmentDiamondCost.canAfford(player, sharedfate$diamonds, id)) {
			cir.setReturnValue(false);
		}
	}

	/**
	 * 요구 레벨을 없앤다.
	 *
	 * <p>{@code clickMenuButton} 안의 {@code experienceLevel} 읽기는 정확히 둘이고
	 * 각각 「레벨 ≥ 칸 번호 + 1」과 「레벨 ≥ costs[칸]」에 쓰인다. 둘 다 무한대로
	 * 바꿔 언제나 통과시킨다. 레벨을 깎는 일은
	 * {@link PlayerEnchantmentLevelMixin} 이 막는다.
	 */
	@ModifyExpressionValue(
			method = "clickMenuButton(Lnet/minecraft/world/entity/player/Player;I)Z",
			at = @At(
					value = "FIELD",
					target = "Lnet/minecraft/world/entity/player/Player;experienceLevel:I",
					opcode = Opcodes.GETFIELD
			),
			require = 2,
			allow = 2
	)
	private int sharedfate$dropLevelRequirement(int experienceLevel) {
		return Integer.MAX_VALUE;
	}

	/**
	 * 다이아몬드를 실제로 걷는다.
	 *
	 * <p>바닐라가 넘기는 람다를 한 겹 감싸서, <b>서버에서만 도는 그 안</b>에서 걷는다.
	 * 인챈트가 정말 이루어졌는지는 <b>청금석이 줄었는지</b>로 본다 — 바닐라는 인챈트
	 * 후보가 하나도 없으면 아무것도 하지 않고 빠져나가는데, 그때 다이아몬드만 사라지면
	 * 안 된다.
	 */
	@WrapOperation(
			method = "clickMenuButton(Lnet/minecraft/world/entity/player/Player;I)Z",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/inventory/ContainerLevelAccess;"
							+ "execute(Ljava/util/function/BiConsumer;)V"
			)
	)
	private void sharedfate$chargeDiamonds(
			ContainerLevelAccess levelAccess, BiConsumer<Level, BlockPos> enchantAction,
			Operation<Void> original, Player player, int id) {
		EnchantmentMenu menu = (EnchantmentMenu) (Object) this;
		original.call(levelAccess, (BiConsumer<Level, BlockPos>) (level, pos) -> {
			// getGoldCount 는 이름과 달리 청금석 개수를 돌려준다.
			int lapisBefore = menu.getGoldCount();
			enchantAction.accept(level, pos);
			if (menu.getGoldCount() < lapisBefore) {
				EnchantmentDiamondCost.consume(player, sharedfate$diamonds, id);
			}
		});
	}

	/**
	 * 쉬프트 클릭이 다이아몬드 칸과 추가 27칸을 알아보게 한다.
	 *
	 * <p>바닐라는 두 칸(아이템·청금석)과 플레이어 36칸만 안다. 그 밖의 번호가 오면
	 * 「아이템 칸에 한 개 넣기」로 처리해 버려서, 다이아몬드를 쉬프트 클릭하면 인챈트할
	 * 물건 자리에 다이아몬드가 놓인다.
	 */
	@Inject(method = "quickMoveStack", at = @At("HEAD"), cancellable = true)
	private void sharedfate$quickMoveDiamonds(
			Player player, int index, CallbackInfoReturnable<ItemStack> cir) {
		AbstractContainerMenu menu = (AbstractContainerMenu) (Object) this;
		int extraStart = ((ExpandedMenuLayout) menu).sharedfate$extraSlotStart();
		int extraSize = ExpandedInventoryManager.EXTRA_SIZE;
		boolean fromExtra = extraStart >= 0
				&& index >= extraStart && index < extraStart + extraSize;
		boolean fromDiamondSlot = index == sharedfate$diamondSlot;
		boolean fromPlayer = index >= PLAYER_SLOT_START
				&& index < PLAYER_SLOT_START + 36;
		if (!fromExtra && !fromDiamondSlot && !fromPlayer) {
			return;
		}

		Slot slot = menu.getSlot(index);
		if (!slot.hasItem() || !slot.mayPickup(player)) {
			return;
		}
		ItemStack stack = slot.getItem();

		// 플레이어 쪽에서 온 다이아몬드는 다이아몬드 칸으로 보낸다.
		if ((fromExtra || fromPlayer) && stack.is(Items.DIAMOND)
				&& sharedfate$diamondSlot != EnchantmentDiamondAccess.NO_SLOT) {
			sharedfate$finishQuickMove(player, cir, menu, slot, stack,
					new int[] {sharedfate$diamondSlot}, false);
			return;
		}
		// 추가 칸에서 온 것은 바닐라 세 줄처럼 핫바로만 보낸다. 위 세 줄을 섞으면 같은
		// 아이템이 덜 찬 칸으로 합쳐져 위로 올라간다.
		if (fromExtra) {
			sharedfate$finishQuickMove(player, cir, menu, slot, stack,
					ExpandedInventoryMoves.hotbarOrder(PLAYER_SLOT_START), false);
			return;
		}
		// 다이아몬드 칸에서 온 것은 플레이어 인벤토리로 돌려보낸다.
		if (fromDiamondSlot) {
			sharedfate$finishQuickMove(player, cir, menu, slot, stack,
					ExpandedInventoryMoves.order(
							PLAYER_SLOT_START, extraSize,
							PLAYER_SLOT_START + extraSize,
							ExpandedInventoryManager.EXTRA_COLUMNS),
					false);
		}
	}

	@Unique
	private void sharedfate$finishQuickMove(
			Player player, CallbackInfoReturnable<ItemStack> cir, AbstractContainerMenu menu,
			Slot slot, ItemStack stack, int[] order, boolean reverse) {
		ItemStack original = stack.copy();
		if (!ExpandedInventoryMoves.move(menu, stack, order, reverse)) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY, original);
		} else {
			slot.setChanged();
		}
		if (stack.getCount() == original.getCount()) {
			cir.setReturnValue(ItemStack.EMPTY);
			return;
		}
		slot.onTake(player, stack);
		cir.setReturnValue(original);
	}
}
