package io.stsm.compat.gif;

/** A decoded GIF frame, full-canvas ARGB pixels. */
public final class GifFrame {
    /** Delay in milliseconds for this frame (already clamped to a reasonable minimum). */
    public final int delayMs;

    /** Full-canvas ARGB pixels, length = width * height of the parent GifImage. */
    public final int[] argb;

    /** Frame rectangle (useful for debugging; not required by the loader patch). */
    public final int x, y, w, h;

    /** Disposal method for this frame (0/1/2/3). */
    public final int disposal;

    public GifFrame(int delayMs, int[] argb, int x, int y, int w, int h, int disposal) {
        this.delayMs = delayMs;
        this.argb = argb;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.disposal = disposal;
    }
}
