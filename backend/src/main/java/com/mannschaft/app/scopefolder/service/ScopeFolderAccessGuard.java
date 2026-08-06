package com.mannschaft.app.scopefolder.service;

import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.scopefolder.ScopeFolderErrorCode;
import com.mannschaft.app.scopefolder.entity.MyScopeFolderEntity;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * マイスコープフォルダの所有者判定を一元化するガード。
 *
 * <p>フォルダは<b>作成した利用者本人の持ち物</b>であり、更新・削除・アイテムの追加削除・
 * 一括振り分けはいずれも本人のみが行える。本クラスは「取得したフォルダが操作者本人のもので
 * あること」の判定を 1 箇所に集約する。</p>
 *
 * <p>フォルダの取得自体は {@code user_id} を結合条件に含むクエリ
 * （{@code MyScopeFolderRepository#findByIdAndUserIdAndDeletedAtIsNull}）で行い、
 * 本ガードは取得結果と操作者を突き合わせて二重に固定する。パス変数のフォルダ ID は
 * 「どの行を引くか」を決めるだけで、判定の根拠にはしない。</p>
 *
 * <p>存在しないフォルダと他者所有のフォルダは<b>同一のエラー</b>
 * （{@link ScopeFolderErrorCode#SCOPE_FOLDER_NOT_FOUND}）へ畳む。撃ち分けると
 * フォルダ ID の実在有無が応答から読み取れるためである。</p>
 *
 * <p>本クラスは状態を持たない（依存注入なし）。判定に必要な材料は引数で受け取る。</p>
 */
@Service
public class ScopeFolderAccessGuard {

    /**
     * 取得したフォルダが<b>操作者本人の所有物である</b>ことを保証し、そのフォルダを返す。
     *
     * @param folder 取得済みフォルダ（未取得・不存在の場合は {@code null}）
     * @param userId 操作者ユーザー ID
     * @return 本人所有と確認できたフォルダ
     * @throws BusinessException フォルダが取得できない／本人の所有物でない場合
     *                           （{@link ScopeFolderErrorCode#SCOPE_FOLDER_NOT_FOUND}）
     */
    public MyScopeFolderEntity requireOwnedFolder(MyScopeFolderEntity folder, Long userId) {
        if (folder == null
                || userId == null
                || !Objects.equals(folder.getUserId(), userId)) {
            throw new BusinessException(ScopeFolderErrorCode.SCOPE_FOLDER_NOT_FOUND);
        }
        return folder;
    }
}
