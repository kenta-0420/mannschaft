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
import com.mannschaft.app.payment.constant.ContentGateType;
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
     * <p>{@link AnnouncementFeedVisibility#CUSTOM} が付与されたお知らせフィード自身のゲートを評価する。
     * FE が {@code checkAccess("ANNOUNCEMENT", announcementFeedId)} を呼ぶのと同じキー体系:
     * {@code content_payment_gates(content_type="ANNOUNCEMENT", content_id=announcementFeedId)}。</p>
     *
     * <p><strong>なぜ sourceType/sourceId でなく "ANNOUNCEMENT"+id か</strong>:
     * {@code ContentGateType.ANNOUNCEMENT = "ANNOUNCEMENT"} が正式値。
     * {@code sourceType} は Java enum 名（例: "BLOG_POST"）であり {@link ContentGateType} 体系と別物のため
     * ゲートキーとして使用すると常にゲートなし（accessible=true）になるバグを招く。</p>
     *
     * <p><strong>fail-closed 設計</strong>: {@code viewerUserId}/{@code row.id()} が {@code null} なら拒否。</p>
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
        // content_type="ANNOUNCEMENT" で announcement_feeds.id をゲートキーとして評価
        return paymentGateService.checkAccess(ContentGateType.ANNOUNCEMENT, row.id(), viewerUserId)
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
