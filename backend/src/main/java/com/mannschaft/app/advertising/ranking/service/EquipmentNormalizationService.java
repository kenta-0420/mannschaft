package com.mannschaft.app.advertising.ranking.service;

import org.springframework.stereotype.Service;

/**
 * 備品名の名寄せサービス。
 * 異なるチームが同じ備品を異なる表記で登録している場合に、グループ化キーとなる
 * normalized_name を生成する。
 *
 * <p>正規化手順:</p>
 * <ol>
 *   <li>全角英数字・記号 → 半角変換</li>
 *   <li>大文字 → 小文字変換（英字）</li>
 *   <li>括弧内の補足（例: 「（赤）」「（会議室用）」「(EPSON)」）を除去</li>
 *   <li>先頭・末尾・連続スペースをトリム</li>
 *   <li>記号（・、#、.）を除去</li>
 * </ol>
 *
 * <p>例: 「プロジェクター（EPSON）」→「プロジェクター」</p>
 * <p>例: 「Ｗｈｉｔｅ Ｂｏａｒｄ」→「white board」</p>
 */
@Service
public class EquipmentNormalizationService {

    /**
     * 備品名を正規化する。
     *
     * @param name 元の備品名
     * @return 正規化済み備品名
     */
    public String normalize(String name) {
        if (name == null || name.isBlank()) return "";

        String result = name;

        // 1. 全角英数字・記号 → 半角変換
        result = toHalfWidth(result);

        // 2. 大文字 → 小文字
        result = result.toLowerCase();

        // 3. 括弧内コンテンツを除去（全角・半角両対応）
        result = result.replaceAll("[（(][^）)]*[）)]", "");

        // 4. 記号除去（・、#、. を除去。数字や文字はそのまま残す）
        result = result.replaceAll("[・#．]", "");

        // 5. 連続スペースをトリム
        result = result.replaceAll("\\s+", " ").strip();

        return result;
    }

    /**
     * 全角英数字・空白・記号を半角に変換する。
     */
    private String toHalfWidth(String input) {
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            // 全角英数字・記号 (U+FF01〜FF5E) → 半角 (U+0021〜007E)
            if (c >= '\uFF01' && c <= '\uFF5E') {
                sb.append((char) (c - '\uFF01' + '\u0021'));
            }
            // 全角スペース → 半角スペース
            else if (c == '\u3000') {
                sb.append(' ');
            }
            else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
