package io.stsm.compat;

import basemod.ReflectionHacks;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.graphics.glutils.ShaderProgram;
import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireRawPatch;
import com.esotericsoftware.spine.Skeleton;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.AbstractCreature;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import javassist.CtBehavior;

import java.lang.reflect.Field;

/**
 * RyoikiTenkai Android compat:
 * Keep After Image visual effect, but render it through a guarded path that always restores batch/FBO state.
 * This prevents "UI disappears and input locks up" when the original effect path breaks rendering state.
 */
public class RyoikiTenkaiAfterImageCompat {
    private static final String RYOIKI_MOD_ID = "RyoikiTenkai";
    private static final String AFTER_IMAGE_EFFECT_CLASS = "sts.ryoikitenkai.vfx.green.AfterImageEffect";
    private static final boolean DEBUG = true;
    private static volatile Boolean androidRuntime;
    private static volatile boolean loggedOnce;
    private static volatile long safeRenderCalls;
    private static volatile long guardedRenderCalls;
    private static volatile long guardedRenderFailures;
    private static volatile long compositeFailures;
    private static volatile long restoreMismatchCount;
    private static volatile long dungeonRenderTicks;
    private static volatile long simpleGhostRenders;
    private static volatile long nearZeroColorLogs;

    private static boolean shouldApply() {
        if (!isAndroidRuntime()) return false;
        try {
            return Loader.isModLoaded(RYOIKI_MOD_ID);
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static boolean isAndroidRuntime() {
        Boolean cached = androidRuntime;
        if (cached != null) return cached.booleanValue();
        boolean value;
        try {
            Class.forName("android.os.Build");
            value = true;
        } catch (Throwable ignored) {
            value = false;
        }
        androidRuntime = Boolean.valueOf(value);
        return value;
    }

    @SpirePatch(
            cls = AFTER_IMAGE_EFFECT_CLASS,
            method = "render",
            paramtypez = { SpriteBatch.class },
            optional = true
    )
    public static class SafeRenderHook {
        @SpireRawPatch
        public static void raw(CtBehavior behavior) throws Exception {
            if (behavior == null) return;
            behavior.insertBefore(
                    "if (io.stsm.compat.RyoikiTenkaiAfterImageCompat.shouldApplyRaw()) {" +
                            " io.stsm.compat.RyoikiTenkaiAfterImageCompat.safeRenderFromPatched(this, $1);" +
                            " return;" +
                            "}"
            );
        }
    }

    public static boolean shouldApplyRaw() {
        return shouldApply();
    }

    public static void safeRenderFromPatched(Object effectInstance, SpriteBatch sb) {
        safeRenderCalls++;
        if (!loggedOnce) {
            loggedOnce = true;
            SRFLog.i("[RYOIKI] Android safe render enabled for After Image effect.");
        }
        if (DEBUG && (safeRenderCalls <= 8 || (safeRenderCalls % 120L) == 0L)) {
            d("safeRender start calls=" + safeRenderCalls + " sb=" + batchState(sb));
        }
        float offset = readInstanceFloat(effectInstance, "offset", 0f);
        float offsetY = 6f * com.megacrit.cardcrawl.core.Settings.scale;
        Color c1 = readStaticColor("COLOR1", new Color(0.678f, 0.922f, 0.678f, 0.6f));
        Color c2 = readStaticColor("COLOR2", new Color(1f, 1f, 0.678f, 0.6f));

        guardedRender(sb, c1, -offset, offsetY);
        guardedRender(sb, c2, offset, offsetY);
    }

    private static void guardedRender(SpriteBatch sb, Color color, float offsetX, float offsetY) {
        guardedRenderCalls++;
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || sb == null || color == null) return;

        final boolean preDrawing = safeIsDrawing(sb);
        final Color preColor = safeGetColorCopy(sb);
        final int preSrc = safeGetBlendSrc(sb);
        final int preDst = safeGetBlendDst(sb);
        final ShaderProgram preShader = safeGetShader(sb);

        if (DEBUG && (guardedRenderCalls <= 10 || (guardedRenderCalls % 200L) == 0L)) {
            d("guardedRender enter calls=" + guardedRenderCalls
                    + " preDrawing=" + preDrawing
                    + " off=(" + offsetX + "," + offsetY + ")"
                    + " screenUp=" + AbstractDungeon.isScreenUp
                    + " screen=" + String.valueOf(AbstractDungeon.screen));
        }

        if (player.img != null) {
            try {
                renderTextureBranch(player, sb, color, offsetX, offsetY);
            } catch (Throwable t) {
                guardedRenderFailures++;
                SRFLog.e("[RYOIKI] texture branch render failed", t);
            } finally {
                restoreBatchState(sb, preDrawing, preColor, preSrc, preDst, preShader, "texture");
            }
            return;
        }

        // FBO-free fallback for spine-only players on Android (avoids UI state corruption risk).
        if (player.img == null) {
            try {
                renderSimpleGhost(player, sb, color, offsetX, offsetY);
            } catch (Throwable t) {
                guardedRenderFailures++;
                SRFLog.e("[RYOIKI] simple ghost render failed", t);
            } finally {
                restoreBatchState(sb, preDrawing, preColor, preSrc, preDst, preShader, "simple-ghost");
            }
            return;
        }

        FrameBuffer shadowBuffer = getOrCreateShadowBuffer();
        if (shadowBuffer == null) {
            if (DEBUG) d("guardedRender shadowBuffer is null");
            restoreBatchState(sb, preDrawing, preColor, preSrc, preDst, preShader, "null-fbo");
            return;
        }

        boolean psbBegun = false;
        boolean fboBegun = false;
        try {
            if (sb.isDrawing()) {
                sb.end();
            }

            shadowBuffer.begin();
            fboBegun = true;

            Gdx.gl.glClearColor(0f, 0f, 0f, 0f);
            Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
            Gdx.gl.glColorMask(true, true, true, true);

            Skeleton skeleton = ReflectionHacks.getPrivate(player, AbstractCreature.class, "skeleton");
            if (skeleton == null || player.state == null || AbstractCreature.sr == null || CardCrawlGame.psb == null) {
                return;
            }

            player.state.apply(skeleton);
            skeleton.updateWorldTransform();
            skeleton.setPosition(offsetX + player.drawX + player.animX, offsetY + player.drawY + player.animY);
            skeleton.setFlip(player.flipHorizontal, player.flipVertical);

            PolygonSpriteBatch psb = CardCrawlGame.psb;
            psb.begin();
            psbBegun = true;
            psb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
            AbstractCreature.sr.draw(psb, skeleton);
            psb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
            psb.end();
            psbBegun = false;
        } catch (Throwable t) {
            guardedRenderFailures++;
            SRFLog.e("[RYOIKI] guarded afterimage render failed", t);
        } finally {
            if (psbBegun) {
                try {
                    CardCrawlGame.psb.end();
                } catch (Throwable ignored) {
                }
            }
            if (fboBegun) {
                try {
                    shadowBuffer.end();
                } catch (Throwable ignored) {
                }
            }
            try {
                if (preDrawing && !sb.isDrawing()) {
                    sb.begin();
                }
            } catch (Throwable ignored) {
            }
        }

        boolean beganForComposite = false;
        try {
            if (!sb.isDrawing()) {
                sb.begin();
                beganForComposite = true;
            }
            sb.setColor(color);
            sb.draw(
                    shadowBuffer.getColorBufferTexture(),
                    0f,
                    0f,
                    (float) Gdx.graphics.getWidth(),
                    (float) Gdx.graphics.getHeight(),
                    0,
                    0,
                    Gdx.graphics.getWidth(),
                    Gdx.graphics.getHeight(),
                    false,
                    true
            );
            sb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        } catch (Throwable t) {
            compositeFailures++;
            SRFLog.e("[RYOIKI] guarded afterimage composite failed", t);
        } finally {
            if (beganForComposite) {
                try {
                    sb.end();
                } catch (Throwable ignored) {
                }
            }
            restoreBatchState(sb, preDrawing, preColor, preSrc, preDst, preShader, "skeleton");
        }
    }

    private static void renderTextureBranch(AbstractPlayer player, SpriteBatch sb, Color color, float offsetX, float offsetY) {
        Texture img = player.img;
        if (img == null) return;
        sb.setColor(color);
        sb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        sb.draw(
                img,
                offsetX + player.drawX - (img.getWidth() * com.megacrit.cardcrawl.core.Settings.scale / 2f) + player.animX,
                offsetY + player.drawY,
                img.getWidth() * com.megacrit.cardcrawl.core.Settings.scale,
                img.getHeight() * com.megacrit.cardcrawl.core.Settings.scale,
                0,
                0,
                img.getWidth(),
                img.getHeight(),
                player.flipHorizontal,
                player.flipVertical
        );
        sb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static void renderSimpleGhost(AbstractPlayer player, SpriteBatch sb, Color color, float offsetX, float offsetY) {
        Skeleton skeleton = null;
        try {
            skeleton = ReflectionHacks.getPrivate(player, AbstractCreature.class, "skeleton");
        } catch (Throwable ignored) {
        }

        if (skeleton != null && player.state != null && AbstractCreature.sr != null && CardCrawlGame.psb != null) {
            boolean endedSb = false;
            boolean begunPsb = false;
            Color old = null;
            float oldX = 0f;
            float oldY = 0f;
            try {
                if (sb.isDrawing()) {
                    sb.end();
                    endedSb = true;
                }

                player.state.apply(skeleton);
                skeleton.updateWorldTransform();

                old = skeleton.getColor().cpy();
                oldX = skeleton.getX();
                oldY = skeleton.getY();

                Color tint = color.cpy();
                tint.a = Math.min(0.30f, Math.max(0.08f, color.a));
                skeleton.setColor(tint);
                skeleton.setPosition(player.drawX + player.animX + offsetX, player.drawY + player.animY + offsetY);
                skeleton.setFlip(player.flipHorizontal, player.flipVertical);

                PolygonSpriteBatch psb = CardCrawlGame.psb;
                psb.begin();
                begunPsb = true;
                psb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
                AbstractCreature.sr.draw(psb, skeleton);
                psb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
                psb.end();
                begunPsb = false;

                if (old != null) skeleton.setColor(old);
                skeleton.setPosition(oldX, oldY);
            } catch (Throwable t) {
                SRFLog.e("[RYOIKI] simple ghost skeleton render failed; fallback to quad", t);
                renderSimpleGhostQuad(player, sb, color, offsetX, offsetY);
            } finally {
                if (begunPsb) {
                    try {
                        CardCrawlGame.psb.end();
                    } catch (Throwable ignored) {
                    }
                }
                if (endedSb) {
                    try {
                        sb.begin();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } else {
            renderSimpleGhostQuad(player, sb, color, offsetX, offsetY);
        }

        simpleGhostRenders++;
        if (DEBUG && (simpleGhostRenders <= 20 || (simpleGhostRenders % 300L) == 0L)) {
            d("simpleGhost render count=" + simpleGhostRenders + " mode=skeleton");
        }
    }

    private static void renderSimpleGhostQuad(AbstractPlayer player, SpriteBatch sb, Color color, float offsetX, float offsetY) {
        Texture white = ImageMaster.WHITE_SQUARE_IMG;
        if (white == null) return;

        float width = 180f * com.megacrit.cardcrawl.core.Settings.scale;
        float height = 240f * com.megacrit.cardcrawl.core.Settings.scale;
        if (player.hb != null) {
            width = player.hb.width * 1.15f;
            height = player.hb.height * 1.25f;
        }

        float x = player.drawX + player.animX + offsetX - width * 0.5f;
        float y = player.drawY + player.animY + offsetY - height * 0.08f;

        Color tint = color.cpy();
        tint.a = Math.min(0.22f, Math.max(0.08f, color.a));

        sb.setColor(tint);
        sb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE);
        sb.draw(white, x, y, width, height);
        sb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
    }

    private static FrameBuffer getOrCreateShadowBuffer() {
        try {
            Class<?> c = Class.forName(AFTER_IMAGE_EFFECT_CLASS);
            Field f = c.getDeclaredField("shadowBuffer");
            f.setAccessible(true);
            Object v = f.get(null);
            FrameBuffer current = (v instanceof FrameBuffer) ? (FrameBuffer) v : null;

            int w = Gdx.graphics.getWidth();
            int h = Gdx.graphics.getHeight();
            if (w <= 0 || h <= 0) return current;

            if (current != null && (current.getWidth() != w || current.getHeight() != h)) {
                try {
                    current.dispose();
                } catch (Throwable ignored) {
                }
                current = null;
            }

            if (current == null) {
                current = new FrameBuffer(Pixmap.Format.RGBA8888, w, h, false, false);
                f.set(null, current);
            }
            return current;
        } catch (Throwable t) {
            SRFLog.e("[RYOIKI] failed to access/create shadowBuffer", t);
            return null;
        }
    }

    private static float readInstanceFloat(Object instance, String fieldName, float fallback) {
        if (instance == null) return fallback;
        try {
            Field f = instance.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.getFloat(instance);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static Color readStaticColor(String fieldName, Color fallback) {
        try {
            Class<?> c = Class.forName(AFTER_IMAGE_EFFECT_CLASS);
            Field f = c.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(null);
            if (v instanceof Color) {
                return ((Color) v).cpy();
            }
        } catch (Throwable ignored) {
        }
        return fallback.cpy();
    }

    private static boolean safeIsDrawing(SpriteBatch sb) {
        try {
            return sb != null && sb.isDrawing();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Color safeGetColorCopy(SpriteBatch sb) {
        try {
            if (sb == null || sb.getColor() == null) return Color.WHITE.cpy();
            return sb.getColor().cpy();
        } catch (Throwable ignored) {
            return Color.WHITE.cpy();
        }
    }

    private static int safeGetBlendSrc(SpriteBatch sb) {
        try {
            if (sb == null) return GL20.GL_SRC_ALPHA;
            return sb.getBlendSrcFunc();
        } catch (Throwable ignored) {
            return GL20.GL_SRC_ALPHA;
        }
    }

    private static int safeGetBlendDst(SpriteBatch sb) {
        try {
            if (sb == null) return GL20.GL_ONE_MINUS_SRC_ALPHA;
            return sb.getBlendDstFunc();
        } catch (Throwable ignored) {
            return GL20.GL_ONE_MINUS_SRC_ALPHA;
        }
    }

    private static ShaderProgram safeGetShader(SpriteBatch sb) {
        try {
            if (sb == null) return null;
            return sb.getShader();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void restoreBatchState(SpriteBatch sb, boolean drawing, Color color, int src, int dst, ShaderProgram shader, String stage) {
        if (sb == null) return;
        try {
            boolean now = sb.isDrawing();
            if (drawing && !now) {
                sb.begin();
            } else if (!drawing && now) {
                sb.end();
            }
        } catch (Throwable t) {
            SRFLog.e("[RYOIKI] restore drawing state failed stage=" + stage, t);
        }

        try {
            // Failsafe: keep default shader to avoid leaking mod shader states into UI draw pipeline.
            sb.setShader(null);
        } catch (Throwable t) {
            SRFLog.e("[RYOIKI] restore shader failed stage=" + stage, t);
        }

        try {
            // Failsafe: force normal alpha blending for UI.
            sb.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);
        } catch (Throwable t) {
            SRFLog.e("[RYOIKI] restore blend failed stage=" + stage, t);
        }

        try {
            // Failsafe: force opaque white to avoid alpha=0 carry-over causing invisible UI.
            sb.setColor(Color.WHITE);
        } catch (Throwable t) {
            SRFLog.e("[RYOIKI] restore color failed stage=" + stage, t);
        }

        if (DEBUG) {
            try {
                if (sb.isDrawing() != drawing) {
                    restoreMismatchCount++;
                    d("restore mismatch stage=" + stage + " expectedDrawing=" + drawing + " actual=" + sb.isDrawing()
                            + " mismatchCount=" + restoreMismatchCount + " sb=" + batchState(sb));
                }
                if (color != null && color.a <= 0.01f) {
                    nearZeroColorLogs++;
                    if (nearZeroColorLogs <= 30 || (nearZeroColorLogs % 200L) == 0L) {
                    d("preColor alpha was near zero stage=" + stage + " preColor=" + color);
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static String batchState(SpriteBatch sb) {
        if (sb == null) return "null";
        try {
            Color c = sb.getColor();
            ShaderProgram sh = sb.getShader();
            return "drawing=" + sb.isDrawing()
                    + ",blend=" + sb.getBlendSrcFunc() + "/" + sb.getBlendDstFunc()
                    + ",color=(" + c.r + "," + c.g + "," + c.b + "," + c.a + ")"
                    + ",shader=" + (sh == null ? "null" : sh.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(sh)));
        } catch (Throwable t) {
            return "state-error=" + String.valueOf(t);
        }
    }

    private static void d(String msg) {
        SRFLog.i("[RYOIKI_DEBUG] " + msg);
    }

    @SpirePatch(
            clz = AbstractDungeon.class,
            method = "render",
            paramtypez = { SpriteBatch.class }
    )
    public static class DungeonRenderProbe {
        private static final ThreadLocal<Boolean> PREFIX_DRAWING = new ThreadLocal<Boolean>();

        @SpirePrefixPatch
        public static void Prefix(AbstractDungeon __instance, SpriteBatch sb) {
            if (!shouldApplyRaw() || !DEBUG) return;
            boolean drawing = safeIsDrawing(sb);
            PREFIX_DRAWING.set(Boolean.valueOf(drawing));

            dungeonRenderTicks++;
            if (dungeonRenderTicks <= 20 || (dungeonRenderTicks % 300L) == 0L) {
                d("dungeon.render prefix tick=" + dungeonRenderTicks
                        + " screenUp=" + AbstractDungeon.isScreenUp
                        + " screen=" + String.valueOf(AbstractDungeon.screen)
                        + " effects=" + sizeOf(AbstractDungeon.effectList)
                        + " top=" + sizeOf(AbstractDungeon.topLevelEffects)
                        + " sb=" + batchState(sb)
                        + " safeCalls=" + safeRenderCalls
                        + " guardCalls=" + guardedRenderCalls
                        + " guardFail=" + guardedRenderFailures
                        + " compFail=" + compositeFailures
                        + " restoreMismatch=" + restoreMismatchCount);
            }
        }

        @SpirePostfixPatch
        public static void Postfix(AbstractDungeon __instance, SpriteBatch sb) {
            if (!shouldApplyRaw() || !DEBUG) return;
            Boolean expectedObj = PREFIX_DRAWING.get();
            PREFIX_DRAWING.remove();
            if (expectedObj == null) return;

            boolean expected = expectedObj.booleanValue();
            boolean actual = safeIsDrawing(sb);
            if (expected != actual) {
                d("dungeon.render drawing changed unexpectedly expected=" + expected + " actual=" + actual + " sb=" + batchState(sb));
            }
        }
    }

    private static int sizeOf(java.util.ArrayList<?> list) {
        return list == null ? -1 : list.size();
    }
}
