package com.mannschaft.app.receipt.repository;

import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.entity.ReceiptIssuerSettingsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * 領収書発行者設定リポジトリ。
 */
public interface ReceiptIssuerSettingsRepository extends JpaRepository<ReceiptIssuerSettingsEntity, Long> {

    /**
     * スコープで発行者設定を検索する。
     */
    Optional<ReceiptIssuerSettingsEntity> findByScopeTypeAndScopeId(ReceiptScopeType scopeType, Long scopeId);

    /**
     * スコープで発行者設定を排他ロック付きで取得する（領収書番号の採番用）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ReceiptIssuerSettingsEntity s WHERE s.scopeType = :scopeType AND s.scopeId = :scopeId")
    Optional<ReceiptIssuerSettingsEntity> findByScopeTypeAndScopeIdForUpdate(
            @Param("scopeType") ReceiptScopeType scopeType,
            @Param("scopeId") Long scopeId);

    /**
     * 年度自動リセット対象の設定一覧を取得する。
     */
    List<ReceiptIssuerSettingsEntity> findByAutoResetNumberTrue();
}
