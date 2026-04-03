package com.mannschaft.app.user.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
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
