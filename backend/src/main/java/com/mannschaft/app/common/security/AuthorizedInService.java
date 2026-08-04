package com.mannschaft.app.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * このエンドポイントの認可が、認可番人（{@code AuthzControllerGuardArchTest}）の白名簿クラス
 * （{@code AccessControlService} / {@code ContentVisibilityChecker} / {@code *AccessGuard} /
 * {@code *AccessService}）を介さず、<b>Service 内の別方式で実施済み</b>であることを示す
 * <b>監査済マーカー</b>。
 *
 * <p>番人は Controller の Mapping メソッドが「認可シグナルを一切持たない」事故を
 * CI 静的解析で機械的に検知するが、以下のような正当な認可方式は番人の呼び出しグラフ判定
 * （{@code @PreAuthorize} / 白名簿クラスへのメソッド呼び出し）では拾えない。本注釈は
 * そうしたエンドポイントを凍結ストアへ落とさず、<b>認可済みとして明示的に承認する</b>ための
 * 監査マーカーである。</p>
 *
 * <ul>
 *   <li>webhook 署名検証（HMAC 等）で正当性を担保するエンドポイント</li>
 *   <li>{@code SecurityConfig} のパス単位 {@code hasRole()} / {@code authenticated()} で
 *       宣言的に保護されるエンドポイント</li>
 *   <li>capability トークン（署名付き URL・ワンタイムトークン等）で認可するエンドポイント</li>
 * </ul>
 *
 * <p><b>付与時の必須条件</b>: 認可が実際にどこで・どの方式で実施されているかを、付与対象の
 * Javadoc またはコメントで<b>根拠を明記すること</b>。根拠なき付与は番人を骨抜きにする
 * バックドアになるため厳禁。付与は監査（本戦役 Wave5）を経た合意のうえで行う。</p>
 *
 * <p><b>{@link SelfScopedEndpoint} との使い分け（重要）</b>: 本注釈が主張するのは
 * 「<b>認可判定が実施されている</b>」ことである。一方、検索対象が認証主体に束縛されていて
 * そもそも他人のデータへ到達する経路が無い EP（自己スコープ EP）が主張するのは
 * 「<b>到達できない</b>」ことであり、両者は別の主張である。過去には自己スコープ EP へ
 * 本注釈を流用した例があるが、専用マーカー {@link SelfScopedEndpoint} の新設をもって
 * <b>その転用は今後禁止</b>とする（証跡の意味が異なり、後年の監査で認可の実在箇所を見失うため）。</p>
 *
 * <p>メソッドに付与するとその 1 エンドポイントのみ、クラスに付与するとそのクラスの
 * 全 Mapping メソッドが認可済みとして扱われる。</p>
 *
 * @see com.mannschaft.app.common.security.AccessGuard
 * @see AuthorizedByPathConfig
 * @see IntentionallyPublic
 * @see SelfScopedEndpoint
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthorizedInService {
}
