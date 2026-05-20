package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ParentalConsentCleanupBatchService} の単体テスト。
 * F01.9 年齢確認・保護者同意機能 Wave 3B の期限切れクリーンアップバッチを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParentalConsentCleanupBatchService")
class ParentalConsentCleanupBatchServiceTest {

    @InjectMocks
    private ParentalConsentCleanupBatchService cleanupBatchService;

    @Mock
    private ParentalConsentLinkRepository parentalConsentLinkRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailOutboxService emailOutboxService;

    // ========================================
    // テストヘルパー
    // ========================================

    /**
     * 子ユーザーを生成する。
     */
    private UserEntity buildChildUser(Long id, String email) {
        UserEntity user = UserEntity.builder()
                .email(email)
                .lastName("山田")
                .firstName("太郎")
                .displayName("たろう")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.PENDING_PARENTAL_CONSENT)
                .isSearchable(true)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /**
     * 期限切れの PENDING リンクを生成する。
     */
    private ParentalConsentLinkEntity buildExpiredPendingLink(Long childUserId) {
        ParentalConsentLinkEntity link = ParentalConsentLinkEntity.builder()
                .childUserId(childUserId)
                .parentEmail("parent@example.com")
                .tokenHash("expired-token-hash-" + childUserId)
                .status(ParentalConsentLinkStatus.PENDING)
                .expiresAt(LocalDateTime.now().minusDays(1)) // 期限切れ
                .build();
        return link;
    }

    // ========================================
    // execute() テスト
    // ========================================

    @Nested
    @DisplayName("execute")
    class Execute {

        @Test
        @DisplayName("正常_期限切れなし_何も実行されない")
        void 正常_期限切れなし_何も実行されない() {
            // given: 期限切れ PENDING リンクが存在しない
            when(parentalConsentLinkRepository.findByStatusAndExpiresAtBefore(
                    eq(ParentalConsentLinkStatus.PENDING), any(LocalDateTime.class)))
                    .thenReturn(List.of());

            // when
            cleanupBatchService.execute();

            // then: ユーザー取得・メール送信は呼ばれない
            verify(userRepository, never()).findById(any());
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("正常_期限切れPENDING_REVOKEDになる")
        void 正常_期限切れPENDING_REVOKEDになる() {
            // given: 期限切れ PENDING リンクが1件
            Long childUserId = 1L;
            ParentalConsentLinkEntity expiredLink = buildExpiredPendingLink(childUserId);
            UserEntity childUser = buildChildUser(childUserId, "child@example.com");

            when(parentalConsentLinkRepository.findByStatusAndExpiresAtBefore(
                    eq(ParentalConsentLinkStatus.PENDING), any(LocalDateTime.class)))
                    .thenReturn(List.of(expiredLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.of(childUser));
            // APPROVED も PENDING も残っていない → 削除対象
            when(parentalConsentLinkRepository.existsByChildUserIdAndStatusIn(
                    eq(childUserId), any(Collection.class)))
                    .thenReturn(false);

            // when
            cleanupBatchService.execute();

            // then: リンクが REVOKED に更新されていること
            assertThat(expiredLink.getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
            assertThat(expiredLink.getRevokedBy()).isNull(); // SYSTEM による自動失効
        }

        @Test
        @DisplayName("正常_APPROVED残存_アカウント削除されない")
        void 正常_APPROVED残存_アカウント削除されない() {
            // given: 期限切れ PENDING はあるが、別の APPROVED リンクが残っている
            Long childUserId = 2L;
            ParentalConsentLinkEntity expiredLink = buildExpiredPendingLink(childUserId);
            UserEntity childUser = buildChildUser(childUserId, "child2@example.com");

            when(parentalConsentLinkRepository.findByStatusAndExpiresAtBefore(
                    eq(ParentalConsentLinkStatus.PENDING), any(LocalDateTime.class)))
                    .thenReturn(List.of(expiredLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.of(childUser));

            // APPROVED リンクが存在する（1回目の呼び出し = APPROVED チェック）
            when(parentalConsentLinkRepository.existsByChildUserIdAndStatusIn(
                    eq(childUserId), any(Collection.class)))
                    .thenReturn(true); // APPROVED が残っている

            // when
            cleanupBatchService.execute();

            // then: アカウント削除（anonymize + softDelete）は呼ばれない
            // UserEntity の email は変更されていないこと
            assertThat(childUser.getEmail()).isEqualTo("child2@example.com");
            assertThat(childUser.getDeletedAt()).isNull();
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("正常_全リンク失効_アカウント削除される")
        void 正常_全リンク失効_アカウント削除される() {
            // given: 期限切れ PENDING のみ存在し、APPROVED・PENDING が残っていない
            Long childUserId = 3L;
            ParentalConsentLinkEntity expiredLink = buildExpiredPendingLink(childUserId);
            UserEntity childUser = buildChildUser(childUserId, "child3@example.com");

            when(parentalConsentLinkRepository.findByStatusAndExpiresAtBefore(
                    eq(ParentalConsentLinkStatus.PENDING), any(LocalDateTime.class)))
                    .thenReturn(List.of(expiredLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.of(childUser));
            // 両方のステータスチェックで false を返す
            when(parentalConsentLinkRepository.existsByChildUserIdAndStatusIn(
                    eq(childUserId), any(Collection.class)))
                    .thenReturn(false);
            when(userRepository.save(any())).thenReturn(childUser);

            // when
            cleanupBatchService.execute();

            // then: anonymize + softDelete が呼ばれていること
            // anonymize() により email が匿名化されている
            assertThat(childUser.getEmail()).contains("@deleted.mannschaft.internal");
            // softDelete() により deletedAt が設定されている
            assertThat(childUser.getDeletedAt()).isNotNull();

            // then: 削除通知メールが送信されること
            ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(captor.capture());
            EmailOutboxRequest request = captor.getValue();
            assertThat(request.templateKind()).isEqualTo("PARENTAL_CONSENT_EXPIRED_ACCOUNT_DELETED");
            // anonymize() 前に退避したアドレス（元のアドレス）宛に送信されること
            assertThat(request.toAddress()).isEqualTo("child3@example.com");
            assertThat(request.sourceDomain()).isEqualTo("auth");
        }

        @Test
        @DisplayName("正常_ユーザー存在しない_スキップされる")
        void 正常_ユーザー存在しない_スキップされる() {
            // given: リンクは期限切れだがユーザーは既に削除済み
            Long childUserId = 4L;
            ParentalConsentLinkEntity expiredLink = buildExpiredPendingLink(childUserId);

            when(parentalConsentLinkRepository.findByStatusAndExpiresAtBefore(
                    eq(ParentalConsentLinkStatus.PENDING), any(LocalDateTime.class)))
                    .thenReturn(List.of(expiredLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.empty());

            // when
            cleanupBatchService.execute();

            // then: メール送信はされない
            verify(emailOutboxService, never()).enqueue(any());
        }
    }
}
