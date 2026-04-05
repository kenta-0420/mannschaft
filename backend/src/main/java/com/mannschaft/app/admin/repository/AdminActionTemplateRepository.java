package com.mannschaft.app.admin.repository;

import com.mannschaft.app.admin.entity.AdminActionTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 管理者アクションテンプレートリポジトリ。
 */
public interface AdminActionTemplateRepository extends JpaRepository<AdminActionTemplateEntity, Long> {

    /**
     * アクション種別でテンプレート一覧を取得する。
     */
    List<AdminActionTemplateEntity> findByActionTypeOrderByNameAsc(String actionType);

    /**
     * アクション種別と理由でテンプレート一覧を取得する。
     */
    List<AdminActionTemplateEntity> findByActionTypeAndReasonOrderByNameAsc(String actionType, String reason);

    /**
     * 全テンプレート一覧を取得する。
     */
    List<AdminActionTemplateEntity> findAllByOrderByActionTypeAscNameAsc();
}
