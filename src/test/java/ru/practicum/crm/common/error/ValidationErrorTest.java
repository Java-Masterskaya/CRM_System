package ru.practicum.crm.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ValidationErrorTest {

    @Test
    void ofField_whenSimpleField_buildsJsonPointer() {
        ValidationError error = ValidationError.ofField("subject", "не должно быть пустым");

        assertThat(error.pointer()).isEqualTo("#/subject");
        assertThat(error.parameter()).isNull();
        assertThat(error.detail()).isEqualTo("не должно быть пустым");
    }

    @Test
    void ofField_whenNestedFieldInArray_buildsJsonPointerWithIndex() {
        ValidationError error = ValidationError.ofField("items[0].name", "обязательно");

        assertThat(error.pointer()).isEqualTo("#/items/0/name");
    }

    @Test
    void ofField_whenFieldIsEmpty_buildsPointerToWholeBody() {
        ValidationError error = ValidationError.ofField("", "объект заполнен неверно");

        assertThat(error.pointer()).isEqualTo("#");
    }

    @Test
    void ofParameter_whenQueryParameterInvalid_keepsParameterName() {
        ValidationError error = ValidationError.ofParameter("size", "должно быть не больше 100");

        assertThat(error.parameter()).isEqualTo("size");
        assertThat(error.pointer()).isNull();
    }
}
