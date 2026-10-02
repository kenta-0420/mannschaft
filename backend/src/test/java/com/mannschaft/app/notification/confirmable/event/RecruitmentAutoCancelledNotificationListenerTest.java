package com.mannschaft.app.notification.confirmable.event;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.common.SystemUsers;
import com.mannschaft.app.membership.ScopeType;
import com.mannschaft.app.notification.confirmable.repository.ConfirmableNotificationRepository;
import com.mannschaft.app.notification.confirmable.service.ConfirmableNotificationService;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.event.RecruitmentAutoCancelledNotificationEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * CMP-260930-1932 AC-5 / AC-6: 自動キャンセル通知リスナー（新設）の単体テスト。
 *
 * <p>{@link RecruitmentPenaltyAppliedNotificationListenerTest} と同型。送信の中身
 * （createdBy=SYSTEM_USER_ID・sourceType・sourceId・スコープ写像）と冪等スキップ、
 * 失敗時の ERROR ログを固定する。並行2回発火の一意性は DB 制約を伴うため IT
 * （{@code RecruitmentAutoCancelNotificationTransactionIT}）で検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CMP-260930-1932 AC-5/AC-6: RecruitmentAutoCancelledNotificationListener 単体テスト")
class RecruitmentAutoCancelledNotificationListenerTest {

    private static final Long LISTING_ID = 555L;

    @Mock
    private ConfirmableNotificationService confirmableNotificationService;

    @Mock
    private ConfirmableNotificationRepository confirmableNotificationRepository;

    @InjectMocks
    private RecruitmentAutoCancelledNotificationListener listener;

    private Logger listenerLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        listenerLogger = (Logger) LoggerFactory.getLogger(RecruitmentAutoCancelledNotificationListener.class);
        appender = new ListAppender<>();
        appender.start();
        listenerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        listenerLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("AC-5: TEAM 募集の自動キャンセル → sourceType=RECRUITMENT_AUTO_CANCEL・sourceId=listingId・"
            + "createdBy=SYSTEM_USER_ID・TEAM スコープで参加者全員へ sendFromSource する")
    void AC5_チーム募集はシステムユーザー発でRECRUITMENT_AUTO_CANCELとして送る() {
        listener.onAutoCancelled(new RecruitmentAutoCancelledNotificationEvent(
                LISTING_ID, RecruitmentScopeType.TEAM, 77L, List.of(1L, 2L)));

        verify(confirmableNotificationService, times(1)).sendFromSource(
                eq("RECRUITMENT_AUTO_CANCEL"), eq(LISTING_ID), eq(ScopeType.TEAM), eq(77L),
                any(), any(), any(), any(), any(), eq(SystemUsers.SYSTEM_USER_ID), eq(List.of(1L, 2L)));
    }

    @Test
    @DisplayName("AC-5: ORG 募集 → ORGANIZATION スコープで送る")
    void AC5_組織募集は組織スコープで送る() {
        listener.onAutoCancelled(new RecruitmentAutoCancelledNotificationEvent(
                LISTING_ID, RecruitmentScopeType.ORGANIZATION, 88L, List.of(3L)));

        verify(confirmableNotificationService, times(1)).sendFromSource(
                eq("RECRUITMENT_AUTO_CANCEL"), eq(LISTING_ID), eq(ScopeType.ORGANIZATION), eq(88L),
                any(), any(), any(), any(), any(), eq(SystemUsers.SYSTEM_USER_ID), eq(List.of(3L)));
    }

    @Test
    @DisplayName("AC-5: PERSONAL 募集 → PLATFORM スコープへ写像して送る（既存バッチの写像を維持）")
    void AC5_個人募集はPLATFORMスコープへ写像して送る() {
        listener.onAutoCancelled(new RecruitmentAutoCancelledNotificationEvent(
                LISTING_ID, RecruitmentScopeType.PERSONAL, 99L, List.of(4L)));

        verify(confirmableNotificationService, times(1)).sendFromSource(
                eq("RECRUITMENT_AUTO_CANCEL"), eq(LISTING_ID), eq(ScopeType.PLATFORM), eq(99L),
                any(), any(), any(), any(), any(), eq(SystemUsers.SYSTEM_USER_ID), eq(List.of(4L)));
    }

    @Test
    @DisplayName("ALIC-1: actionUrl は /notifications を指す"
            + "（AUTO_CANCELLED の募集は RecruitmentListingStatusMapper で ARCHIVED に写像され"
            + "SystemAdmin 以外は /recruitment-listings/{id} へ到達できないため、"
            + "ペナルティ通知と同じく受信者が開ける通知一覧へ誘導する）")
    void ALIC1_actionUrlは通知一覧ページを指す() {
        listener.onAutoCancelled(new RecruitmentAutoCancelledNotificationEvent(
                LISTING_ID, RecruitmentScopeType.TEAM, 77L, List.of(1L, 2L)));

        verify(confirmableNotificationService, times(1)).sendFromSource(
                any(), any(), any(), any(), any(), any(), any(), any(),
                eq("/notifications"), any(), any());
    }

    @Test
    @DisplayName("AC-5: 同一 listing の通知が既にあれば再送しない（existsBySourceTypeAndSourceId で冪等）")
    void AC5_既に送信済みなら再送しない() {
        given(confirmableNotificationRepository.existsBySourceTypeAndSourceId("RECRUITMENT_AUTO_CANCEL", LISTING_ID))
                .willReturn(true);

        listener.onAutoCancelled(new RecruitmentAutoCancelledNotificationEvent(
                LISTING_ID, RecruitmentScopeType.TEAM, 77L, List.of(1L)));

        // 先に冪等確認を実際に行っていること（骨格の「何もしない」で偽 green にしない）
        verify(confirmableNotificationRepository).existsBySourceTypeAndSourceId("RECRUITMENT_AUTO_CANCEL", LISTING_ID);
        verifyNoInteractions(confirmableNotificationService);
    }

    @Test
    @DisplayName("AC-6: 送信が任意の RuntimeException を投げても伝播させず、listingId 入りの ERROR ログを1行出す")
    void AC6_送信失敗はERRORログ1行で伝播しない() {
        given(confirmableNotificationService.sendFromSource(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .willThrow(new IllegalStateException("模擬送信失敗"));

        assertThatCode(() -> listener.onAutoCancelled(new RecruitmentAutoCancelledNotificationEvent(
                LISTING_ID, RecruitmentScopeType.ORGANIZATION, 88L, List.of(3L))))
                .doesNotThrowAnyException();

        List<ILoggingEvent> errors = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR)
                .toList();
        assertThat(errors)
                .as("AC-6: 通知失敗は ERROR ログ1行で可視化する（WARN や握りつぶしは不可）")
                .hasSize(1);
        assertThat(errors.get(0).getFormattedMessage())
                .as("AC-6: ERROR ログに listingId が入っている")
                .contains(String.valueOf(LISTING_ID));
    }
}
