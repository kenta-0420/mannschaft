package com.mannschaft.app.faq.repository;

import com.mannschaft.app.faq.ScopeType;
import com.mannschaft.app.faq.entity.FaqEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * F21.1 §5.5: 公開FAQリポジトリ。
 *
 * <p><b>{@code AbstractTenantAwareRepository} を継承しない理由（CLAUDE.md 原則7）:</b>
 * 原則7のテナント基底クラスは {@code organization_id} カラムで直接絞り込むテーブル専用である。
 * 一方 FAQ は {@code scope_type}（TEAM / ORGANIZATION）+ {@code scope_id} の複合スコープで管理し、
 * チームFAQでは {@code scope_id} がチームIDになるため {@code organization_id} 単独では絞り込めない。
 * よって原則7の意図に合致せず、{@link JpaRepository} を直接継承し scope ベースの
 * カスタムクエリメソッドを提供する。</p>
 */
@Repository
public interface FaqRepository extends JpaRepository<FaqEntity, UUID> {

    /**
     * 指定スコープの有効なFAQを表示順で取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   チーム/組織ID
     * @return 有効なFAQリスト（display_order 昇順）
     */
    List<FaqEntity> findByScopeTypeAndScopeIdAndDeletedAtIsNullOrderByDisplayOrderAsc(
            ScopeType scopeType, Long scopeId);

    /**
     * ID で有効な（未削除の）FAQを取得する。
     *
     * @param id FAQ ID
     * @return 有効なFAQ。なければ空
     */
    Optional<FaqEntity> findByIdAndDeletedAtIsNull(UUID id);

    /**
     * 指定スコープの特定の固定質問FAQを取得する（UPSERT 判定用）。
     *
     * @param scopeType   スコープ種別
     * @param scopeId     チーム/組織ID
     * @param questionKey 固定質問キー（{@link com.mannschaft.app.faq.FixedFaqQuestion} の name）
     * @return 既存の固定質問FAQ。なければ空
     */
    Optional<FaqEntity> findByScopeTypeAndScopeIdAndQuestionKeyAndDeletedAtIsNull(
            ScopeType scopeType, Long scopeId, String questionKey);
}
