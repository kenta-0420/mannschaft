package com.mannschaft.app.cspreport.entity;

import org.hibernate.annotations.IdGeneratorType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** CSP 報告 ID に真正の RFC 9562 UUIDv7 を生成する。 */
@IdGeneratorType(CspUuidV7Generator.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface CspUuidV7Generated {
}
