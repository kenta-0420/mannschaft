package com.mannschaft.app.payment.admin;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.payment.FeePolicy;
import com.mannschaft.app.payment.FeePolicyAssignmentEntity;
import com.mannschaft.app.payment.FeePolicyAssignmentRepository;
import com.mannschaft.app.payment.FeePolicyEntity;
import com.mannschaft.app.payment.FeePolicyRepository;
import com.mannschaft.app.payment.admin.dto.FeePolicyAssignmentCreateRequest;
import com.mannschaft.app.payment.admin.dto.FeePolicyAssignmentResponse;
import com.mannschaft.app.payment.admin.dto.FeePolicyResponse;
import com.mannschaft.app.payment.admin.dto.FeePolicyUpsertRequest;
import com.mannschaft.app.payment.connect.ConnectPaymentErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 統一決済 R2: {@link FeePolicyAdminService} の Service 単体テスト（test-first）。
 *
 * <p>DB はモック。業務制約（DEFAULT 保護・率/固定額バリデーション・重複・参照整合・割当の論理削除/復活）を検証する。
 * 認可（SYSTEM_ADMIN）は Controller/SecurityConfig 側で担保するため本テストの範囲外。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeePolicyAdminService 単体テスト")
class FeePolicyAdminServiceTest {

    @Mock
    private FeePolicyRepository feePolicyRepository;
    @Mock
    private FeePolicyAssignmentRepository assignmentRepository;
    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private FeePolicyAdminService service;

    private static final Long ACTOR = 9L;

    private FeePolicyEntity policy(String key, BigDecimal rate, long flat, boolean enabled) {
        return FeePolicyEntity.builder()
                .policyKey(key)
                .displayName(key + " 表示")
                .percentRate(rate)
                .flatFeeMinor(flat)
                .enabled(enabled)
                .build();
    }

    private FeePolicyUpsertRequest upsert(String key, String name, BigDecimal rate, Long flat, Boolean enabled) {
        FeePolicyUpsertRequest r = new FeePolicyUpsertRequest();
        ReflectionTestUtils.setField(r, "policyKey", key);
        ReflectionTestUtils.setField(r, "displayName", name);
        ReflectionTestUtils.setField(r, "percentRate", rate);
        ReflectionTestUtils.setField(r, "flatFeeMinor", flat);
        ReflectionTestUtils.setField(r, "enabled", enabled);
        return r;
    }

    private FeePolicyAssignmentCreateRequest assignReq(String sourceKind, String subKey, String policyKey) {
        FeePolicyAssignmentCreateRequest r = new FeePolicyAssignmentCreateRequest();
        ReflectionTestUtils.setField(r, "sourceKind", sourceKind);
        ReflectionTestUtils.setField(r, "subKey", subKey);
        ReflectionTestUtils.setField(r, "policyKey", policyKey);
        return r;
    }

    @Nested
    @DisplayName("fee_policies CRUD")
    class Policies {

        @Test
        @DisplayName("一覧: enabled=false 含む全件を割当数付きで返す")
        void listPolicies_returnsAllWithAssignmentCount() {
            given(feePolicyRepository.findAllByOrderByPolicyKeyAsc()).willReturn(List.of(
                    policy("DEFAULT", new BigDecimal("0.0500"), 0L, true),
                    policy("RECRUITMENT_HELPER", new BigDecimal("0.0300"), 100L, false)));
            given(assignmentRepository.countByPolicyKeyAndDeletedAtIsNull("DEFAULT")).willReturn(2L);
            given(assignmentRepository.countByPolicyKeyAndDeletedAtIsNull("RECRUITMENT_HELPER")).willReturn(0L);

            List<FeePolicyResponse> result = service.listPolicies();

            assertThat(result).hasSize(2);
            assertThat(result.get(0).policyKey()).isEqualTo("DEFAULT");
            assertThat(result.get(0).assignmentCount()).isEqualTo(2L);
            assertThat(result.get(1).enabled()).isFalse();
        }

        @Test
        @DisplayName("単件: 不在は FEE_POLICY_NOT_FOUND")
        void getPolicy_notFound() {
            given(feePolicyRepository.findByPolicyKey("NOPE")).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.getPolicy("NOPE"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_NOT_FOUND);
        }

        @Test
        @DisplayName("作成: 正常系で保存・enabled 既定 TRUE")
        void create_ok() {
            given(feePolicyRepository.existsById("RECRUITMENT_HELPER")).willReturn(false);
            given(feePolicyRepository.save(any(FeePolicyEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            FeePolicyResponse res = service.createPolicy(
                    upsert("RECRUITMENT_HELPER", "助っ人", new BigDecimal("0.03"), 100L, null), ACTOR);

            assertThat(res.policyKey()).isEqualTo("RECRUITMENT_HELPER");
            assertThat(res.enabled()).isTrue();
            assertThat(res.flatFeeMinor()).isEqualTo(100L);
        }

        @Test
        @DisplayName("作成: 既存キーは FEE_POLICY_ALREADY_EXISTS（409）")
        void create_duplicate() {
            given(feePolicyRepository.existsById("DEFAULT")).willReturn(true);
            assertThatThrownBy(() -> service.createPolicy(
                    upsert("DEFAULT", "標準", new BigDecimal("0.05"), 0L, null), ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_ALREADY_EXISTS);
            verify(feePolicyRepository, never()).save(any());
        }

        @Test
        @DisplayName("作成: 率1以上は FEE_POLICY_INVALID_RATE（422）")
        void create_rateOutOfRange() {
            assertThatThrownBy(() -> service.createPolicy(
                    upsert("BAD", "不正", new BigDecimal("1.0"), 0L, null), ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_INVALID_RATE);
        }

        @Test
        @DisplayName("作成: 負の固定額は FEE_POLICY_INVALID_RATE（422）")
        void create_negativeFlat() {
            assertThatThrownBy(() -> service.createPolicy(
                    upsert("BAD", "不正", new BigDecimal("0.03"), -1L, null), ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_INVALID_RATE);
        }

        @Test
        @DisplayName("作成: 率・固定額がともに 0 は FEE_POLICY_INVALID_RATE（手数料ゼロ禁止）")
        void create_zeroFee() {
            assertThatThrownBy(() -> service.createPolicy(
                    upsert("ZERO", "ゼロ", new BigDecimal("0.0"), 0L, null), ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_INVALID_RATE);
        }

        @Test
        @DisplayName("更新: DEFAULT の率改定は許容する")
        void update_defaultRateChangeAllowed() {
            given(feePolicyRepository.findByPolicyKey("DEFAULT"))
                    .willReturn(Optional.of(policy("DEFAULT", new BigDecimal("0.0500"), 0L, true)));
            given(feePolicyRepository.save(any(FeePolicyEntity.class))).willAnswer(inv -> inv.getArgument(0));
            given(assignmentRepository.countByPolicyKeyAndDeletedAtIsNull("DEFAULT")).willReturn(0L);

            FeePolicyResponse res = service.updatePolicy(
                    "DEFAULT", upsert("DEFAULT", "標準", new BigDecimal("0.06"), 0L, true), ACTOR);

            assertThat(res.percentRate()).isEqualByComparingTo("0.06");
        }

        @Test
        @DisplayName("更新: DEFAULT を enabled=false にするのは FEE_POLICY_DEFAULT_IMMUTABLE（409）")
        void update_defaultDisableRejected() {
            given(feePolicyRepository.findByPolicyKey("DEFAULT"))
                    .willReturn(Optional.of(policy("DEFAULT", new BigDecimal("0.0500"), 0L, true)));
            assertThatThrownBy(() -> service.updatePolicy(
                    "DEFAULT", upsert("DEFAULT", "標準", new BigDecimal("0.05"), 0L, false), ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_DEFAULT_IMMUTABLE);
            verify(feePolicyRepository, never()).save(any());
        }

        @Test
        @DisplayName("無効化: DEFAULT は FEE_POLICY_DEFAULT_IMMUTABLE（409）で拒否")
        void disable_defaultRejected() {
            assertThatThrownBy(() -> service.disablePolicy("DEFAULT", ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_DEFAULT_IMMUTABLE);
            verify(feePolicyRepository, never()).findByPolicyKey(any());
            verify(feePolicyRepository, never()).save(any());
        }

        @Test
        @DisplayName("無効化: 通常パターンは enabled=false で保存")
        void disable_ok() {
            FeePolicyEntity entity = policy("RECRUITMENT_HELPER", new BigDecimal("0.03"), 100L, true);
            given(feePolicyRepository.findByPolicyKey("RECRUITMENT_HELPER")).willReturn(Optional.of(entity));
            given(feePolicyRepository.save(any(FeePolicyEntity.class))).willAnswer(inv -> inv.getArgument(0));

            service.disablePolicy("RECRUITMENT_HELPER", ACTOR);

            assertThat(entity.getEnabled()).isFalse();
            verify(feePolicyRepository).save(entity);
        }
    }

    @Nested
    @DisplayName("fee_policy_assignments CRUD")
    class Assignments {

        @Test
        @DisplayName("作成: 正常系（sub_key あり）→ 新規 INSERT")
        void create_ok() {
            given(feePolicyRepository.findByPolicyKey("RECRUITMENT_HELPER"))
                    .willReturn(Optional.of(policy("RECRUITMENT_HELPER", new BigDecimal("0.03"), 100L, true)));
            given(assignmentRepository.findBySourceKindAndSubKey("RECRUITMENT", "helper"))
                    .willReturn(Optional.empty());
            given(assignmentRepository.save(any(FeePolicyAssignmentEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            FeePolicyAssignmentResponse res = service.createAssignment(
                    assignReq("RECRUITMENT", "helper", "RECRUITMENT_HELPER"), ACTOR);

            assertThat(res.sourceKind()).isEqualTo("RECRUITMENT");
            assertThat(res.subKey()).isEqualTo("helper");
            assertThat(res.policyKey()).isEqualTo("RECRUITMENT_HELPER");
        }

        @Test
        @DisplayName("作成: 参照先 policy が不在 → FEE_POLICY_NOT_FOUND（404）")
        void create_policyNotFound() {
            given(feePolicyRepository.findByPolicyKey("NOPE")).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.createAssignment(
                    assignReq("RECRUITMENT", "helper", "NOPE"), ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_NOT_FOUND);
        }

        @Test
        @DisplayName("作成: 参照先 policy が無効 → FEE_POLICY_ASSIGNMENT_POLICY_DISABLED（422）")
        void create_policyDisabled() {
            given(feePolicyRepository.findByPolicyKey("OLD"))
                    .willReturn(Optional.of(policy("OLD", new BigDecimal("0.03"), 0L, false)));
            assertThatThrownBy(() -> service.createAssignment(
                    assignReq("RECRUITMENT", "helper", "OLD"), ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_ASSIGNMENT_POLICY_DISABLED);
        }

        @Test
        @DisplayName("作成: 同条件のアクティブ割当が既存 → FEE_POLICY_ASSIGNMENT_DUPLICATE（409）")
        void create_duplicateActive() {
            given(feePolicyRepository.findByPolicyKey("RECRUITMENT_HELPER"))
                    .willReturn(Optional.of(policy("RECRUITMENT_HELPER", new BigDecimal("0.03"), 100L, true)));
            FeePolicyAssignmentEntity active = FeePolicyAssignmentEntity.builder()
                    .sourceKind("RECRUITMENT").subKey("helper").policyKey("RECRUITMENT_HELPER").enabled(true).build();
            given(assignmentRepository.findBySourceKindAndSubKey("RECRUITMENT", "helper"))
                    .willReturn(Optional.of(active));

            assertThatThrownBy(() -> service.createAssignment(
                    assignReq("RECRUITMENT", "helper", "RECRUITMENT_HELPER"), ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_ASSIGNMENT_DUPLICATE);
            verify(assignmentRepository, never()).save(any());
        }

        @Test
        @DisplayName("作成: 論理削除済みの同条件行があれば復活させる（DB UNIQUE 違反回避）")
        void create_revivesSoftDeleted() {
            given(feePolicyRepository.findByPolicyKey("RECRUITMENT_HELPER"))
                    .willReturn(Optional.of(policy("RECRUITMENT_HELPER", new BigDecimal("0.03"), 100L, true)));
            FeePolicyAssignmentEntity deleted = FeePolicyAssignmentEntity.builder()
                    .sourceKind("RECRUITMENT").subKey("helper").policyKey("OLD").enabled(false)
                    .deletedAt(LocalDateTime.now().minusDays(1)).build();
            given(assignmentRepository.findBySourceKindAndSubKey("RECRUITMENT", "helper"))
                    .willReturn(Optional.of(deleted));
            given(assignmentRepository.save(any(FeePolicyAssignmentEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            FeePolicyAssignmentResponse res = service.createAssignment(
                    assignReq("RECRUITMENT", "helper", "RECRUITMENT_HELPER"), ACTOR);

            assertThat(deleted.getDeletedAt()).isNull();
            assertThat(deleted.getEnabled()).isTrue();
            assertThat(deleted.getPolicyKey()).isEqualTo("RECRUITMENT_HELPER");
            assertThat(res.policyKey()).isEqualTo("RECRUITMENT_HELPER");
        }

        @Test
        @DisplayName("作成: 不正な source_kind は FEE_POLICY_INVALID_RATE（入力不正・握りつぶさない）")
        void create_invalidSourceKind() {
            assertThatThrownBy(() -> service.createAssignment(
                    assignReq("UNKNOWN_KIND", null, "RECRUITMENT_HELPER"), ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.FEE_POLICY_INVALID_RATE);
        }

        @Test
        @DisplayName("作成: sub_key 空文字は source_kind 既定（NULL）として扱う")
        void create_blankSubKeyTreatedAsDefault() {
            given(feePolicyRepository.findByPolicyKey("RECRUITMENT_HELPER"))
                    .willReturn(Optional.of(policy("RECRUITMENT_HELPER", new BigDecimal("0.03"), 100L, true)));
            given(assignmentRepository.findBySourceKindAndSubKeyIsNull("RECRUITMENT"))
                    .willReturn(Optional.empty());
            given(assignmentRepository.save(any(FeePolicyAssignmentEntity.class)))
                    .willAnswer(inv -> inv.getArgument(0));

            FeePolicyAssignmentResponse res = service.createAssignment(
                    assignReq("RECRUITMENT", "  ", "RECRUITMENT_HELPER"), ACTOR);

            assertThat(res.subKey()).isNull();
        }

        @Test
        @DisplayName("解除: 未削除割当を論理削除（deletedAt セット・enabled=false）")
        void delete_ok() {
            UUID id = UUID.fromString("019607a0-0000-7000-8000-000000000099");
            FeePolicyAssignmentEntity entity = FeePolicyAssignmentEntity.builder()
                    .sourceKind("RECRUITMENT").subKey("helper").policyKey("RECRUITMENT_HELPER").enabled(true).build();
            entity.setId(id);
            given(assignmentRepository.findById(id)).willReturn(Optional.of(entity));
            given(assignmentRepository.save(any(FeePolicyAssignmentEntity.class))).willAnswer(inv -> inv.getArgument(0));

            service.deleteAssignment(id, ACTOR);

            assertThat(entity.getDeletedAt()).isNotNull();
            assertThat(entity.getEnabled()).isFalse();
        }

        @Test
        @DisplayName("解除: 不在は PAYMENT_RESOURCE_NOT_FOUND（404）")
        void delete_notFound() {
            UUID id = UUID.fromString("019607a0-0000-7000-8000-0000000000aa");
            given(assignmentRepository.findById(id)).willReturn(Optional.empty());
            assertThatThrownBy(() -> service.deleteAssignment(id, ACTOR))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND);
        }
    }
}
