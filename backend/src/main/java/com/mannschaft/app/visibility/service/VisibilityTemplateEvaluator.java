package com.mannschaft.app.visibility.service;

import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.social.repository.TeamFriendRepository;
import com.mannschaft.app.visibility.entity.VisibilityTemplateRuleEntity;
import com.mannschaft.app.visibility.repository.VisibilityTemplateRepository;
import com.mannschaft.app.visibility.repository.VisibilityTemplateRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * F01.7 カスタム公開範囲テンプレート評価サービス。
 *
 * <p>閲覧者がテンプレートの各ルールを満たすか OR 結合で判定する。
 * ルール一覧は {@code visibilityTemplate} キャッシュに TTL=5分でキャッシュされる。</p>
 *
 * <h3>プレースホルダ解決方針</h3>
 * <ul>
 *   <li>{@code @USER_PRIMARY_TEAM}: ユーザーの全チームメンバーシップから最小 team_id を primary とする（F01.2 完全実装前の暫定）</li>
 *   <li>{@code @USER_PRIMARY_REGION}: F01.2 region 実装が未完のため解決失敗 → false フォールバック（WARN ログ出力）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisibilityTemplateEvaluator {

    private final VisibilityTemplateRepository visibilityTemplateRepository;
    private final VisibilityTemplateRuleRepository visibilityTemplateRuleRepository;
    private final UserRoleRepository userRoleRepository;
    private final TeamFriendRepository teamFriendRepository;

    /**
     * viewer がこのテンプレートを通じて対象を閲覧可能か判定する（OR結合・short-circuit）。
     *
     * <p>ルール評価は OR 結合のため、1つでも {@code true} になった時点で即座に {@code true} を返す。
     * テンプレートが存在しない場合は {@code false} にフォールバックする。</p>
     *
     * @param viewerUserId 閲覧者のユーザーID
     * @param templateId   評価対象テンプレートID
     * @param ownerUserId  テンプレート所有者のユーザーID（プレースホルダ解決に使用）
     * @return 閲覧可能な場合 true
     */
    @Transactional(readOnly = true)
    public boolean canView(Long viewerUserId, Long templateId, Long ownerUserId) {
        // テンプレート存在確認（存在しない場合は false フォールバック）
        boolean exists = visibilityTemplateRepository.existsById(templateId);
        if (!exists) {
            log.warn("canView: テンプレートが存在しない templateId={}", templateId);
            return false;
        }

        // ルール取得（キャッシュ経由）
        List<VisibilityTemplateRuleEntity> rules = getTemplateRules(templateId);
        if (rules.isEmpty()) {
            return false;
        }

        // OR結合で評価（short-circuit）
        for (VisibilityTemplateRuleEntity rule : rules) {
            if (evaluateRule(rule, viewerUserId, ownerUserId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * テンプレートを適用した場合の対象ユーザーID全員を返す（プレビュー用）。
     *
     * <p>各ルールで解決されるユーザーID集合を OR 結合（和集合）で返す。
     * 計算コストが高いため、プレビュー用途に限定すること。</p>
     *
     * @param templateId  評価対象テンプレートID
     * @param ownerUserId テンプレート所有者のユーザーID
     * @return テンプレートに該当する全ユーザーID集合
     */
    @Transactional(readOnly = true)
    public Set<Long> resolveMemberUserIds(Long templateId, Long ownerUserId) {
        List<VisibilityTemplateRuleEntity> rules = getTemplateRules(templateId);
        Set<Long> memberUserIds = new HashSet<>();

        for (VisibilityTemplateRuleEntity rule : rules) {
            Set<Long> ruleUserIds = resolveRuleUserIds(rule, ownerUserId);
            memberUserIds.addAll(ruleUserIds);
        }

        return memberUserIds;
    }

    /**
     * テンプレートのルール一覧を取得する（キャッシュ付き、TTL=5分）。
     *
     * @param templateId テンプレートID
     * @return ルール一覧（表示順序昇順）
     */
    @Cacheable(value = "visibilityTemplate", key = "#templateId")
    public List<VisibilityTemplateRuleEntity> getTemplateRules(Long templateId) {
        return visibilityTemplateRuleRepository.findByTemplateIdOrderBySortOrderAsc(templateId);
    }

    /**
     * テンプレートのキャッシュを削除する。
     * テンプレート更新・削除時に {@link VisibilityTemplateService} から呼び出すこと。
     *
     * @param templateId 削除するキャッシュのテンプレートID
     */
    @CacheEvict(value = "visibilityTemplate", key = "#templateId")
    public void evictTemplateCache(Long templateId) {
        log.debug("visibilityTemplate キャッシュ削除: templateId={}", templateId);
    }

    // ============================
    // private ルール評価メソッド
    // ============================

    /**
     * 1つのルールを評価する。
     *
     * @param rule         評価するルール
     * @param viewerUserId 閲覧者のユーザーID
     * @param ownerUserId  テンプレート所有者のユーザーID
     * @return ルールを満たす場合 true
     */
    private boolean evaluateRule(VisibilityTemplateRuleEntity rule, Long viewerUserId, Long ownerUserId) {
        return switch (rule.getRuleType()) {
            case EXPLICIT_USER ->
                // 明示指定ユーザー: viewer.id == rule_target_id
                rule.getRuleTargetId() != null
                        && rule.getRuleTargetId().equals(viewerUserId);

            case EXPLICIT_TEAM ->
                // 明示指定チームのメンバー: viewer が該当チームに所属しているか
                rule.getRuleTargetId() != null
                        && userRoleRepository.existsByUserIdAndTeamId(viewerUserId, rule.getRuleTargetId());

            case EXPLICIT_SOCIAL_PROFILE -> {
                // social_profile の確認: 現時点では viewer.id == rule_target_id で暫定
                // （social_profile_id = user_id の想定。F01.2 完全実装後に再設計予定）
                yield rule.getRuleTargetId() != null
                        && rule.getRuleTargetId().equals(viewerUserId);
            }

            case TEAM_MEMBER_OF ->
                // 指定チームのメンバー: viewer が該当チームに所属しているか
                rule.getRuleTargetId() != null
                        && userRoleRepository.existsByUserIdAndTeamId(viewerUserId, rule.getRuleTargetId());

            case ORGANIZATION_MEMBER_OF ->
                // 指定組織のメンバー: viewer が該当組織に所属しているか
                rule.getRuleTargetId() != null
                        && userRoleRepository.existsByUserIdAndOrganizationId(viewerUserId, rule.getRuleTargetId());

            case TEAM_FRIEND_OF ->
                // フレンドチームのメンバー（プレースホルダ解決含む）
                evaluateTeamFriendOf(rule, viewerUserId, ownerUserId);

            case REGION_MATCH -> {
                // F01.2 region 実装が未完のためフォールバック（false）
                log.warn("REGION_MATCH ルール評価スキップ（F01.2 未実装）: templateId={}, ruleId={}",
                        rule.getTemplate().getId(), rule.getId());
                yield false;
            }
        };
    }

    /**
     * TEAM_FRIEND_OF ルールを評価する。
     *
     * <p>{@code rule_target_text = '@USER_PRIMARY_TEAM'} の場合は、
     * オーナーの primary チーム（最小 team_id）を解決してから評価する。</p>
     *
     * @param rule         評価するルール
     * @param viewerUserId 閲覧者のユーザーID
     * @param ownerUserId  テンプレート所有者のユーザーID（プレースホルダ解決に使用）
     * @return フレンドチームのメンバーである場合 true
     */
    private boolean evaluateTeamFriendOf(VisibilityTemplateRuleEntity rule, Long viewerUserId, Long ownerUserId) {
        Long targetTeamId;

        if ("@USER_PRIMARY_TEAM".equals(rule.getRuleTargetText())) {
            // オーナーの primary チームを解決（最小 team_id を primary とする暫定実装）
            targetTeamId = userRoleRepository.findByUserIdAndTeamIdIsNotNull(ownerUserId)
                    .stream()
                    .map(UserRoleEntity::getTeamId)
                    .min(Long::compareTo)
                    .orElse(null);
            if (targetTeamId == null) {
                log.warn("@USER_PRIMARY_TEAM 解決失敗: ownerUserId={}", ownerUserId);
                return false;
            }
        } else {
            targetTeamId = rule.getRuleTargetId();
            if (targetTeamId == null) {
                return false;
            }
        }

        // viewer が属するチームを取得
        List<Long> viewerTeamIds = userRoleRepository.findByUserIdAndTeamIdIsNotNull(viewerUserId)
                .stream()
                .map(UserRoleEntity::getTeamId)
                .toList();
        if (viewerTeamIds.isEmpty()) {
            return false;
        }

        // viewer のいずれかのチームが targetTeam のフレンドかどうかを確認
        // TeamFriendRepository は teamAId < teamBId で正規化されているため、min/max で検索
        final Long finalTargetTeamId = targetTeamId;
        return viewerTeamIds.stream().anyMatch(viewerTeamId -> {
            long minId = Math.min(viewerTeamId, finalTargetTeamId);
            long maxId = Math.max(viewerTeamId, finalTargetTeamId);
            return teamFriendRepository.findByTeamAIdAndTeamBId(minId, maxId).isPresent();
        });
    }

    /**
     * ルールに該当するユーザーID集合を解決する（resolveMemberUserIds 用）。
     *
     * @param rule        評価するルール
     * @param ownerUserId テンプレート所有者のユーザーID
     * @return ルールに該当するユーザーID集合
     */
    private Set<Long> resolveRuleUserIds(VisibilityTemplateRuleEntity rule, Long ownerUserId) {
        Set<Long> result = new HashSet<>();

        switch (rule.getRuleType()) {
            case EXPLICIT_USER -> {
                if (rule.getRuleTargetId() != null) {
                    result.add(rule.getRuleTargetId());
                }
            }
            case EXPLICIT_SOCIAL_PROFILE -> {
                // 暫定: social_profile_id = user_id の想定
                if (rule.getRuleTargetId() != null) {
                    result.add(rule.getRuleTargetId());
                }
            }
            case EXPLICIT_TEAM, TEAM_MEMBER_OF -> {
                if (rule.getRuleTargetId() != null) {
                    List<Long> userIds = userRoleRepository.findUserIdsByScope("TEAM", rule.getRuleTargetId());
                    result.addAll(userIds);
                }
            }
            case ORGANIZATION_MEMBER_OF -> {
                if (rule.getRuleTargetId() != null) {
                    List<Long> userIds = userRoleRepository.findUserIdsByScope("ORGANIZATION", rule.getRuleTargetId());
                    result.addAll(userIds);
                }
            }
            case TEAM_FRIEND_OF -> {
                // フレンドチームメンバーの全ユーザーを解決
                Long targetTeamId = resolveTargetTeamId(rule, ownerUserId);
                if (targetTeamId != null) {
                    // targetTeam のフレンドチームメンバーを取得（N+1 回避のため直接クエリ）
                    List<Long> memberIds = userRoleRepository.findUserIdsByScope("TEAM", targetTeamId);
                    result.addAll(memberIds);
                }
            }
            case REGION_MATCH -> {
                // F01.2 未実装のためスキップ
                log.warn("REGION_MATCH ルール resolveMemberUserIds スキップ（F01.2 未実装）: templateId={}, ruleId={}",
                        rule.getTemplate().getId(), rule.getId());
            }
        }

        return result;
    }

    /**
     * ルールの対象チームIDを解決する。
     * {@code @USER_PRIMARY_TEAM} プレースホルダの場合はオーナーの primary チームに変換する。
     *
     * @param rule        評価するルール
     * @param ownerUserId テンプレート所有者のユーザーID
     * @return 解決されたチームID（解決できない場合は null）
     */
    private Long resolveTargetTeamId(VisibilityTemplateRuleEntity rule, Long ownerUserId) {
        if ("@USER_PRIMARY_TEAM".equals(rule.getRuleTargetText())) {
            Long primaryTeamId = userRoleRepository.findByUserIdAndTeamIdIsNotNull(ownerUserId)
                    .stream()
                    .map(UserRoleEntity::getTeamId)
                    .min(Long::compareTo)
                    .orElse(null);
            if (primaryTeamId == null) {
                log.warn("@USER_PRIMARY_TEAM 解決失敗: ownerUserId={}", ownerUserId);
            }
            return primaryTeamId;
        }
        return rule.getRuleTargetId();
    }
}
