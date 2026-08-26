package com.mannschaft.app.succession.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.succession.entity.LegalFilingEntity;

import java.util.List;
import java.util.UUID;

/**
 * 法的手続き準備リポジトリ（F09.15）。
 */
public interface LegalFilingRepository
        extends AbstractTenantAwareRepository<LegalFilingEntity, UUID> {

    /** 居住者単位の申立履歴。 */
    List<LegalFilingEntity> findByResidentRegistryIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long residentRegistryId);

    /**
     * 居住者×組織単位の申立履歴（テナント分離）。
     *
     * <p>認可根治戦役 Wave 2: {@code residentRegistryId} は他組織にも存在しうるため、
     * 越境漏洩防止のため {@code organizationId} も条件に含める。
     * {@code LegalFilingService#listByResident} 専用。
     */
    List<LegalFilingEntity> findByResidentRegistryIdAndOrganizationIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long residentRegistryId, Long organizationId);

    /** 居住者×申立種別の履歴。 */
    List<LegalFilingEntity> findByResidentRegistryIdAndFilingTypeAndDeletedAtIsNull(
            Long residentRegistryId, String filingType);

    /** 組織配下の申立一覧（理事長ダッシュボード用）。 */
    List<LegalFilingEntity> findByOrganizationIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            Long organizationId);
}
