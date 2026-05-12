package com.mannschaft.app.repairplan.module;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * {@link RequireRepairPlanModule} 付きメソッドに対する事前ガード AOP。
 *
 * <p>{@code @Order(10)} を指定し、Spring の {@code @Transactional}（既定 Ordered.LOWEST-1）
 * より十分先に走らせる。これによりトランザクション開始前にテンプレ／モジュール判定を完了し、
 * 不要な DB 書き込みコネクションを抑止できる。</p>
 *
 * <p>引数解決には Spring 標準の {@code MethodSignature.getParameterNames()} を用いる。
 * これは Java 8+ で {@code -parameters} コンパイルオプションが有効な場合に動作する
 * （本プロジェクトでは {@code build.gradle} の {@code compileJava.options.compilerArgs}
 * で標準有効化されている）。動作しない環境では Spring の {@code @PathVariable("scopeId")}
 * 等のアノテーションフォールバックを検討する余地があるが、現状は単純解決に限定する。</p>
 */
@Aspect
@Component
@Order(10)
@RequiredArgsConstructor
@Slf4j
public class RepairPlanModuleGuardAspect {

    private final RepairPlanModuleGuard guard;

    @Around("@annotation(com.mannschaft.app.repairplan.module.RequireRepairPlanModule)"
            + " || execution(@com.mannschaft.app.repairplan.module.RequireRepairPlanModule * *(..))")
    public Object guard(ProceedingJoinPoint pjp) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        // JDK プロキシではインターフェースのメソッドが渡るためアノテーションが取れない。
        // 実装クラスの対応メソッドを解決してから探す。
        Method targetMethod = AopUtils.getMostSpecificMethod(method, pjp.getTarget().getClass());
        RequireRepairPlanModule annotation =
                AnnotationUtils.findAnnotation(targetMethod, RequireRepairPlanModule.class);
        // インターフェースのみアノテーションが付いている場合は targetMethod 経由でも null になる。
        // その場合はデフォルト値（{@code "scopeType"} / {@code "scopeId"}）を使う。
        String scopeTypeParam = (annotation != null) ? annotation.scopeTypeParam() : "scopeType";
        String scopeIdParam = (annotation != null) ? annotation.scopeIdParam() : "scopeId";

        String[] paramNames = signature.getParameterNames();
        Object[] args = pjp.getArgs();

        String scopeType = (String) findArg(paramNames, args, scopeTypeParam);
        Long scopeId = toLong(findArg(paramNames, args, scopeIdParam));

        if (log.isDebugEnabled()) {
            log.debug("RepairPlanModuleGuardAspect: method={}, scopeType={}, scopeId={}",
                    method.getName(), scopeType, scopeId);
        }

        guard.requireEnabled(scopeType, scopeId);

        return pjp.proceed();
    }

    private static Object findArg(String[] names, Object[] args, String target) {
        if (names == null || args == null) return null;
        for (int i = 0; i < names.length; i++) {
            if (target.equals(names[i])) {
                return args[i];
            }
        }
        return null;
    }

    private static Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try {
                return Long.parseLong(s);
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }
}
