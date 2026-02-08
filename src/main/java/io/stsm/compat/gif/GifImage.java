package io.stsm.compat.gif;

import java.util.List;

/** A decoded GIF: logical screen size + ordered frames. */
public final class GifImage {
    public final int width;
    public final int height;
    public final List<GifFrame> frames;

    public GifImage(int width, int height, List<GifFrame> frames) {
        this.width = width;
        this.height = height;
        this.frames = frames;
    }
}
