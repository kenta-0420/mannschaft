package com.mannschaft.app.common.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #2764 検分是正（欠陥2）の回帰テスト。
 *
 * <p>{@code messages_en.properties} の
 * {@code notification.jobmatching.checkedIn.body} / {@code checkedOut.body} は
 * {@code "The worker's check-in ..."} のようにアポストロフィを単発で含んでいた。
 * {@link java.text.MessageFormat} はアポストロフィをエスケープ開始文字として扱うため、
 * それ以降が literal 扱いとなり {@code {0}}（求人タイトル）が置換されずそのまま
 * {@code "{0}"} という文字列として出力される機能不全が起きていた。</p>
 *
 * <p>本テストは実際に {@link MessageSource}（本番と同じ {@link ReloadableResourceBundleMessageSource}
 * 構成）で英語 locale を解決し、求人タイトルが本文へ実際に埋め込まれること・
 * {@code {0}} が literal のまま残らないことを検証する。</p>
 */
@DisplayName("notification.jobmatching メッセージの MessageFormat 回帰テスト")
class NotificationJobMatchingMessageFormatTest {

    private static final String JOB_TITLE = "倉庫内軽作業スタッフ";

    private MessageSource buildMessageSource() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames("classpath:messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        source.setUseCodeAsDefaultMessage(false);
        return source;
    }

    @Test
    @DisplayName("checkedIn.body: 英語 locale で求人タイトルが本文に含まれ {0} が残らない")
    void checkedIn_英語locale_タイトルが埋め込まれる() {
        MessageSource messageSource = buildMessageSource();

        String body = messageSource.getMessage(
                "notification.jobmatching.checkedIn.body", new Object[]{JOB_TITLE},
                Locale.ENGLISH);

        assertThat(body).contains(JOB_TITLE);
        assertThat(body).doesNotContain("{0}");
    }

    @Test
    @DisplayName("checkedOut.body: 英語 locale で求人タイトルが本文に含まれ {0} が残らない")
    void checkedOut_英語locale_タイトルが埋め込まれる() {
        MessageSource messageSource = buildMessageSource();

        String body = messageSource.getMessage(
                "notification.jobmatching.checkedOut.body", new Object[]{JOB_TITLE},
                Locale.ENGLISH);

        assertThat(body).contains(JOB_TITLE);
        assertThat(body).doesNotContain("{0}");
    }
}
