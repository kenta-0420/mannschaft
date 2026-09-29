package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.recruitment.DisputeResolution;
import com.mannschaft.app.recruitment.NoShowReason;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.entity.RecruitmentNoShowRecordEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentNoShowRecordRepository.PenaltySourceScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class RecruitmentNoShowConfirmBatchTest {

    @Mock private RecruitmentNoShowRecordRepository noShowRepository;
    @Mock private RecruitmentPenaltyService penaltyService;
    @InjectMocks private RecruitmentNoShowConfirmBatch batch;

    @Test
    void 今回確定した同一ユーザーとスコープは一回だけ判定する() {
        RecruitmentNoShowRecordEntity first = record(101L, 7L, 11L);
        RecruitmentNoShowRecordEntity second = record(102L, 7L, 12L);
        given(noShowRepository.findUnconfirmedBefore(any())).willReturn(List.of(first, second));
        PenaltySourceScope scope = mock(PenaltySourceScope.class);
        given(scope.getScopeType()).willReturn("TEAM");
        given(scope.getScopeId()).willReturn(55L);
        given(noShowRepository.findPenaltySourceScope(101L)).willReturn(Optional.of(scope));
        given(noShowRepository.findPenaltySourceScope(102L)).willReturn(Optional.of(scope));

        batch.confirmNoShows();

        assertThat(first.isConfirmed()).isTrue();
        assertThat(second.isConfirmed()).isTrue();
        verify(penaltyService, times(1)).evaluateAndApplyPenalty(7L, RecruitmentScopeType.TEAM, 55L);
    }

    @Test
    void PERSONALは確定しても発動判定しない() {
        RecruitmentNoShowRecordEntity personal = record(101L, 7L, 11L);
        given(noShowRepository.findUnconfirmedBefore(any())).willReturn(List.of(personal));
        PenaltySourceScope scope = mock(PenaltySourceScope.class);
        given(scope.getScopeType()).willReturn("PERSONAL");
        given(noShowRepository.findPenaltySourceScope(101L)).willReturn(Optional.of(scope));

        batch.confirmNoShows();

        assertThat(personal.isConfirmed()).isTrue();
        verifyNoInteractions(penaltyService);
    }

    @Test
    void REVOKEDは確定しても発動判定しない() {
        RecruitmentNoShowRecordEntity revoked = record(101L, 7L, 11L);
        revoked.dispute("本人異議");
        revoked.resolveDispute(DisputeResolution.REVOKED);
        given(noShowRepository.findUnconfirmedBefore(any())).willReturn(List.of(revoked));

        batch.confirmNoShows();

        assertThat(revoked.isConfirmed()).isTrue();
        verifyNoInteractions(penaltyService);
    }

    @Test
    void 対象が無ければ発動判定しない() {
        given(noShowRepository.findUnconfirmedBefore(any())).willReturn(List.of());
        batch.confirmNoShows();
        verifyNoInteractions(penaltyService);
    }

    private RecruitmentNoShowRecordEntity record(Long id, Long userId, Long listingId) {
        RecruitmentNoShowRecordEntity record = RecruitmentNoShowRecordEntity.builder()
                .participantId(id).listingId(listingId).userId(userId)
                .reason(NoShowReason.ADMIN_MARKED).recordedBy(1L).build();
        ReflectionTestUtils.setField(record, "id", id);
        return record;
    }
}
