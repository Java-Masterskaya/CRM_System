package ru.practicum.crm.request.domain;

/**
 * По каким срокам перенос снял признак просрочки (#200). У этих сроков снимается и отметка об
 * эскалации: она хранится только в базе, поэтому её снимает отдельный запрос, а не сущность.
 *
 * @param firstResponse срок первого ответа снова не нарушен
 * @param resolution срок решения снова не нарушен
 */
public record ReopenedDeadlines(boolean firstResponse, boolean resolution) {

    /** Ни один признак не снят. */
    public static final ReopenedDeadlines NONE = new ReopenedDeadlines(false, false);

    /** Снят хотя бы один признак. */
    public boolean any() {
        return firstResponse || resolution;
    }
}
