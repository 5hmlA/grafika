/*
 * Copyright 2013 Google Inc. All rights reserved.
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

import android.opengl.GLES20;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Resources;

import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.OffscreenSurface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import com.google.grafika.R;


/**
 * ⚡ 基础的 glReadPixels() 速度测试。
 * Basic glReadPixels() speed test.
 */
public class ReadPixelsActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    private static final int WIDTH = 1280;      // 📐 测试用宽度
    private static final int HEIGHT = 720;      // 📐 测试用高度
    private static final int ITERATIONS = 100;  // 🔄 迭代次数

    private volatile boolean mIsCanceled;  // 🔀 测试取消标志（多线程可见）


    /**
     * 🚀 Activity 创建时初始化布局（共4行，刚好未超过5行，但仍补充注释以保持一致性）。
     * 🔧 为什么：Activity生命周期入口，初始化UI布局
     * 📍 时机：系统首次创建Activity时自动调用
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // 📞 调用父类onCreate，完成系统级初始化
        setContentView(R.layout.activity_read_pixels); // 📞 设置Activity内容视图，加载glReadPixels测试布局
    }

    /**
     * Sets the text in the message field.
     * 📝 设置指定 TextView 的文本内容（共4行）。
     * 🔧 为什么：统一管理UI文本更新，避免重复代码
     * 📍 时机：测试开始/完成/取消时调用
     */
    void setMessage(int id, String msg) { // 🏷️ id：TextView资源ID；msg：要显示的文本
        // 🖼️ result：目标TextView控件
        // 📌 为什么：需要通过ID找到要更新的TextView
        // 💡 作用：持有TextView引用，用于设置文本
        // ⏰ 使用时机：立即使用setText
        TextView result = (TextView) findViewById(id); // 📞 通过资源ID查找TextView
        result.setText(msg); // 📝 设置文本内容（运行中/结果/未完成等）
    }

    /**
     * Creates and displays the progress dialog.
     * ⏳ 创建并显示进度对话框（共18行，需逐行注释）。
     *
     * @return the dialog 返回创建的对话框
     */
    private AlertDialog showProgressDialog() {
        // Put up the progress dialog.
        // 📋 创建进度对话框构建器
        AlertDialog.Builder builder = WorkDialog.create(this, R.string.running_test);
        // 🚫 设置不可取消：只能通过按钮关闭对话框
        // 📌 为什么：防止用户在测试进行中意外关闭对话框导致状态混乱
        // 💡 作用：强制用户通过"取消"按钮来明确终止测试
        // ⏰ 使用时机：构建对话框时设置，影响整个对话框的交互行为
        builder.setCancelable(false);   // only by button ⚠️ 只能通过按钮关闭
        // 🔘 设置取消按钮：点击时设置取消标志
        builder.setNegativeButton(R.string.cancel,
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mIsCanceled = true;  // ❌ 设置取消标志，通知后台线程停止测试
                                     // 📌 为什么：AsyncTask后台线程需要检查此标志来决定是否终止
                                     // 💡 作用：实现用户取消功能，避免强制杀进程
                                     // ⏰ 使用时机：用户点击"取消"按钮时立即设置
                // let the async task handle dismiss the dialog
                // 让异步任务来处理对话框的关闭
            }
        });
        return builder.show(); // 📞 构建并显示对话框，返回AlertDialog引用

    }

    /**
     * onClick handler for gfx test button.
     * 🎯 "运行图形测试"按钮的点击处理（共11行，需逐行注释）。
     * 🔧 为什么：用户点击按钮后需要启动异步测试任务
     * 📍 时机：用户点击"Run Gfx Test"按钮时由Android系统调用
     */
    public void clickRunGfxTest(@SuppressWarnings("unused") View unused) {
        // 📦 res：Resources对象，用于获取字符串资源
        // 📌 为什么：需要从strings.xml获取"运行中"的本地化文本
        // 💡 作用：支持多语言，显示测试状态
        // ⏰ 使用时机：立即使用，设置结果文本
        Resources res = getResources(); // 📞 获取应用资源管理器
        // 📝 running：当前状态文本，表示测试正在运行
        // 📌 为什么：需要在UI上显示测试状态，让用户知道测试已启动
        // 💡 作用：提供视觉反馈，替代之前的测试结果
        // ⏰ 使用时机：立即设置到TextView中
        String running = res.getString(R.string.state_running); // 📞 获取"运行中"字符串
        setMessage(R.id.gfxResult_text, running); // 📝 设置结果文本为"运行中"

        // 💬 dialog：进度对话框，显示测试进度和取消按钮
        // 📌 为什么：测试可能耗时较长，需要显示进度并允许用户取消
        // 💡 作用：提供进度可视化和用户中断能力
        // ⏰ 使用时机：创建后传给AsyncTask，测试完成后由AsyncTask关闭
        AlertDialog dialog = showProgressDialog(); // 📞 创建并显示进度对话框
        // 🧵 task：执行glReadPixels测试的异步任务
        // 📌 为什么：测试需要在后台线程执行，避免阻塞UI线程
        // 💡 作用：封装测试逻辑，自动管理线程切换和进度更新
        // ⏰ 使用时机：创建后立即调用execute()启动
        ReadPixelsTask task = new ReadPixelsTask(dialog, R.id.gfxResult_text,
                WIDTH, HEIGHT, ITERATIONS); // 📞 创建测试任务实例
        mIsCanceled = false; // 🔄 重置取消标志，确保新测试可以正常运行
        task.execute(); // ▶️ 启动异步任务执行测试
    }

    /**
     * AsyncTask class that executes the test.
     * 🧵 执行 glReadPixels 测试的异步任务类。
     */
    private class ReadPixelsTask extends AsyncTask<Void, Integer, Long> {
        private int mWidth;          // 📐 测试宽度
        private int mHeight;         // 📐 测试高度
        private int mIterations;     // 🔄 迭代次数
        private int mResultTextId;   // 📝 结果文本的 View ID
        private AlertDialog mDialog; // 💬 进度对话框

        private ProgressBar mProgressBar; // 📊 进度条

        /**
         * Prepare for the glReadPixels test.
         * 🔧 准备 glReadPixels 测试，初始化参数和进度条（共12行，需逐行注释）。
         */
        public ReadPixelsTask(AlertDialog dialog, int resultTextId,
                int width, int height, int iterations) {
            // 💬 mDialog：进度对话框引用，用于测试完成后关闭
            // 📌 为什么：需要持有对话框引用，以便在onPostExecute中dismiss
            // 💡 作用：管理对话框生命周期
            // ⏰ 使用时机：测试完成或取消时在onPostExecute中关闭
            mDialog = dialog;
            // 🏷️ mResultTextId：结果TextView的资源ID
            // 📌 为什么：需要知道更新哪个TextView来显示测试结果
            // 💡 作用：将测试结果文本设置到正确的UI控件
            // ⏰ 使用时机：在onPostExecute中使用setMessage更新显示
            mResultTextId = resultTextId;
            // 📐 mWidth：测试用的渲染缓冲区宽度
            // 📌 为什么：glReadPixels需要指定读取区域的尺寸
            // 💡 作用：定义测试渲染区域大小，影响像素读取量
            // ⏰ 使用时机：在doInBackground中创建OffscreenSurface和分配缓冲区时使用
            mWidth = width;
            // 📐 mHeight：测试用的渲染缓冲区高度
            // 📌 为什么：与mWidth配合定义完整的渲染区域
            // 💡 作用：定义测试渲染区域高度
            // ⏰ 使用时机：与mWidth同时使用
            mHeight = height;
            // 🔄 mIterations：测试循环迭代次数
            // 📌 为什么：更多迭代次数能获得更准确的平均性能数据
            // 💡 作用：控制测试运行的次数，影响测试总时长和结果精度
            // ⏰ 使用时机：在doInBackground的循环中控制测试轮数
            mIterations = iterations;

            // 📊 mProgressBar：进度条控件，用于显示测试进度
            // 📌 为什么：长时间运行的测试需要给用户视觉反馈
            // 💡 作用：显示当前完成了多少次迭代
            // ⏰ 使用时机：在onProgressUpdate中更新进度
            mProgressBar = (ProgressBar) dialog.findViewById(R.id.work_progress);
            mProgressBar.setMax(mIterations); // 📐 设置进度条最大值为迭代次数
        }

        /**
         * ⚙️ 后台线程中执行测试（共28行，需逐行注释）。
         *    创建 EGL 上下文并运行图形测试。
         */
        @Override
        protected Long doInBackground(Void... params) {
            // 📊 result：测试结果（总耗时纳秒），-1表示失败
            // 📌 为什么：需要保存测试结果并返回给onPostExecute
            // 💡 作用：-1表示异常失败，正数表示glReadPixels总耗时
            // ⏰ 使用时机：方法结束时返回，被onPostExecute接收
            long result = -1;
            // 🖥️ eglCore：EGL上下文管理器，用于创建OpenGL ES环境
            // 📌 为什么：glReadPixels需要有效的GL上下文才能执行
            // 💡 作用：提供OpenGL ES渲染所需的底层环境
            // ⏰ 使用时机：在try块中创建OffscreenSurface时使用
            EglCore eglCore = null;
            // 🪟 surface：离屏渲染表面，无需可见窗口即可渲染
            // 📌 为什么：测试不需要显示在屏幕上，离屏渲染更高效
            // 💡 作用：提供GPU渲染目标，glReadPixels从中读取像素
            // ⏰ 使用时机：传给runGfxTest执行测试
            OffscreenSurface surface = null;

            // TODO: this should not use AsyncTask.  The AsyncTask worker thread is run at
            // a lower priority, making it unsuitable for benchmarks.  We can counteract
            // it in the current implementation, but this is not guaranteed to work in
            // future releases.
            // ⚠️ 注意：AsyncTask 的工作线程优先级较低，不适合做基准测试。
            // 当前实现中可以抵消这个问题，但未来版本可能不适用。
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND); // ⬆️ 提升线程优先级到前台级别

            try {
                eglCore = new EglCore(null, 0); // 📞 创建EGL上下文（无共享上下文，无特殊标志）
                surface = new OffscreenSurface(eglCore, mWidth, mHeight); // 📞 创建指定尺寸的离屏渲染表面
                Log.d(TAG, "Buffer size " + mWidth + "x" + mHeight); // 📝 记录缓冲区尺寸
                result = runGfxTest(surface); // 🧪 执行图形测试，返回glReadPixels总耗时
            } finally {
                if (surface != null) {
                    surface.release(); // 🧹 释放离屏渲染表面资源
                }
                if (eglCore != null) {
                    eglCore.release(); // 🧹 释放EGL上下文资源
                }
            }
            return result < 0 ? result : result / mIterations; // 📊 返回平均每次迭代的耗时（或错误码）
        }

        // 📊 更新进度条
        @Override
        protected void onProgressUpdate(Integer... progress) {
            mProgressBar.setProgress(progress[0]);
        }

        /**
         * ✅ 测试完成后更新结果文本，关闭对话框（共14行，需逐行注释）。
         * 🔧 为什么：需要在UI线程中更新界面，显示测试结果
         * 📍 时机：doInBackground返回后由AsyncTask框架自动在UI线程调用
         */
        @Override
        protected void onPostExecute(Long result) {
            Log.d(TAG, "onPostExecute result=" + result); // 📝 记录测试结果（平均每次纳秒数）
            mDialog.dismiss(); // 🚫 关闭进度对话框
            mDialog = null; // 🧹 释放对话框引用，避免内存泄漏

            // 📦 res：Resources对象，用于获取本地化字符串
            // 📌 为什么：结果文本需要支持多语言
            // 💡 作用：获取"未完成"和"微秒/次"等本地化文本
            // ⏰ 使用时机：立即使用，格式化结果文本
            Resources res = getResources();
            if (result < 0) { // ❌ 测试未完成（取消或异常）
                setMessage(mResultTextId, res.getString(R.string.did_not_complete)); // 📝 显示"未完成"
            } else { // ✅ 测试成功完成
                setMessage(mResultTextId, (result / 1000) +
                        res.getString(R.string.usec_per_iteration)); // 📝 显示"XXX微秒/次"
            }
        }

        /**
         * Does a simple bit of rendering and then reads the pixels back.
         * 🧪 执行简单的渲染操作，然后读取像素数据（共68行，需逐行注释）。
         *    循环迭代多次，每次改变颜色并测量 glReadPixels 的耗时。
         * 🔧 为什么：测试glReadPixels的性能，评估GPU到CPU数据传输速度
         * 📍 时机：doInBackground后台线程中调用
         *
         * @return total time spent on glReadPixels() glReadPixels 的总耗时（纳秒）
         */
        private long runGfxTest(OffscreenSurface eglSurface) { // 🖥️ eglSurface：离屏EGL渲染表面
            // ⏱️ totalTime：所有glReadPixels调用的累计耗时
            // 🔍 为什么：最终除以迭代次数得到单次平均耗时
            long totalTime = 0; // 📊 初始化总耗时为0

            eglSurface.makeCurrent(); // 🔧 将此离屏Surface设为当前GL上下文

            // 📦 pixelBuf：用于接收glReadPixels读取的像素数据的直接缓冲区
            // 🔍 为什么：glReadPixels需要DirectBuffer，不能用普通Java数组
            // 🔧 大小：宽*高*4字节（RGBA格式，每像素4字节）
            ByteBuffer pixelBuf = ByteBuffer.allocateDirect(mWidth * mHeight * 4); // 📞 分配直接内存缓冲区
            pixelBuf.order(ByteOrder.LITTLE_ENDIAN); // 📐 设置字节序为小端序（Android设备通常为小端）

            Log.d(TAG, "Running..."); // 📝 记录测试开始
            // 📊 colorMult：颜色变化系数，用于每次迭代改变清屏颜色
            // 🔍 为什么：每次使用不同颜色避免GPU缓存优化影响测试结果
            float colorMult = 1.0f / mIterations; // 🧮 系数 = 1/迭代次数，使颜色从0渐变到1
            for (int i = 0; i < mIterations; i++) { // 🔄 i：当前迭代次数
                if (mIsCanceled) { // 🔍 检查用户是否点击了取消按钮
                    Log.d(TAG, "Canceled!"); // 📝 记录取消
                    totalTime = -2;  // ❌ 取消标记，返回负值表示未完成
                    break; // 🛑 跳出循环
                }
                if ((i % (mIterations / 8)) == 0) { // 📊 每完成1/8的迭代更新一次进度
                    publishProgress(i);  // 📊 通知UI线程更新进度条
                }

                // Clear the screen to a solid color, then add a rectangle.  Change the color
                // each time.
                // 🎨 清屏为纯色，然后添加一个矩形。每次改变颜色。
                // 📐 r, g, b：本次迭代的RGB颜色分量
                float r = i * colorMult;     // 🔴 R分量：从0线性增长到1
                float g = 1.0f - r;          // 🟢 G分量：从1线性减小到0（与R互补）
                float b = (r + g) / 2.0f;    // 🔵 B分量：R和G的平均值
                GLES20.glClearColor(r, g, b, 1.0f); // 🎨 设置清屏颜色（不透明）
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); // 🧹 用设定颜色清除整个屏幕

                GLES20.glEnable(GLES20.GL_SCISSOR_TEST); // ✂️ 启用裁剪测试
                GLES20.glScissor(mWidth / 4, mHeight / 4, mWidth / 2, mHeight / 2);  // ✂️ 设置裁剪区域为屏幕中央1/4面积
                GLES20.glClearColor(b, g, r, 1.0f); // 🎨 设置裁剪区域的清屏颜色（RGB分量反转）
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); // 🧹 清除裁剪区域
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST); // ❌ 关闭裁剪测试

                // Try to ensure that rendering has finished.
                // ⏳ 确保渲染完成
                // 🔍 为什么：glFinish会阻塞直到所有GL命令执行完毕，确保后续glReadPixels读到完整画面
                GLES20.glFinish(); // ⏸️ 同步等待GPU完成所有渲染命令
                GLES20.glReadPixels(0, 0, 1, 1,  // 📖 先读1x1像素预热，避免首次读取包含额外开销
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf); // 🎨 RGBA格式，无符号字节类型

                // Time individual extraction.  Ideally we'd be timing a bunch of these calls
                // and measuring the aggregate time, but we want the isolated time, and if we
                // just read the same buffer repeatedly we might get some sort of cache effect.
                // ⏱️ 测量单次读取耗时。理想情况下应测量多次调用的总时间，
                //    但这里需要隔离的单次时间，且重复读同一 buffer 可能有缓存效应。
                // ⏱️ startWhen：记录glReadPixels开始时间
                long startWhen = System.nanoTime(); // 📊 获取开始时间戳（纳秒精度）
                GLES20.glReadPixels(0, 0, mWidth, mHeight, // 📖 读取整个屏幕的像素数据
                        GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixelBuf); // 🎨 从GPU读取RGBA像素到pixelBuf
                totalTime += System.nanoTime() - startWhen; // ⏱️ 累加本次glReadPixels耗时
            }
            Log.d(TAG, "done"); // 📝 记录测试循环结束

            if (true) { // 🔧 始终为true，保留最后一帧用于调试
                // save the last one off into a file
                // 💾 将最后一帧保存到文件
                // ⏱️ startWhen：保存帧开始时间
                long startWhen = System.nanoTime(); // 📊 记录保存开始时间
                try {
                    eglSurface.saveFrame(new File(Environment.getExternalStorageDirectory(), // 📂 获取外部存储根目录
                            "test.png")); // 📁 保存为test.png文件
                } catch (IOException ioe) { // ❌ 文件写入失败
                    throw new RuntimeException(ioe); // 💥 包装为运行时异常抛出
                }
                Log.d(TAG, "Saved frame in " + ((System.nanoTime() - startWhen) / 1000000) + "ms"); // 📝 记录保存耗时（毫秒）
            }

            return totalTime; // ✅ 返回glReadPixels总耗时（纳秒）
        } // ✅ runGfxTest结束
    }
}
