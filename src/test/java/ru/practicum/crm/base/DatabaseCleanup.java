package ru.practicum.crm.base;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import java.util.List;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DatabaseCleanup implements InitializingBean {

    @PersistenceContext
    private EntityManager entityManager;

    private List<String> tableNames;

    @Override
    public void afterPropertiesSet() {
        tableNames = entityManager.getMetamodel().getEntities().stream()
                .map(this::getTableName)
                .toList();
    }

    private String getTableName(EntityType<?> entity) {
        Class<?> javaType = entity.getJavaType();
        if (javaType != null) {
            Table annotation = javaType.getAnnotation(Table.class);
            if (annotation != null && !annotation.name().isBlank()) {
                return annotation.name();
            }

            return transformName(javaType.getSimpleName());
        }

        return transformName(entity.getName());
    }

    private String transformName(String name) {
        return name.replaceAll("(?<!^)(?=[A-Z])", "_").toLowerCase();
    }

    @Transactional
    public void cleanup() {
        if (!tableNames.isEmpty()) {
            entityManager.flush();
            entityManager.createNativeQuery("SET CONSTRAINTS ALL DEFERRED").executeUpdate();
            for (String tableName : tableNames) {
                String sql = "TRUNCATE TABLE %s RESTART IDENTITY CASCADE".formatted(tableName);
                entityManager.createNativeQuery(sql).executeUpdate();
            }
        }
    }
}
