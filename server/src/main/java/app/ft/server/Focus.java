package app.ft.server;

import android.content.ComponentName;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * What's actually focused right now, car screen or not - read straight from
 * ActivityTaskManager's own service instead of shelling out to "dumpsys activity
 * activities" and grepping the text. The field dumpsys prints under ("ResumedActivity:"
 * vs "mResumedActivity") already turned out to differ across Android builds; reading the
 * structured ComponentName directly sidesteps that whole class of drift, and skips the
 * process fork a shell command needs.
 */
public final class Focus {
    private static Object service;
    private static Method focusedTask;
    private static boolean looked;

    private Focus() {
    }

    private static synchronized boolean ready() {
        if (looked) {
            return service != null && focusedTask != null;
        }
        looked = true;
        try {
            Class<?> atm = Class.forName("android.app.ActivityTaskManager");
            Method getService = atm.getMethod("getService");
            getService.setAccessible(true);
            service = getService.invoke(null);
        } catch (Throwable t) {
            Log.w("no ActivityTaskManager service: " + t);
            return false;
        }
        if (service == null) {
            return false;
        }
        for (Method m : service.getClass().getMethods()) {
            String name = m.getName().toLowerCase();
            if (m.getParameterTypes().length == 0 && name.contains("focused")
                && (name.contains("task") || name.contains("stack"))) {
                m.setAccessible(true);
                focusedTask = m;
                break;
            }
        }
        if (focusedTask == null) {
            Log.w("no focused-task query on " + service.getClass().getName());
        }
        return focusedTask != null;
    }

    /** The component actually focused right now - null if it could not be read. */
    public static ComponentName topActivity() {
        if (!ready()) {
            return null;
        }
        try {
            Object info = focusedTask.invoke(service);
            if (info == null) {
                return null;
            }
            Field field = info.getClass().getField("topActivity");
            return (ComponentName) field.get(info);
        } catch (Throwable t) {
            Log.w("could not read the focused task: " + t);
            return null;
        }
    }
}
