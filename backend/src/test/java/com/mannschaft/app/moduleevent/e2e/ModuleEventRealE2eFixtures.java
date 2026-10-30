package com.mannschaft.app.moduleevent.e2e;

import com.mannschaft.app.admin.BatchJobStatus;
import com.mannschaft.app.admin.entity.BatchJobLogEntity;
import com.mannschaft.app.auth.entity.UserEntity;
import com.mannschaft.app.common.UuidV7;
import com.mannschaft.app.common.event.ModuleEventListener;
import com.mannschaft.app.common.event.ReliableEvent;
import com.mannschaft.app.common.timezone.UserZoneLocalDateTimeParser;
import com.mannschaft.app.membership.domain.ScopeType;
import com.mannschaft.app.membership.entity.MembershipEntity;
import com.mannschaft.app.moduleevent.EventPublicationAttemptEntity;
import com.mannschaft.app.moduleevent.ModuleEventAttemptStatus;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventImpactBinding;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeAudience;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeDescriptor;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeRecipient;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventNoticeScope;
import com.mannschaft.app.moduleevent.api.dto.ModuleEventRecoveryState;
import com.mannschaft.app.moduleevent.api.spi.ModuleEventImpactResolver;
import com.mannschaft.app.organization.entity.OrganizationEntity;
import com.mannschaft.app.role.entity.UserRoleEntity;
import com.mannschaft.app.team.entity.TeamEntity;
import com.mannschaft.app.team.entity.TeamOrgMembershipEntity;
import jakarta.persistence.EntityManager;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.support.SimpleJpaRepository;
import org.springframework.modulith.events.core.EventPublicationRepository;
import org.springframework.modulith.events.core.PublicationTargetIdentifier;
import org.springframework.modulith.events.core.TargetEventPublication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 明示登録する実機専用検体。製品resolver/C・F業務の接続証明ではない。 */
@TestConfiguration(proxyBeanMethods = false)
public class ModuleEventRealE2eFixtures {
    public static final String KEY = "m21-real-e2e";
    public static final String FIRST = "m21-real-first";
    public static final String SECOND = "m21-real-second";
    public static final List<String> LOCALES = List.of("ja", "en", "zh", "ko", "es", "de");

    @Bean World m21World() { return new World(); }
    @Bean Domain m21Domain(EntityManager em, PasswordEncoder encoder, EventPublicationRepository publications,
            ApplicationEventPublisher publisher, World world) {
        return new Domain(em, encoder, publications, publisher, world);
    }
    @Bean Resolver m21Resolver(Domain domain) { return new Resolver(domain); }
    @Bean Listeners m21Listeners(Domain domain, World world) { return new Listeners(domain, world); }

    public record Actor(String code, long userId, String email, String password, String locale) { }
    public record ProbeEvent(UUID operationId, UUID episodeId, Long organizationId, Long teamId,
            LocalDateTime occurredAt) implements ReliableEvent { }

    public static final class Workflow {
        final UUID operationId = UUID.randomUUID();
        final UUID episodeId = UUID.randomUUID();
        final String kind;
        final long organizationId;
        final Long teamId;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release;
        volatile List<Long> logs = List.of();
        volatile List<UUID> publications = List.of();
        Workflow(String kind, long organizationId, Long teamId) {
            this.kind = kind; this.organizationId = organizationId; this.teamId = teamId;
            release = new CountDownLatch("fast".equals(kind) ? 0 : 1);
        }
        ProbeEvent event() { return new ProbeEvent(operationId, episodeId, organizationId, teamId, local(Instant.now())); }
    }

    public static final class World {
        final String run = UUID.randomUUID().toString().replace("-", "");
        final Map<String, Actor> actors = new ConcurrentHashMap<>();
        final Map<String, Workflow> workflows = new ConcurrentHashMap<>();
        final List<Long> users = new CopyOnWriteArrayList<>();
        final List<Long> roles = new CopyOnWriteArrayList<>();
        final List<Long> memberships = new CopyOnWriteArrayList<>();
        final List<Long> affiliations = new CopyOnWriteArrayList<>();
        final List<Long> teams = new CopyOnWriteArrayList<>();
        final List<Long> organizations = new CopyOnWriteArrayList<>();
        volatile Long revokedRole;
        volatile Long revokedMembership;
        Workflow workflow(ProbeEvent event) {
            return workflows.values().stream().filter(w -> w.operationId.equals(event.operationId())
                    && w.episodeId.equals(event.episodeId()) && w.organizationId == event.organizationId()
                    && Objects.equals(w.teamId, event.teamId())).findFirst().orElseThrow();
        }
        Workflow workflow(ModuleEventImpactBinding binding) {
            return workflows.values().stream().filter(w -> KEY.equals(binding.getResolverKey())
                    && w.operationId.equals(binding.getOperationId()) && w.episodeId.equals(binding.getEpisodeId())
                    && Objects.equals(w.organizationId, binding.getOrganizationId())
                    && (w.teamId == null ? binding.getScopeType() == ModuleEventNoticeScope.ORGANIZATION
                        && binding.getScopeId() == w.organizationId
                        : binding.getScopeType() == ModuleEventNoticeScope.TEAM
                        && w.teamId.equals(binding.getScopeId()))).findFirst().orElseThrow();
        }
        void release(String kind) { workflows.get(kind).release.countDown(); }
        void releaseAll() { workflows.values().forEach(w -> w.release.countDown()); }
        public List<Actor> credentials() { return actors.values().stream().sorted(java.util.Comparator.comparing(Actor::code)).toList(); }
    }

    /** M TX外のSPIから呼ぶ検体所有の短TX。役割とdurable closeをM側で推測しない。 */
    public static class Domain {
        private final EntityManager em;
        private final PasswordEncoder encoder;
        private final EventPublicationRepository publications;
        private final ApplicationEventPublisher publisher;
        private final World world;
        Domain(EntityManager em, PasswordEncoder encoder, EventPublicationRepository publications,
                ApplicationEventPublisher publisher, World world) {
            this.em = em; this.encoder = encoder; this.publications = publications;
            this.publisher = publisher; this.world = world;
        }
        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
        public void seed() {
            // hostの専用DB照合に加え、通常Flywayの基底だけを認める。基底行は変更・削除しない。
            require(world.users.isEmpty() && world.actors.isEmpty() && world.workflows.isEmpty()
                    && world.roles.isEmpty() && world.memberships.isEmpty() && world.affiliations.isEmpty()
                    && world.teams.isEmpty() && world.organizations.isEmpty(), "M21検体は未作成でなければならない");
            require(count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=0") == 0
                    && count("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1 AND script IN "
                            + "('V1.012__seed_system_user.sql','V12.004__seed_deleted_sentinel_user.sql')") == 2,
                    "通常Flywayの基底migration成功が必要");
            require(count("SELECT COUNT(*) FROM users") == 2
                    && count("SELECT COUNT(*) FROM users WHERE id=1 AND email='system@mannschaft.local' "
                            + "AND password_hash IS NULL AND status='ACTIVE'") == 1
                    && count("SELECT COUNT(*) FROM users WHERE email='deleted@system.internal' "
                            + "AND password_hash IS NULL AND status='ARCHIVED'") == 1,
                    "M21は通常Flyway基底2行だけの専用DBを使う。sentinelの採番は推測しない");
            Actor system = actor("system", "ja");
            assign(system, "SYSTEM_ADMIN", null, null);
            OrganizationEntity first = organization("a");
            OrganizationEntity other = organization("b");
            TeamEntity team = team("a"); TeamEntity foreignTeam = team("b");
            affiliation(team.getId(), first.getId(), system.userId());
            affiliation(foreignTeam.getId(), other.getId(), system.userId());
            for (String locale : LOCALES) {
                Actor admin = actor("admin-" + locale, locale);
                assign(admin, "ADMIN", team.getId(), null);
                membership(admin, ScopeType.TEAM, team.getId());
                assign(admin, "MEMBER", null, first.getId());
                membership(admin, ScopeType.ORGANIZATION, first.getId());
            }
            Actor member = actor("member", "ja");
            assign(member, "MEMBER", team.getId(), null); membership(member, ScopeType.TEAM, team.getId());
            assign(member, "MEMBER", null, first.getId()); membership(member, ScopeType.ORGANIZATION, first.getId());
            Actor orgAdmin = actor("org-admin", "ja");
            assign(orgAdmin, "ADMIN", null, first.getId()); membership(orgAdmin, ScopeType.ORGANIZATION, first.getId());
            Actor outsider = actor("outsider", "ja");
            assign(outsider, "ADMIN", foreignTeam.getId(), null); membership(outsider, ScopeType.TEAM, foreignTeam.getId());
            assign(outsider, "ADMIN", null, other.getId()); membership(outsider, ScopeType.ORGANIZATION, other.getId());
            Actor revoked = actor("revoked", "ja");
            world.revokedRole = assign(revoked, "ADMIN", team.getId(), null);
            world.revokedMembership = membership(revoked, ScopeType.TEAM, team.getId());
            for (String kind : List.of("delayed", "fast", "quarantine")) {
                world.workflows.put(kind, new Workflow(kind, first.getId(), team.getId()));
            }
            world.workflows.put("organization", new Workflow("organization", first.getId(), null));
            seedWorkflow(world.workflows.get("delayed"), false);
            seedWorkflow(world.workflows.get("organization"), false);
            seedWorkflow(world.workflows.get("fast"), true);
        }
        private Actor actor(String code, String locale) {
            byte[] random = new byte[24]; new SecureRandom().nextBytes(random);
            String password = "Aa1!" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            String email = "m21-" + world.run + "-" + code + "@test.mannschaft.local";
            UserEntity user = save(UserEntity.builder().email(email).passwordHash(encoder.encode(password))
                    .lastName("M21").firstName(code).lastNameKana("エム").firstNameKana("テスト")
                    .displayName("M21 " + code).isSearchable(false).locale(locale).countryCode("JP")
                    .timezone("Asia/Tokyo").status(UserEntity.UserStatus.ACTIVE)
                    .privacyPolicyAcceptedAt(local(Instant.now())).privacyPolicyVersion("1.1.1").build(), UserEntity.class);
            world.users.add(user.getId()); Actor actor = new Actor(code, user.getId(), email, password, locale);
            world.actors.put(code, actor); return actor;
        }
        private OrganizationEntity organization(String suffix) {
            OrganizationEntity value = save(OrganizationEntity.builder().name("M21 " + suffix)
                    .slug("m21-" + world.run.substring(0, 12) + "-" + suffix)
                    .orgType(OrganizationEntity.OrgType.OTHER).visibility(OrganizationEntity.Visibility.PRIVATE)
                    .hierarchyVisibility(OrganizationEntity.HierarchyVisibility.NONE).supporterEnabled(false).build(), OrganizationEntity.class);
            world.organizations.add(value.getId()); return value;
        }
        private TeamEntity team(String suffix) {
            TeamEntity value = save(TeamEntity.builder().name("M21 " + suffix)
                    .slug("m21-" + world.run.substring(0, 12) + "-" + suffix)
                    .visibility(TeamEntity.Visibility.MEMBERS_AND_ABOVE).supporterEnabled(false).build(), TeamEntity.class);
            world.teams.add(value.getId()); return value;
        }
        private void affiliation(long team, long organization, long actor) {
            TeamOrgMembershipEntity value = save(TeamOrgMembershipEntity.builder().teamId(team).organizationId(organization)
                    .status(TeamOrgMembershipEntity.Status.ACTIVE).invitedBy(actor).respondedBy(actor)
                    .invitedAt(local(Instant.now())).respondedAt(local(Instant.now())).build(), TeamOrgMembershipEntity.class);
            world.affiliations.add(value.getId());
        }
        private long assign(Actor actor, String name, Long team, Long organization) {
            Long role = em.createQuery("SELECT r.id FROM RoleEntity r WHERE r.name=:name", Long.class)
                    .setParameter("name", name).getSingleResult();
            UserRoleEntity row = save(UserRoleEntity.builder().userId(actor.userId()).roleId(role)
                    .teamId(team).organizationId(organization).build(), UserRoleEntity.class);
            world.roles.add(row.getId()); return row.getId();
        }
        private long membership(Actor actor, ScopeType type, long scope) {
            MembershipEntity row = save(MembershipEntity.builder().userId(actor.userId()).scopeType(type)
                    .scopeId(scope).joinedAt(local(Instant.now())).build(), MembershipEntity.class);
            world.memberships.add(row.getId()); return row.getId();
        }
        private void seedWorkflow(Workflow workflow, boolean publish) {
            require(workflow.logs.isEmpty(), "episodeを再利用しない");
            List<Long> logs = new ArrayList<>();
            for (String target : List.of(FIRST, SECOND)) {
                BatchJobLogEntity log = save(BatchJobLogEntity.builder().jobName("m21-" + workflow.operationId + "-" + target)
                        .status(BatchJobStatus.RUNNING).startedAt(local(Instant.now())).build(), BatchJobLogEntity.class);
                logs.add(log.getId());
            }
            workflow.logs = List.copyOf(logs);
            ProbeEvent event = workflow.event();
            if (publish) {
                publisher.publishEvent(event); em.flush();
                List<?> stored = em.createNativeQuery("SELECT publication_id FROM event_publication_attempts WHERE operation_id=:op")
                        .setParameter("op", bytes(workflow.operationId)).getResultList();
                workflow.publications = stored.stream().map(value -> uuid((byte[]) value)).toList();
                return;
            }
            List<UUID> ids = new ArrayList<>(); Instant now = Instant.now();
            for (String target : List.of(FIRST, SECOND)) {
                TargetEventPublication row = publications.create(TargetEventPublication.of(event,
                        PublicationTargetIdentifier.of(target), now.minusSeconds(1801)));
                ids.add(row.getIdentifier());
                save(EventPublicationAttemptEntity.builder().id(UuidV7.generate()).publicationId(row.getIdentifier())
                        .status("quarantine".equals(workflow.kind) ? ModuleEventAttemptStatus.IN_PROGRESS : ModuleEventAttemptStatus.PENDING)
                        .retryCount("quarantine".equals(workflow.kind) ? 10 : 0).claimToken(UUID.randomUUID())
                        .leaseUntil("quarantine".equals(workflow.kind) ? now.minusSeconds(1) : null)
                        .resolverKey(KEY).operationId(event.operationId()).episodeId(event.episodeId())
                        .scopeType(workflow.teamId == null ? ModuleEventNoticeScope.ORGANIZATION : ModuleEventNoticeScope.TEAM)
                        .scopeId(workflow.teamId == null ? workflow.organizationId : workflow.teamId)
                        .organizationId(workflow.organizationId).createdAt(now).updatedAt(now).version(0).build(),
                        EventPublicationAttemptEntity.class);
            }
            workflow.publications = List.copyOf(ids);
        }
        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
        public void revokeAndSeedQuarantine() {
            require(world.workflows.get("quarantine").logs.isEmpty(), "Q検体の重複作成を拒否する");
            em.remove(em.find(UserRoleEntity.class, world.revokedRole));
            em.remove(em.find(MembershipEntity.class, world.revokedMembership)); em.flush();
            seedWorkflow(world.workflows.get("quarantine"), false);
        }
        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
        public void complete(ProbeEvent event, int index) {
            Workflow workflow = world.workflow(event);
            BatchJobLogEntity row = em.find(BatchJobLogEntity.class, workflow.logs.get(index));
            require(row != null, "検体workflowの欠損"); row.complete(1); em.flush();
        }
        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
        public ModuleEventRecoveryState recovery(ModuleEventImpactBinding binding) {
            Workflow workflow = world.workflow(binding);
            if (workflow.logs.size() != 2) return ModuleEventRecoveryState.UNKNOWN;
            Long completed = em.createQuery("SELECT COUNT(b) FROM BatchJobLogEntity b WHERE b.id IN :ids AND b.status=:status", Long.class)
                    .setParameter("ids", workflow.logs).setParameter("status", BatchJobStatus.SUCCESS).getSingleResult();
            return completed == 2 ? ModuleEventRecoveryState.CLOSED_COMPLETE : ModuleEventRecoveryState.OPEN;
        }
        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
        public List<ModuleEventNoticeRecipient> recipients(ModuleEventNoticeDescriptor notice, long after, int limit, List<Long> candidates) {
            require(limit >= 1 && limit <= 100 && after >= 0, "受信者境界が不正");
            Workflow workflow = world.workflow(notice.getImpact());
            List<Long> selected = candidates == null ? List.copyOf(world.users) : List.copyOf(candidates);
            if (selected.isEmpty()) return List.of();
            require(selected.size() <= 100, "候補上限を超えた");
            selected = selected.stream().filter(world.users::contains).toList();
            if (selected.isEmpty()) return List.of();
            String type = workflow.teamId == null ? "ORGANIZATION" : "TEAM";
            long scope = workflow.teamId == null ? workflow.organizationId : workflow.teamId;
            boolean admin = notice.getAudience() == ModuleEventNoticeAudience.CURRENT_ADMINS;
            List<?> rows = em.createNativeQuery("""
                    SELECT DISTINCT u.id,u.locale FROM users u JOIN memberships m ON m.user_id=u.id
                    WHERE u.id IN (:users) AND u.id>:after AND u.deleted_at IS NULL AND u.status='ACTIVE'
                      AND m.scope_type=:type AND m.scope_id=:scope AND m.left_at IS NULL
                      AND m.archived_at IS NULL AND m.gdpr_masked_at IS NULL
                      AND EXISTS (SELECT 1 FROM organizations o WHERE o.id=:org AND o.deleted_at IS NULL AND o.lifecycle_status='ACTIVE')
                      AND (:team IS NULL OR EXISTS (SELECT 1 FROM teams t JOIN team_org_memberships a ON a.team_id=t.id
                           WHERE t.id=:team AND t.deleted_at IS NULL AND t.lifecycle_status='ACTIVE'
                             AND a.organization_id=:org AND a.status='ACTIVE'))
                      AND ((:admin=1 AND EXISTS (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id
                            WHERE ur.user_id=u.id AND r.name='ADMIN'
                              AND ((:type='TEAM' AND ur.team_id=:scope AND ur.organization_id IS NULL)
                                OR (:type='ORGANIZATION' AND ur.organization_id=:scope AND ur.team_id IS NULL))))
                        OR (:admin=0 AND u.id=:affected))
                    ORDER BY u.id LIMIT :limit
                    """).setParameter("users", selected).setParameter("after", after).setParameter("type", type)
                    .setParameter("scope", scope).setParameter("org", workflow.organizationId).setParameter("team", workflow.teamId)
                    .setParameter("admin", admin ? 1 : 0).setParameter("affected", world.actors.get("member").userId())
                    .setParameter("limit", limit).getResultList();
            return rows.stream().map(value -> { Object[] row = (Object[]) value;
                return new ModuleEventNoticeRecipient(((Number) row[0]).longValue(), (String) row[1]); }).toList();
        }
        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
        public Map<String, Object> snapshot() {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Workflow workflow : world.workflows.values()) {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("operationId", workflow.operationId); state.put("publications", workflow.publications);
                state.put("pending", count("SELECT COUNT(*) FROM event_publication_attempts a JOIN event_publication p ON p.id=a.publication_id WHERE a.operation_id=:op AND p.completion_date IS NULL", workflow.operationId));
                state.put("completed", workflow.logs.isEmpty() ? 0 : em.createQuery("SELECT COUNT(b) FROM BatchJobLogEntity b WHERE b.id IN :ids AND b.status=:status", Long.class)
                        .setParameter("ids", workflow.logs).setParameter("status", BatchJobStatus.SUCCESS).getSingleResult());
                state.put("entered", workflow.entered.getCount() == 0);
                state.put("noticeCount", count("SELECT COUNT(*) FROM module_event_notice_outbox WHERE operation_id=:op", workflow.operationId));
                state.put("recovered", count("SELECT COUNT(*) FROM module_event_notice_outbox WHERE operation_id=:op AND phase='RECOVERED' AND status IN ('PENDING','RELAYING','RELAYED')", workflow.operationId));
                Map<String, Map<String, Long>> deliveries = new LinkedHashMap<>();
                for (Object value : em.createNativeQuery("SELECT o.phase,n.user_id,COUNT(*) FROM notifications n JOIN module_event_notice_outbox o ON o.notice_ref=n.source_id WHERE n.source_type='MODULE_EVENT_NOTICE' AND o.operation_id=:op GROUP BY o.phase,n.user_id")
                        .setParameter("op", bytes(workflow.operationId)).getResultList()) {
                    Object[] row = (Object[]) value; deliveries.computeIfAbsent((String) row[0], ignored -> new LinkedHashMap<>())
                            .put(((Number) row[1]).toString(), ((Number) row[2]).longValue());
                }
                state.put("deliveries", deliveries); result.put(workflow.kind, state);
            }
            result.put("actors", world.actors.values().stream().map(a -> Map.of("code", a.code(), "userId", a.userId(), "locale", a.locale())).toList());
            result.put("auditCount", ((Number) em.createNativeQuery("SELECT COUNT(*) FROM module_event_resume_audits WHERE actor_user_id=:actor")
                    .setParameter("actor", world.actors.get("system").userId()).getSingleResult()).longValue());
            return result;
        }
        /** 自分の生成IDのみ撤去。全background停止後にhostから呼ぶ。失敗は隠さない。 */
        @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED, readOnly = false)
        public void cleanup() {
            for (Workflow workflow : world.workflows.values()) {
                for (Object reference : em.createNativeQuery("SELECT notice_ref FROM module_event_notice_outbox WHERE operation_id=:op")
                        .setParameter("op", bytes(workflow.operationId)).getResultList()) {
                    long ref = ((Number) reference).longValue();
                    for (String sql : List.of(
                            "DELETE FROM notifications WHERE source_type='MODULE_EVENT_NOTICE' AND source_id=:ref",
                            "DELETE FROM notifications_archive WHERE source_type='MODULE_EVENT_NOTICE' AND source_id=:ref",
                            "DELETE FROM notification_fanout_job_messages WHERE job_id IN (SELECT id FROM notification_fanout_jobs WHERE source_type='MODULE_EVENT_NOTICE' AND source_id=:ref)",
                            "DELETE FROM notification_fanout_jobs WHERE source_type='MODULE_EVENT_NOTICE' AND source_id=:ref")) {
                        em.createNativeQuery(sql).setParameter("ref", ref).executeUpdate();
                    }
                }
                em.createNativeQuery("DELETE FROM module_event_notice_outbox WHERE operation_id=:op")
                        .setParameter("op", bytes(workflow.operationId)).executeUpdate();
                em.createNativeQuery("DELETE FROM event_publication_attempts WHERE operation_id=:op")
                        .setParameter("op", bytes(workflow.operationId)).executeUpdate();
                for (UUID publication : workflow.publications) {
                    em.createNativeQuery("DELETE FROM module_event_resume_audits WHERE publication_id=:id")
                            .setParameter("id", bytes(publication)).executeUpdate();
                    em.createNativeQuery("DELETE FROM event_publication WHERE id=:id")
                            .setParameter("id", bytes(publication)).executeUpdate();
                }
                for (Long id : workflow.logs) remove(BatchJobLogEntity.class, id);
            }
            // 認証/運用alertが生成した通知も、自己user IDのものだけ撤去する。
            if (!world.users.isEmpty()) {
                em.createNativeQuery("DELETE FROM notifications WHERE user_id IN (:ids)").setParameter("ids", world.users).executeUpdate();
                em.createNativeQuery("DELETE FROM notifications_archive WHERE user_id IN (:ids)").setParameter("ids", world.users).executeUpdate();
            }
            world.roles.forEach(id -> remove(UserRoleEntity.class, id));
            world.memberships.forEach(id -> remove(MembershipEntity.class, id));
            world.affiliations.forEach(id -> remove(TeamOrgMembershipEntity.class, id)); em.flush();
            world.teams.forEach(id -> remove(TeamEntity.class, id));
            world.organizations.forEach(id -> remove(OrganizationEntity.class, id)); em.flush();
            world.users.forEach(id -> remove(UserEntity.class, id)); em.flush();
        }
        private <T> void remove(Class<T> type, Long id) {
            T value = em.find(type, id); if (value != null) em.remove(value);
        }
        private long count(String sql) { return ((Number) em.createNativeQuery(sql).getSingleResult()).longValue(); }
        private long count(String sql, UUID id) { return ((Number) em.createNativeQuery(sql).setParameter("op", bytes(id)).getSingleResult()).longValue(); }
        private <T> T save(T value, Class<T> type) { return new SimpleJpaRepository<T, Object>(type, em).saveAndFlush(value); }
    }

    public static final class Resolver implements ModuleEventImpactResolver {
        private final Domain domain;
        Resolver(Domain domain) { this.domain = domain; }
        @Override public String resolverKey() { return KEY; }
        @Override public Optional<ModuleEventImpactBinding> identify(ReliableEvent candidate) {
            if (!(candidate instanceof ProbeEvent event)) return Optional.empty();
            return Optional.of(new ModuleEventImpactBinding(KEY, event.operationId(), event.episodeId(),
                    event.teamId() == null ? ModuleEventNoticeScope.ORGANIZATION : ModuleEventNoticeScope.TEAM,
                    event.teamId() == null ? event.organizationId() : event.teamId(), event.organizationId()));
        }
        @Override public ModuleEventRecoveryState recoveryState(ModuleEventImpactBinding binding) { outside(); return domain.recovery(binding); }
        @Override public List<ModuleEventNoticeRecipient> nextRecipients(ModuleEventNoticeDescriptor notice, long after, int limit) {
            outside(); return domain.recipients(notice, after, limit, null);
        }
        @Override public List<ModuleEventNoticeRecipient> eligibleRecipients(ModuleEventNoticeDescriptor notice, List<Long> candidates) {
            outside(); return domain.recipients(notice, 0, Math.max(1, candidates.size()), candidates);
        }
        private static void outside() { require(!TransactionSynchronizationManager.isActualTransactionActive(), "M TX内で業務SPIを呼んだ"); }
    }
    public static class Listeners {
        private final Domain domain; private final World world;
        Listeners(Domain domain, World world) { this.domain = domain; this.world = world; }
        @ModuleEventListener(id = FIRST) public void first(ProbeEvent event) { domain.complete(event, 0); }
        @ModuleEventListener(id = SECOND) public void second(ProbeEvent event) {
            Workflow workflow = world.workflow(event); workflow.entered.countDown();
            try { require(workflow.release.await(600, TimeUnit.SECONDS), "M21検体gate待機上限"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("M21_GATE_INTERRUPTED", interrupted); }
            domain.complete(event, 1);
        }
    }
    static void require(boolean accepted, String message) { if (!accepted) throw new IllegalStateException(message); }
    static LocalDateTime local(Instant value) { return LocalDateTime.ofInstant(value, UserZoneLocalDateTimeParser.SERVER_ZONE); }
    static UUID uuid(byte[] raw) { ByteBuffer value = ByteBuffer.wrap(raw); return new UUID(value.getLong(), value.getLong()); }
    static byte[] bytes(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
}
