package com.sharedfate.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.enchant.EnchantmentDiamondCost;
import com.sharedfate.enchant.EnchantmentDiamondTooltip;
import com.sharedfate.enchant.EnchantmentTableAccess;
import com.sharedfate.enchant.EnchantmentTableRules;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * <p>청금석은 칸과 상관없이 3개다. 단추를 살리는 비교, 청금석 그림, 툴팁의 청금석 줄을 모두
 * 3개 기준으로 바꾼다 — 서버({@code EnchantmentMenuTableRulesMixin})와 같은 규칙이다.
 *
 * <p><b>통신은 필요 없다.</b> 청금석 개수({@code getGoldCount})와 {@code costs} 는
 * 이미 서버가 보내 주고, 다이아몬드는 클라이언트가 제 인벤토리를 세면 된다.
 * 크리에이티브 예외는 바닐라 자리 그대로 남는다.
 */
@Mixin(EnchantmentScreen.class)
public abstract class EnchantmentScreenMixin {
	/**
	 * 비용은 다이아몬드로 남기고, 칸의 등급과 책장 수는 따로 적는다.
	 *
	 * <p>책장 줄은 언제나 띄운다. 모자라면 빨갛게 「책장이 부족합니다 (N/6)」, 넉넉하면 「책장 6/6」이다.
	 * 제목과 겹치지 않게 창 오른쪽 끝에 붙인다.
	 */
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
		int shelves = ((EnchantmentTableAccess) screen.getMenu()).sharedfate$bookshelves();
		String label = EnchantmentTableRules.shelfLabel(shelves);
		int color = EnchantmentTableRules.lacksShelves(shelves) ? SHELF_LACKING_COLOR : SHELF_ENOUGH_COLOR;
		int x = left + layout.sharedfate$getImageWidth() - SHELF_LABEL_RIGHT_MARGIN - font.width(label);
		graphics.text(font, Component.literal(label), x, top + SHELF_LABEL_Y, color, false);
	}

	/** 책장이 넉넉할 때의 색. 바닐라 제목과 같은 짙은 회색이다. */
	@Unique
	private static final int SHELF_ENOUGH_COLOR = 0xFF404040;
	/** 책장이 모자랄 때의 색. 바닐라 「필요 레벨」 줄과 비슷한 빨강이다. */
	@Unique
	private static final int SHELF_LACKING_COLOR = 0xFFC02020;
	@Unique
	private static final int SHELF_LABEL_RIGHT_MARGIN = 8;
	@Unique
	private static final int SHELF_LABEL_Y = 4;

	/**
	 * 단추를 살릴지 정하는 청금석 개수를 3개 기준으로 바꾼다.
	 *
	 * <p>바닐라는 「청금석 ≥ 칸 번호 + 1」로 칸마다 따로 살린다. 모자라면 0 으로 보여 세 칸을 모두
	 * 죽이고, 넉넉하면 그대로 두어 세 칸을 모두 살린다. 서버도 3개 미만이면 거절한다.
	 */
	@ModifyExpressionValue(
			method = "extractBackground",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/inventory/EnchantmentMenu;getGoldCount()I"
			),
			require = 1,
			allow = 1
	)
	private int sharedfate$threeLapisOnButtons(int lapis) {
		return EnchantmentTableRules.effectiveLapis(lapis);
	}

	/** 툴팁의 청금석 줄 색(모자라면 빨강)도 같은 기준으로 맞춘다. 숫자는 툴팁 글월을 고칠 때 바꾼다. */
	@ModifyExpressionValue(
			method = "extractRenderState",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/inventory/EnchantmentMenu;getGoldCount()I"
			),
			require = 1,
			allow = 1
	)
	private int sharedfate$threeLapisInTooltip(int lapis) {
		return EnchantmentTableRules.effectiveLapis(lapis);
	}

	/**
	 * 단추 왼쪽의 청금석 개수 그림(1·2·3)을 세 칸 모두 「3」으로 그린다.
	 *
	 * <p>바닐라는 칸 번호로 그림 배열을 고른다. 배열을 세 번째 그림으로만 채운 것으로 바꿔 끼운다.
	 */
	@ModifyExpressionValue(
			method = "extractBackground",
			at = @At(
					value = "FIELD",
					target = "Lnet/minecraft/client/gui/screens/inventory/EnchantmentScreen;"
							+ "ENABLED_LEVEL_SPRITES:[Lnet/minecraft/resources/Identifier;",
					opcode = Opcodes.GETSTATIC
			),
			require = 1,
			allow = 1
	)
	private Identifier[] sharedfate$threeLapisSprites(Identifier[] sprites) {
		return sharedfate$lastSpriteOnly(sprites);
	}

	@ModifyExpressionValue(
			method = "extractBackground",
			at = @At(
					value = "FIELD",
					target = "Lnet/minecraft/client/gui/screens/inventory/EnchantmentScreen;"
							+ "DISABLED_LEVEL_SPRITES:[Lnet/minecraft/resources/Identifier;",
					opcode = Opcodes.GETSTATIC
			),
			require = 1,
			allow = 1
	)
	private Identifier[] sharedfate$threeLapisDisabledSprites(Identifier[] sprites) {
		return sharedfate$lastSpriteOnly(sprites);
	}

	@Unique
	private static Identifier[] sharedfate$lastSpriteOnly(Identifier[] sprites) {
		int index = EnchantmentTableRules.LAPIS_COST - 1;
		if (sprites.length <= index) {
			return sprites;
		}
		Identifier[] same = new Identifier[sprites.length];
		java.util.Arrays.fill(same, sprites[index]);
		return same;
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
