package io.github.easytcc.core;

import java.io.Serializable;

public final class BranchTransaction implements Serializable {
    private static final long serialVersionUID = 1L;
    private final String branchId;
    private final String xid;
    private final String name;
    private final String beanName;
    private final String confirmMethod;
    private final String cancelMethod;
    private final Object[] arguments;
    private BranchStatus status;
    private String lastError;

    public BranchTransaction(
            String branchId,
            String xid,
            String name,
            String beanName,
            String confirmMethod,
            String cancelMethod,
            Object[] arguments) {
        this.branchId = branchId;
        this.xid = xid;
        this.name = name;
        this.beanName = beanName;
        this.confirmMethod = confirmMethod;
        this.cancelMethod = cancelMethod;
        this.arguments = arguments == null ? new Object[0] : arguments.clone();
        this.status = BranchStatus.REGISTERED;
    }

    public String getBranchId() {
        return branchId;
    }

    public String getXid() {
        return xid;
    }

    public String getName() {
        return name;
    }

    public String getBeanName() {
        return beanName;
    }

    public String getConfirmMethod() {
        return confirmMethod;
    }

    public String getCancelMethod() {
        return cancelMethod;
    }

    public Object[] getArguments() {
        return arguments.clone();
    }

    public BranchStatus getStatus() {
        return status;
    }

    public void setStatus(BranchStatus status) {
        this.status = status;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }
}
