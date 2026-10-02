package com.mannschaft.app.directmail.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.DomainEventPublisher;
import com.mannschaft.app.directmail.DirectMailMapper;
import com.mannschaft.app.directmail.entity.DirectMailLogEntity;
import com.mannschaft.app.directmail.repository.DirectMailLogRepository;
import com.mannschaft.app.directmail.repository.DirectMailRecipientRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.credit.service.NotificationCreditService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * {@link DirectMailService} の閲覧系（listMails / getMail）認可単体テスト
 * （CMP-260917-1350 Phase 1）。
 *
 * <p>組織サイドバーで ADMIN/DEPUTY_ADMIN 限定表示している「ダイレクトメール」機能の GET が
 * {@code checkMembership} 止まりで MEMBER も閲覧できていた認可漏れを根治する。ただし
 * <b>ORGANIZATION スコープのみ</b> ADMIN 必須とし、<b>TEAM スコープは
 * {@code DirectMailScopeContractIT} が固定した契約（一般メンバーのメール一覧取得は200・
 * 閲覧系はcheckMembership）を維持する</b>。本テストはスコープ差分を番人として固定する
 * （2026-09-17: 最初の是正がスコープを区別せず TEAM 側の契約を巻き込みかけた）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DirectMailService 認可単体テスト（listMails / getMail・スコープ差分）")
class DirectMailServiceAuthzTest {

    @Mock private AccessControlService accessControlService;
    @Mock private DirectMailLogRepository mailLogRepository;
    @Mock private DirectMailRecipientRepository recipientRepository;
    @Mock private DirectMailMapper directMailMapper;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private DomainEventPublisher eventPublisher;
    @Mock private NotificationCreditService notificationCreditService;
    @Mock private EmailOutboxService emailOutboxService;
    @InjectMocks private DirectMailService service;

    private static final Long SCOPE_ID = 1L;
    private static final Long MAIL_ID = 10L;
    private static final Long ACTOR_ID = 100L;

    @Nested
    @DisplayName("listMails")
    class ListMails {

        @Test
        @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
        void organization_member_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq("ORGANIZATION"));

            Pageable pageable = PageRequest.of(0, 20);
            assertThatThrownBy(() -> service.listMails("ORGANIZATION", SCOPE_ID, ACTOR_ID, pageable))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("ORGANIZATIONスコープ: ADMINは200相当で取得できる")
        void organization_admin_isAllowed() {
            doNothing().when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq("ORGANIZATION"));
            Pageable pageable = PageRequest.of(0, 20);
            given(mailLogRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(eq("ORGANIZATION"), eq(SCOPE_ID), any()))
                    .willReturn(Page.empty(pageable));
            given(directMailMapper.toMailResponseList(any())).willReturn(java.util.List.of());

            assertThatCode(() -> service.listMails("ORGANIZATION", SCOPE_ID, ACTOR_ID, pageable))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("TEAMスコープ: MEMBERは200相当で取得できる（スコープ契約維持）")
        void team_member_isAllowed() {
            doNothing().when(accessControlService).checkMembership(anyLong(), eq(SCOPE_ID), eq("TEAM"));
            Pageable pageable = PageRequest.of(0, 20);
            given(mailLogRepository.findByScopeTypeAndScopeIdOrderByCreatedAtDesc(eq("TEAM"), eq(SCOPE_ID), any()))
                    .willReturn(Page.empty(pageable));
            given(directMailMapper.toMailResponseList(any())).willReturn(java.util.List.of());

            assertThatCode(() -> service.listMails("TEAM", SCOPE_ID, ACTOR_ID, pageable))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("TEAMスコープ: 非メンバーは403（COMMON_002）で拒否される")
        void team_nonMember_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(anyLong(), eq(SCOPE_ID), eq("TEAM"));

            Pageable pageable = PageRequest.of(0, 20);
            assertThatThrownBy(() -> service.listMails("TEAM", SCOPE_ID, ACTOR_ID, pageable))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    @Nested
    @DisplayName("getMail")
    class GetMail {

        @Test
        @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
        void organization_member_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq("ORGANIZATION"));

            assertThatThrownBy(() -> service.getMail("ORGANIZATION", SCOPE_ID, ACTOR_ID, MAIL_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("ORGANIZATIONスコープ: ADMINは200相当で取得できる")
        void organization_admin_isAllowed() {
            doNothing().when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq("ORGANIZATION"));
            DirectMailLogEntity entity = DirectMailLogEntity.builder()
                    .scopeType("ORGANIZATION")
                    .scopeId(SCOPE_ID)
                    .senderId(ACTOR_ID)
                    .subject("件名")
                    .bodyMarkdown("本文")
                    .recipientType("ALL")
                    .build();
            given(mailLogRepository.findByIdAndScopeTypeAndScopeId(MAIL_ID, "ORGANIZATION", SCOPE_ID))
                    .willReturn(Optional.of(entity));

            assertThatCode(() -> service.getMail("ORGANIZATION", SCOPE_ID, ACTOR_ID, MAIL_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("TEAMスコープ: MEMBERは200相当で取得できる（スコープ契約維持）")
        void team_member_isAllowed() {
            doNothing().when(accessControlService).checkMembership(anyLong(), eq(SCOPE_ID), eq("TEAM"));
            DirectMailLogEntity entity = DirectMailLogEntity.builder()
                    .scopeType("TEAM")
                    .scopeId(SCOPE_ID)
                    .senderId(ACTOR_ID)
                    .subject("件名")
                    .bodyMarkdown("本文")
                    .recipientType("ALL")
                    .build();
            given(mailLogRepository.findByIdAndScopeTypeAndScopeId(MAIL_ID, "TEAM", SCOPE_ID))
                    .willReturn(Optional.of(entity));

            assertThatCode(() -> service.getMail("TEAM", SCOPE_ID, ACTOR_ID, MAIL_ID))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("TEAMスコープ: 非メンバーは403（COMMON_002）で拒否される")
        void team_nonMember_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkMembership(anyLong(), eq(SCOPE_ID), eq("TEAM"));

            assertThatThrownBy(() -> service.getMail("TEAM", SCOPE_ID, ACTOR_ID, MAIL_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }
    }

    @Nested
    @DisplayName("getStats（CMP-260917-2102 Phase 1 の追撃）")
    class GetStats {

        @Test
        @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
        void organization_member_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq("ORGANIZATION"));

            assertThatThrownBy(() -> service.getStats("ORGANIZATION", SCOPE_ID, ACTOR_ID, MAIL_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("TEAMスコープ: MEMBERは200相当で取得できる（スコープ契約維持）")
        void team_member_isAllowed() {
            doNothing().when(accessControlService).checkMembership(anyLong(), eq(SCOPE_ID), eq("TEAM"));
            DirectMailLogEntity entity = DirectMailLogEntity.builder()
                    .scopeType("TEAM")
                    .scopeId(SCOPE_ID)
                    .senderId(ACTOR_ID)
                    .subject("件名")
                    .bodyMarkdown("本文")
                    .recipientType("ALL")
                    .build();
            given(mailLogRepository.findByIdAndScopeTypeAndScopeId(MAIL_ID, "TEAM", SCOPE_ID))
                    .willReturn(Optional.of(entity));

            assertThatCode(() -> service.getStats("TEAM", SCOPE_ID, ACTOR_ID, MAIL_ID))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("preview（CMP-260917-2102 Phase 1 の追撃）")
    class Preview {

        @Test
        @DisplayName("ORGANIZATIONスコープ: MEMBERは403（COMMON_002）で拒否される")
        void organization_member_isForbidden() {
            doThrow(new BusinessException(CommonErrorCode.COMMON_002))
                    .when(accessControlService).checkAdminOrAbove(anyLong(), eq(SCOPE_ID), eq("ORGANIZATION"));

            var request = new com.mannschaft.app.directmail.dto.PreviewMailRequest("# プレビュー");

            assertThatThrownBy(() -> service.preview("ORGANIZATION", SCOPE_ID, ACTOR_ID, request))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("TEAMスコープ: MEMBERは200相当で取得できる（スコープ契約維持）")
        void team_member_isAllowed() {
            doNothing().when(accessControlService).checkMembership(anyLong(), eq(SCOPE_ID), eq("TEAM"));

            var request = new com.mannschaft.app.directmail.dto.PreviewMailRequest("# プレビュー");

            assertThatCode(() -> service.preview("TEAM", SCOPE_ID, ACTOR_ID, request))
                    .doesNotThrowAnyException();
        }
    }
}
