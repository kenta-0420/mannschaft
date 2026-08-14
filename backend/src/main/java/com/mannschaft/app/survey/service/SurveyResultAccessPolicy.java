package com.mannschaft.app.survey.service;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.survey.entity.SurveyEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 「この利用者はこのアンケートの結果を閲覧できるか」の<b>唯一の判定点</b>。
 *
 * <p>結果取得 API が 403 を投げるか否か（{@link SurveyResultService} の結果閲覧ガード）と、
 * アンケート詳細応答が返す {@code viewerCanViewResults} は、必ず本クラスを経由する。
 * 判定点を 1 箇所に閉じ込めることで、<b>応答が「見られる」と言っているのに実際は 403</b>
 * （またはその逆）という食い違いを構造的に起こせなくする（Issue #2779）。</p>
 *
 * <p>判定の中身は以下の 2 段だけであり、独自述語は一切書かない
 * （独自述語は情報漏洩源になるため、可視性の判断は F00 の
 * {@link ContentVisibilityChecker} 経由に統一するのがリポジトリの方針）:</p>
 * <ol>
 *   <li><b>作成者高速パス</b> — 作成者本人は結果公開設定に関わらず常に閲覧可。
 *       {@code SurveyVisibilityResolver} は CUSTOM の意味論を厳密に保つため
 *       この既存挙動を持たないので、ここで担う。</li>
 *   <li>それ以外は {@link ContentVisibilityChecker#canView} へ委譲。
 *       ADMIN+ ・結果閲覧者名簿・{@code ResultsVisibility} × status の合成は
 *       すべて {@code SurveyVisibilityResolver} が一元処理する。</li>
 * </ol>
 *
 * <p>未認証（{@code userId == null}）・実体不在は fail-closed で {@code false} を返す。</p>
 */
@Component
@RequiredArgsConstructor
public class SurveyResultAccessPolicy {

    private final ContentVisibilityChecker contentVisibilityChecker;

    /**
     * 結果を閲覧できるかを判定する。
     *
     * <p>可視性基盤の呼び出しは 1 回だけであり、作成者の場合はそれすら発行しない
     * （詳細取得に判定を載せても余分なクエリが増えないようにするため）。</p>
     *
     * @param survey 対象アンケート（{@code null} 可）
     * @param userId 閲覧者ユーザーID（{@code null} 可 = 未認証）
     * @return 閲覧できるなら {@code true}
     */
    public boolean canViewResults(SurveyEntity survey, Long userId) {
        if (survey == null || survey.getId() == null || userId == null) {
            return false;
        }
        if (survey.getCreatedBy() != null && survey.getCreatedBy().equals(userId)) {
            return true;
        }
        return contentVisibilityChecker.canView(ReferenceType.SURVEY, survey.getId(), userId);
    }
}
