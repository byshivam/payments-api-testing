package com.aryabank.payments.support;

import java.net.URI;

/** Where the API and its SQLite file live. Both come from environment variables set by the runner. */
public final class Config {

    public static final String BASE_URL = env("ARYA_BASE_URL", "http://localhost:8000");
    public static final String DB_PATH = env("ARYA_DB_PATH", "arya_payments.db");

    private Config() {
    }

    public static String host() {
        return URI.create(BASE_URL).getHost();
    }

    public static int port() {
        int port = URI.create(BASE_URL).getPort();
        return port == -1 ? 80 : port;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            value = System.getProperty(name);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
