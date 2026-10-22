package ph.gov.philhealth.qms.user;

import ph.gov.philhealth.qms.common.ApiException;

/** Minimum password rules; align with the PhilHealth information security policy before go-live. */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;

    private PasswordPolicy() {
    }

    public static void validate(String password, String username) {
        if (password == null || password.length() < MIN_LENGTH || password.length() > 128
                || !password.chars().anyMatch(Character::isUpperCase)
                || !password.chars().anyMatch(Character::isLowerCase)
                || !password.chars().anyMatch(Character::isDigit)) {
            throw ApiException.badRequest("Password must be 12-128 characters with upper-case, lower-case and digits");
        }
        if (username != null && password.toLowerCase().contains(username.toLowerCase())) {
            throw ApiException.badRequest("Password must not contain the username");
        }
    }
}
