package com.mannschaft.app.config;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

/**
 * {@code @Transactional(readOnly = true)} が付いたメソッドをレプリカへ自動ルーティングする AOP。
 * {@code @Order(0)} により Spring の @Transactional アスペクト（Order=Integer.MAX_VALUE-1）より
 * 先に実行され、トランザクション開始前にデータソース種別をセットできる。
 */
@Aspect
@Component
@Order(0)
public class ReplicaRoutingAspect {

    @Around("@annotation(org.springframework.transaction.annotation.Transactional)")
    public Object routeToReplica(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        Transactional transactional = method.getAnnotation(Transactional.class);

        if (transactional == null) {
            // クラスレベルのアノテーションも確認する
            transactional = pjp.getTarget().getClass().getAnnotation(Transactional.class);
        }

        boolean useReplica = (transactional != null && transactional.readOnly());
        if (useReplica) {
            DataSourceContextHolder.setDataSourceType(DataSourceType.REPLICA);
        }
        try {
            return pjp.proceed();
        } finally {
            if (useReplica) {
                DataSourceContextHolder.clear();
            }
        }
    }
}
