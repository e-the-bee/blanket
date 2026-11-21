// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

/**
 * One bytecode instruction of a resolved plan, in runtime names. The RESOLVE planner builds Ops; blanket-emit writes
 * them unchanged (its {@code ChainWriter}), so every Minecraft name in a generated jar arrives here first.
 *
 * <p>Per kind:
 * <ul>
 *   <li>{@code LOAD}: {@code opcode} is ILOAD, LLOAD, FLOAD, DLOAD or ALOAD; {@code arg} is the local slot ({@link Integer}).
 *       In a Mixin handler slot 0 is the Mixin instance and target arguments start at 1 (wide types take two slots);
 *       in a native listener slot 1 is the event; in a glue body (static) slot 0 is the first parameter.</li>
 *   <li>{@code INVOKE}: {@code opcode} is INVOKESTATIC, INVOKEVIRTUAL, INVOKEINTERFACE or INVOKESPECIAL; {@code owner},
 *       {@code name}, {@code desc}, {@code itf} as in the class file.</li>
 *   <li>{@code FIELD}: {@code opcode} is GETFIELD, GETSTATIC, PUTFIELD or PUTSTATIC.</li>
 *   <li>{@code CONST}: {@code arg} is an Integer, Long, Float, Double, String, Boolean, or null ({@code ACONST_NULL}).</li>
 *   <li>{@code CAST}: CHECKCAST to {@code owner}.</li>
 *   <li>{@code NEWARRAY}: {@code desc} is the element descriptor ({@code F}, {@code Ljava/lang/Object;}), {@code arg} the
 *       length ({@link Integer}); pushes the new array.</li>
 *   <li>{@code NEW}: pushes {@code new owner()}; {@code desc} is the constructor descriptor, {@code ()V} only.</li>
 * </ul>
 * Fields a kind does not use are 0, null or false. {@code com/blanket/...} owners are relocated with the mod's classes.
 */
public record Op(Kind kind, int opcode, String owner, String name, String desc, boolean itf, Object arg) {

    public enum Kind { LOAD, INVOKE, FIELD, CONST, CAST, NEWARRAY, NEW }

    public static Op load(int opcode, int slot) {
        return new Op(Kind.LOAD, opcode, null, null, null, false, slot);
    }

    public static Op invoke(int opcode, String owner, String name, String desc, boolean itf) {
        return new Op(Kind.INVOKE, opcode, owner, name, desc, itf, null);
    }

    public static Op field(int opcode, String owner, String name, String desc) {
        return new Op(Kind.FIELD, opcode, owner, name, desc, false, null);
    }

    public static Op constant(Object value) {
        return new Op(Kind.CONST, 0, null, null, null, false, value);
    }

    public static Op cast(String type) {
        return new Op(Kind.CAST, 0, type, null, null, false, null);
    }

    public static Op newArray(String elementDesc, int length) {
        return new Op(Kind.NEWARRAY, 0, null, null, elementDesc, false, length);
    }

    public static Op newObject(String type) {
        return new Op(Kind.NEW, 0, type, "<init>", "()V", false, null);
    }
}
