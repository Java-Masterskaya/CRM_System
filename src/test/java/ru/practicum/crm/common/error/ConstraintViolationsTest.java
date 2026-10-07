package ru.practicum.crm.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.PersistenceException;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class ConstraintViolationsTest {

    private static final ConstraintViolationException UNIQUE_VIOLATION =
            new ConstraintViolationException("could not execute statement",
                    new SQLException("ERROR: duplicate key value violates unique constraint"
                            + " \"holidays_date_unique\""),
                    "holidays_date_unique");

    /** Так отказ базы приходит из Spring Data: исключение Hibernate — прямая причина. */
    @Test
    void nameOf_whenHibernateViolationIsCause_returnsItsConstraintName() {
        assertThat(ConstraintViolations.nameOf(new DataIntegrityViolationException(
                "could not execute statement", UNIQUE_VIOLATION)))
                .isEqualTo("holidays_date_unique");
    }

    @Test
    void nameOf_whenHibernateViolationIsDeeperInChain_findsIt() {
        assertThat(ConstraintViolations.nameOf(new DataIntegrityViolationException(
                "could not execute statement",
                new PersistenceException("flush failed", UNIQUE_VIOLATION))))
                .isEqualTo("holidays_date_unique");
    }

    /** Имя не ищется в тексте: без исключения Hibernate ответ — «не знаю». */
    @Test
    void nameOf_whenNoHibernateViolation_returnsNullEvenIfNameIsInMessage() {
        assertThat(ConstraintViolations.nameOf(new DataIntegrityViolationException(
                "violates unique constraint \"holidays_date_unique\"",
                new SQLException("violates unique constraint \"holidays_date_unique\""))))
                .isNull();
    }
}
