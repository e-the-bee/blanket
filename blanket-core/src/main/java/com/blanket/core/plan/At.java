// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

/**
 * A Mixin injection point (the {@code @At} of a generated {@code @Inject}).
 *
 * @param kind    HEAD, TAIL, RETURN, INVOKE or INVOKE_STRING (written as the {@code @At} value)
 * @param target  for INVOKE and INVOKE_STRING the Mixin target selector in runtime names,
 *                {@code Lowner;name(args)ret}; null otherwise
 * @param ordinal the {@code ordinal}, or -1 for none
 * @param ldc     for INVOKE_STRING the string argument (written as {@code args = "ldc=<ldc>"}); null otherwise
 * @param shift   the {@code At.Shift} constant name ({@code AFTER}, {@code BEFORE}), or null for none
 */
public record At(String kind, String target, int ordinal, String ldc, String shift) {}
