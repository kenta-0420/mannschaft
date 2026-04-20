package com.mannschaft.app.committee.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.committee.dto.CommitteeDistributeRequest;
import com.mannschaft.app.committee.entity.CommitteeDistributionLogEntity;
import com.mannschaft.app.committee.entity.CommitteeEntity;
import com.mannschaft.app.committee.entity.CommitteeMemberEntity;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.entity.CommitteeStatus;
import com.mannschaft.app.committee.entity.ConfirmationMode;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import com.mannschaft.app.committee.repository.CommitteeDistributionLogRepository;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationEntity;
import com.mannschaft.app.notification.confirmable.entity.ConfirmableNotificationPriority;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.social.announcement.AnnouncementFeedEntity;
import com.mannschaft.app.social.announcement.AnnouncementFeedService;
import com.mannschaft.app.social.announcement.AnnouncementScopeType;
import com.mannschaft.app.social.announcement.AnnouncementSourceType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F04.10 委員会伝達処理サービス。
 *
 * <p>委員会からの伝達（お知らせ配信・確認通知送信）の実行と
 * 伝達処理ログの管理を担う。</p>
 *
 * <p><b>認可モデル</b>:
 * <ul>
 *   <li>伝達実行 ({@link #distribute}): CHAIR / VICE_CHAIR / SECRETARY のいずれか</li>
 *   <li>履歴一覧 ({@link #listDistributions}): 委員会メンバーのみ</li>
 *   <li>履歴詳細 ({@link #getDistribution}): 委員会メンバーのみ</li>
 * </ul>
 * </p>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommitteeDistributionService {

    private final CommitteeRepository committeeRepository;
    private final CommitteeMemberRepository committeeMemberRepository;
    private final CommitteeDistributionLogRepository distributionLogRepository;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;
    private final AnnouncementFeedService announcementFeedService;
    private final ConfirmableNotificationService confirmableNotificationService;

    // ========================================
    // 伝達処理
    // ========================================

    /**
     * 伝達を実行し、処理ログを保存して返す。
     *
     * <p>処理フロー:
     * <ol>
     *   <li>委員会の存在確認</li>
     *   <li>認可チェック: CHAIR / VICE_CHAIR / SECRETARY のいずれか</li>
     *   <li>DRAFT 状態チェック（配信不可）</li>
     *   <li>announcementEnabled = true の場合: AnnouncementFeed を生成</li>
     *   <li>confirmationMode != NONE の場合: ConfirmableNotification を生成</li>
     *   <li>CommitteeDistributionLogEntity を保存して返す</li>
     * </ol>
     * </p>
     *
     * @param committeeId   委員会 ID
     * @param request       伝達リクエスト
     * @param currentUserId 実行者ユーザー ID
     * @return 保存された伝達処理ログエンティティ
     */
    @Transactional
    public CommitteeDistributionLogEntity distribute(
            Long committeeId,
            CommitteeDistributeRequest request,
            Long currentUserId) {

        // 1. 委員会の存在確認
        CommitteeEntity committee = committeeRepository.findById(committeeId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));

        // 2. 認可チェック: CHAIR / VICE_CHAIR / SECRETARY のいずれか
        if (!hasDistributionRole(committeeId, currentUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 3. DRAFT 状態は配信不可
        if (CommitteeStatus.DRAFT.equals(committee.getStatus())) {
            throw new BusinessException(CommitteeErrorCode.DRAFT_CANNOT_DISTRIBUTE);
        }

        // 4. お知らせフィード生成
        Long savedDistributionLogId = null; // 先に保存してからフィード生成に使う（後で置き換え）
        List<Long> feedIds;
        if (Boolean.TRUE.equals(request.getAnnouncementEnabled())) {
            // contentType に応じてソース種別を決定する
            // CUSTOM_MESSAGE → COMMITTEE_MINUTES、それ以外（集計結果等）→ COMMITTEE_DECISION
            AnnouncementSourceType sourceType = "CUSTOM_MESSAGE".equals(request.getContentType())
                    ? AnnouncementSourceType.COMMITTEE_MINUTES
                    : AnnouncementSourceType.COMMITTEE_DECISION;

            // 伝達ログを先に保存してソース ID として使う
            CommitteeDistributionLogEntity tempLog = CommitteeDistributionLogEntity.builder()
                    .committeeId(committeeId)
                    .contentType(request.getContentType())
                    .contentId(request.getContentId())
                    .customTitle(request.getCustomTitle())
                    .customBody(request.getCustomBody())
                    .targetScope(request.getTargetScope())
                    .announcementEnabled(request.getAnnouncementEnabled())
                    .confirmationMode(request.getConfirmationMode())
                    .confirmableNotificationId(null)
                    .announcementFeedIds(null)
                    .createdBy(currentUserId)
                    .build();
            CommitteeDistributionLogEntity preSaved = distributionLogRepository.save(tempLog);
            savedDistributionLogId = preSaved.getId();

            AnnouncementFeedEntity feed = announcementFeedService.createFromSource(
                    sourceType,
                    savedDistributionLogId,
                    AnnouncementScopeType.COMMITTEE,
                    committeeId,
                    currentUserId);
            feedIds = List.of(feed.getId());
            log.info("委員会伝達: お知らせフィード生成完了。committeeId={}, feedId={}, sourceType={}",
                    committeeId, feed.getId(), sourceType);
        } else {
            feedIds = List.of();
        }

        // 5. 確認通知生成
        Long confirmableNotificationId = null;
        if (!ConfirmationMode.NONE.equals(request.getConfirmationMode())) {
            // 現役の委員会メンバー全員を受信者とする
            List<Long> recipientUserIds = committeeMemberRepository
                    .findByCommitteeIdAndLeftAtIsNull(committeeId)
                    .stream()
                    .map(CommitteeMemberEntity::getUserId)
                    .toList();

            if (!recipientUserIds.isEmpty()) {
                // タイトル・本文はカスタムタイトル／本文を優先する
                String notifTitle = request.getCustomTitle() != null && !request.getCustomTitle().isBlank()
                        ? request.getCustomTitle()
                        : committee.getName() + " より伝達があります";
                String notifBody = request.getCustomBody();

                ConfirmableNotificationEntity confirmable = confirmableNotificationService.send(
                        ScopeType.COMMITTEE,
                        committeeId,
                        notifTitle,
                        notifBody,
                        ConfirmableNotificationPriority.NORMAL,
                        request.getConfirmationDeadlineAt(),
                        null,
                        null,
                        null,
                        null,
                        currentUserId,
                        recipientUserIds);
                confirmableNotificationId = confirmable.getId();
                log.info("委員会伝達: 確認通知生成完了。committeeId={}, notificationId={}, recipientCount={}",
                        committeeId, confirmableNotificationId, recipientUserIds.size());
            } else {
                log.warn("委員会伝達: 確認通知の受信者が存在しません。committeeId={}", committeeId);
            }
        }

        // 6. announcement_feed_ids を JSON 文字列に変換
        String feedIdsJson = null;
        if (!feedIds.isEmpty()) {
            try {
                feedIdsJson = objectMapper.writeValueAsString(feedIds);
            } catch (JsonProcessingException e) {
                log.warn("announcementFeedIds の JSON シリアライズに失敗しました: feedIds={}", feedIds, e);
                feedIdsJson = "[]";
            }
        }

        // 7. 伝達処理ログを保存（お知らせ有効時は pre-save 済みログを更新、無効時は新規保存）
        CommitteeDistributionLogEntity saved;
        if (savedDistributionLogId != null) {
            // お知らせ有効: step 4 で事前保存したログに生成 ID を反映して更新する
            CommitteeDistributionLogEntity preSavedLog = distributionLogRepository.findById(savedDistributionLogId)
                    .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));
            preSavedLog.applyGeneratedIds(feedIdsJson, confirmableNotificationId);
            saved = distributionLogRepository.save(preSavedLog);
        } else {
            // お知らせ無効: 新規保存
            CommitteeDistributionLogEntity logEntity = CommitteeDistributionLogEntity.builder()
                    .committeeId(committeeId)
                    .contentType(request.getContentType())
                    .contentId(request.getContentId())
                    .customTitle(request.getCustomTitle())
                    .customBody(request.getCustomBody())
                    .targetScope(request.getTargetScope())
                    .announcementEnabled(request.getAnnouncementEnabled())
                    .confirmationMode(request.getConfirmationMode())
                    .confirmableNotificationId(confirmableNotificationId)
                    .announcementFeedIds(feedIdsJson)
                    .createdBy(currentUserId)
                    .build();
            saved = distributionLogRepository.save(logEntity);
        }

        log.info("委員会伝達処理完了: distributionLogId={}, committeeId={}, contentType={}, createdBy={}",
                saved.getId(), committeeId, request.getContentType(), currentUserId);

        return saved;
    }

    // ========================================
    // 伝達履歴一覧
    // ========================================

    /**
     * 委員会の伝達処理履歴一覧を取得する（作成日時降順）。
     *
     * <p>認可: 委員会メンバーのみ。</p>
     *
     * @param committeeId   委員会 ID
     * @param currentUserId 閲覧者ユーザー ID
     * @param pageable      ページング情報
     * @return 伝達処理ログページ
     */
    public Page<CommitteeDistributionLogEntity> listDistributions(
            Long committeeId,
            Long currentUserId,
            Pageable pageable) {

        // 委員会の存在確認
        committeeRepository.findById(committeeId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));

        // 認可チェック: 委員会メンバーのみ
        if (!committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, currentUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        return distributionLogRepository.findByCommitteeIdOrderByCreatedAtDesc(committeeId, pageable);
    }

    // ========================================
    // 伝達履歴詳細
    // ========================================

    /**
     * 伝達処理履歴の詳細を取得する。
     *
     * <p>認可: 対象委員会のメンバーのみ。</p>
     *
     * @param distributionId 伝達処理ログ ID
     * @param currentUserId  閲覧者ユーザー ID
     * @return 伝達処理ログエンティティ
     */
    public CommitteeDistributionLogEntity getDistribution(
            Long distributionId,
            Long currentUserId) {

        // 伝達ログの存在確認
        CommitteeDistributionLogEntity distribution = distributionLogRepository.findById(distributionId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));

        // 委員会の存在確認
        Long committeeId = distribution.getCommitteeId();
        committeeRepository.findById(committeeId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));

        // 認可チェック: 対象委員会のメンバーのみ
        if (!committeeMemberRepository.existsByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, currentUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        return distribution;
    }

    // ========================================
    // プライベートヘルパーメソッド
    // ========================================

    /**
     * ユーザーが伝達権限ロール（CHAIR / VICE_CHAIR / SECRETARY）を持つかどうかを返す。
     */
    private boolean hasDistributionRole(Long committeeId, Long userId) {
        return committeeMemberRepository.findByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, userId)
                .map(m -> CommitteeRole.CHAIR.equals(m.getRole())
                        || CommitteeRole.VICE_CHAIR.equals(m.getRole())
                        || CommitteeRole.SECRETARY.equals(m.getRole()))
                .orElse(false);
    }
}
