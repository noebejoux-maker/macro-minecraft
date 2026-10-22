package fr.macromouse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

/**
 * Macro de souris : enregistre les mouvements de caméra et les clics (attaque / utiliser),
 * puis les rejoue. Les 4 touches se règlent dans Options > Contrôles > Macro Souris.
 *
 * Les mouvements sont enregistrés de façon RELATIVE (de combien la caméra a tourné à chaque tick),
 * donc la macro refait le même geste quelle que soit la direction dans laquelle tu regardes.
 */
public class MacroMouseClient implements ClientModInitializer {
    public static final String MOD_ID = "macromouse";

    /** Durée maximale d'un enregistrement : 20 minutes (20 ticks par seconde). */
    private static final int MAX_FRAMES = 20 * 60 * 20;

    private enum Mode { IDLE, RECORDING, PLAYING }

    /** Ce qui s'est passé pendant un tick : rotation de la caméra + état des clics. */
    private record Frame(float dYaw, float dPitch, boolean attack, boolean use) {}

    private KeyMapping recordKey;
    private KeyMapping stopKey;
    private KeyMapping playKey;
    private KeyMapping loopKey;

    private Mode mode = Mode.IDLE;
    private boolean loop = false;

    private List<Frame> macro = new ArrayList<>();
    private List<Frame> recorded = new ArrayList<>();
    private float lastYaw;
    private float lastPitch;

    private int playIndex;
    private boolean attackHeld;
    private boolean useHeld;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));

        recordKey = register("record", InputConstants.KEY_R, category);
        stopKey = register("stop", InputConstants.KEY_V, category);
        playKey = register("play", InputConstants.KEY_B, category);
        loopKey = register("loop", InputConstants.KEY_N, category);

        loadFromDisk();
        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
    }

    private static KeyMapping register(String name, int defaultKey, KeyMapping.Category category) {
        return KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key." + MOD_ID + "." + name,
                InputConstants.Type.KEYSYM,
                defaultKey,
                category));
    }

    // ------------------------------------------------------------------
    // BOUCLE PRINCIPALE (1 fois par tick)
    // ------------------------------------------------------------------

    private void onTick(Minecraft mc) {
        LocalPlayer player = mc.player;

        if (player == null) {
            // plus de monde (déconnexion...) : on arrête proprement et on vide les touches
            if (mode != Mode.IDLE) {
                releaseKeys(mc);
                mode = Mode.IDLE;
            }
            while (recordKey.consumeClick()) { }
            while (stopKey.consumeClick()) { }
            while (playKey.consumeClick()) { }
            while (loopKey.consumeClick()) { }
            return;
        }

        while (recordKey.consumeClick()) {
            if (mode == Mode.RECORDING) {
                stopAll(mc, player);
            } else {
                startRecording(mc, player);
            }
        }
        while (stopKey.consumeClick()) {
            stopAll(mc, player);
        }
        while (playKey.consumeClick()) {
            if (mode == Mode.PLAYING) {
                stopAll(mc, player);
            } else {
                startPlayback(mc, player);
            }
        }
        while (loopKey.consumeClick()) {
            loop = !loop;
            say(player, "Boucle : " + (loop ? "activée" : "désactivée"));
        }

        if (mode == Mode.RECORDING) {
            recordTick(mc, player);
        } else if (mode == Mode.PLAYING) {
            playTick(mc, player);
        }
    }

    // ------------------------------------------------------------------
    // ENREGISTREMENT
    // ------------------------------------------------------------------

    private void startRecording(Minecraft mc, LocalPlayer player) {
        if (mode == Mode.PLAYING) {
            releaseKeys(mc);
        }
        mode = Mode.RECORDING;
        recorded = new ArrayList<>();
        lastYaw = player.getYRot();
        lastPitch = player.getXRot();
        say(player, "Enregistrement... bouge la souris et clique. Appuie sur la touche Stop pour finir.");
    }

    private void recordTick(Minecraft mc, LocalPlayer player) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        recorded.add(new Frame(
                yaw - lastYaw,
                pitch - lastPitch,
                mc.options.keyAttack.isDown(),
                mc.options.keyUse.isDown()));
        lastYaw = yaw;
        lastPitch = pitch;

        if (recorded.size() >= MAX_FRAMES) {
            say(player, "Durée maximale atteinte (20 minutes).");
            stopAll(mc, player);
        }
    }

    // ------------------------------------------------------------------
    // LECTURE
    // ------------------------------------------------------------------

    private void startPlayback(Minecraft mc, LocalPlayer player) {
        if (mode == Mode.RECORDING) {
            finishRecording(player);
        }
        if (macro.isEmpty()) {
            mode = Mode.IDLE;
            say(player, "Aucune macro enregistrée. Utilise d'abord la touche Enregistrer.");
            return;
        }
        mode = Mode.PLAYING;
        playIndex = 0;
        attackHeld = false;
        useHeld = false;
        say(player, "Lecture de la macro" + (loop ? " (en boucle)" : "") + "...");
    }

    private void playTick(Minecraft mc, LocalPlayer player) {
        if (playIndex >= macro.size()) {
            if (loop) {
                playIndex = 0;
            } else {
                releaseKeys(mc);
                mode = Mode.IDLE;
                say(player, "Lecture terminée.");
                return;
            }
        }
        Frame frame = macro.get(playIndex++);

        // rotation et clics de la même frame sont appliqués ensemble, comme à l'enregistrement
        player.setYRot(player.getYRot() + frame.dYaw());
        player.setXRot(Mth.clamp(player.getXRot() + frame.dPitch(), -90.0f, 90.0f));

        attackHeld = drive(mc.options.keyAttack, frame.attack(), attackHeld);
        useHeld = drive(mc.options.keyUse, frame.use(), useHeld);
    }

    /**
     * Simule l'appui / le relâchement d'un bouton.
     * À l'appui on fait comme le vrai jeu : "click" (pour les coups et poses uniques) + "down" (pour maintenir).
     */
    private static boolean drive(KeyMapping mapping, boolean wanted, boolean held) {
        if (wanted && !held) {
            try {
                KeyMapping.click(InputConstants.getKey(mapping.saveString()));
            } catch (RuntimeException ignored) {
                // si la touche ne peut pas être retrouvée, le simple "down" suffit pour casser des blocs
            }
            mapping.setDown(true);
        } else if (!wanted && held) {
            mapping.setDown(false);
        }
        return wanted;
    }

    // ------------------------------------------------------------------
    // ARRÊT
    // ------------------------------------------------------------------

    private void stopAll(Minecraft mc, LocalPlayer player) {
        if (mode == Mode.RECORDING) {
            finishRecording(player);
        } else if (mode == Mode.PLAYING) {
            releaseKeys(mc);
            say(player, "Lecture arrêtée.");
        }
        mode = Mode.IDLE;
    }

    private void finishRecording(LocalPlayer player) {
        if (recorded.isEmpty()) {
            say(player, "Enregistrement vide : l'ancienne macro est conservée.");
            return;
        }
        macro = recorded;
        recorded = new ArrayList<>();
        saveToDisk();
        say(player, "Macro enregistrée : " + macro.size() + " ticks (" + (macro.size() / 20) + " s).");
    }

    /** Relâche les boutons que la macro maintenait. */
    private void releaseKeys(Minecraft mc) {
        if (attackHeld) {
            mc.options.keyAttack.setDown(false);
            attackHeld = false;
        }
        if (useHeld) {
            mc.options.keyUse.setDown(false);
            useHeld = false;
        }
    }

    // ------------------------------------------------------------------
    // SAUVEGARDE (la macro est conservée après redémarrage du jeu)
    // ------------------------------------------------------------------

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("macromouse.txt");
    }

    private void saveToDisk() {
        List<String> lines = new ArrayList<>(macro.size());
        for (Frame f : macro) {
            lines.add(f.dYaw() + " " + f.dPitch() + " " + (f.attack() ? 1 : 0) + " " + (f.use() ? 1 : 0));
        }
        try {
            Files.write(file(), lines);
        } catch (IOException e) {
            System.err.println("[macromouse] Impossible de sauvegarder la macro : " + e);
        }
    }

    private void loadFromDisk() {
        Path path = file();
        if (!Files.exists(path)) {
            return;
        }
        try {
            List<Frame> loaded = new ArrayList<>();
            for (String line : Files.readAllLines(path)) {
                String[] p = line.trim().split(" ");
                if (p.length < 4) {
                    continue;
                }
                loaded.add(new Frame(
                        Float.parseFloat(p[0]),
                        Float.parseFloat(p[1]),
                        "1".equals(p[2]),
                        "1".equals(p[3])));
            }
            macro = loaded;
        } catch (IOException | RuntimeException e) {
            System.err.println("[macromouse] Impossible de charger la macro : " + e);
        }
    }

    // ------------------------------------------------------------------

    private static void say(LocalPlayer player, String text) {
        player.sendSystemMessage(Component.literal("[Macro] " + text));
    }
}
