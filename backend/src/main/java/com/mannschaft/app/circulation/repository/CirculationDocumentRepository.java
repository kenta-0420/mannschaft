package com.mannschaft.app.circulation.repository;

import com.mannschaft.app.circulation.CirculationStatus;
import com.mannschaft.app.circulation.entity.CirculationDocumentEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 回覧文書リポジトリ。
 */
public interface CirculationDocumentRepository extends JpaRepository<CirculationDocumentEntity, Long> {

    /**
     * スコープ指定で文書をページング取得する。
     */
    Page<CirculationDocumentEntity> findByScopeTypeAndScopeIdOrderByCreatedAtDesc(
            String scopeType, Long scopeId, Pageable pageable);

    /**
     * スコープとステータス指定で文書をページング取得する。
     */
    Page<CirculationDocumentEntity> findByScopeTypeAndScopeIdAndStatusOrderByCreatedAtDesc(
            String scopeType, Long scopeId, CirculationStatus status, Pageable pageable);

    /**
     * IDとスコープで文書を取得する。
     */
    Optional<CirculationDocumentEntity> findByIdAndScopeTypeAndScopeId(
            Long id, String scopeType, Long scopeId);

    /**
     * 作成者IDで文書をページング取得する。
     */
    Page<CirculationDocumentEntity> findByCreatedByOrderByCreatedAtDesc(Long createdBy, Pageable pageable);

    /**
     * スコープ指定でステータス別件数を取得する。
     */
    long countByScopeTypeAndScopeIdAndStatus(String scopeType, Long scopeId, CirculationStatus status);
}
