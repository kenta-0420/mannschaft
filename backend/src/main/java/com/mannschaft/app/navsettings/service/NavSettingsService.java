package com.mannschaft.app.navsettings.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mannschaft.app.auth.AuditEventType;
import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.navsettings.dto.NavFeatureResponse;
import com.mannschaft.app.navsettings.dto.NavSettingsResponse;
import com.mannschaft.app.navsettings.entity.NavFeatureEntity;
import com.mannschaft.app.navsettings.error.NavSettingsErrorCode;
import com.mannschaft.app.navsettings.repository.NavFeatureRepository;
import com.mannschaft.app.navsettings.repository.UserNavSettingsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class NavSettingsService {

    private final NavFeatureRepository navFeatureRepository;
    private final UserNavSettingsRepository userNavSettingsRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public NavSettingsResponse getMyNavSettings(Long userId) {
        // is_enabled=TRUE の項目のみ取得（マスタ sort_order 昇順）
        List<NavFeatureEntity> features = navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc();

        // ユーザーの非表示キー・個人並び順を取得（レコードなければ空）
        Set<String> hiddenKeys = Collections.emptySet();
        List<String> userOrder = Collections.emptyList();
        var settingsOpt = userNavSettingsRepository.findById(userId);
        if (settingsOpt.isPresent()) {
            hiddenKeys = parseList(settingsOpt.get().getHiddenNavKeys(), userId);
            userOrder = parseListAsList(settingsOpt.get().getNavDisplayOrder(), userId);
        }

        // 個人順を尊重し、無い key はマスタ sort_order 順で末尾補完する（欠落・重複なし）
        List<NavFeatureEntity> ordered = resolveOrder(features, userOrder);

        // 'settings' 項目は個人並び順に関わらず常に最後尾に固定する（マスター指示）。
        // 存在しない場合は何もしない。visible 判定（fixed=常時表示）は変更しない。
        ordered = pinSettingsToLast(ordered);

        final Set<String> finalHidden = hiddenKeys;
        List<NavFeatureResponse> responses = ordered.stream()
                .map(f -> NavFeatureResponse.from(f, isVisible(f, finalHidden)))
                .toList();

        return NavSettingsResponse.builder().features(responses).build();
    }

    @Transactional
    public void updateMyNavSettings(Long userId, List<String> hiddenNavKeys, List<String> navDisplayOrder) {
        // 存在するキーをすべて取得して検証
        List<NavFeatureEntity> allEnabled = navFeatureRepository.findByEnabledTrueOrderBySortOrderAsc();
        Set<String> validKeys = allEnabled.stream().map(NavFeatureEntity::getKey)
                .collect(Collectors.toSet());

        // 非表示キーの検証（存在・固定項目を非表示にしていないか）
        for (String key : hiddenNavKeys) {
            if (!validKeys.contains(key)) {
                throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_004);
            }
            allEnabled.stream()
                    .filter(f -> f.getKey().equals(key))
                    .filter(NavFeatureEntity::isFixed)
                    .findFirst()
                    .ifPresent(f -> { throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_002); });
        }

        // 個人並び順の検証（指定があれば全 key が実在すること）
        if (navDisplayOrder != null) {
            for (String key : navDisplayOrder) {
                if (!validKeys.contains(key)) {
                    throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_004);
                }
            }
        }

        // UPSERT（hiddenNavKeys は常に保存、navDisplayOrder は null ならマスタ順にリセット）
        try {
            String hiddenJson = objectMapper.writeValueAsString(hiddenNavKeys);
            String orderJson = navDisplayOrder == null ? null : objectMapper.writeValueAsString(navDisplayOrder);
            userNavSettingsRepository.upsertSettings(userId, hiddenJson, orderJson);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("ナビ設定の保存に失敗", e);
            throw new BusinessException(NavSettingsErrorCode.NAV_SETTINGS_004);
        }

        // 監査ログ記録（保存成功後のみ。PII は含めず件数のみ記録する）
        String metadata = String.format("{\"source\":\"NAV_SETTINGS\",\"hidden_count\":%d,\"reordered\":%b}",
                hiddenNavKeys.size(), navDisplayOrder != null);
        auditLogService.record(AuditEventType.NAV_SETTINGS_UPDATED.name(),
                userId, null, null, null, null, null, null, metadata);
    }

    /**
     * 個人並び順 + マスタ補完で features を並べ替える。
     * <ul>
     *   <li>userOrder に現れる順で先頭に並べる（実在 key のみ・重複は最初の1回のみ）</li>
     *   <li>userOrder に無い key はマスタ sort_order 順（features は既にその順）で末尾補完</li>
     * </ul>
     */
    private List<NavFeatureEntity> resolveOrder(List<NavFeatureEntity> features, List<String> userOrder) {
        if (userOrder == null || userOrder.isEmpty()) {
            return features; // マスタ sort_order 順そのまま
        }
        Map<String, NavFeatureEntity> byKey = features.stream()
                .collect(Collectors.toMap(NavFeatureEntity::getKey, Function.identity(), (a, b) -> a));

        List<NavFeatureEntity> result = new ArrayList<>(features.size());
        Set<String> placed = new LinkedHashSet<>();

        // 1) 個人順（実在・重複排除）
        for (String key : userOrder) {
            if (placed.contains(key)) continue;
            NavFeatureEntity f = byKey.get(key);
            if (f != null) {
                result.add(f);
                placed.add(key);
            }
        }
        // 2) 残りをマスタ sort_order 順で末尾補完
        for (NavFeatureEntity f : features) {
            if (!placed.contains(f.getKey())) {
                result.add(f);
                placed.add(f.getKey());
            }
        }
        return result;
    }

    /**
     * 'settings' キーの項目を常に最後尾へ移動する。
     * マスター指示: 「現状のナビバーの表示形式で、必ず一番後ろに表示させる」。
     * 個人並び順で先頭・中間に設定されていても強制的に末尾へ固定する。
     * 'settings' 項目が存在しない場合は元のリストをそのまま返す。
     *
     * @param features resolveOrder 後の並び順リスト（変更しない・新しいリストを返す）
     * @return 'settings' が末尾に固定されたリスト
     */
    private List<NavFeatureEntity> pinSettingsToLast(List<NavFeatureEntity> features) {
        NavFeatureEntity settingsFeature = null;
        List<NavFeatureEntity> rest = new ArrayList<>(features.size());
        for (NavFeatureEntity f : features) {
            if ("settings".equals(f.getKey())) {
                settingsFeature = f;
            } else {
                rest.add(f);
            }
        }
        if (settingsFeature == null) {
            return features; // 'settings' 項目なし: 変更なし
        }
        rest.add(settingsFeature);
        return rest;
    }

    private boolean isVisible(NavFeatureEntity feature, Set<String> hiddenKeys) {
        if (feature.isFixed()) return true;       // 固定項目は常に表示
        return !hiddenKeys.contains(feature.getKey());
    }

    private Set<String> parseList(String json, Long userId) {
        return new java.util.HashSet<>(parseListAsList(json, userId));
    }

    private List<String> parseListAsList(String json, Long userId) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {});
            return list == null ? Collections.emptyList() : list;
        } catch (Exception e) {
            log.warn("ナビ設定 JSON のパース失敗 userId={}", userId, e);
            return Collections.emptyList();
        }
    }
}
