package com.mannschaft.app.schedule.service;

import com.mannschaft.app.common.EncryptionService;
import com.mannschaft.app.schedule.repository.GoogleCalendarWebhookChannelRepository;
import com.mannschaft.app.schedule.repository.ScheduleRepository;
import com.mannschaft.app.schedule.repository.UserGoogleCalendarConnectionRepository;
import com.mannschaft.app.schedule.repository.UserScheduleGoogleEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link GoogleCalendarWebhookService#parseRfc3339DateTime(String)} のリファクタリング等価性テスト。
 *
 * <p>Issue #2508 Phase 3: {@code ZoneOffset.ofHours(9)} 直書きを
 * {@link com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser#SERVER_ZONE} 経由に
 * 置き換えた。挙動を変えないリファクタリングであることを、複数オフセットの入力が
 * 同一の瞬間を指す JST 壁時計に変換されることで検証する。</p>
 */
@ExtendWith(MockitoExtension.class)
class GoogleCalendarWebhookServiceRfc3339ParseTest {

    @Mock
    private GoogleCalendarWebhookChannelRepository webhookChannelRepository;
    @Mock
    private UserGoogleCalendarConnectionRepository connectionRepository;
    @Mock
    private UserScheduleGoogleEventRepository googleEventRepository;
    @Mock
    private ScheduleRepository scheduleRepository;
    @Mock
    private GoogleApiClient googleApiClient;
    @Mock
    private EncryptionService encryptionService;

    @InjectMocks
    private GoogleCalendarWebhookService service;

    private LocalDateTime invokeParse(String rfc3339) throws Exception {
        Method method = GoogleCalendarWebhookService.class
                .getDeclaredMethod("parseRfc3339DateTime", String.class);
        method.setAccessible(true);
        return (LocalDateTime) method.invoke(service, rfc3339);
    }

    @ParameterizedTest(name = "{0} は同一瞬間の JST 壁時計 {1} に変換される")
    @DisplayName("オフセット付き RFC3339 入力は同一の瞬間を指す JST 壁時計に落ちる")
    @CsvSource({
            // input (RFC3339),                 expected JST wall clock
            "2026-08-01T10:00:00-07:00, 2026-08-02T02:00:00",
            "2026-08-01T10:00:00+00:00, 2026-08-01T19:00:00",
            "2026-08-01T10:00:00+09:00, 2026-08-01T10:00:00",
    })
    void parseRfc3339DateTime_convertsToServerZoneWallClock(String input, String expected) throws Exception {
        LocalDateTime actual = invokeParse(input);
        assertThat(actual).isEqualTo(LocalDateTime.parse(expected));
    }

    @Test
    @DisplayName("パース失敗時は先頭19文字をフォールバックパースする（従来挙動維持）")
    void parseRfc3339DateTime_fallsBackOnParseFailure() throws Exception {
        LocalDateTime actual = invokeParse("2026-08-01T10:00:00INVALID_SUFFIX");
        assertThat(actual).isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 0, 0));
    }
}
