package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.dto.AddMemberRequest;
import com.mannschaft.app.chat.dto.ChangeRoleRequest;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.dto.InviteToZimmerRequest;
import com.mannschaft.app.chat.dto.MemberResponse;
import com.mannschaft.app.chat.dto.UpdateChannelRequest;
import com.mannschaft.app.chat.dto.UpdateInquiryChannelRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * チャンネル ID を指定するチャンネル・メンバー操作の認可を、トランザクションの外で済ませるファサード
 * （CMP-261105-1507。型は {@code AuthzTxFacadeRegistryArchTest} の登録表に従う）。
 *
 * <p>流れは「Controller → 本クラス（tx なし・{@link ChatChannelAccessGuard} で認可と存在秘匿）→
 * tx 本体（{@link ChatChannelService} / {@link ChatMemberService}・認可なし）」である。
 * 認可が読む他ドメイン（membership / role）の行を、チャットの書き込みトランザクションに巻き込まない
 * （CLAUDE.md DB 設計の原則 #5・番人 D-3T）。</p>
 *
 * <p>本クラスに {@code @Transactional} を付けてはならない。チャンネルの取得
 * （{@link ChatChannelService#findChannelOrThrow}）は chat ドメインだけを読む短い読み取りであり、
 * 不在なら認可の前に {@link ChatErrorCode#CHANNEL_NOT_FOUND} を返す（越境の拒否と同一応答）。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatChannelFacade {

    private final ChatChannelService channelService;
    private final ChatMemberService memberService;
    private final ChatChannelAccessGuard channelAccessGuard;

    /**
     * チャンネル詳細を取得する。メンバーシップ管理種別ではチャンネルメンバーであることを要求する
     * （DM の相手・関係グラフの列挙を防ぐ）。
     */
    public ChannelResponse getChannel(Long channelId, Long userId) {
        channelAccessGuard.requireChannelMembership(channelService.findChannelOrThrow(channelId), userId);
        return channelService.getChannel(channelId, userId);
    }

    /** チャンネルを更新する（スコープ ADMIN 以上、DM / GROUP_DM はチャンネル OWNER）。 */
    public ChannelResponse updateChannel(Long channelId, UpdateChannelRequest request, Long userId) {
        requireChannelAdmin(channelId, userId);
        return channelService.updateChannel(channelId, request, userId);
    }

    /** チャンネルを削除する（論理削除。権限は更新と同じ）。 */
    public void deleteChannel(Long channelId, Long userId) {
        requireChannelAdmin(channelId, userId);
        channelService.deleteChannel(channelId, userId);
    }

    /** チャンネルをアーカイブする（権限は更新と同じ）。 */
    public ChannelResponse archiveChannel(Long channelId, Long userId) {
        requireChannelAdmin(channelId, userId);
        return channelService.archiveChannel(channelId, userId);
    }

    /** チャンネルのアーカイブを解除する（権限は更新と同じ）。 */
    public ChannelResponse unarchiveChannel(Long channelId, Long userId) {
        requireChannelAdmin(channelId, userId);
        return channelService.unarchiveChannel(channelId, userId);
    }

    /** DM をグループ DM に変換する（権限は更新と同じ。DM はチャンネル OWNER のみ）。 */
    public ChannelResponse convertToGroup(Long channelId, Long callerId) {
        requireChannelAdmin(channelId, callerId);
        return channelService.convertToGroup(channelId, callerId);
    }

    /**
     * Kabine から Zimmer へ招待する。当事者（チャンネルメンバー）確認を、種別（DM か否か）の判定より先に行う
     * （種別の差で他人のチャンネルの実在が漏れないため）。
     */
    public ChannelResponse inviteToZimmer(Long channelId, Long callerId, InviteToZimmerRequest request) {
        channelAccessGuard.requireMembershipRowOrConceal(
                channelService.findChannelOrThrow(channelId), callerId, ChatErrorCode.CHANNEL_NOT_FOUND);
        return channelService.inviteToZimmer(channelId, callerId, request);
    }

    /** F10.7: 問い合わせチャンネル設定を更新する（当該チームの ADMIN / DEPUTY_ADMIN、または SYSTEM_ADMIN）。 */
    public ChannelResponse updateInquiryChannel(Long channelId, UpdateInquiryChannelRequest request, Long userId) {
        channelAccessGuard.requireInquiryChannelAdmin(channelService.findChannelOrThrow(channelId), userId);
        return channelService.updateInquiryChannel(channelId, request, userId);
    }

    /** チャンネルにメンバーを追加する（チャンネルの OWNER / ADMIN のみ）。 */
    public List<MemberResponse> addMembers(Long channelId, Long operatorUserId, AddMemberRequest request) {
        channelAccessGuard.requireChannelManagerRole(
                channelService.findChannelOrThrow(channelId), operatorUserId, ChatErrorCode.CHANNEL_ACCESS_DENIED);
        return memberService.addMembers(channelId, operatorUserId, request);
    }

    /**
     * チャンネルからメンバーを除外する。他人の除外はチャンネルの OWNER / ADMIN のみ、
     * 自分自身の退出は当該チャンネルのメンバーであることのみを要する。
     */
    public void removeMember(Long channelId, Long userId, Long operatorUserId) {
        ChatChannelEntity channel = channelService.findChannelOrThrow(channelId);
        if (!operatorUserId.equals(userId)) {
            channelAccessGuard.requireChannelManagerRole(channel, operatorUserId, ChatErrorCode.CHANNEL_ACCESS_DENIED);
        } else {
            channelAccessGuard.requireMembershipRowOrConceal(channel, operatorUserId, ChatErrorCode.CHANNEL_NOT_FOUND);
        }
        memberService.removeMember(channelId, userId, operatorUserId);
    }

    /**
     * チャンネルに自分で参加する。招待制（DM / GROUP_DM / 非公開）は不可、公開チャンネルはスコープの所属者に限る。
     * スコープ在籍の行ロックつき再確認は tx 本体が行う。
     */
    public MemberResponse joinChannel(Long channelId, Long userId) {
        channelAccessGuard.requireSelfJoinable(channelService.findChannelOrThrow(channelId), userId);
        return memberService.joinChannel(channelId, userId);
    }

    /** メンバーのロールを変更する（チャンネルの OWNER / ADMIN のみ。権限昇格の防止）。 */
    public MemberResponse changeRole(Long channelId, Long targetUserId, ChangeRoleRequest request,
                                     Long operatorUserId) {
        channelAccessGuard.requireChannelManagerRole(
                channelService.findChannelOrThrow(channelId), operatorUserId, ChatErrorCode.CHANNEL_ACCESS_DENIED);
        return memberService.changeRole(channelId, targetUserId, request, operatorUserId);
    }

    private void requireChannelAdmin(Long channelId, Long userId) {
        channelAccessGuard.requireChannelAdminAccess(channelService.findChannelOrThrow(channelId), userId);
    }
}
