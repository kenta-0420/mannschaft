package com.mannschaft.app.cms.service;

import com.mannschaft.app.admin.batch.BatchEndpoint;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ブログ予約公開バッチの<b>宣言</b>を機械検証する番人テスト（issue #2616・AC-14）。
 *
 * <p>バッチは「動くこと」より「登録されていること・多重起動しないこと・想定間隔で回ること」が
 * 運用上の要になる。これらは実行時にしか壊れが露見しないため reflection で恒久的に固定する
 * （{@code ReservationPendingExpireBatchGuardTest} と同じ作法。新規バッチには 1 本添えるのが規約）。</p>
 *
 * <p>本テストは横断番人 {@code ScheduledBatchGuardTest} の 5 ルール
 * （{@code @SchedulerLock} 必須 / {@code lockAtMostFor} 明示必須 / {@code @BatchEndpoint} 必須 /
 * 短周期は {@code lockAtMostFor} > 起動間隔 / プリミティブ戻り値禁止）を、
 * 本バッチについて<b>具体値まで</b>固定する。</p>
 */
@DisplayName("ブログ予約公開バッチ 宣言番人テスト（issue #2616 / AC-14）")
class BlogScheduledPublishBatchGuardTest {

    private static final String EXPECTED_BATCH_NAME = "blog-scheduled-publish";
    private static final String EXPECTED_LOCK_NAME = "blogScheduledPublishBatch";
    private static final long EXPECTED_FIXED_DELAY_MS = 60_000L;

    private Method batchMethod() throws NoSuchMethodException {
        return BlogScheduledPublishBatchService.class.getMethod("publishScheduledPosts");
    }

    @Test
    @DisplayName("AC-14: @BatchEndpoint が付与され name が blog-scheduled-publish である")
    void ac14_batchEndpointが付与されている() throws Exception {
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
    @DisplayName("AC-14: @Scheduled(fixedDelay = 60_000) で 1 分間隔である")
    void ac14_scheduledが1分間隔である() throws Exception {
        Scheduled annotation = batchMethod().getAnnotation(Scheduled.class);

        assertThat(annotation).as("@Scheduled が付与されていること").isNotNull();
        assertThat(annotation.fixedDelay())
                .as("F06.1: 予約公開は 1 分間隔で拾う（公開時刻の遅延を最大 1 分に抑える）")
                .isEqualTo(EXPECTED_FIXED_DELAY_MS);
        assertThat(annotation.cron())
                .as("fixedDelay 方式のため cron は使わない")
                .isEmpty();
    }

    @Test
    @DisplayName("AC-14: @SchedulerLock が付与され lockAtMostFor が明示されている")
    void ac14_schedulerLockが付与されている() throws Exception {
        SchedulerLock annotation = batchMethod().getAnnotation(SchedulerLock.class);

        assertThat(annotation)
                .as("@SchedulerLock が付与されていること（複数 pod での多重起動防止）")
                .isNotNull();
        assertThat(annotation.name())
                .as("ロック名は ShedLockConfig の Javadoc 台帳と一致すること")
                .isEqualTo(EXPECTED_LOCK_NAME);
        assertThat(annotation.lockAtLeastFor()).isEqualTo("PT30S");
        assertThat(annotation.lockAtMostFor())
                .as("起動間隔(1分)の 3 倍。同値だとロック失効と次回起動が重なり二重公開の窓が開く")
                .isEqualTo("PT3M");
    }

    @Test
    @DisplayName("AC-14: lockAtMostFor が fixedDelay より長い（ロック失効と次回起動の重なりを防ぐ）")
    void ac14_ロック保持時間が実行間隔より長い() throws Exception {
        Scheduled scheduled = batchMethod().getAnnotation(Scheduled.class);
        SchedulerLock lock = batchMethod().getAnnotation(SchedulerLock.class);

        Duration lockAtMostFor = Duration.parse(lock.lockAtMostFor());

        assertThat(lockAtMostFor.toMillis())
                .as("lockAtMostFor は fixedDelay より長いこと（同値・短いと二重処理の窓が開く）")
                .isGreaterThan(scheduled.fixedDelay());
    }

    @Test
    @DisplayName("AC-14: 戻り値がプリミティブでない（ShedLock はプリミティブ戻り値をロックできない）")
    void ac14_戻り値がプリミティブでない() throws Exception {
        Class<?> returnType = batchMethod().getReturnType();

        assertThat(returnType.isPrimitive() && returnType != void.class)
                .as("プリミティブ戻り値は LockingNotSupportedException で毎回失敗する（issue #2724）。"
                        + "void か参照型（Integer 等）にすること")
                .isFalse();
    }

    @Test
    @DisplayName("AC-14: ShedLockConfig の Javadoc 台帳に本バッチのロック名が載っている")
    void ac14_shedLockConfigのJavadoc台帳に記載がある() throws Exception {
        // ShedLockConfig の Javadoc は「新しいバッチを追加する場合は本 Javadoc にロック名と
        // 一行説明を追記すること」を義務規定として持つ。Javadoc は実行時に読めないためソースを直接読む。
        Path configSource = Path.of("src/main/java/com/mannschaft/app/config/ShedLockConfig.java");
        assertThat(Files.exists(configSource))
                .as("ShedLockConfig のソースが見つかること（テストの CWD は backend/）")
                .isTrue();

        assertThat(Files.readString(configSource))
                .as("本バッチのロック名が ShedLockConfig の台帳に追記されていること")
                .contains(EXPECTED_LOCK_NAME);
    }
}
