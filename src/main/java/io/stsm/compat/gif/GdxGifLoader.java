package io.stsm.compat.gif;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * GIF loader for STSM (Android) compatibility.
 *
 * <p>Important: Decoding and frame texture uploads can be expensive. To avoid
 * freezing the render thread, {@link #loadAsync(FileHandle, String, Map, Map, Map, int, int, int)}
 * decodes off-thread and incrementally uploads textures on the render thread.</p>
 */
public final class GdxGifLoader {

    private GdxGifLoader() {}

    /** Whether to print summary logs. */
    private static final boolean DEBUG = true;
    /** Per-frame logs are expensive; keep false unless actively debugging. */
    private static final boolean VERBOSE_FRAME_LOG = false;

    /** How many decoded frames we upload per render-tick runnable. */
    private static final int UPLOAD_BATCH = 1;

    /**
     * Buffer pool default size.
     * We dynamically downgrade to 1 buffer for very large frames to throttle decode CPU.
     */
    private static final int BUF_POOL_SIZE_DEFAULT = 2;

    /**
     * Minimum interval between GPU uploads (ns). Large textures cost more -> larger interval.
     * Uploads happen on the render thread via postRunnable; throttling helps avoid frame hitches.
     */
    private static final long UPLOAD_INTERVAL_NS_SMALL = 15_000_000L; // 15ms
    private static final long UPLOAD_INTERVAL_NS_LARGE = 33_000_000L; // 33ms (~30fps)
    private static final int LARGE_PIXELS_THRESHOLD = 600_000; // ~ 775x775

    /** Pending async jobs by key. */
    private static final Map<String, Job> JOBS = new ConcurrentHashMap<String, Job>();

    /** Throttled per-key debug logging (to avoid log spam from getGif()). */
    private static final Map<String, Long> LAST_DEBUG_NS = new ConcurrentHashMap<String, Long>();

    /**
     * Called from the patched utils.GifHelper#getGif during the loading phase (delays[] may be placeholder).
     * Prints a short state snapshot at most once every ~2s per key.
     */
    public static void debugTick(String key, int listSize, int delaysLen) {
        if (!DEBUG) return;
        if (key == null) return;
        long now = System.nanoTime();
        Long last = LAST_DEBUG_NS.get(key);
        if (last != null && (now - last.longValue()) < 2_000_000_000L) return;
        LAST_DEBUG_NS.put(key, Long.valueOf(now));

        Job j = JOBS.get(key);
        if (j == null) {
            System.out.println("[SRF_GIF] debugTick key=" + key + " list=" + listSize + " delays=" + delaysLen + " job=null");
            return;
        }
        int q = 0;
        try { q = j.queue.size(); } catch (Throwable ignored) {}
        // Note: Job fields are mostly primitives/volatiles. Avoid .get() calls (those are for atomics).
        // uploadedFrames ~= textures size (minus placeholder) during loading.
        int uploaded = Math.max(0, listSize - 1);
        System.out.println("[SRF_GIF] debugTick key=" + key
                + " list=" + listSize + " delays=" + delaysLen
                + " q=" + q + " maxQ=" + j.maxQueue
                + " uploaded=" + uploaded
                + " decodeDone=" + j.decodeDone
                + " failed=" + j.failed + " cancelled=" + j.cancelled
                + " upScheduled=" + j.uploadScheduled.get()
                + " upIntervalMs=" + (j.uploadMinIntervalNs / 1_000_000L));
    }

    private static final ExecutorService DECODE_EXEC = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger idx = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "SRF-GifDecode-" + idx.getAndIncrement());
            t.setDaemon(true);
                try { t.setPriority(Thread.MIN_PRIORITY); } catch (Throwable ignored) {}

            return t;
        }
    });

    public interface FrameCallback {
        void onFrame(int width, int height, int[] canvasArgb, int delayMs, int frameIndex);
    }

    /**
     * Synchronous load (legacy). Prefer {@link #loadAsync} on Android.
     */
    public static boolean load(InputStream in, final List<Texture> outFrames, final List<Integer> outDelays, int maxFrames, final int maxDim) {
        if (outFrames == null || outDelays == null) return false;
        if (in == null) return false;

        final long t0 = System.nanoTime();
        try {
            return GifDecoder.decode(in, new GifDecoder.FrameCallback() {
                @Override
                public void onFrame(int width, int height, int[] canvasArgb, int delayMs, int frameIndex) {
                    int tw = width;
                    int th = height;
                    float scale = 1.0f;
                    if (maxDim > 0 && (tw > maxDim || th > maxDim)) {
                        float sx = maxDim / (float) tw;
                        float sy = maxDim / (float) th;
                        scale = Math.min(sx, sy);
                        tw = Math.max(1, Math.round(tw * scale));
                        th = Math.max(1, Math.round(th * scale));
                    }

                    // Convert ARGB int[] canvas into RGBA pixmap
                    Pixmap pm = new Pixmap(tw, th, Pixmap.Format.RGBA8888);
                    ByteBuffer buf = pm.getPixels();
                    buf.clear();

                    final int w = width;
                    final int h = height;
                    for (int y = 0; y < th; y++) {
                        int sy = (int) ((long) y * h / th);
                        int row = sy * w;
                        for (int x = 0; x < tw; x++) {
                            int sx = (int) ((long) x * w / tw);
                            int c = canvasArgb[row + sx];
                            int a = (c >>> 24) & 0xFF;
                            int r = (c >>> 16) & 0xFF;
                            int g = (c >>> 8) & 0xFF;
                            int b = (c) & 0xFF;
                            buf.put((byte) r);
                            buf.put((byte) g);
                            buf.put((byte) b);
                            buf.put((byte) a);
                        }
                    }

                    buf.flip();
                    Texture tx = new Texture(pm);
                    pm.dispose();
                    outFrames.add(tx);
                    outDelays.add(delayMs);

                    if (VERBOSE_FRAME_LOG) {
                        System.out.println("[SRF_GIF] onFrame cb key=? idx=" + frameIndex + " out=" + outFrames.size() + " delay=" + delayMs + " canvas=" + w + "x" + h + " target=" + tw + "x" + th + " scale=" + scale);
                    }
                }
            }, maxFrames);
        } catch (Throwable t) {
            if (DEBUG) System.out.println("[SRF_GIF] load sync failed: " + t);
            return false;
        } finally {
            closeQuietly(in);
            if (DEBUG) {
                long dtMs = (System.nanoTime() - t0) / 1_000_000L;
                System.out.println("[SRF_GIF] load sync done frames=" + outFrames.size() + " dtMs=" + dtMs);
            }
        }
    }

    /**
     * Async load into GifHelper maps.
     *
     * @param gifTextures GifHelper.gifTextures
     * @param gifDelays   GifHelper.gifDelays
     * @param startTimes  GifHelper.startTimes
     */
    public static void loadAsync(final FileHandle fh,
                                 final String key,
                                 final Map<String, List<Texture>> gifTextures,
                                 final Map<String, int[]> gifDelays,
                                 final Map<String, Long> startTimes,
                                 final int maxFrames,
                                 final int preferMaxDim,
                                 final int fallbackMaxDim) {
        if (fh == null || key == null || gifTextures == null || gifDelays == null || startTimes == null) return;

        // Already loaded or already loading.
        if (gifTextures.containsKey(key) && JOBS.containsKey(key) == false) {
            // If textures exist, assume loaded.
            List<Texture> existing = gifTextures.get(key);
            if (existing != null && !existing.isEmpty()) return;
        }

        Job existingJob = JOBS.get(key);
        if (existingJob != null) {
            // If the file handle changed, ignore; otherwise keep current job.
            return;
        }

        // Ensure a placeholder entry exists so GifHelper.getGif won't keep calling loadGif.
        Texture placeholder = null;
        try {
            int[] wh = peekGifDimensions(fh);
            if (wh != null) {
                int srcW = wh[0];
                int srcH = wh[1];
                int[] twh = computeTargetDim(srcW, srcH, preferMaxDim);
                placeholder = createTransparentTexture(twh[0], twh[1]);
            }
        } catch (Throwable ignored) {}

        if (placeholder == null) {
            placeholder = GifFallback.get();
        }

        List<Texture> list = gifTextures.get(key);
        if (list == null) {
            list = new ArrayList<Texture>(8);
            gifTextures.put(key, list);
        } else {
            // If there is already an entry, do not overwrite it.
        }

        if (list.isEmpty()) {
            list.add(placeholder);
        }

        // Put a temporary delays array so getGif has something to work with.
        if (!gifDelays.containsKey(key)) {
            gifDelays.put(key, new int[] { GifDecoder.MIN_DELAY_MS });
        }
        // Start at current time (will reset when fully ready).
        if (!startTimes.containsKey(key)) {
            startTimes.put(key, System.nanoTime());
        }

        final Job job = new Job(key, fh, list, placeholder, gifTextures, gifDelays, startTimes,
                maxFrames, preferMaxDim, fallbackMaxDim);
        JOBS.put(key, job);

        if (DEBUG) System.out.println("[SRF_GIF] loadAsync start key=" + key + " file=" + fh.path());

        DECODE_EXEC.execute(new Runnable() {
            @Override
            public void run() {
                job.decode();
            }
        });
    }

    private static final class Job {
        final String key;
        final FileHandle fh;

        final List<Texture> textures;
        final Texture placeholder;

        // Reusable upload pixmap (render thread only). Avoids per-frame Pixmap alloc/GC.
        Pixmap uploadPixmap = null;

        final Map<String, List<Texture>> gifTextures;
        final Map<String, int[]> gifDelays;
        final Map<String, Long> startTimes;

        final int maxFrames;
        int maxDim;
        final int fallbackMaxDim;
        boolean triedFallback = false;

        final ArrayBlockingQueue<FrameBytes> queue = new ArrayBlockingQueue<FrameBytes>(BUF_POOL_SIZE_DEFAULT);
        final AtomicBoolean uploadScheduled = new AtomicBoolean(false);

        volatile boolean decodeDone = false;
        volatile boolean failed = false;
        volatile boolean cancelled = false;

        volatile int srcW = 0;
        volatile int srcH = 0;
        volatile int outW = 0;
        volatile int outH = 0;

        BufferPool pool;
        final ArrayList<Integer> delays = new ArrayList<Integer>();

        // perf / throttle
        volatile long lastUploadNs = 0L;
        volatile long uploadMinIntervalNs = UPLOAD_INTERVAL_NS_SMALL;
        long convertNsSum = 0L;
        int convertCount = 0;
        long uploadNsSum = 0L;
        int uploadCount = 0;
        long maxUploadMs = 0L;
        int maxQueue = 0;


        long t0Ns;

        Job(String key,
            FileHandle fh,
            List<Texture> textures,
            Texture placeholder,
            Map<String, List<Texture>> gifTextures,
            Map<String, int[]> gifDelays,
            Map<String, Long> startTimes,
            int maxFrames,
            int maxDim,
            int fallbackMaxDim) {
            this.key = key;
            this.fh = fh;
            this.textures = textures;
            this.placeholder = placeholder;
            this.gifTextures = gifTextures;
            this.gifDelays = gifDelays;
            this.startTimes = startTimes;
            this.maxFrames = maxFrames;
            this.maxDim = maxDim;
            this.fallbackMaxDim = fallbackMaxDim;
        }

        void decode() {
            this.t0Ns = System.nanoTime();
            InputStream in = null;
            try {
                in = fh.read();
                final Job self = this;
                boolean ok = GifDecoder.decode(in, new GifDecoder.FrameCallback() {
                    @Override
                    public void onFrame(int width, int height, int[] canvasArgb, int delayMs, int frameIndex) {
                        if (self.cancelled) throw new RuntimeException("cancelled");

                        if (self.srcW == 0) {
                            self.srcW = width;
                            self.srcH = height;
                            int[] twh = computeTargetDim(width, height, self.maxDim);
                            self.outW = twh[0];
                            self.outH = twh[1];
                            int __px = self.outW * self.outH;
                             int __poolSize = (__px >= LARGE_PIXELS_THRESHOLD) ? 1 : BUF_POOL_SIZE_DEFAULT;
                             self.uploadMinIntervalNs = (__px >= LARGE_PIXELS_THRESHOLD) ? UPLOAD_INTERVAL_NS_LARGE : UPLOAD_INTERVAL_NS_SMALL;
                             self.pool = new BufferPool(self.outW, self.outH, __poolSize);

                            if (DEBUG) {
                                float scale = (width == 0) ? 1.0f : (self.outW / (float) width);
                                System.out.println("[SRF_GIF] async decode init key=" + self.key + " src=" + width + "x" + height + " out=" + self.outW + "x" + self.outH + " maxDim=" + self.maxDim + " scale=" + scale);
                            }
                        }

                        // Convert canvasARGB -> RGBA bytes (in background thread)
                        ByteBuffer rgba = self.pool.acquire();
                        rgba.clear();

                        final int w = self.srcW;
                        final int h = self.srcH;
                        final int tw = self.outW;
                        final int th = self.outH;

                        for (int y = 0; y < th; y++) {
                            int sy = (int) ((long) y * h / th);
                            int row = sy * w;
                            for (int x = 0; x < tw; x++) {
                                int sx = (int) ((long) x * w / tw);
                                int c = canvasArgb[row + sx];
                                int a = (c >>> 24) & 0xFF;
                                int r = (c >>> 16) & 0xFF;
                                int g = (c >>> 8) & 0xFF;
                                int b = (c) & 0xFF;
                                rgba.put((byte) r);
                                rgba.put((byte) g);
                                rgba.put((byte) b);
                                rgba.put((byte) a);
                            }
                        }

                        rgba.flip();

                        FrameBytes fb = new FrameBytes(rgba, delayMs, frameIndex, tw, th);
                        try {
                            self.queue.put(fb);
                             int __qs = self.queue.size();
                             if (__qs > self.maxQueue) self.maxQueue = __qs;
                        } catch (InterruptedException ie) {
                            self.cancelled = true;
                            throw new RuntimeException("interrupted");
                        }

                        self.requestUpload();
                         self.maybeThrottleDecode(frameIndex);
                    }
                }, maxFrames);

                if (!ok) {
                    self.failed = true;
                    if (DEBUG) System.out.println("[SRF_GIF] async decode returned false key=" + self.key);
                }

            } catch (OutOfMemoryError oom) {
                this.failed = true;
                if (DEBUG) System.out.println("[SRF_GIF] async decode OOM key=" + key + " maxDim=" + maxDim + " err=" + oom);
            } catch (Throwable t) {
                // cancelled is expected
                if (!"cancelled".equals(String.valueOf(t.getMessage()))) {
                    this.failed = true;
                    if (DEBUG) System.out.println("[SRF_GIF] async decode failed key=" + key + " err=" + t);
                }
            } finally {
                closeQuietly(in);
                decodeDone = true;
                requestUpload();
            }
        }

        void requestUpload() {
            if (Gdx.app == null) return;
            if (uploadScheduled.compareAndSet(false, true)) {
                Gdx.app.postRunnable(new UploadRunnable(this));
            }
        }

        void finalizeSuccess() {
            // Remove placeholder if we have real frames
            if (placeholder != null && textures.size() > 1 && textures.get(0) == placeholder) {
                textures.remove(0);
                if (placeholder != GifFallback.get()) {
                    try {
                        placeholder.dispose();
                    } catch (Throwable ignored) {}
                }
            }

            // Build delays
            int n = Math.min(textures.size(), delays.size());
            if (n <= 0) {
                failed = true;
                finalizeFail();
                return;
            }
            int[] d = new int[n];
            for (int i = 0; i < n; i++) {
                d[i] = Math.max(GifDecoder.MIN_DELAY_MS, delays.get(i));
            }
            gifDelays.put(key, d);
            startTimes.put(key, System.nanoTime());

            // If we ended up with extra textures (shouldn't), trim.
            while (textures.size() > n) {
                try {
                    Texture tx = textures.remove(textures.size() - 1);
                    if (tx != null) tx.dispose();
                } catch (Throwable ignored) {}
            }

            if (DEBUG) {
                long dtMs = (System.nanoTime() - t0Ns) / 1_000_000L;
                double convAvg = (convertCount <= 0) ? 0.0 : (convertNsSum / 1_000_000.0) / convertCount;
                double upAvg = (uploadCount <= 0) ? 0.0 : (uploadNsSum / 1_000_000.0) / uploadCount;
                System.out.println("[SRF_GIF] loadAsync ok key=" + key +
                        " frames=" + n +
                        " dtMs=" + dtMs +
                        " out=" + outW + "x" + outH +
                        " convAvgMs=" + String.format(java.util.Locale.ROOT, "%.2f", convAvg) +
                        " upAvgMs=" + String.format(java.util.Locale.ROOT, "%.2f", upAvg) +
                        " maxUpMs=" + maxUploadMs +
                        " maxQ=" + maxQueue +
                        " upIntervalMs=" + (uploadMinIntervalNs / 1_000_000L));
            }

            if (uploadPixmap != null) {
                try { uploadPixmap.dispose(); } catch (Throwable ignored) {}
                uploadPixmap = null;
            }

            JOBS.remove(key);
        }

        void finalizeFail() {
            // If we can try fallback, restart.
            if (!triedFallback && fallbackMaxDim > 0 && fallbackMaxDim < maxDim) {
                triedFallback = true;
                maxDim = fallbackMaxDim;
                // clear state and retry
                cleanupPartialTextures();
                delays.clear();
                decodeDone = false;
                failed = false;
                cancelled = false;
                srcW = 0;
                srcH = 0;
                outW = 0;
                outH = 0;
                pool = null;
                // ensure placeholder exists
                if (textures.isEmpty()) {
                    textures.add(placeholder != null ? placeholder : GifFallback.get());
                }
                if (DEBUG) System.out.println("[SRF_GIF] retry with fallback maxDim=" + maxDim + " key=" + key);
                DECODE_EXEC.execute(new Runnable() {
                    @Override
                    public void run() {
                        decode();
                    }
                });
                return;
            }

            if (DEBUG) System.out.println("[SRF_GIF] loadAsync failed key=" + key + " keeping placeholder");

            // Keep placeholder only; provide a safe delays array.
            gifDelays.put(key, new int[] { GifDecoder.MIN_DELAY_MS });
            startTimes.put(key, System.nanoTime());

            // Ensure textures list has at least one element.
            if (textures.isEmpty()) {
                textures.add(placeholder != null ? placeholder : GifFallback.get());
            }

            if (uploadPixmap != null) {
                try { uploadPixmap.dispose(); } catch (Throwable ignored) {}
                uploadPixmap = null;
            }

            JOBS.remove(key);
        }

        
void maybeThrottleDecode(int frameIndex) {
    // Decoding + ARGB->RGBA conversion can saturate CPU and cause gameplay hitches on mobile.
    // We intentionally yield a little on very large frames to reduce contention with the render thread.
    try {
        int px = outW * outH;
        if (px >= LARGE_PIXELS_THRESHOLD) {
            if ((frameIndex & 1) == 0) Thread.sleep(1L);
            if ((frameIndex & 7) == 0) Thread.sleep(2L);
        } else if (px >= 300_000) {
            if ((frameIndex & 3) == 0) Thread.sleep(1L);
        }
    } catch (Throwable ignored) {
    }
}

void cleanupPartialTextures() {
            // Dispose everything except placeholder
            for (int i = textures.size() - 1; i >= 0; i--) {
                Texture tx = textures.get(i);
                if (tx == null) {
                    textures.remove(i);
                    continue;
                }
                if (tx == placeholder) continue;
                try {
                    tx.dispose();
                } catch (Throwable ignored) {}
                textures.remove(i);
            }
            if (uploadPixmap != null) {
                try { uploadPixmap.dispose(); } catch (Throwable ignored) {}
                uploadPixmap = null;
            }
            // Drain queue and release buffers
            FrameBytes fb;
            while ((fb = queue.poll()) != null) {
                if (pool != null && fb.rgba != null) {
                    pool.release(fb.rgba);
                }
            }
        }
    }

    private static final class UploadRunnable implements Runnable {
        private final Job job;

        UploadRunnable(Job job) {
            this.job = job;
        }

        @Override
        public void run() {
            // If job removed, stop.
            if (JOBS.containsKey(job.key) == false) {
                job.uploadScheduled.set(false);
                return;
            }

            // Throttle GPU uploads to reduce frame hitches while decoding large GIFs.
            long __nowNs = System.nanoTime();
            long __lastNs = job.lastUploadNs;
            if (__lastNs != 0L && (__nowNs - __lastNs) < job.uploadMinIntervalNs) {
                job.uploadScheduled.set(false);
                if (!job.queue.isEmpty() || !job.decodeDone) {
                    job.requestUpload();
                }
                return;
            }
            job.lastUploadNs = __nowNs;

            try {
                int processed = 0;
                while (processed < UPLOAD_BATCH) {
                    FrameBytes fb = job.queue.poll();
                    if (fb == null) break;

                    try {
                        long __u0 = System.nanoTime();

                        if (job.uploadPixmap == null) {
                            job.uploadPixmap = new Pixmap(fb.w, fb.h, Pixmap.Format.RGBA8888);
                        }

                        ByteBuffer dst = job.uploadPixmap.getPixels();
                        dst.clear();
                        dst.put(fb.rgba);
                        dst.flip();

                        Texture tx = new Texture(fb.w, fb.h, Pixmap.Format.RGBA8888);
                        tx.draw(job.uploadPixmap, 0, 0);

                        long __u1 = System.nanoTime();
                        long __uMs = (__u1 - __u0) / 1_000_000L;
                        job.uploadNsSum += (__u1 - __u0);
                        job.uploadCount++;
                        if (__uMs > job.maxUploadMs) job.maxUploadMs = __uMs;
                        if (VERBOSE_FRAME_LOG || __uMs >= 25L) {
                            System.out.println("[SRF_GIF] upload key=" + job.key +
                                    " frame=" + fb.frameIndex +
                                    " uploadMs=" + __uMs +
                                    " queued=" + job.queue.size() +
                                    " decodeDone=" + job.decodeDone);
                        }

                        // Replace placeholder as soon as we have the first real frame.
                        if (job.placeholder != null && job.textures.size() == 1 && job.textures.get(0) == job.placeholder) {
                            job.textures.clear();
                            // Dispose placeholder only if it is not the shared fallback.
                            if (job.placeholder != GifFallback.get()) {
                                try {
                                    job.placeholder.dispose();
                                } catch (Throwable ignored) {}
                            }
                        }

                        job.textures.add(tx);
                        job.delays.add(fb.delayMs);

                    } catch (OutOfMemoryError oom) {
                        job.failed = true;
                        if (DEBUG) System.out.println("[SRF_GIF] upload OOM key=" + job.key + " out=" + fb.w + "x" + fb.h + " err=" + oom);
                        // Stop consuming; retry/fail in finalize.
                        break;
                    } catch (Throwable t) {
                        job.failed = true;
                        if (DEBUG) System.out.println("[SRF_GIF] upload failed key=" + job.key + " err=" + t);
                        break;
                    } finally {
                        if (job.pool != null && fb.rgba != null) {
                            job.pool.release(fb.rgba);
                        }
                    }

                    processed++;
                }

            } finally {
                // Mark runnable finished.
                job.uploadScheduled.set(false);

                if (job.failed) {
                    job.cleanupPartialTextures();
                    job.finalizeFail();
                    return;
                }

                // Finalize if decode done and no pending frames.
                if (job.decodeDone && job.queue.isEmpty()) {
                    job.finalizeSuccess();
                    return;
                }

                // If more frames queued, schedule another upload.
                if (!job.queue.isEmpty()) {
                    job.requestUpload();
                }
                // Else: wait until decoder enqueues more frames and requests upload.
            }
        }
    }

    private static final class FrameBytes {
        final ByteBuffer rgba;
        final int delayMs;
        final int frameIndex;
        final int w;
        final int h;

        FrameBytes(ByteBuffer rgba, int delayMs, int frameIndex, int w, int h) {
            this.rgba = rgba;
            this.delayMs = delayMs;
            this.frameIndex = frameIndex;
            this.w = w;
            this.h = h;
        }
    }

    private static final class BufferPool {
        final ArrayBlockingQueue<ByteBuffer> pool;

        BufferPool(int w, int h, int poolSize) {
            int cap = Math.max(4, w * h * 4);
            pool = new ArrayBlockingQueue<ByteBuffer>(poolSize);
            for (int i = 0; i < poolSize; i++) {
                pool.offer(ByteBuffer.allocateDirect(cap));
            }
        }

        ByteBuffer acquire() {
            try {
                return pool.take();
            } catch (InterruptedException ie) {
                throw new RuntimeException(ie);
            }
        }

        void release(ByteBuffer b) {
            if (b == null) return;
            b.clear();
            pool.offer(b);
        }
    }

    private static int[] computeTargetDim(int width, int height, int maxDim) {
        int tw = width;
        int th = height;
        if (maxDim > 0 && (tw > maxDim || th > maxDim)) {
            float sx = maxDim / (float) tw;
            float sy = maxDim / (float) th;
            float s = Math.min(sx, sy);
            tw = Math.max(1, Math.round(tw * s));
            th = Math.max(1, Math.round(th * s));
        }
        return new int[]{tw, th};
    }

    private static int[] peekGifDimensions(FileHandle fh) {
        InputStream in = null;
        try {
            in = fh.read();
            byte[] hdr = new byte[10];
            int off = 0;
            while (off < hdr.length) {
                int n = in.read(hdr, off, hdr.length - off);
                if (n <= 0) break;
                off += n;
            }
            if (off < 10) return null;
            // "GIF87a" or "GIF89a"
            if (!(hdr[0] == 'G' && hdr[1] == 'I' && hdr[2] == 'F')) return null;
            int w = (hdr[6] & 0xFF) | ((hdr[7] & 0xFF) << 8);
            int h = (hdr[8] & 0xFF) | ((hdr[9] & 0xFF) << 8);
            if (w <= 0 || h <= 0) return null;
            return new int[]{w, h};
        } catch (Throwable ignored) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static Texture createTransparentTexture(int w, int h) {
        // Creating a texture here is still cheaper than blocking decode/upload on the render thread.
        try {
            Pixmap pm = new Pixmap(Math.max(1, w), Math.max(1, h), Pixmap.Format.RGBA8888);
            pm.setColor(0f, 0f, 0f, 0f);
            pm.fill();
            Texture tx = new Texture(pm);
            pm.dispose();
            return tx;
        } catch (Throwable t) {
            return GifFallback.get();
        }
    }

    private static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {}
    }
}
