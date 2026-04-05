package com.mannschaft.app.signage;

import com.mannschaft.app.signage.entity.SignageAccessTokenEntity;
import com.mannschaft.app.signage.entity.SignageEmergencyMessageEntity;
import com.mannschaft.app.signage.entity.SignageScreenEntity;
import com.mannschaft.app.signage.entity.SignageSlotEntity;
import com.mannschaft.app.signage.service.SignageAccessTokenService.SignageAccessTokenResponse;
import com.mannschaft.app.signage.service.SignageEmergencyService.EmergencyMessageResponse;
import com.mannschaft.app.signage.service.SignageScreenService.SignageScreenResponse;
import com.mannschaft.app.signage.service.SignageSlotService.SignageSlotResponse;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * デジタルサイネージドメインのエンティティ → DTO 変換マッパー。
 * Service 内の toResponse() と役割が重複するが、Controller 層から直接利用できる
 * 単一責任コンポーネントとして提供する。
 */
@Component
public class SignageMapper {

    // ========================================
    // SignageScreenEntity → SignageScreenResponse
    // ========================================

    /**
     * SignageScreenEntity を SignageScreenResponse に変換する。
     * Entity に description フィールドが存在しないため null を設定する。
     */
    public SignageScreenResponse toScreenResponse(SignageScreenEntity e) {
        return new SignageScreenResponse(
                e.getId(),
                e.getScopeType(),
                e.getScopeId(),
                e.getName(),
                null, // description は Entity に存在しないため null
                e.getLayout(),
                e.getDefaultSlideDuration(),
                e.getTransitionEffect(),
                e.getIsActive(),
                e.getCreatedAt()
        );
    }

    /**
     * SignageScreenEntity リストを SignageScreenResponse リストに変換する。
     */
    public List<SignageScreenResponse> toScreenResponseList(List<SignageScreenEntity> entities) {
        return entities.stream()
                .map(this::toScreenResponse)
                .toList();
    }

    // ========================================
    // SignageSlotEntity → SignageSlotResponse
    // ========================================

    /**
     * SignageSlotEntity を SignageSlotResponse に変換する。
     * contentSourceId は contentConfig フィールドから分離されているため null を設定する。
     */
    public SignageSlotResponse toSlotResponse(SignageSlotEntity e) {
        return new SignageSlotResponse(
                e.getId(),
                e.getScreenId(),
                e.getSlotType(),
                null, // contentSourceId は contentConfig から分離されているため null
                e.getSlotOrder(),
                e.getSlideDuration(),
                e.getContentConfig(),
                e.getIsActive()
        );
    }

    /**
     * SignageSlotEntity リストを SignageSlotResponse リストに変換する。
     */
    public List<SignageSlotResponse> toSlotResponseList(List<SignageSlotEntity> entities) {
        return entities.stream()
                .map(this::toSlotResponse)
                .toList();
    }

    // ========================================
    // SignageAccessTokenEntity → SignageAccessTokenResponse
    // ========================================

    /**
     * SignageAccessTokenEntity を SignageAccessTokenResponse に変換する。
     * expiredAt は Entity に格納されていないため null を設定する。
     */
    public SignageAccessTokenResponse toTokenResponse(SignageAccessTokenEntity e) {
        return new SignageAccessTokenResponse(
                e.getId(),
                e.getScreenId(),
                e.getToken(),
                e.getName(),
                e.getIsActive(),
                parseAllowedIps(e.getAllowedIps()),
                null, // expiredAt は Entity に存在しないため null
                e.getCreatedAt()
        );
    }

    /**
     * SignageAccessTokenEntity リストを SignageAccessTokenResponse リストに変換する。
     */
    public List<SignageAccessTokenResponse> toTokenResponseList(List<SignageAccessTokenEntity> entities) {
        return entities.stream()
                .map(this::toTokenResponse)
                .toList();
    }

    // ========================================
    // SignageEmergencyMessageEntity → EmergencyMessageResponse
    // ========================================

    /**
     * SignageEmergencyMessageEntity を EmergencyMessageResponse に変換する。
     * durationSeconds は Entity に存在しないため null を設定する。
     */
    public EmergencyMessageResponse toEmergencyResponse(SignageEmergencyMessageEntity e) {
        return new EmergencyMessageResponse(
                e.getId(),
                e.getScreenId(),
                e.getMessage(),
                e.getBackgroundColor(),
                e.getTextColor(),
                null, // durationSeconds は Entity に存在しないため null
                e.getCreatedAt()
        );
    }

    /**
     * SignageEmergencyMessageEntity リストを EmergencyMessageResponse リストに変換する。
     */
    public List<EmergencyMessageResponse> toEmergencyResponseList(List<SignageEmergencyMessageEntity> entities) {
        return entities.stream()
                .map(this::toEmergencyResponse)
                .toList();
    }

    // ========================================
    // ヘルパー
    // ========================================

    /**
     * JSON配列文字列を String リストに変換する（簡易実装）。
     * null・空文字列の場合は空リストを返す。
     */
    private List<String> parseAllowedIps(String json) {
        if (json == null || json.isBlank() || json.trim().equals("[]")) {
            return List.of();
        }
        String inner = json.trim().substring(1, json.trim().length() - 1);
        return Arrays.stream(inner.split(","))
                .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
