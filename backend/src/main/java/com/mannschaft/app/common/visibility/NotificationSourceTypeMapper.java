package com.mannschaft.app.common.visibility;

import java.util.Map;
import java.util.Optional;

/**
 * 通知発行時の {@code sourceType} 文字列を {@link ReferenceType} にマッピングするヘルパ。
 *
 * <p>F00 Phase F セキュリティ漏れ修正で導入。{@link com.mannschaft.app.notification.entity.NotificationEntity}
 * の {@code sourceType} カラムは歴史的経緯から自由文字列となっており、
 * {@link ReferenceType} と完全一致しない値も多い ({@code MEMBER_PAYMENT}、
 * {@code JOB_CONTRACT} 等)。本クラスはその対応関係を一元化する。
 *
 * <p><strong>fail-soft 原則 (Phase F)</strong>: 設計書 §11.2 では fail-closed
 * (未対応 ReferenceType は false 返却) が原則だが、通知発行側では
 * {@code sourceType} が ReferenceType に対応しない場合 {@link Optional#empty()}
 * を返し、呼び出し側に「visibility チェック対象外」であることを伝える。
 * これにより既存の通知 ({@code MEMBER_PAYMENT}・{@code JOB_CONTRACT} 等
 * Resolver 未配備の sourceType) を破壊することなく、Resolver 配備済の
 * type のみガードする漸進的な強化が可能となる。
 *
 * <p>新しい sourceType を追加した場合は本クラスの {@link #MAPPING} に
 * 対応する {@link ReferenceType} を登録すること。Phase B 以降で Resolver が
 * 順次配備されると、対応 sourceType の通知は自動的に visibility ガード
 * の対象となる。
 *
 * <p>設計書: {@code docs/features/F00_content_visibility_resolver.md} §11.1 / §19.3。
 */
public final class NotificationSourceTypeMapper {

    /**
     * 通知 sourceType 文字列 → ReferenceType の対応表。
     *
     * <p>キーは大文字 ASCII 文字列 (NotificationEntity.sourceType の規約)。
     * Phase F 着手時の grep 棚卸しで現実コードベースから抽出した既知の値を
     * 列挙する。Resolver 未配備の type も含む (Phase B 以降で順次有効化)。
     */
    private static final Map<String, ReferenceType> MAPPING = Map.ofEntries(
        // F03.5 / F03.15 — スケジュール / 個人時間割
        Map.entry("SCHEDULE", ReferenceType.SCHEDULE),
        Map.entry("PERSONAL_TIMETABLE", ReferenceType.PERSONAL_TIMETABLE),

        // F09.8 / F09.8.1 — タイムライン / コルクボード
        Map.entry("TIMELINE_POST", ReferenceType.TIMELINE_POST),
        Map.entry("CHAT_MESSAGE", ReferenceType.CHAT_MESSAGE),
        Map.entry("BULLETIN_THREAD", ReferenceType.BULLETIN_THREAD),

        // F02 — CMS (ブログ) / イベント / 活動結果
        Map.entry("BLOG_POST", ReferenceType.BLOG_POST),
        Map.entry("EVENT", ReferenceType.EVENT),
        Map.entry("ACTIVITY_RESULT", ReferenceType.ACTIVITY_RESULT),

        // F04 / F05 — 募集 / アンケート / 大会
        Map.entry("RECRUITMENT_LISTING", ReferenceType.RECRUITMENT_LISTING),
        Map.entry("RECRUITMENT", ReferenceType.RECRUITMENT_LISTING),
        Map.entry("SURVEY", ReferenceType.SURVEY),
        Map.entry("TOURNAMENT", ReferenceType.TOURNAMENT),

        // F08 — 求人マッチング
        Map.entry("JOB_POSTING", ReferenceType.JOB_POSTING),

        // F04.10 — 回覧
        Map.entry("CIRCULATION_DOCUMENT", ReferenceType.CIRCULATION_DOCUMENT),
        Map.entry("CIRCULATION", ReferenceType.CIRCULATION_DOCUMENT),

        // 写真アルバム / コメント / ファイル添付 / チーム / 組織
        Map.entry("PHOTO_ALBUM", ReferenceType.PHOTO_ALBUM),
        Map.entry("COMMENT", ReferenceType.COMMENT),
        Map.entry("FILE_ATTACHMENT", ReferenceType.FILE_ATTACHMENT),
        Map.entry("TEAM", ReferenceType.TEAM),
        Map.entry("ORGANIZATION", ReferenceType.ORGANIZATION)
    );

    private NotificationSourceTypeMapper() {
        // util class
    }

    /**
     * 通知の {@code sourceType} 文字列を {@link ReferenceType} に解決する。
     *
     * @param sourceType 通知 sourceType 文字列 ({@code null} 可)
     * @return 対応する {@link ReferenceType}。未対応または {@code null} の場合は空
     */
    public static Optional<ReferenceType> resolve(String sourceType) {
        if (sourceType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(MAPPING.get(sourceType));
    }

    /**
     * 指定 {@code sourceType} が ReferenceType にマッピング済みかを返す。
     *
     * <p>テストやログ判定で「visibility ガード対象 sourceType か」を
     * 一括判定したい用途向け。
     *
     * @param sourceType 通知 sourceType 文字列 ({@code null} 可)
     * @return マッピング済なら true
     */
    public static boolean isMapped(String sourceType) {
        return resolve(sourceType).isPresent();
    }
}
