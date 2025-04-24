import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CompletableFutureTest {
    public static void main(String[] args) {
        CompletableFuture<Void> future1 = CompletableFuture.runAsync(() -> {
            System.out.println("t1:洗水壶");
            sleep(1);
            System.out.println("t1:烧开水");
            sleep(15);
        });

        CompletableFuture<Void> future2 = CompletableFuture.runAsync(() -> {
            System.out.println("t2:洗茶壶");
            sleep(2);
            System.out.println("t2:拿茶叶");
            sleep(2);
        });

        CompletableFuture<String> f3 = future1.thenCombine(future2, (_, tf) -> {
            System.out.println("拿到茶叶" + tf);
            System.out.println("泡茶");
            return "上茶" + tf;
        });
        System.out.println(f3.join());
    }

    private static void sleep(int t) {
        try {
            TimeUnit.SECONDS.sleep(t);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
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