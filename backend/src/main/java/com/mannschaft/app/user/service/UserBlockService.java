package com.mannschaft.app.user.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.chat.entity.ChatChannelEntity;
import com.mannschaft.app.chat.repository.ChatChannelRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.contact.repository.ContactRequestBlockRepository;
import com.mannschaft.app.contact.repository.ContactRequestRepository;
import com.mannschaft.app.contact.service.ContactService;
import com.mannschaft.app.user.UserErrorCode;
import com.mannschaft.app.user.dto.UserBlockResponse;
import com.mannschaft.app.user.entity.UserBlockEntity;
import com.mannschaft.app.user.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * ユーザーブロック機能のサービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class UserBlockService {

    private final UserBlockRepository userBlockRepository;
    private final UserRepository userRepository;
    private final ContactRequestRepository contactRequestRepository;
    private final ContactRequestBlockRepository contactRequestBlockRepository;
    private final ContactService contactService;
    private final ChatChannelRepository chatChannelRepository;

    /**
     * 指定ユーザーをブロックする。
     * 自分自身のブロック・重複ブロックはエラー。
     *
     * @param blockerId ブロックするユーザーID
     * @param targetId  ブロックされるユーザーID
     */
    @Transactional
    public void block(Long blockerId, Long targetId) {
        // 自分自身チェック
        if (blockerId.equals(targetId)) {
            throw new BusinessException(UserErrorCode.USER_003);
        }
        // 重複チェック
        if (userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, targetId)) {
            throw new BusinessException(UserErrorCode.USER_001);
        }
        UserBlockEntity entity = UserBlockEntity.builder()
                .blockerId(blockerId)
                .blockedId(targetId)
                .build();
        userBlockRepository.save(entity);

        // F04.8 ブロック副作用処理
        // 1. 双方の連絡先フォルダから相手を削除
        contactService.removeContactFromFolder(blockerId, targetId);
        contactService.removeContactFromFolder(targetId, blockerId);

        // 2. PENDING 申請をキャンセル
        contactRequestRepository.findByRequesterIdAndTargetIdAndStatus(blockerId, targetId, "PENDING")
                .ifPresent(r -> r.cancel());
        contactRequestRepository.findByRequesterIdAndTargetIdAndStatus(targetId, blockerId, "PENDING")
                .ifPresent(r -> r.cancel());

        // 3. 申請事前拒否リストに追加（ブロック解除まで申請も受けない）
        if (!contactRequestBlockRepository.existsByUserIdAndBlockedId(blockerId, targetId)) {
            contactRequestBlockRepository.save(
                    com.mannschaft.app.contact.entity.ContactRequestBlockEntity.builder()
                            .userId(blockerId).blockedId(targetId).build());
        }

        // 4. DM チャンネルをアーカイブ
        chatChannelRepository.findDmChannelBetween(blockerId, targetId)
                .ifPresent(ChatChannelEntity::archive);
    }

    /**
     * 指定ユーザーのブロックを解除する。
     * ブロック関係が存在しない場合はエラー。
     *
     * @param blockerId ブロックを解除するユーザーID
     * @param targetId  ブロック解除されるユーザーID
     */
    @Transactional
    public void unblock(Long blockerId, Long targetId) {
        // 存在チェック
        if (!userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, targetId)) {
            throw new BusinessException(UserErrorCode.USER_002);
        }
        userBlockRepository.deleteByBlockerIdAndBlockedId(blockerId, targetId);

        // F04.8 ブロック解除副作用: contact_request_blocks も解除
        if (contactRequestBlockRepository.existsByUserIdAndBlockedId(blockerId, targetId)) {
            contactRequestBlockRepository.deleteByUserIdAndBlockedId(blockerId, targetId);
        }
        // 連絡先関係・DMチャンネルは復元しない（再申請が必要）
    }

    /**
     * ブロック一覧を取得する。ブロック対象ユーザーの表示名・アバターURLを付与して返却する。
     *
     * @param blockerId ブロックしているユーザーID
     * @return ブロック一覧
     */
    public List<UserBlockResponse> listBlocks(Long blockerId) {
        List<UserBlockEntity> blocks = userBlockRepository.findByBlockerId(blockerId);
        if (blocks.isEmpty()) {
            return List.of();
        }

        // ブロック対象ユーザーIDを一括取得してマップ化
        List<Long> blockedIds = blocks.stream()
                .map(UserBlockEntity::getBlockedId)
                .toList();
        Map<Long, UserEntity> userMap = userRepository.findAllById(blockedIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        return blocks.stream()
                .map(block -> {
                    UserEntity user = userMap.get(block.getBlockedId());
                    return UserBlockResponse.builder()
                            .blockedId(block.getBlockedId())
                            .blockedDisplayName(user != null ? user.getDisplayName() : null)
                            .blockedAvatarUrl(user != null ? user.getAvatarUrl() : null)
                            .createdAt(block.getCreatedAt())
                            .build();
                })
                .toList();
    }

    /**
     * ブロック関係が存在するか確認する（他サービスから参照用）。
     *
     * @param blockerId ブロックしているユーザーID
     * @param blockedId ブロックされているユーザーID
     * @return ブロックされている場合 true
     */
    public boolean isBlocked(Long blockerId, Long blockedId) {
        return userBlockRepository.existsByBlockerIdAndBlockedId(blockerId, blockedId);
    }
}
