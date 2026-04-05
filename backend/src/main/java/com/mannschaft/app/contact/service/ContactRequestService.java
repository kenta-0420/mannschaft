package com.mannschaft.app.contact.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.contact.ContactErrorCode;
import com.mannschaft.app.contact.dto.ContactRequestResponse;
import com.mannschaft.app.contact.dto.ContactUserDto;
import com.mannschaft.app.contact.dto.SendContactRequestBody;
import com.mannschaft.app.contact.dto.SendContactRequestResponse;
import com.mannschaft.app.contact.entity.ContactRequestEntity;
import com.mannschaft.app.contact.repository.ContactRequestBlockRepository;
import com.mannschaft.app.contact.repository.ContactRequestRepository;
import com.mannschaft.app.dashboard.FolderItemType;
import com.mannschaft.app.dashboard.repository.ChatContactFolderItemRepository;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.user.repository.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 連絡先申請サービス。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ContactRequestService {

    private final ContactRequestRepository contactRequestRepository;
    private final ContactRequestBlockRepository contactRequestBlockRepository;
    private final UserBlockRepository userBlockRepository;
    private final UserRepository userRepository;
    private final ChatContactFolderItemRepository folderItemRepository;
    private final ContactService contactService;
    private final NotificationService notificationService;

    /**
     * 連絡先申請を送信する。
     * ブロック・事前拒否の場合もサイレントに 200 OK を返す（セキュリティ）。
     */
    @Transactional
    public SendContactRequestResponse sendRequest(Long requesterId, SendContactRequestBody req) {
        Long targetId = req.getTargetUserId();

        // 自分自身チェック
        if (requesterId.equals(targetId)) {
            throw new BusinessException(ContactErrorCode.CONTACT_004);
        }

        // 既に連絡先チェック
        if (contactRequestRepository.isContact(requesterId, targetId)) {
            throw new BusinessException(ContactErrorCode.CONTACT_005);
        }

        // サイレントブロックチェック（ブロック関係あり → 200 OK を返すが INSERT しない）
        boolean blocked = userBlockRepository.existsByBlockerIdAndBlockedId(requesterId, targetId)
                || userBlockRepository.existsByBlockedIdAndBlockerId(targetId, requesterId);
        if (blocked) {
            return new SendContactRequestResponse(null, "PENDING");
        }

        // 申請事前拒否チェック（サイレント）
        boolean requestBlocked = contactRequestBlockRepository.existsByBlockedIdAndUserId(requesterId, targetId);
        if (requestBlocked) {
            return new SendContactRequestResponse(null, "PENDING");
        }

        // REJECTED後72時間以内の再申請チェック
        if (contactRequestRepository.hasRecentRejection(requesterId, targetId,
                LocalDateTime.now().minusHours(72))) {
            throw new BusinessException(ContactErrorCode.CONTACT_008);
        }

        // 24時間以内の同一相手への申請チェック
        if (contactRequestRepository.hasRecentRequest(requesterId, targetId,
                LocalDateTime.now().minusHours(24))) {
            throw new BusinessException(ContactErrorCode.CONTACT_009);
        }

        // 対象ユーザーの承認設定を確認
        UserEntity target = userRepository.findById(targetId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_015));

        String initialStatus;
        if (!target.getContactApprovalRequired()) {
            // 自動承認
            initialStatus = "ACCEPTED";
        } else {
            initialStatus = "PENDING";
        }

        // 有効期限設定（30日）
        LocalDateTime expiresAt = "PENDING".equals(initialStatus)
                ? LocalDateTime.now().plusDays(30) : null;

        ContactRequestEntity request = ContactRequestEntity.builder()
                .requesterId(requesterId)
                .targetId(targetId)
                .status(initialStatus)
                .sourceType(req.getSourceType())
                .message(req.getMessage())
                .expiresAt(expiresAt)
                .build();
        ContactRequestEntity saved = contactRequestRepository.save(request);

        if ("ACCEPTED".equals(initialStatus)) {
            // 即時連絡先追加
            contactService.addContactBidirectional(requesterId, targetId);
        } else {
            // 申請受信通知
            sendRequestReceivedNotification(requesterId, targetId, saved.getId());
        }

        return new SendContactRequestResponse(saved.getId(), saved.getStatus());
    }

    /**
     * 受信申請一覧（PENDING のみ）を取得する。
     */
    public List<ContactRequestResponse> listReceivedRequests(Long userId) {
        List<ContactRequestEntity> requests = contactRequestRepository
                .findByTargetIdAndStatusOrderByCreatedAtDesc(userId, "PENDING");
        return toResponseList(requests);
    }

    /**
     * 送信済み申請一覧（PENDING のみ）を取得する。
     */
    public List<ContactRequestResponse> listSentRequests(Long userId) {
        List<ContactRequestEntity> requests = contactRequestRepository
                .findByRequesterIdAndStatusOrderByCreatedAtDesc(userId, "PENDING");
        return toResponseList(requests);
    }

    /**
     * 申請を承認する。
     */
    @Transactional
    public void acceptRequest(Long userId, Long requestId) {
        ContactRequestEntity request = contactRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_006));

        // 自分が申請先であることを確認
        if (!userId.equals(request.getTargetId())) {
            throw new BusinessException(ContactErrorCode.CONTACT_007);
        }
        if (!request.isPending()) {
            throw new BusinessException(ContactErrorCode.CONTACT_006);
        }

        request.accept();

        // 双方の連絡先フォルダに追加
        contactService.addContactBidirectional(request.getRequesterId(), request.getTargetId());

        // 申請者への承認通知
        sendRequestAcceptedNotification(request.getTargetId(), request.getRequesterId(), requestId);
    }

    /**
     * 申請を拒否する。
     * 申請者への通知は行わない（拒否されたことを知らせない）。
     */
    @Transactional
    public void rejectRequest(Long userId, Long requestId) {
        ContactRequestEntity request = contactRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_006));

        if (!userId.equals(request.getTargetId())) {
            throw new BusinessException(ContactErrorCode.CONTACT_007);
        }
        if (!request.isPending()) {
            throw new BusinessException(ContactErrorCode.CONTACT_006);
        }

        request.reject();
        // 意図的に申請者への通知なし
    }

    /**
     * 自分が送った申請をキャンセルする。
     */
    @Transactional
    public void cancelRequest(Long userId, Long requestId) {
        ContactRequestEntity request = contactRequestRepository.findById(requestId)
                .orElseThrow(() -> new BusinessException(ContactErrorCode.CONTACT_006));

        if (!userId.equals(request.getRequesterId())) {
            throw new BusinessException(ContactErrorCode.CONTACT_007);
        }
        if (!request.isPending()) {
            throw new BusinessException(ContactErrorCode.CONTACT_006);
        }

        request.cancel();
    }

    private List<ContactRequestResponse> toResponseList(List<ContactRequestEntity> requests) {
        if (requests.isEmpty()) return List.of();

        List<Long> userIds = requests.stream()
                .flatMap(r -> List.of(r.getRequesterId(), r.getTargetId()).stream())
                .distinct().toList();
        Map<Long, UserEntity> userMap = userRepository.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserEntity::getId, u -> u));

        return requests.stream().map(r -> {
            UserEntity requester = userMap.get(r.getRequesterId());
            UserEntity target = userMap.get(r.getTargetId());
            return ContactRequestResponse.builder()
                    .id(r.getId())
                    .requester(toUserDto(requester, r.getRequesterId()))
                    .target(toUserDto(target, r.getTargetId()))
                    .status(r.getStatus())
                    .message(r.getMessage())
                    .sourceType(r.getSourceType())
                    .createdAt(r.getCreatedAt())
                    .build();
        }).toList();
    }

    private ContactUserDto toUserDto(UserEntity user, Long fallbackId) {
        if (user == null) return ContactUserDto.builder().id(fallbackId).build();
        return ContactUserDto.builder()
                .id(user.getId())
                .displayName(user.getDisplayName())
                .contactHandle(user.getContactHandle())
                .avatarUrl(user.getAvatarUrl())
                .build();
    }

    private void sendRequestReceivedNotification(Long requesterId, Long targetId, Long requestId) {
        UserEntity requester = userRepository.findById(requesterId).orElse(null);
        String requesterName = requester != null ? requester.getDisplayName() : "ユーザー";
        notificationService.createNotification(
                targetId,
                "CONTACT_REQUEST_RECEIVED",
                NotificationPriority.NORMAL,
                "連絡先申請",
                requesterName + " さんから連絡先申請が届きました",
                "CONTACT_REQUEST",
                requestId,
                NotificationScopeType.PERSONAL,
                targetId,
                "/settings/contact-requests",
                requesterId
        );
    }

    private void sendRequestAcceptedNotification(Long actorId, Long targetId, Long requestId) {
        UserEntity actor = userRepository.findById(actorId).orElse(null);
        String actorName = actor != null ? actor.getDisplayName() : "ユーザー";
        notificationService.createNotification(
                targetId,
                "CONTACT_REQUEST_ACCEPTED",
                NotificationPriority.NORMAL,
                "連絡先申請が承認されました",
                actorName + " さんが連絡先申請を承認しました",
                "CONTACT_REQUEST",
                requestId,
                NotificationScopeType.PERSONAL,
                targetId,
                "/chat",
                actorId
        );
    }
}
