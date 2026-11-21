// SPDX-License-Identifier: GPL-3.0-only
package com.blanket.core.plan;

import com.blanket.core.bindings.EventBinding;
import com.blanket.core.bindings.Hooks;
import com.blanket.core.bindings.Lowering;
import com.blanket.core.bindings.Selector;
import com.blanket.core.bindings.TypeBinding;
import com.blanket.core.bindings.Version;
import com.blanket.core.resolve.Ref;
import com.blanket.core.resolve.Resolver;
import com.blanket.core.resolve.TargetSpec;
import com.blanket.core.scan.Scan;
import com.blanket.core.validate.ApiClasses;
import com.blanket.core.validate.BindingSet;
import com.blanket.core.validate.Diagnostic;
import com.blanket.core.validate.ErrorCode;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Builds the {@link Plan} of one target from the bindings and the mod's used set. Runs twice over the same
 * {@link Names}: first to collect every reference, then, after they are resolved, to build the instructions.
 */
final class TargetPlan implements Opcodes {
    static final String DISPATCH = "com/blanket/runtime/Dispatch";
    static final String GLUE_FRAME_CORE = "com/blanket/runtime/glue/GlueFrame.core";
    /** rendering-api.md section 3.2: LWJGL 2 ({@code Display}) through 1.12.2, LWJGL 3 (GLFW) from 1.13. */
    static final String LWJGL3_FROM = "1.13";
    static final String LWJGL2_GL = "com/blanket/runtime/Lwjgl2Gl";
    static final String LWJGL3_GL = "com/blanket/runtime/Lwjgl3Gl";

    /** A value a payload source leaves on the stack: the instructions and the value's type descriptor. */
    private record Value(List<Op> ops, String desc) {}

    /** Where a hook body runs: a Mixin handler (target method arguments) or a native listener (the event in slot 1). */
    private record Site(String label, Type[] mixinArgs) {
        boolean mixin() {
            return mixinArgs != null;
        }
    }

    private final BindingSet b;
    private final Scan scan;
    private final ApiClasses api;
    private final TargetSpec t;
    private final Names n;
    private final String anchor;
    /** Glue member ({@code stub class.method}) to its stub descriptor, for the members bound on this target. */
    private final Map<String, String> planned = new LinkedHashMap<>();

    TargetPlan(BindingSet b, Scan scan, ApiClasses api, TargetSpec t, Names n) {
        this.b = b;
        this.scan = scan;
        this.api = api;
        this.t = t;
        this.n = n;
        this.anchor = b.targets().anchor();
    }

    Plan build() {
        planned.clear();
        List<Glue> glue = glue();
        return new Plan(t, era(t), entry(), glImpl(t), core(glue), types(), members(), glue, hooks());
    }

    // ---------------------------------------------------------------------------------------- target facts

    /** The loader-era id of loader-emitters.md section 1, from the era's loader, runtime namespace and emitter. */
    static String era(TargetSpec t) {
        if (t.emitter().equals("forge-legacy")) return "forge-legacy";
        if (t.runtime().equals("lf")) return "legacy-fabric";
        String names = switch (t.runtime()) {
            case "intermediary" -> "int";
            case "official" -> "off";
            case "mojang" -> "moj";
            case "srg", "srgmoj" -> t.runtime();
            default -> throw new IllegalArgumentException("no loader-era for runtime " + t.runtime() + " of " + t.id());
        };
        return t.loader() + "-" + names;
    }

    /**
     * targets.json has no render data yet (rendering-api.md section 3.3 says its era data selects the GL backend), so
     * the backend follows the LWJGL major of the release from rendering-api.md section 3.2.
     */
    static String glImpl(TargetSpec t) {
        return Version.parse(t.mc()).compareTo(Version.parse(LWJGL3_FROM)) < 0 ? LWJGL2_GL : LWJGL3_GL;
    }

    /** The core-profile flag is the constant the target binds {@code GlueFrame.core} to. */
    private boolean core(List<Glue> glue) {
        for (Glue g : glue) {
            if (g.name().equals(GLUE_FRAME_CORE) && g.steps().size() == 1 && g.steps().get(0).arg() instanceof Boolean on) return on;
        }
        if (n.dry()) return false;
        throw Planner.fail(new Diagnostic(ErrorCode.UNBOUND, t.id(), "GlueFrame.core", null, null,
                "the GL profile of " + t.id() + " comes from the glue member GlueFrame.core, which has no constant rule for it",
                "add a rule { \"when\": ..., \"lower\": { \"const\": true|false } } for " + t.id() + " to member \"core\" in glue/GlueFrame.json"));
    }

    private String entry() {
        if (scan.entries().size() == 1) return scan.entries().iterator().next();
        throw Planner.fail(new Diagnostic(ErrorCode.UNBOUND, t.id(), "entry", null, null,
                "the mod jar has " + scan.entries().size() + " classes that implement com.blanket.api.ClientMod " + scan.entries() + ", the plan needs one",
                "make exactly one class of the mod implement com.blanket.api.ClientMod directly"));
    }

    // ---------------------------------------------------------------------------------------- types and members

    private Map<String, String> types() {
        Map<String, String> out = new LinkedHashMap<>();
        for (TypeBinding tb : b.types().values()) {
            String owner = internal(tb.api());
            if (!used(owner)) continue;
            TypeBinding.Representation rep = first(tb.representation(), TypeBinding.Representation::when);
            if (rep == null) {
                throw Planner.fail(new Diagnostic(ErrorCode.UNBOUND, t.id(), simple(tb.api()) + " (representation)", null, null,
                        "C1 coverage: " + tb.file() + " has no representation rule for " + t.id(),
                        "add a representation rule whose \"when\" matches " + t.id() + " to " + tb.file()));
            }
            if (rep.alias() != null) out.put(owner, n.named(anchorOf(tb), rep.alias(), simple(tb.api()) + " (representation)").owner());
        }
        return out;
    }

    private boolean used(String owner) {
        if (scan.types().contains(owner)) return true;
        for (Scan.ApiMember m : scan.members()) if (m.owner().equals(owner)) return true;
        return false;
    }

    private Map<String, List<Op>> members() {
        Map<String, List<Op>> out = new LinkedHashMap<>();
        for (Scan.ApiMember m : scan.members()) {
            if (m.isField()) continue;
            TypeBinding tb = typeFile(m.owner());
            if (tb == null) continue;   // an owned API class without a type file stays as it is
            TypeBinding.Representation rep = first(tb.representation(), TypeBinding.Representation::when);
            if (rep == null || rep.owned() != null) continue;
            String label = simple(tb.api()) + "." + m.name();
            List<TypeBinding.MemberRule> rules = tb.members().get(m.name() + m.desc());
            if (rules == null) rules = tb.members().get(m.name());
            TypeBinding.MemberRule rule = rules == null ? null : first(rules, TypeBinding.MemberRule::when);
            if (rule == null) {
                throw Planner.fail(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                        "C1 coverage: used member " + label + " has no rule for " + t.id() + " in " + tb.file(),
                        "add a rule whose \"when\" matches " + t.id() + " to member \"" + m.name() + "\" in " + tb.file()));
            }
            out.put(m.owner() + "." + m.name() + m.desc(), lower(rule.lower(), Type.getReturnType(m.desc()).getDescriptor(), anchorOf(tb), label, tb.file()));
        }
        return out;
    }

    private TypeBinding typeFile(String owner) {
        for (TypeBinding tb : b.types().values()) if (internal(tb.api()).equals(owner)) return tb;
        return null;
    }

    // ---------------------------------------------------------------------------------------- lowerings

    /** The instructions of a lowering whose result has type {@code resultDesc} (constants take that type). */
    private List<Op> lower(Lowering l, String resultDesc, String anchor, String label, String file) {
        List<Op> out = new ArrayList<>();
        switch (l.kind()) {
            case CHAIN -> {
                List<Lowering> steps = l.steps();
                for (int i = 0; i < steps.size(); i++) {
                    Lowering s = steps.get(i);
                    if (s.kind() == Lowering.Kind.CONST) {
                        out.add(Op.constant(constant(s.constant(), consumedAs(steps, i), label)));
                    } else {
                        out.add(step(s, anchor, label, file));
                    }
                }
            }
            case CONST -> out.add(Op.constant(constant(l.constant(), resultDesc, label)));
            default -> out.add(step(l, anchor, label, file));
        }
        return Collections.unmodifiableList(out);
    }

    /** The parameter type a chain's const step {@code i} fills: the next invoke takes the pushed steps right before it. */
    private static String consumedAs(List<Lowering> steps, int i) {
        int j = i + 1;
        while (j < steps.size() && (steps.get(j).kind() == Lowering.Kind.CONST || steps.get(j).kind() == Lowering.Kind.NEWARRAY)) j++;
        Type[] params = Type.getArgumentTypes(Ref.parse(steps.get(j).ref(), "", "$").desc());
        return params[params.length - (j - i)].getDescriptor();
    }

    private Op step(Lowering l, String anchor, String label, String file) {
        switch (l.kind()) {
            case INVOKE: {
                Resolver.Resolved r = n.named(anchor, l.ref(), label);
                return invoke(r, n.access(anchor, l.ref(), label));
            }
            case GET:
            case PUT: {
                Resolver.Resolved r = n.named(anchor, l.ref(), label);
                boolean isStatic = n.access(anchor, l.ref(), label).isStatic();
                int op = l.kind() == Lowering.Kind.GET ? (isStatic ? GETSTATIC : GETFIELD) : (isStatic ? PUTSTATIC : PUTFIELD);
                return Op.field(op, r.owner(), r.name(), r.desc());
            }
            case NEW: {
                Resolver.Resolved r = n.named(anchor, l.ref(), label);
                if (!n.dry() && !"()V".equals(r.desc())) {
                    throw Planner.fail(new Diagnostic(ErrorCode.UNSUPPORTED, t.id(), label, l.ref(), null,
                            "a new lowering with constructor arguments is M2; M1 plans the no-argument constructor only",
                            "bind " + label + " in " + file + " to a no-argument constructor or a glue chain"));
                }
                return Op.newObject(r.owner());
            }
            case CAST:
                return Op.cast(n.named(anchor, l.ref(), label).owner());
            case NEWARRAY:
                return Op.newArray(l.ref(), l.length());
            case ADAPTER:
                throw Planner.fail(new Diagnostic(ErrorCode.UNSUPPORTED, t.id(), label, null, null,
                        file + " binds " + label + " to adapter " + l.ref() + " on " + t.id() + "; adapters are M2",
                        "bind " + label + " with a direct lowering or a chain for " + t.id()));
            case UNSUPPORTED:
                throw Planner.fail(new Diagnostic(ErrorCode.UNSUPPORTED, t.id(), label, null, null,
                        file + " marks " + label + " unsupported on " + t.id() + ": " + l.ref(),
                        "do not use " + label + " on " + t.id() + ", or bind it there"));
            default:
                throw new IllegalStateException("not a chain step: " + l);
        }
    }

    private static Op invoke(Resolver.Resolved r, Names.Access a) {
        int op = a.isStatic() ? INVOKESTATIC : a.ownerInterface() ? INVOKEINTERFACE : INVOKEVIRTUAL;
        return Op.invoke(op, r.owner(), r.name(), r.desc(), a.ownerInterface());
    }

    /** A binding constant as the JVM value of type {@code desc}. */
    private Object constant(JsonElement v, String desc, String label) {
        Object out = null;
        if (v == null || v.isJsonNull()) {
            if (desc.startsWith("L") || desc.startsWith("[")) return null;
        } else {
            JsonPrimitive p = v.getAsJsonPrimitive();
            switch (desc) {
                case "Z" -> out = p.isBoolean() ? p.getAsBoolean() : null;
                case "I", "S", "B", "C" -> out = p.isNumber() && integral(p) ? p.getAsInt() : null;
                case "J" -> out = p.isNumber() && integral(p) ? p.getAsLong() : null;
                case "F" -> out = p.isNumber() ? p.getAsFloat() : null;
                case "D" -> out = p.isNumber() ? p.getAsDouble() : null;
                case "Ljava/lang/String;" -> out = p.isString() ? p.getAsString() : null;
                default -> out = null;
            }
        }
        if (out == null && !n.dry()) {
            throw Planner.fail(new Diagnostic(ErrorCode.SHAPE, t.id(), label, null, null,
                    "the constant " + v + " is not a value of type " + desc,
                    "write a constant of type " + desc + " for " + label));
        }
        return out;
    }

    private static boolean integral(JsonPrimitive p) {
        String s = p.getAsString();
        return s.indexOf('.') < 0 && s.indexOf('e') < 0 && s.indexOf('E') < 0;
    }

    // ---------------------------------------------------------------------------------------- glue

    /** A body for every glue member bound on this target (unsupported members keep their throwing stub). */
    private List<Glue> glue() {
        List<Glue> out = new ArrayList<>();
        for (TypeBinding gb : b.glue().values()) {
            String owner = internal(gb.api());
            for (Map.Entry<String, List<TypeBinding.MemberRule>> e : gb.members().entrySet()) {
                TypeBinding.MemberRule rule = first(e.getValue(), TypeBinding.MemberRule::when);
                if (rule == null || rule.lower().kind() == Lowering.Kind.UNSUPPORTED) continue;
                String label = simple(gb.api()) + "." + e.getKey();
                ApiClasses.Method stub = stub(owner, e.getKey(), label, gb.file());
                String retDesc = Type.getReturnType(stub.desc()).getDescriptor();
                List<Op> body = new ArrayList<>(arguments(rule.lower(), stub, anchorOf(gb), label, gb.file()));
                body.addAll(lower(rule.lower(), retDesc, anchorOf(gb), label, gb.file()));
                out.add(new Glue(owner + "." + stub.name(), retDesc, Collections.unmodifiableList(body)));
                planned.put(owner + "." + stub.name(), stub.desc());
            }
        }
        return out;
    }

    /** The one static stub method a glue member key names ({@code name} or {@code name(desc)}). */
    private ApiClasses.Method stub(String owner, String key, String label, String file) {
        int paren = key.indexOf('(');
        String name = paren < 0 ? key : key.substring(0, paren);
        List<ApiClasses.Method> found = new ArrayList<>();
        for (ApiClasses.Method m : api.methods(owner).orElse(List.of())) {
            if (m.name().equals(name) && m.isStatic() && (paren < 0 || m.desc().equals(key.substring(paren)))) found.add(m);
        }
        if (found.size() == 1) return found.get(0);
        throw Planner.fail(new Diagnostic(found.isEmpty() ? ErrorCode.UNBOUND : ErrorCode.SHAPE, t.id(), label, null, null,
                "glue stub " + owner + " has " + found.size() + " static methods named " + name + (paren < 0 ? "" : " with descriptor " + key.substring(paren)),
                "name one static method of " + owner.replace('/', '.') + " in " + file));
    }

    /**
     * Loads the stub's parameters for the lowering that consumes them: slot 0 first. An {@code Object} parameter that the
     * lowering takes as a narrower reference gets a cast (binding-format.md section 4.5: the generator casts per argument).
     */
    private List<Op> arguments(Lowering l, ApiClasses.Method stub, String anchor, String label, String file) {
        Type[] params = Type.getArgumentTypes(stub.desc());
        if (params.length == 0) return List.of();
        Lowering first = l.kind() == Lowering.Kind.CHAIN ? l.steps().get(0) : l;
        List<String> wanted = new ArrayList<>();
        switch (first.kind()) {
            case CAST -> wanted.add(null);   // the chain's own cast step converts the one argument
            case INVOKE, GET, PUT -> {
                Resolver.Resolved r = n.named(anchor, first.ref(), label);
                if (!n.access(anchor, first.ref(), label).isStatic()) wanted.add("L" + r.owner() + ";");
                if (r.field()) {
                    if (first.kind() == Lowering.Kind.PUT) wanted.add(r.desc());
                } else {
                    for (Type a : Type.getArgumentTypes(r.desc())) wanted.add(a.getDescriptor());
                }
            }
            default -> { }
        }
        if (n.dry()) return List.of();
        if (wanted.size() != params.length) {
            throw Planner.fail(new Diagnostic(ErrorCode.SHAPE, t.id(), label, null, null,
                    "C3 shape: the glue stub " + stub.name() + stub.desc() + " has " + params.length + " parameters, its lowering " + l + " takes " + wanted.size(),
                    "make the lowering of " + label + " in " + file + " consume the stub's parameters in order"));
        }
        List<Op> out = new ArrayList<>();
        int slot = 0;
        for (int i = 0; i < params.length; i++) {
            out.add(Op.load(params[i].getOpcode(ILOAD), slot));
            slot += params[i].getSize();
            String want = wanted.get(i);
            if (want != null && params[i].getDescriptor().equals("Ljava/lang/Object;") && !want.equals("Ljava/lang/Object;")
                    && (want.startsWith("L") || want.startsWith("["))) {
                out.add(Op.cast(Type.getType(want).getInternalName()));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------------------------- hooks

    private List<Hook> hooks() {
        List<Hook> out = new ArrayList<>();
        for (String event : scan.events()) {
            String label = "event " + event;
            EventBinding eb = null;
            String file = null;
            for (Map.Entry<String, EventBinding> e : b.events().entrySet()) {
                if (e.getValue().event().equals(event)) {
                    eb = e.getValue();
                    file = e.getKey();
                }
            }
            EventBinding.Recipe recipe = eb == null ? null : first(eb.recipes(), EventBinding.Recipe::when);
            if (recipe == null) {
                throw Planner.fail(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                        "C1 coverage: " + (eb == null ? "no events/ file binds " + label : label + " has no recipe for " + t.id() + " in " + file),
                        "add a recipe whose \"when\" matches " + t.id() + " (contributing.md 3.3)"));
            }
            for (EventBinding.Injection in : recipe.injections()) out.add(in.hook() != null ? mixin(event, in, label) : listener(event, in, label));
        }
        return out;
    }

    private Hook mixin(String event, EventBinding.Injection in, String label) {
        String hookLabel = "hook " + in.hook();
        List<Hooks.Rule> rules = b.hooks() == null ? null : b.hooks().hooks().get(in.hook());
        Hooks.Rule rule = rules == null ? null : first(rules, Hooks.Rule::when);
        if (rule == null) {
            throw Planner.fail(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                    "C1 coverage: hook '" + in.hook() + "' (used by " + label + ") has no rule for " + t.id() + " in hooks.json",
                    "add a rule whose \"when\" matches " + t.id() + " to hook \"" + in.hook() + "\" in hooks.json"));
        }
        Resolver.Resolved method = n.named(anchor, rule.method(), hookLabel);
        Hooks.At a = rule.at();
        At at;
        if (a.kind() == Hooks.At.Kind.INVOKE || a.kind() == Hooks.At.Kind.INVOKE_STRING) {
            Resolver.Resolved call = n.named(anchor, a.method(), hookLabel);
            at = new At(a.kind().name(), "L" + call.owner() + ";" + call.name() + call.desc(), a.ordinal() == null ? -1 : a.ordinal(), a.ldc(), a.shift());
        } else {
            at = new At(a.kind().name(), null, -1, null, null);
        }
        Type[] args = method.desc() == null ? new Type[0] : Type.getArgumentTypes(method.desc());
        StringBuilder handler = new StringBuilder("(");
        for (Type arg : args) handler.append(arg.getDescriptor());
        handler.append(")V");
        Site site = new Site(label, args);
        ApiClasses.Method dispatch = dispatch(in.dispatch(), label);
        return new Hook(event, dispatch.name(), dispatch.desc(), true, method.owner(), method.name() + method.desc(), at, handler.toString(),
                null, null, List.of(), before(in, site), payload(in, dispatch, site));
    }

    private Hook listener(String event, EventBinding.Injection in, String label) {
        String eventClass = n.named(anchor, in.nativeEvent(), label).owner();
        Site site = new Site(label, null);
        List<Filter> filters = new ArrayList<>();
        for (Map.Entry<String, String> f : in.filter().entrySet()) {
            Value got = getter(f.getKey(), label);
            Resolver.Resolved field = n.named(anchor, f.getValue(), label);
            if (!n.dry() && (!field.field() || !n.access(anchor, f.getValue(), label).isStatic())) {
                throw Planner.fail(new Diagnostic(ErrorCode.SHAPE, t.id(), label, f.getValue(), null,
                        "a filter value is a static field, " + f.getValue() + " is not",
                        "use a static field (Owner#NAME:desc) as the filter value"));
            }
            n.access(anchor, f.getValue(), label);
            filters.add(new Filter(got.ops(), Op.field(GETSTATIC, field.owner(), field.name(), field.desc())));
        }
        ApiClasses.Method dispatch = dispatch(in.dispatch(), label);
        return new Hook(event, dispatch.name(), dispatch.desc(), false, null, null, null, null, eventClass, in.bus(),
                Collections.unmodifiableList(filters), before(in, site), payload(in, dispatch, site));
    }

    private ApiClasses.Method dispatch(String name, String label) {
        for (ApiClasses.Method m : api.methods(DISPATCH).orElse(List.of())) {
            if (m.name().equals(name) && m.isStatic()) return m;
        }
        throw Planner.fail(new Diagnostic(ErrorCode.UNBOUND, t.id(), label, null, null,
                "com.blanket.runtime.Dispatch has no public static method " + name,
                "name a Dispatch method in the recipe's \"dispatch\" (D-057)"));
    }

    private List<List<Op>> before(EventBinding.Injection in, Site site) {
        List<List<Op>> out = new ArrayList<>();
        for (EventBinding.Source s : in.before()) out.add(source(s, site, "V").ops());
        return Collections.unmodifiableList(out);
    }

    private List<List<Op>> payload(EventBinding.Injection in, ApiClasses.Method dispatch, Site site) {
        Type[] params = Type.getArgumentTypes(dispatch.desc());
        if (params.length != in.payload().size()) {
            throw Planner.fail(new Diagnostic(ErrorCode.SHAPE, t.id(), site.label(), null, null,
                    "Dispatch." + dispatch.name() + dispatch.desc() + " takes " + params.length + " values, the recipe's payload gives " + in.payload().size(),
                    "give one payload entry per Dispatch parameter, in order (binding-format.md section 7)"));
        }
        List<List<Op>> out = new ArrayList<>();
        int i = 0;
        for (EventBinding.Source s : in.payload().values()) out.add(source(s, site, params[i++].getDescriptor()).ops());
        return Collections.unmodifiableList(out);
    }

    /** One payload source as instructions leaving a value of type {@code want} (or nothing for {@code V}). */
    private Value source(EventBinding.Source s, Site site, String want) {
        Value v = switch (s.kind()) {
            case "arg" -> arg(s.value().getAsInt(), site);
            case "getter" -> getter(s.value().getAsString(), site.label());
            case "const" -> new Value(List.of(Op.constant(constant(s.value(), want, site.label()))), want);
            case "glue" -> glueCall(s, site);
            default -> throw Planner.fail(new Diagnostic(ErrorCode.UNSUPPORTED, t.id(), site.label(), null, null,
                    "the payload source '" + s.kind() + "' is not planned in M1",
                    "use an arg, getter, const or glue source"));
        };
        if (!n.dry() && !fits(v.desc(), want)) {
            throw Planner.fail(new Diagnostic(ErrorCode.SHAPE, t.id(), site.label(), null, null,
                    "a payload source gives " + v.desc() + " where " + want + " is needed (no boxing or widening)",
                    "pass a " + want + " here, through a glue member if it needs converting"));
        }
        return v;
    }

    private static boolean fits(String got, String want) {
        boolean gotRef = got.startsWith("L") || got.startsWith("[");
        boolean wantRef = want.startsWith("L") || want.startsWith("[");
        return gotRef && wantRef || got.equals(want);
    }

    private Value arg(int index, Site site) {
        if (!site.mixin()) {
            throw Planner.fail(new Diagnostic(ErrorCode.SHAPE, t.id(), site.label(), null, null,
                    "an arg source needs a hooked method; a native listener has only the event (use a getter)",
                    "read the value with a getter on the event"));
        }
        Type[] args = site.mixinArgs();
        if (index < 1 || index > args.length) {
            if (n.dry()) return new Value(List.of(), "Ljava/lang/Object;");
            throw Planner.fail(new Diagnostic(ErrorCode.SHAPE, t.id(), site.label(), null, null,
                    "arg " + index + " does not exist: the hooked method takes " + args.length + " arguments",
                    "number the hooked method's arguments from 1"));
        }
        int slot = 1;
        for (int i = 0; i < index - 1; i++) slot += args[i].getSize();
        Type a = args[index - 1];
        return new Value(List.of(Op.load(a.getOpcode(ILOAD), slot)), a.getDescriptor());
    }

    /** A getter or field read on the native event in slot 1. */
    private Value getter(String text, String label) {
        Resolver.Resolved r = n.named(anchor, text, label);
        Names.Access a = n.access(anchor, text, label);
        List<Op> ops = new ArrayList<>();
        ops.add(Op.load(ALOAD, 1));
        if (r.field()) {
            ops.add(Op.field(a.isStatic() ? GETSTATIC : GETFIELD, r.owner(), r.name(), r.desc()));
            return new Value(ops, r.desc());
        }
        ops.add(invoke(r, a));
        return new Value(ops, r.desc() == null ? "V" : Type.getReturnType(r.desc()).getDescriptor());
    }

    /** The argument values, then a static call of the glue stub, whose planned body does the work. */
    private Value glueCall(EventBinding.Source s, Site site) {
        String text = s.value().getAsString();
        int hash = text.indexOf('#');
        String type = text.substring(0, hash), key = text.substring(hash + 1);
        TypeBinding gb = null;
        for (TypeBinding g : b.glue().values()) if (simple(g.api()).equals(type)) gb = g;
        if (gb == null) {
            throw Planner.fail(new Diagnostic(ErrorCode.UNBOUND, t.id(), site.label(), null, null,
                    "no glue/ file has the glue type " + type, "add glue/" + type + ".json"));
        }
        String owner = internal(gb.api());
        ApiClasses.Method stub = stub(owner, key, type + "." + key, gb.file());
        if (!planned.containsKey(owner + "." + stub.name())) {
            throw Planner.fail(new Diagnostic(ErrorCode.UNSUPPORTED, t.id(), site.label(), null, null,
                    "the payload calls glue " + type + "." + stub.name() + ", which " + gb.file() + " does not bind on " + t.id(),
                    "bind " + type + "." + stub.name() + " for " + t.id() + " in " + gb.file()));
        }
        Type[] params = Type.getArgumentTypes(stub.desc());
        if (params.length != s.args().size()) {
            throw Planner.fail(new Diagnostic(ErrorCode.SHAPE, t.id(), site.label(), null, null,
                    "glue " + type + "." + stub.name() + stub.desc() + " takes " + params.length + " arguments, the source gives " + s.args().size(),
                    "give one args entry per glue parameter"));
        }
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < params.length; i++) ops.addAll(source(s.args().get(i), site, params[i].getDescriptor()).ops());
        ops.add(Op.invoke(INVOKESTATIC, owner, stub.name(), stub.desc(), false));
        return new Value(Collections.unmodifiableList(ops), Type.getReturnType(stub.desc()).getDescriptor());
    }

    // ---------------------------------------------------------------------------------------- helpers

    /** First match wins (binding-format.md section 2.2); null when no rule matches. */
    private <R> R first(List<R> rules, Function<R, Selector> when) {
        Version v = Version.parse(t.mc());
        for (R r : rules) if (when.apply(r).matches(t.loader(), v)) return r;
        return null;
    }

    private String anchorOf(TypeBinding tb) {
        return tb.anchor() != null ? tb.anchor() : anchor;
    }

    private static String internal(String dotted) {
        return dotted.replace('.', '/');
    }

    private static String simple(String dotted) {
        return dotted.substring(Math.max(dotted.lastIndexOf('.'), dotted.lastIndexOf('/')) + 1);
    }
}
