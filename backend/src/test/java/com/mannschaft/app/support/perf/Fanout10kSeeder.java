package com.mannschaft.app.support.perf;

import com.mannschaft.app.timeline.PostScopeType;
import com.mannschaft.app.timeline.PostStatus;
import com.mannschaft.app.timeline.PostedAsType;
import com.mannschaft.app.timeline.entity.TimelinePostEntity;
import com.mannschaft.app.village.entity.VillageEntity;
import com.mannschaft.app.village.entity.VillageMeetupAttendanceEntity;
import com.mannschaft.app.village.entity.VillageMeetupEntity;
import com.mannschaft.app.village.entity.VillageMembershipEntity;
import com.mannschaft.app.village.entity.enums.VillageBulletinVisibility;
import com.mannschaft.app.village.entity.enums.VillageJoinPolicy;
import com.mannschaft.app.village.entity.enums.VillageMeetupAttendanceStatus;
import com.mannschaft.app.village.entity.enums.VillageMeetupStatus;
import com.mannschaft.app.village.entity.enums.VillageRole;
import com.mannschaft.app.village.entity.enums.VillageSubjectType;
import com.mannschaft.app.village.entity.enums.VillageType;
import com.mannschaft.app.village.entity.enums.VillageVisibility;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * β4（1万人規模）fan-out 実測 IT 用の合成データ投入ヘルパー（測定専用・production 非依存）。
 *
 * <p>本ヘルパーは <b>テスト支援コード</b>であり production の挙動には一切関与しない。
 * {@code application-test.yml} は {@code ddl-auto=create}（Entity 由来スキーマ・Flyway 無効）のため、
 * スキーマの既定値・シードは効かない。従って全 NOT NULL カラムを明示的に充填した Entity を投入する。</p>
 *
 * <h2>UUID エンコーディングの一貫性</h2>
 * <p>{@code village_id} / {@code meetup_id} / {@code scope_village_id} は {@code BINARY(16)}。
 * 生 JDBC で投入するとバイト順が Hibernate の UUID→bytes 変換とずれ、後段の JPQL パラメータ束縛と
 * 一致しなくなる危険がある。これを避けるため、本ヘルパーは <b>{@link EntityManager#persist} 経由</b>で
 * 投入し、UUID の符号化を Hibernate に一元化する（AC-1〜AC-5 の照合キーがブレない）。</p>
 *
 * <h2>性能</h2>
 * <p>{@link TransactionTemplate} で 1 トランザクションにまとめ、{@code batchSize} 件ごとに
 * {@code flush()+clear()} して 1 次キャッシュの肥大を抑える。tmpfs 上の MySQL で数万行を数十秒で投入する。</p>
 */
public final class Fanout10kSeeder {

    /** 現役 USER メンバーの subject_id 開始値（他テストとの衝突回避のため高位レンジを使う）。 */
    public static final long ACTIVE_SUBJECT_BASE = 900_000_000L;
    /** 退村（left_at）済みメンバーの subject_id 開始値。 */
    public static final long LEFT_SUBJECT_BASE = 910_000_000L;
    /** BAN（banned_at）済みメンバーの subject_id 開始値。 */
    public static final long BANNED_SUBJECT_BASE = 920_000_000L;

    private static final int FLUSH_BATCH = 500;

    private final EntityManager em;
    private final TransactionTemplate txTemplate;

    public Fanout10kSeeder(EntityManager em, TransactionTemplate txTemplate) {
        this.em = em;
        this.txTemplate = txTemplate;
    }

    /**
     * 合成村と各種行を投入する。
     *
     * @param activeMembers 現役 USER メンバー数（＝ fan-out の対象母集団）
     * @param leftMembers   退村済みメンバー数（対象境界の外＝通知されない）
     * @param bannedMembers BAN 済みメンバー数（対象境界の外＝通知されない）
     * @param attendances   CONFIRMED 寄合に付与する出欠行数
     * @param villagePosts  村フィード（scope=VILLAGE）に投入するシステム投稿数
     * @return 投入結果（村・寄合 UUID とアクター user_id）
     */
    public SeedResult seed(int activeMembers, int leftMembers, int bannedMembers,
                           int attendances, int villagePosts) {
        return txTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now();

            // --- 村本体（1 行）---
            VillageEntity village = VillageEntity.builder()
                    .slug("perf-fanout-" + UUID.randomUUID().toString().substring(0, 8))
                    .name("β4 fan-out 実測村")
                    .type(VillageType.COMMUNITY)
                    .joinPolicy(VillageJoinPolicy.FREE)
                    .visibility(VillageVisibility.PUBLIC)
                    .bulletinVisibility(VillageBulletinVisibility.MEMBERS_ONLY)
                    .memberCountCache((long) activeMembers)
                    .createdByUserId(ACTIVE_SUBJECT_BASE)
                    .build();
            em.persist(village);
            em.flush();
            UUID villageId = village.getId();

            // --- 現役メンバー（fan-out 対象母集団）---
            for (int i = 0; i < activeMembers; i++) {
                em.persist(membership(villageId, ACTIVE_SUBJECT_BASE + i,
                        i == 0 ? VillageRole.HEADMAN : VillageRole.VILLAGER, now, null, null));
                flushIfNeeded(i);
            }
            // --- 退村済み（対象外）---
            for (int i = 0; i < leftMembers; i++) {
                em.persist(membership(villageId, LEFT_SUBJECT_BASE + i, VillageRole.VILLAGER,
                        now, now.minusDays(1), null));
            }
            // --- BAN 済み（対象外）---
            for (int i = 0; i < bannedMembers; i++) {
                em.persist(membership(villageId, BANNED_SUBJECT_BASE + i, VillageRole.VILLAGER,
                        now, null, now.minusHours(3)));
            }
            em.flush();
            em.clear();

            // --- 出欠計測用の CONFIRMED 寄合（1 行）＋出欠行 ---
            VillageMeetupEntity confirmed = VillageMeetupEntity.builder()
                    .villageId(villageId)
                    .title("β4 出欠実測寄合")
                    .organizerUserId(ACTIVE_SUBJECT_BASE)
                    .status(VillageMeetupStatus.CONFIRMED)
                    .build();
            em.persist(confirmed);
            em.flush();
            UUID confirmedMeetupId = confirmed.getId();

            for (int i = 0; i < attendances; i++) {
                VillageMeetupAttendanceEntity a = VillageMeetupAttendanceEntity.builder()
                        .meetupId(confirmedMeetupId)
                        .userId(ACTIVE_SUBJECT_BASE + i)
                        .status(VillageMeetupAttendanceStatus.GOING)
                        .build();
                em.persist(a);
                flushIfNeeded(i);
            }
            em.flush();
            em.clear();

            // --- 村フィード（scope=VILLAGE）システム投稿 ---
            for (int i = 0; i < villagePosts; i++) {
                TimelinePostEntity p = TimelinePostEntity.builder()
                        .scopeType(PostScopeType.VILLAGE)
                        .scopeId(0L)
                        .scopeVillageId(villageId)
                        .userId(null)                          // システム投稿（投稿者不在）
                        .postedAsType(PostedAsType.USER)
                        .systemPostType("EVENT_CREATED")
                        .content("β4 村フィード実測投稿 #" + i)
                        .status(PostStatus.PUBLISHED)
                        .build();
                em.persist(p);
                flushIfNeeded(i);
            }
            em.flush();
            em.clear();

            return new SeedResult(villageId, confirmedMeetupId, ACTIVE_SUBJECT_BASE);
        });
    }

    private VillageMembershipEntity membership(UUID villageId, long subjectId, VillageRole role,
                                               LocalDateTime joinedAt, LocalDateTime leftAt,
                                               LocalDateTime bannedAt) {
        return VillageMembershipEntity.builder()
                .villageId(villageId)
                .subjectType(VillageSubjectType.USER)
                .subjectId(subjectId)
                .role(role)
                .joinedAt(joinedAt)
                .leftAt(leftAt)
                .bannedAt(bannedAt)
                .profilePublic(false)
                .build();
    }

    private void flushIfNeeded(int i) {
        if ((i + 1) % FLUSH_BATCH == 0) {
            em.flush();
            em.clear();
        }
    }

    /** 投入結果。 */
    public record SeedResult(UUID villageId, UUID confirmedMeetupId, long actorUserId) {
    }
}
