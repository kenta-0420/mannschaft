package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.BulkCreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.CreateShiftSlotRequest;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import com.mannschaft.app.shift.dto.UpdateShiftSlotRequest;
import com.mannschaft.app.shift.entity.ShiftPositionEntity;
import com.mannschaft.app.shift.entity.ShiftSlotEntity;
import com.mannschaft.app.shift.repository.ShiftPositionRepository;
import com.mannschaft.app.shift.repository.ShiftSlotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * シフト枠サービス。シフト枠のCRUD・一括操作を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftSlotService {

    private final ShiftSlotRepository slotRepository;
    private final ShiftPositionRepository positionRepository;
    private final ObjectMapper objectMapper;

    /**
     * スケジュールのシフト枠一覧を取得する。
     *
     * @param scheduleId スケジュールID
     * @return シフト枠一覧
     */
    public List<ShiftSlotResponse> listSlots(Long scheduleId) {
        List<ShiftSlotEntity> entities = slotRepository.findByScheduleIdOrderBySlotDateAscStartTimeAsc(scheduleId);
        return entities.stream().map(this::toSlotResponse).toList();
    }

    /**
     * シフト枠を単体取得する。
     *
     * @param slotId シフト枠ID
     * @return シフト枠
     */
    public ShiftSlotResponse getSlot(Long slotId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
        return toSlotResponse(entity);
    }

    /**
     * シフト枠を作成する。
     *
     * @param scheduleId スケジュールID
     * @param req        作成リクエスト
     * @return 作成されたシフト枠
     */
    @Transactional
    public ShiftSlotResponse createSlot(Long scheduleId, CreateShiftSlotRequest req) {
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
     * @return 作成されたシフト枠一覧
     */
    @Transactional
    public List<ShiftSlotResponse> bulkCreateSlots(Long scheduleId, BulkCreateShiftSlotRequest req) {
        List<ShiftSlotEntity> entities = req.getSlots().stream()
                .map(slotReq -> ShiftSlotEntity.builder()
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
     * @return 更新されたシフト枠
     */
    @Transactional
    public ShiftSlotResponse updateSlot(Long slotId, UpdateShiftSlotRequest req) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);

        ShiftSlotEntity.ShiftSlotEntityBuilder builder = entity.toBuilder();

        if (req.getSlotDate() != null) builder.slotDate(req.getSlotDate());
        if (req.getStartTime() != null) builder.startTime(req.getStartTime());
        if (req.getEndTime() != null) builder.endTime(req.getEndTime());
        if (req.getPositionId() != null) builder.positionId(req.getPositionId());
        if (req.getRequiredCount() != null) builder.requiredCount(req.getRequiredCount());
        if (req.getAssignedUserIds() != null) builder.assignedUserIds(serializeUserIds(req.getAssignedUserIds()));
        if (req.getNote() != null) builder.note(req.getNote());

        entity = slotRepository.save(builder.build());
        log.info("シフト枠更新: id={}", slotId);
        return toSlotResponse(entity);
    }

    /**
     * シフト枠を削除する。
     *
     * @param slotId シフト枠ID
     */
    @Transactional
    public void deleteSlot(Long slotId) {
        ShiftSlotEntity entity = findSlotOrThrow(slotId);
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
     * エンティティをレスポンスDTOに変換する。
     */
    private ShiftSlotResponse toSlotResponse(ShiftSlotEntity entity) {
        String positionName = null;
        if (entity.getPositionId() != null) {
            positionName = positionRepository.findById(entity.getPositionId())
                    .map(ShiftPositionEntity::getName)
                    .orElse(null);
        }

        return new ShiftSlotResponse(
                entity.getId(),
                entity.getScheduleId(),
                entity.getSlotDate(),
                entity.getStartTime(),
                entity.getEndTime(),
                entity.getPositionId(),
                positionName,
                entity.getRequiredCount(),
                deserializeUserIds(entity.getAssignedUserIds()),
                entity.getNote());
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
