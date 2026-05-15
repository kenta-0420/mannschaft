package com.mannschaft.app.favorite.event;

import com.mannschaft.app.auth.event.UserAnonymizedEvent;
import com.mannschaft.app.favorite.repository.UserFavoriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ユーザー退会（匿名化）イベントを受け取り、お気に入りデータを削除するリスナー。
 *
 * <p>退会ユーザーの個人データ消去（GDPR対応）の一環として、
 * UserAnonymizedEventの発行をトリガーにuser_favoritesを物理削除する。
 * クロスドメインFK非依存のイベント駆動設計（CLAUDE.md 原則5）。</p>
 */
@Component
@RequiredArgsConstructor
public class FavoriteAnonymizationEventListener {

    private final UserFavoriteRepository userFavoriteRepository;

    /**
     * ユーザー退会（匿名化）完了時にお気に入りを削除する。
     *
     * @param event 匿名化完了イベント
     */
    @Transactional
    @EventListener
    public void handleUserAnonymized(UserAnonymizedEvent event) {
        userFavoriteRepository.deleteAllByUserId(event.getUserId());
    }
}
