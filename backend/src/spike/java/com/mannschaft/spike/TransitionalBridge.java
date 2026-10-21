package com.mannschaft.spike;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
/** 移行橋渡しクラスだけを解析から除く検体。 */
@Retention(RetentionPolicy.RUNTIME)
public @interface TransitionalBridge { }