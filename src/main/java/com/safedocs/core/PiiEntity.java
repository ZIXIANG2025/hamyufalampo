package com.safedocs.core;

/** An exact, verbatim text span classified by the local detector. */
public record PiiEntity(String entityType, String matchedText) { }
