package com.mannschaft.app.chat.service;

import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.AddMemberRequest;
import com.mannschaft.app.chat.dto.ChangeRoleRequest;
import com.mannschaft.app.chat.dto.ChannelSettingsRequest;
import com.mannschaft.app.chat.dto.MemberResponse;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
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
     * @param channelId チャンネルID
     * @param request   追加リクエスト
     * @return 追加されたメンバーレスポンスリスト
     */
    @Transactional
    public List<MemberResponse> addMembers(Long channelId, AddMemberRequest request) {
        channelService.findChannelOrThrow(channelId);

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
     * @param channelId チャンネルID
     * @param userId    除外するユーザーID
     */
    @Transactional
    public void removeMember(Long channelId, Long userId) {
        ChatChannelMemberEntity member = findMemberOrThrow(channelId, userId);
        if (member.getRole() == ChannelMemberRole.OWNER) {
            throw new BusinessException(ChatErrorCode.OWNER_CANNOT_LEAVE);
        }
        memberRepository.deleteByChannelIdAndUserId(channelId, userId);
        log.info("メンバー除外完了: channelId={}, userId={}", channelId, userId);
    }

    /**
     * チャンネルに自分で参加する。
     *
     * @param channelId チャンネルID
     * @param userId    参加するユーザーID
     * @return 参加したメンバーレスポンス
     */
    @Transactional
    public MemberResponse joinChannel(Long channelId, Long userId) {
        channelService.findChannelOrThrow(channelId);

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
     * @param channelId    チャンネルID
     * @param targetUserId 対象ユーザーID
     * @param request      ロール変更リクエスト
     * @return 更新されたメンバーレスポンス
     */
    @Transactional
    public MemberResponse changeRole(Long channelId, Long targetUserId, ChangeRoleRequest request) {
        ChatChannelMemberEntity member = findMemberOrThrow(channelId, targetUserId);
        ChannelMemberRole newRole = ChannelMemberRole.valueOf(request.getRole());
        member.changeRole(newRole);
        ChatChannelMemberEntity saved = memberRepository.save(member);
        log.info("ロール変更完了: channelId={}, userId={}, newRole={}", channelId, targetUserId, newRole);
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

    private ChatChannelMemberEntity findMemberOrThrow(Long channelId, Long userId) {
        return memberRepository.findByChannelIdAndUserId(channelId, userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.MEMBER_NOT_FOUND));
    }
}
