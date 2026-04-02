package com.mannschaft.app.gdpr;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 個人データを保持するクラスに付与するアノテーション。
 * PersonalDataCollector がカテゴリキーを使ってデータ収集を行う。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PersonalData {
    /** PersonalDataCollector のカテゴリキー */
    String category();
}
