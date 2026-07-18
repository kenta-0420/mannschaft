package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村ニュースレター号リポジトリ（F17.1 ②-1・案Y）。
 *
 * <p>後続の集計・凍結バッチ（②-2）・配信バッチ（②-3）・号 API（②-4）・公開一覧（§8.2）が使う。</p>
 */
public interface VillageNewsletterIssueRepository
        extends JpaRepository<VillageNewsletterIssueEntity, UUID> {

    /** 村内の号一覧（新しい順・論理削除除外・村内 pull）。 */
    Page<VillageNewsletterIssueEntity> findByVillageIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID villageId, Pageable pageable);

    /** 村内の1号（論理削除除外）。 */
    Optional<VillageNewsletterIssueEntity> findByIdAndVillageIdAndDeletedAtIsNull(
            UUID id, UUID villageId);

    /**
     * 村内の号一覧をタグで絞り込む（新しい順・論理削除除外・②-4）。
     *
     * <p>タグ→号の逆引き（{@link VillageNewsletterIssueTagRepository#findByTagId}）で得た号 ID 集合を
     * IN 条件で渡す。空集合は呼び出し側で {@code Page.empty()} に短絡し、{@code IN ()} を発行しない。</p>
     */
    Page<VillageNewsletterIssueEntity> findByVillageIdAndIdInAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID villageId, java.util.Collection<UUID> ids, Pageable pageable);

    /**
     * 冪等性判定用: 同一村×頻度×期間の号が既にあるか（UNIQUE と対応・集計バッチの二重起動対策）。
     * frequency は null 非対応（EXTRA は対象外）のため定期便のみで使う。
     */
    Optional<VillageNewsletterIssueEntity> findByVillageIdAndFrequencyAndPeriodStart(
            UUID villageId,
            com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency frequency,
            LocalDateTime periodStart);

    /** 配信バッチ走査用: 指定状態かつ配信予定が指定時刻以前の号（論理削除除外）。 */
    List<VillageNewsletterIssueEntity> findByStatusAndScheduledPublishAtLessThanEqualAndDeletedAtIsNull(
            VillageNewsletterIssueStatus status, LocalDateTime threshold);

    /**
     * 集計バッチの期間算出用（②-2）: 同一村×頻度で、指定時刻より前に終わった直近号を1件返す。
     *
     * <p>次の号の {@code period_start} は「直近号の {@code period_end}」から始める（期間を連続させる）。
     * {@code periodEndExclusive}（＝今回の集計基準時刻）より <b>厳密に前</b> に終わった号だけを対象にすることで、
     * 同日に二度走らせても「今作った号」を除外でき、{@code period_start} が同値に収束して冪等性
     * （{@link #findByVillageIdAndFrequencyAndPeriodStart}）が保たれる。</p>
     */
    Optional<VillageNewsletterIssueEntity>
            findFirstByVillageIdAndFrequencyAndPeriodEndLessThanAndDeletedAtIsNullOrderByPeriodEndDesc(
                    UUID villageId,
                    com.mannschaft.app.village.entity.enums.VillageNewsletterFrequency frequency,
                    LocalDateTime periodEndExclusive);

    /** 公開一覧（村横断）: PUBLIC かつ PUBLISHED の号を新しい順（§8.2・idx_vni_public_published）。 */
    Page<VillageNewsletterIssueEntity>
            findByVisibilityAndStatusAndDeletedAtIsNullOrderByPublishedAtDesc(
                    VillageNewsletterVisibility visibility,
                    VillageNewsletterIssueStatus status,
                    Pageable pageable);

    /**
     * 公開一覧（村横断・②-4 堅牢性 AC-4〜8）: 指定 visibility×status の号のうち、
     * <b>発行元の村が生存している</b>（{@code deleted_at IS NULL AND archived_at IS NULL}）ものだけを返す。
     *
     * <p>削除／凍結された村のお便りが「みんなのお便り」に残り続ける漏洩（ゾンビ号）を根治する。
     * villages と village_newsletter_issues は同一 village ドメインのため JOIN（EXISTS 相関）で
     * 絞り込んでよい（マスター御裁可済）。ページの {@code totalElements} を実データと整合させるため、
     * {@code countQuery} も同一の生存条件で数える（AC-8）。</p>
     */
    @Query(value = """
            SELECT i FROM VillageNewsletterIssueEntity i
            WHERE i.visibility = :visibility
              AND i.status = :status
              AND i.deletedAt IS NULL
              AND EXISTS (SELECT 1 FROM VillageEntity v
                          WHERE v.id = i.villageId
                            AND v.deletedAt IS NULL
                            AND v.archivedAt IS NULL)
            ORDER BY i.publishedAt DESC
            """,
            countQuery = """
            SELECT COUNT(i) FROM VillageNewsletterIssueEntity i
            WHERE i.visibility = :visibility
              AND i.status = :status
              AND i.deletedAt IS NULL
              AND EXISTS (SELECT 1 FROM VillageEntity v
                          WHERE v.id = i.villageId
                            AND v.deletedAt IS NULL
                            AND v.archivedAt IS NULL)
            """)
    Page<VillageNewsletterIssueEntity> findPublicIssuesFromAliveVillages(
            @Param("visibility") VillageNewsletterVisibility visibility,
            @Param("status") VillageNewsletterIssueStatus status,
            Pageable pageable);
}
