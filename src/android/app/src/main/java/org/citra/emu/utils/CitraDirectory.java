package org.citra.emu.utils;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.citra.emu.NativeLibrary;
import org.citra.emu.R;
import org.citra.emu.iconcache.IconCache;
import org.citra.emu.model.GameInfo;
import org.citra.emu.settings.Settings;
import org.citra.emu.settings.SettingsFile;
import org.citra.emu.settings.model.Setting;
import org.citra.emu.overlay.InputOverlay;

public final class CitraDirectory {
    private static final String SDMC_DIRECTORY_NAME = "Nintendo 3DS";
    private static final int MAX_SDMC_SEARCH_DEPTH = 4;
    private static final int MAX_SDMC_SEARCH_DIRECTORIES = 256;

    private static int sInitState;
    private static String mUserPath;
    private static String mSDMCPath;
    private static String mStatesPath;
    private static boolean mUsingCustomSDMCPath;
    private static IconCache mIconCache;
    private static Bitmap mDefaultIcon;
    private static final Dictionary<String, String> mTitleDB = new Hashtable<>();

    private static final int INIT_FAILED = -1;
    private static final int INIT_UNKNOWN = 0;
    private static final int INIT_LEGACY = 1;
    private static final int INIT_SAF = 2;

    private static final class SDMCDirectoryResolution {
        public final String rootPath;
        public final boolean existingLayout;

        SDMCDirectoryResolution(String rootPath, boolean existingLayout) {
            this.rootPath = rootPath;
            this.existingLayout = existingLayout;
        }
    }

    private static final class DirectorySearchNode {
        public final File directory;
        public final int depth;

        DirectorySearchNode(File directory, int depth) {
            this.directory = directory;
            this.depth = depth;
        }
    }

    private static class InitTask extends AsyncTask<Context, Void, Void> {
        @Override
        protected Void doInBackground(Context... contexts) {
            initializeExternalStorage(contexts[0]);
            return null;
        }
    }

    public static void start(Context context) {
        if (sInitState == INIT_LEGACY || sInitState == INIT_SAF) {
            return;
        }

        File externalPath = null;
        if (PermissionsHandler.hasWriteAccess(context)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                sInitState = INIT_LEGACY;
                if (Environment.isExternalStorageManager()) {
                externalPath = Environment.getExternalStorageDirectory();
            } else {
                externalPath = context.getExternalFilesDir(null);
            }
            } else if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
                sInitState = INIT_LEGACY;
                externalPath = Environment.getExternalStorageDirectory();
            } else {
                sInitState = INIT_FAILED;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Environment.isExternalStorageLegacy()) {
            sInitState = INIT_SAF;
            externalPath = context.getExternalFilesDir(null);
        } else {
            sInitState = INIT_FAILED;
        }

        if (externalPath != null) {
            File userPath = new File(externalPath, "citra-emu");
            if (!userPath.isDirectory() && !userPath.mkdir()) {
                sInitState = INIT_FAILED;
            } else {
                mUserPath = userPath.getPath();
                mDefaultIcon = BitmapFactory.decodeResource(context.getResources(), R.drawable.no_banner);
                mIconCache = new IconCache(context);
                loadTitleDB(context.getAssets());
                NativeLibrary.ensureLoaded(context);
                NativeLibrary.SetUserPath(mUserPath);
        String configuredPath = loadConfiguredCorePath(SettingsFile.KEY_USER_PATH);
        if (!configuredPath.isEmpty()) {
            mUserPath = configuredPath;
        }
                setSDMCDirectoryOverride(loadConfiguredSDMCDirectory());
                setStatesDirectoryOverride(loadConfiguredStatesDirectory());
                new InitTask().execute(context);
            }
        }
    }

    public static Bitmap getDefaultIcon() {
        return mDefaultIcon;
    }

    public static GameInfo loadGameInfo(String path) {
        GameInfo info = mIconCache.getEntry(path);
        if (info == null) {
            info = new GameInfo();
            info.path = path;
            info.id = NativeLibrary.GetAppId(path);
            info.name = NativeLibrary.GetAppTitle(path);
            info.region = NativeLibrary.GetAppRegion(path);
            info.icon = NativeLibrary.GetAppIcon(path);
            if (!isExternalStorageLegacy()) {
                int idx = info.name.indexOf("%2F");
                if (idx > 0) {
                    info.name = info.name.substring(idx + 3);
                }
            }
            mIconCache.addIconToDB(info);
        }
        String name = mTitleDB.get(info.id);
        if (name == null && info.id.length() > 8) {
            String id = "00040000" + info.id.substring(8);
            if (!info.id.equals(id)) {
                name = mTitleDB.get(id);
            }
        }
        if (name != null) {
            info.name = name;
        }
        return info;
    }

    private static void loadTitleDB(AssetManager mgr) {
        String lan = Locale.getDefault().getLanguage();
        if (lan.equals("zh")) {
            lan = lan + "_" + Locale.getDefault().getCountry();
        }
        String asset = "3dstdb-" + lan + ".txt";
        try {
            BufferedReader input = new BufferedReader(new InputStreamReader(mgr.open(asset)));
            while (input.ready()) {
                String line = input.readLine();
                int sep = line.indexOf('=');
                if (sep > 0 && sep < line.length() - 1) {
                    String key = line.substring(0, sep).trim();
                    String value = line.substring(sep + 1).trim();
                    if (!key.isEmpty() && !value.isEmpty()) {
                        mTitleDB.put(key, value);
                    }
                }
            }
        } catch (IOException e) {
            Log.e("citra", "loadTitleDB error", e);
        }
    }

    private static boolean deleteFilesRecursive(File dir) {
        File[] files = dir.listFiles();
        boolean success = true;
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    success &= deleteFilesRecursive(file);
                }
                if (!file.delete()) {
                    success = false;
                }
            }
        }
        return success;
    }

    public static boolean deleteAllFiles(String dir) {
        return deleteFilesRecursive(new File(dir));
    }

    public static boolean isInitialized() {
        return sInitState == INIT_LEGACY || sInitState == INIT_SAF;
    }

    public static boolean isExternalStorageLegacy() {
        return sInitState == INIT_LEGACY;
    }

    public static void clearIconCache() {
        mIconCache.clearCache();
    }

    public static String getTitleName(String id) {
        return mTitleDB.get(id);
    }

    private static void initializeExternalStorage(Context context) {
        File shaders = new File(getShadersDirectory());
        File sysdata = new File(getSysDataDirectory());
        File config = new File(getConfigDirectory());
        File nand = new File(getNandDirectory());
        File sdmc = new File(getSDMCDirectory());
        File theme = new File(getThemeDirectory());
        copyAssetFolder("shaders", shaders, false, context);
        copyAssetFolder("sysdata", sysdata, false, context);
        copyAssetFolder("config", config, false, context);
        copyAssetFolder("nand", nand, false, context);
        if (!mUsingCustomSDMCPath) {
            copyAssetFolder("sdmc", sdmc, false, context);
        } else if (!sdmc.exists()) {
            sdmc.mkdirs();
        }
        if (theme.exists() || theme.mkdir()) {
            saveInputOverlay(context);
        }
    }

    public static String getUserDirectory() {
        String configuredPath = loadConfiguredCorePath(SettingsFile.KEY_USER_PATH);
        if (!configuredPath.isEmpty()) {
            return configuredPath;
        }
        return mUserPath;
    }

    public static String getDefaultStatesDirectory() {
        return getUserDirectory() + File.separator + "states";
    }

    public static String getDefaultSDMCDirectory() {
        return getUserDirectory() + File.separator + "sdmc";
    }

    public static String getStatesDirectory() {
        if (mStatesPath == null || mStatesPath.isEmpty()) {
            return getDefaultStatesDirectory();
        }
        return mStatesPath;
    }

    public static void setStatesDirectoryOverride(String path) {
        if (path == null || path.isEmpty()) {
            mStatesPath = getDefaultStatesDirectory();
            NativeLibrary.SetStatesPath("");
            return;
        }
        mStatesPath = path;
        NativeLibrary.SetStatesPath(path);
    }

    public static void setSDMCDirectoryOverride(String path) {
        if (path == null || path.isEmpty()) {
            mUsingCustomSDMCPath = false;
            mSDMCPath = getDefaultSDMCDirectory();
            NativeLibrary.SetSDMCPath("");
            return;
        }
        SDMCDirectoryResolution resolution = resolveSDMCDirectory(path);
        ensureSDMCDirectoryExists(resolution);
        mUsingCustomSDMCPath = true;
        mSDMCPath = resolution.rootPath;
        NativeLibrary.SetSDMCPath(resolution.rootPath);
    }

    public static File getCheatFile(String programId) {
        File cheatsPath = new File(mUserPath, "cheats");
        if (!cheatsPath.isDirectory() && !cheatsPath.mkdir()) {
            return null;
        }
        return new File(cheatsPath, programId + ".txt");
    }

    public static String getConfigFile() {
        return getConfigDirectory() + File.separator + "config-mmj.ini";
    }

    public static File getGameListFile() {
        String path = getUserDirectory() + File.separator + "gamelist.bin";
        return new File(path);
    }

    public static String getConfigDirectory() {
        return getUserDirectory() + File.separator + "config";
    }

    public static String getCacheDirectory() {
        return getUserDirectory() + File.separator + "cache";
    }

    public static String getShadersDirectory() {
        return getUserDirectory() + File.separator + "shaders";
    }

    public static String getSysDataDirectory() {
        return getUserDirectory() + File.separator + "sysdata";
    }

    public static String getCheatsDirectory() {
        return getUserDirectory() + File.separator + "cheats";
    }

    public static String getAmiiboDirectory() {
        return getUserDirectory() + File.separator + "amiibo";
    }

    public static String getThemeDirectory() {
        return getUserDirectory() + File.separator + "theme";
    }

    public static String getSDMCDirectory() {
        if (mSDMCPath == null || mSDMCPath.isEmpty()) {
            return getDefaultSDMCDirectory();
        }
        return mSDMCPath;
    }

    public static String getNandDirectory() {
        return getUserDirectory() + File.separator + "nand";
    }

    private static String loadConfiguredStatesDirectory() {
        return loadConfiguredCorePath(SettingsFile.KEY_STATES_PATH);
    }

    private static String loadConfiguredSDMCDirectory() {
        return loadConfiguredCorePath(SettingsFile.KEY_SDMC_PATH);
    }

    private static String loadConfiguredCorePath(String key) {
        HashMap<String, org.citra.emu.settings.model.SettingSection> sections =
            SettingsFile.loadSettings("");
        org.citra.emu.settings.model.SettingSection coreSection =
            sections.get(Settings.SECTION_INI_CORE);
        if (coreSection == null) {
            return "";
        }
        Setting pathSetting = coreSection.getSetting(key);
        if (pathSetting instanceof org.citra.emu.settings.model.StringSetting) {
            return ((org.citra.emu.settings.model.StringSetting)pathSetting).getValue();
        }
        return "";
    }

    public static String getSystemTitleDirectory() {
        return getUserDirectory() + "/nand/00000000000000000000000000000000/title";
    }

    public static String getSystemApplicationDirectory() {
        return getUserDirectory() + "/nand/00000000000000000000000000000000/title/00040010";
    }

    public static String getSystemAppletDirectory() {
        return getUserDirectory() + "/nand/00000000000000000000000000000000/title/00040030";
    }

    public static String getApplicationDirectory() {
        return getSDMCDirectory() + "/Nintendo 3DS/00000000000000000000000000000000/00000000000000000000000000000000/title";
    }

    public static void saveInputOverlay(Context context) {
        final int[] inputIds = InputOverlay.ResIds;
        final String[] inputNames = InputOverlay.ResNames;
        String path = getThemeDirectory() + "/default.zip";
        File file = new File(path);
        if (file.exists()) {
            return;
        }
        try {
            FileOutputStream outputStream = new FileOutputStream(file);
            ZipOutputStream zipOut = new ZipOutputStream(new BufferedOutputStream(outputStream));
            for (int i = 0; i < inputIds.length; ++i) {
                ZipEntry entry = new ZipEntry(inputNames[i]);
                zipOut.putNextEntry(entry);
                Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), inputIds[i]);
                Bitmap.CompressFormat format = Bitmap.CompressFormat.PNG;
                if (inputNames[i].endsWith(".jpg")) {
                    format = Bitmap.CompressFormat.JPEG;
                }
                bitmap.compress(format, 90, zipOut);
            }
            String glsl = "background.glsl";
            ZipEntry entry = new ZipEntry(glsl);
            zipOut.putNextEntry(entry);
            copyFile(context.getAssets().open(glsl), zipOut);
            zipOut.close();
        } catch (IOException e) {
            Log.e("citra", "saveInputOverlay error", e);
        }
    }

    public static Map<Integer, Bitmap> loadInputOverlay(Context context, String theme) {
        final int[] inputIds = InputOverlay.ResIds;
        final String[] inputNames = InputOverlay.ResNames;
        final String themePath = getThemeDirectory();
        Map<Integer, Bitmap> inputs = new HashMap<>();
        File file = new File(themePath + "/" + theme + ".zip");
        if (!file.exists()) {
            file = new File(themePath + "/default.zip");
        }
        for (int id : inputIds) {
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), id);
            inputs.put(id, bitmap);
        }
        if (!file.exists()) {
            return inputs;
        }
        try {
            FileInputStream inputStream = new FileInputStream(file);
            ZipInputStream zipIn = new ZipInputStream(new BufferedInputStream(inputStream));
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                if ("background.glsl".equals(entry.getName())) {
                    ByteArrayOutputStream output = new ByteArrayOutputStream();
                    copyFile(zipIn, output);
                    continue;
                }
                for (int i = 0; i < inputNames.length; ++i) {
                    if (inputNames[i].equals(entry.getName())) {
                        inputs.put(inputIds[i], BitmapFactory.decodeStream(zipIn));
                        break;
                    }
                }
            }
            zipIn.close();
        } catch (IOException e) {
            Log.e("citra", "loadInputOverlay error", e);
        }
        return inputs;
    }

    private static void copyAsset(String asset, File output, Boolean overwrite, Context context) {
        try {
            if (!output.exists() || overwrite) {
                InputStream in = context.getAssets().open(asset);
                OutputStream out = new FileOutputStream(output);
                copyFile(in, out);
                in.close();
                out.close();
            }
        } catch (IOException e) {
            Log.e("citra", "copyAsset error: " + asset, e);
        }
    }

    private static void copyAssetFolder(String assetFolder, File outputFolder, Boolean overwrite,
                                        Context context) {
        try {
            boolean createdFolder = false;
            for (String file : context.getAssets().list(assetFolder)) {
                if (!createdFolder) {
                    outputFolder.mkdir();
                    createdFolder = true;
                }
                copyAssetFolder(assetFolder + File.separator + file, new File(outputFolder, file),
                                overwrite, context);
                copyAsset(assetFolder + File.separator + file, new File(outputFolder, file),
                          overwrite, context);
            }
        } catch (IOException e) {
            Log.e("citra", "copyAssetFolder error: " + assetFolder, e);
        }
    }

    private static SDMCDirectoryResolution resolveSDMCDirectory(String path) {
        File selected = new File(path);
        if (selected.getName().equalsIgnoreCase(SDMC_DIRECTORY_NAME)) {
            File parent = selected.getParentFile();
            if (parent != null) {
                return new SDMCDirectoryResolution(parent.getAbsolutePath(), true);
            }
        }
        File directNintendoDirectory = new File(selected, SDMC_DIRECTORY_NAME);
        if (directNintendoDirectory.isDirectory()) {
            return new SDMCDirectoryResolution(selected.getAbsolutePath(), true);
        }
        File nestedNintendoDirectory = findNintendo3DSDirectory(selected);
        if (nestedNintendoDirectory != null && nestedNintendoDirectory.getParentFile() != null) {
            return new SDMCDirectoryResolution(
                nestedNintendoDirectory.getParentFile().getAbsolutePath(), true);
        }
        return new SDMCDirectoryResolution(selected.getAbsolutePath(), false);
    }

    private static void ensureSDMCDirectoryExists(SDMCDirectoryResolution resolution) {
        File root = new File(resolution.rootPath);
        if (!root.exists()) {
            root.mkdirs();
        }
        if (!resolution.existingLayout) {
            File nintendoDirectory = new File(root, SDMC_DIRECTORY_NAME);
            if (!nintendoDirectory.exists()) {
                nintendoDirectory.mkdirs();
            }
        }
    }

    private static File findNintendo3DSDirectory(File root) {
        if (!root.isDirectory()) {
            return null;
        }
        ArrayDeque<DirectorySearchNode> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(new DirectorySearchNode(root, 0));
        int visitedDirectories = 0;
        while (!queue.isEmpty() && visitedDirectories < MAX_SDMC_SEARCH_DIRECTORIES) {
            DirectorySearchNode node = queue.removeFirst();
            File directory = node.directory;
            String absolutePath = directory.getAbsolutePath();
            if (!visited.add(absolutePath)) {
                continue;
            }
            ++visitedDirectories;
            File[] children = directory.listFiles();
            if (children == null) {
                continue;
            }
            for (File child : children) {
                if (!child.isDirectory()) {
                    continue;
                }
                if (child.getName().equalsIgnoreCase(SDMC_DIRECTORY_NAME)) {
                    return child;
                }
                if (node.depth < MAX_SDMC_SEARCH_DEPTH) {
                    queue.addLast(new DirectorySearchNode(child, node.depth + 1));
                }
            }
        }
        return null;
    }

    public static void copyFile(String from, String to) {
        try {
            InputStream in = new FileInputStream(from);
            OutputStream out = new FileOutputStream(to);
            copyFile(in, out);
        } catch (IOException e) {
            Log.e("citra", "copyFile error from: " + from + ", to: " + to, e);
        }
    }

    private static void copyFile(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    public static List<String> readAllLines(InputStream input) {
        byte[] buffer = new byte[1024 * 8];
        List<String> lines = new ArrayList<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            while (true) {
                int size = input.read(buffer, 0, buffer.length);
                if (size > 0) {
                    int i = 0;
                    int offset = 0;
                    while (i < size) {
                        if (buffer[i++] == '\n') {
                            output.write(buffer, offset, i - offset - 1);
                            lines.add(output.toString());
                            output.reset();
                            offset = i;
                        }
                    }
                    if (offset < size) {
                        output.write(buffer, offset, size - offset);
                    }
                } else {
                    if (output.size() > 0) {
                        lines.add(output.toString());
                    }
                    break;
                }
            }
            input.close();
        } catch (IOException e) {
            Log.e("citra", "readAllLines error", e);
            lines.clear();
        }
        return lines;
    }

    public static Map<String, String> loadInputLayoutConfig(Context context) {
        Map<String, String> layout = new HashMap<>();
        try {
            InputStream is = context.getAssets().open("config/input-layout.ini");
            parseLayoutIni(layout, is);
        } catch (IOException ignored) {}
        try {
            FileInputStream fis = new FileInputStream(getConfigDirectory() + "/input-layout.ini");
            parseLayoutIni(layout, fis);
        } catch (IOException ignored) {}
        return layout;
    }

    public static void saveInputLayoutConfig(Context context, Map<String, String> layout) {
        try {
            FileOutputStream fos = new FileOutputStream(getConfigDirectory() + "/input-layout.ini");
            OutputStreamWriter writer = new OutputStreamWriter(fos);
            writer.write("// landscape layout = X[-100,100], Y[-100,100], SCALE[0,200], VISIBLE[0,1]\n");
            for (Map.Entry<String, String> entry : layout.entrySet()) {
                if (entry.getKey().endsWith("_landscape"))
                    writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
            writer.write("// portrait layout\n");
            for (Map.Entry<String, String> entry : layout.entrySet()) {
                if (entry.getKey().endsWith("_portrait"))
                    writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
            writer.close();
            fos.close();
        } catch (IOException e) {
            Log.e("citra", "saveInputLayoutConfig error", e);
        }
    }

    private static void parseLayoutIni(Map<String, String> map, InputStream is) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        String line;
        while ((line = reader.readLine()) != null) {
            int eq = line.indexOf('=');
            if (eq > 0 && eq < line.length() - 1 && line.charAt(0) != '/') {
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                map.put(key, value);
            }
        }
        reader.close();
    }
}