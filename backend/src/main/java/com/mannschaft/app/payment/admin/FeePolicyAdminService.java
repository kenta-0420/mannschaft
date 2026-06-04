package com.mannschaft.app.payment.admin;

import com.mannschaft.app.auth.AuditEventType;
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
import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F22.1 市（Market）統一決済 R2: 手数料パターン（{@code fee_policies}）・割当（{@code fee_policy_assignments}）の
 * システム管理者向け CRUD（設計書 02 §11）。
 *
 * <p>本サービスは <b>マスタ（policy）と割当（assignment）の管理のみ</b>を担い、R1 のコアロジック
 * （{@code PaymentFeeCalculator} / {@code FeePolicyResolver}）には触れない（CRUD から参照されるだけ・別セッション非干渉）。</p>
 *
 * <p><b>業務制約（§11）:</b></p>
 * <ul>
 *   <li>率 ∈ [0,1)（Bean Validation 前段）かつ「率・固定額がともに 0」禁止 → {@code FEE_POLICY_INVALID_RATE}（422）。</li>
 *   <li>{@code DEFAULT} の削除/無効化禁止（解決終端の最後の砦）→ {@code FEE_POLICY_DEFAULT_IMMUTABLE}（409）。率改定は許容。</li>
 *   <li>POST で既存 policy_key → {@code FEE_POLICY_ALREADY_EXISTS}（409・PUT へ誘導）。</li>
 *   <li>割当の参照先 policy 不在 → {@code FEE_POLICY_NOT_FOUND}（404）/ 無効 → {@code FEE_POLICY_ASSIGNMENT_POLICY_DISABLED}（422）。</li>
 *   <li>割当 UNIQUE(source_kind, sub_key, organization_id) 違反 → {@code FEE_POLICY_ASSIGNMENT_DUPLICATE}（409）。</li>
 * </ul>
 *
 * <p>割当の変更は <b>新規課金にのみ反映</b>される。既存の escrow は焼き付け済み {@code fee_policy_key} の率で不変（遡及防止・R1）。</p>
 */
@Service
@RequiredArgsConstructor
public class FeePolicyAdminService {

    private final FeePolicyRepository feePolicyRepository;
    private final FeePolicyAssignmentRepository assignmentRepository;
    private final AuditLogService auditLogService;

    // ───────────────────────── fee_policies CRUD ─────────────────────────

    /**
     * 手数料パターン一覧（{@code enabled=false} 含む全件・policy_key 昇順）を返す（§11 #1）。
     *
     * @return 全パターン（割当数付き）
     */
    @Transactional(readOnly = true)
    public List<FeePolicyResponse> listPolicies() {
        return feePolicyRepository.findAllByOrderByPolicyKeyAsc()
                .stream()
                .map(e -> FeePolicyResponse.from(e, assignmentRepository.countByPolicyKeyAndDeletedAtIsNull(e.getPolicyKey())))
                .toList();
    }

    /**
     * 手数料パターン単件を返す（§11 #2）。
     *
     * @param policyKey 自然キー
     * @return パターン詳細
     * @throws BusinessException 不在のとき {@code FEE_POLICY_NOT_FOUND}（404）
     */
    @Transactional(readOnly = true)
    public FeePolicyResponse getPolicy(String policyKey) {
        FeePolicyEntity entity = requirePolicy(policyKey);
        return FeePolicyResponse.from(entity, assignmentRepository.countByPolicyKeyAndDeletedAtIsNull(policyKey));
    }

    /**
     * 手数料パターンを新規作成する（§11 #3）。既存キーは {@code FEE_POLICY_ALREADY_EXISTS}（409・PUT へ誘導）。
     *
     * @param request     作成内容
     * @param actorUserId 操作者（監査ログ）
     * @return 作成したパターン
     */
    @Transactional
    public FeePolicyResponse createPolicy(FeePolicyUpsertRequest request, Long actorUserId) {
        String policyKey = request.getPolicyKey();
        validateFeeAmounts(request.getPercentRate(), request.getFlatFeeMinor());
        if (feePolicyRepository.existsById(policyKey)) {
            throw new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_ALREADY_EXISTS);
        }
        FeePolicyEntity entity = FeePolicyEntity.builder()
                .policyKey(policyKey)
                .displayName(request.getDisplayName())
                .percentRate(request.getPercentRate())
                .flatFeeMinor(request.getFlatFeeMinor())
                .enabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled())
                .description(request.getDescription())
                .build();
        FeePolicyResponse response = FeePolicyResponse.from(feePolicyRepository.save(entity), 0L);
        recordAudit(AuditEventType.FEE_POLICY_CREATED, actorUserId, "FEE_POLICY", policyKey);
        return response;
    }

    /**
     * 手数料パターンを更新する（§11 #4）。改定は新規徴収のみ反映（遡及しない）。
     * {@code DEFAULT} は率・固定額の改定は可だが、本 PUT での無効化（enabled=false）は禁止する。
     *
     * @param policyKey   対象キー（パス変数が正）
     * @param request     更新内容
     * @param actorUserId 操作者（監査ログ）
     * @return 更新したパターン
     */
    @Transactional
    public FeePolicyResponse updatePolicy(String policyKey, FeePolicyUpsertRequest request, Long actorUserId) {
        FeePolicyEntity entity = requirePolicy(policyKey);
        validateFeeAmounts(request.getPercentRate(), request.getFlatFeeMinor());

        boolean nextEnabled = request.getEnabled() == null ? Boolean.TRUE.equals(entity.getEnabled()) : request.getEnabled();
        // DEFAULT は解決フォールバックの終端。無効化（enabled=false 化）は禁止（率改定は許容）。
        if (FeePolicy.DEFAULT_KEY.equals(policyKey) && !nextEnabled) {
            throw new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_DEFAULT_IMMUTABLE);
        }

        entity.setDisplayName(request.getDisplayName());
        entity.setPercentRate(request.getPercentRate());
        entity.setFlatFeeMinor(request.getFlatFeeMinor());
        entity.setEnabled(nextEnabled);
        entity.setDescription(request.getDescription());
        FeePolicyResponse response = FeePolicyResponse.from(
                feePolicyRepository.save(entity), assignmentRepository.countByPolicyKeyAndDeletedAtIsNull(policyKey));
        recordAudit(AuditEventType.FEE_POLICY_UPDATED, actorUserId, "FEE_POLICY", policyKey);
        return response;
    }

    /**
     * 手数料パターンを無効化する（{@code enabled=false}・論理無効化・§11 #5）。{@code DEFAULT} は不可。
     *
     * @param policyKey   対象キー
     * @param actorUserId 操作者（監査ログ）
     */
    @Transactional
    public void disablePolicy(String policyKey, Long actorUserId) {
        // DEFAULT は削除/無効化禁止（解決終端の最後の砦が消えると全課金が破綻する）。
        if (FeePolicy.DEFAULT_KEY.equals(policyKey)) {
            throw new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_DEFAULT_IMMUTABLE);
        }
        FeePolicyEntity entity = requirePolicy(policyKey);
        entity.setEnabled(Boolean.FALSE);
        feePolicyRepository.save(entity);
        recordAudit(AuditEventType.FEE_POLICY_DISABLED, actorUserId, "FEE_POLICY", policyKey);
    }

    // ──────────────────── fee_policy_assignments CRUD ────────────────────

    /**
     * 手数料パターン割当一覧（論理削除を除外・作成順）を返す（§11 #6）。
     *
     * @return 割当一覧
     */
    @Transactional(readOnly = true)
    public List<FeePolicyAssignmentResponse> listAssignments() {
        return assignmentRepository.findByDeletedAtIsNullOrderByCreatedAtAsc()
                .stream()
                .map(FeePolicyAssignmentResponse::from)
                .toList();
    }

    /**
     * 手数料パターン割当を作成する（§11 #7）。
     *
     * <p>参照先 policy が不在なら {@code FEE_POLICY_NOT_FOUND}（404）/ 無効なら {@code FEE_POLICY_ASSIGNMENT_POLICY_DISABLED}（422）。
     * 同条件のアクティブ割当が既存なら {@code FEE_POLICY_ASSIGNMENT_DUPLICATE}（409）。論理削除済みの同条件行があれば
     * DB の UNIQUE 制約（deleted_at 非考慮）違反を避けるため <b>復活</b>させる（INSERT による 500 を未然に防ぐ・症状を隠さない）。</p>
     *
     * @param request     作成内容
     * @param actorUserId 操作者（監査ログ）
     * @return 作成（または復活）した割当
     */
    @Transactional
    public FeePolicyAssignmentResponse createAssignment(FeePolicyAssignmentCreateRequest request, Long actorUserId) {
        String sourceKind = normalizeSourceKind(request.getSourceKind());
        String subKey = blankToNull(request.getSubKey());
        String policyKey = request.getPolicyKey();

        // 参照先 policy の存在・有効性を検証（不在=404 / 無効=422・症状を隠さず設定時点で拒否）。
        FeePolicyEntity policy = feePolicyRepository.findByPolicyKey(policyKey)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_NOT_FOUND));
        if (!Boolean.TRUE.equals(policy.getEnabled())) {
            throw new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_ASSIGNMENT_POLICY_DISABLED);
        }

        // 同条件（source_kind, sub_key・R2 では organization_id=NULL 固定）の行を論理削除込みで照会。
        Optional<FeePolicyAssignmentEntity> existing = (subKey == null)
                ? assignmentRepository.findBySourceKindAndSubKeyIsNull(sourceKind)
                : assignmentRepository.findBySourceKindAndSubKey(sourceKind, subKey);

        FeePolicyAssignmentEntity entity;
        if (existing.isPresent()) {
            FeePolicyAssignmentEntity current = existing.get();
            if (current.getDeletedAt() == null) {
                // アクティブな同条件割当が既存 → UNIQUE 違反相当として 409。
                throw new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_ASSIGNMENT_DUPLICATE);
            }
            // 論理削除済みの行を復活（DB UNIQUE は deleted_at 非考慮ゆえ INSERT 不可・復活で対応）。
            current.setPolicyKey(policyKey);
            current.setEnabled(Boolean.TRUE);
            current.setDeletedAt(null);
            entity = assignmentRepository.save(current);
        } else {
            entity = assignmentRepository.save(FeePolicyAssignmentEntity.builder()
                    .sourceKind(sourceKind)
                    .subKey(subKey)
                    .policyKey(policyKey)
                    .enabled(Boolean.TRUE)
                    .build());
        }
        FeePolicyAssignmentResponse response = FeePolicyAssignmentResponse.from(entity);
        recordAudit(AuditEventType.FEE_POLICY_ASSIGNMENT_CREATED, actorUserId, "FEE_POLICY_ASSIGNMENT",
                String.valueOf(entity.getId()));
        return response;
    }

    /**
     * 手数料パターン割当を解除する（論理削除・§11 #8）。既存課金には影響しない（焼き付け済みで不変）。
     *
     * @param id          割当 ID
     * @param actorUserId 操作者（監査ログ）
     */
    @Transactional
    public void deleteAssignment(UUID id, Long actorUserId) {
        FeePolicyAssignmentEntity entity = assignmentRepository.findById(id)
                .filter(a -> a.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.PAYMENT_RESOURCE_NOT_FOUND));
        entity.setDeletedAt(LocalDateTime.now());
        entity.setEnabled(Boolean.FALSE);
        assignmentRepository.save(entity);
        recordAudit(AuditEventType.FEE_POLICY_ASSIGNMENT_DELETED, actorUserId, "FEE_POLICY_ASSIGNMENT",
                String.valueOf(id));
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private FeePolicyEntity requirePolicy(String policyKey) {
        return feePolicyRepository.findByPolicyKey(policyKey)
                .orElseThrow(() -> new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_NOT_FOUND));
    }

    /**
     * 率・固定額の業務妥当性を検証する。率の範囲（[0,1)）は Bean Validation で前段検証済みだが、
     * 「率・固定額がともに 0（手数料ゼロ禁止）」は業務制約ゆえここで {@code FEE_POLICY_INVALID_RATE}（422）として拒否する。
     */
    private void validateFeeAmounts(BigDecimal percentRate, Long flatFeeMinor) {
        if (percentRate == null
                || percentRate.signum() < 0
                || percentRate.compareTo(BigDecimal.ONE) >= 0
                || flatFeeMinor == null
                || flatFeeMinor < 0L) {
            throw new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_INVALID_RATE);
        }
        // 率・固定額がともに 0 だと手数料ゼロ（運用上無意味・誤設定）→ 拒否。
        if (percentRate.signum() == 0 && flatFeeMinor == 0L) {
            throw new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_INVALID_RATE);
        }
    }

    /**
     * source_kind の文字列を {@link EscrowSourceKind} で妥当性検証し、正規化した名称を返す。
     * 不正値は握りつぶさず {@code FEE_POLICY_INVALID_RATE}（422・入力不正）で拒否する。
     */
    private String normalizeSourceKind(String raw) {
        try {
            return EscrowSourceKind.valueOf(raw).name();
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(ConnectPaymentErrorCode.FEE_POLICY_INVALID_RATE);
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * 手数料パターン/割当操作の監査ログを記録する。
     * key は英大文字/数字/アンダースコア（policy_key）または UUID 文字列に制約されるため JSON エスケープ不要。
     */
    private void recordAudit(AuditEventType eventType, Long actorUserId, String source, String key) {
        String metadata = String.format("{\"source\":\"%s\",\"key\":\"%s\"}", source, key);
        auditLogService.record(eventType.name(), actorUserId, null, null, null, null, null, null, metadata);
    }
}
