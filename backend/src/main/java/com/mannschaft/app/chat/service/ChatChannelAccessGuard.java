package com.mannschaft.app.chat.service;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.user.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * チャットドメインの認可判定を一元化するガード。
 *
 * <p>チャンネル・メンバー・メッセージ・添付の各サービスが行う「誰がこの操作をしてよいか」の判定を
 * 本クラスに集約する。判定はすべて<b>対象エンティティを取得したうえで、そのエンティティ由来のスコープ</b>
 * （{@code chat_channels.team_id} / {@code organization_id} / {@code chat_channel_members} の行）に基づく。
 * リクエストが申告したスコープ識別子は判定材料に用いない。</p>
 *
 * <p>本クラスはリポジトリと {@link AccessControlService} のみに依存し、チャットの業務サービスには
 * 依存しない。循環依存を作らずに全経路が同一の判定を通ることを保証するための構成である。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatChannelAccessGuard {

    private final ChatChannelMemberRepository memberRepository;
    private final AccessControlService accessControlService;
    private final UserBlockRepository userBlockRepository;
    private final UserRoleRepository userRoleRepository;
    private final ChatContactFolderItemRepository chatContactFolderItemRepository;

    /**
     * メンバーシップ管理種別（{@link ChannelType#isMembershipGated()}）のチャンネルについて、
     * 呼出ユーザーが当該チャンネルの現役メンバーであることを保証する。
     *
     * <p>村ロビー・イベント・大会チャットは {@code chat_channel_members} 行を持たない横断スペースであり、
     * それぞれのドメイン側の正準判定（village メンバーシップ / {@code EventScopeAccessGuard} /
     * {@code TournamentContactAccessService}）で認可される。本メソッドはその区別を
     * {@link ChannelType#isMembershipGated()} に委ねる。</p>
     *
     * @throws BusinessException メンバーでない場合（{@link ChatErrorCode#CHANNEL_ACCESS_DENIED}）
     */
    public void requireChannelMembership(ChatChannelEntity channel, Long userId) {
        if (!channel.getChannelType().isMembershipGated()) {
            return;
        }
        requireMembershipRow(channel.getId(), userId);
    }

    /**
     * 種別を問わず、呼出ユーザーが当該チャンネルの現役メンバーであることを保証する。
     *
     * <p>DM からの Zimmer 招待のように「メンバーシップ行の存在そのものが操作の前提」となる経路で用いる。</p>
     *
     * @throws BusinessException メンバーでない場合（{@link ChatErrorCode#CHANNEL_ACCESS_DENIED}）
     */
    public void requireChannelMember(Long channelId, Long userId) {
        requireMembershipRow(channelId, userId);
    }

    /**
     * チャンネル内の管理操作（メンバー追加・除外・ロール変更・アイコン変更）について、
     * 呼出ユーザーが当該チャンネルの OWNER または ADMIN であることを保証する。
     *
     * @param channelId  対象チャンネル ID
     * @param operatorUserId 操作者ユーザー ID
     * @param deniedCode 拒否時に用いるエラーコード（経路ごとの API 契約に合わせる）
     * @return 操作者のメンバー行
     * @throws BusinessException メンバーでない、または権限が不足する場合
     */
    public ChatChannelMemberEntity requireChannelManagerRole(Long channelId, Long operatorUserId,
                                                             ChatErrorCode deniedCode) {
        ChatChannelMemberEntity operator = memberRepository
                .findByChannelIdAndUserId(channelId, operatorUserId)
                .orElseThrow(() -> new BusinessException(deniedCode));
        ChannelMemberRole role = operator.getRole();
        if (role != ChannelMemberRole.OWNER && role != ChannelMemberRole.ADMIN) {
            throw new BusinessException(deniedCode);
        }
        return operator;
    }

    /**
     * チャンネル管理操作（更新・削除・アーカイブ・グループ変換）の認可を保証する。
     * チーム/組織チャンネルは当該スコープの ADMIN 以上、DM / GROUP_DM はチャンネル OWNER のみを許可する。
     */
    public void requireChannelAdminAccess(ChatChannelEntity channel, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (channel.getTeamId() != null) {
            accessControlService.checkAdminOrAbove(userId, channel.getTeamId(), "TEAM");
            return;
        }
        if (channel.getOrganizationId() != null) {
            accessControlService.checkAdminOrAbove(userId, channel.getOrganizationId(), "ORGANIZATION");
            return;
        }
        ChatChannelMemberEntity member = memberRepository.findByChannelIdAndUserId(channel.getId(), userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED));
        if (member.getRole() != ChannelMemberRole.OWNER) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
    }

    /**
     * チャンネル作成時に、作成者が<b>申告されたスコープ（チーム / 組織）の所属者</b>であることを保証する。
     *
     * <p>チーム/組織チャンネルはそのスコープの内部資産であり、スコープ外の利用者が作成できる余地を残さない。
     * 非公開チャンネル（{@code *_PRIVATE} または {@code isPrivate=true}）はスコープの ADMIN 以上に限定する。
     * DM / GROUP_DM はスコープを持たないため本判定の対象外であり、別途 {@link #requireDmDeliverable} で
     * 相手側の受信可否を保証する。</p>
     *
     * @param channelType    作成しようとするチャンネル種別
     * @param teamId         リクエストのチーム ID（null 可）
     * @param organizationId リクエストの組織 ID（null 可）
     * @param isPrivate      非公開チャンネルとして作成するか
     * @param createdBy      作成者ユーザー ID
     * @throws BusinessException スコープ非所属・権限不足の場合
     */
    public void requireChannelCreationScope(ChannelType channelType, Long teamId, Long organizationId,
                                            boolean isPrivate, Long createdBy) {
        if (!channelType.isScopeChannel()) {
            return;
        }
        boolean adminRequired = isPrivate || !channelType.isSelfJoinableScopeChannel();
        if (teamId != null) {
            if (adminRequired) {
                accessControlService.checkAdminOrAbove(createdBy, teamId, "TEAM");
            } else {
                accessControlService.checkMembership(createdBy, teamId, "TEAM");
            }
            return;
        }
        if (organizationId != null) {
            if (adminRequired) {
                accessControlService.checkAdminOrAbove(createdBy, organizationId, "ORGANIZATION");
            } else {
                accessControlService.checkMembership(createdBy, organizationId, "ORGANIZATION");
            }
            return;
        }
        // スコープ種別なのにスコープ識別子が無い場合は所属を検証できない。fail-closed で拒否する。
        throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
    }

    /**
     * 会話（Kabine / Zimmer）の相手側が、呼出ユーザーからの DM を受け取る設定であることを保証する。
     *
     * <p>相手のブロック設定と DM 受信範囲設定（{@link DmReceiveFrom}）の双方を検証する。
     * 1 対 1 の Kabine とグループの Zimmer が<b>同一の判定</b>を通ることで、
     * 参加人数によって相手の受信設定が無視される非対称を作らない。</p>
     *
     * @param callerId 会話を開始するユーザー ID
     * @param receiver 会話相手のユーザー
     * @throws BusinessException ブロックされている場合 / 受信範囲外の場合
     */
    public void requireDmDeliverable(Long callerId, UserEntity receiver) {
        Long receiverId = receiver.getId();
        if (userBlockRepository.existsByBlockerIdAndBlockedId(receiverId, callerId)) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
        DmReceiveFrom setting = receiver.getDmReceiveFrom();
        if (setting == DmReceiveFrom.TEAM_MEMBERS_ONLY) {
            if (!userRoleRepository.existsSharedTeam(callerId, receiverId)) {
                throw new BusinessException(ChatErrorCode.DM_RECEIVE_RESTRICTED);
            }
        } else if (setting == DmReceiveFrom.CONTACTS_ONLY) {
            if (!chatContactFolderItemRepository.existsByFolderOwnerAndItemTypeAndItemId(
                    receiverId, FolderItemType.CONTACT, callerId)) {
                throw new BusinessException(ChatErrorCode.DM_RECEIVE_RESTRICTED);
            }
        }
    }

    /**
     * メッセージの編集・削除について、呼出ユーザーが<b>当該メッセージの送信者本人</b>であることを保証する。
     *
     * <p>判定は引数で渡された channelId 等ではなく、取得済みメッセージエンティティの
     * {@code senderId} に基づく。</p>
     *
     * @throws BusinessException 送信者本人でない場合（{@link ChatErrorCode#MESSAGE_EDIT_DENIED}）
     */
    public void requireMessageOwner(ChatMessageEntity message, Long userId) {
        if (userId == null || !userId.equals(message.getSenderId())) {
            throw new BusinessException(ChatErrorCode.MESSAGE_EDIT_DENIED);
        }
    }

    private void requireMembershipRow(Long channelId, Long userId) {
        if (userId == null || !memberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
    }
}
