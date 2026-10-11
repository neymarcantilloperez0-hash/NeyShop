package com.neyshop;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public final class NeyShop extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private Economy economy;
    private File shopsFile;
    private YamlConfiguration shops;
    private NamespacedKey guiKey;
    private final Map<UUID, PendingPurchase> pending = new HashMap<>();
    private final Map<UUID, String> openedShop = new HashMap<>();
    private final Map<UUID, String> editorShop = new HashMap<>();

    private static final String PREFIX = "§6§lNEYSHOP §8» §r";
    private static final String SHOP_TITLE_PREFIX = "§8NeyShop: ";
    private static final String CONFIRM_TITLE = "§8Confirmar compra";
    private static final String EDITOR_TITLE = "§8Editor: ";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        guiKey = new NamespacedKey(this, "neyshop_action");
        shopsFile = new File(getDataFolder(), "shops.yml");
        if (!shopsFile.exists()) {
            getDataFolder().mkdirs();
            try { shopsFile.createNewFile(); } catch (IOException e) { getLogger().severe("No se pudo crear shops.yml: " + e.getMessage()); }
        }
        shops = YamlConfiguration.loadConfiguration(shopsFile);

        RegisteredServiceProvider<Economy> provider = getServer().getServicesManager().getRegistration(Economy.class);
        if (provider != null) economy = provider.getProvider();

        Objects.requireNonNull(getCommand("neyshop")).setExecutor(this);
        Objects.requireNonNull(getCommand("neyshop")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);

        if (economy == null) getLogger().warning("Vault/economy no detectada. Las tiendas cargan, pero las compras y ventas necesitan una economía compatible.");
        getLogger().info("NeyShop habilitado. Tiendas: " + getShopNames().size());
    }

    @Override
    public void onDisable() {
        saveShops();
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private void msg(CommandSender sender, String text) {
        sender.sendMessage(PREFIX + color(text));
    }

    private boolean isAdmin(CommandSender sender) {
        return sender.hasPermission("neyshop.admin");
    }

    private boolean requireAdmin(CommandSender sender) {
        if (isAdmin(sender)) return true;
        msg(sender, "&cNo tienes permiso. Necesitas neyshop.admin.");
        return false;
    }

    private Set<String> getShopNames() {
        ConfigurationSection section = shops.getConfigurationSection("shops");
        if (section == null) return new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        return section.getKeys(false).stream().collect(Collectors.toCollection(() -> new TreeSet<>(String.CASE_INSENSITIVE_ORDER)));
    }

    private String path(String shop) {
        return "shops." + shop.toLowerCase(Locale.ROOT);
    }

    private boolean shopExists(String shop) {
        return shops.isConfigurationSection(path(shop));
    }

    private void saveShops() {
        try { shops.save(shopsFile); }
        catch (IOException e) { getLogger().severe("No se pudo guardar shops.yml: " + e.getMessage()); }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§8§m--------------------------------");
        sender.sendMessage("§6§lNEYSHOP §7- §fAYUDA");
        sender.sendMessage("§e/neyshop help §7- Mostrar esta guía");
        sender.sendMessage("§e/neyshop create <nombre> §7- Crear tienda");
        sender.sendMessage("§e/neyshop list §7- Listar tiendas");
        sender.sendMessage("§e/neyshop open <nombre> §7- Abrir tienda");
        sender.sendMessage("§e/neyshop delete <nombre> §7- Eliminar tienda");
        sender.sendMessage("§e/neyshop settitle <tienda> <título> §7- Cambiar título");
        sender.sendMessage("§e/neyshop setrows <tienda> <1-6> §7- Cambiar filas");
        sender.sendMessage("§e/neyshop additem <tienda> <precio> [categoría] §7- Añadir objeto en mano");
        sender.sendMessage("§e/neyshop removeitem <tienda> <slot> §7- Quitar objeto por slot");
        sender.sendMessage("§e/neyshop category <tienda> <categoría> §7- Asignar categoría");
        sender.sendMessage("§e/neyshop sell <precio-por-objeto> §7- Vender objeto en mano");
        sender.sendMessage("§e/neyshop reload §7- Recargar datos");
        sender.sendMessage("§7Consejo: para agregar un objeto, sostenlo en la mano.");
        sender.sendMessage("§8§m--------------------------------");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("?")) {
            sendHelp(sender);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create": {
                if (!requireAdmin(sender)) return true;
                if (args.length != 2) { msg(sender, "&cUso: /neyshop create <nombre>"); return true; }
                String name = safeName(args[1]);
                if (name == null) { msg(sender, "&cNombre inválido. Usa letras, números, _ o - (máximo 32)."); return true; }
                if (shopExists(name)) { msg(sender, "&cYa existe una tienda con ese nombre."); return true; }
                String p = path(name);
                shops.set(p + ".title", "&6" + name);
                shops.set(p + ".rows", 3);
                shops.set(p + ".category", "General");
                shops.set(p + ".items", new ArrayList<>());
                saveShops();
                msg(sender, "&aTienda &e" + name + " &acreada. Usa /neyshop additem " + name + " <precio> sosteniendo un objeto.");
                return true;
            }
            case "list": {
                if (getShopNames().isEmpty()) { msg(sender, "&7No hay tiendas. Crea una con /neyshop create <nombre>."); return true; }
                msg(sender, "&fTiendas: &e" + String.join(", ", getShopNames()));
                return true;
            }
            case "open": {
                if (!(sender instanceof Player)) { msg(sender, "&cSolo un jugador puede abrir una tienda."); return true; }
                if (args.length != 2) { msg(sender, "&cUso: /neyshop open <nombre>"); return true; }
                openShop((Player) sender, args[1]);
                return true;
            }
            case "delete": {
                if (!requireAdmin(sender)) return true;
                if (args.length != 2) { msg(sender, "&cUso: /neyshop delete <nombre>"); return true; }
                if (!shopExists(args[1])) { msg(sender, "&cEsa tienda no existe."); return true; }
                shops.set(path(args[1]), null);
                saveShops();
                msg(sender, "&aTienda eliminada.");
                return true;
            }
            case "settitle": {
                if (!requireAdmin(sender)) return true;
                if (args.length < 3) { msg(sender, "&cUso: /neyshop settitle <tienda> <título>"); return true; }
                if (!shopExists(args[1])) { msg(sender, "&cEsa tienda no existe."); return true; }
                String title = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                shops.set(path(args[1]) + ".title", title);
                saveShops();
                msg(sender, "&aTítulo actualizado.");
                return true;
            }
            case "setrows": {
                if (!requireAdmin(sender)) return true;
                if (args.length != 3) { msg(sender, "&cUso: /neyshop setrows <tienda> <1-6>"); return true; }
                if (!shopExists(args[1])) { msg(sender, "&cEsa tienda no existe."); return true; }
                int rows;
                try { rows = Integer.parseInt(args[2]); } catch (NumberFormatException e) { msg(sender, "&cLas filas deben ser un número del 1 al 6."); return true; }
                if (rows < 1 || rows > 6) { msg(sender, "&cLas filas deben estar entre 1 y 6."); return true; }
                shops.set(path(args[1]) + ".rows", rows);
                saveShops();
                msg(sender, "&aTamaño actualizado.");
                return true;
            }
            case "category": {
                if (!requireAdmin(sender)) return true;
                if (args.length < 3) { msg(sender, "&cUso: /neyshop category <tienda> <categoría>"); return true; }
                if (!shopExists(args[1])) { msg(sender, "&cEsa tienda no existe."); return true; }
                shops.set(path(args[1]) + ".category", String.join(" ", Arrays.copyOfRange(args, 2, args.length)));
                saveShops();
                msg(sender, "&aCategoría actualizada.");
                return true;
            }
            case "additem": {
                if (!requireAdmin(sender)) return true;
                if (!(sender instanceof Player)) { msg(sender, "&cEste comando solo lo puede usar un jugador sosteniendo un objeto."); return true; }
                if (args.length < 3 || args.length > 4) { msg(sender, "&cUso: /neyshop additem <tienda> <precio> [categoría]"); return true; }
                if (!shopExists(args[1])) { msg(sender, "&cEsa tienda no existe."); return true; }
                double price = parsePrice(args[2]);
                if (!Double.isFinite(price) || price < 0) { msg(sender, "&cEl precio debe ser un número válido mayor o igual a cero."); return true; }
                Player player = (Player) sender;
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand == null || hand.getType() == Material.AIR) { msg(sender, "&cSostén el objeto que quieres añadir en tu mano principal."); return true; }
                String p = path(args[1]) + ".items";
                List<Map<?, ?>> list = shops.getMapList(p);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("item", hand.clone());
                entry.put("price", price);
                entry.put("category", args.length == 4 ? args[3] : shops.getString(path(args[1]) + ".category", "General"));
                entry.put("amount", hand.getAmount());
                list.add(entry);
                shops.set(p, list);
                saveShops();
                msg(sender, "&aObjeto añadido a &e" + args[1] + "&a por &e" + price + "&a.");
                return true;
            }
            case "removeitem": {
                if (!requireAdmin(sender)) return true;
                if (args.length != 3) { msg(sender, "&cUso: /neyshop removeitem <tienda> <slot>"); return true; }
                if (!shopExists(args[1])) { msg(sender, "&cEsa tienda no existe."); return true; }
                int slot;
                try { slot = Integer.parseInt(args[2]); } catch (NumberFormatException e) { msg(sender, "&cEl slot debe ser un número."); return true; }
                List<Map<?, ?>> items = shops.getMapList(path(args[1]) + ".items");
                if (slot < 0 || slot >= items.size()) { msg(sender, "&cSlot inválido. Los índices comienzan en 0. Items: " + items.size()); return true; }
                items.remove(slot);
                shops.set(path(args[1]) + ".items", items);
                saveShops();
                msg(sender, "&aObjeto quitado. Los slots son índices de la lista y empiezan en 0.");
                return true;
            }
            case "sell": {
                if (!(sender instanceof Player)) { msg(sender, "&cSolo un jugador puede vender objetos."); return true; }
                if (!sender.hasPermission("neyshop.sell")) { msg(sender, "&cNo tienes permiso para vender objetos."); return true; }
                if (args.length != 2) { msg(sender, "&cUso: /neyshop sell <precio-por-objeto>"); return true; }
                if (economy == null) { msg(sender, "&cNo hay economía disponible. Instala Vault y un plugin de economía."); return true; }
                double price = parsePrice(args[1]);
                if (!Double.isFinite(price) || price <= 0) { msg(sender, "&cEl precio por objeto debe ser mayor que cero."); return true; }
                Player player = (Player) sender;
                ItemStack hand = player.getInventory().getItemInMainHand();
                if (hand.getType() == Material.AIR || hand.getAmount() <= 0) { msg(sender, "&cSostén el objeto que quieres vender."); return true; }
                int amount = hand.getAmount();
                double total = price * amount;
                if (!Double.isFinite(total) || total <= 0) { msg(sender, "&cTotal inválido."); return true; }
                player.getInventory().setItemInMainHand(null);
                economy.depositPlayer(player, total);
                msg(sender, "&aVendiste &e" + amount + "x " + hand.getType().name() + " &apor &e" + String.format(Locale.US, "%.2f", total) + "&a.");
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
                return true;
            }
            case "reload": {
                if (!requireAdmin(sender)) return true;
                shops = YamlConfiguration.loadConfiguration(shopsFile);
                saveShops();
                msg(sender, "&aDatos de tiendas recargados.");
                return true;
            }
            default:
                msg(sender, "&cComando desconocido: &f/" + label + " " + args[0]);
                msg(sender, "&7Usa &e/neyshop help &7para ver todos los comandos.");
                return true;
        }
    }

    private String safeName(String name) {
        if (name == null || name.length() > 32 || !name.matches("[A-Za-z0-9_-]+")) return null;
        return name.toLowerCase(Locale.ROOT);
    }

    private double parsePrice(String input) {
        try { return Double.parseDouble(input.replace(",", ".")); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    private void openShop(Player player, String name) {
        if (!shopExists(name)) { msg(player, "&cEsa tienda no existe. Usa /neyshop list."); return; }
        String p = path(name);
        int rows = Math.max(1, Math.min(6, shops.getInt(p + ".rows", 3)));
        String title = color(SHOP_TITLE_PREFIX + shops.getString(p + ".title", name));
        Inventory inv = Bukkit.createInventory(null, rows * 9, title);
        List<Map<?, ?>> items = shops.getMapList(p + ".items");
        int slot = 0;
        for (int i = 0; i < items.size() && slot < inv.getSize(); i++) {
            Map<?, ?> entry = items.get(i);
            Object raw = entry.get("item");
            if (!(raw instanceof ItemStack)) continue;
            ItemStack display = ((ItemStack) raw).clone();
            double price = number(entry.get("price"), -1);
            if (price < 0 || !Double.isFinite(price)) continue;
            Object categoryValue = entry.containsKey("category") ? entry.get("category") : "General";
            String category = String.valueOf(categoryValue);
            ItemMeta meta = display.getItemMeta();
            if (meta != null) {
                List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
                lore.add(" ");
                lore.add(color("&7Precio: &a" + String.format(Locale.US, "%.2f", price)));
                lore.add(color("&7Categoría: &e" + category));
                lore.add(color("&ePulsa para comprar"));
                meta.setLore(lore);
                display.setItemMeta(meta);
            }
            display = tag(display, "buy:" + name.toLowerCase(Locale.ROOT) + ":" + i);
            inv.setItem(slot++, display);
        }
        openedShop.put(player.getUniqueId(), name.toLowerCase(Locale.ROOT));
        player.openInventory(inv);
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(String.valueOf(value)); } catch (Exception e) { return fallback; }
    }

    private ItemStack tag(ItemStack item, String value) {
        ItemStack result = item.clone();
        ItemMeta meta = result.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(guiKey, PersistentDataType.STRING, value);
            result.setItemMeta(meta);
        }
        return result;
    }

    private String getTag(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(guiKey, PersistentDataType.STRING);
    }

    private void openConfirm(Player player, String shop, int index, ItemStack item, double price) {
        Inventory inv = Bukkit.createInventory(null, 27, CONFIRM_TITLE);
        ItemStack yes = new ItemStack(Material.LIME_WOOL);
        ItemMeta yesMeta = yes.getItemMeta();
        yesMeta.setDisplayName("§a§lCONFIRMAR COMPRA");
        yesMeta.setLore(Arrays.asList("§7Precio: §a" + String.format(Locale.US, "%.2f", price), "§ePulsa para confirmar"));
        yes.setItemMeta(yesMeta);
        inv.setItem(11, tag(yes, "confirm:yes"));
        ItemStack no = new ItemStack(Material.RED_WOOL);
        ItemMeta noMeta = no.getItemMeta();
        noMeta.setDisplayName("§c§lCANCELAR");
        noMeta.setLore(Collections.singletonList("§7No se realizará ningún cobro."));
        no.setItemMeta(noMeta);
        inv.setItem(15, tag(no, "confirm:no"));
        player.openInventory(inv);
        pending.put(player.getUniqueId(), new PendingPurchase(shop, index, item.clone(), price));
    }

    private void finishPurchase(Player player) {
        PendingPurchase purchase = pending.remove(player.getUniqueId());
        if (purchase == null) { msg(player, "&cNo hay compra pendiente."); return; }
        if (economy == null) { msg(player, "&cNo hay economía disponible. Instala Vault y un plugin de economía."); return; }
        if (!shopExists(purchase.shop)) { msg(player, "&cLa tienda ya no existe."); return; }
        List<Map<?, ?>> items = shops.getMapList(path(purchase.shop) + ".items");
        if (purchase.index < 0 || purchase.index >= items.size()) { msg(player, "&cEl objeto ya no está disponible."); return; }
        Map<?, ?> entry = items.get(purchase.index);
        double currentPrice = number(entry.get("price"), Double.NaN);
        Object raw = entry.get("item");
        if (!(raw instanceof ItemStack) || !Double.isFinite(currentPrice) || currentPrice < 0) { msg(player, "&cDatos del objeto inválidos."); return; }
        ItemStack toGive = ((ItemStack) raw).clone();
        if (!canFit(player, toGive)) { msg(player, "&cNo tienes espacio suficiente en el inventario."); return; }
        if (economy.getBalance(player) + 1e-9 < currentPrice) { msg(player, "&cNo tienes suficiente dinero."); return; }
        if (currentPrice > 0) {
            v
