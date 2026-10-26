package com.mannschaft.app.common.architecture.fixtures.notification;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.transaction.annotation.Transactional;

/**
 * 検体用の合成アノテーション（meta-annotation で {@code @Transactional} を持つ独自注釈）。
 *
 * <p>Spring の実行時にはこれが付いたメソッドは<b>トランザクション内で走る</b>が、
 * 番人の字句走査はメソッドに直接書かれた文字列しか見ないため
 * {@code @BusinessTransaction} という綴りからは TX 文脈を判定できない。
 * {@code GuardBlindSpotFixture} でこの<b>偽陰性</b>を明示的に固定する。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Transactional
public @interface BusinessTransaction {
}
