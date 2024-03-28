package com.nametagedit.plugin;

import com.nametagedit.plugin.api.data.GroupData;
import com.nametagedit.plugin.api.data.INametag;
import com.nametagedit.plugin.api.data.PlayerData;
import com.nametagedit.plugin.api.events.NametagEvent;
import com.nametagedit.plugin.api.events.NametagFirstLoadedEvent;
import com.nametagedit.plugin.utils.Configuration;
import com.nametagedit.plugin.utils.UUIDFetcher;
import com.nametagedit.plugin.utils.Utils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Getter
@Setter
public class NametagHandler implements Listener {

    // Multiple threads access resources. We need to make sure we avoid concurrency issues.
    private ReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    public static boolean DISABLE_PUSH_ALL_TAGS = false;
    private boolean debug;
    @Getter(AccessLevel.NONE)
    private boolean tabListEnabled;
    private boolean longNametagsEnabled;
    private boolean refreshTagOnWorldChange;

    private BukkitTask clearEmptyTeamTask;
    private BukkitTask refreshNametagTask;

    private Configuration config;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private List<GroupData> groupData = new ArrayList<>();

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private Map<UUID, PlayerData> playerData = new HashMap<>();

    private NametagEdit plugin;
    private NametagManager nametagManager;

    public NametagHandler(NametagEdit plugin, NametagManager nametagManager) {
        this.config = getCustomConfig(plugin);
        this.plugin = plugin;
        this.nametagManager = nametagManager;
        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Apply config properties
        this.applyConfig();
    }

    /**
     * This function loads our custom config with comments, and includes changes
     */
    private Configuration getCustomConfig(Plugin plugin) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        if (!file.exists()) {
            plugin.saveDefaultConfig();

            Configuration newConfig = new Configuration(file);
            newConfig.reload(true);
            return newConfig;
        } else {
            Configuration oldConfig = new Configuration(file);
            oldConfig.reload(false);

            file.delete();
            plugin.saveDefaultConfig();

            Configuration newConfig = new Configuration(file);
            newConfig.reload(true);

            for (String key : oldConfig.getKeys(false)) {
                if (newConfig.contains(key)) {
                    newConfig.set(key, oldConfig.get(key));
                }
            }

            newConfig.save();
            return newConfig;
        }
    }

    /**
     * Cleans up any nametag data on the server to prevent memory leaks
     */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        nametagManager.reset(event.getPlayer().getName());
    }

    /**
     * Applies tags to a player
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        nametagManager.sendTeams(player);
    }

    /**
     * Some users may have different permissions per world.
     * If this is enabled, their tag will be reloaded on TP.
     */
    @EventHandler
    public void onTeleport(final PlayerChangedWorldEvent event) {
        if (!refreshTagOnWorldChange) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                applyTagToPlayer(event.getPlayer(), false);
            }
        }.runTaskLater(plugin, 3);
    }

    private void handleClear(UUID uuid, String player) {
        removePlayerData(uuid);
        nametagManager.reset(player);
    }

    public void clearMemoryData() {
        try {
            readWriteLock.writeLock().lock();
            groupData.clear();
            playerData.clear();
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    public void removePlayerData(UUID uuid) {
        try {
            readWriteLock.writeLock().lock();
            playerData.remove(uuid);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    public void storePlayerData(UUID uuid, PlayerData data) {
        try {
            readWriteLock.writeLock().lock();
            playerData.put(uuid, data);
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    public void assignGroupData(List<GroupData> groupData) {
        try {
            readWriteLock.writeLock().lock();
            this.groupData = groupData;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    public void assignData(List<GroupData> groupData, Map<UUID, PlayerData> playerData) {
        try {
            readWriteLock.writeLock().lock();
            this.groupData = groupData;
            this.playerData = playerData;
        } finally {
            readWriteLock.writeLock().unlock();
        }
    }

    // ==========================================
    // Below are methods used by the API/Commands
    // ==========================================
    boolean debug() {
        return debug;
    }

    void toggleDebug() {
        debug = !debug;
        config.set("Debug", debug);
        config.save();
    }

    void toggleLongTags() {
        longNametagsEnabled = !longNametagsEnabled;
        config.set("Tablist.LongTags", longNametagsEnabled);
        config.save();
    }

    // =================================================
    // Below are methods that we have to be careful with
    // as they can be called from different threads
    // =================================================
    public PlayerData getPlayerData(Player player) {
        return player == null ? null : playerData.get(player.getUniqueId());
    }

    /**
     * Replaces placeholders when a player tag is created.
     * Maxim and Clip's plugins are searched for, and input
     * is replaced. We use direct imports to avoid any problems!
     * (So don't change that)
     */
    public String formatWithPlaceholders(Player player, String input, boolean limitChars) {
        plugin.debug("Formatting text..");
        if (input == null) return "";
        if (player == null) return input;

        plugin.debug("Applying colors..");
        return Utils.format(input, limitChars);
    }

    private BukkitTask createTask(String path, BukkitTask existing, Runnable runnable) {
        if (existing != null) {
            existing.cancel();
        }

        if (config.getInt(path, -1) <= 0) return null;
        return Bukkit.getScheduler().runTaskTimer(plugin, runnable, 0, 20L * config.getInt(path));
    }

    public void reload() {
        config.reload(true);
        applyConfig();
        nametagManager.reset();
    }

    private void applyConfig() {
        this.debug = config.getBoolean("Debug");
        this.tabListEnabled = config.getBoolean("Tablist.Enabled");
        this.longNametagsEnabled = config.getBoolean("Tablist.LongTags");
        this.refreshTagOnWorldChange = config.getBoolean("RefreshTagOnWorldChange");
        DISABLE_PUSH_ALL_TAGS = config.getBoolean("DisablePush");

        clearEmptyTeamTask = createTask("ClearEmptyTeamsInterval", clearEmptyTeamTask, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "nte teams clear"));

        refreshNametagTask = createTask("RefreshInterval", refreshNametagTask, () -> {
            nametagManager.reset();
            applyTags();
        });
    }

    public void applyTags() {
        if (!Bukkit.isPrimaryThread()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    applyTags();
                }
            }.runTask(plugin);
            return;
        }

        for (Player online : Utils.getOnline()) {
            if (online != null) {
                applyTagToPlayer(online, false);
            }
        }

        plugin.debug("Applied tags to all online players.");
    }

    public void applyTagToPlayer(final Player player, final boolean loggedIn) {
        // If on the primary thread, run async
        if (Bukkit.isPrimaryThread()) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    applyTagToPlayer(player, loggedIn);
                }
            }.runTaskAsynchronously(plugin);
            return;
        }

        INametag tempNametag = getPlayerData(player);

        if (tempNametag == null) return;
        plugin.debug("Applying " + (tempNametag.isPlayerTag() ? "PlayerTag" : "GroupTag") + " to " + player.getName());

        final INametag nametag = tempNametag;
        new BukkitRunnable() {
            @Override
            public void run() {
                nametagManager.setNametag(player.getName(), formatWithPlaceholders(player, nametag.getPrefix(), true),
                        formatWithPlaceholders(player, nametag.getSuffix(), true), nametag.getSortPriority());
                // If the TabList is disabled...
                if (!tabListEnabled) {
                    // apply the default white username to the player.
                    player.setPlayerListName(Utils.format("&f" + player.getPlayerListName()));
                } else {
                    if (longNametagsEnabled) {
                        player.setPlayerListName(formatWithPlaceholders(player, nametag.getPrefix() + player.getName() + nametag.getSuffix(), false));
                    } else {
                        player.setPlayerListName(null);
                    }
                }

                if (loggedIn) {
                    Bukkit.getPluginManager().callEvent(new NametagFirstLoadedEvent(player, nametag));
                }
            }
        }.runTask(plugin);
    }

    void clear(final CommandSender sender, final String player) {
        Player target = Bukkit.getPlayerExact(player);
        if (target != null) {
            handleClear(target.getUniqueId(), player);
            return;
        }

        UUIDFetcher.lookupUUID(player, plugin, uuid -> {
            if (uuid == null) {
                NametagMessages.UUID_LOOKUP_FAILED.send(sender);
            } else {
                handleClear(uuid, player);
            }
        });
    }

    void save(CommandSender sender, boolean playerTag, String key, int priority) {
        if (playerTag) {
            Player player = Bukkit.getPlayerExact(key);

            PlayerData data = getPlayerData(player);
            if (data == null)
                return;

            data.setSortPriority(priority);
        }
    }

    public void save(String targetName, NametagEvent.ChangeType changeType, String value) {
        save(null, targetName, changeType, value);
    }

    // Reduces checks to have this method (ie not saving data twice)
    public void save(String targetName, String prefix, String suffix) {
        Player player = Bukkit.getPlayerExact(targetName);

        PlayerData data = getPlayerData(player);
        if (data == null) {
            data = new PlayerData(targetName, null, "", "", -1);
            if (player != null) {
                storePlayerData(player.getUniqueId(), data);
            }
        }

        data.setPrefix(prefix);
        data.setSuffix(suffix);

        if (player != null) {
            applyTagToPlayer(player, false);
            data.setUuid(player.getUniqueId());
            return;
        }

        final PlayerData finalData = data;
        UUIDFetcher.lookupUUID(targetName, plugin, (uuid) -> {
            if (uuid != null) {
                storePlayerData(uuid, finalData);
                finalData.setUuid(uuid);
            }
        });
    }

    void save(final CommandSender sender, String targetName, NametagEvent.ChangeType changeType, String value) {
        Player player = Bukkit.getPlayerExact(targetName);

        PlayerData data = getPlayerData(player);
        if (data == null) {
            data = new PlayerData(targetName, null, "", "", -1);
            if (player != null) {
                storePlayerData(player.getUniqueId(), data);
            }
        }

        if (changeType == NametagEvent.ChangeType.PREFIX) {
            data.setPrefix(value);
        } else {
            data.setSuffix(value);
        }

        if (player != null) {
            applyTagToPlayer(player, false);
            data.setUuid(player.getUniqueId());
            return;
        }

        final PlayerData finalData = data;
        UUIDFetcher.lookupUUID(targetName, plugin, (uuid) -> {
            if (uuid == null && sender != null) { // null is passed in api
                NametagMessages.UUID_LOOKUP_FAILED.send(sender);
            }
            else {
                storePlayerData(uuid, finalData);
                finalData.setUuid(uuid);
            }
        });
    }


}