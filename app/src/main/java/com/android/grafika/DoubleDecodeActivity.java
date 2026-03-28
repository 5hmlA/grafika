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
import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import java.io.File;
import java.io.IOException;
import com.google.grafika.R;

/**
 * Decodes two video streams simultaneously to two TextureViews.
 * <p>
 * One key feature is that the video decoders do not stop when the activity is restarted due
 * to an orientation change.  This is to simulate playback of a real-time video stream.
 * <p>
 * TODO: consider shutting down when the screen is turned off, to preserve battery.
 *
 * 🎬 同时解码两个视频流到两个TextureView
 * 💡 关键特性：屏幕旋转时解码器不会停止，模拟实时视频流播放
 * 📱 使用静态存储保持解码器在Activity重启时存活
 * 🔄 TextureView.SurfaceTextureListener管理Surface生命周期
 */
public class DoubleDecodeActivity extends Activity {
    private static final String TAG = MainActivity.TAG;
    private static final int VIDEO_COUNT = 2;  // 🎬 视频数量

    // ⚠️ 必须是静态存储，这样才能在Activity重启时存活
    private static boolean sVideoRunning = false;    // 🎬 视频是否正在运行
    private static VideoBlob[] sBlob = new VideoBlob[VIDEO_COUNT];  // 📦 视频播放块数组

    // 🎯 Activity创建时初始化两个视频播放块（共48行，需逐行注释）
    // 🔧 为什么：初始化两个视频解码器，将视频解码到TextureView
    // 📍 时机：Activity首次创建时由系统调用
    // 💡 关键：使用静态变量sVideoRunning区分"首次创建"和"Activity重建"
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 📞 super.onCreate(savedInstanceState): 调用父类onCreate
        // 💡 为什么调用：必须执行系统级Activity初始化（恢复状态等）
        // 💡 作用：完成标准的Activity创建流程，恢复之前保存的状态
        // 💡 时机：自定义初始化前必须调用
        super.onCreate(savedInstanceState);

        // 🖥️ setContentView(): 设置布局文件
        // 💡 为什么调用：需要加载XML布局，建立View层级关系
        // 💡 作用：将activity_double_decode.xml渲染到屏幕
        // 💡 时机：onCreate中最先调用（在findViewById之前）
        setContentView(R.layout.activity_double_decode);

        // 🔍 sVideoRunning: 静态标志，记录视频播放器是否已创建
        // 💡 为什么定义为静态：Activity旋转重建时，静态变量不会被销毁
        // 💡 作用：区分"首次创建"和"Activity重建"两种场景
        // 💡 时机：在onCreate中判断，决定是新建VideoBlob还是复用
        if (!sVideoRunning) {
            // 🆕 首次创建：初始化两个视频播放块
            // 📦 sBlob[0]: 第一个视频播放块（VideoBlob实例）
            // 💡 为什么创建：需要封装视频解码器、TextureView和播放线程
            // 💡 作用：关联TextureView和视频文件（Sliders），管理播放生命周期
            // 💡 时机：首次创建时初始化，后续Activity重建不再新建
            // 💡 参数1 TextureView：视频渲染的控件
            // 💡 参数2 MOVIE_SLIDERS：视频标签常量，ContentManager根据此标签获取文件路径
            // 💡 参数3 0：实例序号，用于日志区分不同VideoBlob
            sBlob[0] = new VideoBlob((TextureView) findViewById(R.id.double1_texture_view),
                    ContentManager.MOVIE_SLIDERS, 0);

            // 📦 sBlob[1]: 第二个视频播放块
            // 💡 为什么创建：需要同时播放两个不同的视频
            // 💡 作用：关联TextureView和视频文件（EightRects）
            // 💡 时机：与sBlob[0]同时初始化
            sBlob[1] = new VideoBlob((TextureView) findViewById(R.id.double2_texture_view),
                    ContentManager.MOVIE_EIGHT_RECTS, 1);

            // 📝 sVideoRunning: 标记视频已启动
            // 💡 为什么设置：防止Activity重建时重复创建VideoBlob
            // 💡 作用：记录状态，下次onCreate进入else分支复用已有实例
            // 💡 时机：两个播放块创建完成后设置
            sVideoRunning = true;
        } else {
            // 🔄 Activity重建：重新关联TextureView
            // 💡 为什么进入此分支：屏幕旋转导致Activity重建，但解码器仍存活在静态变量中
            // 💡 作用：将新的TextureView控件关联到已有的VideoBlob
            // 💡 时机：Activity旋转重建时

            // 🔄 sBlob[0].recreateView(): 重建第一个TextureView关联
            // 💡 为什么调用：旧的TextureView已被销毁，需要绑定新的控件
            // 💡 作用：将新的TextureView传给VideoBlob，内部会复用保存的SurfaceTexture
            // 💡 时机：Activity重建时复用已有播放块
            sBlob[0].recreateView((TextureView) findViewById(R.id.double1_texture_view));

            // 🔄 sBlob[1].recreateView(): 重建第二个TextureView关联
            // 💡 为什么调用：同上，第二个视频播放块也需要重新关联控件
            // 💡 作用：屏幕旋转后重新绑定控件，保留解码器和SurfaceTexture
            // 💡 时机：Activity重建时复用已有播放块
            sBlob[1].recreateView((TextureView) findViewById(R.id.double2_texture_view));
        }
    }

    /**
     * ⏸️ Activity暂停回调（共43行，需逐行注释）
     * 💡 作用：检查Activity是否正在结束，决定是否释放视频播放资源
     * 💡 时机：Activity进入后台时由系统调用
     * 💡 关键逻辑：屏幕旋转时isFinishing()=false，解码器不释放；按返回键时isFinishing()=true，释放资源
     */
    @Override
    protected void onPause() {
        // 📞 super.onPause(): 调用父类onPause
        // 💡 为什么调用：必须执行系统级暂停逻辑（保存状态等）
        // 💡 作用：完成标准的Activity暂停流程
        // 💡 时机：自定义暂停逻辑前必须调用
        super.onPause(); // 📞 调用父类onPause，执行系统级暂停逻辑
        // 🔍 isFinishing(): 查询Activity是否即将被销毁
        // 💡 finishing变量作用：区分"暂时暂停"和"彻底结束"
        // 💡 为什么区分：暂时暂停（如按Home键）不解码器，彻底结束（如按返回键）才释放
        // 💡 时机：仅在onPause中用于判断是否执行清理操作
        // 💡 注意：屏幕旋转时isFinishing()返回false，因此解码器不会被停止
        boolean finishing = isFinishing(); // 📞 查询Activity是否即将被销毁
        // 📝 Log.d(): 记录Activity结束状态
        // 💡 为什么记录：便于调试，确认屏幕旋转时isFinishing的值
        // 💡 作用：在logcat中查看Activity生命周期状态
        // 💡 TAG变量作用：日志过滤标签，值为"MainActivity"
        // 💡 时机：onPause开始时记录
        Log.d(TAG, "isFinishing: " + finishing); // 📝 记录Activity结束状态，便于调试
        // 🔄 for循环：遍历所有视频播放块
        // 💡 i变量作用：循环索引，从0到VIDEO_COUNT-1（即0到1）
        // 💡 为什么遍历：需要检查并停止每个视频的播放
        // 💡 时机：onPause中检查finishing标志后执行
        for (int i = 0; i < VIDEO_COUNT; i++) {
            // 🔍 检查Activity是否正在结束
            // 💡 finishing变量作用：是否需要释放资源
            if (finishing) {
                // ⏹️ sBlob[i].stopPlayback(): 停止视频播放
                // 💡 为什么调用：Activity结束时需要释放解码器和Surface资源
                // 💡 作用：请求播放线程停止，并清理SurfaceTexture引用
                // 💡 时机：Activity被销毁时调用
                // 💡 注意：这是异步停止，线程不会立即结束
                sBlob[i].stopPlayback();
                // 🔚 sBlob[i] = null: 置空引用
                // 💡 为什么置空：解除静态数组对VideoBlob的引用，允许GC回收
                // 💡 作用：允许垃圾回收器回收VideoBlob对象，释放内存
                // 💡 时机：停止播放后立即置空
                // 💡 注意：必须置空，否则静态数组会永久持有对象引用
                sBlob[i] = null;
            }
        }
        // 🔄 sVideoRunning: 更新视频运行状态标志
        // 💡 为什么更新：需要记录视频是否仍在运行
        // 💡 作用：下次onCreate时判断是否需要重新创建VideoBlob
        // 💡 时机：onPause结束时更新状态
        // 💡 逻辑：如果Activity结束则设为false，否则保持true（屏幕旋转场景）
        sVideoRunning = !finishing;  // 📝 更新运行状态
    }

    /**
     * Video playback blob. Encapsulates the video decoder and playback surface.
     * 
     * 🎬 视频播放块：封装视频解码器和播放Surface
     * 💡 避免在屏幕旋转时重建解码器（代价高昂）
     */
    private static class VideoBlob implements TextureView.SurfaceTextureListener {
        // 🏷️ 日志标签（带序号区分不同实例）
        private final String LTAG;
        private TextureView mTextureView;        // 🖥️ TextureView视图
        private int mMovieTag;                   // 🎬 视频标签
        private SurfaceTexture mSavedSurfaceTexture;  // 💾 保存的SurfaceTexture（跨Activity存活）
        private PlayMovieThread mPlayThread;     // 🧵 播放线程
        private SpeedControlCallback mCallback;  // ⏱️ 速度控制回调

        /**
         * VideoBlob构造函数
         * 💡 作用：初始化视频播放块，关联TextureView和视频文件
         * 💡 使用时机：DoubleDecodeActivity.onCreate()中首次创建时调用
         * 
         * @param view TextureView视图控件
         * @param movieTag 视频标签（ContentManager.MOVIE_SLIDERS或MOVIE_EIGHT_RECTS）
         * @param ordinal 实例序号（用于日志区分不同实例）
         */
        public VideoBlob(TextureView view, int movieTag, int ordinal) {
            // 🏷️ LTAG: 日志标签（带序号）
            //    为什么定义：区分两个VideoBlob实例的日志输出
            //    作用：格式为"DoubleDecode0"或"DoubleDecode1"
            //    使用时机：Log.d(LTAG, ...)中记录调试信息
            LTAG = TAG + ordinal;
            // 🎬 mMovieTag: 视频标签
            //    为什么定义：需要知道播放哪个视频文件
            //    作用：存储ContentManager中的视频标签常量
            //    使用时机：onSurfaceTextureAvailable()中获取视频文件路径
            mMovieTag = movieTag;
            // ⏱️ mCallback: 速度控制回调
            //    为什么定义：控制视频播放速度，避免过快或过慢
            //    作用：管理帧间延迟，实现稳定帧率播放
            //    使用时机：传入PlayMovieThread构造函数
            mCallback = new SpeedControlCallback();
            // 🔄 recreateView(): 重建视图关联
            //    为什么调用：统一初始化逻辑，设置TextureView和监听器
            //    作用：注册SurfaceTextureListener，检查是否有保存的SurfaceTexture
            //    使用时机：构造函数最后调用，完成视图初始化
            recreateView(view);
        }

        /**
         * Recreates the view, using the old SurfaceTexture (if available).
         *
         * 🔄 重建视图（Activity重建后调用）
         * 如果有保存的SurfaceTexture，直接设置给新的TextureView
         *
         * 💡 方法作用：在Activity重建后重新关联TextureView，保持解码器运行
         * 💡 参数view：新的TextureView实例（Activity重建后创建的新控件）
         * 💡 返回值：无（void）
         * 💡 时机：构造函数和Activity重建时调用
         */
        public void recreateView(TextureView view) {
            // 📝 mTextureView: VideoBlob持有的TextureView引用
            //    为什么更新：Activity重建后旧的TextureView已销毁，需要指向新实例
            //    作用：保存TextureView引用，后续通过它设置SurfaceTexture
            //    使用时机：Activity重建后传入新的TextureView时更新
            mTextureView = view;

            // 📝 setSurfaceTextureListener(): 注册SurfaceTexture生命周期监听器
            //    为什么注册：需要监听SurfaceTexture的创建/销毁事件来管理解码器
            //    作用：当SurfaceTexture可用时触发onSurfaceTextureAvailable，启动播放
            //    使用时机：TextureView引用更新后立即注册
            //    注意：必须注册，否则无法感知SurfaceTexture状态变化
            mTextureView.setSurfaceTextureListener(this);

            // 📝 mSavedSurfaceTexture: 保存的SurfaceTexture（跨Activity存活）
            //    为什么检查：如果之前保存了SurfaceTexture（Activity重建场景），可以复用
            //    作用：避免重新创建解码器和SurfaceTexture，保持播放连续性
            //    使用时机：Activity旋转后检查是否有保存的SurfaceTexture
            if (mSavedSurfaceTexture != null) {
                // 📝 setSurfaceTexture(): 将保存的SurfaceTexture绑定到新控件
                //    为什么调用：TextureView默认会创建新的SurfaceTexture，需要替换为保存的
                //    作用：复用之前的SurfaceTexture，解码器继续输出到同一Surface
                //    使用时机：存在保存的SurfaceTexture时设置
                //    注意：这会触发onSurfaceTextureAvailable()回调
                view.setSurfaceTexture(mSavedSurfaceTexture);
            }
        }

        /**
         * Stops playback.
         *
         * ⏹️ 停止播放并清理资源
         *
         * 💡 方法作用：停止视频播放线程并清理SurfaceTexture引用
         * 💡 返回值：无（void）
         * 💡 时机：Activity被销毁（isFinishing()=true）时由onPause()调用
         */
        public void stopPlayback() {
            // 📝 requestStop(): 请求播放线程停止
            //    为什么调用：Activity销毁时必须停止解码线程，否则内存泄漏
            //    作用：设置停止标志，播放线程在下次循环检查后退出
            //    使用时机：Activity即将销毁时调用
            //    注意：这是异步停止，线程不会立即结束
            mPlayThread.requestStop();

            // 📝 mSavedSurfaceTexture: 置空保存的SurfaceTexture引用
            //    为什么置空：Activity销毁后不需要再保留SurfaceTexture
            //    作用：允许GC回收SurfaceTexture对象，释放native资源
            //    使用时机：停止播放后立即置空
            //    注意：置空后下次onSurfaceTextureDestroyed()会返回true，允许销毁
            mSavedSurfaceTexture = null;
        }

        // 🎬 SurfaceTexture可用时启动播放线程
        // 🔍 为什么需要：SurfaceTexture是视频帧的渲染目标，可用后才能创建播放线程
        // 📍 作用：首次创建时保存SurfaceTexture并启动解码；重建时复用已有SurfaceTexture
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
            // 🔍 mSavedSurfaceTexture变量：保存的SurfaceTexture引用（跨Activity存活）
            // 🔍 为什么检查null：区分"首次创建"和"Activity重建后复用"两种场景
            // 📍 作用：如果为null说明是首次创建，需要保存并新建播放线程
            // ⏰ 时机：回调触发时立即判断
            if (mSavedSurfaceTexture == null) {
                // 🆕 首次可用：保存SurfaceTexture并创建播放线程
                // 💾 mSavedSurfaceTexture = st：保存SurfaceTexture引用
                // 🔍 为什么保存：跨Activity重建复用，避免重新创建解码器（代价高昂）
                // 📍 作用：持有SurfaceTexture引用，Activity重建后通过recreateView()复用
                // ⏰ 时机：首次onSurfaceTextureAvailable()触发时保存
                mSavedSurfaceTexture = st;

                // 📁 sliders变量：视频文件路径（File对象）
                // 🔍 为什么获取：播放线程需要知道解码哪个视频文件
                // 📍 作用：根据mMovieTag从ContentManager获取对应的视频文件路径
                // ⏰ 时机：创建PlayMovieThread之前获取
                File sliders = ContentManager.getInstance().getPath(mMovieTag);

                // 🧵 mPlayThread变量：视频播放线程（PlayMovieThread实例）
                // 🔍 为什么创建：视频解码和播放需要在独立线程执行，避免阻塞UI线程
                // 📍 作用：封装MoviePlayer，持续从文件解码帧并输出到Surface
                // ⏰ 时机：Surface首次可用时创建，构造函数中自动start()线程
                // 📌 参数1 sliders：视频文件路径
                // 📌 参数2 new Surface(st)：从SurfaceTexture创建的播放目标Surface
                // 📌 参数3 mCallback：速度控制回调，管理帧间延迟
                mPlayThread = new PlayMovieThread(sliders, new Surface(st), mCallback);
            }
        }

        // 📐 SurfaceTexture尺寸变化回调（无需处理）
        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {}

        // 💥 SurfaceTexture销毁回调
        // 🔍 为什么需要：系统在TextureView销毁时调用，询问是否允许销毁SurfaceTexture
        // 📍 作用：控制SurfaceTexture的生命周期，决定是否跨Activity复用
        // ⏰ 时机：Activity销毁或TextureView移除时由系统调用
        // 🔄 返回值：true=允许销毁，false=保留SurfaceTexture不销毁
        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
            // 🔍 返回是否允许销毁SurfaceTexture
            // 📌 mSavedSurfaceTexture变量：保存的SurfaceTexture引用
            // 🔍 为什么这样判断：如果为null，说明是Activity彻底结束（onPause中已置空），允许销毁
            //                    如果不为null，说明是Activity重建场景（如旋转屏幕），需要保留复用
            // 📍 作用：返回false时系统不会调用release()，SurfaceTexture继续存活
            // ⏰ 时机：系统请求销毁SurfaceTexture时返回此判断结果
            // 💡 注意：返回false后，下次Activity重建时recreateView()会复用此SurfaceTexture
            return (mSavedSurfaceTexture == null);
        }

        // 🔄 SurfaceTexture更新回调
        //    为什么需要：系统在SurfaceTexture内容更新时调用（每帧渲染后）
        //    作用：通知应用SurfaceTexture有新内容（本例中无需特殊处理）
        //    时机：每帧解码数据渲染到SurfaceTexture后由系统调用
        //    注意：本例使用独立的PlayMovieThread处理播放，无需在此回调中操作
        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture st) {}
    }

    /**
     * Thread object that plays a movie from a file to a surface.
     * 
     * 🎬 视频播放线程：从文件播放视频到Surface
     */
    private static class PlayMovieThread extends Thread {
        // 📁 视频文件路径
        private final File mFile;
        // 🖥️ 播放Surface
        private final Surface mSurface;
        // ⏱️ 速度控制回调
        private final SpeedControlCallback mCallback;
        // 🎬 视频播放器
        private MoviePlayer mMoviePlayer;

        public PlayMovieThread(File file, Surface surface, SpeedControlCallback callback) {
            mFile = file;
            mSurface = surface;
            mCallback = callback;
            start();  // 🚀 构造函数中直接启动线程
        }

        /**
         * Requests that the player stop.
         *
         * ⏹️ 请求停止播放
         */
        public void requestStop() {
            mMoviePlayer.requestStop();
        }

        // 🎬 线程运行入口：循环播放视频
        //    为什么需要：视频解码和播放需要在独立线程执行，避免阻塞UI线程
        //    作用：创建MoviePlayer，进入解码循环，持续输出帧到Surface
        //    时机：线程start()后自动调用，运行在独立的工作线程中
        //    注意：线程在构造函数中直接启动，run()退出后线程结束
        @Override
        public void run() {
            // 📝 try块：执行视频播放
            //    为什么用try：需要捕获IO异常（文件读取失败等），确保资源正确释放
            //    作用：包含整个播放流程，catch捕获异常，finally确保清理
            //    使用时机：线程运行时执行
            try {
                // 📝 mMoviePlayer: MoviePlayer视频播放器实例
                //    为什么创建：需要MoviePlayer来解码视频文件并输出到Surface
                //    作用：封装MediaCodec解码器，处理文件读取、解码、渲染
                //    使用时机：线程启动后立即创建
                //    参数1 mFile：视频文件路径（从构造函数传入）
                //    参数2 mSurface：播放目标Surface（从TextureView创建）
                //    参数3 mCallback：速度控制回调（从构造函数传入）
                mMoviePlayer = new MoviePlayer(mFile, mSurface, mCallback);

                // 📝 setLoopMode(true): 设置循环播放模式
                //    为什么调用：希望视频播放结束后自动重新开始
                //    作用：在MoviePlayer内部标记循环模式，播放结束后自动seek到开头
                //    使用时机：播放器创建后、play()之前设置
                //    注意：本例需要循环播放，模拟实时视频流效果
                mMoviePlayer.setLoopMode(true);  // 🔄 设置循环播放模式

                // 📝 play(): 开始播放视频
                //    为什么调用：进入解码循环，持续从文件解码帧并输出到Surface
                //    作用：阻塞方法，持续解码直到播放结束或被停止
                //    使用时机：配置完成后调用
                //    注意：这是阻塞调用，会持续运行直到requestStop()被调用或播放结束
                mMoviePlayer.play();
            } catch (IOException ioe) {
                // 📝 catch块：捕获IO异常
                //    为什么捕获：文件读取或解码过程中可能发生IO错误
                //    作用：记录错误日志，便于调试排查问题
                //    使用时机：播放过程中发生IO错误时触发
                //    常见错误：视频文件不存在、文件损坏、编码格式不支持等
                Log.e(TAG, "movie playback failed", ioe);
            } finally {
                // 📝 finally块：释放Surface资源
                //    为什么在finally中：无论播放成功或失败，都必须释放Surface
                //    作用：释放Surface占用的native资源，避免内存泄漏
                //    使用时机：try块或catch块执行完成后自动执行
                //    注意：即使发生异常也会执行，确保资源正确清理
                mSurface.release();  // 🧹 释放Surface资源
            }
        }
    }
}
