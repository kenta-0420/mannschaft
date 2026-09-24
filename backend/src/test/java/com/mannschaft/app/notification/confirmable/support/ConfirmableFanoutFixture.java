package com.mannschaft.app.notification.confirmable.support;

import jakarta.persistence.EntityManager;

import java.util.ArrayList;
import java.util.List;

/**
 * CMP-260920-1040 試練B: 確認通知の非同期配信ワーカー系テスト共通フィクスチャ。
 *
 * <p>{@code users} テーブルの NOT NULL 全列を明示的に充填した利用者を、1 回の多値 INSERT で
 * 大量投入する（{@code ConfirmableNotificationScopeContractIT#insertUser} の単発版を、
 * 1,201 人規模のチャンク処理系テスト向けにバルク化したもの）。</p>
 */
public final class ConfirmableFanoutFixture {

    private ConfirmableFanoutFixture() {
    }

    /**
     * {@code count} 人の利用者を一括投入し、生成された ID を昇順で返す。
     *
     * @param em          EntityManager
     * @param count       投入人数
     * @param emailPrefix 実行ごとに一意な接頭辞（例: {@code "cfx-" + UUID.randomUUID()}）。
     *                    末尾に {@code -<連番>@example.com} を付けてメールアドレスを作る
     * @return 生成された user.id のリスト（昇順・{@code count} 件）
     */
    @SuppressWarnings("unchecked")
    public static List<Long> insertUsers(EntityManager em, int count, String emailPrefix) {
        StringBuilder sql = new StringBuilder(
                "INSERT INTO users (email, last_name, first_name, display_name, status, "
                        + "is_searchable, handle_searchable, contact_approval_required, "
                        + "online_visibility, dm_receive_from, encryption_key_version, "
                        + "locale, timezone, reporting_restricted, follow_list_visibility, "
                        + "care_notification_enabled, offline_only, created_at, updated_at) VALUES ");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append("('").append(emailPrefix).append('-').append(i).append("@example.com', ")
                    .append("'CFX', 'テスト', 'CFXテスト', 'ACTIVE', 1, 1, 1, 'NOBODY', 'ANYONE', 1, ")
                    .append("'ja', 'Asia/Tokyo', 0, 'PUBLIC', 1, 0, NOW(), NOW())");
        }
        em.createNativeQuery(sql.toString()).executeUpdate();

        List<Object> rows = em.createNativeQuery(
                        "SELECT id FROM users WHERE email LIKE :prefix ORDER BY id")
                .setParameter("prefix", emailPrefix + "-%")
                .getResultList();
        List<Long> ids = new ArrayList<>(rows.size());
        for (Object o : rows) {
            ids.add(((Number) o).longValue());
        }
        return ids;
    }

    /** 指定した接頭辞で投入した利用者をすべて削除する（{@code @AfterEach} 用）。 */
    public static void deleteUsers(EntityManager em, String emailPrefix) {
        em.createNativeQuery("DELETE FROM users WHERE email LIKE :prefix")
                .setParameter("prefix", emailPrefix + "-%")
                .executeUpdate();
    }
}
