package com.mannschaft.app.navsettings.repository;

import com.mannschaft.app.navsettings.entity.NavFeatureEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NavFeatureRepository extends JpaRepository<NavFeatureEntity, String> {

    // is_enabled=TRUE のみ sort_order 昇順で取得（ユーザー向けナビ表示用）
    List<NavFeatureEntity> findByEnabledTrueOrderBySortOrderAsc();

    // 全件 sort_order 昇順（シスアド管理画面用）
    List<NavFeatureEntity> findAllByOrderBySortOrderAsc();
}
