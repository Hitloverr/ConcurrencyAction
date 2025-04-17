package java.com.example.basic;

public class TestVisible {
    private static int count = 0;

    public static void main(String[] args) throws InterruptedException {
        new Thread(()->{
            for (int i = 0; i < 100000; i++) {
                count++;
            }
        }).start();

        new Thread(()->{
            for (int i = 0; i < 100000; i++) {
                count++;
            }
        }).start();

        Thread.sleep(1000);
        System.out.println(count);
    }
}
