package com.mannschaft.app.common.i18n;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.util.Locale;

/**
 * Spring MVC の {@code DispatcherServlet} が使う {@link LocaleResolver} 実装（Bean名固定 {@code localeResolver}）。
 *
 * <h2>CMP-260923-1640 根治の要</h2>
 * <p>main には {@link LocaleResolver} Bean が1つも登録されておらず、Spring Boot 既定の
 * {@code AcceptHeaderLocaleResolver} が使われていた。{@code FrameworkServlet#processRequest} は
 * リクエストごとに {@code localeResolver.resolveLocale(request)} を呼び、その結果で
 * {@link org.springframework.context.i18n.LocaleContextHolder} を<b>上書き</b>する。
 * これにより {@link UserLocaleFilter} が DB locale から正しく解決していても、
 * 後段の DispatcherServlet が Accept-Language（無ければサーバーの既定ロケール）で
 * 上書きしてしまい、ログイン済みユーザーの言語設定が無視されるという欠陥があった。</p>
 *
 * <h2>設計方針: フィルタと二重判定にしない</h2>
 * <p>{@link UserLocaleFilter} は Spring Security のフィルタチェーンより後・
 * DispatcherServlet より前に実行され、「DB locale 優先・未ログインは Accept-Language」という
 * 仕様どおりの判定を既に行っている。本 Resolver はその判定ロジックを複製せず、
 * フィルターがリクエスト属性 {@link UserLocaleFilter#RESOLVED_LOCALE_ATTRIBUTE} に残した結果を
 * そのまま採用する。フィルターが何らかの理由で実行されていない経路（想定外だが保険）に備え、
 * 属性が無い場合のみ {@link DeliveryLocales#DEFAULT_TAG} にフォールバックする。</p>
 *
 * <p>{@link #setLocale} は本アプリに locale 切り替え UI（{@code LocaleChangeInterceptor} 等）が無く
 * 呼ばれる経路が無いため未サポートとする。</p>
 */
@Slf4j
@Component("localeResolver")
public class UserLocaleResolver implements LocaleResolver {

    @Override
    public Locale resolveLocale(HttpServletRequest request) {
        Object attribute = request.getAttribute(UserLocaleFilter.RESOLVED_LOCALE_ATTRIBUTE);
        if (attribute instanceof Locale locale) {
            return locale;
        }
        // UserLocaleFilter が実行されていない想定外経路向けの保険（本来通らない）。
        log.debug("UserLocaleFilter 未実行のリクエストを検出。既定ロケールにフォールバックします: {}",
                request.getRequestURI());
        return DeliveryLocales.toLocale(DeliveryLocales.DEFAULT_TAG);
    }

    @Override
    public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
        throw new UnsupportedOperationException(
                "UserLocaleResolver は locale 切り替え UI を持たないため setLocale をサポートしません。"
                        + "locale 変更は PUT /api/auth/profile 経由で行ってください。");
    }
}
