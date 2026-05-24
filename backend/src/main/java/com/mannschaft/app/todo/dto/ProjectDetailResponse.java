package com.mannschaft.app.todo.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * プロジェクト詳細レスポンスDTO。マイルストーン別の進捗内訳を含む。
 * 旧15フィールドフラット構造をネストDTOに刷新（Wave 1 第一陣）。
 */
@Getter
@Builder(toBuilder = true)
public class ProjectDetailResponse {

    private Long id;
    private ProjectMetaDto meta;
    private ProjectContentDto content;
    private ProjectScheduleDto schedule;
    private ProjectProgressDto progress;
    private List<MilestoneDetail> milestones;
    private UnassignedTodos unassignedTodos;
    private ProjectAuditDto audit;

    public record ProjectMetaDto(
            String status,
            String visibility
    ) {}

    public record ProjectContentDto(
            String title,
            String description,
            String emoji,
            String color
    ) {}

    public record ProjectScheduleDto(
            LocalDate dueDate,
            Long daysRemaining
    ) {}

    public record ProjectProgressDto(
            BigDecimal progressRate,
            int totalTodos,
            int completedTodos
    ) {}

    public record ProjectAuditDto(
            ProjectResponse.UserInfo createdBy
    ) {}

    /**
     * マイルストーン詳細。
     */
    @Getter
    @Builder(toBuilder = true)
    public static class MilestoneDetail {
        private final Long id;
        private final String title;
        private final LocalDate dueDate;
        private final boolean isCompleted;
        private final LocalDateTime completedAt;
        private final BigDecimal progressRate;
        private final long totalTodos;
        private final long completedTodos;
        private final short sortOrder;
    }

    /**
     * マイルストーン未割り当てTODO集計。
     */
    @Getter
    @Builder(toBuilder = true)
    public static class UnassignedTodos {
        private final long total;
        private final long completed;
    }

    // ===== 後方互換アクセサ（テスト・既存コードのコンパイルを維持）=====

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

    /** @deprecated content.emoji() を使うこと */
    @Deprecated
    public String getEmoji() {
        return content != null ? content.emoji() : null;
    }

    /** @deprecated content.color() を使うこと */
    @Deprecated
    public String getColor() {
        return content != null ? content.color() : null;
    }

    /** @deprecated meta.status() を使うこと */
    @Deprecated
    public String getStatus() {
        return meta != null ? meta.status() : null;
    }

    /** @deprecated meta.visibility() を使うこと */
    @Deprecated
    public String getVisibility() {
        return meta != null ? meta.visibility() : null;
    }

    /** @deprecated schedule.dueDate() を使うこと */
    @Deprecated
    public LocalDate getDueDate() {
        return schedule != null ? schedule.dueDate() : null;
    }

    /** @deprecated schedule.daysRemaining() を使うこと */
    @Deprecated
    public Long getDaysRemaining() {
        return schedule != null ? schedule.daysRemaining() : null;
    }

    /** @deprecated progress.progressRate() を使うこと */
    @Deprecated
    public BigDecimal getProgressRate() {
        return progress != null ? progress.progressRate() : null;
    }

    /** @deprecated progress.totalTodos() を使うこと */
    @Deprecated
    public int getTotalTodos() {
        return progress != null ? progress.totalTodos() : 0;
    }

    /** @deprecated progress.completedTodos() を使うこと */
    @Deprecated
    public int getCompletedTodos() {
        return progress != null ? progress.completedTodos() : 0;
    }

    /** @deprecated audit.createdBy() を使うこと */
    @Deprecated
    public ProjectResponse.UserInfo getCreatedBy() {
        return audit != null ? audit.createdBy() : null;
    }
}
