package com.mannschaft.app.config;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.organization.service.OrganizationHierarchyService;
import com.mannschaft.app.organization.service.OrganizationMembershipService;
import com.mannschaft.app.proxy.ProxyInputContext;
import com.mannschaft.app.proxy.service.ProxyInputConsentService;
import com.mannschaft.app.survey.interceptor.SurveyProxyResponseAuthorizationInterceptor;
import com.mannschaft.app.survey.service.SurveyService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 通常のMVCスライスでは代理用Serviceを解決せず、必要なリクエストだけ認可する。 */
@Configuration
public class SurveyProxyResponseWebMvcConfig implements WebMvcConfigurer {
    private final SurveyProxyResponseAuthorizationInterceptor interceptor;

    public SurveyProxyResponseWebMvcConfig(ObjectProvider<ProxyInputContext> contextProvider,
            ObjectProvider<ProxyInputConsentService> consentServiceProvider,
            ObjectProvider<SurveyService> surveyServiceProvider,
            ObjectProvider<AccessControlService> accessControlProvider,
            ObjectProvider<OrganizationHierarchyService> hierarchyProvider,
            ObjectProvider<OrganizationMembershipService> membershipProvider) {
        interceptor = new SurveyProxyResponseAuthorizationInterceptor(contextProvider, consentServiceProvider,
                surveyServiceProvider, accessControlProvider, hierarchyProvider, membershipProvider);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
