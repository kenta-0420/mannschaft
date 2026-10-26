package com.mannschaft.app.common.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

/**
 * 個人スコープ（user_id 軸）リポジトリの共通基底。
 * organization_id を持たないテーブル向け。F18 個人ポイントカードウォレット起点で導入。
 * 詳細: CLAUDE.md DB 設計原則 7 への代替（個人スコープ専用）。
 */
@NoRepositoryBean
public interface AbstractUserOwnedRepository<T, ID> extends JpaRepository<T, ID> {

    List<T> findByUserId(Long userId);

    List<T> findByUserId(Long userId, Sort sort);

    Optional<T> findByIdAndUserId(ID id, Long userId);

    long countByUserId(Long userId);
}
