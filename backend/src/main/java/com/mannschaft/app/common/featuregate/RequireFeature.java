package com.mannschaft.app.common.featuregate;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Gate 基盤工事③: フィーチャーフラグによる BE 側の入口ガードを表すマーカーアノテーション。
 *
 * <p>付与されたメソッド（またはクラス配下の全 public メソッド）は
 * {@link FeatureGateAspect} により実行前にガードされ、{@link #value()} に列挙した
 * フラグキーが<b>すべて有効</b>でなければ
 * {@link com.mannschaft.app.common.BusinessException}（{@code FEATURE_GATE_001}）で拒否される。</p>
 *
 * <p><b>キーの系統</b>: {@code value()} に書くのは {@code feature_flags.flag_key}
 * （SCREAMING_SNAKE、例 {@code FEATURE_SHIFT_ENABLED}）であり、
 * 棚卸し台帳 {@code docs/inventory/feature-inventory.yaml} の {@code release.gate_key} と同一文字列である。
 * 台帳の kebab な {@code feature_key} や課金の {@code feature_catalog.feature_key} とは別系統。</p>
 *
 * <p>⚠️ 試練の骨格。属性定義のみを置いてあり、実際のガード挙動は出陣で実装する。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireFeature {

    /**
     * 要求するフィーチャーフラグキー（{@code feature_flags.flag_key}）。
     *
     * <p>複数指定した場合は AND（すべて有効でなければ拒否）。
     * 文字列リテラルで書くこと・実在するキーであることは番人
     * {@code FeatureGateAnnotationKeyGuardTest} が CI で機械的に検証する。</p>
     */
    String[] value();
}
