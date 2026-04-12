package com.mannschaft.app.recruitment.controller;

import com.mannschaft.app.common.PagedResponse;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.dto.CreateRecruitmentListingRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentListingSummaryResponse;
import com.mannschaft.app.recruitment.service.RecruitmentListingService;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * {@link OrganizationRecruitmentListingController} の単体テスト。
 * F03.11 Phase4 §9.1 組織スコープ募集枠 API を検証する。
 * Spring コンテキストを使わない純 Mockito テスト（軽量・高速）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationRecruitmentListingController 単体テスト")
class OrganizationRecruitmentListingControllerTest {

    private static final Long ORG_ID = 10L;

    @Mock
    private RecruitmentListingService listingService;

    @InjectMocks
    private OrganizationRecruitmentListingController controller;

    private void setUpSecurityContext() {
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    // ==========================================================
    // POST /api/v1/organizations/{orgId}/recruitment-listings
    // ==========================================================

    @Nested
    @DisplayName("POST create()")
    class CreateTests {

        @Test
        @DisplayName("正常系: 201 Created と作成された募集枠レスポンスを返す")
        void create_success_returns201() {
            setUpSecurityContext();
            LocalDateTime future = LocalDateTime.now().plusDays(7);
            RecruitmentListingResponse response = new RecruitmentListingResponse(
                    1L, "ORGANIZATION", ORG_ID, 100L, "category.key", null, null,
                    "テスト組織募集", null, "INDIVIDUAL",
                    future, future.plusHours(2), future.minusDays(1), future.minusDays(1),
                    20, 5, 0, 0, 100, false, null,
                    "PUBLIC", "DRAFT", null, null, null, null, 1L,
                    null, null, null, null, null);

            CreateRecruitmentListingRequest request = mock(CreateRecruitmentListingRequest.class);
            given(listingService.create(
                    eq(RecruitmentScopeType.ORGANIZATION),
                    eq(ORG_ID),
                    any(),
                    eq(request)))
                    .willReturn(response);

            ResponseEntity<?> result = controller.create(ORG_ID, request);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(result.getBody()).isNotNull();
        }

        @Test
        @DisplayName("ORGANIZATION スコープで listingService.create() が呼ばれる")
        void create_callsServiceWithOrganizationScope() {
            setUpSecurityContext();
            LocalDateTime future = LocalDateTime.now().plusDays(7);
            RecruitmentListingResponse response = new RecruitmentListingResponse(
                    1L, "ORGANIZATION", ORG_ID, 100L, "category.key", null, null,
                    "テスト", null, "INDIVIDUAL",
                    future, future.plusHours(2), future.minusDays(1), future.minusDays(1),
                    20, 5, 0, 0, 100, false, null,
                    "PUBLIC", "DRAFT", null, null, null, null, 1L,
                    null, null, null, null, null);

            CreateRecruitmentListingRequest request = mock(CreateRecruitmentListingRequest.class);
            given(listingService.create(
                    eq(RecruitmentScopeType.ORGANIZATION), eq(ORG_ID), any(), eq(request)))
                    .willReturn(response);

            controller.create(ORG_ID, request);

            // scopeType が ORGANIZATION であることを verify
            org.mockito.Mockito.verify(listingService).create(
                    eq(RecruitmentScopeType.ORGANIZATION), eq(ORG_ID), any(), eq(request));
        }
    }

    // ==========================================================
    // GET /api/v1/organizations/{orgId}/recruitment-listings
    // ==========================================================

    @Nested
    @DisplayName("GET list()")
    class ListTests {

        @Test
        @DisplayName("正常系: 200 OK と募集枠一覧を返す")
        void list_success_returns200() {
            setUpSecurityContext();
            LocalDateTime future = LocalDateTime.now().plusDays(7);
            RecruitmentListingSummaryResponse summary = new RecruitmentListingSummaryResponse(
                    1L, 100L, "category.key", "テスト組織募集", "INDIVIDUAL",
                    future, future.plusHours(2), future.minusDays(1),
                    20, 5, 3, 0, "OPEN", "PUBLIC", null, null, false, null);

            Page<RecruitmentListingSummaryResponse> page = new PageImpl<>(
                    java.util.List.of(summary), PageRequest.of(0, 20), 1);

            given(listingService.listByScope(
                    eq(RecruitmentScopeType.ORGANIZATION), eq(ORG_ID), any(), any(), any()))
                    .willReturn(page);

            ResponseEntity<PagedResponse<RecruitmentListingSummaryResponse>> result =
                    controller.list(ORG_ID, null, 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getData()).hasSize(1);
            assertThat(result.getBody().getData().get(0).getId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("空リスト: 200 OK で空の一覧を返す")
        void list_empty_returns200WithEmptyList() {
            setUpSecurityContext();
            Page<RecruitmentListingSummaryResponse> emptyPage =
                    new PageImpl<>(java.util.List.of(), PageRequest.of(0, 20), 0);

            given(listingService.listByScope(
                    eq(RecruitmentScopeType.ORGANIZATION), eq(ORG_ID), any(), any(), any()))
                    .willReturn(emptyPage);

            ResponseEntity<PagedResponse<RecruitmentListingSummaryResponse>> result =
                    controller.list(ORG_ID, null, 0, 20);

            assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(result.getBody()).isNotNull();
            assertThat(result.getBody().getData()).isEmpty();
            assertThat(result.getBody().getMeta().getTotal()).isEqualTo(0L);
        }
    }
}
