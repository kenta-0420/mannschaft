package com.mannschaft.app.queue.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.queue.QueueErrorCode;
import com.mannschaft.app.queue.QueueMapper;
import com.mannschaft.app.queue.dto.CreateQrCodeRequest;
import com.mannschaft.app.queue.dto.QrCodeResponse;
import com.mannschaft.app.queue.entity.QueueQrCodeEntity;
import com.mannschaft.app.queue.repository.QueueQrCodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

/**
 * 順番待ちQRコードサービス。QRコードの発行・取得・無効化を担当する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QueueQrCodeService {

    private static final int QR_TOKEN_BYTE_LENGTH = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final QueueQrCodeRepository qrCodeRepository;
    private final QueueCategoryService categoryService;
    private final QueueCounterService counterService;
    private final QueueMapper queueMapper;

    /**
     * QRコードを発行する。
     *
     * @param request 発行リクエスト
     * @return 発行されたQRコード
     */
    @Transactional
    public QrCodeResponse createQrCode(CreateQrCodeRequest request) {
        // XORバリデーション
        if ((request.getCategoryId() == null) == (request.getCounterId() == null)) {
            throw new BusinessException(QueueErrorCode.QR_CODE_NOT_FOUND);
        }

        // 参照先の存在チェック
        if (request.getCategoryId() != null) {
            categoryService.findEntityOrThrow(request.getCategoryId());
        }
        if (request.getCounterId() != null) {
            counterService.findEntityOrThrow(request.getCounterId());
        }

        String token = generateUniqueToken();

        QueueQrCodeEntity entity = QueueQrCodeEntity.builder()
                .categoryId(request.getCategoryId())
                .counterId(request.getCounterId())
                .qrToken(token)
                .isActive(true)
                .build();

        QueueQrCodeEntity saved = qrCodeRepository.save(entity);
        log.info("QRコード発行: id={}, categoryId={}, counterId={}", saved.getId(),
                saved.getCategoryId(), saved.getCounterId());
        return queueMapper.toQrCodeResponse(saved);
    }

    /**
     * QRトークンでQRコード情報を取得する。
     *
     * @param qrToken QRトークン
     * @return QRコード情報
     */
    public QrCodeResponse getByToken(String qrToken) {
        QueueQrCodeEntity entity = qrCodeRepository.findByQrToken(qrToken)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.QR_CODE_NOT_FOUND));
        if (!entity.getIsActive()) {
            throw new BusinessException(QueueErrorCode.QR_CODE_INACTIVE);
        }
        return queueMapper.toQrCodeResponse(entity);
    }

    /**
     * カテゴリまたはカウンターのQRコード一覧を取得する。
     *
     * @param categoryId カテゴリID（null可）
     * @param counterId  カウンターID（null可）
     * @return QRコード一覧
     */
    public List<QrCodeResponse> listQrCodes(Long categoryId, Long counterId) {
        List<QueueQrCodeEntity> entities;
        if (categoryId != null) {
            entities = qrCodeRepository.findByCategoryId(categoryId);
        } else if (counterId != null) {
            entities = qrCodeRepository.findByCounterId(counterId);
        } else {
            entities = List.of();
        }
        return queueMapper.toQrCodeResponseList(entities);
    }

    /**
     * QRコードを無効化する。
     *
     * @param id QRコードID
     */
    @Transactional
    public void deactivateQrCode(Long id) {
        QueueQrCodeEntity entity = qrCodeRepository.findById(id)
                .orElseThrow(() -> new BusinessException(QueueErrorCode.QR_CODE_NOT_FOUND));
        entity.deactivate();
        qrCodeRepository.save(entity);
        log.info("QRコード無効化: id={}", id);
    }

    private String generateUniqueToken() {
        String token;
        do {
            byte[] bytes = new byte[QR_TOKEN_BYTE_LENGTH];
            SECURE_RANDOM.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (qrCodeRepository.existsByQrToken(token));
        return token;
    }
}
