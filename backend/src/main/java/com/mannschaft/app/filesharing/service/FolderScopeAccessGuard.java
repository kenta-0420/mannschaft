package com.mannschaft.app.filesharing.service;

/**
 * F08.7.1 / 04 — 大会・ディビジョンスコープのファイル置き場に対する横断認可ゲート（ポート）。
 *
 * <p>F05.5 ファイル共有（{@code /api/v1/files**}）は本来 folderId / fileId だけで CRUD を行い、
 * フォルダのスコープ（TEAM / ORGANIZATION / PERSONAL / TOURNAMENT / TOURNAMENT_DIVISION）に応じた
 * 認可を <b>一切持っていなかった</b>。F08.7.1 で大会スコープのフォルダが同じ {@code shared_folders} /
 * {@code shared_files} に相乗りしたため、<b>非公開大会のフォルダ ID / ファイル ID を渡すだけで
 * 任意のログインユーザーがファイル一覧・メタを取得できる情報漏洩</b>が生じていた（PR #1235 検分指摘）。</p>
 *
 * <p>本インターフェースは filesharing ドメインが宣言する <b>認可ポート</b>であり、実装は
 * {@code com.mannschaft.app.tournament} ドメイン（連絡スペースの canView/canPost を流用）が担う
 * （依存性逆転。filesharing → tournament の循環依存を避ける）。filesharing の各 Service は
 * 読み取り経路で {@link #checkFolderViewByFolderId} / {@link #checkFolderViewByFileId} を、
 * 書き込み経路で {@link #checkFolderPostByFolderId} / {@link #checkFolderPostByFileId} を
 * <b>必ず</b>通す。</p>
 *
 * <p>大会／ディビジョンスコープの場合は、フォルダが属する大会／ディビジョンの連絡スペース
 * （BULLETIN）の閲覧／投稿認可を流用する。非公開かつ非メンバー／未ログインは 403、
 * フォルダ・大会・ディビジョンが存在しない場合は 404（IDOR 対策・存在を漏らさない）。</p>
 *
 * <h2>本ガードの適用範囲（認可根治 Wave7 で更新）</h2>
 * <p>旧 Javadoc はここに「対象フォルダのスコープが大会／ディビジョン<b>以外</b>
 * （TEAM / ORGANIZATION / PERSONAL）の場合、本ガードは何もしない（既存挙動を変えない。
 * <b>F05.5 全体のフラット認可是正は別 Issue</b>）」と記していた。<b>この先送りは Wave7 で解消済み</b>
 * であり、記述をそのまま残すと「TEAM/ORG は今も無認可」という誤読を招くため改めた。</p>
 *
 * <p>現在、TEAM / ORGANIZATION / PERSONAL スコープの認可は、本ガードではなく
 * {@link SharedFolderQueryService}（{@code authorizeView} / {@code authorizeDelete}）および
 * {@link SharedFolderService}（{@code checkFolderViewAccess} / {@code checkFolderManageAccess}）が
 * {@code AccessControlService} を用いて<b>実効的に</b>行う。</p>
 *
 * <p>したがって<b>本ガードは大会／ディビジョン専用</b>である。実装は依然として
 * 「大会以外のスコープでは何もしない」防御的な no-op を保持してよいが、呼び出し側は
 * 本ガードを<b>大会／ディビジョンスコープの分岐からのみ</b>呼ぶこと（TEAM/ORG/PERSONAL の認可は
 * 上記のとおり {@link SharedFolderQueryService} / {@link SharedFolderService} 側で担保する）。</p>
 *
 * <p><b>ガードを廃止しない理由:</b> 本インターフェースは filesharing → tournament の循環依存を
 * 避けるための依存性逆転ポート（DIP）であり、大会／ディビジョンの連絡スペース認可を
 * filesharing 側から呼ぶ唯一の手段である。廃止すると filesharing が tournament を直接参照することになり
 * ドメイン境界原則に反するため、<b>大会／ディビジョン専用ポートとして残す</b>。</p>
 *
 * <p>設計書: docs/features/F08.7.1_tournament_extensions/04_file_storage.md §3 / §5</p>
 */
public interface FolderScopeAccessGuard {

    /**
     * folderId のフォルダが大会／ディビジョンスコープなら閲覧認可を通す。
     * 大会以外のスコープは何もしない。
     *
     * @param folderId フォルダ ID
     * @param userId   閲覧ユーザー ID（未ログインは null。公開スペースのみ閲覧可）
     */
    void checkFolderViewByFolderId(Long folderId, Long userId);

    /**
     * folderId のフォルダが大会／ディビジョンスコープならアップロード／編集認可を通す。
     * 大会以外のスコープは何もしない。
     *
     * @param folderId フォルダ ID
     * @param userId   操作ユーザー ID
     */
    void checkFolderPostByFolderId(Long folderId, Long userId);

    /**
     * fileId のファイルが属するフォルダが大会／ディビジョンスコープなら閲覧認可を通す。
     * 大会以外のスコープは何もしない。
     *
     * @param fileId ファイル ID
     * @param userId 閲覧ユーザー ID（未ログインは null）
     */
    void checkFolderViewByFileId(Long fileId, Long userId);

    /**
     * fileId のファイルが属するフォルダが大会／ディビジョンスコープならアップロード／編集認可を通す。
     * 大会以外のスコープは何もしない。
     *
     * @param fileId ファイル ID
     * @param userId 操作ユーザー ID
     */
    void checkFolderPostByFileId(Long fileId, Long userId);
}
