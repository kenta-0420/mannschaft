package com.mannschaft.app.advertising.repository;

import com.mannschaft.app.advertising.AdvertiserAccountStatus;
import com.mannschaft.app.advertising.entity.AdvertiserAccountEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 広告主アカウントリポジトリ。
 */
public interface AdvertiserAccountRepository extends JpaRepository<AdvertiserAccountEntity, Long> {

    /**
     * 組織IDで広告主アカウントを検索する。
     */
    Optional<AdvertiserAccountEntity> findByOrganizationId(Long organizationId);

    /**
     * 組織IDで広告主アカウントの存在を確認する。
     */
    boolean existsByOrganizationId(Long organizationId);

    /**
     * ステータスで広告主アカウントをページネーション取得する。
     */
    Page<AdvertiserAccountEntity> findByStatus(AdvertiserAccountStatus status, Pageable pageable);
}
