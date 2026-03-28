package com.android.grafika;

import android.opengl.GLES20;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.AdapterView.OnItemSelectedListener;
import android.app.Activity;
import com.android.grafika.gles.EglCore;
import com.android.grafika.gles.WindowSurface;
import java.io.File;
import java.io.IOException;
import com.google.grafika.R;

/**
 * Play a movie from a file on disk.  Output goes to a SurfaceView.
 * <p>
 * This is very similar to PlayMovieActivity, but the output goes to a SurfaceView instead of
 * a TextureView.
 * 
 * 🎬 从磁盘文件播放视频，输出到SurfaceView
 * 💡 与PlayMovieActivity类似，但使用SurfaceView而不是TextureView
 * 💡 SurfaceView的优势：系统合成器直接处理，更省电
 * 💡 TextureView的优势：可以自由缩放和旋转
 */
public class PlayMovieSurfaceActivity extends Activity implements OnItemSelectedListener,
        SurfaceHolder.Callback, MoviePlayer.PlayerFeedback {
    private static final String TAG = MainActivity.TAG;

    private SurfaceView mSurfaceView;           // 🖼️ SurfaceView视图
    private String[] mMovieFiles;               // 📁 视频文件列表
    private int mSelectedMovie;                 // 🔢 选中的视频索引
    private boolean mShowStopLabel;             // 🏷️ 是否显示停止标签
    private MoviePlayer.PlayTask mPlayTask;     // 🎬 播放任务
    private boolean mSurfaceHolderReady = false; // ✅ SurfaceHolder是否就绪

    /**
     * 🎨 获取布局ID，子类可覆盖
     * 💡 返回Activity使用的布局资源ID
     * 💡 作用：允许子类使用不同的布局文件
     *
     * @return 布局资源ID（R.layout.activity_play_movie_surface）
     */
    protected int getContentViewId() {
        return R.layout.activity_play_movie_surface;
    }

    /**
     * 🔧 Activity创建时调用
     * 💡 初始化视图、SurfaceView回调、文件选择下拉列表和控件状态
     * 💡 调用时机：Activity首次创建或系统重新创建时
     *
     * @param savedInstanceState 保存的状态数据（用于恢复Activity状态）
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📞 调用父类onCreate，执行基础初始化
        // 💡 必须首先调用，否则Activity无法正常运行
        // ⏰ 使用时机：Activity创建时首先调用
        super.onCreate(savedInstanceState);

        // 🎨 设置Activity的布局文件
        // 💡 作用：加载XML布局文件到当前Activity
        // 💡 时机：必须在findViewById之前调用
        setContentView(getContentViewId());

        // 🖼️ 获取SurfaceView控件
        // 💡 作用：通过ID找到布局中的SurfaceView
        // 💡 后续用于显示视频画面
        mSurfaceView = (SurfaceView) findViewById(R.id.playMovie_surface);

        // 🎧 注册SurfaceHolder回调监听器
        // 💡 作用：监听Surface的创建、变化和销毁事件
        // 💡 时机：获取SurfaceView后立即注册
        mSurfaceView.getHolder().addCallback(this);

        // 📋 获取文件选择下拉列表控件
        // 💡 作用：用于用户选择要播放的视频文件
        Spinner spinner = (Spinner) findViewById(R.id.playMovieFile_spinner);

        // 📁 获取所有mp4文件列表
        // 💡 作用：扫描应用私有目录下的mp4文件
        // 💡 存储在mMovieFiles中，供后续选择使用
        mMovieFiles = MiscUtils.getFiles(getFilesDir(), "*.mp4");

        // 📋 创建数组适配器
        // 💡 作用：将文件列表绑定到Spinner控件
        // 💡 参数：上下文、布局样式、数据源
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mMovieFiles);

        // 🎨 设置下拉列表的布局样式
        // 💡 作用：定义下拉菜单中每项的显示样式
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // 🔌 将适配器应用到Spinner
        // 💡 作用：让Spinner显示文件列表
        spinner.setAdapter(adapter);

        // 🎧 设置Spinner选择监听器
        // 💡 作用：当用户选择不同文件时触发回调
        spinner.setOnItemSelectedListener(this);

        // 🔄 更新界面控件状态
        // 💡 作用：根据当前状态设置按钮文本和启用状态
        updateControls();
    }

    /**
     * 🔄 Activity恢复时调用
     * 💡 当Activity从后台回到前台时触发
     * 💡 作用：记录Activity生命周期状态，便于调试
     */
    @Override
    protected void onResume() {
        // 📝 记录恢复日志（为什么：便于追踪Activity生命周期状态变化）
        // 💡 作用：输出"onResume"事件到Logcat
        // ⏰ 使用时机：Activity从暂停状态恢复时首先调用
        Log.d(TAG, "PlayMovieSurfaceActivity onResume");
        // 📞 调用父类的onResume方法（为什么：确保父类的恢复逻辑正常执行）
        // 💡 作用：执行Android基类的标准恢复流程
        // ⏰ 使用时机：记录日志后调用，遵循先自定义后父类的顺序
        super.onResume();
    }

    /**
     * ⏸️ Activity暂停时调用
     * 💡 停止播放并等待播放完全停止，防止发送帧到已销毁的视图
     * 💡 调用时机：用户离开Activity、屏幕关闭或被其他Activity覆盖时
     */
    @Override
    protected void onPause() {
        // 📝 记录暂停日志，便于调试生命周期问题
        Log.d(TAG, "PlayMovieSurfaceActivity onPause");

        // 📞 调用父类onPause，执行基础暂停逻辑
        super.onPause();

        // 🔍 检查是否有播放任务正在运行
        // 💡 作用：避免在没有播放时执行停止操作
        // 💡 时机：父类暂停后，销毁视图前检查
        if (mPlayTask != null) {
            // ⏹️ 请求停止播放
            // 💡 作用：通知播放线程停止解码和渲染
            stopPlayback();

            // ⏳ 等待播放完全停止
            // 💡 作用：确保播放线程已退出，避免发送帧到已销毁的Surface
            // 💡 时机：请求停止后必须等待，否则可能崩溃
            mPlayTask.waitForStop();
        }
    }

    /**
     * 🖼️ Surface创建时调用
     * 💡 标记SurfaceHolder已就绪
     */
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // 📝 记录Surface创建日志（为什么：便于调试Surface生命周期问题）
        // 💡 作用：输出"surfaceCreated"事件到Logcat
        // ⏰ 使用时机：Surface首次创建时首先记录
        Log.d(TAG, "surfaceCreated");

        // ✅ 标记SurfaceHolder已就绪（为什么：Surface创建后才能接收视频帧）
        // 💡 作用：更新状态标志，使播放按钮变为可用状态
        // ⏰ 使用时机：记录日志后立即设置
        mSurfaceHolderReady = true;

        // 🔄 更新界面控件状态（为什么：Surface就绪后需要启用播放按钮）
        // 💡 作用：根据mSurfaceHolderReady状态设置按钮启用/禁用
        // ⏰ 使用时机：状态标记更新后调用
        updateControls();
    }

    /**
     * 🖼️ Surface尺寸或格式变化时调用
     * 💡 作用：当Surface的尺寸或像素格式发生改变时触发
     * 💡 时机：Surface首次创建后、设备旋转或窗口大小变化时
     *
     * @param holder SurfaceHolder对象
     * @param format 新的像素格式
     * @param width 新的宽度（像素）
     * @param height 新的高度（像素）
     */
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // 📝 记录Surface变化日志（为什么：便于调试Surface生命周期问题）
        // 💡 作用：输出新的格式和尺寸信息到Logcat
        // ⏰ 使用时机：Surface尺寸或格式变化时记录
        Log.d(TAG, "surfaceChanged fmt=" + format + " size=" + width + "x" + height);
    }

    /**
     * 🖼️ Surface销毁时调用
     * 💡 当SurfaceView的Surface即将被销毁时触发
     * 💡 作用：记录Surface销毁事件，用于调试Surface生命周期
     *
     * @param holder 即将销毁的SurfaceHolder对象
     */
    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // 📝 记录Surface销毁日志（为什么：便于调试Surface生命周期问题）
        // 💡 作用：输出"Surface destroyed"事件到Logcat
        // ⏰ 使用时机：Surface即将销毁时调用
        // ⚠️ 注意：实际的播放停止逻辑在onPause()中处理，此处仅记录日志
        // 💡 原因：onPause()会在surfaceDestroyed()之前调用，已处理了停止逻辑
        Log.d(TAG, "Surface destroyed");
    }

    /**
     * 🎯 下拉列表选择事件
     * 💡 当用户在Spinner中选择不同的视频文件时调用
     * 💡 作用：更新当前选中的视频文件索引
     *
     * @param parent 父视图（Spinner下拉列表）
     * @param view 被选中的具体视图项
     * @param pos 选中项的位置索引
     * @param id 选中项的行ID
     */
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
        // 📋 获取Spinner对象（为什么：需要从Spinner获取选中位置）
        // 💡 作用：将父视图转换为Spinner类型以便获取选中项
        // ⏰ 使用时机：回调触发时首先获取
        Spinner spinner = (Spinner) parent;
        // 📝 保存选择的视频索引（为什么：后续播放时需要知道选中了哪个文件）
        // 💡 作用：记录当前选中项在mMovieFiles数组中的位置
        // ⏰ 使用时机：获取Spinner后立即保存
        mSelectedMovie = spinner.getSelectedItemPosition();
        // 📝 记录选择的视频信息（为什么：便于调试文件选择流程）
        // 💡 作用：输出选中索引和文件名到Logcat
        // ⏰ 使用时机：保存索引后记录
        Log.d(TAG, "onItemSelected: " + mSelectedMovie + " '" + mMovieFiles[mSelectedMovie] + "'");
    }

    /**
     * 🚫 当没有选择任何项时调用
     * 💡 当Spinner中没有选中任何项时触发
     * 💡 作用：处理无选择状态（当前留空，使用默认值）
     *
     * @param parent 父视图（Spinner下拉列表）
     */
    @Override public void onNothingSelected(AdapterView<?> parent) {
        // 🚫 此处留空（为什么：Spinner默认总有选中项，此回调很少触发）
        // 💡 作用：实现OnItemSelectedListener接口的必需方法
        // ⏰ 使用时机：Spinner清空选择时调用（实际很少发生）
    }

    /**
     * 🎬 "播放"/"停止"按钮点击事件处理
     * 💡 根据当前状态切换播放和停止
     * 💡 播放时：创建MoviePlayer、设置宽高比、启动播放任务
     * 💡 停止时：请求停止播放任务
     *
     * @param unused 未使用的View参数（onClick要求）
     */
    public void clickPlayStop(@SuppressWarnings("unused") View unused) {
        // 🔍 检查当前是否显示"停止"标签
        // 💡 作用：判断当前状态是"播放中"还是"已停止"
        // 💡 时机：按钮点击时立即判断
        if (mShowStopLabel) {
            // 📝 记录停止播放的日志
            // 💡 作用：便于调试播放停止流程
            Log.d(TAG, "stopping movie");

            // ⏹️ 停止播放
            // 💡 作用：请求播放任务停止，不会等待完成
            stopPlayback();
        } else {
            // 🔍 检查是否已经有播放任务在运行
            // 💡 作用：防止重复启动播放
            // 💡 时机：开始新播放前必须检查
            if (mPlayTask != null) {
                // ⚠️ 记录警告：已在播放
                Log.w(TAG, "movie already playing");
                return;
            }

            // 📝 记录开始播放的日志
            Log.d(TAG, "starting movie");

            // 🎬 创建速度控制回调
            // 💡 作用：控制视频播放速度，支持固定帧率
            // 💡 时机：每次播放前创建新的回调实例
            SpeedControlCallback callback = new SpeedControlCallback();

            // 🖼️ 获取SurfaceHolder
            // 💡 作用：获取SurfaceView的SurfaceHolder，用于获取Surface
            // 💡 时机：播放前获取，传递给MoviePlayer
            SurfaceHolder holder = mSurfaceView.getHolder();

            // 🖼️ 获取Surface对象
            // 💡 作用：获取实际的显示表面
            // 💡 用于：MoviePlayer将解码的帧渲染到此Surface
            Surface surface = holder.getSurface();

            // 🧹 清除Surface，避免上一个视频的最后一帧残留
            // 💡 作用：将Surface清空为黑色
            // 💡 时机：创建MoviePlayer之前清除
            clearSurface(surface);

            // 🎬 MoviePlayer实例
            // 💡 作用：视频解码和渲染的核心类
            // 💡 初始化为null，后续在try块中创建
            MoviePlayer player = null;

            try {
                 // 🎬 创建MoviePlayer实例
                 // 💡 作用：初始化视频解码器，准备播放
                 // 💡 参数：视频文件路径、输出Surface、速度控制回调
                 player = new MoviePlayer(
                        new File(getFilesDir(), mMovieFiles[mSelectedMovie]), surface, callback);
            } catch (IOException ioe) {
                // ❌ 记录错误日志
                // 💡 作用：捕获文件读取或解码器初始化错误
                Log.e(TAG, "Unable to play movie", ioe);

                // 🗑️ 释放Surface资源
                // 💡 作用：避免资源泄漏
                // 💡 时机：播放失败时必须释放
                surface.release();
                return;
            }

            // 📐 获取AspectFrameLayout
            // 💡 作用：用于设置视频宽高比的自定义布局
            AspectFrameLayout layout = (AspectFrameLayout) findViewById(R.id.playMovie_afl);

            // 📐 获取视频宽度
            // 💡 作用：从解码器获取视频的原始宽度
            int width = player.getVideoWidth();

            // 📐 获取视频高度
            // 💡 作用：从解码器获取视频的原始高度
            int height = player.getVideoHeight();

            // 📊 设置宽高比
            // 💡 作用：让布局自动调整以保持视频比例
            // 💡 公式：宽高比 = 宽度 / 高度
            layout.setAspectRatio((double) width / height);

            // 🎬 创建播放任务
            // 💡 作用：在后台线程中执行视频播放
            // 💡 参数：MoviePlayer实例、播放反馈回调（this）
            mPlayTask = new MoviePlayer.PlayTask(player, this);

            // 🏷️ 设置停止标签标志
            // 💡 作用：标记当前处于播放状态，按钮显示"停止"
            // 💡 时机：播放任务创建后立即设置
            mShowStopLabel = true;

            // 🔄 更新控件状态
            // 💡 作用：将按钮文本改为"停止"，禁用设置控件
            updateControls();

            // ▶️ 执行播放任务
            // 💡 作用：启动后台线程开始播放
            // 💡 时机：所有准备工作完成后最后调用
            mPlayTask.execute();
        }
    }

    /**
     * ⏹️ 请求停止播放
     * 💡 向MoviePlayer发送停止信号，不会等待播放完全停止
     * 💡 作用：通知播放线程停止解码和渲染
     */
    private void stopPlayback() {
        // 🔍 检查是否有播放任务正在运行（为什么：避免在没有播放时执行停止操作）
        // 💡 作用：防止空指针异常
        // ⏰ 使用时机：停止前首先检查
        if (mPlayTask != null) {
            // ⏹️ 请求停止播放（为什么：需要通知播放线程退出）
            // 💡 作用：设置停止标志，播放线程会在下一次循环时退出
            // ⏰ 使用时机：检测到有播放任务后立即调用
            mPlayTask.requestStop();
        }
    }

    /**
     * 🎬 播放停止回调
     * 💡 实现MoviePlayer.PlayerFeedback接口
     * 💡 当视频播放结束或被停止时由MoviePlayer调用
     * 💡 作用：更新UI状态，清除播放任务引用
     */
    @Override
    public void playbackStopped() {
        // 📝 记录播放停止日志（为什么：便于调试播放停止流程）
        // 💡 作用：输出"playback stopped"到Logcat
        // ⏰ 使用时机：回调触发时首先记录
        Log.d(TAG, "playback stopped");
        // 🏷️ 清除停止标签标志（为什么：播放已停止，按钮应显示"播放"）
        // 💡 作用：标记当前处于停止状态
        // ⏰ 使用时机：记录日志后更新状态
        mShowStopLabel = false;
        // 🗑️ 清空播放任务引用（为什么：释放播放任务对象，允许GC回收）
        // 💡 作用：断开与MoviePlayer的引用关系
        // ⏰ 使用时机：更新UI状态前清除引用
        mPlayTask = null;
        // 🔄 更新控件状态（为什么：UI需要反映当前已停止的状态）
        // 💡 作用：将按钮文本改为"播放"，启用设置控件
        // ⏰ 使用时机：所有状态更新后最后调用
        updateControls();
    }

    /**
     * 🔄 更新屏幕控件状态
     * 💡 根据当前播放状态更新按钮文本和启用状态
     * 💡 作用：保持UI状态与内部状态一致
     */
    private void updateControls() {
        // 🔘 获取播放/停止按钮引用（为什么：需要更新按钮的文本和启用状态）
        // 💡 作用：通过ID找到布局中的按钮控件
        // ⏰ 使用时机：方法开头获取，后续操作都使用此引用
        Button play = (Button) findViewById(R.id.play_stop_button);
        // 🔍 根据播放状态设置按钮文本（为什么：播放中显示"停止"，停止时显示"播放"）
        // 💡 作用：告知用户当前状态和可执行的操作
        // ⏰ 使用时机：获取按钮引用后立即设置
        if (mShowStopLabel) {
            // 🛑 显示"停止"文本（为什么：当前处于播放状态）
            // 💡 作用：提示用户点击可停止播放
            play.setText(R.string.stop_button_text);
        } else {
            // ▶️ 显示"播放"文本（为什么：当前处于停止状态）
            // 💡 作用：提示用户点击可开始播放
            play.setText(R.string.play_button_text);
        }
        // 🔍 根据SurfaceHolder状态启用/禁用按钮（为什么：SurfaceHolder未就绪时不能播放）
        // 💡 作用：只有SurfaceHolder准备就绪才能播放视频
        // ⏰ 使用时机：设置文本后设置启用状态
        play.setEnabled(mSurfaceHolderReady);
    }

    /**
     * 🧹 清除播放Surface为黑色
     * 💡 使用OpenGL ES清除（不是Canvas），确保完全清空
     * 💡 作用：防止上一个视频的最后一帧残留显示
     *
     * @param surface 需要清除的Surface对象
     */
    private void clearSurface(Surface surface) {
        // 🎮 创建EGL核心对象（使用默认配置）
        // 💡 作用：初始化EGL环境，用于OpenGL ES渲染
        // 💡 时机：清除Surface前创建，清除后释放
        EglCore eglCore = new EglCore();

        // 🖼️ 创建窗口Surface，关联到传入的Surface
        // 💡 作用：将EGL与Android Surface连接
        // 💡 参数：EGL核心、目标Surface、是否释放时销毁Surface（false不销毁）
        WindowSurface win = new WindowSurface(eglCore, surface, false);

        // 🔌 激活EGL上下文
        // 💡 作用：将当前线程与EGL上下文关联，使GL命令可用
        // 💡 时机：Surface创建后，GL命令前必须调用
        win.makeCurrent();

        // 🎨 设置清除颜色为黑色（RGBA全0）
        // 💡 作用：指定glClear使用的填充颜色
        // 💡 参数：红=0, 绿=0, 蓝=0, 透明度=0（完全透明黑色）
        GLES20.glClearColor(0, 0, 0, 0);

        // 🧹 执行颜色缓冲区清除
        // 💡 作用：用glClearColor设置的颜色填充整个Surface
        // 💡 参数：GL_COLOR_BUFFER_BIT表示清除颜色缓冲区
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        // 🔄 交换缓冲区，将清除结果显示到屏幕
        // 💡 作用：将后缓冲区的内容提交到前缓冲区显示
        // 💡 时机：GL绘制命令完成后必须调用
        win.swapBuffers();

        // 🗑️ 释放窗口Surface资源
        // 💡 作用：清理EGL窗口占用的资源
        // 💡 时机：清除完成后立即释放
        win.release();

        // 🗑️ 释放EGL核心资源
        // 💡 作用：清理EGL上下文占用的资源
        // 💡 时机：窗口Surface释放后释放
        eglCore.release();
    }
}
