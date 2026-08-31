package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.market.MarketErrorCode;
import com.mannschaft.app.recruitment.RecruitmentMapper;
import com.mannschaft.app.recruitment.RecruitmentParticipantStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipantType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CancelRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.PersonalMarketMatchResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.UpdateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingAudienceScopeRepository;
import com.mannschaft.app.recruitment.entity.RecruitmentParticipantEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentParticipantRepository;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("個人市Serviceの本人固定契約")
class PersonalMarketListingServiceTest {

    @Mock
    private RecruitmentListingService listingService;

    @Mock
    private RecruitmentListingRepository listingRepository;

    @Mock
    private RecruitmentMapper mapper;

    @Mock
    private RecruitmentParticipantRepository participantRepository;

    @Mock
    private RecruitmentListingAudienceScopeRepository audienceScopeRepository;

    @InjectMocks
    private PersonalMarketListingService service;

    @Test
    @DisplayName("作成時のスコープと札主を認証済み本人へ固定する")
    void create_bindsCurrentUserAsScopeAndOwner() {
        Long currentUserId = 123L;

        service.create(currentUserId, Mockito.mock(CreateRecruitmentListingRequest.class));

        verify(listingService).create(
                eq(RecruitmentScopeType.PERSONAL),
                eq(currentUserId),
                eq(currentUserId),
                any(CreateRecruitmentListingRequest.class));
    }

    @Test
    @DisplayName("DRAFT編集は認証済み本人を固定して共通サービスへ委譲する")
    void update_bindsCurrentUser() {
        Long currentUserId = 123L;
        Long listingId = 456L;
        RecruitmentListingResponse response = Mockito.mock(RecruitmentListingResponse.class);
        given(listingService.updatePersonalDraft(eq(listingId), eq(currentUserId),
                any(UpdateRecruitmentListingRequest.class))).willReturn(response);

        service.update(currentUserId, listingId, Mockito.mock(UpdateRecruitmentListingRequest.class));

        verify(listingService).updatePersonalDraft(eq(listingId), eq(currentUserId),
                any(UpdateRecruitmentListingRequest.class));
    }

    @Test
    @DisplayName("DRAFT取消は認証済み本人を固定して共通サービスへ委譲する")
    void cancel_bindsCurrentUser() {
        Long currentUserId = 123L;
        Long listingId = 456L;

        service.cancel(currentUserId, listingId, Mockito.mock(CancelRecruitmentListingRequest.class));

        verify(listingService).cancelPersonalDraft(eq(listingId), eq(currentUserId),
                any(CancelRecruitmentListingRequest.class));
    }

    @Test
    @DisplayName("マッチング状況は本人所有のPERSONAL札だけを検索する")
    void listMatches_bindsPersonalScopeAndOwner() {
        Long currentUserId = 123L;
        Long listingId = 456L;
        LocalDateTime appliedAt = LocalDateTime.of(2026, 8, 30, 10, 0);
        RecruitmentListingEntity listing = Mockito.mock(RecruitmentListingEntity.class);
        RecruitmentParticipantEntity participant = Mockito.mock(RecruitmentParticipantEntity.class);
        given(listingRepository.findByIdAndScopeTypeAndScopeIdAndCreatedBy(
                listingId, RecruitmentScopeType.PERSONAL, currentUserId, currentUserId))
                .willReturn(Optional.of(listing));
        given(participant.getId()).willReturn(99L);
        given(participant.getParticipantType()).willReturn(RecruitmentParticipantType.USER);
        given(participant.getStatus()).willReturn(RecruitmentParticipantStatus.APPLIED);
        given(participant.getAppliedAt()).willReturn(appliedAt);
        given(participant.getStatusChangedAt()).willReturn(appliedAt);
        PageRequest pageable = PageRequest.of(0, 20);
        given(participantRepository.findByListingIdOrderByAppliedAtAsc(listingId, pageable))
                .willReturn(new PageImpl<>(List.of(participant), pageable, 1));

        var result = service.listMatches(currentUserId, listingId, pageable);

        assertThat(result.getContent()).singleElement().satisfies(match -> {
            assertThat(match.participantId()).isEqualTo(99L);
            assertThat(match.status()).isEqualTo(RecruitmentParticipantStatus.APPLIED);
            assertThat(match.appliedAt()).isEqualTo(appliedAt.toInstant(java.time.ZoneOffset.UTC));
        });
    }

    @Test
    @DisplayName("他人または他スコープの札IDはMARKET_404で秘匿する")
    void listMatches_foreignListing_returnsMarket404() {
        Long currentUserId = 123L;
        Long listingId = 456L;
        given(listingRepository.findByIdAndScopeTypeAndScopeIdAndCreatedBy(
                listingId, RecruitmentScopeType.PERSONAL, currentUserId, currentUserId))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> service.listMatches(currentUserId, listingId, PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(BusinessException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(MarketErrorCode.LISTING_NOT_FOUND));
    }

    @Test
    @DisplayName("マッチング状況DTOは参加者PIIと申込メモを公開しない")
    void matchResponse_exposesOnlyMinimalManagementFields() {
        assertThat(Arrays.stream(PersonalMarketMatchResponse.class.getRecordComponents())
                .map(component -> component.getName()))
                .containsExactlyInAnyOrder(
                        "participantId", "participantType", "status", "waitlistPosition",
                        "appliedAt", "statusChangedAt");
    }
}
