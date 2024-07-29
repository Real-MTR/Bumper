package xyz.atomdev.bumper.disguise;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.SneakyThrows;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import xyz.atomdev.bumper.Bumper;
import xyz.atomdev.bumper.util.ReflectionUtil;
import xyz.atomdev.bumper.util.UUIDCache;

import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.*;
import java.util.concurrent.TimeUnit;

// Can't take credits for this class since I didn't create this!
// Made by someone i dont remember his name...
public class DisguiseManager {

    private final Bumper plugin;
    private final Map<String, Object> texturePropertyMap;

    public DisguiseManager(Bumper plugin) {
        this.plugin = plugin;
        this.texturePropertyMap = new HashMap<>();

        plugin.getPlugin().getServer().getScheduler().runTaskTimerAsynchronously(plugin.getPlugin(), texturePropertyMap::clear, 0L, TimeUnit.MINUTES.toMillis(10));
    }

    public void disguise(Player player, String name) {
        setPlayerSkin(player, name);
        setPlayerName(player, name);
    }

    public void disguise(Player player, String name, String skin) {
        setPlayerSkin(player, skin);
        setPlayerName(player, name);
    }

    @SneakyThrows
    public void setPlayerSkin(Player player, String name) {
        Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);
        Object connection = Arrays.stream(craftPlayer.getClass().getFields()).filter(field -> field.getType().getName().contains("PlayerConnection")).findFirst().orElse(null).get(craftPlayer);
        Object profile;

        try {
            profile = craftPlayer.getClass().getMethod("getProfile").invoke(craftPlayer);
        } catch (ReflectiveOperationException ignored) {
            profile = Arrays.stream(craftPlayer.getClass().getFields()).filter(field -> field.getType().getName().contains("GameProfile")).findFirst().orElse(null).get(craftPlayer);
        }

        Object gameProfile = profile;
        plugin.getPlugin().getServer().getScheduler().runTaskAsynchronously(plugin.getPlugin(), () -> {
            sendPlayerInfoPacket(craftPlayer, connection, "REMOVE_PLAYER");
            reloadTexturesProperty(gameProfile, name);
            sendPlayerInfoPacket(craftPlayer, connection, "ADD_PLAYER");
            refreshPlayer(player, craftPlayer, connection);
        });
    }

    @SneakyThrows
    public void setPlayerName(Player player, String name) {
        player.setDisplayName(name);
        player.setPlayerListName(name);

        Object craftPlayer = player.getClass().getMethod("getHandle").invoke(player);
        Object profile;

        try {
            profile = craftPlayer.getClass().getMethod("getProfile").invoke(craftPlayer);
        } catch (ReflectiveOperationException ignored) {
            profile = Arrays.stream(craftPlayer.getClass().getFields()).filter(field -> field.getType().getName().contains("GameProfile")).findFirst().orElse(null).get(craftPlayer);
        }

        Field nameField = profile.getClass().getDeclaredField("name");
        nameField.setAccessible(true);
        nameField.set(profile, name);

        refreshPlayer(player, craftPlayer, Arrays.stream(craftPlayer.getClass().getFields()).filter(field -> field.getType().getName().contains("PlayerConnection")).findFirst().orElse(null).get(craftPlayer));
    }

    private void refreshPlayer(Player player, Object craftPlayer, Object connection) {
        player.getServer().getScheduler().runTask(plugin.getPlugin(), () -> {
            plugin.getPlugin().getServer().getOnlinePlayers().forEach(onlinePlayer -> {
                if (onlinePlayer.canSee(player)) {
                    onlinePlayer.hidePlayer(player);
                    onlinePlayer.showPlayer(player);
                }
            });
        });

        plugin.getPlugin().getServer().getScheduler().runTask(plugin.getPlugin(), () -> {
            sendRespawnPacket(player, craftPlayer, connection);

            player.setOp(player.isOp());

            player.setHealth(player.getHealth());
            player.setFoodLevel(player.getFoodLevel());
            player.setExp(player.getExp());
            player.setPlayerTime(player.getPlayerTime(), false);

            player.getInventory().setHeldItemSlot(player.getInventory().getHeldItemSlot());
            player.getInventory().setContents(player.getInventory().getContents());
            player.getInventory().setArmorContents(player.getInventory().getArmorContents());

            player.setAllowFlight(player.getAllowFlight());
            player.setFlying(player.isFlying());

            Collection<PotionEffect> activeEffects = player.getActivePotionEffects();

            for (PotionEffect effect : activeEffects) {
                player.removePotionEffect(effect.getType());
            }

            for (PotionEffect effect : activeEffects) {
                player.addPotionEffect(effect);
            }

            Location originalLocation = player.getLocation();

            Location location = player.getLocation();
            location.setY(location.getY() + 1000);
            player.teleport(location);

            plugin.getPlugin().getServer().getScheduler().runTaskLater(plugin.getPlugin(), () -> {
                player.teleport(originalLocation);
            }, 1L);
        });
    }

    @SneakyThrows
    private void reloadTexturesProperty(Object profile, String skin) {
        Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
        properties.getClass().getMethod("removeAll", Object.class).invoke(properties, "textures");
        properties.getClass().getMethod("put", Object.class, Object.class).invoke(properties, "textures", getSkinTextureProperty(skin));
    }

    private Object getSkinTextureProperty(String name) {
        if (!texturePropertyMap.containsKey(name)) {
            try {
                UUID uuid = UUIDCache.synchronouslyRetrieveUUID(name);

                URL sessionServer = new URL("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false");
                InputStreamReader sessionServerReader = new InputStreamReader(sessionServer.openStream());
                JsonObject textureProperty = new JsonParser().parse(sessionServerReader).getAsJsonObject().get("properties").getAsJsonArray().get(0).getAsJsonObject();

                String texture = textureProperty.get("value").getAsString();
                String signature = textureProperty.get("signature").getAsString();

                Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
                texturePropertyMap.put(name, propertyClass.getConstructor(String.class, String.class, String.class).newInstance("textures", texture, signature));
            } catch (ReflectiveOperationException | IOException e) {
                return null;
            }
        }

        return texturePropertyMap.get(name);
    }

    @SneakyThrows
    private void sendPlayerInfoPacket(Object craftPlayer, Object connection, String action) {
        Class<?> entityPlayer;

        try {
            entityPlayer = ReflectionUtil.getNMSClass("EntityPlayer");
        } catch (ReflectiveOperationException e) {
            entityPlayer = Class.forName("net.minecraft.server.level.EntityPlayer");
        }

        Class<?> packetClass = ReflectionUtil.getNMSClass("Packet");
        Class<?> packetPlayOutPlayerInfoClass = ReflectionUtil.getNMSClass("PacketPlayOutPlayerInfo");
        Class enumPlayerInfoActionClass = Arrays.stream(packetPlayOutPlayerInfoClass.getClasses())
                .filter(Class::isEnum)
                .findFirst()
                .orElse(null);

        Enum<?> enumPlayerInfoAction = Enum.valueOf(enumPlayerInfoActionClass, action);

        Object entityPlayerArray = Array.newInstance(entityPlayer, 1);
        Array.set(entityPlayerArray, 0, craftPlayer);

        Object packetPlayOutPlayerInfo = Arrays.stream(packetPlayOutPlayerInfoClass.getConstructors())
                .filter(Constructor::isVarArgs)
                .findFirst()
                .orElse(null)
                .newInstance(enumPlayerInfoAction, entityPlayerArray);

        connection.getClass().getMethod("sendPacket", packetClass).invoke(connection, packetPlayOutPlayerInfo);
    }

    @SneakyThrows
    private void sendRespawnPacket(Player player, Object craftPlayer, Object connection) {
        Class<?> packetClass = ReflectionUtil.getNMSClass("Packet");
        Class<?> packetPlayOutRespawnClass = ReflectionUtil.getNMSClass("PacketPlayOutRespawn");

        Object worldClass = craftPlayer.getClass().getMethod("getWorld").invoke(craftPlayer);
        Object manager = worldClass.getClass().getMethod("getDimensionManager").invoke(worldClass);
        Object key = worldClass.getClass().getMethod("getDimensionKey").invoke(worldClass);

        Object playerInteractManager = craftPlayer.getClass().getField("playerInteractManager").get(craftPlayer);
        Object gamemode = playerInteractManager.getClass().getMethod("getGameMode").invoke(playerInteractManager);

        Object packetPlayOutRespawn = packetPlayOutRespawnClass
                .getConstructor(manager.getClass(), key.getClass(), long.class, gamemode.getClass(), gamemode.getClass(), boolean.class, boolean.class, boolean.class)
                .newInstance(manager, key, player.getWorld().getSeed(), gamemode, gamemode, true, true, true);

        connection.getClass().getMethod("sendPacket", packetClass).invoke(connection, packetPlayOutRespawn);
    }

}