package com.mannschaft.app.contact.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.contact.ContactErrorCode;
import com.mannschaft.app.contact.dto.ContactHandleResponse;
import com.mannschaft.app.contact.dto.HandleCheckResponse;
import com.mannschaft.app.contact.dto.HandleSearchResponse;
import com.mannschaft.app.contact.dto.UpdateHandleRequest;
import com.mannschaft.app.contact.repository.ContactRequestRepository;
import com.mannschaft.app.user.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * @ハンドル管理サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ContactHandleService {

    private static final Set<String> RESERVED_HANDLES = Set.of(
            "admin", "system", "support", "mannschaft", "api", "help",
            "info", "null", "undefined", "me", "anonymous", "moderator",
            "bot", "official"
    );

    private final UserRepository userRepository;
    private final UserBlockRepository userBlockRepository;
    private final ContactRequestRepository contactRequestRepository;

    /**
     * 自分のハンドル情報を取得する。
     */
    public ContactHandleResponse getMyHandle(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_015));
        return ContactHandleResponse.builder()
                .contactHandle(user.getContactHandle())
                .handleSearchable(user.getHandleSearchable())
                .contactApprovalRequired(user.getContactApprovalRequired())
                .onlineVisibility(user.getOnlineVisibility().name())
                .build();
    }

    /**
     * @ハンドルを設定・変更する。
     */
    @Transactional
    public ContactHandleResponse updateHandle(Long userId, UpdateHandleRequest req) {
        String handle = req.getContactHandle();

        // 予約語チェック
        if (handle != null && RESERVED_HANDLES.contains(handle.toLowerCase())) {
            throw new BusinessException(ContactErrorCode.CONTACT_003);
        }

        // 重複チェック（自分以外）
        if (handle != null && userRepository.existsByContactHandleAndIdNot(handle, userId)) {
            throw new BusinessException(ContactErrorCode.CONTACT_002);
        }

        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_015));
        user.updateContactHandle(handle);

        return ContactHandleResponse.builder()
                .contactHandle(user.getContactHandle())
                .handleSearchable(user.getHandleSearchable())
                .contactApprovalRequired(user.getContactApprovalRequired())
                .onlineVisibility(user.getOnlineVisibility().name())
                .build();
    }

    /**
     * ハンドルの使用可否を確認する（リアルタイム重複チェック用）。
     */
    public HandleCheckResponse checkHandleAvailability(Long currentUserId, String handle) {
        // 形式チェック
        if (!handle.matches("^[a-z0-9_-]{3,30}$")) {
            return new HandleCheckResponse(false);
        }
        // 予約語チェック
        if (RESERVED_HANDLES.contains(handle.toLowerCase())) {
            return new HandleCheckResponse(false);
        }
        // 重複チェック（自分以外）
        boolean taken = userRepository.existsByContactHandleAndIdNot(handle, currentUserId);
        return new HandleCheckResponse(!taken);
    }

    /**
     * @ハンドルでユーザーを検索する。
     * ブロック関係・検索不可設定の場合は found=false を返す（サイレント）。
     */
    public HandleSearchResponse searchByHandle(Long currentUserId, String handle) {
        // ユーザーを検索（インデックス利用）
        UserEntity target = userRepository.findByContactHandle(handle).orElse(null);

        // 存在しない場合
        if (target == null) {
            return HandleSearchResponse.builder().found(false).build();
        }

        // 検索不可設定の場合
        if (!target.getHandleSearchable()) {
            return HandleSearchResponse.builder().found(false).build();
        }

        // ブロック関係（双方向）の場合
        if (userBlockRepository.existsByBlockerIdAndBlockedId(currentUserId, target.getId())
                || userBlockRepository.existsByBlockedIdAndBlockerId(target.getId(), currentUserId)) {
            return HandleSearchResponse.builder().found(false).build();
        }

        boolean isContact = contactRequestRepository.isContact(currentUserId, target.getId());
        boolean hasPending = contactRequestRepository
                .findByRequesterIdAndTargetIdAndStatus(currentUserId, target.getId(), "PENDING")
                .isPresent();

        return HandleSearchResponse.builder()
                .found(true)
                .userId(target.getId())
                .displayName(target.getDisplayName())
                .contactHandle(target.getContactHandle())
                .avatarUrl(target.getAvatarUrl())
                .isContact(isContact)
                .hasPendingRequest(hasPending)
                .contactApprovalRequired(target.getContactApprovalRequired())
                .build();
    }
}
