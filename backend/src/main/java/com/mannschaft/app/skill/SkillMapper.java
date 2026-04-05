package com.mannschaft.app.skill;

import com.mannschaft.app.skill.dto.MemberSkillResponse;
import com.mannschaft.app.skill.dto.SkillCategoryResponse;
import com.mannschaft.app.skill.dto.SkillMatrixCellResponse;
import com.mannschaft.app.skill.dto.SkillMatrixRowResponse;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.entity.SkillCategoryEntity;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * スキル・資格ドメインのエンティティ → DTO 変換マッパー。
 */
@Component
public class SkillMapper {

    /**
     * SkillCategoryEntity を SkillCategoryResponse に変換する。
     */
    public SkillCategoryResponse toResponse(SkillCategoryEntity entity) {
        return new SkillCategoryResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getIcon(),
                entity.getSortOrder() != null ? entity.getSortOrder() : 0,
                Boolean.TRUE.equals(entity.getIsActive()),
                entity.getCreatedAt()
        );
    }

    /**
     * MemberSkillEntity を MemberSkillResponse に変換する。
     *
     * @param entity       メンバースキルエンティティ
     * @param categoryName カテゴリ名（カテゴリが存在しない場合はnull可）
     */
    public MemberSkillResponse toResponse(MemberSkillEntity entity, String categoryName) {
        return new MemberSkillResponse(
                entity.getId(),
                entity.getSkillCategoryId(),
                categoryName,
                entity.getUserId(),
                entity.getScopeType(),
                entity.getScopeId(),
                entity.getName(),
                entity.getIssuer(),
                entity.getCredentialNumber(),
                entity.getAcquiredOn(),
                entity.getExpiresAt(),
                entity.getStatus(),
                entity.getCertificateS3Key() != null && !entity.getCertificateS3Key().isBlank(),
                entity.getVerifiedAt(),
                entity.getVerifiedBy(),
                entity.getVersion(),
                entity.getCreatedAt()
        );
    }

    /**
     * SkillCategoryEntity リストを SkillCategoryResponse リストに変換する。
     */
    public List<SkillCategoryResponse> toCategoryResponseList(List<SkillCategoryEntity> entities) {
        return entities.stream()
                .map(this::toResponse)
                .toList();
    }

    /**
     * MemberSkillEntity リストを MemberSkillResponse リストに変換する。
     * カテゴリ名マップを利用して categoryName を解決する。
     *
     * @param entities      メンバースキルエンティティリスト
     * @param categoryNames カテゴリIDをキーとするカテゴリ名マップ
     */
    public List<MemberSkillResponse> toSkillResponseList(
            List<MemberSkillEntity> entities, Map<Long, String> categoryNames) {
        return entities.stream()
                .map(e -> toResponse(e,
                        e.getSkillCategoryId() != null
                                ? categoryNames.getOrDefault(e.getSkillCategoryId(), null)
                                : null))
                .toList();
    }

    /**
     * SkillMatrixService が返す Map<String, Object> 形式のマトリックスデータを
     * SkillMatrixRowResponse リストに変換する。
     *
     * @param matrixData SkillMatrixService.getMatrix() が返すデータ
     * @return 行レスポンスリスト
     */
    @SuppressWarnings("unchecked")
    public List<SkillMatrixRowResponse> toMatrixRowResponseList(Map<String, Object> matrixData) {
        List<Map<String, Object>> rawRows =
                (List<Map<String, Object>>) matrixData.getOrDefault("rows", List.of());
        List<Map<String, Object>> rawColumns =
                (List<Map<String, Object>>) matrixData.getOrDefault("columns", List.of());

        // 列順（categoryId のリスト）を保持
        List<Long> categoryIds = rawColumns.stream()
                .map(col -> toLong(col.get("categoryId")))
                .toList();

        List<SkillMatrixRowResponse> rows = new ArrayList<>();
        for (Map<String, Object> rawRow : rawRows) {
            Long userId = toLong(rawRow.get("userId"));
            String displayName = (String) rawRow.getOrDefault("displayName", "");

            List<Map<String, Object>> rawCells =
                    (List<Map<String, Object>>) rawRow.getOrDefault("cells", List.of());

            // categoryId → SkillMatrixCellResponse のマップを構築
            Map<Long, SkillMatrixCellResponse> cells = new LinkedHashMap<>();
            for (Map<String, Object> rawCell : rawCells) {
                Long categoryId = toLong(rawCell.get("categoryId"));
                Long memberSkillId = toLong(rawCell.get("memberSkillId"));
                SkillStatus status = rawCell.get("status") != null
                        ? SkillStatus.valueOf((String) rawCell.get("status"))
                        : null;
                LocalDate expiresAt = rawCell.get("expiresAt") instanceof LocalDate
                        ? (LocalDate) rawCell.get("expiresAt")
                        : null;
                cells.put(categoryId, new SkillMatrixCellResponse(memberSkillId, status, expiresAt));
            }

            rows.add(new SkillMatrixRowResponse(userId, displayName, cells));
        }
        return rows;
    }

    // ========================================
    // ヘルパー
    // ========================================

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Long l) return l;
        if (value instanceof Integer i) return i.longValue();
        if (value instanceof Number n) return n.longValue();
        return null;
    }
}
