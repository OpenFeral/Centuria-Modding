using System;
using System.Collections.Generic;
using System.Threading;
using UnityEngine;
using ScaffoldSharp.Core.AsyncTasks;
using Logger = FeralTweaks.Logging.Logger;
using System.Diagnostics;
using System.Linq;

namespace FeralTweaks.Actions
{
    /// <summary>
    /// Action scheduling system
    /// </summary>
    public class FeralTweaksActions
    {
        private readonly FeralTweaksActionType type;

        private FeralTweaksActions(FeralTweaksActionType type)
        {
            this.type = type;
        }

        internal static Thread unityThread;
        internal static Thread actionThread;
        private static readonly List<Func<bool>> threadActions = [];
        private static readonly List<Func<bool>> uiRepeatingActions = [];
        internal static FrameUpdateHandler updateHandler;

        /// <summary>
        /// The action scheduler for Unity frame updates, runs on every frame update
        /// </summary>
        public static readonly FeralTweaksActions Unity = new(FeralTweaksActionType.UNITY);

        /// <summary>
        /// The action scheduler for the FeralTweaks event queue, runs actions asynchronously to unity on a shared event queue, NOTE THIS CANNOT INTERACT WITH IL2CPP OR UNITY DIRECTLY
        /// </summary>
        public static readonly FeralTweaksActions EventQueue = new(FeralTweaksActionType.SYNC);

        /// <summary>
        /// The action scheduler for the async FeralTweaks event queue, runs actions asynchronously to unity, NOTE THIS CANNOT INTERACT WITH IL2CPP OR UNITY DIRECTLY
        /// </summary>
        public static readonly FeralTweaksActions Async = new(FeralTweaksActionType.ASYNC);

        internal static void SetupUpdateHandler(FrameUpdateHandler handler)
        {
            updateHandler = handler;
        }

        internal static void SetupUnity()
        {
            unityThread = Thread.CurrentThread;
        }

        internal static bool IsAwaitSafeOnCurrentThread(FeralTweaksActionType type)
        {
            // Async
            if (type == FeralTweaksActionType.ASYNC)
            {
                return true;
            }

            // Unity
            if (type == FeralTweaksActionType.UNITY)
            {
                // Check thread
                return unityThread == null || unityThread.ManagedThreadId != Environment.CurrentManagedThreadId;
            }

            // Sync
            if (type == FeralTweaksActionType.SYNC)
            {
                // Check thread
                return actionThread == null || actionThread.ManagedThreadId != Environment.CurrentManagedThreadId;
            }

            // Safe
            return true;
        }

        internal static void CallUpdate()
        {
            Func<bool>[] actionsUA;
            lock (uiRepeatingActions)
            {
                actionsUA = [.. uiRepeatingActions];
            }

            // Handle actions
            // FIXME: profiler and lag warnings
            foreach (Func<bool> ac in actionsUA)
            {
                try
                {
                    if (ac == null || ac())
                    {
                        lock (uiRepeatingActions)
                        {
                            uiRepeatingActions.Remove(ac);
                        }
                    }
                }
                catch (Exception e)
                {
                    lock (uiRepeatingActions)
                    {
                        uiRepeatingActions.Remove(ac);
                    }

                    // Log error
                    Logger.GetLogger("ActionManager").Error("An exception occurred while handling an on-unity update action", e);
                    if (Debugger.IsAttached)
                        throw;
                }
            }
        }

        internal static void StartActionThread()
        {
            // Start action thread
            actionThread = new Thread(() =>
            {
                while (true)
                {
                    Func<bool>[] actions;
                    lock (threadActions)
                    {
                        actions = [.. threadActions];
                    }

                    // Handle actions
                    // FIXME: profiler and lag warnings
                    foreach (Func<bool> ac in actions)
                    {
                        try
                        {
                            if (ac == null || ac())
                            {
                                lock (threadActions)
                                {
                                    threadActions.Remove(ac);
                                }
                            }
                        }
                        catch (Exception e)
                        {
                            lock (threadActions)
                            {
                                threadActions.Remove(ac);
                            }

                            // Log error
                            Logger.GetLogger("ActionManager").Error("An exception occurred while handling an event queue update action", e);
                            if (Debugger.IsAttached)
                                throw;
                        }
                    }

                    Thread.Sleep(10);
                }
            })
            {
                IsBackground = true,
                Name = "FeralTweaks Event Queue Worker"
            };
            actionThread.Start();
        }

        internal static FeralTweaksAction<T> ScheduleAction<T>(Func<FeralTweaksActionExecutionContext<T>, T> call, FeralTweaksActionType type, bool allowInlineExecute, long millisWait, int interval, int limit)
        {
            // Create action
            FeralTweaksAction<T> action = new FeralTweaksAction<T>(call, type, allowInlineExecute, millisWait, interval, limit);

            // Check type
            switch (type)
            {
                case FeralTweaksActionType.ASYNC:
                    {
                        // Asynchronous task

                        // Schedule
                        AsyncTaskManager.RunAsync(() =>
                        {
                            // Run until finish
                            while (true)
                            {
                                if (!action.Tick())
                                {
                                    break; // Finish
                                }

                                Thread.Sleep(10);
                            }
                        });

                        // Break
                        break;
                    }

                case FeralTweaksActionType.SYNC:
                    {
                        // Action thread task

                        // Check inline permission and if its the action thread
                        if (allowInlineExecute && actionThread != null && actionThread.ManagedThreadId == Environment.CurrentManagedThreadId)
                        {
                            // Inline

                            // Run action
                            action._denyContinueCall = true;
                            action.Tick();
                        }
                        else
                        {
                            // Schedule
                            threadActions.Add(() =>
                            {
                                // Tick
                                return !action.Tick();
                            });
                        }

                        // Break                        
                        break;
                    }

                case FeralTweaksActionType.UNITY:
                    {
                        // Unity task

                        // Check inline permission and if its the action thread
                        if (allowInlineExecute && unityThread != null && unityThread.ManagedThreadId == Environment.CurrentManagedThreadId)
                        {
                            // Inline

                            // Run action
                            action._denyContinueCall = true;
                            action.Tick();
                        }
                        else
                        {
                            // Schedule
                            uiRepeatingActions.Add(() =>
                            {
                                // Tick
                                return !action.Tick();
                            });
                        }

                        // Break
                        break;
                    }

                default:
                    break;
            }

            // Return
            return action;
        }

        /// <summary>
        /// Registers a oneshot action to run on event tick without return type
        /// </summary>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> Oneshot(Action action)
        {
            return Oneshot(ctx => action());
        }

        /// <summary>
        /// Registers a oneshot action to run on event tick without return type
        /// </summary>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> Oneshot(Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return Oneshot<object>(ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers a oneshot action to run on event tick
        /// </summary>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> Oneshot(Func<bool> action)
        {
            return Oneshot<object>(ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers a oneshot action to run on event tick
        /// </summary>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> Oneshot<T>(Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return ScheduleAction(action, type, false, -1, -1, 1);
        }

        /// <summary>
        /// Registers an action to run after a certain amount of ticks have elapsed
        /// </summary>
        /// <param name="delay">Amount of ticks to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> AfterTicks(int delay, Action action)
        {
            return AfterTicks(delay, ctx => action());
        }

        /// <summary>
        /// Registers an action to run after a certain amount of ticks have elapsed
        /// </summary>
        /// <param name="delay">Amount of ticks to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> AfterTicks(int delay, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return AfterTicks<object>(delay, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action to run after a certain amount of ticks have elapsed
        /// </summary>
        /// <param name="delay">Amount of ticks to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> AfterTicks(int delay, Func<bool> action)
        {
            return AfterTicks<object>(delay, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action to run after a certain amount of ticks have elapsed
        /// </summary>
        /// <param name="delay">Amount of ticks to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> AfterTicks<T>(int delay, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return ScheduleAction(action, type, false, -1, delay, 1);
        }

        /// <summary>
        /// Registers an action to run after a certain amount of milliseconds have elapsed
        /// </summary>
        /// <param name="delay">Amount of milliseconds to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> AfterMs(long delay, Action action)
        {
            return AfterMs(delay, ctx => action());
        }

        /// <summary>
        /// Registers an action to run after a certain amount of milliseconds have elapsed
        /// </summary>
        /// <param name="delay">Amount of milliseconds to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> AfterMs(long delay, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return AfterMs<object>(delay, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action to run after a certain amount of milliseconds have elapsed
        /// </summary>
        /// <param name="delay">Amount of milliseconds to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> AfterMs(long delay, Func<bool> action)
        {
            return AfterMs<object>(delay, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action to run after a certain amount of milliseconds have elapsed
        /// </summary>
        /// <param name="delay">Amount of milliseconds to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> AfterMs<T>(long delay, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return ScheduleAction(action, type, false, delay, -1, 1);
        }

        /// <summary>
        /// Registers an action to run after a certain amount of seconds have elapsed
        /// </summary>
        /// <param name="delay">Amount of seconds to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> AfterSecs(long delay, Action action)
        {
            return AfterSecs(delay, ctx => action());
        }

        /// <summary>
        /// Registers an action to run after a certain amount of seconds have elapsed
        /// </summary>
        /// <param name="delay">Amount of seconds to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> AfterSecs(long delay, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return AfterSecs<object>(delay, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action to run after a certain amount of seconds have elapsed
        /// </summary>
        /// <param name="delay">Amount of seconds to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> AfterSecs(long delay, Func<bool> action)
        {
            return AfterSecs<object>(delay, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action to run after a certain amount of seconds have elapsed
        /// </summary>
        /// <param name="delay">Amount of seconds to wait before executing the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> AfterSecs<T>(long delay, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return AfterMs(delay * 1000, action);
        }

        /// <summary>
        /// Registers an action that repeats on each event tick
        /// </summary>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> Repeat(Action action)
        {
            return Repeat(ctx => action());
        }

        /// <summary>
        /// Registers an action that repeats on each event tick
        /// </summary>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> Repeat(Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return Repeat<object>(ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action that repeats on each event tick
        /// </summary>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> Repeat(Func<bool> action)
        {
            return Repeat<object>(ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action that repeats on each event tick
        /// </summary>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> Repeat<T>(Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return ScheduleAction(action, type, false, -1, -1, -1);
        }

        /// <summary>
        /// Registers an action that repeats on each event tick until a set limit is reached
        /// </summary>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> Repeat(int limit, Action action)
        {
            return Repeat(limit, ctx => action());
        }

        /// <summary>
        /// Registers an action that repeats on each event tick until a set limit is reached
        /// </summary>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> Repeat(int limit, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return Repeat<object>(limit, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action that repeats on each event tick until a set limit is reached
        /// </summary>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> Repeat(int limit, Func<bool> action)
        {
            return Repeat<object>(limit, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action that repeats on each event tick until a set limit is reached
        /// </summary>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> Repeat<T>(int limit, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return ScheduleAction(action, type, false, -1, -1, limit);
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval
        /// </summary>
        /// <param name="action">Action to schedule</param>
        /// <param name="interval">Amount of ticks to wait each time before executing</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalTicks(int interval, Action action)
        {
            return IntervalTicks(interval, ctx => action());
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval
        /// </summary>
        /// <param name="interval">Amount of ticks to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalTicks(int interval, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return IntervalTicks<object>(interval, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval
        /// </summary>
        /// <param name="interval">Amount of ticks to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalTicks(int interval, Func<bool> action)
        {
            return IntervalTicks<object>(interval, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval
        /// </summary>
        /// <param name="interval">Amount of ticks to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> IntervalTicks<T>(int interval, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return ScheduleAction(action, type, false, -1, interval, -1);
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval
        /// </summary>
        /// <param name="interval">Amount of ticks to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalTicks(int interval, int limit, Action action)
        {
            return IntervalTicks(interval, limit, ctx => action());
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval
        /// </summary>interval
        /// <param name="interval">Amount of ticks to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalTicks(int interval, int limit, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return IntervalTicks<object>(interval, limit, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval
        /// </summary>
        /// <param name="interval">Amount of ticks to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalTicks(int interval, int limit, Func<bool> action)
        {
            return IntervalTicks<object>(interval, limit, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval
        /// </summary>
        /// <param name="interval">Amount of ticks to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> IntervalTicks<T>(int interval, int limit, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return ScheduleAction(action, type, false, -1, interval, limit);
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of milliseconds
        /// </summary>
        /// <param name="interval">Amount of ticks to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalMs(long interval, Action action)
        {
            return IntervalMs(interval, ctx => action());
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of milliseconds
        /// </summary>
        /// <param name="interval">Amount of milliseconds to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalMs(long interval, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return IntervalMs<object>(interval, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of milliseconds
        /// </summary>
        /// <param name="interval">Amount of milliseconds to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalMs(long interval, Func<bool> action)
        {
            return IntervalMs<object>(interval, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of milliseconds
        /// </summary>
        /// <param name="interval">Amount of milliseconds to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> IntervalMs<T>(long interval, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return ScheduleAction(action, type, false, interval, -1, -1);
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of milliseconds
        /// </summary>
        /// <param name="interval">Amount of milliseconds to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalMs(long interval, int limit, Action action)
        {
            return IntervalMs(interval, limit, ctx => action());
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of milliseconds
        /// </summary>interval
        /// <param name="interval">Amount of milliseconds to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalMs(long interval, int limit, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return IntervalMs<object>(interval, limit, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of milliseconds
        /// </summary>
        /// <param name="interval">Amount of milliseconds to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalMs(long interval, int limit, Func<bool> action)
        {
            return IntervalMs<object>(interval, limit, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of milliseconds
        /// </summary>
        /// <param name="interval">Amount of milliseconds to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> IntervalMs<T>(long interval, int limit, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return ScheduleAction(action, type, false, interval, -1, limit);
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of seconds
        /// </summary>
        /// <param name="interval">Amount of seconds to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalSecs(long interval, Action action)
        {
            return IntervalSecs(interval, ctx => action());
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of seconds
        /// </summary>
        /// <param name="interval">Amount of seconds to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalSecs(long interval, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return IntervalSecs<object>(interval, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of seconds
        /// </summary>
        /// <param name="interval">Amount of seconds to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalSecs(long interval, Func<bool> action)
        {
            return IntervalSecs<object>(interval, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of seconds
        /// </summary>
        /// <param name="interval">Amount of seconds to wait each time before executing</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> IntervalSecs<T>(long interval, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return IntervalMs(interval * 1000, action);
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of seconds
        /// </summary>
        /// <param name="interval">Amount of seconds to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalSecs(long interval, int limit, Action action)
        {
            return IntervalSecs(interval, limit, ctx => action());
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of seconds
        /// </summary>interval
        /// <param name="interval">Amount of seconds to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalSecs(long interval, int limit, Action<FeralTweaksActionExecutionContext<object>> action)
        {
            return IntervalSecs<object>(interval, limit, ctx =>
            {
                action(ctx);
                return null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of seconds
        /// </summary>
        /// <param name="interval">Amount of seconds to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<object> IntervalSecs(long interval, int limit, Func<bool> action)
        {
            return IntervalSecs<object>(interval, limit, ctx =>
            {
                bool val = action();
                return !val ? ctx.Continue() : null;
            });
        }

        /// <summary>
        /// Registers an action that ticks on a specified interval of seconds
        /// </summary>
        /// <param name="interval">Amount of seconds to wait each time before executing</param>
        /// <param name="limit">Amount of times to repeat the action</param>
        /// <param name="action">Action to schedule</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<T> IntervalSecs<T>(long interval, int limit, Func<FeralTweaksActionExecutionContext<T>, T> action)
        {
            return IntervalMs(interval * 1000, limit, action);
        }

        /// <summary>
        /// Runs a coroutine as an action
        /// </summary>
        /// <param name="coroutine">Coroutine initializer</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<Coroutine> RunCoroutine(System.Action<FTCoroutine.CoroutineBuilder> coroutine)
        {
            if (type != FeralTweaksActionType.UNITY && !IsAwaitSafeOnCurrentThread(FeralTweaksActionType.UNITY))
            {
                // Unsafe
                throw new InvalidOperationException("Unable to use RunCoroutine in the current context, RunCoroutine cannot safely be run on non-unity event queues when scheduling from a unity coroutine, as awaiting from unity would cause a deadlock");
            }
            bool coroutineFinished = false;
            Coroutine routine = null;
            return Oneshot<Coroutine>(ctx =>
            {
                // Start unity coroutine on unity thread if needed
                if (routine == null)
                {
                    ctx.GetUnityEventQueue().ExecuteAction(() =>
                    {
                        // Starts the coroutine
                        // Should the RunCoroutine call have been on unity thread, GetUnityEventQueue().ExecuteAction will run right away
                        routine = updateHandler.StartCoroutine(FeralTweaksCoroutines.InjectAtTail(FeralTweaksCoroutines.CreateNew(t =>
                        {
                            t.Execute(() =>
                            {
                                coroutineFinished = true;
                            });
                        }), FeralTweaksCoroutines.CreateNew(coroutine)));
                    }).AwaitComplete();
                }

                // Tick
                if (!coroutineFinished)
                    return ctx.Continue();

                // Finish
                return routine;
            });
        }
        
        /// <summary>
        /// Runs a coroutine as an action
        /// </summary>
        /// <param name="coroutine">Coroutine to run</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<Coroutine> RunCoroutine(System.Collections.IEnumerator coroutine)
        {
            if (type != FeralTweaksActionType.UNITY && !IsAwaitSafeOnCurrentThread(FeralTweaksActionType.UNITY))
            {
                // Unsafe
                throw new InvalidOperationException("Unable to use RunCoroutine in the current context, RunCoroutine cannot safely be run on non-unity event queues when scheduling from a unity coroutine, as awaiting from unity would cause a deadlock");
            }
            bool coroutineFinished = false;
            Coroutine routine = null;
            return Oneshot<Coroutine>(ctx =>
            {
                // Start unity coroutine on unity thread if needed
                if (routine == null)
                {
                    ctx.GetUnityEventQueue().ExecuteAction(() =>
                    {
                        // Starts the coroutine
                        // Should the RunCoroutine call have been on unity thread, GetUnityEventQueue().ExecuteAction will run right away
                        routine = updateHandler.StartCoroutine(FeralTweaksCoroutines.InjectAtTail(FeralTweaksCoroutines.CreateNew(t =>
                        {
                            t.Execute(() =>
                            {
                                coroutineFinished = true;
                            });
                        }), FeralTweaksCoroutines.CreateNew(coroutine)));
                    }).AwaitComplete();
                }

                // Tick
                if (!coroutineFinished)
                    return ctx.Continue();

                // Finish
                return routine;
            });
        }
        
        /// <summary>
        /// Runs a coroutine as an action
        /// </summary>
        /// <param name="coroutine">Coroutine to run</param>
        /// <returns>FeralTweaksAction instance</returns>
        public FeralTweaksAction<Coroutine> RunCoroutine(Il2CppSystem.Collections.IEnumerator coroutine)
        {
            if (type != FeralTweaksActionType.UNITY && !IsAwaitSafeOnCurrentThread(FeralTweaksActionType.UNITY))
            {
                // Unsafe
                throw new InvalidOperationException("Unable to use RunCoroutine in the current context, RunCoroutine cannot safely be run on non-unity event queues when scheduling from a unity coroutine, as awaiting from unity would cause a deadlock");
            }
            bool coroutineFinished = false;
            Coroutine routine = null;
            return Oneshot<Coroutine>(ctx =>
            {
                // Start unity coroutine on unity thread if needed
                if (routine == null)
                {
                    ctx.GetUnityEventQueue().ExecuteAction(() =>
                    {
                        // Starts the coroutine
                        // Should the RunCoroutine call have been on unity thread, GetUnityEventQueue().ExecuteAction will run right away
                        routine = updateHandler.StartCoroutine(FeralTweaksCoroutines.InjectAtTail(FeralTweaksCoroutines.CreateNew(t =>
                        {
                            t.Execute(() =>
                            {
                                coroutineFinished = true;
                            });
                        }), coroutine));
                    }).AwaitComplete();
                }

                // Tick
                if (!coroutineFinished)
                    return ctx.Continue();

                // Finish
                return routine;
            });
        }

    }
}