package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.dto.TermSuggestionResponse;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetableSummary;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;

/**
 * F06.5 Phase 3: 学年・学期自動提案サービス（EP #22・§12.1）。
 *
 * <p>基準日（baseDate）から本人の個人時間割（status=ACTIVE）を照合し、
 * 学年・学期を提案する。timetable ドメインへの越境依存を
 * {@link PersonalTimetableService#findEffectiveAt} Service 経由で解消（D-1 遵守）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReflectionTermSuggestionService {

    private final PersonalTimetableService personalTimetableService;

    /**
     * 基準日から有効な個人時間割の学年・学期を返す（AC-38）。
     *
     * @param userId      認証ユーザーID
     * @param baseDateStr 基準日文字列（YYYY-MM-DD・null/空文字列の場合は今日）
     * @return 提案する学年・学期（該当なしは academicYear=null / termLabel=null）
     */
    @Transactional(readOnly = true)
    public TermSuggestionResponse suggest(Long userId, String baseDateStr) {
        LocalDate baseDate = parseBaseDate(baseDateStr);
        Optional<PersonalTimetableSummary> summary =
                personalTimetableService.findEffectiveAt(userId, baseDate);
        return summary
                .map(s -> TermSuggestionResponse.builder()
                        .academicYear(s.academicYear())
                        .termLabel(s.termLabel())
                        .build())
                .orElse(TermSuggestionResponse.builder()
                        .academicYear(null)
                        .termLabel(null)
                        .build());
    }

    /** baseDate 文字列を LocalDate にパースする。null/空は今日を返す。 */
    private LocalDate parseBaseDate(String baseDateStr) {
        if (baseDateStr == null || baseDateStr.isBlank()) {
            return LocalDate.now();
        }
        return LocalDate.parse(baseDateStr);
    }
}
