package io.github.easytcc.core;

public interface BranchInvoker {
    default void validate(BranchTransaction branch) throws Exception { }
    void confirm(BranchTransaction branch) throws Exception;
    void cancel(BranchTransaction branch) throws Exception;
}
