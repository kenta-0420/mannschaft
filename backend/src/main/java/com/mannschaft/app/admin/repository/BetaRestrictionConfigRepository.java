package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.entity.BetaRestrictionConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ベータ登録制限設定リポジトリ。
 */
public interface BetaRestrictionConfigRepository extends JpaRepository<BetaRestrictionConfigEntity, Long> {
}
