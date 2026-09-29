package dev.cpmemfcompat.bridge;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.cpmemfcompat.CpmEmfCompat;
import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reflection bridge to Entity Model Features.
 *
 * This version targets the API shape used by EMF 3.0.17 / API v8 on 1.20.1.
 * API v8 does NOT expose animateModelForEntity(...). The legacy model root does,
 * however, expose triggerManualAnimation(PoseStack). That is the same root
 * animation runnable EMF normally executes when its ModelParts are rendered.
 */
public final class EmfBridge {
    private static final String EMF_API = "traben.entity_model_features.EMFAnimationApi";

    private static volatile boolean initAttempted;
    private static volatile boolean available;

    private static Method isModelAnimated;
    private static Method isModelCustomized;
    private static Method emfEntityOf;
    private static Method apiVersion;
    private static Method getCurrentEntity;

    private static final AtomicBoolean FIRST_PROBE_LOG = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_APPLY_LOG = new AtomicBoolean(false);
    private static final AtomicBoolean FIRST_FAILURE_LOG = new AtomicBoolean(false);

    private EmfBridge() {
    }

    public enum ApplyResult {
        LEGACY_TRIGGER,
        NO_EMF_ROOT,
        NO_EMF_ANIMATION,
        CONTEXT_MISMATCH,
        TRIGGER_MISSING,
        FAILED
    }

    public record Probe(
            boolean customized,
            boolean animated,
            boolean hasRoot,
            boolean rootHasAnimation,
            boolean currentEntityMatches,
            String rootClass,
            String currentEntityClass
    ) {
        public boolean usable() {
            return hasRoot && rootHasAnimation;
        }
    }

    public static boolean available() {
        ensureInitialized();
        return available;
    }

    public static boolean isAnimated(EntityModel<?> model) {
        ensureInitialized();
        if (!available || isModelAnimated == null) return false;
        try {
            return Boolean.TRUE.equals(isModelAnimated.invoke(null, model));
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Probe after CPM has been temporarily swapped out. This is deliberately
     * separate from the pre-swap check: while CPM redirect parts are installed,
     * EMF's public "is animated" test can report false even though the restored
     * player ModelPart tree has Detailed Animations attached to it.
     */
    public static Probe probe(Entity entity, EntityModel<?> model) {
        ensureInitialized();
        boolean customized = false;
        boolean animated = false;
        boolean rootHasAnimation = false;
        Object root = null;
        Object current = null;

        try {
            if (isModelCustomized != null) {
                customized = Boolean.TRUE.equals(isModelCustomized.invoke(null, model));
            }
        } catch (Throwable ignored) {
        }
        try {
            if (isModelAnimated != null) {
                animated = Boolean.TRUE.equals(isModelAnimated.invoke(null, model));
            }
        } catch (Throwable ignored) {
        }
        try {
            root = findRoot(model);
            if (root != null) rootHasAnimation = rootHasAnimation(root);
        } catch (Throwable ignored) {
        }
        try {
            current = currentEntity();
        } catch (Throwable ignored) {
        }

        Object expected = adaptEntity(entity);
        boolean matches = current != null && (current == entity || current == expected);

        Probe probe = new Probe(
                customized,
                animated,
                root != null,
                rootHasAnimation,
                matches,
                root == null ? "<none>" : root.getClass().getName(),
                current == null ? "<none>" : current.getClass().getName()
        );

        if (FIRST_PROBE_LOG.compareAndSet(false, true)) {
            CpmEmfCompat.LOGGER.info(
                    "First post-CPM-swap EMF probe: customized={}, animated={}, hasRoot={}, rootHasAnimation={}, " +
                    "currentEntityMatches={}, rootClass={}, currentEntityClass={}",
                    probe.customized(), probe.animated(), probe.hasRoot(), probe.rootHasAnimation(),
                    probe.currentEntityMatches(), probe.rootClass(), probe.currentEntityClass()
            );
        }
        return probe;
    }

    /**
     * Execute the actual CEM animation on the restored EMF ModelPart tree.
     * In EMF 3.0.17 EMFModelPartRoot#triggerManualAnimation(PoseStack) calls
     * animationHolder.run(), which evaluates the JEM animation expressions and
     * writes the final transforms to the vanilla/EMF parts.
     */
    public static ApplyResult applyAnimation(Entity entity, EntityModel<?> model, PoseStack poseStack) {
        ensureInitialized();
        if (!available) return ApplyResult.FAILED;

        Object root;
        try {
            root = findRoot(model);
        } catch (Throwable t) {
            logFirstFailure("Failed to obtain EMF root model.", t);
            return ApplyResult.NO_EMF_ROOT;
        }
        if (root == null) return ApplyResult.NO_EMF_ROOT;

        try {
            if (!rootHasAnimation(root)) return ApplyResult.NO_EMF_ANIMATION;
        } catch (Throwable t) {
            logFirstFailure("Could not query EMF root animation state.", t);
            return ApplyResult.FAILED;
        }

        // EMF installs the current entity at EntityRenderDispatcher.render HEAD
        // on 1.20.1. Do not evaluate a player's animation using another entity's
        // stale context; that would make animation variables nonsensical.
        Object current = null;
        try {
            current = currentEntity();
        } catch (Throwable ignored) {
        }
        Object expected = adaptEntity(entity);
        if (current != null && current != entity && current != expected) {
            if (FIRST_FAILURE_LOG.compareAndSet(false, true)) {
                CpmEmfCompat.LOGGER.warn(
                        "EMF current entity does not match rendered player: current={}, player={}. " +
                        "Skipping manual animation for this frame.",
                        current.getClass().getName(), entity.getClass().getName()
                );
            }
            return ApplyResult.CONTEXT_MISMATCH;
        }

        Method trigger = findMethod(root.getClass(), "triggerManualAnimation", method -> {
            Class<?>[] p = method.getParameterTypes();
            return p.length == 1 && PoseStack.class.isAssignableFrom(p[0]);
        });
        if (trigger == null) return ApplyResult.TRIGGER_MISSING;

        String beforePose = describePose(model);
        try {
            trigger.invoke(root, poseStack);
            String afterPose = describePose(model);

            if (FIRST_APPLY_LOG.compareAndSet(false, true)) {
                CpmEmfCompat.LOGGER.info(
                        "First EMF 3.0.x manual animation transfer succeeded: trigger={}, currentEntity={}, poseChanged={}, before=[{}], after=[{}]",
                        signature(trigger),
                        current == null ? "<none>" : current.getClass().getName(),
                        !beforePose.equals(afterPose),
                        beforePose,
                        afterPose
                );
            }
            return ApplyResult.LEGACY_TRIGGER;
        } catch (Throwable t) {
            logFirstFailure("EMF triggerManualAnimation(PoseStack) failed.", unwrap(t));
            return ApplyResult.FAILED;
        }
    }

    private static boolean rootHasAnimation(Object root) throws Throwable {
        Method hasAnimation = findZeroArgMethod(root.getClass(), "hasAnimation");
        return hasAnimation != null && Boolean.TRUE.equals(hasAnimation.invoke(root));
    }

    private static Object currentEntity() throws Throwable {
        if (getCurrentEntity == null) return null;
        return getCurrentEntity.invoke(null);
    }

    private static Object adaptEntity(Entity entity) {
        if (emfEntityOf != null) {
            try {
                Object converted = emfEntityOf.invoke(null, entity);
                if (converted != null) return converted;
            } catch (Throwable ignored) {
            }
        }
        return entity;
    }

    private static Object findRoot(EntityModel<?> model) throws Throwable {
        Method getter = findMethodRecursive(model.getClass(), "emf$getEMFRootModel", m -> m.getParameterCount() == 0);
        if (getter == null) return null;
        return getter.invoke(model);
    }

    private static String describePose(EntityModel<?> model) {
        if (!(model instanceof HumanoidModel<?> humanoid)) return model.getClass().getSimpleName();
        return "head=" + part(humanoid.head)
                + ";body=" + part(humanoid.body)
                + ";la=" + part(humanoid.leftArm)
                + ";ra=" + part(humanoid.rightArm)
                + ";ll=" + part(humanoid.leftLeg)
                + ";rl=" + part(humanoid.rightLeg);
    }

    private static String part(ModelPart p) {
        return String.format(java.util.Locale.ROOT, "(%.4f,%.4f,%.4f|%.4f,%.4f,%.4f)",
                p.x, p.y, p.z, p.xRot, p.yRot, p.zRot);
    }

    private static synchronized void ensureInitialized() {
        if (initAttempted) return;
        initAttempted = true;

        try {
            Class<?> apiClass = Class.forName(EMF_API);

            isModelAnimated = findMethod(apiClass, "isModelAnimatedByEMF", m -> {
                Class<?>[] p = m.getParameterTypes();
                return p.length == 1 && EntityModel.class.isAssignableFrom(p[0]);
            });
            isModelCustomized = findMethod(apiClass, "isModelCustomizedByEMF", m -> {
                Class<?>[] p = m.getParameterTypes();
                return p.length == 1 && EntityModel.class.isAssignableFrom(p[0]);
            });
            emfEntityOf = findMethod(apiClass, "emfEntityOf", m -> {
                Class<?>[] p = m.getParameterTypes();
                return p.length == 1 && p[0].isAssignableFrom(Entity.class);
            });
            apiVersion = findZeroArgMethod(apiClass, "getApiVersion");
            getCurrentEntity = findZeroArgMethod(apiClass, "getCurrentEntity");

            available = isModelAnimated != null || isModelCustomized != null;

            Object version = null;
            if (apiVersion != null) {
                try {
                    version = apiVersion.invoke(null);
                } catch (Throwable ignored) {
                }
            }

            CpmEmfCompat.LOGGER.info(
                    "Connected to EMF legacy animation bridge: apiVersion={}, isAnimated={}, isCustomized={}, emfEntityOf={}, currentEntity={}",
                    version == null ? "unknown" : version,
                    signature(isModelAnimated),
                    signature(isModelCustomized),
                    signature(emfEntityOf),
                    signature(getCurrentEntity)
            );
        } catch (Throwable t) {
            available = false;
            CpmEmfCompat.LOGGER.error(
                    "Could not connect to EMFAnimationApi. Compat will stay disabled.", t
            );
        }
    }

    private interface MethodPredicate {
        boolean test(Method method);
    }

    private static Method findMethod(Class<?> type, String name, MethodPredicate predicate) {
        if (type == null) return null;
        for (Method method : type.getDeclaredMethods()) {
            if (!method.getName().equals(name)) continue;
            if (!predicate.test(method)) continue;
            makeAccessible(method);
            return method;
        }
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name)) continue;
            if (!predicate.test(method)) continue;
            makeAccessible(method);
            return method;
        }
        return null;
    }

    private static Method findMethodRecursive(Class<?> type, String name, MethodPredicate predicate) {
        if (type == null) return null;
        Method direct = findMethod(type, name, predicate);
        if (direct != null) return direct;
        for (Class<?> itf : type.getInterfaces()) {
            Method viaInterface = findMethodRecursive(itf, name, predicate);
            if (viaInterface != null) return viaInterface;
        }
        return findMethodRecursive(type.getSuperclass(), name, predicate);
    }

    private static Method findZeroArgMethod(Class<?> type, String name) {
        return findMethod(type, name, m -> m.getParameterCount() == 0);
    }

    private static void makeAccessible(Method method) {
        try {
            method.setAccessible(true);
        } catch (Throwable ignored) {
        }
    }

    private static String signature(Method method) {
        if (method == null) return "<missing>";
        return method.getDeclaringClass().getName() + "#" + method.getName()
                + Arrays.toString(method.getParameterTypes());
    }

    private static void logFirstFailure(String message, Throwable t) {
        if (FIRST_FAILURE_LOG.compareAndSet(false, true)) {
            CpmEmfCompat.LOGGER.error(message + " Further identical errors are suppressed.", t);
        }
    }

    private static Throwable unwrap(Throwable t) {
        if (t instanceof InvocationTargetException ite && ite.getCause() != null) {
            return ite.getCause();
        }
        return t;
    }
}
