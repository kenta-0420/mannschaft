package com.mannschaft.app.ranch.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 退会強消去のRanch所有行だけを、外domainに触れず一つのPRIMARY TXで消す。 */
@Service
@RequiredArgsConstructor
public class RanchPurgeService {
    private final JdbcTemplate jdbc;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public void purgeUser(Long userId) {
        Objects.requireNonNull(userId);
        if (userId <= 0) throw new IllegalArgumentException("本人IDが不正です");

        // owner参照FKの子から削除。共有catalog/policy/controlは個人行ではないため保持する。
        jdbc.update("DELETE FROM ranch_point_ledger WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_reward_decisions WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_week_budgets WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_affinity_units WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_care_week_budgets WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_room_placements WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_collectible_inventory WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_commands WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_participation_periods WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_dinosaurs WHERE user_id = ?", userId);
        jdbc.update("DELETE FROM ranch_owners WHERE user_id = ?", userId);
    }
}
