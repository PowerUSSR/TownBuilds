package org.mineserver.townbuilds;

import com.palmergames.bukkit.towny.event.TownAddResidentEvent;
import com.palmergames.bukkit.towny.event.TownRemoveResidentEvent;
import com.palmergames.bukkit.towny.object.Resident;
import com.palmergames.bukkit.towny.object.Town;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class TownBuildsPlugin extends JavaPlugin implements Listener {

    private static final String MENU_TITLE = ChatColor.DARK_GREEN + "Прокачки города";
    private static final String UPGRADE_MENU_TITLE = ChatColor.GOLD + "Прокачка: Инвентарь Города";
    private static final String LUMBER_UPGRADE_MENU_TITLE = ChatColor.GOLD + "Прокачка: Лесопилка";
    private static final String QUARRY_UPGRADE_MENU_TITLE = ChatColor.GOLD + "Прокачка: Каменоломня";
    private static final String DECOR_UPGRADE_MENU_TITLE = ChatColor.GOLD + "Прокачка: Декорационные блоки";
    private static final String ORES_UPGRADE_MENU_TITLE = ChatColor.GOLD + "Прокачка: Руды";
    private static final String TREASURY_UPGRADE_MENU_TITLE = ChatColor.GOLD + "Прокачка: Казна";
    private static final String TOWN_HALL_UPGRADE_MENU_TITLE = ChatColor.GOLD + "Прокачка: Улучшения Ратуши";
    private static final String MINING_UPGRADE_MENU_TITLE = ChatColor.GOLD + "Прокачка: Добыча ископаемых";
    private static final String TAILOR_UPGRADE_MENU_TITLE = ChatColor.GOLD + "Прокачка: Швейная мастерская";
    private static final int MENU_SIZE = 27;
    private static final String TOWN_INV_TITLE_PREFIX = ChatColor.DARK_AQUA + "Склад города: ";
    private static final int TOWN_INV_SIZE = 54;
    private static final int MAX_LEVEL = 3;
    private static final long PRODUCTION_INTERVAL_MS = 4L * 60L * 60L * 1000L;
    private static final long TREASURY_INTERVAL_MS = 4L * 60L * 60L * 1000L;
    private static final long UPKEEP_REFUND_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    // Стоимость содержания за чанк (соответствует price_town_upkeep в Towny config)
    private static final double TOWNY_UPKEEP_PER_BLOCK = 1.0;

    private final Map<Integer, Upgrade> upgradesBySlot = new HashMap<Integer, Upgrade>();
    private final Map<UUID, OpenTownInventoryContext> openInventories = new HashMap<UUID, OpenTownInventoryContext>();
    private final Map<String, Long> treasuryLastCheckMs = new HashMap<String, Long>();
    private final Map<String, Long> upkeepRefundLastCheckMs = new HashMap<String, Long>();

    private File dataFile;
    private FileConfiguration data;

    @Override
    public void onEnable() {
        fillUpgrades();
        initData();
        getServer().getPluginManager().registerEvents(this, this);
        startProductionTask();
        startUpkeepRefundTask();
        startTownyDataExportTask();
        Bukkit.getScheduler().runTaskLater(this, new Runnable() {
            @Override
            public void run() {
                applyAllStoredTownHallLevels();
            }
        }, 60L);
        getLogger().info("TownBuilds enabled. Use /t builds and /t inv.");
    }

    @Override
    public void onDisable() {
        saveDataFile();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Commands are handled via Towny-style preprocess (/t ...).
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreprocess(PlayerCommandPreprocessEvent event) {
        String full = event.getMessage().trim();
        String msg = full.toLowerCase(Locale.ROOT);
        if (msg.equals("/t builds debug production") || msg.equals("/town builds debug production")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (!player.isOp() && !player.hasPermission("townbuilds.admin")) {
                notifyPlayer(player, ChatColor.RED, "Нет доступа к debug-команде.");
                return;
            }
            ProductionResult result = applyTimedProductionForAllTowns(true);
            notifyPlayer(player, ChatColor.GREEN,
                    "[Debug] Производство: обработано городов " + result.townsChecked
                    + ", ресурсы выданы в " + result.townsProduced + " городах.");
            return;
        }

        if (msg.equals("/t builds debug treasury") || msg.equals("/town builds debug treasury")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (!player.isOp() && !player.hasPermission("townbuilds.admin")) {
                notifyPlayer(player, ChatColor.RED, "Нет доступа к debug-команде.");
                return;
            }
            ProductionResult result = applyTimedProductionForAllTowns(true);
            notifyPlayer(player, ChatColor.GREEN,
                    "[Debug] Казна: обработано городов " + result.townsChecked
                    + ", автодоход начислен в " + result.treasuryProduced + " городах.");
            return;
        }

        if (msg.equals("/t builds reload") || msg.equals("/town builds reload")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (!player.isOp() && !player.hasPermission("townbuilds.admin")) {
                notifyPlayer(player, ChatColor.RED, "Нет доступа к команде.");
                return;
            }
            writeTownyDataJson();
            notifyPlayer(player, ChatColor.GREEN, "Данные городов обновлены.");
            return;
        }

        if (msg.equals("/t builds debug upkeep") || msg.equals("/town builds debug upkeep")) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            if (!player.isOp() && !player.hasPermission("townbuilds.admin")) {
                notifyPlayer(player, ChatColor.RED, "Нет доступа к debug-команде.");
                return;
            }
            // Сбрасываем таймеры, чтобы следующая проверка сработала сразу
            upkeepRefundLastCheckMs.clear();
            applyUpkeepRefundsForAllTowns(true);
            notifyPlayer(player, ChatColor.GREEN,
                    "[Debug] Скидка на содержание Ратуши применена для всех городов.");
            return;
        }

        if (msg.equals("/t builds") || msg.equals("/town builds")) {
            event.setCancelled(true);
            openMenu(event.getPlayer());
            return;
        }

        if (msg.equals("/t inv") || msg.equals("/town inv") || msg.equals("/t inventory") || msg.equals("/town inventory")) {
            event.setCancelled(true);
            handleTownInventoryCommand(event.getPlayer(), new String[0]);
            return;
        }

        if (msg.startsWith("/t inv ") || msg.startsWith("/town inv ") || msg.startsWith("/t inventory ") || msg.startsWith("/town inventory ")) {
            event.setCancelled(true);
            String[] parts = full.split("\\s+");
            int skip = 2;
            if (parts.length > 1 && ("inventory".equalsIgnoreCase(parts[1]) || "inv".equalsIgnoreCase(parts[1]))) {
                skip = 2;
            }
            if (parts.length <= skip) {
                handleTownInventoryCommand(event.getPlayer(), new String[0]);
                return;
            }
            String[] args = Arrays.copyOfRange(parts, skip, parts.length);
            handleTownInventoryCommand(event.getPlayer(), args);
            return;
        }

        if (msg.equals("/t treasury") || msg.equals("/town treasury")) {
            event.setCancelled(true);
            handleTreasuryCommand(event.getPlayer(), new String[0]);
            return;
        }

        if (msg.startsWith("/t treasury ") || msg.startsWith("/town treasury ")) {
            event.setCancelled(true);
            String[] parts = full.split("\\s+");
            if (parts.length <= 2) {
                handleTreasuryCommand(event.getPlayer(), new String[0]);
                return;
            }
            String[] args = Arrays.copyOfRange(parts, 2, parts.length);
            handleTreasuryCommand(event.getPlayer(), args);
        }

        // Мониторинг /t claim: увеличиваем налог на 1$ за каждый новый заприваченный чанк
        if (msg.startsWith("/t claim") || msg.startsWith("/town claim")) {
            final Player claimPlayer = event.getPlayer();
            final String claimTownName = resolveTownName(claimPlayer);
            if (claimTownName != null) {
                final int beforeBlocks = getTownyNumTownBlocks(claimTownName);
                if (beforeBlocks >= 0) {
                    Bukkit.getScheduler().runTaskLater(TownBuildsPlugin.this, new Runnable() {
                        @Override
                        public void run() {
                            int afterBlocks = getTownyNumTownBlocks(claimTownName);
                            int delta = afterBlocks - beforeBlocks;
                            if (delta > 0) {
                                increaseTownyTax(claimTownName, delta * TOWNY_UPKEEP_PER_BLOCK);
                                int added = (int)(delta * TOWNY_UPKEEP_PER_BLOCK);
                                notifyPlayer(claimPlayer, ChatColor.YELLOW,
                                        "Налог города увеличен на +" + added + "$ за " + delta
                                                + " новых чанк" + (delta == 1 ? "" : "ов") + ".");
                            }
                        }
                    }, 2L);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onTabComplete(TabCompleteEvent event) {
        String buf = event.getBuffer().toLowerCase(Locale.ROOT);
        // /t <tab> или /town <tab>
        if (buf.equals("/t ") || buf.equals("/town ")) {
            List<String> completions = new ArrayList<>(event.getCompletions());
            for (String sub : Arrays.asList("builds", "inv", "treasury")) {
                if (!completions.contains(sub)) completions.add(sub);
            }
            event.setCompletions(completions);
            return;
        }
        // /t builds <tab> — субкоманды builds
        if (buf.equals("/t builds ") || buf.equals("/town builds ")) {
            List<String> completions = new ArrayList<>(event.getCompletions());
            if (event.getSender().isOp() || event.getSender().hasPermission("townbuilds.admin")) {
                for (String sub : Arrays.asList("debug", "reload")) {
                    if (!completions.contains(sub)) completions.add(sub);
                }
            }
            event.setCompletions(completions);
            return;
        }
        // /t treasury <tab>
        if (buf.equals("/t treasury ") || buf.equals("/town treasury ")) {
            List<String> completions = new ArrayList<>(event.getCompletions());
            for (String sub : Arrays.asList("deposit", "withdraw")) {
                if (!completions.contains(sub)) completions.add(sub);
            }
            event.setCompletions(completions);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView() == null || event.getView().getTitle() == null) {
            return;
        }

        if (UPGRADE_MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            onUpgradeMenuClick(event);
            return;
        }

        if (LUMBER_UPGRADE_MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            onLumberUpgradeMenuClick(event);
            return;
        }

        if (QUARRY_UPGRADE_MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            onQuarryUpgradeMenuClick(event);
            return;
        }

        if (DECOR_UPGRADE_MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            onDecorUpgradeMenuClick(event);
            return;
        }

        if (ORES_UPGRADE_MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            onOresUpgradeMenuClick(event);
            return;
        }

        if (TREASURY_UPGRADE_MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            onTreasuryUpgradeMenuClick(event);
            return;
        }

        if (TOWN_HALL_UPGRADE_MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            onTownHallUpgradeMenuClick(event);
            return;
        }

        if (MINING_UPGRADE_MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            onMiningUpgradeMenuClick(event);
            return;
        }

        if (TAILOR_UPGRADE_MENU_TITLE.equals(event.getView().getTitle())) {
            event.setCancelled(true);
            onTailorUpgradeMenuClick(event);
            return;
        }

        if (!MENU_TITLE.equals(event.getView().getTitle())) {
            if (event.getView().getTitle() != null && event.getView().getTitle().startsWith(TOWN_INV_TITLE_PREFIX)) {
                onTownInventoryClick(event);
            }
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        Upgrade upgrade = upgradesBySlot.get(slot);
        if (upgrade == null) {
            return;
        }

        if ("Инвентарь Города".equals(upgrade.name)) {
            openTownInventoryUpgradeMenu(player);
            return;
        }

        if ("Лесопилка".equals(upgrade.name)) {
            openLumbermillUpgradeMenu(player);
            return;
        }

        if ("Каменоломня".equals(upgrade.name)) {
            openQuarryUpgradeMenu(player);
            return;
        }

        if ("Декорационные блоки".equals(upgrade.name)) {
            openDecorUpgradeMenu(player);
            return;
        }

        if ("Руды".equals(upgrade.name)) {
            openOresUpgradeMenu(player);
            return;
        }

        if ("Казна".equals(upgrade.name)) {
            openTreasuryUpgradeMenu(player);
            return;
        }

        if ("Улучшения Ратуши".equals(upgrade.name)) {
            openTownHallUpgradeMenu(player);
            return;
        }

        if ("Добыча ископаемых".equals(upgrade.name)) {
            openMiningUpgradeMenu(player);
            return;
        }

        if ("Швейная мастерская".equals(upgrade.name)) {
            openTailorUpgradeMenu(player);
            return;
        }

        player.closeInventory();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        if (event.getView() == null || event.getView().getTitle() == null) {
            return;
        }
        if (!event.getView().getTitle().startsWith(TOWN_INV_TITLE_PREFIX)) {
            return;
        }
        if (event.getInventory() == null || event.getInventory().getSize() < TOWN_INV_SIZE) {
            return;
        }
        Player player = (Player) event.getPlayer();
        OpenTownInventoryContext ctx = openInventories.remove(player.getUniqueId());
        if (ctx == null) {
            return;
        }
        saveCurrentPageToData(ctx, event.getInventory());
        saveDataFile();
    }

    private void openMenu(Player player) {
        if (!player.hasPermission("townbuilds.use") && !player.isOp()) {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            ((org.bukkit.command.CommandSender) player).sendMessage("§cВы не состоите в городе.");
            return;
        }

        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, MENU_TITLE);

        for (Map.Entry<Integer, Upgrade> entry : upgradesBySlot.entrySet()) {
            inv.setItem(entry.getKey(), createHeadItem(entry.getValue()));
        }

        player.openInventory(inv);
    }

    private void openTownInventoryUpgradeMenu(Player player) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы смотреть прокачку склада.");
            return;
        }

        int currentLevel = getTownLevel(access.townName);
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, UPGRADE_MENU_TITLE);

        inv.setItem(11, createLevelInfoItem(1, currentLevel));
        inv.setItem(13, createLevelInfoItem(2, currentLevel));
        inv.setItem(15, createLevelInfoItem(3, currentLevel));

        player.openInventory(inv);
    }

    private void openLumbermillUpgradeMenu(Player player) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы смотреть прокачку Лесопилки.");
            return;
        }

        int currentLevel = getLumberLevel(access.townName);
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, LUMBER_UPGRADE_MENU_TITLE);

        inv.setItem(11, createLumberLevelInfoItem(1, currentLevel));
        inv.setItem(13, createLumberLevelInfoItem(2, currentLevel));
        inv.setItem(15, createLumberLevelInfoItem(3, currentLevel));

        player.openInventory(inv);
    }

    private void openQuarryUpgradeMenu(Player player) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы смотреть прокачку Каменоломни.");
            return;
        }

        int currentLevel = getQuarryLevel(access.townName);
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, QUARRY_UPGRADE_MENU_TITLE);

        inv.setItem(11, createQuarryLevelInfoItem(1, currentLevel));
        inv.setItem(13, createQuarryLevelInfoItem(2, currentLevel));
        inv.setItem(15, createQuarryLevelInfoItem(3, currentLevel));

        player.openInventory(inv);
    }

    private void openDecorUpgradeMenu(Player player) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы смотреть прокачку Декораций.");
            return;
        }

        int currentLevel = getDecorLevel(access.townName);
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, DECOR_UPGRADE_MENU_TITLE);

        inv.setItem(11, createDecorLevelInfoItem(1, currentLevel));
        inv.setItem(13, createDecorLevelInfoItem(2, currentLevel));
        inv.setItem(15, createDecorLevelInfoItem(3, currentLevel));

        player.openInventory(inv);
    }

    private void onUpgradeMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int requestedLevel;
        if (slot == 11) {
            requestedLevel = 1;
        } else if (slot == 13) {
            requestedLevel = 2;
        } else if (slot == 15) {
            requestedLevel = 3;
        } else {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы прокачивать склад.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Прокачивать склад может только мэр или назначенный кладовщик.");
            return;
        }

        int currentLevel = getTownLevel(access.townName);
        if (requestedLevel <= currentLevel) {
            notifyPlayer(player, ChatColor.YELLOW, "Этот уровень уже открыт.");
            return;
        }

        int nextAllowedLevel = currentLevel + 1;
        if (requestedLevel != nextAllowedLevel) {
            notifyPlayer(player, ChatColor.RED, "Сначала нужно открыть " + nextAllowedLevel + " уровень склада.");
            LevelRequirement requirement = getRequirement(nextAllowedLevel);
            sendRequirementList(player, requirement, "Для открытия " + nextAllowedLevel + " уровня нужно:");
            return;
        }

        LevelRequirement requirement = getRequirement(requestedLevel);
        if (!hasAllItems(player, requirement)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает ресурсов для открытия " + requestedLevel + " уровня.");
            sendRequirementList(player, requirement, "Для открытия " + requestedLevel + " уровня нужно:");
            return;
        }

        removeItems(player, requirement);
        setTownLevel(access.townName, requestedLevel);
        saveDataFile();
        notifyPlayer(player, ChatColor.GREEN, "Открыт " + requestedLevel + " уровень склада города.");
        openTownInventoryUpgradeMenu(player);
    }

    private void onLumberUpgradeMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int requestedLevel;
        if (slot == 11) {
            requestedLevel = 1;
        } else if (slot == 13) {
            requestedLevel = 2;
        } else if (slot == 15) {
            requestedLevel = 3;
        } else {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы прокачивать Лесопилку.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Прокачивать Лесопилку может только мэр или назначенный кладовщик.");
            return;
        }

        if (!hasRequiredTownInventoryLevel(player, access.townName, requestedLevel, "Лесопилка")) {
            return;
        }

        int currentLevel = getLumberLevel(access.townName);
        if (requestedLevel <= currentLevel) {
            notifyPlayer(player, ChatColor.YELLOW, "Этот уровень Лесопилки уже открыт.");
            return;
        }

        int nextAllowedLevel = currentLevel + 1;
        if (requestedLevel != nextAllowedLevel) {
            notifyPlayer(player, ChatColor.RED, "Сначала нужно открыть " + nextAllowedLevel + " уровень Лесопилки.");
            LumberRequirement nextRequirement = getLumberRequirement(nextAllowedLevel);
            sendLumberRequirementList(player, nextRequirement, "Для открытия " + nextAllowedLevel + " уровня Лесопилки нужно:");
            return;
        }

        LumberRequirement requirement = getLumberRequirement(requestedLevel);
        if (!hasAllItems(player, requirement.materials)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает ресурсов для открытия " + requestedLevel + " уровня Лесопилки.");
            sendLumberRequirementList(player, requirement, "Для открытия " + requestedLevel + " уровня Лесопилки нужно:");
            return;
        }

        if (!hasEnoughMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает денег для открытия " + requestedLevel + " уровня Лесопилки.");
            sendLumberRequirementList(player, requirement, "Для открытия " + requestedLevel + " уровня Лесопилки нужно:");
            return;
        }

        removeItems(player, requirement.materials);
        if (!withdrawMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не удалось списать деньги. Проверь Vault/экономику.");
            return;
        }

        setLumberLevel(access.townName, requestedLevel);
        if (requestedLevel == 1) {
            String p = townPath(access.townName) + ".production.lastTickMs";
            if (data.getLong(p, 0L) <= 0L) {
                data.set(p, Long.valueOf(System.currentTimeMillis()));
            }
        }
        saveDataFile();
        notifyPlayer(player, ChatColor.GREEN, "Открыт " + requestedLevel + " уровень Лесопилки.");
        openLumbermillUpgradeMenu(player);
    }

    private void onQuarryUpgradeMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int requestedLevel;
        if (slot == 11) {
            requestedLevel = 1;
        } else if (slot == 13) {
            requestedLevel = 2;
        } else if (slot == 15) {
            requestedLevel = 3;
        } else {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы прокачивать Каменоломню.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Прокачивать Каменоломню может только мэр или назначенный кладовщик.");
            return;
        }

        if (!hasRequiredTownInventoryLevel(player, access.townName, requestedLevel, "Каменоломня")) {
            return;
        }

        int currentLevel = getQuarryLevel(access.townName);
        if (requestedLevel <= currentLevel) {
            notifyPlayer(player, ChatColor.YELLOW, "Этот уровень Каменоломни уже открыт.");
            return;
        }

        int nextAllowedLevel = currentLevel + 1;
        if (requestedLevel != nextAllowedLevel) {
            notifyPlayer(player, ChatColor.RED, "Сначала нужно открыть " + nextAllowedLevel + " уровень Каменоломни.");
            QuarryRequirement nextRequirement = getQuarryRequirement(nextAllowedLevel);
            sendQuarryRequirementList(player, nextRequirement, "Для открытия " + nextAllowedLevel + " уровня Каменоломни нужно:");
            return;
        }

        QuarryRequirement requirement = getQuarryRequirement(requestedLevel);
        if (!hasAllItems(player, requirement.materials)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает ресурсов для открытия " + requestedLevel + " уровня Каменоломни.");
            sendQuarryRequirementList(player, requirement, "Для открытия " + requestedLevel + " уровня Каменоломни нужно:");
            return;
        }

        if (!hasEnoughMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает денег для открытия " + requestedLevel + " уровня Каменоломни.");
            sendQuarryRequirementList(player, requirement, "Для открытия " + requestedLevel + " уровня Каменоломни нужно:");
            return;
        }

        removeItems(player, requirement.materials);
        if (!withdrawMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не удалось списать деньги. Проверь Vault/экономику.");
            return;
        }

        setQuarryLevel(access.townName, requestedLevel);
        if (requestedLevel == 1) {
            String p = townPath(access.townName) + ".production.lastTickMs";
            if (data.getLong(p, 0L) <= 0L) {
                data.set(p, Long.valueOf(System.currentTimeMillis()));
            }
        }
        saveDataFile();
        notifyPlayer(player, ChatColor.GREEN, "Открыт " + requestedLevel + " уровень Каменоломни.");
        openQuarryUpgradeMenu(player);
    }

    private void onDecorUpgradeMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int requestedLevel;
        if (slot == 11) {
            requestedLevel = 1;
        } else if (slot == 13) {
            requestedLevel = 2;
        } else if (slot == 15) {
            requestedLevel = 3;
        } else {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы прокачивать Декорации.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Прокачивать Декорации может только мэр или назначенный кладовщик.");
            return;
        }

        if (!hasRequiredTownInventoryLevel(player, access.townName, requestedLevel, "Декорационные блоки")) {
            return;
        }

        int currentLevel = getDecorLevel(access.townName);
        if (requestedLevel <= currentLevel) {
            notifyPlayer(player, ChatColor.YELLOW, "Этот уровень Декораций уже открыт.");
            return;
        }

        int nextAllowedLevel = currentLevel + 1;
        if (requestedLevel != nextAllowedLevel) {
            notifyPlayer(player, ChatColor.RED, "Сначала нужно открыть " + nextAllowedLevel + " уровень Декораций.");
            DecorationRequirement nextRequirement = getDecorRequirement(nextAllowedLevel);
            sendDecorRequirementList(player, nextRequirement, "Для открытия " + nextAllowedLevel + " уровня Декораций нужно:");
            return;
        }

        DecorationRequirement requirement = getDecorRequirement(requestedLevel);
        if (!hasAllItems(player, requirement.materials)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает ресурсов для открытия " + requestedLevel + " уровня Декораций.");
            sendDecorRequirementList(player, requirement, "Для открытия " + requestedLevel + " уровня Декораций нужно:");
            return;
        }

        if (!hasEnoughMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает денег для открытия " + requestedLevel + " уровня Декораций.");
            sendDecorRequirementList(player, requirement, "Для открытия " + requestedLevel + " уровня Декораций нужно:");
            return;
        }

        removeItems(player, requirement.materials);
        if (!withdrawMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не удалось списать деньги. Проверь Vault/экономику.");
            return;
        }

        setDecorLevel(access.townName, requestedLevel);
        if (requestedLevel == 1) {
            String p = townPath(access.townName) + ".production.lastTickMs";
            if (data.getLong(p, 0L) <= 0L) {
                data.set(p, Long.valueOf(System.currentTimeMillis()));
            }
        }
        saveDataFile();
        notifyPlayer(player, ChatColor.GREEN, "Открыт " + requestedLevel + " уровень Декораций.");
        openDecorUpgradeMenu(player);
    }

    private void openOresUpgradeMenu(Player player) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы смотреть прокачку Руд.");
            return;
        }

        int currentLevel = getOresLevel(access.townName);
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, ORES_UPGRADE_MENU_TITLE);

        inv.setItem(11, createOresLevelInfoItem(1, currentLevel));
        inv.setItem(13, createOresLevelInfoItem(2, currentLevel));
        inv.setItem(15, createOresLevelInfoItem(3, currentLevel));

        player.openInventory(inv);
    }

    private void onOresUpgradeMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int requestedLevel;
        if (slot == 11) {
            requestedLevel = 1;
        } else if (slot == 13) {
            requestedLevel = 2;
        } else if (slot == 15) {
            requestedLevel = 3;
        } else {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы прокачивать Руды.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Прокачивать Руды может только мэр или назначенный кладовщик.");
            return;
        }

        if (!hasRequiredTownInventoryLevel(player, access.townName, requestedLevel, "Руды")) {
            return;
        }

        int currentLevel = getOresLevel(access.townName);
        if (requestedLevel <= currentLevel) {
            notifyPlayer(player, ChatColor.YELLOW, "Этот уровень Руд уже открыт.");
            return;
        }

        int nextAllowedLevel = currentLevel + 1;
        if (requestedLevel != nextAllowedLevel) {
            notifyPlayer(player, ChatColor.RED, "Сначала нужно открыть " + nextAllowedLevel + " уровень Руд.");
            OresRequirement nextRequirement = getOresRequirement(nextAllowedLevel);
            sendOresRequirementList(player, nextRequirement, "Для открытия " + nextAllowedLevel + " уровня Руд нужно:");
            return;
        }

        OresRequirement requirement = getOresRequirement(requestedLevel);
        if (!hasAllItems(player, requirement.materials)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает ресурсов для открытия " + requestedLevel + " уровня Руд.");
            sendOresRequirementList(player, requirement, "Для открытия " + requestedLevel + " уровня Руд нужно:");
            return;
        }

        if (!hasEnoughMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает денег для открытия " + requestedLevel + " уровня Руд.");
            sendOresRequirementList(player, requirement, "Для открытия " + requestedLevel + " уровня Руд нужно:");
            return;
        }

        removeItems(player, requirement.materials);
        if (!withdrawMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не удалось списать деньги. Проверь Vault/экономику.");
            return;
        }

        setOresLevel(access.townName, requestedLevel);
        if (requestedLevel == 1) {
            String p = townPath(access.townName) + ".production.lastTickMs";
            if (data.getLong(p, 0L) <= 0L) {
                data.set(p, Long.valueOf(System.currentTimeMillis()));
            }
        }
        saveDataFile();
        notifyPlayer(player, ChatColor.GREEN, "Открыт " + requestedLevel + " уровень Руд.");
        openOresUpgradeMenu(player);
    }

    private void openTreasuryUpgradeMenu(Player player) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы смотреть прокачку Казны.");
            return;
        }

        int currentLevel = getTreasuryLevel(access.townName);
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, TREASURY_UPGRADE_MENU_TITLE);

        inv.setItem(11, createTreasuryLevelInfoItem(1, currentLevel));
        inv.setItem(13, createTreasuryLevelInfoItem(2, currentLevel));
        inv.setItem(15, createTreasuryLevelInfoItem(3, currentLevel));

        player.openInventory(inv);
    }

    private void onTreasuryUpgradeMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int requestedLevel;
        if (slot == 11) {
            requestedLevel = 1;
        } else if (slot == 13) {
            requestedLevel = 2;
        } else if (slot == 15) {
            requestedLevel = 3;
        } else {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы прокачивать Казну.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Прокачивать Казну может только мэр или назначенный кладовщик.");
            return;
        }

        if (!hasRequiredTownInventoryLevel(player, access.townName, requestedLevel, "Казна")) {
            return;
        }

        int currentLevel = getTreasuryLevel(access.townName);
        if (requestedLevel <= currentLevel) {
            notifyPlayer(player, ChatColor.YELLOW, "Этот уровень Казны уже открыт.");
            return;
        }

        int nextAllowedLevel = currentLevel + 1;
        if (requestedLevel != nextAllowedLevel) {
            notifyPlayer(player, ChatColor.RED, "Сначала нужно открыть " + nextAllowedLevel + " уровень Казны.");
            TreasuryRequirement nextRequirement = getTreasuryRequirement(nextAllowedLevel);
            sendTreasuryRequirementList(player, nextRequirement,
                    "Для открытия " + nextAllowedLevel + " уровня Казны нужно:");
            return;
        }

        TreasuryRequirement requirement = getTreasuryRequirement(requestedLevel);
        if (!hasAllItems(player, requirement.materials)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает ресурсов для открытия " + requestedLevel + " уровня Казны.");
            sendTreasuryRequirementList(player, requirement,
                    "Для открытия " + requestedLevel + " уровня Казны нужно:");
            return;
        }

        removeItems(player, requirement.materials);
        setTreasuryLevel(access.townName, requestedLevel);
        if (requestedLevel == 1) {
            String base = townPath(access.townName) + ".treasury";
            if (data.getLong(base + ".progressMs", 0L) < 0L) {
                data.set(base + ".progressMs", Long.valueOf(0L));
            }
            treasuryLastCheckMs.put(normalizeTownKey(access.townName), Long.valueOf(System.currentTimeMillis()));
        }
        saveDataFile();
        notifyPlayer(player, ChatColor.GREEN, "Открыт " + requestedLevel + " уровень Казны.");
        openTreasuryUpgradeMenu(player);
    }

    private void openTownHallUpgradeMenu(Player player) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы смотреть прокачку Ратуши.");
            return;
        }

        int currentLevel = getTownHallLevel(access.townName);
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, TOWN_HALL_UPGRADE_MENU_TITLE);

        inv.setItem(11, createTownHallLevelInfoItem(1, currentLevel));
        inv.setItem(13, createTownHallLevelInfoItem(2, currentLevel));
        inv.setItem(15, createTownHallLevelInfoItem(3, currentLevel));

        player.openInventory(inv);
    }

    private void onTownHallUpgradeMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int requestedLevel;
        if (slot == 11) {
            requestedLevel = 1;
        } else if (slot == 13) {
            requestedLevel = 2;
        } else if (slot == 15) {
            requestedLevel = 3;
        } else {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы прокачивать Ратушу.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Прокачивать Ратушу может только мэр или назначенный кладовщик.");
            return;
        }

        if (!hasRequiredTownInventoryLevel(player, access.townName, requestedLevel, "Улучшения Ратуши")) {
            return;
        }

        int currentLevel = getTownHallLevel(access.townName);
        if (requestedLevel <= currentLevel) {
            notifyPlayer(player, ChatColor.YELLOW, "Этот уровень Ратуши уже открыт.");
            return;
        }

        int nextAllowedLevel = currentLevel + 1;
        if (requestedLevel != nextAllowedLevel) {
            notifyPlayer(player, ChatColor.RED, "Сначала нужно открыть " + nextAllowedLevel + " уровень Ратуши.");
            TownHallRequirement nextRequirement = getTownHallRequirement(nextAllowedLevel);
            sendTownHallRequirementList(player, nextRequirement,
                    "Для открытия " + nextAllowedLevel + " уровня Ратуши нужно:");
            return;
        }

        TownHallRequirement requirement = getTownHallRequirement(requestedLevel);
        if (!hasAllItems(player, requirement.materials)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает ресурсов для открытия " + requestedLevel + " уровня Ратуши.");
            sendTownHallRequirementList(player, requirement,
                    "Для открытия " + requestedLevel + " уровня Ратуши нужно:");
            return;
        }

        if (!hasEnoughMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает денег для открытия " + requestedLevel + " уровня Ратуши.");
            sendTownHallRequirementList(player, requirement,
                    "Для открытия " + requestedLevel + " уровня Ратуши нужно:");
            return;
        }

        removeItems(player, requirement.materials);
        if (!withdrawMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не удалось списать деньги. Проверь Vault/экономику.");
            return;
        }

        setTownHallLevel(access.townName, requestedLevel);
        applyTownHallClaimLimitToTowny(access.townName, requestedLevel);
        // Инициализируем таймер скидки на содержание начиная со следующего дня
        if (requestedLevel >= 2) {
            upkeepRefundLastCheckMs.put(normalizeTownKey(access.townName), Long.valueOf(System.currentTimeMillis()));
        }
        saveDataFile();
        int newLimit = getTownHallMaxClaims(requestedLevel);
        int discountPct = getTownHallUpkeepDiscountPercent(requestedLevel);
        String discountMsg = discountPct > 0 ? " Скидка на содержание: " + discountPct + "%." : "";
        notifyPlayer(player, ChatColor.GREEN,
                "Открыт " + requestedLevel + " уровень Ратуши! Лимит чанков города: " + newLimit + "." + discountMsg);
        openTownHallUpgradeMenu(player);
    }

    private void openMiningUpgradeMenu(Player player) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы смотреть прокачку Добычи.");
            return;
        }

        int currentLevel = getMiningLevel(access.townName);
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, MINING_UPGRADE_MENU_TITLE);

        inv.setItem(11, createMiningLevelInfoItem(1, currentLevel));
        inv.setItem(13, createMiningLevelInfoItem(2, currentLevel));
        inv.setItem(15, createMiningLevelInfoItem(3, currentLevel));

        player.openInventory(inv);
    }

    private void openTailorUpgradeMenu(Player player) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы смотреть прокачку Швейной мастерской.");
            return;
        }

        int currentLevel = getTailorLevel(access.townName);
        Inventory inv = Bukkit.createInventory(null, MENU_SIZE, TAILOR_UPGRADE_MENU_TITLE);

        inv.setItem(11, createTailorLevelInfoItem(1, currentLevel));
        inv.setItem(13, createTailorLevelInfoItem(2, currentLevel));
        inv.setItem(15, createTailorLevelInfoItem(3, currentLevel));

        player.openInventory(inv);
    }

    private void onTailorUpgradeMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int requestedLevel;
        if (slot == 11) {
            requestedLevel = 1;
        } else if (slot == 13) {
            requestedLevel = 2;
        } else if (slot == 15) {
            requestedLevel = 3;
        } else {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы прокачивать Швейную мастерскую.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Прокачивать Швейную мастерскую может только мэр или назначенный кладовщик.");
            return;
        }

        if (!hasRequiredTownInventoryLevel(player, access.townName, requestedLevel, "Швейная мастерская")) {
            return;
        }

        int currentLevel = getTailorLevel(access.townName);
        if (requestedLevel <= currentLevel) {
            notifyPlayer(player, ChatColor.YELLOW, "Этот уровень Швейной мастерской уже открыт.");
            return;
        }

        int nextAllowedLevel = currentLevel + 1;
        if (requestedLevel != nextAllowedLevel) {
            notifyPlayer(player, ChatColor.RED, "Сначала нужно открыть " + nextAllowedLevel + " уровень Швейной мастерской.");
            TailorRequirement nextReq = getTailorRequirement(nextAllowedLevel);
            sendTailorRequirementList(player, nextReq,
                    "Для открытия " + nextAllowedLevel + " уровня Швейной мастерской нужно:");
            return;
        }

        TailorRequirement requirement = getTailorRequirement(requestedLevel);
        if (!hasAllItems(player, requirement.materials)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает ресурсов для открытия " + requestedLevel + " уровня Швейной мастерской.");
            sendTailorRequirementList(player, requirement,
                    "Для открытия " + requestedLevel + " уровня Швейной мастерской нужно:");
            return;
        }

        if (requirement.money > 0 && !hasEnoughMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает денег для открытия " + requestedLevel + " уровня Швейной мастерской.");
            sendTailorRequirementList(player, requirement,
                    "Для открытия " + requestedLevel + " уровня Швейной мастерской нужно:");
            return;
        }

        removeItems(player, requirement.materials);
        if (requirement.money > 0 && !withdrawMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не удалось списать деньги. Проверь Vault/экономику.");
            return;
        }

        setTailorLevel(access.townName, requestedLevel);
        if (requestedLevel == 1) {
            String p = townPath(access.townName) + ".production.lastTickMs";
            if (data.getLong(p, 0L) <= 0L) {
                data.set(p, Long.valueOf(System.currentTimeMillis()));
            }
        }
        saveDataFile();
        notifyPlayer(player, ChatColor.GREEN, "Открыт " + requestedLevel + " уровень Швейной мастерской.");
        openTailorUpgradeMenu(player);
    }

    private void onMiningUpgradeMenuClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        int slot = event.getRawSlot();
        int requestedLevel;
        if (slot == 11) {
            requestedLevel = 1;
        } else if (slot == 13) {
            requestedLevel = 2;
        } else if (slot == 15) {
            requestedLevel = 3;
        } else {
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы прокачивать Добычу.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Прокачивать Добычу может только мэр или назначенный кладовщик.");
            return;
        }

        if (!hasRequiredTownInventoryLevel(player, access.townName, requestedLevel, "Добыча ископаемых")) {
            return;
        }

        int currentLevel = getMiningLevel(access.townName);
        if (requestedLevel <= currentLevel) {
            notifyPlayer(player, ChatColor.YELLOW, "Этот уровень Добычи уже открыт.");
            return;
        }

        int nextAllowedLevel = currentLevel + 1;
        if (requestedLevel != nextAllowedLevel) {
            notifyPlayer(player, ChatColor.RED, "Сначала нужно открыть " + nextAllowedLevel + " уровень Добычи.");
            MiningRequirement nextReq = getMiningRequirement(nextAllowedLevel);
            sendMiningRequirementList(player, nextReq,
                    "Для открытия " + nextAllowedLevel + " уровня Добычи нужно:");
            return;
        }

        MiningRequirement requirement = getMiningRequirement(requestedLevel);
        if (!hasAllItems(player, requirement.materials)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает ресурсов для открытия " + requestedLevel + " уровня Добычи.");
            sendMiningRequirementList(player, requirement,
                    "Для открытия " + requestedLevel + " уровня Добычи нужно:");
            return;
        }

        if (requirement.money > 0 && !hasEnoughMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не хватает денег для открытия " + requestedLevel + " уровня Добычи.");
            sendMiningRequirementList(player, requirement,
                    "Для открытия " + requestedLevel + " уровня Добычи нужно:");
            return;
        }

        removeItems(player, requirement.materials);
        if (requirement.money > 0 && !withdrawMoney(player, requirement.money)) {
            notifyPlayer(player, ChatColor.RED, "Не удалось списать деньги. Проверь Vault/экономику.");
            return;
        }

        setMiningLevel(access.townName, requestedLevel);
        if (requestedLevel == 1) {
            String p = townPath(access.townName) + ".production.lastTickMs";
            if (data.getLong(p, 0L) <= 0L) {
                data.set(p, Long.valueOf(System.currentTimeMillis()));
            }
        }
        saveDataFile();
        notifyPlayer(player, ChatColor.GREEN, "Открыт " + requestedLevel + " уровень Добычи ископаемых.");
        openMiningUpgradeMenu(player);
    }

    private ItemStack createLevelInfoItem(int level, int currentLevel) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta skullMeta = (SkullMeta) meta;
        setSkullOwner(skullMeta, level <= currentLevel ? "MHF_Chest" : "MHF_Present1");

        String status;
        if (level < currentLevel) {
            status = ChatColor.GREEN + "Уже открыт";
        } else if (level == currentLevel) {
            status = ChatColor.GREEN + "Текущий уровень";
        } else if (level == currentLevel + 1) {
            status = ChatColor.YELLOW + "Следующая прокачка";
        } else {
            status = ChatColor.RED + "Пока недоступно";
        }

        List<String> lore = new ArrayList<String>();
        lore.add(status);
        lore.add(ChatColor.DARK_GRAY + "");
        if (level == 1) {
            lore.add(ChatColor.GRAY + "Страниц склада: 1");
        } else if (level == 2) {
            lore.add(ChatColor.GRAY + "Страниц склада: 2");
        } else {
            lore.add(ChatColor.GRAY + "Страниц склада: 4");
        }
        lore.add(ChatColor.DARK_GRAY + "");

        LevelRequirement requirement = getRequirement(level);
        for (Map.Entry<Material, Integer> entry : requirement.costs.entrySet()) {
            lore.add(ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }

        skullMeta.setDisplayName(ChatColor.AQUA + "Инвентарь Города " + ChatColor.WHITE + level + " ур.");
        skullMeta.setLore(lore);
        item.setItemMeta(skullMeta);
        return item;
    }

    private ItemStack createLumberLevelInfoItem(int level, int currentLevel) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta skullMeta = (SkullMeta) meta;
        setSkullOwner(skullMeta, level <= currentLevel ? "MHF_OakLog" : "MHF_Present1");

        String status;
        if (level < currentLevel) {
            status = ChatColor.GREEN + "Уже открыт";
        } else if (level == currentLevel + 1) {
            status = ChatColor.YELLOW + "Следующая прокачка";
        } else if (level == currentLevel) {
            status = ChatColor.GREEN + "Текущий уровень";
        } else {
            status = ChatColor.RED + "Пока недоступно";
        }

        List<String> lore = new ArrayList<String>();
        lore.add(status);
        lore.add(ChatColor.DARK_GRAY + "");
        lore.add(ChatColor.GRAY + "Доход в /t inv:");
        LevelRequirement drop = getLumberDrop(level);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            lore.add(ChatColor.DARK_GREEN + "+ " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.DARK_GRAY + "");

        LumberRequirement req = getLumberRequirement(level);
        lore.add(ChatColor.GRAY + "Требования:");
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            lore.add(ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.GRAY + "- Деньги: " + ((int) req.money) + "$");

        skullMeta.setDisplayName(ChatColor.AQUA + "Лесопилка " + ChatColor.WHITE + level + " ур.");
        skullMeta.setLore(lore);
        item.setItemMeta(skullMeta);
        return item;
    }

    private ItemStack createQuarryLevelInfoItem(int level, int currentLevel) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta skullMeta = (SkullMeta) meta;
        setSkullOwner(skullMeta, level <= currentLevel ? "MHF_Stone" : "MHF_Present1");

        String status;
        if (level < currentLevel) {
            status = ChatColor.GREEN + "Уже открыт";
        } else if (level == currentLevel + 1) {
            status = ChatColor.YELLOW + "Следующая прокачка";
        } else if (level == currentLevel) {
            status = ChatColor.GREEN + "Текущий уровень";
        } else {
            status = ChatColor.RED + "Пока недоступно";
        }

        List<String> lore = new ArrayList<String>();
        lore.add(status);
        lore.add(ChatColor.DARK_GRAY + "");
        lore.add(ChatColor.GRAY + "Доход в /t inv:");
        LevelRequirement drop = getQuarryDrop(level);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            lore.add(ChatColor.DARK_GREEN + "+ " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.DARK_GRAY + "");

        QuarryRequirement req = getQuarryRequirement(level);
        lore.add(ChatColor.GRAY + "Требования:");
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            lore.add(ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.GRAY + "- Деньги: " + ((int) req.money) + "$");

        skullMeta.setDisplayName(ChatColor.AQUA + "Каменоломня " + ChatColor.WHITE + level + " ур.");
        skullMeta.setLore(lore);
        item.setItemMeta(skullMeta);
        return item;
    }

    private ItemStack createDecorLevelInfoItem(int level, int currentLevel) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta skullMeta = (SkullMeta) meta;
        setSkullOwner(skullMeta, level <= currentLevel ? "MHF_Bookshelf" : "MHF_Present1");

        String status;
        if (level < currentLevel) {
            status = ChatColor.GREEN + "Уже открыт";
        } else if (level == currentLevel + 1) {
            status = ChatColor.YELLOW + "Следующая прокачка";
        } else if (level == currentLevel) {
            status = ChatColor.GREEN + "Текущий уровень";
        } else {
            status = ChatColor.RED + "Пока недоступно";
        }

        List<String> lore = new ArrayList<String>();
        lore.add(status);
        lore.add(ChatColor.DARK_GRAY + "");
        lore.add(ChatColor.GRAY + "Доход в /t inv:");
        LevelRequirement drop = getDecorDrop(level);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            lore.add(ChatColor.DARK_GREEN + "+ " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.DARK_GRAY + "");

        DecorationRequirement req = getDecorRequirement(level);
        lore.add(ChatColor.GRAY + "Требования:");
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            lore.add(ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.GRAY + "- Деньги: " + ((int) req.money) + "$");

        skullMeta.setDisplayName(ChatColor.AQUA + "Декорации " + ChatColor.WHITE + level + " ур.");
        skullMeta.setLore(lore);
        item.setItemMeta(skullMeta);
        return item;
    }

    private ItemStack createOresLevelInfoItem(int level, int currentLevel) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta skullMeta = (SkullMeta) meta;
        setSkullOwner(skullMeta, level <= currentLevel ? "MHF_OreCoal" : "MHF_Present1");

        String status;
        if (level < currentLevel) {
            status = ChatColor.GREEN + "Уже открыт";
        } else if (level == currentLevel + 1) {
            status = ChatColor.YELLOW + "Следующая прокачка";
        } else if (level == currentLevel) {
            status = ChatColor.GREEN + "Текущий уровень";
        } else {
            status = ChatColor.RED + "Пока недоступно";
        }

        List<String> lore = new ArrayList<String>();
        lore.add(status);
        lore.add(ChatColor.DARK_GRAY + "");
        lore.add(ChatColor.GRAY + "Доход в /t inv:");
        LevelRequirement drop = getOresDrop(level);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            lore.add(ChatColor.DARK_GREEN + "+ " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.DARK_GRAY + "");

        OresRequirement req = getOresRequirement(level);
        lore.add(ChatColor.GRAY + "Требования:");
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            lore.add(ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.GRAY + "- Деньги: " + ((int) req.money) + "$");

        skullMeta.setDisplayName(ChatColor.AQUA + "Руды " + ChatColor.WHITE + level + " ур.");
        skullMeta.setLore(lore);
        item.setItemMeta(skullMeta);
        return item;
    }

    private ItemStack createTreasuryLevelInfoItem(int level, int currentLevel) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta skullMeta = (SkullMeta) meta;
        setSkullOwner(skullMeta, level <= currentLevel ? "MHF_Blaze" : "MHF_Present1");

        String status;
        if (level < currentLevel) {
            status = ChatColor.GREEN + "Уже открыт";
        } else if (level == currentLevel + 1) {
            status = ChatColor.YELLOW + "Следующая прокачка";
        } else if (level == currentLevel) {
            status = ChatColor.GREEN + "Текущий уровень";
        } else {
            status = ChatColor.RED + "Пока недоступно";
        }

        List<String> lore = new ArrayList<String>();
        lore.add(status);
        lore.add(ChatColor.DARK_GRAY + "");
        lore.add(ChatColor.GRAY + "Автодоход: " + (int) getTreasuryPayout(level) + "$");
        lore.add(ChatColor.GRAY + "Период: каждые 4 часа");
        lore.add(ChatColor.GRAY + "Только при онлайне жителя города");
        lore.add(ChatColor.DARK_GRAY + "");

        TreasuryRequirement req = getTreasuryRequirement(level);
        lore.add(ChatColor.GRAY + "Требования:");
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            lore.add(ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }

        skullMeta.setDisplayName(ChatColor.AQUA + "Казна " + ChatColor.WHITE + level + " ур.");
        skullMeta.setLore(lore);
        item.setItemMeta(skullMeta);
        return item;
    }

    private ItemStack createTownHallLevelInfoItem(int level, int currentLevel) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta skullMeta = (SkullMeta) meta;
        setSkullOwner(skullMeta, level <= currentLevel ? "MHF_Villager" : "MHF_Present1");

        String status;
        if (level < currentLevel) {
            status = ChatColor.GREEN + "Уже открыт";
        } else if (level == currentLevel) {
            status = ChatColor.GREEN + "Текущий уровень";
        } else if (level == currentLevel + 1) {
            status = ChatColor.YELLOW + "Следующая прокачка";
        } else {
            status = ChatColor.RED + "Пока недоступно";
        }

        List<String> lore = new ArrayList<String>();
        lore.add(status);
        lore.add(ChatColor.DARK_GRAY + "");
        lore.add(ChatColor.GRAY + "Лимит чанков города: " + getTownHallMaxClaims(level));
        int discountPct = getTownHallUpkeepDiscountPercent(level);
        if (discountPct > 0) {
            lore.add(ChatColor.GREEN + "Скидка на содержание: " + discountPct + "%");
        }
        lore.add(ChatColor.DARK_GRAY + "");

        TownHallRequirement req = getTownHallRequirement(level);
        lore.add(ChatColor.GRAY + "Требования:");
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            lore.add(ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.GRAY + "- Деньги: " + (int) req.money + "$");

        skullMeta.setDisplayName(ChatColor.AQUA + "Ратуша " + ChatColor.WHITE + level + " ур.");
        skullMeta.setLore(lore);
        item.setItemMeta(skullMeta);
        return item;
    }

    private ItemStack createMiningLevelInfoItem(int level, int currentLevel) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta skullMeta = (SkullMeta) meta;
        setSkullOwner(skullMeta, level <= currentLevel ? "MHF_OreDiamond" : "MHF_Present1");

        String status;
        if (level < currentLevel) {
            status = ChatColor.GREEN + "Уже открыт";
        } else if (level == currentLevel) {
            status = ChatColor.GREEN + "Текущий уровень";
        } else if (level == currentLevel + 1) {
            status = ChatColor.YELLOW + "Следующая прокачка";
        } else {
            status = ChatColor.RED + "Пока недоступно";
        }

        List<String> lore = new ArrayList<String>();
        lore.add(status);
        lore.add(ChatColor.DARK_GRAY + "");
        lore.add(ChatColor.GRAY + "Производство каждые 6ч в /t inv:");

        LevelRequirement drop = getMiningDrop(level);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            lore.add(ChatColor.DARK_GREEN + "+ " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.DARK_GRAY + "");

        MiningRequirement req = getMiningRequirement(level);
        lore.add(ChatColor.GRAY + "Требования:");
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            lore.add(ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        if (req.money > 0) {
            lore.add(ChatColor.GRAY + "- Деньги: " + (int) req.money + "$");
        }

        skullMeta.setDisplayName(ChatColor.AQUA + "Добыча " + ChatColor.WHITE + level + " ур.");
        skullMeta.setLore(lore);
        item.setItemMeta(skullMeta);
        return item;
    }

    private ItemStack createTailorLevelInfoItem(int level, int currentLevel) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof SkullMeta)) {
            return item;
        }

        SkullMeta skullMeta = (SkullMeta) meta;
        setSkullOwner(skullMeta, level <= currentLevel ? "MHF_Sheep" : "MHF_Present1");

        String status;
        if (level < currentLevel) {
            status = ChatColor.GREEN + "Уже открыт";
        } else if (level == currentLevel) {
            status = ChatColor.GREEN + "Текущий уровень";
        } else if (level == currentLevel + 1) {
            status = ChatColor.YELLOW + "Следующая прокачка";
        } else {
            status = ChatColor.RED + "Пока недоступно";
        }

        List<String> lore = new ArrayList<String>();
        lore.add(status);
        lore.add(ChatColor.DARK_GRAY + "");
        lore.add(ChatColor.GRAY + "Производство каждые 6ч в /t inv:");

        LevelRequirement drop = getTailorDrop(level);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            lore.add(ChatColor.DARK_GREEN + "+ " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        lore.add(ChatColor.DARK_GRAY + "");

        TailorRequirement req = getTailorRequirement(level);
        lore.add(ChatColor.GRAY + "Требования:");
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            lore.add(ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()));
        }
        if (req.money > 0) {
            lore.add(ChatColor.GRAY + "- Деньги: " + (int) req.money + "$");
        }

        skullMeta.setDisplayName(ChatColor.AQUA + "Швейная мастерская " + ChatColor.WHITE + level + " ур.");
        skullMeta.setLore(lore);
        item.setItemMeta(skullMeta);
        return item;
    }

    private ItemStack createHeadItem(Upgrade upgrade) {
        ItemStack icon = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta itemMeta = icon.getItemMeta();
        if (!(itemMeta instanceof SkullMeta)) {
            return icon;
        }

        SkullMeta skullMeta = (SkullMeta) itemMeta;
        setSkullOwner(skullMeta, upgrade.headOwner);
        skullMeta.setDisplayName(ChatColor.AQUA + upgrade.name);
        skullMeta.setLore(Arrays.asList(
                ChatColor.GRAY + upgrade.description,
                ChatColor.DARK_GRAY + "",
                ChatColor.YELLOW + "Нажми для выбора"
        ));
        icon.setItemMeta(skullMeta);
        return icon;
    }

    private void setSkullOwner(SkullMeta skullMeta, String ownerName) {
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerName);
        skullMeta.setOwningPlayer(owner);
    }

    private void fillUpgrades() {
        upgradesBySlot.clear();
        upgradesBySlot.put(2, new Upgrade("Инвентарь Города", "Склад города с уровнями и страницами.", "MHF_Chest"));
        upgradesBySlot.put(3, new Upgrade("Казна", "Пополняет баланс города каждые 4 часа при онлайне жителей.", "MHF_Blaze"));
        upgradesBySlot.put(5, new Upgrade("Лесопилка", "Даёт бонус к добыче древесины.", "MHF_OakLog"));
        upgradesBySlot.put(6, new Upgrade("Швейная мастерская", "Производство тканей и ремесленных ресурсов.", "MHF_Sheep"));

        upgradesBySlot.put(13, new Upgrade("Улучшения Ратуши", "Глобальные улучшения управления городом.", "MHF_Villager"));

        upgradesBySlot.put(20, new Upgrade("Каменоломня", "Даёт бонус к добыче камня.", "MHF_Stone"));
        upgradesBySlot.put(21, new Upgrade("Добыча ископаемых", "Расширенная добыча руд и минералов.", "MHF_OreDiamond"));
        upgradesBySlot.put(23, new Upgrade("Руды", "Даёт бонус к добыче руды.", "MHF_Cobblestone"));
        upgradesBySlot.put(24, new Upgrade("Декорационные блоки", "Открывает декоративные наборы для города.", "MHF_Bookshelf"));
    }

    private void handleTownInventoryCommand(Player player, String[] args) {
        if (args.length > 0 && "manager".equalsIgnoreCase(args[0])) {
            handleManagerSubcommand(player, args);
            return;
        }

        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе, чтобы использовать склад.");
            return;
        }

        if (!access.canAccess) {
            notifyPlayer(player, ChatColor.RED, "Доступ к /t inv только у мэра или назначенного кладовщика.");
            return;
        }

        int level = getTownLevel(access.townName);
        if (level <= 0) {
            LevelRequirement req1 = getRequirement(1);
            if (!hasAllItems(player, req1)) {
                notifyPlayer(player, ChatColor.RED, "Эта команда сейчас не доступна. Её можно разблокировать, нужно собрать ресурсы.");
                sendRequirementList(player, req1, "Для открытия 1 уровня /t inv нужно:");
                return;
            }

            removeItems(player, req1);
            setTownLevel(access.townName, 1);
            saveDataFile();
            notifyPlayer(player, ChatColor.GREEN, "1 уровень склада города открыт.");
            level = 1;
        }

        openTownInventory(player, access.townName, 0, level);
    }

    private void handleTreasuryCommand(Player player, String[] args) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть жителем города, чтобы использовать Казну.");
            return;
        }

        String townName = access.townName;
        int level = getTreasuryLevel(townName);
        double balance = getTreasuryBalance(townName);

        if (args.length == 0 || "info".equalsIgnoreCase(args[0]) || "balance".equalsIgnoreCase(args[0])) {
            notifyPlayer(player, ChatColor.GOLD, "Казна города: " + townName);
            notifyPlayer(player, ChatColor.YELLOW, "Уровень: " + level + "/3");
            notifyPlayer(player, ChatColor.YELLOW, "Баланс: " + formatMoney(balance) + "$.");
            if (level > 0) {
                notifyPlayer(player, ChatColor.YELLOW,
                        "Автодоход: " + formatMoney(getTreasuryPayout(level)) + "$ каждые 4 часа (если есть онлайн-житель города).");
            }
            notifyPlayer(player, ChatColor.GRAY, "Пополнить: /t treasury deposit <сумма>");
            notifyPlayer(player, ChatColor.GRAY, "Снять: /t treasury withdraw <сумма> (только мэр)");
            return;
        }

        if ("deposit".equalsIgnoreCase(args[0])) {
            if (args.length < 2) {
                notifyPlayer(player, ChatColor.YELLOW, "Использование: /t treasury deposit <сумма>");
                return;
            }

            double amount = parsePositiveAmount(args[1]);
            if (amount <= 0.0D) {
                notifyPlayer(player, ChatColor.RED, "Сумма должна быть больше нуля.");
                return;
            }

            if (!hasEnoughMoney(player, amount)) {
                notifyPlayer(player, ChatColor.RED, "Недостаточно денег на балансе.");
                return;
            }

            if (!withdrawMoney(player, amount)) {
                notifyPlayer(player, ChatColor.RED, "Не удалось списать деньги. Проверь Vault/экономику.");
                return;
            }

            addTreasuryBalance(townName, amount);
            saveDataFile();
            notifyPlayer(player, ChatColor.GREEN,
                    "Пополнение успешно: +" + formatMoney(amount) + "$ в Казну города " + townName + ".");
            return;
        }

        if ("withdraw".equalsIgnoreCase(args[0])) {
            if (!access.isMayor) {
                notifyPlayer(player, ChatColor.RED, "Снимать деньги из Казны может только мэр города.");
                return;
            }

            if (args.length < 2) {
                notifyPlayer(player, ChatColor.YELLOW, "Использование: /t treasury withdraw <сумма>");
                return;
            }

            double amount = parsePositiveAmount(args[1]);
            if (amount <= 0.0D) {
                notifyPlayer(player, ChatColor.RED, "Сумма должна быть больше нуля.");
                return;
            }

            if (balance + 1.0E-9D < amount) {
                notifyPlayer(player, ChatColor.RED, "В Казне недостаточно средств.");
                return;
            }

            if (!depositMoney(player, amount)) {
                notifyPlayer(player, ChatColor.RED, "Не удалось выдать деньги игроку. Проверь Vault/экономику.");
                return;
            }

            addTreasuryBalance(townName, -amount);
            saveDataFile();
            notifyPlayer(player, ChatColor.GREEN,
                    "Вывод успешно выполнен: -" + formatMoney(amount) + "$ из Казны города " + townName + ".");
            return;
        }

        notifyPlayer(player, ChatColor.YELLOW, "Использование: /t treasury [info|deposit|withdraw]");
    }

    private void handleManagerSubcommand(Player player, String[] args) {
        TownAccess access = resolveTownAccess(player);
        if (access == null) {
            notifyPlayer(player, ChatColor.RED, "Ты должен быть в городе.");
            return;
        }
        if (!access.isMayor) {
            notifyPlayer(player, ChatColor.RED, "Только мэр может назначать кладовщика.");
            return;
        }
        if (args.length < 2) {
            notifyPlayer(player, ChatColor.YELLOW, "Использование: /t inv manager <ник|clear>");
            return;
        }
        if ("clear".equalsIgnoreCase(args[1])) {
            data.set(townPath(access.townName) + ".manager", null);
            saveDataFile();
            notifyPlayer(player, ChatColor.GREEN, "Кладовщик снят.");
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (target == null || target.getUniqueId() == null) {
            notifyPlayer(player, ChatColor.RED, "Игрок не найден.");
            return;
        }

        data.set(townPath(access.townName) + ".manager", target.getUniqueId().toString());
        saveDataFile();
        notifyPlayer(player, ChatColor.GREEN, "Кладовщик назначен: " + target.getName());
    }

    private void openTownInventory(Player player, String townName, int page, int level) {
        int maxPages = Math.max(1, invLevelToPages(level));
        int safePage = Math.max(0, Math.min(page, maxPages - 1));
        Inventory inv = Bukkit.createInventory(null, TOWN_INV_SIZE, TOWN_INV_TITLE_PREFIX + townName + " | " + (safePage + 1) + "/" + maxPages);

        List<?> saved = data.getList(townPath(townName) + ".pages." + safePage, Collections.emptyList());
        if (saved != null) {
            for (int i = 0; i < Math.min(TOWN_INV_SIZE, saved.size()); i++) {
                Object obj = saved.get(i);
                if (obj instanceof ItemStack) {
                    inv.setItem(i, ((ItemStack) obj).clone());
                }
            }
        }

        if (maxPages > 1 && safePage > 0) {
            inv.setItem(45, createNavItem(ChatColor.YELLOW + "Назад", true));
        }
        if (maxPages > 1 && safePage < maxPages - 1) {
            inv.setItem(53, createNavItem(ChatColor.YELLOW + "Вперёд", false));
        }

        player.openInventory(inv);
        openInventories.put(player.getUniqueId(), new OpenTownInventoryContext(townName, safePage, level));
    }

    private ItemStack createNavItem(String title, boolean isBack) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta) {
            meta.setDisplayName(title);
            setSkullOwner((SkullMeta) meta, isBack ? "MHF_ArrowLeft" : "MHF_ArrowRight");
            head.setItemMeta(meta);
        }
        return head;
    }

    private void onTownInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        OpenTownInventoryContext ctx = openInventories.get(player.getUniqueId());
        if (ctx == null) {
            return;
        }

        int raw = event.getRawSlot();
        if (raw < 0) {
            return;
        }

        if (raw >= TOWN_INV_SIZE) {
            // Block shift-click moving items from player inventory into town storage.
            if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
            }
            return;
        }

        // In town storage: allow taking items, but disallow any placing/swap into slots.
        ItemStack cursor = event.getCursor();
        boolean hasCursorItem = cursor != null && cursor.getType() != Material.AIR;
        if (hasCursorItem) {
            event.setCancelled(true);
        }
        if (event.getAction() == InventoryAction.HOTBAR_SWAP || event.getAction() == InventoryAction.HOTBAR_MOVE_AND_READD) {
            event.setCancelled(true);
        }

        if (raw == 45 || raw == 53) {
            event.setCancelled(true);
            saveCurrentPageToData(ctx, event.getInventory());
            int maxPages = Math.max(1, invLevelToPages(ctx.level));
            if (raw == 45 && ctx.page > 0) {
                openTownInventory(player, ctx.townName, ctx.page - 1, ctx.level);
            } else if (raw == 53 && ctx.page < maxPages - 1) {
                openTownInventory(player, ctx.townName, ctx.page + 1, ctx.level);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTownInventoryDrag(InventoryDragEvent event) {
        if (event.getView() == null || event.getView().getTitle() == null) {
            return;
        }
        if (!event.getView().getTitle().startsWith(TOWN_INV_TITLE_PREFIX)) {
            return;
        }

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= 0 && rawSlot < TOWN_INV_SIZE) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void saveCurrentPageToData(OpenTownInventoryContext ctx, Inventory inv) {
        List<ItemStack> items = new ArrayList<ItemStack>(TOWN_INV_SIZE);
        int limit = Math.min(TOWN_INV_SIZE, inv.getSize());
        for (int i = 0; i < limit; i++) {
            if (i == 45 || i == 53) {
                items.add(null);
                continue;
            }
            ItemStack item = inv.getItem(i);
            items.add(item == null ? null : item.clone());
        }
        while (items.size() < TOWN_INV_SIZE) {
            items.add(null);
        }
        data.set(townPath(ctx.townName) + ".pages." + ctx.page, items);
    }

    private void applyLumberDrop(String townName) {
        int lumberLevel = getLumberLevel(townName);
        if (lumberLevel <= 0) {
            return;
        }

        LevelRequirement drop = getLumberDrop(lumberLevel);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            addMaterialToTownStorage(townName, entry.getKey(), entry.getValue());
        }
        saveDataFile();
    }

    private void startProductionTask() {
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                applyTimedProductionForAllTowns(false);
            }
        }, 20L * 60L, 20L * 60L);
    }

    private void startUpkeepRefundTask() {
        // Проверяем каждые 5 минут, не пора ли вернуть скидку на содержание
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                applyUpkeepRefundsForAllTowns(false);
            }
        }, 20L * 60L * 5L, 20L * 60L * 5L);
    }

    /** Возвращает процент скидки на содержание для уровня Ратуши (0, 10 или 30). */
    private int getTownHallUpkeepDiscountPercent(int level) {
        if (level >= 3) return 30;
        if (level >= 2) return 10;
        return 0;
    }

    /** Начисляет рефанды содержания всем городам с Ратушей 2+ уровня раз в 24ч. */
    private void applyUpkeepRefundsForAllTowns(boolean forceNow) {
        if (data == null) return;
        org.bukkit.configuration.ConfigurationSection townsSec = data.getConfigurationSection("towns");
        if (townsSec == null) return;
        long now = System.currentTimeMillis();
        for (String key : townsSec.getKeys(false)) {
            int level = Math.max(0, Math.min(MAX_LEVEL, data.getInt("towns." + key + ".townhall.level", 0)));
            if (level < 2) continue;
            String realName = data.getString("towns." + key + ".townhall._name", key);
            String townKey = normalizeTownKey(realName);

            if (!forceNow) {
                Long lastCheck = upkeepRefundLastCheckMs.get(townKey);
                if (lastCheck == null) {
                    upkeepRefundLastCheckMs.put(townKey, Long.valueOf(now));
                    continue;
                }
                if (now - lastCheck.longValue() < UPKEEP_REFUND_INTERVAL_MS) continue;
            }

            upkeepRefundLastCheckMs.put(townKey, Long.valueOf(now));

            // Рассчитываем сумму рефанда: кол-во чанков * цена за чанк * процент скидки
            int numBlocks = getTownyNumTownBlocks(realName);
            if (numBlocks <= 0) continue;
            int discountPct = getTownHallUpkeepDiscountPercent(level);
            double refundAmount = numBlocks * TOWNY_UPKEEP_PER_BLOCK * (discountPct / 100.0);
            if (refundAmount <= 0) continue;

            boolean ok = depositToTownBank(realName, refundAmount);
            if (ok) {
                getLogger().info("[TownBuilds] Возврат скидки на содержание для города " + realName
                        + ": +" + String.format("%.2f", refundAmount) + "$ (" + discountPct + "% от "
                        + numBlocks + " чанков)");
            }
        }
    }

    /** Проверяет, существует ли город с данным ключом в Towny. */
    private boolean isTownExistsInTowny(String townKey) {
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Method getInstance = universeClass.getMethod("getInstance");
            Object universe = getInstance.invoke(null);
            Method getTown = universeClass.getMethod("getTown", String.class);
            Object town = getTown.invoke(universe, townKey);
            return town != null;
        } catch (Exception e) {
            return true; // при ошибке не удаляем — на всякий случай
        }
    }

    /** Кладёт деньги в казну города через Towny API (reflection). */
    private boolean depositToTownBank(String townName, double amount) {
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Method getInstance = universeClass.getMethod("getInstance");
            Object universe = getInstance.invoke(null);

            Object town = null;
            try {
                Method getTown = universeClass.getMethod("getTown", String.class);
                town = getTown.invoke(universe, townName);
            } catch (Exception ignored) {}

            if (town == null) {
                getLogger().warning("[TownBuilds] depositToTownBank: город '" + townName + "' не найден.");
                return false;
            }

            // Пробуем town.getAccount().deposit(double, String)
            try {
                Method getAccount = town.getClass().getMethod("getAccount");
                Object account = getAccount.invoke(town);
                for (java.lang.reflect.Method m : account.getClass().getMethods()) {
                    if ("deposit".equals(m.getName()) && m.getParameterCount() == 2) {
                        Class<?>[] params = m.getParameterTypes();
                        if ((params[0] == double.class || params[0] == Double.class)
                                && params[1] == String.class) {
                            m.invoke(account, amount, "Скидка Ратуши на содержание");
                            return true;
                        }
                    }
                }
            } catch (Exception ignored) {}

            return false;
        } catch (Exception ex) {
            getLogger().warning("[TownBuilds] depositToTownBank: ошибка - " + ex.getMessage());
            return false;
        }
    }

    private ProductionResult applyTimedProductionForAllTowns(boolean forceNow) {
        ProductionResult result = new ProductionResult();
        if (data == null) {
            return result;
        }

        if (data.getConfigurationSection("towns") == null) {
            return result;
        }

        long now = System.currentTimeMillis();
        Map<String, Integer> onlineTownResidents = getOnlineTownResidents();
        boolean changed = false;
        for (String townKey : data.getConfigurationSection("towns").getKeys(false)) {
            if (!isTownExistsInTowny(townKey)) {
                continue;
            }
            result.townsChecked++;
            String base = "towns." + townKey;
            int lumberLevel = Math.max(0, Math.min(MAX_LEVEL, data.getInt(base + ".lumber.level", 0)));
            int quarryLevel = Math.max(0, Math.min(MAX_LEVEL, data.getInt(base + ".quarry.level", 0)));
            int decorLevel = Math.max(0, Math.min(MAX_LEVEL, data.getInt(base + ".decor.level", 0)));
            int oresLevel = Math.max(0, Math.min(MAX_LEVEL, data.getInt(base + ".ores.level", 0)));
            int miningLevel = Math.max(0, Math.min(MAX_LEVEL, data.getInt(base + ".mining.level", 0)));
            int tailorLevel = Math.max(0, Math.min(MAX_LEVEL, data.getInt(base + ".tailor.level", 0)));
            int treasuryLevel = Math.max(0, Math.min(MAX_LEVEL, data.getInt(base + ".treasury.level", 0)));

            boolean hasMaterialProduction = lumberLevel > 0 || quarryLevel > 0 || decorLevel > 0 || oresLevel > 0 || miningLevel > 0 || tailorLevel > 0;
            if (!hasMaterialProduction && treasuryLevel <= 0) {
                continue;
            }

            boolean producedAny = false;

            if (treasuryLevel > 0) {
                TreasuryTickResult treasuryTick = applyTreasuryIncomeByTownKey(
                        townKey,
                        treasuryLevel,
                        now,
                        forceNow,
                        onlineTownResidents.containsKey(townKey)
                );
                if (treasuryTick.changed) {
                    changed = true;
                }
                if (treasuryTick.produced) {
                    producedAny = true;
                    result.treasuryProduced++;
                }
            }

            if (!hasMaterialProduction) {
                if (producedAny) {
                    result.townsProduced++;
                }
                continue;
            }

            long lastTick = data.getLong(base + ".production.lastTickMs", 0L);
            if (lastTick <= 0L) {
                data.set(base + ".production.lastTickMs", Long.valueOf(now));
                changed = true;
                continue;
            }

            if (!forceNow && (now - lastTick) < PRODUCTION_INTERVAL_MS) {
                continue;
            }

            boolean produced = false;
            if (lumberLevel > 0) {
                applyLumberDropByTownKey(townKey, lumberLevel);
                produced = true;
            }
            if (quarryLevel > 0) {
                applyQuarryDropByTownKey(townKey, quarryLevel);
                produced = true;
            }
            if (decorLevel > 0) {
                applyDecorDropByTownKey(townKey, decorLevel);
                produced = true;
            }
            if (oresLevel > 0) {
                applyOresDropByTownKey(townKey, oresLevel);
                produced = true;
            }
            if (miningLevel > 0) {
                applyMiningDropByTownKey(townKey, miningLevel);
                produced = true;
            }
            if (tailorLevel > 0) {
                applyTailorDropByTownKey(townKey, tailorLevel);
                produced = true;
            }
            data.set(base + ".production.lastTickMs", Long.valueOf(now));
            if (produced) {
                producedAny = true;
            }
            if (producedAny) {
                result.townsProduced++;
            }
            changed = true;
        }

        if (changed) {
            saveDataFile();
        }
        return result;
    }

    private TreasuryTickResult applyTreasuryIncomeByTownKey(String townKey, int treasuryLevel, long now,
                                                            boolean forceNow, boolean hasOnlineResident) {
        TreasuryTickResult tick = new TreasuryTickResult();
        if (treasuryLevel <= 0) {
            return tick;
        }

        Long previousCheck = treasuryLastCheckMs.get(townKey);
        if (previousCheck == null) {
            treasuryLastCheckMs.put(townKey, Long.valueOf(now));
            return tick;
        }

        long prev = previousCheck.longValue();
        if (now < prev) {
            prev = now;
        }
        long delta = now - prev;
        treasuryLastCheckMs.put(townKey, Long.valueOf(now));

        String base = "towns." + townKey + ".treasury";
        long progress = Math.max(0L, data.getLong(base + ".progressMs", 0L));
        long cycles;

        if (forceNow) {
            cycles = 1L;
        } else {
            if (!hasOnlineResident) {
                return tick;
            }

            if (delta > 0L) {
                progress += delta;
                tick.changed = true;
            }

            cycles = progress / TREASURY_INTERVAL_MS;
            progress = progress % TREASURY_INTERVAL_MS;
        }

        if (cycles <= 0L) {
            if (!forceNow && tick.changed) {
                data.set(base + ".progressMs", Long.valueOf(progress));
            }
            return tick;
        }

        double payout = getTreasuryPayout(treasuryLevel) * cycles;
        double oldBalance = Math.max(0.0D, data.getDouble(base + ".balance", 0.0D));
        data.set(base + ".balance", Double.valueOf(oldBalance + payout));
        data.set(base + ".progressMs", Long.valueOf(forceNow ? progress : progress));
        tick.changed = true;
        tick.produced = true;
        return tick;
    }

    private void applyLumberDropByTownKey(String townKey, int lumberLevel) {
        LevelRequirement drop = getLumberDrop(lumberLevel);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            addMaterialToTownStorageByKey(townKey, entry.getKey(), entry.getValue());
        }
    }

    private void applyQuarryDropByTownKey(String townKey, int quarryLevel) {
        LevelRequirement drop = getQuarryDrop(quarryLevel);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            addMaterialToTownStorageByKey(townKey, entry.getKey(), entry.getValue());
        }
    }

    private void applyDecorDropByTownKey(String townKey, int decorLevel) {
        LevelRequirement drop = getDecorDrop(decorLevel);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            addMaterialToTownStorageByKey(townKey, entry.getKey(), entry.getValue());
        }
    }

    private void applyOresDropByTownKey(String townKey, int oresLevel) {
        LevelRequirement drop = getOresDrop(oresLevel);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            addMaterialToTownStorageByKey(townKey, entry.getKey(), entry.getValue());
        }
    }

    private void applyMiningDropByTownKey(String townKey, int miningLevel) {
        LevelRequirement drop = getMiningDrop(miningLevel);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            addMaterialToTownStorageByKey(townKey, entry.getKey(), entry.getValue());
        }
    }

    private void applyTailorDropByTownKey(String townKey, int tailorLevel) {
        LevelRequirement drop = getTailorDrop(tailorLevel);
        for (Map.Entry<Material, Integer> entry : drop.costs.entrySet()) {
            addMaterialToTownStorageByKey(townKey, entry.getKey(), entry.getValue());
        }
    }

    private LevelRequirement getTailorDrop(int level) {
        LevelRequirement drop = new LevelRequirement();
        if (level == 1) {
            drop.costs.put(Material.WHITE_WOOL, 64);
            return drop;
        }
        if (level == 2) {
            drop.costs.put(Material.WHITE_WOOL, 64);
            drop.costs.put(Material.LIGHT_GRAY_WOOL, 64);
            drop.costs.put(Material.GRAY_WOOL, 64);
            drop.costs.put(Material.BLACK_WOOL, 64);
            drop.costs.put(Material.BROWN_WOOL, 64);
            drop.costs.put(Material.RED_WOOL, 64);
            drop.costs.put(Material.ORANGE_WOOL, 64);
            drop.costs.put(Material.YELLOW_WOOL, 64);
            drop.costs.put(Material.LIME_WOOL, 64);
            return drop;
        }
        drop.costs.put(Material.WHITE_WOOL, 64);
        drop.costs.put(Material.LIGHT_GRAY_WOOL, 64);
        drop.costs.put(Material.GRAY_WOOL, 64);
        drop.costs.put(Material.BLACK_WOOL, 64);
        drop.costs.put(Material.BROWN_WOOL, 64);
        drop.costs.put(Material.RED_WOOL, 64);
        drop.costs.put(Material.ORANGE_WOOL, 64);
        drop.costs.put(Material.YELLOW_WOOL, 64);
        drop.costs.put(Material.LIME_WOOL, 64);
        drop.costs.put(Material.GREEN_WOOL, 64);
        drop.costs.put(Material.CYAN_WOOL, 64);
        drop.costs.put(Material.LIGHT_BLUE_WOOL, 64);
        drop.costs.put(Material.BLUE_WOOL, 64);
        drop.costs.put(Material.PURPLE_WOOL, 64);
        drop.costs.put(Material.MAGENTA_WOOL, 64);
        return drop;
    }

    private LevelRequirement getMiningDrop(int level) {
        LevelRequirement drop = new LevelRequirement();
        if (level == 1) {
            drop.costs.put(Material.DIRT, 128);
            drop.costs.put(Material.GRAVEL, 128);
            drop.costs.put(Material.SAND, 128);
            return drop;
        }
        if (level == 2) {
            drop.costs.put(Material.GRASS_BLOCK, 32);
            drop.costs.put(Material.DIRT, 128);
            drop.costs.put(Material.GRAVEL, 192);
            drop.costs.put(Material.SAND, 192);
            drop.costs.put(Material.RED_SAND, 64);
            drop.costs.put(Material.ICE, 16);
            return drop;
        }
        drop.costs.put(Material.GRASS_BLOCK, 64);
        drop.costs.put(Material.DIRT, 192);
        drop.costs.put(Material.GRAVEL, 320);
        drop.costs.put(Material.SAND, 320);
        drop.costs.put(Material.RED_SAND, 192);
        drop.costs.put(Material.ICE, 64);
        return drop;
    }

    private MiningRequirement getMiningRequirement(int level) {
        MiningRequirement req = new MiningRequirement();
        if (level == 1) {
            req.materials.costs.put(Material.DIRT, 320);
            req.materials.costs.put(Material.GRAVEL, 384);
            req.materials.costs.put(Material.SAND, 384);
            req.materials.costs.put(Material.STONE_SHOVEL, 4);
            req.money = 0.0D;
            return req;
        }
        if (level == 2) {
            req.materials.costs.put(Material.DIRT, 256);
            req.materials.costs.put(Material.GRAVEL, 320);
            req.materials.costs.put(Material.SAND, 320);
            req.materials.costs.put(Material.RED_SAND, 256);
            req.materials.costs.put(Material.ICE, 64);
            req.materials.costs.put(Material.IRON_SHOVEL, 3);
            req.materials.costs.put(Material.IRON_PICKAXE, 2);
            req.money = 30.0D;
            return req;
        }
        req.materials.costs.put(Material.GRASS_BLOCK, 192);
        req.materials.costs.put(Material.DIRT, 256);
        req.materials.costs.put(Material.GRAVEL, 320);
        req.materials.costs.put(Material.SAND, 256);
        req.materials.costs.put(Material.RED_SAND, 256);
        req.materials.costs.put(Material.ICE, 128);
        req.materials.costs.put(Material.DIAMOND_SHOVEL, 2);
        req.materials.costs.put(Material.DIAMOND_PICKAXE, 1);
        req.money = 70.0D;
        return req;
    }

    private TailorRequirement getTailorRequirement(int level) {
        TailorRequirement req = new TailorRequirement();
        if (level == 1) {
            req.materials.costs.put(Material.WHITE_WOOL, 128);
            req.materials.costs.put(Material.STRING, 128);
            req.materials.costs.put(Material.LOOM, 10);
            req.materials.costs.put(Material.CRAFTING_TABLE, 4);
            req.materials.costs.put(Material.SHEARS, 2);
            req.money = 0.0D;
            return req;
        }
        if (level == 2) {
            req.materials.costs.put(Material.WHITE_WOOL, 448);
            req.materials.costs.put(Material.STRING, 192);
            req.materials.costs.put(Material.LOOM, 20);
            req.materials.costs.put(Material.CRAFTING_TABLE, 8);
            req.materials.costs.put(Material.CAULDRON, 4);
            req.materials.costs.put(Material.LIGHT_GRAY_DYE, 64);
            req.materials.costs.put(Material.GRAY_DYE, 64);
            req.materials.costs.put(Material.BLACK_DYE, 64);
            req.materials.costs.put(Material.BROWN_DYE, 64);
            req.materials.costs.put(Material.RED_DYE, 64);
            req.materials.costs.put(Material.ORANGE_DYE, 64);
            req.materials.costs.put(Material.YELLOW_DYE, 64);
            req.materials.costs.put(Material.LIME_DYE, 64);
            req.materials.costs.put(Material.SHEARS, 4);
            req.money = 50.0D;
            return req;
        }
        req.materials.costs.put(Material.WHITE_WOOL, 576);
        req.materials.costs.put(Material.STRING, 320);
        req.materials.costs.put(Material.LOOM, 30);
        req.materials.costs.put(Material.CRAFTING_TABLE, 20);
        req.materials.costs.put(Material.CAULDRON, 16);
        req.materials.costs.put(Material.GREEN_DYE, 64);
        req.materials.costs.put(Material.CYAN_DYE, 64);
        req.materials.costs.put(Material.LIGHT_BLUE_DYE, 64);
        req.materials.costs.put(Material.BLUE_DYE, 64);
        req.materials.costs.put(Material.PURPLE_DYE, 64);
        req.materials.costs.put(Material.MAGENTA_DYE, 64);
        req.materials.costs.put(Material.PINK_DYE, 64);
        req.materials.costs.put(Material.SHEARS, 6);
        req.money = 80.0D;
        return req;
    }

    private void addMaterialToTownStorage(String townName, Material material, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int added = addToTownStorageStacking(townName, material, remaining);
            if (added <= 0) {
                break;
            }
            remaining -= added;
        }
    }

    private void addMaterialToTownStorageByKey(String townKey, Material material, int amount) {
        int remaining = amount;
        while (remaining > 0) {
            int added = addToTownStorageStackingByKey(townKey, material, remaining);
            if (added <= 0) {
                break;
            }
            remaining -= added;
        }
    }

    private int addToTownStorageStacking(String townName, Material material, int amount) {
        int pages = Math.max(1, invLevelToPages(getTownLevel(townName)));
        int remaining = amount;

        // 1) Fill existing partial stacks first.
        for (int page = 0; page < pages && remaining > 0; page++) {
            String path = pagePath(townName, page);
            List<ItemStack> items = readTownPage(path);
            boolean changed = false;

            for (int slot = 0; slot < TOWN_INV_SIZE && remaining > 0; slot++) {
                if (slot == 45 || slot == 53) {
                    continue;
                }
                ItemStack current = items.get(slot);
                if (current == null || current.getType() != material) {
                    continue;
                }
                int max = current.getMaxStackSize();
                int have = current.getAmount();
                if (have >= max) {
                    continue;
                }
                int canAdd = Math.min(remaining, max - have);
                current.setAmount(have + canAdd);
                remaining -= canAdd;
                changed = true;
            }

            if (changed) {
                data.set(path, items);
            }
        }

        // 2) Use empty slots.
        for (int page = 0; page < pages && remaining > 0; page++) {
            String path = pagePath(townName, page);
            List<ItemStack> items = readTownPage(path);
            boolean changed = false;

            for (int slot = 0; slot < TOWN_INV_SIZE && remaining > 0; slot++) {
                if (slot == 45 || slot == 53) {
                    continue;
                }
                ItemStack current = items.get(slot);
                if (current != null && current.getType() != Material.AIR) {
                    continue;
                }

                int put = Math.min(remaining, 64);
                items.set(slot, new ItemStack(material, put));
                remaining -= put;
                changed = true;
            }

            if (changed) {
                data.set(path, items);
            }
        }

        return amount - remaining;
    }

    private int addToTownStorageStackingByKey(String townKey, Material material, int amount) {
        String townNameLike = townKey;
        int pages = Math.max(1, invLevelToPages(Math.max(0, data.getInt("towns." + townNameLike + ".inv.level", 0))));
        int remaining = amount;

        // 1) Fill existing partial stacks first.
        for (int page = 0; page < pages && remaining > 0; page++) {
            String path = "towns." + townNameLike + ".pages." + page;
            List<ItemStack> items = readTownPage(path);
            boolean changed = false;

            for (int slot = 0; slot < TOWN_INV_SIZE && remaining > 0; slot++) {
                if (slot == 45 || slot == 53) {
                    continue;
                }
                ItemStack current = items.get(slot);
                if (current == null || current.getType() != material) {
                    continue;
                }
                int max = current.getMaxStackSize();
                int have = current.getAmount();
                if (have >= max) {
                    continue;
                }
                int canAdd = Math.min(remaining, max - have);
                current.setAmount(have + canAdd);
                remaining -= canAdd;
                changed = true;
            }

            if (changed) {
                data.set(path, items);
            }
        }

        // 2) Use empty slots.
        for (int page = 0; page < pages && remaining > 0; page++) {
            String path = "towns." + townNameLike + ".pages." + page;
            List<ItemStack> items = readTownPage(path);
            boolean changed = false;

            for (int slot = 0; slot < TOWN_INV_SIZE && remaining > 0; slot++) {
                if (slot == 45 || slot == 53) {
                    continue;
                }
                ItemStack current = items.get(slot);
                if (current != null && current.getType() != Material.AIR) {
                    continue;
                }

                int put = Math.min(remaining, 64);
                items.set(slot, new ItemStack(material, put));
                remaining -= put;
                changed = true;
            }

            if (changed) {
                data.set(path, items);
            }
        }

        return amount - remaining;
    }

    private boolean addSingleStackToTownStorage(String townName, ItemStack stack) {
        int pages = Math.max(1, invLevelToPages(getTownLevel(townName)));
        for (int page = 0; page < pages; page++) {
            List<ItemStack> items = readTownPage(pagePath(townName, page));
            for (int slot = 0; slot < TOWN_INV_SIZE; slot++) {
                if (slot == 45 || slot == 53) {
                    continue;
                }
                if (items.get(slot) == null || items.get(slot).getType() == Material.AIR) {
                    items.set(slot, stack.clone());
                    data.set(pagePath(townName, page), items);
                    return true;
                }
            }
        }
        return false;
    }

    private boolean addSingleStackToTownStorageByKey(String townKey, ItemStack stack) {
        String townNameLike = townKey;
        int pages = Math.max(1, invLevelToPages(Math.max(0, data.getInt("towns." + townNameLike + ".inv.level", 0))));
        for (int page = 0; page < pages; page++) {
            List<ItemStack> items = readTownPage("towns." + townNameLike + ".pages." + page);
            for (int slot = 0; slot < TOWN_INV_SIZE; slot++) {
                if (slot == 45 || slot == 53) {
                    continue;
                }
                if (items.get(slot) == null || items.get(slot).getType() == Material.AIR) {
                    items.set(slot, stack.clone());
                    data.set("towns." + townNameLike + ".pages." + page, items);
                    return true;
                }
            }
        }
        return false;
    }

    private List<ItemStack> readTownPage(String path) {
        List<ItemStack> items = new ArrayList<ItemStack>(TOWN_INV_SIZE);
        List<?> raw = data.getList(path, Collections.emptyList());
        for (int i = 0; i < TOWN_INV_SIZE; i++) {
            ItemStack stack = null;
            if (raw != null && i < raw.size() && raw.get(i) instanceof ItemStack) {
                stack = ((ItemStack) raw.get(i)).clone();
            }
            items.add(stack);
        }
        return items;
    }

    private String pagePath(String townName, int page) {
        return townPath(townName) + ".pages." + page;
    }

    private int getTownLevel(String townName) {
        return Math.max(0, data.getInt(townPath(townName) + ".inv.level", 0));
    }

    private int invLevelToPages(int level) {
        if (level <= 0) return 0;
        if (level == 1) return 1;
        if (level == 2) return 2;
        return 4;
    }

    private void setTownLevel(String townName, int level) {
        data.set(townPath(townName) + ".inv.level", Math.max(0, Math.min(level, MAX_LEVEL)));
    }

    private int getLumberLevel(String townName) {
        return Math.max(0, Math.min(MAX_LEVEL, data.getInt(townPath(townName) + ".lumber.level", 0)));
    }

    private void setLumberLevel(String townName, int level) {
        data.set(townPath(townName) + ".lumber.level", Math.max(0, Math.min(level, MAX_LEVEL)));
    }

    private int getQuarryLevel(String townName) {
        return Math.max(0, Math.min(MAX_LEVEL, data.getInt(townPath(townName) + ".quarry.level", 0)));
    }

    private void setQuarryLevel(String townName, int level) {
        data.set(townPath(townName) + ".quarry.level", Math.max(0, Math.min(level, MAX_LEVEL)));
    }

    private int getDecorLevel(String townName) {
        return Math.max(0, Math.min(MAX_LEVEL, data.getInt(townPath(townName) + ".decor.level", 0)));
    }

    private void setDecorLevel(String townName, int level) {
        data.set(townPath(townName) + ".decor.level", Math.max(0, Math.min(level, MAX_LEVEL)));
    }

    private int getOresLevel(String townName) {
        return Math.max(0, Math.min(MAX_LEVEL, data.getInt(townPath(townName) + ".ores.level", 0)));
    }

    private void setOresLevel(String townName, int level) {
        data.set(townPath(townName) + ".ores.level", Math.max(0, Math.min(level, MAX_LEVEL)));
    }

    private int getTreasuryLevel(String townName) {
        return Math.max(0, Math.min(MAX_LEVEL, data.getInt(townPath(townName) + ".treasury.level", 0)));
    }

    private void setTreasuryLevel(String townName, int level) {
        data.set(townPath(townName) + ".treasury.level", Math.max(0, Math.min(level, MAX_LEVEL)));
    }

    private int getTownHallLevel(String townName) {
        return Math.max(0, Math.min(MAX_LEVEL, data.getInt(townPath(townName) + ".townhall.level", 0)));
    }

    private void setTownHallLevel(String townName, int level) {
        String path = townPath(townName) + ".townhall";
        data.set(path + ".level", Math.max(0, Math.min(level, MAX_LEVEL)));
        data.set(path + "._name", townName);
    }

    private int getTownHallMaxClaims(int level) {
        if (level <= 0) return 8;
        if (level == 1) return 100;
        if (level == 2) return 400;
        return 800;
    }

    private boolean applyTownHallClaimLimitToTowny(String townName, int level) {
        int maxClaims = getTownHallMaxClaims(level);
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            java.lang.reflect.Method getInstance = universeClass.getMethod("getInstance");
            Object universe = getInstance.invoke(null);

            Object town = null;
            try {
                java.lang.reflect.Method getTown = universeClass.getMethod("getTown", String.class);
                town = getTown.invoke(universe, townName);
            } catch (Exception ignored) {}

            if (town == null) {
                try {
                    java.lang.reflect.Method getDS = universeClass.getMethod("getDataSource");
                    Object ds = getDS.invoke(universe);
                    for (java.lang.reflect.Method m : ds.getClass().getMethods()) {
                        if ("getTown".equals(m.getName()) && m.getParameterCount() == 1
                                && m.getParameterTypes()[0] == String.class) {
                            town = m.invoke(ds, townName);
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (town == null) {
                return false;
            }

            boolean applied = false;
            for (String methodName : new String[]{"setMaxClaimedTownBlocks", "setMaxClaimBonus", "setBonusBlocks"}) {
                try {
                    java.lang.reflect.Method m = null;
                    for (java.lang.reflect.Method candidate : town.getClass().getMethods()) {
                        if (candidate.getName().equals(methodName) && candidate.getParameterCount() == 1
                                && (candidate.getParameterTypes()[0] == int.class
                                        || candidate.getParameterTypes()[0] == Integer.class)) {
                            m = candidate;
                            break;
                        }
                    }
                    if (m != null) {
                        m.invoke(town, maxClaims);
                        applied = true;
                        break;
                    }
                } catch (Exception ignored) {}
            }

            if (!applied) {
                getLogger().warning("[TownBuilds] Towny: не удалось установить лимит чанков для города '" + townName + "'.");
                return false;
            }

            try {
                java.lang.reflect.Method getDS = universeClass.getMethod("getDataSource");
                Object ds = getDS.invoke(universe);
                for (java.lang.reflect.Method m : ds.getClass().getMethods()) {
                    if ("saveTown".equals(m.getName()) && m.getParameterCount() == 1) {
                        m.invoke(ds, town);
                        break;
                    }
                }
            } catch (Exception ignored) {}

            return true;
        } catch (Exception ex) {
            getLogger().warning("[TownBuilds] Ошибка применения лимита Ратуши через Towny: " + ex.getMessage());
            return false;
        }
    }

    private void applyAllStoredTownHallLevels() {
        if (data == null) return;
        org.bukkit.configuration.ConfigurationSection townsSec = data.getConfigurationSection("towns");
        if (townsSec == null) return;
        int count = 0;
        boolean dirty = false;
        for (String key : new java.util.ArrayList<>(townsSec.getKeys(false))) {
            int level = Math.max(0, Math.min(MAX_LEVEL, data.getInt("towns." + key + ".townhall.level", 0)));
            if (level > 0) {
                String realName = data.getString("towns." + key + ".townhall._name", key);
                boolean found = applyTownHallClaimLimitToTowny(realName, level);
                if (!found) {
                    data.set("towns." + key, null);
                    dirty = true;
                    continue;
                }
                if (level >= 2) {
                    String townKey = normalizeTownKey(realName);
                    // При старте сервера: откладываем первый рефанд на 24ч от сейчас,
                    // если данных ещё нет (т.е. не мешаем уже запущенному таймеру).
                    upkeepRefundLastCheckMs.putIfAbsent(townKey, Long.valueOf(System.currentTimeMillis()));
                }
                count++;
            }
        }
        if (dirty) saveDataFile();
        if (count > 0) {
            getLogger().info("[TownBuilds] Применены лимиты чанков Ратуши для " + count + " городов.");
        }
    }

    private int getMiningLevel(String townName) {
        return Math.max(0, Math.min(MAX_LEVEL, data.getInt(townPath(townName) + ".mining.level", 0)));
    }

    private void setMiningLevel(String townName, int level) {
        data.set(townPath(townName) + ".mining.level", Math.max(0, Math.min(level, MAX_LEVEL)));
    }

    private int getTailorLevel(String townName) {
        return Math.max(0, Math.min(MAX_LEVEL, data.getInt(townPath(townName) + ".tailor.level", 0)));
    }

    private void setTailorLevel(String townName, int level) {
        data.set(townPath(townName) + ".tailor.level", Math.max(0, Math.min(level, MAX_LEVEL)));
    }

    private int getTownyNumTownBlocks(String townName) {
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Method getInstance = universeClass.getMethod("getInstance");
            Object universe = getInstance.invoke(null);

            Object town = null;
            try {
                Method getTown = universeClass.getMethod("getTown", String.class);
                town = getTown.invoke(universe, townName);
            } catch (Exception ignored) {}

            if (town == null) return -1;

            // Пробуем getNumTownBlocks()
            try {
                Method m = town.getClass().getMethod("getNumTownBlocks");
                Object val = m.invoke(town);
                return ((Number) val).intValue();
            } catch (Exception ignored) {}

            // Запасной вариант: getTownBlocks().size()
            try {
                Method m = town.getClass().getMethod("getTownBlocks");
                Object blocks = m.invoke(town);
                Method size = blocks.getClass().getMethod("size");
                return ((Number) size.invoke(blocks)).intValue();
            } catch (Exception ignored) {}

            return -1;
        } catch (Exception ex) {
            return -1;
        }
    }

    /** Увеличивает налог города в Towny на указанную сумму. */
    private void increaseTownyTax(String townName, double amount) {
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Method getInstance = universeClass.getMethod("getInstance");
            Object universe = getInstance.invoke(null);

            Object town = null;
            try {
                Method getTown = universeClass.getMethod("getTown", String.class);
                town = getTown.invoke(universe, townName);
            } catch (Exception ignored) {}

            if (town == null) {
                getLogger().warning("[TownBuilds] increaseTownyTax: город '" + townName + "' не найден.");
                return;
            }

            // Читаем текущий налог
            double currentTax = 0.0;
            for (String getter : new String[]{"getTaxes", "getTax"}) {
                try {
                    Method m = town.getClass().getMethod(getter);
                    currentTax = ((Number) m.invoke(town)).doubleValue();
                    break;
                } catch (Exception ignored) {}
            }

            double newTax = currentTax + amount;

            // Устанавливаем новый налог
            boolean taxSet = false;
            outer:
            for (String setter : new String[]{"setTaxes", "setTax"}) {
                for (Class<?> paramType : new Class[]{double.class, float.class}) {
                    try {
                        Method m = town.getClass().getMethod(setter, paramType);
                        if (paramType == float.class) {
                            m.invoke(town, (float) newTax);
                        } else {
                            m.invoke(town, newTax);
                        }
                        taxSet = true;
                        break outer;
                    } catch (Exception ignored) {}
                }
            }

            if (!taxSet) {
                getLogger().warning("[TownBuilds] increaseTownyTax: не удалось установить налог для '" + townName + "'.");
                return;
            }

            // Сохраняем город
            try {
                Method getDS = universeClass.getMethod("getDataSource");
                Object ds = getDS.invoke(universe);
                for (Method m : ds.getClass().getMethods()) {
                    if ("saveTown".equals(m.getName()) && m.getParameterCount() == 1) {
                        m.invoke(ds, town);
                        break;
                    }
                }
            } catch (Exception ignored) {}

            getLogger().info("[TownBuilds] Налог города '" + townName + "' изменён: " + currentTax + "$ → " + newTax + "$.");
        } catch (Exception ex) {
            getLogger().warning("[TownBuilds] Ошибка увеличения налога: " + ex.getMessage());
        }
    }


    private double getTreasuryBalance(String townName) {
        return Math.max(0.0D, data.getDouble(townPath(townName) + ".treasury.balance", 0.0D));
    }

    private void addTreasuryBalance(String townName, double delta) {
        double current = getTreasuryBalance(townName);
        data.set(townPath(townName) + ".treasury.balance", Double.valueOf(Math.max(0.0D, current + delta)));
    }

    private double getTreasuryPayout(int level) {
        if (level <= 1) {
            return 25.0D;
        }
        if (level == 2) {
            return 50.0D;
        }
        return 75.0D;
    }

    private String townPath(String townName) {
        return "towns." + normalizeTownKey(townName);
    }

    private String normalizeTownKey(String townName) {
        return townName.toLowerCase().replace('.', '_');
    }

    private void initData() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().warning("Cannot create data.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveDataFile() {
        if (data == null || dataFile == null) {
            return;
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().warning("Cannot save data.yml: " + e.getMessage());
        }
    }

    private boolean hasAllItems(Player player, LevelRequirement requirement) {
        for (Map.Entry<Material, Integer> entry : requirement.costs.entrySet()) {
            if (countMaterial(player, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private int countMaterial(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void removeItems(Player player, LevelRequirement requirement) {
        for (Map.Entry<Material, Integer> entry : requirement.costs.entrySet()) {
            removeMaterial(player, entry.getKey(), entry.getValue());
        }
    }

    private void removeMaterial(Player player, Material material, int needed) {
        ItemStack[] contents = player.getInventory().getContents();
        int remaining = needed;
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item == null || item.getType() != material) {
                continue;
            }
            int amount = item.getAmount();
            if (amount <= remaining) {
                contents[i] = null;
                remaining -= amount;
            } else {
                item.setAmount(amount - remaining);
                remaining = 0;
            }
            if (remaining <= 0) {
                break;
            }
        }
        player.getInventory().setContents(contents);
    }

    private LevelRequirement getRequirement(int level) {
        LevelRequirement req = new LevelRequirement();
        if (level == 1) {
            req.costs.put(Material.CHEST, 10);
            req.costs.put(Material.OAK_LOG, 128);
            req.costs.put(Material.BIRCH_LOG, 128);
            req.costs.put(Material.SPRUCE_LOG, 128);
            req.costs.put(Material.IRON_INGOT, 64);
            req.costs.put(Material.IRON_PICKAXE, 2);
            return req;
        }
        if (level == 2) {
            req.costs.put(Material.CHEST, 20);
            req.costs.put(Material.OAK_LOG, 256);
            req.costs.put(Material.BIRCH_LOG, 256);
            req.costs.put(Material.JUNGLE_LOG, 128);
            req.costs.put(Material.DARK_OAK_LOG, 128);
            req.costs.put(Material.GOLD_INGOT, 128);
            return req;
        }
        req.costs.put(Material.CHEST, 30);
        req.costs.put(Material.OAK_LOG, 256);
        req.costs.put(Material.DARK_OAK_LOG, 256);
        req.costs.put(Material.MANGROVE_LOG, 64);
        req.costs.put(Material.CRIMSON_STEM, 64);
        req.costs.put(Material.WARPED_STEM, 64);
        req.costs.put(Material.DIAMOND, 128);
        return req;
    }

    private LumberRequirement getLumberRequirement(int level) {
        LumberRequirement req = new LumberRequirement();
        if (level == 1) {
            req.materials.costs.put(Material.OAK_LOG, 64);
            req.materials.costs.put(Material.BIRCH_LOG, 64);
            req.materials.costs.put(Material.SPRUCE_LOG, 64);
            req.materials.costs.put(Material.DARK_OAK_LOG, 64);
            req.materials.costs.put(Material.IRON_AXE, 4);
            req.money = 10.0D;
            return req;
        }
        if (level == 2) {
            req.materials.costs.put(Material.OAK_LOG, 128);
            req.materials.costs.put(Material.BIRCH_LOG, 128);
            req.materials.costs.put(Material.SPRUCE_LOG, 128);
            req.materials.costs.put(Material.DARK_OAK_LOG, 128);
            req.materials.costs.put(Material.JUNGLE_LOG, 64);
            req.materials.costs.put(Material.ACACIA_LOG, 64);
            req.materials.costs.put(Material.IRON_AXE, 6);
            req.materials.costs.put(Material.DIAMOND_AXE, 2);
            req.money = 100.0D;
            return req;
        }

        req.materials.costs.put(Material.OAK_LOG, 192);
        req.materials.costs.put(Material.BIRCH_LOG, 192);
        req.materials.costs.put(Material.SPRUCE_LOG, 192);
        req.materials.costs.put(Material.DARK_OAK_LOG, 192);
        req.materials.costs.put(Material.JUNGLE_LOG, 128);
        req.materials.costs.put(Material.ACACIA_LOG, 128);
        req.materials.costs.put(Material.MANGROVE_LOG, 32);
        req.materials.costs.put(Material.CHERRY_LOG, 32);
        req.materials.costs.put(Material.CRIMSON_STEM, 32);
        req.materials.costs.put(Material.WARPED_STEM, 32);
        req.materials.costs.put(Material.DIAMOND_AXE, 6);
        req.money = 200.0D;
        return req;
    }

    private LevelRequirement getLumberDrop(int level) {
        LevelRequirement drop = new LevelRequirement();
        if (level == 1) {
            drop.costs.put(Material.OAK_LOG, 32);
            drop.costs.put(Material.BIRCH_LOG, 32);
            drop.costs.put(Material.SPRUCE_LOG, 32);
            drop.costs.put(Material.DARK_OAK_LOG, 32);
            return drop;
        }
        if (level == 2) {
            drop.costs.put(Material.OAK_LOG, 64);
            drop.costs.put(Material.BIRCH_LOG, 64);
            drop.costs.put(Material.SPRUCE_LOG, 64);
            drop.costs.put(Material.DARK_OAK_LOG, 64);
            drop.costs.put(Material.JUNGLE_LOG, 32);
            drop.costs.put(Material.ACACIA_LOG, 32);
            return drop;
        }

        drop.costs.put(Material.OAK_LOG, 128);
        drop.costs.put(Material.BIRCH_LOG, 128);
        drop.costs.put(Material.SPRUCE_LOG, 128);
        drop.costs.put(Material.DARK_OAK_LOG, 128);
        drop.costs.put(Material.JUNGLE_LOG, 128);
        drop.costs.put(Material.ACACIA_LOG, 128);
        drop.costs.put(Material.MANGROVE_LOG, 64);
        drop.costs.put(Material.CHERRY_LOG, 64);
        drop.costs.put(Material.CRIMSON_STEM, 64);
        drop.costs.put(Material.WARPED_STEM, 64);
        return drop;
    }

    private QuarryRequirement getQuarryRequirement(int level) {
        QuarryRequirement req = new QuarryRequirement();
        if (level == 1) {
            req.materials.costs.put(Material.COBBLESTONE, 192);
            req.materials.costs.put(Material.STONE, 128);
            req.materials.costs.put(Material.DEEPSLATE, 64);
            req.materials.costs.put(Material.STONE_PICKAXE, 2);
            req.money = 5.0D;
            return req;
        }
        if (level == 2) {
            req.materials.costs.put(Material.COBBLESTONE, 64);
            req.materials.costs.put(Material.STONE, 192);
            req.materials.costs.put(Material.DEEPSLATE, 192);
            req.materials.costs.put(Material.GRANITE, 128);
            req.materials.costs.put(Material.DIORITE, 128);
            req.materials.costs.put(Material.SANDSTONE, 192);
            req.materials.costs.put(Material.RED_SANDSTONE, 192);
            req.materials.costs.put(Material.IRON_PICKAXE, 4);
            req.money = 25.0D;
            return req;
        }

        req.materials.costs.put(Material.COBBLESTONE, 64);
        req.materials.costs.put(Material.STONE, 256);
        req.materials.costs.put(Material.DEEPSLATE, 256);
        req.materials.costs.put(Material.GRANITE, 192);
        req.materials.costs.put(Material.DIORITE, 192);
        req.materials.costs.put(Material.SANDSTONE, 192);
        req.materials.costs.put(Material.RED_SANDSTONE, 192);
        req.materials.costs.put(Material.DIAMOND_PICKAXE, 4);
        req.money = 75.0D;
        return req;
    }

    private LevelRequirement getQuarryDrop(int level) {
        LevelRequirement drop = new LevelRequirement();
        if (level == 1) {
            drop.costs.put(Material.COBBLESTONE, 64);
            drop.costs.put(Material.STONE, 64);
            drop.costs.put(Material.DEEPSLATE, 64);
            return drop;
        }
        if (level == 2) {
            drop.costs.put(Material.COBBLESTONE, 128);
            drop.costs.put(Material.STONE, 128);
            drop.costs.put(Material.DEEPSLATE, 128);
            drop.costs.put(Material.GRANITE, 64);
            drop.costs.put(Material.DIORITE, 64);
            drop.costs.put(Material.SANDSTONE, 64);
            drop.costs.put(Material.RED_SANDSTONE, 64);
            return drop;
        }

        drop.costs.put(Material.COBBLESTONE, 192);
        drop.costs.put(Material.STONE, 192);
        drop.costs.put(Material.DEEPSLATE, 192);
        drop.costs.put(Material.GRANITE, 128);
        drop.costs.put(Material.DIORITE, 128);
        drop.costs.put(Material.SANDSTONE, 128);
        drop.costs.put(Material.RED_SANDSTONE, 128);
        return drop;
    }

    private DecorationRequirement getDecorRequirement(int level) {
        DecorationRequirement req = new DecorationRequirement();
        if (level == 1) {
            req.materials.costs.put(Material.QUARTZ, 256);
            req.materials.costs.put(Material.BRICK, 256);
            req.materials.costs.put(Material.STONE, 128);
            req.materials.costs.put(Material.SAND, 128);
            req.materials.costs.put(Material.GLOWSTONE_DUST, 192);
            req.materials.costs.put(Material.FURNACE, 10);
            req.materials.costs.put(Material.CRAFTING_TABLE, 5);
            req.materials.costs.put(Material.STONECUTTER, 2);
            req.materials.costs.put(Material.IRON_SHOVEL, 2);
            req.materials.costs.put(Material.IRON_PICKAXE, 2);
            req.money = 50.0D;
            return req;
        }
        if (level == 2) {
            req.materials.costs.put(Material.QUARTZ_BLOCK, 128);
            req.materials.costs.put(Material.QUARTZ, 192);
            req.materials.costs.put(Material.BRICK, 128);
            req.materials.costs.put(Material.STONE_BRICKS, 128);
            req.materials.costs.put(Material.STONE, 192);
            req.materials.costs.put(Material.DEEPSLATE_BRICKS, 64);
            req.materials.costs.put(Material.COBBLED_DEEPSLATE, 192);
            req.materials.costs.put(Material.SAND, 192);
            req.materials.costs.put(Material.GLOWSTONE_DUST, 256);
            req.materials.costs.put(Material.FURNACE, 25);
            req.materials.costs.put(Material.CRAFTING_TABLE, 15);
            req.materials.costs.put(Material.STONECUTTER, 8);
            req.materials.costs.put(Material.IRON_SHOVEL, 3);
            req.materials.costs.put(Material.IRON_PICKAXE, 3);
            req.materials.costs.put(Material.DIAMOND_SHOVEL, 1);
            req.materials.costs.put(Material.DIAMOND_PICKAXE, 1);
            req.money = 250.0D;
            return req;
        }

        req.materials.costs.put(Material.QUARTZ_BLOCK, 256);
        req.materials.costs.put(Material.BRICK, 192);
        req.materials.costs.put(Material.STONE_BRICKS, 192);
        req.materials.costs.put(Material.STONE, 256);
        req.materials.costs.put(Material.DEEPSLATE_BRICKS, 128);
        req.materials.costs.put(Material.COBBLED_DEEPSLATE, 192);
        req.materials.costs.put(Material.SAND, 192);
        req.materials.costs.put(Material.GLOWSTONE_DUST, 128);
        req.materials.costs.put(Material.KELP, 192);
        req.materials.costs.put(Material.HEART_OF_THE_SEA, 3);
        req.materials.costs.put(Material.FURNACE, 35);
        req.materials.costs.put(Material.CRAFTING_TABLE, 25);
        req.materials.costs.put(Material.STONECUTTER, 20);
        req.materials.costs.put(Material.DIAMOND_SHOVEL, 2);
        req.materials.costs.put(Material.DIAMOND_PICKAXE, 2);
        req.money = 400.0D;
        return req;
    }

    private LevelRequirement getDecorDrop(int level) {
        LevelRequirement drop = new LevelRequirement();
        if (level == 1) {
            drop.costs.put(Material.QUARTZ_BLOCK, 64);
            drop.costs.put(Material.BRICKS, 64);
            drop.costs.put(Material.STONE_BRICKS, 64);
            drop.costs.put(Material.GLASS, 64);
            drop.costs.put(Material.GLOWSTONE, 32);
            return drop;
        }
        if (level == 2) {
            drop.costs.put(Material.QUARTZ_BLOCK, 128);
            drop.costs.put(Material.QUARTZ_PILLAR, 64);
            drop.costs.put(Material.CHISELED_QUARTZ_BLOCK, 64);
            drop.costs.put(Material.BRICKS, 128);
            drop.costs.put(Material.STONE_BRICKS, 128);
            drop.costs.put(Material.CRACKED_STONE_BRICKS, 64);
            drop.costs.put(Material.DEEPSLATE_BRICKS, 64);
            drop.costs.put(Material.DEEPSLATE_TILES, 64);
            drop.costs.put(Material.CHISELED_DEEPSLATE, 64);
            drop.costs.put(Material.GLASS, 128);
            drop.costs.put(Material.GLOWSTONE, 64);
            return drop;
        }

        drop.costs.put(Material.QUARTZ_BLOCK, 256);
        drop.costs.put(Material.QUARTZ_PILLAR, 128);
        drop.costs.put(Material.CHISELED_QUARTZ_BLOCK, 128);
        drop.costs.put(Material.QUARTZ_BRICKS, 64);
        drop.costs.put(Material.SMOOTH_QUARTZ, 64);
        drop.costs.put(Material.BRICKS, 192);
        drop.costs.put(Material.STONE_BRICKS, 192);
        drop.costs.put(Material.CRACKED_STONE_BRICKS, 128);
        drop.costs.put(Material.DEEPSLATE_BRICKS, 128);
        drop.costs.put(Material.DEEPSLATE_TILES, 128);
        drop.costs.put(Material.CHISELED_DEEPSLATE, 128);
        drop.costs.put(Material.GLASS, 256);
        drop.costs.put(Material.GLOWSTONE, 192);
        drop.costs.put(Material.PRISMARINE, 128);
        drop.costs.put(Material.PRISMARINE_BRICKS, 64);
        drop.costs.put(Material.DARK_PRISMARINE, 64);
        return drop;
    }

    private OresRequirement getOresRequirement(int level) {
        OresRequirement req = new OresRequirement();
        if (level == 1) {
            req.materials.costs.put(Material.COAL_BLOCK, 32);
            req.materials.costs.put(Material.COAL, 128);
            req.materials.costs.put(Material.IRON_BLOCK, 16);
            req.materials.costs.put(Material.IRON_INGOT, 64);
            req.materials.costs.put(Material.LAPIS_LAZULI, 64);
            req.materials.costs.put(Material.ANVIL, 3);
            req.materials.costs.put(Material.FURNACE, 5);
            req.materials.costs.put(Material.STONE_PICKAXE, 2);
            req.money = 0.0D;
            return req;
        }
        if (level == 2) {
            req.materials.costs.put(Material.COAL_BLOCK, 64);
            req.materials.costs.put(Material.IRON_BLOCK, 32);
            req.materials.costs.put(Material.LAPIS_BLOCK, 32);
            req.materials.costs.put(Material.GOLD_BLOCK, 16);
            req.materials.costs.put(Material.GOLD_INGOT, 64);
            req.materials.costs.put(Material.DIAMOND_BLOCK, 4);
            req.materials.costs.put(Material.DIAMOND, 16);
            req.materials.costs.put(Material.ANVIL, 8);
            req.materials.costs.put(Material.FURNACE, 5);
            req.materials.costs.put(Material.BLAST_FURNACE, 3);
            req.materials.costs.put(Material.IRON_PICKAXE, 3);
            req.money = 200.0D;
            return req;
        }

        req.materials.costs.put(Material.COAL_BLOCK, 96);
        req.materials.costs.put(Material.IRON_BLOCK, 192);
        req.materials.costs.put(Material.LAPIS_BLOCK, 64);
        req.materials.costs.put(Material.GOLD_BLOCK, 114);
        req.materials.costs.put(Material.DIAMOND_BLOCK, 45);
        req.materials.costs.put(Material.EMERALD_BLOCK, 32);
        req.materials.costs.put(Material.EMERALD, 64);
        req.materials.costs.put(Material.NETHERITE_BLOCK, 6);
        req.materials.costs.put(Material.ANVIL, 15);
        req.materials.costs.put(Material.FURNACE, 10);
        req.materials.costs.put(Material.BLAST_FURNACE, 6);
        req.materials.costs.put(Material.DIAMOND_PICKAXE, 3);
        req.money = 600.0D;
        return req;
    }

    private LevelRequirement getOresDrop(int level) {
        LevelRequirement drop = new LevelRequirement();
        if (level == 1) {
            drop.costs.put(Material.COAL, 128);
            drop.costs.put(Material.IRON_INGOT, 64);
            drop.costs.put(Material.LAPIS_LAZULI, 16);
            return drop;
        }
        if (level == 2) {
            drop.costs.put(Material.COAL, 192);
            drop.costs.put(Material.IRON_INGOT, 128);
            drop.costs.put(Material.LAPIS_LAZULI, 64);
            drop.costs.put(Material.GOLD_INGOT, 32);
            drop.costs.put(Material.DIAMOND, 12);
            return drop;
        }

        drop.costs.put(Material.COAL, 320);
        drop.costs.put(Material.IRON_INGOT, 192);
        drop.costs.put(Material.LAPIS_LAZULI, 128);
        drop.costs.put(Material.GOLD_INGOT, 96);
        drop.costs.put(Material.DIAMOND, 32);
        drop.costs.put(Material.NETHERITE_INGOT, 6);
        return drop;
    }

    private TreasuryRequirement getTreasuryRequirement(int level) {
        TreasuryRequirement req = new TreasuryRequirement();
        if (level == 1) {
            req.materials.costs.put(Material.IRON_BLOCK, 32);
            req.materials.costs.put(Material.IRON_INGOT, 64);
            req.materials.costs.put(Material.GOLD_BLOCK, 20);
            req.materials.costs.put(Material.GOLD_INGOT, 32);
            req.materials.costs.put(Material.DIAMOND_BLOCK, 10);
            req.materials.costs.put(Material.BOOKSHELF, 20);
            req.materials.costs.put(Material.CHEST, 8);
            return req;
        }

        if (level == 2) {
            req.materials.costs.put(Material.IRON_BLOCK, 96);
            req.materials.costs.put(Material.GOLD_BLOCK, 48);
            req.materials.costs.put(Material.GOLD_INGOT, 16);
            req.materials.costs.put(Material.DIAMOND_BLOCK, 32);
            req.materials.costs.put(Material.DIAMOND, 40);
            req.materials.costs.put(Material.BOOKSHELF, 48);
            req.materials.costs.put(Material.CHEST, 28);
            return req;
        }

        req.materials.costs.put(Material.IRON_BLOCK, 192);
        req.materials.costs.put(Material.LAPIS_BLOCK, 64);
        req.materials.costs.put(Material.GOLD_BLOCK, 96);
        req.materials.costs.put(Material.DIAMOND_BLOCK, 64);
        req.materials.costs.put(Material.EMERALD_BLOCK, 21);
        req.materials.costs.put(Material.EMERALD, 16);
        req.materials.costs.put(Material.NETHERITE_BLOCK, 4);
        req.materials.costs.put(Material.BEACON, 1);
        req.materials.costs.put(Material.BOOKSHELF, 64);
        req.materials.costs.put(Material.CHEST, 64);
        return req;
    }

    private TownHallRequirement getTownHallRequirement(int level) {
        TownHallRequirement req = new TownHallRequirement();
        if (level == 1) {
            req.materials.costs.put(Material.DIRT, 192);
            req.materials.costs.put(Material.COBBLESTONE, 128);
            req.materials.costs.put(Material.OAK_LOG, 64);
            req.materials.costs.put(Material.DARK_OAK_LOG, 64);
            req.materials.costs.put(Material.CRAFTING_TABLE, 8);
            req.materials.costs.put(Material.FURNACE, 6);
            req.materials.costs.put(Material.ANVIL, 4);
            req.materials.costs.put(Material.STONECUTTER, 2);
            req.materials.costs.put(Material.SMOKER, 1);
            req.materials.costs.put(Material.BLAST_FURNACE, 1);
            req.materials.costs.put(Material.IRON_INGOT, 160);
            req.materials.costs.put(Material.GOLD_INGOT, 64);
            req.materials.costs.put(Material.STONE_SHOVEL, 4);
            req.materials.costs.put(Material.STONE_PICKAXE, 4);
            req.materials.costs.put(Material.STONE_AXE, 4);
            req.money = 10.0D;
            return req;
        }

        if (level == 2) {
            req.materials.costs.put(Material.DIRT, 128);
            req.materials.costs.put(Material.COBBLESTONE, 64);
            req.materials.costs.put(Material.STONE, 128);
            req.materials.costs.put(Material.STONE_BRICKS, 128);
            req.materials.costs.put(Material.SPRUCE_LOG, 192);
            req.materials.costs.put(Material.ACACIA_LOG, 192);
            req.materials.costs.put(Material.CRAFTING_TABLE, 14);
            req.materials.costs.put(Material.FURNACE, 10);
            req.materials.costs.put(Material.ANVIL, 10);
            req.materials.costs.put(Material.STONECUTTER, 5);
            req.materials.costs.put(Material.SMOKER, 5);
            req.materials.costs.put(Material.BLAST_FURNACE, 3);
            req.materials.costs.put(Material.GRINDSTONE, 2);
            req.materials.costs.put(Material.SMITHING_TABLE, 2);
            req.materials.costs.put(Material.IRON_BLOCK, 32);
            req.materials.costs.put(Material.GOLD_BLOCK, 16);
            req.materials.costs.put(Material.DIAMOND_BLOCK, 10);
            req.materials.costs.put(Material.IRON_INGOT, 64);
            req.materials.costs.put(Material.GOLD_INGOT, 64);
            req.materials.costs.put(Material.DIAMOND, 64);
            req.materials.costs.put(Material.IRON_SHOVEL, 2);
            req.materials.costs.put(Material.IRON_PICKAXE, 2);
            req.materials.costs.put(Material.IRON_AXE, 2);
            req.money = 300.0D;
            return req;
        }

        req.materials.costs.put(Material.STONE, 192);
        req.materials.costs.put(Material.STONE_BRICKS, 128);
        req.materials.costs.put(Material.DEEPSLATE_BRICKS, 128);
        req.materials.costs.put(Material.OAK_LOG, 192);
        req.materials.costs.put(Material.CHERRY_LOG, 128);
        req.materials.costs.put(Material.CRAFTING_TABLE, 20);
        req.materials.costs.put(Material.FURNACE, 12);
        req.materials.costs.put(Material.ANVIL, 14);
        req.materials.costs.put(Material.STONECUTTER, 12);
        req.materials.costs.put(Material.SMOKER, 10);
        req.materials.costs.put(Material.BLAST_FURNACE, 10);
        req.materials.costs.put(Material.GRINDSTONE, 6);
        req.materials.costs.put(Material.SMITHING_TABLE, 4);
        req.materials.costs.put(Material.FLETCHING_TABLE, 4);
        req.materials.costs.put(Material.CARTOGRAPHY_TABLE, 2);
        req.materials.costs.put(Material.ENCHANTING_TABLE, 2);
        req.materials.costs.put(Material.BEACON, 1);
        req.materials.costs.put(Material.IRON_BLOCK, 64);
        req.materials.costs.put(Material.GOLD_BLOCK, 32);
        req.materials.costs.put(Material.DIAMOND_BLOCK, 20);
        req.materials.costs.put(Material.EMERALD_BLOCK, 8);
        req.materials.costs.put(Material.GOLD_INGOT, 32);
        req.materials.costs.put(Material.DIAMOND, 16);
        req.materials.costs.put(Material.EMERALD, 8);
        req.materials.costs.put(Material.NETHERITE_INGOT, 4);
        req.materials.costs.put(Material.DIAMOND_SHOVEL, 1);
        req.materials.costs.put(Material.DIAMOND_PICKAXE, 1);
        req.money = 700.0D;
        return req;
    }

    private void sendRequirementList(Player player, LevelRequirement req, String header) {
        sendChat(player, ChatColor.YELLOW + header);
        for (Map.Entry<Material, Integer> entry : req.costs.entrySet()) {
            int have = countMaterial(player, entry.getKey());
            sendChat(player, ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()) + ChatColor.DARK_GRAY + " (есть: " + have + ")");
        }
    }

    private void sendLumberRequirementList(Player player, LumberRequirement req, String header) {
        sendChat(player, ChatColor.YELLOW + header);
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            int have = countMaterial(player, entry.getKey());
            sendChat(player, ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()) + ChatColor.DARK_GRAY + " (есть: " + have + ")");
        }
        double balance = getMoney(player);
        sendChat(player, ChatColor.GRAY + "- Деньги: " + (int) req.money + "$" + ChatColor.DARK_GRAY + " (есть: " + (int) balance + "$)");
    }

    private void sendQuarryRequirementList(Player player, QuarryRequirement req, String header) {
        sendChat(player, ChatColor.YELLOW + header);
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            int have = countMaterial(player, entry.getKey());
            sendChat(player, ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()) + ChatColor.DARK_GRAY + " (есть: " + have + ")");
        }
        double balance = getMoney(player);
        sendChat(player, ChatColor.GRAY + "- Деньги: " + (int) req.money + "$" + ChatColor.DARK_GRAY + " (есть: " + (int) balance + "$)");
    }

    private void sendDecorRequirementList(Player player, DecorationRequirement req, String header) {
        sendChat(player, ChatColor.YELLOW + header);
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            int have = countMaterial(player, entry.getKey());
            sendChat(player, ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()) + ChatColor.DARK_GRAY + " (есть: " + have + ")");
        }
        double balance = getMoney(player);
        sendChat(player, ChatColor.GRAY + "- Деньги: " + (int) req.money + "$" + ChatColor.DARK_GRAY + " (есть: " + (int) balance + "$)");
    }

    private void sendOresRequirementList(Player player, OresRequirement req, String header) {
        sendChat(player, ChatColor.YELLOW + header);
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            int have = countMaterial(player, entry.getKey());
            sendChat(player, ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey()) + ChatColor.DARK_GRAY + " (есть: " + have + ")");
        }
        double balance = getMoney(player);
        sendChat(player, ChatColor.GRAY + "- Деньги: " + (int) req.money + "$" + ChatColor.DARK_GRAY + " (есть: " + (int) balance + "$)");
    }

    private void sendTreasuryRequirementList(Player player, TreasuryRequirement req, String header) {
        sendChat(player, ChatColor.YELLOW + header);
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            int have = countMaterial(player, entry.getKey());
            sendChat(player, ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey())
                    + ChatColor.DARK_GRAY + " (есть: " + have + ")");
        }
    }

    private void sendTownHallRequirementList(Player player, TownHallRequirement req, String header) {
        sendChat(player, ChatColor.YELLOW + header);
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            int have = countMaterial(player, entry.getKey());
            sendChat(player, ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey())
                    + ChatColor.DARK_GRAY + " (есть: " + have + ")");
        }
        double balance = getMoney(player);
        sendChat(player, ChatColor.GRAY + "- Деньги: " + (int) req.money + "$"
                + ChatColor.DARK_GRAY + " (есть: " + (int) balance + "$)");
    }

    private void sendMiningRequirementList(Player player, MiningRequirement req, String header) {
        sendChat(player, ChatColor.YELLOW + header);
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            int have = countMaterial(player, entry.getKey());
            sendChat(player, ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey())
                    + ChatColor.DARK_GRAY + " (есть: " + have + ")");
        }
        if (req.money > 0) {
            double balance = getMoney(player);
            sendChat(player, ChatColor.GRAY + "- Деньги: " + (int) req.money + "$"
                    + ChatColor.DARK_GRAY + " (есть: " + (int) balance + "$)");
        }
    }

    private void sendTailorRequirementList(Player player, TailorRequirement req, String header) {
        sendChat(player, ChatColor.YELLOW + header);
        for (Map.Entry<Material, Integer> entry : req.materials.costs.entrySet()) {
            int have = countMaterial(player, entry.getKey());
            sendChat(player, ChatColor.GRAY + "- " + entry.getValue() + " x " + prettyMaterial(entry.getKey())
                    + ChatColor.DARK_GRAY + " (есть: " + have + ")");
        }
        if (req.money > 0) {
            double balance = getMoney(player);
            sendChat(player, ChatColor.GRAY + "- Деньги: " + (int) req.money + "$"
                    + ChatColor.DARK_GRAY + " (есть: " + (int) balance + "$)");
        }
    }

    private boolean hasRequiredTownInventoryLevel(Player player, String townName, int neededLevel, String buildingName) {
        int invLevel = getTownLevel(townName);
        if (invLevel >= neededLevel) {
            return true;
        }

        notifyPlayer(player, ChatColor.RED,
                "Для прокачки здания '" + buildingName + "' до " + neededLevel + " ур. нужен Инвентарь Города " + neededLevel + " ур.");

        LevelRequirement invRequirement = getRequirement(neededLevel);
        sendRequirementList(player, invRequirement,
                "Сначала открой Инвентарь Города " + neededLevel + " ур. Требования:");
        return false;
    }

    private String prettyMaterial(Material material) {
        switch (material) {
            case CHEST:
                return "Сундуки";
            case OAK_LOG:
                return "Дуб";
            case BIRCH_LOG:
                return "Берёза";
            case SPRUCE_LOG:
                return "Ель";
            case IRON_INGOT:
                return "Слитки железа";
            case IRON_PICKAXE:
                return "Железные кирки";
            case JUNGLE_LOG:
                return "Тропическое дерево";
            case DARK_OAK_LOG:
                return "Тёмный дуб";
            case GOLD_INGOT:
                return "Золотые слитки";
            case ACACIA_LOG:
                return "Акация";
            case CHERRY_LOG:
                return "Вишневое дерево";
            case MANGROVE_LOG:
                return "Мангровое дерево";
            case CRIMSON_STEM:
                return "Багровое дерево";
            case WARPED_STEM:
                return "Искажённое дерево";
            case IRON_AXE:
                return "Железный топор";
            case DIAMOND_AXE:
                return "Алмазный топор";
            case COBBLESTONE:
                return "Булыжник";
            case STONE:
                return "Камень";
            case DEEPSLATE:
                return "Глубинный сланец";
            case GRANITE:
                return "Гранит";
            case DIORITE:
                return "Диорит";
            case SANDSTONE:
                return "Песчаник";
            case RED_SANDSTONE:
                return "Красный песчаник";
            case STONE_PICKAXE:
                return "Каменная кирка";
            case DIAMOND_PICKAXE:
                return "Алмазная кирка";
            case QUARTZ_BLOCK:
                return "Кварцевый блок";
            case QUARTZ_PILLAR:
                return "Кварцевая колонна";
            case CHISELED_QUARTZ_BLOCK:
                return "Резной кварцевый блок";
            case QUARTZ_BRICKS:
                return "Кварцевый кирпич";
            case SMOOTH_QUARTZ:
                return "Гладкий кварцевый блок";
            case BRICKS:
                return "Кирпичный блок";
            case BRICK:
                return "Кирпич";
            case STONE_BRICKS:
                return "Каменные кирпичи";
            case CRACKED_STONE_BRICKS:
                return "Резаные каменные кирпичи";
            case DEEPSLATE_BRICKS:
                return "Глубинносланцевые кирпичи";
            case DEEPSLATE_TILES:
                return "Плитки из глубинного сланца";
            case CHISELED_DEEPSLATE:
                return "Резной глубинный сланец";
            case COBBLED_DEEPSLATE:
                return "Колотый глубинный сланец";
            case GLASS:
                return "Стекло";
            case GLOWSTONE:
                return "Светокамень";
            case GLOWSTONE_DUST:
                return "Светопыль";
            case QUARTZ:
                return "Незер-кварц";
            case SAND:
                return "Песок";
            case FURNACE:
                return "Печка";
            case CRAFTING_TABLE:
                return "Верстак";
            case STONECUTTER:
                return "Камнетёс";
            case BOOKSHELF:
                return "Книжная полка";
            case BEACON:
                return "Маяк";
            case IRON_SHOVEL:
                return "Железная лопата";
            case DIAMOND_SHOVEL:
                return "Алмазная лопата";
            case KELP:
                return "Ламинарий";
            case HEART_OF_THE_SEA:
                return "Сердце моря";
            case PRISMARINE:
                return "Призмарин";
            case PRISMARINE_BRICKS:
                return "Призмариновые кирпичи";
            case DARK_PRISMARINE:
                return "Тёмный призмарин";
            case COAL:
                return "Уголь";
            case COAL_BLOCK:
                return "Угольный блок";
            case IRON_BLOCK:
                return "Железный блок";
            case LAPIS_LAZULI:
                return "Лазурит";
            case LAPIS_BLOCK:
                return "Лазуритовый блок";
            case GOLD_BLOCK:
                return "Золотой блок";
            case DIAMOND_BLOCK:
                return "Алмазный блок";
            case EMERALD:
                return "Изумруд";
            case EMERALD_BLOCK:
                return "Изумрудный блок";
            case NETHERITE_INGOT:
                return "Незеритовый слиток";
            case NETHERITE_BLOCK:
                return "Незеритовый блок";
            case ANVIL:
                return "Наковальня";
            case BLAST_FURNACE:
                return "Плавильная печь";
            case DIRT:
                return "Земля";
            case SMOKER:
                return "Коптильня";
            case STONE_SHOVEL:
                return "Каменная лопата";
            case STONE_AXE:
                return "Каменный топор";
            case GRINDSTONE:
                return "Точило";
            case SMITHING_TABLE:
                return "Стол кузница";
            case FLETCHING_TABLE:
                return "Стол лучника";
            case CARTOGRAPHY_TABLE:
                return "Стол картографа";
            case ENCHANTING_TABLE:
                return "Чародейский стол";
            case GRAVEL:
                return "Гравий";
            case RED_SAND:
                return "Красный песок";
            case ICE:
                return "Лёд";
            case GRASS_BLOCK:
                return "Дёрн";
            case WHITE_WOOL:
                return "Белая шерсть";
            case LIGHT_GRAY_WOOL:
                return "Светло-серая шерсть";
            case GRAY_WOOL:
                return "Серая шерсть";
            case BLACK_WOOL:
                return "Чёрная шерсть";
            case BROWN_WOOL:
                return "Коричневая шерсть";
            case RED_WOOL:
                return "Красная шерсть";
            case ORANGE_WOOL:
                return "Оранжевая шерсть";
            case YELLOW_WOOL:
                return "Жёлтая шерсть";
            case LIME_WOOL:
                return "Лаймовая шерсть";
            case GREEN_WOOL:
                return "Зелёная шерсть";
            case CYAN_WOOL:
                return "Бирюзовая шерсть";
            case LIGHT_BLUE_WOOL:
                return "Голубая шерсть";
            case BLUE_WOOL:
                return "Синяя шерсть";
            case PURPLE_WOOL:
                return "Фиолетовая шерсть";
            case MAGENTA_WOOL:
                return "Пурпурная шерсть";
            case STRING:
                return "Нить";
            case LOOM:
                return "Ткацкий станок";
            case SHEARS:
                return "Ножницы";
            case CAULDRON:
                return "Котёл";
            case LIGHT_GRAY_DYE:
                return "Светло-серый краситель";
            case GRAY_DYE:
                return "Серый краситель";
            case BLACK_DYE:
                return "Чёрный краситель";
            case BROWN_DYE:
                return "Коричневый краситель";
            case RED_DYE:
                return "Красный краситель";
            case ORANGE_DYE:
                return "Оранжевый краситель";
            case YELLOW_DYE:
                return "Жёлтый краситель";
            case LIME_DYE:
                return "Лаймовый краситель";
            case GREEN_DYE:
                return "Зелёный краситель";
            case CYAN_DYE:
                return "Бирюзовый краситель";
            case LIGHT_BLUE_DYE:
                return "Голубой краситель";
            case BLUE_DYE:
                return "Синий краситель";
            case PURPLE_DYE:
                return "Фиолетовый краситель";
            case MAGENTA_DYE:
                return "Пурпурный краситель";
            case PINK_DYE:
                return "Розовый краситель";
            default:
                return "Алмазы";
        }
    }

    private Object getEconomyProvider() {
        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration(economyClass);
            if (rsp == null) {
                return null;
            }
            return rsp.getProvider();
        } catch (Exception ex) {
            return null;
        }
    }

    private boolean hasEnoughMoney(Player player, double amount) {
        if (amount <= 0) {
            return true;
        }
        Object eco = getEconomyProvider();
        if (eco == null) {
            return false;
        }
        try {
            Method has = eco.getClass().getMethod("has", OfflinePlayer.class, double.class);
            Object result = has.invoke(eco, player, amount);
            return result instanceof Boolean && ((Boolean) result).booleanValue();
        } catch (Exception ignored) {
            try {
                Method has = eco.getClass().getMethod("has", String.class, double.class);
                Object result = has.invoke(eco, player.getName(), amount);
                return result instanceof Boolean && ((Boolean) result).booleanValue();
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private double getMoney(Player player) {
        Object eco = getEconomyProvider();
        if (eco == null) {
            return 0.0D;
        }
        try {
            Method getBalance = eco.getClass().getMethod("getBalance", OfflinePlayer.class);
            Object value = getBalance.invoke(eco, player);
            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }
        } catch (Exception ignored) {
            try {
                Method getBalance = eco.getClass().getMethod("getBalance", String.class);
                Object value = getBalance.invoke(eco, player.getName());
                if (value instanceof Number) {
                    return ((Number) value).doubleValue();
                }
            } catch (Exception ex) {
                return 0.0D;
            }
        }
        return 0.0D;
    }

    private boolean withdrawMoney(Player player, double amount) {
        if (amount <= 0) {
            return true;
        }
        Object eco = getEconomyProvider();
        if (eco == null) {
            return false;
        }
        try {
            Method withdraw = eco.getClass().getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            withdraw.invoke(eco, player, amount);
            return true;
        } catch (Exception ignored) {
            try {
                Method withdraw = eco.getClass().getMethod("withdrawPlayer", String.class, double.class);
                withdraw.invoke(eco, player.getName(), amount);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private boolean depositMoney(Player player, double amount) {
        if (amount <= 0) {
            return true;
        }

        Object eco = getEconomyProvider();
        if (eco == null) {
            return false;
        }

        try {
            Method deposit = eco.getClass().getMethod("depositPlayer", OfflinePlayer.class, double.class);
            deposit.invoke(eco, player, amount);
            return true;
        } catch (Exception ignored) {
            try {
                Method deposit = eco.getClass().getMethod("depositPlayer", String.class, double.class);
                deposit.invoke(eco, player.getName(), amount);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }

    private double parsePositiveAmount(String raw) {
        if (raw == null) {
            return -1.0D;
        }
        String normalized = raw.trim().replace(',', '.');
        try {
            double value = Double.parseDouble(normalized);
            if (value <= 0.0D) {
                return -1.0D;
            }
            return value;
        } catch (NumberFormatException ex) {
            return -1.0D;
        }
    }

    private String formatMoney(double amount) {
        if (Math.abs(amount - Math.rint(amount)) < 1.0E-9D) {
            return String.valueOf((long) Math.rint(amount));
        }
        return String.format(Locale.US, "%.2f", amount);
    }

    private Map<String, Integer> getOnlineTownResidents() {
        Map<String, Integer> result = new HashMap<String, Integer>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            String townName = resolveTownName(player);
            if (townName == null || townName.trim().isEmpty()) {
                continue;
            }

            String townKey = normalizeTownKey(townName);
            Integer count = result.get(townKey);
            result.put(townKey, Integer.valueOf(count == null ? 1 : count.intValue() + 1));
        }
        return result;
    }

    private void notifyPlayer(Player player, ChatColor color, String text) {
        sendChat(player, color + text);
    }

    private void sendChat(Player player, String text) {
        try {
            Method sendMessageMethod = player.getClass().getMethod("sendMessage", String.class);
            sendMessageMethod.invoke(player, text);
        } catch (Exception ex) {
            getLogger().warning("Cannot send chat message: " + ex.getMessage());
        }
    }

    private String resolveTownName(Player player) {
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Method getInstance = universeClass.getMethod("getInstance");
            Object universe = getInstance.invoke(null);

            Method getResident = universeClass.getMethod("getResident", UUID.class);
            Object resident = getResident.invoke(universe, player.getUniqueId());
            if (resident == null) {
                return null;
            }

            Method getTownOrNull = resident.getClass().getMethod("getTownOrNull");
            Object town = getTownOrNull.invoke(resident);
            if (town == null) {
                return null;
            }

            Method getName = town.getClass().getMethod("getName");
            return String.valueOf(getName.invoke(town));
        } catch (Exception ex) {
            return null;
        }
    }

    private TownAccess resolveTownAccess(Player player) {
        try {
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Method getInstance = universeClass.getMethod("getInstance");
            Object universe = getInstance.invoke(null);

            Method getResident = universeClass.getMethod("getResident", UUID.class);
            Object resident = getResident.invoke(universe, player.getUniqueId());
            if (resident == null) {
                return null;
            }

            Method getTownOrNull = resident.getClass().getMethod("getTownOrNull");
            Object town = getTownOrNull.invoke(resident);
            if (town == null) {
                return null;
            }

            Method getName = town.getClass().getMethod("getName");
            String townName = String.valueOf(getName.invoke(town));

            Method getMayor = town.getClass().getMethod("getMayor");
            Object mayorResident = getMayor.invoke(town);
            Method getUUID = mayorResident.getClass().getMethod("getUUID");
            UUID mayorUuid = (UUID) getUUID.invoke(mayorResident);

            UUID managerUuid = null;
            String stored = data.getString(townPath(townName) + ".manager");
            if (stored != null && !stored.trim().isEmpty()) {
                try {
                    managerUuid = UUID.fromString(stored);
                } catch (IllegalArgumentException ignored) {
                }
            }

            boolean isMayor = player.getUniqueId().equals(mayorUuid);
            boolean isManager = managerUuid != null && player.getUniqueId().equals(managerUuid);
            return new TownAccess(townName, isMayor, isManager);
        } catch (Exception ex) {
            getLogger().warning("Towny resolve failed: " + ex.getMessage());
            return null;
        }
    }

    // ===== Dynmap town data export =====

    private void startTownyDataExportTask() {
        // First export after 5 seconds, then every 5 minutes
        getServer().getScheduler().runTaskTimerAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                writeTownyDataJson();
            }
        }, 100L, 20L * 60L * 15L);
    }

    private void writeTownyDataJson() {
        try {
            getLogger().info("[TownBuilds] Экспорт towny-data.json запущен...");
            Class<?> universeClass = Class.forName("com.palmergames.bukkit.towny.TownyUniverse");
            Method getInstance = universeClass.getMethod("getInstance");
            Object universe = getInstance.invoke(null);

            java.util.Collection<?> towns = null;
            try {
                Method getTowns = universeClass.getMethod("getTowns");
                Object result = getTowns.invoke(universe);
                if (result instanceof java.util.Collection) {
                    towns = (java.util.Collection<?>) result;
                }
            } catch (Exception ignored) {}

            if (towns == null) {
                try {
                    Method getTownsMap = universeClass.getMethod("getTownsMap");
                    Object result = getTownsMap.invoke(universe);
                    if (result instanceof Map) {
                        towns = ((Map<?, ?>) result).values();
                    }
                } catch (Exception ignored) {}
            }

            if (towns == null) {
                getLogger().warning("[TownBuilds] writeTownyDataJson: getTowns() и getTownsMap() вернули null — Towny не загружен?");
                return;
            }
            getLogger().info("[TownBuilds] Найдено городов: " + towns.size());

            StringBuilder sb = new StringBuilder("{\n");
            boolean firstTown = true;

            for (Object town : towns) {
                try {
                    String townName = String.valueOf(town.getClass().getMethod("getName").invoke(town));

                    String mayorName = null;
                    try {
                        Object mayor = town.getClass().getMethod("getMayor").invoke(town);
                        if (mayor != null) mayorName = String.valueOf(mayor.getClass().getMethod("getName").invoke(mayor));
                    } catch (Exception ignored) {}

                    String nationName = null;
                    try {
                        Method getNationOrNull = town.getClass().getMethod("getNationOrNull");
                        Object nation = getNationOrNull.invoke(town);
                        if (nation != null) nationName = String.valueOf(nation.getClass().getMethod("getName").invoke(nation));
                    } catch (Exception ignored) {}

                    List<String> residentNames = new ArrayList<String>();
                    try {
                        java.util.Collection<?> residents = (java.util.Collection<?>) town.getClass().getMethod("getResidents").invoke(town);
                        for (Object res : residents) {
                            residentNames.add(String.valueOf(res.getClass().getMethod("getName").invoke(res)));
                        }
                    } catch (Exception ignored) {}

                    double balance = 0.0;
                    try {
                        Object account = town.getClass().getMethod("getAccount").invoke(town);
                        for (String bm : new String[]{"getHoldingBalance", "getBalance"}) {
                            try {
                                Object val = account.getClass().getMethod(bm).invoke(account);
                                if (val instanceof Number) { balance = ((Number) val).doubleValue(); break; }
                            } catch (Exception ignored2) {}
                        }
                    } catch (Exception ignored) {}

                    double tax = 0.0;
                    try {
                        for (String tm : new String[]{"getTaxes", "getPlotTax", "getTax"}) {
                            try {
                                Object val = town.getClass().getMethod(tm).invoke(town);
                                if (val instanceof Number) { tax = ((Number) val).doubleValue(); break; }
                            } catch (Exception ignored2) {}
                        }
                    } catch (Exception ignored) {}

                    boolean isPublic = false;
                    try {
                        Object val = town.getClass().getMethod("isPublic").invoke(town);
                        if (val instanceof Boolean) isPublic = (Boolean) val;
                    } catch (Exception ignored) {}

                    String founded = null;
                    try {
                        Object val = town.getClass().getMethod("getRegistered").invoke(town);
                        if (val instanceof Long) {
                            long reg = (Long) val;
                            if (reg > 0) {
                                long millis = reg > 1_000_000_000_000L ? reg : reg * 86400000L;
                                java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("dd.MM.yyyy");
                                founded = sdf.format(new java.util.Date(millis));
                            }
                        }
                    } catch (Exception ignored) {}

                    if (!firstTown) sb.append(",\n");
                    firstTown = false;
                    sb.append("  ").append(twJsonEsc(townName)).append(": {");
                    sb.append("\n    \"mayor\": ").append(twJsonVal(mayorName));
                    sb.append(",\n    \"nation\": ").append(twJsonVal(nationName));
                    sb.append(",\n    \"balance\": ").append(String.format(Locale.US, "%.2f", balance));
                    sb.append(",\n    \"tax\": ").append(String.format(Locale.US, "%.2f", tax));
                    sb.append(",\n    \"public\": ").append(isPublic);
                    if (founded != null) sb.append(",\n    \"founded\": ").append(twJsonVal(founded));
                    sb.append(",\n    \"residents\": [");
                    for (int i = 0; i < residentNames.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(twJsonVal(residentNames.get(i)));
                    }
                    sb.append("]\n  }");
                } catch (Exception ex) {
                    getLogger().warning("[TownBuilds] Export town: " + ex.getMessage());
                }
            }

            sb.append("\n}");

            File dynmapWebDir = new File(getDataFolder().getParentFile(), "dynmap/web");
            getLogger().info("[TownBuilds] Путь для записи: " + dynmapWebDir.getAbsolutePath());
            if (!dynmapWebDir.exists()) {
                boolean created = dynmapWebDir.mkdirs();
                getLogger().info("[TownBuilds] Создана папка dynmap/web: " + created);
            }
            File outFile = new File(dynmapWebDir, "towny-data.json");
            FileWriter fw = new FileWriter(outFile, false);
            try { fw.write(sb.toString()); } finally { fw.close(); }
            getLogger().info("[TownBuilds] towny-data.json записан: " + outFile.getAbsolutePath());

        } catch (Exception ex) {
            getLogger().warning("[TownBuilds] writeTownyDataJson ОШИБКА: " + ex.getClass().getName() + ": " + ex.getMessage());
        }
    }

    private static String twJsonEsc(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String twJsonVal(String s) {
        return s == null ? "null" : twJsonEsc(s);
    }

    // ===== End dynmap export =====

    private static final class Upgrade {
        final String name;
        final String description;
        final String headOwner;

        Upgrade(String name, String description, String headOwner) {
            this.name = name;
            this.description = description;
            this.headOwner = headOwner;
        }
    }

    private static final class LevelRequirement {
        final Map<Material, Integer> costs = new EnumMap<Material, Integer>(Material.class);
    }

    private static final class LumberRequirement {
        final LevelRequirement materials = new LevelRequirement();
        double money;
    }

    private static final class QuarryRequirement {
        final LevelRequirement materials = new LevelRequirement();
        double money;
    }

    private static final class DecorationRequirement {
        final LevelRequirement materials = new LevelRequirement();
        double money;
    }

    private static final class OresRequirement {
        final LevelRequirement materials = new LevelRequirement();
        double money;
    }

    private static final class TreasuryRequirement {
        final LevelRequirement materials = new LevelRequirement();
    }

    private static final class TownHallRequirement {
        final LevelRequirement materials = new LevelRequirement();
        double money;
    }

    private static final class MiningRequirement {
        final LevelRequirement materials = new LevelRequirement();
        double money;
    }

    private static final class TailorRequirement {
        final LevelRequirement materials = new LevelRequirement();
        double money;
    }

    private static final class ProductionResult {
        int townsChecked;
        int townsProduced;
        int treasuryProduced;
    }

    private static final class TreasuryTickResult {
        boolean changed;
        boolean produced;
    }

    private static final class TownAccess {
        final String townName;
        final boolean isMayor;
        final boolean isManager;
        final boolean canAccess;

        TownAccess(String townName, boolean isMayor, boolean isManager) {
            this.townName = townName;
            this.isMayor = isMayor;
            this.isManager = isManager;
            this.canAccess = isMayor || isManager;
        }
    }

    private static final class OpenTownInventoryContext {
        final String townName;
        final int page;
        final int level;

        OpenTownInventoryContext(String townName, int page, int level) {
            this.townName = townName;
            this.page = page;
            this.level = level;
        }
    }

    // ── TAB: обновление префикса при вступлении/выходе из города ───────────

    // Группы LuckPerms, для которых нельзя перекрывать префикс названием города
    private static final String[] STAFF_GROUPS = {
            "osnovatel", "zam", "admin", "moderator", "pomoshnik", "stazher"
    };

    private boolean isStaff(Player player) {
        if (player.isOp()) return true;
        for (String group : STAFF_GROUPS) {
            if (player.hasPermission("group." + group)) return true;
        }
        return false;
    }

    private void clearTabOverride(Player player) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tab player " + player.getName() + " remove");
    }

    @EventHandler
    public void onPlayerJoinForTab(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && isStaff(player)) clearTabOverride(player);
        }, 20L);
    }

    @EventHandler
    public void onTownAddResident(TownAddResidentEvent event) {
        Resident resident = event.getResident();
        Player player = Bukkit.getPlayer(resident.getName());
        if (player == null) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            try {
                clearTabOverride(player);
                if (isStaff(player)) return; // после очистки TAB сам показывает ранг персонала
                Town town = resident.getTownOrNull();
                if (town == null) return;
                String name = town.getName();
                String prefix = "&8[&7" + name + "&8] &f";
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tab player " + player.getName() + " tabprefix " + prefix);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tab player " + player.getName() + " tagprefix " + prefix);
            } catch (Exception ignored) {}
        }, 5L);
    }

    @EventHandler
    public void onTownRemoveResident(TownRemoveResidentEvent event) {
        Resident resident = event.getResident();
        Player player = Bukkit.getPlayer(resident.getName());
        if (player == null) return;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            clearTabOverride(player);
            if (isStaff(player)) return; // после очистки TAB сам показывает ранг персонала
            // Явно ставим [Игрок] — не надеемся на сброс к группе (TAB кеширует)
            String noTownPrefix = "&8[&7\u0418\u0433\u0440\u043e\u043a&8] &f";
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tab player " + player.getName() + " tabprefix " + noTownPrefix);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "tab player " + player.getName() + " tagprefix " + noTownPrefix);
        }, 5L);
    }
}
