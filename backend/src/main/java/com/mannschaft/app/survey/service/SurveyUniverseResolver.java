package com.mannschaft.app.survey.service;

import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.survey.DistributionMode;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.entity.SurveyTargetEntity;
import com.mannschaft.app.survey.repository.SurveyTargetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * アンケートの<b>母集団</b>（＝回答を期待する対象者集合）を解決する唯一の窓口。
 *
 * <p><b>なぜ独立クラスなのか</b>: 同じ母集団解決ロジックが
 * {@code SurveyResultService}（未回答者一覧・回答率の分母）、
 * {@code SurveyRemindService}（督促の宛先）、
 * {@code SurveyService}（締切延長通知の宛先）、
 * {@code SurveyPublishNotificationListener}（公開通知の宛先）に private として重複実装されており、
 * 公開処理（{@code publishSurvey}）から呼べなかった。その結果、設計書
 * {@code docs/features/F05.4_survey_vote.md} §1426-1428 が明記する
 * 「公開時に配信対象者数をスナップショットする」が全員配信（ALL）で実装漏れとなり、
 * {@code target_count} が永久に 0 のままだった（Issue #2787 / CMP-042）。</p>
 *
 * <p><b>定義の一致こそが本クラスの価値</b>: 公開時に数える母集団と、結果閲覧時・督促時に
 * 数える母集団が同一定義でなければ「分母と未回答者リストが別々の母集団を見る」食い違いが生じる。
 * 各サービスは自前で分岐せず、必ず本クラスを経由すること。</p>
 *
 * <h2>母集団の定義</h2>
 * <ul>
 *   <li>{@link DistributionMode#ALL} × {@code ORGANIZATION} →
 *       {@link OrganizationMembershipService#resolveOrgDistributionUserIds(Long, boolean)}
 *       （直属 ∪ 配下 ACTIVE チーム・再帰展開・応援者トグル準拠）</li>
 *   <li>{@link DistributionMode#ALL} × その他（TEAM / COMMITTEE 等） →
 *       {@link UserRoleRepository#findUserIdsByScope(String, Long)}
 *       （{@code user_roles} ∪ {@code memberships} の和集合。配下展開なし）</li>
 *   <li>{@link DistributionMode#TARGETED} → {@code survey_targets} 登録ユーザー</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class SurveyUniverseResolver {

    private final OrganizationMembershipService organizationMembershipService;
    private final UserRoleRepository userRoleRepository;
    private final SurveyTargetRepository targetRepository;

    /**
     * アンケートの母集団ユーザー ID を解決する。
     *
     * @param survey 対象アンケート
     * @return 母集団ユーザー ID リスト（重複なし）
     */
    public List<Long> resolveUniverseUserIds(SurveyEntity survey) {
        if (survey.getDistributionMode() == DistributionMode.ALL) {
            return resolveAllModeUserIds(
                    survey.getScopeType(),
                    survey.getScopeId(),
                    Boolean.TRUE.equals(survey.getIncludeSupporters()));
        }
        return targetRepository.findBySurveyId(survey.getId()).stream()
                .map(SurveyTargetEntity::getUserId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * {@code distribution_mode = ALL} の母集団をスコープから解決する。
     *
     * <p>アンケート実体を持たない経路（公開通知イベントのリスナー等）から呼ぶための入口。
     * 実体がある場合は {@link #resolveUniverseUserIds(SurveyEntity)} を使うこと。</p>
     *
     * @param scopeType         スコープ種別
     * @param scopeId           スコープ ID
     * @param includeSupporters 応援者を含めるか（組織スコープでのみ意味を持つ）
     * @return 配信対象ユーザー ID リスト
     */
    public List<Long> resolveAllModeUserIds(String scopeType, Long scopeId, boolean includeSupporters) {
        if ("ORGANIZATION".equals(scopeType)) {
            return organizationMembershipService.resolveOrgDistributionUserIds(scopeId, includeSupporters);
        }
        return userRoleRepository.findUserIdsByScope(scopeType, scopeId);
    }

    /**
     * 母集団の人数を数える（公開時の {@code target_count} スナップショット用）。
     *
     * <p>母集団の件数だけクエリが増える実装（N+1）にしてはならない。
     * TARGETED は {@code COUNT(*)} 1 本、ALL は母集団解決クエリ 1 系統で完結させる。</p>
     *
     * @param survey 対象アンケート
     * @return 母集団の人数
     */
    public int countUniverseUserIds(SurveyEntity survey) {
        if (survey.getDistributionMode() == DistributionMode.ALL) {
            return resolveAllModeUserIds(
                    survey.getScopeType(),
                    survey.getScopeId(),
                    Boolean.TRUE.equals(survey.getIncludeSupporters())).size();
        }
        return (int) targetRepository.countBySurveyId(survey.getId());
    }

    /**
     * 指定ユーザーが当該アンケートの母集団に属するかを判定する。
     *
     * <p>1 ユーザーの判定では母集団全件を取得せず EXISTS でコストを抑える。
     * 組織スコープは所属軸（応援者を含む）で判定し、配信トグル
     * （{@code includeSupporters}）とは別軸である点に注意すること。</p>
     *
     * @param survey 対象アンケート
     * @param userId 判定対象ユーザー ID
     * @return 母集団に属するなら true
     */
    public boolean isUserInUniverse(SurveyEntity survey, Long userId) {
        if (userId == null) {
            return false;
        }
        if (survey.getDistributionMode() == DistributionMode.ALL) {
            if ("ORGANIZATION".equals(survey.getScopeType())) {
                return organizationMembershipService.isUserInOrgDistributionUniverse(
                        survey.getScopeId(), userId);
            }
            return userRoleRepository.findUserIdsByScope(survey.getScopeType(), survey.getScopeId())
                    .contains(userId);
        }
        return targetRepository.existsBySurveyIdAndUserId(survey.getId(), userId);
    }
}
