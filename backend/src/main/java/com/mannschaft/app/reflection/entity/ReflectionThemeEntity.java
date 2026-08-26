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
import java.util.UUID;

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

    /** Phase 2: 科目名紐づけ（personal_timetable_slots.subject_name と合わせる）。NULL=未紐づけ。 */
    @Column(name = "linked_subject_name", length = 200)
    private String linkedSubjectName;

    /** Phase 2: 履修番号紐づけ（PERSONAL専用・TEAMは常にNULL）。NULL=指定なし。 */
    @Column(name = "linked_course_code", length = 50)
    private String linkedCourseCode;

    /** Phase 3: 学年度（例: 2026）。実値保持・自由数値。NULL=未設定。DB列は SMALLINT だが Java 型は Integer（§12.1）。 */
    @Column(name = "academic_year", columnDefinition = "SMALLINT")
    private Integer academicYear;

    /** Phase 3: 学期ラベル（例: 「1学期」「前期」「Q1」）。自由文字列。NULL=未設定。 */
    @Column(name = "term_label", length = 50)
    private String termLabel;

    /** Phase 3: 親テーマの ID（自己参照・2階層固定）。NULL=トップレベル。 */
    @Column(name = "parent_theme_id", columnDefinition = "BINARY(16)")
    private UUID parentThemeId;

    /** Phase 3: アーカイブ日時。NULL=アクティブ、非NULL=アーカイブ済み。deleted_at とは独立。 */
    @Column(name = "archived_at")
    private LocalDateTime archivedAt;

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
     * Phase 4.1: linkedSlotKind を NULL クリアする（AC-64: linkedSlotId=null 時の強制 NULL 化）。
     */
    public void clearLinkedSlotKind() {
        this.linkedSlotKind = null;
    }

    /**
     * Phase 2: 科目名紐づけを設定する（examDate と同型のミューテート方式）。
     *
     * @param subjectName 科目名（NULL 設定可）
     * @param courseCode  履修番号（NULL 設定可）
     */
    public void setLinkedSubject(String subjectName, String courseCode) {
        this.linkedSubjectName = subjectName;
        this.linkedCourseCode = courseCode;
    }

    /**
     * Phase 2: 科目名紐づけをクリアする（linked_subject_name / linked_course_code → NULL）。
     * linked_slot_id はクリアしない（FEが clearLinkedSlot も送出する場合はService側で処理）。
     */
    public void clearLinkedSubject() {
        this.linkedSubjectName = null;
        this.linkedCourseCode = null;
    }

    /**
     * Phase 3: 学年度・学期ラベルを設定する（null は現値維持・examDate と同型のミューテート方式）。
     *
     * @param academicYear 学年度（null なら現値維持）
     * @param termLabel    学期ラベル（null なら現値維持）
     */
    public void setAcademicYearAndTerm(Integer academicYear, String termLabel) {
        if (academicYear != null) {
            this.academicYear = academicYear;
        }
        if (termLabel != null) {
            this.termLabel = termLabel;
        }
    }

    /**
     * Phase 3: 親テーマIDを設定する（2階層バリデーションは Service 層で実施済みの前提）。
     *
     * @param parentThemeId 親テーマのUUID（null でトップレベル）
     */
    public void setParentThemeId(UUID parentThemeId) {
        this.parentThemeId = parentThemeId;
    }

    /**
     * Phase 3: 親テーマIDをクリアする（clearParent=true の場合）。
     */
    public void clearParentThemeId() {
        this.parentThemeId = null;
    }

    /**
     * Phase 3: テーマをアーカイブする（archived_at = now）。
     */
    public void archive() {
        this.archivedAt = LocalDateTime.now();
    }

    /**
     * Phase 3: アーカイブを解除する（archived_at = null）。
     */
    public void restore() {
        this.archivedAt = null;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
