package org.asf.centuria.feraltweaks.managers;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.stream.Stream;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.feraltweaks.FeralTweaksClientObject;
import org.asf.centuria.feraltweaks.networking.game.OkPopupPacket;
import org.asf.centuria.modules.eventbus.EventBus;
import org.asf.centuria.modules.events.accounts.AccountDisconnectEvent;
import org.asf.centuria.modules.events.accounts.MiscModerationEvent;
import org.asf.centuria.modules.events.accounts.AccountDisconnectEvent.DisconnectType;
import org.asf.centuria.modules.events.maintenance.MaintenanceStartEvent;
import org.asf.centuria.networking.chatserver.ChatClient;
import org.asf.centuria.networking.gameserver.GameServer;

public class ScheduledMaintenanceManager {

	public static long maintenanceStartTime;
	public static boolean maintenanceTimerStarted;
	public static boolean cancelMaintenance;

	/**
	 * Schedules maintenance
	 * 
	 * @param startTime Maintenance start time
	 */
	public static void scheduleMaintenance(long startTime) {
		scheduleMaintenance(null, startTime);
	}

	/**
	 * Schedules maintenance
	 * 
	 * @param issuer    Maintenance issuer
	 * @param startTime Maintenance start time
	 */
	public static void scheduleMaintenance(CenturiaAccount issuer, long startTime) {
		maintenanceStartTime = startTime;
		cancelMaintenance = false;
		maintenanceTimerStarted = true;

		// Log
		SimpleDateFormat fmt = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss a", Locale.US);
		fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
		HashMap<String, String> details = new HashMap<String, String>();
		details.put("Maintenance start date and time", fmt.format(new Date(startTime)) + " UTC");
		EventBus.getInstance()
				.dispatchEvent(new MiscModerationEvent("maintenancescheduled", "Server maintenance was scheduled",
						details, issuer == null ? "SYSTEM" : issuer.getAccountID(), null, false));
	}

	/**
	 * Initializes the maintenance system
	 */
	public static void initMaintenanceManager() {
		// Start maintenance handler
		Thread th = new Thread(() -> {
			long timeLastMessage = 0;
			String lastMessage = null;
			while (true) {
				// Check
				if (maintenanceTimerStarted) {
					// Check cancel
					if (cancelMaintenance) {
						maintenanceStartTime = -1;
						maintenanceTimerStarted = false;
						cancelMaintenance = false;
						timeLastMessage = 0;
						lastMessage = null;
						continue;
					}

					// Check time remaining
					long remaining = maintenanceStartTime - System.currentTimeMillis();
					if (remaining <= 0) {
						// Start maintenance

						// Enable maintenance mode
						Centuria.gameServer.maintenance = true;

						// Dispatch maintenance event
						EventBus.getInstance().dispatchEvent(new MaintenanceStartEvent());

						// Cancel if maintenance is disabled
						if (!Centuria.gameServer.maintenance) {
							// Reset schedule
							maintenanceStartTime = -1;
							maintenanceTimerStarted = false;
							cancelMaintenance = false;
							timeLastMessage = 0;
							lastMessage = null;
							continue;
						}

						// Disconnect everyone but the staff
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (!plr.account.getSaveSharedInventory().containsItem("permissions")
									|| !GameServer.hasPerm(plr.account.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")) {
								// Dispatch event
								EventBus.getInstance().dispatchEvent(
										new AccountDisconnectEvent(plr.account, null, DisconnectType.MAINTENANCE));

								plr.client.sendPacket("%xt%ua%-1%__FORCE_RELOGIN__%");
							}
						}

						// Wait a bit
						int i = 0;
						while (Stream.of(Centuria.gameServer.getPlayers())
								.filter(plr -> !plr.account.getSaveSharedInventory().containsItem("permissions")
										|| !GameServer.hasPerm(
												plr.account.getSaveSharedInventory().getItem("permissions")
														.getAsJsonObject().get("permissionLevel").getAsString(),
												"admin"))
								.findFirst().isPresent()) {
							i++;
							if (i == 30)
								break;

							try {
								Thread.sleep(1000);
							} catch (InterruptedException e) {
							}
						}
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (!plr.account.getSaveSharedInventory().containsItem("permissions")
									|| !GameServer.hasPerm(plr.account.getSaveSharedInventory().getItem("permissions")
											.getAsJsonObject().get("permissionLevel").getAsString(), "admin")) {
								// Disconnect from the game server
								plr.client.disconnect();

								// Disconnect it from the chat server
								for (ChatClient cl : Centuria.chatServer.getClients()) {
									if (cl.getPlayer().getAccountID().equals(plr.account.getAccountID())) {
										cl.disconnect();
									}
								}
							}
						}

						// Reset schedule
						maintenanceStartTime = -1;
						maintenanceTimerStarted = false;
						cancelMaintenance = false;
						timeLastMessage = 0;
						lastMessage = null;

						// Send message
						for (Player plr : Centuria.gameServer.getPlayers()) {
							if (plr != null) {
								if (plr.getObject(FeralTweaksClientObject.class) == null
										|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
									// Send as DM
									Centuria.systemMessage(plr,
											"Server maintenance has started!\n\nOnly admins can remain ingame.", true);
								} else {
									// Show popup
									OkPopupPacket pkt = new OkPopupPacket();
									pkt.title = "Server Maintenance";
									pkt.message = "Server maintenance has started!\n\nOnly admins can remain ingame.";
									plr.client.sendPacket(pkt);
								}
							}
						}
						continue;
					}

					// Countdown messages
					String message = null;
					remaining = (remaining / 1000 / 60);

					// Find message
					switch ((int) remaining) {

					case 15:
					case 30:
					case 60:
					case 120:
					case 180:
					case 240: {
						// Few hours or minutes
						if (remaining < 60)
							message = "Servers are scheduled to go down for maintenance soon!\n" //
									+ "\n"//
									+ "Servers will go down in " + remaining
									+ " minute(s) for maintenance, during this time the servers will be unavailable.\n" //
									+ "\n" //
									+ "We will be back soon!";
						else
							message = "Servers are scheduled to go down for maintenance soon!\n" //
									+ "\n"//
									+ "Servers will go down in " + (remaining / 60)
									+ " hour(s) for maintenance, during this time the servers will be unavailable.\n" //
									+ "\n" //
									+ "We will be back soon!";
						break;
					}

					case 10:
					case 5:
					case 3:
					case 1: {
						// Very little time
						message = "WARNING! Servers maintenance is gonna start very soon!\n" //
								+ "\n" //
								+ "Only " + remaining
								+ " minute(s) remaining before the servers go down for maintenance!";
						break;
					}

					default: {
						if (remaining > 240) {
							SimpleDateFormat fmt = new SimpleDateFormat("MM/dd/yyyy hh:mm:ss a", Locale.US);
							fmt.setTimeZone(TimeZone.getTimeZone("UTC"));

							// More than 4 hours remaining
							// Check amount of time since last message
							long timeSinceLast = System.currentTimeMillis() - timeLastMessage;
							if (timeSinceLast >= (12 * 60 * 60 * 1000)) {
								// Create message
								message = "There is upcoming server maintenance scheduled.\n" //
										+ "\n" //
										+ "Servers are scheduled to go down for maintenance at "
										+ fmt.format(new Date(maintenanceStartTime))
										+ " UTC, during this time the servers will be unavailable.\n" //
										+ "\n" //
										+ "We will be back soon!";
							}
						}

						if (message == null) {
							if (timeLastMessage == 0) {
								// Generate message
								if (remaining <= 10) {
									message = "WARNING! Servers maintenance is gonna start very soon!\n" //
											+ "\n" //
											+ "Only " + remaining
											+ " minute(s) remaining before the servers go down for maintenance!";
								} else if (remaining < 60) {
									message = "Servers are scheduled to go down for maintenance soon!\n" //
											+ "\n"//
											+ "Servers will go down in " + remaining
											+ " minute(s) for maintenance, during this time the servers will be unavailable.\n" //
											+ "\n" //
											+ "We will be back soon!";
								} else {
									long hours = remaining / 60;
									long minutes = remaining - (hours * 60);
									if (minutes > 0) {
										message = "Servers are scheduled to go down for maintenance soon!\n" //
												+ "\n"//
												+ "Servers will go down in " + (remaining / 60) + " hour(s) and "
												+ minutes
												+ " minute(s) for maintenance, during this time the servers will be unavailable.\n" //
												+ "\n" //
												+ "We will be back soon!";
									} else {
										message = "Servers are scheduled to go down for maintenance soon!\n" //
												+ "\n"//
												+ "Servers will go down in " + (remaining / 60)
												+ " hour(s) for maintenance, during this time the servers will be unavailable.\n" //
												+ "\n" //
												+ "We will be back soon!";
									}
								}
							}
						}
					}

					}

					// Check result
					if (message != null) {
						// Check last message
						if (lastMessage == null || !lastMessage.equals(message)) {
							// Send message
							lastMessage = message;
							for (Player plr : Centuria.gameServer.getPlayers()) {
								if (plr != null) {
									if (plr.getObject(FeralTweaksClientObject.class) == null
											|| !plr.getObject(FeralTweaksClientObject.class).isEnabled()) {
										// Send as DM
										Centuria.systemMessage(plr, message, true);
									} else {
										// Show popup
										OkPopupPacket pkt = new OkPopupPacket();
										pkt.title = "Upcoming Server Maintenance";
										pkt.message = message;
										plr.client.sendPacket(pkt);
									}
								}
							}
						}
					}
					timeLastMessage = System.currentTimeMillis();
				}
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
				}
			}
		}, "Server maintenance scheduler");
		th.setDaemon(true);
		th.start();
	}

}