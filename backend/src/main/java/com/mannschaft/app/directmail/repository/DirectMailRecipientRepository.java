package com.mannschaft.app.directmail.repository;

import com.mannschaft.app.directmail.entity.DirectMailRecipientEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * ダイレクトメール受信者リポジトリ。
 */
public interface DirectMailRecipientRepository extends JpaRepository<DirectMailRecipientEntity, Long> {

    /**
     * メールログIDで受信者一覧をページネーション付きで取得する。
     */
    Page<DirectMailRecipientEntity> findByMailLogId(Long mailLogId, Pageable pageable);

    /**
     * メールログIDで全受信者を取得する。
     */
    List<DirectMailRecipientEntity> findByMailLogId(Long mailLogId);

    /**
     * SES メッセージIDで受信者を取得する。
     */
    Optional<DirectMailRecipientEntity> findBySesMessageId(String sesMessageId);

    /**
     * メールログIDと配信ステータスで件数を取得する。
     */
    long countByMailLogIdAndStatus(Long mailLogId, String status);

    /**
     * メールログIDで受信者数を取得する。
     */
    long countByMailLogId(Long mailLogId);
}
