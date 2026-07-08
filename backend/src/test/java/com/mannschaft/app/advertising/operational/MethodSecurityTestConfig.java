package com.mannschaft.app.advertising.operational;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * {@code @WebMvcTest} スライスでメソッドセキュリティ（{@code @PreAuthorize}）を点火するための
 * 追加テスト設定（F09.19.5 AC-5.5）。
 *
 * <p>本アプリは既定では {@code @EnableMethodSecurity} 未有効のため、クラスレベル {@code @PreAuthorize} が
 * 実機で効かない。二重ガード（防御の多層化）として「メソッドセキュリティを有効化した場合に
 * クラス注釈単体で SYSTEM_ADMIN 以外を弾く」ことを検証する目的で、テスト時のみ点火する。</p>
 *
 * <p>ネストした {@code @Configuration} は {@code @WebMvcTest} のスライス構成を上書きして
 * コントローラ登録を失わせる（全ルート 404 化）ため、<b>トップレベルの {@code @TestConfiguration}</b>
 * として切り出し {@code @Import} で加算適用する。</p>
 */
@TestConfiguration
@EnableMethodSecurity
public class MethodSecurityTestConfig {
}
