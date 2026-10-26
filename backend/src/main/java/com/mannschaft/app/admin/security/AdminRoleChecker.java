package com.mannschaft.app.admin.security;

import com.mannschaft.app.role.repository.UserRoleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Spring Security の @PreAuthorize で使用する ADMIN/DEPUTY_ADMIN ロールチェッカー（F10.7）。
 *
 * <p>認証済みユーザーが少なくとも 1 チームで ADMIN または DEPUTY_ADMIN ロールを持つかを判定する。
 * SpEL 式内では {@code @adminRoleChecker.hasAnyAdminRoleInAnyTeam(authentication)} で参照する。</p>
 */
@Component("adminRoleChecker")
@RequiredArgsConstructor
public class AdminRoleChecker {

    private final UserRoleRepository userRoleRepository;

    /**
     * 認証済みユーザーがいずれかのチームで ADMIN または DEPUTY_ADMIN ロールを持つかを返す。
     *
     * <p>JWT の principal にはユーザー ID が文字列で格納されているため、
     * {@code authentication.getName()} を Long にパースして判定する。</p>
     *
     * @param authentication Spring Security の認証情報
     * @return 1 チーム以上で ADMIN/DEPUTY_ADMIN を持つ場合は {@code true}
     */
    public boolean hasAnyAdminRoleInAnyTeam(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        try {
            Long userId = Long.parseLong(authentication.getName());
            return !userRoleRepository.findAdminAndDeputyAdminTeamIds(userId).isEmpty();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
