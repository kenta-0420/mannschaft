package com.mannschaft.app.directmail.repository;

import com.mannschaft.app.directmail.entity.DirectMailTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ダイレクトメールテンプレートリポジトリ。
 */
public interface DirectMailTemplateRepository extends JpaRepository<DirectMailTemplateEntity, Long> {

    /**
     * スコープ別のテンプレート一覧を取得する。
     */
    List<DirectMailTemplateEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(String scopeType, Long scopeId);

    /**
     * スコープとIDでテンプレートを取得する。
     */
    Optional<DirectMailTemplateEntity> findByIdAndScopeTypeAndScopeId(Long id, String scopeType, Long scopeId);
}
