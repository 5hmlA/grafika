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

package com.android.grafika.gles;  // 📦 包声明：OpenGL ES 工具类所在的包

// 📚 导入需要的类库
import android.graphics.SurfaceTexture;           // 🖼️ SurfaceTexture：用于将图像流转换为OpenGL纹理
import android.opengl.EGL14;                      // 🎮 EGL14：EGL（嵌入式系统图形库）核心API
import android.opengl.EGLConfig;                  // ⚙️ EGLConfig：EGL配置对象
import android.opengl.EGLContext;                 // 🎯 EGLContext：OpenGL ES上下文
import android.opengl.EGLDisplay;                 // 🖥️ EGLDisplay：EGL显示设备
import android.opengl.EGLExt;                     // 📊 EGLExt：EGL扩展功能
import android.opengl.EGLSurface;                 // 🖼️ EGLSurface：EGL渲染表面
import android.util.Log;                          // 📝 Log：日志工具
import android.view.Surface;                      // 🖼️ Surface：Android显示表面

/**
 * Core EGL state (display, context, config).
 * 
 * 🎮 核心EGL状态管理类（显示设备、上下文、配置）
 * 
 * <p>
 * The EGLContext must only be attached to one thread at a time.  This class is not thread-safe.
 * ⚠️ EGLContext一次只能附加到一个线程。这个类不是线程安全的。
 * 
 * 💡 EGL（Embedded-Systems Graphics Library）是OpenGL ES和底层窗口系统之间的接口
 *    它负责管理图形上下文、渲染表面和像素格式等
 */
public final class EglCore {
    // 🏷️ TAG：日志标签，用于在 Logcat 中过滤 EGL 相关的日志
    private static final String TAG = GlUtil.TAG;

    /**
     * Constructor flag: surface must be recordable.  This discourages EGL from using a
     * pixel format that cannot be converted efficiently to something usable by the video
     * encoder.
     * 
     * 🎥 构造函数标志：表面必须可录制
     * 💡 这会阻止EGL使用无法高效转换为视频编码器可用格式的像素格式
     * 🔧 位值 0x01（二进制：00000001）
     */
    public static final int FLAG_RECORDABLE = 0x01;

    /**
     * Constructor flag: ask for GLES3, fall back to GLES2 if not available.  Without this
     * flag, GLES2 is used.
     * 
     * ⚡ 构造函数标志：请求GLES3，如果不可用则回退到GLES2
     * 💡 没有这个标志的话，只使用GLES2
     * 🔧 位值 0x02（二进制：00000010）
     */
    public static final int FLAG_TRY_GLES3 = 0x02;

    // 🔧 Android-specific extension.
    // 📱 Android特定扩展：可录制属性的常量值
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    // 🖥️ mEGLDisplay：EGL显示设备连接
    // 💡 初始化为 EGL_NO_DISPLAY，表示尚未连接
    private EGLDisplay mEGLDisplay = EGL14.EGL_NO_DISPLAY;
    // 🎯 mEGLContext：OpenGL ES渲染上下文
    // 💡 初始化为 EGL_NO_CONTEXT，表示尚未创建
    private EGLContext mEGLContext = EGL14.EGL_NO_CONTEXT;
    // ⚙️ mEGLConfig：EGL配置对象，包含像素格式等设置
    private EGLConfig mEGLConfig = null;
    // 📊 mGlVersion：OpenGL ES版本号（2或3）
    // 💡 初始化为 -1，表示尚未确定
    private int mGlVersion = -1;


    /**
     * Prepares EGL display and context.
     * <p>
     * Equivalent to EglCore(null, 0).
     * 
     * 🎮 默认构造函数：准备EGL显示设备和上下文
     * 💡 等同于调用 EglCore(null, 0)
     */
    public EglCore() {
        // 📞 调用带参数的构造函数，不共享上下文，无特殊标志
        this(null, 0);
    }

    /**
     * Prepares EGL display and context.
     * <p>
     * @param sharedContext The context to share, or null if sharing is not desired.
     * @param flags Configuration bit flags, e.g. FLAG_RECORDABLE.
     * 
     * 🎮 带参数构造函数：准备EGL显示设备和上下文
     * 
     * @param sharedContext 要共享的上下文，如果不需要共享则传null
     *                     💡 共享上下文可以让多个EGLContext共享纹理等资源
     * @param flags 配置位标志，例如 FLAG_RECORDABLE
     *             💡 可以用 | 运算符组合多个标志
     */
    public EglCore(EGLContext sharedContext, int flags) {
        // ⚠️ 检查是否已经设置过EGL显示设备，防止重复初始化
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("EGL already set up");
        }

        // 🔄 如果没有提供共享上下文，使用 EGL_NO_CONTEXT
        // 💡 共享上下文允许两个EGL上下文共享纹理、缓冲区等资源
        if (sharedContext == null) {
            sharedContext = EGL14.EGL_NO_CONTEXT;
        }

        // 📺 获取默认EGL显示设备
        // 💡 EGL_DEFAULT_DISPLAY 表示默认显示设备，通常是主屏幕
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        // ⚠️ 检查是否成功获取显示设备，EGL_NO_DISPLAY 表示失败
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL14 display");
        }
        // 📊 初始化EGL并获取版本号
        // 💡 version数组：version[0]为主版本号，version[1]为次版本号
        // 💡 用于后续确定支持的OpenGL ES版本
        int[] version = new int[2];
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null;
            throw new RuntimeException("unable to initialize EGL14");
        }

        // ⚡ 尝试获取GLES3上下文（如果请求了的话）
        // Try to get a GLES3 context, if requested.
        // 💡 FLAG_TRY_GLES3 标志表示优先尝试GLES3
        if ((flags & FLAG_TRY_GLES3) != 0) {
            // 📝 尝试获取GLES 3配置
            // 💡 config包含像素格式、颜色深度等配置信息
            //Log.d(TAG, "Trying GLES 3");
            EGLConfig config = getConfig(flags, 3);
            if (config != null) {
                // 🔧 设置GLES3上下文属性
                // 💡 attrib3_list：EGL上下文属性数组
                // 💡 EGL_CONTEXT_CLIENT_VERSION 指定OpenGL ES版本为3
                // 💡 EGL_NONE 表示属性列表结束
                int[] attrib3_list = {
                        EGL14.EGL_CONTEXT_CLIENT_VERSION, 3,  // 📊 指定客户端版本为3
                        EGL14.EGL_NONE                        // 🛑 属性列表结束标记
                };
                // 🎯 创建GLES3上下文
                // 💡 参数：显示设备、配置、共享上下文、属性列表、属性偏移
                // 💡 使用时机：需要GLES3的高级特性时（如计算着色器、变换反馈等）
                EGLContext context = EGL14.eglCreateContext(mEGLDisplay, config, sharedContext,
                        attrib3_list, 0);

                // ✅ 检查是否成功创建上下文
                // 💡 eglGetError() 返回最后一个EGL错误代码
                if (EGL14.eglGetError() == EGL14.EGL_SUCCESS) {
                    // 📝 保存GLES3配置和上下文
                    // 💡 成功创建后，将配置和上下文保存到成员变量
                    // 💡 使用时机：后续所有OpenGL操作都需要这些对象
                    //Log.d(TAG, "Got GLES 3 config");
                    mEGLConfig = config;
                    mEGLContext = context;
                    mGlVersion = 3;  // 📊 记录版本号为3，用于后续版本判断
                }
            }
        }
        // 🔄 如果GLES3不可用或未请求，使用GLES2
        // 💡 这是降级方案，确保至少GLES2可用
        if (mEGLContext == EGL14.EGL_NO_CONTEXT) {  // GLES 2 only, or GLES 3 attempt failed
            // 📝 尝试获取GLES 2配置
            // 💡 GLES2是Android的基本图形API，所有设备都支持
            //Log.d(TAG, "Trying GLES 2");
            EGLConfig config = getConfig(flags, 2);
            if (config == null) {
                throw new RuntimeException("Unable to find a suitable EGLConfig");
            }
            // 🔧 设置GLES2上下文属性
            // 💡 attrib2_list：EGL上下文属性数组，指定使用GLES2
            int[] attrib2_list = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,  // 📊 指定客户端版本为2
                    EGL14.EGL_NONE                        // 🛑 属性列表结束标记
            };
            // 🎯 创建GLES2上下文
            // 💡 使用时机：当GLES3不可用或不需要GLES3特性时
            EGLContext context = EGL14.eglCreateContext(mEGLDisplay, config, sharedContext,
                    attrib2_list, 0);
            // ⚠️ 检查EGL错误，确保上下文创建成功
            checkEglError("eglCreateContext");
            // 📝 保存GLES2配置和上下文
            // 💡 这些对象将用于后续所有OpenGL操作
            mEGLConfig = config;
            mEGLContext = context;
            mGlVersion = 2;  // 📊 记录版本号为2
        }

        // 🔍 通过查询确认创建的上下文版本
        // Confirm with query.
        // 💡 values数组用于存储查询结果
        // 💡 使用时机：验证实际创建的版本是否与预期一致
        int[] values = new int[1];
        EGL14.eglQueryContext(mEGLDisplay, mEGLContext, EGL14.EGL_CONTEXT_CLIENT_VERSION,
                values, 0);
        // 📝 记录创建的上下文版本信息
        // 💡 用于调试，确认实际使用的OpenGL ES版本
        Log.d(TAG, "EGLContext created, client version " + values[0]);
    }

    /**
     * Finds a suitable EGLConfig.
     *
     * @param flags Bit flags from constructor.
     * @param version Must be 2 or 3.
     * 
     * ⚙️ 查找合适的EGL配置
     * 
     * @param flags 构造函数传入的位标志
     * @param version 必须是2或3，指定OpenGL ES版本
     * @return 匹配的EGLConfig对象，如果没有找到则返回null
     */
    private EGLConfig getConfig(int flags, int version) {
        // 📊 设置可渲染类型：默认为GLES2
        // 💡 renderableType 指定支持的OpenGL ES版本位掩码
        // 💡 使用时机：eglChooseConfig 根据此属性筛选配置
        int renderableType = EGL14.EGL_OPENGL_ES2_BIT;
        // ⚡ 如果版本>=3，添加GLES3支持
        // 💡 EGL_OPENGL_ES3_BIT_KHR 是GLES3的扩展位
        // 💡 使用按位或操作组合标志
        if (version >= 3) {
            renderableType |= EGLExt.EGL_OPENGL_ES3_BIT_KHR;
        }

        /**
         * The actual surface is generally RGBA or RGBX, so situationally omitting alpha
         * doesn't really help.  It can also lead to a huge performance hit on glReadPixels()
         * when reading into a GL_RGBA buffer.
         * 
         * 💡 实际表面通常是RGBA或RGBX格式，所以省略alpha通道帮助不大
         *    而且可能导致glReadPixels()读取到GL_RGBA缓冲区时性能大幅下降
         */
        // 🔧 设置EGL配置属性列表
        // 💡 attribList：EGL配置属性数组，定义所需的像素格式
        // 💡 每两个元素为一对（属性名，属性值）
        // 💡 使用时机：eglChooseConfig 根据这些属性查找匹配的配置
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,           // 🔴 红色通道：8位，定义红色分量精度
                EGL14.EGL_GREEN_SIZE, 8,         // 🟢 绿色通道：8位，定义绿色分量精度
                EGL14.EGL_BLUE_SIZE, 8,          // 🔵 蓝色通道：8位，定义蓝色分量精度
                EGL14.EGL_ALPHA_SIZE, 8,         // 🔲 透明度通道：8位，定义透明度精度
                //EGL14.EGL_DEPTH_SIZE, 16,      // 📐 深度缓冲区：16位（已注释），用于3D渲染
                //EGL14.EGL_STENCIL_SIZE, 8,     // 📐 模板缓冲区：8位（已注释），用于特殊效果
                EGL14.EGL_RENDERABLE_TYPE, renderableType,  // 🎮 可渲染类型，指定支持的GLES版本
                EGL14.EGL_NONE, 0,      // 📝 placeholder for recordable [@-3]，预留位置用于可录制属性
                EGL14.EGL_NONE                  // 🛑 属性列表结束标记
        };
        // 🎥 如果需要可录制支持，设置Android特定属性
        // 💡 FLAG_RECORDABLE 标志表示表面需要可用于视频编码
        // 💡 使用时机：当需要将OpenGL渲染结果录制为视频时
        if ((flags & FLAG_RECORDABLE) != 0) {
            attribList[attribList.length - 3] = EGL_RECORDABLE_ANDROID;  // 📱 Android可录制属性
            attribList[attribList.length - 2] = 1;                        // 📱 属性值：1表示启用
        }
        // 🔍 查询匹配的EGL配置
        // 💡 configs数组：用于存储找到的配置对象
        // 💡 numConfigs数组：用于存储找到的配置数量
        // 💡 使用时机：根据attribList属性查找合适的EGL配置
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(mEGLDisplay, attribList, 0, configs, 0, configs.length,
                numConfigs, 0)) {
            // ⚠️ 如果找不到RGB8888格式的配置，记录警告并返回null
            // 💡 可能是设备不支持RGBA8888格式
            Log.w(TAG, "unable to find RGB8888 / " + version + " EGLConfig");
            return null;
        }
        // ✅ 返回找到的第一个配置
        // 💡 返回第一个匹配的配置，通常是最合适的
        // 💡 使用时机：后续创建EGL上下文和表面时需要此配置
        return configs[0];
    }

    /**
     * Discards all resources held by this class, notably the EGL context.  This must be
     * called from the thread where the context was created.
     * <p>
     * On completion, no context will be current.
     *
     * 🗑️ 释放所有资源，特别是EGL上下文（资源清理方法！）
     * ⚠️ 必须从创建上下文的线程调用此方法
     * 💡 完成后，没有上下文会是当前的
     * 📌 使用时机：EGL不再需要时，必须显式调用防止资源泄漏
     */
    public void release() {
        // 🔍 mEGLDisplay：EGL显示设备连接（EGLDisplay类型）
        // 💡 为什么检查：如果显示设备未初始化（EGL_NO_DISPLAY），说明没有资源需要释放
        // 💡 作用：防止对未初始化的EGL调用清理方法导致崩溃
        // 💡 使用时机：在所有清理操作前判断是否需要执行
        if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
            /**
             * Android is unusual in that it uses a reference-counted EGLDisplay.  So for
             * every eglInitialize() we need an eglTerminate().
             *
             * 💡 Android的特殊之处在于它使用引用计数的EGLDisplay
             *    所以每次eglInitialize()都需要对应的eglTerminate()
             */
            // 🔄 eglMakeCurrent：解绑当前上下文（将NO_SURFACE和NO_CONTEXT设为当前）
            // 💡 为什么需要：必须先解绑上下文才能安全销毁它
            // 💡 参数说明：display=EGL显示设备, draw=无表面, read=无表面, context=无上下文
            // 💡 使用时机：在销毁上下文之前必须调用
            EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            // 🗑️ eglDestroyContext：销毁EGL渲染上下文
            // 💡 为什么需要：释放上下文占用的GPU和系统资源
            // 💡 参数说明：display=EGL显示设备, context=要销毁的上下文(mEGLContext)
            // 💡 使用时机：在解绑上下文之后、终止显示设备之前
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext);
            // 🔄 eglReleaseThread：释放当前线程的EGL资源
            // 💡 为什么需要：清理当前线程上EGL分配的内部资源
            // 💡 作用：通知EGL当前线程不再使用EGL功能
            // 💡 使用时机：在销毁上下文之后、终止显示设备之前
            EGL14.eglReleaseThread();
            // 🗑️ eglTerminate：终止EGL显示设备连接
            // 💡 为什么需要：释放显示设备占用的系统资源，减少引用计数
            // 💡 参数说明：display=要终止的EGL显示设备(mEGLDisplay)
            // 💡 使用时机：所有EGL资源释放后的最后一步
            EGL14.eglTerminate(mEGLDisplay);
        }

        // 🔄 mEGLDisplay：重置为EGL_NO_DISPLAY
        // 💡 为什么重置：标记显示设备已释放，防止后续操作使用无效引用
        // 💡 作用：确保对象处于安全的无效状态
        mEGLDisplay = EGL14.EGL_NO_DISPLAY;
        // 🔄 mEGLContext：重置为EGL_NO_CONTEXT
        // 💡 为什么重置：标记上下文已释放，避免悬空指针
        // 💡 作用：后续访问会触发明确的错误而非未定义行为
        mEGLContext = EGL14.EGL_NO_CONTEXT;
        // 🔄 mEGLConfig：置空EGL配置对象
        // 💡 为什么重置：配置对象依赖于显示设备，显示设备释放后配置也无效
        // 💡 作用：帮助GC回收配置对象占用的内存
        mEGLConfig = null;
    }

    /**
     * 🔚 对象被垃圾回收前调用的finalize方法
     * ⚠️ 如果EGL资源没有显式释放，会在这里尝试释放
     */
    @Override
    protected void finalize() throws Throwable {
        try {
            // 🔍 检查EGL显示设备是否已初始化
            if (mEGLDisplay != EGL14.EGL_NO_DISPLAY) {
                /**
                 * We're limited here -- finalizers don't run on the thread that holds
                 * the EGL state, so if a surface or context is still current on another
                 * thread we can't fully release it here.  Exceptions thrown from here
                 * are quietly discarded.  Complain in the log file.
                 * 
                 * 💡 我们在这里受限——finalizer不会在持有EGL状态的线程上运行
                 *    所以如果surface或context在其他线程上仍然是当前的，我们无法完全释放它
                 *    这里抛出的异常会被静默丢弃，所以在日志文件中抱怨
                 */
                Log.w(TAG, "WARNING: EglCore was not explicitly released -- state may be leaked");
                release();
            }
        } finally {
            // 📞 调用父类的finalize方法
            super.finalize();
        }
    }

    /**
     * Destroys the specified surface.  Note the EGLSurface won't actually be destroyed if it's
     * still current in a context.
     * 
     * 🗑️ 销毁指定的EGL表面
     * ⚠️ 注意：如果EGLSurface仍然在某个上下文中是当前的，它不会被实际销毁
     * 
     * @param eglSurface 要销毁的EGL表面
     */
    public void releaseSurface(EGLSurface eglSurface) {
        // 🗑️ 调用EGL API销毁表面
        EGL14.eglDestroySurface(mEGLDisplay, eglSurface);
    }

    /**
     * Creates an EGL surface associated with a Surface.
     * <p>
     * If this is destined for MediaCodec, the EGLConfig should have the "recordable" attribute.
     * 
     * 🖼️ 创建与Surface关联的EGL表面
     * 💡 如果用于MediaCodec，EGLConfig应该有"recordable"属性
     * 
     * @param surface Surface或SurfaceTexture对象
     * @return 创建的EGLSurface对象
     */
    public EGLSurface createWindowSurface(Object surface) {
        // ⚠️ surface类型检查：验证传入对象是Surface或SurfaceTexture
        // 💡 为什么检查：eglCreateWindowSurface只接受这两种类型，其他类型会导致错误
        // 💡 作用：提前发现错误并给出明确提示，而非底层EGL的模糊错误
        // 💡 使用时机：在调用EGL API创建表面之前进行前置校验
        if (!(surface instanceof Surface) && !(surface instanceof SurfaceTexture)) {
            throw new RuntimeException("invalid surface: " + surface);
        }

        // Create a window surface, and attach it to the Surface we received.
        // 🔧 surfaceAttribs：窗口表面属性数组（int[]类型）
        // 💡 为什么定义：eglCreateWindowSurface需要属性参数，即使没有特殊属性也要传入
        // 💡 作用：传递给eglCreateWindowSurface的属性列表（此处为空，仅EGL_NONE结束标记）
        // 💡 使用时机：在eglCreateWindowSurface创建窗口表面时作为参数传入
        int[] surfaceAttribs = {
                EGL14.EGL_NONE
        };
        // 🖼️ eglSurface：创建的EGL窗口表面（EGLSurface类型）
        // 💡 为什么定义：需要保存eglCreateWindowSurface的返回值用于后续渲染
        // 💡 作用：作为OpenGL渲染的目标表面，绑定到传入的Surface上
        // 💡 使用时机：在后续makeCurrent、swapBuffers等操作中使用
        EGLSurface eglSurface = EGL14.eglCreateWindowSurface(mEGLDisplay, mEGLConfig, surface,
                surfaceAttribs, 0);
        // ⚠️ checkEglError：检查eglCreateWindowSurface调用是否出错
        // 💡 为什么检查：EGL操作失败可能不会立即抛异常，需要主动检查错误码
        // 💡 作用：确保表面创建过程中没有发生错误
        checkEglError("eglCreateWindowSurface");
        // ⚠️ null检查：验证eglSurface是否创建成功
        // 💡 为什么检查：即使没有EGL错误，eglCreateWindowSurface也可能返回null
        // 💡 作用：双重保险，确保表面对象有效
        if (eglSurface == null) {
            throw new RuntimeException("surface was null");
        }
        // ✅ 返回创建的EGLSurface
        // 💡 返回值用途：供调用者进行后续渲染操作（makeCurrent、swapBuffers等）
        return eglSurface;
    }

    /**
     * Creates an EGL surface associated with an offscreen buffer.
     * 
     * 🖼️ 创建与离屏缓冲区关联的EGL表面
     * 💡 用于离屏渲染（不显示在屏幕上）
     * 
     * @param width 缓冲区宽度
     * @param height 缓冲区高度
     * @return 创建的EGLSurface对象
     */
    public EGLSurface createOffscreenSurface(int width, int height) {
        // 🔧 surfaceAttribs：离屏表面属性数组（int[]类型）
        // 💡 为什么定义：eglCreatePbufferSurface需要属性参数来指定缓冲区尺寸
        // 💡 作用：定义像素缓冲区（Pbuffer）的宽度、高度和结束标记
        // 💡 使用时机：在eglCreatePbufferSurface创建离屏表面时作为参数传入
        int[] surfaceAttribs = {
                EGL14.EGL_WIDTH, width,   // 📐 EGL_WIDTH：缓冲区宽度（像素），用于离屏渲染的图像尺寸
                EGL14.EGL_HEIGHT, height, // 📐 EGL_HEIGHT：缓冲区高度（像素），用于离屏渲染的图像尺寸
                EGL14.EGL_NONE            // 🛑 EGL_NONE：属性列表结束标记，告知EGL属性列表到此为止
        };
        // 🖼️ eglSurface：创建的像素缓冲区表面（EGLSurface类型）
        // 💡 为什么定义：需要保存eglCreatePbufferSurface的返回值用于离屏渲染
        // 💡 作用：作为OpenGL离屏渲染的目标（不显示在屏幕上，只在内存中）
        // 💡 使用时机：在makeCurrent绑定后进行离屏渲染，然后通过glReadPixels读取结果
        EGLSurface eglSurface = EGL14.eglCreatePbufferSurface(mEGLDisplay, mEGLConfig,
                surfaceAttribs, 0);
        // ⚠️ checkEglError：检查eglCreatePbufferSurface调用是否出错
        // 💡 为什么检查：Pbuffer创建失败可能由于内存不足或尺寸超出限制
        // 💡 作用：确保像素缓冲区表面创建过程中没有发生错误
        checkEglError("eglCreatePbufferSurface");
        // ⚠️ null检查：验证eglSurface是否创建成功
        // 💡 为什么检查：即使没有EGL错误，创建也可能返回null（如GPU不支持该尺寸）
        // 💡 作用：双重保险，确保表面对象有效
        if (eglSurface == null) {
            throw new RuntimeException("surface was null");
        }
        // ✅ 返回创建的EGLSurface
        // 💡 返回值用途：供调用者进行离屏渲染操作
        return eglSurface;
    }

    /**
     * Makes our EGL context current, using the supplied surface for both "draw" and "read".
     * 
     * 🎯 将我们的EGL上下文设为当前上下文，使用指定的surface进行绘制和读取
     * 💡 调用此方法后，所有OpenGL操作都会在这个上下文和surface上执行
     * 
     * @param eglSurface 要绑定的EGL表面
     */
    public void makeCurrent(EGLSurface eglSurface) {
        // ⚠️ mEGLDisplay检查：验证EGL显示设备是否已初始化
        // 💡 为什么检查：如果显示设备未初始化，eglMakeCurrent会失败
        // 💡 作用：提前发现问题，输出日志提示（不抛异常，让后续API抛出更详细的错误）
        // 💡 使用时机：在调用eglMakeCurrent之前进行前置检查
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            // called makeCurrent() before create?
            // 📝 提示：在创建之前就调用了makeCurrent()
            Log.d(TAG, "NOTE: makeCurrent w/o display");
        }
        // 🎯 eglMakeCurrent：绑定EGL上下文和表面（核心操作！）
        // 💡 为什么调用：必须绑定后，OpenGL ES命令才知道在哪里渲染
        // 💡 参数说明：
        //    - mEGLDisplay：EGL显示设备连接
        //    - eglSurface：绘制表面（draw和read使用同一个surface）
        //    - eglSurface：读取表面（与绘制表面相同）
        //    - mEGLContext：要绑定的渲染上下文
        // 💡 使用时机：每次切换渲染目标时都需要调用
        if (!EGL14.eglMakeCurrent(mEGLDisplay, eglSurface, eglSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * Makes our EGL context current, using the supplied "draw" and "read" surfaces.
     * 
     * 🎯 将我们的EGL上下文设为当前上下文，使用指定的绘制和读取surface
     * 💡 允许使用不同的surface进行绘制和读取操作
     * 
     * @param drawSurface 用于绘制的表面
     * @param readSurface 用于读取的表面
     */
    public void makeCurrent(EGLSurface drawSurface, EGLSurface readSurface) {
        // ⚠️ mEGLDisplay检查：验证EGL显示设备是否已初始化
        // 💡 为什么检查：如果显示设备未初始化，eglMakeCurrent会失败
        // 💡 作用：提前发现问题，输出日志提示（不抛异常，让后续API抛出更详细的错误）
        // 💡 使用时机：在调用eglMakeCurrent之前进行前置检查
        if (mEGLDisplay == EGL14.EGL_NO_DISPLAY) {
            // called makeCurrent() before create?
            // 📝 提示：在创建之前就调用了makeCurrent()
            Log.d(TAG, "NOTE: makeCurrent w/o display");
        }
        // 🎯 eglMakeCurrent：绑定EGL上下文，分别指定绘制和读取表面（高级绑定操作！）
        // 💡 为什么调用：允许使用不同的surface进行绘制和读取操作（如渲染到A，从B读取像素）
        // 💡 参数说明：
        //    - mEGLDisplay：EGL显示设备连接
        //    - drawSurface：绘制表面（OpenGL绘制命令的目标）
        //    - readSurface：读取表面（glReadPixels等读取操作的来源）
        //    - mEGLContext：要绑定的渲染上下文
        // 💡 使用时机：需要在不同surface之间进行渲染和读取时调用
        if (!EGL14.eglMakeCurrent(mEGLDisplay, drawSurface, readSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent(draw,read) failed");
        }
    }

    /**
     * Makes no context current.
     * 
     * 🚫 取消当前上下文绑定
     * 💡 调用后没有上下文是当前的，OpenGL操作将无效
     */
    public void makeNothingCurrent() {
        // 🚫 eglMakeCurrent：解绑所有上下文（设置为"无上下文"状态）
        // 💡 为什么调用：释放当前线程对EGL上下文的绑定，允许其他线程使用该上下文
        // 💡 参数说明：
        //    - mEGLDisplay：EGL显示设备连接
        //    - EGL_NO_SURFACE：无绘制表面（绘制操作将无效）
        //    - EGL_NO_SURFACE：无读取表面（读取操作将无效）
        //    - EGL_NO_CONTEXT：无渲染上下文（任何GL命令都会失败）
        // 💡 使用时机：在多线程环境下释放上下文，或在销毁上下文前的清理步骤
        if (!EGL14.eglMakeCurrent(mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * Calls eglSwapBuffers.  Use this to "publish" the current frame.
     *
     * @return false on failure
     * 
     * 🔄 调用eglSwapBuffers，将当前帧"发布"到屏幕
     * 💡 这会交换前后缓冲区，让绘制的内容显示出来
     * 
     * @param eglSurface 要交换缓冲区的EGL表面
     * @return 成功返回true，失败返回false
     */
    public boolean swapBuffers(EGLSurface eglSurface) {
        // 🔄 交换缓冲区，将绘制结果显示到屏幕
        return EGL14.eglSwapBuffers(mEGLDisplay, eglSurface);
    }

    /**
     * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
     * 
     * ⏰ 设置呈现时间戳给EGL
     * 💡 时间以纳秒为单位，用于视频编码时的时间同步
     * 
     * @param eglSurface 要设置时间戳的EGL表面
     * @param nsecs 纳秒时间戳
     */
    public void setPresentationTime(EGLSurface eglSurface, long nsecs) {
        // ⏰ 调用Android扩展API设置呈现时间
        EGLExt.eglPresentationTimeANDROID(mEGLDisplay, eglSurface, nsecs);
    }

    /**
     * Returns true if our context and the specified surface are current.
     * 
     * 🔍 检查指定的上下文和surface是否是当前的
     * 💡 用于验证OpenGL操作是否在正确的上下文中执行
     * 
     * @param eglSurface 要检查的EGL表面
     * @return 如果当前上下文和surface匹配则返回true
     */
    public boolean isCurrent(EGLSurface eglSurface) {
        // 🔍 比较当前上下文和surface
        return mEGLContext.equals(EGL14.eglGetCurrentContext()) &&
            eglSurface.equals(EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW));
    }

    /**
     * Performs a simple surface query.
     * 
     * 🔍 查询surface的属性值
     * 💡 可以查询宽度、高度等属性
     * 
     * @param eglSurface 要查询的EGL表面
     * @param what 要查询的属性类型
     * @return 查询到的属性值
     */
    public int querySurface(EGLSurface eglSurface, int what) {
        // 📦 value：存储查询结果的int数组（int[]类型，长度为1）
        // 💡 为什么定义：eglQuerySurface需要一个数组来接收查询结果
        // 💡 作用：临时存储surface属性值（宽度、高度、像素格式等）
        // 💡 使用时机：在eglQuerySurface查询时传入，查询后读取value[0]
        int[] value = new int[1];
        // 🔍 eglQuerySurface：查询EGL表面的指定属性
        // 💡 参数说明：
        //    - mEGLDisplay：EGL显示设备连接
        //    - eglSurface：要查询的EGL表面
        //    - what：要查询的属性类型（如EGL_WIDTH、EGL_HEIGHT等）
        //    - value：结果存储数组
        //    - 0：数组偏移量
        // 💡 使用时机：需要获取surface尺寸或其他属性时调用
        EGL14.eglQuerySurface(mEGLDisplay, eglSurface, what, value, 0);
        // 📤 value[0]：返回查询到的属性值
        // 💡 为什么返回value[0]：eglQuerySurface将结果存储在数组第一个元素
        // 💡 作用：将查询结果传递给调用者
        return value[0];
    }

    /**
     * Queries a string value.
     * 
     * 🔍 查询EGL字符串信息
     * 💡 可以查询EGL版本、扩展信息等
     * 
     * @param what 要查询的信息类型
     * @return 查询到的字符串
     */
    public String queryString(int what) {
        // 🔍 查询EGL字符串信息
        return EGL14.eglQueryString(mEGLDisplay, what);
    }

    /**
     * Returns the GLES version this context is configured for (currently 2 or 3).
     * 
     * 📊 获取当前上下文配置的GLES版本（2或3）
     * 
     * @return GLES版本号
     */
    public int getGlVersion() {
        // 📤 返回GLES版本号
        return mGlVersion;
    }

    /**
     * Writes the current display, context, and surface to the log.
     * 
     * 📝 将当前的display、context和surface信息写入日志
     * 💡 用于调试，查看当前OpenGL状态
     * 
     * @param msg 日志消息前缀
     */
    public static void logCurrent(String msg) {
        // 📊 display：当前线程绑定的EGL显示设备（EGLDisplay类型）
        // 💡 为什么定义：需要获取当前display用于日志输出，方便调试EGL连接状态
        // 💡 作用：存储eglGetCurrentDisplay()的查询结果
        // 💡 使用时机：在下方Log.i日志输出中拼接使用
        EGLDisplay display;
        // 🎯 context：当前线程绑定的EGL渲染上下文（EGLContext类型）
        // 💡 为什么定义：需要获取当前context用于日志输出，方便调试渲染上下文状态
        // 💡 作用：存储eglGetCurrentContext()的查询结果
        // 💡 使用时机：在下方Log.i日志输出中拼接使用
        EGLContext context;
        // 🖼️ surface：当前线程绑定的EGL绘制表面（EGLSurface类型）
        // 💡 为什么定义：需要获取当前surface用于日志输出，方便调试渲染目标状态
        // 💡 作用：存储eglGetCurrentSurface(EGL_DRAW)的查询结果
        // 💡 使用时机：在下方Log.i日志输出中拼接使用
        EGLSurface surface;

        // 🔍 eglGetCurrentDisplay：获取当前线程绑定的EGL显示设备
        // 💡 返回值：当前display对象，如果未绑定则返回EGL_NO_DISPLAY
        display = EGL14.eglGetCurrentDisplay();
        // 🔍 eglGetCurrentContext：获取当前线程绑定的EGL渲染上下文
        // 💡 返回值：当前context对象，如果未绑定则返回EGL_NO_CONTEXT
        context = EGL14.eglGetCurrentContext();
        // 🔍 eglGetCurrentSurface：获取当前线程绑定的EGL绘制表面
        // 💡 参数EGL_DRAW：指定获取绘制表面（也可以传EGL_READ获取读取表面）
        // 💡 返回值：当前surface对象，如果未绑定则返回EGL_NO_SURFACE
        surface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW);

        // 📝 Log.i：输出当前EGL状态信息日志
        // 💡 为什么输出：方便调试时确认EGL绑定是否正确
        // 💡 作用：显示当前display、context、surface的状态
        // 💡 使用时机：调试OpenGL初始化、上下文切换等问题时调用
        Log.i(TAG, "Current EGL (" + msg + "): display=" + display + ", context=" + context +
                ", surface=" + surface);
    }

    /**
     * Checks for EGL errors.  Throws an exception if an error has been raised.
     * 
     * ⚠️ 检查EGL错误，如果有错误则抛出异常
     * 💡 用于调试，确保EGL操作正确执行
     * 
     * @param msg 错误消息前缀
     */
    private void checkEglError(String msg) {
        // 📦 error：EGL错误代码（int类型）
        // 💡 为什么定义：需要保存eglGetError()的返回值用于判断是否有错误
        // 💡 作用：存储EGL错误码（EGL_SUCCESS=0x3000表示无错误）
        // 💡 使用时机：在获取错误码后，与EGL_SUCCESS比较判断是否出错
        int error;
        // 🔍 eglGetError：获取最近一次EGL操作的错误代码
        // 💡 为什么调用：EGL操作失败不会抛Java异常，需要主动检查错误码
        // 💡 返回值：EGL_SUCCESS(0x3000)表示无错误，其他值表示有错误
        // 💡 使用时机：在每次EGL API调用后检查（特别是在关键操作之后）
        if ((error = EGL14.eglGetError()) != EGL14.EGL_SUCCESS) {
            // ❌ 抛出运行时异常，包含错误代码的十六进制表示
            // 💡 为什么抛异常：EGL错误通常表示严重的初始化或配置问题
            // 💡 参数msg：操作名称（如"eglCreateContext"），帮助定位出错位置
            // 💡 Integer.toHexString：将错误码转为十六进制字符串，方便对照EGL错误码表
            throw new RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error));
        }
    }
}
