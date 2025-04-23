package com.example.basic;

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
