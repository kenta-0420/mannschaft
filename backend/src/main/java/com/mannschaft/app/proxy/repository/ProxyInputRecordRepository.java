package com.mannschaft.app.proxy.repository;

import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 代理入力実行ログリポジトリ（F14.1）。
 * 追記専用テーブル。UNIQUE KEY uq_pir_idempotent により二重登録を防止する。
 */
public interface ProxyInputRecordRepository extends JpaRepository<ProxyInputRecordEntity, Long> {

    /**
     * 冪等性チェック（紙運用での二重登録防止）。
     * 同じ同意書・対象エンティティへの二重記録を検出する。
     */
    Optional<ProxyInputRecordEntity> findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
            Long proxyInputConsentId, String targetEntityType, Long targetEntityId);

    /**
     * 本人の代理入力履歴を取得する（本人向け監査ログ閲覧・GDPRエクスポート）。
     */
    List<ProxyInputRecordEntity> findBySubjectUserIdOrderByCreatedAtDesc(Long subjectUserId);

    /**
     * 代理者の実行履歴を取得する（管理者向け監査）。
     */
    List<ProxyInputRecordEntity> findByProxyUserIdOrderByCreatedAtDesc(Long proxyUserId);

    /**
     * 同意書に紐づく代理入力記録を取得する（同意書ごとの監査用）。
     */
    List<ProxyInputRecordEntity> findByProxyInputConsentIdOrderByCreatedAtDesc(Long proxyInputConsentId);

    /**
     * 月次サマリ生成用：指定期間内に作成された代理入力レコードを全件取得する。
     * proxy_input_records には organizationId が存在しないため、
     * 全件を subjectUserId でグループ化して月次サマリPDFを生成する。
     *
     * @param fromDate 集計開始日時（inclusive）
     * @param toDate   集計終了日時（exclusive）
     */
    @Query("SELECT r FROM ProxyInputRecordEntity r WHERE r.createdAt >= :fromDate AND r.createdAt < :toDate")
    List<ProxyInputRecordEntity> findForMonthlySummary(
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate);

    /**
     * 保管期限が切れた代理入力記録のIDリストを取得する（RetentionJob用）。
     * retentionExpiresAt が cutoffDate 以前のレコードを対象とする。
     */
    @Query("SELECT r.id FROM ProxyInputRecordEntity r WHERE r.retentionExpiresAt <= :cutoffDate")
    List<Long> findExpiredRecordIds(@Param("cutoffDate") java.time.LocalDate cutoffDate);

    /**
     * IDリストで指定した代理入力記録を物理削除する（RetentionJob用）。
     */
    @Modifying
    @Query("DELETE FROM ProxyInputRecordEntity r WHERE r.id IN :ids")
    void deleteByIdIn(@Param("ids") List<Long> ids);

    /**
     * 指定ユーザーが本人（subject）として関与する代理入力記録を全件取得する（GDPRエクスポート用）。
     */
    List<ProxyInputRecordEntity> findBySubjectUserId(Long subjectUserId);

    /**
     * 退会時物理削除用: 指定ユーザーが本人（subject）の代理入力記録を全件物理削除する。
     */
    @Modifying
    @Query("DELETE FROM ProxyInputRecordEntity r WHERE r.subjectUserId = :userId")
    void deleteAllBySubjectUserId(@Param("userId") Long userId);
}
