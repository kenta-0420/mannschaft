package com.mannschaft.app.tournament.leaguetransfer;

import com.mannschaft.app.common.entity.UuidV7Entity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Builder;
import lombok.experimental.SuperBuilder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * リーグ移籍エンティティ（F08.7.1 / 03 §3.1）。
 *
 * <p>組織をまたぐ昇降格（例: 大分県リーグ 1 部 → 九州リーグ）を 1 テーブルで両方向（昇格・降格）担う。
 * 同一大会内の部間昇降格は既存 {@code PromotionService} が担当し、本テーブルは関与しない（§2.1）。</p>
 *
 * <p>原則準拠:</p>
 * <ul>
 *   <li>新規テーブルゆえ主キーは UUIDv7（原則 6・{@link UuidV7Entity} 継承）。</li>
 *   <li>{@code teamId} / {@code fromOrganizationId} / {@code toOrganizationId} /
 *       {@code sourceDivisionId} / {@code targetDivisionId} / {@code initiatedBy} / {@code respondedBy}
 *       はすべて他ドメインへの ID 参照のみ。クロスドメイン FK は張らない（原則 1）。</li>
 *   <li>from / to の 2 組織をまたぐため単一 {@code organization_id} でテナント絞りできない。
 *       よって {@code AbstractTenantAwareRepository} は適用せず、用途別 index（from_org / to_org / team）で引く（§3.1）。</li>
 *   <li>{@code initiatedBy} / {@code respondedBy} は移籍の証跡として保持＝退会二段モデルの
 *       強匿名化対象外（NULL 化しない・§7 / O-4）。</li>
 * </ul>
 */
@Entity
@Table(name = "league_transfer")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@SuperBuilder(toBuilder = true)
public class LeagueTransferEntity extends UuidV7Entity {

    /** 方向（PROMOTION / RELEGATION）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LeagueTransferDirection direction;

    /** 移籍対象チーム（teams.id への ID 参照・FK なし／原則1）。team_id は移籍しても不変。 */
    @Column(nullable = false)
    private Long teamId;

    /** 手放す側 org（昇格時=下位県協会 / 降格時=上位協会）。FK なし。 */
    @Column(nullable = false)
    private Long fromOrganizationId;

    /** 受け入れる側 org（昇格時=上位協会 / 降格時=出身県協会）。FK なし。 */
    @Column(nullable = false)
    private Long toOrganizationId;

    /** 移籍元ディビジョン（tournament_divisions.id・NULL 許容）。FK なし。 */
    private Long sourceDivisionId;

    /** 移籍先ディビジョン（承認・配属確定時にセット・NULL 許容）。FK なし。 */
    private Long targetDivisionId;

    /** シーズン識別子（二重起票抑止キー UNIQUE(team_id, season, direction) の一部）。 */
    @Column(nullable = false, length = 20)
    private String season;

    /** 移籍元での最終順位（昇格枠/降格枠判定の根拠・NULL 許容）。 */
    private Integer finalRank;

    /** 状態（§3.2）。 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private LeagueTransferStatus status = LeagueTransferStatus.DISPATCHED;

    /** 起票者（手放す側 org ADMIN）の user_id。退会後も証跡保持（NULL 化しない・§7）。 */
    @Column(nullable = false)
    private Long initiatedBy;

    /** 応答者（受け入れ側 org ADMIN）の user_id。退会後も証跡保持（NULL 化しない・§7）。 */
    private Long respondedBy;

    /** 送り出しメッセージ（NULL 許容）。 */
    @Column(length = 500)
    private String message;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    /** 応答日時（承認/拒否/取消時にセット）。 */
    private LocalDateTime respondedAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    /**
     * 受け入れ承認・配属（PLACED）。{@code target_division_id} をセットし応答者・応答日時を記録する。
     *
     * @param targetDivisionId 配属先ディビジョン ID
     * @param respondedBy      受け入れ側 org ADMIN の user_id
     */
    public void place(Long targetDivisionId, Long respondedBy) {
        this.status = LeagueTransferStatus.PLACED;
        this.targetDivisionId = targetDivisionId;
        this.respondedBy = respondedBy;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * 受け入れ拒否（DECLINED）。
     *
     * @param respondedBy 受け入れ側 org ADMIN の user_id
     */
    public void decline(Long respondedBy) {
        this.status = LeagueTransferStatus.DECLINED;
        this.respondedBy = respondedBy;
        this.respondedAt = LocalDateTime.now();
    }

    /**
     * 送り出し取消（CANCELLED）。手放す側が応答前に取り消す。
     *
     * @param respondedBy 手放す側 org ADMIN の user_id（取消操作者）
     */
    public void cancel(Long respondedBy) {
        this.status = LeagueTransferStatus.CANCELLED;
        this.respondedBy = respondedBy;
        this.respondedAt = LocalDateTime.now();
    }

    /** 応答可能（DISPATCHED）か。承認/拒否/取消は DISPATCHED のときのみ許可される（§3.2）。 */
    public boolean isDispatched() {
        return this.status == LeagueTransferStatus.DISPATCHED;
    }
}
