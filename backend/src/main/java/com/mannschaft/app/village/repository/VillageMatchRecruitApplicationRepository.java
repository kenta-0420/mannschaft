package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageMatchRecruitApplicationEntity;
import com.mannschaft.app.village.entity.enums.VillageMatchApplicationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * 村練習試合募集への応募リポジトリ（F17.1 Phase 2）。
 *
 * <p>原則7 適用外（村ドメインは全テナント横断のため）。
 * 標準 {@link JpaRepository} を継承し、必要最小限のクエリのみ追加する。</p>
 */
public interface VillageMatchRecruitApplicationRepository
        extends JpaRepository<VillageMatchRecruitApplicationEntity, UUID> {

    /** 同一募集に対する同一ユーザーの指定状態応募を取得（二重応募防止）。 */
    Optional<VillageMatchRecruitApplicationEntity> findByRecruitIdAndApplicantUserIdAndStatus(
            UUID recruitId, Long applicantUserId, VillageMatchApplicationStatus status);

    /** 募集ごとの応募一覧。 */
    Page<VillageMatchRecruitApplicationEntity> findByRecruitId(UUID recruitId, Pageable pageable);

    /** 募集ごと + 状態別の応募一覧。 */
    Page<VillageMatchRecruitApplicationEntity> findByRecruitIdAndStatus(
            UUID recruitId, VillageMatchApplicationStatus status, Pageable pageable);
}
