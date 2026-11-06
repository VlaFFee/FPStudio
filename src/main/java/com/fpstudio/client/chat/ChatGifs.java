package com.fpstudio.client.chat;

import com.fpstudio.FPStudio;
import com.fpstudio.chat.ChatCfg;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Animated GIFs for the chat: put NAME.gif into config/fpstudio/chat_gifs. Use them in text as {@code <gif=NAME>}
 * (or {@code <gif=NAME,16>} for a size in pixels) or as a message icon {@code gif:NAME}.
 * All frames are packed into one texture atlas.
 */
public final class ChatGifs {
    /** One loaded gif. */
    public static final class Gif {
        final ResourceLocation tex;
        final int cols, cw, ch, stride, aw, ah, n;
        final int[] end;       // cumulative end time of each frame in ms
        final int total;
        public final int srcW, srcH;

        Gif(ResourceLocation tex, int cols, int cw, int ch, int aw, int ah, int n, int[] end, int srcW, int srcH) {
            this.tex = tex; this.cols = cols; this.cw = cw; this.ch = ch; this.stride = cw + 2; this.aw = aw; this.ah = ah;
            this.n = n; this.end = end; this.total = end[n - 1]; this.srcW = srcW; this.srcH = srcH;
        }

        int frameAt(long ms) {
            if (n == 1 || total <= 0) return 0;
            int t = (int) (ms % total);
            for (int i = 0; i < n; i++) if (t < end[i]) return i;
            return n - 1;
        }
    }

    private static final Map<String, Gif> cache = new HashMap<>();
    private static final List<ResourceLocation> uploaded = new ArrayList<>();
    private static int counter;

    private ChatGifs() {}

    public static Path dir() { return ChatCfg.dir().resolve("chat_gifs"); }

    public static String clean(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\.gif$", "").replaceAll("[^a-z0-9._-]", "_");
    }

    public static void reload() {
        Minecraft mc = Minecraft.getInstance();
        for (ResourceLocation rl : uploaded) mc.getTextureManager().release(rl);
        uploaded.clear();
        cache.clear();
    }

    /** Names of the gif files in the folder (cleaned, without extension). */
    public static List<String> list() {
        List<String> out = new ArrayList<>();
        try {
            Files.createDirectories(dir());
            try (Stream<Path> s = Files.list(dir())) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".gif")).sorted()
                        .forEach(n -> out.add(clean(n)));
            }
        } catch (IOException ignored) {
            // no folder
        }
        return out;
    }

    private static Path find(String name) throws IOException {
        if (!Files.isDirectory(dir())) return null;
        try (Stream<Path> s = Files.list(dir())) {
            return s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gif"))
                    .filter(p -> clean(p.getFileName().toString()).equals(name)).findFirst().orElse(null);
        }
    }

    public static Gif get(String rawName) {
        String name = clean(rawName);
        if (name.isEmpty()) return null;
        if (cache.containsKey(name)) return cache.get(name);
        Gif g = null;
        try {
            Path p = find(name);
            if (p != null) g = load(p, name);
        } catch (IOException | RuntimeException e) {
            FPStudio.LOGGER.warn("fpstudio: cannot read gif {}", name, e);
        }
        cache.put(name, g);
        return g;
    }

    private static Node child(Node parent, String name) {
        for (Node c = parent.getFirstChild(); c != null; c = c.getNextSibling()) if (c.getNodeName().equals(name)) return c;
        return null;
    }

    private static int attr(Node n, String key, int def) {
        if (n == null) return def;
        NamedNodeMap a = n.getAttributes();
        Node v = a == null ? null : a.getNamedItem(key);
        if (v == null) return def;
        try { return Integer.parseInt(v.getNodeValue()); } catch (NumberFormatException e) { return def; }
    }

    private static Gif load(Path file, String name) throws IOException {
        List<BufferedImage> frames = new ArrayList<>();
        List<Integer> delays = new ArrayList<>();
        int W, H;
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName("gif");
            if (!it.hasNext()) throw new IOException("no gif reader in this Java");
            ImageReader r = it.next();
            try {
                r.setInput(in, false);
                int count = Math.min(r.getNumImages(true), 150);
                if (count <= 0) throw new IOException("empty gif");
                W = 0; H = 0;
                try {
                    IIOMetadata sm = r.getStreamMetadata();
                    Node sroot = sm == null ? null : sm.getAsTree("javax_imageio_gif_stream_1.0");
                    Node d = sroot == null ? null : child(sroot, "LogicalScreenDescriptor");
                    W = attr(d, "logicalScreenWidth", 0);
                    H = attr(d, "logicalScreenHeight", 0);
                } catch (RuntimeException ignored) {
                    // use the first frame size
                }
                if (W <= 0 || H <= 0) { BufferedImage f0 = r.read(0); W = f0.getWidth(); H = f0.getHeight(); }
                BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2 = canvas.createGraphics();
                int prevDisp = 0, px = 0, py = 0, pw = 0, ph = 0;
                BufferedImage saved = null;
                for (int i = 0; i < count; i++) {
                    BufferedImage fr = r.read(i);
                    IIOMetadata md = r.getImageMetadata(i);
                    Node root = md.getAsTree("javax_imageio_gif_image_1.0");
                    Node desc = child(root, "ImageDescriptor");
                    Node gce = child(root, "GraphicControlExtension");
                    int left = attr(desc, "imageLeftPosition", 0), top = attr(desc, "imageTopPosition", 0);
                    int delay = attr(gce, "delayTime", 10);
                    String disp = "none";
                    if (gce != null && gce.getAttributes().getNamedItem("disposalMethod") != null) disp = gce.getAttributes().getNamedItem("disposalMethod").getNodeValue();
                    int dm = disp.equals("restoreToBackgroundColor") ? 2 : disp.equals("restoreToPrevious") ? 3 : 0;
                    // dispose of the previous frame
                    if (prevDisp == 2) {
                        g2.setComposite(AlphaComposite.Clear);
                        g2.fillRect(px, py, pw, ph);
                        g2.setComposite(AlphaComposite.SrcOver);
                    } else if (prevDisp == 3 && saved != null) {
                        g2.setComposite(AlphaComposite.Src);
                        g2.drawImage(saved, 0, 0, null);
                        g2.setComposite(AlphaComposite.SrcOver);
                    }
                    if (dm == 3) {
                        saved = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D sg = saved.createGraphics();
                        sg.drawImage(canvas, 0, 0, null);
                        sg.dispose();
                    }
                    g2.drawImage(fr, left, top, null);
                    BufferedImage snap = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D sg = snap.createGraphics();
                    sg.drawImage(canvas, 0, 0, null);
                    sg.dispose();
                    frames.add(snap);
                    delays.add(Math.max(2, delay) * 10);     // 1/100 s -> ms, browsers treat tiny delays as 100ms; keep 20ms min
                    prevDisp = dm; px = left; py = top; pw = fr.getWidth(); ph = fr.getHeight();
                }
                g2.dispose();
            } finally {
                r.dispose();
            }
        }
        int n = frames.size();
        float sc = Math.min(1f, 128f / Math.max(W, H));
        int cw = Math.max(1, Math.round(W * sc)), ch = Math.max(1, Math.round(H * sc));
        int cols = (int) Math.ceil(Math.sqrt(n)), rows = (int) Math.ceil(n / (double) cols);
        int stride = cw + 2, strideH = ch + 2;
        int aw = cols * stride, ah = rows * strideH;
        NativeImage ni = new NativeImage(NativeImage.Format.RGBA, aw, ah, true);
        for (int y = 0; y < ah; y++) for (int x = 0; x < aw; x++) ni.setPixelRGBA(x, y, 0);
        int[] end = new int[n];
        int acc = 0;
        for (int i = 0; i < n; i++) {
            BufferedImage fb = frames.get(i);
            if (sc < 1f) {
                BufferedImage sm = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
                Graphics2D sg = sm.createGraphics();
                sg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                sg.drawImage(fb, 0, 0, cw, ch, null);
                sg.dispose();
                fb = sm;
            }
            int ox = (i % cols) * stride + 1, oy = (i / cols) * strideH + 1;
            for (int y = 0; y < ch; y++) {
                for (int x = 0; x < cw; x++) {
                    int argb = fb.getRGB(x, y);
                    int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
                    ni.setPixelRGBA(ox + x, oy + y, abgr);
                }
            }
            acc += delays.get(i);
            end[i] = acc;
        }
        DynamicTexture dt = new DynamicTexture(ni);
        dt.setFilter(true, false);
        ResourceLocation rl = new ResourceLocation(FPStudio.MODID, "chat_gif/" + name + "_" + (counter++));
        Minecraft.getInstance().getTextureManager().register(rl, dt);
        uploaded.add(rl);
        return new Gif(rl, cols, cw, ch, aw, ah, n, end, W, H);
    }

    /** Draw the current frame into the box x,y,w,h. */
    public static boolean draw(GuiGraphics g, String name, float x, float y, float w, float h, float alpha) {
        Gif gif = get(name);
        if (gif == null) return false;
        int f = gif.frameAt(System.currentTimeMillis());
        int sx = (f % gif.cols) * gif.stride + 1, sy = (f / gif.cols) * (gif.ch + 2) + 1;
        g.pose().pushPose();
        g.pose().translate(x, y, 0f);
        g.pose().scale(w / gif.cw, h / gif.ch, 1f);
        g.setColor(1f, 1f, 1f, alpha);
        g.blit(gif.tex, 0, 0, gif.cw, gif.ch, sx, sy, gif.cw, gif.ch, gif.aw, gif.ah);
        g.setColor(1f, 1f, 1f, 1f);
        g.pose().popPose();
        return true;
    }

    /** width/height of the gif, for keeping the aspect ratio (0 if unknown) */
    public static float aspect(String name) {
        Gif gif = get(name);
        return gif == null ? 1f : gif.srcH / (float) Math.max(1, gif.srcW);
    }
}
