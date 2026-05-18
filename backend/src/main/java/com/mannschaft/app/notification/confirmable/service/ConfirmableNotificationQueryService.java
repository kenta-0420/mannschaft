package com.mannschaft.app.notification.confirmable.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationRecipientEntity;
import com.mannschaft.app.notification.confirmable.entity.UnconfirmedVisibility;
import com.mannschaft.app.notification.confirmable.error.ConfirmableNotificationErrorCode;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRecipientRepository;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

/**
 * F04.9 確認通知の参照・一覧取得を担当するサービス（読込専用）。
 *
 * <p>ファサード {@link ConfirmableNotificationService} から委譲される参照系処理を実装する。
 * 全メソッドが {@code @Transactional(readOnly=true)} で動作する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ConfirmableNotificationQueryService {

    private final ConfirmableNotificationRepository notificationRepository;
    private final ConfirmableNotificationRecipientRepository recipientRepository;

    /**
     * 確認通知の詳細を取得する。
     *
     * @param notificationId 確認通知ID
     * @return 確認通知エンティティ
     */
    public ConfirmableNotificationEntity getDetail(Long notificationId) {
        return notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));
    }

    /**
     * 確認通知の受信者一覧を取得する（ADMIN+ 用・全件）。
     *
     * <p>呼び出し側で ADMIN+ 権限チェック済みであること。
     * F04.9 Phase D の MEMBER 視点アクセスは
     * {@link #getRecipientsForMember(Long, Long)} を使用すること。</p>
     *
     * @param notificationId 確認通知ID
     * @return 受信者エンティティリスト（除外者・確認済みも含む全件）
     */
    public List<ConfirmableNotificationRecipientEntity> getRecipients(Long notificationId) {
        // 通知の存在確認
        if (!notificationRepository.existsById(notificationId)) {
            throw new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND);
        }
        return recipientRepository.findByConfirmableNotificationId(notificationId);
    }

    /**
     * MEMBER 視点で確認通知の未確認者一覧を取得する（F04.9 Phase D）。
     *
     * <p>認可判定:
     * <ol>
     *   <li>通知が存在し、{@code unconfirmedVisibility = ALL_MEMBERS} であること</li>
     *   <li>呼び出しユーザーが当通知の受信者であること（除外者は不可）</li>
     * </ol>
     * いずれかを満たさない場合は {@link CommonErrorCode#COMMON_002}（403）を投げる。</p>
     *
     * <p>戻り値は <b>未確認かつ非除外</b> の受信者のみ。Mapper の
     * {@code toRecipientPublicResponseList} で confirmedAt / confirmedVia / excludedAt をマスクして返すこと。</p>
     *
     * @param notificationId  確認通知ID
     * @param requesterUserId リクエスト元ユーザーID
     * @return 未確認受信者エンティティリスト（マスク前）
     */
    public List<ConfirmableNotificationRecipientEntity> getRecipientsForMember(
            Long notificationId, Long requesterUserId) {
        // 通知の存在確認
        ConfirmableNotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new BusinessException(ConfirmableNotificationErrorCode.NOT_FOUND));

        // 公開範囲チェック: ALL_MEMBERS 以外は 403
        if (notification.getUnconfirmedVisibility() != UnconfirmedVisibility.ALL_MEMBERS) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 呼び出しユーザーが受信者かつ非除外であることを確認
        List<ConfirmableNotificationRecipientEntity> allRecipients =
                recipientRepository.findByConfirmableNotificationId(notificationId);
        boolean isRecipient = allRecipients.stream()
                .anyMatch(r -> r.getUser().getId().equals(requesterUserId) && !r.isExcluded());
        if (!isRecipient) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 未確認かつ非除外の受信者のみ返す
        return allRecipients.stream()
                .filter(r -> !Boolean.TRUE.equals(r.getIsConfirmed()))
                .filter(r -> !r.isExcluded())
                .collect(Collectors.toList());
    }

    /**
     * スコープ内の確認通知一覧を取得する（作成日時降順）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 確認通知エンティティリスト
     */
    public List<ConfirmableNotificationEntity> listByScope(ScopeType scopeType, Long scopeId) {
        return notificationRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(scopeType, scopeId);
    }

    /**
     * ユーザーの未確認通知一覧を取得する（受信者視点）。
     *
     * @param userId ユーザーID
     * @return 未確認受信者エンティティリスト
     */
    public List<ConfirmableNotificationRecipientEntity> listPending(Long userId) {
        return recipientRepository.findByUserIdAndIsConfirmedFalseAndExcludedAtIsNull(userId);
    }
}
