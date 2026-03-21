package com.mannschaft.app.queue.repository;

import com.mannschaft.app.queue.entity.QueueQrCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 順番待ちQRコードリポジトリ。
 */
public interface QueueQrCodeRepository extends JpaRepository<QueueQrCodeEntity, Long> {

    /**
     * QRトークンで取得する。
     */
    Optional<QueueQrCodeEntity> findByQrToken(String qrToken);

    /**
     * カテゴリIDで取得する。
     */
    List<QueueQrCodeEntity> findByCategoryId(Long categoryId);

    /**
     * カウンターIDで取得する。
     */
    List<QueueQrCodeEntity> findByCounterId(Long counterId);

    /**
     * QRトークンの存在チェック。
     */
    boolean existsByQrToken(String qrToken);
}
