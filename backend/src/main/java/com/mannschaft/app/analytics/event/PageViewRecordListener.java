package com.mannschaft.app.analytics.event;

import com.mannschaft.app.analytics.PageViewScopeType;
import com.mannschaft.app.analytics.entity.PageViewLogEntity;
import com.mannschaft.app.analytics.repository.PageViewLogRepository;
import com.mannschaft.app.organization.repository.OrganizationRepository;
import com.mannschaft.app.team.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * ページビュー計測イベントの非同期リスナー（F10.8 アクセス解析）。
 *
 * <p>{@link PageViewRecordedEvent} を {@code @Async("page-view-pool")}（PV 専用プール・
 * {@code AsyncConfig#pageViewPool}）で受信し、{@code page_view_logs} へ非同期 INSERT する。</p>
 *
 * <h2>@TransactionalEventListener を使わない理由（設計書 §3.1 / §5.1）</h2>
 * <p>計測ビーコン {@code POST /api/v1/page-views} はトランザクション境界を持たない
 * （書き込みは本リスナーが唯一・DB tx はここで初めて開く）。よって
 * {@code @TransactionalEventListener(AFTER_COMMIT)} は発火しないため、素の {@code @EventListener}
 * ＋ {@code @Async} を使い publish 時点で即時・非同期に処理する。</p>
 *
 * <h2>scope 実在チェック（ログ注入防御・AC-05）</h2>
 * <p>未認証を許容する公開エンドポイントのため、攻撃者が任意 {@code scopeId} を送って生ログを
 * 膨らませる／他 scope に成りすます「ログ注入」を防ぐ。存在しない（または論理削除済みの）
 * team/organization を指すイベントは <b>記録せず破棄</b>する。team は {@code TeamRepository.existsById}、
 * org は {@code OrganizationRepository.existsById} で確認する（両 Entity は
 * {@code @SQLRestriction("deleted_at IS NULL")} を持つため、既定の existsById が論理削除済みを除外する）。
 * これはドメインをまたぐ<b>読み取り専用</b>の実在確認であり、FK も張らないため境界違反にならない
 * （CLAUDE.md 原則 1・原則 5。書き込みは自ドメイン {@code page_view_logs} に閉じる）。</p>
 *
 * <h2>title のログ注入防止（AC-23）</h2>
 * <p>{@code title} は制御文字・改行を除去し 255 文字に切り詰めてから保存する
 * （生ログ SQL に混ぜて監査を偽装する攻撃の防止・設計書 §3.1 入力バリデーション）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PageViewRecordListener {

    /** {@code page_view_logs.title} の最大長（DDL の {@code VARCHAR(255)} に整合）。 */
    private static final int TITLE_MAX_LENGTH = 255;

    private final PageViewLogRepository pageViewLogRepository;
    private final TeamRepository teamRepository;
    private final OrganizationRepository organizationRepository;

    /**
     * 計測イベントを非同期で受信し、scope 実在チェックののち生ログへ INSERT する。
     *
     * @param event 計測イベント（Controller で相対パス検証済み・title は未加工）
     */
    @Async("page-view-pool")
    @EventListener
    public void onPageViewRecorded(PageViewRecordedEvent event) {
        if (!scopeExists(event.scopeType(), event.scopeId())) {
            // 存在しない / 論理削除済みスコープはログ注入防御のため破棄（AC-05）。
            log.debug("[PageViewRecord] 存在しないスコープのため破棄: scopeType={}, scopeId={}",
                    event.scopeType(), event.scopeId());
            return;
        }

        LocalDateTime viewedAt = event.viewedAt() != null ? event.viewedAt() : LocalDateTime.now();

        PageViewLogEntity entity = PageViewLogEntity.builder()
                .scopeType(event.scopeType())
                .scopeId(event.scopeId())
                .contentType(event.contentType())
                .contentId(event.contentId() != null ? event.contentId() : 0L)
                .url(event.url())
                .title(sanitizeTitle(event.title()))
                .userId(event.userId())
                .visitorId(event.visitorId())
                .viewedAt(viewedAt)
                .build();

        pageViewLogRepository.save(entity);
    }

    /**
     * スコープ（team / organization）が実在する（かつ論理削除されていない）かを判定する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープ ID
     * @return 実在すれば {@code true}
     */
    private boolean scopeExists(PageViewScopeType scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) {
            return false;
        }
        return switch (scopeType) {
            case TEAM -> teamRepository.existsById(scopeId);
            case ORGANIZATION -> organizationRepository.existsById(scopeId);
        };
    }

    /**
     * {@code title} から制御文字（改行・タブ等）を除去し、255 文字に切り詰める（AC-23）。
     *
     * <p>{@code null} は空文字に正規化する（{@code page_view_logs.title} は NOT NULL）。</p>
     *
     * @param raw 生の title
     * @return 無害化・切詰済み title
     */
    private String sanitizeTitle(String raw) {
        if (raw == null) {
            return "";
        }
        // 制御文字（C0 制御・DEL）を除去。改行・タブもここで落ちる。
        String cleaned = raw.replaceAll("[\\p{Cntrl}]", "");
        if (cleaned.length() > TITLE_MAX_LENGTH) {
            cleaned = cleaned.substring(0, TITLE_MAX_LENGTH);
        }
        return cleaned;
    }
}
