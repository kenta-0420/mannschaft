package com.mannschaft.app.support.perf;

import com.mannschaft.app.auth.entity.UserInterestTagEntity;
import com.mannschaft.app.auth.repository.UserInterestTagRepository;
import com.mannschaft.app.common.EncryptionService;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CMP-XXX 広告オーディエンス解決（{@code AdAudienceResolver}）縮小版負荷試験用の合成データ投入ヘルパー。
 *
 * <p>{@link Fanout500kSeeder} を金型に、JDBC {@code PreparedStatement.addBatch()} + {@code executeBatch()}
 * で {@code users} を大量投入する。{@link Fanout500kSeeder} との決定的な違いは、広告セグメント評価器が
 * 実際に参照するハッシュ列（HMAC-SHA256 ブラインドインデックス）を埋める点である。</p>
 *
 * <h2>参照列の確定結果</h2>
 * <ul>
 *   <li>{@code PrefectureSegmentEvaluator} → {@code users.prefecture_code_hash}</li>
 *   <li>{@code GenderSegmentEvaluator} → {@code users.gender_hash}</li>
 *   <li>{@code InterestTagSegmentEvaluator} → {@code user_interest_tags.tag_hash}（別テーブル）</li>
 *   <li>{@code LocaleSegmentEvaluator} → {@code users.locale}（平文・暗号化なし）</li>
 *   <li>{@code AgeRangeSegmentEvaluator} → {@code users.birth_year}（平文 SMALLINT、V68.004）</li>
 *   <li>{@code CitySegmentEvaluator} → {@code users.city_code_hash}</li>
 *   <li>{@code OrgTypeSegmentEvaluator} → {@code teams.template} JOIN {@code user_roles}（users 側に列なし）</li>
 *   <li>{@code DeviceSegmentEvaluator} → {@code push_subscriptions.user_agent}（UA パース要・本シーダー対象外）</li>
 * </ul>
 *
 * <p>本シーダーは実測に使う 2 セグメント（REGION_PREFECTURE・GENDER・INTEREST_TAG の組み合わせ）に必要な
 * 列のみを埋める。ハッシュは {@link EncryptionService#hmac(String)} をそのまま呼び出す
 * （自前でハッシュ計算を再実装しない——鍵やアルゴリズムがずれると静かに0件になるため）。</p>
 *
 * <h2>分布設計（積集合・差集合が実際に効くように分散）</h2>
 * <ul>
 *   <li>都道府県: {@link #PREFECTURE_CODES}（5種）に均等分散（{@code i % 5}）</li>
 *   <li>性別: {@code MALE/FEMALE/OTHER} の3種に均等分散（{@code i % 3}）</li>
 *   <li>興味タグ: 4人に1人（{@code i % 4 == 0}）に {@link #INTEREST_TAG} を付与</li>
 * </ul>
 * これにより「都道府県2件 INCLUDE（OR）」「性別1件 INCLUDE（AND）」「興味タグ1件 EXCLUDE」が
 * 全て非自明な部分集合になり、積集合・差集合の実処理経路を実際に通す。
 *
 * <h2>ID 帯の隔離</h2>
 * <p>{@link Fanout500kSeeder}（700,000,000 台）と衝突しない高位レンジ（760,000,000 台）を使う。</p>
 */
public final class AdAudienceSeeder {

    /** users.id の開始値（Fanout500kSeeder の帯と衝突しない高位レンジ）。 */
    public static final long USER_ID_BASE = 760_000_000L;

    /** JIS X 0401 都道府県コード5種（分布用）。先頭2件を REGION_PREFECTURE INCLUDE の測定対象にする。 */
    public static final List<String> PREFECTURE_CODES = List.of("13", "14", "27", "01", "23");

    /** 性別3種（分布用）。GENDER INCLUDE の測定対象は先頭1件。 */
    public static final List<String> GENDERS = List.of("MALE", "FEMALE", "OTHER");

    /** 4人に1人へ付与する興味タグ。INTEREST_TAG EXCLUDE の測定対象。 */
    public static final String INTEREST_TAG = "sports_football";

    /** 呼び出しごとに単調増加する user_id 帯（同一 JVM 内の複数回 seed() 呼び出しの衝突回避）。 */
    private static final AtomicLong NEXT_USER_ID_BASE = new AtomicLong(USER_ID_BASE);

    /** 帯と帯の間に空けるマージン。 */
    private static final long BAND_MARGIN = 1_000L;

    /** 1 バッチあたりの JDBC addBatch 件数。 */
    private static final int BATCH_SIZE = 2_000;

    private final JdbcTemplate jdbc;
    private final EncryptionService encryptionService;
    private final UserInterestTagRepository userInterestTagRepository;

    /**
     * @param jdbc                      users への JDBC バッチ INSERT 用
     * @param encryptionService         HMAC ブラインドインデックス生成用
     * @param userInterestTagRepository user_interest_tags への投入用（JPA 経由）。
     *                                  {@code user_interest_tags.id} は {@link com.mannschaft.app.common.entity.UuidV7Entity}
     *                                  由来で、test プロファイルの Entity 由来スキーマ（{@code ddl-auto=create}）では
     *                                  {@code BINARY(16)} 列になる。生 JDBC で 36 文字の UUID 文字列を直接バインドすると
     *                                  {@code Data too long for column 'id'} で列サイズを超過するため
     *                                  （Hibernate の UUID⇔BINARY(16) 変換を自前で再実装するのは危険——ずれると
     *                                  検索に使えない壊れた ID が黙って入る——ため）、ここは JPA 経由で Hibernate に
     *                                  ID 生成・エンコードを委ねる。
     */
    public AdAudienceSeeder(JdbcTemplate jdbc, EncryptionService encryptionService,
            UserInterestTagRepository userInterestTagRepository) {
        this.jdbc = jdbc;
        this.encryptionService = encryptionService;
        this.userInterestTagRepository = userInterestTagRepository;
    }

    /**
     * {@code memberCount} 件の ACTIVE ユーザーを投入する（毎回呼び出しごとに新規帯へ投入。重複除けなし）。
     *
     * @param memberCount 投入するユーザー数
     * @return 投入結果（user_id 範囲・分布から算出した期待件数）
     */
    public SeedResult seed(int memberCount) {
        long t0 = System.nanoTime();

        // HMAC は事前に一度だけ計算（同一値の再計算は無駄かつ Bean 呼び出し回数を減らす）
        List<String> prefectureHashes = PREFECTURE_CODES.stream().map(encryptionService::hmac).toList();
        List<String> genderHashes = GENDERS.stream().map(encryptionService::hmac).toList();
        String tagHash = encryptionService.hmac(INTEREST_TAG);

        long userIdFrom = NEXT_USER_ID_BASE.getAndAdd(memberCount + BAND_MARGIN);
        insertUsersBatch(userIdFrom, memberCount, prefectureHashes, genderHashes);
        int taggedCount = insertInterestTagsBatch(userIdFrom, memberCount, tagHash);

        long ms = (System.nanoTime() - t0) / 1_000_000;

        // 分布の期待値算出（健全性チェックで使う）
        int perPrefecture = expectedCountPerModulo(memberCount, PREFECTURE_CODES.size());
        int perGender = expectedCountPerModulo(memberCount, GENDERS.size());

        return new SeedResult(userIdFrom, memberCount, ms, prefectureHashes, genderHashes, tagHash,
                perPrefecture, perGender, taggedCount);
    }

    /** {@code i % modulo == 0} の分布における、値0（先頭コード）が受け取る件数。 */
    private int expectedCountPerModulo(int memberCount, int modulo) {
        int base = memberCount / modulo;
        int remainder = memberCount % modulo;
        // i=0,1,2,...,memberCount-1 を modulo で割った余り0の個数（余りが余った分は先頭グループから+1される）
        return base + (remainder > 0 ? 1 : 0);
    }

    private void insertUsersBatch(long userIdFrom, int count, List<String> prefectureHashes,
            List<String> genderHashes) {
        String sql = "INSERT INTO users ("
                + "id, email, last_name, first_name, display_name, status, deleted_at, created_at, updated_at, "
                + "handle_searchable, contact_approval_required, online_visibility, is_searchable, dm_receive_from, "
                + "encryption_key_version, locale, timezone, reporting_restricted, follow_list_visibility, "
                + "care_notification_enabled, offline_only, prefecture_code_hash, gender_hash"
                + ") VALUES ("
                + "?, ?, 'L', 'F', ?, 'ACTIVE', NULL, ?, ?, "
                + "1, 1, 'NOBODY', 1, 'ANYONE', "
                + "1, 'ja', 'Asia/Tokyo', 0, 'PUBLIC', "
                + "1, 0, ?, ?)";
        LocalDateTime now = LocalDateTime.now();
        int prefN = prefectureHashes.size();
        int genderN = genderHashes.size();
        jdbc.execute((java.sql.Connection con) -> {
            boolean prevAutoCommit = con.getAutoCommit();
            con.setAutoCommit(false);
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                int inBatch = 0;
                for (int i = 0; i < count; i++) {
                    long userId = userIdFrom + i;
                    ps.setLong(1, userId);
                    ps.setString(2, "adaudience-" + userId + "@example.test");
                    ps.setString(3, "U" + userId);
                    ps.setObject(4, now);
                    ps.setObject(5, now);
                    ps.setString(6, prefectureHashes.get(i % prefN));
                    ps.setString(7, genderHashes.get(i % genderN));
                    ps.addBatch();
                    inBatch++;
                    if (inBatch == BATCH_SIZE) {
                        ps.executeBatch();
                        con.commit();
                        inBatch = 0;
                    }
                }
                if (inBatch > 0) {
                    ps.executeBatch();
                    con.commit();
                }
            } catch (SQLException e) {
                con.rollback();
                throw e;
            } finally {
                con.setAutoCommit(prevAutoCommit);
            }
            return null;
        });
    }

    /**
     * {@code user_interest_tags} を4人に1人（i % 4 == 0）へ投入する。挿入件数を返す。
     *
     * <p>JPA（{@link UserInterestTagRepository#saveAll}）経由で投入する（理由はコンストラクタ javadoc 参照）。
     * {@link #BATCH_SIZE} 件ごとに {@code saveAll} してリストをクリアし、メモリに全件を溜め込まない。</p>
     */
    private int insertInterestTagsBatch(long userIdFrom, int count, String tagHash) {
        List<UserInterestTagEntity> buffer = new ArrayList<>(BATCH_SIZE);
        int inserted = 0;
        for (int i = 0; i < count; i++) {
            if (i % 4 != 0) {
                continue;
            }
            long userId = userIdFrom + i;
            buffer.add(UserInterestTagEntity.create(userId, INTEREST_TAG, tagHash));
            inserted++;
            if (buffer.size() == BATCH_SIZE) {
                userInterestTagRepository.saveAll(buffer);
                buffer.clear();
            }
        }
        if (!buffer.isEmpty()) {
            userInterestTagRepository.saveAll(buffer);
        }
        return inserted;
    }

    /** 投入結果（分布の期待値込み）。 */
    public record SeedResult(
            long userIdFrom,
            int memberCount,
            long seedMs,
            List<String> prefectureHashes,
            List<String> genderHashes,
            String tagHash,
            int expectedCountFirstPrefecture,
            int expectedCountFirstGender,
            int expectedTaggedCount) {

        public long userIdTo() {
            return userIdFrom + memberCount - 1;
        }
    }
}
