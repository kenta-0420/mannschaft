package com.mannschaft.app.circulation.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.repository.CirculationDocumentRepository;
import com.mannschaft.app.circulation.repository.CirculationRecipientRepository;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * F00 Phase C — {@link ReferenceType#CIRCULATION_DOCUMENT} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §5.1.4 / §7.5 / §11.6 /
 * §12.3.1 / §15 D-13/D-14/D-16。</p>
 *
 * <p>回覧板は機能側に visibility 概念を持たない（§12.3.1）。代わりに {@code circulation_recipients}
 * テーブルへの登録 = 配信対象として確定済 という ACL 固定の意味論を持つため、軍議で確定した案 A により
 * {@link StandardVisibility#CUSTOM} 経路で recipients 判定を行う。</p>
 *
 * <p><strong>判定アルゴリズム（{@link #evaluateCustom}）</strong>:</p>
 * <ol>
 *   <li>{@code viewerUserId} が {@code null}（未認証）→ {@code false}（fail-closed）</li>
 *   <li>作成者本人（{@code authorUserId}）→ {@code true}（自分の作った文書は recipients 未登録でも可視）</li>
 *   <li>{@code circulation_recipients} に該当 (documentId, viewerUserId) が存在 → {@code true}</li>
 *   <li>それ以外 → {@code false}</li>
 * </ol>
 *
 * <p>SystemAdmin 高速パス（§15 D-13）／親 ORG 連鎖ガード（§11.6）／status 軸ガード（§7.5）／監査ログ
 * （§11.4）／メトリクス（§9.4）はすべて {@link AbstractContentVisibilityResolver} に委譲される。</p>
 *
 * <p>機能側 visibility enum を持たないため、総称型 {@code <V>} には {@link StandardVisibility} 自体を
 * 割り当て、{@link #toStandard} は恒等写像（Projection が {@link StandardVisibility#CUSTOM} 固定で返す）
 * となる。</p>
 *
 * <p><strong>N+1 注意</strong>: {@link #evaluateCustom} は判定対象 1 件ごとに 1 SQL（recipients 存在判定）
 * を発行する。Phase C のスコープでは許容し、バルク化は後日 {@link MembershipBatchQueryService} 拡張で
 * 対応する。</p>
 */
@Component
public class CirculationDocumentVisibilityResolver
        extends AbstractContentVisibilityResolver<StandardVisibility, CirculationDocumentVisibilityProjection> {

    private final CirculationDocumentRepository documentRepository;
    private final CirculationRecipientRepository recipientRepository;

    public CirculationDocumentVisibilityResolver(
            CirculationDocumentRepository documentRepository,
            CirculationRecipientRepository recipientRepository,
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.documentRepository = documentRepository;
        this.recipientRepository = recipientRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.CIRCULATION_DOCUMENT;
    }

    @Override
    protected List<CirculationDocumentVisibilityProjection> loadProjections(Collection<Long> ids) {
        return documentRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(StandardVisibility visibility) {
        // Projection が常に CUSTOM を返すため恒等写像。
        return visibility;
    }

    @Override
    protected ContentStatus toContentStatus(CirculationDocumentVisibilityProjection row) {
        return mapStatus(row.status());
    }

    /**
     * 案 A — recipients ベースの ACL 判定（CUSTOM 30 行以下、§5.1.4 規約遵守）。
     *
     * <p>fail-closed: 未認証 / projection 不整合 / 双方 null → false。</p>
     */
    @Override
    protected boolean evaluateCustom(
            CirculationDocumentVisibilityProjection row,
            Long viewerUserId,
            UserScopeRoleSnapshot snapshot) {
        if (viewerUserId == null || row == null || row.id() == null) {
            return false;
        }
        // 作成者本人は recipients 未登録でも閲覧可（自分の起票した文書を見られないのは不合理）。
        if (Objects.equals(viewerUserId, row.authorUserId())) {
            return true;
        }
        return recipientRepository.existsByDocumentIdAndUserId(row.id(), viewerUserId);
    }

    @Override
    protected String customSubType(CirculationDocumentVisibilityProjection row) {
        // CIRCULATION_DOCUMENT は CUSTOM 単一種別。メトリクスタグを安定させる。
        return "RECIPIENT_ACL";
    }

    /**
     * {@link CirculationStatus} → {@link ContentStatus} の写像。
     *
     * <ul>
     *   <li>{@code DRAFT} → {@link ContentStatus#DRAFT}（作成者と SystemAdmin のみ可視）</li>
     *   <li>{@code ACTIVE} / {@code COMPLETED} → {@link ContentStatus#PUBLISHED}（visibility 評価へ）</li>
     *   <li>{@code CANCELLED} → {@link ContentStatus#ARCHIVED}（SystemAdmin のみ可視）</li>
     * </ul>
     *
     * <p>論理削除は射影段階の {@code @SQLRestriction("deleted_at IS NULL")} で除外されるため、
     * {@link ContentStatus#DELETED} への写像は不要。</p>
     */
    private static ContentStatus mapStatus(CirculationStatus status) {
        if (status == null) {
            // fail-closed: status 不明は DRAFT 扱い（基底側で SystemAdmin/作成者のみ可視）
            return ContentStatus.DRAFT;
        }
        return switch (status) {
            case DRAFT -> ContentStatus.DRAFT;
            case ACTIVE, COMPLETED -> ContentStatus.PUBLISHED;
            case CANCELLED -> ContentStatus.ARCHIVED;
        };
    }
}
