package dev.enderring.restorer.mixin;

import net.minecraft.world.level.levelgen.densityfunction.DistanceMetric;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Restores the End "void rings" bug (MC-159283) on Minecraft 26.3.
 *
 * <p>Until 1.14.4/19w36a the main island density was computed in {@code EndIslandFunction} as
 * {@code 100 - Mth.sqrt(x * x + z * z) * 8}, where {@code x}/{@code z} are the section
 * coordinates ({@code blockX / 8}). That expression uses 32-bit int arithmetic, so once
 * {@code x * x + z * z} overflows past {@code 2^31 - 1} it wraps to a negative value and the
 * square root yields {@code NaN}. Because the overflow repeats every {@code 2^32}, the void
 * forms concentric rings whose first edge sits at radius {@code sqrt(2^31) * 8 ≈ 370,720}
 * blocks from the origin.
 *
 * <p>26.3 removed that code: the main island is now the data-driven
 * {@code minecraft:end/islands} function, whose first term is
 * {@code 100 - distance_to_point([0,0,0], euclidean)}. The distance is evaluated in float
 * (via {@code Mth.length}), so it can never overflow and the rings disappeared. This mixin
 * re-evaluates the original integer expression inside that term and restores the {@code NaN}
 * it produced.
 *
 * <p>The term is the left operand of the {@code max(main island, end_outer_islands)} in
 * {@code end/islands.json}. Both sampling paths therefore keep the {@code NaN}: the scalar
 * path uses {@code Math.max} (NaN-propagating), and the bulk path writes the left buffer
 * first and only overwrites it when {@code right > left}, which is false for NaN.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.densityfunction.generator.DistanceToPointFunction$Sampler")
public class DistanceToPointFunctionSamplerMixin {
	@Shadow
	@Final
	private int x;

	@Shadow
	@Final
	private int y;

	@Shadow
	@Final
	private int z;

	@Shadow
	@Final
	private DistanceMetric metric;

	@Inject(method = "sampleValue", at = @At("HEAD"), cancellable = true)
	private void enderRingRestorer$restoreMainIslandOverflow(SamplerContext context, int blockX, int blockY, int blockZ, CallbackInfoReturnable<Float> cir) {
		if (this.metric != DistanceMetric.EUCLIDEAN || this.x != 0 || this.y != 0 || this.z != 0) {
			return;
		}

		int sectionX = blockX / 8;
		int sectionZ = blockZ / 8;

		if (sectionX * sectionX + sectionZ * sectionZ < 0) {
			cir.setReturnValue(Float.NaN);
		}
	}
}
