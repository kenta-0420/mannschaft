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
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ParentalConsentReleaseBatchService} の単体テスト。
 * F01.9 年齢確認・保護者同意機能 Wave 3B の 18歳到達自動解放バッチを検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParentalConsentReleaseBatchService")
class ParentalConsentReleaseBatchServiceTest {

    @InjectMocks
    private ParentalConsentReleaseBatchService releaseBatchService;

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
     * 指定の生年月日を持つ子ユーザーを生成する。
     */
    private UserEntity buildChildUser(Long id, String email, String birthDate) {
        UserEntity user = UserEntity.builder()
                .email(email)
                .lastName("山田")
                .firstName("太郎")
                .displayName("たろう")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .birthDate(birthDate)
                .build();
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    /**
     * 指定の childUserId を持つ APPROVED リンクエンティティを生成する。
     */
    private ParentalConsentLinkEntity buildApprovedLink(Long childUserId) {
        ParentalConsentLinkEntity link = ParentalConsentLinkEntity.builder()
                .childUserId(childUserId)
                .parentEmail("parent@example.com")
                .tokenHash("dummyhash")
                .status(ParentalConsentLinkStatus.APPROVED)
                .expiresAt(LocalDateTime.now().plusDays(7))
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
        @DisplayName("正常_対象なし_何も実行されない")
        void 正常_対象なし_何も実行されない() {
            // given: APPROVED リンクが存在しない
            when(parentalConsentLinkRepository.findByStatus(
                    eq(ParentalConsentLinkStatus.APPROVED), any(Pageable.class)))
                    .thenReturn(List.of());

            // when
            releaseBatchService.execute();

            // then: ユーザー取得・メール送信は呼ばれない
            verify(userRepository, never()).findById(any());
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("正常_成人到達済み_APPROVEDリンクがREVOKEDになる")
        void 正常_成人到達済み_APPROVEDリンクがREVOKEDになる() {
            // given: 1980年生まれの成人ユーザー（18歳以上確定）
            Long childUserId = 1L;
            UserEntity adultUser = buildChildUser(childUserId, "adult@example.com", "1980-01-01");
            ParentalConsentLinkEntity approvedLink = buildApprovedLink(childUserId);

            when(parentalConsentLinkRepository.findByStatus(
                    eq(ParentalConsentLinkStatus.APPROVED), any(Pageable.class)))
                    .thenReturn(List.of(approvedLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.of(adultUser));

            // when
            releaseBatchService.execute();

            // then: リンクが REVOKED に更新されていること
            assertThat(approvedLink.getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
            assertThat(approvedLink.getRevokedBy()).isNull(); // SYSTEM による自動解放
        }

        @Test
        @DisplayName("正常_未成年_処理スキップ")
        void 正常_未成年_処理スキップ() {
            // given: 現在から18年未満（未成年）の子ユーザー
            Long childUserId = 2L;
            // 2010年生まれ（2026年現在 16歳、未成年）
            UserEntity minorUser = buildChildUser(childUserId, "minor@example.com", "2010-06-15");
            ParentalConsentLinkEntity approvedLink = buildApprovedLink(childUserId);

            when(parentalConsentLinkRepository.findByStatus(
                    eq(ParentalConsentLinkStatus.APPROVED), any(Pageable.class)))
                    .thenReturn(List.of(approvedLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.of(minorUser));

            // when
            releaseBatchService.execute();

            // then: リンクのステータスは変わらない（スキップ）
            assertThat(approvedLink.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("正常_成人_メール送信される")
        void 正常_成人_メール送信される() {
            // given: 1980年生まれの成人ユーザー
            Long childUserId = 3L;
            UserEntity adultUser = buildChildUser(childUserId, "adult3@example.com", "1980-03-20");
            ParentalConsentLinkEntity approvedLink = buildApprovedLink(childUserId);

            when(parentalConsentLinkRepository.findByStatus(
                    eq(ParentalConsentLinkStatus.APPROVED), any(Pageable.class)))
                    .thenReturn(List.of(approvedLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.of(adultUser));

            // when
            releaseBatchService.execute();

            // then: メール送信が呼ばれること
            ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(captor.capture());

            EmailOutboxRequest request = captor.getValue();
            assertThat(request.templateKind()).isEqualTo("PARENTAL_CONSENT_RELEASED");
            assertThat(request.toAddress()).isEqualTo("adult3@example.com");
            assertThat(request.sourceDomain()).isEqualTo("auth");
        }

        @Test
        @DisplayName("正常_ユーザー存在しない_スキップされる")
        void 正常_ユーザー存在しない_スキップされる() {
            // given: リンクが存在するがユーザーは削除済み
            Long childUserId = 4L;
            ParentalConsentLinkEntity approvedLink = buildApprovedLink(childUserId);

            when(parentalConsentLinkRepository.findByStatus(
                    eq(ParentalConsentLinkStatus.APPROVED), any(Pageable.class)))
                    .thenReturn(List.of(approvedLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.empty());

            // when
            releaseBatchService.execute();

            // then: リンクのステータスは変わらず、メール送信もされない
            assertThat(approvedLink.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("正常_生年月日未設定_スキップされる")
        void 正常_生年月日未設定_スキップされる() {
            // given: 生年月日が設定されていないユーザー
            Long childUserId = 5L;
            UserEntity userWithoutBirthDate = buildChildUser(childUserId, "nobirth@example.com", null);
            ParentalConsentLinkEntity approvedLink = buildApprovedLink(childUserId);

            when(parentalConsentLinkRepository.findByStatus(
                    eq(ParentalConsentLinkStatus.APPROVED), any(Pageable.class)))
                    .thenReturn(List.of(approvedLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.of(userWithoutBirthDate));

            // when
            releaseBatchService.execute();

            // then: リンクのステータスは変わらず、メール送信もされない
            assertThat(approvedLink.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
            verify(emailOutboxService, never()).enqueue(any());
        }
    }
}
