package com.mannschaft.app.signage.repository;

import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * デジタルサイネージ アクセストークンリポジトリ。
 */
public interface SignageAccessTokenRepository extends JpaRepository<SignageAccessTokenEntity, Long> {

    /**
     * トークン文字列でアクティブなトークンを取得する。
     */
    Optional<SignageAccessTokenEntity> findByTokenAndIsActiveTrue(String token);

    /**
     * 画面IDに紐づくトークン一覧を取得する。
     */
    List<SignageAccessTokenEntity> findByScreenId(Long screenId);
}
