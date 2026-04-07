package com.mannschaft.app.quickmemo.security;

import com.mannschaft.app.quickmemo.repository.QuickMemoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * ポイっとメモの所有権を検証する Spring Security 認可ガード。
 * {@code @PreAuthorize("@quickMemoAccessGuard.canAccess(#memoId, authentication)")} で使用する。
 */
@Component("quickMemoAccessGuard")
@RequiredArgsConstructor
public class QuickMemoAccessGuard {

    private final QuickMemoRepository quickMemoRepository;

    /**
     * 指定されたメモにアクセス可能かどうかを判定する。
     * メモが存在しない・論理削除済みの場合は false を返す（404 を返すためサービス層で別途チェック）。
     *
     * @param memoId         アクセス対象のメモID
     * @param authentication 現在の認証情報
     * @return 所有者であれば true
     */
    public boolean canAccess(Long memoId, Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Long currentUserId = Long.valueOf(authentication.getName());
        return quickMemoRepository.findByIdAndUserId(memoId, currentUserId).isPresent();
    }
}
