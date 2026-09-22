package io.github.easytcc.spring;

import io.github.easytcc.annotation.EasyTccAction;
import io.github.easytcc.annotation.EasyTccTransactional;
import io.github.easytcc.core.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import java.lang.reflect.Method;

@Aspect
public final class EasyTccAspect {
    private final TransactionManager manager;
    public EasyTccAspect(TransactionManager manager) { this.manager = manager; }

    @Around("@annotation(io.github.easytcc.annotation.EasyTccTransactional)")
    public Object transaction(ProceedingJoinPoint point) throws Throwable {
        if (EasyTccContext.isActive()) return point.proceed();
        Method method = specificMethod(point);
        EasyTccTransactional annotation = AnnotationUtils.findAnnotation(method, EasyTccTransactional.class);
        String name = annotation.name().isEmpty() ? method.getDeclaringClass().getSimpleName() + "." + method.getName() : annotation.name();
        GlobalTransaction tx = manager.begin(name, annotation.timeout());
        Object result;
        try {
            result = point.proceed();
        } catch (Throwable businessError) {
            try { manager.cancel(tx.getXid()); }
            catch (Throwable cancelError) { businessError.addSuppressed(cancelError); }
            throw businessError;
        } finally {
            EasyTccContext.clear();
        }
        manager.confirm(tx.getXid());
        return result;
    }

    @Around("@annotation(io.github.easytcc.annotation.EasyTccAction)")
    public Object action(ProceedingJoinPoint point) throws Throwable {
        if (!EasyTccContext.isActive()) return point.proceed();
        Method method = specificMethod(point);
        EasyTccAction annotation = AnnotationUtils.findAnnotation(method, EasyTccAction.class);
        String name = annotation.name().isEmpty() ? method.getDeclaringClass().getSimpleName() + "." + method.getName() : annotation.name();
        BranchTransaction branch = manager.registerBranch(name, method.getDeclaringClass().getName(),
                annotation.confirm(), annotation.cancel(), point.getArgs());
        try {
            Object result = point.proceed();
            manager.markTrySucceeded(branch);
            return result;
        } catch (Throwable error) {
            manager.markTryFailed(branch, error);
            throw error;
        }
    }

    private static Method specificMethod(ProceedingJoinPoint point) {
        Method signatureMethod = ((MethodSignature) point.getSignature()).getMethod();
        return AopUtils.getMostSpecificMethod(signatureMethod, AopUtils.getTargetClass(point.getTarget()));
    }
}
