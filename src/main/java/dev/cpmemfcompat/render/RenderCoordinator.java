package dev.cpmemfcompat.render;

import dev.cpmemfcompat.CpmEmfCompat;
import dev.cpmemfcompat.bridge.CpmBridge;
import dev.cpmemfcompat.bridge.EmfBridge;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderPlayerEvent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Makes EMF/Detailed Animations calculate the base player pose while CPM's
 * redirect parts are temporarily removed, then lets CPM copy that pose back in.
 */
public final class RenderCoordinator {
    private static final ThreadLocal<Deque<PlayerRenderContext>> PENDING =
            ThreadLocal.withInitial(ArrayDeque::new);

    private static final AtomicBoolean FIRST_BEGIN_LOG = new AtomicBoolean(false);
    private static final AtomicBoolean NO_ANIMATION_LOG = new AtomicBoolean(false);
    private static final AtomicBoolean MISSED_HOOK_WARNING = new AtomicBoolean(false);
    private static final AtomicBoolean SUSPEND_FAILURE_WARNING = new AtomicBoolean(false);
    private static final AtomicBoolean RESUME_FAILURE_WARNING = new AtomicBoolean(false);

    private RenderCoordinator() {
    }

    public static void begin(RenderPlayerEvent.Pre event) {
        if (event.isCanceled()) return;
        if (!CpmBridge.available() || !EmfBridge.available()) return;

        PlayerRenderContext context = PlayerRenderContext.from(event);

        // IMPORTANT: do not gate on isModelAnimatedByEMF() here. CPM has already
        // handled its NORMAL-priority Pre event and can make EMF's public check
        // return false while its redirect ModelParts are installed.
        boolean animatedWhileCpmBound = EmfBridge.isAnimated(context.model());

        try {
            CpmBridge.suspend(context);

            EmfBridge.Probe probe = EmfBridge.probe(context.player(), context.model());
            if (FIRST_BEGIN_LOG.compareAndSet(false, true)) {
                CpmEmfCompat.LOGGER.info(
                        "First player render compat probe: animatedWhileCpmBound={}, animatedAfterCpmSwapOut={}, " +
                        "rootHasAnimation={}, emfCurrentEntityMatches={}",
                        animatedWhileCpmBound, probe.animated(), probe.rootHasAnimation(), probe.currentEntityMatches()
                );
            }

            // After CPM is out, this is the meaningful test. If there is no EMF
            // animation attached to the restored model, immediately put CPM back
            // and leave the render untouched.
            if (!probe.usable()) {
                if (NO_ANIMATION_LOG.compareAndSet(false, true)) {
                    CpmEmfCompat.LOGGER.warn(
                            "Restored player model has no usable EMF animation root; compat skipped for this render. Probe={}",
                            probe
                    );
                }
                CpmBridge.resume(context);
                return;
            }

            PENDING.get().push(context);
        } catch (Throwable t) {
            if (SUSPEND_FAILURE_WARNING.compareAndSet(false, true)) {
                CpmEmfCompat.LOGGER.error(
                        "Failed to temporarily unbind CPM. Further identical errors are suppressed.", t
                );
            }
            // Best-effort restoration if suspend succeeded before a later probe failed.
            try {
                CpmBridge.resume(context);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Called by the LivingEntityRenderer mixin after vanilla setupAnim. */
    public static void resumeIfPending(LivingEntity entity, String hookName) {
        if (!(entity instanceof Player player)) return;

        Deque<PlayerRenderContext> stack = PENDING.get();
        PlayerRenderContext context = stack.peek();
        if (context == null || context.player() != player) return;

        stack.pop();
        if (stack.isEmpty()) PENDING.remove();

        EmfBridge.ApplyResult applyResult = EmfBridge.ApplyResult.FAILED;
        try {
            applyResult = EmfBridge.applyAnimation(player, context.model(), context.poseStack());
            CpmEmfCompat.LOGGER.trace(
                    "Resuming CPM after {} for {} (EMF transfer: {})",
                    hookName, player.getGameProfile().getName(), applyResult
            );
        } finally {
            try {
                CpmBridge.resume(context);
            } catch (Throwable t) {
                if (RESUME_FAILURE_WARNING.compareAndSet(false, true)) {
                    CpmEmfCompat.LOGGER.error(
                            "Failed to rebind CPM after EMF animation (result=" + applyResult + "). " +
                            "Further identical errors are suppressed.", t
                    );
                }
            }
        }
    }

    public static void finish(RenderPlayerEvent.Post event) {
        Deque<PlayerRenderContext> stack = PENDING.get();
        if (stack.isEmpty()) return;

        Player player = event.getEntity();
        PlayerRenderContext context = stack.peek();
        if (context != null && context.player() == player) {
            stack.pop();
            if (stack.isEmpty()) PENDING.remove();

            if (MISSED_HOOK_WARNING.compareAndSet(false, true)) {
                CpmEmfCompat.LOGGER.error(
                        "Neither post-setupAnim nor pre-render fallback hook fired for the player model. " +
                        "This frame was rendered without the CPM rebind. Check other renderer/core mods."
                );
            }
        }
    }
}
