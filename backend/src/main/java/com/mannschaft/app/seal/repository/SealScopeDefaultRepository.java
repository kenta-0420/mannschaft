package com.mannschaft.app.seal.repository;

import com.mannschaft.app.seal.SealScopeType;
import com.mannschaft.app.seal.entity.SealScopeDefaultEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 印鑑スコープデフォルトリポジトリ。
 */
public interface SealScopeDefaultRepository extends JpaRepository<SealScopeDefaultEntity, Long> {

    /**
     * ユーザーのスコープデフォルト一覧を取得する。
     */
    List<SealScopeDefaultEntity> findByUserIdOrderByCreatedAtAsc(Long userId);

    /**
     * ユーザー・スコープ種別・スコープIDでデフォルト設定を取得する。
     */
    Optional<SealScopeDefaultEntity> findByUserIdAndScopeTypeAndScopeId(
            Long userId, SealScopeType scopeType, Long scopeId);

    /**
     * ユーザー・スコープ種別・スコープIDの組み合わせが存在するか確認する。
     */
    boolean existsByUserIdAndScopeTypeAndScopeId(Long userId, SealScopeType scopeType, Long scopeId);

    /**
     * 特定の印鑑を参照しているスコープデフォルトを削除する。
     */
    void deleteBySealId(Long sealId);
}
