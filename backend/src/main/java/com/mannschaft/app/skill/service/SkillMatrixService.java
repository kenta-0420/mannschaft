package com.mannschaft.app.skill.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.role.repository.UserRoleRepository;
import com.mannschaft.app.skill.SkillStatus;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.entity.SkillCategoryEntity;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import com.mannschaft.app.skill.repository.SkillCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * スキルマトリックス取得サービス。
 * スコープ内全メンバー × 全カテゴリのピボット形式データを構築する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SkillMatrixService {

    private final SkillCategoryRepository categoryRepository;
    private final MemberSkillRepository memberSkillRepository;
    private final UserRoleRepository userRoleRepository;

    /**
     * スコープ内のスキルマトリックスを取得する。
     * <p>
     * 列定義: スコープ内のアクティブカテゴリ一覧<br>
     * 行定義: スコープ内の全メンバー（UserRoleから取得）<br>
     * セル: ACTIVE または EXPIRED の資格（PENDING_REVIEW 除外）。
     *       同一カテゴリで複数資格ある場合は ACTIVE 優先、次に expires_at DESC で1件を代表選出。
     * </p>
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return ピボット形式のマトリックスデータ（Map形式）
     */
    public ApiResponse<Map<String, Object>> getMatrix(String scopeType, Long scopeId) {

        // 列定義: アクティブカテゴリ一覧
        List<SkillCategoryEntity> categories =
                categoryRepository.findByScopeTypeAndScopeIdAndIsActiveTrueAndDeletedAtIsNull(
                        scopeType, scopeId);

        // 行定義: スコープ内の全メンバーのユーザーID一覧
        List<Long> memberUserIds = resolveMemberUserIds(scopeType, scopeId);

        // スコープ内の全 member_skills（deleted_at IS NULL）を取得
        // ACTIVE / EXPIRED のみ（PENDING_REVIEW 除外）
        List<MemberSkillEntity> allSkills = memberSkillRepository.findAll().stream()
                .filter(s -> s.getScopeType().equals(scopeType)
                        && s.getScopeId().equals(scopeId)
                        && s.getDeletedAt() == null
                        && (s.getStatus() == SkillStatus.ACTIVE || s.getStatus() == SkillStatus.EXPIRED))
                .toList();

        // ユーザーID → カテゴリID → 代表スキル のマップを構築
        // 同一カテゴリで複数資格 → ACTIVE 優先、expires_at DESC で1件
        Map<Long, Map<Long, MemberSkillEntity>> skillMap = new HashMap<>();
        for (MemberSkillEntity skill : allSkills) {
            if (skill.getSkillCategoryId() == null) continue;
            skillMap
                    .computeIfAbsent(skill.getUserId(), k -> new HashMap<>())
                    .merge(skill.getSkillCategoryId(), skill, (existing, candidate) ->
                            selectRepresentativeSkill(existing, candidate));
        }

        // ピボット形式に変換
        List<Map<String, Object>> columns = categories.stream()
                .map(c -> {
                    Map<String, Object> col = new LinkedHashMap<>();
                    col.put("categoryId", c.getId());
                    col.put("categoryName", c.getName());
                    col.put("icon", c.getIcon());
                    col.put("sortOrder", c.getSortOrder());
                    return col;
                })
                .toList();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long userId : memberUserIds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", userId);
            Map<Long, MemberSkillEntity> userSkills = skillMap.getOrDefault(userId, Map.of());
            List<Map<String, Object>> cells = new ArrayList<>();
            for (SkillCategoryEntity category : categories) {
                MemberSkillEntity representative = userSkills.get(category.getId());
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("categoryId", category.getId());
                if (representative != null) {
                    cell.put("memberSkillId", representative.getId());
                    cell.put("status", representative.getStatus().name());
                    cell.put("expiresAt", representative.getExpiresAt());
                    cell.put("verifiedAt", representative.getVerifiedAt());
                } else {
                    cell.put("memberSkillId", null);
                    cell.put("status", null);
                    cell.put("expiresAt", null);
                    cell.put("verifiedAt", null);
                }
                cells.add(cell);
            }
            row.put("cells", cells);
            rows.add(row);
        }

        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("scopeType", scopeType);
        matrix.put("scopeId", scopeId);
        matrix.put("columns", columns);
        matrix.put("rows", rows);

        log.info("スキルマトリックス取得: scope={}/{}, categories={}, members={}",
                scopeType, scopeId, categories.size(), memberUserIds.size());
        return ApiResponse.of(matrix);
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * スコープ内のメンバーユーザーIDリストを返す。
     */
    private List<Long> resolveMemberUserIds(String scopeType, Long scopeId) {
        if ("TEAM".equalsIgnoreCase(scopeType)) {
            return userRoleRepository.findByTeamId(scopeId,
                            org.springframework.data.domain.Pageable.unpaged())
                    .getContent()
                    .stream()
                    .map(UserRoleEntity::getUserId)
                    .distinct()
                    .toList();
        } else {
            return userRoleRepository.findByOrganizationId(scopeId,
                            org.springframework.data.domain.Pageable.unpaged())
                    .getContent()
                    .stream()
                    .map(UserRoleEntity::getUserId)
                    .distinct()
                    .toList();
        }
    }

    /**
     * 同一カテゴリで複数資格がある場合に代表を選出する。
     * ACTIVE 優先、次に expires_at DESC で1件。
     */
    private MemberSkillEntity selectRepresentativeSkill(
            MemberSkillEntity a, MemberSkillEntity b) {

        // ACTIVE 優先
        if (a.getStatus() == SkillStatus.ACTIVE && b.getStatus() != SkillStatus.ACTIVE) {
            return a;
        }
        if (b.getStatus() == SkillStatus.ACTIVE && a.getStatus() != SkillStatus.ACTIVE) {
            return b;
        }

        // 同一ステータスの場合は expires_at DESC（nullは末尾）
        if (a.getExpiresAt() == null && b.getExpiresAt() == null) return a;
        if (a.getExpiresAt() == null) return b;
        if (b.getExpiresAt() == null) return a;
        return a.getExpiresAt().isAfter(b.getExpiresAt()) ? a : b;
    }
}
