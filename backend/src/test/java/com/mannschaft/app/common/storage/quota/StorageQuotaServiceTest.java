package com.mannschaft.app.common.storage.quota;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.quota.entity.StoragePlanEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageSubscriptionEntity;
import com.mannschaft.app.common.storage.quota.entity.StorageUsageLogEntity;
import com.mannschaft.app.common.storage.quota.repository.StoragePlanRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageSubscriptionRepository;
import com.mannschaft.app.common.storage.quota.repository.StorageUsageLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * F13 統合ストレージクォータサービスのユニットテスト。
 *
 * <p>Phase 4-α 救出 PR で新設。設計書 §4 のクォータチェック共通フロー（included_bytes 内許可・
 * pricePerExtraGb による超過課金許可・max_bytes ハードブロック・recordUpload/Deletion の
 * lost-update 防止）を検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StorageQuotaService ユニットテスト")
class StorageQuotaServiceTest {

    private static final Long USER_ID = 100L;
    private static final Long PLAN_ID = 1L;
    private static final Long SUBSCRIPTION_ID = 11L;

    @Mock private StoragePlanRepository planRepository;
    @Mock private StorageSubscriptionRepository subscriptionRepository;
    @Mock private StorageUsageLogRepository usageLogRepository;

    @InjectMocks private StorageQuotaService service;

    private StoragePlanEntity hardBlockPersonalPlan;

    @BeforeEach
    void setUp() {
        // 個人プラン: 1GB 無料・超過課金なし（ハードブロック）
        hardBlockPersonalPlan = StoragePlanEntity.builder()
                .id(PLAN_ID)
                .name("フリー（個人）")
                .scopeLevel("PERSONAL")
                .includedBytes(1024L * 1024 * 1024) // 1GB
                .maxBytes(1024L * 1024 * 1024)
                .priceMonthly(BigDecimal.ZERO)
                .pricePerExtraGb(null)   // ハードブロック
                .isDefault(true)
                .sortOrder((short) 1)
                .build();
    }

    @Nested
    @DisplayName("checkQuota")
    class CheckQuota {

        @Test
        @DisplayName("正常系: included_bytes 内なら許可（既存 subscription）")
        void 正常系_既存サブスクリプション内() {
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("PERSONAL").scopeId(USER_ID)
                    .planId(PLAN_ID).usedBytes(500L * 1024 * 1024).fileCount(10).build();
            given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(hardBlockPersonalPlan));

            // 500MB 既使用 + 100MB 追加 = 600MB ≤ 1GB → 許可
            service.checkQuota(StorageScopeType.PERSONAL, USER_ID, 100L * 1024 * 1024);
        }

        @Test
        @DisplayName("正常系: subscription 未存在ならデフォルトプランで自動作成して許可")
        void 正常系_未存在で自動作成() {
            given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                    .willReturn(Optional.empty());
            given(planRepository.findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull("PERSONAL"))
                    .willReturn(Optional.of(hardBlockPersonalPlan));
            given(subscriptionRepository.save(any(StorageSubscriptionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));
            given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(hardBlockPersonalPlan));

            service.checkQuota(StorageScopeType.PERSONAL, USER_ID, 1L);

            verify(subscriptionRepository).save(any(StorageSubscriptionEntity.class));
        }

        @Test
        @DisplayName("異常系: included_bytes 超過かつハードブロック → StorageQuotaExceededException")
        void 異常系_ハードブロック() {
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("PERSONAL").scopeId(USER_ID)
                    .planId(PLAN_ID).usedBytes(1023L * 1024 * 1024).fileCount(10).build();
            given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(hardBlockPersonalPlan));

            // 1023MB + 10MB = 1033MB > 1GB → 拒否
            assertThatThrownBy(() ->
                    service.checkQuota(StorageScopeType.PERSONAL, USER_ID, 10L * 1024 * 1024))
                    .isInstanceOf(StorageQuotaExceededException.class)
                    .hasFieldOrPropertyWithValue("scopeType", StorageScopeType.PERSONAL)
                    .hasFieldOrPropertyWithValue("scopeId", USER_ID);
        }

        @Test
        @DisplayName("正常系: included_bytes 超過でも pricePerExtraGb があれば max_bytes 内で許可")
        void 正常系_超過課金プラン() {
            StoragePlanEntity overagePlan = hardBlockPersonalPlan.toBuilder()
                    .pricePerExtraGb(BigDecimal.valueOf(100))   // 超過課金あり
                    .maxBytes(2L * 1024 * 1024 * 1024)           // 2GB ハード上限
                    .build();
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("PERSONAL").scopeId(USER_ID)
                    .planId(PLAN_ID).usedBytes(1024L * 1024 * 1024).fileCount(10).build();
            given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(overagePlan));

            // 1GB 既使用 + 500MB 追加 = 1.5GB > included(1GB) だが max(2GB) 内 → 許可
            service.checkQuota(StorageScopeType.PERSONAL, USER_ID, 500L * 1024 * 1024);
        }

        @Test
        @DisplayName("異常系: max_bytes 超過は超過課金プランでも拒否")
        void 異常系_max_bytes超過() {
            StoragePlanEntity overagePlan = hardBlockPersonalPlan.toBuilder()
                    .pricePerExtraGb(BigDecimal.valueOf(100))
                    .maxBytes(2L * 1024 * 1024 * 1024)
                    .build();
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("PERSONAL").scopeId(USER_ID)
                    .planId(PLAN_ID).usedBytes(2L * 1024 * 1024 * 1024).fileCount(10).build();
            given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(planRepository.findById(PLAN_ID)).willReturn(Optional.of(overagePlan));

            assertThatThrownBy(() ->
                    service.checkQuota(StorageScopeType.PERSONAL, USER_ID, 1L))
                    .isInstanceOf(StorageQuotaExceededException.class);
        }

        @Test
        @DisplayName("異常系: デフォルトプラン未登録 → SUBSCRIPTION_NOT_FOUND")
        void 異常系_デフォルトプラン未登録() {
            given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                    .willReturn(Optional.empty());
            given(planRepository.findFirstByScopeLevelAndIsDefaultTrueAndDeletedAtIsNull("PERSONAL"))
                    .willReturn(Optional.empty());

            assertThatThrownBy(() ->
                    service.checkQuota(StorageScopeType.PERSONAL, USER_ID, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode",
                            StorageQuotaErrorCode.SUBSCRIPTION_NOT_FOUND);
        }

        @Test
        @DisplayName("異常系: fileSizeBytes が負数なら IllegalArgumentException")
        void 異常系_負のサイズ() {
            assertThatThrownBy(() ->
                    service.checkQuota(StorageScopeType.PERSONAL, USER_ID, -1L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("recordUpload / recordDeletion")
    class RecordOperations {

        @Test
        @DisplayName("正常系: recordUpload で used_bytes と file_count が加算され、ログが INSERT される")
        void 正常系_recordUpload() {
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("PERSONAL").scopeId(USER_ID)
                    .planId(PLAN_ID).usedBytes(1000L).fileCount(5).build();
            given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(subscriptionRepository.findForUpdate("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(subscriptionRepository.save(any(StorageSubscriptionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.recordUpload(StorageScopeType.PERSONAL, USER_ID, 500L,
                    StorageFeatureType.PERSONAL_TIMETABLE_NOTES,
                    "timetable_slot_user_note_attachments", 999L, USER_ID);

            assertThat(sub.getUsedBytes()).isEqualTo(1500L);
            assertThat(sub.getFileCount()).isEqualTo(6);

            ArgumentCaptor<StorageUsageLogEntity> captor =
                    ArgumentCaptor.forClass(StorageUsageLogEntity.class);
            verify(usageLogRepository).save(captor.capture());
            StorageUsageLogEntity logEntity = captor.getValue();
            assertThat(logEntity.getDeltaBytes()).isEqualTo(500L);
            assertThat(logEntity.getAfterBytes()).isEqualTo(1500L);
            assertThat(logEntity.getFeatureType()).isEqualTo("PERSONAL_TIMETABLE_NOTES");
            assertThat(logEntity.getReferenceType()).isEqualTo("timetable_slot_user_note_attachments");
            assertThat(logEntity.getReferenceId()).isEqualTo(999L);
            assertThat(logEntity.getAction()).isEqualTo("UPLOAD");
            assertThat(logEntity.getActorId()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("正常系: recordDeletion で used_bytes が減算され、負数を 0 にクランプ")
        void 正常系_recordDeletion_クランプ() {
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("PERSONAL").scopeId(USER_ID)
                    .planId(PLAN_ID).usedBytes(100L).fileCount(1).build();
            given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(subscriptionRepository.findForUpdate("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(subscriptionRepository.save(any(StorageSubscriptionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            // used 100 から 1000 引いても 0 にクランプ
            service.recordDeletion(StorageScopeType.PERSONAL, USER_ID, 1000L,
                    StorageFeatureType.PERSONAL_TIMETABLE_NOTES,
                    "timetable_slot_user_note_attachments", 999L, USER_ID);

            assertThat(sub.getUsedBytes()).isEqualTo(0L);
            assertThat(sub.getFileCount()).isEqualTo(0);

            ArgumentCaptor<StorageUsageLogEntity> captor =
                    ArgumentCaptor.forClass(StorageUsageLogEntity.class);
            verify(usageLogRepository).save(captor.capture());
            assertThat(captor.getValue().getAction()).isEqualTo("DELETE");
            assertThat(captor.getValue().getDeltaBytes()).isEqualTo(-1000L);
        }

        @Test
        @DisplayName("正常系: findForUpdate で悲観ロックを取得しに行く")
        void ロック取得() {
            StorageSubscriptionEntity sub = StorageSubscriptionEntity.builder()
                    .id(SUBSCRIPTION_ID).scopeType("PERSONAL").scopeId(USER_ID)
                    .planId(PLAN_ID).usedBytes(0L).fileCount(0).build();
            given(subscriptionRepository.findByScopeTypeAndScopeId("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(subscriptionRepository.findForUpdate("PERSONAL", USER_ID))
                    .willReturn(Optional.of(sub));
            given(subscriptionRepository.save(any(StorageSubscriptionEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            service.recordUpload(StorageScopeType.PERSONAL, USER_ID, 1L,
                    StorageFeatureType.PERSONAL_TIMETABLE_NOTES,
                    "ref", 1L, USER_ID);

            verify(subscriptionRepository).findForUpdate(eq("PERSONAL"), eq(USER_ID));
        }
    }
}
