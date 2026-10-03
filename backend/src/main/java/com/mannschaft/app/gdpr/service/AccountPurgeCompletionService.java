package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * GDPR パージの per-domain 完了報告サービス（Phase D-8・残債1）。
 *
 * <p>他ドメインの {@code *PurgeEventListener} が purge 処理完了時に
 * {@code account_purge_completion_status} を SUCCESS へ更新するための<b>gdpr ドメイン公開 API</b>。
 * CLAUDE.md「ドメイン間のデータ取得は Service のメソッド呼び出し経由」および凍結 ArchUnit
 * {@code CrossDomainTransactionalArchTest}（D-3: @Transactional クラスの他ドメイン Repository 依存禁止）・
 * {@code CrossDomainEntityImportArchTest}（D-1）に従い、他ドメインは gdpr の Repository/Entity に
 * 直接触れず本サービスだけを呼ぶ（既存 6 ドメインのリポジトリ直接更新は凍結済みの負債であり、
 * 新規ドメイン=billing からは本サービス経由が正・chip-away 方針）。</p>
 *
 * <p><b>トランザクション境界:</b> {@code REQUIRES_NEW} の最小 tx を本サービスが所有する。
 * 呼び出し元（例: {@code BillingPurgeEventListener}）は Stripe 等の外部 HTTP を伴い長いトランザクションを
 * 張れない/張らないため、完了報告のこの 1 更新だけを独立して確定させる（呼び出し元 tx の有無に依存しない）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountPurgeCompletionService {

    private final AccountPurgeCompletionStatusRepository completionStatusRepository;

    /**
     * 指定ユーザー×ドメインの purge 完了ステータスを SUCCESS に更新する（bulk update・Entity 非公開）。
     *
     * <p>対象の PENDING レコードが存在しない場合は 0 件更新で正常終了する（WARN ログのみ・
     * purge レコード INSERT 前の旧データ等に対する防御）。</p>
     *
     * @param userId     対象ユーザー ID
     * @param domainName ドメイン識別子（例: {@code "billing"}）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDomainSuccess(Long userId, String domainName) {
        int updated = completionStatusRepository.markSuccess(userId, domainName, LocalDateTime.now());
        if (updated == 0) {
            log.warn("GDPR purge 完了報告: 対象レコードなし（PENDING 未登録の可能性）userId={}, domain={}",
                    userId, domainName);
        }
    }
}
