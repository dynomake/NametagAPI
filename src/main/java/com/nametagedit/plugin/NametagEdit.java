package com.nametagedit.plugin;

import com.nametagedit.plugin.api.INametagApi;
import com.nametagedit.plugin.api.NametagAPI;
import com.nametagedit.plugin.invisibility.InvisibilityTask;
import com.nametagedit.plugin.packets.PacketWrapper;
import com.nametagedit.plugin.packets.VersionChecker;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;

/**
 * TODO:
 * - Better uniform message format + more messages
 * - Code cleanup
 * - Add language support
 */
@Getter
public class NametagEdit extends JavaPlugin {

    private static NametagEdit instance;

    private static INametagApi api;

    private NametagHandler handler;
    private NametagManager manager;
    private VersionChecker.BukkitVersion version;

    public static INametagApi getApi() {
        return api;
    }

    @Override
    public void onEnable() {
        testCompat();
        if (!isEnabled()) return;

        instance = this;

        version = VersionChecker.getBukkitVersion();

        getLogger().info("Successfully loaded using bukkit version: " + version.name());

        manager = new NametagManager(this);
        handler = new NametagHandler(this, manager);

        PluginManager pluginManager = Bukkit.getPluginManager();
        if (api == null) {
            api = new NametagAPI(handler, manager);
        }

        if (version.name().startsWith("v1_8_"))
            new InvisibilityTask().runTaskTimerAsynchronously(this, 100L, 20L);
    }

    public static NametagEdit getInstance(){
        return instance;
    }

    @Override
    public void onDisable() {
        manager.reset();
    }

    void debug(String message) {
        if (handler != null && handler.debug()) {
            getLogger().info("[DEBUG] " + message);
        }
    }

    private void testCompat() {
        PacketWrapper wrapper = new PacketWrapper("TEST", "&f", "", 0, new ArrayList<>(), true);
        wrapper.send();
        if (wrapper.error == null) return;
        Bukkit.getPluginManager().disablePlugin(this);
        getLogger().severe("\n------------------------------------------------------\n" +
                "[WARNING] NametagEdit v" + getDescription().getVersion() + " Failed to load! [WARNING]" +
                "\n------------------------------------------------------" +
                "\nThis might be an issue with reflection. REPORT this:\n> " +
                wrapper.error +
                "\nThe plugin will now self destruct.\n------------------------------------------------------");
    }

}