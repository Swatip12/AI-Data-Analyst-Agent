package com.dataanalyst.domain;

/**
 * Immutable value object representing the outcome of a SQL validation check.
 *
 * <p>On success: {@code valid=true}, {@code reason=null}, {@code matchedKeyword=null}.
 * <br>On failure: {@code valid=false}, {@code reason} contains the failure code
 * (e.g., {@code "MISSING_SELECT_KEYWORD"}), and {@code matchedKeyword} is populated
 * only for the {@code FORBIDDEN_KEYWORD} case.
 *
 * @param valid          {@code true} when the SQL passed all validation checks
 * @param reason         machine-readable failure code; {@code null} when valid
 * @param matchedKeyword the forbidden keyword that triggered rejection;
 *                       {@code null} for all failure cases except {@code FORBIDDEN_KEYWORD}
 */
public record ValidationResult(boolean valid, String reason, String matchedKeyword) {

    /** Factory — successful validation. */
    public static ValidationResult ok() {
        return new ValidationResult(true, null, null);
    }

    /** Factory — failed validation without a matched keyword. */
    public static ValidationResult fail(String reason) {
        return new ValidationResult(false, reason, null);
    }

    /** Factory — failed validation with a matched forbidden keyword. */
    public static ValidationResult failWithKeyword(String reason, String matchedKeyword) {
        return new ValidationResult(false, reason, matchedKeyword);
    }
}
