package com.fpstudio.client.chat;

import com.fpstudio.FPStudio;
import com.fpstudio.chat.ChatCfg;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Your own fonts and sounds without building a resource pack by hand: drop TTF/OTF files into config/fpstudio/chat_fonts and
 * OGG files into config/fpstudio/chat_sounds. They are copied into a generated resource pack (resourcepacks/FPStudio_Assets)
 * that the mod enables itself.
 */
public final class UserAssets {
    public static final String NS = "fpstudio_user";
    private static final String PACK = "FPStudio_Assets";
    private static boolean autoDone;

    private UserAssets() {}

    public static Path fontsDir() { return ChatCfg.dir().resolve("chat_fonts"); }

    public static Path soundsDir() { return ChatCfg.dir().resolve("chat_sounds"); }

    private static Path packDir() { return FMLPaths.GAMEDIR.get().resolve("resourcepacks").resolve(PACK); }

    /** Create the folders (with a short README) on first launch. */
    public static void ensureDirs() {
        try {
            Files.createDirectories(fontsDir());
            Files.createDirectories(soundsDir());
            Files.createDirectories(ChatCfg.iconDir());
            Files.createDirectories(ChatGifs.dir());
            readme(ChatGifs.dir().resolve("README.txt"), "Put .gif files here. Use them in chat text as <gif=NAME> (NAME = file name without .gif, lower case) or as a message icon gif:NAME.\n"
                    + "\u041a\u043b\u0430\u0434\u0438\u0442\u0435 .gif \u0441\u044e\u0434\u0430. \u0412 \u0442\u0435\u043a\u0441\u0442\u0435 \u0447\u0430\u0442\u0430: <gif=\u0438\u043c\u044f>, \u0432 \u0438\u043a\u043e\u043d\u043a\u0435: gif:\u0438\u043c\u044f.\n");
            readme(fontsDir().resolve("README.txt"), "Put .ttf / .otf font files here (a font with Cyrillic if you need Russian text).\n"
                    + "Then open the chat editor (U) -> Font -> \"Apply fonts and sounds\" (or just restart the game).\n"
                    + "Your fonts appear in the font list as " + NS + ":<file name>.\n"
                    + "Положите сюда файлы шрифтов .ttf / .otf, затем в редакторе чата (U) → «Шрифт» → «Применить» или перезапустите игру.\n");
            readme(soundsDir().resolve("README.txt"), "Put short .ogg sounds (mono, Vorbis) here: they become blip sounds " + NS + ":blip.<file name>\n"
                    + "usable in the chat editor (Print tab). Apply like fonts.\n"
                    + "Короткие .ogg (моно) для звука печати: id будет " + NS + ":blip.имя_файла\n");
        } catch (IOException ignored) {
            // read-only folder: the feature just stays empty
        }
    }

    private static void readme(Path p, String text) throws IOException {
        if (!Files.exists(p)) Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    private static List<Path> files(Path dir, String... exts) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(Files::isRegularFile).filter(p -> {
                String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                for (String e : exts) if (n.endsWith(e)) return true;
                return false;
            }).sorted().forEach(out::add);
        } catch (IOException ignored) {
            // unreadable folder
        }
        return out;
    }

    private static String stem(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return (i > 0 ? n.substring(0, i) : n).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    /** Sound ids found in the sounds folder, e.g. fpstudio_user:blip.alice */
    public static List<String> soundIds() {
        List<String> out = new ArrayList<>();
        for (Path p : files(soundsDir(), ".ogg")) out.add(NS + ":blip." + stem(p));
        return out;
    }

    public static List<String> fontIds() {
        List<String> out = new ArrayList<>();
        for (Path p : files(fontsDir(), ".ttf", ".otf")) out.add(NS + ":" + stem(p));
        return out;
    }

    private static String signature(ChatCfg cfg, List<Path> fonts, List<Path> sounds) throws IOException {
        StringBuilder b = new StringBuilder("v1;").append(cfg.ttfSize).append(';').append(cfg.ttfShiftY).append(';').append(cfg.ttfOversample).append(';');
        for (Path p : fonts) b.append(p.getFileName()).append(':').append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(';');
        b.append('|');
        for (Path p : sounds) b.append(p.getFileName()).append(':').append(Files.size(p)).append(':').append(Files.getLastModifiedTime(p).toMillis()).append(';');
        return b.toString();
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /** Rebuild the generated pack if the files changed. Returns true when something was written. */
    public static boolean sync(ChatCfg cfg) {
        try {
            ensureDirs();
            List<Path> fonts = files(fontsDir(), ".ttf", ".otf"), sounds = files(soundsDir(), ".ogg");
            Path pack = packDir();
            Path sigFile = pack.resolve(".fpsig");
            String sig = signature(cfg, fonts, sounds);
            if (Files.exists(sigFile) && Files.readString(sigFile, StandardCharsets.UTF_8).equals(sig)) return false;
            if (fonts.isEmpty() && sounds.isEmpty() && !Files.exists(pack)) return false;
            deleteTree(pack.resolve("assets"));
            Files.createDirectories(pack);
            Files.writeString(pack.resolve("pack.mcmeta"),
                    "{\"pack\":{\"pack_format\":15,\"description\":\"FP Studio: your chat fonts and sounds\"}}", StandardCharsets.UTF_8);
            Path fontOut = pack.resolve("assets").resolve(NS).resolve("font");
            Files.createDirectories(fontOut);
            for (Path f : fonts) {
                String name = stem(f), ext = f.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".otf") ? ".otf" : ".ttf";
                Files.copy(f, fontOut.resolve(name + ext), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                String json = "{\"providers\":[{\"type\":\"ttf\",\"file\":\"" + NS + ":" + name + ext + "\",\"shift\":[0.0," + cfg.ttfShiftY
                        + "],\"size\":" + cfg.ttfSize + ",\"oversample\":" + cfg.ttfOversample + "},"
                        + "{\"type\":\"reference\",\"id\":\"minecraft:include/default\"},{\"type\":\"reference\",\"id\":\"minecraft:include/unifont\"}]}";
                Files.writeString(fontOut.resolve(name + ".json"), json, StandardCharsets.UTF_8);
            }
            if (!sounds.isEmpty()) {
                Path soundOut = pack.resolve("assets").resolve(NS).resolve("sounds").resolve("blip");
                Files.createDirectories(soundOut);
                StringBuilder sj = new StringBuilder("{");
                boolean first = true;
                for (Path f : sounds) {
                    String name = stem(f);
                    Files.copy(f, soundOut.resolve(name + ".ogg"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    if (!first) sj.append(',');
                    first = false;
                    sj.append("\"blip.").append(name).append("\":{\"sounds\":[\"").append(NS).append(":blip/").append(name).append("\"]}");
                }
                sj.append('}');
                Files.writeString(pack.resolve("assets").resolve(NS).resolve("sounds.json"), sj.toString(), StandardCharsets.UTF_8);
            }
            Files.writeString(sigFile, sig, StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            FPStudio.LOGGER.warn("fpstudio: could not build the user assets pack", e);
            return false;
        }
    }

    /** Make sure the generated pack is selected and reload resources (render thread). */
    public static void enableAndReload() {
        Minecraft mc = Minecraft.getInstance();
        try {
            PackRepository repo = mc.getResourcePackRepository();
            repo.reload();
            String id = "file/" + PACK;
            if (repo.getAvailableIds().contains(id) && !repo.getSelectedIds().contains(id)) {
                List<String> sel = new ArrayList<>(repo.getSelectedIds());
                sel.add(id);
                repo.setSelected(sel);
                mc.options.updateResourcePacks(repo);
            }
        } catch (RuntimeException e) {
            FPStudio.LOGGER.warn("fpstudio: could not enable the user assets pack", e);
        }
        mc.reloadResourcePacks();
    }

    /** Editor button: rebuild and reload. */
    public static String applyNow(ChatCfg cfg) {
        boolean changed = sync(cfg);
        enableAndReload();
        return changed ? I18n.get("fpstudio.gui.chat.assets_applied") : I18n.get("fpstudio.gui.chat.assets_nochange");
    }

    /** At the title screen after launch: folders are created, and the pack is built and enabled if it needs to be. */
    public static void autoOnce() {
        if (autoDone) return;
        autoDone = true;
        ensureDirs();
        if (sync(ChatCfg.get())) enableAndReload();
        else {
            try {
                Minecraft mc = Minecraft.getInstance();
                String id = "file/" + PACK;
                mc.getResourcePackRepository().reload();
                if (Files.exists(packDir().resolve("pack.mcmeta")) && !mc.getResourcePackRepository().getSelectedIds().contains(id)) enableAndReload();
            } catch (RuntimeException ignored) {
                // pack stays as it is
            }
        }
    }

    public static void openFolder(Path p) {
        try {
            Files.createDirectories(p);
            net.minecraft.Util.getPlatform().openFile(p.toFile());
        } catch (IOException ignored) {
            // nothing to open
        }
    }
}
