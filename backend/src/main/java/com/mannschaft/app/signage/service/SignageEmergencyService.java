package com.mannschaft.app.signage.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.signage.SignageErrorCode;
import com.mannschaft.app.signage.entity.SignageEmergencyMessageEntity;
import com.mannschaft.app.signage.repository.SignageEmergencyMessageRepository;
import com.mannschaft.app.signage.repository.SignageScreenRepository;
import com.mannschaft.app.signage.websocket.SignageWebSocketPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * デジタルサイネージ 緊急メッセージ管理サービス。
 * 緊急メッセージのブロードキャスト・履歴取得を担う。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SignageEmergencyService {

    /** 取得する緊急メッセージ履歴の上限件数 */
    private static final int RECENT_MESSAGE_LIMIT = 30;

    private final SignageEmergencyMessageRepository emergencyRepository;
    private final SignageScreenRepository screenRepository;
    private final SignageWebSocketPublisher webSocketPublisher;

    // ========================================
    // DTO 定義
    // ========================================

    /**
     * 緊急メッセージブロードキャストリクエスト DTO。
     */
    public record BroadcastEmergencyRequest(
            String message,
            /** 背景色（HEX形式。例: #FF0000） */
            String bgColor,
            /** 文字色（HEX形式。例: #FFFFFF） */
            String textColor,
            /** 表示秒数 */
            Integer durationSeconds
    ) {}

    /**
     * 緊急メッセージレスポンス DTO。
     */
    public record EmergencyMessageResponse(
            Long id,
            Long screenId,
            String message,
            String bgColor,
            String textColor,
            Integer durationSeconds,
            LocalDateTime createdAt
    ) {}

    // ========================================
    // 公開メソッド
    // ========================================

    /**
     * 緊急メッセージをブロードキャストする。
     * DBへの永続化後、WebSocketで全接続クライアントに即時配信する。
     *
     * @param screenId 画面ID
     * @param sentBy   送信者ユーザーID
     * @param req      ブロードキャストリクエスト
     * @return 作成した緊急メッセージレスポンス
     */
    @Transactional
    public EmergencyMessageResponse broadcastEmergency(Long screenId, Long sentBy, BroadcastEmergencyRequest req) {
        // 画面の存在確認
        screenRepository.findByIdAndDeletedAtIsNull(screenId)
                .orElseThrow(() -> new BusinessException(SignageErrorCode.SIGNAGE_001));

        // 緊急メッセージを保存
        SignageEmergencyMessageEntity entity = SignageEmergencyMessageEntity.builder()
                .screenId(screenId)
                .message(req.message())
                .backgroundColor(req.bgColor() != null ? req.bgColor() : "#FF0000")
                .textColor(req.textColor() != null ? req.textColor() : "#FFFFFF")
                .sentBy(sentBy)
                .isActive(true)
                .build();

        SignageEmergencyMessageEntity saved = emergencyRepository.save(entity);
        log.info("緊急メッセージ保存: id={}, screenId={}", saved.getId(), screenId);

        // WebSocket で接続中クライアントへ即時配信
        webSocketPublisher.publishEmergency(screenId, req.message());
        log.info("緊急メッセージWebSocket配信完了: screenId={}", screenId);

        return toResponse(saved);
    }

    /**
     * 画面に紐づく緊急メッセージ履歴を直近30件取得する（createdAt降順）。
     *
     * @param screenId 画面ID
     * @return 緊急メッセージレスポンス一覧
     */
    public List<EmergencyMessageResponse> listEmergencyMessages(Long screenId) {
        return emergencyRepository.findByScreenIdOrderByCreatedAtDesc(screenId)
                .stream()
                .limit(RECENT_MESSAGE_LIMIT)
                .map(this::toResponse)
                .toList();
    }

    // ========================================
    // 内部メソッド
    // ========================================

    /**
     * エンティティをレスポンス DTO に変換する。
     */
    private EmergencyMessageResponse toResponse(SignageEmergencyMessageEntity e) {
        return new EmergencyMessageResponse(
                e.getId(),
                e.getScreenId(),
                e.getMessage(),
                e.getBackgroundColor(),
                e.getTextColor(),
                null, // durationSeconds は Entity に存在しないためnull
                e.getCreatedAt()
        );
    }
}
