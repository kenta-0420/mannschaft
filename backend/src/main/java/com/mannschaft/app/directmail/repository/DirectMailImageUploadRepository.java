package com.mannschaft.app.directmail.repository;

import com.mannschaft.app.directmail.entity.DirectMailImageUploadEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ダイレクトメール画像アップロードリポジトリ。
 */
public interface DirectMailImageUploadRepository extends JpaRepository<DirectMailImageUploadEntity, Long> {

    /**
     * メールログIDで画像一覧を取得する。
     */
    List<DirectMailImageUploadEntity> findByMailLogId(Long mailLogId);

    /**
     * 孤児画像（mail_log_id が NULL で一定期間経過）を取得する。
     */
    List<DirectMailImageUploadEntity> findByMailLogIdIsNullAndCreatedAtBefore(LocalDateTime threshold);
}
