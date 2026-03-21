package com.mannschaft.app.forms.repository;

import com.mannschaft.app.forms.entity.FormTemplateFieldEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * フォームテンプレートフィールドリポジトリ。
 */
public interface FormTemplateFieldRepository extends JpaRepository<FormTemplateFieldEntity, Long> {

    /**
     * テンプレートに属するフィールドをソート順で取得する。
     */
    List<FormTemplateFieldEntity> findByTemplateIdOrderBySortOrderAsc(Long templateId);

    /**
     * テンプレートに属するフィールドを一括削除する。
     */
    void deleteByTemplateId(Long templateId);

    /**
     * テンプレートに属するフィールド数を取得する。
     */
    long countByTemplateId(Long templateId);
}
