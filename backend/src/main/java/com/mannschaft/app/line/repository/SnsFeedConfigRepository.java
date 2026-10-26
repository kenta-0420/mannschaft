package com.mannschaft.app.line.repository;

import com.mannschaft.app.line.ScopeType;
import com.mannschaft.app.line.SnsProvider;
import com.mannschaft.app.line.entity.SnsFeedConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * SNSフィード設定リポジトリ。
 */
public interface SnsFeedConfigRepository extends JpaRepository<SnsFeedConfigEntity, Long> {

    /**
     * スコープ種別とスコープIDでフィード設定一覧を取得する。
     */
    List<SnsFeedConfigEntity> findByScopeTypeAndScopeId(ScopeType scopeType, Long scopeId);

    /**
     * スコープとプロバイダーでフィード設定を取得する。
     */
    Optional<SnsFeedConfigEntity> findByScopeTypeAndScopeIdAndProvider(
            ScopeType scopeType, Long scopeId, SnsProvider provider);

    /**
     * スコープとプロバイダーでフィード設定が存在するか確認する。
     */
    boolean existsByScopeTypeAndScopeIdAndProvider(
            ScopeType scopeType, Long scopeId, SnsProvider provider);
}
