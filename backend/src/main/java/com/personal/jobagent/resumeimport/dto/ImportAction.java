package com.personal.jobagent.resumeimport.dto;

/** What to do with one previewed item at confirm time — chosen by the user, never defaulted to a destructive action. */
public enum ImportAction {
    /** Insert as a new record. Valid for NEW, POSSIBLE_DUPLICATE or NEEDS_REVIEW items. */
    ADD,
    /** Overwrite the matched existing record's fields. Only valid when matchedExistingId is set. */
    REPLACE,
    /** Do nothing with this item. The default for everything not explicitly accepted. */
    SKIP
}
