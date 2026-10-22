package com.mannschaft.app.common;

import com.mannschaft.app.family.CareCategory;
import com.mannschaft.app.family.CareLinkInvitedBy;
import com.mannschaft.app.family.CareLinkStatus;
import com.mannschaft.app.family.entity.UserCareLinkEntity;
import com.mannschaft.app.family.repository.UserCareLinkRepository;
import com.mannschaft.app.gdpr.GdprErrorCode;
import com.mannschaft.app.support.test.AbstractMySqlIntegrationTest;
import com.mannschaft.app.support.test.MembershipTestHelper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.transaction.annotation.Transactional;

import static com.mannschaft.app.common.AccessControlServiceSpiContractTest.CARE_LINK_QUERY;
import static com.mannschaft.app.common.AccessControlServiceSpiContractTest.IMPLEMENTATION;
import static com.mannschaft.app.common.AccessControlServiceSpiContractTest.WITHDRAWAL_ERROR;
import static com.mannschaft.app.common.AccessControlServiceSpiContractTest.requiredClass;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** L1-A AC3/7/8/9: 実認可Bean・実adapter・実MySQLでSPI移設の契約を検査する。 */
@Transactional
@EnabledIf("com.mannschaft.app.support.test.AbstractMySqlIntegrationTest#isDockerAvailable")
class AccessControlServiceSpiIntegrationTest extends AbstractMySqlIntegrationTest {
    private static final long WATCHER = 4022091401L;
    private static final long RECIPIENT = 4022091402L;

    @Autowired private ApplicationContext application;
    @Autowired private AccessControlService accessControl;
    @Autowired private UserCareLinkRepository careLinks;
    @PersistenceContext private EntityManager em;

    @Test
    void Bean名と型注入とSpEL参照は唯一のrole実装を指す() {
        assertThat(application.getBeansOfType(AccessControlService.class))
                .containsOnlyKeys("accessControlService");
        assertThat(application.getBean("accessControlService")).isSameAs(accessControl);
        assertThat(AopUtils.getTargetClass(accessControl)).isEqualTo(requiredClass(IMPLEMENTATION));
        var evaluation = new StandardEvaluationContext();
        evaluation.setBeanResolver(new BeanFactoryResolver(application));
        assertThat(new SpelExpressionParser().parseExpression("@accessControlService")
                .getValue(evaluation)).isSameAs(accessControl);
    }

    @ParameterizedTest
    @EnumSource(CareLinkStatus.class)
    void ケア照会はACTIVEだけ許可し見守り者と対象者を取り違えない(CareLinkStatus status) throws Exception {
        MembershipTestHelper.insertActiveUser(em, WATCHER);
        MembershipTestHelper.insertActiveUser(em, RECIPIENT);
        careLinks.saveAndFlush(UserCareLinkEntity.builder()
                .watcherUserId(WATCHER).careRecipientUserId(RECIPIENT)
                .careCategory(CareCategory.MINOR).status(status)
                .invitedBy(CareLinkInvitedBy.WATCHER).createdBy(WATCHER).build());
        Object adapter = application.getBean(requiredClass(CARE_LINK_QUERY));
        var query = requiredClass(CARE_LINK_QUERY).getMethod("hasActiveCareLink", Long.class, Long.class);
        boolean active = status == CareLinkStatus.ACTIVE;
        assertThat(query.invoke(adapter, WATCHER, RECIPIENT)).isEqualTo(active);
        assertThat(query.invoke(adapter, RECIPIENT, WATCHER)).isEqualTo(false);
        assertThat(query.invoke(adapter, null, RECIPIENT)).isEqualTo(false);
        assertThat(query.invoke(adapter, WATCHER, null)).isEqualTo(false);
        assertThat(query.invoke(adapter, null, null)).isEqualTo(false);
        assertThat(accessControl.hasActiveCareLink(WATCHER, RECIPIENT)).isEqualTo(active);
        assertThat(accessControl.hasActiveCareLink(RECIPIENT, WATCHER)).isFalse();
        assertThat(accessControl.hasActiveCareLink(null, RECIPIENT)).isFalse();
        assertThat(accessControl.hasActiveCareLink(WATCHER, null)).isFalse();
        assertThat(accessControl.hasActiveCareLink(null, null)).isFalse();
        if (active) {
            assertThatCode(() -> accessControl.checkCareLink(WATCHER, RECIPIENT)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> accessControl.checkCareLink(WATCHER, RECIPIENT))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isSameAs(CommonErrorCode.COMMON_002));
        }
    }

    @Test
    void GDPRadapterは既存の退会エラーそのものをWARNで返す() throws Exception {
        Class<?> contract = requiredClass(WITHDRAWAL_ERROR);
        Object provider = application.getBean(contract);
        assertThat(contract.getMethod("lastAdminWithdrawalError").invoke(provider))
                .isSameAs(GdprErrorCode.GDPR_006);
        assertThat(GdprErrorCode.GDPR_006.getSeverity()).isEqualTo(ErrorCode.Severity.WARN);
        assertThat(GdprErrorCode.GDPR_006.getMessage()).isEqualTo("管理者権限の移譲が必要です");
    }

    static Stream<Arguments> spiCounts() {
        return Stream.of(CARE_LINK_QUERY, WITHDRAWAL_ERROR).flatMap(contract -> Stream.of(
                Arguments.of(contract, 0, false), Arguments.of(contract, 1, false),
                Arguments.of(contract, 2, false), Arguments.of(contract, 2, true)));
    }

    @ParameterizedTest
    @MethodSource("spiCounts")
    void SPIは各一実装だけ許しPrimaryで複数を隠しても起動を拒否する(
            String variedContract, int count, boolean primary) {
        Class<?> implementation = requiredClass(IMPLEMENTATION);
        assertThat(implementation.getConstructors()).hasSize(1);
        var constructor = implementation.getConstructors()[0];
        try (var isolated = new AnnotationConfigApplicationContext()) {
            // parentを付けずSPI候補を隔離する。実協力者は外部所有singletonとして参照だけ登録し、
            // destroy callbackは登録しないため、このcontextのcloseで共有実Beanを破棄しない。
            for (var parameter : constructor.getParameters()) {
                if (parameter.getType() == List.class) {
                    assertThat(parameter.getParameterizedType()).isInstanceOf(ParameterizedType.class);
                    String argument = ((ParameterizedType) parameter.getParameterizedType())
                            .getActualTypeArguments()[0].getTypeName();
                    assertThat(argument).isIn(CARE_LINK_QUERY, WITHDRAWAL_ERROR);
                } else {
                    Object collaborator = application.getBean(parameter.getType());
                    isolated.getBeanFactory().registerSingleton(parameter.getType().getName(), collaborator);
                }
            }
            registerSpi(isolated, CARE_LINK_QUERY, CARE_LINK_QUERY.equals(variedContract) ? count : 1,
                    CARE_LINK_QUERY.equals(variedContract) && primary);
            registerSpi(isolated, WITHDRAWAL_ERROR, WITHDRAWAL_ERROR.equals(variedContract) ? count : 1,
                    WITHDRAWAL_ERROR.equals(variedContract) && primary);
            isolated.registerBeanDefinition("accessControlService", new RootBeanDefinition(implementation));
            if (count == 1) {
                assertThatCode(isolated::refresh).doesNotThrowAnyException();
                assertThat(isolated.getBean(AccessControlService.class)).isInstanceOf(implementation);
            } else {
                assertThatThrownBy(isolated::refresh).isInstanceOfSatisfying(BeanCreationException.class, error -> {
                    Throwable reason = error.getMostSpecificCause();
                    assertThat(reason).isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
                    assertThat(reason.getMessage()).contains(requiredClass(variedContract).getSimpleName());
                });
            }
        }
    }

    private static void registerSpi(AnnotationConfigApplicationContext context, String name,
                                   int count, boolean primary) {
        Class<?> contract = requiredClass(name);
        for (int index = 0; index < count; index++) {
            var definition = new RootBeanDefinition(contract);
            definition.setPrimary(primary && index == 0);
            definition.setInstanceSupplier(() -> Proxy.newProxyInstance(contract.getClassLoader(),
                    new Class<?>[] {contract}, (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "toString" -> contract.getSimpleName() + "個数検査専用";
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "equals" -> proxy == arguments[0];
                                default -> throw new AssertionError(method);
                            };
                        }
                        throw new AssertionError("個数の起動検査中にSPI業務処理を呼んではならない: " + method);
                    }));
            context.registerBeanDefinition(name + index, definition);
        }
    }
}
