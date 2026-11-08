package de.robv.android.xposed.callbacks;

/** Compile-time stub of the Xposed API. The real class is provided by Vector/LSPosed at runtime. */
public abstract class XCallback {
    public static final int PRIORITY_DEFAULT = 50;
    public final int priority;

    public XCallback() { this.priority = PRIORITY_DEFAULT; }
    public XCallback(int priority) { this.priority = priority; }

    public static class Param { }
}
