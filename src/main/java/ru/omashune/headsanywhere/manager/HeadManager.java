package ru.omashune.headsanywhere.manager;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Iterables;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import lombok.AccessLevel;
import lombok.SneakyThrows;
import lombok.experimental.FieldDefaults;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.omashune.headsanywhere.util.HeadUtil;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@FieldDefaults(level = AccessLevel.PRIVATE)
public class HeadManager {

    final char[] characters = new char[] { 'ϧ', 'Ϩ', 'ϩ', 'Ϫ', 'ϫ', 'Ϭ', 'ϭ', 'Ϯ' };
    final Gson gson = new Gson();

    final String headsProvider;
    final LoadingCache<String, Optional<BaseComponent[]>> cache;

    boolean hasPlayerProfile;
    String getProfileMethodName;

    public HeadManager(int cacheTime, String headProvider) {
        this.cache = CacheBuilder.newBuilder().expireAfterWrite(cacheTime, TimeUnit.SECONDS)
                .build(CacheLoader.from(this::loadHead));
        this.headsProvider = headProvider;

        try {
            hasPlayerProfile = OfflinePlayer.class.getMethod("getPlayerProfile") != null;
        } catch (NoSuchMethodException ignored) {
            hasPlayerProfile = false;

            String version = Bukkit.getServer().getClass().getName();
            try {
                Class<?> clazz = Class.forName(
                        version.substring(0, version.length() - "CraftServer".length()) + "entity.CraftPlayer");
                for (Method method : clazz.getMethods()) {
                    getProfileMethodName = method.getName();
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @SneakyThrows
    public BaseComponent[] getPlayerHead(Player player) {
        return cache.get(player.getUniqueId().toString()).orElse(null);
    }

    public void refreshPlayerHead(Player player) {
        cache.refresh(player.getUniqueId().toString());
    }

    public UUID fetchUuidByName(String playerName) {
        try {
            URI uri = new URI("https://api.mojang.com/users/profiles/minecraft/" + playerName);
            URL url = uri.toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (connection.getResponseCode() != 200)
                return null;

            InputStreamReader reader = new InputStreamReader(connection.getInputStream());
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            String id = json.get("id").getAsString();
            reader.close();

            // Mojang devuelve UUID sin guiones
            return UUID.fromString(
                    id.replaceFirst(
                            "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                            "$1-$2-$3-$4-$5"));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private Optional<BaseComponent[]> loadHead(String playerName) {
        try {
            // Intentar obtener Player online
            Player player = Bukkit.getPlayer(playerName);
            String skinUrl = null;

            if (player != null) {
                skinUrl = getSkinUrl(player); // Jugador online
            } else {
                // Jugador offline → obtener skin por UUID o nombre
                UUID uuid = fetchUuidByName(playerName); // método que consulta Mojang API o cache
                if (uuid != null) {
                    skinUrl = fetchSkinUrlByUuid(uuid);
                }
            }

            boolean isSpigotSkinUrl = skinUrl != null;
            String finalUrl = isSpigotSkinUrl ? skinUrl : String.format(headsProvider, playerName);
            URI uri = new URI(finalUrl);
            URL url = uri.toURL();

            BufferedImage headImage = isSpigotSkinUrl ? HeadUtil.getHead(url) : ImageIO.read(url);
            ComponentBuilder builder = new ComponentBuilder();

            for (int x = 0; x < headImage.getWidth(); x++) {
                for (int y = 0; y < headImage.getHeight(); y++) {
                    builder.append(String.valueOf(characters[y]))
                            .color(ChatColor.of(new Color(headImage.getRGB(x, y))));
                }
            }

            return Optional.of(builder.create());
        } catch (IOException | URISyntaxException e) {
            e.printStackTrace();
            return Optional.empty();
        }
    }

    public String fetchSkinUrlByUuid(UUID uuid) {
        try {
            URI uri = new URI(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid.toString().replace("-", ""));
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            if (connection.getResponseCode() != 200)
                return null;

            InputStreamReader reader = new InputStreamReader(connection.getInputStream());
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            reader.close();

            JsonArray properties = json.getAsJsonArray("properties");
            for (int i = 0; i < properties.size(); i++) {
                JsonObject prop = properties.get(i).getAsJsonObject();
                if (!"textures".equals(prop.get("name").getAsString()))
                    continue;

                String encoded = prop.get("value").getAsString();
                String decoded = new String(Base64.getDecoder().decode(encoded));

                JsonObject textures = JsonParser.parseString(decoded).getAsJsonObject();
                JsonObject skin = textures.getAsJsonObject("textures").getAsJsonObject("SKIN");
                return skin.get("url").getAsString();
            }

            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String getSkinUrl(Player player) {
        if (player == null)
            return null;

        if (hasPlayerProfile) {
            URL skinURL = player.getPlayerProfile().getTextures().getSkin();
            return skinURL == null ? null : skinURL.toString();
        }

        try {
            Object entityPlayer = player.getClass().getMethod("getHandle").invoke(player);
            GameProfile profile = (GameProfile) entityPlayer.getClass().getMethod(getProfileMethodName)
                    .invoke(entityPlayer);
            if (profile == null)
                return null;

            Property textureProperty = Iterables.getFirst(profile.getProperties().get("textures"), null);
            if (textureProperty == null)
                return null;

            return gson.fromJson(new String(
                    Base64.getDecoder().decode(textureProperty.getValue())), JsonObject.class)
                    .getAsJsonObject("textures")
                    .getAsJsonObject("SKIN")
                    .get("url").getAsString();
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException e) {
            return null;
        }
    }

}