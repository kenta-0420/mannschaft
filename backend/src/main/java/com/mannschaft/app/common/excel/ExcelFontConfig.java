package com.mannschaft.app.common.excel;

import org.springframework.context.annotation.Configuration;

/**
 * Excel 生成用フォント設定。
 *
 * <p>NotoSansJP の Excel への完全な埋め込みは Apache POI では複雑かつ重い処理となるため、
 * クライアント側のフォント解決に委ねる方針を採る。具体的には
 * 「Noto Sans JP」 → クライアントに無ければ MS 系 / メイリオ 等にフォールバックする。
 *
 * <p>{@link com.mannschaft.app.config.PdfFontConfig} の Excel 版という位置付けだが、
 * PDF と異なりフォントの埋め込み登録は行わず、フォント名と既定サイズだけを集中管理する。
 */
@Configuration
public class ExcelFontConfig {

    /** Excel デフォルトフォント名（Noto Sans JP）。 */
    public String getDefaultFontName() {
        return "Noto Sans JP";
    }

    /** 本文セルのデフォルトフォントサイズ（pt）。 */
    public short getDefaultFontSize() {
        return (short) 11;
    }

    /** ヘッダー行のフォントサイズ（pt）。 */
    public short getHeaderFontSize() {
        return (short) 12;
    }
}
