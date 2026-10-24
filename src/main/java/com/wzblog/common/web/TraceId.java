package com.wzblog.common.web;

import org.slf4j.MDC;

import java.util.UUID;

public final class TraceId {

    public static final String MDC_KEY = "traceId";

    private TraceId() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "" : value;
    }

    public static String start() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        MDC.put(MDC_KEY, traceId);
        return traceId;
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}