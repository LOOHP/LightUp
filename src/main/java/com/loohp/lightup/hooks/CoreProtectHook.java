package com.loohp.lightup.hooks;

import net.coreprotect.CoreProtect;
import net.coreprotect.CoreProtectAPI;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

public class CoreProtectHook {

    private static boolean checkedApi = false;
    private static CoreProtectAPI api = null;

    public static CoreProtectAPI getCoreProtect() {
        if (api != null) {
            return api;
        } else if (!checkedApi) {
            checkedApi = true;

            Plugin plugin = Bukkit.getPluginManager().getPlugin("CoreProtect");

            // Check that CoreProtect is loaded
            if (!(plugin instanceof CoreProtect)) {
                return null;
            }

            // Check that the API is enabled
            CoreProtectAPI coreProtect = ((CoreProtect) plugin).getAPI();
            if (!coreProtect.isEnabled()) {
                return null;
            }

            // Check that a compatible version of the API is loaded
            if (coreProtect.APIVersion() < 9) {
                return null;
            }

            return api = coreProtect;
        }
        return null;
    }

    public static boolean logPlacement(String user, Location location, Material type, BlockData blockData) {
        CoreProtectAPI api = getCoreProtect();
        if (api == null) {
            return false;
        }
        return api.logPlacement(user, location, type, blockData);
    }

    public static boolean logRemoval(String user, Location location, Material type, BlockData blockData) {
        CoreProtectAPI api = getCoreProtect();
        if (api == null) {
            return false;
        }
        return api.logRemoval(user, location, type, blockData);
    }

}
