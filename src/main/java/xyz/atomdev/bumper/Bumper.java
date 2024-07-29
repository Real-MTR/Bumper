package xyz.atomdev.bumper;

import lombok.Getter;
import org.bukkit.plugin.java.JavaPlugin;
import xyz.atomdev.bumper.disguise.DisguiseManager;

@Getter
public final class Bumper {

    private final JavaPlugin plugin;
    private final DisguiseManager disguiseManager;

    public Bumper(JavaPlugin plugin) {
        this.plugin = plugin;
        this.disguiseManager = new DisguiseManager(this);
    }
}