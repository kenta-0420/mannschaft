package com.mannschaft.app.forms.repository;

import com.mannschaft.app.forms.FormStatus;
import com.mannschaft.app.forms.entity.FormTemplateEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * フォームテンプレートリポジトリ。
 */
public interface FormTemplateRepository extends JpaRepository<FormTemplateEntity, Long> {

    /**
     * スコープ内のテンプレート一覧をページング取得する。
     */
    Page<FormTemplateEntity> findByScopeTypeAndScopeIdOrderBySortOrderAsc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープ内のテンプレートをステータス指定でページング取得する。
     */
    Page<FormTemplateEntity> findByScopeTypeAndScopeIdAndStatusOrderBySortOrderAsc(
            String scopeType, Long scopeId, FormStatus status, Pageable pageable);

    /**
     * スコープとIDでテンプレートを取得する。
     */
    Optional<FormTemplateEntity> findByIdAndScopeTypeAndScopeId(Long id, String scopeType, Long scopeId);

    /**
     * スコープ内の公開テンプレート一覧を取得する。
     */
    List<FormTemplateEntity> findByScopeTypeAndScopeIdAndStatusOrderBySortOrderAsc(
            String scopeType, Long scopeId, FormStatus status);
}
