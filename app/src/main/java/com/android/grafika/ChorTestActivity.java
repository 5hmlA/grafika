/*
 * Copyright 2014 Google Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.grafika;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.app.Activity;
import android.util.Log;
import android.view.Choreographer;

import com.google.grafika.R;

/**
 * 🧪 测试 Choreographer 行为的简单 Activity。
 * Trivial activity used to test Choreographer behavior.
 */
public class ChorTestActivity extends Activity {
    private static final String TAG = "chor-test";

    ChorRenderThread mRenderThread;  // 🧵 Choreographer 渲染线程

    /**
     * 🔧 Activity创建时调用
     * 💡 作用：初始化布局并启动Choreographer渲染线程
     * 💡 时机：Activity首次创建时由系统调用
     */
    // 🚀 Activity 创建时启动渲染线程（共27行，需逐行注释）
    // 🔧 为什么：初始化布局并启动Choreographer渲染线程
    // 📍 时机：Activity首次创建时由系统调用
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📝 Log.d(): 打印创建日志
        // 💡 为什么记录：标记Activity生命周期开始，方便调试和追踪创建流程
        // 💡 TAG变量作用：日志过滤标签，值为"chor-test"
        // 💡 时机：方法入口处最先调用
        Log.d(TAG, "onCreate");

        // 🔄 super.onCreate(savedInstanceState): 调用父类的onCreate方法
        // 💡 savedInstanceState变量作用：Activity之前保存的状态数据
        // 💡 为什么调用：必须执行标准的Activity创建流程，恢复之前保存的状态
        // 💡 作用：完成系统级初始化（如恢复Fragment、恢复视图状态等）
        // 💡 时机：必须在setContentView之前调用
        super.onCreate(savedInstanceState);

        // 🖥️ setContentView(): 设置Activity布局文件
        // 💡 为什么调用：需要加载XML布局到屏幕
        // 💡 作用：将activity_chor_test.xml渲染到屏幕，包含简单UI
        // 💡 R.layout.activity_chor_test: 布局文件资源ID
        // 💡 时机：父类onCreate之后立即调用
        setContentView(R.layout.activity_chor_test);

        // 🧵 mRenderThread: Choreographer渲染线程实例
        // 💡 为什么定义：需要在独立线程中接收VSYNC信号并记录帧时间戳
        // 💡 作用：持有线程对象，内部持有Handler和Choreographer回调
        // 💡 时机：布局加载完成后创建线程对象
        mRenderThread = new ChorRenderThread();  // 🧵 创建渲染线程

        // ▶️ mRenderThread.start(): 启动渲染线程
        // 💡 为什么调用：线程start()后才会进入run()方法执行初始化
        // 💡 作用：线程开始运行，在run()中初始化Looper、注册Choreographer回调
        // 💡 注意：start()后线程异步运行，Handler需要稍后才能使用
        // 💡 时机：线程对象创建后立即启动
        mRenderThread.start();                   // ▶️ 启动线程
    }

    /**
     * ⏸️ Activity暂停时调用
     * 💡 作用：发送退出消息通知渲染线程停止
     * 💡 时机：Activity失去焦点进入后台时由系统调用
     * 💡 注意：handler可能为null的情况暂不处理
     */
    // ⏸️ Activity 暂停时通知线程退出（共31行，需逐行注释）
    // 🔧 为什么：发送退出消息通知渲染线程停止，释放线程资源
    // 📍 时机：Activity失去焦点进入后台时由系统调用
    // 💡 注意：handler可能为null的情况暂不处理
    @Override
    protected void onPause() {
        // 📝 TAG变量：日志过滤标签，值为"chor-test"
        // 🔍 为什么记录：标记Activity生命周期状态变化，方便在logcat中过滤查看
        // 📍 作用：在logcat中通过"chor-test"标签快速定位本Activity的日志
        // ⏰ 时机：方法入口处最先调用
        Log.d(TAG, "onPause");

        // 🔄 super.onPause(): 调用父类的onPause方法
        // 🔍 为什么调用：必须执行标准的Activity暂停流程
        // 📍 作用：通知系统本Activity即将进入后台，保存临时状态等
        // ⏰ 时机：必须在执行自定义暂停逻辑之前调用
        super.onPause();

        // if we get here too quickly, the handler might still be null; not dealing with that
        // ⚠️ 如果太快到达这里，handler 可能还是 null（暂不处理此情况）

        // 📨 handler变量：渲染线程的Handler引用（Handler类型）
        // 🔍 为什么获取：需要通过消息机制通知渲染线程的Looper退出循环
        // 📍 作用：持有渲染线程的Handler，用于发送退出消息
        // ⏰ 时机：onPause时获取，用于发送退出指令
        // 💡 注意：handler由渲染线程在run()中创建，声明为volatile确保可见性
        Handler handler = mRenderThread.getHandler();

        // 📤 handler.sendEmptyMessage(0): 发送空消息触发线程退出
        // 🔍 为什么发送msg.what=0：消息到达后触发Handler.handleMessage()中的Looper.quit()调用
        // 📍 作用：消息到达后，渲染线程的Handler收到消息并调用Looper.quit()退出循环
        // 📌 机制说明：msg.what=0，Handler收到后不检查具体内容直接退出Looper
        // ⏰ 时机：获取Handler后立即发送
        handler.sendEmptyMessage(0);  // 📨 发送退出消息

        // 🔚 mRenderThread = null: 清空渲染线程引用
        // 🔍 为什么置空：解除Activity对已退出线程的引用，便于GC回收线程对象
        // 📍 作用：允许垃圾回收器回收线程对象和相关资源（Handler、Looper等）
        // ⏰ 时机：发送退出消息后立即清空引用
        // 💡 注意：线程不会立即结束（需要Looper退出循环），但可以被GC回收
        mRenderThread = null;
    }

    /**
     * 🧵 Choreographer 渲染线程，实现 FrameCallback 接口接收每帧回调。
     */
    private static class ChorRenderThread extends Thread implements Choreographer.FrameCallback {
        private volatile Handler mHandler;  // 📨 线程 Handler

        /**
         * 🚀 线程入口：创建 Looper/Handler，注册 Choreographer 回调。
         * 💡 作用：初始化消息循环，注册帧回调，持续接收VSYNC信号
         * 💡 时机：线程start()后自动调用，运行在独立的工作线程中
         */
        @Override
        public void run() {
            // 🏷️ setName(): 设置线程名称
            // 💡 为什么设置：方便调试和性能分析工具识别此线程
            // 💡 作用：在日志和Android Studio Profiler中显示"ChorRenderThread"
            // 💡 时机：线程run()方法入口处最先调用
            setName("ChorRenderThread");  // 🏷️ 设置线程名

            // 🔄 Looper.prepare(): 初始化Looper
            // 💡 为什么调用：为当前线程创建消息队列，使线程能够通过Handler接收消息
            // 💡 作用：初始化线程本地的Looper，创建消息队列
            // 💡 时机：Handler创建之前必须调用
            // 💡 注意：每个线程只能调用一次prepare()
            Looper.prepare();

            // 📨 mHandler: 线程的消息处理器
            // 💡 为什么创建：需要接收来自UI线程的退出消息
            // 💡 作用：接收退出消息并调用Looper.quit()结束消息循环
            // 💡 时机：Looper.prepare()之后创建
            // 💡 volatile修饰：确保多线程间可见性
            mHandler = new Handler() {
                public void handleMessage(Message msg) {
                    // 📝 Log.d(): 记录收到退出消息
                    // 💡 为什么记录：确认退出消息已到达
                    Log.d(TAG, "got message, quitting");  // 📨 收到消息，准备退出
                    // 🚪 Looper.myLooper().quit(): 退出Looper消息循环
                    // 💡 为什么调用：收到退出消息后需要结束消息循环
                    // 💡 作用：使Looper.loop()返回，线程run()继续执行后续清理代码
                    // 💡 时机：收到msg.what=0的退出消息时调用
                    Looper.myLooper().quit();
                }
            };
            // 📋 Choreographer.getInstance().postFrameCallback(): 注册帧回调
            // 💡 为什么注册：需要在每帧VSYNC信号到来时调用doFrame()记录帧时间戳
            // 💡 作用：向Choreographer注册帧回调，VSYNC到来时调用doFrame()
            // 💡 this: 当前线程实现了FrameCallback接口
            // 💡 时机：Handler创建后、Looper.loop()之前注册
            Choreographer.getInstance().postFrameCallback(this);  // 📋 注册帧回调

            // 🔄 Looper.loop(): 开始消息循环
            // 💡 为什么调用：启动消息循环，阻塞直到Looper.quit()被调用
            // 💡 作用：保持线程活跃，持续处理消息和帧回调
            // 💡 时机：Handler创建和帧回调注册完成后调用
            // 💡 注意：这是阻塞调用，会持续运行直到Looper.quit()
            Looper.loop();                   // 🔄 开始消息循环
            // 📝 Log.d(): 记录Looper退出
            // 💡 为什么记录：确认消息循环已正常退出
            // 💡 时机：Looper.loop()返回后记录
            Log.d(TAG, "looper quit");        // 🚪 Looper 退出
            // 🗑️ Choreographer.getInstance().removeFrameCallback(): 移除帧回调
            // 💡 为什么移除：线程退出后不再需要接收帧回调
            // 💡 作用：清理资源，避免在已退出的线程上接收回调
            // 💡 时机：Looper退出后立即移除
            Choreographer.getInstance().removeFrameCallback(this);  // 🗑️ 移除帧回调
        }

        // 📨 获取线程 Handler
        public Handler getHandler() {
            return mHandler;
        }

        /**
         * 🖼️ 每帧回调：记录帧时间戳，并重新注册下一帧回调。
         */
        @Override
        public void doFrame(long frameTimeNanos) {
            Log.d(TAG, "doFrame " + frameTimeNanos);
            Choreographer.getInstance().postFrameCallback(this);  // 🔄 重新注册回调
        }
    }
}
