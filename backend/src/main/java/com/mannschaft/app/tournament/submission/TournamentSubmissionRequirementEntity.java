package com.mannschaft.app.tournament.submission;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.time.LocalDateTime;

/**
 * 大会提出枠エンティティ（F08.7.1/06 §2）。
 *
 * <p>F05.6 の {@code form_templates}（必要書類・フィールド・添付要否）と、tournament ドメインの
 * 大会／ディビジョンを結ぶ<strong>薄い連結テーブル</strong>。提出の実体・承認フローは
 * F05.6 の {@code form_submissions} / {@code workflow_requests} をそのまま使い、本機能では
 * 新規の提出／承認テーブルを作らない。</p>
 *
 * <p>原則準拠:</p>
 * <ul>
 *   <li>新規テーブルゆえ主キーは UUIDv7（原則 6・{@link UuidV7Entity} 継承）。</li>
 *   <li>{@code formTemplateId} / {@code tournamentId} / {@code divisionId} は他ドメインへの
 *       ID 参照のみ。クロスドメイン FK は張らない（原則 1）。</li>
 *   <li>論理削除（soft delete）で履歴を保持し、クロスドメイン CASCADE は使わない（原則 2・3）。</li>
 * </ul>
 */
@Entity
@Table(name = "tournament_submission_requirement")
@SQLRestriction("deleted_at IS NULL")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@SuperBuilder(toBuilder = true)
public class TournamentSubmissionRequirementEntity extends UuidV7Entity {

    /** 対象大会（tournaments.id への ID 参照・FK なし／原則1） */
    @Column(nullable = false)
    private Long tournamentId;

    /** 対象ディビジョン（tournament_divisions.id への ID 参照。NULL = 大会全体） */
    private Long divisionId;

    /** forms/workflow ドメインの form_templates.id への ID 参照（FK なし／原則1） */
    @Column(nullable = false)
    private Long formTemplateId;

    /** 提出枠の表示名（例「参加申込書」「選手登録一覧」） */
    @Column(nullable = false, length = 255)
    private String title;

    /** 補足説明 */
    @Column(columnDefinition = "TEXT")
    private String description;

    /** 提出締切（NULL = 締切なし） */
    private LocalDateTime deadline;

    /** 対象範囲（全チーム / 特定チーム） */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private SubmissionTargetScope targetScope = SubmissionTargetScope.ALL_TEAMS;

    /** 受理条件に「大会参加費の支払い済み」を課すか（領域⑦連携） */
    @Column(nullable = false)
    @Builder.Default
    private boolean requiresPayment = false;

    /** 主催組織（テナント絞り込み・クォータ帰属） */
    @Column(nullable = false)
    private Long organizationId;

    /** 作成した主催組織 ADMIN の user_id（退会時も履歴として保持／設計書 §7） */
    @Column(nullable = false)
    private Long createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime deletedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /**
     * 提出枠の表示情報・締切・対象・支払い条件を更新する。form_template_id（書類定義の出所）は変更不可。
     */
    public void update(String title, String description, Long divisionId,
                       SubmissionTargetScope targetScope, LocalDateTime deadline, Boolean requiresPayment) {
        if (title != null) this.title = title;
        this.description = description;
        this.divisionId = divisionId;
        if (targetScope != null) this.targetScope = targetScope;
        this.deadline = deadline;
        if (requiresPayment != null) this.requiresPayment = requiresPayment;
    }

    /**
     * 論理削除を行う。
     */
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    /**
     * 締切を過ぎているかどうかを判定する。
     *
     * @return 締切が設定され、かつ現在時刻が締切を過ぎている場合 true
     */
    public boolean isDeadlinePassed() {
        return this.deadline != null && LocalDateTime.now().isAfter(this.deadline);
    }
}
