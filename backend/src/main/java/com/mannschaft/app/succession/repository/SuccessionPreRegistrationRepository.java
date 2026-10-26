package com.mannschaft.app.succession.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.succession.entity.SuccessionPreRegistrationEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 「もしもの備え」事前登録リポジトリ（F09.15）。
 *
 * <p>1 居住者 1 事前登録のため、{@link #findByResidentRegistryIdAndDeletedAtIsNull(Long)}
 * は最大 1 件を返す {@link Optional} を返却する。
 */
public interface SuccessionPreRegistrationRepository
        extends AbstractTenantAwareRepository<SuccessionPreRegistrationEntity, UUID> {

    /** 居住者単位の事前登録（最大 1 件）。 */
    Optional<SuccessionPreRegistrationEntity> findByResidentRegistryIdAndDeletedAtIsNull(
            Long residentRegistryId);

    /** 本人ユーザー単位の事前登録（最大 1 件・MEMBER 自身による閲覧用）。 */
    Optional<SuccessionPreRegistrationEntity> findByOwnerUserIdAndDeletedAtIsNull(Long ownerUserId);

    /**
     * 72h 自動再封バッチ用: UNSEALED かつ {@code auto_reseal_at} 経過済みのレコードを抽出する。
     * 設計書 §9.3 自動再封バッチで使用。
     */
    List<SuccessionPreRegistrationEntity> findBySealStatusAndAutoResealAtBeforeAndDeletedAtIsNull(
            String sealStatus, LocalDateTime threshold);
}
