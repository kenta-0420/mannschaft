package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageUsageLogEntity;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageSubscriptionRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageUsageLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F13 統合ストレージクォータサービス。
 *
 * <p>各機能（F03.15 個人時間割メモ添付・F04.2 チャット・F03.14 スケジュールメディア・F04.1 タイムライン・
 * F06.1 CMS・F06.2 ギャラリー・F05.5 ファイル共有 等）の R2 アップロード経路は、本サービスを通じて
 * 容量チェック・使用量計上・削除時の減算を行う。</p>
 *
 * <p>API シグネチャは設計書 §4「クォータチェック共通フロー」に準拠する。</p>
 *
 * @see <a href="../../../../../../../docs/cross-cutting/storage_quota.md">設計書</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StorageQuotaService {

    private final StoragePlanRepository planRepository;
    private final StorageSubscriptionRepository subscriptionRepository;
    private final StorageUsageLogRepository usageLogRepository;

    /**
     * アップロード前のクォータチェック。
     *
     * <p>容量超過時は {@link StorageQuotaExceededException} をスローする。
     * 呼び出し元の機能サービスは、これをキャッチしてユーザー向けの固有エラーコードに
     * 変換してから再スローしてもよい。</p>
     *
     * <p>サブスクリプションが未作成の場合は、デフォルトプランで自動作成する（{@code REQUIRES_NEW}
     * トランザクションで分離して作成する）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID（organizations.id / teams.id / users.id）
     * @param fileSizeBytes 追加しようとしているファイルサイズ（バイト）
     */
    public void checkQuota(StorageScopeType scopeType, Long scopeId, long fileSizeBytes) {
        if (fileSizeBytes < 0) {
            throw new IllegalArgumentException("fileSizeBytes must be >= 0");
        }
        StorageSubscriptionEntity subscription = ensureSubscription(scopeType, scopeId);
        StoragePlanEntity plan = planRepository.findById(subscription.getPlanId())
                .orElseThrow(() -> new BusinessException(StorageQuotaErrorCode.SUBSCRIPTION_NOT_FOUND));

        long used = subscription.getUsedBytes() != null ? subscription.getUsedBytes() : 0L;
        long included = plan.getIncludedBytes() != null ? plan.getIncludedBytes() : 0L;
        long projected = used + fileSizeBytes;

        // included_bytes 内なら必ず許可
        if (projected <= included) {
            return;
        }
        // included_bytes 超過 → 超過課金が NULL の場合はハードブロック
        if (plan.getPricePerExtraGb() == null) {
            throw new StorageQuotaExceededException(scopeType, scopeId, fileSizeBytes, used, included);
        }
        // max_bytes が設定されていれば追加判定
        Long maxBytes = plan.getMaxBytes();
        if (maxBytes != null && projected > maxBytes) {
            throw new StorageQuotaExceededException(scopeType, scopeId, fileSizeBytes, used, maxBytes);
        }
        // 超過課金あり、max_bytes 内 → 許可（Phase 8 で実課金）
    }

    /**
     * アップロード完了後の使用量加算。
     *
     * <p>同一トランザクション内で {@code storage_subscriptions.used_bytes} と
     * {@code storage_usage_logs} を更新する。</p>
     */
    @Transactional
    public void recordUpload(StorageScopeType scopeType, Long scopeId,
                              long fileSizeBytes, StorageFeatureType featureType,
                              String referenceType, Long referenceId, Long actorId) {
        applyDelta(scopeType, scopeId, fileSizeBytes, +1, featureType,
                referenceType, referenceId, actorId, StorageActionType.UPLOAD);
    }

    /**
     * ファイル削除後の使用量減算。
     */
    @Transactional
    public void recordDeletion(StorageScopeType scopeType, Long scopeId,
                                long fileSizeBytes, StorageFeatureType featureType,
                                String referenceType, Long referenceId, Long actorId) {
        applyDelta(scopeType, scopeId, -fileSizeBytes, -1, featureType,
                referenceType, referenceId, actorId, StorageActionType.DELETE);
    }

    // ---- Internal ----

    private void applyDelta(StorageScopeType scopeType, Long scopeId,
                            long deltaBytes, int deltaCount,
                            StorageFeatureType featureType,
                            String referenceType, Long referenceId, Long actorId,
                            StorageActionType action) {
        StorageSubscriptionEntity subscription = ensureSubscription(scopeType, scopeId);
        // 悲観ロックで取り直す（lost update 防止）
        StorageSubscriptionEntity locked = subscriptionRepository
                .findForUpdate(scopeType.name(), scopeId)
                .orElse(subscription);
        locked.applyDelta(deltaBytes, deltaCount);
        StorageSubscriptionEntity saved = subscriptionRepository.save(locked);

        StorageUsageLogEntity logEntity = StorageUsageLogEntity.builder()
                .subscriptionId(saved.getId())
                .deltaBytes(deltaBytes)
                .afterBytes(saved.getUsedBytes())
                .featureType(featureType.name())
                .referenceType(referenceType)
                .referenceId(referenceId)
                .action(action.name())
                .actorId(actorId)
                .build();
        usageLogRepository.save(logEntity);

        log.info("F13 ストレージ使用量を更新: scope={}/{}, delta={}, after={}, feature={}, action={}",
                scopeType, scopeId, deltaBytes, saved.getUsedBytes(), featureType, action);
    }

    /**
     * サブスクリプションを取得し、未存在ならデフォルトプランで自動作成する。
     */
    @Transactional(propagation = Propagation.REQUIRED)
    protected StorageSubscriptionEntity ensureSubscription(StorageScopeType scopeType, Long scopeId) {
        return subscriptionRepository.findByScopeTypeAndScopeId(scopeType.name(), scopeId)
                .orElseGet(() -> createDefault(scopeType, scopeId));
    }

    private StorageSubscriptionEntity createDefault(StorageScopeType scopeType, Long scopeId) {
        StoragePlanEntity defaultPlan = planRepository
                .findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull(scopeType.name())
                .orElseThrow(() -> new BusinessException(StorageQuotaErrorCode.SUBSCRIPTION_NOT_FOUND));
        StorageSubscriptionEntity entity = StorageSubscriptionEntity.builder()
                .scopeType(scopeType.name())
                .scopeId(scopeId)
                .planId(defaultPlan.getId())
                .usedBytes(0L)
                .fileCount(0)
                .build();
        log.info("F13 ストレージサブスクリプションを自動作成: scope={}/{}, planId={}",
                scopeType, scopeId, defaultPlan.getId());
        return subscriptionRepository.save(entity);
    }
}
