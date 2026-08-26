package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.SlotAssignmentPatchRequest;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftScheduleRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * シフト枠サービス。シフト枠のCRUD・一括操作を担当する。
 *
 * <p><b>認可（認可根治 Wave6）:</b> 本サービスはかつて {@code AccessControlService} を
 * import すらしておらず、操作者を受け取る口が無かった。結果として
 * {@code ShiftSlotController} の全エンドポイントが無認可で、任意チームのシフト枠を
 * 閲覧・改変・割当できる状態だった。本改修で全 public メソッドが操作者 {@code userId} を
 * 受け取り、<b>シフト枠の所属スケジュール実体から解決した teamId</b> に対して per-scope 認可する
 *（パス変数・クエリの scope 値を鵜呑みにしないことで BOLA を封鎖する）。</p>
 *
 * <p>粒度は同ドメインの既存実装に合わせる:</p>
 * <ul>
 *   <li><b>参照</b>（{@code listSlots} / {@code getSlot}）: 当該チームのメンバー、ただし
 *       SUPPORTER は不可。{@code ShiftPdfService#checkMemberAndNotSupporter} と同一方針
 *       （PDF で SUPPORTER に伏せている情報を生 API から取れては意味がないため）。</li>
 *   <li><b>更新・割当</b>（作成/一括作成/更新/削除/差分割当）: ADMIN/DEPUTY_ADMIN 以上
 *       （SYSTEM_ADMIN 短絡）。{@code ShiftScheduleService#checkScheduleAdminAccess} と同一方針。</li>
 * </ul>
 *
 * <p>認可失敗は参照・更新とも {@code COMMON_002}（403）とする。越境を 404 に寄せず 403 とするのは
 * 同ドメインの既存契約テスト {@code ShiftScheduleScopeContractIT}（Wave3-B6）が別 scope ADMIN に
 * 403 を期待しており、そちらへ揃えるため。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftSlotService {

    private final ShiftSlotRepository slotRepository;
    private final ShiftPositionRepository positionRepository;
    private final ShiftScheduleRepository scheduleRepository;
    private final AccessControlService accessControlService;
    private final ObjectMapper objectMapper;

    /**
     * スケジュールのシフト枠一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @param userId     操作者ユーザーID
     * @return シフト枠一覧
     */
    public List<ShiftSlotResponse> listSlots(Long scheduleId, Long userId) {
        checkScheduleReadAccess(scheduleId, userId);
        List<ShiftSlotEntity> entities = slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(scheduleId);
        return entities.stream().map(this::toSlotResponse).toList();
    }

    /**
     * シフト枠を単体取得する。
     *
     * @param slotId シフト枠ID
     * @param userId 操作者ユーザーID
     * @return シフト枠
     */
    public ShiftSlotResponse getSlot(Long slotId, Long userId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        checkScheduleReadAccess(entity.getScheduleId(), userId);
        return toSlotResponse(entity);
    }

    /**
     * シフト枠を作成する。
     *
     * @param scheduleId スケジュールID
     * @param req        作成リクエスト
     * @param userId     操作者ユーザーID
     * @return 作成されたシフト枠
     */
    @Transactional
    public ShiftSlotResponse createSlot(Long scheduleId, CreateShiftSlotRequest req, Long userId) {
        checkScheduleAdminAccess(scheduleId, userId);
        ShiftSlotEntity entity = ShiftSlotEntity.builder()
                .scheduleId(scheduleId)
                .slotDate(req.getSlotDate())
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .positionId(req.getPositionId())
                .requiredCount(req.getRequiredCount() != null ? req.getRequiredCount() : 1)
                .note(req.getNote())
                .build();

        entity = slotRepository.save(entity);
        log.info("シフト枠作成: id={}, scheduleId={}", entity.getId(), scheduleId);
        return toSlotResponse(entity);
    }

    /**
     * シフト枠を一括作成する。
     *
     * @param scheduleId スケジュールID
     * @param req        一括作成リクエスト
     * @param userId     操作者ユーザーID
     * @return 作成されたシフト枠一覧
     */
    @Transactional
    public List<ShiftSlotResponse> bulkCreateSlots(Long scheduleId, BulkCreateShiftSlotRequest req, Long userId) {
        checkScheduleAdminAccess(scheduleId, userId);
        List<ShiftSlotEntity> entities = req.getSlots().stream()
                .map(slotReq -> (ShiftSlotEntity) ShiftSlotEntity.builder()
                        .scheduleId(scheduleId)
                        .slotDate(slotReq.getSlotDate())
                        .startTime(slotReq.getStartTime())
                        .endTime(slotReq.getEndTime())
                        .positionId(slotReq.getPositionId())
                        .requiredCount(slotReq.getRequiredCount() != null ? slotReq.getRequiredCount() : 1)
                        .note(slotReq.getNote())
                        .build())
                .toList();

        entities = slotRepository.saveAll(entities);
        log.info("シフト枠一括作成: scheduleId={}, count={}", scheduleId, entities.size());
        return entities.stream().map(this::toSlotResponse).toList();
    }

    /**
     * シフト枠を更新する。
     *
     * @param slotId シフト枠ID
     * @param req    更新リクエスト
     * @param userId 操作者ユーザーID
     * @return 更新されたシフト枠
     */
    @Transactional
    public ShiftSlotResponse updateSlot(Long slotId, UpdateShiftSlotRequest req, Long userId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        checkScheduleAdminAccess(entity.getScheduleId(), userId);

        // managed entity を直接ミューテート（toBuilder().build() でなくドメインメソッドで更新）。
        // ShiftSlotEntity は @Builder(toBuilder=true) / @SuperBuilder でない / BaseEntity継承(自前id無)
        // の3条件が揃うため、toBuilder().build()→save では id=null の新インスタンスが生成され
        // UPDATE でなく INSERT が走る行重複バグになる。
        entity.applyUpdate(
                req.getSlotDate(),
                req.getStartTime(),
                req.getEndTime(),
                req.getPositionId(),
                req.getRequiredCount(),
                req.getAssignedUserIds() != null ? serializeUserIds(req.getAssignedUserIds()) : null,
                req.getNote()
        );

        slotRepository.save(entity);
        log.info("シフト枠更新: id={}", slotId);
        return toSlotResponse(entity);
    }

    /**
     * スロットの割当ユーザーを差分更新する（楽観ロック付き）。
     *
     * @param slotId  シフト枠ID
     * @param request 差分割当リクエスト
     * @param userId  操作者ユーザーID
     * @return 更新後のシフト枠レスポンス
     */
    @Transactional
    public ShiftSlotResponse patchSlotAssignments(Long slotId, SlotAssignmentPatchRequest request, Long userId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        checkScheduleAdminAccess(entity.getScheduleId(), userId);

        // 楽観ロックチェック: version が一致しない場合は 409
        if (!entity.getVersion().equals(request.slotVersion().longValue())) {
            throw new BusinessException(ShiftErrorCode.OPTIMISTIC_LOCK_CONFLICT);
        }

        // 現在の割当ユーザーリストを取得
        List<Long> currentUserIds = new ArrayList<>(deserializeUserIds(entity.getAssignedUserIds()));

        // ユーザーを追加（ループ変数は操作者 userId と衝突しないよう addUserId とする）
        if (request.addUserIds() != null) {
            for (Long addUserId : request.addUserIds()) {
                if (!currentUserIds.contains(addUserId)) {
                    currentUserIds.add(addUserId);
                }
            }
        }

        // ユーザーを削除
        if (request.removeUserIds() != null) {
            currentUserIds.removeAll(request.removeUserIds());
        }

        // 必要人数超過チェック
        if (currentUserIds.size() > entity.getRequiredCount()) {
            throw new BusinessException(ShiftErrorCode.SLOT_ASSIGNMENT_EXCEEDED);
        }

        // managed entity を直接ミューテート（toBuilder().build() 行重複バグ回避）。
        entity.updateAssignedUserIds(serializeUserIds(currentUserIds));
        slotRepository.save(entity);

        log.info("スロット差分割当更新: slotId={}, added={}, removed={}",
                slotId,
                request.addUserIds() != null ? request.addUserIds().size() : 0,
                request.removeUserIds() != null ? request.removeUserIds().size() : 0);
        return toSlotResponse(entity);
    }

    /**
     * シフト枠を削除する。
     *
     * @param slotId シフト枠ID
     * @param userId 操作者ユーザーID
     */
    @Transactional
    public void deleteSlot(Long slotId, Long userId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        checkScheduleAdminAccess(entity.getScheduleId(), userId);
        slotRepository.delete(entity);
        log.info("シフト枠削除: id={}", slotId);
    }

    /**
     * シフト枠を取得する。存在しない場合は例外をスローする。
     */
    ShiftSlotEntity findSlotOrThrow(Long id) {
        return slotRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SLOT_NOT_FOUND));
    }

    /**
     * スケジュール ID から所属チーム ID を解決する。
     *
     * <p>scope をパス変数・クエリ入力でなく<b>スケジュール実体由来</b>にすることで、
     * 「他チームの slotId / scheduleId を直接指定して越境する」BOLA を封鎖する。</p>
     *
     * @param scheduleId スケジュール ID
     * @return 所属チーム ID
     */
    private Long resolveTeamId(Long scheduleId) {
        return scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND))
                .getTeamId();
    }

    /**
     * シフト枠の参照認可（当該チームのメンバー、ただし SUPPORTER は不可）。
     *
     * @param scheduleId スケジュール ID
     * @param userId     操作者ユーザー ID
     * @throws BusinessException メンバーでない場合、または SUPPORTER の場合（COMMON_002 / 403）
     */
    private void checkScheduleReadAccess(Long scheduleId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        Long teamId = resolveTeamId(scheduleId);
        if (!accessControlService.isMember(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        if (accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }

    /**
     * シフト枠の更新・割当認可（SYSTEM_ADMIN 短絡 or 当該チームの ADMIN/DEPUTY_ADMIN）。
     *
     * @param scheduleId スケジュール ID
     * @param userId     操作者ユーザー ID
     * @throws BusinessException 権限が無い場合（COMMON_002 / 403）
     */
    private void checkScheduleAdminAccess(Long scheduleId, Long userId) {
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        accessControlService.checkAdminOrAbove(userId, resolveTeamId(scheduleId), "TEAM");
    }

    /**
     * エンティティをレスポンスDTOに変換する。
     */
    private ShiftSlotResponse toSlotResponse(ShiftSlotEntity entity) {
        String positionName = null;
        if (entity.getPositionId() != null) {
            positionName = positionRepository.findById(entity.getPositionId())
                    .map(ShiftPositionEntity::getName)
                    .orElse(null);
        }

        return ShiftSlotResponse.builder()
                .id(entity.getId())
                .scheduleId(entity.getScheduleId())
                .time(new ShiftSlotResponse.ShiftSlotTimeDto(
                        entity.getSlotDate(), entity.getStartTime(), entity.getEndTime()))
                .position(new ShiftSlotResponse.ShiftSlotPositionDto(
                        entity.getPositionId(), positionName, entity.getRequiredCount()))
                .assignedUserIds(deserializeUserIds(entity.getAssignedUserIds()))
                .note(entity.getNote())
                .build();
    }

    /**
     * ユーザーIDリストをJSON文字列にシリアライズする。
     */
    private String serializeUserIds(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(userIds);
        } catch (JsonProcessingException e) {
            log.warn("ユーザーIDリストのシリアライズに失敗: {}", e.getMessage());
            return null;
        }
    }

    /**
     * JSON文字列からユーザーIDリストをデシリアライズする。
     */
    private List<Long> deserializeUserIds(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Long>>() {});
        } catch (JsonProcessingException e) {
            log.warn("ユーザーIDリストのデシリアライズに失敗: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
