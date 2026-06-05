package com.mannschaft.app.auth.controller;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.dto.ApproveConsentRequest;
import com.mannschaft.app.auth.dto.ChildLinkResponse;
import com.mannschaft.app.auth.dto.InvitationResponse;
import com.mannschaft.app.auth.dto.InviteParentRequest;
import com.mannschaft.app.auth.dto.MessageResponse;
import com.mannschaft.app.auth.dto.ParentLinkResponse;
import com.mannschaft.app.auth.dto.RejectConsentRequest;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.common.ApiResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;

/**
 * F01.9 年齢確認・保護者同意機能: ParentalConsentController 単体テスト。
 *
 * <p>SecurityContextHolder を設定してコントローラーを直接呼び出す方式。
 * Service は Mockito でモックする。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParentalConsentController 単体テスト")
class ParentalConsentControllerTest {

    private static final Long CHILD_USER_ID = 100L;
    private static final Long PARENT_USER_ID = 200L;
    private static final String TEST_TOKEN = "test-consent-token-abc123";
    private static final String TEST_LINK_ID = UUID.randomUUID().toString();
    private static final String PARENT_EMAIL = "parent@example.com";

    @Mock
    private ParentalConsentService parentalConsentService;

    @Mock
    private com.mannschaft.app.auth.guardianship.AuthenticationCriticalOperationGuard
            authenticationCriticalOperationGuard;

    @InjectMocks
    private ParentalConsentController controller;

    @BeforeEach
    void setUp() {
        // 子ユーザーとして認証
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(CHILD_USER_ID.toString(), null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /**
     * テスト用 ParentalConsentLinkEntity を生成するヘルパー。
     */
    private ParentalConsentLinkEntity buildLink(ParentalConsentLinkStatus status) {
        ParentalConsentLinkEntity link = ParentalConsentLinkEntity.builder()
                .childUserId(CHILD_USER_ID)
                .parentUserId(PARENT_USER_ID)
                .parentEmail(PARENT_EMAIL)
                .tokenHash("hashedtoken")
                .status(status)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        link.setId(UUID.fromString(TEST_LINK_ID));
        return link;
    }

    // ========================================
    // 招待操作
    // ========================================

    @Nested
    @DisplayName("POST /invitations — 保護者招待送信")
    class InviteParentTests {

        @Test
        @DisplayName("正常系: 招待送信が200で返る")
        void inviteParent_正常_200() {
            // Given
            doNothing().when(parentalConsentService).inviteParent(eq(CHILD_USER_ID), eq(PARENT_EMAIL));
            InviteParentRequest req = new InviteParentRequest(PARENT_EMAIL);

            // When
            ResponseEntity<ApiResponse<MessageResponse>> response = controller.inviteParent(req);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getMessage()).contains("招待メール");
            verify(parentalConsentService).inviteParent(CHILD_USER_ID, PARENT_EMAIL);
        }
    }

    @Nested
    @DisplayName("GET /invitations — 招待一覧取得")
    class GetInvitationsTests {

        @Test
        @DisplayName("正常系: 招待一覧が200で返る")
        void getInvitations_正常_200() {
            // Given
            ParentalConsentLinkEntity link = buildLink(ParentalConsentLinkStatus.PENDING);
            given(parentalConsentService.getInvitations(CHILD_USER_ID)).willReturn(List.of(link));

            // When
            ResponseEntity<ApiResponse<List<InvitationResponse>>> response = controller.getInvitations();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            assertThat(response.getBody().getData().get(0).getParentEmail()).isEqualTo(PARENT_EMAIL);
            assertThat(response.getBody().getData().get(0).getStatus()).isEqualTo("PENDING");
            verify(parentalConsentService).getInvitations(CHILD_USER_ID);
        }

        @Test
        @DisplayName("正常系: 招待が空の場合は空リストで200で返る")
        void getInvitations_空の場合_200() {
            // Given
            given(parentalConsentService.getInvitations(CHILD_USER_ID)).willReturn(List.of());

            // When
            ResponseEntity<ApiResponse<List<InvitationResponse>>> response = controller.getInvitations();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).isEmpty();
        }
    }

    @Nested
    @DisplayName("DELETE /invitations/{linkId} — 招待取消")
    class CancelInvitationTests {

        @Test
        @DisplayName("正常系: 招待取消が200で返る")
        void cancelInvitation_正常_200() {
            // Given
            doNothing().when(parentalConsentService).revokeInvitation(eq(TEST_LINK_ID), eq(CHILD_USER_ID));

            // When
            ResponseEntity<ApiResponse<MessageResponse>> response = controller.cancelInvitation(TEST_LINK_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getMessage()).contains("取消");
            verify(parentalConsentService).revokeInvitation(TEST_LINK_ID, CHILD_USER_ID);
        }
    }

    // ========================================
    // 保護者リンク操作（子側）
    // ========================================

    @Nested
    @DisplayName("GET /parents — 承認済み保護者一覧")
    class GetParentsTests {

        @Test
        @DisplayName("正常系: 承認済み保護者一覧が200で返る")
        void getParents_正常_200() {
            // Given
            ParentalConsentLinkEntity link = buildLink(ParentalConsentLinkStatus.APPROVED);
            link.approve(PARENT_USER_ID);
            given(parentalConsentService.getApprovedParents(CHILD_USER_ID)).willReturn(List.of(link));

            // When
            ResponseEntity<ApiResponse<List<ParentLinkResponse>>> response = controller.getParents();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            assertThat(response.getBody().getData().get(0).getParentEmail()).isEqualTo(PARENT_EMAIL);
            verify(parentalConsentService).getApprovedParents(CHILD_USER_ID);
        }
    }

    @Nested
    @DisplayName("DELETE /parents/{linkId} — 保護者リンク解除（子側）")
    class RemoveParentTests {

        @Test
        @DisplayName("正常系: 保護者リンク解除が200で返る")
        void removeParent_正常_200() {
            // Given
            doNothing().when(parentalConsentService).removeParentalLink(eq(TEST_LINK_ID), eq(CHILD_USER_ID));

            // When
            ResponseEntity<ApiResponse<MessageResponse>> response = controller.removeParent(TEST_LINK_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getMessage()).contains("解除");
            verify(parentalConsentService).removeParentalLink(TEST_LINK_ID, CHILD_USER_ID);
        }
    }

    // ========================================
    // 保護者側 — 承認・否認操作（認証不要エンドポイント）
    // ========================================

    @Nested
    @DisplayName("POST /approve — 保護者同意承認")
    class ApproveTests {

        @Test
        @DisplayName("正常系: 認証済みユーザーが承認すると200で返る")
        void approve_認証済み_200() {
            // Given（認証済みユーザーのセットアップは @BeforeEach で実施済み）
            doNothing().when(parentalConsentService).approveParentalConsent(eq(TEST_TOKEN), eq(CHILD_USER_ID));
            ApproveConsentRequest req = new ApproveConsentRequest(TEST_TOKEN);

            // When
            ResponseEntity<ApiResponse<MessageResponse>> response = controller.approve(req);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getMessage()).contains("承認");
            verify(parentalConsentService).approveParentalConsent(TEST_TOKEN, CHILD_USER_ID);
        }

        @Test
        @DisplayName("正常系: 未認証ユーザー（メールリンク直接アクセス）が承認すると200で返る")
        void approve_未認証_200() {
            // Given
            SecurityContextHolder.clearContext(); // 未認証状態にする
            doNothing().when(parentalConsentService).approveParentalConsent(eq(TEST_TOKEN), eq(null));
            ApproveConsentRequest req = new ApproveConsentRequest(TEST_TOKEN);

            // When
            ResponseEntity<ApiResponse<MessageResponse>> response = controller.approve(req);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getMessage()).contains("承認");
            verify(parentalConsentService).approveParentalConsent(TEST_TOKEN, null);
        }
    }

    @Nested
    @DisplayName("POST /reject — 保護者同意否認")
    class RejectTests {

        @Test
        @DisplayName("正常系: 保護者同意否認が200で返る")
        void reject_正常_200() {
            // Given
            doNothing().when(parentalConsentService).rejectParentalConsent(eq(TEST_TOKEN));
            RejectConsentRequest req = new RejectConsentRequest(TEST_TOKEN);

            // When
            ResponseEntity<ApiResponse<MessageResponse>> response = controller.reject(req);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getMessage()).contains("否認");
            verify(parentalConsentService).rejectParentalConsent(TEST_TOKEN);
        }
    }

    // ========================================
    // 保護者側 — 子一覧取得
    // ========================================

    @Nested
    @DisplayName("GET /children — 子ユーザー一覧取得（保護者）")
    class GetChildrenTests {

        @Test
        @DisplayName("正常系: 子一覧が200で返る（displayName は null）")
        void getChildren_正常_200() {
            // Given（保護者ユーザーとして認証）
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(PARENT_USER_ID.toString(), null, List.of())
            );
            ParentalConsentLinkEntity link = buildLink(ParentalConsentLinkStatus.APPROVED);
            given(parentalConsentService.getChildrenAsParent(PARENT_USER_ID)).willReturn(List.of(link));

            // When
            ResponseEntity<ApiResponse<List<ChildLinkResponse>>> response = controller.getChildren();

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData()).hasSize(1);
            assertThat(response.getBody().getData().get(0).getChildUserId()).isEqualTo(CHILD_USER_ID);
            // PII リスク低減のため displayName は null
            assertThat(response.getBody().getData().get(0).getChildDisplayName()).isNull();
            verify(parentalConsentService).getChildrenAsParent(PARENT_USER_ID);
        }
    }

    // ========================================
    // 保護者側 — リンク解除（DELETE /children/{linkId}）
    // ========================================

    @Nested
    @DisplayName("DELETE /children/{linkId} — 保護者リンク解除（保護者側）")
    class RemoveChildLinkTests {

        @Test
        @DisplayName("正常系: 保護者側からのリンク解除が200で返る")
        void removeChildLink_正常_200() {
            // Given（保護者ユーザーとして認証）
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(PARENT_USER_ID.toString(), null, List.of())
            );
            doNothing().when(parentalConsentService).removeParentalLinkAsParent(eq(TEST_LINK_ID), eq(PARENT_USER_ID));

            // When
            ResponseEntity<ApiResponse<MessageResponse>> response = controller.removeChildLink(TEST_LINK_ID);

            // Then
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().getData().getMessage()).contains("解除");
            verify(parentalConsentService).removeParentalLinkAsParent(TEST_LINK_ID, PARENT_USER_ID);
        }
    }
}
