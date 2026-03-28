package com.android.grafika;

import android.opengl.GLES20;
import android.opengl.GLES30;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.app.Activity;
import android.graphics.SurfaceTexture;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.WindowSurface;
import com.google.grafika.R;

/**
 * Simple demonstration of using GLES to draw on a TextureView.
 * <p>
 * Note that rendering is a multi-stage process:
 * <ol>
 * <li>Render thread draws with GL on its local EGLSurface, a window surface it created.
 * <li>The SurfaceTexture takes what is rendered onto it and makes it available as a GL texture.
 * <li>TextureView takes the GL texture and renders it onto its EGLSurface.
 * </ol>
 * 
 * 🎨 使用GLES在TextureView上绘制的简单演示
 * 💡 渲染是多阶段过程：渲染线程 -> SurfaceTexture -> TextureView
 * 💡 与GLSurfaceView不同，TextureView不管理EGL配置和渲染线程
 */
public class TextureViewGLActivity extends Activity {
    private static final String TAG = MainActivity.TAG;

    // 💡 实验：在回调中释放SurfaceTexture vs 在绘制循环中显式释放
    // ⚠️ 必须是静态的，否则每次Activity暂停/恢复时会重置
    private static volatile boolean sReleaseInCallback = true;

    private TextureView mTextureView;  // 🖼️ TextureView视图
    private Renderer mRenderer;        // 🧵 渲染线程

    /**
     * 🔧 Activity创建时调用
     * 启动渲染线程并设置SurfaceTexture监听器
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📝 打印创建日志，用于调试Activity生命周期
        Log.d(TAG, "onCreate");
        // 🔄 调用父类onCreate，执行标准Activity创建流程
        super.onCreate(savedInstanceState);

        // 🧵 创建渲染线程实例
        // 💡 变量mRenderer：渲染线程对象，负责GL渲染
        // 💡 作用：在独立线程中执行OpenGL ES渲染任务
        // 💡 时机：Activity创建时初始化
        mRenderer = new Renderer();
        // ▶️ 启动渲染线程
        // 💡 作用：线程开始运行，等待SurfaceTexture可用
        mRenderer.start();

        // 🖼️ 设置Activity布局文件
        // 💡 作用：加载包含TextureView的XML布局
        setContentView(R.layout.activity_texture_view_gl);
        // 🔍 查找TextureView控件
        // 💡 变量mTextureView：TextureView视图引用
        // 💡 作用：获取布局中的TextureView，用于GL渲染显示
        // 💡 时机：布局加载后获取
        mTextureView = (TextureView) findViewById(R.id.glTextureView);
        // 📋 设置SurfaceTexture监听器
        // 💡 作用：监听SurfaceTexture生命周期事件（可用/销毁等）
        mTextureView.setSurfaceTextureListener(mRenderer);
    }

    /**
     * ▶️ Activity 恢复时调用
     * 💡 作用：更新UI按钮的文本状态
     * 💡 时机：Activity从暂停状态恢复时
     */
    @Override
    protected void onResume() {
        // 🔄 调用父类的onResume方法
        // 💡 作用：执行标准的Activity恢复流程
        super.onResume();
        // 🔄 更新UI控件状态
        // 💡 作用：同步按钮文本与当前sReleaseInCallback状态
        // 💡 时机：每次Activity恢复时刷新UI
        updateControls();
    }

    /**
     * 💀 Activity销毁时调用
     * 停止渲染线程
     */
    @Override
    protected void onDestroy() {
        // 📝 打印销毁日志，用于调试Activity生命周期
        Log.d(TAG, "onDestroy");
        // 🔄 调用父类onDestroy，执行标准Activity销毁流程
        super.onDestroy();
        // ⏹️ 通知渲染线程停止运行
        // 💡 作用：设置停止标志并唤醒线程，使其安全退出
        mRenderer.halt();
    }

    /** 🔘 更新UI按钮的文本状态 */
    private void updateControls() {
        // 🔍 查找toggleRelease按钮
        // 💡 作用：获取释放模式切换按钮的引用
        // 💡 时机：Activity恢复或模式切换后调用
        Button toggleRelease = (Button) findViewById(R.id.toggleRelease_button);
        // 📝 根据当前释放模式选择对应的字符串资源ID
        // 💡 变量id：存储选中的字符串资源ID
        // 💡 作用：决定按钮显示"在回调中释放"还是"在渲染线程释放"
        int id = sReleaseInCallback ?
                R.string.toggleReleaseCallbackOff : R.string.toggleReleaseCallbackOn;
        // 📝 设置按钮文本为选中的字符串
        // 💡 作用：更新按钮显示文本，反映当前模式
        toggleRelease.setText(id);
    }

    /** 🔘 切换释放模式按钮点击 */
    public void clickToggleRelease(View unused) {
        sReleaseInCallback = !sReleaseInCallback;
        updateControls();
    }

    /**
     * 🧵 渲染线程类
     * 💡 处理GL渲染和SurfaceTexture回调
     * 💡 不创建Looper，所以SurfaceTexture回调在UI线程执行
     */
    private static class Renderer extends Thread implements TextureView.SurfaceTextureListener {
        private Object mLock = new Object();        // 🔒 保护mSurfaceTexture和mDone
        private SurfaceTexture mSurfaceTexture;     // 🖼️ SurfaceTexture
        private EglCore mEglCore;                   // 🎮 EGL核心
        private boolean mDone;                      // 🔚 是否完成

        public Renderer() {
            super("TextureViewGL Renderer");
        }

        /**
         * 🧵 线程入口点
         * 💡 等待SurfaceTexture可用，然后开始渲染
         * 💡 使用synchronized确保线程安全访问SurfaceTexture
         */
        @Override
        public void run() {
            // 🔄 主循环：持续运行直到收到停止信号
            // 💡 作用：保持渲染线程活跃，处理多次SurfaceTexture生命周期
            // 💡 时机：线程启动后立即进入
            while (true) {
                // 🖼️ 临时存储SurfaceTexture引用
                // 💡 作用：从成员变量获取当前SurfaceTexture
                // 💡 时机：每次循环迭代开始时重置
                SurfaceTexture surfaceTexture = null;

                // 🔒 进入同步块，等待SurfaceTexture变为可用
                // 💡 作用：保护mSurfaceTexture和mDone的线程安全访问
                // 💡 时机：循环开始时，等待UI线程设置SurfaceTexture
                synchronized (mLock) {
                    // ⏳ 等待循环：直到SurfaceTexture可用或收到停止信号
                    // 💡 作用：避免忙等待，节省CPU资源
                    // 💡 条件：未完成(mDone=false) 且 SurfaceTexture为null时持续等待
                    while (!mDone && (surfaceTexture = mSurfaceTexture) == null) {
                        try {
                            // 💤 释放锁并等待通知
                            // 💡 作用：阻塞当前线程，直到其他线程调用notify()
                            // 💡 时机：SurfaceTexture不可用时
                            mLock.wait();
                        } catch (InterruptedException ie) {
                            // ⚠️ 线程被中断时抛出运行时异常
                            // 💡 作用：将检查型异常转换为运行时异常
                            throw new RuntimeException(ie);
                        }
                    }
                    // 🔍 检查是否收到停止信号
                    // 💡 作用：判断是否应该退出渲染循环
                    // 💡 时机：从等待中唤醒后立即检查
                    if (mDone) {
                        // 🚪 跳出主循环，线程结束
                        break;
                    }
                }
                // 📝 记录获取到的SurfaceTexture信息
                Log.d(TAG, "Got surfaceTexture=" + surfaceTexture);

                // 🎮 创建EGL环境，尝试使用GLES3
                // 💡 作用：初始化OpenGL ES渲染上下文
                // 💡 参数：null表示默认显示，FLAG_TRY_GLES3优先尝试GLES3
                // 💡 时机：获取SurfaceTexture后立即创建
                mEglCore = new EglCore(null, EglCore.FLAG_TRY_GLES3);

                // 🪟 创建窗口Surface，绑定到SurfaceTexture
                // 💡 作用：将EGL渲染输出连接到TextureView
                // 💡 时机：EGL核心创建后立即创建
                WindowSurface windowSurface = new WindowSurface(mEglCore, mSurfaceTexture);

                // 🔌 激活EGL上下文，使当前线程可以进行GL调用
                // 💡 作用：将窗口Surface设为当前渲染目标
                // 💡 时机：Surface创建后，渲染前必须调用
                windowSurface.makeCurrent();

                // 🎬 执行动画渲染循环
                // 💡 作用：开始绘制动画帧，直到SurfaceTexture被销毁
                // 💡 时机：EGL环境就绪后
                doAnimation(windowSurface);

                // 🗑️ 释放窗口Surface资源
                // 💡 作用：清理渲染表面占用的资源
                // 💡 时机：动画循环结束后
                windowSurface.release();

                // 🗑️ 释放EGL核心资源
                // 💡 作用：清理EGL上下文占用的资源
                // 💡 时机：Surface释放后
                mEglCore.release();

                // 🔍 检查是否在渲染线程释放SurfaceTexture
                // 💡 作用：根据配置决定是否在此处释放SurfaceTexture
                // 💡 时机：EGL资源释放后
                if (!sReleaseInCallback) {
                    // 📝 记录释放操作
                    Log.i(TAG, "Releasing SurfaceTexture in renderer thread");
                    // 🗑️ 释放SurfaceTexture资源
                    // 💡 作用：清理SurfaceTexture占用的资源
                    // 💡 时机：不在回调中释放时，在此释放
                    surfaceTexture.release();
                }
            }

            // 📝 记录线程退出
            Log.d(TAG, "Renderer thread exiting");
        }

        /**
         * 🎬 执行动画，尽可能快地渲染帧
         * 💡 绘制一个移动的红色方块，背景色渐变
         * 💡 使用GL scissor test绘制方块，避免使用着色器
         */
        private void doAnimation(WindowSurface eglSurface) {
            // 📐 定义方块宽度（像素）
            // 💡 作用：控制动画方块的大小
            // 💡 时机：在scissor test中使用
            final int BLOCK_WIDTH = 80;

            // 🚀 定义方块移动速度（像素/帧）
            // 💡 作用：控制方块移动的快慢
            // 💡 时机：更新方块位置时使用
            final int BLOCK_SPEED = 2;

            // 🎨 清除颜色值（灰度值，0.0-1.0）
            // 💡 作用：用于背景渐变效果，从黑色逐渐变亮
            // 💡 时机：每帧绘制前设置背景色
            float clearColor = 0.0f;

            // 📍 方块X坐标（左上角）
            // 💡 作用：控制方块在屏幕上的水平位置
            // 💡 初始值：-BLOCK_WIDTH/2，使方块从屏幕左侧开始进入
            // 💡 时机：每帧更新位置
            int xpos = -BLOCK_WIDTH / 2;

            // 🚀 方块移动方向和速度
            // 💡 作用：正值向右移动，负值向左移动
            // 💡 初始值：BLOCK_SPEED，开始向右移动
            // 💡 时机：方块到达边界时反转方向
            int xdir = BLOCK_SPEED;

            // 📐 获取渲染表面的宽度
            // 💡 作用：用于判断方块是否到达边界
            // 💡 时机：边界检测时使用
            int width = eglSurface.getWidth();

            // 📐 获取渲染表面的高度
            // 💡 作用：用于计算方块的垂直位置和大小
            // 💡 时机：设置scissor区域时使用
            int height = eglSurface.getHeight();

            // 📝 记录动画尺寸信息
            Log.d(TAG, "Animating " + width + "x" + height + " EGL surface");

            // 🔄 动画主循环
            // 💡 作用：持续渲染帧直到SurfaceTexture被销毁
            while (true) {
                // 🔒 同步检查SurfaceTexture是否仍然有效
                // 💡 作用：线程安全地检查SurfaceTexture状态
                // 💡 时机：每帧开始时检查
                synchronized (mLock) {
                    // 🖼️ 获取当前SurfaceTexture引用
                    // 💡 作用：临时存储用于null检查
                    SurfaceTexture surfaceTexture = mSurfaceTexture;

                    // 🔍 检查SurfaceTexture是否已被销毁
                    // 💡 作用：判断是否应该退出动画循环
                    // 💡 条件：为null表示已销毁
                    if (surfaceTexture == null) {
                        // 📝 记录退出信息
                        Log.d(TAG, "doAnimation exiting");
                        // 🚪 退出动画循环
                        return;
                    }
                }

                // 🖼️ 设置清除颜色（渐变的灰色背景）
                // 💡 作用：配置glClear使用的颜色值
                // 💡 参数：R, G, B, A（都是clearColor值，形成灰度）
                GLES20.glClearColor(clearColor, clearColor, clearColor, 1.0f);

                // 🧹 清除颜色缓冲区
                // 💡 作用：用清除颜色填充整个屏幕
                // 💡 GL_COLOR_BUFFER_BIT：只清除颜色缓冲
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                // 🔲 启用裁剪测试
                // 💡 作用：限制绘制区域，用于绘制方块
                // 💡 时机：绘制方块前启用
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST);

                // 📐 设置裁剪区域（方块位置和大小）
                // 💡 作用：定义方块的显示区域
                // 💡 参数：x, y（左下角坐标）, width, height
                // 💡 注意：GL坐标系Y轴向上，所以y是height/4
                GLES20.glScissor(xpos, height / 4, BLOCK_WIDTH, height / 2);

                // 🔴 设置清除颜色为红色
                // 💡 作用：配置方块的填充颜色
                GLES20.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);

                // 🧹 清除裁剪区域为红色（绘制方块）
                // 💡 作用：用红色填充裁剪区域，形成方块
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

                // 🔲 禁用裁剪测试
                // 💡 作用：恢复全屏绘制模式
                // 💡 时机：方块绘制完成后
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

                // 📤 交换缓冲区，显示当前帧
                // 💡 作用：将后台缓冲区内容提交到屏幕
                // 💡 时机：所有绘制操作完成后
                eglSurface.swapBuffers();

                // 🔄 更新背景色（渐变效果）
                // 💡 作用：使背景色逐渐变亮
                // 💡 增量：0.015625 = 1/64，约64帧从黑变白
                clearColor += 0.015625f;

                // 🔄 背景色超过1.0时重置为0
                // 💡 作用：实现循环渐变效果
                if (clearColor > 1.0f) {
                    clearColor = 0.0f;
                }

                // 📍 更新方块X坐标
                // 💡 作用：移动方块位置
                xpos += xdir;

                // 🔍 边界检测：检查方块是否到达屏幕边缘
                // 💡 作用：判断是否需要反转移动方向
                // 💡 条件：左边界(-BLOCK_WIDTH/2) 或 右边界(width-BLOCK_WIDTH/2)
                if (xpos <= -BLOCK_WIDTH / 2 || xpos >= width - BLOCK_WIDTH / 2) {
                    // 📝 记录方向变化
                    Log.d(TAG, "change direction");
                    // 🔄 反转移动方向
                    // 💡 作用：使方块在边界处反弹
                    xdir = -xdir;
                }
            }
        }

        /** 🔚 告诉线程停止运行 */
        public void halt() {
            // 🔒 进入同步块，安全修改停止标志
            // 💡 作用：保护mDone变量的线程安全访问
            synchronized (mLock) {
                // ✅ 设置停止标志为true
                // 💡 作用：通知run()方法退出主循环
                mDone = true;
                // 🔔 唤醒等待中的渲染线程
                // 💡 作用：解除run()中mLock.wait()的阻塞状态
                mLock.notify();
            }
        }

        /**
         * 🖼️ SurfaceTexture可用时调用（UI线程）
         * 💡 作用：TextureView的SurfaceTexture就绪时通知渲染线程
         * 💡 参数st：新创建的SurfaceTexture对象
         * 💡 参数width/height：SurfaceTexture的像素尺寸
         */
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            // 📝 记录SurfaceTexture可用信息和尺寸
            Log.d(TAG, "onSurfaceTextureAvailable(" + width + "x" + height + ")");
            // 🔒 进入同步块，安全地将SurfaceTexture传递给渲染线程
            // 💡 作用：保护mSurfaceTexture的线程安全写入
            synchronized (mLock) {
                // 🖼️ 保存SurfaceTexture引用
                // 💡 作用：供run()方法获取并用于EGL渲染
                mSurfaceTexture = st;
                // 🔔 唤醒等待SurfaceTexture的渲染线程
                // 💡 作用：解除run()中mLock.wait()的阻塞，开始渲染
                mLock.notify();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
            Log.d(TAG, "onSurfaceTextureSizeChanged(" + width + "x" + height + ")");
        }

        /**
         * 🖼️ SurfaceTexture销毁时调用（UI线程）
         * 💡 在4.4中，缓冲区队列是同步的，需要在这里释放以确保渲染线程能退出
         * 💡 参数st：即将被销毁的SurfaceTexture
         * 💡 返回值：true表示允许TextureView释放SurfaceTexture
         */
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            // 📝 记录SurfaceTexture销毁事件
            Log.d(TAG, "onSurfaceTextureDestroyed");
            // 🔒 进入同步块，安全地清除SurfaceTexture引用
            // 💡 作用：保护mSurfaceTexture的线程安全写入
            synchronized (mLock) {
                // 🖼️ 将SurfaceTexture设为null
                // 💡 作用：通知run()方法SurfaceTexture已被销毁，退出动画循环
                mSurfaceTexture = null;
            }
            // 🔍 检查是否在回调中释放SurfaceTexture
            // 💡 作用：根据实验配置决定释放策略
            if (sReleaseInCallback) {
                // 📝 记录释放策略：允许TextureView释放SurfaceTexture
                Log.i(TAG, "Allowing TextureView to release SurfaceTexture");
            }
            // 📤 返回释放标志
            // 💡 作用：true=TextureView释放，false=渲染线程释放
            return sReleaseInCallback;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {
            //Log.d(TAG, "onSurfaceTextureUpdated");
        }
    }
}
