package com.mannschaft.app.gdpr.service;

import com.mannschaft.app.billing.BillingPurgeEventListener;
import com.mannschaft.app.chart.event.ChartPurgeEventListener;
import com.mannschaft.app.errorreport.event.ErrorReportPurgeEventListener;
import com.mannschaft.app.gdpr.dto.RetryResultResponse;
import com.mannschaft.app.gdpr.entity.AccountPurgeCompletionStatusEntity;
import com.mannschaft.app.gdpr.repository.AccountPurgeCompletionStatusRepository;
import com.mannschaft.app.payment.event.PaymentPurgeEventListener;
import com.mannschaft.app.proxy.event.ProxyPurgeEventListener;
import com.mannschaft.app.role.event.RolePurgeEventListener;
import com.mannschaft.app.team.event.TeamPurgeEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * GDPR パージ手動 retry サービス（Phase F）。
 *
 * <p>システム管理者が管理画面から PENDING 状態のドメインパージを手動で再実行する機能を提供する。
 * 各ドメインリスナーの {@code retryPurge(userId)} を呼び出し、完了後に
 * {@code account_purge_completion_status} の retry_count / last_retried_at / status を更新する。</p>
 *
 * <h2>責務の分担</h2>
 * <ul>
 *   <li>各 {@code *PurgeEventListener#retryPurge(userId)} — ドメイン操作（completionStatus 更新なし）</li>
 *   <li>本サービス — completionStatus の retry_count / last_retried_at / status 更新を一元管理</li>
 * </ul>
 *
 * <p>設計根拠: {@code docs/architecture/account_purge_cross_domain_refactor.md} §4 Phase F</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GdprPurgeRetryService {

    private final AccountPurgeCompletionStatusRepository completionStatusRepository;
    private final RolePurgeEventListener rolePurgeEventListener;
    private final TeamPurgeEventListener teamPurgeEventListener;
    private final PaymentPurgeEventListener paymentPurgeEventListener;
    private final ChartPurgeEventListener chartPurgeEventListener;
    private final ProxyPurgeEventListener proxyPurgeEventListener;
    private final ErrorReportPurgeEventListener errorReportPurgeEventListener;
    private final BillingPurgeEventListener billingPurgeEventListener;

    /** 受け付けるドメイン名の集合。不明なドメイン名は即時 IllegalArgumentException。 */
    private static final Set<String> VALID_DOMAINS =
            Set.of("role", "team", "payment", "chart", "proxy", "errorreport", "billing");

    /**
     * 指定ユーザー × ドメインの GDPR パージを手動で retry する。
     *
     * <p>対象の completionStatus レコードが存在しない場合、または既に SUCCESS の場合は
     * ドメイン操作を実行せず即座に返す。retry 後は retry_count と last_retried_at を必ず更新し、
     * retry 成功時は status を SUCCESS に更新する。</p>
     *
     * @param userId     retry 対象ユーザー ID
     * @param domainName retry 対象ドメイン（role / team / payment / chart / proxy / errorreport）
     * @return retry 結果
     * @throws IllegalArgumentException 不明なドメイン名、または対象レコードが存在しない場合
     */
    @Transactional
    public RetryResultResponse retryDomainPurge(Long userId, String domainName) {
        if (!VALID_DOMAINS.contains(domainName)) {
            throw new IllegalArgumentException("不明なドメイン名: " + domainName);
        }

        AccountPurgeCompletionStatusEntity entity =
                completionStatusRepository.findByUserIdAndDomainName(userId, domainName)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "対象レコードが見つかりません userId=" + userId + " domain=" + domainName));

        // 既に SUCCESS の場合はドメイン操作を実行せず即座に返す
        if ("SUCCESS".equals(entity.getStatus())) {
            return new RetryResultResponse(
                    true, domainName, "SUCCESS", entity.getRetryCount(), "既に処理済みです");
        }

        // ドメイン操作を実行（completionStatusRepository の更新はここでは行わない）
        boolean succeeded = executeRetry(userId, domainName);

        // retry_count / last_retried_at を必ず更新（成功・失敗いずれの場合も）
        entity.setRetryCount(entity.getRetryCount() + 1);
        entity.setLastRetriedAt(LocalDateTime.now());

        if (succeeded) {
            entity.setStatus("SUCCESS");
            entity.setCompletedAt(LocalDateTime.now());
            log.info("GDPR パージ retry 成功: userId={} domain={} retryCount={}",
                    userId, domainName, entity.getRetryCount());
        } else {
            log.warn("GDPR パージ retry 失敗（PENDING 継続）: userId={} domain={} retryCount={}",
                    userId, domainName, entity.getRetryCount());
        }

        completionStatusRepository.save(entity);

        return new RetryResultResponse(
                succeeded,
                domainName,
                entity.getStatus(),
                entity.getRetryCount(),
                succeeded ? "retry 成功" : "retry 失敗（PENDING 継続）");
    }

    /**
     * ドメイン名に応じて対応するリスナーの {@code retryPurge()} を呼び出す。
     *
     * @param userId     retry 対象ユーザー ID
     * @param domainName retry 対象ドメイン
     * @return true=成功、false=失敗
     */
    private boolean executeRetry(Long userId, String domainName) {
        return switch (domainName) {
            case "role"        -> rolePurgeEventListener.retryPurge(userId);
            case "team"        -> teamPurgeEventListener.retryPurge(userId);
            case "payment"     -> paymentPurgeEventListener.retryPurge(userId);
            case "chart"       -> chartPurgeEventListener.retryPurge(userId);
            case "proxy"       -> proxyPurgeEventListener.retryPurge(userId);
            case "errorreport" -> errorReportPurgeEventListener.retryPurge(userId);
            case "billing"     -> billingPurgeEventListener.retryPurge(userId);
            default -> throw new IllegalStateException("到達不能: " + domainName);
        };
    }
}
