package com.mannschaft.app.gdpr.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * 個人データコレクター。
 * 各カテゴリのデータ収集ロジックを提供し、登録済みカテゴリキーを管理する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonalDataCollector {

    /**
     * 登録済みのカテゴリキーセットを返す。
     * PersonalDataCoverageValidator が @PersonalData 付きエンティティとの整合性チェックに使用する。
     *
     * @return カテゴリキーのセット
     */
    public Set<String> getCategoryKeys() {
        // TODO: F12.3 足軽4（Service部隊）が各カテゴリのコレクター登録後に完全実装予定
        return Set.of(
                "account",
                "charts",
                "payments",
                "chatMessages",
                "auditLogs",
                "oauthAccounts"
        );
    }
}
