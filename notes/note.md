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

