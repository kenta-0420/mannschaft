package com.mannschaft.app.notification;

import com.mannschaft.app.notification.dto.NotificationResponse;
import com.mannschaft.app.notification.service.NotificationDispatchService;
import com.mannschaft.app.notification.service.NotificationHelper;
import com.mannschaft.app.notification.service.NotificationService;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CMP-260909-1446 の受け入れテスト。
 * <b>JPA を迂回する一括 fan-out（生 JDBC バルク INSERT）が {@code created_at} を UTC 壁時計で格納する</b>
 * ことを実 MySQL で固定する。
 *
 * <h2>何が壊れていたか</h2>
 * <p>本アプリの DB 格納基準は {@code spring.jpa.properties.hibernate.jdbc.time_zone: UTC} により
 * <b>UTC 壁時計</b>である。JPA 経路は {@code @PrePersist} の {@code LocalDateTime.now()}（JST 壁時計）を
 * Hibernate が UTC へ変換して格納する。ところが {@code NotificationBulkFanoutService} の生 JDBC 多値 INSERT は
 * {@code @PrePersist} も Hibernate の変換も通らず、JST 壁時計をそのまま束縛していたため、
 * bulk 経路の通知だけが<b>正より 9 時間先</b>に格納されていた。結果、一覧（{@code created_at DESC}）で
 * bulk 通知が未来として先頭に居座り、JPA 経路の通知が下方に埋もれた。</p>
 *
 * <p>是正は「Java 側で壁時計を作って束縛する」のをやめ、SQL リテラル {@code UTC_TIMESTAMP()} に委ねる形
 * （{@code AnnouncementReadStatusRepository#markAllAsReadByFeedIds} と同じ型）。</p>
 */
@DisplayName("一括通知バルク経路の created_at は UTC 壁時計で格納される（CMP-260909-1446）")
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class NotificationBulkFanoutCreatedAtUtcIT extends AbstractMySqlIntegrationTest {

    /** 実配信（@Async・WebSocket/Push）を切り離す。DB 格納基準の検証が目的のため。 */
    @MockitoBean
    private NotificationDispatchService dispatchService;

    @Autowired
    private NotificationHelper notificationHelper;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private JdbcTemplate jdbc;

    /** 許容差。テスト実行の揺らぎのみを吸収し、9 時間のずれは決して吸収しない。 */
    private static final Duration TOLERANCE = Duration.ofSeconds(60);

    private void fanoutBulk(List<Long> recipients, String title) {
        notificationHelper.notifyAllPreAuthorized(
                recipients, "EVENT_CREATED", NotificationPriority.NORMAL, title, "本文",
                "VILLAGE_EVENT", null, NotificationScopeType.SYSTEM, null, "/villages/x", null);
    }

    private LocalDateTime dbUtcNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP()", LocalDateTime.class);
    }

    /** DATETIME をタイムゾーン変換なしの生の壁時計として読む（格納された値そのもの）。 */
    private List<LocalDateTime> storedCreatedAt(long userId) {
        return jdbc.queryForList(
                "SELECT created_at FROM notifications WHERE user_id = ? ORDER BY id",
                LocalDateTime.class, userId);
    }

    @Test
    @DisplayName("AC-01: bulk 経路の created_at は UTC 壁時計（UTC_TIMESTAMP() との差が60秒以内）")
    void bulk経路のcreatedAtはUTC壁時計である() {
        long userId = 952_100_001L;
        fanoutBulk(List.of(userId), "AC-01");

        LocalDateTime utcNow = dbUtcNow();
        List<LocalDateTime> stored = storedCreatedAt(userId);

        assertThat(stored).hasSize(1);
        assertThat(Duration.between(stored.get(0), utcNow).abs())
                .as("bulk 経路の created_at=%s は UTC 壁時計 %s と一致すべき"
                        + "（9時間ずれていれば JST 壁時計をそのまま束縛している）", stored.get(0), utcNow)
                .isLessThanOrEqualTo(TOLERANCE);
    }

    @Test
    @DisplayName("AC-02: 同時刻に作った JPA 経路と bulk 経路の created_at が一致する（本欠陥を直接殺す）")
    void JPA経路とbulk経路のcreatedAtが一致する() {
        long jpaUser = 952_200_001L;
        long bulkUser = 952_200_002L;

        notificationService.createNotificationPreAuthorized(
                jpaUser, "EVENT_CREATED", NotificationPriority.NORMAL, "AC-02-jpa", "本文",
                "VILLAGE_EVENT", null, NotificationScopeType.SYSTEM, null, "/villages/x", null);
        fanoutBulk(List.of(bulkUser), "AC-02-bulk");

        LocalDateTime jpaCreatedAt = storedCreatedAt(jpaUser).get(0);
        LocalDateTime bulkCreatedAt = storedCreatedAt(bulkUser).get(0);

        assertThat(Duration.between(jpaCreatedAt, bulkCreatedAt).abs())
                .as("JPA 経路=%s と bulk 経路=%s は同じ格納基準（UTC 壁時計）でなければならない",
                        jpaCreatedAt, bulkCreatedAt)
                .isLessThanOrEqualTo(TOLERANCE);
    }

    @Test
    @DisplayName("AC-03: 通知一覧 API の created_at は現在時刻を超えない（未来日時を返さない）")
    void 通知一覧のcreatedAtは未来にならない() {
        long userId = 952_300_001L;
        fanoutBulk(List.of(userId), "AC-03");

        // 一覧 API は JPA 経由で読み出すため、返る createdAt はサーバ既定ゾーン（JST）の壁時計。
        LocalDateTime now = LocalDateTime.now();
        List<NotificationResponse> items =
                notificationService.listNotifications(userId, PageRequest.of(0, 10)).getContent();

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getCreatedAt())
                .as("一覧 API が未来日時を返している（bulk 経路の格納基準ずれ）。now=%s", now)
                .isBeforeOrEqualTo(now.plus(TOLERANCE));
    }

    @Test
    @DisplayName("AC-04: bulk 経路と JPA 経路が混在しても一覧の並び順が実際の作成順と一致する")
    void 混在時の一覧並び順が作成順と一致する() {
        long userId = 952_400_001L;

        // 先に bulk 経路、後から JPA 経路。created_at DESC の一覧では JPA 経路（後発）が先頭に来るべき。
        fanoutBulk(List.of(userId), "AC-04-bulk-先");
        notificationService.createNotificationPreAuthorized(
                userId, "EVENT_CREATED", NotificationPriority.NORMAL, "AC-04-jpa-後", "本文",
                "VILLAGE_EVENT", null, NotificationScopeType.SYSTEM, null, "/villages/x", null);

        List<NotificationResponse> items =
                notificationService.listNotifications(userId, PageRequest.of(0, 10)).getContent();

        assertThat(items).extracting(NotificationResponse::getTitle)
                .as("bulk 経路が未来日時で格納されると、後から作った JPA 経路の通知が下に埋もれる")
                .containsExactly("AC-04-jpa-後", "AC-04-bulk-先");
    }

    @Test
    @DisplayName("AC-05: 1 チャンク内の全行が同一の created_at を持つ（NOW() 等への退行検出）")
    void チャンク内の全行が同一のcreatedAtを持つ() {
        List<Long> recipients = List.of(952_500_001L, 952_500_002L, 952_500_003L, 952_500_004L);
        fanoutBulk(recipients, "AC-05");

        List<LocalDateTime> stored = jdbc.queryForList(
                "SELECT created_at FROM notifications WHERE user_id BETWEEN ? AND ? ORDER BY id",
                LocalDateTime.class, 952_500_001L, 952_500_004L);

        assertThat(stored).hasSize(recipients.size());
        assertThat(stored).as("同一 INSERT 文で作られた行は全て同じ created_at を持つ")
                .containsOnly(stored.get(0));
    }
}
