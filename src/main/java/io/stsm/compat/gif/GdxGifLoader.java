package io.stsm.compat.gif;

import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Android-safe GIF loader for libGDX.
 *
 * - Decodes GIF frames with {@link GifDecoder} without allocating a full-canvas int[] per frame.
 * - Optionally downscales large GIFs.
 * - Provides a small cache trim helper to avoid unbounded Texture growth on memory-limited devices.
 */
public final class GdxGifLoader {
    private GdxGifLoader() {}

    /**
     * Decode GIF into textures.
     *
     * @return Object[]{ ArrayList&lt;Texture&gt; textures, int[] delaysMs }
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Object[] load(InputStream in, final int maxFrames, final int maxDim) throws Exception {
        try {
            System.out.println("[SRF_GIF] load start: maxFrames=" + maxFrames + " maxDim=" + maxDim);
        } catch (Throwable ignored) {}

        final ArrayList<Texture> textures = new ArrayList<Texture>();
        final ArrayList<Integer> delays = new ArrayList<Integer>();

        final int[] srcWH = new int[] { -1, -1 };

        boolean ok = GifDecoder.decode(in, new GifDecoder.FrameCallback() {
            @Override
            public void onFrame(int w, int h, int[] canvasArgb, int delayMs, int frameIndex) {
                srcWH[0] = w;
                srcWH[1] = h;
                try {
                    System.out.println("[SRF_GIF] onFrame cb: idx=" + frameIndex + " delayMs=" + delayMs + " canvas=" + w + "x" + h);
                } catch (Throwable ignored) {}

                // Decide target size (downscale only).
                int tw = w;
                int th = h;
                if (maxDim > 0 && (w > maxDim || h > maxDim)) {
                    float s = Math.min((float) maxDim / (float) w, (float) maxDim / (float) h);
                    tw = Math.max(1, (int) (w * s));
                    th = Math.max(1, (int) (h * s));
                }

                Pixmap pm = new Pixmap(tw, th, Pixmap.Format.RGBA8888);
                ByteBuffer pb = pm.getPixels();
                pb.clear();

                // Nearest-neighbor sampling from full canvas.
                for (int y = 0; y < th; y++) {
                    int sy = (int) ((long) y * (long) h / (long) th);
                    int srcRow = sy * w;
                    for (int x = 0; x < tw; x++) {
                        int sx = (int) ((long) x * (long) w / (long) tw);
                        int c = canvasArgb[srcRow + sx];
                        int a = (c >>> 24) & 255;
                        int r = (c >>> 16) & 255;
                        int g = (c >>> 8) & 255;
                        int b = (c) & 255;
                        pb.put((byte) r);
                        pb.put((byte) g);
                        pb.put((byte) b);
                        pb.put((byte) a);
                    }
                }

                pb.flip();

                Texture tx = new Texture(pm);
                textures.add(tx);
                delays.add(Integer.valueOf(Math.max(GifDecoder.MIN_DELAY_MS, delayMs)));
                pm.dispose();
            }
        }, maxFrames);

        if (!ok || textures.isEmpty()) {
            try {
                System.out.println("[SRF_GIF] load failed: ok=" + ok + " frames=" + textures.size());
            } catch (Throwable ignored) {}
            return null;
        }

        int[] delayArr = new int[delays.size()];
        for (int i = 0; i < delays.size(); i++) {
            delayArr[i] = delays.get(i).intValue();
        }

        try {
            System.out.println("[SRF_GIF] load ok: frames=" + textures.size() + " delays=" + delayArr.length
                    + " src=" + srcWH[0] + "x" + srcWH[1]
                    + " maxDim=" + maxDim);
        } catch (Throwable ignored) {}
        return new Object[] { textures, delayArr };
    }

    /**
     * Trim GIF caches (maps from key -> textures/delays/startTime) to a max entry count.
     * Disposes evicted textures.
     */
    @SuppressWarnings({"rawtypes"})
    public static void trimCache(Map gifTextures, Map gifDelays, Map startTimes, String keepKey, int maxEntries) {
        if (gifTextures == null || maxEntries <= 0) return;
        try {
            if (gifTextures.size() <= maxEntries) return;

            Iterator it = gifTextures.keySet().iterator();
            while (gifTextures.size() > maxEntries && it.hasNext()) {
                Object k = it.next();
                if (k == null) continue;
                if (keepKey != null && keepKey.equals(k)) continue;

                Object v = gifTextures.get(k);
                if (v instanceof List) {
                    List list = (List) v;
                    for (int i = 0; i < list.size(); i++) {
                        Object t = list.get(i);
                        if (t instanceof Texture) {
                            try { ((Texture) t).dispose(); } catch (Throwable ignored) {}
                        }
                    }
                }

                it.remove();
                if (gifDelays != null) {
                    try { gifDelays.remove(k); } catch (Throwable ignored) {}
                }
                if (startTimes != null) {
                    try { startTimes.remove(k); } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
