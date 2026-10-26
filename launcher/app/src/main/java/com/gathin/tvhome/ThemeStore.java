package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Themes kept on the TV for the Launcher settings picker. A pack is a folder under
 * {@code Android/data/com.gathin.tvhome/files/themes/<name>/} with the same config.json and optional wallpaper.jpg
 * or wallpapers/ collection that tools/theme.sh pushes. Applying one copies it over the live theme files in the
 * parent folder, so Config keeps reading a single config.json at Home's start.
 */
final class ThemeStore {
    static final String KEY = "theme_choice";
    private static final String FOLDER = "themes";

    static final class Theme {
        final String name;

        Theme(String name) {
            this.name = name;
        }

        String label() {
            String text = name.replace('_', ' ').replace('-', ' ').trim();
            return text.isEmpty() ? name : text.substring(0, 1).toUpperCase(Locale.getDefault()) + text.substring(1);
        }
    }

    private ThemeStore() {}

    static File folder(Context context) {
        File dir = Config.dir(context);
        return dir == null ? null : new File(dir, FOLDER);
    }

    /** The installed packs, by folder name, or an empty list when none are there. */
    static List<Theme> list(Context context) {
        File root = folder(context);
        File[] entries = root == null ? null : root.listFiles();
        if (entries == null) return Collections.emptyList();
        List<Theme> themes = new ArrayList<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (!entry.isDirectory() || name.startsWith(".") || !name.matches("[A-Za-z0-9_.-]+")) continue;
            if (new File(entry, Config.FILE_NAME).isFile()) themes.add(new Theme(name));
        }
        themes.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return themes;
    }

    /** The applied pack's name, or null for the built-in look. */
    static String applied(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getString(KEY, null);
    }

    static String appliedLabel(Context context) {
        String name = applied(context);
        if (name == null) return context.getString(R.string.theme_builtin);
        for (Theme theme : list(context)) if (theme.name.equals(name)) return theme.label();
        return name;
    }

    /**
     * Applies a pack, or the built-in look for a null/empty name. The live config.json and wallpapers are replaced,
     * and theme_revision moves so Config clears the on-TV layout choices that belonged to the previous theme.
     */
    static boolean apply(Context context, String name) {
        File dir = Config.dir(context);
        if (dir == null) return false;
        File themes = new File(dir, FOLDER);
        File source = name == null || name.isEmpty() ? null : new File(themes, name);
        File sourceConfig = source == null ? null : new File(source, Config.FILE_NAME);
        if (source != null && !sourceConfig.isFile()) return false;
        try {
            if (source == null) {
                delete(new File(dir, Config.FILE_NAME));
                delete(new File(dir, Config.WALLPAPER_NAME));
            } else {
                copy(sourceConfig, new File(dir, Config.FILE_NAME));
                File wallpaper = new File(source, Config.WALLPAPER_NAME);
                if (wallpaper.isFile()) copy(wallpaper, new File(dir, Config.WALLPAPER_NAME));
                else delete(new File(dir, Config.WALLPAPER_NAME));
            }
            clearWallpapers(new File(dir, "wallpapers"));
            if (source != null) copyWallpapers(new File(source, "wallpapers"), new File(dir, "wallpapers"));
            write(new File(dir, "theme_revision"), Long.toString(System.currentTimeMillis()));
            SharedPreferences preferences = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
            if (source == null) preferences.edit().remove(KEY).commit();
            else preferences.edit().putString(KEY, name).commit();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void copyWallpapers(File from, File to) throws IOException {
        File[] entries = from.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            String name = entry.getName();
            if (entry.isFile() && ThemeFiles.collectionImage(name)) copy(entry, new File(to, name));
            else if (entry.isFile() && WallpaperCaptions.FILE_NAME.equals(name)) copy(entry, new File(to, name));
        }
    }

    private static void clearWallpapers(File folder) {
        File[] entries = folder.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            String name = entry.getName();
            if (entry.isFile() && (ThemeFiles.collectionImage(name)
                    || WallpaperCaptions.FILE_NAME.equals(name))) delete(entry);
        }
    }

    private static void copy(File from, File to) throws IOException {
        File parent = to.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdirs");
        File temp = new File(to.getPath() + ".tmp");
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[16 * 1024];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        }
        if (!temp.renameTo(to)) {
            delete(to);
            if (!temp.renameTo(to)) throw new IOException("rename");
        }
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("mkdirs");
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes(StandardCharsets.US_ASCII));
        }
    }

    private static void delete(File file) {
        if (file.exists()) file.delete();
    }
}
