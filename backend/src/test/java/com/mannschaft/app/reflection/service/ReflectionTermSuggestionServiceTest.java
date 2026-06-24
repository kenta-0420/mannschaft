package com.mannschaft.app.reflection.service;

import com.mannschaft.app.reflection.dto.TermSuggestionResponse;
import com.mannschaft.app.timetable.personal.dto.PersonalTimetableSummary;
import com.mannschaft.app.timetable.personal.service.PersonalTimetableService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * {@link ReflectionTermSuggestionService} 単体テスト（F06.5 Phase 3・AC-38）。
 *
 * <p>カバー: 基準日が有効範囲内の時間割から academicYear/termLabel を提案 / 複数該当時は最遅を採用 /
 * 該当なしは null 提案 / baseDate 省略時は今日基準。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReflectionTermSuggestionService 単体テスト")
class ReflectionTermSuggestionServiceTest {

    @Mock private PersonalTimetableService personalTimetableService;

    @InjectMocks private ReflectionTermSuggestionService service;

    private static final Long USER_ID = 100L;

    @Test
    @DisplayName("AC-38(a): 基準日が effectiveFrom〜effectiveUntil 範囲内の時間割から academicYear/termLabel を返す")
    void suggest_withinRange_returnsSummary() {
        LocalDate baseDate = LocalDate.of(2026, 6, 15);
        given(personalTimetableService.findEffectiveAt(USER_ID, baseDate))
                .willReturn(Optional.of(new PersonalTimetableSummary(2026, "1学期")));

        TermSuggestionResponse result = service.suggest(USER_ID, "2026-06-15");

        assertThat(result.academicYear()).isEqualTo(2026);
        assertThat(result.termLabel()).isEqualTo("1学期");
    }

    @Test
    @DisplayName("AC-38(b): 該当する時間割がない場合は academicYear=null / termLabel=null")
    void suggest_noMatch_returnsNull() {
        given(personalTimetableService.findEffectiveAt(eq(USER_ID), any(LocalDate.class)))
                .willReturn(Optional.empty());

        TermSuggestionResponse result = service.suggest(USER_ID, "2026-01-01");

        assertThat(result.academicYear()).isNull();
        assertThat(result.termLabel()).isNull();
    }

    @Test
    @DisplayName("AC-38(c): baseDate 省略時は今日基準で findEffectiveAt が呼ばれる（今日を含む）")
    void suggest_noBaseDate_usesToday() {
        LocalDate today = LocalDate.now();
        given(personalTimetableService.findEffectiveAt(USER_ID, today))
                .willReturn(Optional.of(new PersonalTimetableSummary(2026, "前期")));

        TermSuggestionResponse result = service.suggest(USER_ID, null);

        assertThat(result.academicYear()).isEqualTo(2026);
        assertThat(result.termLabel()).isEqualTo("前期");
    }
}
