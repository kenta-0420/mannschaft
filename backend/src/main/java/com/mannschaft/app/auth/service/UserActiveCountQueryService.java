package com.mannschaft.app.auth.service;

import com.mannschaft.app.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * F10.1.1 / P3b Wave2: user(auth) ドメインの「アクティブユーザー件数」read Query Service。
 *
 * <p>他ドメイン（dashboard ファサード等）が「ある user_id 集合のうちアクティブ
 * （{@code users.status='ACTIVE'} かつ未削除）なユーザー数」を必要とするとき、
 * {@link UserRepository} を直接参照させずに本サービス経由で取得させる（ドメイン境界厳守・
 * CLAUDE.md「データ取得は Service のメソッド呼び出し経由で行う」）。</p>
 *
 * <p>読み取り専用・単一ドメイン内のため {@code @Transactional(readOnly=true)} はドメイン内に閉じる。</p>
 *
 * <p>設計書: docs/features/F10.1.1_team_org_admin_console/02_admin_lens_widgets.md §2.2④ / §2.3④</p>
 */
@Service
@RequiredArgsConstructor
public class UserActiveCountQueryService {

    private final UserRepository userRepository;

    /**
     * 指定 user_id 集合のうち、アクティブ（{@code status='ACTIVE'} かつ {@code deleted_at IS NULL}）な
     * ユーザー数を返す。空集合の場合は 0 を返す（{@code IN ()} を発行しない）。
     *
     * @param userIds 対象 user_id 集合
     * @return アクティブユーザー数
     */
    @Transactional(readOnly = true)
    public long countActive(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return 0L;
        }
        return userRepository.countActiveByUserIds(userIds);
    }
}
