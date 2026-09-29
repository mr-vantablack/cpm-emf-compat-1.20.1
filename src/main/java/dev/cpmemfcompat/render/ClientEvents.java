package dev.cpmemfcompat.render;

import dev.cpmemfcompat.CpmEmfCompat;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(
        modid = CpmEmfCompat.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT
)
public final class ClientEvents {
    private ClientEvents() {
    }

    /**
     * CPM's normal playerRenderPre has NORMAL priority, so LOWEST runs after CPM
     * has swapped its redirect ModelParts into the PlayerModel.
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerRenderPre(RenderPlayerEvent.Pre event) {
        RenderCoordinator.begin(event);
    }

    /** Runs after CPM's normal Post callback and only cleans stale state. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onPlayerRenderPost(RenderPlayerEvent.Post event) {
        RenderCoordinator.finish(event);
    }
}
