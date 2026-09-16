package dev.tggamesyt.szakmai;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class Szakmai extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        for (ItemStack item : event.getPlayer().getInventory().getContents()) {
            applyTooltip(item);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        applyTooltip(event.getCurrentItem());
        applyTooltip(event.getCursor());
    }

    private void applyTooltip(ItemStack item) {
        if (item == null || item.getType().isAir()) return;

        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        List<Component> lore = meta.hasLore() ? meta.lore() : new ArrayList<>();
        if (lore == null) lore = new ArrayList<>();

        Component customLine = Component.text("ligma: " + item.getType().name(), NamedTextColor.AQUA);

        if (!lore.contains(customLine)) {
            lore.add(customLine);
            meta.lore(lore);
            item.setItemMeta(meta);
        }
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
