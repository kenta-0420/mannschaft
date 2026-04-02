package com.mannschaft.app.gdpr.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * F12.3 個人データ収集サービス。
 * 各カテゴリの個人データをJSON形式で収集する。
 */
@Slf4j
@Service
public class PersonalDataCollector {

    /**
     * 収集するカテゴリとそのコレクター関数のマップ。
     * カテゴリキー → データ収集関数（userId → JSON文字列）
     */
    private final Map<String, Function<Long, String>> collectors;

    public PersonalDataCollector() {
        this.collectors = buildDefaultCollectors();
    }

    /** テスト用コンストラクタ（カスタムコレクター注入） */
    public PersonalDataCollector(Map<String, Function<Long, String>> collectors) {
        this.collectors = collectors;
    }

    private Map<String, Function<Long, String>> buildDefaultCollectors() {
        return Map.of(
                "account", userId -> "{}",
                "payments", userId -> "[]",
                "activity", userId -> "[]",
                "schedule", userId -> "[]",
                "chart", userId -> "[]",
                "cms", userId -> "[]",
                "filesharing", userId -> "[]",
                "todo", userId -> "[]",
                "moderation", userId -> "[]",
                "audit_log", userId -> "[]"
        );
    }

    /**
     * 個人データを収集する。
     *
     * @param userId     ユーザーID
     * @param categories 収集するカテゴリキーのリスト（nullで全カテゴリ）
     * @return 収集したデータのリスト（各要素はカテゴリキーとJSON文字列のペア）
     */
    public List<CategoryData> collect(Long userId, List<String> categories) {
        Set<String> targetCategories = categories == null
                ? collectors.keySet()
                : Set.copyOf(categories);

        List<CategoryData> result = new ArrayList<>();

        for (String category : targetCategories) {
            Function<Long, String> collectorFn = collectors.get(category);
            if (collectorFn == null) {
                continue;
            }
            try {
                String json = collectorFn.apply(userId);
                result.add(new CategoryData(category, json));
            } catch (Exception e) {
                log.error("カテゴリ収集失敗: category={}, userId={}", category, userId, e);
                result.add(new CategoryData(category, "[]"));
            }
        }

        return result;
    }

    /**
     * 利用可能なカテゴリキーのリストを返す。
     */
    public List<String> getCategoryKeys() {
        return List.copyOf(collectors.keySet());
    }

    /**
     * カテゴリデータを保持するレコード。
     */
    public record CategoryData(String category, String json) {
    }
}
