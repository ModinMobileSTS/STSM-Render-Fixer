package io.stsm.compat.gif;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import java.util.Arrays;

/**
 * Minimal GIF87a/GIF89a decoder (pure Java, no javax.imageio / java.awt).
 *
 * Key design for Android:
 *  - DOES NOT snapshot full-canvas int[] per frame (which easily OOMs on large GIFs).
 *  - Instead, keeps a single full-canvas ARGB canvas and invokes a callback per frame.
 *
 * The callback receives the current composited full canvas (width*height ARGB).
 */
public final class GifDecoder {
    private GifDecoder() {}

    /** Match some desktop helpers: clamp frame delay to at least 20ms. */
    public static final int MIN_DELAY_MS = 20;

    /** Internal version string for log verification. */
    private static final String VERSION = "gif-v25-fullcycle";

    /** Enable verbose decode logs when troubleshooting. */
    private static final boolean DEBUG = true;
    // Turn off extremely verbose per-block logs by default (can be re-enabled in source when needed).
    private static final boolean VERBOSE = false;
    private static final int VERBOSE_MAX_UNKNOWN = 32;

    private static void log(String msg) {
        try {
            System.out.println(msg);
        } catch (Throwable ignored) {
        }
    }

    /** Frame callback invoked after compositing each frame onto the full canvas. */
    public interface FrameCallback {
        void onFrame(int width, int height, int[] canvasArgb, int delayMs, int frameIndex) throws Exception;
    }

    /**
     * Plan for emitting frames: when a GIF contains too many frames, we don't want to
     * just take the first N, because that makes the animation loop early and it looks
     * like "only plays the first half". Instead, we keep every K-th source frame and
     * merge skipped delays into the previous kept frame.
     */
    private static final class FramePlan {
        final boolean[] keep;
        final int[] mergedDelayMs;
        final int step;
        final int maxOut;

        private FramePlan(boolean[] keep, int[] mergedDelayMs, int step, int maxOut) {
            this.keep = keep;
            this.mergedDelayMs = mergedDelayMs;
            this.step = step;
            this.maxOut = maxOut;
        }

        static FramePlan fallback(int maxOut) {
            // No scan data. We'll behave like legacy: emit first maxOut frames.
            return new FramePlan(null, null, 1, maxOut);
        }

        boolean shouldEmit(int srcFrameIndex, int emittedSoFar) {
            if (keep == null) {
                return emittedSoFar < maxOut;
            }
            if (srcFrameIndex < 0 || srcFrameIndex >= keep.length) return false;
            return keep[srcFrameIndex];
        }

        int delayFor(int srcFrameIndex, int rawDelayMs) {
            if (mergedDelayMs == null) {
                return rawDelayMs > 0 ? rawDelayMs : MIN_DELAY_MS;
            }
            if (srcFrameIndex < 0 || srcFrameIndex >= mergedDelayMs.length) {
                return rawDelayMs > 0 ? rawDelayMs : MIN_DELAY_MS;
            }
            int d = mergedDelayMs[srcFrameIndex];
            return d > 0 ? d : Math.max(MIN_DELAY_MS, rawDelayMs);
        }

        static FramePlan build(byte[] bytes, int maxOut) throws IOException {
            ScanResult scan = scanFramesAndDelays(bytes);
            if (scan.frameCount <= 0) {
                return fallback(maxOut);
            }

            int step = 1;
            if (scan.frameCount > maxOut) {
                step = (int) Math.ceil(scan.frameCount / (double) maxOut);
                if (step < 1) step = 1;
            }

            boolean[] keep = new boolean[scan.frameCount];
            for (int i = 0; i < scan.frameCount; i++) {
                keep[i] = (i % step) == 0;
            }
            keep[0] = true;

            int[] merged = new int[scan.frameCount];
            int lastKept = 0;
            for (int i = 0; i < scan.frameCount; i++) {
                if (keep[i]) lastKept = i;
                int d = scan.delayMs[i];
                if (d <= 0) d = MIN_DELAY_MS;
                merged[lastKept] += d;
            }

            if (DEBUG) {
                int keptCount = 0;
                long sum = 0;
                for (int i = 0; i < scan.frameCount; i++) {
                    sum += scan.delayMs[i];
                    if (keep[i]) keptCount++;
                }
                log("[SRF_GIF] plan: srcFrames=" + scan.frameCount + " keepFrames=" + keptCount
                        + " step=" + step + " maxOut=" + maxOut + " sumMs=" + sum);
            }

            return new FramePlan(keep, merged, step, maxOut);
        }
    }

    private static final class ScanResult {
        final int frameCount;
        final int[] delayMs;
        ScanResult(int frameCount, int[] delayMs) {
            this.frameCount = frameCount;
            this.delayMs = delayMs;
        }
    }

    /**
     * A lightweight pass that counts frames and captures per-frame delays (GCE delay applies to the
     * following image) without LZW decoding. This lets us plan a fair sampling strategy.
     */
    private static ScanResult scanFramesAndDelays(byte[] bytes) throws IOException {
        Reader r = new Reader(bytes);

        // Header
        String sig = r.readString(6);
        if (!"GIF87a".equals(sig) && !"GIF89a".equals(sig)) {
            return new ScanResult(0, new int[0]);
        }

        // Logical Screen Descriptor
        int width = r.readU16LE();
        int height = r.readU16LE();
        if (width <= 0 || height <= 0) {
            return new ScanResult(0, new int[0]);
        }

        int packed = r.readU8();
        boolean gctFlag = (packed & 0x80) != 0;
        int gctSizePow = (packed & 0x07) + 1;
        r.readU8(); // bg index
        r.readU8(); // aspect

        if (gctFlag) {
            int size = 1 << gctSizePow;
            // each entry is 3 bytes
            r.skip(size * 3);
        }

        int[] delays = new int[64];
        int count = 0;

        int nextDelayMs = MIN_DELAY_MS;

        // Blocks
        while (r.hasRemaining()) {
            int b = r.readU8();
            if (b == 0x00) continue;
            if (b == 0x3B) {
                break; // trailer
            } else if (b == 0x21) {
                int label = r.readU8();
                if (label == 0xF9) {
                    int blockSize = r.readU8();
                    // blockSize is typically 4
                    if (blockSize > 0) {
                        int packedGce = r.readU8();
                        int delayCs = r.readU16LE();
                        r.readU8(); // trans index
                        r.readU8(); // terminator
                        int delay = delayCs * 10;
                        if (delay <= 0) delay = MIN_DELAY_MS;
                        nextDelayMs = delay;
                    } else {
                        // malformed; attempt to skip to terminator
                        skipDataSubBlocks(r);
                        nextDelayMs = MIN_DELAY_MS;
                    }
                } else {
                    // Other extension: skip its data sub-blocks
                    skipDataSubBlocks(r);
                }
            } else if (b == 0x2C) {
                // Image descriptor
                r.readU16LE(); // ix
                r.readU16LE(); // iy
                r.readU16LE(); // iw
                r.readU16LE(); // ih
                int ipacked = r.readU8();
                boolean lctFlag = (ipacked & 0x80) != 0;
                int lctSizePow = (ipacked & 0x07) + 1;
                if (lctFlag) {
                    int size = 1 << lctSizePow;
                    r.skip(size * 3);
                }

                // LZW minimum code size
                r.readU8();
                // Image data sub-blocks
                skipDataSubBlocks(r);

                // Record delay for this frame
                if (count == delays.length) {
                    delays = Arrays.copyOf(delays, delays.length * 2);
                }
                delays[count++] = nextDelayMs;
                nextDelayMs = MIN_DELAY_MS;
            } else {
                // Unknown top-level block. Some real-world GIFs contain junk/padding.
                // Be lenient: attempt to resync to the next known block introducer.
                if (!r.resyncToNextBlockIntroducer()) {
                    break;
                }
            }
        }

        return new ScanResult(count, Arrays.copyOf(delays, count));
    }

    /** Skip a sequence of GIF data sub-blocks (len byte followed by len bytes) until a 0 len. */
    private static void skipDataSubBlocks(Reader r) throws IOException {
        while (r.hasRemaining()) {
            int len = r.readU8();
            if (len <= 0) break;
            r.skip(len);
        }
    }

    /**
     * Decode and invoke callback for up to {@code maxFrames} frames.
     * Returns false on invalid input.
     */
    public static boolean decode(InputStream in, FrameCallback cb, int maxFrames) throws IOException {
        if (in == null || cb == null) return false;
        if (maxFrames <= 0) maxFrames = 1;

        byte[] bytes = readAll(in);
        if (bytes.length < 14) return false;

        if (DEBUG) {
            log("[SRF_GIF] decoder=" + VERSION);
        }

        // If the GIF contains many frames, emitting only the first N causes the animation
        // to loop early ("only plays the first half"). Instead of truncating, we plan a
        // sampling strategy that keeps at most maxFrames frames spread across the whole
        // animation and merges skipped delays into the previous kept frame.
        FramePlan plan;
        try {
            plan = FramePlan.build(bytes, maxFrames);
        } catch (Throwable t) {
            // Fallback: keep legacy behavior (truncate) if planning fails.
            plan = FramePlan.fallback(maxFrames);
            if (DEBUG) log("[SRF_GIF] FramePlan build failed, fallback to truncate. err=" + t);
        }

        Reader r = new Reader(bytes);

        // Header
        String sig = r.readString(6);
        if (!"GIF87a".equals(sig) && !"GIF89a".equals(sig)) return false;

        int width = r.readU16LE();
        int height = r.readU16LE();
        if (width <= 0 || height <= 0) return false;
        if (DEBUG) {
            System.out.println("[SRF_GIF] decode start: " + sig + " " + width + "x" + height
                    + " maxFrames=" + maxFrames + " bytes=" + bytes.length);
        }

        int packed = r.readU8();
        boolean gctFlag = (packed & 0x80) != 0;
        int gctSizePow = (packed & 0x07) + 1;
        r.readU8(); // bg index
        r.readU8(); // pixel aspect

        int[] gct = null;
        if (gctFlag) {
            int size = 1 << gctSizePow;
            gct = readColorTable(r, size);
        }

        // Current GCE state (applies to NEXT image)
        int gceDisposal = 0;
        int gceDelayMs = MIN_DELAY_MS;
        boolean gceTransFlag = false;
        int gceTransIndex = 0;

        // Full canvas ARGB
        int[] canvas = new int[width * height];

        // Disposal bookkeeping
        int prevX = 0, prevY = 0, prevW = 0, prevH = 0, prevDisposal = 0;
        int[] restoreCanvas = null; // for disposal==3
        byte[] idxReuse = null; // reuse LZW output buffer to reduce GC pressure

        int emitted = 0;
        int frameIndex = 0;

        int junkBytes = 0;
        String endReason = "eof";

        while (r.hasRemaining()) {
            int __pos = r.position();
            int b = r.readU8();
            // Some GIF encoders insert padding 0x00 bytes between top-level blocks.
            // Treat them as no-ops to avoid prematurely terminating the decode.
            if (b == 0x00) {
                continue;
            }
            if (VERBOSE) {
                log("[SRF_GIF] topblock pos=" + __pos + " b=0x" + Integer.toHexString(b));
            }
            if (b == 0x3B) {
                endReason = "trailer";
                break; // trailer
            } else if (b == 0x21) {
                // extension
                int label = r.readU8();
                if (label == 0xF9) {
                    // Graphic Control Extension
                    int blockSize = r.readU8();
                    if (blockSize != 4) {
                        r.skip(blockSize);
                        r.readU8();
                        continue;
                    }
                    int p = r.readU8();
                    gceDisposal = (p >> 2) & 0x07;
                    gceTransFlag = (p & 0x01) != 0;
                    int delayCs = r.readU16LE();
                    gceDelayMs = Math.max(MIN_DELAY_MS, delayCs * 10);
                    gceTransIndex = r.readU8() & 0xFF;
                    r.readU8(); // terminator
                    if (VERBOSE) {
                        log("[SRF_GIF] GCE: disposal=" + gceDisposal + " delayMs=" + gceDelayMs + " trans=" + gceTransFlag + " transIndex=" + gceTransIndex);
                    }
                } else {
                    skipSubBlocks(r);
                }
            } else if (b == 0x2C) {
                // image descriptor

                // Apply disposal for previous frame BEFORE drawing this one
                if (prevDisposal == 2) {
                    clearRect(canvas, width, height, prevX, prevY, prevW, prevH);
                } else if (prevDisposal == 3 && restoreCanvas != null) {
                    System.arraycopy(restoreCanvas, 0, canvas, 0, canvas.length);
                }

                int ix = r.readU16LE();
                int iy = r.readU16LE();
                int iw = r.readU16LE();
                int ih = r.readU16LE();
                int ipacked = r.readU8();

                boolean lctFlag = (ipacked & 0x80) != 0;
                boolean interlace = (ipacked & 0x40) != 0;
                if (VERBOSE) {
                    log("[SRF_GIF] IMG: frame=" + frameIndex + " rect=" + ix + "," + iy + " " + iw + "x" + ih
                            + " packed=0x" + Integer.toHexString(ipacked)
                            + " lct=" + lctFlag + " interlace=" + interlace);
                }
                int lctSizePow = (ipacked & 0x07) + 1;

                int[] lct = null;
                if (lctFlag) {
                    int size = 1 << lctSizePow;
                    lct = readColorTable(r, size);
                }
                int[] act = (lct != null) ? lct : gct;
                if (act == null) return false;

                // For disposal==3, save canvas state BEFORE drawing this frame.
                // This can be very memory-hungry on Android (full-canvas snapshot).
                // If we can't allocate, degrade gracefully by treating disposal as 0.
                if (gceDisposal == 3) {
                    try {
                        restoreCanvas = new int[canvas.length];
                        System.arraycopy(canvas, 0, restoreCanvas, 0, canvas.length);
                    } catch (OutOfMemoryError oom) {
                        restoreCanvas = null;
                        gceDisposal = 0;
                    }
                } else {
                    restoreCanvas = null;
                }

                int lzwMin = r.readU8();
                int expectedPixels = iw * ih;
                if (idxReuse == null || idxReuse.length < expectedPixels) {
                    try {
                        idxReuse = new byte[expectedPixels];
                    } catch (OutOfMemoryError oom) {
                        endReason = "oom_idx";
                        if (DEBUG) log("[SRF_GIF] OOM allocating idx buffer pixels=" + expectedPixels + " frame=" + frameIndex);
                        return false;
                    }
                }

                int decodedPixels = decodeLzwSubBlocks(r, lzwMin, idxReuse, expectedPixels);
                if (VERBOSE) {
                    log("[SRF_GIF] LZW: frame=" + frameIndex + " minCodeSize=" + lzwMin
                            + " expectedPixels=" + expectedPixels + " decodedPixels=" + decodedPixels);
                }
                if (decodedPixels <= 0) {
                    endReason = "lzwFail";
                    if (DEBUG) log("[SRF_GIF] LZW decode failed at frame=" + frameIndex + " pos=" + r.position());
                    return false;
                }
                byte[] idx = idxReuse;

                if (!interlace) {
                    composite(canvas, width, height, ix, iy, iw, ih, idx, act, gceTransFlag, gceTransIndex);
                } else {
                    compositeInterlaced(canvas, width, height, ix, iy, iw, ih, idx, act, gceTransFlag, gceTransIndex);
                }

                int rawDelayMs = (gceDelayMs > 0) ? gceDelayMs : MIN_DELAY_MS;

                // Decide whether to emit this SOURCE frame. Even when we don't emit,
                // we still decode/composite to keep the canvas state correct.
                boolean keep = plan.shouldEmit(frameIndex, emitted);
                if (keep) {
                    int delayMs = plan.delayFor(frameIndex, rawDelayMs);
                    try {
                        if (VERBOSE) {
                            log("[SRF_GIF] emit: emitted=" + emitted + " srcFrame=" + frameIndex
                                    + " delayMs=" + delayMs + " rawDelayMs=" + rawDelayMs
                                    + " step=" + plan.step
                                    + " gceDisp=" + gceDisposal
                                    + " rect=" + ix + "," + iy + " " + iw + "x" + ih
                                    + " act=" + (act==null?"null":("len"+act.length))
                                    + " trans=" + gceTransFlag + " tIdx=" + gceTransIndex);
                        }
                        cb.onFrame(width, height, canvas, delayMs, emitted);
                    } catch (RuntimeException re) {
                        throw re;
                    } catch (Exception e) {
                        IOException ioe = new IOException(e);
                        throw ioe;
                    }
                    emitted++;
                } else {
                    if (VERBOSE) {
                        log("[SRF_GIF] skip: srcFrame=" + frameIndex + " rawDelayMs=" + rawDelayMs
                                + " step=" + plan.step + " emittedSoFar=" + emitted);
                    }
                }

                // Remember disposal for next iteration
                prevX = ix; prevY = iy; prevW = iw; prevH = ih; prevDisposal = gceDisposal;

                // Reset GCE for next image
                gceDisposal = 0;
                gceDelayMs = MIN_DELAY_MS;
                gceTransFlag = false;
                gceTransIndex = 0;

                frameIndex++;
            } else {
                // Unknown byte at top-level.
                // Real-world GIFs sometimes contain junk/padding bytes between blocks.
                // Be lenient: ignore and keep scanning.
                junkBytes++;
                if (VERBOSE && junkBytes <= VERBOSE_MAX_UNKNOWN) {
                    log("[SRF_GIF] unknown top byte pos=" + __pos + " val=0x" + Integer.toHexString(b) + " junkCount=" + junkBytes);
                }
                continue;
            }
        }

        if (DEBUG) {
            System.out.println("[SRF_GIF] decode finished: emitted=" + emitted
                    + " reason=" + endReason
                    + " junkBytes=" + junkBytes
                    + " bytes=" + bytes.length);
        }
        return emitted > 0;
    }

    // -------- core helpers --------

    private static void composite(int[] canvas, int canvasW, int canvasH,
                                  int ix, int iy, int iw, int ih,
                                  byte[] idx, int[] act,
                                  boolean trans, int transIndex) {
        int p = 0;
        for (int y = 0; y < ih; y++) {
            int cy = iy + y;
            if (cy < 0 || cy >= canvasH) { p += iw; continue; }
            int rowOff = cy * canvasW;
            for (int x = 0; x < iw; x++) {
                int cx = ix + x;
                int ii = idx[p++] & 0xFF;
                if (cx < 0 || cx >= canvasW) continue;
                if (trans && ii == (transIndex & 0xFF)) continue;
                int rgb = act[ii];
                // act is 0xRRGGBB, force alpha=255
                canvas[rowOff + cx] = 0xFF000000 | rgb;
            }
        }
    }

    private static void compositeInterlaced(int[] canvas, int canvasW, int canvasH,
                                            int ix, int iy, int iw, int ih,
                                            byte[] idx, int[] act,
                                            boolean trans, int transIndex) {
        // Interlace passes: (start, step)
        int[] starts = {0, 4, 2, 1};
        int[] steps  = {8, 8, 4, 2};
        int p = 0;
        for (int pass = 0; pass < 4; pass++) {
            for (int y = starts[pass]; y < ih; y += steps[pass]) {
                int cy = iy + y;
                if (cy < 0 || cy >= canvasH) { p += iw; continue; }
                int rowOff = cy * canvasW;
                for (int x = 0; x < iw; x++) {
                    int cx = ix + x;
                    int ii = idx[p++] & 0xFF;
                    if (cx < 0 || cx >= canvasW) continue;
                    if (trans && ii == (transIndex & 0xFF)) continue;
                    int rgb = act[ii];
                    canvas[rowOff + cx] = 0xFF000000 | rgb;
                }
            }
        }
    }

    private static void clearRect(int[] canvas, int canvasW, int canvasH, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(canvasW, x + w);
        int y1 = Math.min(canvasH, y + h);
        for (int yy = y0; yy < y1; yy++) {
            int off = yy * canvasW;
            for (int xx = x0; xx < x1; xx++) {
                canvas[off + xx] = 0; // transparent
            }
        }
    }

    private static int[] readColorTable(Reader r, int size) throws IOException {
        int[] table = new int[size];
        for (int i = 0; i < size; i++) {
            int rr = r.readU8();
            int gg = r.readU8();
            int bb = r.readU8();
            table[i] = ((rr & 0xFF) << 16) | ((gg & 0xFF) << 8) | (bb & 0xFF);
        }
        return table;
    }

    private static void skipSubBlocks(Reader r) throws IOException {
        while (r.hasRemaining()) {
            int sz = r.readU8();
            if (sz == 0) break;
            r.skip(sz);
        }
    }

    private static byte[] readSubBlocksBytes(Reader r) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(1024);
        while (r.hasRemaining()) {
            int sz = r.readU8();
            if (sz == 0) break;
            byte[] tmp = r.readBytes(sz);
            baos.write(tmp, 0, tmp.length);
        }
        return baos.toByteArray();
    }


    /**
     * Decode LZW stream directly from GIF image data sub-blocks without concatenating
     * the whole compressed stream into a temporary byte[].
     *
     * This avoids large transient allocations that can trigger OOM on Android.
     *
     * @return number of pixels written into {@code out} (<= expectedSize)
     */
    private static int decodeLzwSubBlocks(Reader r, int minCodeSize, byte[] out, int expectedSize) throws IOException {
        if (out == null || expectedSize <= 0) return 0;
        if (minCodeSize < 2) minCodeSize = 2;

        SubBlockInput in = new SubBlockInput(r);

        int clearCode = 1 << minCodeSize;
        int endCode = clearCode + 1;
        int codeSize = minCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;
        int available = clearCode + 2;

        int[] prefix = new int[4096];
        byte[] suffix = new byte[4096];
        byte[] pixelStack = new byte[4097];

        for (int i = 0; i < clearCode; i++) {
            prefix[i] = 0;
            suffix[i] = (byte) i;
        }

        int outPos = 0;

        int datum = 0;
        int bits = 0;

        int first = 0;
        int top = 0;
        int oldCode = -1;

        while (outPos < expectedSize) {
            if (top == 0) {
                while (bits < codeSize) {
                    int nb = in.read();
                    if (nb < 0) {
                        // truncated image data; return what we have
                        if (outPos < expectedSize) Arrays.fill(out, outPos, expectedSize, (byte) 0);
                        return outPos;
                    }
                    datum |= (nb & 0xFF) << bits;
                    bits += 8;
                }

                int code = datum & codeMask;
                datum >>= codeSize;
                bits -= codeSize;

                if (code == clearCode) {
                    codeSize = minCodeSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clearCode + 2;
                    oldCode = -1;
                    continue;
                }
                if (code == endCode) {
                    break;
                }

                if (oldCode == -1) {
                    out[outPos++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }

                int inCode = code;
                if (code >= available) {
                    pixelStack[top++] = (byte) first;
                    code = oldCode;
                }

                while (code >= clearCode) {
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }
                first = suffix[code] & 0xFF;
                pixelStack[top++] = suffix[code];

                if (available < 4096) {
                    prefix[available] = oldCode;
                    suffix[available] = (byte) first;
                    available++;
                    if ((available & codeMask) == 0 && available < 4096) {
                        codeSize++;
                        codeMask = (1 << codeSize) - 1;
                    }
                }

                oldCode = inCode;
            }

            // Pop decoded bytes
            out[outPos++] = pixelStack[--top];
        }

        // We may have stopped early (end code / expectedSize). Drain the remaining sub-blocks
        // so the caller is positioned at the next block.
        in.drain();

        if (outPos < expectedSize) Arrays.fill(out, outPos, expectedSize, (byte) 0);
        return outPos;
    }

    private static final class SubBlockInput {
        private final Reader r;
        private int remaining = 0;
        private boolean done = false;

        private SubBlockInput(Reader r) {
            this.r = r;
        }

        private int read() throws IOException {
            if (done) return -1;
            while (remaining == 0) {
                if (!r.hasRemaining()) {
                    done = true;
                    return -1;
                }
                int sz = r.readU8();
                if (sz == 0) {
                    done = true;
                    return -1;
                }
                remaining = sz;
            }
            remaining--;
            return r.readU8();
        }

        private void drain() throws IOException {
            while (read() >= 0) {
                // consume
            }
        }
    }


    private static byte[] decodeLzw(byte[] data, int minCodeSize, int expectedSize) {
        if (data == null) return null;
        if (minCodeSize < 2) minCodeSize = 2;

        int clearCode = 1 << minCodeSize;
        int endCode = clearCode + 1;
        int codeSize = minCodeSize + 1;
        int codeMask = (1 << codeSize) - 1;
        int available = clearCode + 2;

        int[] prefix = new int[4096];
        byte[] suffix = new byte[4096];
        byte[] pixelStack = new byte[4097];

        for (int i = 0; i < clearCode; i++) {
            prefix[i] = 0;
            suffix[i] = (byte) i;
        }

        byte[] out = new byte[expectedSize];
        int outPos = 0;

        int datum = 0;
        int bits = 0;
        int dataPos = 0;

        int first = 0;
        int top = 0;
        int oldCode = -1;

        while (outPos < expectedSize) {
            if (top == 0) {
                while (bits < codeSize) {
                    if (dataPos >= data.length) {
                        return out; // truncated; return what we have
                    }
                    datum |= (data[dataPos] & 0xFF) << bits;
                    bits += 8;
                    dataPos++;
                }

                int code = datum & codeMask;
                datum >>= codeSize;
                bits -= codeSize;

                if (code == clearCode) {
                    codeSize = minCodeSize + 1;
                    codeMask = (1 << codeSize) - 1;
                    available = clearCode + 2;
                    oldCode = -1;
                    continue;
                }
                if (code == endCode) {
                    break;
                }

                if (oldCode == -1) {
                    out[outPos++] = suffix[code];
                    oldCode = code;
                    first = code;
                    continue;
                }

                int inCode = code;
                if (code >= available) {
                    pixelStack[top++] = (byte) first;
                    code = oldCode;
                }

                while (code >= clearCode) {
                    pixelStack[top++] = suffix[code];
                    code = prefix[code];
                }

                first = suffix[code] & 0xFF;
                pixelStack[top++] = (byte) first;

                if (available < 4096) {
                    prefix[available] = oldCode;
                    suffix[available] = (byte) first;
                    available++;
                    if ((available & codeMask) == 0 && available < 4096) {
                        codeSize++;
                        codeMask = (1 << codeSize) - 1;
                    }
                }

                oldCode = inCode;
            }

            // Pop stack
            top--;
            out[outPos++] = pixelStack[top];
        }

        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(16 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n > 0) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    // -------- byte reader --------

    private static final class Reader {
        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
            this.pos = 0;
        }

        boolean hasRemaining() {
            return pos < data.length;
        }

        int position() { return pos; }
        int length() { return data.length; }

        int readU8() throws IOException {
            if (pos >= data.length) throw new IOException("EOF");
            return data[pos++] & 0xFF;
        }

        int readU16LE() throws IOException {
            int lo = readU8();
            int hi = readU8();
            return (hi << 8) | lo;
        }

        void skip(int n) throws IOException {
            if (n < 0) return;
            pos += n;
            if (pos > data.length) throw new IOException("EOF");
        }

        byte[] readBytes(int n) throws IOException {
            if (n < 0) n = 0;
            if (pos + n > data.length) throw new IOException("EOF");
            byte[] out = new byte[n];
            System.arraycopy(data, pos, out, 0, n);
            pos += n;
            return out;
        }

        String readString(int n) throws IOException {
            byte[] b = readBytes(n);
            return new String(b, "ASCII");
        }

        /**
         * Best-effort recovery: scan forward to the next valid top-level block introducer.
         * Returns true if found (and moves the cursor), false otherwise.
         */
        boolean resyncToNextBlockIntroducer() {
            for (int i = pos; i < data.length; i++) {
                int v = data[i] & 0xFF;
                if (v == 0x21 || v == 0x2C || v == 0x3B) {
                    pos = i;
                    return true;
                }
            }
            pos = data.length;
            return false;
        }
    }
}
