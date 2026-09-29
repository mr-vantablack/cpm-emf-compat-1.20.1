package dev.cpmemfcompat.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cpmemfcompat.render.RenderCoordinator;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The primary hook runs immediately after vanilla EntityModel.setupAnim.
 * A pre-render fallback is included because renderer/core mods occasionally
 * redirect or wrap setupAnim in ways that make a single injection point brittle.
 */
@Mixin(value = LivingEntityRenderer.class, priority = 500)
public abstract class LivingEntityRendererMixin {
    private static final String RENDER =
            "render(Lnet/minecraft/world/entity/LivingEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;" +
            "Lnet/minecraft/client/renderer/MultiBufferSource;I)V";

    @Inject(
            method = RENDER,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;setupAnim" +
                            "(Lnet/minecraft/world/entity/Entity;FFFFF)V",
                    shift = At.Shift.AFTER
            ),
            require = 0
    )
    private void cpmemf$afterSetupAnim(
            LivingEntity entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            CallbackInfo ci
    ) {
        RenderCoordinator.resumeIfPending(entity, "setupAnim");
    }

    /**
     * Fallback for unusual renderer transformations. In 1.20.1 renderToBuffer is
     * declared on Model, while calls may be emitted against Model or EntityModel
     * depending on transformed bytecode. Both owner forms are covered.
     */
    @Inject(
            method = RENDER,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/Model;renderToBuffer" +
                            "(Lcom/mojang/blaze3d/vertex/PoseStack;" +
                            "Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
                    shift = At.Shift.BEFORE
            ),
            require = 0
    )
    private void cpmemf$beforeRenderModelViaModelOwner(
            LivingEntity entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            CallbackInfo ci
    ) {
        RenderCoordinator.resumeIfPending(entity, "renderToBuffer(Model fallback)");
    }

    @Inject(
            method = RENDER,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/model/EntityModel;renderToBuffer" +
                            "(Lcom/mojang/blaze3d/vertex/PoseStack;" +
                            "Lcom/mojang/blaze3d/vertex/VertexConsumer;IIFFFF)V",
                    shift = At.Shift.BEFORE
            ),
            require = 0
    )
    private void cpmemf$beforeRenderModelViaEntityModelOwner(
            LivingEntity entity,
            float entityYaw,
            float partialTick,
            PoseStack poseStack,
            MultiBufferSource buffers,
            int packedLight,
            CallbackInfo ci
    ) {
        RenderCoordinator.resumeIfPending(entity, "renderToBuffer(EntityModel fallback)");
    }
}
