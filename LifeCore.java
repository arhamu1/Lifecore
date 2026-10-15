package com.lifecore;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.*;

/**
 * LifeCore - all-in-one Lifesteal SMP plugin:
 * Economy, Shop, Sell, Auction House, RTP, TPA, Homes, Spawn, Warps, Lifesteal hearts.
 */
public class LifeCore extends JavaPlugin implements Listener, CommandExecutor {

    private YamlConfiguration data;
    private File dataFile;
    private boolean dirty = false;
    private NamespacedKey heartKey;
    private final Map<UUID, UUID> tpaReq = new HashMap<>();
    private final Map<UUID, Long> tpaTime = new HashMap<>();
    private final Map<UUID, Long> rtpCooldown = new HashMap<>();
    private final Random rnd = new Random();
    private static final Set<String> ADMIN_CMDS = Set.of("eco", "revive", "setspawn", "setwarp", "delwarp");

    // ---------- GUI holder ----------
    static class Gui implements InventoryHolder {
        final String type;
        Inventory inv;
        final List<String> ids = new ArrayList<>();
        Gui(String type) { this.type = type; }
        @Override public Inventory getInventory() { return inv; }
    }

    // ---------- lifecycle ----------
    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        heartKey = new NamespacedKey(this, "heart");
        Bukkit.getPluginManager().registerEvents(this, this);
        for (String name : getDescription().getCommands().keySet()) {
            PluginCommand pc = getCommand(name);
            if (pc != null) pc.setExecutor(this);
        }
        Bukkit.getScheduler().runTaskTimer(this, this::saveData, 600L, 600L);
        for (Player p : Bukkit.getOnlinePlayers()) applyHealth(p, false);
        getLogger().info("LifeCore enabled.");
    }

    @Override
    public void onDisable() {
        dirty = true;
        saveData();
    }

    private void saveData() {
        if (!dirty) return;
        try {
            data.save(dataFile);
            dirty = false;
        } catch (Exception ex) {
            getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    // ---------- helpers ----------
    private String c(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    private void msg(CommandSender s, String m) { s.sendMessage(c("&8[&cLifeCore&8] &7" + m)); }

    private String money(double v) { return String.format("$%,.2f", v); }

    private double bal(UUID u) {
        return data.getDouble("bal." + u, getConfig().getDouble("start-balance", 100));
    }

    private void setBal(UUID u, double v) {
        data.set("bal." + u, Math.max(0, v));
        dirty = true;
    }

    private Double parseAmount(String s) {
        try {
            double d = Double.parseDouble(s);
            if (d <= 0 || Double.isNaN(d) || Double.isInfinite(d)) return null;
            return d;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private ItemStack withLore(ItemStack base, String... lines) {
        ItemStack it = base.clone();
        ItemMeta m = it.getItemMeta();
        List<String> lore = m.hasLore() && m.getLore() != null ? new ArrayList<>(m.getLore()) : new ArrayList<>();
        for (String l : lines) lore.add(c(l));
        m.setLore(lore);
        it.setItemMeta(m);
        return it;
    }

    private void give(Player p, ItemStack it) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(it);
        for (ItemStack l : left.values()) p.getWorld().dropItemNaturally(p.getLocation(), l);
    }

    // ---------- lifesteal ----------
    private Attribute maxAttr() {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("max_health"));
        if (a == null) a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.max_health"));
        return a;
    }

    private int hearts(UUID u) { return data.getInt("hearts." + u, getConfig().getInt("start-hearts", 10)); }

    private void setHearts(UUID u, int h) {
        data.set("hearts." + u, h);
        dirty = true;
        Player p = Bukkit.getPlayer(u);
        if (p != null) applyHealth(p, false);
    }

    private void applyHealth(Player p, boolean fill) {
        Attribute at = maxAttr();
        if (at == null) return;
        AttributeInstance in = p.getAttribute(at);
        if (in == null) return;
        int h = Math.max(1, hearts(p.getUniqueId()));
        in.setBaseValue(h * 2.0);
        if (fill) p.setHealth(h * 2.0);
        else if (p.getHealth() > h * 2.0) p.setHealth(h * 2.0);
    }

    private ItemStack heartItem() {
        ItemStack it = new ItemStack(Material.NETHER_STAR);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(c("&c&l\u2764 Heart"));
        m.setLore(List.of(c("&7Right-click to gain +1 heart")));
        m.getPersistentDataContainer().set(heartKey, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(m);
        return it;
    }

    private boolean isHeart(ItemStack it) {
        return it != null && it.hasItemMeta()
                && it.getItemMeta().getPersistentDataContainer().has(heartKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        applyHealth(e.getPlayer(), false);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(this, () -> applyHealth(p, true), 2L);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        Player v = e.getEntity();
        Player k = v.getKiller();
        int h = hearts(v.getUniqueId()) - 1;
        setHearts(v.getUniqueId(), Math.max(0, h));
        int max = getConfig().getInt("max-hearts", 20);
        if (k != null && !k.equals(v)) {
            if (hearts(k.getUniqueId()) < max) {
                setHearts(k.getUniqueId(), hearts(k.getUniqueId()) + 1);
                msg(k, "You stole a heart from &c" + v.getName() + "&7!");
            } else {
                give(k, heartItem());
                msg(k, "You are at max hearts, so you received a Heart item instead.");
            }
        }
        if (h <= 0) {
            String name = v.getName();
            Bukkit.getScheduler().runTaskLater(this, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "ban " + name + " You lost all your hearts! Ask for a revive."), 5L);
        } else {
            msg(v, "You lost a heart. Hearts left: &c" + h);
        }
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        ItemStack it = p.getInventory().getItemInMainHand();
        if (!isHeart(it)) return;
        e.setCancelled(true);
        int max = getConfig().getInt("max-hearts", 20);
        if (hearts(p.getUniqueId()) >= max) {
            msg(p, "You already have the max hearts (" + max + ").");
            return;
        }
        it.setAmount(it.getAmount() - 1);
        setHearts(p.getUniqueId(), hearts(p.getUniqueId()) + 1);
        msg(p, "You gained a heart! Hearts: &c" + hearts(p.getUniqueId()));
    }

    // ---------- commands ----------
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        String n = cmd.getName().toLowerCase();
        if (ADMIN_CMDS.contains(n) && !s.hasPermission("lifecore.admin")) {
            msg(s, "&cNo permission.");
            return true;
        }
        if (n.equals("eco")) return ecoCmd(s, a);
        if (n.equals("revive")) return reviveCmd(s, a);
        if (n.equals("baltop")) { baltop(s); return true; }
        if (!(s instanceof Player p)) {
            msg(s, "Players only.");
            return true;
        }
        UUID u = p.getUniqueId();
        switch (n) {
            // economy
            case "bal" -> {
                if (a.length > 0) {
                    OfflinePlayer o = Bukkit.getOfflinePlayer(a[0]);
                    msg(p, a[0] + "'s balance: &a" + money(bal(o.getUniqueId())));
                } else msg(p, "Balance: &a" + money(bal(u)));
            }
            case "pay" -> {
                if (a.length < 2) { msg(p, "Usage: /pay <player> <amount>"); return true; }
                Player t = Bukkit.getPlayerExact(a[0]);
                Double amt = parseAmount(a[1]);
                if (t == null || t.equals(p)) { msg(p, "Player not found."); return true; }
                if (amt == null) { msg(p, "Invalid amount."); return true; }
                if (bal(u) < amt) { msg(p, "Not enough money."); return true; }
                setBal(u, bal(u) - amt);
                setBal(t.getUniqueId(), bal(t.getUniqueId()) + amt);
                msg(p, "Sent &a" + money(amt) + "&7 to " + t.getName());
                msg(t, "Received &a" + money(amt) + "&7 from " + p.getName());
            }
            // sell
            case "sell" -> {
                boolean all = a.length > 0 && a[0].equalsIgnoreCase("all");
                double total = sellItems(p, all);
                if (total <= 0) msg(p, "Nothing sellable. Use &f/sell hand&7 or &f/sell all&7.");
                else msg(p, "Sold for &a" + money(total));
            }
            case "shop" -> openShop(p);
            // auction
            case "ah" -> {
                if (a.length >= 1 && a[0].equalsIgnoreCase("sell")) {
                    if (a.length < 2) { msg(p, "Usage: /ah sell <price>"); return true; }
                    Double price = parseAmount(a[1]);
                    if (price == null) { msg(p, "Invalid price."); return true; }
                    ItemStack hand = p.getInventory().getItemInMainHand();
                    if (hand.getType().isAir()) { msg(p, "Hold an item in your hand."); return true; }
                    int count = 0;
                    ConfigurationSection ah = data.getConfigurationSection("ah");
                    if (ah != null) for (String id : ah.getKeys(false))
                        if (u.toString().equals(ah.getString(id + ".seller"))) count++;
                    if (count >= getConfig().getInt("ah-max-listings", 5)) { msg(p, "Listing limit reached."); return true; }
                    int id = data.getInt("ah-next", 1);
                    data.set("ah-next", id + 1);
                    data.set("ah." + id + ".seller", u.toString());
                    data.set("ah." + id + ".price", price);
                    data.set("ah." + id + ".item", hand.clone());
                    dirty = true;
                    p.getInventory().setItemInMainHand(null);
                    msg(p, "Listed for &a" + money(price) + "&7. Click your listing in /ah to cancel it.");
                } else openAh(p);
            }
            // teleports
            case "rtp" -> rtp(p);
            case "tpa" -> {
                if (a.length < 1) { msg(p, "Usage: /tpa <player>"); return true; }
                Player t = Bukkit.getPlayerExact(a[0]);
                if (t == null || t.equals(p)) { msg(p, "Player not found."); return true; }
                tpaReq.put(t.getUniqueId(), u);
                tpaTime.put(t.getUniqueId(), System.currentTimeMillis());
                msg(p, "Request sent to " + t.getName());
                msg(t, p.getName() + " wants to teleport to you. &a/tpaccept&7 or &c/tpdeny");
            }
            case "tpaccept" -> {
                UUID rq = tpaReq.remove(u);
                Long t0 = tpaTime.remove(u);
                Player r = rq == null ? null : Bukkit.getPlayer(rq);
                if (r == null || t0 == null || System.currentTimeMillis() - t0 > 60000) {
                    msg(p, "No pending request.");
                    return true;
                }
                r.teleport(p.getLocation());
                msg(r, "Teleported!");
                msg(p, "Request accepted.");
            }
            case "tpdeny" -> {
                UUID rq = tpaReq.remove(u);
                tpaTime.remove(u);
                Player r = rq == null ? null : Bukkit.getPlayer(rq);
                msg(p, "Request denied.");
                if (r != null) msg(r, p.getName() + " denied your request.");
            }
            // homes
            case "sethome" -> {
                String name = homeName(a);
                ConfigurationSection cs = data.getConfigurationSection("homes." + u);
                int cnt = cs == null ? 0 : cs.getKeys(false).size();
                boolean exists = cs != null && cs.contains(name);
                if (!exists && cnt >= getConfig().getInt("max-homes", 3)) {
                    msg(p, "You reached the max homes (" + getConfig().getInt("max-homes", 3) + ").");
                    return true;
                }
                data.set("homes." + u + "." + name, p.getLocation());
                dirty = true;
                msg(p, "Home &f" + name + "&7 set.");
            }
            case "home" -> {
                Location l = data.getLocation("homes." + u + "." + homeName(a));
                if (l == null) msg(p, "Home not found. See /homes");
                else { p.teleport(l); msg(p, "Teleported home."); }
            }
            case "delhome" -> {
                String name = homeName(a);
                if (!data.contains("homes." + u + "." + name)) { msg(p, "Home not found."); return true; }
                data.set("homes." + u + "." + name, null);
                dirty = true;
                msg(p, "Home deleted.");
            }
            case "homes" -> {
                ConfigurationSection cs = data.getConfigurationSection("homes." + u);
                msg(p, "Homes: &f" + (cs == null || cs.getKeys(false).isEmpty() ? "none" : String.join(", ", cs.getKeys(false))));
            }
            // spawn
            case "spawn" -> {
                Location l = data.getLocation("spawn");
                p.teleport(l != null ? l : Bukkit.getWorlds().get(0).getSpawnLocation());
                msg(p, "Teleported to spawn.");
            }
            case "setspawn" -> {
                data.set("spawn", p.getLocation());
                dirty = true;
                msg(p, "Spawn set.");
            }
            // warps
            case "warp" -> {
                if (a.length < 1) { warps(p); return true; }
                Location l = data.getLocation("warps." + a[0].toLowerCase().replace(".", ""));
                if (l == null) msg(p, "Warp not found.");
                else { p.teleport(l); msg(p, "Warped."); }
            }
            case "warps" -> warps(p);
            case "setwarp" -> {
                if (a.length < 1) { msg(p, "Usage: /setwarp <name>"); return true; }
                data.set("warps." + a[0].toLowerCase().replace(".", ""), p.getLocation());
                dirty = true;
                msg(p, "Warp set.");
            }
            case "delwarp" -> {
                if (a.length < 1) { msg(p, "Usage: /delwarp <name>"); return true; }
                data.set("warps." + a[0].toLowerCase().replace(".", ""), null);
                dirty = true;
                msg(p, "Warp deleted.");
            }
            // lifesteal
            case "hearts" -> {
                if (a.length > 0) {
                    OfflinePlayer o = Bukkit.getOfflinePlayer(a[0]);
                    msg(p, a[0] + " has &c" + hearts(o.getUniqueId()) + "&7 hearts.");
                } else msg(p, "You have &c" + hearts(u) + "&7 hearts.");
            }
            case "withdraw" -> {
                int amt = 1;
                if (a.length > 0) {
                    try { amt = Integer.parseInt(a[0]); } catch (NumberFormatException ex) { amt = 0; }
                }
                if (amt < 1) { msg(p, "Invalid amount."); return true; }
                if (hearts(u) - amt < 1) { msg(p, "You must keep at least 1 heart."); return true; }
                setHearts(u, hearts(u) - amt);
                for (int i = 0; i < amt; i++) give(p, heartItem());
                msg(p, "Withdrew &c" + amt + "&7 heart(s).");
            }
            default -> { return false; }
        }
        return true;
    }

    private String homeName(String[] a) {
        return (a.length > 0 ? a[0] : "home").toLowerCase().replace(".", "");
    }

    private void warps(Player p) {
        ConfigurationSection cs = data.getConfigurationSection("warps");
        msg(p, "Warps: &f" + (cs == null || cs.getKeys(false).isEmpty() ? "none" : String.join(", ", cs.getKeys(false))));
    }

    private boolean ecoCmd(CommandSender s, String[] a) {
        if (a.length < 3) { msg(s, "Usage: /eco <give|take|set> <player> <amount>"); return true; }
        OfflinePlayer o = Bukkit.getOfflinePlayer(a[1]);
        Double amt = parseAmount(a[2]);
        if (amt == null) { msg(s, "Invalid amount."); return true; }
        UUID u = o.getUniqueId();
        switch (a[0].toLowerCase()) {
            case "give" -> setBal(u, bal(u) + amt);
            case "take" -> setBal(u, bal(u) - amt);
            case "set" -> setBal(u, amt);
            default -> { msg(s, "Usage: /eco <give|take|set> <player> <amount>"); return true; }
        }
        msg(s, a[1] + " balance is now &a" + money(bal(u)));
        return true;
    }

    private boolean reviveCmd(CommandSender s, String[] a) {
        if (a.length < 1) { msg(s, "Usage: /revive <player>"); return true; }
        OfflinePlayer o = Bukkit.getOfflinePlayer(a[0]);
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "pardon " + a[0]);
        setHearts(o.getUniqueId(), getConfig().getInt("revive-hearts", 3));
        msg(s, a[0] + " revived with " + getConfig().getInt("revive-hearts", 3) + " hearts.");
        return true;
    }

    private void baltop(CommandSender s) {
        ConfigurationSection cs = data.getConfigurationSection("bal");
        if (cs == null) { msg(s, "No data yet."); return; }
        List<String> keys = new ArrayList<>(cs.getKeys(false));
        keys.sort((x, y) -> Double.compare(cs.getDouble(y), cs.getDouble(x)));
        msg(s, "&6Top balances:");
        int i = 1;
        for (String k : keys) {
            if (i > 10) break;
            String name = Bukkit.getOfflinePlayer(UUID.fromString(k)).getName();
            s.sendMessage(c("&e" + i + ". &f" + (name == null ? k : name) + " &a" + money(cs.getDouble(k))));
            i++;
        }
    }

    // ---------- sell ----------
    private double sellItems(Player p, boolean all) {
        PlayerInventory inv = p.getInventory();
        double total = 0;
        int held = inv.getHeldItemSlot();
        for (int i = 0; i < 36; i++) {
            if (!all && i != held) continue;
            ItemStack it = inv.getItem(i);
            if (it == null || it.getType().isAir() || it.hasItemMeta()) continue;
            double price = getConfig().getDouble("prices." + it.getType().name() + ".sell", 0);
            if (price <= 0) continue;
            total += price * it.getAmount();
            inv.setItem(i, null);
        }
        if (total > 0) setBal(p.getUniqueId(), bal(p.getUniqueId()) + total);
        return total;
    }

    // ---------- shop ----------
    private void openShop(Player p) {
        Gui g = new Gui("shop");
        g.inv = Bukkit.createInventory(g, 54, c("&8Shop"));
        ConfigurationSection cs = getConfig().getConfigurationSection("prices");
        if (cs != null) {
            for (String key : cs.getKeys(false)) {
                if (g.ids.size() >= 54) break;
                Material m = Material.matchMaterial(key);
                double buy = cs.getDouble(key + ".buy", 0);
                if (m == null || buy <= 0) continue;
                g.inv.setItem(g.ids.size(), withLore(new ItemStack(m),
                        "&7Buy: &a" + money(buy),
                        "&8Left-click: buy 1",
                        "&8Shift-click: buy 64"));
                g.ids.add(key);
            }
        }
        p.openInventory(g.inv);
    }

    private void shopBuy(Player p, Gui g, int slot, boolean shift) {
        if (slot < 0 || slot >= g.ids.size()) return;
        String key = g.ids.get(slot);
        Material m = Material.matchMaterial(key);
        double price = getConfig().getDouble("prices." + key + ".buy", 0);
        if (m == null || price <= 0) return;
        int amt = shift ? 64 : 1;
        double cost = price * amt;
        if (bal(p.getUniqueId()) < cost) { msg(p, "Not enough money. Need &a" + money(cost)); return; }
        Map<Integer, ItemStack> left = p.getInventory().addItem(new ItemStack(m, amt));
        int notGiven = 0;
        for (ItemStack l : left.values()) notGiven += l.getAmount();
        int given = amt - notGiven;
        if (given <= 0) { msg(p, "Your inventory is full."); return; }
        setBal(p.getUniqueId(), bal(p.getUniqueId()) - price * given);
        msg(p, "Bought &f" + given + "x " + m.name().toLowerCase() + "&7 for &a" + money(price * given));
    }

    // ---------- auction house ----------
    private void openAh(Player p) {
        Gui g = new Gui("ah");
        g.inv = Bukkit.createInventory(g, 54, c("&8Auction House"));
        ConfigurationSection ah = data.getConfigurationSection("ah");
        if (ah != null) {
            for (String id : ah.getKeys(false)) {
                if (g.ids.size() >= 54) break;
                ItemStack item = ah.getItemStack(id + ".item");
                if (item == null) continue;
                String seller = ah.getString(id + ".seller", "");
                String sname = Bukkit.getOfflinePlayer(UUID.fromString(seller)).getName();
                boolean own = seller.equals(p.getUniqueId().toString());
                g.inv.setItem(g.ids.size(), withLore(item,
                        "&7Price: &a" + money(ah.getDouble(id + ".price")),
                        "&7Seller: &f" + (sname == null ? "?" : sname),
                        own ? "&cClick to cancel listing" : "&eClick to buy"));
                g.ids.add(id);
            }
        }
        p.openInventory(g.inv);
    }

    private void ahClick(Player p, Gui g, int slot) {
        if (slot < 0 || slot >= g.ids.size()) return;
        String id = g.ids.get(slot);
        String path = "ah." + id;
        if (!data.contains(path)) { msg(p, "That listing is gone."); p.closeInventory(); return; }
        ItemStack item = data.getItemStack(path + ".item");
        String seller = data.getString(path + ".seller", "");
        double price = data.getDouble(path + ".price");
        if (item == null) return;
        UUID buyer = p.getUniqueId();
        if (seller.equals(buyer.toString())) {
            data.set(path, null);
            dirty = true;
            give(p, item);
            msg(p, "Listing cancelled.");
        } else {
            if (bal(buyer) < price) { msg(p, "Not enough money."); return; }
            UUID su = UUID.fromString(seller);
            double tax = getConfig().getDouble("ah-tax-percent", 5) / 100.0;
            double payout = price * (1 - tax);
            setBal(buyer, bal(buyer) - price);
            setBal(su, bal(su) + payout);
            data.set(path, null);
            dirty = true;
            give(p, item);
            msg(p, "Bought for &a" + money(price));
            Player sp = Bukkit.getPlayer(su);
            if (sp != null) msg(sp, p.getName() + " bought your item. You received &a" + money(payout));
        }
        openAh(p);
    }

    // ---------- gui events ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Gui g)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;
        if (g.type.equals("shop")) shopBuy(p, g, e.getSlot(), e.isShiftClick());
        else if (g.type.equals("ah")) ahClick(p, g, e.getSlot());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Gui) e.setCancelled(true);
    }

    // ---------- rtp ----------
    private void rtp(Player p) {
        long now = System.currentTimeMillis();
        long cd = getConfig().getLong("rtp-cooldown-seconds", 30) * 1000;
        Long last = rtpCooldown.get(p.getUniqueId());
        if (last != null && now - last < cd && !p.hasPermission("lifecore.admin")) {
            msg(p, "Wait " + ((cd - (now - last)) / 1000 + 1) + "s before using /rtp again.");
            return;
        }
        World w = p.getWorld().getEnvironment() == World.Environment.NORMAL ? p.getWorld() : Bukkit.getWorlds().get(0);
        rtpCooldown.put(p.getUniqueId(), now);
        msg(p, "Finding a safe spot...");
        tryRtp(p, w, 0);
    }

    private void tryRtp(Player p, World w, int tries) {
        if (tries > 15) { msg(p, "Could not find a safe spot, try again."); return; }
        int r = getConfig().getInt("rtp-radius", 5000);
        int x = w.getSpawnLocation().getBlockX() + rnd.nextInt(r * 2 + 1) - r;
        int z = w.getSpawnLocation().getBlockZ() + rnd.nextInt(r * 2 + 1) - r;
        w.getChunkAtAsync(x >> 4, z >> 4).thenAccept(chunk -> {
            int y = w.getHighestBlockYAt(x, z);
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (b.isLiquid() || m == Material.MAGMA_BLOCK || m == Material.CACTUS || m == Material.FIRE
                    || m == Material.CAMPFIRE || m == Material.SWEET_BERRY_BUSH || m == Material.POWDER_SNOW) {
                tryRtp(p, w, tries + 1);
                return;
            }
            if (!p.isOnline()) return;
            p.teleport(new Location(w, x + 0.5, y + 1, z + 0.5));
            msg(p, "Teleported to &f" + x + ", " + (y + 1) + ", " + z);
        });
    }
}
