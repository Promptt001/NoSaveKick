package dev.promptt.nosavekick;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.UUID;

/**
 * NoSaveKick
 * <p>
 * A small admin utility plugin that "kicks without saving" by restoring the player's on-disk
 * {@code playerdata/<uuid>.dat} file after the kick occurs.
 * <p>
 * Bukkit/Paper servers will normally save player data during the disconnect flow
 * (kick, quit, timeout, etc.). There is no supported API to cancel that save. Instead,
 * this plugin implements a pragmatic workaround:
 * <ol>
 *   <li>Back up the player's current on-disk {@code .dat} file (the "last known good" state).</li>
 *   <li>Kick the player (the server writes the current in-memory state to disk).</li>
 *   <li>After a short delay, overwrite the newly-saved file with the backup (rollback).</li>
 * </ol>
 *
 * Important limitations:
 * <ul>
 *   <li>This restores only vanilla playerdata saved in the world folder. Any data saved elsewhere
 *       by other plugins (databases, custom files, inventories from other plugins, etc.) is not affected.</li>
 *   <li>If the player rejoins <em>before</em> the rollback happens, they may briefly load the "bad" state.
 *       Increasing {@code rollback.delay-ticks} helps, at the cost of slower rollback.</li>
 *   <li>File I/O is best-effort; the restore includes retries in case the file is still being written.</li>
 * </ul>
 */
public final class NoSaveKick extends JavaPlugin implements CommandExecutor {

    private static final String COMMAND_NAME = "nosavekick";
    private static final String PERMISSION_USE = "nosavekick.use";

    @Override
    public void onEnable() {
        // Creates config.yml on first run.
        saveDefaultConfig();

        PluginCommand command = getCommand(COMMAND_NAME);
        if (command == null) {
            getLogger().severe("Command '/" + COMMAND_NAME + "' is missing from plugin.yml; plugin will not work.");
            return;
        }
        command.setExecutor(this);
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!sender.hasPermission(PERMISSION_USE)) {
            sender.sendMessage(component(getConfig().getString("messages.no-permission", "&cYou do not have permission.")));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(component(getConfig().getString("messages.usage", "&cUsage: /nosavekick <player> [reason]")));
            return true;
        }

        // Only online players can be kicked via this command.
        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(component(getConfig().getString("messages.player-not-found", "&cPlayer not found (must be online).")));
            return true;
        }

        String reason;
        if (args.length > 1) {
            reason = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        } else {
            reason = getConfig().getString("messages.default-reason", "Connection reset by peer.");
        }

        rollbackKick(target, reason);

        String feedback = getConfig().getString("messages.kicked", "&aKicked {player} without saving.")
                .replace("{player}", target.getName());
        sender.sendMessage(component(feedback));
        return true;
    }

    /**
     * Executes the backup → kick → restore sequence.
     *
     * @param player target player (must be online)
     * @param reason kick message shown to the player
     */
    private void rollbackKick(@NotNull Player player, @NotNull String reason) {
        World overworld = resolveOverworld();
        if (overworld == null) {
            getLogger().severe("No worlds are loaded; cannot resolve playerdata folder.");
            return;
        }

        UUID uuid = player.getUniqueId();
        Path playerDataDir = resolvePlayerDataDir(overworld, uuid);
        if (playerDataDir == null) {
            getLogger().severe("Could not resolve the playerdata directory; cannot roll back " + uuid + ".");
            return;
        }
        Path playerDataFile = playerDataDir.resolve(uuid + ".dat");
        Path playerDataOldFile = playerDataDir.resolve(uuid + ".dat_old");

        boolean hadExistingFile = Files.exists(playerDataFile);
        Path backupDir = getDataFolder().toPath()
                .resolve(getConfig().getString("rollback.backup-directory", "rollback"));
        Path backupFile = backupDir.resolve(uuid + ".dat.bak");
        Path backupOldFile = backupDir.resolve(uuid + ".dat_old.bak");

        // 1) BACKUP: copy the current on-disk files before the kick triggers a save.
        if (hadExistingFile) {
            try {
                Files.createDirectories(backupDir);
                Files.copy(playerDataFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
                // The disconnect-save rotates the previous .dat into .dat_old (Util.safeReplaceFile),
                // so back that up too in order to restore the exact pre-kick pair of files.
                if (Files.exists(playerDataOldFile)) {
                    Files.copy(playerDataOldFile, backupOldFile, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(backupOldFile);
                }
            } catch (IOException e) {
                // Abort: if we can't make a backup, we can't safely roll back.
                getLogger().severe("Failed to back up player data for " + uuid + " from " + playerDataDir
                        + ": " + e.getMessage());
                return;
            }
        } else {
            // Ensure no stale backups remain.
            try {
                Files.deleteIfExists(backupFile);
                Files.deleteIfExists(backupOldFile);
            } catch (IOException ignored) {
            }
        }

        // 2) KICK: server will save player state during disconnect.
        player.kick(component(reason));

        // 3) RESTORE: after a short delay, overwrite the saved files with the backups.
        int delayTicks = getConfig().getInt("rollback.delay-ticks", 5);
        int maxAttempts = Math.max(1, getConfig().getInt("rollback.max-retries", 5));
        int retryDelayTicks = Math.max(1, getConfig().getInt("rollback.retry-delay-ticks", 5));

        Bukkit.getScheduler().runTaskLater(this, () ->
                        scheduleRestoreAttempt(uuid, playerDataFile, playerDataOldFile, backupFile, backupOldFile,
                                playerDataDir, hadExistingFile, 1, maxAttempts, retryDelayTicks),
                delayTicks);
    }

    /**
     * Attempts to restore the player's .dat file on a background thread.
     * If an I/O error occurs (commonly because the server is still writing the file),
     * the attempt is retried a few times.
     */
    private void scheduleRestoreAttempt(
            @NotNull UUID uuid,
            @NotNull Path playerDataFile,
            @NotNull Path playerDataOldFile,
            @NotNull Path backupFile,
            @NotNull Path backupOldFile,
            @NotNull Path playerDataDir,
            boolean hadExistingFile,
            int attempt,
            int maxAttempts,
            int retryDelayTicks
    ) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                if (hadExistingFile) {
                    if (Files.exists(backupFile)) {
                        Files.copy(backupFile, playerDataFile, StandardCopyOption.REPLACE_EXISTING);
                        // Restore the pre-kick .dat_old too; the kick-save rotated the previous
                        // .dat into it, so leaving the post-kick one would keep stale data around.
                        if (Files.exists(backupOldFile)) {
                            Files.copy(backupOldFile, playerDataOldFile, StandardCopyOption.REPLACE_EXISTING);
                        } else {
                            Files.deleteIfExists(playerDataOldFile);
                        }
                        Files.deleteIfExists(backupFile);
                        Files.deleteIfExists(backupOldFile);
                        getLogger().info("Rolled back playerdata for " + uuid + " in " + playerDataDir);
                    } else {
                        getLogger().warning("Backup missing for " + uuid + "; nothing to restore.");
                    }
                } else {
                    // Player had no previous on-disk state; delete the newly created files to avoid saving.
                    Files.deleteIfExists(playerDataFile);
                    Files.deleteIfExists(playerDataOldFile);
                    getLogger().info("Deleted new playerdata file for " + uuid + " in " + playerDataDir
                            + " (no prior save existed)");
                }
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    getLogger().warning("Restore attempt " + attempt + "/" + maxAttempts + " failed for " + uuid
                            + ": " + e.getMessage() + " (retrying)");
                    Bukkit.getScheduler().runTaskLater(this,
                            () -> scheduleRestoreAttempt(uuid, playerDataFile, playerDataOldFile, backupFile, backupOldFile,
                                    playerDataDir, hadExistingFile, attempt + 1, maxAttempts, retryDelayTicks),
                            retryDelayTicks);
                } else {
                    getLogger().severe("Restore failed after " + maxAttempts + " attempts for " + uuid
                            + ": " + e.getMessage());
                }
            }
        });
    }

    /**
     * Resolves the "main" overworld folder where {@code playerdata/} normally lives.
     *
     * <p>On most servers, this is the first world with {@link World.Environment#NORMAL}.
     * If none exist, we fall back to the first loaded world.</p>
     */
    /**
     * Resolves the directory where the server stores {@code <uuid>.dat} player files.
     *
     * <p>Minecraft 26.x moved player data from {@code <world>/playerdata/} to
     * {@code <world>/players/data/} (with siblings {@code players/stats} and
     * {@code players/advancements}). Older versions (1.21.x and earlier) still use
     * {@code playerdata/}.</p>
     *
     * <p>Additionally, on 26.x {@link World#getWorldFolder()} returns the per-dimension
     * folder (e.g. {@code <world>/dimensions/minecraft/overworld}) while the server's
     * PlayerDataStorage writes at the level-storage root (e.g. {@code <world>}). The
     * resolver therefore climbs up the folder hierarchy, preferring the directory that
     * actually contains the player's {@code <uuid>.dat} file, falling back to the first
     * existing directory matching the running server's layout.</p>
     *
     * @param world the "main" overworld
     * @param uuid the target player's UUID, used to locate the exact directory
     * @return the playerdata directory, or {@code null} if none can be located
     */
    private Path resolvePlayerDataDir(@NotNull World world, @NotNull UUID uuid) {
        // On Paper 26.x, World.getWorldFolder() returns the per-dimension folder
        // (e.g. ./world/dimensions/minecraft/overworld) while the server's PlayerDataStorage
        // lives at the level-storage root (e.g. ./world). Climb up from the world folder
        // until we find the folder that actually holds this player's .dat file.
        Path folder = world.getWorldFolder().toPath().toAbsolutePath().normalize();
        boolean modernServer = supportsModernPlayerDataLayout();
        Path fallback = null;
        for (Path dir = folder; dir != null; dir = dir.getParent()) {
            Path modern = dir.resolve("players").resolve("data");
            Path legacy = dir.resolve("playerdata");
            // Prefer the directory that actually contains this player's file.
            if (Files.isRegularFile(modern.resolve(uuid + ".dat"))) {
                return modern;
            }
            if (Files.isRegularFile(legacy.resolve(uuid + ".dat"))) {
                return legacy;
            }
            // Track the first existing directory of the matching layout, in case the
            // player's file has never been saved yet (first join before first autosave).
            if (fallback == null) {
                if (modernServer && Files.isDirectory(modern)) {
                    fallback = modern;
                } else if (!modernServer && Files.isDirectory(legacy)) {
                    fallback = legacy;
                }
            }
        }
        return fallback;
    }

    /**
     * Detects whether the running server uses the 26.x {@code players/data} layout.
     *
     * <p>The calendar-versioned Minecraft line (26.1/26.2, i.e. API versions "26.1"/"26.2" and up)
     * renamed the playerdata directory. Bukkit {@code api-version} strings are numeric-comparable
     * for these versions, so we compare against "26.1".</p>
     *
     * @return true if the server stores player data under {@code players/data}
     */
    private boolean supportsModernPlayerDataLayout() {
        String apiVersion = getServer().getBukkitVersion();
        return compareApiVersions(apiVersion, "26.1") >= 0;
    }

    /**
     * Compares two Bukkit-style version strings numerically per dot-separated component,
     * treating non-numeric suffixes (e.g. "1.21.1-R0.2-SNAPSHOT" or "26.2.build.121-stable")
     * as the numeric prefix of each component.
     */
    private static int compareApiVersions(@NotNull String left, @NotNull String right) {
        int cmp = 0;
        String[] a = left.split("[-.]"), b = right.split("[-.]");
        int n = Math.max(a.length, b.length);
        for (int i = 0; i < n && cmp == 0; i++) {
            int x = leadingInt(i < a.length ? a[i] : "0");
            int y = leadingInt(i < b.length ? b[i] : "0");
            cmp = Integer.compare(x, y);
        }
        return cmp;
   } 

    private static int leadingInt(@NotNull String s) {
        int end = 0;
        while (end < s.length() && Character.isDigit(s.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        return Integer.parseInt(s.substring(0, end));
    }

    private World resolveOverworld() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world;
            }
        }
        return Bukkit.getWorlds().isEmpty() ? null : Bukkit.getWorlds().getFirst();
    }

    /**
     * Converts a legacy {@code &}-color-coded config string into an Adventure {@link Component}.
     */
    private static Component component(@NotNull String input) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(input);
    }
}
