package com.mannschaft.app.chat.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.common.visibility.StandardVisibility;
import com.mannschaft.app.common.visibility.UserScopeRoleSnapshot;
import com.mannschaft.app.common.visibility.VisibilityMetrics;
import com.mannschaft.app.visibility.service.VisibilityTemplateEvaluator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/**
 * F00 Phase B（積み残し根治） — {@link ReferenceType#CHAT_MESSAGE} 用
 * {@link AbstractContentVisibilityResolver} 実装。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §4.6 / §12.3.1 /
 * §15 D-13/D-14/D-16。</p>
 *
 * <h3>導入の経緯（実機 E2E 障害の根治）</h3>
 * <p>{@code CHAT_MESSAGE} は {@link com.mannschaft.app.common.visibility.NotificationSourceTypeMapper}
 * に登録されているが、対応 Resolver が長らく未実装だった。その結果
 * {@code ContentVisibilityChecker.canView(CHAT_MESSAGE, …)} が fail-closed（未対応 type）で
 * <b>常に false</b> を返し、問い合わせ通知（{@code INQUIRY_RECEIVED}）を含む CHAT_MESSAGE 起点の
 * 通知が全受信者で deny され作成されなかった。設計書 §12.3.1 が規定する「所属固定機能の最小実装
 * Resolver」を本クラスで実装し、可視性判定を迂回・緩和せずに根治する。</p>
 *
 * <h3>判定モデル（§12.3.1 最小実装＋検分是正 2026-07-11 の粒度）</h3>
 * <ol>
 *   <li><b>問い合わせチャンネル（{@code isInquiryChannel=true}）</b> — {@link StandardVisibility#CUSTOM}
 *       経路で {@link #evaluateCustom} が「当該スコープの <b>ADMIN / DEPUTY_ADMIN のみ</b>」に絞る。
 *       通知受信者集合（{@code InquiryChatEventListener} の
 *       {@code findAdminUserIdsByTeamId} + {@code findAllDeputyAdminUserIdsByTeamId}）と完全一致させる。
 *       閾値は {@code hasRoleOrAbove(scope, "DEPUTY_ADMIN")}
 *       — {@code RolePriority} 上 {@code isAtLeast("DEPUTY_ADMIN", "ADMIN")} は false（3&le;2 不成立）で
 *       {@code ADMINS_AND_ABOVE} 経路では DEPUTY_ADMIN が deny されるため、閾値 "DEPUTY_ADMIN"
 *       （SYSTEM_ADMIN/ADMIN/DEPUTY_ADMIN 包含・MEMBER 以下除外）を明示採用する。</li>
 *   <li><b>PRIVATE チャンネル（{@code isPrivate=true} かつ非 inquiry）</b> — Projection が scope を
 *       {@code null} に落とすため <b>fail-closed（SystemAdmin 以外不可視）</b>。チャンネルメンバーシップ
 *       ベース判定への昇格は将来の別軍議（{@link ChatMessageVisibilityProjection} の危険注記参照）。</li>
 *   <li><b>公開チャンネル</b> — {@link StandardVisibility#SCOPE_AFFILIATED}＝チャンネルのスコープ
 *       （TEAM/ORGANIZATION）への直接所属者全員。基底の {@code snapshot.isMemberOf(scope)} に委譲。</li>
 * </ol>
 *
 * <p><b>危険注記</b>: 本 Resolver の canView を<b>チャット本文可視の単独ゲートに使用してはならない</b>。
 * チャンネルメンバーシップ（DM・PRIVATE の招待制）を判定に含まないため、本文読取・WS 購読の認可は
 * {@code ChatChannelMemberRepository} 直参照（{@code ChatChannelSubscriptionInterceptor} 等）を正とする。
 * 本経路は通知発行ガード・コルクボード参照解決など二次参照の可視性確認用途に限る。</p>
 *
 * <p>DM・村ロビー等 team/org スコープを持たないチャンネルのメッセージは Projection の
 * {@code scopeType/scopeId} が {@code null} となり fail-closed（不可視）になる（最小実装の範囲外・後日別軍議）。</p>
 *
 * <p>SystemAdmin 高速パス（§15 D-13）／status 軸ガード（§7.5）／監査ログ（§11.4）／
 * メトリクス（§9.4）はすべて {@link AbstractContentVisibilityResolver} に委譲される。</p>
 */
@Component
public class ChatMessageVisibilityResolver
        extends AbstractContentVisibilityResolver<StandardVisibility, ChatMessageVisibilityProjection> {

    /**
     * 問い合わせチャンネルの閲覧許可閾値。SYSTEM_ADMIN(1)/ADMIN(2)/DEPUTY_ADMIN(3) を包含し
     * MEMBER(4) 以下を除外する（通知受信者集合と完全一致）。
     */
    private static final String INQUIRY_MIN_ROLE = "DEPUTY_ADMIN";

    private final ChatMessageRepository messageRepository;

    public ChatMessageVisibilityResolver(
            ChatMessageRepository messageRepository,
            MembershipBatchQueryService membershipBatchQueryService,
            VisibilityTemplateEvaluator templateEvaluator,
            VisibilityMetrics visibilityMetrics,
            @Autowired(required = false) FollowBatchService followBatchService,
            @Autowired(required = false) AuditLogService auditLogService) {
        super(membershipBatchQueryService, templateEvaluator, visibilityMetrics,
                followBatchService, auditLogService);
        this.messageRepository = messageRepository;
    }

    @Override
    public ReferenceType referenceType() {
        return ReferenceType.CHAT_MESSAGE;
    }

    @Override
    protected List<ChatMessageVisibilityProjection> loadProjections(Collection<Long> ids) {
        return messageRepository.findVisibilityProjectionsByIdIn(ids);
    }

    @Override
    protected StandardVisibility toStandard(StandardVisibility visibility) {
        // Projection が SCOPE_AFFILIATED / CUSTOM を直接返すため恒等写像。
        return visibility;
    }

    /**
     * 問い合わせチャンネル（CUSTOM 経路）の判定: 当該スコープの ADMIN / DEPUTY_ADMIN のみ許可。
     *
     * <p>fail-closed: 未認証・scope 欠落・inquiry 以外の CUSTOM 到達（設計上発生しない）は false。</p>
     */
    @Override
    protected boolean evaluateCustom(
            ChatMessageVisibilityProjection row,
            Long viewerUserId,
            UserScopeRoleSnapshot snapshot) {
        if (viewerUserId == null || row == null || !row.inquiry()) {
            return false;
        }
        if (row.scopeType() == null || row.scopeId() == null) {
            return false;
        }
        return snapshot.hasRoleOrAbove(
                new ScopeKey(row.scopeType(), row.scopeId()), INQUIRY_MIN_ROLE);
    }

    @Override
    protected String customSubType(ChatMessageVisibilityProjection row) {
        // CUSTOM は問い合わせチャンネル単一種別。メトリクスタグを安定させる。
        return "INQUIRY_ADMINS_ONLY";
    }
}
