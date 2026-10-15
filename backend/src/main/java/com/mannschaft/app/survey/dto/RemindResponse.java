package com.mannschaft.app.survey.dto;

/**
 * F05.4 アンケート督促 API のレスポンス。
 *
 * <p>督促送信に成功した場合に返却する。送信先人数・残回数・案内メッセージを含む。</p>
 *
 * @param surveyId             対象アンケートID
 * @param remindedCount        実際にリマインドを送信した未回答者の人数
 * @param remainingRemindQuota 残りの督促可能回数（最大3回 - 既送信回数）
 * @param message              ユーザー向け案内メッセージ
 */
public record RemindResponse(
        Long surveyId,
        Integer remindedCount,
        Integer remainingRemindQuota,
        String message
) {}
