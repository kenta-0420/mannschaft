package com.mannschaft.app.gamification.service;

import com.mannschaft.app.gamification.dto.LegacyBadgeAward;
import com.mannschaft.app.gamification.entity.BadgeEntity;
import com.mannschaft.app.gamification.repository.BadgeRepository;
import com.mannschaft.app.gamification.repository.UserBadgeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 旧バッジの取得証拠を所有ドメイン内で読む内部SPI。
 * 本人のACTIVE確認は呼び出し元のauth guardが行う。
 * readOnly=falseでPRIMARYを選び、Entityを外へ渡さない。
 */
@Service
@RequiredArgsConstructor
public class GamificationRanchBadgeQueryService {
    /** 100件と次ページ判定用1件。呼び出し元は最後の処理済みIDをcursorにする。 */
    public static final int PAGE_SIZE = 100;

    private final UserBadgeRepository awards;
    private final BadgeRepository badges;

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = false)
    public List<LegacyBadgeAward> page(Long userId, Long afterId) {
        if (userId == null || userId <= 0 || afterId == null || afterId < 0) {
            throw new IllegalArgumentException("バッジ取得ページの本人またはcursorが不正です");
        }
        var rows = awards.findByUserIdAndIdGreaterThanOrderByIdAsc(
                userId, afterId, PageRequest.of(0, PAGE_SIZE + 1));
        if (rows.isEmpty()) {
            return List.of();
        }
        Set<Long> ids = rows.stream().map(row -> row.getBadgeId()).collect(Collectors.toSet());
        Set<Long> available = badges.findAllById(ids).stream()
                .filter(badge -> Boolean.TRUE.equals(badge.getIsActive()))
                .map(BadgeEntity::getId).collect(Collectors.toSet());
        return rows.stream().map(row -> new LegacyBadgeAward(row.getId(),
                Long.toString(row.getBadgeId()), row.getPeriodLabel(), row.getEarnedOn(),
                available.contains(row.getBadgeId()))).toList();
    }
}
