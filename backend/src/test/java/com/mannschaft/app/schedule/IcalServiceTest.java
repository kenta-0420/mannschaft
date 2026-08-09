package com.mannschaft.app.schedule;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
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

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

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

    @Mock
    private NameResolverService nameResolverService;

    @Mock
    private AccessControlService accessControlService;

    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private IcalService icalService;

    // ========================================
    // テスト用定数・ヘルパー
    // ========================================

    private static final Long USER_ID = 100L;
    private static final String TOKEN = "test-secure-token-abc123";

    @BeforeEach
    void setUp() {
        // @Value("${app.base-url}") は @InjectMocks では注入されないため ReflectionTestUtils で設定する
        ReflectionTestUtils.setField(icalService, "appBaseUrl", "http://localhost:3000");
        // CMP-017b 第五隊: filterAccessible は既定で「渡された ID を全て可視」として通す
        // （可視性判定そのものを検証するテストは個別に上書きする）。
        org.mockito.Mockito.lenient()
                .when(contentVisibilityChecker.filterAccessible(any(), any(), any()))
                .thenAnswer(inv -> {
                    java.util.Collection<Long> ids = inv.getArgument(1);
                    return new java.util.HashSet<>(ids);
                });
    }

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
    // スコープ指定フィードの認可（認可根治 Wave6 追加戦）
    // ========================================

    @Nested
    @DisplayName("スコープ指定フィードの認可")
    class FeedScopeAuthorization {

        private static final Long TEAM_ID = 500L;
        private static final Long ORG_ID = 600L;

        @Test
        @DisplayName("teamスコープ_トークン所有者が当該チームに所属していない場合は403相当")
        void teamスコープ_非所属は拒否() {
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(createActiveToken()));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER"))
                    .willReturn(false);

            assertThatThrownBy(() -> icalService.generateIcalFeed(TOKEN, "team", TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);

            verify(scheduleRepository, org.mockito.Mockito.never())
                    .findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
                            any(), any(LocalDateTime.class), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("teamスコープ_所属していれば配信される（正常系）")
        void teamスコープ_所属者は配信() {
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(createActiveToken()));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER"))
                    .willReturn(true);
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(TEAM_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(createScheduleForFeed()));

            String result = icalService.generateIcalFeed(TOKEN, "team", TEAM_ID);

            assertThat(result).contains("SUMMARY:テスト予定");
        }

        @Test
        @DisplayName("organizationスコープ_トークン所有者が当該組織に所属していない場合は403相当")
        void 組織スコープ_非所属は拒否() {
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(createActiveToken()));
            given(accessControlService.hasRoleOrAbove(USER_ID, ORG_ID, "ORGANIZATION", "SUPPORTER"))
                    .willReturn(false);

            assertThatThrownBy(() -> icalService.generateIcalFeed(TOKEN, "organization", ORG_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("ETag算出も同一のスコープ認可を課す")
        void ETag算出_非所属は拒否() {
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(createActiveToken()));
            given(accessControlService.hasRoleOrAbove(USER_ID, TEAM_ID, "TEAM", "SUPPORTER"))
                    .willReturn(false);

            assertThatThrownBy(() -> icalService.calculateETag(TOKEN, "team", TEAM_ID))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_002);
        }

        @Test
        @DisplayName("スコープ未指定_認可判定を経ずに自分のフィードを返す（正常系・非回帰）")
        void スコープ未指定_認可判定なしで配信() {
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(createActiveToken()));
            given(scheduleRepository.findByUserIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(USER_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(createScheduleForFeed()));
            given(userRoleRepository.findByUserIdAndTeamIdIsNotNull(USER_ID)).willReturn(List.of());
            given(userRoleRepository.findByUserIdAndOrganizationIdIsNotNull(USER_ID)).willReturn(List.of());

            String result = icalService.generateIcalFeed(TOKEN, null, null);

            assertThat(result).contains("SUMMARY:テスト予定");
            verify(accessControlService, org.mockito.Mockito.never())
                    .hasRoleOrAbove(any(), any(), any(), any());
        }
    }

    // ========================================
    // CMP-017b 第五隊: F00 可視性基盤連携（AC-10 / AC-11）
    // ========================================

    @Nested
    @DisplayName("iCalフィードのF00可視性連携（AC-10/AC-11）")
    class VisibilityFiltering {

        private ScheduleEntity scheduleWithId(long id, String title) {
            ScheduleEntity s = ScheduleEntity.builder()
                    .teamId(500L)
                    .title(title)
                    .startAt(LocalDateTime.of(2026, 4, 1, 10, 0))
                    .eventType(EventType.MEETING)
                    .visibility(ScheduleVisibility.MEMBERS_ONLY)
                    .minViewRole(MinViewRole.MEMBER_PLUS)
                    .status(ScheduleStatus.SCHEDULED)
                    .build();
            ReflectionTestUtils.setField(s, "id", id);
            return s;
        }

        @Test
        @DisplayName("AC-10: min_view_role で不可視な予定はフィードから除外される（応援者にMEMBER_PLUS予定を漏らさない）")
        void 不可視な予定はフィードから除外される() {
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(createActiveToken()));
            given(accessControlService.hasRoleOrAbove(USER_ID, 500L, "TEAM", "SUPPORTER")).willReturn(true);
            ScheduleEntity hidden = scheduleWithId(1L, "MEMBER_PLUS限定予定");
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(500L), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(hidden));
            // 応援者には不可視（filterAccessible が空集合を返す）。
            // doReturn().when(...) を使う: given()/when() は再登録時に既存の lenient デフォルト
            // Answer（setUp のもの）を実行してしまい ids=null で NPE になるため回避する。
            org.mockito.Mockito.doReturn(java.util.Set.of())
                    .when(contentVisibilityChecker).filterAccessible(any(), any(), any());

            String result = icalService.generateIcalFeed(TOKEN, "team", 500L);

            assertThat(result).doesNotContain("BEGIN:VEVENT");
            assertThat(result).doesNotContain("MEMBER_PLUS限定予定");
        }

        @Test
        @DisplayName("塞ぎすぎていない: SUPPORTER_PLUS 予定は可視なら通常どおりフィードに含まれる")
        void 可視な予定はフィードに含まれる() {
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(createActiveToken()));
            given(accessControlService.hasRoleOrAbove(USER_ID, 500L, "TEAM", "SUPPORTER")).willReturn(true);
            ScheduleEntity visible = scheduleWithId(2L, "SUPPORTER_PLUS予定");
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(500L), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(visible));
            org.mockito.Mockito.doReturn(java.util.Set.of(2L))
                    .when(contentVisibilityChecker).filterAccessible(any(), any(), any());

            String result = icalService.generateIcalFeed(TOKEN, "team", 500L);

            assertThat(result).contains("BEGIN:VEVENT");
            assertThat(result).contains("SUPPORTER_PLUS予定");
        }

        @Test
        @DisplayName("AC-11: ETag は可視性フィルタ後の件数から算出される（不可視予定の件数漏洩を防ぐ）")
        void ETagはフィルタ後の件数から算出される() {
            given(icalTokenRepository.findByToken(TOKEN)).willReturn(Optional.of(createActiveToken()));
            given(accessControlService.hasRoleOrAbove(USER_ID, 500L, "TEAM", "SUPPORTER")).willReturn(true);
            ScheduleEntity visible = scheduleWithId(3L, "可視予定");
            ScheduleEntity hidden = scheduleWithId(4L, "不可視予定");
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(500L), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(visible, hidden));
            // id=3 のみ可視。
            org.mockito.Mockito.doReturn(java.util.Set.of(3L))
                    .when(contentVisibilityChecker).filterAccessible(any(), any(), any());

            String etagWithTwoRawButOneVisible = icalService.calculateETag(TOKEN, "team", 500L);

            // 比較対象: 可視な予定が1件だけ最初から存在した場合の ETag と一致するはず
            // （= 不可視な予定の有無が ETag に影響しない）。
            org.mockito.Mockito.reset(scheduleRepository, contentVisibilityChecker);
            given(scheduleRepository.findByTeamIdAndStartAtBetweenOrderByStartAtAsc(
                    eq(500L), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .willReturn(List.of(visible));
            org.mockito.Mockito.doReturn(java.util.Set.of(3L))
                    .when(contentVisibilityChecker).filterAccessible(any(), any(), any());

            String etagWithOnlyVisible = icalService.calculateETag(TOKEN, "team", 500L);

            assertThat(etagWithTwoRawButOneVisible).isEqualTo(etagWithOnlyVisible);
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

    // ========================================
    // buildWebcalUrl
    // ========================================

    @Nested
    @DisplayName("buildWebcalUrl")
    class BuildWebcalUrl {

        @Test
        @DisplayName("https:// → webcal:// に変換される")
        void httpsSchemeConvertedToWebcal() {
            String result = icalService.buildWebcalUrl("https://app.example.com/ical/abc.ics");
            assertThat(result).isEqualTo("webcal://app.example.com/ical/abc.ics");
        }

        @Test
        @DisplayName("http://localhost:3000 → webcal://localhost:3000 に変換される")
        void httpLocalhostConvertedToWebcal() {
            String result = icalService.buildWebcalUrl("http://localhost:3000/ical/abc.ics");
            assertThat(result).isEqualTo("webcal://localhost:3000/ical/abc.ics");
        }

        @Test
        @DisplayName("null → null を返す")
        void nullReturnsNull() {
            assertThat(icalService.buildWebcalUrl(null)).isNull();
        }
    }
}
