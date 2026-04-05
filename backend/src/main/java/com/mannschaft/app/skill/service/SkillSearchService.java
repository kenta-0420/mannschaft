package com.mannschaft.app.skill.service;

import com.mannschaft.app.common.ApiResponse;
import com.mannschaft.app.common.NameResolverService;
import com.mannschaft.app.skill.SkillStatus;
import com.mannschaft.app.skill.entity.MemberSkillEntity;
import com.mannschaft.app.skill.repository.MemberSkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * スキル・資格検索サービス。
 * カテゴリ・ステータス・期限切れ有無などでメンバーの資格を検索する。
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class SkillSearchService {

    private final MemberSkillRepository memberSkillRepository;
    private final NameResolverService nameResolverService;

    /**
     * スコープ内のメンバーを資格条件で検索する。
     *
     * @param scopeType          スコープ種別
     * @param scopeId            スコープID
     * @param categoryId         カテゴリIDフィルタ（任意）
     * @param status             ステータスフィルタ（任意）
     * @param includeExpired     期限切れを含むか（false の場合は EXPIRED を除外）
     * @param keyword            資格名キーワード（任意）
     * @return 検索結果（資格情報 + ユーザー表示名）のリスト
     */
    public ApiResponse<List<Map<String, Object>>> searchMembers(
            String scopeType, Long scopeId,
            Long categoryId, SkillStatus status, boolean includeExpired, String keyword) {

        // スコープ内の全 member_skills（deleted_at IS NULL）を取得してフィルタ
        Stream<MemberSkillEntity> stream = memberSkillRepository.findAll().stream()
                .filter(s -> s.getScopeType().equals(scopeType)
                        && s.getScopeId().equals(scopeId)
                        && s.getDeletedAt() == null);

        // カテゴリフィルタ
        if (categoryId != null) {
            stream = stream.filter(s -> categoryId.equals(s.getSkillCategoryId()));
        }

        // ステータスフィルタ
        if (status != null) {
            stream = stream.filter(s -> s.getStatus() == status);
        } else if (!includeExpired) {
            stream = stream.filter(s -> s.getStatus() != SkillStatus.EXPIRED);
        }

        // キーワードフィルタ（資格名部分一致）
        if (keyword != null && !keyword.isBlank()) {
            String lower = keyword.toLowerCase();
            stream = stream.filter(s -> s.getName().toLowerCase().contains(lower));
        }

        List<MemberSkillEntity> skills = stream.toList();

        // ユーザー表示名を一括解決（N+1 回避）
        List<Long> userIds = skills.stream().map(MemberSkillEntity::getUserId).distinct().toList();
        Map<Long, String> displayNames = nameResolverService.resolveUserDisplayNames(userIds);

        // レスポンス構築
        List<Map<String, Object>> result = skills.stream()
                .map(s -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("memberSkillId", s.getId());
                    item.put("userId", s.getUserId());
                    item.put("displayName", displayNames.getOrDefault(s.getUserId(), "不明なユーザー"));
                    item.put("categoryId", s.getSkillCategoryId());
                    item.put("skillName", s.getName());
                    item.put("issuer", s.getIssuer());
                    item.put("credentialNumber", s.getCredentialNumber());
                    item.put("acquiredOn", s.getAcquiredOn());
                    item.put("expiresAt", s.getExpiresAt());
                    item.put("status", s.getStatus().name());
                    item.put("verifiedAt", s.getVerifiedAt());
                    // 期限切れフラグ（有効期限が今日より前 && status=ACTIVE）
                    boolean isExpiringSoon = s.getExpiresAt() != null
                            && s.getStatus() == SkillStatus.ACTIVE
                            && s.getExpiresAt().isBefore(LocalDate.now().plusDays(30));
                    item.put("expiringSoon", isExpiringSoon);
                    return item;
                })
                .toList();

        log.info("スキル検索: scope={}/{}, hits={}", scopeType, scopeId, result.size());
        return ApiResponse.of(result);
    }
}
