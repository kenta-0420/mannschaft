package com.mannschaft.app.role.service;

import com.mannschaft.app.role.dto.InvitableScopesResponse;
import com.mannschaft.app.role.dto.MembershipInviteRequest;
import com.mannschaft.app.role.dto.MembershipInviteResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * チャットからチーム/組織への承諾型招待サービス（F04.12・骨格）。
 *
 * <p><strong>骨格（スタブ）:</strong> 本クラスのメソッドは未実装であり
 * {@link UnsupportedOperationException} を投げる。実装は /出陣 で行う。</p>
 *
 * <p>設計書: docs/features/F04.12_chat_membership_invite.md §4・§5。</p>
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MembershipInviteService {

    /**
     * DM 相手を指定スコープへ招待する（宛先付きトークン発行 ＋ 招待カード投稿）。
     *
     * <p>発行フローは role ドメイン（トークン発行）を主トランザクションとし、
     * chat ドメイン（カード投稿）は主 tx の外で明示的に連結する（原則5・Saga 補償）。</p>
     *
     * @param channelId   DM チャンネル ID（{@code channel_type = 'DM'} であること）
     * @param request     招待リクエスト（scopeType/scopeId/roleId/expiresInDays）
     * @param actorUserId 実行ユーザー ID
     * @return 発行結果
     */
    @Transactional
    public MembershipInviteResponse issueMembershipInvite(
            Long channelId, MembershipInviteRequest request, Long actorUserId) {
        throw new UnsupportedOperationException("未実装: /出陣で実装");
    }

    /**
     * 招待を取消す（{@code revoked_at} を立てカードを取消済み表示・audit に CANCELLED 記録）。
     *
     * @param channelId   DM チャンネル ID
     * @param tokenId     取消対象の招待トークン ID
     * @param actorUserId 実行ユーザー ID（発行者 or 対象スコープ ADMIN）
     */
    @Transactional
    public void revokeMembershipInvite(Long channelId, Long tokenId, Long actorUserId) {
        throw new UnsupportedOperationException("未実装: /出陣で実装");
    }

    /**
     * 自分が招待発行できる（ADMIN/DEPUTY_ADMIN の {@code INVITE_MEMBERS} 権限を持つ）スコープ一覧を返す。
     *
     * <p>認可の真実源は BE（設計書 B-6）。管理スコープ 0 件でもエラーにせず空を返す。</p>
     *
     * @param userId 実行ユーザー ID
     * @return 招待発行可能スコープ一覧
     */
    public InvitableScopesResponse getInvitableScopes(Long userId) {
        throw new UnsupportedOperationException("未実装: /出陣で実装");
    }
}
