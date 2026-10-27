package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.circulation.service.CirculationService;
import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.visibility.ScopeKey;
import com.mannschaft.app.schedule.service.ScheduleAttendanceService;
import com.mannschaft.app.survey.service.SurveyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 候補は型付きで束ね、取得失敗を確定ゼロに変えない。 */
@ExtendWith(MockitoExtension.class)
class ActionRequiredCandidateTest {
    @Mock private CirculationService circulation;
    @Mock private SurveyService survey;
    @Mock private ScheduleAttendanceService attendance;
    @Mock private AccessControlService access;
    @InjectMocks private ScopeActionRequiredFacade facade;
    @Captor private ArgumentCaptor<List<Long>> circulationChunks;
    @Captor private ArgumentCaptor<List<Long>> surveyChunks;
    @Captor private ArgumentCaptor<List<Long>> attendanceChunks;
    private static final Long USER = 1L;

    @BeforeEach
    void 空候補の正規戻り値を用意する() {
        lenient().when(circulation.findUnconfirmedScopeIds(any(), any(), eq(USER))).thenReturn(List.of());
        lenient().when(survey.findUnansweredScopeIds(any(), any(), eq(USER))).thenReturn(List.of());
        lenient().when(attendance.findUnansweredScopeIds(any(), any(), eq(USER))).thenReturn(List.of());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 1000, 1001})
    void 空と1000境界でSQL引数を有界化する(int size) {
        Set<Long> teams = ids(size);
        lenient().when(circulation.findUnconfirmedScopeIds(any(), any(), eq(USER)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        assertThat(facade.findCandidateScopes(USER, teams, Set.of()))
                .containsExactlyElementsOf(teams.stream().map(id -> new ScopeKey("TEAM", id)).toList());
        int calls = (size + 999) / 1000;
        verify(circulation, times(calls)).findUnconfirmedScopeIds(eq("TEAM"), circulationChunks.capture(), eq(USER));
        verify(survey, times(calls)).findUnansweredScopeIds(eq("TEAM"), surveyChunks.capture(), eq(USER));
        verify(attendance, times(calls)).findUnansweredScopeIds(eq("TEAM"), attendanceChunks.capture(), eq(USER));
        for (var chunks : List.of(circulationChunks.getAllValues(), surveyChunks.getAllValues(), attendanceChunks.getAllValues())) {
            chunks.forEach(chunk -> assertThat(chunk).isNotEmpty().hasSizeLessThanOrEqualTo(1000));
            assertThat(chunks.stream().flatMap(List::stream).toList()).containsExactlyElementsOf(teams);
        }
        verifyNoInteractions(access);
    }

    @Test
    void 同じ数値IDのTEAMと組織を混同せずdomain候補を和集合にする() {
        when(survey.findUnansweredScopeIds(eq("TEAM"), any(), eq(USER))).thenReturn(List.of(10L));
        when(attendance.findUnansweredScopeIds(eq("ORGANIZATION"), any(), eq(USER))).thenReturn(List.of(10L));
        assertThat(facade.findCandidateScopes(USER, Set.of(10L, 20L), Set.of(10L, 20L)))
                .containsExactly(new ScopeKey("TEAM", 10L), new ScopeKey("ORGANIZATION", 10L));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void domainの後続chunk失敗でも元の両型全集合を返す(int domain) {
        if (domain == 0) {
            when(circulation.findUnconfirmedScopeIds(eq("TEAM"), any(), eq(USER)))
                    .thenReturn(List.of(1L)).thenThrow(new IllegalStateException("候補DB障害"));
        } else if (domain == 1) {
            when(survey.findUnansweredScopeIds(eq("TEAM"), any(), eq(USER)))
                    .thenReturn(List.of(1L)).thenThrow(new IllegalStateException("候補DB障害"));
        } else {
            when(attendance.findUnansweredScopeIds(eq("TEAM"), any(), eq(USER)))
                    .thenReturn(List.of(1L)).thenThrow(new IllegalStateException("候補DB障害"));
        }
        Set<Long> teams = ids(1001);
        Set<ScopeKey> expected = teams.stream().map(id -> new ScopeKey("TEAM", id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        expected.add(new ScopeKey("ORGANIZATION", 1L));
        assertThat(facade.findCandidateScopes(USER, teams, Set.of(1L))).containsExactlyElementsOf(expected);
    }

    private Set<Long> ids(int count) {
        return LongStream.rangeClosed(1, count).boxed().collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
