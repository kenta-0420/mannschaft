package com.mannschaft.spike;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.core.annotation.AliasFor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.event.TransactionalEventListener;
/** TXを持たず専用executorで動く合成購読注釈の検体。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Async("module-event-pool")
@TransactionalEventListener
public @interface ModuleEventListener {
    @AliasFor(annotation = TransactionalEventListener.class, attribute = "id")
    String id();
}