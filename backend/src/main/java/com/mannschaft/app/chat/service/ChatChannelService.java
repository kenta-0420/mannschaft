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
import com.mannschaft.app.chat.dto.InviteToZimmerRequest;
import com.mannschaft.app.chat.dto.UpdateChannelRequest;
import com.mannschaft.app.chat.dto.UpdateInquiryChannelRequest;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.entity.ChatMessageEntity;
import com.mannschaft.app.chat.repository.ChatMessageRepository;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.chat.event.InquiryChannelChangedEvent;
import com.mannschaft.app.user.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
    private final ChatMessageRepository messageRepository;
    private final ChatMapper chatMapper;
    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserRoleRepository userRoleRepository;
    private final ChatContactFolderItemRepository chatContactFolderItemRepository;
    private final ChatChannelEventPublisher eventPublisher;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final AccessControlService accessControlService;

    /**
     * ユーザーが参加しているチャンネル一覧を取得する。
     *
     * <p>各チャンネルに per-user 拡張（memberCount / viewer / dmPartner）を付与する。
     * N+1 を避けるため、メンバー行・メンバー数・DM 相手はいずれもチャンネル数に依存しない
     * 一括クエリで取得する（AC-B7）。</p>
     *
     * @param userId ユーザーID
     * @return チャンネルレスポンスリスト
     */
    public List<ChannelResponse> listMyChannels(Long userId) {
        List<ChatChannelEntity> channels = channelRepository.findByMemberUserId(userId);
        if (channels.isEmpty()) {
            return List.of();
        }
        List<Long> channelIds = channels.stream().map(ChatChannelEntity::getId).toList();

        // viewer: 呼出ユーザー自身のメンバー行を一括取得（1 クエリ）
        Map<Long, ChatChannelMemberEntity> myMemberByChannel = memberRepository.findByUserId(userId).stream()
                .filter(m -> m.getChannelId() != null)
                .collect(Collectors.toMap(ChatChannelMemberEntity::getChannelId, m -> m, (a, b) -> a));

        // memberCount: 一括集計（1 クエリ）
        Map<Long, Integer> memberCountByChannel = new HashMap<>();
        for (ChatChannelMemberRepository.ChannelMemberCount row
                : memberRepository.countGroupedByChannelIds(channelIds)) {
            memberCountByChannel.put(row.getChannelId(), (int) row.getMemberCount());
        }

        // dmPartner: DM チャンネルのみ、相手メンバー＋相手ユーザーを一括取得
        Map<Long, ChannelResponse.DmPartnerDto> dmPartnerByChannel = resolveDmPartners(channels, userId);

        return channels.stream()
                .map(ch -> enrichChannel(ch,
                        myMemberByChannel.get(ch.getId()),
                        memberCountByChannel.get(ch.getId()),
                        dmPartnerByChannel.get(ch.getId())))
                .toList();
    }

    /**
     * チャンネル詳細を取得する。per-user 拡張（memberCount / viewer / dmPartner）を付与する。
     *
     * @param channelId チャンネルID
     * @param userId    呼出ユーザーID
     * @return チャンネルレスポンス
     */
    public ChannelResponse getChannel(Long channelId, Long userId) {
        ChatChannelEntity entity = findChannelOrThrow(channelId);

        ChatChannelMemberEntity myMember =
                memberRepository.findByChannelIdAndUserId(channelId, userId).orElse(null);
        int memberCount = (int) memberRepository.countByChannelId(channelId);

        ChannelResponse.DmPartnerDto dmPartner = null;
        if (entity.getChannelType() == ChannelType.DM) {
            dmPartner = memberRepository.findByChannelIdAndUserIdNot(channelId, userId).stream()
                    .findFirst()
                    .map(pm -> toDmPartner(pm, userRepository.findById(pm.getUserId()).orElse(null)))
                    .orElse(null);
        }

        return enrichChannel(entity, myMember, memberCount, dmPartner);
    }

    /**
     * DM チャンネル群の「呼出ユーザー以外の相手」を一括解決する。
     * メンバー行とユーザーをそれぞれ 1 クエリで取得し、N+1 を避ける。
     */
    private Map<Long, ChannelResponse.DmPartnerDto> resolveDmPartners(
            List<ChatChannelEntity> channels, Long callerId) {
        List<Long> dmChannelIds = channels.stream()
                .filter(c -> c.getChannelType() == ChannelType.DM)
                .map(ChatChannelEntity::getId)
                .toList();
        if (dmChannelIds.isEmpty()) {
            return Map.of();
        }

        List<ChatChannelMemberEntity> partnerMembers =
                memberRepository.findByChannelIdInAndUserIdNot(dmChannelIds, callerId);
        List<Long> partnerUserIds = partnerMembers.stream()
                .map(ChatChannelMemberEntity::getUserId)
                .distinct()
                .toList();
        Map<Long, UserEntity> userById = userRepository.findAllById(partnerUserIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u, (a, b) -> a));

        Map<Long, ChannelResponse.DmPartnerDto> result = new HashMap<>();
        for (ChatChannelMemberEntity pm : partnerMembers) {
            // DM は 2 名構成のため、チャンネルごとに先頭の相手を採用する
            result.putIfAbsent(pm.getChannelId(), toDmPartner(pm, userById.get(pm.getUserId())));
        }
        return result;
    }

    /**
     * ベース変換（{@link ChatMapper#toChannelResponse}）に per-user 拡張を付与する。
     */
    private ChannelResponse enrichChannel(ChatChannelEntity entity,
                                          ChatChannelMemberEntity myMember,
                                          Integer memberCount,
                                          ChannelResponse.DmPartnerDto dmPartner) {
        ChannelResponse base = chatMapper.toChannelResponse(entity);
        if (base == null) {
            return null;
        }
        return base.toBuilder()
                .memberCount(memberCount)
                .viewer(buildViewer(myMember))
                .dmPartner(dmPartner)
                .build();
    }

    /**
     * 呼出ユーザーのメンバー行から viewer 状態を構築する。非メンバー（null）の場合は null。
     */
    private ChannelResponse.ViewerStateDto buildViewer(ChatChannelMemberEntity member) {
        if (member == null) {
            return null;
        }
        return new ChannelResponse.ViewerStateDto(
                member.getUnreadCount(),
                member.getIsMuted(),
                member.getIsPinned(),
                member.getCategory(),
                member.getRole() != null ? member.getRole().name() : null);
    }

    /**
     * 相手メンバー行と相手ユーザーから DmPartnerDto を構築する。
     * 表示名は {@code getDisplayName()}（null の場合は "ユーザー" にフォールバック）。
     */
    private ChannelResponse.DmPartnerDto toDmPartner(ChatChannelMemberEntity partnerMember, UserEntity user) {
        String displayName = (user != null && user.getDisplayName() != null) ? user.getDisplayName() : "ユーザー";
        String avatarUrl = user != null ? user.getAvatarUrl() : null;
        return new ChannelResponse.DmPartnerDto(partnerMember.getUserId(), displayName, avatarUrl);
    }

    /**
     * チャンネルを作成する。
     *
     * @param request   作成リクエスト
     * @param createdBy 作成者ユーザーID
     * @return 作成されたチャンネルレスポンス
     */
    // TODO: chatドメインがauthドメイン（UserRepository）・userドメイン（UserBlockRepository）・roleドメイン（UserRoleRepository）・dashboardドメイン（ChatContactFolderItemRepository）をまたいでいる。将来はそれぞれのQueryService/Eventで分離予定。Phase1-E: 2026-05-09
    @Transactional
    public ChannelResponse createChannel(CreateChannelRequest request, Long createdBy) {
        ChannelType channelType = ChannelType.valueOf(request.getChannelType());

        validateChannelNameUniqueness(request, channelType);

        // DMチャンネル作成時: ブロック・DM受信制限チェック
        if (channelType == ChannelType.DM) {
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
     * @param userId    操作ユーザーID（認可チェック用）
     * @return 更新されたチャンネルレスポンス
     */
    @Transactional
    public ChannelResponse updateChannel(Long channelId, UpdateChannelRequest request, Long userId) {
        ChatChannelEntity channel = findChannelOrThrow(channelId);
        checkChannelAdminAccess(channel, userId);
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
     * @param userId    操作ユーザーID（認可チェック用）
     */
    @Transactional
    public void deleteChannel(Long channelId, Long userId) {
        ChatChannelEntity channel = findChannelOrThrow(channelId);
        checkChannelAdminAccess(channel, userId);
        channel.softDelete();
        channelRepository.save(channel);
        log.info("チャンネル削除完了: channelId={}", channelId);
        // F04.2.1 §3.10.1: 削除されたチャンネルを開いている全メンバーにタブ自動クローズを通知
        eventPublisher.publishChannelDeleted(channelId);
    }

    /**
     * チャンネルをアーカイブする。
     *
     * @param channelId チャンネルID
     * @param userId    操作ユーザーID（認可チェック用）
     * @return アーカイブされたチャンネルレスポンス
     */
    @Transactional
    public ChannelResponse archiveChannel(Long channelId, Long userId) {
        ChatChannelEntity channel = findChannelOrThrow(channelId);
        checkChannelAdminAccess(channel, userId);
        channel.archive();
        ChatChannelEntity saved = channelRepository.save(channel);
        log.info("チャンネルアーカイブ完了: channelId={}", channelId);
        // F04.2.1 §3.10.1: アーカイブされたチャンネルを開いている全メンバーに入力欄無効化を通知
        eventPublisher.publishChannelArchived(channelId);
        return chatMapper.toChannelResponse(saved);
    }

    /**
     * チャンネルのアーカイブを解除する。
     * F04.2.1 §3.10.1: 解除後に全メンバーへ CHANNEL_UNARCHIVED イベントを配信し、フロントエンドのタブを復元する。
     *
     * @param channelId チャンネルID
     * @param userId    操作ユーザーID（認可チェック用）
     * @return 更新されたチャンネルレスポンス
     */
    @Transactional
    public ChannelResponse unarchiveChannel(Long channelId, Long userId) {
        ChatChannelEntity channel = findChannelOrThrow(channelId);
        checkChannelAdminAccess(channel, userId);
        if (!Boolean.TRUE.equals(channel.getIsArchived())) {
            throw new BusinessException(ChatErrorCode.CHANNEL_NOT_ARCHIVED);
        }
        channel.unarchive();
        ChatChannelEntity saved = channelRepository.save(channel);
        log.info("チャンネルアーカイブ解除完了: channelId={}", channelId);
        // F04.2.1 §3.10.1: 解除を全メンバーに通知（フロントエンドが自動タブクローズを解除）
        eventPublisher.publishChannelUnarchived(channelId);
        return chatMapper.toChannelResponse(saved);
    }

    /**
     * 会話を開始する。参加者数に応じて Kabine（DM）/ Zimmer（GROUP_DM）を自動振り分け。
     * <ul>
     *   <li>userIds.size == 1 → Kabine: 既存 DM があれば返却（200）、なければ新規作成（201）</li>
     *   <li>userIds.size >= 2 → Zimmer: 常に新規 GROUP_DM を作成（201）</li>
     * </ul>
     *
     * @param callerId 会話開始者のユーザーID
     * @param userIds  会話相手のユーザーIDリスト（1〜9名、自分自身を含まない）
     * @return 会話チャンネルレスポンスと新規作成フラグ
     */
    // TODO: chatドメインがauthドメイン（UserRepository）・userドメイン（UserBlockRepository）・roleドメイン（UserRoleRepository）・dashboardドメイン（ChatContactFolderItemRepository）をまたいでいる。将来はそれぞれのQueryService/Eventで分離予定。Phase1-E: 2026-05-09
    @Transactional
    public ConversationResult startConversation(Long callerId, List<Long> userIds) {
        // 自分自身が含まれていないか検証
        if (userIds.contains(callerId)) {
            throw new BusinessException(ChatErrorCode.CHANNEL_SELF_DM);
        }

        if (userIds.size() == 1) {
            return startKabine(callerId, userIds.get(0));
        } else {
            return startZimmer(callerId, userIds);
        }
    }

    /**
     * Kabine（1対1 DM）を開始する。既存があれば返却、なければ新規作成。
     */
    private ConversationResult startKabine(Long callerId, Long partnerId) {
        // ブロック・DM受信制限チェック
        if (userBlockRepository.existsByBlockerIdAndBlockedId(partnerId, callerId)) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }
        UserEntity partner = userRepository.findById(partnerId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHANNEL_NOT_FOUND));
        checkDmReceiveRestriction(callerId, partner);

        // 既存 DM を検索
        return channelRepository.findExistingDm(callerId, partnerId)
                .map(existing -> new ConversationResult(chatMapper.toChannelResponse(existing), false))
                .orElseGet(() -> {
                    // 新規 DM 作成
                    ChatChannelEntity channel = ChatChannelEntity.builder()
                            .channelType(ChannelType.DM)
                            .createdBy(callerId)
                            .build();
                    ChatChannelEntity saved = channelRepository.save(channel);
                    memberRepository.save(ChatChannelMemberEntity.builder()
                            .channelId(saved.getId()).userId(callerId).role(ChannelMemberRole.OWNER).build());
                    memberRepository.save(ChatChannelMemberEntity.builder()
                            .channelId(saved.getId()).userId(partnerId).role(ChannelMemberRole.MEMBER).build());
                    log.info("Kabine作成: channelId={}, callerId={}, partnerId={}", saved.getId(), callerId, partnerId);
                    return new ConversationResult(chatMapper.toChannelResponse(saved), true);
                });
    }

    /**
     * Zimmer（グループDM）を開始する。常に新規作成。
     */
    private ConversationResult startZimmer(Long callerId, List<Long> partnerIds) {
        // 全参加者のブロックチェック（自分がブロックされている相手がいないか確認）
        for (Long partnerId : partnerIds) {
            if (userBlockRepository.existsByBlockerIdAndBlockedId(partnerId, callerId)) {
                throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
            }
        }

        ChatChannelEntity channel = ChatChannelEntity.builder()
                .channelType(ChannelType.GROUP_DM)
                .createdBy(callerId)
                .build();
        ChatChannelEntity saved = channelRepository.save(channel);

        // 作成者をOWNERとして追加
        memberRepository.save(ChatChannelMemberEntity.builder()
                .channelId(saved.getId()).userId(callerId).role(ChannelMemberRole.OWNER).build());

        // 参加者をMEMBERとして追加
        for (Long partnerId : partnerIds) {
            memberRepository.save(ChatChannelMemberEntity.builder()
                    .channelId(saved.getId()).userId(partnerId).role(ChannelMemberRole.MEMBER).build());
        }

        log.info("Zimmer作成: channelId={}, callerId={}, members={}", saved.getId(), callerId, partnerIds.size() + 1);
        return new ConversationResult(chatMapper.toChannelResponse(saved), true);
    }

    /**
     * DM受信制限チェック（相手の設定に基づいて DM を受け入れるか判定）。
     */
    private void checkDmReceiveRestriction(Long senderId, UserEntity receiver) {
        DmReceiveFrom setting = receiver.getDmReceiveFrom();
        if (setting == DmReceiveFrom.TEAM_MEMBERS_ONLY) {
            if (!userRoleRepository.existsSharedTeam(senderId, receiver.getId())) {
                throw new BusinessException(ChatErrorCode.DM_RECEIVE_RESTRICTED);
            }
        } else if (setting == DmReceiveFrom.CONTACTS_ONLY) {
            if (!chatContactFolderItemRepository.existsByFolderOwnerAndItemTypeAndItemId(
                    receiver.getId(), FolderItemType.CONTACT, senderId)) {
                throw new BusinessException(ChatErrorCode.DM_RECEIVE_RESTRICTED);
            }
        }
    }

    /**
     * 会話開始結果。チャンネルレスポンスと新規作成フラグを保持する。
     */
    public record ConversationResult(ChannelResponse channel, boolean created) {}

    /**
     * KabineからZimmerへの招待。既存のKabine（DM）を維持したまま新しいZimmer（GROUP_DM）を作成する。
     * <ol>
     *   <li>channelId は ChannelType.DM のチャンネルであること</li>
     *   <li>callerId はそのKabineのメンバーであること</li>
     *   <li>Kabineの既存メンバー全員 + inviteeIds で新Zimmerを作成</li>
     *   <li>shareHistory=true の場合、Kabineのトップレベルメッセージを転送コピー</li>
     * </ol>
     *
     * @param channelId Kabine（DM）のチャンネルID
     * @param callerId  招待操作を行うユーザーID
     * @param request   招待リクエスト
     * @return 新しく作成されたZimmerのチャンネルレスポンス
     */
    // TODO: chatドメインがauthドメイン（UserRepository）・userドメイン（UserBlockRepository）・roleドメイン（UserRoleRepository）・dashboardドメイン（ChatContactFolderItemRepository）をまたいでいる。将来はそれぞれのQueryService/Eventで分離予定。Phase1-E: 2026-05-09
    @Transactional
    public ChannelResponse inviteToZimmer(Long channelId, Long callerId, InviteToZimmerRequest request) {
        ChatChannelEntity kabine = findChannelOrThrow(channelId);

        if (kabine.getChannelType() != ChannelType.DM) {
            throw new BusinessException(ChatErrorCode.CHANNEL_NOT_DM);
        }
        if (!memberRepository.existsByChannelIdAndUserId(channelId, callerId)) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
        }

        // 新しいZimmer（GROUP_DM）を作成
        ChatChannelEntity zimmer = ChatChannelEntity.builder()
                .channelType(ChannelType.GROUP_DM)
                .createdBy(callerId)
                .build();
        ChatChannelEntity savedZimmer = channelRepository.save(zimmer);

        // Kabineの既存メンバーを移行（callerIdはOWNER、他はMEMBER）
        List<ChatChannelMemberEntity> kabineMembers =
                memberRepository.findByChannelIdOrderByJoinedAtAsc(channelId);
        for (ChatChannelMemberEntity m : kabineMembers) {
            ChannelMemberRole role = m.getUserId().equals(callerId)
                    ? ChannelMemberRole.OWNER : ChannelMemberRole.MEMBER;
            memberRepository.save(ChatChannelMemberEntity.builder()
                    .channelId(savedZimmer.getId())
                    .userId(m.getUserId())
                    .role(role)
                    .build());
        }

        // 新たに招待するメンバーを追加（ブロックチェック込み）
        for (Long inviteeId : request.getUserIds()) {
            // 招待対象が呼び出しユーザーをブロックしている場合は拒否
            if (userBlockRepository.existsByBlockerIdAndBlockedId(inviteeId, callerId)) {
                throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
            }
            if (!memberRepository.existsByChannelIdAndUserId(savedZimmer.getId(), inviteeId)) {
                memberRepository.save(ChatChannelMemberEntity.builder()
                        .channelId(savedZimmer.getId())
                        .userId(inviteeId)
                        .role(ChannelMemberRole.MEMBER)
                        .build());
            }
        }

        // 履歴共有: Kabineのトップレベルメッセージを転送コピー
        if (request.isShareHistory()) {
            List<ChatMessageEntity> history =
                    messageRepository.findByChannelIdOrderByCreatedAtAsc(channelId);
            for (ChatMessageEntity original : history) {
                if (original.getParentId() != null) {
                    // スレッド返信はスキップ
                    continue;
                }
                messageRepository.save(ChatMessageEntity.builder()
                        .channelId(savedZimmer.getId())
                        .senderId(original.getSenderId())
                        .body(original.getBody())
                        .forwardedFromId(original.getId())
                        .build());
            }
        }

        log.info("Zimmer作成（Kabineから招待）: kabineId={}, zimmerId={}, callerId={}, shareHistory={}",
                channelId, savedZimmer.getId(), callerId, request.isShareHistory());
        return chatMapper.toChannelResponse(savedZimmer);
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
     * <p>F13 Phase 4-β でパッケージ外（{@link com.mannschaft.app.chat.controller.ChatUploadController}）
     * からも呼ばれるため public とした。</p>
     *
     * @param channelId チャンネルID
     * @return チャンネルエンティティ
     */
    public ChatChannelEntity findChannelOrThrow(Long channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHANNEL_NOT_FOUND));
    }

    private void validateNotArchived(ChatChannelEntity channel) {
        if (channel.getIsArchived()) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ARCHIVED);
        }
    }

    /**
     * チャンネル管理操作（更新・削除・アーカイブ）の認可チェック。
     * チーム/組織チャンネルはADMIN以上、DM/GROUP_DMはチャンネルOWNERのみ許可。
     */
    private void checkChannelAdminAccess(ChatChannelEntity channel, Long userId) {
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
        // DM / GROUP_DM: チャンネルのOWNERのみ許可
        ChatChannelMemberEntity member = memberRepository.findByChannelIdAndUserId(channel.getId(), userId)
                .orElseThrow(() -> new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED));
        if (member.getRole() != ChannelMemberRole.OWNER) {
            throw new BusinessException(ChatErrorCode.CHANNEL_ACCESS_DENIED);
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

    /**
     * F10.7: 問い合わせチャンネル設定を更新する。
     *
     * <p>バリデーション:</p>
     * <ul>
     *   <li>{@code channelType = TEAM} のみ設定可能（それ以外は 400 Bad Request）</li>
     *   <li>アーカイブ済み（{@code is_archived = TRUE}）には設定不可（400 Bad Request）</li>
     *   <li>{@code is_inquiry_channel = TRUE} にする場合、同チームに既に別の問い合わせチャンネルがあれば 409 Conflict</li>
     * </ul>
     *
     * <p><b>認可の真の強制点（Track2 第二陣 / 2026-05-29）</b>: コントローラーの
     * {@code @PreAuthorize("hasRole('ADMIN')")} は {@code @EnableMethodSecurity} 未有効ゆえ
     * 実機では効かないため、本メソッド内で per-scope 認可（当該チャンネルが属するチームの
     * ADMIN/DEPUTY_ADMIN、または SYSTEM_ADMIN）を強制する。
     * circulation ドメインの {@code CirculationService#checkScopeAdminAccess}（#1183）と同一の方針。</p>
     *
     * @param channelId チャンネルID
     * @param request   更新リクエスト
     * @param userId    操作ユーザー ID（認可チェック用）
     * @return 更新後のチャンネルレスポンス
     * @throws BusinessException 当該チームの ADMIN/DEPUTY_ADMIN でも SYSTEM_ADMIN でもない場合（COMMON_002）
     */
    @Transactional
    public ChannelResponse updateInquiryChannel(Long channelId, UpdateInquiryChannelRequest request, Long userId) {
        ChatChannelEntity channel = findChannelOrThrow(channelId);

        // per-scope 認可: SYSTEM_ADMIN 短絡 → そのチャンネルのチーム ADMIN/DEPUTY_ADMIN 必須。
        if (!accessControlService.isSystemAdmin(userId)) {
            accessControlService.checkAdminOrAbove(userId, channel.getTeamId(), "TEAM");
        }

        // TEAM_PUBLIC / TEAM_PRIVATE チャンネルのみ設定可能
        if (channel.getChannelType() != ChannelType.TEAM_PUBLIC
                && channel.getChannelType() != ChannelType.TEAM_PRIVATE) {
            throw new BusinessException(ChatErrorCode.INQUIRY_CHANNEL_TEAM_ONLY);
        }

        // アーカイブ済みには設定不可
        if (Boolean.TRUE.equals(channel.getIsArchived())) {
            throw new BusinessException(ChatErrorCode.INQUIRY_CHANNEL_ARCHIVED);
        }

        // is_inquiry_channel = TRUE にする場合、同チームに既に別の問い合わせチャンネルがあれば 409 Conflict
        if (Boolean.TRUE.equals(request.getIsInquiryChannel())) {
            channelRepository.findByTeamIdAndIsInquiryChannelTrue(channel.getTeamId())
                    .filter(existing -> !existing.getId().equals(channelId))
                    .ifPresent(existing -> {
                        throw new BusinessException(ChatErrorCode.INQUIRY_CHANNEL_ALREADY_EXISTS);
                    });
        }

        channel.updateInquiryChannel(request.getIsInquiryChannel());
        ChatChannelEntity saved = channelRepository.save(channel);
        log.info("問い合わせチャンネル設定更新: channelId={}, isInquiryChannel={}", channelId, request.getIsInquiryChannel());
        // 業務アラートキャッシュを即時無効化するためにイベントを発行する。
        // AdminBusinessAlertService がこのイベントを受信してキャッシュを削除し、
        // ウィジェットに inquiry 設定が即時反映されるようにする（F10.7）。
        applicationEventPublisher.publishEvent(
                new InquiryChannelChangedEvent(saved.getTeamId(), saved.getId()));
        return chatMapper.toChannelResponse(saved);
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
