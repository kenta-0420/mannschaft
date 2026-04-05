package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chat.ChannelType;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.entity.ChatChannelMemberEntity;
import com.mannschaft.app.chat.repository.ChatChannelMemberRepository;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.dto.ChatHubResponse;
import com.mannschaft.app.dashboard.dto.ChatHubSummaryDto;
import com.mannschaft.app.dashboard.dto.ContactFolderDto;
import com.mannschaft.app.dashboard.dto.ContactItemDto;
import com.mannschaft.app.dashboard.dto.DirectMessageItemDto;
import com.mannschaft.app.dashboard.dto.TeamChannelItemDto;
import com.mannschaft.app.dashboard.entity.ChatContactFolderEntity;
import com.mannschaft.app.dashboard.entity.ChatContactFolderItemEntity;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.dashboard.repository.ChatContactFolderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * チャットハブサービス。
 * チームチャンネル・DM・連絡先フォルダを集約して {@link ChatHubResponse} を返す。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class ChatHubService {

    private final ChatChannelMemberRepository chatChannelMemberRepository;
    private final ChatChannelRepository chatChannelRepository;
    private final ChatContactFolderRepository chatContactFolderRepository;
    private final ChatContactFolderItemRepository chatContactFolderItemRepository;
    private final UserRepository userRepository;

    /**
     * 指定ユーザーのチャットハブデータを取得する。
     *
     * @param userId ログインユーザーID
     * @return チャットハブ全体レスポンス
     */
    public ChatHubResponse getChatHub(Long userId) {

        // ── 1. 自分が参加しているチャンネルのメンバーシップを取得 ──────────────────
        List<ChatChannelMemberEntity> memberships = chatChannelMemberRepository.findByUserId(userId);
        Set<Long> channelIds = memberships.stream()
                .map(ChatChannelMemberEntity::getChannelId)
                .collect(Collectors.toSet());

        // メンバーシップを channelId → entity でマップ化（後続で isPinned / isMuted を参照）
        Map<Long, ChatChannelMemberEntity> membershipByChannelId = memberships.stream()
                .collect(Collectors.toMap(ChatChannelMemberEntity::getChannelId, Function.identity()));

        // ── 2. チャンネルエンティティを一括取得 ──────────────────────────────────
        List<ChatChannelEntity> channels = chatChannelRepository.findByMemberUserId(userId);

        // channelId → channel エンティティのマップ
        Map<Long, ChatChannelEntity> channelMap = channels.stream()
                .collect(Collectors.toMap(c -> c.getId(), Function.identity()));

        // ── 3. チームチャンネルと DM に振り分け ──────────────────────────────────
        List<ChatChannelEntity> teamChannelEntities = channels.stream()
                .filter(c -> !isDm(c.getChannelType()))
                .toList();

        List<ChatChannelEntity> dmChannelEntities = channels.stream()
                .filter(c -> isDm(c.getChannelType()))
                .toList();

        // ── 4. DM のパートナーユーザーを解決 ────────────────────────────────────
        // DM チャンネルのメンバーを一括取得し、自分以外のユーザーIDを集める
        Set<Long> dmChannelIdSet = dmChannelEntities.stream()
                .map(c -> c.getId())
                .collect(Collectors.toSet());

        // DM パートナーユーザーID収集 (チャンネルごとに自分以外の最初のメンバーを使用)
        Map<Long, Long> dmChannelToPartnerUserId = dmChannelIdSet.stream()
                .collect(Collectors.toMap(
                        Function.identity(),
                        channelId -> {
                            List<ChatChannelMemberEntity> members =
                                    chatChannelMemberRepository.findByChannelIdOrderByJoinedAtAsc(channelId);
                            return members.stream()
                                    .map(ChatChannelMemberEntity::getUserId)
                                    .filter(uid -> !uid.equals(userId))
                                    .findFirst()
                                    .orElse(userId); // フォールバック: 自分自身（1人チャンネル等の異常系）
                        }
                ));

        // パートナーユーザーIDを一括取得
        Set<Long> partnerUserIds = dmChannelToPartnerUserId.values().stream()
                .collect(Collectors.toSet());
        Map<Long, UserEntity> partnerUserMap = userRepository.findAllById(partnerUserIds).stream()
                .collect(Collectors.toMap(u -> u.getId(), Function.identity()));

        // ── 5. チームチャンネル DTO 組み立て ──────────────────────────────────────
        List<TeamChannelItemDto> teamChannels = teamChannelEntities.stream()
                .map(channel -> {
                    ChatChannelMemberEntity membership = membershipByChannelId.get(channel.getId());
                    // TODO: 未読数は別フェーズで実装予定。現在は 0 を返す。
                    int unreadCount = (membership != null) ? membership.getUnreadCount() : 0;
                    boolean isPinned = (membership != null) && Boolean.TRUE.equals(membership.getIsPinned());
                    boolean isMuted  = (membership != null) && Boolean.TRUE.equals(membership.getIsMuted());
                    return new TeamChannelItemDto(
                            channel.getId(),
                            channel.getName(),
                            channel.getChannelType().name(),
                            channel.getTeamId(),
                            channel.getOrganizationId(),
                            unreadCount,
                            isPinned,
                            isMuted,
                            channel.getLastMessageAt()
                    );
                })
                .sorted(Comparator.comparing(TeamChannelItemDto::isPinned).reversed()
                        .thenComparing(Comparator.comparing(TeamChannelItemDto::lastMessageAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))))
                .toList();

        // ── 6. DM DTO 組み立て ────────────────────────────────────────────────
        List<DirectMessageItemDto> directMessages = dmChannelEntities.stream()
                .map(channel -> {
                    ChatChannelMemberEntity membership = membershipByChannelId.get(channel.getId());
                    // TODO: 未読数は別フェーズで実装予定。現在は 0 を返す。
                    int unreadCount = (membership != null) ? membership.getUnreadCount() : 0;
                    boolean isPinned = (membership != null) && Boolean.TRUE.equals(membership.getIsPinned());
                    boolean isMuted  = (membership != null) && Boolean.TRUE.equals(membership.getIsMuted());

                    Long partnerUserId = dmChannelToPartnerUserId.getOrDefault(channel.getId(), null);
                    UserEntity partner = (partnerUserId != null) ? partnerUserMap.get(partnerUserId) : null;
                    String partnerDisplayName = (partner != null) ? partner.getDisplayName() : "";
                    String partnerAvatarUrl   = (partner != null) ? partner.getAvatarUrl() : null;

                    // lastMessagePreview: クエリコスト削減のため channel エンティティのプレビューを流用
                    // 最大50文字にトリム
                    String preview = null;
                    if (channel.getLastMessageAt() != null && channel.getLastMessagePreview() != null) {
                        String raw = channel.getLastMessagePreview();
                        preview = raw.length() > 50 ? raw.substring(0, 50) : raw;
                    }

                    return new DirectMessageItemDto(
                            channel.getId(),
                            partnerUserId,
                            partnerDisplayName,
                            partnerAvatarUrl,
                            unreadCount,
                            isPinned,
                            isMuted,
                            channel.getLastMessageAt(),
                            preview
                    );
                })
                .sorted(Comparator.comparing(DirectMessageItemDto::isPinned).reversed()
                        .thenComparing(Comparator.comparing(DirectMessageItemDto::lastMessageAt,
                                Comparator.nullsLast(Comparator.reverseOrder()))))
                .toList();

        // ── 7. フォルダ別連絡先 DTO 組み立て ──────────────────────────────────────
        List<ChatContactFolderEntity> folders =
                chatContactFolderRepository.findByUserIdOrderBySortOrder(userId);

        // DM チャンネルを userId でマップ化（パートナーユーザーID → チャンネル）
        // userId が連絡先 itemId としてそのまま使われる
        // FolderItemType.CONTACT を想定（アイテムIDはユーザーID）
        Map<Long, ChatChannelEntity> dmByPartnerId = dmChannelToPartnerUserId.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getValue,
                        e -> channelMap.get(e.getKey())
                ));

        List<ContactFolderDto> contacts = folders.stream()
                .map(folder -> {
                    List<ChatContactFolderItemEntity> folderItems =
                            chatContactFolderItemRepository.findByFolderId(folder.getId());

                    // USER タイプのアイテムのみ対象とする
                    List<Long> userItemIds = folderItems.stream()
                            .filter(item -> FolderItemType.CONTACT.equals(item.getItemType()))
                            .map(ChatContactFolderItemEntity::getItemId)
                            .toList();

                    Map<Long, UserEntity> folderUserMap = userRepository.findAllById(userItemIds).stream()
                            .collect(Collectors.toMap(u -> u.getId(), Function.identity()));

                    // folderItems を itemId でマップ化
                    Map<Long, ChatContactFolderItemEntity> folderItemByUserId = folderItems.stream()
                            .filter(item -> FolderItemType.CONTACT.equals(item.getItemType()))
                            .collect(Collectors.toMap(ChatContactFolderItemEntity::getItemId, Function.identity()));

                    List<ContactItemDto> items = folderItems.stream()
                            .filter(item -> FolderItemType.CONTACT.equals(item.getItemType()))
                            .map(item -> {
                                Long contactUserId = item.getItemId();
                                UserEntity user = folderUserMap.get(contactUserId);
                                String displayName = (item.getCustomName() != null && !item.getCustomName().isBlank())
                                        ? item.getCustomName()
                                        : (user != null ? user.getDisplayName() : "");
                                String avatarUrl = (user != null) ? user.getAvatarUrl() : null;

                                // アクティブ DM の有無と最終メッセージ日時
                                ChatChannelEntity activeDm = dmByPartnerId.get(contactUserId);
                                boolean hasActiveDm = (activeDm != null);

                                return new ContactItemDto(
                                        contactUserId,
                                        displayName,
                                        avatarUrl,
                                        Boolean.TRUE.equals(item.getIsPinned()),
                                        hasActiveDm,
                                        hasActiveDm ? activeDm.getLastMessageAt() : null
                                );
                            })
                            // isPinned=true を先頭にソート
                            .sorted(Comparator.comparing(ContactItemDto::isPinned).reversed()
                                    .thenComparing(Comparator.comparing(ContactItemDto::lastMessageAt,
                                            Comparator.nullsLast(Comparator.reverseOrder()))))
                            .toList();

                    return new ContactFolderDto(
                            folder.getId(),
                            folder.getName(),
                            folder.getIcon(),
                            folder.getColor(),
                            folder.getSortOrder(),
                            items
                    );
                })
                .toList();

        // ── 8. サマリー算出 ───────────────────────────────────────────────────
        // TODO: totalUnread は未読数集計フェーズで正確な値に置き換える予定。現在はメンバーシップの合計値を使用。
        int totalUnread = memberships.stream()
                .mapToInt(ChatChannelMemberEntity::getUnreadCount)
                .sum();
        int totalDmCount = directMessages.size();
        int totalContactCount = contacts.stream()
                .mapToInt(f -> f.items().size())
                .sum();

        ChatHubSummaryDto summary = new ChatHubSummaryDto(totalUnread, totalDmCount, totalContactCount);

        return new ChatHubResponse(teamChannels, directMessages, contacts, summary);
    }

    /**
     * チャンネルタイプが DM 系か判定する。
     *
     * @param type チャンネルタイプ
     * @return DM または GROUP_DM の場合 true
     */
    private boolean isDm(ChannelType type) {
        return type == ChannelType.DM || type == ChannelType.GROUP_DM;
    }
}
