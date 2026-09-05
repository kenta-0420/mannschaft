package com.mannschaft.app.shift.entity;

import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.shift.ShiftPeriodType;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * シフトスケジュールエンティティ。チーム単位のシフト期間を管理する。
 */
@Entity
@Table(name = "shift_schedules")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class ShiftScheduleEntity extends BaseEntity {

    /**
     * 非管理者に「存在を見せてよい」シフト表を選ぶ JPQL 述語（別名 {@code s} 前提）。
     *
     * <p>CMP-260826-2127。{@code ShiftScheduleVisibilityPolicy.Visibility#HIDDEN} の否定であり、
     * {@code MASKED}（COLLECTING / ADJUSTING）と {@code FULL}（PUBLISHED / 公開済み ARCHIVED）を含む。
     * {@code @Query} のアノテーション値に埋め込むためコンパイル時定数として entity 側に置く
     *（可視性判定の正本は {@code ShiftScheduleVisibilityPolicy}。条件式を各所へ書き写さないための唯一の複製）。</p>
     */
    public static final String NOT_HIDDEN_JPQL =
            "(s.status IN ('COLLECTING', 'ADJUSTING', 'PUBLISHED') "
                    + "OR (s.status = 'ARCHIVED' AND s.publishedAt IS NOT NULL))";

    @Column(nullable = false)
    private Long teamId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShiftPeriodType periodType = ShiftPeriodType.WEEKLY;

    @Column(nullable = false)
    private LocalDate startDate;

    @Column(nullable = false)
    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShiftScheduleStatus status = ShiftScheduleStatus.DRAFT;

    private LocalDateTime requestDeadline;

    @Column(columnDefinition = "TEXT")
    private String note;

    private Long createdBy;

    private LocalDateTime publishedAt;

    private Long publishedBy;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isReminderSent = false;

    @Column(name = "is_reminder_sent_48h", nullable = false)
    @Builder.Default
    private Boolean isReminderSent48h = false;

    @Column(nullable = false)
    @Builder.Default
    private Boolean isLowSubmissionAlerted = false;

    private LocalDateTime lastAutoTransitionAt;

    /**
     * 紐付プロジェクト ID（F08.7 シフト-予算-TODO 連携で使用）。
     * NULL: プロジェクト紐付なし（通常運用）。
     * 設計書 F08.7 (v1.2) §4.3 / §12.1 参照。
     * <p>1:1 関係。プロジェクト削除時は ON DELETE SET NULL で本カラムが NULL になる。</p>
     */
    @Column(name = "linked_project_id")
    private Long linkedProjectId;

    @Version
    @Column(nullable = false)
    @Builder.Default
    private Long version = 0L;

    private LocalDateTime deletedAt;

    /**
     * シフトスケジュールの更新可能フィールドを一括で書き換える（部分更新）。
     *
     * <p>本メソッドは managed entity をその場でミューテートする更新メソッドである。
     * {@code @Transactional} 内で managed な本エンティティに対して呼ぶことで JPA の
     * dirty checking により UPDATE が発行される。
     *
     * <p><strong>なぜ builder ({@code toBuilder().build()}) で作り直さないか:</strong>
     * {@link ShiftScheduleEntity} は {@code @SuperBuilder(toBuilder = true)}（{@code @SuperBuilder} ではない）であり、
     * 主キー {@code id} は基底クラス {@link com.mannschaft.app.common.BaseEntity} のフィールドである。
     * {@code @SuperBuilder} は superclass のフィールドを取り込まないため、{@code toBuilder()} で
     * 作り直すと継承フィールド {@code id} が引き継がれず {@code id = null} の新インスタンスになる。
     * これを {@code save} すると UPDATE ではなく INSERT が走り、行重複が発生する
     * （本メソッド導入の動機）。よって更新は必ず managed entity の直接ミューテートで行う。
     *
     * <p>各フィールドは「リクエスト値が非 null なら採用、null なら現値を維持」の部分更新セマンティクス。
     * 日付整合性の検証は呼び出し側（{@code ShiftScheduleService}）の責務とし、本メソッドは検証済みの値を受け取る。
     *
     * @param title           新タイトル（null なら現値維持）
     * @param periodType      新期間タイプ（null なら現値維持）
     * @param startDate       新開始日（null なら現値維持）
     * @param endDate         新終了日（null なら現値維持）
     * @param requestDeadline 新希望提出期限（null なら現値維持）
     * @param note            新メモ（null なら現値維持）
     */
    public void applyUpdate(String title, ShiftPeriodType periodType, LocalDate startDate,
                            LocalDate endDate, LocalDateTime requestDeadline, String note) {
        if (title != null) {
            this.title = title;
        }
        if (periodType != null) {
            this.periodType = periodType;
        }
        if (startDate != null) {
            this.startDate = startDate;
        }
        if (endDate != null) {
            this.endDate = endDate;
        }
        if (requestDeadline != null) {
            this.requestDeadline = requestDeadline;
        }
        if (note != null) {
            this.note = note;
        }
    }

    /**
     * ステータスを希望収集中に遷移する。
     */
    public void startCollecting() {
        this.status = ShiftScheduleStatus.COLLECTING;
    }

    /**
     * ステータスを調整中に遷移する。
     */
    public void startAdjusting() {
        this.status = ShiftScheduleStatus.ADJUSTING;
    }

    /**
     * シフトを公開する。
     *
     * @param userId 公開操作者のユーザーID
     */
    public void publish(Long userId) {
        this.status = ShiftScheduleStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
        this.publishedBy = userId;
    }

    /**
     * シフトをアーカイブする。
     */
    public void archive() {
        this.status = ShiftScheduleStatus.ARCHIVED;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * リマインダー送信済みに更新する。
     */
    public void markReminderSent() {
        this.isReminderSent = true;
    }

    /**
     * 48時間前リマインダー送信済みに更新する。
     */
    public void markReminderSent48h() {
        this.isReminderSent48h = true;
    }

    /**
     * 低提出率アラート送信済みに更新する。
     */
    public void markLowSubmissionAlerted() {
        this.isLowSubmissionAlerted = true;
    }
}
