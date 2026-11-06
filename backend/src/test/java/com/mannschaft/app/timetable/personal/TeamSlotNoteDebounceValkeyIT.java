package com.mannschaft.app.timetable.personal;

import com.mannschaft.app.common.i18n.UserLocaleCache;
import com.mannschaft.app.notification.service.NotificationDeliveryResult;
import com.mannschaft.app.notification.service.NotificationDeliveryRunner;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.timetable.WeekPattern;
import com.mannschaft.app.timetable.event.TimetableSlotNoteUpdatedEvent;
import com.mannschaft.app.timetable.personal.listener.TeamSlotNoteNotifyListener;
import com.mannschaft.app.timetable.personal.repository.PersonalTimetableSettingsRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #2997 G7-a（M8）— デバウンスの印の取得・解放を<b>実 Valkey（Redis 互換）</b>で検証する。
 *
 * <p>UT ではフェイクが「トークン一致時のみ削除」を自前実装するため、実装の Lua を無条件 DEL に変えても緑になってしまう。
 * 本 IT は実際の Lua スクリプトを実 Redis で走らせ、「TTL を跨いで遅れて来た古い解放が、後から取り直された新しい印を
 * 消さない」ことを固定する（無条件 DEL にすると {@link #古い解放が新しい印を消さない} が red になる）。</p>
 */
@EnabledIf("com.mannschaft.app.timetable.personal.TeamSlotNoteDebounceValkeyIT#isDockerAvailable")
@DisplayName("Issue #2997 G7-a デバウンスの印（実 Valkey）")
class TeamSlotNoteDebounceValkeyIT {

    private static final String KEY = "mannschaft:f0315:slot_note_debounce:200:MON:1:EVERY";
    private static final Long TEAM_ID = 50L;

    // docker exec を使わずログ待機にする（開発機の Docker TCP プロキシ環境での回避。既存の relay IT と同じ）。
    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections tcp.*\\n", 1)
                    .withStartupTimeout(Duration.ofSeconds(120)));

    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    private final UserRoleRepository userRoleRepository = mock(UserRoleRepository.class);
    private final PersonalTimetableSettingsRepository settingsRepository = mock(PersonalTimetableSettingsRepository.class);
    private final UserLocaleCache userLocaleCache = mock(UserLocaleCache.class);
    private final NotificationDeliveryRunner deliveryRunner = mock(NotificationDeliveryRunner.class);
    private TeamSlotNoteNotifyListener listener;

    public static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeAll
    static void start() {
        if (!isDockerAvailable()) {
            return;
        }
        REDIS.start();
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getFirstMappedPort()));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void stop() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (REDIS.isRunning()) {
            REDIS.stop();
        }
    }

    @BeforeEach
    void setUp() {
        redis.delete(KEY);
        when(userLocaleCache.getLocales(any())).thenReturn(Map.of());
        when(settingsRepository.findById(any())).thenReturn(Optional.empty());
        when(userRoleRepository.findUserIdsByScope("TEAM", TEAM_ID)).thenReturn(List.of(101L, 102L));
        listener = new TeamSlotNoteNotifyListener(
                userRoleRepository, settingsRepository, userLocaleCache, new StaticMessageSource(), deliveryRunner);
        ReflectionTestUtils.setField(listener, "redisTemplate", redis);
    }

    @AfterEach
    void tearDown() {
        redis.delete(KEY);
    }

    private void fire() {
        listener.onSlotNoteUpdated(new TimetableSlotNoteUpdatedEvent(
                11L, 200L, TEAM_ID, "情報", "持参物", "MON", 1, WeekPattern.EVERY));
    }

    @Test
    @DisplayName("取得: 値は UUID・TTL は5分以内で、5分内の2回目は配送されない")
    void 印を取得し2回目は配送しない() {
        when(deliveryRunner.sendOne(any())).thenReturn(NotificationDeliveryResult.DELIVERED);

        fire();
        String value = redis.opsForValue().get(KEY);
        Long ttl = redis.getExpire(KEY);
        fire();

        assertThat(UUID.fromString(value)).isNotNull();
        assertThat(ttl).isBetween(1L, 300L);
        verify(deliveryRunner, times(2)).sendOne(any()); // 1回目の2人ぶんのみ（2回目は0）
    }

    @Test
    @DisplayName("全員失敗: 自分が取った印を実 Lua で解放する")
    void 全員失敗なら印を解放する() {
        when(deliveryRunner.sendOne(any())).thenThrow(new RuntimeException("DB 障害（検証用）"));

        fire();

        assertThat(redis.hasKey(KEY)).isFalse();
    }

    @Test
    @DisplayName("TTL を跨いで遅れて来た古い解放が、後から取り直された新しい印を消さない（無条件 DEL なら red）")
    void 古い解放が新しい印を消さない() {
        // 配送の最中に TTL が切れ、別の発火が新しい印を取り直した状況を実 Valkey 上で再現する。
        when(deliveryRunner.sendOne(any())).thenAnswer(inv -> {
            redis.opsForValue().set(KEY, "新しい発火のトークン", Duration.ofMinutes(5));
            throw new RuntimeException("DB 障害（検証用）");
        });

        fire(); // 全員失敗 → 自分のトークンで解放を試みる

        assertThat(redis.opsForValue().get(KEY)).as("古い解放は新しい印を消さない").isEqualTo("新しい発火のトークン");
    }

    @Test
    @DisplayName("一部成功なら印を保持する")
    void 一部成功なら印を保持する() {
        when(deliveryRunner.sendOne(any()))
                .thenThrow(new RuntimeException("DB 障害（検証用）"))
                .thenReturn(NotificationDeliveryResult.DELIVERED);

        fire();

        assertThat(redis.hasKey(KEY)).isTrue();
    }
}
