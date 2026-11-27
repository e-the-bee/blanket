// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.api;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Marks an API type that is owned: real code shipped in the mod's jar (or an owned facade whose members are bound),
 * not an alias of a game class. Every API type without this annotation is an alias stub.
 */
@Owned
@Retention(RetentionPolicy.CLASS)
@java.lang.annotation.Target(ElementType.TYPE)
public @interface Owned {}
