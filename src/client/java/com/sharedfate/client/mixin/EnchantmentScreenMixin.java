package com.sharedfate.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.enchant.EnchantmentDiamondCost;
import com.sharedfate.enchant.EnchantmentDiamondTooltip;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 인챈트 창을 <b>다이아몬드 기준</b>으로 그린다.
 *
 * <p>대가가 경험치 레벨이 아니므로 바닐라 화면은 전부 거짓말이 된다. 단추의
 * 숫자는 요구 레벨이고, 회색으로 죽는 조건도 레벨이며, 툴팁도 레벨을 말한다.
 *
 * <p>고치는 방법은 <b>바닐라가 읽는 두 값을 갈아 끼우는 것</b>이다.
 *
 * <ul>
 *   <li>{@code menu.costs} → 칸마다의 다이아몬드 개수 (후보가 없는 칸의 0 은 그대로)</li>
 *   <li>{@code player.experienceLevel} → 가진 다이아몬드 개수</li>
 * </ul>
 *
 * <p>바닐라가 하는 「레벨 ≥ costs[칸]」 비교가 그대로 「다이아몬드 ≥ 필요 개수」가 되고,
 * 단추에 찍히는 숫자도 저절로 다이아몬드 개수가 된다. 칸마다 값을 다르게 바꿔도
 * 화면은 손댈 필요가 없다.
 *
 * <p><b>통신은 필요 없다.</b> 청금석 개수({@code getGoldCount})와 {@code costs} 는
 * 이미 서버가 보내 주고, 다이아몬드는 클라이언트가 제 인벤토리를 세면 된다.
 * 크리에이티브 예외는 바닐라 자리 그대로 남는다.
 */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenMixin {
	/** 비용은 다이아몬드로 남기고, 후보 강도와 책장 포화 여부는 별도 줄에 적는다. */
	@Inject(method = "extractBackground", at = @At("TAIL"))
	private void sharedfate$showGrades(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick,
			CallbackInfo ci) {
		var screen = (EnchantmentScreen) (Object) this;
		var layout = (AbstractContainerScreenAccessor) this;
		var font = net.minecraft.client.Minecraft.getInstance().font;
		int left = layout.sharedfate$getLeftPos();
		int top = layout.sharedfate$getTopPos();
		for (int slot = 0; slot < 3; slot++) {
			int grade = screen.getMenu().costs[slot];
			if (grade > 0) graphics.text(font, Component.literal(grade + "레벨"),
					left + layout.sharedfate$getImageWidth() + 5, top + 20 + slot * 19, 0xFFFFFFFF, true);
		}
		int shelves = ((com.sharedfate.enchant.EnchantmentTableAccess) screen.getMenu()).sharedfate$bookshelves();
		graphics.text(font, Component.literal("책장 " + shelves + "/15"), left + 80, top + 4, 0xFF404040, false);
	}
	/** 「인벤토리」 글자를 옮길 자리. 조합법 단추 세 개(y 14~71) 바로 아래다. */
	@Unique
	private static final int INVENTORY_LABEL_X = 60;
	@Unique
	private static final int INVENTORY_LABEL_Y = 72;

	/** 단추에 찍히는 숫자와 회색 판정의 기준값을 다이아몬드 개수로 바꾼다. */
	@ModifyExpressionValue(
			method = "extractBackground",
			at = @At(
					value = "FIELD",
					target = "Lnet/minecraft/world/inventory/EnchantmentMenu;costs:[I",
					opcode = Opcodes.GETFIELD
			),
			require = 1,
			allow = 1
	)
	private int[] sharedfate$diamondCostsOnButtons(int[] costs) {
		return EnchantmentDiamondCost.displayCosts(costs);
	}

	/** 단추를 살릴지 죽일지를 레벨이 아니라 가진 다이아몬드로 정한다. */
	@ModifyExpressionValue(
			method = "extractBackground",
			at = @At(
					value = "FIELD",
					target = "Lnet/minecraft/client/player/LocalPlayer;experienceLevel:I",
					opcode = Opcodes.GETFIELD
			),
			require = 1,
			allow = 1
	)
	private int sharedfate$diamondsInsteadOfLevelOnButtons(int experienceLevel) {
		return sharedfate$ownedDiamonds();
	}

	/** 툴팁에서도 같은 값을 쓴다. {@code costs} 는 여기서 두 번 읽힌다. */
	@ModifyExpressionValue(
			method = "extractRenderState",
			at = @At(
					value = "FIELD",
					target = "Lnet/minecraft/world/inventory/EnchantmentMenu;costs:[I",
					opcode = Opcodes.GETFIELD
			),
			require = 2,
			allow = 2
	)
	private int[] sharedfate$diamondCostsInTooltip(int[] costs) {
		return EnchantmentDiamondCost.displayCosts(costs);
	}

	@ModifyExpressionValue(
			method = "extractRenderState",
			at = @At(
					value = "FIELD",
					target = "Lnet/minecraft/client/player/LocalPlayer;experienceLevel:I",
					opcode = Opcodes.GETFIELD
			),
			require = 1,
			allow = 1
	)
	private int sharedfate$diamondsInsteadOfLevelInTooltip(int experienceLevel) {
		return sharedfate$ownedDiamonds();
	}

	/**
	 * 툴팁 글월을 다시 쓴다.
	 *
	 * <p>위의 두 바꿔치기로 <b>어느 줄이 뜨는지</b>는 이미 다이아몬드 기준이 되지만,
	 * 글월 자체는 아직 「필요 레벨」·「인챈트 레벨 N」이라 거짓말이다. 바닐라가 다 만든
	 * 줄 목록을 받아 그 두 종류만 갈아 끼운다.
	 */
	@WrapOperation(
			method = "extractRenderState",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;II)V"
			)
	)
	private void sharedfate$diamondTooltipText(
			GuiGraphicsExtractor graphics, Font font, List<Component> lines, int mouseX, int mouseY,
			Operation<Void> original) {
		original.call(graphics, font, EnchantmentDiamondTooltip.rewrite(lines), mouseX, mouseY);
	}

	/**
	 * 「인벤토리」 글자를 다이아몬드 칸 옆으로 옮긴다.
	 *
	 * <p>바닐라는 그 글자를 (8, 72)에 찍는데, 다이아몬드 칸이 (15, 65)~(15, 82)를 쓰므로
	 * 그대로 두면 글자가 칸 위에 겹쳐 찍힌다. 인챈트 탁자에서 그 줄만 조합법 단추 아래
	 * 빈자리로 민다.
	 */
	@Inject(method = "init", at = @At("RETURN"))
	private void sharedfate$moveInventoryLabel(CallbackInfo ci) {
		AbstractContainerScreenAccessor screen = (AbstractContainerScreenAccessor) this;
		screen.sharedfate$setInventoryLabelX(INVENTORY_LABEL_X);
		screen.sharedfate$setInventoryLabelY(INVENTORY_LABEL_Y);
	}

	@Unique
	private int sharedfate$ownedDiamonds() {
		return EnchantmentDiamondCost.countIn(
				((EnchantmentScreen) (Object) this).getMenu());
	}
}
