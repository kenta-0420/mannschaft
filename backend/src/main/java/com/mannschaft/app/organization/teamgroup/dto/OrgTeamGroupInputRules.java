package com.mannschaft.app.organization.teamgroup.dto;

/**
 * チームグループの入力規則（F01.2.1 §5.2・AC-G113）。リクエスト DTO の Bean Validation と
 * サービス層の正規化が同じ規則を共有する。
 *
 * <p>長さは UTF-16 単位ではなくコードポイント数で数える（絵文字などのサロゲートペアを1文字と数える）。
 * 名前は前後の空白を除いて 1〜50、説明は 200 まで。</p>
 */
public final class OrgTeamGroupInputRules {

    /** 名前の最大コードポイント数 */
    public static final int NAME_MAX = 50;

    /** 説明の最大コードポイント数 */
    public static final int DESCRIPTION_MAX = 200;

    private OrgTeamGroupInputRules() {
    }

    /** 前後の空白（全角空白を含む Unicode 空白）を除く。null は null。 */
    public static String normalize(String value) {
        return value == null ? null : value.strip();
    }

    /** 名前として有効か（前後の空白を除いて 1〜50 コードポイント）。null は無効。 */
    public static boolean isValidName(String name) {
        if (name == null) {
            return false;
        }
        String trimmed = name.strip();
        int length = trimmed.codePointCount(0, trimmed.length());
        return length >= 1 && length <= NAME_MAX;
    }

    /** 説明として有効か（200 コードポイント以内）。null と空は有効（消去を表す）。 */
    public static boolean isValidDescription(String description) {
        if (description == null) {
            return true;
        }
        String trimmed = description.strip();
        return trimmed.codePointCount(0, trimmed.length()) <= DESCRIPTION_MAX;
    }

    /** 説明を保存形に直す。空白だけ・空は null（消去）。 */
    public static String normalizeDescription(String description) {
        String trimmed = normalize(description);
        return (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
    }
}
