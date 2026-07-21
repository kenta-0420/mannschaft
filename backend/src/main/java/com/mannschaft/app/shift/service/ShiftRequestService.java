package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.entity.ProxyInputRecordEntity;
import com.mannschaft.app.proxy.repository.ProxyInputRecordRepository;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.ShiftMapper;
import com.mannschaft.app.shift.ShiftPreference;
import com.mannschaft.app.shift.ShiftScheduleStatus;
import com.mannschaft.app.shift.dto.CreateShiftRequestRequest;
import com.mannschaft.app.shift.dto.ShiftRequestResponse;
import com.mannschaft.app.shift.dto.ShiftRequestSummaryResponse;
import com.mannschaft.app.shift.dto.UpdateShiftRequestRequest;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * シフト希望サービス。メンバーのシフト希望提出・更新・集計を担当する。
 *
 * <p><b>認可（認可根治 Wave6）:</b> scope は<b>シフト希望／スケジュール実体から解決した teamId</b>
 * で判定し、パス変数・クエリの scope 値を鵜呑みにしない（BOLA 封鎖）。</p>
 *
 * <ul>
 *   <li><b>他人分を含む一覧・集計</b>（{@code listRequests} / {@code getRequestSummary}）:
 *       ADMIN/DEPUTY_ADMIN 以上（SYSTEM_ADMIN 短絡）</li>
 *   <li><b>提出</b>（{@code submitRequest}）: 当該チームのメンバー（SUPPORTER 不可）</li>
 *   <li><b>更新・削除</b>: 希望の提出者本人、または当該チームの ADMIN 以上</li>
 *   <li><b>自分の一覧</b>（{@code listMyRequests}）: リポジトリ引きの時点で {@code userId} 複合のため
 *       構造的に自己スコープ</li>
 * </ul>
 *
 * <p>認可失敗は {@code COMMON_002}（403）。同ドメインの {@code ShiftScheduleScopeContractIT} /
 * {@code ShiftSlotScopeContractIT} の規約に揃える。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftRequestService {

    private final ShiftRequestRepository requestRepository;
    private final ShiftScheduleService scheduleService;
    private final ShiftMapper shiftMapper;
    private final UserRoleRepository userRoleRepository;
    private final AccessControlService accessControlService;
    private final ProxyInputContext proxyInputContext;
    private final ProxyInputRecordRepository proxyInputRecordRepository;

    /**
     * スケジュールのシフト希望一覧を取得する（他メンバー分を含むため管理者のみ）。
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @return シフト希望一覧
     * @throws BusinessException 当該スケジュールのチームの ADMIN 以上でない場合（COMMON_002 / 403）
     */
    public List<ShiftRequestResponse> listRequests(Long scheduleId, Long userId) {
        checkScheduleAdminAccess(scheduleId, userId);
        List<ShiftRequestEntity> entities = requestRepository.findByScheduleIdOrderBySlotDateAsc(scheduleId);
        return shiftMapper.toRequestResponseList(entities);
    }

    /**
     * 自分のシフト希望一覧を取得する。
     *
     * @param userId ユーザーID
     * @return シフト希望一覧
     */
    public List<ShiftRequestResponse> listMyRequests(Long userId) {
        List<ShiftRequestEntity> entities = requestRepository.findByUserIdOrderBySlotDateDesc(userId);
        return shiftMapper.toRequestResponseList(entities);
    }

    /**
     * シフト希望を提出する。
     *
     * @param req    提出リクエスト
     * @param userId ユーザーID
     * @return 提出されたシフト希望
     */
    // TODO: shiftドメインとproxyドメインをまたいでいる（ProxyInputRecordRepositoryを直接参照）。将来はProxyInputServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    @Transactional
    public ShiftRequestResponse submitRequest(CreateShiftRequestRequest req, Long userId) {
        ShiftScheduleEntity schedule = scheduleService.findScheduleOrThrow(req.getScheduleId());
        checkTeamMemberAccess(schedule.getTeamId(), userId);
        validateCollectingStatus(schedule);
        validateRequestDeadline(schedule);

        // 重複チェック
        requestRepository.findByScheduleIdAndUserIdAndSlotDate(req.getScheduleId(), userId, req.getSlotDate())
                .ifPresent(existing -> {
                    throw new BusinessException(ShiftErrorCode.REQUEST_ALREADY_EXISTS);
                });

        ShiftRequestEntity entity = ShiftRequestEntity.builder()
                .scheduleId(req.getScheduleId())
                .userId(userId)
                .slotId(req.getSlotId())
                .slotDate(req.getSlotDate())
                .preference(ShiftPreference.valueOf(req.getPreference()))
                .note(req.getNote())
                .build();

        entity = requestRepository.save(entity);

        // 代理入力の場合: proxy_input_records を作成し、フラグをセット
        if (proxyInputContext.isProxy()) {
            ProxyInputRecordEntity proxyRecord = buildAndSaveProxyInputRecord("SHIFT_REQUEST", entity.getId());
            entity = requestRepository.save(entity.toBuilder()
                    .isProxyInput(true)
                    .proxyInputRecordId(proxyRecord.getId())
                    .build());
        }

        log.info("シフト希望提出: id={}, scheduleId={}, userId={}", entity.getId(), req.getScheduleId(), userId);
        return shiftMapper.toRequestResponse(entity);
    }

    /**
     * シフト希望を更新する。
     *
     * @param requestId リクエストID
     * @param req       更新リクエスト
     * @param userId    ユーザーID
     * @return 更新されたシフト希望
     */
    @Transactional
    public ShiftRequestResponse updateRequest(Long requestId, UpdateShiftRequestRequest req, Long userId) {
        ShiftRequestEntity entity = findRequestOrThrow(requestId);
        checkOwnerOrTeamAdmin(entity, userId);

        ShiftScheduleEntity schedule = scheduleService.findScheduleOrThrow(entity.getScheduleId());
        validateCollectingStatus(schedule);
        validateRequestDeadline(schedule);

        entity.updatePreference(ShiftPreference.valueOf(req.getPreference()), req.getNote());
        entity = requestRepository.save(entity);

        log.info("シフト希望更新: id={}", requestId);
        return shiftMapper.toRequestResponse(entity);
    }

    /**
     * シフト希望を削除する。
     *
     * @param requestId リクエストID
     * @param userId    操作者ユーザーID
     * @throws BusinessException 提出者本人でも当該チームの ADMIN 以上でもない場合（COMMON_002 / 403）
     */
    @Transactional
    public void deleteRequest(Long requestId, Long userId) {
        ShiftRequestEntity entity = findRequestOrThrow(requestId);
        checkOwnerOrTeamAdmin(entity, userId);
        requestRepository.delete(entity);
        log.info("シフト希望削除: id={}", requestId);
    }

    /**
     * シフト希望提出サマリーを取得する。
     *
     * <p>v2 拡張: 5 段階 preference 別カウント（PREFERRED / AVAILABLE / WEAK_REST /
     * STRONG_REST / ABSOLUTE_REST）を 1 クエリで集計して返却する。</p>
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @return 提出サマリー
     * @throws BusinessException 当該スケジュールのチームの ADMIN 以上でない場合（COMMON_002 / 403）
     */
    // TODO: shiftドメインとroleドメインをまたいでいる（UserRoleRepositoryを直接参照）。将来はUserRoleQueryServiceのAPI呼び出し経由で分離予定。Phase1-E: 2026-05-09
    public ShiftRequestSummaryResponse getRequestSummary(Long scheduleId, Long userId) {
        checkScheduleAdminAccess(scheduleId, userId);
        long submittedCount = requestRepository.countDistinctUserIdByScheduleId(scheduleId);
        ShiftScheduleEntity schedule = scheduleService.findScheduleOrThrow(scheduleId);
        long totalMembers = userRoleRepository.countByTeamId(schedule.getTeamId());
        long pendingCount = Math.max(0, totalMembers - submittedCount);

        Map<ShiftPreference, Long> preferenceCounts = aggregatePreferenceCounts(scheduleId);

        return new ShiftRequestSummaryResponse(
                scheduleId,
                totalMembers,
                submittedCount,
                pendingCount,
                preferenceCounts.getOrDefault(ShiftPreference.PREFERRED, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.AVAILABLE, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.WEAK_REST, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.STRONG_REST, 0L),
                preferenceCounts.getOrDefault(ShiftPreference.ABSOLUTE_REST, 0L));
    }

    /**
     * スケジュール単位の 5 段階 preference 別件数を集計する。
     *
     * <p>DB への 1 クエリで取得した結果を {@link EnumMap} に詰め替えて返却。
     * 集計対象が存在しない preference は Map に含まれず、呼び出し側で {@code 0} として扱う。</p>
     */
    private Map<ShiftPreference, Long> aggregatePreferenceCounts(Long scheduleId) {
        Map<ShiftPreference, Long> counts = new EnumMap<>(ShiftPreference.class);
        List<Object[]> rows = requestRepository.countByPreferenceForSchedule(scheduleId);
        for (Object[] row : rows) {
            ShiftPreference preference = (ShiftPreference) row[0];
            Long count = (Long) row[1];
            if (preference != null && count != null) {
                counts.put(preference, count);
            }
        }
        return counts;
    }

    /**
     * 代理入力記録を作成して保存する（冪等性チェック付き）。
     *
     * @param targetEntityType 対象エンティティ種別
     * @param targetEntityId   対象エンティティID
     * @return 保存済みの代理入力記録エンティティ
     */
    private ProxyInputRecordEntity buildAndSaveProxyInputRecord(String targetEntityType, Long targetEntityId) {
        Long proxyUserId = SecurityUtils.getCurrentUserIdOrNull();
        // 冪等性チェック（紙運用での二重登録防止）
        return proxyInputRecordRepository.findByProxyInputConsentIdAndTargetEntityTypeAndTargetEntityId(
                proxyInputContext.getConsentId(), targetEntityType, targetEntityId)
                .orElseGet(() -> proxyInputRecordRepository.save(
                        ProxyInputRecordEntity.builder()
                                .proxyInputConsentId(proxyInputContext.getConsentId())
                                .subjectUserId(proxyInputContext.getSubjectUserId())
                                .proxyUserId(proxyUserId)
                                .featureScope("SHIFT_REQUEST")
                                .targetEntityType(targetEntityType)
                                .targetEntityId(targetEntityId)
                                .inputSource(ProxyInputRecordEntity.InputSource.valueOf(
                                        proxyInputContext.getInputSource()))
                                .originalStorageLocation(proxyInputContext.getOriginalStorageLocation())
                                .build()));
    }

    // ═════════════════════════════════════════════════════════════════════
    // 認可ヘルパー（認可根治 Wave6）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * スケジュール実体由来のチームに対する管理操作 per-scope 認可。
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @throws BusinessException 権限が無い場合（COMMON_002 / 403）
     */
    private void checkScheduleAdminAccess(Long scheduleId, Long userId) {
        // AccessControlService をこのメソッドから直接呼ぶ（番人 AuthzControllerGuardArchTest の
        // 委譲探索は深さ2までのため、認可クラスへの到達を1ホップ内に収める）
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(
                userId, scheduleService.findScheduleOrThrow(scheduleId).getTeamId(), "TEAM");
    }

    /**
     * メンバー操作の per-scope 認可（当該チームのメンバー、ただし SUPPORTER は不可）。
     *
     * @param teamId 対象チームID
     * @param userId 操作者ユーザーID
     * @throws BusinessException メンバーでない場合、または SUPPORTER の場合（COMMON_002 / 403）
     */
    private void checkTeamMemberAccess(Long teamId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (!accessControlService.isMember(userId, teamId, "TEAM")
                || accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * 本人操作の per-scope 認可（提出者本人、または当該チームの ADMIN 以上）。
     *
     * @param entity 対象シフト希望
     * @param userId 操作者ユーザーID
     * @throws BusinessException 提出者でも当該チームの ADMIN 以上でもない場合（COMMON_002 / 403）
     */
    private void checkOwnerOrTeamAdmin(ShiftRequestEntity entity, Long userId) {
        // AccessControlService をこのメソッドから直接呼ぶ（番人の委譲探索は深さ2までのため）
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (entity.getUserId() != null && entity.getUserId().equals(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(
                userId, scheduleService.findScheduleOrThrow(entity.getScheduleId()).getTeamId(), "TEAM");
    }

    /**
     * シフト希望を取得する。存在しない場合は例外をスローする。
     */
    private ShiftRequestEntity findRequestOrThrow(Long id) {
        return requestRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_REQUEST_NOT_FOUND));
    }

    /**
     * スケジュールが希望収集中であることを検証する。
     */
    private void validateCollectingStatus(ShiftScheduleEntity schedule) {
        if (schedule.getStatus() != ShiftScheduleStatus.COLLECTING) {
            throw new BusinessException(ShiftErrorCode.INVALID_SCHEDULE_STATUS);
        }
    }

    /**
     * 希望提出期限を過ぎていないことを検証する。
     */
    private void validateRequestDeadline(ShiftScheduleEntity schedule) {
        if (schedule.getRequestDeadline() != null
                && LocalDateTime.now().isAfter(schedule.getRequestDeadline())) {
            throw new BusinessException(ShiftErrorCode.REQUEST_DEADLINE_PASSED);
        }
    }
}
