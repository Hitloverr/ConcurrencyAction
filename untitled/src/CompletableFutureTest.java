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
