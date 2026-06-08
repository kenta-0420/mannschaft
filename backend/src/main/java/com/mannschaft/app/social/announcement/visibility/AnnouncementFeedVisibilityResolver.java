package com.mannschaft.app.social.announcement.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.ContentStatus;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.common.visibility.mapping.AnnouncementFeedVisibilityMapper;
import com.mannschaft.app.payment.service.PaymentGateService;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedRepository;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * お知らせウィジェットフィード（{@code announcement_feeds}）用
 * {@link com.mannschaft.app.common.visibility.ContentVisibilityResolver}（F02.6 / F08.9 P4b）。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §12.3
 * および {@code docs/features/F08.9_membership_billing_paywall/02_api_design.md} §6。</p>
 *
 * <p><strong>visibility セマンティクス</strong>:</p>
 * <ul>
 *   <li>{@link AnnouncementFeedVisibility#PUBLIC} → 全員閲覧可</li>
 *   <li>{@link AnnouncementFeedVisibility#SUPPORTERS_AND_ABOVE} → SUPPORTER 以上</li>
 *   <li>{@link AnnouncementFeedVisibility#MEMBERS_AND_ABOVE} → MEMBER 以上（SUPPORTER 除外）</li>
 *   <li>{@link AnnouncementFeedVisibility#CUSTOM} → ペイウォール判定
 *       ({@link PaymentGateService#checkAccess} 経由、受益者キー判定)</li>
 * </ul>
 *
 * <p><strong>F08.9 P4b — ペイウォール連結</strong>: {@link AnnouncementFeedVisibility#CUSTOM} の
 * フィードに対して {@link #evaluateCustom} が呼ばれる。
 * {@code contentType = feed.sourceType}（{@code "BLOG_POST"} 等）、
 * {@code contentId = feed.sourceId} で {@link PaymentGateService#checkAccess} を呼ぶ。
 * 元コンテンツ側のペイウォール設定をお知らせフィード経由のアクセスにも適用することで、
 * visibility(ロールベース) と ペイウォール の AND 判定を実現する（設計書 F08.9 02 §6）。</p>
 *
 * <p><strong>既存一覧クエリとの共存</strong>:
 * {@link com.mannschaft.app.social.announcement.AnnouncementFeedQueryRepository#findByScope}
 * は {@code visibility IN (allowedVisibilities)} で DB レベルフィルタを行うため、
 * {@code visibility = "CUSTOM"} のフィードは {@code allowedVisibilities} に {@code "CUSTOM"}
 * を含めない限り一覧に表示されない（既存一覧ロジック無影響）。
 * 本 Resolver は個別可視性判定（{@link #canView}/{@link #decide}/{@link #filterAccessible}）に使用する。</p>
 *
 * <p><strong>status 軸</strong>: {@code announcement_feeds} はステータスを持たないため
 * {@link ContentStatus#PUBLISHED} 固定（既定実装を踏襲）。</p>
 */
@Slf4j
@Component
public class AnnouncementFeedVisibilityResolver
        extends AbstractContentVisibilityResolver<AnnouncementFeedVisibility, AnnouncementFeedVisibilityProjection> {

    private final AnnouncementFeedRepository announcementFeedRepository;
    private final PaymentGateService paymentGateService;

    public AnnouncementFeedVisibilityResolver(
            AnnouncementFeedRepository announcementFeedRepository,
            PaymentGateService paymentGateService,
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.announcementFeedRepository = announcementFeedRepository;
        this.paymentGateService = paymentGateService;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.ANNOUNCEMENT_FEED;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@link AnnouncementFeedEntity} を ID 集合で一括取得し、
     * {@link AnnouncementFeedVisibilityProjection#from(AnnouncementFeedEntity)} で変換する。</p>
     */
    @Override
    protected List<AnnouncementFeedVisibilityProjection> loadProjections(Collection<Long> ids) {
        List<AnnouncementFeedEntity> entities = announcementFeedRepository.findByIdIn(ids);
        return entities.stream()
                .map(AnnouncementFeedVisibilityProjection::from)
                .toList();
    }

    @Override
    protected StandardVisibility toStandard(AnnouncementFeedVisibility v) {
        return AnnouncementFeedVisibilityMapper.toStandard(v);
    }

    /**
     * F08.9 P4b — ペイウォール解錠判定（{@link StandardVisibility#CUSTOM} 経路）。
     *
     * <p>{@link AnnouncementFeedVisibility#CUSTOM} が付与されたお知らせフィードのみここに到達する。
     * 元コンテンツ（{@code sourceType}/{@code sourceId}）の
     * {@link PaymentGateService#checkAccess} で閲覧者本人（受益者キー）の支払い状態を評価する。</p>
     *
     * <p><strong>fail-closed 設計</strong>: {@code viewerUserId} が {@code null}（未認証）、
     * {@code sourceType} や {@code sourceId} が {@code null} の場合は閲覧拒否側に倒す。</p>
     *
     * @param row          判定対象の Projection
     * @param viewerUserId 閲覧者 user_id（{@code null} 可、未認証 = fail-closed）
     * @param snapshot     メンバーシップスナップショット（本メソッドでは不使用）
     * @return ペイウォール解錠済みなら {@code true}（ゲートなし＝誰でも閲覧可を含む）
     */
    @Override
    protected boolean evaluateCustom(
            AnnouncementFeedVisibilityProjection row,
            Long viewerUserId,
            UserScopeRoleSnapshot snapshot) {
        if (viewerUserId == null || row == null || row.id() == null) {
            // 未認証またはデータ不整合 → fail-closed（漏洩より過剰遮断・03_security §4）
            return false;
        }
        if (row.sourceType() == null || row.sourceId() == null) {
            // 元コンテンツ情報不完全 → fail-closed
            log.warn("ペイウォール判定不能（sourceType/sourceId 欠落）: announcementFeedId={} → accessible=false",
                    row.id());
            return false;
        }
        return paymentGateService.checkAccess(row.sourceType(), row.sourceId(), viewerUserId)
                .isAccessible();
    }

    /**
     * {@link StandardVisibility#CUSTOM} の細分種別タグ。
     *
     * <p>メトリクス用タグとして元コンテンツ種別（{@code "BLOG_POST"} 等）を返す。
     * {@code sourceType} が {@code null} の場合は {@code "UNKNOWN"} を返す。</p>
     */
    @Override
    protected String customSubType(AnnouncementFeedVisibilityProjection row) {
        return row.sourceType() != null ? row.sourceType() : "UNKNOWN";
    }
}
