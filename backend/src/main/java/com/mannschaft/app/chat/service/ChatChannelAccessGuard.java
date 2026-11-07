package com.mannschaft.app.chat.service;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.BooleanSupplier;

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
 *
 * <h2>存在秘匿（CMP-261105-1507・docs/security/01_authorization_baseline.md §3.3.2）</h2>
 * <p>チャンネル ID・メッセージ ID を指定する EP で、<b>そのチャンネルの存在を知り得ない利用者</b>への拒否は、
 * 不在 ID への応答（{@link ChatErrorCode#CHANNEL_NOT_FOUND} / {@link ChatErrorCode#MESSAGE_NOT_FOUND}）と
 * status・{@code error.code}・{@code error.message} まで完全に一致させる。存在を知り得る利用者
 * （{@link #isAwareOfChannel}）への拒否だけを 403 とする。判定は<b>拒否経路でのみ</b>追加クエリを撃ち、
 * 許可経路のクエリ回数は変えない（{@link com.mannschaft.app.common.ScopeConcealingAccessGate} と同じ流儀）。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatChannelAccessGuard {

    private final ChatChannelMemberRepository memberRepository;
    private final AccessControlService accessControlService;

    /** F04.2 §2: DEPUTY_ADMIN にチャンネル管理・メッセージ削除を許す権限名。 */
    static final String MANAGE_CHAT_PERMISSION = "MANAGE_CHAT";

    /**
     * メンバーシップ管理種別（{@link ChannelType#isMembershipGated()}）のチャンネルについて、
     * 呼出ユーザーが当該チャンネルの現役メンバーであることを保証する。
     *
     * <p>村ロビー・イベント・大会チャットは {@code chat_channel_members} 行を持たない横断スペースであり、
     * それぞれのドメイン側の正準判定（village メンバーシップ / {@code EventScopeAccessGuard} /
     * {@code TournamentContactAccessService}）で認可される。本メソッドはその区別を
     * {@link ChannelType#isMembershipGated()} に委ねる。</p>
     *
     * @throws BusinessException 存在を知り得ない利用者なら {@link ChatErrorCode#CHANNEL_NOT_FOUND}（不在と同一）、
     *                           知り得るが未参加なら {@link ChatErrorCode#CHANNEL_ACCESS_DENIED}
     */
    public void requireChannelMembership(ChatChannelEntity channel, Long userId) {
        if (!channel.getChannelType().isMembershipGated()) {
            return;
        }
        requireMembershipRowOrConceal(channel, userId, ChatErrorCode.CHANNEL_NOT_FOUND);
    }

    /**
     * 種別を問わず、呼出ユーザーが当該チャンネルの現役メンバー（{@code chat_channel_members} 行あり）で
     * あることを保証する。拒否は存在秘匿つきで作り分ける。
     *
     * @param channel      対象チャンネル（生存確認済み）
     * @param userId       呼出ユーザー ID
     * @param notFoundCode 当該 EP の不在コード（チャンネル ID 指定なら CHANNEL_NOT_FOUND、
     *                     メッセージ ID 指定なら MESSAGE_NOT_FOUND）
     * @throws BusinessException 存在を知り得ない利用者なら {@code notFoundCode}、
     *                           知り得るが未参加なら {@link ChatErrorCode#CHANNEL_ACCESS_DENIED}
     */
    public void requireMembershipRowOrConceal(ChatChannelEntity channel, Long userId, ErrorCode notFoundCode) {
        if (userId != null && memberRepository.existsByChannelIdAndUserId(channel.getId(), userId)) {
            return;
        }
        throw concealOrForbid(channel, userId, notFoundCode, ChatErrorCode.CHANNEL_ACCESS_DENIED);
    }

    /**
     * 拒否が確定した後に、越境（存在を知り得ない）か権限不足（存在を知り得る）かを作り分けた例外を返す。
     *
     * <p>拒否経路でのみ呼ぶこと（{@link #isAwareOfChannel} は追加クエリを撃つ）。</p>
     *
     * @param channel       対象チャンネル
     * @param userId        呼出ユーザー ID
     * @param notFoundCode  不在時に投げるコードそのもの（専用コードを新設しない）
     * @param forbiddenCode 存在を知り得る利用者に返す 403 コード
     * @return 投げるべき例外
     */
    public BusinessException concealOrForbid(ChatChannelEntity channel, Long userId,
                                             ErrorCode notFoundCode, ErrorCode forbiddenCode) {
        if (isAwareOfChannel(channel, userId)) {
            return new BusinessException(forbiddenCode);
        }
        return new BusinessException(notFoundCode);
    }

    /**
     * 呼出ユーザーがチャンネルの<b>存在を知り得る</b>かを判定する（F04.2 §5「チャンネルメンバーシップの可視性」）。
     *
     * <ul>
     *   <li>当該チャンネルのメンバー（{@code chat_channel_members} 行あり）</li>
     *   <li>SYSTEM_ADMIN（全チャンネルのモデレーション権限を持つ）</li>
     *   <li>スコープチャンネルのスコープ（チーム/組織）の ADMIN / DEPUTY_ADMIN（スコープ内の全チャンネルを管理する）</li>
     *   <li>公開スコープチャンネル（{@code TEAM_PUBLIC} / {@code ORG_PUBLIC} かつ非 private）のスコープ所属者
     *       （参加を促す UI のため非メンバーにも存在が見える）</li>
     * </ul>
     * <p>上記以外（他人の DM・グループ DM、非公開チャンネルの非メンバー、他スコープの利用者、
     * 村ロビー・イベント・大会チャットの非該当者）は存在を知り得ない。</p>
     */
    public boolean isAwareOfChannel(ChatChannelEntity channel, Long userId) {
        if (userId == null) {
            return false;
        }
        if (memberRepository.existsByChannelIdAndUserId(channel.getId(), userId)) {
            return true;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        if (!channel.getChannelType().isScopeChannel()) {
            return false;
        }
        if (isScopeAdmin(channel, userId)) {
            return true;
        }
        boolean publicChannel = channel.getChannelType().isSelfJoinableScopeChannel()
                && !Boolean.TRUE.equals(channel.getIsPrivate());
        if (!publicChannel) {
            return false;
        }
        if (channel.getTeamId() != null) {
            return accessControlService.isMember(userId, channel.getTeamId(), "TEAM");
        }
        if (channel.getOrganizationId() != null) {
            return accessControlService.isMember(userId, channel.getOrganizationId(), "ORGANIZATION");
        }
        return false;
    }

    /**
     * チャンネル内の管理操作（メンバー追加・除外・ロール変更・アイコン変更）について、
     * 呼出ユーザーが当該チャンネルの OWNER または ADMIN であることを保証する。
     *
     * @param channel        対象チャンネル（生存確認済み）
     * @param operatorUserId 操作者ユーザー ID
     * @param deniedCode     存在を知り得る利用者への拒否コード（経路ごとの API 契約に合わせる）
     * @return 操作者のメンバー行
     * @throws BusinessException 存在を知り得ない利用者なら {@link ChatErrorCode#CHANNEL_NOT_FOUND}（不在と同一）、
     *                           知り得るが権限が不足する場合は {@code deniedCode}
     */
    public ChatChannelMemberEntity requireChannelManagerRole(ChatChannelEntity channel, Long operatorUserId,
                                                             ChatErrorCode deniedCode) {
        ChatChannelMemberEntity operator = memberRepository
                .findByChannelIdAndUserId(channel.getId(), operatorUserId)
                .orElseThrow(() -> concealOrForbid(channel, operatorUserId,
                        ChatErrorCode.CHANNEL_NOT_FOUND, deniedCode));
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
        if (channel.getTeamId() != null || channel.getOrganizationId() != null) {
            if (isScopeAdmin(channel, userId)) {
                return;
            }
            // 越境は不在と同一（CHANNEL_NOT_FOUND）、存在を知り得る利用者の権限不足は従来どおり COMMON_002。
            throw concealOrForbid(channel, userId, ChatErrorCode.CHANNEL_NOT_FOUND, CommonErrorCode.COMMON_002);
        }
        ChatChannelMemberEntity member = memberRepository.findByChannelIdAndUserId(channel.getId(), userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHANNEL_NOT_FOUND));
        if (member.getRole() != ChannelMemberRole.OWNER) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
    }

    /**
     * チャンネルのスコープ（チーム優先・無ければ組織）の ADMIN / DEPUTY_ADMIN かを判定する。
     */
    public boolean isScopeAdmin(ChatChannelEntity channel, Long userId) {
        if (channel.getTeamId() != null) {
            return accessControlService.isAdminOrAbove(userId, channel.getTeamId(), "TEAM");
        }
        if (channel.getOrganizationId() != null) {
            return accessControlService.isAdminOrAbove(userId, channel.getOrganizationId(), "ORGANIZATION");
        }
        return false;
    }

    /**
     * メッセージ削除の認可（F04.2 §5「メッセージ削除権限」・§2 対象ロール）を保証する。
     *
     * <p>許可主体: 投稿者本人 / SYSTEM_ADMIN / チャンネルの OWNER・ADMIN /
     * チャンネルのスコープ（チーム・組織）の ADMIN、または {@code MANAGE_CHAT} 権限を持つ DEPUTY_ADMIN。</p>
     *
     * @param channel メッセージが属するチャンネル（生存確認済み）
     * @param message 対象メッセージ
     * @param userId  操作者
     * @return 投稿者本人以外による削除（モデレーション削除）なら true
     * @throws BusinessException 存在を知り得ない利用者なら {@link ChatErrorCode#MESSAGE_NOT_FOUND}（不在と同一）、
     *                           知り得るが権限が無い場合は {@link ChatErrorCode#MESSAGE_DELETE_DENIED}
     */
    public boolean requireMessageDeletable(ChatChannelEntity channel, ChatMessageEntity message, Long userId) {
        if (userId != null && userId.equals(message.getSenderId())) {
            return false;
        }
        if (userId != null && canModerateMessages(channel, userId)) {
            return true;
        }
        throw concealOrForbid(channel, userId, ChatErrorCode.MESSAGE_NOT_FOUND, ChatErrorCode.MESSAGE_DELETE_DENIED);
    }

    /**
     * 呼出ユーザーが当該チャンネルのメッセージをモデレーション（他人のメッセージの削除）できるかを判定する。
     * 判定材料はチャンネル実体のスコープとメンバー行のみで、リクエストの申告値は使わない。
     */
    public boolean canModerateMessages(ChatChannelEntity channel, Long userId) {
        if (userId == null) {
            return false;
        }
        if (accessControlService.isSystemAdmin(userId)) {
            return true;
        }
        var member = memberRepository.findByChannelIdAndUserId(channel.getId(), userId);
        if (member.isPresent()) {
            ChannelMemberRole role = member.get().getRole();
            if (role == ChannelMemberRole.OWNER || role == ChannelMemberRole.ADMIN) {
                return true;
            }
        }
        if (channel.getTeamId() != null) {
            return accessControlService.hasAdminOrPermissionInScope(
                    userId, channel.getTeamId(), "TEAM", MANAGE_CHAT_PERMISSION);
        }
        if (channel.getOrganizationId() != null) {
            return accessControlService.hasAdminOrPermissionInScope(
                    userId, channel.getOrganizationId(), "ORGANIZATION", MANAGE_CHAT_PERMISSION);
        }
        return false;
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
     * 1 対 1 の Kabine とグループの Zimmer、および Kabine からの招待が<b>同一の判定</b>を通ることで、
     * 参加人数や経路によって相手の受信設定が無視される非対称を作らない。</p>
     *
     * <p><b>入力を真偽値・列挙で受け取る理由</b>: 判定に要るユーザー・ブロック・所属・連絡先の各情報は
     * それぞれ別ドメイン（auth / user / role / dashboard）が所有する。chat ドメインの本クラスが
     * それらのエンティティ・リポジトリを直接参照するとドメイン境界を越えるため、取得は呼び出し側
     * （{@code ChatChannelService}）が行い、本クラスは<b>判定のみ</b>を担う。
     * 受信範囲設定に応じてしか要らない 2 つの照会は {@link BooleanSupplier} で受け、
     * 不要な問い合わせが走らないようにする。</p>
     *
     * @param callerId            会話を開始するユーザー ID
     * @param receiverId          会話相手のユーザー ID
     * @param blockedByReceiver   相手が呼出ユーザーをブロックしているか
     * @param receiverSetting     相手の DM 受信範囲設定
     * @param sharesTeam          相手と共通チームに所属しているか（{@code TEAM_MEMBERS_ONLY} でのみ評価）
     * @param registeredAsContact 相手の連絡先に登録されているか（{@code CONTACTS_ONLY} でのみ評価）
     * @throws BusinessException ブロックされている場合 / 受信範囲外の場合
     */
    public void requireDmDeliverable(Long callerId, Long receiverId, boolean blockedByReceiver,
                                     DmReceiveFrom receiverSetting,
                                     BooleanSupplier sharesTeam,
                                     BooleanSupplier registeredAsContact) {
        if (blockedByReceiver) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
        if (receiverSetting == DmReceiveFrom.TEAM_MEMBERS_ONLY) {
            if (!sharesTeam.getAsBoolean()) {
                throw new BusinessException(ChatErrorCode.DM_RECEIVE_RESTRICTED);
            }
        } else if (receiverSetting == DmReceiveFrom.CONTACTS_ONLY) {
            if (!registeredAsContact.getAsBoolean()) {
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
     * @throws BusinessException 送信者本人でない場合。チャンネルの存在を知り得ない利用者なら
     *                           {@link ChatErrorCode#MESSAGE_NOT_FOUND}（不在と同一）、知り得るなら
     *                           {@link ChatErrorCode#MESSAGE_EDIT_DENIED}
     */
    public void requireMessageOwner(ChatChannelEntity channel, ChatMessageEntity message, Long userId) {
        if (userId == null || !userId.equals(message.getSenderId())) {
            throw concealOrForbid(channel, userId, ChatErrorCode.MESSAGE_NOT_FOUND, ChatErrorCode.MESSAGE_EDIT_DENIED);
        }
    }
}
