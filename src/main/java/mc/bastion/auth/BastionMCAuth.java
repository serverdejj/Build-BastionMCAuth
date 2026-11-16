package mc.bastion.auth;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.ServerCommandEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.mindrot.jbcrypt.BCrypt;

import java.io.File;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BastionMCAuth extends JavaPlugin implements Listener, TabCompleter {
    private Connection db;
    private final Set<UUID> authenticated = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> deadlines = new HashMap<>();
    private final Map<UUID, Integer> attempts = new HashMap<>();
    private final Map<UUID, PotionEffect> oldDarkness = new HashMap<>();
    private final Map<UUID, PotionEffect> oldSlowness = new HashMap<>();
    private final Map<UUID, Location> frozen = new HashMap<>();
    private final Set<UUID> internalSpawnTeleports = new HashSet<>();
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            getDataFolder().mkdirs();
            db = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "accounts.db").getAbsolutePath());
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("CREATE TABLE IF NOT EXISTS accounts (username TEXT PRIMARY KEY COLLATE NOCASE, hash TEXT NOT NULL, created_at INTEGER NOT NULL)");
            }
        } catch (SQLException e) {
            getLogger().severe("No se pudo abrir SQLite: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }
        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("login")).setExecutor(this);
        Objects.requireNonNull(getCommand("register")).setExecutor(this);
        getCommand("login").setTabCompleter(this);
        getCommand("register").setTabCompleter(this);
        Objects.requireNonNull(getCommand("authsetspawn")).setExecutor(this);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, Math.max(1L, getConfig().getLong("titles.refresh-ticks", 20L)));
        for (Player p : Bukkit.getOnlinePlayers()) begin(p);
        if (!Bukkit.getOnlineMode()) getLogger().warning("Servidor offline-mode: /login no verifica propiedad de cuentas Microsoft. Proteja el servidor con firewall y proxy adecuados.");
    }
    @Override public void onDisable() {
        for (Player p : Bukkit.getOnlinePlayers()) restore(p);
        authenticated.clear(); deadlines.clear(); attempts.clear(); frozen.clear();
        if (db != null) try { db.close(); } catch (SQLException ignored) { }
    }
    private String color(String input) {
        Matcher m = HEX.matcher(input == null ? "" : input);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String h = m.group(1); StringBuilder x = new StringBuilder("§x");
            for (char c : h.toCharArray()) x.append('§').append(c);
            m.appendReplacement(b, Matcher.quoteReplacement(x.toString()));
        }
        m.appendTail(b);
        return ChatColor.translateAlternateColorCodes('&', b.toString());
    }
    private String msg(String key) { return color(getConfig().getString("messages." + key, "&c" + key)); }
    private boolean locked(Player p) { return !authenticated.contains(p.getUniqueId()); }
    private String key(Player p) { return p.getName().toLowerCase(Locale.ROOT); }
    private String lookup(String username) throws SQLException {
        try (PreparedStatement s = db.prepareStatement("SELECT hash FROM accounts WHERE username=?")) {
            s.setString(1, username);
            try (ResultSet r = s.executeQuery()) { return r.next() ? r.getString(1) : null; }
        }
    }
    private Location configuredSpawn() {
        if (!getConfig().getBoolean("spawn.teleport-on-join", false)) return null;
        String worldName = getConfig().getString("spawn.location.world", "");
        org.bukkit.World world = Bukkit.getWorld(worldName);
        if (world == null) {
            getLogger().warning("Spawn de autenticacion no configurado o mundo inexistente: " + worldName);
            return null;
        }
        return new Location(world,
            getConfig().getDouble("spawn.location.x"), getConfig().getDouble("spawn.location.y"),
            getConfig().getDouble("spawn.location.z"),
            (float) getConfig().getDouble("spawn.location.yaw"),
            (float) getConfig().getDouble("spawn.location.pitch"));
    }
    private void sendToAuthSpawn(Player p) {
        Location spawn = configuredSpawn();
        if (spawn == null || !p.isOnline()) return;
        UUID id = p.getUniqueId();
        internalSpawnTeleports.add(id);
        try {
            if (p.teleport(spawn)) frozen.put(id, p.getLocation().clone());
            else getLogger().warning("No se pudo teletransportar al spawn a " + p.getName());
        } finally { internalSpawnTeleports.remove(id); }
    }
    private void begin(Player p) {
        UUID id = p.getUniqueId(); authenticated.remove(id); attempts.put(id, 0);
        deadlines.put(id, System.currentTimeMillis() + getConfig().getLong("authentication.login-timeout-seconds", 60) * 1000L);
        frozen.put(id, p.getLocation().clone());
        oldDarkness.put(id, p.getPotionEffect(PotionEffectType.DARKNESS));
        oldSlowness.put(id, p.getPotionEffect(PotionEffectType.SLOWNESS));
        if (getConfig().getBoolean("login-effects.enabled", true)) {
            boolean particles = !getConfig().getBoolean("login-effects.hide-particles", true);
            boolean icon = !getConfig().getBoolean("login-effects.hide-icon", true);
            if (getConfig().getBoolean("login-effects.darkness", true)) p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, Integer.MAX_VALUE, 0, false, particles, icon));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, Integer.MAX_VALUE, Math.min(255, Math.max(0, getConfig().getInt("login-effects.slowness-amplifier", 9))), false, particles, icon));
        }
        try { p.sendMessage(msg(lookup(key(p)) == null ? "register-required" : "login-required")); }
        catch (SQLException e) { p.kickPlayer(msg("unavailable")); }
    }
    private void restore(Player p) {
        UUID id = p.getUniqueId();
        if (oldDarkness.containsKey(id)) {
            p.removePotionEffect(PotionEffectType.DARKNESS);
            PotionEffect prior = oldDarkness.remove(id); if (prior != null) p.addPotionEffect(prior);
        }
        if (oldSlowness.containsKey(id)) {
            p.removePotionEffect(PotionEffectType.SLOWNESS);
            PotionEffect prior = oldSlowness.remove(id); if (prior != null) p.addPotionEffect(prior);
        }
        p.resetTitle(); deadlines.remove(id); attempts.remove(id); frozen.remove(id);
    }
    private void unlock(Player p, String message) { authenticated.add(p.getUniqueId()); restore(p); p.sendMessage(msg(message)); }
    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!locked(p)) continue;
            Long end = deadlines.get(p.getUniqueId()); if (end == null) { begin(p); continue; }
            if (now >= end) { p.kickPlayer(msg("timeout")); continue; }
            if (getConfig().getBoolean("security.freeze-player", true)) {
                Location anchor = frozen.get(p.getUniqueId());
                if (anchor != null && (p.getWorld() != anchor.getWorld() || p.getLocation().distanceSquared(anchor) > 0.04)) {
                    internalSpawnTeleports.add(p.getUniqueId());
                    try { p.teleport(anchor); } finally { internalSpawnTeleports.remove(p.getUniqueId()); }
                }
            }
            if (getConfig().getBoolean("titles.enabled", true)) {
                try {
                    boolean registered = lookup(key(p)) != null;
                    String title = color(getConfig().getString("titles.title", "&eBASTION MC"));
                    String sub = color(getConfig().getString(registered ? "titles.login-subtitle" : "titles.register-subtitle", "&a/login"));
                    int remaining = (int)Math.ceil((end - now) / 1000.0);
                    p.sendTitle(title, sub + ChatColor.GRAY + "  [" + remaining + "s]", 0, (int)Math.max(25L, getConfig().getLong("titles.refresh-ticks", 20) + 10), 0);
                } catch (SQLException e) { p.kickPlayer(msg("unavailable")); }
            }
        }
    }
    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("authsetspawn")) {
            if (!(sender instanceof Player admin)) { sender.sendMessage("Solo jugadores."); return true; }
            if (!admin.hasPermission("bastionmcauth.admin.setspawn")) { admin.sendMessage(ChatColor.RED + "Sin permiso."); return true; }
            if (locked(admin)) { admin.sendMessage(ChatColor.RED + "Primero inicia sesion."); return true; }
            Location loc = admin.getLocation();
            getConfig().set("spawn.location.world", loc.getWorld().getName());
            getConfig().set("spawn.location.x", loc.getX());
            getConfig().set("spawn.location.y", loc.getY());
            getConfig().set("spawn.location.z", loc.getZ());
            getConfig().set("spawn.location.yaw", loc.getYaw());
            getConfig().set("spawn.location.pitch", loc.getPitch());
            getConfig().set("spawn.teleport-on-join", true);
            saveConfig();
            admin.sendMessage(ChatColor.GREEN + "Spawn de BastionMCAuth establecido. Teletransporte al entrar activado.");
            return true;
        }
        if (!(sender instanceof Player p)) { sender.sendMessage("Solo jugadores."); return true; }
        if (!locked(p)) { p.sendMessage(msg("already-logged-in")); return true; }
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        try {
            String stored = lookup(key(p));
            if (name.equals("register")) {
                if (stored != null) { p.sendMessage(msg("already-registered")); return true; }
                if (args.length != 2) { p.sendMessage(msg("usage-register")); return true; }
                if (!args[0].equals(args[1])) { p.sendMessage(msg("passwords-differ")); return true; }
                if (args[0].length() < getConfig().getInt("authentication.min-password-length", 10)) { p.sendMessage(msg("password-short")); return true; }
                int cost = Math.min(14, Math.max(10, getConfig().getInt("authentication.bcrypt-cost", 12)));
                String hash = BCrypt.hashpw(args[0], BCrypt.gensalt(cost));
                try (PreparedStatement s = db.prepareStatement("INSERT INTO accounts(username,hash,created_at) VALUES(?,?,?)")) {
                    s.setString(1, key(p)); s.setString(2, hash); s.setLong(3, System.currentTimeMillis()); s.executeUpdate();
                }
                unlock(p, "registered"); return true;
            }
            if (stored == null) { p.sendMessage(msg("not-registered")); return true; }
            if (args.length != 1) { p.sendMessage(msg("usage-login")); return true; }
            if (BCrypt.checkpw(args[0], stored)) { unlock(p, "logged-in"); return true; }
            int n = attempts.merge(p.getUniqueId(), 1, Integer::sum);
            if (n >= getConfig().getInt("authentication.max-login-attempts", 3)) p.kickPlayer(msg("too-many-attempts"));
            else p.sendMessage(msg("wrong-password"));
        } catch (Exception e) {
            getLogger().warning("Fallo en autenticación para " + p.getName() + ": " + e.getClass().getSimpleName());
            p.kickPlayer(msg("unavailable"));
        }
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) { return Collections.emptyList(); }
    @EventHandler(priority = EventPriority.LOWEST) public void join(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        begin(p);
        // Un tick despues del ingreso, una vez que otros plugins han procesado el evento.
        Bukkit.getScheduler().runTask(this, () -> {
            if (p.isOnline() && locked(p)) sendToAuthSpawn(p);
        });
    }
    @EventHandler public void quit(PlayerQuitEvent e) { UUID id = e.getPlayer().getUniqueId(); if (locked(e.getPlayer())) restore(e.getPlayer()); authenticated.remove(id); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false) public void move(PlayerMoveEvent e) {
        if (!locked(e.getPlayer()) || !getConfig().getBoolean("security.freeze-player", true)) return;
        Location a = e.getFrom(), b = e.getTo();
        if (b != null && (a.getX() != b.getX() || a.getY() != b.getY() || a.getZ() != b.getZ() || a.getWorld() != b.getWorld())) { e.setCancelled(true); e.setTo(a); }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void teleport(PlayerTeleportEvent e) { if (locked(e.getPlayer()) && !internalSpawnTeleports.contains(e.getPlayer().getUniqueId())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void portal(PlayerPortalEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void command(PlayerCommandPreprocessEvent e) {
        if (!locked(e.getPlayer())) return;
        String raw = e.getMessage().trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (!raw.equals("/login") && !raw.equals("/register")) { e.setCancelled(true); e.getPlayer().sendMessage(msg("login-required")); }
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void suggestions(PlayerCommandSendEvent e) {
        if (locked(e.getPlayer())) e.getCommands().removeIf(s -> !s.equalsIgnoreCase("login") && !s.equalsIgnoreCase("register"));
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void chat(AsyncPlayerChatEvent e) { if (locked(e.getPlayer()) && getConfig().getBoolean("security.block-chat", true)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void interact(PlayerInteractEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void interactEntity(PlayerInteractEntityEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void interactAt(PlayerInteractAtEntityEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void breakBlock(BlockBreakEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void placeBlock(BlockPlaceEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void inventory(InventoryClickEvent e) { if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void drag(InventoryDragEvent e) { if (e.getWhoClicked() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void drop(PlayerDropItemEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void pickup(EntityPickupItemEvent e) { if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void damage(EntityDamageEvent e) { if (e.getEntity() instanceof Player p && locked(p)) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void attack(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && locked(p)) e.setCancelled(true);
        if (e.getDamager() instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player p && locked(p)) e.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.HIGHEST) public void swap(PlayerSwapHandItemsEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void consume(PlayerItemConsumeEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void bed(PlayerBedEnterEvent e) { if (locked(e.getPlayer())) e.setCancelled(true); }
    @EventHandler(priority = EventPriority.HIGHEST) public void respawn(PlayerRespawnEvent e) { if (locked(e.getPlayer())) Bukkit.getScheduler().runTask(this, () -> { if (e.getPlayer().isOnline() && locked(e.getPlayer())) begin(e.getPlayer()); }); }
}
