using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using AssetRipper.VersionUtilities;
using FeralTweaks;
using FeralTweaks.Logging;
using FeralTweaksBootstrap.Detour;
using HarmonyLib.Public.Patching;
using Il2CppDumper;
using Il2CppInterop.Common;
using Il2CppInterop.Generator;
using Il2CppInterop.Generator.Runners;
using Il2CppInterop.HarmonySupport;
using Il2CppInterop.Runtime.Injection;
using Il2CppInterop.Runtime.InteropTypes;
using Il2CppInterop.Runtime.Startup;
using Newtonsoft.Json;

namespace FeralTweaksBootstrap
{
    public static class Bootstrap
    {
        public const string VERSION = "v1.5-alpha-a4";
        private static Il2CppInteropRuntime runtime;
        private static RuntimeInvokeDetourContainer runtimeInvokeDetour;
        private static string GameAssemblyPath;
        private static Logger logger;
        internal static bool loaderReady;
        internal static bool logUnityToFile;
        internal static bool logUnityToConsole = true;
        internal static bool showConsole = false;

        private class ModInfo
        {
            public string id;
            public string version;

            public int loadPriority = 0;
            public List<string> dependencies = new List<string>();
            public List<string> optionalDependencies = new List<string>();
            public List<string> conflictsWith = new List<string>();
            public List<string> loadBefore = new List<string>();
            public Dictionary<string, string> dependencyVersions = new Dictionary<string, string>();
        }

        public static bool DebugLogging
        {
            get
            {
                return Logger.GlobalLogLevel >= LogLevel.DEBUG;
            }
        }

        public static Logger Logger
        {
            get
            {
                return logger;
            }
        }

        /// <summary>
        /// Assembly resolution hooks
        /// </summary>
        /// <param name="assemblyName">Assembly name</param>
        /// <param name="requestingAssembly">The assembly whose dependencies are being resolved</param>
        /// <returns>Assembly or null</returns>
        public delegate Assembly AssemblyResolutionHookHandler(AssemblyName assemblyName, Assembly requestingAssembly);

        /// <summary>
        /// Assembly resolution hooks
        /// </summary>
        public static event AssemblyResolutionHookHandler ResolveAssembly;

        public static void LogDebug(string message)
        {
            if (logger == null && DebugLogging)
                Console.WriteLine("[" + DateTime.Now.ToString("HH:mm:ss") + " DEBUG] [Preloader] " + message);
            else if (logger != null)
                logger.Debug(message);
        }

        public static void LogTrace(string message)
        {
            if (logger == null && DebugLogging)
                Console.WriteLine("[" + DateTime.Now.ToString("HH:mm:ss") + " TRACE] [Preloader] " + message);
            else if (logger != null)
                logger.Trace(message);
        }

        public static void LogInfo(string message)
        {
            if (logger == null)
                Console.WriteLine("[" + DateTime.Now.ToString("HH:mm:ss") + "  INFO] [Preloader] " + message);
            else
                logger.Info(message);
        }

        public static void LogWarn(string message)
        {
            if (logger == null)
                Console.Error.WriteLine("[" + DateTime.Now.ToString("HH:mm:ss") + "  WARN] [Preloader] " + message);
            else
                logger.Warn(message);
        }

        public static void LogError(string message)
        {
            if (logger == null)
                Console.Error.WriteLine("[" + DateTime.Now.ToString("HH:mm:ss") + " ERROR] [Preloader] " + message);
            else
                logger.Error(message);
        }

        public static void LogFatal(string message)
        {
            if (logger == null)
                Console.Error.WriteLine("[" + DateTime.Now.ToString("HH:mm:ss") + " FATAL] [Preloader] " + message);
            else
                logger.Fatal(message);
        }

        public static void Start()
        {
            // Preprare
            Directory.CreateDirectory("FeralTweaks/cache");
            Directory.CreateDirectory("FeralTweaks/logs");

            // Set up logging
            logger = Logger.GetLogger("Preloader");

            // Log
            LogInfo("Preparing...");
            LogInfo("Binding exception logger...");
            AppDomain.CurrentDomain.UnhandledException += (sender, args) =>
            {
                // Log
                LogError("Caught an unhandled exception! Thread name: " + Thread.CurrentThread.Name + "\nException: " + args.ExceptionObject + ":\n" + ((Exception)args.ExceptionObject).StackTrace);
            };

            // Handle arguments
            LogInfo("Processing arguments...");
            bool dumpOnly = false;
            bool loadMods = false;
            bool regenerateAssemblies = false;
            string[] args = Environment.GetCommandLineArgs();

            // Packaging
            string packageSource = null;
            string packageOutput = null;
            string packageVersion = null;
            string packageID = null;
            bool packageOutputOverwrite = false;
            Dictionary<string, string> packageIncludeSources = new Dictionary<string, string>();
            List<string> packageIncludeSourceZips = new List<string>();
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
                    LogDebug("Handling argument: " + opt);

                    // Handle argument                    
                    switch (opt)
                    {
                        case "wait-for-debugger":
                            {
                                while (!Debugger.IsAttached)
                                    Thread.Sleep(100);
                                break;
                            }
                        case "help":
                            {
                                // Show help

                                // Header
                                LogInfo("");
                                LogInfo("");
                                string msg = "FeralTweaksLoader (FTL), Preloader " + VERSION + ", FTL " + FeralTweaksLoader.VERSION;
                                string longest = "                                              of fat mod packages, such as universal packages containing both server and client code as long as the server can    ";
                                string ln = "";
                                for (int i2 = 0; i2 < (msg.Length > longest.Length ? msg.Length : longest.Length); i2++)
                                    ln += "-";
                                LogInfo(msg);
                                LogInfo(ln);
                                LogInfo("");

                                // Help page
                                LogInfo("  Preloader arguments:");
                                LogInfo("    --dry-run                              -  instructs FTL to not do anything apart from early-load actions");
                                LogInfo("    --dryrun-load-mods                     -  same as dry-run however mod are also preloaded");
                                LogInfo("    --regenerate-interop-assemblies        -  regenerates the interop assembly cache even if it exists");
                                LogInfo("    --show-console                         -  attaches a system console to the game (windows only)");
                                LogInfo("    --debug-log                            -  enables debug logging in the loader, preloader and mods");
                                LogInfo("    --log-level <level>                    -  assigns the log level (eg. debug, trace, info, warn, error, fatal, quiet)");
                                LogInfo("    --console-log-level <level>            -  assigns the console log level (eg. debug, trace, info, warn, error, fatal, quiet)");
                                LogInfo("    --log-unity-to-file                    -  logs unity logs to a file");
                                LogInfo("");
                                LogInfo("  Modloader arguments:");
                                LogInfo("    --load-mod-from \"<path>\"               -  instructs FTL to load a structured mod from the specified folder path");
                                LogInfo("    --debug-mod-assemblies:<id> \"<path>\"   -  instructs FTL to load assemblies from the given folder path for the given mod ID");
                                LogInfo("");
                                LogInfo("  Mod packaging arguments:");
                                LogInfo("    --build-package \"<path>\"               -  instructs FTL to build a mod package for the given structured mod folder");
                                LogInfo("    --package-output \"<path>\"              -  changes the output path for mod packages (can only be used after build-package)");
                                LogInfo("    --force-overwrite                      -  instructs FTL to overwrite existing packages during build (can only be used after build-package)");
                                LogInfo("    --package-include \"<path>\"             -  instructs FTL to include directories in the mod package during build (can only be used after build-package)");
                                LogInfo("                                              if the specified path is a zip or jar file, FTL will merge the contents of it instead, this allows for creation");
                                LogInfo("                                              of fat mod packages, such as universal packages containing both server and client code as long as the server can");
                                LogInfo("                                              accept FTM files.");
                                LogInfo("    --package-include-assemblies \"<path>\"  -  instructs FTL to include assemblies from the given directory during package build");

                                // Footer
                                LogInfo("");
                                LogInfo(ln);
                                Environment.Exit(0);
                                break;
                            }
                        case "console-log-level":
                            {
                                if (val == null)
                                {
                                    if (i + 1 < args.Length)
                                        val = args[i + 1];
                                    else
                                        break;
                                    i++;
                                }

                                // Handle log level
                                switch (val.ToLower())
                                {
                                    case "debug":
                                        Logger.GlobalConsoleLogLevel = LogLevel.DEBUG;
                                        break;
                                    case "trace":
                                        Logger.GlobalConsoleLogLevel = LogLevel.TRACE;
                                        break;
                                    case "info":
                                    case "information":
                                        Logger.GlobalConsoleLogLevel = LogLevel.INFO;
                                        break;
                                    case "warnings":
                                    case "warning":
                                    case "warn":
                                        Logger.GlobalConsoleLogLevel = LogLevel.WARN;
                                        break;
                                    case "errors":
                                    case "error":
                                        Logger.GlobalConsoleLogLevel = LogLevel.ERROR;
                                        break;
                                    case "fatal":
                                        Logger.GlobalConsoleLogLevel = LogLevel.FATAL;
                                        break;
                                    case "silent":
                                    case "none":
                                    case "nothing":
                                    case "quiet":
                                        Logger.GlobalConsoleLogLevel = LogLevel.QUIET;
                                        break;
                                }

                                break;
                            }
                        case "log-level":
                            {
                                if (val == null)
                                {
                                    if (i + 1 < args.Length)
                                        val = args[i + 1];
                                    else
                                        break;
                                    i++;
                                }

                                // Handle log level
                                switch (val.ToLower())
                                {
                                    case "debug":
                                        Logger.GlobalLogLevel = LogLevel.DEBUG;
                                        break;
                                    case "trace":
                                        Logger.GlobalLogLevel = LogLevel.TRACE;
                                        break;
                                    case "info":
                                    case "information":
                                        Logger.GlobalLogLevel = LogLevel.INFO;
                                        break;
                                    case "warnings":
                                    case "warning":
                                    case "warn":
                                        Logger.GlobalLogLevel = LogLevel.WARN;
                                        break;
                                    case "errors":
                                    case "error":
                                        Logger.GlobalLogLevel = LogLevel.ERROR;
                                        break;
                                    case "fatal":
                                        Logger.GlobalLogLevel = LogLevel.FATAL;
                                        break;
                                    case "silent":
                                    case "none":
                                    case "nothing":
                                    case "quiet":
                                        Logger.GlobalLogLevel = LogLevel.QUIET;
                                        break;
                                }

                                break;
                            }
                        case "log-unity-to-file":
                            {
                                logUnityToFile = true;
                                break;    
                            }
                        case "debug-log":
                            {
                                Logger.GlobalLogLevel = LogLevel.DEBUG;
                                break;
                            }
                        case "dryrun-load-mods":
                            {
                                loadMods = true;
                                dumpOnly = true;
                                break;
                            }
                        case "show-console":
                            {
                                showConsole = true;
                                break;
                            }
                        case "dry-run":
                            {
                                dumpOnly = true;
                                break;
                            }
                        case "regenerate-interop-assemblies":
                            {
                                regenerateAssemblies = true;
                                break;
                            }
                        case "build-package":
                            {
                                if (val == null)
                                {
                                    if (i + 1 < args.Length)
                                        val = args[i + 1];
                                    else
                                        break;
                                    i++;
                                }

                                // Build previous
                                if (packageSource != null)
                                    BuildPackage();

                                // Assign
                                LogDebug("Processing package build command...");
                                LogDebug("Source package: " + val);
                                packageSource = val;
                                packageOutputOverwrite = false;
                                packageIncludeSources.Clear();

                                // Verify package
                                if (File.Exists(val + "/clientmod.json"))
                                {
                                    ModInfo mod = JsonConvert.DeserializeObject<ModInfo>(File.ReadAllText(val + "/clientmod.json"));
                                    if (mod.id == null || mod.id == "" || mod.id.Replace(" ", "") == "" || mod.version == null || mod.version == "" || mod.version.Replace(" ", "") == "")
                                        throw new ArgumentException();
                                    if (!Regex.Match(mod.id, "^[0-9A-Za-z_.]+$").Success)
                                    {
                                        LogError("Failed to load mod manifest for " + Path.GetFullPath(val) + ": invalid mod ID.");
                                        Environment.Exit(1);
                                    }
                                    LogDebug("ID: " + mod.id);
                                    LogDebug("Version: " + mod.version);

                                    // Load arguments
                                    packageID = mod.id;
                                    packageVersion = mod.version;
                                    packageOutput = "FeralTweaks/mods/" + mod.id + ".ftm";
                                    Directory.CreateDirectory("FeralTweaks/mods");
                                }
                                else
                                {
                                    LogError("Invalid mod folder: " + val);
                                    Environment.Exit(1);
                                }

                                break;
                            }
                        case "force-overwrite":
                            {
                                // Check
                                if (packageSource == null)
                                {
                                    LogError("No sources specified for '--package-output', please use '--build-package' before any other package arguments.");
                                    Environment.Exit(1);
                                }
                                packageOutputOverwrite = true;
                                LogDebug("Overwriting existing package files is now allowed.");
                                break;
                            }
                        case "package-include-assemblies":
                            {
                                if (val == null)
                                {
                                    if (i + 1 < args.Length)
                                        val = args[i + 1];
                                    else
                                        break;
                                    i++;
                                }

                                // Check
                                if (packageSource == null)
                                {
                                    LogError("No sources specified for '--package-output', please use '--build-package' before any other package arguments.");
                                    Environment.Exit(1);
                                }

                                // Verify folder
                                if (!Directory.Exists(val))
                                {
                                    LogWarn("Invalid package assembly source path: " + val + ": does not exist.");
                                    break;
                                }

                                // Scan directory
                                scanFiles(new DirectoryInfo(val), "clientmod/assemblies/");
                                void scanFiles(DirectoryInfo src, string prefix)
                                {
                                    foreach (DirectoryInfo dir in src.GetDirectories())
                                    {
                                        packageIncludeSources[prefix + dir.Name + "/"] = null;
                                        scanFiles(dir, prefix + dir.Name + "/");
                                    }
                                    foreach (FileInfo file in src.GetFiles("*.dll"))
                                    {
                                        packageIncludeSources[prefix + file.Name] = file.FullName;
                                        LogDebug("Queued file for pacakge: " + prefix + file.Name);
                                    }
                                }

                                break;
                            }
                        case "package-include":
                            {
                                if (val == null)
                                {
                                    if (i + 1 < args.Length)
                                        val = args[i + 1];
                                    else
                                        break;
                                    i++;
                                }

                                // Check
                                if (packageSource == null)
                                {
                                    LogError("No sources specified for '--package-output', please use '--build-package' before any other package arguments.");
                                    Environment.Exit(1);
                                }

                                // Verify file
                                if (File.Exists(val) || Directory.Exists(val))
                                {
                                    // Check if file and if its a zip
                                    bool sourceIsZip = false;
                                    if (File.Exists(val))
                                    {
                                        try
                                        {
                                            // Check if zip

                                            // Open stream
                                            Stream strm = File.OpenRead(val);

                                            // Check first four bytes
                                            if (strm.ReadByte() == 0x50 && strm.ReadByte() == 0x4b)
                                            {
                                                // Check next byte
                                                int b = strm.ReadByte();
                                                switch (b)
                                                {
                                                    case 0x03:
                                                        {
                                                            // Next should be 0x04
                                                            if (strm.ReadByte() == 0x04)
                                                                sourceIsZip = true;
                                                            break;
                                                        }
                                                    case 0x05:
                                                        {
                                                            // Next should be 0x06
                                                            if (strm.ReadByte() == 0x06)
                                                                sourceIsZip = true;
                                                            break;
                                                        }
                                                    case 0x07:
                                                        {
                                                            // Next should be 0x08
                                                            if (strm.ReadByte() == 0x08)
                                                                sourceIsZip = true;
                                                            break;
                                                        }
                                                }
                                            }

                                            // Close
                                            strm.Close();
                                        }
                                        catch
                                        {
                                            // Not a zip
                                        }
                                    }

                                    // Handle result
                                    if (sourceIsZip)
                                    {
                                        // Add to zip sources
                                        packageIncludeSourceZips.Add(val);
                                        LogDebug("Queued zip merge for package: " + val);
                                    }
                                    else
                                    {
                                        if (File.Exists(val))
                                        {
                                            // File

                                            // Needs to be a relative path
                                            if (Path.IsPathFullyQualified(val))
                                            {
                                                // Invalid
                                                LogWarn("Invalid source file: " + val + ": not a zip file and not relative to the current directory, unable to add to the destination FTM package.");
                                            }
                                            else
                                            {
                                                // Add it
                                                if (val.StartsWith("." + Path.DirectorySeparatorChar) || val.StartsWith("." + Path.AltDirectorySeparatorChar))
                                                    val = val.Substring(2);
                                                packageIncludeSources[val] = Path.GetFullPath(val);
                                                LogDebug("Queued file for pacakge: " + val);
                                            }
                                        }
                                        else
                                        {
                                            // Directory

                                            // Scan directory
                                            scanFiles(new DirectoryInfo(val), "");
                                            void scanFiles(DirectoryInfo src, string prefix)
                                            {
                                                foreach (DirectoryInfo dir in src.GetDirectories())
                                                {
                                                    scanFiles(dir, prefix + dir.Name + "/");
                                                }
                                                foreach (FileInfo file in src.GetFiles())
                                                {
                                                    packageIncludeSources[prefix + file.Name] = file.FullName;
                                                    LogDebug("Queued file for pacakge: " + prefix + file.Name);
                                                }
                                            }
                                        }
                                    }
                                }
                                else
                                {
                                    LogWarn("Invalid package source path: " + val + ": does not exist.");
                                }
                                break;
                            }
                        case "package-output":
                            {
                                if (val == null)
                                {
                                    if (i + 1 < args.Length)
                                        val = args[i + 1];
                                    else
                                        break;
                                    i++;
                                }

                                // Check
                                if (packageSource == null)
                                {
                                    LogError("No sources specified for '--package-output', please use '--build-package' before any other package arguments.");
                                    Environment.Exit(1);
                                }

                                // Verify path
                                try
                                {
                                    if (Directory.Exists(Path.GetDirectoryName(val)) || Path.GetDirectoryName(val) == "")
                                    {
                                        packageOutput = val;
                                        LogDebug("Package output path assigned: " + val);
                                    }
                                    else
                                        throw new ArgumentException();
                                }
                                catch
                                {
                                    LogError("Invalid package output file path: " + val);
                                    Environment.Exit(1);
                                }

                                break;
                            }

                            // TODO: launcher handoff log
                    }
                }
            }

            // Attach console
            if (showConsole && File.Exists("GameAssembly.dll"))
            {
                WindowsConsoleTools.Attach();
                try
                {
                    Console.Title = "FTL " + VERSION + " loading...";
                }
                catch { }
            }

            // Log load
            LogInfo("FeralTweaks Bootstrapper version " + VERSION + " loading...");

            // Build previous
            if (packageSource != null)
            {
                BuildPackage();
                Environment.Exit(0);
            }
            void BuildPackage()
            {
                // Check output
                if (File.Exists(packageOutput) && !packageOutputOverwrite)
                {
                    LogWarn("Aborting package build for " + packageOutput + ": file already exists!");
                    LogWarn("To continue anyways, add '--force-overwrite' to the command arguments.");
                    return;
                }

                // Prepare to build
                LogInfo("Building package...");
                LogInfo("Package source path: " + packageSource);
                LogInfo("Package output path: " + packageOutput);
                LogInfo("Creating output file...");
                if (File.Exists(packageOutput))
                    File.Delete(packageOutput);
                List<string> dirs = new List<string>();
                ZipArchive outp = ZipFile.Open(packageOutput, ZipArchiveMode.Create);

                // Write mod info
                Dictionary<string, ZipArchiveEntry> ents = new Dictionary<string, ZipArchiveEntry>();
                Stream strm = CreateOrOpen("mod.json").Open();
                Dictionary<string, object> man = new Dictionary<string, object>();
                man["id"] = packageID;
                man["version"] = packageVersion;
                man["timestamp"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                strm.Write(Encoding.UTF8.GetBytes(JsonConvert.SerializeObject(man)));
                strm.Close();
                LogDebug("Added mod.json");

                // Write source entries
                List<string> includedFiles = new List<string>();
                scanFiles(new DirectoryInfo(packageSource), "clientmod/");
                void scanFiles(DirectoryInfo src, string prefix)
                {
                    foreach (DirectoryInfo dir in src.GetDirectories())
                    {
                        if (!dirs.Contains(prefix + dir.Name + "/"))
                        {
                            // Add directory
                            CreateOrOpen(prefix + dir.Name + "/");
                            dirs.Add(prefix + dir.Name + "/");
                        }
                        if (packageIncludeSources.ContainsKey(prefix + dir.Name + "/"))
                            packageIncludeSources.Remove(prefix + dir.Name + "/");
                        includedFiles.Add(prefix + dir.Name + "/");
                        scanFiles(dir, prefix + dir.Name + "/");
                    }
                    foreach (FileInfo file in src.GetFiles())
                    {
                        // File
                        if (packageIncludeSources.ContainsKey(prefix + file.Name))
                            packageIncludeSources.Remove(prefix + file.Name);
                        Stream destS = CreateOrOpen(prefix + file.Name).Open();
                        FileStream sourceS = file.OpenRead();
                        sourceS.CopyTo(destS);
                        sourceS.Close();
                        destS.Close();
                        includedFiles.Add(prefix + file.Name);
                        LogDebug("Added " + prefix + file.Name);
                    }
                }

                // Write included zips

                foreach (string zip in packageIncludeSourceZips)
                {
                    LogDebug("Adding files from zip " + zip);

                    // Load
                    ZipArchive archive = ZipFile.OpenRead(zip);

                    // Go through each entry
                    foreach (ZipArchiveEntry entry in archive.Entries)
                    {
                        string ent = entry.FullName.Replace("\\", "/");
                        while (ent.StartsWith("/"))
                            ent = ent.Substring(1);

                        // Check
                        if (includedFiles.Contains(ent) || ent == "")
                            continue;
                        includedFiles.Add(ent);

                        // Handle
                        if (ent.EndsWith("/"))
                        {
                            // Folder
                            if (!dirs.Contains(ent))
                            {
                                // Add directory
                                CreateOrOpen(ent);
                                dirs.Add(ent);
                                includedFiles.Add(ent);
                            }
                        }
                        else
                        {
                            // File
                            Stream destS = CreateOrOpen(ent).Open();
                            Stream sourceS = entry.Open();
                            sourceS.CopyTo(destS);
                            sourceS.Close();
                            destS.Close();
                            LogDebug("Added " + ent);
                            includedFiles.Add(ent);
                        }
                    }
                    archive.Dispose();
                }

                // Write source files
                foreach ((string ent, string source) in packageIncludeSources)
                {
                    if (includedFiles.Contains(ent))
                        continue;
                    // Add each
                    if (ent.EndsWith("/"))
                    {
                        // Directory
                        if (!dirs.Contains(ent))
                        {
                            // Add directory
                            CreateOrOpen(ent);
                            dirs.Add(ent);
                            includedFiles.Add(ent);
                        }
                    }
                    else
                    {
                        // File
                        Stream destS = CreateOrOpen(ent).Open();
                        FileStream sourceS = File.OpenRead(source);
                        sourceS.CopyTo(destS);
                        sourceS.Close();
                        destS.Close();
                        LogDebug("Added " + ent);
                        includedFiles.Add(ent);
                    }
                }

                // Utility
                ZipArchiveEntry CreateOrOpen(string entName)
                {
                    if (!ents.ContainsKey(entName))
                        ents[entName] = outp.CreateEntry(entName);
                    return ents[entName];
                }

                // Finish
                outp.Dispose();
                LogInfo("Package build completed for " + packageOutput);
            }

            // Find game
            string game = "Fer.al";
            if (File.Exists("game.info"))
                game = File.ReadAllLines("game.info")[0]; // Override

            // Determine platform
            LogInfo("Determining platform...");
            PlatformType plat;
            if (File.Exists("GameAssembly.dll"))
                plat = PlatformType.WINDOWS;
            else if (File.Exists(game + ".app/Contents/Frameworks/GameAssembly.dylib"))
                plat = PlatformType.OSX;
            else
                plat = PlatformType.ANDROID;
            LogInfo("Platform: " + plat);

            // Build paths
            LogInfo("Resolving game paths...");
            string gameAssemblyPath = null;
            string dataPath = null;
            switch (plat)
            {
                case PlatformType.WINDOWS:
                    gameAssemblyPath = "GameAssembly.dll";
                    dataPath = game + "_Data";
                    break;
                case PlatformType.OSX:
                    gameAssemblyPath = game + ".app/Contents/Frameworks/GameAssembly.dylib";
                    dataPath = game + ".app/Contents/Resources/Data";
                    break;
                case PlatformType.ANDROID:
                    // TODO: idfk how to do this atm
                    LogError("Android is presently non-functional");
                    Environment.Exit(1);
                    break;
            }
            string il2cppMetadata = dataPath + "/il2cpp_data/Metadata/global-metadata.dat";
            LogInfo("Data path: " + dataPath);
            LogInfo("Assembly path: " + gameAssemblyPath);
            LogInfo("IL2CPP metadata path: " + il2cppMetadata);
            gameAssemblyPath = Path.GetFullPath(gameAssemblyPath);
            GameAssemblyPath = gameAssemblyPath;

            // Log
            LogInfo("Determining versions...");

            // Determine unity version
            byte[] data = File.ReadAllBytes(dataPath + "/Resources/unity_builtin_extra");
            string buffer = "";
            for (int i = 48; i < data.Length; i++)
            {
                if (data[i] == 0)
                    break;
                else
                    buffer += (char)data[i];
            }
            string unityVer = buffer;
            LogInfo("Unity version: " + unityVer);

            // Check if the game assemblies need to be updated
            if (!regenerateAssemblies)
            {
                string oldHash = "";
                if (File.Exists("FeralTweaks/cache/assemblies/current"))
                    oldHash = File.ReadAllText("FeralTweaks/cache/assemblies/current");

                // Compute hash
                string currentHash = "";
                LogInfo("Checking if the assembly cache is up-to-date...");
                FileStream strm = File.OpenRead(dataPath + "/globalgamemanagers");
                currentHash = string.Concat(SHA256.Create().ComputeHash(strm).Select(t => t.ToString("x2")));
                strm.Close();
                if (!oldHash.Equals(currentHash))
                {
                    LogInfo("Cache is out of date, regenerating assemblies...");
                    regenerateAssemblies = true;
                }
            }

            // Check unstrip folder
            bool unstrip = false;
            if (Directory.Exists("FeralTweaks/cache/unity"))
            {
                if (!File.Exists("FeralTweaks/cache/assemblies/unstripped"))
                {
                    LogInfo("Unity assemblies found, regenerating interop assemblies with unstripping enabled...");
                    regenerateAssemblies = true;
                }
                unstrip = true;
            }

            // Add to Cecil
            string trusted = (string)AppDomain.CurrentDomain.GetData("TRUSTED_PLATFORM_ASSEMBLIES");
            if (trusted == null)
                trusted = "";

            // Find assemblies
            foreach (FileInfo asm in new DirectoryInfo("CoreCLR").GetFiles("*.dll"))
                if (trusted == "")
                    trusted = asm.FullName;
                else
                    trusted += Path.PathSeparator + asm.FullName;

            // Add unity if needed
            if (unstrip)
            {
                // Find assemblies
                foreach (FileInfo asm in new DirectoryInfo("FeralTweaks/cache/unity").GetFiles("*.dll"))
                    if (trusted == "")
                        trusted = asm.FullName;
                    else
                        trusted += Path.PathSeparator + asm.FullName;
            }

            // Set data
            AppDomain.CurrentDomain.SetData("TRUSTED_PLATFORM_ASSEMBLIES", trusted);

            // Generate assemblies
            if (!File.Exists("FeralTweaks/cache/assemblies/complete") || regenerateAssemblies)
            {
                LogInfo("Dumping dummy assemblies...");

                // Delete old
                if (Directory.Exists("FeralTweaks/cache/dummy"))
                {
                    try
                    {
                        Directory.Delete("FeralTweaks/cache/dummy", true);
                    }
                    catch
                    {
                        Directory.Delete("FeralTweaks/cache/dummy");
                    }
                }

                // Set up and dump into memory
                LogInfo("Loading metadata...");

                //
                // Run il2cppdumper
                // Credits to Prefare, adapted for internal use by Zera
                //

                // Load metadata
                MemoryStream strm = new MemoryStream(File.ReadAllBytes(il2cppMetadata));
                Metadata md = new Metadata(strm);

                // Read binary
                LogInfo("Loading Il2Cpp binary...");
                byte[] binBytes = File.ReadAllBytes(gameAssemblyPath);
                uint magic = BitConverter.ToUInt32(binBytes, 0);
                MemoryStream binStrm = new MemoryStream(binBytes);
                Il2Cpp bin;

                // Process
                LogInfo("Processing binary type...");
                switch (magic)
                {
                    // NSO
                    case 0x304F534E:
                        {
                            NSO nso = new NSO(binStrm);
                            bin = nso.UnCompress();
                            break;
                        }

                    // PE
                    case 0x905A4D:
                        {
                            bin = new PE(binStrm);
                            break;
                        }

                    // ELF
                    case 0x464c457f:
                        {
                            if (binBytes[4] == 2)
                            {
                                // ELF64
                                bin = new Elf64(binStrm);
                            }
                            else
                            {
                                // ELF32
                                bin = new Elf(binStrm);
                            }
                            break;
                        }

                    // Mach-O 64-bits
                    case 0xFEEDFACF:
                        {
                            bin = new Macho64(binStrm);
                            break;
                        }

                    // Mach-O 32-bits
                    case 0xFEEDFACE:
                        {
                            bin = new Macho(binStrm);
                            break;
                        }

                    // Fail
                    default:
                        {
                            LogError("Invalid game assembly file, cannot process " + gameAssemblyPath + " due to its type not being supported!");
                            Environment.Exit(1);
                            return;
                        }
                }

                // Setup
                LogInfo("Prepared metadata, loaded binary. IL2CPP Version: " + md.Version);
                LogInfo("Configuring...");
                bin.SetProperties(md.Version, md.metadataUsagesCount);

                // Check dump
                if (bin.CheckDump())
                {
                    // Check
                    if (bin is ElfBase)
                    {
                        // Fail
                        LogError("Unable to automatically select dump address, unable to continue!");
                        Environment.Exit(1);
                        return;
                    }
                    bin.IsDumped = true;
                }

                // Search
                LogInfo("Searching...");
                try
                {
                    var flag = bin.PlusSearch(md.methodDefs.Count(x => x.methodIndex >= 0), md.typeDefs.Length, md.imageDefs.Length);
                    if (RuntimeInformation.IsOSPlatform(OSPlatform.Windows))
                    {
                        if (!flag && bin is PE)
                        {
                            LogInfo("Use custom PE loader");
                            bin = PELoader.Load(gameAssemblyPath);
                            bin.SetProperties(md.Version, md.metadataUsagesCount);
                            flag = bin.PlusSearch(md.methodDefs.Count(x => x.methodIndex >= 0), md.typeDefs.Length, md.imageDefs.Length);
                        }
                    }
                    if (!flag)
                    {
                        flag = bin.Search();
                    }
                    if (!flag)
                    {
                        flag = bin.SymbolSearch();
                    }
                    if (!flag)
                    {
                        LogError("Can't use auto mode to process file, unable to continue!");
                        Environment.Exit(1);
                        return;
                    }
                    if (bin.Version >= 27 && bin.IsDumped)
                    {
                        var typeDef = md.typeDefs[0];
                        var il2CppType = bin.types[typeDef.byvalTypeIndex];
                        md.ImageBase = il2CppType.data.typeHandle - md.header.typeDefinitionsOffset;
                    }
                }
                catch
                {
                    LogError("An unexpected error occured, unable to continue!");
                    Environment.Exit(1);
                    return;
                }
                
                // Generate dlls
                LogInfo("Generating dummy dlls...");
                Il2CppExecutor exec = new Il2CppExecutor(md, bin);
                Directory.CreateDirectory("FeralTweaks/cache/dummy");
                DummyAssemblyGenerator dummy = new DummyAssemblyGenerator(exec, true);
                foreach (var assembly in dummy.Assemblies)
                {
                    
                    LogInfo("Dumped " + assembly.MainModule.Name);
                    assembly.Write("FeralTweaks/cache/dummy/" + assembly.MainModule.Name);
                }
                LogInfo("Done!");

                // Clean up
                LogInfo("Cleaning...");
                strm.Close();
                binStrm.Close();

                // Delete old
                if (Directory.Exists("FeralTweaks/cache/assemblies"))
                {
                    try
                    {
                        Directory.Delete("FeralTweaks/cache/assemblies", true);
                    }
                    catch
                    {
                        Directory.Delete("FeralTweaks/cache/assemblies");
                    }
                }

                // Unhollow
                LogInfo("Unhollowing assemblies...");
                GeneratorOptions opts = new GeneratorOptions();
                opts.GameAssemblyPath = gameAssemblyPath;
                opts.Source = new AltDirCecilAssemblyResolver("FeralTweaks/cache/dummy").ToList();
                opts.OutputDir = "FeralTweaks/cache/assemblies";
                if (unstrip)
                    opts.UnityBaseLibsDir = "FeralTweaks/cache/unity";
                Il2CppInteropGenerator.Create(opts).AddLogger(new PreloaderLogger()).AddInteropAssemblyGenerator().Run();

                // Done
                File.Create("FeralTweaks/cache/assemblies/complete");
                if (unstrip && !File.Exists("FeralTweaks/cache/assemblies/unstripped"))
                    File.Create("FeralTweaks/cache/assemblies/unstripped");

                // Write hash
                FileStream strmI = File.OpenRead(dataPath + "/globalgamemanagers");
                string hash = string.Concat(SHA256.Create().ComputeHash(strmI).Select(t => t.ToString("x2")));
                strm.Close();
                File.WriteAllText("FeralTweaks/cache/assemblies/current", hash);
            }

            // Close if dump-only
            if (dumpOnly && !loadMods)
            {
                LogInfo("EXITING! Dry run finished!");
                Environment.Exit(0);
            }

            // Bind resolve
            LogInfo("Binding assembly resolution...");
            Dictionary<string, Assembly> assemblyCache = new Dictionary<string, Assembly>();
            AppDomain.CurrentDomain.AssemblyResolve += (s, args) =>
            {
                // Attempt to resolve
                AssemblyName nm = new AssemblyName(args.Name);

                // Resolve if needed
                Assembly res = null;
                if (ResolveAssembly != null)
                {
                    res = ResolveAssembly.Invoke(nm, args.RequestingAssembly);
                    if (res != null)
                        return res;
                }

                // Check cache
                if (assemblyCache.ContainsKey(nm.Name))
                    return assemblyCache[nm.Name];

                // Handle it ourselves
                if (File.Exists("FeralTweaks/cache/assemblies/" + nm.Name + ".dll"))
                {
                    res = Assembly.Load(File.ReadAllBytes("FeralTweaks/cache/assemblies/" + nm.Name + ".dll"));
                }
                else if (File.Exists("FeralTweaks/cache/unity/" + nm.Name + ".dll"))
                {
                    res = Assembly.Load(File.ReadAllBytes("FeralTweaks/cache/unity/" + nm.Name + ".dll"));
                }
                if (res != null)
                    assemblyCache[nm.Name] = res;
                return res;
            };

            // Set resolver
            LogInfo("Adding assembly resolver...");
            NativeLibrary.SetDllImportResolver(typeof(Il2CppInterop.Runtime.IL2CPP).Assembly, Resolver);

            // Create runtime
            LogInfo("Creating il2cpp interop runtime...");
            UnityVersion uver = UnityVersion.Parse(unityVer);
            runtime = Il2CppInteropRuntime.Create(new RuntimeConfiguration()
            {
                UnityVersion = new Version(uver.Major, uver.Minor, uver.Build),
                DetourProvider = new Il2CppDetourProvider()
            });

            // Load funchook for the current platform
            switch (plat)
            {
                case PlatformType.WINDOWS:
                    NativeLibrary.Load(Path.GetFullPath("funchook.dll"));
                    break;
                case PlatformType.OSX:
                    NativeLibrary.Load(Path.GetFullPath("libfunchook.dylib"));
                    break;
                case PlatformType.ANDROID:
                    // TODO: idfk how to do this atm
                    LogError("Android is presently non-functional");
                    Environment.Exit(1);
                    break;
            }

            // Add harmony support
            LogInfo("Adding harmony support...");
            runtime = runtime.AddHarmonySupport();

            // Add logger
            LogInfo("Adding logger...");
            runtime = runtime.AddLogger(new InteropLogger());

            // Start runtime
            LogInfo("Starting runtime...");
            runtime.Start();

            // Hook
            IntPtr handle = NativeLibrary.Load(gameAssemblyPath, typeof(Il2CppInteropRuntime).Assembly, null);
            IntPtr invokePtr = NativeLibrary.GetExport(handle, "il2cpp_runtime_invoke");
            runtimeInvokeDetour = new RuntimeInvokeDetourContainer();
            NativeDetours.CreateDetour(invokePtr, runtimeInvokeDetour);

            // Start
            LogInfo("Preloader finished!");
            LogInfo("Starting FeralTweaks loader...");
            StartLoader();

            // Exit if needed
            if (loadMods)
            {
                LogInfo("EXITING! Dry run finished!");
                Environment.Exit(0);
            }
        }

        private static IntPtr Resolver(string libraryName, Assembly assembly, DllImportSearchPath? searchPath)
        {
            if (libraryName == "GameAssembly")
                return NativeLibrary.Load(GameAssemblyPath, assembly, searchPath);
            return IntPtr.Zero;
        }

        private static void StartLoader()
        {
            loaderReady = true;
            FeralTweaksLoader.Start();
        }
    }
}
