package com.mannschaft.app.schedule.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * リマインダーレスポンスDTO。
 *
 * <p>機能55 第三陣で相対指定（{@code remindBeforeMinutes}）／絶対指定（{@code remindAt}）の
 * 両方を露出するよう拡張。{@code reminderKind} に応じて FE が表示を出し分けられる。</p>
 *
 * <p>共有予定（出欠リマインダー）は {@code isSent}/{@code sentAt} を、個人予定リマインダーは
 * {@code notified} を使用する。該当しない側は {@code null} となる。</p>
 */
@Builder
@Getter
public class ReminderResponse {

    /** リマインダー id。 */
    private final Long id;

    /** 指定方式（RELATIVE / ABSOLUTE）。 */
    private final String reminderKind;

    /** 絶対指定日時（ABSOLUTE 時。RELATIVE 時は materialize 前のため null のことがある）。 */
    private final LocalDateTime remindAt;

    /** 相対指定：開始 N 分前（RELATIVE 時）。 */
    private final Integer remindBeforeMinutes;

    /** 共有予定（出欠リマインダー）の送信済みフラグ。個人予定では null。 */
    private final Boolean isSent;

    /** 共有予定（出欠リマインダー）の送信日時。個人予定・未送信では null。 */
    private final LocalDateTime sentAt;

    /** 個人予定リマインダーの通知済みフラグ。共有予定では null。 */
    private final Boolean notified;
}
