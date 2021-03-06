package fr.xephi.authmevelocity.listeners;

import ch.jalu.configme.SettingsManager;
import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.PlayerChatEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.xephi.authmevelocity.AuthMeVelocity;
import fr.xephi.authmevelocity.config.SettingsDependent;
import fr.xephi.authmevelocity.config.VelocityConfigProperties;
import fr.xephi.authmevelocity.data.AuthPlayer;
import fr.xephi.authmevelocity.services.AuthPlayerManager;
import net.kyori.text.TextComponent;
import net.kyori.text.format.TextColor;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

public class VelocityPlayerListener implements SettingsDependent {

    // Services
    private final AuthPlayerManager authPlayerManager;

    // Settings
    private boolean isAutoLoginEnabled;
    private boolean isServerSwitchRequiresAuth;
    private String requiresAuthKickMessage;
    private List<String> authServers;
    private boolean allServersAreAuthServers;
    private boolean isCommandsRequireAuth;
    private List<String> commandWhitelist;
    private boolean chatRequiresAuth;

    @Inject
    public VelocityPlayerListener(final SettingsManager settings, final AuthPlayerManager authPlayerManager) {
        this.authPlayerManager = authPlayerManager;
        reload(settings);
    }

    @Override
    public void reload(final SettingsManager settings) {
        isAutoLoginEnabled = settings.getProperty(VelocityConfigProperties.AUTOLOGIN);
        isServerSwitchRequiresAuth = settings.getProperty(VelocityConfigProperties.SERVER_SWITCH_REQUIRES_AUTH);
        requiresAuthKickMessage = settings.getProperty(VelocityConfigProperties.SERVER_SWITCH_KICK_MESSAGE);
        authServers = new ArrayList<>();
        for (final String server : settings.getProperty(VelocityConfigProperties.AUTH_SERVERS)) {
            authServers.add(server.toLowerCase());
        }
        allServersAreAuthServers = settings.getProperty(VelocityConfigProperties.ALL_SERVERS_ARE_AUTH_SERVERS);
        isCommandsRequireAuth = settings.getProperty(VelocityConfigProperties.COMMANDS_REQUIRE_AUTH);
        commandWhitelist = new ArrayList<>();
        for (final String command : settings.getProperty(VelocityConfigProperties.COMMANDS_WHITELIST)) {
            // Substring so the AuthMeBungee config is usable without making modifications
            commandWhitelist.add(command.toLowerCase().substring(1));
        }
        chatRequiresAuth = settings.getProperty(VelocityConfigProperties.CHAT_REQUIRES_AUTH);
    }

    @Subscribe
    public void onPlayerJoin(final PostLoginEvent event) {
        // Register player in our list
        authPlayerManager.addAuthPlayer(event.getPlayer());
    }

    @Subscribe
    public void onPlayerDisconnect(final DisconnectEvent event) {
        // Remove player from out list
        authPlayerManager.removeAuthPlayer(event.getPlayer());
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onCommand(final CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player) || !event.getResult().isAllowed() || !isCommandsRequireAuth) {
            return;
        }

        final Player player = (Player) event.getCommandSource();

        // Filter only unauthenticated players
        final AuthPlayer authPlayer = authPlayerManager.getAuthPlayer(player);
        if (authPlayer != null && authPlayer.isLogged()) {
            return;
        }
        // Only in auth servers
        if (!player.getCurrentServer().isPresent()
            || !isAuthServer(player.getCurrentServer().get().getServerInfo())) {
            return;
        }
        // Check if command is whitelisted command
        if (commandWhitelist.contains(event.getCommand().split(" ")[0].toLowerCase())) {
            return;
        }

        event.setResult(CommandExecuteEvent.CommandResult.denied());
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onCommand(final PlayerChatEvent event) {
        if (!event.getResult().isAllowed() ||  !chatRequiresAuth) {
            return;
        }

        final Player player = event.getPlayer();

        // Filter only unauthenticated players
        final AuthPlayer authPlayer = authPlayerManager.getAuthPlayer(player);
        if (authPlayer != null && authPlayer.isLogged()) {
            return;
        }
        // Only in auth servers
        if (!player.getCurrentServer().isPresent()
            || !isAuthServer(player.getCurrentServer().get().getServerInfo())) {
            return;
        }

        event.setResult(PlayerChatEvent.ChatResult.denied());
    }

    private boolean isAuthServer(ServerInfo serverInfo) {
        return allServersAreAuthServers || authServers.contains(serverInfo.getName().toLowerCase());
    }

    @Subscribe(order = PostOrder.EARLY)
    public void onPlayerConnectedToServer(final ServerConnectedEvent event) {
        final Player player = event.getPlayer();
        final AuthPlayer authPlayer = authPlayerManager.getAuthPlayer(player);
        final boolean isAuthenticated = authPlayer != null && authPlayer.isLogged();

        if (isAuthenticated && isAuthServer(event.getServer().getServerInfo())) {
            // If AutoLogin enabled, notify the server
            if (isAutoLoginEnabled) {
                final ByteArrayDataOutput out = ByteStreams.newDataOutput();
                out.writeUTF("AuthMe.v2");
                out.writeUTF("perform.login");
                out.writeUTF(event.getPlayer().getUsername());
                byte[] data = out.toByteArray();
                event.getServer().sendPluginMessage(AuthMeVelocity.LEGACY_AUTHME, data);
                event.getServer().sendPluginMessage(AuthMeVelocity.AUTHME_CHANNEL, data);
            }
        }
    }

    @Subscribe(order = PostOrder.LATE)
    public void onPlayerConnectingToServer(final ServerPreConnectEvent event) {
        if (!event.getResult().isAllowed()) {
            return;
        }

        final Player player = event.getPlayer();
        final AuthPlayer authPlayer = authPlayerManager.getAuthPlayer(player);
        final boolean isAuthenticated = authPlayer != null && authPlayer.isLogged();

        // Skip logged users
        if (isAuthenticated) {
            return;
        }

        // Only check non auth servers
        if (isAuthServer(event.getOriginalServer().getServerInfo())) {
            return;
        }

        // If the player is not logged in and serverSwitchRequiresAuth is enabled, cancel the connection
        if (isServerSwitchRequiresAuth) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());

            final TextComponent reasonMessage = TextComponent.of(requiresAuthKickMessage).color(TextColor.RED);

            if (player.getCurrentServer().isPresent()) {
                player.sendMessage(reasonMessage);
            } else {
                player.disconnect(reasonMessage);
            }
        }
    }
}
