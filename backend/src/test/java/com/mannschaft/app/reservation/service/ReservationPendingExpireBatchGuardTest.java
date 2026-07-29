package com.mannschaft.app.reservation.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 仮押さえ自動失効バッチの<b>宣言</b>を機械検証する番人テスト（F03.4.5 §6.3・受け入れ条件 AC-6-8）。
 *
 * <p>バッチは「動くこと」より「登録されていること・多重起動しないこと・想定間隔で回ること」が
 * 運用上の要になる。これらは実行時にしか壊れが露見しないため、reflection で恒久的に固定する。</p>
 *
 * <ul>
 *   <li>{@link BatchEndpoint} … {@code BatchEndpointRegistry} が起動時に走査する運用バッチ台帳への登録。
 *       名前が重複すると起動時 FAIL FAST するため、名前そのものも固定する</li>
 *   <li>{@link Scheduled} … {@code fixedDelay = 300_000}（5 分）。リマインド送出（1 分）と負荷帯を分離する
 *       という設計意図（§6.3）が、うっかり 1 分等へ変えられていないことを守る</li>
 *   <li>{@link SchedulerLock} … 複数 pod での多重起動防止。ロック名は
 *       {@code ShedLockConfig} の Javadoc 台帳と一致していること</li>
 * </ul>
 */
@DisplayName("仮押さえ自動失効バッチ 宣言番人テスト（F03.4.5 §6.3 / AC-6-8）")
class ReservationPendingExpireBatchGuardTest {

    private static final String EXPECTED_BATCH_NAME = "reservation-pending-expire";
    private static final String EXPECTED_LOCK_NAME = "reservationPendingExpireBatch";
    private static final long EXPECTED_FIXED_DELAY_MS = 300_000L;

    private Method batchMethod() throws NoSuchMethodException {
        return ReservationPendingExpireBatchService.class.getMethod("expirePendingReservations");
    }

    @Test
    @DisplayName("@BatchEndpoint が付与され name が reservation-pending-expire である")
    void batchEndpointが付与されている() throws Exception {
        BatchEndpoint annotation = batchMethod().getAnnotation(BatchEndpoint.class);

        assertThat(annotation)
                .as("@BatchEndpoint が付与されていること（BatchEndpointRegistry の走査対象になる）")
                .isNotNull();
        assertThat(annotation.name())
                .as("バッチ名は {domain}-{action} 命名規約に沿い、既存バッチと重複しないこと")
                .isEqualTo(EXPECTED_BATCH_NAME);
        assertThat(annotation.description())
                .as("運用者が用途を判別できる説明が入っていること")
                .isNotBlank();
    }

    @Test
    @DisplayName("@Scheduled(fixedDelay = 300_000) で 5 分間隔である")
    void scheduledが5分間隔である() throws Exception {
        Scheduled annotation = batchMethod().getAnnotation(Scheduled.class);

        assertThat(annotation).as("@Scheduled が付与されていること").isNotNull();
        assertThat(annotation.fixedDelay())
                .as("§6.3: 5 分間隔（リマインド送出の 1 分間隔と負荷帯を分離する）")
                .isEqualTo(EXPECTED_FIXED_DELAY_MS);
        assertThat(annotation.cron())
                .as("fixedDelay 方式のため cron は使わない")
                .isEmpty();
    }

    @Test
    @DisplayName("@SchedulerLock が付与され ShedLockConfig 台帳と同じロック名である")
    void schedulerLockが付与されている() throws Exception {
        SchedulerLock annotation = batchMethod().getAnnotation(SchedulerLock.class);

        assertThat(annotation)
                .as("@SchedulerLock が付与されていること（複数 pod での多重起動防止）")
                .isNotNull();
        assertThat(annotation.name())
                .as("ロック名は ShedLockConfig の Javadoc 台帳と一致すること")
                .isEqualTo(EXPECTED_LOCK_NAME);
        assertThat(annotation.lockAtLeastFor()).isEqualTo("30s");
        assertThat(annotation.lockAtMostFor())
                .as("fixedDelay(5分)と同値だとロック失効と次回起動が重なり二重処理になる。"
                        + "@Version が無いため二重処理は booked_count を余分に減らす")
                .isEqualTo("15m");
    }

    @Test
    @DisplayName("lockAtMostFor が fixedDelay より長い（ロック失効と次回起動の重なりを防ぐ）")
    void ロック保持時間が実行間隔より長い() throws Exception {
        Scheduled scheduled = batchMethod().getAnnotation(Scheduled.class);
        SchedulerLock lock = batchMethod().getAnnotation(SchedulerLock.class);

        long fixedDelayMinutes = scheduled.fixedDelay() / 60_000L;
        long lockAtMostForMinutes = Long.parseLong(lock.lockAtMostFor().replace("m", ""));

        assertThat(lockAtMostForMinutes)
                .as("lockAtMostFor は fixedDelay より長いこと（同値・短いと二重処理の窓が開く）")
                .isGreaterThan(fixedDelayMinutes);
    }

    @Test
    @DisplayName("ShedLockConfig の Javadoc 台帳に本バッチと待ちクリーンアップのロック名が載っている")
    void shedLockConfigのJavadoc台帳に記載がある() throws Exception {
        // ShedLockConfig の Javadoc は「新しいバッチを追加する場合は本 Javadoc にロック名と
        // 一行説明を追記すること」を義務規定として持つ。Javadoc は実行時に読めないためソースを直接読む。
        java.nio.file.Path configSource = java.nio.file.Path.of(
                "src/main/java/com/mannschaft/app/config/ShedLockConfig.java");
        assertThat(java.nio.file.Files.exists(configSource))
                .as("ShedLockConfig のソースが見つかること（テストの CWD は backend/）")
                .isTrue();
        String source = java.nio.file.Files.readString(configSource);

        assertThat(source)
                .as("本バッチのロック名が ShedLockConfig の台帳に追記されていること")
                .contains(EXPECTED_LOCK_NAME);
        assertThat(source)
                .as("W2-4 で追加済みなのに追記漏れしていた待ちクリーンアップも台帳に載っていること")
                .contains("reservationWaitlistCleanupBatch");
    }
}
