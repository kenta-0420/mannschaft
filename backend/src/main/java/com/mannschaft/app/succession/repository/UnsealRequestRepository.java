package com.mannschaft.app.succession.repository;

import com.mannschaft.app.common.repository.AbstractTenantAwareRepository;
import com.mannschaft.app.succession.entity.UnsealRequestEntity;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 封緘解除二者承認リポジトリ（F09.15）。
 */
public interface UnsealRequestRepository
        extends AbstractTenantAwareRepository<UnsealRequestEntity, UUID> {

    /** 事前登録 ID 単位の解除申請履歴。 */
    List<UnsealRequestEntity> findByPreRegistrationIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID preRegistrationId);

    /** 居住者 ID 単位の解除申請履歴（unseal_completed_at 降順）。 */
    List<UnsealRequestEntity> findByResidentRegistryIdAndDeletedAtIsNullOrderByUnsealCompletedAtDesc(
            Long residentRegistryId);

    /**
     * 72h 自動再封バッチ用: {@code auto_reseal_at} 経過済みかつ未再封のレコードを抽出する。
     * 設計書 §9.3 自動再封バッチで使用。
     */
    List<UnsealRequestEntity> findByAutoResealAtBeforeAndReSealedAtIsNullAndDeletedAtIsNull(
            LocalDateTime threshold);
}
