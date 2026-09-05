package com.mannschaft.app.common.duplicatename;

/**
 * CMP-260901-1538 柱③-A: 同名確認フローで提示する候補1件分。
 *
 * <p>可視性ルールに従い、PRIVATE スコープの候補は {@code nameVisible=false} とし
 * {@code name} は null（非公開の同名スコープが存在すること自体のみを示す）。
 * PUBLIC スコープの候補は {@code nameVisible=true} とし実名を含める。</p>
 *
 * @param id          候補の組織/チーム ID（文字列化）
 * @param nameVisible 名称を開示してよいか（PUBLIC のみ true）
 * @param name        名称。{@code nameVisible=false} の場合は必ず null
 */
public record DuplicateNameCandidate(String id, boolean nameVisible, String name) {

    public DuplicateNameCandidate {
        if (!nameVisible && name != null) {
            throw new IllegalArgumentException("非公開候補は name を保持してはならない（漏洩防止）");
        }
    }
}
