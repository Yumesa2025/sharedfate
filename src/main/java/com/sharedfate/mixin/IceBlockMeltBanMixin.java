package com.sharedfate.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.sharedfate.sync.TrialDryWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 「메마른 세계」({@link TrialDryWorld})가 <b>얼음</b>으로 새는 것을 막는다 — 금지 중 엔드에서는
 * 얼음이 물 대신 <b>공기</b>가 된다.
 *
 * <h2>26.3 바이트코드에서 읽은 길 둘</h2>
 *
 * <ul>
 *   <li>{@code IceBlock.playerDestroy} — 도구에 {@code PREVENTS_ICE_MELTING}(섬세한 손길)이 없고
 *       {@code WATER_EVAPORATES} 가 거짓이면(엔드는 이 속성이 없다), 밑 블록이
 *       {@code #ice_melts_when_destroyed_above}(= {@code #blocks_motion}) 이거나 액체일 때
 *       {@code ServerLevel.setBlockAndUpdate(pos, meltsInto())} 로 물을 놓는다</li>
 *   <li>{@code IceBlock.melt} — {@code randomTick} 이 블록 빛 &gt; 11 − 빛 감쇠일 때 부른다.
 *       같은 속성을 보고 {@code Level.setBlockAndUpdate(pos, meltsInto())} 로 물을 놓는다.
 *       {@code FrostedIceBlock} 도 {@code super} 의 이 메서드로 녹는다</li>
 * </ul>
 *
 * <p>{@code meltsInto()} 는 정적이고 월드를 모르므로 거기 걸지 않고, 월드가 손에 있는
 * {@code setBlockAndUpdate} 호출을 감싼다. 두 메서드 모두 그 호출이 <b>하나뿐</b>이라
 * {@code ordinal} 을 적지 않는다.
 *
 * <p>⚠ <b>재정의 확인.</b> 26.3 에서 {@code IceBlock} 을 물려받는 것은 {@code FrostedIceBlock}
 * 하나이고, 그쪽은 {@code playerDestroy} 도 {@code melt} 도 재정의하지 않는다({@code javap}).
 * 꽁꽁 언 얼음·푸른 얼음은 {@code IceBlock} 이 아니라 녹지 않는다.
 *
 * <p>공기로 바꿔도 잃는 것은 없다 — {@code playerDestroy} 쪽은 얼음이 이미 지워진 뒤라
 * 공기 위에 공기를 놓는 셈이고, {@code melt} 쪽은 얼음이 사라진다(녹는 것 그대로). 막는 판단은
 * {@link TrialDryWorld#banMelting} 한 곳이다.
 */
@Mixin(IceBlock.class)
public abstract class IceBlockMeltBanMixin {

	/** 부순 얼음이 물이 되는 자리. */
	@WrapOperation(
			method = "playerDestroy",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/server/level/ServerLevel;setBlockAndUpdate("
							+ "Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean sharedfate$dryWorldBreaksIceDry(ServerLevel level, BlockPos pos,
			BlockState melted, Operation<Boolean> original) {
		return original.call(level, pos, sharedfate$dried(level, melted));
	}

	/** 빛에 녹은 얼음이 물이 되는 자리. */
	@WrapOperation(
			method = "melt",
			at = @At(
					value = "INVOKE",
					target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate("
							+ "Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)Z"))
	private boolean sharedfate$dryWorldMeltsIceDry(Level level, BlockPos pos, BlockState melted,
			Operation<Boolean> original) {
		return original.call(level, pos, sharedfate$dried(level, melted));
	}

	/** 금지 중 엔드면 공기, 아니면 바닐라가 고른 그대로. */
	private static BlockState sharedfate$dried(Level level, BlockState melted) {
		return TrialDryWorld.banMelting(level) ? Blocks.AIR.defaultBlockState() : melted;
	}
}
