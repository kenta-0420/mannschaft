package com.mannschaft.app.member;

import com.mannschaft.app.auth.service.AuditLogService;
import com.mannschaft.app.member.service.MemberProfileService;
import com.mannschaft.app.member.service.MemberSubtabVisibilityService;
import com.mannschaft.app.member.service.TeamPageSectionService;
import com.mannschaft.app.member.service.TeamPageService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PR #3387 D-3T 根治 試練 — AC-D3T-3・AC-D3T-4（構造の固定。軍議書 gungi-3387-d3t.md 第3版 §7）。
 *
 * <p>「他ドメインの権限確認・名前解決を読む Service にはクラス単位の TX を付けない。書き込みはメソッド単位の
 * TX を必ず持ち、別の部品に分ける」（判断点5の規約例外）をリフレクションで固定する。</p>
 *
 * <p>「閲覧4クラス」は軍議書 §2.1(a)(b)(c) で TX を外す4クラス（TeamPageService・MemberProfileService・
 * TeamPageSectionService・MemberSubtabVisibilityService）と解した。</p>
 *
 * <p>新設の {@code MemberSubtabVisibilityWriter} はクラス名で引く（出陣前でもこのテストをコンパイル可能に
 * 保ち、未実装を「クラスが無い」赤として示すため）。</p>
 */
@DisplayName("PR #3387 D-3T 試練 AC-3/AC-4: member の TX 境界の構造")
class MemberTransactionBoundaryTest {

    private static final String WRITER_FQCN = "com.mannschaft.app.member.service.MemberSubtabVisibilityWriter";

    private static final List<Class<?>> NON_TX_CLASSES = List.of(
            TeamPageService.class,
            MemberProfileService.class,
            TeamPageSectionService.class,
            MemberSubtabVisibilityService.class);

    /** 閲覧・段取りのメソッド（TX を持ってはならない）。 */
    private static final Map<Class<?>, List<String>> NON_TX_METHODS = Map.of(
            TeamPageService.class, List.of("listPages", "getPage"),
            MemberProfileService.class, List.of("listProfiles", "getProfile", "lookupMembers"),
            TeamPageSectionService.class, List.of("listSections"),
            MemberSubtabVisibilityService.class,
            List.of("getSettings", "updateSettings", "assertViewable", "resolveMinRole"));

    /** 書き込み15メソッド（{@code @Transactional(readOnly=false)} を必ず持つ）。 */
    private static final Map<Class<?>, List<String>> WRITE_METHODS = Map.of(
            TeamPageService.class, List.of("createPage", "updatePage", "deletePage", "changeStatus",
                    "issuePreviewToken", "revokePreviewToken"),
            MemberProfileService.class, List.of("createProfile", "updateProfile", "deleteProfile",
                    "bulkCreate", "copyMembers", "reorderMembers"),
            TeamPageSectionService.class, List.of("createSection", "updateSection", "deleteSection"));

    private static List<Method> methodsNamed(Class<?> clazz, String name) {
        List<Method> found = Arrays.stream(clazz.getDeclaredMethods())
                .filter(m -> m.getName().equals(name))
                .filter(m -> !m.isSynthetic() && !m.isBridge())
                .toList();
        assertThat(found).as("前提: %s#%s が存在する", clazz.getSimpleName(), name).isNotEmpty();
        return found;
    }

    @Test
    @DisplayName("AC-3: 閲覧4クラスのクラスに @Transactional が無い")
    void 閲覧4クラスにクラス単位のTXが無い() {
        for (Class<?> clazz : NON_TX_CLASSES) {
            assertThat(clazz.isAnnotationPresent(Transactional.class))
                    .as("%s にクラス単位の @Transactional がある（権限確認が member の TX の中で走る）",
                            clazz.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("AC-3: 閲覧・段取りのメソッドに @Transactional が無い")
    void 閲覧と段取りのメソッドにTXが無い() {
        NON_TX_METHODS.forEach((clazz, names) -> {
            for (String name : names) {
                for (Method m : methodsNamed(clazz, name)) {
                    assertThat(m.isAnnotationPresent(Transactional.class))
                            .as("%s#%s に @Transactional がある", clazz.getSimpleName(), name)
                            .isFalse();
                }
            }
        });
    }

    @Test
    @DisplayName("AC-3: 書き込み15メソッドが @Transactional(readOnly=false) を持つ")
    void 書き込み15メソッドがTXを持つ() {
        int count = 0;
        for (Map.Entry<Class<?>, List<String>> e : WRITE_METHODS.entrySet()) {
            for (String name : e.getValue()) {
                for (Method m : methodsNamed(e.getKey(), name)) {
                    Transactional tx = m.getAnnotation(Transactional.class);
                    assertThat(tx).as("%s#%s にメソッド単位の @Transactional が無い",
                            e.getKey().getSimpleName(), name).isNotNull();
                    assertThat(tx.readOnly()).as("%s#%s が readOnly", e.getKey().getSimpleName(), name).isFalse();
                }
                count++;
            }
        }
        assertThat(count).isEqualTo(15);
    }

    @Test
    @DisplayName("AC-4: MemberSubtabVisibilityWriter#applyUpdates が @Transactional(readOnly=false)")
    void writerのapplyUpdatesが書き込みTX() throws Exception {
        Class<?> writer = Class.forName(WRITER_FQCN);
        assertThat(writer).isNotEqualTo(MemberSubtabVisibilityService.class);
        List<Method> applyUpdates = methodsNamed(writer, "applyUpdates");
        for (Method m : applyUpdates) {
            Transactional tx = m.getAnnotation(Transactional.class);
            assertThat(tx).as("applyUpdates にメソッド単位の @Transactional が無い").isNotNull();
            assertThat(tx.readOnly()).isFalse();
        }
    }

    @Test
    @DisplayName("AC-4: MemberSubtabVisibilityService は writer をフィールドに持ち、AuditLogService に依存しない")
    void 段取り役はwriterを持ちAuditLogServiceに依存しない() throws Exception {
        Class<?> writer = Class.forName(WRITER_FQCN);
        List<Class<?>> fieldTypes = Arrays.stream(MemberSubtabVisibilityService.class.getDeclaredFields())
                .map(Field::getType)
                .<Class<?>>map(t -> t)
                .toList();
        assertThat(fieldTypes).as("writer をフィールドとして保持する").contains(writer);
        assertThat(fieldTypes).as("AuditLogService への依存が残っている").doesNotContain(AuditLogService.class);
    }
}
