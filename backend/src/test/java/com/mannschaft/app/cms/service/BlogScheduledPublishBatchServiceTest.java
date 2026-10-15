package com.mannschaft.app.cms.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ブログ予約公開バッチの制御構造テスト（issue #2616・試練）。
 *
 * <p>受け入れ条件の対応:</p>
 * <ul>
 *   <li><b>AC-7</b>: 対象を拾って 1 件ずつ公開処理へ渡す（実際の遷移は結合テストが実 MySQL で検証）</li>
 *   <li><b>AC-10</b>: 対象 0 件で例外を投げず、更新経路を 1 度も呼ばない</li>
 *   <li><b>AC-11</b>: 3 件中 2 件目が例外を投げても 1 件目・3 件目は処理される</li>
 * </ul>
 *
 * <p>時刻境界・NULL・論理削除・N+1 は
 * {@code BlogScheduledPublishPersistenceIntegrationTest} が実 MySQL で検証する。
 * 本クラスは障害注入しやすい Mockito で<b>ループと try/catch の構造</b>だけを固定する
 * （{@code ReservationPendingExpireBatchServiceTest} の作法を踏襲）。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ブログ予約公開バッチ 制御構造テスト（issue #2616）")
class BlogScheduledPublishBatchServiceTest {

    @Mock
    private BlogScheduledPublishService scheduledPublishService;

    /**
     * 固定した壁時計 Clock。本番では {@code ClockConfig#wallClock}（業務ローカル時刻ゾーン）が
     * 注入される。ここではゾーンずれを検出できるよう、あえて UTC 以外のゾーンで固定する。
     */
    private static final Instant FIXED_INSTANT = Instant.parse("2026-08-13T01:23:45Z");
    private static final ZoneId WALL_ZONE = ZoneId.of("Asia/Tokyo");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_INSTANT, WALL_ZONE);

    private BlogScheduledPublishBatchService batchService;

    @BeforeEach
    void setUp() {
        batchService = new BlogScheduledPublishBatchService(scheduledPublishService, FIXED_CLOCK);
    }

    // ────────────────────────────────────────────────────────────
    // AC-10: 対象 0 件で副作用ゼロ
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-10: 対象0件なら更新クエリを一切発行せず 0 件を返す")
    void ac10_対象0件は副作用ゼロ() {
        given(scheduledPublishService.findDuePostIds(any(LocalDateTime.class))).willReturn(List.of());

        Integer published = batchService.publishScheduledPosts();

        assertThat(published).as("公開件数は 0").isZero();
        verify(scheduledPublishService, never()).publishScheduledPost(anyLong(), any());
    }

    @Test
    @DisplayName("AC-10: 対象0件でも例外を投げない")
    void ac10_対象0件でも例外を投げない() {
        given(scheduledPublishService.findDuePostIds(any(LocalDateTime.class))).willReturn(List.of());

        assertThatCode(() -> batchService.publishScheduledPosts()).doesNotThrowAnyException();
    }

    // ────────────────────────────────────────────────────────────
    // AC-7: 拾った対象を 1 件ずつ公開処理へ渡す
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-7: 対象記事をすべて公開処理へ渡し、遷移した件数を返す")
    void ac7_対象を拾って公開処理へ渡す() {
        given(scheduledPublishService.findDuePostIds(any(LocalDateTime.class)))
                .willReturn(List.of(11L, 12L, 13L));
        given(scheduledPublishService.publishScheduledPost(eq(11L), any())).willReturn(true);
        given(scheduledPublishService.publishScheduledPost(eq(12L), any())).willReturn(true);
        // 取得後に条件が崩れた等で遷移しなかった件は件数に数えない
        given(scheduledPublishService.publishScheduledPost(eq(13L), any())).willReturn(false);

        Integer published = batchService.publishScheduledPosts();

        assertThat(published).as("実際に遷移した 2 件のみ計上する").isEqualTo(2);
        verify(scheduledPublishService, times(1)).publishScheduledPost(eq(11L), any());
        verify(scheduledPublishService, times(1)).publishScheduledPost(eq(12L), any());
        verify(scheduledPublishService, times(1)).publishScheduledPost(eq(13L), any());
    }

    // ────────────────────────────────────────────────────────────
    // AC-11: 途中 1 件の失敗が他を巻き込まない
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("AC-11: 3件中2件目が例外でも1件目・3件目は処理され、バッチ自体は例外を投げない")
    void ac11_一件の失敗が他を巻き込まない() {
        given(scheduledPublishService.findDuePostIds(any(LocalDateTime.class)))
                .willReturn(List.of(21L, 22L, 23L));
        given(scheduledPublishService.publishScheduledPost(eq(21L), any())).willReturn(true);
        given(scheduledPublishService.publishScheduledPost(eq(22L), any()))
                .willThrow(new IllegalStateException("公開遷移で想定外の障害"));
        given(scheduledPublishService.publishScheduledPost(eq(23L), any())).willReturn(true);

        Integer published = batchService.publishScheduledPosts();

        assertThat(published)
                .as("失敗した 2 件目を除き、1 件目と 3 件目が計上される（ループが中断していない）")
                .isEqualTo(2);
        verify(scheduledPublishService, times(1)).publishScheduledPost(eq(23L), any());
    }

    @Test
    @DisplayName("AC-11: 全件が例外でもバッチは落ちず 0 件を返す")
    void ac11_全件失敗でもバッチは落ちない() {
        given(scheduledPublishService.findDuePostIds(any(LocalDateTime.class)))
                .willReturn(List.of(31L, 32L));
        given(scheduledPublishService.publishScheduledPost(anyLong(), any()))
                .willThrow(new IllegalStateException("DB 障害"));

        Integer published = batchService.publishScheduledPosts();

        assertThat(published).isZero();
        verify(scheduledPublishService, times(2)).publishScheduledPost(anyLong(), any());
    }

    // ────────────────────────────────────────────────────────────
    // 時間基準: 注入 Clock を JVM 既定ゾーンで読み替えた壁時計を基準時刻に使う
    // （UTC Clock をそのまま使うと JST 環境で 9 時間ずれ、予約時刻より早く/遅く公開される）
    // ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("基準時刻は注入した壁時計 Clock のゾーンで解釈した値である（published_at と同一基準）")
    void 基準時刻は壁時計clockのゾーンで解釈する() {
        given(scheduledPublishService.findDuePostIds(any(LocalDateTime.class))).willReturn(List.of());

        batchService.publishScheduledPosts();

        ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(scheduledPublishService).findDuePostIds(captor.capture());
        assertThat(captor.getValue())
                .as("UTC 固定の utcClock を取り違えると JST 環境で 9 時間ずれる")
                .isEqualTo(LocalDateTime.ofInstant(FIXED_INSTANT, WALL_ZONE));
    }
}
