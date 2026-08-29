package com.mannschaft.app.common.featuregate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Feature gate の対象外として常時到達させる API メソッドを明示する。
 * 実行時の振る舞いは変更せず、{@code ApiGateDeclarationGuardTest} が宣言を監査する。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AlwaysReachable {

    AlwaysReachableCategory category();

    String reason();
}
