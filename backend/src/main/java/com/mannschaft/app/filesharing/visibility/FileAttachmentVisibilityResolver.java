package com.mannschaft.app.filesharing.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.filesharing.FileScopeType;
import com.mannschaft.app.filesharing.repository.SharedFileRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase D-β — {@link ReferenceType#FILE_ATTACHMENT} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §5.1 / §7.5 / §11.6 / §15 D-13/D-14/D-16。</p>
 *
 * <p><strong>SharedFile の visibility 設計</strong>:
 * SharedFile 自体には visibility/status フィールドがない。
 * 親フォルダ（{@code SharedFolderEntity}）の {@link FileScopeType} から
 * {@link StandardVisibility} に一意にマッピングする（CUSTOM なし）。</p>
 *
 * <p><strong>スコープ → StandardVisibility マッピング</strong>（設計書 §5.2）:</p>
 * <ul>
 *   <li>{@link FileScopeType#TEAM} → {@link StandardVisibility#SCOPE_AFFILIATED}
 *       — チームメンバーのみ可視</li>
 *   <li>{@link FileScopeType#ORGANIZATION} → {@link StandardVisibility#ORGANIZATION_WIDE}
 *       — 組織メンバー全員可視</li>
 *   <li>{@link FileScopeType#PERSONAL} → {@link StandardVisibility#PRIVATE}
 *       — フォルダ所有者（{@code shared_folders.user_id}）のみ可視</li>
 * </ul>
 *
 * <p><strong>status 概念なし</strong>:
 * SharedFile には status がないため {@link #toContentStatus} は常に
 * {@link com.mannschaft.app.common.visibility.ContentStatus#PUBLISHED} を返す。
 * これにより DRAFT / SCHEDULED / ARCHIVED / DELETED のガードは一切かからない。</p>
 *
 * <p><strong>制約</strong>（§15 D-14 / D-16）:</p>
 * <ul>
 *   <li>{@code AccessControlService} の 12 メソッドに一切触れない（D-14）。</li>
 *   <li>他 Resolver を inject せず、必要であれば
 *       {@link com.mannschaft.app.common.visibility.ContentVisibilityChecker} を通じて参照する（D-16）。</li>
 *   <li>本クラスには {@code @Transactional} を付与してはならない
 *       （{@code AbstractContentVisibilityResolver} の final テンプレートメソッドが
 *       CGLIB プロキシで NPE を起こすため。{@code VisibilityArchitectureTest} で機械的に検出される）。</li>
 * </ul>
 */
@Component
public class FileAttachmentVisibilityResolver
        extends AbstractContentVisibilityResolver<FileScopeType, FileAttachmentVisibilityProjection> {

    private final SharedFileRepository sharedFileRepository;

    public FileAttachmentVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            SharedFileRepository sharedFileRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.sharedFileRepository = sharedFileRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.FILE_ATTACHMENT;
    }

    /**
     * {@inheritDoc}
     *
     * <p>JPQL クロスジョイン形式で shared_files と shared_folders を結合し、
     * 親フォルダのスコープ情報を 1 SQL で取得する。</p>
     */
    @Override
    protected List<FileAttachmentVisibilityProjection> loadProjections(Collection<Long> ids) {
        return sharedFileRepository.findVisibilityProjectionsByIdIn(ids);
    }

    /**
     * {@inheritDoc}
     *
     * <p>FileScopeType → StandardVisibility の一意マッピング。
     * CUSTOM 経路は存在しない（SharedFile に機能独自セマンティクスなし）。</p>
     */
    @Override
    protected StandardVisibility toStandard(FileScopeType visibility) {
        return switch (visibility) {
            // 挙動不変・名称正準化（W3）: SCOPE_AFFILIATED = isMemberOf = 旧 MEMBERS_ONLY と同一判定。
            case TEAM -> StandardVisibility.SCOPE_AFFILIATED;
            // F08.7.1 / 04: 大会・ディビジョンは主催組織の可視性に集約（§6）。
            case ORGANIZATION, TOURNAMENT, TOURNAMENT_DIVISION -> StandardVisibility.ORGANIZATION_WIDE;
            case PERSONAL -> StandardVisibility.PRIVATE;
        };
    }
}
