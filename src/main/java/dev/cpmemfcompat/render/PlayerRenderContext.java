package dev.cpmemfcompat.render;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderPlayerEvent;

/**
 * Everything needed to replay only CPM's public Pre/Post callbacks without
 * reposting the Forge event itself.
 */
public record PlayerRenderContext(
        Player player,
        PlayerRenderer renderer,
        float partialTick,
        PoseStack poseStack,
        MultiBufferSource buffers,
        int packedLight,
        EntityModel<?> model
) {
    public static PlayerRenderContext from(RenderPlayerEvent.Pre event) {
        return new PlayerRenderContext(
                event.getEntity(),
                event.getRenderer(),
                event.getPartialTick(),
                event.getPoseStack(),
                event.getMultiBufferSource(),
                event.getPackedLight(),
                event.getRenderer().getModel()
        );
    }

    public RenderPlayerEvent.Pre makePreEvent() {
        return new RenderPlayerEvent.Pre(
                player, renderer, partialTick, poseStack, buffers, packedLight
        );
    }

    public RenderPlayerEvent.Post makePostEvent() {
        return new RenderPlayerEvent.Post(
                player, renderer, partialTick, poseStack, buffers, packedLight
        );
    }
}
