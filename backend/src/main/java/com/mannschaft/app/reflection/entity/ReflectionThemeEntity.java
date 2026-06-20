package com.mannschaft.app.reflection.entity;

import com.mannschaft.app.common.entity.UuidV7Entity;
import com.mannschaft.app.reflection.ReflectionConstants;
import com.mannschaft.app.reflection.ReflectionLinkedSlotKind;
import com.mannschaft.app.reflection.ReflectionSourceType;
import com.mannschaft.app.reflection.ReflectionVisibility;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 振り返りメインテーマ（F06.5・§2.1）。
 *
 * <p>日々の振り返り（{@link ReflectionEntryEntity}）を束ねる軽量な器。個人所有（user_id）・既定 PRIVATE。
 * user_id / linked_slot_id は他ドメイン参照のため FK を張らず ID のみ保持する（原則1）。</p>
 *
 * <p><b>更新は {@link #applyUpdate} の直接ミューテートで行う</b>。{@code UuidV7Entity} を継承するため
 * {@code toBuilder().build()} は継承フィールド id を落とし INSERT 化する既知バグがあり、使わない
 * （手本: {@code TimetableChangeEntity.applyUpdate} / {@code IncidentBannerEntity.update}）。</p>
 */
@Entity
@Table(name = "reflection_themes")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
@EqualsAndHashCode(callSuper = true)
public class ReflectionThemeEntity extends UuidV7Entity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Column(name = "description", length = 500)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    @Builder.Default
    private ReflectionSourceType sourceType = ReflectionSourceType.FREE;

    @Enumerated(EnumType.STRING)
    @Column(name = "linked_slot_kind", length = 10)
    private ReflectionLinkedSlotKind linkedSlotKind;

    @Column(name = "linked_slot_id")
    private Long linkedSlotId;

    @Column(name = "exam_date")
    private LocalDate examDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility", nullable = false, length = 20)
    @Builder.Default
    private ReflectionVisibility visibility = ReflectionVisibility.PRIVATE;

    @Column(name = "recall_interval_days", nullable = false, length = 50)
    @Builder.Default
    private String recallIntervalDays = ReflectionConstants.DEFAULT_RECALL_INTERVALS;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * テーマの更新可能フィールドを部分更新する（null=現値維持セマンティクス・toBuilder 回避）。
     *
     * @param title       新タイトル（null なら現値維持）
     * @param description 新説明（null なら現値維持・空文字で消去）
     * @param sourceType  新 source_type（null なら現値維持）
     * @param examDate    新考査日（null なら現値維持。消去は別途専用メソッド想定。次陣で詳細仕様化）
     */
    public void applyUpdate(String title, String description, ReflectionSourceType sourceType,
                            LocalDate examDate) {
        if (title != null) {
            this.title = title;
        }
        if (description != null) {
            this.description = description;
        }
        if (sourceType != null) {
            this.sourceType = sourceType;
        }
        if (examDate != null) {
            this.examDate = examDate;
        }
    }

    /**
     * 考査日を設定（NULL でクリア）する。考査日の明示的な消去を許容するための専用メソッド。
     */
    public void setExamDate(LocalDate examDate) {
        this.examDate = examDate;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
