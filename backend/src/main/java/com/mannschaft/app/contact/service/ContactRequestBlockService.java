package com.mannschaft.app.contact.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.contact.ContactErrorCode;
import com.mannschaft.app.contact.dto.ContactRequestBlockResponse;
import com.mannschaft.app.contact.dto.ContactUserDto;
import com.mannschaft.app.contact.entity.ContactRequestBlockEntity;
import com.mannschaft.app.contact.repository.ContactRequestBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 連絡先申請事前拒否サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ContactRequestBlockService {

    private final ContactRequestBlockRepository contactRequestBlockRepository;
    private final UserRepository userRepository;

    /**
     * 事前拒否リストを取得する。
     */
    public List<ContactRequestBlockResponse> listBlocks(Long userId) {
        List<ContactRequestBlockEntity> blocks =
                contactRequestBlockRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (blocks.isEmpty()) return List.of();

        List<Long> blockedIds = blocks.stream().map(ContactRequestBlockEntity::getBlockedId).toList();
        Map<Long, UserEntity> userMap = userRepository.findAllById(blockedIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        return blocks.stream().map(block -> {
            UserEntity u = userMap.get(block.getBlockedId());
            return ContactRequestBlockResponse.builder()
                    .id(block.getId())
                    .blockedUser(u != null ? ContactUserDto.builder()
                            .id(u.getId())
                            .displayName(u.getDisplayName())
                            .contactHandle(u.getContactHandle())
                            .avatarUrl(u.getAvatarUrl())
                            .build() : ContactUserDto.builder().id(block.getBlockedId()).build())
                    .createdAt(block.getCreatedAt())
                    .build();
        }).toList();
    }

    /**
     * 特定ユーザーからの申請を事前拒否に追加する。
     */
    @Transactional
    public ContactRequestBlockResponse addBlock(Long userId, Long targetUserId) {
        if (contactRequestBlockRepository.existsByUserIdAndBlockedId(userId, targetUserId)) {
            throw new BusinessException(ContactErrorCode.CONTACT_011);
        }

        ContactRequestBlockEntity entity = ContactRequestBlockEntity.builder()
                .userId(userId)
                .blockedId(targetUserId)
                .build();
        ContactRequestBlockEntity saved = contactRequestBlockRepository.save(entity);

        UserEntity u = userRepository.findById(targetUserId).orElse(null);
        return ContactRequestBlockResponse.builder()
                .id(saved.getId())
                .blockedUser(u != null ? ContactUserDto.builder()
                        .id(u.getId())
                        .displayName(u.getDisplayName())
                        .contactHandle(u.getContactHandle())
                        .avatarUrl(u.getAvatarUrl())
                        .build() : ContactUserDto.builder().id(targetUserId).build())
                .createdAt(saved.getCreatedAt())
                .build();
    }

    /**
     * 事前拒否設定を解除する。
     */
    @Transactional
    public void removeBlock(Long userId, Long blockedUserId) {
        if (!contactRequestBlockRepository.existsByUserIdAndBlockedId(userId, blockedUserId)) {
            throw new BusinessException(ContactErrorCode.CONTACT_010);
        }
        contactRequestBlockRepository.deleteByUserIdAndBlockedId(userId, blockedUserId);
    }
}
