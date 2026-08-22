package com.mannschaft.app.village.service;

import com.mannschaft.app.common.AccessControlService;
import com.mannschaft.app.common.BusinessException;
import com.mannschaft.app.village.VillageErrorCode;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import com.mannschaft.app.village.repository.VillageMembershipRepository;
import com.mannschaft.app.village.repository.VillageRepository;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * 村ドメインの<b>共通アクセスゲート</b>。村の存在確認と可視性判定を一元化する。
 *
 * <h2>なぜ必要か（存在オラクルの根治）</h2>
 * <p>村ドメインには「<b>非公開(UNLISTED)村の存在を秘匿する</b>」契約がある（UNLISTED 村は検索から
 * 意図的に除外される）。ところが従来は各サービスが {@code private VillageEntity loadActiveVillage(UUID)}
 * を各自複製しており、いずれも {@code deletedAt}/{@code archivedAt} しか見ず {@code visibility} を
 * 判定していなかった。その結果、寄合・祭・掲示板などで非村人が任意の村 ID を叩くと
 * 「不在なら 404 ／ UNLISTED 村として実在すれば 403」と応答が割れ、
 * <b>応答の違いそのものが「その村は存在する」という情報を漏らしていた</b>（＝存在オラクル）。
 * 本ゲートはその判定を 1 箇所に集約し、各サービスが判定を書き忘れる余地を無くす。</p>
 *
 * <h2>なぜ専用コードではなく {@link VillageErrorCode#VILLAGE_NOT_FOUND} を投げるのか</h2>
 * <p>秘匿のために「非公開村です」といった専用コードを新設すると、それ自体が
 * 「この ID の村は実在する」という答えになってしまい、秘匿の意味が消える。
 * また 403 を返す実装も同じ理由で不可である。<b>不在側のコードそのもの</b>を投げることで、
 * HTTP ステータス（404）だけでなく<b>応答本文の {@code error.code} まで</b>架空 ID への応答と
 * 完全に一致し、攻撃者は村の存在有無を一切区別できない。
 * 既に本番稼働している {@link VillageCharterAccessService#loadReadableVillageOrHide} と同じ作法である。</p>
 *
 * <h2>依存を 3 つに限る理由（循環依存の回避）</h2>
 * <p>依存は {@link VillageRepository} / {@link VillageMembershipRepository} /
 * {@link AccessControlService} の 3 つだけに固定する。特に他の村サービス
 * （とりわけ {@code PostingIdentityService}）に依存してはならない。
 * {@code VillageBulletinAccessService} が {@code PostingIdentityService} を使い、その
 * {@code PostingIdentityService} 自身も将来この ゲートの利用者になるため、依存すると
 * <b>循環依存が構造的に発生する</b>。本クラスはリポジトリだけに依存する葉ノードであること。</p>
 *
 * <h2>性能</h2>
 * <p>可視性はロード済みエンティティのフィールドで判定するため、<b>PUBLIC 村では追加クエリ 0 件</b>。
 * UNLISTED 村でのみ最大 2 件（メンバーシップ 1・SYSTEM_ADMIN 1）を撃ち、
 * 現役メンバーに該当した時点で SYSTEM_ADMIN 問い合わせは行わず短絡する。</p>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VillageAccessGate {

    private final VillageRepository villageRepository;
    private final VillageMembershipRepository membershipRepository;
    private final AccessControlService accessControlService;

    /**
     * write / member-scoped 操作用に、稼働中の村をロードする。
     *
     * <p>判定順序は以下に固定する。<b>この順序自体が秘匿契約の一部であり、入れ替えてはならない。</b></p>
     * <ol>
     *   <li>{@code villageId == null} → {@link VillageErrorCode#VILLAGE_NOT_FOUND}</li>
     *   <li>不存在、または論理削除済み（{@code deletedAt != null}） → {@code VILLAGE_NOT_FOUND}</li>
     *   <li><b>可視性ゲート</b>: UNLISTED かつ現役 USER メンバーでなく SYSTEM_ADMIN でもない
     *       → {@code VILLAGE_NOT_FOUND}（存在ごと秘匿）</li>
     *   <li>凍結済み（{@code archivedAt != null}） → {@link VillageErrorCode#VILLAGE_ALREADY_ARCHIVED}（409）</li>
     *   <li>村を返す</li>
     * </ol>
     *
     * <h3>なぜ可視性判定(3)が凍結判定(4)より前なのか</h3>
     * <p>{@code VILLAGE_ALREADY_ARCHIVED} は 409 に写像されている。順序を逆にすると
     * 「凍結済みの非公開村」を叩いた非村人が 409 を受け取り、<b>不在の 404 と区別がついてしまう</b>
     * （別経路の存在オラクル）。可視性を先に判定すれば、非公開村は凍結の有無に関わらず
     * 非村人には一律 404 となり、かつ<b>公開村の凍結は従来どおり 409</b> のまま保てる。
     * 後任が「削除・凍結をまとめて先に見た方が素直だ」と善意で入れ替えないよう、ここに理由を残す。</p>
     *
     * <p>なお PUBLIC 村は検索で誰でも見つけられる＝存在が秘密ではないため、
     * <b>絶対に 404 へ倒さない</b>。PUBLIC 村はゲートを素通りし、
     * 非村人かどうかの判定は従来どおり呼び出し元の 403 に委ねる。</p>
     *
     * @param villageId   村 ID（null 可。null は不在として扱い NPE を投げない）
     * @param actorUserId 操作者ユーザー ID（null 可。null は非メンバーとして扱う）
     * @return 稼働中かつ操作者に可視な村
     * @throws BusinessException {@link VillageErrorCode#VILLAGE_NOT_FOUND}（不在・削除済み・非可視）または
     *                           {@link VillageErrorCode#VILLAGE_ALREADY_ARCHIVED}（凍結済み・可視）
     */
    public VillageEntity loadActiveVillage(UUID villageId, @Nullable Long actorUserId) {
        VillageEntity village = loadVisibleVillage(villageId, actorUserId);

        // 4. 凍結判定は可視性ゲートを通過した後にのみ行う（上記 Javadoc の理由を参照）。
        if (village.getArchivedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_ALREADY_ARCHIVED);
        }
        return village;
    }

    /**
     * read 公開用に、閲覧可能な村をロードする。
     *
     * <p>{@link #loadActiveVillage} との違いは凍結済みの扱いのみで、read では
     * 凍結済み村も {@link VillageErrorCode#VILLAGE_NOT_FOUND} に畳む
     * （憲章 read・掲示板 read の既存実体 {@code findByIdAndDeletedAtIsNullAndArchivedAtIsNull} と揃える）。
     * 可視性判定が凍結判定より前に来る点は同じで、非公開村の非村人はここでも 404 である。</p>
     *
     * @param villageId   村 ID（null 可）
     * @param actorUserId 閲覧者ユーザー ID（null 可。null は非メンバーとして扱う）
     * @return 閲覧可能な村
     * @throws BusinessException 不在・削除済み・凍結済み・非可視はすべて
     *                           {@link VillageErrorCode#VILLAGE_NOT_FOUND}
     */
    public VillageEntity loadReadableVillage(UUID villageId, @Nullable Long actorUserId) {
        VillageEntity village = loadVisibleVillage(villageId, actorUserId);

        if (village.getArchivedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        return village;
    }

    /**
     * 村が操作者に可視かを判定する（判定結果だけが必要な呼び出し向け）。
     *
     * <p>PUBLIC 村は常に可視で、このとき<b>追加クエリを一切撃たない</b>。
     * UNLISTED 村は現役 USER メンバーまたは SYSTEM_ADMIN にのみ可視。</p>
     *
     * <p>{@code visibility} に将来値が増えた場合に<b>既定が秘匿側へ倒れる</b>よう、
     * PUBLIC を許可リストとして扱い、それ以外はすべて秘匿側（メンバー判定が必要）として扱う。
     * 新しい可視性を「公開扱い」にしたい場合は、ここに明示的に足すこと。</p>
     *
     * @param village     村（null 可。null は不可視）
     * @param actorUserId 操作者ユーザー ID（null 可。null は非メンバー扱い）
     * @return 可視なら true
     */
    public boolean isVisibleTo(@Nullable VillageEntity village, @Nullable Long actorUserId) {
        if (village == null) {
            return false;
        }
        // 許可リスト方式: PUBLIC のみ無条件可視。未知の新しい可視性は秘匿側に倒れる。
        if (village.getVisibility() == VillageVisibility.PUBLIC) {
            return true;
        }
        if (actorUserId == null) {
            return false;
        }
        // 現役メンバー（leftAt / bannedAt 除外）。認可用の findActiveByVillageIdAndSubject を使うこと。
        if (membershipRepository
                .findActiveByVillageIdAndSubject(village.getId(), VillageSubjectType.USER, actorUserId)
                .isPresent()) {
            // メンバーに該当した時点で SYSTEM_ADMIN 問い合わせは行わない（追加 1 クエリの節約）。
            return true;
        }
        return accessControlService.isSystemAdmin(actorUserId);
    }

    /**
     * 凍結の有無を問わず、操作者に可視な稼働中（未削除）の村をロードする。
     *
     * <p>{@link #loadActiveVillage} / {@link #loadReadableVillage} との違いは
     * <b>凍結済み（{@code archivedAt != null}）の村をそのまま返す</b>点だけである。
     * 募集カテゴリ（設計書 §6.4）やニュースレター設定のように
     * 「<b>凍結村でも閲覧・購読操作は許す</b>／書き込みだけを別途 {@code VILLAGE_ALREADY_ARCHIVED} で弾く」
     * 設計の呼び出し元がある。そこへ機械的に {@code loadActiveVillage}（凍結=409）や
     * {@code loadReadableVillage}（凍結=404）を当てると、
     * <b>凍結村の一覧が読めなくなるという別の退行</b>を作ってしまう。
     * 凍結の可否判定は呼び出し元の責務として残しつつ、
     * <b>存在確認と可視性判定だけ</b>をゲートへ寄せるための入口である。</p>
     *
     * <p>不在・削除済み・非可視はすべて {@link VillageErrorCode#VILLAGE_NOT_FOUND} で、
     * 判定順序も {@code loadActiveVillage} と同一（可視性が先）。</p>
     *
     * @param villageId   村 ID（null 可）
     * @param actorUserId 操作者ユーザー ID（null 可。null は非メンバー扱い）
     * @return 操作者に可視な村（凍結済みを含む）
     */
    public VillageEntity loadVillageAllowingArchived(UUID villageId, @Nullable Long actorUserId) {
        return loadVisibleVillage(villageId, actorUserId);
    }

    /**
     * 例外を投げずに、操作者に可視な稼働中（未削除）の村を探す。
     *
     * <p>ピン一覧のように<b>複数の村をまとめて hydrate し、見えないものは黙って落とす</b>
     * 経路のための入口。例外を投げる入口しか無いと、そうした呼び出し元は
     * 「ゲートを使うと例外制御になって書けない」という理由でリポジトリを直接引き始め、
     * 可視性判定が再び抜け落ちる。</p>
     *
     * @param villageId   村 ID（null 可）
     * @param actorUserId 操作者ユーザー ID（null 可。null は非メンバー扱い）
     * @return 不在・削除済み・非可視なら {@link Optional#empty()}、それ以外は村（凍結済みを含む）
     */
    public Optional<VillageEntity> findVisibleVillage(UUID villageId, @Nullable Long actorUserId) {
        if (villageId == null) {
            return Optional.empty();
        }
        return villageRepository.findById(villageId)
                .filter(v -> v.getDeletedAt() == null)
                .filter(v -> isVisibleTo(v, actorUserId));
    }

    /**
     * 判定順序 1〜3（null ID・不在/削除済み・可視性ゲート）を実行し、可視な村を返す共通部。
     * 凍結（4）の扱いだけが呼び出し元で分かれる。
     */
    private VillageEntity loadVisibleVillage(UUID villageId, @Nullable Long actorUserId) {
        // 1. null ID は不在と同じ扱い（NPE を投げず、応答も不在と同一にする）。
        if (villageId == null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        // 2. 不在・論理削除済みは 404。
        VillageEntity village = villageRepository.findById(villageId)
                .orElseThrow(() -> new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND));
        if (village.getDeletedAt() != null) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }

        // 3. 可視性ゲート。非可視は「不在側のコードそのもの」を投げ、架空 ID への応答と完全に一致させる。
        if (!isVisibleTo(village, actorUserId)) {
            throw new BusinessException(VillageErrorCode.VILLAGE_NOT_FOUND);
        }
        return village;
    }
}
