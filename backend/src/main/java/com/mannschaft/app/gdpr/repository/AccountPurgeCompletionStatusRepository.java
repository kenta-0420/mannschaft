package com.mannschaft.app.gdpr.repository;

import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
 * <p>Phase E 拡張: システム管理者向け読み取り専用 API をサポートするため
 * {@link JpaSpecificationExecutor} を追加した。動的検索・サマリー集計・CSV エクスポートに使用する。</p>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase D-8 / Phase E</p>
 */
public interface AccountPurgeCompletionStatusRepository
        extends JpaRepository<AccountPurgeCompletionStatusEntity, UUID>,
                JpaSpecificationExecutor<AccountPurgeCompletionStatusEntity> {

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

    // ---- Phase E: システム管理者向け読み取り専用 API 追加メソッド ----

    /**
     * ユーザー詳細取得用: userId に紐づく全ドメイン行をドメイン名昇順で返す。
     *
     * @param userId 対象ユーザー ID
     * @return 対象ユーザーの全 per-domain レコード（ドメイン名昇順）
     */
    List<AccountPurgeCompletionStatusEntity> findByUserIdOrderByDomainName(Long userId);

    /**
     * サマリー集計用: domainName × status のカウントを返す。
     *
     * <p>戻り値の {@code Object[]} の構造: {@code [0]=domainName, [1]=status, [2]=count}。</p>
     *
     * @return ドメイン × ステータス別カウント
     */
    @Query("SELECT e.domainName, e.status, COUNT(e) FROM AccountPurgeCompletionStatusEntity e GROUP BY e.domainName, e.status")
    List<Object[]> countByDomainAndStatus();

    /**
     * アラート対象件数取得: PENDING かつ指定閾値より古い attemptedAt を持つレコード数。
     *
     * <p>GDPR Art.17「30日以内削除完了」の監視に使用する。</p>
     *
     * @param threshold この日時より前の {@code attemptedAt} を持つ PENDING レコードをカウント
     * @return アラート対象レコード数
     */
    @Query("SELECT COUNT(e) FROM AccountPurgeCompletionStatusEntity e WHERE e.status = 'PENDING' AND e.attemptedAt < :threshold")
    long countAlerting(@Param("threshold") LocalDateTime threshold);

    /**
     * 指定ユーザー・ドメインの完了ステータスを SUCCESS に更新する（bulk update・残債1）。
     *
     * <p>他の {@code *PurgeEventListener} は {@link #findByUserIdAndDomainName} で取得した
     * {@link AccountPurgeCompletionStatusEntity} を直接ミューテートして SUCCESS 更新する（Phase D-8 前例）。
     * {@code BillingPurgeEventListener} はこの方式を採らず本メソッド経由で更新する。理由:
     * 凍結 ArchUnit {@code CrossDomainEntityImportArchTest}（D-1）は既存 6 ドメインの直接ミューテートを
     * 既存違反として凍結済みだが、billing ドメインは今回新規登録のため、同じ direct-mutate 方式を billing で
     * 行うと {@code BillingPurgeEventListener → gdpr.entity.AccountPurgeCompletionStatusEntity} という
     * <b>新規</b>のクロスドメイン Entity 依存が発生し番人テストが fail する。本メソッドは Entity 型を
     * 一切公開しない bulk update のため、呼び出し側（billing ドメイン）は Entity に一切触れず番人テストに
     * 抵触しない。</p>
     *
     * <p>{@code @Transactional} を本メソッドに直接付与する: 呼び出し元（{@code BillingPurgeEventListener
     * #onAccountPurged}）は Stripe への外部 HTTP 呼び出しをトランザクション外で行う設計（DB 接続の長時間占有
     * 回避）のため、呼び出し元メソッド全体を {@code @Transactional} にできない。Spring Data のリポジトリは
     * それ自体が別 Bean としてプロキシされるため、ここに {@code @Transactional} を付けることで
     * 「この 1 回の bulk update だけ」を独立した小さなトランザクションで実行できる
     * （self-invocation 問題は発生しない・Spring Data の標準対応パターン）。</p>
     *
     * @param userId      対象ユーザー ID
     * @param domainName  ドメイン識別子（例: {@code "billing"}）
     * @param completedAt 完了日時
     * @return 更新件数（0 = 対象レコードなし、1 = 更新成功）
     */
    @Transactional
    @Modifying
    @Query("UPDATE AccountPurgeCompletionStatusEntity e SET e.status = 'SUCCESS', e.completedAt = :completedAt "
            + "WHERE e.userId = :userId AND e.domainName = :domainName")
    int markSuccess(
            @Param("userId") Long userId,
            @Param("domainName") String domainName,
            @Param("completedAt") LocalDateTime completedAt);
}
