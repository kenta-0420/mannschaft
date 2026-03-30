package com.mannschaft.app.translation.repository;

import com.mannschaft.app.translation.entity.TranslationConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 翻訳設定リポジトリ。
 */
public interface TranslationConfigRepository extends JpaRepository<TranslationConfigEntity, Long> {

    /**
     * スコープに紐づく翻訳設定を取得する。
     *
     * @param scopeType スコープ種別
     * @param scopeId   スコープID
     * @return 翻訳設定
     */
    Optional<TranslationConfigEntity> findByScopeTypeAndScopeId(String scopeType, Long scopeId);
}
