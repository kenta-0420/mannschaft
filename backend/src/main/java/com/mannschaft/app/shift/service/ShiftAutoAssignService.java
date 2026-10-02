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
 *
 * <p><b>認可はここに置かない（CMP-260923-0954 W2）:</b> 権限の確認は非トランザクションの
 * {@link ShiftAutoAssignFacade} が行い、通ったものだけを本クラスの tx が実行する。
 * {@code AccessControlService} / {@code ScopeConcealingAccessGate} への依存と認可用の private メソッドは持たない
 * （D-3T 番人と {@code ShiftTxFacadeArchTest} が固定）。</p>
 *
 * <p><b>BOLA 封鎖:</b> スコープ（チーム）は<b>パス変数ではなく実体由来</b>で解決する。
 * Facade は認可の前に {@link #resolveScheduleTeamId} / {@link #resolveRunTeamId}（readOnly・ロックなし）で
 * run 実体 → {@code scheduleId} → スケジュール実体 → {@code teamId} とたどる。パスの {@code scheduleId} と
 * run 実体の {@code scheduleId} が食い違う場合は<b>存在を秘匿して 404</b>（{@code ASSIGNMENT_RUN_NOT_FOUND}）。</p>
 *
 * <p><b>ロックの順序（K6）:</b> スケジュール行の {@code FOR UPDATE}（親削除との直列化）は<b>認可の後</b>、
 * tx の中で取る（素の読み取り → 認可 → FOR UPDATE して読み直す）。部外者のリクエストが他チームの行ロックを
 * 取って待たせることがない。tx 内では解決時と同じ経路で読み直し、不在なら解決時と同じコードの 404（DB 不変）。</p>
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
     * <p>認可（スケジュール実体から解決したチームの ADMIN/DEPUTY_ADMIN のみ）は
     * {@link ShiftAutoAssignFacade#runAutoAssign} が tx の外で済ませる。</p>
     *
     * @param scheduleId  スケジュールID
     * @param request     自動割当リクエスト
     * @param triggeredBy 実行者ユーザーID
     * @return 実行ログレスポンス
     */
    @Transactional
    public AssignmentRunResponse runAutoAssign(Long scheduleId, AutoAssignRequest request, Long triggeredBy) {
        // 認可は Facade 済み。ここで初めて親行を FOR UPDATE する（K6: 部外者にはロックを取らせない）。
        // 認可の後・tx の前にスケジュールが消えた競合は、読み直しの不在で 404（Facade の解決時と同じコード・DB 不変）。
        ShiftScheduleEntity schedule = scheduleRepository.findByIdForUpdate(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));

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
                    .map(proposal -> (ShiftAssignmentEntity) ShiftAssignmentEntity.builder()
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
     * <p>認可（run 実体 → スケジュール実体 → チームと辿り、当該チームの ADMIN/DEPUTY_ADMIN のみ）は
     * {@link ShiftAutoAssignFacade#confirmAutoAssign} が済ませる。パスの {@code scheduleId} と run 実体の
     * 食い違いは 404。</p>
     *
     * @param scheduleId スケジュールID
     * @param request    確定リクエスト
     */
    @Transactional
    public void confirmAutoAssign(Long scheduleId, ConfirmAutoAssignRequest request) {
        // 認可は Facade 済み。run を読み直し、パスのスケジュールとの突合・親行の FOR UPDATE をここで行う。
        ShiftAssignmentRunEntity run = findRunOrThrow(request.runId());
        lockScheduleOfRun(run, scheduleId);

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
     * <p>認可（run 実体由来のチームの ADMIN/DEPUTY_ADMIN のみ）は
     * {@link ShiftAutoAssignFacade#revokeAutoAssign} が済ませる。</p>
     *
     * @param scheduleId スケジュールID
     * @param runId      実行ログID
     */
    @Transactional
    public void revokeAutoAssign(Long scheduleId, Long runId) {
        ShiftAssignmentRunEntity run = findRunOrThrow(runId);
        lockScheduleOfRun(run, scheduleId);

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
     * <p>認可: 自動割当は管理者専用の運用機能であり、履歴には誰がいつ実行したか・
     * 何枠が埋まったか等の運用情報が含まれる。書き込み系と同じ ADMIN/DEPUTY_ADMIN 粒度とする
     * （{@link ShiftAutoAssignFacade#getAssignmentRuns} が tx の外で判定する）。</p>
     *
     * @param scheduleId スケジュールID
     * @return 実行ログ一覧
     */
    public List<AssignmentRunResponse> getAssignmentRuns(Long scheduleId) {
        // 認可は Facade 済み。認可の後にスケジュールが消えた競合を 404 にするため読み直す。
        findScheduleOrThrow(scheduleId);

        List<ShiftAssignmentRunEntity> runs = assignmentRunRepository
                .findAllByScheduleIdOrderByStartedAtDesc(scheduleId);
        return runs.stream()
                .map(run -> toRunResponse(run, null, deserializeWarnings(run.getWarningsJson()), deserializeParameters(run.getParametersJson())))
                .toList();
    }

    /**
     * 自動割当実行ログ詳細を取得する（割当提案一覧を含む）。
     *
     * <p>認可: 詳細は「誰をどの枠に入れる提案か」＝メンバーの {@code userId} 一覧を含むため、
     * run 実体由来のチームの ADMIN/DEPUTY_ADMIN のみ閲覧可（{@link ShiftAutoAssignFacade#getAssignmentRunDetail}）。
     * パス変数にスコープが無い（{@code /assignment-runs/{runId}}）ため、突合対象の scheduleId は渡さない。</p>
     *
     * @param runId  実行ログID
     * @return 実行ログ詳細
     */
    public AssignmentRunResponse getAssignmentRunDetail(Long runId) {
        ShiftAssignmentRunEntity run = findRunOrThrow(runId);
        // 認可は Facade 済み。認可の後にスケジュールが消えた競合を 404（ASSIGNMENT_RUN_NOT_FOUND）にする。
        traceTeamIdOfRunConcealed(run);

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
     * <p>認可: 本 API は {@code confirmAutoAssign} の前提条件
     * （{@code VISUAL_REVIEW_REQUIRED}）を解除する操作のため、確定と<b>同一粒度</b>
     * （run 実体由来のチームの ADMIN/DEPUTY_ADMIN）で独立に認可する
     * （{@link ShiftAutoAssignFacade#confirmVisualReview} が tx の外で判定する）。</p>
     *
     * @param runId  実行ログID
     * @param note   確認備考
     * @param userId 確認者ユーザーID
     */
    @Transactional
    public void confirmVisualReview(Long runId, String note, Long userId) {
        ShiftAssignmentRunEntity run = findRunOrThrow(runId);
        // 認可は Facade 済み。認可の後にスケジュールが消えた競合を 404（ASSIGNMENT_RUN_NOT_FOUND）にする。
        traceTeamIdOfRunConcealed(run);

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
     * <p><b>認可を敷かない理由（Wave7）:</b> 本メソッドは公開エンドポイントの入口ではなく、
     * {@code ShiftScheduleService#publish}（当該チームの ADMIN 認可済み）から呼ばれる
     * <b>内部の事前条件チェック</b>である。ここに認可を埋めると呼び出し元の認可と二重になるうえ、
     * 将来バッチから呼ぶ際に巻き添えで落ちる。認可は public 入口（publish）側に置く方針
     * （{@code feedback_authz_gate_on_public_entry_not_shared_method}）。</p>
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

    private ShiftAssignmentRunEntity findRunOrThrow(Long runId) {
        return assignmentRunRepository.findById(runId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND));
    }

    // ═════════════════════════════════════════════════════════════════════
    // scope 解決（Facade が認可の前に呼ぶ readOnly の読み取り。ロックは取らない。Entity は返さない）
    // ═════════════════════════════════════════════════════════════════════

    /**
     * スケジュール ID から所属チーム ID を解決する。不在・論理削除済みなら {@code SHIFT_SCHEDULE_NOT_FOUND}。
     *
     * @param scheduleId スケジュール ID
     * @return 所属チーム ID
     */
    public Long resolveScheduleTeamId(Long scheduleId) {
        return findScheduleOrThrow(scheduleId).getTeamId();
    }

    /**
     * run 実体から所属チーム ID を解決する。
     *
     * <p>{@code expectedScheduleId} が非 null（パスにスケジュールを持つ確定・破棄）の場合は、run 実体の
     * scheduleId と突合し、食い違えば存在を秘匿して {@code ASSIGNMENT_RUN_NOT_FOUND}、スケジュールが不在なら
     * {@code SHIFT_SCHEDULE_NOT_FOUND}。null（パスにスコープを持たない詳細・目視確認）の場合は、
     * スケジュールが不在でも {@code ASSIGNMENT_RUN_NOT_FOUND}（是正前の応答と同一）。</p>
     *
     * @param runId              実行ログ ID
     * @param expectedScheduleId パス変数由来のスケジュール ID（突合しない経路は null）
     * @return 所属チーム ID
     */
    public Long resolveRunTeamId(Long runId, Long expectedScheduleId) {
        ShiftAssignmentRunEntity run = findRunOrThrow(runId);
        if (expectedScheduleId == null) {
            return traceTeamIdOfRunConcealed(run);
        }
        assertRunBelongsToPathSchedule(run, expectedScheduleId);
        return findScheduleOrThrow(run.getScheduleId()).getTeamId();
    }

    /**
     * パスのスケジュールに属さない run は「存在しない」と同じ応答（404）に寄せる。
     * 403 と 404 を撃ち分けると他チームの runId の存在有無が観測できてしまうため。
     */
    private void assertRunBelongsToPathSchedule(ShiftAssignmentRunEntity run, Long expectedScheduleId) {
        if (!expectedScheduleId.equals(run.getScheduleId())) {
            throw new BusinessException(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND);
        }
    }

    /**
     * 確定・破棄の tx 内: パスのスケジュールとの突合のあと、親スケジュール行を {@code FOR UPDATE} で読み直す
     * （親削除と子の更新を同じ親行のロックで直列化する）。呼び出しは Facade の認可の<b>後</b>。
     */
    private void lockScheduleOfRun(ShiftAssignmentRunEntity run, Long expectedScheduleId) {
        assertRunBelongsToPathSchedule(run, expectedScheduleId);
        scheduleRepository.findByIdForUpdate(run.getScheduleId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND));
    }

    /**
     * run からスケジュールを経由してチーム ID を解決する（<b>存在秘匿版</b>）。
     * スケジュールが不在なら {@code ASSIGNMENT_RUN_NOT_FOUND}。
     */
    private Long traceTeamIdOfRunConcealed(ShiftAssignmentRunEntity run) {
        return scheduleRepository.findById(run.getScheduleId())
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.ASSIGNMENT_RUN_NOT_FOUND))
                .getTeamId();
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

                // managed entity を直接ミューテート（toBuilder().build() 行重複バグ回避）。
                // slot は findById で取得した managed entity なので直接ミューテートで UPDATE になる。
                slot.updateAssignedUserIds(serializeList(userIds));
                slotRepository.save(slot);
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

    /**
     * 割当ユーザー ID リストを {@code assigned_user_ids} の JSON 形式へ直列化する。
     *
     * <p>CMP-260908-2117: 実体は {@link com.mannschaft.app.shift.ShiftAssignedUserIds}
     *（割当 JSON の読み書きの唯一の定義）に委譲する。手動割当側と別実装のままだと
     * 空リストの表現（{@code NULL} か {@code []} か）が経路ごとにずれる。</p>
     */
    private String serializeList(List<Long> list) {
        return com.mannschaft.app.shift.ShiftAssignedUserIds.serialize(list);
    }
}
