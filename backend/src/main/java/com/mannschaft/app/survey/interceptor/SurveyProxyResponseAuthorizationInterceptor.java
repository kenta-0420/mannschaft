package com.mannschaft.app.survey.interceptor;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.CommonErrorCode;
import com.mannschaft.app.common.SecurityUtils;
import com.mannschaft.app.organization.service.OrganizationHierarchyService;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.ProxyInputContext.SurveyResponseOperation;
import com.mannschaft.app.proxy.service.ProxyInputConsentService;
import com.mannschaft.app.survey.SurveyErrorCode;
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
    private final ObjectProvider<OrganizationMembershipService> membershipProvider;

    public SurveyProxyResponseAuthorizationInterceptor(ObjectProvider<ProxyInputContext> contextProvider,
            ObjectProvider<ProxyInputConsentService> consentServiceProvider,
            ObjectProvider<SurveyService> surveyServiceProvider,
            ObjectProvider<AccessControlService> accessControlProvider,
            ObjectProvider<OrganizationHierarchyService> hierarchyProvider,
            ObjectProvider<OrganizationMembershipService> membershipProvider) {
        this.contextProvider = contextProvider;
        this.consentServiceProvider = consentServiceProvider;
        this.surveyServiceProvider = surveyServiceProvider;
        this.accessControlProvider = accessControlProvider;
        this.hierarchyProvider = hierarchyProvider;
        this.membershipProvider = membershipProvider;
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
        if (!context.isProxy() || context.getConsentId() == null || !context.hasSurveyScope()
                || !Objects.equals(context.getValidatedActorUserId(), actorUserId)) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        Long organizationId = consentServiceProvider.getObject().getValidSurveyInputConsentOrganizationId(
                context.getConsentId(), actorUserId, context.getSubjectUserId());
        AccessControlService accessControl = accessControlProvider.getObject();
        boolean systemAdmin = accessControl.isSystemAdmin(actorUserId);
        // 自組合の実行資格を先に確認し、資格のない主体へ実体の実在差を返さない。
        if (!systemAdmin) {
            accessControl.checkPermission(actorUserId, organizationId, "ORGANIZATION", "PROXY_INPUT_EXECUTE");
        }
        SurveyService.ResponseScope scope = surveyServiceProvider.getObject().getResponseScope(surveyId);
        boolean withinConsent = organizationId != null && scope.scopeId() != null
                && ("ORGANIZATION".equals(scope.scopeType())
                    ? organizationId.equals(scope.scopeId())
                    : "TEAM".equals(scope.scopeType()) && hierarchyProvider.getObject()
                        .getAnchorOrgIdsByTeamIds(List.of(scope.scopeId())).contains(organizationId));
        if (!withinConsent) {
            // 正本の判定順: SYS → 実体スコープの管理資格 → 配下を含む閲覧資格。
            // 非TXの入口で判定し、可知な範囲不一致403と私有IDの不在404を区別する。
            boolean canKnowSurvey = systemAdmin
                    || accessControl.isAdminOrAbove(actorUserId, scope.scopeId(), scope.scopeType())
                    || accessControl.isMemberOrDescendant(actorUserId, scope.scopeId(), scope.scopeType(), true);
            if (!canKnowSurvey) {
                throw new BusinessException(SurveyErrorCode.SURVEY_NOT_FOUND);
            }
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        // 代理者の実行資格とは別に、本人の回答資格を配信母集団の正本で確認する。
        // GET_MEは終了後・非対象となった既回答の取得を維持し、通常本人経路には影響させない。
        if (operation == SurveyResponseOperation.SUBMIT && "ORGANIZATION".equals(scope.scopeType())
                && scope.allDistribution() && !membershipProvider.getObject().isInOrgDistributionAudience(
                        scope.scopeId(), context.getSubjectUserId(), scope.includeSupporters())) {
            throw new BusinessException(CommonErrorCode.COMMON_002);
        }
        context.authorizeSurveyResponse(actorUserId, surveyId, operation);
        return true;
    }
}
