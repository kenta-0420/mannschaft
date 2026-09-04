package com.mannschaft.app.receipt.repository;

import com.mannschaft.app.receipt.ReceiptScopeType;
import com.mannschaft.app.receipt.entity.ReceiptNumberSequenceEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 領収書番号採番シーケンスのリポジトリ（F08.12 §3.2）。
 */
public interface ReceiptNumberSequenceRepository
        extends JpaRepository<ReceiptNumberSequenceEntity, UUID> {

    /**
     * 採番行をロックせずに読む（存在確認用）。
     */
    Optional<ReceiptNumberSequenceEntity> findByScopeTypeAndScopeIdAndPeriodKey(
            ReceiptScopeType scopeType, Long scopeId, String periodKey);

    /**
     * 採番行を {@code SELECT ... FOR UPDATE} で取得する。
     *
     * <p>ロックするのは<b>本表の行だけ</b>である。発行者設定行はロックしない
     * （従来方式はそれをロックしており、PLATFORM では全件が直列化していた）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ReceiptNumberSequenceEntity s "
            + "WHERE s.scopeType = :scopeType AND s.scopeId = :scopeId AND s.periodKey = :periodKey")
    Optional<ReceiptNumberSequenceEntity> findForUpdate(
            @Param("scopeType") ReceiptScopeType scopeType,
            @Param("scopeId") Long scopeId,
            @Param("periodKey") String periodKey);
}
