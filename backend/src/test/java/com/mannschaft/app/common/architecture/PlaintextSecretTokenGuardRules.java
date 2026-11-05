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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
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
 * ASM 標準の {@code Analyzer}（asm-analysis）に印の集合を持つ値を載せた may 解析で、合流は和集合（{@link PlaintextSecretRandomFlowAnalyzer}）。</p>
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
 * <p><b>既知の偽陽性（保守的近似。2026-11-05 殿裁定で許容）</b>: 配列の別名を同値類で近似するため、共通の格納先
 * （外側の配列）へ入れた別々の配列が同じ類になり、乱数を受けていない配列から token 系へ詰めた値も検出する
 * （{@link PlaintextSecretRandomFlowAnalyzer} 参照。検出を固定する fixture あり）。誤検出したときは配列を共通の格納先に
 * 入れずに分けるか、{@code @PlaintextTokenDebt}（台帳登録済みの未完了 CMP）を付ける。</p>
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
    private static final String ANY_CALL = PlaintextSecretRandomFlowAnalyzer.ANY_CALL;

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
     * クラスファイルから、メソッドごとに「自前乱数（{@code UUID.randomUUID()}・{@code SecureRandom#nextBytes} の配列）から
     * 導かれた値を引数に受けた呼び出し」を取り出す（キーは {@link #callKey}）。
     *
     * <p>解析の本体は {@link PlaintextSecretRandomFlowAnalyzer}（ASM 標準の {@code Analyzer} に、乱数由来の印を持つ値と
     * 合流＝和集合の {@code Interpreter} を載せた may 解析）。スタックと局所変数の意味・カテゴリ2の値・例外ハンドラ・不動点は
     * ASM 標準に任せ、自前では書かない。追うのはメソッド内のローカル変数の流れだけで、メソッドをまたぐ流れ・フィールド経由・
     * ラムダの本体・リフレクションは追わない（既知の限界。クラス Javadoc 参照）。
     * 判定が追えない形（作った場所が分からない配列への {@code nextBytes}）では {@link #ANY_CALL} を入れ、同居だけで違反とする側に倒す。</p>
     *
     * <p>JTI のように token 系でない名前の書込み（{@code jti}・{@code jwtId} 等）へ UUID を渡すだけの発行は、
     * その呼び出しが {@link #isEntityTokenWriter} に該当しないため、乱数由来と分かっていても違反にならない。
     * 許可リストを別に持たず、書込み先の名前の語彙で切り分けている。</p>
     */
    private static Map<String, Set<String>> loadRandomTaintedCalls(JavaClass type) {
        if (type.getSource().isEmpty()) {
            return new HashMap<>();
        }
        try (InputStream in = type.getSource().get().getUri().toURL().openStream()) {
            return PlaintextSecretRandomFlowAnalyzer.analyze(in);
        } catch (IOException e) {
            throw new IllegalStateException("クラスファイルを読めません: " + type.getName(), e);
        }
    }
}
