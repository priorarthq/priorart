package io.priorart.auth;

/** The caller is known but not allowed to do this (HTTP 403). */
public class Forbidden extends RuntimeException {
    public Forbidden(String m) { super(m); }
}
