package com.mannschaft.app.todo.dto;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * TODOステータス変更レスポンスDTO。プロジェクト進捗情報を含む。
 */
@Getter
@RequiredArgsConstructor
public class TodoStatusChangeResponse {

    private final Long id;
    private final String status;
    private final LocalDateTime completedAt;
    private final ProjectResponse.UserInfo completedBy;
    private final ProjectProgress projectProgress;

    /**
     * プロジェクト進捗情報。project_idが非NULLの場合のみ返却。
     */
    @Getter
    @RequiredArgsConstructor
    public static class ProjectProgress {
        private final Long projectId;
        private final BigDecimal progressRate;
        private final int totalTodos;
        private final int completedTodos;
    }
}
