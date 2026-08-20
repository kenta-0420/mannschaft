package com.mannschaft.app.notification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationBulkFanoutService;
import com.mannschaft.app.notification.service.NotificationHelper;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * {@link NotificationHelper#notifyAllPreAuthorizedLocalized} の fan-out 経路回帰ガード（Codex 検分是正・PR #2873）。
 *
 * <p>当初実装は受信者ごとに {@code notifyPreAuthorized}（1 件 1 save）を呼ぶループで、
 * fan-out 抜本改修 P1（{@link NotificationBulkFanoutService#insertAndDispatchChunk} によるチャンク単位
 * バルク INSERT）から外れていた。本テストは、受信者を locale ごとにグループ化し、
 * <b>受信者数ではなく locale 数ぶん</b>の {@code insertAndDispatchChunk} 呼び出しになることを固定する
 * （退行の再発を止める番人）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationHelper#notifyAllPreAuthorizedLocalized のバルク fan-out 経路")
class NotificationHelperLocalizedFanoutTest {

    @Mock
    private NotificationBulkFanoutService bulkFanoutService;

    @Mock
    private UserLocaleCache userLocaleCache;

    @InjectMocks
    private NotificationHelper notificationHelper;

    /**
     * Codex 三巡目是正（PR #2873）: {@code notifyAllPreAuthorized} がチャンク失敗を握って
     * 正常 return する best-effort 契約であることを踏まえ、「例外が飛ばなかった＝成功」と
     * 誤集計していないかをログ出力で検証するための appender。
     */
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void attachLogAppender() {
        logAppender = new ListAppender<>();
        logAppender.start();
        ((Logger) LoggerFactory.getLogger(NotificationHelper.class)).addAppender(logAppender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(NotificationHelper.class)).detachAppender(logAppender);
    }

    @Test
    @DisplayName("複数localeの受信者100件でも、insertAndDispatchChunk呼び出しはlocale数（3回）に留まる")
    void 複数locale受信者はlocale数ぶんのバルク呼び出しに畳まれる() {
        // given: 100 人の受信者を 3 locale（ja/en/zh）に振り分ける。
        List<Long> recipients = LongStream.rangeClosed(1, 100).boxed().toList();
        Map<Long, String> locales = new HashMap<>();
        for (Long userId : recipients) {
            String tag = switch ((int) (userId % 3)) {
                case 0 -> "ja";
                case 1 -> "en";
                default -> "zh";
            };
            locales.put(userId, tag);
        }
        given(userLocaleCache.getLocales(recipients)).willReturn(locales);

        // when
        notificationHelper.notifyAllPreAuthorizedLocalized(
                recipients,
                "SURVEY_CREATED",
                "SURVEY", 1L,
                NotificationScopeType.ORGANIZATION, 2L,
                "/surveys/1", 3L,
                (userId, locale) -> new NotificationHelper.LocalizedMessage(
                        "タイトル(" + locale.toLanguageTag() + ")", "本文(" + locale.toLanguageTag() + ")"));

        // then: 受信者数（100件）ぶんの個別呼び出しではなく、locale 数（3件）ぶんのバルク呼び出しになる。
        verify(bulkFanoutService, times(3)).insertAndDispatchChunk(
                anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(bulkFanoutService, never()).insertAndDispatchChunk(
                argThatSingleRecipient(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("各localeグループの受信者集合が正しく渡される")
    void localeグループごとに正しい受信者集合が渡る() {
        // given
        List<Long> jaUsers = List.of(1L, 2L);
        List<Long> enUsers = List.of(3L);
        List<Long> recipients = List.of(1L, 2L, 3L);
        Map<Long, String> locales = Map.of(1L, "ja", 2L, "ja", 3L, "en");
        given(userLocaleCache.getLocales(recipients)).willReturn(locales);

        // when
        notificationHelper.notifyAllPreAuthorizedLocalized(
                recipients,
                "SURVEY_CREATED",
                "SURVEY", 1L,
                NotificationScopeType.ORGANIZATION, 2L,
                "/surveys/1", 3L,
                (userId, locale) -> new NotificationHelper.LocalizedMessage("t", "b"));

        // then
        verify(bulkFanoutService, times(1)).insertAndDispatchChunk(
                argThatEquals(jaUsers), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(bulkFanoutService, times(1)).insertAndDispatchChunk(
                argThatEquals(enUsers), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("特定localeのbodyBuilderが例外を投げても伝播せず、残りlocaleグループへは配信が継続する（Codex再検分是正）")
    void 特定localeのbodyBuilder失敗は伝播せず残りのlocaleグループは継続する() {
        // given: ja/en/zh の3 locale。en の bodyBuilder だけ MessageFormat エラー等を模した例外を投げる。
        List<Long> jaUsers = List.of(1L);
        List<Long> enUsers = List.of(2L);
        List<Long> zhUsers = List.of(3L);
        List<Long> recipients = List.of(1L, 2L, 3L);
        Map<Long, String> locales = Map.of(1L, "ja", 2L, "en", 3L, "zh");
        given(userLocaleCache.getLocales(recipients)).willReturn(locales);

        // when / then: ①例外がメソッド外へ伝播しない
        assertThatCode(() -> notificationHelper.notifyAllPreAuthorizedLocalized(
                recipients,
                "SURVEY_CREATED",
                "SURVEY", 1L,
                NotificationScopeType.ORGANIZATION, 2L,
                "/surveys/1", 3L,
                (userId, locale) -> {
                    if ("en".equals(locale.toLanguageTag())) {
                        throw new IllegalArgumentException("simulated MessageFormat failure for en");
                    }
                    return new NotificationHelper.LocalizedMessage("t", "b");
                }))
                .as("locale グループ単位の catch により、呼び出し元（@Transactional な業務処理）を巻き添えにしない")
                .doesNotThrowAnyException();

        // ②残り2ロケール（ja/zh）への配信は実行される
        verify(bulkFanoutService, times(1)).insertAndDispatchChunk(
                argThatEquals(jaUsers), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(bulkFanoutService, times(1)).insertAndDispatchChunk(
                argThatEquals(zhUsers), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        // en グループは bodyBuilder で失敗しているため insertAndDispatchChunk まで到達しない
        verify(bulkFanoutService, never()).insertAndDispatchChunk(
                argThatEquals(enUsers), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("notifyAllPreAuthorizedはチャンク失敗件数を戻り値で返す（best-effort契約は変えない）")
    void notifyAllPreAuthorizedはチャンク失敗件数を戻り値で返す() {
        // given: insertAndDispatchChunk が例外を投げる＝配信全滅。best-effort契約どおり例外は外へ伝播しない。
        List<Long> recipients = List.of(1L, 2L, 3L);
        org.mockito.BDDMockito.willThrow(new RuntimeException("simulated chunk insert failure"))
                .given(bulkFanoutService).insertAndDispatchChunk(
                        anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // when
        int failedRecipients = notificationHelper.notifyAllPreAuthorized(
                recipients, "SURVEY_CREATED", NotificationPriority.NORMAL,
                "タイトル", "本文", "SURVEY", 1L,
                NotificationScopeType.ORGANIZATION, 2L, "/surveys/1", 3L);

        // then: 例外は投げないが、戻り値で欠落を正確に返す。
        assertThat(failedRecipients).isEqualTo(3);
    }

    @Test
    @DisplayName("あるlocaleグループの配信が全滅しても、成功として誤集計しない（Codex三巡目是正）")
    void localeグループの配信全滅は成功として誤集計されない() {
        // given: ja/en の2 locale。en グループの insertAndDispatchChunk だけ例外を投げる＝配信全滅。
        List<Long> recipients = List.of(1L, 2L);
        Map<Long, String> locales = Map.of(1L, "ja", 2L, "en");
        given(userLocaleCache.getLocales(recipients)).willReturn(locales);

        org.mockito.BDDMockito.willAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<Long> chunk = invocation.getArgument(0);
                    if (chunk.contains(2L)) {
                        throw new RuntimeException("simulated chunk insert failure for en group");
                    }
                    return null;
                })
                .given(bulkFanoutService).insertAndDispatchChunk(
                        anyList(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());

        // when: bodyBuilder 自体は例外を投げない（= 旧実装なら successGroupCount++ されてしまう状況）。
        notificationHelper.notifyAllPreAuthorizedLocalized(
                recipients,
                "SURVEY_CREATED",
                "SURVEY", 1L,
                NotificationScopeType.ORGANIZATION, 2L,
                "/surveys/1", 3L,
                (userId, locale) -> new NotificationHelper.LocalizedMessage("t", "b"));

        // then: 末尾ログが実際の欠落（failedRecipientCount=1）を報告し、
        // 「例外なく呼び出せた」だけの messageBuiltGroupCount を「成功」と詐称していないことを検証する。
        assertThat(logAppender.list)
                .as("配信全滅（en グループ）が failedRecipientCount へ正しく反映される")
                .anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("failedRecipientCount=1")
                        .contains("deliveredRecipientCount=1"));
    }

    private static List<Long> argThatEquals(List<Long> expected) {
        return org.mockito.ArgumentMatchers.argThat(actual ->
                actual != null && actual.stream().collect(Collectors.toSet())
                        .equals(expected.stream().collect(Collectors.toSet())));
    }

    private static List<Long> argThatSingleRecipient() {
        return org.mockito.ArgumentMatchers.argThat(actual -> actual != null && actual.size() == 1);
    }
}
