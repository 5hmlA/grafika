Grafika
=======

欢迎来到 Grafika，这是 Android 图形和媒体技巧的实验场。

Grafika 是：
- 各种练习图形功能的技巧集合。
- 一个 SDK 应用，针对 API 18 (Android 4.3) 开发。虽然某些代码可能在旧版本 Android 上运行，但也会进行一些零星的工作来支持它们。
- 开源（Apache 2 许可证），版权归 Google 所有。因此，您可以根据许可证条款使用代码（见 "LICENSE"）。
- 一个永远在进行中的工作。每当有需要时就会更新。

但是：
- 它不稳定。
- 它未经打磨或充分测试。UI 可能会很丑且尴尬。
- 它不旨在演示做事的“正确方式”。代码对边缘情况的处理可能很差或根本没有处理。日志通常保持在适度详细的级别。
- 它几乎没有文档。
- 它不是 Android 开源项目（AOSP）的一部分。即使您签署了 AOSP CLA，我们也无法接受对 Grafika 的贡献。
- 它**不是官方的 GOOGLE 产品**。它只是在公司时间和设备上拼凑起来的一堆东西。
- 它通常不受支持。

在某种程度上，Grafika 可以被视为 [Android 系统级图形架构](http://source.android.com/devices/graphics/architecture.html) 文档的配套项目。该文档解释了示例所依赖的技术，并使用 Grafika 的一些 Activity 作为示例。如果您想了解这里的代码是如何工作的，请先阅读该文档。

与 http://www.bigflake.com/mediacodec/ 上的代码有一些重合。那里的代码主要由“无界面”的 CTS 测试组成，旨在实现健壮、自包含，并很大程度上独立于通常的应用生命周期问题。Grafika 是一个常规应用，并努力正确处理应用问题（例如不在 UI 线程上做大量工作）。

功能会根据需要添加到 Grafika 中，通常是为了响应开发者对平台正确性或性能问题的投诉（要么是为了确认问题存在，要么是为了演示一种可行的方法）。

在以下两个领域投入了较多精力：
- **线程安全**。在处理媒体类时，非常容易以微妙且危险的方式产生线程冲突。（请阅读 [Android SMP 入门](http://developer.android.com/training/articles/smp.html) 以获取有关该问题的详细介绍。）GL/EGL 对线程本地存储（Thread-local storage）的依赖也无济于事。线程问题经常在源代码的注释中被提及。
- **垃圾回收（GC）**。GC 停顿会导致掉帧（jank）。理想情况下，所有 Activity 在“稳定状态”下都不会进行任何分配。在切换模式时可能会发生分配，例如开始或停止录制。

所有代码均使用 Java 编程语言编写 —— 未使用 NDK。

Grafika 第一次启动时，会生成两个视频（gen-eight-rects，gen-sliders）。如果您想尝试生成代码，可以通过主 Activity 菜单（"Regenerate content"）重新生成它们。

当前功能
----------------

[* 播放视频 (TextureView)](app/src/main/java/com/android/grafika/PlayMovieActivity.java)。播放 MP4 文件中的视频轨道。
- 仅识别 `/data/data/com.android.grafika/files/` 中的文件。所有创建视频的 Activity 都会将文件保存在那里。您还会发现两个自动生成的视频（gen-eight-rects.mp4 和 gen-slides.mp4）。
- 默认情况下，视频播放一次，速率与录制时相同。您可以使用复选框循环播放和/或以 60 FPS 的固定速率播放帧。
- 使用 `TextureView` 进行输出。
- 名称以星号开头，因此它位于 Activity 列表的最顶部。

[连续捕获](app/src/main/java/com/android/grafika/ContinuousCaptureActivity.java)。将视频存储在循环缓冲区中，点击“捕获”按钮时保存。（以前称为 "Constant capture"。）
- 目前硬编码为尝试从摄像头以 6MB/秒的速度捕获 7 秒视频，首选 15fps 720p。这需要约 5MB 的缓冲区大小。
- 显示当前保存在缓冲区中的帧的时间跨度。点击“捕获”时保存的实际时间跨度将略小于显示的时间跨度，因为我们必须从同步帧（配置为每秒出现一次）开始输出。
- 输出为仅视频的 MP4 文件（"constant-capture.mp4"）。视频始终为 1280x720，通常与摄像头提供的分辨率匹配；如果不匹配，录制的视频长宽比将不正确。

[双重解码](app/src/main/java/com/android/grafika/DoubleDecodeActivity.java)。将两个视频流并排解码到一对 `TextureView` 中。
- 播放两个自动生成的视频。请注意它们的播放速率不同。
- 屏幕旋转时视频解码器不会停止。我们保留 `SurfaceTexture` 并将其附加到新的 `TextureView`。这有助于避免昂贵的解码器重新配置。如果您退出 Activity，解码器*会*停止，这样我们就不会无限期地占用硬件解码器资源。（按电源键关闭屏幕时它也不会停止，这不利于电池续航，但如果您正向外部显示器输出或播放器也处理音频，这可能会很方便。）
- 与 Grafika 中的大多数 Activity 不同，它为纵向和横向提供了不同的布局。视频会缩放以适应屏幕。

[硬件缩放器练习](app/src/main/java/com/android/grafika/HardwareScalerActivity.java)。展示带有即时 Surface 大小更改的 GL 渲染。
- 此功能背后的动机在开发者博客文章中有所描述：http://android-developers.blogspot.com/2013/09/using-hardware-scaler-for-performance.html
- 更改大小时，您会看到一帧渲染不正确。这是因为渲染大小是在 "surface changed" 回调中调整的，但 Surface 的实际大小直到我们锁定下一个缓冲区时才会改变。这很容易修复（留作读者练习）。

[实时摄像头 (TextureView)](app/src/main/java/com/android/grafika/LiveCameraActivity.java)。将摄像头预览定向到 `TextureView`。
- 这几乎逐字取自 [TextureView](http://developer.android.com/reference/android/view/TextureView.html) 文档。
- 使用默认（后置）摄像头。如果设备没有默认摄像头（例如 Nexus 7 (2012)），应用将崩溃。

[多 Surface 测试](app/src/main/java/com/android/grafika/MultiSurfaceTest.java)。具有三个重叠 SurfaceView 的简单 Activity，其中一个标记为安全（secure）。
- 用于检查具有多个静态层的 HWC 行为，以及具有安全 Surface 的截屏/录屏行为。（如果您录制屏幕，其中一个圆圈应该消失，截屏应该只显示黑色。）
- 如果点击 "bounce" 按钮，非安全层上的圆圈将动起来。它会尽可能快地更新，这可能慢于显示刷新率，因为圆圈是用软件渲染的。帧率将记录在 logcat 中。

[播放视频 (SurfaceView)](app/src/main/java/com/android/grafika/PlayMovieSurfaceActivity.java)。播放 MP4 文件中的视频轨道。
- 工作方式非常类似于 "播放视频 (TextureView)"，尽管并非所有功能都存在。有关使用 SurfaceView 的优点列表，请参阅类注释。

[录制 GL 应用](app/src/main/java/com/android/grafika/RecordFBOActivity.java)。使用 OpenGL ES 同时绘制到显示屏和视频编码器，利用帧缓冲对象（FBO）避免重复渲染。
- 它可以通过三种不同的方式写入视频编码器：(1) 绘制两次；(2) 离屏绘制并 blit 两次；(3) 屏幕绘制并 blit 帧缓冲。#3 目前还不可用。
- 渲染器由 Choreographer 触发，每个 vsync 更新一次。如果我们落后太多，我们将跳帧。这会通过屏幕上的掉帧计数器和边框闪烁来体现。通常您不会在动画中看到任何卡顿，因为我们没有跳过物体移动，只是跳过了渲染。
- 编码器每隔一帧接收一次数据，因此在典型设备上，录制的输出将是 ~30fps 而不是 ~60fps。
- 录制内容会进行黑边处理（letter- 或 pillar-boxed）以保持与显示屏匹配的长宽比，因此横屏与竖屏录制的结果会不同。
- 输出为仅视频的 MP4 文件（"fbo-gl-recording.mp4"）。

[使用 MediaProjectionManager 录制屏幕](app/src/main/java/com/android/grafika/ScreenRecordActivity.java)。
使用 MediaProjectionManager 将屏幕录制为电影。此 API 需要 API 级别 23 (Marshmallow) 或更高。

[计划交换 (Scheduled swap)](app/src/main/java/com/android/grafika/ScheduledSwapActivity.java)。练习一项 SurfaceFlinger 功能，该功能允许您提交要在特定时间显示的缓冲区。
- 需要 API 19 (Android 4.4 "KitKat") 才能实现其预期功能。在 API 18 上，目前的实现在肉眼看来并没有什么不同。
- 您可以配置帧传递时序（例如 24fps 使用 3-2 模式）以及提前多少计划帧。选择 "ASAP" 将禁用计划。
- 使用带有标签 `sched gfx view --app=com.android.grafika` 的 systrace 来观察效果。
- 当应用对时序不满意时，移动的方块会改变颜色。

[显示 + 捕获摄像头](app/src/main/java/com/android/grafika/CameraCaptureActivity.java)。尝试从前置摄像头以 720p 录制，同时显示预览并进行录制。
- 使用录制按钮切换录制的开始和停止。
- 录制持续到停止。如果您退出并返回，录制将重新开始，并带有一个实时时间间隙。如果您在录制时尝试播放视频，您将看到一个不完整的文件（并可能导致播放视频的 Activity 崩溃）。
- 录制的视频被缩放到 640x480，因此看起来可能会被挤压。真正的应用要么将录制大小设置为等于摄像头输入大小，要么在将帧渲染到编码器时通过黑边处理来纠正长宽比。
- 您可以选择要应用于预览的滤镜；录制会使用同一套滤镜（`CameraPreviewFilter` + `TextureMovieEncoder#setCameraFilterMode`）。用于滤镜的着色器未经过优化，但在大多数设备上似乎性能良好（原版 Nexus 7 (2012) 是一个显著的例外）。
- 输出为仅视频的 MP4 文件（"camera-test.mp4"）。

[TextureView 中的简单 Canvas](app/src/main/java/com/android/grafika/TextureViewCanvasActivity.java)。练习使用 `Canvas` 在 `TextureView` 中进行软件渲染。
- 尽可能快地渲染。因为它使用软件渲染，所以运行速度可能比 "TextureView 中的简单 GL" Activity 慢。
- 每 64 帧切换一次脏矩形（dirty rect）的使用。启用时，脏矩形水平延伸穿过屏幕。

[TextureView 中的简单 GL](app/src/main/java/com/android/grafika/TextureViewGLActivity.java)。演示在 `TextureView` 中简单使用 GLES，而不是 `GLSurfaceView`。
- 尽可能快地渲染。在大多数设备上，它将超过 60fps 并剧烈闪烁，但在 4.4 ("KitKat") 中，一个 bug 会阻止系统掉帧。

[来自摄像头的纹理](app/src/main/java/com/android/grafika/TextureFromCameraActivity.java)。使用 GLES 纹理渲染摄像头预览输出。
- 调整滑块以设置大小、旋转和缩放。触摸其他任何地方以将矩形居中于触摸点。

[彩色条](app/src/main/java/com/android/grafika/ColorBarActivity.java)。显示 RGB 彩色条。

[OpenGL ES 信息](app/src/main/java/com/android/grafika/GlesInfoActivity.java)。转储版本信息和扩展列表。
- "Save" 按钮将输出副本写入应用的文件夹。

[glTexImage2D 速度测试](app/src/main/java/com/android/grafika/TextureUploadActivity.java)。对使用 `glTexImage2D()` 上传 512x512 RGBA 纹理所需时间的简单、非科学测量。

[glReadPixels 速度测试](app/src/main/java/com/android/grafika/ReadPixelsActivity.java)。对 `glReadPixels()` 读取 720p 帧所需时间的简单、非科学测量。


已知问题
------------

- 运行 Android 4.3 (JWR67E) 的 Nexus 4：如果您选择其中一个滤镜模式，"显示 + 捕获摄像头"会崩溃。似乎是一个驱动程序 bug（Adreno "Internal compiler error"）。


功能和修复构想
-------------------

排名不分先后。

- 在性能或延迟至关重要的地方，停止使用 AsyncTask。
- 为摄像头添加“胖像素”查看器（单个 SurfaceView；左半部分显示实时摄像头馈送和全景矩形，右半部分显示 8x 像素）。
- 更改 "TextureView 中的简单 GL" 的动画。或者添加癫痫警告。
- 从一个视频淡入淡出到另一个视频，并录制结果。允许指定分辨率（例如 QVGA, 720p, 1080p）并相应地生成。
- 为视频播放器添加功能，例如用于随机访问的滑块，以及用于单帧前进/后退的按钮（需要寻道到最近的同步帧并解码帧直到到达目标）。
- 将一系列 PNG 图像转换为视频。
- 播放具有不同特性的一系列 MP4 文件中的连续视频。可能需要“预加载”下一部视频以保持播放无缝。
- 尝试 glReadPixels() 的替代方案。添加 PBO 速度测试。（在 Java 中似乎没有办法使用 eglCreateImageKHR。）
- 研究 ImageReader 类（要求 API 19）。
- 弄清楚为什么“双重解码”播放有时会卡顿。
- 在 "TextureView 中的简单 GL" 中添加 fps 指示器。
- 从麦克风捕获音频，进行录制并合成（mux）。
- 同时在前后摄像头上启用预览，并并排显示它们。（除非在特定设备上，否则这似乎是不可能的。）
- 添加一个测试，使用单个渲染器线程中的不同 EGLContext 渲染到两个不同的 TextureView。
