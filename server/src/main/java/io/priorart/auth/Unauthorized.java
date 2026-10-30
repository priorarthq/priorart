package io.priorart.auth;

/** The caller isn't who they need to be, or a sign-in wasn't accepted (HTTP 401). */
public class Unauthorized extends RuntimeException {
    public Unauthorized(String m) { super(m); }
}
