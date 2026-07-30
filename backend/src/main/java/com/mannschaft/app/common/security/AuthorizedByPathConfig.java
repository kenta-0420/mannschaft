package com.mannschaft.app.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * このエンドポイントの認可が、Controller / Service のコード上ではなく
 * <b>{@code SecurityConfig} のパス単位の宣言的認可</b>（{@code hasRole()} /
 * {@code hasAnyRole()} / {@code authenticated()} 等の {@code requestMatcher}）で
 * 強制済みであることを示す<b>監査済マーカー</b>。
 *
 * <p>番人（{@code AuthzControllerGuardArchTest}）は Controller の Mapping メソッドが
 * 「認可シグナルを一切持たない」事故を CI 静的解析で機械的に検知するが、認可が
 * {@code SecurityConfig} のフィルタチェーン側に宣言されている場合、Controller のバイトコードには
 * 何の痕跡も残らないため呼び出しグラフ判定では拾えない。本注釈はそうしたエンドポイントを
 * 凍結ストアへ落とさず、<b>認可済みとして明示的に承認する</b>ための監査マーカーである。</p>
 *
 * <p><b>付与時の必須条件</b>: 付与対象の Javadoc またはコメントに、<b>根拠となる
 * {@code SecurityConfig} の {@code requestMatcher} を明記すること</b>
 * （例: {@code SecurityConfig.java:123 — requestMatchers("/api/v1/admin/**").hasRole("ADMIN")}）。
 * 根拠なき付与は番人を骨抜きにするバックドアになるため厳禁。パス定義を変更・削除した際は
 * 本注釈の根拠が失効するため、必ず併せて見直すこと。</p>
 *
 * <p><b>{@link AuthorizedInService} との使い分け</b>: 「Service 内で認可済み」を意味する
 * {@link AuthorizedInService} を、実際には SecurityConfig のパス認可で守られているだけの
 * エンドポイントへ貼ると<b>誤った証跡</b>となり、後年の監査で認可の実在箇所を見失う。
 * 認可の所在に応じて本注釈と使い分けること。</p>
 *
 * <p>メソッドに付与するとその 1 エンドポイントのみ、クラスに付与するとそのクラスの
 * 全 Mapping メソッドが認可済みとして扱われる。</p>
 *
 * @see AuthorizedInService
 * @see IntentionallyPublic
 * @see SelfScopedEndpoint
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthorizedByPathConfig {
}
