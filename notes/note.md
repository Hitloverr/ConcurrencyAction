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

一个线程对共享变量的修改，另外一个线程能够马上看到，这就是可见性。![image-20250405163509470](image\image-20250405163509470.png)

CPU缓存 和 内存中的值可能不是时刻一致的。

代码验证：见 TestVisible，每个线程先读变量到CPU缓存中，操作之后再写到内存中。

## 线程切换导致的原子性问题

时间片-多进程切换交替获得CPU的使用权 ==> 分时复用系统。

- 进程：由于不共享内存空间，如果切换就需要切换内存映射地址
- 线程：进程创建的所有线程，共享一个内存空间，切换任务成本比较低



比如一个 count+=1 的语句，底层实际是三条机器指令：变量加载到CPU寄存器、寄存器数值加一、写入内存或CPU缓存。

![image-20250405164430765](image\image-20250405164430765.png)

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
3. 传递性：A happens before B、B  happens before C=> A happens before C。举个例子：![image-20250405201423499](image\image-20250405201423499.png)

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

eg：long型变量是64位，在32位cpu上执行写操作会拆分成两次写操作。如果两个线程在不同的CPU核心中同时写long变量高32位的话，就会出现bug了：重新读出来却不是自己写出的。



原子性：一个或者多个操作在CPU执行的过程中不被中断。**本质是要保证中间状态不对外可见**。不能直接禁用线程切换，那就效率太低下了。（这种情况就是互斥，同一时刻只有一个线程执行）

## 锁模型

确定要锁定的对象，锁要保护的资源以及在哪里加锁解锁。

![image-20250406173122725](image\image-20250406173122725.png)

![image-20250406173231885](image\image-20250406173231885.png)

首先，标注临界区要保护的资源R；其次，要保护资源R就得为它创建锁LR；最后，需要在进出临界区时添加加锁操作和解锁操作。



Java中的锁：Synchronized

```java
class X {
  // 修饰非静态方法
  synchronized void foo() {
    // 临界区
  }
  // 修饰静态方法
  synchronized static void bar() {
    // 临界区
  }
  // 修饰代码块
  Object obj = new Object()；
  void baz() {
    synchronized(obj) {
      // 临界区
    }
  }
}  
```

> 修饰静态方式：锁的是当前类的Class对象；修饰非静态方法，锁的是当前实例对象this

解决count+=1的问题：根据happens-before传递性规则，前一个线程在临界区

修改的共享变量（解锁之前），对后续进入临界区（加锁之后）的线程是可见的。当然，为了保证可见性，在get方法那里也要加上锁。

```java
class SafeCalc {
  long value = 0L;
  synchronized long get() {
    return value;
  }
  synchronized void addOne() {
    value += 1;
  }
}
```

![image-20250406174443262](image\image-20250406174443262.png)

一把锁可以锁上多个受保护资源，如果并发操作用的是不同的锁，那是做不到线程安全的。![image-20250406174755448](image\image-20250406174755448.png)

## 保护没有关联关系的多个资源

比如一个账户下的属性有 密码、账户余额。这两个属性的修改与查看可以用同一把锁，但是性能太差：取款、查看余额、修改密码、查看密码都要是串行的。 最好，是用不同的锁对受保护的资源进行精细化管理，提升性能。就是**细粒度锁**。

## 保护有关联关系的多个资源

比如账户转账

```java
class Account {
  private int balance;
  // 转账
  synchronized void transfer(Account target, int amt){
    if (this.balance > amt) {
      this.balance -= amt;
      target.balance += amt;
    }
  } 
}
```

临界区有两个资源：转出账户的余额this.balance 和 转入账户的余额target.balance。问题出在this这把锁，因为保护不了别人的余额target.balance。

比如：账户A往账户B转账，账户B往账户C转账，两个线程可以同时进入临界区，就会造成写覆盖，有一方的写入丢失了。

## 使用锁的正确姿势

要让锁能覆盖所有受保护资源，上面的例子可以这样写：(创建Account对象时，传入相同的lock，但是这种方式缺乏实践性，需要写代码的时候做好保障。)

```java
class Account {
  private Object lock；
  private int balance;
  private Account();
  // 创建Account时传入同一个lock对象
  public Account(Object lock) {
    this.lock = lock;
  } 
  // 转账
  void transfer(Account target, int amt){
    // 此处检查所有对象共享的锁
    synchronized(lock) {
      if (this.balance > amt) {
        this.balance -= amt;
        target.balance += amt;
      }
    }
  }
}
```

用类对象就好了~都是共享的

```java
class Account {
  private int balance;
  // 转账
  void transfer(Account target, int amt){
    synchronized(Account.class) {
      if (this.balance > amt) {
        this.balance -= amt;
        target.balance += amt;
      }
    }
  } 
}
```

![image-20250406180215590](image\image-20250406180215590.png)

# 死锁

上一个转账的例子，直接锁Account.class粒度太大了，性能太差了。可以这样子，A向B账户转账，拿账户A和账户B的对象锁

![image-20250407223216510](image\image-20250407223216510.png)

```java
class Account {
  private int balance;
  // 转账
  void transfer(Account target, int amt){
    // 锁定转出账户
    synchronized(this) {              
      // 锁定转入账户
      synchronized(target) {           
        if (this.balance > amt) {
          this.balance -= amt;
          target.balance += amt;
        }
      }
    }
  } 
}
```

用了这个细粒度的锁，并行度就提高了，但是可能会导致死锁。比如一个线程做的是账户A往账户B转账，另外一个线程做的是账户B往账户A转账。



死锁：一组相互竞争资源的线程因为相互等待，导致永久阻塞的现象。看一下有向的资源分配图：

![image-20250407223519735](image\image-20250407223519735.png)

## 如何预防死锁

死锁的条件：

1. 互斥，共享资源X和Y只能被一个线程占用【不能破解】
2. 占用且等待：线程获得共享资源X，在等待Y资源的时候，不释放共享资源X。【可以一次性申请所有资源】
3. 不可抢占：其他线程不能强行抢占线程占有的资源【线程申请其他资源的时候，如果申请不到，可以主动释放它占有的资源】
4. 循环等待：线程之间相互等待其他线程占有的资源。【可以按照顺序申请资源，这样就不会循环了】

### 破坏占有且等待条件

增加一个管理员，所有账户申请资源都通过管理员来申请资源，一次性申请。![image-20250407223935425](image\image-20250407223935425.png)

Allocator（单例），Account持有Allocator的单例对象，转账时，首先向Allocator申请资源

```java
class Allocator {
  private List<Object> als =
    new ArrayList<>();
  // 一次性申请所有资源
  synchronized boolean apply(
    Object from, Object to){
    if(als.contains(from) ||
         als.contains(to)){
      return false;  
    } else {
      als.add(from);
      als.add(to);  
    }
    return true;
  }
  // 归还资源
  synchronized void free(
    Object from, Object to){
    als.remove(from);
    als.remove(to);
  }
}

class Account {
  // actr应该为单例
  private Allocator actr;
  private int balance;
  // 转账
  void transfer(Account target, int amt){
    // 一次性申请转出账户和转入账户，直到成功
    while(!actr.apply(this, target))
      ；
    try{
      // 锁定转出账户
      synchronized(this){              
        // 锁定转入账户
        synchronized(target){           
          if (this.balance > amt){
            this.balance -= amt;
            target.balance += amt;
          }
        }
      }
    } finally {
      actr.free(this, target)
    }
  } 
}
```



### 破坏不可抢占条件

synchronized不能做到，但是Lock可以做到

### 破坏循环等待条件

这里是按照账户的id顺序来申请锁

```java
class Account {
  private int id;
  private int balance;
  // 转账
  void transfer(Account target, int amt){
    Account left = this        ①
    Account right = target;    ②
    if (this.id > target.id) { ③
      left = target;           ④
      right = this;            ⑤
    }                          ⑥
    // 锁定序号小的账户
    synchronized(left){
      // 锁定序号大的账户
      synchronized(right){ 
        if (this.balance > amt){
          this.balance -= amt;
          target.balance += amt;
        }
      }
    }
  } 
}
```

# 用 等待通知 机制优化循环等待

```java
// 一次性申请转出账户和转入账户，直到成功
while(!actr.apply(this, target))
```

如果apply方法耗时长，或者并发冲突量大的时候，可能循环很久，才能获取到锁，消耗了很多CPU资源。

最好的方法：线程要求的条件不满足，那么线程阻塞自己，进入等待状态；线程要求的条件满足后，通知等待的线程重新执行。用线程阻塞的方式避免循环等待消耗CPU。



一个完整的等待-通知机制：线程首先获取互斥锁，当线程要求的条件不满足时，释放互斥锁，进入等待状态；当要求的条件满足时，通知等待的线程，重新获取互斥锁。

## synchronized实现

![image-20250407224756115](image\image-20250407224756115.png)

等待队列与互斥锁是一对一的关系，每个互斥锁有自己独立的等待队列。



wait就会让当前线程阻塞，进入右边的等待队列，同时释放持有的互斥锁，其他线程就能获得锁进入临界区。

notify、notifyAll会通知等待队列中的线程，告诉它条件曾经满足过，它就会再次尝试获取互斥锁。

这三个API都只能在synchronized块里面使用，不然会抛出java.lang.IllegalMonitorStateException。

## 一个更好地资源分配器

```java
class Allocator {
  private List<Object> als;
  // 一次性申请所有资源
  synchronized void apply(Object from, Object to){
    // 经典写法
    while(als.contains(from) ||
         als.contains(to)){ //重新判断条件是否满足
      try{
        wait();
      }catch(Exception e){
      }   
    } 
    als.add(from);
    als.add(to);  
  }
  // 归还资源
  synchronized void free(
    Object from, Object to){
    als.remove(from);
    als.remove(to);
    notifyAll();
  }
}
```

## 尽量使用notifyAll

**notify()是会随机地通知等待队列中的一个线程，而notifyAll()会通知等待队列中的所有线程**。

例子：资源A B C D。线程1申请到了AB，线程2申请到了CD，线程3此时要申请AB会进入等待队列，线程4申请CD也会进入等待队列。后面线程1归还了资源AB，如果用notify来通知等待队列中的线程，有可能被通知的是线程4，但线程4申请的是CD，还是会继续等待，而真正该唤醒的线程再也没有机会被唤醒了。

# 安全性、活跃性以及性能问题

## 安全性问题

线程安全，主要就是：原子性、可见性、有序性。在存在共享数据且该数据会发生变化时，也就是说有多个线程会同时读写同一数据时才可能会出问题。

1. 不共享数据或者数据不会变化：线程本地存储、不变模式

2. 在数据竞争的情况下，存在竞态条件：程序执行结果依赖线程执行的顺序。要靠互斥、锁等方式解决。

   ```java
   if (状态变量 满足 执行条件) { 
       执行操作
   }
   ```

   在检查完条件后执行操作时，可能其他线程修改了状态变量。

## 活跃性问题

- 死锁 ：见上节
- 活锁 【比如一直谦让，却一直没执行】，解决方式：可以等待一个随机时间，再谦让，避免谦让后碰撞。
- 饥饿：比如线程优先级很低，又比如其他拿锁的线程，执行时间很长。
  - 保证资源充足
  - 公平分配资源【比如公平锁】
  - 避免持有锁的线程长时间执行

## 性能问题

锁过度使用可能导致串行化的范围过大，性能提升不了。

1. 最好使用无锁的算法和数据结构。比如线程本地存储、写时复制、乐观锁、原子类、Disruptor则是一个无锁的内存队列，性能非常好。
2. 减少锁的持有时间，比如使用细粒度锁，ConcurrentHashMap；读写锁。

性能方面的指标：

1. 吞吐量：单位时间内能处理的请求数量。
2. 延迟：从发出请求到收到响应的时间。
3. 并发量：能同时处理的请求数量。一般来说，随着并发量的增加，延迟也会增加。



# 管程：并发编程的万能钥匙

## 什么是管程

管程和信号量是等价的，synchronized、wait、notifyAll都是管程的组成部分。管程Monitor，指的是管理共享变量以及对共享变量的操作过程，让他们支持并发。

## MESA模型

并发编程的两大核心问题：互斥【同一时刻只允许一个线程访问共享资源】和同步【线程之间如何通信、协作】。

- 互斥，比如封装一个线程不安全的队列成线程安全的队列。管程X将共享变量queue和相关的操作：enq、deq封装。线程如果想要访问queue，只能通过管程调用的enq deq。这两个方法，只允许一个线程进入管程。
- 同步：条件变量 都 对应一个等待队列。

![image-20250408211241669](image\image-20250408211241669.png)

- 线程发现竞态条件不满足，就会通过A.wait方法进入对应的等待队列等待。
- 当条件满足时，线程需要调用A.notify通知A等待队列中的线程。

```java
public class BlockedQueue{ 
    final Lock lock = new ReentrantLock();
    // 条件变量：队列不满
    final Condition notFull = lock.newCondition();
	// 条件变量：队列不空
	final Condition notEmpty = lock.newCondition();
    // 入队 
    void enq(T x) {
        lock.lock();
        try {
          while (队列已满){
            // 等待队列不满 
            notFull.await();
          }  
          // 省略入队操作...
          //入队后,通知可出队
          notEmpty.signal();
        }finally {
          lock.unlock();
        }
	} 
    // 出队 
    void deq(){
        lock.lock();
        try {
          while (队列已空){
            // 等待队列不空
            notEmpty.await();
          }
          // 省略出队操作...
          //出队后，通知可入队
          notFull.signal();
        }finally {
          lock.unlock();
        }  
	} 
}
```

await和前面wait的语义是一致的，signal和notify语义是一致的。

## wait的正确姿势

```java
while(条件不满足) {
  wait();
}
```

## notify何时可以使用

1. 所有等待线程拥有相同的等待条件；
2. 所有等待线程被唤醒后，执行相同的操作；
3. 只需要唤醒一个线程。

```java
while (阻塞队列已满){
  // 等待队列不满
  notFull.await();
}
// 省略入队操作...
// 入队后,通知可出队
notEmpty.signal();
```



# Java线程：生命周期

`jstack` 命令或者`Java VisualVM`这个可视化工具将JVM所有的线程栈信息导出来，完整的线程栈信息不仅包括线程的当前状态、调用栈，还包括了锁的信息。

## 通用的线程生命周期

1. 初始状态：线程已经被创建，不允许分配CPU运行。仅仅是在编程语言层面，OS层面上真正的线程还没有创建
2. 可运行状态：可以分配CPU运行。OS层面上线程创建了。
3. 运行状态：分配到CPU的线程的状态
4. 休眠状态：调用阻塞API 或者 等待某个事件比如条件变量，就会休眠，释放CPU使用权。
5. 终止状态：线程执行完或者异常。

Java语言里则把可运行状态和运行状态合并了，这两个状态在操作系统调度层面有用，而JVM层面不关心这两个状态，因为JVM把线程调度交给操作系统处理了。

## Java中的

1. NEW（初始化状态）
2. RUNNABLE（可运行/运行状态）
3. BLOCKED（阻塞状态）
4. WAITING（无时限等待）
5. TIMED_WAITING（有时限等待）
6. TERMINATED（终止状态）

![image-20250409211845554](image\image-20250409211845554.png)

1. Runnable<-> Blocked: 只有等待synchronized锁没有获取到。（调用阻塞API时，OS层面线程会切换到休眠【等待IO】，但是在JVM看来等待CPU 与 等待IO没有区别，都是Runnable）
2. Runnable<- >WAITING
   1. synchronized 里面的Object.wait
   2. Thread.join
   3. LockSupport.park unpark
3. Runnable<->TIMED_WAITING
   1. Thread.sleep(long)
   2. synchronized {Object.wait(long)}
   3. Thread.join(long)
   4. LockSupport.parkNanos(long)
   5. LockSupport.parkUtil(long)
4. New-> Runnable: 调用线程Thread.start
5. Runnable->TERMINATED: 
   1. 执行完或者异常
   2. stop【不要用，类似suspend和resume】
   3. interrupt 通知线程

被动：

- 当线程A处于WAITING、TIMED_WAITING状态时，如果其他线程调用线程A的interrupt()方法，会使线程A返回到RUNNABLE状态，同时线程A的代码会触发InterruptedException异常。
- 当线程A处于RUNNABLE状态时，并且阻塞在java.nio.channels.InterruptibleChannel上时，如果其他线程调用线程A的interrupt()方法，线程A会触发java.nio.channels.ClosedByInterruptException这个异常
- 阻塞在java.nio.channels.Selector上时，如果其他线程调用线程A的interrupt()方法，线程A的java.nio.channels.Selector会立即返回

主动：

- 线程可以自己通过isInterrupted方法，检测自己是不是被中断了。

# 创建多少线程才是合适的

“各种线程池的线程数量调整成多少是合适的？”或者“Tomcat的线程数、Jdbc连接池的连接数是多少？”

## 为什么要使用多线程

两个指标：延迟和吞吐量。希望降低延迟，提高吞吐量。

优化思路：优化算法、将硬件的性能发挥到极致，也就是CPU和IO的利用率。

- 操作系统：利用中断机制 避免CPU轮询IO，提升了CPU的使用率。
- 程序，多线程。



假设程序按照CPU计算和I/O操作交叉执行的方式运行，而且CPU计算和I/O操作的耗时是1:1。

- 如果只有一个线程，执行CPU计算的时候，I/O设备空闲；执行I/O操作的时候，CPU空闲，所以CPU的利用率和I/O设备的利用率都是50%
- 如果有两个线程，当线程A执行CPU计算的时候，线程B执行I/O操作；当线程A执行I/O操作的时候，线程B执行CPU计算，这样CPU的利用率和I/O设备的利用率就都达到了100%。

在单核时代，多线程就是用来平衡CPU和IO设备的，如果程序只有CPU计算，而没有I/O操作的话，多线程不但不会提升性能，还会使性能变得更差，原因是增加了线程切换的成本。在多核时代，这种纯计算型的程序也可以利用多线程来提升性能。



计算1+2+… … +100亿的值，如果在4核的CPU上利用4个线程执行，线程A计算[1，25亿)，线程B计算[25亿，50亿)，线程C计算[50，75亿)，线程D计算[75亿，100亿]，之后汇总，那么理论上应该比一个线程计算[1，100亿]快将近4倍，响应时间能够降到25%。一个线程，对于4核的CPU，CPU的利用率只有25%，而4个线程，则能够将CPU的利用率提高到100%。

## 创建多少线程合适?

大部分情况下，I/O操作执行的时间相对于CPU计算来说都非常长，这种场景我们一般都称为I/O密集型计算；和I/O密集型计算相对的就是CPU密集型计算了，CPU密集型计算大部分场景下都是纯CPU计算。

1. CPU密集计算，多线程本质是提升多核CPU利用率；为了避免增加线程切换的成本呢 和考虑到 因为偶尔的内存页失效或者其他原因阻塞，一般线程数量=CPU核数+1
2. IO密集型计算，最佳的线程数是与程序中CPU计算和I/O操作的耗时比相关的。最佳线程数=1 +（I/O耗时 / CPU耗时）：当线程A执行IO操作时，另外R个线程正好执行完各自的CPU计算。这样CPU的利用率就达到了100%。 如果是多核CPU，最佳线程数=CPU核数 * [ 1 +（I/O耗时 / CPU耗时）]



> 假设一个 I/O 密集型任务中，每次 I/O 操作需要 100 毫秒（I/O 耗时），而每次 CPU 处理数据的时间只需要 10 毫秒（CPU 耗时）。根据公式：
> 最佳线程数 = 1 +（100 / 10） = 1 + 10 = 11
>
> 
>
> 这意味着在这个场景中，为了充分利用 CPU 资源，提高程序的整体性能，理想情况下应该创建 11 个线程。其中 1 个线程负责处理 CPU 相关的任务，另外 10 个线程用于在 I/O 操作等待期间执行其他任务，从而使得 CPU 在大部分时间都处于忙碌状态，提高了系统的并发处理能力和资源利用率。

# 为什么局部变量是线程安全的

## 方法是如何执行的

```
int a = 7；
int[] b = fibonacci(a);
int[] c = b;
```

调用fibonacci(a)的时候，CPU要先找到方法 fibonacci() 的地址，然后跳转到这个地址去执行代码，最后CPU执行完方法 fibonacci() 之后，要能够返回。首先找到调用方法的下一条语句的地址：也就是`int[] c=b;`的地址，再跳转到这个地址去执行

![image-20250410210511253](image\image-20250410210511253.png)



CPU怎么找调用方法的参数和返回值？堆栈寄存器，调用栈。

![image-20250410210627462](image\image-20250410210627462.png)

每个方法在调用栈里面有自己的独立空间：栈帧，里面有方法需要的参数和返回地址。调用方法时，就会创建新的栈帧，压入调用栈；方法返回时，栈帧就弹出。

## 局部变量存哪里

局部变量的作用域是方法内部，**局部变量就是放到了调用栈里**。

![image-20250410210838528](image\image-20250410210838528.png)

局部变量是和方法同生共死的，一个变量如果想跨越方法的边界，就必须创建在堆里。

## 调用栈与线程

两个线程可以同时用不同的参数调用相同的方法，那调用栈和线程之间是什么关系呢？答案是：**每个线程都有自己独立的调用栈**。因为如果不是这样，那两个线程就互相干扰了。

![image-20250410210924262](image\image-20250410210924262.png)

因为每个线程都有自己的调用栈，局部变量保存在线程各自的调用栈里面，不会共享，所以自然也就没有并发问题。

## 线程封闭

方法里面的局部变量，因为不会和其他线程共享，所以没有并发问题。这个思路就叫做线程封闭：仅在单线程内访问数据，不存在共享，无并发问题。



例如从数据库连接池里获取的连接Connection，在JDBC规范里并没有要求这个Connection必须是线程安全的。数据库连接池通过线程封闭技术，保证一个Connection一旦被一个线程获取之后，在这个线程关闭Connection之前的这段时间里，不会再分配给其他线程，从而保证了Connection不会有并发问题。

# 如何利用面向对象思想写好并发程序

## 1. 如何封装共享变量

面向对象思想里面有一个很重要的特性是**封装**，封装的通俗解释就是**将属性和实现细节封装在对象内部**，外界对象**只能通过**目标对象提供的**公共方法来间接访问**这些内部属性。

我们把共享变量作为对象的属性，那对于共享变量的访问路径就是对象的公共方法，这里可以做并发访问策略。利用面向对象思想写并发程序的思路，其实就这么简单：**将共享变量作为对象属性封装在内部，对所有公共方法制定并发访问策略**。【注意逸出】

```
public class Counter {
  private long value;
  synchronized long get(){
    return value;
  }
  synchronized long addOne(){
    return ++value;
  }
}
```

信用卡账户有卡号、姓名、身份证、信用额度、已出账单、未出账单等很多共享变量。很多共享变量的值是不会变的，例如信用卡账户的卡号、姓名、身份证。**对于这些不会发生变化的共享变量，建议你用final关键字来修饰**。这样既能避免并发问题，也能很明了地表明你的设计意图.

## 2. 识别共享变量之间的约束条件

识别共享变量间的约束条件非常重要。因为**这些约束条件，决定了并发访问策略**。例如，库存管理里面有个合理库存的概念，库存量不能太高，也不能太低，它有一个上限和一个下限。

```java
public class SafeWM {
  // 库存上限
  private final AtomicLong upper =
        new AtomicLong(0); // 原子类不需要同步。
  // 库存下限
  private final AtomicLong lower =
        new AtomicLong(0);
  // 设置库存上限
  void setUpper(long v){
    upper.set(v);
  }
  // 设置库存下限
  void setLower(long v){
    lower.set(v);
  }
  // 省略其他业务代码
}
```

约束条件: 错误的写法，竞态条件，判断过后可能就不满足条件了。

```java
// 设置库存上限
  void setUpper(long v){
    // 检查参数合法性
    if (v < lower.get()) {
      throw new IllegalArgumentException();
    }
    upper.set(v);
  }
  // 设置库存下限
  void setLower(long v){
    // 检查参数合法性
    if (v > upper.get()) {
      throw new IllegalArgumentException();
    }
    lower.set(v);
  }
```

在设计阶段，我们**一定要识别出所有共享变量之间的约束条件**

## 3. 指定并发访问策略

1. 避免共享：避免共享的技术主要是利于线程本地存储以及为每个任务分配独立的线程。
2. 不变模式：这个在Java领域应用的很少，但在其他领域却有着广泛的应用，例如Actor模式、CSP模式以及函数式编程的基础都是不变模式。
3. 管程及其他同步工具：Java领域万能的解决方案是管程，但是对于很多特定场景，使用Java并发包提供的读写锁、并发容器等同步工具会更好。



原则：

- 优先使用成熟的工具类：Java SDK并发包里提供了丰富的工具类
- 迫不得已时才使用低级的同步原语：低级的同步原语主要指的是synchronized、Lock、Semaphore等
- 避免过早优化：安全第一，并发程序首先要保证安全，出现性能瓶颈后再优化

# 总结课（1）

![image-20250411210213674](image\image-20250411210213674.png)

**锁，应是私有的、不可变的、不可重用的**。

Integer String因为可能是被重用的，可能锁被其他代码使用，可能拿不到锁。

```java
class Account {
  // 账户余额  
  private Integer balance;
  // 账户密码
  private String password;
  // 取款
  void withdraw(Integer amt) {
    synchronized(balance) {
      if (this.balance > amt){
        this.balance -= amt;
      }
    }
  } 
  // 更改密码
  void updatePassword(String pw){
    synchronized(password) {
      this.password = pw;
    }
  } 
}
```



最佳实践

```java
// 普通对象锁
private final Object 
  lock = new Object();
// 静态对象锁
private static final Object
  lock = new Object(); 
```

方法的调用，是先计算参数，然后将参数压入调用栈之后才会执行方法体.

这有个额外的知识点：

```java
logger.debug("The var1：" + 
  var1 + ", var2:" + var2); // info级别日志打印的时候，仍然会计算参数

logger.debug("The var1：{}, var2:{}", 
  var1, var2); // 仅仅是参数压栈，并不会计算。
```

中断标识可能会自动清除：

```java
Thread th = Thread.currentThread();
while(true) {
  if(th.isInterrupted()) {
    break;
  }
  // 省略业务代码无数
  try {
    Thread.sleep(100);
  }catch (InterruptedException e){
    e.printStackTrace();
      // 重新设置中断标志位
  th.interrupt();
  }
}
```

# Lock 和Condition

## 为什么还有个Lock

破坏不可抢占条件的话，synchronized做不到。我们希望做到：

> 对于“不可抢占”这个条件，占用部分资源的线程进一步申请其他资源时，如果申请不到，可以主动释放它占有的资源，这样不可抢占这个条件就破坏掉了。



1. 能响应中断：阻塞中的线程希望能被唤醒，释放它之前占有的锁
2. 支持超时：线程一段时间没有获取到锁，不是进入阻塞态而是返回错误，能释放之前锁
3. 非阻塞地获取锁：获取锁不到，不被阻塞，可以释放之前的锁。

```java
// 支持中断的API
void lockInterruptibly() 
  throws InterruptedException;
// 支持超时的API
boolean tryLock(long time, TimeUnit unit) 
  throws InterruptedException;
// 支持非阻塞获取锁的API
boolean tryLock();
```

## 如何保证可见性

```java
class X {
  private final Lock rtl =
  new ReentrantLock();
  int value;
  public void addOne() {
    // 获取锁
    rtl.lock();  
    try {
      value+=1;
    } finally {
      // 保证锁能释放
      rtl.unlock();
    }
  }
}
```

Java SDK里面的ReentrantLock，内部持有一个volatile 的成员变量state，获取锁的时候，会读写state的值；解锁的时候，也会读写state的值。也就是说，在执行value+=1之前，程序先读写了一次volatile变量state，在执行value+=1之后，又读写了一次volatile变量state。根据相关的Happens-Before规则：

```java
// 省略代码无数
state = 1;
} // 解锁 unlock() {

// 省略代码无数
state = 0;
} }
```



1. **顺序性规则**：对于线程T1，value+=1 Happens-Before 释放锁的操作unlock()；

2. **volatile变量规则**：由于state = 1会先读取state，所以线程T1的unlock()操作Happens-Before线程T2的lock()操作；

3. **传递性规则**：线程 T1的value+=1 Happens-Before 线程 T2 的 lock() 操作。

   class SampleLock { volatile int state; // 加锁 lock() {

## 什么是可重入锁？

一个线程获取到锁之后，如果想再获取一下锁，能顺利获取就是可重入锁。

可重入函数，指的是多个线程可以同时调用该函数，并且支持线程切换，每个线程都能得到正确的结果。（必定是线程安全的）

## 公平锁与非公平锁

```java
//无参构造函数：默认非公平锁
public ReentrantLock() {
    sync = new NonfairSync();
}
//根据公平策略参数创建锁
public ReentrantLock(boolean fair){
    sync = fair ? new FairSync() 
                : new NonfairSync();
}
```

入口等待队列，锁都对应着一个等待队列，如果一个线程没有获得锁，就会进入等待队列，当有线程释放锁的时候，就需要从等待队列中唤醒一个等待的线程。如果是公平锁，唤醒的策略就是谁等待的时间长，就唤醒谁，很公平；如果是非公平锁，则不提供这个公平保证，有可能等待时间短的线程反而先被唤醒。

## 用锁的最佳实践

1. 永远只在更新对象的成员变量时加锁
2. 永远只在访问可变的成员变量时加锁
3. 永远不在调用其他对象的方法时加锁

关于第三条，调用其他对象的方法，实在是太不安全了，也许“其他”方法里面有线程sleep()的调用，也可能会有奇慢无比的I/O操作，这些都会严重影响性能。更可怕的是，“其他”类的方法可能也会加锁，然后双重加锁就可能导致死锁



减少锁的持有时间、减小锁的粒度.

# Dubbo如何利用管程实现异步转同步

Condition能支持多个条件变量

## 利用两个条件变量实现阻塞队列

```
public class BlockedQueue<T>{
  final Lock lock =
    new ReentrantLock();
  // 条件变量：队列不满  
  final Condition notFull =
    lock.newCondition();
  // 条件变量：队列不空  
  final Condition notEmpty =
    lock.newCondition();

  // 入队
  void enq(T x) {
    lock.lock();
    try {
      while (队列已满){
        // 等待队列不满
        notFull.await();
      }  
      // 省略入队操作...
      //入队后,通知可出队
      notEmpty.signal();
    }finally {
      lock.unlock();
    }
  }
  // 出队
  void deq(){
    lock.lock();
    try {
      while (队列已空){
        // 等待队列不空
        notEmpty.await();
      }  
      // 省略出队操作...
      //出队后，通知可入队
      notFull.signal();
    }finally {
      lock.unlock();
    }  
  }
}
```

## 同步和异步

**调用方是否需要等待结果，如果需要等待结果，就是同步；如果不需要等待结果，就是异步**。

1. 调用方创建一个子线程，在子线程中执行方法调用，这种调用我们称为异步调用；
2. 方法实现的时候，创建一个新的线程执行主要逻辑，主线程直接return，这种方法我们一般称为异步方法。

## Dubbo

异步的场景还是挺多的，比如TCP协议本身就是异步的，我们工作中经常用到的RPC调用，**在TCP协议层面，发送完RPC请求后，线程是不会等待RPC的响应结果的**。

Dubbo帮我们做了异步转同步的动作

```java
DemoService service = 初始化部分省略
String message = 
  service.sayHello("dubbo");
System.out.println(message);
```

调用线程阻塞了，线程状态是TIMED_WAITING。本来发送请求是异步的，但是调用线程却阻塞了，说明Dubbo帮我们做了异步转同步的事情。通过调用栈，你能看到线程是阻塞在DefaultFuture.get()方法上

![image-20250412102913138](image\image-20250412102913138.png)

```java
public class DubboInvoker{
  Result doInvoke(Invocation inv){
    // 下面这行就是源码中108行
    // 为了便于展示，做了修改
    return currentClient 
      .request(inv, timeout)
      .get();
  }
}
```

需求：当RPC返回结果之前，阻塞调用线程，让调用线程等待；当RPC返回结果后，唤醒调用线程，让调用线程重新执行。

```java
// 创建锁与条件变量
private final Lock lock 
    = new ReentrantLock();
private final Condition done 
    = lock.newCondition();

// 调用方通过该方法等待结果
Object get(int timeout){
  long start = System.nanoTime();
  lock.lock();
  try {
	while (!isDone()) {
	  done.await(timeout);
      long cur=System.nanoTime();
	  if (isDone() || 
          cur-start > timeout){
	    break;
	  }
	}
  } finally {
	lock.unlock();
  }
  if (!isDone()) {
	throw new TimeoutException();
  }
  return returnFromResponse();
}
// RPC结果是否已经返回
boolean isDone() {
  return response != null;
}
// RPC结果返回时调用该方法   
private void doReceived(Response res) {
  lock.lock();
  try {
    response = res;
    if (done != null) {
      done.signal();
    }
  } finally {
    lock.unlock();
  }
}
```

调用lock()获取锁，在finally里面调用unlock()释放锁；获取锁后，通过经典的在循环中调用await()方法来实现等待。

当RPC结果返回时，会调用doReceived()方法，这个方法里面，调用lock()获取锁，在finally里面调用unlock()释放锁，获取锁后通过调用signal()来通知调用线程，结果已经返回，不用继续等待了。

例如创建云主机，就是一个异步的API，调用虽然成功了，但是云主机并没有创建成功，你需要调用另外一个API去轮询云主机的状态。如果你需要在项目内部封装创建云主机的API，你也会面临异步转同步的问题，因为同步的API更易用。



# Semaphore：实现一个限流器

## 信号量模型

![image-20250412103415811](image\image-20250412103415811.png)

- init()：设置计数器的初始值。
- down()：计数器的值减1；如果此时计数器的值小于0，则当前线程将被阻塞，否则当前线程可以继续执行。
- up()：计数器的值加1；如果此时计数器的值小于或者等于0，则唤醒等待队列中的一个线程，并将其从等待队列中移除。

init()、down()和up()三个方法都是原子性的，并且这个原子性是由信号量模型的实现方保证的

```java
class Semaphore{
  // 计数器
  int count;
  // 等待队列
  Queue queue;
  // 初始化操作
  Semaphore(int c){
    this.count=c;
  }
  // 
  void down(){
    this.count--;
    if(this.count<0){
      //将当前线程插入等待队列
      //阻塞当前线程
    }
  }
  void up(){
    this.count++;
    if(this.count<=0) {
      //移除等待队列中的某个线程T
      //唤醒线程T
    }
  }
}
```

down()、up()这两个操作历史上最早称为P操作和V操作，所以信号量模型也被称为PV原语。

## 如何使用信号量

只需要在进入临界区之前执行一下down()操作，退出临界区之前执行一下up()操作就可以了。下面是Java代码的示例，acquire()就是信号量里的down()操作，release()就是信号量里的up()操作

```java
static int count;
//初始化信号量
static final Semaphore s 
    = new Semaphore(1);
//用信号量保证互斥    
static void addOne() {
  s.acquire();
  try {
    count+=1;
  } finally {
    s.release();
  }
}
```

假设两个线程T1和T2同时访问addOne()方法，当它们同时调用acquire()的时候，由于acquire()是一个原子操作，所以只能有一个线程（假设T1）把信号量里的计数器减为0，另外一个线程（T2）则是将计数器减为-1。对于线程T1，信号量里面的计数器的值是0，大于等于0，所以线程T1会继续执行；对于线程T2，信号量里面的计数器的值是-1，小于0，按照信号量模型里对down()操作的描述，线程T2将被阻塞。所以此时只有线程T1会进入临界区执行`count+=1；`



当线程T1执行release()操作，也就是up()操作的时候，信号量里计数器的值是-1，加1之后的值是0，小于等于0，按照信号量模型里对up()操作的描述，此时等待队列中的T2将会被唤醒。于是T2在T1执行完临界区代码之后才获得了进入临界区执行的机会，从而保证了互斥性。

## 快速实现一个限流器

Semaphore还有一个功能是Lock不容易实现的，那就是：**Semaphore可以允许多个线程访问一个临界区**。



比较常见的需求就是我们工作中遇到的各种池化资源，例如连接池、对象池、线程池等等。其中，你可能最熟悉数据库连接池，在同一时刻，一定是允许多个线程同时使用连接池的，当然，每个连接在被释放前，是不允许其他线程使用的。



如果我们把计数器的值设置成对象池里对象的个数N，就能完美解决对象池的限流问题了

```java
class ObjPool<T, R> {
  final List<T> pool;
  // 用信号量实现限流器
  final Semaphore sem;
  // 构造函数
  ObjPool(int size, T t){
    pool = new Vector<T>(){};
    for(int i=0; i<size; i++){
      pool.add(t);
    }
    sem = new Semaphore(size);
  }
  // 利用对象池的对象，调用func
  R exec(Function<T,R> func) {
    T t = null;
    sem.acquire();
    try {
      t = pool.remove(0);
      return func.apply(t);
    } finally {
      pool.add(t);
      sem.release();
    }
  }
}
// 创建对象池
ObjPool<Long, String> pool = 
  new ObjPool<Long, String>(10, 2);
// 通过对象池获取t，之后执行  
pool.exec(t -> {
    System.out.println(t);
    return t.toString();
});
```

用一个List来保存对象实例，用Semaphore实现限流器。关键的代码是ObjPool里面的exec()方法，这个方法里面实现了限流的功能。在这个方法里面，我们首先调用acquire()方法（与之匹配的是在finally里面调用release()方法），假设对象池的大小是10，信号量的计数器初始化为10，那么前10个线程调用acquire()方法，都能继续执行，相当于通过了信号灯，而其他线程则会阻塞在acquire()方法上。



对于通过信号灯的线程，我们为每个线程分配了一个对象 t（这个分配工作是通过pool.remove(0)实现的），分配完之后会执行一个回调函数func，而函数的参数正是前面分配的对象 t ；



执行完回调函数之后，它们就会释放对象（这个释放工作是通过pool.add(t)实现的），同时调用release()方法来更新信号量的计数器。如果此时信号量里计数器的值小于等于0，那么说明有线程在等待，此时会自动唤醒等待的线程

# ReadWriteLock：构建缓存

这样一个场景：读多写少，缓存。

```java
class Cache<K,V> {
  final Map<K, V> m =
    new HashMap<>();
  final ReadWriteLock rwl =
    new ReentrantReadWriteLock();
  // 读锁
  final Lock r = rwl.readLock();
  // 写锁
  final Lock w = rwl.writeLock();
  // 读缓存
  V get(K key) {
    r.lock();
    try { return m.get(key); }
    finally { r.unlock(); }
  }
  // 写缓存
  V put(K key, V value) {
    w.lock();
    try { return m.put(key, v); }
    finally { w.unlock(); }
  }
}
```

**使用缓存首先要解决缓存数据的初始化问题**。缓存数据的初始化，可以采用一次性加载的方式，也可以使用按需加载的方式

![image-20250412192133162](image\image-20250412192133162.png)

如果源头数据量非常大，那么就需要按需加载了，按需加载也叫懒加载，指的是只有当应用查询缓存，并且数据不在缓存里的时候，才触发加载源头相关数据进缓存的操作

## 缓存的按需加载

```java
class Cache<K,V> {
  final Map<K, V> m =
    new HashMap<>();
  final ReadWriteLock rwl = 
    new ReentrantReadWriteLock();
  final Lock r = rwl.readLock();
  final Lock w = rwl.writeLock();

  V get(K key) {
    V v = null;
    //读缓存
    r.lock();         ①
    try {
      v = m.get(key); ②
    } finally{
      r.unlock();     ③
    }
    //缓存中存在，返回
    if(v != null) {   ④
      return v;
    }  
    //缓存中不存在，查询数据库
    w.lock();         ⑤
    try {
      //再次验证
      //其他线程可能已经查询过数据库
      v = m.get(key); ⑥
      if(v == null){  ⑦
        //查询数据库
        v=省略代码无数
        m.put(key, v);
      }
    } finally{
      w.unlock();
    }
    return v; 
  }
}
```

在高并发的场景下，有可能会有多线程竞争写锁。假设缓存是空的，没有缓存任何东西，如果此时有三个线程T1、T2和T3同时调用get()方法，并且参数key也是相同的。那么它们会同时执行到代码⑤处，但此时只有一个线程能够获得写锁，假设是线程T1，线程T1获取写锁之后查询数据库并更新缓存，最终释放写锁。



此时线程T2和T3会再有一个线程能够获取写锁，假设是T2，如果不采用再次验证的方式，此时T2会再次查询数据库。T2释放写锁之后，T3也会再次查询一次数据库。而实际上线程T1已经把缓存的值设置好了，T2、T3完全没有必要再次查询数据库。所以，再次验证的方式，能够避免高并发场景下重复查询数据的问题



## 读写锁的升级与降级

```java
//读缓存
r.lock();         ①
try {
  v = m.get(key); ②
  if (v == null) {
    w.lock();
    try {
      //再次验证并更新缓存
      //省略详细代码
    } finally{
      w.unlock();
    }
  }
} finally{
  r.unlock();     ③
}
```

ReadWriteLock并不支持这种升级。在上面的代码示例中，读锁还没有释放，此时获取写锁，会导致写锁永久等待，最终导致相关线程都被阻塞，永远也没有机会被唤醒。锁的升级是不允许的.

锁的降级却是允许的

```java
class CachedData {
  Object data;
  volatile boolean cacheValid;
  final ReadWriteLock rwl =
    new ReentrantReadWriteLock();
  // 读锁  
  final Lock r = rwl.readLock();
  //写锁
  final Lock w = rwl.writeLock();

  void processCachedData() {
    // 获取读锁
    r.lock();
    if (!cacheValid) {
      // 释放读锁，因为不允许读锁的升级
      r.unlock();
      // 获取写锁
      w.lock();
      try {
        // 再次检查状态  
        if (!cacheValid) {
          data = ...
          cacheValid = true;
        }
        // 释放写锁前，降级为读锁
        // 降级是可以的
        r.lock(); ①
      } finally {
        // 释放写锁
        w.unlock(); 
      }
    }
    // 此处仍然持有读锁
    try {use(data);} 
    finally {r.unlock();}
  }
}
```

数据同步指的是保证缓存数据和源头数据的一致性。解决数据同步问题的一个最简单的方案就是**超时机制**。所谓超时机制指的是加载进缓存的数据不是长久有效的，而是有时效的，当缓存的数据超过时效，也就是超时之后，这条数据在缓存中就失效了。而访问缓存中失效的数据，会触发缓存重新从源头把数据加载进缓存。

当然也可以在源头数据发生变化时，快速反馈给缓存，但这个就要依赖具体的场景了。例如MySQL作为数据源头，可以通过近实时地解析binlog来识别数据是否发生了变化，如果发生了变化就将最新的数据推送给缓存。另外，还有一些方案采取的是数据库和缓存的双写方案。

# StampedLock

## 支持的三种模式

**写锁**、**悲观读锁**和**乐观读**。StampedLock里的写锁和悲观读锁加锁成功之后，都会返回一个stamp；然后解锁的时候，需要传入这个stamp。

```java
final StampedLock sl = 
  new StampedLock();

// 获取/释放悲观读锁示意代码
long stamp = sl.readLock();
try {
  //省略业务相关代码
} finally {
  sl.unlockRead(stamp);
}

// 获取/释放写锁示意代码
long stamp = sl.writeLock();
try {
  //省略业务相关代码
} finally {
  sl.unlockWrite(stamp);
}
```

ReadWriteLock支持多个线程同时读，但是当多个线程同时读的时候，所有的写操作会被阻塞；而StampedLock提供的乐观读，是允许一个线程获取写锁的，也就是说不是所有的写操作都被阻塞。

**乐观读这个操作是无锁的**，所以相比较ReadWriteLock的读锁，乐观读的性能更好一些。

tryOptimisticRead()是无锁的，所以共享变量x和y读入方法局部变量时，x和y有可能被其他线程修改了。因此最后读完之后，还需要再次验证一下是否存在写操作，这个验证操作是通过调用validate(stamp)来实现的。

```java
class Point {
  private int x, y;
  final StampedLock sl = 
    new StampedLock();
  //计算到原点的距离  
  int distanceFromOrigin() {
    // 乐观读
    long stamp = 
      sl.tryOptimisticRead();
    // 读入局部变量，
    // 读的过程数据可能被修改
    int curX = x, curY = y;
    //判断执行读操作期间，
    //是否存在写操作，如果存在，
    //则sl.validate返回false
    if (!sl.validate(stamp)){
      // 升级为悲观读锁
      stamp = sl.readLock();
      try {
        curX = x;
        curY = y;
      } finally {
        //释放悲观读锁
        sl.unlockRead(stamp);
      }
    }
    return Math.sqrt(
      curX * curX + curY * curY);
  }
}
```

如果执行乐观读操作的期间，存在写操作，会把乐观读升级为悲观读锁。这个做法挺合理的，否则你就需要在一个循环里反复执行乐观读，直到执行乐观读操作的期间没有写操作（只有这样才能保证x和y的正确性和一致性），而循环读会浪费大量的CPU。升级为悲观读锁，代码简练且不易出错，建议你在具体实践时也采用这样的方法。

## 理解乐观锁

在ERP的生产模块里，会有多个人通过ERP系统提供的UI同时修改同一条生产订单，那如何保证生产订单数据是并发安全的呢？



在生产订单的表 product_doc 里增加了一个数值型版本号字段 version，每次更新product_doc这个表的时候，都将 version 字段加1。生产订单的UI在展示的时候，需要查询数据库，此时将这个 version 字段和其他业务字段一起返回给生产订单UI。假设用户查询的生产订单的id=777，那么SQL语句类似下面这样：

```sql
select id，... ，version
from product_doc
where id=777
```



用户在生产订单UI执行保存操作的时候，后台利用下面的SQL语句更新生产订单，此处我们假设该条生产订单的 version=9。

```sql
update product_doc 
set version=version+1，...
where id=777 and version=9
```

如果这条SQL语句执行成功并且返回的条数等于1，那么说明从生产订单UI执行查询操作到执行保存操作期间，没有其他人修改过这条数据。因为如果这期间其他人修改过这条数据，那么版本号字段一定会大于9。



数据库里的乐观锁，查询的时候需要把 version 字段查出来，更新的时候要利用 version 字段做验证。这个 version 字段就类似于StampedLock里面的stamp。

## StampedLock使用注意事项

对于读多写少的场景StampedLock性能很好，简单的应用场景基本上可以替代ReadWriteLock，但是**StampedLock的功能仅仅是ReadWriteLock的子集**

1. **StampedLock不支持重入**
2. 如果线程阻塞在StampedLock的readLock()或者writeLock()上时，此时调用该阻塞线程的interrupt()方法，会导致CPU飙升。例如下面的代码中，线程T1获取写锁之后将自己阻塞，线程T2尝试获取悲观读锁，也会阻塞；如果此时调用线程T2的interrupt()方法来中断线程T2的话，你会发现线程T2所在CPU会飙升到100%。

```java
final StampedLock lock
  = new StampedLock();
Thread T1 = new Thread(()->{
  // 获取写锁
  lock.writeLock();
  // 永远阻塞在此处，不释放写锁
  LockSupport.park();
});
T1.start();
// 保证T1获取写锁
Thread.sleep(100);
Thread T2 = new Thread(()->
  //阻塞在悲观读锁
  lock.readLock()
);
T2.start();
// 保证T2阻塞在读锁
Thread.sleep(100);
//中断线程T2
//会导致线程T2所在CPU飙升
T2.interrupt();
T2.join();
```

**使用StampedLock一定不要调用中断操作，如果需要支持中断功能，一定使用可中断的悲观读锁readLockInterruptibly()和写锁writeLockInterruptibly()**。

## 最佳实践

```java
final StampedLock sl = 
  new StampedLock();

// 乐观读
long stamp = 
  sl.tryOptimisticRead();
// 读入方法局部变量
......
// 校验stamp
if (!sl.validate(stamp)){
  // 升级为悲观读锁
  stamp = sl.readLock();
  try {
    // 读入方法局部变量
    .....
  } finally {
    //释放悲观读锁
    sl.unlockRead(stamp);
  }
}
//使用方法局部变量执行业务操作
......
    
long stamp = sl.writeLock();
try {
  // 写共享变量
  ......
} finally {
  sl.unlockWrite(stamp);
}
```

StampedLock支持锁的降级（通过tryConvertToReadLock()方法实现）和升级（通过tryConvertToWriteLock()方法实现），但是建议你要慎重使用

# CountDownLatch 和 CyclicBarrier：多线程步调一致

## 需求描述

对账

![image-20250414181027550](image\image-20250414181027550.png)

```java
while(存在未对账订单){
  // 查询未对账订单
  pos = getPOrders();
  // 查询派送单
  dos = getDOrders();
  // 执行对账操作
  diff = check(pos, dos);
  // 差异写入差异库
  save(diff);
} 
```

## 利用并行优化

查询未对账订单getPOrders()和查询派送单getDOrders()相对较慢

![image-20250414181252918](image\image-20250414181252918.png)

查询未对账订单getPOrders()和查询派送单getDOrders()可以并行处理

![image-20250414181329180](image\image-20250414181329180.png)

创建了两个线程T1和T2，并行执行查询未对账订单getPOrders()和查询派送单getDOrders()这两个操作。在主线程中执行对账操作check()和差异写入save()两个操作。不过需要注意的是：主线程需要等待线程T1和T2执行完才能执行check()和save()这两个操作，为此我们通过调用T1.join()和T2.join()来实现等待，当T1和T2线程退出时，调用T1.join()和T2.join()的主线程就会从阻塞态被唤醒，从而执行之后的check()和save()

```java
while(存在未对账订单){
  // 查询未对账订单
  Thread T1 = new Thread(()->{
    pos = getPOrders();
  });
  T1.start();
  // 查询派送单
  Thread T2 = new Thread(()->{
    dos = getDOrders();
  });
  T2.start();
  // 等待T1、T2结束
  T1.join();
  T2.join();
  // 执行对账操作
  diff = check(pos, dos);
  // 差异写入差异库
  save(diff);
} 
```

## CountDownLatch优化

避免重复创建线程：线程池。前面主线程通过调用线程T1和T2的join()方法来等待线程T1和T2退出，但是在线程池的方案里，线程根本就不会退出，所以join()方法已经失效了。

```java
// 创建2个线程的线程池
Executor executor = 
  Executors.newFixedThreadPool(2);
while(存在未对账订单){
  // 查询未对账订单
  executor.execute(()-> {
    pos = getPOrders();
  });
  // 查询派送单
  executor.execute(()-> {
    dos = getDOrders();
  });

  /* ？？如何实现等待？？*/

  // 执行对账操作
  diff = check(pos, dos);
  // 差异写入差异库
  save(diff);
}   
```

弄一个计数器，初始值设置成2，当执行完`pos = getPOrders();`这个操作之后将计数器减1，执行完`dos = getDOrders();`之后也将计数器减1，在主线程里，等待计数器等于0；当计数器等于0时，说明这两个查询操作执行完了。等待计数器等于0其实就是一个条件变量，用管程实现起来也很简单。

```java
// 创建2个线程的线程池
Executor executor = 
  Executors.newFixedThreadPool(2);
while(存在未对账订单){
  // 计数器初始化为2
  CountDownLatch latch = 
    new CountDownLatch(2);
  // 查询未对账订单
  executor.execute(()-> {
    pos = getPOrders();
    latch.countDown();
  });
  // 查询派送单
  executor.execute(()-> {
    dos = getDOrders();
    latch.countDown();
  });

  // 等待两个查询操作结束
  latch.await();

  // 执行对账操作
  diff = check(pos, dos);
  // 差异写入差异库
  save(diff);
}
```

## 进一步优化

在执行对账操作的时候，可以同时去执行下一轮的查询操作

![image-20250414181628253](image\image-20250414181628253.png)

两次查询操作能够和对账操作并行，对账操作还依赖查询操作的结果，这明显有点生产者-消费者的意思，两次查询操作是生产者，对账操作是消费者。既然是生产者-消费者模型，那就需要有个队列，来保存生产者生产的数据，而消费者则从这个队列消费数据。



订单查询操作将订单查询结果插入订单队列，派送单查询操作将派送单插入派送单队列，这两个队列的元素之间是有一一对应的关系的。两个队列的好处是，对账操作可以每次从订单队列出一个元素，从派送单队列出一个元素，然后对这两个元素执行对账操作，这样数据一定不会乱掉。



个线程T1执行订单的查询工作，一个线程T2执行派送单的查询工作，当线程T1和T2都各自生产完1条数据的时候，通知线程T3执行对账操作。这个想法虽看上去简单，但其实还隐藏着一个条件，线程T1和线程T2要互相等待，步调要一致；同时当线程T1和T2都生产完一条数据的时候，还要能够通知线程T3执行对账操作。

![image-20250414181807019](image\image-20250414181807019.png)

## CyclicBarrier实现线程同步

一个是线程T1和T2要做到步调一致，另一个是要能够通知到线程T3。依然可以利用一个计数器来解决这两个难点，计数器初始化为2，线程T1和T2生产完一条数据都将计数器减1，如果计数器大于0则线程T1或者T2等待。如果计数器等于0，则通知线程T3，并唤醒等待的线程T1或者T2，与此同时，将计数器重置为2，这样线程T1和线程T2生产下一条数据的时候就可以继续使用这个计数器了。





线程T1负责查询订单，当查出一条时，调用 `barrier.await()` 来将计数器减1，同时等待计数器变成0；线程T2负责查询派送单，当查出一条时，也调用 `barrier.await()` 来将计数器减1，同时等待计数器变成0；当T1和T2都调用 `barrier.await()` 的时候，计数器会减到0，此时T1和T2就可以执行下一条语句了，同时会调用barrier的回调函数来执行对账操作。



非常值得一提的是，CyclicBarrier的计数器有自动重置的功能，当减到0的时候，会自动重置你设置的初始值。

```java
// 订单队列
Vector<P> pos;
// 派送单队列
Vector<D> dos;
// 执行回调的线程池 
Executor executor = 
  Executors.newFixedThreadPool(1);
final CyclicBarrier barrier =
  new CyclicBarrier(2, ()->{
    executor.execute(()->check());
  });

void check(){
  P p = pos.remove(0);
  D d = dos.remove(0);
  // 执行对账操作
  diff = check(p, d);
  // 差异写入差异库
  save(diff);
}

void checkAll(){
  // 循环查询订单库
  Thread T1 = new Thread(()->{
    while(存在未对账订单){
      // 查询订单库
      pos.add(getPOrders());
      // 等待
      barrier.await();
    }
  });
  T1.start();  
  // 循环查询运单库
  Thread T2 = new Thread(()->{
    while(存在未对账订单){
      // 查询运单库
      dos.add(getDOrders());
      // 等待
      barrier.await();
    }
  });
  T2.start();
}
```

## 区别

1. **CountDownLatch主要用来解决一个线程等待多个线程的场景**，可以类比旅游团团长要等待所有的游客到齐才能去下一个景点；而**CyclicBarrier是一组线程之间互相等待**，更像是几个驴友之间不离不弃。
2. CountDownLatch的计数器是不能循环利用的，也就是说一旦计数器减到0，再有线程调用await()，该线程会直接通过。但**CyclicBarrier的计数器是可以循环利用的**，而且具备自动重置的功能，一旦计数器减到0会自动重置到你设置的初始值
3. CyclicBarrier还可以设置回调函数



# 并发容器的坑

## 同步容器以及注意事项

List、Map、Set和Queue；如何将非线程安全的容器变成线程安全的容器？只要把非线程安全的容器封装在对象内部，然后控制好访问路径就可以了。

```java
SafeArrayList<T>{
  //封装ArrayList
  List<T> c = new ArrayList<>();
  //控制访问路径
  synchronized
  T get(int idx){
    return c.get(idx);
  }

  synchronized
  void add(int idx, T t) {
    c.add(idx, t);
  }

  synchronized
  boolean addIfNotExist(T t){
    if(!c.contains(t)) {
      c.add(t);
      return true;
    }
    return false;
  }
}
```

在Collections这个类中还提供了一套完备的包装类，比如下面的示例代码中，分别把ArrayList、HashSet和HashMap包装成了线程安全的List、Set和Map。

```java
List list = Collections.
  synchronizedList(new ArrayList());
Set set = Collections.
  synchronizedSet(new HashSet());
Map map = Collections.
  synchronizedMap(new HashMap());
```

**组合操作需要注意竞态条件问题**，即便每个操作都能保证原子性，也并不能保证组合操作的原子性，这个一定要注意。



**一个容易被忽视的“坑”是用迭代器遍历容器**，例如在下面的代码中，通过迭代器遍历容器list，对每个元素调用foo()方法，这就存在并发问题，这些组合的操作不具备原子性。

```java
List list = Collections.
  synchronizedList(new ArrayList());
Iterator i = list.iterator(); 
while (i.hasNext())
  foo(i.next());
```

而正确做法是下面这样，锁住list之后再执行遍历操作。如果你查看Collections内部的包装类源码，你会发现包装类的公共方法锁的是对象的this，其实就是我们这里的list，所以锁住list绝对是线程安全的。

```java
List list = Collections.
  synchronizedList(new ArrayList());
synchronized (list) {  
  Iterator i = list.iterator(); 
  while (i.hasNext())
    foo(i.next());
}    
```

基于synchronized这个同步关键字实现的，所以也被称为**同步容器**。Java提供的同步容器还有Vector、Stack和Hashtable

## 并发容器和注意事项

![image-20250414182525972](image\image-20250414182525972.png)

### List

**CopyOnWriteArrayList**内部维护了一个数组，成员变量array就指向这个内部数组，所有的读操作都是基于array进行的，如下图所示，迭代器Iterator遍历的就是array数组。



如果在遍历array的同时，还有一个写操作，例如增加元素，CopyOnWriteArrayList是如何处理的呢？CopyOnWriteArrayList会将array复制一份，然后在新复制处理的数组上执行增加元素的操作，执行完之后再将array指向这个新的数组。读写是可以并行的，**遍历操作一直都是基于原array执行，而写操作则是基于新array进行**。



一个是应用场景，CopyOnWriteArrayList仅适用于写操作非常少的场景，而且能够容忍读写的短暂不一致。例如上面的例子中，写入的新元素并不能立刻被遍历到。

另一个需要注意的是，CopyOnWriteArrayList迭代器是只读的，不支持增删改。因为迭代器遍历的仅仅是一个快照，而对快照进行增删改是没有意义的。



### Map

**ConcurrentHashMap的key是无序的，而ConcurrentSkipListMap的key是有序的**。所以如果你需要保证key的顺序，就只能使用ConcurrentSkipListMap。

它们的key和value都不能为空，否则会抛出`NullPointerException`这个运行时异常。

![image-20250414182712248](image\image-20250414182712248.png)

ConcurrentSkipListMap里面的SkipList本身就是一种数据结构，中文一般都翻译为“跳表”。跳表插入、删除、查询操作平均的时间复杂度是 O(log n)，理论上和并发线程数没有关系，所以在并发度非常高的情况下，若你对ConcurrentHashMap的性能还不满意，可以尝试一下ConcurrentSkipListMap。

### Set

CopyOnWriteArraySet和ConcurrentSkipListSet，使用场景可以参考前面讲述的CopyOnWriteArrayList和ConcurrentSkipListMap，它们的原理都是一样的

### Queue

一个维度是**阻塞与非阻塞**，所谓阻塞指的是当队列已满时，入队操作阻塞；当队列已空时，出队操作阻塞。另一个维度是**单端与双端**，单端指的是只能队尾入队，队首出队；而双端指的是队首队尾皆可入队出队。Java并发包里**阻塞队列都用Blocking关键字标识，单端队列使用Queue标识，双端队列使用Deque标识**。



1.**单端阻塞队列**：其实现有ArrayBlockingQueue、LinkedBlockingQueue、SynchronousQueue、LinkedTransferQueue、PriorityBlockingQueue和DelayQueue。内部一般会持有一个队列，这个队列可以是数组（其实现是ArrayBlockingQueue）也可以是链表（其实现是LinkedBlockingQueue）；甚至还可以不持有队列（其实现是SynchronousQueue），此时生产者线程的入队操作必须等待消费者线程的出队操作。而LinkedTransferQueue融合LinkedBlockingQueue和SynchronousQueue的功能，性能比LinkedBlockingQueue更好；PriorityBlockingQueue支持按照优先级出队；DelayQueue支持延时出队。

![image-20250414182902077](image\image-20250414182902077.png)

2.**双端阻塞队列**：其实现是LinkedBlockingDeque。

3.**单端非阻塞队列**：其实现是ConcurrentLinkedQueue。-

4.**双端非阻塞队列**：其实现是ConcurrentLinkedDeque。



使用队列时，需要格外注意队列是否支持有界（所谓有界指的是内部的队列是否有容量限制）。实际工作中，一般都不建议使用无界的队列，因为数据量大了之后很容易导致OOM。只有ArrayBlockingQueue和LinkedBlockingQueue是支持有界的

# 原子类：无锁工具

可见性：volatile； 原子性：互斥。



```java
public class Test {
  AtomicLong count = 
    new AtomicLong(0);
  // 这个是线程安全的。  
  void add10K() {
    int idx = 0;
    while(idx++ < 10000) {
      count.getAndIncrement();
    }
  }
}
```

无锁方案：最大的好处是性能。没有加锁 解锁的性能消耗，还能保证互斥

## 无锁方案的实现原理

CPU为了解决并发问题，提供了CAS指令（CAS，全称是Compare And Swap，即“比较并交换”）。CAS指令包含3个参数：共享变量的内存地址A、用于比较的值B和共享变量的新值C；并且只有当内存中地址A处的值等于B时，才能将内存中地址A处的值更新为新值C。**作为一条CPU指令，CAS指令本身是能够保证原子性的**。

一个是期望值expect，另一个是需要写入的新值newValue，**只有当目前count的值和期望值expect相等时，才会将count更新为newValue**。

```java
class SimulatedCAS{
  int count；
  synchronized int cas(
    int expect, int newValue){
    // 读目前count的值
    int curValue = count;
    // 比较目前count值是否==期望值
    if(curValue == expect){
      // 如果是，则更新count的值
      count = newValue;
    }
    // 返回写入前的值
    return curValue;
  }
}
```

基于内存中count的当前值A计算出来的count+=1为A+1，在将A+1写入内存的时候，很可能此时内存中count已经被其他线程更新过了，这样就会导致错误地覆盖其他线程写入的值。也就是说，只有当内存中count的值等于期望值A时，才能将内存中count的值更新为计算结果A+1。

使用CAS来解决并发问题，一般都会伴随着自旋，而所谓自旋，其实就是循环尝试。例如，实现一个线程安全的`count += 1`操作，“CAS+自旋”的实现方案如下所示，首先计算newValue = count+1，如果cas(count,newValue)返回的值不等于count，则意味着线程在执行完代码①处之后，执行代码②处之前，count的值被其他线程更新过。那此时该怎么处理呢？可以采用自旋方案，就像下面代码中展示的，可以重新读count最新的值来计算newValue并尝试再次更新，直到成功。

```java
class SimulatedCAS{
  volatile int count;
  // 实现count+=1
  addOne(){
    do {
      newValue = count+1; //①
    }while(count !=
      cas(count,newValue) //②
  }
  // 模拟实现CAS，仅用来帮助理解
  synchronized int cas(
    int expect, int newValue){
    // 读目前count的值
    int curValue = count;
    // 比较目前count值是否==期望值
    if(curValue == expect){
      // 如果是，则更新count的值
      count= newValue;
    }
    // 返回写入前的值
    return curValue;
  }
}
```

**ABA**的问题。StampedLock之类的，version

## 原子化的count += 1

```java
// this和valueOffset两个参数可以唯一确定共享变量的内存地址。
final long getAndIncrement() {
  return unsafe.getAndAddLong(
    this, valueOffset, 1L);
}
```

unsafe.getAndAddLong()方法的源码如下，该方法首先会在内存中读取共享变量的值，之后循环调用compareAndSwapLong()方法来尝试设置共享变量的值，直到成功为止。compareAndSwapLong()是一个native方法，只有当内存中共享变量的值等于expected时，才会将共享变量的值更新为x，并且返回true；否则返回fasle。compareAndSwapLong的语义和CAS指令的语义的差别仅仅是返回值不同而已。

```java
public final long getAndAddLong(
  Object o, long offset, long delta){
  long v;
  do {
    // 读取内存中的值
    v = getLongVolatile(o, offset);
  } while (!compareAndSwapLong(
      o, offset, v, v + delta));
  return v;
}
//原子性地将变量更新为x
//条件是内存中的值等于expected
//更新成功则返回true
native boolean compareAndSwapLong(
  Object o, long offset, 
  long expected,
  long x);
```

```java
do {
  // 获取当前值
  oldV = xxxx；
  // 根据当前值计算新值
  newV = ...oldV...
}while(!compareAndSet(oldV,newV);
```

## 原子类概览

**原子化的基本数据类型、原子化的对象引用类型、原子化数组、原子化对象属性更新器**和**原子化的累加器**

![image-20250415122917346](image\image-20250415122917346.png)

AtomicBoolean、AtomicInteger和AtomicLong

```java
getAndIncrement() //原子化i++
getAndDecrement() //原子化的i--
incrementAndGet() //原子化的++i
decrementAndGet() //原子化的--i
//当前值+=delta，返回+=前的值
getAndAdd(delta) 
//当前值+=delta，返回+=后的值
addAndGet(delta)
//CAS操作，返回是否成功
compareAndSet(expect, update)
//以下四个方法
//新值可以通过传入func函数来计算
getAndUpdate(func)
updateAndGet(func)
getAndAccumulate(x,func)
accumulateAndGet(x,func)
```



原子化的对象引用类型：AtomicReference、AtomicStampedReference和AtomicMarkableReference。AtomicStampedReference和AtomicMarkableReference这两个原子类可以解决ABA问题。



增加一个版本号维度

```java
boolean compareAndSet(
  V expectedReference,
  V newReference,
  int expectedStamp,
  int newStamp) 
```

版本号简化成了一个Boolean值

```java
boolean compareAndSet(
  V expectedReference,
  V newReference,
  boolean expectedMark,
  boolean newMark)
```





原子化数组：AtomicIntegerArray、AtomicLongArray和AtomicReferenceArray。原子化地更新数组里面的每一个元素。这些类提供的方法和原子化的基本数据类型的区别仅仅是：每个方法多了一个数组的索引参数



原子化对象属性更新器：AtomicIntegerFieldUpdater、AtomicLongFieldUpdater和AtomicReferenceFieldUpdater

```java
public static <U>
AtomicXXXFieldUpdater<U> 
newUpdater(Class<U> tclass, 
  String fieldName)
```

需要注意的是，**对象属性必须是volatile类型的，只有这样才能保证可见性**；如果对象属性不是volatile类型的，newUpdater()方法会抛出IllegalArgumentException这个运行时异常。



原子化对象属性更新器相关的方法，相比原子化的基本数据类型仅仅是多了对象引用参数

```java
boolean compareAndSet(
  T obj, 
  int expect, 
  int update)
```



原子化的累加器：DoubleAccumulator、DoubleAdder、LongAccumulator和LongAdder.不支持compareAndSet()方法。如果你仅仅需要累加操作，使用原子化的累加器性能会更好。

```java
```

# Executor与线程池：创建正确的线程池

创建一个线程，却需要调用操作系统内核的API，然后操作系统要为线程分配一系列的资源，这个成本就很高了，所以**线程是一个重量级的对象，应该避免频繁创建和销毁**

```java
class XXXPool{
  // 获取池化资源
  XXX acquire() {
  }
  // 释放池化资源
  void release(XXX x){
  }
}  
```

## 线程池是一种生产者-消费者模式

```java
//采用一般意义上池化资源的设计方法
class ThreadPool{
  // 获取空闲线程
  Thread acquire() {
  }
  // 释放线程
  void release(Thread t){
  }
} 
//期望的使用
ThreadPool pool；
Thread T1=pool.acquire();
//传入Runnable对象
T1.execute(()->{
  //具体业务逻辑
  ......
});
```

线程池的设计，没有办法直接采用一般意义上池化资源的设计方法。普遍采用的都是**生产者-消费者模式**。线程池的使用方是生产者，线程池本身是消费者。

```java
//简化的线程池，仅用来说明工作原理
class MyThreadPool{
  //利用阻塞队列实现生产者-消费者模式
  BlockingQueue<Runnable> workQueue;
  //保存内部工作线程
  List<WorkerThread> threads 
    = new ArrayList<>();
  // 构造方法
  MyThreadPool(int poolSize, 
    BlockingQueue<Runnable> workQueue){
    this.workQueue = workQueue;
    // 创建工作线程
    for(int idx=0; idx<poolSize; idx++){
      WorkerThread work = new WorkerThread();
      work.start();
      threads.add(work);
    }
  }
  // 提交任务
  void execute(Runnable command){
    workQueue.put(command);
  }
  // 工作线程负责消费任务，并执行任务
  class WorkerThread extends Thread{
    public void run() {
      //循环取任务并执行
      while(true){ ①
        Runnable task = workQueue.take();
        task.run();
      } 
    }
  }  
}

/** 下面是使用示例 **/
// 创建有界阻塞队列
BlockingQueue<Runnable> workQueue = 
  new LinkedBlockingQueue<>(2);
// 创建线程池  
MyThreadPool pool = new MyThreadPool(
  10, workQueue);
// 提交任务  
pool.execute(()->{
    System.out.println("hello");
});
```

维护了一个阻塞队列workQueue和一组工作线程，工作线程的个数由构造函数中的poolSize来指定。用户通过调用execute()方法来提交Runnable任务，execute()方法的内部实现仅仅是将任务加入到workQueue中。MyThreadPool内部维护的工作线程会消费workQueue中的任务并执行任务，相关的代码就是代码①处的while循环

## 如何使用线程池

```java
ThreadPoolExecutor(
  int corePoolSize,
  int maximumPoolSize,
  long keepAliveTime,
  TimeUnit unit,
  BlockingQueue<Runnable> workQueue,
  ThreadFactory threadFactory,
  RejectedExecutionHandler handler) 
```

- **corePoolSize**：表示线程池保有的最小线程数。有些项目很闲，但是也不能把人都撤了，至少要留corePoolSize个人坚守阵地。
- **maximumPoolSize**：表示线程池创建的最大线程数。当项目很忙时，就需要加人，但是也不能无限制地加，最多就加到maximumPoolSize个人。当项目闲下来时，就要撤人了，最多能撤到corePoolSize个人。
- **keepAliveTime & unit**：上面提到项目根据忙闲来增减人员，那在编程世界里，如何定义忙和闲呢？很简单，一个线程如果在一段时间内，都没有执行任务，说明很闲，keepAliveTime 和 unit 就是用来定义这个“一段时间”的参数。也就是说，如果一个线程空闲了`keepAliveTime & unit`这么久，而且线程池的线程数大于 corePoolSize ，那么这个空闲的线程就要被回收了。
- **workQueue**：工作队列，和上面示例代码的工作队列同义。
- **threadFactory**：通过这个参数你可以自定义如何创建线程，例如你可以给线程指定一个有意义的名字。
- **handler**：通过这个参数你可以自定义任务的拒绝策略。如果线程池中所有的线程都在忙碌，并且工作队列也满了（前提是工作队列是有界队列），那么此时提交任务，线程池就会拒绝接收。
  - CallerRunsPolicy：提交任务的线程自己去执行该任务。
  - AbortPolicy：默认的拒绝策略，会throws RejectedExecutionException。
  - DiscardPolicy：直接丢弃任务，没有任何异常抛出。
  - DiscardOldestPolicy：丢弃最老的任务，其实就是把最早进入工作队列的任务丢弃，然后把新任务加入到工作队列。

Java在1.6版本还增加了 allowCoreThreadTimeOut(boolean value) 方法，它可以让所有线程都支持超时，这意味着如果项目很闲，就会将项目组的成员都撤走。

## 使用线程池的注意事项

Executors提供的很多方法默认使用的都是无界的LinkedBlockingQueue，高负载情境下，无界队列很容易导致OOM，而OOM会导致所有请求都无法处理，这是致命问题。所以**强烈建议使用有界队列**。



使用有界队列，当任务过多时，线程池会触发执行拒绝策略，线程池默认的拒绝策略会throw RejectedExecutionException 这是个运行时异常，对于运行时异常编译器并不强制catch它，所以开发人员很容易忽略。因此**默认拒绝策略要慎重使用**。如果线程池处理的任务非常重要，建议自定义自己的拒绝策略；并且在实际工作中，自定义的拒绝策略往往和降级策略配合使用。



通过ThreadPoolExecutor对象的execute()方法提交任务时，如果任务在执行的过程中出现运行时异常，会导致执行任务的线程终止；不过，最致命的是任务虽然异常了，但是你却获取不到任何通知，这会让你误以为任务都执行得很正常。虽然线程池提供了很多用于异常处理的方法，但是最稳妥和简单的方案还是捕获所有异常并按需处理

```java
try {
  //业务逻辑
} catch (RuntimeException x) {
  //按需处理
} catch (Throwable x) {
  //按需处理
} 
```

# Future：如何用多线程实现最优的 烧水泡茶程序

## 如何获取任务执行结果？

```java
// 提交Runnable任务
Future<?> 
  submit(Runnable task);
// 提交Callable任务
<T> Future<T> 
  submit(Callable<T> task);
// 提交Runnable任务及结果引用  
<T> Future<T> 
  submit(Runnable task, T result);
```

**取消任务的方法cancel()、判断任务是否已取消的方法isCancelled()、判断任务是否已结束的方法isDone()**以及**2个获得任务执行结果的get()和get(timeout, unit)**

这两个get()方法都是阻塞式的，如果被调用的时候，任务还没有执行完，那么调用get()方法的线程会阻塞，直到任务执行完才会被唤醒。

```java
// 取消任务
boolean cancel(
  boolean mayInterruptIfRunning);
// 判断任务是否已取消  
boolean isCancelled();
// 判断任务是否已结束
boolean isDone();
// 获得任务执行结果
get();
// 获得任务执行结果，支持超时
get(long timeout, TimeUnit unit);
```

1. 提交Runnable任务 `submit(Runnable task)` ：这个方法的参数是一个Runnable接口，Runnable接口的run()方法是没有返回值的，所以 `submit(Runnable task)` 这个方法返回的Future仅可以用来断言任务已经结束了，类似于Thread.join()
2. 提交Callable任务 `submit(Callable<T> task)`：这个方法的参数是一个Callable接口，它只有一个call()方法，并且这个方法是有返回值的，所以这个方法返回的Future对象可以通过调用其get()方法来获取任务的执行结果
3. 提交Runnable任务及结果引用 `submit(Runnable task, T result)`：这个方法很有意思，假设这个方法返回的Future对象是f，f.get()的返回值就是传给submit()方法的参数result需要你注意的是Runnable接口的实现类Task声明了一个有参构造函数 `Task(Result r)` ，创建Task对象的时候传入了result对象，这样就能在类Task的run()方法中对result进行各种操作了。result相当于主线程和子线程之间的桥梁，通过它主子线程可以共享数据。

```java
ExecutorService executor = Executors.newFixedThreadPool(1); 
// 创建Result对象r 
Result r = new Result(); 
r.setAAA(a); 
// 提交任务 Future 
future = executor.submit(new Task®, r);
Result fr = future.get(); 
// 下面等式成立 fr === r; fr.getAAA() === a; fr.getXXX() === x

class Task implements Runnable{ 
    Result r; //通过构造函数传入result 
    
    Task(Result r){
		this.r = r;
	} 
    void run() {
    	//可以操作result
    	a = r.getAAA();
    	r.setXXX(x);
	} 
}

```

Future是一个接口，而FutureTask是一个实实在在的工具类，这个工具类有两个构造函数

```java
FutureTask(Callable<V> callable);
FutureTask(Runnable runnable, V result);
```

FutureTask实现了Runnable和Future接口，由于实现了Runnable接口，所以可以将FutureTask对象作为任务提交给ThreadPoolExecutor去执行，也可以直接被Thread执行；又因为实现了Future接口，所以也能用来获得任务的执行结果

```java
// 创建FutureTask
FutureTask<Integer> futureTask
  = new FutureTask<>(()-> 1+2);
// 创建线程池
ExecutorService es = 
  Executors.newCachedThreadPool();
// 提交FutureTask 
es.submit(futureTask);
// 获取计算结果
Integer result = futureTask.get();
```

利用FutureTask对象可以很容易获取子线程的执行结果

```java
// 创建FutureTask
FutureTask<Integer> futureTask
  = new FutureTask<>(()-> 1+2);
// 创建并启动线程
Thread T1 = new Thread(futureTask);
T1.start();
// 获取计算结果
Integer result = futureTask.get();
```

## 实现最优的 烧水泡茶程序

![image-20250417182822922](image\image-20250417182822922.png)

发编程可以总结为三个核心问题：分工、同步和互斥。编写并发程序，首先要做的就是分工，所谓分工指的是如何高效地拆解任务并分配给线程。对于烧水泡茶这个程序，一种最优的分工方案可以是下图所示的这样：用两个线程T1和T2来完成烧水泡茶程序，T1负责洗水壶、烧开水、泡茶这三道工序，T2负责洗茶壶、洗茶杯、拿茶叶三道工序，其中T1在执行泡茶这道工序时需要等待T2完成拿茶叶的工序



对于T1的这个等待动作，你应该可以想出很多种办法，例如Thread.join()、CountDownLatch，甚至阻塞队列都可以解决，不过今天我们用Future特性来实现



创建了两个FutureTask——ft1和ft2，ft1完成洗水壶、烧开水、泡茶的任务，ft2完成洗茶壶、洗茶杯、拿茶叶的任务；这里需要注意的是ft1这个任务在执行泡茶任务前，需要等待ft2把茶叶拿来，所以ft1内部需要引用ft2，并在执行泡茶之前，调用ft2的get()方法实现等待。

```java
// 创建任务T2的FutureTask
FutureTask<String> ft2
  = new FutureTask<>(new T2Task());
// 创建任务T1的FutureTask
FutureTask<String> ft1
  = new FutureTask<>(new T1Task(ft2));

// 线程T1执行任务ft1
Thread T1 = new Thread(ft1);
T1.start();
// 线程T2执行任务ft2
Thread T2 = new Thread(ft2);
T2.start();
// 等待线程T1执行结果
System.out.println(ft1.get());

// T1Task需要执行的任务：
// 洗水壶、烧开水、泡茶
class T1Task implements Callable<String>{
  FutureTask<String> ft2;
  // T1任务需要T2任务的FutureTask
  T1Task(FutureTask<String> ft2){
    this.ft2 = ft2;
  }
  @Override
  String call() throws Exception {
    System.out.println("T1:洗水壶...");
    TimeUnit.SECONDS.sleep(1);

    System.out.println("T1:烧开水...");
    TimeUnit.SECONDS.sleep(15);
    // 获取T2线程的茶叶  
    String tf = ft2.get();
    System.out.println("T1:拿到茶叶:"+tf);

    System.out.println("T1:泡茶...");
    return "上茶:" + tf;
  }
}
// T2Task需要执行的任务:
// 洗茶壶、洗茶杯、拿茶叶
class T2Task implements Callable<String> {
  @Override
  String call() throws Exception {
    System.out.println("T2:洗茶壶...");
    TimeUnit.SECONDS.sleep(1);

    System.out.println("T2:洗茶杯...");
    TimeUnit.SECONDS.sleep(2);

    System.out.println("T2:拿茶叶...");
    TimeUnit.SECONDS.sleep(1);
    return "龙井";
  }
}
// 一次执行结果：
T1:洗水壶...
T2:洗茶壶...
T1:烧开水...
T2:洗茶杯...
T2:拿茶叶...
T1:拿到茶叶:龙井
T1:泡茶...
上茶:龙井
```

利用多线程可以快速将一些串行的任务并行化，从而提高性能；如果任务之间有依赖关系，比如当前任务依赖前一个任务的执行结果，这种问题基本上都可以用Future来解决。在分析这种问题的过程中，建议你用有向图描述一下任务之间的依赖关系，同时将线程的分工也做好

# CompletableFuture：异步编程

耗时长的操作？创建子线程异步化执行。

**异步化**，是并行方案得以实施的基础，更深入地讲其实就是：**利用多线程优化性能这个核心方案得以实施的基础**

## CompletableFuture的核心优势

我们分了3个任务：任务1负责洗水壶、烧开水，任务2负责洗茶壶、洗茶杯和拿茶叶，任务3负责泡茶。其中任务3要等待任务1和任务2都完成后才能开始。

1. 无需手工维护线程，没有繁琐的手工维护线程的工作，给任务分配线程的工作也不需要我们关注；
2. 语义更清晰，例如 `f3 = f1.thenCombine(f2, ()->{})` 能够清晰地表述“任务3要等待任务1和任务2都完成后才能开始”；
3. 代码更简练并且专注于业务逻辑，几乎所有代码都是业务逻辑相关的。

```java
//任务1：洗水壶->烧开水 
CompletableFuture f1 = CompletableFuture.runAsync(()->{ 			      	  System.out.println(“T1:洗水壶…”); sleep(1, TimeUnit.SECONDS);
	System.out.println(“T1:烧开水…”); sleep(15, TimeUnit.SECONDS); 
}); 
//任务2：洗茶壶->洗茶杯->拿茶叶 
CompletableFuture f2 = CompletableFuture.supplyAsync(()->{ 			  		System.out.println(“T2:洗茶壶…”); sleep(1, TimeUnit.SECONDS);
	System.out.println(“T2:洗茶杯…”); sleep(2, TimeUnit.SECONDS);
	System.out.println(“T2:拿茶叶…”); sleep(1, TimeUnit.SECONDS); 
    return “龙井”; }); 

//任务3：任务1和任务2完成后执行：泡茶 
CompletableFuture f3 = f1.thenCombine(f2, (__, tf)->{
    System.out.println("T1:拿到茶叶:" + tf);
    System.out.println("T1:泡茶...");
    return "上茶:" + tf;
}); 
//等待任务3执行结果 
System.out.println(f3.join());

void sleep(int t, TimeUnit u) { try {
	u.sleep(t);
}catch(InterruptedException e){} }
```

## 创建CompletableFuture对象

`runAsync(Runnable runnable)`和`supplyAsync(Supplier<U> supplier)`，它们之间的区别是：Runnable 接口的run()方法没有返回值，而Supplier接口的get()方法是有返回值的。



默认情况下CompletableFuture会使用公共的ForkJoinPool线程池，这个线程池默认创建的线程数是CPU的核数（也可以通过JVM option:-Djava.util.concurrent.ForkJoinPool.common.parallelism来设置ForkJoinPool线程池的线程数）。如果所有CompletableFuture共享一个线程池，那么一旦有任务执行一些很慢的I/O操作，就会导致线程池中所有线程都阻塞在I/O操作上，从而造成线程饥饿，进而影响整个系统的性能。所以，强烈建议你要**根据不同的业务类型创建不同的线程池，以避免互相干扰**。

```java
//使用默认线程池
static CompletableFuture<Void> runAsync(Runnable runnable)
static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier)
//可以指定线程池  
static CompletableFuture<Void> 
  runAsync(Runnable runnable, Executor executor)
    
static <U> CompletableFuture<U> 
  supplyAsync(Supplier<U> supplier, Executor executor)  
```

创建完CompletableFuture对象之后，会自动地异步执行runnable.run()方法或者supplier.get()方法。

一个是异步操作什么时候结束，另一个是如何获取异步操作的执行结果。因为CompletableFuture类实现了Future接口，所以这两个问题你都可以通过Future接口来解决

# CompletionStage接口



**串行关系、并行关系、汇聚关系**。洗水壶和烧开水就是串行关系，洗水壶、烧开水和洗茶壶、洗茶杯这两组任务之间就是并行关系，而烧开水、拿茶叶和泡茶就是汇聚关系。

![image-20250417185230968](image\image-20250417185230968.png)

CompletionStage接口可以清晰地描述任务之间的这种时序关系，例如前面提到的 `f3 = f1.thenCombine(f2, ()->{})` 描述的就是一种汇聚关系。烧水泡茶程序中的汇聚关系是一种 AND 聚合关系，这里的AND指的是所有依赖的任务（烧开水和拿茶叶）都完成后才开始执行当前任务。

一定还有OR聚合关系，所谓OR指的是依赖的任务只要有一个完成就可以执行当前任务。

在编程领域，还有一个绕不过去的山头，那就是异常处理，CompletionStage接口也可以方便地描述异常处理。



### 描述串行关系

thenApply、thenAccept、thenRun和thenCompose这四个系列的接口。

- thenApply系列函数里参数fn的类型是接口Function，这个接口里与CompletionStage相关的方法是 `R apply(T t)`，这个方法既能接收参数也支持返回值，所以thenApply系列方法返回的是`CompletionStage<R>`
- thenAccept系列方法里参数consumer的类型是接口`Consumer<T>`，这个接口里与CompletionStage相关的方法是 `void accept(T t)`，这个方法虽然支持参数，但却不支持回值，所以thenAccept系列方法返回的是`CompletionStage<Void>`
- thenRun系列方法里action的参数是Runnable，所以action既不能接收参数也不支持返回值，所以thenRun系列方法返回的也是`CompletionStage<Void>`



Async代表的是异步执行。thenCompose系列方法，这个系列的方法会新创建出一个子流程，最终结果和thenApply系列是相同的。

```java
CompletionStage<R> thenApply(fn);
CompletionStage<R> thenApplyAsync(fn);
CompletionStage<Void> thenAccept(consumer);
CompletionStage<Void> thenAcceptAsync(consumer);
CompletionStage<Void> thenRun(action);
CompletionStage<Void> thenRunAsync(action);
CompletionStage<R> thenCompose(fn);
CompletionStage<R> thenComposeAsync(fn);
```

```java
CompletableFuture<String> f0 = 
  CompletableFuture.supplyAsync(
    () -> "Hello World")      //①
  .thenApply(s -> s + " QQ")  //②
  .thenApply(String::toUpperCase);//③

System.out.println(f0.join());
//输出结果
HELLO WORLD QQ
```

### 描述AND汇聚关系

thenCombine、thenAcceptBoth和runAfterBoth系列的接口

```java
CompletionStage<R> thenCombine(other, fn);
CompletionStage<R> thenCombineAsync(other, fn);
CompletionStage<Void> thenAcceptBoth(other, consumer);
CompletionStage<Void> thenAcceptBothAsync(other, consumer);
CompletionStage<Void> runAfterBoth(other, action);
CompletionStage<Void> runAfterBothAsync(other, action);
```

### 描述OR汇聚关系

```java
CompletionStage applyToEither(other, fn);
CompletionStage applyToEitherAsync(other, fn);
CompletionStage acceptEither(other, consumer);
CompletionStage acceptEitherAsync(other, consumer);
CompletionStage runAfterEither(other, action);
CompletionStage runAfterEitherAsync(other, action);
```

```java
CompletableFuture<String> f1 = 
  CompletableFuture.supplyAsync(()->{
    int t = getRandom(5, 10);
    sleep(t, TimeUnit.SECONDS);
    return String.valueOf(t);
});

CompletableFuture<String> f2 = 
  CompletableFuture.supplyAsync(()->{
    int t = getRandom(5, 10);
    sleep(t, TimeUnit.SECONDS);
    return String.valueOf(t);
});

CompletableFuture<String> f3 = 
  f1.applyToEither(f2,s -> s);

System.out.println(f3.join());
```

### 异常处理

```java
CompletionStage exceptionally(fn);
CompletionStage<R> whenComplete(consumer);
CompletionStage<R> whenCompleteAsync(consumer);
CompletionStage<R> handle(fn);
CompletionStage<R> handleAsync(fn);
```

支持链式。

whenComplete()和handle()系列方法就类似于try{}finally{}中的finally{}。whenComplete()不支持返回结果，而handle()是支持返回结果的。

```java
CompletableFuture<Integer> 
  f0 = CompletableFuture
    .supplyAsync(()->(7/0))
    .thenApply(r->r*10)
    .exceptionally(e->0);
System.out.println(f0.join());
```

# ForkJoin：单机版本的MapReduce

简单的并行任务：线程池 + Future

任务之间有聚合关系：AND OR-> CompletableFuture

批量的并行任务：CompletionService



![image-20250420150937284](image\image-20250420150937284.png)

> 简单并行任务 聚合任务 批量并行任务

分治：归并排序 快速排序 二分查找 MapReduce

## 分治任务模型

任务分解： 任务迭代地分解成子任务，直到获取最终结果

结果合并：逐层合并子任务的执行结果。

相似性：任务和子任务的算法相同，但是计算的数据规模不同

## ForkJoin的使用

- 分治任务的线程池ForkJoinPool
- 分治任务：ForkJoinTask【fork() join()】:子类有RecursiveAction RecursiveTask，前者没有返回值，后者有。

```java
 public static void main(String[] args) {
        ForkJoinPool forkJoinPool = new ForkJoinPool(4);
        Fibonacci fibonacci = new Fibonacci(30);
        Integer result = forkJoinPool.invoke(fibonacci);
        System.out.println(result);

    }

    private static class Fibonacci extends RecursiveTask<Integer> {
        final int n;

        Fibonacci(int n) {
            this.n = n;
        }
        @Override
        protected Integer compute() {
            if (n <= 1) {
                return n;
            }
            Fibonacci f1 = new Fibonacci(n - 1);
            f1.fork();// 使用了异步子任务。
            Fibonacci f2 = new Fibonacci(n - 2);
            f2.fork();
            return f1.join() + f2.join();
        }
    }
```

## ForkJoinPool工作原理

ThreadPoolExecutor内部只有一个任务队列，而ForkJoinPool内部有多个任务队列，当我们通过ForkJoinPool的invoke()或者submit()方法提交任务时，ForkJoinPool根据一定的路由规则把任务提交到一个任务队列中，如果任务在执行过程中会创建出子任务，那么子任务会提交到工作线程对应的任务队列中。

“任务窃取”机制：工作线程空闲了就可以窃取其他工作任务队列里面的任务。队列是双端队列，工作线程正常获取任务 和 窃取任务 分别是从任务队列不用端消费。

![image-20250420152029587](image\image-20250420152029587.png)

## 模拟MapReduce统计单词数量

统计一个文件里面每个单词的数量：可以先用二分法递归地将一个文件拆分成更小的文件，直到文件里只有一行数据，然后统计这一行数据里单词的数量，最后再逐级汇总结果。

用一个字符串数组 `String[] fc` 来模拟文件内容，fc里面的元素与文件里面的行数据一一对应。关键的代码在 `compute()` 这个方法里面，这是一个递归方法，前半部分数据fork一个递归任务去处理（关键代码mr1.fork()），后半部分数据则在当前任务中递归处理（mr2.compute()）

```java
class ForkJoinTest2 {
    public static void main(String[] args) {
        String[] fc = {"hello world",
                "hello me",
                "hello fork",
                "hello join",
                "fork join in world"};
        ForkJoinPool forkJoinPool = new ForkJoinPool(3);
        MR mr = new MR(fc, 0, fc.length);
        Map<String, Long> result = forkJoinPool.invoke(mr);
        System.out.println(result);
    }

    static class MR extends RecursiveTask<Map<String,Long>> {
        private String[] fc;
        private int start,end;

        MR(String[] fc, int start, int end) {
            this.fc = fc;
            this.start = start;
            this.end = end;
        }
        @Override
        protected Map<String, Long> compute() {
            if (end - start == 1) {
                return calc(fc[start]);
            }
            int mid = (start + end) / 2;
            MR mr1 = new MR(fc, start, mid);
            mr1.fork();
            MR mr2 = new MR(fc,mid,end);
            mr2.fork();
            return merge(mr1.join(), mr2.join());
        }

        private Map<String, Long> merge(Map<String, Long> m1, Map<String, Long> m2) {
            Map<String,Long> result = new HashMap<>();
            result.putAll(m1);
            m2.forEach((k,v) -> {
                if (result.containsKey(k)) {
                    result.put(k,result.get(k) + v);
                } else {
                    result.put(k, v);
                }
            });
            return result;
        }

        private Map<String, Long> calc(String line) {
            Map<String, Long> result = new HashMap<>();
            String[] words = line.split("\\s+");
            for (String word : words) {
                result.put(word, result.getOrDefault(word,0l) + 1);
            }
            return result;
        }
    }
}
```

## 总结扩展

Java Stream并行流也是以ForkJoinPool为基础。不过要注意，默认并行流共享一个ForkJoinPool，默认是CPU核数。如果所有并行流都是CPU密集任务，没有问题，但是如果有IO密集型的并行流计算，很可能因为一个很慢的IO计算拖慢整个系统的性能。

建议用不同的ForkJoinPool执行不同类型的计算任务。



# 并发工具类模块答疑

1. 转账的功能代码：

   ```java
   import java.util.concurrent.locks.Lock;
   import java.util.concurrent.locks.ReentrantLock;
   
   class Account {
       private int balance;
       private final Lock lock = new ReentrantLock();
   
       // 转账
       void transfer(Account tar, int amt) throws InterruptedException {
           while (true) {
               if (this.lock.tryLock()) {
                   try {
                       System.out.println("当前对象的锁已获取");
                       if (tar.lock.tryLock()) {
                           try {
                               System.out.println("目标对象的锁已获取");
                               this.balance -= amt;
                               tar.balance += amt;
                               System.out.println("转账完成，准备跳出循环");
                               // 新增：退出循环
                               break;
                           } finally {
                               System.out.println("释放目标对象的锁");
                               tar.lock.unlock();
                           }
                       }
                   } finally {
                       System.out.println("释放当前对象的锁");
                       this.lock.unlock();
                   }
               }
               // 新增：sleep一个随机时间避免活锁. 避免线程A 账户A到B转账，现成B 账户B到账户A转账，一直冲突获取不到锁。
               Thread.sleep((long) (Math.random() * 100));
           }
       }
   }
   ```

   

2. WM

```java
public class SafeWM {
  class WMRange{
    final int upper;
    final int lower;
    WMRange(int upper,int lower){
    //省略构造函数实现
    }
  }
  final AtomicReference<WMRange>
    rf = new AtomicReference<>(
      new WMRange(0,0)
    );
  // 设置库存上限
  void setUpper(int v){
    WMRange nr;
    WMRange or;
    //原代码在这里
    //WMRange or=rf.get();
    do{
      //移动到此处
      //每个回合都需要重新获取旧值
      or = rf.get();
      // 检查参数合法性
      if(v < or.lower){
        throw new IllegalArgumentException();
      }
      nr = new
        WMRange(v, or.lower);
    }while(!rf.compareAndSet(or, nr));
  }
}
```

当遇到回调函数的时候，你应该本能地问自己：执行回调函数的线程是哪一个？这个在多线程场景下非常重要。因为不同线程ThreadLocal里的数据是不同的，有些框架比如Spring就用ThreadLocal来管理事务，如果不清楚回调函数用的是哪个线程，很可能会导致错误的事务管理，并最终导致数据不一致。

CyclicBarrier的回调函数究竟是哪个线程执行的呢？如果你分析源码，你会发现执行回调函数的线程是将CyclicBarrier内部计数器减到 0 的那个线程。



# Immutability模式：利用不变性解决并发问题

对象一旦被创建之后，状态就不在发生变化。没有修改操作。

## 快速实现具备不可变性的类

类：final； 属性都是final的，并且只允许存在只读方法。比如String Integer Long Double

关于String的replace方法

```java
public final class String {
  private final char value[];
  // 字符替换
  String replace(char oldChar, 
      char newChar) {
    //无需替换，直接返回this  
    if (oldChar == newChar){
      return this;
    }

    int len = value.length;
    int i = -1;
    /* avoid getfield opcode */
    char[] val = value; 
    //定位到需要替换的字符位置
    while (++i < len) {
      if (val[i] == oldChar) {
        break;
      }
    }
    //未找到oldChar，无需替换
    if (i >= len) {
      return this;
    } 
    //创建一个buf[]，这是关键
    //用来保存替换后的字符串
    char buf[] = new char[len];
    for (int j = 0; j < i; j++) {
      buf[j] = val[j];
    }
    while (i < len) {
      char c = val[i];
      buf[i] = (c == oldChar) ? 
        newChar : c;
      i++;
    }
    //创建一个新的字符串返回
    //原字符串不会发生任何变化
    return new String(buf, true);
  }
}
```

不可变类如果要提供修改方法：创建一个新的不可变对象。



## 利用享元模式避免创建重复对象

享元模式本质上其实就是一个**对象池**，利用享元模式创建对象的逻辑也很简单：创建之前，首先去对象池里看看是不是存在；如果已经存在，就利用对象池里的对象；如果不存在，就会新创建一个对象，并且把这个新创建出来的对象放进对象池里。



比如包装类，Integer Long

```java
Long valueOf(long l) {
  final int offset = 128;
  // [-128,127]直接的数字做了缓存
  if (l >= -128 && l <= 127) { 
    return LongCache
      .cache[(int)l + offset];
  }
  return new Long(l);
}
//缓存，等价于对象池
//仅缓存[-128,127]直接的数字
static class LongCache {
  static final Long cache[] 
    = new Long[-(-128) + 127 + 1];

  static {
    for(int i=0; i<cache.length; i++)
      cache[i] = new Long(i-128);
  }
}
```

“Integer 和 String 类型的对象不适合做锁”，其实基本上所有的基础类型的包装类都不适合做锁，因为它们内部用到了享元模式，这会导致看上去私有的锁，其实是共有的。

```java
class A {
  Long al=Long.valueOf(1);
  public void setAX(){
    synchronized (al) {
      //省略代码无数
    }
  }
}
class B {
  Long bl=Long.valueOf(1);
  public void setBY(){
    synchronized (bl) {
      //省略代码无数
    }
  }
}
```

## 使用不可变模式的注意事项

1. 对象的所有属性都是final的，并不能保证不可变性；
2. 不可变对象也需要正确发布。

**一定要确认保持不变性的边界在哪里，是否要求属性对象也具备不可变性**。

```
class Foo{
  int age=0;
  int name="abc";
}
final class Bar {
  final Foo foo;
  void setAge(int a){
    foo.age=a;
  }
}
```

不可变对象虽然是线程安全的，但是并不意味着引用这些不可变对象的对象就是线程安全的。例如在下面的代码中，Foo具备不可变性，线程安全，但是类Bar并不是线程安全的，类Bar中持有对Foo的引用foo，对foo这个引用的修改在多线程中并不能保证可见性和原子性。

```java
//Foo线程安全
final class Foo{
  final int age=0;
  final int name="abc";
}
//Bar线程不安全
class Bar {
  Foo foo;
  void setFoo(Foo f){
    this.foo=f;
  }
}
```

如果你的程序仅仅需要foo保持可见性，无需保证原子性，那么可以将foo声明为volatile变量，这样就能保证可见性。如果你的程序需要保证原子性，那么可以通过原子类来实现。

```java
public class SafeWM {
  class WMRange{
    final int upper;
    final int lower;
    WMRange(int upper,int lower){
    //省略构造函数实现
    }
  }
  final AtomicReference<WMRange>
    rf = new AtomicReference<>(
      new WMRange(0,0)
    );
  // 设置库存上限
  void setUpper(int v){
    while(true){
      WMRange or = rf.get();
      // 检查参数合法性
      if(v < or.lower){
        throw new IllegalArgumentException();
      }
      WMRange nr = new
          WMRange(v, or.lower);
      if(rf.compareAndSet(or, nr)){
        return;
      }
    }
  }
}
```

不可变性的另外一种形式是：无状态对象内部没有属性，只有方法。除了无状态的对象，你可能还听说过无状态的服务、无状态的协议等等。无状态有很多好处，最核心的一点就是性能。在多线程领域，无状态对象没有线程安全问题，无需同步处理，自然性能很好；在分布式领域，无状态意味着可以无限地水平扩展，所以分布式领域里面性能的瓶颈一定不是出在无状态的服务节点上。

# 写时复制技术

## 应用领域

CopyOnWriteArrayList和CopyOnWriteArraySet。读操作是无锁的，由于无锁，所以将读操作的性能发挥到了极致。

Linux中的fork()函数就聪明得多了，fork()子进程的时候，并不复制整个进程的地址空间，而是让父子进程共享同一个地址空间；只用在父进程或者子进程需要写入的时候才会复制地址空间，从而使父子进程拥有各自的地址空间。

本质上来讲，父子进程的地址空间以及数据都是要隔离的，使用Copy-on-Write更多地体现的是一种**延时策略，只有在真正需要复制的时候才复制，而不是提前复制好**，同时Copy-on-Write还支持按需复制，所以Copy-on-Write在操作系统领域是能够提升性能的



在操作系统领域，除了创建进程用到了Copy-on-Write，很多文件系统也同样用到了，例如Btrfs (B-Tree File System)、aufs（advanced multi-layered unification filesystem）等。

除了上面我们说的Java领域、操作系统领域，很多其他领域也都能看到Copy-on-Write的身影：Docker容器镜像的设计是Copy-on-Write，甚至分布式源码管理系统Git背后的设计思想都有Copy-on-Write……



**Copy-on-Write最大的应用领域还是在函数式编程领域**。函数式编程的基础是不可变性（Immutability），所以函数式编程里面所有的修改操作都需要Copy-on-Write来解决。



CopyOnWriteArrayList和CopyOnWriteArraySet这两个Copy-on-Write容器在修改的时候会复制整个数组，所以如果容器经常被修改或者这个数组本身就非常大的时候，是不建议使用的。反之，如果是修改非常少、数组数量也不大，并且对读性能要求苛刻的场景，使用Copy-on-Write容器效果就非常好了

## 一个真实案例

服务提供方是多实例分布式部署的，所以服务的客户端在调用RPC的时候，会选定一个服务实例来调用，这个选定的过程本质上就是在做负载均衡，而做负载均衡的前提是客户端要有全部的路由信息。例如在下图中，A服务的提供方有3个实例，分别是192.168.1.1、192.168.1.2和192.168.1.3，客户端在调用目标服务A前，首先需要做的是负载均衡，也就是从这3个实例中选出1个来，然后再通过RPC把请求发送选中的目标实例。

![image-20250421214808273](image\image-20250421214808273.png)

RPC框架的一个核心任务就是维护服务的路由关系，我们可以把服务的路由关系简化成下图所示的路由表。当服务提供方上线或者下线的时候，就需要更新客户端的这张路由表。

![image-20250421214836481](image\image-20250421214836481.png)

每次RPC调用都需要通过负载均衡器来计算目标服务的IP和端口号，而负载均衡器需要通过路由表获取接口的所有路由信息，也就是说，每次RPC调用都需要访问路由表，所以访问路由表这个操作的性能要求是很高的。不过路由表对数据的一致性要求并不高，一个服务提供方从上线到反馈到客户端的路由表里，即便有5秒钟，很多时候也都是能接受的（5秒钟，对于以纳秒作为时钟周期的CPU来说，那何止是一万年，所以路由表对一致性的要求并不高）。而且路由表是典型的读多写少类问题



对读的性能要求很高，读多写少，弱一致性。它们综合在一起，你会想到什么呢？CopyOnWriteArrayList和CopyOnWriteArraySet天生就适用这种场景啊。

服务提供方的每一次上线、下线都会更新路由信息，这时候你有两种选择。一种是通过更新Router的一个状态位来标识，如果这样做，那么所有访问该状态位的地方都需要同步访问，这样很影响性能。另外一种就是采用Immutability模式，每次上线、下线都创建新的Router对象或者删除对应的Router对象。由于上线、下线的频率很低，所以后者是最好的选择。

```java
//路由信息
public final class Router{
  private final String  ip;
  private final Integer port;
  private final String  iface;
  //构造函数
  public Router(String ip, 
      Integer port, String iface){
    this.ip = ip;
    this.port = port;
    this.iface = iface;
  }
  //重写equals方法
  public boolean equals(Object obj){
    if (obj instanceof Router) {
      Router r = (Router)obj;
      return iface.equals(r.iface) &&
             ip.equals(r.ip) &&
             port.equals(r.port);
    }
    return false;
  }
  public int hashCode() {
    //省略hashCode相关代码
  }
}
//路由表信息
public class RouterTable {
  //Key:接口名
  //Value:路由集合
  ConcurrentHashMap<String, CopyOnWriteArraySet<Router>> 
    rt = new ConcurrentHashMap<>();
  //根据接口名获取路由表
  public Set<Router> get(String iface){
    return rt.get(iface);
  }
  //删除路由
  public void remove(Router router) {
    Set<Router> set=rt.get(router.iface);
    if (set != null) {
      set.remove(router);
    }
  }
  //增加路由
  public void add(Router router) {
    Set<Router> set = rt.computeIfAbsent(
      route.iface, r -> 
        new CopyOnWriteArraySet<>());
    set.add(router);
  }
}
```

# 线程本地存储模式

## ThreadLocal的使用方法：

```java
static class ThreadId {
  static final AtomicLong 
  nextId=new AtomicLong(0);
  //定义ThreadLocal变量
  static final ThreadLocal<Long> 
  tl=ThreadLocal.withInitial(
    ()->nextId.getAndIncrement());
  //此方法会为每个线程分配一个唯一的Id
  static long get(){
    return tl.get();
  }
}
```

不同线程调用SafeDateFormat的get()方法将返回不同的SimpleDateFormat对象实例，由于不同线程并不共享SimpleDateFormat，所以就像局部变量一样，是线程安全的。

```java
static class SafeDateFormat {
  //定义ThreadLocal变量
  static final ThreadLocal<DateFormat>
  tl=ThreadLocal.withInitial(
    ()-> new SimpleDateFormat(
      "yyyy-MM-dd HH:mm:ss"));

  static DateFormat get(){
    return tl.get();
  }
}
//不同线程执行下面代码
//返回的df是不同的
DateFormat df =
  SafeDateFormat.get()；
```

## ThreadLocal工作原理

数据是存储在Thread线程对象中，threadLocals： ThreadLocalMap类型

![image-20250422205209537](image\image-20250422205209537.png)

```java
class Thread {
  //内部持有ThreadLocalMap
  ThreadLocal.ThreadLocalMap 
    threadLocals;
}
class ThreadLocal<T>{
  public T get() {
    //首先获取线程持有的
    //ThreadLocalMap
    ThreadLocalMap map =
      Thread.currentThread()
        .threadLocals;
    //在ThreadLocalMap中
    //查找变量
    Entry e = 
      map.getEntry(this);
    return e.value;  
  }
  static class ThreadLocalMap{
    //内部是数组而不是Map
    Entry[] table;
    //根据ThreadLocal查找Entry
    Entry getEntry(ThreadLocal key){
      //省略查找逻辑
    }
    //Entry定义
    static class Entry extends
    WeakReference<ThreadLocal>{
      Object value;
    }
  }
}
```

在Java的实现方案里面，ThreadLocal仅仅是一个代理工具类，内部并不持有任何与线程相关的数据，所有和线程相关的数据都存储在Thread里面，这样的设计容易理解。而从数据的亲缘性上来讲，ThreadLocalMap属于Thread也更加合理.

Java的实现中Thread持有ThreadLocalMap，而且ThreadLocalMap里对ThreadLocal的引用还是弱引用（WeakReference），所以只要Thread对象可以被回收，那么ThreadLocalMap就能被回收

## ThreadLocal与内存泄漏

在线程池中使用ThreadLocal为什么可能导致内存泄露呢？原因就出在线程池中线程的存活时间太长，往往都是和程序同生共死的，这就意味着Thread持有的ThreadLocalMap一直都不会被回收，再加上ThreadLocalMap中的Entry对ThreadLocal是弱引用（WeakReference），所以只要ThreadLocal结束了自己的生命周期是可以被回收掉的。但是Entry中的Value却是被Entry强引用的，所以即便Value的生命周期结束了，Value也是无法被回收的，从而导致内存泄露。



解决方案：手动释放

```java
ExecutorService es;
ThreadLocal tl;
es.execute(()->{
  //ThreadLocal增加变量
  tl.set(obj);
  try {
    // 省略业务逻辑代码
  }finally {
    //手动清理ThreadLocal 
    tl.remove();
  }
});
```

## InheritableThreadLocal

需要子线程继承父线程的线程变量，Java提供了InheritableThreadLocal来支持这种特性

可能导致内存泄露，更重要的原因是：线程池中线程的创建是动态的，很容易导致继承关系错乱，如果你的业务逻辑依赖InheritableThreadLocal，那么很可能导致业务逻辑计算错误，而这个错误往往比内存泄露更要命。

## 总结

一种方案是将这个工具类作为局部变量使用，另外一种方案就是线程本地存储模式。这两种方案，局部变量方案的缺点是在高并发场景下会频繁创建对象，而线程本地存储方案，每个线程只需要创建一个工具类的实例，所以不存在频繁创建对象的问题。

# Guarded Suspension模式：等待唤醒

![image-20250422205739156](image\image-20250422205739156.png)

用户通过浏览器发过来一个请求，会被转换成一个异步消息发送给MQ，等MQ返回结果后，再将这个结果返回至浏览器。小灰同学的问题是：给MQ发送消息的线程是处理Web请求的线程T1，但消费MQ结果的线程并不是线程T1，那线程T1如何等待MQ的返回结果呢

```java
class Message{
  String id;
  String content;
}
//该方法可以发送消息
void send(Message msg){
  //省略相关代码
}
//MQ消息返回后会调用该方法
//该方法的执行线程不同于
//发送消息的线程
void onMessage(Message msg){
  //省略相关代码
}
//处理浏览器发来的请求
Respond handleWebReq(){
  //创建一消息
  Message msg1 = new 
    Message("1","{...}");
  //发送消息
  send(msg1);
  //如何等待MQ返回的消息呢？
  String result = ...;
}
```

## Guarded Suspension模式

Guarded Suspension模式的结构图，非常简单，一个对象GuardedObject，内部有一个成员变量——受保护的对象，以及两个成员方法——`get(Predicate<T> p)`和`onChanged(T obj)`方法。

![image-20250422205900781](image\image-20250422205900781.png)

```java
class GuardedObject<T>{
  //受保护的对象
  T obj;
  final Lock lock = 
    new ReentrantLock();
  final Condition done =
    lock.newCondition();
  final int timeout=1;
  //获取受保护对象  
  T get(Predicate<T> p) {
    lock.lock();
    try {
      //MESA管程推荐写法
      while(!p.test(obj)){
        done.await(timeout, 
          TimeUnit.SECONDS);
      }
    }catch(InterruptedException e){
      throw new RuntimeException(e);
    }finally{
      lock.unlock();
    }
    //返回非空的受保护对象
    return obj;
  }
  //事件通知方法
  void onChanged(T obj) {
    lock.lock();
    try {
      this.obj = obj;
      done.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
```

## 扩展Guarded Suspension模式

Guarded Suspension模式里GuardedObject有两个核心方法，一个是get()方法，一个是onChanged()方法。很显然，在处理Web请求的方法handleWebReq()中，可以调用GuardedObject的get()方法来实现等待；在MQ消息的消费方法onMessage()中，可以调用GuardedObject的onChanged()方法来实现唤醒。

```java
//处理浏览器发来的请求
Respond handleWebReq(){
  //创建一消息
  Message msg1 = new 
    Message("1","{...}");
  //发送消息
  send(msg1);
  //利用GuardedObject实现等待
  GuardedObject<Message> go
    =new GuardObjec<>();
  Message r = go.get(
    t->t != null);
}
void onMessage(Message msg){
  //如何找到匹配的go？
  GuardedObject<Message> go=???
  go.onChanged(msg);
}
```

扩展Guarded Suspension模式的实现，扩展后的GuardedObject内部维护了一个Map，其Key是MQ消息id，而Value是GuardedObject对象实例，同时增加了静态方法create()和fireEvent()；create()方法用来创建一个GuardedObject对象实例，并根据key值将其加入到Map中，而fireEvent()方法则是模拟的大堂经理根据包间找就餐人的逻辑。

```java
class GuardedObject<T>{
  //受保护的对象
  T obj;
  final Lock lock = 
    new ReentrantLock();
  final Condition done =
    lock.newCondition();
  final int timeout=2;
  //保存所有GuardedObject
  final static Map<Object, GuardedObject> 
  gos=new ConcurrentHashMap<>();
  //静态方法创建GuardedObject
  static <K> GuardedObject 
      create(K key){
    GuardedObject go=new GuardedObject();
    gos.put(key, go);
    return go;
  }
  static <K, T> void 
      fireEvent(K key, T obj){
    GuardedObject go=gos.remove(key);
    if (go != null){
      go.onChanged(obj);
    }
  }
  //获取受保护对象  
  T get(Predicate<T> p) {
    lock.lock();
    try {
      //MESA管程推荐写法
      while(!p.test(obj)){
        done.await(timeout, 
          TimeUnit.SECONDS);
      }
    }catch(InterruptedException e){
      throw new RuntimeException(e);
    }finally{
      lock.unlock();
    }
    //返回非空的受保护对象
    return obj;
  }
  //事件通知方法
  void onChanged(T obj) {
    lock.lock();
    try {
      this.obj = obj;
      done.signalAll();
    } finally {
      lock.unlock();
    }
  }
}
```



```java
//处理浏览器发来的请求
Respond handleWebReq(){
  int id=序号生成器.get();
  //创建一消息
  Message msg1 = new 
    Message(id,"{...}");
  //创建GuardedObject实例
  GuardedObject<Message> go=
    GuardedObject.create(id);  
  //发送消息
  send(msg1);
  //等待MQ消息
  Message r = go.get(
    t->t != null);  
}
void onMessage(Message msg){
  //唤醒等待的线程
  GuardedObject.fireEvent(
    msg.id, msg);
}
```

Guarded Suspension模式也常被称作Guarded Wait模式、Spin Lock模式（因为使用了while循环去等待），这些名字都很形象，不过它还有一个更形象的非官方名字：多线程版本的if。单线程场景中，if语句是不需要等待的，因为在只有一个线程的条件下，如果这个线程被阻塞，那就没有其他活动线程了，这意味着if判断条件的结果也不会发生变化了。但是多线程场景中，等待就变得有意义了，这种场景下，if判断条件的结果是可能发生变化的。

# Balking模式：再谈线程安全的单例模式

一个例子：编辑器提供的自动保存功能，如果文件做出了修改，就自动定时保存。

```java
class AutoSaveEditor{
  //文件是否被修改过
  boolean changed=false;
  //定时任务线程池
  ScheduledExecutorService ses = 
    Executors.newSingleThreadScheduledExecutor();
  //定时执行自动保存
  void startAutoSave(){
    ses.scheduleWithFixedDelay(()->{
      autoSave();
    }, 5, 5, TimeUnit.SECONDS);  
  }
  //自动存盘操作
  void autoSave(){
    if (!changed) {
      return;
    }
    changed = false;
    //执行存盘操作
    //省略且实现
    this.execSave();
  }
  //编辑操作
  void edit(){
    //省略编辑逻辑
    ......
    changed = true;
  }
}
```

有问题，因为对changed的读写没有进行同步，不线程安全，修改：

```java
//自动存盘操作
void autoSave(){
  synchronized(this){
    if (!changed) {
      return;
    }
    changed = false;
  }
  //执行存盘操作
  //省略且实现
  this.execSave();
}
//编辑操作
void edit(){
  //省略编辑逻辑
  ......
  synchronized(this){
    changed = true;
  }
}  
```

示例中的共享变量是一个状态变量，业务逻辑依赖于这个状态变量的状态。多线程版本的if

## Balking模式的经典实现

```java
boolean changed=false;
//自动存盘操作
void autoSave(){
  synchronized(this){
    if (!changed) {
      return;
    }
    changed = false;
  }
  //执行存盘操作
  //省略且实现
  this.execSave();
}
//编辑操作
void edit(){
  //省略编辑逻辑
  ......
  change();
}
//改变状态
void change(){
  synchronized(this){
    changed = true;
  }
}
```

## 用volatile实现Balking模式

前面是Synchronized实现Balking模式，也可以用volatile（对原子性没有要求）

1. 对共享变量changed和rt的写操作不存在原子性的要求。
2. 同一时刻只有一个线程执行autoSave方法

```java
//路由表信息
public class RouterTable {
  //Key:接口名
  //Value:路由集合
  ConcurrentHashMap<String, CopyOnWriteArraySet<Router>> 
    rt = new ConcurrentHashMap<>();    
  //路由表是否发生变化
  volatile boolean changed;
  //将路由表写入本地文件的线程池
  ScheduledExecutorService ses=
    Executors.newSingleThreadScheduledExecutor();
  //启动定时任务
  //将变更后的路由表写入本地文件
  public void startLocalSaver(){
    ses.scheduleWithFixedDelay(()->{
      autoSave();
    }, 1, 1, MINUTES);
  }
  //保存路由表到本地文件
  void autoSave() {
    if (!changed) {
      return;
    }
    changed = false;
    //将路由表写入本地文件
    //省略其方法实现
    this.save2Local();
  }
  //删除路由
  public void remove(Router router) {
    Set<Router> set=rt.get(router.iface);
    if (set != null) {
      set.remove(router);
      //路由表已发生变化
      changed = true;
    }
  }
  //增加路由
  public void add(Router router) {
    Set<Router> set = rt.computeIfAbsent(
      route.iface, r -> 
        new CopyOnWriteArraySet<>());
    set.add(router);
    //路由表已发生变化
    changed = true;
  }
}
```

一个常见的例子：单次初始化。

```java
public class InitTest {
    boolean inited = false;   
    synchronized void init() {
        if (inited) {
            return;
        }
        //doSthToInit
        inited = true;
    }
}
```

线程安全的单例模式实质上也是这样的：

```java
class Singleton{
  private static
    Singleton singleton;
  //构造方法私有化  
  private Singleton(){}
  //获取实例（单例）
  public synchronized static 
  Singleton getInstance(){
    if(singleton == null){
      singleton=new Singleton();
    }
    return singleton;
  }
}
```

优化方案：双重检查方法

```java
class SingleObject {
    private SingleObject() {}
    
    // 避免获取为空
    private static volatile SingleObject INSTANCE;
    
    public static SingleObject getInstance() {
        if (INSTANCE == null) {
            synchronized (SingleObject.class) {
                if (INSTANCE == null) {
                    // 有三步。
                    INSTANCE = new SingleObject();
                }
            }
        }
        return INSTANCE;
    }
}
```

# Thread-Per-Message模式：分工

## 如何理解？

委托，比如HTTP Server中，主线程只能接收请求，但不能处理HTTP请求。可以创建子线程，委托子线程处理HTTP请求。总结就是为每一个任务分配一个独立的线程

## 用Thread实现Thread-Per-Message模式

Thread-Per-Message模式的一个最经典的应用场景是**网络编程里服务端的实现**，服务端为每个客户端请求创建一个独立的线程，当线程处理完请求后，自动销毁，这是一种最简单的并发处理网络请求的方法。

```java
final ServerSocketChannel  = 
  ServerSocketChannel.open().bind(
    new InetSocketAddress(8080));
//处理请求    
try {
  while (true) {
    // 接收请求
    SocketChannel sc = ssc.accept();
    // 每个请求都创建一个线程
    new Thread(()->{
      try {
        // 读Socket
        ByteBuffer rb = ByteBuffer
          .allocateDirect(1024);
        sc.read(rb);
        //模拟处理请求
        Thread.sleep(2000);
        // 写Socket
        ByteBuffer wb = 
          (ByteBuffer)rb.flip();
        sc.write(wb);
        // 关闭Socket
        sc.close();
      }catch(Exception e){
        throw new UncheckedIOException(e);
      }
    }).start();
  }
} finally {
  ssc.close();
}   
```

Java中的线程是一个重量级的对象，创建成本很高，一方面创建线程比较耗时，另一方面线程占用的内存也比较大。所以，为每个请求创建一个新的线程并不适合高并发场景。这时候很可能你会想到Java提供的线程池。你的这个思路没有问题，但是引入线程池难免会增加复杂度

Java语言里，Java线程是和操作系统线程一一对应的，这种做法本质上是将Java线程的调度权完全委托给操作系统，而操作系统在这方面非常成熟，所以这种做法的好处是稳定、可靠，但是也继承了操作系统线程的缺点：创建成本高。为了解决这个缺点，Java并发包里提供了线程池等工具类。这个思路在很长一段时间里都是很稳妥的方案，但是这个方案并不是唯一的方案。

**轻量级线程**。Go语言、Lua语言里的协程，本质上就是一种轻量级的线程。轻量级的线程，创建的成本很低，基本上和创建一个普通对象的成本相似；并且创建的速度和内存占用相比操作系统线程至少有一个数量级的提升，所以基于轻量级线程实现Thread-Per-Message模式就完全没有问题了。

## Fiber实现

Java中有个项目，轻量级线程被叫做**Fiber**

```java
final ServerSocketChannel ssc = 
  ServerSocketChannel.open().bind(
    new InetSocketAddress(8080));
//处理请求
try{
  while (true) {
    // 接收请求
    final SocketChannel sc = 
      ssc.accept();
    Fiber.schedule(()->{
      try {
        // 读Socket
        ByteBuffer rb = ByteBuffer
          .allocateDirect(1024);
        sc.read(rb);
        //模拟处理请求
        LockSupport.parkNanos(2000*1000000);
        // 写Socket
        ByteBuffer wb = 
          (ByteBuffer)rb.flip()
        sc.write(wb);
        // 关闭Socket
        sc.close();
      } catch(Exception e){
        throw new UncheckedIOException(e);
      }
    });
  }//while
}finally{
  ssc.close();
}
```

1. 首先通过 `ulimit -u 512` 将用户能创建的最大进程数（包括线程）设置为512；
2. 启动Fiber实现的echo程序；
3. 利用压测工具ab进行压测：ab -r -c 20000 -n 200000 [http://测试机IP地址:8080/](http://xn--ip-im8ckc884ihkivx9c:8080/)

```
Concurrency Level:      20000
Time taken for tests:   67.718 seconds
Complete requests:      200000
Failed requests:        0
Write errors:           0
Non-2xx responses:      200000
Total transferred:      16400000 bytes
HTML transferred:       0 bytes
Requests per second:    2953.41 [#/sec] (mean)
Time per request:       6771.844 [ms] (mean)
Time per request:       0.339 [ms] (mean, across all concurrent requests)
Transfer rate:          236.50 [Kbytes/sec] received

Connection Times (ms)
              min  mean[+/-sd] median   max
Connect:        0  557 3541.6      1   63127
Processing:  2000 2010  31.8   2003    2615
Waiting:     1986 2008  30.9   2002    2615
Total:       2000 2567 3543.9   2004   65293
```

即便在20000并发下，该程序依然能够良好运行。同等条件下，Thread实现的echo程序512并发都抗不过去，直接就OOM了。

`top -Hp pid` 查看Fiber实现的echo程序的进程信息，你可以看到该进程仅仅创建了16（不同CPU核数结果会不同）个操作系统线程。

## 总结

Java中Thread-Per-Message方案由于线程是重量级对象，所以没有那么流行；

对于并发度没有那么高的异步场景，比如定时任务，用Thread-Per-Message就是完全没有问题的。

# WorkerThread模式：复用线程

![image-20250424205301364](image\image-20250424205301364.png)

用阻塞队列做任务池，然后创建固定数量的线程消费阻塞队列中的任务。其实你仔细想会发现，这个方案就是Java语言提供的线程池。

```java
ExecutorService es = Executors
  .newFixedThreadPool(500);
final ServerSocketChannel ssc = 
  ServerSocketChannel.open().bind(
    new InetSocketAddress(8080));
//处理请求    
try {
  while (true) {
    // 接收请求
    SocketChannel sc = ssc.accept();
    // 将请求处理任务提交给线程池
    es.execute(()->{
      try {
        // 读Socket
        ByteBuffer rb = ByteBuffer
          .allocateDirect(1024);
        sc.read(rb);
        //模拟处理请求
        Thread.sleep(2000);
        // 写Socket
        ByteBuffer wb = 
          (ByteBuffer)rb.flip();
        sc.write(wb);
        // 关闭Socket
        sc.close();
      }catch(Exception e){
        throw new UncheckedIOException(e);
      }
    });
  }
} finally {
  ssc.close();
  es.shutdown();
}  
```

## 正确创建线程池

建议你**用创建有界的队列来接收任务**。即便线程池默认的拒绝策略能够满足你的需求，也同样建议你**在创建线程池时，清晰地指明拒绝策略**。同时，为了便于调试和诊断问题，我也强烈建议你**在实际工作中给线程赋予一个业务相关的名字**。

```java
ExecutorService es = new ThreadPoolExecutor(
  50, 500,
  60L, TimeUnit.SECONDS,
  //注意要创建有界队列
  new LinkedBlockingQueue<Runnable>(2000),
  //建议根据业务需求实现ThreadFactory
  r->{
    return new Thread(r, "echo-"+ r.hashCode());
  },
  //建议根据业务需求实现RejectedExecutionHandler
  new ThreadPoolExecutor.CallerRunsPolicy());
```

## 避免线程死锁

如果提交到相同线程池的任务不是相互独立的，而是有依赖关系的，那么就有可能导致线程死锁。实际工作中，我就亲历过这种线程死锁的场景。具体现象是**应用每运行一段时间偶尔就会处于无响应的状态，监控数据看上去一切都正常，但是实际上已经不能正常工作了**。

该应用将一个大型的计算任务分成两个阶段，第一个阶段的任务会等待第二阶段的子任务完成。在这个应用里，每一个阶段都使用了线程池，而且两个阶段使用的还是同一个线程池。

![image-20250424205505315](image\image-20250424205505315.png)

```java
//L1、L2阶段共用的线程池
ExecutorService es = Executors.
  newFixedThreadPool(2);
//L1阶段的闭锁    
CountDownLatch l1=new CountDownLatch(2);
for (int i=0; i<2; i++){
  System.out.println("L1");
  //执行L1阶段任务
  es.execute(()->{
    //L2阶段的闭锁 
    CountDownLatch l2=new CountDownLatch(2);
    //执行L2阶段子任务
    for (int j=0; j<2; j++){
      es.execute(()->{
        System.out.println("L2");
        l2.countDown();
      });
    }
    //等待L2阶段任务执行完
    l2.await();
    l1.countDown();
  });
}
//等着L1阶段任务执行完
l1.await();
System.out.println("end");
```

线程池中的两个线程全部都阻塞在 `l2.await();` 这行代码上了，也就是说，线程池里所有的线程都在等待L2阶段的任务执行完

![image-20250424205616821](image\image-20250424205616821.png)

最简单粗暴的办法就是将线程池的最大线程数调大，如果能够确定任务的数量不是非常多的话，这个办法也是可行的，否则这个办法就行不通了。其实**这种问题通用的解决方案是为不同的任务创建不同的线程池**。对于上面的这个应用，L1阶段的任务和L2阶段的任务如果各自都有自己的线程池，就不会出现这种问题了。

最后再次强调一下：**提交到相同线程池中的任务一定是相互独立的，否则就一定要慎重**。

# 两阶段终止模式：优雅终止线程

“优雅地终止线程”，不是自己终止自己，而是在一个线程T1中，终止线程T2；这里所谓的“优雅”，指的是给T2一个机会料理后事，而不是被一剑封喉。

## 如何理解？

第一个阶段主要是线程T1向线程T2**发送终止指令**，而第二阶段则是线程T2**响应终止指令**

![image-20250424205908867](image\image-20250424205908867.png)

Java线程进入终止状态的前提是线程进入RUNNABLE状态，而实际上线程也可能处在休眠状态，也就是说，我们要想终止一个线程，首先要把线程的状态从休眠状态转换到RUNNABLE状态。如何做到呢？这个要靠Java Thread类提供的**interrupt()方法**，它可以将休眠状态的线程转换到RUNNABLE状态。

优雅的方式是让Java线程自己执行完 run() 方法，所以一般我们采用的方法是**设置一个标志位**，然后线程会在合适的时机检查这个标志位，如果发现符合终止条件，则自动退出run()方法。这个过程其实就是我们前面提到的第二阶段：**响应终止指令**。



**interrupt()方法**和**线程终止的标志位**。

## 用两阶段终止模式终止监控操作

![image-20250424210039032](image\image-20250424210039032.png)

出于对性能的考虑（有些监控项对系统性能影响很大，所以不能一直持续监控），动态采集功能一般都会有终止操作。

```java
class Proxy {
  boolean started = false;
  //采集线程
  Thread rptThread;
  //启动采集功能
  synchronized void start(){
    //不允许同时启动多个采集线程
    if (started) {
      return;
    }
    started = true;
    rptThread = new Thread(()->{
      while (true) {
        //省略采集、回传实现
        report();
        //每隔两秒钟采集、回传一次数据
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e) {  
        }
      }
      //执行到此处说明线程马上终止
      started = false;
    });
    rptThread.start();
  }
  //终止采集功能
  synchronized void stop(){
    //如何实现？
  }
}  
```

需要注意的是，我们在捕获Thread.sleep()的中断异常之后，通过 `Thread.currentThread().interrupt()` 重新设置了线程的中断状态，因为JVM的异常处理会清除线程的中断状态。

```java
class Proxy {
  boolean started = false;
  //采集线程
  Thread rptThread;
  //启动采集功能
  synchronized void start(){
    //不允许同时启动多个采集线程
    if (started) {
      return;
    }
    started = true;
    rptThread = new Thread(()->{
      while (!Thread.currentThread().isInterrupted()){
        //省略采集、回传实现
        report();
        //每隔两秒钟采集、回传一次数据
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e){
          //重新设置线程中断状态
          Thread.currentThread().interrupt();
        }
      }
      //执行到此处说明线程马上终止
      started = false;
    });
    rptThread.start();
  }
  //终止采集功能
  synchronized void stop(){
    rptThread.interrupt();
  }
}
```





我们很可能在线程的run()方法中调用第三方类库提供的方法，而我们没有办法保证第三方类库正确处理了线程的中断异常，例如第三方类库在捕获到Thread.sleep()方法抛出的中断异常后，没有重新设置线程的中断状态，那么就会导致线程不能够正常终止。所以强烈建议你**设置自己的线程终止标志位**

```java
class Proxy {
  //线程终止标志位
  volatile boolean terminated = false;
  boolean started = false;
  //采集线程
  Thread rptThread;
  //启动采集功能
  synchronized void start(){
    //不允许同时启动多个采集线程
    if (started) {
      return;
    }
    started = true;
    terminated = false;
    rptThread = new Thread(()->{
      while (!terminated){
        //省略采集、回传实现
        report();
        //每隔两秒钟采集、回传一次数据
        try {
          Thread.sleep(2000);
        } catch (InterruptedException e){
          //重新设置线程中断状态
          Thread.currentThread().interrupt();
        }
      }
      //执行到此处说明线程马上终止
      started = false;
    });
    rptThread.start();
  }
  //终止采集功能
  synchronized void stop(){
    //设置中断标志位
    terminated = true;
    //中断线程rptThread
    rptThread.interrupt();
  }
}
```



## 优雅终止线程池

shutdown()方法是一种很保守的关闭线程池的方法。线程池执行shutdown()后，就会拒绝接收新的任务，但是会等待线程池中正在执行的任务和已经进入阻塞队列的任务都执行完之后才最终关闭线程池。

而shutdownNow()方法，相对就激进一些了，线程池执行shutdownNow()后，会拒绝接收新的任务，同时还会中断线程池中正在执行的任务，已经进入阻塞队列的任务也被剥夺了执行的机会，不过这些被剥夺执行机会的任务会作为shutdownNow()方法的返回值返回。因为shutdownNow()方法会中断正在执行的线程，所以提交到线程池的任务，如果需要优雅地结束，就需要正确地处理线程中断。

如果提交到线程池的任务不允许取消，那就不能使用shutdownNow()方法终止线程池。不过，如果提交到线程池的任务允许后续以补偿的方式重新执行，也是可以使用shutdownNow()方法终止线程池的。提到一种将已提交但尚未开始执行的任务以及已经取消的正在执行的任务保存起来，以便后续重新执行的方案

它们实质上使用的也是两阶段终止模式，只是终止指令的范围不同而已，前者**只影响阻塞队列接收任务**，后者范围**扩大到线程池中所有的任务**。

## 总结

一个是仅检查终止标志位是不够的，因为线程的状态可能处于休眠态；另一个是仅检查线程的中断状态也是不够的，因为我们依赖的第三方类库很可能没有正确处理中断异常



# 生产者消费者模式：流水线思想提高效率

## 优点

生产者线程1、2、3 --> 任务队列 --> 消费者线程1 2 3

架构设计的优点：

1. 解耦：生产者消费者之间的通信只通过任务队列
2. 异步：平衡生产者和消费者之间的速度差异

## 支持批量执行以提升性能

轻量级线程是链价的话，那么生产者消费者模式是否没用了呢？

1. 1000个线程insert一条数据
2. 1个线程批量insert1000条线程。

第2种好。



有一个例子：怎么做到回传数据批量插入到数据库中？

![image-20250426171140084](image\image-20250426171140084.png)

利用生产者-消费者模式实现批量执行SQL非常简单：将原来直接INSERT数据到数据库的线程作为生产者线程，生产者线程只需将数据添加到任务队列，然后消费者线程负责将任务从任务队列中批量取出并批量执行。

```java
//任务队列
BlockingQueue<Task> bq=new
  LinkedBlockingQueue<>(2000);
//启动5个消费者线程
//执行批量任务  
void start() {
  ExecutorService es=executors
    .newFixedThreadPool(5);
    
  for (int i=0; i<5; i++) {
    es.execute(()->{
      try {
        while (true) {
          //获取批量任务
          List<Task> ts=pollTasks();
          //执行批量任务
          execTasks(ts);
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
    });
  }
}
//从任务队列中获取批量任务
List<Task> pollTasks() 
    throws InterruptedException{
  List<Task> ts=new LinkedList<>();
  //阻塞式获取一条任务
  Task t = bq.take();
  while (t != null) {
    ts.add(t);
    //非阻塞式获取一条任务
    t = bq.poll();
  }
  return ts;
}
//批量执行任务
execTasks(List<Task> ts) {
  //省略具体代码无数
}
```

5个消费者线程以 `while(true){}` 循环方式批量地获取任务并批量地执行。需要注意的是，从任务队列中获取批量任务的方法pollTasks()中，首先是以阻塞方式获取任务队列中的一条任务，而后则是以非阻塞的方式获取任务；之所以首先采用阻塞方式，是因为如果任务队列中没有任务，这样的方式能够避免无谓的循环。



## 支持分阶段提交以提升性能

写文件如果同步刷盘性能会很慢，所以对于不是很重要的数据，我们往往采用异步刷盘的方式。我曾经参与过一个项目，其中的日志组件是自己实现的，采用的就是异步刷盘方式，刷盘的时机是：

1. ERROR级别的日志需要立即刷盘；
2. 数据积累到500条需要立即刷盘；
3. 存在未刷盘数据，且5秒钟内未曾刷盘，需要立即刷盘。

调用 `info()`和`error()` 方法写入日志，这两个方法都是创建了一个日志任务LogMsg，并添加到阻塞队列中，调用 `info()`和`error()` 方法的线程是生产者；而真正将日志写入文件的是消费者线程，在Logger这个类中，我们只创建了1个消费者线程，在这个消费者线程中，会根据刷盘规则执行刷盘操作

```java
class Logger {
  //任务队列  
  final BlockingQueue<LogMsg> bq
    = new BlockingQueue<>();
  //flush批量  
  static final int batchSize=500;
  //只需要一个线程写日志
  ExecutorService es = 
    Executors.newFixedThreadPool(1);
  //启动写日志线程
  void start(){
    File file=File.createTempFile(
      "foo", ".log");
    final FileWriter writer=
      new FileWriter(file);
    this.es.execute(()->{
      try {
        //未刷盘日志数量
        int curIdx = 0;
        long preFT=System.currentTimeMillis();
        while (true) {
          LogMsg log = bq.poll(
            5, TimeUnit.SECONDS);
          //写日志
          if (log != null) {
            writer.write(log.toString());
            ++curIdx;
          }
          //如果不存在未刷盘数据，则无需刷盘
          if (curIdx <= 0) {
            continue;
          }
          //根据规则刷盘
          if (log!=null && log.level==LEVEL.ERROR ||
              curIdx == batchSize ||
              System.currentTimeMillis()-preFT>5000){
            writer.flush();
            curIdx = 0;
            preFT=System.currentTimeMillis();
          }
        }
      }catch(Exception e){
        e.printStackTrace();
      } finally {
        try {
          writer.flush();
          writer.close();
        }catch(IOException e){
          e.printStackTrace();
        }
      }
    });  
  }
  //写INFO级别日志
  void info(String msg) {
    bq.put(new LogMsg(
      LEVEL.INFO, msg));
  }
  //写ERROR级别日志
  void error(String msg) {
    bq.put(new LogMsg(
      LEVEL.ERROR, msg));
  }
}
//日志级别
enum LEVEL {
  INFO, ERROR
}
class LogMsg {
  LEVEL level;
  String msg;
  //省略构造函数实现
  LogMsg(LEVEL lvl, String msg){}
  //省略toString()实现
  String toString(){}
}
```

## 总结

MQ一般都会支持两种消息模型，一种是点对点模型，一种是发布订阅模型。这两种模型的区别在于，点对点模型里一个消息只会被一个消费者消费，和Java的线程池非常类似（Java线程池的任务也只会被一个线程执行）；而发布订阅模型里一个消息会被多个消费者消费，本质上是一种消息的广播，在多线程编程领域，你可以结合观察者模式实现广播功能。





# 一些小问题

避免共享的设计模式：不变量模式 、CopyOnWrite模式、线程本地存储模式。

多线程版本的if：Guarded Suspension、Balking

分工的设计模式：Thread-Per-Message模式、Worker Thread模式 、生产者消费者模式

- SDK中为什么没有提供 CopyOnWriteLinkedList：性能问题一定是其中一个很重要的原因，毕竟完整地复制LinkedList性能开销太大了
- 在异步场景中，是否可以使用 Spring 的事务管理器。答案显然是不能的，Spring 使用 ThreadLocal 来传递事务信息，因此这个事务信息是不能跨线程共享的。
- 在多线程场景中使用if语句时，一定要多问自己一遍：是否存在竞态条件。
- Thread-Per-Message模式在实现的时候需要注意是否存在线程的频繁创建、销毁以及是否可能导致OOM
- Worker Thread模式的实现，需要注意潜在的线程**死锁问题**“：工厂里只有一个工人，他的工作就是同步地等待工厂里其他人给他提供东西，然而并没有其他人，他将等到天荒地老，海枯石烂！”因此，共享线程池虽然能够提供线程池的使用效率，但一定要保证一个前提，那就是：**任务之间没有依赖关系**。



 # 案例一：Guava RateLimiter

在向线程池提交任务之前，调用 `acquire()` 方法就能起到限流的作用。通过示例代码的执行结果，任务提交到线程池的时间间隔基本上稳定在500毫秒

```java
//限流器流速：2个请求/秒
RateLimiter limiter = 
  RateLimiter.create(2.0);
//执行任务的线程池
ExecutorService es = Executors
  .newFixedThreadPool(1);
//记录上一次执行时间
prev = System.nanoTime();
//测试执行20次
for (int i=0; i<20; i++){
  //限流器限流
  limiter.acquire();
  //提交任务异步执行
  es.execute(()->{
    long cur=System.nanoTime();
    //打印时间间隔：毫秒
    System.out.println(
      (cur-prev)/1000_000);
    prev = cur;
  });
}

输出结果：
...
500
499
499
500
499
```

## 令牌桶算法·

**令牌桶算法**，其**核心是要想通过限流器，必须拿到令牌**

1. 令牌以固定的速率添加到令牌桶中，假设限流的速率是 r/秒，则令牌每 1/r 秒会添加一个；
2. 假设令牌桶的容量是 b ，如果令牌桶已满，则新的令牌会被丢弃；
3. 请求能够通过限流器的前提是令牌桶中有令牌。

b 其实是burst的简写，意义是**限流器允许的最大突发流量**。比如b=10，而且令牌桶中的令牌已满，此时限流器允许10个请求同时通过限流器，当然只是突发流量而已，这10个请求会带走10个令牌，所以后续的流量只能按照速率 r 通过限流器。



你的直觉会告诉你生产者-消费者模式：一个生产者线程定时向阻塞队列中添加令牌，而试图通过限流器的线程则作为消费者线程，只有从阻塞队列中获取到令牌，才允许通过限流器。



这个算法看上去非常完美，而且实现起来非常简单，如果并发量不大，这个实现并没有什么问题。可实际情况却是使用限流的场景大部分都是高并发场景，而且系统压力已经临近极限了，此时这个实现就有问题了。问题就出在定时器上，在高并发场景下，当系统压力已经临近极限的时候，定时器的精度误差会非常大，同时定时器本身会创建调度线程，也会对系统的性能产生影响。

## Guava如何实现

**记录并动态计算下一令牌发放的时间**。下面我们以一个最简单的场景来介绍该算法的执行过程。假设令牌桶的容量为 b=1，限流速率 r = 1个请求/秒，如下图所示，如果当前令牌桶中没有令牌，下一个令牌的发放时间是在第3秒，而在第2秒的时候有一个线程T1请求令牌，此时该如何处理呢

![image-20250427192220224](image\image-20250427192220224.png)

对于这个请求令牌的线程而言，很显然需要等待1秒，因为1秒以后（第3秒）它就能拿到令牌了。此时需要注意的是，下一个令牌发放的时间也要增加1秒，为什么呢？因为第3秒发放的令牌已经被线程T1预占了

![image-20250427192259592](image\image-20250427192259592.png)

假设T1在预占了第3秒的令牌之后，马上又有一个线程T2请求令牌，如下图所示。



![image-20250427192332213](image\image-20250427192332213.png)

很显然，由于下一个令牌产生的时间是第4秒，所以线程T2要等待两秒的时间，才能获取到令牌，同时由于T2预占了第4秒的令牌，所以下一令牌产生时间还要增加1秒，完全处理之后，如下图所示

![image-20250427192400936](image\image-20250427192400936.png)

如果线程在**下一令牌产生时间之后**请求令牌会如何呢？假设在线程T1请求令牌之后的5秒，也就是第7秒，线程T3请求令牌

![image-20250427192419746](image\image-20250427192419746.png)

由于在第5秒已经产生了一个令牌，所以此时线程T3可以直接拿到令牌，而无需等待。在第7秒，实际上限流器能够产生3个令牌，第5、6、7秒各产生一个令牌。由于我们假设令牌桶的容量是1，所以第6、7秒产生的令牌就丢弃了，其实等价地你也可以认为是保留的第7秒的令牌，丢弃的第5、6秒的令牌，也就是说第7秒的令牌被线程T3占有了，于是下一令牌的的产生时间应该是第8秒

![image-20250427192555450](image\image-20250427192555450.png)

**只需要记录一个下一令牌产生的时间，并动态更新它，就能够轻松完成限流功能**。我们可以将上面的这个算法代码化，示例代码如下所示，依然假设令牌桶的容量是1。关键是**reserve()方法**，这个方法会为请求令牌的线程预分配令牌，同时返回该线程能够获取令牌的时间。其实现逻辑就是上面提到的：如果线程请求令牌的时间在下一令牌产生时间之后，那么该线程立刻就能够获取令牌；反之，如果请求时间在下一令牌产生时间之前，那么该线程是在下一令牌产生的时间获取令牌。由于此时下一令牌已经被该线程预占，所以下一令牌产生的时间需要加上1秒

```java
class SimpleLimiter {
  //下一令牌产生时间
  long next = System.nanoTime();
  //发放令牌间隔：纳秒
  long interval = 1000_000_000;
  //预占令牌，返回能够获取令牌的时间
  synchronized long reserve(long now){
    //请求时间在下一令牌产生时间之后
    //重新计算下一令牌产生时间
    if (now > next){
      //将下一令牌产生时间重置为当前时间
      next = now;
    }
    //能够获取令牌的时间
    long at=next;
    //设置下一令牌产生时间
    next += interval;
    //返回线程需要等待的时间
    return Math.max(at, 0L);
  }
  //申请令牌
  void acquire() {
    //申请令牌时的时间
    long now = System.nanoTime();
    //预占令牌
    long at=reserve(now);
    long waitTime=max(at-now, 0);
    //按照条件等待
    if(waitTime > 0) {
      try {
        TimeUnit.NANOSECONDS
          .sleep(waitTime);
      }catch(InterruptedException e){
        e.printStackTrace();
      }
    }
  }
}
```

如果令牌桶的容量大于1，又该如何处理呢？按照令牌桶算法，令牌要首先从令牌桶中出，所以我们需要按需计算令牌桶中的数量，当有线程请求令牌时，先从令牌桶中出。具体的代码实现如下所示。我们增加了一个**resync()方法**，在这个方法中，如果线程请求令牌的时间在下一令牌产生时间之后，会重新计算令牌桶中的令牌数，**新产生的令牌的计算公式是：(now-next)/interval**，你可对照上面的示意图来理解。reserve()方法中，则增加了先从令牌桶中出令牌的逻辑，不过需要注意的是，如果令牌是从令牌桶中出的，那么next就无需增加一个 interval 了。

```java
class SimpleLimiter {
  //当前令牌桶中的令牌数量
  long storedPermits = 0;
  //令牌桶的容量
  long maxPermits = 3;
  //下一令牌产生时间
  long next = System.nanoTime();
  //发放令牌间隔：纳秒
  long interval = 1000_000_000;

  //请求时间在下一令牌产生时间之后,则
  // 1.重新计算令牌桶中的令牌数
  // 2.将下一个令牌发放时间重置为当前时间
  void resync(long now) {
    if (now > next) {
      //新产生的令牌数
      long newPermits=(now-next)/interval;
      //新令牌增加到令牌桶
      storedPermits=min(maxPermits, 
        storedPermits + newPermits);
      //将下一个令牌发放时间重置为当前时间
      next = now;
    }
  }
  //预占令牌，返回能够获取令牌的时间
  synchronized long reserve(long now){
    resync(now);
    //能够获取令牌的时间
    long at = next;
    //令牌桶中能提供的令牌
    long fb=min(1, storedPermits);
    //令牌净需求：首先减掉令牌桶中的令牌
    long nr = 1 - fb;
    //重新计算下一令牌产生时间
    next = next + nr*interval;
    //重新计算令牌桶中的令牌
    this.storedPermits -= fb;
    return at;
  }
  //申请令牌
  void acquire() {
    //申请令牌时的时间
    long now = System.nanoTime();
    //预占令牌
    long at=reserve(now);
    long waitTime=max(at-now, 0);
    //按照条件等待
    if(waitTime > 0) {
      try {
        TimeUnit.NANOSECONDS
          .sleep(waitTime);
      }catch(InterruptedException e){
        e.printStackTrace();
      }
    }
  }
}
```

## 总结

经典的限流算法有两个，一个是**令牌桶算法（Token Bucket）**，另一个是**漏桶算法（Leaky Bucket）**。令牌桶算法是定时向令牌桶发送令牌，请求能够从令牌桶中拿到令牌，然后才能通过限流器；而漏桶算法里，请求就像水一样注入漏桶，漏桶会按照一定的速率自动将水漏掉，只有漏桶里还能注入水的时候，请求才能通过限流器。令牌桶算法和漏桶算法很像一个硬币的正反面，所以你可以参考令牌桶算法的实现来实现漏桶算法

Guava RateLimiter扩展了标准的令牌桶算法，比如还能支持预热功能。对于按需加载的缓存来说，预热后缓存能支持5万TPS的并发，但是在预热前5万TPS的并发直接就把缓存击垮了，所以如果需要给该缓存限流，限流器也需要支持预热功能，在初始阶段，限制的流速 r 很小，但是动态增长的。预热功能的实现非常复杂，Guava构建了一个积分函数来解决这个问题，如果你感兴趣，可以继续深入研究。

# Netty

线程模型

## 网络编程性能的瓶颈

阻塞式I/O（BIO）。BIO模型里，所有read()操作和write()操作都会阻塞当前线程的，如果客户端已经和服务端建立了一个连接，而迟迟不发送数据，那么服务端的read()操作会一直阻塞，所以**使用BIO模型，一般都会为每个socket分配一个独立的线程**，这样就不会因为线程阻塞在一个socket上而影响对其他socket的读写。每一个socket都对应一个独立的线程；为了避免频繁创建、消耗线程，可以采用线程池，但是socket和线程之间的对应关系并不会变化。

![image-20250427193636217](image\image-20250427193636217.png)

互联网场景中，虽然连接多，但是每个连接上的请求并不频繁，所以线程大部分时间都在等待I/O就绪。也就是说线程大部分时间都阻塞在那里，这完全是浪费。

可以用一个线程来处理多个连接，这样线程的利用率就上来了，同时所需的线程数量也跟着降下来了。这个思路很好，可是使用BIO相关的API是无法实现的，这是为什么呢？因为BIO相关的socket读写操作都是阻塞式的，而一旦调用了阻塞式API，在I/O就绪前，调用线程会一直阻塞，也就无法处理其他的socket连接了

![image-20250427193753033](image\image-20250427193753033.png)

非阻塞式（NIO）API，**利用非阻塞式API就能够实现一个线程处理多个连接了**。那具体如何实现呢？现在普遍都是**采用Reactor模式**

## Reactor模式

Handle指的是I/O句柄，在Java网络编程里，它本质上就是一个网络连接。Event Handler很容易理解，就是一个事件处理器，其中handle_event()方法处理I/O事件，也就是每个Event Handler处理一个I/O Handle；get_handle()方法可以返回这个I/O的Handle。Synchronous Event Demultiplexer可以理解为操作系统提供的I/O多路复用API，例如POSIX标准里的select()以及Linux里面的epoll()。

![image-20250427193853993](image\image-20250427193853993.png)

**Reactor这个类**，其中register_handler()和remove_handler()这两个方法可以注册和删除一个事件处理器；**handle_events()方式是核心**，也是Reactor模式的发动机，这个方法的核心逻辑如下：首先通过同步事件多路选择器提供的select()方法监听网络事件，当有网络事件就绪后，就遍历事件处理器来处理该网络事件。由于网络事件是源源不断的，所以在主程序中启动Reactor模式，需要以 `while(true){}` 的方式调用handle_events()方法。

```java
void Reactor::handle_events(){
  //通过同步事件多路选择器提供的
  //select()方法监听网络事件
  select(handlers);
  //处理网络事件
  for(h in handlers){
    h.handle_event();
  }
}
// 在主程序中启动事件循环
while (true) {
  handle_events();
```

## Netty

最核心：事件循环EventLoop，也就是Reactor，负责监听网络事件并且调用事件处理器进行处理。

网络连接和EventLoop是多对一的关系，EventLoop和线程是一对一的关系。也就是一个网络连接只对应一个EventLoop，一个EventLoop只对应一个Java线程=> 一个网络连接，只对应一个Java线程。也就是网络连接的事件处理是单线程的，避免了并发问题。

![image-20250427194337453](image\image-20250427194337453.png)

EventLoopGroup：由一组EventLoop组成，一般至少有两个EventLoopGroup，bossGroup workerGroup



socket处理TCP网络连接请求，是在一个独立的socket中，每当有一个TCP连接成功建立，都会创建一个新的socket，之后对TCP连接的读写都是由新创建处理的socket完成的。也就是说**处理TCP连接请求和读写请求是通过两个不同的socket完成的**



bossGroup就是用来处理连接请求的，workerGroup是用来处理读写请求。bossGroup处理完连接请求后，会将这个连接提交给workerGroup来处理， workerGroup里面有多个EventLoop，那新的连接会交给哪个EventLoop来处理呢？这就需要一个负载均衡算法，Netty中目前使用的是**轮询算法**。

## 实现示例

第一个，如果NettybossGroup只监听一个端口，那bossGroup只需要1个EventLoop就可以了，多了纯属浪费。

第二个，默认情况下，Netty会创建“2*CPU核数”个EventLoop，由于网络连接与EventLoop有稳定的关系，所以事件处理器在处理网络事件的时候是不能有阻塞操作的，否则很容易导致请求大面积超时。如果实在无法避免使用阻塞操作，那可以通过线程池来异步处理。

```java
//事件处理器
final EchoServerHandler serverHandler 
  = new EchoServerHandler();

//boss线程组  
EventLoopGroup bossGroup 
  = new NioEventLoopGroup(1); 

//worker线程组  
EventLoopGroup workerGroup 
  = new NioEventLoopGroup();

try {
  ServerBootstrap b = new ServerBootstrap();
  b.group(bossGroup, workerGroup)
   .channel(NioServerSocketChannel.class)
   .childHandler(new ChannelInitializer<SocketChannel>() {
     @Override
     public void initChannel(SocketChannel ch){
       ch.pipeline().addLast(serverHandler);
     }
    });
  //bind服务端端口  
  ChannelFuture f = b.bind(9090).sync();
  f.channel().closeFuture().sync();
} finally {
  //终止工作线程组
  workerGroup.shutdownGracefully();
  //终止boss线程组
  bossGroup.shutdownGracefully();
}

//socket连接处理器
class EchoServerHandler extends 
    ChannelInboundHandlerAdapter {
  //处理读事件  
  @Override
  public void channelRead(
    ChannelHandlerContext ctx, Object msg){
      ctx.write(msg);
  }
  //处理读完成事件
  @Override
  public void channelReadComplete(
    ChannelHandlerContext ctx){
      ctx.flush();
  }
  //处理异常事件
  @Override
  public void exceptionCaught(
    ChannelHandlerContext ctx,  Throwable cause) {
      cause.printStackTrace();
      ctx.close();
  }
}
```

## 总结

Netty是一个款优秀的网络编程框架，性能非常好，为了实现高性能的目标，Netty做了很多优化，例如优化了ByteBuffer、支持零拷贝等等，和并发编程相关的就是它的线程模型了。Netty的线程模型设计得很精巧，每个网络连接都关联到了一个线程上，这样做的好处是：对于一个网络连接，读写操作都是单线程执行的，从而避免了并发程序的各种问题。



# 高性能队列Disruptor

性能更高的有界队列：Disruptor。

**Disruptor是一款高性能的有界内存队列**

1. 内存分配更加合理，使用RingBuffer数据结构，数组元素在初始化时一次性全部创建，提升缓存命中率；对象循环利用，避免频繁GC。
2. 能够避免伪共享，提升缓存利用率。
3. 采用无锁算法，避免频繁加锁、解锁的性能消耗。
4. 支持批量消费，消费者可以无锁方式消费多个消息。



- 在Disruptor中，生产者生产的对象（也就是消费者消费的对象）称为Event，使用Disruptor必须自定义Event，例如示例代码的自定义Event是LongEvent；
- 构建Disruptor对象除了要指定队列大小外，还需要传入一个EventFactory，示例代码中传入的是`LongEvent::new`；
- 消费Disruptor中的Event需要通过handleEventsWith()方法注册一个事件处理器，发布Event则需要通过publishEvent()方法。

```java
//自定义Event 
class LongEvent { 
    private long value; 
    
    public void set(long value) {
        this.value = value;
	} 
}
//指定RingBuffer大小, //必须是2的N次方 
int bufferSize = 1024;

//构建Disruptor 
Disruptor disruptor = new Disruptor<>(
	LongEvent::new,
	bufferSize,
	DaemonThreadFactory.INSTANCE);
//注册事件处理器 
disruptor.handleEventsWith( (event, sequence, endOfBatch) ->

	System.out.println("E: "+event));
	//启动Disruptor 
	disruptor.start();
//获取RingBuffer 
	RingBuffer ringBuffer = disruptor.getRingBuffer(); 
//生产Event 
	ByteBuffer bb = ByteBuffer.allocate(8); 
	for (long l = 0; true; l++){ bb.putLong(0, l); 
                                //生产者生产消息 
    ringBuffer.publishEvent(
    (event, sequence, buffer) -> 
      event.set(buffer.getLong(0)), bb);
    Thread.sleep(1000); }
}
```

## RingBuffer如何提升性能

ArrayBlockingQueue使用**数组**作为底层的数据存储，而Disruptor是使用**RingBuffer**作为数据存储

**程序的局部性原理指的是在一段时间内程序的执行会限定在一个局部范围内**。这里的“局部性”可以从两个方面来理解，一个是时间局部性，另一个是空间局部性。**时间局部性**指的是程序中的某条指令一旦被执行，不久之后这条指令很可能再次被执行；如果某条数据被访问，不久之后这条数据很可能再次被访问。而**空间局部性**是指某块内存一旦被访问，不久之后这块内存附近的内存也很可能被访问。

CPU从内存中加载数据X时，会将数据X缓存在高速缓存Cache中，实际上CPU缓存X的同时，还缓存了X周围的数据。

ArrayBlockingQueue。生产者线程向ArrayBlockingQueue增加一个元素，每次增加元素E之前，都需要创建一个对象E，如下图所示，ArrayBlockingQueue内部有6个元素，这6个元素都是由生产者线程创建的，由于创建这些元素的时间基本上是离散的，所以这些元素的内存地址大概率也不是连续的

![image-20250428193731895](image\image-20250428193731895.png)

Disruptor内部的RingBuffer也是用数组实现的，但是这个数组中的所有元素在初始化时是一次性全部创建的，所以这些元素的内存地址大概率是连续的

```java
for (int i=0; i<bufferSize; i++){
  //entries[]就是RingBuffer内部的数组
  //eventFactory就是前面示例代码中传入的LongEvent::new
  entries[BUFFER_PAD + i] 
    = eventFactory.newInstance();
}
```

数组中所有元素内存地址连续能提升性能吗？能！为什么呢？因为消费者线程在消费的时候，是遵循空间局部性原理的，消费完第1个元素，很快就会消费第2个元素；当消费第1个元素E1的时候，CPU会把内存中E1后面的数据也加载进Cache，如果E1和E2在内存中的地址是连续的，那么E2也就会被加载进Cache中，然后当消费第2个元素的时候，由于E2已经在Cache中了，所以就不需要从内存中加载了，这样就能大大提升性能

![image-20250428193827343](image\image-20250428193827343.png)

在Disruptor中，生产者线程通过publishEvent()发布Event的时候，并不是创建一个新的Event，而是通过event.set()方法修改Event， 也就是说RingBuffer创建的Event是可以循环利用的，这样还能避免频繁创建、删除Event导致的频繁GC问题。

## 避免伪共享

伪共享和CPU内部的Cache有关，Cache内部是按照缓存行（Cache Line）管理的，缓存行的大小通常是64个字节；CPU从内存中加载数据X，会同时加载X后面（64-size(X)）个字节的数据。

ArrayBlockingQueue，其内部维护了4个成员变量，分别是队列数组items、出队索引takeIndex、入队索引putIndex以及队列中的元素总数count。

```java
/** 队列数组 */
final Object[] items;
/** 出队索引 */
int takeIndex;
/** 入队索引 */
int putIndex;
/** 队列中元素总数 */
int count;
```

当CPU从内存中加载takeIndex的时候，会同时将putIndex以及count都加载进Cache。下图是某个时刻CPU中Cache的状况，为了简化，缓存行中我们仅列出了takeIndex和putIndex。

![image-20250428193937031](image\image-20250428193937031.png)

假设线程A运行在CPU-1上，执行入队操作，入队操作会修改putIndex，而修改putIndex会导致其所在的所有核上的缓存行均失效；此时假设运行在CPU-2上的线程执行出队操作，出队操作需要读取takeIndex，由于takeIndex所在的缓存行已经失效，所以CPU-2必须从内存中重新读取。入队操作本不会修改takeIndex，但是由于takeIndex和putIndex共享的是一个缓存行，就导致出队操作不能很好地利用Cache，这其实就是**伪共享**。简单来讲，**伪共享指的是由于共享缓存行导致缓存无效的场景**。

ArrayBlockingQueue的入队和出队操作是用锁来保证互斥的，所以入队和出队不会同时发生。如果允许入队和出队同时发生，那就会导致线程A和线程B争用同一个缓存行，这样也会导致性能问题。所以为了更好地利用缓存，我们必须避免伪共享.

方案很简单，**每个变量独占一个缓存行、不共享缓存行**就可以了，具体技术是**缓存行填充**。比如想让takeIndex独占一个缓存行，可以在takeIndex的前后各填充56个字节，这样就一定能保证takeIndex独占一个缓存行。下面的示例代码出自Disruptor，Sequence 对象中的value属性就能避免伪共享，因为这个属性前后都填充了56个字节。Disruptor中很多对象，例如RingBuffer、RingBuffer内部的数组都用到了这种填充技术来避免伪共享。

```java
//前：填充56字节
class LhsPadding{
    long p1, p2, p3, p4, p5, p6, p7;
}
class Value extends LhsPadding{
    volatile long value;
}
//后：填充56字节
class RhsPadding extends Value{
    long p9, p10, p11, p12, p13, p14, p15;
}
class Sequence extends RhsPadding{
  //省略实现
}
```

## Disruptor中的无锁算法

Disruptor采用的是无锁算法，很复杂，但是核心无非是生产和消费两个操作。Disruptor中最复杂的是入队操作，所以我们重点来看看入队操作是如何实现的。

对于入队操作，最关键的要求是不能覆盖没有消费的元素；对于出队操作，最关键的要求是不能读取没有写入的元素，所以Disruptor中也一定会维护类似出队索引和入队索引这样两个关键变量。Disruptor中的RingBuffer维护了入队索引，但是并没有维护出队索引，这是因为在Disruptor中多个消费者可以同时消费，每个消费者都会有一个出队索引，所以RingBuffer的出队索引是所有消费者里面最小的那一个.



逻辑很简单：如果没有足够的空余位置，就出让CPU使用权，然后重新计算；反之则用CAS设置入队索引。

```java
//生产者获取n个写入位置
do {
  //cursor类似于入队索引，指的是上次生产到这里
  current = cursor.get();
  //目标是在生产n个
  next = current + n;
  //减掉一个循环
  long wrapPoint = next - bufferSize;
  //获取上一次的最小消费位置
  long cachedGatingSequence = gatingSequenceCache.get();
  //没有足够的空余位置
  if (wrapPoint>cachedGatingSequence || cachedGatingSequence>current){
    //重新计算所有消费者里面的最小值位置
    long gatingSequence = Util.getMinimumSequence(
        gatingSequences, current);
    //仍然没有足够的空余位置，出让CPU使用权，重新执行下一循环
    if (wrapPoint > gatingSequence){
      LockSupport.parkNanos(1);
      continue;
    }
    //从新设置上一次的最小消费位置
    gatingSequenceCache.set(gatingSequence);
  } else if (cursor.compareAndSet(current, next)){
    //获取写入位置成功，跳出循环
    break;
  }
} while (true);
```

## 总结

Java 8中，提供了避免伪共享的注解：@sun.misc.Contended，通过这个注解就能轻松避免伪共享（需要设置JVM参数-XX:-RestrictContended）。不过避免伪共享是以牺牲内存为代价的



# HiKariCP

数据库连接池和线程池一样，都属于池化资源，作用都是避免重量级资源的频繁创建和销毁，对于数据库连接池来说，也就是避免数据库连接频繁创建和销毁。如下图所示，服务端会在运行期持有一定数量的数据库连接，当需要执行SQL时，并不是直接创建一个数据库连接，而是从连接池中获取一个；当SQL执行完，也并不是将数据库连接真的关掉，而是将其归还到连接池中

![image-20250428194311318](image\image-20250428194311318.png)

1. 通过数据源获取一个数据库连接；
2. 创建Statement；
3. 执行SQL；
4. 通过ResultSet获取SQL执行结果；
5. 释放ResultSet；
6. 释放Statement；
7. 释放数据库连接。

`ds.getConnection()` 获取一个数据库连接时，其实是向数据库连接池申请一个数据库连接，而不是创建一个新的数据库连接。同样，通过 `conn.close()` 释放一个数据库连接时，也不是直接将连接关闭，而是将连接归还给数据库连接池。

```java
//数据库连接池配置
HikariConfig config = new HikariConfig();
config.setMinimumIdle(1);
config.setMaximumPoolSize(2);
config.setConnectionTestQuery("SELECT 1");
config.setDataSourceClassName("org.h2.jdbcx.JdbcDataSource");
config.addDataSourceProperty("url", "jdbc:h2:mem:test");
// 创建数据源
DataSource ds = new HikariDataSource(config);
Connection conn = null;
Statement stmt = null;
ResultSet rs = null;
try {
  // 获取数据库连接
  conn = ds.getConnection();
  // 创建Statement 
  stmt = conn.createStatement();
  // 执行SQL
  rs = stmt.executeQuery("select * from abc");
  // 获取结果
  while (rs.next()) {
    int id = rs.getInt(1);
    ......
  }
} catch(Exception e) {
   e.printStackTrace();
} finally {
  //关闭ResultSet
  close(rs);
  //关闭Statement 
  close(stmt);
  //关闭Connection
  close(conn);
}
//关闭资源
void close(AutoCloseable rs) {
  if (rs != null) {
    try {
      rs.close();
    } catch (SQLException e) {
      e.printStackTrace();
    }
  }
}
```

微观上HiKariCP程序编译出的字节码执行效率更高，站在字节码的角度去优化Java代码.宏观上主要是和两个数据结构有关，一个是FastList，另一个是ConcurrentBag

## FastList

最好的办法是当关闭Connection时，能够自动关闭Statement。为了达到这个目标，Connection就需要跟踪创建的Statement，最简单的办法就是将创建的Statement保存在数组ArrayList里，这样当关闭Connection的时候，就可以依次将数组中的所有Statement关闭.

假设一个Connection依次创建6个Statement，分别是S1、S2、S3、S4、S5、S6，按照正常的编码习惯，关闭Statement的顺序一般是逆序的，关闭的顺序是：S6、S5、S4、S3、S2、S1，而ArrayList的remove(Object o)方法是顺序遍历查找，逆序删除而顺序查找，这样的查找效率就太慢了。如何优化呢？很简单，优化成逆序查找就可以了.

HiKariCP中的FastList相对于ArrayList的一个优化点就是将 `remove(Object element)` 方法的**查找顺序变成了逆序查找**。除此之外，FastList还有另一个优化点，是 `get(int index)` 方法没有对index参数进行越界检查，HiKariCP能保证不会越界，所以不用每次都进行越界检查

## ConcurrentBag

实现一个数据库连接池，最简单的办法就是用两个阻塞队列来实现，一个用于保存空闲数据库连接的队列idle，另一个用于保存忙碌数据库连接的队列busy；获取连接时将空闲的数据库连接从idle队列移动到busy队列，而关闭连接时将数据库连接从busy移动到idle。这种方案将并发问题委托给了阻塞队列，实现简单，但是性能并不是很理想。因为Java SDK中的阻塞队列是用锁实现的，而高并发场景下锁的争用对性能影响很大

```java
//忙碌队列
BlockingQueue<Connection> busy;
//空闲队列
BlockingQueue<Connection> idle;
```

HiKariCP自己实现了一个叫做ConcurrentBag的并发容器,一个核心设计是使用ThreadLocal避免部分并发问题.

最关键的属性有4个，分别是：用于存储所有的数据库连接的共享队列sharedList、线程本地存储threadList、等待数据库连接的线程数waiters以及分配数据库连接的工具handoffQueue。其中，handoffQueue用的是Java SDK提供的SynchronousQueue，SynchronousQueue主要用于线程之间传递数据。

```java
//用于存储所有的数据库连接
CopyOnWriteArrayList<T> sharedList;
//线程本地存储中的数据库连接
ThreadLocal<List<Object>> threadList;
//等待数据库连接的线程数
AtomicInteger waiters;
//分配数据库连接的工具
SynchronousQueue<T> handoffQueue;
```

线程池创建了一个数据库连接时，通过调用ConcurrentBag的add()方法加入到ConcurrentBag中，下面是add()方法的具体实现，逻辑很简单，就是将这个连接加入到共享队列sharedList中，如果此时有线程在等待数据库连接，那么就通过handoffQueue将这个连接分配给等待的线程

```java
//将空闲连接添加到队列
void add(final T bagEntry){
  //加入共享队列
  sharedList.add(bagEntry);
  //如果有等待连接的线程，
  //则通过handoffQueue直接分配给等待的线程
  while (waiters.get() > 0 
    && bagEntry.getState() == STATE_NOT_IN_USE 
    && !handoffQueue.offer(bagEntry)) {
      yield();
  }
}
```

ConcurrentBag提供的borrow()方法，可以获取一个空闲的数据库连接，borrow()的主要逻辑是：

1. 首先查看线程本地存储是否有空闲连接，如果有，则返回一个空闲的连接；
2. 如果线程本地存储中无空闲连接，则从共享队列中获取。
3. 如果共享队列中也没有空闲的连接，则请求线程需要等待

线程本地存储中的连接是可以被其他线程窃取的，所以需要用CAS方法防止重复分配。在共享队列中获取空闲连接，也采用了CAS方法防止重复分配。

```java
T borrow(long timeout, final TimeUnit timeUnit){
  // 先查看线程本地存储是否有空闲连接
  final List<Object> list = threadList.get();
  for (int i = list.size() - 1; i >= 0; i--) {
    final Object entry = list.remove(i);
    final T bagEntry = weakThreadLocals 
      ? ((WeakReference<T>) entry).get() 
      : (T) entry;
    //线程本地存储中的连接也可以被窃取，
    //所以需要用CAS方法防止重复分配
    if (bagEntry != null 
      && bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
      return bagEntry;
    }
  }

  // 线程本地存储中无空闲连接，则从共享队列中获取
  final int waiting = waiters.incrementAndGet();
  try {
    for (T bagEntry : sharedList) {
      //如果共享队列中有空闲连接，则返回
      if (bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
        return bagEntry;
      }
    }
    //共享队列中没有连接，则需要等待
    timeout = timeUnit.toNanos(timeout);
    do {
      final long start = currentTime();
      final T bagEntry = handoffQueue.poll(timeout, NANOSECONDS);
      if (bagEntry == null 
        || bagEntry.compareAndSet(STATE_NOT_IN_USE, STATE_IN_USE)) {
          return bagEntry;
      }
      //重新计算等待时间
      timeout -= elapsedNanos(start);
    } while (timeout > 10_000);
    //超时没有获取到连接，返回null
    return null;
  } finally {
    waiters.decrementAndGet();
  }
}
```

释放连接需要调用ConcurrentBag提供的requite()方法，该方法的逻辑很简单，首先将数据库连接状态更改为STATE_NOT_IN_USE，之后查看是否存在等待线程，如果有，则分配给等待线程；如果没有，则将该数据库连接保存到线程本地存储里。

```java
//释放连接
void requite(final T bagEntry){
  //更新连接状态
  bagEntry.setState(STATE_NOT_IN_USE);
  //如果有等待的线程，则直接分配给线程，无需进入任何队列
  for (int i = 0; waiters.get() > 0; i++) {
    if (bagEntry.getState() != STATE_NOT_IN_USE 
      || handoffQueue.offer(bagEntry)) {
        return;
    } else if ((i & 0xff) == 0xff) {
      parkNanos(MICROSECONDS.toNanos(10));
    } else {
      yield();
    }
  }
  //如果没有等待的线程，则进入线程本地存储
  final List<Object> threadLocalList = threadList.get();
  if (threadLocalList.size() < 50) {
    threadLocalList.add(weakThreadLocals 
      ? new WeakReference<>(bagEntry) 
      : bagEntry);
  }
}
```

## 总结

FastList适用于逆序删除场景；而ConcurrentBag通过ThreadLocal做一次预分配，避免直接竞争共享资源，非常适合池化资源的分配。
