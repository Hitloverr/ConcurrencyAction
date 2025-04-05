# 前言
学好并发，三条主线：
1. 分工。eg：Executor、ForkJoin、Future，生产者消费者模式，Thread-Per-Message、Worker Thread等模式。
2. 同步。线程间的协作：当一个线程执行完了一个任务，如何通知执行后续任务的线程开工。eg，比如Future的get，CountDownLatch、CyclicBarrier、Phaser、Exchanger。
3. 互斥。线程安全，并发程序中，多个线程同时访问同一个共享变量，可能有原子性、有序性、可见性的问题。核心是锁，Synchronized、Lock，ReadWriteLock、StampedLock、乐观锁、分段锁、原子类。ConcurrentHashMap、CopyOnWriteXXX、死锁。
![](img.png)

另外，要看本质，这个概念是从哪儿来的？背景和解决的问题是什么，理论模型是什么。

## 为什么要学好并发

虽然第一要义是不要写并发程序，但是现在并发编程是一项必备技能。怎么写出正确高效的？

1. 理解技术背后的理论和模型，比如信号量模型、管程模型。涉及到操作系统、CPU、内存等多方面的知识。
2. 坚持

# 并发编程BUG的三大性

## 并发程序背后的故事

CPU、内存、IO三者之间的速度差异很大，为了平衡，做了以下事情：

1. CPU增加了**缓存**，均衡与内存的速度差异
2. 操作系统增加了**线程**、进程，用来**时分复用**CPU，均衡CPU与IO设备的速度差异
3. 编译程序**优化指令执行次序**，让缓存能够合理得到使用。

## 缓存导致的可见性问题

一个线程对共享变量的修改，另外一个线程能够马上看到，这就是可见性。![image-20250405163509470](D:\code\ConcurrencyAction\notes\image\image-20250405163509470.png)

CPU缓存 和 内存中的值可能不是时刻一致的。

代码验证：见 TestVisible，每个线程先读变量到CPU缓存中，操作之后再写到内存中。

## 线程切换导致的原子性问题

时间片-多进程切换交替获得CPU的使用权 ==> 分时复用系统。

- 进程：由于不共享内存空间，如果切换就需要切换内存映射地址
- 线程：进程创建的所有线程，共享一个内存空间，切换任务成本比较低



比如一个 count+=1 的语句，底层实际是三条机器指令：变量加载到CPU寄存器、寄存器数值加一、写入内存或CPU缓存。

![image-20250405164430765](D:\code\ConcurrencyAction\notes\image\image-20250405164430765.png)

把一个或者多个操作，在CPU执行过程中不被中断的特性叫做原子性。CPU能保证的原子操作是CPU指令级别的，与高级语言的一条指令并不一一对应，需要注意。（注意下，在32位机器上对long double变量进行加减操作有并发隐患）

## 编译优化带来的有序性问题

```java
 private static TestSingleObject instance;

    public static TestSingleObject getInstance() {
        if (instance == null) {
            synchronized (TestSingleObject.class) {
                if (instance == null) {
                    instance = new TestSingleObject();
                }
            }
        }
        return instance;
    }
```

这个单例模式是有问题的，new操作：

1. 分配内存M
2. 内存M上初始化对象
3. M的地址赋值给变量。

第2 3 步可能因为优化导致顺序颠倒，可能导致空指针异常。

![image-20250405170248684](D:\code\ConcurrencyAction\notes\image\image-20250405170248684.png)

# Java内存模型

解决可见性 + 有序性。

## 什么是？

为了兼具 性能 与正确性，需要做到按需禁用缓存 和 编译优化，提供给程序员手段。具体方法包括volatile、Synchronized、final、happens-before

### volatile

禁用cpu缓存，必须从内存中读取或者写入。

### happens-before

表达了前面一个操作的结果对后续操作是可见的，约束了编译器的优化行为。

1. 程序的顺序性规则：在一个线程中，前面代码的操作happens before后面的代码操作。
2. volatile变量规则：对一个volatile变量的写操作，happens before于后续对这个volatile变量的读操作。
3. 传递性：A happens before B、B  happens before C=> A happens before C。举个例子：![image-20250405201423499](D:\code\ConcurrencyAction\notes\image\image-20250405201423499.png)

- x=42 happens before v== true
- 写v==true hb 读v==true
- x == 42 hb 读变量X

4. 管程中锁的规则：对一个锁的解锁happens before于后续对这个锁的加锁。

5. 线程start规则：线程A启动子线程B后，B能看到线程A在启动子线程B前的操作。

   ```java
   Thread B = new Thread(()->{
     // 主线程调用B.start()之前
     // 所有对共享变量的修改，此处皆可见
     // 此例中，var==77
   });
   // 此处对共享变量var修改
   var = 77;
   // 主线程启动子线程
   B.start();
   ```

6. 线程join规则，A等待B执行完成，则A线程能看到线程B中的操作。

```java
Thread B = new Thread(()->{
  // 此处对共享变量var修改
  var = 66;
});
// 例如此处对共享变量修改，
// 则这个修改结果对线程B可见
// 主线程启动子线程
B.start();
B.join()
// 子线程所有对共享变量的修改
// 在主线程调用B.join()之后皆可见
// 此例中，var==66
```

### final

final就表示了这个变量生而不变，可以使劲优化。后面对final类型变量的重排进行了约束，只要构造函数没有逸出，就没有问题。

```java
// 错误的构造函数, 线程通过global.obj读取x是有可能读到0的
public FinalFieldExample() { 
  x = 3;
  y = 4;
  // 此处就是讲this逸出，
  global.obj = this;
}
```



# 互斥锁： 解决原子性问题

