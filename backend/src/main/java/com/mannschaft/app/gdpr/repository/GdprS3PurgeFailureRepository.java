package com.mannschaft.app.gdpr.repository;

import com.mannschaft.app.gdpr.entity.GdprS3PurgeFailureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * {@link GdprS3PurgeFailureEntity} の永続化リポジトリ。
 *
 * <p>GDPR 退会処理における S3 削除失敗レコードを操作する。
 * gdpr ドメイン内で完結するリポジトリのため、クロスドメイン制約違反なし。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase G</p>
 */
public interface GdprS3PurgeFailureRepository extends JpaRepository<GdprS3PurgeFailureEntity, UUID> {

    /**
     * 未解決（{@code resolved_at} が null）の S3 削除失敗レコードを全件取得する。
     *
     * <p>{@code GdprPurgeAuditBatchService#retryS3PurgeFailures} がリトライ対象を取得するために使用する。</p>
     *
     * @return 未解決の失敗レコード一覧
     */
    List<GdprS3PurgeFailureEntity> findByResolvedAtIsNull();
}
