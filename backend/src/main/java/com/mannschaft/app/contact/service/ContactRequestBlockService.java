package com.mannschaft.app.contact.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.storage.MediaUrlResolver;
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
    private final MediaUrlResolver mediaUrlResolver;
    private final ContactHandleService contactHandleService;

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
                    .blockedUser(toVisibleUserDto(userId, u, block.getBlockedId()))
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
                .blockedUser(toVisibleUserDto(userId, u, targetUserId))
                .createdAt(saved.getCreatedAt())
                .build();
    }

    /**
     * 事前拒否対象ユーザーの身元情報を、要求者が閲覧資格を持つ場合に限定して DTO に詰める。
     * 閲覧資格の判定は {@link ContactHandleService#isIdentityVisibleTo} と共有し、
     * 資格がない・対象ユーザーが存在しない場合は識別子のみを返す。
     */
    private ContactUserDto toVisibleUserDto(Long viewerId, UserEntity u, Long fallbackId) {
        if (u == null || !contactHandleService.isIdentityVisibleTo(viewerId, u)) {
            return ContactUserDto.builder().id(fallbackId).build();
        }
        return ContactUserDto.builder()
                .id(u.getId())
                .fullName(u.getLastName() + " " + u.getFirstName())
                .contactHandle(u.getContactHandle())
                .avatarUrl(mediaUrlResolver.resolve(u.getAvatarUrl()))
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
