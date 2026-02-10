package io.stsm.compat;

import com.badlogic.gdx.graphics.Texture;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;

/**
 * Compat for mods that ship their own utils.GifRenderHelper implementation that uses javax.imageio.ImageIO
 * (not available on Android). We redirect calls to utils.GifHelper, which STSM Render Fixer already patches.
 *
 * IMPORTANT:
 * - utils.GifRenderHelper is not part of the base game; it only exists when certain mods are loaded.
 * - Therefore these patches MUST be optional, otherwise ModTheSpire will crash during patch injection
 *   when the class is absent.
 */
public class GifRenderHelperCompat {

    // --- utils.GifRenderHelper.loadGif(String) : boolean ---
    @SpirePatch(
            cls = "utils.GifRenderHelper",
            method = "loadGif",
            paramtypez = { String.class },
            optional = true
    )
    public static class LoadGif {
        @SpirePrefixPatch
        public static SpireReturn<Boolean> Prefix(String key) {
            try {
                // Delegate to utils.GifHelper.getGif which triggers loading and returns a non-null Texture
                // (or placeholder) when our compat is active.
                Texture t = callGifHelperGetGif(key);
                boolean ok = (t != null);
                if (!ok) {
                    System.out.println("[SRF_GIF] GifRenderHelperCompat.loadGif: GifHelper returned null for key=" + key);
                }
                return SpireReturn.Return(ok);
            } catch (Throwable ex) {
                System.out.println("[SRF_GIF] GifRenderHelperCompat.loadGif failed key=" + key + " ex=" + ex);
                return SpireReturn.Return(false);
            }
        }
    }

    // --- utils.GifRenderHelper.getGif(String) : Texture ---
    @SpirePatch(
            cls = "utils.GifRenderHelper",
            method = "getGif",
            paramtypez = { String.class },
            optional = true
    )
    public static class GetGif {
        @SpirePrefixPatch
        public static SpireReturn<Texture> Prefix(String key) {
            try {
                Texture t = callGifHelperGetGif(key);
                if (t == null) {
                    System.out.println("[SRF_GIF] GifRenderHelperCompat.getGif: null key=" + key);
                }
                return SpireReturn.Return(t);
            } catch (Throwable ex) {
                System.out.println("[SRF_GIF] GifRenderHelperCompat.getGif failed key=" + key + " ex=" + ex);
                return SpireReturn.Return(null);
            }
        }
    }

    private static Texture callGifHelperGetGif(String key) throws Exception {
        Class<?> c = Class.forName("utils.GifHelper");
        java.lang.reflect.Method m = c.getMethod("getGif", String.class);
        Object r = m.invoke(null, key);
        return (Texture) r;
    }
}
