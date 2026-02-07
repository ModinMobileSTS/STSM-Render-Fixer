package io.stsm.compat;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireRawPatch;
import javassist.CtBehavior;
import javassist.CannotCompileException;
import javassist.expr.ExprEditor;
import javassist.expr.FieldAccess;

/**
 * Haberdashery fix for Android/GLES:
 *
 * Haberdashery's AdjustRelic has a static initializer that creates:
 *   new FrameBuffer(Pixmap.Format.Alpha, Gdx.graphics.getWidth(), Gdx.graphics.getHeight(), false, false)
 *
 * On many GLES devices, Alpha-only textures are NOT renderable as an FBO color attachment, causing:
 *   IllegalStateException: frame buffer couldn't be constructed: incomplete attachment
 *
 * The reliable fix is to patch Haberdashery's static initializer (<clinit>) and rewrite:
 *   Pixmap.Format.Alpha -> Pixmap.Format.RGBA8888
 * before the FrameBuffer is constructed.
 *
 * Notes:
 * - Patch target is SpirePatch.STATICINITIALIZER (not FrameBuffer.<init>), because any code inserted into a
 *   constructor runs after super(), which is too late for GLFrameBuffer attachment building.
 * - Uses SpireRawPatch + ExprEditor (no PrefixPatch) to avoid signature resolution issues.
 * - Avoids anonymous classes/lambdas to prevent D8/R8 dropping synthetic $1 classes.
 */
@SpirePatch(
        cls = "com.evacipated.cardcrawl.mod.haberdashery.AdjustRelic",
        method = SpirePatch.STATICINITIALIZER,
        optional = true
)
public class HaberdasheryFboCompat {

    private static boolean shouldApply() {
        try {
            return Loader.isModLoaded("haberdashery");
        } catch (Throwable ignored) {
            // If Loader isn't accessible for some reason, prefer applying when target class exists.
            return true;
        }
    }

    @SpireRawPatch
    public static void raw(CtBehavior behavior) throws Exception {
        if (!shouldApply() || behavior == null) return;

        behavior.instrument(new AlphaToRgbaEditor());
    }

    /**
     * Replace Pixmap.Format.Alpha reads with Pixmap.Format.RGBA8888.
     * (Named inner class to avoid generating $1 synthetic classes.)
     */
    private static final class AlphaToRgbaEditor extends ExprEditor {
        @Override
        public void edit(FieldAccess f) throws CannotCompileException {
            if (f == null || !f.isReader()) return;

            // bytecode field owner uses '$' for inner classes
            if ("com.badlogic.gdx.graphics.Pixmap$Format".equals(f.getClassName())
                    && "Alpha".equals(f.getFieldName())) {
                f.replace("{ $_ = com.badlogic.gdx.graphics.Pixmap.Format.RGBA8888; }");
            }
        }
    }
}
