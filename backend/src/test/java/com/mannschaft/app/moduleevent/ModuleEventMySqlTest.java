package com.mannschaft.app.moduleevent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;

/** 実 Security の filters を有効のまま、M 試練の購読検体と MVC 構成を共有する。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@AutoConfigureMockMvc
@Import(ModuleEventMySqlTestConfiguration.class)
public @interface ModuleEventMySqlTest {
}
