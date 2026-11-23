package com.macesmp.maceevolution;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.*;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MaceEvolutionPlugin extends JavaPlugin implements Listener {

    private NamespacedKey maceTierKey;
    private NamespacedKey maceOwnerKey;

    private File dataFile;
    private FileConfiguration dataConfig;

    // Anti-farm cooldown: Killer UUID -> (Victim UUID -> Timestamp in ms)
    private final Map<UUID, Map<UUID, Long>> killCooldowns = new ConcurrentHashMap<>();
    private static final long KILL_COOLDOWN_MS = 15L * 60L * 1000L; // 15 minutes

    // Corruption healing reduction tracker: Victim UUID -> Expiry timestamp in ms
    private final Map<UUID, Long> corruptedPlayers = new ConcurrentHashMap<>();

    @Override
    public void onEnable() {
        maceTierKey = new NamespacedKey(this, "mace_tier");
        maceOwnerKey = new NamespacedKey(this, "mace_owner");

        setupDataFile();
        Bukkit.getPluginManager().registerEvents(this, this);
        startAuraTask();
    }

    @Override
    public void onDisable() {
        saveDataFile();
    }

    private void setupDataFile() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        dataFile = new File(getDataFolder(), "kills.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create kills.yml!");
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveDataFile() {
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Could not save kills.yml!");
        }
    }

    private int getKills(UUID uuid) {
        return dataConfig.getInt("players." + uuid + ".kills", 0);
    }

    private void setKills(UUID uuid, int kills) {
        dataConfig.set("players." + uuid + ".kills", Math.max(0, kills));
        saveDataFile();
    }

    // ==================== TIER DEFINITIONS ====================

    public enum MaceTier {
        RUSTED(1, 0, "RUSTED MACE", TextColor.color(0x8B7355),
                List.of("Density I", "Unbreaking I", "Smite I",
                        "Impact I: Slightly increases smash damage.",
                        "Lightweight I: Small movement bonus after a smash.")),
        BLOODFORGED(2, 1, "BLOODFORGED MACE", TextColor.color(0xB22222),
                List.of("Density II", "Wind Burst I", "Unbreaking II",
                        "Bloodlust I: Gain Speed I briefly after a kill.",
                        "Brutality I: Bonus damage against players wearing armor.")),
        VOID(3, 3, "VOID MACE", TextColor.color(0x4B0082),
                List.of("Density III", "Wind Burst I", "Unbreaking II",
                        "Void Touch I: Applies brief Slowness on hit.",
                        "Gravity I: Creates a small visual pull effect.",
                        "Shockwave I: Releases particles on impact.")),
        REAPER(4, 6, "REAPER MACE", TextColor.color(0x2F4F4F),
                List.of("Density III", "Wind Burst II", "Unbreaking III",
                        "Reaper I: Gain Regeneration briefly after a kill.",
                        "Soul Drain I: Restore a small amount of health on a successful hit.",
                        "Executioner I: Bonus damage against low-health opponents.")),
        FROSTBITE(5, 10, "FROSTBITE MACE", TextColor.color(0x00CED1),
                List.of("Density IV", "Wind Burst II", "Unbreaking III",
                        "Frost I: Applies Slowness on hit.",
                        "Ice Prison I: Briefly creates an icy visual cage.",
                        "Frozen Impact I: Creates a snow particle explosion on impact.")),
        VENOM(6, 15, "VENOM MACE", TextColor.color(0x32CD32),
                List.of("Density IV", "Wind Burst II", "Unbreaking III",
                        "Venom II: Applies Poison briefly.",
                        "Corruption I: Reduces enemy healing temporarily.",
                        "Toxic Aura I: Creates green particles around the mace.")),
        CELESTIAL(7, 21, "CELESTIAL MACE", TextColor.color(0xFFD700),
                List.of("Density V", "Wind Burst III", "Unbreaking III", "Mending",
                        "Divine Protection I: Brief Resistance after a kill.",
                        "Angelic Power II: Brief Speed after a kill.",
                        "Holy Impact I: Creates a golden particle explosion.",
                        "Blessing I: Small chance to receive Regeneration after a kill.")),
        INFERNAL(8, 28, "INFERNAL MACE", TextColor.color(0xFF4500),
                List.of("Density V", "Wind Burst III", "Fire Aspect II", "Unbreaking III",
                        "Hellfire II: Creates a fire-themed impact effect.",
                        "Demonic Strength II: Increases smash damage.",
                        "Burning Soul I: Applies brief Fire Resistance after a kill.",
                        "Inferno I: Creates a fiery particle explosion.")),
        OBLIVION(9, 36, "OBLIVION MACE", TextColor.color(0x800080),
                List.of("Density V", "Wind Burst III", "Unbreaking III", "Mending",
                        "Obliteration II: Increases impact damage.",
                        "Dark Matter II: Creates a dark explosion effect.",
                        "Soul Reaper II: Restores some health after a kill.",
                        "Shadow Step I: Brief Speed boost after a kill.",
                        "Annihilation I: Creates a dark particle shockwave.")),
        GODSLAYER(10, 45, "GODSLAYER MACE", TextColor.color(0xFFAA00),
                List.of("Density V", "Wind Burst III", "Unbreaking III", "Mending",
                        "Godly Strength III: Massive smash damage bonus.",
                        "Divine Wrath III: Huge golden shockwave effect.",
                        "Immortality I: Brief Resistance after a kill.",
                        "Soul Devourer III: Restores health after a kill.",
                        "Celestial Fury III: Creates lightning particles.",
                        "Reality Breaker II: Creates a cosmic impact effect.",
                        "Overlord III: Brief Speed and Strength after a kill.",
                        "Godly Presence I: Creates a permanent aura of particles."));

        public final int tier;
        public final int requiredKills;
        public final String displayName;
        public final TextColor color;
        public final List<String> loreLines;

        MaceTier(int tier, int requiredKills, String displayName, TextColor color, List<String> loreLines) {
            this.tier = tier;
            this.requiredKills = requiredKills;
            this.displayName = displayName;
            this.color = color;
            this.loreLines = loreLines;
        }

        public static MaceTier fromKills(int kills) {
            MaceTier result = RUSTED;
            for (MaceTier t : values()) {
                if (kills >= t.requiredKills) {
                    result = t;
                }
            }
            return result;
        }

        public static MaceTier fromTierNumber(int num) {
            for (MaceTier t : values()) {
                if (t.tier == num) return t;
            }
            return RUSTED;
        }
    }

    // ==================== ITEM CREATION & SYNC ====================

    private ItemStack createMace(UUID owner, MaceTier tier, int kills) {
        ItemStack item = new ItemStack(Material.MACE);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(Component.text("⚔ " + tier.displayName + " ⚔", tier.color, TextDecoration.BOLD)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("Evolution " + tier.tier + " / 10  |  Kills: " + kills, NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());

        for (String line : tier.loreLines) {
            if (line.contains(":")) {
                String[] parts = line.split(":", 2);
                lore.add(Component.text("✦ " + parts[0] + ":", NamedTextColor.LIGHT_PURPLE)
                        .append(Component.text(parts[1], NamedTextColor.WHITE))
                        .decoration(TextDecoration.ITALIC, false));
            } else {
                lore.add(Component.text("• " + line, NamedTextColor.AQUA)
                        .decoration(TextDecoration.ITALIC, false));
            }
        }

        lore.add(Component.empty());
        lore.add(Component.text("SOULBOUND — Resets on death", NamedTextColor.DARK_RED)
                .decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(maceTierKey, PersistentDataType.INTEGER, tier.tier);
        meta.getPersistentDataContainer().set(maceOwnerKey, PersistentDataType.STRING, owner.toString());
        item.setItemMeta(meta);

        // Apply vanilla & unsafe enchantments
        switch (tier) {
            case RUSTED -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 1);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
                item.addUnsafeEnchantment(Enchantment.SMITE, 1);
            }
            case BLOODFORGED -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 2);
                item.addUnsafeEnchantment(Enchantment.WIND_BURST, 1);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 2);
            }
            case VOID -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 3);
                item.addUnsafeEnchantment(Enchantment.WIND_BURST, 1);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 2);
            }
            case REAPER -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 3);
                item.addUnsafeEnchantment(Enchantment.WIND_BURST, 2);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            }
            case FROSTBITE -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 4);
                item.addUnsafeEnchantment(Enchantment.WIND_BURST, 2);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            }
            case VENOM -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 4);
                item.addUnsafeEnchantment(Enchantment.WIND_BURST, 2);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            }
            case CELESTIAL -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 5);
                item.addUnsafeEnchantment(Enchantment.WIND_BURST, 3);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
                item.addUnsafeEnchantment(Enchantment.MENDING, 1);
            }
            case INFERNAL -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 5);
                item.addUnsafeEnchantment(Enchantment.WIND_BURST, 3);
                item.addUnsafeEnchantment(Enchantment.FIRE_ASPECT, 2);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            }
            case OBLIVION -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 5);
                item.addUnsafeEnchantment(Enchantment.WIND_BURST, 3);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
                item.addUnsafeEnchantment(Enchantment.MENDING, 1);
            }
            case GODSLAYER -> {
                item.addUnsafeEnchantment(Enchantment.DENSITY, 5);
                item.addUnsafeEnchantment(Enchantment.WIND_BURST, 3);
                item.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
                item.addUnsafeEnchantment(Enchantment.MENDING, 1);
            }
        }

        return item;
    }

    private boolean isEvolutionMace(ItemStack item) {
        if (item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(maceTierKey, PersistentDataType.INTEGER);
    }

    private MaceTier getMaceTier(ItemStack item) {
        if (!isEvolutionMace(item)) return null;
        Integer t = item.getItemMeta().getPersistentDataContainer().get(maceTierKey, PersistentDataType.INTEGER);
        return t == null ? null : MaceTier.fromTierNumber(t);
    }

    private void syncPlayerMace(Player player) {
        int kills = getKills(player.getUniqueId());
        MaceTier targetTier = MaceTier.fromKills(kills);
        ItemStack updatedMace = createMace(player.getUniqueId(), targetTier, kills);

        PlayerInventory inv = player.getInventory();
        boolean replaced = false;

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (isEvolutionMace(item)) {
                if (!replaced) {
                    inv.setItem(i, updatedMace);
                    replaced = true;
                } else {
                    // Anti-dupe: remove extra evolution maces
                    inv.setItem(i, null);
                }
            }
        }

        if (!replaced) {
            HashMap<Integer, ItemStack> overflow = inv.addItem(updatedMace);
            if (!overflow.isEmpty()) {
                player.sendMessage(Component.text("Make inventory space and run /mace to claim your Mace!", NamedTextColor.RED));
            }
        }
    }

    // ==================== JOIN, RESPAWN & SOULBOUND PROTECTION ====================

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        syncPlayerMace(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> syncPlayerMace(event.getPlayer()), 2L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (isEvolutionMace(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Component.text("Your soulbound mace cannot be dropped!", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();

        // Prevent moving evolution mace into chests, hoppers, shulkers, bundles, etc.
        if (event.getClickedInventory() != null && event.getClickedInventory().getType() != InventoryType.PLAYER) {
            if (isEvolutionMace(cursor) || isEvolutionMace(current)) {
                event.setCancelled(true);
            }
        }
        if (event.isShiftClick() && isEvolutionMace(current)
                && event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
            event.setCancelled(true);
        }
        if (event.getClick() == ClickType.NUMBER_KEY && event.getView().getTopInventory().getType() != InventoryType.CRAFTING) {
            ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
            if (isEvolutionMace(hotbarItem)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (isEvolutionMace(event.getOldCursor())) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < event.getView().getTopInventory().getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() != null && event.getRecipe().getResult().getType() == Material.MACE) {
            // Optional: prevents crafting duplicate vanilla maces if desired; remove if vanilla maces are allowed
        }
    }

    // ==================== DEATH & KILL PROGRESSION ====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        UUID victimId = victim.getUniqueId();
        int oldKills = getKills(victimId);
        MaceTier oldTier = MaceTier.fromKills(oldKills);

        // Remove soulbound mace from death drops so it cannot be looted or duped
        event.getDrops().removeIf(this::isEvolutionMace);

        // Check if victim held Godslayer Mace
        boolean hadGodslayer = oldTier == MaceTier.GODSLAYER ||
                Arrays.stream(victim.getInventory().getContents())
                        .anyMatch(item -> getMaceTier(item) == MaceTier.GODSLAYER);

        // Reset victim kills to 0 (Evolution 1)
        setKills(victimId, 0);

        if (hadGodslayer) {
            Bukkit.broadcast(Component.empty());
            Bukkit.broadcast(Component.text("☠ GODSLAYER DESTROYED ☠", TextColor.color(0x9B59B6), TextDecoration.BOLD));
            Bukkit.broadcast(Component.text("A GOD HAS FALLEN", NamedTextColor.RED, TextDecoration.BOLD));
            Bukkit.broadcast(Component.text(victim.getName() + " has lost the GODSLAYER MACE forever.", NamedTextColor.GRAY));
            Bukkit.broadcast(Component.text("Their legacy ends here.", TextColor.color(0x8E44AD)));
            Bukkit.broadcast(Component.empty());

            for (Player online : Bukkit.getOnlinePlayers()) {
                online.playSound(online.getLocation(), Sound.ENTITY_WITHER_DEATH, 1.0f, 0.7f);
            }
        } else if (oldKills > 0) {
            victim.sendMessage(Component.text("You died! Your mace has reset to the Rusted Mace (0 kills).", NamedTextColor.RED));
        }

        // Handle Killer Progression
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victimId)) return;

        ItemStack weapon = killer.getInventory().getItemInMainHand();
        MaceTier killerTier = getMaceTier(weapon);
        if (killerTier == null) return;

        // 15-Minute Anti-Farm Cooldown Check
        UUID killerId = killer.getUniqueId();
        long now = System.currentTimeMillis();
        Map<UUID, Long> killerHistory = killCooldowns.computeIfAbsent(killerId, k -> new ConcurrentHashMap<>());
        Long lastKillTime = killerHistory.get(victimId);

        if (lastKillTime != null && (now - lastKillTime) < KILL_COOLDOWN_MS) {
            long remainingSeconds = (KILL_COOLDOWN_MS - (now - lastKillTime)) / 1000L;
            long mins = remainingSeconds / 60L;
            long secs = remainingSeconds % 60L;
            killer.sendMessage(Component.text("Anti-Farm: You must wait " + mins + "m " + secs + "s before gaining a kill from " + victim.getName() + "!", NamedTextColor.YELLOW));
            return;
        }

        killerHistory.put(victimId, now);

        // Increment Kills & Evolve
        int previousKills = getKills(killerId);
        MaceTier previousTier = MaceTier.fromKills(previousKills);
        int newKills = previousKills + 1;
        setKills(killerId, newKills);
        MaceTier newTier = MaceTier.fromKills(newKills);

        syncPlayerMace(killer);
        triggerOnKillEffects(killer, newTier);

        if (newTier.tier > previousTier.tier) {
            killer.showTitle(Title.title(
                    Component.text("MACE EVOLVED!", newTier.color, TextDecoration.BOLD),
                    Component.text(newTier.displayName, NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))
            ));
            killer.playSound(killer.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.1f);
            killer.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, killer.getLocation().add(0, 1, 0), 40, 0.5, 0.8, 0.5, 0.1);

            if (newTier == MaceTier.GODSLAYER) {
                Bukkit.broadcast(Component.text("⚡ " + killer.getName() + " has awakened the GODSLAYER MACE (45 Kills)! ⚡",
                        TextColor.color(0xFFAA00), TextDecoration.BOLD));
            }
        } else {
            killer.sendMessage(Component.text("Kill recorded! (" + newKills + " kills)", NamedTextColor.GREEN));
        }
    }

    private void triggerOnKillEffects(Player killer, MaceTier tier) {
        switch (tier) {
            case BLOODFORGED -> // Bloodlust I: Speed I briefly
                    killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0));
            case REAPER -> // Reaper I: Regeneration briefly
                    killer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 0));
            case CELESTIAL -> {
                // Divine Protection I + Angelic Power II + Blessing I
                killer.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 100, 0));
                killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 1));
                if (Math.random() < 0.35) {
                    killer.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 80, 0));
                }
            }
            case INFERNAL -> // Burning Soul I: Fire Resistance
                    killer.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 200, 0));
            case OBLIVION -> {
                // Soul Reaper II + Shadow Step I
                healPlayer(killer, 4.0);
                killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0));
            }
            case GODSLAYER -> {
                // Immortality I + Soul Devourer III + Overlord III
                killer.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 120, 0));
                healPlayer(killer, 6.0);
                killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 120, 2));
                killer.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 120, 2));
            }
            default -> {}
        }
    }

    // ==================== COMBAT & CUSTOM ENCHANTMENT EFFECTS ====================

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;

        ItemStack weapon = attacker.getInventory().getItemInMainHand();
        MaceTier tier = getMaceTier(weapon);
        if (tier == null) return;

        boolean isSmash = attacker.getFallDistance() > 1.5f;
        double bonusDamage = 0.0;
        Location loc = target.getLocation().add(0, 1, 0);
        World world = target.getWorld();

        switch (tier) {
            case RUSTED -> {
                if (isSmash) {
                    bonusDamage += 1.5; // Impact I
                    attacker.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 50, 0)); // Lightweight I
                }
            }
            case BLOODFORGED -> {
                // Brutality I: Bonus damage against armored targets
                if (target.getEquipment() != null) {
                    for (ItemStack armor : target.getEquipment().getArmorContents()) {
                        if (armor != null && armor.getType() != Material.AIR) {
                            bonusDamage += 1.5;
                            break;
                        }
                    }
                }
            }
            case VOID -> {
                // Void Touch I + Gravity I + Shockwave I
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 0));
                Vector pull = attacker.getLocation().toVector().subtract(target.getLocation().toVector()).normalize().multiply(0.25);
                target.setVelocity(target.getVelocity().add(pull));
                world.spawnParticle(Particle.PORTAL, loc, 25, 0.6, 0.6, 0.6, 0.2);
                if (isSmash) {
                    world.spawnParticle(Particle.SONIC_BOOM, loc, 1);
                }
            }
            case REAPER -> {
                // Soul Drain I + Executioner I
                healPlayer(attacker, 1.0);
                double maxHp = Objects.requireNonNull(target.getAttribute(Attribute.MAX_HEALTH)).getValue();
                if (target.getHealth() <= maxHp * 0.35) {
                    bonusDamage += 3.0;
                }
            }
            case FROSTBITE -> {
                // Frost I + Ice Prison I + Frozen Impact I
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 70, 1));
                spawnIceCageVisual(target.getLocation());
                if (isSmash) {
                    world.spawnParticle(Particle.SNOWFLAKE, loc, 45, 1.2, 1.0, 1.2, 0.1);
                    world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.0f, 1.4f);
                }
            }
            case VENOM -> {
                // Venom II + Corruption I
                target.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 80, 1));
                if (target instanceof Player pTarget) {
                    corruptedPlayers.put(pTarget.getUniqueId(), System.currentTimeMillis() + 5000L);
                }
            }
            case CELESTIAL -> {
                // Holy Impact I
                if (isSmash) {
                    world.spawnParticle(Particle.WAX_ON, loc, 40, 1.2, 1.0, 1.2, 0.2);
                    world.spawnParticle(Particle.END_ROD, loc, 20, 0.8, 0.8, 0.8, 0.1);
                }
            }
            case INFERNAL -> {
                // Fire Aspect II guarantee + Hellfire II + Demonic Strength II + Inferno I
                target.setFireTicks(160);
                if (isSmash) {
                    bonusDamage += 3.5;
                    world.spawnParticle(Particle.FLAME, loc, 50, 1.3, 1.0, 1.3, 0.15);
                    world.spawnParticle(Particle.LAVA, loc, 15, 0.8, 0.5, 0.8, 0.1);
                }
            }
            case OBLIVION -> {
                // Obliteration II + Dark Matter II + Annihilation I
                if (isSmash) {
                    bonusDamage += 4.5;
                    world.spawnParticle(Particle.SQUID_INK, loc, 40, 1.4, 1.0, 1.4, 0.15);
                    world.spawnParticle(Particle.DRAGON_BREATH, loc, 35, 1.4, 0.5, 1.4, 0.08);
                }
            }
            case GODSLAYER -> {
                // Godly Strength III + Divine Wrath III + Celestial Fury III + Reality Breaker II
                if (isSmash) {
                    bonusDamage += 6.5;
                    world.spawnParticle(Particle.END_ROD, loc, 60, 2.0, 1.2, 2.0, 0.2);
                    world.spawnParticle(Particle.ENCHANT, loc, 50, 1.8, 1.5, 1.8, 0.5);
                    world.spawnParticle(Particle.FLASH, loc, 2);
                    world.strikeLightningEffect(target.getLocation());
                } else {
                    world.spawnParticle(Particle.ELECTRIC_SPARK, loc, 15, 0.5, 0.5, 0.5, 0.1);
                }
            }
        }

        event.setDamage(event.getDamage() + bonusDamage);
    }

    // Corruption I: Reduces healing by 50% for 5 seconds
    @EventHandler(ignoreCancelled = true)
    public void onHeal(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Long expiry = corruptedPlayers.get(player.getUniqueId());
        if (expiry != null && System.currentTimeMillis() < expiry) {
            event.setAmount(event.getAmount() * 0.5);
        }
    }

    private void healPlayer(Player player, double amount) {
        double max = Objects.requireNonNull(player.getAttribute(Attribute.MAX_HEALTH)).getValue();
        player.setHealth(Math.min(max, player.getHealth() + amount));
    }

    private void spawnIceCageVisual(Location base) {
        World w = base.getWorld();
        for (double y = 0; y <= 2.0; y += 0.5) {
            for (int angle = 0; angle < 360; angle += 45) {
                double rad = Math.toRadians(angle);
                double x = Math.cos(rad) * 0.9;
                double z = Math.sin(rad) * 0.9;
                w.spawnParticle(Particle.SNOWFLAKE, base.clone().add(x, y, z), 1, 0, 0, 0, 0);
            }
        }
    }

    // ==================== PASSIVE AURAS (VENOM & GODSLAYER) ====================

    private void startAuraTask() {
        new BukkitRunnable() {
            double angle = 0;

            @Override
            public void run() {
                angle += Math.PI / 8;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    ItemStack main = player.getInventory().getItemInMainHand();
                    MaceTier tier = getMaceTier(main);
                    if (tier == null) continue;

                    Location loc = player.getLocation();
                    World world = player.getWorld();

                    if (tier == MaceTier.VENOM) {
                        // Toxic Aura I
                        double x = Math.cos(angle) * 0.7;
                        double z = Math.sin(angle) * 0.7;
                        world.spawnParticle(Particle.HAPPY_VILLAGER, loc.clone().add(x, 1.0, z), 1, 0, 0, 0, 0);
                    } else if (tier == MaceTier.GODSLAYER) {
                        // Godly Presence I
                        double x1 = Math.cos(angle) * 1.0;
                        double z1 = Math.sin(angle) * 1.0;
                        double x2 = Math.cos(angle + Math.PI) * 1.0;
                        double z2 = Math.sin(angle + Math.PI) * 1.0;
                        world.spawnParticle(Particle.END_ROD, loc.clone().add(x1, 0.3, z1), 1, 0, 0, 0, 0);
                        world.spawnParticle(Particle.WAX_ON, loc.clone().add(x2, 1.6, z2), 1, 0, 0, 0, 0);
                    }
                }
            }
        }.runTaskTimer(this, 10L, 4L);
    }

    // ==================== COMMANDS (/mace) ====================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("mace")) return false;

        if (args.length == 0 || args[0].equalsIgnoreCase("info")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            syncPlayerMace(player);
            int kills = getKills(player.getUniqueId());
            MaceTier current = MaceTier.fromKills(kills);
            player.sendMessage(Component.text("=== MACE EVOLUTION ===", NamedTextColor.GOLD, TextDecoration.BOLD));
            player.sendMessage(Component.text("Current Mace: ", NamedTextColor.GRAY)
                    .append(Component.text(current.displayName, current.color)));
            player.sendMessage(Component.text("Total Kills: " + kills, NamedTextColor.YELLOW));
            if (current.tier < 10) {
                MaceTier next = MaceTier.fromTierNumber(current.tier + 1);
                player.sendMessage(Component.text("Next Evolution: " + next.displayName + " (" + (next.requiredKills - kills) + " kills needed)", NamedTextColor.AQUA));
            } else {
                player.sendMessage(Component.text("MAX EVOLUTION REACHED!", NamedTextColor.LIGHT_PURPLE));
            }
            return true;
        }

        if (!sender.hasPermission("maceevolution.admin") && !sender.isOp()) {
            sender.sendMessage(Component.text("No permission.", NamedTextColor.RED));
            return true;
        }

        if (args[0].equalsIgnoreCase("setkills") && args.length == 3) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
            try {
                int amount = Integer.parseInt(args[2]);
                setKills(target.getUniqueId(), amount);
                syncPlayerMace(target);
                sender.sendMessage(Component.text("Set " + target.getName() + "'s kills to " + amount + ".", NamedTextColor.GREEN));
            } catch (NumberFormatException e) {
                sender.sendMessage(Component.text("Invalid number.", NamedTextColor.RED));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("reset") && args.length == 2) {
            Player target = Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                return true;
            }
            setKills(target.getUniqueId(), 0);
            syncPlayerMace(target);
            sender.sendMessage(Component.text("Reset " + target.getName() + " to Rusted Mace.", NamedTextColor.YELLOW));
            return true;
        }

        sender.sendMessage(Component.text("Usage: /mace [info | setkills <player> <amount> | reset <player>]", NamedTextColor.RED));
        return true;
    }
}
