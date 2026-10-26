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

    /**
     * 指定ユーザーの物件問い合わせを全件削除する（クロスドメインFK撤廃キャンペーン 第二陣E）。
     *
     * <p>{@code ResidentAnonymizationEventListener#onUserAnonymized} が退会受付直後
     * （{@code UserAnonymizedEvent} 即時匿名化）に呼び出し、users 本体削除より前に
     * 問い合わせ message（自由記述の問い合わせ内容＝PII）を先行削除する安全弁メソッド。
     * これにより V100.001 で撤廃する {@code fk_pli_user}（ON DELETE CASCADE）が冗長になる。</p>
     *
     * <p>{@code PropertyListingInquiryEntity} は {@code @SQLRestriction} を持たず
     * （論理削除カラム deleted_at なし）、派生 delete でも消し残しは発生しないため通常の派生 delete を用いる。</p>
     *
     * @param userId 退会ユーザーID
     * @return 削除された行数
     */
    int deleteByUserId(Long userId);
}
