package com.yufeichi.server.ai;

/** Transaction-local signal. Contains identifiers only, never article text or credentials. */
public record AiContentChanged(String sourceType, long sourceId) { }
