package com.mannschaft.app.todo.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * TODOレスポンスDTO。
 * 旧30フィールドフラット構造をネストDTOに刷新（Wave 1 第一陣）。
 */
@Getter
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TodoResponse {

    private Long id;
    private TodoScopeDto scope;
    private TodoContentDto content;
    private TodoScheduleDto schedule;
    private TodoStatusDto status;
    private List<AssigneeResponse> assignees;
    private TodoHierarchyDto hierarchy;
    private TodoAuditDto audit;

    public record TodoScopeDto(
            String scopeType,
            Long scopeId,
            Long projectId,
            Long milestoneId,
            /** TEAM / ORGANIZATION の slug（URLルーティング用）。PERSONAL は null。 */
            String scopeSlug
    ) {}

    public record TodoContentDto(
            String title,
            String description,
            LocalDate startDate,
            BigDecimal progressRate,
            Boolean progressManual,
            int sortOrder
    ) {}

    public record TodoScheduleDto(
            LocalDate dueDate,
            LocalTime dueTime,
            Long daysRemaining,
            Long linkedScheduleId
    ) {}

    public record TodoStatusDto(
            String status,
            String priority,
            LocalDateTime completedAt,
            TodoStatusLabelInfo statusLabel
    ) {}

    public record TodoHierarchyDto(
            Long parentId,
            Integer depth,
            List<TodoResponse> children,
            int childCount,
            int descendantCompletedCount,
            int descendantTotalCount
    ) {}

    public record TodoAuditDto(
            LocalDateTime createdAt,
            LocalDateTime updatedAt,
            ProjectResponse.UserInfo createdBy,
            ProjectResponse.UserInfo completedBy
    ) {}

    /**
     * TODO レスポンスに埋め込むステータスラベル要約。
     */
    public record TodoStatusLabelInfo(Long id, String name, String bucket, String color) {
    }

    // ===== 後方互換アクセサ（テスト・既存コードのコンパイルを維持）=====

    /** @deprecated scope.scopeType() を使うこと */
    @Deprecated
    public String getScopeType() {
        return scope != null ? scope.scopeType() : null;
    }

    /** @deprecated scope.scopeId() を使うこと */
    @Deprecated
    public Long getScopeId() {
        return scope != null ? scope.scopeId() : null;
    }

    /** @deprecated scope.projectId() を使うこと */
    @Deprecated
    public Long getProjectId() {
        return scope != null ? scope.projectId() : null;
    }

    /** @deprecated scope.milestoneId() を使うこと */
    @Deprecated
    public Long getMilestoneId() {
        return scope != null ? scope.milestoneId() : null;
    }

    /** @deprecated content.title() を使うこと */
    @Deprecated
    public String getTitle() {
        return content != null ? content.title() : null;
    }

    /** @deprecated content.description() を使うこと */
    @Deprecated
    public String getDescription() {
        return content != null ? content.description() : null;
    }

    /** @deprecated content.startDate() を使うこと */
    @Deprecated
    public LocalDate getStartDate() {
        return content != null ? content.startDate() : null;
    }

    /** @deprecated content.progressRate() を使うこと */
    @Deprecated
    public BigDecimal getProgressRate() {
        return content != null ? content.progressRate() : null;
    }

    /** @deprecated content.progressManual() を使うこと */
    @Deprecated
    public Boolean getProgressManual() {
        return content != null ? content.progressManual() : null;
    }

    /** @deprecated content.sortOrder() を使うこと */
    @Deprecated
    public int getSortOrder() {
        return content != null ? content.sortOrder() : 0;
    }

    /** @deprecated schedule.dueDate() を使うこと */
    @Deprecated
    public LocalDate getDueDate() {
        return schedule != null ? schedule.dueDate() : null;
    }

    /** @deprecated schedule.dueTime() を使うこと */
    @Deprecated
    public LocalTime getDueTime() {
        return schedule != null ? schedule.dueTime() : null;
    }

    /** @deprecated schedule.daysRemaining() を使うこと */
    @Deprecated
    public Long getDaysRemaining() {
        return schedule != null ? schedule.daysRemaining() : null;
    }

    /** @deprecated schedule.linkedScheduleId() を使うこと */
    @Deprecated
    public Long getLinkedScheduleId() {
        return schedule != null ? schedule.linkedScheduleId() : null;
    }

    /** @deprecated status.status() を使うこと */
    @Deprecated
    public String getStatus() {
        return status != null ? status.status() : null;
    }

    /** @deprecated status.priority() を使うこと */
    @Deprecated
    public String getPriority() {
        return status != null ? status.priority() : null;
    }

    /** @deprecated status.completedAt() を使うこと */
    @Deprecated
    public LocalDateTime getCompletedAt() {
        return status != null ? status.completedAt() : null;
    }

    /** @deprecated status.statusLabel() を使うこと */
    @Deprecated
    public TodoStatusLabelInfo getStatusLabel() {
        return status != null ? status.statusLabel() : null;
    }

    /** @deprecated hierarchy.parentId() を使うこと */
    @Deprecated
    public Long getParentId() {
        return hierarchy != null ? hierarchy.parentId() : null;
    }

    /** @deprecated hierarchy.depth() を使うこと */
    @Deprecated
    public Integer getDepth() {
        return hierarchy != null ? hierarchy.depth() : null;
    }

    /** @deprecated hierarchy.children() を使うこと */
    @Deprecated
    public List<TodoResponse> getChildren() {
        return hierarchy != null ? hierarchy.children() : null;
    }

    /** @deprecated hierarchy.childCount() を使うこと */
    @Deprecated
    public int getChildCount() {
        return hierarchy != null ? hierarchy.childCount() : 0;
    }

    /** @deprecated hierarchy.descendantCompletedCount() を使うこと */
    @Deprecated
    public int getDescendantCompletedCount() {
        return hierarchy != null ? hierarchy.descendantCompletedCount() : 0;
    }

    /** @deprecated hierarchy.descendantTotalCount() を使うこと */
    @Deprecated
    public int getDescendantTotalCount() {
        return hierarchy != null ? hierarchy.descendantTotalCount() : 0;
    }

    /** @deprecated audit.createdAt() を使うこと */
    @Deprecated
    public LocalDateTime getCreatedAt() {
        return audit != null ? audit.createdAt() : null;
    }

    /** @deprecated audit.updatedAt() を使うこと */
    @Deprecated
    public LocalDateTime getUpdatedAt() {
        return audit != null ? audit.updatedAt() : null;
    }

    /** @deprecated audit.createdBy() を使うこと */
    @Deprecated
    public ProjectResponse.UserInfo getCreatedBy() {
        return audit != null ? audit.createdBy() : null;
    }

    /** @deprecated audit.completedBy() を使うこと */
    @Deprecated
    public ProjectResponse.UserInfo getCompletedBy() {
        return audit != null ? audit.completedBy() : null;
    }
}
