package com.mannschaft.app.auth.guardianship;

import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.auth.repository.UserRepository;
import com.mannschaft.app.auth.service.ParentalConsentService;
import com.mannschaft.app.family.service.CareLinkService;
import com.mannschaft.app.mail.outbox.EmailOutboxRequest;
import com.mannschaft.app.mail.outbox.EmailOutboxService;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link GuardianshipProgressionNoticeBatchService} の単体テスト（F08.9 P3c-3 進学予告）。
 *
 * <p>JP ポリシーで 2013-04-02 生まれの子の封印境界日は 2026-04-01。
 * 3ヶ月前ウィンドウ [2026-01-01, 2026-04-01) の内外を Clock 固定で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("GuardianshipProgressionNoticeBatchService")
class GuardianshipProgressionNoticeBatchServiceTest {

    private static final ZoneId JST = ZoneId.of("Asia/Tokyo");
    private static final Long GUARDIAN_ID = 100L;
    private static final Long CHILD_ID = 200L;
    /** JP: 2013-04-02 生まれ → 封印境界日 2026-04-01。 */
    private static final String CHILD_BIRTH = "2013-04-02";
    private static final LocalDate SEAL_DATE = LocalDate.of(2026, 4, 1);

    @Mock private ParentalConsentService parentalConsentService;
    @Mock private CareLinkService careLinkService;
    @Mock private UserRepository userRepository;
    @Mock private GuardianshipTransitionNotificationRepository transitionNotificationRepository;
    @Mock private NotificationHelper notificationHelper;
    @Mock private EmailOutboxService emailOutboxService;
    @Mock private MessageSource messageSource;

    private GuardianshipProgressionNoticeBatchService newService(LocalDate today) {
        Clock clock = Clock.fixed(today.atStartOfDay(JST).toInstant(), JST);
        // JP ポリシーを実体で使う（境界日算出の整合を実ロジックで検証）。
        GuardianshipAgePolicyRegistry registry = new GuardianshipAgePolicyRegistry(
                List.of(new JapanGuardianshipAgePolicy()), new DefaultGuardianshipAgePolicy());
        return new GuardianshipProgressionNoticeBatchService(
                parentalConsentService, careLinkService, userRepository, registry,
                transitionNotificationRepository, notificationHelper, emailOutboxService,
                messageSource, clock);
    }

    @BeforeEach
    void stubMessages() {
        lenient().when(messageSource.getMessage(eq("notification.guardianship.progression.title"),
                any(), any(), any(Locale.class))).thenReturn("予告タイトル");
        lenient().when(messageSource.getMessage(eq("notification.guardianship.progression.body"),
                any(), any(), any(Locale.class))).thenReturn("予告本文");
    }

    private UserEntity user(Long id, String birthDate, String email, String passwordHash) {
        UserEntity u = UserEntity.builder()
                .email(email)
                .lastName("山田").firstName("花子").displayName("はなこ")
                .locale("ja").timezone("Asia/Tokyo")
                .status(UserEntity.UserStatus.ACTIVE)
                .countryCode("JP")
                .birthDate(birthDate)
                .passwordHash(passwordHash)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @Test
    @DisplayName("3ヶ月前ちょうど（ウィンドウ開始日）は送信する")
    void sendsAtWindowStart() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));
        when(userRepository.findById(GUARDIAN_ID))
                .thenReturn(java.util.Optional.of(user(GUARDIAN_ID, "1980-01-01", "parent@example.com", "h")));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);

        // ウィンドウ開始 = 2026-01-01（2026-04-01 の3ヶ月前）
        newService(LocalDate.of(2026, 1, 1)).execute();

        verify(notificationHelper).notify(eq(GUARDIAN_ID), eq("GUARDIANSHIP_PROGRESSION_NOTICE"),
                any(), any(), eq("GUARDIANSHIP_PROGRESSION"), eq(CHILD_ID),
                eq(NotificationScopeType.PERSONAL), eq(GUARDIAN_ID), any(), isNull());
        ArgumentCaptor<GuardianshipTransitionNotificationEntity> rec =
                ArgumentCaptor.forClass(GuardianshipTransitionNotificationEntity.class);
        verify(transitionNotificationRepository).save(rec.capture());
        assertThat(rec.getValue().getSealDate()).isEqualTo(SEAL_DATE);
        assertThat(rec.getValue().getNotificationKind())
                .isEqualTo(GuardianshipTransitionNotificationKind.PROGRESSION_NOTICE);
        // メールも outbox に enqueue される（ルーティング可能な保護者メール）。
        verify(emailOutboxService).enqueue(any(EmailOutboxRequest.class));
    }

    @Test
    @DisplayName("ウィンドウ開始日の前日は送信しない")
    void noSendBeforeWindow() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));

        // 2025-12-31 = ウィンドウ開始 2026-01-01 の前日
        newService(LocalDate.of(2025, 12, 31)).execute();

        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("封印境界日当日は送信しない（ウィンドウは半開区間 [start, seal)）")
    void noSendOnSealDate() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));

        newService(SEAL_DATE).execute();

        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("封印後（境界日を過ぎている）は送信しない")
    void noSendAfterSeal() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));

        newService(SEAL_DATE.plusDays(10)).execute();

        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("送信済み（既存記録あり）はスキップする")
    void skipsAlreadySent() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        eq(GuardianshipTransitionNotificationKind.PROGRESSION_NOTICE),
                        eq(GUARDIAN_ID), eq(CHILD_ID), eq(SEAL_DATE))).thenReturn(true);

        newService(LocalDate.of(2026, 2, 1)).execute();

        verify(transitionNotificationRepository, never()).save(any());
        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("並行実行で送信記録 INSERT が UNIQUE 競合（DuplicateKeyException）したら二重送信しない")
    void noDoubleSendOnUniqueConflict() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));
        when(userRepository.findById(GUARDIAN_ID))
                .thenReturn(java.util.Optional.of(user(GUARDIAN_ID, "1980-01-01", "parent@example.com", "h")));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);
        when(transitionNotificationRepository.save(any()))
                .thenThrow(new DuplicateKeyException("dup"));

        newService(LocalDate.of(2026, 2, 1)).execute();

        // 記録挿入が UNIQUE 競合で弾かれたら通知もメールも送らない（DuplicateKeyException はスキップ扱い）。
        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(emailOutboxService, never()).enqueue(any());
    }

    @Test
    @DisplayName("DuplicateKeyException 以外の整合性違反（例: FK）は重複扱いせず失敗カウントに流れ、通知は送らない")
    void otherIntegrityViolationIsFailureNotSkip() {
        UserEntity child = user(CHILD_ID, CHILD_BIRTH, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));
        when(userRepository.findById(GUARDIAN_ID))
                .thenReturn(java.util.Optional.of(user(GUARDIAN_ID, "1980-01-01", "parent@example.com", "h")));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);
        // DuplicateKeyException ではない一般の整合性違反（例: FK 違反）。
        when(transitionNotificationRepository.save(any()))
                .thenThrow(new DataIntegrityViolationException("fk violation"));

        // 例外が外側の汎用 catch で失敗カウントに流れ、バッチ全体は落ちずに完了する。
        newService(LocalDate.of(2026, 2, 1)).execute();

        // 記録保存に失敗しているので通知もメールも送られない。
        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
        verify(emailOutboxService, never()).enqueue(any());
    }

    @Test
    @DisplayName("生年月日解決不能の子はスキップする（安全側）")
    void skipsUnresolvableBirthDate() {
        UserEntity child = user(CHILD_ID, null, "child@example.com", null);
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));

        newService(LocalDate.of(2026, 2, 1)).execute();

        verify(transitionNotificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("対象ペアが無ければ何もしない")
    void noPairsNoOp() {
        when(parentalConsentService.listApprovedParentChildPairs(0, 500)).thenReturn(List.of());
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());

        newService(LocalDate.of(2026, 2, 1)).execute();

        verify(userRepository, never()).findByIdIn(any());
        verify(notificationHelper, never()).notify(anyLong(), any(), any(), any(),
                any(), any(), any(), any(), any(), any());
    }

    // --- 予告ウィンドウ境界（月末・うるう年） -------------------------------------------------

    /** private {@code isInNoticeWindow(today, sealDate)} を反射で呼ぶ（構築不能な sealDate を直接検証するため）。 */
    private boolean inWindow(LocalDate today, LocalDate sealDate) {
        Boolean r = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                newService(today), "isInNoticeWindow", today, sealDate);
        return Boolean.TRUE.equals(r);
    }

    @Test
    @DisplayName("うるう日 sealDate=2028-02-29 の3ヶ月前窓 [2027-11-29, 2028-02-29) の両端を検証する")
    void noticeWindowLeapDay() {
        LocalDate seal = LocalDate.of(2028, 2, 29); // うるう日
        LocalDate windowStart = LocalDate.of(2027, 11, 29); // 2028-02-29 の3ヶ月前
        // 窓開始日の前日は対象外。
        assertThat(inWindow(windowStart.minusDays(1), seal)).isFalse();
        // 窓開始日（含む）。
        assertThat(inWindow(windowStart, seal)).isTrue();
        // 封印境界日の前日（含む・窓内）。
        assertThat(inWindow(seal.minusDays(1), seal)).isTrue();
        // 封印境界日当日は対象外（半開区間 [start, seal)）。
        assertThat(inWindow(seal, seal)).isFalse();
    }

    @Test
    @DisplayName("月末 sealDate=2026-05-31 は minusMonths(3) が月末調整され窓開始 2026-02-28 になる")
    void noticeWindowMonthEnd() {
        LocalDate seal = LocalDate.of(2026, 5, 31);
        LocalDate windowStart = LocalDate.of(2026, 2, 28); // 5-31 の3ヶ月前は 2-28 に丸められる
        assertThat(seal.minusMonths(3)).isEqualTo(windowStart);
        // 窓開始日の前日（2026-02-27）は対象外。
        assertThat(inWindow(LocalDate.of(2026, 2, 27), seal)).isFalse();
        // 窓開始日 2026-02-28（含む）。
        assertThat(inWindow(windowStart, seal)).isTrue();
        // 封印境界日の前日（含む）。
        assertThat(inWindow(seal.minusDays(1), seal)).isTrue();
        // 封印境界日当日は対象外。
        assertThat(inWindow(seal, seal)).isFalse();
    }

    @Test
    @DisplayName("フォールバック圏（誕生日基準）の子で月末 sealDate を execute 経由で検証する")
    void monthEndSealViaExecuteSends() {
        // フォールバックポリシー（未対応国＝CC 指定無し）で 2013-05-31 生まれ → sealDate=2026-05-31。
        UserEntity child = user(CHILD_ID, "2013-05-31", "child@example.com", null);
        org.springframework.test.util.ReflectionTestUtils.setField(child, "countryCode", "ZZ"); // 未対応国→フォールバック
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(child));
        when(userRepository.findById(GUARDIAN_ID))
                .thenReturn(java.util.Optional.of(user(GUARDIAN_ID, "1980-01-01", "parent@example.com", "h")));
        when(transitionNotificationRepository
                .existsByNotificationKindAndRecipientUserIdAndChildUserIdAndSealDate(
                        any(), any(), any(), any())).thenReturn(false);

        // 窓開始日 2026-02-28 ちょうど → 送信される。
        newService(LocalDate.of(2026, 2, 28)).execute();

        ArgumentCaptor<GuardianshipTransitionNotificationEntity> rec =
                ArgumentCaptor.forClass(GuardianshipTransitionNotificationEntity.class);
        verify(transitionNotificationRepository).save(rec.capture());
        assertThat(rec.getValue().getSealDate()).isEqualTo(LocalDate.of(2026, 5, 31));
    }

    // --- ページ上限打ち切りの検知（collectPairs） ---------------------------------------------

    /** private {@code collectPairs(pairKeys, guardiansByChild, today)} を反射で呼ぶ。 */
    private boolean invokeCollectPairs(LocalDate today) {
        Boolean r = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                newService(today), "collectPairs",
                new java.util.LinkedHashSet<String>(), new java.util.LinkedHashMap<Long, java.util.Set<Long>>(), today);
        return Boolean.TRUE.equals(r);
    }

    /** {@code PAGE_SIZE} 件ちょうどのペアリストを 1 ページ分作る（parentUserId/childUserId はページ番号でずらし衝突回避）。 */
    private List<ParentalConsentService.ParentChildPair> fullConsentPage(int page) {
        List<ParentalConsentService.ParentChildPair> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < GuardianshipProgressionNoticeBatchService.PAGE_SIZE; i++) {
            long seed = (long) page * GuardianshipProgressionNoticeBatchService.PAGE_SIZE + i + 1;
            pairs.add(new ParentalConsentService.ParentChildPair(seed, seed + 1_000_000L));
        }
        return pairs;
    }

    private List<CareLinkService.ParentChildPair> fullCareLinkPage(int page) {
        List<CareLinkService.ParentChildPair> pairs = new java.util.ArrayList<>();
        for (int i = 0; i < GuardianshipProgressionNoticeBatchService.PAGE_SIZE; i++) {
            long seed = (long) page * GuardianshipProgressionNoticeBatchService.PAGE_SIZE + i + 1;
            pairs.add(new CareLinkService.ParentChildPair(seed, seed + 2_000_000L));
        }
        return pairs;
    }

    @Test
    @DisplayName("通常ケース（ページ上限未到達）では打ち切りを検知しない")
    void collectPairsNotTruncatedWhenUnderPageLimit() {
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(List.of(new ParentalConsentService.ParentChildPair(GUARDIAN_ID, CHILD_ID)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());

        boolean truncated = invokeCollectPairs(LocalDate.of(2026, 2, 1));

        assertThat(truncated).isFalse();
    }

    @Test
    @DisplayName("常に PAGE_SIZE 件を返し続けると MAX_PAGES で打ち切られ、取りこぼしを検知する")
    void collectPairsTruncatedWhenAlwaysFullPage() {
        when(parentalConsentService.listApprovedParentChildPairs(anyInt(), eq(500)))
                .thenAnswer(inv -> fullConsentPage(inv.getArgument(0)));
        when(careLinkService.listActiveParentWatcherPairs(0, 500)).thenReturn(List.of());

        boolean truncated = invokeCollectPairs(LocalDate.of(2026, 2, 1));

        assertThat(truncated).isTrue();
        verify(parentalConsentService, org.mockito.Mockito.times(GuardianshipProgressionNoticeBatchService.MAX_PAGES))
                .listApprovedParentChildPairs(anyInt(), eq(500));
    }

    @Test
    @DisplayName("ちょうど PAGE_SIZE の倍数で終わる境界ケース（最終ページが満杯・次が空）では誤検知しない")
    void collectPairsNotTruncatedWhenExactPageSizeMultiple() {
        // 1ページ目は PAGE_SIZE 件ちょうど、2ページ目は空 → 正常終了（打ち切りではない）。
        when(parentalConsentService.listApprovedParentChildPairs(0, 500))
                .thenReturn(fullConsentPage(0));
        when(parentalConsentService.listApprovedParentChildPairs(1, 500))
                .thenReturn(List.of());
        when(careLinkService.listActiveParentWatcherPairs(anyInt(), eq(500)))
                .thenAnswer(inv -> {
                    int page = inv.getArgument(0);
                    return page == 0 ? fullCareLinkPage(0) : List.of();
                });

        boolean truncated = invokeCollectPairs(LocalDate.of(2026, 2, 1));

        assertThat(truncated).isFalse();
    }
}
