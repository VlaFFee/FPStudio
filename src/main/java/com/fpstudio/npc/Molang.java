package com.fpstudio.npc;

import java.util.Locale;

/**
 * A tiny Molang subset for animation files: numbers, + - * / %, parentheses, unary minus, ternary ?:, comparisons,
 * query.anim_time / query.life_time, variable.* (always 0) and the common math.* functions (sin/cos take degrees).
 * Expressions are compiled once into a node tree and evaluated without allocation.
 */
public final class Molang {
    private Molang() {}

    public interface Expr { float eval(float animTime); }

    private static final class Const implements Expr {
        final float v;
        Const(float v) { this.v = v; }
        public float eval(float t) { return v; }
    }

    public static final Expr ZERO = new Const(0f);

    public static Expr constant(float v) { return v == 0f ? ZERO : new Const(v); }

    public static boolean isConstant(Expr e) { return e instanceof Const; }

    public static float value(Expr e) { return e.eval(0f); }

    /** Never throws: an expression that cannot be parsed becomes 0. */
    public static Expr compile(String src) {
        if (src == null) return ZERO;
        String s = src.trim().toLowerCase(Locale.ROOT);
        if (s.endsWith(";")) s = s.substring(0, s.length() - 1);
        if (s.startsWith("return ")) s = s.substring(7);
        if (s.isEmpty()) return ZERO;
        try {
            return new P(s).parseTernary();
        } catch (RuntimeException e) {
            return ZERO;
        }
    }

    private static final class P {
        final String s;
        int i;

        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && s.charAt(i) == ' ') i++; }

        boolean eat(char c) {
            ws();
            if (i < s.length() && s.charAt(i) == c) { i++; return true; }
            return false;
        }

        boolean peek2(String t) { ws(); return s.startsWith(t, i); }

        Expr parseTernary() {
            Expr c = parseCompare();
            if (eat('?')) {
                Expr a = parseTernary();
                Expr b = eat(':') ? parseTernary() : ZERO;
                return t -> c.eval(t) != 0f ? a.eval(t) : b.eval(t);
            }
            return c;
        }

        Expr parseCompare() {
            Expr l = parseSum();
            while (true) {
                final Expr a = l;
                if (peek2("<=")) { i += 2; Expr r = parseSum(); l = t -> a.eval(t) <= r.eval(t) ? 1f : 0f; }
                else if (peek2(">=")) { i += 2; Expr r = parseSum(); l = t -> a.eval(t) >= r.eval(t) ? 1f : 0f; }
                else if (peek2("==")) { i += 2; Expr r = parseSum(); l = t -> a.eval(t) == r.eval(t) ? 1f : 0f; }
                else if (peek2("!=")) { i += 2; Expr r = parseSum(); l = t -> a.eval(t) != r.eval(t) ? 1f : 0f; }
                else if (peek2("<")) { i += 1; Expr r = parseSum(); l = t -> a.eval(t) < r.eval(t) ? 1f : 0f; }
                else if (peek2(">")) { i += 1; Expr r = parseSum(); l = t -> a.eval(t) > r.eval(t) ? 1f : 0f; }
                else return l;
            }
        }

        Expr parseSum() {
            Expr l = parseTerm();
            while (true) {
                final Expr a = l;
                if (eat('+')) { Expr r = parseTerm(); l = t -> a.eval(t) + r.eval(t); }
                else if (eat('-')) { Expr r = parseTerm(); l = t -> a.eval(t) - r.eval(t); }
                else return l;
            }
        }

        Expr parseTerm() {
            Expr l = parseUnary();
            while (true) {
                final Expr a = l;
                if (eat('*')) { Expr r = parseUnary(); l = t -> a.eval(t) * r.eval(t); }
                else if (eat('/')) { Expr r = parseUnary(); l = t -> { float d = r.eval(t); return d == 0f ? 0f : a.eval(t) / d; }; }
                else if (eat('%')) { Expr r = parseUnary(); l = t -> { float d = r.eval(t); return d == 0f ? 0f : a.eval(t) % d; }; }
                else return l;
            }
        }

        Expr parseUnary() {
            if (eat('-')) { Expr e = parseUnary(); return e instanceof Const c ? constant(-c.v) : t -> -e.eval(t); }
            if (eat('+')) return parseUnary();
            if (eat('!')) { Expr e = parseUnary(); return t -> e.eval(t) == 0f ? 1f : 0f; }
            return parsePrimary();
        }

        Expr parsePrimary() {
            ws();
            if (eat('(')) { Expr e = parseTernary(); eat(')'); return e; }
            char c = i < s.length() ? s.charAt(i) : 0;
            if ((c >= '0' && c <= '9') || c == '.') {
                int st = i;
                while (i < s.length() && ((s.charAt(i) >= '0' && s.charAt(i) <= '9') || s.charAt(i) == '.')) i++;
                if (i < s.length() && s.charAt(i) == 'f') i++; // 1.0f
                String n = s.substring(st, s.charAt(i - 1) == 'f' ? i - 1 : i);
                return constant(Float.parseFloat(n));
            }
            int st = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.')) i++;
            if (st == i) throw new IllegalArgumentException("unexpected " + c);
            String id = s.substring(st, i);
            if (eat('(')) {
                Expr[] args = new Expr[3];
                int n = 0;
                if (!eat(')')) {
                    do { Expr a = parseTernary(); if (n < 3) args[n++] = a; } while (eat(','));
                    eat(')');
                }
                return call(id, args, n);
            }
            return ident(id);
        }

        Expr ident(String id) {
            String n = id.replace("query.", "q.").replace("variable.", "v.").replace("math.", "m.");
            switch (n) {
                case "q.anim_time", "q.life_time", "q.time_stamp" -> { return t -> t; }
                case "m.pi" -> { return constant((float) Math.PI); }
                case "true" -> { return constant(1f); }
                default -> { return ZERO; }
            }
        }

        Expr call(String id, Expr[] a, int n) {
            String f = id.replace("math.", "").replace("m.", "");
            Expr x = n > 0 ? a[0] : ZERO, y = n > 1 ? a[1] : ZERO, z = n > 2 ? a[2] : ZERO;
            return switch (f) {
                case "sin" -> t -> (float) Math.sin(Math.toRadians(x.eval(t)));
                case "cos" -> t -> (float) Math.cos(Math.toRadians(x.eval(t)));
                case "asin" -> t -> (float) Math.toDegrees(Math.asin(x.eval(t)));
                case "acos" -> t -> (float) Math.toDegrees(Math.acos(x.eval(t)));
                case "atan" -> t -> (float) Math.toDegrees(Math.atan(x.eval(t)));
                case "atan2" -> t -> (float) Math.toDegrees(Math.atan2(x.eval(t), y.eval(t)));
                case "abs" -> t -> Math.abs(x.eval(t));
                case "sqrt" -> t -> (float) Math.sqrt(Math.max(0f, x.eval(t)));
                case "floor" -> t -> (float) Math.floor(x.eval(t));
                case "ceil" -> t -> (float) Math.ceil(x.eval(t));
                case "round" -> t -> (float) Math.round(x.eval(t));
                case "trunc" -> t -> { float v = x.eval(t); return v < 0 ? (float) Math.ceil(v) : (float) Math.floor(v); };
                case "min" -> t -> Math.min(x.eval(t), y.eval(t));
                case "max" -> t -> Math.max(x.eval(t), y.eval(t));
                case "pow" -> t -> (float) Math.pow(x.eval(t), y.eval(t));
                case "exp" -> t -> (float) Math.exp(x.eval(t));
                case "ln" -> t -> (float) Math.log(Math.max(1e-9f, x.eval(t)));
                case "mod" -> t -> { float d = y.eval(t); return d == 0f ? 0f : x.eval(t) % d; };
                case "clamp" -> t -> Math.max(y.eval(t), Math.min(z.eval(t), x.eval(t)));
                case "lerp" -> t -> { float u = z.eval(t); return x.eval(t) + (y.eval(t) - x.eval(t)) * u; };
                case "random", "random_integer", "die_roll", "hermite_blend" -> t -> 0.5f * (x.eval(t) + y.eval(t));
                default -> ZERO;
            };
        }
    }
}
