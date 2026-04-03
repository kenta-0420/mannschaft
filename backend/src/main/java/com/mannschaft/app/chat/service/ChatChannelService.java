package com.mannschaft.app.chat.service;

import com.mannschaft.app.auth.DmReceiveFrom;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chat.ChannelMemberRole;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.ChatErrorCode;
import com.mannschaft.app.chat.ChatMapper;
import com.mannschaft.app.chat.dto.ChannelResponse;
import com.mannschaft.app.chat.dto.CreateChannelRequest;
import com.mannschaft.app.chat.dto.UpdateChannelRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.user.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * チャットチャンネルサービス。チャンネルのCRUD・アーカイブを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatChannelService {

    private final ChatChannelRepository channelRepository;
    private final ChatChannelMemberRepository memberRepository;
    private final ChatMapper chatMapper;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserRoleRepository userRoleRepository;
    private final ChatContactFolderItemRepository chatContactFolderItemRepository;

    /**
     * ユーザーが参加しているチャンネル一覧を取得する。
     *
     * @param userId ユーザーID
     * @return チャンネルレスポンスリスト
     */
    public List<ChannelResponse> listMyChannels(Long userId) {
        List<ChatChannelEntity> channels = channelRepository.findByMemberUserId(userId);
        return chatMapper.toChannelResponseList(channels);
    }

    /**
     * チャンネル詳細を取得する。
     *
     * @param channelId チャンネルID
     * @return チャンネルレスポンス
     */
    public ChannelResponse getChannel(Long channelId) {
        ChatChannelEntity entity = findChannelOrThrow(channelId);
        return chatMapper.toChannelResponse(entity);
    }

    /**
     * チャンネルを作成する。
     *
     * @param request   作成リクエスト
     * @param createdBy 作成者ユーザーID
     * @return 作成されたチャンネルレスポンス
     */
    @Transactional
    public ChannelResponse createChannel(CreateChannelRequest request, Long createdBy) {
        ChannelType channelType = ChannelType.valueOf(request.getChannelType());

        validateChannelNameUniqueness(request, channelType);

        // DMチャンネル作成時: ブロック・DM受信制限チェック
        if (channelType == ChannelType.DIRECT) {
            validateDmCreation(request, createdBy);
        }

        ChatChannelEntity channel = ChatChannelEntity.builder()
                .channelType(channelType)
                .teamId(request.getTeamId())
                .organizationId(request.getOrganizationId())
                .name(request.getName())
                .description(request.getDescription())
                .iconKey(request.getIconKey())
                .isPrivate(request.getIsPrivate() != null ? request.getIsPrivate() : false)
                .createdBy(createdBy)
                .build();

        ChatChannelEntity saved = channelRepository.save(channel);

        // 作成者をOWNERとして追加
        ChatChannelMemberEntity ownerMember = ChatChannelMemberEntity.builder()
                .channelId(saved.getId())
                .userId(createdBy)
                .role(ChannelMemberRole.OWNER)
                .build();
        memberRepository.save(ownerMember);

        // 追加メンバーを登録
        if (request.getMemberUserIds() != null) {
            for (Long userId : request.getMemberUserIds()) {
                if (!userId.equals(createdBy)) {
                    ChatChannelMemberEntity member = ChatChannelMemberEntity.builder()
                            .channelId(saved.getId())
                            .userId(userId)
                            .role(ChannelMemberRole.MEMBER)
                            .build();
                    memberRepository.save(member);
                }
            }
        }

        log.info("チャンネル作成完了: channelId={}, type={}, createdBy={}", saved.getId(), channelType, createdBy);
        return chatMapper.toChannelResponse(saved);
    }

    /**
     * チャンネルを更新する。
     *
     * @param channelId チャンネルID
     * @param request   更新リクエスト
     * @return 更新されたチャンネルレスポンス
     */
    @Transactional
    public ChannelResponse updateChannel(Long channelId, UpdateChannelRequest request) {
        ChatChannelEntity channel = findChannelOrThrow(channelId);
        validateNotArchived(channel);

        channel.updateInfo(
                request.getName() != null ? request.getName() : channel.getName(),
                request.getDescription() != null ? request.getDescription() : channel.getDescription(),
                request.getIconKey() != null ? request.getIconKey() : channel.getIconKey()
        );

        ChatChannelEntity saved = channelRepository.save(channel);
        log.info("チャンネル更新完了: channelId={}", channelId);
        return chatMapper.toChannelResponse(saved);
    }

    /**
     * チャンネルを削除する（論理削除）。
     *
     * @param channelId チャンネルID
     */
    @Transactional
    public void deleteChannel(Long channelId) {
        ChatChannelEntity channel = findChannelOrThrow(channelId);
        channel.softDelete();
        channelRepository.save(channel);
        log.info("チャンネル削除完了: channelId={}", channelId);
    }

    /**
     * チャンネルをアーカイブする。
     *
     * @param channelId チャンネルID
     * @return アーカイブされたチャンネルレスポンス
     */
    @Transactional
    public ChannelResponse archiveChannel(Long channelId) {
        ChatChannelEntity channel = findChannelOrThrow(channelId);
        channel.archive();
        ChatChannelEntity saved = channelRepository.save(channel);
        log.info("チャンネルアーカイブ完了: channelId={}", channelId);
        return chatMapper.toChannelResponse(saved);
    }

    /**
     * DMチャンネルをグループDMに変換する。
     * 2者間DMをグループDMに拡張し、追加メンバーを招待可能にする。
     *
     * @param channelId チャンネルID
     * @return 変換後のチャンネルレスポンス
     */
    @Transactional
    public ChannelResponse convertToGroup(Long channelId) {
        ChatChannelEntity channel = findChannelOrThrow(channelId);

        // DM・GROUP_DM以外は変換不可
        if (!channel.isDm()) {
            throw new BusinessException(ChatErrorCode.CHANNEL_NOT_DM);
        }

        channel.convertToGroupDm();
        ChatChannelEntity saved = channelRepository.save(channel);
        log.info("DMをグループDMに変換: channelId={}", channelId);
        return chatMapper.toChannelResponse(saved);
    }

    /**
     * チャンネルエンティティを取得する。見つからない場合は例外をスローする。
     *
     * @param channelId チャンネルID
     * @return チャンネルエンティティ
     */
    ChatChannelEntity findChannelOrThrow(Long channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHANNEL_NOT_FOUND));
    }

    private void validateNotArchived(ChatChannelEntity channel) {
        if (channel.getIsArchived()) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ARCHIVED);
        }
    }

    /**
     * DM作成時のブロック・受信制限チェック。
     * memberUserIds の最初の要素（送信者以外）を受信者とみなしてチェックする。
     *
     * @param request   チャンネル作成リクエスト
     * @param senderId  送信者ユーザーID（DM開始者）
     */
    private void validateDmCreation(CreateChannelRequest request, Long senderId) {
        if (request.getMemberUserIds() == null || request.getMemberUserIds().isEmpty()) {
            return;
        }

        // 送信者以外の最初のメンバーを受信者とみなす
        Long receiverId = request.getMemberUserIds().stream()
                .filter(id -> !id.equals(senderId))
                .findFirst()
                .orElse(null);

        if (receiverId == null) {
            return;
        }

        // 1. ブロックチェック: 受信者が送信者をブロックしている場合は拒否
        if (userBlockRepository.existsByBlockerIdAndBlockedId(receiverId, senderId)) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        // 2. DM受信制限チェック
        UserEntity receiver = userRepository.findById(receiverId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHANNEL_NOT_FOUND));

        DmReceiveFrom dmReceiveFrom = receiver.getDmReceiveFrom();

        if (dmReceiveFrom == DmReceiveFrom.TEAM_MEMBERS_ONLY) {
            // 送信者と受信者がいずれかの共通チームに所属していなければ拒否
            if (!userRoleRepository.existsSharedTeam(senderId, receiverId)) {
                throw new BusinessException(ChatErrorCode.DM_RECEIVE_RESTRICTED);
            }
        } else if (dmReceiveFrom == DmReceiveFrom.CONTACTS_ONLY) {
            // 受信者の連絡先フォルダに送信者が CONTACT として登録されていなければ拒否
            if (!chatContactFolderItemRepository.existsByFolderOwnerAndItemTypeAndItemId(
                    receiverId, FolderItemType.CONTACT, senderId)) {
                throw new BusinessException(ChatErrorCode.DM_RECEIVE_RESTRICTED);
            }
        }
        // ANYONE の場合はスルー
    }

    private void validateChannelNameUniqueness(CreateChannelRequest request, ChannelType channelType) {
        if (request.getName() == null) {
            return;
        }
        boolean duplicate = false;
        if (request.getTeamId() != null) {
            duplicate = channelRepository.existsByTeamIdAndNameAndDeletedAtIsNull(
                    request.getTeamId(), request.getName());
        } else if (request.getOrganizationId() != null) {
            duplicate = channelRepository.existsByOrganizationIdAndNameAndDeletedAtIsNull(
                    request.getOrganizationId(), request.getName());
        }
        if (duplicate) {
            throw new BusinessException(ChatErrorCode.CHANNEL_NAME_DUPLICATE);
        }
    }
}
