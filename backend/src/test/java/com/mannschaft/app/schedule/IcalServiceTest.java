package com.mannschaft.app.schedule;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.schedule.entity.ScheduleEntity;
import com.mannschaft.app.schedule.entity.UserIcalTokenEntity;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserIcalTokenRepository;
import com.mannschaft.app.schedule.service.IcalService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * {@link IcalService} の単体テスト。
 * iCalトークン管理・フィード生成・トークン再生成・削除を検証する。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("IcalService 単体テスト")
class IcalServiceTest {

    @Mock
    private UserIcalTokenRepository icalTokenRepository;

    @Mock
    private ScheduleRepository scheduleRepository;

    @Mock
    private UserRoleRepository userRoleRepository;

    @InjectMocks
    private IcalService icalService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 100L;
    private static final String TOKEN = "test-secure-token-abc123";

    private UserIcalTokenEntity createActiveToken() {
        return UserIcalTokenEntity.builder()
                .userId(USER_ID)
                .token(TOKEN)
                .isActive(true)
                .build();
    }

    private ScheduleEntity createScheduleForFeed() {
        return ScheduleEntity.builder()
                .userId(USER_ID)
                .title("テスト予定")
                .description("説明文")
                .location("会議室A")
                .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                .endAt(LocalDateTime.of(2026, 4, 1, 12, 0))
                .allDay(false)
                .eventType(EventType.MEETING)
                .visibility(ScheduleVisibility.MEMBERS_ONLY)
                .minViewRole(MinViewRole.MEMBER_PLUS)
                .status(ScheduleStatus.SCHEDULED)
                .isException(false)
                .build();
    }

    // ========================================
    // getOrCreateToken
    // ========================================

    @Nested
    @DisplayName("getOrCreateToken")
    class GetOrCreateToken {

        @Test
        @DisplayName("トークン取得_既存あり_既存トークンを返す")
        void トークン取得_既存あり_既存トークンを返す() {
            // given
            UserIcalTokenEntity existing = createActiveToken();
            given(icalTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(existing));
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            // when
            var result = icalService.getOrCreateToken(USER_ID);

            // then
            assertThat(result.getToken()).isEqualTo(TOKEN);
            assertThat(result.isActive()).isTrue();
        }

        @Test
        @DisplayName("トークン取得_未発行_新規生成される")
        void トークン取得_未発行_新規生成される() {
            // given
            given(icalTokenRepository.findByUserId(USER_ID))
                    .willReturn(Optional.empty())  // 初回: 未発行
                    .willReturn(Optional.of(createActiveToken()));  // insert後

            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            // when
            var result = icalService.getOrCreateToken(USER_ID);

            // then
            verify(icalTokenRepository).insert(eq(USER_ID), any(String.class), eq(true));
            assertThat(result.getToken()).isEqualTo(TOKEN);
        }
    }

    // ========================================
    // regenerateToken
    // ========================================

    @Nested
    @DisplayName("regenerateToken")
    class RegenerateToken {

        @Test
        @DisplayName("トークン再生成_正常_新しいトークンで置き換えられる")
        void トークン再生成_正常_新しいトークンで置き換えられる() {
            // given
            given(icalTokenRepository.findByUserId(USER_ID))
                    .willReturn(Optional.of(createActiveToken()))
                    .willReturn(Optional.of(createActiveToken()));
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            // when
            icalService.regenerateToken(USER_ID);

            // then
            verify(icalTokenRepository).updateToken(eq(USER_ID), any(String.class));
        }

        @Test
        @DisplayName("トークン再生成_トークン不在_例外スロー")
        void トークン再生成_トークン不在_例外スロー() {
            // given
            given(icalTokenRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> icalService.regenerateToken(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.ICAL_TOKEN_NOT_FOUND);
        }
    }

    // ========================================
    // deleteToken
    // ========================================

    @Nested
    @DisplayName("deleteToken")
    class DeleteToken {

        @Test
        @DisplayName("トークン削除_正常_削除される")
        void トークン削除_正常_削除される() {
            // given
            given(icalTokenRepository.findByUserId(USER_ID)).willReturn(Optional.of(createActiveToken()));

            // when
            icalService.deleteToken(USER_ID);

            // then
            verify(icalTokenRepository).deleteByUserId(USER_ID);
        }

        @Test
        @DisplayName("トークン削除_トークン不在_例外スロー")
        void トークン削除_トークン不在_例外スロー() {
            // given
            given(icalTokenRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> icalService.deleteToken(USER_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.ICAL_TOKEN_NOT_FOUND);
        }
    }

    // ========================================
    // generateIcalFeed
    // ========================================

    @Nested
    @DisplayName("generateIcalFeed")
    class GenerateIcalFeed {

        @Test
        @DisplayName("フィード生成_個人スコープ_VCALENDAR文字列を返す")
        void フィード生成_個人スコープ_VCALENDAR文字列を返す() {
            // given
            UserIcalTokenEntity tokenEntity = createActiveToken();
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(tokenEntity));

            ScheduleEntity schedule = createScheduleForFeed();
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(schedule));

            // when
            String result = icalService.generateIcalFeed(TOKEN, "personal", null);

            // then
            assertThat(result).startsWith("BEGIN:VCALENDAR");
            assertThat(result).contains("BEGIN:VEVENT");
            assertThat(result).contains("SUMMARY:テスト予定");
            assertThat(result).contains("LOCATION:会議室A");
            assertThat(result).endsWith("END:VCALENDAR\r\n");
        }

        @Test
        @DisplayName("フィード生成_無効トークン_例外スロー")
        void フィード生成_無効トークン_例外スロー() {
            // given
            given(icalTokenRepository.findByToken("invalid-token")).willReturn(Optional.empty());

            // when & then
            assertThatThrownBy(() -> icalService.generateIcalFeed("invalid-token", null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.ICAL_TOKEN_INVALID);
        }

        @Test
        @DisplayName("フィード生成_非アクティブトークン_例外スロー")
        void フィード生成_非アクティブトークン_例外スロー() {
            // given
            UserIcalTokenEntity inactiveToken = UserIcalTokenEntity.builder()
                    .userId(USER_ID)
                    .token(TOKEN)
                    .isActive(false)
                    .build();
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(inactiveToken));

            // when & then
            assertThatThrownBy(() -> icalService.generateIcalFeed(TOKEN, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(GoogleCalendarErrorCode.ICAL_TOKEN_INVALID);
        }

        @Test
        @DisplayName("フィード生成_スケジュールなし_空のVCALENDARを返す")
        void フィード生成_スケジュールなし_空のVCALENDARを返す() {
            // given
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(createActiveToken()));
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of());

            // when
            String result = icalService.generateIcalFeed(TOKEN, "personal", null);

            // then
            assertThat(result).startsWith("BEGIN:VCALENDAR");
            assertThat(result).doesNotContain("BEGIN:VEVENT");
            assertThat(result).endsWith("END:VCALENDAR\r\n");
        }
    }

    // ========================================
    // recordPoll
    // ========================================

    @Nested
    @DisplayName("recordPoll")
    class RecordPoll {

        @Test
        @DisplayName("ポーリング記録_正常_更新される")
        void ポーリング記録_正常_更新される() {
            // when
            icalService.recordPoll(TOKEN);

            // then
            verify(icalTokenRepository).updateLastPolledAt(eq(TOKEN), any(LocalDateTime.class));
        }
    }
}
