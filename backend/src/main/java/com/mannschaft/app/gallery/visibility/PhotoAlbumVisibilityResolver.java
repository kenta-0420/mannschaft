package com.mannschaft.app.gallery.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.AlbumVisibilityMapper;
import com.mannschaft.app.gallery.AlbumVisibility;
import com.mannschaft.app.gallery.repository.PhotoAlbumRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase D-β — {@link ReferenceType#PHOTO_ALBUM} 用 {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md}
 * §4.6 / §5.2 / §11.6 / §15 D-13/D-14/D-16。</p>
 *
 * <p><strong>機能側 visibility との対応</strong>（§5.2）:</p>
 * <ul>
 *   <li>{@link AlbumVisibility#ALL_MEMBERS} → {@link StandardVisibility#SCOPE_AFFILIATED}</li>
 *   <li>{@link AlbumVisibility#SUPPORTERS_AND_ABOVE} → {@link StandardVisibility#SUPPORTERS_AND_ABOVE}</li>
 *   <li>{@link AlbumVisibility#ADMIN_ONLY} → {@link StandardVisibility#ADMINS_AND_ABOVE}</li>
 * </ul>
 *
 * <p><strong>status × visibility 合成</strong>:
 * PhotoAlbum には status 概念が無いため {@code toContentStatus()} はオーバーライドせず、
 * 基底クラスの既定実装（常に {@link com.mannschaft.app.common.visibility.ContentStatus#PUBLISHED}）を使用する。</p>
 *
 * <p><strong>CUSTOM 経路</strong>:
 * {@link AlbumVisibility} に CUSTOM 値が存在しないため {@code evaluateCustom()} は不要。
 * 基底クラスの既定実装（{@code false}）がフォールバックとして機能する。</p>
 *
 * <p><strong>制約</strong>（§15 D-14 / D-16）:</p>
 * <ul>
 *   <li>{@code AccessControlService} の 12 メソッドに一切触れない（D-14）。</li>
 *   <li>他 Resolver を inject せず、必要であれば
 *       {@link com.mannschaft.app.common.visibility.ContentVisibilityChecker} を通じて参照する（D-16）。</li>
 *   <li>本クラスには {@code @Transactional} を付与してはならない（{@code AbstractContentVisibilityResolver}
 *       の final テンプレートメソッドが CGLIB プロキシで NPE を起こすため）。</li>
 * </ul>
 */
@Component
public class PhotoAlbumVisibilityResolver
        extends AbstractContentVisibilityResolver<AlbumVisibility, PhotoAlbumVisibilityProjection> {

    private final PhotoAlbumRepository photoAlbumRepository;

    public PhotoAlbumVisibilityResolver(
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService,
            PhotoAlbumRepository photoAlbumRepository) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.photoAlbumRepository = photoAlbumRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.PHOTO_ALBUM;
    }

    @Override
    protected List<PhotoAlbumVisibilityProjection> loadProjections(Collection<Long> ids) {
        return photoAlbumRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(AlbumVisibility visibility) {
        return AlbumVisibilityMapper.toStandard(visibility);
    }
}
