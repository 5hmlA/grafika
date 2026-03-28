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
import android.graphics.Bitmap;

import com.android.grafika.gles.Drawable2d;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.GlUtil;
import com.android.grafika.gles.OffscreenSurface;
import com.android.grafika.gles.Sprite2d;
import com.android.grafika.gles.Texture2dProgram;
import com.google.grafika.R;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 🖼️ 纹理上传速度的非科学测试。
 * An unscientific test of texture upload speed.
 */
public class TextureUploadActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    // Texture width/height. 纹理宽度/高度
    private static final int WIDTH = 512;       // must be power of 2 ⚠️ 必须是2的幂次
    private static final int HEIGHT = 512;      // 📐 纹理高度
    private static final int ITERATIONS = 10;   // 10 iterations... 🔄 10次迭代...
    private static final int TEX_PER_ITER = 8;  // ...uploading 8 textures per iteration 📤 每次迭代上传8个纹理

    private volatile boolean mIsCanceled;  // 🔀 测试取消标志（多线程可见）

    /**
     * 🚀 Activity 创建时初始化布局（共4行）。
     * 🔧 为什么：Activity生命周期入口，初始化UI
     * 📍 时机：系统首次创建Activity时自动调用
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState); // 📞 调用父类onCreate，完成系统级初始化
        setContentView(R.layout.activity_texture_upload); // 📞 设置Activity内容视图，加载纹理上传测试布局
    }

    /**
     * Sets the text in the message field.
     * 📝 设置结果文本内容（共4行）。
     * 🔧 为什么：统一管理UI文本更新
     * 📍 时机：测试开始/完成/取消时调用
     */
    void setMessage(String msg) { // 📝 msg：要显示的文本内容
        // 🖼️ result：结果TextView控件
        // 📌 为什么：需要找到指定的TextView来更新测试状态
        // 💡 作用：持有TextView引用用于显示测试结果
        // ⏰ 使用时机：立即调用setText
        TextView result = (TextView) findViewById(R.id.textureResult_text); // 📞 通过ID查找结果TextView
        result.setText(msg); // 📝 设置文本内容
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
        // 🚫 设置不可取消：只能通过按钮关闭
        // 📌 为什么：防止用户在测试进行中意外关闭对话框
        // 💡 作用：强制用户通过"取消"按钮明确终止测试
        // ⏰ 使用时机：构建对话框时设置
        builder.setCancelable(false);   // only by button ⚠️ 只能通过按钮关闭
        // 🔘 设置取消按钮：点击时设置取消标志
        builder.setNegativeButton(R.string.cancel,
                new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                mIsCanceled = true;  // ❌ 设置取消标志，通知后台线程停止
                                     // 📌 为什么：后台线程会检查此标志决定是否终止
                                     // 💡 作用：实现用户中断测试的功能
                                     // ⏰ 使用时机：用户点击取消按钮时立即设置
                // let the async task handle dismiss the dialog
                // 让异步任务来处理对话框的关闭
            }
        });
        return builder.show(); // 📞 构建并显示对话框

    }

    /**
     * 🎯 "运行测试"按钮的点击处理（共10行，需逐行注释）。
     * 🔧 为什么：用户点击后需要启动纹理上传性能测试
     * 📍 时机：用户点击"Run Test"按钮时由系统调用
     */
    public void clickRunTest(@SuppressWarnings("unused") View unused) {
        // 📦 res：Resources对象，用于获取字符串资源
        // 📌 为什么：需要显示本地化的"运行中"状态文本
        // 💡 作用：支持多语言显示
        // ⏰ 使用时机：立即获取状态文本
        Resources res = getResources();
        // 📝 running："运行中"状态文本
        // 📌 为什么：需要告知用户测试已启动
        // 💡 作用：视觉反馈，替换之前的测试结果
        // ⏰ 使用时机：立即设置到结果TextView
        String running = res.getString(R.string.state_running);
        setMessage(running); // 📝 更新结果文本为"运行中"

        // 💬 dialog：进度对话框引用
        // 📌 为什么：需要显示测试进度并提供取消功能
        // 💡 作用：进度可视化+用户中断能力
        // ⏰ 使用时机：传给AsyncTask，测试完成后关闭
        AlertDialog dialog = showProgressDialog(); // 📞 创建进度对话框
        // 🧵 task：纹理上传测试任务
        // 📌 为什么：测试需要在后台执行，避免阻塞UI
        // 💡 作用：封装测试逻辑
        // ⏰ 使用时机：创建后立即execute启动
        TextureUploadTask task = new TextureUploadTask(dialog, WIDTH, HEIGHT, ITERATIONS); // 📞 创建测试任务
        mIsCanceled = false; // 🔄 重置取消标志
        task.execute(); // ▶️ 启动异步测试
    }


    /**
     * AsyncTask class that executes the test.
     * 🧵 执行纹理上传测试的异步任务类。
     */
    private class TextureUploadTask extends AsyncTask<Void, Integer, Long> {
        private static final int OUTPUT_WIDTH = 256;   // 📐 输出宽度
        private static final int OUTPUT_HEIGHT = 256;  // 📐 输出高度
        private static final int RGBA_BPP = 4;         // RGBA bytes-per-pixel 🎨 RGBA 每像素字节数

        private int mWidth;           // 📐 纹理宽度
        private int mHeight;          // 📐 纹理高度
        private int mIterations;      // 🔄 迭代次数
        private int mResultTextId;    // 📝 结果文本的 View ID
        private AlertDialog mDialog;  // 💬 进度对话框

        private ByteBuffer[] mPixelSource;  // 📦 像素数据源数组

        private ProgressBar mProgressBar;   // 📊 进度条

        /**
         * Prepare for the glTexImage2d test.
         * 🔧 准备纹理上传测试，初始化参数和进度条（共10行，需逐行注释）。
         */
        public TextureUploadTask(AlertDialog dialog, int width, int height, int iterations) {
            // 💬 mDialog：进度对话框引用
            // 📌 为什么：需要在onPostExecute中关闭对话框
            // 💡 作用：管理对话框生命周期
            // ⏰ 使用时机：测试完成后dismiss
            mDialog = dialog;
            // 📐 mWidth：纹理宽度（512）
            // 📌 为什么：glTexImage2D需要知道上传纹理的尺寸
            // 💡 作用：定义像素数据源和纹理的宽度
            // ⏰ 使用时机：在createPixelSources和runTextureTest中使用
            mWidth = width;
            // 📐 mHeight：纹理高度（512）
            // 📌 为什么：与mWidth配合定义纹理尺寸
            // 💡 作用：定义像素数据源和纹理的高度
            // ⏰ 使用时机：与mWidth同时使用
            mHeight = height;
            // 🔄 mIterations：测试迭代次数（10）
            // 📌 为什么：多次迭代可获得更准确的平均值
            // 💡 作用：控制测试循环次数
            // ⏰ 使用时机：在doInBackground循环中使用
            mIterations = iterations;

            // 📊 mProgressBar：进度条控件
            // 📌 为什么：长时间测试需要给用户进度反馈
            // 💡 作用：显示当前完成的迭代数
            // ⏰ 使用时机：在onProgressUpdate中更新
            mProgressBar = (ProgressBar) dialog.findViewById(R.id.work_progress);
            mProgressBar.setMax(mIterations); // 📐 设置进度条最大值
        }

        /**
         * ⚙️ 后台线程中执行测试（共30行，需逐行注释）。
         *    创建像素数据源，然后运行纹理上传测试。
         */
        @Override
        protected Long doInBackground(Void... params) {
            // 📊 result：测试结果（总耗时纳秒），-1表示失败
            // 📌 为什么：需要保存测试结果
            // 💡 作用：-1表示失败，正数为纹理上传总耗时
            // ⏰ 使用时机：方法结束时返回给onPostExecute
            long result = -1;

            // TODO: this should not use AsyncTask.  The AsyncTask worker thread is run at
            // a lower priority, making it unsuitable for benchmarks.  We can counteract
            // it in the current implementation, but this is not guaranteed to work in
            // future releases.
            // ⚠️ 注意：AsyncTask 的工作线程优先级较低，不适合做基准测试。
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND); // ⬆️ 提升线程优先级

            // This can take a second or two.
            // ⏳ 创建像素数据源可能需要 1-2 秒
            createPixelSources(); // 📦 创建8个纹理的像素数据

            // 🖥️ eglCore：EGL上下文管理器
            // 📌 为什么：纹理上传需要有效的GL上下文
            // 💡 作用：提供OpenGL ES渲染环境
            // ⏰ 使用时机：创建OffscreenSurface时使用
            EglCore eglCore = null;
            // 🪟 surface：离屏渲染表面
            // 📌 为什么：无需显示窗口即可进行纹理测试
            // 💡 作用：提供渲染目标
            // ⏰ 使用时机：传给runTextureTest
            OffscreenSurface surface = null;
            try {
                eglCore = new EglCore(null, 0); // 📞 创建EGL上下文
                surface = new OffscreenSurface(eglCore, OUTPUT_WIDTH, OUTPUT_HEIGHT); // 📞 创建256x256离屏表面
                result = runTextureTest(surface); // 🧪 执行纹理上传测试
            } finally {
                if (surface != null) {
                    surface.release(); // 🧹 释放渲染表面
                }
                if (eglCore != null) {
                    eglCore.release(); // 🧹 释放EGL上下文
                }
            }
            return result < 0 ? result : result / (mIterations * TEX_PER_ITER); // 📊 返回平均每次上传的耗时
        }

        // 📊 更新进度条
        @Override
        protected void onProgressUpdate(Integer... progress) {
            mProgressBar.setProgress(progress[0]);
        }

        /**
         * ✅ 测试完成后更新结果文本，关闭对话框（共13行，需逐行注释）。
         * 🔧 为什么：需要在UI线程更新界面显示测试结果
         * 📍 时机：doInBackground返回后由AsyncTask自动在UI线程调用
         */
        @Override
        protected void onPostExecute(Long result) {
            Log.d(TAG, "onPostExecute result=" + result); // 📝 记录测试结果
            mDialog.dismiss(); // 🚫 关闭进度对话框
            mDialog = null; // 🧹 释放对话框引用，允许GC回收

            // 📦 res：Resources对象
            // 📌 为什么：需要获取本地化的结果文本
            // 💡 作用：支持多语言显示
            // ⏰ 使用时机：立即格式化结果
            Resources res = getResources();
            if (result < 0) { // ❌ 测试未完成
                setMessage(res.getString(R.string.did_not_complete)); // 📝 显示"未完成"
            } else { // ✅ 测试成功
                setMessage((result / 1000) + res.getString(R.string.usec_per_iteration)); // 📝 显示"XXX微秒/次"
            }
        }

        /**
         * Create the bitmaps we create the textures from.
         * 🖼️ 创建用于生成纹理的像素数据源（共13行，需逐行注释）。
         *    前 4 个使用规则图案，后 4 个使用随机数据。
         * 🔧 为什么：需要预生成像素数据，避免测试时包含数据生成时间
         * 📍 时机：在doInBackground中最先调用，可能耗时1-2秒
         */
        private void createPixelSources() {
            Log.d(TAG, "Creating pixel data..."); // 📝 记录开始创建像素数据
            // 📦 mPixelSource：8个ByteBuffer数组，存储8个纹理的像素数据
            // 📌 为什么：需要为每次迭代准备不同的纹理数据源
            // 💡 作用：每个元素是一个RGBA格式的像素缓冲区
            // ⏰ 使用时机：在runTextureTest中每次迭代上传纹理时使用
            mPixelSource = new ByteBuffer[TEX_PER_ITER]; // 📞 创建8个元素的数组
            for (int i = 0; i < TEX_PER_ITER; i++) { // 🔄 i：当前纹理索引（0~7）
                // 📦 mPixelSource[i]：第i个纹理的像素数据缓冲区
                // 📌 为什么：glTexImage2D需要直接缓冲区作为像素数据源
                // 💡 作用：存储宽*高*4字节的RGBA像素数据
                // ⏰ 使用时机：在runTextureTest中传给GlUtil.createImageTexture
                mPixelSource[i] = ByteBuffer.allocateDirect(mWidth * mHeight * RGBA_BPP); // 📞 分配直接内存
                if (i < 4) { // 🎨 前4个使用规则图案（压缩效果好）
                    patternPixelSource(mPixelSource[i], i); // 📞 用规则图案填充
                } else { // 🎲 后4个使用随机数据（不可压缩）
                    randomPixelSource(mPixelSource[i], i); // 📞 用随机数据填充
                }
            }
            Log.d(TAG, "done"); // 📝 记录创建完成
        }

        /**
         * Fill the buffer with a regular pattern.  This should compress well.
         * 🎨 用规则图案填充缓冲区（压缩效果好，共33行，需逐行注释）。
         *    生成 4 种随机颜色，按重复模式填充像素。
         * 🔧 为什么：测试可压缩纹理的上传性能
         * 📍 时机：在createPixelSources中对前4个纹理调用
         */
        private void patternPixelSource(ByteBuffer buf, int index) {
            // 📦 array：ByteBuffer底层的byte数组
            // 📌 为什么：直接操作数组比ByteBuffer API更快
            // 💡 作用：用于逐像素写入RGBA数据
            // ⏰ 使用时机：在下面的嵌套循环中逐像素赋值
            byte[] array = buf.array();     // works in recent Android

            // generate 4 random RGBA colors
            // 🎨 colors：4种随机颜色，每种4字节(RGBA)
            // 📌 为什么：需要4种不同颜色来创建变化的图案
            // 💡 作用：每行使用不同颜色，形成条纹效果
            // ⏰ 使用时机：在下面的像素填充循环中引用
            byte[][] colors = new byte[4][4];
            for (int i = 0; i < 4; i++) { // 🔄 i：颜色索引
                colors[i][0] = (byte) (256 * Math.random() - 128); // 🔴 R分量：随机值
                colors[i][1] = (byte) (256 * Math.random() - 128); // 🟢 G分量：随机值
                colors[i][2] = (byte) (256 * Math.random() - 128); // 🔵 B分量：随机值
                colors[i][3] = (byte) 255; //(byte) (256 * Math.random() - 128);
                                            // 🔲 A分量：固定255（不透明）
            }

            // 🔁 repCount：颜色重复次数（1~4），随index变化
            // 📌 为什么：不同重复次数产生不同压缩率的图案
            // 💡 作用：控制每种颜色连续出现的像素数
            // ⏰ 使用时机：在内层循环中控制重复
            final int repCount = (index % 4) + 1;
            // 📍 off：当前写入位置的字节偏移量
            // 📌 为什么：需要追踪在array中的写入位置
            // 💡 作用：每次写入4字节(RGBA)后递增
            // ⏰ 使用时机：每次写入像素时使用并递增
            int off = 0;
            for (int y = 0; y < mHeight; y++) { // 🔄 y：当前行号
                // 🎨 colIndex：当前行使用的起始颜色索引
                // 📌 为什么：每行使用不同的起始颜色，形成条纹效果
                // 💡 作用：通过(y/repCount)%4使颜色随行数变化
                // ⏰ 使用时机：在x循环中决定每个像素的颜色
                int colIndex = (y / repCount) % 4;
                for (int x = 0; x < mWidth; ) { // 🔄 x：当前列号（在内层递增）
                    // repeat the color N times (if possible)
                    // 🔁 将颜色重复 N 次
                    for (int rep = 0; rep < repCount && x < mWidth; rep++, x++) {
                        // copy the Nth color to the current pixel
                        // 📋 将第 N 种颜色复制到当前像素
                        array[off++] = colors[colIndex][0]; // 🔴 写入R分量
                        array[off++] = colors[colIndex][1]; // 🟢 写入G分量
                        array[off++] = colors[colIndex][2]; // 🔵 写入B分量
                        array[off++] = colors[colIndex][3]; // 🔲 写入A分量
                    }
                    colIndex = (colIndex + 1) % 4; // 🔄 切换到下一个颜色
                }
            }

            if (false) saveTestBitmap(buf, index); // 🔧 调试用：保存为PNG查看效果（默认关闭）
        }


        /**
         * Fill the buffer with random data.  This will not compress at all.
         * 🎲 用随机数据填充缓冲区（完全不可压缩，共14行，需逐行注释）。
         * 🔧 为什么：测试不可压缩纹理的上传性能（最坏情况）
         * 📍 时机：在createPixelSources中对后4个纹理调用
         */
        private void randomPixelSource(ByteBuffer buf, int index) {
            // 📦 array：ByteBuffer底层的byte数组
            // 📌 为什么：直接操作数组效率更高
            // 💡 作用：存储随机生成的RGBA像素数据
            // ⏰ 使用时机：在下面的三重循环中逐字节写入
            byte[] array = buf.array();     // works in recent Android

            // 📍 off：当前写入位置的字节偏移
            // 📌 为什么：需要追踪写入位置
            // 💡 作用：每次写入一字节后递增
            // ⏰ 使用时机：在最内层循环中写入每个颜色分量
            int off = 0;
            for (int y = 0; y < mHeight; y++) { // 🔄 y：行号
                for (int x = 0; x < mWidth; x++) { // 🔄 x：列号
                    for (int b = 0; b < 4; b++) { // 🔄 b：RGBA分量索引(0=R,1=G,2=B,3=A)
                        array[off++] = (byte) (256 * Math.random() - 128); // 🎲 写入随机字节值(-128~127)
                    }
                }
            }

            if (false) saveTestBitmap(buf, index); // 🔧 调试用：保存为PNG（默认关闭）
        }

        /**
         * Save generated data to a PNG file for debugging.
         * 💾 将生成的像素数据保存为 PNG 文件（调试用，共24行，需逐行注释）。
         * 🔧 为什么：验证生成的像素数据是否正确
         * 📍 时机：仅在调试模式下调用（当前默认关闭）
         */
        private void saveTestBitmap(ByteBuffer buf, int index) {
            // Save the generated bitmap to a PNG so we can see what it looks like.
            // 💾 将生成的位图保存为 PNG 以便查看
            // 📝 filename：输出PNG文件路径
            // 📌 为什么：需要指定保存位置以便查看生成的图案
            // 💡 作用：用于调试验证像素数据正确性
            // ⏰ 使用时机：仅调试时保存
            String filename = "/sdcard/test-" + index + ".png";
            Log.d(TAG, "Creating " + filename); // 📝 记录正在创建的文件
            // 📤 bos：缓冲输出流，用于高效写入PNG数据
            // 📌 为什么：BufferedOutputStream提高文件写入效率
            // 💡 作用：包装FileOutputStream，提供缓冲写入
            // ⏰ 使用时机：在finally中关闭，确保资源释放
            BufferedOutputStream bos = null;
            try {
                bos = new BufferedOutputStream(new FileOutputStream(filename)); // 📞 创建文件输出流
                // 🖼️ bmp：Bitmap对象，用于将ByteBuffer转换为PNG
                // 📌 为什么：需要Bitmap作为中间格式来压缩为PNG
                // 💡 作用：从ByteBuffer复制像素，然后压缩输出
                // ⏰ 使用时机：复制像素后压缩到输出流
                Bitmap bmp = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888); // 📞 创建ARGB位图
                bmp.copyPixelsFromBuffer(buf); // 📋 从ByteBuffer复制像素到位图
                bmp.compress(Bitmap.CompressFormat.PNG, 90, bos); // 📦 压缩为PNG（质量90）写入输出流
                bmp.recycle(); // 🧹 回收Bitmap内存
            } catch (IOException ioe) {
                Log.w(TAG, "Failed to create " + filename, ioe); // ⚠️ 记录文件创建失败
            } finally {
                if (bos != null) {
                    try {
                        bos.close(); // 🧹 关闭输出流
                    } catch (IOException ioe) {
                        Log.w(TAG, "Failed to close " + filename, ioe); // ⚠️ 记录关闭失败
                    }
                }
            }
        }

        /**
         * Attempt to measure the time required to upload a 512x512 texture.
         * <p>
         * The driver may employ various forms of cleverness, like not fully processing
         * a texture that never gets used.  So we want to render something with the texture.
         * To avoid including the texture rendering time in the result, we do a second set
         * of operations that just do rendering with a previously-uploaded texture, and
         * subtract that off the total.
         * <p>
         * This is all rather unscientific, but it should be good for a ball-park value.
         * 🧪 尝试测量上传 512x512 纹理所需的时间（共90行，需逐行注释）。
         *    驱动可能使用各种优化手段（如不完全处理未使用的纹理），因此需要实际渲染。
         *    为排除渲染时间的影响，会做两轮操作并相减以获得更准确的上传时间。
         *    这不是严格的科学测试，但可以给出大致参考值。
         * 🔧 为什么：测量glTexImage2D的GPU纹理上传性能
         * 📍 时机：doInBackground后台线程中调用
         *
         * @return Total time spent on glTexImage2d(). glTexImage2d 的总耗时（纳秒）
         */
        private long runTextureTest(OffscreenSurface eglSurface) { // 🖥️ eglSurface：离屏EGL渲染表面
            // ⏱️ totalTime：所有纹理上传操作的累计耗时
            long totalTime = 0; // 📊 初始化总耗时

            // Prep GL/EGL.  We use an identity projection matrix, which means the surface
            // coordinates span from -1 to 1 in both dimensions.
            // 🔧 初始化 GL/EGL。使用单位投影矩阵，表面坐标范围为 -1 到 1。
            eglSurface.makeCurrent(); // 🔧 将此离屏Surface设为当前GL上下文
            // 🎮 texProgram：纹理渲染程序，用于将纹理绘制到屏幕
            // 🔍 为什么：需要一个GL程序来渲染纹理，验证纹理上传成功
            Texture2dProgram texProgram = // 📞 创建2D纹理渲染程序
                    new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D); // 🏷️ 使用标准2D纹理类型
            // 📐 rectDrawable：矩形几何体
            Drawable2d rectDrawable = new Drawable2d(Drawable2d.Prefab.RECTANGLE); // 📞 创建预制矩形顶点数据
            // 🖼️ rect：2D精灵对象，用于渲染纹理到矩形
            Sprite2d rect = new Sprite2d(rectDrawable); // 📞 使用矩形几何体创建2D精灵

            for (int iteration = 0; iteration < mIterations; iteration++) { // 🔄 iteration：当前迭代轮次
                if (mIsCanceled) { // 🔍 检查用户是否点击取消
                    Log.d(TAG, "Canceled!"); // 📝 记录取消
                    totalTime = -2; // ❌ 返回-2表示用户取消
                    break; // 🛑 跳出循环
                }
                publishProgress(iteration); // 📊 通知UI更新进度条

                GLES20.glClearColor(1f, 0f, 0f, 1f); // 🎨 设置清屏颜色为红色（RGBA: 1,0,0,1）
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT); // 🧹 清除颜色缓冲区为红色背景

                // Upload all 8 textures.  We're also including the time to generate an ID
                // and do the other housekeeping, but there's no reason not to include it.
                // 📤 上传全部 8 个纹理（包括生成 ID 和其他管理操作的时间）
                // 📦 textureHandles：存储8个纹理对象的ID数组
                int[] textureHandles = new int[TEX_PER_ITER]; // 📞 创建纹理句柄数组
                // ⏱️ uploadStartNanos：纹理上传开始时间戳
                long uploadStartNanos = System.nanoTime(); // 📊 记录上传开始时间
                for (int i = 0; i < TEX_PER_ITER; i++) { // 🔄 i：当前纹理索引
                    // 📤 从像素数据源创建GL纹理对象
                    textureHandles[i] = GlUtil.createImageTexture(mPixelSource[i], mWidth, mHeight, // 📞 创建纹理并上传像素数据
                            GLES20.GL_RGBA); // 🎨 指定RGBA格式
                }
                // ⏱️ uploadEndNanos：纹理上传结束时间戳
                long uploadEndNanos = System.nanoTime(); // 📊 记录上传结束时间

                // Render all textures, onto the top half of the output window.
                // 🎨 将所有纹理渲染到输出窗口的上半部分
                for (int i = 0; i < TEX_PER_ITER; i++) { // 🔄 遍历所有纹理进行渲染
                    // 📐 rectWidth：每个矩形的宽度（总宽度2除以纹理数量）
                    float rectWidth = 2f / TEX_PER_ITER; // 🧮 每个矩形占总宽度的1/8
                    // 📐 rectHeight：矩形高度（填满上半部分）
                    float rectHeight = 1f; // 🧮 高度为1（NDC坐标系中上半屏高度）
                    rect.setScale(rectWidth, rectHeight); // 📐 设置精灵缩放（NDC坐标系中的宽高）
                    // 📍 设置精灵位置：X从-1开始均匀分布，Y在上半部分（-rectHeight/2即-0.5）
                    rect.setPosition(2f * i / TEX_PER_ITER - 1 + rectWidth / 2, - rectHeight / 2); // 📍 计算每个矩形的中心位置
                    rect.setTexture(textureHandles[i]); // 🖼️ 将第i个纹理设置到精灵上
                    rect.draw(texProgram, GlUtil.IDENTITY_MATRIX); // 🎨 使用单位矩阵绘制精灵（无变换）
                }
                GLES20.glFinish(); // ⏸️ 等待GPU完成所有渲染，确保计时准确
                // ⏱️ drawEndNanos：第一轮渲染结束时间
                long drawEndNanos = System.nanoTime(); // 📊 记录第一轮渲染结束时间

                // Render all textures, onto the bottom half of the output window.
                // 🎨 将所有纹理渲染到输出窗口的下半部分（顺序反转）
                // 🔍 为什么：第二轮渲染用于测量纯渲染时间，以便从总时间中扣除
                for (int i = 0; i < TEX_PER_ITER; i++) { // 🔄 遍历所有纹理
                    float rectWidth = 2f / TEX_PER_ITER; // 📐 矩形宽度（同上）
                    float rectHeight = 1f; // 📐 矩形高度（同上）
                    rect.setScale(rectWidth, rectHeight); // 📐 设置缩放
                    // 📍 Y坐标改为正值0.5（下半部分中心），顺序反转渲染
                    rect.setPosition(2f * i / TEX_PER_ITER - 1 + rectWidth / 2, rectHeight / 2); // 📍 下半部分位置
                    rect.setTexture(textureHandles[TEX_PER_ITER - i - 1]); // 🖼️ 使用反转顺序的纹理（避免缓存优化影响）
                    rect.draw(texProgram, GlUtil.IDENTITY_MATRIX); // 🎨 绘制
                }
                GLES20.glFinish(); // ⏸️ 等待GPU完成第二轮渲染
                // ⏱️ redrawEndNanos：第二轮渲染结束时间
                long redrawEndNanos = System.nanoTime(); // 📊 记录第二轮渲染结束时间

                // ✂️ trimmedTime：剪裁后的上传时间 = 第一轮总时间 - 第二轮纯渲染时间
                // 🧮 为什么：排除渲染操作本身的时间，只保留上传开销
                long trimmedTime = (drawEndNanos - uploadStartNanos) - // 📊 第一轮：上传+渲染总时间
                                   (redrawEndNanos - drawEndNanos);  // ✂️ 减去第二轮纯渲染时间
                Log.d(TAG, "iter " + iteration + // 📝 记录本次迭代的详细时间分解
                        " upload=" + (uploadEndNanos - uploadStartNanos) + // ⏱️ 纯上传时间
                        " draw=" + (drawEndNanos - uploadEndNanos) +        // ⏱️ 第一轮渲染时间
                        " redraw=" + (redrawEndNanos - drawEndNanos) +      // ⏱️ 第二轮渲染时间
                        " trimmed=" + trimmedTime);                         // ⏱️ 剪裁后的上传时间
                totalTime += trimmedTime; // 📊 累加到总时间

                GLES20.glDeleteTextures(TEX_PER_ITER, textureHandles, 0); // 🧹 删除所有纹理对象，释放GPU内存
                eglSurface.swapBuffers(); // 🔄 交换缓冲区（虽然不显示但保持GL状态正确）
            }

            Log.d(TAG, "done"); // 📝 记录测试完成

            if (true) { // 🔧 始终为true，保留最后一帧用于调试
                // save the final frame into a file
                // 💾 将最后一帧保存到文件
                long startWhen = System.nanoTime(); // ⏱️ 记录保存开始时间
                try {
                    eglSurface.saveFrame(new File(Environment.getExternalStorageDirectory(), // 📂 外部存储根目录
                            "test.png")); // 📁 保存为test.png
                } catch (IOException ioe) { // ❌ 保存失败
                    throw new RuntimeException(ioe); // 💥 抛出运行时异常
                }
                Log.d(TAG, "Saved frame in " + ((System.nanoTime() - startWhen) / 1000000) + "ms"); // 📝 记录保存耗时
            }

            return totalTime; // ✅ 返回纹理上传总耗时（纳秒）
        } // ✅ runTextureTest结束
    }
}
