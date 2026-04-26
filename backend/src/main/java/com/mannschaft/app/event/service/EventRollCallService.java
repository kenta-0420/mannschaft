package com.mannschaft.app.event.service;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.event.CheckinType;
import com.mannschaft.app.event.EventErrorCode;
import com.mannschaft.app.event.dto.RollCallCandidateResponse;
import com.mannschaft.app.event.dto.RollCallEntryRequest;
import com.mannschaft.app.event.dto.RollCallSessionRequest;
import com.mannschaft.app.event.dto.RollCallSessionResponse;
import com.mannschaft.app.event.entity.EventCheckinEntity;
import com.mannschaft.app.event.entity.EventRsvpResponseEntity;
import com.mannschaft.app.event.repository.EventCheckinRepository;
import com.mannschaft.app.event.repository.EventRsvpResponseRepository;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.family.service.CareEventNotificationService;
import com.mannschaft.app.family.service.CareLinkService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 主催者点呼サービス。F03.12 §14 主催者点呼（一括チェックイン）機能。
 *
 * <p>スマホを持たない子供・高齢者向けに、主催者が参加予定者一覧を見ながら
 * 到着/遅刻/欠席を一括記録し、見守り者へ自動通知する。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class EventRollCallService {

    /** 点呼ステータス定数：出席 */
    private static final String STATUS_PRESENT = "PRESENT";
    /** 点呼ステータス定数：欠席 */
    private static final String STATUS_ABSENT = "ABSENT";
    /** 点呼ステータス定数：遅刻 */
    private static final String STATUS_LATE = "LATE";

    private final EventRsvpResponseRepository rsvpResponseRepository;
    private final EventCheckinRepository checkinRepository;
    private final UserCareLinkRepository careLinkRepository;
    private final UserRepository userRepository;
    private final CareLinkService careLinkService;
    private final CareEventNotificationService careEventNotificationService;

    // =========================================================
    // 公開 API
    // =========================================================

    /**
     * 点呼候補者一覧を取得する。
     *
     * <p>RSVP=ATTENDING/MAYBE の参加予定者に対して、
     * ケアリンク情報・既存チェックイン状態を一括付与して返す。N+1 防止のため IN 句を使用する。</p>
     *
     * @param eventId        イベントID
     * @param teamId         チームID（認可チェック用、現在は使用しない）
     * @param operatorUserId オペレーターのユーザーID
     * @return 点呼候補者レスポンスリスト
     */
    public List<RollCallCandidateResponse> getRollCallCandidates(Long eventId, Long teamId, Long operatorUserId) {
        // ATTENDING / MAYBE の RSVP 回答を取得
        List<EventRsvpResponseEntity> rsvps = rsvpResponseRepository.findAttendingOrMaybeByEventId(eventId);
        if (rsvps.isEmpty()) {
            return List.of();
        }

        // 候補者ユーザーIDを収集
        List<Long> userIds = rsvps.stream().map(EventRsvpResponseEntity::getUserId).toList();

        // ケアリンク情報を IN 句で一括取得（N+1 防止）
        List<UserCareLinkEntity> careLinks = careLinkRepository
                .findByCareRecipientUserIdInAndStatus(userIds, CareLinkStatus.ACTIVE);

        // ユーザーID → 見守り者数 のマップを構築
        Map<Long, Long> watcherCountMap = new HashMap<>();
        for (UserCareLinkEntity link : careLinks) {
            watcherCountMap.merge(link.getCareRecipientUserId(), 1L, Long::sum);
        }

        // ユーザーID → ケア対象フラグ のセット（ケアリンクが1件以上あれば対象）
        Set<Long> underCareUserIds = new HashSet<>(watcherCountMap.keySet());

        // ユーザー表示名・アバター情報をまとめて取得（N+1 だが件数は点呼候補者数に限定されるため許容）
        Map<Long, UserEntity> userMap = new HashMap<>();
        for (Long uid : userIds) {
            userRepository.findById(uid).ifPresent(u -> userMap.put(uid, u));
        }

        // 各ユーザーの既存チェックイン状態を確認
        Map<Long, Boolean> checkinMap = new HashMap<>();
        for (Long userId : userIds) {
            checkinMap.put(userId, checkinRepository.existsByEventIdAndUserId(eventId, userId));
        }

        // レスポンス構築
        List<RollCallCandidateResponse> results = new ArrayList<>();
        for (EventRsvpResponseEntity rsvp : rsvps) {
            Long userId = rsvp.getUserId();
            UserEntity user = userMap.get(userId);
            String displayName = user != null ? user.getDisplayName() : "（不明）";
            String avatarUrl = user != null ? user.getAvatarUrl() : null;
            boolean isUnderCare = underCareUserIds.contains(userId);
            int watcherCount = watcherCountMap.getOrDefault(userId, 0L).intValue();
            boolean alreadyCheckedIn = checkinMap.getOrDefault(userId, false);

            results.add(RollCallCandidateResponse.builder()
                    .userId(userId)
                    .displayName(displayName)
                    .avatarUrl(avatarUrl)
                    .rsvpStatus(rsvp.getResponse())
                    .isAlreadyCheckedIn(alreadyCheckedIn)
                    .isUnderCare(isUnderCare)
                    .watcherCount(watcherCount)
                    .build());
        }

        log.debug("点呼候補者一覧取得: eventId={}, 件数={}", eventId, results.size());
        return results;
    }

    /**
     * 点呼セッションを一括登録する。
     *
     * <p>1 トランザクション内で全エントリを処理する。
     * 冪等性: 同一 rollCallSessionId + userId が既存の場合は UPDATE する。
     * ケア対象者かつ PRESENT の場合は保護者通知を送信する。</p>
     *
     * @param eventId        イベントID
     * @param teamId         チームID（認可チェック用）
     * @param operatorUserId オペレーターのユーザーID
     * @param request        点呼セッションリクエスト
     * @return 点呼セッションレスポンス
     */
    @Transactional
    public RollCallSessionResponse submitRollCall(Long eventId, Long teamId,
                                                   Long operatorUserId, RollCallSessionRequest request) {
        String sessionId = request.getRollCallSessionId();
        List<RollCallEntryRequest> entries = request.getEntries();

        int createdCount = 0;
        int updatedCount = 0;
        int guardianNotificationsSent = 0;
        List<String> guardianSetupWarnings = new ArrayList<>();

        for (RollCallEntryRequest entry : entries) {
            Long userId = entry.getUserId();

            // ユーザー表示名を取得（警告メッセージ用）
            String displayName = userRepository.findById(userId)
                    .map(UserEntity::getDisplayName)
                    .orElse("userId=" + userId);

            // 冪等チェック: 同一セッション・ユーザーの既存レコードを検索
            Optional<EventCheckinEntity> existingOpt =
                    checkinRepository.findByEventIdAndRollCallSessionIdAndUserId(eventId, sessionId, userId);

            if (existingOpt.isPresent()) {
                // 既存レコードを上書き更新
                EventCheckinEntity existing = existingOpt.get();
                existing.updateRollCallResult(
                        resolveCheckinType(entry.getStatus()),
                        entry.getLateArrivalMinutes(),
                        entry.getAbsenceReason());
                checkinRepository.save(existing);
                updatedCount++;
                log.debug("点呼レコード上書き: eventId={}, userId={}, status={}", eventId, userId, entry.getStatus());
            } else {
                // 新規作成
                EventCheckinEntity checkin = buildCheckinEntity(eventId, sessionId, operatorUserId, userId, entry);
                checkinRepository.save(checkin);
                createdCount++;
                log.debug("点呼レコード新規作成: eventId={}, userId={}, status={}", eventId, userId, entry.getStatus());
            }

            // ケア対象者かつ PRESENT → 保護者通知
            if (STATUS_PRESENT.equals(entry.getStatus())) {
                boolean isUnderCare = careLinkService.isUnderCare(userId);
                if (isUnderCare && request.isNotifyGuardiansImmediately()) {
                    // 見守り者数チェック（警告対象の判定にも使用）
                    long watcherCount = careLinkRepository
                            .countByCareRecipientUserIdAndStatusIn(userId, List.of(CareLinkStatus.ACTIVE));
                    if (watcherCount == 0) {
                        // ケア対象者だが見守り者未設定 → 警告収集（通知送信はスキップ）
                        guardianSetupWarnings.add(displayName + "（見守り者未設定）");
                        log.warn("ケア対象者の見守り者未設定: userId={}, displayName={}", userId, displayName);
                    } else {
                        // 保護者通知を送信
                        careEventNotificationService.notifyCheckin(userId, eventId);
                        guardianNotificationsSent++;
                        log.info("点呼チェックイン保護者通知送信: eventId={}, userId={}", eventId, userId);
                    }
                }
            } else if (STATUS_ABSENT.equals(entry.getStatus())) {
                // ABSENT の場合、ケア対象者で見守り者未設定なら警告を収集
                boolean isUnderCare = careLinkService.isUnderCare(userId);
                if (isUnderCare) {
                    long watcherCount = careLinkRepository
                            .countByCareRecipientUserIdAndStatusIn(userId, List.of(CareLinkStatus.ACTIVE));
                    if (watcherCount == 0) {
                        guardianSetupWarnings.add(displayName + "（見守り者未設定）");
                    }
                }
                // ABSENT は notifyCheckin を呼ばない（§14.4 設計通り）
            }
        }

        log.info("点呼セッション完了: eventId={}, sessionId={}, created={}, updated={}, notified={}",
                eventId, sessionId, createdCount, updatedCount, guardianNotificationsSent);

        return RollCallSessionResponse.builder()
                .rollCallSessionId(sessionId)
                .createdCount(createdCount)
                .updatedCount(updatedCount)
                .guardianNotificationsSent(guardianNotificationsSent)
                .guardianSetupWarnings(guardianSetupWarnings)
                .build();
    }

    /**
     * 点呼セッション一覧を取得する（主催者向け履歴）。
     *
     * @param eventId イベントID
     * @return 点呼セッションIDリスト
     */
    public List<String> getRollCallSessions(Long eventId) {
        return checkinRepository.findDistinctRollCallSessionIdsByEventId(eventId);
    }

    /**
     * 点呼結果を個別修正する（誤チェックの訂正）。
     *
     * @param eventId        イベントID
     * @param userId         対象ユーザーID
     * @param entry          修正内容
     * @param operatorUserId オペレーターのユーザーID
     */
    @Transactional
    public void patchRollCallEntry(Long eventId, Long userId, RollCallEntryRequest entry, Long operatorUserId) {
        // 最新の点呼レコードを取得（rollCallUserId + eventId で降順1件目）
        EventCheckinEntity target = checkinRepository
                .findRollCallByEventIdAndUserId(eventId, userId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException(EventErrorCode.CHECKIN_NOT_FOUND));

        target.updateRollCallResult(
                resolveCheckinType(entry.getStatus()),
                entry.getLateArrivalMinutes(),
                entry.getAbsenceReason());
        checkinRepository.save(target);
        log.info("点呼結果個別修正: eventId={}, userId={}, status={}, operatorUserId={}",
                eventId, userId, entry.getStatus(), operatorUserId);
    }

    // =========================================================
    // プライベートヘルパー
    // =========================================================

    /**
     * 点呼ステータス文字列から CheckinType を解決する。
     * PRESENT / LATE / ABSENT いずれも ROLL_CALL を使用する。
     */
    private CheckinType resolveCheckinType(String status) {
        if (STATUS_PRESENT.equals(status) || STATUS_LATE.equals(status) || STATUS_ABSENT.equals(status)) {
            return CheckinType.ROLL_CALL;
        }
        throw new BusinessException(EventErrorCode.CHECKIN_NOT_FOUND);
    }

    /**
     * エントリ情報から EventCheckinEntity を生成する。
     */
    private EventCheckinEntity buildCheckinEntity(Long eventId, String sessionId,
                                                   Long operatorUserId, Long userId,
                                                   RollCallEntryRequest entry) {
        return EventCheckinEntity.builder()
                .eventId(eventId)
                .ticketId(null)         // 点呼チェックインはチケット不要
                .rollCallUserId(userId) // 点呼対象ユーザーを直接格納
                .checkinType(CheckinType.ROLL_CALL_BATCH)
                .checkedInBy(operatorUserId)
                .rollCallSessionId(sessionId)
                .lateArrivalMinutes(STATUS_LATE.equals(entry.getStatus())
                        ? entry.getLateArrivalMinutes() : null)
                .absenceReason(STATUS_ABSENT.equals(entry.getStatus())
                        ? entry.getAbsenceReason() : null)
                .build();
    }
}
