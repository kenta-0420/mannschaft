package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageSubscriptionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link StorageSubscriptionProvisioningService} の単体テスト（Phase 4-D 根治）。
 *
 * <p>デフォルトサブスクリプションの自動作成（未存在時 INSERT・既存時は再利用・
 * デフォルトプラン未登録エラー・並行作成レース時の取り直し）を検証する。
 * 本 Bean は {@code REQUIRES_NEW} で外側 readOnly トランザクションから分離され、
 * INSERT が確実にプライマリで実行される（read-only コネクション INSERT 500 の根治）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageSubscriptionProvisioningService 単体テスト")
class StorageSubscriptionProvisioningServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long PLAN_ID = 1L;

    @Mock private StoragePlanRepository planRepository;
    @Mock private StorageSubscriptionRepository subscriptionRepository;

    @InjectMocks private StorageSubscriptionProvisioningService service;

    private StoragePlanEntity defaultPersonalPlan() {
        return StoragePlanEntity.builder()
                .id(PLAN_ID).name("フリー（個人）").scopeLevel("PERSONAL")
                .includedBytes(1024L * 1024 * 1024).isDefault(true)
                .build();
    }

    @Test
    @DisplayName("未存在ならデフォルトプランで INSERT して返す")
    void 未存在で作成() {
        given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                .willReturn(Optional.empty());
        given(planRepository.findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull("PERSONAL"))
                .willReturn(Optional.of(defaultPersonalPlan()));
        given(subscriptionRepository.saveAndFlush(any(StorageSubscriptionEntity.class)))
                .willAnswer(inv -> inv.getArgument(0));

        StorageSubscriptionEntity result =
                service.getOrCreateDefault(StorageScopeType.PERSONAL, USER_ID);

        ArgumentCaptor<StorageSubscriptionEntity> captor =
                ArgumentCaptor.forClass(StorageSubscriptionEntity.class);
        verify(subscriptionRepository).saveAndFlush(captor.capture());
        StorageSubscriptionEntity inserted = captor.getValue();
        assertThat(inserted.getScopeType()).isEqualTo("PERSONAL");
        assertThat(inserted.getScopeId()).isEqualTo(USER_ID);
        assertThat(inserted.getPlanId()).isEqualTo(PLAN_ID);
        assertThat(inserted.getUsedBytes()).isZero();
        assertThat(inserted.getFileCount()).isZero();
        assertThat(result).isSameAs(inserted);
    }

    @Test
    @DisplayName("既存があれば INSERT せず再利用する")
    void 既存を再利用() {
        StorageSubscriptionEntity existing = StorageSubscriptionEntity.builder()
                .id(11L).scopeType("PERSONAL").scopeId(USER_ID)
                .planId(PLAN_ID).usedBytes(5L).fileCount(1).build();
        given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                .willReturn(Optional.of(existing));

        StorageSubscriptionEntity result =
                service.getOrCreateDefault(StorageScopeType.PERSONAL, USER_ID);

        assertThat(result).isSameAs(existing);
        verify(subscriptionRepository, never()).saveAndFlush(any());
        verify(planRepository, never())
                .findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull(any());
    }

    @Test
    @DisplayName("デフォルトプラン未登録なら SUBSCRIPTION_NOT_FOUND")
    void デフォルトプラン未登録() {
        given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                .willReturn(Optional.empty());
        given(planRepository.findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull("PERSONAL"))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrCreateDefault(StorageScopeType.PERSONAL, USER_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode",
                        StorageQuotaErrorCode.SUBSCRIPTION_NOT_FOUND);
    }

    @Test
    @DisplayName("並行初回作成で一意制約違反なら取り直して既存を返す")
    void 並行作成レースで取り直し() {
        StorageSubscriptionEntity concurrent = StorageSubscriptionEntity.builder()
                .id(12L).scopeType("PERSONAL").scopeId(USER_ID)
                .planId(PLAN_ID).usedBytes(0L).fileCount(0).build();
        // 1回目 empty（作成へ）、2回目（取り直し）で他リクエストが作成済みの行を返す
        given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                .willReturn(Optional.empty())
                .willReturn(Optional.of(concurrent));
        given(planRepository.findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull("PERSONAL"))
                .willReturn(Optional.of(defaultPersonalPlan()));
        given(subscriptionRepository.saveAndFlush(any(StorageSubscriptionEntity.class)))
                .willThrow(new DataIntegrityViolationException("duplicate key"));

        StorageSubscriptionEntity result =
                service.getOrCreateDefault(StorageScopeType.PERSONAL, USER_ID);

        assertThat(result).isSameAs(concurrent);
    }
}
