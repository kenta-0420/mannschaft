package com.mannschaft.app.analytics.event;

import com.mannschaft.app.analytics.PageViewContentType;
import com.mannschaft.app.analytics.PageViewScopeType;

import java.time.LocalDateTime;

/**
 * ページビュー計測イベント（F10.8 アクセス解析）。
 *
 * <p>計測ビーコン {@code POST /api/v1/page-views} を受けた
 * {@link com.mannschaft.app.analytics.service.PageViewRecordingService} が
 * {@code ApplicationEventPublisher} で publish する軽量 DTO。
 * {@link PageViewRecordListener} が {@code @Async("page-view-pool")} で受信し、
 * scope 実在チェックののち {@code page_view_logs} へ非同期 INSERT する（設計書 §5.1）。</p>
 *
 * <p><b>トランザクション外イベントである点（重要）</b>: 本 POST はトランザクション境界を持たないため、
 * {@code @TransactionalEventListener(AFTER_COMMIT)} ではなく素の {@code @EventListener} で受ける
 * （AFTER_COMMIT はトランザクションが無いと発火しない）。よって publish 時点で即座に非同期実行される。</p>
 *
 * <p>不変・軽量（record）にすることで、非同期スレッドへ渡す際の副作用を排除する。</p>
 *
 * @param scopeType   スコープ種別（{@code TEAM} / {@code ORGANIZATION}）
 * @param scopeId     チーム/組織の数値 ID（slug ではない）
 * @param contentType 閲覧対象種別（{@code ARTICLE} / {@code ACTIVITY} / {@code PAGE} / {@code TEAM}）
 * @param contentId   閲覧対象 ID（ID を持たない種別は 0 固定）
 * @param url         アプリ内相対パス（Controller で相対パス検証済み）
 * @param title       表示タイトル（Controller で最大長・制御文字は未加工。リスナー側で無害化・切詰する）
 * @param userId      ログイン利用者 ID。{@code null} = ゲスト（未ログイン）
 * @param visitorId   匿名 cookie の UUID（個人特定不能）
 * @param viewedAt    閲覧日時（JST 壁時計）
 */
public record PageViewRecordedEvent(
        PageViewScopeType scopeType,
        Long scopeId,
        PageViewContentType contentType,
        Long contentId,
        String url,
        String title,
        Long userId,
        String visitorId,
        LocalDateTime viewedAt) {
}
