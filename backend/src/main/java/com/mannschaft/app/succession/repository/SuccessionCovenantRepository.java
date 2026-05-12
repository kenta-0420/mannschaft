package com.mannschaft.app.succession.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.succession.entity.SuccessionCovenantEntity;

import java.util.List;
import java.util.UUID;

/**
 * 入居時誓約リポジトリ（F09.15）。
 *
 * <p>{@link AbstractTenantAwareRepository} を継承し、テナントスコープでの
 * 検索メソッドを提供する。追加クエリは設計書 §6 / §7 で必要なもののみ最小限。
 */
public interface SuccessionCovenantRepository
        extends AbstractTenantAwareRepository<SuccessionCovenantEntity, UUID> {

    /** 居住者ごとの誓約一覧（撤回されていないものを含む全件・履歴含む）。 */
    List<SuccessionCovenantEntity> findByResidentRegistryIdAndDeletedAtIsNull(
            Long residentRegistryId);

    /** 居住者×誓約区分ごとの最新有効誓約（revoked_at IS NULL）。 */
    List<SuccessionCovenantEntity> findByResidentRegistryIdAndCovenantTypeAndRevokedAtIsNullAndDeletedAtIsNull(
            Long residentRegistryId, String covenantType);

    /** 署名者ユーザーごとの誓約履歴（署名日時降順）。 */
    List<SuccessionCovenantEntity> findBySignerUserIdAndDeletedAtIsNullOrderBySignedAtDesc(
            Long signerUserId);
}
