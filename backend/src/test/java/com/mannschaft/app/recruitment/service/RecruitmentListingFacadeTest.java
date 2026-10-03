package com.mannschaft.app.recruitment.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.ErrorCode;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.recruitment.RecruitmentDistributionTargetType;
import com.mannschaft.app.recruitment.RecruitmentErrorCode;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.dto.ApplyToRecruitmentRequest;
import com.mannschaft.app.recruitment.dto.RecruitmentListingResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentParticipantResponse;
import com.mannschaft.app.recruitment.dto.RecruitmentTemplateResponse;
import com.mannschaft.app.recruitment.service.RecruitmentListingService.ListingAccessScope;
import com.mannschaft.app.recruitment.service.RecruitmentTemplateService.TemplateAccessScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * {@link RecruitmentListingFacade}（認可は tx の外・tx 本体は認可に依存しない）の単体テスト（CMP-260923-0954 W5）。
 *
 * <p>認可の検証は旧 Service 単体テスト（{@code RecruitmentListingServiceTest}・{@code RecruitmentTemplateServiceTest} の
 * 管理者判定・メンバー判定）からここへ移した。置き場を認可の置き場に合わせただけで、検証の中身は消していない。
 * 実 DB 越しの応答表は {@code RecruitmentListingTemplateScopeContractIT}、競合・ロック順・クエリ回数は
 * {@code RecruitmentListingFacadeRaceAndQueryIT}。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentListingFacade 単体テスト（認可の外出し）")
class RecruitmentListingFacadeTest {

    @Mock private RecruitmentListingService listingService;
    @Mock private RecruitmentParticipantService participantService;
    @Mock private RecruitmentTemplateService templateService;
    @Mock private AccessControlService accessControlService;
    @Mock private ContentVisibilityChecker visibilityChecker;

    private static final Long LISTING_ID = 100L;
    private static final Long TEMPLATE_ID = 300L;
    private static final Long TEAM_ID = 10L;
    private static final Long ADMIN_ID = 2L;
    private static final Long MEMBER_ID = 3L;
    private static final Long OUTSIDER_ID = 66L;
    private static final Long OWNER_ID = 7L;

    private RecruitmentListingFacade facade() {
        return new RecruitmentListingFacade(listingService, participantService, templateService,
                accessControlService, visibilityChecker);
    }

    private static ListingAccessScope teamListing(RecruitmentListingStatus status, RecruitmentVisibility visibility) {
        return new ListingAccessScope(RecruitmentScopeType.TEAM, TEAM_ID, ADMIN_ID, status, visibility);
    }

    private static ListingAccessScope personalListing(RecruitmentListingStatus status) {
        return new ListingAccessScope(RecruitmentScopeType.PERSONAL, OWNER_ID, OWNER_ID, status,
                RecruitmentVisibility.SCOPE_ONLY);
    }

    private static ErrorCode codeOf(Throwable t) {
        return ((BusinessException) t).getErrorCode();
    }

    // ========================================
    // 募集の書込系
    // ========================================

    @Nested
    @DisplayName("募集の書込系（update・publish・cancel・archive・配信対象・参加者一覧・出席）")
    class ListingWrites {

        @Test
        @DisplayName("管理者は許可。判定は isAdminOrAbove 1 本だけで、SYSTEM_ADMIN・在籍の判定は足さない。認可の後に tx 本体が呼ばれる")
        void admin_isAllowedWithSingleQuery() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.OPEN, RecruitmentVisibility.SCOPE_ONLY));
            given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(true);
            RecruitmentListingResponse response = mock(RecruitmentListingResponse.class);
            given(listingService.publish(LISTING_ID, ADMIN_ID)).willReturn(response);

            assertThat(facade().publish(LISTING_ID, ADMIN_ID)).isSameAs(response);

            InOrder order = Mockito.inOrder(listingService, accessControlService);
            order.verify(listingService).resolveListingScope(LISTING_ID);
            order.verify(accessControlService).isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM");
            order.verify(listingService).publish(LISTING_ID, ADMIN_ID);
            verify(accessControlService, never()).isSystemAdmin(anyLong());
            verify(accessControlService, never()).isMember(anyLong(), anyLong(), anyString());
        }

        @Test
        @DisplayName("部外者は非公開物に不在と同一の 404（LISTING_NOT_FOUND）。tx 本体は呼ばれない（FOR UPDATE も走らない）")
        void outsider_isConcealed() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.OPEN, RecruitmentVisibility.SCOPE_ONLY));

            assertThatThrownBy(() -> facade().archive(LISTING_ID, OUTSIDER_ID))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));

            verify(listingService, never()).archive(anyLong(), anyLong());
        }

        @Test
        @DisplayName("同スコープの一般メンバー・SYSTEM_ADMIN は 403（COMMON_002）。tx 本体は呼ばれない")
        void memberAndSystemAdmin_areForbidden() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY));
            lenient().when(accessControlService.isMember(MEMBER_ID, TEAM_ID, "TEAM")).thenReturn(true);
            lenient().when(accessControlService.isSystemAdmin(OUTSIDER_ID)).thenReturn(true);

            assertThatThrownBy(() -> facade().update(LISTING_ID, MEMBER_ID, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(CommonErrorCode.COMMON_002));
            assertThatThrownBy(() -> facade().update(LISTING_ID, OUTSIDER_ID, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(CommonErrorCode.COMMON_002));

            verify(listingService, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("公開物（PUBLIC かつ公開中）への越境書込は 403 のまま（404 に倒さない）")
        void publicObject_isForbiddenNotConcealed() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.OPEN, RecruitmentVisibility.PUBLIC));

            assertThatThrownBy(() -> facade().cancel(LISTING_ID, OUTSIDER_ID, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(CommonErrorCode.COMMON_002));

            verify(listingService, never()).cancelByAdmin(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("PUBLIC でも DRAFT・中止済みは公開物ではないので、部外者には 404")
        void publicVisibilityButNotPublished_isConcealed() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.CANCELLED, RecruitmentVisibility.PUBLIC));

            assertThatThrownBy(() -> facade().cancel(LISTING_ID, OUTSIDER_ID, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));
        }

        @Test
        @DisplayName("募集が不在・論理削除済みなら scope 解決の 404 で終わり、認可クラスは呼ばれない")
        void missing_skipsAuthorization() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willThrow(new BusinessException(RecruitmentErrorCode.LISTING_NOT_FOUND));

            assertThatThrownBy(() -> facade().listParticipants(LISTING_ID, ADMIN_ID, PageRequest.of(0, 20)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));

            verifyNoInteractions(accessControlService, participantService);
        }

        @Test
        @DisplayName("PERSONAL 募集は本人にも部外者にも SYSTEM_ADMIN にも不在と同一の 404（汎用 PATCH・cancel・archive・参加者管理）")
        void personal_isConcealedForEveryone_onGenericEndpoints() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(personalListing(RecruitmentListingStatus.OPEN));

            for (Long actor : List.of(OWNER_ID, OUTSIDER_ID)) {
                assertThatThrownBy(() -> facade().update(LISTING_ID, actor, null))
                        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));
                assertThatThrownBy(() -> facade().markAttended(LISTING_ID, 1L, actor))
                        .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));
            }

            verifyNoInteractions(accessControlService);
            verify(listingService, never()).update(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("PERSONAL 募集の公開・配信対象: 本人は是正前どおり tx 本体の判定へ、SYSTEM_ADMIN は 403、部外者は 404")
        void personal_publishAndDistribution_keepOwnerAndSystemAdminBehaviour() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(personalListing(RecruitmentListingStatus.DRAFT));
            given(accessControlService.isSystemAdmin(66L)).willReturn(false);
            given(accessControlService.isSystemAdmin(99L)).willReturn(true);
            given(listingService.getDistributionTargets(LISTING_ID)).willReturn(List.of());

            assertThat(facade().getDistributionTargets(LISTING_ID, OWNER_ID)).isEmpty();
            assertThatThrownBy(() -> facade().publish(LISTING_ID, 99L))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(CommonErrorCode.COMMON_002));
            assertThatThrownBy(() -> facade().setDistributionTargets(LISTING_ID, 66L,
                    List.of(RecruitmentDistributionTargetType.MEMBERS)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));

            verify(listingService, never()).publish(anyLong(), anyLong());
            verify(listingService, never()).setDistributionTargets(anyLong(), any());
        }

        @Test
        @DisplayName("参加者一覧: 管理者の認可は募集単位で 1 回。tx 本体は userId を受け取らない")
        void listParticipants_authorizesOncePerListing() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.OPEN, RecruitmentVisibility.SCOPE_ONLY));
            given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(participantService.listParticipants(eq(LISTING_ID), any()))
                    .willReturn(new org.springframework.data.domain.PageImpl<>(List.<RecruitmentParticipantResponse>of()));

            facade().listParticipants(LISTING_ID, ADMIN_ID, PageRequest.of(0, 20));

            verify(accessControlService, Mockito.times(1)).isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM");
            verifyNoMoreInteractions(accessControlService);
        }
    }

    // ========================================
    // 募集詳細・申込
    // ========================================

    @Nested
    @DisplayName("募集詳細（下書き・個人札）と申込")
    class GetAndApply {

        @Test
        @DisplayName("TEAM の DRAFT: 作成者は管理者判定なしで通り、同スコープ一般メンバー・SYSTEM_ADMIN は 403（DRAFT_VIEW_DENIED）、部外者は 404")
        void draft_splitsByKnowledge() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY));
            lenient().when(accessControlService.isMember(MEMBER_ID, TEAM_ID, "TEAM")).thenReturn(true);
            lenient().when(accessControlService.isSystemAdmin(77L)).thenReturn(true);
            RecruitmentListingResponse response = mock(RecruitmentListingResponse.class);
            given(listingService.findAuthorizedDraftListing(LISTING_ID)).willReturn(java.util.Optional.of(response));

            assertThat(facade().getListing(LISTING_ID, ADMIN_ID)).isSameAs(response);
            assertThatThrownBy(() -> facade().getListing(LISTING_ID, MEMBER_ID))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.DRAFT_VIEW_DENIED));
            assertThatThrownBy(() -> facade().getListing(LISTING_ID, 77L))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.DRAFT_VIEW_DENIED));
            assertThatThrownBy(() -> facade().getListing(LISTING_ID, OUTSIDER_ID))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));

            // 下書きの許可後は、管理者判定を繰り返さない専用の読み取りを使う（通常の getListing は呼ばない）
            verify(listingService, never()).getListing(any(), any());
        }

        @Test
        @DisplayName("PERSONAL の公開後は誰にも不在と同一の 404、PERSONAL の DRAFT は本人のみ（SYSTEM_ADMIN は 403・部外者は 404）")
        void personal_visibility() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(personalListing(RecruitmentListingStatus.OPEN))
                    .willReturn(personalListing(RecruitmentListingStatus.DRAFT));
            // 1 回目（OPEN）
            assertThatThrownBy(() -> facade().getListing(LISTING_ID, OWNER_ID))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));
            // 2 回目以降（DRAFT）
            given(accessControlService.isSystemAdmin(99L)).willReturn(true);
            RecruitmentListingResponse response = mock(RecruitmentListingResponse.class);
            given(listingService.findAuthorizedDraftListing(LISTING_ID)).willReturn(java.util.Optional.of(response));

            assertThat(facade().getListing(LISTING_ID, OWNER_ID)).isSameAs(response);
            assertThatThrownBy(() -> facade().getListing(LISTING_ID, 99L))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.DRAFT_VIEW_DENIED));
            assertThatThrownBy(() -> facade().getListing(LISTING_ID, OUTSIDER_ID))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));
        }

        @Test
        @DisplayName("申込: 閲覧できず在籍者でもない者は状態に依らず不在と同一の 404。tx 本体（FOR UPDATE）は呼ばれない")
        void apply_outsiderIsConcealed() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY));
            given(visibilityChecker.canView(ReferenceType.RECRUITMENT_LISTING, LISTING_ID, OUTSIDER_ID))
                    .willReturn(false);

            assertThatThrownBy(() -> facade().apply(LISTING_ID, OUTSIDER_ID, mock(ApplyToRecruitmentRequest.class)))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.LISTING_NOT_FOUND));

            verifyNoInteractions(participantService);
        }

        @Test
        @DisplayName("申込: 閲覧できない在籍者は下書きなら 409 DRAFT_NOT_APPLICABLE、それ以外は 403（VISIBILITY_001）。どちらも tx 本体（FOR UPDATE）へ進まない")
        void apply_insiderDeniedAnsweredByFacadeWithoutLock() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.DRAFT, RecruitmentVisibility.SCOPE_ONLY));
            given(visibilityChecker.canView(ReferenceType.RECRUITMENT_LISTING, LISTING_ID, MEMBER_ID))
                    .willReturn(false);
            given(accessControlService.isMember(MEMBER_ID, TEAM_ID, "TEAM")).willReturn(true);
            ApplyToRecruitmentRequest request = mock(ApplyToRecruitmentRequest.class);

            assertThatThrownBy(() -> facade().apply(LISTING_ID, MEMBER_ID, request))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.DRAFT_NOT_APPLICABLE));

            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.OPEN, RecruitmentVisibility.CUSTOM_TEMPLATE));
            org.mockito.Mockito.doThrow(new com.mannschaft.app.common.BusinessException(
                            com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_001))
                    .when(visibilityChecker).assertCanView(ReferenceType.RECRUITMENT_LISTING, LISTING_ID, MEMBER_ID);

            assertThatThrownBy(() -> facade().apply(LISTING_ID, MEMBER_ID, request))
                    .satisfies(e -> assertThat(codeOf(e))
                            .isEqualTo(com.mannschaft.app.common.visibility.VisibilityErrorCode.VISIBILITY_001));

            verifyNoInteractions(participantService);
        }

        @Test
        @DisplayName("申込: 閲覧できる者（公開物の部外者・SYSTEM_ADMIN）は在籍の判定なしで tx 本体へ進む")
        void apply_viewerSkipsInsiderCheck() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(teamListing(RecruitmentListingStatus.OPEN, RecruitmentVisibility.PUBLIC));
            given(visibilityChecker.canView(ReferenceType.RECRUITMENT_LISTING, LISTING_ID, OUTSIDER_ID))
                    .willReturn(true);
            ApplyToRecruitmentRequest request = mock(ApplyToRecruitmentRequest.class);

            facade().apply(LISTING_ID, OUTSIDER_ID, request);

            verify(participantService).apply(LISTING_ID, OUTSIDER_ID, request);
            verifyNoInteractions(accessControlService);
        }

        @Test
        @DisplayName("申込: 自分の個人札は可視性の判定をせず tx 本体へ進む（自己応募の禁止は tx 本体）")
        void apply_ownPersonalListingGoesToBody() {
            given(listingService.resolveListingScope(LISTING_ID))
                    .willReturn(personalListing(RecruitmentListingStatus.OPEN));
            ApplyToRecruitmentRequest request = mock(ApplyToRecruitmentRequest.class);

            facade().apply(LISTING_ID, OWNER_ID, request);

            verify(participantService).apply(LISTING_ID, OWNER_ID, request);
            verifyNoInteractions(visibilityChecker, accessControlService);
        }
    }

    // ========================================
    // テンプレート
    // ========================================

    @Nested
    @DisplayName("テンプレート（GET・PATCH・archive）")
    class Templates {

        private void givenTemplate() {
            given(templateService.resolveTemplateScope(TEMPLATE_ID))
                    .willReturn(new TemplateAccessScope(RecruitmentScopeType.TEAM, TEAM_ID));
        }

        @Test
        @DisplayName("GET: 在籍メンバーは isMember 1 本だけで許可（SYSTEM_ADMIN・管理者の判定を足さない）")
        void get_memberIsAllowedWithSingleQuery() {
            givenTemplate();
            given(accessControlService.isMember(MEMBER_ID, TEAM_ID, "TEAM")).willReturn(true);
            RecruitmentTemplateResponse response = mock(RecruitmentTemplateResponse.class);
            given(templateService.getTemplate(TEMPLATE_ID)).willReturn(response);

            assertThat(facade().getTemplate(TEMPLATE_ID, MEMBER_ID)).isSameAs(response);

            verify(accessControlService).isMember(MEMBER_ID, TEAM_ID, "TEAM");
            verifyNoMoreInteractions(accessControlService);
        }

        @Test
        @DisplayName("GET: 在籍なしの管理者（user_roles のみ）・SYSTEM_ADMIN は 403、部外者は不在と同一の 404（TEMPLATE_NOT_FOUND）")
        void get_denialSplitsByKnowledge() {
            givenTemplate();
            lenient().when(accessControlService.isAdminOrAbove(55L, TEAM_ID, "TEAM")).thenReturn(true);
            lenient().when(accessControlService.isSystemAdmin(77L)).thenReturn(true);

            assertThatThrownBy(() -> facade().getTemplate(TEMPLATE_ID, 55L))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(CommonErrorCode.COMMON_002));
            assertThatThrownBy(() -> facade().getTemplate(TEMPLATE_ID, 77L))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(CommonErrorCode.COMMON_002));
            assertThatThrownBy(() -> facade().getTemplate(TEMPLATE_ID, OUTSIDER_ID))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.TEMPLATE_NOT_FOUND));

            verify(templateService, never()).getTemplate(anyLong());
        }

        @Test
        @DisplayName("PATCH・archive: 管理者は許可（isAdminOrAbove 1 本）、メンバー・SYSTEM_ADMIN は 403、部外者は 404。tx 本体は許可時のみ")
        void write_splitsByKnowledge() {
            givenTemplate();
            given(accessControlService.isAdminOrAbove(ADMIN_ID, TEAM_ID, "TEAM")).willReturn(true);
            given(accessControlService.isMember(MEMBER_ID, TEAM_ID, "TEAM")).willReturn(true);

            facade().archiveTemplate(TEMPLATE_ID, ADMIN_ID);
            assertThatThrownBy(() -> facade().archiveTemplate(TEMPLATE_ID, MEMBER_ID))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(CommonErrorCode.COMMON_002));
            assertThatThrownBy(() -> facade().updateTemplate(TEMPLATE_ID, OUTSIDER_ID, null))
                    .satisfies(e -> assertThat(codeOf(e)).isEqualTo(RecruitmentErrorCode.TEMPLATE_NOT_FOUND));

            verify(templateService, Mockito.times(1)).archive(TEMPLATE_ID);
            verify(templateService, never()).update(anyLong(), any());
        }
    }
}
