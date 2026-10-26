package com.mannschaft.app.payment;

import com.mannschaft.app.payment.escrow.EscrowSourceKind;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * F22.1 市（Market）統一決済 R1: 手数料パターンの解決（{@code fee_policy_assignments} → {@code fee_policies}）。
 *
 * <p>DB 参照（割当・マスタ照会）を本クラスに閉じることで、{@link PaymentFeeCalculator} を純粋関数（状態・外部依存
 * なし）に保つ（設計書 02 §3.5 / §3.5.1）。{@code escrow_transactions} 起票（charge/与信/サブスク加入）時に
 * source_kind＋任意 sub_key から適用パターンを解決し、解決した {@code policy_key} を焼き付け（遡及防止）に用いる。</p>
 *
 * <p><b>解決順序（設計書 02 §3.5.1）:</b></p>
 * <ol>
 *   <li>{@code (source_kind, sub_key)} 完全一致のアクティブ割当 → 参照先 policy が有効なら採用。</li>
 *   <li>{@code (source_kind, sub_key=NULL)} の source_kind 既定割当 → 参照先 policy が有効なら採用。</li>
 *   <li>{@code DEFAULT} パターン（解決終端・削除不可）。</li>
 * </ol>
 *
 * <p>割当・参照先 policy はいずれも {@code enabled=TRUE}・{@code deleted_at IS NULL} を満たすもののみ有効扱い。
 * 文字列直比較（{@code recruitment_category} 値 等）は本 Resolver に一箇所集約し散在させない。</p>
 *
 * <p><b>DEFAULT が DB に未シードの異常時</b>でも、組み込み既定（{@link FeePolicy#defaultPolicy()}＝率5%＋固定0）へ
 * フォールバックして {@code NullPointerException}（症状を隠した連鎖故障）を起こさず後方互換を保つ。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeePolicyResolver {

    private final FeePolicyRepository feePolicyRepository;
    private final FeePolicyAssignmentRepository assignmentRepository;

    /**
     * source_kind（＋任意 sub_key）から適用する手数料パターンを解決する（設計書 02 §3.5.1）。
     *
     * @param sourceKind 解決キー（escrow の出所種別・非 null）
     * @param subKey     細分キー（助っ人＝{@code recruitment_category} 値 等。{@code null}＝source_kind 既定を引く）
     * @return 解決した手数料パターン（必ず非 null・終端は DEFAULT）
     */
    @Transactional(readOnly = true)
    public FeePolicy resolve(EscrowSourceKind sourceKind, String subKey) {
        String sourceKindName = sourceKind.name();

        // ① (source_kind, sub_key) 完全一致（sub_key が非 null のときのみ照会）。
        if (subKey != null && !subKey.isBlank()) {
            Optional<FeePolicy> exact = assignmentRepository
                    .findBySourceKindAndSubKeyAndEnabledTrueAndDeletedAtIsNull(sourceKindName, subKey)
                    .flatMap(a -> activePolicy(a.getPolicyKey()));
            if (exact.isPresent()) {
                return exact.get();
            }
        }

        // ② (source_kind, sub_key=NULL) source_kind 既定。
        Optional<FeePolicy> bySourceKind = assignmentRepository
                .findBySourceKindAndSubKeyIsNullAndEnabledTrueAndDeletedAtIsNull(sourceKindName)
                .flatMap(a -> activePolicy(a.getPolicyKey()));
        if (bySourceKind.isPresent()) {
            return bySourceKind.get();
        }

        // ③ DEFAULT（解決終端・削除不可）。DB 未シードの異常時は組み込み既定へフォールバック（症状を隠さず警告）。
        return activePolicy(FeePolicy.DEFAULT_KEY).orElseGet(() -> {
            log.warn("fee_policies に DEFAULT が見つかりません（未シード/無効）。組み込み既定（率5%＋固定0）へフォールバック: "
                    + "sourceKind={}, subKey={}", sourceKindName, subKey);
            return FeePolicy.defaultPolicy();
        });
    }

    /**
     * policy_key で有効（enabled=TRUE）なマスタ行を引き、値オブジェクトへ変換する。無効/不在は empty。
     *
     * @param policyKey 解決対象の自然キー
     * @return 有効パターンの値オブジェクト（無効/不在なら empty）
     */
    private Optional<FeePolicy> activePolicy(String policyKey) {
        return feePolicyRepository.findByPolicyKeyAndEnabledTrue(policyKey)
                .map(FeePolicyEntity::toFeePolicy);
    }
}
