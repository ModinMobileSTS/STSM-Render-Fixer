package io.stsm.compat;

import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireRawPatch;
import javassist.CtBehavior;
import javassist.CtMethod;

/**
 * STSM Render Fixer - ImageIO/GIF compatibility for Android (STSM).
 *
 * Many desktop mods decode GIFs via javax.imageio + java.awt.image.BufferedImage,
 * which are not available on Android. We patch common GIF helper implementations
 * (e.g. utils.GifHelper) to use a lightweight pure-Java GIF decoder and upload
 * frames into libGDX textures.
 */
@SpireInitializer
public class ImageIOCompat {

    public static void initialize() {
        try {
            System.out.println("[SRF] ImageIOCompat init (gif-v23-oomfix)");
        } catch (Throwable ignored) {
        }
    }

    /**
     * Patch: utils.GifHelper#loadGif(String):boolean
     */
    @SpirePatch(
            cls = "utils.GifHelper",
            method = "loadGif",
            paramtypez = { String.class },
            optional = true
    )
    public static class PatchGifHelperLoadGif {
        @SpireRawPatch
        public static void raw(CtBehavior behavior) throws Exception {
            if (!(behavior instanceof CtMethod)) return;
            CtMethod m = (CtMethod) behavior;

            // NOTE: keep the body simple (no lambdas/streams) for Javassist.
            // IMPORTANT: return true on failure, otherwise getGif() will retry every frame and stutter.
            String body =
                    "{\n" +
                    "  final String __key = $1;\n" +
                    "  try {\n" +
                    "    String __path = __key;\n" +
                    "    if (__path != null) {\n" +
                    "      String __lower = __path.toLowerCase();\n" +
                    "      if (!__lower.endsWith(\".gif\")) __path = __path + \".gif\";\n" +
                    "      __path = __path.replace('\\\\', '/');\n" +
                    "      if (__path.startsWith(\"/\")) __path = __path.substring(1);\n" +
                    "    }\n" +
                    "\n" +
                    "    com.badlogic.gdx.files.FileHandle __fh = null;\n" +
                    "    if (__path != null) __fh = com.badlogic.gdx.Gdx.files.internal(__path);\n" +
                    "    if (__fh == null || !__fh.exists()) {\n" +
                    "      if (__path != null) __fh = com.badlogic.gdx.Gdx.files.local(__path);\n" +
                    "    }\n" +
                    "\n" +
                    "    if (__fh == null || !__fh.exists()) {\n" +
                    "      java.lang.System.out.println(\"[SRF_GIF] file not found key=\" + __key + \" path=\" + __path);\n" +
                    "      gifTextures.put(__key, new java.util.ArrayList(0));\n" +
                    "      gifDelays.put(__key, new int[]{1000});\n" +
                    "      startTimes.put(__key, java.lang.Long.valueOf(java.lang.System.nanoTime()));\n" +
                    "      return true;\n" +
                    "    }\n" +
                    "\n" +
                    "    final int __MAX_FRAMES = 60;\n" +
                    "    final int __PREFER_MAX_DIM = 1024;\n" +
                    "    final int __FALLBACK_MAX_DIM = 512;\n" +
                    "\n" +
                    "    java.lang.Object[] __res = null;\n" +
                    "    try {\n" +
                    "      java.io.InputStream __is1 = __fh.read();\n" +
                    "      try {\n" +
                    "        __res = io.stsm.compat.gif.GdxGifLoader.load(__is1, __MAX_FRAMES, __PREFER_MAX_DIM);\n" +
                    "      } finally {\n" +
                    "        if (__is1 != null) __is1.close();\n" +
                    "      }\n" +
                    "    } catch (java.lang.OutOfMemoryError __oom) {\n" +
                    "      __res = null;\n" +
                    "    }\n" +
                    "\n" +
                    "    if (__res == null) {\n" +
                    "      try {\n" +
                    "        java.io.InputStream __is2 = __fh.read();\n" +
                    "        try {\n" +
                    "          __res = io.stsm.compat.gif.GdxGifLoader.load(__is2, __MAX_FRAMES, __FALLBACK_MAX_DIM);\n" +
                    "        } finally {\n" +
                    "          if (__is2 != null) __is2.close();\n" +
                    "        }\n" +
                    "      } catch (java.lang.OutOfMemoryError __oom2) {\n" +
                    "        __res = null;\n" +
                    "      }\n" +
                    "    }\n" +
                    "\n" +
                    "    if (__res == null) {\n" +
                    "      java.lang.System.out.println(\"[SRF_GIF] decode failed key=\" + __key + \" path=\" + __path);\n" +
                    "      gifTextures.put(__key, new java.util.ArrayList(0));\n" +
                    "      gifDelays.put(__key, new int[]{1000});\n" +
                    "      startTimes.put(__key, java.lang.Long.valueOf(java.lang.System.nanoTime()));\n" +
                    "      return true;\n" +
                    "    }\n" +
                    "\n" +
                    "    java.util.ArrayList __texList = (java.util.ArrayList)__res[0];\n" +
                    "    int[] __delays = (int[])__res[1];\n" +
                    "    if (__texList == null || __texList.isEmpty()) {\n" +
                    "      java.lang.System.out.println(\"[SRF_GIF] decode empty key=\" + __key + \" path=\" + __path);\n" +
                    "      gifTextures.put(__key, new java.util.ArrayList(0));\n" +
                    "      gifDelays.put(__key, new int[]{1000});\n" +
                    "      startTimes.put(__key, java.lang.Long.valueOf(java.lang.System.nanoTime()));\n" +
                    "      return true;\n" +
                    "    }\n" +
                    "\n" +
                    "    if (__delays == null || __delays.length != __texList.size()) {\n" +
                    "      int __n = __texList.size();\n" +
                    "      int[] __tmp = new int[__n];\n" +
                    "      for (int __i = 0; __i < __n; __i++) __tmp[__i] = 80;\n" +
                    "      __delays = __tmp;\n" +
                    "      java.lang.System.out.println(\"[SRF_GIF] rebuilt delays: n=\" + __n + \" key=\" + __key);\n" +
                    "    }\n" +
                    "\n" +
                    "    for (int __i = 0; __i < __delays.length; __i++) __delays[__i] = java.lang.Math.max(20, __delays[__i]);\n" +
                    "    int __sum = 0;\n" +
                    "    for (int __i = 0; __i < __delays.length; __i++) __sum += __delays[__i];\n" +
                    "    java.lang.System.out.println(\"[SRF_GIF] loaded gif: key=\" + __key + \" frames=\" + __texList.size() + \" sumMs=\" + __sum + \" path=\" + __path);\n" +
                    "\n" +
                    "    gifTextures.put(__key, __texList);\n" +
                    "    gifDelays.put(__key, __delays);\n" +
                    "    startTimes.put(__key, java.lang.Long.valueOf(java.lang.System.nanoTime()));\n" +
                    "    io.stsm.compat.gif.GdxGifLoader.trimCache(gifTextures, gifDelays, startTimes, __key, 2);\n" +
                    "    return true;\n" +
                    "  } catch (java.lang.Throwable __t) {\n" +
                    "    try { __t.printStackTrace(); } catch (java.lang.Throwable __ignore) {}\n" +
                    "    try {\n" +
                    "      gifTextures.put(__key, new java.util.ArrayList(0));\n" +
                    "      gifDelays.put(__key, new int[]{1000});\n" +
                    "      startTimes.put(__key, java.lang.Long.valueOf(java.lang.System.nanoTime()));\n" +
                    "    } catch (java.lang.Throwable __ignore2) {}\n" +
                    "    return true;\n" +
                    "  }\n" +
                    "}\n";

            m.setBody(body);
        }
    }

    /**
     * Patch: utils.GifHelper#getGif(String):Texture
     *
     * Fixes the 'half frames' issue by computing total duration with the same delay clamp
     * used during frame selection, and guarantees a non-null return on failures.
     */
    @SpirePatch(
            cls = "utils.GifHelper",
            method = "getGif",
            paramtypez = { String.class },
            optional = true
    )
    public static class PatchGifHelperGetGif {
        @SpireRawPatch
        public static void raw(CtBehavior behavior) throws Exception {
            if (!(behavior instanceof CtMethod)) return;
            CtMethod m = (CtMethod) behavior;

            String body =
                    "{\n" +
                    "  final String __key = $1;\n" +
                    "  if (!gifTextures.containsKey(__key)) {\n" +
                    "    boolean __ok = loadGif(__key);\n" +
                    "    if (!__ok) return io.stsm.compat.gif.GifFallback.get();\n" +
                    "  }\n" +
                    "  java.util.List __list = (java.util.List)gifTextures.get(__key);\n" +
                    "  int[] __delays = (int[])gifDelays.get(__key);\n" +
                    "  if (__list == null || __list.isEmpty()) return io.stsm.compat.gif.GifFallback.get();\n" +
                    "  java.lang.Long __st = (java.lang.Long)startTimes.get(__key);\n" +
                    "  if (__st == null) { startTimes.put(__key, java.lang.Long.valueOf(java.lang.System.nanoTime())); __st = (java.lang.Long)startTimes.get(__key); }\n" +
                    "  long __start = __st.longValue();\n" +
                    "  long __elapsedNs = java.lang.System.nanoTime() - __start;\n" +
                    "  int __idx = 0;\n" +
                    "  if (__delays != null && __delays.length > 0) {\n" +
                    "    long __ms = __elapsedNs / 1000000L;\n" +
                    "    int __total = 0;\n" +
                    "    for (int __i = 0; __i < __delays.length; __i++) __total += java.lang.Math.max(20, __delays[__i]);\n" +
                    "    if (__total <= 0) __total = __delays.length * 80;\n" +
                    "    long __t = __ms % (long)__total;\n" +
                    "    int __acc = 0;\n" +
                    "    for (int __i = 0; __i < __delays.length; __i++) {\n" +
                    "      __acc += java.lang.Math.max(20, __delays[__i]);\n" +
                    "      if (__t < (long)__acc) { __idx = __i; break; }\n" +
                    "    }\n" +
                    "  } else {\n" +
                    "    __idx = (int)(((float)__elapsedNs / 1.0E9f) / 0.08f);\n" +
                    "    __idx = __idx % __list.size();\n" +
                    "  }\n" +
                    "  if (__idx < 0) __idx = 0;\n" +
                    "  if (__idx >= __list.size()) __idx = __list.size() - 1;\n" +
                    "  com.badlogic.gdx.graphics.Texture __tx = (com.badlogic.gdx.graphics.Texture)__list.get(__idx);\n" +
                    "  if (__tx == null) return io.stsm.compat.gif.GifFallback.get();\n" +
                    "  return __tx;\n" +
                    "}\n";

            m.setBody(body);
        }
    }
}
