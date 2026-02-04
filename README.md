# NoSaveKick

**NoSaveKick** is a small admin utility plugin for **Paper/Spigot** servers that lets you kick an online player **without keeping the “just-saved” playerdata**.

Minecraft servers normally save a player’s data (inventory, location, health, etc.) when they disconnect—**including when they are kicked**. There is no supported Bukkit/Paper API to cancel that save. NoSaveKick uses a pragmatic workaround: it restores the player’s on-disk `playerdata/<uuid>.dat` file after the kick occurs.

---

## What “without saving” means

This plugin does **not** stop the server from writing the player’s data during disconnect (that part is hard-coded in the server). Instead, it:

1. **Backs up** the current on-disk file (`playerdata/<uuid>.dat`) *before* the kick.
2. **Kicks** the player (server writes the current in-memory state to disk).
3. **Restores** the on-disk file from the backup (rolling back that save).

Net effect: after the kick, the player’s vanilla `.dat` file is reverted to the last disk state that existed before the kick.

---

## Requirements

- **Server:** Paper or Spigot compatible with **Minecraft 1.21** (API version in `plugin.yml` is `1.21`).
- **Java:** **Java 21** (project is compiled for Java 21).
- **Build tool (optional):** Maven

---

## Installation

### Option A: Use the built JAR

1. Build the plugin (see “Building from source”), or use an already-built JAR.
2. Drop the JAR into your server’s `plugins/` folder.
3. Restart the server.

On first start, the plugin will create:

- `plugins/NoSaveKick/config.yml`

### Option B: Build from source

```bash
git clone <your repo url>
cd NoSaveKick
mvn -U clean package
```

The output JAR will be in:

```
target/NoSaveKick-1.0.jar
```

Copy that file to your server’s `plugins/` folder.

---

## Commands

### `/nosavekick <player> [reason]`

Kicks an **online** player and rolls back their vanilla `playerdata` file to the last on-disk state.

**Examples:**

```text
/nosavekick Steve
/nosavekick Steve Desynced inventory; please relog.
```

**Notes:**

- The target must be online (`Bukkit.getPlayer(...)` is used).
- If `reason` is omitted, the configured default is used.

---

## Permissions

- `nosavekick.use` — allows use of `/nosavekick` (defaults to **op**)

---

## Configuration

File: `plugins/NoSaveKick/config.yml`

```yaml
rollback:
  delay-ticks: 5
  max-retries: 5
  retry-delay-ticks: 5
  backup-directory: rollback

messages:
  no-permission: "&cYou do not have permission."
  usage: "&cUsage: /nosavekick <player> [reason]"
  player-not-found: "&cPlayer not found (must be online)."
  default-reason: "Connection reset by peer."
  kicked: "&aKicked {player} without saving."
```

### `rollback.delay-ticks`

How long to wait (in ticks) after kicking the player before attempting the rollback.

- `20 ticks = 1 second`
- If you see occasional “restore failed” messages, increasing this value is the first thing to try.

### `rollback.max-retries` / `rollback.retry-delay-ticks`

Rollback runs asynchronously and may fail briefly if the server is still writing the `.dat` file. These settings implement simple retry logic.

### `rollback.backup-directory`

The folder (inside `plugins/NoSaveKick/`) used for short-lived backups. Backups are deleted after a successful restore.

### Message settings

All messages support `&` color codes. `{player}` is replaced with the player’s name in the “kicked” message.

---

## How it works (internal)

The plugin’s entire behavior is implemented in one class:

- `com.yourname.nosavekick.NoSaveKick`

The core sequence is:

1. Resolve the “main” overworld folder (first loaded world with `World.Environment.NORMAL`).
2. Build the path: `<worldFolder>/playerdata/<uuid>.dat`.
3. If the file exists, copy it to: `plugins/NoSaveKick/<backup-directory>/<uuid>.dat.bak`.
4. Kick the player.
5. After `delay-ticks`, attempt restore/delete on a background thread:
   - If a backup existed: overwrite `playerdata/<uuid>.dat` with the backup.
   - If no prior file existed (brand new player): delete the newly-created file to avoid saving.
6. If the restore/delete fails, retry up to `max-retries` times.

---

## Operational notes and edge cases

### Multiworld setups

Vanilla playerdata is normally stored in the server’s primary overworld folder (`level-name`). This plugin chooses the first loaded world with `Environment.NORMAL` as the best approximation.

If your server uses a nonstandard setup where playerdata is stored elsewhere, you may need to adjust the code.

### “Bad save” window

There is a small window between the kick and the restore where the server has written the “bad” in-memory state to disk.

- If the player rejoins *immediately* and the rollback has not happened yet, they may load the bad state.
- Mitigation: increase `rollback.delay-ticks` a bit and/or increase retry settings.

### Other plugins’ data

NoSaveKick only touches the vanilla `.dat` file in `playerdata/`. If another plugin persists player state somewhere else (database, separate files, custom inventories, economies, etc.), this plugin **will not** roll that back.

### Disk permissions

The server process must have permission to read/write the world folder and the plugin data folder.

---

## Troubleshooting

### “Backup missing … nothing to restore”

This usually means the player had no existing `playerdata/<uuid>.dat` at the moment of kicking (e.g., very first join and never saved). In that case the plugin attempts to delete the newly created file instead.

### “Restore failed … retrying”

This commonly happens when the server is still writing the `.dat` file.

Try:

1. Increase `rollback.delay-ticks` (e.g., 10–40)
2. Increase `rollback.max-retries`
3. Increase `rollback.retry-delay-ticks`

### Player still has the “bad” state

Possible causes:

- They rejoined before rollback completed.
- Their important data is stored by another plugin (not in vanilla playerdata).
- Your primary world folder isn’t the one being targeted.

---

## Development notes

### Package / coordinates

This project uses placeholder coordinates:

- `groupId`: `com.yourname`
- package: `com.yourname.nosavekick`

You should change these to your real namespace before publishing.

### Recommended repo hygiene

If you put this on GitHub, consider adding a `.gitignore` that excludes build output:

```gitignore
/target/
*.iml
.idea/
.classpath
.project
.settings/
```

---

## License

Add a license file if you plan to distribute this publicly.
