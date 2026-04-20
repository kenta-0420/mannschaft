package com.mannschaft.app.committee.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.activity.ActivityScopeType;
import com.mannschaft.app.activity.entity.ActivityResultEntity;
import com.mannschaft.app.activity.repository.ActivityResultRepository;
import com.mannschaft.app.committee.entity.CommitteeRole;
import com.mannschaft.app.committee.error.CommitteeErrorCode;
import com.mannschaft.app.committee.repository.CommitteeMemberRepository;
import com.mannschaft.app.committee.repository.CommitteeRepository;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * F04.10 議事録確定サービス。
 * 委員会の活動記録（議事録テンプレート）に CONFIRMED ステータスを付与する。
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class CommitteeMinutesService {

    private final CommitteeRepository committeeRepository;
    private final CommitteeMemberRepository committeeMemberRepository;
    private final ActivityResultRepository activityResultRepository;
    private final ObjectMapper objectMapper;

    /**
     * 議事録を確定する。
     *
     * <p>処理フロー:
     * <ol>
     *   <li>委員会の存在確認</li>
     *   <li>CHAIR / VICE_CHAIR 権限チェック</li>
     *   <li>活動記録の存在確認</li>
     *   <li>スコープが COMMITTEE かつ committeeId 一致確認</li>
     *   <li>既に CONFIRMED の場合は 409</li>
     *   <li>fieldValues の _meta を更新して保存</li>
     * </ol>
     *
     * @param committeeId   委員会ID
     * @param recordId      活動記録ID
     * @param currentUserId 実行者ユーザーID
     * @return 更新後の活動記録エンティティ
     */
    @Transactional
    public ActivityResultEntity confirmMinutes(Long committeeId, Long recordId, Long currentUserId) {

        // 1. 委員会の存在確認
        committeeRepository.findById(committeeId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));

        // 2. CHAIR / VICE_CHAIR 権限チェック
        boolean hasRole = committeeMemberRepository
                .findByCommitteeIdAndUserIdAndLeftAtIsNull(committeeId, currentUserId)
                .map(member -> member.getRole() == CommitteeRole.CHAIR
                        || member.getRole() == CommitteeRole.VICE_CHAIR)
                .orElse(false);
        if (!hasRole) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }

        // 3. 活動記録の存在確認
        ActivityResultEntity record = activityResultRepository.findById(recordId)
                .orElseThrow(() -> new BusinessException(CommitteeErrorCode.NOT_FOUND));

        // 4. スコープが COMMITTEE かつ committeeId 一致確認
        if (record.getScopeType() != ActivityScopeType.COMMITTEE
                || !committeeId.equals(record.getScopeId())) {
            throw new BusinessException(CommitteeErrorCode.MINUTES_NOT_COMMITTEE_SCOPE);
        }

        // 5. fieldValues の _meta を解析して既に CONFIRMED か確認
        Map<String, Object> fieldMap = parseFieldValues(record.getFieldValues());

        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) fieldMap.computeIfAbsent("_meta", k -> new HashMap<>());

        if ("CONFIRMED".equals(meta.get("status"))) {
            throw new BusinessException(CommitteeErrorCode.MINUTES_ALREADY_CONFIRMED);
        }

        // 6. _meta を更新
        meta.put("status", "CONFIRMED");
        meta.put("confirmed_at", Instant.now().toString());
        meta.put("confirmed_by", currentUserId);

        String updatedFieldValues = serializeFieldValues(fieldMap);
        record.updateFieldValues(updatedFieldValues);
        return activityResultRepository.save(record);
    }

    // ========================================
    // プライベートヘルパーメソッド
    // ========================================

    /**
     * JSON 文字列を Map に変換する。
     */
    private Map<String, Object> parseFieldValues(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new HashMap<>();
            }
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            // JSON が不正な場合は空 Map として扱う
            return new HashMap<>();
        }
    }

    /**
     * Map を JSON 文字列に変換する。
     */
    private String serializeFieldValues(Map<String, Object> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new BusinessException(CommitteeErrorCode.MINUTES_NOT_COMMITTEE_SCOPE, e);
        }
    }
}
