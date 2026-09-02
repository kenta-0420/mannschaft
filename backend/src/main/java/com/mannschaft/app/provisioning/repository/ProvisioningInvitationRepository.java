package com.mannschaft.app.provisioning.repository;

import com.mannschaft.app.provisioning.entity.ProvisioningInvitationEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * 柱②-1: 販促プロビジョニング招待リポジトリ（トークンハッシュ式）。
 *
 * <p>本 PR では骨格のみを追加する。作成・承諾のユースケースは後続 PR で実装する。</p>
 */
public interface ProvisioningInvitationRepository extends JpaRepository<ProvisioningInvitationEntity, UUID> {

    /** トークンハッシュでの検索（照合用）。 */
    Optional<ProvisioningInvitationEntity> findByTokenHash(String tokenHash);

    /**
     * 承諾時の同時実行対策として悲観ロック付きでトークンハッシュ検索を行う。
     *
     * <p>承諾処理（status 更新・lifecycle_status への波及）はこのメソッドで取得した行に
     * 対して行うこと（{@code village.VillageInvitationRepository#findByTokenHashForUpdate} と同型）。</p>
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM ProvisioningInvitationEntity i WHERE i.tokenHash = :tokenHash")
    Optional<ProvisioningInvitationEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}
