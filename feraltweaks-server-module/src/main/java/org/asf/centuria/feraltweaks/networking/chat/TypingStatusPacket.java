package org.asf.centuria.feraltweaks.networking.chat;

import org.asf.centuria.networking.chatserver.ChatClient;
import org.asf.centuria.networking.chatserver.networking.AbstractChatPacket;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.centuria.social.SocialManager;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.feraltweaks.networking.entities.TypingStatusSupported;

import com.google.gson.JsonObject;

/**
 * 
 * Typing status broadcast packet
 * 
 * @author Sky Swimmer
 *
 */
public class TypingStatusPacket extends AbstractChatPacket {

	public String playerID;
	public String playerDisplay;
	public String conversationId;

	@Override
	public String id() {
		return "feraltweaks.typing";
	}

	@Override
	public AbstractChatPacket instantiate() {
		return new TypingStatusPacket();
	}

	@Override
	public void build(JsonObject obj) {
		obj.addProperty("conversationId", conversationId);
		obj.addProperty("uuid", playerID);
		obj.addProperty("displayName", playerDisplay);
	}

	@Override
	public void parse(JsonObject obj) {
		conversationId = obj.get("conversationId").getAsString();
	}

	@Override
	public boolean handle(ChatClient client) {
		// Check supported
		if (client.getObject(TypingStatusSupported.class) != null) {
			// Update packet
			playerID = client.getPlayer().getAccountID();
			playerDisplay = client.getPlayer().getDisplayName();
			SocialManager socialManager = SocialManager.getInstance();
			Player playerInstLocal = client.getPlayer().getOnlinePlayerInstance();

			// Broadcast
			for (ChatClient cl : client.getServer().getClients()) {
				String permLevelR = "member";
				if (client.getPlayer().getSaveSharedInventory().containsItem("permissions")) {
					permLevelR = cl.getPlayer().getSaveSharedInventory().getItem("permissions").getAsJsonObject()
							.get("permissionLevel").getAsString();
				}

				// Check supported
				if (cl.getObject(TypingStatusSupported.class) != null && cl.isInRoom(conversationId)) {
					// Check states
					if (playerInstLocal != null && playerInstLocal.ghostMode
							&& !GameServer.hasPerm(permLevelR, "moderator"))
						continue; // Ghosting

					// Check block
					if (socialManager.getPlayerIsBlocked(client.getPlayer().getAccountID(),
							cl.getPlayer().getAccountID()))
						continue; // Do not sync to blocked players

					// Send
					cl.sendPacket(this);
				}
			}
		}
		return true;
	}
}
