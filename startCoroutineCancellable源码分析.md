# startCoroutineCancellable 和 intercepted() 方法源码分析

## 概述

本文档详细分析 `startCoroutineCancellable` 方法中的 `.intercepted()` 调用，这是协程启动过程中的关键步骤。

## startCoroutineCancellable 方法

### 方法签名

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/intrinsics/Cancellable.kt
internal fun <R, T> (suspend (R) -> T).startCoroutineCancellable(
    receiver: R,
    completion: Continuation<T>,
    onCancellation: ((cause: Throwable) -> Unit)? = null
) = runSafely(completion) {
    createCoroutineUnintercepted(receiver, completion)
        .intercepted()
        .resumeCancellableWith(Result.success(Unit), onCancellation)
}
```

### 方法分解

这个方法分为三个关键步骤：

1. **createCoroutineUnintercepted(receiver, completion)** - 创建原始的 Continuation
2. **intercepted()** - 拦截并包装 Continuation
3. **resumeCancellableWith()** - 启动协程

## 第一步：createCoroutineUnintercepted

### 实现

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/intrinsics/Intrinsics.kt
public expect fun <R, T> (suspend (R) -> T).createCoroutineUnintercepted(
    receiver: R,
    completion: Continuation<T>
): Continuation<Unit>
```

### 作用

- 创建一个原始的 Continuation 实例
- 这个 Continuation 包含了协程体的状态机实现
- 还没有经过任何拦截器处理

### 编译器生成的代码

```kotlin
// 编译器为协程体生成的 Continuation 实现
class ContinuationImpl(
    private val completion: Continuation<Unit>
) : Continuation<Unit> {
    private var state = 0
    private var count: Int = 0
    private var num: Int = 0
    
    override fun resumeWith(result: Result<Unit>) {
        when (state) {
            0 -> {
                // 状态0：初始化
                count = 19 * Random.nextInt(17)
                state = 1
                delay(10, this) // 挂起
            }
            1 -> {
                // 状态1：delay后
                println("ceshi***1-${count}")
                state = 2
                withContext(Dispatchers.Main, this) { // 挂起
                    println("ceshi***2-${count}")
                    5
                }
            }
            2 -> {
                // 状态2：withContext后
                num = result.getOrThrow()
                println("ceshi***3-${num}")
                completion.resumeWith(result) // 完成
            }
        }
    }
}
```

## 第二步：intercepted() 方法

### 方法签名

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/intrinsics/Intrinsics.kt
public expect fun <T> Continuation<T>.intercepted(): Continuation<T>
```

### 实际实现

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/intrinsics/IntrinsicsJvm.kt
public actual fun <T> Continuation<T>.intercepted(): Continuation<T> =
    (this as? ContinuationImpl)?.intercepted() ?: this
```

### ContinuationImpl.intercepted() 实现

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/internal/ContinuationImpl.kt
internal abstract class ContinuationImpl(
    completion: Continuation<Any?>?,
    private val _context: CoroutineContext?
) : BaseContinuationImpl(completion) {
    
    public fun intercepted(): Continuation<Any?> =
        intercepted
            ?: (context[ContinuationInterceptor]?.interceptContinuation(this) ?: this)
                .also { intercepted = it }
}
```

### 关键理解点

1. **检查是否已经拦截**：如果已经拦截过，直接返回
2. **查找 ContinuationInterceptor**：从协程上下文中查找拦截器
3. **调用 interceptContinuation**：让拦截器处理 Continuation
4. **缓存结果**：将拦截后的 Continuation 缓存起来

## ContinuationInterceptor 的实现

### 接口定义

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/ContinuationInterceptor.kt
public interface ContinuationInterceptor : CoroutineContext.Element {
    companion object Key : CoroutineContext.Key<ContinuationInterceptor>
    
    public fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T>
}
```

### CoroutineDispatcher 的实现

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/CoroutineDispatcher.kt
public abstract class CoroutineDispatcher : AbstractCoroutineContextElement(ContinuationInterceptor), ContinuationInterceptor {
    
    public final override fun <T> interceptContinuation(continuation: Continuation<T>): Continuation<T> {
        return DispatchedContinuation(this, continuation)
    }
}
```

### DispatchedContinuation 的实现

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/internal/DispatchedContinuation.kt
internal class DispatchedContinuation<T>(
    val dispatcher: CoroutineDispatcher,
    val continuation: Continuation<T>
) : DispatchedTask<T>(MODE_UNINITIALIZED), Continuation<T> by continuation {
    
    override fun resumeWith(result: Result<T>) {
        val context = continuation.context
        val state = result.toState()
        if (dispatcher.isDispatchNeeded(context)) {
            _state = state
            resumeMode = MODE_ATOMIC
            dispatcher.dispatch(context, this)
        } else {
            executeUnconfined(state, MODE_ATOMIC) {
                withCoroutineContext(this.context, countOrElement) {
                    continuation.resumeWith(result)
                }
            }
        }
    }
}
```

## 完整的执行流程

### 1. 协程启动过程

```kotlin
// 当调用 CoroutineScope(Dispatchers.IO).launch 时：

// 1. 创建 StandaloneCoroutine
val coroutine = StandaloneCoroutine(newContext, active = true)

// 2. 调用 coroutine.start()
coroutine.start(start, coroutine, block)

// 3. 最终调用 startCoroutineCancellable
block.startCoroutineCancellable(coroutine, coroutine)
```

### 2. startCoroutineCancellable 执行

```kotlin
internal fun <R, T> (suspend (R) -> T).startCoroutineCancellable(
    receiver: R,           // StandaloneCoroutine 实例
    completion: Continuation<T>,  // StandaloneCoroutine 实例
    onCancellation: ((cause: Throwable) -> Unit)? = null
) = runSafely(completion) {
    // 步骤1：创建原始的 Continuation
    val originalContinuation = createCoroutineUnintercepted(receiver, completion)
    
    // 步骤2：拦截 Continuation
    val interceptedContinuation = originalContinuation.intercepted()
    
    // 步骤3：启动协程
    interceptedContinuation.resumeCancellableWith(Result.success(Unit), onCancellation)
}
```

### 3. intercepted() 的详细执行

```kotlin
// 当调用 originalContinuation.intercepted() 时：

// 1. 检查是否已经拦截
if (this is ContinuationImpl && intercepted != null) {
    return intercepted
}

// 2. 从上下文中查找 ContinuationInterceptor
val interceptor = context[ContinuationInterceptor]

// 3. 如果找到拦截器，调用 interceptContinuation
if (interceptor != null) {
    val intercepted = interceptor.interceptContinuation(this)
    this.intercepted = intercepted  // 缓存结果
    return intercepted
}

// 4. 如果没有拦截器，返回原始 Continuation
return this
```

### 4. DispatchedContinuation 的作用

```kotlin
// DispatchedContinuation 包装了原始的 Continuation
class DispatchedContinuation<T>(
    val dispatcher: CoroutineDispatcher,  // Dispatchers.IO
    val continuation: Continuation<T>     // 原始的 ContinuationImpl
) : Continuation<T> by continuation {
    
    override fun resumeWith(result: Result<T>) {
        // 检查是否需要切换线程
        if (dispatcher.isDispatchNeeded(context)) {
            // 需要切换线程，提交到线程池
            dispatcher.dispatch(context, this)
        } else {
            // 不需要切换线程，直接执行
            continuation.resumeWith(result)
        }
    }
}
```

## 线程切换的具体实现

### 1. Dispatchers.IO 的 dispatch 方法

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/scheduling/ExperimentalCoroutineDispatcher.kt
public class ExperimentalCoroutineDispatcher(
    private val corePoolSize: Int,
    private val maxPoolSize: Int,
    private val idleWorkerKeepAliveNs: Long = IDLE_WORKER_KEEP_ALIVE_NS,
    private val schedulerName: String = "CoroutineScheduler"
) : CoroutineDispatcher() {
    
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        scheduler.execute(block)
    }
}
```

### 2. CoroutineScheduler 的执行

```kotlin
// kotlinx-coroutines-core/src/main/kotlin/kotlinx/coroutines/scheduling/CoroutineScheduler.kt
internal class CoroutineScheduler(
    private val corePoolSize: Int,
    private val maxPoolSize: Int,
    private val idleWorkerKeepAliveNs: Long = IDLE_WORKER_KEEP_ALIVE_NS,
    private val schedulerName: String = DEFAULT_SCHEDULER_NAME
) : Executor, Closeable {
    
    override fun execute(command: Runnable) {
        val task = TaskImpl(command, submissionTime = schedulerTimeSource.nanoTime())
        if (addToGlobalQueue(task)) {
            requestCpuWorker()
        } else {
            throw RejectedExecutionException("$schedulerName was terminated")
        }
    }
}
```

## 关键理解点

### 1. intercepted() 的作用

- **线程调度**：确保协程在正确的线程上执行
- **拦截机制**：允许在协程执行前进行拦截和处理
- **性能优化**：缓存拦截结果，避免重复处理

### 2. 拦截器的层次结构

```
原始 Continuation (ContinuationImpl)
    ↓ intercepted()
DispatchedContinuation (包装了调度器)
    ↓ resumeWith()
线程池中的线程执行协程体
```

### 3. 为什么需要 intercepted()

- **原始 Continuation**：只包含状态机逻辑，不知道在哪个线程执行
- **DispatchedContinuation**：知道如何将协程调度到正确的线程
- **线程切换**：通过调度器实现协程在不同线程间的切换

## 总结

`.intercepted()` 方法是协程线程调度的核心：

1. **创建原始 Continuation**：包含协程的状态机实现
2. **拦截处理**：通过 ContinuationInterceptor 包装 Continuation
3. **线程调度**：DispatchedContinuation 负责在正确的线程上执行协程
4. **启动协程**：调用 resumeCancellableWith 开始执行

这种设计让协程能够灵活地在不同线程间切换，同时保持代码的简洁性。 