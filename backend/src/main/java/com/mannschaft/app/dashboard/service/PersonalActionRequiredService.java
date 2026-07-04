package com.mannschaft.app.dashboard.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.dashboard.dto.ActionRequiredSummaryResponse;
import com.mannschaft.app.dashboard.dto.PersonalActionRequiredResponse;
import com.mannschaft.app.organization.service.OrganizationService;
import com.mannschaft.app.team.service.TeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 個人ダッシュボード: 全チーム・組織横断「要対応」集計サービス。
 *
 * <p>ユーザーが所属する全チーム・全組織の未処理アイテム（回覧板/アンケート/出席確認）を
 * スコープ情報付きのフラットリストで集計して返す。</p>
 *
 * <p><b>処理フロー:</b></p>
 * <ol>
 *   <li>{@link AccessControlService#findAffiliatedScopeIds} で所属チーム・組織 ID を取得する
 *       （{@code user_roles} ∪ {@code memberships} の和集合）</li>
 *   <li>{@link TeamService#getSlugsByIds} / {@link TeamService#getNamesByIds} でスコープメタ情報を
 *       バルク取得する（N+1 回避・Entity 直接参照禁止の D-1 境界遵守）</li>
 *   <li>各スコープに対して {@link ScopeActionRequiredFacade#getActionRequired} を
 *       {@link CompletableFuture} で並行実行する</li>
 *   <li>取得結果を {@link PersonalActionRequiredResponse.ActionItem} のフラットリストに変換して返す</li>
 *   <li><b>縮退設計（AC-12）</b>: 各スコープのフューチャーが例外になっても、当該スコープのみ
 *       0 件に縮退し他スコープは正常に返す。例外は握り潰さずログに出す</li>
 * </ol>
 *
 * <p><b>認可設計（AC-13）</b>: {@link AccessControlService#findAffiliatedScopeIds} が所属スコープのみを
 * 返すため、非所属スコープのデータは自然に除外される。また {@link ScopeActionRequiredFacade} 内で
 * per-scope 所属検証も行われるため二重防御となっている。</p>
 *
 * <p>{@code @Transactional} 不要（読み取り専用集計）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalActionRequiredService {

    private final AccessControlService accessControlService;
    private final ScopeActionRequiredFacade scopeActionRequiredFacade;
    /** チームの slug / name バルク解決用（D-1: Entity 直接参照禁止のため Service 経由）。 */
    private final TeamService teamService;
    /** 組織の slug / name バルク解決用（D-1: Entity 直接参照禁止のため Service 経由）。 */
    private final OrganizationService organizationService;

    /**
     * 指定ユーザーの全スコープ横断「要対応」集計を取得する。
     *
     * @param userId 閲覧ユーザー ID
     * @return 全スコープの要対応アイテムフラットリストと合計件数
     */
    public PersonalActionRequiredResponse getPersonalActionRequired(Long userId) {
        // 所属チーム・組織の ID 一覧を取得する（user_roles ∪ memberships の和集合）
        Set<Long> teamIds = accessControlService.findAffiliatedScopeIds(userId, "TEAM");
        Set<Long> orgIds = accessControlService.findAffiliatedScopeIds(userId, "ORGANIZATION");

        // スコープメタ情報（slug/name）をバルク取得する（N+1 回避）
        Map<Long, String> teamSlugs = teamService.getSlugsByIds(teamIds);
        Map<Long, String> teamNames = teamService.getNamesByIds(teamIds);
        Map<Long, String> orgSlugs = organizationService.getSlugsByIds(orgIds);
        Map<Long, String> orgNames = organizationService.getNamesByIds(orgIds);

        // 各スコープの集計を CompletableFuture で並行実行する
        List<CompletableFuture<List<PersonalActionRequiredResponse.ActionItem>>> futures = new ArrayList<>();

        for (Long teamId : teamIds) {
            String slug = teamSlugs.get(teamId);
            String name = teamNames.get(teamId);
            if (slug == null) {
                // 削除済み・不明なチームはスキップする
                log.debug("PersonalActionRequiredService: チーム id={} のスラッグが取得できないためスキップします", teamId);
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
                log.debug("PersonalActionRequiredService: 組織 id={} のスラッグが取得できないためスキップします", orgId);
                continue;
            }
            Long capturedOrgId = orgId;
            String capturedSlug = slug;
            String capturedName = name;
            futures.add(CompletableFuture.supplyAsync(() ->
                    fetchAndConvert(userId, "ORGANIZATION", capturedOrgId, capturedSlug, capturedName)));
        }

        // 結果を集約する（縮退: 例外は当該スコープのみ0件にして継続）
        List<PersonalActionRequiredResponse.ActionItem> allItems = new ArrayList<>();
        for (CompletableFuture<List<PersonalActionRequiredResponse.ActionItem>> future : futures) {
            List<PersonalActionRequiredResponse.ActionItem> items = joinWithDegradation(future);
            allItems.addAll(items);
        }

        return new PersonalActionRequiredResponse(allItems, allItems.size());
    }

    /**
     * 指定スコープの「要対応」集計を取得し、{@link PersonalActionRequiredResponse.ActionItem} のリストに変換する。
     */
    private List<PersonalActionRequiredResponse.ActionItem> fetchAndConvert(
            Long userId, String scopeType, Long scopeId, String scopeSlug, String scopeName) {

        // ScopeActionRequiredFacade で集計を取得する（既存の per-scope 集計を再利用）
        ActionRequiredSummaryResponse summary =
                scopeActionRequiredFacade.getActionRequired(userId, scopeType, scopeId);

        // フラットリストに変換する
        List<PersonalActionRequiredResponse.ActionItem> items = new ArrayList<>();

        // 回覧板アイテム（直近 RECENT_ITEM_LIMIT 件）
        for (ActionRequiredSummaryResponse.CirculationItem c : summary.circulation().items()) {
            // CirculationItem.deadline は LocalDate → LocalDateTime に変換する（null-safe）
            LocalDateTime deadlineAt = c.deadline() != null ? c.deadline().atStartOfDay() : null;
            items.add(new PersonalActionRequiredResponse.ActionItem(
                    "CIRCULATION",
                    scopeType,
                    scopeId,
                    scopeSlug,
                    scopeName,
                    c.id() != null ? String.valueOf(c.id()) : null,
                    c.title(),
                    deadlineAt,
                    null  // startsAt は circulation では使用しない
            ));
        }

        // アンケートアイテム
        for (ActionRequiredSummaryResponse.SurveyItem s : summary.survey().items()) {
            items.add(new PersonalActionRequiredResponse.ActionItem(
                    "SURVEY",
                    scopeType,
                    scopeId,
                    scopeSlug,
                    scopeName,
                    s.id() != null ? String.valueOf(s.id()) : null,
                    s.title(),
                    s.deadline(),
                    null  // startsAt は survey では使用しない
            ));
        }

        // 出席確認アイテム
        for (ActionRequiredSummaryResponse.AttendanceItem a : summary.attendance().items()) {
            items.add(new PersonalActionRequiredResponse.ActionItem(
                    "ATTENDANCE",
                    scopeType,
                    scopeId,
                    scopeSlug,
                    scopeName,
                    a.scheduleId() != null ? String.valueOf(a.scheduleId()) : null,
                    a.eventTitle(),
                    null,          // deadline は attendance では使用しない
                    a.startsAt()
            ));
        }

        return items;
    }

    /**
     * CompletableFuture を join し、例外が発生した場合は空リストを返す（縮退処理）。
     *
     * <p>AC-12 の縮退要件に従い、1 スコープの失敗が全体の集計を停止させないようにする。
     * 例外は握り潰さずログに記録する（対処療法禁止）。</p>
     */
    private List<PersonalActionRequiredResponse.ActionItem> joinWithDegradation(
            CompletableFuture<List<PersonalActionRequiredResponse.ActionItem>> future) {
        try {
            return future.get();
        } catch (ExecutionException ex) {
            log.warn("PersonalActionRequiredService: スコープ集計に失敗。当該スコープを 0 件に縮退します。",
                    ex.getCause() != null ? ex.getCause() : ex);
            return List.of();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("PersonalActionRequiredService: スコープ集計が中断されました。0 件に縮退します。", ex);
            return List.of();
        }
    }
}
