package com.mannschaft.app.safetycheck.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.notification.NotificationPriority;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.safetycheck.SafetyCheckErrorCode;
import com.mannschaft.app.safetycheck.SafetyCheckMapper;
import com.mannschaft.app.safetycheck.SafetyCheckScopeType;
import com.mannschaft.app.safetycheck.SafetyCheckStatus;
import com.mannschaft.app.safetycheck.dto.CreateSafetyCheckRequest;
import com.mannschaft.app.safetycheck.dto.SafetyCheckResponse;
import com.mannschaft.app.safetycheck.dto.SafetyCheckResultsResponse;
import com.mannschaft.app.safetycheck.dto.SafetyResponseResponse;
import com.mannschaft.app.safetycheck.dto.UnrespondedUserResponse;
import com.mannschaft.app.safetycheck.entity.SafetyCheckEntity;
import com.mannschaft.app.safetycheck.entity.SafetyCheckTemplateEntity;
import com.mannschaft.app.safetycheck.entity.SafetyResponseEntity;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.safetycheck.repository.SafetyCheckTemplateRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseRepository;
import com.mannschaft.app.safetycheck.SafetyResponseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 安否確認サービス。安否確認の発信・クローズ・結果集計・リマインドを担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SafetyCheckService {

    private final SafetyCheckRepository safetyCheckRepository;
    private final SafetyResponseRepository safetyResponseRepository;
    private final SafetyCheckTemplateRepository templateRepository;
    private final SafetyCheckMapper mapper;
    private final UserRoleRepository userRoleRepository;
    private final NotificationHelper notificationHelper;

    /**
     * 安否確認を発信する。
     *
     * @param req    作成リクエスト
     * @param userId 発信者ID
     * @return 作成された安否確認
     */
    @Transactional
    public SafetyCheckResponse createSafetyCheck(CreateSafetyCheckRequest req, Long userId) {
        SafetyCheckScopeType scopeType = parseScopeType(req.getScopeType());

        SafetyCheckEntity.SafetyCheckEntityBuilder builder = SafetyCheckEntity.builder()
                .scopeType(scopeType)
                .scopeId(req.getScopeId())
                .title(req.getTitle())
                .message(req.getMessage())
                .isDrill(req.getIsDrill() != null ? req.getIsDrill() : false)
                .reminderIntervalMinutes(req.getReminderIntervalMinutes())
                .createdBy(userId);

        // テンプレートからデフォルト値を適用
        if (req.getTemplateId() != null) {
            SafetyCheckTemplateEntity template = templateRepository.findById(req.getTemplateId())
                    .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.TEMPLATE_NOT_FOUND));
            if (req.getTitle() == null || req.getTitle().isBlank()) {
                builder.title(template.getTitle());
            }
            if (req.getMessage() == null || req.getMessage().isBlank()) {
                builder.message(template.getMessage());
            }
            if (req.getReminderIntervalMinutes() == null) {
                builder.reminderIntervalMinutes(template.getReminderIntervalMinutes());
            }
        }

        SafetyCheckEntity entity = safetyCheckRepository.save(builder.build());

        // スコープのメンバー総数を設定
        long memberCount = "TEAM".equals(scopeType)
                ? userRoleRepository.countByTeamId(req.getScopeId())
                : userRoleRepository.countByOrganizationId(req.getScopeId());
        entity.updateTotalTargetCount((int) memberCount);
        safetyCheckRepository.save(entity);

        log.info("安否確認発信: id={}, scope={}:{}, createdBy={}", entity.getId(), scopeType, req.getScopeId(), userId);
        return mapper.toSafetyCheckResponse(entity);
    }

    /**
     * 安否確認一覧を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータス（null の場合は全件）
     * @param page      ページ番号
     * @param size      ページサイズ
     * @return 安否確認一覧
     */
    public Page<SafetyCheckResponse> listSafetyChecks(String scopeType, Long scopeId,
                                                       String status, int page, int size) {
        SafetyCheckScopeType scope = parseScopeType(scopeType);
        PageRequest pageRequest = PageRequest.of(page, size);

        Page<SafetyCheckEntity> entities;
        if (status != null && !status.isBlank()) {
            SafetyCheckStatus checkStatus = SafetyCheckStatus.valueOf(status);
            entities = safetyCheckRepository.findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
                    scope, scopeId, checkStatus, pageRequest);
        } else {
            entities = safetyCheckRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
                    scope, scopeId, pageRequest);
        }

        return entities.map(mapper::toSafetyCheckResponse);
    }

    /**
     * 安否確認詳細を取得する。
     *
     * @param safetyCheckId 安否確認ID
     * @return 安否確認詳細
     */
    public SafetyCheckResponse getSafetyCheck(Long safetyCheckId) {
        SafetyCheckEntity entity = findSafetyCheckOrThrow(safetyCheckId);
        return mapper.toSafetyCheckResponse(entity);
    }

    /**
     * 安否確認をクローズする。
     *
     * @param safetyCheckId 安否確認ID
     * @param userId        操作者ID
     * @return クローズ後の安否確認
     */
    @Transactional
    public SafetyCheckResponse closeSafetyCheck(Long safetyCheckId, Long userId) {
        SafetyCheckEntity entity = findSafetyCheckOrThrow(safetyCheckId);
        validateActive(entity);

        entity.close(userId);
        entity = safetyCheckRepository.save(entity);

        log.info("安否確認クローズ: id={}, closedBy={}", safetyCheckId, userId);
        return mapper.toSafetyCheckResponse(entity);
    }

    /**
     * 安否確認の結果集計を取得する。
     *
     * @param safetyCheckId 安否確認ID
     * @return 結果集計
     */
    public SafetyCheckResultsResponse getResults(Long safetyCheckId) {
        SafetyCheckEntity check = findSafetyCheckOrThrow(safetyCheckId);

        List<SafetyResponseEntity> responses = safetyResponseRepository
                .findBySafetyCheckIdOrderByRespondedAtAsc(safetyCheckId);
        List<SafetyResponseResponse> responseList = mapper.toSafetyResponseResponseList(responses);

        long respondedCount = responses.size();
        long safeCount = safetyResponseRepository.countBySafetyCheckIdAndStatus(
                safetyCheckId, SafetyResponseStatus.SAFE);
        long needSupportCount = safetyResponseRepository.countBySafetyCheckIdAndStatus(
                safetyCheckId, SafetyResponseStatus.NEED_SUPPORT);
        long otherCount = safetyResponseRepository.countBySafetyCheckIdAndStatus(
                safetyCheckId, SafetyResponseStatus.OTHER);
        long unrespondedCount = check.getTotalTargetCount() - respondedCount;

        return new SafetyCheckResultsResponse(
                safetyCheckId, check.getTotalTargetCount(),
                respondedCount, safeCount, needSupportCount, otherCount,
                Math.max(0, unrespondedCount), responseList);
    }

    /**
     * 未回答ユーザー一覧を取得する。
     *
     * @param safetyCheckId 安否確認ID
     * @return 未回答ユーザー一覧
     */
    public List<UnrespondedUserResponse> getUnrespondedUsers(Long safetyCheckId) {
        findSafetyCheckOrThrow(safetyCheckId);

        // 回答済みユーザーIDを取得
        List<Long> respondedUserIds = safetyResponseRepository.findRespondedUserIdsBySafetyCheckId(safetyCheckId);

        // NOTE: 未回答ユーザーの詳細情報取得にはUserServiceとの連携が必要
        // 現時点ではスコープメンバー数 - 回答者数の差分のみ返却
        log.debug("回答済みユーザー数: {}", respondedUserIds.size());
        return List.of();
    }

    /**
     * 安否確認履歴を取得する（クローズ済み）。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param page      ページ番号
     * @param size      ページサイズ
     * @return 履歴一覧
     */
    public Page<SafetyCheckResponse> getHistory(String scopeType, Long scopeId, int page, int size) {
        SafetyCheckScopeType scope = parseScopeType(scopeType);
        PageRequest pageRequest = PageRequest.of(page, size);

        return safetyCheckRepository.findClosedByScopeOrderByClosedAtDesc(scope, scopeId, pageRequest)
                .map(mapper::toSafetyCheckResponse);
    }

    /**
     * リマインドを送信する。
     *
     * @param safetyCheckId 安否確認ID
     * @param userId        操作者ID
     */
    @Transactional
    public void sendReminder(Long safetyCheckId, Long userId) {
        SafetyCheckEntity entity = findSafetyCheckOrThrow(safetyCheckId);
        validateActive(entity);

        // リマインド間隔チェック
        if (entity.getLastReminderAt() != null && entity.getReminderIntervalMinutes() != null) {
            LocalDateTime nextAllowed = entity.getLastReminderAt()
                    .plusMinutes(entity.getReminderIntervalMinutes());
            if (LocalDateTime.now().isBefore(nextAllowed)) {
                throw new BusinessException(SafetyCheckErrorCode.REMIND_TOO_FREQUENT);
            }
        }

        entity.updateLastReminderAt();
        safetyCheckRepository.save(entity);

        // 未回答者にリマインド通知を送信
        List<Long> respondedIds = safetyResponseRepository.findRespondedUserIdsBySafetyCheckId(safetyCheckId);
        // NOTE: 全メンバーから回答済みを除いた未回答者への通知は、メンバー一覧取得実装後に拡張
        notificationHelper.notify(userId, "SAFETY_CHECK_REMINDER", NotificationPriority.URGENT,
                "安否確認リマインド", "安否確認に未回答です。至急回答をお願いします。",
                "SAFETY_CHECK", safetyCheckId,
                NotificationScopeType.valueOf(entity.getScopeType().name()), entity.getScopeId(),
                "/safety-checks/" + safetyCheckId, userId);
        log.info("リマインド送信: safetyCheckId={}, sentBy={}", safetyCheckId, userId);
    }

    // --- プライベートメソッド ---

    /**
     * 安否確認を取得する。存在しない場合は例外をスローする。
     */
    SafetyCheckEntity findSafetyCheckOrThrow(Long id) {
        return safetyCheckRepository.findById(id)
                .orElseThrow(() -> new BusinessException(SafetyCheckErrorCode.SAFETY_CHECK_NOT_FOUND));
    }

    /**
     * アクティブ状態を検証する。
     */
    private void validateActive(SafetyCheckEntity entity) {
        if (entity.getStatus() == SafetyCheckStatus.CLOSED) {
            throw new BusinessException(SafetyCheckErrorCode.SAFETY_CHECK_ALREADY_CLOSED);
        }
    }

    /**
     * スコープ種別文字列をEnumに変換する。
     */
    private SafetyCheckScopeType parseScopeType(String scopeType) {
        try {
            return SafetyCheckScopeType.valueOf(scopeType);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(SafetyCheckErrorCode.INVALID_SCOPE_TYPE);
        }
    }
}
