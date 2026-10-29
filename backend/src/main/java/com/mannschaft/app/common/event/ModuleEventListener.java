package com.mannschaft.app.common.event;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 専用 executor で commit 後に購読する。リスナー自身はトランザクションを開始しない。 */
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Async("module-event-pool")
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public @interface ModuleEventListener {
    /** 再配信先を特定する、アプリ全体で一意かつ変更しない購読 ID。 */
    @AliasFor(annotation = TransactionalEventListener.class, attribute = "id")
    String id() default "";
}
