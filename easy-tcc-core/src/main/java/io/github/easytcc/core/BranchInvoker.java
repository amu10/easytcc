package io.github.easytcc.core;

public interface BranchInvoker {
    void confirm(BranchTransaction branch) throws Exception;
    void cancel(BranchTransaction branch) throws Exception;
}
