package io.github.easytcc.core;

public final class EasyTccContext {
    private static final ThreadLocal<String> CURRENT = new ThreadLocal<String>();
    private static final ThreadLocal<String> CURRENT_BRANCH = new ThreadLocal<String>();
    private EasyTccContext() { }
    public static String currentXid() { return CURRENT.get(); }
    public static boolean isActive() { return CURRENT.get() != null; }
    public static void bind(String xid) { CURRENT.set(xid); }
    public static void clear() { CURRENT.remove(); }
    public static String currentBranchId() { return CURRENT_BRANCH.get(); }
    public static void bindBranch(String branchId) { CURRENT_BRANCH.set(branchId); }
    public static void clearBranch() { CURRENT_BRANCH.remove(); }
}
