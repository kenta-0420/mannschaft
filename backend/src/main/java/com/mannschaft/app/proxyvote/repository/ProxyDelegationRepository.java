package com.mannschaft.app.proxyvote.repository;

import com.mannschaft.app.proxyvote.DelegationStatus;
import com.mannschaft.app.proxyvote.entity.ProxyDelegationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 委任状リポジトリ。
 */
public interface ProxyDelegationRepository extends JpaRepository<ProxyDelegationEntity, Long> {

    Optional<ProxyDelegationEntity> findBySessionIdAndDelegatorId(Long sessionId, Long delegatorId);

    List<ProxyDelegationEntity> findBySessionIdAndStatus(Long sessionId, DelegationStatus status);

    List<ProxyDelegationEntity> findBySessionId(Long sessionId);

    List<ProxyDelegationEntity> findBySessionIdAndDelegateIdAndStatus(
            Long sessionId, Long delegateId, DelegationStatus status);

    long countBySessionIdAndStatus(Long sessionId, DelegationStatus status);

    boolean existsBySessionIdAndDelegatorId(Long sessionId, Long delegatorId);
}
