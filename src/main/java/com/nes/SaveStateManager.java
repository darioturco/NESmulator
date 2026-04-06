package com.nes;

import java.io.*;
import java.nio.file.*;

/**
 * Handles serialization of {@link SaveState} snapshots to the {@code save_states/}
 * folder on disk.
 *
 * File naming: {@code save_states/<rom-basename>_<slot>.sav}
 *
 * Example:
 * <pre>
 *   SaveStateManager.save(nes.captureState(), "roms/Zelda.nes", 1);
 *   SaveState s = SaveStateManager.load("roms/Zelda.nes", 1);
 *   if (s != null) nes.restoreState(s);
 * </pre>
 */
public final class SaveStateManager {

    private static final String SAVE_DIR = "save_states";

    private SaveStateManager() {}

    /**
     * Write a save state to disk.
     *
     * @param state   snapshot produced by {@link NES#captureState()}
     * @param romPath path to the ROM file — used to derive the save file name
     * @param slot    save slot index (1-based by convention)
     */
    public static void save(SaveState state, String romPath, int slot) {
        try {
            Files.createDirectories(Paths.get(SAVE_DIR));
            Path file = savePath(romPath, slot);
            try (ObjectOutputStream oos = new ObjectOutputStream(
                    new BufferedOutputStream(new FileOutputStream(file.toFile())))) {
                oos.writeObject(state);
            }
            System.out.printf("[SaveState] Saved slot %d → %s%n", slot, file);
        } catch (IOException e) {
            System.err.println("[SaveState] Save failed: " + e.getMessage());
        }
    }

    /**
     * Read a save state from disk.
     *
     * @param romPath path to the ROM file — used to derive the save file name
     * @param slot    save slot index
     * @return the loaded {@link SaveState}, or {@code null} if the file does not exist or fails
     */
    public static SaveState load(String romPath, int slot) {
        Path file = savePath(romPath, slot);
        if (!Files.exists(file)) {
            System.err.printf("[SaveState] No save file for slot %d (%s)%n", slot, file);
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(file.toFile())))) {
            SaveState state = (SaveState) ois.readObject();
            System.out.printf("[SaveState] Loaded slot %d ← %s%n", slot, file);
            return state;
        } catch (IOException | ClassNotFoundException e) {
            System.err.println("[SaveState] Load failed: " + e.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------

    private static Path savePath(String romPath, int slot) {
        String base = Paths.get(romPath).getFileName().toString()
                           .replaceFirst("\\.[^.]+$", ""); // strip extension
        return Paths.get(SAVE_DIR, base + "_" + slot + ".sav");
    }
}
