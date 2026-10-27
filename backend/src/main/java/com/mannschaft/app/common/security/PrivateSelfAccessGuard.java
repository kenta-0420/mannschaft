package com.mannschaft.app.common.security;

import com.mannschaft.app.auth.UserOperationErrorCode;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;

/** 本人非公開APIのHTTP入口。管理者変身をtrusted属性で拒否し、本人IDはprincipalからだけ得る。 */
@Component
public class PrivateSelfAccessGuard {
    public Long requireSelfAccess(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (request.getAttribute("originalAdminId") != null) {
            throw new BusinessException(UserOperationErrorCode.NOT_ALLOWED);
        }
        return SecurityUtils.getCurrentUserId();
    }
}
