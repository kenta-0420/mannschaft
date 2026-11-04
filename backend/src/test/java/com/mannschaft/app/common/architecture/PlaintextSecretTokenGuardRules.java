package com.mannschaft.app.common.architecture;

import com.mannschaft.app.common.token.BearerSecretValkeyKey;
import com.mannschaft.app.common.token.IssuedToken;
import com.mannschaft.app.common.token.PlaintextSecretRequired;
import com.mannschaft.app.common.token.PlaintextTokenDebt;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.properties.HasAnnotations;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Transient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.asm.Type;
import org.springframework.core.GenericTypeResolver;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * 番人 {@link PlaintextSecretTokenGuardArchTest}（CMP-260826-1457 §2-4 番人1〜6）の判定ロジックの単一正準。
 *
 * <p>本番の番人（{@link PlaintextSecretTokenGuardArchTest}）と自己検証（{@link PlaintextSecretTokenGuardConditionTest}）は、
 * どちらもこのクラスの同じメソッドを呼ぶ（判定ロジックを二重に書かない）。判定はリフレクション・ArchUnit・バイトコードで行い、
 * ソースの字句解析は使わない。</p>
 *
 * <h2>実装方針</h2>
 * <p>列と型は ArchUnit の {@code JavaClass}、呼び出しの同居は {@code getMethodCallsFromSelf()} で判定する。
 * 文字列定数（{@code INSERT INTO}/{@code UPDATE}）だけは ArchUnit が公開しないため、クラスファイルを ASM
 * （Spring が同梱する {@code org.springframework.asm}）で読んで取る。</p>
 *
 * <h2>結果の表記の約束（テストはこの約束に依存する）</h2>
 * <ul>
 *   <li>フィールドは {@code 完全修飾クラス名 + "." + フィールド名}（例 {@code com.example.FooEntity.token}）</li>
 *   <li>メソッドは {@code 完全修飾クラス名 + "#" + メソッド名}（例 {@code com.example.FooService#issue}）。
 *       番人4で Repository メソッドの引数を指すときも、そのメソッドの表記を使う</li>
 *   <li>各要素は上の識別子そのものか、識別子の後に半角空白を1つ置いて理由を続けたもの
 *       （例 {@code "com.example.FooEntity.token 平文の token 系列（_hash でなく免除注釈も無い）"}）</li>
 * </ul>
 *
 * <h2>各番人の判定（陣立て書 §2-4 の要約）</h2>
 * <ol>
 *   <li><b>番人1（列）</b>: {@code @Entity}・{@code @MappedSuperclass} の宣言フィールドで、型が {@code String}/{@code char[]} かつ
 *       フィールド名または {@code @Column(name)} が {@code (?i)(token|secret|passcode|invite_code|share_key)} に一致するものを検査対象とする。
 *       列名（{@code @Column(name)}、無ければフィールド名の snake_case）が {@code _hash} で終わるもの、
 *       有効な免除注釈が付くものは違反でない。</li>
 *   <li><b>番人2（Repository）</b>: Spring Data の {@code Repository} を継承する全インタフェース（{@code @NoRepositoryBean} を含む）の
 *       宣言メソッドを検査する。名前に Token/Secret 系の語を含み Hash を含まない検索メソッド、または {@code @Query} 本文が
 *       平文の token 系プロパティを等値比較しているメソッドは、対応する Entity フィールド（継承元を含む）が有効な免除注釈を
 *       持たない限り違反。</li>
 *   <li><b>番人3（自前発行）</b>: 同じメソッドの中で {@code UUID.randomUUID()} または {@code SecureRandom#nextBytes} を呼び、かつ
 *       {@code @Entity} クラス（またはその入れ子の builder）の token 系の名前のメソッドで String/char[] を受け取るものを呼ぶメソッドは違反。
 *       メソッドに有効な免除注釈があれば免除。</li>
 *   <li><b>番人4（IssuedToken の永続化）</b>: {@code IssuedToken} を {@code @Entity}/{@code @MappedSuperclass} のフィールド型
 *       （{@code @Transient} を含む）や Repository メソッドの引数型に使うことは違反（免除なし）。</li>
 *   <li><b>番人6（列を通らない書き込み）</b>: (a) {@code INSERT INTO}/{@code UPDATE} を含む文字列定数を持ち、かつ自前乱数を呼ぶメソッド、
 *       (b) {@code BearerSecretValkeyKey.of} を呼び、かつ自前乱数を呼ぶメソッドは違反。メソッドに有効な免除注釈があれば免除。
 *       {@code BearerSecretValkeyKey.of} を呼ばない Valkey の一時キー（CSV プレビュー等）は対象外。</li>
 * </ol>
 *
 * <p><b>有効な免除注釈</b>: {@code @PlaintextSecretRequired}（{@code reason} が空白以外の文字を含む）または
 * {@code @PlaintextTokenDebt}（{@code cmp} が {@code docs/task-list.md} に登録された CMP ID で、状態が完了でないもの。
 * {@link #isOpenRegisteredCmp}）。任意の文字列・未登録の ID・完了済みの戦役は無効で、
 * 無効な免除注釈が付いた要素は免除されず違反になる。</p>
 *
 * <h2>番人3の健全性の範囲と既知の限界（偽陰性。2026-11-03・11-04 殿裁定）</h2>
 * <p><b>覆う範囲</b>: メソッド内のローカル変数（とオペランドスタック）の流れ。制御フローは通常の辺（分岐の合流
 * ［if/else・三項演算子・switch］）、後方辺（ループで次の周回へ持ち越される値）、例外辺（try 範囲の各命令の実行前の状態を
 * ハンドラへ合流。入れ子の try・finally を含む）のすべてを含む。乱数を詰めた配列の別名も追う。
 * 基本ブロックとワークリストによる不動点反復の may 解析である。</p>
 * <p><b>既知の限界（検出しない）</b>:</p>
 * <ul>
 *   <li>メソッドをまたぐ流れ: 乱数の生成ヘルパ（別メソッドが {@code UUID.randomUUID()} を返し、呼び出し側が戻り値を
 *       token 系に詰める）と、書込みヘルパ（乱数を引数に別メソッドを呼び、その中で Entity の token 系に詰める）</li>
 *   <li>フィールド経由: 乱数をフィールドへ入れ、読み戻して token 系に詰める形（フィールドの読み出しは乱数由来としない）</li>
 *   <li>ラムダの本体: 乱数を捕捉したラムダの本体（合成メソッド）で token 系に詰める形</li>
 *   <li>リフレクション: {@code Method#invoke} 等で token 系の setter を呼ぶ形</li>
 * </ul>
 * <p>限界ごとに {@link PlaintextSecretTokenGuardConditionTest} に「既知の偽陰性」の fixture と、検出されないことを固定する試験を置く。
 * 将来検出できるようになったら、その試験の期待値を反転する。ソースの字句解析で補う案は棄却済み。</p>
 */
final class PlaintextSecretTokenGuardRules {

    /** 台帳（リポジトリルートからの相対パス）。債務注釈の {@code cmp} はここに登録された未完了の戦役でなければならない。 */
    private static final Path TASK_LIST_RELATIVE = Paths.get("docs", "task-list.md");

    /** 台帳の表の行から ID と状態（第3列）を取り出す。戦役名の列に {@code \|} が含まれても列を取り違えない。 */
    private static final Pattern LEDGER_ROW = Pattern.compile(
            "(?m)^\\|\\s*(CMP-\\d{6}-\\d{4}|CMP-\\d+)\\s*\\|(?:\\\\\\||[^|])*\\|\\s*([^|]*?)\\s*\\|");

    /** 台帳で「完了」を表す状態の接頭辞（完了（日付）・完了(日付) を含む）。 */
    private static final String LEDGER_DONE_PREFIX = "完了";

    /** 台帳の本文（初回に1度だけ読む）。 */
    private static String ledgerContent;

    /** 合言葉を指す語彙（陣立て書 §2-4 番人1）。フィールド名・列名はどちらも snake_case に直してから判定する。 */
    private static final Pattern SECRET_VOCABULARY =
            Pattern.compile("(?i)(token|secret|passcode|invite_code|share_key)");

    /** メソッド名（camelCase）向けの語彙。アンダースコアを含まない表記で判定する。 */
    private static final Pattern SECRET_METHOD_VOCABULARY =
            Pattern.compile("(?i)(token|secret|passcode|invitecode|sharekey)");

    private static final String HASH_COLUMN_SUFFIX = "_hash";

    private static final String HASH_WORD = "hash";

    private static final String ISSUED_TOKEN_CLASS = IssuedToken.class.getName();

    private static final String BEARER_KEY_CLASS = BearerSecretValkeyKey.class.getName();

    /** 派生クエリの {@code By} 以降（プロパティ式）を取り出す。 */
    private static final Pattern DERIVED_QUERY = Pattern.compile(
            "^(?:find|read|get|query|search|stream|count|exists|delete|remove)\\w*?By(?=[A-Z])(.*)$");

    private static final Pattern DERIVED_SEPARATOR = Pattern.compile("And(?=[A-Z])|Or(?=[A-Z])");

    /** JDBC 直接書き込みの SQL 定数。 */
    private static final Pattern RAW_WRITE_SQL = Pattern.compile(
            "(?i)\\binsert\\s+(?:ignore\\s+)?into\\b|\\bupdate\\s+[\\w`.]+(?:\\s+\\w+)?\\s+set\\b");

    private static final String JPA_QUERY_VALUE = "value";

    /**
     * 「どの書込みへ届くか追えない乱数」の印。追えないとき（フィールドの配列へ乱数を詰めた等）は、従来どおり
     * 同居しているだけで token 系の書込みへ届いたものとみなす（見逃すより誤検出に倒す）。
     */
    private static final String ANY_CALL = "*";

    private static final int NO_LOCAL_SLOT = -1;

    private static final String EXEMPTION_REASON_PROP = "reason";

    private static final String EXEMPTION_CMP_PROP = "cmp";

    private PlaintextSecretTokenGuardRules() {
    }

    /**
     * 1つの番人の判定結果。
     *
     * @param inspected  検査対象として拾った要素（番人1: 候補フィールド、番人2: 候補メソッド、番人3・6: 自前乱数を呼ぶメソッド、
     *                   番人4: 検査した Entity・Repository の型）
     * @param exempted   違反の形だが、有効な免除注釈で免除された要素
     * @param violations 違反
     */
    record GuardResult(List<String> inspected, List<String> exempted, List<String> violations) {
    }

    /** 判定中に要素を集める入れ物。 */
    private static final class Collector {
        private final Set<String> inspected = new LinkedHashSet<>();
        private final Set<String> exempted = new LinkedHashSet<>();
        private final Set<String> violations = new LinkedHashSet<>();

        GuardResult toResult() {
            return new GuardResult(List.copyOf(inspected), List.copyOf(exempted), List.copyOf(violations));
        }
    }

    // ---------------------------------------------------------------- 共通

    private static String fieldId(JavaField field) {
        return field.getOwner().getName() + "." + field.getName();
    }

    private static String methodId(JavaMethod method) {
        return method.getOwner().getName() + "#" + method.getName();
    }

    private static boolean isEntityLike(JavaClass type) {
        return type.isAnnotatedWith(Entity.class) || type.isAnnotatedWith(MappedSuperclass.class);
    }

    private static boolean isEntityLikeOrSubclass(JavaClass type) {
        if (isEntityLike(type)) {
            return true;
        }
        return type.getAllRawSuperclasses().stream().anyMatch(PlaintextSecretTokenGuardRules::isEntityLike);
    }

    private static boolean isStringOrChars(JavaClass type) {
        return type.isEquivalentTo(String.class) || type.isEquivalentTo(char[].class);
    }

    private static String toSnakeCase(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }

    /** 免除注釈が有効か。{@code reason} が空白だけ、または {@code cmp} が戦役 ID と違えば無効。 */
    private static boolean hasValidExemption(HasAnnotations<?> element) {
        boolean required = element.tryGetAnnotationOfType(PlaintextSecretRequired.class.getName())
                .flatMap(annotation -> annotation.get(EXEMPTION_REASON_PROP))
                .map(value -> value instanceof String reason && !reason.isBlank())
                .orElse(false);
        boolean debt = element.tryGetAnnotationOfType(PlaintextTokenDebt.class.getName())
                .flatMap(annotation -> annotation.get(EXEMPTION_CMP_PROP))
                .map(value -> value instanceof String cmp && isOpenRegisteredCmp(cmp, ledger()))
                .orElse(false);
        return required || debt;
    }

    /**
     * {@code cmp} が台帳に登録された CMP ID で、状態が完了でないか。
     *
     * @param cmp    債務注釈の {@code cmp}
     * @param ledger {@code docs/task-list.md} の本文
     * @return 登録済みかつ未完了なら true
     */
    static boolean isOpenRegisteredCmp(String cmp, String ledger) {
        Matcher matcher = LEDGER_ROW.matcher(ledger);
        while (matcher.find()) {
            if (matcher.group(1).equals(cmp)) {
                return !matcher.group(2).startsWith(LEDGER_DONE_PREFIX);
            }
        }
        return false;
    }

    private static synchronized String ledger() {
        if (ledgerContent == null) {
            Path path = null;
            for (Path candidate : new Path[]{TASK_LIST_RELATIVE, Paths.get("..").resolve(TASK_LIST_RELATIVE),
                    Paths.get("backend").resolve(TASK_LIST_RELATIVE)}) {
                if (Files.isRegularFile(candidate)) {
                    path = candidate;
                    break;
                }
            }
            if (path == null) {
                throw new IllegalStateException("docs/task-list.md が見つからない（債務注釈の cmp を検証できない）。CWD="
                        + Paths.get("").toAbsolutePath());
            }
            try {
                ledgerContent = Files.readString(path, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("docs/task-list.md を読めません: " + path, e);
            }
        }
        return ledgerContent;
    }

    /** 免除注釈が付いているが無効（cmp 違い・reason 空）か。形に関係なく常に違反として扱う（AC-27）。 */
    private static boolean hasInvalidExemption(HasAnnotations<?> element) {
        boolean present = element.tryGetAnnotationOfType(PlaintextSecretRequired.class.getName()).isPresent()
                || element.tryGetAnnotationOfType(PlaintextTokenDebt.class.getName()).isPresent();
        return present && !hasValidExemption(element);
    }

    /** 永続フィールドの列名。{@code @Column(name)} があればそれ、無ければフィールド名の snake_case。 */
    private static String columnNameOf(JavaField field) {
        return field.tryGetAnnotationOfType(Column.class.getName())
                .flatMap(annotation -> annotation.get("name"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(name -> !name.isBlank())
                .orElseGet(() -> toSnakeCase(field.getName()))
                .toLowerCase();
    }

    private static boolean matchesVocabulary(JavaField field) {
        return SECRET_VOCABULARY.matcher(toSnakeCase(field.getName())).find()
                || SECRET_VOCABULARY.matcher(columnNameOf(field)).find();
    }

    private static boolean isPersistentInstanceField(JavaField field) {
        return !field.getModifiers().contains(JavaModifier.STATIC)
                && !field.isAnnotatedWith(Transient.class)
                && !field.getModifiers().contains(JavaModifier.TRANSIENT);
    }

    /** 番人1の検査対象（語彙一致かつ String/char[] の永続フィールド）。 */
    private static boolean isSecretCandidate(JavaField field) {
        return isPersistentInstanceField(field) && isStringOrChars(field.getRawType()) && matchesVocabulary(field);
    }

    private static boolean isHashColumn(JavaField field) {
        return columnNameOf(field).endsWith(HASH_COLUMN_SUFFIX);
    }

    /** 継承元を含めた Entity のフィールド。 */
    private static List<JavaField> fieldsWithInherited(JavaClass entity) {
        List<JavaField> fields = new ArrayList<>(entity.getFields());
        entity.getAllRawSuperclasses().forEach(superclass -> fields.addAll(superclass.getFields()));
        return fields;
    }

    private static boolean isSelfRandomCall(JavaMethodCall call) {
        String owner = call.getTargetOwner().getName();
        return (owner.equals(UUID.class.getName()) && call.getName().equals("randomUUID"))
                || (owner.equals(SecureRandom.class.getName()) && call.getName().equals("nextBytes"));
    }

    private static boolean callsSelfRandom(JavaMethod method) {
        return method.getMethodCallsFromSelf().stream().anyMatch(PlaintextSecretTokenGuardRules::isSelfRandomCall);
    }

    private static boolean isRepositoryInterface(JavaClass type) {
        return type.isInterface() && type.isAssignableTo(Repository.class) && !type.isEquivalentTo(Repository.class);
    }

    // ---------------------------------------------------------------- 番人1

    /** 番人1: 平文の token 系列。 */
    static GuardResult plaintextColumns(JavaClasses classes) {
        Collector out = new Collector();
        for (JavaClass type : classes) {
            if (!isEntityLike(type)) {
                continue;
            }
            for (JavaField field : type.getFields()) {
                // 免除注釈の検証は列の種類（ハッシュ列・語彙に一致しない列）に関係なく、付いていれば必ず行う
                if (hasInvalidExemption(field)) {
                    out.violations.add(fieldId(field) + " 無効な免除注釈（cmp が台帳に未登録または完了済み、または reason が空）");
                }
                if (!isSecretCandidate(field)) {
                    continue;
                }
                String id = fieldId(field);
                out.inspected.add(id);
                if (isHashColumn(field)) {
                    continue;
                }
                if (hasValidExemption(field)) {
                    out.exempted.add(id);
                } else {
                    out.violations.add(id + " 平文の token 系列（_hash でなく免除注釈も無い）");
                }
            }
        }
        return out.toResult();
    }

    // ---------------------------------------------------------------- 番人2

    /** 番人2: 平文の token 系列を検索する Repository メソッド。 */
    static GuardResult plaintextRepositoryLookups(JavaClasses classes) {
        Collector out = new Collector();
        for (JavaClass repository : classes) {
            if (!isRepositoryInterface(repository)) {
                continue;
            }
            Optional<JavaClass> entity = resolveDomainType(repository, classes);
            for (JavaMethod method : repository.getMethods()) {
                inspectRepositoryMethod(method, entity, out);
            }
        }
        return out.toResult();
    }

    private static Optional<JavaClass> resolveDomainType(JavaClass repository, JavaClasses classes) {
        try {
            Class<?>[] args = GenericTypeResolver.resolveTypeArguments(repository.reflect(), Repository.class);
            if (args != null && args.length > 0 && classes.contain(args[0].getName())) {
                return Optional.of(classes.get(args[0].getName()));
            }
        } catch (RuntimeException | LinkageError e) {
            // 解決できないときは「未解決」として扱い、名前が語彙に一致するメソッドを違反側に倒す
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static void inspectRepositoryMethod(JavaMethod method, Optional<JavaClass> entity, Collector out) {
        String name = method.getName();
        boolean nameCandidate = SECRET_METHOD_VOCABULARY.matcher(name).find()
                && !name.toLowerCase().contains(HASH_WORD);
        String queryBody = queryBodyOf(method);
        boolean queryCandidate = queryBody != null
                && SECRET_METHOD_VOCABULARY.matcher(queryBody.replace("_", "")).find();
        if (!nameCandidate && !queryCandidate) {
            return;
        }
        String id = methodId(method);
        out.inspected.add(id);
        if (entity.isEmpty()) {
            if (nameCandidate) {
                out.violations.add(id + " Entity を解決できない Repository の token 系検索");
            }
            return;
        }
        List<JavaField> entityFields = fieldsWithInherited(entity.get());
        Set<JavaField> matched = new LinkedHashSet<>();
        if (nameCandidate) {
            matched.addAll(derivedQueryFields(name, entityFields));
        }
        if (queryBody != null) {
            matched.addAll(queryBodyFields(queryBody, entityFields));
        }
        matched.removeIf(field -> !isSecretCandidate(field) || isHashColumn(field));
        if (matched.isEmpty()) {
            return;
        }
        boolean allExempt = matched.stream().allMatch(PlaintextSecretTokenGuardRules::hasValidExemption);
        if (allExempt) {
            out.exempted.add(id);
        } else {
            out.violations.add(id + " 平文の token 系列で検索している（対応フィールドに有効な免除注釈が無い）");
        }
    }

    private static String queryBodyOf(JavaMethod method) {
        return method.tryGetAnnotationOfType(Query.class.getName())
                .flatMap(annotation -> annotation.get(JPA_QUERY_VALUE))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .orElse(null);
    }

    /** 派生クエリ名の各プロパティ区間に対応するフィールド（最長一致）。 */
    private static Set<JavaField> derivedQueryFields(String methodName, List<JavaField> entityFields) {
        Set<JavaField> result = new LinkedHashSet<>();
        Matcher matcher = DERIVED_QUERY.matcher(methodName);
        if (!matcher.matches()) {
            return result;
        }
        for (String segment : DERIVED_SEPARATOR.split(matcher.group(1))) {
            JavaField best = null;
            for (JavaField field : entityFields) {
                String property = Character.toUpperCase(field.getName().charAt(0)) + field.getName().substring(1);
                boolean prefix = segment.startsWith(property)
                        && (segment.length() == property.length()
                        || Character.isUpperCase(segment.charAt(property.length())));
                if (prefix && (best == null || property.length() > best.getName().length())) {
                    best = field;
                }
            }
            if (best != null) {
                result.add(best);
            }
        }
        return result;
    }

    /** {@code @Query} 本文が等値・IN・LIKE で比較しているフィールド（フィールド名または列名）。 */
    private static Set<JavaField> queryBodyFields(String body, List<JavaField> entityFields) {
        Set<JavaField> result = new LinkedHashSet<>();
        for (JavaField field : entityFields) {
            for (String candidate : List.of(field.getName(), columnNameOf(field))) {
                Pattern comparison = Pattern.compile(
                        "(?i)(?<![\\w])" + Pattern.quote(candidate) + "\\s*(=|<>|!=|\\bin\\b|\\blike\\b)");
                if (comparison.matcher(body).find()) {
                    result.add(field);
                }
            }
        }
        return result;
    }

    // ---------------------------------------------------------------- 番人3

    /** 番人3: 金庫を通さない自前発行（自前乱数と Entity の token 系 setter/builder の同居）。 */
    static GuardResult selfIssuedTokens(JavaClasses classes) {
        Collector out = new Collector();
        for (JavaClass type : classes) {
            Map<String, Set<String>> taintedCalls = null;
            for (JavaMethod method : type.getMethods()) {
                if (hasInvalidExemption(method)) {
                    out.violations.add(methodId(method) + " 無効な免除注釈（cmp が台帳に未登録または完了済み、または reason が空）");
                }
                if (!callsSelfRandom(method)) {
                    continue;
                }
                String id = methodId(method);
                out.inspected.add(id);
                if (taintedCalls == null) {
                    taintedCalls = loadRandomTaintedCalls(type);
                }
                // 乱数の値が実際に token 系の書込みへ届いているものだけを違反の対象にする（JTI 用の UUID は届かない）
                Set<String> reached = taintedCalls.getOrDefault(method.getName() + method.getDescriptor(), Set.of());
                boolean writesToken = method.getMethodCallsFromSelf().stream()
                        .filter(PlaintextSecretTokenGuardRules::isEntityTokenWriter)
                        .anyMatch(call -> reached.contains(ANY_CALL) || reached.contains(callKey(call)));
                if (!writesToken) {
                    continue;
                }
                if (hasValidExemption(method)) {
                    out.exempted.add(id);
                } else {
                    out.violations.add(id + " 自前乱数で発行した値を Entity の token 系に詰めている（SecretTokenVault を使う）");
                }
            }
        }
        return out.toResult();
    }

    /** 呼び出し先の識別キー（ASM の owner・name と同じ表記にする。descriptor は見ない）。 */
    private static String callKey(JavaMethodCall call) {
        return call.getTargetOwner().getName() + "." + call.getName();
    }

    private static boolean isEntityTokenWriter(JavaMethodCall call) {
        JavaClass owner = call.getTargetOwner();
        boolean entityOwner = isEntityLikeOrSubclass(owner)
                || owner.getEnclosingClass().filter(PlaintextSecretTokenGuardRules::isEntityLikeOrSubclass).isPresent();
        if (!entityOwner) {
            return false;
        }
        String name = call.getName();
        // Hash を含む書込みも対象にする: 乱数 → vault.hash() → setTokenHash() は金庫外の発行（AC-22）
        if (!SECRET_METHOD_VOCABULARY.matcher(name).find()) {
            return false;
        }
        return call.getTarget().getRawParameterTypes().stream()
                .anyMatch(PlaintextSecretTokenGuardRules::isStringOrChars);
    }

    // ---------------------------------------------------------------- 番人4

    /** 番人4: {@code IssuedToken} の永続化（Entity のフィールド型・Repository 引数）。 */
    static GuardResult issuedTokenPersistence(JavaClasses classes) {
        Collector out = new Collector();
        for (JavaClass type : classes) {
            if (isEntityLike(type)) {
                out.inspected.add(type.getName());
                for (JavaField field : type.getFields()) {
                    if (field.getRawType().getName().equals(ISSUED_TOKEN_CLASS)) {
                        out.violations.add(fieldId(field) + " IssuedToken（平文とハッシュの対）を Entity に抱えている");
                    }
                }
            }
            if (isRepositoryInterface(type)) {
                out.inspected.add(type.getName());
                for (JavaMethod method : type.getMethods()) {
                    boolean takesIssued = method.getRawParameterTypes().stream()
                            .anyMatch(parameter -> parameter.getName().equals(ISSUED_TOKEN_CLASS));
                    if (takesIssued) {
                        out.violations.add(methodId(method) + " IssuedToken を永続化層へ渡している");
                    }
                }
            }
        }
        return out.toResult();
    }

    // ---------------------------------------------------------------- 番人6

    /** 番人6: JDBC 直接 INSERT/UPDATE と自前乱数の同居、{@code BearerSecretValkeyKey.of} と自前乱数の同居。 */
    static GuardResult rawWritesAndBearerKeysWithSelfRandom(JavaClasses classes) {
        Collector out = new Collector();
        for (JavaClass type : classes) {
            Map<String, Set<String>> constants = null;
            for (JavaMethod method : type.getMethods()) {
                if (hasInvalidExemption(method)) {
                    out.violations.add(methodId(method) + " 無効な免除注釈（cmp が台帳に未登録または完了済み、または reason が空）");
                }
                if (!callsSelfRandom(method)) {
                    continue;
                }
                String id = methodId(method);
                out.inspected.add(id);
                boolean bearerKey = method.getMethodCallsFromSelf().stream()
                        .anyMatch(call -> call.getTargetOwner().getName().equals(BEARER_KEY_CLASS)
                                && call.getName().equals("of"));
                if (constants == null) {
                    constants = loadStringConstants(type);
                }
                boolean rawWrite = constants.getOrDefault(method.getName() + method.getDescriptor(), Set.of())
                        .stream().anyMatch(constant -> RAW_WRITE_SQL.matcher(constant).find());
                if (!bearerKey && !rawWrite) {
                    continue;
                }
                if (hasValidExemption(method)) {
                    out.exempted.add(id);
                } else {
                    String why = bearerKey
                            ? "BearerSecretValkeyKey.of と自前乱数が同居している（SecretTokenVault で発行しハッシュを渡す）"
                            : "JDBC 直接書き込みと自前乱数が同居している（SecretTokenVault で発行しハッシュを入れる）";
                    out.violations.add(id + " " + why);
                }
            }
        }
        return out.toResult();
    }

    /** クラスファイルから、メソッドごとの文字列定数（LDC と文字列連結のレシピ定数）を取り出す。 */
    private static Map<String, Set<String>> loadStringConstants(JavaClass type) {
        Map<String, Set<String>> result = new HashMap<>();
        if (type.getSource().isEmpty()) {
            return result;
        }
        try (InputStream in = type.getSource().get().getUri().toURL().openStream()) {
            new ClassReader(in).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    Set<String> constants = result.computeIfAbsent(name + descriptor, key -> new HashSet<>());
                    return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                        @Override
                        public void visitLdcInsn(Object value) {
                            if (value instanceof String text) {
                                constants.add(text);
                            }
                        }

                        @Override
                        public void visitInvokeDynamicInsn(String indyName, String indyDescriptor, Handle bootstrap,
                                                           Object... bootstrapArguments) {
                            for (Object argument : bootstrapArguments) {
                                if (argument instanceof String text) {
                                    constants.add(text);
                                }
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new IllegalStateException("クラスファイルを読めません: " + type.getName(), e);
        }
        return result;
    }

    // ---------------------------------------------------------------- 乱数の値の行き先（番人3）

    /**
     * 値の流れの追跡で使う、オペランドスタックの1要素。
     * {@code sources} は、この値が（分岐の合流を含めて）どのローカル変数の値でありうるか（may-alias の元）。
     */
    private record Operand(boolean tainted, boolean wide, Set<Integer> sources) {
        static Operand clean() {
            return new Operand(false, false, Set.of());
        }

        static Operand of(boolean tainted, boolean wide) {
            return new Operand(tainted, wide, Set.of());
        }

        /** 合流点での和集合（印は論理和、元の変数は和集合）。 */
        Operand join(Operand other) {
            if (sources.containsAll(other.sources()) && (tainted || !other.tainted())) {
                return this;
            }
            Set<Integer> merged = new HashSet<>(sources);
            merged.addAll(other.sources());
            return new Operand(tainted || other.tainted(), wide, Set.copyOf(merged));
        }
    }

    /**
     * 値の流れの追跡の抽象状態（あるプログラム点での、オペランドスタック・乱数由来のローカル変数・配列の別名）。
     * 別名は「同じ配列を指しうる」ローカル変数の組（無向の辺）の集合で持つ。
     */
    private static final class TaintState {
        /** 下から上へ並べたオペランドスタック。 */
        private final List<Operand> stack;
        private final Set<Integer> taintedLocals;
        /** 別名の辺（{@link #edge} で符号化した、変数番号の順序なし組）。 */
        private final Set<Long> aliasEdges;

        TaintState() {
            this(new ArrayList<>(), new HashSet<>(), new HashSet<>());
        }

        private TaintState(List<Operand> stack, Set<Integer> taintedLocals, Set<Long> aliasEdges) {
            this.stack = stack;
            this.taintedLocals = taintedLocals;
            this.aliasEdges = aliasEdges;
        }

        TaintState copy() {
            return new TaintState(new ArrayList<>(stack), new HashSet<>(taintedLocals), new HashSet<>(aliasEdges));
        }

        /** 例外ハンドラの入口: ローカル変数の情報は保ち、スタックは投げられた例外1つだけにする。 */
        TaintState handlerEntry() {
            List<Operand> handlerStack = new ArrayList<>();
            handlerStack.add(Operand.clean());
            return new TaintState(handlerStack, new HashSet<>(taintedLocals), new HashSet<>(aliasEdges));
        }

        /**
         * 合流点で {@code other} を和集合として取り込み、状態が増えたかを返す。
         * スタックの深さが違う（検証済みのバイトコードでは起きない）ときはスタックを据え置く。
         */
        boolean joinFrom(TaintState other) {
            boolean changed = taintedLocals.addAll(other.taintedLocals);
            changed |= aliasEdges.addAll(other.aliasEdges);
            if (stack.size() == other.stack.size()) {
                for (int i = 0; i < stack.size(); i++) {
                    Operand merged = stack.get(i).join(other.stack.get(i));
                    if (!merged.equals(stack.get(i))) {
                        stack.set(i, merged);
                        changed = true;
                    }
                }
            }
            return changed;
        }

        /** ローカル変数の情報（印・別名）だけを和集合で取り込む（スタックは触らない）。 */
        void joinLocalsFrom(TaintState other) {
            taintedLocals.addAll(other.taintedLocals);
            aliasEdges.addAll(other.aliasEdges);
        }

        Operand pop() {
            return stack.isEmpty() ? Operand.clean() : stack.remove(stack.size() - 1);
        }

        void push(Operand operand) {
            stack.add(operand);
        }

        private static long edge(int a, int b) {
            return ((long) Math.min(a, b) << Integer.SIZE) | Math.max(a, b);
        }

        private static int low(long key) {
            return (int) (key >>> Integer.SIZE);
        }

        private static int high(long key) {
            return (int) key;
        }

        private Set<Integer> neighbors(int slot) {
            Set<Integer> result = new HashSet<>();
            for (long key : aliasEdges) {
                if (low(key) == slot) {
                    result.add(high(key));
                } else if (high(key) == slot) {
                    result.add(low(key));
                }
            }
            return result;
        }

        /** 変数 {@code slot} と、その別名（辺を辿って届くものすべて）に乱数由来の印を付ける。 */
        void taintSlot(int slot) {
            Set<Integer> seen = new HashSet<>();
            Deque<Integer> work = new ArrayDeque<>();
            work.push(slot);
            while (!work.isEmpty()) {
                int current = work.pop();
                if (seen.add(current)) {
                    taintedLocals.add(current);
                    neighbors(current).forEach(work::push);
                }
            }
        }

        /** 変数 {@code slot} を別名から外す（別の値を代入したとき）。経由していた別名どうしの関係は残す。 */
        void detachAlias(int slot) {
            List<Integer> around = new ArrayList<>(neighbors(slot));
            for (int i = 0; i < around.size(); i++) {
                for (int j = i + 1; j < around.size(); j++) {
                    aliasEdges.add(edge(around.get(i), around.get(j)));
                }
            }
            aliasEdges.removeIf(key -> low(key) == slot || high(key) == slot);
        }

        void joinAlias(int slot, int source) {
            aliasEdges.add(edge(slot, source));
        }
    }

    /**
     * クラスファイルから、メソッドごとに「自前乱数（{@code UUID.randomUUID()}・{@code SecureRandom#nextBytes} の配列）から
     * 導かれた値を引数に受けた呼び出し」を取り出す（キーは {@link #callKey}）。
     *
     * <p>メソッド内の値の流れ追跡（may 解析）で、オペランドスタックとローカル変数に「乱数由来か」の印を持つ。
     * 命令列を基本ブロックに分け、ワークリストで不動点まで反復する。合流点（分岐の合流・ループの先頭・例外ハンドラ）では
     * 流れ込む状態の和集合をとり、ブロックの入口の状態が増えなくなるまで回す。後方ジャンプ（ループ）で次の周回へ持ち越される
     * 乱数・配列の別名も、周回後の状態でループ本体を再評価するので見逃さない。
     * 例外ハンドラの入口には、例外表のエントリが覆う範囲内の各命令の「実行前の状態」の和集合を合流させる
     * （try の途中で例外が起きた時点の状態を取りこぼさない。入れ子の try・finally も同じ規則）。
     * <b>停止性</b>: 状態はローカル変数番号の有限集合の部分集合（印・別名の辺・スタック要素の元の変数）と深さ固定のスタックの組で
     * 格子は有限であり、ブロック入口の状態は和集合でしか更新しない（単調に増える）ので、有限回で不動点に達する。</p>
     *
     * <p>呼び出しの戻り値は、引数かレシーバのどれかが乱数由来なら乱数由来とする（{@code toString}・ハッシュ化・連結を貫通する）。
     * 乱数を詰める配列の別名（{@code byte[] b = a;}）は同じ配列として扱い、片方へ印が付けば他方にも付く。
     * 直線上の代入（ブロック内）は印・別名を上書きし、枝ごとの違いは合流点の和集合で保つ。
     * 追うのはメソッド内のローカル変数の流れだけで、メソッドをまたぐ流れ・フィールド経由・ラムダの本体・リフレクションは
     * 追わない（既知の限界。クラス Javadoc 参照）。
     * 判定が追えない形（配列がローカル変数でない）では {@link #ANY_CALL} を入れ、同居だけで違反とする側に倒す。</p>
     *
     * <p>JTI のように token 系でない名前の書込み（{@code jti}・{@code jwtId} 等）へ UUID を渡すだけの発行は、
     * その呼び出しが {@link #isEntityTokenWriter} に該当しないため、乱数由来と分かっていても違反にならない。
     * 許可リストを別に持たず、書込み先の名前の語彙で切り分けている。</p>
     */
    private static Map<String, Set<String>> loadRandomTaintedCalls(JavaClass type) {
        Map<String, Set<String>> result = new HashMap<>();
        if (type.getSource().isEmpty()) {
            return result;
        }
        try (InputStream in = type.getSource().get().getUri().toURL().openStream()) {
            new ClassReader(in).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                                 String[] exceptions) {
                    return new RandomTaintTracker(result.computeIfAbsent(name + descriptor, key -> new HashSet<>()));
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            throw new IllegalStateException("クラスファイルを読めません: " + type.getName(), e);
        }
        return result;
    }

    /**
     * 1メソッド分の値の流れ追跡（{@link #loadRandomTaintedCalls} の本体）。
     * 訪問中は命令ごとの状態遷移と制御の行き先を記録するだけで、{@link #visitEnd} で基本ブロックに分けて不動点反復する。
     */
    private static final class RandomTaintTracker extends MethodVisitor {
        private static final String UUID_OWNER = UUID.class.getName().replace('.', '/');
        private static final String SECURE_RANDOM_OWNER = SecureRandom.class.getName().replace('.', '/');
        private static final String CONSTRUCTOR = "<init>";

        /** 1命令: 状態遷移、ジャンプ先、次の命令へ落ちるか。 */
        private record Step(Consumer<TaintState> effect, List<Label> targets, boolean fallsThrough) {
            boolean endsBlock() {
                return !fallsThrough || !targets.isEmpty();
            }
        }

        /** 例外ハンドラの範囲（{@code start} 以上 {@code end} 未満の命令が {@code handler} へ飛びうる）。 */
        private record TryCatch(Label start, Label end, Label handler) {
        }

        private final Set<String> reachedCalls;
        private final List<Step> steps = new ArrayList<>();
        /** ラベル → その直後の命令の番号。 */
        private final Map<Label, Integer> labelIndex = new IdentityHashMap<>();
        private final List<TryCatch> tryCatches = new ArrayList<>();

        RandomTaintTracker(Set<String> reachedCalls) {
            super(SpringAsmInfo.ASM_VERSION);
            this.reachedCalls = reachedCalls;
        }

        private void step(Consumer<TaintState> effect) {
            steps.add(new Step(effect, List.of(), true));
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            tryCatches.add(new TryCatch(start, end, handler));
        }

        @Override
        public void visitLabel(Label label) {
            labelIndex.put(label, steps.size());
        }

        @Override
        public void visitEnd() {
            analyze();
        }

        // ------------------------------------------------------------ 基本ブロックと不動点反復

        private void analyze() {
            int count = steps.size();
            if (count == 0) {
                return;
            }
            TreeSet<Integer> leaders = new TreeSet<>();
            leaders.add(0);
            labelIndex.values().stream().filter(index -> index < count).forEach(leaders::add);
            for (int i = 0; i + 1 < count; i++) {
                if (steps.get(i).endsBlock()) {
                    leaders.add(i + 1);
                }
            }
            int[] starts = leaders.stream().mapToInt(Integer::intValue).toArray();
            Map<Integer, Integer> blockAt = new HashMap<>();
            for (int b = 0; b < starts.length; b++) {
                blockAt.put(starts[b], b);
            }
            List<List<Integer>> successors = new ArrayList<>();
            List<List<Integer>> handlers = new ArrayList<>();
            for (int b = 0; b < starts.length; b++) {
                Step last = steps.get(blockEnd(starts, b, count) - 1);
                List<Integer> next = new ArrayList<>();
                for (Label target : last.targets()) {
                    blockOf(target, blockAt).ifPresent(next::add);
                }
                if (last.fallsThrough() && b + 1 < starts.length) {
                    next.add(b + 1);
                }
                successors.add(next);
                List<Integer> covering = new ArrayList<>();
                for (TryCatch tryCatch : tryCatches) {
                    Integer from = labelIndex.get(tryCatch.start());
                    Integer to = labelIndex.get(tryCatch.end());
                    if (from != null && to != null && starts[b] >= from && starts[b] < to) {
                        blockOf(tryCatch.handler(), blockAt).ifPresent(covering::add);
                    }
                }
                handlers.add(covering);
            }

            TaintState[] entry = new TaintState[starts.length];
            entry[0] = new TaintState();
            Deque<Integer> work = new ArrayDeque<>();
            boolean[] queued = new boolean[starts.length];
            work.add(0);
            queued[0] = true;
            while (!work.isEmpty()) {
                int b = work.poll();
                queued[b] = false;
                TaintState state = entry[b].copy();
                // 例外辺: try 範囲のどの命令でも例外は起きうるので、範囲内の各命令の「実行前の状態」の和集合を
                // ハンドラの入口へ合流させる（入れ子の try・finally も例外表のエントリごとに同じ規則）
                TaintState toHandlers = handlers.get(b).isEmpty() ? null : state.handlerEntry();
                for (int i = starts[b]; i < blockEnd(starts, b, count); i++) {
                    if (toHandlers != null) {
                        toHandlers.joinLocalsFrom(state);
                    }
                    steps.get(i).effect().accept(state);
                }
                for (int next : successors.get(b)) {
                    if (flowInto(entry, next, state)) {
                        enqueue(work, queued, next);
                    }
                }
                for (int handler : handlers.get(b)) {
                    if (flowInto(entry, handler, toHandlers)) {
                        enqueue(work, queued, handler);
                    }
                }
            }
        }

        private static int blockEnd(int[] starts, int block, int count) {
            return block + 1 < starts.length ? starts[block + 1] : count;
        }

        private Optional<Integer> blockOf(Label label, Map<Integer, Integer> blockAt) {
            Integer index = labelIndex.get(label);
            return index == null ? Optional.empty() : Optional.ofNullable(blockAt.get(index));
        }

        /** ブロック {@code block} の入口へ {@code state} を和集合で流し込み、入口の状態が増えたかを返す。 */
        private static boolean flowInto(TaintState[] entry, int block, TaintState state) {
            if (entry[block] == null) {
                entry[block] = state.copy();
                return true;
            }
            return entry[block].joinFrom(state);
        }

        private static void enqueue(Deque<Integer> work, boolean[] queued, int block) {
            if (!queued[block]) {
                queued[block] = true;
                work.add(block);
            }
        }

        // ------------------------------------------------------------ 命令ごとの状態遷移

        private static void pushResult(TaintState state, boolean tainted, Type type) {
            if (type.getSort() != Type.VOID) {
                state.push(Operand.of(tainted, type.getSize() == 2));
            }
        }

        @Override
        public void visitInsn(int opcode) {
            switch (opcode) {
                case Opcodes.ATHROW, Opcodes.RETURN, Opcodes.IRETURN, Opcodes.LRETURN, Opcodes.FRETURN,
                     Opcodes.DRETURN, Opcodes.ARETURN ->
                        steps.add(new Step(state -> state.stack.clear(), List.of(), false));
                default -> step(state -> applyInsn(state, opcode));
            }
        }

        private static void applyInsn(TaintState state, int opcode) {
            switch (opcode) {
                case Opcodes.ACONST_NULL, Opcodes.ICONST_M1, Opcodes.ICONST_0, Opcodes.ICONST_1, Opcodes.ICONST_2,
                     Opcodes.ICONST_3, Opcodes.ICONST_4, Opcodes.ICONST_5, Opcodes.FCONST_0, Opcodes.FCONST_1,
                     Opcodes.FCONST_2 -> state.push(Operand.clean());
                case Opcodes.LCONST_0, Opcodes.LCONST_1, Opcodes.DCONST_0, Opcodes.DCONST_1 ->
                        state.push(Operand.of(false, true));
                case Opcodes.IALOAD, Opcodes.FALOAD, Opcodes.AALOAD, Opcodes.BALOAD, Opcodes.CALOAD,
                     Opcodes.SALOAD, Opcodes.LALOAD, Opcodes.DALOAD -> {
                    state.pop();
                    Operand array = state.pop();
                    state.push(Operand.of(array.tainted(), opcode == Opcodes.LALOAD || opcode == Opcodes.DALOAD));
                }
                case Opcodes.IASTORE, Opcodes.FASTORE, Opcodes.AASTORE, Opcodes.BASTORE, Opcodes.CASTORE,
                     Opcodes.SASTORE, Opcodes.LASTORE, Opcodes.DASTORE -> {
                    Operand value = state.pop();
                    state.pop();
                    Operand array = state.pop();
                    if (value.tainted()) {
                        array.sources().forEach(state::taintSlot);
                    }
                }
                case Opcodes.POP, Opcodes.MONITORENTER, Opcodes.MONITOREXIT -> state.pop();
                case Opcodes.POP2 -> {
                    if (!state.pop().wide()) {
                        state.pop();
                    }
                }
                case Opcodes.DUP -> {
                    Operand top = state.pop();
                    state.push(top);
                    state.push(top);
                }
                case Opcodes.DUP_X1, Opcodes.DUP_X2 -> {
                    Operand top = state.pop();
                    Operand second = state.pop();
                    Operand third = opcode == Opcodes.DUP_X2 && !second.wide() ? state.pop() : null;
                    state.push(top);
                    if (third != null) {
                        state.push(third);
                    }
                    state.push(second);
                    state.push(top);
                }
                case Opcodes.DUP2, Opcodes.DUP2_X1, Opcodes.DUP2_X2 -> {
                    Operand top = state.pop();
                    if (top.wide()) {
                        state.push(top);
                        state.push(top);
                    } else {
                        Operand second = state.pop();
                        state.push(second);
                        state.push(top);
                        state.push(second);
                        state.push(top);
                    }
                }
                case Opcodes.SWAP -> {
                    Operand top = state.pop();
                    Operand second = state.pop();
                    state.push(top);
                    state.push(second);
                }
                case Opcodes.INEG, Opcodes.LNEG, Opcodes.FNEG, Opcodes.DNEG, Opcodes.ARRAYLENGTH -> {
                    Operand value = state.pop();
                    state.push(Operand.of(value.tainted(), value.wide()));
                }
                default -> applyArithmetic(state, opcode);
            }
        }

        /** 四則・ビット演算・型変換・比較。値は2つ取って1つ返す（型変換は1つ取って1つ返す）。 */
        private static void applyArithmetic(TaintState state, int opcode) {
            boolean conversion = opcode >= Opcodes.I2L && opcode <= Opcodes.I2S;
            boolean binary = (opcode >= Opcodes.IADD && opcode <= Opcodes.DREM)
                    || (opcode >= Opcodes.ISHL && opcode <= Opcodes.LXOR)
                    || (opcode >= Opcodes.LCMP && opcode <= Opcodes.DCMPG);
            if (conversion) {
                Operand value = state.pop();
                state.push(Operand.of(value.tainted(), false));
            } else if (binary) {
                Operand right = state.pop();
                Operand left = state.pop();
                state.push(Operand.of(left.tainted() || right.tainted(), false));
            }
        }

        @Override
        public void visitIntInsn(int opcode, int operand) {
            step(state -> {
                if (opcode == Opcodes.NEWARRAY) {
                    state.pop();
                }
                state.push(Operand.clean());
            });
        }

        @Override
        public void visitVarInsn(int opcode, int varIndex) {
            step(state -> applyVarInsn(state, opcode, varIndex));
        }

        private static void applyVarInsn(TaintState state, int opcode, int varIndex) {
            if (opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE) {
                Operand value = state.pop();
                // 直線上の代入は別名を外す。ただし値が自分自身の変数を含む合流（b = flag ? b : new ...）のときは、
                // 合流前の別名でありうるので関係を保つ（may-alias）
                if (!value.sources().contains(varIndex)) {
                    state.detachAlias(varIndex);
                }
                if (opcode == Opcodes.ASTORE) {
                    for (int source : value.sources()) {
                        if (source != varIndex) {
                            state.joinAlias(varIndex, source);
                        }
                    }
                }
                if (value.tainted()) {
                    state.taintSlot(varIndex);
                } else {
                    // javac はスコープの終わった変数の番号を使い回すので、きれいな値の代入では印を外す
                    // （別の枝の乱数由来の値は、合流点の和集合で残る）
                    state.taintedLocals.remove(varIndex);
                }
            } else if (opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD) {
                boolean wide = opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD;
                state.push(new Operand(state.taintedLocals.contains(varIndex), wide, Set.of(varIndex)));
            }
        }

        @Override
        public void visitTypeInsn(int opcode, String typeName) {
            step(state -> {
                switch (opcode) {
                    case Opcodes.NEW -> state.push(Operand.clean());
                    case Opcodes.INSTANCEOF -> {
                        state.pop();
                        state.push(Operand.clean());
                    }
                    default -> {
                        // ANEWARRAY は長さを取って配列を返し、CHECKCAST は値をそのまま通す
                        Operand value = state.pop();
                        state.push(opcode == Opcodes.ANEWARRAY ? Operand.clean() : value);
                    }
                }
            });
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            Type type = Type.getType(descriptor);
            step(state -> {
                switch (opcode) {
                    case Opcodes.GETSTATIC -> pushResult(state, false, type);
                    case Opcodes.GETFIELD -> {
                        state.pop();
                        pushResult(state, false, type);
                    }
                    case Opcodes.PUTSTATIC -> state.pop();
                    default -> {
                        state.pop();
                        state.pop();
                    }
                }
            });
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
            step(state -> applyMethodInsn(state, opcode, owner, name, descriptor));
        }

        private void applyMethodInsn(TaintState state, int opcode, String owner, String name, String descriptor) {
            Type[] arguments = Type.getArgumentTypes(descriptor);
            Operand[] popped = new Operand[arguments.length];
            boolean argumentTainted = false;
            for (int i = arguments.length - 1; i >= 0; i--) {
                popped[i] = state.pop();
                argumentTainted |= popped[i].tainted();
            }
            Operand receiver = opcode == Opcodes.INVOKESTATIC ? Operand.clean() : state.pop();
            Type returnType = Type.getReturnType(descriptor);
            if (argumentTainted) {
                reachedCalls.add(owner.replace('/', '.') + "." + name);
            }
            if (owner.equals(UUID_OWNER) && name.equals("randomUUID")) {
                pushResult(state, true, returnType);
            } else if (owner.equals(SECURE_RANDOM_OWNER) && name.equals("nextBytes")) {
                markArrayArgumentsTainted(state, popped);
            } else if (name.equals(CONSTRUCTOR)) {
                // new X(args) は NEW と DUP で積んだ2つのうち DUP 側が残っている。乱数由来の引数は残る側へ移す
                if (argumentTainted && !state.stack.isEmpty()) {
                    Operand created = state.pop();
                    state.push(Operand.of(true, created.wide()));
                }
            } else {
                pushResult(state, argumentTainted || receiver.tainted(), returnType);
            }
        }

        private void markArrayArgumentsTainted(TaintState state, Operand[] arguments) {
            for (Operand argument : arguments) {
                if (argument.sources().isEmpty()) {
                    reachedCalls.add(ANY_CALL);
                } else {
                    argument.sources().forEach(state::taintSlot);
                }
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrap,
                                           Object... bootstrapArguments) {
            int argumentCount = Type.getArgumentTypes(descriptor).length;
            Type returnType = Type.getReturnType(descriptor);
            step(state -> {
                boolean tainted = false;
                for (int i = 0; i < argumentCount; i++) {
                    tainted |= state.pop().tainted();
                }
                pushResult(state, tainted, returnType);
            });
        }

        @Override
        public void visitJumpInsn(int opcode, Label label) {
            boolean twoOperands = opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE;
            boolean oneOperand = (opcode >= Opcodes.IFEQ && opcode <= Opcodes.IFLE)
                    || opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL;
            int operands = twoOperands ? 2 : oneOperand ? 1 : 0;
            steps.add(new Step(state -> {
                for (int i = 0; i < operands; i++) {
                    state.pop();
                }
            }, List.of(label), opcode != Opcodes.GOTO));
        }

        @Override
        public void visitLdcInsn(Object value) {
            boolean wide = value instanceof Long || value instanceof Double;
            step(state -> state.push(Operand.of(false, wide)));
        }

        @Override
        public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) {
            addSwitch(dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) {
            addSwitch(dflt, labels);
        }

        private void addSwitch(Label dflt, Label[] labels) {
            List<Label> targets = new ArrayList<>(List.of(labels));
            targets.add(dflt);
            steps.add(new Step(TaintState::pop, List.copyOf(targets), false));
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            step(state -> {
                for (int i = 0; i < numDimensions; i++) {
                    state.pop();
                }
                state.push(Operand.clean());
            });
        }
    }
}
