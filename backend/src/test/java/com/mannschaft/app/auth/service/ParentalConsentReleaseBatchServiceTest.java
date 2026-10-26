package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.ParentalConsentLinkStatus;
import com.mannschaft.app.auth.entity.ParentalConsentLinkEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.ParentalConsentLinkRepository;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.common.util.AgeGroupCalculator;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ParentalConsentReleaseBatchService} の単体テスト。
 * F01.9 年齢確認・保護者同意機能 Wave 3B の 18歳到達自動解放バッチを検証する。
 *
 * <p>本テストは以下 2 つの事故を同時に防ぐ番人である。</p>
 * <ul>
 *   <li><b>未成年の誤解放</b> — SQL は暗号化列 {@code birth_date} を比較できず、平文 {@code birth_year}
 *       による粗い絞り込みしかできない。境界年（今年18歳になる年）の未成年が候補に混ざるため、
 *       復号済み生年月日による確定判定を省くと未成年の保護者同意を解放してしまう。</li>
 *   <li><b>成人到達者の飢餓</b> — 事後フィルタを持つ以上、先頭ページを毎回取り直すページングでは
 *       境界年の未成年が先頭ページを占有した瞬間に後方の成人到達者へ永久に到達できない。
 *       カーソルが判定結果によらず前進することを実際に踏んで検証する。</li>
 * </ul>
 *
 * <p>リポジトリのモックは「キーセットページングを忠実に模倣する」フィクスチャ駆動とし、
 * 呼び出し回数に応じて固定のページを返すスタブにはしない（カーソルを前進させない実装でも
 * 緑になってしまい、飢餓の分岐を一度も踏めないため）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ParentalConsentReleaseBatchService")
class ParentalConsentReleaseBatchServiceTest {

    /** 本番実装と同じページサイズ（飢餓テスト用）。*/
    private static final int PAGE_SIZE = 500;

    /** 本番実装と同じ日付基準タイムゾーン。*/
    private static final ZoneId BATCH_ZONE = ZoneId.of("Asia/Tokyo");

    @InjectMocks
    private ParentalConsentReleaseBatchService releaseBatchService;

    @Mock
    private ParentalConsentLinkRepository parentalConsentLinkRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailOutboxService emailOutboxService;

    /** DB を模したリンク一覧（id 昇順）。*/
    private final List<ParentalConsentLinkEntity> links = new ArrayList<>();

    /** DB を模したユーザー表。*/
    private final Map<Long, UserEntity> users = new HashMap<>();

    /** 連番 id 採番用。*/
    private long nextLinkSeq = 1L;

    private static LocalDate today() {
        return LocalDate.now(BATCH_ZONE);
    }

    // ========================================
    // テストヘルパー
    // ========================================

    /**
     * 指定の生年月日を持つ子ユーザーと、その APPROVED リンクを 1 本ずつ用意する。
     *
     * @param childUserId 子ユーザー ID
     * @param birthDate   生年月日（ISO-8601）
     * @return 生成した APPROVED リンク
     */
    private ParentalConsentLinkEntity givenChildWithApprovedLink(Long childUserId, LocalDate birthDate) {
        UserEntity user = UserEntity.builder()
                .email("child" + childUserId + "@example.com")
                .lastName("山田")
                .firstName("太郎")
                .displayName("たろう")
                .locale("ja")
                .timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .isSearchable(true)
                .birthDate(birthDate.toString())
                .birthYear(birthDate.getYear())
                .build();
        ReflectionTestUtils.setField(user, "id", childUserId);
        users.put(childUserId, user);

        ParentalConsentLinkEntity link = ParentalConsentLinkEntity.builder()
                .childUserId(childUserId)
                .parentEmail("parent@example.com")
                .tokenHash("hash-" + childUserId)
                .status(ParentalConsentLinkStatus.APPROVED)
                .expiresAt(LocalDateTime.now().plusDays(7))
                .build();
        // id 昇順のキーセットページングを模倣するため連番 UUID を採番する
        link.setId(new UUID(0L, nextLinkSeq++));
        links.add(link);
        return link;
    }

    /**
     * {@link #links} / {@link #users} を正とする「本物らしい」リポジトリのふるまいを仕込む。
     *
     * <p>取得クエリは本番と同じく (1) status=APPROVED (2) birthYear <= maxBirthYear
     * （NULL は候補に含む） (3) id > cursor の 3 条件を、id 昇順・ページサイズ上限つきで模倣する。
     * 未成年の除外は行わない（本番の SQL も行えない）。</p>
     */
    private void stubRepository() {
        lenient().when(parentalConsentLinkRepository.findAdultCandidateLinksAfterId(
                        any(), anyInt(), any(UUID.class), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    ParentalConsentLinkStatus status = invocation.getArgument(0);
                    int maxBirthYear = invocation.getArgument(1);
                    UUID cursor = invocation.getArgument(2);
                    Pageable pageable = invocation.getArgument(3);
                    return links.stream()
                            .filter(l -> l.getStatus() == status)
                            .filter(l -> l.getId().compareTo(cursor) > 0)
                            .filter(l -> {
                                UserEntity u = users.get(l.getChildUserId());
                                return u == null || u.getBirthYear() == null
                                        || u.getBirthYear() <= maxBirthYear;
                            })
                            .sorted(java.util.Comparator.comparing(ParentalConsentLinkEntity::getId))
                            .limit(pageable.getPageSize())
                            .toList();
                });

        lenient().when(userRepository.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(users.get(invocation.<Long>getArgument(0))));
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
            // given: リンクが1件も無い
            stubRepository();

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
            ParentalConsentLinkEntity link =
                    givenChildWithApprovedLink(1L, LocalDate.of(1980, 1, 1));
            stubRepository();

            // when
            releaseBatchService.execute();

            // then: リンクが REVOKED に更新されていること
            assertThat(link.getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
            assertThat(link.getRevokedBy()).isNull(); // SYSTEM による自動解放
        }

        @Test
        @DisplayName("正常_成人_メール送信される")
        void 正常_成人_メール送信される() {
            // given
            givenChildWithApprovedLink(3L, LocalDate.of(1980, 3, 20));
            stubRepository();

            // when
            releaseBatchService.execute();

            // then: メール送信が呼ばれること
            ArgumentCaptor<EmailOutboxRequest> captor = ArgumentCaptor.forClass(EmailOutboxRequest.class);
            verify(emailOutboxService).enqueue(captor.capture());

            EmailOutboxRequest request = captor.getValue();
            assertThat(request.templateKind()).isEqualTo("PARENTAL_CONSENT_RELEASED");
            assertThat(request.toAddress()).isEqualTo("child3@example.com");
            assertThat(request.sourceDomain()).isEqualTo("auth");
        }

        @Test
        @DisplayName("正常_ユーザー存在しない_スキップされる")
        void 正常_ユーザー存在しない_スキップされる() {
            // given: リンクが存在するがユーザーは参照できない
            ParentalConsentLinkEntity link =
                    givenChildWithApprovedLink(4L, LocalDate.of(1980, 1, 1));
            users.remove(4L);
            stubRepository();

            // when
            releaseBatchService.execute();

            // then: リンクのステータスは変わらず、メール送信もされない
            assertThat(link.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("AC-0-7: 誕生日当日に18歳へ到達した子のリンクが当日中にREVOKEDされ通知される")
        void 誕生日当日に18歳到達_当日中に解放される() {
            // given: 生年月日がちょうど「今日18歳になる」日の子
            LocalDate turnsAdultToday = AgeGroupCalculator.adultBirthDateThreshold(today());
            ParentalConsentLinkEntity link = givenChildWithApprovedLink(10L, turnsAdultToday);
            stubRepository();

            // when
            releaseBatchService.execute();

            // then: 当日中に解放され通知される（境界を1日でも締めすぎると翌日まで解放されない）
            assertThat(AgeGroupCalculator.isMinor(turnsAdultToday, today()))
                    .as("前提: この生年月日は本日時点で成人であること").isFalse();
            assertThat(link.getStatus()).isEqualTo(ParentalConsentLinkStatus.REVOKED);
            verify(emailOutboxService).enqueue(any());
        }

        @Test
        @DisplayName("AC-0-7: 明日18歳になる子（境界年の未成年）のリンクは解放されない")
        void 明日18歳になる子は解放されない() {
            // given: 生年月日が閾値の翌日＝本日時点ではまだ未成年。
            //        生年は成人と同じ年になり得るため SQL の粗い絞り込みでは候補に残る。
            LocalDate turnsAdultTomorrow = AgeGroupCalculator.adultBirthDateThreshold(today()).plusDays(1);
            ParentalConsentLinkEntity link = givenChildWithApprovedLink(11L, turnsAdultTomorrow);
            stubRepository();

            // when
            releaseBatchService.execute();

            // then: 未成年の保護者同意を解放してはならない
            assertThat(AgeGroupCalculator.isMinor(turnsAdultTomorrow, today()))
                    .as("前提: この生年月日は本日時点で未成年であること").isTrue();
            assertThat(link.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("生年月日が未設定・不正な子のリンクは解放されない（安全側）")
        void 生年月日が解決できない子は解放されない() {
            // given
            ParentalConsentLinkEntity link =
                    givenChildWithApprovedLink(12L, LocalDate.of(1980, 1, 1));
            ReflectionTestUtils.setField(users.get(12L), "birthDate", "not-a-date");
            stubRepository();

            // when
            releaseBatchService.execute();

            // then
            assertThat(link.getStatus()).isEqualTo(ParentalConsentLinkStatus.APPROVED);
            verify(emailOutboxService, never()).enqueue(any());
        }

        @Test
        @DisplayName("SQL へ渡す生年の上限は AgeGroupCalculator の閾値の年である")
        void 生年上限はAgeGroupCalculatorの閾値年() {
            // given
            stubRepository();

            // when
            releaseBatchService.execute();

            // then
            ArgumentCaptor<Integer> yearCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(parentalConsentLinkRepository).findAdultCandidateLinksAfterId(
                    eq(ParentalConsentLinkStatus.APPROVED), yearCaptor.capture(),
                    any(UUID.class), any(Pageable.class));
            assertThat(yearCaptor.getValue())
                    .isEqualTo(AgeGroupCalculator.adultBirthDateThreshold(today()).getYear());
        }

        @Test
        @DisplayName("AC-0-6: 先頭ページが境界年の未成年で埋まっても後方の成人到達者は解放される（飢餓しない）")
        void 先頭ページが未成年で埋まっても成人到達者は飢餓しない() {
            // given: 先頭 PAGE_SIZE 件はすべて「明日18歳になる」未成年（SQL では除外できない候補）。
            //        その後方に成人到達者を 3 人置く。
            //        カーソルを前進させない実装では先頭ページを永久に取り直し、後方へ到達できない。
            LocalDate minorBirthDate = AgeGroupCalculator.adultBirthDateThreshold(today()).plusDays(1);
            List<ParentalConsentLinkEntity> minorLinks = new ArrayList<>();
            for (long i = 1; i <= PAGE_SIZE; i++) {
                minorLinks.add(givenChildWithApprovedLink(1000L + i, minorBirthDate));
            }
            List<ParentalConsentLinkEntity> adultLinks = new ArrayList<>();
            for (long i = 1; i <= 3; i++) {
                adultLinks.add(givenChildWithApprovedLink(9000L + i, LocalDate.of(1980, 5, 5)));
            }
            stubRepository();

            // when
            releaseBatchService.execute();

            // then: 未成年は 1 件も解放されず、後方の成人到達者は全員解放される
            assertThat(minorLinks)
                    .as("境界年の未成年は解放されないこと")
                    .allMatch(l -> l.getStatus() == ParentalConsentLinkStatus.APPROVED);
            assertThat(adultLinks)
                    .as("未成年に埋もれた後方の成人到達者へ到達できること（飢餓しない）")
                    .allMatch(l -> l.getStatus() == ParentalConsentLinkStatus.REVOKED);
            verify(emailOutboxService, org.mockito.Mockito.times(3)).enqueue(any());
        }

        @Test
        @DisplayName("AC-0-6: 成人到達者がページサイズを超えても全員が同日中に解放される")
        void 成人到達者がページサイズ超でも全員解放される() {
            // given
            List<ParentalConsentLinkEntity> adultLinks = new ArrayList<>();
            for (long i = 1; i <= PAGE_SIZE + 10; i++) {
                adultLinks.add(givenChildWithApprovedLink(i, LocalDate.of(1980, 1, 1)));
            }
            stubRepository();

            // when
            releaseBatchService.execute();

            // then
            assertThat(adultLinks).allMatch(l -> l.getStatus() == ParentalConsentLinkStatus.REVOKED);
            verify(emailOutboxService, org.mockito.Mockito.times(PAGE_SIZE + 10)).enqueue(any());
        }
    }
}
