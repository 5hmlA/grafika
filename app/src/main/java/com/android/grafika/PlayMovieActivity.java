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

// 📚 导入需要的类库
import android.os.Bundle;                    // 🎁 Bundle：用于在Activity之间传递数据
import android.app.Activity;                 // 📱 Activity：Android活动基类
import android.graphics.Matrix;              // 📐 Matrix：矩阵变换类
import android.graphics.SurfaceTexture;      // 🖼️ SurfaceTexture：用于将图像流转换为OpenGL纹理
import android.util.Log;                     // 📝 Log：日志工具
import android.view.Surface;                 // 🖼️ Surface：显示表面
import android.view.TextureView;             // 🖼️ TextureView：用于显示纹理内容的视图
import android.view.View;                    // 👁️ View：所有UI组件的基类
import android.widget.AdapterView;           // 🎯 AdapterView：适配器视图基类
import android.widget.ArrayAdapter;          // 📋 ArrayAdapter：数组适配器
import android.widget.Button;                // 🔘 Button：按钮组件
import android.widget.CheckBox;              // ☑️ CheckBox：复选框组件
import android.widget.Spinner;               // 📋 Spinner：下拉列表组件
import android.widget.AdapterView.OnItemSelectedListener;  // 🎯 列表项选择监听器

import java.io.File;                         // 📁 File：文件类
import java.io.IOException;                  // ⚠️ IOException：IO异常
import com.google.grafika.R;                 // 🎨 R：资源文件生成的类

/**
 * Play a movie from a file on disk.  Output goes to a TextureView.
 * <p>
 * Currently video-only.
 * <p>
 * Contrast with PlayMovieSurfaceActivity, which uses a SurfaceView.  Much of the code is
 * the same, but here we can handle the aspect ratio adjustment with a simple matrix,
 * rather than a custom layout.
 * <p>
 * TODO: investigate crash when screen is rotated while movie is playing (need
 *       to have onPause() wait for playback to stop)
 * 
 * 🎬 从磁盘文件播放视频。输出到TextureView。
 * 📹 目前仅支持视频。
 * 💡 对比PlayMovieSurfaceActivity（使用SurfaceView），代码大部分相同，
 *    但这里可以用简单的矩阵处理宽高比调整，而不需要自定义布局。
 * ⚠️ 待办事项：研究屏幕旋转时视频播放崩溃的问题（需要在onPause()中等待播放停止）
 */
public class PlayMovieActivity extends Activity implements OnItemSelectedListener,
        TextureView.SurfaceTextureListener, MoviePlayer.PlayerFeedback {
    // 🏷️ TAG：日志标签，用于在Logcat中过滤日志
    private static final String TAG = MainActivity.TAG;

    // 🖼️ mTextureView：用于显示视频的TextureView
    private TextureView mTextureView;
    // 📁 mMovieFiles：可用的视频文件数组
    private String[] mMovieFiles;
    // 🔢 mSelectedMovie：当前选中的视频索引
    private int mSelectedMovie;
    // 🔄 mShowStopLabel：是否显示"停止"标签
    private boolean mShowStopLabel;
    // 🎬 mPlayTask：视频播放任务
    private MoviePlayer.PlayTask mPlayTask;
    // ✅ mSurfaceTextureReady：SurfaceTexture是否准备就绪
    private boolean mSurfaceTextureReady = false;

    // 🔒 mStopper：用于信号停止的对象锁
    private final Object mStopper = new Object();   // used to signal stop

    /**
     * 🔧 Activity创建时调用的生命周期方法
     * 
     * @param savedInstanceState 保存的状态数据
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📞 调用父类的onCreate方法
        // 💡 作用：执行Activity基类的初始化逻辑
        // ⏰ 使用时机：Activity创建时首先调用
        super.onCreate(savedInstanceState);
        // 🎨 设置Activity的布局文件
        // 💡 作用：加载XML布局文件到当前Activity
        // ⏰ 使用时机：super.onCreate()之后立即设置
        setContentView(R.layout.activity_play_movie);

        // 🔍 获取TextureView控件
        // 💡 作用：通过ID找到布局中的TextureView
        // ⏰ 使用时机：布局设置后获取，用于后续视频渲染
        mTextureView = (TextureView) findViewById(R.id.movie_texture_view);
        // 🎧 设置SurfaceTexture监听器
        // 💡 作用：监听TextureView的SurfaceTexture生命周期
        // ⏰ 使用时机：获取TextureView后立即设置
        mTextureView.setSurfaceTextureListener(this);

        // 📋 初始化文件选择下拉列表
        // Populate file-selection spinner.
        // 💡 作用：获取Spinner控件，用于用户选择视频文件
        Spinner spinner = (Spinner) findViewById(R.id.playMovieFile_spinner);
        // 💡 需要创建一个ArrayAdapter，并指定下拉列表的布局样式
        // Need to create one of these fancy ArrayAdapter thingies, and specify the generic layout
        // for the widget itself.
        
        // 📁 获取所有mp4文件
        // 💡 作用：扫描应用私有目录下的mp4文件
        // ⏰ 使用时机：Spinner创建后获取文件列表
        mMovieFiles = MiscUtils.getFiles(getFilesDir(), "*.mp4");
        // 📋 创建数组适配器
        // 💡 作用：将文件列表绑定到Spinner控件
        // ⏰ 使用时机：获取文件列表后创建适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_spinner_item, mMovieFiles);
        // 🎨 设置下拉列表的布局样式
        // 💡 作用：定义下拉菜单中每项的显示样式
        // ⏰ 使用时机：创建适配器后设置样式
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        // 🔌 将适配器应用到下拉列表
        // Apply the adapter to the spinner.
        // 💡 作用：让Spinner显示文件列表
        // ⏰ 使用时机：设置样式后应用适配器
        spinner.setAdapter(adapter);
        // 🎧 设置下拉列表项选择监听器
        // 💡 作用：当用户选择不同文件时触发回调
        // ⏰ 使用时机：应用适配器后设置监听器
        spinner.setOnItemSelectedListener(this);

        // 🔄 更新界面控件状态
        // 💡 作用：根据当前状态设置按钮文本和启用状态
        // ⏰ 使用时机：所有初始化完成后更新UI
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
        Log.d(TAG, "PlayMovieActivity onResume");
        // 📞 调用父类的onResume方法（为什么：确保父类的恢复逻辑正常执行）
        // 💡 作用：执行Android基类的标准恢复流程
        // ⏰ 使用时机：记录日志后调用，遵循先自定义后父类的顺序
        super.onResume();
    }

    /**
     * ⏸️ Activity暂停时调用
     * 💡 当用户离开Activity时会调用此方法
     */
    @Override
    protected void onPause() {
        // 📝 记录日志
        // 💡 作用：输出"onPause"事件到Logcat
        // ⏰ 使用时机：Activity暂停时首先调用
        Log.d(TAG, "PlayMovieActivity onPause");
        // 📞 调用父类的onPause方法
        // 💡 作用：执行Android基类的标准暂停流程
        // ⏰ 使用时机：记录日志后调用
        super.onPause();
        
        /**
         * We're not keeping track of the state in static fields, so we need to shut the
         * playback down.  Ideally we'd preserve the state so that the player would continue
         * after a device rotation.
         *
         * We want to be sure that the player won't continue to send frames after we pause,
         * because we're tearing the view down.  So we wait for it to stop here.
         * 
         * 💡 我们没有在静态字段中保存状态，所以需要关闭播放。
         *    理想情况下，我们应该保存状态，以便设备旋转后播放器能继续播放。
         * 💡 我们要确保播放器在暂停后不会继续发送帧，因为我们正在销毁视图。
         *    所以我们在这里等待它停止。
         */
        
        // 🔍 检查是否有播放任务正在运行（为什么：避免在没有播放时执行无意义的停止操作）
        // 💡 作用：判断当前是否有视频在播放
        // ⏰ 使用时机：暂停时首先检查，防止空指针异常
        if (mPlayTask != null) {
            // ⏹️ 停止播放（为什么：Activity暂停时需要停止视频播放）
            // 💡 作用：请求MoviePlayer停止解码和渲染
            // ⏰ 使用时机：检测到有播放任务后立即停止
            stopPlayback();
            // ⏳ 等待播放完全停止（为什么：防止播放线程在视图销毁后继续发送帧）
            // 💡 作用：阻塞当前线程直到播放线程完全退出
            // ⏰ 使用时机：请求停止后必须等待，避免后续销毁视图时崩溃
            mPlayTask.waitForStop();
        }
    }

    /**
     * 🖼️ SurfaceTexture可用时调用
     * 💡 这个回调在TextureView初始化完成后触发
     * 💡 作用：标记SurfaceTexture已就绪，启用播放按钮
     * 💡 原因：Activity启动和TextureView初始化之间有短暂延迟
     *
     * @param st SurfaceTexture对象（TextureView的渲染表面）
     * @param width SurfaceTexture宽度（像素）
     * @param height SurfaceTexture高度（像素）
     */
    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        /**
         * There's a short delay between the start of the activity and the initialization
         * of the SurfaceTexture that backs the TextureView.  We don't want to try to
         * send a video stream to the TextureView before it has initialized, so we disable
         * the "play" button until this callback fires.
         * 
         * 💡 Activity启动和TextureView的SurfaceTexture初始化之间有短暂延迟。
         *    我们不希望在初始化完成之前尝试向TextureView发送视频流，
         *    所以禁用"播放"按钮，直到这个回调触发。
         */
        
        // 📝 记录SurfaceTexture就绪的日志
        Log.d(TAG, "SurfaceTexture ready (" + width + "x" + height + ")");
        // ✅ 标记SurfaceTexture已就绪
        mSurfaceTextureReady = true;
        // 🔄 更新界面控件状态
        updateControls();
    }

    /**
     * 🖼️ SurfaceTexture大小改变时调用
     * 💡 当TextureView的SurfaceTexture尺寸发生改变时触发
     * 💡 作用：宽高比调整在adjustAspectRatio()中处理，此处不需要额外操作
     *
     * @param st SurfaceTexture对象（大小发生改变的纹理）
     * @param width 新的SurfaceTexture宽度（像素）
     * @param height 新的SurfaceTexture高度（像素）
     */
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        // 🚫 忽略大小改变事件（为什么：宽高比调整已在adjustAspectRatio()中处理）
        // 💡 作用：实现SurfaceTextureListener接口的必需方法
        // ⏰ 使用时机：SurfaceTexture尺寸改变时调用
        // 💡 此处留空，不需要特殊处理
        // ignore
    }

    /**
     * 🖼️ SurfaceTexture销毁时调用
     * 💡 当TextureView的SurfaceTexture即将被销毁时触发
     * 💡 作用：标记SurfaceTexture未就绪，防止播放器继续发送帧
     *
     * @param st 即将销毁的SurfaceTexture对象
     * @return true表示调用者应该释放SurfaceTexture
     */
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        // ❌ 标记SurfaceTexture未就绪（为什么：SurfaceTexture销毁后不能接收视频帧）
        // 💡 作用：更新状态标记，使播放按钮变为禁用状态
        // ⏰ 使用时机：SurfaceTexture即将销毁时首先设置
        mSurfaceTextureReady = false;
        // 💡 假设Activity正在暂停，所以不需要更新控件
        // assume activity is pausing, so don't need to update controls.
        // 💡 原因：通常onPause()会先调用并停止播放，此处不需要额外操作
        return true;    // caller should release ST
    }

    /**
     * 🖼️ SurfaceTexture更新时调用（每帧触发）
     * 💡 TextureView每渲染一帧都会触发此回调
     * 💡 作用：可用于实时处理帧数据（如图像分析、滤镜处理等）
     *
     * @param surface 正在更新的SurfaceTexture对象
     */
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        // 🚫 忽略更新事件（为什么：不需要处理每帧更新）
        // 💡 作用：实现SurfaceTextureListener接口的必需方法
        // ⏰ 使用时机：每帧渲染完成后调用
        // 💡 如需处理帧数据可在此添加逻辑
        // ignore
    }

    /*
     * Called when the movie Spinner gets touched.
     * 
     * 🎯 当视频下拉列表被选择时调用
     * 💡 当用户在Spinner中选择不同的视频文件时触发
     * 💡 作用：更新当前选中的视频文件索引
     * 
     * @param parent 父视图（Spinner下拉列表）
     * @param view 被选择的视图项
     * @param pos 选择的位置索引
     * @param id 选择的行ID
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
     * onClick handler for "play"/"stop" button.
     * 
     * 🎬 "播放"/"停止"按钮的点击事件处理方法
     * 💡 根据当前状态切换播放和停止
     * 
     * @param unused 未使用的参数
     */
    public void clickPlayStop(@SuppressWarnings("unused") View unused) {
        // 🔍 检查当前是否显示"停止"标签
        // 💡 为什么判断：根据按钮文本判断当前是播放中还是已停止
        // 💡 作用：决定执行停止操作还是开始播放
        if (mShowStopLabel) {
            // 📝 记录停止播放的日志
            // 💡 作用：便于调试播放停止流程
            Log.d(TAG, "stopping movie");

            // ⏹️ 停止播放
            // 💡 作用：请求MoviePlayer停止解码和渲染
            stopPlayback();

            // 🚫 不在这里更新控件——让任务线程在视频实际停止后更新
            // Don't update the controls here -- let the task thread do it after the movie has
            // actually stopped.
            //mShowStopLabel = false;
            //updateControls();
        } else {
            // 🔍 检查是否已经有播放任务在运行
            // 💡 为什么检查：防止重复启动播放任务
            if (mPlayTask != null) {
                // ⚠️ 记录警告日志
                // 💡 作用：提示开发者或用户已有播放任务
                Log.w(TAG, "movie already playing");
                return;
            }

            // 📝 记录开始播放的日志
            // 💡 作用：便于调试播放启动流程
            Log.d(TAG, "starting movie");

            // 🎬 callback - 速度控制回调对象
            // 💡 为什么定义：MoviePlayer需要回调来控制播放速度
            // 💡 作用：可以设置固定帧率或自由运行模式
            // ⏰ 使用时机：创建MoviePlayer时传入
            SpeedControlCallback callback = new SpeedControlCallback();

            // 🔍 检查是否启用了60fps锁定
            // 💡 作用：如果用户勾选了60fps锁定，设置固定播放速率
            if (((CheckBox) findViewById(R.id.locked60fps_checkbox)).isChecked()) {
                // 📝 TODO: 考虑将其改为"自由运行"模式
                // TODO: consider changing this to be "free running" mode
                // 🔄 设置固定播放速率为60fps
                // 💡 作用：锁定视频以60fps播放，忽略原始帧率
                callback.setFixedPlaybackRate(60);
            }

            // 🖼️ st - SurfaceTexture对象
            // 💡 为什么定义：需要获取TextureView的SurfaceTexture来创建Surface
            // 💡 作用：作为视频帧的输出目标
            // ⏰ 使用时机：创建Surface时使用
            SurfaceTexture st = mTextureView.getSurfaceTexture();

            // 🖼️ surface - Surface对象
            // 💡 为什么定义：MoviePlayer需要Surface来渲染解码后的视频帧
            // 💡 作用：作为MediaCodec的输出Surface
            // ⏰ 使用时机：创建MoviePlayer时传入
            Surface surface = new Surface(st);

            // 🎬 player - MoviePlayer实例
            // 💡 为什么定义：需要MoviePlayer来解码和播放视频文件
            // 💡 作用：控制视频播放的核心对象
            // ⏰ 使用时机：try块中创建，后续用于调整宽高比和创建播放任务
            MoviePlayer player = null;

            try {
                 // 🎬 创建MoviePlayer实例
                 // 💡 作用：初始化视频解码器，准备播放指定文件
                 // 💡 参数：视频文件路径、输出Surface、速度控制回调
                 player = new MoviePlayer(
                        new File(getFilesDir(), mMovieFiles[mSelectedMovie]), surface, callback);
            } catch (IOException ioe) {
                // ❌ 记录错误日志
                // 💡 作用：捕获文件读取或解码器初始化错误
                Log.e(TAG, "Unable to play movie", ioe);

                // 🗑️ 释放Surface资源
                // 💡 作用：避免资源泄漏
                surface.release();
                return;
            }

            // 📐 调整宽高比
            // 💡 作用：根据视频实际尺寸调整TextureView的显示比例
            // 💡 参数：从解码器获取的视频宽度和高度
            adjustAspectRatio(player.getVideoWidth(), player.getVideoHeight());

            // 🎬 mPlayTask - 播放任务对象
            // 💡 为什么赋值：需要保存播放任务引用，用于后续停止操作
            // 💡 作用：在后台线程执行视频播放
            // ⏰ 使用时机：MoviePlayer创建后创建，用于启动和停止播放
            mPlayTask = new MoviePlayer.PlayTask(player, this);

            // 🔍 检查是否启用了循环播放
            // 💡 作用：如果用户勾选了循环播放，设置循环模式
            if (((CheckBox) findViewById(R.id.loopPlayback_checkbox)).isChecked()) {
                // 🔄 设置循环模式
                // 💡 作用：视频播放结束后自动重新开始
                mPlayTask.setLoopMode(true);
            }

            // 🏷️ mShowStopLabel - 显示停止标签标志
            // 💡 为什么设置为true：标记当前处于播放状态，按钮应显示"停止"
            // 💡 作用：控制按钮文本和后续操作（停止vs播放）
            // ⏰ 使用时机：播放任务创建后设置
            mShowStopLabel = true;

            // 🔄 更新控件状态
            // 💡 作用：将按钮文本改为"停止"，禁用设置控件
            updateControls();

            // ▶️ 执行播放任务
            // 💡 作用：启动后台线程开始播放视频
            // ⏰ 使用时机：所有准备工作完成后最后调用
            mPlayTask.execute();
        }
    }

    /**
     * Requests stoppage if a movie is currently playing.  Does not wait for it to stop.
     * 
     * ⏹️ 请求停止视频播放（如果正在播放）
     * 💡 不会等待播放完全停止
     */
    private void stopPlayback() {
        // 🔍 检查是否有播放任务
        if (mPlayTask != null) {
            // ⏹️ 请求停止播放
            mPlayTask.requestStop();
        }
    }

    /**
     * 🎬 视频播放停止时的回调方法（实现MoviePlayer.PlayerFeedback接口）
     */
    @Override   // MoviePlayer.PlayerFeedback
    public void playbackStopped() {
        // 📝 记录播放停止的日志
        Log.d(TAG, "playback stopped");
        // ❌ 不显示停止标签
        mShowStopLabel = false;
        // 🗑️ 清空播放任务引用
        mPlayTask = null;
        // 🔄 更新控件状态
        updateControls();
    }

    /**
     * Sets the TextureView transform to preserve the aspect ratio of the video.
     * 
     * 📐 设置TextureView的变换以保持视频的宽高比
     * 💡 根据视频和视图的尺寸计算缩放和偏移
     * 
     * @param videoWidth 视频宽度
     * @param videoHeight 视频高度
     */
    private void adjustAspectRatio(int videoWidth, int videoHeight) {
        // 📐 viewWidth - TextureView的当前宽度（像素）
        // 💡 为什么定义：需要获取视图尺寸来计算视频应显示的区域大小
        // 💡 作用：作为计算缩放比例的基准
        // ⏰ 使用时机：后续计算缩放比例和偏移量时使用
        int viewWidth = mTextureView.getWidth();

        // 📐 viewHeight - TextureView的当前高度（像素）
        // 💡 为什么定义：需要获取视图尺寸来计算视频应显示的区域大小
        // 💡 作用：作为计算缩放比例的基准
        // ⏰ 使用时机：后续计算缩放比例和偏移量时使用
        int viewHeight = mTextureView.getHeight();

        // 📊 aspectRatio - 视频的宽高比（高度/宽度）
        // 💡 为什么定义：需要保持视频原始比例，防止拉伸变形
        // 💡 作用：用于判断是宽度限制还是高度限制，以及计算缩放后的尺寸
        // ⏰ 使用时机：在if判断和newWidth/newHeight计算时使用
        double aspectRatio = (double) videoHeight / videoWidth;

        // 📐 newWidth - 缩放后的新宽度
        // 💡 为什么定义：需要计算视频在视图中应该显示的实际宽度
        // 💡 作用：用于创建变换矩阵，设置水平缩放比例
        // ⏰ 使用时机：在if/else分支中计算后，用于设置Matrix缩放和偏移
        int newWidth;

        // 📐 newHeight - 缩放后的新高度
        // 💡 为什么定义：需要计算视频在视图中应该显示的实际高度
        // 💡 作用：用于创建变换矩阵，设置垂直缩放比例
        // ⏰ 使用时机：在if/else分支中计算后，用于设置Matrix缩放和偏移
        int newHeight;

        // 🔍 判断是宽度限制还是高度限制
        // 💡 为什么判断：不同的限制方向需要不同的缩放策略
        if (viewHeight > (int) (viewWidth * aspectRatio)) {
            // limited by narrow width; restrict height
            // 💡 宽度限制：视图相对较宽，视频需要按宽度适配
            // 💡 作用：保持宽度不变，按比例调整高度
            newWidth = viewWidth;
            newHeight = (int) (viewWidth * aspectRatio);
        } else {
            // limited by short height; restrict width
            // 💡 高度限制：视图相对较窄，视频需要按高度适配
            // 💡 作用：保持高度不变，按比例调整宽度
            newWidth = (int) (viewHeight / aspectRatio);
            newHeight = viewHeight;
        }

        // 📊 xoff - 水平偏移量（像素）
        // 💡 为什么定义：视频缩放后需要居中显示，不能贴边
        // 💡 作用：将视频在水平方向上居中对齐
        // ⏰ 使用时机：在Matrix.postTranslate()中使用
        int xoff = (viewWidth - newWidth) / 2;

        // 📊 yoff - 垂直偏移量（像素）
        // 💡 为什么定义：视频缩放后需要居中显示，不能贴边
        // 💡 作用：将视频在垂直方向上居中对齐
        // ⏰ 使用时机：在Matrix.postTranslate()中使用
        int yoff = (viewHeight - newHeight) / 2;

        // 📝 记录调试日志
        // 💡 作用：便于调试宽高比调整逻辑
        Log.v(TAG, "video=" + videoWidth + "x" + videoHeight +
                " view=" + viewWidth + "x" + viewHeight +
                " newView=" + newWidth + "x" + newHeight +
                " off=" + xoff + "," + yoff);

        // 📐 txform - 变换矩阵对象
        // 💡 为什么定义：Android的Matrix类用于执行2D变换（缩放、平移、旋转）
        // 💡 作用：存储缩放和平移变换，应用到TextureView
        // ⏰ 使用时机：设置缩放和平移后，应用到TextureView
        Matrix txform = new Matrix();

        // 📊 获取TextureView当前的变换矩阵
        // 💡 作用：初始化txform为当前变换状态
        // ⏰ 使用时机：创建Matrix后立即获取当前变换
        mTextureView.getTransform(txform);

        // 📊 设置缩放比例
        // 💡 作用：将视图尺寸缩放到视频实际显示尺寸
        // 💡 参数：(float) newWidth / viewWidth = 水平缩放比例
        // 💡 参数：(float) newHeight / viewHeight = 垂直缩放比例
        // ⏰ 使用时机：获取当前变换后设置缩放
        txform.setScale((float) newWidth / viewWidth, (float) newHeight / viewHeight);

        // 🔄 可以添加旋转（仅用于测试）
        //txform.postRotate(10);          // just for fun

        // 📊 设置平移（居中显示）
        // 💡 作用：将缩放后的视频移动到视图中心
        // 💡 参数：xoff = 水平偏移，yoff = 垂直偏移
        // ⏰ 使用时机：设置缩放后设置平移
        txform.postTranslate(xoff, yoff);

        // 🎨 应用变换到TextureView
        // 💡 作用：将计算好的变换矩阵应用到视图，完成宽高比调整
        // ⏰ 使用时机：所有变换设置完成后最后调用
        mTextureView.setTransform(txform);
    }

    /**
     * Updates the on-screen controls to reflect the current state of the app.
     * 
     * 🔄 更新屏幕控件以反映应用的当前状态
     */
    private void updateControls() {
        // 🔘 play - 播放/停止按钮的引用
        // 💡 为什么定义：需要获取按钮控件来更新其文本和启用状态
        // 💡 作用：存储按钮引用，用于后续设置文本和启用/禁用
        // ⏰ 使用时机：在方法开头获取，后续操作都使用此引用
        Button play = (Button) findViewById(R.id.play_stop_button);

        // 🔍 根据播放状态设置按钮文本
        // 💡 为什么判断：播放中显示"停止"，停止时显示"播放"
        if (mShowStopLabel) {
            // 🛑 显示"停止"文本
            // 💡 作用：告知用户当前是播放状态，点击可停止
            play.setText(R.string.stop_button_text);
        } else {
            // ▶️ 显示"播放"文本
            // 💡 作用：告知用户当前是停止状态，点击可播放
            play.setText(R.string.play_button_text);
        }

        // 🔍 根据SurfaceTexture状态启用/禁用按钮
        // 💡 作用：只有SurfaceTexture准备就绪才能播放视频
        // 💡 时机：设置文本后设置启用状态
        play.setEnabled(mSurfaceTextureReady);

        // We don't support changes mid-play, so dim these.
        // 💡 不支持在播放中更改设置，所以禁用这些控件

        // ☑️ check - 复选框控件的引用
        // 💡 为什么定义：需要获取复选框来更新其启用状态
        // 💡 作用：存储复选框引用，用于后续启用/禁用
        // ⏰ 使用时机：获取后设置启用状态，然后复用获取下一个复选框
        CheckBox check = (CheckBox) findViewById(R.id.locked60fps_checkbox);

        // 🔄 根据播放状态启用/禁用60fps锁定复选框
        // 💡 作用：播放中不允许更改帧率锁定设置
        // 💡 参数：!mShowStopLabel = 未播放时启用，播放中禁用
        check.setEnabled(!mShowStopLabel);

        // ☑️ 获取循环播放复选框
        // 💡 作用：复用check变量，指向循环播放复选框
        check = (CheckBox) findViewById(R.id.loopPlayback_checkbox);

        // 🔄 根据播放状态启用/禁用循环播放复选框
        // 💡 作用：播放中不允许更改循环播放设置
        // 💡 参数：!mShowStopLabel = 未播放时启用，播放中禁用
        check.setEnabled(!mShowStopLabel);
    }
}
