package com.mannschaft.app.social.announcement.adapter;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.social.announcement.AnnouncementContentRequest;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import com.mannschaft.app.todo.TodoScopeType;
import com.mannschaft.app.todo.dto.CreateTodoRequest;
import com.mannschaft.app.todo.dto.TodoResponse;
import com.mannschaft.app.todo.service.TodoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;

/**
 * F02.8 TODO タスクチャネルアダプター。
 *
 * <p>{@link TodoService} を呼び出して共有 TODO を作成し、作成された TODO の ID を返す。
 * 告知ウィザードでは担当者未設定（assignee_ids = []）で作成する。
 * 担当者は ADMIN が後から TODO 詳細ページで割り当てるか、各メンバーが名乗り出る運用を前提とする。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TodoAnnouncementAdapter implements AnnouncementChannelAdapter {

    private final TodoService todoService;

    @Override
    public AnnouncementSourceType getSourceType() {
        return AnnouncementSourceType.TODO;
    }

    @Override
    public Long createContent(AnnouncementContentRequest content, String scopeType,
                              Long scopeId, String visibility, Long userId) {
        TodoScopeType todoScopeType = TodoScopeType.valueOf(scopeType);

        CreateTodoRequest request = new CreateTodoRequest(
                content.getTitle(),
                content.getBody(),   // description として使用
                null,                // projectId（告知 TODO はプロジェクト未所属）
                null,                // milestoneId
                null,                // priority（デフォルト MEDIUM）
                content.getEndAt() != null ? content.getEndAt().toLocalDate() : null, // dueDate
                null,                // dueTime
                null,                // sortOrder
                Collections.emptyList(), // assigneeIds（担当者未設定）
                null,                // parentId
                content.getStartAt() != null ? content.getStartAt().toLocalDate() : null, // startDate
                null,                // linkedScheduleId
                null,                // progressRate
                false                // createLinkedSchedule
        );

        ApiResponse<TodoResponse> response = todoService.createTodo(
                todoScopeType, scopeId, request, userId);

        Long todoId = response.getData().getId();
        log.info("TODO作成完了 todoId={}, scopeType={}, scopeId={}", todoId, scopeType, scopeId);
        return todoId;
    }

    @Override
    public String buildContentUrl(String scopeType, Long scopeId, Long contentId) {
        String scopePath = "TEAM".equalsIgnoreCase(scopeType) ? "teams" : "organizations";
        return "/" + scopePath + "/" + scopeId + "/todos/" + contentId;
    }
}
