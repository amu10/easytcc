package io.github.easytcc.spring;

import io.github.easytcc.core.BranchInvoker;
import io.github.easytcc.core.BranchTransaction;
import io.github.easytcc.core.EasyTccContext;
import io.github.easytcc.core.EasyTccException;
import java.lang.reflect.Method;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.util.ReflectionUtils;

public final class SpringBranchInvoker implements BranchInvoker {
    private final ApplicationContext context;

    public SpringBranchInvoker(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void validate(BranchTransaction branch) {
        resolve(branch, branch.getConfirmMethod());
        resolve(branch, branch.getCancelMethod());
    }

    @Override
    public void confirm(BranchTransaction branch) throws Exception {
        invoke(branch, branch.getConfirmMethod());
    }

    @Override
    public void cancel(BranchTransaction branch) throws Exception {
        invoke(branch, branch.getCancelMethod());
    }

    private void invoke(BranchTransaction branch, String methodName) throws Exception {
        Class<?> type = Class.forName(branch.getBeanName());
        String[] names = context.getBeanNamesForType(type);
        if (names.length == 0)
            throw new EasyTccException("No Spring bean found for " + type.getName());
        Object bean = context.getBean(names[0]);
        Class<?> targetType = AopUtils.getTargetClass(bean);
        Method method = findCompatible(targetType, methodName, branch.getArguments());
        ReflectionUtils.makeAccessible(method);
        String previousXid = EasyTccContext.currentXid();
        String previousBranch = EasyTccContext.currentBranchId();
        EasyTccContext.bind(branch.getXid());
        EasyTccContext.bindBranch(branch.getBranchId());
        try {
            method.invoke(bean, branch.getArguments());
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new EasyTccException("Branch invocation failed", cause);
        } finally {
            if (previousXid == null) EasyTccContext.clear();
            else EasyTccContext.bind(previousXid);
            if (previousBranch == null) EasyTccContext.clearBranch();
            else EasyTccContext.bindBranch(previousBranch);
        }
    }

    private Method resolve(BranchTransaction branch, String methodName) {
        try {
            Class<?> type = Class.forName(branch.getBeanName());
            String[] names = context.getBeanNamesForType(type);
            if (names.length != 1)
                throw new EasyTccException(
                        "Expected exactly one Spring bean for "
                                + type.getName()
                                + ", found "
                                + names.length);
            return findCompatible(
                    AopUtils.getTargetClass(context.getBean(names[0])),
                    methodName,
                    branch.getArguments());
        } catch (ClassNotFoundException e) {
            throw new EasyTccException("Branch type not found: " + branch.getBeanName(), e);
        }
    }

    private static Method findCompatible(Class<?> type, String name, Object[] args) {
        Method match = null;
        for (Method method : type.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterTypes().length != args.length)
                continue;
            Class<?>[] parameters = method.getParameterTypes();
            boolean compatible = true;
            for (int i = 0; i < parameters.length; i++) {
                if (args[i] != null && !wrap(parameters[i]).isInstance(args[i])) {
                    compatible = false;
                    break;
                }
                if (args[i] == null && parameters[i].isPrimitive()) {
                    compatible = false;
                    break;
                }
            }
            if (!compatible) continue;
            if (match != null)
                throw new EasyTccException("Ambiguous method: " + type.getName() + "#" + name);
            match = method;
        }
        if (match == null)
            throw new EasyTccException(
                    "Compatible method not found: " + type.getName() + "#" + name);
        return match;
    }

    private static Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        return Void.class;
    }
}
