package io.stsm.compat;

import com.evacipated.cardcrawl.modthespire.Loader;
import com.evacipated.cardcrawl.modthespire.ModInfo;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLDecoder;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fix Haberdashery "mod does nothing" on Android:
 *
 * Haberdashery relies on java.nio.file + FSFileHandle to read:
 *   haberdashery/*.json, haberdashery/skeletons/**, haberdashery/masks/**
 * from either:
 *   - local folder "haberdashery/" (relative to working dir), or
 *   - the mod jar via a Zip FileSystem.
 *
 * On many Android runtimes, mounting the mod jar as a Zip FileSystem fails,
 * and the local folder doesn't exist by default -> database stays empty ->
 * supported relics won't be attached/rendered.
 *
 * This patch extracts Haberdashery's "haberdashery/" folder from the mod jar
 * into the local filesystem (relative path "haberdashery/") during mod init.
 *
 * No lambdas / anonymous classes (D8/R8 friendly).
 */
@SpirePatch(
        cls = "com.evacipated.cardcrawl.mod.haberdashery.HaberdasheryMod",
        method = "initialize",
        optional = true
)
public class HaberdasheryResourceCompat {

    private static final String HABERDASHERY_ID = "haberdashery";
    private static volatile boolean attempted = false;

    public static void Prefix() {
        SRFLog.i("HaberdasheryResourceCompat Prefix called. attempted=" + attempted);
        SRFLog.i("user.dir=" + System.getProperty("user.dir") + ", cwd=" + new File(".").getAbsolutePath());
        if (attempted) return;
        attempted = true;

        if (!isAndroidRuntime()) return;

        try {
            // If already present, don't re-extract.
            File marker = new File(HABERDASHERY_ID + File.separator + "shared.json");
            SRFLog.i("marker=" + marker.getAbsolutePath() + ", exists=" + marker.exists());
            if (marker.exists() && marker.isFile()) return;

            File jarFile = findHaberdasheryJar();
            SRFLog.i("haberdasheryJar=" + (jarFile == null ? "null" : jarFile.getAbsolutePath()));
            if (jarFile == null || !jarFile.exists()) return;

            extractHaberdasheryFolder(jarFile);
        } catch (Throwable t) {
            SRFLog.e("HaberdasheryResourceCompat exception in Prefix", t);
        }
    }

    private static boolean isAndroidRuntime() {
        try {
            Class.forName("android.os.Build");
            return true;
        } catch (Throwable t) {
            SRFLog.e("extractHaberdasheryFolder exception", t);
            return false;
        }
    }

    private static File findHaberdasheryJar() {
        try {
            ModInfo[] infos = Loader.MODINFOS;
            SRFLog.i("Loader.MODINFOS=" + (infos == null ? "null" : String.valueOf(infos.length)));
            if (infos == null) return null;

            for (int i = 0; i < infos.length; i++) {
                ModInfo info = infos[i];
                if (info == null) continue;

                String id = readStringField(info, "ID");
                if (!HABERDASHERY_ID.equals(id)) continue;

                URL jarUrl = readUrlField(info, "jarURL");
                if (jarUrl == null) return null;

                File f = urlToFile(jarUrl);
                if (f != null && f.exists()) return f;
            }
        } catch (Throwable t) {
            SRFLog.e("extractHaberdasheryFolder exception", t);
        }
        return null;
    }

    private static String readStringField(Object obj, String name) {
        try {
            Field f;
            try {
                f = obj.getClass().getField(name);
            } catch (NoSuchFieldException e) {
                f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
            }
            Object v = f.get(obj);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) {
            SRFLog.e("extractHaberdasheryFolder exception", t);
            return null;
        }
    }

    private static URL readUrlField(Object obj, String name) {
        try {
            Field f;
            try {
                f = obj.getClass().getField(name);
            } catch (NoSuchFieldException e) {
                f = obj.getClass().getDeclaredField(name);
                f.setAccessible(true);
            }
            Object v = f.get(obj);
            return (v instanceof URL) ? (URL) v : null;
        } catch (Throwable t) {
            SRFLog.e("extractHaberdasheryFolder exception", t);
            return null;
        }
    }

    private static File urlToFile(URL url) {
        if (url == null) return null;
        try {
            // handle "jar:file:/...!/..." or "file:/..."
            String spec = url.toString();
            if (spec.startsWith("jar:")) spec = spec.substring(4);
            int bang = spec.indexOf("!/");
            if (bang > 0) spec = spec.substring(0, bang);

            URL fileUrl = new URL(spec);
            if (!"file".equalsIgnoreCase(fileUrl.getProtocol())) return null;

            String decodedPath = URLDecoder.decode(fileUrl.getPath(), "UTF-8");
            return new File(decodedPath);
        } catch (Throwable t) {
            SRFLog.e("extractHaberdasheryFolder exception", t);
            return null;
        }
    }

    private static void extractHaberdasheryFolder(File jarFile) {
        ZipFile zip = null;
        try {
            SRFLog.i("extractHaberdasheryFolder from jar=" + jarFile.getAbsolutePath() + ", size=" + jarFile.length());
            zip = new ZipFile(jarFile);

            boolean foundAny = false;
            int extracted = 0;

            for (ZipEntry e : java.util.Collections.list(zip.entries())) {
                String name = e.getName();
                if (name == null) continue;
                if (!name.startsWith(HABERDASHERY_ID + "/")) continue;
                if (e.isDirectory()) continue;

                foundAny = true;
                extracted++;

                File out = new File(name); // relative output (matches Haberdashery's Paths.get("haberdashery", ...))
                File parent = out.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();

                InputStream in = null;
                BufferedOutputStream bos = null;
                try {
                    in = new BufferedInputStream(zip.getInputStream(e));
                    bos = new BufferedOutputStream(new FileOutputStream(out));

                    byte[] buf = new byte[16 * 1024];
                    int r;
                    while ((r = in.read(buf)) >= 0) {
                        bos.write(buf, 0, r);
                    }
                    bos.flush();
                } catch (Throwable t) {
            SRFLog.e("extractHaberdasheryFolder exception", t);
                } finally {
                    try { if (bos != null) bos.close(); } catch (Throwable ignored2) {}
                    try { if (in != null) in.close(); } catch (Throwable ignored2) {}
                }
            }

            SRFLog.i("extractHaberdasheryFolder done. foundAny=" + foundAny + ", extractedFiles=" + extracted + ", outDir=" + new File(HABERDASHERY_ID).getAbsolutePath());

            // If the transformed jar lost resources (rare), don't leave an empty folder behind.
            if (!foundAny) {
                // no-op
            }
        } catch (Throwable t) {
            SRFLog.e("extractHaberdasheryFolder exception", t);
        } finally {
            try { if (zip != null) zip.close(); } catch (Throwable ignored2) {}
        }
    }
}
