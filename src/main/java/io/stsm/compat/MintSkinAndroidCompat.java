package io.stsm.compat;

import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireRawPatch;
import javassist.CtBehavior;
import javassist.CtClass;
import javassist.CtMethod;

/**
 * A tiny compatibility patch mod for Android runtimes:
 * - Some desktop mods call javax.imageio.ImageIO (not available on Android).
 * - Even if you short-circuit at runtime, Javassist 'insertBefore' can fail because it rebuilds stackmaps
 *   and tries to resolve javax.imageio.* types.
 *
 * So we use setBody(...) to REPLACE the method implementation, avoiding stackmap resolution of the original code.
 */
@SpireInitializer
public class MintSkinAndroidCompat {

    public static void initialize() {
        // Required by ModTheSpire, can be empty.
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
            // loadGif(String) : boolean
            // On Android, pretend "gif not loaded" to avoid crash.
            m.setBody("{ return false; }");
        }
    }

    @SpirePatch(
            cls = "utils.GifHelper",
            method = "getGif",
            optional = true
    )
    public static class PatchGifHelperGetGif {
        @SpireRawPatch
        public static void raw(CtBehavior behavior) throws Exception {
            if (!(behavior instanceof CtMethod)) return;
            CtMethod m = (CtMethod) behavior;

            // We don't know exact signature across versions, so return a safe default based on return type.
            CtClass rt = m.getReturnType();
            m.setBody(defaultReturnStatement(rt));
        }

        private static String defaultReturnStatement(CtClass rt) {
            if (CtClass.voidType.equals(rt)) {
                return "{ return; }";
            }
            if (CtClass.booleanType.equals(rt)) {
                return "{ return false; }";
            }
            if (CtClass.byteType.equals(rt) || CtClass.charType.equals(rt) ||
                CtClass.shortType.equals(rt) || CtClass.intType.equals(rt)) {
                return "{ return 0; }";
            }
            if (CtClass.longType.equals(rt)) {
                return "{ return 0L; }";
            }
            if (CtClass.floatType.equals(rt)) {
                return "{ return 0.0f; }";
            }
            if (CtClass.doubleType.equals(rt)) {
                return "{ return 0.0d; }";
            }
            // Any object type
            return "{ return null; }";
        }
    }
}
