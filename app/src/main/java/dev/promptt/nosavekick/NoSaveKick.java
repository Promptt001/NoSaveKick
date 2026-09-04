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
        Path playerDataFile = overworld.getWorldFolder().toPath()
                .resolve("playerdata")
                .resolve(uuid + ".dat");

        boolean hadExistingFile = Files.exists(playerDataFile);
        Path backupFile = getDataFolder().toPath()
                .resolve(getConfig().getString("rollback.backup-directory", "rollback"))
                .resolve(uuid + ".dat.bak");

        // 1) BACKUP: copy the current on-disk file before the kick triggers a save.
        if (hadExistingFile) {
            try {
                Files.createDirectories(backupFile.getParent());
                Files.copy(playerDataFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                // Abort: if we can't make a backup, we can't safely roll back.
                getLogger().severe("Failed to back up player data for " + uuid + ": " + e.getMessage());
                return;
            }
        } else {
            // Ensure no stale backup remains.
            try {
                Files.deleteIfExists(backupFile);
            } catch (IOException ignored) {
            }
        }

        // 2) KICK: server will save player state during disconnect.
        player.kick(component(reason));

        // 3) RESTORE: after a short delay, overwrite the saved file with the backup.
        int delayTicks = getConfig().getInt("rollback.delay-ticks", 5);
        int maxAttempts = Math.max(1, getConfig().getInt("rollback.max-retries", 5));
        int retryDelayTicks = Math.max(1, getConfig().getInt("rollback.retry-delay-ticks", 5));

        Bukkit.getScheduler().runTaskLater(this, () ->
                        scheduleRestoreAttempt(uuid, playerDataFile, backupFile, hadExistingFile, 1, maxAttempts, retryDelayTicks),
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
            @NotNull Path backupFile,
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
                        Files.deleteIfExists(backupFile);
                        getLogger().info("Rolled back playerdata for " + uuid);
                    } else {
                        getLogger().warning("Backup missing for " + uuid + "; nothing to restore.");
                    }
                } else {
                    // Player had no previous on-disk state; delete the newly created file to avoid saving.
                    Files.deleteIfExists(playerDataFile);
                    getLogger().info("Deleted new playerdata file for " + uuid + " (no prior save existed)");
                }
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    getLogger().warning("Restore attempt " + attempt + "/" + maxAttempts + " failed for " + uuid
                            + ": " + e.getMessage() + " (retrying)");
                    Bukkit.getScheduler().runTaskLater(this,
                            () -> scheduleRestoreAttempt(uuid, playerDataFile, backupFile, hadExistingFile,
                                    attempt + 1, maxAttempts, retryDelayTicks),
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
