package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.dto.LinkableSlotResponse;
import com.mannschaft.app.timetable.personal.dto.TimetableWeekSlotInfo;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * F06.5 Phase 2: 科目紐づけ候補一覧の提供サービス（§11.3 AC-30）。
 *
 * <p>timetable ドメインの {@link PersonalTimetableDashboardService#listAllWeekSlots} から
 * {@link TimetableWeekSlotInfo} リストを取得し、reflection ドメインの {@link LinkableSlotResponse} に
 * マッピングして返す。これにより timetable→reflection の依存方向を逆転させ、
 * reflection がtimetable に依存する正しいドメイン境界を維持する。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReflectionLinkableSlotService {

    private final PersonalTimetableDashboardService personalTimetableDashboardService;

    /**
     * 本人の週全体の時間割スロットを科目単位で重複排除した「科目紐づけ候補」を返す。
     *
     * @param userId 対象ユーザー
     * @param today  今日の日付（effective 期間判定の基準日）
     * @return 重複排除済み候補一覧（時間割ゼロの場合は空リスト）
     */
    public List<LinkableSlotResponse> listLinkableSlots(Long userId, LocalDate today) {
        List<TimetableWeekSlotInfo> slots = personalTimetableDashboardService.listAllWeekSlots(userId, today);
        return slots.stream()
                .map(s -> new LinkableSlotResponse(
                        s.kind(),
                        s.slotId(),
                        s.subjectName(),
                        s.courseCode(),
                        s.teacherName(),
                        s.periodLabel()
                ))
                .toList();
    }
}
