package com.example.basic;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;


public class TestForkJoin {
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
}


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