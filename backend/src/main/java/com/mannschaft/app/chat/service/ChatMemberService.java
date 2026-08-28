package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.AddMemberRequest;
import com.mannschaft.app.chat.dto.ChangeRoleRequest;
import com.mannschaft.app.chat.dto.ChannelSettingsRequest;
import com.mannschaft.app.chat.dto.MemberResponse;
import com.mannschaft.app.chat.dto.UpdateMyChannelSettingsRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * チャンネルメンバーサービス。メンバーの追加・除外・ロール変更・個人設定を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatMemberService {

    private final ChatChannelMemberRepository memberRepository;
    private final ChatChannelService channelService;
    private final ChatMapper chatMapper;
    private final ChatChannelEventPublisher eventPublisher;
    private final AccessControlService accessControlService;
    private final ChatChannelAccessGuard channelAccessGuard;

    /**
     * チャンネルのメンバー一覧を取得する。
     *
     * @param channelId チャンネルID
     * @return メンバーレスポンスリスト
     */
    public List<MemberResponse> listMembers(Long channelId) {
        channelService.findChannelOrThrow(channelId);
        List<ChatChannelMemberEntity> members = memberRepository.findByChannelIdOrderByJoinedAtAsc(channelId);
        return chatMapper.toMemberResponseList(members);
    }

    /**
     * チャンネルにメンバーを追加する。
     *
     * @param channelId       チャンネルID
     * @param operatorUserId  操作者ユーザーID（認可チェック用）
     * @param request         追加リクエスト
     * @return 追加されたメンバーレスポンスリスト
     */
    @Transactional
    public List<MemberResponse> addMembers(Long channelId, Long operatorUserId, AddMemberRequest request) {
        channelService.findChannelOrThrow(channelId);
        // 操作者が当該チャンネルの OWNER / ADMIN であることを保証する（一般 MEMBER によるメンバー追加を禁止）。
        channelAccessGuard.requireChannelManagerRole(
                channelId, operatorUserId, ChatErrorCode.CHANNEL_ACCESS_DENIED);

        List<ChatChannelMemberEntity> added = new java.util.ArrayList<>();
        for (Long userId : request.getUserIds()) {
            if (memberRepository.existsByChannelIdAndUserId(channelId, userId)) {
                continue;
            }
            ChatChannelMemberEntity member = ChatChannelMemberEntity.builder()
                    .channelId(channelId)
                    .userId(userId)
                    .role(ChannelMemberRole.MEMBER)
                    .build();
            added.add(memberRepository.save(member));
        }

        log.info("メンバー追加完了: channelId={}, addedCount={}", channelId, added.size());
        return chatMapper.toMemberResponseList(added);
    }

    /**
     * チャンネルからメンバーを除外する。
     *
     * @param channelId      チャンネルID
     * @param userId         除外するユーザーID
     * @param operatorUserId 操作者ユーザーID（認可チェック用）
     */
    @Transactional
    public void removeMember(Long channelId, Long userId, Long operatorUserId) {
        // 他人を除外する場合は OWNER / ADMIN のみ許可する（一般 MEMBER によるキックを禁止）。
        // 自分自身の退出は当該チャンネルのメンバーであることのみを要する。
        if (!operatorUserId.equals(userId)) {
            channelAccessGuard.requireChannelManagerRole(
                    channelId, operatorUserId, ChatErrorCode.CHANNEL_ACCESS_DENIED);
        } else {
            channelAccessGuard.requireChannelMember(channelId, operatorUserId);
        }
        ChatChannelMemberEntity member = findMemberOrThrow(channelId, userId);
        if (member.getRole() == ChannelMemberRole.OWNER) {
            throw new BusinessException(ChatErrorCode.OWNER_CANNOT_LEAVE);
        }
        memberRepository.deleteByChannelIdAndUserId(channelId, userId);
        log.info("メンバー除外完了: channelId={}, userId={}", channelId, userId);
        // F04.2.1 §3.10.1: kick されたメンバーにタブ自動クローズを通知
        eventPublisher.publishMemberKicked(channelId, userId);
    }

    /**
     * チャンネルに自分で参加する。
     *
     * <p><b>認可根治 Wave 6</b>: {@code channelType}・{@code isPrivate}・スコープ所属に基づき
     * 自己参加の可否を判定する。</p>
     *
     * <p>規則:</p>
     * <ul>
     *   <li><b>{@code DM} / {@code GROUP_DM} / 非公開チャンネル（{@code *_PRIVATE} または
     *       {@code isPrivate=true}）</b> — 自己参加は一切不可（招待制）。
     *       参加者追加は {@link #addMembers} の OWNER/ADMIN 経路のみ。</li>
     *   <li><b>{@code TEAM_PUBLIC} / {@code ORG_PUBLIC}</b> — <b>当該チーム/組織のメンバーであること</b>を
     *       要求する（「公開」はスコープ内に対する公開であり、全世界への公開ではない）。</li>
     *   <li><b>村ロビー・イベント・大会チャット</b> — {@code chat_channel_members} をアクセス判定に
     *       用いない種別のため従来どおり（各ドメイン側で認可される）。</li>
     * </ul>
     *
     * @param channelId チャンネルID
     * @param userId    参加するユーザーID
     * @return 参加したメンバーレスポンス
     * @throws BusinessException 自己参加が許されないチャンネルの場合（{@link ChatErrorCode#CHANNEL_ACCESS_DENIED}）、
     *                           またはスコープ非メンバーの場合（{@code COMMON_002}）
     */
    @Transactional
    public MemberResponse joinChannel(Long channelId, Long userId) {
        ChatChannelEntity channel = channelService.findChannelOrThrow(channelId);
        checkSelfJoinAllowed(channel, userId);

        if (memberRepository.existsByChannelIdAndUserId(channelId, userId)) {
            throw new BusinessException(ChatErrorCode.ALREADY_MEMBER);
        }

        ChatChannelMemberEntity member = ChatChannelMemberEntity.builder()
                .channelId(channelId)
                .userId(userId)
                .role(ChannelMemberRole.MEMBER)
                .build();

        ChatChannelMemberEntity saved = memberRepository.save(member);
        log.info("チャンネル参加完了: channelId={}, userId={}", channelId, userId);
        return chatMapper.toMemberResponse(saved);
    }

    /**
     * メンバーのロールを変更する。
     *
     * <p>認可根治 Wave 1 束2: 手本 {@link #removeMember(Long, Long, Long)} と同型で、
     * 操作者が当該チャンネルの OWNER/ADMIN であることを確認してから変更を許可する
     * （権限昇格の防止）。</p>
     *
     * @param channelId      チャンネルID
     * @param targetUserId   対象ユーザーID
     * @param request        ロール変更リクエスト
     * @param operatorUserId 操作者ユーザーID（認可チェック用）
     * @return 更新されたメンバーレスポンス
     */
    @Transactional
    public MemberResponse changeRole(Long channelId, Long targetUserId, ChangeRoleRequest request, Long operatorUserId) {
        // 操作者が当該チャンネルの OWNER / ADMIN であることを保証する
        //（一般 MEMBER による権限昇格・他人のロール変更を禁止）。
        channelAccessGuard.requireChannelManagerRole(
                channelId, operatorUserId, ChatErrorCode.CHANNEL_ACCESS_DENIED);

        ChatChannelMemberEntity member = findMemberOrThrow(channelId, targetUserId);
        ChannelMemberRole newRole = ChannelMemberRole.valueOf(request.getRole());
        member.changeRole(newRole);
        ChatChannelMemberEntity saved = memberRepository.save(member);
        log.info("ロール変更完了: channelId={}, userId={}, newRole={}, operatorUserId={}",
                channelId, targetUserId, newRole, operatorUserId);
        return chatMapper.toMemberResponse(saved);
    }

    /**
     * チャンネルの個人設定を更新する。
     *
     * @param channelId チャンネルID
     * @param userId    ユーザーID
     * @param request   設定リクエスト
     * @return 更新されたメンバーレスポンス
     */
    @Transactional
    public MemberResponse updateSettings(Long channelId, Long userId, ChannelSettingsRequest request) {
        ChatChannelMemberEntity member = findMemberOrThrow(channelId, userId);

        if (request.getIsMuted() != null) {
            member.setMuted(request.getIsMuted());
        }
        if (request.getIsPinned() != null) {
            member.setPinned(request.getIsPinned());
        }
        if (request.getCategory() != null) {
            member.updateCategory(request.getCategory());
        }

        ChatChannelMemberEntity saved = memberRepository.save(member);
        log.info("チャンネル個人設定更新完了: channelId={}, userId={}", channelId, userId);
        return chatMapper.toMemberResponse(saved);
    }

    /**
     * F04.2 Phase 11 第二陣 2-β: 自分のチャンネル個人設定（通知ミュート・ピン留め・カテゴリ）を更新する。
     *
     * <p>{@code PATCH /chat/channels/{id}/members/me} の Service エントリポイント。
     * 設計書 §4 「{@code /settings}（チャンネル全体）」と「{@code /members/me}（メンバー個人）」を
     * 別リソースとして扱う設計のため、本メソッドは「呼び出しユーザー自身のメンバー行」だけを更新する。</p>
     *
     * <p>認可: チャンネルメンバーであること（行が存在しなければ {@link ChatErrorCode#MEMBER_NOT_FOUND}）。
     * 「他人の設定」を弄れない設計のため、対象ユーザー ID パラメータは取らず {@code userId} のみを受ける。</p>
     *
     * @param channelId チャンネル ID
     * @param userId    呼び出しユーザー ID（自分自身）
     * @param request   個人設定更新リクエスト
     * @return 更新後のメンバー情報
     */
    @Transactional
    public MemberResponse updateMySettings(Long channelId, Long userId, UpdateMyChannelSettingsRequest request) {
        ChatChannelMemberEntity member = findMemberOrThrow(channelId, userId);

        if (request.getIsMuted() != null) {
            member.setMuted(request.getIsMuted());
        }
        if (request.getIsPinned() != null) {
            member.setPinned(request.getIsPinned());
        }
        if (request.getCategory() != null) {
            member.updateCategory(request.getCategory());
        }

        ChatChannelMemberEntity saved = memberRepository.save(member);
        log.info("自分のチャンネル個人設定更新完了: channelId={}, userId={}", channelId, userId);
        return chatMapper.toMemberResponse(saved);
    }

    /**
     * 既読処理を行う。
     *
     * @param channelId チャンネルID
     * @param userId    ユーザーID
     */
    @Transactional
    public void markAsRead(Long channelId, Long userId) {
        ChatChannelMemberEntity member = findMemberOrThrow(channelId, userId);
        member.resetUnreadCount();
        memberRepository.save(member);
    }

    /**
     * 自己参加（{@link #joinChannel}）が許されるチャンネルかを検証する。
     *
     * <p>メンバーシップでアクセスを判定する種別（{@link ChannelType#isMembershipGated()}）のうち、
     * 自己参加を許すのは公開スコープチャンネル（{@code TEAM_PUBLIC} / {@code ORG_PUBLIC}）だけであり、
     * さらにそのスコープ（チーム/組織）のメンバーであることを要求する。</p>
     */
    private void checkSelfJoinAllowed(ChatChannelEntity channel, Long userId) {
        ChannelType channelType = channel.getChannelType();
        if (!channelType.isMembershipGated()) {
            // 村ロビー / イベント / 大会チャット: chat_channel_members でアクセス判定しない種別のため従来どおり。
            return;
        }
        // DM / GROUP_DM / 非公開チャンネルは招待制。自己参加は認めない。
        if (!channelType.isSelfJoinableScopeChannel() || Boolean.TRUE.equals(channel.getIsPrivate())) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
        // 公開チャンネルは「スコープ内に対する公開」。当該チーム/組織のメンバーであることを要求する。
        if (channel.getTeamId() != null) {
            accessControlService.checkMembership(userId, channel.getTeamId(), "TEAM");
            return;
        }
        if (channel.getOrganizationId() != null) {
            accessControlService.checkMembership(userId, channel.getOrganizationId(), "ORGANIZATION");
            return;
        }
        // 公開種別なのにスコープが無いチャンネルは所属を検証できない。安全側に倒して拒否する。
        throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
    }

    private ChatChannelMemberEntity findMemberOrThrow(Long channelId, Long userId) {
        return memberRepository.findByChannelIdAndUserId(channelId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MEMBER_NOT_FOUND));
    }
}
