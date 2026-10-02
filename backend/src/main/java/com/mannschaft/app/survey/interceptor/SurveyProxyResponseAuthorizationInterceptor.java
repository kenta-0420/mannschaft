package com.mannschaft.app.survey.interceptor;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationHierarchyService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.ProxyInputContext.SurveyResponseOperation;
import com.mannschaft.app.proxy.entity.ProxyInputConsentScopeEntity.FeatureScope;
import com.mannschaft.app.proxy.service.ProxyInputConsentService;
import com.mannschaft.app.survey.controller.SurveyResponseController;
import com.mannschaft.app.survey.service.SurveyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 回答の業務TXへ入る前に、代理主体・同意・実体スコープ・実行権限を束縛する。 */
public class SurveyProxyResponseAuthorizationInterceptor implements HandlerInterceptor {
    private final ObjectProvider<ProxyInputContext> contextProvider;
    private final ObjectProvider<ProxyInputConsentService> consentServiceProvider;
    private final ObjectProvider<SurveyService> surveyServiceProvider;
    private final ObjectProvider<AccessControlService> accessControlProvider;
    private final ObjectProvider<OrganizationHierarchyService> hierarchyProvider;

    public SurveyProxyResponseAuthorizationInterceptor(ObjectProvider<ProxyInputContext> contextProvider,
            ObjectProvider<ProxyInputConsentService> consentServiceProvider,
            ObjectProvider<SurveyService> surveyServiceProvider,
            ObjectProvider<AccessControlService> accessControlProvider,
            ObjectProvider<OrganizationHierarchyService> hierarchyProvider) {
        this.contextProvider = contextProvider;
        this.consentServiceProvider = consentServiceProvider;
        this.surveyServiceProvider = surveyServiceProvider;
        this.accessControlProvider = accessControlProvider;
        this.hierarchyProvider = hierarchyProvider;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)
                || !SurveyResponseController.class.isAssignableFrom(method.getBeanType())
                || request.getHeader("X-Proxy-For-User-Id") == null) {
            return true;
        }
        SurveyResponseOperation operation;
        if (method.getMethod().getName().equals("submitResponse")) {
            operation = SurveyResponseOperation.SUBMIT;
        } else if (method.getMethod().getName().equals("getMyResponses")) {
            operation = SurveyResponseOperation.GET_ME;
        } else {
            return true;
        }

        @SuppressWarnings("unchecked")
        Map<String, String> variables = (Map<String, String>) request.getAttribute(
                HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String rawSurveyId = variables == null ? null : variables.get("surveyId");
        Long surveyId;
        try {
            surveyId = Long.valueOf(rawSurveyId);
        } catch (NumberFormatException exception) {
            throw new MethodArgumentTypeMismatchException(rawSurveyId, Long.class, "surveyId",
                    method.getMethodParameters()[0], exception);
        }

        ProxyInputContext context = contextProvider.getObject();
        Long actorUserId = SecurityUtils.getCurrentUserId();
        if (!context.isProxy() || context.getConsentId() == null || !context.hasScope(FeatureScope.SURVEY)
                || !Objects.equals(context.getValidatedActorUserId(), actorUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        Long organizationId = consentServiceProvider.getObject().getValidInputConsentOrganizationId(
                context.getConsentId(), actorUserId, context.getSubjectUserId(), FeatureScope.SURVEY);
        SurveyService.ResponseScope scope = surveyServiceProvider.getObject().getResponseScope(surveyId);
        boolean withinConsent = organizationId != null && scope.scopeId() != null
                && ("ORGANIZATION".equals(scope.scopeType())
                    ? organizationId.equals(scope.scopeId())
                    : "TEAM".equals(scope.scopeType()) && hierarchyProvider.getObject()
                        .getAnchorOrgIdsByTeamIds(List.of(scope.scopeId())).contains(organizationId));
        if (!withinConsent) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        AccessControlService accessControl = accessControlProvider.getObject();
        if (!accessControl.isSystemAdmin(actorUserId)) {
            accessControl.checkPermission(actorUserId, organizationId, "ORGANIZATION", "PROXY_INPUT_EXECUTE");
        }
        context.authorizeSurveyResponse(actorUserId, surveyId, operation);
        return true;
    }
}
