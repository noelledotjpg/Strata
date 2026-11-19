package com.nikohalfkino.rubydung.util;

import java.io.File;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * where the game keeps options, worlds and everything else it writes. use GameDirectory.file("worlds")
 * instead of new File("worlds"), a bare relative path ends up in whatever the working directory is,
 * which for an installed game is a folder you usually can't write to.
 *
 * picked once at startup, first match wins:
 *   1. -Dstrata.dataDir=<path>    explicit override (gradlew run uses this to keep using run/)
 *   2. a portable.txt next to the game (in the app folder or up to two folders above the jar):
 *      data lives in that same folder, nothing is written anywhere else
 *   3. the user's data folder:
 *        Windows  %APPDATA%\Strata
 *        macOS    ~/Library/Application Support/Strata
 *        Linux    $XDG_DATA_HOME/strata, or ~/.local/share/strata
 */
public final class GameDirectory {

    private static final String APP_NAME = "Strata";
    private static final String PORTABLE_MARKER = "portable.txt";
    private static final String OVERRIDE_PROPERTY = "strata.dataDir";

    private static final File ROOT = resolve();
    private static final File LOG_DIR = resolveLogDir();

    private GameDirectory() {
    }

    /** the folder everything is stored in, it exists */
    public static File root() {
        return ROOT;
    }

    /**
     * where log.txt goes: next to the game (the install / app folder) when that folder can be written to, so the logs are
     * easy to find and to attach to a bug report. an explicit -Dstrata.dataDir, a portable install, an IDE run and an
     * app folder that is read-only (/opt, Program Files) all use the data folder instead
     */
    public static File logDir() {
        return LOG_DIR;
    }

    private static File resolveLogDir() {
        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override == null || override.trim().isEmpty()) {
            File app = findAppFolder();
            if (app != null && app.isDirectory() && app.canWrite()) {
                return app;
            }
        }
        return ROOT;
    }

    // fallback from the jar: Windows is Strata/app/x.jar -> Strata, Linux is Strata/lib/app/x.jar -> Strata.
    // null when not running from a jar (IDE, gradlew run)
    private static File findAppFolder() {
        // the folder of the game's own executable (Strata.exe, bin/Strata): that is where the launcher process lives.
        // a plain java / javaw process (IDE, gradlew run, java -jar) is not the game's executable, then the jar decides
        try {
            java.util.Optional<String> command = ProcessHandle.current().info().command();
            if (command.isPresent()) {
                File exe = new File(command.get());
                String name = exe.getName().toLowerCase(Locale.ROOT);
                if (exe.isFile() && !name.startsWith("java") && exe.getParentFile() != null) {
                    return exe.getParentFile();
                }
            }
        } catch (RuntimeException e) {
            // not available here, use the jar
        }
        try {
            File location = new File(GameDirectory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!location.isFile()) {
                return null;
            }
            File dir = location.getParentFile();
            if (dir != null && dir.getName().equals("app")) {
                dir = dir.getParentFile();
                if (dir != null && dir.getName().equals("lib")) {
                    dir = dir.getParentFile();
                }
            }
            return dir;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** a file or folder inside the data folder, e.g. file("options.txt") or file("worlds") */
    public static File file(String name) {
        return new File(ROOT, name);
    }

    private static File resolve() {
        File dir = null;
        String reason = null;

        String override = System.getProperty(OVERRIDE_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            dir = new File(override.trim());
            reason = "override";
        }

        if (dir == null) {
            dir = findPortableRoot();
            if (dir != null) {
                reason = "portable";
            }
        }

        if (dir == null) {
            dir = platformDataDir();
            reason = "user folder";
        }

        if (!dir.isDirectory() && !dir.mkdirs()) {
            System.err.println("GameDirectory: could not create " + dir + ", using the working directory instead");
            dir = new File(".").getAbsoluteFile();
            reason = "fallback";
        }

        System.out.println("Data folder (" + reason + "): " + dir);
        return dir;
    }

    // walks up from the jar: Windows is Strata/app/x.jar, Linux is Strata/lib/app/x.jar
    private static File findPortableRoot() {
        try {
            File location = new File(GameDirectory.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File dir = location.isFile() ? location.getParentFile() : location;

            for (int i = 0; i < 3 && dir != null; i++) {
                if (new File(dir, PORTABLE_MARKER).isFile()) {
                    return dir;
                }
                dir = dir.getParentFile();
            }
        } catch (URISyntaxException | RuntimeException e) {
            // no usable code source (some IDE setups), just not portable
        }
        return null;
    }

    private static File platformDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        File home = new File(System.getProperty("user.home", "."));

        if (os.contains("mac")) {
            return new File(home, "Library/Application Support/" + APP_NAME);
        }

        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            File base = appData != null && !appData.isEmpty()
                    ? new File(appData)
                    : new File(new File(home, "AppData"), "Roaming");
            return new File(base, APP_NAME);
        }

        String xdg = System.getenv("XDG_DATA_HOME");
        File base = xdg != null && !xdg.isEmpty() ? new File(xdg) : new File(home, ".local/share");
        return new File(base, APP_NAME.toLowerCase(Locale.ROOT));
    }
}