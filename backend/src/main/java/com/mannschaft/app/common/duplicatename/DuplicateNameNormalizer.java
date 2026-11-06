package com.mannschaft.app.common.duplicatename;

import com.mannschaft.app.common.annotation.TransitionalBridge;
import com.mannschaft.app.organization.api.DuplicateNameNormalizerApi;

/**
 * 組織所有の名称正規化へ委譲する旧FQCNの互換入口。
 *
 * <p>既存の同名確認・組織・チームの呼出元を維持するために残す。
 * 呼出側をL2で公開窓口へ移行し、L5でこの橋渡しを撤去する。</p>
 *
 * @deprecated 組織の公開窓口 {@link DuplicateNameNormalizerApi} を使用する
 */
@Deprecated
@TransitionalBridge(
        reason = "同名確認・組織・チームの既存static呼出をL2移行まで維持するため",
        removalLane = "L5（呼出側はL2で移行）")
public final class DuplicateNameNormalizer {

    private DuplicateNameNormalizer() {
    }

    /**
     * 先頭・末尾の半角スペース（U+0020）のみを除去する。{@code null} は空文字として扱う。
     *
     * @param value 正規化対象の文字列（{@code null} 可）
     * @return 先頭・末尾の半角スペースのみを除去した文字列
     */
    public static String trimSpaces(String value) {
        return DuplicateNameNormalizerApi.trimSpaces(value);
    }
}
