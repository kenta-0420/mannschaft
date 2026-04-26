package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.pdf.PdfGeneratorService;
import com.mannschaft.app.shift.ShiftErrorCode;
import com.mannschaft.app.shift.dto.ShiftScheduleResponse;
import com.mannschaft.app.shift.dto.ShiftSlotResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * シフト PDF 生成サービス。
 * F03.5 §PDF出力 — チーム全体表 / 個人タイムラインの PDF を生成する。
 * Thymeleaf テンプレートから PdfGeneratorService 経由で PDF を作成する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftPdfService {

    private final ShiftScheduleService scheduleService;
    private final ShiftSlotService shiftSlotService;
    private final PdfGeneratorService pdfGeneratorService;

    /**
     * チーム全体表 PDF を生成する。
     *
     * @param scheduleId スケジュール ID
     * @param requesterId リクエスターのユーザー ID
     * @return PDF の byte[]
     */
    public byte[] generateTeamPdf(Long scheduleId, Long requesterId) {
        ShiftScheduleResponse schedule = scheduleService.getSchedule(scheduleId);
        List<ShiftSlotResponse> slots = shiftSlotService.listSlots(scheduleId);

        Map<String, Object> variables = new HashMap<>();
        variables.put("schedule", schedule);
        variables.put("slots", slots);
        variables.put("layout", "team");

        log.info("チーム全体表 PDF 生成開始: scheduleId={}, requesterId={}", scheduleId, requesterId);
        return pdfGeneratorService.generateFromTemplate("pdf/shift-team", variables);
    }

    /**
     * 個人タイムライン PDF を生成する。
     *
     * @param scheduleId スケジュール ID
     * @param requesterId リクエスターのユーザー ID（個人フィルタ用）
     * @return PDF の byte[]
     */
    public byte[] generatePersonalPdf(Long scheduleId, Long requesterId) {
        ShiftScheduleResponse schedule = scheduleService.getSchedule(scheduleId);
        List<ShiftSlotResponse> slots = shiftSlotService.listSlots(scheduleId);

        // 個人の割り当てのみにフィルタ
        List<ShiftSlotResponse> mySlots = slots.stream()
                .filter(slot -> slot.getAssignedUserIds().contains(requesterId))
                .toList();

        Map<String, Object> variables = new HashMap<>();
        variables.put("schedule", schedule);
        variables.put("slots", mySlots);
        variables.put("userId", requesterId);
        variables.put("layout", "personal");

        log.info("個人タイムライン PDF 生成開始: scheduleId={}, requesterId={}", scheduleId, requesterId);
        return pdfGeneratorService.generateFromTemplate("pdf/shift-personal", variables);
    }
}
