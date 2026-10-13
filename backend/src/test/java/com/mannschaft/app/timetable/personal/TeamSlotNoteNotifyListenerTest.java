package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationDeliveryRequest;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.event.TimetableSlotNoteUpdatedEvent;
import com.mannschaft.app.timetable.personal.entity.PersonalTimetableSettingsEntity;
import com.mannschaft.app.timetable.personal.listener.TeamSlotNoteNotifyListener;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * F03.15 Phase 4 TeamSlotNoteNotifyListener のユニットテスト
 * （Issue #2997 G7-a: AFTER_COMMIT + @Async + 受信者ごと sendOne、M8 デバウンス実効化）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TeamSlotNoteNotifyListener ユニットテスト")
class TeamSlotNoteNotifyListenerTest {

    private static final Long TEAM_ID = 50L;
    private static final Long TT_ID = 200L;
    private static final Long SLOT_ID = 11L;
    private static final String KEY =
            "mannschaft:f0315:slot_note_debounce:" + TT_ID + ":MON:1:EVERY";

    @Mock private UserRoleRepository userRoleRepository;
    @Mock private PersonalTimetableSettingsRepository settingsRepository;
    @Mock private UserLocaleCache userLocaleCache;
    @Mock private NotificationDeliveryRunner deliveryRunner;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private TeamSlotNoteNotifyListener listener;

    @BeforeEach
    void setUp() {
        // Issue #2715 ロットB: getLocales は N+1 防止の一括解決。
        lenient().when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        listener = new TeamSlotNoteNotifyListener(
                userRoleRepository, settingsRepository, userLocaleCache, new StaticMessageSource(), deliveryRunner);
        ReflectionTestUtils.setField(listener, "redisTemplate", redisTemplate);
    }

    private static TimetableSlotNoteUpdatedEvent event(String notes, Long teamId) {
        return new TimetableSlotNoteUpdatedEvent(
                SLOT_ID, TT_ID, teamId, "情報", notes, "MON", 1, WeekPattern.EVERY);
    }

    private void acquireSucceeds() {
        given(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class))).willReturn(true);
    }

    // ── 抑止条件 ──

    @Test
    @DisplayName("notes が空文字なら通知しない（印も取らない）")
    void empty_notes_スキップ() {
        listener.onSlotNoteUpdated(event("", TEAM_ID));
        verify(userRoleRepository, never()).findUserIdsByScope(any(), any());
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    @Test
    @DisplayName("notes が空白のみなら通知しない")
    void blank_notes_スキップ() {
        listener.onSlotNoteUpdated(event("   ", TEAM_ID));
        verify(userRoleRepository, never()).findUserIdsByScope(any(), any());
    }

    @Test
    @DisplayName("notes が null なら通知しない")
    void null_notes_スキップ() {
        listener.onSlotNoteUpdated(event(null, TEAM_ID));
        verify(userRoleRepository, never()).findUserIdsByScope(any(), any());
    }

    @Test
    @DisplayName("teamId が null なら通知しない（印も取らない）")
    void team_null_スキップ() {
        listener.onSlotNoteUpdated(event("持参物", null));
        verify(userRoleRepository, never()).findUserIdsByScope(any(), any());
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
    }

    // ── 配送 ──

    @Test
    @DisplayName("Issue #2715 ロットB: 受信者 locale が en の場合、件名が英語で組み立てられプレースホルダが残らない")
    void 受信者ロケールがenなら英語件名になる() {
        var realMessageSource = new org.springframework.context.support.ResourceBundleMessageSource();
        realMessageSource.setBasename("messages");
        realMessageSource.setDefaultEncoding("UTF-8");
        listener = new TeamSlotNoteNotifyListener(
                userRoleRepository, settingsRepository, userLocaleCache, realMessageSource, deliveryRunner);
        ReflectionTestUtils.setField(listener, "redisTemplate", redisTemplate);
        acquireSucceeds();
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L));
        given(settingsRepository.findById(101L)).willReturn(Optional.empty());
        given(userLocaleCache.getLocales(List.of(101L))).willReturn(Map.of(101L, "en"));
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onSlotNoteUpdated(new TimetableSlotNoteUpdatedEvent(
                SLOT_ID, TT_ID, TEAM_ID, "Math", "持参物", "MON", 1, WeekPattern.EVERY));

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(deliveryRunner).sendOne(captor.capture());
        assertThat(captor.getValue().title()).contains("Math").doesNotContain("{0}");
        assertThat(captor.getValue().recipientUserId()).isEqualTo(101L);
        assertThat(captor.getValue().notificationType()).isEqualTo("TEAM_SLOT_NOTE_UPDATED");
        assertThat(captor.getValue().sourceType()).isEqualTo("TIMETABLE_SLOT");
        assertThat(captor.getValue().sourceId()).isEqualTo(SLOT_ID);
        assertThat(captor.getValue().actionUrl()).isEqualTo("/teams/50/timetables/200");
    }

    @Test
    @DisplayName("notify_team_slot_note_updates = false のメンバーには配送せず、ON のメンバーには配送する")
    void 設定OFFメンバーは通知しない() {
        acquireSucceeds();
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L, 102L));
        given(settingsRepository.findById(101L)).willReturn(Optional.of(
                PersonalTimetableSettingsEntity.builder().userId(101L).notifyTeamSlotNoteUpdates(false).build()));
        given(settingsRepository.findById(102L)).willReturn(Optional.empty()); // デフォルト ON
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onSlotNoteUpdated(event("次回 p.30 まで読んでくる", TEAM_ID));

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(deliveryRunner, times(1)).sendOne(captor.capture());
        assertThat(captor.getValue().recipientUserId()).isEqualTo(102L);
        // 受信者ごとの設定取得は従来どおり N 回（悪化させない）。
        verify(settingsRepository, times(1)).findById(101L);
        verify(settingsRepository, times(1)).findById(102L);
    }

    @Test
    @DisplayName("本文は100文字で切り詰められる")
    void 本文は100文字で切り詰める() {
        acquireSucceeds();
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L));
        given(settingsRepository.findById(101L)).willReturn(Optional.empty());
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onSlotNoteUpdated(event("あ".repeat(150), TEAM_ID));

        ArgumentCaptor<NotificationDeliveryRequest> captor = ArgumentCaptor.forClass(NotificationDeliveryRequest.class);
        verify(deliveryRunner).sendOne(captor.capture());
        assertThat(captor.getValue().body()).isEqualTo("あ".repeat(100) + "...");
    }

    // ── M8 デバウンス ──

    @Test
    @DisplayName("M8: 印の取得に失敗（5分内に通知済み）なら配送せずメンバー取得もしない")
    void 印を取れなければ配送しない() {
        given(valueOps.setIfAbsent(eq(KEY), anyString(), any(Duration.class))).willReturn(false);

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        verify(userRoleRepository, never()).findUserIdsByScope(any(), any());
        verify(deliveryRunner, never()).sendOne(any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("M8: 印は {timetableId}:{dayOfWeek}:{periodNumber}:{weekPattern} で TTL 5分、値は UUID")
    void 印のキーと値と有効期限() {
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        given(valueOps.setIfAbsent(eq(KEY), value.capture(), ttl.capture())).willReturn(true);
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L));
        given(settingsRepository.findById(101L)).willReturn(Optional.empty());
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(5));
        assertThat(java.util.UUID.fromString(value.getValue())).isNotNull();
    }

    @Test
    @DisplayName("M8: 全員が例外で失敗したら、自分が取った印を（同じトークンで）解放する")
    void 全員失敗なら印を解放する() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        given(valueOps.setIfAbsent(eq(KEY), token.capture(), any(Duration.class))).willReturn(true);
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L, 102L));
        given(settingsRepository.findById(any())).willReturn(Optional.empty());
        given(deliveryRunner.sendOne(any())).willThrow(new RuntimeException("DB 障害（検証用）"));

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        verify(deliveryRunner, times(2)).sendOne(any());
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(KEY)), eq(token.getValue()));
    }

    @Test
    @DisplayName("M8: 配送対象が0人（全員が通知OFF）なら印を解放する")
    void 配送対象が0人なら印を解放する() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        given(valueOps.setIfAbsent(eq(KEY), token.capture(), any(Duration.class))).willReturn(true);
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L));
        given(settingsRepository.findById(101L)).willReturn(Optional.of(
                PersonalTimetableSettingsEntity.builder().userId(101L).notifyTeamSlotNoteUpdates(false).build()));

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        verify(deliveryRunner, never()).sendOne(any());
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(KEY)), eq(token.getValue()));
    }

    @Test
    @DisplayName("M8: メンバーが0人でも印を解放する")
    void メンバー0人なら印を解放する() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        given(valueOps.setIfAbsent(eq(KEY), token.capture(), any(Duration.class))).willReturn(true);
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of());

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(KEY)), eq(token.getValue()));
    }

    @Test
    @DisplayName("M8: メンバー取得で例外になったら印を解放し、例外は外へ出さない")
    void 準備で例外なら印を解放する() {
        ArgumentCaptor<String> token = ArgumentCaptor.forClass(String.class);
        given(valueOps.setIfAbsent(eq(KEY), token.capture(), any(Duration.class))).willReturn(true);
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willThrow(new RuntimeException("DB 障害（検証用）"));

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of(KEY)), eq(token.getValue()));
    }

    @Test
    @DisplayName("M8: 一部成功なら印を保持する（成功済みにも失敗した受信者にも再送しない）")
    void 一部成功なら印を保持する() {
        acquireSucceeds();
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L, 102L));
        given(settingsRepository.findById(any())).willReturn(Optional.empty());
        given(deliveryRunner.sendOne(any()))
                .willThrow(new RuntimeException("DB 障害（検証用）"))
                .willReturn(NotificationDeliveryResult.DELIVERED);

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        verify(deliveryRunner, times(2)).sendOne(any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("M8: visibility deny のみ（例外なし）でも印を保持する")
    void denyなら印を保持する() {
        acquireSucceeds();
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L));
        given(settingsRepository.findById(101L)).willReturn(Optional.empty());
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.VISIBILITY_DENIED);

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("M8: Valkey 障害時はデバウンスを諦めて毎回通知する（解放も試みない）")
    void Valkey障害時は毎回通知する() {
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willThrow(new IllegalStateException("Valkey 障害（検証用）"));
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L));
        given(settingsRepository.findById(101L)).willReturn(Optional.empty());
        given(deliveryRunner.sendOne(any())).willThrow(new RuntimeException("DB 障害（検証用）"));

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));
        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        verify(deliveryRunner, times(2)).sendOne(any());
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("M8: TTL を跨いで遅れて来た解放が、後から取り直された新しい印を消さない（トークン一致時のみ削除）")
    void 古い解放が新しい印を消さない() {
        Map<String, String> store = new ConcurrentHashMap<>();
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willAnswer(inv -> store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        given(redisTemplate.execute(any(RedisScript.class), anyList(), any())).willAnswer(inv -> {
            List<String> keys = inv.getArgument(1);
            if (inv.getArgument(2).toString().equals(store.get(keys.get(0)))) {
                store.remove(keys.get(0));
                return 1L;
            }
            return 0L;
        });
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L));
        given(settingsRepository.findById(101L)).willReturn(Optional.empty());
        // 配送の最中に TTL が切れ、別の発火が新しい印を取り直した状況を再現する。
        given(deliveryRunner.sendOne(any())).willAnswer(inv -> {
            store.put(KEY, "新しい発火のトークン");
            throw new RuntimeException("DB 障害（検証用）");
        });

        listener.onSlotNoteUpdated(event("持参物", TEAM_ID));

        assertThat(store).as("古い解放は新しい印を消さない").containsEntry(KEY, "新しい発火のトークン");
    }

    @Test
    @DisplayName("M8: 並行する2発火でも配送は1回（SET NX で片方だけが印を取る）")
    void 並行2発火でも配送は1回() throws Exception {
        Map<String, String> store = new ConcurrentHashMap<>();
        given(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willAnswer(inv -> store.putIfAbsent(inv.getArgument(0), inv.getArgument(1)) == null);
        given(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).willReturn(List.of(101L));
        given(settingsRepository.findById(101L)).willReturn(Optional.empty());
        given(deliveryRunner.sendOne(any())).willReturn(NotificationDeliveryResult.DELIVERED);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Runnable fire = () -> {
                ready.countDown();
                try {
                    go.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                listener.onSlotNoteUpdated(event("持参物", TEAM_ID));
            };
            var f1 = pool.submit(fire);
            var f2 = pool.submit(fire);
            ready.await();
            go.countDown();
            f1.get(10, TimeUnit.SECONDS);
            f2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        verify(deliveryRunner, times(1)).sendOne(any());
    }
}
