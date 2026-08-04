package com.mannschaft.app.favorite.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.common.visibility.ContentVisibilityChecker;
import com.mannschaft.app.common.visibility.ReferenceType;
import com.mannschaft.app.favorite.FavoriteEntityType;
import com.mannschaft.app.favorite.FavoriteErrorCode;
import com.mannschaft.app.favorite.entity.UserFavoriteEntity;
import com.mannschaft.app.favorite.repository.UserFavoriteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * F02.9 お気に入りの認可ゲート。認可根治戦役 第1波・個人領域。
 *
 * <h2>保証する内容</h2>
 * <ul>
 *   <li><b>お気に入り行そのもの</b>: 参照・削除できるのは<b>登録した本人</b>のみ。認可スコープは
 *       リクエストではなくお気に入り実体の {@code user_id} 由来で確定する。不存在は
 *       {@link FavoriteErrorCode#FAV_003}（404）、他者所有は {@link FavoriteErrorCode#FAV_004}（403）。</li>
 *   <li><b>登録対象エンティティ</b>: チーム／組織は F00 共通可視性ラダー
 *       （{@link ContentVisibilityChecker}）で<b>閲覧できる対象のみ</b>登録を許す。閲覧できない対象は
 *       {@link FavoriteErrorCode#FAV_003}（404）で存在を秘匿する。これにより、お気に入り登録の応答で
 *       チーム名・アイコンといった非公開スコープの属性が返る経路を塞ぐ。</li>
 * </ul>
 *
 * <p>登録対象の判定を<b>業務検証（件数上限・重複）より前</b>に置く。上限や重複の応答差から
 * 対象の実在が推測されるのを防ぐためである。</p>
 *
 * <p>一覧・詳細の表示メタ解決経路（{@code *FavoriteResolver}）も同じ F00 ラダーで判定し、
 * 閲覧できなくなった対象は {@code available=false}（UNAVAILABLE）として名称・アイコンを返さない。
 * 登録時点では閲覧できた対象が、後に非公開化・アーカイブ・退会で閲覧不可になる場合があるため、
 * 入口（本ゲート）と表示（Resolver）の両方で同一ラダーを評価する。</p>
 */
@Component
@RequiredArgsConstructor
public class FavoriteAccessGuard {

    private final UserFavoriteRepository userFavoriteRepository;
    private final ContentVisibilityChecker contentVisibilityChecker;

    /**
     * 本人所有のお気に入り行を取得する。
     *
     * @param userId     操作ユーザー ID（認証主体）
     * @param favoriteId 対象お気に入り ID
     * @return 本人所有のお気に入り実体
     * @throws BusinessException 不存在（FAV_003 / 404）・他者所有（FAV_004 / 403）
     */
    public UserFavoriteEntity requireOwnedFavorite(Long userId, UUID favoriteId) {
        UserFavoriteEntity entity = userFavoriteRepository.findById(favoriteId)
                .orElseThrow(() -> new BusinessException(FavoriteErrorCode.FAV_003));
        if (!entity.getUserId().equals(userId)) {
            throw new BusinessException(FavoriteErrorCode.FAV_004);
        }
        return entity;
    }

    /**
     * 登録対象が本人に閲覧可能であることを要求する（F00 共通可視性ラダー）。
     *
     * <p>ラダー評価の対象は F00 に resolver がある {@code TEAM} / {@code ORGANIZATION}。
     * {@code KB_PAGE} はページの {@code access_level} × スコープロールで判定するため
     * 専用 Resolver 側に委ね、{@code BLOG_AUTHOR} / {@code VILLAGE} は本ゲートの対象外とする
     * （いずれも該当 Resolver が対象の可用性を判定する）。</p>
     *
     * <p>{@code entityId} が対象種別の ID 形式として解釈できない場合は判定せず戻る。
     * どのエンティティも特定できないため漏洩の余地が無く、形式不正は呼び出し側の
     * 入力検証（{@code FAV_006}）が扱う。</p>
     *
     * @param userId     操作ユーザー ID（認証主体）
     * @param entityType 対象エンティティ種別
     * @param entityId   対象エンティティ ID（文字列）
     * @throws BusinessException 閲覧できない対象（FAV_003 / 404・存在秘匿）
     */
    public void requireViewableTarget(Long userId, FavoriteEntityType entityType, String entityId) {
        ReferenceType referenceType = referenceTypeOf(entityType);
        if (referenceType == null || entityId == null) {
            return;
        }
        long numericId;
        try {
            numericId = Long.parseLong(entityId);
        } catch (NumberFormatException e) {
            return;
        }
        if (!contentVisibilityChecker.canView(referenceType, numericId, userId)) {
            throw new BusinessException(FavoriteErrorCode.FAV_003);
        }
    }

    /**
     * お気に入り種別を F00 可視性ラダーの {@link ReferenceType} に写す。
     *
     * @return 対応する {@link ReferenceType}。ラダー評価の対象外の種別は {@code null}
     */
    private ReferenceType referenceTypeOf(FavoriteEntityType entityType) {
        return switch (entityType) {
            case TEAM -> ReferenceType.TEAM;
            case ORGANIZATION -> ReferenceType.ORGANIZATION;
            case KB_PAGE, BLOG_AUTHOR, VILLAGE -> null;
        };
    }
}
