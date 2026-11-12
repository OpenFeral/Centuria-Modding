using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Text.RegularExpressions;
using FeralTweaks.Logging;
using FeralTweaks.Mods;
using Newtonsoft.Json;

namespace FeralTweaks
{
    /// <summary>
    /// FeralTweaks Modloader Type
    /// </summary>
    public static class FeralTweaksLoader
    {
        public const string VERSION = "v1.2.5-alpha-a3";
        private static List<FeralTweaksMod> mods;

        private static Logger logger;

        public static Logger Logger
        {
            get
            {
                return logger;
            }
        }
        
        private class ModInfo
        {
            public string id;
            public string version;

            public string path;

            public int loadPriority = 0;
            public List<string> dependencies = new List<string>();
            public List<string> optionalDependencies = new List<string>();
            public List<string> conflictsWith = new List<string>();
            public List<string> loadBefore = new List<string>();
            public Dictionary<string, string> dependencyVersions = new Dictionary<string, string>();
        }

        private class FTMManifest
        {
            public string id;
            public string version;
            public long timestamp;
        }

        /// <summary>
        /// Checks if debug logging is enabled
        /// </summary>
        public static bool DebugLoggingEnabled
        {
            get
            {
                return FeralTweaksBootstrap.Bootstrap.DebugLogging;
            }
        }

        /// <summary>
        /// Retrieves all loaded mods
        /// </summary>
        /// <returns>Array of FeralTweaksMod instances</returns>
        public static FeralTweaksMod[] GetLoadedMods()
        {
            return mods.ToArray();
        }

        /// <summary>
        /// Checks if a mod is loaded by its ID
        /// </summary>
        /// <param name="id">Mod ID</param>
        /// <returns>True if loaded, false otherwise</returns>
        public static bool IsModLoaded(string id)
        {
            return mods.Any(t => t.ID == id);
        }

        /// <summary>
        /// Retrieves loaded mods by ID
        /// </summary>
        /// <param name="id">Mod ID</param>
        /// <returns>FeralTweaksMod instance</returns>
        public static FeralTweaksMod GetLoadedMod(string id)
        {
            if (IsModLoaded(id))
                return mods.Find(t => t.ID == id);
            throw new ArgumentException("Specified mod is not loaded");
        }

        /// <summary>
        /// Retrieves loaded mods by type
        /// </summary>
        /// <returns>FeralTweaksMod instance</returns>
        public static T GetLoadedMod<T>() where T : FeralTweaksMod
        {
            if (mods.Any(t => t is T))
                return (T)mods.Find(t => t is T);
            throw new ArgumentException("Specified mod is not loaded");
        }

        /// <summary>
        /// Logs an info message
        /// </summary>
        /// <param name="message">Message to log</param>
        public static void LogInfo(string message)
        {
            logger.Info(message);
        }

        /// <summary>
        /// Logs a debug message
        /// </summary>
        /// <param name="message">Message to log</param>
        public static void LogDebug(string message)
        {
            logger.Debug(message);
        }

        /// <summary>
        /// Logs a trace message
        /// </summary>
        /// <param name="message">Message to log</param>
        public static void LogTrace(string message)
        {
            logger.Trace(message);
        }

        /// <summary>
        /// Logs a warning message
        /// </summary>
        /// <param name="message">Message to log</param>
        public static void LogWarn(string message)
        {
            logger.Warn(message);
        }

        /// <summary>
        /// Logs an error message
        /// </summary>
        /// <param name="message">Message to log</param>
        public static void LogError(string message)
        {
            logger.Error(message);
        }

        /// <summary>
        /// Logs a fatal error message
        /// </summary>
        /// <param name="message">Message to log</param>
        public static void LogFatal(string message)
        {
            logger.Fatal(message);
        }

        private static void Logger_MessageReceived(object sender, HarmonyLib.Tools.Logger.LogEventArgs e)
        {
            switch (e.LogChannel)
            {
                case HarmonyLib.Tools.Logger.LogChannel.Info:
                    LogInfo(e.Message);
                    break;
                case HarmonyLib.Tools.Logger.LogChannel.Warn:
                    LogWarn(e.Message);
                    break;
                case HarmonyLib.Tools.Logger.LogChannel.Error:
                    LogError(e.Message);
                    break;
                case HarmonyLib.Tools.Logger.LogChannel.Debug:
                    break;
                case HarmonyLib.Tools.Logger.LogChannel.IL:
                    break;
            }
        }

        internal static void LoadFinish()
        {
            // Log
            LogInfo("Initial loading completed!");
            LogInfo("Post-initializing mods...");
            RunForMods(mod =>
            {
                LogInfo("Post-initializing mod: " + mod.ID);
                mod.PostInit();
            });
        }

        internal static void Start()
        {
            // Set up log
            logger = Logger.GetLogger("Loader");

            // Log
            LogInfo("Preparing...");
            LogInfo("FeralTweaks Loader version " + VERSION + " initializing...");
            HarmonyLib.Tools.Logger.MessageReceived += Logger_MessageReceived;

            // Load command line mods
            LogDebug("Parsing command line properties...");
            string[] args = Environment.GetCommandLineArgs();
            List<string> externalMods = new List<string>();
            Dictionary<string, List<string>> externalModAssemblies = new Dictionary<string, List<string>>();
            for (int i = 1; i < args.Length; i++)
            {
                if (args[i].StartsWith("--"))
                {
                    string opt = args[i].Substring(2);
                    string val = null;
                    if (opt.Contains("="))
                    {
                        val = opt.Substring(opt.IndexOf("=") + 1);
                        opt = opt.Remove(opt.IndexOf("="));
                    }

                    // Handle argument
                    switch (opt)
                    {
                        case "load-mod-from":
                            {
                                if (val == null)
                                {
                                    if (i + 1 < args.Length)
                                        val = args[i + 1];
                                    else
                                        break;
                                    i++;
                                }
                                LogDebug("Queued mod loading from command line, path: " + val);
                                externalMods.Add(val);
                                break;
                            }
                    }
                    if (opt.StartsWith("debug-mod-assemblies:"))
                    {
                        string id = opt.Substring("debug-mod-assemblies:".Length);
                        if (val == null)
                        {
                            if (i + 1 < args.Length)
                                val = args[i + 1];
                            else
                                continue;
                            i++;
                        }
                        LogDebug("Queued debug mod assembly loading from command line, path: " + val);
                        if (!externalModAssemblies.ContainsKey(id))
                            externalModAssemblies[id] = new List<string>();
                        externalModAssemblies[id].Add(val);
                    }
                }
            }

            // Log and prepare
            LogInfo("Preparing to load mods...");
            Directory.CreateDirectory("FeralTweaks/mods");
            Directory.CreateDirectory("FeralTweaks/config");
            mods = new List<FeralTweaksMod>();

            // Discover packaged mods
            LogInfo("Finding mod packages...");
            foreach (FileInfo mod in new DirectoryInfo("FeralTweaks/mods").GetFiles("*.ftm"))
            {
                // Log
                LogDebug("Examining FTM package: " + mod.FullName);

                try
                {
                    // Attempt to read
                    ZipArchive zip = ZipFile.OpenRead(mod.FullName);
                    try
                    {
                        // Read mod manifest
                        ZipArchiveEntry manEnt = zip.GetEntry("mod.json");
                        if (manEnt == null)
                            throw new ArgumentException("Missing mod manifest JSON");
                        Stream strm = manEnt.Open();
                        StreamReader rd = new StreamReader(strm);
                        string json = rd.ReadToEnd();
                        rd.Close();

                        // Parse
                        FTMManifest man = JsonConvert.DeserializeObject<FTMManifest>(json);
                        if (man == null || man.id == null || man.version == null || man.timestamp == 0)
                            throw new ArgumentException("Inavlid mod manifest JSON");

                        // Check current version
                        long current = -1;
                        if (File.Exists("FeralTweaks/mods/" + man.id + "/version.info"))
                        {
                            // Load version
                            current = long.Parse(File.ReadAllText("FeralTweaks/mods/" + man.id + "/version.info"));
                        }
                        else if (Directory.Exists("FeralTweaks/mods/" + man.id))
                            throw new ArgumentException("Conflict: folder " + man.id + " exists and is not related to the mod package.");
                        LogInfo("Discovered package: " + man.id + ", version: " + man.version);

                        // Check
                        if (current != man.timestamp)
                        {
                            LogInfo("Updating " + man.id + " from local package...");

                            // Delete old
                            if (Directory.Exists("FeralTweaks/mods/" + man.id))
                            {
                                try
                                {
                                    Directory.Delete("FeralTweaks/mods/" + man.id, true);
                                }
                                catch
                                {
                                    Directory.Delete("FeralTweaks/mods/" + man.id);
                                }
                            }

                            // Write placeholder while we update
                            Directory.CreateDirectory("FeralTweaks/mods/" + man.id);
                            File.WriteAllText("FeralTweaks/mods/" + man.id + "/version.info", "-1");

                            // Begin update
                            foreach (ZipArchiveEntry ent in zip.Entries)
                            {
                                string nm = ent.FullName;
                                nm = nm.Replace("\\", "/");
                                while (nm.StartsWith("/"))
                                    nm = nm.Substring(1);

                                // Verify path
                                if (nm.StartsWith("clientmod/") && nm != "clientmod/")
                                {
                                    // Extract entry
                                    string outp = nm.Substring("clientmod/".Length);
                                    LogDebug("Updating file: " + outp + "...");

                                    // Check if a directory
                                    if (outp.EndsWith("/"))
                                        Directory.CreateDirectory("FeralTweaks/mods/" + man.id + "/" + outp);
                                    else
                                    {
                                        // Create parent
                                        Directory.CreateDirectory(Path.GetDirectoryName("FeralTweaks/mods/" + man.id + "/" + outp));

                                        // Write
                                        Stream source = ent.Open();
                                        Stream dest = File.OpenWrite("FeralTweaks/mods/" + man.id + "/" + outp);
                                        source.CopyTo(dest);
                                        source.Close();
                                        dest.Close();
                                    }
                                }
                            }

                            // Write version
                            File.WriteAllText("FeralTweaks/mods/" + man.id + "/version.info", man.timestamp.ToString());
                        }
                    }
                    finally
                    {
                        // Close
                        zip.Dispose();
                    }
                }
                catch (Exception e)
                {
                    LogError("Failed to parse FTM package: " + mod.FullName + ": " + e.GetType().FullName + (e.Message != null && e.Message != "" ? ": " + e.Message : ""));
                }
            }

            // Discover mods
            LogInfo("Discovering mods...");
            List<string> modDirs = new List<string>();
            foreach (FileInfo mod in new DirectoryInfo("FeralTweaks/mods").GetFiles("*.dll"))
            {
                LogDebug("Loading classic-style ftl mod: " + mod.FullName);
                LoadModFile(mod, null, null, modDirs);
            }
            foreach (DirectoryInfo dir in new DirectoryInfo("FeralTweaks/mods").GetDirectories())
            {
                if (File.Exists(dir.FullName + "/clientmod.json"))
                    continue; // Skip loading this
                LogDebug("Loading semi-structured ftl mod from: " + dir.FullName);

                // Load dlls from folder
                if (Directory.Exists(dir.FullName + "/assemblies"))
                {
                    // Load dlls
                    foreach (FileInfo mod in new DirectoryInfo(dir.FullName + "/assemblies").GetFiles("*.dll", SearchOption.AllDirectories))
                    {
                        LoadModFile(mod, null, dir.FullName, modDirs);
                    }
                }
                else
                {                    
                    // Load dlls
                    foreach (FileInfo mod in dir.GetFiles("*.dll", SearchOption.AllDirectories))
                    {
                        LoadModFile(mod, null, dir.FullName, modDirs);
                    }
                }
            }

            // Discover mods with json info
            List<ModInfo> structuredMods = new List<ModInfo>();
            foreach (DirectoryInfo dir in new DirectoryInfo("FeralTweaks/mods").GetDirectories())
            {
                if (!File.Exists(dir.FullName + "/clientmod.json"))
                    continue; // Skip loading this
                LoadStructuredMod(dir, structuredMods);
            }

            // Discover command line mods
            foreach (string mod in externalMods)
            {
                if (!Directory.Exists(mod))
                    continue; // Invalid
                LoadStructuredMod(new DirectoryInfo(mod), structuredMods);
            }

            // Load structured mods
            RunForMods(modInfo =>
            {
                // Load structured mod
                LogDebug("Loading structured mod assemblies for " + modInfo.id + "...");

                // Find directory
                DirectoryInfo dir = new DirectoryInfo(modInfo.path);

                // Load dlls from folder
                if (Directory.Exists(dir.FullName + "/assemblies"))
                {
                    // Load dlls
                    foreach (FileInfo mod in new DirectoryInfo(dir.FullName + "/assemblies").GetFiles("*.dll", SearchOption.AllDirectories))
                    {
                        LoadModFile(mod, modInfo, dir.FullName, modDirs);
                    }
                }
                else
                {
                    // Load dlls
                    foreach (FileInfo mod in dir.GetFiles("*.dll"))
                    {
                        LoadModFile(mod, modInfo, dir.FullName, modDirs);
                    }
                }

                // Find assemblies
                if (externalModAssemblies.ContainsKey(modInfo.id))
                {
                    // Add override assemblies
                    foreach (string path in externalModAssemblies[modInfo.id])
                    {
                        foreach (FileInfo mod in new DirectoryInfo(path).GetFiles("*.dll", SearchOption.AllDirectories))
                        {
                            LoadModFile(mod, modInfo, dir.FullName, modDirs);
                        }
                    }
                }
            }, structuredMods);

            // Load mods
            LogInfo("Discovered " + mods.Count + " mods.");
            LogInfo("Loading mods...");
            RunForMods(mod =>
            {
                LogInfo("Loading mod: " + mod.ID + ", version " + mod.Version + "...");
                mod.PreInit();
            });

            // Initialize mods
            LogInfo("Initializing mods...");
            RunForMods(mod =>
            {
                // Log
                LogInfo("Initializing mod: " + mod.ID);

                // Create config and cache folder
                Directory.CreateDirectory("FeralTweaks/config/" + mod.ID);
                Directory.CreateDirectory("FeralTweaks/cache/" + mod.ID);
                mod.ConfigDir = Path.GetFullPath("FeralTweaks/config/" + mod.ID);
                mod.CacheDir = Path.GetFullPath("FeralTweaks/cache/" + mod.ID);

                // Init
                mod.Init();
            });
        }

        private static void LoadStructuredMod(DirectoryInfo dir, List<ModInfo> structuredMods)
        {
            if (!File.Exists(dir.FullName + "/clientmod.json"))
                return; // Invalid
            LogDebug("Loading structured ftl mod from: " + dir.FullName + "...");

            // Load mod info
            try
            {
                LogDebug("Parsing mod manifest...");
                ModInfo mod = JsonConvert.DeserializeObject<ModInfo>(File.ReadAllText(dir.FullName + "/clientmod.json"));
                mod.path = dir.FullName;
                if (mod.id == null || mod.id == "" || mod.id.Replace(" ", "") == "" || mod.version == null || mod.version == "" || mod.version.Replace(" ", "") == "")
                    throw new ArgumentException();
                if (!Regex.Match(mod.id, "^[0-9A-Za-z_.]+$").Success)
                {
                    LogError("Failed to load mod " + mod.id + ": invalid mod ID.");
                    return;
                }
                LogDebug("Loading structured ftl mod manifest for: " + mod.id + "...");
                structuredMods.Add(mod);
            }
            catch
            {
                LogError("Failed to load mod " + dir.Name + ": invalid mod manifest json.");
            }
        }

        private static void LoadModFile(FileInfo mod, ModInfo structuredMod, string modBaseDir, List<string> modDirs)
        {
            try
            {
                // Add mod assembly folder to resolution if not done yet
                string dir = mod.DirectoryName;
                if (!modDirs.Contains(dir))
                {
                    // Add folder to assembly resolution
                    Dictionary<string, Assembly> assemblyCache = new Dictionary<string, Assembly>();
                    AppDomain.CurrentDomain.AssemblyResolve += (s, args) =>
                    {
                        // Attempt to resolve
                        AssemblyName nm = new AssemblyName(args.Name);

                        // Check cache
                        if (assemblyCache.ContainsKey(nm.FullName))
                            return assemblyCache[nm.FullName];

                        // Find file
                        Assembly res = null;
                        if (File.Exists(dir + "/" + nm.Name + ".dll"))
                        {
                            res = Assembly.LoadFrom(Path.GetFullPath(dir + "/" + nm.Name + ".dll"));
                        }
                        if (res != null)
                            assemblyCache[nm.FullName] = res;
                        return res;
                    };
                    modDirs.Add(dir);
                    LogDebug("Added " + dir + " to assembly resolution.");
                }

                // Load assembly
                LogDebug("Loading mod assembly: " + mod.FullName);
                Assembly asm = Assembly.Load(mod.Name.Remove(mod.Name.LastIndexOf(".dll")));

                // Find mod types
                LoadModsFrom(asm, structuredMod, modBaseDir);
            }
            catch (Exception e)
            {
                LogError("Failed to load mod file: " + mod.Name + ": " + e);
            }
        }

        private static void LoadModsFrom(Assembly asm, ModInfo structuredMod, string modBaseDir)
        {
            LogDebug("Finding mod types...");
            foreach (Type t in asm.GetTypes())
            {
                LogDebug("Verifying type: " + t.FullName + "...");
                if (t.IsAssignableTo(typeof(FeralTweaksMod)) && !t.IsAbstract)
                {
                    try
                    {
                        LogDebug("Loading mod type: " + t.FullName + "...");

                        // Attempt to load type
                        LogDebug("Finding parameterless constructor...");
                        ConstructorInfo constr = t.GetConstructor(new Type[0]);
                        if (constr == null)
                            throw new ArgumentException("No empty constructor");

                        // Instantiate
                        LogDebug("Creating mod instance...");
                        FeralTweaksMod inst = (FeralTweaksMod)constr.Invoke(new object[0]);
                        try
                        {
                            // Attempt to load mod instance
                            LogDebug("Setting up mod... Defining dependencies and loading logger...");
                            if (structuredMod != null)
                            {
                                inst._id = structuredMod.id;
                                inst._version = structuredMod.version;
                                inst._priority = structuredMod.loadPriority;
                                inst._conflicts.AddRange(structuredMod.conflictsWith);
                                inst._depends.AddRange(structuredMod.dependencies);
                                inst._optDepends.AddRange(structuredMod.optionalDependencies);
                                inst._loadBefore.AddRange(structuredMod.loadBefore);
                                inst._dependencyVersions = new Dictionary<string, string>(structuredMod.dependencyVersions);
                            }
                            inst.Initialize(modBaseDir);

                            // Find existing mod
                            LogDebug("Verifying mod ID...");
                            if (IsModLoaded(inst.ID))
                                throw new ArgumentException("Duplicate mod detected! ID: " + inst.ID + " was loaded twice!");
                            if (!Regex.Match(inst.ID, "^[0-9A-Za-z_.]+$").Success)
                                throw new ArgumentException("invalid mod ID.");
                            LogDebug("Discovered mod ID: " + inst.ID);
                            mods.Add(inst);
                        }
                        catch (Exception e)
                        {
                            LogError("Failed to load mod: " + inst.ID + ": " + e);
                            Environment.Exit(1);
                            return;
                        }
                    }
                    catch (Exception e)
                    {
                        LogError("Failed to load mod: " + t.FullName + ": " + e);
                        Environment.Exit(1);
                        return;
                    }
                }
            }
        }

        private static void RunForMods(Action<FeralTweaksMod> ex)
        {
            List<string> loading = new List<string>();
            foreach (FeralTweaksMod mod in mods.OrderBy(t => t._priority))
            {
                RunFor(mod, loading, ex);
            }
        }

        private static void RunFor(FeralTweaksMod mod, List<string> loading, Action<FeralTweaksMod> ex)
        {
            // Skip double loads
            if (loading.Contains(mod.ID))
                return;
            loading.Add(mod.ID);

            // Load dependencies first
            foreach (string dep in mod._depends)
            {
                if (!IsModLoaded(dep))
                {
                    // Failure
                    LogError("Unable to load mod " + mod.ID + ", missing dependency mod: " + dep);
                    Environment.Exit(1);
                    return;
                }

                // Verify version
                if (mod._dependencyVersions.ContainsKey(dep) && !VerifyVersionRequirement(GetLoadedMod(dep).Version, mod._dependencyVersions[dep]))
                {
                    // Failure
                    LogError("Unable to load mod " + mod.ID + ", dependency " + dep + " has the wrong version!");
                    Environment.Exit(1);
                    return;
                }

                // Run for dependency
                RunFor(GetLoadedMod(dep), loading, ex);
            }

            // Load optional dependencies after that
            foreach (string dep in mod._optDepends)
            {
                if (IsModLoaded(dep))
                {
                    // Verify version
                    if (mod._dependencyVersions.ContainsKey(dep) && !VerifyVersionRequirement(GetLoadedMod(dep).Version, mod._dependencyVersions[dep]))
                    {
                        // Failure
                        LogError("Unable to load mod " + mod.ID + ", dependency " + dep + " has the wrong version!");
                        Environment.Exit(1);
                        return;
                    }

                    // Run for dependency
                    RunFor(GetLoadedMod(dep), loading, ex);
                }
            }

            // Check conflicts
            foreach (string conflict in mod._conflicts)
            {
                if (IsModLoaded(conflict))
                {
                    // Failure
                    LogError("Mod conflict! Unable to load mod " + mod.ID + " as it conflicts with " + conflict + "!");
                    Environment.Exit(1);
                    return;
                }
            }

            // Check load-after of other mods
            foreach (FeralTweaksMod md in mods)
            {
                if (md._loadBefore.Contains(mod.ID))
                {
                    // Load this one first
                    RunFor(md, loading, ex);
                }
            }

            // Load
            ex(mod);
        }

        private static void RunForMods(Action<ModInfo> ex, List<ModInfo> mods)
        {
            List<string> loading = new List<string>();
            foreach (ModInfo mod in mods.OrderBy(t => t.loadPriority))
            {
                RunFor(mod, mods, loading, ex);
            }
        }

        private static void RunFor(ModInfo mod, List<ModInfo> mods, List<string> loading, Action<ModInfo> ex)
        {
            // Skip double loads
            if (loading.Contains(mod.id))
                return;
            loading.Add(mod.id);

            // Load dependencies first
            foreach (string dep in mod.dependencies)
            {
                if (!mods.Any(t => t.id == dep) && !IsModLoaded(dep))
                {
                    // Failure
                    LogError("Unable to load mod " + mod.id + ", missing dependency mod: " + dep);
                    Environment.Exit(1);
                    return;
                }

                // Verify version
                if (mod.dependencyVersions.ContainsKey(dep) && (
                    (IsModLoaded(dep) && !VerifyVersionRequirement(GetLoadedMod(dep).Version, mod.dependencyVersions[dep]))
                        || (mods.Any(t => t.id == dep) && !VerifyVersionRequirement(mods.Find(t => t.id == dep).version, mod.dependencyVersions[dep]))))
                {
                    // Failure
                    LogError("Unable to load mod " + mod.id + ", dependency " + dep + " has the wrong version!");
                    Environment.Exit(1);
                    return;
                }

                // Run for dependency
                if (mods.Any(t => t.id == dep))
                    RunFor(mods.Find(t => t.id == dep), mods, loading, ex);
            }

            // Load optional dependencies after that
            foreach (string dep in mod.optionalDependencies)
            {
                if (IsModLoaded(dep) || mods.Any(t => t.id == dep))
                {
                    // Verify version
                    if (mod.dependencyVersions.ContainsKey(dep) && (
                        (IsModLoaded(dep) && !VerifyVersionRequirement(GetLoadedMod(dep).Version, mod.dependencyVersions[dep]))
                            || (mods.Any(t => t.id == dep) && !VerifyVersionRequirement(mods.Find(t => t.id == dep).version, mod.dependencyVersions[dep]))))
                    {
                        // Failure
                        LogError("Unable to load mod " + mod.id + ", dependency " + dep + " has the wrong version!");
                        Environment.Exit(1);
                        return;
                    }

                    // Run for dependency
                    if (mods.Any(t => t.id == dep))
                        RunFor(mods.Find(t => t.id == dep), mods, loading, ex);
                }
            }

            // Check conflicts
            foreach (string conflict in mod.conflictsWith)
            {
                if (IsModLoaded(conflict) && mods.Any(t => t.id == conflict))
                {
                    // Failure
                    LogError("Mod conflict! Unable to load mod " + mod.id + " as it conflicts with " + conflict + "!");
                    Environment.Exit(1);
                    return;
                }
            }

            // Check load-after of other mods
            foreach (ModInfo md in mods)
            {
                if (md.loadBefore.Contains(mod.id))
                {
                    // Load this one first
                    RunFor(md, mods, loading, ex);
                }
            }

            // Load
            ex(mod);
        }

        private static bool VerifyVersionRequirement(string version, string versionCheck)
        {
            foreach (string filterRaw in versionCheck.Split("||"))
            {
                string filter = filterRaw.Trim();
                if (VerifyVersionRequirementPart(version, filter))
                    return true;
            }
            return false;
        }

        private static bool VerifyVersionRequirementPart(string version, string versionCheck)
        {
            // Handle versions
            foreach (string filterRaw in versionCheck.Split("&"))
            {
                string filter = filterRaw.Trim();

                // Verify filter string
                if (filter.StartsWith("!="))
                {
                    // Not equal
                    if (version == filter.Substring(2))
                        return false;
                }
                else if (filter.StartsWith("=="))
                {
                    // Equal to
                    if (version != filter.Substring(2))
                        return false;
                }
                else if (filter.StartsWith(">="))
                {
                    int[] valuesVersionCurrent = parseVersionValues(version);
                    int[] valuesVersionCheck = parseVersionValues(filter.Substring(2));

                    // Handle each
                    for (int i = 0; i < valuesVersionCheck.Length; i++)
                    {
                        int val = valuesVersionCheck[i];

                        // Verify lengths
                        if (i > valuesVersionCurrent.Length)
                            break;

                        // Verify value
                        if (valuesVersionCurrent[i] < val)
                            return false;
                    }
                }
                else if (filter.StartsWith("<="))
                {
                    int[] valuesVersionCurrent = parseVersionValues(version);
                    int[] valuesVersionCheck = parseVersionValues(filter.Substring(2));

                    // Handle each
                    for (int i = 0; i < valuesVersionCheck.Length; i++)
                    {
                        int val = valuesVersionCheck[i];

                        // Verify lengths
                        if (i > valuesVersionCurrent.Length)
                            break;

                        // Verify value
                        if (valuesVersionCurrent[i] > val)
                            return false;
                    }
                }
                else if (filter.StartsWith(">"))
                {
                    int[] valuesVersionCurrent = parseVersionValues(version);
                    int[] valuesVersionCheck = parseVersionValues(filter.Substring(1));

                    // Handle each
                    for (int i = 0; i < valuesVersionCheck.Length; i++)
                    {
                        int val = valuesVersionCheck[i];

                        // Verify lengths
                        if (i > valuesVersionCurrent.Length)
                            break;

                        // Verify value
                        if (valuesVersionCurrent[i] <= val)
                            return false;
                    }
                }
                else if (filter.StartsWith("<"))
                {
                    int[] valuesVersionCurrent = parseVersionValues(version);
                    int[] valuesVersionCheck = parseVersionValues(filter.Substring(1));

                    // Handle each
                    for (int i = 0; i < valuesVersionCheck.Length; i++)
                    {
                        int val = valuesVersionCheck[i];

                        // Verify lengths
                        if (i > valuesVersionCurrent.Length)
                            break;

                        // Verify value
                        if (valuesVersionCurrent[i] >= val)
                            return false;
                    }
                }
                else
                {
                    // Equal to
                    if (version != filter)
                        return false;
                }
            }

            // Valid
            return true;
        }

        private static int[] parseVersionValues(string version)
        {
            List<int> values = new List<int>();

            // Parse version string
            string buffer = "";
            foreach (char ch in version)
            {
                if (ch == '-' || ch == '.')
                {
                    // Handle segment
                    HandleSegment(buffer);
                    buffer = "";
                }
                else
                {
                    // Add to segment buffer
                    buffer += ch;
                }
            }
            if (buffer != "")
                HandleSegment(buffer);
            void HandleSegment(string segment)
            {
                if (segment == "")
                    return;

                // Check if its a number
                if (Regex.Match(segment, "^[0-9]+$").Success)
                {
                    // Add value
                    try
                    {
                        values.Add(int.Parse(segment));
                    }
                    catch
                    {
                        // ... okay... add first char value instead
                        values.Add((int)segment[0]);
                    }
                }
                else
                {
                    // Check if its a full word and doesnt contain numbers
                    if (Regex.Match(segment, "^[^0-9]+$").Success)
                    {
                        // It is, add first char value
                        values.Add((int)segment[0]);
                    }
                    else
                    {
                        // Add each value
                        foreach (char ch in segment)
                            values.Add((int)ch);
                    }
                }
            }

            return values.ToArray();
        }
    }
}
