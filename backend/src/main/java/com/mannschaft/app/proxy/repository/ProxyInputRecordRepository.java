package com.mannschaft.app.proxy.repository;

import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
