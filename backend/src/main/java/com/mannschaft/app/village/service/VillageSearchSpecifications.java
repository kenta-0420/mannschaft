package com.mannschaft.app.village.service;

import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import org.springframework.data.jpa.domain.Specification;

/**
 * 村検索（F17.1 §4.2）用の {@link Specification} 集。
 *
 * <p>検索結果は以下の条件を必須とする:</p>
 * <ul>
 *   <li>{@code deletedAt IS NULL}（論理削除済みでない）</li>
 *   <li>{@code archivedAt IS NULL}（運営凍結済みでない）</li>
 *   <li>{@code visibility = PUBLIC}（UNLISTED は検索結果に出さない）</li>
 * </ul>
 *
 * <p>追加で、村名・スラッグ・説明の部分一致、カテゴリ完全一致、種別完全一致を組み合わせる。</p>
 */
public final class VillageSearchSpecifications {

    private VillageSearchSpecifications() {
    }

    /** 検索結果に出すための前提条件（公開・未削除・未凍結）。 */
    public static Specification<VillageEntity> searchable() {
        return (root, query, cb) -> cb.and(
                cb.isNull(root.get("deletedAt")),
                cb.isNull(root.get("archivedAt")),
                cb.equal(root.get("visibility"), VillageVisibility.PUBLIC)
        );
    }

    /**
     * 全文検索的部分一致。{@code name} / {@code slug} / {@code description} を LIKE で検索。
     * MySQL の utf8mb4_0900_ai_ci 照合により大文字小文字・全半角の差は吸収される。
     */
    public static Specification<VillageEntity> textContains(String q) {
        if (q == null || q.isBlank()) {
            return null;
        }
        String pattern = "%" + q.trim() + "%";
        return (root, query, cb) -> cb.or(
                cb.like(root.get("name"), pattern),
                cb.like(root.get("slug"), pattern),
                cb.like(root.get("description"), pattern)
        );
    }

    public static Specification<VillageEntity> categoryEquals(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("category"), category);
    }

    public static Specification<VillageEntity> typeEquals(VillageType type) {
        if (type == null) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("type"), type);
    }
}
