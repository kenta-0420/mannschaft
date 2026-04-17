package com.mannschaft.app.social.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationMapper;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.repository.NotificationRepository;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.SocialErrorCode;
import com.mannschaft.app.social.dto.FriendNotificationDeliveryResponse;
import com.mannschaft.app.social.dto.FriendNotificationSendRequest;
import com.mannschaft.app.social.repository.TeamFriendFolderMemberRepository;
import com.mannschaft.app.social.repository.TeamFriendFolderRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * フレンドチーム通知サービス（F01.5 Phase 2）。
 *
 * <p>
 * フレンドチームへの通知送信（POST /friend-notifications/send）と
 * 受信通知一覧取得（GET /friend-notifications）を担当する。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FriendNotificationService {

    private static final String PERM_MANAGE_FRIEND_TEAMS = "MANAGE_FRIEND_TEAMS";
    private static final String SCOPE_TEAM = "TEAM";
    private static final String DEFAULT_NOTIFICATION_TYPE = "FRIEND_ANNOUNCEMENT";

    private final AccessControlService accessControlService;
    private final NotificationService notificationService;
    private final NotificationRepository notificationRepository;
    private final NotificationMapper notificationMapper;
    private final TeamFriendRepository teamFriendRepository;
    private final TeamFriendFolderRepository teamFriendFolderRepository;
    private final TeamFriendFolderMemberRepository folderMemberRepository;
    private final UserRoleRepository userRoleRepository;

    /**
     * フレンドチーム経由で自チームに届いた通知一覧を取得する。
     * scope_type = FRIEND_TEAM, scope_id = teamId の通知を返す。
     *
     * @param teamId   受信チーム ID
     * @param userId   操作ユーザー ID
     * @param isRead   既読フィルタ（null = 全件, true/false = 絞り込み）
     * @param pageable ページング
     * @return 通知ページ
     */
    @Transactional(readOnly = true)
    public Page<NotificationResponse> listFriendNotifications(
            Long teamId, Long userId, Boolean isRead, Pageable pageable) {

        accessControlService.checkPermission(userId, teamId, SCOPE_TEAM, PERM_MANAGE_FRIEND_TEAMS);

        Page<com.mannschaft.app.notification.entity.NotificationEntity> page;
        if (isRead != null) {
            page = notificationRepository.findByScopeTypeAndScopeIdAndIsReadOrderByCreatedAtDesc(
                    NotificationScopeType.FRIEND_TEAM.name(), teamId, isRead, pageable);
        } else {
            page = notificationRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    NotificationScopeType.FRIEND_TEAM.name(), teamId, pageable);
        }
        return page.map(notificationMapper::toNotificationResponse);
    }

    /**
     * フレンドチーム（またはフォルダ）へ通知を送信する。
     *
     * <p>
     * 処理フロー:
     * <ol>
     *   <li>権限チェック（MANAGE_FRIEND_TEAMS）</li>
     *   <li>送信先チーム ID リストを解決（FOLDER → フォルダメンバーから, TEAMS → リクエスト直接）</li>
     *   <li>全送信先がフレンド関係にあることを検証</li>
     *   <li>ADMIN/DEPUTY_ADMIN ユーザー ID を取得</li>
     *   <li>各ユーザーへ通知を作成</li>
     * </ol>
     * </p>
     *
     * @param sourceTeamId 送信元チーム ID
     * @param userId       操作ユーザー ID
     * @param request      送信リクエスト
     * @return 配信サマリ（202 Accepted 用）
     */
    @Transactional
    public FriendNotificationDeliveryResponse sendFriendNotification(
            Long sourceTeamId, Long userId, FriendNotificationSendRequest request) {

        // 1. 権限チェック
        accessControlService.checkPermission(userId, sourceTeamId, SCOPE_TEAM, PERM_MANAGE_FRIEND_TEAMS);

        // 2. 送信先チーム ID を解決
        List<Long> targetTeamIds = resolveTargetTeamIds(sourceTeamId, request);
        if (targetTeamIds.isEmpty()) {
            throw new BusinessException(SocialErrorCode.FRIEND_NOTIFICATION_NO_TARGETS);
        }

        // 3. フレンド関係の検証
        validateAllAreFriends(sourceTeamId, targetTeamIds);

        // 4. ADMIN/DEPUTY_ADMIN ユーザー ID を取得（送信先全体の集計用）
        List<Long> adminUserIds = userRoleRepository.findAdminUserIdsByTeamIds(targetTeamIds);

        // 5. 通知タイプと優先度の解決
        String notificationType = request.getNotificationType() != null
                ? request.getNotificationType()
                : DEFAULT_NOTIFICATION_TYPE;
        NotificationPriority priority = resolvePriority(request.getPriority());

        // 6. 各ターゲットチームの管理者に通知を作成
        //    scope_id = ターゲットチーム ID（受信側チームの管理者フィード）
        for (Long targetTeamId : targetTeamIds) {
            List<Long> teamAdminIds = userRoleRepository.findAdminUserIdsByTeamIds(List.of(targetTeamId));
            for (Long adminUserId : teamAdminIds) {
                notificationService.createNotification(
                        adminUserId,
                        notificationType,
                        priority,
                        request.getTitle(),
                        request.getBody(),
                        "FRIEND_TEAM",
                        sourceTeamId,
                        NotificationScopeType.FRIEND_TEAM,
                        targetTeamId,
                        null,
                        userId
                );
            }
        }

        String deliveryId = generateDeliveryId();
        log.info("フレンド通知送信完了: sourceTeam={}, targets={}, admins={}",
                sourceTeamId, targetTeamIds.size(), adminUserIds.size());

        return FriendNotificationDeliveryResponse.builder()
                .deliveryId(deliveryId)
                .queuedTeamsCount(targetTeamIds.size())
                .queuedAdminsCount(adminUserIds.size())
                .queuedAt(LocalDateTime.now())
                .build();
    }

    private List<Long> resolveTargetTeamIds(Long sourceTeamId,
                                             FriendNotificationSendRequest request) {
        return switch (request.getTargetType()) {
            case "FOLDER" -> {
                if (request.getTargetFolderId() == null) {
                    throw new BusinessException(SocialErrorCode.FRIEND_NOTIFICATION_FOLDER_ID_REQUIRED);
                }
                var folder = teamFriendFolderRepository
                        .findByIdAndOwnerTeamIdAndDeletedAtIsNull(
                                request.getTargetFolderId(), sourceTeamId)
                        .orElseThrow(() -> new BusinessException(SocialErrorCode.FRIEND_FOLDER_NOT_FOUND));
                var members = folderMemberRepository.findByFolderId(folder.getId());
                yield members.stream()
                        .map(m -> resolvePartnerTeamId(sourceTeamId, m.getTeamFriendId()))
                        .filter(id -> id != null)
                        .distinct()
                        .toList();
            }
            case "TEAMS" -> {
                if (request.getTargetTeamIds() == null || request.getTargetTeamIds().isEmpty()) {
                    throw new BusinessException(SocialErrorCode.FRIEND_NOTIFICATION_TEAM_IDS_REQUIRED);
                }
                yield request.getTargetTeamIds();
            }
            default -> throw new BusinessException(SocialErrorCode.FRIEND_NOTIFICATION_INVALID_TARGET_TYPE);
        };
    }

    private Long resolvePartnerTeamId(Long sourceTeamId, Long teamFriendId) {
        return teamFriendRepository.findById(teamFriendId)
                .map(tf -> tf.getTeamAId().equals(sourceTeamId) ? tf.getTeamBId() : tf.getTeamAId())
                .orElse(null);
    }

    private void validateAllAreFriends(Long sourceTeamId, List<Long> targetTeamIds) {
        for (Long targetTeamId : targetTeamIds) {
            boolean isFriend = teamFriendRepository.findByTeamAIdAndTeamBId(sourceTeamId, targetTeamId).isPresent()
                    || teamFriendRepository.findByTeamAIdAndTeamBId(targetTeamId, sourceTeamId).isPresent();
            if (!isFriend) {
                throw new BusinessException(SocialErrorCode.FRIEND_NOTIFICATION_TARGET_NOT_FRIEND);
            }
        }
    }

    private NotificationPriority resolvePriority(String priorityStr) {
        if (priorityStr == null) return NotificationPriority.NORMAL;
        return switch (priorityStr.toUpperCase()) {
            case "HIGH" -> NotificationPriority.HIGH;
            case "LOW" -> NotificationPriority.LOW;
            default -> NotificationPriority.NORMAL;
        };
    }

    private String generateDeliveryId() {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        int random = ThreadLocalRandom.current().nextInt(1000, 9999);
        return "frdl_" + timestamp + random;
    }
}
