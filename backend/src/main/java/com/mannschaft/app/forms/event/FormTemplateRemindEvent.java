package com.mannschaft.app.forms.event;

import java.time.LocalDateTime;
import java.util.List;

/**
 * フォームテンプレート リマインド送信イベント（F05.7 Phase 11 第四陣 4-B）。
 *
 * <p>{@code POST /api/v1/{scopeType}/{scopeId}/form-templates/{templateId}/remind} および
 * {@code .../remind-specific} の発火イベント。通知ドメイン（F04.3 プッシュ通知 / メール等）の
 * リスナーがこれを受信して実配信を行う。</p>
 *
 * <p>本イベント自体は事実のみを記録し、Service 層は配信責務を持たない。
 * モジュラーモノリスの原則に従い、forms ドメイン → notification ドメインの結合を
 * 直接の Service 呼び出しではなくイベントで疎結合化する。</p>
 *
 * @param templateId       テンプレート ID
 * @param scopeType        スコープ種別（teams / organizations）
 * @param scopeId          スコープ ID
 * @param remindKind       リマインド種別（{@code ALL_UNSUBMITTED} / {@code SPECIFIC_USERS}）
 * @param targetUserIds    リマインド対象のユーザー ID リスト
 * @param customMessage    任意のカスタムメッセージ（特定者向けのみ。null 可）
 * @param requestedBy      リマインド実行者ユーザー ID
 * @param requestedAt      リマインド実行日時
 *
 * @since 2026-05-17 (F05.7 Phase 11 第四陣 4-B)
 */
public record FormTemplateRemindEvent(
        Long templateId,
        String scopeType,
        Long scopeId,
        RemindKind remindKind,
        List<Long> targetUserIds,
        String customMessage,
        Long requestedBy,
        LocalDateTime requestedAt
) {

    /** リマインド種別。 */
    public enum RemindKind {
        /** 全未提出者宛て（{@code .../remind}）。 */
        ALL_UNSUBMITTED,
        /** 特定者向け（{@code .../remind-specific}）。 */
        SPECIFIC_USERS
    }
}
