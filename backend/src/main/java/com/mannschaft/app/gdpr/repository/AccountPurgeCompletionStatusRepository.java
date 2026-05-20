package com.mannschaft.app.gdpr.repository;

import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AccountPurgeCompletionStatusEntity} の永続化リポジトリ。
 *
 * <p>GDPR Art.17「30日以内削除完了」の per-domain 完了証跡を操作する。
 * gdpr ドメイン内で完結するリポジトリのため、クロスドメイン制約違反なし。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase D-8</p>
 */
public interface AccountPurgeCompletionStatusRepository extends JpaRepository<AccountPurgeCompletionStatusEntity, UUID> {

    /**
     * 指定ステータスかつ指定日時より古い PENDING レコードを取得する（監査バッチ用）。
     *
     * <p>主な用途: {@code GdprPurgeAuditBatchService} が
     * {@code status = "PENDING"} かつ {@code attemptedAt < 2時間前} のレコードを検出し、
     * アラートログを出力する。</p>
     *
     * @param status    ステータス（例: {@code "PENDING"}）
     * @param threshold この日時より前の {@code attemptedAt} を持つレコードを対象とする
     * @return マッチしたエンティティのリスト
     */
    List<AccountPurgeCompletionStatusEntity> findByStatusAndAttemptedAtBefore(
            String status, LocalDateTime threshold);

    /**
     * 特定ユーザーの全ドメイン完了状況を取得する。
     *
     * @param userId 対象ユーザー ID
     * @return 対象ユーザーの全 per-domain レコード
     */
    List<AccountPurgeCompletionStatusEntity> findByUserId(Long userId);

    /**
     * 特定ユーザー・特定ドメインのレコードを取得する（*PurgeEventListener による SUCCESS 更新用）。
     *
     * <p>各 {@code *PurgeEventListener} が処理完了時に {@code status} を
     * {@code SUCCESS} に更新するために使用する。</p>
     *
     * @param userId     対象ユーザー ID
     * @param domainName ドメイン識別子（例: {@code "role"}, {@code "team"} 等）
     * @return マッチしたエンティティ（存在しない場合は空）
     */
    Optional<AccountPurgeCompletionStatusEntity> findByUserIdAndDomainName(Long userId, String domainName);
}
