package com.mannschaft.app.payment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.payment.PaymentErrorCode;
import com.mannschaft.app.payment.constant.ContentGateType;
import com.mannschaft.app.payment.dto.GateCheckResponse;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import lombok.RequiredArgsConstructor;
import com.mannschaft.app.payment.spi.ContentGateTarget;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** コンテンツの実存・可視性・課金を一つの認可境界で合成する。 */
@Service
@RequiredArgsConstructor
public class ContentGateAccessService {
    private final ContentGateResolverRegistry resolverRegistry;
    private final ContentVisibilityChecker visibilityChecker;
    private final AccessControlService accessControlService;
    private final PaymentGateService paymentGateService;

    public GateCheckResponse check(String contentType, Long contentId, Long viewerUserId) {
        if (contentType == null || contentId == null
                || (!ContentGateType.POST.equals(contentType)
                    && !ContentGateType.ANNOUNCEMENT.equals(contentType))) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND);
        }
        ContentGateTarget target;
        try {
            target = resolverRegistry.resolveForAccess(contentType, contentId)
                    .filter(ContentGateTarget::hasExactlyOneScope)
                    .orElseThrow(() -> new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND, e);
        }
        ReferenceType referenceType = ContentGateType.POST.equals(contentType)
                ? ReferenceType.BLOG_POST : ReferenceType.ANNOUNCEMENT_FEED;
        final boolean visible;
        try {
            visible = visibilityChecker.canView(referenceType, contentId, viewerUserId);
        } catch (RuntimeException e) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND, e);
        }
        if (!visible) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND);
        }
        if (isStrictAdmin(viewerUserId, target)) {
            return new GateCheckResponse(true, false, java.util.List.of());
        }
        final GateCheckResponse response;
        try {
            response = paymentGateService.checkAccess(contentType, contentId, viewerUserId, target);
        } catch (RuntimeException e) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND, e);
        }
        if (response == null) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND);
        }
        if (response.isTitleHidden()) {
            throw new BusinessException(PaymentErrorCode.CONTENT_NOT_FOUND);
        }
        return response;
    }

    /**
     * 指定種別で課金ゲートが付いたコンテンツのうち、閲覧者が<b>支払い条件を満たさない</b>ものの ID 集合を一括で返す
     * （グローバル検索 F04.6 ARTICLE の除外条件。CMP-261116-0722）。
     *
     * <p>判定は記事を開くときと同じ正準判定 {@link PaymentGateService#checkAccessBatch(String, java.util.Collection, Long, Map)}
     * （実コンテンツの scope を束縛した版）に委ね、新しい規則は作らない。すなわち:</p>
     * <ul>
     *   <li>ゲートの支払い項目は<b>すべて</b>満たす必要がある（AND）。</li>
     *   <li>満たすとは、閲覧者本人が受益者（{@code member_payments.user_id}）の {@code PAID} 記録が、未削除の支払い項目に対して
     *       {@code valid_until IS NULL OR valid_until >= CURRENT_DATE - grace_period_days}（DB 側の日付で判定）で存在すること。
     *       払い手（{@code payer_user_id}）が誰かは問わず、他人が受益者の支払いでは満たさない。{@code valid_from} は見ない。</li>
     *   <li>支払い項目の消失・論理削除・DONATION 型、記事と別スコープの項目、記事の実在／scope が確認できないものは、
     *       誰が何を支払っても満たさない（fail-closed）。判定中の障害も満たさない側に倒れる。</li>
     *   <li>{@code is_title_hidden=TRUE} の未充足は満たさない。{@code is_title_hidden=FALSE} の未充足（鍵付きタイトル表示）も、
     *       本メソッドでは「満たさない」として返す（本文由来の snippet を返す検索ではタイトルだけの表示をしない）。</li>
     * </ul>
     * <p>{@link #check} と異なり SYSTEM_ADMIN / スコープ ADMIN のバイパスは<b>行わない</b>（検索は権限で母集団を広げない）。
     * クエリは ID 数に比例せず、ゲート ID・記事の scope・ゲート・支払い項目・支払い状況の定数本。</p>
     *
     * @param viewerUserId 閲覧者（受益者キー。null なら何も満たさない）
     * @param contentType  コンテンツ種別（{@link ContentGateType}）
     * @return 閲覧者が条件を満たさないゲート付きコンテンツ ID（無ければ空）
     */
    public Set<Long> findUnsatisfiedGatedContentIds(Long viewerUserId, String contentType) {
        Set<Long> gated = paymentGateService.findGatedContentIds(contentType);
        if (gated.isEmpty()) {
            return Set.of();
        }
        Map<Long, ContentGateTarget> targets = resolverRegistry.resolveForAccessBatch(contentType, gated);
        Map<Long, GateCheckResponse> decisions =
                paymentGateService.checkAccessBatch(contentType, gated, viewerUserId, targets);
        Set<Long> unsatisfied = new LinkedHashSet<>();
        for (Long id : gated) {
            GateCheckResponse decision = decisions.get(id);
            if (decision == null || !decision.isAccessible() || decision.isTitleHidden()) {
                unsatisfied.add(id);
            }
        }
        return Set.copyOf(unsatisfied);
    }

    private boolean isStrictAdmin(Long userId, ContentGateTarget target) {
        if (userId == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        Long scopeId = target.teamId() != null ? target.teamId() : target.organizationId();
        String scopeType = target.teamId() != null ? "TEAM" : "ORGANIZATION";
        return "ADMIN".equals(accessControlService.getRoleName(userId, scopeId, scopeType));
    }
}
