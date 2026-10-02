package com.mannschaft.app.village.repository;

import com.mannschaft.app.village.entity.VillageInvitationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 村招待リポジトリ（トークンハッシュ式）。
 */
public interface VillageInvitationRepository extends JpaRepository<VillageInvitationEntity, UUID> {

    /** トークンハッシュでの検索（照合用）。 */
    Optional<VillageInvitationEntity> findByTokenHash(String tokenHash);

    /** 村の招待一覧。 */
    List<VillageInvitationEntity> findByVillageId(UUID villageId);

    /**
     * 受諾時の同時実行対策として悲観ロック付きでトークンハッシュ検索を行う。
     *
     * <p>使用回数の同時更新（used_count のインクリメント）による
     * max_uses 超過を防ぐため、受諾処理はこのメソッドで取得した行に対して行うこと。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM VillageInvitationEntity i WHERE i.tokenHash = :tokenHash")
    Optional<VillageInvitationEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
