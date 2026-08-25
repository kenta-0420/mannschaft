package com.mannschaft.app.safetycheck.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.i18n.UserLocaleCache;
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
import com.mannschaft.app.safetycheck.event.SafetyCheckReminderNotificationEvent;
import com.mannschaft.app.safetycheck.repository.SafetyCheckRepository;
import com.mannschaft.app.safetycheck.repository.SafetyCheckTemplateRepository;
import com.mannschaft.app.safetycheck.repository.SafetyResponseRepository;
import com.mannschaft.app.safetycheck.SafetyResponseStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.MessageSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final NameResolverService nameResolverService;
    private final NotificationHelper notificationHelper;
    private final MessageSource messageSource;
    private final UserLocaleCache userLocaleCache;
    private final AccessControlService accessControlService;
    private final ApplicationEventPublisher applicationEventPublisher;

    /**
     * 安否確認を発信する。
     *
     * @param req    作成リクエスト
     * @param userId 発信者ID
     * @return 作成された安否確認
     */
    @Transactional
    // TODO: SafetycheckドメインとRoleドメイン・Notificationドメインをまたいでいる。将来はMemberCountResolvedEventとNotificationRequestedEventで分離予定
    public SafetyCheckResponse createSafetyCheck(CreateSafetyCheckRequest req, Long userId) {
        SafetyCheckScopeType scopeType = parseScopeType(req.getScopeType());

        // 束3 AC-1-4: 安否確認の発信はスコープの ADMIN/DEPUTY_ADMIN のみ許可（生命安全の偽発信防止）
        accessControlService.checkAdminOrAbove(userId, req.getScopeId(), scopeType.name());

        SafetyCheckEntity.SafetyCheckEntityBuilder builder = SafetyCheckEntity.builder()
                .scopeType(scopeType)
                .scopeId(req.getScopeId())
                .title(req.getTitle())
                .message(req.getMessage())
                .isDrill(req.getIsDrill() != null ? req.getIsDrill() : false)
                .reminderIntervalMinutes(req.getReminderIntervalMinutes())
                .sourceType(req.getSourceType())
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
        long memberCount = scopeType == SafetyCheckScopeType.TEAM
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
     * <p><b>認可</b>: 安否確認の本文は災害時の機微情報を含むため、宣言スコープのメンバーのみ閲覧可
     * （回答は非 ADMIN メンバーも行うため {@code checkMembership}。回答状況・未回答者一覧といった
     * 個人の安否そのものは従来どおり {@link #getResults} / {@link #getUnrespondedUsers} 側で
     * {@code checkAdminOrAbove} に限定される）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param status    ステータス（null の場合は全件）
     * @param page      ページ番号
     * @param size      ページサイズ
     * @param userId    操作者ID
     * @return 安否確認一覧
     */
    public Page<SafetyCheckResponse> listSafetyChecks(String scopeType, Long scopeId,
                                                       String status, int page, int size, Long userId) {
        SafetyCheckScopeType scope = parseScopeType(scopeType);
        requireScopeMember(userId, scope, scopeId);
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
     * <p><b>認可（BOLA 封鎖）</b>: URL にスコープを持たない bare id EP のため、
     * まず entity を fetch し <b>entity 由来のスコープ</b>（{@code scopeType}/{@code scopeId}）で
     * メンバーシップを判定する。権限が無い場合は 403 ではなく
     * {@code SAFETY_CHECK_NOT_FOUND}（404）で存在を秘匿する。</p>
     *
     * @param safetyCheckId 安否確認ID
     * @param userId        操作者ID
     * @return 安否確認詳細
     */
    public SafetyCheckResponse getSafetyCheck(Long safetyCheckId, Long userId) {
        SafetyCheckEntity entity = findSafetyCheckOrThrow(safetyCheckId);
        // entity 由来スコープのメンバーでなければ存在秘匿（404）。番人テストの 2 ホップ制約のため
        // accessControlService は本メソッドから直接呼ぶこと。
        if (entity.getScopeType() == SafetyCheckScopeType.GROUP
                || userId == null
                || !accessControlService.isMember(userId, entity.getScopeId(), entity.getScopeType().name())) {
            throw new BusinessException(SafetyCheckErrorCode.SAFETY_CHECK_NOT_FOUND);
        }
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
        // 束3 AC-1-4: クローズはスコープの ADMIN/DEPUTY_ADMIN のみ許可
        accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), entity.getScopeType().name());
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
     * @param userId        操作者ID（スコープ ADMIN/DEPUTY_ADMIN のみ閲覧可）
     * @return 結果集計
     */
    public SafetyCheckResultsResponse getResults(Long safetyCheckId, Long userId) {
        SafetyCheckEntity check = findSafetyCheckOrThrow(safetyCheckId);
        // 束3 AC-1-4: 回答状況（誰が安全/要支援か）はスコープ管理者のみ閲覧可
        accessControlService.checkAdminOrAbove(userId, check.getScopeId(), check.getScopeType().name());

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
     * @param actorUserId   操作者ID（スコープ ADMIN/DEPUTY_ADMIN のみ閲覧可）
     * @return 未回答ユーザー一覧
     */
    public List<UnrespondedUserResponse> getUnrespondedUsers(Long safetyCheckId, Long actorUserId) {
        SafetyCheckEntity safetyCheck = findSafetyCheckOrThrow(safetyCheckId);
        // 束3 AC-1-4: 未回答者（安否不明者）の氏名一覧はスコープ管理者のみ閲覧可
        accessControlService.checkAdminOrAbove(actorUserId, safetyCheck.getScopeId(), safetyCheck.getScopeType().name());

        // 回答済みユーザーIDを取得
        Set<Long> respondedUserIds = new HashSet<>(
                safetyResponseRepository.findRespondedUserIdsBySafetyCheckId(safetyCheckId));

        // スコープ内の全メンバーを取得
        String scopeType = safetyCheck.getScopeType().name();
        List<Object[]> scopeMembers = userRoleRepository.findUserIdAndEmailByScope(scopeType, safetyCheck.getScopeId());

        // 未回答ユーザーIDを抽出
        Set<Long> unrespondedUserIds = new HashSet<>();
        for (Object[] row : scopeMembers) {
            Long userId = ((Number) row[0]).longValue();
            if (!respondedUserIds.contains(userId)) {
                unrespondedUserIds.add(userId);
            }
        }

        if (unrespondedUserIds.isEmpty()) {
            return List.of();
        }

        // 表示名を一括解決
        Map<Long, String> nameMap = nameResolverService.resolveUserDisplayNames(unrespondedUserIds);

        return unrespondedUserIds.stream()
                .map(uid -> new UnrespondedUserResponse(uid, nameMap.getOrDefault(uid, "")))
                .toList();
    }

    /**
     * 安否確認履歴を取得する（クローズ済み）。
     *
     * <p><b>認可</b>: {@link #listSafetyChecks} と同一（宣言スコープのメンバーのみ）。</p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @param page      ページ番号
     * @param size      ページサイズ
     * @param userId    操作者ID
     * @return 履歴一覧
     */
    public Page<SafetyCheckResponse> getHistory(String scopeType, Long scopeId, int page, int size, Long userId) {
        SafetyCheckScopeType scope = parseScopeType(scopeType);
        requireScopeMember(userId, scope, scopeId);
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
        // 束3 AC-1-4: リマインド送信はスコープの ADMIN/DEPUTY_ADMIN のみ許可
        accessControlService.checkAdminOrAbove(userId, entity.getScopeId(), entity.getScopeType().name());
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
        // NOTE: 全メンバーから回答済みを除いた未回答者への通知は、メンバー一覧取得実装後に拡張
        // Issue #2834 / CMP-056 横展開: 業務TX内ではイベントを publish するだけに留め、
        // 文面組み立て・配送は AFTER_COMMIT の SafetyCheckReminderNotificationListener に委譲する。
        applicationEventPublisher.publishEvent(new SafetyCheckReminderNotificationEvent(
                safetyCheckId, userId, entity.getScopeType(), entity.getScopeId()));
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
     * 宣言スコープのメンバーであることを要求する（参照系の入口ガード）。
     *
     * <p>{@code GROUP} スコープは {@code scopeId} がチーム／組織 ID ではなくグループ ID を指し、
     * {@code memberships} で所属解決ができない（{@code ScopeType.valueOf("GROUP")} は例外）。
     * {@code SafetyCheckRepository#searchByKeyword} の既存方針と揃え <b>fail-closed</b> で拒否する。</p>
     *
     * <p>番人テスト {@code AuthzControllerGuardArchTest} は Controller 起点で 2 ホップまでしか
     * 委譲を辿らないため、{@code accessControlService} は本メソッドから<b>直接</b>呼ぶこと。</p>
     */
    private void requireScopeMember(Long userId, SafetyCheckScopeType scope, Long scopeId) {
        if (scope == SafetyCheckScopeType.GROUP || userId == null
                || !accessControlService.isMember(userId, scopeId, scope.name())) {
            throw new BusinessException(SafetyCheckErrorCode.ACCESS_DENIED);
        }
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
