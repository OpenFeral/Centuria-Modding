using System;
using System.Threading.Tasks;
using FeralTweaks.Actions;
using FeralTweaks.Managers;
using Il2CppInterop.Runtime.Attributes;
using Il2CppInterop.Runtime.Injection;
using Il2CppSystem.Collections;
using UnityEngine;

namespace feraltweaks.Patches.AssemblyCSharp
{
    public class TestManager : FeralTweaksManagerBase
    {
        static TestManager()
        {
            ClassInjector.RegisterTypeInIl2Cpp<TestBehaviour>();
        }

        public TestManager() : base(ClassInjector.DerivedConstructorPointer<TestManager>())
        {
            ClassInjector.DerivedConstructorBody(this);
        }

        public TestManager(nint pointer) : base(pointer)
        {
        }

        [ManagedBehaviourFTManager(typeof(TestManager))]
        public class TestBehaviour : FeralTweaksManagedBehaviour
        {
            public TestBehaviour() : base(ClassInjector.DerivedConstructorPointer<TestBehaviour>())
            {
                ClassInjector.DerivedConstructorBody(this);
            }

            public TestBehaviour(nint pointer) : base(pointer)
            {
            }

            public override void MAwake()
            {
                GetType();
            }

            public override void MOnEnable()
            {
                GetType();
            }

            public override void MStart()
            {
                GetType();
            }

            public override void MStartAfterLocal()
            {
                GetType();
            }

            public override void MOnDisable()
            {
                GetType();
            }

            public override void MOnDestroy()
            {
                GetType();
            }

            public override void MUpdate()
            {
                GetType();
            }
        }

        [FTManagerSetInstance]
        public static TestManager instance { get; internal set; }

        [FTManagerSetInstance]
        public static TestManager coreInstance { get; internal set; }

        [HideFromIl2Cpp]
        protected override void SetupLoadRules(LoadRuleBuilder ruleBuilder)
        {
            ruleBuilder.AddLoadFirstRule();
        }

        [HideFromIl2Cpp]
        protected override void SetupGameObject(GameObject gameObject)
        {
            // No need for any special gameobject stuff
        }

        [HideFromIl2Cpp]
        protected override void SetupBehaviourInterceptionRules(BehaviourInterceptionRuleBuilder ruleBuilder)
        {
            // ruleBuilder.AddInterceptOfManagerRule<WorldObjectManager>();
            // ruleBuilder.AddInterceptOfBehaviourTypeRule<FeralAudioEmitter>(FeralTweaksManagerBehaviourInterceptionMethod.TAKEOVER);
        }

        public override bool HasInitCoroutine()
        {
            return true;
        }

        public override IEnumerator InitCoroutine()
        {
            return FeralTweaksCoroutines.CreateNew(t =>
            {
                // t.OnFrame(t.Execute(() =>
                // {
                //     // Something to execute
                // }).OnComplete(result => t.Execute(() =>
                // {
                //     // Executed on complete
                // })).OnError(t.Execute(() =>
                // {
                //     // Executed on error 
                // })).Then(t.Execute(() =>
                // {
                //     // Executed on same frame
                // }))).OnNextFrame(t.Execute(() =>
                // {
                //     // On next frame
                // }));


                // t.OnNextFrame(t.AwaitPromise(() =>
                // {
                //     return FeralTweaksPromises.CreatePromise().GetPromise();
                // }).OnComplete(result => t.Execute(() =>
                // {

                // }).Then()));

                // // First phase: action
                // t.OnFrame(t.Execute(ctx =>
                // {
                //     ctx = ctx;

                //     FeralTweaksActions.EventQueue.Oneshot(() =>
                //     {
                //         ctx = ctx;
                //     }).OnComplete(() =>
                //     {
                //         // Handle
                //     }).OnError(() =>
                //     {
                //         // Error
                //     });

                //     // Create promise
                //     long time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                //     FeralTweaksPromiseController testPromise1 = FeralTweaksPromises.CreatePromise();
                //     testPromise1.GetPromise().OnComplete(() =>
                //     {
                //         long length = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - time;
                //         GetType();
                //     });
                //     testPromise1.CallComplete();

                //     time = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                //     FeralTweaksPromiseController<string> testPromise2 = FeralTweaksPromises.CreatePromise<string>();
                //     testPromise2.GetPromise().OnComplete(res =>
                //     {
                //         long length = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - time;
                //         res = res;
                //     });
                //     FeralTweaksActions.Async.Oneshot(() => testPromise2.CallComplete("test"));

                //     // Run a test code
                //     long time2 = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                //     FeralTweaksActions.Async.Oneshot(async () =>
                //     {
                //         FeralTweaksAction<string> func = FeralTweaksActions.Async.Oneshot<string>((ctx) =>
                //         {
                //             long length = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - time2;
                //             return "test";
                //         });
                //         string test = await func;
                //         test = test;
                //     }).AwaitComplete();

                //     // Run something async
                //     long time3 = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                //     FeralTweaksActions.Async.Oneshot<string>((ctx) =>
                //     {
                //         // Runs outside of unity
                //         // Say a webrequest
                //         return "test";
                //     }).OnComplete(result =>
                //     {
                //         // Do something with the result
                //         // OnComplete runs on unity, or on the event queue, it runs on the queue used previously
                //         long length = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - time3;
                //         result = result;
                //     }).AwaitComplete();

                //     // Test
                //     Action<int, string> ac = (test1, test) =>
                //     {
                //         Action callback = FeralTweaksCallbacks.CreateQueuedWrapper(() =>
                //         {
                //             test = test;
                //         });
                //         FeralTweaksActions.EventQueue.Oneshot(callback);
                //     };
                //     ac(1, "hi");

                //     // Create test
                //     GameObject test = new GameObject("test");
                //     test.AddComponent<TestBehaviour>();
                //     GameObject.DontDestroyOnLoad(test);
                //     test = test;

                //     // Test
                //     FeralTweaksActions.Unity.Oneshot(() =>
                //     {
                //         // Wait for core to init
                //         if (!Core.Loaded)
                //             return false;

                //         // Pull behaviours attached to test manager
                //         TestBehaviour[] behaviours = this.GetAllLinkedBehaviours<TestBehaviour>();
                //         behaviours = behaviours;

                //         // Finish
                //         return true;
                //     });

                //     // FIXME: remove manager
                // }, out CoroutineResultReference<Il2CppSystem.Object> ac1));

                // // Next phase: coroutine
                // t.OnFrame(t.ExecuteCoroutine(() => InitCoroutineCustom(ac1), out CoroutineResultReference<System.Collections.IEnumerator> ac2CR));

                // // Run promise
                // t.OnFrame(t.AwaitPromise(() => FetchDataAsync(), out CoroutineResultReference<FeralTweaksPromise<string>> promise).OnResult(() =>
                // {
                //     // Result
                // }));

                // // Await action
                // t.OnFrame(t.AwaitAction(() => FeralTweaksActions.Async.Oneshot(() =>
                // {
                //     promise = promise;
                // }), out CoroutineResultReference<FeralTweaksAction<object>> acFtA1));

                // // Next phase: FT action
                // t.OnFrame(t.AwaitAction(() => FeralTweaksActions.Async.AfterSecs<string>(10, ctx =>
                // {
                //     promise = promise;
                //     return "test";
                // }), out CoroutineResultReference<FeralTweaksAction<string>> acFtA));

                // // Next phase: action
                // t.OnFrame(t.Execute(ctx =>
                // {
                //     // Get result
                //     string s = acFtA.ReturnValue.GetResult();

                //     // Test
                //     ctx = ctx;
                // }));

            });
        }

        [HideFromIl2Cpp]
        public System.Collections.IEnumerator InitCoroutineCustom(CoroutineResultReference<Il2CppSystem.Object> ac1)
        {
            yield break;
        }

        [HideFromIl2Cpp]
        private FeralTweaksPromise<string> FetchDataAsync()
        {
            FeralTweaksPromiseController<string> promise = FeralTweaksPromises.CreatePromise<string>();
            Task.Run(() =>
            {
                promise.CallComplete("Hello World");
            });
            return promise.GetPromise();
        }
    }
}