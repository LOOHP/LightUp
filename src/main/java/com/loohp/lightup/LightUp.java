package com.loohp.lightup;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.loohp.lightup.hooks.CoreProtectHook;
import dev.jorel.commandapi.CommandAPICommand;
import dev.jorel.commandapi.arguments.BlockStateArgument;
import dev.jorel.commandapi.arguments.BooleanArgument;
import dev.jorel.commandapi.arguments.IntegerArgument;
import dev.jorel.commandapi.arguments.StringArgument;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

public class LightUp extends JavaPlugin implements Listener {

    public static final Map<UUID, Future<?>> playerTask = new ConcurrentHashMap<>();
    public static final Map<UUID, List<List<Location>>> playerUndo = new ConcurrentHashMap<>();
    public static final ExecutorService service = Executors.newCachedThreadPool(new ThreadFactoryBuilder().setNameFormat("LightUp Task Thread #%d").build());

    public static LightUp lightUp;

    public String messageReload;
    public String messageOnlyPlayerCommand;
    public String messageNoActiveTask;
    public String messageNothingToUndo;
    public String messageWaitComplete;
    public String messageLightUpBegin;
    public String messageLightUpComplete;
    public String messageLightUpCancelled;
    public String messageUndoUnloadedWorld;
    public String messageUndoComplete;

    public boolean progressActionBarEnabled;
    public String progressActionBarFormatting;

    @Override
    public void onEnable() {
        lightUp = this;

        getConfig().options().copyDefaults(true);
        saveConfig();
        loadConfig();

        registerCommands();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getConsoleSender().sendMessage(ChatColor.GREEN + "LightUp has been enabled!");

        for (Player player : Bukkit.getOnlinePlayers()) {
            playerUndo.put(player.getUniqueId(), Collections.synchronizedList(new LinkedList<>()));
        }
    }

    @Override
    public void onDisable() {
        service.shutdown();
        getServer().getConsoleSender().sendMessage(ChatColor.RED + "LightUp has been disabled!");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        playerUndo.put(event.getPlayer().getUniqueId(), Collections.synchronizedList(new LinkedList<>()));
    }

    public enum LightUpType {
        SURFACE, CAVE, ALL;
    }

    public void loadConfig() {
        reloadConfig();
        messageReload = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.Reload"));
        messageOnlyPlayerCommand = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.OnlyPlayerCommand"));
        messageNoActiveTask = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.NoActiveTask"));
        messageNothingToUndo = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.NothingToUndo"));
        messageWaitComplete = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.WaitComplete"));
        messageLightUpBegin = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.LightUpBegin"));
        messageLightUpComplete = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.LightUpComplete"));
        messageLightUpCancelled = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.LightUpCancelled"));
        messageUndoUnloadedWorld = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.UndoUnloadedWorld"));
        messageUndoComplete = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Messages.UndoComplete"));

        progressActionBarEnabled = getConfig().getBoolean("Options.ProgressActionBar.Enabled");
        progressActionBarFormatting = ChatColor.translateAlternateColorCodes('&', getConfig().getString("Options.ProgressActionBar.Formatting"));
    }

    public void registerCommands() {
        new CommandAPICommand("lightup")
            .withAliases("lu")
            .withSubcommand(new CommandAPICommand("reload")
                                .withPermission("lightup.reload")
                                .executes((sender, args) -> {
                                    getServer().getScheduler().runTask(this, () -> {
                                        loadConfig();
                                        sender.sendMessage(messageReload);
                                    });
                                }))
            .withSubcommand(new CommandAPICommand("cancel")
                                .withPermission("lightup.cancel")
                                .executes((sender, args) -> {
                                    if (sender instanceof Player) {
                                        Player player = (Player) sender;
                                        Future<?> task = playerTask.get(player.getUniqueId());
                                        if (task == null) {
                                            player.sendMessage(messageNoActiveTask);
                                        } else {
                                            task.cancel(true);
                                        }
                                    } else {
                                        sender.sendMessage(messageOnlyPlayerCommand);
                                    }
                                }))
            .withSubcommand(new CommandAPICommand("undo")
                                .withPermission("lightup.undo")
                                .executes((sender, args) -> {
                                    if (sender instanceof Player) {
                                        Player player = (Player) sender;
                                        List<List<Location>> undos = playerUndo.get(player.getUniqueId());
                                        if (undos.isEmpty()) {
                                            player.sendMessage(messageNothingToUndo);
                                        } else {
                                            undo(player);
                                        }
                                    } else {
                                        sender.sendMessage(messageOnlyPlayerCommand);
                                    }
                                }))
            .withPermission("lightup.use")
            .withArguments(
                new BlockStateArgument("block"),
                new IntegerArgument("min_light_level", 0, 15).replaceWithSafeSuggestions(info -> IntStream.range(0, 16).boxed().toArray(Integer[]::new)),
                new IntegerArgument("range", 0).includeWithSafeSuggestions(info -> new Integer[] {50}),
                new BooleanArgument("include_skylight"),
                new StringArgument("lightup_type").replaceSuggestions(info -> Arrays.stream(LightUpType.values()).map(each -> each.name().toLowerCase()).toArray(String[]::new)))
            .executes((sender, args) -> {
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    BlockData blockData = (BlockData) args[0];
                    int minLightLevel = (int) args[1];
                    int distanceMax = (int) args[2];
                    boolean includeSkylight = (boolean) args[3];
                    LightUpType type;
                    try {
                        type = LightUpType.valueOf(((String) args[4]).toUpperCase());
                    } catch (IllegalArgumentException e) {
                        type = LightUpType.ALL;
                    }
                    lightUp(player, blockData, player.getLocation(), minLightLevel, distanceMax, includeSkylight, type);
                } else {
                    sender.sendMessage(messageOnlyPlayerCommand);
                }
            }).register();
    }

    public void lightUp(Player player, BlockData blockData, Location origin, int minLightLevel, int distanceMax, boolean includeSkylight, LightUpType type) {
        if (playerTask.get(player.getUniqueId()) != null) {
            player.sendMessage(messageWaitComplete);
            return;
        }
        Future<?> task = service.submit(() -> {
            try {
                player.sendMessage(messageLightUpBegin);
                int minimumLightLevel = Math.min(15, minLightLevel);
                Queue<Block> blocks = getServer().getScheduler().callSyncMethod(this, () -> collectBlocks(origin, distanceMax)).get();
                int totalBlocks = blocks.size();
                int totalPlaced = 0;
                boolean continueQueue;
                List<Location> placementRecord = new LinkedList<>();
                playerUndo.get(player.getUniqueId()).add(placementRecord);
                do {
                    CompletableFuture<Block> future = new CompletableFuture<>();
                    getServer().getScheduler().runTaskLater(this, () -> future.complete(continueLightUp(player, blocks, blockData, minimumLightLevel, includeSkylight, type)), 1);
                    Block block = future.get();
                    continueQueue = block != null;
                    if (continueQueue) {
                        placementRecord.add(block.getLocation());
                        totalPlaced++;
                        if (progressActionBarEnabled) {
                            int percentage = Math.round((1F - ((float) blocks.size() / (float) totalBlocks)) * 100F);
                            String format = progressActionBarFormatting.replace("{ScannedBlocks}", "" + (totalBlocks - blocks.size())).replace("{TotalBlocks}", "" + totalBlocks).replace("{PlacedLights}", "" + totalPlaced).replace("{CompletedPercentage}", "" + percentage);
                            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(format));
                        }
                    }
                } while (continueQueue);
                if (progressActionBarEnabled) {
                    String format = progressActionBarFormatting.replace("{ScannedBlocks}", "" + totalBlocks).replace("{TotalBlocks}", "" + totalBlocks).replace("{PlacedLights}", "" + totalPlaced).replace("{CompletedPercentage}", "100");
                    player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(format));
                }
                player.sendMessage(messageLightUpComplete.replace("{TotalBlocks}", "" + totalBlocks).replace("{TotalPlaced}", "" + totalPlaced));
            } catch (InterruptedException | ExecutionException e) {
                player.sendMessage(messageLightUpCancelled);
            }
            playerTask.remove(player.getUniqueId());
        });
        playerTask.put(player.getUniqueId(), task);
    }

    @SuppressWarnings("ConstantConditions")
    public Queue<Block> collectBlocks(Location origin, int distanceMax) {
        Queue<Block> blocks = new LinkedList<>();
        World world = origin.getWorld();
        for (int y = Math.max(origin.getBlockY() - distanceMax, world.getMinHeight()); y <= Math.min(origin.getBlockY() + distanceMax, world.getMaxHeight() - 1); y++) {
            for (int x = origin.getBlockX() - distanceMax; x <= origin.getBlockX() + distanceMax; x++) {
                for (int z = origin.getBlockZ() - distanceMax; z <= origin.getBlockZ() + distanceMax; z++) {
                    blocks.add(world.getBlockAt(x, y, z));
                }
            }
        }
        return blocks;
    }

    public Block continueLightUp(Player player, Queue<Block> blocks, BlockData blockData, int minLightLevel, boolean includeSkylight, LightUpType type) {
        Block block;
        while ((block = blocks.poll()) != null) {
            Material down = block.getRelative(BlockFace.DOWN).getType();
            boolean validType = type.equals(LightUpType.ALL) || type.equals(LightUpType.SURFACE) && hasSkyAccess(block, 0) || type.equals(LightUpType.CAVE) && !hasSkyAccess(block, 7);
            if (down.isSolid() && down.isOccluding() && !down.equals(Material.BEDROCK) && block.getType().isAir() && validType) {
                if (includeSkylight) {
                    if (block.getLightLevel() < minLightLevel) {
                        BlockData placedBlockData = blockData.clone();
                        block.setBlockData(placedBlockData);
                        CoreProtectHook.logPlacement(player.getName(), block.getLocation(), placedBlockData.getMaterial(), placedBlockData);
                        return block;
                    }
                } else {
                    if (block.getLightFromBlocks() < minLightLevel) {
                        BlockData placedBlockData = blockData.clone();
                        block.setBlockData(placedBlockData);
                        CoreProtectHook.logPlacement(player.getName(), block.getLocation(), placedBlockData.getMaterial(), placedBlockData);
                        return block;
                    }
                }
            }
        }
        return null;
    }

    public boolean hasSkyAccess(Block block, int minLight) {
        return block.getLightFromSky() > minLight;
    }

    public void undo(Player player) {
        if (playerTask.get(player.getUniqueId()) != null) {
            player.sendMessage(messageWaitComplete);
            return;
        }
        List<List<Location>> undos = playerUndo.get(player.getUniqueId());
        List<Location> locations = undos.remove(undos.size() - 1);
        getServer().getScheduler().runTask(this, () -> {
            if (!locations.isEmpty() && !locations.get(0).isWorldLoaded()) {
                undos.add(locations);
                player.sendMessage(messageUndoUnloadedWorld);
                return;
            }
            for (Location location : locations) {
                Block block = location.getBlock();
                CoreProtectHook.logRemoval(player.getName(), block.getLocation(), block.getType(), block.getBlockData());
                block.setType(Material.AIR);
            }
            player.sendMessage(messageUndoComplete.replace("{BlocksUndone}", "" + locations.size()));
        });
    }

}
