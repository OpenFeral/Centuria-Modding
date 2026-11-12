package org.asf.centuria.feraltweaks.handlers.handshake;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.SaveMode;
import org.asf.centuria.data.XtReader;
import org.asf.centuria.entities.players.Player;
import org.asf.centuria.feraltweaks.FeralTweaksClientObject;
import org.asf.centuria.feraltweaks.FeralTweaksModule;
import org.asf.centuria.feraltweaks.api.versioning.IModVersionHandler;
import org.asf.centuria.feraltweaks.managers.PlayerNameManager;
import org.asf.centuria.feraltweaks.utils.VersionUtils;
import org.asf.centuria.modules.ICenturiaModule;
import org.asf.centuria.modules.ModuleManager;
import org.asf.centuria.modules.eventbus.EventListener;
import org.asf.centuria.modules.eventbus.IEventReceiver;
import org.asf.centuria.modules.events.accounts.AccountPreloginEvent;

import com.google.gson.JsonObject;

public class GameHandshakeHandler implements IEventReceiver {

	@EventListener
	public void handleGamePrelogin(AccountPreloginEvent event) {
		FeralTweaksModule ftModule = ((FeralTweaksModule) ModuleManager.getInstance().getModule("feraltweaks"));

		// Add mods to result json
		JsonObject modsJ = new JsonObject();
		modsJ.addProperty("feraltweaks", ftModule.version());
		for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
			if (module instanceof IModVersionHandler) {
				modsJ.addProperty(module.id(), module.version());
			}
		}
		event.getLoginResponseParameters().addProperty("serverSoftwareName", "Centuria");
		event.getLoginResponseParameters().addProperty("serverSoftwareVersion", Centuria.SERVER_UPDATE_VERSION);
		event.getLoginResponseParameters().add("serverMods", modsJ);

		// Handshake feraltweaks
		try {
			// Parse nick variable
			boolean feralTweaks = false;
			XtReader rd = new XtReader(event.getAuthPacket().nick);
			while (rd.hasNext()) {
				String entry = rd.read();
				if (entry.equals("feraltweaks")) {
					// Verify the chain
					if (!rd.hasNext())
						break;
					String status = rd.read();
					if (!status.equals("enabled"))
						continue;
					if (!rd.hasNext())
						break; // Invalid
					int protVer = rd.readInt();
					if (!rd.hasNext())
						break; // Invalid
					String ver = rd.read();
					if (!rd.hasNext())
						break; // Invalid
					String dataVer = rd.read();
					if (!rd.hasNext())
						break; // Invalid
					int modCount = rd.readInt();
					HashMap<String, String> mods = new HashMap<String, String>();
					HashMap<String, HashMap<String, String>> handshakeRules = new HashMap<String, HashMap<String, String>>();
					for (int i = 0; i < modCount; i++) {
						// Read id
						if (!rd.hasNext())
							break; // Invalid
						String id = rd.read();

						// Read version
						if (!rd.hasNext())
							break; // Invalid
						String version = rd.read();

						// Read handshake rules
						if (!rd.hasNext())
							break; // Invalid
						int l = rd.readInt();
						HashMap<String, String> rules = new HashMap<String, String>();
						for (int i2 = 0; i2 < l; i2++) {
							// Read id
							if (!rd.hasNext())
								break; // Invalid
							String rID = rd.read();

							// Read version check string
							if (!rd.hasNext())
								break; /// Invalid
							String rVer = rd.read();
							rules.put(rID, rVer);
						}

						// Add
						mods.put(id, version);
						handshakeRules.put(id, rules);
					}
					if (!rd.hasNext() || !rd.read().equals("end"))
						break; // Invalid
					if (rd.hasNext())
						break; // Invalid

					// Check handshake
					if (protVer != FeralTweaksModule.FT_VERSION || (!dataVer.equals("undefined")
							&& !dataVer.equals(ftModule.modDataVersion + "/" + Centuria.SERVER_UPDATE_VERSION))) {
						// Handshake failure
						event.getLoginResponseParameters().addProperty("errorMessage", ftModule.ftOutdatedErrorMessage);
						event.setStatus(-26);
						return;
					}

					// Check if FT is enabled
					if (!ftModule.enableByDefault
							&& !event.getAccount().getSaveSharedInventory().containsItem("feraltweaks")
							&& !event.getAccount().getSaveSpecificInventory().containsItem("feraltweaks")) {
						// Handshake failure
						event.setStatus(-26);
						event.getLoginResponseParameters().addProperty("errorMessage",
								ftModule.ftUnsupportedErrorMessage);
						return;
					}

					// Check managed saves if needed
					if (ftModule.requireManagedSaveData && event.getAccount().getSaveMode() != SaveMode.MANAGED) {
						// Handshake failure
						event.setStatus(-26);
						event.getLoginResponseParameters().addProperty("errorMessage",
								"Please migrate to managed save data before continuing, you can do this from the account panel.");
						return;
					}

					// Log
					String modsStr = "";
					for (String id : mods.keySet()) {
						if (!modsStr.isEmpty())
							modsStr += ", ";
						modsStr += id + " (" + mods.get(id) + ")";
					}
					Centuria.logger.info("Player " + event.getAccount().getDisplayName() + " is logging in with "
							+ mods.size() + " client mod" + (mods.size() == 1 ? "" : "s") + " [" + modsStr + "]");

					// Verify handshake rules of server
					HashMap<String, String> localMods = new HashMap<String, String>();
					ArrayList<String> missingMods = new ArrayList<String>();
					ArrayList<String> incompatibleMods = new ArrayList<String>();
					localMods.put("feraltweaks", ftModule.version());
					for (ICenturiaModule module : ModuleManager.getInstance().getAllModules()) {
						if (module instanceof IModVersionHandler) {
							localMods.put(module.id(), module.version());
							IModVersionHandler handler = (IModVersionHandler) module;
							Map<String, String> rules = handler.getClientModVersionRules();

							// Verify rules
							for (String id : rules.keySet()) {
								if (!mods.containsKey(id)) {
									// Missing
									if (!missingMods.contains(id))
										missingMods.add(id);
								} else {
									// Check version
									if (!VersionUtils.verifyVersionRequirement(mods.get(id), rules.get(id))) {
										// Incompatible
										if (!incompatibleMods.contains(id))
											incompatibleMods.add(id);
									}
								}
							}
						}
					}

					// Verify
					if (missingMods.size() != 0 || incompatibleMods.size() != 0) {
						// Handshake error

						event.setStatus(-26);
						String msg = "Your current game installation is not compatible with the server.\n\n";

						// Build message
						String logMsg = "";
						msg += "Missing/outdated client mods:\n";
						boolean first = true;
						for (String mod : missingMods) {
							if (!first)
								msg += ", ";
							msg += mod;
							if (!logMsg.isEmpty())
								logMsg += ", ";
							logMsg += mod;
							first = false;
						}
						for (String mod : incompatibleMods) {
							if (!first)
								msg += ", ";
							if (!logMsg.isEmpty())
								logMsg += ", ";
							logMsg += mod;
							msg += mod;
							first = false;
						}

						// Log
						Centuria.logger.error("Player " + event.getAccount().getDisplayName()
								+ " failed to log in due to " + (missingMods.size() + incompatibleMods.size())
								+ " incompatible/missing CLIENT mod"
								+ ((missingMods.size() + incompatibleMods.size()) == 1 ? "" : "s") + " [" + logMsg
								+ "]");
						event.getLoginResponseParameters().addProperty("incompatibleClientMods", logMsg);
						event.getLoginResponseParameters().addProperty("incompatibleClientModCount",
								(missingMods.size() + incompatibleMods.size()));

						// Set result
						event.getLoginResponseParameters().addProperty("errorMessage", msg);
						return;
					}

					// Verify client mod rules
					incompatibleMods.clear();
					for (HashMap<String, String> rules : handshakeRules.values()) {
						// Verify rules
						for (String id : rules.keySet()) {
							if (!localMods.containsKey(id)) {
								// Missing
								if (!incompatibleMods.contains(id))
									incompatibleMods.add(id);
							} else {
								// Check version
								if (!VersionUtils.verifyVersionRequirement(localMods.get(id), rules.get(id))) {
									// Incompatible
									if (!incompatibleMods.contains(id))
										incompatibleMods.add(id);
								}
							}
						}
					}

					// Verify
					if (incompatibleMods.size() != 0) {
						// Handshake error

						// Set status
						event.setStatus(-26);

						// Build message
						String logMsg = "";
						String msg = "You are running mods that require mods that require a up-to-date server mod:\n";
						boolean first = true;
						for (String mod : incompatibleMods) {
							if (!first)
								msg += ", ";
							if (!logMsg.isEmpty())
								logMsg += ", ";
							logMsg += mod;
							msg += mod;
							first = false;
						}

						// Log
						Centuria.logger
								.error("Player " + event.getAccount().getDisplayName() + " failed to log in due to "
										+ incompatibleMods.size() + " incompatible/missing SERVER mod"
										+ (incompatibleMods.size() == 1 ? "" : "s") + " [" + logMsg + "]");
						event.getLoginResponseParameters().addProperty("incompatibleServerMods", logMsg);
						event.getLoginResponseParameters().addProperty("incompatibleServerModCount",
								incompatibleMods.size());

						// Set result
						event.getLoginResponseParameters().addProperty("errorMessage", msg);
						return;
					}

					// Handshake success
					event.getClient().addObject(new FeralTweaksClientObject(true, ver, mods));
					feralTweaks = true;

					// Set displaynames
					PlayerNameManager.getInstance().syncToPlayer(((Player) event.getClient().container));
					break;
				}
			}

			if (!feralTweaks) {
				// No feraltweaks
				if ((ftModule.enableByDefault || event.getAccount().getSaveSharedInventory().containsItem("feraltweaks")
						|| event.getAccount().getSaveSpecificInventory().containsItem("feraltweaks"))
						&& ftModule.preventNonFTClients) {
					// Requires feraltweaks and its not installed/active
					// Set to error
					event.setStatus(-24);
					return;
				}
				event.getClient().addObject(new FeralTweaksClientObject(false, null, Map.of()));
			}
		} catch (Exception e) {
			// Uhh what
			event.setStatus(-1);
		}
	}
}
