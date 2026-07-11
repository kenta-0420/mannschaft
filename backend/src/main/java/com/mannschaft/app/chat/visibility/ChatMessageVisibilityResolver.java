package com.mannschaft.app.chat.visibility;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.common.visibility.AbstractContentVisibilityResolver;
import com.mannschaft.app.common.visibility.FollowBatchService;
import com.mannschaft.app.common.visibility.MembershipBatchQueryService;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.common.visibility.StandardVisibility;
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
 * Resolver（実質 SCOPE_AFFILIATED 固定）」を本クラスで実装し、可視性判定を迂回・緩和せずに根治する。</p>
 *
 * <h3>判定モデル（§12.3.1 最小実装）</h3>
 * <p>チャットは機能側に visibility 概念を持たないため、Projection は常に
 * {@link StandardVisibility#SCOPE_AFFILIATED} を返す。可視範囲は「メッセージが属するチャンネルの
 * スコープ（TEAM/ORGANIZATION）への直接所属者全員」。判定は基底の
 * {@code visibleByVisibility} の {@code SCOPE_AFFILIATED} 分岐＝
 * {@code snapshot.isMemberOf(scope)} に委譲される。チーム ADMIN / DEPUTY_ADMIN も当該スコープの
 * ロール保有者＝所属者であるため閲覧可となり、問い合わせ通知の受信者（チーム管理者）が正しく許可される。</p>
 *
 * <p>DM・村ロビー等 team/org スコープを持たないチャンネルのメッセージは Projection の
 * {@code scopeType/scopeId} が {@code null} となり、基底の {@code SCOPE_AFFILIATED} 判定で
 * scope が null → fail-closed（不可視）になる（最小実装の範囲外・後日別軍議）。</p>
 *
 * <p>SystemAdmin 高速パス（§15 D-13）／status 軸ガード（§7.5）／監査ログ（§11.4）／
 * メトリクス（§9.4）はすべて {@link AbstractContentVisibilityResolver} に委譲される。</p>
 */
@Component
public class ChatMessageVisibilityResolver
        extends AbstractContentVisibilityResolver<StandardVisibility, ChatMessageVisibilityProjection> {

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
        // Projection が常に SCOPE_AFFILIATED を返すため恒等写像。
        return visibility;
    }
}
