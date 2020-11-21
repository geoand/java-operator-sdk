package io.javaoperatorsdk.operator;

import java.util.HashMap;
import java.util.Map;

import io.fabric8.kubernetes.api.builder.Function;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.client.CustomResourceDoneable;
import io.javaoperatorsdk.operator.api.Controller;
import io.javaoperatorsdk.operator.api.ResourceController;
import javassist.CannotCompileException;
import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtConstructor;
import javassist.CtNewConstructor;
import javassist.LoaderClassPath;
import javassist.NotFoundException;


public class ControllerUtils {

    private final static double JAVA_VERSION = Double.parseDouble(System.getProperty("java.specification.version"));
    private static final String FINALIZER_NAME_SUFFIX = "/finalizer";

    // this is just to support testing, this way we don't try to create class multiple times in memory with same name.
    // note that other solution is to add a random string to doneable class name
    private static Map<Class<? extends CustomResource>, Class<? extends CustomResourceDoneable<? extends CustomResource>>>
            doneableClassCache = new HashMap<>();

    static String getFinalizer(ResourceController controller) {
        final String annotationFinalizerName = getAnnotation(controller).finalizerName();
        if (!Controller.NULL.equals(annotationFinalizerName)) {
            return annotationFinalizerName;
        }
        return controller.getClass().getCanonicalName() + FINALIZER_NAME_SUFFIX;
    }

    static boolean getGenerationEventProcessing(ResourceController controller) {
        return getAnnotation(controller).generationAwareEventProcessing();
    }

    static <R extends CustomResource> Class<R> getCustomResourceClass(ResourceController<R> controller) {
        return (Class<R>) getAnnotation(controller).customResourceClass();
    }

    static String getCrdName(ResourceController controller) {
        return getAnnotation(controller).crdName();
    }


    public static <T extends CustomResource> Class<? extends CustomResourceDoneable<T>>
    getCustomResourceDoneableClass(ResourceController<T> controller) {
        try {
            Class<? extends CustomResource> customResourceClass = getAnnotation(controller).customResourceClass();
            String className = customResourceClass.getPackage().getName() + "." + customResourceClass.getSimpleName() + "CustomResourceDoneable";

            if (doneableClassCache.containsKey(customResourceClass)) {
                return (Class<? extends CustomResourceDoneable<T>>) doneableClassCache.get(customResourceClass);
            }

            ClassPool pool = ClassPool.getDefault();
            pool.appendClassPath(new LoaderClassPath(Thread.currentThread().getContextClassLoader()));

            CtClass superClass = pool.get(CustomResourceDoneable.class.getName());
            CtClass function = pool.get(Function.class.getName());
            CtClass customResource = pool.get(customResourceClass.getName());
            CtClass[] argTypes = {customResource, function};
            CtClass customDoneable = pool.makeClass(className, superClass);
            CtConstructor ctConstructor = CtNewConstructor.make(argTypes, null, "super($1, $2);", customDoneable);
            customDoneable.addConstructor(ctConstructor);

            Class<? extends CustomResourceDoneable<T>> doneableClass;
            if (JAVA_VERSION >= 9) {
                doneableClass = (Class<? extends CustomResourceDoneable<T>>) customDoneable.toClass(customResourceClass);
            } else {
                doneableClass = (Class<? extends CustomResourceDoneable<T>>) customDoneable.toClass();
            }
            doneableClassCache.put(customResourceClass, doneableClass);
            return doneableClass;
        } catch (CannotCompileException | NotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Controller getAnnotation(ResourceController controller) {
        return controller.getClass().getAnnotation(Controller.class);
    }

    public static boolean hasGivenFinalizer(CustomResource resource, String finalizer) {
        return resource.getMetadata().getFinalizers() != null && resource.getMetadata().getFinalizers().contains(finalizer);
    }
}
