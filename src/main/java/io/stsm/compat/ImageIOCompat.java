package io.stsm.compat;

import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireRawPatch;
import javassist.CtBehavior;
import javassist.CtMethod;

@SpireInitializer
public class ImageIOCompat {

    public static void initialize() {
        try {
            System.out.println("[SRF] ImageIOCompat init (gif-v29-animate-while-loading)");
        } catch (Throwable ignored) {
        }
    }

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
                    "    io.stsm.compat.gif.GdxGifLoader.loadAsync(__fh, __key, gifTextures, gifDelays, startTimes, __MAX_FRAMES, __PREFER_MAX_DIM, __FALLBACK_MAX_DIM);\n" +
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
                    "  int __size = __list.size();\n" +
                    "  java.lang.Long __st = (java.lang.Long)startTimes.get(__key);\n" +
                    "  if (__st == null) { startTimes.put(__key, java.lang.Long.valueOf(java.lang.System.nanoTime())); __st = (java.lang.Long)startTimes.get(__key); }\n" +
                    "  long __elapsedNs = java.lang.System.nanoTime() - __st.longValue();\n" +
                    "  int __idx = 0;\n" +
                    "  int __dlen = (__delays == null) ? 0 : __delays.length;\n" +
                    "  if (__dlen > 0 && __dlen == __size) {\n" +
                    "    long __ms = __elapsedNs / 1000000L;\n" +
                    "    int __total = 0;\n" +
                    "    for (int __i = 0; __i < __dlen; __i++) __total += java.lang.Math.max(20, __delays[__i]);\n" +
                    "    if (__total <= 0) __total = __dlen * 80;\n" +
                    "    long __t = __ms % (long)__total;\n" +
                    "    int __acc = 0;\n" +
                    "    for (int __i = 0; __i < __dlen; __i++) {\n" +
                    "      __acc += java.lang.Math.max(20, __delays[__i]);\n" +
                    "      if (__t < (long)__acc) { __idx = __i; break; }\n" +
                    "    }\n" +
                    "  } else if (__size > 1) {\n" +
                    "    // Loading phase: frames may already be available while delays[] is still placeholder.\n" +
                    "    long __ms = __elapsedNs / 1000000L;\n" +
                    "    long __step = __ms / 80L;\n" +
                    "    __idx = (int)(__step % (long)__size);\n" +
                    "    io.stsm.compat.gif.GdxGifLoader.debugTick(__key, __size, __dlen);\n" +
                    "  } else {\n" +
                    "    __idx = 0;\n" +
                    "  }\n" +
                    "  if (__idx < 0) __idx = 0;\n" +
                    "  if (__idx >= __size) __idx = __idx % __size;\n" +
                    "  com.badlogic.gdx.graphics.Texture __tx = (com.badlogic.gdx.graphics.Texture)__list.get(__idx);\n" +
                    "  if (__tx == null) return io.stsm.compat.gif.GifFallback.get();\n" +
                    "  return __tx;\n" +
                    "}\n";

            m.setBody(body);
        }
    }
}
