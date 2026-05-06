package com.mannschaft.app.todo.event;

import com.mannschaft.app.todo.TodoScopeType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * TODO キャッチボール（引き渡し）イベント（F02.3.1 Phase 2）。
 *
 * <p>{@link com.mannschaft.app.todo.service.TodoHandoffService#handoff} が同一トランザクション
 * の最後に発行する。リスナー側で各 toUserId（操作者を除く）に対して
 * {@code TODO_HANDED_OFF} 通知を発火する。</p>
 *
 * @param todoId           対象 TODO ID
 * @param fromUserId       操作者 ID（ボールを渡した人）
 * @param toUserIds        新しい assignees の userId 一覧
 * @param statusLabelName  新しいステータスラベル名（通知本文に埋め込み用）
 * @param message          添えメッセージ（任意。null/空文字も許容）
 * @param todoTitle        TODO タイトル（通知本文に埋め込み用）
 * @param scopeType        TODO のスコープ種別（チーム/組織）
 * @param scopeId          TODO のスコープ ID
 */
@Getter
@AllArgsConstructor
public class TodoHandoffEvent {

    private final Long todoId;
    private final Long fromUserId;
    private final List<Long> toUserIds;
    private final String statusLabelName;
    private final String message;
    private final String todoTitle;
    private final TodoScopeType scopeType;
    private final Long scopeId;
}
