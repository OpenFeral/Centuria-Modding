package org.asf.centuria.feraltweaks.managers;

import java.util.ArrayList;
import java.util.HashMap;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.feraltweaks.FeralTweaksClientObject;
import org.asf.centuria.feraltweaks.networking.game.PlayerDisplayNameUpdatePacket;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.eventbus.EventListener;
import org.asf.centuria.modules.eventbus.IEventReceiver;
import org.asf.centuria.modules.events.accounts.AccountDisplayNameChangedEvent;
import org.asf.centuria.networking.gameserver.GameServer;

public class PlayerNameManager implements IEventReceiver {

	private boolean inited;
	private HashMap<String, String> playerNames = new HashMap<String, String>();
	private static PlayerNameManager instance = new PlayerNameManager();

	/**
	 * Retrieves the player name manager instance
	 * 
	 * @return PlayerNameManager instance
	 */
	public static PlayerNameManager getInstance() {
		return instance;
	}

	/**
	 * Initializes the player name manager
	 */
	public void initPlayerNameManager() {
		if (inited)
			throw new IllegalStateException("Already initialized");
		inited = true;

		// Start username refresher
		Thread th = new Thread(() -> {
			while (true) {
				// Go through users
				ArrayList<String> users = new ArrayList<String>();
				if (Centuria.gameServer != null) {
					for (Player plr : Centuria.gameServer.getPlayers()) {
						// Add to list
						users.add(plr.account.getAccountID());

						// Synchronize player names that updated
						synchronized (playerNames) {
							// Check if the current player's name has changed
							String nm = GameServer.getPlayerNameWithPrefix(plr.account);
							if (!playerNames.containsKey(plr.account.getAccountID())
									|| !playerNames.get(plr.account.getAccountID()).equals(nm)) {
								// Name changed, update all players about this account
								updateAllPlayers(plr.account);
								playerNames.put(plr.account.getAccountID(), nm);
							}
						}
					}
				}

				// Remove players no longer ingame from the cache
				synchronized (playerNames) {
					String[] names = playerNames.keySet().toArray(t -> new String[t]);
					for (String id : names) {
						if (!users.contains(id))
							playerNames.remove(id);
					}
				}
				try {
					Thread.sleep(30000);
				} catch (InterruptedException e) {
				}
			}
		}, "User update handler (FeralTweaks)");
		th.setDaemon(true);
		th.start();

		// Subscribe events
		EventBus.getInstance().addAllEventsFromReceiver(this);
	}

	@EventListener
	public void updatePlayername(AccountDisplayNameChangedEvent event) {
		// Update name
		CenturiaAccount account = event.getAccount();
		if (account.getOnlinePlayerInstance() != null) {
			// Save
			synchronized (playerNames) {
				playerNames.put(account.getAccountID(), account.getDisplayName());
			}
		}

		// Send update
		updateAllPlayers(event.getAccount());
	}

	/**
	 * Sends a username update to all players of the given user
	 * 
	 * @param account Player to send a update of
	 */
	public void updateAllPlayers(CenturiaAccount account) {
		// Send username update packet to all players
		PlayerDisplayNameUpdatePacket pkt = new PlayerDisplayNameUpdatePacket();
		pkt.id = account.getAccountID();
		pkt.name = GameServer.getPlayerNameWithPrefix(account);
		for (Player plr : Centuria.gameServer.getPlayers()) {
			if (plr != null) {
				if (plr.getObject(FeralTweaksClientObject.class) != null
						&& plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
					// Send
					plr.client.sendPacket(pkt);
				}
			}
		}

		// Save
		synchronized (playerNames) {
			if (playerNames.containsKey(pkt.id))
				playerNames.put(pkt.id, pkt.name);
		}
	}

	/**
	 * Sends a displayname update of all ingame players to a specific recipient
	 * 
	 * @param plr Recipient to update
	 */
	public void syncToPlayer(Player plr) {
		// Update this user
		for (Player plr2 : Centuria.gameServer.getPlayers()) {
			if (plr2 != null) {
				if (plr2.getObject(FeralTweaksClientObject.class) != null
						&& plr2.getObject(FeralTweaksClientObject.class).isEnabled()) {
					// Send
					PlayerDisplayNameUpdatePacket pkt = new PlayerDisplayNameUpdatePacket();
					pkt.id = plr2.account.getAccountID();
					pkt.name = GameServer.getPlayerNameWithPrefix(plr2.account);
					plr.client.sendPacket(pkt);
				}
			}
		}
	}

}
