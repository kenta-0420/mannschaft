package com.mannschaft.app.resident.repository;

import com.mannschaft.app.resident.entity.PropertyListingInquiryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 物件問い合わせリポジトリ。
 */
public interface PropertyListingInquiryRepository extends JpaRepository<PropertyListingInquiryEntity, Long> {

    List<PropertyListingInquiryEntity> findByListingIdOrderByCreatedAtDesc(Long listingId);

    boolean existsByListingIdAndUserId(Long listingId, Long userId);
}
