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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ParentalConsentReleaseBatchService} の単体テスト。
 * F01.9 年齢確認・保護者同意機能 Wave 3B の 18歳到達自動解放バッチを検証する。
 *
 * <p>本テストは「成人到達者が取得ページに埋もれて解放されない」飢餓を防ぐ番人である。
 * 成人条件は取得クエリ側（WHERE 句）で絞り込まれていなければならない。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParentalConsentReleaseBatchService")
class ParentalConsentReleaseBatchServiceTest {

    /** 本番実装と同じページサイズ（飢餓テスト用）。*/
    private static final int PAGE_SIZE = 500;

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
        return ParentalConsentLinkEntity.builder()
                .childUserId(childUserId)
                .parentEmail("parent@example.com")
                .tokenHash("dummyhash")
                .status(ParentalConsentLinkStatus.APPROVED)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
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
            // given: 成人到達済みの APPROVED リンクが存在しない
            when(parentalConsentLinkRepository.findAdultApprovedLinks(
                    eq(ParentalConsentLinkStatus.APPROVED), anyString(), any(Pageable.class)))
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

            stubSinglePage(List.of(approvedLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.of(adultUser));

            // when
            releaseBatchService.execute();

            // then: リンクが REVOKED に更新されていること
            assertThat(approvedLink.getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
            assertThat(approvedLink.getRevokedBy()).isNull(); // SYSTEM による自動解放
        }

        @Test
        @DisplayName("正常_成人_メール送信される")
        void 正常_成人_メール送信される() {
            // given: 1980年生まれの成人ユーザー
            Long childUserId = 3L;
            UserEntity adultUser = buildChildUser(childUserId, "adult3@example.com", "1980-03-20");
            ParentalConsentLinkEntity approvedLink = buildApprovedLink(childUserId);

            stubSinglePage(List.of(approvedLink));
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

            stubSinglePage(List.of(approvedLink));
            when(userRepository.findById(childUserId)).thenReturn(Optional.empty());

            // when
            releaseBatchService.execute();

            // then: リンクのステータスは変わらず、メール送信もされない
            assertThat(approvedLink.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("AC-0-7: 年齢の閾値は「今日ちょうど18歳」を含む境界で取得クエリに渡される")
        void 年齢閾値は今日18歳到達を含む() {
            // given
            stubSinglePage(List.of());

            // when
            releaseBatchService.execute();

            // then: 誕生日当日に18歳へ到達した子（生年月日 = 今日の18年前）も
            //       birth_date <= cutoff で拾えるよう、cutoff は「今日の18年前」ちょうどであること
            ArgumentCaptor<String> cutoffCaptor = ArgumentCaptor.forClass(String.class);
            verify(parentalConsentLinkRepository).findAdultApprovedLinks(
                    eq(ParentalConsentLinkStatus.APPROVED), cutoffCaptor.capture(), any(Pageable.class));

            LocalDate cutoff = LocalDate.parse(cutoffCaptor.getValue());
            assertThat(cutoff).isEqualTo(
                    com.mannschaft.app.common.util.AgeGroupCalculator.adultBirthDateThreshold(LocalDate.now()));
            // 境界: cutoff 当日生まれは成人（未成年でない）／その翌日生まれは未成年
            assertThat(com.mannschaft.app.common.util.AgeGroupCalculator.isMinor(cutoff, LocalDate.now())).isFalse();
            assertThat(com.mannschaft.app.common.util.AgeGroupCalculator
                    .isMinor(cutoff.plusDays(1), LocalDate.now())).isTrue();
        }

        @Test
        @DisplayName("AC-0-6: APPROVEDが上限超でも成人到達者は同日中に全員解放される（飢餓しない）")
        void 上限超でも成人到達者が飢餓しない() {
            // given: 成人到達者が PAGE_SIZE を超えて PAGE_SIZE + 10 人いる。
            //        未成年は WHERE 句で除外されるため、そもそも取得ページに混ざらない。
            List<ParentalConsentLinkEntity> firstPage = new ArrayList<>();
            List<ParentalConsentLinkEntity> secondPage = new ArrayList<>();
            for (long i = 1; i <= PAGE_SIZE; i++) {
                firstPage.add(buildApprovedLink(i));
            }
            for (long i = PAGE_SIZE + 1; i <= PAGE_SIZE + 10; i++) {
                secondPage.add(buildApprovedLink(i));
            }

            when(parentalConsentLinkRepository.findAdultApprovedLinks(
                    eq(ParentalConsentLinkStatus.APPROVED), anyString(), any(Pageable.class)))
                    .thenReturn(firstPage)
                    .thenReturn(secondPage)
                    .thenReturn(List.of());

            when(userRepository.findById(any())).thenAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                return Optional.of(buildChildUser(id, "adult" + id + "@example.com", "1980-01-01"));
            });

            // when
            releaseBatchService.execute();

            // then: 2ページ目の到達者まで含めて全員が REVOKED になる
            assertThat(firstPage).allMatch(l -> l.getStatus() == ParentalConsentLinkStatus.REVOKED);
            assertThat(secondPage).allMatch(l -> l.getStatus() == ParentalConsentLinkStatus.REVOKED);
            verify(emailOutboxService, org.mockito.Mockito.times(PAGE_SIZE + 10)).enqueue(any());
        }
    }

    /** 1ページだけ返し、以降は空を返す取得スタブ。*/
    private void stubSinglePage(List<ParentalConsentLinkEntity> page) {
        when(parentalConsentLinkRepository.findAdultApprovedLinks(
                eq(ParentalConsentLinkStatus.APPROVED), anyString(), any(Pageable.class)))
                .thenReturn(page)
                .thenReturn(List.of());
    }
}
