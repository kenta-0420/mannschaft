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

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * シフト PDF 生成サービス（<b>トランザクション本体</b>）。
 * F03.5 §PDF出力 — チーム全体表 / 個人タイムラインの PDF を生成する。
 * Thymeleaf テンプレートから PdfGeneratorService 経由で PDF を作成する。
 *
 * <p><b>認可はここに無い（CMP-260923-0954 W6a）:</b> 認可（メンバーかつ非 SUPPORTER、越境の 404 隠蔽、
 * SYSTEM_ADMIN の扱い）は {@link ShiftPdfFacade} が行う。本クラスは {@code AccessControlService} に
 * 依存せず、Facade が判定した {@code privileged}（管理者側か）の真偽を受け取って、tx の中の最新の状態で
 * 公開状態（未公開の秘匿）を再判定する。スケジュール・枠の読み直しは認可を持たない
 * {@link ShiftScheduleService#getSchedule(Long, boolean)} / {@link ShiftSlotService#listSlots(Long, boolean)} を通す。</p>
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
     * @param scheduleId  スケジュール ID
     * @param requesterId リクエスターのユーザー ID
     * @param privileged  管理者側（SYSTEM_ADMIN または当該チームの ADMIN 以上）なら true
     * @return PDF の byte[]
     */
    public byte[] generateTeamPdf(Long scheduleId, Long requesterId, boolean privileged) {
        ShiftScheduleResponse schedule = scheduleService.getSchedule(scheduleId, privileged);
        checkPubliclyReleasable(schedule, privileged);
        List<ShiftSlotResponse> slots = shiftSlotService.listSlots(scheduleId, privileged);

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
     * @param scheduleId  スケジュール ID
     * @param requesterId リクエスターのユーザー ID（個人フィルタ用）
     * @param privileged  管理者側なら true
     * @return PDF の byte[]
     */
    public byte[] generatePersonalPdf(Long scheduleId, Long requesterId, boolean privileged) {
        ShiftScheduleResponse schedule = scheduleService.getSchedule(scheduleId, privileged);
        checkPubliclyReleasable(schedule, privileged);
        List<ShiftSlotResponse> slots = shiftSlotService.listSlots(scheduleId, privileged);

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

    /**
     * 非管理者に PDF を発行してよい状態か確認する（CMP-260826-2127 / AC-5）。
     *
     * <p>未公開（{@code DRAFT} / {@code ARCHIVED} かつ {@code publishedAt} が NULL）に加えて、
     * {@code COLLECTING} / {@code ADJUSTING} も 404 とする。割当を伏せたまま流すと
     * 「割当欄が全部空白のシフト表 PDF」という無意味な紙が出るためである
     *（{@code 04_security_operations.md} §6【v2.2】の既存宣言と整合させる）。</p>
     *
     * <p>本サービスはエンティティを持たず DTO しか受け取らない。DTO の {@code status} は
     * 部分的にしか組み立てられていない経路では {@code null} になりうるため、
     * <b>{@code null} は未公開扱い（fail-closed）</b>とする（判定は
     * {@link ShiftScheduleVisibilityPolicy#classifyByStatusName} に閉じる）。</p>
     *
     * @param schedule   対象シフト表
     * @param privileged 管理者側なら true（未公開でも発行できる）
     * @throws BusinessException 非管理者に対して未公開の場合（SHIFT_SCHEDULE_NOT_FOUND / 404）
     */
    private void checkPubliclyReleasable(ShiftScheduleResponse schedule, boolean privileged) {
        if (privileged) {
            return;
        }
        String statusName = schedule.getStatus() != null ? schedule.getStatus().status() : null;
        LocalDateTime publishedAt = schedule.getStatus() != null ? schedule.getStatus().publishedAt() : null;
        ShiftScheduleVisibilityPolicy.Visibility visibility =
                ShiftScheduleVisibilityPolicy.classifyByStatusName(statusName, publishedAt);
        if (visibility != ShiftScheduleVisibilityPolicy.Visibility.FULL) {
            throw new BusinessException(ShiftErrorCode.SHIFT_SCHEDULE_NOT_FOUND);
        }
    }
}
