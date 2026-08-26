package com.mannschaft.app.skill.repository;

import com.mannschaft.app.skill.entity.MemberSkillEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

/**
 * メンバースキル・資格クエリリポジトリ（JdbcTemplate使用）。
 */
@Repository
@RequiredArgsConstructor
public class MemberSkillQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 同一資格の登録数を返す（重複チェック用）。
     *
     * @param userId     ユーザーID
     * @param scopeType  スコープ種別
     * @param scopeId    スコープID
     * @param categoryId カテゴリID（nullの場合はカテゴリ指定なし）
     * @param name       資格名
     * @return 件数
     */
    public int countByUserIdAndScopeAndCategoryAndName(
            Long userId, String scopeType, Long scopeId, Long categoryId, String name) {
        if (categoryId != null) {
            String sql = """
                    SELECT COUNT(*) FROM member_skills
                    WHERE user_id = ?
                      AND scope_type = ?
                      AND scope_id = ?
                      AND skill_category_id = ?
                      AND name = ?
                      AND deleted_at IS NULL
                    """;
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                    userId, scopeType, scopeId, categoryId, name);
            return count != null ? count : 0;
        } else {
            String sql = """
                    SELECT COUNT(*) FROM member_skills
                    WHERE user_id = ?
                      AND scope_type = ?
                      AND scope_id = ?
                      AND skill_category_id IS NULL
                      AND name = ?
                      AND deleted_at IS NULL
                    """;
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
                    userId, scopeType, scopeId, name);
            return count != null ? count : 0;
        }
    }

    /**
     * 指定期限以内に失効する資格のうち、指定通知種別がまだ送信されていないものを返す（期限リマインダー用）。
     *
     * <p><b>既に失効した資格は対象外とする（{@code expires_at >= today}）。</b>
     * 初版は上限（{@code expires_at <= threshold}）しか持たず、
     * <b>期限を過ぎた ACTIVE 資格まで拾っていた</b>。その結果、
     * 障害や停止でバッチが数日走らなかっただけで、再開時に
     * 「期限まで 30 日です」という通知が<b>失効済みの資格に対して</b>送られる
     * （Codex 検分の指摘。機能フラグとは無関係に存在する潜在バグであった）。
     * 下限を入れることで、通知は「まだ失効していないもの」に限られる。</p>
     *
     * @param today            基準日（この日より前に失効済みのものは対象外）
     * @param threshold        期限日の閾値（この日付以前に失効する資格を対象とする）
     * @param notificationType 通知種別文字列（例: "DAYS_30", "DAYS_7"）
     * @return 対象のMemberSkillEntityリスト
     */
    public List<MemberSkillEntity> findExpiringSoon(
            LocalDate today, LocalDate threshold, String notificationType) {
        String sql = """
                SELECT ms.id, ms.skill_category_id, ms.user_id, ms.scope_type, ms.scope_id,
                       ms.name, ms.issuer, ms.credential_number, ms.acquired_on, ms.expires_at,
                       ms.status, ms.certificate_s3_key, ms.verified_at, ms.verified_by,
                       ms.version, ms.created_at, ms.updated_at, ms.deleted_at
                FROM member_skills ms
                WHERE ms.expires_at IS NOT NULL
                  AND ms.expires_at <= ?
                  AND ms.expires_at >= ?
                  AND ms.status = 'ACTIVE'
                  AND ms.deleted_at IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM skill_expiry_notifications sen
                      WHERE sen.member_skill_id = ms.id
                        AND sen.notification_type = ?
                  )
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> MemberSkillEntity.builder()
                .skillCategoryId(rs.getObject("skill_category_id", Long.class))
                .userId(rs.getLong("user_id"))
                .scopeType(rs.getString("scope_type"))
                .scopeId(rs.getLong("scope_id"))
                .name(rs.getString("name"))
                .issuer(rs.getString("issuer"))
                .credentialNumber(rs.getString("credential_number"))
                .acquiredOn(rs.getObject("acquired_on", LocalDate.class))
                .expiresAt(rs.getObject("expires_at", LocalDate.class))
                .certificateS3Key(rs.getString("certificate_s3_key"))
                .build(),
                threshold, today, notificationType);
    }
}
