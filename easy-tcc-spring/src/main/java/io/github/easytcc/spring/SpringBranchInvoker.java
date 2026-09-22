package io.github.easytcc.spring;

import io.github.easytcc.core.BranchInvoker;
import io.github.easytcc.core.BranchTransaction;
import io.github.easytcc.core.EasyTccException;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;
import java.lang.reflect.Method;

public final class SpringBranchInvoker implements BranchInvoker {
    private final ApplicationContext context;
    public SpringBranchInvoker(ApplicationContext context) { this.context = context; }
    @Override public void confirm(BranchTransaction branch) throws Exception { invoke(branch, branch.getConfirmMethod()); }
    @Override public void cancel(BranchTransaction branch) throws Exception { invoke(branch, branch.getCancelMethod()); }

    private void invoke(BranchTransaction branch, String methodName) throws Exception {
        Class<?> type = Class.forName(branch.getBeanName());
        String[] names = context.getBeanNamesForType(type);
        if (names.length == 0) throw new EasyTccException("No Spring bean found for " + type.getName());
        Object bean = context.getBean(names[0]);
        Class<?> targetType = AopUtils.getTargetClass(bean);
        Method method = findCompatible(targetType, methodName, branch.getArguments());
        if (method == null) throw new EasyTccException("Method not found: " + targetType.getName() + "#" + methodName);
        ReflectionUtils.makeAccessible(method);
        try { method.invoke(bean, branch.getArguments()); }
        catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new EasyTccException("Branch invocation failed", cause);
        }
    }

    private static Method findCompatible(Class<?> type, String name, Object[] args) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == args.length) return method;
        }
        return null;
    }
}
