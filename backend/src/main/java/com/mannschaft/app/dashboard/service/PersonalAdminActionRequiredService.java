package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.dto.AdminActionRequiredResponse;
import com.mannschaft.app.dashboard.dto.PersonalAdminActionRequiredResponse;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 個人ダッシュボード: 全チーム・組織横断「承認待ち」集計サービス（司令塔第二弾）。
 *
 * <p>複数チーム/組織を管理するユーザーが、自身が ADMIN/DEPUTY_ADMIN として管理する全スコープの
 * 承認待ちアイテム（予約承認/シフトリクエスト/マッチング応募/未収請求）をスコープ情報付きの
 * フラットリストで集約して返す。{@link PersonalActionRequiredService}（「私が回答/確認すべきこと」・
 * 全メンバー向け）を金型としつつ、認可対象は<b>ADMIN/DEPUTY_ADMIN スコープのみ</b>に絞る点が異なる。</p>
 *
 * <p><b>処理フロー:</b></p>
 * <ol>
 *   <li>{@link AccessControlService#findAdminOrAboveScopeIds} で ADMIN/DEPUTY_ADMIN として
 *       管理しているチーム・組織 ID のみを取得する（{@code user_roles} 由来・N+1 なし・AC-B1-1/AC-B1-5）</li>
 *   <li>{@link TeamService#getSlugsByIds} / {@link TeamService#getNamesByIds} でスコープメタ情報を
 *       バルク取得する（N+1 回避・Entity 直接参照禁止の D-1 境界遵守）</li>
 *   <li>各スコープに対して {@link AdminActionRequiredFacade#getAdminActionRequired} を
 *       {@link CompletableFuture} で並行実行する（per-scope facade が入口で
 *       {@code checkAdminOrAbove} を再検証する二重防御・AC-B1-1）</li>
 *   <li>取得結果を {@link PersonalAdminActionRequiredResponse.ActionItem} のフラットリストに変換して返す</li>
 *   <li><b>縮退設計（AC-B1-4）</b>: 各スコープのフューチャーが例外になっても、当該スコープのみ
 *       0 件に縮退し他スコープは正常に返す。例外は握り潰さずログに出す</li>
 * </ol>
 *
 * <p><b>認可設計（AC-B1-1・AC-B1-2）</b>: {@link AccessControlService#findAdminOrAboveScopeIds} が
 * ADMIN/DEPUTY_ADMIN のスコープのみを返すため、MEMBER 等それ未満のロールしか持たないスコープは
 * 自然に除外される（DEPUTY_ADMIN ちょうどは含む・MEMBER は含まない）。また
 * {@link AdminActionRequiredFacade#getAdminActionRequired} 内で per-scope
 * {@code checkAdminOrAbove} も行われるため二重防御となっている。</p>
 *
 * <p>{@code @Transactional} 不要（読み取り専用集計）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalAdminActionRequiredService {

    /** per-scope 承認待ちプレビューの取得件数（アイテム一覧のプレビュー上限。件数バッジは totalPending が別途正確な値を持つ）。 */
    private static final int PREVIEW_SIZE = 3;

    private final AccessControlService accessControlService;
    private final AdminActionRequiredFacade adminActionRequiredFacade;
    /** チームの slug / name バルク解決用（D-1: Entity 直接参照禁止のため Service 経由）。 */
    private final TeamService teamService;
    /** 組織の slug / name バルク解決用（D-1: Entity 直接参照禁止のため Service 経由）。 */
    private final OrganizationService organizationService;

    /**
     * 指定ユーザーが ADMIN/DEPUTY_ADMIN として管理する全スコープ横断「承認待ち」集計を取得する。
     *
     * @param userId 閲覧ユーザー ID
     * @return 全管理スコープの承認待ちアイテムフラットリストと実合計件数
     */
    public PersonalAdminActionRequiredResponse getPersonalAdminActionRequired(Long userId) {
        // ADMIN/DEPUTY_ADMIN として管理するチーム・組織の ID 一覧のみを取得する（AC-B1-1/AC-B1-2）。
        Set<Long> teamIds = accessControlService.findAdminOrAboveScopeIds(userId, "TEAM");
        Set<Long> orgIds = accessControlService.findAdminOrAboveScopeIds(userId, "ORGANIZATION");

        // スコープメタ情報（slug/name）をバルク取得する（N+1 回避）
        Map<Long, String> teamSlugs = teamService.getSlugsByIds(teamIds);
        Map<Long, String> teamNames = teamService.getNamesByIds(teamIds);
        Map<Long, String> orgSlugs = organizationService.getSlugsByIds(orgIds);
        Map<Long, String> orgNames = organizationService.getNamesByIds(orgIds);

        // 各スコープの集計を CompletableFuture で並行実行する
        List<CompletableFuture<ScopeResult>> futures = new ArrayList<>();

        for (Long teamId : teamIds) {
            String slug = teamSlugs.get(teamId);
            String name = teamNames.get(teamId);
            if (slug == null) {
                // 削除済み・不明なチームはスキップする
                log.debug("PersonalAdminActionRequiredService: チーム id={} のスラッグが取得できないためスキップします", teamId);
                continue;
            }
            Long capturedTeamId = teamId;
            String capturedSlug = slug;
            String capturedName = name;
            futures.add(CompletableFuture.supplyAsync(() ->
                    fetchAndConvert(userId, "TEAM", capturedTeamId, capturedSlug, capturedName)));
        }

        for (Long orgId : orgIds) {
            String slug = orgSlugs.get(orgId);
            String name = orgNames.get(orgId);
            if (slug == null) {
                log.debug("PersonalAdminActionRequiredService: 組織 id={} のスラッグが取得できないためスキップします", orgId);
                continue;
            }
            Long capturedOrgId = orgId;
            String capturedSlug = slug;
            String capturedName = name;
            futures.add(CompletableFuture.supplyAsync(() ->
                    fetchAndConvert(userId, "ORGANIZATION", capturedOrgId, capturedSlug, capturedName)));
        }

        // 結果を集約する（縮退: 例外は当該スコープのみ0件にして継続）
        List<PersonalAdminActionRequiredResponse.ActionItem> allItems = new ArrayList<>();
        long totalPending = 0L;
        for (CompletableFuture<ScopeResult> future : futures) {
            ScopeResult result = joinWithDegradation(future);
            allItems.addAll(result.items());
            totalPending += result.pendingCount();
        }

        return new PersonalAdminActionRequiredResponse(allItems, totalPending);
    }

    /**
     * 指定スコープの「承認待ち」集計を取得し、{@link PersonalAdminActionRequiredResponse.ActionItem} の
     * リストと実合計件数に変換する。
     */
    private ScopeResult fetchAndConvert(
            Long userId, String scopeType, Long scopeId, String scopeSlug, String scopeName) {

        // AdminActionRequiredFacade で集計を取得する（既存の per-scope 集計を再利用・二重防御）
        AdminActionRequiredResponse summary =
                adminActionRequiredFacade.getAdminActionRequired(userId, scopeType, scopeId, scopeSlug, PREVIEW_SIZE);

        List<PersonalAdminActionRequiredResponse.ActionItem> items = new ArrayList<>();
        for (AdminActionRequiredResponse.DomainSection section : summary.domains()) {
            for (AdminActionRequiredResponse.PreviewItem item : section.items()) {
                items.add(new PersonalAdminActionRequiredResponse.ActionItem(
                        section.domain(),
                        scopeType,
                        scopeId,
                        scopeSlug,
                        scopeName,
                        item.id(),
                        item.title(),
                        item.requestedBy(),
                        item.requestedAt(),
                        item.detailRoute()
                ));
            }
        }

        return new ScopeResult(items, summary.totalPending());
    }

    /**
     * CompletableFuture を join し、例外が発生した場合は空の集計に縮退する。
     *
     * <p>AC-B1-4 の縮退要件に従い、1 スコープの失敗が全体の集計を停止させないようにする。
     * 例外は握り潰さずログに記録する（対処療法禁止）。</p>
     */
    private ScopeResult joinWithDegradation(CompletableFuture<ScopeResult> future) {
        try {
            return future.get();
        } catch (ExecutionException ex) {
            log.warn("PersonalAdminActionRequiredService: スコープ集計に失敗。当該スコープを 0 件に縮退します。",
                    ex.getCause() != null ? ex.getCause() : ex);
            return ScopeResult.EMPTY;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("PersonalAdminActionRequiredService: スコープ集計が中断されました。0 件に縮退します。", ex);
            return ScopeResult.EMPTY;
        }
    }

    /** 1 スコープ分の変換結果（アイテムフラットリスト＋実合計件数）を束ねる内部値オブジェクト。 */
    private record ScopeResult(List<PersonalAdminActionRequiredResponse.ActionItem> items, long pendingCount) {
        static final ScopeResult EMPTY = new ScopeResult(List.of(), 0L);
    }
}
