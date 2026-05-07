package com.mannschaft.app.todo.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * TODO キャッチボール履歴レスポンス DTO（F02.3.1 Phase 2）。
 *
 * <p>削除済みラベルのスナップショット名を含む。フロント側はラベル ID が
 * NULL でも {@code labelName} が残っていれば「（削除済み）」表示にフォールバックする。</p>
 *
 * @param id                       履歴 ID
 * @param fromUser                 操作者（ボールを渡した人）
 * @param fromAssignees            操作前の assignees（ボールを持っていた人）
 * @param toAssignees              操作後の assignees（ボールを受け取った人）
 * @param previousStatus           操作前のステータス文字列（OPEN/IN_PROGRESS/COMPLETED 等）
 * @param previousStatusLabel      操作前のラベル情報（NULL 可、ラベル削除済みでもスナップショット名は残る）
 * @param newStatus                操作後のステータス文字列
 * @param newStatusLabel           操作後のラベル情報
 * @param message                  添えメッセージ
 * @param createdAt                作成日時
 */
public record TodoHandoffResponse(
        Long id,
        UserSummary fromUser,
        List<UserSummary> fromAssignees,
        List<UserSummary> toAssignees,
        String previousStatus,
        LabelInfo previousStatusLabel,
        String newStatus,
        LabelInfo newStatusLabel,
        String message,
        LocalDateTime createdAt
) {

    /**
     * 履歴行に埋め込むユーザー要約。
     *
     * @param userId      ユーザー ID
     * @param displayName 表示名（NameResolverService で解決）
     */
    public record UserSummary(Long userId, String displayName) {
    }

    /**
     * 履歴行に埋め込むラベル情報のスナップショット。
     *
     * @param id      ラベル ID（削除済みの場合 NULL）
     * @param name    ラベル名（スナップショット。NULL の場合は「（不明）」想定）
     * @param bucket  バケット文字列（OPEN/IN_PROGRESS/COMPLETED）
     * @param color   ラベル色（#RRGGBB、NULL 可）
     * @param deleted 元ラベルが削除済みかどうか
     */
    public record LabelInfo(Long id, String name, String bucket, String color, boolean deleted) {
    }
}
