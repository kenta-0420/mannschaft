package com.mannschaft.app.visibility.repository;

import com.mannschaft.app.visibility.entity.VisibilityTemplateRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 公開範囲テンプレートルールリポジトリ。
 * テンプレートに紐づくルールの取得・削除・カウント操作を提供する。
 */
public interface VisibilityTemplateRuleRepository extends JpaRepository<VisibilityTemplateRuleEntity, Long> {

    /**
     * 指定テンプレートのルール一覧を表示順序の昇順で取得する。
     *
     * @param templateId テンプレートID
     * @return ルール一覧（表示順序昇順）
     */
    List<VisibilityTemplateRuleEntity> findByTemplateIdOrderBySortOrderAsc(Long templateId);

    /**
     * 指定テンプレートに紐づく全ルールを削除する。
     * テンプレートのルール一括更新（全削除 → 再登録）に使用する。
     *
     * @param templateId テンプレートID
     */
    @Transactional
    void deleteByTemplateId(Long templateId);

    /**
     * 指定テンプレートのルール数を返す（上限チェック用）。
     *
     * @param templateId テンプレートID
     * @return ルール数
     */
    long countByTemplateId(Long templateId);
}
