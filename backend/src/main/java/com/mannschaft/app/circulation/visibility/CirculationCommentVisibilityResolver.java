package com.mannschaft.app.circulation.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.circulation.repository.CirculationCommentRepository;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.RecursionDepthCounter;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase C — {@link ReferenceType#COMMENT} 用 {@link AbstractContentVisibilityResolver} 実装。
 * Phase C スコープは CirculationComment 限定。他 Comment 実体は Phase D 以降の汎用化軍議対象。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §15 D-16。</p>
 *
 * <p>コメントは機能側に visibility 概念を持たない。親文書 ({@link ReferenceType#CIRCULATION_DOCUMENT})
 * の可視性に従属し、{@link #evaluateCustom} 内で
 * {@link ContentVisibilityChecker#canView(ReferenceType, Long, Long)} を呼び出して委譲する（§D-16）。</p>
 *
 * <p><strong>循環依存対策</strong>: {@link ContentVisibilityChecker} はすべての Resolver Bean を
 * constructor で集約するため、本 Resolver が同 Checker を直接 inject すると循環依存が発生する。
 * {@code @Lazy} により proxy 経由の遅延解決でこれを回避する。</p>
 *
 * <p><strong>再帰深度対策（§D-16）</strong>: {@link RecursionDepthCounter}（@RequestScope）を
 * {@code evaluateCustom} 呼び出し前後で enter/exit し、深度上限 3 超過時に
 * {@link IllegalStateException} を伝播させる。</p>
 */
@Component
public class CirculationCommentVisibilityResolver
        extends AbstractContentVisibilityResolver<StandardVisibility, CirculationCommentVisibilityProjection> {

    private final CirculationCommentRepository commentRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;
    private final RecursionDepthCounter recursionDepthCounter;

    public CirculationCommentVisibilityResolver(
            CirculationCommentRepository commentRepository,
            @Lazy ContentVisibilityChecker contentVisibilityChecker,
            RecursionDepthCounter recursionDepthCounter,
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.commentRepository = commentRepository;
        this.contentVisibilityChecker = contentVisibilityChecker;
        this.recursionDepthCounter = recursionDepthCounter;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.COMMENT;
    }

    @Override
    protected ContentVisibilityChecker checker() {
        return contentVisibilityChecker;
    }

    @Override
    protected List<CirculationCommentVisibilityProjection> loadProjections(Collection<Long> ids) {
        return commentRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(StandardVisibility visibility) {
        // Projection が常に CUSTOM を返すため恒等写像。
        return visibility;
    }

    @Override
    protected ContentStatus toContentStatus(CirculationCommentVisibilityProjection row) {
        // コメントは論理削除のみ（@SQLRestriction で除外済）。残存行は常に PUBLISHED。
        return ContentStatus.PUBLISHED;
    }

    /**
     * 親文書 ({@link ReferenceType#CIRCULATION_DOCUMENT}) への可視性委譲。
     *
     * <p>fail-closed: viewerUserId が null または documentId が null → false。</p>
     * <p>§D-16 再帰深度ガード: {@link RecursionDepthCounter#enter()} 後に checker 呼び出し。</p>
     */
    @Override
    protected boolean evaluateCustom(
            CirculationCommentVisibilityProjection row,
            Long viewerUserId,
            UserScopeRoleSnapshot snapshot) {
        if (viewerUserId == null || row == null || row.documentId() == null) {
            return false;
        }
        recursionDepthCounter.enter();
        try {
            return contentVisibilityChecker.canView(
                    ReferenceType.CIRCULATION_DOCUMENT, row.documentId(), viewerUserId);
        } finally {
            recursionDepthCounter.exit();
        }
    }

    @Override
    protected String customSubType(CirculationCommentVisibilityProjection row) {
        return "PARENT_DELEGATION";
    }
}
