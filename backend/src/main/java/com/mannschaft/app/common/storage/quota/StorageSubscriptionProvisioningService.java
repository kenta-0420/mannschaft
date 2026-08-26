package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageSubscriptionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * F13 ストレージサブスクリプションの自動プロビジョニング（未存在スコープのデフォルト作成）。
 *
 * <h2>なぜ独立サービス＋{@code REQUIRES_NEW} なのか（根治ポイント）</h2>
 * <p>デフォルトサブスクリプションの INSERT は、<b>呼び出し元の外側トランザクションが
 * {@code readOnly = true}（＝レプリカへルーティング）でも、必ずプライマリで実行される</b>必要がある。
 * 従来は {@link StorageQuotaService} 内の {@code protected} メソッドを<b>自己呼び出し</b>していたため、
 * {@code @Transactional(REQUIRES_NEW)} が AOP プロキシに載らず（自己呼び出しはプロキシを経由しない）、
 * 外側 readOnly トランザクション（レプリカ・read-only コネクション）にそのまま参加して INSERT し、
 * {@code "Connection is read-only"} で 500 になっていた（新規ユーザー初回の presign-upload で発火）。</p>
 *
 * <p>本作成処理を<b>別 Bean の public メソッド</b>に切り出し、{@link Propagation#REQUIRES_NEW} で
 * <b>新規トランザクション（＝新規コネクション取得）</b>を張ることで、
 * {@link com.mannschaft.app.config.ReplicaRoutingAspect} が「書き込みメソッド＝プライマリ」を
 * 明示セットした状態でコネクションを取得でき、確実にプライマリへ INSERT される。
 * また外側トランザクションから分離されるため、外側がロールバックしても自動作成は保持される
 * （設計書 §4「REQUIRES_NEW で分離して作成する」の意図を実装として満たす）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StorageSubscriptionProvisioningService {

    private final StoragePlanRepository planRepository;
    private final StorageSubscriptionRepository subscriptionRepository;

    /**
     * スコープのサブスクリプションを取得し、未存在ならデフォルトプランで自動作成して返す。
     *
     * <p>外側トランザクションから独立した {@code REQUIRES_NEW} トランザクションで実行し、
     * 内部で存在確認 → 無ければ INSERT する（並行初回作成のレースを緩和）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @return 既存または新規作成したサブスクリプション
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public StorageSubscriptionEntity getOrCreateDefault(StorageScopeType scopeType, Long scopeId) {
        return subscriptionRepository.findByScopeTypeAndScopeId(scopeType.name(), scopeId)
                .orElseGet(() -> insertDefault(scopeType, scopeId));
    }

    private StorageSubscriptionEntity insertDefault(StorageScopeType scopeType, Long scopeId) {
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
        try {
            StorageSubscriptionEntity saved = subscriptionRepository.saveAndFlush(entity);
            log.info("F13 ストレージサブスクリプションを自動作成: scope={}/{}, planId={}",
                    scopeType, scopeId, defaultPlan.getId());
            return saved;
        } catch (DataIntegrityViolationException e) {
            // 並行初回作成で他リクエストが先に INSERT した場合は取り直す（一意制約: scope_type + scope_id）
            log.info("F13 ストレージサブスクリプションの並行作成を検知、既存を取得: scope={}/{}", scopeType, scopeId);
            return subscriptionRepository.findByScopeTypeAndScopeId(scopeType.name(), scopeId)
                    .orElseThrow(() -> e);
        }
    }
}
