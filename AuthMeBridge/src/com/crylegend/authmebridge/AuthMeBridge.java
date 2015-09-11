package com.crylegend.authmebridge;

import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import com.google.common.io.ByteArrayDataInput;
import com.google.common.io.ByteStreams;

import fr.xephi.authme.api.API;

public class AuthMeBridge extends JavaPlugin implements PluginMessageListener {
	Logger log = Logger.getLogger("Minecraft");
	String prefix = "[AuthMeBridge] ";
	String incomingChannel = "BAuthMeBridge";
	String outgoingChannel = "AuthMeBridge";

	public void onEnable() {
		log.info(prefix + "Hello world");
		if (!getServer().getPluginManager().isPluginEnabled("AuthMe")) {
			log.info(prefix + "AuthMe not found, disabling");
			getServer().getPluginManager().disablePlugin(this);
		}
		getServer().getPluginManager().registerEvents(new AuthMeBridgeListener(this), this);
		getServer().getMessenger().registerIncomingPluginChannel(this, incomingChannel, this);
		getServer().getMessenger().registerOutgoingPluginChannel(this, outgoingChannel);

	}

	public void onDisable() {
		log.info(prefix + "Goodbye world");
	}

	@Override
	public void onPluginMessageReceived(String channel, Player p, byte[] message) {
		if (channel.equals(incomingChannel)) {
			ByteArrayDataInput in = ByteStreams.newDataInput(message);
			String subchannel = in.readUTF();

			if (subchannel.equals("AutoLogin")) {
				Player player = Bukkit.getPlayer(in.readUTF());

				if (player != null) {
					API.forceLogin(player);
				}
			}
		}
	}
}
