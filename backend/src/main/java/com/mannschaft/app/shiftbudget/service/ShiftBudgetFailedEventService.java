package com.mannschaft.app.shiftbudget.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.shiftbudget.ShiftBudgetErrorCode;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventStatus;
import com.mannschaft.app.shiftbudget.ShiftBudgetFailedEventType;
import com.mannschaft.app.shiftbudget.ShiftBudgetFeatureService;
import com.mannschaft.app.shiftbudget.dto.FailedEventResponse;
import com.mannschaft.app.shiftbudget.entity.ShiftBudgetFailedEventEntity;
import com.mannschaft.app.shiftbudget.repository.ShiftBudgetFailedEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * F08.7 Phase 10-β: 通知失敗 / hook 失敗イベントの記録 + リトライ + 管理 API サービス。
 *
 * <p>9-δ AFTER_COMMIT hook の swallow パターンを補完する。失敗イベントを永続化し、
 * {@link com.mannschaft.app.shiftbudget.batch.ShiftBudgetRetryBatchJob} が
 * 15 分毎に {@code retry_count < 3} まで再実行する。
 * EXHAUSTED 化したものは管理 API で個別再実行 / 手動補正済マーク可能。</p>
 *
 * <p>権限:</p>
 * <ul>
 *   <li>list: {@code BUDGET_VIEW}</li>
 *   <li>retry: {@code BUDGET_ADMIN}</li>
 *   <li>markManualResolved: {@code BUDGET_ADMIN}</li>
 *   <li>recordFailure: 既存 hook 内部から呼ばれる（権限チェック不要、システム経路）</li>
 * </ul>
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class ShiftBudgetFailedEventService {

    /** 一覧の最大ページサイズ。 */
    private static final int MAX_PAGE_SIZE = 100;

    private final ShiftBudgetFailedEventRepository repository;
    private final ShiftBudgetFeatureService featureService;
    private final AccessControlService accessControlService;
    private final AuditLogService auditLogService;
    private final ShiftBudgetRetryExecutor retryExecutor;
    private final ObjectMapper objectMapper;

    /**
     * 循環依存ガード: FailedEventService → RetryExecutor → ThresholdAlertEvaluationService → FailedEventService
     * の循環があるため、{@code retryExecutor} を {@code @Lazy} で注入する。
     */
    @Autowired
    public ShiftBudgetFailedEventService(
            ShiftBudgetFailedEventRepository repository,
            ShiftBudgetFeatureService featureService,
            AccessControlService accessControlService,
            AuditLogService auditLogService,
            @Lazy ShiftBudgetRetryExecutor retryExecutor,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.featureService = featureService;
        this.accessControlService = accessControlService;
        this.auditLogService = auditLogService;
        this.retryExecutor = retryExecutor;
        this.objectMapper = objectMapper;
    }

    /**
     * 失敗イベントを記録する（既存 hook の swallow 箇所から呼ばれる）。
     *
     * <p>独立トランザクション ({@link Propagation#REQUIRES_NEW}) で動作させ、
     * 呼び出し側の AFTER_COMMIT hook の例外伝播 / 失敗が記録自体を巻き戻さないようにする。</p>
     *
     * <p>本メソッド自体が例外を出した場合は呼び出し側 (Listener) で握りつぶされる前提。
     * フォレンジック保証として ERROR ログだけは確実に出す。</p>
     *
     * @param organizationId 組織 ID
     * @param eventType      イベント種別
     * @param sourceId       ソース ID（allocation_id / alert_id 等）。NULL 可
     * @param payload        再実行用ペイロード（任意の Map、JSON にシリアライズして格納）
     * @param errorMessage   失敗時のエラーメッセージ
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ShiftBudgetFailedEventEntity recordFailure(Long organizationId,
                                                     ShiftBudgetFailedEventType eventType,
                                                     Long sourceId,
                                                     Map<String, Object> payload,
                                                     String errorMessage) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (JsonProcessingException e) {
            log.warn("F08.7 失敗イベント記録: payload JSON シリアライズ失敗 → 空オブジェクトで継続: orgId={}, eventType={}",
                    organizationId, eventType, e);
            payloadJson = "{}";
        }

        ShiftBudgetFailedEventEntity entity = ShiftBudgetFailedEventEntity.builder()
                .organizationId(organizationId)
                .eventType(eventType)
                .sourceId(sourceId)
                .payload(payloadJson)
                .errorMessage(truncate(errorMessage))
                .retryCount(0)
                .status(ShiftBudgetFailedEventStatus.PENDING)
                .build();
        ShiftBudgetFailedEventEntity saved = repository.save(entity);
        log.info("F08.7 失敗イベント記録: id={}, orgId={}, eventType={}, sourceId={}",
                saved.getId(), organizationId, eventType, sourceId);
        return saved;
    }

    /**
     * 個別再実行（管理 API、{@code BUDGET_ADMIN} 権限必須）。
     *
     * <p>EXHAUSTED ステータスでも運用判断による再試行を許容する。
     * SUCCEEDED / MANUAL_RESOLVED は終端のため {@code FAILED_EVENT_NOT_RETRIABLE} (409) を返す。</p>
     */
    @Transactional
    public FailedEventResponse retry(Long organizationId, Long failedEventId) {
        featureService.requireEnabled(organizationId);
        requireBudgetAdmin(organizationId);

        ShiftBudgetFailedEventEntity entity = repository.findById(failedEventId)
                .filter(e -> e.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.FAILED_EVENT_NOT_FOUND));

        if (entity.getStatus() == ShiftBudgetFailedEventStatus.SUCCEEDED
                || entity.getStatus() == ShiftBudgetFailedEventStatus.MANUAL_RESOLVED) {
            throw new BusinessException(ShiftBudgetErrorCode.FAILED_EVENT_NOT_RETRIABLE);
        }

        Long currentUserId = SecurityUtils.getCurrentUserIdOrNull();
        boolean success = retryExecutor.execute(entity);

        ShiftBudgetFailedEventEntity refreshed = repository.findById(failedEventId).orElse(entity);

        auditLogService.record(
                "FAILED_EVENT_RETRIED",
                currentUserId, null,
                null, organizationId,
                null, null, null,
                String.format("{\"failed_event_id\":%d,\"event_type\":\"%s\",\"success\":%s,"
                                + "\"retry_count\":%d,\"new_status\":\"%s\"}",
                        failedEventId, refreshed.getEventType(), success,
                        refreshed.getRetryCount(), refreshed.getStatus()));

        log.info("F08.7 失敗イベント手動再実行: id={}, success={}, newStatus={}",
                failedEventId, success, refreshed.getStatus());

        return FailedEventResponse.from(refreshed);
    }

    /**
     * 手動補正済マーク（管理 API、{@code BUDGET_ADMIN} 権限必須）。
     *
     * <p>運用者がDB直接補正やオフライン対応で問題を解消した後に、
     * MANUAL_RESOLVED 終端ステータスへ遷移させる。バッチは以後拾わない。</p>
     */
    @Transactional
    public FailedEventResponse markManualResolved(Long organizationId, Long failedEventId) {
        featureService.requireEnabled(organizationId);
        requireBudgetAdmin(organizationId);

        ShiftBudgetFailedEventEntity entity = repository.findById(failedEventId)
                .filter(e -> e.getOrganizationId().equals(organizationId))
                .orElseThrow(() -> new BusinessException(ShiftBudgetErrorCode.FAILED_EVENT_NOT_FOUND));

        Long currentUserId = SecurityUtils.getCurrentUserId();
        entity.markManualResolved();
        ShiftBudgetFailedEventEntity saved = repository.save(entity);

        auditLogService.record(
                "FAILED_EVENT_RESOLVED",
                currentUserId, null,
                null, organizationId,
                null, null, null,
                String.format("{\"failed_event_id\":%d,\"event_type\":\"%s\",\"resolved_by\":%d}",
                        failedEventId, saved.getEventType(), currentUserId));

        log.info("F08.7 失敗イベント手動補正済マーク: id={}, ackBy={}", failedEventId, currentUserId);
        return FailedEventResponse.from(saved);
    }

    /** 一覧取得（管理 API、{@code BUDGET_VIEW} 権限必須）。{@code status} が null なら全件。 */
    public List<FailedEventResponse> list(Long organizationId,
                                          ShiftBudgetFailedEventStatus status,
                                          int page, int size) {
        featureService.requireEnabled(organizationId);
        requireBudgetView(organizationId);

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int safePage = Math.max(page, 0);
        Pageable pageable = PageRequest.of(safePage, safeSize);

        List<ShiftBudgetFailedEventEntity> entities = (status == null)
                ? repository.findByOrganizationId(organizationId, pageable)
                : repository.findByOrganizationIdAndStatus(organizationId, status, pageable);

        return entities.stream().map(FailedEventResponse::from).toList();
    }

    private void requireBudgetView(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!accessControlService.isSystemAdmin(currentUserId)
                && !hasOrgPermission(currentUserId, organizationId, "BUDGET_VIEW")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_VIEW_REQUIRED);
        }
    }

    private void requireBudgetAdmin(Long organizationId) {
        Long currentUserId = SecurityUtils.getCurrentUserId();
        if (!accessControlService.isSystemAdmin(currentUserId)
                && !hasOrgPermission(currentUserId, organizationId, "BUDGET_ADMIN")) {
            throw new BusinessException(ShiftBudgetErrorCode.BUDGET_ADMIN_REQUIRED);
        }
    }

    private boolean hasOrgPermission(Long userId, Long organizationId, String permissionName) {
        if (!accessControlService.isMember(userId, organizationId, "ORGANIZATION")) {
            return false;
        }
        try {
            accessControlService.checkPermission(userId, organizationId, "ORGANIZATION", permissionName);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    /** error_message TEXT カラム保護のため最大 4000 文字でカット。 */
    private String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }
}
