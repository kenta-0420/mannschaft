package com.mannschaft.app.signage.repository;

import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.websocket.SignageSocketAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** CONNECT用。raw tokenはこの1回の照合にだけ渡し、画面状態も同時に確認する。 */
    @Query("""
            select new com.mannschaft.app.signage.websocket.SignageSocketAccess(
                t.id, t.screenId, t.expiredAt, t.allowedIps)
            from SignageAccessTokenEntity t join SignageScreenEntity s on s.id = t.screenId
            where t.token = :token and t.isActive = true and s.isActive = true and s.deletedAt is null
            """)
    Optional<SignageSocketAccess> findSocketAccessByToken(@Param("token") String token);

    /** SUBSCRIBE/配送用。保存済みIDを使い、tokenと画面の有効状態を1queryで読み直す。 */
    @Query("""
            select new com.mannschaft.app.signage.websocket.SignageSocketAccess(
                t.id, t.screenId, t.expiredAt, t.allowedIps)
            from SignageAccessTokenEntity t join SignageScreenEntity s on s.id = t.screenId
            where t.id = :tokenId and t.screenId = :screenId
                and t.isActive = true and s.isActive = true and s.deletedAt is null
            """)
    Optional<SignageSocketAccess> findSocketAccessById(@Param("tokenId") Long tokenId, @Param("screenId") Long screenId);
}
