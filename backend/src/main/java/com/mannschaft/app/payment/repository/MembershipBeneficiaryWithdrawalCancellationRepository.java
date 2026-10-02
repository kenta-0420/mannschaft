package com.mannschaft.app.payment.repository;

import com.mannschaft.app.payment.entity.MembershipBeneficiaryWithdrawalCancellationEntity;
import com.mannschaft.app.payment.entity.MembershipBeneficiaryWithdrawalCancellationStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 受益者退会の同期作業行。組織に属さないため通常の Repository を使う。 */
public interface MembershipBeneficiaryWithdrawalCancellationRepository
        extends JpaRepository<MembershipBeneficiaryWithdrawalCancellationEntity, UUID> {

    Optional<MembershipBeneficiaryWithdrawalCancellationEntity> findBySubscriptionId(UUID subscriptionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM MembershipBeneficiaryWithdrawalCancellationEntity c WHERE c.subscriptionId = :subscriptionId")
    Optional<MembershipBeneficiaryWithdrawalCancellationEntity> findBySubscriptionIdForUpdate(
            @Param("subscriptionId") UUID subscriptionId);

    List<MembershipBeneficiaryWithdrawalCancellationEntity> findByStatusInOrderByUpdatedAtAsc(
            Collection<MembershipBeneficiaryWithdrawalCancellationStatus> statuses);
}
