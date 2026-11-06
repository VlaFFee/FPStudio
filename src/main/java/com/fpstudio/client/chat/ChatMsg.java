package com.fpstudio.client.chat;

import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** One received message, already restyled, with its icon and a cached word-wrap. */
public final class ChatMsg {
    public final Component text;      // the message without timestamp
    public final String icon;         // icon spec ("" = none)
    public final long time;           // System.currentTimeMillis()
    public final int nameRgb;         // accent colour for the bar style

    // layout cache
    List<FormattedCharSequence> lines;
    int cacheVer = -1, cacheW = -1;
    float cacheScale;
    int height, textW;
    float cacheTextScale;
    boolean[] fxLine;     // which wrapped lines contain effects / gifs
    // typewriter
    int[] cps;            // code points of the wrapped text
    float[] sched;        // seconds (since arrival) at which each character appears
    int[] lineOff, lineLen;
    float typeTotal;
    int lastRevealed;
    int blipCount;
    /** smoothed vertical position (glide animation); NaN = not placed yet */
    float shownY = Float.NaN;

    public ChatMsg(Component text, String icon, long time, int nameRgb) {
        this.text = text;
        this.icon = icon == null ? "" : icon;
        this.time = time;
        this.nameRgb = nameRgb;
    }
}
