package com.mannschaft.app.proxy;

import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity.FeatureScope;
import lombok.Getter;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;

/**
 * リクエストスコープで代理入力状態を保持するBean（F14.1）。
 * ProxyInputContextFilterがヘッダーを検証してactivate()し、
 * Service層はisProxy()で代理入力モードかどうかを判定する。
 *
 * <p>F08.9 P3b: 同意書で許可されたスコープ集合を保持し、決済系 Service が
 * {@link #hasScope(FeatureScope)} で要求スコープ（例 {@code PAYMENT}）の有無を検証できる。</p>
 */
@Component
@RequestScope
@Getter
public class ProxyInputContext {

    private boolean proxyMode = false;
    private Long subjectUserId;
    private Long consentId;
    private Long validatedActorUserId;
    @Getter(lombok.AccessLevel.NONE)
    private SurveyResponseAuthorization surveyResponseAuthorization;
    private String inputSource;
    private String originalStorageLocation;

    /** 同意書で許可された機能スコープ集合（F08.9 P3b）。 */
    private final Set<FeatureScope> scopes = new HashSet<>();

    public boolean isProxy() {
        return proxyMode;
    }

    public void activate(Long subjectUserId, Long consentId,
                         String inputSource, String originalStorageLocation) {
        activate(subjectUserId, consentId, inputSource, originalStorageLocation, Set.of());
    }

    /**
     * 同意書で許可されたスコープ集合を伴って代理入力モードを有効化する（F08.9 P3b）。
     */
    public void activate(Long subjectUserId, Long consentId,
                         String inputSource, String originalStorageLocation,
                         Set<FeatureScope> scopes) {
        activate(subjectUserId, consentId, inputSource, originalStorageLocation, scopes, null);
    }

    /** フィルターで同意書を検証した認証主体を保持する。 */
    public void activate(Long subjectUserId, Long consentId,
                         String inputSource, String originalStorageLocation,
                         Set<FeatureScope> scopes, Long validatedActorUserId) {
        this.surveyResponseAuthorization = null;
        // 防御的バリデーション（検分 P3c 🔵）。subjectUserId / inputSource は
        // proxy_input_records の NOT NULL 列・enum 解決の前提となるため必須。
        // consentId / originalStorageLocation は後見切替（GUARDIANSHIP_SWITCH）で
        // null / 固定値を許容するため要求しない。
        if (subjectUserId == null) {
            throw new IllegalArgumentException("subjectUserId は必須です");
        }
        if (inputSource == null || inputSource.isBlank()) {
            throw new IllegalArgumentException("inputSource は必須です");
        }
        this.proxyMode = true;
        this.subjectUserId = subjectUserId;
        this.consentId = consentId;
        this.validatedActorUserId = validatedActorUserId;
        this.inputSource = inputSource;
        this.originalStorageLocation = originalStorageLocation;
        this.scopes.clear();
        if (scopes != null) {
            this.scopes.addAll(scopes);
        }
    }

    /**
     * 代理入力モードかつ指定スコープが同意書で許可されているかを返す（F08.9 P3b）。
     *
     * <p>代理入力モードでない場合（本人操作）は常に {@code false} を返す。
     * 決済系 Service は「代理払いには {@code PAYMENT} スコープが必須」のような
     * 要求スコープ検証にこれを使う。</p>
     */
    public boolean hasScope(FeatureScope scope) {
        return proxyMode && scope != null && scopes.contains(scope);
    }

    /** MVCの事前認可結果を対象アンケートと操作へ束縛する。 */
    public void authorizeSurveyResponse(Long actorUserId, Long surveyId, SurveyResponseOperation operation) {
        if (!proxyMode || actorUserId == null || surveyId == null || operation == null
                || consentId == null || !Objects.equals(validatedActorUserId, actorUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        surveyResponseAuthorization = new SurveyResponseAuthorization(
                actorUserId, subjectUserId, consentId, surveyId, operation);
    }

    /** 事前認可済みの同一主体・同意・対象・操作に限って本人IDを返す。 */
    public Long requireSurveyResponseSubject(Long actorUserId, Long surveyId, SurveyResponseOperation operation) {
        SurveyResponseAuthorization expected = new SurveyResponseAuthorization(
                actorUserId, subjectUserId, consentId, surveyId, operation);
        if (!proxyMode || actorUserId == null || !Objects.equals(validatedActorUserId, actorUserId)
                || !expected.equals(surveyResponseAuthorization)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        return subjectUserId;
    }

    public enum SurveyResponseOperation { SUBMIT, GET_ME }

    private record SurveyResponseAuthorization(Long actorUserId, Long subjectUserId,
                                               Long consentId, Long surveyId,
                                               SurveyResponseOperation operation) { }

    public void clear() {
        this.proxyMode = false;
        this.subjectUserId = null;
        this.consentId = null;
        this.validatedActorUserId = null;
        this.surveyResponseAuthorization = null;
        this.inputSource = null;
        this.originalStorageLocation = null;
        this.scopes.clear();
    }
}
