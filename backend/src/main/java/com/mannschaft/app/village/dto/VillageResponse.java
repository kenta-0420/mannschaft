package com.mannschaft.app.village.dto;

import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import lombok.Builder;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 村レスポンス DTO（F17.1 §4.1.2 詳細 / §4.2 検索結果共通）。
 *
 * <p>セキュリティ: 個人特定情報（実名・user_id）は含めない。</p>
 *
 * @param id               村 ID
 * @param slug             スラッグ
 * @param name             村名
 * @param description      説明文
 * @param type             村種別
 * @param joinPolicy       参加方式
 * @param visibility       可視性
 * @param bulletinVisibility 掲示板公開範囲（PUBLIC / MEMBERS_ONLY）
 * @param category         カテゴリ
 * @param iconUrl          アイコンの表示用 URL（署名付き GET URL。未設定 / 解決失敗時は {@code null}）
 * @param coverUrl         カバー画像の表示用 URL（署名付き GET URL。未設定 / 解決失敗時は {@code null}）
 * @param monshoUrl        村紋の表示用 URL（署名付き GET URL。未設定 / 解決失敗時は {@code null}）
 * @param guidelineMd      ガイドライン
 * @param memberCount      現役メンバー数（キャッシュ値）
 * @param isOfficial       OFFICIAL 種別かどうか
 * @param isMember         呼び出しユーザーがこの村のメンバーか
 * @param isPinned         呼び出しユーザーがこの村をピン留め済みか
 * @param myRole           呼び出しユーザーの村内ロール（メンバーのみ）
 * @param archivedAt       凍結日時（null なら未凍結）
 * @param createdAt        作成日時
 * @param updatedAt        更新日時
 * @param version          楽観ロック用バージョン
 */
@Builder
public record VillageResponse(
        UUID id,
        String slug,
        String name,
        String description,
        VillageType type,
        VillageJoinPolicy joinPolicy,
        VillageVisibility visibility,
        VillageBulletinVisibility bulletinVisibility,
        String category,
        String iconUrl,
        String coverUrl,
        String monshoUrl,
        String guidelineMd,
        long memberCount,
        boolean isOfficial,
        boolean isMember,
        boolean isPinned,
        VillageRole myRole,
        LocalDateTime archivedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version
) {}
