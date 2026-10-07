package ru.practicum.crm.common.error;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Какое ограничение базы нарушила операция — чтобы сервис превратил отказ базы в понятную
 * ошибку: повтор — в {@code ALREADY_EXISTS}, чужой справочник — в ошибку поля.
 *
 * <p>Имя берётся из {@link ConstraintViolationException} Hibernate: Spring кладёт его причиной в
 * {@link DataIntegrityViolationException}. Hibernate достаёт имя по коду ошибки базы (для
 * PostgreSQL — по шаблону сообщения вида {@code violates unique constraint "…"}), поэтому
 * сравнивается точное имя, а не кусок текста ошибки.
 */
public final class ConstraintViolations {

    private ConstraintViolations() {
    }

    /**
     * Имя нарушенного ограничения.
     *
     * @return {@code null}, если отказ не связан с ограничением или имя определить не удалось
     */
    public static String nameOf(DataIntegrityViolationException ex) {
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
