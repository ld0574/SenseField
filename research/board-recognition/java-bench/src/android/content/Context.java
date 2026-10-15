package android.content;

import java.io.File;

/** 桌面 JVM 替身：只需要 getFilesDir()。 */
public class Context {
    private final File filesDir;

    public Context(File filesDir) { this.filesDir = filesDir; }

    public File getFilesDir() { return filesDir; }
}
