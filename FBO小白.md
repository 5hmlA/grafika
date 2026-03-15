**整体主流程（适合小白理解的简化版）**

在 Android 上用 **FBO（Framebuffer Object，帧缓冲对象）** 进行视频录制，最经典、最高效的方式是 **离屏渲染 + MediaCodec 硬编码**。核心思路是：

1. 先把你要录制的画面（比如动画、三角形、摄像头纹理等）渲染到 **FBO**（离屏缓冲区）里，这样只需绘制一次。
2. 然后把 FBO 上的纹理“复制/绘制”到两个地方：
    - 显示屏幕（预览）。
    - MediaCodec 的输入 Surface（录制用）。
3. MediaCodec 把 Surface 上的帧自动编码成 H.264，配合 MediaMuxer 封装成 MP4 文件。

这样避免了“重复绘制两次”的浪费（性能高），特别适合游戏录制、滤镜视频、OpenGL 动画录制。

推荐学习 **Google 官方 Grafika 示例** 中的 `RecordFBOActivity`（它就是专门演示 FBO 录制的）。下面我用这个思路，**一步一步手把手讲解**，每个步骤都配**完整可用的代码片段**（基于 OpenGL ES 2.0/3.0 + MediaCodec API 18+）。

---

### **步骤 1: 初始化 EGL 环境 + 渲染线程（最基础）**

必须在**独立线程**里做 OpenGL 操作（不能在 UI 线程）。用 `EglCore`、`WindowSurface` 等辅助类（Grafika 里就有，你可以直接复制或自己写简单版）。

```java
// RenderThread.java （渲染线程核心）
private void prepareGl(Surface surface) {
    mEglCore = new EglCore(null, EglCore.FLAG_RECORDABLE | EglCore.FLAG_TRY_GLES3);
    mWindowSurface = new WindowSurface(mEglCore, surface, false);  // 显示用的 Surface
    mWindowSurface.makeCurrent();

    // 准备全屏绘制工具（后面用来把 FBO 纹理画到屏幕/编码器）
    mFullScreen = new FullFrameRect(new Texture2dProgram(Texture2dProgram.ProgramType.TEXTURE_2D));

    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    GLES20.glDisable(GLES20.GL_DEPTH_TEST);
}
```

**为什么重要？**  
EGL 是 OpenGL 和 Android Surface 的桥梁。`makeCurrent()` 后才能调用 gl* 函数。

---

### **步骤 2: 创建 FBO（离屏渲染核心！）**

这就是你问的 **FBO**！它包含一个颜色纹理（存画面）和深度缓冲。

```java
private int mFramebuffer;       // FBO ID
private int mOffscreenTexture;  // FBO 绑定的纹理
private int mDepthBuffer;

private void prepareFramebuffer(int width, int height) {
    int[] values = new int[1];

    // 1. 创建颜色纹理
    GLES20.glGenTextures(1, values, 0);
    mOffscreenTexture = values[0];
    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mOffscreenTexture);
    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
    GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);

    // 2. 创建 FBO
    GLES20.glGenFramebuffers(1, values, 0);
    mFramebuffer = values[0];
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);

    // 3. 创建深度缓冲（可选，但推荐）
    GLES20.glGenRenderbuffers(1, values, 0);
    mDepthBuffer = values[0];
    GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, mDepthBuffer);
    GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, width, height);
    GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
            GLES20.GL_RENDERBUFFER, mDepthBuffer);

    // 4. 把纹理挂到 FBO 上
    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, mOffscreenTexture, 0);

    // 检查是否完整
    int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
    if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
        throw new RuntimeException("FBO 创建失败! status=" + status);
    }

    // 切换回默认帧缓冲（屏幕）
    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
}
```

**调用时机**：在 `surfaceChanged` 里调用（宽高变化时）。

**小白提示**：FBO 就像一张“隐形画布”，你画完后可以把这张画（纹理）拿去用很多次。

---

### **步骤 3: 设置 MediaCodec 编码器 + 编码 Surface**

```java
// 在录制开始前
private void startRecording(File outputFile) {
    mVideoEncoder = new TextureMovieEncoder();  // Grafika 里的封装类
    mVideoEncoder.startRecording(new TextureMovieEncoder.EncoderConfig(
            outputFile, 1280, 720, 4000000, mEglCore.getContext()));

    // 创建编码专用的 EGLSurface
    mInputWindowSurface = new WindowSurface(mEglCore, mVideoEncoder.getInputSurface(), true);
}
```

**MediaCodec 配置（核心代码）**：
```java
MediaFormat format = MediaFormat.createVideoFormat("video/avc", width, height);
format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
format.setInteger(MediaFormat.KEY_BIT_RATE, 4000000);
format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

MediaCodec encoder = MediaCodec.createEncoderByType("video/avc");
encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
Surface inputSurface = encoder.createInputSurface();  // 这就是我们要绑定的 Surface
encoder.start();
```

---

### **步骤 4: 渲染循环里使用 FBO（最关键的一步）**

每帧的绘制逻辑（在 `doFrame()` 或 `onDrawFrame`）：

```java
private void doFrame(long timeStampNanos) {
    updateScene();  // 更新三角形位置、动画等

    if (正在录制) {
        // === 1. 先画到 FBO（离屏）===
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, mFramebuffer);
        drawScene();   // 你的正常绘制代码：画三角形、贴图等
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // === 2. 把 FBO 纹理画到屏幕预览 ===
        mWindowSurface.makeCurrent();
        mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);  // 全屏画纹理
        mWindowSurface.swapBuffers();

        // === 3. 把同一个 FBO 纹理画到编码器 Surface ===
        mInputWindowSurface.makeCurrent();
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        // 可以调整视口让录制画面比例正确
        GLES20.glViewport(videoRect.left, videoRect.top, videoRect.width(), videoRect.height());
        mFullScreen.drawFrame(mOffscreenTexture, mIdentityMatrix);

        mInputWindowSurface.setPresentationTime(timeStampNanos);
        mInputWindowSurface.swapBuffers();   // 关键！告诉 MediaCodec “这一帧好了”
        
        // 切回屏幕 Surface
        mWindowSurface.makeCurrent();
    } else {
        // 仅预览时直接画到屏幕
        drawScene();
        mWindowSurface.swapBuffers();
    }
}
```

**小白提示**：`swapBuffers()` 是“提交帧”的信号。编码器收到后自动编码。

---

### **步骤 5: 启动/停止录制 + 封装 MP4**

```java
// 开始录制
mRecordingEnabled = true;
mVideoEncoder.startRecording(...);

// 停止录制
mRecordingEnabled = false;
mVideoEncoder.stopRecording();
mVideoEncoder.waitForStop();  // 等待编码完成

// MediaMuxer 部分（TextureMovieEncoder 内部已处理）
// 如果你自己写：encoder.dequeueOutputBuffer + muxer.writeSampleData
```

---

### **完整小例子推荐（直接可跑）**

1. 下载 **Grafika**（GitHub: google/grafika） → 打开 `RecordFBOActivity`。
2. 里面有三种录制方式（RECMETHOD_DRAW_TWICE / FBO / BLIT），FBO 就是我们讲的最高效方式。
3. 如果你想加摄像头：把 `SurfaceTexture`（OES 纹理）先画到 FBO，再用上面流程录制。

**性能小贴士**：
- 用 GLES3.0 的 `glBlitFramebuffer` 可以更快（Grafika 已支持）。
- 录制分辨率建议 720p 或 1080p，帧率 30fps。
- 一定要在渲染线程释放：`glDeleteFramebuffers`、`glDeleteTextures`。

**常见坑（小白必看）**：
- FBO 必须在 `glBindFramebuffer` 后检查 `GL_FRAMEBUFFER_COMPLETE`。
- EGLContext 要共享（如果预览和录制用不同线程）。
- MediaCodec 的 `COLOR_FormatSurface` 是 Android 4.3+ 才有的高效方式。