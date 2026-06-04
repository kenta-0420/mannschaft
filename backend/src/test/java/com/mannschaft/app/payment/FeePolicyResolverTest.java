package com.mannschaft.app.payment;

import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * F22.1 市（Market）統一決済 R1: {@link FeePolicyResolver}（手数料パターン解決）単体テスト。
 *
 * <p>解決順序（設計書 02 §3.5.1）: ① {@code (source_kind, sub_key)} 完全一致 →
 * ② {@code (source_kind, sub_key=NULL)} 既定 → ③ {@code DEFAULT}。割当・参照先 policy とも有効なもののみ。test-first。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FeePolicyResolver 単体テスト（解決順序）")
class FeePolicyResolverTest {

    @Mock private FeePolicyRepository feePolicyRepository;
    @Mock private FeePolicyAssignmentRepository assignmentRepository;
    @InjectMocks private FeePolicyResolver resolver;

    private static FeePolicyEntity policyEntity(String key, String percent, long flat) {
        return FeePolicyEntity.builder()
                .policyKey(key)
                .displayName(key)
                .percentRate(new BigDecimal(percent))
                .flatFeeMinor(flat)
                .enabled(true)
                .build();
    }

    private static FeePolicyAssignmentEntity assignment(String sourceKind, String subKey, String policyKey) {
        return FeePolicyAssignmentEntity.builder()
                .sourceKind(sourceKind)
                .subKey(subKey)
                .policyKey(policyKey)
                .enabled(true)
                .build();
    }

    @Test
    @DisplayName("① 完全一致: (RECRUITMENT, soccer)→RECRUITMENT_HELPER を解決（sub_key 別 policy・助っ人）")
    void exactMatchSubKey() {
        given(assignmentRepository.findBySourceKindAndSubKeyAndEnabledTrueAndDeletedAtIsNull("RECRUITMENT", "soccer"))
                .willReturn(Optional.of(assignment("RECRUITMENT", "soccer", "RECRUITMENT_HELPER")));
        given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("RECRUITMENT_HELPER"))
                .willReturn(Optional.of(policyEntity("RECRUITMENT_HELPER", "0.0300", 100L)));

        FeePolicy resolved = resolver.resolve(EscrowSourceKind.RECRUITMENT, "soccer");

        assertThat(resolved.policyKey()).isEqualTo("RECRUITMENT_HELPER");
        assertThat(resolved.percentRate()).isEqualByComparingTo(new BigDecimal("0.0300"));
        assertThat(resolved.flatFeeMinor()).isEqualTo(100L);
        // 完全一致が取れたので source_kind 既定は引かない。
        verify(assignmentRepository, never())
                .findBySourceKindAndSubKeyIsNullAndEnabledTrueAndDeletedAtIsNull("RECRUITMENT");
    }

    @Test
    @DisplayName("② source_kind 既定: sub_key 完全一致なし→(MEMBERSHIP, NULL) の既定割当を解決")
    void sourceKindDefault() {
        given(assignmentRepository.findBySourceKindAndSubKeyAndEnabledTrueAndDeletedAtIsNull("MEMBERSHIP", "premium"))
                .willReturn(Optional.empty());
        given(assignmentRepository.findBySourceKindAndSubKeyIsNullAndEnabledTrueAndDeletedAtIsNull("MEMBERSHIP"))
                .willReturn(Optional.of(assignment("MEMBERSHIP", null, "MEMBERSHIP_STANDARD")));
        given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("MEMBERSHIP_STANDARD"))
                .willReturn(Optional.of(policyEntity("MEMBERSHIP_STANDARD", "0.0400", 0L)));

        FeePolicy resolved = resolver.resolve(EscrowSourceKind.MEMBERSHIP, "premium");

        assertThat(resolved.policyKey()).isEqualTo("MEMBERSHIP_STANDARD");
        assertThat(resolved.percentRate()).isEqualByComparingTo(new BigDecimal("0.0400"));
    }

    @Test
    @DisplayName("② subKey=null は最初から source_kind 既定を引く（完全一致照会をしない）")
    void nullSubKeyGoesDirectToDefault() {
        given(assignmentRepository.findBySourceKindAndSubKeyIsNullAndEnabledTrueAndDeletedAtIsNull("MEMBERSHIP"))
                .willReturn(Optional.of(assignment("MEMBERSHIP", null, "MEMBERSHIP_STANDARD")));
        given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("MEMBERSHIP_STANDARD"))
                .willReturn(Optional.of(policyEntity("MEMBERSHIP_STANDARD", "0.0400", 0L)));

        FeePolicy resolved = resolver.resolve(EscrowSourceKind.MEMBERSHIP, null);

        assertThat(resolved.policyKey()).isEqualTo("MEMBERSHIP_STANDARD");
        // subKey が null のため完全一致照会（非 null sub_key 用）は呼ばない。
        verify(assignmentRepository, never())
                .findBySourceKindAndSubKeyAndEnabledTrueAndDeletedAtIsNull("MEMBERSHIP", null);
    }

    @Test
    @DisplayName("③ DEFAULT フォールバック: 割当が一切なし→DEFAULT policy を解決")
    void defaultFallback() {
        given(assignmentRepository.findBySourceKindAndSubKeyAndEnabledTrueAndDeletedAtIsNull("RECRUITMENT", "unknown"))
                .willReturn(Optional.empty());
        given(assignmentRepository.findBySourceKindAndSubKeyIsNullAndEnabledTrueAndDeletedAtIsNull("RECRUITMENT"))
                .willReturn(Optional.empty());
        given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("DEFAULT"))
                .willReturn(Optional.of(policyEntity("DEFAULT", "0.0500", 0L)));

        FeePolicy resolved = resolver.resolve(EscrowSourceKind.RECRUITMENT, "unknown");

        assertThat(resolved.policyKey()).isEqualTo("DEFAULT");
        assertThat(resolved.percentRate()).isEqualByComparingTo(new BigDecimal("0.0500"));
        assertThat(resolved.flatFeeMinor()).isEqualTo(0L);
    }

    @Test
    @DisplayName("無効 policy: 割当はあるが参照先 fee_policies.enabled=false→無効扱いで次段（DEFAULT）へ")
    void disabledPolicyTreatedAsAbsent() {
        given(assignmentRepository.findBySourceKindAndSubKeyAndEnabledTrueAndDeletedAtIsNull("RECRUITMENT", "soccer"))
                .willReturn(Optional.of(assignment("RECRUITMENT", "soccer", "DISABLED_POLICY")));
        // findByPolicyKeyAndEnabledTrue は enabled=true のみ返すため、無効 policy は empty。
        given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("DISABLED_POLICY"))
                .willReturn(Optional.empty());
        // 完全一致が無効扱いになったため source_kind 既定→DEFAULT を引く。
        given(assignmentRepository.findBySourceKindAndSubKeyIsNullAndEnabledTrueAndDeletedAtIsNull("RECRUITMENT"))
                .willReturn(Optional.empty());
        given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("DEFAULT"))
                .willReturn(Optional.of(policyEntity("DEFAULT", "0.0500", 0L)));

        FeePolicy resolved = resolver.resolve(EscrowSourceKind.RECRUITMENT, "soccer");

        assertThat(resolved.policyKey()).isEqualTo("DEFAULT");
    }

    @Test
    @DisplayName("終端 DEFAULT も不在（DB 未シード）→ 組み込み DEFAULT（率5%＋固定0）にフォールバックし NPE を出さない")
    void builtInDefaultWhenSeedMissing() {
        lenient().when(assignmentRepository
                        .findBySourceKindAndSubKeyAndEnabledTrueAndDeletedAtIsNull("RECRUITMENT", "x"))
                .thenReturn(Optional.empty());
        lenient().when(assignmentRepository
                        .findBySourceKindAndSubKeyIsNullAndEnabledTrueAndDeletedAtIsNull("RECRUITMENT"))
                .thenReturn(Optional.empty());
        given(feePolicyRepository.findByPolicyKeyAndEnabledTrue("DEFAULT")).willReturn(Optional.empty());

        FeePolicy resolved = resolver.resolve(EscrowSourceKind.RECRUITMENT, "x");

        assertThat(resolved.policyKey()).isEqualTo("DEFAULT");
        assertThat(resolved.percentRate()).isEqualByComparingTo(new BigDecimal("0.0500"));
        assertThat(resolved.flatFeeMinor()).isEqualTo(0L);
    }
}
