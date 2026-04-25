package com.mannschaft.app.shift.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.AssignmentStrategyType;
import com.mannschaft.app.shift.ShiftAssignmentRunStatus;
import com.mannschaft.app.shift.ShiftAssignmentStatus;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.assignment.AssignmentContext;
import com.mannschaft.app.shift.assignment.AssignmentResult;
import com.mannschaft.app.shift.assignment.ShiftAssignmentStrategy;
import com.mannschaft.app.shift.dto.AssignmentParametersDto;
import com.mannschaft.app.shift.dto.AssignmentRunResponse;
import com.mannschaft.app.shift.dto.AssignmentWarningDto;
import com.mannschaft.app.shift.dto.AutoAssignRequest;
import com.mannschaft.app.shift.dto.ConfirmAutoAssignRequest;
import com.mannschaft.app.shift.dto.ProposedAssignmentDto;
import com.mannschaft.app.shift.entity.MemberWorkConstraintEntity;
import com.mannschaft.app.shift.entity.ShiftAssignmentEntity;
import com.mannschaft.app.shift.entity.ShiftAssignmentRunEntity;
import com.mannschaft.app.shift.entity.ShiftRequestEntity;
import com.mannschaft.app.shift.entity.ShiftScheduleEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.MemberWorkConstraintRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRepository;
import com.mannschaft.app.shift.repository.ShiftAssignmentRunRepository;
import com.mannschaft.app.shift.repository.ShiftRequestRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * シフト自動割当サービス。割当アルゴリズムの実行・確定・取消・履歴管理を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftAutoAssignService {

    private final ShiftScheduleRepository scheduleRepository;
    private final ShiftSlotRepository slotRepository;
    private final ShiftRequestRepository requestRepository;
    private final MemberWorkConstraintRepository constraintRepository;
    private final ShiftAssignmentRepository assignmentRepository;
    private final ShiftAssignmentRunRepository assignmentRunRepository;
    private final List<ShiftAssignmentStrategy> strategies;
    private final ObjectMapper objectMapper;

    /**
     * 自動割当を実行する。
     *
     * @param scheduleId  スケジュールID
     * @param request     自動割当リクエスト
     * @param triggeredBy 実行者ユーザーID
     * @return 実行ログレスポンス
     */
    @Transactional
    public AssignmentRunResponse runAutoAssign(Long scheduleId, AutoAssignRequest request, Long triggeredBy) {
        // スケジュール存在チェック
        ShiftScheduleEntity schedule = findScheduleOrThrow(scheduleId);

        // パラメータのデフォルト値補完
        AssignmentParametersDto params = request.parameters() != null
                ? request.parameters().withDefaults()
                : AssignmentParametersDto.defaults();

        // 実行ログを RUNNING で INSERT
        ShiftAssignmentRunEntity run = ShiftAssignmentRunEntity.builder()
                .scheduleId(scheduleId)
                .strategy(request.strategy())
                .status(ShiftAssignmentRunStatus.RUNNING)
                .triggeredBy(triggeredBy)
                .slotsTotal(0)
                .slotsFilled(0)
                .parametersJson(serializeParameters(params))
                .build();
        run = assignmentRunRepository.save(run);
        log.info("自動割当開始: scheduleId={}, runId={}, strategy={}", scheduleId, run.getId(), request.strategy());

        try {
            // AssignmentContext を組み立て
            List<ShiftSlotEntity> slots = slotRepository
                    .findByScheduleIdOrderBySlotDateAscStartTimeAsc(scheduleId);
            List<ShiftRequestEntity> requests = requestRepository
                    .findByScheduleIdOrderBySlotDateAsc(scheduleId);
            List<MemberWorkConstraintEntity> constraints = constraintRepository
                    .findAllByTeamId(schedule.getTeamId());

            AssignmentContext context = new AssignmentContext(scheduleId, slots, requests, constraints, params);

            // Strategy を取得して実行
            ShiftAssignmentStrategy strategy = findStrategy(request.strategy());
            AssignmentResult result = strategy.assign(context);

            // 割当提案を shift_assignments に INSERT（status=PROPOSED）
            final Long runId = run.getId();
            List<ShiftAssignmentEntity> assignments = result.proposals().stream()
                    .map(proposal -> ShiftAssignmentEntity.builder()
                            .slotId(proposal.slotId())
                            .userId(proposal.userId())
                            .runId(runId)
                            .status(ShiftAssignmentStatus.PROPOSED)
                            .score(proposal.score())
                            .assignedBy(triggeredBy)
                            .build())
                    .toList();
            assignmentRepository.saveAll(assignments);

            // スロット充足数を計算
            long filledSlots = result.proposals().stream()
                    .collect(Collectors.groupingBy(p -> p.slotId(), Collectors.counting()))
                    .entrySet().stream()
                    .filter(e -> {
                        ShiftSlotEntity slot = slots.stream()
                                .filter(s -> s.getId().equals(e.getKey()))
                                .findFirst().orElse(null);
                        return slot != null && e.getValue() >= slot.getRequiredCount();
                    })
                    .count();

            // 実行ログを SUCCEEDED に更新
            run.succeed(slots.size(), (int) filledSlots, serializeWarnings(result.warnings()));
            run = assignmentRunRepository.save(run);

            log.info("自動割当完了: runId={}, slotsTotal={}, slotsFilled={}, proposals={}",
                    run.getId(), slots.size(), filledSlots, assignments.size());

            return toRunResponse(run, toProposedAssignmentDtos(assignments), deserializeWarnings(run.getWarningsJson()), params);

        } catch (Exception e) {
            // 失敗時は実行ログを FAILED に更新
            run.fail(e.getMessage());
            assignmentRunRepository.save(run);
            log.error("自動割当失敗: runId={}, error={}", run.getId(), e.getMessage(), e);
            throw new BusinessException(ShiftErrorCode.INVALID_ASSIGNMENT_RUN_STATUS, e);
        }
    }

    /**
     * 自動割当提案を確定する。
     *
     * @param scheduleId スケジュールID
     * @param request    確定リクエスト
     * @param userId     操作者ユーザーID
     */
    @Transactional
    public void confirmAutoAssign(Long scheduleId, ConfirmAutoAssignRequest request, Long userId) {
        // 実行ログの存在・ステータスチェック
        ShiftAssignmentRunEntity run = assignmentRunRepository.findById(request.runId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND));

        if (run.getStatus() != ShiftAssignmentRunStatus.CONFIRMED) {
            throw new BusinessException(ShiftErrorCode.VISUAL_REVIEW_REQUIRED);
        }

        // 指定された割当IDの status を PROPOSED → CONFIRMED に更新
        List<ShiftAssignmentEntity> assignments = assignmentRepository
                .findAllByRunId(request.runId()).stream()
                .filter(a -> request.assignmentIds().contains(a.getId()))
                .toList();

        for (ShiftAssignmentEntity assignment : assignments) {
            if (assignment.getStatus() != ShiftAssignmentStatus.PROPOSED) {
                continue;
            }
            assignment.confirm();
        }
        assignmentRepository.saveAll(assignments);

        // 対応するスロットの assignedUserIds を更新
        updateSlotAssignedUsers(assignments);

        log.info("自動割当確定: scheduleId={}, runId={}, confirmed={}", scheduleId, request.runId(), assignments.size());
    }

    /**
     * 自動割当提案を破棄する（PROPOSED → REVOKED 一括更新）。
     *
     * @param scheduleId スケジュールID
     * @param runId      実行ログID
     * @param userId     操作者ユーザーID
     */
    @Transactional
    public void revokeAutoAssign(Long scheduleId, Long runId, Long userId) {
        ShiftAssignmentRunEntity run = assignmentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND));

        // PROPOSED の割当を全て REVOKED に更新
        List<ShiftAssignmentEntity> proposals = assignmentRepository.findAllByRunId(runId).stream()
                .filter(a -> a.getStatus() == ShiftAssignmentStatus.PROPOSED)
                .toList();

        for (ShiftAssignmentEntity assignment : proposals) {
            assignment.revoke();
        }
        assignmentRepository.saveAll(proposals);

        run.revoke();
        assignmentRunRepository.save(run);

        log.info("自動割当破棄: scheduleId={}, runId={}, revoked={}", scheduleId, runId, proposals.size());
    }

    /**
     * スケジュールの自動割当実行履歴一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @return 実行ログ一覧
     */
    public List<AssignmentRunResponse> getAssignmentRuns(Long scheduleId) {
        List<ShiftAssignmentRunEntity> runs = assignmentRunRepository
                .findAllByScheduleIdOrderByStartedAtDesc(scheduleId);
        return runs.stream()
                .map(run -> toRunResponse(run, null, deserializeWarnings(run.getWarningsJson()), deserializeParameters(run.getParametersJson())))
                .toList();
    }

    /**
     * 自動割当実行ログ詳細を取得する（割当提案一覧を含む）。
     *
     * @param runId 実行ログID
     * @return 実行ログ詳細
     */
    public AssignmentRunResponse getAssignmentRunDetail(Long runId) {
        ShiftAssignmentRunEntity run = assignmentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND));

        List<ShiftAssignmentEntity> assignments = assignmentRepository.findAllByRunId(runId);
        return toRunResponse(
                run,
                toProposedAssignmentDtos(assignments),
                deserializeWarnings(run.getWarningsJson()),
                deserializeParameters(run.getParametersJson()));
    }

    /**
     * 目視確認を完了させる。
     *
     * @param runId  実行ログID
     * @param note   確認備考
     * @param userId 確認者ユーザーID
     */
    @Transactional
    public void confirmVisualReview(Long runId, String note, Long userId) {
        ShiftAssignmentRunEntity run = assignmentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND));

        if (run.getStatus() != ShiftAssignmentRunStatus.SUCCEEDED) {
            throw new BusinessException(ShiftErrorCode.INVALID_ASSIGNMENT_RUN_STATUS);
        }

        run.confirmByVisualReview(userId, note);
        assignmentRunRepository.save(run);

        log.info("目視確認完了: runId={}, userId={}", runId, userId);
    }

    /**
     * スケジュール公開前に未確認の SUCCEEDED run がないかチェックする。
     * 存在する場合は VISUAL_REVIEW_REQUIRED 例外をスローする。
     *
     * @param scheduleId スケジュールID
     */
    public void assertNoUnreviewedRuns(Long scheduleId) {
        assignmentRunRepository
                .findTopByScheduleIdAndStatusOrderByStartedAtDesc(scheduleId, ShiftAssignmentRunStatus.SUCCEEDED)
                .ifPresent(run -> {
                    throw new BusinessException(ShiftErrorCode.VISUAL_REVIEW_REQUIRED);
                });
    }

    // --- private helpers ---

    private ShiftScheduleEntity findScheduleOrThrow(Long scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
    }

    private ShiftAssignmentStrategy findStrategy(AssignmentStrategyType type) {
        return strategies.stream()
                .filter(s -> s.getStrategyType() == type)
                .findFirst()
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.INVALID_ASSIGNMENT_RUN_STATUS));
    }

    /**
     * スロットの assignedUserIds を確定済み割当から再構築して更新する。
     */
    private void updateSlotAssignedUsers(List<ShiftAssignmentEntity> confirmedAssignments) {
        // slotId → userId リストを集約
        Map<Long, List<Long>> slotUserMap = confirmedAssignments.stream()
                .collect(Collectors.groupingBy(
                        ShiftAssignmentEntity::getSlotId,
                        Collectors.mapping(ShiftAssignmentEntity::getUserId, Collectors.toList())));

        for (Map.Entry<Long, List<Long>> entry : slotUserMap.entrySet()) {
            slotRepository.findById(entry.getKey()).ifPresent(slot -> {
                // 既存の CONFIRMED 割当ユーザーも含めて再構築
                List<ShiftAssignmentEntity> allConfirmed = assignmentRepository
                        .findAllBySlotId(slot.getId()).stream()
                        .filter(a -> a.getStatus() == ShiftAssignmentStatus.CONFIRMED)
                        .toList();
                List<Long> userIds = allConfirmed.stream()
                        .map(ShiftAssignmentEntity::getUserId)
                        .distinct()
                        .toList();

                ShiftSlotEntity updated = slot.toBuilder()
                        .assignedUserIds(serializeList(userIds))
                        .build();
                slotRepository.save(updated);
            });
        }
    }

    private AssignmentRunResponse toRunResponse(
            ShiftAssignmentRunEntity run,
            List<ProposedAssignmentDto> assignments,
            List<AssignmentWarningDto> warnings,
            AssignmentParametersDto parameters) {
        return new AssignmentRunResponse(
                run.getId(),
                run.getScheduleId(),
                run.getStrategy(),
                run.getStatus(),
                run.getTriggeredBy(),
                run.getSlotsTotal(),
                run.getSlotsFilled(),
                warnings,
                parameters,
                run.getErrorMessage(),
                run.getVisualReviewConfirmedBy(),
                run.getVisualReviewConfirmedAt(),
                run.getVisualReviewNote(),
                run.getStartedAt(),
                run.getCompletedAt(),
                assignments);
    }

    private List<ProposedAssignmentDto> toProposedAssignmentDtos(List<ShiftAssignmentEntity> assignments) {
        return assignments.stream()
                .map(a -> new ProposedAssignmentDto(
                        a.getId(),
                        a.getSlotId(),
                        a.getUserId(),
                        a.getStatus(),
                        a.getScore(),
                        a.getNote()))
                .toList();
    }

    private String serializeParameters(AssignmentParametersDto params) {
        try {
            return objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            log.warn("パラメータのシリアライズに失敗: {}", e.getMessage());
            return null;
        }
    }

    private AssignmentParametersDto deserializeParameters(String json) {
        if (json == null || json.isBlank()) {
            return AssignmentParametersDto.defaults();
        }
        try {
            return objectMapper.readValue(json, AssignmentParametersDto.class);
        } catch (JsonProcessingException e) {
            log.warn("パラメータのデシリアライズに失敗: {}", e.getMessage());
            return AssignmentParametersDto.defaults();
        }
    }

    private String serializeWarnings(List<AssignmentWarningDto> warnings) {
        if (warnings == null || warnings.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(warnings);
        } catch (JsonProcessingException e) {
            log.warn("警告リストのシリアライズに失敗: {}", e.getMessage());
            return null;
        }
    }

    private List<AssignmentWarningDto> deserializeWarnings(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<AssignmentWarningDto>>() {});
        } catch (JsonProcessingException e) {
            log.warn("警告リストのデシリアライズに失敗: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    private String serializeList(List<Long> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            log.warn("リストのシリアライズに失敗: {}", e.getMessage());
            return null;
        }
    }
}
