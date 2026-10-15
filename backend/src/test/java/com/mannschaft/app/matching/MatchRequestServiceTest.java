package com.mannschaft.app.matching;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.matching.dto.ActivitySuggestionResponse;
import com.mannschaft.app.matching.dto.CreateMatchRequestRequest;
import com.mannschaft.app.matching.dto.MatchRequestCreateResponse;
import com.mannschaft.app.matching.dto.MatchRequestResponse;
import com.mannschaft.app.matching.entity.MatchProposalEntity;
import com.mannschaft.app.matching.entity.MatchRequestEntity;
import com.mannschaft.app.matching.repository.MatchProposalRepository;
import com.mannschaft.app.matching.repository.MatchRequestRepository;
import com.mannschaft.app.matching.repository.MatchReviewRepository;
import com.mannschaft.app.matching.repository.NgTeamRepository;
import com.mannschaft.app.matching.service.MatchRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;

/**
 * {@link MatchRequestService} の単体テスト。
 *
 * <p>認可根治（IDOR/所有権）反映後の契約:
 * <ul>
 *   <li><b>更新/削除</b>: Controller から渡される actor（{@code currentUserId}）に対し
 *       {@code accessControlService.isAdminOrAbove(currentUserId, entity.getTeamId(), "TEAM")}
 *       が true の場合のみ許可（従来の {@code entity.getTeamId().equals(teamId)} という userID≠teamID の
 *       誤比較を撤廃）。非権限は {@link MatchingErrorCode#INSUFFICIENT_PERMISSION}。</li>
 *   <li><b>詳細/検索</b>: NG 除外・可視性判定は「閲覧ユーザーの所属チーム群」
 *       （{@code accessControlService.findAffiliatedScopeIds(currentUserId, "TEAM")}）を基準にする
 *       （従来の userID を teamId として扱う誤りを撤廃）。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MatchRequestService 単体テスト")
class MatchRequestServiceTest {

    @Mock
    private MatchRequestRepository requestRepository;
    @Mock
    private MatchProposalRepository proposalRepository;
    @Mock
    private MatchReviewRepository reviewRepository;
    @Mock
    private NgTeamRepository ngTeamRepository;
    @Mock
    private AccessControlService accessControlService;

    @InjectMocks
    private MatchRequestService service;

    /** 操作/閲覧ユーザー（＝認証プリンシパル）。teamId とは別物であることを明示するため teamId とは異なる値にする。 */
    private static final Long USER_ID = 500L;
    /** 募集を所有する / ユーザーが所属するチーム。 */
    private static final Long TEAM_ID = 1L;
    private static final Long REQUEST_ID = 10L;

    @Nested
    @DisplayName("createRequest")
    class CreateRequest {

        @Test
        @DisplayName("正常系: 募集が正常に作成される")
        void 募集正常作成() {
            // Given
            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "テスト募集", null, "PRACTICE", null, null,
                    "13", null, null, null, null, null, null, null,
                    null, null, null, null);

            MatchRequestEntity saved = MatchRequestEntity.builder()
                    .teamId(TEAM_ID)
                    .title("テスト募集")
                    .build();
            given(requestRepository.save(any())).willReturn(saved);

            // When
            MatchRequestCreateResponse result = service.createRequest(TEAM_ID, request);

            // Then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("異常系: 開始日が終了日より後の場合エラー")
        void 日付範囲不正() {
            // Given
            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "テスト募集", null, "PRACTICE", null, null,
                    "13", null, null,
                    LocalDate.of(2026, 4, 10), LocalDate.of(2026, 4, 5),
                    null, null, null, null, null, null, null);

            // When & Then
            assertThatThrownBy(() -> service.createRequest(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.INVALID_DATE_RANGE);
        }

        @Test
        @DisplayName("異常系: 最小参加人数が最大参加人数を超過")
        void 参加人数範囲不正() {
            // Given
            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "テスト募集", null, "PRACTICE", null, null,
                    "13", null, null, null, null, null, null, null,
                    (short) 20, (short) 10, null, null);

            // When & Then
            assertThatThrownBy(() -> service.createRequest(TEAM_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.INVALID_PARTICIPANT_RANGE);
        }
    }

    @Nested
    @DisplayName("updateRequest — 認可（所有権）")
    class UpdateRequest {

        @Test
        @DisplayName("AC-2 異常系: 募集チームの管理者/副管理者でないユーザーは更新不可（越境は存在秘匿のため 404 相当）")
        void 非管理者は更新不可() {
            // Given: entity.teamId=TEAM_ID の募集に対し、actor(USER_ID) は管理者ではない
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(TEAM_ID)
                    .title("募集")
                    .build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "更新", null, "PRACTICE", null, null,
                    "13", null, null, null, null, null, null, null,
                    null, null, null, null);

            // When & Then
            assertThatThrownBy(() -> service.updateRequest(REQUEST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.INSUFFICIENT_PERMISSION);
        }
    }

    @Nested
    @DisplayName("deleteRequest — 認可（所有権）")
    class DeleteRequest {

        @Test
        @DisplayName("異常系: 募集が見つからない場合エラー")
        void 募集不存在() {
            // Given
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> service.deleteRequest(REQUEST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(MatchingErrorCode.REQUEST_NOT_FOUND);
        }

        @Test
        @DisplayName("AC-3 異常系: 募集チームの管理者/副管理者でないユーザーは削除不可（越境は存在秘匿のため 404 相当）")
        void 非管理者は削除不可() {
            // Given
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("募集").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(false);

            // When / Then
            assertThatThrownBy(() -> service.deleteRequest(REQUEST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.INSUFFICIENT_PERMISSION));
        }

        @Test
        @DisplayName("異常系: MATCHED状態の募集は削除不可（認可通過後）")
        void MATCHED状態削除不可() {
            // Given
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.MATCHED).prefectureCode("13").build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            // When / Then
            assertThatThrownBy(() -> service.deleteRequest(REQUEST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.REQUEST_MATCHED_CANNOT_DELETE));
        }

        @Test
        @DisplayName("AC-3 正常系: 正当な管理者は削除でき、PENDING応募が一括REJECTされる")
        void 正常削除_PENDING応募REJECT() {
            // Given
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            MatchProposalEntity pending = MatchProposalEntity.builder()
                    .requestId(REQUEST_ID).proposingTeamId(99L).build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(proposalRepository.findByRequestIdAndStatus(any(), any())).willReturn(List.of(pending));
            given(proposalRepository.save(any())).willReturn(pending);
            given(requestRepository.save(any())).willReturn(entity);

            // When
            service.deleteRequest(REQUEST_ID, USER_ID);

            // Then
            assertThat(pending.getStatus()).isEqualTo(MatchProposalStatus.REJECTED);
        }
    }

    @Nested
    @DisplayName("getRequest — 可視性（所属チーム群）")
    class GetRequest {

        @Test
        @DisplayName("AC-4 異常系: 所属チーム群がブロックしている募集は取得不可")
        void NGチーム_取得不可() {
            // Given: entity.teamId=999 は、ユーザーの所属チーム(TEAM_ID)がブロックしている
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(999L).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of(999L));

            // When / Then
            assertThatThrownBy(() -> service.getRequest(REQUEST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.REQUEST_NOT_FOUND));
        }

        @Test
        @DisplayName("AC-4 異常系: 所属チーム群に属さない他チームのOPEN以外の募集は取得不可")
        void 他チームOPEN以外_取得不可() {
            // Given
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(999L).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.EXPIRED).prefectureCode("13").build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of());

            // When / Then
            assertThatThrownBy(() -> service.getRequest(REQUEST_ID, USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.REQUEST_NOT_FOUND));
        }

        @Test
        @DisplayName("AC-4 正常系: 自チーム（所属チーム群に含む）のMATCHED状態募集は取得できる")
        void 自チームMATCHED_取得成功() {
            // Given: entity.teamId=TEAM_ID はユーザーの所属チーム群に含まれる
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.MATCHED).prefectureCode("13").build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of());
            given(reviewRepository.findAverageRating(anyLong(), any())).willReturn(null);
            given(reviewRepository.countByRevieweeTeamIdAndCreatedAtAfter(anyLong(), any())).willReturn(0L);
            given(proposalRepository.countCancellationsByTeam(anyLong(), any())).willReturn(0L);

            // When
            MatchRequestResponse result = service.getRequest(REQUEST_ID, USER_ID);

            // Then
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("updateRequest — ステータス/正常系")
    class UpdateRequestAdditional {

        @Test
        @DisplayName("異常系: OPEN以外の募集は更新不可（認可通過後）")
        void OPEN以外_更新不可() {
            // Given
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.MATCHED).prefectureCode("13").build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);

            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "更新", null, "SOCCER", null, null,
                    "13", null, null, null, null, null, null, null,
                    null, null, null, null);

            // When / Then
            assertThatThrownBy(() -> service.updateRequest(REQUEST_ID, USER_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                            .isEqualTo(MatchingErrorCode.REQUEST_NOT_EDITABLE));
        }

        @Test
        @DisplayName("AC-2 正常系: 正当な管理者はOPEN状態の募集を更新できる")
        void OPEN状態_更新成功() {
            // Given
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("テスト").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(requestRepository.findById(REQUEST_ID)).willReturn(Optional.of(entity));
            given(accessControlService.isAdminOrAbove(USER_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(requestRepository.save(any())).willReturn(entity);
            given(reviewRepository.findAverageRating(anyLong(), any())).willReturn(null);
            given(reviewRepository.countByRevieweeTeamIdAndCreatedAtAfter(anyLong(), any())).willReturn(0L);
            given(proposalRepository.countCancellationsByTeam(anyLong(), any())).willReturn(0L);

            CreateMatchRequestRequest request = new CreateMatchRequestRequest(
                    "更新タイトル", null, "PRACTICE", null, null,
                    "13", null, null, null, null, null, null, null,
                    null, null, null, null);

            // When
            MatchRequestResponse result = service.updateRequest(REQUEST_ID, USER_ID, request);

            // Then
            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("searchRequests — 所属チーム群ベースのNG除外")
    class SearchRequests {

        @Test
        @DisplayName("正常系: フィルタなしで検索できる（所属なし＝除外なし）")
        void フィルタなし_検索成功() {
            // Given: ユーザーは TEAM_ID に所属
            Pageable pageable = PageRequest.of(0, 10);
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(999L).title("テスト募集").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of());
            given(requestRepository.searchRequests(any(), anyList(), isNull(), isNull(),
                    isNull(), isNull(), isNull(), isNull(), any(), eq(pageable)))
                    .willReturn(new PageImpl<>(List.of(entity)));
            given(reviewRepository.findAverageRating(anyLong(), any())).willReturn(null);
            given(reviewRepository.countByRevieweeTeamIdAndCreatedAtAfter(anyLong(), any())).willReturn(0L);
            given(proposalRepository.countCancellationsByTeam(anyLong(), any())).willReturn(0L);

            // When
            Page<MatchRequestResponse> result = service.searchRequests(USER_ID, null, null, null, null, null, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: フィルタあり（prefecture指定）で検索でき、所属チームのNG除外が適用される")
        void フィルタあり_検索成功() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(999L).title("テスト募集").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of(888L));
            given(requestRepository.searchRequests(any(), anyList(), eq("13"), isNull(),
                    any(), isNull(), isNull(), isNull(), any(), eq(pageable)))
                    .willReturn(new PageImpl<>(List.of(entity)));
            given(reviewRepository.findAverageRating(anyLong(), any())).willReturn(4.5);
            given(reviewRepository.countByRevieweeTeamIdAndCreatedAtAfter(anyLong(), any())).willReturn(10L);
            given(proposalRepository.countCancellationsByTeam(anyLong(), any())).willReturn(1L);

            // When
            Page<MatchRequestResponse> result = service.searchRequests(USER_ID, "13", null, "PRACTICE", null, null, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("searchByKeyword — 所属チーム群ベースのNG除外")
    class SearchByKeyword {

        @Test
        @DisplayName("正常系: キーワード検索で結果が取得できる")
        void キーワード検索_成功() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(999L).title("テスト募集").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of());
            given(requestRepository.searchByKeyword(anyString(), anyList(), anyString(),
                    isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(), eq(pageable)))
                    .willReturn(new PageImpl<>(List.of(entity)));
            given(reviewRepository.findAverageRating(anyLong(), any())).willReturn(null);
            given(reviewRepository.countByRevieweeTeamIdAndCreatedAtAfter(anyLong(), any())).willReturn(0L);
            given(proposalRepository.countCancellationsByTeam(anyLong(), any())).willReturn(0L);

            // When
            Page<MatchRequestResponse> result = service.searchByKeyword(
                    USER_ID, "サッカー", null, null, null, null, null, null, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }

        @Test
        @DisplayName("正常系: キーワード＋全フィルタを正規化して Repository へ渡す（条件を落とさない）")
        void キーワード複合検索_フィルタ転送() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(999L).title("テスト募集").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(accessControlService.findAffiliatedScopeIds(USER_ID, "TEAM")).willReturn(Set.of(TEAM_ID));
            given(ngTeamRepository.findBidirectionalBlockedTeamIds(TEAM_ID)).willReturn(List.of());
            given(requestRepository.searchByKeyword(eq("OPEN"), anyList(), eq("サッカー"),
                    eq("13"), eq("13101"), eq("PRACTICE"), eq("JUNIOR_HIGH"),
                    eq("BEGINNER"), eq("PLATFORM"), any(), eq(pageable)))
                    .willReturn(new PageImpl<>(List.of(entity)));
            given(reviewRepository.findAverageRating(anyLong(), any())).willReturn(null);
            given(reviewRepository.countByRevieweeTeamIdAndCreatedAtAfter(anyLong(), any())).willReturn(0L);
            given(proposalRepository.countCancellationsByTeam(anyLong(), any())).willReturn(0L);

            // When
            Page<MatchRequestResponse> result = service.searchByKeyword(
                    USER_ID, "サッカー", "13", "13101", "PRACTICE", "JUNIOR_HIGH", "BEGINNER", "PLATFORM", pageable);

            // Then: eq(...) マッチャに合致した場合のみ 1 件返る＝全フィルタが正規化されて転送されている
            assertThat(result.getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("listTeamRequests")
    class ListTeamRequests {

        @Test
        @DisplayName("正常系: 自チームの募集一覧が取得できる")
        void 自チーム募集一覧_取得成功() {
            // Given
            Pageable pageable = PageRequest.of(0, 10);
            MatchRequestEntity entity = MatchRequestEntity.builder()
                    .teamId(TEAM_ID).title("テスト募集").activityType(ActivityType.PRACTICE)
                    .status(MatchRequestStatus.OPEN).prefectureCode("13").build();
            given(requestRepository.findByTeamIdOrderByCreatedAtDesc(eq(TEAM_ID), eq(pageable)))
                    .willReturn(new PageImpl<>(List.of(entity)));
            given(reviewRepository.findAverageRating(anyLong(), any())).willReturn(null);
            given(reviewRepository.countByRevieweeTeamIdAndCreatedAtAfter(anyLong(), any())).willReturn(0L);
            given(proposalRepository.countCancellationsByTeam(anyLong(), any())).willReturn(0L);

            // When
            Page<MatchRequestResponse> result = service.listTeamRequests(TEAM_ID, pageable);

            // Then
            assertThat(result.getContent()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("getActivitySuggestions")
    class GetActivitySuggestions {

        @Test
        @DisplayName("正常系: サジェスト一覧が取得できる")
        void サジェスト取得_成功() {
            // Given
            Object[] row1 = {"フットサル", 5L};
            Object[] row2 = {"サッカー", 10L};
            given(requestRepository.findActivitySuggestions("サ", "PRACTICE"))
                    .willReturn(List.of(row1, row2));

            // When
            List<ActivitySuggestionResponse> result = service.getActivitySuggestions("サ", "PRACTICE");

            // Then
            assertThat(result).hasSize(2);
            assertThat(result.get(0).getActivityDetail()).isEqualTo("フットサル");
            assertThat(result.get(1).getUsageCount()).isEqualTo(10L);
        }

        @Test
        @DisplayName("正常系: 結果なしで空リストが返る")
        void サジェスト_結果なし() {
            // Given
            given(requestRepository.findActivitySuggestions(anyString(), anyString()))
                    .willReturn(List.of());

            // When
            List<ActivitySuggestionResponse> result = service.getActivitySuggestions("xyz", "PRACTICE");

            // Then
            assertThat(result).isEmpty();
        }
    }
}
