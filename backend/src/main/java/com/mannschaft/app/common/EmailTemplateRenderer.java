package com.mannschaft.app.common;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Locale;

/**
 * 認証系メールの HTML 本文を Thymeleaf テンプレートから生成するコンポーネント。
 * PDF 生成と同じ {@link TemplateEngine} Bean を共用する。
 * メッセージキーは classpath:email/email_{locale}.properties から解決する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailTemplateRenderer {

    private final TemplateEngine templateEngine;
    private final MessageSource messageSource;

    /**
     * メール認証メールの HTML 本文を生成する。
     *
     * @param displayName 宛先ユーザーの表示名
     * @param verifyUrl   メール認証 URL
     * @param locale      送信先ロケール
     * @return HTML 文字列
     */
    public String renderVerificationEmail(String displayName, String verifyUrl, Locale locale) {
        Context context = new Context(locale);
        context.setVariable("displayName", displayName);
        context.setVariable("verifyUrl", verifyUrl);
        return templateEngine.process("email/verification", context);
    }

    /**
     * パスワードリセットメールの HTML 本文を生成する（displayName なし）。
     *
     * @param resetUrl リセット URL
     * @param locale   送信先ロケール
     * @return HTML 文字列
     */
    public String renderPasswordResetEmail(String resetUrl, Locale locale) {
        Context context = new Context(locale);
        context.setVariable("resetUrl", resetUrl);
        return templateEngine.process("email/password-reset", context);
    }

    /**
     * 指定キーのメッセージをロケールに合わせて解決する。
     *
     * @param code   メッセージキー（例: "email.verification.subject"）
     * @param locale ロケール
     * @return メッセージ文字列
     */
    public String resolveMessage(String code, Locale locale) {
        return messageSource.getMessage(code, null, code, locale);
    }
}
