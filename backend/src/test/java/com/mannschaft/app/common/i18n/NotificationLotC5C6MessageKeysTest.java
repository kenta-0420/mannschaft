package com.mannschaft.app.common.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #2715 CMP-055 ロットC-5/C-6 の追加鍵に対する番人。
 *
 * <p>AC-2: 追加した鍵が7ロケールファイルすべてに存在すること。
 * AC-7: en ロケールで組み立てた件名・本文に日本語文字が含まれないこと（未翻訳のまま紛れ込む事故を検出）。</p>
 *
 * <p>MessageSource は実物（{@link ResourceBundleMessageSource}）を使う。モックが引数をそのまま
 * 返す形だと鍵の欠落もフォーマット崩れも検出できないため（C-4 と同じ作法）。</p>
 */
@DisplayName("CMP-055 ロットC-5/C-6 追加鍵の番人")
class NotificationLotC5C6MessageKeysTest {

    private static final Pattern JAPANESE_CHAR = Pattern.compile("[ぁ-ゖァ-ヶ一-龠]");

    private final ResourceBundleMessageSource messageSource = buildMessageSource();

    private static ResourceBundleMessageSource buildMessageSource() {
        ResourceBundleMessageSource ms = new ResourceBundleMessageSource();
        ms.setBasenames("messages");
        ms.setDefaultEncoding("UTF-8");
        ms.setUseCodeAsDefaultMessage(false);
        return ms;
    }

    /**
     * ロットC-5/C-6 で新規追加した鍵一覧（title/body 単位。args は各鍵の呼び出し箇所の引数個数に合わせたダミー値）。
     *
     * <p>args にはあえて日本語を含めない（ASCII のダミー値のみを使う）。args は呼び出し箇所が実行時に
     * 渡す動的な値（アンケート名・氏名・日時の整形済み文字列等）であり、AC-7 が検出したいのは
     * <b>テンプレート側</b>の未翻訳漏れであって、ユーザー入力や日時整形結果（呼び出し元で既に
     * locale 別にフォーマット済み）に日本語が含まれること自体はここでの検出対象ではない。
     * 日本語を含む args を使うと、テンプレートが正しく英訳されていても
     * 「args に含まれる日本語」が誤検出され、テストが常に赤くなる（実際に発生・修正済み）。</p>
     */
    private static List<Object[]> keysWithArgs() {
        return List.of(
                new Object[]{"notification.survey.published.title", new Object[]{"Survey A"}},
                new Object[]{"notification.survey.published.body", new Object[]{"Survey A"}},
                new Object[]{"notification.survey.remind.title", new Object[]{}},
                new Object[]{"notification.survey.remind.body", new Object[]{"Survey A"}},
                new Object[]{"notification.survey.deadlineExtended.title", new Object[]{}},
                new Object[]{"notification.survey.deadlineExtended.body", new Object[]{"Survey A", "Sep 1, 2026 10:00"}},
                new Object[]{"notification.onboarding.reminder.title", new Object[]{}},
                new Object[]{"notification.onboarding.reminder.body", new Object[]{}},
                new Object[]{"notification.onboarding.reminder.deadlineBody", new Object[]{"Sep 1, 2026"}},
                new Object[]{"notification.onboarding.overdue.title", new Object[]{}},
                new Object[]{"notification.onboarding.overdue.body", new Object[]{}},
                new Object[]{"notification.memberinfo.updateReminder.title", new Object[]{}},
                new Object[]{"notification.memberinfo.updateReminder.body", new Object[]{"Contact Info"}},
                new Object[]{"notification.actionmemo.reminder.title", new Object[]{}},
                new Object[]{"notification.actionmemo.reminder.body", new Object[]{}},
                new Object[]{"notification.admin.batchCompleted.title", new Object[]{"BATCH_X"}},
                new Object[]{"notification.admin.batchCompleted.body", new Object[]{5000}},
                new Object[]{"notification.admin.batchFailed.title", new Object[]{"BATCH_X"}},
                new Object[]{"notification.admin.batchFailed.body", new Object[]{"NPE at Foo.java:1"}},
                new Object[]{"notification.advertising.push.defaultTitle", new Object[]{}},
                new Object[]{"notification.advertising.push.bodyPrefix", new Object[]{}},
                new Object[]{"notification.advertising.invoiceOverdue.title", new Object[]{}},
                new Object[]{"notification.advertising.invoiceOverdue.body", new Object[]{"INV-1", "2026-08-01"}},
                new Object[]{"notification.analytics.backfillCompleted.title", new Object[]{}},
                new Object[]{"notification.analytics.backfillCompleted.body",
                        new Object[]{"job1", "2026-01-01", "2026-01-31"}},
                new Object[]{"notification.chat.inquiryReceived.title", new Object[]{}},
                new Object[]{"notification.chat.inquiryReceived.body", new Object[]{"Taro Yamada", "Support"}},
                new Object[]{"notification.circulation.reminder.title", new Object[]{}},
                new Object[]{"notification.circulation.reminder.body", new Object[]{"Document A"}},
                new Object[]{"notification.contact.common.defaultActorName", new Object[]{}},
                new Object[]{"notification.contact.inviteUsed.title", new Object[]{}},
                new Object[]{"notification.contact.inviteUsed.body", new Object[]{"Hanako Tanaka"}},
                new Object[]{"notification.contact.requestReceived.title", new Object[]{}},
                new Object[]{"notification.contact.requestReceived.body", new Object[]{"Hanako Tanaka"}},
                new Object[]{"notification.contact.requestAccepted.title", new Object[]{}},
                new Object[]{"notification.contact.requestAccepted.body", new Object[]{"Hanako Tanaka"}},
                new Object[]{"notification.digest.completed.title", new Object[]{}},
                new Object[]{"notification.digest.completed.body", new Object[]{}},
                new Object[]{"notification.digest.failed.title", new Object[]{}},
                new Object[]{"notification.digest.failed.body", new Object[]{}},
                new Object[]{"notification.inbox.snoozeRevival.title", new Object[]{}},
                new Object[]{"notification.inbox.snoozeRevival.body", new Object[]{}},
                new Object[]{"notification.quickmemo.reminder.title", new Object[]{}},
                new Object[]{"notification.quickmemo.reminder.body", new Object[]{3}},
                new Object[]{"notification.recruitment.friendListing.title", new Object[]{"Listing A"}},
                new Object[]{"notification.recruitment.friendListing.body", new Object[]{"Listing A"}},
                new Object[]{"notification.reflection.recallReminder.title", new Object[]{}},
                new Object[]{"notification.reflection.recallReminder.body", new Object[]{"Theme A"}},
                new Object[]{"notification.repairplan.termReminder.title", new Object[]{}},
                new Object[]{"notification.repairplan.termReminder.body",
                        new Object[]{"Apr 1, 2026", "Mar 31, 2027", 25}},
                new Object[]{"notification.safetycheck.reminder.title", new Object[]{}},
                new Object[]{"notification.safetycheck.reminder.body", new Object[]{}},
                new Object[]{"notification.todo.milestoneUnlocked.title", new Object[]{}},
                new Object[]{"notification.todo.milestoneForceUnlocked.title", new Object[]{}},
                new Object[]{"notification.todo.milestoneUnlocked.body", new Object[]{"Milestone A"}},
                new Object[]{"notification.todo.handoff.title", new Object[]{}},
                new Object[]{"notification.todo.handoff.body", new Object[]{"Taro Yamada", "TODO-1", "In Progress"}}
        );
    }

    @Test
    @DisplayName("AC-2: 追加した鍵が7ロケールすべてで解決できる（欠落があれば NoSuchMessageException で fail）")
    void 全鍵が7ロケールで解決できる() {
        List<Locale> locales = List.of(
                Locale.forLanguageTag("ja"), Locale.forLanguageTag("en"), Locale.forLanguageTag("zh"),
                Locale.forLanguageTag("ko"), Locale.forLanguageTag("es"), Locale.forLanguageTag("de"),
                Locale.ROOT);
        for (Object[] entry : keysWithArgs()) {
            String key = (String) entry[0];
            Object[] args = (Object[]) entry[1];
            for (Locale locale : locales) {
                String resolved = messageSource.getMessage(key, args, locale);
                assertThat(resolved).as("key=%s locale=%s", key, locale).isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("AC-7: en ロケールで組み立てた文言に日本語文字が含まれない")
    void enロケールで日本語文字が残らない() {
        List<String> violations = new java.util.ArrayList<>();
        for (Object[] entry : keysWithArgs()) {
            String key = (String) entry[0];
            Object[] args = (Object[]) entry[1];
            String resolved = messageSource.getMessage(key, args, Locale.forLanguageTag("en"));
            if (JAPANESE_CHAR.matcher(resolved).find()) {
                violations.add(key + "=" + resolved);
            }
        }
        assertThat(violations)
                .as("en ロケールの文言に日本語文字が残っていないこと（未翻訳キーの取り違い検出）: %s", violations)
                .isEmpty();
    }

    @Test
    @DisplayName("AC-5: en の値にプレースホルダを含みつつ奇数個のアポストロフィが残っていない（回帰検出）")
    void enロケールでプレースホルダ未解決が残らない() {
        Pattern unresolvedPlaceholder = Pattern.compile("\\{\\d+\\}");
        List<String> violations = new java.util.ArrayList<>();
        for (Object[] entry : keysWithArgs()) {
            String key = (String) entry[0];
            Object[] args = (Object[]) entry[1];
            if (args.length == 0) {
                continue;
            }
            String resolved = messageSource.getMessage(key, args, Locale.forLanguageTag("en"));
            if (unresolvedPlaceholder.matcher(resolved).find()) {
                violations.add(key + "=" + resolved);
            }
        }
        assertThat(violations)
                .as("プレースホルダが未解決のまま残っていないこと（MessageFormat アポストロフィ事故の兆候）: %s", violations)
                .isEmpty();
    }
}
