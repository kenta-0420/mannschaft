package com.mannschaft.app.reflection.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.reflection.ReflectionErrorCode;
import com.mannschaft.app.reflection.entity.ReflectionEntryEntity;
import com.mannschaft.app.reflection.entity.ReflectionThemeEntity;
import com.mannschaft.app.reflection.repository.ReflectionEntryRepository;
import com.mannschaft.app.reflection.repository.ReflectionThemeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * F06.5 振り返り（テーマ・エントリ）の認可ゲート。認可根治戦役 第1波・個人領域。
 *
 * <h2>保証する内容</h2>
 * <p>振り返りのテーマ・エントリは<b>作成者本人のみ</b>が参照・更新・削除できる。
 * 認可に用いるスコープはリクエストのパラメータではなく <b>対象実体の {@code user_id}</b> 由来で確定する
 * （{@code findByIdAndUserId} で id と所有者を同時に条件化するため、他者所有の ID は行が引けない）。</p>
 *
 * <p>他者所有・不存在・論理削除済みはいずれも {@link ReflectionErrorCode#REFLECTION_NOT_FOUND}
 * （REFLECTION_001 / 404）に正規化して<b>存在を秘匿</b>する（403 を返すと当該 ID の振り返りが
 * 実在することを漏らすため）。</p>
 *
 * <h2>本クラスに集約した理由</h2>
 * <p>同一の所有者判定が {@link ReflectionThemeService} / {@link ReflectionEntryService} /
 * {@link RecallService} / {@link ReflectionArchiveService} の private ヘルパとして 4 重に実装されていた。
 * 認可判定の所在を 1 箇所に集約し、いずれかの経路だけ判定が緩む事故を構造的に防ぐ。</p>
 */
@Component
@RequiredArgsConstructor
public class ReflectionAccessGuard {

    private final ReflectionThemeRepository reflectionThemeRepository;
    private final ReflectionEntryRepository reflectionEntryRepository;

    /**
     * 本人所有のテーマを取得する。
     *
     * <p>{@code @SQLRestriction("deleted_at IS NULL")} により論理削除済みは引けない。
     * アーカイブ済み（{@code archived_at IS NOT NULL}）は所有者本人なら取得できる
     * （復元操作の対象とするため。アーカイブ状態の判定は呼び出し側の業務ルール）。</p>
     *
     * @param userId  操作ユーザー ID（認証主体）
     * @param themeId 対象テーマ ID
     * @return 本人所有のテーマ実体
     * @throws BusinessException 他者所有・不存在・論理削除済み（REFLECTION_001 / 404）
     */
    public ReflectionThemeEntity requireOwnedTheme(Long userId, UUID themeId) {
        return reflectionThemeRepository.findByIdAndUserId(themeId, userId)
                .orElseThrow(() -> new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND));
    }

    /**
     * 本人所有のエントリを取得する。
     *
     * @param userId  操作ユーザー ID（認証主体）
     * @param entryId 対象エントリ ID
     * @return 本人所有のエントリ実体
     * @throws BusinessException 他者所有・不存在・論理削除済み（REFLECTION_001 / 404）
     */
    public ReflectionEntryEntity requireOwnedEntry(Long userId, UUID entryId) {
        return reflectionEntryRepository.findByIdAndUserId(entryId, userId)
                .orElseThrow(() -> new BusinessException(ReflectionErrorCode.REFLECTION_NOT_FOUND));
    }
}
