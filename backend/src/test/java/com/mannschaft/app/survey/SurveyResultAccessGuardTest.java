package com.mannschaft.app.survey;

import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.survey.entity.SurveyEntity;
import com.mannschaft.app.survey.service.SurveyResultAccessGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 試練（#2779）— {@link SurveyResultAccessGuard} の単体テスト。
 *
 * <p>結果閲覧可否の判定は 403 を投げる経路（{@code SurveyResultService#validateResultAccess}）と
 * 詳細応答の {@code viewerCanViewResults} の<b>両方が同じ 1 箇所</b>を使うことで一致する。
 * 本テストはその 1 箇所が
 * 「作成者高速パス → {@link ContentVisibilityChecker} への委譲」以外の独自述語を
 * 持たないことを固定する（独自述語は情報漏洩源になるため）。</p>
 *
 * <p>担保する受け入れ条件: <b>AC-1 / AC-2 / AC-4 / AC-8 / AC-10</b>。</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SurveyResultAccessGuard 単体テスト（#2779）")
class SurveyResultAccessGuardTest {

    private static final Long SURVEY_ID = 100L;
    private static final Long CREATOR_ID = 10L;
    private static final Long OTHER_USER_ID = 20L;

    @Mock
    private ContentVisibilityChecker contentVisibilityChecker;

    @InjectMocks
    private SurveyResultAccessGuard policy;

    /** AC-1 — 可視性基盤が許可すれば true。 */
    @Test
    @DisplayName("AC-1: 可視性基盤が許可すれば true")
    void ac1_allowedByChecker() {
        given(contentVisibilityChecker.canView(ReferenceType.SURVEY, SURVEY_ID, OTHER_USER_ID))
                .willReturn(true);

        assertThat(policy.canViewResults(survey(), OTHER_USER_ID)).isTrue();
    }

    /** AC-2 — 可視性基盤が拒否すれば false。 */
    @Test
    @DisplayName("AC-2: 可視性基盤が拒否すれば false")
    void ac2_deniedByChecker() {
        given(contentVisibilityChecker.canView(ReferenceType.SURVEY, SURVEY_ID, OTHER_USER_ID))
                .willReturn(false);

        assertThat(policy.canViewResults(survey(), OTHER_USER_ID)).isFalse();
    }

    /** AC-4 — 作成者は可視性基盤を呼ぶまでもなく true（優先順 1・既存の高速パス）。 */
    @Test
    @DisplayName("AC-4: 作成者は可視性基盤を呼ばずに true")
    void ac4_creatorFastPath() {
        assertThat(policy.canViewResults(survey(), CREATOR_ID)).isTrue();

        verifyNoInteractions(contentVisibilityChecker);
    }

    /** AC-8 — 未認証（userId が null）は fail-closed で false。 */
    @Test
    @DisplayName("AC-8: 未認証（userId=null）は fail-closed で false")
    void ac8_anonymousIsFailClosed() {
        assertThat(policy.canViewResults(survey(), null)).isFalse();

        verify(contentVisibilityChecker, never()).canView(any(), anyLong(), anyLong());
    }

    /** AC-10 — 判定 1 回につき可視性基盤の呼び出しは 1 回だけ（余分なクエリを足さない）。 */
    @Test
    @DisplayName("AC-10: 判定 1 回につき可視性基盤の呼び出しは 1 回のみ")
    void ac10_singleCheckerCall() {
        given(contentVisibilityChecker.canView(ReferenceType.SURVEY, SURVEY_ID, OTHER_USER_ID))
                .willReturn(true);

        policy.canViewResults(survey(), OTHER_USER_ID);

        verify(contentVisibilityChecker, times(1))
                .canView(ReferenceType.SURVEY, SURVEY_ID, OTHER_USER_ID);
    }

    private SurveyEntity survey() {
        SurveyEntity entity = SurveyEntity.builder()
                .scopeType("TEAM")
                .scopeId(1L)
                .title("結果閲覧可否テスト")
                .status(SurveyStatus.PUBLISHED)
                .resultsVisibility(ResultsVisibility.ALWAYS)
                .createdBy(CREATOR_ID)
                .build();
        org.springframework.test.util.ReflectionTestUtils.setField(entity, "id", SURVEY_ID);
        return entity;
    }
}
