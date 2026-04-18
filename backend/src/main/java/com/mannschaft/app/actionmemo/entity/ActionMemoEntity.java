package com.mannschaft.app.actionmemo.entity;

import com.mannschaft.app.actionmemo.ActionMemoMood;
import com.mannschaft.app.actionmemo.enums.ActionMemoCategory;
import com.mannschaft.app.common.BaseEntity;
import com.mannschaft.app.gdpr.PersonalData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * F02.5 行動メモ本体エンティティ。
 *
 * <p>ユーザー個人の行動ログ。必須項目は content のみ。
 * タイトル無し・タグ無し・TODO 紐付け無しでも作成可能（摩擦ゼロ原則）。</p>
 *
 * <p><b>GDPR 連携</b>: {@code @PersonalData(category = "action_memos")} により
 * {@code PersonalDataCollector} の網羅性チェックに組み込まれる。</p>
 *
 * <p><b>Phase 3 追加フィールド</b>: category / duration_minutes / progress_rate /
 * completes_todo / posted_team_id。全て nullable または DEFAULT 値付き（非破壊拡張）。</p>
 */
@PersonalData(category = "action_memos")
@Entity
@Table(name = "action_memos")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder(toBuilder = true)
public class ActionMemoEntity extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Setter
    @Column(nullable = false)
    private LocalDate memoDate;

    @Setter
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /**
     * Phase 3: メモカテゴリ（WORK / PRIVATE / OTHER）。
     * 省略時は {@code PRIVATE}（デフォルト）。
     */
    @Setter
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private ActionMemoCategory category = ActionMemoCategory.PRIVATE;

    /**
     * Phase 3: 実績時間（分）。0〜1440。NULL = 未入力。
     */
    @Setter
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    /**
     * Phase 3: 記録時点の進捗率（0.00〜100.00）。NULL = 未入力。
     * 非 NULL のとき {@code related_todo_id} が必須（バリデーションは Service 層）。
     */
    @Setter
    @Column(name = "progress_rate", precision = 5, scale = 2)
    private BigDecimal progressRate;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ActionMemoMood mood;

    @Setter
    private Long relatedTodoId;

    /**
     * Phase 3: related_todo_id の TODO を完了扱いにするフラグ。
     * true のとき relatedTodoId が必須（バリデーションは Service 層）。
     */
    @Setter
    @Column(nullable = false)
    @Builder.Default
    private Boolean completesTodo = false;

    @Setter
    private Long timelinePostId;

    /**
     * Phase 3: チームタイムラインに投稿済みの場合のチームID。NULL = 未投稿。
     * FK → teams.id（ON DELETE SET NULL）。
     */
    @Setter
    @Column(name = "posted_team_id")
    private Long postedTeamId;

    private LocalDateTime deletedAt;

    /**
     * 行動メモを論理削除する。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
}
