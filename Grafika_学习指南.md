# Grafika 项目代码结构学习指南

这份指南旨在帮助开发者从零开始，循序渐进地掌握 Grafika 中的 Android 图形和媒体技术。我们将学习过程分为四个阶段：从基础的 EGL 环境搭建，到复杂的视频编码和离屏渲染。

---

## 第一阶段：入门 - 夯实基础 (OpenGL ES & EGL)

在深入研究复杂的视频功能之前，必须先理解 Android 是如何管理 OpenGL ES 环境的。

### 核心目录：`com.android.grafika.gles`
这是项目的灵魂，封装了所有低层级的 OpenGL 抽象。

1.  **`EglCore.java` (最重要)**
    - **学习点**：如何初始化 EGLDisplay、EGLContext 和 EGLConfig。理解什么是“离屏上下文”以及如何进行上下文共享。
2.  **`EglSurfaceBase.java` & `WindowSurface.java`**
    - **学习点**：理解 EGLSurface 的概念。`WindowSurface` 将普通的 `Surface`（来自 SurfaceView 或 TextureView）包装成 OpenGL 可以渲染的目标。
3.  **`GlUtil.java`**
    - **学习点**：通用的编译着色器、检查错误、创建纹理的工具类。
4.  **`GlesInfoActivity.java`**
    - **实践**：运行这个 Activity，查看你的设备支持哪些 GL 版本和扩展。

---

## 第二阶段：进阶 - 视图与纹理 (View & Texture)

学习如何将 OpenGL 渲染的内容展示在 UI 上，并处理外部纹理（如摄像头）。

1.  **`TextureViewGLActivity.java`**
    - **学习点**：对比 `GLSurfaceView`。学习如何手动创建一个渲染线程，并在 `TextureView` 的 `SurfaceTexture` 上进行绘制。
2.  **`LiveCameraActivity.java`**
    - **学习点**：摄像头预览的基础。学习 `SurfaceTexture.OnFrameAvailableListener` 的用法。
3.  **`Texture2dProgram.java` & `FullFrameRect.java`**
    - **学习点**：着色器程序管理。理解普通纹理（TEXTURE_2D）与摄像头外部纹理（TEXTURE_EXTERNAL_OES）的区别。
4.  **`TextureFromCameraActivity.java`**
    - **学习点**：学习如何对摄像头纹理进行缩放、旋转和裁剪。

---

## 第三阶段：核心 - 视频编码与 FBO (Encoding & FBO)

这是 Grafika 最具价值的部分，讲解了如何高效地录制屏幕或摄像头。

1.  **`VideoEncoderCore.java`**
    - **学习点**：直接操作 `MediaCodec`。学习如何配置编码器、处理输出缓冲区以及使用 `MediaMuxer` 封装成 MP4。
2.  **`TextureMovieEncoder.java`**
    - **学习点**：高效率录制的典范。学习如何使用“生产者-消费者”模型，在独立线程中进行录制而不阻塞 UI。
3.  **`RecordFBOActivity.java` (教科书级示例)**
    - **学习点**：**帧缓冲对象 (FBO)**。
    - **核心逻辑**：场景先画到 FBO 纹理一次，然后该纹理被“复用”：一份画到屏幕给用户看，一份画到编码器的输入 Surface 进行录制。这是最节省性能的做法。
4.  **`ContinuousCaptureActivity.java`**
    - **学习点**：**循环缓冲区录制**。像行车记录仪一样，只保留最后几秒的视频。理解 `CircularEncoder` 的内存管理。

---

## 第四阶段：高手 - 复杂场景与性能调优

处理多流异步、硬件同步等高级话题。

1.  **`DoubleDecodeActivity.java`**
    - **学习点**：同时解码两个视频。处理多个 `MediaCodec` 实例和线程同步。
2.  **`MultiSurfaceActivity.java`**
    - **学习点**：理解 Android 窗口系统的层级。学习如何叠加多个 `SurfaceView`。
3.  **`ScheduledSwapActivity.java`**
    - **学习点**：精确的时间戳控制。学习使用 `eglPresentationTimeANDROID` 来控制每一帧显示的绝对时间。
4.  **`HardwareScalerActivity.java`**
    - **学习点**：动态调整 Surface 大小以平衡性能与画质（类似手游的动态分辨率）。

---

## 学习建议

1.  **先跑通 Demo**：先在真机上运行一遍所有功能，感受其效果。
2.  **从日志入手**：Grafika 的日志非常详尽，通过 Logcat 观察各个组件的初始化和销毁顺序。
3.  **代码移植**：尝试将 `gles` 目录下的工具类拷贝到你自己的项目中，实现一个简单的 OpenGL 滤镜预览。
4.  **关注线程**：图形编程中，绝大部分错误都源于“在错误的线程调用了 GL 指令”。重点观察 Grafika 是如何切换上下文（`makeCurrent`）的。
