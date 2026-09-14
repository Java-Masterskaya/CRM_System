package ru.practicum.crm.base;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.EntityType;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class DatabaseCleanup implements InitializingBean {

    @PersistenceContext
    private EntityManager entityManager;

    private List<String> tableNames;

    @Override
    public void afterPropertiesSet() {
        tableNames = entityManager.getMetamodel().getEntities().stream()
                .filter(entity -> entity.getJavaType().isAnnotationPresent(Table.class))
                .map(this::getTableName)
                .toList();
    }

    private String getTableName(EntityType<?> entity) {
        Class<?> javaType = entity.getJavaType();
        if (javaType.isAnnotationPresent(Table.class)) {
            String tableName = javaType.getAnnotation(Table.class).name();
            if (!tableName.isBlank()) {
                return tableName;
            }
        }

        return entity.getName().toLowerCase();
    }

    @Transactional
    public void cleanup() {
        if (!tableNames.isEmpty()) {
            entityManager.flush();
            entityManager.createNativeQuery("SET CONSTRAINTS ALL DEFERRED").executeUpdate();
            for (String tableName : tableNames) {
                entityManager.createNativeQuery("TRUNCATE TABLE %s RESTART IDENTITY CASCADE".formatted(tableName)).executeUpdate();
            }
        }
    }
}
