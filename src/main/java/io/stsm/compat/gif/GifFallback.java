package io.stsm.compat.gif;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

/**
 * A tiny always-available placeholder texture.
 *
 * Some mods assume {@code utils.GifHelper#getGif(String)} never returns null.
 * When decoding fails (most commonly due to OOM), returning this avoids crashing.
 */
public final class GifFallback {
    private static volatile Texture fallback;

    private GifFallback() {
    }

    public static Texture get() {
        Texture t = fallback;
        if (t != null) {
            return t;
        }
        synchronized (GifFallback.class) {
            t = fallback;
            if (t != null) {
                return t;
            }
            try {
                Pixmap pm = new Pixmap(2, 2, Pixmap.Format.RGBA8888);
                pm.setColor(0f, 0f, 0f, 0f);
                pm.fill();
                t = new Texture(pm);
                pm.dispose();
            } catch (Throwable ignored) {
                t = null;
            }
            fallback = t;
            return t;
        }
    }
}
