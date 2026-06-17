package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.payment.PaymentRequestStatus;
import com.mannschaft.app.payment.connect.ScopeKind;
import com.mannschaft.app.payment.entity.PaymentRequestEntity;
import com.mannschaft.app.payment.repository.PaymentRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * F10.1.1 / P1: 支払いドメインの管理者向け承認待ち集約 Query Service（read-only・組織スコープ専用）。
 *
 * <p>payment ドメインには「承認・却下」の双方向ワークフローが存在しないため、本ドメインは
 * 「承認待ち」ではなく <b>組織が発行し、まだ支払い完了していない請求</b>
 * （{@code status ∈ {SENT, VIEWED, OVERDUE}}）= 「組織が回収状況を追うべき対象」として集約する
 * （設計書 03 §3.4）。UI では「未収の請求」等に i18n する。</p>
 *
 * <p>全クエリの WHERE に {@code issuer_scope_kind = ORG AND issuer_scope_id = ?} を含めるため、
 * テナント越境（IDOR）は構造的に発生しない。承認ロジック・トランザクション・監査ログには触れない。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/03_admin_action_required_api.md §3.4 / §4.4</p>
 */
@Service
@RequiredArgsConstructor
public class PaymentAdminQueryService {

    /** 未収（まだ支払い完了していない）と見なす請求ステータス（§3.4）。 */
    private static final Set<PaymentRequestStatus> UNSETTLED_STATUSES =
            Set.of(PaymentRequestStatus.SENT, PaymentRequestStatus.VIEWED, PaymentRequestStatus.OVERDUE);

    private final PaymentRequestRepository paymentRequestRepository;
    private final NameResolverService nameResolverService;

    /**
     * 指定組織が発行した未収請求（SENT/VIEWED/OVERDUE）の件数とプレビューを返す。
     *
     * @param orgId       組織 ID（issuer_scope_id・WHERE 必須・IDOR 防止）
     * @param previewSize プレビュー件数（0 なら件数のみ）
     * @return 件数とプレビューの集計結果
     */
    @Transactional(readOnly = true)
    public PendingAggregate unsettledForOrg(Long orgId, int previewSize) {
        long count = UNSETTLED_STATUSES.stream()
                .mapToLong(s -> paymentRequestRepository
                        .countByIssuerScopeKindAndIssuerScopeIdAndStatusAndDeletedAtIsNull(
                                ScopeKind.ORG, orgId, s))
                .sum();

        if (previewSize <= 0) {
            return new PendingAggregate(count, List.of());
        }

        List<PaymentRequestEntity> preview = paymentRequestRepository
                .findByIssuerScopeKindAndIssuerScopeIdAndStatusInAndDeletedAtIsNull(
                        ScopeKind.ORG, orgId, UNSETTLED_STATUSES, PageRequest.of(0, previewSize))
                .getContent();

        Map<Long, String> names = nameResolverService.resolveUserDisplayNames(
                preview.stream().map(PaymentRequestEntity::getCreatedBy).toList());

        List<PendingAggregate.Item> items = preview.stream()
                .map(p -> new PendingAggregate.Item(
                        String.valueOf(p.getId()),
                        p.getTitle(),
                        names.getOrDefault(p.getCreatedBy(), "不明なユーザー"),
                        p.getCreatedAt()))
                .toList();

        return new PendingAggregate(count, items);
    }
}
