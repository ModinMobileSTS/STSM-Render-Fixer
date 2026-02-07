package io.stsm.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Simple logger that prints to both System.out and Log4j if available.
 * Avoids lambdas/anonymous classes for D8/R8 friendliness.
 */
public final class SRFLog {
    private static final String TAG = "[SRF_DEBUG] ";
    private static final Logger LOG = init();

    private SRFLog() {}

    private static Logger init() {
        try {
            return LogManager.getLogger("STSM Render Fixer");
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void i(String msg) {
        String m = TAG + msg;
        try { System.out.println(m); } catch (Throwable ignored) {}
        if (LOG != null) {
            try { LOG.info(m); } catch (Throwable ignored) {}
        }
    }

    public static void w(String msg) {
        String m = TAG + msg;
        try { System.out.println(m); } catch (Throwable ignored) {}
        if (LOG != null) {
            try { LOG.warn(m); } catch (Throwable ignored) {}
        }
    }

    public static void e(String msg, Throwable t) {
        String m = TAG + msg;
        try { System.out.println(m); } catch (Throwable ignored) {}
        if (t != null) {
            try { t.printStackTrace(); } catch (Throwable ignored) {}
        }
        if (LOG != null) {
            try { LOG.error(m, t); } catch (Throwable ignored) {}
        }
    }
}
