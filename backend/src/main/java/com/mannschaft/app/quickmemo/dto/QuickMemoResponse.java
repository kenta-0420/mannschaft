package com.mannschaft.app.quickmemo.dto;

import com.mannschaft.app.quickmemo.entity.QuickMemoEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ポイっとメモレスポンス。
 */
public record QuickMemoResponse(
        Long id,
        String title,
        String body,
        String status,
        List<TagSummary> tags,
        List<AttachmentSummary> attachments,
        Boolean reminderUsesDefault,
        List<ReminderSlot> reminders,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public record ReminderSlot(int slot, LocalDateTime scheduledAt, LocalDateTime sentAt) {}

    public static QuickMemoResponse from(QuickMemoEntity entity,
                                          List<TagSummary> tags,
                                          List<AttachmentSummary> attachments) {
        List<ReminderSlot> reminders = List.of(
                new ReminderSlot(1, entity.getReminder1ScheduledAt(), entity.getReminder1SentAt()),
                new ReminderSlot(2, entity.getReminder2ScheduledAt(), entity.getReminder2SentAt()),
                new ReminderSlot(3, entity.getReminder3ScheduledAt(), entity.getReminder3SentAt())
        );
        return new QuickMemoResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getBody(),
                entity.getStatus(),
                tags,
                attachments,
                entity.getReminderUsesDefault(),
                reminders,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
