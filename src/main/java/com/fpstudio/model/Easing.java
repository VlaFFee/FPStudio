package com.fpstudio.model;

/** Easing applied to the segment that STARTS at a keyframe. SPLINE = smooth Hermite curve through neighbours. */
public enum Easing {
    LINEAR, SMOOTH, EASE_IN, EASE_OUT, EASE_IN_OUT, BACK_OUT, ELASTIC_OUT, BOUNCE_OUT, STEP, SPLINE;

    public float apply(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        switch (this) {
            case SMOOTH:
                return t * t * (3f - 2f * t);
            case EASE_IN:
                return t * t * t;
            case EASE_OUT: {
                float u = 1f - t;
                return 1f - u * u * u;
            }
            case EASE_IN_OUT:
                return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
            case BACK_OUT: {
                float c1 = 1.70158f, c3 = c1 + 1f, u = t - 1f;
                return 1f + c3 * u * u * u + c1 * u * u;
            }
            case ELASTIC_OUT: {
                float c4 = (float) (2 * Math.PI / 3);
                return (float) (Math.pow(2, -10 * t) * Math.sin((t * 10 - 0.75) * c4) + 1);
            }
            case BOUNCE_OUT: {
                float n1 = 7.5625f, d1 = 2.75f;
                if (t < 1 / d1) return n1 * t * t;
                if (t < 2 / d1) { t -= 1.5f / d1; return n1 * t * t + 0.75f; }
                if (t < 2.5 / d1) { t -= 2.25f / d1; return n1 * t * t + 0.9375f; }
                t -= 2.625f / d1;
                return n1 * t * t + 0.984375f;
            }
            case STEP:
                return 0f;
            case LINEAR:
            case SPLINE:
            default:
                return t;
        }
    }

    public Easing step(int d) {
        Easing[] v = values();
        return v[(ordinal() + d + v.length) % v.length];
    }
}
