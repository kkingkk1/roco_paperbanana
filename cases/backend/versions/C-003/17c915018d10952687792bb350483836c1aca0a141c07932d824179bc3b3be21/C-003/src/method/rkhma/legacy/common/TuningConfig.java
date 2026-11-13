package method.rkhma.legacy.common;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Unified tuning parameter configuration reader.
 *
 * Loading priority: .properties config file > System Property > default value / exception.
 * Config file path is specified via System Property "tuning.config.path".
 * Overloads with default values return the default when not configured;
 * overloads without default values throw IllegalStateException when not configured.
 *
 * Dependencies: none (pure JDK)
 */
public final class TuningConfig {

    /** System Property key for specifying the config file path */
    public static final String CONFIG_PATH_PROPERTY = "tuning.config.path";

    private static volatile Properties fileProps;
    private static volatile boolean loaded = false;

    private TuningConfig() {
        throw new UnsupportedOperationException("A utility class cannot be instantiated.");
    }

    private static Properties getFileProps() {
        if (!loaded) {
            synchronized (TuningConfig.class) {
                if (!loaded) {
                    String path = System.getProperty(CONFIG_PATH_PROPERTY);
                    if (path != null && !path.trim().isEmpty()) {
                        Properties p = new Properties();
                        try (FileInputStream fis = new FileInputStream(path.trim())) {
                            p.load(fis);
                            fileProps = p;
                            // System.out.println("[TuningConfig] Loaded config file: " + path.trim()
                            //     + " (" + p.size() + " entries)");
                        } catch (IOException e) {
                            System.err.println("[TuningConfig] Failed to load config file: "
                                + path + " — " + e.getMessage());
                            fileProps = null;
                        }
                    }
                    loaded = true;
                }
            }
        }
        return fileProps;
    }
    private static String resolve(String key) {
        Properties fp = getFileProps();
        if (fp != null) {
            String v = fp.getProperty(key);
            if (v != null) {
                return v.trim();
            }
        }
        String sv = System.getProperty(key);
        if (sv != null) {
            return sv.trim();
        }
        return null;
    }

    public static int getInt(String key) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("[TuningConfig] Required parameter not configured: " + key);
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("[TuningConfig] Cannot parse int: " + key + "=" + v, e);
        }
    }

    public static int getInt(String key, int defaultValue) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            System.err.println("[TuningConfig] Cannot parse int: " + key + "=" + v);
            return defaultValue;
        }
    }

    public static long getLong(String key) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("[TuningConfig] Required parameter not configured: " + key);
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("[TuningConfig] Cannot parse long: " + key + "=" + v, e);
        }
    }

    public static long getLong(String key, long defaultValue) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            System.err.println("[TuningConfig] Cannot parse long: " + key + "=" + v);
            return defaultValue;
        }
    }

    public static double getDouble(String key) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("[TuningConfig] Required parameter not configured: " + key);
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("[TuningConfig] Cannot parse double: " + key + "=" + v, e);
        }
    }

    public static double getDouble(String key, double defaultValue) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            System.err.println("[TuningConfig] Cannot parse double: " + key + "=" + v);
            return defaultValue;
        }
    }

    public static boolean getBoolean(String key) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("[TuningConfig] Required parameter not configured: " + key);
        }
        return Boolean.parseBoolean(v);
    }

    public static boolean getBoolean(String key, boolean defaultValue) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(v);
    }

    public static String getString(String key) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            throw new IllegalStateException("[TuningConfig] Required parameter not configured: " + key);
        }
        return v;
    }

    public static String getString(String key, String defaultValue) {
        String v = resolve(key);
        if (v == null || v.isEmpty()) {
            return defaultValue;
        }
        return v;
    }

    /** Reset loading state, used for switching config files in tests */
    public static synchronized void reload() {
        loaded = false;
        fileProps = null;
    }

    /** Set config path and reset loading state, used after CLI parsing. */
    public static synchronized void reload(Path path) {
        if (path != null) {
            System.setProperty(CONFIG_PATH_PROPERTY, path.toString());
        }
        reload();
    }
}
