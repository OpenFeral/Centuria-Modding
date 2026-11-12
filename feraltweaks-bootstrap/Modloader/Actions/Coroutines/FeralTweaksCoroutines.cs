using Il2CppInterop.Runtime;
using Il2CppInterop.Runtime.Injection;
using Il2CppInterop.Runtime.Runtime;
using Il2CppSystem.Collections;

namespace FeralTweaks.Actions
{
    /// <summary>
    /// Coroutine injection system, usage example:
    /// 
    /// <code>
    /// [HarmonyPostfix]
    /// [HarmonyPatch(typeof(ObjectExample), "RunCoroutine")]
    /// private static void RunCoroutine(ObjectExample __instance, ref Il2CppSystem.Collections.IEnumerator __result)
    /// {
    ///     __result = FeralTweaksCoroutines.InjectAtStart(__result, FeralTweaksCoroutines.CreateNew(builder => 
    ///     {
    ///          builder.OnFrame(builder.Execute(() => 
    ///          {
    ///             __instance.SomeCall();
    ///          })).OnNextFrame(builder.Execute(() =>
    ///          {
    ///             __instance.SomeOtherCall();
    ///          }));
    ///     });
    /// }
    /// </code> 
    /// </summary>
    public static class FeralTweaksCoroutines
    {
        private static bool _inited;
        private static void Init()
        {
            if (_inited)
                return;
            _inited = true;
            Il2CppInterfaceCollection col = new Il2CppInterfaceCollection(new System.Type[] { typeof(IEnumerator) });
            ClassInjector.RegisterTypeInIl2Cpp<FTCoroutine>(new RegisterTypeOptions()
            {
                Interfaces = col
            });
        }

        private static void CheckSafety()
        {
            if (FeralTweaksActions.unityThread == null || FeralTweaksActions.unityThread.ManagedThreadId != System.Environment.CurrentManagedThreadId)
            {
                throw new System.InvalidOperationException("Unable to safely call FeralTweaksCoroutines from non-il2cpp thread");
            }
        }

        /// <summary>
        /// Injects a coroutine at the start of another coroutine
        /// </summary>
        /// <param name="coroutine">Coroutine to inject at the start of the target</param>
        /// <param name="target">Target coroutine</param>
        /// <returns>Altered coroutine</returns>
        public static IEnumerator InjectAtHead(IEnumerator coroutine, IEnumerator target)
        {
            CheckSafety();
            Init();
            return CreateNew(t =>
            {
                t.ExecuteCoroutine(coroutine);
                t.ExecuteCoroutine(target);
            });
        }

        /// <summary>
        /// Injects a coroutine at the end of another coroutine
        /// </summary>
        /// <param name="coroutine">Coroutine to inject at the end of the target</param>
        /// <param name="target">Target coroutine</param>
        /// <returns>Altered coroutine</returns>
        public static IEnumerator InjectAtTail(IEnumerator coroutine, IEnumerator target)
        {
            CheckSafety();
            Init();
            return CreateNew(t =>
            {
                t.ExecuteCoroutine(target);
                t.ExecuteCoroutine(coroutine);
            });
        }

        /// <summary>
        /// Injects a coroutine at the start of another coroutine
        /// </summary>
        /// <param name="coroutine">Coroutine to inject at the start of the target</param>
        /// <param name="target">Target coroutine</param>
        /// <returns>Altered coroutine</returns>
        public static IEnumerator InjectAtHead(System.Collections.IEnumerator coroutine, IEnumerator target)
        {
            CheckSafety();
            Init();
            return CreateNew(t =>
            {
                t.ExecuteCoroutine(coroutine);
                t.ExecuteCoroutine(target);
            });
        }

        /// <summary>
        /// Injects a coroutine at the end of another coroutine
        /// </summary>
        /// <param name="coroutine">Coroutine to inject at the end of the target</param>
        /// <param name="target">Target coroutine</param>
        /// <returns>Altered coroutine</returns>
        public static IEnumerator InjectAtTail(System.Collections.IEnumerator coroutine, IEnumerator target)
        {
            CheckSafety();
            Init();
            return CreateNew(t =>
            {
                t.ExecuteCoroutine(target);
                t.ExecuteCoroutine(coroutine);
            });
        }

        /// <summary>
        /// Creates a new coroutine
        /// </summary>
        /// <param name="func">Coroutine initializer</param>
        /// <returns>Altered coroutine</returns>
        public static IEnumerator CreateNew(System.Action<FTCoroutine.CoroutineBuilder> func)
        {
            CheckSafety();
            Init();
            FTCoroutine.CoroutineBuilder b = new FTCoroutine.CoroutineBuilder();
            func(b);
            return CastFT(new FTCoroutine(b));
        }

        /// <summary>
        /// Creates a new coroutine from a managed coroutine
        /// </summary>
        /// <param name="coroutine">Coroutine instance</param>
        /// <returns>Altered coroutine</returns>
        public static IEnumerator CreateNew(System.Collections.IEnumerator coroutine)
        {
            CheckSafety();
            Init();
            FTCoroutine.CoroutineBuilder b = new FTCoroutine.CoroutineBuilder();
            b.ExecuteCoroutine(coroutine);
            return CastFT(new FTCoroutine(b));
        }

        internal static IEnumerator CastFT(FTCoroutine coroutine)
        {
            return new IEnumerator(coroutine.Pointer);
        }
    }
}