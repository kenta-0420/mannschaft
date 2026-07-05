package com.mannschaft.app.matching;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.dto.CreateReviewRequest;
import com.mannschaft.app.matching.dto.ReviewCreateResponse;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.entity.MatchReviewEntity;
import com.mannschaft.app.matching.mapper.MatchingMapper;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.repository.MatchReviewRepository;
import com.mannschaft.app.matching.service.MatchReviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link MatchReviewService} の単体テスト。
 *
 * <p>認可根治（第2弾 (C)）: レビュー投稿の第1引数は <b>認証ユーザー ID（userID）</b> であり、
 * レビュアーチーム（reviewer team）は「対戦した 2 チーム（募集チーム/応募チーム）のうち、
 * その userID が管理者/副管理者であるチーム」として proposal 経由で解決される。
 * userID を teamId として直接扱う従来の誤りを撤廃したことを、userID≠teamID の実値分離で担保する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("MatchReviewService 単体テスト（レビュー投稿の認可・レビュアーチーム解決）")
class MatchReviewServiceTest {

    @Mock
    private MatchReviewRepository reviewRepository;
    @Mock
    private MatchProposalRepository proposalRepository;
    @Mock
    private MatchRequestRepository requestRepository;
    @Mock
    private MatchingMapper matchingMapper;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private MatchReviewService service;

    /** 募集を出したチーム（requesting team）。 */
    private static final Long REQUESTING_TEAM = 1L;
    /** 応募したチーム（proposing team）。 */
    private static final Long PROPOSING_TEAM = 2L;
    private static final Long PROPOSAL_ID = 10L;
    private static final Long REQUEST_ID = 100L;

    /** userID は teamId とは別の値域（誤用検出のため意図的に分離）。 */
    private static final Long REQUESTING_ADMIN_USER = 501L;
    private static final Long PROPOSING_ADMIN_USER = 502L;
    private static final Long OUTSIDER_USER = 999L;

    private MatchProposalEntity acceptedProposal() {
        MatchProposalEntity proposal = MatchProposalEntity.builder()
                .requestId(REQUEST_ID)
                .proposingTeamId(PROPOSING_TEAM)
                .build();
        setProposalStatus(proposal, MatchProposalStatus.ACCEPTED);
        setUpdatedAt(proposal, LocalDateTime.now().minusDays(1));
        return proposal;
    }

    private MatchRequestEntity requestByRequestingTeam() {
        return MatchRequestEntity.builder().teamId(REQUESTING_TEAM).build();
    }

    @Nested
    @DisplayName("createReview")
    class CreateReview {

        @Test
        @DisplayName("異常系: 応募が見つからない場合エラー")
        void 応募不存在() {
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.empty());
            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 4, "良い試合でした", true);

            assertThatThrownBy(() -> service.createReview(REQUESTING_ADMIN_USER, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.PROPOSAL_NOT_FOUND);
        }

        @Test
        @DisplayName("認可: 対戦に参加していないユーザー（両チームの管理者でない）はレビュー不可 → REVIEW_NOT_PARTICIPANT")
        void 非参加ユーザーはレビュー不可() {
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(acceptedProposal()));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestByRequestingTeam()));
            // どちらの参加チームの管理者/副管理者でもない
            given(accessControlService.isAdminOrAbove(OUTSIDER_USER, REQUESTING_TEAM, "TEAM")).willReturn(false);
            given(accessControlService.isAdminOrAbove(OUTSIDER_USER, PROPOSING_TEAM, "TEAM")).willReturn(false);

            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 4, "テスト", true);

            assertThatThrownBy(() -> service.createReview(OUTSIDER_USER, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.REVIEW_NOT_PARTICIPANT);
        }

        @Test
        @DisplayName("認可: 参加チームの一般メンバー（管理者でない）はレビュー不可 → REVIEW_NOT_PARTICIPANT")
        void 参加チーム非管理者はレビュー不可() {
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(acceptedProposal()));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestByRequestingTeam()));
            // 参加チームに所属はしているが管理者/副管理者ではない（isAdminOrAbove=false）
            given(accessControlService.isAdminOrAbove(REQUESTING_ADMIN_USER, REQUESTING_TEAM, "TEAM")).willReturn(false);
            given(accessControlService.isAdminOrAbove(REQUESTING_ADMIN_USER, PROPOSING_TEAM, "TEAM")).willReturn(false);

            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 4, "テスト", true);

            assertThatThrownBy(() -> service.createReview(REQUESTING_ADMIN_USER, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.REVIEW_NOT_PARTICIPANT);
        }

        @Test
        @DisplayName("異常系: レビュー重複エラー（レビュアーチームで重複判定）")
        void レビュー重複() {
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(acceptedProposal()));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestByRequestingTeam()));
            given(accessControlService.isAdminOrAbove(REQUESTING_ADMIN_USER, REQUESTING_TEAM, "TEAM")).willReturn(true);
            // 重複判定はレビュアーチーム（=REQUESTING_TEAM）で行われる
            given(reviewRepository.existsByProposalIdAndReviewerTeamId(PROPOSAL_ID, REQUESTING_TEAM)).willReturn(true);

            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 5, "テスト", true);

            assertThatThrownBy(() -> service.createReview(REQUESTING_ADMIN_USER, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.DUPLICATE_REVIEW);
        }

        @Test
        @DisplayName("正常系: 募集チームの管理者がレビュー → reviewer=募集チーム / reviewee=応募チーム")
        void 募集チーム管理者がレビュー正常作成() {
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(acceptedProposal()));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestByRequestingTeam()));
            given(accessControlService.isAdminOrAbove(REQUESTING_ADMIN_USER, REQUESTING_TEAM, "TEAM")).willReturn(true);
            given(reviewRepository.existsByProposalIdAndReviewerTeamId(PROPOSAL_ID, REQUESTING_TEAM)).willReturn(false);

            MatchReviewEntity saved = MatchReviewEntity.builder()
                    .proposalId(PROPOSAL_ID)
                    .reviewerTeamId(REQUESTING_TEAM)
                    .revieweeTeamId(PROPOSING_TEAM)
                    .rating((short) 5)
                    .build();
            given(reviewRepository.save(any())).willReturn(saved);

            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 5, "素晴らしい", true);

            ReviewCreateResponse result = service.createReview(REQUESTING_ADMIN_USER, request);

            assertThat(result.getRating()).isEqualTo((short) 5);
            assertThat(result.getRevieweeTeamId()).isEqualTo(PROPOSING_TEAM);

            ArgumentCaptor<MatchReviewEntity> captor = ArgumentCaptor.forClass(MatchReviewEntity.class);
            verify(reviewRepository).save(captor.capture());
            assertThat(captor.getValue().getReviewerTeamId()).isEqualTo(REQUESTING_TEAM);
            assertThat(captor.getValue().getRevieweeTeamId()).isEqualTo(PROPOSING_TEAM);
        }

        @Test
        @DisplayName("正常系: 応募チームの管理者がレビュー → reviewer=応募チーム / reviewee=募集チーム")
        void 応募チーム管理者がレビュー正常作成() {
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(acceptedProposal()));
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(requestByRequestingTeam()));
            given(accessControlService.isAdminOrAbove(PROPOSING_ADMIN_USER, REQUESTING_TEAM, "TEAM")).willReturn(false);
            given(accessControlService.isAdminOrAbove(PROPOSING_ADMIN_USER, PROPOSING_TEAM, "TEAM")).willReturn(true);
            given(reviewRepository.existsByProposalIdAndReviewerTeamId(PROPOSAL_ID, PROPOSING_TEAM)).willReturn(false);

            MatchReviewEntity saved = MatchReviewEntity.builder()
                    .proposalId(PROPOSAL_ID)
                    .reviewerTeamId(PROPOSING_TEAM)
                    .revieweeTeamId(REQUESTING_TEAM)
                    .rating((short) 4)
                    .build();
            given(reviewRepository.save(any())).willReturn(saved);

            CreateReviewRequest request = new CreateReviewRequest(PROPOSAL_ID, (short) 4, "また対戦したい", true);

            ReviewCreateResponse result = service.createReview(PROPOSING_ADMIN_USER, request);

            assertThat(result.getRevieweeTeamId()).isEqualTo(REQUESTING_TEAM);

            ArgumentCaptor<MatchReviewEntity> captor = ArgumentCaptor.forClass(MatchReviewEntity.class);
            verify(reviewRepository).save(captor.capture());
            assertThat(captor.getValue().getReviewerTeamId()).isEqualTo(PROPOSING_TEAM);
            assertThat(captor.getValue().getRevieweeTeamId()).isEqualTo(REQUESTING_TEAM);
        }
    }

    private void setProposalStatus(MatchProposalEntity proposal, MatchProposalStatus status) {
        try {
            Field field = MatchProposalEntity.class.getDeclaredField("status");
            field.setAccessible(true);
            field.set(proposal, status);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setUpdatedAt(MatchProposalEntity proposal, LocalDateTime updatedAt) {
        try {
            Field field = proposal.getClass().getSuperclass().getDeclaredField("updatedAt");
            field.setAccessible(true);
            field.set(proposal, updatedAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
