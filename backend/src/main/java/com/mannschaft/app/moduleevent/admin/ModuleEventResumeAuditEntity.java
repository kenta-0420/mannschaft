package com.mannschaft.app.moduleevent.admin;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

/**
 * 明示的な再開に対する追記専用の監査履歴。
 * 状態変更と同じmoduleeventのTXで保存し、publicationやユーザーの削除に連鎖させない。
 */
@Entity
@Table(name = "module_event_resume_audits")
@Getter
@SuperBuilder(toBuilder = true)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModuleEventResumeAuditEntity extends UuidV7Entity {
    @Column(name = "publication_id", nullable = false, updatable = false)
    private UUID publicationId;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    @Column(nullable = false, updatable = false, length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
