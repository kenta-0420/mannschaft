package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.PendingAggregate;
import com.mannschaft.app.dashboard.dto.AdminActionRequiredResponse;
import com.mannschaft.app.matching.service.MatchingAdminQueryService;
import com.mannschaft.app.payment.service.PaymentAdminQueryService;
import com.mannschaft.app.reservation.service.ReservationAdminQueryService;
import com.mannschaft.app.shift.service.ShiftRequestAdminQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * F10.1.1 / P1: 管理者向け横断「承認待ち」集約のファサード（dashboard ドメイン）。
 *
 * <p>メンバー向け {@link ScopeActionRequiredFacade}（「私が回答/確認すべきこと」）とは
 * <b>別 Bean・別クラス・別認可</b>。こちらは「ADMIN/DEPUTY が承認/処理すべき承認タスク」を
 * ドメイン横断で集約する。集約対象ドメインは<b>スコープ別に動的</b>に決まる（設計書 03 §3.2）:</p>
 * <ul>
 *   <li>team: 予約（RESERVATION）/シフト（SHIFT_REQUEST）/マッチング（MATCHING）</li>
 *   <li>organization: 未収請求（PAYMENT）のみ</li>
 * </ul>
 *
 * <p><b>認可</b>: 入口で {@link AccessControlService#checkAdminOrAbove} を必ず通す（二重防御の 1 段目・
 * 設計書 04 §2）。認可違反（COMMON_002）は縮退させず伝播させる。</p>
 *
 * <p><b>縮退（degradation・設計書 03 §4.3）</b>: 各ドメインを {@link CompletableFuture} で並行集計し、
 * 一時障害（{@link DataAccessException} / {@link TimeoutException}）のみ当該ドメインを
 * {@code pending_count=0・items=[]・degraded=true} に縮退し WARN ログ。{@code total_pending} に
 * 縮退分は加算しない（0 件と集計失敗を区別）。認可例外・プログラミングエラー（NPE 等）は
 * <b>握りつぶさず再スロー</b>する（CLAUDE.md 障害対応原則・症状を隠さない）。</p>
 *
 * <p><b>原則 5 遵守</b>: 読み取り集約のため {@code @Transactional} をドメイン跨ぎに張らない。
 * SQL は発行せず、各 Query Service の戻り値（{@link PendingAggregate}）をメモリ合成するのみ。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/03_admin_action_required_api.md §4</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminActionRequiredFacade {

    private final AccessControlService accessControlService;
    private final ReservationAdminQueryService reservationAdminQueryService;
    private final ShiftRequestAdminQueryService shiftRequestAdminQueryService;
    private final MatchingAdminQueryService matchingAdminQueryService;
    private final PaymentAdminQueryService paymentAdminQueryService;

    /**
     * 指定スコープの管理者向け横断「承認待ち」集約を取得する。
     *
     * @param userId      閲覧ユーザー ID（認可主体・パスの scopeId は信用せず本値で判定）
     * @param scopeType   スコープ種別（"TEAM" / "ORGANIZATION"）
     * @param scopeId     スコープ ID（slug 解決済みの内部 ID）
     * @param scopeSlug   スコープ slug（ルート文字列の組み立てに使用）
     * @param previewSize 各ドメインのプレビュー件数（0〜5・呼び出し側でバリデーション済み）
     * @return 横断承認待ち集約レスポンス
     */
    public AdminActionRequiredResponse getAdminActionRequired(
            Long userId, String scopeType, Long scopeId, String scopeSlug, int previewSize) {

        // ① 入口認可（二重防御の 1 段目）。違反は COMMON_002 で伝播（縮退しない）。
        accessControlService.checkAdminOrAbove(userId, scopeId, scopeType);

        // ② スコープ別に有効なドメインのタスクだけを構築（§3.2）。
        List<DomainTask> tasks = buildTasks(scopeType, scopeId, scopeSlug, previewSize);

        // ③ 各タスクを並行実行し、縮退ヘルパーで合成。
        List<CompletableFuture<AdminActionRequiredResponse.DomainSection>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(() -> buildSection(task)))
                .toList();

        List<AdminActionRequiredResponse.DomainSection> sections = new ArrayList<>();
        long totalPending = 0L;
        for (int i = 0; i < tasks.size(); i++) {
            AdminActionRequiredResponse.DomainSection section = join(futures.get(i), tasks.get(i));
            sections.add(section);
            // 縮退ドメインは total に加算しない（0 件と集計失敗を区別）。
            if (!section.degraded()) {
                totalPending += section.pendingCount();
            }
        }

        return AdminActionRequiredResponse.builder()
                .scopeType(scopeType)
                .scopeId(scopeId)
                .totalPending(totalPending)
                .domains(sections)
                .build();
    }

    // ─────────────────────────────────────────────
    // スコープ別ドメインタスク構築（§3.2）
    // ─────────────────────────────────────────────

    private List<DomainTask> buildTasks(String scopeType, Long scopeId, String scopeSlug, int previewSize) {
        if ("TEAM".equals(scopeType)) {
            return List.of(
                    new DomainTask("RESERVATION",
                            "/teams/" + scopeSlug + "/admin/reservations?status=PENDING",
                            () -> reservationAdminQueryService.pendingForTeam(scopeId, scopeSlug, previewSize)),
                    new DomainTask("SHIFT_REQUEST",
                            "/teams/" + scopeSlug + "/admin/shifts?tab=requests",
                            () -> shiftRequestAdminQueryService.pendingForTeam(scopeId, scopeSlug, previewSize)),
                    new DomainTask("MATCHING",
                            "/teams/" + scopeSlug + "/admin/matching?tab=received",
                            () -> matchingAdminQueryService.pendingReceivedForTeam(scopeId, scopeSlug, previewSize))
            );
        }
        if ("ORGANIZATION".equals(scopeType)) {
            return List.of(
                    new DomainTask("PAYMENT",
                            "/organizations/" + scopeSlug + "/admin/payments?status=UNSETTLED",
                            () -> paymentAdminQueryService.unsettledForOrg(scopeId, scopeSlug, previewSize))
            );
        }
        throw new IllegalArgumentException("未対応のスコープ種別です: " + scopeType);
    }

    private AdminActionRequiredResponse.DomainSection buildSection(DomainTask task) {
        PendingAggregate agg = task.supplier().get();
        // detail_route は要素ごとの個別遷移先（Query Service が id・slug を解決して設定済み）。
        // list_route（status 付き一覧）とは別物（設計書 03 §3.1 / §3.3）。
        List<AdminActionRequiredResponse.PreviewItem> items = agg.items().stream()
                .map(item -> AdminActionRequiredResponse.PreviewItem.builder()
                        .id(item.id())
                        .title(item.title())
                        .requestedBy(item.requestedBy())
                        .requestedAt(item.requestedAt())
                        .detailRoute(item.detailRoute())
                        .build())
                .toList();
        return AdminActionRequiredResponse.DomainSection.builder()
                .domain(task.domain())
                .pendingCount(agg.pendingCount())
                .degraded(false)
                .listRoute(task.listRoute())
                .items(items)
                .build();
    }

    // ─────────────────────────────────────────────
    // 縮退ヘルパー（一時障害のみ縮退・認可/バグは再スロー）
    // ─────────────────────────────────────────────

    private AdminActionRequiredResponse.DomainSection join(
            CompletableFuture<AdminActionRequiredResponse.DomainSection> future, DomainTask task) {
        try {
            return future.get();
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (isTransient(cause)) {
                // 一時障害のみ当該ドメインを縮退（degraded=true）。WARN ログ。
                log.warn("AdminActionRequiredFacade: domain '{}' の集計に一時障害が発生。"
                        + "当該ドメインを degraded=true に縮退します。", task.domain(), cause);
                return degradedSection(task);
            }
            // 認可例外（COMMON_002）・プログラミングエラー（NPE 等）は握りつぶさず再スロー。
            throw rethrow(cause);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("AdminActionRequiredFacade: domain '{}' の集計が中断されました。"
                    + "当該ドメインを degraded=true に縮退します。", task.domain(), ex);
            return degradedSection(task);
        }
    }

    /** 一時障害（DB 接続断・SQL タイムアウト・並行集計タイムアウト）か判定する。 */
    private boolean isTransient(Throwable cause) {
        if (cause == null) {
            return false;
        }
        if (cause instanceof DataAccessException || cause instanceof TimeoutException) {
            return true;
        }
        // CompletableFuture が原因をラップした CompletionException は中身を見て判定する。
        if (cause instanceof CompletionException && cause.getCause() != cause) {
            return isTransient(cause.getCause());
        }
        return false;
    }

    private RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RuntimeException re) {
            return re;
        }
        if (cause instanceof Error err) {
            throw err;
        }
        return new CompletionException(cause);
    }

    private AdminActionRequiredResponse.DomainSection degradedSection(DomainTask task) {
        return AdminActionRequiredResponse.DomainSection.builder()
                .domain(task.domain())
                .pendingCount(0L)
                .degraded(true)
                .listRoute(task.listRoute())
                .items(List.of())
                .build();
    }

    /** 1 ドメインの集約タスク（ドメイン名・一覧ルート・集計サプライヤ）。 */
    private record DomainTask(String domain, String listRoute, Supplier<PendingAggregate> supplier) {
    }
}
