package ru.practicum.crm.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import ru.practicum.crm.security.api.PermissionCode;

/**
 * Правила проверки доступа (T-030): доступ решает гранулярное право, а не название роли.
 *
 * <p>Проверяется только рабочий код: в тестах роли встречаются законно — например, в
 * {@code @WithMockUser}.
 */
@AnalyzeClasses(packages = "ru.practicum.crm", importOptions = ImportOption.DoNotIncludeTests.class)
public class AccessCheckRulesTest {

    private static final Set<String> ROLE_CHECKS = Set.of("hasRole", "hasAnyRole");
    private static final String SECURED = "org.springframework.security.access.annotation.Secured";
    private static final String ROLES_ALLOWED = "jakarta.annotation.security.RolesAllowed";

    /** {@code hasAuthority('КОД')} или {@code hasAnyAuthority('КОД', 'КОД', …)} — и только. */
    private static final Pattern PERMISSION_EXPRESSION = Pattern.compile(
            "hasAuthority\\('[A-Z_]+'\\)|hasAnyAuthority\\('[A-Z_]+'(, '[A-Z_]+')*\\)");

    private static final Pattern PERMISSION_CODE = Pattern.compile("'([A-Z_]+)'");

    private static final Set<String> KNOWN_PERMISSIONS = Arrays.stream(PermissionCode.values())
            .map(Enum::name)
            .collect(Collectors.toUnmodifiableSet());

    @ArchTest
    static final ArchRule no_role_checks_in_security_configuration = noClasses()
            .should(callRoleCheck())
            .because("доступ проверяется по праву, а не по названию роли: роль — набор прав,"
                    + " который меняется данными, без изменения кода (ТЗ, T-030)");

    @ArchTest
    static final ArchRule no_role_annotations_on_methods = noMethods()
            .should().beAnnotatedWith(SECURED)
            .orShould().beAnnotatedWith(ROLES_ALLOWED)
            .because("@Secured и @RolesAllowed проверяют роли; право проверяет @PreAuthorize"
                    + " с hasAuthority");

    @ArchTest
    static final ArchRule no_role_annotations_on_classes = noClasses()
            .should().beAnnotatedWith(SECURED)
            .orShould().beAnnotatedWith(ROLES_ALLOWED)
            .because("@Secured и @RolesAllowed проверяют роли; право проверяет @PreAuthorize"
                    + " с hasAuthority");

    @ArchTest
    static final ArchRule pre_authorize_checks_known_permissions_only = classes()
            .should(checkOnlyKnownPermissionsInPreAuthorize())
            .because("в @PreAuthorize — только hasAuthority или hasAnyAuthority с кодом права из"
                    + " PermissionCode: опечатка в коде молча закрыла бы операцию для всех");

    private static ArchCondition<JavaClass> callRoleCheck() {
        return new ArchCondition<>("call hasRole or hasAnyRole of Spring Security") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                for (JavaMethodCall call : javaClass.getMethodCallsFromSelf()) {
                    boolean roleCheck = ROLE_CHECKS.contains(call.getTarget().getName())
                            && call.getTargetOwner().getPackageName()
                                    .startsWith("org.springframework.security");
                    if (roleCheck) {
                        events.add(SimpleConditionEvent.satisfied(call, call.getDescription()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> checkOnlyKnownPermissionsInPreAuthorize() {
        return new ArchCondition<>("use @PreAuthorize with known permissions only") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                checkExpression(javaClass.tryGetAnnotationOfType(PreAuthorize.class),
                        javaClass.getName(), javaClass, events);
                javaClass.getMethods().forEach(method -> checkExpression(
                        method.tryGetAnnotationOfType(PreAuthorize.class),
                        method.getFullName(), method, events));
            }
        };
    }

    private static void checkExpression(Optional<PreAuthorize> annotation, String where,
            Object owner, ConditionEvents events) {
        if (annotation.isEmpty()) {
            return;
        }
        String expression = annotation.get().value();
        if (!PERMISSION_EXPRESSION.matcher(expression).matches()) {
            events.add(SimpleConditionEvent.violated(owner, where + ": в @PreAuthorize \""
                    + expression + "\" — ожидается hasAuthority или hasAnyAuthority с кодом"
                    + " права"));
            return;
        }
        Matcher codes = PERMISSION_CODE.matcher(expression);
        while (codes.find()) {
            if (!KNOWN_PERMISSIONS.contains(codes.group(1))) {
                events.add(SimpleConditionEvent.violated(owner, where + ": права "
                        + codes.group(1) + " нет в PermissionCode"));
            }
        }
    }
}
