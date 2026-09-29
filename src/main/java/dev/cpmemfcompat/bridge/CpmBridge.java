package dev.cpmemfcompat.bridge;

import dev.cpmemfcompat.CpmEmfCompat;
import dev.cpmemfcompat.render.PlayerRenderContext;
import net.minecraftforge.client.event.RenderPlayerEvent;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Talks to CPM only through the public Forge event handler methods that CPM
 * already exposes. Reflection deliberately avoids a hard compile dependency
 * on a particular CPM jar.
 */
public final class CpmBridge {
    private static final String CPM_CLIENT = "com.tom.cpm.client.CustomPlayerModelsClient";

    private static volatile boolean initAttempted;
    private static volatile boolean available;
    private static Object instance;
    private static Method playerRenderPre;
    private static Method playerRenderPost;

    private CpmBridge() {
    }

    public static boolean available() {
        ensureInitialized();
        return available;
    }

    /**
     * CPM has already handled the real RenderPlayerEvent.Pre at this point.
     * Calling its public Post handler swaps its redirect parts back out so
     * vanilla + EMF can animate the real ModelPart tree.
     */
    public static void suspend(PlayerRenderContext context) throws Throwable {
        ensureAvailable();
        invoke(playerRenderPost, context.makePostEvent());
    }

    /**
     * Replay only CPM's own public Pre handler after EMF has produced the pose.
     * This swaps CPM redirect parts back in and makes that pose CPM's base pose.
     */
    public static void resume(PlayerRenderContext context) throws Throwable {
        ensureAvailable();
        invoke(playerRenderPre, context.makePreEvent());
    }

    private static void ensureAvailable() {
        ensureInitialized();
        if (!available) {
            throw new IllegalStateException("CPM client bridge is unavailable");
        }
    }

    private static synchronized void ensureInitialized() {
        if (initAttempted) return;
        initAttempted = true;

        try {
            Class<?> clientClass = Class.forName(CPM_CLIENT);
            Field instanceField = clientClass.getField("INSTANCE");
            instance = instanceField.get(null);

            playerRenderPre = clientClass.getMethod("playerRenderPre", RenderPlayerEvent.Pre.class);
            playerRenderPost = clientClass.getMethod("playerRenderPost", RenderPlayerEvent.Post.class);

            available = true;
            CpmEmfCompat.LOGGER.info("Connected to CPM public render callbacks: {}", clientClass.getName());
        } catch (Throwable t) {
            available = false;
            CpmEmfCompat.LOGGER.error(
                    "Could not connect to CPM's public render callbacks. Compat will stay disabled.", t
            );
        }
    }

    private static void invoke(Method method, Object event) throws Throwable {
        try {
            method.invoke(instance, event);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            throw cause != null ? cause : e;
        }
    }
}
