package com.jrickey.battleship;

// Helpers.java - merged helper classes (AnalogStickView, AssetExtractor,
// PackImporter, RomImporter). Package-private; same package as the activities.

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipFile;

/**
 * Fixed-anchor virtual analog stick. The ring stays in the same place inside
 * its hosting region; touch delta from that anchor sets
 * SDL_CONTROLLER_AXIS_LEFTX/Y. On release, the axes return to centered.
 */
final class AnalogStickView extends View {

    /* SDL_GameControllerAxis mirrors. */
    private static final int SDL_AXIS_LEFTX = 0;
    private static final int SDL_AXIS_LEFTY = 1;

    /** Pixels of drag distance that map to full deflection on the SDL axis. */
    private final int mDeflectionPx;

    /** Visual radius for the outer ring. */
    private final int mRingPx;
    /** Visual radius for the moving thumb. */
    private final int mThumbPx;

    private final Paint mRingPaint;
    private final Paint mThumbPaint;

    /** Active touch pointer ID, or -1 when idle. */
    private int  mActivePointerId = -1;
    /** Stable anchor inside the left-stick region. */
    private float mAnchorX, mAnchorY;
    /** Clamped thumb position. */
    private float mTouchX, mTouchY;
    private boolean mAnchorInitialized = false;

    public AnalogStickView(Context ctx) {
        super(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        mDeflectionPx = Math.round(80f * density);
        mRingPx       = Math.round(70f * density);
        mThumbPx      = Math.round(34f * density);

        mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(Math.max(2f, 2f * density));
        mRingPaint.setColor(Color.argb(0xC0, 0xFF, 0xFF, 0xFF));

        mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mThumbPaint.setColor(Color.argb(0xA0, 0x33, 0x99, 0xFF));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w <= 0 || h <= 0) {
            return;
        }

        if (mAnchorInitialized && oldw > 0 && oldh > 0) {
            mAnchorX = Math.max(0f, Math.min(w, mAnchorX * w / oldw));
            mAnchorY = Math.max(0f, Math.min(h, mAnchorY * h / oldh));
        } else {
            mAnchorX = w * 0.5f;
            mAnchorY = h * 0.5f;
            mAnchorInitialized = true;
        }

        if (mActivePointerId < 0) {
            centerThumb();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!mAnchorInitialized) {
            return;
        }
        canvas.drawCircle(mAnchorX, mAnchorY, mRingPx,  mRingPaint);
        canvas.drawCircle(mTouchX,  mTouchY,  mThumbPx, mThumbPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (mActivePointerId >= 0) {
                    // Already tracking a pointer in this view — ignore others.
                    return true;
                }
                int idx = ev.getActionIndex();
                ensureAnchor();
                mActivePointerId = ev.getPointerId(idx);
                updateTouch(ev.getX(idx), ev.getY(idx));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                int idx = ev.findPointerIndex(mActivePointerId);
                if (idx < 0) return true;
                updateTouch(ev.getX(idx), ev.getY(idx));
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL: {
                // CANCEL applies to the whole gesture and getActionIndex()
                // is always 0 here — if we were tracking a secondary
                // pointer, an ID check would skip the reset and leave the
                // stick stuck at its last deflection. Reset unconditionally.
                mActivePointerId = -1;
                resetInput();
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int upId = ev.getPointerId(ev.getActionIndex());
                if (upId != mActivePointerId) {
                    return true;
                }
                mActivePointerId = -1;
                resetInput();
                invalidate();
                return true;
            }
            default:
                return false;
        }
    }

    private void ensureAnchor() {
        if (mAnchorInitialized) {
            return;
        }
        int w = getWidth();
        int h = getHeight();
        mAnchorX = w > 0 ? w * 0.5f : 0f;
        mAnchorY = h > 0 ? h * 0.5f : 0f;
        mAnchorInitialized = true;
        centerThumb();
    }

    private void updateTouch(float x, float y) {
        float dx = x - mAnchorX;
        float dy = y - mAnchorY;
        float len = (float) Math.hypot(dx, dy);
        float clampedDx = dx, clampedDy = dy;
        if (len > mDeflectionPx) {
            clampedDx = dx * mDeflectionPx / len;
            clampedDy = dy * mDeflectionPx / len;
        }
        mTouchX = mAnchorX + clampedDx;
        mTouchY = mAnchorY + clampedDy;
        emitAxis(clampedDx / mDeflectionPx,
                 clampedDy / mDeflectionPx);
    }

    private void resetInput() {
        centerThumb();
        emitAxis(0f, 0f);
    }

    private void centerThumb() {
        mTouchX = mAnchorX;
        mTouchY = mAnchorY;
    }

    /** @param nx,ny normalized [-1, 1]; positive Y is downward (matches SDL). */
    private void emitAxis(float nx, float ny) {
        // SDL signed-16 axis range
        int sx = (int) Math.round(Math.max(-1f, Math.min(1f, nx)) * 32767f);
        int sy = (int) Math.round(Math.max(-1f, Math.min(1f, ny)) * 32767f);
        TouchOverlay.setAxis(SDL_AXIS_LEFTX, sx);
        TouchOverlay.setAxis(SDL_AXIS_LEFTY, sy);
    }
}

/**
 * Copies bundled APK assets (f3d.o2r, config.yml, yamls/) into the app's
 * externalFilesDir on first launch and on app updates.
 *
 * - externalFilesDir is what LUS's Ship::Context::GetAppDirectoryPath()
 *   returns on Android (via SDL_AndroidGetExternalStoragePath), so files
 *   landed here are reachable through the same path the desktop build
 *   uses for cwd-relative archive lookups (f3d.o2r).
 * - We mark "extracted" by writing a versioned sentinel file. If the
 *   APK's versionCode bumps (i.e. the user updated the app), the sentinel
 *   no longer matches and we re-extract. This handles asset format
 *   changes that ship inside the APK.
 * - BattleShip.o2r is NOT extracted here — that comes from libtorch_runner.so
 *   in Phase 4.4 once the user picks their ROM. NEVER add anything
 *   ROM-derived to the APK assets.
 */
final class AssetExtractor {
    private static final String TAG = "ssb64.assets";

    /** Sentinel file: contains "<versionCode>:<sha-or-anything>" if extraction matches. */
    private static final String SENTINEL = ".bundled_assets_version";

    private AssetExtractor() { /* static */ }

    /**
     * Extract bundled assets into externalFilesDir if they aren't already
     * up-to-date for this versionCode.
     *
     * @return null on success, or a human-readable error message on failure.
     */
    public static String extractIfNeeded(Context ctx) {
        File dst = ctx.getExternalFilesDir(null);
        if (dst == null) {
            return "externalFilesDir is null — external storage not mounted?";
        }

        String wantedVersion = String.valueOf(BuildConfig.VERSION_CODE);
        File sentinel = new File(dst, SENTINEL);
        if (sentinel.isFile()) {
            try {
                String have = new String(readAll(sentinel),
                                         StandardCharsets.UTF_8).trim();
                if (wantedVersion.equals(have)) {
                    Log.i(TAG, "Bundled assets are current (versionCode=" + wantedVersion + ")");
                    return null;
                }
                Log.i(TAG, "Bundled assets stale (have=" + have + " want=" + wantedVersion + "), re-extracting");
            } catch (IOException ioe) {
                Log.w(TAG, "Couldn't read sentinel, re-extracting: " + ioe.getMessage());
            }
        } else {
            Log.i(TAG, "First-run extraction into " + dst);
        }

        AssetManager am = ctx.getAssets();
        try {
            // Top-level files. Order doesn't matter — they're independent.
            copyAsset(am, "f3d.o2r",    new File(dst, "f3d.o2r"));
            copyAsset(am, "config.yml", new File(dst, "config.yml"));

            // yamls/ subtree — Torch reads these at extraction time to
            // know the ROM's file table layout. Walk the asset directory
            // recursively so we pick up every reloc_*.yml.
            copyAssetTree(am, "yamls", new File(dst, "yamls"));
        } catch (IOException e) {
            Log.e(TAG, "Asset extraction failed", e);
            return "Asset extraction failed: " + e.getMessage();
        }

        try (OutputStream out = new FileOutputStream(sentinel)) {
            out.write(wantedVersion.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ioe) {
            Log.w(TAG, "Couldn't write sentinel — extraction will repeat next launch: " + ioe.getMessage());
            // Not fatal; the assets are extracted and the game can run.
        }

        Log.i(TAG, "Asset extraction complete");
        return null;
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static void copyAsset(AssetManager am, String assetPath, File outFile) throws IOException {
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs " + parent + " failed");
        }
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    /**
     * Recursively copy an asset directory subtree. AssetManager.list() returns
     * an empty array for files, non-empty for directories — this is the
     * documented way to walk it.
     */
    private static void copyAssetTree(AssetManager am, String assetPath, File outDir) throws IOException {
        String[] entries = am.list(assetPath);
        if (entries == null || entries.length == 0) {
            // It's a leaf file (or empty dir). Treat as file copy.
            copyAsset(am, assetPath, outDir);
            return;
        }
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            throw new IOException("mkdirs " + outDir + " failed");
        }
        for (String entry : entries) {
            String childAsset = assetPath + "/" + entry;
            File   childOut   = new File(outDir, entry);
            copyAssetTree(am, childAsset, childOut);
        }
    }

    /** Whether BattleShip.o2r already exists in externalFilesDir. Phase 4.4 uses this. */
    public static boolean haveExtractedRom(Context ctx) {
        File dst = ctx.getExternalFilesDir(null);
        return dst != null && new File(dst, "BattleShip.o2r").isFile();
    }
}

/**
 * Imports a hi-res texture pack (.zip) from a SAF content URI into the app's
 * own mods/ directory under externalFilesDir.
 *
 * Why copy at all: under scoped storage the native HiResPack scanner can only
 * read files the app itself owns. A pack adb-pushed or dropped by a file
 * manager into Android/data/&lt;pkg&gt;/ is owned by another uid and the scan
 * fails with "Permission denied". Copying the picked stream here makes the
 * file app-owned, so {@code HiResPack::Init} reads it (in place, no extraction)
 * on the next game launch.
 *
 * Security/robustness (mirrors the v1.3 Android pass on RomImporter/BootActivity):
 *   - The pack is hundreds of MB, so the stream goes STRAIGHT to its final
 *     mods/ path — never staged into cacheDir and copied again. Bytes flow in
 *     256 KB chunks, so peak heap is bounded regardless of pack size.
 *   - Written to a "&lt;name&gt;.zip.part" temp and renamed only after it
 *     validates, so an interrupted/torn copy never leaves a half-written .zip
 *     the scanner would trip on. Every failure path deletes the partial — no
 *     orphaned hundred-MB files.
 *   - A full disk (write IOException) fails fast instead of publishing a
 *     truncated archive.
 *   - The SAF display name is sanitized to a bare basename (no path
 *     separators / traversal) and forced to end in .zip.
 *   - The copied file is untrusted: it must open as a real ZIP with at least
 *     one entry before it's published. Extension/MIME are not trusted.
 *   - A size cap rejects an absurd download (DoS / accidental wrong file).
 */
final class PackImporter {
    private static final String TAG = "ssb64.pack";

    /** Hard ceiling on an imported pack. Real packs are ~200 MB; this only
     *  guards against an accidental wrong file or a malicious huge archive. */
    private static final long MAX_PACK_BYTES = 1024L * 1024L * 1024L; // 1 GB

    /**
     * Copy the picked zip into &lt;externalFilesDir&gt;/mods/. Heavy I/O — call
     * from a background thread.
     *
     * @return the published File on success, or null on any failure (already
     *         logged under tag "ssb64.pack"; the partial is cleaned up).
     */
    public static File importPack(Context ctx, Uri uri) {
        if (uri == null) {
            return null;
        }
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                Log.e(TAG, "openInputStream returned null for " + uri);
                return null;
            }
            return copyToMods(ctx, in, queryDisplayName(ctx, uri));
        } catch (IOException e) {
            Log.e(TAG, "open content uri failed", e);
            return null;
        }
    }

    /**
     * Dev/test path: import a pack from an absolute file the app can already
     * read (e.g. an adb-pushed /sdcard/Download/*.zip). Mirrors RomImporter's
     * dev_rom shortcut; not part of the user-facing SAF flow.
     */
    public static File importPackFromFile(Context ctx, File src) {
        if (src == null || !src.canRead()) {
            Log.e(TAG, "dev pack not readable: " + src);
            return null;
        }
        try (InputStream in = new FileInputStream(src)) {
            return copyToMods(ctx, in, src.getName());
        } catch (IOException e) {
            Log.e(TAG, "open dev pack failed", e);
            return null;
        }
    }

    /** Stream {@code in} into mods/&lt;safe&gt;.zip via a validated temp+rename. */
    private static File copyToMods(Context ctx, InputStream in, String displayName) {
        File extDir = ctx.getExternalFilesDir(null);
        if (extDir == null) {
            Log.e(TAG, "externalFilesDir is null — external storage not mounted?");
            return null;
        }
        File modsDir = new File(extDir, "mods");
        if (!modsDir.isDirectory() && !modsDir.mkdirs()) {
            Log.e(TAG, "could not create mods dir " + modsDir);
            return null;
        }

        File dst = new File(modsDir, safeZipName(displayName));
        File tmp = new File(modsDir, dst.getName() + ".part");

        long total = 0;
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[256 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_PACK_BYTES) {
                    // Treated as a copy failure so the single catch cleans up.
                    throw new IOException("pack exceeds " + MAX_PACK_BYTES + "-byte cap");
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            Log.e(TAG, "copy failed (full disk / read error / too large?)", e);
            tmp.delete();
            return null;
        }

        // Validate (untrusted input — confirm it parses as a ZIP with >=1 entry)
        // and publish. The `published` flag + finally guarantees the .part temp
        // is removed on every exit short of a successful publish — including a
        // Throwable (e.g. OutOfMemoryError raised inside ZipFile on a hostile
        // archive), which would otherwise leave a ~200 MB orphan.
        boolean published = false;
        try {
            if (!isValidZip(tmp)) {
                Log.e(TAG, "rejected " + total + " bytes: not a valid .zip archive");
                return null;
            }
            // rename(2) on the same filesystem (tmp and dst share mods/)
            // atomically replaces any existing same-named pack — no
            // delete-then-rename window that could leave mods/ with neither the
            // old nor the new pack. Fall back to delete+rename only if a strict
            // renameTo refuses to overwrite.
            if (!tmp.renameTo(dst)) {
                dst.delete();
                if (!tmp.renameTo(dst)) {
                    throw new IOException("rename " + tmp + " -> " + dst + " failed");
                }
            }
            published = true;
            Log.i(TAG, "imported " + total + " bytes -> " + dst);
            return dst;
        } catch (IOException e) {
            Log.e(TAG, "publish failed", e);
            return null;
        } finally {
            if (!published) {
                tmp.delete();
            }
        }
    }

    /** Best-effort SAF display name (OpenableColumns.DISPLAY_NAME); null on miss. */
    private static String queryDisplayName(Context ctx, Uri uri) {
        try (Cursor c = ctx.getContentResolver().query(
                uri, new String[] { OpenableColumns.DISPLAY_NAME },
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return c.getString(idx);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "display-name query failed; using fallback name", e);
        }
        return null;
    }

    /**
     * Turn a SAF display name into a safe basename ending in .zip. Strips any
     * path components (a content provider could hand back "../x" or "a/b") and
     * restricts to a conservative charset so nothing escapes mods/.
     */
    static String safeZipName(String displayName) {
        String base = (displayName == null) ? "" : displayName;
        int cut = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (cut >= 0) {
            base = base.substring(cut + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9 ._-]", "_").trim();
        // Also reject names that are nothing but the extension (e.g. a SAF name
        // of " .zip" trims to ".zip", which would land as a hidden file).
        if (base.isEmpty() || base.equals(".") || base.equals("..")
                || base.equalsIgnoreCase(".zip")) {
            base = "pack";
        }
        if (!base.toLowerCase(Locale.ROOT).endsWith(".zip")) {
            base = base + ".zip";
        }
        if (base.length() > 100) {
            base = base.substring(0, 96) + ".zip";
        }
        return base;
    }

    /** True if {@code f} opens as a ZIP with at least one entry. */
    private static boolean isValidZip(File f) {
        try (ZipFile zf = new ZipFile(f)) {
            return zf.size() > 0;
        } catch (IOException e) {
            return false;
        }
    }

    private PackImporter() { /* static */ }
}

/**
 * Bridges the Java first-run UI to libtorch_runner.so's ROM extractor.
 *
 * Two pieces:
 *   1. {@link #stageRomFromUri} — copies the SAF-selected content URI
 *      into a real file in cacheDir, because Torch's Cartridge::open()
 *      uses fopen() and can't read content:// URIs directly.
 *   2. {@link #extractO2R} — JNI entry point into Companion (O2R mode).
 *      Returns 0 on success.
 *
 * This class deliberately lives outside org.libsdl.app — loading
 * libtorch_runner.so does NOT trigger SDL2's JNI_OnLoad and therefore
 * doesn't need the SDLActivity Java class to be registered.
 */
final class RomImporter {
    private static final String TAG = "ssb64.rom";

    /** Stable load: System.loadLibrary uses linker caching after the first hit. */
    static {
        System.loadLibrary("torch_runner");
    }

    /**
     * Streams a SAF content URI into a real file under cacheDir.
     *
     * @param ctx     for ContentResolver + cacheDir
     * @param romUri  user-picked URI from ACTION_OPEN_DOCUMENT
     * @return        local path on success, or null on I/O failure
     */
    public static File stageRomFromUri(Context ctx, Uri romUri) {
        File outFile = new File(ctx.getCacheDir(), "user_rom.z64");

        ContentResolver cr = ctx.getContentResolver();
        try (InputStream in = cr.openInputStream(romUri);
             OutputStream out = new FileOutputStream(outFile)) {
            if (in == null) {
                Log.e(TAG, "openInputStream returned null for " + romUri);
                return null;
            }
            byte[] buf = new byte[256 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            Log.i(TAG, "Staged " + total + " bytes from " + romUri + " -> " + outFile);
        } catch (IOException ioe) {
            Log.e(TAG, "stageRomFromUri failed", ioe);
            outFile.delete();
            return null;
        }
        return outFile;
    }

    /**
     * Native entry into Companion(O2R). Heavy work — call from a
     * background thread. Logcat tag "ssb64.torch" is the source of
     * detailed error messages on non-zero return.
     *
     * @param romPath  absolute path to a real file (NOT a content:// URI)
     * @param srcDir   directory with config.yml + yamls/us/*.yml
     *                 (extracted there by AssetExtractor on first run)
     * @param dstDir   where BattleShip.o2r will be written
     * @return 0 on success
     */
    public static native int extractO2R(String romPath, String srcDir, String dstDir);

    private RomImporter() { /* static */ }
}
