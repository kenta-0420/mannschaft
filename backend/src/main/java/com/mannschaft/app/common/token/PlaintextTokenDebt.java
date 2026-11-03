package com.mannschaft.app.common.token;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 平文トークン保存の<b>移行期間だけ</b>の債務宣言（CMP-260826-1457）。
 *
 * <p>W0 で既存の違反（平文トークン列と、金庫を通さない発行メソッド）にすべて付け、
 * W1〜W2 の各波が自分の系統の注釈を外していく。W3 でこの型そのものを削除し、
 * 付け残しをコンパイルエラーで検出する。</p>
 *
 * <p>番人 {@code PlaintextSecretTokenGuardArchTest} は {@link #cmp()} が
 * {@code "CMP-260826-1457"} 以外のとき違反として扱う（他の戦役が流用して番人を黙らせることを防ぐ）。</p>
 *
 * <p>判定は番人 {@code PlaintextSecretTokenGuardArchTest} が行う。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface PlaintextTokenDebt {

    /**
     * 債務を負っている戦役の ID。{@code "CMP-260826-1457"} だけを許す。
     *
     * @return 戦役 ID
     */
    String cmp();
}
