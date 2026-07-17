package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageNewsletterIssueEntity;
import com.mannschaft.app.village.entity.enums.VillageNewsletterIssueStatus;
import com.mannschaft.app.village.entity.enums.VillageNewsletterVisibility;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

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

    /** 公開一覧（村横断）: PUBLIC かつ PUBLISHED の号を新しい順（§8.2・idx_vni_public_published）。 */
    Page<VillageNewsletterIssueEntity>
            findByVisibilityAndStatusAndDeletedAtIsNullOrderByPublishedAtDesc(
                    VillageNewsletterVisibility visibility,
                    VillageNewsletterIssueStatus status,
                    Pageable pageable);
}
