package com.contest.sell;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class SellPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int MENU_SIZE = 54;      // 6 rows
    private static final int CONFIRM_SLOT = 53;   // bottom-right lime pane

    /** Shared key for the future /settings plugin. 0 = player muted plugin sounds. */
    private static final NamespacedKey SOUNDS_KEY = NamespacedKey.fromString("settings:sounds_enabled");

    private static final String PREFIX = "&6&lSell &r>> ";

    // ---- price data (loaded from config) --------------------------------

    /** Prices set by hand in config.yml. */
    private final Map<Material, Double> prices = new EnumMap<>(Material.class);
    /** Automatic prices for every other item in the game (so literally everything sells). */
    private final Map<Material, Double> autoPrices = new EnumMap<>(Material.class);
    private final Set<Material> blacklist = EnumSet.noneOf(Material.class);
    private final Map<String, Double> enchantValues = new HashMap<>();
    private final List<Rule> rules = new ArrayList<>();
    private final Map<String, Double> trimPatternBonus = new HashMap<>();
    private final Map<String, Double> trimMaterialBonus = new HashMap<>();

    /** One entry of special-items: material(s) + minimum enchant levels (+ optional trim) -> price. */
    private record Rule(String id, List<String> materials, Map<String, Integer> enchants,
                        String trimPattern, String trimMaterial, double price) {

        boolean matches(Material type, Map<String, Integer> have, String pattern, String trimMat) {
            if (!materialMatches(type)) return false;
            for (Map.Entry<String, Integer> e : enchants.entrySet()) {
                Integer level = have.get(e.getKey());
                if (level == null || level < e.getValue()) return false;
            }
            if (trimPattern != null && !trimPattern.equals(pattern)) return false;
            if (trimMaterial != null && !trimMaterial.equals(trimMat)) return false;
            return true;
        }

        private boolean materialMatches(Material type) {
            String name = type.name();
            for (String m : materials) {
                if (m.startsWith("*")) {
                    if (name.endsWith(m.substring(1))) return true;
                } else if (m.endsWith("*")) {
                    if (name.startsWith(m.substring(0, m.length() - 1))) return true;
                } else if (name.equals(m)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static class SellHolder implements InventoryHolder {
        Inventory inventory;
        boolean sold = false;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------- lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPrices();
        getServer().getPluginManager().registerEvents(this, this);

        var cmd = getCommand("sell");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        // Close open sell menus so nobody loses the items they put in.
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof SellHolder) {
                p.closeInventory();
            }
        }
    }

    private void loadPrices() {
        prices.clear();
        autoPrices.clear();
        blacklist.clear();
        enchantValues.clear();
        rules.clear();
        trimPatternBonus.clear();
        trimMaterialBonus.clear();

        ConfigurationSection section = getConfig().getConfigurationSection("prices");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                double price = section.getDouble(key);
                if (material == null) {
                    getLogger().warning("Unknown material in prices: " + key);
                } else if (price > 0) {
                    prices.put(material, price);
                }
            }
        }

        ConfigurationSection special = getConfig().getConfigurationSection("special-items");
        if (special != null) {
            for (String id : special.getKeys(false)) {
                ConfigurationSection r = special.getConfigurationSection(id);
                if (r == null) continue;

                List<String> mats = new ArrayList<>();
                if (r.isList("materials")) {
                    for (String m : r.getStringList("materials")) mats.add(m.toUpperCase(Locale.ROOT));
                } else if (r.isString("materials")) {
                    mats.add(r.getString("materials", "").toUpperCase(Locale.ROOT));
                } else if (r.isString("material")) {
                    mats.add(r.getString("material", "").toUpperCase(Locale.ROOT));
                }

                Map<String, Integer> enchants = new HashMap<>();
                ConfigurationSection e = r.getConfigurationSection("enchants");
                if (e != null) {
                    for (String k : e.getKeys(false)) enchants.put(k.toLowerCase(Locale.ROOT), e.getInt(k));
                }

                String tp = r.getString("trim-pattern");
                String tm = r.getString("trim-material");
                double price = r.getDouble("price");
                if (mats.isEmpty() || price <= 0) {
                    getLogger().warning("Skipping special item '" + id + "' (needs materials and a price).");
                    continue;
                }
                rules.add(new Rule(id, mats, enchants,
                        tp == null ? null : tp.toLowerCase(Locale.ROOT),
                        tm == null ? null : tm.toLowerCase(Locale.ROOT), price));
            }
        }

        for (String name : getConfig().getStringList("blacklist")) {
            Material m = Material.matchMaterial(name);
            if (m != null) blacklist.add(m);
        }

        enchantValues.putAll(defaultEnchantValues());
        ConfigurationSection ev = getConfig().getConfigurationSection("enchant-values");
        if (ev != null) {
            for (String k : ev.getKeys(false)) enchantValues.put(k.toLowerCase(Locale.ROOT), ev.getDouble(k));
        }

        // Every item that has no hand-set price gets an automatic one.
        for (Material m : Material.values()) {
            if (m.name().startsWith("LEGACY_") || !m.isItem() || m.isAir() || blacklist.contains(m)) continue;
            if (!prices.containsKey(m)) autoPrices.put(m, Math.max(0.1, autoPrice(m)));
        }

        loadBonuses("trim-bonuses.patterns", trimPatternBonus);
        loadBonuses("trim-bonuses.materials", trimMaterialBonus);

        getLogger().info("Loaded " + prices.size() + " set prices + " + autoPrices.size()
                + " auto-priced items, " + rules.size() + " special items.");
    }

    private void loadBonuses(String path, Map<String, Double> into) {
        ConfigurationSection s = getConfig().getConfigurationSection(path);
        if (s == null) return;
        for (String k : s.getKeys(false)) into.put(k.toLowerCase(Locale.ROOT), s.getDouble(k));
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        return rsp == null ? null : rsp.getProvider();
    }

    // ---------------------------------------------------------------- pricing

    /** Everything we need to know about an item, read once. */
    private record Info(Map<String, Integer> enchants, String trimPattern, String trimMaterial,
                        boolean named, boolean hasContents) {}

    private Info inspect(ItemStack item) {
        Map<String, Integer> enchants = new HashMap<>();
        String pattern = null;
        String trimMat = null;
        boolean named = false;
        boolean contents = false;

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            named = meta.hasDisplayName() || meta.hasLore();

            Map<Enchantment, Integer> source = meta instanceof EnchantmentStorageMeta storage
                    ? storage.getStoredEnchants()
                    : meta.getEnchants();
            for (Map.Entry<Enchantment, Integer> e : source.entrySet()) {
                enchants.put(e.getKey().getKey().getKey(), e.getValue());
            }

            if (meta instanceof ArmorMeta armor && armor.hasTrim()) {
                ArmorTrim trim = armor.getTrim();
                if (trim != null) {
                    pattern = trim.getPattern().getKey().getKey();
                    trimMat = trim.getMaterial().getKey().getKey();
                }
            }

            // Shulker boxes, chests, bundles etc. with stuff inside: refuse so nothing gets destroyed.
            if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
                contents = true;
            } else if (meta instanceof BlockStateMeta bsm && bsm.hasBlockState()
                    && bsm.getBlockState() instanceof Container container) {
                for (ItemStack inside : container.getSnapshotInventory().getContents()) {
                    if (inside != null && !inside.getType().isAir()) {
                        contents = true;
                        break;
                    }
                }
            }
        }
        return new Info(enchants, pattern, trimMat, named, contents);
    }

    private Double basePrice(Material type) {
        Double set = prices.get(type);
        return set != null ? set : autoPrices.get(type);
    }

    /** Total value of this stack, or 0 if it can't be sold. Every item in the game has a value. */
    private double valueOf(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        if (blacklist.contains(item.getType())) return 0;

        Info info = inspect(item);
        if (info.hasContents()) return 0;
        if (info.named() && !getConfig().getBoolean("allow-renamed-items", true)) return 0;

        Double base = basePrice(item.getType());
        if (base == null) return 0;
        double unit = base;

        // Enchantments add value per level.
        for (Map.Entry<String, Integer> e : info.enchants().entrySet()) {
            double perLevel = enchantValues.getOrDefault(e.getKey(),
                    getConfig().getDouble("default-enchant-value", 300));
            unit += perLevel * e.getValue();
        }

        // Special-items rules act as a premium floor (e.g. a full god set sells for more).
        for (Rule rule : rules) {
            if (rule.price() > unit
                    && rule.matches(item.getType(), info.enchants(), info.trimPattern(), info.trimMaterial())) {
                unit = rule.price();
            }
        }

        if (info.trimPattern() != null) unit += trimPatternBonus.getOrDefault(info.trimPattern(), 0.0);
        if (info.trimMaterial() != null) unit += trimMaterialBonus.getOrDefault(info.trimMaterial(), 0.0);

        if (getConfig().getBoolean("scale-by-durability", true)) {
            int max = item.getType().getMaxDurability();
            ItemMeta meta = item.getItemMeta();
            if (max > 0 && meta instanceof Damageable damageable) {
                unit *= Math.max(0.0, 1.0 - (double) damageable.getDamage() / max);
            }
        }
        return unit * item.getAmount();
    }

    /** Human-readable reason an item can't be sold. */
    private String reasonFor(ItemStack item) {
        if (blacklist.contains(item.getType())) return "this item is blacklisted";
        Info info = inspect(item);
        if (info.hasContents()) return "it has items inside - empty it first";
        if (info.named() && !getConfig().getBoolean("allow-renamed-items", true)) {
            return "it has a custom name or lore";
        }
        if (basePrice(item.getType()) == null) return "it can't be priced";
        return "its durability is gone";
    }

    // ------------------------------------------------------------ auto pricing

    private static final String[][] TOOL_PIECES = {
            {"_PICKAXE", "3"}, {"_AXE", "3"}, {"_SHOVEL", "1"}, {"_HOE", "2"}, {"_SWORD", "2"}, {"_SPEAR", "3"},
            {"_HELMET", "5"}, {"_CHESTPLATE", "8"}, {"_LEGGINGS", "7"}, {"_BOOTS", "4"}, {"_HORSE_ARMOR", "5"}
    };

    private static double tierUnit(String tier) {
        return switch (tier) {
            case "WOODEN" -> 0.8;
            case "STONE" -> 0.5;
            case "LEATHER" -> 2;
            case "COPPER" -> 8;
            case "CHAINMAIL" -> 12;
            case "IRON" -> 20;
            case "GOLDEN" -> 30;
            case "TURTLE" -> 25;
            case "DIAMOND" -> 200;
            case "NETHERITE" -> 3200;
            default -> -1;
        };
    }

    /** Fallback price so that every item in Minecraft can be sold. Hand-set prices in config override this. */
    private double autoPrice(Material m) {
        String n = m.name();

        // Tools, weapons and armor by tier: material cost of the recipe.
        for (String[] piece : TOOL_PIECES) {
            if (n.endsWith(piece[0]) && n.length() > piece[0].length()) {
                String tier = n.substring(0, n.length() - piece[0].length());
                double unit = tierUnit(tier);
                if (unit > 0) {
                    int count = Integer.parseInt(piece[1]);
                    return tier.equals("NETHERITE") ? 3000 + 200.0 * count : unit * count;
                }
            }
        }

        // Collectibles and templates
        if (n.endsWith("_SPAWN_EGG")) return 500;
        if (n.startsWith("MUSIC_DISC_")) return 300;
        if (n.endsWith("_BANNER_PATTERN")) return 100;
        if (n.endsWith("_POTTERY_SHERD")) return 80;
        if (n.endsWith("_SMITHING_TEMPLATE")) return n.startsWith("NETHERITE") ? 500 : 250;
        if (n.endsWith("_HEAD") || n.endsWith("_SKULL")) return 200;

        // Colored blocks
        if (n.endsWith("_SHULKER_BOX") || n.equals("SHULKER_BOX")) return 300;
        if (n.endsWith("_DYE")) return 2;
        if (n.endsWith("_WOOL")) return 3;
        if (n.endsWith("_CARPET")) return 1;
        if (n.endsWith("_BED")) return 12;
        if (n.endsWith("_BANNER")) return 12;
        if (n.endsWith("_CONCRETE_POWDER") || n.endsWith("_CONCRETE")) return 1.5;
        if (n.endsWith("_GLAZED_TERRACOTTA")) return 6;
        if (n.endsWith("_TERRACOTTA")) return 3;
        if (n.endsWith("_STAINED_GLASS_PANE")) return 0.5;
        if (n.endsWith("_STAINED_GLASS")) return 2.5;
        if (n.endsWith("_CANDLE") || n.equals("CANDLE")) return 4;

        // Vehicles, buckets, potions
        if (n.endsWith("_CHEST_BOAT") || n.endsWith("_CHEST_RAFT")) return 7;
        if (n.endsWith("_BOAT") || n.endsWith("_RAFT")) return 4;
        if (n.endsWith("_MINECART") || n.equals("MINECART")) return 90;
        if (n.endsWith("_BUCKET")) return 70;
        if (n.equals("BUCKET")) return 60;
        if (n.equals("POTION")) return 10;
        if (n.equals("SPLASH_POTION")) return 12;
        if (n.equals("LINGERING_POTION")) return 15;
        if (n.equals("TIPPED_ARROW")) return 5;
        if (n.equals("SPECTRAL_ARROW")) return 4;

        // Copper family (cut copper, stairs, lanterns, bulbs, golems...)
        if (n.contains("COPPER")) return 20;

        // Wood family
        if (n.endsWith("_LEAVES")) return 0.5;
        if (n.endsWith("_SAPLING") || n.endsWith("_PROPAGULE")) return 1;
        if (n.endsWith("_LOG") || n.endsWith("_WOOD") || n.endsWith("_STEM") || n.endsWith("_HYPHAE")) return 3;
        if (n.endsWith("_PLANKS")) return 0.8;
        if (n.endsWith("_FENCE_GATE")) return 3;
        if (n.endsWith("_FENCE")) return 1.5;
        if (n.endsWith("_HANGING_SIGN")) return 5;
        if (n.endsWith("_SIGN")) return 3;
        if (n.endsWith("_DOOR") || n.endsWith("_TRAPDOOR")) return 3;
        if (n.endsWith("_BUTTON")) return 0.5;
        if (n.endsWith("_PRESSURE_PLATE")) return 1.5;

        // Stone family
        if (n.endsWith("_SLAB")) return 0.6;
        if (n.endsWith("_STAIRS")) return 1;
        if (n.endsWith("_WALL")) return 1;
        if (n.endsWith("_ORE")) return 10;
        if (n.contains("BRICK")) return 3;
        if (n.startsWith("POLISHED_") || n.startsWith("SMOOTH_")) return 2;
        if (n.startsWith("CHISELED_") || n.startsWith("CUT_")) return 4;
        if (n.endsWith("_SANDSTONE")) return 4;

        // Plants
        if (n.endsWith("_SEEDS")) return 0.5;
        if (n.endsWith("_MUSHROOM") || n.endsWith("_FUNGUS")) return 3;
        if (n.endsWith("_ROOTS")) return 2;

        return getConfig().getDouble("default-price", 2);
    }

    private static Map<String, Double> defaultEnchantValues() {
        Map<String, Double> m = new HashMap<>();
        m.put("mending", 5000.0);       m.put("unbreaking", 800.0);      m.put("protection", 800.0);
        m.put("fire_protection", 300.0); m.put("blast_protection", 300.0); m.put("projectile_protection", 300.0);
        m.put("feather_falling", 400.0); m.put("thorns", 400.0);         m.put("respiration", 400.0);
        m.put("aqua_affinity", 1200.0);  m.put("depth_strider", 400.0);  m.put("frost_walker", 600.0);
        m.put("soul_speed", 800.0);      m.put("swift_sneak", 1500.0);   m.put("binding_curse", 10.0);
        m.put("vanishing_curse", 10.0);  m.put("sharpness", 600.0);      m.put("smite", 250.0);
        m.put("bane_of_arthropods", 250.0); m.put("knockback", 300.0);   m.put("fire_aspect", 400.0);
        m.put("looting", 800.0);         m.put("sweeping_edge", 400.0);  m.put("efficiency", 600.0);
        m.put("silk_touch", 2500.0);     m.put("fortune", 1200.0);       m.put("power", 600.0);
        m.put("punch", 300.0);           m.put("flame", 1000.0);         m.put("infinity", 3000.0);
        m.put("luck_of_the_sea", 300.0); m.put("lure", 300.0);           m.put("loyalty", 500.0);
        m.put("impaling", 300.0);        m.put("riptide", 500.0);        m.put("channeling", 1000.0);
        m.put("multishot", 1000.0);      m.put("piercing", 300.0);       m.put("quick_charge", 400.0);
        m.put("density", 800.0);         m.put("breach", 800.0);         m.put("wind_burst", 1200.0);
        return m;
    }

    // ---------------------------------------------------------------- command

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("sell.admin")) {
                sender.sendMessage(Component.text("You can't do that.", NamedTextColor.RED));
                return true;
            }
            reloadConfig();
            loadPrices();
            sender.sendMessage(color(PREFIX + "&aReloaded &f" + prices.size() + " &aprices and &f"
                    + rules.size() + " &aspecial items."));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /sell.");
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("worth")) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            if (hand.getType().isAir()) {
                player.sendMessage(color(PREFIX + "&cHold an item to check its value."));
            } else if (valueOf(hand) > 0) {
                player.sendMessage(color(PREFIX + "&fThis stack sells for &a" + money(valueOf(hand)) + "&f."));
            } else {
                player.sendMessage(color(PREFIX + "&cCan't be sold: " + reasonFor(hand) + "."));
            }
            return true;
        }

        if (economy() == null) {
            player.sendMessage(color(msg("no-economy", PREFIX + "&cNo economy found. Install Vault + an economy plugin.")));
            return true;
        }
        openMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        List<String> options = new ArrayList<>(List.of("worth"));
        if (sender.hasPermission("sell.admin")) options.add("reload");
        String typed = args[0].toLowerCase();
        return options.stream().filter(s -> s.startsWith(typed)).toList();
    }

    // ------------------------------------------------------------------- menu

    /** Open slots are columns 1-8 of rows 1-5 (slots 0-44 except the right-hand column). */
    private static boolean isSellSlot(int slot) {
        return slot >= 0 && slot < 45 && slot % 9 < 8;
    }

    private void openMenu(Player player) {
        SellHolder holder = new SellHolder();
        Inventory inv = Bukkit.createInventory(holder, MENU_SIZE, Component.text("Sell"));
        holder.inventory = inv;

        for (int i = 0; i < MENU_SIZE; i++) {
            if (!isSellSlot(i)) inv.setItem(i, darkPane());
        }
        refreshButton(inv);
        player.openInventory(inv);
    }

    private ItemStack darkPane() {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        meta.displayName(Component.empty());
        pane.setItemMeta(meta);
        return pane;
    }

    /** Recalculates the payout and rewrites the lime pane. */
    private void refreshButton(Inventory inv) {
        double total = 0;
        for (int i = 0; i < 45; i++) {
            if (isSellSlot(i)) total += valueOf(inv.getItem(i));
        }

        ItemStack button = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta meta = button.getItemMeta();
        meta.displayName(noItalic(Component.text("Sell Items?", NamedTextColor.GRAY, TextDecoration.BOLD)));
        meta.lore(List.of(noItalic(Component.text(money(total), NamedTextColor.GREEN, TextDecoration.BOLD))));
        button.setItemMeta(meta);
        inv.setItem(CONFIRM_SLOT, button);
    }

    // ----------------------------------------------------------------- events

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder holder)) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;

        int raw = event.getRawSlot();
        if (raw >= 0 && raw < MENU_SIZE) {
            if (raw == CONFIRM_SLOT) {
                event.setCancelled(true);
                sell(player, holder);
                return;
            }
            if (!isSellSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleRefresh(top);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof SellHolder)) return;

        for (int raw : event.getRawSlots()) {
            if (raw < MENU_SIZE && !isSellSlot(raw)) {
                event.setCancelled(true);
                return;
            }
        }
        scheduleRefresh(top);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        Inventory inv = event.getInventory();
        if (!(inv.getHolder() instanceof SellHolder)) return;
        if (!(event.getPlayer() instanceof Player player)) return;

        // Give back everything that wasn't sold.
        for (int i = 0; i < 45; i++) {
            if (!isSellSlot(i)) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            inv.setItem(i, null);
            for (ItemStack leftover : player.getInventory().addItem(item).values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), leftover);
            }
        }
    }

    private void scheduleRefresh(Inventory top) {
        // Run next tick so the click/drag has finished moving items.
        Bukkit.getScheduler().runTask(this, () -> refreshButton(top));
    }

    // ------------------------------------------------------------------- sell

    private void sell(Player player, SellHolder holder) {
        if (holder.sold) return;
        Inventory inv = holder.inventory;

        double total = 0;
        List<Integer> slots = new ArrayList<>();
        Set<String> problems = new LinkedHashSet<>();
        boolean anyItems = false;

        for (int i = 0; i < 45; i++) {
            if (!isSellSlot(i)) continue;
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            anyItems = true;

            double value = valueOf(item);
            if (value > 0) {
                total += value;
                slots.add(i);
            } else if (problems.size() < 3) {
                problems.add(prettyName(item.getType()) + "|" + reasonFor(item));
            }
        }

        if (slots.isEmpty()) {
            play(player, Sound.ENTITY_VILLAGER_NO, 1f);
            if (!anyItems) {
                player.sendMessage(color(msg("nothing", PREFIX + "&cThere is nothing to sell!")));
            } else {
                // Tell them exactly why, instead of a vague "nothing to sell".
                String template = msg("unsellable", PREFIX + "&cYou can't sell &f{item} &c({reason})");
                for (String problem : problems) {
                    String[] parts = problem.split("\\|", 2);
                    player.sendMessage(color(template.replace("{item}", parts[0]).replace("{reason}", parts[1])));
                }
            }
            return;
        }

        Economy eco = economy();
        if (eco == null) {
            player.sendMessage(color(msg("no-economy", PREFIX + "&cNo economy found.")));
            return;
        }

        // Pay first; only remove the items if the deposit worked.
        EconomyResponse response = eco.depositPlayer(player, total);
        if (!response.transactionSuccess()) {
            play(player, Sound.ENTITY_VILLAGER_NO, 1f);
            player.sendMessage(color(PREFIX + "&cSale failed: " + response.errorMessage));
            return;
        }

        holder.sold = true;
        for (int slot : slots) inv.setItem(slot, null);

        play(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.5f);
        player.sendMessage(color(msg("sold", PREFIX + "&aYou sold your items for &2{amount}&a!")
                .replace("{amount}", money(total))));

        // Close next tick (also returns any items that couldn't be sold).
        Bukkit.getScheduler().runTask(this, () -> player.closeInventory());
    }

    // ---------------------------------------------------------------- helpers

    private String msg(String key, String fallback) {
        return getConfig().getString("messages." + key, fallback);
    }

    private String money(double amount) {
        return getConfig().getString("currency-symbol", "$") + compact(amount);
    }

    private static String prettyName(Material material) {
        StringBuilder sb = new StringBuilder();
        for (String word : material.name().toLowerCase(Locale.ROOT).split("_")) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return sb.toString();
    }

    /**
     * 450 -> "450", 1,150 -> "1.2k", 100,430 -> "100.4k" (nearest hundred),
     * 1,140,000 -> "1.1m" (nearest 100k), then b and t the same way.
     */
    static String compact(double amount) {
        if (amount < 1000) {
            double r = Math.round(amount * 100.0) / 100.0;
            return r == Math.rint(r) ? String.valueOf((long) r) : String.format(Locale.US, "%.2f", r);
        }

        String[] suffix = {"k", "m", "b", "t"};
        double[] unit = {1e3, 1e6, 1e9, 1e12};

        int i = 0;
        for (int j = unit.length - 1; j >= 0; j--) {
            if (amount >= unit[j]) {
                i = j;
                break;
            }
        }

        double rounded = Math.round(amount / unit[i] * 10.0) / 10.0;
        if (rounded >= 1000 && i < unit.length - 1) { // e.g. 999,960 -> 1m, not 1000k
            i++;
            rounded = Math.round(amount / unit[i] * 10.0) / 10.0;
        }

        String number = rounded == Math.rint(rounded)
                ? String.valueOf((long) rounded)
                : String.format(Locale.US, "%.1f", rounded);
        return number + suffix[i];
    }

    private static Component noItalic(Component c) {
        return c.decoration(TextDecoration.ITALIC, false);
    }

    private static Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text == null ? "" : text);
    }

    /** Respects the /settings sound toggle (settings:sounds_enabled = 0 mutes). */
    private void play(Player player, Sound sound, float pitch) {
        Byte enabled = player.getPersistentDataContainer().get(SOUNDS_KEY, PersistentDataType.BYTE);
        if (enabled != null && enabled == 0) return;
        player.playSound(player, sound, 1f, pitch);
    }
}
