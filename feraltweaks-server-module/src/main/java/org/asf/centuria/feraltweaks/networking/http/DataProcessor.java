package org.asf.centuria.feraltweaks.networking.http;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;

import org.asf.centuria.Centuria;
import org.asf.centuria.accounts.AccountManager;
import org.asf.centuria.accounts.CenturiaAccount;
import org.asf.centuria.feraltweaks.FeralTweaksModule;
import org.asf.centuria.modules.ModuleManager;
import org.asf.centuria.networking.gameserver.GameServer;
import org.asf.connective.NetworkedConnectiveHttpServer;
import org.asf.connective.RemoteClient;
import org.asf.connective.TlsSecuredHttpServer;
import org.asf.connective.impl.http_1_1.RemoteClientHttp_1_1;
import org.asf.connective.handlers.HttpRequestHandler;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public class DataProcessor extends HttpRequestHandler {

	@Override
	public HttpRequestHandler createNewInstance() {
		return new DataProcessor();
	}

	@Override
	public String path() {
		return "/data";
	}

	@Override
	public void handle(String pth, String method, RemoteClient client) {
		try {
			// Retrieve path
			String path = getRequest().getRequestPath().substring(path().length());

			// Sanitize path
			if (path.contains("..")) {
				setResponseStatus(403, "Access to parent directories denied");
				return;
			}

			// Retrieve module
			FeralTweaksModule module = (FeralTweaksModule) ModuleManager.getInstance().getModule("feraltweaks");

			// Check file
			File reqFile = new File(module.ftDataPath, path);
			File sourceFile = reqFile;
			File userpatchFile = new File(module.ftUserPatchesPath, path);
			File cachedModuleFile = new File(new File(module.ftCachePath, "moduledata"), path);
			if (reqFile.isDirectory() && (!userpatchFile.exists() || userpatchFile.isDirectory())
					&& (!cachedModuleFile.exists() || cachedModuleFile.isDirectory())) {
				this.setResponseStatus(404, "Not found");
				this.setResponseContent("text/json", "{\"error\":\"file_not_found\"}");
				return;
			}

			// Check cache data of server modules and contentpacks
			if (cachedModuleFile.exists() && !cachedModuleFile.isDirectory())
				reqFile = cachedModuleFile;

			// Check existence of userpatch, if found, use that one
			if (userpatchFile.exists() && !userpatchFile.isDirectory())
				reqFile = userpatchFile;

			// Verify security

			// Load account
			CenturiaAccount account = null;
			if (getRequest().hasHeader("Authorization")) {
				// Parse JWT payload
				String token = this.getHeader("Authorization").substring("Bearer ".length());
				if (!token.isBlank()) {
					// Verify signature
					String verifyD = token.split("\\.")[0] + "." + token.split("\\.")[1];
					String sig = token.split("\\.")[2];
					if (!Centuria.verify(verifyD.getBytes("UTF-8"), Base64.getUrlDecoder().decode(sig))) {
						this.setResponseStatus(401, "Unauthorized");
						return;
					}

					// Verify expiry
					JsonObject jwtPl = JsonParser
							.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
							.getAsJsonObject();
					if (jwtPl.has("exp") && jwtPl.get("exp").getAsLong() >= System.currentTimeMillis() / 1000) {
						JsonObject payload = JsonParser
								.parseString(new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), "UTF-8"))
								.getAsJsonObject();

						// Find account
						String accId = payload.get("uuid").getAsString();
						account = AccountManager.getInstance().getAccount(accId);
					}
				}
			}

			// First, check if modding requires to be enabled per account
			if (!module.enableByDefault) {
				// Require authorization
				if (account == null) {
					this.setResponseContent("text/json", "{\"error\":\"server_requires_authorization\"}");
					this.setResponseStatus(401, "Unauthorized");
					return;
				}

				// Check enabled
				if (!account.getSaveSharedInventory().containsItem("feraltweaks")
						&& !account.getSaveSpecificInventory().containsItem("feraltweaks")) {
					// Error
					this.setResponseContent("text/json", "{\"error\":\"feraltweaks_not_enabled\"}");
					this.setResponseStatus(401, "Unauthorized");
					return;
				}
			}

			// Find secure root
			File moduleFileRoot = new File(module.ftDataPath);
			File moduleDataFileRoot = new File(module.ftCachePath, "moduledata");
			File userpatchFileRoot = new File(module.ftUserPatchesPath);
			File secureRoot = reqFile.getParentFile();
			while (true) {
				// Check if at module root
				if (secureRoot.getCanonicalPath().equals(moduleFileRoot.getCanonicalPath())
						|| secureRoot.getCanonicalPath().equals(moduleDataFileRoot.getCanonicalPath())
						|| secureRoot.getCanonicalPath().equals(userpatchFileRoot.getCanonicalPath()))
					break;

				// Check file
				if (new File(secureRoot, "ftserverdocsecurity.json").exists())
					break;

				// Get parent
				secureRoot = secureRoot.getParentFile();
			}

			// Check module cache if using userpatches and the secure root file config cant
			// be found
			if (reqFile == userpatchFile && !new File(secureRoot, "ftserverdocsecurity.json").exists()) {
				// Try with original source
				secureRoot = cachedModuleFile.getParentFile();
				while (true) {
					// Check if at module root
					if (secureRoot.getCanonicalPath().equals(moduleDataFileRoot.getCanonicalPath()))
						break;

					// Check file
					if (new File(secureRoot, "ftserverdocsecurity.json").exists())
						break;

					// Get parent
					secureRoot = secureRoot.getParentFile();
				}
			}

			// Check contentpacks
			if (reqFile != sourceFile && !new File(secureRoot, "ftserverdocsecurity.json").exists()) {
				// Try with original source
				secureRoot = sourceFile.getParentFile();
				while (true) {
					// Check if at module root
					if (secureRoot.getCanonicalPath().equals(moduleFileRoot.getCanonicalPath()))
						break;

					// Check file
					if (new File(secureRoot, "ftserverdocsecurity.json").exists())
						break;

					// Get parent
					secureRoot = secureRoot.getParentFile();
				}
			}

			// Check security
			if (new File(secureRoot, "ftserverdocsecurity.json").exists()) {
				// Make sure its not a request for this document
				if (reqFile.getCanonicalPath()
						.equals(new File(secureRoot, "ftserverdocsecurity.json").getCanonicalPath())) {
					// 404 it
					this.setResponseStatus(404, "Not found");
					this.setResponseContent("text/json", "{\"error\":\"file_not_found\"}");
					return;
				}

				// Read
				JsonObject security = JsonParser
						.parseString(Files.readString(new File(secureRoot, "ftserverdocsecurity.json").toPath()))
						.getAsJsonObject();

				// Whitelist
				if (security.has("whitelistedUsers")) {
					JsonArray whitelist = security.get("whitelistedUsers").getAsJsonArray();
					if (account == null) {
						// Authorization missing
						this.setResponseContent("text/json", "{\"error\":\"server_requires_authorization\"}");
						this.setResponseStatus(401, "Unauthorized");
						return;
					}

					// Verify whitelist
					boolean found = false;
					for (JsonElement ele : whitelist) {
						if (ele.getAsString().equals(account.getAccountID())) {
							found = true;
							break;
						}
					}
					if (!found) {
						// Authorization missing
						JsonObject res = new JsonObject();
						res.addProperty("error", "not_authorized");
						res.addProperty("errorMessage",
								security.has("whitelistErrorMessage")
										? security.get("whitelistErrorMessage").getAsString()
										: "You are not authorized to use this launcher.\n\nUnable to start the game.");
						this.setResponseContent("text/json", res.toString());
						this.setResponseStatus(401, "Unauthorized");
						return;
					}
				}

				// Blacklist
				if (security.has("blacklistedUsers")) {
					JsonArray blacklist = security.get("blacklistedUsers").getAsJsonArray();
					if (account == null) {
						// Authorization missing
						this.setResponseContent("text/json", "{\"error\":\"server_requires_authorization\"}");
						this.setResponseStatus(401, "Unauthorized");
						return;
					}

					// Verify blacklist
					boolean found = false;
					for (JsonElement ele : blacklist) {
						if (ele.getAsString().equals(account.getAccountID())) {
							found = true;
							break;
						}
					}
					if (found) {
						// Authorization missing
						JsonObject res = new JsonObject();
						res.addProperty("error", "not_authorized");
						res.addProperty("errorMessage",
								security.has("blacklistErrorMessage")
										? security.get("blacklistErrorMessage").getAsString()
										: "You are not authorized to use this launcher.\n\nUnable to start the game.");
						this.setResponseContent("text/json", res.toString());
						this.setResponseStatus(401, "Unauthorized");
						return;
					}
				}

				// Permission whitelist
				if (security.has("allowedPermissionLevels")) {
					JsonArray permissionLevelsWhitelist = security.get("allowedPermissionLevels").getAsJsonArray();
					if (account == null) {
						// Authorization missing
						this.setResponseContent("text/json", "{\"error\":\"server_requires_authorization\"}");
						this.setResponseStatus(401, "Unauthorized");
						return;
					}

					// Get permission level
					String permLevel = "member";
					if (account.getSaveSharedInventory().containsItem("permissions")) {
						permLevel = account.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
								.get("permissionLevel").getAsString();
					}

					// Verify permission level
					boolean found = false;
					for (JsonElement ele : permissionLevelsWhitelist) {
						if (ele.getAsString().equals(permLevel)) {
							found = true;
							break;
						}
					}
					if (!found) {
						// Authorization missing
						JsonObject res = new JsonObject();
						res.addProperty("error", "not_authorized");
						res.addProperty("errorMessage",
								security.has("permissionWhitelistErrorMessage")
										? security.get("permissionWhitelistErrorMessage").getAsString()
										: "You are not authorized to use this launcher.\n\nUnable to start the game.");
						this.setResponseContent("text/json", res.toString());
						this.setResponseStatus(401, "Unauthorized");
						return;
					}
				}

				// Permission blacklist
				if (security.has("deniedPermissionLevels")) {
					JsonArray permissionLevelsBlacklist = security.get("deniedPermissionLevels").getAsJsonArray();
					if (account == null) {
						// Authorization missing
						this.setResponseContent("text/json", "{\"error\":\"server_requires_authorization\"}");
						this.setResponseStatus(401, "Unauthorized");
						return;
					}

					// Get permission level
					String permLevel = "member";
					if (account.getSaveSharedInventory().containsItem("permissions")) {
						permLevel = account.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
								.get("permissionLevel").getAsString();
					}

					// Verify permission level
					boolean found = false;
					for (JsonElement ele : permissionLevelsBlacklist) {
						if (ele.getAsString().equals(permLevel)) {
							found = true;
							break;
						}
					}
					if (found) {
						// Authorization missing
						JsonObject res = new JsonObject();
						res.addProperty("error", "not_authorized");
						res.addProperty("errorMessage",
								security.has("permissionBlacklistErrorMessage")
										? security.get("permissionBlacklistErrorMessage").getAsString()
										: "You are not authorized to use this launcher.\n\nUnable to start the game.");
						this.setResponseContent("text/json", res.toString());
						this.setResponseStatus(401, "Unauthorized");
						return;
					}
				}

				// Permission level
				if (security.has("requiredPermissionLevel")) {
					if (account == null) {
						// Authorization missing
						this.setResponseContent("text/json", "{\"error\":\"server_requires_authorization\"}");
						this.setResponseStatus(401, "Unauthorized");
						return;
					}

					// Get permission level
					String permLevel = "member";
					if (account.getSaveSharedInventory().containsItem("permissions")) {
						permLevel = account.getSaveSharedInventory().getItem("permissions").getAsJsonObject()
								.get("permissionLevel").getAsString();
					}

					// Verify permission level
					if (!GameServer.hasPerm(permLevel, security.get("requiredPermissionLevel").getAsString())) {
						// Authorization missing
						JsonObject res = new JsonObject();
						res.addProperty("error", "not_authorized");
						res.addProperty("errorMessage",
								security.has("permissionLevelErrorMessage")
										? security.get("permissionLevelErrorMessage").getAsString()
										: "You are not authorized to use this launcher.\n\nUnable to start the game.");
						this.setResponseContent("text/json", res.toString());
						this.setResponseStatus(401, "Unauthorized");
						return;
					}
				}

				// Tag whitelist
				if (security.has("allowedTags")) {
					JsonArray tagWhitelist = security.get("allowedTags").getAsJsonArray();
					if (account == null) {
						// Authorization missing
						this.setResponseContent("text/json", "{\"error\":\"server_requires_authorization\"}");
						this.setResponseStatus(401, "Unauthorized");
						return;
					}

					// Verify tags
					boolean found = false;
					for (JsonElement ele : tagWhitelist) {
						if (account.getSaveSharedInventory().containsItem("tag-" + ele.getAsString())) {
							found = true;
							break;
						}
					}
					if (!found) {
						// Authorization missing
						JsonObject res = new JsonObject();
						res.addProperty("error", "not_authorized");
						res.addProperty("errorMessage",
								security.has("tagWhitelistErrorMessage")
										? security.get("tagWhitelistErrorMessage").getAsString()
										: "You are not authorized to use this launcher.\n\nUnable to start the game.");
						this.setResponseContent("text/json", res.toString());
						this.setResponseStatus(401, "Unauthorized");
						return;
					}
				}

				// Permission blacklist
				if (security.has("deniedTags")) {
					JsonArray tagBlacklist = security.get("deniedTags").getAsJsonArray();
					if (account == null) {
						// Authorization missing
						this.setResponseContent("text/json", "{\"error\":\"server_requires_authorization\"}");
						this.setResponseStatus(401, "Unauthorized");
						return;
					}

					// Verify tags
					boolean found = false;
					for (JsonElement ele : tagBlacklist) {
						if (account.getSaveSharedInventory().containsItem("tag-" + ele.getAsString())) {
							found = true;
							break;
						}
					}
					if (found) {
						// Authorization missing
						JsonObject res = new JsonObject();
						res.addProperty("error", "not_authorized");
						res.addProperty("errorMessage",
								security.has("tagBlacklistErrorMessage")
										? security.get("tagBlacklistErrorMessage").getAsString()
										: "You are not authorized to use this launcher.\n\nUnable to start the game.");
						this.setResponseContent("text/json", res.toString());
						this.setResponseStatus(401, "Unauthorized");
						return;
					}
				}
			}

			// Check test endpoint
			if (path.endsWith("/clientmods/testendpoint")) {
				this.setResponseStatus(200, "OK");
				this.setResponseContent("text/json", "{\"status\":\"ok\"}");
				return;
			}

			// Check result
			if ((!reqFile.exists() || ((!reqFile.getParentFile().getCanonicalPath()
					.equalsIgnoreCase(new File(module.ftDataPath).getCanonicalPath())
					&& !reqFile.getParentFile().getCanonicalPath().toLowerCase()
							.startsWith(new File(module.ftDataPath).getCanonicalPath().toLowerCase() + File.separator))
					&& (!reqFile.getParentFile().getCanonicalPath()
							.equalsIgnoreCase(new File(module.ftUserPatchesPath).getCanonicalPath())
							&& !reqFile.getParentFile().getCanonicalPath().toLowerCase()
									.startsWith(new File(module.ftUserPatchesPath).getCanonicalPath().toLowerCase()
											+ File.separator))
					&& (!reqFile.getParentFile().getCanonicalPath()
							.equalsIgnoreCase(new File(module.ftCachePath, "moduledata").getCanonicalPath())
							&& !reqFile.getParentFile().getCanonicalPath().toLowerCase().startsWith(
									new File(module.ftCachePath, "moduledata").getCanonicalPath().toLowerCase()
											+ File.separator))))
					&& !path.endsWith("/feraltweaks/chartpatches/index.json")
					&& !path.endsWith("/feraltweaks/settings.props")
					&& !path.endsWith("/clientmods/assemblies/index.json")
					&& !path.endsWith("/clientmods/assets/index.json")
					&& !path.endsWith("/clientmods/assemblies-osx/index.json")
					&& !path.endsWith("/clientmods/assets-osx/index.json")
					&& !path.endsWith("/clientmods/assemblies-win64/index.json")
					&& !path.endsWith("/clientmods/assets-win64/index.json")
					&& !path.endsWith("/clientmods/assemblies-linux/index.json")
					&& !path.endsWith("/clientmods/assets-linux/index.json") && !path.endsWith("/server.json")) {

				// Check if its a index json request of a different folder
				if (path.endsWith("/index.json")) {
					// Find path
					File parent = sourceFile.getParentFile();
					File parentModuleData = cachedModuleFile.getParentFile();
					File parentPatched = userpatchFile.getParentFile();
					if ((parent.exists() && parent.isDirectory() && !parent.equals(new File(module.ftDataPath)))
							|| (parentPatched.exists() && parentPatched.isDirectory()
									&& !parentPatched.equals(new File(module.ftUserPatchesPath)))
							|| (parentModuleData.exists() && parentModuleData.isDirectory()
									&& !parentModuleData.equals(new File(module.ftCachePath, "moduledata")))) {
						// Handle
						JsonArray res = new JsonArray();
						scan(parent, parentModuleData, parentPatched, new ArrayList<String>(), res,
								path.substring(0, path.lastIndexOf("index.json")));
						getResponse().setResponseStatus(200, "OK");
						getResponse().setContent("text/json", res.toString());
						return;
					}
				}

				this.setResponseStatus(404, "Not found");
				this.setResponseContent("text/json", "{\"error\":\"file_not_found\"}");
				return;
			}

			// Check feraltweaks requests
			if (path.endsWith("/feraltweaks/settings.props")) {
				if (!reqFile.getParentFile().exists()) {
					this.setResponseStatus(404, "Not found");
					this.setResponseContent("text/json", "{\"error\":\"file_not_found\"}");
					return;
				}

				// Append to the settings properties file
				String res = "";
				if (reqFile.exists())
					res = Files.readString(reqFile.toPath()).replace("\r", "");
				res = res.replace("${server:version}", Centuria.SERVER_UPDATE_VERSION);

				// Add replication
				for (String obj : module.replicatingObjects.keySet()) {
					res += "OverrideReplicate-" + obj + "=" + (module.replicatingObjects.get(obj) ? "True" : "False")
							+ "\n";
				}
				res += "ServerVersion=" + module.modDataVersion + "/" + Centuria.SERVER_UPDATE_VERSION + "\n";

				getResponse().setResponseStatus(200, "OK");
				getResponse().setContent("text/plain", res);
				return;
			} else if (path.endsWith("/feraltweaks/chartpatches/index.json")) {
				if (!reqFile.getParentFile().exists()) {
					this.setResponseStatus(404, "Not found");
					this.setResponseContent("text/json", "{\"error\":\"file_not_found\"}");
					return;
				}

				// Index json
				JsonArray res = new JsonArray();
				File reqSource = new File(module.ftDataPath, path.substring(0, path.lastIndexOf("/index.json")));
				File reqSourcePatched = new File(module.ftUserPatchesPath,
						path.substring(0, path.lastIndexOf("/index.json")));
				File reqSourceCached = new File(new File(module.ftCachePath, "moduledata"),
						path.substring(0, path.lastIndexOf("/index.json")));
				scan(reqSource, reqSourceCached, reqSourcePatched, new ArrayList<String>(), res,
						path.substring(0, path.lastIndexOf("/index.json")) + "/");
				getResponse().setResponseStatus(200, "OK");
				getResponse().setContent("text/json", res.toString());
				return;
			} else if (path.endsWith("/clientmods/assemblies/index.json")
					&& path.endsWith("/clientmods/assemblies-osx/index.json")
					&& path.endsWith("/clientmods/assemblies-win64/index.json")
					&& path.endsWith("/clientmods/assemblies-linux/index.json")) {
				if (!reqFile.getParentFile().exists()) {
					this.setResponseStatus(404, "Not found");
					this.setResponseContent("text/json", "{\"error\":\"file_not_found\"}");
					return;
				}

				// Index json
				JsonObject res = new JsonObject();
				File source = new File(module.ftDataPath, path.substring(0, path.lastIndexOf("/index.json")));
				File sourcePatched = new File(module.ftUserPatchesPath,
						path.substring(0, path.lastIndexOf("/index.json")));
				File reqSourceCached = new File(new File(module.ftCachePath, "moduledata"),
						path.substring(0, path.lastIndexOf("/index.json")));
				scan(source, reqSourceCached, sourcePatched, new ArrayList<String>(), res,
						path.substring(0, path.lastIndexOf("/index.json")) + "/", "/");
				getResponse().setResponseStatus(200, "OK");
				getResponse().setContent("text/json", res.toString());
				return;
			} else if (path.endsWith("/clientmods/assets/index.json")
					&& path.endsWith("/clientmods/assets-osx/index.json")
					&& path.endsWith("/clientmods/assets-win64/index.json")
					&& path.endsWith("/clientmods/assets-linux/index.json")) {
				if (!reqFile.getParentFile().exists()) {
					this.setResponseStatus(404, "Not found");
					this.setResponseContent("text/json", "{\"error\":\"file_not_found\"}");
					return;
				}

				// Index json
				JsonObject res = new JsonObject();
				File source = new File(module.ftDataPath, path.substring(0, path.lastIndexOf("/index.json")));
				File sourcePatched = new File(module.ftUserPatchesPath,
						path.substring(0, path.lastIndexOf("/index.json")));
				File reqSourceCached = new File(new File(module.ftCachePath, "moduledata"),
						path.substring(0, path.lastIndexOf("/index.json")));
				scan(source, reqSourceCached, sourcePatched, new ArrayList<String>(), res,
						path.substring(0, path.lastIndexOf("/index.json")) + "/", "/");
				getResponse().setResponseStatus(200, "OK");
				getResponse().setContent("text/json", res.toString());
				return;
			}

			// Handle server.json
			if (path.endsWith("/server.json")) {
				// Create cache
				File cacheDir = new File(module.ftCachePath);
				cacheDir.mkdirs();
				File cachedServerFileDir = new File(cacheDir,
						path.substring(0, path.length() - "/server.json".length()));
				cachedServerFileDir.mkdirs();

				// Attempt to update upstream
				JsonObject serverInfo = new JsonObject();
				boolean successfulUpdate = false;
				if (!module.upstreamServerJsonURL.isEmpty() && !module.upstreamServerJsonURL.equals("undefined")
						&& !module.upstreamServerJsonURL.equals("disabled")) {
					try {
						String url = module.upstreamServerJsonURL;
						if (!url.endsWith("/"))
							url = url + "/";
						HttpURLConnection conn = (HttpURLConnection) new URL(url + path).openConnection();
						InputStream strm = conn.getInputStream();
						String data = new String(strm.readAllBytes(), "UTF-8");
						Files.writeString(new File(cachedServerFileDir, "upstream.server.json").toPath(), data);
						serverInfo = JsonParser.parseString(data).getAsJsonObject();
						successfulUpdate = true;
					} catch (Exception e) {
					}
				}

				if (!successfulUpdate) {
					// Load existing
					try {
						File serverFile = new File(cachedServerFileDir, "upstream.server.json");
						if (serverFile.exists())
							serverInfo = JsonParser.parseString(Files.readString(serverFile.toPath()))
									.getAsJsonObject();
					} catch (Exception e) {
					}
				}

				// Parse address
				String addr = Centuria.discoveryAddress;
				if (addr.equals("localhost") || addr.equals("127.0.0.1")) {
					// Get local IP
					String host = "localhost";
					if (client instanceof RemoteClientHttp_1_1) {
						RemoteClientHttp_1_1 cl = (RemoteClientHttp_1_1) client;
						Socket sock = cl.getSocket();

						// Get interface
						SocketAddress ad = sock.getLocalSocketAddress();
						if (ad instanceof InetSocketAddress) {
							InetSocketAddress iA = (InetSocketAddress) ad;
							if (Centuria.encryptGame
									&& (!Centuria.encryptGame || Centuria.directorServer instanceof TlsSecuredHttpServer
											|| this.getServer() instanceof TlsSecuredHttpServer))
								host = iA.getAddress().getCanonicalHostName();
							else
								host = iA.getAddress().getHostAddress();
							addr = host;
						}
					}
					if (host.equals("localhost")
							&& (!Centuria.encryptGame && !(Centuria.directorServer instanceof TlsSecuredHttpServer)
									&& !(this.getServer() instanceof TlsSecuredHttpServer)))
						addr = "127.0.0.1";
				}

				// Generate data
				String addrRaw = addr;
				if (addr.contains(":"))
					addr = "[" + addr + "]";
				JsonObject serverBlock = createOrGetJsonObject(serverInfo, "server");
				JsonObject hosts = createOrGetJsonObject(serverBlock, "hosts");
				hosts.addProperty("director",
						((Centuria.directorServer instanceof TlsSecuredHttpServer) ? "https" : "http") + "://" + addr
								+ ":" + ((NetworkedConnectiveHttpServer) Centuria.directorServer).getListenPort()
								+ "/");
				hosts.addProperty("api", ((this.getServer() instanceof TlsSecuredHttpServer) ? "https" : "http") + "://"
						+ addr + ":" + ((NetworkedConnectiveHttpServer) this.getServer()).getListenPort() + "/");
				hosts.addProperty("chat", addrRaw);
				hosts.addProperty("voiceChat", addrRaw);
				JsonObject ports = createOrGetJsonObject(serverBlock, "ports");
				ports.addProperty("game", Centuria.gameServer.getServerSocket().getLocalPort());
				ports.addProperty("chat", Centuria.chatServer.getServerSocket().getLocalPort());
				ports.addProperty("voiceChat", Centuria.voiceChatServer.getServerSocket().getLocalPort());
				ports.addProperty("bluebox", -1);
				serverBlock.addProperty("encryptedGame", Centuria.encryptGame);
				serverBlock.addProperty("encryptedChat", Centuria.encryptChat);
				serverBlock.addProperty("encryptedVoiceChat", Centuria.encryptVoiceChat);
				serverBlock.addProperty("modVersion",
						(serverBlock.has("modVersion") ? serverBlock.get("modVersion").getAsString() + "-" : "")
								+ module.modDataVersion);
				serverBlock.addProperty("assetVersion",
						(serverBlock.has("assetVersion") ? serverBlock.get("assetVersion").getAsString() + "-" : "")
								+ module.modDataVersion);
				if (hosts.has("launcherDataSource")) {
					// Deal with data sources pointing to upstream, as that WILL go wrong
					// Check upstream source
					String upstream = hosts.get("launcherDataSource").getAsString();
					if (!module.upstreamServerJsonURL.isEmpty() && !module.upstreamServerJsonURL.equals("undefined")
							&& !module.upstreamServerJsonURL.equals("disabled")) {
						String url = module.upstreamServerJsonURL;
						if (!url.endsWith("/"))
							url = url + "/";
						if (upstream.startsWith(url)) {
							// Uses upstream okaaaaaaaaay
							// Lets change that to the local server
							upstream = ((this.getServer() instanceof TlsSecuredHttpServer) ? "https" : "http") + "://"
									+ addr + ":" + ((NetworkedConnectiveHttpServer) this.getServer()).getListenPort()
									+ "/" + upstream.substring(url.length());
							hosts.addProperty("launcherDataSource", upstream);
						}
					}
				}

				// Load existing data over it
				if (sourceFile.exists()) {
					try {
						JsonObject localServerInfo = JsonParser.parseString(Files.readString(reqFile.toPath()))
								.getAsJsonObject();

						// Load existing data over the server info block
						mergeObject(localServerInfo, serverInfo);
					} catch (Exception e) {
					}
				}
				if (userpatchFile.exists()) {
					try {
						JsonObject localServerInfo = JsonParser.parseString(Files.readString(reqFile.toPath()))
								.getAsJsonObject();

						// Load existing data over the server info block
						mergeObject(localServerInfo, serverInfo);
					} catch (Exception e) {
					}
				}

				// Set content
				getResponse().setResponseStatus(200, "OK");
				getResponse().setContent("application/json",
						new Gson().newBuilder().setPrettyPrinting().create().toJson(serverInfo));
				return;
			}

			// Set content
			getResponse().setResponseStatus(200, "OK");
			getResponse().setContent(MainFileMap.getInstance().getContentType(reqFile.getName()),
					new FileInputStream(reqFile), reqFile.length());
		} catch (Exception e) {
			setResponseStatus(500, "Internal Server Error");
			Centuria.logger.error(getRequest().getRequestPath() + " failed: 500: Internal Server Error", e);
		}
	}

	private void mergeObject(JsonObject source, JsonObject target) {
		// Merge each entry
		for (String key : source.keySet()) {
			if (source.get(key).isJsonObject()) {
				JsonObject chT = createOrGetJsonObject(target, key);
				mergeObject(source.get(key).getAsJsonObject(), chT);
			} else
				target.add(key, source.get(key));
		}
	}

	private JsonObject createOrGetJsonObject(JsonObject obj, String name) {
		if (obj.has(name))
			return obj.get(name).getAsJsonObject();
		JsonObject res = new JsonObject();
		obj.add(name, res);
		return res;
	}

	private void scan(File source, File sourceCache, File sourcePatched, ArrayList<String> foundFiles, JsonArray res,
			String prefix) {
		if (source.exists()) {
			for (File f : source.listFiles()) {
				if (f.isDirectory()) {
					// Scan
					scan(f, new File(sourceCache, f.getName()), new File(sourcePatched, f.getName()), foundFiles, res,
							prefix + f.getName() + "/");
				} else if (!foundFiles.contains(prefix + f.getName())) {
					res.add(prefix + f.getName());
					foundFiles.add(prefix + f.getName());
				}
			}
		}
		if (sourceCache.exists()) {
			for (File f : source.listFiles()) {
				if (f.isDirectory()) {
					// Scan
					scan(new File(source, f.getName()), f, new File(sourcePatched, f.getName()), foundFiles, res,
							prefix + f.getName() + "/");
				} else if (!foundFiles.contains(prefix + f.getName())) {
					res.add(prefix + f.getName());
					foundFiles.add(prefix + f.getName());
				}
			}
		}
		if (sourcePatched.exists()) {
			for (File f : sourcePatched.listFiles()) {
				if (f.isDirectory()) {
					// Scan
					scan(new File(source, f.getName()), new File(sourceCache, f.getName()), f, foundFiles, res,
							prefix + f.getName() + "/");
				} else if (!foundFiles.contains(prefix + f.getName())) {
					res.add(prefix + f.getName());
					foundFiles.add(prefix + f.getName());
				}
			}
		}
	}

	private void scan(File source, File sourceCached, File sourcePatched, ArrayList<String> foundFiles, JsonObject res,
			String prefix, String prefixOut) {
		if (source.exists()) {
			for (File f : source.listFiles()) {
				if (f.isDirectory()) {
					// Scan
					scan(f, new File(sourceCached, f.getName()), new File(sourcePatched, f.getName()), foundFiles, res,
							prefix + f.getName() + "/", prefixOut + f.getName() + "/");
				} else if (!foundFiles.contains(prefix + f.getName())) {
					res.addProperty(prefix + f.getName(), prefixOut + f.getName());
					foundFiles.add(prefix + f.getName());
				}
			}
		}
		if (sourceCached.exists()) {
			for (File f : sourcePatched.listFiles()) {
				if (f.isDirectory()) {
					// Scan
					scan(new File(source, f.getName()), f, new File(sourcePatched, f.getName()), foundFiles, res,
							prefix + f.getName() + "/", prefixOut + f.getName() + "/");
				} else if (!foundFiles.contains(prefix + f.getName())) {
					res.addProperty(prefix + f.getName(), prefixOut + f.getName());
					foundFiles.add(prefix + f.getName());
				}
			}
		}
		if (sourcePatched.exists()) {
			for (File f : sourcePatched.listFiles()) {
				if (f.isDirectory()) {
					// Scan
					scan(new File(source, f.getName()), new File(sourceCached, f.getName()), f, foundFiles, res,
							prefix + f.getName() + "/", prefixOut + f.getName() + "/");
				} else if (!foundFiles.contains(prefix + f.getName())) {
					res.addProperty(prefix + f.getName(), prefixOut + f.getName());
					foundFiles.add(prefix + f.getName());
				}
			}
		}
	}

	@Override
	public boolean supportsChildPaths() {
		return true;
	}
}