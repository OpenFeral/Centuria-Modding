package org.asf.centuria.feraltweaks.handlers;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.TimeZone;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.feraltweaks.FeralTweaksClientObject;
import org.asf.centuria.feraltweaks.managers.ScheduledMaintenanceManager;
import org.asf.centuria.feraltweaks.networking.game.ErrorPopupPacket;
import org.asf.centuria.feraltweaks.networking.game.NotificationPacket;
import org.asf.centuria.feraltweaks.networking.game.OkPopupPacket;
import org.asf.centuria.feraltweaks.networking.game.YesNoPopupPacket;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.eventbus.EventListener;
import org.asf.centuria.modules.eventbus.IEventReceiver;
import org.asf.centuria.modules.events.accounts.MiscModerationEvent;
import org.asf.centuria.modules.events.chatcommands.ChatCommandEvent;
import org.asf.centuria.modules.events.chatcommands.ModuleCommandSyntaxListEvent;

public class CommandHandlers implements IEventReceiver {

	@EventListener
	public void registerCommands(ModuleCommandSyntaxListEvent event) {
		// Command registration
		if (event.hasPermission("moderator")) {
			// Warnings, announcements, etc
			event.addCommandSyntaxMessage("announce \"<message>\" [\"<title>\"]");
			event.addCommandSyntaxMessage("warn \"<player>\" \"<message>\" [\"<title>\"]");
			event.addCommandSyntaxMessage(
					"request \"<player>\" \"<message>\" [\"<title>\"] [\"<yes-button>\"] [\"<no-button>\"]");
			event.addCommandSyntaxMessage("notify \"<player>\" \"<message>\"");

			// Maintenance
			if (event.hasPermission("admin")) {
				event.addCommandSyntaxMessage("startmaintenancetimer <timer length in minutes>");
				event.addCommandSyntaxMessage("schedulemaintenance \"<MM/dd/yyyy HH:mm>\" (expects date/time in UTC)");
				event.addCommandSyntaxMessage("cancelmaintenance");
			}
		}
	}

	@EventListener
	public void runCommand(ChatCommandEvent event) {
		if (event.hasPermission("moderator")) {
			switch (event.getCommandID().toLowerCase()) {

			case "startmaintenancetimer": {
				if (event.hasPermission("admin")) {
					// Maintenance with timer

					// Set handled
					event.setHandled();

					// Check arguments
					if (event.getCommandArguments().length == 0) {
						event.respond("Error: missing argument: timer length");
						return;
					} else if (!event.getCommandArguments()[0].matches("^[0-9]+$")) {
						event.respond("Error: invalid argument: timer length: not a valid number");
						return;
					}

					// Check
					if (!ScheduledMaintenanceManager.maintenanceTimerStarted
							|| ScheduledMaintenanceManager.cancelMaintenance) {
						// Schedule
						ScheduledMaintenanceManager.scheduleMaintenance(event.getAccount(), System.currentTimeMillis()
								+ (Integer.parseInt(event.getCommandArguments()[0]) * 60 * 1000));
						event.respond("Maintenance scheduled");
					} else {
						// Error
						event.respond("Error: there is a maintenance scheduled already");
						return;
					}

					return;
				}
			}

			case "schedulemaintenance": {
				if (event.hasPermission("admin")) {
					// Maintenance with timer

					// Set handled
					event.setHandled();

					// Check arguments
					if (event.getCommandArguments().length == 0) {
						event.respond("Error: missing argument: maintenance start date and time (UTC)");
						return;
					}

					// Parse
					SimpleDateFormat fmt = new SimpleDateFormat("MM/dd/yyyy HH:mm");
					fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
					Date start;
					try {
						start = fmt.parse(event.getCommandArguments()[0]);
					} catch (Exception e) {
						event.respond(
								"Error: invalid argument: date/time: not a valid date/time value (expected a value formatted 'MM/dd/yyyy HH:mm')");
						return;
					}

					// Check
					if (!ScheduledMaintenanceManager.maintenanceTimerStarted
							|| ScheduledMaintenanceManager.cancelMaintenance) {
						// Schedule
						ScheduledMaintenanceManager.scheduleMaintenance(event.getAccount(), start.getTime());
						event.respond("Maintenance scheduled");
					} else {
						// Error
						event.respond("Error: there is a maintenance scheduled already");
						return;
					}

					return;
				}
			}

			case "cancelmaintenance": {
				if (event.hasPermission("admin")) {
					// Canceling maintenance

					// Set handled
					event.setHandled();

					// Check
					if (ScheduledMaintenanceManager.maintenanceTimerStarted
							&& !ScheduledMaintenanceManager.cancelMaintenance) {
						event.respond("Maintenance cancelled");
						ScheduledMaintenanceManager.cancelMaintenance = true;
					} else {
						event.respond("Error: no maintenance scheduled");
						return;
					}

					// Log
					HashMap<String, String> details = new HashMap<String, String>();
					EventBus.getInstance()
							.dispatchEvent(new MiscModerationEvent("cancelmaintenance", "Server maintenance cancelled",
									details, event.getClient().getPlayer().getAccountID(), null, false));

					return;
				}
			}

			case "announce": {
				// Handle announcement commands

				event.setHandled();
				if (event.getCommandArguments().length == 0) {
					event.respond("Error: missing argument: message");
					return;
				}

				// Send announcement
				for (Player plr : Centuria.gameServer.getPlayers()) {
					if (plr != null) {
						if (plr.getObject(FeralTweaksClientObject.class) == null
								|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
							// Send as DM
							Centuria.systemMessage(plr, "Server announcement!\n" + event.getCommandArguments()[0],
									true);
						} else {
							// Show popup
							OkPopupPacket pkt = new OkPopupPacket();
							pkt.title = "Server Announcement";
							pkt.message = event.getCommandArguments()[0];
							if (event.getCommandArguments().length >= 2)
								pkt.title = event.getCommandArguments()[1];
							plr.client.sendPacket(pkt);
						}
					}
				}
				event.respond(
						"Sent announcement, note that for some this might be sent as a DM as not every client supports FeralTweaks.");

				// Log
				HashMap<String, String> details = new HashMap<String, String>();
				details.put("Message", event.getCommandArguments()[0]);
				EventBus.getInstance().dispatchEvent(new MiscModerationEvent("announce", "Made a server announcement",
						details, event.getClient().getPlayer().getAccountID(), null, false));

				break;
			}

			case "request": {
				// Handle request commands

				event.setHandled();
				if (event.getCommandArguments().length == 0) {
					event.respond("Error: missing argument: player");
					return;
				}
				if (event.getCommandArguments().length == 1) {
					event.respond("Error: missing argument: message");
					return;
				}

				// Find player
				String uuid = AccountManager.getInstance().getUserByDisplayName(event.getCommandArguments()[0]);
				if (uuid == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
				if (acc == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				Player plr = acc.getOnlinePlayerInstance();
				if (plr == null) {
					// Player offline
					event.respond(
							"Player is offline, cannot send popups to them unless they are ingame, please use DMs instead.");
					return;
				}

				// Check support
				if (plr.getObject(FeralTweaksClientObject.class) == null
						|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
					// Send as DM
					event.respond(
							"Error: the given player has no client mods that support this command, cannot send a popup.");
					return;
				} else {
				}

				// Send popup
				YesNoPopupPacket pkt = new YesNoPopupPacket();
				pkt.title = event.getClient().getPlayer().getDisplayName() + " asks";
				pkt.message = event.getCommandArguments()[1];
				pkt.yesButton = "Yes";
				pkt.noButton = "No";
				pkt.id = "playersent/" + event.getAccount().getAccountID();
				if (event.getCommandArguments().length >= 3)
					pkt.title = event.getCommandArguments()[2];
				if (event.getCommandArguments().length >= 4)
					pkt.yesButton = event.getCommandArguments()[3];
				if (event.getCommandArguments().length >= 5)
					pkt.noButton = event.getCommandArguments()[4];
				plr.client.sendPacket(pkt);
				event.respond("Request sent.");

				break;
			}

			case "warn": {
				// Handle warning commands

				event.setHandled();
				if (event.getCommandArguments().length == 0) {
					event.respond("Error: missing argument: player");
					return;
				}
				if (event.getCommandArguments().length == 1) {
					event.respond("Error: missing argument: message");
					return;
				}

				// Find player
				String uuid = AccountManager.getInstance().getUserByDisplayName(event.getCommandArguments()[0]);
				if (uuid == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
				if (acc == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				Player plr = acc.getOnlinePlayerInstance();
				if (plr == null) {
					// Player offline
					event.respond(
							"Player is offline, cannot warn them unless they are ingame, please use DMs instead.");
					return;
				}

				// Warn
				if (plr.getObject(FeralTweaksClientObject.class) == null
						|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
					// Send as DM
					Centuria.systemMessage(plr, "You have been warned!\n" + event.getCommandArguments()[1], true);
					event.respond(
							"Sent DM as system as the user does not have feraltweaks active, cannot display popups without it.");
				} else {
					// Show popup
					ErrorPopupPacket pkt = new ErrorPopupPacket();
					pkt.title = "You have been warned!";
					pkt.message = event.getCommandArguments()[1];
					if (event.getCommandArguments().length >= 3)
						pkt.title = event.getCommandArguments()[2];
					plr.client.sendPacket(pkt);
					event.respond("Warning sent.");
				}

				// Log
				HashMap<String, String> details = new HashMap<String, String>();
				details.put("Message", event.getCommandArguments()[1]);
				EventBus.getInstance().dispatchEvent(new MiscModerationEvent("request", "Issued a warning", details,
						event.getClient().getPlayer().getAccountID(), plr.account));

				break;
			}

			case "notify": {
				// Handle warning commands

				event.setHandled();
				if (event.getCommandArguments().length == 0) {
					event.respond("Error: missing argument: player");
					return;
				}
				if (event.getCommandArguments().length == 1) {
					event.respond("Error: missing argument: message");
					return;
				}

				// Find player
				String uuid = AccountManager.getInstance().getUserByDisplayName(event.getCommandArguments()[0]);
				if (uuid == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				CenturiaAccount acc = AccountManager.getInstance().getAccount(uuid);
				if (acc == null) {
					// Player not found
					event.respond("Specified account could not be located");
					return;
				}
				Player plr = acc.getOnlinePlayerInstance();
				if (plr == null) {
					// Player offline
					event.respond(
							"Player is offline, cannot notify them unless they are ingame, please use DMs instead.");
					return;
				}

				// Check support
				if (plr.getObject(FeralTweaksClientObject.class) == null
						|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
					// Send as DM
					event.respond(
							"Error: the given player has no client mods that support this command, cannot send a popup.");
					return;
				} else {
				}

				// Show popup
				NotificationPacket pkt = new NotificationPacket();
				pkt.message = event.getCommandArguments()[1];
				plr.client.sendPacket(pkt);
				event.respond("Notification sent.");

				break;
			}
			}
		}
	}
}
