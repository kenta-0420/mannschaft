package com.mannschaft.app.shift.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
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
    private final AccessControlService accessControlService;

    /**
     * チーム全体表 PDF を生成する。
     *
     * <p>認可ルール: チームのメンバー（MEMBER 以上）のみ許可。SUPPORTER は不可。</p>
     *
     * @param scheduleId  スケジュール ID
     * @param requesterId リクエスターのユーザー ID
     * @return PDF の byte[]
     */
    public byte[] generateTeamPdf(Long scheduleId, Long requesterId) {
        // getSchedule 側でも同等の参照認可（メンバーかつ非SUPPORTER）が走る（多層防御）。
        ShiftScheduleResponse schedule = scheduleService.getSchedule(scheduleId, requesterId);
        checkMemberAndNotSupporter(requesterId, schedule.getTeamId());
        checkPubliclyReleasable(schedule, requesterId);
        // listSlots 側でも同等の参照認可が走る（多層防御）。requesterId をそのまま引き渡す。
        List<ShiftSlotResponse> slots = shiftSlotService.listSlots(scheduleId, requesterId);

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
     * <p>認可ルール: チームのメンバー（MEMBER 以上）のみ許可。SUPPORTER は不可。</p>
     *
     * @param scheduleId  スケジュール ID
     * @param requesterId リクエスターのユーザー ID（個人フィルタ用）
     * @return PDF の byte[]
     */
    public byte[] generatePersonalPdf(Long scheduleId, Long requesterId) {
        // getSchedule 側でも同等の参照認可（メンバーかつ非SUPPORTER）が走る（多層防御）。
        ShiftScheduleResponse schedule = scheduleService.getSchedule(scheduleId, requesterId);
        checkMemberAndNotSupporter(requesterId, schedule.getTeamId());
        checkPubliclyReleasable(schedule, requesterId);
        // listSlots 側でも同等の参照認可が走る（多層防御）。requesterId をそのまま引き渡す。
        List<ShiftSlotResponse> slots = shiftSlotService.listSlots(scheduleId, requesterId);

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
     * @param schedule    対象シフト表
     * @param requesterId リクエスターのユーザー ID
     * @throws BusinessException 非管理者に対して未公開の場合（SHIFT_SCHEDULE_NOT_FOUND / 404）
     */
    private void checkPubliclyReleasable(ShiftScheduleResponse schedule, Long requesterId) {
        if (accessControlService.isSystemAdmin(requesterId)
                || accessControlService.isAdminOrAbove(requesterId, schedule.getTeamId(), "TEAM")) {
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

    /**
     * リクエスターがチームのメンバーであり、かつ SUPPORTER でないことを確認する。
     *
     * <p>SUPPORTER は閲覧者ロールであり、シフト PDF のような運用情報への
     * アクセスは認められない（IDOR 防止も兼ねる）。</p>
     *
     * @param userId  リクエスターのユーザー ID
     * @param teamId  チーム ID
     * @throws BusinessException メンバーでない場合、または SUPPORTER の場合（COMMON_002）
     */
    private void checkMemberAndNotSupporter(Long userId, Long teamId) {
        // SYSTEM_ADMIN 短絡（CMP-260826-2127 / AC-11(b)）。同ドメインの
        // ShiftScheduleService#checkTeamReadAccess・ShiftSlotService#checkScheduleReadAccess は
        // いずれも短絡するのに、ここだけ短絡せず非メンバーの SYSTEM_ADMIN が 403 になっていた。
        if (accessControlService.isSystemAdmin(userId)) {
            return;
        }
        if (!accessControlService.isMember(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        if (accessControlService.isSupporter(userId, teamId, "TEAM")) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
    }
}
