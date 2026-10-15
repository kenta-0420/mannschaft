package com.mannschaft.app.recruitment.event;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.NotificationScopeType;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.recruitment.RecruitmentListingStatus;
import com.mannschaft.app.recruitment.RecruitmentParticipationType;
import com.mannschaft.app.recruitment.RecruitmentScopeType;
import com.mannschaft.app.recruitment.RecruitmentVisibility;
import com.mannschaft.app.recruitment.entity.RecruitmentListingEntity;
import com.mannschaft.app.recruitment.repository.RecruitmentListingRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * {@link RecruitmentNotificationListener} の単体テスト（Issue #2997 G2）。
 *
 * <p>旧 {@code RecruitmentListingServiceTest#confirmApplication_localeAware...}
 * （受信者 locale に従って RECRUITMENT_CONFIRMED の件名・本文が切り替わる検証）の移設先を含む。
 * 文面の組み立てが業務 Service からリスナーへ移ったため、locale 検証もここで行う。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RecruitmentNotificationListener 単体テスト")
class RecruitmentNotificationListenerTest {

    private static final Long LISTING_ID = 200L;
    private static final Long TEAM_ID = 10L;
    private static final Long CREATOR_ID = 2L;

    @Mock private NotificationDeliveryRunner runner;
    @Mock private RecruitmentListingRepository listingRepository;
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private MessageSource messageSource;

    private RecruitmentNotificationListener listener() {
        return new RecruitmentNotificationListener(runner, listingRepository, userLocaleCache, messageSource);
    }

    private RecruitmentListingEntity listing() {
        LocalDateTime start = LocalDateTime.now().plusDays(2);
        RecruitmentListingEntity l = RecruitmentListingEntity.builder()
                .scopeType(RecruitmentScopeType.TEAM)
                .scopeId(TEAM_ID)
                .categoryId(1L)
                .title("test")
                .participationType(RecruitmentParticipationType.INDIVIDUAL)
                .startAt(start)
                .endAt(start.plusHours(2))
                .applicationDeadline(start.minusDays(1))
                .autoCancelAt(start.minusDays(1))
                .capacity(10)
                .minCapacity(1)
                .visibility(RecruitmentVisibility.SCOPE_ONLY)
                .status(RecruitmentListingStatus.OPEN)
                .createdBy(CREATOR_ID)
                .build();
        ReflectionTestUtils.setField(l, "id", LISTING_ID);
        return l;
    }

    @Test
    @DisplayName("CONFIRMED: 受信者localeに従って件名・本文が切り替わる（旧 Service テストの移設）")
    void confirmed_localeAware() {
        Long applicant = 5L;
        Locale en = Locale.forLanguageTag("en");
        given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(listing()));
        given(userLocaleCache.getLocales(List.of(applicant))).willReturn(Map.of(applicant, "en"));
        given(messageSource.getMessage(eq("notification.recruitment.confirmed.title"), any(), any(), eq(en)))
                .willReturn("Participation confirmed");
        given(messageSource.getMessage(eq("notification.recruitment.confirmed.body"), any(), any(), eq(en)))
                .willReturn("Your participation in test has been confirmed.");

        listener().onRecruitmentNotification(
                RecruitmentNotificationEvent.confirmed(LISTING_ID, applicant, 9L));

        ArgumentCaptor<NotificationDeliveryRequest> cap = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(runner).sendOne(cap.capture());
        NotificationDeliveryRequest r = cap.getValue();
        assertThat(r.recipientUserId()).isEqualTo(applicant);
        assertThat(r.notificationType()).isEqualTo("RECRUITMENT_CONFIRMED");
        assertThat(r.title()).isEqualTo("Participation confirmed");
        assertThat(r.body()).isEqualTo("Your participation in test has been confirmed.");
        assertThat(r.actorId()).isEqualTo(9L);
        assertThat(r.sourceType()).isEqualTo("RECRUITMENT_LISTING");
        assertThat(r.sourceId()).isEqualTo(LISTING_ID);
        assertThat(r.scopeType()).isEqualTo(NotificationScopeType.TEAM);
    }

    @Test
    @DisplayName("PUBLISHED: 受信者ごとに locale 別の文面で sendOne し、1人の失敗が他の受信者を止めない")
    void published_perRecipientLocale_and_failureIsolated() {
        given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(listing()));
        given(userLocaleCache.getLocales(List.of(5L, 6L))).willReturn(Map.of(5L, "en", 6L, "ja"));
        given(messageSource.getMessage(eq("notification.recruitment.published.title"), any(), any(),
                eq(Locale.forLanguageTag("en")))).willReturn("New: test");
        given(messageSource.getMessage(eq("notification.recruitment.published.title"), any(), any(),
                eq(Locale.forLanguageTag("ja")))).willReturn("新着募集: test");
        given(messageSource.getMessage(eq("notification.recruitment.published.body"), any(), any(), any()))
                .willReturn("body");
        given(runner.sendOne(any())).willThrow(new IllegalStateException("1人目の配送失敗")).willReturn(null);

        listener().onRecruitmentNotification(
                RecruitmentNotificationEvent.published(LISTING_ID, List.of(5L, 6L)));

        ArgumentCaptor<NotificationDeliveryRequest> cap = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(runner, times(2)).sendOne(cap.capture());
        assertThat(cap.getAllValues()).extracting(NotificationDeliveryRequest::title)
                .containsExactly("New: test", "新着募集: test");
        assertThat(cap.getAllValues()).extracting(NotificationDeliveryRequest::notificationType)
                .containsOnly("RECRUITMENT_PUBLISHED");
    }

    @Test
    @DisplayName("FRIEND_LISTING: MARKET_FRIEND_LISTING をフレンド宛の遷移先で送る")
    void friendListing_request() {
        given(listingRepository.findById(LISTING_ID)).willReturn(Optional.of(listing()));
        given(userLocaleCache.getLocales(List.of(7L))).willReturn(Map.of());
        given(messageSource.getMessage(any(String.class), any(), any(), any())).willReturn("x");

        listener().onRecruitmentNotification(RecruitmentNotificationEvent.friendListing(LISTING_ID, List.of(7L)));

        ArgumentCaptor<NotificationDeliveryRequest> cap = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(runner).sendOne(cap.capture());
        assertThat(cap.getValue().notificationType()).isEqualTo("MARKET_FRIEND_LISTING");
        assertThat(cap.getValue().actionUrl()).isEqualTo("/market/listings/" + LISTING_ID);
        assertThat(cap.getValue().scopeType()).isEqualTo(NotificationScopeType.TEAM);
    }

    @Test
    @DisplayName("受信者が空なら何もしない")
    void emptyRecipients_noop() {
        listener().onRecruitmentNotification(RecruitmentNotificationEvent.published(LISTING_ID, List.of()));
        verifyNoInteractions(runner, listingRepository);
    }
}
