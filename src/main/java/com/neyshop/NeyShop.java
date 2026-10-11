package com.neyshop;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class NeyShop extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Identifica los inventarios de tienda. */
    public static class ShopHolder implements InventoryHolder {
        final String id;
        Inventory inv;

        ShopHolder(String id) {
            this.id = id;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    private static final List<String> SUBS = Arrays.asList(
            "create", "npc", "link", "remove", "additem", "removeitem",
            "delete", "settitle", "setrows", "open", "list", "reload");
    private static final List<String> SHOP_ARG = Arrays.asList(
            "npc", "link", "additem", "removeitem", "delete", "settitle", "setrows", "open");

    private Economy economy;
    private NamespacedKey shopKey;
    private final Map<UUID, String> pending = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        shopKey = new NamespacedKey(this, "shop");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("neyshop") != null) {
            getCommand("neyshop").setExecutor(this);
            getCommand("neyshop").setTabCompleter(this);
        }
        getLogger().info("NeyShop activado.");
    }

    // ---------- Utilidades ----------
    private Economy eco() {
        if (economy == null) {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) economy = rsp.getProvider();
        }
        return economy;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s == null ? "" : s);
    }

    private String msg(String key, String... kv) {
        String s = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1]);
        return color(getConfig().getString("messages.prefix", "") + s);
    }

    private void send(Player p, String key, String... kv) {
        p.sendMessage(msg(key, kv));
    }

    private String fmt(double v) {
        DecimalFormat df = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.US));
        return getConfig().getString("currency-symbol", "$") + df.format(v);
    }

    private Set<String> shopIds() {
        ConfigurationSection sec = getConfig().getConfigurationSection("shops");
        if (sec == null) return Collections.emptySet();
        return sec.getKeys(false);
    }

    private boolean shopExists(String id) {
        return getConfig().getConfigurationSection("shops." + id) != null;
    }

    private boolean validId(String id) {
        return id.matches("[a-z0-9_-]{1,32}");
    }

    private int rowsOf(String id) {
        return Math.max(1, Math.min(6, getConfig().getInt("shops." + id + ".rows", 3)));
    }

    private void createShop(String id) {
        String base = "shops." + id;
        getConfig().set(base + ".title", "&6&l" + id);
        getConfig().set(base + ".npc-name", "&6&l" + id);
        getConfig().set(base + ".rows", 3);
        getConfig().set(base + ".filler", "GRAY_STAINED_GLASS_PANE");
        saveConfig();
    }

    private int freeSlot(String id) {
        int rows = rowsOf(id);
        for (int i = 0; i < rows * 9; i++) {
            if (!getConfig().contains("shops." + id + ".items." + i)) return i;
        }
        return -1;
    }

    private double parseAmount(String raw) {
        try {
            if (raw.contains("-")) {
                String[] p = raw.split("-");
                double min = Double.parseDouble(p[0].trim());
                double max = Double.parseDouble(p[1].trim());
                if (max < min) {
                    double t = min;
                    min = max;
                    max = t;
                }
                return Math.floor(min + Math.random() * (max - min + 1));
            }
            return Double.parseDouble(raw.trim());
        } catch (Exception ex) {
            return 0;
        }
    }

    // ---------- Minas: dinero digital ----------
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!getConfig().getBoolean("mining.enabled", true)) return;
        Player p = e.getPlayer();
        if (p.getGameMode() == GameMode.CREATIVE) return;
        String raw = getConfig().getString("mining.rewards." + e.getBlock().getType().name());
        if (raw == null) return;
        double amount = parseAmount(raw);
        if (amount <= 0) return;
        Economy bank = eco();
        if (bank == null) return;

        bank.depositPlayer(p, amount);
        if (getConfig().getBoolean("mining.cancel-drops", true)) e.setDropItems(false);
        if (getConfig().getBoolean("mining.show-actionbar", true)) {
            String text = color(getConfig().getString("messages.mined", "&a+{amount}").replace("{amount}", fmt(amount)));
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text));
        }
    }

    // ---------- Menú de tienda ----------
    private void addPriceLore(ItemStack it, double price) {
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return;
        List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
        lore.add("");
        lore.add(color(getConfig().getString("messages.lore-price", "&7Precio: &a{price}")
                .replace("{price}", fmt(price))));
        lore.add(color(getConfig().getString("messages.lore-click", "&eClic para comprar")));
        meta.setLore(lore);
        it.setItemMeta(meta);
    }

    private ItemStack buildItem(ConfigurationSection s, boolean forDisplay) {
        if (s == null) return null;

        // Ítem guardado desde el juego con /neyshop additem
        if (s.isItemStack("item")) {
            ItemStack saved = s.getItemStack("item");
            if (saved == null || saved.getType().isAir()) return null;
            ItemStack it = saved.clone();
            if (forDisplay && s.contains("price")) addPriceLore(it, s.getDouble("price"));
            return it;
        }

        // Ítem definido a mano en el config
        Material m = Material.matchMaterial(s.getString("material", "STONE"));
        if (m == null || m.isAir()) return null;
        int amount = Math.max(1, Math.min(64, s.getInt("amount", 1)));
        ItemStack it = new ItemStack(m, amount);
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return it;

        if (s.contains("name")) meta.setDisplayName(color(s.getString("name")));
        List<String> lore = new ArrayList<>();
        for (String l : s.getStringList("lore")) lore.add(color(l));
        if (!lore.isEmpty()) meta.setLore(lore);

        for (String en : s.getStringList("enchants")) {
            String[] parts = en.split(":");
            Enchantment ench = Enchantment.getByKey(NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT)));
            if (ench == null) continue;
            int lvl = 1;
            if (parts.length > 1) {
                try {
                    lvl = Integer.parseInt(parts[1].trim());
                } catch (NumberFormatException ignored) {
                }
            }
            meta.addEnchant(ench, lvl, true);
        }
        it.setItemMeta(meta);
        if (forDisplay && s.contains("price")) addPriceLore(it, s.getDouble("price"));
        return it;
    }

    private void openShop(Player p, String id) {
        ConfigurationSection sec = getConfig().getConfigurationSection("shops." + id);
        if (sec == null) {
            send(p, "shop-not-found", "shops", String.join(", ", shopIds()));
            return;
        }
        int rows = rowsOf(id);
        ShopHolder holder = new ShopHolder(id);
        Inventory inv = Bukkit.createInventory(holder, rows * 9, color(sec.getString("title", "Tienda")));
        holder.inv = inv;

        ConfigurationSection items = sec.getConfigurationSection("items");
        if (items != null) {
            for (String slotKey : items.getKeys(false)) {
                int slot;
                try {
                    slot = Integer.parseInt(slotKey);
                } catch (NumberFormatException ex) {
                    continue;
                }
                if (slot < 0 || slot >= rows * 9) continue;
                ItemStack display = buildItem(items.getConfigurationSection(slotKey), true);
                if (display != null) inv.setItem(slot, display);
            }
        }

        String filler = sec.getString("filler");
        if (filler != null) {
            Material fm = Material.matchMaterial(filler);
            if (fm != null && !fm.isAir()) {
                ItemStack f = new ItemStack(fm);
                ItemMeta meta = f.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName(" ");
                    f.setItemMeta(meta);
                }
                for (int i = 0; i < inv.getSize(); i++) {
                    if (inv.getItem(i) == null) inv.setItem(i, f);
                }
            }
        }
        p.openInventory(inv);
    }

    private boolean canFit(PlayerInventory inv, ItemStack item) {
        int space = 0;
        for (ItemStack st : inv.getStorageContents()) {
            if (st == null || st.getType().isAir()) space += item.getMaxStackSize();
            else if (st.isSimilar(item)) space += Math.max(0, st.getMaxStackSize() - st.getAmount());
            if (space >= item.getAmount()) return true;
        }
        return false;
    }

    private void buy(Player p, ConfigurationSection s) {
        Economy bank = eco();
        if (bank == null) {
            send(p, "no-economy");
            return;
        }
        double price = s.getDouble("price", 0);
        if (price < 0) return;
        ItemStack item = s.getBoolean("give-item", true) ? buildItem(s, false) : null;

        if (!bank.has(p, price)) {
            send(p, "not-enough", "price", fmt(price), "balance", fmt(bank.getBalance(p)));
            return;
        }
        if (item != null && !canFit(p.getInventory(), item)) {
            send(p, "inventory-full");
            return;
        }
        EconomyResponse r = bank.withdrawPlayer(p, price);
        if (!r.transactionSuccess()) {
            send(p, "not-enough", "price", fmt(price), "balance", fmt(bank.getBalance(p)));
            return;
        }
        if (item != null) p.getInventory().addItem(item);
        for (String cmd : s.getStringList("commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
        }

        String name;
        if (item != null) {
            ItemMeta im = item.getItemMeta();
            String base = (im != null && im.hasDisplayName())
                    ? im.getDisplayName()
                    : item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
            name = item.getAmount() + "x " + base;
        } else {
            name = color(s.getString("name", "ítem"));
        }
        send(p, "bought", "item", name, "price", fmt(price));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof ShopHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != top) return;
        ShopHolder h = (ShopHolder) top.getHolder();
        ConfigurationSection sec = getConfig().getConfigurationSection("shops." + h.id + ".items." + e.getSlot());
        if (sec == null || !sec.contains("price")) return;
        buy((Player) e.getWhoClicked(), sec);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }

    // ---------- Aldeanos ----------
    private void setupNpc(LivingEntity ent, String id) {
        ent.getPersistentDataContainer().set(shopKey, PersistentDataType.STRING, id);
        ent.setAI(false);
        ent.setInvulnerable(true);
        ent.setSilent(true);
        ent.setCollidable(false);
        ent.setRemoveWhenFarAway(false);
        String name = getConfig().getString("shops." + id + ".npc-name");
        if (name != null) {
            ent.setCustomName(color(name));
            ent.setCustomNameVisible(true);
        }
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Entity ent = e.getRightClicked();
        if (ent instanceof Player) return;
        Player p = e.getPlayer();

        String mode = pending.remove(p.getUniqueId());
        if (mode != null) {
            e.setCancelled(true);
            if (!p.hasPermission("neyshop.admin")) return;
            if (mode.equals("remove")) {
                if (ent.getPersistentDataContainer().has(shopKey, PersistentDataType.STRING)) {
                    ent.remove();
                    send(p, "npc-removed");
                } else {
                    send(p, "npc-not-shop");
                }
                return;
            }
            if (mode.startsWith("link:") && ent instanceof LivingEntity) {
                String id = mode.substring(5);
                setupNpc((LivingEntity) ent, id);
                send(p, "npc-linked", "shop", id);
            }
            return;
        }

        String id = ent.getPersistentDataContainer().get(shopKey, PersistentDataType.STRING);
        if (id == null) return;
        e.setCancelled(true);
        openShop(p, id);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity().getPersistentDataContainer().has(shopKey, PersistentDataType.STRING)) {
            e.setCancelled(true);
        }
    }

    // ---------- Comandos ----------
    @Override
    public boolean onCommand(CommandSender sender, Command c, String label, String[] a) {
        if (!sender.hasPermission("neyshop.admin")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        if (a.length == 0) {
            sender.sendMessage(msg("usage"));
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload":
                reloadConfig();
                sender.sendMessage(msg("reloaded"));
                return true;

            case "list":
                sender.sendMessage(msg("list", "shops", String.join(", ", shopIds())));
                return true;

            case "open": {
                if (a.length < 2) { sender.sendMessage(msg("usage")); return true; }
                Player target = a.length >= 3 ? Bukkit.getPlayerExact(a[2])
                        : (sender instanceof Player ? (Player) sender : null);
                if (target == null) { sender.sendMessage(msg("player-not-found")); return true; }
                openShop(target, a[1].toLowerCase(Locale.ROOT));
                return true;
            }

            case "create": {
                if (a.length < 2) { sender.sendMessage(msg("usage")); return true; }
                String id = a[1].toLowerCase(Locale.ROOT);
                if (!validId(id)) { sender.sendMessage(msg("bad-id")); return true; }
                if (shopExists(id)) { sender.sendMessage(msg("shop-exists", "shop", id)); return true; }
                createShop(id);
                sender.sendMessage(msg("shop-created", "shop", id));
                return true;
            }

            case "delete": {
                if (a.length < 2) { sender.sendMessage(msg("usage")); return true; }
                String id = a[1].toLowerCase(Locale.ROOT);
                if (!shopExists(id)) { sender.sendMessage(msg("shop-not-found", "shops", String.join(", ", shopIds()))); return true; }
                getConfig().set("shops." + id, null);
                saveConfig();
                sender.sendMessage(msg("shop-deleted", "shop", id));
                return true;
            }

            case "settitle": {
                if (a.length < 3) { sender.sendMessage(msg("usage")); return true; }
                String id = a[1].toLowerCase(Locale.ROOT);
                if (!shopExists(id)) { sender.sendMessage(msg("shop-not-found", "shops", String.join(", ", shopIds()))); return true; }
                String title = String.join(" ", Arrays.copyOfRange(a, 2, a.length));
                getConfig().set("shops." + id + ".title", title);
                saveConfig();
                sender.sendMessage(msg("title-set", "shop", id));
                return true;
            }

            case "setrows": {
                if (a.length < 3) { sender.sendMessage(msg("usage")); return true; }
                String id = a[1].toLowerCase(Locale.ROOT);
                if (!shopExists(id)) { sender.sendMessage(msg("shop-not-found", "shops", String.join(", ", shopIds()))); return true; }
                int rows;
                try {
                    rows = Integer.parseInt(a[2]);
                } catch (NumberFormatException ex) {
                    sender.sendMessage(msg("bad-number"));
                    return true;
                }
                if (rows < 1 || rows > 6) { sender.sendMessage(msg("bad-number")); return true; }
                getConfig().set("shops." + id + ".rows", rows);
                saveConfig();
                sender.sendMessage(msg("rows-set", "shop", id, "rows", String.valueOf(rows)));
                return true;
            }

            case "additem": {
                if (!(sender instanceof Player)) { sender.sendMessage(msg("player-only")); return true; }
                Player p = (Player) sender;
           
